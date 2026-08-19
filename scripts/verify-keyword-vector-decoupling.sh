#!/usr/bin/env bash
# Verify the V43 local-keyword/vector derivation boundary.
set -euo pipefail

cd "$(dirname "$0")/.."

MODE="full"
if [[ "${1:-}" == "--webui-only" ]]; then
  MODE="webui-only"
elif [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  cat <<'EOF'
Usage:
  ./scripts/verify-keyword-vector-decoupling.sh
  ./scripts/verify-keyword-vector-decoupling.sh --webui-only

Full mode verifies:
  - no explicit pessimistic locks
  - V43 PostgreSQL migration, local chunks, lifecycle and full-text providers
  - backend compilation
  - WebUI TypeScript, Vitest, production build, alignment and Mock Playwright
  - whitespace cleanliness

WebUI-only mode runs the frontend gates against a temporary Vite preview.

Environment:
  KEYWORD_VECTOR_VERIFY_LOG_DIR
  KEYWORD_VECTOR_VERIFY_RUN_ID
  KEYWORD_VECTOR_PLAYWRIGHT_PORT
  KEYWORD_VECTOR_IT_JDBC_URL
  KEYWORD_VECTOR_IT_USERNAME
  KEYWORD_VECTOR_IT_PASSWORD
  KEYWORD_VECTOR_IT_CLEAN_CONFIRM=YES
  KEYWORD_VECTOR_PG_IMAGE
  MIRROR_BASE_URL

An external JDBC URL is destructive because the integration test runs Flyway
clean before each test. Use a disposable database and set the confirmation
variable explicitly. Without an external URL, the script creates a temporary
database on the local PostgreSQL instance or starts a disposable Docker
container.
EOF
  exit 0
elif [[ $# -gt 0 ]]; then
  echo "Unknown option: $1" >&2
  exit 2
fi

RUN_ID="${KEYWORD_VECTOR_VERIFY_RUN_ID:-$(date +%Y%m%d-%H%M%S)}"
LOG_DIR="${KEYWORD_VECTOR_VERIFY_LOG_DIR:-.verification/keyword-vector-decoupling/${RUN_ID}}"
PLAYWRIGHT_PORT="${KEYWORD_VECTOR_PLAYWRIGHT_PORT:-4177}"
PG_IMAGE="${KEYWORD_VECTOR_PG_IMAGE:-${TESTCONTAINERS_PG_IMAGE:-pgvector/pgvector:pg16}}"
MIRROR_BASE_URL="${MIRROR_BASE_URL:-docker.m.daocloud.io}"

JDBC_URL="${KEYWORD_VECTOR_IT_JDBC_URL:-}"
JDBC_USERNAME="${KEYWORD_VECTOR_IT_USERNAME:-}"
JDBC_PASSWORD="${KEYWORD_VECTOR_IT_PASSWORD:-}"
TEMP_DATABASE=""
POSTGRES_CONTAINER=""
PREVIEW_PID=""
STEP_INDEX=0
PASS_COUNT=0

mkdir -p "$LOG_DIR"
: > "$LOG_DIR/summary.tsv"

slugify() {
  printf '%s' "$1" | tr '[:upper:] ' '[:lower:]-' | tr -cd 'a-z0-9._-'
}

run_step() {
  local name="$1"
  shift
  STEP_INDEX=$((STEP_INDEX + 1))
  local log_path="$LOG_DIR/${STEP_INDEX}-$(slugify "$name").log"

  echo
  echo "=== ${name} ==="
  echo "log: ${log_path}"
  set +e
  "$@" > >(tee "$log_path") 2>&1
  local rc=$?
  set -e
  if [[ "$rc" -ne 0 ]]; then
    printf '%s\tFAIL\t%s\n' "$name" "$log_path" >> "$LOG_DIR/summary.tsv"
    echo "FAIL: ${name} (exit ${rc})" >&2
    return "$rc"
  fi
  PASS_COUNT=$((PASS_COUNT + 1))
  printf '%s\tPASS\t%s\n' "$name" "$log_path" >> "$LOG_DIR/summary.tsv"
  echo "PASS: ${name}"
}

load_local_env() {
  [[ -f .env ]] || return 0
  local explicit_host="${POSTGRES_HOST:-}"
  local explicit_port="${POSTGRES_PORT:-}"
  local explicit_database="${POSTGRES_DATABASE:-}"
  local explicit_username="${POSTGRES_USER:-}"
  local explicit_password="${POSTGRES_PASSWORD:-}"

  set -a
  # shellcheck disable=SC1091
  source .env
  set +a

  [[ -n "$explicit_host" ]] && POSTGRES_HOST="$explicit_host"
  [[ -n "$explicit_port" ]] && POSTGRES_PORT="$explicit_port"
  [[ -n "$explicit_database" ]] && POSTGRES_DATABASE="$explicit_database"
  [[ -n "$explicit_username" ]] && POSTGRES_USER="$explicit_username"
  [[ -n "$explicit_password" ]] && POSTGRES_PASSWORD="$explicit_password"
}

check_prerequisites() {
  local command_name
  for command_name in bash curl git java mvn node npm npx python3 rg; do
    command -v "$command_name" >/dev/null || {
      echo "Missing required command: ${command_name}" >&2
      return 1
    }
  done
}

check_webui_screenshot_policy() {
  rg -n "screenshot:[[:space:]]*['\"]off['\"]" \
    spring-ai-rag-webui/playwright.config.ts >/dev/null
  if rg -n "screenshot|toHaveScreenshot|canvas" \
      spring-ai-rag-webui/e2e/documents.spec.ts \
      spring-ai-rag-webui/e2e/embeddings.spec.ts; then
    echo "The focused WebUI suite contains a screenshot or canvas assertion." >&2
    return 1
  fi
}

webui_gates() {
  (
    cd spring-ai-rag-webui
    npx tsc -b --pretty false
    npm run test:run -- --reporter=dot
    npm run build
    npm run check:alignment
  )
}

start_preview() {
  local preview_log="$LOG_DIR/vite-preview.log"
  (
    cd spring-ai-rag-webui
    exec npx vite preview \
      --host 127.0.0.1 \
      --port "$PLAYWRIGHT_PORT" \
      --strictPort
  ) >"$preview_log" 2>&1 &
  PREVIEW_PID=$!

  local attempt
  for attempt in $(seq 1 30); do
    if ! kill -0 "$PREVIEW_PID" >/dev/null 2>&1; then
      echo "Vite preview exited; see ${preview_log}." >&2
      return 1
    fi
    if curl --fail --silent --show-error --connect-timeout 1 --max-time 2 \
        "http://127.0.0.1:${PLAYWRIGHT_PORT}/webui/" \
        | grep -Fq "<title>spring-ai-rag WebUI</title>"; then
      return 0
    fi
    sleep 1
  done
  echo "Vite preview did not become ready; see ${preview_log}." >&2
  return 1
}

stop_preview() {
  if [[ -n "$PREVIEW_PID" ]]; then
    kill "$PREVIEW_PID" >/dev/null 2>&1 || true
    wait "$PREVIEW_PID" >/dev/null 2>&1 || true
    PREVIEW_PID=""
  fi
}

webui_playwright() {
  check_webui_screenshot_policy
  if ! start_preview; then
    stop_preview
    return 1
  fi
  local rc=0
  (
    cd spring-ai-rag-webui
    BASE_URL="http://127.0.0.1:${PLAYWRIGHT_PORT}" \
      npx playwright test e2e/documents.spec.ts e2e/embeddings.spec.ts
  ) || rc=$?
  stop_preview
  return "$rc"
}

postgres_ready() {
  local host="${POSTGRES_HOST:-127.0.0.1}"
  local port="${POSTGRES_PORT:-5432}"
  local username="${POSTGRES_USER:-postgres}"
  local database="${POSTGRES_DATABASE:-postgres}"
  PGPASSWORD="${POSTGRES_PASSWORD:-}" \
    psql -h "$host" -p "$port" -U "$username" -d "$database" \
      -v ON_ERROR_STOP=1 -Atqc "SELECT 1" 2>/dev/null | grep -qx 1
}

create_local_database() {
  command -v psql >/dev/null
  command -v createdb >/dev/null
  command -v dropdb >/dev/null
  postgres_ready

  local host="${POSTGRES_HOST:-127.0.0.1}"
  local port="${POSTGRES_PORT:-5432}"
  local username="${POSTGRES_USER:-postgres}"
  local password="${POSTGRES_PASSWORD:-}"
  local safe_run_id
  safe_run_id="$(printf '%s' "$RUN_ID" | tr -cd 'a-zA-Z0-9_' | tr '[:upper:]' '[:lower:]')"
  TEMP_DATABASE="spring_ai_rag_keyword_vector_${safe_run_id}_$$"

  PGPASSWORD="$password" createdb \
    -h "$host" -p "$port" -U "$username" "$TEMP_DATABASE"
  JDBC_URL="jdbc:postgresql://${host}:${port}/${TEMP_DATABASE}"
  JDBC_USERNAME="$username"
  JDBC_PASSWORD="$password"
}

resolve_docker_image() {
  if docker image inspect "$PG_IMAGE" >/dev/null 2>&1; then
    printf '%s' "$PG_IMAGE"
    return
  fi
  local mirrored_image="${MIRROR_BASE_URL%/}/${PG_IMAGE}"
  if docker pull "$mirrored_image" >/dev/null 2>&1; then
    printf '%s' "$mirrored_image"
    return
  fi
  docker pull "$PG_IMAGE" >/dev/null
  printf '%s' "$PG_IMAGE"
}

start_docker_database() {
  command -v docker >/dev/null || {
    echo "No disposable PostgreSQL database is available." >&2
    return 1
  }
  local image
  image="$(resolve_docker_image)"
  local username="${POSTGRES_USER:-postgres}"
  local password="${POSTGRES_PASSWORD:-postgres}"
  POSTGRES_CONTAINER="spring-ai-rag-keyword-vector-${RUN_ID}-$$"

  docker run -d --rm \
    --name "$POSTGRES_CONTAINER" \
    -e POSTGRES_DB=spring_ai_rag_keyword_vector_test \
    -e POSTGRES_USER="$username" \
    -e POSTGRES_PASSWORD="$password" \
    -p 127.0.0.1::5432 \
    "$image" >"$LOG_DIR/postgres-container-id"

  local attempt port
  for attempt in $(seq 1 45); do
    if docker exec "$POSTGRES_CONTAINER" pg_isready \
        -U "$username" -d spring_ai_rag_keyword_vector_test \
        >/dev/null 2>&1; then
      port="$(docker port "$POSTGRES_CONTAINER" 5432/tcp | sed 's/.*://')"
      JDBC_URL="jdbc:postgresql://127.0.0.1:${port}/spring_ai_rag_keyword_vector_test"
      JDBC_USERNAME="$username"
      JDBC_PASSWORD="$password"
      return 0
    fi
    sleep 1
  done
  echo "Disposable PostgreSQL container did not become ready." >&2
  return 1
}

prepare_database() {
  load_local_env
  if [[ -n "$JDBC_URL" ]]; then
    [[ "${KEYWORD_VECTOR_IT_CLEAN_CONFIRM:-}" == "YES" ]] || {
      echo "External JDBC requires KEYWORD_VECTOR_IT_CLEAN_CONFIRM=YES." >&2
      return 1
    }
    [[ -n "$JDBC_USERNAME" ]] || {
      echo "KEYWORD_VECTOR_IT_USERNAME is required with an external JDBC URL." >&2
      return 1
    }
    return 0
  fi
  if command -v psql >/dev/null && postgres_ready; then
    create_local_database
  else
    start_docker_database
  fi
}

backend_tests() {
  DOCUMENT_LIFECYCLE_IT_JDBC_URL="$JDBC_URL" \
    DOCUMENT_LIFECYCLE_IT_USERNAME="$JDBC_USERNAME" \
    DOCUMENT_LIFECYCLE_IT_PASSWORD="$JDBC_PASSWORD" \
    DOCUMENT_LIFECYCLE_IT_CLEAN_CONFIRM=YES \
    mvn -pl spring-ai-rag-core -am \
      -Ddocument-lifecycle.it.enabled=true \
      -Dsurefire.failIfNoSpecifiedTests=false \
      -Dtest=DocumentLifecyclePostgresIntegrationTest,PgEnglishFtsProviderTest,PgJiebaFulltextProviderTest,PgTrgmFulltextProviderTest \
      test
}

backend_compile() {
  mvn clean compile test-compile
}

cleanup_database() {
  if [[ -n "$TEMP_DATABASE" ]]; then
    PGPASSWORD="${POSTGRES_PASSWORD:-}" dropdb \
      -h "${POSTGRES_HOST:-127.0.0.1}" \
      -p "${POSTGRES_PORT:-5432}" \
      -U "${POSTGRES_USER:-postgres}" \
      --if-exists "$TEMP_DATABASE" >/dev/null 2>&1 || true
  fi
  if [[ -n "$POSTGRES_CONTAINER" ]]; then
    docker stop "$POSTGRES_CONTAINER" >/dev/null 2>&1 || true
  fi
}

write_summary() {
  {
    echo "# Keyword/vector decoupling verification"
    echo
    echo "- Run: \`${RUN_ID}\`"
    echo "- Mode: \`${MODE}\`"
    echo "- Generated: \`$(date '+%Y-%m-%d %H:%M:%S %z')\`"
    echo "- Branch: \`$(git branch --show-current)\`"
    echo "- Commit: \`$(git rev-parse --short HEAD)\`"
    echo "- Passed steps: **${PASS_COUNT}**"
    echo
    echo "| Step | Status | Evidence |"
    echo "|------|--------|----------|"
    while IFS=$'\t' read -r name status evidence; do
      echo "| ${name} | ${status} | \`${evidence}\` |"
    done < "$LOG_DIR/summary.tsv"
  } > "$LOG_DIR/summary.md"
}

cleanup() {
  stop_preview
  cleanup_database
  write_summary
}

trap cleanup EXIT

if [[ "$MODE" == "webui-only" ]]; then
  run_step "WebUI prerequisites" check_prerequisites
  run_step "WebUI build and unit gates" webui_gates
  run_step "WebUI screenshot policy and Mock Playwright" webui_playwright
  echo
  echo "Keyword/vector WebUI verification passed: ${PASS_COUNT} steps"
  echo "Summary: ${LOG_DIR}/summary.md"
  exit 0
fi

run_step "Prerequisites" check_prerequisites
run_step "No explicit pessimistic locks" ./scripts/verify-no-pessimistic-locks.sh
run_step "Disposable PostgreSQL preparation" prepare_database
run_step "V43 local keyword/vector integration tests" backend_tests
run_step "Maven clean compile test-compile" backend_compile
run_step "WebUI build and unit gates" webui_gates
run_step "WebUI screenshot policy and Mock Playwright" webui_playwright
run_step "Git whitespace check" git diff --check

echo
echo "Keyword/vector decoupling verification passed: ${PASS_COUNT} steps"
echo "Summary: ${LOG_DIR}/summary.md"
