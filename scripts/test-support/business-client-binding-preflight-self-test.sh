#!/usr/bin/env bash
# Fast negative and report-safety checks for the deployed binding preflight runner.
set -euo pipefail

cd "$(dirname "$0")/../.."

ROOT_DIR="$(mktemp -d)"
trap 'rm -rf "$ROOT_DIR"' EXIT
umask 077

CREDENTIAL_FILE="${ROOT_DIR}/credential"
COLLECTIONS_FILE="${ROOT_DIR}/collections.json"
VALID_COLLECTIONS_FILE="${ROOT_DIR}/valid-collections.json"
printf 'rag_sk_%064d\n' 0 > "$CREDENTIAL_FILE"
chmod 600 "$CREDENTIAL_FILE"
printf '["sample-a","sample-b"]\n' > "$VALID_COLLECTIONS_FILE"
printf '["sample-a","sample-b"]\n' > "$COLLECTIONS_FILE"

run_negative() {
  local name="$1" expected_category="$2" base_url="$3"
  local mode="$4" auth_scheme="$5" confirm="$6" canary_key="$7"
  local capability_profile="${8:-READ_WRITE}"
  local expected_report_profile="${9-$capability_profile}"
  local evidence="${ROOT_DIR}/${name}" output="${ROOT_DIR}/${name}.out"
  local error="${ROOT_DIR}/${name}.err" rc report

  set +e
  RAG_BINDING_BASE_URL="$base_url" \
  RAG_BINDING_CREDENTIAL_FILE="$CREDENTIAL_FILE" \
  RAG_BINDING_EXPECTED_COLLECTIONS_FILE="$COLLECTIONS_FILE" \
  RAG_BINDING_TARGET_LABEL="self-test-${name}" \
  RAG_BINDING_PREFLIGHT_MODE="$mode" \
  RAG_BINDING_AUTH_SCHEME="$auth_scheme" \
  RAG_BINDING_EXPECTED_CAPABILITY_PROFILE="$capability_profile" \
  RAG_BINDING_CANARY_CONFIRM="$confirm" \
  RAG_BINDING_CANARY_COLLECTION_KEY="$canary_key" \
  RAG_BINDING_PREFLIGHT_EVIDENCE_DIR="$evidence" \
    scripts/business-client-binding-preflight.sh > "$output" 2> "$error"
  rc=$?
  set -e
  [[ "$rc" -ne 0 ]] || {
    echo "${name}: expected a non-zero exit" >&2
    return 1
  }
  report="${evidence}/preflight-report.json"
  jq -e \
    --arg category "$expected_category" \
    --arg expectedProfile "$expected_report_profile" \
    '.result == "FAIL"
      and .failureCategory == $category
      and .verification.requiredOperationCount == 6
      and .expectedCapabilityProfile
        == (if $expectedProfile == "" then null else $expectedProfile end)
      and .principal.capabilityProfile == null' \
    "$report" >/dev/null || {
    echo "${name}: invalid or unsafe report" >&2
    return 1
  }
  ! rg -F "$(tr -d '\r\n' < "$CREDENTIAL_FILE")" "$report" >/dev/null \
    || {
      echo "${name}: report contains credential material" >&2
      return 1
    }
  ! rg -F "sample-a" "$report" >/dev/null || {
    echo "${name}: report contains Collection identity" >&2
    return 1
  }
  if [[ -z "$expected_report_profile" && -n "$capability_profile" ]]; then
    ! rg -F "$capability_profile" "$report" \
      "${evidence}/summary.md" >/dev/null || {
      echo "${name}: report contains an invalid raw capability profile" >&2
      return 1
    }
  fi
  printf 'PASS: %s\n' "$name"
}

run_capability_case() {
  local name="$1" expected_exit="$2" expected_category="$3"
  local batch_items="$4" batch_payload="$5" observability="$6"
  local protocol="${7:-1.0}" extra_env="${8:-}"
  local evidence="${ROOT_DIR}/${name}" output="${ROOT_DIR}/${name}.out"
  local error="${ROOT_DIR}/${name}.err" report port_file stub_pid rc

  port_file="${ROOT_DIR}/${name}.port"
  stub_pid="$(
    python3 scripts/test-support/business-client-capability-stub.py \
      --port 0 \
      --port-file "$port_file" \
      --batch-items "$batch_items" \
      --batch-payload-bytes "$batch_payload" \
      --observability "$observability" \
      --protocol-version "$protocol" \
      > "${ROOT_DIR}/${name}.stub.log" 2>&1 &
    printf '%s' "$!"
  )"
  for _ in $(seq 1 50); do
    [[ -s "$port_file" ]] && break
    sleep 0.02
  done
  port="$(<"$port_file")"

  set +e
  env \
  RAG_BINDING_BASE_URL="http://127.0.0.1:${port}" \
  RAG_BINDING_CREDENTIAL_FILE="$CREDENTIAL_FILE" \
  RAG_BINDING_EXPECTED_COLLECTIONS_FILE="$COLLECTIONS_FILE" \
  RAG_BINDING_TARGET_LABEL="self-test-${name}" \
  RAG_BINDING_PREFLIGHT_MODE="READ_ONLY" \
  RAG_BINDING_AUTH_SCHEME="X_API_KEY" \
  RAG_BINDING_EXPECTED_CAPABILITY_PROFILE="READ_ONLY" \
  RAG_BINDING_ALLOW_HTTP_LOOPBACK=true \
  RAG_BINDING_PREFLIGHT_EVIDENCE_DIR="$evidence" \
  RAG_BINDING_MIN_JSON_BATCH_ITEMS=10 \
  RAG_BINDING_MIN_JSON_BATCH_PAYLOAD_BYTES=1000000 \
  RAG_BINDING_REQUIRE_OPERATION_OBSERVABILITY=true \
  $extra_env \
    scripts/business-client-binding-preflight.sh > "$output" 2> "$error"
  rc=$?
  set -e
  kill "$stub_pid" >/dev/null 2>&1 || true
  wait "$stub_pid" >/dev/null 2>&1 || true

  [[ "$rc" == "$expected_exit" ]] || {
    echo "${name}: expected exit ${expected_exit}, got ${rc}" >&2
    sed -n '1,80p' "$error" >&2 || true
    return 1
  }
  report="${evidence}/preflight-report.json"
  jq -e \
    --arg category "$expected_category" \
    --arg expectedProtocol "$protocol" \
    --argjson expectedBatchItems "$batch_items" \
    --argjson expectedBatchPayload "$batch_payload" \
    --argjson expectedObservability "$([[ "$observability" == "true" ]] && printf true || printf false)" \
    '.result == (if $category == "" then "PASS" else "FAIL" end)
      and .failureCategory == (if $category == "" then null else $category end)
      and (if $category == "CAPABILITY_CONTRACT_MISMATCH"
        then .capability == {
          protocolVersion:null,
          jsonBatchItems:null,
          jsonBatchPayloadBytes:null,
          operationObservability:null
        }
        else .capability.protocolVersion == $expectedProtocol
          and .capability.jsonBatchItems == $expectedBatchItems
          and .capability.jsonBatchPayloadBytes == $expectedBatchPayload
          and .capability.operationObservability == $expectedObservability
        end)
      and .requirements.minJsonBatchItems == 10
      and .requirements.minJsonBatchPayloadBytes == 1000000
      and .requirements.requireOperationObservability == true' \
    "$report" >/dev/null || {
    echo "${name}: capability report assertion failed" >&2
    return 1
  }
  ! rg -F "$(tr -d '\r\n' < "$CREDENTIAL_FILE")" "$report" >/dev/null \
    || {
      echo "${name}: capability report contains credential material" >&2
      return 1
    }
  printf 'PASS: %s\n' "$name"
}

run_negative \
  "query-url" "INVALID_BASE_URL" \
  "https://rag.example.invalid?apiKey=should-not-be-logged" \
  "READ_ONLY" "X_API_KEY" "" ""

chmod 644 "$CREDENTIAL_FILE"
run_negative \
  "credential-permissions" "INSECURE_CREDENTIAL_PERMISSIONS" \
  "https://rag.example.invalid" \
  "READ_ONLY" "X_API_KEY" "" ""
chmod 600 "$CREDENTIAL_FILE"

printf '["sample-a","sample-b"]\n' > "$COLLECTIONS_FILE"
run_negative \
  "insecure-http" "INSECURE_HTTP_TARGET" \
  "http://rag.example.invalid" \
  "READ_ONLY" "X_API_KEY" "" ""

printf '["sample-a","sample-a"]\n' > "$COLLECTIONS_FILE"
run_negative \
  "duplicate-collections" "DUPLICATE_COLLECTION_KEY" \
  "https://rag.example.invalid" \
  "READ_ONLY" "X_API_KEY" "" ""

printf '["sample-a","sample-b"]\n' > "$COLLECTIONS_FILE"
run_negative \
  "canary-confirmation" "CANARY_CONFIRMATION_REQUIRED" \
  "https://rag.example.invalid" \
  "CANARY_MUTATION" "BEARER" "" "sample-a"

run_negative \
  "canary-allow-list" "CANARY_COLLECTION_NOT_ALLOWED" \
  "https://rag.example.invalid" \
  "CANARY_MUTATION" "BEARER" "YES" "sample-missing"

run_negative \
  "canary-only-expected" "CANARY_COLLECTION_MUST_BE_ONLY_EXPECTED" \
  "https://rag.example.invalid" \
  "CANARY_MUTATION" "BEARER" "YES" "sample-a"

run_negative \
  "unexpected-auth-scheme" "INVALID_AUTH_SCHEME" \
  "https://rag.example.invalid" \
  "READ_ONLY" "INVALID" "" ""

run_negative \
  "read-only-canary-input" "CANARY_INPUT_NOT_ALLOWED_IN_READ_ONLY" \
  "https://rag.example.invalid" \
  "READ_ONLY" "X_API_KEY" "YES" "sample-a"

run_negative \
  "invalid-capability-profile" "INVALID_CAPABILITY_PROFILE" \
  "https://rag.example.invalid" \
  "READ_ONLY" "X_API_KEY" "" "" "DO_NOT_REPORT_THIS_VALUE" ""

printf '["sample-a"]\n' > "$COLLECTIONS_FILE"
run_negative \
  "read-only-canary-profile" "CAPABILITY_PROFILE_INCOMPATIBLE_WITH_MODE" \
  "https://rag.example.invalid" \
  "CANARY_MUTATION" "BEARER" "YES" "sample-a" "READ_ONLY"

printf '["sample-a","sample-b"]\n' > "$COLLECTIONS_FILE"
run_capability_case "capability-pass" 0 "" 20 10485760 true
run_capability_case "capability-low-items" 1 "CAPABILITY_LIMIT_TOO_LOW" 9 10485760 true
run_capability_case "capability-low-payload" 1 "CAPABILITY_LIMIT_TOO_LOW" 20 999999 true
run_capability_case "capability-feature-required" 1 "CAPABILITY_FEATURE_REQUIRED" 20 10485760 false
run_capability_case "capability-protocol-mismatch" 1 "CAPABILITY_CONTRACT_MISMATCH" 20 10485760 true 1.1

set +e
RAG_BINDING_BASE_URL="https://rag.example.invalid" \
RAG_BINDING_CREDENTIAL_FILE="$CREDENTIAL_FILE" \
RAG_BINDING_EXPECTED_COLLECTIONS_FILE="$COLLECTIONS_FILE" \
RAG_BINDING_TARGET_LABEL="invalid-minimum" \
RAG_BINDING_MIN_JSON_BATCH_ITEMS=0 \
RAG_BINDING_PREFLIGHT_EVIDENCE_DIR="${ROOT_DIR}/invalid-minimum" \
  scripts/business-client-binding-preflight.sh >/dev/null 2>&1
rc=$?
set -e
[[ "$rc" -ne 0 ]] || {
  echo "invalid-minimum: expected a non-zero exit" >&2
  exit 1
}
jq -e '.failureCategory == "INVALID_MIN_JSON_BATCH_ITEMS"' \
  "${ROOT_DIR}/invalid-minimum/preflight-report.json" >/dev/null
printf 'PASS: invalid minimum input is rejected before network access\n'

printf 'Binding preflight self-test passed: 11 negative cases and 5 capability cases\n'
