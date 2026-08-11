#!/usr/bin/env python3
"""Verify a restored MySQL, Redis and vector snapshot through the application."""
from __future__ import annotations

import argparse
import json
from datetime import datetime, timezone
from pathlib import Path

from multi_instance_acceptance import (
    AcceptanceFailure,
    discover_fixture,
    json_request,
    require,
    stream_chat,
    wait_for_messages,
)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:19058")
    parser.add_argument("--api-key", default="")
    parser.add_argument("--expected-documents", type=int, default=5)
    parser.add_argument("--expected-vectors", type=int, default=13)
    parser.add_argument("--output", default="target/acceptance/backup-restore/restore.json")
    args = parser.parse_args()
    base = args.base_url.rstrip("/")
    session_id: str | None = None
    space_id: int | None = None
    try:
        readiness = json_request("GET", f"{base}/api/readiness", api_key=args.api_key)["obj"]
        require(readiness["status"] == "UP", f"Restored service is not ready: {readiness}")
        for dependency in ("mysql", "redis"):
            require(
                readiness["components"][dependency] == "UP",
                f"Restored {dependency} is not ready: {readiness['components']}",
            )
        vector = readiness["components"]["vector"]
        require(vector["status"] == "UP", f"Restored vector store is not ready: {vector}")
        require(
            int(vector["activeChunks"]) == int(vector["indexedChunks"]) == args.expected_vectors,
            f"Restored vector counts differ from {args.expected_vectors}: {vector}",
        )

        fixture = discover_fixture(base, args.api_key)
        space_id = int(fixture["id"])
        documents = json_request(
            "GET", f"{base}/api/spaces/{space_id}/documents", api_key=args.api_key
        ).get("obj") or []
        require(
            len(documents) == args.expected_documents,
            f"Restored fixture has {len(documents)} documents, expected {args.expected_documents}",
        )

        session = json_request(
            "POST",
            f"{base}/api/spaces/{space_id}/sessions",
            api_key=args.api_key,
            body={"title": "restore acceptance"},
        )["obj"]
        session_id = str(session["id"])
        status, _, events = stream_chat(
            base,
            space_id,
            session_id,
            "为什么查询命中向量后还要检查 MySQL？",
            api_key=args.api_key,
        )
        require(status == 200 and isinstance(events, list), f"Restored chat returned HTTP {status}")
        event_names = [event["event"] for event in events]
        require("retrieval" in event_names and "token" in event_names and "done" in event_names,
                f"Restored SSE lifecycle is incomplete: {event_names}")
        retrieval = next(event["data"] for event in events if event["event"] == "retrieval")
        snippets = retrieval.get("snippets") or []
        require(bool(snippets), "Restored vector query returned no snippets")
        require(
            all(item.get("documentId") and item.get("chunkId") for item in snippets),
            f"Restored citations lack exact ids: {snippets}",
        )
        detail = wait_for_messages(base, args.api_key, space_id, session_id)
        require("MySQL" in detail["messages"][-1]["content"], "Restored Redis answer is invalid")

        json_request(
            "DELETE", f"{base}/api/spaces/{space_id}/sessions/{session_id}", api_key=args.api_key
        )
        session_id = None
        report = {
            "timestamp": datetime.now(timezone.utc).isoformat(),
            "status": "PASS",
            "baseUrl": base,
            "fixture": {
                "spaceId": space_id,
                "name": fixture.get("name"),
                "documentCount": len(documents),
            },
            "dependencies": readiness["components"],
            "sseEvents": event_names,
            "citationCount": len(snippets),
            "cleanup": "restore-smoke-session-deleted",
        }
        rendered = json.dumps(report, ensure_ascii=False, indent=2)
        output = Path(args.output)
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(rendered + "\n", encoding="utf-8")
        print(rendered)
        return 0
    except (AcceptanceFailure, KeyError, TypeError) as error:
        print(f"RESTORE ACCEPTANCE FAILED: {error}")
        return 2
    finally:
        if session_id and space_id is not None:
            try:
                json_request(
                    "DELETE", f"{base}/api/spaces/{space_id}/sessions/{session_id}", api_key=args.api_key
                )
            except Exception:
                pass


if __name__ == "__main__":
    raise SystemExit(main())
