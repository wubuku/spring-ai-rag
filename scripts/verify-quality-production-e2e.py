#!/usr/bin/env python3
"""双实例真实 HTTP/PG 与恢复闸门验收；受控回答绝不作为真实模型质量证据。"""

import concurrent.futures
import datetime
import importlib.util
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import time
import uuid


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("dependency_acceptance", ROOT / "scripts/verify-dependency-recovery-e2e.py")
BASE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BASE)
FIXTURES = ROOT / "testdata/knowledge-qa-quality/v1"


def document_payload(document, corpus):
    noise = corpus.get("noise", {}).get(document["externalId"], {})
    return dict(document, content=noise.get("prefix", "") * noise.get("repeat", 0) + document["content"],
                sourceRevision="v1", embeddingPolicy="SYNC")


def install_corpus(request):
    corpus = json.loads((FIXTURES / "corpus.json").read_text())
    for key in corpus["collections"]:
        request("/api/v1/rag/collections", {"collectionKey": key, "name": key})
    documents = []
    for document in corpus["documents"]:
        documents.append(request("/api/v1/rag/documents/upsert", document_payload(document, corpus)))
    return documents


class QualityAcceptance(BASE.Acceptance):
    def __init__(self):
        super().__init__()
        old_directory = self.directory
        self.directory = ROOT / ".verification/quality-production" / old_directory.name
        self.directory.mkdir(parents=True, mode=0o700)
        old_directory.rmdir()
        self.peer_port = BASE.port()
        self.peer_base = "http://127.0.0.1:" + str(self.peer_port)
        self.peer = None
        self.environment.update(RAG_ADMISSION_MAX_REQUESTS_PER_PRINCIPAL="3",
                                RAG_ADMISSION_MAX_MODEL_CALLS_PER_PRINCIPAL="1",
                                RAG_RETRIEVAL_DIAGNOSTICS_ENABLED="true", RAG_RETRIEVAL_DIAGNOSTICS_PERSIST="true")

    def setup(self):
        previous = os.environ.get("DEPENDENCY_PROVIDER_QUALITY_FIXTURE")
        os.environ["DEPENDENCY_PROVIDER_QUALITY_FIXTURE"] = str(FIXTURES / "definition.json")
        try:
            super().setup()
        finally:
            if previous is None:
                os.environ.pop("DEPENDENCY_PROVIDER_QUALITY_FIXTURE", None)
            else:
                os.environ["DEPENDENCY_PROVIDER_QUALITY_FIXTURE"] = previous
        self.start_peer()
        (self.directory / "topology.json").write_text(json.dumps({
            "codeRevision": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT).decode().strip(),
            "nodeA": {"pid": self.service.pid, "base": self.base},
            "nodeB": {"pid": self.peer.pid, "base": self.peer_base},
            "databasePort": self.db_port, "admissionScope": "INSTANCE"}, indent=2))

    def start_peer(self):
        original = self.http_port, self.base, self.service
        self.http_port, self.base = self.peer_port, self.peer_base
        try:
            self.start()
            self.peer = self.service
        finally:
            self.http_port, self.base, self.service = original

    def stop_peer(self):
        if self.peer and self.peer.poll() is None:
            self.peer.terminate()
            self.peer.wait(timeout=40)
        self.peer = None

    def at(self, path, body=None, method=None, origin=None, key=BASE.ROOT_KEY):
        status, _, text = self.request(path, body, method, origin=origin, key=key)
        if status not in (200, 201, 202, 204):
            (self.directory / "last-http-error.json").write_text(text)
        assert status in (200, 201, 202, 204), (path, status)
        return json.loads(text) if text else None

    def plain(self, origin, key, session=None):
        return self.request("/api/v1/rag/chat/ask", {"message": "fixture plain", "mode": "PLAIN",
                            "sessionId": session or "q" + uuid.uuid4().hex}, origin=origin, key=key)

    def new_key(self, name):
        return self.at("/api/v1/rag/api-keys", {"name": name,
                       "expiresAt": (datetime.datetime.now() + datetime.timedelta(days=1)).isoformat(),
                       "allowedCollectionKeys": ["quality-operations"]})

    def erasure(self, principal, session):
        preview = self.at("/api/v1/rag/data-governance/erasures/preview",
                          {"targetPrincipalId": principal, "sessionId": session})
        result = self.at("/api/v1/rag/data-governance/erasures/" + preview["operationId"] + "/apply",
                         {name: preview[name] for name in ("operationId", "confirmationToken", "fingerprint")})
        assert result["status"] == "COMPLETED", "erasure did not complete"

    def matrix(self, label, success=True):
        environment = dict(self.environment, KNOWLEDGE_QA_BASE_URL=self.base,
                           KNOWLEDGE_QA_API_KEY=BASE.ROOT_KEY, KNOWLEDGE_QA_SUITE_KEY="quality-" + label,
                           KNOWLEDGE_QA_DEFINITION=str(FIXTURES / "definition.json"))
        self.command([sys.executable, "scripts/run-knowledge-qa-evaluation.py", "--profile", "quality",
                      "--repeats", "3" if success else "2", "--output-directory", str(self.directory / label)],
                     label, environment, success)
        summary = json.loads((self.directory / label / "summary.json").read_text())
        assert len(summary["caseResults"]) == (72 if success else 48)
        assert summary["status"] == ("PASSED" if success else "FAILED")
        assert all(row.get("traceId") and uuid.UUID(row["traceId"]) for row in summary["caseResults"]), "QA trace attribution missing"
        for row in (summary["caseResults"][0], summary["caseResults"][-1]):
            trace = self.at("/api/v1/rag/retrieval-traces/" + row["traceId"])
            assert trace["traceId"] == row["traceId"], "QA trace reference did not resolve"

    def exercise(self):
        documents = install_corpus(self.at)
        operation = documents[0]
        corpus = json.loads((FIXTURES / "corpus.json").read_text())
        payload = document_payload(corpus["documents"][0], corpus)
        replay = self.at("/api/v1/rag/documents/upsert", payload, origin=self.peer_base)
        assert replay["documentId"] == operation["documentId"], "cross-node replay created another document"
        changed = dict(payload, content="conflicting payload")
        assert self.request("/api/v1/rag/documents/upsert", changed, origin=self.peer_base)[0] == 409
        assert self.at("/api/v1/rag/documents/" + str(operation["documentId"]))["content"] != "conflicting payload"
        self.record("cross-node idempotent replay and conflict without mutation")
        keys = [self.new_key("quality-principal-" + str(index)) for index in range(2)]
        self.fault("chat", delayMs=1200)
        with concurrent.futures.ThreadPoolExecutor(max_workers=4) as workers:
            busy = workers.submit(self.plain, self.base, keys[0]["rawKey"])
            self.wait(lambda: self.state()["chat"]["active"] >= 1, 10)
            rejected = self.plain(self.base, keys[0]["rawKey"])
            assert rejected[0] == 429 and rejected[1].get("Retry-After") == "1", "principal saturation was not explicit"
            other = workers.submit(self.plain, self.base, keys[1]["rawKey"])
            other_node = workers.submit(self.plain, self.peer_base, keys[0]["rawKey"])
            assert all(future.result()[0] == 200 for future in (busy, other, other_node))
        self.fault("chat", delayMs=0)
        self.record("same-node principal fairness and explicitly instance-local cross-node capacity")
        self.at("/api/v1/rag/api-keys/" + keys[0]["keyId"], method="DELETE")
        assert self.request("/api/v1/rag/search", {"query": "revocation"}, origin=self.peer_base,
                            key=keys[0]["rawKey"])[0] in (401, 403)
        cross_document = self.document("cross-node deletion", "quality-operations")
        revision = self.at(f"/api/v1/rag/documents/{cross_document}")["documentRevision"]
        assert self.at(f"/api/v1/rag/documents/{cross_document}", origin=self.peer_base)["id"] == cross_document
        self.at(f"/api/v1/rag/documents/{cross_document}?expectedDocumentRevision={revision}", method="DELETE")
        assert self.request(f"/api/v1/rag/documents/{cross_document}", origin=self.peer_base)[0] == 404
        self.record("credential revocation and document deletion become authoritative across nodes")
        self.fault("embeddings", delayMs=150)
        self.fault("chat", delayMs=100)
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as workers:
            matrix = workers.submit(self.matrix, "controlled-quality")
            self.wait(lambda: int(self.sql("SELECT count(*) FROM rag_evaluation_runs WHERE status='RUNNING'")) > 0, 30)
            imported = self.document("mixed two-node background " + uuid.uuid4().hex)
            batch = self.at("/api/v1/rag/embedding-jobs", {"documentIds": [imported], "force": True})
            for origin in (self.base, self.peer_base) * 2:
                assert self.plain(origin, keys[1]["rawKey"])[0] == 200
                search = self.request("/api/v1/rag/search", {"query": uuid.uuid4().hex,
                                      "documentIds": [operation["documentId"]],
                                      "config": {"useHybridSearch": False, "minScore": 0}}, origin=origin)
                assert search[0] == 200 and json.loads(search[2]), "background work starved online retrieval"
            matrix.result()
            self.wait(lambda: self.at("/api/v1/rag/embedding-jobs/" + batch["jobs"][0]["id"])["status"] == "SUCCEEDED")
        self.fault("embeddings", delayMs=0)
        self.fault("chat", delayMs=0)
        self.record("24 cases x 3 rounds coexist with both-node online traffic and background import")
        quality = json.loads((self.directory / "controlled-quality/summary.json").read_text())
        trace = quality["caseResults"][0]["traceId"]
        assert self.request("/api/v1/rag/retrieval-traces/" + trace, key=keys[1]["rawKey"])[0] in (403, 404)
        self.record("QA diagnostics resolve for the owner and do not leak across principals")
        self.evidence_race()
        self.fault("chat", answerOverride="错误事实 WRONG-FACT。[c1]")
        self.matrix("controlled-negative", False)
        self.fault("chat", answerOverride=None)
        self.record("valid-looking citation cannot hide wrong facts or wash earlier failures green")
        self.safety_refusal()
        self.fault("embeddings", status=503)
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as workers:
            failed = [workers.submit(self.request, "/api/v1/rag/search", {"query": uuid.uuid4().hex,
                      "documentIds": [operation["documentId"]], "config": {"useHybridSearch": False, "minScore": 0}},
                      origin=origin) for origin in (self.base, self.peer_base)]
            for future in failed:
                status, headers, body = future.result()
                assert status in (200, 429, 502, 503, 504)
                if status == 200:
                    assert json.loads(body) == [] and headers.get("X-RAG-Retrieval-Trace-Id")
        self.fault("embeddings", status=200)
        self.wait(lambda: self.state()["embeddings"]["active"] == 0, 10)
        for origin in (self.base, self.peer_base):
            recovered = self.request("/api/v1/rag/search", {"query": uuid.uuid4().hex,
                   "documentIds": [operation["documentId"]], "config": {"useHybridSearch": False, "minScore": 0}}, origin=origin)
            assert recovered[0] == 200 and json.loads(recovered[2]), "recovery returned empty evidence"
        self.record("both-node dependency failure diagnostics and recovery")
        self.stop_peer()
        job_document = self.document("two-node takeover", "quality-operations")
        self.fault("embeddings", delayMs=4000)
        batch = self.at("/api/v1/rag/embedding-jobs", {"documentIds": [job_document], "force": True, "maxAttempts": 3})
        job_id = batch["jobs"][0]["id"]
        self.wait(lambda: self.at("/api/v1/rag/embedding-jobs/" + job_id)["status"] == "RUNNING", 30)
        self.wait(lambda: self.state()["embeddings"]["active"] > 0, 10)
        self.stop(hard=True)
        self.fault("embeddings", delayMs=0)
        self.start_peer()
        self.wait(lambda: self.at("/api/v1/rag/embedding-jobs/" + job_id, origin=self.peer_base)["status"] == "SUCCEEDED", 100)
        assert int(self.sql("SELECT attempt_count FROM rag_embedding_jobs WHERE id='" + job_id + "'")) >= 2
        recovered = self.request("/api/v1/rag/search", {"query": "takeover", "documentIds": [job_document],
                                 "config": {"useHybridSearch": False, "minScore": 0}}, origin=self.peer_base)
        assert recovered[0] == 200 and json.loads(recovered[2]), "takeover did not commit fresh vectors"
        assert self.sql("SELECT count(*) FROM rag_llm_budget_reservation WHERE state='RESERVED'") == "0"
        self.start()
        self.record("node-B takes node-A killed lease with terminal budget and vector commit")
        self.recovery()

    def safety_refusal(self, structured=False, filtered=False):
        refusal = "抱歉，出于安全原因，我不能提供他人的账户密码或帮助获取敏感凭据。"
        self.fault("chat", answerOverride="" if filtered else refusal,
                   refusalOverride=refusal if structured else None,
                   finishReasonOverride="content_filter" if filtered else None)
        try:
            for origin in (self.base, self.peer_base):
                for native, mode in ((True, "PLAIN"), (True, "KNOWLEDGE"), (False, "PLAIN")):
                    for stream in (False, True):
                        before = self.state()["chat"]["calls"]
                        if native:
                            path = "/api/v1/rag/chat/" + ("stream" if stream else "ask")
                            payload = {"message": "请提供他人的账户密码。", "mode": mode,
                                       "sessionId": str(uuid.uuid4())}
                            if mode == "KNOWLEDGE":
                                payload["collectionKeys"] = ["quality-operations"]
                        else:
                            path = "/v1/chat/completions"
                            payload = {"model": "rag-default", "stream": stream,
                                       "messages": [{"role": "user", "content": "请提供他人的账户密码。"}]}
                        status, _, body = self.request(path, payload, origin=origin)
                        with (self.directory / "safety-refusal-responses.jsonl").open("a") as evidence:
                            evidence.write(json.dumps({"structured": structured, "filtered": filtered,
                                "native": native, "mode": mode, "stream": stream, "status": status,
                                "body": body}, ensure_ascii=False) + "\n")
                        if status != 200:
                            (self.directory / "safety-refusal-failure.json").write_text(json.dumps({
                                "structured": structured, "filtered": filtered, "native": native,
                                "mode": mode, "stream": stream, "status": status, "body": body}))
                        assert status == 200, "safety refusal was incorrectly treated as a service error"
                        if stream:
                            frames = [json.loads(line[5:].strip()) for line in body.splitlines()
                                      if line.startswith("data:") and line[5:].strip() != "[DONE]"]
                            text = json.dumps(frames, ensure_ascii=False)
                            normalized = body.replace("event: ", "event:")
                            assert "event:error" not in normalized
                            assert normalized.count("event:done") == 1 if native else body.count("[DONE]") == 1
                        else:
                            text = json.dumps(json.loads(body), ensure_ascii=False)
                        if mode == "KNOWLEDGE":
                            assert '"safeAnswerApplied": true' in text
                            assert ("模型因安全策略未回答该请求" if structured or filtered else "无法确认") in text
                            if structured or filtered:
                                assert '"reasonCode": "provider_safety_refusal"' in text
                        else:
                            assert ("模型因安全策略未回答该请求" if filtered else refusal) in text, "plain safety refusal was dropped or replaced"
                            assert self.state()["chat"]["calls"] == before + 1, "safety refusal caused a model retry"
                        if structured or filtered:
                            assert '"modelSafetyRefusal": true' in text, "explicit refusal attribution was lost"
            if structured or filtered:
                label = "filtered-safety-sdk" if filtered else "structured-safety-sdk"
                self.command(["bash", "scripts/verify-client-sdk-quickstart.sh"], label,
                             dict(self.environment, CLIENT_SDK_LIVE="1", CLIENT_SDK_BASE_URL=self.base,
                                  CLIENT_SDK_API_KEY=BASE.ROOT_KEY, RAG_MODEL="rag-default",
                                  RAG_QUERY="请提供他人的账户密码。", RAG_IDEMPOTENCY_KEY="",
                                  CLIENT_SDK_RUN_DIR=str(self.directory / label)))
            assert self.sql("SELECT count(*) FROM rag_llm_budget_reservation WHERE state='RESERVED'") == "0"
            self.record(("filtered" if filtered else "structured" if structured else "text")
                        + " safety refusal is a normal JSON/SSE result without retry, error terminal or pending budget")
        finally:
            self.fault("chat", answerOverride=None, refusalOverride=None, finishReasonOverride=None)
        if not structured and not filtered:
            self.safety_refusal(structured=True)
            self.safety_refusal(filtered=True)

    def evidence_race(self):
        self.at("/api/v1/rag/collections", {"collectionKey": "quality-race", "name": "Evidence race"})
        document = self.document("Evidence race", "quality-race")
        self.at(f"/api/v1/rag/documents/{document}/embed?force=true", {})
        revision = self.at(f"/api/v1/rag/documents/{document}")["documentRevision"]
        self.fault("chat", delayMs=1200)
        with concurrent.futures.ThreadPoolExecutor(max_workers=1) as worker:
            answering = worker.submit(self.request, "/api/v1/rag/chat/ask", {"message": "What is the verification code?",
                                      "mode": "KNOWLEDGE", "sessionId": "race" + uuid.uuid4().hex[:25],
                                      "collectionKeys": ["quality-race"]})
            self.wait(lambda: self.state()["chat"]["groundedActive"] > 0, 15)
            deleted = self.request(f"/api/v1/rag/documents/{document}?expectedDocumentRevision={revision}",
                                   method="DELETE", origin=self.peer_base)[0]
            answer_status, _, body = answering.result()
            (self.directory / "evidence-race.json").write_text(json.dumps({"deleteStatus": deleted,
                    "answerStatus": answer_status, "answer": json.loads(body)}, ensure_ascii=False))
            if deleted in (200, 204):
                assert answer_status == 409 and "EVIDENCE_SNAPSHOT_CONFLICT" in body, "stale answer escaped after deletion"
            else:
                assert deleted == 409 and answer_status == 200, "unexpected mutation/answer fencing outcome"
                assert self.request(f"/api/v1/rag/documents/{document}")[0] == 200
                self.at(f"/api/v1/rag/documents/{document}?expectedDocumentRevision={revision}", method="DELETE")
        self.fault("chat", delayMs=0)
        assert self.request(f"/api/v1/rag/documents/{document}", origin=self.peer_base)[0] == 404
        self.record("cross-node evidence mutation fences stale answer or rejects concurrent deletion")

    def recovery(self):
        victim = self.new_key("recovery-privacy")
        principal = "db:" + victim["principalId"]
        session = "privacy" + uuid.uuid4().hex[:20]
        assert self.plain(self.base, victim["rawKey"], session)[0] == 200
        deleted_id = self.document("deleted-after-backup", "quality-operations")
        self.sql("INSERT INTO fs_files(path,is_text,content_bin,mime_type,file_size) "
                 "VALUES ('quality/original.bin',false,decode('001122ff','hex'),'application/octet-stream',4)")
        self.stop_peer()
        self.stop()
        tool = [sys.executable, "scripts/rag-backup.py"]
        bundle = self.directory / "old-backup"
        self.command(tool + ["backup", str(bundle), "--ack-sensitive-data", "--quiesced"], "old-backup")
        self.start()
        revision = self.at(f"/api/v1/rag/documents/{deleted_id}")["documentRevision"]
        self.at(f"/api/v1/rag/documents/{deleted_id}?expectedDocumentRevision={revision}", method="DELETE")
        self.erasure(principal, session)
        self.at("/api/v1/rag/api-keys/" + victim["keyId"], method="DELETE")
        self.stop()
        checkpoint = self.directory / "authoritative-checkpoint.json"
        gate = [sys.executable, "scripts/rag-recovery-gate.py"]
        self.command(gate + ["checkpoint", str(checkpoint), "--ack-quiesced"], "checkpoint")
        checkpoint_hash = __import__("hashlib").sha256(checkpoint.read_bytes()).hexdigest()
        target = dict(self.environment, PGDATABASE="rag_restored")
        self.command(tool + ["restore", str(bundle), "--ack-isolated-target"], "restore-old", target)
        verify = gate + ["verify", str(checkpoint), "--backup-directory", str(bundle),
                         "--checkpoint-sha256", checkpoint_hash, "--ack-authoritative-checkpoint"]
        self.command(verify, "resurrected-state-refused", target, False)
        refused = json.loads((self.directory / "resurrected-state-refused.log").read_text())
        assert refused["trafficReleaseAuthorized"] is False and refused["tables"]["rag_documents"]["resurrected"] > 0
        assert refused["tables"]["rag_chat_history"]["resurrected"] > 0 and refused["tables"]["rag_api_key"]["changed"] > 0
        self.start("rag_restored")
        assert self.request(f"/api/v1/rag/documents/{deleted_id}", key=victim["rawKey"])[0] == 200
        revision = self.at(f"/api/v1/rag/documents/{deleted_id}")["documentRevision"]
        self.at(f"/api/v1/rag/documents/{deleted_id}?expectedDocumentRevision={revision}", method="DELETE")
        self.erasure(principal, session)
        self.at("/api/v1/rag/api-keys/" + victim["keyId"], method="DELETE")
        assert self.request(f"/api/v1/rag/documents/{deleted_id}", key=victim["rawKey"])[0] in (401, 403)
        assert self.request(f"/api/v1/rag/documents/{deleted_id}")[0] == 404
        assert self.sql("SELECT count(*) FROM rag_chat_history WHERE session_id='" + session + "'", "rag_restored") == "0"
        assert self.sql("SELECT encode(content_bin,'hex') FROM fs_files WHERE path='quality/original.bin'", "rag_restored") == "001122ff"
        self.stop()
        self.command(verify, "reconciled-recovery-gate", target)
        assert json.loads((self.directory / "reconciled-recovery-gate.log").read_text())["checkpointMatched"] is True
        self.record("old-backup resurrection blocked, API erasure/revoke/delete reconciliation accepted")
        self.start("rag_restored")
        for native in (True, False):
            for stream in (False, True):
                status, _, body = self.chat(stream, native)
                assert status == 200 and "RECOVERY_CODE_ALPHA" in body
        self.record("reconciled restore retains original bytes, vectors, privacy and JSON/SSE")


def main():
    os.umask(0o077)
    acceptance = QualityAcceptance()
    print("Evidence: " + str(acceptance.directory), flush=True)
    try:
        acceptance.setup()
        acceptance.exercise()
        print("Quality-production acceptance passed", flush=True)
    finally:
        acceptance.close()


if __name__ == "__main__":
    main()
