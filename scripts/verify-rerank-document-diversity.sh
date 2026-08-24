#!/usr/bin/env bash
# One-click acceptance for rerank document-level evidence diversification.
set -uo pipefail

cd "$(dirname "$0")/.."

RUN_ID="${RERANK_DIVERSITY_RUN_ID:-$(date +%Y%m%d-%H%M%S)}"
LOG_DIR="${RERANK_DIVERSITY_LOG_DIR:-.verification/rerank-document-diversity/${RUN_ID}}"
ENV_FILE="${RERANK_DIVERSITY_ENV_FILE:-.env}"
BACKEND_PORT="${RERANK_DIVERSITY_BACKEND_PORT:-18083}"
FRONTEND_PORT="${RERANK_DIVERSITY_FRONTEND_PORT:-15175}"
MOCK_PLAYWRIGHT_PORT="${RERANK_DIVERSITY_MOCK_PORT:-4199}"
POSTGRES_IMAGE="${RERANK_DIVERSITY_POSTGRES_IMAGE:-pgvector/pgvector:pg16}"
TESTCONTAINERS_API_VERSION="${TESTCONTAINERS_API_VERSION:-1.40}"
TESTCONTAINERS_RYUK_DISABLED="${TESTCONTAINERS_RYUK_DISABLED:-true}"
SEARCH_SAMPLE_COUNT="${RERANK_DIVERSITY_SEARCH_SAMPLES:-20}"
CHAT_SAMPLE_COUNT="${RERANK_DIVERSITY_CHAT_SAMPLES:-5}"
CHAT_MAX_ATTEMPTS="${RERANK_DIVERSITY_CHAT_MAX_ATTEMPTS:-2}"
METRICS_SCRIPT="./scripts/rerank-document-diversity-metrics.py"

PASS_COUNT=0
FAIL_COUNT=0
SKIP_COUNT=0
STEP_INDEX=0
MOCK_PREVIEW_PID=""
REAL_STACK_OWNED=0
REAL_STACK_READY=0
DATABASE_KIND=""
DATABASE_NAME=""
DATABASE_HOST=""
DATABASE_PORT=""
DATABASE_USER=""
DATABASE_PASSWORD=""
DATABASE_ADMIN=""
DATABASE_CONTAINER=""
ENV_OVERLAY=""
ROOT_API_KEY=""

mkdir -p "$LOG_DIR"
LOG_DIR="$(cd "$LOG_DIR" && pwd -P)"
FIXTURE_FILE="${LOG_DIR}/fixture.json"
RUNTIME_COMPARISON_JSON="${LOG_DIR}/runtime-comparison.json"
RUNTIME_COMPARISON_MARKDOWN="${LOG_DIR}/runtime-comparison.md"
: >"$LOG_DIR/summary.tsv"

slugify() {
  printf '%s' "$1" \
    | tr '[:upper:] ' '[:lower:]-' \
    | tr -cd 'a-z0-9._-'
}

record() {
  local name="$1" status="$2" exit_code="$3" evidence="$4"
  printf '%s|%s|%s|%s\n' "$name" "$status" "$exit_code" "$evidence" \
    >>"$LOG_DIR/summary.tsv"
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
  return "$rc"
}

skip_step() {
  local name="$1" reason="$2"
  echo "SKIP: ${name} (${reason})"
  record "$name" SKIP 0 "$reason"
}

require_commands() {
  local command_name
  for command_name in bash curl git java lsof mvn node npm npx openssl python3 rg; do
    command -v "$command_name" >/dev/null || {
      echo "Missing required command: ${command_name}" >&2
      return 1
    }
  done
  [[ -f "$ENV_FILE" ]] || {
    echo "Environment file does not exist: ${ENV_FILE}" >&2
    return 1
  }
  [[ -x "$METRICS_SCRIPT" ]] || {
    echo "Runtime metrics helper is not executable: ${METRICS_SCRIPT}" >&2
    return 1
  }
  [[ "$SEARCH_SAMPLE_COUNT" =~ ^[1-9][0-9]*$ ]] || {
    echo "RERANK_DIVERSITY_SEARCH_SAMPLES must be positive." >&2
    return 1
  }
  [[ "$CHAT_SAMPLE_COUNT" =~ ^[1-9][0-9]*$ ]] || {
    echo "RERANK_DIVERSITY_CHAT_SAMPLES must be positive." >&2
    return 1
  }
  [[ "$CHAT_MAX_ATTEMPTS" =~ ^[1-9][0-9]*$ ]] || {
    echo "RERANK_DIVERSITY_CHAT_MAX_ATTEMPTS must be positive." >&2
    return 1
  }
  bash -n "$ENV_FILE"
}

focused_backend_tests() {
  mvn -pl spring-ai-rag-core -am \
    -Dtest='RagRerankPropertiesTest,ReRankingServiceTest,RerankResultSelectorTest,HeuristicRerankProviderTest,HttpRerankProviderTest,RerankProviderFactoryTest,RagSearchControllerTest,ProjectRerankPostProcessorTest,KnowledgeSearchToolTest,RetrievalTraceCollectorTest,EvaluationCaseExecutorTest,JsonRecordServiceTest,RerankAdvisorTest,AdvisorChainIntegrationTest' \
    -Dsurefire.failIfNoSpecifiedTests=false \
    test
}

postgres_integration_tests() {
  TESTCONTAINERS_RYUK_DISABLED="$TESTCONTAINERS_RYUK_DISABLED" \
    DOCKER_API_VERSION="${DOCKER_API_VERSION:-$TESTCONTAINERS_API_VERSION}" \
    mvn -pl spring-ai-rag-core -am \
      -Dhybrid-rrf.it.enabled=true \
      -Dtest=HybridRetrieverRrfPostgresIntegrationTest \
      -Dsurefire.failIfNoSpecifiedTests=false \
      test
}

maven_compile() {
  mvn clean compile test-compile
}

webui_typecheck() {
  (cd spring-ai-rag-webui && npm run typecheck)
}

webui_unit() {
  (cd spring-ai-rag-webui && npm run test:run)
}

webui_build() {
  (cd spring-ai-rag-webui && npm run build)
}

webui_alignment() {
  (cd spring-ai-rag-webui && npm run check:alignment)
}

mock_playwright() {
  local preview_log="$LOG_DIR/mock-playwright-preview.log"
  (
    cd spring-ai-rag-webui
    exec npx vite preview \
      --host 127.0.0.1 \
      --port "$MOCK_PLAYWRIGHT_PORT" \
      --strictPort
  ) >"$preview_log" 2>&1 &
  MOCK_PREVIEW_PID=$!

  local attempt
  for attempt in $(seq 1 45); do
    if ! kill -0 "$MOCK_PREVIEW_PID" >/dev/null 2>&1; then
      echo "Vite preview exited before readiness; see ${preview_log}" >&2
      return 1
    fi
    if curl --noproxy '*' -fsS --connect-timeout 1 --max-time 2 \
      "http://127.0.0.1:${MOCK_PLAYWRIGHT_PORT}/webui/" >/dev/null 2>&1; then
      break
    fi
    if [[ "$attempt" == "45" ]]; then
      echo "Vite preview did not become ready; see ${preview_log}" >&2
      return 1
    fi
    sleep 1
  done

  (
    cd spring-ai-rag-webui
    BASE_URL="http://127.0.0.1:${MOCK_PLAYWRIGHT_PORT}" \
      npx playwright test \
        e2e/search.spec.ts \
        e2e/chat.spec.ts \
        e2e/navigation.spec.ts \
        --project=chromium
  )
}

load_environment() {
  set +u
  set -a
  # shellcheck disable=SC1090
  source "$ENV_FILE"
  set +a
  set -u

  ROOT_API_KEY="$(openssl rand -hex 32)"
  [[ "${#ROOT_API_KEY}" -ge 32 ]]
}

assert_isolated_dev_state() {
  local status
  status="$(./scripts/dev.sh --status)"
  printf '%s\n' "$status"
  if printf '%s\n' "$status" | rg -q 'Backend: +running|Frontend: +running'; then
    echo "Refusing to overwrite an existing launcher-managed .dev stack." >&2
    return 1
  fi
  if lsof -nP -tiTCP:"$BACKEND_PORT" -sTCP:LISTEN >/dev/null 2>&1; then
    echo "Backend port ${BACKEND_PORT} is already occupied." >&2
    return 1
  fi
  if lsof -nP -tiTCP:"$FRONTEND_PORT" -sTCP:LISTEN >/dev/null 2>&1; then
    echo "Frontend port ${FRONTEND_PORT} is already occupied." >&2
    return 1
  fi
}

safe_database_name() {
  local safe_run_id
  safe_run_id="$(printf '%s' "$RUN_ID" \
    | tr '[:upper:]-' '[:lower:]_' \
    | tr -cd 'a-z0-9_')"
  printf 'spring_ai_rag_rerank_%s_%s\n' "$safe_run_id" "$$"
}

try_local_postgres() {
  command -v psql >/dev/null || return 1
  command -v createdb >/dev/null || return 1
  command -v dropdb >/dev/null || return 1

  local host="${POSTGRES_HOST:-127.0.0.1}"
  local port="${POSTGRES_PORT:-5432}"
  local username="${POSTGRES_USER:-postgres}"
  local password="${POSTGRES_PASSWORD:-}"
  local admin_database="${POSTGRES_ADMIN_DATABASE:-postgres}"
  local available
  available="$(
    PGPASSWORD="$password" \
      psql -h "$host" -p "$port" -U "$username" -d "$admin_database" \
        -Atqc "SELECT 1 FROM pg_available_extensions WHERE name = 'vector'" \
        2>/dev/null
  )" || return 1
  [[ "$available" == "1" ]] || return 1

  DATABASE_KIND="local"
  DATABASE_NAME="$(safe_database_name)"
  DATABASE_HOST="$host"
  DATABASE_PORT="$port"
  DATABASE_USER="$username"
  DATABASE_PASSWORD="$password"
  DATABASE_ADMIN="$admin_database"
  PGPASSWORD="$DATABASE_PASSWORD" \
    createdb \
      -h "$DATABASE_HOST" \
      -p "$DATABASE_PORT" \
      -U "$DATABASE_USER" \
      "$DATABASE_NAME" || {
        DATABASE_KIND=""
        DATABASE_NAME=""
        return 1
      }
  echo "Created local disposable PostgreSQL database: ${DATABASE_NAME}"
}

start_docker_postgres() {
  command -v docker >/dev/null || {
    echo "Neither a usable local PostgreSQL nor Docker is available." >&2
    return 1
  }
  docker version >/dev/null

  DATABASE_KIND="docker"
  DATABASE_NAME="$(safe_database_name)"
  DATABASE_HOST="127.0.0.1"
  DATABASE_USER="postgres"
  DATABASE_PASSWORD="$(openssl rand -hex 24)"
  DATABASE_ADMIN="$DATABASE_NAME"
  DATABASE_CONTAINER="spring-ai-rag-rerank-${RUN_ID//[^a-zA-Z0-9_.-]/-}-$$"
  DATABASE_CONTAINER="$(
    docker run -d --rm \
      --name "$DATABASE_CONTAINER" \
      -e "POSTGRES_DB=${DATABASE_NAME}" \
      -e "POSTGRES_USER=${DATABASE_USER}" \
      -e "POSTGRES_PASSWORD=${DATABASE_PASSWORD}" \
      -p 127.0.0.1::5432 \
      "$POSTGRES_IMAGE"
  )" || return 1
  DATABASE_PORT="$(
    docker port "$DATABASE_CONTAINER" 5432/tcp \
      | awk -F: 'NR == 1 { print $NF }'
  )"
  [[ -n "$DATABASE_PORT" ]] || return 1

  local attempt
  for attempt in $(seq 1 90); do
    if docker exec "$DATABASE_CONTAINER" \
      pg_isready -U "$DATABASE_USER" -d "$DATABASE_NAME" >/dev/null 2>&1; then
      echo "Created Docker disposable PostgreSQL database: ${DATABASE_NAME}"
      return 0
    fi
    if [[ "$attempt" == "90" ]]; then
      docker logs "$DATABASE_CONTAINER" >&2 || true
      return 1
    fi
    sleep 1
  done
}

prepare_database() {
  if try_local_postgres; then
    return 0
  fi
  echo "Local disposable PostgreSQL is unavailable; using Docker fallback."
  start_docker_postgres
}

write_environment_overlay() {
  local preferred_max_chunks="$1"
  local absolute_env
  absolute_env="$(cd "$(dirname "$ENV_FILE")" && pwd -P)/$(basename "$ENV_FILE")"
  local jdbc_url="jdbc:postgresql://${DATABASE_HOST}:${DATABASE_PORT}/${DATABASE_NAME}"
  ENV_OVERLAY="$LOG_DIR/dev.env"
  umask 077
  {
    printf 'source %q\n' "$absolute_env"
    printf 'POSTGRES_HOST=%q\n' "$DATABASE_HOST"
    printf 'POSTGRES_PORT=%q\n' "$DATABASE_PORT"
    printf 'POSTGRES_DATABASE=%q\n' "$DATABASE_NAME"
    printf 'POSTGRES_USER=%q\n' "$DATABASE_USER"
    printf 'POSTGRES_PASSWORD=%q\n' "$DATABASE_PASSWORD"
    printf 'SPRING_DATASOURCE_URL=%q\n' "$jdbc_url"
    printf 'SPRING_DATASOURCE_USERNAME=%q\n' "$DATABASE_USER"
    printf 'SPRING_DATASOURCE_PASSWORD=%q\n' "$DATABASE_PASSWORD"
    printf 'SPRING_FLYWAY_URL=%q\n' "$jdbc_url"
    printf 'SPRING_FLYWAY_USER=%q\n' "$DATABASE_USER"
    printf 'SPRING_FLYWAY_PASSWORD=%q\n' "$DATABASE_PASSWORD"
    printf 'RAG_RERANK_ENABLED=true\n'
    printf 'RAG_RERANK_PROVIDER=heuristic\n'
    printf 'RAG_RERANK_CANDIDATE_LIMIT=20\n'
    printf 'RAG_RERANK_PREFERRED_MAX_CHUNKS_PER_DOCUMENT=%q\n' \
      "$preferred_max_chunks"
  } >"$ENV_OVERLAY"
  chmod 600 "$ENV_OVERLAY"
  bash -n "$ENV_OVERLAY"
}

launch_real_stack() {
  BACKEND_PORT="$BACKEND_PORT" \
    FRONTEND_PORT="$FRONTEND_PORT" \
    DEV_ENV_FILE="$ENV_OVERLAY" \
    RAG_DEV_OPEN_BROWSER=false \
    RAG_ROOT_API_KEY="$ROOT_API_KEY" \
    SPRING_PROFILES_ACTIVE=postgresql,prod \
    ./scripts/dev.sh
  local rc=$?
  if [[ "$rc" -ne 0 ]]; then
    return "$rc"
  fi
  REAL_STACK_OWNED=1

  local health
  health="$(
    curl --noproxy '*' -fsS \
      "http://127.0.0.1:${BACKEND_PORT}/actuator/health"
  )" || return 1
  [[ "$health" == *'"status":"UP"'* ]] || return 1
  curl --noproxy '*' -fsS \
    "http://127.0.0.1:${FRONTEND_PORT}/webui/unlock" >/dev/null
  REAL_STACK_READY=1
}

stop_real_stack_for_restart() {
  if [[ "$REAL_STACK_OWNED" == "1" ]]; then
    DEV_ENV_FILE="$ENV_OVERLAY" \
      RAG_DEV_OPEN_BROWSER=false \
      ./scripts/dev.sh --stop
  fi
  REAL_STACK_OWNED=0
  REAL_STACK_READY=0
}

restart_real_stack() {
  local preferred_max_chunks="$1"
  stop_real_stack_for_restart || return 1
  assert_isolated_dev_state || return 1
  write_environment_overlay "$preferred_max_chunks" || return 1
  launch_real_stack
}

start_real_stack() {
  load_environment || return 1
  assert_isolated_dev_state || return 1
  prepare_database || return 1
  write_environment_overlay 2 || return 1
  launch_real_stack
}

create_fixture() {
  [[ "$REAL_STACK_READY" == "1" ]] || {
    echo "Real stack is not ready." >&2
    return 1
  }
  python3 - \
    "http://127.0.0.1:${FRONTEND_PORT}" \
    "$ROOT_API_KEY" \
    "$FIXTURE_FILE" \
    "$RUN_ID" <<'PY'
import json
import pathlib
import sys
import urllib.error
import urllib.parse
import urllib.request

base_url, api_key, fixture_path, run_id = sys.argv[1:]
api = base_url.rstrip("/") + "/api/v1/rag"
token = "".join(ch for ch in run_id.lower() if ch.isalnum())[-16:]
query = f"orbital calibration protocol {token}"
collection_key = f"rerank-diversity-{token}"


def request(method, path, body=None, timeout=180):
    payload = None if body is None else json.dumps(body).encode("utf-8")
    headers = {"Accept": "application/json", "X-API-Key": api_key}
    if payload is not None:
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(
        api + path, data=payload, headers=headers, method=method
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as response:
            raw = response.read().decode("utf-8")
            return response.status, json.loads(raw) if raw else None
    except urllib.error.HTTPError as error:
        raw = error.read().decode("utf-8", errors="replace")
        raise RuntimeError(
            f"{method} {path} failed with HTTP {error.code}: {raw[:1000]}"
        ) from error


_, collection = request("POST", "/collections", {
    "collectionKey": collection_key,
    "name": f"Rerank diversity {token}",
    "description": "Disposable document diversity acceptance fixture",
    "dimensions": 1024,
    "enabled": True,
    "metadata": {"managedBy": "verify-rerank-document-diversity"},
})

filler = (
    "The procedure checks stable alignment, sensor tolerance, evidence order, "
    "and repeatable recovery before deployment. "
)
main_sections = []
for index in range(1, 6):
    main_sections.append(
        f"{query}. Primary repeated evidence section {index}. "
        + filler * 6
        + f"Section marker MAIN_{index}_{token}."
    )
documents = [
    (
        f"Primary repeated evidence {token}",
        "\n\n".join(main_sections),
    ),
    (
        f"Independent safety evidence {token}",
        f"{query}. Independent evidence explains safe shutdown and validation. "
        + filler * 3
        + f"Section marker ALTERNATE_{token}.",
    ),
    (
        f"Independent latency evidence {token}",
        f"{query}. Independent evidence explains latency limits and response checks. "
        + filler * 3
        + f"Section marker THIRD_{token}.",
    ),
    (
        f"Independent recovery evidence {token}",
        f"{query}. Independent evidence explains recovery checks and audit evidence. "
        + filler * 3
        + f"Section marker FOURTH_{token}.",
    ),
]

created_documents = []
for title, content in documents:
    _, created = request("POST", "/documents", {
        "title": title,
        "content": content,
        "collectionKey": collection_key,
        "source": "rerank-diversity-acceptance",
        "metadata": {"managedBy": "verify-rerank-document-diversity"},
    })
    document_id = int(created["id"])
    _, embedded = request(
        "POST", f"/documents/{document_id}/embed?force=true"
    )
    status = str(embedded.get("status", "")).upper()
    chunks = int(
        embedded.get("chunksCreated")
        or embedded.get("embeddingsStored")
        or 0
    )
    if status in {"FAILED", "ERROR"} or chunks <= 0:
        raise RuntimeError(
            f"Embedding failed for {document_id}: {embedded}"
        )
    created_documents.append({
        "id": document_id,
        "title": title,
        "documentRevision": int(created["documentRevision"]),
        "chunksCreated": chunks,
    })

if created_documents[0]["chunksCreated"] < 4:
    raise RuntimeError(
        f"Primary fixture produced too few chunks: {created_documents[0]}"
    )

fixture = {
    "query": query,
    "collectionId": int(collection["id"]),
    "collectionKey": collection_key,
    "maxResults": 5,
    "preferredMaxChunksPerDocument": 2,
    "documents": created_documents,
}
path = pathlib.Path(fixture_path)
path.write_text(
    json.dumps(fixture, ensure_ascii=False, indent=2) + "\n",
    encoding="utf-8",
)
print(
    f"Created fixture collection={collection_key} "
    f"documents={len(created_documents)} "
    f"chunks={[item['chunksCreated'] for item in created_documents]}"
)
PY
}

real_playwright() {
  (
    cd spring-ai-rag-webui
    BASE_URL="http://127.0.0.1:${FRONTEND_PORT}" \
      RAG_ROOT_API_KEY="$ROOT_API_KEY" \
      RERANK_DIVERSITY_FIXTURE_FILE="$FIXTURE_FILE" \
      npx playwright test \
        e2e/rerank-document-diversity-real.spec.ts \
        --project=chromium
  )
}

retrieval_goldenset() {
  BASE_URL="http://127.0.0.1:${BACKEND_PORT}" \
    API_KEY="$ROOT_API_KEY" \
    ./scripts/run-retrieval-goldenset.sh
}

quality_regression() {
  BASE_URL="http://127.0.0.1:${BACKEND_PORT}" \
    RAG_ROOT_API_KEY="$ROOT_API_KEY" \
    QUALITY_VERIFY_LOG_DIR="$LOG_DIR/quality-regression" \
    ./scripts/verify-quality-regression.sh
}

real_llm_baseline() {
  BASE_URL="http://127.0.0.1:${BACKEND_PORT}" \
    REAL_LLM_ENV_FILE="$ENV_OVERLAY" \
    RAG_ROOT_API_KEY="$ROOT_API_KEY" \
    ./scripts/real-llm-e2e-smoke.sh --skip-stream
}

database_query() {
  local sql="$1"
  case "$DATABASE_KIND" in
    local)
      PGPASSWORD="$DATABASE_PASSWORD" \
        psql \
          -X \
          -qAt \
          -v ON_ERROR_STOP=1 \
          -h "$DATABASE_HOST" \
          -p "$DATABASE_PORT" \
          -U "$DATABASE_USER" \
          -d "$DATABASE_NAME" \
          -c "$sql"
      ;;
    docker)
      docker exec \
        -e "PGPASSWORD=${DATABASE_PASSWORD}" \
        "$DATABASE_CONTAINER" \
        psql \
          -X \
          -qAt \
          -v ON_ERROR_STOP=1 \
          -U "$DATABASE_USER" \
          -d "$DATABASE_NAME" \
          -c "$sql"
      ;;
    *)
      echo "Disposable database is not available." >&2
      return 1
      ;;
  esac
}

collect_runtime_variant() {
  local label="$1"
  local preferred_max_chunks="$2"
  local output_file="$3"
  local raw_file="$LOG_DIR/runtime-${label}-raw.json"
  local database_file="$LOG_DIR/runtime-${label}-database.json"
  local enrich_log="$LOG_DIR/runtime-${label}-enrich.log"
  local trace_ids
  local sql

  "$METRICS_SCRIPT" collect \
    --base-url "http://127.0.0.1:${BACKEND_PORT}" \
    --api-key "$ROOT_API_KEY" \
    --fixture "$FIXTURE_FILE" \
    --label "$label" \
    --preferred-max-chunks "$preferred_max_chunks" \
    --search-samples "$SEARCH_SAMPLE_COUNT" \
    --chat-samples "$CHAT_SAMPLE_COUNT" \
    --chat-max-attempts "$CHAT_MAX_ATTEMPTS" \
    --output "$raw_file" || return 1

  trace_ids="$(
    "$METRICS_SCRIPT" trace-sql --samples "$raw_file"
  )" || return 1
  sql="
    SELECT COALESCE(
      json_agg(
        json_build_object(
          'traceId', trace_id::text,
          'operation', operation,
          'retrievalLatencyMs', total_time_ms,
          'rerankStageLatencyMs', rerank_time_ms,
          'latestRetrievalResultCount', result_count
        )
        ORDER BY created_at
      ),
      '[]'::json
    )::text
    FROM rag_retrieval_logs
    WHERE trace_id IN (${trace_ids});
  "

  local attempt
  for attempt in $(seq 1 30); do
    if database_query "$sql" >"$database_file" \
        && "$METRICS_SCRIPT" enrich \
          --samples "$raw_file" \
          --database-metrics "$database_file" \
          --output "$output_file" >"$enrich_log" 2>&1; then
      cat "$enrich_log"
      return 0
    fi
    sleep 0.25
  done
  cat "$enrich_log" >&2
  return 1
}

runtime_before_after_comparison() {
  local baseline_file="$LOG_DIR/runtime-cap0.json"
  local feature_file="$LOG_DIR/runtime-cap2.json"
  local comparison_rc=0

  if restart_real_stack 0; then
    collect_runtime_variant "cap0" 0 "$baseline_file" || comparison_rc=1
  else
    comparison_rc=1
  fi

  if ! restart_real_stack 2; then
    echo "Failed to restore the real stack to cap=2." >&2
    return 1
  fi
  collect_runtime_variant "cap2" 2 "$feature_file" || comparison_rc=1

  if [[ -f "$baseline_file" && -f "$feature_file" ]]; then
    "$METRICS_SCRIPT" compare \
      --baseline "$baseline_file" \
      --feature "$feature_file" \
      --output-json "$RUNTIME_COMPARISON_JSON" \
      --output-markdown "$RUNTIME_COMPARISON_MARKDOWN" \
      || comparison_rc=1
    if [[ -f "$RUNTIME_COMPARISON_MARKDOWN" ]]; then
      cat "$RUNTIME_COMPARISON_MARKDOWN"
    fi
  else
    echo "Runtime comparison inputs are incomplete." >&2
    comparison_rc=1
  fi

  return "$comparison_rc"
}

real_llm_knowledge_fixture() {
  python3 - \
    "http://127.0.0.1:${BACKEND_PORT}" \
    "$ROOT_API_KEY" \
    "$FIXTURE_FILE" \
    "$LOG_DIR/real-knowledge-response.json" <<'PY'
import json
import pathlib
import re
import sys
import urllib.error
import urllib.request

base_url, api_key, fixture_path, output_path = sys.argv[1:]
fixture = json.loads(pathlib.Path(fixture_path).read_text(encoding="utf-8"))
api = base_url.rstrip("/") + "/api/v1/rag"
document_ids = [item["id"] for item in fixture["documents"]]
body = {
    "message": (
        "Using only the selected evidence, summarize the orbital calibration "
        "protocol and cite every factual sentence with the supplied source IDs."
    ),
    "mode": "KNOWLEDGE",
    "maxResults": fixture["maxResults"],
    "useHybridSearch": True,
    "useRerank": True,
    "collectionScopeMode": "SELECTED_COLLECTIONS",
    "collectionKeys": [fixture["collectionKey"]],
    "documentIds": document_ids,
}
req = urllib.request.Request(
    api + "/chat/ask",
    data=json.dumps(body).encode("utf-8"),
    headers={
        "Accept": "application/json",
        "Content-Type": "application/json",
        "X-API-Key": api_key,
    },
    method="POST",
)
try:
    with urllib.request.urlopen(req, timeout=240) as response:
        raw = response.read().decode("utf-8")
except urllib.error.HTTPError as error:
    raw = error.read().decode("utf-8", errors="replace")
    raise RuntimeError(
        f"KNOWLEDGE smoke failed with HTTP {error.code}: {raw[:1000]}"
    ) from error

payload = json.loads(raw)
pathlib.Path(output_path).write_text(
    json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
    encoding="utf-8",
)
sources = payload.get("sources") or []
source_document_ids = {
    str(source.get("documentId"))
    for source in sources
    if source.get("documentId") is not None
}
if len(source_document_ids) < 2:
    raise RuntimeError(
        f"KNOWLEDGE sources lack document diversity: {source_document_ids}"
    )
valid_citations = {
    str(source.get("citationId"))
    for source in sources
    if source.get("citationId")
}
answer = str(payload.get("answer") or "")
used_citations = set(re.findall(r"\[(S\d+)\]", answer))
invalid = sorted(used_citations.difference(valid_citations))
if invalid:
    raise RuntimeError(
        f"Answer contains citations not present in sources: {invalid}"
    )
if not answer.strip():
    raise RuntimeError("KNOWLEDGE answer is empty")
print(
    f"KNOWLEDGE answer chars={len(answer)} "
    f"sources={len(sources)} uniqueDocuments={len(source_document_ids)} "
    f"citations={len(used_citations)}"
)
PY
}

cleanup_mock_preview() {
  if [[ -n "$MOCK_PREVIEW_PID" ]] \
      && kill -0 "$MOCK_PREVIEW_PID" >/dev/null 2>&1; then
    kill "$MOCK_PREVIEW_PID" >/dev/null 2>&1 || true
    wait "$MOCK_PREVIEW_PID" >/dev/null 2>&1 || true
  fi
  MOCK_PREVIEW_PID=""
}

cleanup_real_stack() {
  if [[ "$REAL_STACK_OWNED" == "1" ]]; then
    DEV_ENV_FILE="$ENV_OVERLAY" \
      RAG_DEV_OPEN_BROWSER=false \
      ./scripts/dev.sh --stop >/dev/null 2>&1 || true
  fi
  REAL_STACK_OWNED=0
  REAL_STACK_READY=0
}

cleanup_database() {
  case "$DATABASE_KIND" in
    local)
      if [[ -n "$DATABASE_NAME" ]]; then
        PGPASSWORD="$DATABASE_PASSWORD" \
          dropdb \
            -h "$DATABASE_HOST" \
            -p "$DATABASE_PORT" \
            -U "$DATABASE_USER" \
            --if-exists \
            "$DATABASE_NAME" >/dev/null 2>&1 || true
      fi
      ;;
    docker)
      if [[ -n "$DATABASE_CONTAINER" ]]; then
        docker rm -f "$DATABASE_CONTAINER" >/dev/null 2>&1 || true
      fi
      ;;
  esac
  DATABASE_KIND=""
  DATABASE_NAME=""
  DATABASE_CONTAINER=""
}

write_summary() {
  local result="PASS"
  if [[ "$FAIL_COUNT" -gt 0 ]]; then
    result="FAIL"
  elif [[ "$SKIP_COUNT" -gt 0 ]]; then
    result="PASS_WITH_SKIPS"
  fi

  {
    echo "# Rerank document diversity verification"
    echo
    echo "- Run: \`${RUN_ID}\`"
    echo "- Generated: \`$(date '+%Y-%m-%d %H:%M:%S %z')\`"
    echo "- Branch: \`$(git branch --show-current)\`"
    echo "- Commit: \`$(git rev-parse --short HEAD)\`"
    echo "- Result: **${result}**"
    echo "- Counts: ${PASS_COUNT} passed, ${FAIL_COUNT} failed, ${SKIP_COUNT} skipped"
    echo "- Real backend/frontend: \`${BACKEND_PORT}\` / \`${FRONTEND_PORT}\`"
    if [[ -f "$RUNTIME_COMPARISON_MARKDOWN" ]]; then
      echo "- Runtime comparison: \`${RUNTIME_COMPARISON_MARKDOWN}\`"
      echo "- Runtime comparison JSON: \`${RUNTIME_COMPARISON_JSON}\`"
    fi
    echo
    echo "| Step | Status | Exit | Evidence |"
    echo "|------|--------|------|----------|"
    while IFS='|' read -r name status exit_code evidence; do
      echo "| ${name} | ${status} | ${exit_code} | \`${evidence}\` |"
    done <"$LOG_DIR/summary.tsv"
  } >"$LOG_DIR/summary.md"
}

cleanup() {
  cleanup_mock_preview
  cleanup_real_stack
  cleanup_database
  ROOT_API_KEY=""
  write_summary
}

trap cleanup EXIT
trap 'exit 130' INT TERM

run_step "Prerequisites" require_commands
run_step "Focused backend call-chain tests" focused_backend_tests
run_step "PostgreSQL pgvector integration" postgres_integration_tests
run_step "Maven clean compile test-compile" maven_compile
run_step "WebUI TypeScript" webui_typecheck
run_step "WebUI Vitest" webui_unit
run_step "WebUI production build" webui_build
run_step "WebUI alignment policy" webui_alignment
run_step "Core Mock Playwright" mock_playwright
cleanup_mock_preview
run_step "No explicit pessimistic locks" ./scripts/verify-no-pessimistic-locks.sh
run_step "Project documentation gates" ./scripts/verify-project-docs.sh
run_step "Retrieval regression response contract" \
  ./scripts/run-retrieval-regression.sh --self-test
run_step "Runtime comparison metrics self-test" \
  "$METRICS_SCRIPT" self-test
run_step "Git whitespace check" git diff --check

if run_step "Isolated real dev stack" start_real_stack; then
  if run_step "Disposable multi-document fixture" create_fixture; then
    run_step "Real Search proxy and DOM Playwright" real_playwright
    run_step "Retrieval goldenset" retrieval_goldenset
    run_step "Versioned quality regression" quality_regression
    if run_step "Real LLM provider baseline" real_llm_baseline; then
      run_step "Real cap=0 versus cap=2 runtime comparison" \
        runtime_before_after_comparison
      run_step "Real LLM KNOWLEDGE diversity" real_llm_knowledge_fixture
    else
      skip_step \
        "Real cap=0 versus cap=2 runtime comparison" \
        "provider baseline failed; inspect the preceding log"
      skip_step \
        "Real LLM KNOWLEDGE diversity" \
        "provider baseline failed; inspect the preceding log"
    fi
  else
    skip_step "Real Search proxy and DOM Playwright" "fixture creation failed"
    skip_step "Retrieval goldenset" "fixture creation failed"
    skip_step "Versioned quality regression" "fixture creation failed"
    skip_step "Real LLM provider baseline" "fixture creation failed"
    skip_step \
      "Real cap=0 versus cap=2 runtime comparison" \
      "fixture creation failed"
    skip_step "Real LLM KNOWLEDGE diversity" "fixture creation failed"
  fi
else
  skip_step "Disposable multi-document fixture" "real dev stack failed"
  skip_step "Real Search proxy and DOM Playwright" "real dev stack failed"
  skip_step "Retrieval goldenset" "real dev stack failed"
  skip_step "Versioned quality regression" "real dev stack failed"
  skip_step "Real LLM provider baseline" "real dev stack failed"
  skip_step \
    "Real cap=0 versus cap=2 runtime comparison" \
    "real dev stack failed"
  skip_step "Real LLM KNOWLEDGE diversity" "real dev stack failed"
fi

echo
echo "Rerank document diversity verification: ${PASS_COUNT} passed, ${FAIL_COUNT} failed, ${SKIP_COUNT} skipped"
echo "Summary: ${LOG_DIR}/summary.md"

if [[ "$FAIL_COUNT" -gt 0 ]]; then
  exit 1
fi
exit 0
