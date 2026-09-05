#!/usr/bin/env python3
"""Acceptance proof for shared MySQL, Redis, vector state and rate limits."""
from __future__ import annotations

import argparse
import json
import time
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


class AcceptanceFailure(RuntimeError):
    pass


def headers(api_key: str = "", forwarded_for: str = "") -> dict[str, str]:
    result = {"Accept": "application/json"}
    if api_key:
        result["X-API-Key"] = api_key
    if forwarded_for:
        result["X-Forwarded-For"] = forwarded_for
    return result


def json_request(
    method: str,
    url: str,
    *,
    api_key: str = "",
    body: dict[str, Any] | None = None,
    expected_status: int = 200,
) -> dict[str, Any]:
    request_headers = headers(api_key)
    encoded = None
    if body is not None:
        request_headers["Content-Type"] = "application/json"
        encoded = json.dumps(body).encode("utf-8")
    request = urllib.request.Request(url, data=encoded, headers=request_headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            status = response.status
            raw = response.read().decode("utf-8")
    except urllib.error.HTTPError as error:
        status = error.code
        raw = error.read().decode("utf-8", errors="replace")
    try:
        payload = json.loads(raw)
    except json.JSONDecodeError as error:
        raise AcceptanceFailure(f"{method} {url} returned non-JSON data") from error
    if status != expected_status:
        raise AcceptanceFailure(f"{method} {url} returned HTTP {status}: {raw[:240]}")
    expected_code = "200" if expected_status < 400 else str(expected_status)
    if payload.get("resCode") != expected_code:
        raise AcceptanceFailure(
            f"{method} {url} returned resCode={payload.get('resCode')}, expected {expected_code}"
        )
    return payload


def stream_chat(
    base_url: str,
    space_id: int,
    session_id: str,
    question: str,
    *,
    api_key: str = "",
    forwarded_for: str = "",
) -> tuple[int, dict[str, str], list[dict[str, Any]] | dict[str, Any]]:
    request_headers = headers(api_key, forwarded_for)
    request_headers.update({"Accept": "text/event-stream", "Content-Type": "application/json"})
    encoded = json.dumps({"sessionId": session_id, "question": question}).encode("utf-8")
    request = urllib.request.Request(
        f"{base_url}/api/spaces/{space_id}/chat",
        data=encoded,
        headers=request_headers,
        method="POST",
    )
    try:
        response = urllib.request.urlopen(request, timeout=30)
    except urllib.error.HTTPError as error:
        raw = error.read().decode("utf-8", errors="replace")
        try:
            payload = json.loads(raw)
        except json.JSONDecodeError as decode_error:
            raise AcceptanceFailure(f"Rate-limit response was not JSON: {raw[:240]}") from decode_error
        return error.code, dict(error.headers.items()), payload

    events: list[dict[str, Any]] = []
    event_name = "message"
    data_lines: list[str] = []
    with response:
        response_headers = dict(response.headers.items())
        for raw_line in response:
            line = raw_line.decode("utf-8").rstrip("\r\n")
            if not line:
                if data_lines:
                    raw_data = "\n".join(data_lines)
                    try:
                        data: Any = json.loads(raw_data)
                    except json.JSONDecodeError:
                        data = raw_data
                    events.append({"event": event_name, "data": data})
                event_name = "message"
                data_lines = []
            elif line.startswith("event:"):
                event_name = line[6:].strip()
            elif line.startswith("data:"):
                data_lines.append(line[5:].strip())
    return response.status, response_headers, events


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AcceptanceFailure(message)


def discover_fixture(base_url: str, api_key: str) -> dict[str, Any]:
    spaces = json_request("GET", f"{base_url}/api/spaces", api_key=api_key).get("obj") or []
    candidates = [
        space for space in spaces
        if str(space.get("description", "")).startswith("Fixed evaluation dataset ")
    ]
    require(bool(candidates), "Fixed evaluation space is missing; run scripts/evaluate-mock.mjs first")
    return max(candidates, key=lambda space: int(space["id"]))


def wait_for_messages(base_url: str, api_key: str, space_id: int, session_id: str) -> dict[str, Any]:
    deadline = time.monotonic() + 10
    while time.monotonic() < deadline:
        detail = json_request(
            "GET", f"{base_url}/api/spaces/{space_id}/sessions/{session_id}", api_key=api_key
        ).get("obj") or {}
        if len(detail.get("messages") or []) >= 2:
            return detail
        time.sleep(0.2)
    raise AcceptanceFailure("Messages written through one instance were not visible through the other")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--primary-url", default="http://127.0.0.1:19050")
    parser.add_argument("--secondary-url", default="http://127.0.0.1:19057")
    parser.add_argument("--primary-key", default="")
    parser.add_argument("--secondary-key", required=True)
    parser.add_argument("--rate-limit", type=int, default=4)
    parser.add_argument("--output", default="target/acceptance/multi-instance.json")
    args = parser.parse_args()
    if args.rate_limit < 2:
        parser.error("rate-limit must be at least 2")
    primary = args.primary_url.rstrip("/")
    secondary = args.secondary_url.rstrip("/")
    session_id: str | None = None
    space_id: int | None = None

    try:
        primary_ready = json_request("GET", f"{primary}/api/readiness", api_key=args.primary_key)["obj"]
        secondary_ready = json_request("GET", f"{secondary}/api/readiness", api_key=args.secondary_key)["obj"]
        require(primary_ready["status"] == secondary_ready["status"] == "UP", "Both instances must be ready")
        primary_vector = primary_ready["components"]["vector"]
        secondary_vector = secondary_ready["components"]["vector"]
        for field in ("type", "collection", "activeChunks", "indexedChunks"):
            require(
                primary_vector.get(field) == secondary_vector.get(field),
                f"Vector readiness mismatch for {field}: {primary_vector} vs {secondary_vector}",
            )
        fixture = discover_fixture(primary, args.primary_key)
        space_id = int(fixture["id"])

        created = json_request(
            "POST",
            f"{primary}/api/spaces/{space_id}/sessions",
            api_key=args.primary_key,
            body={"title": "multi-instance acceptance"},
        )["obj"]
        session_id = str(created["id"])
        visible = json_request(
            "GET", f"{secondary}/api/spaces/{space_id}/sessions/{session_id}", api_key=args.secondary_key
        )["obj"]
        require(visible["session"]["id"] == session_id, "MySQL session metadata was not shared")

        status, _, events = stream_chat(
            primary,
            space_id,
            session_id,
            "为什么查询命中向量后还要检查 MySQL？",
            api_key=args.primary_key,
        )
        require(status == 200 and isinstance(events, list), f"Primary stream failed: HTTP {status}")
        event_names = [event["event"] for event in events]
        require("retrieval" in event_names and "done" in event_names, f"Incomplete SSE lifecycle: {event_names}")
        detail = wait_for_messages(secondary, args.secondary_key, space_id, session_id)
        messages = detail["messages"]
        require(messages[-2]["role"] == "user" and messages[-1]["role"] == "assistant", "Redis turns are invalid")
        require("MySQL" in messages[-1]["content"], "Shared assistant answer is missing expected fixture content")

        renamed = json_request(
            "PUT",
            f"{secondary}/api/spaces/{space_id}/sessions/{session_id}",
            api_key=args.secondary_key,
            body={"title": "renamed by secondary"},
        )["obj"]
        require(renamed["title"] == "renamed by secondary", "Secondary rename failed")
        primary_detail = json_request(
            "GET", f"{primary}/api/spaces/{space_id}/sessions/{session_id}", api_key=args.primary_key
        )["obj"]
        require(primary_detail["session"]["title"] == "renamed by secondary", "Rename was not shared")

        rate_seed = uuid.uuid4().hex
        rate_client = f"2001:db8:{rate_seed[:4]}:{rate_seed[4:8]}::{rate_seed[8:12]}"
        accepted_by: list[str] = []
        for index in range(args.rate_limit):
            target, key, label = (
                (primary, args.primary_key, "primary")
                if index % 2 == 0
                else (secondary, args.secondary_key, "secondary")
            )
            rate_status, _, rate_events = stream_chat(
                target,
                space_id,
                session_id,
                "向量索引重建后如何对账？",
                api_key=key,
                forwarded_for=rate_client,
            )
            require(rate_status == 200 and isinstance(rate_events, list), f"Request {index + 1} was rejected early")
            require(any(event["event"] == "done" for event in rate_events), "Accepted stream did not complete")
            accepted_by.append(label)
        rejected_status, rejected_headers, rejected = stream_chat(
            secondary,
            space_id,
            session_id,
            "这次请求应命中共享限流。",
            api_key=args.secondary_key,
            forwarded_for=rate_client,
        )
        require(rejected_status == 429, f"Shared rate limiter returned HTTP {rejected_status}, expected 429")
        require(isinstance(rejected, dict) and rejected.get("resCode") == "429", "Invalid rate-limit body")
        require(rejected_headers.get("Retry-After") == "60", "Rate-limit response lacks Retry-After: 60")

        json_request(
            "DELETE", f"{secondary}/api/spaces/{space_id}/sessions/{session_id}", api_key=args.secondary_key
        )
        session_id = None
        remaining = json_request(
            "GET", f"{primary}/api/spaces/{space_id}/sessions", api_key=args.primary_key
        ).get("obj") or []
        require(all(item.get("id") != created["id"] for item in remaining), "Cross-instance delete did not persist")

        report = {
            "timestamp": datetime.now(timezone.utc).isoformat(),
            "status": "PASS",
            "primary": primary,
            "secondary": secondary,
            "fixture": {"spaceId": space_id, "name": fixture.get("name")},
            "vector": primary_vector,
            "sharedSession": {"sseEvents": event_names, "messageCount": len(messages)},
            "sharedRateLimit": {
                "capacity": args.rate_limit,
                "acceptedBy": accepted_by,
                "rejectedBy": "secondary",
                "status": rejected_status,
                "retryAfter": rejected_headers.get("Retry-After"),
            },
            "cleanup": "session-deleted-cross-instance",
        }
        rendered = json.dumps(report, ensure_ascii=False, indent=2)
        output = Path(args.output)
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(rendered + "\n", encoding="utf-8")
        print(rendered)
        return 0
    except (AcceptanceFailure, KeyError, TypeError) as error:
        print(f"MULTI-INSTANCE ACCEPTANCE FAILED: {error}")
        return 2
    finally:
        if session_id and space_id is not None:
            try:
                json_request(
                    "DELETE",
                    f"{secondary}/api/spaces/{space_id}/sessions/{session_id}",
                    api_key=args.secondary_key,
                )
            except Exception:
                pass


if __name__ == "__main__":
    raise SystemExit(main())
