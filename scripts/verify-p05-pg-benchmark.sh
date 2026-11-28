#!/usr/bin/env bash
# P0-5 一键演练：真实 Docker PostgreSQL 16 + pgvector 0.8 一次性容器，
# 30 万行非均匀语料 Recall@10 / P95 + 百万行在线 CREATE INDEX CONCURRENTLY。
#
# 关键点：所有长连接查询通过 docker exec 在容器内部执行（psql -f -），
# 规避部分 OrbStack 环境下宿主 → bridge IP 长连接超时的问题。
#
# 用法：
#   ./scripts/verify-p05-pg-benchmark.sh --out /tmp/p05-evidence
#   RESULT_DIR=/tmp/p05-evidence ./scripts/verify-p05-pg-benchmark.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT_DIR="${RESULT_DIR:-${TMPDIR:-/tmp}/p05-evidence}"
PG_IMAGE="${P05_PG_IMAGE:-pgvector/pgvector:pg16}"
ROWS="${P05_ROWS:-300000}"
SHARES="${P05_SHARES:-80,15,5}"
TENANTS="${P05_TENANTS:-t_large,t_medium,t_small}"
QUERIES="${P05_QUERIES:-50}"
CONCURRENT_ROWS="${P05_CONCURRENT_ROWS:-1000000}"

mkdir -p "$OUT_DIR"
echo "[p05] 结果目录: $OUT_DIR"

# ---- 阶段 1：300k 倾斜语料 + HNSW 召回压测（Python 建语料/索引；SQL 在容器内跑）----
python3 "$SCRIPT_DIR/verify-p05-hnsw-tenant-recall.py" \
  --image "$PG_IMAGE" --rows "$ROWS" --shares "$SHARES" --tenants "$TENANTS" \
  --queries "$QUERIES" --keep-container --prepare-only \
  --out "$OUT_DIR/phase1-python.json"

# 找到刚创建且仍存活的 300k 容器（按最近创建时间）。
HNSW_CONTAINER=$(docker ps --filter name=p05-hnsw- --format '{{.Names}}' | head -1)
if [[ -z "$HNSW_CONTAINER" ]]; then
  echo "[p05] 未找到 300k 压测容器" >&2
  exit 1
fi
echo "[p05] 300k 容器: $HNSW_CONTAINER"

docker exec -i "$HNSW_CONTAINER" psql -U p05 -d p05 -f - \
  < "$SCRIPT_DIR/verify-p05-hnsw-tenant-recall.sql" \
  > "$OUT_DIR/phase1-sql.txt" 2>&1
echo "[p05] 阶段 1 结果: $OUT_DIR/phase1-sql.txt"

# 强制全局 HNSW 复现实验（临时删 tenant B-tree，只影响一次性容器）。
docker exec -i "$HNSW_CONTAINER" psql -U p05 -d p05 -f - \
  < "$SCRIPT_DIR/verify-p05-hnsw-tenant-recall-forced.sql" \
  > "$OUT_DIR/phase1-forced-hnsw.txt" 2>&1
echo "[p05] 强制 HNSW 结果: $OUT_DIR/phase1-forced-hnsw.txt"
docker rm -f "$HNSW_CONTAINER" >/dev/null

# ---- 阶段 2：百万行在线 CONCURRENTLY 演练 ----
python3 "$SCRIPT_DIR/verify-p05-online-concurrent-index.py" \
  --image "$PG_IMAGE" --rows "$CONCURRENT_ROWS" --batch 100000 --keep-container \
  --out "$OUT_DIR/phase2-python.json"
CONCURRENT_CONTAINER=$(docker ps --filter name=p05-concurrent- --format '{{.Names}}' | head -1)
if [[ -z "$CONCURRENT_CONTAINER" ]]; then
  echo "[p05] 未找到百万行演练容器" >&2
  exit 1
fi
echo "[p05] 百万行容器: $CONCURRENT_CONTAINER"
docker exec "$CONCURRENT_CONTAINER" psql -U p05 -d p05 -c \
  "SELECT c.relname, i.indisvalid, i.indisready, i.indislive, pg_size_pretty(pg_relation_size(c.oid)) AS size FROM pg_index i JOIN pg_class c ON c.oid=i.indexrelid WHERE c.relname LIKE 'idx_rag_embeddings%';" \
  > "$OUT_DIR/phase2-index-state.txt" 2>&1
docker exec "$CONCURRENT_CONTAINER" psql -U p05 -d p05 -c \
  "SELECT count(*) AS rows, count(*) FILTER (WHERE chunk_index > 0) AS writes_during_build FROM rag_embeddings;" \
  >> "$OUT_DIR/phase2-index-state.txt" 2>&1
echo "[p05] 阶段 2 结果: $OUT_DIR/phase2-index-state.txt"
docker rm -f "$CONCURRENT_CONTAINER" >/dev/null

echo "[p05] 全部完成；证据目录: $OUT_DIR"
