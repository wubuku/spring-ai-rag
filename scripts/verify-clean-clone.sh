#!/usr/bin/env bash
# 干净环境冷启动验收。
#
# 在一个临时目录做干净 clone，按 docs/getting-started 的步骤：
#   .env -> createdb -> mvn install -> 启动 demo -> health -> documents -> embed -> search -> chat
# 全程使用仓库自带 mock provider，不需要真实 API Key；只验证链路，不验证回答质量。
#
# 用法：
#   ./scripts/verify-clean-clone.sh                 # 从当前仓库 HEAD 克隆
#   ./scripts/verify-clean-clone.sh --keep          # 保留临时目录/日志供排查
#   ./scripts/verify-clean-clone.sh --source <url>  # 从远端或本地路径克隆
#   ./scripts/verify-clean-clone.sh --port 18084    # 指定 demo 端口
#
# 依赖：git、Maven、JDK 21+、PostgreSQL（createdb/psql）、curl、node、python3。
# 退出码 0 表示全流程通过；任何一步失败都会打印日志位置并以非 0 退出。

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

SOURCE="$PROJECT_DIR"
APP_PORT="${CLEAN_CLONE_PORT:-18084}"
MOCK_PORT="${CLEAN_CLONE_MOCK_PORT:-18085}"
KEEP=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --keep) KEEP=1; shift ;;
    --source) SOURCE="$2"; shift 2 ;;
    --port) APP_PORT="$2"; shift 2 ;;
    --mock-port) MOCK_PORT="$2"; shift 2 ;;
    -h|--help)
      sed -n '2,18p' "$0"
      exit 0
      ;;
    *) echo "Unknown option: $1" >&2; exit 1 ;;
  esac
done

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'
pass() { echo -e "${GREEN}[PASS]${NC} $1"; }
info() { echo -e "${YELLOW}[INFO]${NC} $1"; }
fail() { echo -e "${RED}[FAIL]${NC} $1"; }

WORK_DIR="$(mktemp -d /tmp/rag-clean-verify.XXXXXX)"
CLONE_DIR="$WORK_DIR/spring-ai-rag"
LOG_DIR="$WORK_DIR/logs"
mkdir -p "$LOG_DIR"
DB_NAME="rag_clean_verify_$(date +%s)_$RANDOM"
APP_PID=""
MOCK_PID=""

cleanup() {
  local rc=$?
  if [[ -n "$APP_PID" ]] && kill -0 "$APP_PID" 2>/dev/null; then
    kill "$APP_PID" 2>/dev/null || true
    wait "$APP_PID" 2>/dev/null || true
  fi
  # spring-boot:run forks a JVM; terminate whatever still listens on the port.
  lsof -ti:"$APP_PORT" -sTCP:LISTEN 2>/dev/null | xargs -r kill 2>/dev/null || true
  if [[ -n "$MOCK_PID" ]] && kill -0 "$MOCK_PID" 2>/dev/null; then
    kill "$MOCK_PID" 2>/dev/null || true
    wait "$MOCK_PID" 2>/dev/null || true
  fi
  if [[ "${POSTGRES_DROP:-1}" == "1" ]]; then
    drop_database "$DB_NAME" || true
  fi
  if [[ "$KEEP" == "1" ]]; then
    info "保留下载目录：$WORK_DIR"
  else
    rm -rf "$WORK_DIR"
  fi
  exit "$rc"
}
trap cleanup EXIT

# 从本工作区 .env 读取数据库连接信息，但不要 wholesale source：
# 工作区 .env 可能导出 SPRING_DATASOURCE_URL，它会覆盖 demo 的 POSTGRES_* 配置，
# 让“临时库验收”悄悄连到日常开发库。
read_env() {
  local key=$1
  [[ -f "$PROJECT_DIR/.env" ]] || return 0
  grep -E "^${key}=" "$PROJECT_DIR/.env" | tail -1 | cut -d= -f2- \
    | sed -e 's/^"//' -e 's/"$//' -e "s/^'//" -e "s/'$//"
}
unset SPRING_DATASOURCE_URL SPRING_DATASOURCE_USERNAME SPRING_DATASOURCE_PASSWORD \
  SPRING_DATASOURCE_DRIVER_CLASS_NAME 2>/dev/null || true

PG_HOST="$(read_env POSTGRES_HOST)"; PG_HOST="${PG_HOST:-localhost}"
PG_PORT="$(read_env POSTGRES_PORT)"; PG_PORT="${PG_PORT:-5432}"
PG_USER="$(read_env POSTGRES_USER)"; PG_USER="${PG_USER:-postgres}"
PG_PASSWORD="$(read_env POSTGRES_PASSWORD)"
PGADMIN_OPTS=(-h "$PG_HOST" -p "$PG_PORT" -U "$PG_USER" -d postgres)
if [[ -n "$PG_PASSWORD" ]]; then
  export PGPASSWORD="$PG_PASSWORD"
fi

drop_database() {
  local db=$1
  psql "${PGADMIN_OPTS[@]}" -v ON_ERROR_STOP=1 -c \
    "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname='$db' AND pid <> pg_backend_pid()" \
    >/dev/null 2>&1 || true
  psql "${PGADMIN_OPTS[@]}" -v ON_ERROR_STOP=1 -c "DROP DATABASE IF EXISTS \"$db\"" >/dev/null 2>&1 || true
}

info "临时目录：$WORK_DIR"
info "克隆来源：$SOURCE"
git clone --quiet --depth 1 "$SOURCE" "$CLONE_DIR"
cd "$CLONE_DIR"
pass "干净 clone 完成（HEAD $(git rev-parse --short HEAD)）"

# 文档第 2 步：数据库。V1 迁移会自行创建 vector/pg_trgm 扩展。
info "创建临时数据库 $DB_NAME"
psql "${PGADMIN_OPTS[@]}" -v ON_ERROR_STOP=1 -c "CREATE DATABASE \"$DB_NAME\"" >/dev/null
pass "数据库创建完成"

# 文档第 3 步：.env（mock provider 等价替代真实凭据）。
cat > "$CLONE_DIR/.env" <<ENV
POSTGRES_HOST=$PG_HOST
POSTGRES_PORT=$PG_PORT
POSTGRES_DATABASE=$DB_NAME
POSTGRES_USER=$PG_USER
POSTGRES_PASSWORD=$PG_PASSWORD
LLM_PROVIDER=openai
SPRING_AI_OPENAI_API_KEY=mock-key
SPRING_AI_OPENAI_BASE_URL=http://127.0.0.1:$MOCK_PORT
SPRING_AI_OPENAI_CHAT_OPTIONS_MODEL=mock-chat
RAG_EMBEDDING_API_KEY=mock-key
RAG_EMBEDDING_BASE_URL=http://127.0.0.1:$MOCK_PORT
# 与 V25 迁移播种的默认 Embedding Profile 精确匹配，否则 Active Collection
# 会因为“引用了一个没有运行时配置的 profile”而拒绝写入。
RAG_EMBEDDING_MODEL=BAAI/bge-m3
RAG_EMBEDDING_DIMENSIONS=1024
RAG_EMBEDDING_PROFILE_KEY=siliconflow-bge-m3-1024-v1
RAG_EMBEDDING_PROVIDER=siliconflow
RAG_RETRIEVAL_MIN_SCORE=0
RAG_SECURITY_ENABLED=false
RAG_ROOT_API_KEY=clean-clone-verify-root-key-0123456789
ENV
pass ".env 已按 .env.example 变量名生成（mock provider）"

# mock provider 只依赖 node，不依赖仓库构建产物。
info "启动 mock provider（端口 ${MOCK_PORT}）"
"$CLONE_DIR/scripts/run-mock-llm.sh" --port "$MOCK_PORT" >"$LOG_DIR/mock.log" 2>&1 &
MOCK_PID=$!
for _ in $(seq 1 30); do
  if curl -sf "http://127.0.0.1:$MOCK_PORT/health" >/dev/null 2>&1; then break; fi
  sleep 1
done
curl -sf "http://127.0.0.1:$MOCK_PORT/health" >/dev/null || {
  fail "mock provider 未就绪，日志：$LOG_DIR/mock.log"
  exit 1
}
pass "mock provider 就绪"

# 文档第 4 步：先 install（demo 依赖本地仓库中的 starter）。
info "mvn -B -DskipTests install"
set -a
# shellcheck disable=SC1091
source "$CLONE_DIR/.env"
set +a
unset SPRING_DATASOURCE_URL SPRING_DATASOURCE_USERNAME SPRING_DATASOURCE_PASSWORD \
  SPRING_DATASOURCE_DRIVER_CLASS_NAME 2>/dev/null || true
(
  cd "$CLONE_DIR"
  mvn -B -DskipTests install
) >"$LOG_DIR/maven-install.log" 2>&1 || {
  fail "mvn install 失败，日志：$LOG_DIR/maven-install.log"
  tail -40 "$LOG_DIR/maven-install.log"
  exit 1
}
pass "mvn install 完成"

# 文档第 5 步：启动 demo。
info "启动 demo-basic-rag（端口 ${APP_PORT}）"
(
  cd "$CLONE_DIR/demos/demo-basic-rag"
  set -a; source "$CLONE_DIR/.env"; set +a
  unset SPRING_DATASOURCE_URL SPRING_DATASOURCE_USERNAME SPRING_DATASOURCE_PASSWORD \
    SPRING_DATASOURCE_DRIVER_CLASS_NAME 2>/dev/null || true
  exec mvn -B spring-boot:run -Dspring-boot.run.arguments="--server.port=$APP_PORT"
) >"$LOG_DIR/demo.log" 2>&1 &
APP_PID=$!

BASE_URL="http://127.0.0.1:$APP_PORT"
READY=0
for _ in $(seq 1 120); do
  if curl -sf "$BASE_URL/actuator/health" >/dev/null 2>&1; then READY=1; break; fi
  if ! kill -0 "$APP_PID" 2>/dev/null; then break; fi
  sleep 1
done
if [[ "$READY" != "1" ]]; then
  fail "demo 未在 120s 内就绪，日志：$LOG_DIR/demo.log"
  tail -60 "$LOG_DIR/demo.log"
  exit 1
fi
curl -sf "$BASE_URL/actuator/health" | python3 -c 'import sys,json; d=json.load(sys.stdin); assert d.get("status")=="UP", d; print("health="+d["status"])'
pass "健康检查通过"

# 文档第 6 步：collection -> documents -> embed -> search -> chat。
COLLECTION_KEY="clean-verify"
curl -sf -X POST "$BASE_URL/api/v1/rag/collections" \
  -H "Content-Type: application/json" \
  -d "{\"collectionKey\":\"$COLLECTION_KEY\",\"name\":\"Clean Clone Verify\",\"embeddingModel\":\"BAAI/bge-m3\",\"dimensions\":1024}" \
  > "$LOG_DIR/collection.json"
pass "创建知识库 collectionKey=$COLLECTION_KEY"

DOC_JSON="$LOG_DIR/document.json"
curl -sf -X POST "$BASE_URL/api/v1/rag/documents" \
  -H "Content-Type: application/json" \
  -d "{\"title\":\"Clean Clone Verify\",\"content\":\"Spring AI RAG clean clone verification document with unique marker clean-clone-marker-42.\",\"source\":\"manual\",\"collectionKey\":\"$COLLECTION_KEY\"}" \
  > "$DOC_JSON"
DOC_ID="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["id"])' "$DOC_JSON")"
[[ -n "$DOC_ID" && "$DOC_ID" != "None" ]]
# 确认数据真的落在临时库，而不是被环境里的 SPRING_DATASOURCE_URL 劫持到日常库。
IN_TEMP_DB="$(psql -h "$PG_HOST" -p "$PG_PORT" -U "$PG_USER" -d "$DB_NAME" -tAc \
  "SELECT count(*) FROM rag_documents WHERE id=$DOC_ID")"
[[ "$IN_TEMP_DB" == "1" ]] || {
  fail "文档 id=$DOC_ID 不在临时库 ${DB_NAME}（疑似连接到了其他数据库）"
  exit 1
}
pass "创建文档 id=${DOC_ID}（已确认落在临时库）"

curl -sf -X POST "$BASE_URL/api/v1/rag/documents/$DOC_ID/embed" >"$LOG_DIR/embed.json"
python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); assert d.get("embeddings",0) >= 1, d' "$LOG_DIR/embed.json"
pass "嵌入完成：$(python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); print("chunks=%s embeddings=%s" % (d.get("chunks"), d.get("embeddings")))' "$LOG_DIR/embed.json")"

curl -sf "$BASE_URL/api/v1/rag/search?query=clean-clone-marker-42&limit=3&collectionKeys=$COLLECTION_KEY" >"$LOG_DIR/search.json"
python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); results=d.get("results", d if isinstance(d,list) else []); assert len(results) >= 1, d' "$LOG_DIR/search.json"
pass "检索返回 $(python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); print(len(d.get("results", d if isinstance(d,list) else [])))' "$LOG_DIR/search.json") 条结果"

curl -sf -X POST "$BASE_URL/api/v1/rag/chat/ask" \
  -H "Content-Type: application/json" \
  -d "{\"message\":\"What is Spring AI?\",\"collectionKeys\":[\"$COLLECTION_KEY\"]}" >"$LOG_DIR/chat.json"
python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); assert d.get("answer"), d' "$LOG_DIR/chat.json"
pass "RAG 问答返回 answer"

pass "干净环境冷启动验收通过（mock provider，未验证真实模型回答质量）"
