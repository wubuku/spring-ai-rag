#!/usr/bin/env bash
# =============================================================================
# Live HTTP E2E for ordinary external-document synchronization.
#
# Prerequisites:
#   - A running PostgreSQL-profile backend
#   - curl and python3
#   - A root API key or an unrestricted ADMIN API key (the script creates a
#     temporary Collection; restricted business keys cannot create Collections)
#
# Usage:
#   BASE_URL=http://127.0.0.1:18081 \
#   RAG_API_KEY="$RAG_ROOT_API_KEY" \
#   ./scripts/external-documents-e2e.sh
#
# Set EXTERNAL_DOCUMENT_E2E_EMBED=false only when the embedding provider is
# intentionally unavailable. The script never prints API keys or full content.
# =============================================================================
set -euo pipefail

cd "$(dirname "$0")/.."

BASE_URL="${BASE_URL:-http://127.0.0.1:18081}"
API="${BASE_URL}/api/v1/rag"
RUN_ID="${EXTERNAL_DOCUMENT_E2E_RUN_ID:-$(date +%Y%m%d-%H%M%S)-$$}"
LOG_DIR="${EXTERNAL_DOCUMENT_E2E_LOG_DIR:-.verification/external-documents-e2e/${RUN_ID}}"
REQUEST_TIMEOUT_SECONDS="${EXTERNAL_DOCUMENT_E2E_TIMEOUT_SECONDS:-120}"
RUN_EMBEDDING="${EXTERNAL_DOCUMENT_E2E_EMBED:-true}"

mkdir -p "$LOG_DIR"

PRESERVE_RAG_API_KEY="${RAG_API_KEY-}"
if [[ -f .env ]]; then
  # shellcheck disable=SC2046
  export $(grep -v '^#' .env | grep -v '^$' | xargs) || true
fi
[[ -n "$PRESERVE_RAG_API_KEY" ]] && export RAG_API_KEY="$PRESERVE_RAG_API_KEY"

OPERATOR_KEY="${RAG_API_KEY:-${RAG_ROOT_API_KEY:-}}"
[[ -n "$OPERATOR_KEY" ]] || {
  echo "RAG_API_KEY or RAG_ROOT_API_KEY is required" >&2
  exit 2
}

SOURCE_KEY="external-doc-e2e-${RUN_ID}"
EXTERNAL_ID="cms:article:${RUN_ID}"
DOCUMENT_ID=""
BATCH_DOCUMENT_ID=""
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

urlencode() {
  python3 - "$1" <<'PY'
import sys
from urllib.parse import quote
print(quote(sys.argv[1], safe=""))
PY
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

require_json() {
  local description="$1"
  local file="$2"
  local code="$3"
  if [[ "$code" != "2"* ]]; then
    fail "${description}: expected 2xx, got HTTP ${code}"
    return 1
  fi
  python3 - "$file" <<'PY'
import json
import sys
json.load(open(sys.argv[1], encoding="utf-8"))
PY
  pass "$description"
}

json_value() {
  python3 - "$1" "$2" <<'PY'
import json
import sys
value = json.load(open(sys.argv[1], encoding="utf-8"))
for part in sys.argv[2].split("."):
    if not isinstance(value, dict):
        value = None
        break
    value = value.get(part)
print("" if value is None else value)
PY
}

write_upsert() {
  local file="$1"
  local revision="$2"
  local content="$3"
  local embed="$4"
  python3 - "$file" "$SOURCE_KEY" "$EXTERNAL_ID" "$revision" "$content" "$embed" <<'PY'
import json
import sys
path, collection_key, external_id, revision, content, embed = sys.argv[1:]
with open(path, "w", encoding="utf-8") as handle:
    json.dump({
        "collectionKey": collection_key,
        "externalId": external_id,
        "sourceRevision": revision,
        "title": "External document E2E fixture",
        "content": content,
        "source": "external-documents-e2e",
        "documentType": "text",
        "metadata": {"test": "external-documents-e2e"},
        "embed": embed == "true",
    }, handle)
PY
}

write_update() {
  local file="$1"
  local revision="$2"
  local expected="$3"
  local content="$4"
  local embed="$5"
  python3 - "$file" "$SOURCE_KEY" "$EXTERNAL_ID" "$revision" "$expected" "$content" "$embed" <<'PY'
import json
import sys
path, collection_key, external_id, revision, expected, content, embed = sys.argv[1:]
with open(path, "w", encoding="utf-8") as handle:
    json.dump({
        "collectionKey": collection_key,
        "externalId": external_id,
        "sourceRevision": revision,
        "expectedSourceRevision": expected,
        "title": "External document E2E fixture",
        "content": content,
        "source": "external-documents-e2e",
        "documentType": "text",
        "metadata": {"test": "external-documents-e2e"},
        "embed": embed == "true",
    }, handle)
PY
}

cleanup() {
  set +e
  if [[ -n "$OPERATOR_KEY" ]]; then
    if [[ -n "$DOCUMENT_ID" ]]; then
      request DELETE \
        "$API/documents/${DOCUMENT_ID}" \
        "$LOG_DIR/cleanup-document.json" "$OPERATOR_KEY" >/dev/null 2>&1
    fi
    if [[ -n "$BATCH_DOCUMENT_ID" ]]; then
      request DELETE \
        "$API/documents/${BATCH_DOCUMENT_ID}" \
        "$LOG_DIR/cleanup-batch-document.json" "$OPERATOR_KEY" >/dev/null 2>&1
    fi
    request DELETE \
      "$API/collections/by-key?collectionKey=$(urlencode "$SOURCE_KEY")" \
      "$LOG_DIR/cleanup.json" "$OPERATOR_KEY" >/dev/null 2>&1
  fi
  find "$LOG_DIR" -type f ! -name summary.md -delete 2>/dev/null || true
  {
    echo "# External document live HTTP E2E"
    echo
    echo "- Run: \`${RUN_ID}\`"
    echo "- Base URL: \`${BASE_URL}\`"
    echo "- Passed checks: **${PASS_COUNT}**"
    echo "- Failed checks: **${FAIL_COUNT}**"
    if [[ "$RUN_EMBEDDING" == "true" ]]; then
      echo "- Embedding check: requested"
    else
      echo "- Embedding check: skipped by \`EXTERNAL_DOCUMENT_E2E_EMBED=false\`"
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

case "$RUN_EMBEDDING" in
  true|false) ;;
  *) echo "EXTERNAL_DOCUMENT_E2E_EMBED must be true or false" >&2; exit 2 ;;
esac

echo "External document live HTTP E2E: ${BASE_URL}"

HEALTH_FILE="$LOG_DIR/health.json"
HEALTH_CODE=$(request GET "$BASE_URL/actuator/health" "$HEALTH_FILE" "$OPERATOR_KEY")
require_status "backend health" "$HEALTH_CODE" "200"
python3 - "$HEALTH_FILE" <<'PY'
import json
import sys
data = json.load(open(sys.argv[1], encoding="utf-8"))
if data.get("status") != "UP":
    raise SystemExit(f"health is not UP: {data.get('status')}")
PY
pass "backend health status is UP"

COLLECTION_REQUEST="$LOG_DIR/collection.request.json"
python3 - "$COLLECTION_REQUEST" "$SOURCE_KEY" <<'PY'
import json
import sys
with open(sys.argv[1], "w", encoding="utf-8") as handle:
    json.dump({
        "collectionKey": sys.argv[2],
        "name": "External document E2E fixture",
        "description": "Temporary collection for external document synchronization",
        "dimensions": 1024,
    }, handle)
PY
COLLECTION_RESPONSE="$LOG_DIR/collection.json"
COLLECTION_CODE=$(request POST "$API/collections" "$COLLECTION_RESPONSE" "$OPERATOR_KEY" \
  "$(cat "$COLLECTION_REQUEST")")
require_json "source collection created" "$COLLECTION_RESPONSE" "$COLLECTION_CODE"

CREATE_REQUEST="$LOG_DIR/create.request.json"
write_upsert "$CREATE_REQUEST" "rev-1" \
  "The external document synchronization fixture starts with revision one." "false"
CREATE_RESPONSE="$LOG_DIR/create.json"
CREATE_CODE=$(request POST "$API/documents/upsert" "$CREATE_RESPONSE" "$OPERATOR_KEY" \
  "$(cat "$CREATE_REQUEST")")
require_json "external document created without embedding" "$CREATE_RESPONSE" "$CREATE_CODE"
python3 - "$CREATE_RESPONSE" <<'PY'
import json
import sys
data = json.load(open(sys.argv[1], encoding="utf-8"))
if data.get("action") != "CREATED" or data.get("embeddingStatus") != "NOT_REQUESTED":
    raise SystemExit("create did not report CREATED + NOT_REQUESTED")
if data.get("embeddingFresh") is not False:
    raise SystemExit("create should not be embedding-fresh")
if not data.get("documentId"):
    raise SystemExit("create did not return documentId")
PY
DOCUMENT_ID="$(json_value "$CREATE_RESPONSE" documentId)"
pass "create reports stable document identity and no-embedding state"

REPLAY_RESPONSE="$LOG_DIR/replay.json"
REPLAY_CODE=$(request POST "$API/documents/upsert" "$REPLAY_RESPONSE" "$OPERATOR_KEY" \
  "$(cat "$CREATE_REQUEST")")
require_json "exact upsert replay returned" "$REPLAY_RESPONSE" "$REPLAY_CODE"
python3 - "$REPLAY_RESPONSE" "$DOCUMENT_ID" <<'PY'
import json
import sys
data = json.load(open(sys.argv[1], encoding="utf-8"))
if data.get("action") != "UNCHANGED" or str(data.get("documentId")) != sys.argv[2]:
    raise SystemExit("exact replay did not preserve identity")
PY
pass "exact replay is idempotent"

GET_RESPONSE="$LOG_DIR/get-by-external-id.json"
GET_CODE=$(request GET \
  "$API/documents/by-external-id?collectionKey=$(urlencode "$SOURCE_KEY")&externalId=$(urlencode "$EXTERNAL_ID")" \
  "$GET_RESPONSE" "$OPERATOR_KEY")
require_json "lookup by external identity returned" "$GET_RESPONSE" "$GET_CODE"
python3 - "$GET_RESPONSE" "$DOCUMENT_ID" <<'PY'
import json
import sys
data = json.load(open(sys.argv[1], encoding="utf-8"))
if str(data.get("id")) != sys.argv[2] or data.get("externalId") is None:
    raise SystemExit("lookup did not return the stable document")
PY
pass "lookup uses collectionKey and externalId"

UPDATE_REQUEST="$LOG_DIR/update.request.json"
write_update "$UPDATE_REQUEST" "rev-2" "rev-1" \
  "The external document synchronization fixture contains the updated revision." \
  "$RUN_EMBEDDING"
UPDATE_RESPONSE="$LOG_DIR/update.json"
UPDATE_CODE=$(request POST "$API/documents/upsert" "$UPDATE_RESPONSE" "$OPERATOR_KEY" \
  "$(cat "$UPDATE_REQUEST")")
require_json "content update returned" "$UPDATE_RESPONSE" "$UPDATE_CODE"
python3 - "$UPDATE_RESPONSE" "$DOCUMENT_ID" "$RUN_EMBEDDING" <<'PY'
import json
import sys
data = json.load(open(sys.argv[1], encoding="utf-8"))
if data.get("action") != "UPDATED" or str(data.get("documentId")) != sys.argv[2]:
    raise SystemExit("content update did not preserve documentId")
if data.get("contentChanged") is not True:
    raise SystemExit("content update was not marked contentChanged")
if sys.argv[3] == "true":
    if data.get("embeddingStatus") != "COMPLETED" or data.get("embeddingFresh") is not True:
        raise SystemExit("content update did not complete fresh embedding")
else:
    if data.get("embeddingStatus") != "NOT_REQUESTED":
        raise SystemExit("embedding skip did not report NOT_REQUESTED")
PY
if [[ "$RUN_EMBEDDING" == "true" ]]; then
  pass "content update re-embedded and is fresh"
else
  pass "content update persistence passed; embedding provider check skipped"
fi

CAS_REQUEST="$LOG_DIR/cas-conflict.request.json"
write_update "$CAS_REQUEST" "rev-3" "rev-1" \
  "This content must not overwrite the current revision." "false"
CAS_RESPONSE="$LOG_DIR/cas-conflict.json"
CAS_CODE=$(request POST "$API/documents/upsert" "$CAS_RESPONSE" "$OPERATOR_KEY" \
  "$(cat "$CAS_REQUEST")")
require_status "stale expected revision rejected" "$CAS_CODE" "409"

SAME_REVISION_REQUEST="$LOG_DIR/same-revision-conflict.request.json"
write_update "$SAME_REVISION_REQUEST" "rev-2" "rev-2" \
  "This content conflicts with the already accepted revision." "false"
SAME_REVISION_RESPONSE="$LOG_DIR/same-revision-conflict.json"
SAME_REVISION_CODE=$(request POST "$API/documents/upsert" \
  "$SAME_REVISION_RESPONSE" "$OPERATOR_KEY" \
  "$(cat "$SAME_REVISION_REQUEST")")
require_status "same revision with different content rejected" \
  "$SAME_REVISION_CODE" "409"

BATCH_REQUEST="$LOG_DIR/batch.request.json"
python3 - "$BATCH_REQUEST" "$SOURCE_KEY" "$RUN_ID" <<'PY'
import json
import sys
with open(sys.argv[1], "w", encoding="utf-8") as handle:
    json.dump({"items": [{
        "collectionKey": sys.argv[2],
        "externalId": f"cms:batch:{sys.argv[3]}",
        "sourceRevision": "batch-1",
        "title": "External batch fixture",
        "content": "A second document in the external synchronization batch.",
        "embed": False,
    }]}, handle)
PY
BATCH_RESPONSE="$LOG_DIR/batch.json"
BATCH_CODE=$(request POST "$API/documents/batch-upsert" "$BATCH_RESPONSE" "$OPERATOR_KEY" \
  "$(cat "$BATCH_REQUEST")")
require_json "batch upsert returned" "$BATCH_RESPONSE" "$BATCH_CODE"
BATCH_DOCUMENT_ID="$(python3 - "$BATCH_RESPONSE" <<'PY'
import json
import sys
items = json.load(open(sys.argv[1], encoding="utf-8")).get("items") or []
document_id = items[0].get("documentId") if items else None
print("" if document_id is None else document_id)
PY
)"
python3 - "$BATCH_RESPONSE" <<'PY'
import json
import sys
data = json.load(open(sys.argv[1], encoding="utf-8"))
if (data.get("summary") or {}).get("created") != 1:
    raise SystemExit("batch summary did not report one created document")
PY
pass "batch upsert isolates and reports item results"

DELETE_URL="$API/documents/by-external-id?collectionKey=$(urlencode "$SOURCE_KEY")&externalId=$(urlencode "$EXTERNAL_ID")&sourceRevision=$(urlencode "rev-3")&expectedSourceRevision=$(urlencode "rev-2")"
DELETE_RESPONSE="$LOG_DIR/delete.json"
DELETE_CODE=$(request DELETE "$DELETE_URL" "$DELETE_RESPONSE" "$OPERATOR_KEY")
require_json "source tombstone returned" "$DELETE_RESPONSE" "$DELETE_CODE"
python3 - "$DELETE_RESPONSE" <<'PY'
import json
import sys
data = json.load(open(sys.argv[1], encoding="utf-8"))
if data.get("action") != "DELETED" or data.get("enabled") is not False:
    raise SystemExit("source delete did not create a tombstone")
PY
pass "source deletion creates a tombstone"

DELETE_REPLAY="$LOG_DIR/delete-replay.json"
DELETE_REPLAY_CODE=$(request DELETE "$DELETE_URL" "$DELETE_REPLAY" "$OPERATOR_KEY")
require_json "source tombstone replay returned" "$DELETE_REPLAY" "$DELETE_REPLAY_CODE"
python3 - "$DELETE_REPLAY" <<'PY'
import json
import sys
if json.load(open(sys.argv[1], encoding="utf-8")).get("action") != "UNCHANGED":
    raise SystemExit("source delete replay was not idempotent")
PY
pass "source deletion replay is idempotent"

RESTORE_REQUEST="$LOG_DIR/restore.request.json"
write_update "$RESTORE_REQUEST" "rev-4" "rev-3" \
  "The external document synchronization fixture was restored at revision four." "false"
RESTORE_RESPONSE="$LOG_DIR/restore.json"
RESTORE_CODE=$(request POST "$API/documents/upsert" "$RESTORE_RESPONSE" "$OPERATOR_KEY" \
  "$(cat "$RESTORE_REQUEST")")
require_json "distinct subsequent source revision restored document" "$RESTORE_RESPONSE" "$RESTORE_CODE"
python3 - "$RESTORE_RESPONSE" "$DOCUMENT_ID" <<'PY'
import json
import sys
data = json.load(open(sys.argv[1], encoding="utf-8"))
if data.get("action") != "UPDATED" or str(data.get("documentId")) != sys.argv[2]:
    raise SystemExit("tombstone restore did not preserve documentId")
if data.get("processingStatus") not in ("PENDING", "COMPLETED"):
    raise SystemExit("restore returned an invalid processing status")
PY
pass "distinct subsequent source revision restores the same document identity"

echo
echo "External document live HTTP E2E passed: ${PASS_COUNT} checks"
