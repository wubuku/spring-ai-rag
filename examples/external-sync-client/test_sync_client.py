#!/usr/bin/env python3

from __future__ import annotations

import json
import os
import stat
import subprocess
import sys
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any


CLIENT = Path(__file__).with_name("sync_client.py")
SECRET = "rag_sk_reference_client_secret_value"


class ApiHandler(BaseHTTPRequestHandler):
    requests: list[dict[str, Any]] = []
    fail_first = False
    conflict = False

    def log_message(self, _format: str, *_args: object) -> None:
        return

    def do_POST(self) -> None:
        self._handle()

    def do_DELETE(self) -> None:
        self._handle()

    def _handle(self) -> None:
        length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(length) if length else b""
        record = {
            "method": self.command,
            "path": self.path,
            "apiKey": self.headers.get("X-API-Key"),
            "body": json.loads(body) if body else None,
        }
        type(self).requests.append(record)

        if type(self).conflict:
            self._json(409, {
                "error": "STRUCTURED_RECORD_CONFLICT",
                "detail": "expected revision does not match",
            })
            return
        if type(self).fail_first and len(type(self).requests) == 1:
            self._json(503, {"error": "UNAVAILABLE", "detail": "retry"})
            return
        action = "DELETED" if self.command == "DELETE" else "UPDATED"
        self._json(200, {"action": action, "embeddingAction": "QUEUED"})

    def _json(self, status_code: int, body: dict[str, Any]) -> None:
        encoded = json.dumps(body).encode("utf-8")
        self.send_response(status_code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)


class ReferenceClientTest(unittest.TestCase):
    def setUp(self) -> None:
        ApiHandler.requests = []
        ApiHandler.fail_first = False
        ApiHandler.conflict = False
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), ApiHandler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.temp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_dir.name)

    def tearDown(self) -> None:
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=5)
        self.temp_dir.cleanup()

    def run_client(
        self,
        events: Path,
        checkpoint: Path,
        *extra_args: str,
    ) -> subprocess.CompletedProcess[str]:
        env = os.environ.copy()
        env["RAG_API_KEY"] = SECRET
        return subprocess.run(
            [
                sys.executable,
                str(CLIENT),
                "apply-events",
                "--events",
                str(events),
                "--checkpoint",
                str(checkpoint),
                "--base-url",
                f"http://127.0.0.1:{self.server.server_port}",
                "--backoff-base",
                "0",
                *extra_args,
            ],
            check=False,
            capture_output=True,
            text=True,
            env=env,
            timeout=20,
        )

    def write_events(self, events: list[dict[str, Any]]) -> Path:
        path = self.root / "events.jsonl"
        path.write_text(
            "".join(json.dumps(event, separators=(",", ":")) + "\n" for event in events),
            encoding="utf-8",
        )
        return path

    def test_applies_events_retries_and_resumes_without_persisting_secrets(self) -> None:
        events = self.write_events([
            upsert_event("create-r1", "r1"),
            upsert_event("update-r2", "r2", expected="r1"),
            tombstone_event("delete-r3", "r3", expected="r2"),
        ])
        checkpoint = self.root / "state" / "checkpoint.sqlite3"
        ApiHandler.fail_first = True

        first = self.run_client(events, checkpoint)

        self.assertEqual(0, first.returncode, first.stderr)
        summary = json.loads(first.stdout)
        self.assertEqual(3, summary["applied"])
        self.assertEqual(1, summary["httpRetries"])
        self.assertEqual(4, len(ApiHandler.requests))
        self.assertTrue(all(item["apiKey"] == SECRET for item in ApiHandler.requests))
        self.assertEqual("ASYNC", ApiHandler.requests[1]["body"]["embeddingPolicy"])
        self.assertIn("sourceNamespace=cms-main", ApiHandler.requests[-1]["path"])

        checkpoint_bytes = checkpoint.read_bytes()
        self.assertNotIn(SECRET.encode("utf-8"), checkpoint_bytes)
        self.assertNotIn(b"Current searchable body", checkpoint_bytes)
        if os.name == "posix":
            self.assertEqual(0o600, stat.S_IMODE(checkpoint.stat().st_mode))

        previous_request_count = len(ApiHandler.requests)
        resumed = self.run_client(events, checkpoint)
        self.assertEqual(0, resumed.returncode, resumed.stderr)
        self.assertEqual(0, json.loads(resumed.stdout)["applied"])
        self.assertEqual(previous_request_count, len(ApiHandler.requests))

    def test_reports_revision_conflict_without_advancing_checkpoint(self) -> None:
        events = self.write_events([upsert_event("update-r2", "r2", expected="r1")])
        checkpoint = self.root / "checkpoint.sqlite3"
        ApiHandler.conflict = True

        result = self.run_client(events, checkpoint, "--max-retries", "0")

        self.assertEqual(3, result.returncode)
        error = json.loads(result.stderr)
        self.assertEqual("FAILED", error["status"])
        self.assertIn("expected revision does not match", error["error"])
        self.assertNotIn(SECRET, result.stderr)

        ApiHandler.conflict = False
        retry = self.run_client(events, checkpoint, "--max-retries", "0")
        self.assertEqual(0, retry.returncode, retry.stderr)
        self.assertEqual(1, json.loads(retry.stdout)["applied"])

    def test_rejects_modified_input_for_existing_checkpoint(self) -> None:
        events = self.write_events([upsert_event("create-r1", "r1")])
        checkpoint = self.root / "checkpoint.sqlite3"
        first = self.run_client(events, checkpoint)
        self.assertEqual(0, first.returncode, first.stderr)

        with events.open("a", encoding="utf-8") as stream:
            stream.write(json.dumps(upsert_event("update-r2", "r2", expected="r1")) + "\n")

        changed = self.run_client(events, checkpoint)
        self.assertEqual(5, changed.returncode)
        self.assertIn("modified input file", changed.stderr)

    def test_dry_run_requires_no_api_key_or_checkpoint(self) -> None:
        events = self.write_events([upsert_event("create-r1", "r1")])
        checkpoint = self.root / "checkpoint.sqlite3"
        env = os.environ.copy()
        env.pop("RAG_API_KEY", None)

        result = subprocess.run(
            [
                sys.executable,
                str(CLIENT),
                "apply-events",
                "--events",
                str(events),
                "--checkpoint",
                str(checkpoint),
                "--dry-run",
            ],
            check=False,
            capture_output=True,
            text=True,
            env=env,
            timeout=20,
        )

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("DRY_RUN_OK", json.loads(result.stdout)["status"])
        self.assertFalse(checkpoint.exists())


def upsert_event(
    event_id: str,
    revision: str,
    *,
    expected: str | None = None,
) -> dict[str, Any]:
    event: dict[str, Any] = {
        "eventId": event_id,
        "operation": "UPSERT",
        "collectionKey": "manuals",
        "sourceNamespace": "cms-main",
        "externalId": "article:1",
        "sourceRevision": revision,
        "title": "Current title",
        "content": "Current searchable body",
        "metadata": {"locale": "en-US"},
        "embeddingPolicy": "ASYNC",
    }
    if expected is not None:
        event["expectedSourceRevision"] = expected
    return event


def tombstone_event(
    event_id: str,
    revision: str,
    *,
    expected: str,
) -> dict[str, Any]:
    return {
        "eventId": event_id,
        "operation": "TOMBSTONE",
        "collectionKey": "manuals",
        "sourceNamespace": "cms-main",
        "externalId": "article:1",
        "sourceRevision": revision,
        "expectedSourceRevision": expected,
    }


if __name__ == "__main__":
    unittest.main()
