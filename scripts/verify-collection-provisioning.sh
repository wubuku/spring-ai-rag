#!/usr/bin/env bash
# Collection 创建幂等性的 PostgreSQL、双实例和重启验收。
set -euo pipefail

cd "$(dirname "$0")/.."

RUN_ID="${COLLECTION_PROVISIONING_VERIFY_RUN_ID:-$(date +%Y%m%d-%H%M%S)-$$}"
LOG_DIR="${COLLECTION_PROVISIONING_VERIFY_LOG_DIR:-.verification/collection-provisioning/${RUN_ID}}"
VERIFY_PHASE="${COLLECTION_PROVISIONING_VERIFY_PHASE:-all}"
POSTGRES_IMAGE="${COLLECTION_PROVISIONING_POSTGRES_IMAGE:-${TESTCONTAINERS_PG_IMAGE:-pgvector/pgvector:pg16}}"
POSTGRES_DATABASE="${COLLECTION_PROVISIONING_POSTGRES_DATABASE:-collection_provisioning_gate}"
POSTGRES_USERNAME="${COLLECTION_PROVISIONING_POSTGRES_USERNAME:-postgres}"
POSTGRES_PASSWORD="${COLLECTION_PROVISIONING_POSTGRES_PASSWORD:-postgres}"
BACKEND_A_PORT="${COLLECTION_PROVISIONING_BACKEND_A_PORT:-18191}"
BACKEND_B_PORT="${COLLECTION_PROVISIONING_BACKEND_B_PORT:-18192}"

PRIVATE_DIR="$(mktemp -d "/tmp/spring-ai-rag-collection-provisioning-${RUN_ID}.XXXXXX")"
POSTGRES_CONTAINER=""
POSTGRES_PORT=""
BACKEND_A_PID=""
BACKEND_B_PID=""
RUNTIME_CLASSPATH=""
ROOT_KEY=""
PASS_COUNT=0
STEP_INDEX=0

mkdir -p "$LOG_DIR"
chmod 700 "$PRIVATE_DIR"
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
  local log_path="${LOG_DIR}/${STEP_INDEX}-$(slugify "$name").log"
  echo
  echo "=== ${name} ==="
  echo "log: ${log_path}"
  set +e
  "$@" > >(tee "$log_path") 2>&1
  local rc=$?
  set -e
  if [[ "$rc" -ne 0 ]]; then
    printf '%s\tFAIL\t%s\t%s\n' "$name" "$rc" "$log_path" \
      >> "$LOG_DIR/summary.tsv"
    return "$rc"
  fi
  PASS_COUNT=$((PASS_COUNT + 1))
  printf '%s\tPASS\t0\t%s\n' "$name" "$log_path" \
    >> "$LOG_DIR/summary.tsv"
}

collect_process_tree() {
  local root_pid="$1"
  local child_pid
  for child_pid in $(pgrep -P "$root_pid" 2>/dev/null || true); do
    collect_process_tree "$child_pid"
  done
  printf '%s\n' "$root_pid"
}

stop_pid() {
  local root_pid="$1"
  [[ -n "$root_pid" ]] || return 0
  kill -0 "$root_pid" >/dev/null 2>&1 || return 0
  local process_tree pid attempt alive
  process_tree="$(collect_process_tree "$root_pid")"
  kill $process_tree >/dev/null 2>&1 || true
  for attempt in $(seq 1 50); do
    alive=false
    for pid in $process_tree; do
      if kill -0 "$pid" >/dev/null 2>&1; then
        alive=true
        break
      fi
    done
    [[ "$alive" == "false" ]] && break
    sleep 0.2
  done
  for pid in $process_tree; do
    kill -9 "$pid" >/dev/null 2>&1 || true
  done
}

write_summary() {
  local exit_code="$1"
  local result="PASS"
  [[ "$exit_code" -eq 0 ]] || result="FAIL"
  {
    echo "# Collection provisioning verification"
    echo
    echo "- Run: \`${RUN_ID}\`"
    echo "- Generated: \`$(date '+%Y-%m-%d %H:%M:%S %z')\`"
    echo "- Branch: \`$(git branch --show-current)\`"
    echo "- Commit: \`$(git rev-parse HEAD)\`"
    echo "- Result: **${result}**"
    echo "- Passed steps: **${PASS_COUNT}**"
    echo "- Phase: \`${VERIFY_PHASE}\`"
    echo
    echo "| Step | Status | Exit | Evidence |"
    echo "|------|--------|------|----------|"
    while IFS=$'\t' read -r name status code evidence; do
      echo "| ${name} | ${status} | ${code} | \`${evidence}\` |"
    done < "$LOG_DIR/summary.tsv"
  } > "$LOG_DIR/summary.md"
}

cleanup() {
  local exit_code=$?
  stop_pid "$BACKEND_A_PID"
  stop_pid "$BACKEND_B_PID"
  if [[ -n "$POSTGRES_CONTAINER" ]]; then
    docker rm -f "$POSTGRES_CONTAINER" >/dev/null 2>&1 || true
  fi
  rm -rf "$PRIVATE_DIR"
  write_summary "$exit_code"
}
trap cleanup EXIT
trap 'exit 130' INT TERM

prerequisites() {
  case "$VERIFY_PHASE" in
    all|http) ;;
    *)
      echo "COLLECTION_PROVISIONING_VERIFY_PHASE must be all or http" >&2
      return 1
      ;;
  esac
  local command_name port
  for command_name in curl docker git java jq lsof mvn openssl pgrep rg; do
    command -v "$command_name" >/dev/null || {
      echo "Missing required command: ${command_name}" >&2
      return 1
    }
  done
  docker version >/dev/null
  for port in "$BACKEND_A_PORT" "$BACKEND_B_PORT"; do
    [[ "$port" =~ ^[0-9]+$ ]] && (( port >= 1 && port <= 65535 )) || {
      echo "Invalid verification port: ${port}" >&2
      return 1
    }
    if lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then
      echo "Verification port is already in use: ${port}" >&2
      return 1
    fi
  done
  [[ "$BACKEND_A_PORT" != "$BACKEND_B_PORT" ]] || {
    echo "Backend verification ports must be distinct" >&2
    return 1
  }
}

focused_tests() {
  mvn -pl spring-ai-rag-core -am \
    -Dtest=CollectionProvisioningFingerprintTest,\
ProvisioningOwnerResolverTest,RagCollectionProvisioningPropertiesTest,\
CollectionProvisioningServiceTest,RagCollectionControllerTest,\
IntegrationCapabilitiesControllerTest,IntegrationCapabilityCatalogTest,\
OpenApiContractTest \
    -Dsurefire.failIfNoSpecifiedTests=false test
}

postgres_tests() {
  TESTCONTAINERS_RYUK_DISABLED=true \
    mvn -pl spring-ai-rag-core -am \
      -Dcollection-provisioning.it.enabled=true \
      -Dtest=CollectionProvisioningPostgresIntegrationTest \
      -Dsurefire.failIfNoSpecifiedTests=false test
  local report="spring-ai-rag-core/target/surefire-reports/TEST-com.springairag.core.integration.CollectionProvisioningPostgresIntegrationTest.xml"
  [[ -f "$report" ]] || return 1
  rg -q 'tests="9"' "$report" || return 1
  rg -q 'failures="0"' "$report" || return 1
  rg -q 'errors="0"' "$report" || return 1
  rg -q 'skipped="0"' "$report"
}

prepare_runtime() {
  mvn -q -pl spring-ai-rag-core -am -DskipTests compile
  local classpath_file="${LOG_DIR}/runtime-classpath.txt"
  mvn -q -pl spring-ai-rag-core -am dependency:build-classpath \
    "-Dmdep.outputFile=${PWD}/${classpath_file}" \
    -DincludeScope=runtime
  RUNTIME_CLASSPATH="spring-ai-rag-core/target/classes:"
  RUNTIME_CLASSPATH+="spring-ai-rag-api/target/classes:"
  RUNTIME_CLASSPATH+="spring-ai-rag-documents/target/classes:"
  RUNTIME_CLASSPATH+="spring-ai-rag-starter/target/classes:"
  RUNTIME_CLASSPATH+="$(<"$classpath_file")"
}

start_postgres() {
  POSTGRES_CONTAINER="spring-ai-rag-collection-provisioning-${RUN_ID}"
  docker run -d --rm \
    --name "$POSTGRES_CONTAINER" \
    -e POSTGRES_DB="$POSTGRES_DATABASE" \
    -e POSTGRES_USER="$POSTGRES_USERNAME" \
    -e POSTGRES_PASSWORD="$POSTGRES_PASSWORD" \
    -p 127.0.0.1::5432 \
    "$POSTGRES_IMAGE" >/dev/null
  local attempt ready_streak=0 state
  for attempt in $(seq 1 120); do
    if docker exec "$POSTGRES_CONTAINER" \
        pg_isready -U "$POSTGRES_USERNAME" \
        -d "$POSTGRES_DATABASE" >/dev/null 2>&1; then
      ready_streak=$((ready_streak + 1))
      (( ready_streak >= 3 )) && break
    else
      ready_streak=0
    fi
    state="$(docker inspect --format '{{.State.Status}}' \
      "$POSTGRES_CONTAINER" 2>/dev/null || true)"
    [[ "$state" == "running" ]] || return 1
    sleep 1
  done
  (( ready_streak >= 3 )) || return 1
  POSTGRES_PORT="$(docker port "$POSTGRES_CONTAINER" 5432/tcp \
    | awk -F: 'NR == 1 {print $NF}')"
  [[ -n "$POSTGRES_PORT" ]]
}

start_backend() {
  local port="$1" log_path="$2"
  env \
    SPRING_PROFILES_ACTIVE=postgresql \
    SERVER_PORT="$port" \
    SPRING_DATASOURCE_URL="jdbc:postgresql://127.0.0.1:${POSTGRES_PORT}/${POSTGRES_DATABASE}" \
    SPRING_DATASOURCE_USERNAME="$POSTGRES_USERNAME" \
    SPRING_DATASOURCE_PASSWORD="$POSTGRES_PASSWORD" \
    RAG_SECURITY_ENABLED=true \
    RAG_ROOT_API_KEY="$ROOT_KEY" \
    RAG_RATE_LIMIT_ENABLED=false \
    RAG_COLLECTION_PROVISIONING_ENABLED=true \
    APP_LLM_PROVIDER=openai \
    SPRING_AI_OPENAI_API_KEY=dummy \
    SPRING_AI_OPENAI_BASE_URL=http://127.0.0.1:9 \
    SPRING_AI_OPENAI_CHAT_OPTIONS_MODEL=dummy-chat \
    RAG_EMBEDDING_API_KEY=dummy \
    RAG_EMBEDDING_BASE_URL=http://127.0.0.1:9 \
    RAG_EMBEDDING_MODEL=dummy-embedding \
    RAG_EMBEDDING_DIMENSIONS=1024 \
    RAG_EMBEDDING_PROFILE_KEY=collection-provisioning-gate-1024-v1 \
    RAG_EMBEDDING_PROVIDER=verification \
    RAG_EMBEDDING_MODEL_REVISION=v1 \
    java -cp "$RUNTIME_CLASSPATH" \
      com.springairag.core.SpringAiRagApplication \
      > "$log_path" 2>&1 &
  printf '%s\n' "$!"
}

wait_backend() {
  local port="$1" pid="$2" log_path="$3" attempt
  for attempt in $(seq 1 120); do
    if curl -fsS --connect-timeout 1 --max-time 2 \
        "http://127.0.0.1:${port}/actuator/health/readiness" \
        >/dev/null 2>&1; then
      kill -0 "$pid" >/dev/null 2>&1 || return 1
      return 0
    fi
    kill -0 "$pid" >/dev/null 2>&1 || {
      tail -100 "$log_path" >&2 || true
      return 1
    }
    sleep 1
  done
  tail -100 "$log_path" >&2 || true
  return 1
}

start_backends() {
  BACKEND_A_PID="$(start_backend \
    "$BACKEND_A_PORT" "$LOG_DIR/backend-a.log")"
  wait_backend \
    "$BACKEND_A_PORT" "$BACKEND_A_PID" "$LOG_DIR/backend-a.log"
  BACKEND_B_PID="$(start_backend \
    "$BACKEND_B_PORT" "$LOG_DIR/backend-b.log")"
  wait_backend \
    "$BACKEND_B_PORT" "$BACKEND_B_PID" "$LOG_DIR/backend-b.log"
}

root_request() {
  curl -sS -H "X-API-Key: ${ROOT_KEY}" "$@"
}

assert_code() {
  local actual="$1" expected="$2" label="$3"
  [[ "$actual" == "$expected" ]] || {
    echo "${label}: expected HTTP ${expected}, got ${actual}" >&2
    return 1
  }
  echo "PASS: ${label} (HTTP ${actual})"
}

create_principal() {
  local base="$1" request_file="$2" response_file="$3"
  root_request --request POST \
    --header 'Content-Type: application/json' \
    --data-binary "@${request_file}" \
    --output "$response_file" \
    --write-out '%{http_code}' \
    "${base}/api/v1/rag/api-keys"
}

database_scalar() {
  docker exec "$POSTGRES_CONTAINER" psql \
    -U "$POSTGRES_USERNAME" \
    -d "$POSTGRES_DATABASE" \
    -Atqc "$1"
}

http_contract() {
  local a="http://127.0.0.1:${BACKEND_A_PORT}"
  local b="http://127.0.0.1:${BACKEND_B_PORT}"
  local provisioning_key="cp-${RUN_ID}-$(openssl rand -hex 12)"
  local denied_key="cp-denied-${RUN_ID}-$(openssl rand -hex 8)"
  local failure_key="cp-failure-${RUN_ID}-$(openssl rand -hex 8)"
  local token collection_key owner_collection_key denied_collection_key
  token="$(printf '%s' "$RUN_ID" | tr -cd 'A-Za-z0-9' | tail -c 20)"
  collection_key="cp.${token}.root"
  owner_collection_key="cp.${token}.owner"
  denied_collection_key="cp.${token}.denied"

  local code
  code="$(root_request --output "$PRIVATE_DIR/capabilities.json" \
    --write-out '%{http_code}' \
    "${a}/api/v1/rag/integration-capabilities")"
  assert_code "$code" 200 "capability discovery" || return 1
  jq -e '
    .protocol.version == "1.0"
    and .features.provisioning.collectionCreateIdempotencyKey == true
  ' "$PRIVATE_DIR/capabilities.json" >/dev/null || return 1

  jq -n --arg key "$collection_key" \
    '{
      collectionKey:$key,
      name:"Collection provisioning gate",
      description:"Disposable idempotency acceptance",
      metadata:{z:3,nested:{b:2,a:1}}
    }' > "$PRIVATE_DIR/root-create.json"
  code="$(root_request --request POST \
    --header 'Content-Type: application/json' \
    --header "Idempotency-Key: ${provisioning_key}" \
    --data-binary "@${PRIVATE_DIR}/root-create.json" \
    --dump-header "$PRIVATE_DIR/root-first.headers" \
    --output "$PRIVATE_DIR/root-first.json" \
    --write-out '%{http_code}' \
    "${a}/api/v1/rag/collections")"
  assert_code "$code" 201 "first keyed Collection create" || return 1
  ! rg -qi '^X-RAG-Idempotent-Replay:' \
    "$PRIVATE_DIR/root-first.headers" || return 1
  local root_collection_id
  root_collection_id="$(jq -er '.id' "$PRIVATE_DIR/root-first.json")"

  code="$(root_request --request POST \
    --header 'Content-Type: application/json' \
    --header "Idempotency-Key: ${provisioning_key}" \
    --data-binary "@${PRIVATE_DIR}/root-create.json" \
    --dump-header "$PRIVATE_DIR/root-replay.headers" \
    --output "$PRIVATE_DIR/root-replay.json" \
    --write-out '%{http_code}' \
    "${b}/api/v1/rag/collections")"
  assert_code "$code" 200 "cross-instance exact replay" || return 1
  grep -qi '^X-RAG-Idempotent-Replay:[[:space:]]*true' \
    "$PRIVATE_DIR/root-replay.headers" || return 1
  [[ "$(jq -r '.id' "$PRIVATE_DIR/root-replay.json")" \
      == "$root_collection_id" ]] || return 1

  jq -n --arg key "$collection_key" \
    '{
      metadata:{nested:{a:1,b:2},z:3},
      enabled:true,
      dimensions:1024,
      description:"Disposable idempotency acceptance",
      name:"Collection provisioning gate",
      collectionKey:$key
    }' > "$PRIVATE_DIR/root-canonical-replay.json"
  code="$(root_request --request POST \
    --header 'Content-Type: application/json' \
    --header "Idempotency-Key: ${provisioning_key}" \
    --data-binary "@${PRIVATE_DIR}/root-canonical-replay.json" \
    --output "$PRIVATE_DIR/root-canonical-response.json" \
    --write-out '%{http_code}' \
    "${b}/api/v1/rag/collections")"
  assert_code "$code" 200 "canonical request replay" || return 1
  [[ "$(jq -r '.id' "$PRIVATE_DIR/root-canonical-response.json")" \
      == "$root_collection_id" ]] || return 1

  jq '.name = "Conflicting Collection name"' \
    "$PRIVATE_DIR/root-create.json" \
    > "$PRIVATE_DIR/root-conflict.json"
  code="$(root_request --request POST \
    --header 'Content-Type: application/json' \
    --header "Idempotency-Key: ${provisioning_key}" \
    --data-binary "@${PRIVATE_DIR}/root-conflict.json" \
    --output "$PRIVATE_DIR/root-conflict-response.json" \
    --write-out '%{http_code}' \
    "${a}/api/v1/rag/collections")"
  assert_code "$code" 409 "fingerprint conflict" || return 1
  jq -e '.error == "IDEMPOTENCY_KEY_REUSED"' \
    "$PRIVATE_DIR/root-conflict-response.json" >/dev/null || return 1

  jq -n '{
    name:"Unrestricted provisioning client",
    expiresAt:"2099-12-31T23:59:00",
    requestsPerMinute:1000,
    capabilities:["RAG_READ","RAG_WRITE"]
  }' > "$PRIVATE_DIR/unrestricted-principal.json"
  code="$(create_principal \
    "$a" "$PRIVATE_DIR/unrestricted-principal.json" \
    "$PRIVATE_DIR/unrestricted-principal-response.json")"
  assert_code "$code" 201 "create unrestricted database principal" || return 1
  local unrestricted_key unrestricted_key_id unrestricted_principal_id
  unrestricted_key="$(jq -er '.rawKey' \
    "$PRIVATE_DIR/unrestricted-principal-response.json")"
  unrestricted_key_id="$(jq -er '.keyId' \
    "$PRIVATE_DIR/unrestricted-principal-response.json")"
  unrestricted_principal_id="$(jq -er '.principalId' \
    "$PRIVATE_DIR/unrestricted-principal-response.json")"

  jq -n --arg key "$owner_collection_key" \
    '{
      collectionKey:$key,
      name:"Owner-isolated Collection",
      metadata:{owner:"database-principal"}
    }' > "$PRIVATE_DIR/owner-create.json"
  code="$(curl -sS --request POST \
    --header "X-API-Key: ${unrestricted_key}" \
    --header 'Content-Type: application/json' \
    --header "Idempotency-Key: ${provisioning_key}" \
    --data-binary "@${PRIVATE_DIR}/owner-create.json" \
    --output "$PRIVATE_DIR/owner-response.json" \
    --write-out '%{http_code}' \
    "${b}/api/v1/rag/collections")"
  assert_code "$code" 201 "same key under another owner" || return 1

  jq -n --arg key "$collection_key" '{
    name:"Restricted provisioning client",
    expiresAt:"2099-12-31T23:59:00",
    allowedCollectionKeys:[$key],
    requestsPerMinute:1000,
    capabilities:["RAG_READ","RAG_WRITE"]
  }' > "$PRIVATE_DIR/restricted-principal.json"
  code="$(create_principal \
    "$a" "$PRIVATE_DIR/restricted-principal.json" \
    "$PRIVATE_DIR/restricted-principal-response.json")"
  assert_code "$code" 201 "create restricted database principal" || return 1
  local restricted_key restricted_key_id operations_before operations_after
  restricted_key="$(jq -er '.rawKey' \
    "$PRIVATE_DIR/restricted-principal-response.json")"
  restricted_key_id="$(jq -er '.keyId' \
    "$PRIVATE_DIR/restricted-principal-response.json")"
  operations_before="$(database_scalar \
    'SELECT count(*) FROM rag_collection_provisioning_operation')"
  jq -n --arg key "$denied_collection_key" \
    '{collectionKey:$key,name:"Denied Collection"}' \
    > "$PRIVATE_DIR/denied-create.json"
  code="$(curl -sS --request POST \
    --header "X-API-Key: ${restricted_key}" \
    --header 'Content-Type: application/json' \
    --header "Idempotency-Key: ${denied_key}" \
    --data-binary "@${PRIVATE_DIR}/denied-create.json" \
    --output "$PRIVATE_DIR/denied-response.json" \
    --write-out '%{http_code}' \
    "${a}/api/v1/rag/collections")"
  assert_code "$code" 403 "restricted principal create denial" || return 1
  operations_after="$(database_scalar \
    'SELECT count(*) FROM rag_collection_provisioning_operation')"
  [[ "$operations_after" == "$operations_before" ]] || return 1

  code="$(root_request --request DELETE --get \
    --data-urlencode "collectionKey=${collection_key}" \
    --output "$PRIVATE_DIR/root-delete.json" \
    --write-out '%{http_code}' \
    "${a}/api/v1/rag/collections/by-key")"
  assert_code "$code" 200 "soft-delete provisioned Collection" || return 1
  local version_after_delete
  version_after_delete="$(database_scalar \
    "SELECT version FROM rag_collection WHERE id=${root_collection_id}")"
  code="$(root_request --request POST \
    --header 'Content-Type: application/json' \
    --header "Idempotency-Key: ${provisioning_key}" \
    --data-binary "@${PRIVATE_DIR}/root-create.json" \
    --output "$PRIVATE_DIR/root-deleted-replay.json" \
    --write-out '%{http_code}' \
    "${b}/api/v1/rag/collections")"
  assert_code "$code" 200 "soft-deleted current-state replay" || return 1
  jq -e --argjson id "$root_collection_id" \
    '.id == $id and .deleted == true and .documentCount == 0' \
    "$PRIVATE_DIR/root-deleted-replay.json" >/dev/null || return 1
  local replay_version create_audit_count
  replay_version="$(database_scalar \
      "SELECT version FROM rag_collection WHERE id=${root_collection_id}")"
  [[ "$replay_version" == "$version_after_delete" ]] || {
    echo "Replay changed Collection version: before=${version_after_delete}, after=${replay_version}" >&2
    return 1
  }
  create_audit_count="$(database_scalar \
      "SELECT count(*) FROM rag_audit_log
       WHERE operation='CREATE' AND entity_type='Collection'
         AND entity_id='${root_collection_id}'")"
  [[ "$create_audit_count" == "1" ]] || {
    echo "Expected one Collection create audit, found ${create_audit_count}" >&2
    return 1
  }

  stop_pid "$BACKEND_A_PID"
  stop_pid "$BACKEND_B_PID"
  BACKEND_A_PID=""
  BACKEND_B_PID=""
  BACKEND_A_PID="$(start_backend \
    "$BACKEND_A_PORT" "$LOG_DIR/backend-a-restart.log")"
  wait_backend \
    "$BACKEND_A_PORT" "$BACKEND_A_PID" "$LOG_DIR/backend-a-restart.log"
  BACKEND_B_PID="$(start_backend \
    "$BACKEND_B_PORT" "$LOG_DIR/backend-b-restart.log")"
  wait_backend \
    "$BACKEND_B_PORT" "$BACKEND_B_PID" "$LOG_DIR/backend-b-restart.log"
  code="$(root_request --request POST \
    --header 'Content-Type: application/json' \
    --header "Idempotency-Key: ${provisioning_key}" \
    --data-binary "@${PRIVATE_DIR}/root-create.json" \
    --dump-header "$PRIVATE_DIR/root-restart-replay.headers" \
    --output "$PRIVATE_DIR/root-restart-replay.json" \
    --write-out '%{http_code}' \
    "${a}/api/v1/rag/collections")"
  assert_code "$code" 200 "replay after both instances restart" || return 1
  grep -qi '^X-RAG-Idempotent-Replay:[[:space:]]*true' \
    "$PRIVATE_DIR/root-restart-replay.headers" || return 1
  jq -e --argjson id "$root_collection_id" \
    '.id == $id and .deleted == true' \
    "$PRIVATE_DIR/root-restart-replay.json" >/dev/null || return 1

  docker exec "$POSTGRES_CONTAINER" psql \
    -U "$POSTGRES_USERNAME" \
    -d "$POSTGRES_DATABASE" \
    -v ON_ERROR_STOP=1 \
    -c 'ALTER TABLE rag_collection_provisioning_operation
        RENAME TO rag_collection_provisioning_operation_unavailable' \
    >/dev/null
  jq -n --arg key "cp.${token}.failure" \
    '{collectionKey:$key,name:"Must not be created"}' \
    > "$PRIVATE_DIR/failure-create.json"
  code="$(root_request --request POST \
    --header 'Content-Type: application/json' \
    --header "Idempotency-Key: ${failure_key}" \
    --data-binary "@${PRIVATE_DIR}/failure-create.json" \
    --output "$PRIVATE_DIR/failure-response.json" \
    --write-out '%{http_code}' \
    "${b}/api/v1/rag/collections")"
  docker exec "$POSTGRES_CONTAINER" psql \
    -U "$POSTGRES_USERNAME" \
    -d "$POSTGRES_DATABASE" \
    -v ON_ERROR_STOP=1 \
    -c 'ALTER TABLE rag_collection_provisioning_operation_unavailable
        RENAME TO rag_collection_provisioning_operation' \
    >/dev/null
  assert_code "$code" 503 "ledger failure closes keyed create" || return 1
  jq -e '.error == "SERVICE_UNAVAILABLE"' \
    "$PRIVATE_DIR/failure-response.json" >/dev/null || return 1
  [[ "$(database_scalar \
      "SELECT count(*) FROM rag_collection
       WHERE collection_key='cp.${token}.failure'")" == "0" ]] || return 1

  local database_facts
  database_facts="$(database_scalar "
    SELECT
      (SELECT version FROM flyway_schema_history
       WHERE success ORDER BY installed_rank DESC LIMIT 1)
      || ',' ||
      (SELECT count(*) FROM rag_collection_provisioning_operation)
      || ',' ||
      (SELECT count(*) FROM rag_collection
       WHERE collection_key IN ('${collection_key}','${owner_collection_key}'))
      || ',' ||
      (SELECT count(*) FROM rag_api_key WHERE api_key IS NOT NULL)
  ")"
  [[ "$database_facts" == "52,2,2,0" ]] || {
    echo "Unexpected database facts: ${database_facts}" >&2
    return 1
  }
  [[ "$(database_scalar "
      SELECT count(*) FROM rag_collection_provisioning_operation
      WHERE owner_id='root:environment-root'
         OR owner_id='db:${unrestricted_principal_id}'
    ")" == "2" ]] || return 1

  root_request --request DELETE \
    --output /dev/null \
    "${a}/api/v1/rag/api-keys/${restricted_key_id}" >/dev/null
  root_request --request DELETE \
    --output /dev/null \
    "${a}/api/v1/rag/api-keys/${unrestricted_key_id}" >/dev/null
  root_request --request DELETE --get \
    --data-urlencode "collectionKey=${owner_collection_key}" \
    --output /dev/null \
    "${a}/api/v1/rag/collections/by-key" >/dev/null

  rm -f "$PRIVATE_DIR"/*
  echo "collection_provisioning_contract=PASS"
  echo "cross_instance=true restart=true owner_isolation=true acl=true"
  echo "soft_delete_current_state=true audit_create_once=true fail_closed=true"
  echo "database_facts=migration_52 ledger_2 collections_2 plaintext_credentials_0"
}

run_step "Prerequisites and isolated ports" prerequisites
if [[ "$VERIFY_PHASE" == "all" ]]; then
  run_step "Focused backend contract tests" focused_tests
  run_step "PostgreSQL integration matrix" postgres_tests
fi
run_step "Runtime classpath" prepare_runtime
ROOT_KEY="collection-provisioning-root-$(openssl rand -hex 32)"
run_step "Disposable PostgreSQL startup" start_postgres
run_step "Two backend startup" start_backends
run_step "Dual-instance restart HTTP contract" http_contract

echo
echo "Collection provisioning verification passed: ${PASS_COUNT} steps"
echo "Summary: ${LOG_DIR}/summary.md"
