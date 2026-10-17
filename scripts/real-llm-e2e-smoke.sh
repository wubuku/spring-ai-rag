#!/usr/bin/env bash
# =============================================================================
# Real LLM End-to-End smoke (NOT mock)
#
# Pipeline against a RUNNING backend (default from start-real-e2e-server.sh):
#   1) health
#   2) preflight embedding key (SiliconFlow)
#   3) preflight the configured chat provider (MiniMax / Anthropic-gateway /
#      OpenAI-compatible)
#   4) create an isolated collection + document with a unique verification code
#   5) embed (real Embedding API)
#   6) search (real vector/fulltext)
#   7) chat/ask (real Chat LLM) — answer must contain the code
#   8) chat/ask mode=AGENT (real tool-calling LLM) — tool/source handles must align
#   9) chat/stream (optional) — stream must contain the code
#
# Prerequisites:
#   - PostgreSQL + pgvector
#   - .env keys matching start-real-e2e-server.sh defaults:
#       RAG_EMBEDDING_API_KEY (+ optional RAG_EMBEDDING_BASE_URL/MODEL)
#       LLM_PROVIDER=minimax: SPRING_AI_MINIMAX_API_KEY + ...
#       LLM_PROVIDER=anthropic: ANTHROPIC_API_KEY + ...
#       LLM_PROVIDER=openai: SPRING_AI_OPENAI_API_KEY + ...
#   - Backend: ./scripts/start-real-e2e-server.sh
#
# Usage:
#   BASE_URL=http://127.0.0.1:18081 ./scripts/real-llm-e2e-smoke.sh
#   ./scripts/real-llm-e2e-smoke.sh --skip-stream
#
# Exit: 0=pass, 1=step fail, 2=preflight fail
# =============================================================================
set -euo pipefail
cd "$(dirname "$0")/.."

BASE_URL="${BASE_URL:-http://127.0.0.1:18081}"
API="${BASE_URL}/api/v1/rag"
SKIP_STREAM=0
for arg in "$@"; do
  case "$arg" in
    --skip-stream) SKIP_STREAM=1 ;;
  esac
done

# Preserve caller overrides
_PRESERVE_LLM_PROVIDER="${LLM_PROVIDER-}"
_PRESERVE_APP_LLM_PROVIDER="${APP_LLM_PROVIDER-}"
_PRESERVE_REAL_LLM_CHAT_PROVIDER="${REAL_LLM_CHAT_PROVIDER-}"
_PRESERVE_EMBEDDING_KEY="${RAG_EMBEDDING_API_KEY-}"
_PRESERVE_EMBEDDING_BASE="${RAG_EMBEDDING_BASE_URL-}"
_PRESERVE_EMBEDDING_MODEL="${RAG_EMBEDDING_MODEL-}"
_PRESERVE_EMBEDDING_DIMENSIONS="${RAG_EMBEDDING_DIMENSIONS-}"
_PRESERVE_MM_KEY="${SPRING_AI_MINIMAX_API_KEY-}"
_PRESERVE_MM_BASE="${SPRING_AI_MINIMAX_BASE_URL-}"
_PRESERVE_MM_MODEL="${SPRING_AI_MINIMAX_CHAT_OPTIONS_MODEL-}"
_PRESERVE_OPENAI_KEY="${SPRING_AI_OPENAI_API_KEY-}"
_PRESERVE_OPENAI_BASE="${SPRING_AI_OPENAI_BASE_URL-}"
_PRESERVE_OPENAI_MODEL="${SPRING_AI_OPENAI_CHAT_OPTIONS_MODEL-}"
_PRESERVE_ANTH_KEY="${ANTHROPIC_API_KEY-}"
_PRESERVE_ANTH_BASE="${ANTHROPIC_BASE_URL-}"
_PRESERVE_ANTH_MODEL="${ANTHROPIC_MODEL-}"
_PRESERVE_ROOT_KEY="${RAG_ROOT_API_KEY-}"

if [[ -f .env ]]; then
  # shellcheck disable=SC2046
  export $(grep -v '^#' .env | grep -v '^$' | xargs) || true
fi

[[ -n "${_PRESERVE_LLM_PROVIDER}" ]] && export LLM_PROVIDER="${_PRESERVE_LLM_PROVIDER}"
[[ -n "${_PRESERVE_APP_LLM_PROVIDER}" ]] && export APP_LLM_PROVIDER="${_PRESERVE_APP_LLM_PROVIDER}"
[[ -n "${_PRESERVE_REAL_LLM_CHAT_PROVIDER}" ]] && export REAL_LLM_CHAT_PROVIDER="${_PRESERVE_REAL_LLM_CHAT_PROVIDER}"
[[ -n "${_PRESERVE_EMBEDDING_KEY}" ]] && export RAG_EMBEDDING_API_KEY="${_PRESERVE_EMBEDDING_KEY}"
[[ -n "${_PRESERVE_EMBEDDING_BASE}" ]] && export RAG_EMBEDDING_BASE_URL="${_PRESERVE_EMBEDDING_BASE}"
[[ -n "${_PRESERVE_EMBEDDING_MODEL}" ]] && export RAG_EMBEDDING_MODEL="${_PRESERVE_EMBEDDING_MODEL}"
[[ -n "${_PRESERVE_EMBEDDING_DIMENSIONS}" ]] && export RAG_EMBEDDING_DIMENSIONS="${_PRESERVE_EMBEDDING_DIMENSIONS}"
[[ -n "${_PRESERVE_MM_KEY}" ]] && export SPRING_AI_MINIMAX_API_KEY="${_PRESERVE_MM_KEY}"
[[ -n "${_PRESERVE_MM_BASE}" ]] && export SPRING_AI_MINIMAX_BASE_URL="${_PRESERVE_MM_BASE}"
[[ -n "${_PRESERVE_MM_MODEL}" ]] && export SPRING_AI_MINIMAX_CHAT_OPTIONS_MODEL="${_PRESERVE_MM_MODEL}"
[[ -n "${_PRESERVE_OPENAI_KEY}" ]] && export SPRING_AI_OPENAI_API_KEY="${_PRESERVE_OPENAI_KEY}"
[[ -n "${_PRESERVE_OPENAI_BASE}" ]] && export SPRING_AI_OPENAI_BASE_URL="${_PRESERVE_OPENAI_BASE}"
[[ -n "${_PRESERVE_OPENAI_MODEL}" ]] && export SPRING_AI_OPENAI_CHAT_OPTIONS_MODEL="${_PRESERVE_OPENAI_MODEL}"
[[ -n "${_PRESERVE_ANTH_KEY}" ]] && export ANTHROPIC_API_KEY="${_PRESERVE_ANTH_KEY}"
[[ -n "${_PRESERVE_ANTH_BASE}" ]] && export ANTHROPIC_BASE_URL="${_PRESERVE_ANTH_BASE}"
[[ -n "${_PRESERVE_ANTH_MODEL}" ]] && export ANTHROPIC_MODEL="${_PRESERVE_ANTH_MODEL}"
[[ -n "${_PRESERVE_ROOT_KEY}" ]] && export RAG_ROOT_API_KEY="${_PRESERVE_ROOT_KEY}"

ROOT_API_KEY="${RAG_ROOT_API_KEY:-${RAG_API_KEY:-}}"
API_AUTH_ARGS=()
if [[ -n "$ROOT_API_KEY" ]]; then
  API_AUTH_ARGS=(-H "X-API-Key: ${ROOT_API_KEY}")
fi

export PROBE_TOKEN="REAL_E2E_$(date +%s)_$RANDOM"
PASS=0
FAIL=0
DOC_ID=""
DOC_REVISION=""
COLLECTION_ID=""
COLLECTION_KEY="real-e2e-${PROBE_TOKEN}"
COLLECTION_CREATED=0
DOCUMENT_CREATED=0
RUN_DIR="${REAL_LLM_E2E_RUN_DIR:-.verification/real-llm-e2e/${PROBE_TOKEN}}"
mkdir -p "$RUN_DIR"
TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/spring-ai-rag-real-e2e.XXXXXX")"
step() { echo; echo "=== $* ==="; }
ok()   { echo "OK: $*"; PASS=$((PASS+1)); }
bad()  { echo "FAIL: $*"; FAIL=$((FAIL+1)); }
need() { command -v "$1" >/dev/null || { echo "need $1"; exit 2; }; }

cleanup() {
  local original_rc="$1"
  local cleanup_failed=0
  local document_cleanup_verified=0
  set +e

  if [[ "$DOCUMENT_CREATED" -eq 0 ]]; then
    document_cleanup_verified=1
  fi

  if [[ "$DOCUMENT_CREATED" -eq 1 && -n "$DOC_ID" ]]; then
    local latest_revision
    latest_revision="$(curl -sS ${API_AUTH_ARGS[@]+"${API_AUTH_ARGS[@]}"} \
      -o "$TMP_DIR/document-before-cleanup.json" \
      -w '%{http_code}' "$API/documents/$DOC_ID" 2>"$TMP_DIR/document-before-cleanup.err")"
    if [[ "$latest_revision" == "200" ]]; then
      DOC_REVISION="$(python3 - "$TMP_DIR/document-before-cleanup.json" <<'PY'
import json
import sys

with open(sys.argv[1], encoding='utf-8') as stream:
    value = json.load(stream).get('documentRevision')
if not isinstance(value, int) or value <= 0:
    raise SystemExit(1)
print(value)
PY
      )"
    elif [[ "$latest_revision" != "404" ]]; then
      echo "CLEANUP FAIL: GET document $DOC_ID returned HTTP $latest_revision" >&2
      cleanup_failed=1
    fi

    if [[ -n "$DOC_REVISION" ]]; then
      local document_code
      document_code="$(curl -sS ${API_AUTH_ARGS[@]+"${API_AUTH_ARGS[@]}"} \
        -X DELETE "$API/documents/$DOC_ID?expectedDocumentRevision=$DOC_REVISION" \
        -o "$TMP_DIR/delete-document.json" -w '%{http_code}' \
        2>"$TMP_DIR/delete-document.err")"
      if [[ "$document_code" != "200" && "$document_code" != "404" ]]; then
        echo "CLEANUP FAIL: DELETE document $DOC_ID returned HTTP $document_code" >&2
        cleanup_failed=1
      fi
    fi

    local after_delete_code
    after_delete_code="$(curl -sS ${API_AUTH_ARGS[@]+"${API_AUTH_ARGS[@]}"} \
      -o "$TMP_DIR/document-after-cleanup.json" \
      -w '%{http_code}' "$API/documents/$DOC_ID" 2>"$TMP_DIR/document-after-cleanup.err")"
    if [[ "$after_delete_code" != "404" ]]; then
      echo "CLEANUP FAIL: document $DOC_ID still visible after hard delete (HTTP $after_delete_code)" >&2
      cleanup_failed=1
    fi

    local enabled_documents
    enabled_documents="$(curl -sS ${API_AUTH_ARGS[@]+"${API_AUTH_ARGS[@]}"} \
      -o "$TMP_DIR/enabled-documents.json" \
      -w '%{http_code}' "$API/documents?enabled=true&offset=0&limit=1000" \
      2>"$TMP_DIR/enabled-documents.err")"
    if [[ "$enabled_documents" == "200" ]]; then
      if ! python3 - "$TMP_DIR/enabled-documents.json" "$DOC_ID" <<'PY'
import json
import sys

with open(sys.argv[1], encoding='utf-8') as stream:
    payload = json.load(stream)
target = int(sys.argv[2])
items = payload.get('content', payload.get('items', payload if isinstance(payload, list) else []))
if not isinstance(items, list):
    raise SystemExit(1)
for item in items:
    if isinstance(item, dict) and item.get('id') == target:
        raise SystemExit(1)
PY
      then
        echo "CLEANUP FAIL: document $DOC_ID remains in enabled document listing" >&2
        cleanup_failed=1
      fi
    else
      echo "CLEANUP FAIL: enabled document listing returned HTTP $enabled_documents" >&2
      cleanup_failed=1
    fi
    if [[ "$after_delete_code" == "404" && "$enabled_documents" == "200" ]]; then
      if python3 - "$TMP_DIR/enabled-documents.json" "$DOC_ID" <<'PY'
import json
import sys

with open(sys.argv[1], encoding='utf-8') as stream:
    payload = json.load(stream)
target = int(sys.argv[2])
items = payload.get('content', payload.get('items', payload if isinstance(payload, list) else []))
if not isinstance(items, list):
    raise SystemExit(1)
raise SystemExit(0 if not any(
    isinstance(item, dict) and item.get('id') == target for item in items
) else 1)
PY
      then
        document_cleanup_verified=1
      fi
    fi
  fi

  if [[ "$COLLECTION_CREATED" -eq 1 && -n "$COLLECTION_KEY" ]]; then
    if [[ "$document_cleanup_verified" -ne 1 ]]; then
      echo "CLEANUP HOLD: Collection $COLLECTION_KEY retained because document cleanup was not verified" >&2
      cleanup_failed=1
    else
      local collection_code
      collection_code="$(curl -sS ${API_AUTH_ARGS[@]+"${API_AUTH_ARGS[@]}"} -X DELETE \
        "$API/collections/by-key?collectionKey=$COLLECTION_KEY" \
        -o "$TMP_DIR/delete-collection.json" -w '%{http_code}' \
        2>"$TMP_DIR/delete-collection.err")"
      if [[ "$collection_code" != "200" && "$collection_code" != "404" ]]; then
        echo "CLEANUP FAIL: DELETE collection $COLLECTION_KEY returned HTTP $collection_code" >&2
        cleanup_failed=1
      fi
    fi
  fi

  if [[ "$cleanup_failed" -eq 1 ]]; then
    echo "Cleanup evidence retained in $RUN_DIR (run=$PROBE_TOKEN)" >&2
    cp -R "$TMP_DIR" "$RUN_DIR/cleanup" 2>/dev/null || true
  fi
  rm -rf "$TMP_DIR"
  if [[ "$cleanup_failed" -eq 1 ]]; then
    return 1
  fi
  return "$original_rc"
}
trap 'rc=$?; cleanup "$rc"; exit $?' EXIT

need curl
need python3

step "0) Preflight health @ $BASE_URL"
if ! curl -sf "$BASE_URL/actuator/health" >/tmp/rag-health.json; then
  echo "Server not reachable at $BASE_URL"
  echo "Start with: ./scripts/start-real-e2e-server.sh"
  exit 2
fi
python3 - <<'PY'
import json
d=json.load(open('/tmp/rag-health.json'))
assert d.get('status')=='UP', d
print('health status UP')
PY
ok "health UP"

step "0b) Probe configured embedding API"
EMB_KEY="${RAG_EMBEDDING_API_KEY:-}"
EMB_BASE="${RAG_EMBEDDING_BASE_URL:-}"
EMB_MODEL="${RAG_EMBEDDING_MODEL:-}"
EMB_DIMENSIONS="${RAG_EMBEDDING_DIMENSIONS:-}"
embedding_problems=()
if [[ -z "$EMB_KEY" ]]; then
  embedding_problems+=("RAG_EMBEDDING_API_KEY is missing")
  [[ -n "${SILICONFLOW_API_KEY:-}" ]] && \
    embedding_problems+=("SILICONFLOW_API_KEY is retired; rename it to RAG_EMBEDDING_API_KEY")
fi
if [[ -z "$EMB_BASE" ]]; then
  embedding_problems+=("RAG_EMBEDDING_BASE_URL is missing")
  [[ -n "${RAG_EMBEDDING_URL:-}" ]] && \
    embedding_problems+=("RAG_EMBEDDING_URL is retired; rename it to RAG_EMBEDDING_BASE_URL")
  [[ -z "${RAG_EMBEDDING_URL:-}" && -n "${SILICONFLOW_URL:-}" ]] && \
    embedding_problems+=("SILICONFLOW_URL is retired; rename it to RAG_EMBEDDING_BASE_URL")
fi
[[ -n "$EMB_MODEL" ]] || embedding_problems+=("RAG_EMBEDDING_MODEL is missing")
[[ "$EMB_DIMENSIONS" =~ ^[1-9][0-9]*$ ]] || \
  embedding_problems+=("RAG_EMBEDDING_DIMENSIONS must be a positive integer")
if [[ -n "$EMB_BASE" && "$EMB_BASE" =~ /v1/?$ ]]; then
  embedding_problems+=("RAG_EMBEDDING_BASE_URL must not end with /v1")
fi
if (( ${#embedding_problems[@]} > 0 )); then
  echo "Embedding configuration is incomplete:" >&2
  printf '  - %s\n' "${embedding_problems[@]}" >&2
  echo "Set the four RAG_EMBEDDING_* variables before running real E2E." >&2
  exit 2
fi
EMB_BASE=$(echo "$EMB_BASE" | sed 's|/$||; s|/v1$||')
CODE=$(curl -s -o /tmp/rag-emb-probe.json -w "%{http_code}" \
  -X POST "${EMB_BASE}/v1/embeddings" \
  -H "Authorization: Bearer ${EMB_KEY}" \
  -H "Content-Type: application/json" \
  -d "{\"model\":\"${EMB_MODEL}\",\"input\":\"probe\"}" || true)
if [[ "$CODE" != "200" ]]; then
  echo "Embedding API HTTP $CODE: $(head -c 200 /tmp/rag-emb-probe.json)"
  echo "Update RAG_EMBEDDING_API_KEY in .env"
  exit 2
fi
ok "embedding API HTTP 200 (${EMB_BASE} ${EMB_MODEL})"

PROVIDER="${REAL_LLM_CHAT_PROVIDER:-${LLM_PROVIDER:-${APP_LLM_PROVIDER:-openai}}}"
PROVIDER="$(printf '%s' "$PROVIDER" | tr '[:upper:]' '[:lower:]')"
step "0c) Probe configured chat API provider=${PROVIDER}"
CHAT_OK=0
case "$PROVIDER" in
  minimax)
    CHAT_KEY="${SPRING_AI_MINIMAX_API_KEY:-${MINIMAX_API_KEY:-}}"
    CHAT_BASE=$(echo "${SPRING_AI_MINIMAX_BASE_URL:-${MINIMAX_BASE_URL:-https://api.minimaxi.com}}" | sed 's|/$||; s|/v1$||')
    CHAT_MODEL="${SPRING_AI_MINIMAX_CHAT_OPTIONS_MODEL:-${MINIMAX_MODEL:-MiniMax-M3}}"
    CHAT_URL="${CHAT_BASE}/v1/chat/completions"
    CHAT_BODY="{\"model\":\"${CHAT_MODEL}\",\"messages\":[{\"role\":\"user\",\"content\":\"ping\"}],\"max_tokens\":8,\"temperature\":0.1}"
    ;;
  openai)
    CHAT_KEY="${SPRING_AI_OPENAI_API_KEY:-${OPENAI_API_KEY:-}}"
    CHAT_BASE=$(echo "${SPRING_AI_OPENAI_BASE_URL:-${OPENAI_BASE_URL:-https://api.siliconflow.cn}}" | sed 's|/$||; s|/v1$||')
    CHAT_MODEL="${SPRING_AI_OPENAI_CHAT_OPTIONS_MODEL:-${OPENAI_MODEL:-Qwen/Qwen2.5-7B-Instruct}}"
    CHAT_URL="${CHAT_BASE}/v1/chat/completions"
    CHAT_BODY="{\"model\":\"${CHAT_MODEL}\",\"messages\":[{\"role\":\"user\",\"content\":\"ping\"}],\"max_tokens\":5}"
    ;;
  anthropic)
    CHAT_KEY="${ANTHROPIC_API_KEY:-${SPRING_AI_MINIMAX_API_KEY:-}}"
    CHAT_BASE=$(echo "${ANTHROPIC_BASE_URL:-https://api.minimaxi.com/anthropic}" | sed 's|/$||')
    CHAT_MODEL="${ANTHROPIC_MODEL:-${SPRING_AI_MINIMAX_CHAT_OPTIONS_MODEL:-MiniMax-M3}}"
    CHAT_URL="${CHAT_BASE}/v1/messages"
    CHAT_BODY="{\"model\":\"${CHAT_MODEL}\",\"max_tokens\":8,\"messages\":[{\"role\":\"user\",\"content\":\"ping\"}]}"
    ;;
  *)
    echo "Unsupported LLM_PROVIDER '${PROVIDER}'" >&2
    exit 2
    ;;
esac
if [[ -z "${CHAT_KEY:-}" ]]; then
  echo "Configured provider '${PROVIDER}' has no chat API key" >&2
  exit 2
fi
if [[ "$PROVIDER" == "anthropic" ]]; then
  CODE=$(curl -s -o /tmp/rag-chat-probe.json -w "%{http_code}" \
    -X POST "$CHAT_URL" \
    --max-time "${REAL_LLM_PREFLIGHT_TIMEOUT_SECONDS:-60}" \
    -H "x-api-key: ${CHAT_KEY}" \
    -H "anthropic-version: 2023-06-01" \
    -H "Content-Type: application/json" \
    -d "$CHAT_BODY" || true)
else
  CODE=$(curl -s -o /tmp/rag-chat-probe.json -w "%{http_code}" \
    -X POST "$CHAT_URL" \
    --max-time "${REAL_LLM_PREFLIGHT_TIMEOUT_SECONDS:-60}" \
    -H "Authorization: Bearer ${CHAT_KEY}" \
    -H "Content-Type: application/json" \
    -d "$CHAT_BODY" || true)
fi
echo "${PROVIDER} probe ${CHAT_BASE} ${CHAT_MODEL} key_len=${#CHAT_KEY} -> HTTP $CODE"
if [[ "$CODE" == "200" ]]; then
  CHAT_OK=1
  CHAT_DESC="${PROVIDER} ${CHAT_MODEL}"
else
  echo "Chat provider response: $(head -c 300 /tmp/rag-chat-probe.json)" >&2
fi
if [[ "$CHAT_OK" != "1" ]]; then
  echo "Configured chat provider '${PROVIDER}' is unavailable" >&2
  exit 2
fi
ok "chat API HTTP 200 (${CHAT_DESC})"

step "1) Create isolated collection and document with verification code=$PROBE_TOKEN"
curl -s "${API_AUTH_ARGS[@]}" -X POST "$API/collections" \
  -H 'Content-Type: application/json' \
  -d "{\"collectionKey\":\"${COLLECTION_KEY}\",\"name\":\"real-llm-e2e-${PROBE_TOKEN}\",\"description\":\"Isolated real LLM release verification collection\",\"domainId\":\"default\"}" \
  -o /tmp/rag-e2e-collection.json
COLLECTION_ID=$(python3 -c "import json; print(json.load(open('/tmp/rag-e2e-collection.json')).get('id') or '')")
RETURNED_COLLECTION_KEY=$(python3 -c "import json; print(json.load(open('/tmp/rag-e2e-collection.json')).get('collectionKey') or '')")
[[ -n "$COLLECTION_ID" && "$RETURNED_COLLECTION_KEY" == "$COLLECTION_KEY" ]] \
  || { bad "create collection or collectionKey mismatch"; exit 1; }
COLLECTION_CREATED=1
ok "collection key=$COLLECTION_KEY id=$COLLECTION_ID"

curl -s "${API_AUTH_ARGS[@]}" -X POST "$API/documents" \
  -H 'Content-Type: application/json' \
  -d "{\"title\":\"Real LLM E2E ${PROBE_TOKEN}\",\"content\":\"This is automated release verification test data. The release verification code is ${PROBE_TOKEN}. The code may be repeated verbatim when requested.\",\"collectionKey\":\"${COLLECTION_KEY}\"}" \
  -o /tmp/rag-e2e-create.json
head -c 400 /tmp/rag-e2e-create.json; echo
DOC_ID=$(python3 -c "import json; print(json.load(open('/tmp/rag-e2e-create.json')).get('id') or '')")
DOC_REVISION=$(python3 -c "import json; print(json.load(open('/tmp/rag-e2e-create.json')).get('documentRevision') or '')")
[[ -n "$DOC_ID" && -n "$DOC_REVISION" ]] || { bad "create document or documentRevision missing"; exit 1; }
DOCUMENT_CREATED=1
export DOC_ID DOC_REVISION
ok "document id=$DOC_ID revision=$DOC_REVISION"

curl -s "${API_AUTH_ARGS[@]}" -X POST \
  "$API/collections/by-key/documents?collectionKey=$COLLECTION_KEY" \
  -H 'Content-Type: application/json' \
  -d "{\"documentId\":$DOC_ID,\"expectedDocumentRevision\":$DOC_REVISION}" \
  -o /tmp/rag-e2e-associate.json
python3 - <<'PY'
import json, os, sys
d = json.load(open('/tmp/rag-e2e-associate.json'))
expected = int(os.environ['DOC_ID'])
actual = int(d.get('documentId') or 0)
if actual != expected:
    print('collection association failed', d)
    sys.exit(1)
print('associated document', actual)
PY
ok "document associated with isolated collection"

step "2) Embed document (real embedding)"
curl -s "${API_AUTH_ARGS[@]}" -X POST \
  "$API/documents/${DOC_ID}/embed?force=true" -o /tmp/rag-e2e-embed.json
head -c 400 /tmp/rag-e2e-embed.json; echo
python3 - <<'PY'
import json,sys
d=json.load(open('/tmp/rag-e2e-embed.json'))
status=str(d.get('status','')).upper()
stored=int(d.get('embeddingsStored') or d.get('chunksCreated') or 0)
if status in ('FAILED','ERROR') or stored<=0:
    print('embed failed', d)
    sys.exit(1)
print('embedded', stored)
PY
ok "embed stored vectors"

step "3) Search for probe token"
curl -s "${API_AUTH_ARGS[@]}" -X POST "$API/search" \
  -H 'Content-Type: application/json' \
  -d "{\"query\":\"${PROBE_TOKEN}\",\"collectionKeys\":[\"${COLLECTION_KEY}\"],\"documentIds\":[$DOC_ID],\"config\":{\"maxResults\":5,\"minScore\":0,\"useHybridSearch\":true,\"useRerank\":true}}" \
  -o /tmp/rag-e2e-search.json
python3 - <<'PY'
import json,sys,os
d=json.load(open('/tmp/rag-e2e-search.json'))
if isinstance(d, list):
    results=d
else:
    results=d.get('results') or d.get('data') or []
token=os.environ['PROBE_TOKEN']
expected=int(os.environ['DOC_ID'])
if not results:
    print('no search hits')
    sys.exit(1)
print('hits', len(results), 'top', results[0].get('documentId'))
if any(int(r.get('documentId') or r.get('id') or 0) != expected for r in results):
    print('search escaped isolated document scope', results)
    sys.exit(1)
if not any(token in (r.get('chunkText') or '') for r in results):
    print('verification code not found in hit texts')
    sys.exit(1)
print('verification code found in isolated search hits')
PY
ok "search returned only the isolated document"

step "4) Chat ask (REAL LLM)"
curl -s "${API_AUTH_ARGS[@]}" -X POST "$API/chat/ask" \
  -H 'Content-Type: application/json' \
  -d "{\"message\":\"Using only the selected release-verification document, return the release verification code exactly. The code begins with REAL_E2E_.\",\"maxResults\":5,\"useHybridSearch\":true,\"useRerank\":true,\"collectionKeys\":[\"${COLLECTION_KEY}\"],\"documentIds\":[$DOC_ID]}" \
  --max-time 180 \
  -o /tmp/rag-e2e-ask.json
set +e
python3 - <<'PY'
import json,os,sys
d=json.load(open('/tmp/rag-e2e-ask.json'))
a=d.get('answer') or ''
token=os.environ['PROBE_TOKEN']
print('answer_len', len(a))
print(a[:500])
print('sources', len(d.get('sources') or []))
print('trace', d.get('traceId'))
print('TOKEN_IN_ANSWER', token in a)
assessment=d.get('evidenceAssessment') or {}
status=assessment.get('status')
safe_applied=assessment.get('safeAnswerApplied') is True
if safe_applied:
    print('EVIDENCE_SAFETY_STATUS', status)
    print('SAFE_ANSWER_APPLIED', safe_applied)
    sys.exit(0 if status != 'SUPPORTED' and a and d.get('sources') else 1)
sys.exit(0 if token in a else 1)
PY
ask_rc=$?
set -e
if [[ $ask_rc -eq 0 ]]; then
  if [[ "$(python3 - <<'PY'
import json
d=json.load(open('/tmp/rag-e2e-ask.json'))
print('safe' if (d.get('evidenceAssessment') or {}).get('safeAnswerApplied') is True else 'answer')
PY
)" == "safe" ]]; then
    ok "chat evidence safety gate applied a controlled answer"
  else
    ok "chat answer contains verification code"
  fi
else
  bad "chat answer missing verification code or invalid evidence assessment"
fi

step "5) Agent chat with citation registry (REAL LLM, bounded)"
set +e
curl -s "${API_AUTH_ARGS[@]}" -X POST "$API/chat/ask" \
  -H 'Content-Type: application/json' \
  -d "{\"mode\":\"AGENT\",\"message\":\"Use the knowledge retrieval tool for the selected release-verification document. Return the release verification code exactly and cite the retrieved evidence with the provided citation handle. The code begins with REAL_E2E_.\",\"maxResults\":5,\"useHybridSearch\":true,\"useRerank\":true,\"collectionKeys\":[\"${COLLECTION_KEY}\"],\"documentIds\":[$DOC_ID]}" \
  --max-time 180 \
  -o /tmp/rag-e2e-agent.json
agent_curl_rc=$?
agent_rc=$agent_curl_rc
if [[ $agent_curl_rc -eq 0 ]]; then
  python3 - <<'PY'
import json
import os
import sys

try:
    data = json.load(open('/tmp/rag-e2e-agent.json'))
except Exception as exc:
    print('agent response is not valid JSON:', exc)
    sys.exit(1)

if data.get('error') == 'MODEL_CAPABILITY_UNSUPPORTED':
    print('agent capability is not configured for the selected provider')
    sys.exit(2)

token = os.environ['PROBE_TOKEN']
answer = data.get('answer') or ''
sources = data.get('sources') or []
metadata = data.get('metadata') or {}
registry = metadata.get('citationRegistry') or {}
handles = registry.get('handles') or []
transcript = metadata.get('toolTranscript') or []
source_handles = [source.get('sourceHandle') for source in sources]
source_citations = [source.get('citationId') for source in sources]
transcript_text = json.dumps(transcript, ensure_ascii=False)

print('agent_answer_len', len(answer))
print('agent_sources', len(sources))
print('agent_tool_calls', len(transcript))
print('agent_registry_handles', handles)
if not sources:
    print('agent response has no sources')
    sys.exit(1)
if any(not value for value in source_handles + source_citations):
    print('agent sources must contain both sourceHandle and citationId', sources)
    sys.exit(1)
if set(source_handles) != set(handles):
    print('registry handles do not match source handles', handles, source_handles)
    sys.exit(1)
if not transcript or 'sourceHandle' not in transcript_text or 'citationId' not in transcript_text:
    print('toolTranscript does not prove a bounded tool result with both citation identifiers')
    sys.exit(1)
if any(handle not in transcript_text for handle in source_handles):
    print('toolTranscript does not contain every exposed source handle')
    sys.exit(1)
if token not in answer:
    print('agent answer missing verification code')
    sys.exit(1)
print('agent answer and tool/source citation registry validated')
PY
  agent_rc=$?
fi
set -e
if [[ $agent_rc -eq 2 ]]; then
  echo "SKIP: agent tool/result citation validation (selected provider has no AGENT capability)"
  agent_rc=0
fi
if [[ $agent_rc -eq 0 ]]; then
  ok "agent tool result and citation registry are aligned or capability-gated"
else
  bad "agent tool/result citation validation failed"
fi

if [[ "$SKIP_STREAM" != "1" ]]; then
  step "6) Chat stream (REAL LLM, sample)"
  set +e
  curl -s -N "${API_AUTH_ARGS[@]}" -X POST "$API/chat/stream" \
    -H 'Content-Type: application/json' \
    -d "{\"message\":\"Using only the selected release-verification document, return the release verification code exactly. The code begins with REAL_E2E_.\",\"maxResults\":5,\"useHybridSearch\":true,\"useRerank\":true,\"collectionKeys\":[\"${COLLECTION_KEY}\"],\"documentIds\":[$DOC_ID]}" \
    --max-time 180 \
    -o /tmp/rag-e2e-stream.txt
  python3 - <<'PY'
import json
import os

token=os.environ['PROBE_TOKEN']
t=open('/tmp/rag-e2e-stream.txt', errors='replace').read()
chunks=[]
data_events=0
done_assessment={}
for line in t.splitlines():
    if not line.startswith('data:'):
        continue
    payload=line[5:].strip()
    if not payload or payload == '[DONE]':
        continue
    data_events += 1
    try:
        event=json.loads(payload)
    except json.JSONDecodeError:
        continue
    if isinstance(event.get('evidenceAssessment'), dict):
        done_assessment=event['evidenceAssessment']
    for choice in event.get('choices') or []:
        delta=choice.get('delta') or {}
        content=delta.get('content')
        if isinstance(content, str):
            chunks.append(content)

stream_content=''.join(chunks)
print('stream_len', len(t))
print(t[:400])
print('data_events', data_events)
print('delta_chunks', len(chunks))
print('stream_content', stream_content[:400])
print('TOKEN_IN_STREAM', token in stream_content)
print('EVIDENCE_SAFETY_STATUS', done_assessment.get('status'))
print('SAFE_ANSWER_APPLIED', done_assessment.get('safeAnswerApplied'))
looks_sse = data_events > 0 and len(chunks) > 0
safe_answer = done_assessment.get('safeAnswerApplied') is True
answer_verified = token in stream_content or (
    safe_answer and done_assessment.get('status') != 'SUPPORTED')
raise SystemExit(0 if looks_sse and answer_verified else 1)
PY
stream_rc=$?
set -e
if [[ $stream_rc -eq 0 ]]; then
  ok "stream returned SSE chunks with verification code"
else
  bad "stream missing expected SSE chunks or verification code"
fi
fi

echo
echo "=============================="
echo "PASS=$PASS FAIL=$FAIL probe=$PROBE_TOKEN base=$BASE_URL"
echo "=============================="
if [[ "$FAIL" -gt 0 ]]; then exit 1; fi
exit 0
