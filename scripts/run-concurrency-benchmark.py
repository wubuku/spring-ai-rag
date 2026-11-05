#!/usr/bin/env python3
"""固定并发性能基准：真实 PostgreSQL + mock provider + 真实 HTTP 入口。

对五条关键路径在并发 1 / 8 / 32 下测吞吐与 p50/p95/p99：

  - 向量检索（GET /api/v1/rag/search?useHybrid=false）
  - 混合检索（GET /api/v1/rag/search?useHybrid=true）
  - 文档创建（POST /api/v1/rag/documents）
  - 上传解析（POST /api/v1/rag/documents/upload，multipart）
  - embedding 管线（POST /api/v1/rag/documents/{id}/embed）
  - 流式导出（GET /api/v1/rag/collections/by-key/export）

脚本默认自建一次性数据库（`LocalStack`），不会碰开发者日常库；结束后删除。
也可以用 `--skip-stack --dsn ... --base-url ... --api-key ...` 对已运行的服务采样。

结果写入 JSON；`scripts/verify-performance-baseline.py` 用同一份 JSON 做退化门禁。
"""

from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import math
import os
import platform
import random
import statistics
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPT_DIR))

try:
    import psycopg2
    import psycopg2.extras
except ImportError:  # pragma: no cover - 环境缺失时给出可操作提示
    psycopg2 = None

from lib.rag_local_stack import (  # noqa: E402
    ROOT_API_KEY,
    LocalStack,
    multipart_request,
)

FIXTURE_PREFIX = "perf-bench"
DEFAULT_CHUNKER_VERSION = "bounded-v3:1000:100:100:1000000:20000"
VECTOR_POOL_SIZE = 32
TEXT_WORDS = (
    "alpha bravo charlie delta echo foxtrot golf hotel india juliet kilo lima"
).split()

APP_PORT = 18091
MOCK_PORT = 18092


# ---------------------------------------------------------------- 统计工具


def percentiles(values):
    """返回 p50/p95/p99/max/mean；空输入返回 None。"""
    if not values:
        return {k: None for k in ("p50", "p95", "p99", "max", "mean")}
    ordered = sorted(values)

    def nearest_rank(p):
        rank = max(1, math.ceil(p * len(ordered)))
        return ordered[rank - 1]

    return {
        "p50": round(nearest_rank(0.50), 2),
        "p95": round(nearest_rank(0.95), 2),
        "p99": round(nearest_rank(0.99), 2),
        "max": round(ordered[-1], 2),
        "mean": round(statistics.fmean(ordered), 2),
    }


def summarize(latencies, status_counts, wall_seconds):
    total = sum(status_counts.values())
    ok = sum(count for status, count in status_counts.items() if 200 <= status < 300)
    errors = total - ok
    return {
        "requests": total,
        "ok": ok,
        "errors": errors,
        "error_rate": round(errors / total, 4) if total else None,
        "status_counts": {str(k): v for k, v in sorted(status_counts.items())},
        "throughput_rps": round(ok / wall_seconds, 2) if wall_seconds > 0 else None,
        "wall_ms": round(wall_seconds * 1000, 2),
        **percentiles(latencies),
    }


# ---------------------------------------------------------------- 语料


def content_hash(value):
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def build_vectors(dimensions):
    rng = random.Random(42)
    vectors = []
    for _ in range(VECTOR_POOL_SIZE):
        raw = [rng.uniform(-1.0, 1.0) for _ in range(dimensions)]
        norm = math.sqrt(sum(v * v for v in raw)) or 1.0
        vectors.append("[" + ",".join(f"{v / norm:.6f}" for v in raw) + "]")
    return vectors


def chunk_text(document_index, chunk_index):
    words = [
        TEXT_WORDS[(document_index + chunk_index * 3 + i) % len(TEXT_WORDS)]
        for i in range(24)
    ]
    return f"doc{document_index:05d} chunk{chunk_index:03d} " + " ".join(words)


def seed_corpus(dsn, collection_key, documents, chunks_per_doc):
    """直接写库生成确定性语料：固定文档数、chunk 数与向量，便于重复比较。"""
    if psycopg2 is None:
        raise RuntimeError("psycopg2 is required for seeding")

    connection = psycopg2.connect(dsn)
    connection.autocommit = False
    try:
        with connection.cursor() as cursor:
            cursor.execute(
                "SELECT id, dimensions FROM rag_embedding_profiles "
                "WHERE enabled = true ORDER BY id LIMIT 1")
            row = cursor.fetchone()
            if row is None:
                raise RuntimeError(
                    "no enabled embedding profile; start the app once to register it")
            profile_id, dimensions = row
            vectors = build_vectors(dimensions)
            chunker = DEFAULT_CHUNKER_VERSION

            cursor.execute(
                "SELECT id FROM rag_collection WHERE collection_key = %s",
                (collection_key,))
            old = cursor.fetchone()
            if old:
                old_id = old[0]
                for table in ("rag_embeddings", "rag_document_chunks",
                              "rag_document_local_index_state",
                              "rag_document_embedding_state"):
                    cursor.execute(
                        f"DELETE FROM {table} WHERE document_id IN "
                        "(SELECT id FROM rag_documents WHERE collection_id = %s)",
                        (old_id,))
                cursor.execute(
                    "DELETE FROM rag_documents WHERE collection_id = %s", (old_id,))
                cursor.execute(
                    "DELETE FROM rag_collection WHERE id = %s", (old_id,))

            cursor.execute(
                "INSERT INTO rag_collection "
                "(collection_key, name, embedding_model, dimensions, enabled, "
                " active_embedding_profile_id) "
                "VALUES (%s, %s, %s, %s, true, %s) RETURNING id",
                (collection_key, f"{FIXTURE_PREFIX} {documents}", "mock/perf",
                 dimensions, profile_id))
            collection_id = cursor.fetchone()[0]

            doc_rows = []
            for index in range(documents):
                digest = content_hash(f"{collection_key}:{index}")
                body = f"{FIXTURE_PREFIX} document {index} " + " ".join(
                    TEXT_WORDS[(index + i) % len(TEXT_WORDS)] for i in range(40))
                doc_rows.append((
                    collection_id, f"{FIXTURE_PREFIX} doc {index:05d}",
                    FIXTURE_PREFIX, body, digest, len(body), chunker))
            psycopg2.extras.execute_values(
                cursor,
                "INSERT INTO rag_documents "
                "(collection_id, title, source, content, content_hash, size, "
                " derivation_chunker_version) VALUES %s",
                doc_rows, page_size=500)

            cursor.execute(
                "SELECT id FROM rag_documents WHERE collection_id = %s ORDER BY id",
                (collection_id,))
            document_ids = [row[0] for row in cursor.fetchall()]

            chunk_rows = []
            vector_rows = []
            for doc_index, document_id in enumerate(document_ids):
                digest = content_hash(f"{collection_key}:{doc_index}")
                for chunk_index in range(chunks_per_doc):
                    text = chunk_text(doc_index, chunk_index)
                    chunk_rows.append((
                        document_id, 1, digest, chunker, text, chunk_index,
                        chunk_index * 200, chunk_index * 200 + len(text)))
                    vector_rows.append((
                        document_id, chunk_index, text, digest, chunker,
                        chunk_index * 200, chunk_index * 200 + len(text),
                        profile_id,
                        vectors[(doc_index * chunks_per_doc + chunk_index)
                                % VECTOR_POOL_SIZE]))
            psycopg2.extras.execute_values(
                cursor,
                "INSERT INTO rag_document_chunks "
                "(document_id, local_index_generation, content_hash, chunker_version, "
                " chunk_text, chunk_index, chunk_start_pos, chunk_end_pos) VALUES %s",
                chunk_rows, page_size=500)
            psycopg2.extras.execute_values(
                cursor,
                "INSERT INTO rag_embeddings "
                "(document_id, chunk_index, chunk_text, embedding_profile_id, "
                f" embedding_{dimensions}, chunk_start_pos, chunk_end_pos) "
                "VALUES %s",
                [(row[0], row[1], row[2], row[7], row[8], row[5], row[6])
                 for row in vector_rows],
                template="(%s,%s,%s,%s,%s::vector,%s,%s)",
                page_size=200)
            cursor.execute(
                "INSERT INTO rag_document_embedding_state "
                "(document_id, embedding_profile_id, content_hash, chunker_version, "
                " status, chunk_count, completed_at) "
                "SELECT d.id, %s, d.content_hash, d.derivation_chunker_version, "
                "       'COMPLETED', %s, now() "
                "FROM rag_documents d WHERE d.collection_id = %s "
                "ON CONFLICT (document_id, embedding_profile_id) DO UPDATE SET "
                "status = EXCLUDED.status, chunk_count = EXCLUDED.chunk_count, "
                "completed_at = EXCLUDED.completed_at",
                (profile_id, chunks_per_doc, collection_id))
            cursor.execute(
                "INSERT INTO rag_document_local_index_state "
                "(document_id, local_index_status, content_hash, chunker_version, "
                " local_index_generation, chunk_count) "
                "SELECT d.id, 'READY', d.content_hash, d.derivation_chunker_version, "
                "       1, %s "
                "FROM rag_documents d WHERE d.collection_id = %s "
                "ON CONFLICT (document_id) DO UPDATE SET "
                "local_index_status = EXCLUDED.local_index_status, "
                "content_hash = EXCLUDED.content_hash, "
                "chunker_version = EXCLUDED.chunker_version, "
                "local_index_generation = EXCLUDED.local_index_generation, "
                "chunk_count = EXCLUDED.chunk_count",
                (chunks_per_doc, collection_id))
        connection.commit()
        connection.autocommit = True
        with connection.cursor() as vacuum_cursor:
            vacuum_cursor.execute("VACUUM ANALYZE rag_documents")
            vacuum_cursor.execute("VACUUM ANALYZE rag_document_chunks")
            vacuum_cursor.execute("VACUUM ANALYZE rag_embeddings")
        return {
            "collection_id": collection_id,
            "profile_id": profile_id,
            "dimensions": dimensions,
            "documents": documents,
            "chunks": documents * chunks_per_doc,
        }
    finally:
        connection.close()


def cleanup_corpus(dsn, collection_key):
    """只删本脚本的 fixture Collection；找不到时静默返回。"""
    if psycopg2 is None:
        return 0
    connection = psycopg2.connect(dsn)
    connection.autocommit = True
    try:
        with connection.cursor() as cursor:
            cursor.execute(
                "SELECT id FROM rag_collection WHERE collection_key = %s",
                (collection_key,))
            row = cursor.fetchone()
            if row is None:
                return 0
            collection_id = row[0]
            for table in ("rag_embeddings", "rag_document_chunks",
                          "rag_document_local_index_state",
                          "rag_document_embedding_state"):
                cursor.execute(
                    f"DELETE FROM {table} WHERE document_id IN "
                    "(SELECT id FROM rag_documents WHERE collection_id = %s)",
                    (collection_id,))
            cursor.execute(
                "DELETE FROM rag_documents WHERE collection_id = %s", (collection_id,))
            cursor.execute("DELETE FROM rag_collection WHERE id = %s", (collection_id,))
            return 1
    finally:
        connection.close()


# ---------------------------------------------------------------- HTTP 工作负载


class ApiClient:
    def __init__(self, base_url, api_key, timeout):
        self.base_url = base_url.rstrip("/")
        self.api_key = api_key
        self.timeout = timeout

    def _url(self, path, params=None):
        url = self.base_url + path
        if params:
            url += "?" + urllib.parse.urlencode(params, doseq=True)
        return url

    def request(self, method, path, params=None, body=None, headers=None):
        url = self._url(path, params)
        data = json.dumps(body).encode("utf-8") if body is not None else None
        request = urllib.request.Request(url, data=data, method=method)
        request.add_header("X-API-Key", self.api_key)
        if data is not None:
            request.add_header("Content-Type", "application/json")
        for name, value in (headers or {}).items():
            request.add_header(name, value)
        started = time.perf_counter()
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                response.read()
                return response.status, (time.perf_counter() - started) * 1000, None
        except urllib.error.HTTPError as error:
            return error.code, (time.perf_counter() - started) * 1000, \
                error.read()[:200].decode("utf-8", "replace")
        except Exception as error:  # noqa: BLE001 - 记录所有客户端失败类别
            return 0, (time.perf_counter() - started) * 1000, repr(error)[:200]


def run_workload(name, concurrency, total, operation):
    """固定并发跑 total 个请求；返回吞吐/分位数/错误码分布。"""
    latencies = []
    status_counts = {}
    lock = threading.Lock()

    def task(index):
        status, latency, error = operation(index)
        with lock:
            status_counts[status] = status_counts.get(status, 0) + 1
            if 200 <= status < 300:
                latencies.append(latency)
            elif error:
                print(f"[WARN] {name} #{index} -> {status}: {error}", file=sys.stderr)

    started = time.perf_counter()
    with ThreadPoolExecutor(max_workers=concurrency) as executor:
        list(executor.map(task, range(total)))
    wall = time.perf_counter() - started
    return summarize(latencies, status_counts, wall)


def benchmark(args, client, collection_key):
    results = {}
    concurrency_levels = [int(value) for value in args.concurrency.split(",") if value]
    search_params = {
        "query": "alpha bravo",
        "limit": "10",
        "collectionKeys": collection_key,
    }

    def search(use_hybrid):
        def operation(_index):
            params = dict(search_params, useHybrid=str(use_hybrid).lower())
            status, latency, error = client.request("GET", "/api/v1/rag/search", params)
            return status, latency, error
        return operation

    def document_create(prefix):
        def operation(index):
            title = f"{prefix}-create-{index:06d}"
            status, latency, error = client.request(
                "POST", "/api/v1/rag/documents",
                body={"title": title,
                      "content": f"{title} benchmark body " * 8,
                      "source": FIXTURE_PREFIX,
                      "collectionKey": collection_key})
            return status, latency, error
        return operation

    # multipart_request 不返回耗时，因此包装一层客户端计时。
    def upload_timed(prefix):
        def operation(index):
            started = time.perf_counter()
            status, body = multipart_request(
                client.base_url + "/api/v1/rag/documents/upload",
                "files", f"{prefix}-upload-{index:06d}.txt",
                (f"{prefix} upload {index}\n" + ("spring ai benchmark text " * 12)).encode(),
                {"collectionKey": collection_key, "force": "true"},
                api_key=client.api_key, timeout=client.timeout)
            return status, (time.perf_counter() - started) * 1000, \
                None if 200 <= status < 300 else body[:200]
        return operation

    def embed(document_ids):
        def operation(index):
            document_id = document_ids[index % len(document_ids)]
            status, latency, error = client.request(
                "POST", f"/api/v1/rag/documents/{document_id}/embed")
            return status, latency, error
        return operation

    def export():
        def operation(_index):
            params = {"collectionKey": collection_key}
            url = client._url("/api/v1/rag/collections/by-key/export", params)
            request = urllib.request.Request(url, method="GET")
            request.add_header("X-API-Key", client.api_key)
            started = time.perf_counter()
            try:
                with urllib.request.urlopen(request, timeout=client.timeout) as response:
                    while response.read(65536):
                        pass
                    return response.status, (time.perf_counter() - started) * 1000, None
            except urllib.error.HTTPError as error:
                return error.code, (time.perf_counter() - started) * 1000, \
                    error.read()[:200].decode("utf-8", "replace")
            except Exception as error:  # noqa: BLE001
                return 0, (time.perf_counter() - started) * 1000, repr(error)[:200]
        return operation

    per_worker = args.requests_per_worker
    for concurrency in concurrency_levels:
        total = max(concurrency * per_worker, args.min_requests)
        print(f"[bench] {concurrency} 并发 / {total} 请求", flush=True)
        workload = {}
        workload["search_vector"] = run_workload(
            "search_vector", concurrency, total, search(False))
        workload["search_hybrid"] = run_workload(
            "search_hybrid", concurrency, total, search(True))
        workload["export_collection"] = run_workload(
            "export_collection", concurrency, max(concurrency, 4), export())
        workload["document_create"] = run_workload(
            "document_create", concurrency, total,
            document_create(f"{FIXTURE_PREFIX}-{int(time.time())}"))
        workload["upload_parse"] = run_workload(
            "upload_parse", concurrency, total,
            upload_timed(f"{FIXTURE_PREFIX}-{int(time.time())}"))
        # embedding 需要预先准备未嵌入的文档，按并发级别分批创建；
        # 直接从库里取最新未嵌入的 fixture 文档 ID，避免解析响应格式。
        for index in range(total):
            title = f"{FIXTURE_PREFIX}-embed-{int(time.time())}-{index:06d}"
            status, _, error = client.request(
                "POST", "/api/v1/rag/documents",
                body={"title": title,
                      "content": f"{title} embedding benchmark body " * 6,
                      "source": FIXTURE_PREFIX,
                      "collectionKey": collection_key})
            if not 200 <= status < 300:
                raise RuntimeError(f"准备 embedding fixture 失败: {status} {error}")
        documents = pending_embedding_documents(args, collection_key, total)
        if len(documents) < total:
            raise RuntimeError(
                f"embedding fixture 不足：期望 {total}，实际 {len(documents)}")
        workload["embedding_pipeline"] = run_workload(
            "embedding_pipeline", concurrency, total, embed(documents))
        results[str(concurrency)] = workload

    return results


def pending_embedding_documents(args, collection_key, limit):
    """取本 fixture 尚未完成 embedding 的文档 ID（最新创建的 limit 个）。"""
    if psycopg2 is None:
        raise RuntimeError("psycopg2 is required to prepare embedding fixtures")
    connection = psycopg2.connect(args.dsn)
    try:
        with connection.cursor() as cursor:
            cursor.execute(
                "SELECT d.id FROM rag_documents d "
                "JOIN rag_collection c ON c.id = d.collection_id "
                "LEFT JOIN rag_document_embedding_state s "
                "  ON s.document_id = d.id "
                " AND s.embedding_profile_id = c.active_embedding_profile_id "
                "WHERE c.collection_key = %s "
                "  AND (s.document_id IS NULL OR s.status <> 'COMPLETED') "
                "ORDER BY d.id DESC LIMIT %s",
                (collection_key, limit))
            return [row[0] for row in cursor.fetchall()]
    finally:
        connection.close()


# ---------------------------------------------------------------- 环境信息


def assert_port_free(port):
    """端口被占用时直接退出，避免 LocalStack 清端口时误伤他人进程。"""
    result = subprocess.run(
        ["lsof", "-ti", f":{port}", "-sTCP:LISTEN"],
        capture_output=True, text=True)
    if result.stdout.strip():
        raise SystemExit(
            f"端口 {port} 已被占用（pid={result.stdout.strip()}）；"
            "请先释放或改用其他端口，脚本不会杀掉非自身进程")


def git_commit():
    try:
        return subprocess.check_output(
            ["git", "rev-parse", "--short", "HEAD"], text=True,
            cwd=str(SCRIPT_DIR.parent)).strip()
    except Exception:  # noqa: BLE001
        return "unknown"


def java_version():
    try:
        output = subprocess.check_output(["java", "-version"], stderr=subprocess.STDOUT,
                                         text=True)
        return output.splitlines()[0]
    except Exception:  # noqa: BLE001
        return "unknown"


def database_version(dsn):
    if psycopg2 is None:
        return "unknown"
    try:
        connection = psycopg2.connect(dsn)
        try:
            with connection.cursor() as cursor:
                cursor.execute("SELECT version()")
                return cursor.fetchone()[0].split(" on ")[0]
        finally:
            connection.close()
    except Exception:  # noqa: BLE001
        return "unknown"


# ---------------------------------------------------------------- 入口


def self_test():
    stats = percentiles([1, 2, 3, 4, 5, 6, 7, 8, 9, 10])
    assert stats["p50"] == 5 and stats["p95"] == 10 and stats["p99"] == 10, stats
    summary = summarize([10.0, 20.0], {200: 1, 503: 1}, 2.0)
    assert summary["requests"] == 2 and summary["ok"] == 1
    assert summary["error_rate"] == 0.5 and summary["throughput_rps"] == 0.5, summary
    assert percentiles([])["p50"] is None
    print("concurrency benchmark self-test ok")


def parse_args():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--profile", choices=("quick", "standard"), default="standard")
    parser.add_argument("--concurrency", default="1,8,32")
    parser.add_argument("--requests-per-worker", type=int, default=6)
    parser.add_argument("--min-requests", type=int, default=20)
    parser.add_argument("--fixture-docs", type=int, default=400)
    parser.add_argument("--chunks-per-doc", type=int, default=5)
    parser.add_argument("--pool-size", type=int, default=20)
    parser.add_argument("--timeout", type=float, default=120.0)
    parser.add_argument("--output")
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--skip-stack", action="store_true",
                        help="不复用 LocalStack，直接连 --base-url/--dsn")
    parser.add_argument("--dsn", default="")
    parser.add_argument("--base-url", default="")
    parser.add_argument("--api-key", default="")
    parser.add_argument("--keep-fixtures", action="store_true")
    parser.add_argument("--keep-stack", action="store_true",
                        help="保留临时数据库与日志目录（排查用）")
    parser.add_argument("--app-arg", action="append", default=[],
                        help="追加传给应用的 Spring Boot 参数（可重复）")
    parser.add_argument("--hikari-leak-detection-ms", type=int, default=10000,
                        help="打开 Hikari 泄漏检测（0 关闭）；默认 10s 便于暴露连接占用")
    parser.add_argument("--skip-seed", action="store_true")
    return parser.parse_args()


def run(args):
    if args.self_test:
        self_test()
        return 0

    if args.profile == "quick":
        args.requests_per_worker = 2
        args.min_requests = 12
        args.fixture_docs = 200
        args.chunks_per_doc = 4

    concurrency_levels = [int(value) for value in args.concurrency.split(",") if value]
    if not concurrency_levels or min(concurrency_levels) < 1:
        raise SystemExit("--concurrency 必须是正整数列表")
    if args.fixture_docs <= 0 or args.chunks_per_doc <= 0:
        raise SystemExit("--fixture-docs/--chunks-per-doc 必须为正")
    if args.fixture_docs > 2000:
        raise SystemExit("--fixture-docs 上限 2000（有界基准，不做无限加压）")

    stack = None
    if not args.skip_stack:
        assert_port_free(APP_PORT)
        assert_port_free(MOCK_PORT)
        stack = LocalStack("perf_bench", APP_PORT, MOCK_PORT,
                           keep=args.keep_stack)
        stack.build_demo_if_needed()
        stack.create_database()
        stack.start_mock(delay_ms=0, stream_delay_ms=0, stream_chunks=0)
        app_args = [
            "--rag.admission.max-requests=1024",
            "--rag.admission.max-requests-per-principal=1024",
            "--rag.admission.max-model-calls=1024",
            "--rag.admission.max-model-calls-per-principal=1024",
            "--rag.admission.max-embedding-calls=1024",
            "--rag.long-running-operations.poll-interval-ms=600000",
            "--rag.evaluation.poll-interval-ms=600000",
            "--rag.embedding-jobs.poll-interval-ms=600000",
            "--rag.embedding-profile-backfill.poll-interval-ms=600000",
            "--rag.document-processing.housekeeping-interval-ms=600000",
            "--rag.collection-provisioning.cleanup-interval-ms=600000",
            "--rag.api-key-provisioning.cleanup-interval-ms=600000",
            "--rag.integration-observability.enabled=false",
            "--rag.data-governance.cleanup-enabled=false",
            "--rag.rate-limit.cleanup-interval-seconds=600000",
            "--management.endpoints.web.exposure.include=health,metrics",
        ]
        if args.hikari_leak_detection_ms > 0:
            app_args.append(
                "--spring.datasource.hikari.leak-detection-threshold="
                + str(args.hikari_leak_detection_ms))
        app_args.extend(args.app_arg)
        base_url = stack.start_app(
            extra_env={"DB_POOL_SIZE": str(args.pool_size),
                       "DB_POOL_MIN_IDLE": str(min(5, args.pool_size))},
            extra_args=app_args)
        args.base_url = base_url
        args.dsn = ("postgresql://%s:%s@%s:%s/%s" % (
            stack.pg_user, stack.pg_password, stack.pg_host, stack.pg_port,
            stack.db_name))
        args.api_key = ROOT_API_KEY
    else:
        if not (args.base_url and args.dsn and args.api_key):
            raise SystemExit("--skip-stack 需要 --base-url/--dsn/--api-key")

    collection_key = f"{FIXTURE_PREFIX}-{int(time.time())}-{os.getpid()}"
    output = {
        "schemaVersion": 1,
        "profile": args.profile,
        "generatedAt": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "commit": git_commit(),
        "environment": {
            "java": java_version(),
            "database": database_version(args.dsn),
            "os": platform.platform(),
            "cpus": os.cpu_count(),
            "poolSize": args.pool_size,
            "concurrency": concurrency_levels,
            "requestsPerWorker": args.requests_per_worker,
        },
        "fixture": {"documents": args.fixture_docs,
                    "chunks": args.fixture_docs * args.chunks_per_doc,
                    "collectionKey": collection_key},
        "workloads": {},
    }

    try:
        print("[perf] 准备确定性语料 ...", flush=True)
        seed = {"documents": args.fixture_docs,
                "chunks": args.fixture_docs * args.chunks_per_doc}
        if not args.skip_seed:
            seed = seed_corpus(args.dsn, collection_key, args.fixture_docs,
                               args.chunks_per_doc)
            output["fixture"]["seed"] = seed
        client = ApiClient(args.base_url, args.api_key, args.timeout)
        # 预热，避免首个请求承担类加载/JIT。
        client.request("GET", "/api/v1/rag/search",
                       {"query": "alpha bravo", "limit": "10",
                        "useHybrid": "false", "collectionKeys": collection_key})
        client.request("GET", "/api/v1/rag/collections/by-key/export",
                       {"collectionKey": collection_key})
        print("[perf] 开始并发基准 ...", flush=True)
        output["workloads"] = benchmark(args, client, collection_key)
    finally:
        if stack is None and not args.keep_fixtures:
            try:
                cleanup_corpus(args.dsn, collection_key)
            except Exception as error:  # noqa: BLE001
                print(f"[WARN] 清理 fixture 失败：{error}", file=sys.stderr)
        if stack is not None:
            stack.cleanup()

    text = json.dumps(output, indent=2, ensure_ascii=False)
    print(text)
    if args.output:
        Path(args.output).write_text(text + "\n", encoding="utf-8")
        print(f"wrote {args.output}", file=sys.stderr)
    return 0


def main():
    args = parse_args()
    return run(args)


if __name__ == "__main__":
    raise SystemExit(main())
