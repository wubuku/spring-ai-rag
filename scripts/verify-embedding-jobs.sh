#!/usr/bin/env bash
# One-click verification for persistent embedding/reindex jobs.
set -euo pipefail

cd "$(dirname "$0")/.."

RUN_ID="${EMBEDDING_JOBS_VERIFY_RUN_ID:-$(date +%Y%m%d-%H%M%S)}"
LOG_DIR="${EMBEDDING_JOBS_VERIFY_LOG_DIR:-.verification/embedding-jobs/${RUN_ID}}"
PG_IMAGE="${TESTCONTAINERS_PG_IMAGE:-pgvector/pgvector:pg16}"
PG_JDBC_URL="${EMBEDDING_JOBS_IT_JDBC_URL:-}"
PG_USERNAME="${EMBEDDING_JOBS_IT_USERNAME:-postgres}"
PG_PASSWORD="${EMBEDDING_JOBS_IT_PASSWORD:-postgres}"
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
    echo "# Embedding jobs verification"
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
    echo "Docker is required unless EMBEDDING_JOBS_IT_JDBC_URL is set." >&2
    return 1
  }
  CONTAINER_NAME="spring-ai-rag-embedding-jobs-${RUN_ID}-$$"
  docker run -d --rm \
    --name "$CONTAINER_NAME" \
    -e POSTGRES_DB=spring_ai_rag_embedding_jobs_test \
    -e POSTGRES_USER="$PG_USERNAME" \
    -e POSTGRES_PASSWORD="$PG_PASSWORD" \
    -p 127.0.0.1::5432 \
    "$PG_IMAGE" >/dev/null
  local attempt port
  for attempt in $(seq 1 30); do
    if docker exec "$CONTAINER_NAME" \
        pg_isready -U "$PG_USERNAME" \
        -d spring_ai_rag_embedding_jobs_test >/dev/null 2>&1; then
      break
    fi
    if [[ "$attempt" == "30" ]]; then
      echo "PostgreSQL did not become ready." >&2
      return 1
    fi
    sleep 1
  done
  port="$(docker port "$CONTAINER_NAME" 5432/tcp | sed 's/.*://')"
  PG_JDBC_URL="jdbc:postgresql://127.0.0.1:${port}/spring_ai_rag_embedding_jobs_test"
}

focused_tests() {
  mvn -pl spring-ai-rag-core -am \
    -Dtest=EmbeddingJobServiceTest,EmbeddingJobWorkerTest,EmbeddingJobControllerWebTest,DocumentEmbedServiceTest \
    -Dsurefire.failIfNoSpecifiedTests=false test
}

postgres_tests() {
  mvn -pl spring-ai-rag-core -am \
    -Dtest=EmbeddingJobsPostgresIntegrationTest \
    -Dsurefire.failIfNoSpecifiedTests=false \
    -Dembedding-jobs.it.enabled=true \
    "-Dembedding-jobs.it.jdbc-url=${PG_JDBC_URL}" \
    "-Dembedding-jobs.it.username=${PG_USERNAME}" \
    "-Dembedding-jobs.it.password=${PG_PASSWORD}" \
    test
}

run_step "Focused job service worker and HTTP tests" focused_tests
run_step "Isolated PostgreSQL startup" start_postgres
run_step "PostgreSQL V1-V39 job integration tests (V33 contracts)" postgres_tests
run_step "Maven test compile" \
  mvn -pl spring-ai-rag-core -am test-compile -DskipTests
run_step "Shell syntax" bash -n scripts/verify-embedding-jobs.sh
run_step "Git whitespace" git diff --check

echo
echo "Embedding jobs verification passed: ${PASS_COUNT} steps"
echo "Summary: ${LOG_DIR}/summary.md"
