#!/usr/bin/env bash
# Stable managed API principal, shared quota, WebUI and optional real-LLM gate.
set -uo pipefail

cd "$(dirname "$0")/.."

RUN_ID="${MANAGED_API_VERIFY_RUN_ID:-$(date +%Y%m%d-%H%M%S)}"
LOG_DIR="${MANAGED_API_VERIFY_LOG_DIR:-.verification/managed-api-principals/${RUN_ID}}"
ENV_FILE="${MANAGED_API_REAL_ENV_FILE:-.env}"
BACKEND_A_PORT="${MANAGED_API_BACKEND_A_PORT:-18181}"
BACKEND_B_PORT="${MANAGED_API_BACKEND_B_PORT:-18182}"
FRONTEND_PORT="${MANAGED_API_FRONTEND_PORT:-15181}"
MOCK_PORT="${MANAGED_API_MOCK_PORT:-4199}"
PG_IMAGE="${TESTCONTAINERS_PG_IMAGE:-pgvector/pgvector:pg16}"
RUN_REAL_LLM=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --with-real-llm) RUN_REAL_LLM=1 ;;
    -h|--help)
      echo "Usage: $0 [--with-real-llm]"
      exit 0
      ;;
    *) echo "Unknown option: $1" >&2; exit 2 ;;
  esac
  shift
done

mkdir -p "$LOG_DIR/private"
chmod 700 "$LOG_DIR/private"
: > "$LOG_DIR/summary.tsv"

PASS_COUNT=0
FAIL_COUNT=0
STEP_INDEX=0
PG_CONTAINER=""
BACKEND_A_PID=""
BACKEND_B_PID=""
FRONTEND_PID=""
MOCK_PID=""
ROOT_KEY=""
RUNTIME_CLASSPATH=""
DB_PORT=""
CURRENT_STEP_NAME=""
CURRENT_STEP_LOG=""

slugify() {
  printf '%s' "$1" | tr '[:upper:] ' '[:lower:]-' | tr -cd 'a-z0-9._-'
}

record() {
  printf '%s|%s|%s|%s\n' "$1" "$2" "$3" "$4" >> "$LOG_DIR/summary.tsv"
  if [[ "$2" == "PASS" ]]; then
    PASS_COUNT=$((PASS_COUNT + 1))
  else
    FAIL_COUNT=$((FAIL_COUNT + 1))
  fi
}

run_step() {
  local name="$1"
  shift
  STEP_INDEX=$((STEP_INDEX + 1))
  local log="$LOG_DIR/${STEP_INDEX}-$(slugify "$name").log"
  CURRENT_STEP_NAME="$name"
  CURRENT_STEP_LOG="$log"
  echo
  echo "=== ${name} ==="
  "$@" > >(tee "$log") 2>&1
  local rc=$?
  if [[ "$rc" -eq 0 ]]; then
    echo "PASS: ${name}"
    record "$name" PASS 0 "$log"
  else
    echo "FAIL: ${name} (exit ${rc})" >&2
    record "$name" FAIL "$rc" "$log"
  fi
  CURRENT_STEP_NAME=""
  CURRENT_STEP_LOG=""
  return 0
}

write_summary() {
  local result="PASS"
  [[ "$FAIL_COUNT" -gt 0 ]] && result="FAIL"
  {
    echo "# Managed API principal verification"
    echo
    echo "- Run: \`${RUN_ID}\`"
    echo "- Generated: \`$(date '+%Y-%m-%d %H:%M:%S %z')\`"
    echo "- Branch: \`$(git branch --show-current)\`"
    echo "- Commit: \`$(git rev-parse --short HEAD)\`"
    echo "- Result: **${result}**"
    echo "- Counts: ${PASS_COUNT} passed, ${FAIL_COUNT} failed"
    echo "- Real LLM requested: \`${RUN_REAL_LLM}\`"
    echo
    echo "| Step | Status | Exit | Evidence |"
    echo "|------|--------|------|----------|"
    while IFS='|' read -r name status code evidence; do
      echo "| ${name} | ${status} | ${code} | \`${evidence}\` |"
    done < "$LOG_DIR/summary.tsv"
  } > "$LOG_DIR/summary.md"
}

stop_pid() {
  local pid="$1"
  if [[ -n "$pid" ]] && kill -0 "$pid" >/dev/null 2>&1; then
    kill "$pid" >/dev/null 2>&1 || true
    wait "$pid" >/dev/null 2>&1 || true
  fi
}

cleanup() {
  stop_pid "$FRONTEND_PID"
  stop_pid "$MOCK_PID"
  stop_pid "$BACKEND_A_PID"
  stop_pid "$BACKEND_B_PID"
  if [[ -n "$PG_CONTAINER" ]]; then
    docker rm -f "$PG_CONTAINER" >/dev/null 2>&1 || true
  fi
  write_summary
}

interrupt() {
  if [[ -n "$CURRENT_STEP_NAME" ]]; then
    record "$CURRENT_STEP_NAME" FAIL 130 "$CURRENT_STEP_LOG"
    CURRENT_STEP_NAME=""
    CURRENT_STEP_LOG=""
  fi
  exit 130
}

trap cleanup EXIT
trap interrupt INT TERM

prerequisites() {
  local command_name
  for command_name in bash curl docker git java jq lsof mvn node npm npx openssl rg; do
    command -v "$command_name" >/dev/null || {
      echo "Missing required command: ${command_name}" >&2
      return 1
    }
  done
  docker version >/dev/null
  for port in "$BACKEND_A_PORT" "$BACKEND_B_PORT" "$FRONTEND_PORT" "$MOCK_PORT"; do
    if lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then
      echo "Verification port is already in use: ${port}" >&2
      return 1
    fi
  done
  if [[ "$RUN_REAL_LLM" == "1" && ! -f "$ENV_FILE" ]]; then
    echo "Real LLM environment file does not exist: ${ENV_FILE}" >&2
    return 1
  fi
}

postgres_matrix() {
  TESTCONTAINERS_RYUK_DISABLED=true \
    mvn -q -pl spring-ai-rag-core -am \
      -Dchat.it.enabled=true \
      -Dchat.idempotency.it.enabled=true \
      -Dnext-high-value.it.enabled=true \
      -Dmanaged-api-principal.it.enabled=true \
      -Djacoco.skip=true \
      -Dtest=ChatSessionPostgresIntegrationTest,ChatTurnOperationPostgresIntegrationTest,NextHighValueFeaturesPostgresIntegrationTest,ManagedApiPrincipalPostgresIntegrationTest \
      -Dsurefire.failIfNoSpecifiedTests=false test

  local report
  for report in \
      ChatSessionPostgresIntegrationTest \
      ChatTurnOperationPostgresIntegrationTest \
      NextHighValueFeaturesPostgresIntegrationTest \
      ManagedApiPrincipalPostgresIntegrationTest; do
    local xml="spring-ai-rag-core/target/surefire-reports/TEST-com.springairag.core.integration.${report}.xml"
    [[ -f "$xml" ]] || return 1
    rg -q 'failures="0"' "$xml" || return 1
    rg -q 'errors="0"' "$xml" || return 1
    rg -q 'skipped="0"' "$xml" || return 1
  done
}

maven_compile() { mvn clean compile test-compile; }
maven_test() { mvn test; }

webui_vitest() { (cd spring-ai-rag-webui && npm run test:run); }
webui_typecheck() { (cd spring-ai-rag-webui && npm run typecheck); }
webui_build() { (cd spring-ai-rag-webui && npm run build); }
webui_alignment() { (cd spring-ai-rag-webui && npm run check:alignment); }

mock_playwright() {
  local preview_log="$LOG_DIR/mock-preview.log"
  (
    cd spring-ai-rag-webui
    exec npx vite preview --host 127.0.0.1 --port "$MOCK_PORT" --strictPort
  ) > "$preview_log" 2>&1 &
  MOCK_PID=$!
  local attempt
  for attempt in $(seq 1 30); do
    curl -fsS "http://127.0.0.1:${MOCK_PORT}/webui/" >/dev/null 2>&1 && break
    kill -0 "$MOCK_PID" >/dev/null 2>&1 || return 1
    sleep 1
  done
  curl -fsS "http://127.0.0.1:${MOCK_PORT}/webui/" >/dev/null || return 1
  (
    cd spring-ai-rag-webui
    BASE_URL="http://127.0.0.1:${MOCK_PORT}" \
      npx playwright test e2e/api-key-mvp.spec.ts --project=chromium
  )
  local rc=$?
  stop_pid "$MOCK_PID"
  MOCK_PID=""
  return "$rc"
}

prepare_runtime() {
  local classpath_file="$LOG_DIR/runtime-classpath.txt"
  mvn -q -pl spring-ai-rag-core -am dependency:build-classpath \
    "-Dmdep.outputFile=${PWD}/${classpath_file}" -DincludeScope=runtime
  RUNTIME_CLASSPATH="spring-ai-rag-core/target/classes:"
  RUNTIME_CLASSPATH+="spring-ai-rag-api/target/classes:"
  RUNTIME_CLASSPATH+="spring-ai-rag-documents/target/classes:"
  RUNTIME_CLASSPATH+="spring-ai-rag-starter/target/classes:"
  RUNTIME_CLASSPATH+="$(cat "$classpath_file")"
}

start_database() {
  PG_CONTAINER="$(docker run -d --rm \
    -e POSTGRES_PASSWORD=postgres \
    -e POSTGRES_DB=managed_api_principal_gate \
    -p 127.0.0.1::5432 "$PG_IMAGE")" || {
      echo "Failed to start disposable PostgreSQL" >&2
      return 1
    }
  local attempt
  local container_state
  local ready_streak=0
  for attempt in $(seq 1 20); do
    DB_PORT="$(docker port "$PG_CONTAINER" 5432/tcp \
      | awk -F: 'NR == 1 {print $NF}')"
    [[ -n "$DB_PORT" ]] && break
    sleep 1
  done
  [[ -n "$DB_PORT" ]] || {
    echo "Disposable PostgreSQL published port was not discoverable" >&2
    return 1
  }
  for attempt in $(seq 1 180); do
    container_state="$(docker inspect --format '{{.State.Status}}' "$PG_CONTAINER" 2>/dev/null || true)"
    if [[ "$container_state" != "running" ]]; then
      echo "Disposable PostgreSQL stopped before becoming ready (state=${container_state:-missing})" >&2
      docker logs "$PG_CONTAINER" 2>&1 || true
      return 1
    fi
    if docker exec "$PG_CONTAINER" pg_isready -U postgres \
      -d managed_api_principal_gate >/dev/null 2>&1; then
      ready_streak=$((ready_streak + 1))
      [[ "$ready_streak" -ge 3 ]] && return 0
    else
      ready_streak=0
    fi
    sleep 1
  done
  echo "Disposable PostgreSQL did not become stably ready" >&2
  docker inspect --format 'state={{.State.Status}} exit={{.State.ExitCode}} error={{.State.Error}}' \
    "$PG_CONTAINER" 2>&1 || true
  docker logs "$PG_CONTAINER" 2>&1 || true
  return 1
}

load_provider_environment() {
  ROOT_KEY="managed-root-$(openssl rand -hex 32)"
  if [[ "$RUN_REAL_LLM" == "1" ]]; then
    set +u
    set -a
    # shellcheck disable=SC1090
    source "$ENV_FILE"
    set +a
    set -u
    export APP_LLM_PROVIDER=openai
    export LLM_PROVIDER=openai
    [[ -n "${SPRING_AI_OPENAI_API_KEY:-}" ]] || {
      echo "SPRING_AI_OPENAI_API_KEY is missing" >&2
      return 1
    }
    [[ -n "${SPRING_AI_OPENAI_BASE_URL:-}" ]] || {
      echo "SPRING_AI_OPENAI_BASE_URL is missing" >&2
      return 1
    }
    [[ -n "${SPRING_AI_OPENAI_CHAT_OPTIONS_MODEL:-}" ]] || {
      echo "SPRING_AI_OPENAI_CHAT_OPTIONS_MODEL is missing" >&2
      return 1
    }
  else
    export APP_LLM_PROVIDER=openai
    export SPRING_AI_OPENAI_API_KEY=dummy
    export SPRING_AI_OPENAI_BASE_URL=http://127.0.0.1:9
    export SPRING_AI_OPENAI_CHAT_OPTIONS_MODEL=dummy-chat
  fi
}

start_backend() {
  local port="$1" log="$2"
  env \
    SPRING_PROFILES_ACTIVE=postgresql \
    SERVER_PORT="$port" \
    SPRING_DATASOURCE_URL="jdbc:postgresql://127.0.0.1:${DB_PORT}/managed_api_principal_gate" \
    SPRING_DATASOURCE_USERNAME=postgres \
    SPRING_DATASOURCE_PASSWORD=postgres \
    RAG_SECURITY_ENABLED=true \
    RAG_ROOT_API_KEY="$ROOT_KEY" \
    RAG_RATE_LIMIT_ENABLED=true \
    RAG_RATE_LIMIT_BACKEND=postgresql \
    RAG_RATE_LIMIT_STRATEGY=principal \
    RAG_RATE_LIMIT_REQUESTS_PER_MINUTE=1000 \
    RAG_RATE_LIMIT_CLEANUP_INTERVAL_SECONDS=60 \
    RAG_CORS_ENABLED=true \
    RAG_CORS_ALLOWED_ORIGINS_0="http://127.0.0.1:${FRONTEND_PORT}" \
    RAG_OPENAI_COMPATIBILITY_ENABLED=true \
    SPRING_APPLICATION_JSON='{"rag":{"openai-compatibility":{"enabled":true,"models":{"rag-default":{"candidates":[],"mode":"PLAIN","memory":"STATELESS"}}}}}' \
    RAG_EMBEDDING_API_KEY=dummy \
    RAG_EMBEDDING_BASE_URL=http://127.0.0.1:9 \
    RAG_EMBEDDING_MODEL=dummy-embedding \
    RAG_EMBEDDING_DIMENSIONS=1024 \
    RAG_EMBEDDING_PROFILE_KEY=managed-principal-gate-1024-v1 \
    RAG_EMBEDDING_PROVIDER=verification \
    RAG_EMBEDDING_MODEL_REVISION=v1 \
    java -cp "$RUNTIME_CLASSPATH" com.springairag.core.SpringAiRagApplication \
      > "$log" 2>&1 &
  printf '%s\n' "$!"
}

wait_backend() {
  local port="$1" pid="$2" log="$3" attempt health
  for attempt in $(seq 1 120); do
    kill -0 "$pid" >/dev/null 2>&1 || {
      tail -100 "$log" >&2 || true
      return 1
    }
    health="$(curl -fsS --max-time 2 \
      "http://127.0.0.1:${port}/actuator/health" 2>/dev/null || true)"
    [[ "$health" == *'"status":"UP"'* ]] && return 0
    sleep 1
  done
  tail -100 "$log" >&2 || true
  return 1
}

root_curl() {
  curl -sS -H "X-API-Key: ${ROOT_KEY}" "$@"
}

create_principal() {
  local base="$1" name="$2" quota="$3" output="$4"
  local capabilities="${5:-[\"RAG_READ\",\"RAG_WRITE\"]}"
  root_curl -X POST "${base}/api/v1/rag/api-keys" \
    -H 'Content-Type: application/json' \
    -d "{\"name\":\"${name}\",\"expiresAt\":\"2099-12-31T23:59:00\",\"requestsPerMinute\":${quota},\"capabilities\":${capabilities}}" \
    -o "$output" -w '%{http_code}'
}

assert_code() {
  local actual="$1" expected="$2" label="$3"
  [[ "$actual" == "$expected" ]] || {
    echo "${label}: expected HTTP ${expected}, got ${actual}" >&2
    return 1
  }
}

provider_counter_for_port() {
  local port="$1"
  local body="$LOG_DIR/private/provider-counter-${port}.json"
  local code metric
  for metric in rag.chat.provider.calls rag.chat.provider.calls.total; do
    code="$(curl -sS -o "$body" -w '%{http_code}' \
      "http://127.0.0.1:${port}/actuator/metrics/${metric}")" \
      || return 1
    if [[ "$code" == "200" ]]; then
      jq -r '.measurements[] | select(.statistic == "COUNT") | .value' "$body"
      return $?
    fi
    [[ "$code" == "404" ]] || {
      echo "Provider counter endpoint returned HTTP ${code}" >&2
      return 1
    }
  done
  printf '0\n'
}

provider_counter() {
  local count_a count_b
  count_a="$(provider_counter_for_port "$BACKEND_A_PORT")" || return 1
  count_b="$(provider_counter_for_port "$BACKEND_B_PORT")" || return 1
  awk -v a="$count_a" -v b="$count_b" 'BEGIN {print a + b}'
}

run_two_instance_contract() {
  local a="http://127.0.0.1:${BACKEND_A_PORT}"
  local b="http://127.0.0.1:${BACKEND_B_PORT}"
  local private="$LOG_DIR/private"
  local code accepted=0 rejected=0 i endpoint

  code="$(curl -sS -o "$private/capabilities-unauthenticated.json" \
    -w '%{http_code}' "${a}/api/v1/rag/integration-capabilities")"
  assert_code "$code" 401 "unauthenticated capability discovery" || return 1

  code="$(root_curl -o "$private/capabilities-root.json" -w '%{http_code}' \
    "${a}/api/v1/rag/integration-capabilities")"
  assert_code "$code" 200 "root capability discovery" || return 1
  jq -e '
    .protocol.name == "spring-ai-rag-integration"
    and .protocol.version == "1.0"
    and .protocol.apiVersion == "1.0.0"
    and .principal.principalType == "ENVIRONMENT_ROOT"
    and .principal.collectionAccessMode == "UNRESTRICTED"
    and .principal.allowedCollectionKeys == null
    and .features.provisioning.idempotencyKey == true
    and .features.provisioning.replayReturnsSecret == false
    and .features.provisioning.rawCredentialShownOnce == true
    and .features.optional.openAiCompatibility == true
  ' "$private/capabilities-root.json" >/dev/null || return 1

  local capability_collection_key="managed-capability-${RUN_ID}"
  code="$(root_curl -X POST "${a}/api/v1/rag/collections" \
    -H 'Content-Type: application/json' \
    -d "{\"collectionKey\":\"${capability_collection_key}\",\"name\":\"Managed capability ${RUN_ID}\",\"description\":\"Disposable capability projection\",\"dimensions\":1024}" \
    -o "$private/capability-collection.json" -w '%{http_code}')"
  assert_code "$code" 200 "create capability Collection" || return 1

  local provisioning_key="managed-provisioning-${RUN_ID}"
  jq -n \
    --arg name "Idempotent provisioning ${RUN_ID}" \
    --arg expiresAt "2099-12-31T23:59:00" \
    --arg collectionKey "$capability_collection_key" \
    '{
      name:$name,
      expiresAt:$expiresAt,
      requestsPerMinute:100,
      capabilities:["RAG_READ"],
      allowedCollectionKeys:[$collectionKey]
    }' > "$private/provisioning-request.json"

  code="$(root_curl -X POST "${a}/api/v1/rag/api-keys" \
    -H 'Content-Type: application/json' \
    -H "Idempotency-Key: ${provisioning_key}" \
    --data-binary "@$private/provisioning-request.json" \
    -D "$private/provisioning-first.headers" \
    -o "$private/provisioning-first.json" -w '%{http_code}')"
  assert_code "$code" 201 "first idempotent provisioning" || return 1
  jq -e '
    (.rawKey | startswith("rag_sk_"))
    and .secretAvailable == true
    and .idempotentReplay == false
    and .currentCredentialActive == true
    and .credentialVersion == 1
    and .capabilities == ["RAG_READ"]
  ' "$private/provisioning-first.json" >/dev/null || return 1
  ! grep -qi '^X-RAG-Idempotent-Replay:' \
    "$private/provisioning-first.headers" || return 1
  grep -qi '^Cache-Control:.*no-store' \
    "$private/provisioning-first.headers" || return 1

  local provisioned_principal provisioned_key_id provisioned_raw
  provisioned_principal="$(jq -r '.principalId' \
    "$private/provisioning-first.json")"
  provisioned_key_id="$(jq -r '.keyId' \
    "$private/provisioning-first.json")"
  provisioned_raw="$(jq -r '.rawKey' \
    "$private/provisioning-first.json")"

  code="$(root_curl -X POST "${b}/api/v1/rag/api-keys" \
    -H 'Content-Type: application/json' \
    -H "Idempotency-Key: ${provisioning_key}" \
    --data-binary "@$private/provisioning-request.json" \
    -D "$private/provisioning-replay.headers" \
    -o "$private/provisioning-replay.json" -w '%{http_code}')"
  assert_code "$code" 200 "cross-instance provisioning replay" || return 1
  jq -e --arg principal "$provisioned_principal" \
    --arg keyId "$provisioned_key_id" '
    .principalId == $principal
    and .keyId == $keyId
    and .rawKey == null
    and .secretAvailable == false
    and .idempotentReplay == true
    and .currentCredentialActive == true
    and .credentialVersion == 1
  ' "$private/provisioning-replay.json" >/dev/null || return 1
  grep -qi '^X-RAG-Idempotent-Replay:[[:space:]]*true' \
    "$private/provisioning-replay.headers" || return 1
  grep -qi '^Cache-Control:.*no-store' \
    "$private/provisioning-replay.headers" || return 1
  ! rg -F -- "$provisioned_raw" \
    "$private/provisioning-replay.json" >/dev/null || return 1

  jq -n \
    --arg name "Conflicting provisioning ${RUN_ID}" \
    --arg expiresAt "2099-12-31T23:59:00" \
    '{name:$name,expiresAt:$expiresAt,requestsPerMinute:100}' \
    > "$private/provisioning-conflict-request.json"
  code="$(root_curl -X POST "${b}/api/v1/rag/api-keys" \
    -H 'Content-Type: application/json' \
    -H "Idempotency-Key: ${provisioning_key}" \
    --data-binary "@$private/provisioning-conflict-request.json" \
    -o "$private/provisioning-conflict.json" -w '%{http_code}')"
  assert_code "$code" 409 "provisioning fingerprint conflict" || return 1
  jq -e '.error == "IDEMPOTENCY_KEY_REUSED"' \
    "$private/provisioning-conflict.json" >/dev/null || return 1

  code="$(curl -sS -H "X-API-Key: ${provisioned_raw}" \
    -o "$private/capabilities-restricted.json" -w '%{http_code}' \
    "${b}/api/v1/rag/integration-capabilities")"
  assert_code "$code" 200 "restricted capability discovery" || return 1
  jq -e --arg collectionKey "$capability_collection_key" '
    .principal.principalType == "DATABASE_API_KEY"
    and .principal.principalRole == "NORMAL"
    and .principal.capabilities == ["RAG_READ"]
    and .principal.collectionAccessMode == "RESTRICTED"
    and .principal.allowedCollectionKeys == [$collectionKey]
    and .features.provisioning.idempotencyKey == true
  ' "$private/capabilities-restricted.json" >/dev/null || return 1

  code="$(root_curl -X POST \
    "${a}/api/v1/rag/api-keys/${provisioned_key_id}/rotate" \
    -o "$private/provisioning-rotated.json" -w '%{http_code}')"
  assert_code "$code" 201 "rotate provisioned principal" || return 1
  local provisioned_rotated_key_id
  provisioned_rotated_key_id="$(jq -r '.keyId' \
    "$private/provisioning-rotated.json")"
  jq -e --arg principal "$provisioned_principal" '
    .principalId == $principal
    and .credentialVersion == 2
    and .secretAvailable == true
    and .currentCredentialActive == true
  ' "$private/provisioning-rotated.json" >/dev/null || return 1

  code="$(root_curl -X POST "${b}/api/v1/rag/api-keys" \
    -H 'Content-Type: application/json' \
    -H "Idempotency-Key: ${provisioning_key}" \
    --data-binary "@$private/provisioning-request.json" \
    -o "$private/provisioning-after-rotate.json" -w '%{http_code}')"
  assert_code "$code" 200 "provisioning replay after rotation" || return 1
  jq -e --arg keyId "$provisioned_rotated_key_id" '
    .keyId == $keyId
    and .credentialVersion == 2
    and .rawKey == null
    and .secretAvailable == false
    and .idempotentReplay == true
    and .currentCredentialActive == true
  ' "$private/provisioning-after-rotate.json" >/dev/null || return 1

  code="$(root_curl -X DELETE \
    "${a}/api/v1/rag/api-keys/${provisioned_rotated_key_id}" \
    -o /dev/null -w '%{http_code}')"
  assert_code "$code" 204 "revoke provisioned principal" || return 1
  code="$(root_curl -X POST "${b}/api/v1/rag/api-keys" \
    -H 'Content-Type: application/json' \
    -H "Idempotency-Key: ${provisioning_key}" \
    --data-binary "@$private/provisioning-request.json" \
    -o "$private/provisioning-after-revoke.json" -w '%{http_code}')"
  assert_code "$code" 200 "provisioning replay after revoke" || return 1
  jq -e '
    .keyId == null
    and .credentialVersion == null
    and .rawKey == null
    and .secretAvailable == false
    and .idempotentReplay == true
    and .currentCredentialActive == false
  ' "$private/provisioning-after-revoke.json" >/dev/null || return 1
  echo "provisioning_idempotency create=201 replay=200 conflict=409 rotation=current revoke=inactive"
  echo "integration_capabilities unauthenticated=401 root=unrestricted database=restricted"

  code="$(create_principal "$a" "Shared quota ${RUN_ID}" 6 "$private/quota-create.json")"
  assert_code "$code" 201 "create quota principal" || return 1
  local quota_key
  quota_key="$(jq -r '.rawKey' "$private/quota-create.json")"
  [[ "$quota_key" == rag_sk_* ]] || return 1
  for i in $(seq 1 12); do
    endpoint="$a"
    (( i % 2 == 0 )) && endpoint="$b"
    code="$(curl -sS -o "$private/quota-${i}.json" -w '%{http_code}' \
      -H "X-API-Key: ${quota_key}" "${endpoint}/api/v1/rag/auth/me")"
    if [[ "$code" == "200" ]]; then
      accepted=$((accepted + 1))
    elif [[ "$code" == "429" ]]; then
      rejected=$((rejected + 1))
    else
      echo "Unexpected shared quota status: ${code}" >&2
      return 1
    fi
  done
  [[ "$accepted" -eq 6 && "$rejected" -eq 6 ]] || {
    echo "Shared quota mismatch: accepted=${accepted}, rejected=${rejected}" >&2
    return 1
  }
  echo "shared_quota accepted=${accepted} rejected=${rejected}"

  code="$(create_principal "$a" "Read only ${RUN_ID}" 100 \
    "$private/read-only-v1.json" '["RAG_READ"]')"
  assert_code "$code" 201 "create read-only principal" || return 1
  local read_only_key read_only_key_id read_only_v2
  read_only_key="$(jq -r '.rawKey' "$private/read-only-v1.json")"
  read_only_key_id="$(jq -r '.keyId' "$private/read-only-v1.json")"
  jq -e '.capabilities == ["RAG_READ"]' \
    "$private/read-only-v1.json" >/dev/null || return 1

  code="$(curl -sS -o "$private/read-only-identity.json" -w '%{http_code}' \
    -H "X-API-Key: ${read_only_key}" "${b}/api/v1/rag/auth/me")"
  assert_code "$code" 200 "read-only identity" || return 1
  jq -e '.capabilities == ["RAG_READ"]' \
    "$private/read-only-identity.json" >/dev/null || return 1

  code="$(curl -sS -o "$private/read-only-get.json" -w '%{http_code}' \
    -H "X-API-Key: ${read_only_key}" \
    "${b}/api/v1/rag/documents?page=0&size=1")"
  assert_code "$code" 200 "read-only data-plane GET" || return 1

  code="$(curl -sS -o "$private/read-only-post.json" -w '%{http_code}' \
    -X POST -H "X-API-Key: ${read_only_key}" \
    -H 'Content-Type: application/json' -d '{}' \
    "${b}/api/v1/rag/documents")"
  assert_code "$code" 403 "read-only data-plane POST" || return 1
  jq -e '.error == "FORBIDDEN" and (.message | contains("RAG_WRITE"))' \
    "$private/read-only-post.json" >/dev/null || return 1

  code="$(root_curl -X POST \
    "${a}/api/v1/rag/api-keys/${read_only_key_id}/rotate" \
    -o "$private/read-only-v2.json" -w '%{http_code}')"
  assert_code "$code" 201 "rotate read-only principal" || return 1
  jq -e '.capabilities == ["RAG_READ"]' \
    "$private/read-only-v2.json" >/dev/null || return 1
  read_only_v2="$(jq -r '.rawKey' "$private/read-only-v2.json")"
  code="$(curl -sS -o "$private/read-only-v2-identity.json" -w '%{http_code}' \
    -H "X-API-Key: ${read_only_v2}" "${b}/api/v1/rag/auth/me")"
  assert_code "$code" 200 "rotated read-only identity" || return 1
  jq -e '.capabilities == ["RAG_READ"]' \
    "$private/read-only-v2-identity.json" >/dev/null || return 1
  echo "operation_capabilities read=200 write=403 rotation=preserved"

  code="$(root_curl -X POST "${a}/api/v1/rag/api-keys" \
    -H 'Content-Type: application/json' \
    -d "{\"name\":\"Invalid capability ${RUN_ID}\",\"expiresAt\":\"2099-12-31T23:59:00\",\"capabilities\":[\"RAG_WRITE\"]}" \
    -o "$private/invalid-capability.json" -w '%{http_code}')"
  assert_code "$code" 400 "invalid capability request" || return 1
  local invalid_capability_count
  invalid_capability_count="$(docker exec "$PG_CONTAINER" psql -U postgres \
    -d managed_api_principal_gate -At -c \
    "SELECT count(*) FROM rag_api_principal WHERE name='Invalid capability ${RUN_ID}'")"
  [[ "$invalid_capability_count" == "0" ]] || {
    echo "Invalid capability principal was persisted" >&2
    return 1
  }

  code="$(create_principal "$a" "Lifecycle ${RUN_ID}" 100 "$private/lifecycle-v1.json")"
  assert_code "$code" 201 "create lifecycle principal" || return 1
  local principal key_id v1 v2 new_key_id
  principal="$(jq -r '.principalId' "$private/lifecycle-v1.json")"
  key_id="$(jq -r '.keyId' "$private/lifecycle-v1.json")"
  v1="$(jq -r '.rawKey' "$private/lifecycle-v1.json")"

  code="$(curl -sS -o "$private/identity-v1.json" -w '%{http_code}' \
    -H "X-API-Key: ${v1}" "${b}/api/v1/rag/auth/me")"
  assert_code "$code" 200 "cross-instance v1 authentication" || return 1
  [[ "$(jq -r '.principalId' "$private/identity-v1.json")" == "$principal" ]] || return 1

  code="$(root_curl -X PUT \
    "${a}/api/v1/rag/api-keys/principals/${principal}/policy" \
    -H 'Content-Type: application/json' \
    -d "{\"expectedPolicyVersion\":1,\"name\":\"Lifecycle updated ${RUN_ID}\",\"expiresAt\":\"2099-12-31T23:59:00\",\"requestsPerMinute\":100}" \
    -o "$private/policy-success.json" -w '%{http_code}')"
  assert_code "$code" 200 "policy CAS success" || return 1
  [[ "$(jq -r '.policyVersion' "$private/policy-success.json")" == "2" ]] || return 1
  code="$(root_curl -X PUT \
    "${b}/api/v1/rag/api-keys/principals/${principal}/policy" \
    -H 'Content-Type: application/json' \
    -d "{\"expectedPolicyVersion\":1,\"name\":\"stale\",\"expiresAt\":\"2099-12-31T23:59:00\",\"requestsPerMinute\":100}" \
    -o "$private/policy-stale.json" -w '%{http_code}')"
  assert_code "$code" 409 "policy CAS stale conflict" || return 1

  code="$(root_curl -X POST "${a}/api/v1/rag/api-keys/${key_id}/rotate" \
    -o "$private/lifecycle-v2.json" -w '%{http_code}')"
  assert_code "$code" 201 "rotate lifecycle principal" || return 1
  v2="$(jq -r '.rawKey' "$private/lifecycle-v2.json")"
  new_key_id="$(jq -r '.keyId' "$private/lifecycle-v2.json")"
  [[ "$(jq -r '.principalId' "$private/lifecycle-v2.json")" == "$principal" ]] || return 1
  [[ "$(jq -r '.credentialVersion' "$private/lifecycle-v2.json")" == "2" ]] || return 1
  code="$(curl -sS -o "$private/old-after-rotate.json" -w '%{http_code}' \
    -H "X-API-Key: ${v1}" "${b}/api/v1/rag/auth/me")"
  assert_code "$code" 401 "old credential after rotation" || return 1
  code="$(curl -sS -o "$private/new-after-rotate.json" -w '%{http_code}' \
    -H "X-API-Key: ${v2}" "${b}/api/v1/rag/auth/me")"
  assert_code "$code" 200 "new credential after rotation" || return 1

  code="$(root_curl -X DELETE "${a}/api/v1/rag/api-keys/${new_key_id}" \
    -o /dev/null -w '%{http_code}')"
  assert_code "$code" 204 "revoke lifecycle principal" || return 1
  code="$(curl -sS -o "$private/new-after-revoke.json" -w '%{http_code}' \
    -H "X-API-Key: ${v2}" "${b}/api/v1/rag/auth/me")"
  assert_code "$code" 401 "cross-instance revoke" || return 1

  code="$(create_principal "$a" "Store failure ${RUN_ID}" 100 "$private/store-failure.json")"
  assert_code "$code" 201 "create store-failure principal" || return 1
  local failure_key
  failure_key="$(jq -r '.rawKey' "$private/store-failure.json")"
  docker exec "$PG_CONTAINER" psql -U postgres -d managed_api_principal_gate \
    -v ON_ERROR_STOP=1 -c 'ALTER TABLE rag_api_rate_limit_bucket RENAME TO rag_api_rate_limit_bucket_unavailable' >/dev/null
  code="$(curl -sS -o "$private/store-unavailable.json" -w '%{http_code}' \
    -H "X-API-Key: ${failure_key}" "${b}/api/v1/rag/auth/me")"
  docker exec "$PG_CONTAINER" psql -U postgres -d managed_api_principal_gate \
    -v ON_ERROR_STOP=1 -c 'ALTER TABLE rag_api_rate_limit_bucket_unavailable RENAME TO rag_api_rate_limit_bucket' >/dev/null
  assert_code "$code" 503 "quota store failure" || return 1

  local db_facts
  db_facts="$(docker exec "$PG_CONTAINER" psql -U postgres \
    -d managed_api_principal_gate -At -F, -c \
    "SELECT (SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1), (SELECT count(*) FROM rag_api_key WHERE api_key IS NOT NULL), (SELECT count(*) FROM (SELECT principal_id FROM rag_api_key WHERE enabled GROUP BY principal_id HAVING count(*) > 1) duplicate_active), (SELECT count(*) FROM rag_api_provisioning_operation)")"
  [[ "$db_facts" == "51,0,0,1" ]] || {
    echo "Unexpected database facts: ${db_facts}" >&2
    return 1
  }
  echo "database_facts migration=51 raw_credentials=0 duplicate_active=0 provisioning_operations=1"
}

start_frontend() {
  local log="$LOG_DIR/real-frontend.log"
  (
    cd spring-ai-rag-webui
    exec env \
      VITE_DEV_PORT="$FRONTEND_PORT" \
      VITE_DEV_PROXY_TARGET="http://127.0.0.1:${BACKEND_A_PORT}" \
      VITE_DEV_ORIGIN="http://127.0.0.1:${FRONTEND_PORT}/webui" \
      npm run dev -- --host 127.0.0.1 --strictPort
  ) > "$log" 2>&1 &
  FRONTEND_PID=$!
  local attempt
  for attempt in $(seq 1 60); do
    curl -fsS "http://127.0.0.1:${FRONTEND_PORT}/webui/unlock" >/dev/null 2>&1 && return 0
    kill -0 "$FRONTEND_PID" >/dev/null 2>&1 || return 1
    sleep 1
  done
  return 1
}

real_webui() {
  start_frontend || return 1
  (
    cd spring-ai-rag-webui
    BASE_URL="http://127.0.0.1:${FRONTEND_PORT}" \
      RAG_ROOT_API_KEY="$ROOT_KEY" \
      npx playwright test e2e/api-key-real.spec.ts --project=chromium
  )
  local rc=$?
  stop_pid "$FRONTEND_PID"
  FRONTEND_PID=""
  return "$rc"
}

real_llm_contract() {
  local a="http://127.0.0.1:${BACKEND_A_PORT}"
  local b="http://127.0.0.1:${BACKEND_B_PORT}"
  local private="$LOG_DIR/private"
  local session="mpr-$(openssl rand -hex 12)"
  local code before after_denial after_first after_replay after_all

  code="$(create_principal "$a" "Real LLM ${RUN_ID}" 100 \
    "$private/real-v1.json" '["RAG_READ"]')"
  assert_code "$code" 201 "create real LLM principal" || return 1
  local principal key_id v1 v2 new_key_id turn_id
  principal="$(jq -r '.principalId' "$private/real-v1.json")"
  key_id="$(jq -r '.keyId' "$private/real-v1.json")"
  v1="$(jq -r '.rawKey' "$private/real-v1.json")"
  jq -e '.capabilities == ["RAG_READ"]' \
    "$private/real-v1.json" >/dev/null || return 1

  code="$(curl -sS -o "$private/real-identity-v1.json" -w '%{http_code}' \
    -H "X-API-Key: ${v1}" "${b}/api/v1/rag/auth/me")"
  assert_code "$code" 200 "real LLM read-only identity" || return 1
  jq -e --arg principal "$principal" \
    '.principalId == $principal and .capabilities == ["RAG_READ"]' \
    "$private/real-identity-v1.json" >/dev/null || return 1

  before="$(provider_counter)" || return 1
  code="$(curl -sS -o "$private/real-write-rejected.json" -w '%{http_code}' \
    -X POST -H "X-API-Key: ${v1}" \
    -H 'Content-Type: application/json' -d '{}' \
    "${a}/api/v1/rag/documents")"
  assert_code "$code" 403 "real LLM read-only write rejection" || return 1
  jq -e '.error == "FORBIDDEN" and (.message | contains("RAG_WRITE"))' \
    "$private/real-write-rejected.json" >/dev/null || return 1
  after_denial="$(provider_counter)" || return 1
  [[ "$after_denial" == "$before" ]] || {
    echo "Rejected write unexpectedly changed the provider counter" >&2
    return 1
  }
  echo "real_read_only_contract=PASS write=403 provider_delta=0"

  code="$(curl -sS --max-time 180 -D "$private/real-first.headers" \
    -o "$private/real-first.json" -w '%{http_code}' \
    -H "X-API-Key: ${v1}" -H 'Content-Type: application/json' \
    -H "Idempotency-Key: managed-real-first-${RUN_ID}" \
    -d "{\"message\":\"Reply briefly that the real provider is reachable.\",\"sessionId\":\"${session}\",\"mode\":\"PLAIN\"}" \
    "${a}/api/v1/rag/chat/ask")"
  assert_code "$code" 200 "real native JSON v1" || return 1
  jq -e '.answer | strings | length > 0' "$private/real-first.json" >/dev/null || return 1
  turn_id="$(jq -r '.turnId' "$private/real-first.json")"
  [[ -n "$turn_id" && "$turn_id" != "null" ]] || return 1
  after_first="$(provider_counter)" || return 1
  awk -v before="$before" -v after="$after_first" 'BEGIN {exit !(after == before + 1)}' || {
    echo "Provider counter did not increase exactly once" >&2
    return 1
  }
  echo "real_native_json_v1=PASS provider_delta=1"

  code="$(curl -sS --max-time 30 -D "$private/real-replay.headers" \
    -o "$private/real-replay.json" -w '%{http_code}' \
    -H "X-API-Key: ${v1}" -H 'Content-Type: application/json' \
    -H "Idempotency-Key: managed-real-first-${RUN_ID}" \
    -d "{\"message\":\"Reply briefly that the real provider is reachable.\",\"sessionId\":\"${session}\",\"mode\":\"PLAIN\"}" \
    "${b}/api/v1/rag/chat/ask")"
  assert_code "$code" 200 "real native JSON replay" || return 1
  grep -qi '^X-RAG-Idempotent-Replay:[[:space:]]*true' "$private/real-replay.headers" || return 1
  [[ "$(jq -r '.turnId' "$private/real-replay.json")" == "$turn_id" ]] || return 1
  after_replay="$(provider_counter)" || return 1
  [[ "$after_replay" == "$after_first" ]] || {
    echo "Replay called the provider" >&2
    return 1
  }
  echo "real_native_json_replay=PASS provider_delta=0"

  code="$(root_curl -X POST "${a}/api/v1/rag/api-keys/${key_id}/rotate" \
    -o "$private/real-v2.json" -w '%{http_code}')"
  assert_code "$code" 201 "rotate real LLM principal" || return 1
  v2="$(jq -r '.rawKey' "$private/real-v2.json")"
  new_key_id="$(jq -r '.keyId' "$private/real-v2.json")"
  jq -e --arg principal "$principal" \
    '.principalId == $principal and .capabilities == ["RAG_READ"]' \
    "$private/real-v2.json" >/dev/null || return 1

  code="$(curl -sS --max-time 15 -o "$private/real-old-rejected.json" -w '%{http_code}' \
    -H "X-API-Key: ${v1}" -H 'Content-Type: application/json' \
    -d '{"message":"This must not reach the provider.","mode":"PLAIN"}' \
    "${b}/api/v1/rag/chat/ask")"
  assert_code "$code" 401 "old real credential rejected" || return 1
  [[ "$(provider_counter)" == "$after_first" ]] || return 1

  code="$(curl -sS -o "$private/real-identity-v2.json" -w '%{http_code}' \
    -H "X-API-Key: ${v2}" "${b}/api/v1/rag/auth/me")"
  assert_code "$code" 200 "rotated real LLM read-only identity" || return 1
  jq -e --arg principal "$principal" \
    '.principalId == $principal
      and .credentialVersion == 2
      and .capabilities == ["RAG_READ"]' \
    "$private/real-identity-v2.json" >/dev/null || return 1

  code="$(curl -sS -o "$private/real-history-after-rotate.json" -w '%{http_code}' \
    -H "X-API-Key: ${v2}" "${b}/api/v1/rag/chat/history/${session}")"
  assert_code "$code" 200 "history continuity after rotation" || return 1
  jq -e --arg session "$session" 'length >= 1 and all(.[]; .sessionId == $session)' \
    "$private/real-history-after-rotate.json" >/dev/null || return 1

  code="$(curl -sS --max-time 180 -o "$private/real-second.json" -w '%{http_code}' \
    -H "X-API-Key: ${v2}" -H 'Content-Type: application/json' \
    -H "Idempotency-Key: managed-real-second-${RUN_ID}" \
    -d "{\"message\":\"Reply briefly that credential rotation preserved this session.\",\"sessionId\":\"${session}\",\"mode\":\"PLAIN\"}" \
    "${b}/api/v1/rag/chat/ask")"
  assert_code "$code" 200 "real native JSON v2" || return 1
  jq -e '.answer | strings | length > 0' "$private/real-second.json" >/dev/null || return 1

  code="$(curl -sS -N --max-time 180 -o "$private/real-native-sse.txt" -w '%{http_code}' \
    -H "X-API-Key: ${v2}" -H 'Content-Type: application/json' \
    -H "Idempotency-Key: managed-real-sse-${RUN_ID}" \
    -d "{\"message\":\"Reply briefly over native SSE.\",\"sessionId\":\"${session}\",\"mode\":\"PLAIN\"}" \
    "${a}/api/v1/rag/chat/stream")"
  assert_code "$code" 200 "real native SSE v2" || return 1
  rg -q '^event:done|^event: done' "$private/real-native-sse.txt" || return 1
  rg -q '^event:content|^event: content' "$private/real-native-sse.txt" || return 1

  code="$(curl -sS --max-time 180 -o "$private/real-openai.json" -w '%{http_code}' \
    -H "X-API-Key: ${v2}" -H 'Content-Type: application/json' \
    -d '{"model":"rag-default","messages":[{"role":"user","content":"Reply briefly through the OpenAI-compatible JSON endpoint."}]}' \
    "${a}/v1/chat/completions")"
  assert_code "$code" 200 "real OpenAI JSON v2" || return 1
  jq -e '.choices[0].message.content | strings | length > 0' "$private/real-openai.json" >/dev/null || return 1

  code="$(curl -sS -N --max-time 180 -o "$private/real-openai-sse.txt" -w '%{http_code}' \
    -H "X-API-Key: ${v2}" -H 'Content-Type: application/json' \
    -d '{"model":"rag-default","stream":true,"messages":[{"role":"user","content":"Reply briefly through the OpenAI-compatible SSE endpoint."}]}' \
    "${b}/v1/chat/completions")"
  assert_code "$code" 200 "real OpenAI SSE v2" || return 1
  rg -q '^data:.*\[DONE\]' "$private/real-openai-sse.txt" || return 1
  rg -q '"delta"' "$private/real-openai-sse.txt" || return 1

  after_all="$(provider_counter)" || return 1
  awk -v before="$before" -v after="$after_all" 'BEGIN {exit !(after == before + 5)}' || {
    echo "Expected five bounded provider calls, got ${before} -> ${after_all}" >&2
    return 1
  }
  code="$(root_curl -X DELETE "${a}/api/v1/rag/api-keys/${new_key_id}" \
    -o /dev/null -w '%{http_code}')"
  assert_code "$code" 204 "revoke real LLM principal" || return 1
  code="$(curl -sS -o /dev/null -w '%{http_code}' \
    -H "X-API-Key: ${v2}" "${b}/api/v1/rag/auth/me")"
  assert_code "$code" 401 "real credential revoked across instances" || return 1
  echo "real_llm_contract=PASS provider_calls=5 principal_continuity=true read_only=true"
  echo "backend_log_tail:"
  rg 'Chat execution|provider|credential|API principal' "$LOG_DIR/backend-a.log" \
    | tail -20 | sed -E 's/rag_sk_[A-Za-z0-9_-]+/***REDACTED***/g' || true
}

fullstack_contract() {
  echo "fullstack_stage=prepare_runtime"
  prepare_runtime || return 1
  echo "fullstack_stage=start_database"
  start_database || return 1
  echo "fullstack_stage=load_provider_environment"
  load_provider_environment || return 1
  echo "fullstack_stage=start_backend_a"
  BACKEND_A_PID="$(start_backend "$BACKEND_A_PORT" "$LOG_DIR/backend-a.log")" || return 1
  wait_backend "$BACKEND_A_PORT" "$BACKEND_A_PID" "$LOG_DIR/backend-a.log" || return 1
  echo "fullstack_stage=start_backend_b"
  BACKEND_B_PID="$(start_backend "$BACKEND_B_PORT" "$LOG_DIR/backend-b.log")" || return 1
  wait_backend "$BACKEND_B_PORT" "$BACKEND_B_PID" "$LOG_DIR/backend-b.log" || return 1
  echo "fullstack_stage=two_instance_contract"
  run_two_instance_contract || return 1
  echo "fullstack_stage=real_webui"
  real_webui || return 1
  if [[ "$RUN_REAL_LLM" == "1" ]]; then
    echo "fullstack_stage=real_llm_contract"
    real_llm_contract || return 1
  fi
}

run_step "Prerequisites" prerequisites
run_step "PostgreSQL integration matrix" postgres_matrix
run_step "Maven clean compile test-compile" maven_compile
run_step "Full Maven test" maven_test
run_step "WebUI Vitest" webui_vitest
run_step "WebUI TypeScript" webui_typecheck
run_step "WebUI production build" webui_build
run_step "WebUI alignment" webui_alignment
run_step "Core Mock Playwright" mock_playwright
run_step "No pessimistic locks" ./scripts/verify-no-pessimistic-locks.sh
run_step "Project documentation" ./scripts/verify-project-docs.sh
run_step "Git whitespace" git diff --check
run_step "Two-instance real full-stack acceptance" fullstack_contract

echo
echo "Managed API principal verification: ${PASS_COUNT} passed, ${FAIL_COUNT} failed"
echo "Summary: ${LOG_DIR}/summary.md"
[[ "$FAIL_COUNT" -eq 0 ]]
