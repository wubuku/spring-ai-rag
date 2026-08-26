#!/usr/bin/env bash
# Pure HTTP contract for a disposable root-mode spring-ai-rag deployment.
set -euo pipefail

cd "$(dirname "$0")/.."

BASE_URL="${BASE_URL:-http://127.0.0.1:18084}"
API="${BASE_URL%/}/api/v1/rag"
ROOT_CREDENTIAL_FILE="${ROOT_CREDENTIAL_FILE:-}"
PRIVATE_ROOT="${BUSINESS_CLIENT_PRIVATE_DIR:-}"
EVIDENCE_DIR="${BUSINESS_CLIENT_EVIDENCE_DIR:-.verification/business-client-contract}"
EMBEDDING_COUNTER_FILE="${BUSINESS_CLIENT_EMBEDDING_COUNTER_FILE:-}"
EMBEDDING_FAIL_MARKER="${BUSINESS_CLIENT_EMBEDDING_FAIL_MARKER:-}"
RUN_ID="${BUSINESS_CLIENT_RUN_ID:-$(date +%Y%m%d-%H%M%S)-$$}"
REQUEST_TIMEOUT_SECONDS="${BUSINESS_CLIENT_TIMEOUT_SECONDS:-30}"

[[ -n "$ROOT_CREDENTIAL_FILE" && -f "$ROOT_CREDENTIAL_FILE" ]] || {
  echo "ROOT_CREDENTIAL_FILE must point to a readable credential file" >&2
  exit 2
}
[[ -n "$PRIVATE_ROOT" && -d "$PRIVATE_ROOT" ]] || {
  echo "BUSINESS_CLIENT_PRIVATE_DIR must point to an existing private directory" >&2
  exit 2
}
[[ -n "$EMBEDDING_FAIL_MARKER" ]] || {
  echo "BUSINESS_CLIENT_EMBEDDING_FAIL_MARKER must be set" >&2
  exit 2
}

umask 077
mkdir -p "$EVIDENCE_DIR"
CONTRACT_PRIVATE="${PRIVATE_ROOT}/contract-${RUN_ID}"
mkdir -p "$CONTRACT_PRIVATE"
chmod 700 "$PRIVATE_ROOT" "$CONTRACT_PRIVATE"

ROOT_CONFIG="${CONTRACT_PRIVATE}/root.curl"
NO_AUTH_CONFIG="${CONTRACT_PRIVATE}/no-auth.curl"
RESTRICTED_KEY_ID=""
RESTRICTED_B_KEY_ID=""
CANARY_KEY_ID=""
UNRESTRICTED_KEY_ID=""
QUERY_KEY_ID=""
RESTRICTED_CURRENT_KEY_ID=""
PASS_COUNT=0

write_auth_config() {
  local output="$1" scheme="$2" secret_file="$3"
  {
    printf 'silent\n'
    printf 'show-error\n'
    printf 'connect-timeout = 5\n'
    printf 'max-time = %s\n' "$REQUEST_TIMEOUT_SECONDS"
    printf 'header = "Accept: application/json"\n'
    printf 'header = "'
    if [[ "$scheme" == "bearer" ]]; then
      printf 'Authorization: Bearer '
    else
      printf 'X-API-Key: '
    fi
    tr -d '\r\n' < "$secret_file"
    printf '"\n'
  } > "$output"
  chmod 600 "$output"
}

write_no_auth_config() {
  {
    printf 'silent\n'
    printf 'show-error\n'
    printf 'connect-timeout = 5\n'
    printf 'max-time = %s\n' "$REQUEST_TIMEOUT_SECONDS"
    printf 'header = "Accept: application/json"\n'
  } > "$1"
  chmod 600 "$1"
}

request() {
  local method="$1" url="$2" auth_config="$3" output="$4" headers="$5"
  local body="${6:-}"
  local args=(
    --config "$auth_config"
    --request "$method"
    --output "$output"
    --dump-header "$headers"
    --write-out '%{http_code}'
  )
  if [[ -n "$body" ]]; then
    args+=(--header 'Content-Type: application/json' --data-binary "@${body}")
  fi
  curl "${args[@]}" "$url"
}

query_request() {
  local method="$1" url="$2" auth_config="$3" output="$4" headers="$5"
  shift 5
  local args=(
    --config "$auth_config"
    --get
    --request "$method"
    --output "$output"
    --dump-header "$headers"
    --write-out '%{http_code}'
  )
  local pair
  for pair in "$@"; do
    args+=(--data-urlencode "$pair")
  done
  curl "${args[@]}" "$url"
}

root_delete_key() {
  local key_id="$1"
  [[ -n "$key_id" && -f "$ROOT_CONFIG" ]] || return 0
  curl --config "$ROOT_CONFIG" --request DELETE --output /dev/null \
    "${API}/api-keys/${key_id}" >/dev/null 2>&1 || true
}

cleanup() {
root_delete_key "$QUERY_KEY_ID"
root_delete_key "$UNRESTRICTED_KEY_ID"
root_delete_key "$CANARY_KEY_ID"
root_delete_key "$RESTRICTED_CURRENT_KEY_ID"
  root_delete_key "$RESTRICTED_B_KEY_ID"
  rm -rf "$CONTRACT_PRIVATE"
}

on_exit() {
  local exit_code=$?
  trap - EXIT
  cleanup
  exit "$exit_code"
}

trap on_exit EXIT
trap 'exit 130' INT TERM

pass() {
  PASS_COUNT=$((PASS_COUNT + 1))
  printf 'PASS: %s\n' "$1"
}

assert_status() {
  local actual="$1" expected="$2" description="$3"
  [[ "$actual" == "$expected" ]] || {
    echo "${description}: expected HTTP ${expected}, got ${actual}" >&2
    return 1
  }
  pass "${description} (HTTP ${actual})"
}

assert_json() {
  local file="$1" expression="$2" description="$3"
  jq -e "$expression" "$file" >/dev/null || {
    echo "${description}: JSON assertion failed" >&2
    return 1
  }
  pass "$description"
}

assert_json_with_args() {
  local file="$1" description="$2"
  shift 2
  jq -e "$@" "$file" >/dev/null || {
    echo "${description}: JSON assertion failed" >&2
    return 1
  }
  pass "$description"
}

assert_no_store() {
  local headers="$1" description="$2"
  tr -d '\r' < "$headers" | rg -qi '^cache-control:.*no-store' || {
    echo "${description}: Cache-Control no-store is missing" >&2
    return 1
  }
  pass "${description} uses no-store"
}

assert_secret_absent() {
  local secret_file="$1" target="$2" description="$3"
  if rg -F -f "$secret_file" "$target" >/dev/null; then
    echo "${description}: response exposed a raw credential" >&2
    return 1
  fi
  pass "$description"
}

assert_anti_enumeration() {
  local response="$1" target_key="$2" description="$3"
  shift 3
  local forbidden
  for forbidden in "$target_key" "$@"; do
    [[ -n "$forbidden" ]] || continue
    if [[ "$forbidden" =~ ^[0-9]+$ ]]; then
      if rg -qi \
          "(document|record)([ _-]?id)?[^0-9]{0,16}${forbidden}([^0-9]|$)" \
          "$response"; then
        echo "${description}: denial leaked an internal record identity" >&2
        return 1
      fi
    elif rg -F "$forbidden" "$response" >/dev/null; then
      echo "${description}: denial leaked target identity" >&2
      return 1
    fi
  done
  if rg -qi \
      '"(collectionId|documentId|principalId)"[[:space:]]*:|not found|does not exist|unknown collection' \
      "$response"; then
    echo "${description}: denial leaked existence or internal identity details" >&2
    return 1
  fi
  pass "${description} is anti-enumeration safe"
}

extract_secret() {
  local response="$1" output="$2"
  jq -er '.rawKey | select(startswith("rag_sk_"))' "$response" > "$output"
  chmod 600 "$output"
}

assert_literal_absent() {
  local target="$1" description="$2"
  shift 2
  local value
  for value in "$@"; do
    [[ -n "$value" ]] || continue
    if rg -F -- "$value" "$target" >/dev/null; then
      echo "${description}: report exposed forbidden value" >&2
      return 1
    fi
  done
  pass "$description"
}

run_binding_preflight() {
  local label="$1" mode="$2" auth_scheme="$3" credential_file="$4"
  local collections_file="$5" expected_exit="$6" marker="${7:-}"
  local evidence="${EVIDENCE_DIR}/preflight-${label}"
  local output="${CONTRACT_PRIVATE}/preflight-${label}.out"
  local error="${CONTRACT_PRIVATE}/preflight-${label}.err"
  local run_id="${RUN_ID}-preflight-${label}" code

  set +e
  RAG_BINDING_BASE_URL="$BASE_URL" \
  RAG_BINDING_CREDENTIAL_FILE="$credential_file" \
  RAG_BINDING_EXPECTED_COLLECTIONS_FILE="$collections_file" \
  RAG_BINDING_TARGET_LABEL="$label" \
  RAG_BINDING_PREFLIGHT_MODE="$mode" \
  RAG_BINDING_AUTH_SCHEME="$auth_scheme" \
  RAG_BINDING_CANARY_CONFIRM="$([[ "$mode" == "CANARY_MUTATION" ]] && printf 'YES' || true)" \
  RAG_BINDING_CANARY_COLLECTION_KEY="$([[ "$mode" == "CANARY_MUTATION" ]] && jq -er '.[0]' "$collections_file" || true)" \
  RAG_BINDING_CANARY_RETRIEVAL_MARKER="$marker" \
  RAG_BINDING_PREFLIGHT_RUN_ID="$run_id" \
  RAG_BINDING_PREFLIGHT_EVIDENCE_DIR="$evidence" \
  RAG_BINDING_REQUEST_TIMEOUT_SECONDS="$REQUEST_TIMEOUT_SECONDS" \
  RAG_BINDING_READY_TIMEOUT_SECONDS=90 \
  RAG_BINDING_ALLOW_HTTP_LOOPBACK=true \
    bash scripts/business-client-binding-preflight.sh > "$output" 2> "$error"
  code=$?
  set -e
  [[ "$code" == "$expected_exit" ]] || {
    echo "${label} binding preflight: expected exit ${expected_exit}, got ${code}" >&2
    sed -n '1,80p' "$error" >&2 || true
    return 1
  }
  pass "${label} binding preflight exits as expected"
}

assert_binding_report() {
  local label="$1" expected_result="$2" expected_category="$3"
  local expected_canary_state="$4" credential_file="$5"
  shift 5
  local report="${EVIDENCE_DIR}/preflight-${label}/preflight-report.json"
  [[ -f "$report" ]] || {
    echo "${label} binding preflight report is missing" >&2
    return 1
  }
  jq -e \
    --arg result "$expected_result" \
    --arg category "$expected_category" \
    --arg canaryState "$expected_canary_state" \
    '
      .schemaVersion == 1
      and .result == $result
      and .verification.requiredOperationCount == 6
      and .failureCategory == (if $result == "PASS" then null else $category end)
      and .verification.canaryFinalState
        == (if $canaryState == "" then null else $canaryState end)
      and (.runId | test("^[A-Za-z0-9._-]+$"))
      and (.targetLabel | test("^[A-Za-z0-9._-]+$"))
    ' "$report" >/dev/null || {
    echo "${label} binding preflight report schema/assertion failed" >&2
    return 1
  }
  assert_secret_absent "$credential_file" "$report" \
    "${label} binding preflight report hides credential"
  assert_literal_absent "$report" \
    "${label} binding preflight report hides binding identities" "$@"
  pass "${label} binding preflight report is safe"
}

create_collection() {
  local key="$1" name="$2" response="$3" headers="$4"
  local request_file="${response}.request"
  jq -n --arg key "$key" --arg name "$name" \
    '{collectionKey:$key,name:$name,description:"Disposable business-client contract",dimensions:1024}' \
    > "$request_file"
  request POST "${API}/collections" "$ROOT_CONFIG" "$response" "$headers" \
    "$request_file"
}

create_principal() {
  local name="$1" collection_key="$2" response="$3" headers="$4"
  local request_file="${response}.request"
  if [[ -n "$collection_key" ]]; then
    jq -n --arg name "$name" --arg key "$collection_key" \
      '{name:$name,expiresAt:"2099-12-31T23:59:00",allowedCollectionKeys:[$key],requestsPerMinute:1000}' \
      > "$request_file"
  else
    jq -n --arg name "$name" \
      '{name:$name,expiresAt:"2099-12-31T23:59:00",requestsPerMinute:1000}' \
      > "$request_file"
  fi
  request POST "${API}/api-keys" "$ROOT_CONFIG" "$response" "$headers" \
    "$request_file"
}

write_search_request() {
  local output="$1" collection_key="$2" payload_status="${3:-}"
  if [[ -n "$payload_status" ]]; then
    jq -n --arg key "$collection_key" --arg status "$payload_status" \
      '{query:"contract searchable record",collectionKeys:[$key],payloadContains:{status:$status},config:{maxResults:10,minScore:0,useRerank:false}}' \
      > "$output"
  else
    jq -n --arg key "$collection_key" \
      '{query:"contract searchable record",collectionKeys:[$key],config:{maxResults:10,minScore:0,useRerank:false}}' \
      > "$output"
  fi
}

write_upsert_request() {
  local output="$1" collection_key="$2" revision="$3" expected="$4"
  local status="$5" retrieval_text="$6"
  local source_namespace="${7:-business-client.contract.v1}"
  local external_id="${8:-record-1}"
  local embedding_policy="${9:-ASYNC}"
  if [[ -n "$expected" ]]; then
    jq -n \
      --arg key "$collection_key" \
      --arg revision "$revision" \
      --arg expected "$expected" \
      --arg status "$status" \
      --arg text "$retrieval_text" \
      --arg namespace "$source_namespace" \
      --arg externalId "$external_id" \
      --arg embeddingPolicy "$embedding_policy" \
      '{
        collectionKey:$key,
        sourceNamespace:$namespace,
        externalId:$externalId,
        sourceRevision:$revision,
        expectedSourceRevision:$expected,
        title:"Contract Record",
        retrievalText:$text,
        jsonbPayload:{schemaVersion:"contract.v1",status:$status,kind:"REFERENCE"},
        embeddingPolicy:$embeddingPolicy
      }' > "$output"
  else
    jq -n \
      --arg key "$collection_key" \
      --arg revision "$revision" \
      --arg status "$status" \
      --arg text "$retrieval_text" \
      --arg namespace "$source_namespace" \
      --arg externalId "$external_id" \
      --arg embeddingPolicy "$embedding_policy" \
      '{
        collectionKey:$key,
        sourceNamespace:$namespace,
        externalId:$externalId,
        sourceRevision:$revision,
        title:"Contract Record",
        retrievalText:$text,
        jsonbPayload:{schemaVersion:"contract.v1",status:$status,kind:"REFERENCE"},
        embeddingPolicy:$embeddingPolicy
      }' > "$output"
  fi
}

wait_for_fresh_embedding() {
  local collection_key="$1" response="$2" headers="$3"
  local attempt code
  for attempt in $(seq 1 90); do
    code="$(query_request GET "${API}/collections/embedding-readiness" \
      "$ROOT_CONFIG" "$response" "$headers" "collectionKey=${collection_key}")"
    if [[ "$code" == "200" ]] \
        && jq -e '.enabledDocuments == 1 and .freshDocuments == 1
          and .queuedDocuments == 0 and .runningDocuments == 0
          and .failedDocuments == 0 and .staleOrMissingDocuments == 0' \
          "$response" >/dev/null; then
      pass "ASYNC embedding converged to fresh"
      return 0
    fi
    sleep 1
  done
  echo "ASYNC embedding did not become fresh" >&2
  return 1
}

wait_for_failed_embedding() {
  local auth_config="$1" collection_key="$2" source_namespace="$3"
  local external_id="$4" document_id="$5" source_revision="$6"
  local response="$7" headers="$8"
  local attempt code
  for attempt in $(seq 1 90); do
    code="$(query_request GET "${API}/json-records/by-external-id" \
      "$auth_config" "$response" "$headers" \
      "collectionKey=${collection_key}" \
      "sourceNamespace=${source_namespace}" \
      "externalId=${external_id}")"
    if [[ "$code" == "200" ]] \
        && jq -e \
          --argjson documentId "$document_id" \
          --arg revision "$source_revision" \
          '.documentId == $documentId
            and .sourceRevision == $revision
            and .documentRevision == 1
            and .enabled == true
            and .jsonbPayload.status == "provider-failure"
            and .lifecycle.documentState == "ACTIVE"
            and .lifecycle.embeddingStatus == "FAILED"' \
          "$response" >/dev/null; then
      pass "failed embedding preserves the persisted JSON Record"
      return 0
    fi
    sleep 1
  done
  echo "ASYNC embedding did not reach FAILED while preserving the record" >&2
  return 1
}

assert_denied_data_plane() {
  local auth_config="$1" target_key="$2" source_namespace="$3"
  local external_id="$4" label="$5"
  shift 5
  local slug request_file response headers code
  slug="$(printf '%s' "$label" | tr '[:upper:] ' '[:lower:]-' | tr -cd 'a-z0-9._-')"

  request_file="${CONTRACT_PRIVATE}/${slug}-search.request.json"
  response="${CONTRACT_PRIVATE}/${slug}-search.json"
  headers="${response}.headers"
  write_search_request "$request_file" "$target_key"
  code="$(request POST "${API}/json-records/search" "$auth_config" \
    "$response" "$headers" "$request_file")"
  assert_status "$code" 403 "${label} search"
  assert_anti_enumeration "$response" "$target_key" "${label} search denial" \
    "$@"

  response="${CONTRACT_PRIVATE}/${slug}-lookup.json"
  headers="${response}.headers"
  code="$(query_request GET "${API}/json-records/by-external-id" \
    "$auth_config" "$response" "$headers" \
    "collectionKey=${target_key}" \
    "sourceNamespace=${source_namespace}" \
    "externalId=${external_id}")"
  assert_status "$code" 403 "${label} lookup"
  assert_anti_enumeration "$response" "$target_key" "${label} lookup denial" \
    "$@"

  request_file="${CONTRACT_PRIVATE}/${slug}-upsert.request.json"
  response="${CONTRACT_PRIVATE}/${slug}-upsert.json"
  headers="${response}.headers"
  write_upsert_request "$request_file" "$target_key" "denied-revision" "" \
    "denied" "denied contract mutation" "$source_namespace" "$external_id" "SKIP"
  code="$(request POST "${API}/json-records/upsert" "$auth_config" \
    "$response" "$headers" "$request_file")"
  assert_status "$code" 403 "${label} upsert"
  assert_anti_enumeration "$response" "$target_key" "${label} upsert denial" \
    "$@"

  response="${CONTRACT_PRIVATE}/${slug}-tombstone.json"
  headers="${response}.headers"
  code="$(query_request DELETE "${API}/json-records/by-external-id" \
    "$auth_config" "$response" "$headers" \
    "collectionKey=${target_key}" \
    "sourceNamespace=${source_namespace}" \
    "externalId=${external_id}" \
    "sourceRevision=denied-tombstone")"
  assert_status "$code" 403 "${label} tombstone"
  assert_anti_enumeration "$response" "$target_key" "${label} tombstone denial" \
    "$@"
}

stub_request_count() {
  [[ -n "$EMBEDDING_COUNTER_FILE" && -f "$EMBEDDING_COUNTER_FILE" ]] || {
    printf '%s\n' "-1"
    return
  }
  jq -r '.requests' "$EMBEDDING_COUNTER_FILE"
}

write_auth_config "$ROOT_CONFIG" x-api-key "$ROOT_CREDENTIAL_FILE"
write_no_auth_config "$NO_AUTH_CONFIG"

RUN_TOKEN="$(printf '%s' "$RUN_ID" | tr -cd 'a-zA-Z0-9' | tail -c 20)"
COLLECTION_A="bc.${RUN_TOKEN}.a"
COLLECTION_B="bc.${RUN_TOKEN}.b"
COLLECTION_CANARY="bc.${RUN_TOKEN}.canary"
UNKNOWN_COLLECTION="bc.${RUN_TOKEN}.missing"
MINIMUM_COLLECTION="z"
BOUNDARY_KEY="$(python3 - "$RUN_TOKEN" <<'PY'
import sys
prefix = "b." + sys.argv[1] + "."
print(prefix + "x" * (128 - len(prefix)))
PY
)"
INVALID_BOUNDARY_KEY="${BOUNDARY_KEY}x"

ROOT_IDENTITY="${CONTRACT_PRIVATE}/root-identity.json"
ROOT_HEADERS="${CONTRACT_PRIVATE}/root-identity.headers"
code="$(request GET "${API}/auth/me" "$ROOT_CONFIG" "$ROOT_IDENTITY" "$ROOT_HEADERS")"
assert_status "$code" 200 "environment root introspection"
assert_no_store "$ROOT_HEADERS" "environment root introspection"
assert_json "$ROOT_IDENTITY" \
  '.principalType == "ENVIRONMENT_ROOT"
    and .principalRole == null
    and .collectionAccessMode == "UNRESTRICTED"
    and .allowedCollectionKeys == null
    and (.capabilities | index("API_KEY_MANAGE") != null)' \
  "environment root contract is explicit"
! rg -qi '"(rawKey|apiKeyHash|credentialHash|secret)"' "$ROOT_IDENTITY"
pass "environment root response contains no secret material"

for collection_spec in \
    "${MINIMUM_COLLECTION}|One character Collection key" \
    "${COLLECTION_A}|Contract Collection A" \
    "${COLLECTION_B}|Contract Collection B" \
    "${COLLECTION_CANARY}|Binding preflight canary Collection" \
    "${BOUNDARY_KEY}|128 character Collection key"; do
  IFS='|' read -r collection_key collection_name <<<"$collection_spec"
  response="${CONTRACT_PRIVATE}/collection-$(printf '%s' "$collection_name" | tr ' ' '-').json"
  headers="${response}.headers"
  code="$(create_collection "$collection_key" "$collection_name" "$response" "$headers")"
  assert_status "$code" 200 "create ${collection_name}"
  assert_json_with_args "$response" \
    "created Collection exposes its stable key" \
    --arg key "$collection_key" '.collectionKey == $key'
done

INVALID_COLLECTION_RESPONSE="${CONTRACT_PRIVATE}/collection-invalid-129.json"
INVALID_COLLECTION_HEADERS="${INVALID_COLLECTION_RESPONSE}.headers"
code="$(create_collection "$INVALID_BOUNDARY_KEY" "Invalid 129 character Collection key" \
  "$INVALID_COLLECTION_RESPONSE" "$INVALID_COLLECTION_HEADERS")"
assert_status "$code" 400 "reject 129 character Collection key"

INVALID_COLLECTION_SPACE="${CONTRACT_PRIVATE}/collection-invalid-space.json"
code="$(create_collection "invalid key" "Invalid Collection key with space" \
  "$INVALID_COLLECTION_SPACE" "${INVALID_COLLECTION_SPACE}.headers")"
assert_status "$code" 400 "reject Collection key containing space"

INVALID_COLLECTION_CONTROL="${CONTRACT_PRIVATE}/collection-invalid-control.json"
code="$(create_collection $'invalid\tkey' "Invalid Collection key with control character" \
  "$INVALID_COLLECTION_CONTROL" "${INVALID_COLLECTION_CONTROL}.headers")"
assert_status "$code" 400 "reject Collection key containing control character"

INVALID_COLLECTION_NON_ASCII="${CONTRACT_PRIVATE}/collection-invalid-non-ascii.json"
code="$(create_collection "invalid-集合" "Invalid non-ASCII Collection key" \
  "$INVALID_COLLECTION_NON_ASCII" "${INVALID_COLLECTION_NON_ASCII}.headers")"
assert_status "$code" 400 "reject non-ASCII Collection key"

RESTRICTED_CREATE="${CONTRACT_PRIVATE}/restricted-create.json"
RESTRICTED_CREATE_HEADERS="${RESTRICTED_CREATE}.headers"
code="$(create_principal "Restricted Contract Principal" "$COLLECTION_A" \
  "$RESTRICTED_CREATE" "$RESTRICTED_CREATE_HEADERS")"
assert_status "$code" 201 "create restricted principal"
assert_no_store "$RESTRICTED_CREATE_HEADERS" "restricted principal creation"
RESTRICTED_KEY_ID="$(jq -er '.keyId' "$RESTRICTED_CREATE")"
RESTRICTED_CURRENT_KEY_ID="$RESTRICTED_KEY_ID"
RESTRICTED_PRINCIPAL_ID="$(jq -er '.principalId' "$RESTRICTED_CREATE")"
RESTRICTED_SECRET_FILE="${CONTRACT_PRIVATE}/restricted.key"
extract_secret "$RESTRICTED_CREATE" "$RESTRICTED_SECRET_FILE"
RESTRICTED_X_CONFIG="${CONTRACT_PRIVATE}/restricted-x.curl"
RESTRICTED_BEARER_CONFIG="${CONTRACT_PRIVATE}/restricted-bearer.curl"
write_auth_config "$RESTRICTED_X_CONFIG" x-api-key "$RESTRICTED_SECRET_FILE"
write_auth_config "$RESTRICTED_BEARER_CONFIG" bearer "$RESTRICTED_SECRET_FILE"

RESTRICTED_B_CREATE="${CONTRACT_PRIVATE}/restricted-b-create.json"
RESTRICTED_B_CREATE_HEADERS="${RESTRICTED_B_CREATE}.headers"
code="$(create_principal "Restricted Contract Principal B" "$COLLECTION_B" \
  "$RESTRICTED_B_CREATE" "$RESTRICTED_B_CREATE_HEADERS")"
assert_status "$code" 201 "create second restricted principal"
assert_no_store "$RESTRICTED_B_CREATE_HEADERS" "second restricted principal creation"
RESTRICTED_B_KEY_ID="$(jq -er '.keyId' "$RESTRICTED_B_CREATE")"
RESTRICTED_B_PRINCIPAL_ID="$(jq -er '.principalId' "$RESTRICTED_B_CREATE")"
RESTRICTED_B_SECRET_FILE="${CONTRACT_PRIVATE}/restricted-b.key"
extract_secret "$RESTRICTED_B_CREATE" "$RESTRICTED_B_SECRET_FILE"
RESTRICTED_B_CONFIG="${CONTRACT_PRIVATE}/restricted-b.curl"
write_auth_config "$RESTRICTED_B_CONFIG" x-api-key "$RESTRICTED_B_SECRET_FILE"

CANARY_CREATE="${CONTRACT_PRIVATE}/canary-create.json"
CANARY_CREATE_HEADERS="${CANARY_CREATE}.headers"
code="$(create_principal "Binding Preflight Canary Principal" "$COLLECTION_CANARY" \
  "$CANARY_CREATE" "$CANARY_CREATE_HEADERS")"
assert_status "$code" 201 "create binding preflight canary principal"
assert_no_store "$CANARY_CREATE_HEADERS" "binding preflight canary principal creation"
CANARY_KEY_ID="$(jq -er '.keyId' "$CANARY_CREATE")"
CANARY_SECRET_FILE="${CONTRACT_PRIVATE}/canary.key"
extract_secret "$CANARY_CREATE" "$CANARY_SECRET_FILE"
CANARY_BEARER_CONFIG="${CONTRACT_PRIVATE}/canary-bearer.curl"
write_auth_config "$CANARY_BEARER_CONFIG" bearer "$CANARY_SECRET_FILE"

UNRESTRICTED_CREATE="${CONTRACT_PRIVATE}/unrestricted-create.json"
UNRESTRICTED_CREATE_HEADERS="${UNRESTRICTED_CREATE}.headers"
code="$(create_principal "Unrestricted Contract Principal" "" \
  "$UNRESTRICTED_CREATE" "$UNRESTRICTED_CREATE_HEADERS")"
assert_status "$code" 201 "create unrestricted principal"
UNRESTRICTED_KEY_ID="$(jq -er '.keyId' "$UNRESTRICTED_CREATE")"
UNRESTRICTED_SECRET_FILE="${CONTRACT_PRIVATE}/unrestricted.key"
extract_secret "$UNRESTRICTED_CREATE" "$UNRESTRICTED_SECRET_FILE"
UNRESTRICTED_CONFIG="${CONTRACT_PRIVATE}/unrestricted.curl"
write_auth_config "$UNRESTRICTED_CONFIG" x-api-key "$UNRESTRICTED_SECRET_FILE"

QUERY_CREATE="${CONTRACT_PRIVATE}/query-create.json"
QUERY_CREATE_HEADERS="${QUERY_CREATE}.headers"
code="$(create_principal "Disposable Query Rejection Principal" "" \
  "$QUERY_CREATE" "$QUERY_CREATE_HEADERS")"
assert_status "$code" 201 "create disposable query-rejection principal"
QUERY_KEY_ID="$(jq -er '.keyId' "$QUERY_CREATE")"
QUERY_SECRET_FILE="${CONTRACT_PRIVATE}/query.key"
extract_secret "$QUERY_CREATE" "$QUERY_SECRET_FILE"
QUERY_RESPONSE="${CONTRACT_PRIVATE}/query-rejected.json"
QUERY_HEADERS="${QUERY_RESPONSE}.headers"
code="$(curl --config "$NO_AUTH_CONFIG" --get --output "$QUERY_RESPONSE" \
  --dump-header "$QUERY_HEADERS" --write-out '%{http_code}' \
  --data-urlencode "apiKey@${QUERY_SECRET_FILE}" "${API}/auth/me")"
assert_status "$code" 401 "reject valid credential in query string"
root_delete_key "$QUERY_KEY_ID"
QUERY_KEY_ID=""

KEY_LIST="${CONTRACT_PRIVATE}/key-list.json"
KEY_LIST_HEADERS="${KEY_LIST}.headers"
code="$(request GET "${API}/api-keys" "$ROOT_CONFIG" "$KEY_LIST" "$KEY_LIST_HEADERS")"
assert_status "$code" 200 "list credential metadata"
PRINCIPAL_LIST="${CONTRACT_PRIVATE}/principal-list.json"
PRINCIPAL_LIST_HEADERS="${PRINCIPAL_LIST}.headers"
code="$(request GET "${API}/api-keys/principals" "$ROOT_CONFIG" \
  "$PRINCIPAL_LIST" "$PRINCIPAL_LIST_HEADERS")"
assert_status "$code" 200 "list principal metadata"
for secret_file in \
    "$RESTRICTED_SECRET_FILE" "$RESTRICTED_B_SECRET_FILE" \
    "$UNRESTRICTED_SECRET_FILE"; do
  assert_secret_absent "$secret_file" "$KEY_LIST" "credential list hides raw secrets"
  assert_secret_absent "$secret_file" "$PRINCIPAL_LIST" "principal list hides raw secrets"
done

RESTRICTED_IDENTITY="${CONTRACT_PRIVATE}/restricted-identity.json"
RESTRICTED_IDENTITY_HEADERS="${RESTRICTED_IDENTITY}.headers"
code="$(request GET "${API}/auth/me" "$RESTRICTED_X_CONFIG" \
  "$RESTRICTED_IDENTITY" "$RESTRICTED_IDENTITY_HEADERS")"
assert_status "$code" 200 "restricted principal X-API-Key authentication"
assert_no_store "$RESTRICTED_IDENTITY_HEADERS" "restricted introspection"
assert_json_with_args "$RESTRICTED_IDENTITY" \
  "restricted introspection returns exact own allow-list" \
  --arg principal "$RESTRICTED_PRINCIPAL_ID" --arg key "$COLLECTION_A" \
  '.principalType == "DATABASE_API_KEY"
    and .principalId == $principal
    and .principalRole == "NORMAL"
    and .collectionAccessMode == "RESTRICTED"
    and .allowedCollectionKeys == [$key]
    and .credentialVersion == 1
    and .policyVersion == 1'
assert_secret_absent "$RESTRICTED_SECRET_FILE" "$RESTRICTED_IDENTITY" \
  "restricted introspection hides raw secret"

BEARER_IDENTITY="${CONTRACT_PRIVATE}/restricted-bearer-identity.json"
BEARER_HEADERS="${BEARER_IDENTITY}.headers"
code="$(request GET "${API}/auth/me" "$RESTRICTED_BEARER_CONFIG" \
  "$BEARER_IDENTITY" "$BEARER_HEADERS")"
assert_status "$code" 200 "restricted principal Bearer authentication"
assert_json_with_args "$BEARER_IDENTITY" \
  "Bearer and X-API-Key resolve the same principal" \
  --arg principal "$RESTRICTED_PRINCIPAL_ID" \
  '.principalId == $principal and .collectionAccessMode == "RESTRICTED"'

UNRESTRICTED_IDENTITY="${CONTRACT_PRIVATE}/unrestricted-identity.json"
UNRESTRICTED_IDENTITY_HEADERS="${UNRESTRICTED_IDENTITY}.headers"
code="$(request GET "${API}/auth/me" "$UNRESTRICTED_CONFIG" \
  "$UNRESTRICTED_IDENTITY" "$UNRESTRICTED_IDENTITY_HEADERS")"
assert_status "$code" 200 "unrestricted database principal introspection"
assert_json "$UNRESTRICTED_IDENTITY" \
  '.principalRole == "NORMAL"
    and .collectionAccessMode == "UNRESTRICTED"
    and .allowedCollectionKeys == null' \
  "unrestricted database principal uses null allow-list"

COLLECTION_PROBE="${CONTRACT_PRIVATE}/collection-probe.json"
COLLECTION_PROBE_HEADERS="${COLLECTION_PROBE}.headers"
code="$(query_request GET "${API}/collections/by-key" "$RESTRICTED_X_CONFIG" \
  "$COLLECTION_PROBE" "$COLLECTION_PROBE_HEADERS" "collectionKey=${COLLECTION_A}")"
assert_status "$code" 200 "restricted binding active Collection probe"

PREFLIGHT_A_COLLECTIONS="${CONTRACT_PRIVATE}/preflight-a-collections.json"
jq -n --arg key "$COLLECTION_A" '[$key]' > "$PREFLIGHT_A_COLLECTIONS"
PREFLIGHT_A_WRONG_COLLECTIONS="${CONTRACT_PRIVATE}/preflight-a-wrong-collections.json"
jq -n --arg keyA "$COLLECTION_A" --arg keyB "$COLLECTION_B" \
  '[$keyA,$keyB]' > "$PREFLIGHT_A_WRONG_COLLECTIONS"
PREFLIGHT_CANARY_COLLECTIONS="${CONTRACT_PRIVATE}/preflight-canary-collections.json"
jq -n --arg key "$COLLECTION_CANARY" '[$key]' > "$PREFLIGHT_CANARY_COLLECTIONS"

run_binding_preflight \
  "readonly-pass" "READ_ONLY" "X_API_KEY" "$RESTRICTED_SECRET_FILE" \
  "$PREFLIGHT_A_COLLECTIONS" 0
assert_binding_report \
  "readonly-pass" "PASS" "" "" "$RESTRICTED_SECRET_FILE" \
  "$COLLECTION_A"

run_binding_preflight \
  "readonly-wrong-allow-list" "READ_ONLY" "X_API_KEY" "$RESTRICTED_SECRET_FILE" \
  "$PREFLIGHT_A_WRONG_COLLECTIONS" 1
assert_binding_report \
  "readonly-wrong-allow-list" "FAIL" "POLICY_MISMATCH" "" \
  "$RESTRICTED_SECRET_FILE" "$COLLECTION_A" "$COLLECTION_B"

run_binding_preflight \
  "canary-success" "CANARY_MUTATION" "BEARER" "$CANARY_SECRET_FILE" \
  "$PREFLIGHT_CANARY_COLLECTIONS" 0
assert_binding_report \
  "canary-success" "PASS" "" "TOMBSTONED" "$CANARY_SECRET_FILE" \
  "$COLLECTION_CANARY"

run_binding_preflight \
  "canary-provider-failure" "CANARY_MUTATION" "BEARER" "$CANARY_SECRET_FILE" \
  "$PREFLIGHT_CANARY_COLLECTIONS" 1 "$EMBEDDING_FAIL_MARKER"
assert_binding_report \
  "canary-provider-failure" "FAIL" "EMBEDDING_FAILED" "TOMBSTONED" \
  "$CANARY_SECRET_FILE" "$COLLECTION_CANARY" "$EMBEDDING_FAIL_MARKER" \
  "preflight-${RUN_ID}-preflight-canary-provider-failure"

RETRIEVAL_TEXT="The contract searchable record is active and ready for retrieval."
UPSERT_V1_REQUEST="${CONTRACT_PRIVATE}/upsert-v1.request.json"
write_upsert_request "$UPSERT_V1_REQUEST" "$COLLECTION_A" "rev-1" "" \
  "active" "$RETRIEVAL_TEXT"
UPSERT_V1_RESPONSE="${CONTRACT_PRIVATE}/upsert-v1.json"
UPSERT_V1_HEADERS="${UPSERT_V1_RESPONSE}.headers"
code="$(request POST "${API}/json-records/upsert" "$RESTRICTED_X_CONFIG" \
  "$UPSERT_V1_RESPONSE" "$UPSERT_V1_HEADERS" "$UPSERT_V1_REQUEST")"
assert_status "$code" 200 "ASYNC JSON Record create"
assert_json "$UPSERT_V1_RESPONSE" \
  '.action == "CREATED"
    and (.embeddingAction == "ASYNC_QUEUED" or .embeddingAction == "ASYNC_COALESCED")
    and .embeddingJobId != null
    and .sourceRevision == "rev-1"' \
  "ASYNC create persists record and durable job"
DOCUMENT_ID="$(jq -er '.documentId' "$UPSERT_V1_RESPONSE")"

READINESS_RESPONSE="${CONTRACT_PRIVATE}/embedding-readiness.json"
READINESS_HEADERS="${READINESS_RESPONSE}.headers"
wait_for_fresh_embedding "$COLLECTION_A" "$READINESS_RESPONSE" "$READINESS_HEADERS"

RESTRICTED_B_IDENTITY="${CONTRACT_PRIVATE}/restricted-b-identity.json"
RESTRICTED_B_IDENTITY_HEADERS="${RESTRICTED_B_IDENTITY}.headers"
code="$(request GET "${API}/auth/me" "$RESTRICTED_B_CONFIG" \
  "$RESTRICTED_B_IDENTITY" "$RESTRICTED_B_IDENTITY_HEADERS")"
assert_status "$code" 200 "second restricted principal authentication"
assert_json_with_args "$RESTRICTED_B_IDENTITY" \
  "second restricted principal returns its own exact allow-list" \
  --arg principal "$RESTRICTED_B_PRINCIPAL_ID" --arg key "$COLLECTION_B" \
  '.principalId == $principal
    and .collectionAccessMode == "RESTRICTED"
    and .allowedCollectionKeys == [$key]'

FAILED_NAMESPACE="business-client.failure.v1"
FAILED_EXTERNAL_ID="provider-failure-record"
FAILED_REVISION="failure-rev-1"
FAILED_UPSERT_REQUEST="${CONTRACT_PRIVATE}/failed-upsert.request.json"
write_upsert_request "$FAILED_UPSERT_REQUEST" "$COLLECTION_B" "$FAILED_REVISION" "" \
  "provider-failure" \
  "Embedding provider failure contract ${EMBEDDING_FAIL_MARKER}" \
  "$FAILED_NAMESPACE" "$FAILED_EXTERNAL_ID" "ASYNC"
FAILED_UPSERT_RESPONSE="${CONTRACT_PRIVATE}/failed-upsert.json"
FAILED_UPSERT_HEADERS="${FAILED_UPSERT_RESPONSE}.headers"
code="$(request POST "${API}/json-records/upsert" "$RESTRICTED_B_CONFIG" \
  "$FAILED_UPSERT_RESPONSE" "$FAILED_UPSERT_HEADERS" "$FAILED_UPSERT_REQUEST")"
assert_status "$code" 200 "persist JSON Record before deterministic embedding failure"
assert_json "$FAILED_UPSERT_RESPONSE" \
  '.action == "CREATED"
    and (.embeddingAction == "ASYNC_QUEUED" or .embeddingAction == "ASYNC_COALESCED")
    and .embeddingJobId != null
    and .documentRevision == 1
    and .sourceRevision == "failure-rev-1"' \
  "provider failure fixture persists one durable business mutation"
FAILED_DOCUMENT_ID="$(jq -er '.documentId' "$FAILED_UPSERT_RESPONSE")"

FAILED_LOOKUP_RESPONSE="${CONTRACT_PRIVATE}/failed-lookup.json"
FAILED_LOOKUP_HEADERS="${FAILED_LOOKUP_RESPONSE}.headers"
wait_for_failed_embedding \
  "$RESTRICTED_B_CONFIG" "$COLLECTION_B" "$FAILED_NAMESPACE" \
  "$FAILED_EXTERNAL_ID" "$FAILED_DOCUMENT_ID" "$FAILED_REVISION" \
  "$FAILED_LOOKUP_RESPONSE" "$FAILED_LOOKUP_HEADERS"
assert_json_with_args "$FAILED_LOOKUP_RESPONSE" \
  "embedding failure keeps identity, revision, payload, and one mutation" \
  --argjson documentId "$FAILED_DOCUMENT_ID" --arg revision "$FAILED_REVISION" \
  '.documentId == $documentId
    and .sourceRevision == $revision
    and .documentRevision == 1
    and .enabled == true
    and .jsonbPayload.status == "provider-failure"
    and .lifecycle.embeddingStatus == "FAILED"'
if [[ -n "$EMBEDDING_COUNTER_FILE" && -f "$EMBEDDING_COUNTER_FILE" ]]; then
  jq -e '.failedRequests >= 1' "$EMBEDDING_COUNTER_FILE" >/dev/null || {
    echo "embedding stub did not record the deterministic failed request" >&2
    exit 1
  }
  pass "embedding stub records failed provider requests"
fi

assert_denied_data_plane \
  "$RESTRICTED_X_CONFIG" "$COLLECTION_B" "$FAILED_NAMESPACE" \
  "$FAILED_EXTERNAL_ID" "principal A denies Collection B" "$FAILED_DOCUMENT_ID"
assert_denied_data_plane \
  "$RESTRICTED_X_CONFIG" "$UNKNOWN_COLLECTION" "business-client.unknown.v1" \
  "unknown-record" "principal A denies unknown Collection"
assert_denied_data_plane \
  "$RESTRICTED_B_CONFIG" "$COLLECTION_A" "business-client.contract.v1" \
  "record-1" "principal B denies Collection A" "$DOCUMENT_ID"

REPLAY_RESPONSE="${CONTRACT_PRIVATE}/replay-v1.json"
REPLAY_HEADERS="${REPLAY_RESPONSE}.headers"
code="$(request POST "${API}/json-records/upsert" "$RESTRICTED_X_CONFIG" \
  "$REPLAY_RESPONSE" "$REPLAY_HEADERS" "$UPSERT_V1_REQUEST")"
assert_status "$code" 200 "exact revision replay"
assert_json_with_args "$REPLAY_RESPONSE" \
  "exact replay preserves document identity" \
  --argjson documentId "$DOCUMENT_ID" \
  '.documentId == $documentId and (.action == "REPLAYED" or .action == "UNCHANGED")'

SAME_REVISION_REQUEST="${CONTRACT_PRIVATE}/same-revision-conflict.request.json"
write_upsert_request "$SAME_REVISION_REQUEST" "$COLLECTION_A" "rev-1" "" \
  "changed-without-revision" "${RETRIEVAL_TEXT} changed"
SAME_REVISION_RESPONSE="${CONTRACT_PRIVATE}/same-revision-conflict.json"
SAME_REVISION_HEADERS="${SAME_REVISION_RESPONSE}.headers"
code="$(request POST "${API}/json-records/upsert" "$RESTRICTED_X_CONFIG" \
  "$SAME_REVISION_RESPONSE" "$SAME_REVISION_HEADERS" "$SAME_REVISION_REQUEST")"
assert_status "$code" 409 "same revision with different content conflicts"

WRONG_CAS_REQUEST="${CONTRACT_PRIVATE}/wrong-cas.request.json"
write_upsert_request "$WRONG_CAS_REQUEST" "$COLLECTION_A" "rev-2" "rev-stale" \
  "active-v2" "$RETRIEVAL_TEXT"
WRONG_CAS_RESPONSE="${CONTRACT_PRIVATE}/wrong-cas.json"
WRONG_CAS_HEADERS="${WRONG_CAS_RESPONSE}.headers"
code="$(request POST "${API}/json-records/upsert" "$RESTRICTED_X_CONFIG" \
  "$WRONG_CAS_RESPONSE" "$WRONG_CAS_HEADERS" "$WRONG_CAS_REQUEST")"
assert_status "$code" 409 "stale expectedSourceRevision conflicts"

BEFORE_PAYLOAD_COUNT="$(stub_request_count)"
UPSERT_V2_REQUEST="${CONTRACT_PRIVATE}/upsert-v2.request.json"
write_upsert_request "$UPSERT_V2_REQUEST" "$COLLECTION_A" "rev-2" "rev-1" \
  "active-v2" "$RETRIEVAL_TEXT"
UPSERT_V2_RESPONSE="${CONTRACT_PRIVATE}/upsert-v2.json"
UPSERT_V2_HEADERS="${UPSERT_V2_RESPONSE}.headers"
code="$(request POST "${API}/json-records/upsert" "$RESTRICTED_X_CONFIG" \
  "$UPSERT_V2_RESPONSE" "$UPSERT_V2_HEADERS" "$UPSERT_V2_REQUEST")"
assert_status "$code" 200 "correct JSON Record CAS update"
assert_json_with_args "$UPSERT_V2_RESPONSE" \
  "payload-only CAS update preserves fresh embedding" \
  --argjson documentId "$DOCUMENT_ID" \
  '.documentId == $documentId
    and .action == "UPDATED"
    and .contentChanged == false
    and .payloadChanged == true
    and .sourceRevision == "rev-2"
    and (.embeddingAction != "ASYNC_QUEUED")
    and (.embeddingAction != "ASYNC_COALESCED")'
AFTER_PAYLOAD_COUNT="$(stub_request_count)"
if [[ "$BEFORE_PAYLOAD_COUNT" != "-1" ]]; then
  [[ "$BEFORE_PAYLOAD_COUNT" == "$AFTER_PAYLOAD_COUNT" ]] || {
    echo "payload-only update unexpectedly called the embedding provider" >&2
    exit 1
  }
  pass "payload-only update does not call embedding provider"
fi

LOOKUP_RESPONSE="${CONTRACT_PRIVATE}/lookup-v2.json"
LOOKUP_HEADERS="${LOOKUP_RESPONSE}.headers"
code="$(query_request GET "${API}/json-records/by-external-id" \
  "$RESTRICTED_X_CONFIG" "$LOOKUP_RESPONSE" "$LOOKUP_HEADERS" \
  "collectionKey=${COLLECTION_A}" \
  "sourceNamespace=business-client.contract.v1" \
  "externalId=record-1")"
assert_status "$code" 200 "lookup JSON Record by external identity"
assert_json_with_args "$LOOKUP_RESPONSE" \
  "lookup returns current payload and revision" \
  --argjson documentId "$DOCUMENT_ID" \
  '.documentId == $documentId and .sourceRevision == "rev-2"
    and .jsonbPayload.status == "active-v2" and .enabled == true'

PAYLOAD_MATCH_REQUEST="${CONTRACT_PRIVATE}/payload-match.request.json"
write_search_request "$PAYLOAD_MATCH_REQUEST" "$COLLECTION_A" "active-v2"
PAYLOAD_MATCH_RESPONSE="${CONTRACT_PRIVATE}/payload-match.json"
PAYLOAD_MATCH_HEADERS="${PAYLOAD_MATCH_RESPONSE}.headers"
code="$(request POST "${API}/json-records/search" "$RESTRICTED_X_CONFIG" \
  "$PAYLOAD_MATCH_RESPONSE" "$PAYLOAD_MATCH_HEADERS" "$PAYLOAD_MATCH_REQUEST")"
assert_status "$code" 200 "payloadContains matching search"
assert_json_with_args "$PAYLOAD_MATCH_RESPONSE" \
  "payloadContains returns matching record" \
  --argjson documentId "$DOCUMENT_ID" \
  '(.results | map(.documentId) | index($documentId)) != null'

PAYLOAD_MISS_REQUEST="${CONTRACT_PRIVATE}/payload-miss.request.json"
write_search_request "$PAYLOAD_MISS_REQUEST" "$COLLECTION_A" "not-present"
PAYLOAD_MISS_RESPONSE="${CONTRACT_PRIVATE}/payload-miss.json"
PAYLOAD_MISS_HEADERS="${PAYLOAD_MISS_RESPONSE}.headers"
code="$(request POST "${API}/json-records/search" "$RESTRICTED_X_CONFIG" \
  "$PAYLOAD_MISS_RESPONSE" "$PAYLOAD_MISS_HEADERS" "$PAYLOAD_MISS_REQUEST")"
assert_status "$code" 200 "payloadContains non-matching search"
assert_json "$PAYLOAD_MISS_RESPONSE" '.results == []' \
  "payloadContains excludes non-matching records"

DELETE_RESPONSE="${CONTRACT_PRIVATE}/delete-v3.json"
DELETE_HEADERS="${DELETE_RESPONSE}.headers"
code="$(query_request DELETE "${API}/json-records/by-external-id" \
  "$RESTRICTED_X_CONFIG" "$DELETE_RESPONSE" "$DELETE_HEADERS" \
  "collectionKey=${COLLECTION_A}" \
  "sourceNamespace=business-client.contract.v1" \
  "externalId=record-1" \
  "sourceRevision=rev-3" \
  "expectedSourceRevision=rev-2")"
assert_status "$code" 200 "tombstone JSON Record"
assert_json_with_args "$DELETE_RESPONSE" \
  "tombstone preserves external identity" \
  --argjson documentId "$DOCUMENT_ID" \
  '.documentId == $documentId and .enabled == false
    and .sourceDeletedAt != null and .sourceRevision == "rev-3"'

DISABLED_LOOKUP="${CONTRACT_PRIVATE}/lookup-disabled.json"
DISABLED_HEADERS="${DISABLED_LOOKUP}.headers"
code="$(query_request GET "${API}/json-records/by-external-id" \
  "$RESTRICTED_X_CONFIG" "$DISABLED_LOOKUP" "$DISABLED_HEADERS" \
  "collectionKey=${COLLECTION_A}" \
  "sourceNamespace=business-client.contract.v1" \
  "externalId=record-1")"
assert_status "$code" 200 "lookup tombstoned JSON Record"
assert_json_with_args "$DISABLED_LOOKUP" \
  "lookup exposes tombstone state" \
  --argjson documentId "$DOCUMENT_ID" \
  '.documentId == $documentId and .enabled == false
    and .sourceRevision == "rev-3"
    and .lifecycle.documentState == "TOMBSTONED"
    and .lifecycle.searchability == "DISABLED"
    and .lifecycle.localIndexStatus == "DISABLED"
    and .lifecycle.embeddingStatus == "DISABLED"'

RESTORE_REQUEST="${CONTRACT_PRIVATE}/restore-v4.request.json"
write_upsert_request "$RESTORE_REQUEST" "$COLLECTION_A" "rev-4" "rev-3" \
  "active-restored" "$RETRIEVAL_TEXT"
RESTORE_RESPONSE="${CONTRACT_PRIVATE}/restore-v4.json"
RESTORE_HEADERS="${RESTORE_RESPONSE}.headers"
code="$(request POST "${API}/json-records/upsert" "$RESTRICTED_X_CONFIG" \
  "$RESTORE_RESPONSE" "$RESTORE_HEADERS" "$RESTORE_REQUEST")"
assert_status "$code" 200 "restore tombstoned JSON Record"
assert_json_with_args "$RESTORE_RESPONSE" \
  "restore reuses the same document identity" \
  --argjson documentId "$DOCUMENT_ID" \
  '.documentId == $documentId and .sourceRevision == "rev-4"
    and .lifecycle.documentState == "ACTIVE"
    and .lifecycle.searchability != "DISABLED"'
wait_for_fresh_embedding "$COLLECTION_A" "$READINESS_RESPONSE" "$READINESS_HEADERS"

BOUNDARY_VALUES_FILE="${CONTRACT_PRIVATE}/boundary-values.txt"
python3 > "$BOUNDARY_VALUES_FILE" <<'PY'
print("n" * 128)
print("e" * 255)
print("r" * 255)
print("n" * 129)
print("e" * 256)
print("r" * 256)
PY
MAX_NAMESPACE="$(sed -n '1p' "$BOUNDARY_VALUES_FILE")"
MAX_EXTERNAL_ID="$(sed -n '2p' "$BOUNDARY_VALUES_FILE")"
MAX_REVISION="$(sed -n '3p' "$BOUNDARY_VALUES_FILE")"
OVERLONG_NAMESPACE="$(sed -n '4p' "$BOUNDARY_VALUES_FILE")"
OVERLONG_EXTERNAL_ID="$(sed -n '5p' "$BOUNDARY_VALUES_FILE")"
OVERLONG_REVISION="$(sed -n '6p' "$BOUNDARY_VALUES_FILE")"

BOUNDARY_REQUEST="${CONTRACT_PRIVATE}/boundary-max.request.json"
write_upsert_request "$BOUNDARY_REQUEST" "$COLLECTION_A" "$MAX_REVISION" "" \
  "boundary-max" "Boundary contract record" \
  "$MAX_NAMESPACE" "$MAX_EXTERNAL_ID" "SKIP"
BOUNDARY_RESPONSE="${CONTRACT_PRIVATE}/boundary-max.json"
BOUNDARY_HEADERS="${BOUNDARY_RESPONSE}.headers"
code="$(request POST "${API}/json-records/upsert" "$RESTRICTED_X_CONFIG" \
  "$BOUNDARY_RESPONSE" "$BOUNDARY_HEADERS" "$BOUNDARY_REQUEST")"
assert_status "$code" 200 "accept maximum namespace, externalId, and sourceRevision"
assert_json_with_args "$BOUNDARY_RESPONSE" \
  "maximum identity and revision persist without embedding" \
  --arg revision "$MAX_REVISION" \
  '.action == "CREATED"
    and .sourceRevision == $revision
    and .embeddingAction == "SKIPPED"'
BOUNDARY_DOCUMENT_ID="$(jq -er '.documentId' "$BOUNDARY_RESPONSE")"

BOUNDARY_LOOKUP="${CONTRACT_PRIVATE}/boundary-max-lookup.json"
BOUNDARY_LOOKUP_HEADERS="${BOUNDARY_LOOKUP}.headers"
code="$(query_request GET "${API}/json-records/by-external-id" \
  "$RESTRICTED_X_CONFIG" "$BOUNDARY_LOOKUP" "$BOUNDARY_LOOKUP_HEADERS" \
  "collectionKey=${COLLECTION_A}" \
  "sourceNamespace=${MAX_NAMESPACE}" \
  "externalId=${MAX_EXTERNAL_ID}")"
assert_status "$code" 200 "lookup maximum external identity"
assert_json_with_args "$BOUNDARY_LOOKUP" \
  "maximum external identity resolves the same record" \
  --argjson documentId "$BOUNDARY_DOCUMENT_ID" \
  --arg namespace "$MAX_NAMESPACE" \
  --arg externalId "$MAX_EXTERNAL_ID" \
  --arg revision "$MAX_REVISION" \
  '.documentId == $documentId
    and .sourceNamespace == $namespace
    and .externalId == $externalId
    and .sourceRevision == $revision'

OVERLONG_NAMESPACE_REQUEST="${CONTRACT_PRIVATE}/boundary-namespace-129.request.json"
write_upsert_request "$OVERLONG_NAMESPACE_REQUEST" "$COLLECTION_A" "boundary-rev" "" \
  "invalid" "Boundary rejection" \
  "$OVERLONG_NAMESPACE" "boundary-namespace-129" "SKIP"
code="$(request POST "${API}/json-records/upsert" "$RESTRICTED_X_CONFIG" \
  "${CONTRACT_PRIVATE}/boundary-namespace-129.json" \
  "${CONTRACT_PRIVATE}/boundary-namespace-129.headers" \
  "$OVERLONG_NAMESPACE_REQUEST")"
assert_status "$code" 400 "reject 129 character sourceNamespace"

CONTROL_NAMESPACE_REQUEST="${CONTRACT_PRIVATE}/boundary-namespace-control.request.json"
write_upsert_request "$CONTROL_NAMESPACE_REQUEST" "$COLLECTION_A" "boundary-rev" "" \
  "invalid" "Boundary rejection" \
  $'invalid\tnamespace' "boundary-namespace-control" "SKIP"
code="$(request POST "${API}/json-records/upsert" "$RESTRICTED_X_CONFIG" \
  "${CONTRACT_PRIVATE}/boundary-namespace-control.json" \
  "${CONTRACT_PRIVATE}/boundary-namespace-control.headers" \
  "$CONTROL_NAMESPACE_REQUEST")"
assert_status "$code" 400 "reject sourceNamespace containing control character"

OVERLONG_EXTERNAL_REQUEST="${CONTRACT_PRIVATE}/boundary-external-256.request.json"
write_upsert_request "$OVERLONG_EXTERNAL_REQUEST" "$COLLECTION_A" "boundary-rev" "" \
  "invalid" "Boundary rejection" \
  "business-client.boundary.v1" "$OVERLONG_EXTERNAL_ID" "SKIP"
code="$(request POST "${API}/json-records/upsert" "$RESTRICTED_X_CONFIG" \
  "${CONTRACT_PRIVATE}/boundary-external-256.json" \
  "${CONTRACT_PRIVATE}/boundary-external-256.headers" \
  "$OVERLONG_EXTERNAL_REQUEST")"
assert_status "$code" 400 "reject 256 character externalId"

OVERLONG_REVISION_REQUEST="${CONTRACT_PRIVATE}/boundary-revision-256.request.json"
write_upsert_request "$OVERLONG_REVISION_REQUEST" "$COLLECTION_A" \
  "$OVERLONG_REVISION" "" "invalid" "Boundary rejection" \
  "business-client.boundary.v1" "boundary-revision-256" "SKIP"
code="$(request POST "${API}/json-records/upsert" "$RESTRICTED_X_CONFIG" \
  "${CONTRACT_PRIVATE}/boundary-revision-256.json" \
  "${CONTRACT_PRIVATE}/boundary-revision-256.headers" \
  "$OVERLONG_REVISION_REQUEST")"
assert_status "$code" 400 "reject 256 character sourceRevision"

code="$(query_request DELETE "${API}/json-records/by-external-id" \
  "$RESTRICTED_X_CONFIG" \
  "${CONTRACT_PRIVATE}/boundary-expected-revision-256.json" \
  "${CONTRACT_PRIVATE}/boundary-expected-revision-256.headers" \
  "collectionKey=${COLLECTION_A}" \
  "sourceNamespace=${MAX_NAMESPACE}" \
  "externalId=${MAX_EXTERNAL_ID}" \
  "sourceRevision=boundary-delete" \
  "expectedSourceRevision=${OVERLONG_REVISION}")"
assert_status "$code" 400 "reject 256 character expectedSourceRevision"

ROTATE_RESPONSE="${CONTRACT_PRIVATE}/restricted-rotate.json"
ROTATE_HEADERS="${ROTATE_RESPONSE}.headers"
code="$(request POST "${API}/api-keys/${RESTRICTED_KEY_ID}/rotate" \
  "$ROOT_CONFIG" "$ROTATE_RESPONSE" "$ROTATE_HEADERS")"
assert_status "$code" 201 "rotate restricted credential"
assert_no_store "$ROTATE_HEADERS" "credential rotation"
ROTATED_KEY_ID="$(jq -er '.keyId' "$ROTATE_RESPONSE")"
ROTATED_SECRET_FILE="${CONTRACT_PRIVATE}/restricted-rotated.key"
extract_secret "$ROTATE_RESPONSE" "$ROTATED_SECRET_FILE"
ROTATED_CONFIG="${CONTRACT_PRIVATE}/restricted-rotated.curl"
write_auth_config "$ROTATED_CONFIG" x-api-key "$ROTATED_SECRET_FILE"
RESTRICTED_CURRENT_KEY_ID="$ROTATED_KEY_ID"

OLD_AFTER_ROTATE="${CONTRACT_PRIVATE}/old-after-rotate.json"
OLD_AFTER_ROTATE_HEADERS="${OLD_AFTER_ROTATE}.headers"
code="$(request GET "${API}/auth/me" "$RESTRICTED_X_CONFIG" \
  "$OLD_AFTER_ROTATE" "$OLD_AFTER_ROTATE_HEADERS")"
assert_status "$code" 401 "old credential after rotation"

NEW_AFTER_ROTATE="${CONTRACT_PRIVATE}/new-after-rotate.json"
NEW_AFTER_ROTATE_HEADERS="${NEW_AFTER_ROTATE}.headers"
code="$(request GET "${API}/auth/me" "$ROTATED_CONFIG" \
  "$NEW_AFTER_ROTATE" "$NEW_AFTER_ROTATE_HEADERS")"
assert_status "$code" 200 "new credential after rotation"
assert_json_with_args "$NEW_AFTER_ROTATE" \
  "rotation preserves principal and policy" \
  --arg principal "$RESTRICTED_PRINCIPAL_ID" --arg key "$COLLECTION_A" \
  '.principalId == $principal
    and .credentialVersion == 2
    and .policyVersion == 1
    and .collectionAccessMode == "RESTRICTED"
    and .allowedCollectionKeys == [$key]'

root_delete_key "$ROTATED_KEY_ID"
RESTRICTED_CURRENT_KEY_ID=""
REVOKED_RESPONSE="${CONTRACT_PRIVATE}/revoked.json"
REVOKED_HEADERS="${REVOKED_RESPONSE}.headers"
code="$(request GET "${API}/auth/me" "$ROTATED_CONFIG" \
  "$REVOKED_RESPONSE" "$REVOKED_HEADERS")"
assert_status "$code" 401 "revoked credential authentication"

root_delete_key "$UNRESTRICTED_KEY_ID"
UNRESTRICTED_KEY_ID=""

{
  printf 'run_id=%s\n' "$RUN_ID"
  printf 'result=PASS\n'
  printf 'checks=%s\n' "$PASS_COUNT"
  printf 'collection_key_boundary=1_and_128_pass_invalid_and_129_reject\n'
  printf 'identity_boundary=namespace_128_external_255_revision_255\n'
  printf 'principal_contract=root_two_restricted_unrestricted\n'
  printf 'acl_contract=bidirectional_full_data_plane_anti_enumeration\n'
  printf 'json_record_contract=replay_cas_payload_filter_tombstone_restore_async_failure_preserves_record\n'
  printf 'credential_contract=headers_query_reject_rotation_revocation\n'
} > "${EVIDENCE_DIR}/summary.txt"

printf '\nBusiness client HTTP contract passed: %s checks\n' "$PASS_COUNT"
printf 'Summary: %s/summary.txt\n' "$EVIDENCE_DIR"
