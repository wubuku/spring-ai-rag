#!/usr/bin/env bash
# Collection 受保护清理与退役的一键验收门禁。
set -euo pipefail

cd "$(dirname "$0")/.."

RUN_ID="${COLLECTION_PURGE_VERIFY_RUN_ID:-$(date +%Y%m%d-%H%M%S)}"
LOG_DIR="${COLLECTION_PURGE_VERIFY_LOG_DIR:-.verification/collection-purge/${RUN_ID}}"
PLAYWRIGHT_PORT="${COLLECTION_PURGE_PLAYWRIGHT_PORT:-4178}"
VITE_PID=""
STEP_INDEX=0
PASS_COUNT=0

mkdir -p "$LOG_DIR"
: >"$LOG_DIR/summary.tsv"

slugify() {
  printf '%s' "$1" \
    | tr '[:upper:] ' '[:lower:]-' \
    | tr -cd 'a-z0-9._-'
}

run_step() {
  local name="$1"
  shift
  STEP_INDEX=$((STEP_INDEX + 1))
  local log_path="$LOG_DIR/${STEP_INDEX}-$(slugify "$name").log"

  echo
  echo "=== ${name} ==="
  if "$@" > >(tee "$log_path") 2>&1; then
    PASS_COUNT=$((PASS_COUNT + 1))
    printf '%s\tPASS\t%s\n' "$name" "$log_path" >>"$LOG_DIR/summary.tsv"
  else
    printf '%s\tFAIL\t%s\n' "$name" "$log_path" >>"$LOG_DIR/summary.tsv"
    return 1
  fi
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
  const selected = await probe(preferred) ?? await probe(0);
  if (selected === null) process.exit(1);
  process.stdout.write(String(selected));
})();
NODE
}

focused_backend_tests() {
  mvn -pl spring-ai-rag-core -am \
    -Dtest=CollectionPurgeControllerWebTest,RagCollectionControllerTest,CollectionIdentityResolverTest,CollectionRetrievalScopeResolverTest,RagCollectionServiceTest,UserFeedbackServiceImplTest,AuditLogServiceTest,OpenAiCompatibilityControllerWebTest,OpenAiRequestRetrievalScopeAdapterTest,IntegrationOperationClassifierTest \
    -Dsurefire.failIfNoSpecifiedTests=false \
    test
}

postgres_tests() {
  local purge_report="spring-ai-rag-core/target/surefire-reports/TEST-com.springairag.core.integration.CollectionPurgePostgresIntegrationTest.xml"
  local observability_report="spring-ai-rag-core/target/surefire-reports/TEST-com.springairag.core.integration.IntegrationObservabilityPostgresIntegrationTest.xml"
  local -a arguments=(
    -pl spring-ai-rag-core
    -am
    -Dcollection-purge.it.enabled=true
    -Dintegration-observability.it.enabled=true
    -Dtest=CollectionPurgePostgresIntegrationTest,IntegrationObservabilityPostgresIntegrationTest
    -Dsurefire.failIfNoSpecifiedTests=false
  )

  if [[ -n "${COLLECTION_PURGE_IT_JDBC_URL:-}" ]]; then
    [[ "${COLLECTION_PURGE_IT_CLEAN_CONFIRM:-}" == "YES" ]] || {
      echo "COLLECTION_PURGE_IT_CLEAN_CONFIRM=YES is required for a caller-provided disposable database." >&2
      return 1
    }
    arguments+=(
      "-Dcollection-purge.it.jdbc-url=${COLLECTION_PURGE_IT_JDBC_URL}"
      "-Dcollection-purge.it.username=${COLLECTION_PURGE_IT_USERNAME:-postgres}"
      "-Dcollection-purge.it.password=${COLLECTION_PURGE_IT_PASSWORD:-postgres}"
      -Dcollection-purge.it.clean-confirm=YES
    )
    export INTEGRATION_OBSERVABILITY_IT_JDBC_URL="$COLLECTION_PURGE_IT_JDBC_URL"
    export INTEGRATION_OBSERVABILITY_IT_USERNAME="${COLLECTION_PURGE_IT_USERNAME:-postgres}"
    export INTEGRATION_OBSERVABILITY_IT_PASSWORD="${COLLECTION_PURGE_IT_PASSWORD:-postgres}"
    export INTEGRATION_OBSERVABILITY_IT_CLEAN_CONFIRM=YES
  fi

  mvn "${arguments[@]}" test

  assert_test_report "$purge_report" 5 "Collection purge PostgreSQL acceptance"
  assert_test_report "$observability_report" 6 \
    "Integration observability V56 compatibility acceptance"
}

assert_test_report() {
  local report="$1"
  local expected_tests="$2"
  local label="$3"
  [[ -f "$report" ]] || {
    echo "Missing PostgreSQL acceptance report: ${report}" >&2
    return 1
  }

  local tests failures errors skipped
  tests="$(sed -n 's/.* tests="\([0-9][0-9]*\)".*/\1/p' "$report" | head -1)"
  failures="$(sed -n 's/.* failures="\([0-9][0-9]*\)".*/\1/p' "$report" | head -1)"
  errors="$(sed -n 's/.* errors="\([0-9][0-9]*\)".*/\1/p' "$report" | head -1)"
  skipped="$(sed -n 's/.* skipped="\([0-9][0-9]*\)".*/\1/p' "$report" | head -1)"
  if [[ "$tests" != "$expected_tests" || "$failures" != "0" \
      || "$errors" != "0" || "$skipped" != "0" ]]; then
    echo "${label} requires ${expected_tests} tests with no failure/error/skip; got tests=${tests:-missing}, failures=${failures:-missing}, errors=${errors:-missing}, skipped=${skipped:-missing}." >&2
    return 1
  fi
}

webui_checks() {
  (
    cd spring-ai-rag-webui
    npm run typecheck
    npm run test:run
    npm run lint
    npm run build
  )
}

mock_playwright() {
  PLAYWRIGHT_PORT="$(find_available_port "$PLAYWRIGHT_PORT")"
  (
    cd spring-ai-rag-webui
    exec ./node_modules/.bin/vite preview \
      --host 127.0.0.1 \
      --port "$PLAYWRIGHT_PORT" \
      --strictPort
  ) >"$LOG_DIR/vite-preview.log" 2>&1 &
  VITE_PID=$!

  local attempt
  for attempt in $(seq 1 30); do
    if ! kill -0 "$VITE_PID" >/dev/null 2>&1; then
      echo "Vite preview exited before readiness." >&2
      return 1
    fi
    if curl --fail --silent --show-error --connect-timeout 1 --max-time 2 \
        "http://127.0.0.1:${PLAYWRIGHT_PORT}/webui/" >/dev/null 2>&1; then
      break
    fi
    [[ "$attempt" != "30" ]] || {
      echo "Vite preview did not become ready." >&2
      return 1
    }
    sleep 1
  done

  local rc=0
  (
    cd spring-ai-rag-webui
    BASE_URL="http://127.0.0.1:${PLAYWRIGHT_PORT}" \
      npx playwright test e2e/collections.spec.ts --project=chromium
  ) || rc=$?

  kill "$VITE_PID" >/dev/null 2>&1 || true
  wait "$VITE_PID" >/dev/null 2>&1 || true
  VITE_PID=""
  return "$rc"
}

write_summary() {
  {
    echo "# Collection purge verification"
    echo
    echo "- Run: \`${RUN_ID}\`"
    echo "- Generated: \`$(date '+%Y-%m-%d %H:%M:%S %z')\`"
    echo "- Branch: \`$(git branch --show-current)\`"
    echo "- Commit: \`$(git rev-parse --short HEAD)\`"
    echo "- Passed steps: **${PASS_COUNT}**"
    echo
    echo "| Step | Status | Evidence |"
    echo "|------|--------|----------|"
    while IFS=$'\t' read -r name status evidence; do
      echo "| ${name} | ${status} | \`${evidence}\` |"
    done <"$LOG_DIR/summary.tsv"
  } >"$LOG_DIR/summary.md"
}

cleanup() {
  if [[ -n "$VITE_PID" ]]; then
    kill "$VITE_PID" >/dev/null 2>&1 || true
    wait "$VITE_PID" >/dev/null 2>&1 || true
  fi
  write_summary
}
trap cleanup EXIT

for command_name in bash curl git java mvn node npm npx; do
  command -v "$command_name" >/dev/null || {
    echo "Missing required command: ${command_name}" >&2
    exit 1
  }
done

run_step "No explicit pessimistic locks" ./scripts/verify-no-pessimistic-locks.sh
run_step "Focused purge backend tests" focused_backend_tests
run_step "Disposable PostgreSQL V1-V57 purge acceptance" postgres_tests
run_step "Maven clean compile test-compile" mvn clean compile test-compile
run_step "WebUI typecheck Vitest lint build" webui_checks
run_step "Collection Mock Playwright" mock_playwright
run_step "Project documentation gate" ./scripts/verify-project-docs.sh
run_step "Shell syntax" bash -n scripts/verify-collection-purge.sh
run_step "Git whitespace" git diff --check

echo
echo "Collection purge verification passed: ${PASS_COUNT} steps"
echo "Summary: ${LOG_DIR}/summary.md"
