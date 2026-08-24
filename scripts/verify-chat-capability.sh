#!/usr/bin/env bash
# One-click verification for the Spring AI chat capability redesign.
#
# The default run verifies the local implementation without calling external
# LLM or embedding providers. PostgreSQL/Testcontainers is attempted by
# default because V32 lease/history/memory behavior needs a real database.
#
# Usage:
#   ./scripts/verify-chat-capability.sh
#   ./scripts/verify-chat-capability.sh --skip-postgres
#   ./scripts/verify-chat-capability.sh --skip-startup
#   ./scripts/verify-chat-capability.sh --with-real-llm
#
# Logs are written outside Maven target/ so `mvn clean` does not remove them.
set -uo pipefail

cd "$(dirname "$0")/.."

RUN_ID="${CHAT_VERIFY_RUN_ID:-$(date +%Y%m%d-%H%M%S)}"
LOG_DIR="${CHAT_VERIFY_LOG_DIR:-.verification/chat-capability/${RUN_ID}}"
PLAYWRIGHT_PORT="${CHAT_PLAYWRIGHT_PORT:-4198}"
STARTUP_PORT="${CHAT_STARTUP_PORT:-4210}"
REAL_BACKEND_PORT="${CHAT_REAL_BACKEND_PORT:-18083}"
REAL_FRONTEND_PORT="${CHAT_REAL_FRONTEND_PORT:-15175}"
REAL_ENV_FILE="${CHAT_REAL_ENV_FILE:-${DEV_ENV_FILE:-.env}}"
TESTCONTAINERS_API_VERSION="${TESTCONTAINERS_API_VERSION:-1.40}"
TESTCONTAINERS_RYUK_DISABLED="${TESTCONTAINERS_RYUK_DISABLED:-true}"
TESTCONTAINERS_PG_IMAGE="${TESTCONTAINERS_PG_IMAGE:-pgvector/pgvector:pg16}"
RUN_POSTGRES=1
RUN_STARTUP=1
RUN_PLAYWRIGHT=1
RUN_REAL_LLM=0

usage() {
  cat <<'EOF'
Usage: ./scripts/verify-chat-capability.sh [options]

Runs:
  Chat mode/tool/memory/history/SSE/export focused tests
  PostgreSQL V32 session/lease/atomicity integration tests
  mvn clean compile test-compile and full mvn test
  Current reactor install and independent domain-extension/SQL demo tests
  Isolated PostgreSQL + dummy-model Spring Boot startup smoke
  WebUI Vitest, TypeScript, production build, and Mock Playwright
  project documentation and whitespace gates

Options:
      --skip-postgres    Explicitly skip the PostgreSQL/Testcontainers gate
      --skip-startup     Explicitly skip the isolated backend startup gate
      --skip-playwright  Explicitly skip the Mock Playwright gate
      --with-real-llm    Run isolated real WebUI and provider acceptance
  -h, --help             Show this help

Environment:
  CHAT_VERIFY_LOG_DIR          Verification output directory
  CHAT_VERIFY_RUN_ID           Stable run identifier
  CHAT_PLAYWRIGHT_PORT         Strict Vite preview port (default: 4198)
  CHAT_STARTUP_PORT            Strict backend smoke port (default: 4210)
  CHAT_REAL_BACKEND_PORT       Isolated real Chat backend port (default: 18083)
  CHAT_REAL_FRONTEND_PORT      Isolated real WebUI port (default: 15175)
  CHAT_REAL_ENV_FILE           Environment file for real provider/dev stack
  TESTCONTAINERS_API_VERSION   Docker API version (default: 1.40)
  TESTCONTAINERS_RYUK_DISABLED Disable Ryuk when local registry access blocks it
  TESTCONTAINERS_PG_IMAGE      PostgreSQL/pgvector image
  BASE_URL                     Existing backend URL for --with-real-llm
  REAL_LLM_BASE_URL            Existing real-LLM backend URL override

Explicit skips are recorded in summary.md. A run with skips is not a full
release gate even when the script exits successfully.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --skip-postgres)
      RUN_POSTGRES=0
      shift
      ;;
    --skip-startup)
      RUN_STARTUP=0
      shift
      ;;
    --skip-playwright)
      RUN_PLAYWRIGHT=0
      shift
      ;;
    --with-real-llm)
      RUN_REAL_LLM=1
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

mkdir -p "$LOG_DIR"
: > "$LOG_DIR/summary.tsv"
PASS_COUNT=0
FAIL_COUNT=0
SKIP_COUNT=0
STEP_INDEX=0
PREVIEW_PID=""
STARTUP_PID=""
STARTUP_CONTAINER_ID=""
REAL_DEV_PID=""
REAL_ENV_OVERLAY=""
REAL_DATABASE=""
REAL_STACK_READY=0
REAL_ROOT_API_KEY="${RAG_ROOT_API_KEY:-}"

slugify() {
  printf '%s' "$1" \
    | tr '[:upper:] ' '[:lower:]-' \
    | tr -cd 'a-z0-9._-'
}

record() {
  local name="$1" status="$2" exit_code="$3" evidence="$4"
  printf '%s|%s|%s|%s\n' "$name" "$status" "$exit_code" "$evidence" \
    >> "$LOG_DIR/summary.tsv"
  case "$status" in
    PASS) PASS_COUNT=$((PASS_COUNT + 1)) ;;
    FAIL) FAIL_COUNT=$((FAIL_COUNT + 1)) ;;
    SKIP) SKIP_COUNT=$((SKIP_COUNT + 1)) ;;
  esac
}

run_step() {
  local name="$1"
  shift
  STEP_INDEX=$((STEP_INDEX + 1))
  local log_path="$LOG_DIR/${STEP_INDEX}-$(slugify "$name").log"

  echo
  echo "=== ${name} ==="
  echo "log: ${log_path}"
  "$@" > >(tee "$log_path") 2>&1
  local rc=$?
  if [[ "$rc" -eq 0 ]]; then
    echo "PASS: ${name}"
    record "$name" PASS 0 "$log_path"
  else
    echo "FAIL: ${name} (exit ${rc})" >&2
    record "$name" FAIL "$rc" "$log_path"
  fi
  return 0
}

skip_step() {
  local name="$1" reason="$2"
  echo "SKIP: ${name} (${reason})"
  record "$name" SKIP 0 "$reason"
}

require_commands() {
  local command_name
  for command_name in bash curl git java mvn node npm npx rg; do
    command -v "$command_name" >/dev/null || {
      echo "Missing required command: ${command_name}" >&2
      return 1
    }
  done
}

docker_environment() {
  command -v docker >/dev/null || {
    echo "Docker CLI is required for PostgreSQL/Testcontainers." >&2
    return 1
  }
  docker version
}

chat_focused_tests() {
  mvn -pl spring-ai-rag-core -am \
    -Dtest='ChatCommandMapperTest,ChatExecutionServiceTest,ModeAwareChatClientFactoryTest,ProjectDocumentJoinerTest,BoundedMultiQueryExpanderTest,RagChatPropertiesValidationTest,RetrievalTraceCollectorTest,KnowledgeSearchToolTest,RagChatServiceTest,RagChatHistoryRepositoryTest,RagChatControllerTest,SseStreamE2ETest,ChatExportServiceTest,RagControllerIntegrationTest' \
    -Dsurefire.failIfNoSpecifiedTests=false \
    test
}

chat_postgres_tests() {
  local chat_rc=0
  local next_high_value_rc=0
  local idempotency_rc=0
  local bounded_expansion_rc=0

  DOCKER_API_VERSION="${DOCKER_API_VERSION:-${TESTCONTAINERS_API_VERSION}}" \
    TESTCONTAINERS_RYUK_DISABLED="$TESTCONTAINERS_RYUK_DISABLED" \
    mvn -pl spring-ai-rag-core -am \
      "-Dapi.version=${TESTCONTAINERS_API_VERSION}" \
      -Dchat.it.enabled=true \
      "-Dtestcontainers.pg.image=${TESTCONTAINERS_PG_IMAGE}" \
      -Dtest=ChatSessionPostgresIntegrationTest \
      -Dsurefire.failIfNoSpecifiedTests=false \
      test || chat_rc=$?

  DOCKER_API_VERSION="${DOCKER_API_VERSION:-${TESTCONTAINERS_API_VERSION}}" \
    TESTCONTAINERS_RYUK_DISABLED="$TESTCONTAINERS_RYUK_DISABLED" \
    mvn -pl spring-ai-rag-core -am \
      "-Dapi.version=${TESTCONTAINERS_API_VERSION}" \
      -Dnext-high-value.it.enabled=true \
      "-Dtestcontainers.pg.image=${TESTCONTAINERS_PG_IMAGE}" \
      -Dtest=NextHighValueFeaturesPostgresIntegrationTest \
      -Dsurefire.failIfNoSpecifiedTests=false \
      test || next_high_value_rc=$?

  DOCKER_API_VERSION="${DOCKER_API_VERSION:-${TESTCONTAINERS_API_VERSION}}" \
    TESTCONTAINERS_RYUK_DISABLED="$TESTCONTAINERS_RYUK_DISABLED" \
    mvn -pl spring-ai-rag-core -am \
      "-Dapi.version=${TESTCONTAINERS_API_VERSION}" \
      -Dchat.idempotency.it.enabled=true \
      "-Dtestcontainers.pg.image=${TESTCONTAINERS_PG_IMAGE}" \
      -Dtest=ChatTurnOperationPostgresIntegrationTest \
      -Dsurefire.failIfNoSpecifiedTests=false \
      test || idempotency_rc=$?

  DOCKER_API_VERSION="${DOCKER_API_VERSION:-${TESTCONTAINERS_API_VERSION}}" \
    TESTCONTAINERS_RYUK_DISABLED="$TESTCONTAINERS_RYUK_DISABLED" \
    mvn -pl spring-ai-rag-core -am \
      "-Dapi.version=${TESTCONTAINERS_API_VERSION}" \
      -Dhybrid-rrf.it.enabled=true \
      "-Dtestcontainers.pg.image=${TESTCONTAINERS_PG_IMAGE}" \
      -Dtest=HybridRetrieverRrfPostgresIntegrationTest \
      -Dsurefire.failIfNoSpecifiedTests=false \
      test || bounded_expansion_rc=$?

  if [[ "$chat_rc" -ne 0 || "$next_high_value_rc" -ne 0
        || "$idempotency_rc" -ne 0 || "$bounded_expansion_rc" -ne 0 ]]; then
    echo "PostgreSQL integration matrix failed (chat=${chat_rc}, next-high-value=${next_high_value_rc}, idempotency=${idempotency_rc}, bounded-expansion=${bounded_expansion_rc})." >&2
    return 1
  fi
}

maven_compile() {
  mvn clean compile test-compile
}

maven_all_tests() {
  mvn test
}

maven_install_current_reactor() {
  mvn -pl spring-ai-rag-starter -am -DskipTests install
}

domain_extension_demo_tests() {
  mvn -f demos/demo-domain-extension/pom.xml test
}

sql_tool_demo_tests() {
  DOCKER_API_VERSION="${DOCKER_API_VERSION:-${TESTCONTAINERS_API_VERSION}}" \
    TESTCONTAINERS_RYUK_DISABLED="$TESTCONTAINERS_RYUK_DISABLED" \
    mvn -f demos/demo-tool-calling-sql/pom.xml \
      "-Dtestcontainers.pg.image=${TESTCONTAINERS_PG_IMAGE}" \
      -Dsurefire.failIfNoSpecifiedTests=false \
      test
}

cleanup_startup_smoke() {
  if [[ -n "$STARTUP_PID" ]] && kill -0 "$STARTUP_PID" >/dev/null 2>&1; then
    kill "$STARTUP_PID" >/dev/null 2>&1 || true
    wait "$STARTUP_PID" >/dev/null 2>&1 || true
  fi
  STARTUP_PID=""
  if [[ -n "$STARTUP_CONTAINER_ID" ]]; then
    docker rm -f "$STARTUP_CONTAINER_ID" >/dev/null 2>&1 || true
  fi
  STARTUP_CONTAINER_ID=""
}

backend_startup_smoke() {
  local backend_log="$LOG_DIR/backend-startup.log"
  local classpath_file="$LOG_DIR/backend-runtime-classpath.txt"
  local db_port=""
  local health=""

  if command -v lsof >/dev/null 2>&1 \
      && lsof -tiTCP:"$STARTUP_PORT" -sTCP:LISTEN >/dev/null 2>&1; then
    echo "Backend startup port ${STARTUP_PORT} is already in use." >&2
    return 1
  fi

  cleanup_startup_smoke
  STARTUP_CONTAINER_ID="$(
    docker run -d --rm \
      -e POSTGRES_PASSWORD=postgres \
      -e POSTGRES_DB=spring_ai_rag_chat_startup \
      -p 127.0.0.1::5432 \
      "$TESTCONTAINERS_PG_IMAGE"
  )" || return 1

  local attempt
  for attempt in $(seq 1 45); do
    if docker exec "$STARTUP_CONTAINER_ID" \
        pg_isready -U postgres -d spring_ai_rag_chat_startup >/dev/null 2>&1; then
      break
    fi
    if [[ "$attempt" == "45" ]]; then
      echo "Temporary PostgreSQL did not become ready." >&2
      docker logs "$STARTUP_CONTAINER_ID" >&2 || true
      cleanup_startup_smoke
      return 1
    fi
    sleep 1
  done

  db_port="$(
    docker port "$STARTUP_CONTAINER_ID" 5432/tcp \
      | awk -F: 'NR == 1 { print $NF }'
  )"
  if [[ -z "$db_port" ]]; then
    echo "Could not resolve temporary PostgreSQL host port." >&2
    cleanup_startup_smoke
    return 1
  fi

  mvn -pl spring-ai-rag-core -am -q dependency:build-classpath \
    "-Dmdep.outputFile=${PWD}/${classpath_file}" \
    -DincludeScope=runtime || {
      cleanup_startup_smoke
      return 1
    }

  local runtime_classpath
  runtime_classpath="spring-ai-rag-core/target/classes:"
  runtime_classpath+="spring-ai-rag-api/target/classes:"
  runtime_classpath+="spring-ai-rag-documents/target/classes:"
  runtime_classpath+="spring-ai-rag-starter/target/classes:"
  runtime_classpath+="$(cat "$classpath_file")"

  env \
    SPRING_PROFILES_ACTIVE=postgresql \
    SERVER_PORT="$STARTUP_PORT" \
    SPRING_DATASOURCE_URL="jdbc:postgresql://127.0.0.1:${db_port}/spring_ai_rag_chat_startup" \
    SPRING_DATASOURCE_USERNAME=postgres \
    SPRING_DATASOURCE_PASSWORD=postgres \
    RAG_SECURITY_ENABLED=false \
    APP_LLM_PROVIDER=openai \
    SPRING_AI_OPENAI_API_KEY=dummy \
    SPRING_AI_OPENAI_BASE_URL=http://127.0.0.1:9 \
    SPRING_AI_OPENAI_CHAT_OPTIONS_MODEL=dummy-chat \
    RAG_EMBEDDING_API_KEY=dummy \
    RAG_EMBEDDING_BASE_URL=http://127.0.0.1:9 \
    RAG_EMBEDDING_MODEL=dummy-embedding \
    RAG_EMBEDDING_DIMENSIONS=1024 \
    RAG_EMBEDDING_PROFILE_KEY=verification-dummy-embedding-1024-v1 \
    RAG_EMBEDDING_PROVIDER=verification \
    RAG_EMBEDDING_MODEL_REVISION=v1 \
    java -cp "$runtime_classpath" com.springairag.core.SpringAiRagApplication \
    >"$backend_log" 2>&1 &
  STARTUP_PID=$!

  for attempt in $(seq 1 90); do
    if ! kill -0 "$STARTUP_PID" >/dev/null 2>&1; then
      echo "Backend exited before health readiness; see ${backend_log}" >&2
      tail -80 "$backend_log" >&2 || true
      cleanup_startup_smoke
      return 1
    fi
    health="$(
      curl --fail --silent --show-error --connect-timeout 1 --max-time 2 \
        "http://127.0.0.1:${STARTUP_PORT}/actuator/health" 2>/dev/null || true
    )"
    if [[ "$health" == *'"status":"UP"'* ]]; then
      echo "$health"
      cleanup_startup_smoke
      return 0
    fi
    sleep 1
  done

  echo "Backend did not become healthy; last health payload: ${health}" >&2
  tail -80 "$backend_log" >&2 || true
  cleanup_startup_smoke
  return 1
}

load_real_environment() {
  [[ -f "$REAL_ENV_FILE" ]] || {
    echo "Real LLM environment file does not exist: ${REAL_ENV_FILE}" >&2
    return 1
  }
  bash -n "$REAL_ENV_FILE"

  local caller_root_key="$REAL_ROOT_API_KEY"
  set +u
  set -a
  # shellcheck disable=SC1090
  source "$REAL_ENV_FILE"
  set +a
  set -u
  REAL_ROOT_API_KEY="${caller_root_key:-${RAG_ROOT_API_KEY:-}}"
  [[ -n "$REAL_ROOT_API_KEY" ]] || {
    echo "RAG_ROOT_API_KEY is required for real WebUI acceptance." >&2
    return 1
  }
}

prepare_real_database() {
  command -v createdb >/dev/null || {
    echo "createdb is required for the isolated real Chat database." >&2
    return 1
  }
  command -v dropdb >/dev/null || {
    echo "dropdb is required for the isolated real Chat database." >&2
    return 1
  }
  command -v psql >/dev/null || {
    echo "psql is required for the isolated real Chat database." >&2
    return 1
  }

  local host="${POSTGRES_HOST:-127.0.0.1}"
  local port="${POSTGRES_PORT:-5432}"
  local username="${POSTGRES_USER:-postgres}"
  local password="${POSTGRES_PASSWORD:-}"
  local admin_database="${POSTGRES_ADMIN_DATABASE:-postgres}"
  local safe_run_id
  safe_run_id="$(printf '%s' "$RUN_ID" | tr -cd 'a-zA-Z0-9_' | tr '[:upper:]' '[:lower:]')"
  REAL_DATABASE="spring_ai_rag_chat_real_${safe_run_id}_$$"

  PGPASSWORD="$password" \
    psql -h "$host" -p "$port" -U "$username" -d "$admin_database" \
      -v ON_ERROR_STOP=1 -Atqc 'SELECT 1' >/dev/null
  PGPASSWORD="$password" \
    createdb -h "$host" -p "$port" -U "$username" "$REAL_DATABASE"
  echo "Created disposable real Chat database: ${REAL_DATABASE}"
}

create_real_environment_overlay() {
  local host="${POSTGRES_HOST:-127.0.0.1}"
  local port="${POSTGRES_PORT:-5432}"
  local username="${POSTGRES_USER:-postgres}"
  local password="${POSTGRES_PASSWORD:-}"
  local jdbc_url="jdbc:postgresql://${host}:${port}/${REAL_DATABASE}"

  REAL_ENV_OVERLAY="$(mktemp "${TMPDIR:-/tmp}/spring-ai-rag-chat-real-env.XXXXXX")"
  chmod 600 "$REAL_ENV_OVERLAY"
  {
    printf 'source %q\n' "$REAL_ENV_FILE"
    printf 'POSTGRES_HOST=%q\n' "$host"
    printf 'POSTGRES_PORT=%q\n' "$port"
    printf 'POSTGRES_DATABASE=%q\n' "$REAL_DATABASE"
    printf 'POSTGRES_USER=%q\n' "$username"
    printf 'POSTGRES_PASSWORD=%q\n' "$password"
    printf 'SPRING_DATASOURCE_URL=%q\n' "$jdbc_url"
    printf 'SPRING_DATASOURCE_USERNAME=%q\n' "$username"
    printf 'SPRING_DATASOURCE_PASSWORD=%q\n' "$password"
    printf 'SPRING_FLYWAY_URL=%q\n' "$jdbc_url"
    printf 'SPRING_FLYWAY_USER=%q\n' "$username"
    printf 'SPRING_FLYWAY_PASSWORD=%q\n' "$password"
    printf 'RAG_ROOT_API_KEY=%q\n' "$REAL_ROOT_API_KEY"
    printf 'APP_MODELS_LEGACY_CAPABILITIES_OPENAI_TOOL_CALLING=true\n'
  } >"$REAL_ENV_OVERLAY"
}

cleanup_real_stack() {
  if [[ -n "$REAL_DEV_PID" ]] && kill -0 "$REAL_DEV_PID" >/dev/null 2>&1; then
    kill "$REAL_DEV_PID" >/dev/null 2>&1 || true
    wait "$REAL_DEV_PID" >/dev/null 2>&1 || true
  fi
  REAL_DEV_PID=""
  if [[ -n "$REAL_ENV_OVERLAY" ]]; then
    DEV_ENV_FILE="$REAL_ENV_OVERLAY" \
      RAG_DEV_OPEN_BROWSER=false \
      ./scripts/dev.sh --stop >/dev/null 2>&1 || true
  fi
  REAL_STACK_READY=0
}

cleanup_real_database() {
  if [[ -n "$REAL_DATABASE" ]]; then
    PGPASSWORD="${POSTGRES_PASSWORD:-}" \
      dropdb \
        -h "${POSTGRES_HOST:-127.0.0.1}" \
        -p "${POSTGRES_PORT:-5432}" \
        -U "${POSTGRES_USER:-postgres}" \
        --if-exists \
        "$REAL_DATABASE" >/dev/null 2>&1 || true
    REAL_DATABASE=""
  fi
  if [[ -n "$REAL_ENV_OVERLAY" ]]; then
    rm -f "$REAL_ENV_OVERLAY"
    REAL_ENV_OVERLAY=""
  fi
}

start_real_stack() {
  load_real_environment || return 1
  prepare_real_database || return 1
  create_real_environment_overlay || {
    cleanup_real_database
    return 1
  }

  local real_log="$LOG_DIR/real-dev.log"
  (
    BACKEND_PORT="$REAL_BACKEND_PORT" \
      FRONTEND_PORT="$REAL_FRONTEND_PORT" \
      DEV_ENV_FILE="$REAL_ENV_OVERLAY" \
      RAG_ROOT_API_KEY="$REAL_ROOT_API_KEY" \
      RAG_DEV_OPEN_BROWSER=false \
      ./scripts/dev.sh
  ) >"$real_log" 2>&1 &
  REAL_DEV_PID=$!

  local attempt
  for attempt in $(seq 1 180); do
    if curl --fail --silent --show-error --connect-timeout 1 --max-time 2 \
      "http://127.0.0.1:${REAL_FRONTEND_PORT}/webui/unlock" >/dev/null 2>&1; then
      REAL_STACK_READY=1
      echo "Real dev stack ready: backend=${REAL_BACKEND_PORT}, frontend=${REAL_FRONTEND_PORT}"
      return 0
    fi
    if ! kill -0 "$REAL_DEV_PID" >/dev/null 2>&1 \
      && ! lsof -nP -iTCP:"$REAL_BACKEND_PORT" -sTCP:LISTEN >/dev/null 2>&1; then
      echo "Real dev stack exited before readiness; see ${real_log}" >&2
      tail -100 "$real_log" >&2 || true
      cleanup_real_stack
      cleanup_real_database
      return 1
    fi
    if [[ "$attempt" == "180" ]]; then
      echo "Real dev stack did not become ready; see ${real_log}" >&2
      tail -100 "$real_log" >&2 || true
      cleanup_real_stack
      cleanup_real_database
      return 1
    fi
    sleep 1
  done
}

real_browser_and_provider_e2e() {
  start_real_stack || return 1

  local browser_rc=0
  local provider_rc=0
  (
    cd spring-ai-rag-webui
    BASE_URL="http://127.0.0.1:${REAL_FRONTEND_PORT}" \
      RAG_ROOT_API_KEY="$REAL_ROOT_API_KEY" \
      npx playwright test e2e/chat-real.spec.ts
  ) || browser_rc=$?
  if [[ "$browser_rc" -eq 0 ]]; then
    echo "Real WebUI Playwright: PASS"
  else
    echo "Real WebUI Playwright: FAIL (exit ${browser_rc})" >&2
  fi

  BASE_URL="http://127.0.0.1:${REAL_BACKEND_PORT}" \
    REAL_LLM_ENV_FILE="$REAL_ENV_OVERLAY" \
    RAG_ROOT_API_KEY="$REAL_ROOT_API_KEY" \
    ./scripts/real-llm-chat-idempotency-smoke.sh || provider_rc=$?
  if [[ "$provider_rc" -eq 0 ]]; then
    echo "Real provider smoke: PASS"
  else
    echo "Real provider smoke: FAIL (exit ${provider_rc})" >&2
  fi

  cleanup_real_stack
  cleanup_real_database
  [[ "$browser_rc" -eq 0 && "$provider_rc" -eq 0 ]]
}

webui_unit() {
  (
    cd spring-ai-rag-webui
    npm run test:run
  )
}

webui_typecheck() {
  (
    cd spring-ai-rag-webui
    npx tsc -b --pretty false
  )
}

webui_build() {
  (
    cd spring-ai-rag-webui
    npm run build
  )
}

webui_playwright() {
  local preview_log="$LOG_DIR/playwright-preview.log"
  (
    cd spring-ai-rag-webui
    exec npx vite preview --host 127.0.0.1 --port "$PLAYWRIGHT_PORT" --strictPort
  ) >"$preview_log" 2>&1 &
  PREVIEW_PID=$!

  local attempt
  for attempt in $(seq 1 30); do
    if ! kill -0 "$PREVIEW_PID" >/dev/null 2>&1; then
      echo "Vite preview exited before readiness; see ${preview_log}" >&2
      return 1
    fi
    if rg -q "error when starting preview server|Port .* is already in use" "$preview_log"; then
      echo "Vite preview could not bind ${PLAYWRIGHT_PORT}; see ${preview_log}" >&2
      return 1
    fi
    if curl --fail --silent --show-error --connect-timeout 1 --max-time 2 \
      "http://127.0.0.1:${PLAYWRIGHT_PORT}/webui/" >/dev/null 2>&1; then
      break
    fi
    if [[ "$attempt" == "30" ]]; then
      echo "Vite preview did not become ready; see ${preview_log}" >&2
      return 1
    fi
    sleep 1
  done

  (
    cd spring-ai-rag-webui
    BASE_URL="http://127.0.0.1:${PLAYWRIGHT_PORT}" \
      npx playwright test e2e/chat.spec.ts e2e/streaming-upload.spec.ts
  )
}

real_llm_smoke() {
  BASE_URL="${REAL_LLM_BASE_URL:-${BASE_URL:-http://127.0.0.1:18081}}" \
    ./scripts/real-llm-e2e-smoke.sh
}

cleanup() {
  if [[ -n "$PREVIEW_PID" ]] && kill -0 "$PREVIEW_PID" >/dev/null 2>&1; then
    kill "$PREVIEW_PID" >/dev/null 2>&1 || true
    wait "$PREVIEW_PID" >/dev/null 2>&1 || true
  fi
  cleanup_real_stack
  cleanup_real_database
  cleanup_startup_smoke
  write_summary
}

write_summary() {
  local result="PASS"
  if [[ "$FAIL_COUNT" -gt 0 ]]; then
    result="FAIL"
  elif [[ "$SKIP_COUNT" -gt 0 ]]; then
    result="PASS_WITH_SKIPS"
  fi

  {
    echo "# Chat capability verification"
    echo
    echo "- Run: \`${RUN_ID}\`"
    echo "- Generated: \`$(date '+%Y-%m-%d %H:%M:%S %z')\`"
    echo "- Branch: \`$(git branch --show-current)\`"
    echo "- Commit: \`$(git rev-parse --short HEAD)\`"
    echo "- Result: **${result}**"
    echo "- Counts: ${PASS_COUNT} passed, ${FAIL_COUNT} failed, ${SKIP_COUNT} skipped"
    echo "- PostgreSQL/Testcontainers API version: \`${TESTCONTAINERS_API_VERSION}\`"
    echo "- PostgreSQL image: \`${TESTCONTAINERS_PG_IMAGE}\`"
    echo
    printf '%s\n' 'A `PASS_WITH_SKIPS` result is not a complete release gate. Inspect every'
    printf '%s\n' '`SKIP` row and rerun without the corresponding skip when the environment'
    echo "is available. Real LLM verification is never implied by local tests."
    echo
    echo "| Step | Status | Exit | Evidence |"
    echo "|------|--------|------|----------|"
    if [[ -f "$LOG_DIR/summary.tsv" ]]; then
      while IFS='|' read -r name status exit_code evidence; do
        echo "| ${name} | ${status} | ${exit_code} | \`${evidence}\` |"
      done < "$LOG_DIR/summary.tsv"
    fi
  } > "$LOG_DIR/summary.md"
}

cleanup_on_signal() {
  cleanup
  exit 130
}

trap cleanup EXIT
trap cleanup_on_signal INT TERM

run_step "Prerequisites" require_commands
run_step "Chat focused backend tests" chat_focused_tests

if [[ "$RUN_POSTGRES" == "1" ]]; then
  if docker_environment >/dev/null 2>&1; then
    run_step "Docker environment" docker_environment
    run_step "Chat and high-value PostgreSQL integration tests" chat_postgres_tests
  else
    skip_step "Docker environment" "Docker daemon unavailable or Docker API negotiation failed"
    skip_step "Chat and high-value PostgreSQL integration tests" "Docker prerequisite unavailable"
  fi
else
  skip_step "Docker environment" "--skip-postgres"
  skip_step "Chat and high-value PostgreSQL integration tests" "--skip-postgres"
fi

run_step "Maven clean compile test-compile" maven_compile
run_step "Full Maven test" maven_all_tests
run_step "Install current reactor artifacts" maven_install_current_reactor
run_step "Domain extension demo tests" domain_extension_demo_tests
run_step "Read-only SQL tool demo tests" sql_tool_demo_tests

if [[ "$RUN_STARTUP" == "1" ]]; then
  if docker_environment >/dev/null 2>&1; then
    run_step "Isolated backend startup smoke" backend_startup_smoke
  else
    skip_step "Isolated backend startup smoke" "Docker prerequisite unavailable"
  fi
else
  skip_step "Isolated backend startup smoke" "--skip-startup"
fi

run_step "WebUI Vitest" webui_unit
run_step "WebUI TypeScript" webui_typecheck
run_step "WebUI production build" webui_build

if [[ "$RUN_PLAYWRIGHT" == "1" ]]; then
  run_step "Chat core Mock Playwright" webui_playwright
else
  skip_step "Chat core Mock Playwright" "--skip-playwright"
fi

run_step "No explicit pessimistic locks" ./scripts/verify-no-pessimistic-locks.sh
run_step "Project documentation gates" ./scripts/verify-project-docs.sh
run_step "Git whitespace check" git diff --check

if [[ "$RUN_REAL_LLM" == "1" ]]; then
  run_step "Real LLM isolated WebUI and provider E2E" real_browser_and_provider_e2e
else
  skip_step "Real LLM isolated WebUI and provider E2E" "not requested; use --with-real-llm"
fi

echo
echo "Chat capability verification: ${PASS_COUNT} passed, ${FAIL_COUNT} failed, ${SKIP_COUNT} skipped"
echo "Summary: ${LOG_DIR}/summary.md"

if [[ "$FAIL_COUNT" -gt 0 ]]; then
  exit 1
fi
exit 0
