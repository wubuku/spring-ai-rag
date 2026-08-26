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
CLIENT_ENVELOPE_DIR="${BUSINESS_CLIENT_CLIENT_ENVELOPE_DIR:-}"
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
SHARED_KEY_ID=""
CANARY_KEY_ID=""
UNRESTRICTED_KEY_ID=""
QUERY_KEY_ID=""
THROTTLED_KEY_ID=""
RESTRICTED_CURRENT_KEY_ID=""
QUERY_CURRENT_KEY_ID=""
CLIENT_ENVELOPE_SOURCE=""
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
  root_delete_key "$QUERY_CURRENT_KEY_ID"
  root_delete_key "$UNRESTRICTED_KEY_ID"
  root_delete_key "$THROTTLED_KEY_ID"
  root_delete_key "$CANARY_KEY_ID"
  root_delete_key "$SHARED_KEY_ID"
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

assert_projection_is_sanitized() {
  local target="$1" description="$2"
  python3 - "$target" <<'PY'
import json
from pathlib import Path
import sys

forbidden_keys = {
    "privateattachment",
    "url",
    "publicurl",
    "signedurl",
    "downloadurl",
    "objectkey",
    "storageobjectid",
    "providerid",
    "apikey",
    "token",
    "secret",
    "clientkey",
    "eventid",
    "recordkey",
    "recordincarnation",
    "changeid",
    "sourcefingerprint",
    "documentfingerprint",
    "dedupkey",
    "previousrecordkey",
    "deleteidentity",
}

def walk(value):
    if isinstance(value, dict):
        for key, child in value.items():
            normalized = "".join(character for character in key.lower()
                                 if character.isalnum())
            if normalized in forbidden_keys:
                raise SystemExit(f"forbidden projection field: {key}")
            walk(child)
    elif isinstance(value, list):
        for child in value:
            walk(child)
    elif isinstance(value, str):
        lowered = value.lower()
        if lowered.startswith(("http://", "https://")):
            raise SystemExit("projection contains a URL")

walk(json.loads(Path(sys.argv[1]).read_text(encoding="utf-8")))
PY
  pass "$description"
}

assert_client_envelope_has_private_transport() {
  local target="$1" description="$2"
  jq -e '
    .protocolVersion == "generic-client-record-mutation-v1"
    and .operation == "UPSERT"
    and (.privateAttachment | type) == "object"
    and (.privateAttachment | length) > 0
  ' "$target" >/dev/null || {
    echo "${description}: client envelope lacks private transport material" >&2
    return 1
  }
  pass "$description"
}

run_binding_preflight() {
  local label="$1" mode="$2" auth_scheme="$3" credential_file="$4"
  local collections_file="$5" capability_profile="$6" expected_exit="$7"
  local marker="${8:-}"
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
  RAG_BINDING_EXPECTED_CAPABILITY_PROFILE="$capability_profile" \
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
  local expected_profile="$6" verified_profile="$7"
  shift 7
  local report="${EVIDENCE_DIR}/preflight-${label}/preflight-report.json"
  [[ -f "$report" ]] || {
    echo "${label} binding preflight report is missing" >&2
    return 1
  }
  jq -e \
    --arg result "$expected_result" \
    --arg category "$expected_category" \
    --arg canaryState "$expected_canary_state" \
    --arg expectedProfile "$expected_profile" \
    --arg verifiedProfile "$verified_profile" \
    '
      .schemaVersion == 1
      and .result == $result
      and .expectedCapabilityProfile == $expectedProfile
      and .principal.capabilityProfile
        == (if $verifiedProfile == "" then null else $verifiedProfile end)
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
  local name="$1" collection_key="$2" capability_profile="$3"
  local response="$4" headers="$5"
  local requests_per_minute="${6:-1000}"
  local request_file="${response}.request"
  local capabilities
  case "$capability_profile" in
    READ_ONLY) capabilities='["RAG_READ"]' ;;
    READ_WRITE) capabilities='["RAG_READ","RAG_WRITE"]' ;;
    *)
      echo "Unsupported capability profile: ${capability_profile}" >&2
      return 2
      ;;
  esac
  if [[ -n "$collection_key" ]]; then
    jq -n --arg name "$name" --arg key "$collection_key" \
      --argjson capabilities "$capabilities" \
      --argjson requestsPerMinute "$requests_per_minute" \
      '{name:$name,expiresAt:"2099-12-31T23:59:00",allowedCollectionKeys:[$key],requestsPerMinute:$requestsPerMinute,capabilities:$capabilities}' \
      > "$request_file"
  else
    jq -n --arg name "$name" --argjson capabilities "$capabilities" \
      --argjson requestsPerMinute "$requests_per_minute" \
      '{name:$name,expiresAt:"2099-12-31T23:59:00",requestsPerMinute:$requestsPerMinute,capabilities:$capabilities}' \
      > "$request_file"
  fi
  request POST "${API}/api-keys" "$ROOT_CONFIG" "$response" "$headers" \
    "$request_file"
}

create_principal_for_collections() {
  local name="$1" capability_profile="$2" response="$3" headers="$4"
  shift 4
  local request_file="${response}.request"
  local capabilities
  case "$capability_profile" in
    READ_ONLY) capabilities='["RAG_READ"]' ;;
    READ_WRITE) capabilities='["RAG_READ","RAG_WRITE"]' ;;
    *)
      echo "Unsupported capability profile: ${capability_profile}" >&2
      return 2
      ;;
  esac
  jq -n --arg name "$name" --argjson capabilities "$capabilities" \
    --args \
    '{
      name:$name,
      expiresAt:"2099-12-31T23:59:00",
      allowedCollectionKeys:$ARGS.positional,
      requestsPerMinute:1000,
      capabilities:$capabilities
    }' "$@" > "$request_file"
  request POST "${API}/api-keys" "$ROOT_CONFIG" "$response" "$headers" \
    "$request_file"
}

assert_capability_profile() {
  local response="$1" capability_profile="$2" description="$3"
  local expected
  case "$capability_profile" in
    READ_ONLY) expected='["RAG_READ"]' ;;
    READ_WRITE) expected='["RAG_READ","RAG_WRITE"]' ;;
    *) return 2 ;;
  esac
  assert_json_with_args "$response" "$description" \
    --argjson expected "$expected" '.capabilities == $expected'
}

assert_write_capability_denied() {
  local response="$1" description="$2"
  assert_json "$response" \
    '.error == "FORBIDDEN" and (.message | contains("RAG_WRITE"))' \
    "$description"
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

prepare_client_envelopes() {
  local fixture_dir
  if [[ -n "$CLIENT_ENVELOPE_DIR" ]]; then
    fixture_dir="$CLIENT_ENVELOPE_DIR"
    CLIENT_ENVELOPE_SOURCE="external"
  else
    fixture_dir="${CONTRACT_PRIVATE}/client-envelopes"
    mkdir -p "$fixture_dir"
    CLIENT_ENVELOPE_SOURCE="generated"
    python3 - "$fixture_dir" <<'PY'
import hashlib
import json
from pathlib import Path
import sys

target = Path(sys.argv[1])

def fingerprint(value):
    encoded = json.dumps(
        value, ensure_ascii=True, separators=(",", ":"), sort_keys=True
    ).encode("utf-8")
    return "sha256:" + hashlib.sha256(encoded).hexdigest()

def write(name, *, scope, scope_key, record_type, record_id, revision,
          operation, document=None, private_attachment=None,
          incarnation="inc-1"):
    identity = "\n".join((
        "generic-client-fixture",
        scope,
        scope_key,
        record_type,
        record_id,
        "<NONE>",
        "<NONE>",
    ))
    payload = {
        "protocolVersion": "generic-client-record-mutation-v1",
        "eventId": "evt_" + hashlib.sha256(
            f"{identity}:{revision}:{operation}".encode("utf-8")
        ).hexdigest()[:40],
        "operation": operation,
        "clientKey": "generic-client-fixture",
        "recordScope": scope,
        "scopeKey": scope_key,
        "recordType": record_type,
        "recordId": record_id,
        "variantKey": "<NONE>",
        "bindingKey": "<NONE>",
        "recordKey": "rec_" + hashlib.sha256(
            f"{identity}:{incarnation}".encode("utf-8")
        ).hexdigest()[:40],
        "recordIncarnation": incarnation,
        "changeId": "chg_" + hashlib.sha256(
            f"{identity}:{revision}".encode("utf-8")
        ).hexdigest()[:40],
        "sourceRevision": revision,
        "sourceFingerprint": fingerprint({
            "identity": identity,
            "revision": revision,
            "operation": operation,
        }),
        "indexGeneration": "keyword-metadata-v1",
        "occurredAt": f"2026-08-26T00:00:0{revision}Z",
        "documentSchemaVersion": 1,
        "documentFingerprint": fingerprint(document or {}),
    }
    if operation == "UPSERT":
        payload["searchDocument"] = document or {}
        payload["privateAttachment"] = private_attachment or {}
    else:
        payload.update({
            "previousRecordKey": payload["recordKey"],
            "previousChangeId": "previous-change",
            "previousSourceFingerprint": "sha256:" + "1" * 64,
            "previousDocumentFingerprint": "sha256:" + "2" * 64,
            "deleteIdentity": "del_" + hashlib.sha256(
                f"{identity}:{revision}".encode("utf-8")
            ).hexdigest()[:40],
        })
    payload["dedupKey"] = "GCR1:" + hashlib.sha256(
        json.dumps(
            payload, ensure_ascii=True, separators=(",", ":"), sort_keys=True
        ).encode("utf-8")
    ).hexdigest()
    (target / name).write_text(
        json.dumps(payload, ensure_ascii=True, indent=2) + "\n",
        encoding="utf-8",
)

private_document_v1 = {
    "name": "Tenant warranty policy",
    "description": "Private warranty and replacement terms for one tenant.",
    "keywords": ["warranty", "replacement", "support"],
    "structured": {
        "category": "SUPPORT_POLICY",
        "locale": "en-US",
    },
}
private_document_v2 = {
    **private_document_v1,
    "description": "Updated private warranty and replacement terms.",
}
private_attachment = {
    "storageObjectId": "private-object-1001",
    "downloadUrl": "https://private.example.test/tenant/policy.pdf",
}
write(
    "private-lifecycle-v1.json",
    scope="TENANT_PRIVATE",
    scope_key="tenant-42",
    record_type="KNOWLEDGE_ARTICLE",
    record_id="support-policy-1001",
    revision=1,
    operation="UPSERT",
    document=private_document_v1,
    private_attachment=private_attachment,
)
write(
    "private-lifecycle-v2.json",
    scope="TENANT_PRIVATE",
    scope_key="tenant-42",
    record_type="KNOWLEDGE_ARTICLE",
    record_id="support-policy-1001",
    revision=2,
    operation="UPSERT",
    document=private_document_v2,
    private_attachment=private_attachment,
)
write(
    "private-lifecycle-v3.json",
    scope="TENANT_PRIVATE",
    scope_key="tenant-42",
    record_type="KNOWLEDGE_ARTICLE",
    record_id="support-policy-1001",
    revision=3,
    operation="DELETE",
)
write(
    "private-lifecycle-v4.json",
    scope="TENANT_PRIVATE",
    scope_key="tenant-42",
    record_type="KNOWLEDGE_ARTICLE",
    record_id="support-policy-1001",
    revision=4,
    operation="UPSERT",
    document=private_document_v2,
    private_attachment=private_attachment,
    incarnation="inc-2",
)
write(
    "private-lifecycle-v5.json",
    scope="TENANT_PRIVATE",
    scope_key="tenant-42",
    record_type="KNOWLEDGE_ARTICLE",
    record_id="support-policy-1001",
    revision=5,
    operation="DELETE",
    incarnation="inc-2",
)

shared_document = {
    "name": "Shared support glossary",
    "description": "Common terminology available to all authorized tenants.",
}
shared_attachment = {
    "signedUrl": "https://private.example.test/shared/glossary.json",
    "objectKey": "private/shared/glossary.json",
}
write(
    "shared-lifecycle-v1.json",
    scope="SHARED_CATALOG",
    scope_key="common-reference",
    record_type="GLOSSARY_ENTRY",
    record_id="glossary-entry-9001",
    revision=1,
    operation="UPSERT",
    document=shared_document,
    private_attachment=shared_attachment,
    incarnation="glossary-entry-9001",
)
write(
    "shared-lifecycle-v2.json",
    scope="SHARED_CATALOG",
    scope_key="common-reference",
    record_type="GLOSSARY_ENTRY",
    record_id="glossary-entry-9001",
    revision=2,
    operation="DELETE",
    incarnation="glossary-entry-9001",
)
PY
  fi

  PRIVATE_ENVELOPE_V1="${fixture_dir}/private-lifecycle-v1.json"
  PRIVATE_ENVELOPE_V2="${fixture_dir}/private-lifecycle-v2.json"
  PRIVATE_ENVELOPE_V3="${fixture_dir}/private-lifecycle-v3.json"
  PRIVATE_ENVELOPE_V4="${fixture_dir}/private-lifecycle-v4.json"
  PRIVATE_ENVELOPE_V5="${fixture_dir}/private-lifecycle-v5.json"
  SHARED_ENVELOPE_V1="${fixture_dir}/shared-lifecycle-v1.json"
  SHARED_ENVELOPE_V2="${fixture_dir}/shared-lifecycle-v2.json"

  local fixture
  for fixture in \
      "$PRIVATE_ENVELOPE_V1" "$PRIVATE_ENVELOPE_V2" \
      "$PRIVATE_ENVELOPE_V3" "$PRIVATE_ENVELOPE_V4" \
      "$PRIVATE_ENVELOPE_V5" "$SHARED_ENVELOPE_V1" \
      "$SHARED_ENVELOPE_V2"; do
    [[ -f "$fixture" ]] || {
      echo "Business-client envelope fixture is missing: $(basename "$fixture")" >&2
      return 1
    }
    jq -e . "$fixture" >/dev/null || {
      echo "Business-client envelope fixture is invalid JSON: $(basename "$fixture")" >&2
      return 1
    }
  done
  pass "client-owned mutation envelope fixture set is complete"
}

compile_client_envelope() {
  local envelope="$1" collection_key="$2" expected_scope="$3"
  local expected_operation="$4" output="$5" expected_revision="${6:-}"
  python3 - \
    "$envelope" "$collection_key" "$expected_scope" "$expected_operation" \
    "$output" "$expected_revision" <<'PY'
import hashlib
import json
from pathlib import Path
import sys

source_path = Path(sys.argv[1])
collection_key = sys.argv[2]
expected_scope = sys.argv[3]
expected_operation = sys.argv[4]
output_path = Path(sys.argv[5])
expected_revision = sys.argv[6]
envelope = json.loads(source_path.read_text(encoding="utf-8"))

def require_text(field):
    value = envelope.get(field)
    if not isinstance(value, str) or not value.strip():
        raise SystemExit(f"client envelope requires non-blank {field}")
    return value.strip()

if envelope.get("protocolVersion") != "generic-client-record-mutation-v1":
    raise SystemExit("unsupported client envelope protocolVersion")
if envelope.get("operation") != expected_operation:
    raise SystemExit("unexpected client envelope operation")
if envelope.get("recordScope") != expected_scope:
    raise SystemExit("unexpected client envelope recordScope")

client_key = require_text("clientKey")
scope = require_text("recordScope")
scope_key = require_text("scopeKey")
record_type = require_text("recordType")
record_id = require_text("recordId")
variant = require_text("variantKey")
binding = require_text("bindingKey")
if record_id.lower().startswith(("http://", "https://")):
    raise SystemExit("recordId must be a stable locator, not a URL")

revision_value = envelope.get("sourceRevision")
if isinstance(revision_value, bool) or revision_value is None:
    raise SystemExit("client envelope requires sourceRevision")
revision = str(revision_value).strip()
if not revision or len(revision) > 255:
    raise SystemExit("compiled sourceRevision exceeds the RAG contract")

identity = "\n".join((
    client_key,
    scope,
    scope_key,
    record_type,
    record_id,
    variant,
    binding,
))
external_id = "client_" + hashlib.sha256(identity.encode("utf-8")).hexdigest()
if len(external_id) > 80:
    raise SystemExit("compiled externalId exceeds the client contract")

result = {
    "collectionKey": collection_key,
    "sourceNamespace": "generic-client.record-mutation.v1",
    "externalId": external_id,
    "sourceRevision": revision,
}
if expected_revision:
    result["expectedSourceRevision"] = expected_revision

if expected_operation == "UPSERT":
    document = envelope.get("searchDocument")
    private_attachment = envelope.get("privateAttachment")
    if not isinstance(document, dict) or not document:
        raise SystemExit("UPSERT envelope requires a non-empty searchDocument")
    if not isinstance(private_attachment, dict) or not private_attachment:
        raise SystemExit("UPSERT envelope requires private transport material")

    text_values = []

    def add_text(value):
        if value is None or isinstance(value, bool):
            return
        if isinstance(value, (int, float)):
            text_values.append(str(value))
        elif isinstance(value, str):
            normalized = " ".join(value.split())
            if normalized and not normalized.lower().startswith(("http://", "https://")):
                text_values.append(normalized)
        elif isinstance(value, list):
            for item in value:
                add_text(item)
        elif isinstance(value, dict):
            for key in sorted(value):
                add_text(value[key])

    for key in (
        "name",
        "title",
        "description",
        "tags",
        "keywords",
        "summary",
        "body",
        "structured",
    ):
        if key in document:
            add_text(document[key])
    if not text_values:
        text_values.extend((record_type, record_id))

    title = document.get("name") or document.get("title") or f"{scope} record"
    title = " ".join(str(title).split())[:255]
    visibility = "PRIVATE" if scope == "TENANT_PRIVATE" else "SHARED"
    result.update({
        "title": title,
        "retrievalText": "\n".join(text_values),
        "jsonbPayload": {
            "schemaVersion": "generic-client.record-projection-v1",
            "recordScope": scope,
            "scopeKey": scope_key,
            "recordType": record_type,
            "recordId": record_id,
            "visibility": visibility,
        },
        "source": "generic-client-record-projection",
        "embeddingPolicy": "ASYNC",
    })

output_path.write_text(
    json.dumps(result, ensure_ascii=True, indent=2) + "\n",
    encoding="utf-8",
)
PY
}

write_scoped_search_request() {
  local output="$1" collection_key="$2" record_scope="$3"
  local scope_key="$4" visibility="$5" query="$6"
  jq -n \
    --arg key "$collection_key" \
    --arg scope "$record_scope" \
    --arg scopeKey "$scope_key" \
    --arg visibility "$visibility" \
    --arg query "$query" \
    '{
      query:$query,
      collectionKeys:[$key],
      payloadContains:{
        schemaVersion:"generic-client.record-projection-v1",
        recordScope:$scope,
        scopeKey:$scopeKey,
        visibility:$visibility
      },
      config:{
        maxResults:10,
        minScore:0,
        useHybridSearch:true,
        useRerank:false
      }
    }' > "$output"
}

wait_for_fresh_embedding() {
  local collection_key="$1" response="$2" headers="$3"
  local expected_documents="${4:-1}"
  local attempt code
  for attempt in $(seq 1 90); do
    code="$(query_request GET "${API}/collections/embedding-readiness" \
      "$ROOT_CONFIG" "$response" "$headers" "collectionKey=${collection_key}")"
    if [[ "$code" == "200" ]] \
        && jq -e --argjson expected "$expected_documents" \
          '.enabledDocuments == $expected and .freshDocuments == $expected
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
COLLECTION_SHARED="bc.${RUN_TOKEN}.shared"
COLLECTION_CANARY="bc.${RUN_TOKEN}.canary"
COLLECTION_THROTTLED="bc.${RUN_TOKEN}.throttled"
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
    and .rootMode == true
    and .principalRole == null
    and .collectionAccessMode == "UNRESTRICTED"
    and .allowedCollectionKeys == null
    and .capabilities == ["RAG_READ","RAG_WRITE","API_KEY_MANAGE"]' \
  "environment root contract is explicit"
! rg -qi '"(rawKey|apiKeyHash|credentialHash|secret)"' "$ROOT_IDENTITY"
pass "environment root response contains no secret material"

for collection_spec in \
    "${MINIMUM_COLLECTION}|One character Collection key" \
    "${COLLECTION_A}|Contract Collection A" \
    "${COLLECTION_B}|Contract Collection B" \
    "${COLLECTION_SHARED}|Shared reference Collection" \
    "${COLLECTION_CANARY}|Binding preflight canary Collection" \
    "${COLLECTION_THROTTLED}|Rate limit recovery Collection" \
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
  "READ_WRITE" "$RESTRICTED_CREATE" "$RESTRICTED_CREATE_HEADERS")"
assert_status "$code" 201 "create restricted principal"
assert_no_store "$RESTRICTED_CREATE_HEADERS" "restricted principal creation"
assert_capability_profile "$RESTRICTED_CREATE" "READ_WRITE" \
  "restricted dispatcher creation returns exact capabilities"
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
  "READ_WRITE" "$RESTRICTED_B_CREATE" "$RESTRICTED_B_CREATE_HEADERS")"
assert_status "$code" 201 "create second restricted principal"
assert_no_store "$RESTRICTED_B_CREATE_HEADERS" "second restricted principal creation"
assert_capability_profile "$RESTRICTED_B_CREATE" "READ_WRITE" \
  "second dispatcher creation returns exact capabilities"
RESTRICTED_B_KEY_ID="$(jq -er '.keyId' "$RESTRICTED_B_CREATE")"
RESTRICTED_B_PRINCIPAL_ID="$(jq -er '.principalId' "$RESTRICTED_B_CREATE")"
RESTRICTED_B_SECRET_FILE="${CONTRACT_PRIVATE}/restricted-b.key"
extract_secret "$RESTRICTED_B_CREATE" "$RESTRICTED_B_SECRET_FILE"
RESTRICTED_B_CONFIG="${CONTRACT_PRIVATE}/restricted-b.curl"
write_auth_config "$RESTRICTED_B_CONFIG" x-api-key "$RESTRICTED_B_SECRET_FILE"

SHARED_CREATE="${CONTRACT_PRIVATE}/shared-create.json"
SHARED_CREATE_HEADERS="${SHARED_CREATE}.headers"
code="$(create_principal "Shared Record Dispatcher" "$COLLECTION_SHARED" \
  "READ_WRITE" "$SHARED_CREATE" "$SHARED_CREATE_HEADERS")"
assert_status "$code" 201 "create shared-record dispatcher"
assert_no_store "$SHARED_CREATE_HEADERS" "shared-record dispatcher creation"
assert_capability_profile "$SHARED_CREATE" "READ_WRITE" \
  "shared-record dispatcher creation returns exact capabilities"
SHARED_KEY_ID="$(jq -er '.keyId' "$SHARED_CREATE")"
SHARED_PRINCIPAL_ID="$(jq -er '.principalId' "$SHARED_CREATE")"
SHARED_SECRET_FILE="${CONTRACT_PRIVATE}/shared.key"
extract_secret "$SHARED_CREATE" "$SHARED_SECRET_FILE"
SHARED_CONFIG="${CONTRACT_PRIVATE}/shared.curl"
write_auth_config "$SHARED_CONFIG" x-api-key "$SHARED_SECRET_FILE"

CANARY_CREATE="${CONTRACT_PRIVATE}/canary-create.json"
CANARY_CREATE_HEADERS="${CANARY_CREATE}.headers"
code="$(create_principal "Binding Preflight Canary Principal" "$COLLECTION_CANARY" \
  "READ_WRITE" "$CANARY_CREATE" "$CANARY_CREATE_HEADERS")"
assert_status "$code" 201 "create binding preflight canary principal"
assert_no_store "$CANARY_CREATE_HEADERS" "binding preflight canary principal creation"
assert_capability_profile "$CANARY_CREATE" "READ_WRITE" \
  "canary principal creation returns exact capabilities"
CANARY_KEY_ID="$(jq -er '.keyId' "$CANARY_CREATE")"
CANARY_SECRET_FILE="${CONTRACT_PRIVATE}/canary.key"
extract_secret "$CANARY_CREATE" "$CANARY_SECRET_FILE"
CANARY_BEARER_CONFIG="${CONTRACT_PRIVATE}/canary-bearer.curl"
write_auth_config "$CANARY_BEARER_CONFIG" bearer "$CANARY_SECRET_FILE"

UNRESTRICTED_CREATE="${CONTRACT_PRIVATE}/unrestricted-create.json"
UNRESTRICTED_CREATE_HEADERS="${UNRESTRICTED_CREATE}.headers"
code="$(create_principal "Unrestricted Contract Principal" "" \
  "READ_WRITE" "$UNRESTRICTED_CREATE" "$UNRESTRICTED_CREATE_HEADERS")"
assert_status "$code" 201 "create unrestricted principal"
assert_capability_profile "$UNRESTRICTED_CREATE" "READ_WRITE" \
  "unrestricted principal creation returns exact capabilities"
UNRESTRICTED_KEY_ID="$(jq -er '.keyId' "$UNRESTRICTED_CREATE")"
UNRESTRICTED_SECRET_FILE="${CONTRACT_PRIVATE}/unrestricted.key"
extract_secret "$UNRESTRICTED_CREATE" "$UNRESTRICTED_SECRET_FILE"
UNRESTRICTED_CONFIG="${CONTRACT_PRIVATE}/unrestricted.curl"
write_auth_config "$UNRESTRICTED_CONFIG" x-api-key "$UNRESTRICTED_SECRET_FILE"

QUERY_CREATE="${CONTRACT_PRIVATE}/query-create.json"
QUERY_CREATE_HEADERS="${QUERY_CREATE}.headers"
code="$(create_principal_for_collections "Restricted Query Principal" \
  "READ_ONLY" "$QUERY_CREATE" "$QUERY_CREATE_HEADERS" \
  "$COLLECTION_A" "$COLLECTION_SHARED")"
assert_status "$code" 201 "create restricted read-only query principal"
assert_no_store "$QUERY_CREATE_HEADERS" "read-only query principal creation"
assert_capability_profile "$QUERY_CREATE" "READ_ONLY" \
  "query principal creation returns exact capabilities"
QUERY_KEY_ID="$(jq -er '.keyId' "$QUERY_CREATE")"
QUERY_CURRENT_KEY_ID="$QUERY_KEY_ID"
QUERY_PRINCIPAL_ID="$(jq -er '.principalId' "$QUERY_CREATE")"
QUERY_SECRET_FILE="${CONTRACT_PRIVATE}/query.key"
extract_secret "$QUERY_CREATE" "$QUERY_SECRET_FILE"
QUERY_X_CONFIG="${CONTRACT_PRIVATE}/query-x.curl"
write_auth_config "$QUERY_X_CONFIG" x-api-key "$QUERY_SECRET_FILE"

THROTTLED_CREATE="${CONTRACT_PRIVATE}/throttled-create.json"
THROTTLED_CREATE_HEADERS="${THROTTLED_CREATE}.headers"
code="$(create_principal "Rate Limited Dispatcher" "$COLLECTION_THROTTLED" \
  "READ_WRITE" "$THROTTLED_CREATE" "$THROTTLED_CREATE_HEADERS" 1)"
assert_status "$code" 201 "create one-request-per-minute dispatcher"
assert_no_store "$THROTTLED_CREATE_HEADERS" \
  "one-request-per-minute dispatcher creation"
THROTTLED_KEY_ID="$(jq -er '.keyId' "$THROTTLED_CREATE")"
THROTTLED_PRINCIPAL_ID="$(jq -er '.principalId' "$THROTTLED_CREATE")"
THROTTLED_SECRET_FILE="${CONTRACT_PRIVATE}/throttled.key"
extract_secret "$THROTTLED_CREATE" "$THROTTLED_SECRET_FILE"
THROTTLED_CONFIG="${CONTRACT_PRIVATE}/throttled.curl"
write_auth_config "$THROTTLED_CONFIG" x-api-key "$THROTTLED_SECRET_FILE"

QUERY_RESPONSE="${CONTRACT_PRIVATE}/query-rejected.json"
QUERY_HEADERS="${QUERY_RESPONSE}.headers"
code="$(curl --config "$NO_AUTH_CONFIG" --get --output "$QUERY_RESPONSE" \
  --dump-header "$QUERY_HEADERS" --write-out '%{http_code}' \
  --data-urlencode "apiKey@${QUERY_SECRET_FILE}" "${API}/auth/me")"
assert_status "$code" 401 "reject valid credential in query string"

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
    "$SHARED_SECRET_FILE" "$CANARY_SECRET_FILE" \
    "$UNRESTRICTED_SECRET_FILE" "$QUERY_SECRET_FILE" \
    "$THROTTLED_SECRET_FILE"; do
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
    and .capabilities == ["RAG_READ","RAG_WRITE"]
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
  '.principalId == $principal
    and .collectionAccessMode == "RESTRICTED"
    and .capabilities == ["RAG_READ","RAG_WRITE"]'

QUERY_IDENTITY="${CONTRACT_PRIVATE}/query-identity.json"
QUERY_IDENTITY_HEADERS="${QUERY_IDENTITY}.headers"
code="$(request GET "${API}/auth/me" "$QUERY_X_CONFIG" \
  "$QUERY_IDENTITY" "$QUERY_IDENTITY_HEADERS")"
assert_status "$code" 200 "read-only query principal authentication"
assert_no_store "$QUERY_IDENTITY_HEADERS" "read-only query introspection"
assert_json_with_args "$QUERY_IDENTITY" \
  "query introspection returns exact read-only binding" \
  --arg principal "$QUERY_PRINCIPAL_ID" \
  --arg tenantKey "$COLLECTION_A" \
  --arg sharedKey "$COLLECTION_SHARED" \
  '.principalType == "DATABASE_API_KEY"
    and .principalId == $principal
    and .principalRole == "NORMAL"
    and .collectionAccessMode == "RESTRICTED"
    and (.allowedCollectionKeys | sort) == ([$tenantKey,$sharedKey] | sort)
    and .capabilities == ["RAG_READ"]
    and .credentialVersion == 1
    and .policyVersion == 1'

SHARED_IDENTITY="${CONTRACT_PRIVATE}/shared-identity.json"
SHARED_IDENTITY_HEADERS="${SHARED_IDENTITY}.headers"
code="$(request GET "${API}/auth/me" "$SHARED_CONFIG" \
  "$SHARED_IDENTITY" "$SHARED_IDENTITY_HEADERS")"
assert_status "$code" 200 "shared-record dispatcher authentication"
assert_json_with_args "$SHARED_IDENTITY" \
  "shared-record dispatcher returns only the shared Collection" \
  --arg principal "$SHARED_PRINCIPAL_ID" --arg key "$COLLECTION_SHARED" \
  '.principalId == $principal
    and .collectionAccessMode == "RESTRICTED"
    and .allowedCollectionKeys == [$key]
    and .capabilities == ["RAG_READ","RAG_WRITE"]'

UNRESTRICTED_IDENTITY="${CONTRACT_PRIVATE}/unrestricted-identity.json"
UNRESTRICTED_IDENTITY_HEADERS="${UNRESTRICTED_IDENTITY}.headers"
code="$(request GET "${API}/auth/me" "$UNRESTRICTED_CONFIG" \
  "$UNRESTRICTED_IDENTITY" "$UNRESTRICTED_IDENTITY_HEADERS")"
assert_status "$code" 200 "unrestricted database principal introspection"
assert_json "$UNRESTRICTED_IDENTITY" \
  '.principalRole == "NORMAL"
    and .collectionAccessMode == "UNRESTRICTED"
    and .allowedCollectionKeys == null
    and .capabilities == ["RAG_READ","RAG_WRITE"]' \
  "unrestricted database principal uses null allow-list"

COLLECTION_PROBE="${CONTRACT_PRIVATE}/collection-probe.json"
COLLECTION_PROBE_HEADERS="${COLLECTION_PROBE}.headers"
code="$(query_request GET "${API}/collections/by-key" "$QUERY_X_CONFIG" \
  "$COLLECTION_PROBE" "$COLLECTION_PROBE_HEADERS" "collectionKey=${COLLECTION_A}")"
assert_status "$code" 200 "read-only binding active Collection probe"

THROTTLED_UPSERT_REQUEST="${CONTRACT_PRIVATE}/throttled-upsert.request.json"
write_upsert_request "$THROTTLED_UPSERT_REQUEST" "$COLLECTION_THROTTLED" \
  "throttled-rev-1" "" "rate-limited" \
  "client retry budget contract record" \
  "business-client.rate-limit.v1" "rate-limited-record" "SKIP"
THROTTLED_FIRST_RESPONSE="${CONTRACT_PRIVATE}/throttled-first.json"
THROTTLED_FIRST_HEADERS="${THROTTLED_FIRST_RESPONSE}.headers"
code="$(request POST "${API}/json-records/upsert" "$THROTTLED_CONFIG" \
  "$THROTTLED_FIRST_RESPONSE" "$THROTTLED_FIRST_HEADERS" \
  "$THROTTLED_UPSERT_REQUEST")"
assert_status "$code" 200 "rate-limited dispatcher persists first request"
THROTTLED_DOCUMENT_ID="$(jq -er '.documentId' "$THROTTLED_FIRST_RESPONSE")"

THROTTLED_RETRY_RESPONSE="${CONTRACT_PRIVATE}/throttled-retry.json"
THROTTLED_RETRY_HEADERS="${THROTTLED_RETRY_RESPONSE}.headers"
code="$(request POST "${API}/json-records/upsert" "$THROTTLED_CONFIG" \
  "$THROTTLED_RETRY_RESPONSE" "$THROTTLED_RETRY_HEADERS" \
  "$THROTTLED_UPSERT_REQUEST")"
assert_status "$code" 429 "exact retry is rate limited"
tr -d '\r' < "$THROTTLED_RETRY_HEADERS" \
  | rg -q '^Retry-After: ([1-9]|[1-5][0-9]|60)$' || {
  echo "rate-limited response is missing a bounded Retry-After header" >&2
  exit 1
}
tr -d '\r' < "$THROTTLED_RETRY_HEADERS" \
  | rg -q '^X-RateLimit-Limit: 1$' || {
  echo "rate-limited response does not expose the principal quota" >&2
  exit 1
}
assert_json "$THROTTLED_RETRY_RESPONSE" \
  '.error == "TOO_MANY_REQUESTS"' \
  "rate-limited retry returns a machine-readable error"

THROTTLED_POLICY_REQUEST="${CONTRACT_PRIVATE}/throttled-policy.request.json"
jq -n --arg key "$COLLECTION_THROTTLED" \
  '{
    expectedPolicyVersion:1,
    name:"Rate Limited Dispatcher",
    expiresAt:"2099-12-31T23:59:00",
    allowedCollectionKeys:[$key],
    requestsPerMinute:1000,
    capabilities:["RAG_READ","RAG_WRITE"]
  }' > "$THROTTLED_POLICY_REQUEST"
THROTTLED_POLICY_RESPONSE="${CONTRACT_PRIVATE}/throttled-policy.json"
THROTTLED_POLICY_HEADERS="${THROTTLED_POLICY_RESPONSE}.headers"
code="$(request PUT \
  "${API}/api-keys/principals/${THROTTLED_PRINCIPAL_ID}/policy" \
  "$ROOT_CONFIG" "$THROTTLED_POLICY_RESPONSE" "$THROTTLED_POLICY_HEADERS" \
  "$THROTTLED_POLICY_REQUEST")"
assert_status "$code" 200 "operator lifts dispatcher quota"
assert_json "$THROTTLED_POLICY_RESPONSE" \
  '.policyVersion == 2 and .requestsPerMinute == 1000' \
  "quota policy update is visible"

THROTTLED_REPLAY_RESPONSE="${CONTRACT_PRIVATE}/throttled-replay.json"
THROTTLED_REPLAY_HEADERS="${THROTTLED_REPLAY_RESPONSE}.headers"
code="$(request POST "${API}/json-records/upsert" "$THROTTLED_CONFIG" \
  "$THROTTLED_REPLAY_RESPONSE" "$THROTTLED_REPLAY_HEADERS" \
  "$THROTTLED_UPSERT_REQUEST")"
assert_status "$code" 200 "same request snapshot succeeds after quota recovery"
assert_json_with_args "$THROTTLED_REPLAY_RESPONSE" \
  "post-429 replay preserves one document identity" \
  --argjson documentId "$THROTTLED_DOCUMENT_ID" \
  '.documentId == $documentId
    and (.action == "REPLAYED" or .action == "UNCHANGED")'

PREFLIGHT_A_COLLECTIONS="${CONTRACT_PRIVATE}/preflight-a-collections.json"
jq -n --arg key "$COLLECTION_A" '[$key]' > "$PREFLIGHT_A_COLLECTIONS"
PREFLIGHT_QUERY_COLLECTIONS="${CONTRACT_PRIVATE}/preflight-query-collections.json"
jq -n --arg keyA "$COLLECTION_A" --arg keyShared "$COLLECTION_SHARED" \
  '[$keyA,$keyShared]' > "$PREFLIGHT_QUERY_COLLECTIONS"
PREFLIGHT_QUERY_WRONG_COLLECTIONS="${CONTRACT_PRIVATE}/preflight-query-wrong-collections.json"
jq -n \
  --arg keyA "$COLLECTION_A" \
  --arg keyB "$COLLECTION_B" \
  --arg keyShared "$COLLECTION_SHARED" \
  '[$keyA,$keyShared,$keyB]' > "$PREFLIGHT_QUERY_WRONG_COLLECTIONS"
PREFLIGHT_CANARY_COLLECTIONS="${CONTRACT_PRIVATE}/preflight-canary-collections.json"
jq -n --arg key "$COLLECTION_CANARY" '[$key]' > "$PREFLIGHT_CANARY_COLLECTIONS"

run_binding_preflight \
  "readonly-pass" "READ_ONLY" "X_API_KEY" "$QUERY_SECRET_FILE" \
  "$PREFLIGHT_QUERY_COLLECTIONS" "READ_ONLY" 0
assert_binding_report \
  "readonly-pass" "PASS" "" "" "$QUERY_SECRET_FILE" \
  "READ_ONLY" "READ_ONLY" \
  "$COLLECTION_A" "$COLLECTION_SHARED"

run_binding_preflight \
  "readwrite-pass" "READ_ONLY" "X_API_KEY" "$RESTRICTED_SECRET_FILE" \
  "$PREFLIGHT_A_COLLECTIONS" "READ_WRITE" 0
assert_binding_report \
  "readwrite-pass" "PASS" "" "" "$RESTRICTED_SECRET_FILE" \
  "READ_WRITE" "READ_WRITE" "$COLLECTION_A"

run_binding_preflight \
  "readonly-wrong-allow-list" "READ_ONLY" "X_API_KEY" "$QUERY_SECRET_FILE" \
  "$PREFLIGHT_QUERY_WRONG_COLLECTIONS" "READ_ONLY" 1
assert_binding_report \
  "readonly-wrong-allow-list" "FAIL" "POLICY_MISMATCH" "" \
  "$QUERY_SECRET_FILE" "READ_ONLY" "" \
  "$COLLECTION_A" "$COLLECTION_SHARED" "$COLLECTION_B"

run_binding_preflight \
  "capability-profile-mismatch" "READ_ONLY" "X_API_KEY" "$QUERY_SECRET_FILE" \
  "$PREFLIGHT_QUERY_COLLECTIONS" "READ_WRITE" 1
assert_binding_report \
  "capability-profile-mismatch" "FAIL" "POLICY_MISMATCH" "" \
  "$QUERY_SECRET_FILE" "READ_WRITE" "" \
  "$COLLECTION_A" "$COLLECTION_SHARED"

run_binding_preflight \
  "canary-success" "CANARY_MUTATION" "BEARER" "$CANARY_SECRET_FILE" \
  "$PREFLIGHT_CANARY_COLLECTIONS" "READ_WRITE" 0
assert_binding_report \
  "canary-success" "PASS" "" "TOMBSTONED" "$CANARY_SECRET_FILE" \
  "READ_WRITE" "READ_WRITE" \
  "$COLLECTION_CANARY"

run_binding_preflight \
  "canary-provider-failure" "CANARY_MUTATION" "BEARER" "$CANARY_SECRET_FILE" \
  "$PREFLIGHT_CANARY_COLLECTIONS" "READ_WRITE" 1 "$EMBEDDING_FAIL_MARKER"
assert_binding_report \
  "canary-provider-failure" "FAIL" "EMBEDDING_FAILED" "TOMBSTONED" \
  "$CANARY_SECRET_FILE" "READ_WRITE" "READ_WRITE" \
  "$COLLECTION_CANARY" "$EMBEDDING_FAIL_MARKER" \
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

QUERY_LOOKUP_RESPONSE="${CONTRACT_PRIVATE}/query-lookup-v1.json"
QUERY_LOOKUP_HEADERS="${QUERY_LOOKUP_RESPONSE}.headers"
code="$(query_request GET "${API}/json-records/by-external-id" \
  "$QUERY_X_CONFIG" "$QUERY_LOOKUP_RESPONSE" "$QUERY_LOOKUP_HEADERS" \
  "collectionKey=${COLLECTION_A}" \
  "sourceNamespace=business-client.contract.v1" \
  "externalId=record-1")"
assert_status "$code" 200 "read-only query principal lookup"
assert_json_with_args "$QUERY_LOOKUP_RESPONSE" \
  "read-only lookup returns dispatcher-created revision" \
  --argjson documentId "$DOCUMENT_ID" \
  '.documentId == $documentId
    and .sourceRevision == "rev-1"
    and .documentRevision == 1
    and .jsonbPayload.status == "active"
    and .enabled == true'

QUERY_SEARCH_REQUEST="${CONTRACT_PRIVATE}/query-search.request.json"
write_search_request "$QUERY_SEARCH_REQUEST" "$COLLECTION_A" "active"
QUERY_SEARCH_RESPONSE="${CONTRACT_PRIVATE}/query-search.json"
QUERY_SEARCH_HEADERS="${QUERY_SEARCH_RESPONSE}.headers"
code="$(request POST "${API}/json-records/search" "$QUERY_X_CONFIG" \
  "$QUERY_SEARCH_RESPONSE" "$QUERY_SEARCH_HEADERS" "$QUERY_SEARCH_REQUEST")"
assert_status "$code" 200 "read-only query principal search"
assert_json_with_args "$QUERY_SEARCH_RESPONSE" \
  "read-only search returns dispatcher-created record" \
  --argjson documentId "$DOCUMENT_ID" \
  '(.results | map(.documentId) | index($documentId)) != null'

QUERY_DENIED_UPSERT_REQUEST="${CONTRACT_PRIVATE}/query-denied-upsert.request.json"
write_upsert_request "$QUERY_DENIED_UPSERT_REQUEST" "$COLLECTION_A" \
  "query-denied-rev-2" "rev-1" "forbidden" "forbidden query mutation"
QUERY_DENIED_UPSERT_RESPONSE="${CONTRACT_PRIVATE}/query-denied-upsert.json"
QUERY_DENIED_UPSERT_HEADERS="${QUERY_DENIED_UPSERT_RESPONSE}.headers"
code="$(request POST "${API}/json-records/upsert" "$QUERY_X_CONFIG" \
  "$QUERY_DENIED_UPSERT_RESPONSE" "$QUERY_DENIED_UPSERT_HEADERS" \
  "$QUERY_DENIED_UPSERT_REQUEST")"
assert_status "$code" 403 "read-only query principal upsert"
assert_write_capability_denied "$QUERY_DENIED_UPSERT_RESPONSE" \
  "read-only upsert reports missing write capability"

QUERY_DENIED_DELETE_RESPONSE="${CONTRACT_PRIVATE}/query-denied-delete.json"
QUERY_DENIED_DELETE_HEADERS="${QUERY_DENIED_DELETE_RESPONSE}.headers"
code="$(query_request DELETE "${API}/json-records/by-external-id" \
  "$QUERY_X_CONFIG" "$QUERY_DENIED_DELETE_RESPONSE" \
  "$QUERY_DENIED_DELETE_HEADERS" \
  "collectionKey=${COLLECTION_A}" \
  "sourceNamespace=business-client.contract.v1" \
  "externalId=record-1" \
  "sourceRevision=query-denied-delete" \
  "expectedSourceRevision=rev-1")"
assert_status "$code" 403 "read-only query principal tombstone"
assert_write_capability_denied "$QUERY_DENIED_DELETE_RESPONSE" \
  "read-only tombstone reports missing write capability"

QUERY_UNCHANGED_RESPONSE="${CONTRACT_PRIVATE}/query-unchanged-v1.json"
QUERY_UNCHANGED_HEADERS="${QUERY_UNCHANGED_RESPONSE}.headers"
code="$(query_request GET "${API}/json-records/by-external-id" \
  "$QUERY_X_CONFIG" "$QUERY_UNCHANGED_RESPONSE" "$QUERY_UNCHANGED_HEADERS" \
  "collectionKey=${COLLECTION_A}" \
  "sourceNamespace=business-client.contract.v1" \
  "externalId=record-1")"
assert_status "$code" 200 "lookup after read-only write rejection"
assert_json_with_args "$QUERY_UNCHANGED_RESPONSE" \
  "read-only write rejection preserves record state" \
  --argjson documentId "$DOCUMENT_ID" \
  '.documentId == $documentId
    and .sourceRevision == "rev-1"
    and .documentRevision == 1
    and .jsonbPayload.status == "active"
    and .enabled == true'

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
    and .allowedCollectionKeys == [$key]
    and .capabilities == ["RAG_READ","RAG_WRITE"]'

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
assert_denied_data_plane \
  "$QUERY_X_CONFIG" "$COLLECTION_B" "$FAILED_NAMESPACE" \
  "$FAILED_EXTERNAL_ID" "dual-Collection query denies another tenant" \
  "$FAILED_DOCUMENT_ID"

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

prepare_client_envelopes
assert_client_envelope_has_private_transport "$PRIVATE_ENVELOPE_V1" \
  "private-record envelope contains private transport material before compilation"
assert_client_envelope_has_private_transport "$SHARED_ENVELOPE_V1" \
  "shared-record envelope contains private transport material before compilation"

PRIVATE_CLIENT_REQUEST="${CONTRACT_PRIVATE}/external-client-private-v1.request.json"
compile_client_envelope \
  "$PRIVATE_ENVELOPE_V1" "$COLLECTION_A" "TENANT_PRIVATE" "UPSERT" \
  "$PRIVATE_CLIENT_REQUEST"
PRIVATE_UPDATE_REQUEST="${CONTRACT_PRIVATE}/external-client-private-v2.request.json"
compile_client_envelope \
  "$PRIVATE_ENVELOPE_V2" "$COLLECTION_A" "TENANT_PRIVATE" "UPSERT" \
  "$PRIVATE_UPDATE_REQUEST" "$(jq -er '.sourceRevision | tostring' "$PRIVATE_ENVELOPE_V1")"
PRIVATE_DELETE_V3="${CONTRACT_PRIVATE}/external-client-private-v3.delete.json"
compile_client_envelope \
  "$PRIVATE_ENVELOPE_V3" "$COLLECTION_A" "TENANT_PRIVATE" "DELETE" \
  "$PRIVATE_DELETE_V3" "$(jq -er '.sourceRevision | tostring' "$PRIVATE_ENVELOPE_V2")"
PRIVATE_RESTORE_REQUEST="${CONTRACT_PRIVATE}/external-client-private-v4.request.json"
compile_client_envelope \
  "$PRIVATE_ENVELOPE_V4" "$COLLECTION_A" "TENANT_PRIVATE" "UPSERT" \
  "$PRIVATE_RESTORE_REQUEST" "$(jq -er '.sourceRevision | tostring' "$PRIVATE_ENVELOPE_V3")"
PRIVATE_DELETE_V5="${CONTRACT_PRIVATE}/external-client-private-v5.delete.json"
compile_client_envelope \
  "$PRIVATE_ENVELOPE_V5" "$COLLECTION_A" "TENANT_PRIVATE" "DELETE" \
  "$PRIVATE_DELETE_V5" "$(jq -er '.sourceRevision | tostring' "$PRIVATE_ENVELOPE_V4")"

SHARED_CLIENT_REQUEST="${CONTRACT_PRIVATE}/external-client-shared-v1.request.json"
compile_client_envelope \
  "$SHARED_ENVELOPE_V1" "$COLLECTION_SHARED" "SHARED_CATALOG" "UPSERT" \
  "$SHARED_CLIENT_REQUEST"
SHARED_DELETE_V2="${CONTRACT_PRIVATE}/external-client-shared-v2.delete.json"
compile_client_envelope \
  "$SHARED_ENVELOPE_V2" "$COLLECTION_SHARED" "SHARED_CATALOG" "DELETE" \
  "$SHARED_DELETE_V2" "$(jq -er '.sourceRevision | tostring' "$SHARED_ENVELOPE_V1")"

for request_file in \
    "$PRIVATE_CLIENT_REQUEST" "$PRIVATE_UPDATE_REQUEST" \
    "$PRIVATE_RESTORE_REQUEST" "$SHARED_CLIENT_REQUEST"; do
  assert_projection_is_sanitized "$request_file" \
    "$(basename "$request_file") excludes private envelope and credential material"
done

PRIVATE_EXTERNAL_ID="$(jq -er '.externalId' "$PRIVATE_CLIENT_REQUEST")"
PRIVATE_RECORD_ID="$(jq -er '.jsonbPayload.recordId' "$PRIVATE_CLIENT_REQUEST")"
PRIVATE_SCOPE_KEY="$(jq -er '.jsonbPayload.scopeKey' "$PRIVATE_CLIENT_REQUEST")"
PRIVATE_RECORD_TYPE="$(jq -er '.jsonbPayload.recordType' "$PRIVATE_CLIENT_REQUEST")"
PRIVATE_REVISION_V1="$(jq -er '.sourceRevision' "$PRIVATE_CLIENT_REQUEST")"
PRIVATE_REVISION_V2="$(jq -er '.sourceRevision' "$PRIVATE_UPDATE_REQUEST")"
PRIVATE_REVISION_V3="$(jq -er '.sourceRevision' "$PRIVATE_DELETE_V3")"
PRIVATE_REVISION_V4="$(jq -er '.sourceRevision' "$PRIVATE_RESTORE_REQUEST")"
PRIVATE_REVISION_V5="$(jq -er '.sourceRevision' "$PRIVATE_DELETE_V5")"
PRIVATE_QUERY_TEXT="$(jq -er '.retrievalText' "$PRIVATE_RESTORE_REQUEST")"
SHARED_EXTERNAL_ID="$(jq -er '.externalId' "$SHARED_CLIENT_REQUEST")"
SHARED_RECORD_ID="$(jq -er '.jsonbPayload.recordId' "$SHARED_CLIENT_REQUEST")"
SHARED_SCOPE_KEY="$(jq -er '.jsonbPayload.scopeKey' "$SHARED_CLIENT_REQUEST")"
SHARED_RECORD_TYPE="$(jq -er '.jsonbPayload.recordType' "$SHARED_CLIENT_REQUEST")"
SHARED_REVISION_V1="$(jq -er '.sourceRevision' "$SHARED_CLIENT_REQUEST")"
SHARED_REVISION_V2="$(jq -er '.sourceRevision' "$SHARED_DELETE_V2")"
SHARED_QUERY_TEXT="$(jq -er '.retrievalText' "$SHARED_CLIENT_REQUEST")"

for lifecycle_file in \
    "$PRIVATE_UPDATE_REQUEST" "$PRIVATE_DELETE_V3" \
    "$PRIVATE_RESTORE_REQUEST" "$PRIVATE_DELETE_V5"; do
  [[ "$(jq -er '.externalId' "$lifecycle_file")" == "$PRIVATE_EXTERNAL_ID" ]] || {
    echo "Private lifecycle did not compile to one stable externalId" >&2
    exit 1
  }
done
[[ "$(jq -er '.externalId' "$SHARED_DELETE_V2")" == "$SHARED_EXTERNAL_ID" ]] || {
  echo "Shared lifecycle did not compile to one stable externalId" >&2
  exit 1
}
pass "client envelope lifecycles compile to stable hashed external identities"

PRIVATE_CLIENT_RESPONSE="${CONTRACT_PRIVATE}/external-client-private.json"
PRIVATE_CLIENT_HEADERS="${PRIVATE_CLIENT_RESPONSE}.headers"
code="$(request POST "${API}/json-records/upsert" "$RESTRICTED_X_CONFIG" \
  "$PRIVATE_CLIENT_RESPONSE" "$PRIVATE_CLIENT_HEADERS" "$PRIVATE_CLIENT_REQUEST")"
assert_status "$code" 200 "tenant dispatcher writes private-scoped record"
assert_json_with_args "$PRIVATE_CLIENT_RESPONSE" \
  "private-scoped record persists with asynchronous embedding" \
  --arg revision "$PRIVATE_REVISION_V1" \
  '.action == "CREATED"
    and .sourceRevision == $revision
    and (.embeddingAction == "ASYNC_QUEUED"
      or .embeddingAction == "ASYNC_COALESCED")'
PRIVATE_CLIENT_DOCUMENT_ID="$(jq -er '.documentId' "$PRIVATE_CLIENT_RESPONSE")"

PRIVATE_UPDATE_RESPONSE="${CONTRACT_PRIVATE}/external-client-private-v2.json"
PRIVATE_UPDATE_HEADERS="${PRIVATE_UPDATE_RESPONSE}.headers"
code="$(request POST "${API}/json-records/upsert" "$RESTRICTED_X_CONFIG" \
  "$PRIVATE_UPDATE_RESPONSE" "$PRIVATE_UPDATE_HEADERS" "$PRIVATE_UPDATE_REQUEST")"
assert_status "$code" 200 "tenant dispatcher applies client CAS update"
assert_json_with_args "$PRIVATE_UPDATE_RESPONSE" \
  "client CAS update preserves document identity and advances revision" \
  --argjson documentId "$PRIVATE_CLIENT_DOCUMENT_ID" \
  --arg revision "$PRIVATE_REVISION_V2" \
  '.documentId == $documentId and .sourceRevision == $revision
    and .action == "UPDATED"'

PRIVATE_DELETE_V3_RESPONSE="${CONTRACT_PRIVATE}/external-client-private-v3.json"
PRIVATE_DELETE_V3_HEADERS="${PRIVATE_DELETE_V3_RESPONSE}.headers"
code="$(query_request DELETE "${API}/json-records/by-external-id" \
  "$RESTRICTED_X_CONFIG" "$PRIVATE_DELETE_V3_RESPONSE" \
  "$PRIVATE_DELETE_V3_HEADERS" \
  "collectionKey=${COLLECTION_A}" \
  "sourceNamespace=$(jq -er '.sourceNamespace' "$PRIVATE_DELETE_V3")" \
  "externalId=${PRIVATE_EXTERNAL_ID}" \
  "sourceRevision=${PRIVATE_REVISION_V3}" \
  "expectedSourceRevision=$(jq -er '.expectedSourceRevision' "$PRIVATE_DELETE_V3")")"
assert_status "$code" 200 "tenant dispatcher applies client tombstone"
assert_json_with_args "$PRIVATE_DELETE_V3_RESPONSE" \
  "client tombstone preserves identity and disables searchability" \
  --argjson documentId "$PRIVATE_CLIENT_DOCUMENT_ID" \
  --arg revision "$PRIVATE_REVISION_V3" \
  '.documentId == $documentId and .sourceRevision == $revision
    and .enabled == false
    and .lifecycle.documentState == "TOMBSTONED"
    and .lifecycle.searchability == "DISABLED"'

PRIVATE_RESTORE_RESPONSE="${CONTRACT_PRIVATE}/external-client-private-v4.json"
PRIVATE_RESTORE_HEADERS="${PRIVATE_RESTORE_RESPONSE}.headers"
code="$(request POST "${API}/json-records/upsert" "$RESTRICTED_X_CONFIG" \
  "$PRIVATE_RESTORE_RESPONSE" "$PRIVATE_RESTORE_HEADERS" \
  "$PRIVATE_RESTORE_REQUEST")"
assert_status "$code" 200 "tenant dispatcher restores client record"
assert_json_with_args "$PRIVATE_RESTORE_RESPONSE" \
  "client restore upsert reuses identity and returns to active lifecycle" \
  --argjson documentId "$PRIVATE_CLIENT_DOCUMENT_ID" \
  --arg revision "$PRIVATE_REVISION_V4" \
  '.documentId == $documentId and .sourceRevision == $revision
    and .lifecycle.documentState == "ACTIVE"'

PRIVATE_RESTORE_LOOKUP="${CONTRACT_PRIVATE}/external-client-private-v4.lookup.json"
PRIVATE_RESTORE_LOOKUP_HEADERS="${PRIVATE_RESTORE_LOOKUP}.headers"
code="$(query_request GET "${API}/json-records/by-external-id" \
  "$RESTRICTED_X_CONFIG" "$PRIVATE_RESTORE_LOOKUP" \
  "$PRIVATE_RESTORE_LOOKUP_HEADERS" \
  "collectionKey=${COLLECTION_A}" \
  "sourceNamespace=$(jq -er '.sourceNamespace' "$PRIVATE_RESTORE_REQUEST")" \
  "externalId=${PRIVATE_EXTERNAL_ID}")"
assert_status "$code" 200 "tenant dispatcher reads restored client record"
assert_json_with_args "$PRIVATE_RESTORE_LOOKUP" \
  "persisted client restore reuses identity and is enabled" \
  --argjson documentId "$PRIVATE_CLIENT_DOCUMENT_ID" \
  --arg revision "$PRIVATE_REVISION_V4" \
  '.documentId == $documentId and .sourceRevision == $revision
    and .enabled == true
    and .lifecycle.documentState == "ACTIVE"
    and .lifecycle.searchability != "DISABLED"'

SHARED_CLIENT_RESPONSE="${CONTRACT_PRIVATE}/external-client-shared.json"
SHARED_CLIENT_HEADERS="${SHARED_CLIENT_RESPONSE}.headers"
code="$(request POST "${API}/json-records/upsert" "$SHARED_CONFIG" \
  "$SHARED_CLIENT_RESPONSE" "$SHARED_CLIENT_HEADERS" \
  "$SHARED_CLIENT_REQUEST")"
assert_status "$code" 200 "shared dispatcher writes shared-scoped record"
assert_json_with_args "$SHARED_CLIENT_RESPONSE" \
  "shared-scoped record persists with asynchronous embedding" \
  --arg revision "$SHARED_REVISION_V1" \
  '.action == "CREATED"
    and .sourceRevision == $revision
    and (.embeddingAction == "ASYNC_QUEUED"
      or .embeddingAction == "ASYNC_COALESCED")'
SHARED_CLIENT_DOCUMENT_ID="$(jq -er '.documentId' "$SHARED_CLIENT_RESPONSE")"

wait_for_fresh_embedding \
  "$COLLECTION_A" "$READINESS_RESPONSE" "$READINESS_HEADERS" 2
SHARED_READINESS_RESPONSE="${CONTRACT_PRIVATE}/shared-embedding-readiness.json"
SHARED_READINESS_HEADERS="${SHARED_READINESS_RESPONSE}.headers"
wait_for_fresh_embedding \
  "$COLLECTION_SHARED" "$SHARED_READINESS_RESPONSE" \
  "$SHARED_READINESS_HEADERS"

assert_denied_data_plane \
  "$RESTRICTED_X_CONFIG" "$COLLECTION_SHARED" \
  "generic-client.record-mutation.v1" "$SHARED_EXTERNAL_ID" \
  "tenant dispatcher denies shared-scope Collection" \
  "$SHARED_CLIENT_DOCUMENT_ID"
assert_denied_data_plane \
  "$SHARED_CONFIG" "$COLLECTION_A" \
  "generic-client.record-mutation.v1" "$PRIVATE_EXTERNAL_ID" \
  "shared dispatcher denies tenant Collection" \
  "$PRIVATE_CLIENT_DOCUMENT_ID"

PRIVATE_QUERY_REQUEST="${CONTRACT_PRIVATE}/external-client-private-search.request.json"
write_scoped_search_request \
  "$PRIVATE_QUERY_REQUEST" "$COLLECTION_A" "TENANT_PRIVATE" \
  "$PRIVATE_SCOPE_KEY" "PRIVATE" "$PRIVATE_QUERY_TEXT"
PRIVATE_QUERY_RESPONSE="${CONTRACT_PRIVATE}/external-client-private-search.json"
PRIVATE_QUERY_HEADERS="${PRIVATE_QUERY_RESPONSE}.headers"
code="$(request POST "${API}/json-records/search" "$QUERY_X_CONFIG" \
  "$PRIVATE_QUERY_RESPONSE" "$PRIVATE_QUERY_HEADERS" "$PRIVATE_QUERY_REQUEST")"
assert_status "$code" 200 "dual-Collection query searches tenant route"
assert_json_with_args "$PRIVATE_QUERY_RESPONSE" \
  "tenant route returns only the private-scoped fixture" \
  --argjson documentId "$PRIVATE_CLIENT_DOCUMENT_ID" \
  --arg scopeKey "$PRIVATE_SCOPE_KEY" \
  --arg recordType "$PRIVATE_RECORD_TYPE" \
  --arg recordId "$PRIVATE_RECORD_ID" \
  '(.results | length) >= 1
    and (.results | map(.documentId) | index($documentId)) != null
    and all(.results[];
      .jsonbPayload.schemaVersion == "generic-client.record-projection-v1"
      and .jsonbPayload.recordScope == "TENANT_PRIVATE"
      and .jsonbPayload.scopeKey == $scopeKey
      and .jsonbPayload.recordType == $recordType
      and .jsonbPayload.recordId == $recordId
      and .jsonbPayload.visibility == "PRIVATE")'

SHARED_QUERY_REQUEST="${CONTRACT_PRIVATE}/external-client-shared-search.request.json"
write_scoped_search_request \
  "$SHARED_QUERY_REQUEST" "$COLLECTION_SHARED" \
  "SHARED_CATALOG" "$SHARED_SCOPE_KEY" "SHARED" "$SHARED_QUERY_TEXT"
SHARED_QUERY_RESPONSE="${CONTRACT_PRIVATE}/external-client-shared-search.json"
SHARED_QUERY_HEADERS="${SHARED_QUERY_RESPONSE}.headers"
code="$(request POST "${API}/json-records/search" "$QUERY_X_CONFIG" \
  "$SHARED_QUERY_RESPONSE" "$SHARED_QUERY_HEADERS" \
  "$SHARED_QUERY_REQUEST")"
assert_status "$code" 200 "dual-Collection query searches shared route"
assert_json_with_args "$SHARED_QUERY_RESPONSE" \
  "shared route returns only the shared-scoped fixture" \
  --argjson documentId "$SHARED_CLIENT_DOCUMENT_ID" \
  --arg scopeKey "$SHARED_SCOPE_KEY" \
  --arg recordType "$SHARED_RECORD_TYPE" \
  --arg recordId "$SHARED_RECORD_ID" \
  '(.results | length) >= 1
    and (.results | map(.documentId) | index($documentId)) != null
    and all(.results[];
      .jsonbPayload.schemaVersion == "generic-client.record-projection-v1"
      and .jsonbPayload.recordScope == "SHARED_CATALOG"
      and .jsonbPayload.scopeKey == $scopeKey
      and .jsonbPayload.recordType == $recordType
      and .jsonbPayload.recordId == $recordId
      and .jsonbPayload.visibility == "SHARED")'

SAFE_CLIENT_RESPONSE="${CONTRACT_PRIVATE}/client-safe-records.json"
python3 - \
  "$PRIVATE_QUERY_RESPONSE" "$SHARED_QUERY_RESPONSE" \
  "$PRIVATE_CLIENT_DOCUMENT_ID" "$SHARED_CLIENT_DOCUMENT_ID" \
  "$SAFE_CLIENT_RESPONSE" "$PRIVATE_RECORD_ID" "$SHARED_RECORD_ID" <<'PY'
import json
from pathlib import Path
import sys

private_record = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
shared_record = json.loads(Path(sys.argv[2]).read_text(encoding="utf-8"))
expected = sorted((int(sys.argv[3]), int(sys.argv[4])))
private_source_id = sys.argv[6]
shared_source_id = sys.argv[7]

def merge(left, right):
    unique = {}
    for route, response in (("TENANT_PRIVATE", left), ("SHARED_CATALOG", right)):
        for result in response["results"]:
            unique[(route, int(result["documentId"]))] = {
                "route": route,
                "documentId": int(result["documentId"]),
            }
    return sorted(unique.values(), key=lambda item: (item["route"], item["documentId"]))

first = merge(private_record, shared_record)
second = merge(private_record, shared_record)
if first != second:
    raise SystemExit("two-route merge is not deterministic")
ids = sorted(item["documentId"] for item in first)
if not all(document_id in ids for document_id in expected):
    raise SystemExit("two-route merge omitted an expected fixture")

# Simulate the client's authoritative reload. The client-facing DTO is rebuilt
# from trusted business records and excludes all RAG transport fields.
safe = {
    "items": [
        {
            "kind": "PRIVATE_RECORD",
            "recordId": private_source_id,
            "displayName": "Authorized private record",
        },
        {
            "kind": "SHARED_RECORD",
            "recordId": shared_source_id,
            "displayName": "Visible shared record",
        },
    ]
}
Path(sys.argv[5]).write_text(
    json.dumps(safe, ensure_ascii=True, indent=2) + "\n",
    encoding="utf-8",
)
PY
pass "tenant and shared route results merge deterministically"
assert_json "$SAFE_CLIENT_RESPONSE" \
  '(.items | length) == 2
    and all(.items[];
      (has("documentId") | not)
      and (has("externalId") | not)
      and (has("collectionKey") | not)
      and (has("score") | not)
      and (has("jsonbPayload") | not)
      and (has("retrievalText") | not))' \
  "authoritative client-facing DTO excludes RAG transport fields"

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
    and .allowedCollectionKeys == [$key]
    and .capabilities == ["RAG_READ","RAG_WRITE"]'

QUERY_ROTATE_RESPONSE="${CONTRACT_PRIVATE}/query-rotate.json"
QUERY_ROTATE_HEADERS="${QUERY_ROTATE_RESPONSE}.headers"
code="$(request POST "${API}/api-keys/${QUERY_KEY_ID}/rotate" \
  "$ROOT_CONFIG" "$QUERY_ROTATE_RESPONSE" "$QUERY_ROTATE_HEADERS")"
assert_status "$code" 201 "rotate dual-Collection query credential"
assert_no_store "$QUERY_ROTATE_HEADERS" "dual-Collection query rotation"
QUERY_ROTATED_KEY_ID="$(jq -er '.keyId' "$QUERY_ROTATE_RESPONSE")"
QUERY_ROTATED_SECRET_FILE="${CONTRACT_PRIVATE}/query-rotated.key"
extract_secret "$QUERY_ROTATE_RESPONSE" "$QUERY_ROTATED_SECRET_FILE"
QUERY_ROTATED_CONFIG="${CONTRACT_PRIVATE}/query-rotated.curl"
write_auth_config "$QUERY_ROTATED_CONFIG" x-api-key "$QUERY_ROTATED_SECRET_FILE"
QUERY_CURRENT_KEY_ID="$QUERY_ROTATED_KEY_ID"

QUERY_OLD_AFTER_ROTATE="${CONTRACT_PRIVATE}/query-old-after-rotate.json"
QUERY_OLD_AFTER_ROTATE_HEADERS="${QUERY_OLD_AFTER_ROTATE}.headers"
code="$(request GET "${API}/auth/me" "$QUERY_X_CONFIG" \
  "$QUERY_OLD_AFTER_ROTATE" "$QUERY_OLD_AFTER_ROTATE_HEADERS")"
assert_status "$code" 401 "old dual-Collection query credential after rotation"

QUERY_NEW_AFTER_ROTATE="${CONTRACT_PRIVATE}/query-new-after-rotate.json"
QUERY_NEW_AFTER_ROTATE_HEADERS="${QUERY_NEW_AFTER_ROTATE}.headers"
code="$(request GET "${API}/auth/me" "$QUERY_ROTATED_CONFIG" \
  "$QUERY_NEW_AFTER_ROTATE" "$QUERY_NEW_AFTER_ROTATE_HEADERS")"
assert_status "$code" 200 "new dual-Collection query credential after rotation"
assert_json_with_args "$QUERY_NEW_AFTER_ROTATE" \
  "query rotation preserves both Collection bindings without root fallback" \
  --arg principal "$QUERY_PRINCIPAL_ID" \
  --arg tenantKey "$COLLECTION_A" \
  --arg sharedKey "$COLLECTION_SHARED" \
  '.principalId == $principal
    and .credentialVersion == 2
    and .policyVersion == 1
    and .collectionAccessMode == "RESTRICTED"
    and (.allowedCollectionKeys | sort) == ([$tenantKey,$sharedKey] | sort)
    and .capabilities == ["RAG_READ"]'

QUERY_ROTATED_PRIVATE_RESPONSE="${CONTRACT_PRIVATE}/query-rotated-private.json"
QUERY_ROTATED_PRIVATE_HEADERS="${QUERY_ROTATED_PRIVATE_RESPONSE}.headers"
code="$(request POST "${API}/json-records/search" "$QUERY_ROTATED_CONFIG" \
  "$QUERY_ROTATED_PRIVATE_RESPONSE" "$QUERY_ROTATED_PRIVATE_HEADERS" \
  "$PRIVATE_QUERY_REQUEST")"
assert_status "$code" 200 "rotated query credential searches tenant route"
assert_json_with_args "$QUERY_ROTATED_PRIVATE_RESPONSE" \
  "rotated query credential retains tenant result" \
  --argjson documentId "$PRIVATE_CLIENT_DOCUMENT_ID" \
  '(.results | map(.documentId) | index($documentId)) != null'

QUERY_ROTATED_SHARED_RESPONSE="${CONTRACT_PRIVATE}/query-rotated-shared.json"
QUERY_ROTATED_SHARED_HEADERS="${QUERY_ROTATED_SHARED_RESPONSE}.headers"
code="$(request POST "${API}/json-records/search" "$QUERY_ROTATED_CONFIG" \
  "$QUERY_ROTATED_SHARED_RESPONSE" "$QUERY_ROTATED_SHARED_HEADERS" \
  "$SHARED_QUERY_REQUEST")"
assert_status "$code" 200 "rotated query credential searches shared route"
assert_json_with_args "$QUERY_ROTATED_SHARED_RESPONSE" \
  "rotated query credential retains shared result" \
  --argjson documentId "$SHARED_CLIENT_DOCUMENT_ID" \
  '(.results | map(.documentId) | index($documentId)) != null'

PRIVATE_DELETE_V5_RESPONSE="${CONTRACT_PRIVATE}/external-client-private-v5.json"
PRIVATE_DELETE_V5_HEADERS="${PRIVATE_DELETE_V5_RESPONSE}.headers"
code="$(query_request DELETE "${API}/json-records/by-external-id" \
  "$ROTATED_CONFIG" "$PRIVATE_DELETE_V5_RESPONSE" \
  "$PRIVATE_DELETE_V5_HEADERS" \
  "collectionKey=${COLLECTION_A}" \
  "sourceNamespace=$(jq -er '.sourceNamespace' "$PRIVATE_DELETE_V5")" \
  "externalId=${PRIVATE_EXTERNAL_ID}" \
  "sourceRevision=${PRIVATE_REVISION_V5}" \
  "expectedSourceRevision=$(jq -er '.expectedSourceRevision' "$PRIVATE_DELETE_V5")")"
assert_status "$code" 200 "rotated tenant dispatcher applies final client tombstone"
assert_json_with_args "$PRIVATE_DELETE_V5_RESPONSE" \
  "final private-record tombstone preserves identity and revision" \
  --argjson documentId "$PRIVATE_CLIENT_DOCUMENT_ID" \
  --arg revision "$PRIVATE_REVISION_V5" \
  '.documentId == $documentId and .sourceRevision == $revision
    and .enabled == false
    and .lifecycle.documentState == "TOMBSTONED"'

SHARED_DELETE_V2_RESPONSE="${CONTRACT_PRIVATE}/external-client-shared-v2.json"
SHARED_DELETE_V2_HEADERS="${SHARED_DELETE_V2_RESPONSE}.headers"
code="$(query_request DELETE "${API}/json-records/by-external-id" \
  "$SHARED_CONFIG" "$SHARED_DELETE_V2_RESPONSE" \
  "$SHARED_DELETE_V2_HEADERS" \
  "collectionKey=${COLLECTION_SHARED}" \
  "sourceNamespace=$(jq -er '.sourceNamespace' "$SHARED_DELETE_V2")" \
  "externalId=${SHARED_EXTERNAL_ID}" \
  "sourceRevision=${SHARED_REVISION_V2}" \
  "expectedSourceRevision=$(jq -er '.expectedSourceRevision' "$SHARED_DELETE_V2")")"
assert_status "$code" 200 "shared dispatcher applies shared-record tombstone"
assert_json_with_args "$SHARED_DELETE_V2_RESPONSE" \
  "shared-record tombstone preserves identity and revision" \
  --argjson documentId "$SHARED_CLIENT_DOCUMENT_ID" \
  --arg revision "$SHARED_REVISION_V2" \
  '.documentId == $documentId and .sourceRevision == $revision
    and .enabled == false
    and .lifecycle.documentState == "TOMBSTONED"'

PRIVATE_AFTER_DELETE_RESPONSE="${CONTRACT_PRIVATE}/external-client-private-after-delete.json"
PRIVATE_AFTER_DELETE_HEADERS="${PRIVATE_AFTER_DELETE_RESPONSE}.headers"
code="$(request POST "${API}/json-records/search" "$QUERY_ROTATED_CONFIG" \
  "$PRIVATE_AFTER_DELETE_RESPONSE" "$PRIVATE_AFTER_DELETE_HEADERS" \
  "$PRIVATE_QUERY_REQUEST")"
assert_status "$code" 200 "rotated query searches tenant route after tombstone"
assert_json "$PRIVATE_AFTER_DELETE_RESPONSE" '.results == []' \
  "private-record tombstone removes the client record from search"

SHARED_AFTER_DELETE_RESPONSE="${CONTRACT_PRIVATE}/external-client-shared-after-delete.json"
SHARED_AFTER_DELETE_HEADERS="${SHARED_AFTER_DELETE_RESPONSE}.headers"
code="$(request POST "${API}/json-records/search" "$QUERY_ROTATED_CONFIG" \
  "$SHARED_AFTER_DELETE_RESPONSE" "$SHARED_AFTER_DELETE_HEADERS" \
  "$SHARED_QUERY_REQUEST")"
assert_status "$code" 200 "rotated query searches shared route after tombstone"
assert_json "$SHARED_AFTER_DELETE_RESPONSE" '.results == []' \
  "shared-record tombstone removes the shared record from search"

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
  printf 'capability_contract=query_read_only_dispatcher_read_write_preflight_rotation\n'
  printf 'dual_collection_contract=tenant_and_shared_query_cross_dispatcher_acl_rotation\n'
  printf 'client_envelope_contract=compiled_sanitized_cas_tombstone_restore_lifecycle\n'
  printf 'acl_contract=bidirectional_full_data_plane_anti_enumeration\n'
  printf 'json_record_contract=replay_cas_payload_filter_tombstone_restore_async_failure_preserves_record\n'
  printf 'retry_contract=429_retry_after_same_snapshot_replay\n'
  printf 'credential_contract=headers_query_reject_rotation_revocation\n'
} > "${EVIDENCE_DIR}/summary.txt"

printf '\nBusiness client HTTP contract passed: %s checks\n' "$PASS_COUNT"
printf 'Summary: %s/summary.txt\n' "$EVIDENCE_DIR"
