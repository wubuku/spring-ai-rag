#!/usr/bin/env bash
# =============================================================================
# Real LLM End-to-End smoke (NOT mock)
#
# Pipeline against a RUNNING backend (default from start-real-e2e-server.sh):
#   1) health
#   2) preflight embedding key (SiliconFlow)
#   3) preflight chat key (MiniMax / Anthropic-gateway / OpenAI-compat)
#   4) create document with unique probe token
#   5) embed (real Embedding API)
#   6) search (real vector/fulltext)
#   7) chat/ask (real Chat LLM) — answer must contain token
#   8) chat/stream sample (optional)
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
step() { echo; echo "=== $* ==="; }
ok()   { echo "OK: $*"; PASS=$((PASS+1)); }
bad()  { echo "FAIL: $*"; FAIL=$((FAIL+1)); }
need() { command -v "$1" >/dev/null || { echo "need $1"; exit 2; }; }

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

step "1) Create document with probe token=$PROBE_TOKEN"
curl -s -X POST "$BASE_URL/api/v1/rag/documents" \
  -H 'Content-Type: application/json' \
  -d "{\"title\":\"Real LLM E2E ${PROBE_TOKEN}\",\"content\":\"This is an automated real-LLM end-to-end probe. The unique secret token is ${PROBE_TOKEN}. Remember this exact token when asked.\"}" \
  -o /tmp/rag-e2e-create.json
head -c 400 /tmp/rag-e2e-create.json; echo
DOC_ID=$(python3 -c "import json; print(json.load(open('/tmp/rag-e2e-create.json')).get('id') or '')")
[[ -n "$DOC_ID" ]] || { bad "create document"; exit 1; }
ok "document id=$DOC_ID"

step "2) Embed document (real embedding)"
curl -s -X POST "$BASE_URL/api/v1/rag/documents/${DOC_ID}/embed?force=true" -o /tmp/rag-e2e-embed.json
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
curl -s -G "$BASE_URL/api/v1/rag/search" \
  --data-urlencode "query=${PROBE_TOKEN}" --data-urlencode "limit=5" \
  -o /tmp/rag-e2e-search.json
python3 - <<'PY'
import json,sys,os
d=json.load(open('/tmp/rag-e2e-search.json'))
results=d.get('results') or d.get('data') or []
if isinstance(d, list):
    results=d
token=os.environ['PROBE_TOKEN']
if not results:
    print('no search hits')
    sys.exit(1)
print('hits', len(results), 'top', results[0].get('documentId'))
if not any(token in (r.get('chunkText') or '') for r in results):
    print('WARN: token not found in hit texts')
else:
    print('token found in search hits')
PY
ok "search returned hits"

step "4) Chat ask (REAL LLM)"
curl -s -X POST "$BASE_URL/api/v1/rag/chat/ask" \
  -H 'Content-Type: application/json' \
  -d "{\"message\":\"What is the unique secret token in the knowledge base that starts with REAL_E2E_? Quote the full token exactly.\",\"maxResults\":8,\"useHybridSearch\":true}" \
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
if [[ $ask_rc -eq 0 ]]; then ok "chat answer contains probe token"; else bad "chat answer missing probe token"; fi

if [[ "$SKIP_STREAM" != "1" ]]; then
  step "5) Chat stream (REAL LLM, sample)"
  set +e
  curl -s -N -X POST "$BASE_URL/api/v1/rag/chat/stream" \
    -H 'Content-Type: application/json' \
    -d "{\"message\":\"If the context contains a token starting with REAL_E2E_, print that full token.\"}" \
    --max-time 180 \
    -o /tmp/rag-e2e-stream.txt
  python3 - <<'PY'
import os
token=os.environ['PROBE_TOKEN']
t=open('/tmp/rag-e2e-stream.txt', errors='replace').read()
print('stream_len', len(t))
print(t[:400])
print('TOKEN_IN_STREAM', token in t)
print('looks_sse', ('choices' in t) or ('delta' in t) or ('data:' in t) or ('event:done' in t))
raise SystemExit(0 if (('choices' in t or 'delta' in t or 'data:' in t) and len(t)>10) else 1)
PY
stream_rc=$?
set -e
if [[ $stream_rc -eq 0 ]]; then ok "stream returned SSE chunks"; else bad "stream did not return expected chunks"; fi
fi

echo
echo "=============================="
echo "PASS=$PASS FAIL=$FAIL probe=$PROBE_TOKEN base=$BASE_URL"
echo "=============================="
if [[ "$FAIL" -gt 0 ]]; then exit 1; fi
exit 0
