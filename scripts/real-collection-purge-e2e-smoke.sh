#!/usr/bin/env bash
# 对运行中的真实 provider 服务执行外部文档准实时嵌入与 Collection purge 生命周期验收。
set -euo pipefail

cd "$(dirname "$0")/.."

BASE_URL="${BASE_URL:-http://127.0.0.1:18085}"
API="${BASE_URL}/api/v1/rag"
ENV_FILE="${REAL_LLM_ENV_FILE:-.env}"
WORK_DIR_OWNED=0
if [[ -n "${REAL_COLLECTION_PURGE_LOG_DIR:-}" ]]; then
  WORK_DIR="$REAL_COLLECTION_PURGE_LOG_DIR"
else
  WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/rag-real-purge.XXXXXX")"
  WORK_DIR_OWNED=1
fi
EVENT_START_TIMEOUT_SECONDS="${REAL_COLLECTION_PURGE_EVENT_START_TIMEOUT_SECONDS:-20}"
JOB_TIMEOUT_SECONDS="${REAL_COLLECTION_PURGE_JOB_TIMEOUT_SECONDS:-180}"
OBSERVABILITY_TIMEOUT_SECONDS="${REAL_COLLECTION_PURGE_OBSERVABILITY_TIMEOUT_SECONDS:-15}"

mkdir -p "$WORK_DIR"
chmod 700 "$WORK_DIR"

if [[ -f "$ENV_FILE" ]]; then
  set +u
  set -a
  # shellcheck disable=SC1090
  source "$ENV_FILE"
  set +a
  set -u
fi

ROOT_API_KEY="${RAG_ROOT_API_KEY:-${RAG_API_KEY:-}}"
[[ -n "$ROOT_API_KEY" ]] || {
  echo "RAG_ROOT_API_KEY is required." >&2
  exit 2
}

for command_name in curl jq psql; do
  command -v "$command_name" >/dev/null || {
    echo "Missing required command: ${command_name}" >&2
    exit 2
  }
done

DB_HOST="${POSTGRES_HOST:-127.0.0.1}"
DB_PORT="${POSTGRES_PORT:-5432}"
DB_NAME="${POSTGRES_DATABASE:-spring_ai_rag_dev}"
DB_USER="${POSTGRES_USER:-postgres}"
DB_PASSWORD="${POSTGRES_PASSWORD:-postgres}"
AUTH_HEADER="X-API-Key: ${ROOT_API_KEY}"
TOKEN="PURGE_REAL_$(date +%s)_${RANDOM}"
COLLECTION_KEY="real-purge-${TOKEN}"
EXTERNAL_ID="policy-${TOKEN}"
SESSION_ID="purge-${TOKEN#PURGE_REAL_}"
SESSION_ID="${SESSION_ID:0:36}"
DOC_ID=""
JOB_ID=""
COLLECTION_ID=""
STARTED_BY_EVENT=0
PASS_COUNT=0
HTTP_BODY=""
HTTP_STATUS=""
SEARCH_DOCUMENT_IDS="[]"
CHAT_SOURCE_DOCUMENT_IDS="[]"
FOLLOW_UP_SOURCE_DOCUMENT_IDS="[]"
CHAT_ANSWER_LENGTH=0
FOLLOW_UP_ANSWER_LENGTH=0
OPENAI_ANSWER_LENGTH=0
CHAT_MODEL=""
FOLLOW_UP_MODEL=""
OPENAI_MODEL=""
PURGE_STATUS=""
PURGED_DOCUMENT_COUNT=0
RETIRED_SEARCH_STATUS=0
RETIRED_CHAT_STATUS=0
RETIRED_OPENAI_STATUS=0
PURGE_PREVIEW_OPERATION_COUNT=0
PURGE_APPLY_OPERATION_COUNT=0
PURGE_PREVIEW_COLLECTION_COUNT=0
PURGE_APPLY_COLLECTION_COUNT=0

cleanup() {
  if [[ "$WORK_DIR_OWNED" == "1" ]]; then
    rm -rf "$WORK_DIR"
  fi
}
trap cleanup EXIT

step() {
  echo
  echo "=== $* ==="
}

pass() {
  PASS_COUNT=$((PASS_COUNT + 1))
  echo "PASS: $*"
}

require_positive_count() {
  local label="$1"
  local value="$2"
  if [[ ! "$value" =~ ^[0-9]+$ ]] || (( value < 1 )); then
    echo "${label} must be a positive integer, got: ${value}" >&2
    exit 1
  fi
}

request() {
  local method="$1"
  local url="$2"
  local expected_status="$3"
  local body="${4:-}"
  local response
  local -a args=(
    --silent
    --show-error
    --connect-timeout 5
    --max-time 240
    --request "$method"
    --header "$AUTH_HEADER"
    --write-out $'\n%{http_code}'
  )
  if [[ -n "$body" ]]; then
    args+=(--header "Content-Type: application/json" --data "$body")
  fi
  response="$(curl "${args[@]}" "$url")"
  HTTP_STATUS="${response##*$'\n'}"
  HTTP_BODY="${response%$'\n'"$HTTP_STATUS"}"
  if [[ "$HTTP_STATUS" != "$expected_status" ]]; then
    echo "Unexpected HTTP status for ${method} ${url}: ${HTTP_STATUS}, expected ${expected_status}" >&2
    printf '%s' "$HTTP_BODY" \
      | jq -c '{error, status, detail}' 2>/dev/null >&2 || true
    return 1
  fi
}

db_scalar() {
  local sql="$1"
  PGPASSWORD="$DB_PASSWORD" psql \
    -X -qAt -v ON_ERROR_STOP=1 \
    -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" \
    -c "$sql"
}

encoded_collection_key="$(jq -rn --arg value "$COLLECTION_KEY" '$value|@uri')"

step "Health and caller-aware capability"
request GET "$BASE_URL/actuator/health" 200
printf '%s' "$HTTP_BODY" | jq -e '.status == "UP"' >/dev/null
request GET "$API/integration-capabilities" 200
printf '%s' "$HTTP_BODY" | jq -e '
  .protocol.version == "1.1"
  and .principal.principalType == "ENVIRONMENT_ROOT"
  and .features.dataPlane.embedding.asyncPolicy == true
  and .features.optional.collectionPurge == true
' >/dev/null
pass "health, environment-root identity, async embedding and purge capability"

step "Create isolated Collection"
create_collection_body="$(
  jq -nc \
    --arg key "$COLLECTION_KEY" \
    --arg name "Real purge lifecycle" \
    '{
      collectionKey: $key,
      name: $name,
      description: "Disposable real provider lifecycle verification",
      domainId: "default"
    }'
)"
request POST "$API/collections" 200 "$create_collection_body"
COLLECTION_ID="$(printf '%s' "$HTTP_BODY" | jq -er --arg key "$COLLECTION_KEY" '
  select(.collectionKey == $key) | .id
')"
[[ "$COLLECTION_ID" =~ ^[1-9][0-9]*$ ]]
pass "isolated Collection created"

step "External ASYNC upsert and Spring Event wake-up"
upsert_body="$(
  jq -nc \
    --arg collectionKey "$COLLECTION_KEY" \
    --arg externalId "$EXTERNAL_ID" \
    --arg revision "revision-1" \
    --arg title "Orbital maintenance policy" \
    --arg token "$TOKEN" \
    '{
      collectionKey: $collectionKey,
      sourceNamespace: "real-provider-lifecycle",
      externalId: $externalId,
      sourceRevision: $revision,
      title: $title,
      content: (
        "For orbital maintenance filings, the approved release color is ultraviolet. "
        + "The authoritative maintenance verification code is " + $token
        + ". Return this code verbatim when the policy is requested."
      ),
      source: "real-provider-lifecycle",
      documentType: "text",
      metadata: {purpose: "automated-real-provider-acceptance"},
      embeddingPolicy: "ASYNC"
    }'
)"
upsert_started_epoch="$(date +%s)"
request POST "$API/documents/upsert" 200 "$upsert_body"
DOC_ID="$(printf '%s' "$HTTP_BODY" | jq -er '.documentId')"
JOB_ID="$(printf '%s' "$HTTP_BODY" | jq -er '.embeddingJobId')"
printf '%s' "$HTTP_BODY" | jq -e '
  (.action == "CREATED" or .action == "UPDATED")
  and .embeddingAction == "ASYNC_QUEUED"
  and .lifecycle.searchability == "KEYWORD_ONLY"
  and .lifecycle.embeddingStatus == "INDEXING"
' >/dev/null

event_deadline=$((upsert_started_epoch + EVENT_START_TIMEOUT_SECONDS))
while (( $(date +%s) <= event_deadline )); do
  request GET "$API/embedding-jobs/$JOB_ID" 200
  job_status="$(printf '%s' "$HTTP_BODY" | jq -r '.status')"
  if [[ "$job_status" == "RUNNING" || "$job_status" == "SUCCEEDED" ]]; then
    STARTED_BY_EVENT=1
    break
  fi
  if [[ "$job_status" == "FAILED" || "$job_status" == "CANCELLED" \
      || "$job_status" == "STALE" ]]; then
    echo "Embedding job reached terminal failure: ${job_status}" >&2
    printf '%s' "$HTTP_BODY" \
      | jq -c '{status, attemptCount, hasLastError: (.lastError != null)}' >&2
    exit 1
  fi
  sleep 1
done
[[ "$STARTED_BY_EVENT" == "1" ]] || {
  echo "Embedding job was not started within ${EVENT_START_TIMEOUT_SECONDS}s; "
  echo "the configured 60s recovery scan must not be the primary trigger." >&2
  exit 1
}
pass "Spring Event started the persisted job before the 60s recovery scan"

job_deadline=$((upsert_started_epoch + JOB_TIMEOUT_SECONDS))
while (( $(date +%s) <= job_deadline )); do
  request GET "$API/embedding-jobs/$JOB_ID" 200
  job_status="$(printf '%s' "$HTTP_BODY" | jq -r '.status')"
  if [[ "$job_status" == "SUCCEEDED" ]]; then
    break
  fi
  if [[ "$job_status" == "FAILED" || "$job_status" == "CANCELLED" \
      || "$job_status" == "STALE" ]]; then
    echo "Embedding job reached terminal failure: ${job_status}" >&2
    printf '%s' "$HTTP_BODY" \
      | jq -c '{status, attemptCount, hasLastError: (.lastError != null)}' >&2
    exit 1
  fi
  sleep 2
done
[[ "$job_status" == "SUCCEEDED" ]] || {
  echo "Embedding job did not succeed within ${JOB_TIMEOUT_SECONDS}s." >&2
  exit 1
}
request GET \
  "$API/collections/embedding-readiness?collectionKey=$encoded_collection_key" 200
printf '%s' "$HTTP_BODY" | jq -e '
  .enabledDocuments == 1
  and .freshDocuments == 1
  and .queuedDocuments == 0
  and .runningDocuments == 0
  and .failedDocuments == 0
  and .staleOrMissingDocuments == 0
' >/dev/null
pass "real embedding completed and Collection readiness is fresh"

step "Vector-only natural-language retrieval"
search_body="$(
  jq -nc \
    --arg key "$COLLECTION_KEY" \
    --argjson docId "$DOC_ID" \
    '{
      query: "Which release color is approved for an orbital maintenance filing?",
      collectionScopeMode: "SELECTED_COLLECTIONS",
      collectionKeys: [$key],
      documentIds: [$docId],
      config: {
        maxResults: 5,
        minScore: 0,
        useHybridSearch: false,
        useRerank: false,
        vectorWeight: 1,
        fulltextWeight: 0
      }
    }'
)"
request POST "$API/search" 200 "$search_body"
printf '%s' "$HTTP_BODY" | jq -e --arg docId "$DOC_ID" '
  length > 0
  and all(.[]; .documentId == $docId)
  and any(.[]; (.chunkText // "") | contains("ultraviolet"))
' >/dev/null
SEARCH_DOCUMENT_IDS="$(printf '%s' "$HTTP_BODY" \
  | jq -c '[.[].documentId] | unique')"
pass "real query embedding found only the intended document through vector search"

step "Native real LLM Chat with durable citations"
chat_body="$(
  jq -nc \
    --arg key "$COLLECTION_KEY" \
    --arg sessionId "$SESSION_ID" \
    --arg token "$TOKEN" \
    --argjson docId "$DOC_ID" \
    '{
      message: (
        "Using only the selected policy document, answer with the approved release color "
        + "and the authoritative maintenance verification code. The code begins with PURGE_REAL_."
      ),
      sessionId: $sessionId,
      mode: "KNOWLEDGE",
      maxResults: 5,
      minScore: 0,
      useHybridSearch: false,
      useRerank: false,
      collectionKeys: [$key],
      documentIds: [$docId]
    }'
)"
request POST "$API/chat/ask" 200 "$chat_body"
printf '%s' "$HTTP_BODY" | jq -e \
  --arg token "$TOKEN" \
  --arg docId "$DOC_ID" '
  (.answer | contains($token))
  and (.answer | ascii_downcase | contains("ultraviolet"))
  and any(.sources[]; .documentId == $docId)
' >/dev/null
CHAT_ANSWER_LENGTH="$(printf '%s' "$HTTP_BODY" | jq -r '.answer | length')"
CHAT_MODEL="$(printf '%s' "$HTTP_BODY" | jq -r '.resolvedModel // "unknown"')"
CHAT_SOURCE_DOCUMENT_IDS="$(printf '%s' "$HTTP_BODY" \
  | jq -c '[.sources[].documentId] | unique')"
echo "Native Chat answer length: ${CHAT_ANSWER_LENGTH}"
pass "native real LLM answer and citation match the selected document"

follow_up_body="$(
  jq -nc \
    --arg key "$COLLECTION_KEY" \
    --arg sessionId "$SESSION_ID" \
    --argjson docId "$DOC_ID" \
    '{
      message: "Repeat only the approved release color from the same selected policy.",
      sessionId: $sessionId,
      mode: "KNOWLEDGE",
      maxResults: 5,
      minScore: 0,
      useHybridSearch: false,
      useRerank: false,
      collectionKeys: [$key],
      documentIds: [$docId]
    }'
)"
request POST "$API/chat/ask" 200 "$follow_up_body"
printf '%s' "$HTTP_BODY" | jq -e '
  (.answer | ascii_downcase | contains("ultraviolet"))
  and (.sources | length) > 0
' >/dev/null
FOLLOW_UP_ANSWER_LENGTH="$(printf '%s' "$HTTP_BODY" | jq -r '.answer | length')"
FOLLOW_UP_MODEL="$(printf '%s' "$HTTP_BODY" | jq -r '.resolvedModel // "unknown"')"
FOLLOW_UP_SOURCE_DOCUMENT_IDS="$(printf '%s' "$HTTP_BODY" \
  | jq -c '[.sources[].documentId] | unique')"
echo "Follow-up Chat answer length: ${FOLLOW_UP_ANSWER_LENGTH}"
pass "second real LLM turn used the durable session and selected source"

step "OpenAI-compatible real LLM client path"
openai_body="$(
  jq -nc \
    --arg key "$COLLECTION_KEY" \
    --arg token "$TOKEN" \
    --argjson docId "$DOC_ID" \
    '{
      model: "rag-default",
      messages: [{
        role: "user",
        content: (
          "Using only the selected document, return the authoritative maintenance "
          + "verification code beginning with PURGE_REAL_."
        )
      }],
      stream: false,
      rag: {
        scope: {
          mode: "SELECTED_COLLECTIONS",
          collection_keys: [$key]
        },
        document_ids: [$docId]
      }
    }'
)"
request POST "$BASE_URL/v1/chat/completions" 200 "$openai_body"
printf '%s' "$HTTP_BODY" | jq -e --arg token "$TOKEN" '
  .object == "chat.completion"
  and (.choices[0].message.content | contains($token))
' >/dev/null
OPENAI_ANSWER_LENGTH="$(
  printf '%s' "$HTTP_BODY" | jq -r '.choices[0].message.content | length'
)"
OPENAI_MODEL="$(printf '%s' "$HTTP_BODY" | jq -r '.model // "unknown"')"
echo "OpenAI-compatible answer length: ${OPENAI_ANSWER_LENGTH}"
pass "OpenAI-compatible real LLM path returned the selected document fact"

step "Preview and apply protected purge"
request POST \
  "$API/collections/by-key/purge/preview?collectionKey=$encoded_collection_key" 200
PREVIEW_BODY="$HTTP_BODY"
printf '%s' "$PREVIEW_BODY" | jq -e '
  .status == "PREVIEWED"
  and .documentCount == 1
  and .externalDocumentCount == 1
  and .embeddingCount > 0
  and .embeddingJobCount > 0
  and .affectedChatSessionCount > 0
  and .chatHistoryCount >= 2
  and .activeSyncRunCount == 0
  and .activeDerivationRepairCount == 0
  and .activeChatSessionCount == 0
  and .unindexedChatReferenceCount == 0
  and .unindexedFeedbackReferenceCount == 0
' >/dev/null

apply_body="$(
  printf '%s' "$PREVIEW_BODY" | jq -c '{
    collectionKey,
    previewId,
    confirmationToken,
    fingerprint,
    expectedCollectionVersion: .collectionVersion,
    expectedChatCommitFenceVersion: .chatCommitFenceVersion
  }'
)"
request POST "$API/collections/by-key/purge" 200 "$apply_body"
RESULT_BODY="$HTTP_BODY"
printf '%s' "$RESULT_BODY" | jq -e \
  --arg key "$COLLECTION_KEY" \
  --argjson collectionId "$COLLECTION_ID" '
  .status == "RETIRED"
  and .collectionKey == $key
  and .collectionId == $collectionId
  and .purgedDocumentCount == 1
  and .purgedExternalDocumentCount == 1
  and .purgedLocalDocumentCount == 0
  and .purgedAt != null
' >/dev/null
PURGE_STATUS="$(printf '%s' "$RESULT_BODY" | jq -r '.status')"
PURGED_DOCUMENT_COUNT="$(
  printf '%s' "$RESULT_BODY" | jq -r '.purgedDocumentCount'
)"
request POST "$API/collections/by-key/purge" 200 "$apply_body"
jq -en --argjson original "$RESULT_BODY" --argjson replay "$HTTP_BODY" \
  '$original == $replay' >/dev/null
unset PREVIEW_BODY RESULT_BODY apply_body
pass "preview, apply and exact result replay retired the Collection"

step "Retired scope rejection and default-scope exclusion"
request POST "$API/search" 409 "$search_body"
RETIRED_SEARCH_STATUS="$HTTP_STATUS"
printf '%s' "$HTTP_BODY" \
  | jq -e '.error == "COLLECTION_ALREADY_RETIRED" and .status == 409' >/dev/null
request POST "$API/chat/ask" 409 "$chat_body"
RETIRED_CHAT_STATUS="$HTTP_STATUS"
printf '%s' "$HTTP_BODY" \
  | jq -e '.error == "COLLECTION_ALREADY_RETIRED" and .status == 409' >/dev/null
request POST "$BASE_URL/v1/chat/completions" 409 "$openai_body"
RETIRED_OPENAI_STATUS="$HTTP_STATUS"
printf '%s' "$HTTP_BODY" \
  | jq -e '.error.code == "COLLECTION_ALREADY_RETIRED"' >/dev/null

default_search_body="$(
  jq -nc --arg token "$TOKEN" '{
    query: $token,
    collectionScopeMode: "CALLER_VISIBLE",
    config: {
      maxResults: 5,
      minScore: 0,
      useHybridSearch: true,
      useRerank: false
    }
  }'
)"
request POST "$API/search" 200 "$default_search_body"
printf '%s' "$HTTP_BODY" | jq -e --arg docId "$DOC_ID" --arg token "$TOKEN" '
  all(.[]; .documentId != $docId)
  and all(.[]; ((.chunkText // "") | contains($token) | not))
' >/dev/null
pass "all explicit client paths reject the tombstone and default scope excludes it"

step "Durable purge observability rollups"
observability_deadline=$(( $(date +%s) + OBSERVABILITY_TIMEOUT_SECONDS ))
while (( $(date +%s) <= observability_deadline )); do
  PURGE_PREVIEW_OPERATION_COUNT="$(db_scalar "
    SELECT COALESCE(SUM(request_count), 0)
    FROM rag_api_operation_hourly
    WHERE operation = 'COLLECTION_PURGE_PREVIEW' AND http_status = 200;
  ")"
  PURGE_APPLY_OPERATION_COUNT="$(db_scalar "
    SELECT COALESCE(SUM(request_count), 0)
    FROM rag_api_operation_hourly
    WHERE operation = 'COLLECTION_PURGE_APPLY' AND http_status = 200;
  ")"
  PURGE_PREVIEW_COLLECTION_COUNT="$(db_scalar "
    SELECT COALESCE(SUM(request_count), 0)
    FROM rag_api_collection_operation_hourly
    WHERE collection_id = ${COLLECTION_ID}
      AND operation = 'COLLECTION_PURGE_PREVIEW' AND http_status = 200;
  ")"
  PURGE_APPLY_COLLECTION_COUNT="$(db_scalar "
    SELECT COALESCE(SUM(request_count), 0)
    FROM rag_api_collection_operation_hourly
    WHERE collection_id = ${COLLECTION_ID}
      AND operation = 'COLLECTION_PURGE_APPLY' AND http_status = 200;
  ")"
  if (( PURGE_PREVIEW_OPERATION_COUNT >= 1
      && PURGE_APPLY_OPERATION_COUNT >= 1
      && PURGE_PREVIEW_COLLECTION_COUNT >= 1
      && PURGE_APPLY_COLLECTION_COUNT >= 1 )); then
    break
  fi
  sleep 1
done
require_positive_count \
  "Global purge preview observation count" \
  "$PURGE_PREVIEW_OPERATION_COUNT"
require_positive_count \
  "Global purge apply observation count" \
  "$PURGE_APPLY_OPERATION_COUNT"
require_positive_count \
  "Collection purge preview observation count" \
  "$PURGE_PREVIEW_COLLECTION_COUNT"
require_positive_count \
  "Collection purge apply observation count" \
  "$PURGE_APPLY_COLLECTION_COUNT"
pass "purge preview/apply reached global and Collection observability rollups"

step "Read-only PostgreSQL lifecycle facts"
collection_fact="$(db_scalar "
  SELECT deleted::text || '|' || enabled::text || '|' ||
         (purged_at IS NOT NULL)::text || '|' || name || '|' ||
         (description IS NULL)::text || '|' || (metadata IS NULL)::text
  FROM rag_collection WHERE id = ${COLLECTION_ID};
")"
[[ "$collection_fact" == "true|false|true|Retired collection|true|true" ]]
[[ "$(db_scalar "SELECT COUNT(*) FROM rag_documents WHERE collection_id = ${COLLECTION_ID};")" == "0" ]]
[[ "$(db_scalar "SELECT COUNT(*) FROM rag_embeddings WHERE document_id = ${DOC_ID};")" == "0" ]]
[[ "$(db_scalar "SELECT COUNT(*) FROM rag_embedding_jobs WHERE document_id = ${DOC_ID};")" == "0" ]]
[[ "$(db_scalar "SELECT COUNT(*) FROM rag_chat_history_source_document WHERE document_id = ${DOC_ID};")" == "0" ]]
[[ "$(db_scalar "SELECT COUNT(*) FROM rag_chat_history WHERE session_id = '$SESSION_ID';")" == "0" ]]
[[ "$(db_scalar "
  SELECT COUNT(*) FROM rag_collection_purge_preview
  WHERE collection_id = ${COLLECTION_ID} AND status = 'COMPLETED';
")" == "1" ]]
pass "database retains only the minimal tombstone and completed purge receipt"

jq -n \
  --arg generatedAt "$(date '+%Y-%m-%dT%H:%M:%S%z')" \
  --argjson passes "$PASS_COUNT" \
  --argjson collectionId "$COLLECTION_ID" \
  --arg documentId "$DOC_ID" \
  --arg jobId "$JOB_ID" \
  --argjson searchDocumentIds "$SEARCH_DOCUMENT_IDS" \
  --arg chatModel "$CHAT_MODEL" \
  --argjson chatAnswerLength "$CHAT_ANSWER_LENGTH" \
  --argjson chatSourceDocumentIds "$CHAT_SOURCE_DOCUMENT_IDS" \
  --arg followUpModel "$FOLLOW_UP_MODEL" \
  --argjson followUpAnswerLength "$FOLLOW_UP_ANSWER_LENGTH" \
  --argjson followUpSourceDocumentIds "$FOLLOW_UP_SOURCE_DOCUMENT_IDS" \
  --arg openAiModel "$OPENAI_MODEL" \
  --argjson openAiAnswerLength "$OPENAI_ANSWER_LENGTH" \
  --arg purgeStatus "$PURGE_STATUS" \
  --argjson purgedDocumentCount "$PURGED_DOCUMENT_COUNT" \
  --argjson retiredSearchStatus "$RETIRED_SEARCH_STATUS" \
  --argjson retiredChatStatus "$RETIRED_CHAT_STATUS" \
  --argjson retiredOpenAiStatus "$RETIRED_OPENAI_STATUS" \
  --argjson purgePreviewOperationCount "$PURGE_PREVIEW_OPERATION_COUNT" \
  --argjson purgeApplyOperationCount "$PURGE_APPLY_OPERATION_COUNT" \
  --argjson purgePreviewCollectionCount "$PURGE_PREVIEW_COLLECTION_COUNT" \
  --argjson purgeApplyCollectionCount "$PURGE_APPLY_COLLECTION_COUNT" \
  '{
    generatedAt: $generatedAt,
    status: "PASS",
    passes: $passes,
    identifiers: {
      collectionId: $collectionId,
      documentId: $documentId,
      embeddingJobId: $jobId
    },
    embedding: {
      startedBySpringEventBeforeRecoveryScan: true,
      finalStatus: "SUCCEEDED",
      readinessFresh: true
    },
    retrieval: {
      vectorOnlyMatched: true,
      sourceDocumentIds: $searchDocumentIds
    },
    nativeChat: {
      model: $chatModel,
      answerLength: $chatAnswerLength,
      expectedFactsMatched: true,
      sourceDocumentIds: $chatSourceDocumentIds
    },
    followUpChat: {
      model: $followUpModel,
      answerLength: $followUpAnswerLength,
      expectedFactsMatched: true,
      sourceDocumentIds: $followUpSourceDocumentIds
    },
    openAiCompatibleChat: {
      model: $openAiModel,
      answerLength: $openAiAnswerLength,
      expectedFactsMatched: true
    },
    purge: {
      status: $purgeStatus,
      purgedDocumentCount: $purgedDocumentCount,
      replayMatched: true,
      retiredSearchStatus: $retiredSearchStatus,
      retiredChatStatus: $retiredChatStatus,
      retiredOpenAiStatus: $retiredOpenAiStatus,
      defaultScopeExcluded: true,
      tombstoneOnly: true
    },
    observability: {
      purgePreviewOperationCount: $purgePreviewOperationCount,
      purgeApplyOperationCount: $purgeApplyOperationCount,
      purgePreviewCollectionCount: $purgePreviewCollectionCount,
      purgeApplyCollectionCount: $purgeApplyCollectionCount
    }
  }' >"$WORK_DIR/summary.json"
chmod 600 "$WORK_DIR/summary.json"

echo
echo "REAL_COLLECTION_PURGE_E2E_OK passes=${PASS_COUNT} collectionId=${COLLECTION_ID} documentId=${DOC_ID}"
