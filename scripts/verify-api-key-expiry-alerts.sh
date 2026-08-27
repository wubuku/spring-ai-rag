#!/usr/bin/env bash
# 受管 API principal 到期告警的一键验收门禁。
set -euo pipefail

cd "$(dirname "$0")/.."

RUN_ID="${API_KEY_EXPIRY_ALERT_VERIFY_RUN_ID:-$(date +%Y%m%d-%H%M%S)}"
LOG_DIR="${API_KEY_EXPIRY_ALERT_VERIFY_LOG_DIR:-.verification/api-key-expiry-alerts/${RUN_ID}}"
PHASE="${API_KEY_EXPIRY_ALERT_VERIFY_PHASE:-all}"
PLAYWRIGHT_PORT="${API_KEY_EXPIRY_ALERT_PLAYWRIGHT_PORT:-4179}"
PREVIEW_PID=""
STEP_INDEX=0
PASS_COUNT=0

mkdir -p "$LOG_DIR"
: >"$LOG_DIR/summary.tsv"

usage() {
  cat <<'EOF'
Usage: ./scripts/verify-api-key-expiry-alerts.sh

Phases:
  all       Focused backend, PostgreSQL, Maven, WebUI, Mock Playwright,
            locks, documentation, diff, secret, and shell gates (default)
  focused   Focused backend, PostgreSQL, and WebUI tests only

Environment:
  API_KEY_EXPIRY_ALERT_VERIFY_PHASE
  API_KEY_EXPIRY_ALERT_VERIFY_RUN_ID
  API_KEY_EXPIRY_ALERT_VERIFY_LOG_DIR
  API_KEY_EXPIRY_ALERT_PLAYWRIGHT_PORT
  API_PRINCIPAL_EXPIRY_ALERT_IT_JDBC_URL
  API_PRINCIPAL_EXPIRY_ALERT_IT_USERNAME
  API_PRINCIPAL_EXPIRY_ALERT_IT_PASSWORD
  API_PRINCIPAL_EXPIRY_ALERT_IT_CLEAN_CONFIRM=YES
  TESTCONTAINERS_PG_IMAGE
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
  echo "API_KEY_EXPIRY_ALERT_VERIFY_PHASE must be all or focused" >&2
  exit 2
fi

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

require_commands() {
  local command_name
  for command_name in bash curl docker git java mvn node npm npx rg; do
    command -v "$command_name" >/dev/null || {
      echo "Missing required command: ${command_name}" >&2
      return 1
    }
  done
}

focused_backend_tests() {
  mvn -pl spring-ai-rag-core -am \
    -Dtest='RagApiKeyExpiryAlertPropertiesTest,ApiPrincipalLifecycleEventPublisherTest,NotificationAsyncProxyTest,AlertManagementAuthorizationTest,AlertControllerWebTest,ApiKeyManagementServiceTest,AlertServiceImplTest,EmailNotificationServiceTest,DingTalkNotificationServiceTest,ApiCapabilityFilterTest,RagControllerIntegrationTest,OpenApiContractTest' \
    -Dsurefire.failIfNoSpecifiedTests=false \
    test
}

postgres_tests() {
  local report="spring-ai-rag-core/target/surefire-reports/TEST-com.springairag.core.integration.ApiPrincipalExpiryAlertPostgresIntegrationTest.xml"
  TESTCONTAINERS_RYUK_DISABLED=true \
  TESTCONTAINERS_CHECKS_DISABLE=true \
  mvn -pl spring-ai-rag-core -am \
    -Dapi-principal-expiry-alert.it.enabled=true \
    -Dtest=ApiPrincipalExpiryAlertPostgresIntegrationTest \
    -Dsurefire.failIfNoSpecifiedTests=false \
    test

  [[ -f "$report" ]] || {
    echo "Missing PostgreSQL acceptance report: ${report}" >&2
    return 1
  }
  local tests failures errors skipped
  tests="$(sed -n 's/.* tests="\([0-9][0-9]*\)".*/\1/p' "$report" | head -1)"
  failures="$(sed -n 's/.* failures="\([0-9][0-9]*\)".*/\1/p' "$report" | head -1)"
  errors="$(sed -n 's/.* errors="\([0-9][0-9]*\)".*/\1/p' "$report" | head -1)"
  skipped="$(sed -n 's/.* skipped="\([0-9][0-9]*\)".*/\1/p' "$report" | head -1)"
  if [[ "$tests" != "6" || "$failures" != "0" \
      || "$errors" != "0" || "$skipped" != "0" ]]; then
    echo "V57 PostgreSQL acceptance requires 6 tests with no "
    echo "failure/error/skip; got tests=${tests:-missing}, "
    echo "failures=${failures:-missing}, errors=${errors:-missing}, "
    echo "skipped=${skipped:-missing}." >&2
    return 1
  fi
}

webui_checks() {
  (
    cd spring-ai-rag-webui
    npm run typecheck
    npm run test:run
    npm run check:alignment
    npm run build
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
  if [[ -n "$PREVIEW_PID" ]]; then
    kill "$PREVIEW_PID" >/dev/null 2>&1 || true
    wait "$PREVIEW_PID" >/dev/null 2>&1 || true
    PREVIEW_PID=""
  fi
}

mock_playwright() {
  local preview_log="$LOG_DIR/playwright-preview.log"
  PLAYWRIGHT_PORT="$(find_available_port "$PLAYWRIGHT_PORT")"
  (
    cd spring-ai-rag-webui
    exec ./node_modules/.bin/vite preview \
      --host 127.0.0.1 \
      --port "$PLAYWRIGHT_PORT" \
      --strictPort
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
        "http://127.0.0.1:${PLAYWRIGHT_PORT}/webui/" \
        >/dev/null 2>&1; then
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
      npx playwright test e2e/alerts.spec.ts --project=chromium
  ) || rc=$?
  cleanup_preview
  return "$rc"
}

check_added_secrets() {
  local added_lines
  added_lines="$(git diff --no-ext-diff --unified=0 -- . ':(exclude)*.lock' \
    | sed -n 's/^+[^+]//p')"
  if printf '%s\n' "$added_lines" \
      | rg -n '(sk-[A-Za-z0-9_-]{20,}|gh[oprsu]_[A-Za-z0-9]{30,}|AIza[0-9A-Za-z_-]{30,}|Bearer[[:space:]]+[A-Za-z0-9._-]{32,})'; then
    echo "Potential secret detected in added lines." >&2
    return 1
  fi
}

write_summary() {
  {
    echo "# API principal expiry alert verification"
    echo
    echo "- Run: \`${RUN_ID}\`"
    echo "- Generated: \`$(date '+%Y-%m-%d %H:%M:%S %z')\`"
    echo "- Branch: \`$(git branch --show-current)\`"
    echo "- Commit: \`$(git rev-parse --short HEAD)\`"
    echo "- Phase: \`${PHASE}\`"
    echo "- Passed steps: **${PASS_COUNT}**"
    echo
    echo "| Step | Status | Evidence |"
    echo "|------|--------|----------|"
    while IFS=$'\t' read -r name status evidence; do
      echo "| ${name} | ${status} | \`${evidence}\` |"
    done <"$LOG_DIR/summary.tsv"
  } >"$LOG_DIR/summary.md"
}

finish() {
  cleanup_preview
  write_summary
}
trap finish EXIT

run_step "Prerequisites" require_commands
run_step "Focused expiry alert backend tests" focused_backend_tests
run_step "Disposable PostgreSQL V1-V57 lifecycle acceptance" postgres_tests
run_step "WebUI typecheck Vitest alignment build" webui_checks
run_step "Alerts Mock Playwright" mock_playwright

if [[ "$PHASE" == "all" ]]; then
  run_step "Maven clean compile test-compile" mvn clean compile test-compile
  run_step "No pessimistic locks" ./scripts/verify-no-pessimistic-locks.sh
  run_step "Project documentation" ./scripts/verify-project-docs.sh
  run_step "Shell syntax" bash -n scripts/verify-api-key-expiry-alerts.sh
  run_step "Git whitespace" git diff --check
  run_step "Added-line secret scan" check_added_secrets
fi

echo
echo "API principal expiry alert verification passed: ${PASS_COUNT} steps"
echo "Summary: ${LOG_DIR}/summary.md"
