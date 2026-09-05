#!/usr/bin/env python3
"""Deterministic local performance acceptance for reads and reversible writes."""
from __future__ import annotations

import argparse
import json
import math
import os
import time
import urllib.error
import urllib.request
import uuid
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable


class RequestFailure(RuntimeError):
    """An HTTP or business response failed the acceptance contract."""


def request(
    method: str,
    url: str,
    *,
    body: dict[str, Any] | None = None,
    api_key: str = "",
    timeout: float = 10.0,
) -> tuple[float, dict[str, Any]]:
    encoded = json.dumps(body).encode("utf-8") if body is not None else None
    headers = {"Accept": "application/json"}
    if body is not None:
        headers["Content-Type"] = "application/json"
    if api_key:
        headers["X-API-Key"] = api_key
    req = urllib.request.Request(url, data=encoded, headers=headers, method=method)
    started = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as response:
            raw = response.read().decode("utf-8")
            status = response.status
    except urllib.error.HTTPError as error:
        raw = error.read().decode("utf-8", errors="replace")
        raise RequestFailure(f"{method} {url} returned HTTP {error.code}: {raw[:240]}") from error
    except (urllib.error.URLError, TimeoutError) as error:
        raise RequestFailure(f"{method} {url} failed: {error}") from error
    elapsed_ms = (time.perf_counter() - started) * 1000
    try:
        payload = json.loads(raw)
    except json.JSONDecodeError as error:
        raise RequestFailure(f"{method} {url} returned non-JSON data") from error
    if status != 200 or payload.get("resCode") != "200":
        raise RequestFailure(
            f"{method} {url} failed: HTTP {status}, resCode={payload.get('resCode')}, "
            f"msg={payload.get('msg')}"
        )
    return elapsed_ms, payload


def percentile(values: list[float], value: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    return ordered[max(0, math.ceil(value / 100 * len(ordered)) - 1)]


def discover_fixture(base: str, api_key: str, explicit_space_id: int | None) -> dict[str, Any]:
    _, response = request("GET", f"{base}/api/spaces", api_key=api_key)
    spaces = response.get("obj") or []
    if explicit_space_id is not None:
        matches = [space for space in spaces if int(space.get("id", -1)) == explicit_space_id]
    else:
        matches = [
            space for space in spaces
            if str(space.get("description", "")).startswith("Fixed evaluation dataset ")
        ]
    if not matches:
        expectation = f"space id {explicit_space_id}" if explicit_space_id is not None else "fixed evaluation space"
        raise RequestFailure(f"Unable to find {expectation}; run scripts/evaluate-mock.mjs first")
    fixture = max(matches, key=lambda space: int(space["id"]))
    _, documents = request("GET", f"{base}/api/spaces/{fixture['id']}/documents", api_key=api_key)
    document_items = documents.get("obj") or []
    if len(document_items) < 5:
        raise RequestFailure(
            f"Fixed fixture space {fixture['id']} has {len(document_items)} documents; expected at least 5"
        )
    return {
        "spaceId": int(fixture["id"]),
        "name": fixture.get("name"),
        "description": fixture.get("description"),
        "documentCount": len(document_items),
    }


def read_once(base: str, space_id: int, api_key: str) -> dict[str, float]:
    endpoints = {
        "health": "/api/health",
        "readiness": "/api/readiness",
        "space": f"/api/spaces/{space_id}",
        "documents": f"/api/spaces/{space_id}/documents",
        "sessions": f"/api/spaces/{space_id}/sessions",
    }
    timings: dict[str, float] = {}
    for name, path in endpoints.items():
        timings[f"{name}_ms"], _ = request("GET", f"{base}{path}", api_key=api_key)
    return timings


def write_once(base: str, space_id: int, api_key: str) -> dict[str, float]:
    title = f"perf-{uuid.uuid4().hex}"
    session_id: str | None = None
    timings: dict[str, float] = {}
    try:
        timings["session_create_ms"], created = request(
            "POST",
            f"{base}/api/spaces/{space_id}/sessions",
            body={"title": title},
            api_key=api_key,
        )
        session_id = str((created.get("obj") or {}).get("id") or "")
        if not session_id:
            raise RequestFailure("Session create response did not contain an id")
        timings["session_rename_ms"], renamed = request(
            "PUT",
            f"{base}/api/spaces/{space_id}/sessions/{session_id}",
            body={"title": f"{title}-renamed"},
            api_key=api_key,
        )
        if (renamed.get("obj") or {}).get("title") != f"{title}-renamed":
            raise RequestFailure(f"Session {session_id} rename was not persisted")
        timings["session_delete_ms"], _ = request(
            "DELETE",
            f"{base}/api/spaces/{space_id}/sessions/{session_id}",
            api_key=api_key,
        )
        session_id = None
        return timings
    finally:
        if session_id:
            try:
                request(
                    "DELETE",
                    f"{base}/api/spaces/{space_id}/sessions/{session_id}",
                    api_key=api_key,
                )
            except RequestFailure:
                pass


def parallel_run(
    operation: Callable[[], dict[str, float]], iterations: int, concurrency: int
) -> tuple[list[dict[str, float]], list[str]]:
    rows: list[dict[str, float]] = []
    errors: list[str] = []
    with ThreadPoolExecutor(max_workers=concurrency) as executor:
        futures = [executor.submit(operation) for _ in range(iterations)]
        for future in as_completed(futures):
            try:
                rows.append(future.result())
            except Exception as error:  # noqa: BLE001 - acceptance must report every failed operation
                errors.append(str(error))
    return rows, errors


def summarise(rows: list[dict[str, float]]) -> dict[str, dict[str, float]]:
    keys = sorted({key for row in rows for key in row})
    return {
        key.removesuffix("_ms"): {
            "p50Ms": round(percentile([row[key] for row in rows], 50), 2),
            "p95Ms": round(percentile([row[key] for row in rows], 95), 2),
            "maxMs": round(max(row[key] for row in rows), 2),
        }
        for key in keys
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:19050")
    parser.add_argument("--api-key", default=os.getenv("RAG_API_KEY", ""))
    parser.add_argument("--space-id", type=int)
    parser.add_argument("-n", "--iterations", type=int, default=30)
    parser.add_argument("-c", "--concurrency", type=int, default=4)
    parser.add_argument("--read-p95-ms", type=float, default=300.0)
    parser.add_argument("--write-p95-ms", type=float, default=800.0)
    parser.add_argument("--output")
    args = parser.parse_args()
    if args.iterations < 1 or args.concurrency < 1:
        parser.error("iterations and concurrency must both be positive")
    base = args.base_url.rstrip("/")

    try:
        fixture = discover_fixture(base, args.api_key, args.space_id)
        read_once(base, fixture["spaceId"], args.api_key)
        write_once(base, fixture["spaceId"], args.api_key)
    except RequestFailure as error:
        print(f"PERFORMANCE ACCEPTANCE FAILED during setup: {error}")
        return 1

    reads, read_errors = parallel_run(
        lambda: read_once(base, fixture["spaceId"], args.api_key), args.iterations, args.concurrency
    )
    writes, write_errors = parallel_run(
        lambda: write_once(base, fixture["spaceId"], args.api_key), args.iterations, args.concurrency
    )
    read_metrics = summarise(reads) if reads else {}
    write_metrics = summarise(writes) if writes else {}
    read_p95 = max((metric["p95Ms"] for metric in read_metrics.values()), default=float("inf"))
    write_p95 = max((metric["p95Ms"] for metric in write_metrics.values()), default=float("inf"))
    errors = read_errors + write_errors
    passed = not errors and read_p95 <= args.read_p95_ms and write_p95 <= args.write_p95_ms
    report = {
        "timestamp": datetime.now(timezone.utc).isoformat(),
        "baseUrl": base,
        "fixture": fixture,
        "iterations": args.iterations,
        "concurrency": args.concurrency,
        "apiKeyConfigured": bool(args.api_key),
        "thresholds": {
            "readP95Ms": args.read_p95_ms,
            "writeP95Ms": args.write_p95_ms,
            "businessErrorRate": 0,
        },
        "reads": read_metrics,
        "writes": write_metrics,
        "readP95Ms": read_p95,
        "writeP95Ms": write_p95,
        "attemptedOperations": args.iterations * 2,
        "errorCount": len(errors),
        "errors": errors[:10],
        "status": "PASS" if passed else "FAIL",
    }
    rendered = json.dumps(report, ensure_ascii=False, indent=2)
    print(rendered)
    if args.output:
        output = Path(args.output)
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(rendered + "\n", encoding="utf-8")
    return 0 if passed else 2


if __name__ == "__main__":
    raise SystemExit(main())
