#!/usr/bin/env python3
"""对隔离后端执行黑盒竞争验收；需要受控 provider，不能冒充真实 LLM 测试。"""

import concurrent.futures
import contextlib
import json
import os
import time
import urllib.error
import urllib.request
import uuid


def main():
    base = os.environ["EVIDENCE_ADMISSION_BASE_URL"].rstrip("/")
    stub = os.environ["EVIDENCE_ADMISSION_STUB_URL"].rstrip("/")
    key = os.environ.get("EVIDENCE_ADMISSION_API_KEY", "")
    model = os.environ.get("EVIDENCE_ADMISSION_MODEL", "fixture/controlled")

    def request(path, body=None, method=None, headers=None, origin=base):
        merged = {"Content-Type": "application/json", "X-API-Key": key, "Authorization": "Bearer " + key}
        merged.update(headers or {})
        req = urllib.request.Request(origin + path, data=json.dumps(body).encode() if body is not None else None,
                                     method=method, headers=merged)
        try:
            response = urllib.request.urlopen(req, timeout=150)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            return response.status, dict(response.headers), response.read().decode()

    def control(action):
        status, _, body = request("/control/" + action, {} if action != "state" else None, origin=stub)
        assert status == 200, (action, status)
        return json.loads(body)

    def await_active(pending):
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            if control("state")["active"] == 1:
                return
            if pending.done():
                raise AssertionError(("request completed before provider", pending.result()))
            time.sleep(0.1)
        raise AssertionError("request did not reach controlled provider")

    @contextlib.contextmanager
    def release_on_exit():
        try:
            yield
        finally:
            control("release")

    if os.environ.get("EVIDENCE_ADMISSION_REQUEST_LIMIT_ONLY") == "1":
        sessions = []
        try:
            with concurrent.futures.ThreadPoolExecutor(max_workers=1) as executor, release_on_exit():
                for streaming in (False, True):
                    session = "rq-" + uuid.uuid4().hex
                    sessions.append(session)
                    command = {"message": "plain fixture", "mode": "PLAIN", "model": model, "sessionId": session}
                    control("hold")
                    first = executor.submit(request, "/api/v1/rag/chat/" + ("stream" if streaming else "ask"), command)
                    await_active(first)
                    before = control("state")["calls"]
                    for path, payload in [
                        ("/api/v1/rag/chat", dict(command, sessionId="rq-" + uuid.uuid4().hex)),
                        ("/api/v1/rag/search", {"query": "capacity"}),
                        ("/v1/chat/completions", {"model": "rag-plain", "messages": [{"role": "user", "content": "capacity"}]}),
                    ]:
                        status, headers, body = request(path, payload)
                        assert status == 429 and headers.get("Retry-After") == "1", (path, status, body)
                        error = json.loads(body)["error"]
                        assert (error.get("code") if isinstance(error, dict) else error) == "TOO_MANY_REQUESTS", body
                    assert request("/actuator/health")[0] == 200
                    assert request("/v1/models")[0] == 200
                    assert control("state")["calls"] == before
                    control("release")
                    assert first.result()[0] == 200
                    assert request("/api/v1/rag/chat/ask", command)[0] == 200
                    print("PASS: request limit, versioned routes/legacy alias, health/discovery bypass and "
                          + ("SSE" if streaming else "JSON") + " release/recovery")
        finally:
            for session in sessions:
                request("/api/v1/rag/chat/history/" + session, method="DELETE")
        return

    token = "SNAPSHOT_TEST_" + uuid.uuid4().hex.upper()
    status, _, body = request("/api/v1/rag/documents", {
        "title": "Snapshot acceptance", "content": "The verification code is " + token + "."})
    assert status in (200, 201), (status, body)
    document_id = json.loads(body)["id"]
    session_ids = []
    try:
        status, _, body = request(f"/api/v1/rag/documents/{document_id}/embed?force=true", {})
        assert status == 200, (status, body)
        search = {"query": token, "documentIds": [document_id],
                  "config": {"maxResults": 5, "minScore": 0, "useHybridSearch": True, "useRerank": False}}
        status, _, body = request("/api/v1/rag/search", search)
        assert status == 200, (status, body)
        hits = json.loads(body)
        if isinstance(hits, dict):
            hits = hits.get("results", [])
        assert hits and hits[0]["evidenceSnapshot"]["documentRevision"] >= 1, body
        assert len(hits[0]["evidenceSnapshot"]["evidenceDigest"]) == 64
        print("PASS: authoritative search snapshot")

        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as executor, release_on_exit():
            control("hold")
            plain_session = "sp-" + uuid.uuid4().hex
            session_ids.append(plain_session)
            plain = {"message": "plain fixture", "mode": "PLAIN", "model": model,
                     "sessionId": plain_session}
            first = executor.submit(request, "/api/v1/rag/chat/ask", plain)
            await_active(first)
            before = control("state")["calls"]
            rejected_session = "sr-" + uuid.uuid4().hex
            session_ids.append(rejected_session)
            status, headers, body = request("/api/v1/rag/chat/ask", dict(plain, sessionId=rejected_session))
            assert status == 429 and headers.get("Retry-After") == "1", (status, headers, body)
            assert json.loads(body)["error"] == "TOO_MANY_REQUESTS", body
            status, headers, body = request("/v1/chat/completions", {
                "model": "rag-plain", "messages": [{"role": "user", "content": "plain fixture"}]})
            assert status == 429 and headers.get("Retry-After") == "1", (status, headers, body)
            assert json.loads(body)["error"]["code"] == "TOO_MANY_REQUESTS", body
            assert control("state")["calls"] == before
            assert request("/actuator/health")[0] == 200
            assert request("/api/v1/rag/search", search)[0] == 200
            control("release")
            assert first.result()[0] == 200
            assert request("/api/v1/rag/chat/ask", plain)[0] == 200
            print("PASS: Native/OpenAI 429, Retry-After, no provider call, health/search isolation and recovery")

            for streaming in (False, True):
                status, _, body = request(f"/api/v1/rag/documents/{document_id}")
                assert status == 200
                current = json.loads(body)
                revision = current["documentRevision"]
                status, _, body = request(f"/api/v1/rag/documents/{document_id}/embed?force=true", {})
                assert status == 200, (status, body)
                session = "ss-" + uuid.uuid4().hex
                session_ids.append(session)
                command = {"message": token, "mode": "KNOWLEDGE",
                           "model": model, "sessionId": session, "documentIds": [document_id],
                           "useRerank": False, "useHybridSearch": True}
                control("hold")
                pending = executor.submit(request, "/api/v1/rag/chat/" + ("stream" if streaming else "ask"), command)
                await_active(pending)
                status, _, body = request(f"/api/v1/rag/documents/{document_id}", {
                    "expectedDocumentRevision": revision, "title": "Changed during generation " + str(streaming), "embeddingPolicy": "SKIP"}, "PATCH")
                assert status == 200, (status, body)
                control("release")
                status, _, body = pending.result()
                if streaming:
                    assert "EVIDENCE_SNAPSHOT_CONFLICT" in body and "event:done" not in body and "event: done" not in body, body
                else:
                    assert status == 409 and json.loads(body)["error"] == "EVIDENCE_SNAPSHOT_CONFLICT", (status, body)
                status, _, body = request("/api/v1/rag/chat/history/" + session)
                assert (status == 200 and json.loads(body) == []) or (
                    status == 404 and json.loads(body).get("error") == "SESSION_NOT_FOUND"), (status, body)
                print("PASS: " + ("SSE" if streaming else "JSON") + " mutation conflict without successful history/terminal")
    finally:
        control("release")
        for session in session_ids:
            request("/api/v1/rag/chat/history/" + session, method="DELETE")
        status, _, body = request(f"/api/v1/rag/documents/{document_id}")
        if status == 200:
            revision = json.loads(body)["documentRevision"]
            status, _, body = request(f"/api/v1/rag/documents/{document_id}?expectedDocumentRevision={revision}", method="DELETE")
            assert status == 200, (status, body)
        print("PASS: owned fixture cleanup")


if __name__ == "__main__":
    main()
