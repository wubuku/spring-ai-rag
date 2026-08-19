#!/usr/bin/env bash
# 文档 CRUD、派生索引一致性和外部同步 client 的一键验收。
set -euo pipefail

cd "$(dirname "$0")/.."

RUN_ID="${DOCUMENT_LIFECYCLE_VERIFY_RUN_ID:-$(date +%Y%m%d-%H%M%S)}"
LOG_DIR="${DOCUMENT_LIFECYCLE_VERIFY_LOG_DIR:-.verification/document-data-plane/${RUN_ID}}"
PLAYWRIGHT_PORT="${DOCUMENT_LIFECYCLE_PLAYWRIGHT_PORT:-4176}"
BACKEND_PORT="${DOCUMENT_LIFECYCLE_BACKEND_PORT:-4186}"
PG_IMAGE="${DOCUMENT_LIFECYCLE_PG_IMAGE:-${TESTCONTAINERS_PG_IMAGE:-pgvector/pgvector:pg16}}"
MIRROR_BASE_URL="${MIRROR_BASE_URL:-docker.m.daocloud.io}"

LIFECYCLE_JDBC_URL="${DOCUMENT_LIFECYCLE_IT_JDBC_URL:-}"
LIFECYCLE_USERNAME="${DOCUMENT_LIFECYCLE_IT_USERNAME:-}"
LIFECYCLE_PASSWORD="${DOCUMENT_LIFECYCLE_IT_PASSWORD:-}"
TEMP_DATABASE=""
POSTGRES_CONTAINER=""
PLAYWRIGHT_PREVIEW_PID=""
BACKEND_PID=""
REFERENCE_CLIENT_TEMP_DIR=""
STEP_INDEX=0
PASS_COUNT=0

usage() {
  cat <<'EOF'
Usage: ./scripts/verify-document-lifecycle.sh

Runs, in order:
  no-pessimistic-lock gate
  focused lifecycle/controller/service tests
  Flyway V39 -> V41 and lifecycle consistency tests on disposable PostgreSQL
  reference client HTTP/retry/checkpoint tests
  reference client against the real Spring Boot HTTP and PostgreSQL data path
  mvn clean compile test-compile and full backend tests
  WebUI Vitest, production build, alignment gate and Mock Playwright documents suite
  project documentation gate and git diff --check

PostgreSQL selection:
  1. DOCUMENT_LIFECYCLE_IT_JDBC_URL (must point to a disposable database)
  2. create a disposable database using POSTGRES_* from the current shell or .env
  3. start a disposable pgvector Docker container

Environment:
  DOCUMENT_LIFECYCLE_VERIFY_LOG_DIR  Verification artifact directory
  DOCUMENT_LIFECYCLE_PLAYWRIGHT_PORT Preferred Vite preview port
  DOCUMENT_LIFECYCLE_BACKEND_PORT    Preferred Spring Boot E2E port
  DOCUMENT_LIFECYCLE_IT_JDBC_URL     Caller-provided disposable JDBC URL
  DOCUMENT_LIFECYCLE_IT_USERNAME     Disposable database username
  DOCUMENT_LIFECYCLE_IT_PASSWORD     Disposable database password
  DOCUMENT_LIFECYCLE_PG_IMAGE        Docker fallback image
  MIRROR_BASE_URL                    Mainland-China registry mirror prefix
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

mkdir -p "$LOG_DIR"
: > "$LOG_DIR/summary.tsv"

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

check_prerequisites() {
  local command_name
  for command_name in bash curl git java mvn node npm npx python3 rg; do
    command -v "$command_name" >/dev/null || {
      echo "Missing required command: ${command_name}" >&2
      return 1
    }
  done
}

load_local_env() {
  [[ -f .env ]] || return 0

  local explicit_url="$LIFECYCLE_JDBC_URL"
  local explicit_username="$LIFECYCLE_USERNAME"
  local explicit_password="$LIFECYCLE_PASSWORD"

  set -a
  # shellcheck disable=SC1091
  source .env
  set +a

  [[ -n "$explicit_url" ]] && LIFECYCLE_JDBC_URL="$explicit_url"
  [[ -n "$explicit_username" ]] && LIFECYCLE_USERNAME="$explicit_username"
  [[ -n "$explicit_password" ]] && LIFECYCLE_PASSWORD="$explicit_password"
}

postgres_ready() {
  local host="${POSTGRES_HOST:-127.0.0.1}"
  local port="${POSTGRES_PORT:-5432}"
  local username="${POSTGRES_USER:-postgres}"
  local database="${POSTGRES_DATABASE:-postgres}"
  PGPASSWORD="${POSTGRES_PASSWORD:-}" \
    psql -h "$host" -p "$port" -U "$username" -d "$database" \
      -v ON_ERROR_STOP=1 -Atqc "SELECT 1" 2>/dev/null \
    | grep -qx 1
}

create_local_disposable_database() {
  command -v psql >/dev/null || return 1
  command -v createdb >/dev/null || return 1
  command -v dropdb >/dev/null || return 1
  postgres_ready || return 1

  local host="${POSTGRES_HOST:-127.0.0.1}"
  local port="${POSTGRES_PORT:-5432}"
  local username="${POSTGRES_USER:-postgres}"
  local password="${POSTGRES_PASSWORD:-}"
  local safe_run_id
  safe_run_id="$(printf '%s' "$RUN_ID" | tr -cd 'a-zA-Z0-9_' | tr '[:upper:]' '[:lower:]')"
  TEMP_DATABASE="spring_ai_rag_lifecycle_${safe_run_id}_$$"

  PGPASSWORD="$password" \
    createdb -h "$host" -p "$port" -U "$username" "$TEMP_DATABASE"

  LIFECYCLE_JDBC_URL="jdbc:postgresql://${host}:${port}/${TEMP_DATABASE}"
  LIFECYCLE_USERNAME="$username"
  LIFECYCLE_PASSWORD="$password"
  echo "Created disposable local PostgreSQL database: ${TEMP_DATABASE}"
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

start_docker_postgres() {
  command -v docker >/dev/null || {
    echo "No reachable local PostgreSQL and Docker is unavailable." >&2
    return 1
  }

  local image
  image="$(resolve_docker_image)"
  LIFECYCLE_USERNAME="${LIFECYCLE_USERNAME:-postgres}"
  LIFECYCLE_PASSWORD="${LIFECYCLE_PASSWORD:-postgres}"
  POSTGRES_CONTAINER="spring-ai-rag-lifecycle-${RUN_ID}-$$"

  docker run -d --rm \
    --name "$POSTGRES_CONTAINER" \
    -e POSTGRES_DB=spring_ai_rag_document_lifecycle_test \
    -e POSTGRES_USER="$LIFECYCLE_USERNAME" \
    -e POSTGRES_PASSWORD="$LIFECYCLE_PASSWORD" \
    -p 127.0.0.1::5432 \
    "$image" >/dev/null

  local attempt port
  for attempt in $(seq 1 30); do
    if docker exec "$POSTGRES_CONTAINER" \
        pg_isready -U "$LIFECYCLE_USERNAME" \
        -d spring_ai_rag_document_lifecycle_test >/dev/null 2>&1; then
      break
    fi
    if [[ "$attempt" == "30" ]]; then
      echo "Disposable PostgreSQL container did not become ready." >&2
      return 1
    fi
    sleep 1
  done

  port="$(docker port "$POSTGRES_CONTAINER" 5432/tcp | sed 's/.*://')"
  LIFECYCLE_JDBC_URL="jdbc:postgresql://127.0.0.1:${port}/spring_ai_rag_document_lifecycle_test"
  echo "Started disposable PostgreSQL container using ${image}."
}

prepare_postgres() {
  load_local_env
  if [[ -n "$LIFECYCLE_JDBC_URL" ]]; then
    [[ -n "$LIFECYCLE_USERNAME" ]] || {
      echo "DOCUMENT_LIFECYCLE_IT_USERNAME is required with an external JDBC URL." >&2
      return 1
    }
    echo "Using caller-provided disposable PostgreSQL database."
    return
  fi

  if create_local_disposable_database; then
    return
  fi
  start_docker_postgres
}

focused_backend_tests() {
  mvn -pl spring-ai-rag-core -am \
    -Dtest=DocumentLifecycleControllerWebTest,DocumentMutationServiceTest,ExternalDocumentControllerWebTest,RagDocumentControllerTest,RagJsonRecordControllerWebTest,JsonRecordServiceTest,BatchDocumentServiceTest,EmbeddingJobServiceTest,RagCollectionControllerTest \
    -Dsurefire.failIfNoSpecifiedTests=false \
    test
}

postgres_lifecycle_tests() {
  DOCUMENT_LIFECYCLE_IT_JDBC_URL="$LIFECYCLE_JDBC_URL" \
  DOCUMENT_LIFECYCLE_IT_USERNAME="$LIFECYCLE_USERNAME" \
  DOCUMENT_LIFECYCLE_IT_PASSWORD="$LIFECYCLE_PASSWORD" \
  DOCUMENT_LIFECYCLE_IT_CLEAN_CONFIRM=YES \
    mvn -pl spring-ai-rag-core -am \
      -Dtest=DocumentLifecyclePostgresIntegrationTest \
      -Dsurefire.failIfNoSpecifiedTests=false \
      -Ddocument-lifecycle.it.enabled=true \
      test

  python3 - <<'PY'
from pathlib import Path
import xml.etree.ElementTree as ET

report = Path(
    "spring-ai-rag-core/target/surefire-reports/"
    "TEST-com.springairag.core.integration."
    "DocumentLifecyclePostgresIntegrationTest.xml"
)
if not report.is_file():
    raise SystemExit(f"Missing Surefire report: {report}")
root = ET.parse(report).getroot()
tests = int(root.attrib.get("tests", "0"))
failures = int(root.attrib.get("failures", "0"))
errors = int(root.attrib.get("errors", "0"))
skipped = int(root.attrib.get("skipped", "0"))
if tests < 8 or failures or errors or skipped:
    raise SystemExit(
        "Lifecycle PostgreSQL acceptance did not fully execute: "
        f"tests={tests}, failures={failures}, errors={errors}, skipped={skipped}"
    )
print(
    "Lifecycle PostgreSQL acceptance fully executed: "
    f"tests={tests}, failures=0, errors=0, skipped=0"
)
PY
}

reference_client_tests() {
  python3 -m unittest discover \
    -s examples/external-sync-client \
    -p 'test_*.py' \
    -v
}

cleanup_reference_client_e2e() {
  if [[ -n "$BACKEND_PID" ]]; then
    kill "$BACKEND_PID" >/dev/null 2>&1 || true
    wait "$BACKEND_PID" >/dev/null 2>&1 || true
    BACKEND_PID=""
  fi
  if [[ -n "$REFERENCE_CLIENT_TEMP_DIR" ]]; then
    rm -rf "$REFERENCE_CLIENT_TEMP_DIR"
    REFERENCE_CLIENT_TEMP_DIR=""
  fi
}

reference_client_real_http_e2e() {
  local backend_log="$LOG_DIR/reference-client-backend.log"
  local classpath_file="$LOG_DIR/reference-client-runtime-classpath.txt"
  local runtime_classpath
  local collection_key="lifecycle-client:${RUN_ID}:$$"
  local source_namespace="verification-client"
  local external_id="document:1"
  local events_path
  local checkpoint_path
  local response_path
  local health=""

  cleanup_reference_client_e2e
  BACKEND_PORT="$(find_available_port "$BACKEND_PORT")"
  REFERENCE_CLIENT_TEMP_DIR="$(
    mktemp -d "${TMPDIR:-/tmp}/spring-ai-rag-lifecycle-client.XXXXXX"
  )"
  chmod 700 "$REFERENCE_CLIENT_TEMP_DIR"
  events_path="$REFERENCE_CLIENT_TEMP_DIR/events.jsonl"
  checkpoint_path="$REFERENCE_CLIENT_TEMP_DIR/checkpoint.sqlite3"
  response_path="$REFERENCE_CLIENT_TEMP_DIR/document.json"

  python3 - "$events_path" "$collection_key" "$source_namespace" "$external_id" <<'PY'
import json
import sys

path, collection_key, source_namespace, external_id = sys.argv[1:]
events = [
    {
        "eventId": "verification-create-r1",
        "operation": "UPSERT",
        "collectionKey": collection_key,
        "sourceNamespace": source_namespace,
        "externalId": external_id,
        "sourceRevision": "r1",
        "title": "Reference client verification",
        "content": "Initial lifecycle verification content.",
        "source": "verification",
        "documentType": "text",
        "metadata": {"stage": "create"},
        "embeddingPolicy": "SKIP",
    },
    {
        "eventId": "verification-update-r2",
        "operation": "UPSERT",
        "collectionKey": collection_key,
        "sourceNamespace": source_namespace,
        "externalId": external_id,
        "sourceRevision": "r2",
        "expectedSourceRevision": "r1",
        "title": "Reference client verification updated",
        "content": "Updated lifecycle verification content.",
        "source": "verification",
        "documentType": "text",
        "metadata": {"stage": "update"},
        "embeddingPolicy": "SKIP",
    },
    {
        "eventId": "verification-delete-r3",
        "operation": "TOMBSTONE",
        "collectionKey": collection_key,
        "sourceNamespace": source_namespace,
        "externalId": external_id,
        "sourceRevision": "r3",
        "expectedSourceRevision": "r2",
    },
]
with open(path, "w", encoding="utf-8") as stream:
    for event in events:
        stream.write(json.dumps(event, ensure_ascii=True, separators=(",", ":")))
        stream.write("\n")
PY

  mvn -pl spring-ai-rag-core -am -q dependency:build-classpath \
    "-Dmdep.outputFile=${PWD}/${classpath_file}" \
    -DincludeScope=runtime

  runtime_classpath="spring-ai-rag-core/target/classes:"
  runtime_classpath+="spring-ai-rag-api/target/classes:"
  runtime_classpath+="spring-ai-rag-documents/target/classes:"
  runtime_classpath+="spring-ai-rag-starter/target/classes:"
  runtime_classpath+="$(cat "$classpath_file")"

  env \
    SPRING_PROFILES_ACTIVE=postgresql \
    SERVER_PORT="$BACKEND_PORT" \
    SPRING_DATASOURCE_URL="$LIFECYCLE_JDBC_URL" \
    SPRING_DATASOURCE_USERNAME="$LIFECYCLE_USERNAME" \
    SPRING_DATASOURCE_PASSWORD="$LIFECYCLE_PASSWORD" \
    RAG_SECURITY_ENABLED=false \
    RAG_ROOT_API_KEY= \
    RAG_EMBEDDING_JOBS_ENABLED=false \
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
  BACKEND_PID=$!

  local attempt
  for attempt in $(seq 1 90); do
    if ! kill -0 "$BACKEND_PID" >/dev/null 2>&1; then
      echo "Backend exited before readiness; see ${backend_log}." >&2
      tail -80 "$backend_log" >&2 || true
      cleanup_reference_client_e2e
      return 1
    fi
    health="$(
      curl --fail --silent --show-error --connect-timeout 1 --max-time 2 \
        "http://127.0.0.1:${BACKEND_PORT}/actuator/health" 2>/dev/null || true
    )"
    if [[ "$health" == *'"status":"UP"'* ]]; then
      break
    fi
    if [[ "$attempt" == "90" ]]; then
      echo "Backend did not become healthy; last payload: ${health}" >&2
      tail -80 "$backend_log" >&2 || true
      cleanup_reference_client_e2e
      return 1
    fi
    sleep 1
  done

  curl --fail --silent --show-error \
    -H 'Content-Type: application/json' \
    -X POST \
    --data "$(
      python3 - "$collection_key" <<'PY'
import json
import sys
print(json.dumps({
    "collectionKey": sys.argv[1],
    "name": "Reference client verification",
    "dimensions": 1024,
}))
PY
    )" \
    "http://127.0.0.1:${BACKEND_PORT}/api/v1/rag/collections" \
    >/dev/null

  RAG_API_KEY=verification-only \
    python3 examples/external-sync-client/sync_client.py apply-events \
      --events "$events_path" \
      --checkpoint "$checkpoint_path" \
      --base-url "http://127.0.0.1:${BACKEND_PORT}" \
      --max-retries 0

  local query
  query="$(
    python3 - "$collection_key" "$source_namespace" "$external_id" <<'PY'
import sys
import urllib.parse
print(urllib.parse.urlencode({
    "collectionKey": sys.argv[1],
    "sourceNamespace": sys.argv[2],
    "externalId": sys.argv[3],
}))
PY
  )"
  curl --fail --silent --show-error \
    "http://127.0.0.1:${BACKEND_PORT}/api/v1/rag/documents/by-external-id?${query}" \
    >"$response_path"

  python3 - "$response_path" "$collection_key" "$source_namespace" "$external_id" <<'PY'
import json
import sys

path, collection_key, source_namespace, external_id = sys.argv[1:]
with open(path, encoding="utf-8") as stream:
    document = json.load(stream)

expected = {
    "collectionKey": collection_key,
    "sourceNamespace": source_namespace,
    "externalId": external_id,
    "sourceRevision": "r3",
    "enabled": False,
    "documentRevision": 3,
}
for field, value in expected.items():
    if document.get(field) != value:
        raise SystemExit(
            f"Unexpected final document {field}: "
            f"expected={value!r}, actual={document.get(field)!r}"
        )
if not document.get("sourceDeletedAt"):
    raise SystemExit("Final external document is missing sourceDeletedAt")
print(
    "Real HTTP reference-client lifecycle passed: "
    "sourceRevision=r3, enabled=false, documentRevision=3"
)
PY

  cleanup_reference_client_e2e
}

backend_compile() {
  mvn clean compile test-compile
}

backend_full_tests() {
  mvn test
}

webui_unit_build_alignment() {
  (
    cd spring-ai-rag-webui
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

cleanup_playwright_preview() {
  if [[ -n "$PLAYWRIGHT_PREVIEW_PID" ]]; then
    kill "$PLAYWRIGHT_PREVIEW_PID" >/dev/null 2>&1 || true
    wait "$PLAYWRIGHT_PREVIEW_PID" >/dev/null 2>&1 || true
    PLAYWRIGHT_PREVIEW_PID=""
  fi
}

webui_mock_playwright() {
  local preview_log="$LOG_DIR/playwright-preview.log"
  PLAYWRIGHT_PORT="$(find_available_port "$PLAYWRIGHT_PORT")"

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
      echo "Vite preview exited before readiness; see ${preview_log}." >&2
      return 1
    fi
    if curl --fail --silent --show-error --connect-timeout 1 --max-time 2 \
        "http://127.0.0.1:${PLAYWRIGHT_PORT}/webui/" \
        | grep -Fq "<title>spring-ai-rag WebUI</title>"; then
      break
    fi
    if [[ "$attempt" == "30" ]]; then
      echo "Vite preview did not become ready; see ${preview_log}." >&2
      return 1
    fi
    sleep 1
  done

  local rc=0
  (
    cd spring-ai-rag-webui
    BASE_URL="http://127.0.0.1:${PLAYWRIGHT_PORT}" \
      npx playwright test e2e/documents.spec.ts
  ) || rc=$?
  cleanup_playwright_preview
  return "$rc"
}

write_summary() {
  {
    echo "# Document lifecycle verification"
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
    done < "$LOG_DIR/summary.tsv"
  } > "$LOG_DIR/summary.md"
}

cleanup() {
  cleanup_playwright_preview
  cleanup_reference_client_e2e
  if [[ -n "$TEMP_DATABASE" ]]; then
    PGPASSWORD="${POSTGRES_PASSWORD:-}" \
      dropdb \
        -h "${POSTGRES_HOST:-127.0.0.1}" \
        -p "${POSTGRES_PORT:-5432}" \
        -U "${POSTGRES_USER:-postgres}" \
        --if-exists \
        "$TEMP_DATABASE" >/dev/null 2>&1 || true
  fi
  if [[ -n "$POSTGRES_CONTAINER" ]]; then
    docker stop "$POSTGRES_CONTAINER" >/dev/null 2>&1 || true
  fi
  write_summary
}

trap cleanup EXIT

run_step "Prerequisites" check_prerequisites
run_step "No explicit pessimistic locks" ./scripts/verify-no-pessimistic-locks.sh
run_step "Focused document lifecycle tests" focused_backend_tests
run_step "Disposable PostgreSQL preparation" prepare_postgres
run_step "V39 to V41 lifecycle PostgreSQL acceptance" postgres_lifecycle_tests
run_step "External sync reference client tests" reference_client_tests
run_step "External sync client real Spring Boot HTTP E2E" reference_client_real_http_e2e
run_step "Maven clean compile test-compile" backend_compile
run_step "Full backend test suite" backend_full_tests
run_step "WebUI Vitest build and alignment" webui_unit_build_alignment
run_step "WebUI Mock Playwright documents suite" webui_mock_playwright
run_step "Project documentation gates" ./scripts/verify-project-docs.sh
run_step "Git whitespace check" git diff --check

echo
echo "Document lifecycle verification passed: ${PASS_COUNT} steps"
echo "Summary: ${LOG_DIR}/summary.md"
