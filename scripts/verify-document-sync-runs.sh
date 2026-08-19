#!/usr/bin/env bash
# Sync Run HTTP acceptance against disposable PostgreSQL and Spring Boot.
set -euo pipefail

cd "$(dirname "$0")/.."

RUN_ID="${DOCUMENT_SYNC_RUNS_VERIFY_RUN_ID:-$(date +%Y%m%d-%H%M%S)}"
LOG_DIR="${DOCUMENT_SYNC_RUNS_VERIFY_LOG_DIR:-.verification/document-sync-runs/${RUN_ID}}"
BACKEND_PORT="${DOCUMENT_SYNC_RUNS_VERIFY_BACKEND_PORT:-4187}"
PG_IMAGE="${DOCUMENT_SYNC_RUNS_VERIFY_PG_IMAGE:-${TESTCONTAINERS_PG_IMAGE:-pgvector/pgvector:pg16}}"
MIRROR_BASE_URL="${MIRROR_BASE_URL:-docker.m.daocloud.io}"

JDBC_URL="${DOCUMENT_SYNC_RUNS_IT_JDBC_URL:-}"
DB_USERNAME="${DOCUMENT_SYNC_RUNS_IT_USERNAME:-}"
DB_PASSWORD="${DOCUMENT_SYNC_RUNS_IT_PASSWORD:-}"
TEMP_DATABASE=""
POSTGRES_CONTAINER=""
BACKEND_PID=""
STEP_INDEX=0

mkdir -p "$LOG_DIR"
: > "$LOG_DIR/summary.tsv"

log_step() {
  STEP_INDEX=$((STEP_INDEX + 1))
  local name="$1"
  printf '%s\tPASS\t%s\n' "$name" "$LOG_DIR/${STEP_INDEX}-${name}.log" \
    >> "$LOG_DIR/summary.tsv"
  printf 'PASS: %s\n' "$name"
}

load_local_env() {
  [[ -f .env ]] || return 0
  local explicit_url="$JDBC_URL"
  local explicit_username="$DB_USERNAME"
  local explicit_password="$DB_PASSWORD"
  set -a
  # shellcheck disable=SC1091
  source .env
  set +a
  [[ -n "$explicit_url" ]] && JDBC_URL="$explicit_url"
  [[ -n "$explicit_username" ]] && DB_USERNAME="$explicit_username"
  [[ -n "$explicit_password" ]] && DB_PASSWORD="$explicit_password"
  return 0
}

postgres_ready() {
  command -v psql >/dev/null 2>&1 || return 1
  PGPASSWORD="${POSTGRES_PASSWORD:-}" \
    psql -h "${POSTGRES_HOST:-127.0.0.1}" \
      -p "${POSTGRES_PORT:-5432}" \
      -U "${POSTGRES_USER:-postgres}" \
      -d "${POSTGRES_DATABASE:-postgres}" \
      -v ON_ERROR_STOP=1 -Atqc 'SELECT 1' 2>/dev/null | grep -qx 1
}

create_local_database() {
  command -v createdb >/dev/null 2>&1 || return 1
  command -v dropdb >/dev/null 2>&1 || return 1
  postgres_ready || return 1
  local safe_run_id
  safe_run_id="$(printf '%s' "$RUN_ID" | tr -cd 'a-zA-Z0-9_' | tr '[:upper:]' '[:lower:]')"
  TEMP_DATABASE="spring_ai_rag_sync_runs_${safe_run_id}_$$"
  PGPASSWORD="${POSTGRES_PASSWORD:-}" \
    createdb -h "${POSTGRES_HOST:-127.0.0.1}" \
      -p "${POSTGRES_PORT:-5432}" \
      -U "${POSTGRES_USER:-postgres}" "$TEMP_DATABASE"
  JDBC_URL="jdbc:postgresql://${POSTGRES_HOST:-127.0.0.1}:${POSTGRES_PORT:-5432}/${TEMP_DATABASE}"
  DB_USERNAME="${POSTGRES_USER:-postgres}"
  DB_PASSWORD="${POSTGRES_PASSWORD:-}"
}

resolve_docker_image() {
  if docker image inspect "$PG_IMAGE" >/dev/null 2>&1; then
    printf '%s' "$PG_IMAGE"
    return
  fi
  local mirrored="${MIRROR_BASE_URL%/}/${PG_IMAGE}"
  if docker pull "$mirrored" >/dev/null 2>&1; then
    printf '%s' "$mirrored"
    return
  fi
  docker pull "$PG_IMAGE" >/dev/null
  printf '%s' "$PG_IMAGE"
}

start_docker_database() {
  command -v docker >/dev/null 2>&1 || {
    echo "No disposable PostgreSQL is available." >&2
    return 1
  }
  local image port
  image="$(resolve_docker_image)"
  DB_USERNAME="${DB_USERNAME:-postgres}"
  DB_PASSWORD="${DB_PASSWORD:-postgres}"
  POSTGRES_CONTAINER="spring-ai-rag-sync-runs-${RUN_ID}-$$"
  docker run -d --rm \
    --name "$POSTGRES_CONTAINER" \
    -e POSTGRES_DB=spring_ai_rag_sync_runs_test \
    -e POSTGRES_USER="$DB_USERNAME" \
    -e POSTGRES_PASSWORD="$DB_PASSWORD" \
    -p 127.0.0.1::5432 \
    "$image" >/dev/null
  for _ in $(seq 1 60); do
    if docker exec "$POSTGRES_CONTAINER" pg_isready \
        -U "$DB_USERNAME" -d spring_ai_rag_sync_runs_test >/dev/null 2>&1; then
      break
    fi
    sleep 1
  done
  docker exec "$POSTGRES_CONTAINER" pg_isready \
    -U "$DB_USERNAME" -d spring_ai_rag_sync_runs_test >/dev/null
  port="$(docker port "$POSTGRES_CONTAINER" 5432/tcp | sed 's/.*://')"
  JDBC_URL="jdbc:postgresql://127.0.0.1:${port}/spring_ai_rag_sync_runs_test"
}

prepare_database() {
  load_local_env
  if [[ -n "$JDBC_URL" ]]; then
    [[ "${DOCUMENT_SYNC_RUNS_IT_CLEAN_CONFIRM:-}" == "YES" ]] || {
      echo "DOCUMENT_SYNC_RUNS_IT_CLEAN_CONFIRM=YES is required for a supplied JDBC URL." >&2
      return 1
    }
    [[ -n "$DB_USERNAME" ]] || {
      echo "DOCUMENT_SYNC_RUNS_IT_USERNAME is required with a supplied JDBC URL." >&2
      return 1
    }
    return
  fi
  create_local_database || start_docker_database
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

start_backend() {
  local classpath_file="$LOG_DIR/runtime-classpath.txt"
  mvn -pl spring-ai-rag-core -am -q dependency:build-classpath \
    "-Dmdep.outputFile=${PWD}/${classpath_file}" \
    -DincludeScope=runtime >"$LOG_DIR/build-classpath.log" 2>&1
  local runtime_classpath="spring-ai-rag-core/target/classes:"
  runtime_classpath+="spring-ai-rag-api/target/classes:"
  runtime_classpath+="spring-ai-rag-documents/target/classes:"
  runtime_classpath+="spring-ai-rag-starter/target/classes:"
  runtime_classpath+="$(cat "$classpath_file")"
  BACKEND_PORT="$(find_available_port "$BACKEND_PORT")"
  env \
    SPRING_PROFILES_ACTIVE=postgresql \
    SERVER_PORT="$BACKEND_PORT" \
    SPRING_DATASOURCE_URL="$JDBC_URL" \
    SPRING_DATASOURCE_USERNAME="$DB_USERNAME" \
    SPRING_DATASOURCE_PASSWORD="$DB_PASSWORD" \
    RAG_SECURITY_ENABLED=false \
    RAG_ROOT_API_KEY= \
    RAG_DOCUMENT_SYNC_RUNS_ENABLED=true \
    RAG_DOCUMENT_VERSION_RESTORE_ENABLED=true \
    RAG_EMBEDDING_JOBS_ENABLED=false \
    APP_LLM_PROVIDER=openai \
    SPRING_AI_OPENAI_API_KEY=dummy \
    SPRING_AI_OPENAI_BASE_URL=http://127.0.0.1:9 \
    SPRING_AI_OPENAI_CHAT_OPTIONS_MODEL=dummy-chat \
    RAG_EMBEDDING_API_KEY=dummy \
    RAG_EMBEDDING_BASE_URL=http://127.0.0.1:9 \
    RAG_EMBEDDING_MODEL=dummy-embedding \
    RAG_EMBEDDING_DIMENSIONS=1024 \
    RAG_EMBEDDING_PROFILE_KEY=sync-run-dummy-embedding-1024-v1 \
    RAG_EMBEDDING_PROVIDER=verification \
    RAG_EMBEDDING_MODEL_REVISION=v1 \
    java -cp "$runtime_classpath" com.springairag.core.SpringAiRagApplication \
    >"$LOG_DIR/backend.log" 2>&1 &
  BACKEND_PID=$!
  for _ in $(seq 1 90); do
    if ! kill -0 "$BACKEND_PID" >/dev/null 2>&1; then
      tail -80 "$LOG_DIR/backend.log" >&2 || true
      return 1
    fi
    if curl --fail --silent --show-error --connect-timeout 1 --max-time 2 \
        "http://127.0.0.1:${BACKEND_PORT}/actuator/health" \
        | grep -Fq '"status":"UP"'; then
      return
    fi
    sleep 1
  done
  tail -80 "$LOG_DIR/backend.log" >&2 || true
  return 1
}

run_http_acceptance() {
  python3 - "$BACKEND_PORT" "$RUN_ID" "$LOG_DIR/http-acceptance.json" <<'PY'
import json
import secrets
import sys
import urllib.error
import urllib.parse
import urllib.request

port, run_id, evidence_path = sys.argv[1:]
base = f"http://127.0.0.1:{port}/api/v1/rag"

def request(method, path, payload=None, headers=None, expected=(200,)):
    body = None if payload is None else json.dumps(payload).encode()
    merged = {"Content-Type": "application/json"}
    if headers:
        merged.update(headers)
    req = urllib.request.Request(
        base + path, data=body, headers=merged, method=method)
    try:
        with urllib.request.urlopen(req, timeout=10) as response:
            status = response.status
            data = json.loads(response.read().decode() or "{}")
    except urllib.error.HTTPError as error:
        status = error.code
        raw = error.read().decode()
        try:
            data = json.loads(raw or "{}")
        except json.JSONDecodeError:
            data = {"raw": raw[:200]}
    if status not in expected:
        raise AssertionError(f"{method} {path} returned {status}: {data}")
    return data

collection_key = f"sync-run-{run_id}-{secrets.token_hex(4)}"
namespace = "cms-main"
other_namespace = "erp-main"
lease = secrets.token_urlsafe(32)
collection = request("POST", "/collections", {
    "collectionKey": collection_key,
    "name": "Sync Run verification",
    "dimensions": 1024,
})

def upsert(source_namespace, external_id, revision, content, expected=None):
    payload = {
        "collectionKey": collection_key,
        "sourceNamespace": source_namespace,
        "externalId": external_id,
        "sourceRevision": revision,
        "title": external_id,
        "content": content,
        "documentType": "text",
        "embeddingPolicy": "SKIP",
    }
    if expected is not None:
        payload["expectedSourceRevision"] = expected
    return request("POST", "/documents/upsert", payload)

missing = upsert(namespace, "missing-1", "r1", "missing before cut")
newer = upsert(namespace, "newer-1", "r1", "will be updated after cut")
isolated = upsert(other_namespace, "missing-1", "r1", "different namespace")

begin_payload = {
    "collectionKey": collection_key,
    "sourceNamespace": namespace,
    "clientRunId": f"client-run-{run_id}",
    "snapshotMode": "ONLINE_CUT",
    "missingPolicy": "TOMBSTONE",
    "leaseSeconds": 900,
}
run = request("POST", "/document-sync-runs", begin_payload,
              {"X-RAG-Sync-Lease": lease})
run_id_value = run["runId"]
status_path = run["statusPath"]
assert status_path.startswith("/api/v1/rag/document-sync-runs/")
status = request("GET", status_path.removeprefix("/api/v1/rag"))
assert status["runId"] == run_id_value
replay = request("POST", "/document-sync-runs", begin_payload,
                 {"X-RAG-Sync-Lease": lease})
assert replay["runId"] == run_id_value

upsert(namespace, "newer-1", "r2", "updated after cut", "r1")

batch_headers = {"X-RAG-Sync-Lease": lease}
batch = request("POST", f"/document-sync-runs/{run_id_value}/batch-upsert", {
    "items": [
        {
            "documentKind": "TEXT",
            "externalId": "seen-1",
            "sourceRevision": "r1",
            "title": "seen-1",
            "content": "seen in authoritative snapshot",
            "embeddingPolicy": "SKIP",
        },
        {
            "documentKind": "JSON_RECORD",
            "externalId": "json-1",
            "sourceRevision": "r1",
            "title": "json-1",
            "retrievalText": "JSON record description",
            "jsonbPayload": {"kind": "verification", "active": True},
            "embeddingPolicy": "SKIP",
        },
    ],
}, batch_headers)
assert [item["status"] for item in batch["items"]] == ["APPLIED", "APPLIED"]

preview = request(
    "POST", f"/document-sync-runs/{run_id_value}/preview-missing",
    headers=batch_headers)
assert preview["candidateCount"] == 1
assert preview["protectedByNewerMutationCount"] >= 1
assert preview["jsonRecordCount"] == 0

complete = request(
    "POST", f"/document-sync-runs/{run_id_value}/complete",
    {"previewToken": preview["previewToken"], "confirmMissingCount": 1},
    batch_headers)
assert complete["status"] == "COMPLETED"
assert complete["tombstonedCount"] == 1

failed_lease = secrets.token_urlsafe(32)
failed_run = request(
    "POST", "/document-sync-runs", {
        **begin_payload,
        "clientRunId": f"failed-tombstone-{run_id}",
    }, {"X-RAG-Sync-Lease": failed_lease})
failed_run_id = failed_run["runId"]
failed_headers = {"X-RAG-Sync-Lease": failed_lease}
bad_item = {
    "documentKind": "JSON_RECORD",
    "externalId": "bad-1",
    "sourceRevision": "r1",
    "title": "bad-1",
    "retrievalText": "missing payload",
    "jsonbPayload": None,
    "embeddingPolicy": "SKIP",
}
failed_first = request(
    "POST", f"/document-sync-runs/{failed_run_id}/batch-upsert",
    {"items": [bad_item]}, failed_headers)
failed_retry = request(
    "POST", f"/document-sync-runs/{failed_run_id}/batch-upsert",
    {"items": [bad_item]}, failed_headers)
assert failed_first["items"][0]["status"] == "FAILED"
assert failed_retry["items"][0]["status"] == "FAILED"
failed_preview = request(
    "POST", f"/document-sync-runs/{failed_run_id}/preview-missing",
    headers=failed_headers)
incomplete = request(
    "POST", f"/document-sync-runs/{failed_run_id}/complete",
    {"previewToken": failed_preview["previewToken"]},
    failed_headers,
    expected=(409,))
assert incomplete["error"] == "SYNC_RUN_INCOMPLETE"
request(
    "POST", f"/document-sync-runs/{failed_run_id}/abort",
    headers=failed_headers)

def lookup(source_namespace, external_id):
    query = urllib.parse.urlencode({
        "collectionKey": collection_key,
        "sourceNamespace": source_namespace,
        "externalId": external_id,
    })
    return request("GET", f"/documents/by-external-id?{query}")

missing_after = lookup(namespace, "missing-1")
newer_after = lookup(namespace, "newer-1")
isolated_after = lookup(other_namespace, "missing-1")
assert missing_after["enabled"] is False
assert missing_after["sourceDeletedAt"] is not None
assert missing_after["sourceRevision"] == "r1"
assert newer_after["enabled"] is True
assert newer_after["sourceRevision"] == "r2"
assert isolated_after["enabled"] is True

bad_mode = request(
    "POST", "/document-sync-runs",
    {
        **begin_payload,
        "clientRunId": f"offline-invalid-{run_id}",
        "snapshotMode": "OFFLINE_MANIFEST",
        "missingPolicy": "TOMBSTONE",
    },
    {"X-RAG-Sync-Lease": secrets.token_urlsafe(32)},
    expected=(400,))

exclusive_lease = secrets.token_urlsafe(32)
exclusive_without_confirmation = request(
    "POST", "/document-sync-runs",
    {
        **begin_payload,
        "clientRunId": f"exclusive-without-confirmation-{run_id}",
        "snapshotMode": "EXCLUSIVE_OFFLINE",
        "missingPolicy": "TOMBSTONE",
    },
    {"X-RAG-Sync-Lease": exclusive_lease},
    expected=(400,))
exclusive = request(
    "POST", "/document-sync-runs",
    {
        **begin_payload,
        "clientRunId": f"exclusive-confirmed-{run_id}",
        "snapshotMode": "EXCLUSIVE_OFFLINE",
        "missingPolicy": "TOMBSTONE",
        "confirmExclusiveOffline": True,
    },
    {"X-RAG-Sync-Lease": exclusive_lease})
assert exclusive["status"] == "ACTIVE"
request(
    "POST", f"/document-sync-runs/{exclusive['runId']}/abort",
    headers={"X-RAG-Sync-Lease": exclusive_lease})

evidence = {
    "collectionCreated": bool(collection),
    "runId": run_id_value,
    "batchStatuses": [item["status"] for item in batch["items"]],
    "failedRetryStatus": failed_retry["items"][0]["status"],
    "failedTombstoneCompletionError": incomplete["error"],
    "candidateCount": preview["candidateCount"],
    "protectedByNewerMutationCount": preview["protectedByNewerMutationCount"],
    "tombstonedCount": complete["tombstonedCount"],
    "sourceDeletedAtPresent": missing_after["sourceDeletedAt"] is not None,
    "namespaceIsolation": isolated_after["enabled"],
    "invalidOfflineModeStatus": 400,
    "exclusiveConfirmationRequired": 400,
    "exclusiveConfirmationAccepted": exclusive["status"] == "ACTIVE",
}
with open(evidence_path, "w", encoding="utf-8") as stream:
    json.dump(evidence, stream, indent=2, sort_keys=True)
print("Sync Run HTTP acceptance passed: applied=2, failed-retry=2, tombstoned=1")
PY
}

cleanup() {
  if [[ -n "$BACKEND_PID" ]]; then
    kill "$BACKEND_PID" >/dev/null 2>&1 || true
    wait "$BACKEND_PID" >/dev/null 2>&1 || true
  fi
  if [[ -n "$TEMP_DATABASE" ]]; then
    PGPASSWORD="${POSTGRES_PASSWORD:-}" \
      dropdb -h "${POSTGRES_HOST:-127.0.0.1}" \
        -p "${POSTGRES_PORT:-5432}" \
        -U "${POSTGRES_USER:-postgres}" --if-exists "$TEMP_DATABASE" \
        >/dev/null 2>&1 || true
  fi
  if [[ -n "$POSTGRES_CONTAINER" ]]; then
    docker stop "$POSTGRES_CONTAINER" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

load_local_env
prepare_database
log_step "disposable-postgresql"
start_backend
log_step "spring-boot-with-flyway-v42"
run_http_acceptance
log_step "sync-run-http-contract"
./scripts/verify-no-pessimistic-locks.sh >"$LOG_DIR/no-locks.log"
log_step "no-pessimistic-locks"
git diff --check
log_step "git-whitespace"

cat > "$LOG_DIR/summary.md" <<EOF
# Document Sync Run Verification

- Run: \`$RUN_ID\`
- Backend port: \`$BACKEND_PORT\`
- Evidence: \`$LOG_DIR/http-acceptance.json\`
- Flyway: V1–V42
- Result: PASS
EOF
echo "Document Sync Run verification passed: $LOG_DIR/summary.md"
