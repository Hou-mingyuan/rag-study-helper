#!/usr/bin/env python3
"""RAG Study Helper HTTP dry-run (health + documents, no /api/chat)."""
from __future__ import annotations

import argparse
import json
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed


def get(url: str, timeout: float = 10.0) -> tuple[float, dict]:
    started = time.perf_counter()
    with urllib.request.urlopen(url, timeout=timeout) as resp:
        body = resp.read().decode("utf-8")
    ms = (time.perf_counter() - started) * 1000
    try:
        return ms, json.loads(body)
    except json.JSONDecodeError:
        return ms, {}


def one(base: str) -> dict[str, float]:
    h_ms, h = get(f"{base}/api/health")
    d_ms, d = get(f"{base}/api/documents")
    return {"health_ms": h_ms, "docs_ms": d_ms, "health_ok": 1.0 if h.get("resCode") == "200" else 0.0}


def pct(vals: list[float], p: float) -> float:
    s = sorted(vals)
    return s[int(round((p / 100) * (len(s) - 1)))] if s else 0.0


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--base-url", default="http://localhost:8080")
    p.add_argument("-n", "--iterations", type=int, default=12)
    p.add_argument("-c", "--concurrency", type=int, default=3)
    args = p.parse_args()
    base = args.base_url.rstrip("/")

    for _ in range(2):
        try:
            one(base)
        except Exception:
            pass

    rows, err = [], 0
    with ThreadPoolExecutor(max_workers=args.concurrency) as ex:
        futs = [ex.submit(one, base) for _ in range(args.iterations)]
        for f in as_completed(futs):
            try:
                rows.append(f.result())
            except (urllib.error.URLError, TimeoutError) as e:
                err += 1
                print("ERROR:", e)

    if not rows:
        print("DRY-RUN FAILED: start compose first")
        return 1

    h = [r["health_ms"] for r in rows]
    d = [r["docs_ms"] for r in rows]
    print(f"health p95={pct(h,95):.0f}ms docs p95={pct(d,95):.0f}ms ok={len(rows)} err={err}")
    ok = err == 0 and pct(h, 95) < 500 and pct(d, 95) < 1200 and all(r["health_ok"] for r in rows)
    print("DRY-RUN", "PASSED" if ok else "FAILED")
    return 0 if ok else 2


if __name__ == "__main__":
    raise SystemExit(main())
