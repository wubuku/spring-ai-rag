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
    sync_run_fail_batch_once = False
    sync_run_failed_item_once = False
    sync_run_candidate_count = 0
    sync_run_id = "00000000-0000-0000-0000-000000000001"

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
            "lease": self.headers.get("X-RAG-Sync-Lease"),
            "body": json.loads(body) if body else None,
        }
        type(self).requests.append(record)

        if self.path.startswith("/api/v1/rag/document-sync-runs"):
            self._handle_sync_run(record)
            return
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

    def _handle_sync_run(self, record: dict[str, Any]) -> None:
        path = record["path"].split("?", 1)[0]
        if path == "/api/v1/rag/document-sync-runs":
            self._json(200, {
                "runId": type(self).sync_run_id,
                "status": "ACTIVE",
            })
            return
        if path.endswith("/batch-upsert"):
            if type(self).sync_run_fail_batch_once:
                type(self).sync_run_fail_batch_once = False
                self._json(503, {"detail": "retry batch"})
                return
            items = record["body"]["items"]
            if type(self).sync_run_failed_item_once:
                type(self).sync_run_failed_item_once = False
                self._json(200, {
                    "items": [
                        {
                            "externalId": item["externalId"],
                            "documentKind": item["documentKind"],
                            "status": "FAILED",
                            "sourceRevision": item["sourceRevision"],
                            "errorCode": "BAD_REQUEST",
                            "error": "fixture failure",
                        }
                        for item in items
                    ],
                })
                return
            self._json(200, {
                "items": [
                    {
                        "externalId": item["externalId"],
                        "documentKind": item["documentKind"],
                        "status": "APPLIED",
                        "documentId": index + 1,
                        "sourceRevision": item["sourceRevision"],
                        "embeddingAction": "QUEUED",
                    }
                    for index, item in enumerate(items)
                ],
            })
            return
        if path.endswith("/preview-missing"):
            self._json(200, {
                "runId": type(self).sync_run_id,
                "previewToken": "preview-token",
                "candidateCount": type(self).sync_run_candidate_count,
            })
            return
        if path.endswith("/complete"):
            self._json(200, {
                "runId": type(self).sync_run_id,
                "status": "COMPLETED",
            })
            return
        self._json(404, {"detail": "unknown sync-run fixture route"})

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
        ApiHandler.sync_run_fail_batch_once = False
        ApiHandler.sync_run_failed_item_once = False
        ApiHandler.sync_run_candidate_count = 0
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

    def write_manifest(self, items: list[dict[str, Any]]) -> Path:
        path = self.root / "manifest.jsonl"
        path.write_text(
            "".join(
                json.dumps(item, separators=(",", ":")) + "\n"
                for item in items
            ),
            encoding="utf-8",
        )
        return path

    def run_sync_run(
        self,
        subcommand: str,
        checkpoint: Path,
        *,
        manifest: Path | None = None,
        snapshot_mode: str = "OFFLINE_MANIFEST",
        missing_policy: str = "NONE",
        auto_confirm: bool = False,
        confirm_exclusive_offline: bool = False,
        extra_env: dict[str, str] | None = None,
    ) -> subprocess.CompletedProcess[str]:
        env = os.environ.copy()
        env["RAG_API_KEY"] = SECRET
        env["RAG_SYNC_LEASE_TOKEN"] = "opaque-sync-lease-token"
        if extra_env:
            env.update(extra_env)
        command = [
            sys.executable,
            str(CLIENT),
            "sync-run",
            subcommand,
            "--collection-key",
            "manuals",
            "--source-namespace",
            "cms-main",
            "--client-run-id",
            "snapshot-2026-08-19",
            "--snapshot-mode",
            snapshot_mode,
            "--missing-policy",
            missing_policy,
            "--checkpoint",
            str(checkpoint),
            "--base-url",
            f"http://127.0.0.1:{self.server.server_port}",
            "--backoff-base",
            "0",
        ]
        if manifest is not None:
            command.extend(["--manifest", str(manifest)])
        if auto_confirm:
            command.append("--auto-confirm-missing")
        if confirm_exclusive_offline:
            command.append("--confirm-exclusive-offline")
        return subprocess.run(
            command,
            check=False,
            capture_output=True,
            text=True,
            env=env,
            timeout=20,
        )

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

    def test_sync_run_offline_manifest_contract_and_completed_resume(self) -> None:
        manifest = self.write_manifest([
            {
                "documentKind": "TEXT",
                "externalId": "article:1",
                "sourceRevision": "r1",
                "title": "Article",
                "content": "Searchable content",
                "metadata": {"locale": "zh-CN"},
            },
            {
                "documentKind": "JSON_RECORD",
                "externalId": "product:1",
                "sourceRevision": "p1",
                "title": "Product",
                "retrievalText": "Red chair",
                "jsonbPayload": {"sku": "chair-1", "tags": ["red"]},
            },
        ])
        checkpoint = self.root / "sync-run.sqlite3"

        first = self.run_sync_run(
            "apply",
            checkpoint,
            manifest=manifest,
        )

        self.assertEqual(0, first.returncode, first.stderr)
        summary = json.loads(first.stdout)
        self.assertEqual("COMPLETED", summary["status"])
        self.assertEqual(2, summary["uploadedItems"])
        self.assertEqual(
            [
                "/api/v1/rag/document-sync-runs",
                "/api/v1/rag/document-sync-runs/"
                + ApiHandler.sync_run_id
                + "/batch-upsert",
                "/api/v1/rag/document-sync-runs/"
                + ApiHandler.sync_run_id
                + "/preview-missing",
                "/api/v1/rag/document-sync-runs/"
                + ApiHandler.sync_run_id
                + "/complete",
            ],
            [request["path"] for request in ApiHandler.requests],
        )
        begin = ApiHandler.requests[0]
        self.assertEqual(SECRET, begin["apiKey"])
        self.assertEqual("opaque-sync-lease-token", begin["lease"])
        self.assertEqual("OFFLINE_MANIFEST", begin["body"]["snapshotMode"])
        self.assertEqual("NONE", begin["body"]["missingPolicy"])
        batch = ApiHandler.requests[1]["body"]["items"]
        self.assertEqual({"sku": "chair-1", "tags": ["red"]}, batch[1]["jsonbPayload"])

        checkpoint_bytes = checkpoint.read_bytes()
        self.assertNotIn(SECRET.encode("utf-8"), checkpoint_bytes)
        self.assertNotIn(b"opaque-sync-lease-token", checkpoint_bytes)
        self.assertNotIn(b"Searchable content", checkpoint_bytes)
        self.assertNotIn(b"chair-1", checkpoint_bytes)

        request_count = len(ApiHandler.requests)
        resumed = self.run_sync_run(
            "apply",
            checkpoint,
            manifest=manifest,
        )
        self.assertEqual(0, resumed.returncode, resumed.stderr)
        self.assertTrue(json.loads(resumed.stdout)["resumed"])
        self.assertEqual(request_count, len(ApiHandler.requests))

    def test_sync_run_online_cut_requires_begin_and_confirms_preview(self) -> None:
        manifest = self.write_manifest([{
            "documentKind": "TEXT",
            "externalId": "article:1",
            "sourceRevision": "r1",
            "title": "Article",
            "content": "Searchable content",
        }])
        checkpoint = self.root / "online-cut.sqlite3"

        without_begin = self.run_sync_run(
            "apply",
            checkpoint,
            manifest=manifest,
            snapshot_mode="ONLINE_CUT",
            missing_policy="TOMBSTONE",
        )
        self.assertEqual(2, without_begin.returncode)
        self.assertIn("requires 'sync-run begin'", without_begin.stderr)
        self.assertEqual([], ApiHandler.requests)

        begun = self.run_sync_run(
            "begin",
            checkpoint,
            snapshot_mode="ONLINE_CUT",
            missing_policy="TOMBSTONE",
        )
        self.assertEqual(0, begun.returncode, begun.stderr)
        ApiHandler.sync_run_candidate_count = 2
        applied = self.run_sync_run(
            "apply",
            checkpoint,
            manifest=manifest,
            snapshot_mode="ONLINE_CUT",
            missing_policy="TOMBSTONE",
            auto_confirm=True,
        )
        self.assertEqual(0, applied.returncode, applied.stderr)
        complete = ApiHandler.requests[-1]
        self.assertEqual(2, complete["body"]["confirmMissingCount"])

    def test_sync_run_exclusive_tombstone_requires_explicit_confirmation(self) -> None:
        checkpoint = self.root / "exclusive.sqlite3"

        rejected = self.run_sync_run(
            "begin",
            checkpoint,
            snapshot_mode="EXCLUSIVE_OFFLINE",
            missing_policy="TOMBSTONE",
        )
        self.assertEqual(2, rejected.returncode)
        self.assertIn("--confirm-exclusive-offline", rejected.stderr)
        self.assertEqual([], ApiHandler.requests)

        confirmed = self.run_sync_run(
            "begin",
            checkpoint,
            snapshot_mode="EXCLUSIVE_OFFLINE",
            missing_policy="TOMBSTONE",
            confirm_exclusive_offline=True,
        )
        self.assertEqual(0, confirmed.returncode, confirmed.stderr)
        self.assertTrue(
            ApiHandler.requests[-1]["body"]["confirmExclusiveOffline"]
        )

    def test_sync_run_batch_retries_and_failed_batch_does_not_advance_checkpoint(self) -> None:
        manifest = self.write_manifest([{
            "documentKind": "TEXT",
            "externalId": "article:1",
            "sourceRevision": "r1",
            "title": "Article",
            "content": "Searchable content",
        }])
        checkpoint = self.root / "retry.sqlite3"
        ApiHandler.sync_run_fail_batch_once = True

        result = self.run_sync_run("apply", checkpoint, manifest=manifest)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertGreaterEqual(
            json.loads(result.stdout)["httpRetries"],
            1,
        )

        failed_checkpoint = self.root / "failed.sqlite3"
        ApiHandler.sync_run_failed_item_once = True
        failed = self.run_sync_run(
            "apply",
            failed_checkpoint,
            manifest=manifest,
        )
        self.assertEqual(4, failed.returncode)
        self.assertNotIn("COMPLETED", failed.stderr)
        records_after_failure = len(ApiHandler.requests)
        retried = self.run_sync_run(
            "apply",
            failed_checkpoint,
            manifest=manifest,
        )
        self.assertEqual(0, retried.returncode, retried.stderr)
        self.assertGreater(len(ApiHandler.requests), records_after_failure)

    def test_sync_run_rejects_scalar_json_payload(self) -> None:
        manifest = self.write_manifest([{
            "documentKind": "JSON_RECORD",
            "externalId": "product:1",
            "sourceRevision": "p1",
            "title": "Product",
            "retrievalText": "Product",
            "jsonbPayload": "not-an-object",
        }])
        checkpoint = self.root / "invalid.sqlite3"

        result = self.run_sync_run(
            "apply",
            checkpoint,
            manifest=manifest,
        )

        self.assertEqual(5, result.returncode)
        self.assertIn("object/array payload", result.stderr)
        self.assertEqual([], ApiHandler.requests)


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
