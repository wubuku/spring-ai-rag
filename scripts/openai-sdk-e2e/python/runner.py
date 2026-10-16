#!/usr/bin/env python3
"""Official OpenAI Python SDK black-box contract runner.

The runner intentionally prints only redacted, structural results. It never
prints credentials, prompts, answers, or exception bodies.
"""

from __future__ import annotations

import os
import sys
from datetime import datetime, timedelta, timezone
from typing import Any
from uuid import uuid4

import httpx
from openai import OpenAI


BASE_URL = os.environ.get("BASE_URL", "http://127.0.0.1:8081").rstrip("/")
API_KEY = os.environ.get("API_KEY", "")
MODEL = os.environ.get("MODEL", "rag-default")


def fail(message: str) -> None:
    raise AssertionError(message)


def require(condition: bool, message: str) -> None:
    if not condition:
        fail(message)


def error_status(error: BaseException) -> int | None:
    value = getattr(error, "status_code", None)
    if isinstance(value, int):
        return value
    value = getattr(error, "status", None)
    return value if isinstance(value, int) else None


def error_type(error: BaseException) -> str | None:
    value = getattr(error, "type", None)
    if isinstance(value, str):
        return value
    value = getattr(error, "body", None)
    if isinstance(value, dict):
        nested = value.get("error")
        if isinstance(nested, dict) and isinstance(nested.get("type"), str):
            return nested["type"]
    return None


def run() -> None:
    global phase
    require(API_KEY.strip() != "", "API_KEY is required")
    client = OpenAI(base_url=f"{BASE_URL}/v1", api_key=API_KEY, max_retries=0, timeout=30)

    if os.environ.get("SDK_EXPECT_UNAVAILABLE") == "1":
        phase = "provider-unavailable"
        expect_error(lambda: client.chat.completions.create(
            model=MODEL, messages=[{"role": "user", "content": "SDK failure probe"}]
        ), 503, "server_error")
        print("provider-unavailable: PASS")
        return

    phase = "models"
    models = client.models.list()
    model_ids = {item.id for item in models.data}
    require(MODEL in model_ids, "configured model alias is absent from /v1/models")
    print(f"models: PASS ({len(model_ids)} model(s))")

    phase = "json"
    request: dict[str, Any] = {
        "model": MODEL,
        "messages": [{"role": "user", "content": "SDK contract probe"}],
    }
    completion = client.chat.completions.create(**request)
    require(completion.object == "chat.completion", "non-stream object mismatch")
    require(completion.model == MODEL and bool(completion.id), "non-stream identity mismatch")
    require(len(completion.choices) == 1, "non-stream choices mismatch")
    require(bool(completion.choices[0].message.content), "non-stream content missing")
    require(completion.choices[0].message.role == "assistant", "non-stream role mismatch")
    require(completion.usage is not None and isinstance(completion.usage.total_tokens, int)
            and completion.usage.total_tokens >= 0, "usage missing")
    print("json: PASS")

    phase = "sse"
    stream = client.chat.completions.create(**request, stream=True)
    chunks = 0
    text_parts: list[str] = []
    saw_finish = False
    saw_role = False
    stream_id = None
    for chunk in stream:
        chunks += 1
        stream_id = stream_id or chunk.id
        require(chunk.object == "chat.completion.chunk" and chunk.model == MODEL
                and chunk.id == stream_id and bool(stream_id), "stream identity mismatch")
        if not chunk.choices:
            continue
        choice = chunk.choices[0]
        require(not saw_finish, "chunk after finish_reason")
        if choice.delta and choice.delta.role:
            require(choice.delta.role == "assistant" and chunks == 1, "role order mismatch")
            saw_role = True
        if choice.delta and choice.delta.content:
            require(saw_role, "content before assistant role")
            text_parts.append(choice.delta.content)
        if choice.finish_reason is not None:
            saw_finish = True
    require(chunks > 0, "stream returned no chunks")
    require(bool(text_parts), "stream returned no content delta")
    require(saw_finish, "stream did not expose finish_reason")
    require(saw_role, "stream did not expose assistant role")
    with client.chat.completions.with_streaming_response.create(**request, stream=True) as raw:
        require("text/event-stream" in raw.headers.get("content-type", ""), "SSE content type mismatch")
        frames = [line[5:].strip() for line in raw.iter_lines() if line.startswith("data:")]
    require(bool(frames) and frames[-1] == "[DONE]"
            and frames.count("[DONE]") == 1, "SSE [DONE] mismatch")
    print("sse: PASS")

    phase = "idempotency"
    idempotency_key = f"sdk-python-{uuid4()}"
    mock_url = os.environ.get("SDK_MOCK_BASE_URL")
    before = httpx.get(f"{mock_url}/stats", trust_env=False).json()["chatRequests"] if mock_url else None
    replay_one = client.chat.completions.create(
        **request,
        extra_headers={"Idempotency-Key": idempotency_key},
    )
    replay_two = client.chat.completions.create(
        **request,
        extra_headers={"Idempotency-Key": idempotency_key},
    )
    require(replay_one.id == replay_two.id, "idempotent replay returned a different id")
    require(
        replay_one.choices[0].message.content == replay_two.choices[0].message.content,
        "idempotent replay returned different content",
    )
    if before is not None:
        after = httpx.get(f"{mock_url}/stats", trust_env=False).json()["chatRequests"]
        require(after - before == 1, "idempotent replay invoked provider again")
    print("idempotency: PASS")

    phase = "error-envelope"
    try:
        client.chat.completions.create(
            model="missing-sdk-python-model",
            messages=request["messages"],
        )
    except Exception as error:
        require(error_status(error) == 404, "invalid model did not return HTTP 404")
        require(error_type(error) == "invalid_request_error", "error type mismatch")
    else:
        fail("invalid model unexpectedly succeeded")
    print("error-envelope: PASS")

    phase = "authentication"
    unauthenticated = OpenAI(base_url=f"{BASE_URL}/v1", api_key="invalid-sdk-key", max_retries=0)
    try:
        unauthenticated.models.list()
    except Exception as error:
        require(error_status(error) == 401, "invalid credential did not return HTTP 401")
    else:
        fail("invalid credential unexpectedly succeeded")
    print("authentication: PASS")

    if os.environ.get("SDK_MANAGED_FIXTURES") == "1":
        phase = "native-acl-quota"
        with httpx.Client(base_url=BASE_URL, headers={"Authorization": f"Bearer {API_KEY}"},
                          timeout=30, trust_env=False) as native:
            response = native.get("/api/v1/rag/collections")
            require(response.status_code == 200, "Native collection list failed")
            keys = [f"sdk:{uuid4()}:allowed", f"sdk:{uuid4()}:denied"]
            for key in keys:
                created = native.post("/api/v1/rag/collections", json={"collectionKey": key, "name": key})
                require(created.status_code in (200, 201), "Native collection create failed")
            expires = (datetime.now(timezone.utc) + timedelta(days=1)).strftime("%Y-%m-%dT%H:%M:%S")
            created = native.post("/api/v1/rag/api-keys", json={
                "name": "SDK ACL quota probe", "expiresAt": expires,
                "allowedCollectionKeys": [keys[0]], "requestsPerMinute": 1,
                "capabilities": ["RAG_READ"],
            })
            require(created.status_code == 201, "Native API key create failed")
            restricted = OpenAI(base_url=f"{BASE_URL}/v1", api_key=created.json()["rawKey"],
                                max_retries=0, timeout=30)
            expect_error(lambda: restricted.chat.completions.create(
                **request, extra_body={"rag": {"scope": {
                    "mode": "SELECTED_COLLECTIONS", "collection_keys": [keys[1]],
                }}}
            ), 403, "permission_error")
            expect_error(lambda: restricted.models.list(), 429, "rate_limit_error")
        print("native-acl-quota: PASS")

    print("python-sdk-e2e: PASS")


def expect_error(operation: Any, status: int, response_type: str) -> None:
    try:
        operation()
    except Exception as error:
        require(error_status(error) == status, f"expected HTTP {status}, received {error_status(error)}")
        require(error_type(error) == response_type, "error type mismatch")
    else:
        fail("request unexpectedly succeeded")


if __name__ == "__main__":
    phase = "initialization"
    try:
        run()
    except Exception as error:
        status = error_status(error)
        response_type = error_type(error)
        code = getattr(error, "code", None)
        print(
            "python-sdk-e2e: FAIL "
            f"phase={phase} type={type(error).__name__} "
            f"status={status} error_type={response_type} code={code}",
            file=sys.stderr,
        )
        if isinstance(error, AssertionError):
            print(f"assertion: {error}", file=sys.stderr)
        sys.exit(1)
