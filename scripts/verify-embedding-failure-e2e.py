#!/usr/bin/env python3
"""embedding 依赖故障验收：上传 / 导入 / 检索三条路径的真实行为。

和 `verify-upstream-recovery-e2e.py`（chat 故障语义）互补。这里复用
`verify-dependency-recovery-e2e.py` 的临时 PostgreSQL + controlled provider，
只注入 embedding 端点故障，验证：

- 上传（`embeddingPolicy=SYNC`）与导入（显式 `/embed`）在 provider 故障时返回
  可读的失败状态，而不是 500 堆栈；强制重嵌入失败不会删掉旧向量；
- 检索在 embedding 故障时降级为全文而不是整体失败；
- 每次故障的 provider 调用次数有界（不再 batch 失败后逐条放大）；
- 失败原因、provider 与 trace 能通过指标和日志定位；
- 数据库不留半写向量 / 悬挂任务 / 已占用配额。
"""

from __future__ import annotations

import importlib.util
import json
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASE_SCRIPT = ROOT / "scripts" / "verify-dependency-recovery-e2e.py"

spec = importlib.util.spec_from_file_location("dependency_recovery_e2e", BASE_SCRIPT)
base = importlib.util.module_from_spec(spec)
spec.loader.exec_module(base)

# 一次失败应最多触发 embedding 重试上限，而不是 1（批量）+ N（逐条）× 重试。
EMBEDDING_RETRY_MAX_ATTEMPTS = 3
# job 层默认总尝试次数（`rag.embedding-jobs.default-max-attempts`）。
EMBEDDING_JOB_MAX_ATTEMPTS = 3

# (名称, provider 控制参数, 期望 failureDetails.reason, 期望 failureClass, 是否可重试)
FAULTS = [
    ("status-500", {"status": 500}, "UPSTREAM_UNAVAILABLE", "DEPENDENCY_UNAVAILABLE", True),
    ("status-429", {"status": 429, "retryAfter": 1}, "UPSTREAM_RATE_LIMITED", "RATE_LIMITED", True),
    ("disconnect", {"disconnect": True}, "UPSTREAM_CONNECTION", "DEPENDENCY_UNAVAILABLE", True),
    # 延迟响应体（不是延迟响应头）：让客户端命中 read timeout 并保留 timeout 语义。
    ("timeout", {"bodyDelayMs": 9000}, "UPSTREAM_TIMEOUT", "TIMEOUT", True),
    # 畸形响应不是"可重试的上游不可用"，但对外仍属于上游依赖失败：
    # 客户端拿 DEPENDENCY_UNAVAILABLE 并且 retryable=false。
    ("bad-json", {"badJson": True}, "UPSTREAM_RESPONSE_INVALID", "DEPENDENCY_UNAVAILABLE", False),
]

# embedding 批大小默认 10；一次故障最多 ceil(chunks/10) * 重试上限 次 provider 调用。
EMBEDDING_BATCH_SIZE = 10


class Acceptance(base.Acceptance):
    def __init__(self):
        super().__init__()
        # 让每个故障独立可观测：关闭熔断，固定重试预算。
        self.environment.update({
            "RAG_EMBEDDING_RETRY_MAX_ATTEMPTS": str(EMBEDDING_RETRY_MAX_ATTEMPTS),
            "RAG_EMBEDDING_CIRCUIT_BREAKER_ENABLED": "false",
            # 5s < 9s body 延迟 → 确定命中 read timeout，验证 TIMEOUT 分类；
            # 生产默认 30s，不该由测试隐式决定。
            "RAG_EMBEDDING_REQUEST_TIMEOUT_MS": "5000",
        })

    # ---------- helpers ----------

    def create_document(self, token: str, policy: str = "SYNC"):
        # 控制在 ~120 段、约 7 个 chunk，正好一个 batch：故障路径的调用上限才是
        # ceil(chunks/batchSize) * attempts，而不是随正文长度线性放大。
        content = (("The verification token is " + token + ". ") * 120).strip()
        status, headers, body = self.request("/api/v1/rag/documents", {
            "title": "embedding-failure-" + token,
            "content": content,
            "embeddingPolicy": policy,
        })
        return status, headers, json.loads(body) if body else None

    def embed_document(self, document_id: int, force: bool = True):
        status, headers, body = self.request(
            f"/api/v1/rag/documents/{document_id}/embed?force={str(force).lower()}", {})
        return status, headers, json.loads(body) if body else None

    def document_lifecycle(self, document_id: int):
        return self.json(f"/api/v1/rag/documents/{document_id}")["lifecycle"]

    def wait_embedding(self, document_id: int, expected: set[str], timeout: int = 90):
        observed = {}

        def settled():
            lifecycle = self.document_lifecycle(document_id)
            observed.clear()
            observed.update(lifecycle)
            processing = lifecycle.get("processing") or {}
            # 只等 embeddingStatus 会读到"失败但 processing 仍 RUNNING"的中间态，
            # lastErrorCode/lastError 还没写定，断言会随机失败。
            run_settled = processing.get("status") not in (
                "RUNNING", "QUEUED", "PENDING")
            return lifecycle["embeddingStatus"] in expected and run_settled

        self.wait(settled, timeout)
        return observed

    def vectors(self, document_id: int) -> int:
        return int(self.sql(
            f"SELECT count(*) FROM rag_embeddings WHERE document_id={document_id}"))

    def chunk_count(self, document_id: int) -> int:
        raw = self.sql(
            "SELECT chunk_count FROM rag_document_local_index_state "
            f"WHERE document_id={document_id}")
        return int(raw) if raw else 0

    def max_embedding_calls(self, document_id: int) -> int:
        # 两层重试各自有界：transport 重试（RetryTemplate）× job 重试（maxAttempts）。
        chunks = self.chunk_count(document_id)
        batches = max(1, -(-chunks // EMBEDDING_BATCH_SIZE))
        return batches * EMBEDDING_RETRY_MAX_ATTEMPTS * EMBEDDING_JOB_MAX_ATTEMPTS

    def embedding_calls(self) -> int:
        return int(self.state()["embeddings"]["calls"])

    def search_token(self, token: str, document_id: int):
        status, headers, body = self.request("/api/v1/rag/search", {
            "query": token,
            "documentIds": [document_id],
            "config": {"maxResults": 3, "minScore": 0, "useHybridSearch": True},
        })
        return status, headers, json.loads(body) if body else None

    def reset_faults(self):
        self.fault("embeddings", status=200, disconnect=False, badJson=False,
                   delayMs=0, bodyDelayMs=0, retryAfter=0)

    def assert_failure_envelope(self, body, reason: str, failure_class: str, retryable: bool):
        assert body.get("status") == "FAILED", body
        details = body.get("failureDetails") or {}
        assert details.get("reason") == reason, body
        assert details.get("origin") == "UPSTREAM", body
        assert details.get("retryable") is retryable, body
        assert body.get("retryable") is retryable, body
        assert body.get("failureClass") == failure_class, body

    # ---------- scenario ----------

    def exercise(self):
        baseline_token = "EMBED_BASELINE_" + uuid.uuid4().hex[:8]
        status, _, body = self.create_document(baseline_token, "SYNC")
        assert status == 200, body
        baseline_id = body["id"]
        lifecycle = self.wait_embedding(baseline_id, {"READY"})
        baseline_vectors = self.vectors(baseline_id)
        assert baseline_vectors > 0, "baseline document produced no vectors"

        status, _, body = self.search_token(baseline_token, baseline_id)
        assert status == 200 and len(body) >= 1, body

        # 1) 上传：SYNC 建文档时 embedding 故障，不能返回 500，也不能留半写向量。
        for name, fault, reason, failure_class, retryable in FAULTS:
            token = "EMBED_" + name.replace("-", "_") + "_" + uuid.uuid4().hex[:6]
            self.reset_faults()
            self.fault("embeddings", **fault)
            calls_before = self.embedding_calls()
            status, _, body = self.create_document(token, "SYNC")
            assert status == 200, (name, status, body)
            document_id = body["id"]
            lifecycle = self.wait_embedding(document_id, {"FAILED"}, timeout=180)
            calls = self.embedding_calls() - calls_before
            assert lifecycle["embeddingStatus"] == "FAILED", (name, lifecycle)
            assert lifecycle["lastErrorCode"] == "VECTOR_FAILED", (name, lifecycle)
            assert lifecycle.get("lastError"), (name, lifecycle)
            assert self.vectors(document_id) == 0, (name, "partial vectors written")
            assert calls <= self.max_embedding_calls(document_id), (
                name, "provider calls not bounded", calls, self.chunk_count(document_id))
            jobs = self.sql(
                "SELECT status FROM rag_embedding_jobs WHERE document_id="
                f"{document_id} ORDER BY created_at DESC LIMIT 1")
            assert jobs in ("FAILED", "QUEUED"), (name, "unexpected job state", jobs)
            assert self.sql("SELECT count(*) FROM rag_llm_budget_reservation "
                            "WHERE state='RESERVED'") == "0"
            self.record(f"upload under {name}: 200/FAILED, vectors=0, provider calls={calls}")

            # 2) 导入/强制重嵌入：失败时旧向量必须原样保留。
            before = self.vectors(baseline_id)
            status, _, body = self.embed_document(baseline_id, force=True)
            assert status == 200, (name, status, body)
            self.assert_failure_envelope(body, reason, failure_class, retryable)
            after = self.vectors(baseline_id)
            assert after == before, (name, "failed re-embed replaced existing vectors", before, after)

            # 3) 检索：embedding 故障时降级为全文，HTTP 仍是 200。
            status, _, body = self.search_token(baseline_token, baseline_id)
            assert status == 200, (name, status, body)
            assert len(body) >= 1, (name, "full-text degradation returned no results", body)
            self.record(f"import+search under {name}: re-embed FAILED without replacing vectors, "
                        f"search degraded to full-text")

        self.reset_faults()
        assert self.embed_document(baseline_id, force=True)[0] == 200

        # 4) 可观测性：指标按 operation/provider/reason 打标，日志能定位调用点。
        status, _, body = self.request("/actuator/metrics/rag.upstream.failures")
        assert status == 200, body
        metric = json.loads(body)
        tags = {tag["tag"]: set(tag["values"]) for tag in metric.get("availableTags", [])}
        operations = tags.get("operation", set())
        assert "embedding.document" in operations, metric
        assert "retrieval.embedding" in operations, metric
        providers = tags.get("provider", set())
        assert providers and all(value.strip() for value in providers), metric
        reasons = tags.get("reason", set())
        assert "UPSTREAM_RATE_LIMITED" in reasons, metric

        server_log = (self.directory / "rag_source-server.log").read_text()
        assert "Upstream failure operation=embedding.document provider=" in server_log
        assert "Retrieval embedding failed operation=retrieval.embedding provider=" in server_log
        assert "reason=UPSTREAM_RATE_LIMITED" in server_log
        self.record("embedding failures expose provider/reason metrics and structured logs")

        # 5) 恢复后上传与检索回到正常。
        recovery_token = "EMBED_RECOVERED_" + uuid.uuid4().hex[:8]
        status, _, body = self.create_document(recovery_token, "SYNC")
        assert status == 200, body
        recovery_id = body["id"]
        lifecycle = self.wait_embedding(recovery_id, {"READY"})
        assert lifecycle["embeddingStatus"] == "READY", lifecycle
        assert self.vectors(recovery_id) > 0
        status, _, body = self.search_token(recovery_token, recovery_id)
        assert status == 200 and len(body) >= 1, body
        self.record("embedding recovery restores SYNC embedding and vector search")


if __name__ == "__main__":
    acceptance = Acceptance()
    try:
        acceptance.setup()
        acceptance.exercise()
        print("All embedding failure acceptance steps passed", flush=True)
    finally:
        acceptance.close()
