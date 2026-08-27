#!/usr/bin/env python3
"""Small HTTP stub for binding-preflight capability contract tests."""

from __future__ import annotations

import argparse
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any


def response_payload(server: ThreadingHTTPServer, path: str) -> dict[str, Any]:
    if path == "/actuator/health/readiness":
        return {"status": "UP"}
    if path == "/v3/api-docs":
        return {
            "info": {"version": "1.0.0"},
            "paths": {
                "/api/v1/rag/auth/me": {"get": {}},
                "/api/v1/rag/collections/by-key": {"get": {}},
                "/api/v1/rag/json-records/upsert": {"post": {}},
                "/api/v1/rag/json-records/search": {"post": {}},
                "/api/v1/rag/json-records/by-external-id": {"get": {}, "delete": {}},
            },
        }
    if path == "/api/v1/rag/integration-capabilities":
        return {
            "protocol": {
                "name": "spring-ai-rag-integration",
                "version": server.protocol_version,
                "apiVersion": "1.0.0",
            },
            "features": {
                "optional": {
                    "integrationObservability": server.observability,
                }
            },
            "limits": {
                "structuredRecords": {
                    "maxBatchItems": server.batch_items,
                    "maxBatchPayloadBytes": server.batch_payload_bytes,
                }
            },
        }
    if path == "/api/v1/rag/auth/me":
        return {
            "principalType": "DATABASE_API_KEY",
            "principalRole": "NORMAL",
            "collectionAccessMode": "RESTRICTED",
            "allowedCollectionKeys": server.collections,
            "capabilities": ["RAG_READ"],
            "credentialVersion": 1,
            "policyVersion": 1,
        }
    return {}


class Handler(BaseHTTPRequestHandler):
    server: ThreadingHTTPServer

    def do_GET(self) -> None:  # noqa: N802
        path = self.path.split("?", 1)[0]
        if path == "/api/v1/rag/collections/by-key":
            key = self._query_value("collectionKey")
            if key not in self.server.collections:
                self._json(404, {"error": "not_found"})
                return
            self._json(
                200,
                {"collectionKey": key, "deleted": False, "enabled": True},
            )
            return
        payload = response_payload(self.server, path)
        if not payload:
            self._json(404, {"error": "not_found"})
            return
        self._json(200, payload)

    def log_message(self, format: str, *args: object) -> None:
        return

    def _query_value(self, name: str) -> str:
        from urllib.parse import parse_qs, urlsplit

        values = parse_qs(urlsplit(self.path).query).get(name, [])
        return values[0] if values else ""

    def _json(self, status: int, payload: dict[str, Any]) -> None:
        body = json.dumps(payload, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--port-file", type=Path, required=True)
    parser.add_argument("--batch-items", type=int, default=20)
    parser.add_argument("--batch-payload-bytes", type=int, default=10485760)
    parser.add_argument("--observability", choices=("true", "false"), default="true")
    parser.add_argument("--protocol-version", default="1.1")
    args = parser.parse_args()

    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    server.batch_items = args.batch_items
    server.batch_payload_bytes = args.batch_payload_bytes
    server.observability = args.observability == "true"
    server.protocol_version = args.protocol_version
    server.collections = ["sample-a", "sample-b"]
    args.port_file.write_text(str(server.server_address[1]) + "\n", encoding="ascii")
    server.serve_forever()


if __name__ == "__main__":
    main()
