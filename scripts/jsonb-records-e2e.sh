#!/usr/bin/env bash
# =============================================================================
# Live HTTP E2E for JSONB structured records.
#
# Prerequisites:
#   - A running PostgreSQL-profile backend
#   - A working embedding provider (embed=true is exercised)
#   - curl and python3
#   - For ACL checks: RAG_ROOT_API_KEY or E2E_ADMIN_API_KEY
#
# Usage:
#   BASE_URL=http://127.0.0.1:18081 \
#   RAG_API_KEY="$RAG_ROOT_API_KEY" \
#   ./scripts/jsonb-records-e2e.sh
#
# Use --skip-acl only against a server where API-key management is intentionally
# unavailable, for example a local server started with RAG_SECURITY_ENABLED=false.
# The script never prints API keys or full JSON payloads.
# =============================================================================
set -euo pipefail

cd "$(dirname "$0")/.."

BASE_URL="${BASE_URL:-http://127.0.0.1:18081}"
API="${BASE_URL}/api/v1/rag"
RUN_ID="${JSONB_E2E_RUN_ID:-$(date +%Y%m%d-%H%M%S)-$$}"
LOG_DIR="${JSONB_E2E_LOG_DIR:-.verification/jsonb-e2e/${RUN_ID}}"
REQUEST_TIMEOUT_SECONDS="${JSONB_E2E_TIMEOUT_SECONDS:-120}"
SKIP_ACL=0

for arg in "$@"; do
  case "$arg" in
    --skip-acl)
      SKIP_ACL=1
      ;;
    -h|--help)
      sed -n '1,35p' "$0"
      exit 0
      ;;
    *)
      echo "Unknown option: $arg" >&2
      exit 2
      ;;
  esac
done

mkdir -p "$LOG_DIR"

# Preserve explicit caller overrides while loading the repository .env.
PRESERVE_RAG_API_KEY="${RAG_API_KEY-}"
PRESERVE_ADMIN_KEY="${E2E_ADMIN_API_KEY-}"
if [[ -f .env ]]; then
  # shellcheck disable=SC2046
  export $(grep -v '^#' .env | grep -v '^$' | xargs) || true
fi
[[ -n "$PRESERVE_RAG_API_KEY" ]] && export RAG_API_KEY="$PRESERVE_RAG_API_KEY"
[[ -n "$PRESERVE_ADMIN_KEY" ]] && export E2E_ADMIN_API_KEY="$PRESERVE_ADMIN_KEY"

OPERATOR_KEY="${RAG_API_KEY:-${E2E_ADMIN_API_KEY:-${RAG_ROOT_API_KEY:-}}}"
ADMIN_KEY="${E2E_ADMIN_API_KEY:-${RAG_ROOT_API_KEY:-$OPERATOR_KEY}}"

SOURCE_KEY="jsonb-e2e-${RUN_ID}"
CLONE_KEY="jsonb-e2e-clone-${RUN_ID}"
IMPORT_KEY="jsonb-e2e-import-${RUN_ID}"
TOKEN="JSONB_E2E_${RUN_ID}"
SOURCE_ID=""
CLONE_ID=""
IMPORT_ID=""
RESTRICTED_KEY_ID=""
RESTRICTED_RAW_KEY=""
PASS_COUNT=0
FAIL_COUNT=0

request() {
  local method="$1"
  local url="$2"
  local output="$3"
  local key="$4"
  local body="${5-}"
  local args=(
    -sS
    --connect-timeout 5
    --max-time "$REQUEST_TIMEOUT_SECONDS"
    -o "$output"
    -w "%{http_code}"
    -X "$method"
    -H "Accept: application/json"
  )
  if [[ -n "$key" ]]; then
    args+=( -H "X-API-Key: $key" )
  fi
  if [[ -n "$body" ]]; then
    args+=( -H "Content-Type: application/json" -d "$body" )
  fi
  curl "${args[@]}" "$url"
}

pass() {
  PASS_COUNT=$((PASS_COUNT + 1))
  echo "PASS: $*"
}

fail() {
  FAIL_COUNT=$((FAIL_COUNT + 1))
  echo "FAIL: $*" >&2
}

require_status() {
  local description="$1"
  local actual="$2"
  local expected="$3"
  if [[ "$actual" != "$expected" ]]; then
    fail "${description}: expected HTTP ${expected}, got ${actual}"
    return 1
  fi
  pass "${description} (HTTP ${actual})"
}

require_file_json() {
  local description="$1"
  local file="$2"
  local code="$3"
  if [[ "$code" != "2"* ]]; then
    fail "${description}: response was not successful (HTTP ${code})"
    return 1
  fi
  if ! python3 - "$file" >/dev/null <<'PY'
import json
import sys
with open(sys.argv[1], encoding="utf-8") as handle:
    json.load(handle)
PY
  then
    fail "${description}: response was not valid JSON"
    return 1
  fi
  pass "$description"
}

json_id() {
  python3 - "$1" <<'PY'
import json
import sys
value = json.load(open(sys.argv[1], encoding="utf-8")).get("id")
print("" if value is None else value)
PY
}

json_document_id() {
  python3 - "$1" <<'PY'
import json
import sys
value = json.load(open(sys.argv[1], encoding="utf-8")).get("documentId")
print("" if value is None else value)
PY
}

json_content_hash() {
  python3 - "$1" <<'PY'
import json
import sys
value = json.load(open(sys.argv[1], encoding="utf-8")).get("contentHash")
print("" if value is None else value)
PY
}

write_upsert_request() {
  local file="$1"
  local external_id="$2"
  local retrieval_text="$3"
  local value="$4"
  python3 - "$file" "$SOURCE_KEY" "$external_id" "$retrieval_text" "$value" <<'PY'
import json
import sys

path, collection_key, external_id, retrieval_text, value = sys.argv[1:]
request = {
    "collectionKey": collection_key,
    "externalId": external_id,
    "title": f"JSONB E2E {external_id}",
    "retrievalText": retrieval_text,
    "jsonbPayload": {
        "recordKey": external_id,
        "value": int(value),
        "verificationToken": "present-but-not-embedded",
    },
    "source": "jsonb-records-e2e",
    "metadata": {"test": "jsonb-records-e2e"},
    "embed": True,
}
with open(path, "w", encoding="utf-8") as handle:
    json.dump(request, handle, ensure_ascii=False)
PY
}

write_payload_update_request() {
  local file="$1"
  python3 - "$file" "$SOURCE_KEY" "$TOKEN" <<'PY'
import json
import sys

path, collection_key, token = sys.argv[1:]
request = {
    "collectionKey": collection_key,
    "externalId": "record-1",
    "title": "JSONB E2E record-1",
    "retrievalText": f"The JSONB record E2E description contains {token}.",
    "jsonbPayload": {
        "recordKey": "record-1",
        "value": 101,
        "verificationToken": "payload-only-update",
    },
    "source": "jsonb-records-e2e",
    "metadata": {"test": "jsonb-records-e2e"},
    "embed": True,
}
with open(path, "w", encoding="utf-8") as handle:
    json.dump(request, handle, ensure_ascii=False)
PY
}

write_retrieval_update_request() {
  local file="$1"
  python3 - "$file" "$SOURCE_KEY" "$TOKEN" <<'PY'
import json
import sys

path, collection_key, token = sys.argv[1:]
request = {
    "collectionKey": collection_key,
    "externalId": "record-2",
    "title": "JSONB E2E record-2",
    "retrievalText": f"The updated JSONB record description contains {token}.",
    "jsonbPayload": {
        "recordKey": "record-2",
        "value": 202,
        "verificationToken": "retrieval-update",
    },
    "source": "jsonb-records-e2e",
    "metadata": {"test": "jsonb-records-e2e"},
    "embed": True,
}
with open(path, "w", encoding="utf-8") as handle:
    json.dump(request, handle, ensure_ascii=False)
PY
}

write_search_request() {
  local file="$1"
  local collection_key="$2"
  python3 - "$file" "$collection_key" "$TOKEN" <<'PY'
import json
import sys

path, collection_key, token = sys.argv[1:]
request = {
    "query": f"JSONB record {token}",
    "collectionKeys": [collection_key],
    "config": {
        "maxResults": 10,
        "minScore": 0,
        "useHybridSearch": True,
        "useRerank": False,
    },
}
with open(path, "w", encoding="utf-8") as handle:
    json.dump(request, handle)
PY
}

cleanup() {
  set +e
  if [[ -n "$RESTRICTED_KEY_ID" && -n "$ADMIN_KEY" ]]; then
    request DELETE "$API/api-keys/${RESTRICTED_KEY_ID}" \
      "$LOG_DIR/revoke-key.json" "$ADMIN_KEY" >/dev/null 2>&1
  fi
  for key in "$IMPORT_KEY" "$CLONE_KEY" "$SOURCE_KEY"; do
    request DELETE "$API/collections/by-key?collectionKey=${key}" \
      "$LOG_DIR/delete-${key}.json" "$OPERATOR_KEY" >/dev/null 2>&1
  done
  # Response files may contain caller payloads or a raw temporary API key.
  find "$LOG_DIR" -type f ! -name summary.md -delete 2>/dev/null || true
  {
    echo "# JSONB live HTTP E2E"
    echo
    echo "- Run: \`${RUN_ID}\`"
    echo "- Base URL: \`${BASE_URL}\`"
    echo "- Passed checks: **${PASS_COUNT}**"
    echo "- Failed checks: **${FAIL_COUNT}**"
    if [[ "$SKIP_ACL" == "1" ]]; then
      echo "- ACL checks: skipped by `--skip-acl`"
    else
      echo "- ACL checks: executed"
    fi
  } > "$LOG_DIR/summary.md"
}
trap cleanup EXIT

for command_name in curl python3; do
  command -v "$command_name" >/dev/null || {
    echo "Missing required command: ${command_name}" >&2
    exit 2
  }
done

echo "JSONB live HTTP E2E: ${BASE_URL}"

HEALTH_FILE="$LOG_DIR/health.json"
HEALTH_CODE=$(request GET "$BASE_URL/actuator/health" "$HEALTH_FILE" "$OPERATOR_KEY") || {
  fail "backend is not reachable at ${BASE_URL}"
  exit 1
}
require_status "backend health" "$HEALTH_CODE" "200"
python3 - "$HEALTH_FILE" <<'PY'
import json
import sys
data = json.load(open(sys.argv[1], encoding="utf-8"))
if data.get("status") != "UP":
    raise SystemExit(f"health is not UP: {data.get('status')}")
PY
pass "backend health status is UP"

SOURCE_REQUEST="$LOG_DIR/create-source.request.json"
python3 - "$SOURCE_REQUEST" "$SOURCE_KEY" <<'PY'
import json
import sys
path, key = sys.argv[1:]
with open(path, "w", encoding="utf-8") as handle:
    json.dump({
        "collectionKey": key,
        "name": "JSONB live E2E source",
        "description": "Temporary JSONB structured-record E2E collection",
        "dimensions": 1024,
    }, handle)
PY
SOURCE_RESPONSE="$LOG_DIR/create-source.json"
SOURCE_CODE=$(request POST "$API/collections" "$SOURCE_RESPONSE" "$OPERATOR_KEY" \
  "$(cat "$SOURCE_REQUEST")")
require_file_json "source collection created" "$SOURCE_RESPONSE" "$SOURCE_CODE"
SOURCE_ID="$(json_id "$SOURCE_RESPONSE")"
[[ -n "$SOURCE_ID" ]] || { fail "source collection response did not contain id"; exit 1; }

for spec in "record-1|1" "record-2|2"; do
  IFS='|' read -r external_id value <<<"$spec"
  request_file="$LOG_DIR/upsert-${external_id}.request.json"
  response_file="$LOG_DIR/upsert-${external_id}.json"
  write_upsert_request "$request_file" "$external_id" \
    "The JSONB record E2E description contains ${TOKEN}." "$value"
  code=$(request POST "$API/json-records/upsert" "$response_file" "$OPERATOR_KEY" \
    "$(cat "$request_file")")
  require_file_json "upsert ${external_id}" "$response_file" "$code"
  python3 - "$response_file" <<'PY'
import json
import sys
data = json.load(open(sys.argv[1], encoding="utf-8"))
if data.get("action") not in ("CREATED", "UPDATED"):
    raise SystemExit(f"unexpected action: {data.get('action')}")
if data.get("embeddingStatus") != "COMPLETED":
    raise SystemExit(f"embedding did not complete: {data.get('embeddingStatus')}")
PY
  pass "upsert ${external_id} embedded retrievalText"
done

DETAIL_BEFORE="$LOG_DIR/detail-before.json"
DETAIL_CODE=$(request GET "$API/json-records/$(json_document_id "$LOG_DIR/upsert-record-1.json")" \
  "$DETAIL_BEFORE" "$OPERATOR_KEY")
require_file_json "record detail returned" "$DETAIL_BEFORE" "$DETAIL_CODE"
HASH_BEFORE="$(json_content_hash "$DETAIL_BEFORE")"

SEARCH_REQUEST="$LOG_DIR/search.request.json"
write_search_request "$SEARCH_REQUEST" "$SOURCE_KEY"
SEARCH_RESPONSE="$LOG_DIR/search.json"
SEARCH_CODE=$(request POST "$API/json-records/search" "$SEARCH_RESPONSE" "$OPERATOR_KEY" \
  "$(cat "$SEARCH_REQUEST")")
require_file_json "JSON record search returned" "$SEARCH_RESPONSE" "$SEARCH_CODE"
python3 - "$SEARCH_RESPONSE" "$TOKEN" <<'PY'
import json
import sys

data = json.load(open(sys.argv[1], encoding="utf-8"))
token = sys.argv[2]
results = data.get("results") or []
if len(results) < 2:
    raise SystemExit(f"expected both JSON records, got {len(results)}")
ids = {item.get("externalId") for item in results}
if not {"record-1", "record-2"}.issubset(ids):
    raise SystemExit(f"search did not return both external IDs: {ids}")
for item in results:
    if token not in (item.get("retrievalText") or ""):
        raise SystemExit("search result did not expose retrievalText")
    payload = item.get("jsonbPayload") or {}
    if payload.get("recordKey") != item.get("externalId"):
        raise SystemExit("payload/externalId correspondence is incorrect")
print("search results", len(results))
PY
pass "search is collection-scoped and returns matching JSONB payloads"

PAYLOAD_REQUEST="$LOG_DIR/payload-update.request.json"
write_payload_update_request "$PAYLOAD_REQUEST"
PAYLOAD_RESPONSE="$LOG_DIR/payload-update.json"
PAYLOAD_CODE=$(request POST "$API/json-records/upsert" "$PAYLOAD_RESPONSE" "$OPERATOR_KEY" \
  "$(cat "$PAYLOAD_REQUEST")")
require_file_json "payload-only update returned" "$PAYLOAD_RESPONSE" "$PAYLOAD_CODE"
python3 - "$PAYLOAD_RESPONSE" <<'PY'
import json
import sys
data = json.load(open(sys.argv[1], encoding="utf-8"))
if data.get("action") != "UPDATED":
    raise SystemExit(f"expected UPDATED, got {data.get('action')}")
if data.get("contentChanged") is not False or data.get("payloadChanged") is not True:
    raise SystemExit("payload-only change flags are incorrect")
if data.get("embeddingStatus") != "CACHED":
    raise SystemExit(f"payload-only update unexpectedly re-embedded: {data.get('embeddingStatus')}")
PY
pass "payload-only update creates a version without re-embedding"

DETAIL_AFTER_PAYLOAD="$LOG_DIR/detail-after-payload.json"
DETAIL_CODE=$(request GET "$API/json-records/$(json_document_id "$LOG_DIR/upsert-record-1.json")" \
  "$DETAIL_AFTER_PAYLOAD" "$OPERATOR_KEY")
require_file_json "detail after payload update returned" "$DETAIL_AFTER_PAYLOAD" "$DETAIL_CODE"
HASH_AFTER_PAYLOAD="$(json_content_hash "$DETAIL_AFTER_PAYLOAD")"
[[ "$HASH_BEFORE" == "$HASH_AFTER_PAYLOAD" ]] \
  && pass "payload-only update keeps contentHash unchanged" \
  || { fail "payload-only update changed contentHash"; exit 1; }

RETRIEVAL_REQUEST="$LOG_DIR/retrieval-update.request.json"
write_retrieval_update_request "$RETRIEVAL_REQUEST"
RETRIEVAL_RESPONSE="$LOG_DIR/retrieval-update.json"
RETRIEVAL_CODE=$(request POST "$API/json-records/upsert" "$RETRIEVAL_RESPONSE" "$OPERATOR_KEY" \
  "$(cat "$RETRIEVAL_REQUEST")")
require_file_json "retrievalText update returned" "$RETRIEVAL_RESPONSE" "$RETRIEVAL_CODE"
python3 - "$RETRIEVAL_RESPONSE" <<'PY'
import json
import sys
data = json.load(open(sys.argv[1], encoding="utf-8"))
if data.get("contentChanged") is not True:
    raise SystemExit("retrievalText update was not marked contentChanged")
if data.get("embeddingStatus") != "COMPLETED":
    raise SystemExit(f"retrievalText update did not re-embed: {data.get('embeddingStatus')}")
PY
pass "retrievalText update invalidates and replaces embedding"

CLONE_RESPONSE="$LOG_DIR/clone.json"
CLONE_CODE=$(request POST "$API/collections/clone" \
  "$CLONE_RESPONSE" "$OPERATOR_KEY" \
  "{\"sourceCollectionKey\":\"${SOURCE_KEY}\",\"collectionKey\":\"${CLONE_KEY}\"}")
require_file_json "collection clone returned" "$CLONE_RESPONSE" "$CLONE_CODE"
CLONE_ID="$(python3 - "$CLONE_RESPONSE" <<'PY'
import json
import sys
value = json.load(open(sys.argv[1], encoding="utf-8")).get("clonedCollectionId")
print("" if value is None else value)
PY
)"
[[ -n "$CLONE_ID" ]] || { fail "clone response did not contain clonedCollectionId"; exit 1; }

for collection_key in "$CLONE_KEY" "$SOURCE_KEY"; do
  export_file="$LOG_DIR/export-${collection_key}.json"
  export_code=$(request GET \
    "$API/collections/by-key/export?collectionKey=${collection_key}" \
    "$export_file" "$OPERATOR_KEY")
  require_file_json "export collection ${collection_key}" "$export_file" "$export_code"
  python3 - "$export_file" <<'PY'
import json
import sys
data = json.load(open(sys.argv[1], encoding="utf-8"))
documents = data.get("documents") or []
records = [d for d in documents if d.get("documentType") == "json-record"]
if not records:
    raise SystemExit("export did not contain a JSON record")
if any(not d.get("externalId") or d.get("jsonbPayload") is None for d in records):
    raise SystemExit("exported JSON record lost externalId or jsonbPayload")
PY
  pass "export ${collection_key} preserves JSON record fields"
done

python3 - "$LOG_DIR/export-${SOURCE_KEY}.json" "$LOG_DIR/import.request.json" "$IMPORT_KEY" <<'PY'
import json
import sys

source, target, key = sys.argv[1:]
data = json.load(open(source, encoding="utf-8"))
data["name"] = "JSONB live E2E imported"
data["collectionKey"] = key
with open(target, "w", encoding="utf-8") as handle:
    json.dump(data, handle, ensure_ascii=False)
PY
IMPORT_RESPONSE="$LOG_DIR/import.json"
IMPORT_CODE=$(request POST "$API/collections/import" "$IMPORT_RESPONSE" "$OPERATOR_KEY" \
  "$(cat "$LOG_DIR/import.request.json")")
require_file_json "collection import returned" "$IMPORT_RESPONSE" "$IMPORT_CODE"
IMPORT_ID="$(json_id "$IMPORT_RESPONSE")"
[[ -n "$IMPORT_ID" ]] || { fail "import response did not contain id"; exit 1; }
python3 - "$IMPORT_RESPONSE" <<'PY'
import json
import sys
data = json.load(open(sys.argv[1], encoding="utf-8"))
if int(data.get("importedDocuments") or 0) < 2:
    raise SystemExit("import did not report both JSON records")
PY
pass "typed collection import preserved JSON records"

IMPORTED_EXPORT="$LOG_DIR/export-${IMPORT_KEY}.json"
IMPORTED_EXPORT_CODE=$(request GET \
  "$API/collections/by-key/export?collectionKey=${IMPORT_KEY}" \
  "$IMPORTED_EXPORT" "$OPERATOR_KEY")
require_file_json "imported collection export returned" "$IMPORTED_EXPORT" "$IMPORTED_EXPORT_CODE"
python3 - "$IMPORTED_EXPORT" <<'PY'
import json
import sys
data = json.load(open(sys.argv[1], encoding="utf-8"))
records = [d for d in data.get("documents") or [] if d.get("documentType") == "json-record"]
if len(records) < 2:
    raise SystemExit("imported collection does not contain both JSON records")
if any(d.get("jsonbPayload") is None for d in records):
    raise SystemExit("imported collection lost JSONB payload")
PY
pass "import/export round trip preserved JSONB payloads"

if [[ "$SKIP_ACL" == "1" ]]; then
  echo "SKIP: ACL allow/deny checks (--skip-acl)"
else
  [[ -n "$ADMIN_KEY" ]] || {
    echo "ACL checks require RAG_ROOT_API_KEY or E2E_ADMIN_API_KEY; use --skip-acl to skip." >&2
    exit 2
  }
  KEY_REQUEST="$LOG_DIR/restricted-key.request.json"
  python3 - "$KEY_REQUEST" "$SOURCE_KEY" <<'PY'
import json
import sys
from datetime import datetime, timedelta

path, collection_key = sys.argv[1:]
expires = (datetime.now() + timedelta(hours=1)).replace(microsecond=0).isoformat()
with open(path, "w", encoding="utf-8") as handle:
    json.dump({
        "name": "jsonb-live-e2e-restricted",
        "expiresAt": expires,
        "allowedCollectionKeys": [collection_key],
    }, handle)
PY
  KEY_RESPONSE="$LOG_DIR/restricted-key.json"
  KEY_CODE=$(request POST "$API/api-keys" "$KEY_RESPONSE" "$ADMIN_KEY" \
    "$(cat "$KEY_REQUEST")")
  require_file_json "restricted API key created" "$KEY_RESPONSE" "$KEY_CODE"
  RESTRICTED_KEY_ID="$(python3 - "$KEY_RESPONSE" <<'PY'
import json
import sys
value = json.load(open(sys.argv[1], encoding="utf-8")).get("keyId")
print("" if value is None else value)
PY
)"
  RESTRICTED_RAW_KEY="$(python3 - "$KEY_RESPONSE" <<'PY'
import json
import sys
value = json.load(open(sys.argv[1], encoding="utf-8")).get("rawKey")
print("" if value is None else value)
PY
)"
  [[ -n "$RESTRICTED_KEY_ID" && -n "$RESTRICTED_RAW_KEY" ]] \
    || { fail "restricted API key response did not contain key identity"; exit 1; }
  ACL_SEARCH="$LOG_DIR/acl-allowed-search.json"
  ACL_ALLOWED_CODE=$(request POST "$API/json-records/search" "$ACL_SEARCH" \
    "$RESTRICTED_RAW_KEY" "$(cat "$SEARCH_REQUEST")")
  require_status "restricted key can search allowed collection" "$ACL_ALLOWED_CODE" "200"
  ACL_DENIED="$LOG_DIR/acl-denied-search.json"
  DENIED_CODE=$(request POST "$API/json-records/search" "$ACL_DENIED" \
    "$RESTRICTED_RAW_KEY" "$(python3 - "$CLONE_KEY" <<'PY'
import json
import sys
print(json.dumps({
    "query": "JSONB record",
    "collectionKeys": [sys.argv[1]],
    "config": {"maxResults": 5, "minScore": 0, "useRerank": False},
}))
PY
)")
  require_status "restricted key is denied outside its collection" "$DENIED_CODE" "403"
  REVOKE_CODE=$(request DELETE "$API/api-keys/${RESTRICTED_KEY_ID}" \
    "$LOG_DIR/revoke-key.json" "$ADMIN_KEY")
  require_status "temporary restricted key revoked" "$REVOKE_CODE" "204"
  RESTRICTED_KEY_ID=""
  RESTRICTED_RAW_KEY=""
fi

echo
echo "JSONB live HTTP E2E passed: ${PASS_COUNT} checks"
