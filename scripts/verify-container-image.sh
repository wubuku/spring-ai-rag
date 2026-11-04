#!/usr/bin/env bash
# 容器交付烟测：全新构建镜像 → 按官方 compose 默认配置起栈 → 校验安全基线。
#
# 覆盖：
#   1. 用当前源码构建镜像（默认 --no-cache，避免复用旧层/旧镜像），记录耗时与体积；
#   2. 运行时非 root、无内置密钥、无 .env、基础镜像非 latest、内置 HEALTHCHECK；
#   3. compose 默认配置（postgresql,prod + RAG_SECURITY_ENABLED=true）下：
#      健康检查 UP、未认证 401、带 root key 的 search 成功；
#   4. 容器内 Flyway 顺序/最新版本、WebUI 静态 content-type；
#   5. 只读根文件系统、/tmp 临时盘、no-new-privileges、cap_drop=ALL 生效；
#   6. docker stop 在 stop_grace_period 内优雅退出。
#
# 用法：
#   ./scripts/verify-container-image.sh                # 全新构建并烟测
#   ./scripts/verify-container-image.sh --skip-build   # 复用已构建镜像（CI 缓存构建后）
#   ./scripts/verify-container-image.sh --keep         # 保留临时日志目录
#   ./scripts/verify-container-image.sh --image tag --project name --app-port 18091
#
# 环境限制：不推送 registry、不部署集群；这两项需要额外凭据/环境。
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"

IMAGE="${IMAGE_TAG:-spring-ai-rag:container-verify}"
SKIP_BUILD=0
KEEP=0
PROJECT="${COMPOSE_PROJECT:-rag-container-smoke}"
APP_PORT="${APP_PORT:-18091}"
DB_PORT="${DB_PORT:-15434}"
MOCK_PORT="${MOCK_PORT:-18086}"
ROOT_KEY="${ROOT_KEY:-verify-container-root-key-0123456789abcdef}"
WORK_DIR="$(mktemp -d "/tmp/rag-container-smoke.XXXXXX")"
LOG_DIR="$WORK_DIR/logs"
mkdir -p "$LOG_DIR"
MOCK_PID=""
FAILED=0

usage() {
  sed -n '2,26p' "$0" | sed 's/^# \{0,1\}//'
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --skip-build) SKIP_BUILD=1; shift ;;
    --keep) KEEP=1; shift ;;
    --image) IMAGE="$2"; shift 2 ;;
    --project) PROJECT="$2"; shift 2 ;;
    --app-port) APP_PORT="$2"; shift 2 ;;
    --db-port) DB_PORT="$2"; shift 2 ;;
    --mock-port) MOCK_PORT="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
  esac
done

check() {
  local name="$1" condition="$2" detail="${3:-}"
  if [[ "$condition" == "0" ]]; then
    echo "[PASS] $name"
  else
    FAILED=$((FAILED + 1))
    echo "[FAIL] $name :: $detail"
  fi
}

cleanup() {
  # 应用日志先落盘再拆栈：健康检查失败时 compose 的进度输出不含容器 stdout，
  # 没有这份日志就只能看到 "health: starting" 而无法定位启动异常。
  if [[ -f "$WORK_DIR/override.yml" ]]; then
    docker compose -p "$PROJECT" \
      -f docker/docker-compose.yml \
      -f "$WORK_DIR/override.yml" logs --no-color --tail=200 \
      > "$LOG_DIR/app.log" 2>&1 || true
  fi
  docker compose -p "$PROJECT" \
    -f docker/docker-compose.yml \
    -f "$WORK_DIR/override.yml" down -v >/dev/null 2>&1 || true
  if [[ -n "$MOCK_PID" ]]; then
    kill "$MOCK_PID" 2>/dev/null || true
    wait "$MOCK_PID" 2>/dev/null || true
  fi
  if [[ "$KEEP" == "1" ]]; then
    echo "[INFO] 日志保留在 $WORK_DIR"
  else
    rm -rf "$WORK_DIR"
  fi
}
trap cleanup EXIT

echo "== 容器交付烟测 =="
echo "镜像: $IMAGE  项目: $PROJECT  端口: app=$APP_PORT db=$DB_PORT mock=$MOCK_PORT"

# ---------- 1. 构建 ----------
if [[ "$SKIP_BUILD" != "1" ]]; then
  echo "[INFO] 全新构建（--no-cache），日志: $LOG_DIR/docker-build.log"
  BUILD_START=$(date +%s)
  if ! docker build --no-cache \
      --file docker/Dockerfile \
      --build-arg MAVEN_IMAGE="${MAVEN_IMAGE:-maven:3.9-eclipse-temurin-21}" \
      --build-arg RUNTIME_IMAGE="${RUNTIME_IMAGE:-eclipse-temurin:21-jre-alpine}" \
      --tag "$IMAGE" \
      . > "$LOG_DIR/docker-build.log" 2>&1; then
    echo "[FAIL] 镜像构建失败，末尾日志："
    tail -40 "$LOG_DIR/docker-build.log"
    exit 1
  fi
  BUILD_SECONDS=$(( $(date +%s) - BUILD_START ))
  echo "[INFO] 构建耗时: ${BUILD_SECONDS}s（日志 $LOG_DIR/docker-build.log）"
else
  echo "[INFO] --skip-build：复用已构建镜像"
fi

if ! docker image inspect "$IMAGE" >/dev/null 2>&1; then
  echo "[FAIL] 镜像不存在: $IMAGE"
  exit 1
fi

IMAGE_SIZE_BYTES=$(docker image inspect "$IMAGE" --format '{{.Size}}')
IMAGE_ID=$(docker image inspect "$IMAGE" --format '{{.Id}}')
IMAGE_ARCH=$(docker image inspect "$IMAGE" --format '{{.Architecture}}')
echo "[INFO] 镜像: $IMAGE_ID arch=$IMAGE_ARCH size=$((IMAGE_SIZE_BYTES / 1024 / 1024))MB"

# ---------- 2. 镜像静态基线 ----------
IMAGE_USER=$(docker image inspect "$IMAGE" --format '{{.Config.User}}')
check "镜像声明非 root 运行用户" \
  "$([[ "$IMAGE_USER" != "root" && -n "$IMAGE_USER" ]] && echo 0 || echo 1)" \
  "user=$IMAGE_USER"

IMAGE_ENV=$(docker image inspect "$IMAGE" --format '{{range .Config.Env}}{{println .}}{{end}}')
if grep -Eiq '(^|=)([A-Za-z0-9_]*)(API_KEY|SECRET|TOKEN|PASSWORD)=' <<< "$IMAGE_ENV"; then
  SECRET_ENV_PROBLEM=1
else
  SECRET_ENV_PROBLEM=0
fi
check "镜像 Config.Env 无内置密钥/口令" "$SECRET_ENV_PROBLEM" \
  "env=$(tr '\n' ' ' <<< "$IMAGE_ENV" | head -c 200)"

if docker run --rm --entrypoint sh "$IMAGE" \
    -c 'test ! -e /app/.env && test ! -e /app/.env.local && test ! -d /build' \
    >/dev/null 2>&1; then
  ENV_LEAK=0
else
  ENV_LEAK=1
fi
check "镜像内无 .env / 构建目录残留" "$ENV_LEAK"

BASE_ARGS=$(grep -E '^ARG (MAVEN_IMAGE|RUNTIME_IMAGE)=' docker/Dockerfile || true)
if grep -q ':latest' <<< "$BASE_ARGS" || ! grep -q ':' <<< "$BASE_ARGS"; then
  BASE_PINNED=1
else
  BASE_PINNED=0
fi
check "基础镜像使用显式版本标签（非 latest）" "$BASE_PINNED" "$BASE_ARGS"

IMAGE_HEALTHCHECK=$(docker image inspect "$IMAGE" --format '{{json .Config.Healthcheck}}')
check "镜像内置 HEALTHCHECK" \
  "$([[ "$IMAGE_HEALTHCHECK" != "null" && -n "$IMAGE_HEALTHCHECK" ]] && echo 0 || echo 1)" \
  "$IMAGE_HEALTHCHECK"

# ---------- 3. 启动 mock provider 与官方 compose 栈 ----------
./scripts/run-mock-llm.sh --port "$MOCK_PORT" --delay 0 \
  > "$LOG_DIR/mock.log" 2>&1 &
MOCK_PID=$!

MOCK_READY=1
for _ in $(seq 1 60); do
  if curl -fsS "http://127.0.0.1:$MOCK_PORT/health" >/dev/null 2>&1; then
    MOCK_READY=0
    break
  fi
  sleep 1
done
check "mock provider 就绪" "$MOCK_READY" "$LOG_DIR/mock.log"

cat > "$WORK_DIR/override.yml" <<EOF
services:
  postgres:
    container_name: ${PROJECT}-db
    ports: !override
      - "${DB_PORT}:5432"
  rag-service:
    container_name: ${PROJECT}-app
    image: ${IMAGE}
    ports: !override
      - "${APP_PORT}:8081"
    extra_hosts:
      - "host.docker.internal:host-gateway"
    environment:
      SPRING_AI_OPENAI_API_KEY: mock-key
      SPRING_AI_OPENAI_BASE_URL: http://host.docker.internal:${MOCK_PORT}
      SPRING_AI_OPENAI_CHAT_OPTIONS_MODEL: mock-chat
      RAG_EMBEDDING_API_KEY: mock-key
      RAG_EMBEDDING_BASE_URL: http://host.docker.internal:${MOCK_PORT}
      RAG_EMBEDDING_MODEL: BAAI/bge-m3
      RAG_EMBEDDING_DIMENSIONS: "1024"
      RAG_ROOT_API_KEY: "${ROOT_KEY}"
      # mock provider 跑在宿主机上，容器只能经 host-gateway 用明文 http 访问它。
      # 启动校验默认拒绝远程明文 http；这里是脚本自己起的可信内网端点，
      # 显式打开逃生阀（RAG_ALLOW_INSECURE_UPSTREAM），而不是放宽生产默认值。
      RAG_ALLOW_INSECURE_UPSTREAM: "true"
EOF

if ! docker compose -p "$PROJECT" \
    -f docker/docker-compose.yml \
    -f "$WORK_DIR/override.yml" \
    up -d --no-build > "$LOG_DIR/compose-up.log" 2>&1; then
  echo "[FAIL] compose 启动失败："
  tail -40 "$LOG_DIR/compose-up.log"
  exit 1
fi

APP_READY=1
HEALTH_BODY=""
for _ in $(seq 1 90); do
  if HEALTH_BODY=$(curl -fsS "http://127.0.0.1:$APP_PORT/actuator/health" 2>/dev/null); then
    if grep -q '"status":"UP"' <<< "$HEALTH_BODY"; then
      APP_READY=0
      break
    fi
  fi
  sleep 2
done
check "compose 应用健康检查 UP" "$APP_READY" "见 $LOG_DIR/compose-up.log"

RUNTIME_UID=$(docker exec "${PROJECT}-app" id -u 2>/dev/null || echo "")
check "运行时实际非 root（uid != 0）" \
  "$([[ -n "$RUNTIME_UID" && "$RUNTIME_UID" != "0" ]] && echo 0 || echo 1)" "uid=$RUNTIME_UID"

SECURITY_ENABLED=$(grep -o '"securityEnabled":[^,}]*' <<< "$HEALTH_BODY" | head -1)
check "健康检查标明认证已启用" \
  "$([[ "$SECURITY_ENABLED" == *"true"* ]] && echo 0 || echo 1)" "$SECURITY_ENABLED"

UNAUTH_CODE=$(curl -s -o "$LOG_DIR/search-unauth.json" -w '%{http_code}' \
  "http://127.0.0.1:$APP_PORT/api/v1/rag/search?query=container-smoke")
check "未认证 search 返回 401" \
  "$([[ "$UNAUTH_CODE" == "401" ]] && echo 0 || echo 1)" "code=$UNAUTH_CODE"

AUTH_CODE=$(curl -s -o "$LOG_DIR/search-auth.json" -w '%{http_code}' \
  -H "Authorization: Bearer ${ROOT_KEY}" \
  "http://127.0.0.1:$APP_PORT/api/v1/rag/search?query=container-smoke")
AUTH_BODY=$(cat "$LOG_DIR/search-auth.json" 2>/dev/null || true)
check "带 root key 的 search 成功" \
  "$([[ "$AUTH_CODE" == "200" && "$AUTH_BODY" == *'"results"'* ]] && echo 0 || echo 1)" \
  "code=$AUTH_CODE body=${AUTH_BODY:0:200}"

READONLY=$(docker inspect "${PROJECT}-app" --format '{{.HostConfig.ReadonlyRootfs}}')
check "根文件系统只读" "$([[ "$READONLY" == "true" ]] && echo 0 || echo 1)" "$READONLY"

# ---------- 静态资源：容器内 WebUI 不能退化成 HTML/JSON ----------
TMPFS=$(docker inspect "${PROJECT}-app" --format '{{json .HostConfig.Tmpfs}}')
check "/tmp 临时盘已挂载" "$([[ "$TMPFS" == *"/tmp"* ]] && echo 0 || echo 1)" "$TMPFS"

LATEST_MIGRATION_VERSION=$(ls spring-ai-rag-core/src/main/resources/db/migration/V*__*.sql \
  | sed -E 's/.*V([0-9]+)__.*/\1/' | sort -n | tail -1)

FLYWAY_FAILED=$(docker exec "${PROJECT}-db" bash -c \
  'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atc "SELECT count(*) FROM flyway_schema_history WHERE NOT success"' \
  2>/dev/null | tr -d '[:space:]')
FLYWAY_COUNT=$(docker exec "${PROJECT}-db" bash -c \
  'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atc "SELECT count(*) FROM flyway_schema_history"' \
  2>/dev/null | tr -d '[:space:]')
FLYWAY_MAX_RANK=$(docker exec "${PROJECT}-db" bash -c \
  'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atc "SELECT max(installed_rank) FROM flyway_schema_history"' \
  2>/dev/null | tr -d '[:space:]')
FLYWAY_MAX_VERSION=$(docker exec "${PROJECT}-db" bash -c \
  'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atc "SELECT max(version::int) FROM flyway_schema_history"' \
  2>/dev/null | tr -d '[:space:]')
check "容器内 Flyway 无失败迁移" \
  "$([[ "$FLYWAY_FAILED" == "0" ]] && echo 0 || echo 1)" "failed=$FLYWAY_FAILED"
check "容器内 Flyway installed_rank 连续" \
  "$([[ "$FLYWAY_COUNT" == "$FLYWAY_MAX_RANK" ]] && echo 0 || echo 1)" \
  "count=$FLYWAY_COUNT max_rank=$FLYWAY_MAX_RANK"
check "容器内 Flyway 已应用最新迁移 V${LATEST_MIGRATION_VERSION}" \
  "$([[ "$FLYWAY_MAX_VERSION" == "$LATEST_MIGRATION_VERSION" ]] && echo 0 || echo 1)" \
  "db=$FLYWAY_MAX_VERSION source=$LATEST_MIGRATION_VERSION"

WEBUI_CODE=$(curl -s -o "$LOG_DIR/webui-index.html" -w '%{http_code}' \
  "http://127.0.0.1:$APP_PORT/webui/")
WEBUI_BODY=$(cat "$LOG_DIR/webui-index.html" 2>/dev/null || true)
check "容器 /webui/ 返回真实入口页" \
  "$([[ "$WEBUI_CODE" == "200" && "$WEBUI_BODY" == *"<title>spring-ai-rag WebUI</title>"* ]] \
    && echo 0 || echo 1)" "code=$WEBUI_CODE body=${WEBUI_BODY:0:80}"

curl -s -D "$LOG_DIR/theme-init.headers" -o "$LOG_DIR/theme-init.js" \
  "http://127.0.0.1:$APP_PORT/webui/theme-init.js"
THEME_TYPE=$(grep -i '^content-type:' "$LOG_DIR/theme-init.headers" \
  | head -1 | tr -d '\r' | cut -d' ' -f2-)
THEME_BODY=$(cat "$LOG_DIR/theme-init.js" 2>/dev/null || true)
check "容器 theme-init.js 以 JS 返回且不是 HTML" \
  "$([[ "$THEME_TYPE" == *javascript* && "$THEME_BODY" != *"<!DOCTYPE html>"* ]] \
    && echo 0 || echo 1)" "type=$THEME_TYPE body=${THEME_BODY:0:60}"

curl -s -D "$LOG_DIR/favicon.headers" -o "$LOG_DIR/favicon.svg" \
  "http://127.0.0.1:$APP_PORT/webui/favicon.svg"
FAVICON_TYPE=$(grep -i '^content-type:' "$LOG_DIR/favicon.headers" \
  | head -1 | tr -d '\r' | cut -d' ' -f2-)
FAVICON_BODY=$(cat "$LOG_DIR/favicon.svg" 2>/dev/null || true)
check "容器 favicon.svg 以 SVG 返回" \
  "$([[ "$FAVICON_TYPE" == *"image/svg+xml"* && "$FAVICON_BODY" == "<svg"* ]] \
    && echo 0 || echo 1)" "type=$FAVICON_TYPE body=${FAVICON_BODY:0:40}"

SECURITY_OPT=$(docker inspect "${PROJECT}-app" --format '{{json .HostConfig.SecurityOpt}}')
check "no-new-privileges 生效" \
  "$([[ "$SECURITY_OPT" == *"no-new-privileges"* ]] && echo 0 || echo 1)" "$SECURITY_OPT"

CAP_DROP=$(docker inspect "${PROJECT}-app" --format '{{json .HostConfig.CapDrop}}')
check "cap_drop=ALL 生效" "$([[ "$CAP_DROP" == *"ALL"* ]] && echo 0 || echo 1)" "$CAP_DROP"

HEALTH_STATUS=""
for _ in $(seq 1 60); do
  HEALTH_STATUS=$(docker inspect "${PROJECT}-app" --format '{{.State.Health.Status}}' 2>/dev/null || true)
  if [[ "$HEALTH_STATUS" == "healthy" ]]; then
    break
  fi
  sleep 2
done
check "容器 healthcheck 状态 healthy" \
  "$([[ "$HEALTH_STATUS" == "healthy" ]] && echo 0 || echo 1)" "$HEALTH_STATUS"

# ---------- 优雅停机：compose 的 stop_grace_period 必须覆盖 graceful shutdown ----------
STOP_START=$(date +%s)
docker stop -t 35 "${PROJECT}-app" > "$LOG_DIR/docker-stop.log" 2>&1
STOP_SECONDS=$(( $(date +%s) - STOP_START ))
STOP_EXIT=$(docker inspect "${PROJECT}-app" --format '{{.State.ExitCode}}' 2>/dev/null || echo "")
STOP_LOG=$(docker logs "${PROJECT}-app" 2>&1 | tail -60 || true)
check "容器 STOP 后优雅退出（exit 0/143，未超时强杀）" \
  "$([[ "$STOP_EXIT" == "0" || "$STOP_EXIT" == "143" ]] && echo 0 || echo 1)" \
  "exit=$STOP_EXIT elapsed=${STOP_SECONDS}s"
check "容器日志确认进入优雅停机" \
  "$([[ "$STOP_LOG" == *"Commencing graceful shutdown"* \
      || "$STOP_LOG" == *"Graceful shutdown complete"* ]] && echo 0 || echo 1)" \
  "tail=${STOP_LOG: -160}"

echo
if [[ "$FAILED" -gt 0 ]]; then
  echo "$FAILED 项失败"
  echo "[INFO] 日志目录: $WORK_DIR"
  exit 1
fi

echo "全部容器交付断言通过"
echo "[INFO] 构建: $([[ "$SKIP_BUILD" == "1" ]] && echo '复用镜像' || echo "${BUILD_SECONDS}s")"
echo "[INFO] 体积: $((IMAGE_SIZE_BYTES / 1024 / 1024))MB  架构: $IMAGE_ARCH  digest: $IMAGE_ID"
echo "[INFO] 未覆盖: registry 推送与集群部署（需要额外凭据/环境）"
