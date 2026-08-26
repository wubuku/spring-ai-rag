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

printf 'Binding preflight self-test passed: 11 negative cases\n'
