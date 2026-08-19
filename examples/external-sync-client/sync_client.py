#!/usr/bin/env python3
"""External document incremental synchronization reference client."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import random
import sqlite3
import ssl
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Any, BinaryIO


DEFAULT_API_KEY_ENV = "RAG_API_KEY"
DEFAULT_BASE_URL_ENV = "RAG_BASE_URL"
RETRYABLE_HTTP_STATUSES = {408, 425, 429, 500, 502, 503, 504}
EXIT_USAGE = 2
EXIT_CONFLICT = 3
EXIT_HTTP = 4
EXIT_INPUT = 5
ALLOWED_COMMON_FIELDS = {
    "eventId",
    "operation",
    "collectionKey",
    "sourceNamespace",
    "externalId",
    "sourceRevision",
    "expectedSourceRevision",
}
ALLOWED_UPSERT_FIELDS = ALLOWED_COMMON_FIELDS | {
    "title",
    "content",
    "source",
    "documentType",
    "metadata",
    "embeddingPolicy",
}


class SyncClientError(Exception):
    def __init__(self, message: str, exit_code: int = EXIT_INPUT):
        super().__init__(message)
        self.exit_code = exit_code


class SourceRevisionConflict(SyncClientError):
    def __init__(self, message: str):
        super().__init__(message, EXIT_CONFLICT)


@dataclass(frozen=True)
class InputIdentity:
    canonical_path: str
    size: int
    sha256: str


@dataclass(frozen=True)
class HttpResult:
    status: int
    body: dict[str, Any]
    attempts: int


def canonical_json(value: Any) -> bytes:
    return json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")


def event_fingerprint(event: dict[str, Any]) -> str:
    return hashlib.sha256(canonical_json(event)).hexdigest()


def inspect_input(path: Path) -> InputIdentity:
    digest = hashlib.sha256()
    size = 0
    with path.open("rb") as stream:
        while chunk := stream.read(1024 * 1024):
            digest.update(chunk)
            size += len(chunk)
    return InputIdentity(str(path.resolve()), size, digest.hexdigest())


def require_text(event: dict[str, Any], field: str, *, max_length: int) -> str:
    value = event.get(field)
    if not isinstance(value, str) or not value.strip():
        raise SyncClientError(f"{field} must be a non-blank string")
    normalized = value.strip()
    if len(normalized) > max_length:
        raise SyncClientError(f"{field} exceeds {max_length} characters")
    return normalized


def validate_event(event: Any) -> dict[str, Any]:
    if not isinstance(event, dict):
        raise SyncClientError("each JSONL line must be a JSON object")

    operation = require_text(event, "operation", max_length=16).upper()
    if operation not in {"UPSERT", "TOMBSTONE"}:
        raise SyncClientError("operation must be UPSERT or TOMBSTONE")
    allowed_fields = ALLOWED_UPSERT_FIELDS if operation == "UPSERT" else ALLOWED_COMMON_FIELDS
    unknown_fields = sorted(set(event) - allowed_fields)
    if unknown_fields:
        raise SyncClientError(f"unknown event fields: {', '.join(unknown_fields)}")

    normalized: dict[str, Any] = {
        "eventId": require_text(event, "eventId", max_length=255),
        "operation": operation,
        "collectionKey": require_text(event, "collectionKey", max_length=128),
        "sourceNamespace": require_text(event, "sourceNamespace", max_length=128),
        "externalId": require_text(event, "externalId", max_length=255),
        "sourceRevision": require_text(event, "sourceRevision", max_length=255),
    }
    expected = event.get("expectedSourceRevision")
    if expected is not None:
        if not isinstance(expected, str) or not expected.strip():
            raise SyncClientError("expectedSourceRevision must be a non-blank string when present")
        if len(expected.strip()) > 255:
            raise SyncClientError("expectedSourceRevision exceeds 255 characters")
        normalized["expectedSourceRevision"] = expected.strip()

    if operation == "TOMBSTONE":
        if "expectedSourceRevision" not in normalized:
            raise SyncClientError("TOMBSTONE requires expectedSourceRevision")
        return normalized

    normalized["title"] = require_text(event, "title", max_length=255)
    content = event.get("content")
    if not isinstance(content, str) or not content.strip():
        raise SyncClientError("content must be a non-blank string")
    if len(content) > 1_000_000:
        raise SyncClientError("content exceeds 1000000 characters")
    normalized["content"] = content

    source = event.get("source")
    if source is not None:
        if not isinstance(source, str):
            raise SyncClientError("source must be a string or null")
        normalized["source"] = source.strip() or None
    else:
        normalized["source"] = None

    document_type = event.get("documentType", "text")
    if not isinstance(document_type, str) or not document_type.strip():
        raise SyncClientError("documentType must be a non-blank string")
    if len(document_type.strip()) > 50:
        raise SyncClientError("documentType exceeds 50 characters")
    normalized["documentType"] = document_type.strip()

    metadata = event.get("metadata", {})
    if metadata is None:
        metadata = {}
    if not isinstance(metadata, dict):
        raise SyncClientError("metadata must be a JSON object")
    normalized["metadata"] = metadata

    embedding_policy = event.get("embeddingPolicy", "ASYNC")
    if embedding_policy not in {"SYNC", "ASYNC", "SKIP"}:
        raise SyncClientError("embeddingPolicy must be SYNC, ASYNC, or SKIP")
    normalized["embeddingPolicy"] = embedding_policy
    return normalized


class Checkpoint:
    def __init__(self, path: Path, identity: InputIdentity):
        self.path = path
        path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        try:
            os.chmod(path.parent, 0o700)
        except OSError:
            pass
        self.connection = sqlite3.connect(path)
        try:
            os.chmod(path, 0o600)
        except OSError:
            pass
        self.connection.execute(
            """
            CREATE TABLE IF NOT EXISTS checkpoint_metadata (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL
            )
            """
        )
        self.connection.execute(
            """
            CREATE TABLE IF NOT EXISTS processed_events (
                event_id TEXT PRIMARY KEY,
                fingerprint TEXT NOT NULL,
                operation TEXT NOT NULL,
                result_action TEXT,
                processed_at_epoch INTEGER NOT NULL
            )
            """
        )
        self.connection.commit()
        self._bind_input(identity)

    def close(self) -> None:
        self.connection.close()

    def _metadata(self) -> dict[str, str]:
        return dict(self.connection.execute(
            "SELECT key, value FROM checkpoint_metadata"
        ).fetchall())

    def _bind_input(self, identity: InputIdentity) -> None:
        metadata = self._metadata()
        if not metadata:
            values = {
                "input_path": identity.canonical_path,
                "input_size": str(identity.size),
                "input_sha256": identity.sha256,
                "byte_offset": "0",
                "line_number": "0",
                "status": "IN_PROGRESS",
            }
            self.connection.executemany(
                "INSERT INTO checkpoint_metadata(key, value) VALUES (?, ?)",
                values.items(),
            )
            self.connection.commit()
            return

        expected = (
            metadata.get("input_path"),
            metadata.get("input_size"),
            metadata.get("input_sha256"),
        )
        actual = (identity.canonical_path, str(identity.size), identity.sha256)
        if expected != actual:
            raise SyncClientError(
                "checkpoint belongs to a different or modified input file; "
                "use a new checkpoint path"
            )

    def position(self) -> tuple[int, int]:
        metadata = self._metadata()
        return int(metadata["byte_offset"]), int(metadata["line_number"])

    def processed_fingerprint(self, event_id: str) -> str | None:
        row = self.connection.execute(
            "SELECT fingerprint FROM processed_events WHERE event_id = ?",
            (event_id,),
        ).fetchone()
        return row[0] if row else None

    def record(
        self,
        *,
        event_id: str,
        fingerprint: str,
        operation: str,
        action: str | None,
        byte_offset: int,
        line_number: int,
    ) -> None:
        with self.connection:
            self.connection.execute(
                """
                INSERT OR IGNORE INTO processed_events(
                    event_id, fingerprint, operation, result_action, processed_at_epoch
                ) VALUES (?, ?, ?, ?, ?)
                """,
                (event_id, fingerprint, operation, action, int(time.time())),
            )
            self.connection.executemany(
                """
                INSERT INTO checkpoint_metadata(key, value) VALUES (?, ?)
                ON CONFLICT(key) DO UPDATE SET value = excluded.value
                """,
                (
                    ("byte_offset", str(byte_offset)),
                    ("line_number", str(line_number)),
                    ("status", "IN_PROGRESS"),
                ),
            )

    def advance(self, *, byte_offset: int, line_number: int) -> None:
        with self.connection:
            self.connection.executemany(
                """
                INSERT INTO checkpoint_metadata(key, value) VALUES (?, ?)
                ON CONFLICT(key) DO UPDATE SET value = excluded.value
                """,
                (
                    ("byte_offset", str(byte_offset)),
                    ("line_number", str(line_number)),
                    ("status", "IN_PROGRESS"),
                ),
            )

    def complete(self) -> None:
        with self.connection:
            self.connection.execute(
                """
                INSERT INTO checkpoint_metadata(key, value) VALUES ('status', 'COMPLETED')
                ON CONFLICT(key) DO UPDATE SET value = excluded.value
                """
            )


class RagHttpClient:
    def __init__(
        self,
        *,
        base_url: str,
        api_key: str,
        timeout: float,
        max_retries: int,
        backoff_base: float,
        insecure: bool,
    ):
        normalized = base_url.rstrip("/")
        if normalized.endswith("/api/v1/rag"):
            self.api_root = normalized
        else:
            self.api_root = normalized + "/api/v1/rag"
        self.api_key = api_key
        self.timeout = timeout
        self.max_retries = max_retries
        self.backoff_base = backoff_base
        self.ssl_context = ssl._create_unverified_context() if insecure else ssl.create_default_context()

    def apply(self, event: dict[str, Any]) -> HttpResult:
        if event["operation"] == "UPSERT":
            payload = {key: value for key, value in event.items() if key not in {"eventId", "operation"}}
            return self._request("POST", "/documents/upsert", payload)

        query = urllib.parse.urlencode({
            "collectionKey": event["collectionKey"],
            "sourceNamespace": event["sourceNamespace"],
            "externalId": event["externalId"],
            "sourceRevision": event["sourceRevision"],
            "expectedSourceRevision": event["expectedSourceRevision"],
        })
        return self._request("DELETE", f"/documents/by-external-id?{query}", None)

    def _request(
        self,
        method: str,
        path: str,
        payload: dict[str, Any] | None,
    ) -> HttpResult:
        body = canonical_json(payload) if payload is not None else None
        headers = {
            "Accept": "application/json",
            "X-API-Key": self.api_key,
        }
        if body is not None:
            headers["Content-Type"] = "application/json"

        for attempt in range(1, self.max_retries + 2):
            request = urllib.request.Request(
                self.api_root + path,
                data=body,
                headers=headers,
                method=method,
            )
            try:
                with urllib.request.urlopen(
                    request,
                    timeout=self.timeout,
                    context=self.ssl_context,
                ) as response:
                    return HttpResult(
                        response.status,
                        parse_json_body(response.read()),
                        attempt,
                    )
            except urllib.error.HTTPError as error:
                error_body = parse_json_body(error.read())
                if error.code == 409:
                    detail = error_body.get("detail") or error_body.get("message") or "source revision conflict"
                    raise SourceRevisionConflict(str(detail)) from error
                if error.code in RETRYABLE_HTTP_STATUSES and attempt <= self.max_retries:
                    self._sleep(attempt, error.headers.get("Retry-After"))
                    continue
                detail = error_body.get("detail") or error_body.get("message") or error.reason
                raise SyncClientError(
                    f"HTTP {error.code}: {detail}",
                    EXIT_HTTP,
                ) from error
            except (urllib.error.URLError, TimeoutError, ConnectionError) as error:
                if attempt <= self.max_retries:
                    self._sleep(attempt, None)
                    continue
                raise SyncClientError(
                    f"network request failed after {attempt} attempts: {error}",
                    EXIT_HTTP,
                ) from error

        raise SyncClientError("request retry loop exhausted", EXIT_HTTP)

    def _sleep(self, attempt: int, retry_after: str | None) -> None:
        if retry_after and retry_after.isdigit():
            delay = min(float(retry_after), 30.0)
        else:
            delay = min(self.backoff_base * (2 ** (attempt - 1)), 30.0)
            delay *= random.uniform(0.75, 1.25)
        time.sleep(delay)


def parse_json_body(raw: bytes) -> dict[str, Any]:
    if not raw:
        return {}
    try:
        value = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError):
        return {}
    return value if isinstance(value, dict) else {"value": value}


def open_events(path: Path, byte_offset: int) -> BinaryIO:
    stream = path.open("rb")
    stream.seek(byte_offset)
    return stream


def apply_events(args: argparse.Namespace) -> int:
    events_path = Path(args.events)
    if not events_path.is_file():
        raise SyncClientError(f"events file does not exist: {events_path}")
    identity = inspect_input(events_path)

    if args.dry_run:
        summary = validate_stream(events_path)
        print(json.dumps({"status": "DRY_RUN_OK", **summary}, sort_keys=True))
        return 0

    api_key = os.environ.get(args.api_key_env, "").strip()
    if not api_key:
        raise SyncClientError(
            f"API key is required in environment variable {args.api_key_env}",
            EXIT_USAGE,
        )
    base_url = (args.base_url or os.environ.get(DEFAULT_BASE_URL_ENV, "")).strip()
    if not base_url:
        raise SyncClientError(
            f"--base-url or {DEFAULT_BASE_URL_ENV} is required",
            EXIT_USAGE,
        )

    checkpoint = Checkpoint(Path(args.checkpoint), identity)
    client = RagHttpClient(
        base_url=base_url,
        api_key=api_key,
        timeout=args.timeout,
        max_retries=args.max_retries,
        backoff_base=args.backoff_base,
        insecure=args.insecure,
    )
    applied = 0
    duplicate_events = 0
    retries = 0
    try:
        byte_offset, line_number = checkpoint.position()
        with open_events(events_path, byte_offset) as stream:
            while raw_line := stream.readline():
                line_number += 1
                next_offset = stream.tell()
                if not raw_line.strip():
                    checkpoint.advance(
                        byte_offset=next_offset,
                        line_number=line_number,
                    )
                    continue
                event = parse_event_line(raw_line, line_number)
                fingerprint = event_fingerprint(event)
                existing = checkpoint.processed_fingerprint(event["eventId"])
                if existing is not None:
                    if existing != fingerprint:
                        raise SyncClientError(
                            f"eventId {event['eventId']} was reused with a different payload"
                        )
                    duplicate_events += 1
                    checkpoint.record(
                        event_id=event["eventId"],
                        fingerprint=fingerprint,
                        operation=event["operation"],
                        action="DUPLICATE",
                        byte_offset=next_offset,
                        line_number=line_number,
                    )
                    continue

                try:
                    result = client.apply(event)
                except SourceRevisionConflict as error:
                    raise SourceRevisionConflict(
                        f"{event_identity(event)}: {error}"
                    ) from error
                action = result.body.get("action")
                checkpoint.record(
                    event_id=event["eventId"],
                    fingerprint=fingerprint,
                    operation=event["operation"],
                    action=str(action) if action is not None else None,
                    byte_offset=next_offset,
                    line_number=line_number,
                )
                applied += 1
                retries += result.attempts - 1
        checkpoint.complete()
    finally:
        checkpoint.close()

    print(json.dumps({
        "status": "COMPLETED",
        "inputSha256": identity.sha256,
        "applied": applied,
        "duplicateEvents": duplicate_events,
        "httpRetries": retries,
    }, sort_keys=True))
    return 0


def event_identity(event: dict[str, Any]) -> str:
    return (
        f"{event['collectionKey']}/{event['sourceNamespace']}/"
        f"{event['externalId']} revision={event['sourceRevision']}"
    )


def parse_event_line(raw_line: bytes, line_number: int) -> dict[str, Any]:
    try:
        decoded = raw_line.decode("utf-8")
    except UnicodeDecodeError as error:
        raise SyncClientError(f"line {line_number}: input must be UTF-8") from error
    try:
        value = json.loads(decoded)
    except json.JSONDecodeError as error:
        raise SyncClientError(f"line {line_number}: invalid JSON: {error.msg}") from error
    try:
        return validate_event(value)
    except SyncClientError as error:
        raise SyncClientError(f"line {line_number}: {error}", error.exit_code) from error


def validate_stream(path: Path) -> dict[str, int]:
    events = 0
    upserts = 0
    tombstones = 0
    seen: dict[str, str] = {}
    with path.open("rb") as stream:
        line_number = 0
        while raw_line := stream.readline():
            line_number += 1
            if not raw_line.strip():
                continue
            event = parse_event_line(raw_line, line_number)
            fingerprint = event_fingerprint(event)
            existing = seen.get(event["eventId"])
            if existing is not None and existing != fingerprint:
                raise SyncClientError(
                    f"line {line_number}: eventId {event['eventId']} has conflicting payloads"
                )
            seen[event["eventId"]] = fingerprint
            events += 1
            if event["operation"] == "UPSERT":
                upserts += 1
            else:
                tombstones += 1
    return {"events": events, "upserts": upserts, "tombstones": tombstones}


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Incrementally synchronize externally managed documents.",
    )
    subparsers = parser.add_subparsers(dest="command", required=True)
    apply_parser = subparsers.add_parser(
        "apply-events",
        help="Apply an immutable JSONL file of UPSERT/TOMBSTONE events.",
    )
    apply_parser.add_argument("--events", required=True, help="UTF-8 JSONL event file")
    apply_parser.add_argument(
        "--checkpoint",
        default=".external-sync/checkpoint.sqlite3",
        help="SQLite checkpoint path; API keys and document bodies are never stored",
    )
    apply_parser.add_argument("--base-url", help=f"Service base URL; fallback: {DEFAULT_BASE_URL_ENV}")
    apply_parser.add_argument(
        "--api-key-env",
        default=DEFAULT_API_KEY_ENV,
        help=f"environment variable containing the API key (default: {DEFAULT_API_KEY_ENV})",
    )
    apply_parser.add_argument("--timeout", type=float, default=30.0)
    apply_parser.add_argument("--max-retries", type=int, default=4)
    apply_parser.add_argument("--backoff-base", type=float, default=0.5)
    apply_parser.add_argument("--dry-run", action="store_true")
    apply_parser.add_argument(
        "--insecure",
        action="store_true",
        help="disable TLS certificate verification for local development only",
    )
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    if args.command != "apply-events":
        parser.error("unsupported command")
    if args.max_retries < 0 or args.max_retries > 10:
        parser.error("--max-retries must be between 0 and 10")
    if args.timeout <= 0 or args.backoff_base < 0:
        parser.error("--timeout must be positive and --backoff-base must be non-negative")
    return apply_events(args)


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except SyncClientError as error:
        print(json.dumps({
            "status": "FAILED",
            "error": str(error),
        }, sort_keys=True), file=sys.stderr)
        raise SystemExit(error.exit_code)
