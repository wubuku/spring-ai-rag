#!/usr/bin/env python3
"""真实进程验收：models.json 多 provider、chat fallback、预算与用量归账。

覆盖用户可见的失败构造：
- 主模型上游 500 / 超时（可重试）→ 切 fallback；
- 主模型上游 401 / 404（不可重试）→ 不静默切换，fail closed；
- candidate-attempt 预算耗尽 → fallback 不会被调用；
- 每次调用的 usage/cost 落到正确的 model_ref 与 purpose；
- 日志能区分“主失败”和“fallback 成功”。

依赖：Docker（pgvector/pgvector:pg16）、Maven、本地 Ollama 之外的网络均不需要。
只使用 loopback 故障夹具，不代表真实模型质量。
"""

from __future__ import annotations

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
ROOT_KEY = "model-fallback-fixture-root-key-20261104"
PRIMARY_MODEL = "primary-chat"
FALLBACK_MODEL = "fallback-chat"


def port() -> int:
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        return listener.getsockname()[1]


class Acceptance:
    def __init__(self):
        stamp = time.strftime("%Y%m%d-%H%M%S")
        self.directory = ROOT / ".verification" / "model-fallback" / (
            stamp + "-" + uuid.uuid4().hex[:8])
        self.directory.mkdir(parents=True, mode=0o700)
        self.container = "rag-model-fallback-" + uuid.uuid4().hex[:12]
        self.http_port = port()
        self.provider_port = port()
        self.db_port = port()
        self.base = f"http://127.0.0.1:{self.http_port}"
        self.provider = f"http://127.0.0.1:{self.provider_port}"
        self.processes = []
        self.service = None
        self.results = []
        self.environment = dict(
            os.environ,
            PGHOST="127.0.0.1",
            PGPORT=str(self.db_port),
            PGUSER="postgres",
            PGPASSWORD="postgres",
            PGDATABASE="rag_fallback",
        )

    # ---------- 基础设施 ----------

    def command(self, command, label, environment=None, success=True):
        with (self.directory / (label + ".log")).open("ab") as log:
            result = subprocess.run(
                command, cwd=ROOT, env=environment or self.environment,
                stdout=log, stderr=log)
        assert (result.returncode == 0) == success, (
            label + " failed; inspect " + str(self.directory / (label + ".log")))

    def sql(self, statement):
        result = subprocess.run(
            ["docker", "exec", self.container, "psql", "-h", "127.0.0.1",
             "-XqAt", "-U", "postgres", "-d", "rag_fallback",
             "-v", "ON_ERROR_STOP=1", "-c", statement],
            capture_output=True, check=True)
        return result.stdout.decode().strip()

    def provider_request(self, path, body=None, method=None):
        data = json.dumps(body).encode() if body is not None else None
        request = urllib.request.Request(
            self.provider + path, data=data, method=method,
            headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(request, timeout=10) as response:
            return response.read().decode()

    def fault(self, **values):
        self.provider_request("/control/chat", values)

    def reset_faults(self):
        self.fault(modelFaults={}, status=200, delayMs=0,
                   bodyDelayMs=0, disconnect=False)

    def provider_state(self):
        return json.loads(self.provider_request("/control/state"))

    def chat(self, session_id):
        body = {"message": "fixture plain", "mode": "PLAIN",
                "sessionId": session_id}
        request = urllib.request.Request(
            self.base + "/api/v1/rag/chat/ask",
            data=json.dumps(body).encode(),
            headers={"Content-Type": "application/json",
                     "X-API-Key": ROOT_KEY})
        try:
            response = urllib.request.urlopen(request, timeout=45)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            return response.status, dict(response.headers), response.read().decode()

    def stream(self, session_id):
        body = {"message": "fixture plain", "mode": "PLAIN",
                "sessionId": session_id}
        request = urllib.request.Request(
            self.base + "/api/v1/rag/chat/stream",
            data=json.dumps(body).encode(),
            headers={"Content-Type": "application/json",
                     "Accept": "text/event-stream",
                     "X-API-Key": ROOT_KEY})
        try:
            response = urllib.request.urlopen(request, timeout=45)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            return response.status, response.read().decode()

    def ready(self):
        assert self.service.poll() is None, (
            "server exited; inspect " + str(self.directory / "server.log"))
        try:
            with urllib.request.urlopen(self.base + "/actuator/health", timeout=5) as health:
                return health.status == 200
        except OSError:
            return False

    def wait(self, predicate, timeout=120, label="deadline"):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if predicate():
                return
            time.sleep(0.2)
        raise AssertionError(label + " exceeded")

    def record(self, name, **details):
        self.results.append(dict(name=name, status="PASS", **details))
        print("PASS: " + name, flush=True)
        (self.directory / "summary.json").write_text(
            json.dumps(self.results, indent=2) + "\n")

    # ---------- 启动 ----------

    def setup(self):
        self.command(["mvn", "-q", "-pl", "spring-ai-rag-core", "-am",
                      "-DskipTests", "install"], "install")
        self.command(["mvn", "-q", "-pl", "spring-ai-rag-core", "-am",
                      "dependency:build-classpath",
                      "-Dmdep.outputFile=" + str(self.directory / "dependencies.txt"),
                      "-DincludeScope=runtime"], "classpath")
        classpaths = []
        for module in ("api", "documents", "core"):
            target = self.directory / "runtime" / module
            shutil.copytree(ROOT / ("spring-ai-rag-" + module) / "target/classes", target)
            classpaths.append(str(target))
        self.classpath = ":".join(classpaths) + ":" + (
            self.directory / "dependencies.txt").read_text().strip()

        self.command(["docker", "run", "--rm", "-d", "--name", self.container,
                      "-e", "POSTGRES_PASSWORD=postgres",
                      "-e", "POSTGRES_DB=rag_fallback",
                      "-p", f"127.0.0.1:{self.db_port}:5432",
                      os.environ.get("TESTCONTAINERS_PG_IMAGE",
                                     "pgvector/pgvector:pg16")], "docker")
        self.wait(lambda: subprocess.run(
            ["docker", "exec", self.container, "pg_isready", "-h", "127.0.0.1",
             "-U", "postgres", "-d", "rag_fallback"],
            capture_output=True).returncode == 0, 60, "postgres")

        provider = subprocess.Popen(
            [sys.executable, "scripts/test-support/dependency-resilience-provider.py",
             "--port", str(self.provider_port)],
            cwd=ROOT,
            stdout=(self.directory / "provider.log").open("ab"),
            stderr=subprocess.STDOUT)
        self.processes.append(provider)
        self.wait(lambda: self.provider_up(), 20, "provider")

    def provider_up(self):
        try:
            self.provider_state()
            return True
        except OSError:
            return False

    def models_json(self):
        path = self.directory / "models.json"
        document = {
            "models": {
                "providers": {
                    "primary": {
                        "displayName": "Primary fixture",
                        "baseUrl": self.provider,
                        "apiKey": "primary-wrong-key",
                        "apiType": "openai",
                        "enabled": True,
                        "priority": 1,
                        "models": [{
                            "id": PRIMARY_MODEL, "type": "chat",
                            "cost": {"input": 1, "output": 2},
                            "contextWindow": 32000, "maxTokens": 1024,
                            "capabilities": {"streaming": True, "toolCalling": False},
                        }],
                    },
                    "fallback": {
                        "displayName": "Fallback fixture",
                        "baseUrl": self.provider,
                        "apiKey": "fallback-good-key",
                        "apiType": "openai",
                        "enabled": True,
                        "priority": 2,
                        "models": [{
                            "id": FALLBACK_MODEL, "type": "chat",
                            "cost": {"input": 3, "output": 5},
                            "contextWindow": 32000, "maxTokens": 1024,
                            "capabilities": {"streaming": True, "toolCalling": False},
                        }],
                    },
                    "embed": {
                        "displayName": "Embedding fixture",
                        "baseUrl": self.provider,
                        "apiKey": "fallback-good-key",
                        "apiType": "openai",
                        "enabled": True,
                        "priority": 3,
                        "models": [{
                            "id": "fixture-embedding", "type": "embedding",
                            "dimension": 1024, "inputModalities": ["text"],
                        }],
                    },
                },
                "chatModel": {
                    "primary": "primary/" + PRIMARY_MODEL,
                    "fallbacks": ["fallback/" + FALLBACK_MODEL],
                },
                "embeddingModel": {"primary": "embed/fixture-embedding"},
            }
        }
        path.write_text(json.dumps(document, indent=2) + "\n")
        return path

    def start(self, candidate_attempts=3, extra_args=None):
        if self.service is not None:
            self.stop()
        environment = dict(
            self.environment,
            SERVER_PORT=str(self.http_port),
            SPRING_PROFILES_ACTIVE="postgresql",
            SPRING_DATASOURCE_URL=(
                f"jdbc:postgresql://127.0.0.1:{self.db_port}/rag_fallback"),
            SPRING_DATASOURCE_USERNAME="postgres",
            SPRING_DATASOURCE_PASSWORD="postgres",
            SPRING_AI_OPENAI_API_KEY="fixture-key",
            SPRING_AI_OPENAI_BASE_URL=self.provider,
            SPRING_AI_OPENAI_CHAT_OPTIONS_MODEL=FALLBACK_MODEL,
            LLM_PROVIDER="openai",
            APP_LLM_PROVIDER="openai",
            RAG_EMBEDDING_API_KEY="fixture-key",
            RAG_EMBEDDING_BASE_URL=self.provider,
            RAG_EMBEDDING_MODEL="fixture-embedding",
            RAG_EMBEDDING_DIMENSIONS="1024",
            RAG_EMBEDDING_PROFILE_KEY="fallback-e2e-1024-v1",
            RAG_EMBEDDING_PROVIDER="siliconflow",
            RAG_RETRIEVAL_MIN_SCORE="0",
            RAG_SECURITY_ENABLED="true",
            RAG_SECURITY_API_KEY=ROOT_KEY,
            RAG_ROOT_API_KEY=ROOT_KEY,
            RAG_RATE_LIMIT_REQUESTS_PER_MINUTE="10000",
            RAG_ALLOW_INSECURE_UPSTREAM="true",
            RAG_LLM_BUDGET_ENABLED="true",
            MODELS_CONFIG_FILE=str(self.models_json()),
            RAG_OPENAI_COMPATIBILITY_ENABLED="false",
        )
        self.service = subprocess.Popen(
            ["java", "-cp", self.classpath,
             "com.springairag.core.SpringAiRagApplication",
             "--rag.retry.max-attempts=1",
             "--rag.admission.dependency-timeout-ms=1500",
             "--rag.timeout.connect-timeout-ms=500",
             "--rag.timeout.read-timeout-ms=1500",
             "--rag.chat.execution.max-candidate-attempts=" + str(candidate_attempts),
             "--rag.embedding-jobs.poll-interval-ms=10000"]
            + list(extra_args or []),
            cwd=ROOT, env=environment,
            stdout=(self.directory / "server.log").open("ab"),
            stderr=subprocess.STDOUT)
        self.processes.append(self.service)
        self.wait(self.ready, 120, "server ready")

    def stop(self):
        if self.service is None:
            return
        self.service.send_signal(signal.SIGTERM)
        self.service.wait(timeout=40)
        self.service = None

    def cleanup(self):
        self.stop()
        for process in reversed(self.processes):
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill()
        subprocess.run(["docker", "rm", "-f", self.container],
                       capture_output=True)

    # ---------- 断言辅助 ----------

    def log_offset(self):
        path = self.directory / "server.log"
        return path.stat().st_size if path.exists() else 0

    def log_since(self, offset):
        path = self.directory / "server.log"
        return path.read_text(errors="replace")[offset:]

    def usage_rows(self, session_id):
        output = self.sql(
            "SELECT model_ref, purpose, outcome, prompt_tokens, completion_tokens, "
            "total_tokens, input_cost_per_million, output_cost_per_million, "
            "configured_cost, cost_available "
            "FROM rag_llm_usage_event WHERE session_id='" + session_id + "' "
            "ORDER BY call_ordinal")
        rows = []
        for line in output.splitlines():
            if not line:
                continue
            parts = line.split("|")
            rows.append(dict(
                model_ref=parts[0], purpose=parts[1], outcome=parts[2],
                prompt_tokens=int(parts[3]), completion_tokens=int(parts[4]),
                total_tokens=int(parts[5]), input_price=parts[6],
                output_price=parts[7], configured_cost=parts[8],
                cost_available=parts[9]))
        return rows

    def reservation_count(self):
        return int(self.sql("SELECT count(*) FROM rag_llm_budget_reservation"))

    def model_calls(self):
        return self.provider_state()["chat"].get("modelCalls", {})

    def calls_for(self, before, after, model):
        return after.get(model, 0) - before.get(model, 0)

    def run_scenario(self, name, faults, expected_success, expected_primary_calls,
                     expected_fallback_calls, expected_log_fragment=None,
                     forbidden_log_fragment=None, session_id=None):
        session_id = session_id or ("fb-" + name[:12] + "-" + uuid.uuid4().hex[:10])
        self.reset_faults()
        if faults:
            self.fault(**faults)
        before_calls = self.model_calls()
        before_log = self.log_offset()
        status, headers, body = self.chat(session_id)
        after_calls = self.model_calls()
        log_slice = self.log_since(before_log)
        usage = self.usage_rows(session_id)
        details = dict(session=session_id, http_status=status, body=body,
                       primary_calls=self.calls_for(before_calls, after_calls, PRIMARY_MODEL),
                       fallback_calls=self.calls_for(before_calls, after_calls, FALLBACK_MODEL),
                       usage=usage)
        assert self.calls_for(before_calls, after_calls, PRIMARY_MODEL) == expected_primary_calls, details
        assert self.calls_for(before_calls, after_calls, FALLBACK_MODEL) == expected_fallback_calls, details
        if expected_success:
            assert status == 200, (status, body)
            assert usage and usage[-1]["outcome"] == "SUCCEEDED", details
        else:
            assert status >= 400, (status, body)
        if expected_log_fragment is not None:
            assert expected_log_fragment in log_slice, (
                "missing log fragment: " + expected_log_fragment + "\n" + log_slice)
        if forbidden_log_fragment is not None:
            assert forbidden_log_fragment not in log_slice, (
                "unexpected log fragment: " + forbidden_log_fragment + "\n" + log_slice)
        self.record(name, **details)
        return status, headers, body, usage, log_slice


def main():
    acceptance = Acceptance()
    try:
        acceptance.setup()
        acceptance.start(candidate_attempts=3)

        acceptance.run_scenario(
            "healthy-primary",
            faults=None,
            expected_success=True,
            expected_primary_calls=1,
            expected_fallback_calls=0,
        )
        healthy_usage = acceptance.usage_rows(
            acceptance.results[-1]["session"])
        assert len(healthy_usage) == 1, healthy_usage
        row = healthy_usage[0]
        assert row["model_ref"] == "primary/" + PRIMARY_MODEL, row
        assert row["purpose"] == "CHAT", row
        expected = (row["prompt_tokens"] * 1 + row["completion_tokens"] * 2) / 1_000_000
        assert abs(float(row["configured_cost"]) - expected) < 1e-9, row
        acceptance.record("healthy-primary-usage", model_ref=row["model_ref"],
                          purpose=row["purpose"], cost=row["configured_cost"])

        acceptance.run_scenario(
            "primary-500",
            faults={"modelFaults": {PRIMARY_MODEL: {"status": 500}}},
            expected_success=True,
            expected_primary_calls=1,
            expected_fallback_calls=1,
            expected_log_fragment=(
                "Chat candidate 1/3 failed (primary/" + PRIMARY_MODEL
                + ") reason=UPSTREAM_UNAVAILABLE retryable=true"),
        )
        failed_usage = acceptance.usage_rows(acceptance.results[-1]["session"])
        assert len(failed_usage) == 2, failed_usage
        assert failed_usage[0]["model_ref"] == "primary/" + PRIMARY_MODEL, failed_usage
        assert failed_usage[0]["outcome"] == "FAILED", failed_usage
        assert failed_usage[0]["purpose"] == "CHAT", failed_usage
        assert failed_usage[1]["model_ref"] == "fallback/" + FALLBACK_MODEL, failed_usage
        assert failed_usage[1]["outcome"] == "SUCCEEDED", failed_usage
        expected = (failed_usage[1]["prompt_tokens"] * 3
                    + failed_usage[1]["completion_tokens"] * 5) / 1_000_000
        assert abs(float(failed_usage[1]["configured_cost"]) - expected) < 1e-9, failed_usage
        acceptance.record(
            "primary-500-usage",
            failed_model=failed_usage[0]["model_ref"],
            failed_outcome=failed_usage[0]["outcome"],
            fallback_cost=failed_usage[1]["configured_cost"],
        )

        log_slice = acceptance.log_since(0)
        assert ("Chat fallback succeeded: requested=null, resolved=fallback/"
                + FALLBACK_MODEL) in log_slice, log_slice
        acceptance.record("fallback-log-distinction")

        # 401/404 属于不可重试的配置/凭据错误：不得静默切到 fallback。
        acceptance.run_scenario(
            "primary-401",
            faults={"modelFaults": {PRIMARY_MODEL: {"status": 401}}},
            expected_success=False,
            expected_primary_calls=1,
            expected_fallback_calls=0,
            expected_log_fragment="retryable=false",
            forbidden_log_fragment="Chat fallback succeeded",
        )
        acceptance.run_scenario(
            "primary-404",
            faults={"modelFaults": {PRIMARY_MODEL: {"status": 404}}},
            expected_success=False,
            expected_primary_calls=1,
            expected_fallback_calls=0,
            expected_log_fragment="Chat candidate 1/3 failed (primary/"
                                   + PRIMARY_MODEL + ") reason=UPSTREAM_REJECTED",
            forbidden_log_fragment="Chat fallback succeeded",
        )

        acceptance.run_scenario(
            "primary-timeout",
            faults={"modelFaults": {PRIMARY_MODEL: {"delayMs": 5000}}},
            expected_success=True,
            expected_primary_calls=1,
            expected_fallback_calls=1,
            expected_log_fragment="reason=UPSTREAM_TIMEOUT retryable=true",
        )

        # 流式路径与 JSON 共用候选链，也必须在上游 500 时切 fallback。
        session_id = "fb-stream-" + uuid.uuid4().hex[:10]
        acceptance.reset_faults()
        acceptance.fault(modelFaults={PRIMARY_MODEL: {"status": 500}})
        before_calls = acceptance.model_calls()
        status, body = acceptance.stream(session_id)
        after_calls = acceptance.model_calls()
        assert status == 200, (status, body)
        assert acceptance.calls_for(before_calls, after_calls, PRIMARY_MODEL) == 1
        assert acceptance.calls_for(before_calls, after_calls, FALLBACK_MODEL) == 1
        assert "fallback-chat" in body, body
        assert "data:" in body, body
        acceptance.record("primary-500-stream-fallback", http_status=status,
                          stream_bytes=len(body))

        # 预算边界：候选尝试数=1 时，主模型失败后 fallback 根本不允许发起。
        acceptance.start(candidate_attempts=1)
        acceptance.reset_faults()
        acceptance.fault(modelFaults={PRIMARY_MODEL: {"status": 500}})
        before_reservations = acceptance.reservation_count()
        before_calls = acceptance.model_calls()
        before_log = acceptance.log_offset()
        session_id = "fb-budget-" + uuid.uuid4().hex[:10]
        status, headers, body = acceptance.chat(session_id)
        after_calls = acceptance.model_calls()
        after_reservations = acceptance.reservation_count()
        log_slice = acceptance.log_since(before_log)
        assert status == 422, (status, body)
        error = json.loads(body)
        code = error.get("code") or error.get("errorCode") or ""
        assert "CHAT_BUDGET_EXHAUSTED" in json.dumps(error), (code, body)
        assert acceptance.calls_for(before_calls, after_calls, PRIMARY_MODEL) == 1
        assert acceptance.calls_for(before_calls, after_calls, FALLBACK_MODEL) == 0
        assert after_reservations - before_reservations == 1, (
            after_reservations - before_reservations)
        assert "Chat fallback succeeded" not in log_slice
        acceptance.record("fallback-bounded-by-candidate-budget",
                          http_status=status, reservations=after_reservations - before_reservations,
                          error_code=code)

        # token 预算同样在候选调用之前生效：预算拒绝时主/备模型都不会被调用。
        acceptance.start(candidate_attempts=3,
                         extra_args=["--rag.llm-budget.max-request-tokens=1"])
        acceptance.reset_faults()
        before_calls = acceptance.model_calls()
        session_id = "fb-token-" + uuid.uuid4().hex[:10]
        status, headers, body = acceptance.chat(session_id)
        after_calls = acceptance.model_calls()
        assert status == 429, (status, body)
        assert acceptance.calls_for(before_calls, after_calls, PRIMARY_MODEL) == 0
        assert acceptance.calls_for(before_calls, after_calls, FALLBACK_MODEL) == 0
        acceptance.record("budget-denies-before-any-candidate",
                          http_status=status, body_code=json.loads(body).get("code"))
        (acceptance.directory / "summary.md").write_text(
            "# Multi-model fallback E2E\n\n"
            + "\n".join("- PASS: " + item["name"] for item in acceptance.results)
            + "\n")
        print("Multi-model fallback E2E passed: " + str(acceptance.directory), flush=True)
        return 0
    except BaseException as error:
        print("FAILED: " + str(error), file=sys.stderr, flush=True)
        return 1
    finally:
        acceptance.cleanup()


if __name__ == "__main__":
    raise SystemExit(main())
