#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://127.0.0.1:8081}"
API="${BASE_URL%/}/api/v1/rag"
API_KEY="${RAG_ROOT_API_KEY:-${RAG_API_KEY:-}}"
OTHER_ADMIN_API_KEY="${OTHER_ADMIN_API_KEY:-}"
[[ -n "$API_KEY" ]] || { echo "RAG_ROOT_API_KEY is required." >&2; exit 2; }
for command_name in curl jq; do
  command -v "$command_name" >/dev/null || {
    echo "Missing required command: ${command_name}" >&2
    exit 2
  }
done

HEADERS_FILE="$(mktemp "${TMPDIR:-/tmp}/rag-purge-http.XXXXXX")"
chmod 600 "$HEADERS_FILE"
trap 'rm -f "$HEADERS_FILE"' EXIT

request() {
  local method="$1" url="$2" expected="$3" body="${4:-}"
  local key="${5:-$API_KEY}" idempotency_key="${6:-}"
  local response
  local -a args=(--silent --show-error --connect-timeout 5 --max-time 30
    --request "$method" --header "X-API-Key: $key"
    --dump-header "$HEADERS_FILE" --write-out $'\n%{http_code}')
  [[ -n "$idempotency_key" ]] && args+=(--header "Idempotency-Key: $idempotency_key")
  [[ -n "$body" ]] && args+=(--header 'Content-Type: application/json' --data "$body")
  response="$(curl "${args[@]}" "$url")"
  HTTP_STATUS="${response##*$'\n'}"
  HTTP_BODY="${response%$'\n'"$HTTP_STATUS"}"
  if [[ "$HTTP_STATUS" != "$expected" ]]; then
    echo "Unexpected HTTP status: ${method} ${url}, got ${HTTP_STATUS}, expected ${expected}." >&2
    return 1
  fi
}

pass() { printf 'PASS: %s\n' "$1"; }

request GET "$BASE_URL/actuator/health" 200
printf '%s' "$HTTP_BODY" | jq -e '.status == "UP"' >/dev/null
pass "running service health"

TOKEN="PURGE_HTTP_$(date +%s)_${RANDOM}"
COLLECTION_KEY="purge-http-${TOKEN}"
COLLECTION_KEY_ENCODED="$(jq -rn --arg value "$COLLECTION_KEY" '$value|@uri')"
IDEMPOTENCY_KEY="${TOKEN}-operation"
create_body="$(jq -nc --arg key "$COLLECTION_KEY" \
  '{collectionKey: $key, name: "Async purge HTTP smoke", enabled: true}')"
request POST "$API/collections" 201 "$create_body" "$API_KEY" "${TOKEN}-collection"
request POST "$API/collections/by-key/purge/async/preview?collectionKey=$COLLECTION_KEY_ENCODED" 200
preview="$HTTP_BODY"
printf '%s' "$preview" | jq -e '
  .status == "PREVIEWED" and .documentCount == 0
  and (.previewId | type == "string")
  and (.confirmationToken | type == "string")
  and (.fingerprint | type == "string")' >/dev/null
apply_body="$(printf '%s' "$preview" | jq -c '
  {collectionKey, previewId, confirmationToken, fingerprint,
   expectedCollectionVersion: .collectionVersion,
   expectedChatCommitFenceVersion: .chatCommitFenceVersion}')"
request POST "$API/collections/by-key/purge" 409 "$apply_body"
printf '%s' "$HTTP_BODY" | jq -e \
  '.error == "COLLECTION_PURGE_ASYNC_PREVIEW_REQUIRES_ASYNC_APPLY" and .status == 409' >/dev/null
pass "legacy synchronous apply rejects async previews"

request POST "$API/collections/by-key/purge/async" 202 "$apply_body" "$API_KEY" "$IDEMPOTENCY_KEY"
receipt="$HTTP_BODY"
operation_id="$(printf '%s' "$receipt" | jq -er '.operationId')"
status_path="$(printf '%s' "$receipt" | jq -er '.statusUrl')"
result_path="$(printf '%s' "$receipt" | jq -er '.resultUrl')"
grep -Eiq '^Location:[[:space:]]*[^[:space:]]' "$HEADERS_FILE"
grep -Eiq '^Retry-After:[[:space:]]*[1-9][0-9]*' "$HEADERS_FILE"
request POST "$API/collections/by-key/purge/async" 202 "$apply_body" "$API_KEY" "$IDEMPOTENCY_KEY"
jq -en --argjson first "$receipt" --argjson replay "$HTTP_BODY" \
  '$first.operationId == $replay.operationId and $first.statusUrl == $replay.statusUrl' >/dev/null
pass "202 receipt headers and idempotent replay"

conflict_body="$(printf '%s' "$apply_body" | jq -c '.expectedCollectionVersion += 1')"
request POST "$API/collections/by-key/purge/async" 409 "$conflict_body" "$API_KEY" "$IDEMPOTENCY_KEY"
printf '%s' "$HTTP_BODY" | jq -e '.error == "IDEMPOTENCY_KEY_REUSED" and .status == 409' >/dev/null
request GET "$API/operations/00000000-0000-0000-0000-000000000000" 404
if [[ -n "$OTHER_ADMIN_API_KEY" ]]; then
  request GET "${BASE_URL%/}${status_path}" 404 "$OTHER_ADMIN_API_KEY"
  pass "foreign owner cannot read the operation"
fi

for ((attempt = 0; attempt < 60; attempt++)); do
  request GET "${BASE_URL%/}${status_path}" 200
  status="$(printf '%s' "$HTTP_BODY" | jq -r '.status')"
  case "$status" in
    SUCCEEDED) break ;;
    QUEUED|RUNNING|RETRY_WAIT) sleep 1 ;;
    *) echo "Async purge entered unexpected status: ${status}." >&2; exit 1 ;;
  esac
done
[[ "${status:-}" == SUCCEEDED ]] || { echo "Async purge timed out." >&2; exit 1; }
request GET "${BASE_URL%/}${result_path}" 200
printf '%s' "$HTTP_BODY" | jq -e \
  '.status == "RETIRED" and .purgedDocumentCount == 0 and .purgedAt != null' >/dev/null
pass "worker completion and durable result retrieval"
printf 'Async Collection Purge HTTP smoke passed for operation %s.\n' "$operation_id"
