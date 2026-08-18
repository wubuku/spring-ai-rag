#!/usr/bin/env bash
# One-click verification for retrieval diagnostics (Batch A).
set -euo pipefail

cd "$(dirname "$0")/.."

RUN_ID="${RETRIEVAL_DIAGNOSTICS_VERIFY_RUN_ID:-$(date +%Y%m%d-%H%M%S)}"
LOG_DIR="${RETRIEVAL_DIAGNOSTICS_VERIFY_LOG_DIR:-.verification/retrieval-diagnostics/${RUN_ID}}"
PG_IMAGE="${TESTCONTAINERS_PG_IMAGE:-pgvector/pgvector:pg16}"
PG_JDBC_URL="${RETRIEVAL_DIAGNOSTICS_IT_JDBC_URL:-}"
PG_USERNAME="${RETRIEVAL_DIAGNOSTICS_IT_USERNAME:-postgres}"
PG_PASSWORD="${RETRIEVAL_DIAGNOSTICS_IT_PASSWORD:-postgres}"
CONTAINER_NAME=""
mkdir -p "$LOG_DIR"
: > "$LOG_DIR/summary.tsv"

PASS_COUNT=0

cleanup() {
  if [[ -n "$CONTAINER_NAME" ]]; then
    docker stop "$CONTAINER_NAME" >/dev/null 2>&1 || true
  fi
}

write_summary() {
  {
    echo "# Retrieval diagnostics verification"
    echo
    echo "- Run: \`${RUN_ID}\`"
    echo "- Commit: \`$(git rev-parse --short HEAD)\`"
    echo "- Passed steps: **${PASS_COUNT}**"
    echo "- PostgreSQL image: \`${PG_IMAGE}\`"
    echo
    echo "| Step | Status | Evidence |"
    echo "|------|--------|----------|"
    while IFS=$'\t' read -r name status evidence; do
      echo "| ${name} | ${status} | \`${evidence}\` |"
    done < "$LOG_DIR/summary.tsv"
  } > "$LOG_DIR/summary.md"
  python3 - <<PY
import json
from pathlib import Path
log_dir = Path("$LOG_DIR")
rows = []
for line in (log_dir / "summary.tsv").read_text().splitlines():
    name, status, evidence = line.split("\t")
    rows.append({"name": name, "status": status, "evidence": evidence})
(log_dir / "summary.json").write_text(json.dumps({
    "runId": "$RUN_ID",
    "passed": $PASS_COUNT,
    "steps": rows
}, indent=2) + "\n")
PY
}

finish() {
  write_summary
  cleanup
}
trap finish EXIT

run_step() {
  local name="$1"
  shift
  local slug log_path
  slug="$(printf '%s' "$name" | tr '[:upper:] ' '[:lower:]-' | tr -cd 'a-z0-9._-')"
  log_path="$LOG_DIR/${slug}.log"
  echo
  echo "=== ${name} ==="
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

start_postgres() {
  if [[ -n "$PG_JDBC_URL" ]]; then
    echo "Using caller-provided PostgreSQL URL."
    return
  fi
  command -v docker >/dev/null || {
    echo "Docker is required unless RETRIEVAL_DIAGNOSTICS_IT_JDBC_URL is set." >&2
    return 1
  }
  CONTAINER_NAME="spring-ai-rag-retrieval-diagnostics-${RUN_ID}-$$"
  docker run -d --rm \
    --name "$CONTAINER_NAME" \
    -e POSTGRES_DB=spring_ai_rag_retrieval_diagnostics_test \
    -e POSTGRES_USER="$PG_USERNAME" \
    -e POSTGRES_PASSWORD="$PG_PASSWORD" \
    -p 127.0.0.1::5432 \
    "$PG_IMAGE" >/dev/null
  local attempt port
  for attempt in $(seq 1 30); do
    if docker exec "$CONTAINER_NAME" \
        pg_isready -U "$PG_USERNAME" \
        -d spring_ai_rag_retrieval_diagnostics_test >/dev/null 2>&1; then
      break
    fi
    if [[ "$attempt" == "30" ]]; then
      echo "PostgreSQL did not become ready." >&2
      return 1
    fi
    sleep 1
  done
  port="$(docker port "$CONTAINER_NAME" 5432/tcp | sed 's/.*://')"
  PG_JDBC_URL="jdbc:postgresql://127.0.0.1:${port}/spring_ai_rag_retrieval_diagnostics_test"
}

focused_tests() {
  mvn -pl spring-ai-rag-core -am \
    -Dtest=RetrievalOutcomeCodesTest,RetrievalDiagnosticsServiceTest,HybridRetrieverServiceTest,HybridRetrieverOutcomeTest,PgTrgmFulltextProviderTest,RetrievalScopeSummaryTest,ProjectRerankPostProcessorTest,RagSearchControllerTest,KnowledgeSearchToolTest,JsonRecordSearchToolTest \
    -Dsurefire.failIfNoSpecifiedTests=false test
}

postgres_tests() {
  mvn -pl spring-ai-rag-core -am \
    -Dtest=RetrievalDiagnosticsPostgresIntegrationTest \
    -Dsurefire.failIfNoSpecifiedTests=false \
    -Dretrieval-diagnostics.it.enabled=true \
    "-Dretrieval-diagnostics.it.jdbc-url=${PG_JDBC_URL}" \
    "-Dretrieval-diagnostics.it.username=${PG_USERNAME}" \
    "-Dretrieval-diagnostics.it.password=${PG_PASSWORD}" \
    test
}

run_step "Focused diagnostics unit and controller tests" focused_tests
run_step "Isolated PostgreSQL startup" start_postgres
run_step "V1-V39 PostgreSQL diagnostics integration (V35 contracts)" postgres_tests

echo
echo "Verification artifacts: ${LOG_DIR}/summary.md"
