#!/usr/bin/env bash
# Validates a deployed spring-ai-rag business binding without requiring root access.
set -uo pipefail

cd "$(dirname "$0")/.."

GENERATED_RUN_ID="$(date +%Y%m%d-%H%M%S)-$$-$(openssl rand -hex 4 2>/dev/null || printf 'local')"
RAW_RUN_ID="${RAG_BINDING_PREFLIGHT_RUN_ID:-$GENERATED_RUN_ID}"
RUN_ID="$RAW_RUN_ID"
TARGET_LABEL="${RAG_BINDING_TARGET_LABEL:-}"
MODE="${RAG_BINDING_PREFLIGHT_MODE:-READ_ONLY}"
AUTH_SCHEME="${RAG_BINDING_AUTH_SCHEME:-X_API_KEY}"
CAPABILITY_PROFILE="${RAG_BINDING_EXPECTED_CAPABILITY_PROFILE:-READ_WRITE}"
EVIDENCE_DIR="${RAG_BINDING_PREFLIGHT_EVIDENCE_DIR:-}"
REQUEST_TIMEOUT_SECONDS="${RAG_BINDING_REQUEST_TIMEOUT_SECONDS:-30}"
READY_TIMEOUT_SECONDS="${RAG_BINDING_READY_TIMEOUT_SECONDS:-180}"
MIN_JSON_BATCH_ITEMS="${RAG_BINDING_MIN_JSON_BATCH_ITEMS:-}"
MIN_JSON_BATCH_PAYLOAD_BYTES="${RAG_BINDING_MIN_JSON_BATCH_PAYLOAD_BYTES:-}"
REQUIRE_OPERATION_OBSERVABILITY="${RAG_BINDING_REQUIRE_OPERATION_OBSERVABILITY:-}"

PRIVATE_DIR=""
STEPS_FILE=""
AUTH_CONFIG=""
NO_AUTH_CONFIG=""
VALIDATED_INPUT=""
BASE_URL=""
API=""
CA_CERT_FILE=""
PROTOCOL_RULE="=https"

RESULT="FAIL"
FAILED_STEP=""
FAILURE_CATEGORY=""
PASSED_STEPS=0
API_VERSION=""
CAPABILITY_PROTOCOL_VERSION=""
VERIFIED_JSON_BATCH_ITEMS=""
VERIFIED_JSON_BATCH_PAYLOAD_BYTES=""
VERIFIED_OPERATION_OBSERVABILITY=""
PRINCIPAL_TYPE=""
PRINCIPAL_ROLE=""
ACCESS_MODE=""
CREDENTIAL_VERSION=""
POLICY_VERSION=""
EXPECTED_COLLECTION_COUNT=""
VERIFIED_CAPABILITY_PROFILE=""
CANARY_FINAL_STATE=""

CANARY_MAY_EXIST=false
CANARY_NAMESPACE="spring-ai-rag.binding-preflight.v1"
CANARY_EXTERNAL_ID=""
CANARY_COLLECTION_KEY=""
CANARY_RETRIEVAL_MARKER=""
REV_CREATED=""
REV_UPDATED=""
REV_TOMBSTONED=""
REV_RESTORED=""
REV_CLEANUP=""
REV_TRAP_CLEANUP=""
DOCUMENT_ID=""
INITIAL_DOCUMENT_REVISION=""

HTTP_CODE=""

safe_value_or_empty() {
  local value="$1" pattern="$2" max_length="$3"
  if [[ -n "$value" && ${#value} -le "$max_length" && "$value" =~ $pattern ]]; then
    printf '%s' "$value"
  fi
}

bootstrap_evidence() {
  local safe_run safe_label
  safe_run="$(safe_value_or_empty "$RUN_ID" '^[A-Za-z0-9._-]+$' 96)"
  if [[ -z "$safe_run" ]]; then
    RUN_ID="$GENERATED_RUN_ID"
    FAILED_STEP="input"
    FAILURE_CATEGORY="INVALID_RUN_ID"
  fi
  safe_label="$(safe_value_or_empty "$TARGET_LABEL" '^[A-Za-z0-9._-]+$' 64)"
  if [[ -z "$safe_label" ]]; then
    FAILED_STEP="${FAILED_STEP:-input}"
    FAILURE_CATEGORY="${FAILURE_CATEGORY:-INVALID_TARGET_LABEL}"
  else
    TARGET_LABEL="$safe_label"
  fi
  if [[ "$MODE" != "READ_ONLY" && "$MODE" != "CANARY_MUTATION" ]]; then
    FAILED_STEP="${FAILED_STEP:-input}"
    FAILURE_CATEGORY="${FAILURE_CATEGORY:-INVALID_MODE}"
    MODE=""
  fi
  if [[ "$AUTH_SCHEME" != "X_API_KEY" && "$AUTH_SCHEME" != "BEARER" ]]; then
    FAILED_STEP="${FAILED_STEP:-input}"
    FAILURE_CATEGORY="${FAILURE_CATEGORY:-INVALID_AUTH_SCHEME}"
    AUTH_SCHEME=""
  fi
  if [[ "$CAPABILITY_PROFILE" != "READ_ONLY"
      && "$CAPABILITY_PROFILE" != "READ_WRITE" ]]; then
    FAILED_STEP="${FAILED_STEP:-input}"
    FAILURE_CATEGORY="${FAILURE_CATEGORY:-INVALID_CAPABILITY_PROFILE}"
    CAPABILITY_PROFILE=""
  fi

  if [[ -z "$EVIDENCE_DIR" ]]; then
    EVIDENCE_DIR=".verification/business-client-binding/${RUN_ID}"
  fi
  mkdir -p "$EVIDENCE_DIR" || return 1
  PRIVATE_DIR="$(mktemp -d "${EVIDENCE_DIR%/}/private.XXXXXX")" || return 1
  chmod 700 "$PRIVATE_DIR" || return 1
  STEPS_FILE="${EVIDENCE_DIR%/}/steps.tsv"
  printf 'step\tstatus\thttpStatus\tcategory\n' > "$STEPS_FILE" || return 1
  VALIDATED_INPUT="${PRIVATE_DIR}/validated-input.json"
  AUTH_CONFIG="${PRIVATE_DIR}/auth.curl"
  NO_AUTH_CONFIG="${PRIVATE_DIR}/no-auth.curl"
}

record_step() {
  local step="$1" status="$2" http_status="${3:-}" category="${4:-}"
  printf '%s\t%s\t%s\t%s\n' \
    "$step" "$status" "$http_status" "$category" >> "$STEPS_FILE"
}

pass_step() {
  local step="$1" http_status="${2:-}"
  PASSED_STEPS=$((PASSED_STEPS + 1))
  record_step "$step" "PASS" "$http_status" ""
  printf 'PASS: %s\n' "$step"
}

fail_step() {
  local step="$1" category="$2" http_status="${3:-}"
  if [[ -z "$FAILED_STEP" ]]; then
    FAILED_STEP="$step"
    FAILURE_CATEGORY="$category"
  fi
  record_step "$step" "FAIL" "$http_status" "$category"
  printf 'FAIL: %s (%s%s)\n' \
    "$step" "$category" "${http_status:+, HTTP ${http_status}}" >&2
  return 1
}

write_report() {
  local exit_code="$1"
  if [[ "$exit_code" -eq 0 ]]; then
    RESULT="PASS"
  else
    RESULT="FAIL"
  fi
  REPORT_RUN_ID="$RUN_ID" \
  REPORT_RESULT="$RESULT" \
  REPORT_MODE="$MODE" \
  REPORT_TARGET_LABEL="$TARGET_LABEL" \
  REPORT_API_VERSION="$API_VERSION" \
  REPORT_CAPABILITY_PROTOCOL_VERSION="$CAPABILITY_PROTOCOL_VERSION" \
  REPORT_VERIFIED_JSON_BATCH_ITEMS="$VERIFIED_JSON_BATCH_ITEMS" \
  REPORT_VERIFIED_JSON_BATCH_PAYLOAD_BYTES="$VERIFIED_JSON_BATCH_PAYLOAD_BYTES" \
  REPORT_VERIFIED_OPERATION_OBSERVABILITY="$VERIFIED_OPERATION_OBSERVABILITY" \
  REPORT_MIN_JSON_BATCH_ITEMS="$MIN_JSON_BATCH_ITEMS" \
  REPORT_MIN_JSON_BATCH_PAYLOAD_BYTES="$MIN_JSON_BATCH_PAYLOAD_BYTES" \
  REPORT_REQUIRE_OPERATION_OBSERVABILITY="$REQUIRE_OPERATION_OBSERVABILITY" \
  REPORT_AUTH_SCHEME="$AUTH_SCHEME" \
  REPORT_EXPECTED_CAPABILITY_PROFILE="$CAPABILITY_PROFILE" \
  REPORT_VERIFIED_CAPABILITY_PROFILE="$VERIFIED_CAPABILITY_PROFILE" \
  REPORT_PRINCIPAL_TYPE="$PRINCIPAL_TYPE" \
  REPORT_PRINCIPAL_ROLE="$PRINCIPAL_ROLE" \
  REPORT_ACCESS_MODE="$ACCESS_MODE" \
  REPORT_CREDENTIAL_VERSION="$CREDENTIAL_VERSION" \
  REPORT_POLICY_VERSION="$POLICY_VERSION" \
  REPORT_COLLECTION_COUNT="$EXPECTED_COLLECTION_COUNT" \
  REPORT_PASSED_STEPS="$PASSED_STEPS" \
  REPORT_CANARY_FINAL_STATE="$CANARY_FINAL_STATE" \
  REPORT_FAILED_STEP="$FAILED_STEP" \
  REPORT_FAILURE_CATEGORY="$FAILURE_CATEGORY" \
    python3 - "${EVIDENCE_DIR%/}/preflight-report.json" <<'PY'
import json
import os
from pathlib import Path
import sys

def nullable(name):
    value = os.environ.get(name, "").strip()
    return value or None

def nullable_int(name):
    value = nullable(name)
    return int(value) if value is not None else None

def nullable_bool(name):
    value = nullable(name)
    if value is None:
        return None
    if value not in {"true", "false"}:
        raise SystemExit(f"{name} must be true or false")
    return value == "true"

payload = {
    "schemaVersion": 1,
    "runId": os.environ["REPORT_RUN_ID"],
    "result": os.environ["REPORT_RESULT"],
    "mode": nullable("REPORT_MODE"),
    "targetLabel": nullable("REPORT_TARGET_LABEL"),
    "apiVersion": nullable("REPORT_API_VERSION"),
    "capability": {
        "protocolVersion": nullable("REPORT_CAPABILITY_PROTOCOL_VERSION"),
        "jsonBatchItems": nullable_int("REPORT_VERIFIED_JSON_BATCH_ITEMS"),
        "jsonBatchPayloadBytes": nullable_int(
            "REPORT_VERIFIED_JSON_BATCH_PAYLOAD_BYTES"
        ),
        "operationObservability": nullable_bool(
            "REPORT_VERIFIED_OPERATION_OBSERVABILITY"
        ),
    },
    "requirements": {
        "minJsonBatchItems": nullable_int("REPORT_MIN_JSON_BATCH_ITEMS"),
        "minJsonBatchPayloadBytes": nullable_int(
            "REPORT_MIN_JSON_BATCH_PAYLOAD_BYTES"
        ),
        "requireOperationObservability": nullable_bool(
            "REPORT_REQUIRE_OPERATION_OBSERVABILITY"
        ),
    },
    "credentialTransport": nullable("REPORT_AUTH_SCHEME"),
    "expectedCapabilityProfile": nullable(
        "REPORT_EXPECTED_CAPABILITY_PROFILE"
    ),
    "principal": {
        "type": nullable("REPORT_PRINCIPAL_TYPE"),
        "role": nullable("REPORT_PRINCIPAL_ROLE"),
        "accessMode": nullable("REPORT_ACCESS_MODE"),
        "capabilityProfile": nullable("REPORT_VERIFIED_CAPABILITY_PROFILE"),
        "credentialVersion": nullable_int("REPORT_CREDENTIAL_VERSION"),
        "policyVersion": nullable_int("REPORT_POLICY_VERSION"),
        "expectedCollectionCount": nullable_int("REPORT_COLLECTION_COUNT"),
    },
    "verification": {
        "passedSteps": int(os.environ["REPORT_PASSED_STEPS"]),
        "requiredOperationCount": 6,
        "canaryFinalState": nullable("REPORT_CANARY_FINAL_STATE"),
    },
    "failedStep": nullable("REPORT_FAILED_STEP"),
    "failureCategory": nullable("REPORT_FAILURE_CATEGORY"),
}
if payload["result"] == "PASS":
    expected = payload["expectedCapabilityProfile"]
    actual = payload["principal"]["capabilityProfile"]
    if expected is None or actual != expected:
        raise SystemExit("successful report requires a verified capability profile")
    if payload["capability"]["protocolVersion"] != "1.1":
        raise SystemExit("successful report requires capability protocol 1.1")
    if not isinstance(payload["capability"]["jsonBatchItems"], int):
        raise SystemExit("successful report requires a verified JSON batch limit")
    if not isinstance(payload["capability"]["jsonBatchPayloadBytes"], int):
        raise SystemExit(
            "successful report requires a verified JSON batch payload limit"
        )
    if not isinstance(payload["capability"]["operationObservability"], bool):
        raise SystemExit(
            "successful report requires a verified observability feature"
        )
path = Path(sys.argv[1])
temporary = path.with_suffix(".json.tmp")
temporary.write_text(
    json.dumps(payload, ensure_ascii=True, indent=2) + "\n",
    encoding="utf-8",
)
temporary.replace(path)
PY

  {
    echo "# Business client binding preflight"
    echo
    echo "- Run: \`${RUN_ID}\`"
    echo "- Target: \`${TARGET_LABEL:-unavailable}\`"
    echo "- Mode: \`${MODE:-unavailable}\`"
    echo "- Credential transport: \`${AUTH_SCHEME:-unavailable}\`"
    echo "- Expected capability profile: \`${CAPABILITY_PROFILE:-unavailable}\`"
    echo "- Verified capability profile: \`${VERIFIED_CAPABILITY_PROFILE:-unavailable}\`"
    echo "- Result: **${RESULT}**"
    echo "- API version: \`${API_VERSION:-unavailable}\`"
    echo "- Capability protocol: \`${CAPABILITY_PROTOCOL_VERSION:-unavailable}\`"
    echo "- Verified JSON batch items: \`${VERIFIED_JSON_BATCH_ITEMS:-unavailable}\`"
    echo "- Verified JSON batch payload bytes: \`${VERIFIED_JSON_BATCH_PAYLOAD_BYTES:-unavailable}\`"
    echo "- Operation observability: \`${VERIFIED_OPERATION_OBSERVABILITY:-unavailable}\`"
    echo "- Minimum JSON batch items: \`${MIN_JSON_BATCH_ITEMS:-not-required}\`"
    echo "- Minimum JSON batch payload bytes: \`${MIN_JSON_BATCH_PAYLOAD_BYTES:-not-required}\`"
    echo "- Operation observability required: \`${REQUIRE_OPERATION_OBSERVABILITY:-not-required}\`"
    echo "- Passed steps: **${PASSED_STEPS}**"
    echo "- Canary final state: \`${CANARY_FINAL_STATE:-not-applicable}\`"
    if [[ "$RESULT" != "PASS" ]]; then
      echo "- Failed step: \`${FAILED_STEP:-unknown}\`"
      echo "- Failure category: \`${FAILURE_CATEGORY:-UNKNOWN}\`"
    fi
  } > "${EVIDENCE_DIR%/}/summary.md"
}

write_auth_configs() {
  local credential
  credential="$(tr -d '\r\n' < "${RAG_BINDING_CREDENTIAL_FILE}")"
  {
    printf 'silent\n'
    printf 'show-error\n'
    printf 'connect-timeout = 5\n'
    printf 'header = "Accept: application/json"\n'
    if [[ "$AUTH_SCHEME" == "BEARER" ]]; then
      printf 'header = "Authorization: Bearer %s"\n' "$credential"
    else
      printf 'header = "X-API-Key: %s"\n' "$credential"
    fi
  } > "$AUTH_CONFIG"
  {
    printf 'silent\n'
    printf 'show-error\n'
    printf 'connect-timeout = 5\n'
    printf 'header = "Accept: application/json"\n'
  } > "$NO_AUTH_CONFIG"
  chmod 600 "$AUTH_CONFIG" "$NO_AUTH_CONFIG"
  unset credential
}

validate_inputs() {
  local validation_category
  if ! command -v python3 >/dev/null \
      || ! command -v jq >/dev/null \
      || ! command -v curl >/dev/null \
      || ! command -v rg >/dev/null; then
    fail_step "input_validation" "MISSING_PREREQUISITE"
    return 1
  fi
  if [[ -n "$FAILED_STEP" ]]; then
    fail_step "input_validation" "$FAILURE_CATEGORY"
    return 1
  fi

  validation_category="$(
    VALIDATED_OUTPUT="$VALIDATED_INPUT" \
    INPUT_RUN_ID="$RUN_ID" \
    INPUT_TARGET_LABEL="$TARGET_LABEL" \
    INPUT_MODE="$MODE" \
    INPUT_AUTH_SCHEME="$AUTH_SCHEME" \
    INPUT_CAPABILITY_PROFILE="$CAPABILITY_PROFILE" \
    INPUT_BASE_URL="${RAG_BINDING_BASE_URL:-}" \
    INPUT_CREDENTIAL_FILE="${RAG_BINDING_CREDENTIAL_FILE:-}" \
    INPUT_COLLECTIONS_FILE="${RAG_BINDING_EXPECTED_COLLECTIONS_FILE:-}" \
    INPUT_CANARY_CONFIRM="${RAG_BINDING_CANARY_CONFIRM:-}" \
    INPUT_CANARY_COLLECTION_KEY="${RAG_BINDING_CANARY_COLLECTION_KEY:-}" \
    INPUT_CANARY_RETRIEVAL_MARKER="${RAG_BINDING_CANARY_RETRIEVAL_MARKER:-}" \
    INPUT_CA_CERT_FILE="${RAG_BINDING_CA_CERT_FILE:-}" \
    INPUT_ALLOW_HTTP_LOOPBACK="${RAG_BINDING_ALLOW_HTTP_LOOPBACK:-false}" \
    INPUT_REQUEST_TIMEOUT="$REQUEST_TIMEOUT_SECONDS" \
    INPUT_READY_TIMEOUT="$READY_TIMEOUT_SECONDS" \
    INPUT_MIN_JSON_BATCH_ITEMS="$MIN_JSON_BATCH_ITEMS" \
    INPUT_MIN_JSON_BATCH_PAYLOAD_BYTES="$MIN_JSON_BATCH_PAYLOAD_BYTES" \
    INPUT_REQUIRE_OPERATION_OBSERVABILITY="$REQUIRE_OPERATION_OBSERVABILITY" \
      python3 <<'PY'
import ipaddress
import json
import os
from pathlib import Path
import re
import stat
from urllib.parse import urlsplit, urlunsplit

class ValidationError(Exception):
    pass

def reject(category):
    raise ValidationError(category)

def regular_file(raw, category, *, private=False):
    if not raw:
        reject(category)
    path = Path(raw)
    try:
        info = path.lstat()
    except OSError:
        reject(category)
    if stat.S_ISLNK(info.st_mode) or not stat.S_ISREG(info.st_mode):
        reject(category)
    if private and info.st_mode & 0o077:
        reject("INSECURE_CREDENTIAL_PERMISSIONS")
    if not os.access(path, os.R_OK):
        reject(category)
    return path

try:
    run_id = os.environ["INPUT_RUN_ID"]
    target_label = os.environ["INPUT_TARGET_LABEL"]
    mode = os.environ["INPUT_MODE"]
    auth_scheme = os.environ["INPUT_AUTH_SCHEME"]
    capability_profile = os.environ["INPUT_CAPABILITY_PROFILE"]
    if not re.fullmatch(r"[A-Za-z0-9._-]{1,96}", run_id):
        reject("INVALID_RUN_ID")
    if not re.fullmatch(r"[A-Za-z0-9._-]{1,64}", target_label):
        reject("INVALID_TARGET_LABEL")
    if mode not in {"READ_ONLY", "CANARY_MUTATION"}:
        reject("INVALID_MODE")
    if auth_scheme not in {"X_API_KEY", "BEARER"}:
        reject("INVALID_AUTH_SCHEME")
    if capability_profile not in {"READ_ONLY", "READ_WRITE"}:
        reject("INVALID_CAPABILITY_PROFILE")
    expected_capabilities = (
        ["RAG_READ"]
        if capability_profile == "READ_ONLY"
        else ["RAG_READ", "RAG_WRITE"]
    )

    credential_path = regular_file(
        os.environ.get("INPUT_CREDENTIAL_FILE", ""),
        "INVALID_CREDENTIAL_FILE",
        private=True,
    )
    credential_bytes = credential_path.read_bytes()
    candidates = [credential_bytes]
    if credential_bytes.endswith(b"\r\n"):
        candidates.append(credential_bytes[:-2])
    elif credential_bytes.endswith(b"\n"):
        candidates.append(credential_bytes[:-1])
    credential = candidates[-1]
    try:
        credential_text = credential.decode("ascii")
    except UnicodeDecodeError:
        reject("INVALID_CREDENTIAL_FORMAT")
    if credential_bytes not in {
        credential,
        credential + b"\n",
        credential + b"\r\n",
    } or not re.fullmatch(r"rag_sk_[0-9a-f]{64}", credential_text):
        reject("INVALID_CREDENTIAL_FORMAT")

    collections_path = regular_file(
        os.environ.get("INPUT_COLLECTIONS_FILE", ""),
        "INVALID_COLLECTIONS_FILE",
    )
    try:
        collections = json.loads(collections_path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError):
        reject("INVALID_COLLECTIONS_FILE")
    if not isinstance(collections, list) or not 1 <= len(collections) <= 100:
        reject("INVALID_COLLECTIONS")
    if any(
        not isinstance(value, str)
        or not re.fullmatch(r"[\x21-\x7e]{1,128}", value)
        for value in collections
    ):
        reject("INVALID_COLLECTION_KEY")
    if len(set(collections)) != len(collections):
        reject("DUPLICATE_COLLECTION_KEY")

    raw_url = os.environ.get("INPUT_BASE_URL", "")
    if not raw_url or raw_url != raw_url.strip():
        reject("INVALID_BASE_URL")
    try:
        parts = urlsplit(raw_url)
        _ = parts.port
    except ValueError:
        reject("INVALID_BASE_URL")
    if (
        parts.scheme not in {"http", "https"}
        or not parts.hostname
        or parts.username is not None
        or parts.password is not None
        or parts.query
        or parts.fragment
        or parts.path not in {"", "/"}
    ):
        reject("INVALID_BASE_URL")
    allow_http = os.environ.get("INPUT_ALLOW_HTTP_LOOPBACK", "false")
    if allow_http not in {"true", "false"}:
        reject("INVALID_HTTP_LOOPBACK_FLAG")
    host = parts.hostname.lower()
    loopback = host == "localhost"
    if not loopback:
        try:
            loopback = ipaddress.ip_address(host).is_loopback
        except ValueError:
            loopback = False
    if parts.scheme == "http" and not (loopback and allow_http == "true"):
        reject("INSECURE_HTTP_TARGET")
    normalized_url = urlunsplit((parts.scheme, parts.netloc, "", "", ""))

    ca_path = None
    raw_ca = os.environ.get("INPUT_CA_CERT_FILE", "")
    if raw_ca:
        ca_path = regular_file(raw_ca, "INVALID_CA_CERT_FILE")

    try:
        request_timeout = int(os.environ["INPUT_REQUEST_TIMEOUT"])
        ready_timeout = int(os.environ["INPUT_READY_TIMEOUT"])
    except ValueError:
        reject("INVALID_TIMEOUT")
    if not 1 <= request_timeout <= 120 or not 1 <= ready_timeout <= 900:
        reject("INVALID_TIMEOUT")

    def optional_positive_int(name, category, maximum):
        raw = os.environ.get(name, "")
        if not raw:
            return None
        if not re.fullmatch(r"[1-9][0-9]{0,8}", raw):
            reject(category)
        value = int(raw)
        if value > maximum:
            reject(category)
        return value

    min_json_batch_items = optional_positive_int(
        "INPUT_MIN_JSON_BATCH_ITEMS",
        "INVALID_MIN_JSON_BATCH_ITEMS",
        100_000,
    )
    min_json_batch_payload_bytes = optional_positive_int(
        "INPUT_MIN_JSON_BATCH_PAYLOAD_BYTES",
        "INVALID_MIN_JSON_BATCH_PAYLOAD_BYTES",
        1_073_741_824,
    )
    require_operation_observability = os.environ.get(
        "INPUT_REQUIRE_OPERATION_OBSERVABILITY", ""
    )
    if require_operation_observability not in {"", "true", "false"}:
        reject("INVALID_REQUIRE_OPERATION_OBSERVABILITY")

    canary_key = os.environ.get("INPUT_CANARY_COLLECTION_KEY", "")
    canary_confirm = os.environ.get("INPUT_CANARY_CONFIRM", "")
    canary_retrieval_marker = os.environ.get("INPUT_CANARY_RETRIEVAL_MARKER", "")
    if canary_retrieval_marker and not re.fullmatch(
        r"[A-Za-z0-9._-]{1,128}", canary_retrieval_marker
    ):
        reject("INVALID_CANARY_RETRIEVAL_MARKER")
    if mode == "CANARY_MUTATION":
        if capability_profile != "READ_WRITE":
            reject("CAPABILITY_PROFILE_INCOMPATIBLE_WITH_MODE")
        if canary_confirm != "YES":
            reject("CANARY_CONFIRMATION_REQUIRED")
        if canary_key not in collections:
            reject("CANARY_COLLECTION_NOT_ALLOWED")
        if len(collections) != 1 or canary_key != collections[0]:
            reject("CANARY_COLLECTION_MUST_BE_ONLY_EXPECTED")
    elif canary_key or canary_confirm:
        reject("CANARY_INPUT_NOT_ALLOWED_IN_READ_ONLY")

    output = {
        "baseUrl": normalized_url,
        "expectedCollections": collections,
        "expectedCollectionCount": len(collections),
        "capabilityProfile": capability_profile,
        "expectedCapabilities": expected_capabilities,
        "canaryCollectionKey": canary_key or None,
        "canaryRetrievalMarker": canary_retrieval_marker or None,
        "caCertFile": str(ca_path) if ca_path is not None else None,
        "requestTimeoutSeconds": request_timeout,
        "readyTimeoutSeconds": ready_timeout,
        "httpLoopback": parts.scheme == "http",
        "minJsonBatchItems": min_json_batch_items,
        "minJsonBatchPayloadBytes": min_json_batch_payload_bytes,
        "requireOperationObservability": (
            require_operation_observability == "true"
            if require_operation_observability
            else None
        ),
    }
    Path(os.environ["VALIDATED_OUTPUT"]).write_text(
        json.dumps(output, ensure_ascii=True, separators=(",", ":")) + "\n",
        encoding="utf-8",
    )
except ValidationError as error:
    print(str(error))
    raise SystemExit(1)
PY
  )"
  if [[ $? -ne 0 ]]; then
    fail_step "input_validation" "${validation_category:-INVALID_INPUT}"
    return 1
  fi

  BASE_URL="$(jq -er '.baseUrl' "$VALIDATED_INPUT")" || return 1
  API="${BASE_URL}/api/v1/rag"
  CA_CERT_FILE="$(jq -r '.caCertFile // ""' "$VALIDATED_INPUT")" || return 1
  REQUEST_TIMEOUT_SECONDS="$(jq -er '.requestTimeoutSeconds' "$VALIDATED_INPUT")" \
    || return 1
  READY_TIMEOUT_SECONDS="$(jq -er '.readyTimeoutSeconds' "$VALIDATED_INPUT")" \
    || return 1
  MIN_JSON_BATCH_ITEMS="$(jq -r '.minJsonBatchItems // ""' "$VALIDATED_INPUT")" \
    || return 1
  MIN_JSON_BATCH_PAYLOAD_BYTES="$(
    jq -r '.minJsonBatchPayloadBytes // ""' "$VALIDATED_INPUT"
  )" || return 1
  if [[ "$(jq -r '.requireOperationObservability' "$VALIDATED_INPUT")" == "true" ]]; then
    REQUIRE_OPERATION_OBSERVABILITY="true"
  elif [[ "$(jq -r '.requireOperationObservability' "$VALIDATED_INPUT")" == "false" ]]; then
    REQUIRE_OPERATION_OBSERVABILITY="false"
  else
    REQUIRE_OPERATION_OBSERVABILITY=""
  fi
  EXPECTED_COLLECTION_COUNT="$(
    jq -er '.expectedCollectionCount' "$VALIDATED_INPUT"
  )" || return 1
  CAPABILITY_PROFILE="$(
    jq -er '.capabilityProfile' "$VALIDATED_INPUT"
  )" || return 1
  CANARY_COLLECTION_KEY="$(
    jq -r '.canaryCollectionKey // ""' "$VALIDATED_INPUT"
  )" || return 1
  CANARY_RETRIEVAL_MARKER="$(
    jq -r '.canaryRetrievalMarker // ""' "$VALIDATED_INPUT"
  )" || return 1
  if [[ "$(jq -r '.httpLoopback' "$VALIDATED_INPUT")" == "true" ]]; then
    PROTOCOL_RULE="=http,https"
  fi

  write_auth_configs || {
    fail_step "input_validation" "AUTH_CONFIG_WRITE_FAILED"
    return 1
  }
  pass_step "input_validation"
}

curl_common_args() {
  return 0
}

http_request() {
  local method="$1" url="$2" auth_config="$3" output="$4" headers="$5"
  local timeout="$6" body="${7:-}"
  local args=(
    --config "$auth_config"
    --proto "$PROTOCOL_RULE"
    --max-time "$timeout"
    --request "$method"
    --output "$output"
    --dump-header "$headers"
    --write-out '%{http_code}'
  )
  if [[ -n "$CA_CERT_FILE" ]]; then
    args+=(--cacert "$CA_CERT_FILE")
  fi
  if [[ -n "$body" ]]; then
    args+=(--header 'Content-Type: application/json' --data-binary "@${body}")
  fi
  if ! HTTP_CODE="$(curl "${args[@]}" "$url" 2>"${PRIVATE_DIR}/curl.stderr")"; then
    HTTP_CODE=""
    return 1
  fi
}

http_query() {
  local method="$1" url="$2" auth_config="$3" output="$4" headers="$5"
  local timeout="$6"
  shift 6
  local args=(
    --config "$auth_config"
    --proto "$PROTOCOL_RULE"
    --max-time "$timeout"
    --get
    --request "$method"
    --output "$output"
    --dump-header "$headers"
    --write-out '%{http_code}'
  )
  if [[ -n "$CA_CERT_FILE" ]]; then
    args+=(--cacert "$CA_CERT_FILE")
  fi
  local pair
  for pair in "$@"; do
    args+=(--data-urlencode "$pair")
  done
  if ! HTTP_CODE="$(curl "${args[@]}" "$url" 2>"${PRIVATE_DIR}/curl.stderr")"; then
    HTTP_CODE=""
    return 1
  fi
}

require_http() {
  local step="$1" expected="$2"
  if [[ "$HTTP_CODE" != "$expected" ]]; then
    fail_step "$step" "UNEXPECTED_HTTP_STATUS" "$HTTP_CODE"
    return 1
  fi
}

capability_preflight() {
  local response headers
  response="${PRIVATE_DIR}/capabilities.json"
  headers="${response}.headers"
  if ! http_request GET "${API}/integration-capabilities" \
      "$AUTH_CONFIG" "$response" "$headers" "$REQUEST_TIMEOUT_SECONDS"; then
    fail_step "integration_capabilities" "TRANSPORT_ERROR"
    return 1
  fi
  require_http "integration_capabilities" 200 || return 1
  if ! tr -d '\r' < "$headers" | rg -qi '^cache-control:.*no-store'; then
    fail_step "integration_capabilities" "NO_STORE_REQUIRED" "$HTTP_CODE"
    return 1
  fi
  if ! jq -e '
      .protocol.name == "spring-ai-rag-integration"
      and .protocol.version == "1.1"
      and (.limits.structuredRecords.maxBatchItems | type == "number")
      and (.limits.structuredRecords.maxBatchItems >= 1)
      and (.limits.structuredRecords.maxBatchPayloadBytes | type == "number")
      and (.limits.structuredRecords.maxBatchPayloadBytes >= 1)
      and (.features.optional.integrationObservability | type == "boolean")
    ' "$response" >/dev/null; then
    fail_step "integration_capabilities" "CAPABILITY_CONTRACT_MISMATCH" "$HTTP_CODE"
    return 1
  fi

  CAPABILITY_PROTOCOL_VERSION="$(jq -er '.protocol.version' "$response")" || return 1
  VERIFIED_JSON_BATCH_ITEMS="$(
    jq -er '.limits.structuredRecords.maxBatchItems' "$response"
  )" || return 1
  VERIFIED_JSON_BATCH_PAYLOAD_BYTES="$(
    jq -er '.limits.structuredRecords.maxBatchPayloadBytes' "$response"
  )" || return 1
  VERIFIED_OPERATION_OBSERVABILITY="$(
    jq -r '.features.optional.integrationObservability' "$response"
  )" || return 1

  if [[ -n "$MIN_JSON_BATCH_ITEMS" ]] \
      && (( VERIFIED_JSON_BATCH_ITEMS < MIN_JSON_BATCH_ITEMS )); then
    fail_step "integration_capabilities" "CAPABILITY_LIMIT_TOO_LOW" "$HTTP_CODE"
    return 1
  fi
  if [[ -n "$MIN_JSON_BATCH_PAYLOAD_BYTES" ]] \
      && (( VERIFIED_JSON_BATCH_PAYLOAD_BYTES < MIN_JSON_BATCH_PAYLOAD_BYTES )); then
    fail_step "integration_capabilities" "CAPABILITY_LIMIT_TOO_LOW" "$HTTP_CODE"
    return 1
  fi
  if [[ "$REQUIRE_OPERATION_OBSERVABILITY" == "true" \
      && "$VERIFIED_OPERATION_OBSERVABILITY" != "true" ]]; then
    fail_step "integration_capabilities" "CAPABILITY_FEATURE_REQUIRED" "$HTTP_CODE"
    return 1
  fi
  pass_step "integration_capabilities" "$HTTP_CODE"
}

read_only_preflight() {
  local response headers index key

  response="${PRIVATE_DIR}/readiness.json"
  headers="${response}.headers"
  if ! http_request GET "${BASE_URL}/actuator/health/readiness" \
      "$NO_AUTH_CONFIG" "$response" "$headers" "$REQUEST_TIMEOUT_SECONDS"; then
    fail_step "service_readiness" "TRANSPORT_ERROR"
    return 1
  fi
  require_http "service_readiness" 200 || return 1
  if ! jq -e '.status == "UP"' "$response" >/dev/null; then
    fail_step "service_readiness" "SERVICE_NOT_READY" "$HTTP_CODE"
    return 1
  fi
  pass_step "service_readiness" "$HTTP_CODE"

  capability_preflight || return 1

  response="${PRIVATE_DIR}/openapi.json"
  headers="${response}.headers"
  if ! http_request GET "${BASE_URL}/v3/api-docs" \
      "$NO_AUTH_CONFIG" "$response" "$headers" "$REQUEST_TIMEOUT_SECONDS"; then
    fail_step "openapi_contract" "TRANSPORT_ERROR"
    return 1
  fi
  require_http "openapi_contract" 200 || return 1
  if ! jq -e '
      .info.version == "1.0.0"
      and .paths["/api/v1/rag/auth/me"].get != null
      and .paths["/api/v1/rag/collections/by-key"].get != null
      and .paths["/api/v1/rag/json-records/upsert"].post != null
      and .paths["/api/v1/rag/json-records/search"].post != null
      and .paths["/api/v1/rag/json-records/by-external-id"].get != null
      and .paths["/api/v1/rag/json-records/by-external-id"].delete != null
    ' "$response" >/dev/null; then
    fail_step "openapi_contract" "OPENAPI_CONTRACT_MISMATCH" "$HTTP_CODE"
    return 1
  fi
  API_VERSION="$(jq -er '.info.version' "$response")" || return 1
  pass_step "openapi_contract" "$HTTP_CODE"

  response="${PRIVATE_DIR}/identity.json"
  headers="${response}.headers"
  if ! http_request GET "${API}/auth/me" \
      "$AUTH_CONFIG" "$response" "$headers" "$REQUEST_TIMEOUT_SECONDS"; then
    fail_step "principal_identity" "TRANSPORT_ERROR"
    return 1
  fi
  require_http "principal_identity" 200 || return 1
  if ! tr -d '\r' < "$headers" | rg -qi '^cache-control:.*no-store'; then
    fail_step "principal_identity" "NO_STORE_REQUIRED" "$HTTP_CODE"
    return 1
  fi
  if ! jq -e --slurpfile validated "$VALIDATED_INPUT" '
      .principalType == "DATABASE_API_KEY"
      and .principalRole == "NORMAL"
      and .collectionAccessMode == "RESTRICTED"
      and (.allowedCollectionKeys | type == "array")
      and ((.allowedCollectionKeys | sort)
        == ($validated[0].expectedCollections | sort))
      and .capabilities == $validated[0].expectedCapabilities
      and (.credentialVersion | type == "number")
      and (.policyVersion | type == "number")
    ' "$response" >/dev/null; then
    fail_step "principal_identity" "POLICY_MISMATCH" "$HTTP_CODE"
    return 1
  fi
  if ! jq -e '
      [paths
       | map(tostring)
       | join(".")
       | ascii_downcase
       | select(test("rawkey|apikeyhash|credentialhash|secret"))]
      | length == 0
    ' "$response" >/dev/null; then
    fail_step "principal_identity" "SECRET_FIELD_EXPOSED" "$HTTP_CODE"
    return 1
  fi
  PRINCIPAL_TYPE="$(jq -er '.principalType' "$response")" || return 1
  PRINCIPAL_ROLE="$(jq -er '.principalRole' "$response")" || return 1
  ACCESS_MODE="$(jq -er '.collectionAccessMode' "$response")" || return 1
  CREDENTIAL_VERSION="$(jq -er '.credentialVersion' "$response")" || return 1
  POLICY_VERSION="$(jq -er '.policyVersion' "$response")" || return 1
  VERIFIED_CAPABILITY_PROFILE="$CAPABILITY_PROFILE"
  pass_step "principal_identity" "$HTTP_CODE"

  index=0
  while IFS= read -r key; do
    index=$((index + 1))
    response="${PRIVATE_DIR}/collection-${index}.json"
    headers="${response}.headers"
    if ! http_query GET "${API}/collections/by-key" \
        "$AUTH_CONFIG" "$response" "$headers" "$REQUEST_TIMEOUT_SECONDS" \
        "collectionKey=${key}"; then
      fail_step "collection_probes" "TRANSPORT_ERROR"
      return 1
    fi
    require_http "collection_probes" 200 || return 1
    if ! jq -e --arg key "$key" '
        .collectionKey == $key
        and .deleted == false
        and .enabled == true
      ' "$response" >/dev/null; then
      fail_step "collection_probes" "COLLECTION_NOT_ACTIVE" "$HTTP_CODE"
      return 1
    fi
  done < <(jq -r '.expectedCollections[]' "$VALIDATED_INPUT")
  pass_step "collection_probes" 200
}

write_upsert_request() {
  local output="$1" revision="$2" expected="$3" state="$4"
  local retrieval_text="Binding preflight ${RUN_ID} searchable record"
  if [[ -n "$CANARY_RETRIEVAL_MARKER" ]]; then
    retrieval_text="${retrieval_text} ${CANARY_RETRIEVAL_MARKER}"
  fi
  if [[ -n "$expected" ]]; then
    jq -n \
      --arg key "$CANARY_COLLECTION_KEY" \
      --arg namespace "$CANARY_NAMESPACE" \
      --arg externalId "$CANARY_EXTERNAL_ID" \
      --arg revision "$revision" \
      --arg expected "$expected" \
      --arg runId "$RUN_ID" \
      --arg state "$state" \
      --arg text "$retrieval_text" \
      '{
        collectionKey:$key,
        sourceNamespace:$namespace,
        externalId:$externalId,
        sourceRevision:$revision,
        expectedSourceRevision:$expected,
        title:"Binding Preflight",
        retrievalText:$text,
        jsonbPayload:{
          schemaVersion:"spring-ai-rag.binding-preflight.v1",
          preflightRunId:$runId,
          state:$state
        },
        embeddingPolicy:"ASYNC"
      }' > "$output"
  else
    jq -n \
      --arg key "$CANARY_COLLECTION_KEY" \
      --arg namespace "$CANARY_NAMESPACE" \
      --arg externalId "$CANARY_EXTERNAL_ID" \
      --arg revision "$revision" \
      --arg runId "$RUN_ID" \
      --arg state "$state" \
      --arg text "$retrieval_text" \
      '{
        collectionKey:$key,
        sourceNamespace:$namespace,
        externalId:$externalId,
        sourceRevision:$revision,
        title:"Binding Preflight",
        retrievalText:$text,
        jsonbPayload:{
          schemaVersion:"spring-ai-rag.binding-preflight.v1",
          preflightRunId:$runId,
          state:$state
        },
        embeddingPolicy:"ASYNC"
      }' > "$output"
  fi
}

lookup_canary() {
  local response="$1" headers="$2" timeout="$3"
  http_query GET "${API}/json-records/by-external-id" \
    "$AUTH_CONFIG" "$response" "$headers" "$timeout" \
    "collectionKey=${CANARY_COLLECTION_KEY}" \
    "sourceNamespace=${CANARY_NAMESPACE}" \
    "externalId=${CANARY_EXTERNAL_ID}"
}

wait_for_canary_ready() {
  local expected_revision="${1:-$REV_CREATED}"
  local deadline now remaining timeout response headers
  deadline=$(( $(date +%s) + READY_TIMEOUT_SECONDS ))
  response="${PRIVATE_DIR}/canary-ready.json"
  headers="${response}.headers"
  while true; do
    now="$(date +%s)"
    remaining=$(( deadline - now ))
    if (( remaining <= 0 )); then
      fail_step "canary_readiness" "EMBEDDING_TIMEOUT"
      return 1
    fi
    timeout="$REQUEST_TIMEOUT_SECONDS"
    if (( timeout > remaining )); then
      timeout="$remaining"
    fi
    if ! lookup_canary "$response" "$headers" "$timeout"; then
      fail_step "canary_readiness" "TRANSPORT_ERROR"
      return 1
    fi
    require_http "canary_readiness" 200 || return 1
    if ! jq -e \
        --argjson documentId "$DOCUMENT_ID" \
        --arg revision "$expected_revision" \
        '.documentId == $documentId
          and .sourceRevision == $revision
          and .enabled == true
          and .lifecycle.documentState == "ACTIVE"' \
        "$response" >/dev/null; then
      fail_step "canary_readiness" "CANARY_STATE_MISMATCH" "$HTTP_CODE"
      return 1
    fi
    if jq -e '.lifecycle.searchability == "READY"' "$response" >/dev/null; then
      pass_step "canary_readiness" "$HTTP_CODE"
      return 0
    fi
    if jq -e '
        .lifecycle.embeddingStatus == "FAILED"
        or .lifecycle.searchability == "FAILED"
        or .lifecycle.documentState == "TOMBSTONED"
      ' "$response" >/dev/null; then
      fail_step "canary_readiness" "EMBEDDING_FAILED" "$HTTP_CODE"
      return 1
    fi
    sleep 1
  done
}

delete_canary() {
  local revision="$1" expected="$2" response="$3" headers="$4"
  http_query DELETE "${API}/json-records/by-external-id" \
    "$AUTH_CONFIG" "$response" "$headers" "$REQUEST_TIMEOUT_SECONDS" \
    "collectionKey=${CANARY_COLLECTION_KEY}" \
    "sourceNamespace=${CANARY_NAMESPACE}" \
    "externalId=${CANARY_EXTERNAL_ID}" \
    "sourceRevision=${revision}" \
    "expectedSourceRevision=${expected}"
}

canary_mutation_preflight() {
  local request response headers updated_document_revision

  CANARY_EXTERNAL_ID="preflight-${RUN_ID}"
  REV_CREATED="${RUN_ID}-created"
  REV_UPDATED="${RUN_ID}-updated"
  REV_TOMBSTONED="${RUN_ID}-tombstoned"
  REV_RESTORED="${RUN_ID}-restored"
  REV_CLEANUP="${RUN_ID}-cleanup"
  REV_TRAP_CLEANUP="${RUN_ID}-trap-cleanup"

  request="${PRIVATE_DIR}/canary-created.request.json"
  response="${PRIVATE_DIR}/canary-created.json"
  headers="${response}.headers"
  write_upsert_request "$request" "$REV_CREATED" "" "created" || {
    fail_step "canary_create" "REQUEST_BUILD_FAILED"
    return 1
  }
  CANARY_MAY_EXIST=true
  if ! http_request POST "${API}/json-records/upsert" \
      "$AUTH_CONFIG" "$response" "$headers" "$REQUEST_TIMEOUT_SECONDS" "$request"; then
    fail_step "canary_create" "TRANSPORT_ERROR"
    return 1
  fi
  require_http "canary_create" 200 || return 1
  if ! jq -e --arg revision "$REV_CREATED" '
      .action == "CREATED"
      and .sourceRevision == $revision
      and .documentId != null
      and (.documentRevision | type == "number")
      and .lifecycle.documentState == "ACTIVE"
      and (.embeddingAction == "ASYNC_QUEUED"
        or .embeddingAction == "ASYNC_COALESCED")
    ' "$response" >/dev/null; then
    fail_step "canary_create" "CANARY_CREATE_MISMATCH" "$HTTP_CODE"
    return 1
  fi
  DOCUMENT_ID="$(jq -er '.documentId' "$response")" || return 1
  INITIAL_DOCUMENT_REVISION="$(jq -er '.documentRevision' "$response")" || return 1
  pass_step "canary_create" "$HTTP_CODE"

  response="${PRIVATE_DIR}/canary-replay.json"
  headers="${response}.headers"
  if ! http_request POST "${API}/json-records/upsert" \
      "$AUTH_CONFIG" "$response" "$headers" "$REQUEST_TIMEOUT_SECONDS" "$request"; then
    fail_step "canary_replay" "TRANSPORT_ERROR"
    return 1
  fi
  require_http "canary_replay" 200 || return 1
  if ! jq -e \
      --argjson documentId "$DOCUMENT_ID" \
      --argjson documentRevision "$INITIAL_DOCUMENT_REVISION" \
      '.documentId == $documentId
        and .documentRevision == $documentRevision
        and (.action == "REPLAYED" or .action == "UNCHANGED")' \
      "$response" >/dev/null; then
    fail_step "canary_replay" "CANARY_REPLAY_MISMATCH" "$HTTP_CODE"
    return 1
  fi
  pass_step "canary_replay" "$HTTP_CODE"

  wait_for_canary_ready || return 1

  request="${PRIVATE_DIR}/canary-search.request.json"
  jq -n \
    --arg query "Binding preflight ${RUN_ID} searchable record" \
    --arg key "$CANARY_COLLECTION_KEY" \
    --arg runId "$RUN_ID" \
    '{
      query:$query,
      collectionKeys:[$key],
      payloadContains:{preflightRunId:$runId},
      config:{
        maxResults:10,
        minScore:0,
        useHybridSearch:true,
        useRerank:false
      }
    }' > "$request"
  response="${PRIVATE_DIR}/canary-search.json"
  headers="${response}.headers"
  if ! http_request POST "${API}/json-records/search" \
      "$AUTH_CONFIG" "$response" "$headers" "$REQUEST_TIMEOUT_SECONDS" "$request"; then
    fail_step "canary_search" "TRANSPORT_ERROR"
    return 1
  fi
  require_http "canary_search" 200 || return 1
  if ! jq -e \
      --argjson documentId "$DOCUMENT_ID" \
      --arg runId "$RUN_ID" \
      'any(.results[];
        .documentId == $documentId
        and .jsonbPayload.preflightRunId == $runId)' \
      "$response" >/dev/null; then
    fail_step "canary_search" "CANARY_SEARCH_MISMATCH" "$HTTP_CODE"
    return 1
  fi
  pass_step "canary_search" "$HTTP_CODE"

  request="${PRIVATE_DIR}/canary-updated.request.json"
  response="${PRIVATE_DIR}/canary-updated.json"
  headers="${response}.headers"
  write_upsert_request "$request" "$REV_UPDATED" "$REV_CREATED" "updated"
  if ! http_request POST "${API}/json-records/upsert" \
      "$AUTH_CONFIG" "$response" "$headers" "$REQUEST_TIMEOUT_SECONDS" "$request"; then
    fail_step "canary_update" "TRANSPORT_ERROR"
    return 1
  fi
  require_http "canary_update" 200 || return 1
  if ! jq -e \
      --argjson documentId "$DOCUMENT_ID" \
      --arg revision "$REV_UPDATED" \
      '.documentId == $documentId
        and .sourceRevision == $revision
        and .action == "UPDATED"
        and .lifecycle.documentState == "ACTIVE"' \
      "$response" >/dev/null; then
    fail_step "canary_update" "CANARY_UPDATE_MISMATCH" "$HTTP_CODE"
    return 1
  fi
  updated_document_revision="$(jq -er '.documentRevision' "$response")" || return 1
  pass_step "canary_update" "$HTTP_CODE"

  request="${PRIVATE_DIR}/canary-stale.request.json"
  response="${PRIVATE_DIR}/canary-stale.json"
  headers="${response}.headers"
  write_upsert_request "$request" "${RUN_ID}-stale" "$REV_CREATED" "stale"
  if ! http_request POST "${API}/json-records/upsert" \
      "$AUTH_CONFIG" "$response" "$headers" "$REQUEST_TIMEOUT_SECONDS" "$request"; then
    fail_step "canary_stale_cas" "TRANSPORT_ERROR"
    return 1
  fi
  require_http "canary_stale_cas" 409 || return 1
  response="${PRIVATE_DIR}/canary-after-stale.json"
  headers="${response}.headers"
  if ! lookup_canary "$response" "$headers" "$REQUEST_TIMEOUT_SECONDS"; then
    fail_step "canary_stale_cas" "TRANSPORT_ERROR"
    return 1
  fi
  require_http "canary_stale_cas" 200 || return 1
  if ! jq -e \
      --argjson documentId "$DOCUMENT_ID" \
      --argjson documentRevision "$updated_document_revision" \
      --arg revision "$REV_UPDATED" \
      '.documentId == $documentId
        and .documentRevision == $documentRevision
        and .sourceRevision == $revision
        and .enabled == true' \
      "$response" >/dev/null; then
    fail_step "canary_stale_cas" "CAS_STATE_CHANGED" "$HTTP_CODE"
    return 1
  fi
  pass_step "canary_stale_cas" 409

  response="${PRIVATE_DIR}/canary-tombstone.json"
  headers="${response}.headers"
  if ! delete_canary "$REV_TOMBSTONED" "$REV_UPDATED" "$response" "$headers"; then
    fail_step "canary_tombstone" "TRANSPORT_ERROR"
    return 1
  fi
  require_http "canary_tombstone" 200 || return 1
  if ! jq -e \
      --argjson documentId "$DOCUMENT_ID" \
      --arg revision "$REV_TOMBSTONED" \
      '.documentId == $documentId
        and .sourceRevision == $revision
        and .enabled == false
        and .lifecycle.documentState == "TOMBSTONED"' \
      "$response" >/dev/null; then
    fail_step "canary_tombstone" "TOMBSTONE_MISMATCH" "$HTTP_CODE"
    return 1
  fi
  pass_step "canary_tombstone" "$HTTP_CODE"

  request="${PRIVATE_DIR}/canary-restored.request.json"
  response="${PRIVATE_DIR}/canary-restored.json"
  headers="${response}.headers"
  write_upsert_request \
    "$request" "$REV_RESTORED" "$REV_TOMBSTONED" "restored"
  if ! http_request POST "${API}/json-records/upsert" \
      "$AUTH_CONFIG" "$response" "$headers" "$REQUEST_TIMEOUT_SECONDS" "$request"; then
    fail_step "canary_restore" "TRANSPORT_ERROR"
    return 1
  fi
  require_http "canary_restore" 200 || return 1
    if ! jq -e \
      --argjson documentId "$DOCUMENT_ID" \
      --arg revision "$REV_RESTORED" \
      '.documentId == $documentId
        and .sourceRevision == $revision
        and .lifecycle.documentState == "ACTIVE"' \
      "$response" >/dev/null; then
    fail_step "canary_restore" "RESTORE_MISMATCH" "$HTTP_CODE"
    return 1
  fi
  pass_step "canary_restore" "$HTTP_CODE"
  wait_for_canary_ready "$REV_RESTORED" || return 1

  response="${PRIVATE_DIR}/canary-cleanup.json"
  headers="${response}.headers"
  if ! delete_canary "$REV_CLEANUP" "$REV_RESTORED" "$response" "$headers"; then
    fail_step "canary_final_cleanup" "TRANSPORT_ERROR"
    return 1
  fi
  require_http "canary_final_cleanup" 200 || return 1
  response="${PRIVATE_DIR}/canary-final.json"
  headers="${response}.headers"
  if ! lookup_canary "$response" "$headers" "$REQUEST_TIMEOUT_SECONDS"; then
    fail_step "canary_final_cleanup" "TRANSPORT_ERROR"
    return 1
  fi
  require_http "canary_final_cleanup" 200 || return 1
  if ! jq -e \
      --argjson documentId "$DOCUMENT_ID" \
      --arg revision "$REV_CLEANUP" \
      '.documentId == $documentId
        and .sourceRevision == $revision
        and .enabled == false
        and .lifecycle.documentState == "TOMBSTONED"' \
      "$response" >/dev/null; then
    fail_step "canary_final_cleanup" "FINAL_TOMBSTONE_MISMATCH" "$HTTP_CODE"
    return 1
  fi
  CANARY_FINAL_STATE="TOMBSTONED"
  pass_step "canary_final_cleanup" "$HTTP_CODE"
}

best_effort_canary_cleanup() {
  local response headers current_revision
  [[ "$CANARY_MAY_EXIST" == "true" ]] || return 0
  [[ -n "$AUTH_CONFIG" && -f "$AUTH_CONFIG" ]] || {
    CANARY_FINAL_STATE="UNKNOWN"
    return 0
  }
  response="${PRIVATE_DIR}/trap-lookup.json"
  headers="${response}.headers"
  if ! lookup_canary "$response" "$headers" 10; then
    CANARY_FINAL_STATE="UNKNOWN"
    record_step "canary_exit_cleanup" "FAIL" "" "TRANSPORT_ERROR"
    return 0
  fi
  if [[ "$HTTP_CODE" == "404" ]]; then
    CANARY_FINAL_STATE="ABSENT"
    record_step "canary_exit_cleanup" "PASS" 404 ""
    return 0
  fi
  if [[ "$HTTP_CODE" != "200" ]]; then
    CANARY_FINAL_STATE="UNKNOWN"
    record_step "canary_exit_cleanup" "FAIL" "$HTTP_CODE" \
      "UNEXPECTED_HTTP_STATUS"
    return 0
  fi
  if jq -e '.enabled == false and .lifecycle.documentState == "TOMBSTONED"' \
      "$response" >/dev/null; then
    CANARY_FINAL_STATE="TOMBSTONED"
    record_step "canary_exit_cleanup" "PASS" 200 ""
    return 0
  fi
  current_revision="$(jq -er '.sourceRevision' "$response" 2>/dev/null || true)"
  if [[ -z "$current_revision" ]]; then
    CANARY_FINAL_STATE="UNKNOWN"
    record_step "canary_exit_cleanup" "FAIL" 200 "MISSING_SOURCE_REVISION"
    return 0
  fi
  response="${PRIVATE_DIR}/trap-tombstone.json"
  headers="${response}.headers"
  if ! delete_canary \
      "$REV_TRAP_CLEANUP" "$current_revision" "$response" "$headers"; then
    CANARY_FINAL_STATE="UNKNOWN"
    record_step "canary_exit_cleanup" "FAIL" "" "TRANSPORT_ERROR"
    return 0
  fi
  if [[ "$HTTP_CODE" != "200" ]] \
      || ! jq -e '.enabled == false
        and .lifecycle.documentState == "TOMBSTONED"' \
        "$response" >/dev/null; then
    CANARY_FINAL_STATE="UNKNOWN"
    record_step "canary_exit_cleanup" "FAIL" "$HTTP_CODE" \
      "CLEANUP_TOMBSTONE_FAILED"
    return 0
  fi
  CANARY_FINAL_STATE="TOMBSTONED"
  record_step "canary_exit_cleanup" "PASS" 200 ""
}

on_exit() {
  local exit_code=$?
  trap - EXIT
  if [[ "$exit_code" -ne 0 && "$CANARY_FINAL_STATE" != "TOMBSTONED" ]]; then
    best_effort_canary_cleanup
  fi
  if [[ -n "$EVIDENCE_DIR" && -d "$EVIDENCE_DIR" ]]; then
    if ! write_report "$exit_code"; then
      printf 'Failed to write preflight report\n' >&2
      exit_code=1
    fi
    printf 'Report: %s/preflight-report.json\n' "${EVIDENCE_DIR%/}"
  fi
  if [[ -n "$PRIVATE_DIR" && -d "$PRIVATE_DIR" ]]; then
    rm -rf "$PRIVATE_DIR"
  fi
  exit "$exit_code"
}

main() {
  if [[ "$#" -ne 0 ]]; then
    FAILED_STEP="input"
    FAILURE_CATEGORY="UNEXPECTED_ARGUMENTS"
  fi
  bootstrap_evidence || {
    printf 'Could not create preflight evidence directory\n' >&2
    return 2
  }
  trap on_exit EXIT
  trap 'exit 130' INT TERM

  validate_inputs || return 1
  read_only_preflight || return 1
  if [[ "$MODE" == "CANARY_MUTATION" ]]; then
    canary_mutation_preflight || return 1
  fi
  return 0
}

main "$@"
