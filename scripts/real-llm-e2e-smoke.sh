#!/usr/bin/env bash
# =============================================================================
# Real LLM End-to-End smoke (NOT mock)
#
# Pipeline against a RUNNING backend (default from start-real-e2e-server.sh):
#   1) health
#   2) preflight embedding key (SiliconFlow)
#   3) preflight chat key (MiniMax / Anthropic-gateway / OpenAI-compat)
#   4) create an isolated collection + document with a unique verification code
#   5) embed (real Embedding API)
#   6) search (real vector/fulltext)
#   7) chat/ask (real Chat LLM) — answer must contain the code
#   8) chat/stream (optional) — stream must contain the code
#
# Prerequisites:
#   - PostgreSQL + pgvector
#   - .env keys matching start-real-e2e-server.sh defaults:
#       SILICONFLOW_API_KEY (+ optional SILICONFLOW_URL/MODEL)
#       SPRING_AI_MINIMAX_API_KEY + SPRING_AI_MINIMAX_BASE_URL + MiniMax-M3
#         (or ANTHROPIC_* for MiniMax Anthropic-compatible gateway)
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
_PRESERVE_SF_KEY="${SILICONFLOW_API_KEY-}"
_PRESERVE_MM_KEY="${SPRING_AI_MINIMAX_API_KEY-}"
_PRESERVE_OPENAI_KEY="${SPRING_AI_OPENAI_API_KEY-}"
_PRESERVE_ANTH_KEY="${ANTHROPIC_API_KEY-}"

if [[ -f .env ]]; then
  # shellcheck disable=SC2046
  export $(grep -v '^#' .env | grep -v '^$' | xargs) || true
fi

[[ -n "${_PRESERVE_SF_KEY}" ]] && export SILICONFLOW_API_KEY="${_PRESERVE_SF_KEY}"
[[ -n "${_PRESERVE_MM_KEY}" ]] && export SPRING_AI_MINIMAX_API_KEY="${_PRESERVE_MM_KEY}"
[[ -n "${_PRESERVE_OPENAI_KEY}" ]] && export SPRING_AI_OPENAI_API_KEY="${_PRESERVE_OPENAI_KEY}"
[[ -n "${_PRESERVE_ANTH_KEY}" ]] && export ANTHROPIC_API_KEY="${_PRESERVE_ANTH_KEY}"

export PROBE_TOKEN="REAL_E2E_$(date +%s)_$RANDOM"
PASS=0
FAIL=0
DOC_ID=""
COLLECTION_ID=""
COLLECTION_KEY="real-e2e-${PROBE_TOKEN}"
step() { echo; echo "=== $* ==="; }
ok()   { echo "OK: $*"; PASS=$((PASS+1)); }
bad()  { echo "FAIL: $*"; FAIL=$((FAIL+1)); }
need() { command -v "$1" >/dev/null || { echo "need $1"; exit 2; }; }

cleanup() {
  set +e
  if [[ -n "$DOC_ID" ]]; then
    curl -s -X DELETE "$API/documents/$DOC_ID" >/dev/null
  fi
  if [[ -n "$COLLECTION_KEY" ]]; then
    curl -s -X DELETE "$API/collections/by-key?collectionKey=$COLLECTION_KEY" >/dev/null
  fi
}
trap cleanup EXIT

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

step "0b) Probe embedding API (SiliconFlow BGE-M3)"
EMB_KEY="${SILICONFLOW_API_KEY:-}"
EMB_BASE=$(echo "${SILICONFLOW_URL:-https://api.siliconflow.cn}" | sed 's|/$||; s|/v1$||')
EMB_MODEL="${SILICONFLOW_MODEL:-BAAI/bge-m3}"
if [[ -z "$EMB_KEY" ]]; then
  echo "SILICONFLOW_API_KEY empty"
  exit 2
fi
CODE=$(curl -s -o /tmp/rag-emb-probe.json -w "%{http_code}" \
  -X POST "${EMB_BASE}/v1/embeddings" \
  -H "Authorization: Bearer ${EMB_KEY}" \
  -H "Content-Type: application/json" \
  -d "{\"model\":\"${EMB_MODEL}\",\"input\":\"probe\"}" || true)
if [[ "$CODE" != "200" ]]; then
  echo "Embedding API HTTP $CODE: $(head -c 200 /tmp/rag-emb-probe.json)"
  echo "Update SILICONFLOW_API_KEY in .env"
  exit 2
fi
ok "embedding API HTTP 200 (${EMB_BASE} ${EMB_MODEL})"

step "0c) Probe chat API (MiniMax preferred, then Anthropic-gateway, then OpenAI-compat)"
CHAT_OK=0
# 1) MiniMax OpenAI-compatible
MM_KEY="${SPRING_AI_MINIMAX_API_KEY:-${MINIMAX_API_KEY:-}}"
MM_BASE=$(echo "${SPRING_AI_MINIMAX_BASE_URL:-${MINIMAX_BASE_URL:-https://api.minimaxi.com}}" | sed 's|/$||; s|/v1$||')
MM_MODEL="${SPRING_AI_MINIMAX_CHAT_OPTIONS_MODEL:-${MINIMAX_MODEL:-MiniMax-M3}}"
if [[ -n "$MM_KEY" ]]; then
  CODE=$(curl -s -o /tmp/rag-chat-probe.json -w "%{http_code}" \
    -X POST "${MM_BASE}/v1/chat/completions" \
    -H "Authorization: Bearer ${MM_KEY}" \
    -H "Content-Type: application/json" \
    -d "{\"model\":\"${MM_MODEL}\",\"messages\":[{\"role\":\"user\",\"content\":\"ping\"}],\"max_tokens\":8,\"temperature\":0.1}" || true)
  echo "minimax probe ${MM_BASE} ${MM_MODEL} -> HTTP $CODE"
  if [[ "$CODE" == "200" ]]; then CHAT_OK=1; CHAT_DESC="minimax ${MM_MODEL}"; fi
fi
# 2) OpenAI-compatible (SiliconFlow chat etc.)
if [[ "$CHAT_OK" != "1" ]]; then
  OA_BASE=$(echo "${SPRING_AI_OPENAI_BASE_URL:-${OPENAI_BASE_URL:-https://api.siliconflow.cn}}" | sed 's|/$||; s|/v1$||')
  OA_MODEL="${SPRING_AI_OPENAI_CHAT_OPTIONS_MODEL:-${OPENAI_MODEL:-Qwen/Qwen2.5-7B-Instruct}}"
  for candidate in "${SPRING_AI_OPENAI_API_KEY:-}" "${OPENAI_API_KEY:-}" "${SILICONFLOW_API_KEY:-}"; do
    [[ -z "$candidate" ]] && continue
    CODE=$(curl -s -o /tmp/rag-chat-probe.json -w "%{http_code}" \
      -X POST "${OA_BASE}/v1/chat/completions" \
      -H "Authorization: Bearer ${candidate}" \
      -H "Content-Type: application/json" \
      -d "{\"model\":\"${OA_MODEL}\",\"messages\":[{\"role\":\"user\",\"content\":\"ping\"}],\"max_tokens\":5}" || true)
    echo "openai-compat probe ${OA_BASE} ${OA_MODEL} key_len=${#candidate} -> HTTP $CODE"
    if [[ "$CODE" == "200" ]]; then CHAT_OK=1; CHAT_DESC="openai-compat ${OA_MODEL}"; break; fi
  done
fi
if [[ "$CHAT_OK" != "1" ]]; then
  echo "No working chat API key. For default stack set SPRING_AI_MINIMAX_API_KEY (MiniMax-M3) in .env"
  exit 2
fi
ok "chat API HTTP 200 (${CHAT_DESC})"

step "1) Create isolated collection and document with verification code=$PROBE_TOKEN"
curl -s -X POST "$API/collections" \
  -H 'Content-Type: application/json' \
  -d "{\"collectionKey\":\"${COLLECTION_KEY}\",\"name\":\"real-llm-e2e-${PROBE_TOKEN}\",\"description\":\"Isolated real LLM release verification collection\",\"domainId\":\"default\"}" \
  -o /tmp/rag-e2e-collection.json
COLLECTION_ID=$(python3 -c "import json; print(json.load(open('/tmp/rag-e2e-collection.json')).get('id') or '')")
RETURNED_COLLECTION_KEY=$(python3 -c "import json; print(json.load(open('/tmp/rag-e2e-collection.json')).get('collectionKey') or '')")
[[ -n "$COLLECTION_ID" && "$RETURNED_COLLECTION_KEY" == "$COLLECTION_KEY" ]] \
  || { bad "create collection or collectionKey mismatch"; exit 1; }
ok "collection key=$COLLECTION_KEY id=$COLLECTION_ID"

curl -s -X POST "$API/documents" \
  -H 'Content-Type: application/json' \
  -d "{\"title\":\"Real LLM E2E ${PROBE_TOKEN}\",\"content\":\"This is automated release verification test data. The release verification code is ${PROBE_TOKEN}. The code may be repeated verbatim when requested.\",\"collectionKey\":\"${COLLECTION_KEY}\"}" \
  -o /tmp/rag-e2e-create.json
head -c 400 /tmp/rag-e2e-create.json; echo
DOC_ID=$(python3 -c "import json; print(json.load(open('/tmp/rag-e2e-create.json')).get('id') or '')")
[[ -n "$DOC_ID" ]] || { bad "create document"; exit 1; }
export DOC_ID
ok "document id=$DOC_ID"

curl -s -X POST "$API/collections/by-key/documents?collectionKey=$COLLECTION_KEY" \
  -H 'Content-Type: application/json' \
  -d "{\"documentId\":$DOC_ID}" \
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
curl -s -X POST "$API/documents/${DOC_ID}/embed?force=true" -o /tmp/rag-e2e-embed.json
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
curl -s -X POST "$API/search" \
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
curl -s -X POST "$API/chat/ask" \
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
sys.exit(0 if token in a else 1)
PY
ask_rc=$?
set -e
if [[ $ask_rc -eq 0 ]]; then ok "chat answer contains verification code"; else bad "chat answer missing verification code"; fi

if [[ "$SKIP_STREAM" != "1" ]]; then
  step "5) Chat stream (REAL LLM, sample)"
  set +e
  curl -s -N -X POST "$API/chat/stream" \
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
looks_sse = data_events > 0 and len(chunks) > 0
raise SystemExit(0 if looks_sse and token in stream_content else 1)
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
