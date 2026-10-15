#!/usr/bin/env bash
# Validate the versioned external Client API contract and optional live smoke paths.
set -euo pipefail

cd "$(dirname "$0")/.."

BASE_URL="${API_CONTRACT_BASE_URL:-${BASE_URL:-http://127.0.0.1:8081}}"
MANIFEST="${API_CONTRACT_MANIFEST:-testdata/api-contract/v1-compatibility.json}"
SPEC_FILE="${API_CONTRACT_SPEC_FILE:-}"
API_KEY="${API_CONTRACT_API_KEY:-}"
RUN_LIVE="${API_CONTRACT_RUN_LIVE:-0}"
QUERY="${API_CONTRACT_QUERY:-}"
RAG_MODE="${API_CONTRACT_RAG_MODE:-}"
RUN_ID="${API_CONTRACT_RUN_ID:-$(date +%Y%m%d-%H%M%S)}"
LOG_DIR="${API_CONTRACT_LOG_DIR:-.verification/api-contract/${RUN_ID}}"

mkdir -p "$LOG_DIR"

for command_name in curl node; do
  command -v "$command_name" >/dev/null || {
    echo "Missing required command: $command_name" >&2
    exit 2
  }
done

[[ -f "$MANIFEST" ]] || {
  echo "Missing API contract manifest: $MANIFEST" >&2
  exit 2
}

node scripts/lib/api-contract-check.mjs --self-test >"$LOG_DIR/helper-self-test.json"

curl_args=(--silent --show-error --location --max-time "${API_CONTRACT_TIMEOUT_SECONDS:-15}")
if [[ -n "$API_KEY" ]]; then
  curl_args+=(--header "X-API-Key: ${API_KEY}")
fi

if [[ -z "$SPEC_FILE" ]]; then
  SPEC_FILE="$LOG_DIR/openapi.json"
  curl "${curl_args[@]}" "${BASE_URL%/}/v3/api-docs" --output "$SPEC_FILE"
fi

[[ -s "$SPEC_FILE" ]] || {
  echo "OpenAPI spec is empty: $SPEC_FILE" >&2
  exit 1
}

node scripts/lib/api-contract-check.mjs \
  --spec "$SPEC_FILE" \
  --manifest "$MANIFEST" \
  | tee "$LOG_DIR/static-contract.json"

request() {
  local method="$1" url="$2" body_file="$3" header_file="$4" response_file="$5"
  local args=("${curl_args[@]}" --request "$method" --dump-header "$header_file" --output "$response_file" --write-out '%{http_code}')
  if [[ -n "$body_file" ]]; then
    args+=(--header 'Content-Type: application/json' --data-binary "@$body_file")
  fi
  curl "${args[@]}" "$url"
}

check_error_shape() {
  local response_file="$1"
  node - "$response_file" <<'NODE'
const fs = require('node:fs');
const file = process.argv[2];
const text = fs.readFileSync(file, 'utf8').trim();
if (!text) process.exit(0);
let value;
try { value = JSON.parse(text); } catch { process.exit(0); }
if (Array.isArray(value) || value === null || typeof value !== 'object') {
  throw new Error('error response is not a JSON object');
}
const hasMachineCode = typeof value.error === 'string' || typeof value.code === 'string';
const hasProblemShape = typeof value.title === 'string' || typeof value.detail === 'string'
  || typeof value.message === 'string';
if (!hasMachineCode && !hasProblemShape) {
  throw new Error('error response has neither machine code nor problem detail');
}
NODE
}

check_trace_header() {
  local header_file="$1"
  if ! rg -i '^x-trace-id:[[:space:]]*[^[:space:]]+' "$header_file" >/dev/null; then
    echo "Missing X-Trace-Id response header" >&2
    return 1
  fi
}

check_failure_class_header() {
  local header_file="$1"
  if ! rg -i '^x-rag-failure-class:[[:space:]]*[^[:space:]]+' "$header_file" >/dev/null; then
    echo "Missing X-RAG-Failure-Class response header" >&2
    return 1
  fi
}

allowed_error_probe() {
  local label="$1" method="$2" url="$3" body_file="$4"
  local header_file="$LOG_DIR/${label}.headers"
  local response_file="$LOG_DIR/${label}.body"
  local status
  status="$(request "$method" "$url" "$body_file" "$header_file" "$response_file")"
  case "$status" in
    400|401|403|404|409|422) ;;
    *)
      echo "${label}: unexpected status ${status}" >&2
      return 1
      ;;
  esac
  check_error_shape "$response_file"
  check_trace_header "$header_file"
  check_failure_class_header "$header_file"
  echo "${label}: ${status}"
}

invalid_chat="$LOG_DIR/invalid-chat.json"
invalid_search="$LOG_DIR/invalid-search.json"
printf '%s' '{"message":""}' >"$invalid_chat"
printf '%s' '{"query":""}' >"$invalid_search"

allowed_error_probe chat-invalid POST "${BASE_URL%/}/api/v1/rag/chat/ask" "$invalid_chat"
allowed_error_probe chat-alias-invalid POST "${BASE_URL%/}/api/v1/rag/chat" "$invalid_chat"
allowed_error_probe chat-stream-invalid POST "${BASE_URL%/}/api/v1/rag/chat/stream" "$invalid_chat"
allowed_error_probe search-invalid POST "${BASE_URL%/}/api/v1/rag/search" "$invalid_search"

trace_headers="$LOG_DIR/retrieval-traces.headers"
trace_body="$LOG_DIR/retrieval-traces.body"
trace_status="$(request GET "${BASE_URL%/}/api/v1/rag/retrieval-traces?page=0&size=1&requestTraceId=contract-probe" '' "$trace_headers" "$trace_body")"
case "$trace_status" in
  200|401|403) ;;
  *) echo "retrieval-traces: unexpected status ${trace_status}" >&2; exit 1 ;;
esac
if [[ "$trace_status" == "200" ]]; then
  node - "$trace_body" <<'NODE'
const fs = require('node:fs');
const value = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
if (!value || !Array.isArray(value.items)) throw new Error('retrieval trace page missing items');
NODE
fi
check_trace_header "$trace_headers"
echo "retrieval-traces: ${trace_status}"

policy_headers="$LOG_DIR/data-governance-policy.headers"
policy_body="$LOG_DIR/data-governance-policy.body"
policy_status="$(request GET "${BASE_URL%/}/api/v1/rag/data-governance/policy" '' "$policy_headers" "$policy_body")"
case "$policy_status" in
  200|401|403) ;;
  *) echo "data-governance-policy: unexpected status ${policy_status}" >&2; exit 1 ;;
esac
if [[ "$policy_status" == "200" ]]; then
  node - "$policy_body" <<'NODE'
const fs = require('node:fs');
const value = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
if (!value || typeof value.policyVersion !== 'string'
    || typeof value.redactionVersion !== 'string'
    || !Array.isArray(value.retention)
    || !value.capabilities || !value.cleanup) {
  throw new Error('data governance policy response shape is invalid');
}
NODE
fi
check_trace_header "$policy_headers"
echo "data-governance-policy: ${policy_status}"

audit_headers="$LOG_DIR/audit-logs.headers"
audit_body="$LOG_DIR/audit-logs.body"
audit_status="$(request GET "${BASE_URL%/}/api/v1/rag/audit-logs?page=0&size=1" '' "$audit_headers" "$audit_body")"
case "$audit_status" in
  200|401|403) ;;
  *) echo "audit-logs: unexpected status ${audit_status}" >&2; exit 1 ;;
esac
if [[ "$audit_status" == "200" ]]; then
  node - "$audit_body" <<'NODE'
const fs = require('node:fs');
const value = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
if (!value || !Array.isArray(value.items)) throw new Error('audit page missing items');
for (const item of value.items) {
  if (Object.prototype.hasOwnProperty.call(item, 'details')) {
    throw new Error('audit response exposed persisted details');
  }
}
NODE
fi
check_trace_header "$audit_headers"
echo "audit-logs: ${audit_status}"

if [[ "$RUN_LIVE" == "1" ]]; then
  [[ -n "$API_KEY" ]] || { echo 'API_CONTRACT_API_KEY is required for live mode' >&2; exit 2; }
  [[ -n "$QUERY" ]] || { echo 'API_CONTRACT_QUERY is required for live mode' >&2; exit 2; }

  encoded_query="$(node -e 'console.log(encodeURIComponent(process.argv[1]))' "$QUERY")"
  live_search_headers="$LOG_DIR/live-search.headers"
  live_search_body="$LOG_DIR/live-search.body"
  live_search_status="$(request GET "${BASE_URL%/}/api/v1/rag/search?query=${encoded_query}&limit=3" '' "$live_search_headers" "$live_search_body")"
  [[ "$live_search_status" == "200" ]] || { echo "live search failed: ${live_search_status}" >&2; exit 1; }
  node - "$live_search_body" <<'NODE'
const fs = require('node:fs');
const value = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
if (!value || !Array.isArray(value.results) || typeof value.total !== 'number') {
  throw new Error('live SearchResponse shape is invalid');
}
NODE
  check_trace_header "$live_search_headers"

  live_chat="$LOG_DIR/live-chat.json"
  node - "${API_CONTRACT_MODEL:-default}" "$QUERY" "$RAG_MODE" >"$live_chat" <<'NODE'
const model = process.argv[2];
const query = process.argv[3];
const ragMode = process.argv[4];
const request = {
  model,
  messages: [{ role: 'user', content: query }]
};
if (ragMode) {
  request.rag = { mode: ragMode, memory: 'STATELESS' };
}
process.stdout.write(JSON.stringify(request));
NODE
  live_chat_headers="$LOG_DIR/live-chat.headers"
  live_chat_body="$LOG_DIR/live-chat.body"
  live_chat_status="$(request POST "${BASE_URL%/}/v1/chat/completions" "$live_chat" "$live_chat_headers" "$live_chat_body")"
  [[ "$live_chat_status" == "200" ]] || { echo "live OpenAI Chat failed: ${live_chat_status}" >&2; exit 1; }
  node - "$live_chat_body" <<'NODE'
const fs = require('node:fs');
const value = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
for (const key of ['id', 'object', 'created', 'model', 'choices']) {
  if (!(key in value)) throw new Error(`OpenAI response missing ${key}`);
}
NODE
  check_trace_header "$live_chat_headers"
  echo "live Search/OpenAI smoke: 200"
fi

echo "API compatibility verification passed. Summary: ${LOG_DIR}"
