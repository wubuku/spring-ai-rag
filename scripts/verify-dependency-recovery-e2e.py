#!/usr/bin/env python3
"""自有隔离 PostgreSQL/真实 HTTP 的故障、混载、重启与 dump/restore 验收。"""

import concurrent.futures
import contextlib
import datetime
import json
import os
from pathlib import Path
import shutil
import signal
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid


ROOT = Path(__file__).resolve().parents[1]
ROOT_KEY = "dependency-recovery-fixture-root-key-20261018"


def assert_database_identity(expected, observed, phase):
    if not isinstance(expected, str) or not expected or observed != expected:
        raise RuntimeError("database identity assertion failed before " + phase)


def assert_database_startup_log(expected_target, log_bytes, previous_byte_count):
    if (not isinstance(expected_target, str) or not expected_target
            or not isinstance(log_bytes, bytes)
            or type(previous_byte_count) is not int
            or previous_byte_count < 0 or previous_byte_count > len(log_bytes)
            or expected_target.encode("utf-8") not in log_bytes[previous_byte_count:]):
        raise RuntimeError("current startup JDBC target assertion failed")


def port():
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        return listener.getsockname()[1]


class Acceptance:
    def __init__(self):
        self.directory = ROOT / ".verification" / "dependency-recovery" / (time.strftime("%Y%m%d-%H%M%S") + "-" + uuid.uuid4().hex[:8])
        self.directory.mkdir(parents=True, mode=0o700)
        self.container = "rag-dependency-recovery-" + uuid.uuid4().hex[:12]
        self.http_port = port()
        self.provider_port = port()
        self.db_port = port()
        self.base = "http://127.0.0.1:" + str(self.http_port)
        self.provider = "http://127.0.0.1:" + str(self.provider_port)
        self.processes = []
        self.service = None
        self.results = []
        self.environment = dict(os.environ, PGHOST="127.0.0.1", PGPORT=str(self.db_port), PGUSER="postgres",
                                PGPASSWORD="postgres", PGDATABASE="rag_source")
        self.environment.pop("PGSERVICE", None)
        self.environment.pop("PGSERVICEFILE", None)

    def command(self, command, label, environment=None, success=True):
        with (self.directory / (label + ".log")).open("ab") as log:
            result = subprocess.run(command, cwd=ROOT, env=environment or self.environment, stdout=log, stderr=log)
        assert (result.returncode == 0) == success, label + " failed; inspect private log"

    def sql(self, statement, database="rag_source"):
        result = subprocess.run(["docker", "exec", self.container, "psql", "-h", "127.0.0.1", "-XqAt", "-U", "postgres", "-d", database,
                                 "-v", "ON_ERROR_STOP=1", "-c", statement], capture_output=True, check=True)
        return result.stdout.decode().strip()

    def request(self, path, body=None, method=None, origin=None, key=ROOT_KEY):
        request = urllib.request.Request((origin or self.base) + path,
                                         data=json.dumps(body).encode() if body is not None else None,
                                         method=method, headers={"Content-Type": "application/json",
                                                                 "X-API-Key": key, "Authorization": "Bearer " + key})
        try:
            response = urllib.request.urlopen(request, timeout=45)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            return response.status, dict(response.headers), response.read().decode()

    def json(self, path, body=None, method=None):
        status, _, value = self.request(path, body, method)
        if status not in (200, 201, 202):
            (self.directory / "last-http-error.json").write_text(value)
        assert status in (200, 201, 202), (path, status)
        return json.loads(value)

    def fault(self, kind, **values):
        self.request("/control/" + kind, values, origin=self.provider)

    def state(self):
        return json.loads(self.request("/control/state", origin=self.provider)[2])

    def wait(self, predicate, timeout=90):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if predicate():
                return
            time.sleep(0.2)
        raise AssertionError("Acceptance deadline exceeded")

    def record(self, name, **details):
        self.results.append(dict(name=name, status="PASS", **details))
        print("PASS: " + name, flush=True)
        (self.directory / "summary.json").write_text(json.dumps(self.results, indent=2) + "\n")

    def setup(self):
        self.command(["mvn", "-q", "-pl", "spring-ai-rag-core", "-am", "-DskipTests", "compile"], "compile")
        self.command(["mvn", "-q", "-pl", "spring-ai-rag-core", "-am", "dependency:build-classpath",
                      "-Dmdep.outputFile=" + str(self.directory / "dependencies.txt"), "-DincludeScope=runtime"], "classpath")
        classpaths = []
        for module in ("api", "documents", "core"):
            target = self.directory / "runtime" / module
            shutil.copytree(ROOT / ("spring-ai-rag-" + module) / "target/classes", target)
            classpaths.append(str(target))
        self.classpath = ":".join(classpaths) + ":" + (self.directory / "dependencies.txt").read_text().strip()
        self.command(["docker", "run", "--rm", "-d", "--name", self.container,
                      "-e", "POSTGRES_PASSWORD=postgres", "-e", "POSTGRES_DB=rag_source",
                      "-p", "127.0.0.1:" + str(self.db_port) + ":5432",
                      "-v", str(self.directory) + ":" + str(self.directory),
                      os.environ.get("TESTCONTAINERS_PG_IMAGE", "pgvector/pgvector:pg16")], "docker")
        self.wait(lambda: subprocess.run(["docker", "exec", self.container, "pg_isready", "-h", "127.0.0.1", "-U", "postgres", "-d", "rag_source"],
                                          capture_output=True).returncode == 0, 60)
        self.sql("CREATE DATABASE rag_restored")
        binary_directory = self.directory / "bin"
        binary_directory.mkdir()
        for name in ("psql", "pg_dump", "pg_restore"):
            wrapper = binary_directory / name
            wrapper.write_text("#!/bin/sh\nexec docker exec -i --user " + str(os.getuid()) + ":" + str(os.getgid())
                               + " -w /tmp -e PGDATABASE -e PGUSER -e PGPASSWORD "
                               "-e PGHOST=127.0.0.1 -e PGPORT=5432 " + self.container + " " + name + ' "$@"\n')
            wrapper.chmod(0o700)
        self.environment["PATH"] = str(binary_directory) + ":" + os.environ["PATH"]
        provider = subprocess.Popen([sys.executable, "scripts/test-support/dependency-resilience-provider.py",
                                     "--port", str(self.provider_port)], cwd=ROOT,
                                    stdout=(self.directory / "provider.log").open("ab"), stderr=subprocess.STDOUT)
        self.processes.append(provider)
        self.wait(lambda: self.provider_ready(), 15)
        self.start()

    def provider_ready(self):
        try:
            return self.request("/control/state", origin=self.provider)[0] == 200
        except OSError:
            return False

    def start(self, database="rag_source", chat_attempts=1):
        environment = dict(self.environment, SERVER_PORT=str(self.http_port), SPRING_PROFILES_ACTIVE="postgresql",
                           SPRING_DATASOURCE_URL="jdbc:postgresql://127.0.0.1:" + str(self.db_port) + "/" + database,
                           SPRING_DATASOURCE_USERNAME="postgres", SPRING_DATASOURCE_PASSWORD="postgres",
                           SPRING_AI_OPENAI_API_KEY="fixture-key", SPRING_AI_OPENAI_BASE_URL=self.provider,
                           SPRING_AI_OPENAI_CHAT_OPTIONS_MODEL="controlled", LLM_PROVIDER="openai", APP_LLM_PROVIDER="openai",
                           RAG_EMBEDDING_API_KEY="fixture-key", RAG_EMBEDDING_BASE_URL=self.provider,
                           RAG_EMBEDDING_MODEL="controlled-embedding", RAG_EMBEDDING_DIMENSIONS="1024",
                           RAG_EMBEDDING_PROFILE_KEY="controlled-recovery-1024-v1", MODELS_CONFIG_FILE="",
                           RAG_ROOT_API_KEY=ROOT_KEY, RAG_SECURITY_API_KEY=ROOT_KEY,
                           RAG_SECURITY_ENABLED="true", RAG_RATE_LIMIT_REQUESTS_PER_MINUTE="10000")
        self.service = subprocess.Popen(["java", "-cp", self.classpath, "com.springairag.core.SpringAiRagApplication",
                                         "--rag.admission.max-embedding-calls=2", "--rag.admission.max-background-embedding-calls=4",
                                         "--rag.admission.max-rerank-calls=1", "--rag.admission.dependency-timeout-ms=3000",
                                         "--rag.admission.background-embedding-timeout-ms=6000", "--rag.retry.max-attempts=" + str(chat_attempts),
                                         "--rag.embedding-jobs.poll-interval-ms=10000", "--rag.embedding-jobs.lease-seconds=30",
                                         "--rag.rerank.enabled=true", "--rag.rerank.provider=http",
                                         "--rag.rerank.base-url=" + self.provider, "--rag.rerank.api-key=fixture-key",
                                         "--rag.openai-compatibility.enabled=true", "--rag.openai-compatibility.require-explicit-scope=false",
                                         "--rag.openai-compatibility.models.rag-default.mode=PLAIN"],
                                        cwd=ROOT, env=environment,
                                        stdout=(self.directory / (database + "-server.log")).open("ab"), stderr=subprocess.STDOUT)
        self.processes.append(self.service)
        self.wait(lambda: self.ready(), 120)

    def ready(self):
        assert self.service.poll() is None, "server exited; inspect private server log"
        try:
            return self.request("/actuator/health")[0] == 200
        except OSError:
            return False

    def stop(self, hard=False):
        self.service.send_signal(signal.SIGKILL if hard else signal.SIGTERM)
        self.service.wait(timeout=40)
        self.service = None

    def document(self, title, collection_key=None):
        return self.json("/api/v1/rag/documents", {"title": title, "collectionKey": collection_key,
                         "content": "The verification code is RECOVERY_CODE_ALPHA. " + title})["id"]

    def search(self, query, document_id, rerank=False):
        return self.request("/api/v1/rag/search", {"query": query, "documentIds": [document_id],
                            "config": {"maxResults": 3, "minScore": 0, "useHybridSearch": False, "useRerank": rerank}})

    def chat(self, stream=False, native=True):
        if native:
            return self.request("/api/v1/rag/chat/" + ("stream" if stream else "ask"),
                                {"message": "fixture plain", "mode": "PLAIN", "sessionId": "rc-" + uuid.uuid4().hex})
        return self.request("/v1/chat/completions", {"model": "rag-default", "stream": stream,
                            "messages": [{"role": "user", "content": "fixture plain"}]})

    def fingerprint(self, database):
        tables = self.sql("SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename", database).splitlines()
        return {table: self.sql('SELECT count(*)::text || \':\' || md5(coalesce(string_agg(payload, \'\' ORDER BY payload), \'\')) '
                               'FROM (SELECT row_to_json(entry)::text AS payload FROM public."' + table + '" entry) rows', database)
                for table in tables}

    def exercise(self):
        document_id = self.document("Recovery acceptance")
        self.json("/api/v1/rag/collections", {"collectionKey": "recovery-public", "name": "Recovery scoped data"})
        scoped_document_id = self.json("/api/v1/rag/documents/upsert", {"collectionKey": "recovery-public",
                                      "externalId": "recovery-fact", "sourceRevision": "v1", "title": "Scoped original",
                                      "content": "The verification code is RECOVERY_CODE_ALPHA.",
                                      "embeddingPolicy": "SYNC"})["documentId"]
        expires = (datetime.datetime.now() + datetime.timedelta(days=1)).isoformat()
        scoped_key = self.json("/api/v1/rag/api-keys", {"name": "Recovery read-only principal", "expiresAt": expires,
                               "allowedCollectionKeys": ["recovery-public"], "capabilities": ["RAG_READ"]})["rawKey"]
        assert self.request(f"/api/v1/rag/documents/{scoped_document_id}", key=scoped_key)[0] == 200
        assert self.request(f"/api/v1/rag/documents/{document_id}", key=scoped_key)[0] in (403, 404)
        assert self.request("/api/v1/rag/documents", {"title": "forbidden", "content": "not permitted"}, key=scoped_key)[0] == 403
        self.json(f"/api/v1/rag/documents/{document_id}/embed?force=true", {})
        assert json.loads(self.search("first", document_id)[2])
        for native in (True, False):
            for stream in (False, True):
                status, _, body = self.chat(stream, native)
                assert status == 200 and "RECOVERY_CODE_ALPHA" in body, ("chat", native, stream, status)
                if stream:
                    assert ("[DONE]" in body) if not native else ("event:done" in body.replace("event: ", "event:"))
        assert self.request("/api/v1/rag/documents/" + str(document_id), key="invalid-fixture-key")[0] in (401, 403)
        self.record("Native/OpenAI JSON+SSE success content/terminal and authentication")
        for fault in ({"status": 429}, {"status": 503}, {"disconnect": True}, {"delayMs": 4000}):
            self.fault("embeddings", **fault)
            started = time.monotonic()
            status, headers, body = self.search(uuid.uuid4().hex, document_id)
            assert time.monotonic() - started < 6, "embedding deadline exceeded"
            assert status in (200, 429, 502, 503, 504)
            if status == 200:
                assert json.loads(body) == [] and headers.get("X-RAG-Retrieval-Trace-Id"), "failure disguised as success"
                trace = self.json("/api/v1/rag/retrieval-traces/" + headers["X-RAG-Retrieval-Trace-Id"])
                assert trace["outcomeCode"] != "SUCCESS" or trace["emptyReasonCode"], "missing failure diagnostics"
            self.fault("embeddings", status=200, delayMs=0, disconnect=False)
            self.wait(lambda: self.state()["embeddings"]["active"] == 0, 10)
            assert json.loads(self.search(uuid.uuid4().hex, document_id)[2])
            self.record("embedding fault/recovery " + str(fault))
        for fault in ({"status": 429}, {"status": 503}, {"disconnect": True}, {"delayMs": 4000}):
            self.fault("rerank", **fault)
            status, _, body = self.search(uuid.uuid4().hex, document_id, True)
            assert status == 200 and json.loads(body), "rerank fallback lost evidence"
            self.fault("rerank", status=200, delayMs=0, disconnect=False)
            self.wait(lambda: self.state()["rerank"]["active"] == 0, 10)
            self.record("rerank bounded fallback " + str(fault))
        self.fault("embeddings", delayMs=500)
        self.fault("chat", delayMs=100)
        import_document_id = self.document("Concurrent import")
        self.json("/api/v1/rag/evaluation/suites", {"suiteKey": "recovery-mixed", "name": "Mixed workload QA"})
        definition = {"cases": [{"id": "fact", "query": "What is the verification code?", "task": "KNOWLEDGE_QA",
                       "scope": {"mode": "SELECTED_COLLECTIONS", "collectionKeys": ["recovery-public"]},
                       "relevant": [{"collectionKey": "recovery-public", "externalId": "recovery-fact"}],
                       "requiredFacts": ["RECOVERY_CODE_ALPHA"], "forbiddenFacts": [],
                       "evidenceExpectation": "SUFFICIENT", "judge": {"enabled": False}}],
                      "variants": [{"key": "default", "minScore": 0, "maxResults": 3, "hybrid": False, "rerank": False}]}
        version = self.json("/api/v1/rag/evaluation/suites/recovery-mixed/versions", {"definition": definition})["version"]
        latencies = {"search": [], "native": [], "openai": []}
        capacity = []
        def call(index):
            started = time.monotonic()
            result = self.search(uuid.uuid4().hex, document_id, True) if index % 3 == 0 else self.chat(index % 2 == 0, index % 3 == 1)
            elapsed = time.monotonic() - started
            assert result[0] in (200, 429), result[0]
            if result[0] == 429:
                assert result[1].get("Retry-After") == "1"
                capacity.append(index)
            elif index % 3 == 0 and not json.loads(result[2]):
                trace = self.json("/api/v1/rag/retrieval-traces/" + result[1]["X-RAG-Retrieval-Trace-Id"])
                assert "EMBEDDING_CAPACITY" in json.dumps(trace) or "AdmissionRejectedException" in json.dumps(trace)
                capacity.append(index)
            else:
                if index % 3 != 0:
                    assert "RECOVERY_CODE_ALPHA" in result[2]
                latencies[("search", "native", "openai")[index % 3]].append(elapsed)
        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as workers:
            imported = workers.submit(self.json, f"/api/v1/rag/documents/{import_document_id}/embed?force=true", {})
            evaluation = self.json("/api/v1/rag/evaluation/runs", {"suiteKey": "recovery-mixed", "version": version,
                                   "variantKeys": ["default"]})
            list(workers.map(call, range(24)))
            assert imported.result()["status"] == "COMPLETED"
            self.wait(lambda: self.json("/api/v1/rag/evaluation/runs/" + evaluation["id"])["status"] not in ("PENDING", "RUNNING", "STARTED"), 60)
            completed = self.json("/api/v1/rag/evaluation/runs/" + evaluation["id"])
            (self.directory / "controlled-qa.json").write_text(json.dumps(completed))
            assert completed["status"] == "PASSED" and completed["cases"], "mixed QA did not pass"
            assert all(case["status"] == "PASSED" and case["metrics"].get("deterministicPass") is True
                       for case in completed["cases"]), "mixed QA hard assertions did not pass"
        self.fault("embeddings", delayMs=0)
        self.fault("chat", delayMs=0)
        self.wait(lambda: all(value["active"] == 0 for value in self.state().values()), 15)
        state = self.state()
        assert state["embeddings"]["peak"] <= 6 and state["rerank"]["peak"] <= 1, state
        summaries = {}
        for operation, samples in latencies.items():
            assert samples and max(samples) < 6, (operation, samples)
            ordered = sorted(samples)
            summaries[operation] = {"count": len(samples), "p95Seconds": round(ordered[min(len(ordered)-1, int(len(ordered)*0.95))], 3),
                                    "p99Seconds": round(ordered[min(len(ordered)-1, int(len(ordered)*0.99))], 3)}
        self.record("mixed HTTP load/import/QA bounded capacity/recovery", requests=24, explicitCapacity=len(capacity),
                    successfulLatency=summaries, providerPeak={kind: value["peak"] for kind, value in state.items()})
        k6_image = os.environ.get("DEPENDENCY_RECOVERY_K6_IMAGE")
        if k6_image:
            network = ["--add-host", "host.docker.internal:host-gateway"] if sys.platform.startswith("linux") else []
            self.command(["docker", "run", "--rm", *network, "-v", str(ROOT / "scripts/k6-load-test.js") + ":/test.js:ro",
                          "-v", str(self.directory) + ":/results", "--user", "0", "-e", "K6_PROFILE=reliability",
                          "-e", "BASE_URL=http://host.docker.internal:" + str(self.http_port), "-e", "API_KEY=" + ROOT_KEY,
                          "-e", "K6_DOCUMENT_ID=" + str(document_id), "-e", "RELIABILITY_VUS=2",
                          "-e", "RELIABILITY_DURATION=12s", "-e", "RELIABILITY_MIN_SUCCESS_RATE=1",
                          k6_image, "run", "--summary-export=/results/k6-summary.json", "/test.js"], "k6-reliability")
            self.record("k6 reliability success/error/latency hard gates")
        self.fault("chat", delayMs=2000)
        payload = json.dumps({"message": "cancel fixture", "mode": "PLAIN", "sessionId": "cancel-" + uuid.uuid4().hex[:24]}).encode()
        with socket.create_connection(("127.0.0.1", self.http_port), timeout=5) as client:
            headers = ("POST /api/v1/rag/chat/stream HTTP/1.1\r\nHost: localhost\r\nX-API-Key: " + ROOT_KEY
                       + "\r\nContent-Type: application/json\r\nContent-Length: " + str(len(payload)) + "\r\n\r\n")
            client.sendall(headers.encode() + payload)
            self.wait(lambda: self.state()["chat"]["active"] > 0, 10)
        self.wait(lambda: self.state()["chat"]["active"] == 0, 10)
        self.fault("chat", delayMs=0)
        assert self.chat()[0] == 200
        self.record("real HTTP client disconnect drains provider and permits recovery")
        job_doc = self.document("Restart durable job")
        self.fault("embeddings", delayMs=4000)
        batch = self.json("/api/v1/rag/embedding-jobs", {"documentIds": [job_doc], "force": True, "maxAttempts": 3})
        job_id = batch["jobs"][0]["id"]
        self.wait(lambda: self.json("/api/v1/rag/embedding-jobs/" + job_id)["status"] == "RUNNING", 30)
        self.stop(hard=True)
        self.fault("embeddings", delayMs=0)
        self.wait(lambda: self.state()["embeddings"]["active"] == 0, 10)
        self.start()
        self.wait(lambda: self.json("/api/v1/rag/embedding-jobs/" + job_id)["status"] == "SUCCEEDED", 90)
        assert json.loads(self.search("restart", job_doc)[2])
        assert self.sql("SELECT count(*) FROM rag_llm_budget_reservation WHERE state='RESERVED'") == "0"
        self.record("SIGKILL/restart lease takeover, vector commit and budget terminal")
        deleted_id = self.document("Deleted before backup")
        revision = self.json(f"/api/v1/rag/documents/{deleted_id}")["documentRevision"]
        assert self.request(f"/api/v1/rag/documents/{deleted_id}?expectedDocumentRevision={revision}", method="DELETE")[0] in (200, 204)
        self.sql("INSERT INTO fs_files(path,is_text,content_bin,mime_type,file_size) "
                 "VALUES ('recovery/original.bin',false,decode('001122ff','hex'),'application/octet-stream',4)")
        self.stop()
        before = self.fingerprint("rag_source")
        bundle = self.directory / "backup"
        backup_tool = [sys.executable, "scripts/rag-backup.py"]
        self.command(backup_tool + ["backup", str(bundle), "--ack-sensitive-data", "--quiesced"], "backup")
        self.command(backup_tool + ["verify", str(bundle)], "verify")
        self.command(backup_tool + ["restore", str(bundle), "--ack-isolated-target"], "same-source-refused", success=False)
        restore_env = dict(self.environment, PGDATABASE="rag_restored")
        self.command(backup_tool + ["restore", str(bundle), "--ack-isolated-target"], "restore", restore_env)
        assert before == self.fingerprint("rag_restored"), "restored rows do not exactly match source"
        self.command(backup_tool + ["restore", str(bundle), "--ack-isolated-target"], "nonempty-refused", restore_env, False)
        assert before == self.fingerprint("rag_restored"), "negative restore changed target"
        self.sql("CREATE ROLE restore_fixture LOGIN PASSWORD 'postgres'")
        self.sql("CREATE DATABASE rag_rejected OWNER restore_fixture")
        self.command(backup_tool + ["restore", str(bundle), "--ack-isolated-target"], "transaction-rollback",
                     dict(self.environment, PGDATABASE="rag_rejected", PGUSER="restore_fixture"), False)
        assert self.sql("SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public'",
                        "rag_rejected") == "0", "failed restore left partial tables"
        self.record("restore SQL failure rolls back target transaction")
        corrupt_bundle = self.directory / "corrupt-backup"
        shutil.copytree(bundle, corrupt_bundle)
        archive = corrupt_bundle / "database.dump"
        with archive.open("ab") as output:
            output.write(b"corruption")
        self.command(backup_tool + ["verify", str(corrupt_bundle)], "corrupt-refused", success=False)
        self.start("rag_restored")
        assert json.loads(self.search("restored", document_id)[2])
        assert self.request(f"/api/v1/rag/documents/{deleted_id}")[0] == 404
        assert self.request(f"/api/v1/rag/documents/{scoped_document_id}", key=scoped_key)[0] == 200
        assert self.request(f"/api/v1/rag/documents/{document_id}", key=scoped_key)[0] in (403, 404)
        assert self.request("/api/v1/rag/documents", {"title": "forbidden", "content": "not permitted"}, key=scoped_key)[0] == 403
        for native in (True, False):
            for stream in (False, True):
                status, _, body = self.chat(stream, native)
                assert status == 200 and "RECOVERY_CODE_ALPHA" in body
        assert self.sql("SELECT encode(content_bin,'hex') FROM fs_files WHERE path='recovery/original.bin'", "rag_restored") == "001122ff"
        self.record("real dump/restore all-table equality, binary/vector/history/deletion and JSON/SSE", tables=len(before))
        self.stop()
        self.start("rag_restored")
        assert self.chat()[0] == 200
        self.record("graceful shutdown/restart HTTP readiness and success")

    def close(self):
        for process in reversed(self.processes):
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=40)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=10)
        subprocess.run(["docker", "rm", "-f", self.container], capture_output=True)


def main():
    os.umask(0o077)
    acceptance = Acceptance()
    print("Evidence: " + str(acceptance.directory), flush=True)
    try:
        acceptance.setup()
        acceptance.exercise()
        print("All controlled dependency/recovery acceptance steps passed", flush=True)
    finally:
        acceptance.close()


if __name__ == "__main__":
    main()
