#!/usr/bin/env python3
"""P0-5 大表专项：真实 PostgreSQL 16 + pgvector 的非均匀多租户 HNSW 召回/延迟压测。

只操作脚本自建的一次性 Docker 容器（默认随机宿主端口），绝不连接共享 dev 库。

语料设计（可参数化）：
  --rows 300000 --shares 80,15,5 --tenants t_large,t_medium,t_small
  1024 维向量，按租户簇生成：向量前 3 维为租户中心 + 小扰动，其余维为确定性伪随机。
  这样精确 top-10 仍落在同一租户簇内，ANN 候选被大租户挤占时可观测召回退化。

实测流程：
  1. 建表 rag_documents / rag_embeddings（字段对齐仓库 V1/V25/V76/V81）。
  2. 按租户分批 INSERT，ANALYZE，建 V81 同款全局 HNSW（m=16, ef_construction=64）。
  3. 对每个查询租户取 50 个 query（来自该租户真实行 + 扰动），以精确扫描计算 ground truth top-10。
  4. 分别在 SET hnsw.iterative_scan = off / relaxed_order 下跑 50 query，
     用临时 SET hnsw.ef_search 控制候选深度，统计 Recall@10 和 P95 延迟；保存 EXPLAIN 计划。
  5. 把机器可读结果写到 --out JSON（不写密钥、不写 .env）。

退出码：完成即 0；无法建容器/不支持 GUC/断言失败则非 0（不伪造数据）。
"""

from __future__ import annotations

import argparse
import json
import math
import os
import subprocess
import sys
import time
import traceback
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any

try:
    import psycopg2
except ImportError:
    sys.exit("缺少 psycopg2：请 pip install psycopg2-binary")
from pgvector.psycopg2 import register_vector
try:
    import numpy as np
except ImportError:
    sys.exit("缺少 numpy：请 pip install numpy")


def log(msg: str) -> None:
    print(f"[p05-hnsw] {datetime.now(timezone.utc).isoformat()} {msg}", flush=True)


def run(cmd: list[str], *, check: bool = True, capture: bool = False) -> subprocess.CompletedProcess:
    result = subprocess.run(cmd, text=True, capture_output=capture)
    if check and result.returncode != 0:
        raise RuntimeError(f"命令失败 ({result.returncode}): {' '.join(cmd)}\n{result.stdout}\n{result.stderr}")
    return result


@dataclass
class Stack:
    container: str
    host: str = "127.0.0.1"

    @property
    def dsn(self) -> dict[str, Any]:
        return {"host": self.host, "port": 5432, "dbname": "p05", "user": "p05", "password": "p05"}

    def start(self, image: str) -> None:
        log(f"启动一次性容器 image={image}")
        run([
            "docker", "run", "-d", "--name", self.container,
            "-e", "POSTGRES_DB=p05", "-e", "POSTGRES_USER=p05", "-e", "POSTGRES_PASSWORD=p05",
            "--health-cmd", "pg_isready -U p05 -d p05", "--health-interval=2s",
            "--health-timeout=2s", "--health-retries=60", image,
        ])
        for _ in range(60):
            healthy = run([
                "docker", "inspect", "--format", "{{.State.Health.Status}}", self.container
            ], capture=True).stdout.strip()
            if healthy == "healthy":
                # OrbStack 部分版本 host port mapping 不生效；直接走 Docker bridge IP。
                self.host = run([
                    "docker", "inspect", "-f",
                    "{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}", self.container
                ], capture=True).stdout.strip()
                log("容器健康")
                return
            time.sleep(1)
        raise RuntimeError("容器 60s 内未健康，执行 docker logs 检查")

    def stop(self, keep: bool) -> None:
        if keep:
            log(f"--keep-container：容器 {self.container} 保留供复核")
            return
        log(f"删除一次性容器 {self.container}")
        run(["docker", "rm", "-f", self.container], check=False)


def connect(stack: Stack):
    last: Exception | None = None
    for _ in range(30):
        try:
            conn = psycopg2.connect(**stack.dsn, connect_timeout=5)
            break
        except Exception as exc:
            last = exc
            time.sleep(1)
    else:
        raise RuntimeError(f"30s 内无法连接容器 PostgreSQL：{last}")
    conn.autocommit = True
    with conn.cursor() as cur:
        cur.execute("CREATE EXTENSION IF NOT EXISTS vector")
        cur.execute("SELECT extversion FROM pg_extension WHERE extname='vector'")
        version = cur.fetchone()[0]
        cur.execute("SHOW server_version")
        pg_version = cur.fetchone()[0]
        cur.execute("SELECT current_setting('hnsw.iterative_scan', true)")
        guc_default = cur.fetchone()[0]
    register_vector(conn)
    log(f"PostgreSQL {pg_version} / pgvector {version} / hnsw.iterative_scan(default)={guc_default}")
    return conn, {"pg_version": pg_version, "pgvector_version": version, "hnsw_iterative_scan_default": guc_default}


def create_schema(cur) -> None:
    for statement in (
        """CREATE TABLE rag_documents (
            id BIGSERIAL PRIMARY KEY,
            tenant_id VARCHAR(64) NOT NULL,
            title TEXT NOT NULL,
            content TEXT NOT NULL,
            enabled BOOLEAN NOT NULL DEFAULT true,
            created_at TIMESTAMPTZ NOT NULL DEFAULT now()
        )""",
        """CREATE TABLE rag_embeddings (
            id BIGSERIAL PRIMARY KEY,
            tenant_id VARCHAR(64) NOT NULL,
            document_id BIGINT NOT NULL REFERENCES rag_documents(id),
            chunk_text TEXT NOT NULL,
            chunk_index INTEGER NOT NULL DEFAULT 0,
            embedding_1024 VECTOR(1024) NOT NULL,
            created_at TIMESTAMPTZ NOT NULL DEFAULT now()
        )""",
        "CREATE INDEX idx_rag_documents_tenant_leading ON rag_documents (tenant_id)",
        "CREATE INDEX idx_rag_embeddings_tenant_leading ON rag_embeddings (tenant_id)",
    ):
        cur.execute(statement)


def load_corpus(cur, rows: int, shares: list[float], tenants: list[str], seed: int, batch: int) -> list[dict[str, Any]]:
    """服务端 SQL 生成伪随机向量（setseed + random()），避免 Python 端逐维序列化。"""
    counts: list[int] = []
    allocated = 0
    for i, share in enumerate(shares):
        count = rows // 100 * int(share) if i < len(shares) - 1 else rows - allocated
        counts.append(count)
        allocated += count
    counts[-1] += rows - sum(counts)

    cur.execute(f"SELECT setseed({seed / 2**31:.12f})")
    stats: list[dict[str, Any]] = []
    offset = 0
    for tenant_idx, (tenant, count) in enumerate(zip(tenants, counts)):
        t0 = time.perf_counter()
        center = (tenant_idx + 1) * 4.0
        cur.execute("""
            INSERT INTO rag_documents (id, tenant_id, title, content)
            SELECT g, %s, 'doc-' || %s || '-' || g, 'p05 synthetic content'
            FROM generate_series(%s, %s) g
        """, (tenant, tenant, offset + 1, offset + count))
        cur.execute("""
            INSERT INTO rag_embeddings (id, tenant_id, document_id, chunk_text, embedding_1024)
            SELECT g, %s, g, 'p05 synthetic chunk',
                   (ARRAY[%s + random() * 0.7 - 0.35,
                          random() * 0.7 - 0.35,
                          random() * 0.7 - 0.35] ||
+                    ARRAY(SELECT random() * 2 - 1 FROM generate_series(1, 1021)))::vector(1024)
            FROM generate_series(%s, %s) g
        """.replace('+                    ', '                    '), (tenant, center, offset + 1, offset + count))
        elapsed = time.perf_counter() - t0
        stats.append({"tenant": tenant, "rows": count, "share_pct": count / rows * 100, "load_seconds": round(elapsed, 2)})
        log(f"tenant={tenant} rows={count} ({count / rows * 100:.1f}%) load={elapsed:.1f}s")
        offset += count
    cur.execute("SELECT setval('rag_documents_id_seq', (SELECT max(id) FROM rag_documents))")
    cur.execute("SELECT setval('rag_embeddings_id_seq', (SELECT max(id) FROM rag_embeddings))")
    cur.execute("ANALYZE rag_documents")
    cur.execute("ANALYZE rag_embeddings")
    return stats


def build_index(cur, ef_construction: int, m: int) -> float:
    t0 = time.perf_counter()
    cur.execute(
        "CREATE INDEX idx_rag_embeddings_embedding_1024_hnsw ON rag_embeddings "
        "USING hnsw (embedding_1024 vector_cosine_ops) WITH (m=%s, ef_construction=%s)",
        (m, ef_construction),
    )
    elapsed = time.perf_counter() - t0
    cur.execute("ANALYZE rag_embeddings")
    log(f"HNSW 索引构建完成 {elapsed:.1f}s (m={m}, ef_construction={ef_construction})")
    return round(elapsed, 2)


def build_queries(cur, tenants: list[str], queries: int, seed: int, dim: int) -> dict[str, list]:
    """查询向量 = 目标租户真实行 + 小扰动；ground truth 用精确扫描单独计算。"""
    rng = np.random.default_rng(seed + 991)
    result: dict[str, list[str]] = {}
    for tenant in tenants:
        cur.execute(
            "SELECT embedding_1024::text FROM rag_embeddings WHERE tenant_id=%s "
            "ORDER BY id LIMIT 2000", (tenant,)
        )
        pool = [r[0] for r in cur.fetchall()]
        matrices = np.array([np.fromstring(v.strip("[]"), sep=",") for v in pool], dtype=np.float32)
        base = matrices[np.arange(queries) % len(matrices)]
        perturbed = base + rng.normal(0.0, 0.002, size=base.shape).astype(np.float32)
        perturbed /= np.linalg.norm(perturbed, axis=1, keepdims=True)
        vectors = list(perturbed)
        result[tenant] = vectors
    return result


def exact_top10(conn, vector, tenant: str) -> set[int]:
    """ground truth：专用事务内禁用索引扫描，强制顺序扫描 + 精确 top-N。"""
    with conn.cursor() as cur:
        cur.execute("BEGIN")
        cur.execute("SET LOCAL enable_indexscan = off")
        cur.execute("SET LOCAL enable_bitmapscan = off")
        cur.execute(
            "SELECT id FROM rag_embeddings WHERE tenant_id=%s "
            "ORDER BY embedding_1024 <=> %s LIMIT 10", (tenant, vector),
        )
        rows = [r[0] for r in cur.fetchall()]
        cur.execute("COMMIT")
    return set(rows)


def percentile(values: list[float], p: float) -> float:
    ordered = sorted(values)
    if not ordered:
        return float("nan")
    idx = min(len(ordered) - 1, math.ceil(p / 100 * len(ordered)) - 1)
    return ordered[idx]


def run_mode(conn, queries: dict[str, list], mode: str, ef_search: int, top_k: int) -> dict[str, Any]:
    per_tenant: dict[str, Any] = {}
    with conn.cursor() as cur:
        cur.execute(f"SET hnsw.iterative_scan = {mode}")
        cur.execute(f"SET hnsw.ef_search = {ef_search}")
        for tenant, vectors in queries.items():
            recalls: list[float] = []
            latencies: list[float] = []
            empty = 0
            for vector in vectors:
                truth = exact_top10(conn, vector, tenant)
                t0 = time.perf_counter()
                cur.execute(
                    "SELECT id FROM rag_embeddings WHERE tenant_id=%s "
                    "ORDER BY embedding_1024 <=> %s LIMIT %s", (tenant, vector, top_k),
                )
                rows = [r[0] for r in cur.fetchall()]
                latencies.append((time.perf_counter() - t0) * 1000)
                if not rows:
                    empty += 1
                hits = len(set(rows[:10]) & truth)
                recalls.append(hits / 10.0)
            per_tenant[tenant] = {
                "queries": len(vectors),
                "recall_at_10": round(sum(recalls) / len(recalls), 4),
                "p50_ms": round(percentile(latencies, 50), 3),
                "p95_ms": round(percentile(latencies, 95), 3),
                "max_ms": round(max(latencies), 3),
                "empty_results": empty,
            }
            log(f"{mode} ef_search={ef_search} tenant={tenant} recall@10={per_tenant[tenant]['recall_at_10']} "
                f"p95={per_tenant[tenant]['p95_ms']}ms")
        plan_tenant = next(iter(queries))
        cur.execute(
            "EXPLAIN (ANALYZE, BUFFERS) SELECT id FROM rag_embeddings WHERE tenant_id=%s "
            "ORDER BY embedding_1024 <=> %s LIMIT %s", (plan_tenant, queries[plan_tenant][0], top_k),
        )
        plan = "\n".join(row[0] for row in cur.fetchall())
        cur.execute("RESET hnsw.iterative_scan")
        cur.execute("RESET hnsw.ef_search")
    return {"mode": mode, "ef_search": ef_search, "tenants": per_tenant, "explain_analyze": plan}


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--image", default="pgvector/pgvector:pg16", help="一次性 PostgreSQL/pgvector 镜像")
    p.add_argument("--rows", type=int, default=300_000, help="语料总行数（>=300000）")
    p.add_argument("--shares", default="80,15,5", help="各租户百分比，逗号分隔；必须合计 100")
    p.add_argument("--tenants", default="t_large,t_medium,t_small", help="租户 ID，与 --shares 一一对应")
    p.add_argument("--queries", type=int, default=50, help="每租户查询数")
    p.add_argument("--batch", type=int, default=2000, help="每批 INSERT 行数")
    p.add_argument("--seed", type=int, default=20261128)
    p.add_argument("--ef-search", type=int, default=40, help="固定 ef_search；两模式同值以隔离 iterative_scan 影响")
    p.add_argument("--top-k", type=int, default=10)
    p.add_argument("--m", type=int, default=16)
    p.add_argument("--ef-construction", type=int, default=64)
    p.add_argument("--out", required=True, help="JSON 结果输出路径")
    p.add_argument("--keep-container", action="store_true")
    p.add_argument("--prepare-only", action="store_true", help="只建语料与 HNSW 索引，召回压测交给容器内 SQL 脚本")
    return p.parse_args()


def main() -> int:
    args = parse_args()
    shares = [int(x) for x in args.shares.split(",")]
    tenants = [x.strip() for x in args.tenants.split(",")]
    if len(shares) != len(tenants) or sum(shares) != 100:
        raise SystemExit("--shares 必须与 --tenants 一一对应且合计 100")
    if args.rows < 300_000:
        raise SystemExit("--rows 必须 >= 300000（P0-5 验收门槛）")

    suffix = f"p05-hnsw-{os.getpid()}"
    stack = Stack(container=suffix)
    result: dict[str, Any] = {
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "parameters": vars(args),
        "modes": [],
        "status": "RUNNING",
    }
    try:
        stack.start(args.image)
        conn, versions = connect(stack)
        result["versions"] = versions
        if tuple(int(x) for x in versions["pgvector_version"].split(".")[:2]) < (0, 8):
            raise RuntimeError("pgvector < 0.8，不支持 hnsw.iterative_scan；请指定 0.8+ 镜像")
        create_schema(conn.cursor())
        t0 = time.perf_counter()
        cur = conn.cursor()
        corpus = load_corpus(cur, args.rows, shares, tenants, args.seed, args.batch)
        result["corpus"] = {"tenants": corpus, "total_rows": sum(x["rows"] for x in corpus),
                            "total_load_seconds": round(time.perf_counter() - t0, 2)}
        result["index_build_seconds"] = build_index(conn.cursor(), args.ef_construction, args.m)
        if not args.prepare_only:
            queries = build_queries(conn.cursor(), tenants, args.queries, args.seed, 1024)
            for mode in ("off", "relaxed_order"):
                result["modes"].append(run_mode(conn, queries, mode, args.ef_search, args.top_k))
        else:
            result["modes"] = "已委托 scripts/verify-p05-hnsw-tenant-recall.sql 在容器内实测"
        result["status"] = "PASS"
    except Exception as exc:
        result["status"] = "FAILED"
        result["error"] = f"{type(exc).__name__}: {exc}"
        result["traceback"] = traceback.format_exc()
        log(f"失败：{result['error']}")
        return 1
    finally:
        with open(args.out, "w", encoding="utf-8") as f:
            json.dump(result, f, ensure_ascii=False, indent=2)
        log(f"结果写入 {args.out}（status={result['status']}）")
        stack.stop(args.keep_container)
    return 0


if __name__ == "__main__":
    sys.exit(main())
