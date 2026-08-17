#!/usr/bin/env bash
# One-click verification for JSONB structured records and the surrounding release gates.
#
# Usage:
#   ./scripts/verify-jsonb-records.sh
#   ./scripts/verify-jsonb-records.sh --skip-playwright
#
# PostgreSQL defaults are deliberately environment-overridable:
#   TESTCONTAINERS_PG_IMAGE=pgvector/pgvector:pg16
#   JSONB_IT_JDBC_URL=jdbc:postgresql://127.0.0.1:5432/test
set -euo pipefail

cd "$(dirname "$0")/.."

RUN_ID="${JSONB_VERIFY_RUN_ID:-$(date +%Y%m%d-%H%M%S)}"
# Keep logs outside Maven's target/ because the compile gate runs `mvn clean`.
LOG_DIR="${JSONB_VERIFY_LOG_DIR:-.verification/jsonb-verification/${RUN_ID}}"
PLAYWRIGHT_PORT="${JSONB_PLAYWRIGHT_PORT:-4174}"
TESTCONTAINERS_PG_IMAGE="${TESTCONTAINERS_PG_IMAGE:-pgvector/pgvector:pg16}"
JSONB_IT_JDBC_URL="${JSONB_IT_JDBC_URL:-}"
JSONB_IT_USERNAME="${JSONB_IT_USERNAME:-postgres}"
JSONB_IT_PASSWORD="${JSONB_IT_PASSWORD:-postgres}"
POSTGRES_CONTAINER=""
PLAYWRIGHT_PREVIEW_PID=""
SKIP_PLAYWRIGHT=0

usage() {
  cat <<'EOF'
Usage: ./scripts/verify-jsonb-records.sh [options]

Runs:
  API DTO, documents chunker, JSON record service/controller/OpenAPI tests
  real PostgreSQL JSONB/Testcontainers integration tests
  mvn clean compile test-compile
  WebUI Vitest and production build
  Mock API Playwright suite
  project documentation gates and git diff --check

Options:
      --skip-playwright  Skip the browser suite
  -h, --help             Show help

Environment:
  JSONB_VERIFY_LOG_DIR       Verification output directory (default: .verification/jsonb-verification/<run-id>)
  JSONB_VERIFY_RUN_ID        Stable run identifier
  JSONB_PLAYWRIGHT_PORT      Preferred Vite preview port (default: 4174; falls back if busy)
  TESTCONTAINERS_PG_IMAGE    PostgreSQL/pgvector image
  JSONB_IT_JDBC_URL          Reuse an external isolated PostgreSQL database
  JSONB_IT_USERNAME          External database username
  JSONB_IT_PASSWORD          External database password
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --skip-playwright)
      SKIP_PLAYWRIGHT=1
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
PASS_COUNT=0
STEP_INDEX=0

run_step() {
  local name="$1"
  shift
  STEP_INDEX=$((STEP_INDEX + 1))
  local slug
  slug="$(printf '%s' "$name" | tr '[:upper:] ' '[:lower:]-' | tr -cd 'a-z0-9._-')"
  local log_path="$LOG_DIR/${STEP_INDEX}-${slug}.log"

  echo
  echo "=== ${name} ==="
  echo "log: ${log_path}"
  set +e
  "$@" > >(tee "$log_path") 2>&1
  local rc=$?
  set -e
  if [[ "$rc" -ne 0 ]]; then
    echo "FAIL: ${name} (exit ${rc})" >&2
    echo "${name}|FAIL|${rc}|${log_path}" >> "$LOG_DIR/summary.tsv"
    return "$rc"
  fi
  PASS_COUNT=$((PASS_COUNT + 1))
  echo "PASS: ${name}"
  echo "${name}|PASS|0|${log_path}" >> "$LOG_DIR/summary.tsv"
}

check_commands() {
  local command_name
  for command_name in bash curl git mvn node npm npx rg; do
    command -v "$command_name" >/dev/null || {
      echo "Missing required command: ${command_name}" >&2
      return 1
    }
  done
}

find_available_port() {
  node - "$1" <<'NODE'
const net = require('node:net');
const preferred = Number(process.argv[2]);

function probe(port) {
  return new Promise((resolve) => {
    const server = net.createServer();
    server.once('error', () => resolve(null));
    server.listen({ host: '127.0.0.1', port, exclusive: true }, () => {
      const address = server.address();
      const selected = typeof address === 'object' && address ? address.port : null;
      server.close(() => resolve(selected));
    });
  });
}

(async () => {
  const preferredPort = await probe(preferred);
  const selected = preferredPort ?? await probe(0);
  if (selected === null) {
    process.exit(1);
  }
  process.stdout.write(String(selected));
})();
NODE
}

check_docker() {
  if [[ -n "$JSONB_IT_JDBC_URL" ]]; then
    echo "Using caller-provided PostgreSQL URL."
    return
  fi
  command -v docker >/dev/null || {
    echo "Docker is required unless JSONB_IT_JDBC_URL is set." >&2
    return 1
  }
  docker version
}

start_postgres() {
  if [[ -n "$JSONB_IT_JDBC_URL" ]]; then
    return
  fi
  POSTGRES_CONTAINER="spring-ai-rag-jsonb-${RUN_ID}-$$"
  docker run -d --rm \
    --name "$POSTGRES_CONTAINER" \
    -e POSTGRES_DB=spring_ai_rag_jsonb_test \
    -e POSTGRES_USER="$JSONB_IT_USERNAME" \
    -e POSTGRES_PASSWORD="$JSONB_IT_PASSWORD" \
    -p 127.0.0.1::5432 \
    "$TESTCONTAINERS_PG_IMAGE" >/dev/null
  local attempt port
  for attempt in $(seq 1 30); do
    if docker exec "$POSTGRES_CONTAINER" \
        pg_isready -U "$JSONB_IT_USERNAME" \
        -d spring_ai_rag_jsonb_test >/dev/null 2>&1; then
      break
    fi
    if [[ "$attempt" == "30" ]]; then
      echo "PostgreSQL did not become ready." >&2
      return 1
    fi
    sleep 1
  done
  port="$(docker port "$POSTGRES_CONTAINER" 5432/tcp | sed 's/.*://')"
  JSONB_IT_JDBC_URL="jdbc:postgresql://127.0.0.1:${port}/spring_ai_rag_jsonb_test"
}

backend_api_tests() {
  mvn -pl spring-ai-rag-api -Dtest=DtoTest -Dsurefire.failIfNoSpecifiedTests=false test
}

documents_tests() {
  mvn -pl spring-ai-rag-documents -Dtest=HierarchicalTextChunkerTest \
    -Dsurefire.failIfNoSpecifiedTests=false test
}

core_json_tests() {
  mvn -pl spring-ai-rag-core -am \
    -Dtest=JsonRecordServiceTest,JsonRecordSearchToolTest,RagJsonRecordControllerWebTest,HybridRetrieverServiceTest,RetrievalScopeSqlTest,PgTrgmFulltextProviderTest,RagControllerIntegrationTest,OpenApiContractTest,ChatExecutionServiceTest \
    -Dsurefire.failIfNoSpecifiedTests=false test
}

postgres_json_tests() {
  mvn -pl spring-ai-rag-core -am \
    -Djsonb.it.enabled=true \
    "-Djsonb.it.jdbc-url=${JSONB_IT_JDBC_URL}" \
    "-Djsonb.it.username=${JSONB_IT_USERNAME}" \
    "-Djsonb.it.password=${JSONB_IT_PASSWORD}" \
    -Dtest=JsonbStructuredRecordsPostgresIntegrationTest \
    -Dsurefire.failIfNoSpecifiedTests=false test
}

backend_compile() {
  mvn clean compile test-compile
}

webui_unit_and_build() {
  (
    cd spring-ai-rag-webui
    npm run test:run
    npm run build
  )
}

cleanup_playwright_preview() {
  if [[ -n "${PLAYWRIGHT_PREVIEW_PID:-}" ]]; then
    kill "$PLAYWRIGHT_PREVIEW_PID" >/dev/null 2>&1 || true
    wait "$PLAYWRIGHT_PREVIEW_PID" >/dev/null 2>&1 || true
    PLAYWRIGHT_PREVIEW_PID=""
  fi
}

webui_playwright() {
  local preview_log="$LOG_DIR/playwright-preview.log"
  local requested_port="$PLAYWRIGHT_PORT"
  local preview_html=""

  PLAYWRIGHT_PORT="$(find_available_port "$requested_port")"
  if [[ "$PLAYWRIGHT_PORT" != "$requested_port" ]]; then
    echo "Preferred Playwright port ${requested_port} is busy; using ${PLAYWRIGHT_PORT}."
  fi

  (
    cd spring-ai-rag-webui
    exec ./node_modules/.bin/vite preview \
      --host 127.0.0.1 \
      --port "$PLAYWRIGHT_PORT" \
      --strictPort
  ) >"$preview_log" 2>&1 &
  PLAYWRIGHT_PREVIEW_PID=$!

  local attempt
  for attempt in $(seq 1 30); do
    if ! kill -0 "$PLAYWRIGHT_PREVIEW_PID" >/dev/null 2>&1; then
      echo "Vite preview exited before becoming ready; see ${preview_log}" >&2
      cleanup_playwright_preview
      return 1
    fi
    if rg -q "error when starting preview server|Port .* is already in use" "$preview_log"; then
      echo "Vite preview could not bind port ${PLAYWRIGHT_PORT}; see ${preview_log}" >&2
      cleanup_playwright_preview
      return 1
    fi
    preview_html="$(curl --fail --silent --show-error --connect-timeout 1 --max-time 2 \
      "http://127.0.0.1:${PLAYWRIGHT_PORT}/webui/" 2>/dev/null || true)"
    if rg -q "Local:" "$preview_log" \
        && grep -Fq "<title>spring-ai-rag WebUI</title>" <<<"$preview_html"; then
      break
    fi
    if [[ "$attempt" == "30" ]]; then
      echo "Spring AI RAG Vite preview did not become ready; see ${preview_log}" >&2
      cleanup_playwright_preview
      return 1
    fi
    sleep 1
  done

  local rc=0
  (
    cd spring-ai-rag-webui
    BASE_URL="http://127.0.0.1:${PLAYWRIGHT_PORT}" npx playwright test
  ) || rc=$?
  cleanup_playwright_preview
  return "$rc"
}

write_summary() {
  mkdir -p "$LOG_DIR"
  {
    echo "# JSONB verification"
    echo
    echo "- Run: \`${RUN_ID}\`"
    echo "- Generated: \`$(date '+%Y-%m-%d %H:%M:%S %z')\`"
    echo "- Branch: \`$(git branch --show-current)\`"
    echo "- Commit: \`$(git rev-parse --short HEAD)\`"
    echo "- Passed steps: **${PASS_COUNT}**"
    echo "- PostgreSQL image: \`${TESTCONTAINERS_PG_IMAGE}\`"
    echo
    echo "| Step | Status | Exit | Log |"
    echo "|------|--------|------|-----|"
    if [[ -f "$LOG_DIR/summary.tsv" ]]; then
      while IFS='|' read -r name status exit_code log_path; do
        echo "| ${name} | ${status} | ${exit_code} | \`${log_path}\` |"
      done < "$LOG_DIR/summary.tsv"
    fi
  } > "$LOG_DIR/summary.md"
}

finish() {
  cleanup_playwright_preview
  write_summary
  if [[ -n "$POSTGRES_CONTAINER" ]]; then
    docker stop "$POSTGRES_CONTAINER" >/dev/null 2>&1 || true
  fi
}

trap finish EXIT

: > "$LOG_DIR/summary.tsv"
run_step "Prerequisites" check_commands
run_step "Docker environment" check_docker
run_step "API DTO tests" backend_api_tests
run_step "Documents chunker tests" documents_tests
run_step "Core JSONB and controller tests" core_json_tests
run_step "Isolated PostgreSQL startup" start_postgres
run_step "PostgreSQL JSONB integration tests" postgres_json_tests
run_step "Maven clean compile test-compile" backend_compile
run_step "WebUI Vitest and production build" webui_unit_and_build

if [[ "$SKIP_PLAYWRIGHT" == "1" ]]; then
  echo "SKIP: Mock API Playwright suite (--skip-playwright)"
  echo "Mock API Playwright suite|SKIP|0|explicitly skipped" >> "$LOG_DIR/summary.tsv"
else
  run_step "Mock API Playwright suite" webui_playwright
fi

run_step "Project documentation gates" ./scripts/verify-project-docs.sh
run_step "Git whitespace check" git diff --check

echo
echo "JSONB verification passed: ${PASS_COUNT} steps"
echo "Summary: ${LOG_DIR}/summary.md"
