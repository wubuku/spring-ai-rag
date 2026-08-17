#!/usr/bin/env bash
# One-click verification for the controlled OpenAI Chat Completions adapter.
set -euo pipefail

cd "$(dirname "$0")/.."

RUN_ID="${OPENAI_COMPAT_VERIFY_RUN_ID:-$(date +%Y%m%d-%H%M%S)}"
LOG_DIR="${OPENAI_COMPAT_VERIFY_LOG_DIR:-.verification/openai-compatibility/${RUN_ID}}"
mkdir -p "$LOG_DIR"
: > "$LOG_DIR/summary.tsv"

PASS_COUNT=0

run_step() {
  local name="$1"
  shift
  local slug log_path
  slug="$(printf '%s' "$name" | tr '[:upper:] ' '[:lower:]-' | tr -cd 'a-z0-9._-')"
  log_path="$LOG_DIR/${slug}.log"
  echo
  echo "=== ${name} ==="
  set +e
  "$@" 2>&1 | tee "$log_path"
  local rc=${PIPESTATUS[0]}
  set -e
  if [[ "$rc" -ne 0 ]]; then
    printf '%s\tFAIL\t%s\n' "$name" "$log_path" >> "$LOG_DIR/summary.tsv"
    return "$rc"
  fi
  PASS_COUNT=$((PASS_COUNT + 1))
  printf '%s\tPASS\t%s\n' "$name" "$log_path" >> "$LOG_DIR/summary.tsv"
}

write_summary() {
  {
    echo "# OpenAI compatibility verification"
    echo
    echo "- Run: \`${RUN_ID}\`"
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

trap write_summary EXIT

run_step "OpenAI JSON SSE and scope contract tests" \
  mvn -pl spring-ai-rag-core -am \
    -Dtest=OpenAiModelAliasRegistryTest,OpenAiRequestRetrievalScopeAdapterTest,OpenAiChatRequestMapperTest,OpenAiCompatibilityControllerWebTest,RagWebSecurityConfigurationTest,ChatExecutionServiceTest \
    -Dsurefire.failIfNoSpecifiedTests=false test
run_step "Maven test compile" \
  mvn -pl spring-ai-rag-core -am test-compile -DskipTests
run_step "Shell syntax" bash -n scripts/verify-openai-compatibility.sh
run_step "Git whitespace" git diff --check

echo
echo "OpenAI compatibility verification passed: ${PASS_COUNT} steps"
echo "Summary: ${LOG_DIR}/summary.md"
