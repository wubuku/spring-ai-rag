#!/usr/bin/env bash
# One-command verification for the durable model-invocation usage ledger.
#
# The default database and browser port are disposable and isolated. A caller
# supplied PostgreSQL URL must be explicitly acknowledged as safe to clean.
set -euo pipefail

cd "$(dirname "$0")/.."

RUN_ID="${LLM_USAGE_LEDGER_VERIFY_RUN_ID:-$(date +%Y%m%d-%H%M%S)}"
LOG_DIR="${LLM_USAGE_LEDGER_VERIFY_LOG_DIR:-.verification/llm-usage-ledger/${RUN_ID}}"
PHASE="${LLM_USAGE_LEDGER_VERIFY_PHASE:-all}"
PG_IMAGE="${TESTCONTAINERS_PG_IMAGE:-pgvector/pgvector:pg16}"
PG_JDBC_URL="${LLM_USAGE_LEDGER_IT_JDBC_URL:-}"
PG_USERNAME="${LLM_USAGE_LEDGER_IT_USERNAME:-postgres}"
PG_PASSWORD="${LLM_USAGE_LEDGER_IT_PASSWORD:-postgres}"
PLAYWRIGHT_PORT="${LLM_USAGE_LEDGER_PLAYWRIGHT_PORT:-4177}"
PG_CONTAINER=""
PREVIEW_PID=""
mkdir -p "$LOG_DIR"
: > "$LOG_DIR/summary.tsv"

PASS_COUNT=0
STEP_INDEX=0

usage() {
  cat <<'EOF'
Usage: ./scripts/verify-llm-usage-ledger.sh

Phases:
  all       Focused tests, PostgreSQL, Maven, WebUI, Mock Playwright, locks,
            documentation, and whitespace (default)
  focused   Fast focused backend and WebUI checks only

Environment:
  LLM_USAGE_LEDGER_VERIFY_PHASE       all or focused
  LLM_USAGE_LEDGER_VERIFY_RUN_ID      Evidence run identifier
  LLM_USAGE_LEDGER_VERIFY_LOG_DIR     Evidence directory
  LLM_USAGE_LEDGER_PLAYWRIGHT_PORT    Preferred Vite preview port
  LLM_USAGE_LEDGER_IT_JDBC_URL        Explicit disposable PostgreSQL JDBC URL
  LLM_USAGE_LEDGER_IT_USERNAME        Explicit database username
  LLM_USAGE_LEDGER_IT_PASSWORD        Explicit database password
  LLM_USAGE_LEDGER_IT_CLEAN_CONFIRM   Must be YES with an external JDBC URL
  TESTCONTAINERS_PG_IMAGE             Disposable PostgreSQL/pgvector image
EOF
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi
if [[ $# -gt 0 ]]; then
  echo "Unknown option: $1" >&2
  usage >&2
  exit 2
fi
if [[ "$PHASE" != "all" && "$PHASE" != "focused" ]]; then
  echo "LLM_USAGE_LEDGER_VERIFY_PHASE must be all or focused" >&2
  exit 2
fi

run_step() {
  local name="$1"
  shift
  STEP_INDEX=$((STEP_INDEX + 1))
  local slug log_path
  slug="$(printf '%s' "$name" | tr '[:upper:] ' '[:lower:]-' | tr -cd 'a-z0-9._-')"
  log_path="$LOG_DIR/${STEP_INDEX}-${slug}.log"
  echo
  echo "=== ${name} ==="
  echo "log: ${log_path}"
  set +e
  "$@" > >(tee "$log_path") 2>&1
  local rc=$?
  set -e
  if [[ "$rc" -ne 0 ]]; then
    printf '%s\tFAIL\t%s\n' "$name" "$log_path" >> "$LOG_DIR/summary.tsv"
    return "$rc"
  fi
  PASS_COUNT=$((PASS_COUNT + 1))
  printf '%s\tPASS\t%s\n' "$name" "$log_path" >> "$LOG_DIR/summary.tsv"
}

require_commands() {
  local command_name
  for command_name in bash curl docker git mvn node npm npx rg; do
    command -v "$command_name" >/dev/null || {
      echo "Missing required command: ${command_name}" >&2
      return 1
    }
  done
}

check_docker() {
  if [[ -n "$PG_JDBC_URL" ]]; then
    [[ "$LLM_USAGE_LEDGER_IT_CLEAN_CONFIRM" == "YES" ]] || {
      echo "External database requires LLM_USAGE_LEDGER_IT_CLEAN_CONFIRM=YES." >&2
      return 1
    }
    echo "Using explicitly acknowledged disposable PostgreSQL URL."
    return 0
  fi
  docker version >/dev/null
}

start_postgres() {
  if [[ -n "$PG_JDBC_URL" ]]; then
    return 0
  fi
  PG_CONTAINER="spring-ai-rag-llm-usage-${RUN_ID}-$$"
  docker run -d --rm \
    --name "$PG_CONTAINER" \
    -e POSTGRES_DB=spring_ai_rag_llm_usage_test \
    -e POSTGRES_USER="$PG_USERNAME" \
    -e POSTGRES_PASSWORD="$PG_PASSWORD" \
    -p 127.0.0.1::5432 \
    "$PG_IMAGE" >/dev/null

  local attempt port
  for attempt in $(seq 1 45); do
    if docker exec "$PG_CONTAINER" pg_isready \
        -U "$PG_USERNAME" -d spring_ai_rag_llm_usage_test >/dev/null 2>&1; then
      port="$(docker port "$PG_CONTAINER" 5432/tcp | sed 's/.*://')"
      PG_JDBC_URL="jdbc:postgresql://127.0.0.1:${port}/spring_ai_rag_llm_usage_test"
      return 0
    fi
    sleep 1
  done
  echo "PostgreSQL did not become ready." >&2
  return 1
}

focused_backend_tests() {
  mvn -pl spring-ai-rag-core -am \
    -Dtest='BudgetedChatModelTest,ChatExecutionBudgetTest,RagChatServiceTest,ChatExecutionServiceTest,ModeAwareChatClientFactoryTest,ConversationSummaryServiceTest,QueryRewritingServiceTest,JdbcLlmUsageRecorderTest,LlmUsageQueryServiceTest,RagMetricsControllerWebTest,OpenApiContractTest' \
    -Dsurefire.failIfNoSpecifiedTests=false test
}

postgres_tests() {
  TESTCONTAINERS_RYUK_DISABLED=true \
  TESTCONTAINERS_CHECKS_DISABLE=true \
  mvn -pl spring-ai-rag-core -am \
    -Dllm-usage.it.enabled=true \
    "-Dllm-usage.it.jdbc-url=${PG_JDBC_URL}" \
    "-Dllm-usage.it.username=${PG_USERNAME}" \
    "-Dllm-usage.it.password=${PG_PASSWORD}" \
    "-Dllm-usage.it.clean-confirm=${LLM_USAGE_LEDGER_IT_CLEAN_CONFIRM:-YES}" \
    -Dtest=LlmUsagePostgresIntegrationTest \
    -Dsurefire.failIfNoSpecifiedTests=false test
}

backend_compile() {
  mvn clean compile test-compile
}

backend_full_tests() {
  mvn test
}

webui_checks() {
  (
    cd spring-ai-rag-webui
    npm run typecheck
    npm run test:run
    npm run build
    npm run check:alignment
  )
}

find_available_port() {
  node - "$1" <<'NODE'
const net = require('node:net');
const preferred = Number(process.argv[2]);

function probe(port) {
  return new Promise(resolve => {
    const server = net.createServer();
    server.once('error', () => resolve(null));
    server.listen({host: '127.0.0.1', port, exclusive: true}, () => {
      const address = server.address();
      const selected = typeof address === 'object' && address ? address.port : null;
      server.close(() => resolve(selected));
    });
  });
}

(async () => {
  const selected = await probe(preferred) ?? await probe(0);
  if (selected === null) process.exit(1);
  process.stdout.write(String(selected));
})();
NODE
}

cleanup_preview() {
  if [[ -n "${PREVIEW_PID:-}" ]]; then
    kill "$PREVIEW_PID" >/dev/null 2>&1 || true
    wait "$PREVIEW_PID" >/dev/null 2>&1 || true
    PREVIEW_PID=""
  fi
}

playwright_checks() {
  local preview_log="$LOG_DIR/playwright-preview.log"
  PLAYWRIGHT_PORT="$(find_available_port "$PLAYWRIGHT_PORT")"
  (
    cd spring-ai-rag-webui
    exec ./node_modules/.bin/vite preview \
      --host 127.0.0.1 --port "$PLAYWRIGHT_PORT" --strictPort
  ) >"$preview_log" 2>&1 &
  PREVIEW_PID=$!

  local attempt
  for attempt in $(seq 1 30); do
    if ! kill -0 "$PREVIEW_PID" >/dev/null 2>&1; then
      echo "Vite preview exited; see ${preview_log}" >&2
      cleanup_preview
      return 1
    fi
    if curl --fail --silent --show-error \
        "http://127.0.0.1:${PLAYWRIGHT_PORT}/webui/" >/dev/null 2>&1; then
      break
    fi
    if [[ "$attempt" == "30" ]]; then
      echo "Vite preview did not become ready; see ${preview_log}" >&2
      cleanup_preview
      return 1
    fi
    sleep 1
  done

  local rc=0
  (
    cd spring-ai-rag-webui
    BASE_URL="http://127.0.0.1:${PLAYWRIGHT_PORT}" \
      npx playwright test e2e/pages.spec.ts --project=chromium
  ) || rc=$?
  cleanup_preview
  return "$rc"
}

write_summary() {
  {
    echo "# LLM usage ledger verification"
    echo
    echo "- Run: \`${RUN_ID}\`"
    echo "- Generated: \`$(date '+%Y-%m-%d %H:%M:%S %z')\`"
    echo "- Branch: \`$(git branch --show-current)\`"
    echo "- Commit: \`$(git rev-parse --short HEAD)\`"
    echo "- Phase: \`${PHASE}\`"
    echo "- Passed steps: **${PASS_COUNT}**"
    echo "- PostgreSQL image: \`${PG_IMAGE}\`"
    echo
    echo "| Step | Status | Log |"
    echo "|------|--------|-----|"
    while IFS=$'\t' read -r name status log_path; do
      echo "| ${name} | ${status} | \`${log_path}\` |"
    done < "$LOG_DIR/summary.tsv"
  } > "$LOG_DIR/summary.md"
}

finish() {
  cleanup_preview
  write_summary
  if [[ -n "$PG_CONTAINER" ]]; then
    docker stop "$PG_CONTAINER" >/dev/null 2>&1 || true
  fi
}
trap finish EXIT

run_step "Prerequisites" require_commands
run_step "Docker or disposable PostgreSQL preflight" check_docker
run_step "Focused backend usage ledger tests" focused_backend_tests

if [[ "$PHASE" == "all" ]]; then
  run_step "Disposable PostgreSQL startup" start_postgres
  run_step "PostgreSQL V1-V53 usage ledger integration" postgres_tests
  run_step "Maven clean compile test-compile" backend_compile
  run_step "Full Maven test suite" backend_full_tests
  run_step "WebUI typecheck Vitest build alignment" webui_checks
  run_step "WebUI Mock Playwright pages and durable usage" playwright_checks
  run_step "No pessimistic locks" ./scripts/verify-no-pessimistic-locks.sh
  run_step "Project documentation" ./scripts/verify-project-docs.sh
  run_step "Git whitespace" git diff --check
fi

echo
echo "LLM usage ledger verification passed: ${PASS_COUNT} steps"
echo "Summary: ${LOG_DIR}/summary.md"
