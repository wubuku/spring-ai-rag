#!/usr/bin/env python3
"""慢 SQL 与索引/执行计划验证：pg_stat_statements + EXPLAIN。

默认自建一次性 PostgreSQL 容器（`pgvector/pgvector:pg16`），并在启动参数里
预加载 `pg_stat_statements`（宿主机日常库通常没有预加载，不能直接取样）。

流程：
  1. 启动临时 PG / mock provider / demo-basic-rag；
  2. 直接写库生成确定性语料（默认 1200 文档 × 8 chunk）；
  3. 清空 pg_stat_statements，跑向量/混合检索与导出；
  4. 取 total_exec_time 前 10 的 SQL；
  5. EXPLAIN 关键路径：向量 ANN、导出 keyset、标题关键词；
  6. 断言 ANN 使用 HNSW、关键查询没有退化为全表扫描。

只清理自己启动的容器/进程/数据库；容器名带 PID。
"""

from __future__ import annotations

import argparse
import importlib.util
import json
import os
import statistics
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPT_DIR))

try:
    import psycopg2
except ImportError:  # pragma: no cover
    psycopg2 = None

from lib.rag_local_stack import ROOT_API_KEY, LocalStack  # noqa: E402

APP_PORT = 18093
MOCK_PORT = 18094
PG_PORT = 15549
PG_IMAGE = "pgvector/pgvector:pg16"
FIXTURE_PREFIX = "queryplan"
PG_CONTAINER = f"rag-queryplan-pg-{os.getpid()}"


def load_scale_benchmark():
    spec = importlib.util.spec_from_file_location(
        "run_scale_benchmark", SCRIPT_DIR / "run-scale-benchmark.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def run(command, **kwargs):
    return subprocess.run(command, capture_output=True, text=True, **kwargs)


def port_in_use(port):
    result = run(["lsof", "-ti", f":{port}", "-sTCP:LISTEN"])
    return result.stdout.strip()


def wait_for_postgres(port, timeout=60):
    deadline = time.time() + timeout
    while time.time() < deadline:
        probe = run(["pg_isready", "-h", "127.0.0.1", "-p", str(port)])
        if probe.returncode == 0:
            return
        time.sleep(1)
    raise RuntimeError(f"PostgreSQL 容器 {PG_CONTAINER} 未在 {timeout}s 内就绪")


def start_pg_container():
    if port_in_use(PG_PORT):
        raise SystemExit(f"端口 {PG_PORT} 已占用；脚本不会杀掉非自身进程")
    run(["docker", "rm", "-f", PG_CONTAINER])
    result = run([
        "docker", "run", "-d", "--name", PG_CONTAINER,
        "-e", "POSTGRES_PASSWORD=postgres",
        "-p", f"127.0.0.1:{PG_PORT}:5432",
        PG_IMAGE,
        "-c", "shared_preload_libraries=pg_stat_statements",
        "-c", "pg_stat_statements.track=all",
        "-c", "pg_stat_statements.max=10000",
    ])
    if result.returncode != 0:
        raise RuntimeError(f"启动临时 PG 失败：{result.stderr[-500:]}")
    wait_for_postgres(PG_PORT)


def stop_pg_container():
    # 只删自己按 PID 命名的容器，绝不动其他容器。
    run(["docker", "rm", "-f", PG_CONTAINER])


class Client:
    def __init__(self, base_url, api_key, timeout=60):
        self.base_url = base_url.rstrip("/")
        self.api_key = api_key
        self.timeout = timeout

    def get(self, path, params=None):
        url = self.base_url + path
        if params:
            url += "?" + urllib.parse.urlencode(params, doseq=True)
        request = urllib.request.Request(url, method="GET")
        request.add_header("X-API-Key", self.api_key)
        with urllib.request.urlopen(request, timeout=self.timeout) as response:
            return response.status, response.read()


def reset_stat_statements(dsn):
    connection = psycopg2.connect(dsn)
    connection.autocommit = True
    try:
        with connection.cursor() as cursor:
            cursor.execute("SELECT pg_stat_statements_reset()")
    finally:
        connection.close()


def top_statements(dsn, limit=10):
    connection = psycopg2.connect(dsn)
    try:
        with connection.cursor() as cursor:
            cursor.execute(
                "SELECT column_name FROM information_schema.columns "
                "WHERE table_name='pg_stat_statements'")
            columns = {row[0] for row in cursor.fetchall()}
            total = "total_exec_time" if "total_exec_time" in columns else "total_time"
            mean = "mean_exec_time" if "mean_exec_time" in columns else "mean_time"
            cursor.execute(
                "SELECT left(regexp_replace(query, E'[[:space:]]+', ' ', 'g'), 180), "
                f"calls, round({total}::numeric, 2), round({mean}::numeric, 2), rows "
                "FROM pg_stat_statements "
                "WHERE query NOT LIKE '%%pg_stat_statements%%' "
                f"ORDER BY {total} DESC LIMIT %s",
                (limit,))
            return [
                {"query": row[0], "calls": row[1], "total_ms": float(row[2]),
                 "mean_ms": float(row[3]), "rows": row[4]}
                for row in cursor.fetchall()
            ]
    finally:
        connection.close()


def explain(dsn, sql, params=None, seqscan_off=False):
    connection = psycopg2.connect(dsn)
    connection.autocommit = False
    try:
        with connection.cursor() as cursor:
            if seqscan_off:
                cursor.execute("SET LOCAL enable_seqscan = off")
            cursor.execute("EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) " + sql,
                           params or ())
            payload = cursor.fetchone()[0]
            plan = payload[0] if isinstance(payload, list) else payload
        connection.rollback()
        return plan
    finally:
        connection.close()


def flatten_plan(node):
    yield node
    for child in node.get("Plans", []) or []:
        yield from flatten_plan(child)


def plan_summary(plan):
    nodes = list(flatten_plan(plan["Plan"]))
    return {
        "node_types": [node.get("Node Type") for node in nodes],
        "index_names": [node.get("Index Name") for node in nodes
                        if node.get("Index Name")],
        "relation_names": [node.get("Relation Name") for node in nodes
                           if node.get("Relation Name")],
        "execution_ms": round(plan.get("Execution Time", 0), 2),
        "seq_scan_relations": [node.get("Relation Name") for node in nodes
                               if node.get("Node Type") == "Seq Scan"],
        "index_used": any(node.get("Node Type") in ("Index Scan", "Bitmap Index Scan",
                                                    "Index Only Scan")
                          for node in nodes),
        "hnsw_used": any("hnsw" in (node.get("Index Name") or "").lower()
                         for node in nodes),
    }


def build_vector_sql(profile_id, dimensions, collection_id, vector, limit,
                     scope=True):
    """按生产 SQL 的形态构造向量候选查询。

    关键：生产代码把 profile id 内联成字面量（见 HybridRetrieverService
    `WHERE e.embedding_profile_id = <id>`）。HNSW 是 **部分索引**，谓词里
    带 profile id；如果这里改成绑定参数，规划器无法证明
    `embedding_profile_id = $1` 能蕴含部分索引谓词，会退回 Seq Scan ——
    那是脚本失真，不是生产退化。这里必须和内联字面量保持一致。
    """
    column = f"embedding_{dimensions}"
    scope_sql = ""
    params = []
    if scope:
        scope_sql = ("AND e.document_id IN (SELECT d.id FROM rag_documents d "
                     "WHERE d.enabled = TRUE AND d.collection_id = %s) ")
        params.append(collection_id)
    sql = (
        "WITH candidates AS MATERIALIZED ("
        "SELECT e.id, e.document_id, e.chunk_index, e.chunk_text, e.metadata, "
        "e.embedding_profile_id, e." + column + " AS embedding_vector "
        "FROM rag_embeddings e "
        f"WHERE e.embedding_profile_id = {int(profile_id)} "
        "AND e." + column + " IS NOT NULL "
        + scope_sql
        + "ORDER BY e." + column + " <=> CAST(%s AS vector) "
        "LIMIT %s) "
        "SELECT * FROM candidates"
    )
    params.extend([vector, limit])
    return sql, tuple(params)


def run_workload(client, collection_key, rounds=40):
    params_vector = {"query": "alpha bravo", "limit": "10",
                     "useHybrid": "false", "collectionKeys": collection_key}
    params_hybrid = {"query": "alpha bravo", "limit": "10",
                     "useHybrid": "true", "collectionKeys": collection_key}
    for _ in range(rounds):
        client.get("/api/v1/rag/search", params_vector)
        client.get("/api/v1/rag/search", params_hybrid)
    for _ in range(10):
        client.get("/api/v1/rag/collections/by-key/export",
                   {"collectionKey": collection_key})


def collect(args):
    if psycopg2 is None:
        raise SystemExit("需要 psycopg2：pip install psycopg2-binary")
    if not args.docker:
        raise SystemExit("当前只支持 --docker（需要 pg_stat_statements 预加载）")

    start_pg_container()
    os.environ["POSTGRES_HOST"] = "127.0.0.1"
    os.environ["POSTGRES_PORT"] = str(PG_PORT)
    os.environ["POSTGRES_USER"] = "postgres"
    os.environ["POSTGRES_PASSWORD"] = "postgres"

    stack = LocalStack("queryplan", APP_PORT, MOCK_PORT, keep=args.keep)
    dsn = f"postgresql://postgres:postgres@127.0.0.1:{PG_PORT}/{stack.db_name}"
    collection_key = f"{FIXTURE_PREFIX}-{int(time.time())}-{os.getpid()}"
    result = {
        "schemaVersion": 1,
        "generatedAt": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "fixture": {"documents": args.documents,
                    "chunks": args.documents * args.chunks_per_doc},
        "statements": [],
        "plans": {},
        "assertions": [],
    }
    try:
        stack.create_database()
        # V1 迁移会创建 vector/pg_trgm/pg_jieba；pg_stat_statements 需要手动建。
        stack._psql(stack.db_name,
                    "CREATE EXTENSION IF NOT EXISTS pg_stat_statements", scalar=False)
        stack.build_demo_if_needed()
        stack.start_mock(delay_ms=0, stream_delay_ms=0, stream_chunks=0)
        base_url = stack.start_app(
            extra_env={"DB_POOL_SIZE": "20", "DB_POOL_MIN_IDLE": "5"},
            extra_args=[
                "--rag.admission.max-requests=512",
                "--rag.admission.max-requests-per-principal=512",
                "--rag.long-running-operations.poll-interval-ms=600000",
                "--rag.document-processing.housekeeping-interval-ms=600000",
                "--management.endpoints.web.exposure.include=health,metrics",
            ])

        # 启动后 profile 注册是异步的，直接写库前先等它落库。
        deadline = time.time() + 90
        while time.time() < deadline:
            connection = psycopg2.connect(dsn)
            try:
                with connection.cursor() as cursor:
                    cursor.execute(
                        "SELECT count(*) FROM rag_embedding_profiles WHERE enabled = true")
                    if cursor.fetchone()[0] > 0:
                        break
            finally:
                connection.close()
            time.sleep(1)
        else:
            raise RuntimeError("90s 内没有可用的 embedding profile")

        benchmark = load_scale_benchmark()
        print("[plans] 生成确定性语料 ...", flush=True)
        seed = benchmark.seed_corpus(dsn, collection_key, args.documents,
                                      args.chunks_per_doc, FIXTURE_PREFIX)
        result["fixture"].update(seed)

        client = Client(base_url, ROOT_API_KEY)
        # 预热一次，避免首查询的 JIT/planning 噪声进入统计。
        client.get("/api/v1/rag/search", {
            "query": "alpha bravo", "limit": "10",
            "useHybrid": "false", "collectionKeys": collection_key})
        reset_stat_statements(dsn)
        print("[plans] 采样负载 ...", flush=True)
        run_workload(client, collection_key, rounds=args.rounds)
        result["statements"] = top_statements(dsn)

        connection = psycopg2.connect(dsn)
        try:
            with connection.cursor() as cursor:
                cursor.execute(
                    "SELECT dimensions FROM rag_embedding_profiles "
                    "WHERE id = %s", (seed["profile_id"],))
                dimensions = cursor.fetchone()[0]
                cursor.execute(
                    "SELECT indexname, indexdef FROM pg_indexes "
                    "WHERE tablename = 'rag_embeddings' ORDER BY indexname")
                result["indexes"] = [
                    {"name": row[0], "definition": row[1]}
                    for row in cursor.fetchall()
                ]
                cursor.execute(
                    "SELECT c.relname, i.indisvalid FROM pg_index i "
                    "JOIN pg_class c ON c.oid = i.indexrelid "
                    "JOIN pg_class t ON t.oid = i.indrelid "
                    "WHERE t.relname = 'rag_embeddings'")
                result["indexValidity"] = [
                    {"name": row[0], "valid": bool(row[1])}
                    for row in cursor.fetchall()
                ]
        finally:
            connection.close()

        vectors = [
            "[" + ",".join(f"{0.001 * (i % 7 + 1):.6f}" for i in range(dimensions))
            + "]"
        ]
        vector_sql, vector_params = build_vector_sql(
            seed["profile_id"], dimensions, seed["collection_id"],
            vectors[0], 50)
        vector_plan = explain(dsn, vector_sql, vector_params)
        result["plans"]["vector_ann"] = plan_summary(vector_plan)

        # 诊断：去掉 collection 过滤 / 强制禁 seqscan / collection 也用字面量，
        # 用于区分“索引缺失”“部分索引谓词不匹配”和“带过滤后规划器改选”。
        # 这些计划只用于定位问题，不参与门禁。
        no_scope_sql, no_scope_params = build_vector_sql(
            seed["profile_id"], dimensions, seed["collection_id"],
            vectors[0], 50, scope=False)
        result["plans"]["vector_ann_no_scope"] = plan_summary(
            explain(dsn, no_scope_sql, no_scope_params))
        result["plans"]["vector_ann_forced"] = plan_summary(
            explain(dsn, vector_sql, vector_params, seqscan_off=True))
        literal_scope_sql = (
            "WITH candidates AS MATERIALIZED ("
            "SELECT e.id, e.document_id, e.chunk_index, e.chunk_text, e.metadata, "
            f"e.embedding_profile_id, e.embedding_{dimensions} AS embedding_vector "
            "FROM rag_embeddings e "
            f"WHERE e.embedding_profile_id = {int(seed['profile_id'])} "
            f"AND e.embedding_{dimensions} IS NOT NULL "
            "AND e.document_id IN (SELECT d.id FROM rag_documents d "
            f"WHERE d.enabled = TRUE AND d.collection_id = {int(seed['collection_id'])}) "
            f"ORDER BY e.embedding_{dimensions} <=> CAST(%s AS vector) "
            "LIMIT %s) SELECT * FROM candidates")
        result["plans"]["vector_ann_literal_scope"] = plan_summary(
            explain(dsn, literal_scope_sql, (vectors[0], 50)))

        export_plan = explain(
            dsn,
            "SELECT id, title, source FROM rag_documents "
            "WHERE collection_id = %s AND id > 0 ORDER BY id LIMIT 200",
            (seed["collection_id"],))
        result["plans"]["export_keyset"] = plan_summary(export_plan)

        title_sql = ("SELECT id FROM rag_documents "
                     "WHERE collection_id = %s "
                     "AND lower(title) LIKE lower(%s) ORDER BY id DESC LIMIT 20")
        title_params = (seed["collection_id"], "%alpha%")
        natural = explain(dsn, title_sql, title_params)
        forced = explain(dsn, title_sql, title_params, seqscan_off=True)
        result["plans"]["title_keyword"] = {
            "natural": plan_summary(natural),
            "forced": plan_summary(forced),
        }

        failures = []
        if not result["plans"]["vector_ann"]["hnsw_used"]:
            failures.append("vector_ann 未使用 HNSW 索引")
        if "rag_embeddings" in result["plans"]["vector_ann"]["seq_scan_relations"]:
            failures.append("vector_ann 在 rag_embeddings 上退化为 Seq Scan")
        if not result["plans"]["export_keyset"]["index_used"]:
            failures.append("export_keyset 未使用索引")
        if "rag_documents" in result["plans"]["export_keyset"]["seq_scan_relations"]:
            failures.append("export_keyset 在 rag_documents 上退化为 Seq Scan")
        if not result["plans"]["title_keyword"]["forced"]["index_used"]:
            failures.append("title_keyword 在关闭 seqscan 后仍无索引可用")
        if not result["statements"]:
            failures.append("pg_stat_statements 没有采样到语句")
        result["assertions"] = {"ok": not failures, "failures": failures}
        return result
    finally:
        try:
            stack.cleanup()
        finally:
            stop_pg_container()


def self_test():
    plan = {"Plan": {"Node Type": "Seq Scan", "Relation Name": "rag_embeddings"}}
    summary = plan_summary(plan)
    assert not summary["index_used"] and summary["seq_scan_relations"] == ["rag_embeddings"]
    plan = {"Plan": {"Node Type": "Index Scan", "Index Name": "idx_x_hnsw",
                     "Relation Name": "rag_embeddings"}}
    summary = plan_summary(plan)
    assert summary["index_used"] and summary["hnsw_used"]
    print("query plan self-test ok")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--documents", type=int, default=1200)
    parser.add_argument("--chunks-per-doc", type=int, default=8)
    parser.add_argument("--rounds", type=int, default=40)
    parser.add_argument("--docker", action="store_true",
                        help="启动临时 PG 容器（唯一支持的模式）")
    parser.add_argument("--output", default="/tmp/query-plans.json")
    parser.add_argument("--keep", action="store_true", help="保留临时目录与日志")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return 0
    result = collect(args)
    text = json.dumps(result, indent=2, ensure_ascii=False)
    Path(args.output).write_text(text + "\n", encoding="utf-8")
    print(text)
    if not result["assertions"]["ok"]:
        for failure in result["assertions"]["failures"]:
            print("[plans][FAIL] " + failure, file=sys.stderr)
        return 1
    print("[plans][PASS] 关键检索路径使用索引，未退化为全表扫描")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
