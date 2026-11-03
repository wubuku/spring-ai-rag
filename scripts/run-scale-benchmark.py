#!/usr/bin/env python3
"""
有界规模基准：确定性假 embedding + 两档语料，测导入吞吐、检索/列表/导出延迟与退化曲线。

设计目标不是绝对 QPS，而是“规模 ×2 后是否线性退化”，以及暴露 N+1 查询与全表扫描。

用法（先启动 mock embedding 服务与应用）：

    # 1) mock LLM/embedding（1024 维确定性/随机向量，零延迟）
    MOCK_PORT=18096 MOCK_DELAY_MS=0 node scripts/mock-llm-server.js

    # 2) 应用（独立数据库，避免污染开发库；starter 是库，入口用 demo jar）
    POSTGRES_DATABASE=spring_ai_rag_scale \\
    SPRING_PROFILES_ACTIVE=postgresql \\
    RAG_EMBEDDING_BASE_URL=http://127.0.0.1:18096 \\
    RAG_EMBEDDING_API_KEY=dummy \\
    RAG_ROOT_API_KEY=scale-bench-key-0123456789abcdefghijkl \\
    SERVER_PORT=18095 \\
    java -jar demos/demo-basic-rag/target/demo-basic-rag-1.0.0.jar

    # 3) 基准（写入需要显式确认）
    python3 scripts/run-scale-benchmark.py --ack-write \\
        --dsn 'postgresql://postgres:postgres@localhost:5432/spring_ai_rag_scale' \\
        --base-url http://127.0.0.1:18095 \\
        --api-key scale-bench-key-0123456789abcdefghijkl \\
        --docs 1000 --chunks-per-doc 10 --requests 20

依赖 psycopg2（`pip install psycopg2-binary`）。

默认是有界版本：1000 / 2000 文档 × 10 chunk（约 1 万 / 2 万向量行），
可用 --docs / --chunks-per-doc / --scale-docs 放大。脚本只写 `scale-bench-*`
fixture 集合，并在 --cleanup 时删除它们。

导出走流式接口：客户端把响应落盘而不是整体读入内存，并断言
`X-Rag-Export-Batch-Size` / `X-Rag-Export-Planned-Batches` 与文档数匹配；
断言失败时脚本以非零退出码结束。
"""

import argparse
import hashlib
import json
import math
import os
import random
import shutil
import statistics
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request

try:
    import psycopg2
    import psycopg2.extras
except ImportError:  # pragma: no cover - 环境缺失时给出可操作提示
    psycopg2 = None

FIXTURE_PREFIX = "scale-bench"
DEFAULT_CHUNKER_VERSION = "bounded-v3:1000:100:100:1000000:20000"
VECTOR_POOL_SIZE = 32
TEXT_WORDS = (
    "alpha bravo charlie delta echo foxtrot golf hotel india juliet kilo lima"
).split()


def percentiles(values):
    """返回 p50/p95/p99/max/mean；空输入返回 None 值。"""
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


def ratio(numerator, denominator):
    if not numerator or not denominator:
        return None
    return round(numerator / denominator, 3)


def build_vectors(dimensions):
    """预计算 32 个确定性单位向量，避免在 Python 里逐行生成百万级浮点。"""
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


def content_hash(value):
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


class Timer:
    def __init__(self):
        self.started = time.perf_counter()

    def ms(self):
        return round((time.perf_counter() - self.started) * 1000, 2)


class HttpClient:
    def __init__(self, base_url, api_key, timeout):
        self.base_url = base_url.rstrip("/")
        self.api_key = api_key
        self.timeout = timeout

    def request(self, method, path, params=None, body=None):
        url = self.base_url + path
        if params:
            url += "?" + urllib.parse.urlencode(params, doseq=True)
        data = json.dumps(body).encode("utf-8") if body is not None else None
        req = urllib.request.Request(url, data=data, method=method)
        req.add_header("X-API-Key", self.api_key)
        if data is not None:
            req.add_header("Content-Type", "application/json")
        started = time.perf_counter()
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as response:
                payload = response.read()
                elapsed = (time.perf_counter() - started) * 1000
                return elapsed, response.status, payload
        except urllib.error.HTTPError as error:
            elapsed = (time.perf_counter() - started) * 1000
            payload = error.read()
            raise RuntimeError(
                f"{method} {url} -> HTTP {error.code}: {payload[:300]!r}"
            ) from error
        except urllib.error.URLError as error:
            raise RuntimeError(f"{method} {url} failed: {error}") from error


def stream_response(response, target):
    """把响应体写入文件而不是读进内存，模拟真实导出客户端。"""
    with open(target, 'wb') as handle:
        shutil.copyfileobj(response, handle)
        return handle.tell()


def measure_export(client, collection_key, documents, requests=3, warmup=1):
    """流式导出：断言服务端按批写出，且 JSON 结构与文档数不变。

    服务端内存是否随行数线性增长无法从客户端直接观测，因此用响应头里的
    `X-Rag-Export-Batch-Size` / `X-Rag-Export-Planned-Batches` 做确定性断言：
    分块数 = ceil(documents / batchSize)，客户端只按流落盘、不整体驻留。
    """
    failures = []
    latencies = []
    last = {}
    with tempfile.TemporaryDirectory(prefix="rag-export-") as directory:
        target = os.path.join(directory, "export.json")
        for index in range(warmup + requests):
            started = time.perf_counter()
            url = (client.base_url
                   + "/api/v1/rag/collections/by-key/export?"
                   + urllib.parse.urlencode({"collectionKey": collection_key}))
            request = urllib.request.Request(url, method="GET")
            request.add_header("X-API-Key", client.api_key)
            try:
                with urllib.request.urlopen(request, timeout=client.timeout) as response:
                    headers = {key.lower(): value for key, value
                               in response.headers.items()}
                    size = stream_response(response, target)
                    status = response.status
            except urllib.error.HTTPError as error:
                raise RuntimeError(
                    f"GET {url} -> HTTP {error.code}: {error.read()[:300]!r}"
                ) from error
            elapsed = (time.perf_counter() - started) * 1000
            if index >= warmup:
                latencies.append(elapsed)
            last = {"status": status, "bytes": size, "headers": headers,
                    "path": target}

        batch_size = last["headers"].get("x-rag-export-batch-size")
        planned = last["headers"].get("x-rag-export-planned-batches")
        reported = last["headers"].get("x-rag-export-documents")
        expected_batches = math.ceil(documents / 200) if documents else 0
        if last["status"] != 200:
            failures.append(f"status={last['status']}")
        if batch_size != "200":
            failures.append(f"batch_size={batch_size}")
        if reported != str(documents):
            failures.append(f"documents_header={reported} expected={documents}")
        if planned != str(expected_batches):
            failures.append(f"planned_batches={planned} expected={expected_batches}")
        try:
            with open(last["path"], "r", encoding="utf-8") as handle:
                body = json.load(handle)
        except (OSError, ValueError) as error:
            failures.append(f"invalid json: {error}")
            body = {}
        if body.get("documentCount") != documents:
            failures.append(
                f"documentCount={body.get('documentCount')} expected={documents}")
        if len(body.get("documents") or []) != documents:
            failures.append(
                f"documents={len(body.get('documents') or [])} expected={documents}")

        return {
            **percentiles(latencies),
            "bytes": last["bytes"],
            "batch_size": None if batch_size is None else int(batch_size),
            "planned_batches": None if planned is None else int(planned),
            "expected_batches": expected_batches,
            "assertion": {"ok": not failures, "failures": failures},
        }


def measure(client, method, path, params=None, body=None, requests=10, warmup=2):
    latencies = []
    for _ in range(warmup):
        client.request(method, path, params, body)
    for _ in range(requests):
        elapsed, _, _ = client.request(method, path, params, body)
        latencies.append(elapsed)
    return percentiles(latencies)


def seed_corpus(dsn, collection_key, documents, chunks_per_doc, fixture):
    """直接写库生成确定性语料；幂等：先删同名 fixture 集合的全部行。"""
    if psycopg2 is None:
        raise RuntimeError("psycopg2 is required for seeding")

    connection = psycopg2.connect(dsn)
    connection.autocommit = False
    timings = {}
    try:
        with connection.cursor() as cursor:
            cursor.execute(
                "SELECT id, dimensions FROM rag_embedding_profiles "
                "WHERE enabled = true ORDER BY id LIMIT 1")
            profile_row = cursor.fetchone()
            if profile_row is None:
                raise RuntimeError("no enabled embedding profile; start the app once to register it")
            profile_id, dimensions = profile_row
            vectors = build_vectors(dimensions)
            chunker = DEFAULT_CHUNKER_VERSION

            # 幂等清理：按 collection_key 找到旧 fixture 并删除。
            cursor.execute(
                "SELECT id FROM rag_collection WHERE collection_key = %s",
                (collection_key,))
            old = cursor.fetchone()
            if old:
                old_collection_id = old[0]
                cursor.execute(
                    "DELETE FROM rag_embeddings WHERE document_id IN "
                    "(SELECT id FROM rag_documents WHERE collection_id = %s)",
                    (old_collection_id,))
                cursor.execute(
                    "DELETE FROM rag_document_chunks WHERE document_id IN "
                    "(SELECT id FROM rag_documents WHERE collection_id = %s)",
                    (old_collection_id,))
                cursor.execute(
                    "DELETE FROM rag_document_local_index_state WHERE document_id IN "
                    "(SELECT id FROM rag_documents WHERE collection_id = %s)",
                    (old_collection_id,))
                cursor.execute(
                    "DELETE FROM rag_document_embedding_state WHERE document_id IN "
                    "(SELECT id FROM rag_documents WHERE collection_id = %s)",
                    (old_collection_id,))
                cursor.execute(
                    "DELETE FROM rag_documents WHERE collection_id = %s",
                    (old_collection_id,))
                cursor.execute("DELETE FROM rag_collection WHERE id = %s",
                               (old_collection_id,))

            cursor.execute(
                "INSERT INTO rag_collection "
                "(collection_key, name, embedding_model, dimensions, enabled, "
                " active_embedding_profile_id) "
                "VALUES (%s, %s, %s, %s, true, %s) RETURNING id",
                (collection_key, f"{fixture} {documents}", "mock/scale", dimensions,
                 profile_id))
            collection_id = cursor.fetchone()[0]

            timer = Timer()
            doc_rows = []
            for index in range(documents):
                digest = content_hash(f"{collection_key}:{index}")
                body = f"{fixture} document {index} " + " ".join(
                    TEXT_WORDS[(index + i) % len(TEXT_WORDS)] for i in range(40))
                doc_rows.append((
                    collection_id, f"{fixture} doc {index:05d}", "scale-bench",
                    body, digest, len(body), chunker))
            psycopg2.extras.execute_values(
                cursor,
                "INSERT INTO rag_documents "
                "(collection_id, title, source, content, content_hash, size, "
                " derivation_chunker_version) VALUES %s",
                doc_rows, page_size=500)
            timings["documents_ms"] = timer.ms()
            timings["documents_per_second"] = round(documents / (timings["documents_ms"] / 1000), 1)

            cursor.execute(
                "SELECT id FROM rag_documents WHERE collection_id = %s ORDER BY id",
                (collection_id,))
            document_ids = [row[0] for row in cursor.fetchall()]

            timer = Timer()
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
                        profile_id, vectors[(doc_index * chunks_per_doc + chunk_index)
                                            % VECTOR_POOL_SIZE]))
            total_chunks = len(chunk_rows)
            psycopg2.extras.execute_values(
                cursor,
                "INSERT INTO rag_document_chunks "
                "(document_id, local_index_generation, content_hash, chunker_version, "
                " chunk_text, chunk_index, chunk_start_pos, chunk_end_pos) VALUES %s",
                chunk_rows, page_size=500)
            timings["chunks_ms"] = timer.ms()
            timings["chunks_per_second"] = round(total_chunks / (timer.ms() / 1000), 1)

            timer = Timer()
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
            timings["vectors_ms"] = timer.ms()
            timings["vectors_per_second"] = round(total_chunks / (timings["vectors_ms"] / 1000), 1)

            timer = Timer()
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
            timings["state_ms"] = timer.ms()

            timer = Timer()
            cursor.execute("ANALYZE rag_documents")
            cursor.execute("ANALYZE rag_document_chunks")
            cursor.execute("ANALYZE rag_embeddings")
            cursor.execute("ANALYZE rag_document_embedding_state")
            timings["analyze_ms"] = timer.ms()

        connection.commit()
        timings["total_ms"] = round(
            timings["documents_ms"] + timings["chunks_ms"] + timings["vectors_ms"]
            + timings["state_ms"] + timings["analyze_ms"], 2)
        return {
            "collection_id": collection_id,
            "profile_id": profile_id,
            "dimensions": dimensions,
            "documents": documents,
            "chunks": documents * chunks_per_doc,
            "timings": timings,
        }
    finally:
        connection.close()


def cleanup_fixtures(dsn):
    if psycopg2 is None:
        raise RuntimeError("psycopg2 is required for cleanup")
    connection = psycopg2.connect(dsn)
    connection.autocommit = True
    removed = 0
    try:
        with connection.cursor() as cursor:
            cursor.execute(
                "SELECT id FROM rag_collection WHERE collection_key LIKE %s",
                (FIXTURE_PREFIX + "%",))
            collection_ids = [row[0] for row in cursor.fetchall()]
            for collection_id in collection_ids:
                cursor.execute(
                    "DELETE FROM rag_embeddings WHERE document_id IN "
                    "(SELECT id FROM rag_documents WHERE collection_id = %s)",
                    (collection_id,))
                cursor.execute(
                    "DELETE FROM rag_document_chunks WHERE document_id IN "
                    "(SELECT id FROM rag_documents WHERE collection_id = %s)",
                    (collection_id,))
                cursor.execute(
                    "DELETE FROM rag_document_local_index_state WHERE document_id IN "
                    "(SELECT id FROM rag_documents WHERE collection_id = %s)",
                    (collection_id,))
                cursor.execute(
                    "DELETE FROM rag_document_embedding_state WHERE document_id IN "
                    "(SELECT id FROM rag_documents WHERE collection_id = %s)",
                    (collection_id,))
                cursor.execute("DELETE FROM rag_documents WHERE collection_id = %s",
                               (collection_id,))
                cursor.execute("DELETE FROM rag_collection WHERE id = %s",
                               (collection_id,))
                removed += 1
    finally:
        connection.close()
    return removed


def seed_ttl_rows(dsn, sessions, rows_per_session):
    """写入过期 chat history，用于测 TTL 清理的分批删除成本（不涉及租约事务）。"""
    connection = psycopg2.connect(dsn)
    connection.autocommit = False
    try:
        with connection.cursor() as cursor:
            cursor.execute(
                "DELETE FROM rag_chat_history WHERE session_id LIKE %s",
                (FIXTURE_PREFIX + "-ttl-%",))
            values = []
            for session in range(sessions):
                for row in range(rows_per_session):
                    values.append((
                        f"{FIXTURE_PREFIX}-ttl-{session:04d}",
                        "scale-bench",
                        "old question " + str(row),
                        "old answer " + str(row),
                        30,
                    ))
            psycopg2.extras.execute_values(
                cursor,
                "INSERT INTO rag_chat_history "
                "(session_id, owner_principal_id, user_message, ai_response, created_at) "
                "VALUES %s",
                values, template="(%s,%s,%s,%s,now() - (%s || ' days')::interval)",
                page_size=1000)
        connection.commit()
        return sessions * rows_per_session
    finally:
        connection.close()


def bench_ttl(dsn, sessions):
    """重放 ChatHistoryCleanupService 的候选查询 + 分批 DELETE（DB 层成本）。

    只处理 `scale-bench-ttl-*` fixture 会话；真实过期会话不会被本脚本删除。
    """
    connection = psycopg2.connect(dsn)
    connection.autocommit = True
    stats = {"sessions_processed": 0, "rows_deleted": 0, "session_query_ms": None}
    try:
        with connection.cursor() as cursor:
            timer = Timer()
            cursor.execute(
                "SELECT owner_principal_id, session_id "
                "FROM rag_chat_history "
                "WHERE owner_principal_id IS NOT NULL AND created_at < now() - interval '30 days' "
                "GROUP BY owner_principal_id, session_id "
                "ORDER BY MIN(created_at), owner_principal_id, session_id LIMIT 50")
            cursor.fetchall()
            stats["session_query_ms"] = timer.ms()

            timer = Timer()
            for _ in range(sessions + 1):
                cursor.execute(
                    "SELECT DISTINCT session_id "
                    "FROM rag_chat_history "
                    "WHERE session_id LIKE %s "
                    "AND created_at < now() - interval '30 days' "
                    "ORDER BY session_id LIMIT 50",
                    (FIXTURE_PREFIX + "-ttl-%",))
                candidates = [row[0] for row in cursor.fetchall()]
                if not candidates:
                    break
                for session in candidates:
                    cursor.execute(
                        "WITH victims AS ("
                        " SELECT id FROM rag_chat_history"
                        " WHERE session_id = %s AND created_at < now() - interval '30 days'"
                        " ORDER BY created_at ASC, id ASC LIMIT 500)"
                        " DELETE FROM rag_chat_history history USING victims"
                        " WHERE history.id = victims.id",
                        (session,))
                    stats["rows_deleted"] += cursor.rowcount
                    stats["sessions_processed"] += 1
            stats["cleanup_ms"] = timer.ms()
            return stats
    finally:
        connection.close()


def table_stats(dsn):
    connection = psycopg2.connect(dsn)
    try:
        with connection.cursor() as cursor:
            cursor.execute(
                "SELECT relname, seq_scan, seq_tup_read, idx_scan, idx_tup_fetch "
                "FROM pg_stat_user_tables "
                "WHERE relname IN ('rag_documents', 'rag_document_chunks', "
                "'rag_embeddings', 'rag_document_embedding_state', 'rag_chat_history')")
            rows = cursor.fetchall()
            return {row[0]: {
                "seq_scan": row[1], "seq_tup_read": row[2],
                "idx_scan": row[3], "idx_tup_fetch": row[4],
            } for row in rows}
    finally:
        connection.close()


def diff_table_stats(before, after):
    result = {}
    for table in sorted(set(before) | set(after)):
        start = before.get(table, {})
        end = after.get(table, {})
        result[table] = {
            key: int(end.get(key, 0)) - int(start.get(key, 0))
            for key in ("seq_scan", "seq_tup_read", "idx_scan", "idx_tup_fetch")
        }
    return result


def bench_http(client, collection_key, documents, chunk_count, args):
    result = {}
    result["documents_first_page"] = measure(
        client, "GET", "/api/v1/rag/documents",
        {"collectionKey": collection_key, "offset": 0, "limit": 20},
        requests=args.requests, warmup=args.warmup)
    result["documents_deep_page"] = measure(
        client, "GET", "/api/v1/rag/documents",
        {"collectionKey": collection_key,
         "offset": max(0, documents - 20), "limit": 20},
        requests=args.requests, warmup=args.warmup)
    result["collections_list"] = measure(
        client, "GET", "/api/v1/rag/collections",
        {"offset": 0, "limit": 20},
        requests=args.requests, warmup=args.warmup)
    result["search_vector_only"] = measure(
        client, "GET", "/api/v1/rag/search",
        {"query": "alpha bravo", "limit": 10, "useHybrid": "false",
         "collectionKeys": collection_key},
        requests=args.requests, warmup=args.warmup)
    result["search_hybrid"] = measure(
        client, "GET", "/api/v1/rag/search",
        {"query": "alpha bravo", "limit": 10, "useHybrid": "true",
         "vectorWeight": 0.5, "fulltextWeight": 0.5,
         "collectionKeys": collection_key},
        requests=args.requests, warmup=args.warmup)
    result["search_fulltext_weighted"] = measure(
        client, "GET", "/api/v1/rag/search",
        {"query": "alpha bravo", "limit": 10, "useHybrid": "true",
         "vectorWeight": 0.0, "fulltextWeight": 1.0,
         "collectionKeys": collection_key},
        requests=args.requests, warmup=args.warmup)
    result["export_collection"] = measure_export(
        client, collection_key, documents,
        requests=max(3, args.requests // 4), warmup=1)
    evaluation_body = {
        "query": "alpha bravo",
        "retrievedDocIds": list(range(1, 11)),
        "relevantDocIds": list(range(1, 6)),
        "evaluationMethod": "AUTO",
        "evaluatorId": "scale-bench",
    }
    result["evaluation"] = measure(
        client, "POST", "/api/v1/rag/evaluation/evaluate",
        body=evaluation_body,
        requests=args.requests, warmup=args.warmup)
    result["chunks"] = chunk_count
    return result


def run(args):
    if args.self_test:
        values = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10]
        stats = percentiles(values)
        assert stats["p50"] == 5 and stats["p95"] == 10 and stats["p99"] == 10, stats
        assert ratio(2.0, 1.0) == 2.0 and ratio(1, 0) is None
        print("self-test ok")
        return 0

    if args.docs <= 0 or args.chunks_per_doc <= 0:
        raise SystemExit("--docs and --chunks-per-doc must be positive")
    if args.docs > 5000:
        raise SystemExit("--docs is capped at 5000 for this bounded script")
    if not args.ack_write:
        raise SystemExit("refusing to write fixture data without --ack-write")
    if not args.dsn:
        raise SystemExit("--dsn is required (or set SCALE_BENCH_DSN)")

    started = time.perf_counter()
    scales = [args.docs]
    if args.scale_docs and args.scale_docs > 0:
        scales.append(args.scale_docs)

    output = {"generated_at": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
              "bounded": True, "scales": {}}

    seeded = {}
    if not args.skip_seed:
        for documents in scales:
            key = f"{FIXTURE_PREFIX}-{documents}"
            print(f"seeding {documents} documents × {args.chunks_per_doc} chunks ...",
                  flush=True)
            seeded[documents] = {"collection_key": key,
                                 "seed": seed_corpus(args.dsn, key, documents,
                                                     args.chunks_per_doc, key)}
            output["scales"][str(documents)] = {"seed": seeded[documents]["seed"]}
        if args.seed_ttl_rows:
            print(f"seeding {args.seed_ttl_rows} expired chat rows ...", flush=True)
            seed_ttl_rows(args.dsn, args.ttl_sessions, args.ttl_rows_per_session)
    else:
        for documents in scales:
            key = f"{FIXTURE_PREFIX}-{documents}"
            seeded[documents] = {"collection_key": key, "seed": {
                "documents": documents,
                "chunks": documents * args.chunks_per_doc,
                "timings": {},
            }}
            output["scales"][str(documents)] = {"seed": seeded[documents]["seed"]}

    if not args.skip_http:
        client = HttpClient(args.base_url, args.api_key, args.timeout)
        before = table_stats(args.dsn)
        for documents in scales:
            key = seeded[documents]["collection_key"]
            print(f"benchmarking {documents} documents ...", flush=True)
            bench = bench_http(client, key, documents,
                               documents * args.chunks_per_doc, args)
            output["scales"][str(documents)]["http"] = bench
        output["table_stats_delta"] = diff_table_stats(before, table_stats(args.dsn))

    if args.seed_ttl_rows and not args.skip_http:
        output["ttl_cleanup"] = bench_ttl(args.dsn, args.ttl_sessions)

    if args.cleanup:
        removed = cleanup_fixtures(args.dsn)
        output["cleanup_collections_removed"] = removed

    if len(scales) >= 2:
        small, large = str(min(scales)), str(max(scales))
        curve = {}
        small_http = output["scales"][small].get("http", {})
        large_http = output["scales"][large].get("http", {})
        for name in small_http:
            if name == "chunks":
                continue
            small_p95 = (small_http.get(name) or {}).get("p95")
            large_p95 = (large_http.get(name) or {}).get("p95")
            curve[name] = {
                "p95_ratio": ratio(large_p95, small_p95),
                "docs_ratio": ratio(int(large), int(small)),
            }
        output["degradation"] = curve
        seed_small = output["scales"][small]["seed"]["timings"]
        seed_large = output["scales"][large]["seed"]["timings"]
        output["seed_degradation"] = {
            name: ratio(seed_large.get(name), seed_small.get(name))
            for name in ("documents_ms", "chunks_ms", "vectors_ms")
        }

    # 导出断言失败时以非零退出码结束，避免压测"看起来跑完了"。
    export_failures = {}
    for documents, scale in output["scales"].items():
        export = (scale.get("http") or {}).get("export_collection") or {}
        assertion = export.get("assertion")
        if assertion and not assertion.get("ok"):
            export_failures[documents] = assertion.get("failures")
    if export_failures:
        output["export_assertion_failures"] = export_failures

    output["wall_seconds"] = round(time.perf_counter() - started, 1)
    text = json.dumps(output, indent=2, ensure_ascii=False)
    print(text)
    if args.output:
        with open(args.output, "w", encoding="utf-8") as handle:
            handle.write(text + "\n")
        print(f"wrote {args.output}", file=sys.stderr)
    return 1 if export_failures else 0


def parse_args():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dsn", default=os.getenv("SCALE_BENCH_DSN"),
                        help="PostgreSQL DSN for seeding/stat queries")
    parser.add_argument("--base-url", default=os.getenv("SCALE_BENCH_BASE_URL",
                                                        "http://127.0.0.1:18095"))
    parser.add_argument("--api-key", default=os.getenv("RAG_ROOT_API_KEY", ""))
    parser.add_argument("--docs", type=int, default=1000,
                        help="fixture documents for the small scale")
    parser.add_argument("--scale-docs", type=int, default=2000,
                        help="fixture documents for the large scale (0 disables)")
    parser.add_argument("--chunks-per-doc", type=int, default=10)
    parser.add_argument("--requests", type=int, default=20)
    parser.add_argument("--warmup", type=int, default=3)
    parser.add_argument("--timeout", type=float, default=120.0)
    parser.add_argument("--seed-ttl-rows", type=int, default=0,
                        help="expired chat rows to seed for TTL cleanup timing")
    parser.add_argument("--ttl-sessions", type=int, default=100)
    parser.add_argument("--ttl-rows-per-session", type=int, default=100)
    parser.add_argument("--skip-seed", action="store_true")
    parser.add_argument("--skip-http", action="store_true")
    parser.add_argument("--cleanup", action="store_true",
                        help="delete scale-bench-* fixtures after the run")
    parser.add_argument("--output", help="write JSON report to this path")
    parser.add_argument("--ack-write", action="store_true",
                        help="required acknowledgement for fixture writes")
    parser.add_argument("--self-test", action="store_true")
    return parser.parse_args()


if __name__ == "__main__":
    raise SystemExit(run(parse_args()))
