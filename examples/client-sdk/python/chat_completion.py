#!/usr/bin/env python3
"""Minimal external-client example for the controlled OpenAI-compatible API."""

from __future__ import annotations

import argparse
import os
import sys
from urllib.parse import urlparse

import httpx
from openai import APIError, OpenAI
from recovery import recovery_action


def required(name: str) -> str:
    value = os.environ.get(name, "").strip()
    if not value:
        raise SystemExit(f"missing required environment variable: {name}")
    return value


def trust_environment_for(root_url: str) -> bool:
    """Keep local loopback probes independent from a machine proxy."""
    host = (urlparse(root_url).hostname or "").lower()
    return host not in {"127.0.0.1", "localhost", "::1"}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--stream", action="store_true")
    parser.add_argument("--idempotency-key", default="")
    args = parser.parse_args()

    root_url = required("RAG_BASE_URL").rstrip("/")
    if root_url.endswith("/v1"):
        raise SystemExit("RAG_BASE_URL must be the service root without /v1")

    client = OpenAI(
        max_retries=0,
        api_key=required("RAG_API_KEY"),
        base_url=f"{root_url}/v1",
        http_client=httpx.Client(
            timeout=120,
            trust_env=trust_environment_for(root_url),
        ),
    )
    headers = {}
    collection_key = os.environ.get("RAG_COLLECTION_KEY", "").strip()
    if collection_key:
        headers["X-RAG-Collection-Key"] = collection_key
    idempotency_key = args.idempotency_key.strip()
    if idempotency_key:
        headers["Idempotency-Key"] = idempotency_key

    request = {
        "model": os.environ.get("RAG_MODEL", "rag-default"),
        "messages": [
            {"role": "user", "content": os.environ.get(
                "RAG_QUERY", "Return a short service-capability summary")},
        ],
        "stream": args.stream,
    }
    stream_started = False
    try:
        response = client.chat.completions.create(
            **request,
            extra_headers=headers or None,
        )
        if args.stream:
            chunks = []
            for chunk in response:
                stream_started = True
                text = chunk.choices[0].delta.content if chunk.choices else None
                if text:
                    chunks.append(text)
            print(f"stream_ok chunks={len(chunks)}")
        else:
            print(
                f"json_ok id={response.id} model={response.model} "
                f"choices={len(response.choices)} rag={getattr(response, 'rag', None) is not None}"
            )
    except APIError as error:
        body = error.body if isinstance(error.body, dict) else {}
        body = body.get("error", body)
        details = body.get("failureDetails") if isinstance(body, dict) else None
        action = recovery_action(details, stream_started=stream_started, keyed=bool(idempotency_key))
        print(f"request_failed recovery={action}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
