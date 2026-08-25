#!/usr/bin/env python3
"""Deterministic OpenAI-compatible embedding stub for local contract tests."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any


class Counter:
    def __init__(self, path: Path) -> None:
        self.path = path
        self.lock = threading.Lock()
        self.requests = 0
        self.inputs = 0
        self.failed_requests = 0
        self._write()

    def add(self, input_count: int, *, failed: bool = False) -> None:
        with self.lock:
            self.requests += 1
            self.inputs += input_count
            if failed:
                self.failed_requests += 1
            self._write()

    def _write(self) -> None:
        payload = {
            "requests": self.requests,
            "inputs": self.inputs,
            "failedRequests": self.failed_requests,
        }
        fd, temporary = tempfile.mkstemp(
            prefix=f"{self.path.name}.", dir=self.path.parent
        )
        try:
            with os.fdopen(fd, "w", encoding="utf-8") as handle:
                json.dump(payload, handle, separators=(",", ":"))
            os.replace(temporary, self.path)
        finally:
            if os.path.exists(temporary):
                os.unlink(temporary)


def embedding(text: str, dimensions: int) -> list[float]:
    seed = hashlib.sha256(text.encode("utf-8")).digest()
    values: list[float] = []
    for index in range(dimensions):
        byte = seed[index % len(seed)]
        value = ((byte + index * 17) % 251 - 125) / 125.0
        values.append(value)
    norm = math.sqrt(sum(value * value for value in values)) or 1.0
    return [round(value / norm, 8) for value in values]


class Handler(BaseHTTPRequestHandler):
    server_version = "EmbeddingContractStub/1.0"
    max_request_bytes = 4 * 1024 * 1024

    def do_GET(self) -> None:  # noqa: N802
        if self.path == "/health":
            self._json(200, {"status": "UP"})
            return
        self._json(404, {"error": "not_found"})

    def do_POST(self) -> None:  # noqa: N802
        if self.path != "/v1/embeddings":
            self._json(404, {"error": "not_found"})
            return
        try:
            request = json.loads(self._read_body())
            raw_input = request.get("input")
            inputs = raw_input if isinstance(raw_input, list) else [raw_input]
            if not inputs or any(not isinstance(value, str) for value in inputs):
                raise ValueError("input must be a string or a non-empty string array")
            dimensions = int(request.get("dimensions") or self.server.dimensions)
            if dimensions != self.server.dimensions:
                raise ValueError(
                    f"dimensions must equal {self.server.dimensions}"
                )
            model = str(request.get("model") or "contract-embedding")
            if self.server.fail_marker and any(
                self.server.fail_marker in value for value in inputs
            ):
                self.server.counter.add(len(inputs), failed=True)
                self._json(
                    503,
                    {
                        "error": {
                            "type": "provider_unavailable",
                            "message": "deterministic contract failure",
                        }
                    },
                )
                return
            data = [
                {
                    "object": "embedding",
                    "index": index,
                    "embedding": embedding(value, dimensions),
                }
                for index, value in enumerate(inputs)
            ]
            self.server.counter.add(len(inputs))
            self._json(
                200,
                {
                    "object": "list",
                    "data": data,
                    "model": model,
                    "usage": {
                        "prompt_tokens": sum(len(value.split()) for value in inputs),
                        "total_tokens": sum(len(value.split()) for value in inputs),
                    },
                },
            )
        except (ValueError, TypeError, json.JSONDecodeError) as error:
            self._json(
                400,
                {
                    "error": {
                        "type": "invalid_request_error",
                        "message": str(error),
                    }
                },
            )

    def _read_body(self) -> bytes:
        transfer_encoding = self.headers.get("Transfer-Encoding", "").lower()
        if "chunked" in transfer_encoding:
            return self._read_chunked_body()

        length = int(self.headers.get("Content-Length", "0"))
        if length <= 0 or length > self.max_request_bytes:
            raise ValueError("invalid request body length")
        body = self.rfile.read(length)
        if len(body) != length:
            raise ValueError("incomplete request body")
        return body

    def _read_chunked_body(self) -> bytes:
        chunks: list[bytes] = []
        total = 0
        while True:
            size_line = self.rfile.readline(4096)
            if not size_line:
                raise ValueError("incomplete chunked request body")
            try:
                size = int(size_line.split(b";", 1)[0].strip(), 16)
            except ValueError as error:
                raise ValueError("invalid chunk size") from error
            if size == 0:
                while True:
                    trailer = self.rfile.readline(4096)
                    if trailer in (b"\r\n", b"\n"):
                        return b"".join(chunks)
                    if not trailer:
                        raise ValueError("incomplete chunked request trailer")
            total += size
            if total > self.max_request_bytes:
                raise ValueError("request body exceeds size limit")
            chunk = self.rfile.read(size)
            if len(chunk) != size:
                raise ValueError("incomplete request body chunk")
            if self.rfile.read(2) != b"\r\n":
                raise ValueError("invalid chunk terminator")
            chunks.append(chunk)

    def log_message(self, _format: str, *_args: Any) -> None:
        return

    def _json(self, status: int, payload: dict[str, Any]) -> None:
        encoded = json.dumps(payload, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)


class Server(ThreadingHTTPServer):
    def __init__(
        self,
        address: tuple[str, int],
        handler: type[BaseHTTPRequestHandler],
        dimensions: int,
        counter: Counter,
        fail_marker: str | None,
    ) -> None:
        super().__init__(address, handler)
        self.dimensions = dimensions
        self.counter = counter
        self.fail_marker = fail_marker


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--dimensions", type=int, default=1024)
    parser.add_argument("--counter-file", type=Path, required=True)
    parser.add_argument("--fail-marker")
    args = parser.parse_args()

    args.counter_file.parent.mkdir(parents=True, exist_ok=True)
    counter = Counter(args.counter_file)
    server = Server(
        (args.host, args.port),
        Handler,
        dimensions=args.dimensions,
        counter=counter,
        fail_marker=args.fail_marker,
    )
    server.serve_forever()


if __name__ == "__main__":
    main()
