#!/usr/bin/env bash
# Internal runner for relocation and derivation-integrity acceptance gates.
# 注意：本脚本的编译门禁是 `mvn clean compile test-compile`，它会连同
# target/surefire-reports 一起删掉。verify-test-visibility.mjs 正是拿那份报告
# 和测试源码树双向对账的，所以跑完本脚本后要跑门禁链，请先补一次 `mvn test`。
set -euo pipefail

cd "$(dirname "$0")/.."

# Batch 894: one reader for the surefire report, so the `tests` count comes from
# the <testcase> elements rather than the attribute. Five gates had their own copy
# of the same sed pipeline and none of them had a self-test; see
# scripts/lib/surefire-report.sh for the measurement behind the rule.
source scripts/lib/surefire-report.sh
# Batch 925: this is the one script in the repository that selects surefire tests
# by *method name* rather than by class, which is the shape surefire answers
# with silence when a name has gone stale. See
# scripts/lib/surefire-method-selection.sh.
source scripts/lib/surefire-method-selection.sh

FEATURE="${1:-}"
case "$FEATURE" in
  relocation)
    HTTP_TESTS="ExternalDocumentControllerWebTest"
    POSTGRES_METHODS="latestMigrationsCreateDurableControlPlanesFromEmptyDatabase+relocationPreservesIdentityAndDerivationsAndReplaysExactly+relocationRejectsActiveSyncRunWithoutLeavingPartialState+externalUpsertRechecksRetiredAddressAfterNamespaceSequenceWait"
    PLAYWRIGHT_SPEC="e2e/documents.spec.ts"
    ;;
  derivation-integrity)
    HTTP_TESTS="DerivationRepairControllerWebTest,EmbeddingJobControllerWebTest"
    POSTGRES_METHODS="latestMigrationsCreateDurableControlPlanesFromEmptyDatabase+strictIntegrityRejectsSameCountButMismatchedVectorContent+repairPreviewAndApplyPersistStableLedgerAndOnlyQueueVectorWork+repairSelectionExcludesAlreadyConvergingDocuments+repairApplyTakesOverExpiredLeasesAndRetainsResultForFullDay+repairTakeoverContinuesFromCommittedLocalLedgerState+repairRejectsActiveProfileChangeWithoutQueueingWork"
    PLAYWRIGHT_SPEC="e2e/embeddings.spec.ts"
    ;;
  *)
    echo "Usage: $0 relocation|derivation-integrity" >&2
    exit 2
    ;;
esac

RUN_ID="${NEXT_HIGH_VALUE_VERIFY_RUN_ID:-$(date +%Y%m%d-%H%M%S)}"
LOG_DIR="${NEXT_HIGH_VALUE_VERIFY_LOG_DIR:-.verification/${FEATURE}/${RUN_ID}}"
VITE_PORT="${NEXT_HIGH_VALUE_PLAYWRIGHT_PORT:-4176}"
VITE_PID=""
STEP=0
PASS=0
mkdir -p "$LOG_DIR"
: >"$LOG_DIR/summary.tsv"

run_step() {
  local name="$1"
  shift
  STEP=$((STEP + 1))
  local log="$LOG_DIR/${STEP}-$(printf '%s' "$name" | tr '[:upper:] ' '[:lower:]-' | tr -cd 'a-z0-9._-').log"
  echo "=== $name ==="
  if "$@" > >(tee "$log") 2>&1; then
    PASS=$((PASS + 1))
    printf '%s\tPASS\t%s\n' "$name" "$log" >>"$LOG_DIR/summary.tsv"
  else
    printf '%s\tFAIL\t%s\n' "$name" "$log" >>"$LOG_DIR/summary.tsv"
    return 1
  fi
}

find_port() {
  local candidate="$1"
  while lsof -nP -iTCP:"$candidate" -sTCP:LISTEN >/dev/null 2>&1; do
    candidate=$((candidate + 1))
  done
  printf '%s' "$candidate"
}

postgres_tests() {
  local selector="NextHighValueFeaturesPostgresIntegrationTest#${POSTGRES_METHODS}"
  local source_file="spring-ai-rag-core/src/test/java/com/springairag/core/integration/NextHighValueFeaturesPostgresIntegrationTest.java"
  local report="spring-ai-rag-core/target/surefire-reports/TEST-com.springairag.core.integration.NextHighValueFeaturesPostgresIntegrationTest.xml"
  assert_surefire_methods_exist "$source_file" "$POSTGRES_METHODS" || return 1
  # Batch 925. Derived from the list rather than carried beside it: the two were
  # separate numbers that had to agree, and `POSTGRES_EXPECTED=7` no longer did.
  local expected
  expected="$(surefire_method_count "$POSTGRES_METHODS")"
  if [[ -n "${NEXT_HIGH_VALUE_IT_JDBC_URL:-}" ]]; then
    [[ "${NEXT_HIGH_VALUE_IT_CLEAN_CONFIRM:-}" == "YES" ]] || {
      echo "NEXT_HIGH_VALUE_IT_CLEAN_CONFIRM=YES is required for a caller-provided disposable database." >&2
      return 1
    }
  fi
  mvn -pl spring-ai-rag-core -am \
    -Dnext-high-value.it.enabled=true \
    "-Dtest=${selector}" \
    -Dsurefire.failIfNoSpecifiedTests=false test
  [[ -f "$report" ]] || {
    echo "Missing PostgreSQL acceptance report: $report" >&2
    return 1
  }
  IFS=, read -r tests failures errors skipped < <(surefire_counts "$report")
  if [[ "$tests" != "$expected" || "$failures" != 0 \
      || "$errors" != 0 || "$skipped" != 0 ]]; then
    echo "PostgreSQL acceptance must run all ${expected} named methods without failure/error/skip; got tests=${tests:-missing}, failures=${failures:-missing}, errors=${errors:-missing}, skipped=${skipped:-missing}" >&2
    return 1
  fi
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

playwright_checks() {
  VITE_PORT="$(find_port "$VITE_PORT")"
  (
    cd spring-ai-rag-webui
    exec ./node_modules/.bin/vite preview --host 127.0.0.1 \
      --port "$VITE_PORT" --strictPort
  ) >"$LOG_DIR/vite-preview.log" 2>&1 &
  VITE_PID=$!
  local attempt
  for attempt in $(seq 1 30); do
    if curl --fail --silent --show-error --connect-timeout 1 --max-time 2 \
        "http://127.0.0.1:${VITE_PORT}/webui/" >/dev/null 2>&1; then
      break
    fi
    kill -0 "$VITE_PID" >/dev/null 2>&1 || return 1
    [[ "$attempt" != "30" ]] || return 1
    sleep 1
  done
  (
    cd spring-ai-rag-webui
    BASE_URL="http://127.0.0.1:${VITE_PORT}" \
      npx playwright test "$PLAYWRIGHT_SPEC" --project=chromium
  )
}

cleanup() {
  if [[ -n "$VITE_PID" ]]; then
    kill "$VITE_PID" >/dev/null 2>&1 || true
    wait "$VITE_PID" >/dev/null 2>&1 || true
  fi
  {
    echo "# ${FEATURE} verification"
    echo
    echo "- Run: \`${RUN_ID}\`"
    echo "- Generated: \`$(date '+%Y-%m-%d %H:%M:%S %z')\`"
    echo "- Commit: \`$(git rev-parse --short HEAD)\`"
    echo "- Passed steps: **${PASS}**"
    echo
    echo "| Step | Status | Evidence |"
    echo "|------|--------|----------|"
    while IFS=$'\t' read -r name status evidence; do
      echo "| ${name} | ${status} | \`${evidence}\` |"
    done <"$LOG_DIR/summary.tsv"
  } >"$LOG_DIR/summary.md"
}
trap cleanup EXIT

for command_name in curl git java lsof mvn node npm npx; do
  command -v "$command_name" >/dev/null || {
    echo "Missing required command: $command_name" >&2
    exit 1
  }
done

run_step "No explicit pessimistic locks" ./scripts/verify-no-pessimistic-locks.sh
run_step "Focused HTTP tests" mvn -pl spring-ai-rag-core -am \
  "-Dtest=${HTTP_TESTS}" -Dsurefire.failIfNoSpecifiedTests=false test
run_step "Disposable PostgreSQL acceptance" postgres_tests
run_step "Maven clean compile test-compile" mvn clean compile test-compile
run_step "WebUI typecheck test build alignment" webui_checks
run_step "WebUI Mock Playwright" playwright_checks
run_step "Project documentation gate" ./scripts/verify-project-docs.sh
run_step "Git whitespace check" git diff --check

echo "${FEATURE} verification passed: ${PASS} steps"
echo "Summary: ${LOG_DIR}/summary.md"
