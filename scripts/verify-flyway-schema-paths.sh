#!/usr/bin/env bash
# Flyway 升级路径验收。
#
# 验证两件事：
#   1. 空库直接迁移到最新（V1..latest）与「先迁到较早版本、再增量升级到最新」
#      得到相同的 schema（用 pg_dump --schema-only 归一化后逐字比对）。
#   2. 较早版本写入的数据在增量升级后仍在；最新库再次执行 migrate（重跑）不破坏数据。
#
# 用法：
#   ./scripts/verify-flyway-schema-paths.sh                # 默认较早版本 V30
#   ./scripts/verify-flyway-schema-paths.sh --early 40     # 指定较早版本
#   ./scripts/verify-flyway-schema-paths.sh --keep         # 保留日志与 schema dump
#
# 依赖：Maven、JDK 21+、PostgreSQL（psql/pg_dump）。
# 脚本只创建并删除自己的临时数据库（rag_flyway_*），不会碰日常开发库。

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
MIGRATION_DIR="$PROJECT_DIR/spring-ai-rag-core/src/main/resources/db/migration"

EARLY_VERSION="${FLYWAY_EARLY_VERSION:-30}"
KEEP=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --early) EARLY_VERSION="$2"; shift 2 ;;
    --keep) KEEP=1; shift ;;
    -h|--help) sed -n '2,14p' "$0"; exit 0 ;;
    *) echo "Unknown option: $1" >&2; exit 1 ;;
  esac
done

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'
pass() { echo -e "${GREEN}[PASS]${NC} $1"; }
info() { echo -e "${YELLOW}[INFO]${NC} $1"; }
fail() { echo -e "${RED}[FAIL]${NC} $1"; }

WORK_DIR="$(mktemp -d /tmp/rag-flyway-verify.XXXXXX)"
STAMP="$(date +%s)_$RANDOM"
FULL_DB="rag_flyway_full_$STAMP"
INC_DB="rag_flyway_inc_$STAMP"

if [[ -f "$PROJECT_DIR/.env" ]]; then
  set -a
  # shellcheck disable=SC1091
  source "$PROJECT_DIR/.env"
  set +a
fi

PG_HOST="${POSTGRES_HOST:-localhost}"
PG_PORT="${POSTGRES_PORT:-5432}"
PG_USER="${POSTGRES_USER:-postgres}"
PG_PASSWORD="${POSTGRES_PASSWORD:-}"
PGADMIN_OPTS=(-h "$PG_HOST" -p "$PG_PORT" -U "$PG_USER" -d postgres)
if [[ -n "$PG_PASSWORD" ]]; then
  export PGPASSWORD="$PG_PASSWORD"
fi

psql_db() {
  local db=$1; shift
  psql -h "$PG_HOST" -p "$PG_PORT" -U "$PG_USER" -d "$db" -v ON_ERROR_STOP=1 "$@"
}

drop_database() {
  local db=$1
  psql "${PGADMIN_OPTS[@]}" -c \
    "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname='$db' AND pid <> pg_backend_pid()" \
    >/dev/null 2>&1 || true
  psql "${PGADMIN_OPTS[@]}" -c "DROP DATABASE IF EXISTS \"$db\"" >/dev/null 2>&1 || true
}

cleanup() {
  local rc=$?
  drop_database "$FULL_DB" || true
  drop_database "$INC_DB" || true
  if [[ "$KEEP" == "1" ]]; then
    info "保留日志目录：$WORK_DIR"
  else
    rm -rf "$WORK_DIR"
  fi
  exit "$rc"
}
trap cleanup EXIT

EARLY_FILE="$(ls "$MIGRATION_DIR"/V"${EARLY_VERSION}"__*.sql 2>/dev/null | head -1 || true)"
[[ -n "$EARLY_FILE" ]] || {
  fail "找不到 V${EARLY_VERSION} 迁移文件（${MIGRATION_DIR}）"
  exit 1
}

info "工作目录：$WORK_DIR"
info "较早版本：V${EARLY_VERSION}；最新版本：$(ls "$MIGRATION_DIR" | sed -n 's/^V\([0-9]*\)__.*/\1/p' | sort -n | tail -1)"

# 运行 Flyway 需要 spring-ai-rag-core 的运行时 classpath（含 flyway-core 与 PostgreSQL 驱动）。
info "解析 core 模块运行时 classpath"
mvn -q -pl spring-ai-rag-core dependency:build-classpath \
  -Dmdep.outputFile="$WORK_DIR/classpath.txt" -Dmdep.includeScope=runtime
CP="$(cat "$WORK_DIR/classpath.txt")"
[[ -n "$CP" ]] || { fail "classpath 为空"; exit 1; }

run_migrate() {
  local db=$1 target=$2
  java -cp "$CP" "$PROJECT_DIR/scripts/test-support/FlywayRunner.java" \
    "jdbc:postgresql://$PG_HOST:$PG_PORT/$db" "$PG_USER" "$PG_PASSWORD" \
    "filesystem:$MIGRATION_DIR" "$target"
}

# ---------- 路径 A：空库 -> 最新 ----------
info "创建两条路径的临时数据库"
psql "${PGADMIN_OPTS[@]}" -c "CREATE DATABASE \"$FULL_DB\"" >/dev/null
psql "${PGADMIN_OPTS[@]}" -c "CREATE DATABASE \"$INC_DB\"" >/dev/null

info "路径 A：$FULL_DB 从空库迁移到 latest"
run_migrate "$FULL_DB" latest
pass "空库 -> 最新完成"

# ---------- 路径 B：空库 -> 较早版本 -> 最新 ----------
info "路径 B：$INC_DB 先迁移到 V$EARLY_VERSION"
run_migrate "$INC_DB" "$EARLY_VERSION"

# 在较早版本落一条标记数据，用来证明增量升级不会丢数据。
psql_db "$INC_DB" -c "INSERT INTO rag_documents (title, content, content_hash) VALUES ('flyway-early-marker', 'early-marker-content', 'flyway-early-marker-hash')" >/dev/null
EARLY_COUNT="$(psql_db "$INC_DB" -tAc "SELECT count(*) FROM rag_documents WHERE content_hash='flyway-early-marker-hash'")"
[[ "$EARLY_COUNT" == "1" ]] || { fail "较早版本标记数据未写入（count=${EARLY_COUNT}）"; exit 1; }

info "路径 B：$INC_DB 从 V$EARLY_VERSION 增量升级到 latest"
run_migrate "$INC_DB" latest
AFTER_COUNT="$(psql_db "$INC_DB" -tAc "SELECT count(*) FROM rag_documents WHERE content_hash='flyway-early-marker-hash'")"
[[ "$AFTER_COUNT" == "1" ]] || { fail "增量升级后标记数据丢失（count=${AFTER_COUNT}）"; exit 1; }
pass "增量升级保留较早版本写入的数据"

# ---------- schema 比对 ----------
normalize_dump() {
  # 忽略：注释、会话级 SET、pg_dump 的 \restrict 安全行、空行。
  grep -v -E '^(--|SET |SELECT pg_catalog\.set_config|\\|$)' "$1" || true
}

pg_dump -h "$PG_HOST" -p "$PG_PORT" -U "$PG_USER" \
  --schema-only --no-owner --no-privileges --no-comments \
  -d "$FULL_DB" > "$WORK_DIR/full.sql"
pg_dump -h "$PG_HOST" -p "$PG_PORT" -U "$PG_USER" \
  --schema-only --no-owner --no-privileges --no-comments \
  -d "$INC_DB" > "$WORK_DIR/incremental.sql"

normalize_dump "$WORK_DIR/full.sql" > "$WORK_DIR/full.norm.sql"
normalize_dump "$WORK_DIR/incremental.sql" > "$WORK_DIR/incremental.norm.sql"

if ! diff -u "$WORK_DIR/full.norm.sql" "$WORK_DIR/incremental.norm.sql" > "$WORK_DIR/schema.diff"; then
  fail "两条路径的 schema 不一致，差异见 $WORK_DIR/schema.diff"
  head -60 "$WORK_DIR/schema.diff"
  exit 1
fi
pass "两条路径 schema 完全一致（$(wc -l < "$WORK_DIR/full.norm.sql") 行归一化 DDL）"

FULL_HISTORY="$(psql_db "$FULL_DB" -tAc "SELECT count(*) FROM flyway_schema_history WHERE success")"
INC_HISTORY="$(psql_db "$INC_DB" -tAc "SELECT count(*) FROM flyway_schema_history WHERE success")"
[[ "$FULL_HISTORY" == "$INC_HISTORY" ]] || {
  fail "迁移历史条数不一致（full=$FULL_HISTORY incremental=${INC_HISTORY}）"
  exit 1
}
pass "两条路径迁移历史条数一致（${FULL_HISTORY}）"

# ---------- 重跑不破坏数据 ----------
psql_db "$FULL_DB" -c "INSERT INTO rag_documents (title, content, content_hash) VALUES ('flyway-rerun-marker', 'rerun-marker-content', 'flyway-rerun-marker-hash')" >/dev/null
BEFORE_RERUN="$(psql_db "$FULL_DB" -tAc "SELECT count(*) FROM rag_documents WHERE content_hash='flyway-rerun-marker-hash'")"
run_migrate "$FULL_DB" latest >/dev/null
AFTER_RERUN="$(psql_db "$FULL_DB" -tAc "SELECT count(*) FROM rag_documents WHERE content_hash='flyway-rerun-marker-hash'")"
[[ "$BEFORE_RERUN" == "1" && "$AFTER_RERUN" == "1" ]] || {
  fail "重跑 migrate 后数据异常（before=$BEFORE_RERUN after=${AFTER_RERUN}）"
  exit 1
}
pass "最新库重跑 migrate 未破坏数据"

pass "Flyway 升级路径验收通过"
