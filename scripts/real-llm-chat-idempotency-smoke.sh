#!/usr/bin/env bash
# Real OpenAI-compatible Chat turn idempotency smoke.
#
# This gate intentionally uses PLAIN mode so it validates the durable Chat
# operation without requiring an embedding provider or a knowledge document.
# It must run against an already started isolated backend.
set -euo pipefail

cd "$(dirname "$0")/.."

BASE_URL="${BASE_URL:-http://127.0.0.1:18081}"
ENV_FILE="${REAL_LLM_ENV_FILE:-${DEV_ENV_FILE:-.env}}"
API="${BASE_URL}/api/v1/rag"
RUN_ID="${REAL_CHAT_RUN_ID:-$(date +%Y%m%d-%H%M%S)-$$}"
WORK_DIR="${REAL_CHAT_LOG_DIR:-.verification/real-chat/${RUN_ID}}"
mkdir -p "$WORK_DIR"
chmod 700 "$WORK_DIR"

[[ -f "$ENV_FILE" ]] || {
  echo "Real Chat environment file does not exist: ${ENV_FILE}" >&2
  exit 2
}

# Preserve explicit caller values, then load the selected environment file.
PRESERVE_PROVIDER="${LLM_PROVIDER-}"
PRESERVE_APP_PROVIDER="${APP_LLM_PROVIDER-}"
PRESERVE_OPENAI_KEY="${SPRING_AI_OPENAI_API_KEY-}"
PRESERVE_OPENAI_BASE="${SPRING_AI_OPENAI_BASE_URL-}"
PRESERVE_OPENAI_MODEL="${SPRING_AI_OPENAI_CHAT_OPTIONS_MODEL-}"
PRESERVE_ROOT_KEY="${RAG_ROOT_API_KEY-}"
set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a
[[ -n "$PRESERVE_PROVIDER" ]] && LLM_PROVIDER="$PRESERVE_PROVIDER"
[[ -n "$PRESERVE_APP_PROVIDER" ]] && APP_LLM_PROVIDER="$PRESERVE_APP_PROVIDER"
[[ -n "$PRESERVE_OPENAI_KEY" ]] && SPRING_AI_OPENAI_API_KEY="$PRESERVE_OPENAI_KEY"
[[ -n "$PRESERVE_OPENAI_BASE" ]] && SPRING_AI_OPENAI_BASE_URL="$PRESERVE_OPENAI_BASE"
[[ -n "$PRESERVE_OPENAI_MODEL" ]] && SPRING_AI_OPENAI_CHAT_OPTIONS_MODEL="$PRESERVE_OPENAI_MODEL"
[[ -n "$PRESERVE_ROOT_KEY" ]] && RAG_ROOT_API_KEY="$PRESERVE_ROOT_KEY"
export LLM_PROVIDER APP_LLM_PROVIDER SPRING_AI_OPENAI_API_KEY
export SPRING_AI_OPENAI_BASE_URL SPRING_AI_OPENAI_CHAT_OPTIONS_MODEL RAG_ROOT_API_KEY

PROVIDER="${REAL_LLM_CHAT_PROVIDER:-${LLM_PROVIDER:-${APP_LLM_PROVIDER:-openai}}}"
PROVIDER="$(printf '%s' "$PROVIDER" | tr '[:upper:]' '[:lower:]')"
[[ "$PROVIDER" == "openai" ]] || {
  echo "This smoke requires the explicit OpenAI-compatible provider; got '${PROVIDER}'." >&2
  echo "Set LLM_PROVIDER=openai or REAL_LLM_CHAT_PROVIDER=openai." >&2
  exit 2
}

OPENAI_KEY="${SPRING_AI_OPENAI_API_KEY:-}"
OPENAI_BASE="${SPRING_AI_OPENAI_BASE_URL:-}"
OPENAI_MODEL="${SPRING_AI_OPENAI_CHAT_OPTIONS_MODEL:-}"
[[ -n "$OPENAI_KEY" ]] || {
  echo "SPRING_AI_OPENAI_API_KEY is required for the real Chat smoke." >&2
  exit 2
}
[[ -n "$OPENAI_BASE" ]] || {
  echo "SPRING_AI_OPENAI_BASE_URL is required for the real Chat smoke." >&2
  exit 2
}
[[ -n "$OPENAI_MODEL" ]] || {
  echo "SPRING_AI_OPENAI_CHAT_OPTIONS_MODEL is required for the real Chat smoke." >&2
  exit 2
}

strip_v1() {
  printf '%s' "$1" | sed 's|/$||; s|/v1$||'
}

BASE_DISPLAY="$(strip_v1 "$OPENAI_BASE")"
echo "[real-chat] provider=openai model=${OPENAI_MODEL} base=${BASE_DISPLAY} key_len=${#OPENAI_KEY}"
echo "[real-chat] backend=${BASE_URL} log_dir=${WORK_DIR}"

AUTH_ARGS=()
ROOT_KEY="${RAG_ROOT_API_KEY:-${RAG_API_KEY:-}}"
if [[ -n "$ROOT_KEY" ]]; then
  AUTH_ARGS=(-H "X-API-Key: ${ROOT_KEY}")
fi

need() {
  command -v "$1" >/dev/null || {
    echo "Required command not found: $1" >&2
    exit 2
  }
}
need curl
need python3

get_counter() {
  local output="$WORK_DIR/counter.json"
  local metric=""
  for metric in \
      "rag.chat.provider.calls" \
      "rag.chat.provider.calls.total"; do
    if curl --fail --silent --show-error \
        "${AUTH_ARGS[@]}" \
        "${BASE_URL}/actuator/metrics/${metric}" >"$output" 2>/dev/null; then
      python3 - "$output" <<'PY'
import json
import sys

payload = json.load(open(sys.argv[1]))
measurements = payload.get("measurements") or []
for item in measurements:
    if item.get("statistic") == "COUNT":
        print(float(item["value"]))
        raise SystemExit(0)
print("counter response has no COUNT measurement", file=sys.stderr)
raise SystemExit(1)
PY
      return 0
    fi
  done
  echo "Unable to read /actuator/metrics/rag.chat.provider.calls; fixed counter evidence is required." >&2
  return 1
}

assert_uuid_header() {
  local headers="$1"
  local expected="${2:-}"
  local actual
  actual="$(awk 'BEGIN{IGNORECASE=1} /^X-RAG-Turn-Id:/ {gsub("\r","",$2); print $2; exit}' "$headers")"
  [[ "$actual" =~ ^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$ ]] || {
    echo "Missing or invalid X-RAG-Turn-Id header." >&2
    cat "$headers" >&2
    return 1
  }
  if [[ -n "$expected" && "$actual" != "$expected" ]]; then
    echo "Turn ID changed: expected ${expected}, got ${actual}." >&2
    return 1
  fi
  printf '%s\n' "$actual"
}

status_code() {
  awk 'NR == 1 {print $2; exit}' "$1"
}

assert_json_response() {
  local body="$1"
  local expected_turn="${2:-}"
  python3 - "$body" "$expected_turn" <<'PY'
import json
import sys

payload = json.load(open(sys.argv[1]))
expected = sys.argv[2]
if not payload.get("answer"):
    raise SystemExit("real Chat response has no answer")
turn_id = payload.get("turnId")
if not turn_id or (expected and turn_id != expected):
    raise SystemExit(f"unexpected response turnId: {turn_id!r}")
if (payload.get("mode") or "") != "PLAIN":
    raise SystemExit(f"expected PLAIN mode, got {payload.get('mode')!r}")
metadata = payload.get("metadata") or {}
if metadata.get("turnId") != turn_id:
    raise SystemExit("metadata.turnId does not match response.turnId")
print(payload["answer"])
PY
}

assert_sse_response() {
  local body="$1"
  local expected_turn="$2"
  python3 - "$body" "$expected_turn" <<'PY'
import json
import re
import sys

text = open(sys.argv[1], errors="replace").read()
turn_id = sys.argv[2]
if not re.search(r"(?m)^event:\s*done\s*$", text):
    raise SystemExit("SSE response has no done event")
if "[SSE_ERROR]" in text:
    raise SystemExit(text)
matches = re.findall(r"event:\s*done\s*\n(?:[^\n]*\n)*?data:\s*(\{.*?\})(?:\r?\n\r?\n|$)", text, re.S)
if not matches:
    raise SystemExit("could not parse SSE done payload")
done = json.loads(matches[-1])
if done.get("turnId") != turn_id:
    raise SystemExit(f"SSE done turnId mismatch: {done.get('turnId')!r}")
if done.get("status") != "complete":
    raise SystemExit(f"SSE did not complete: {done!r}")
content_parts = []
for raw in re.findall(
    r"event:\s*content\s*\n(?:[^\n]*\n)*?data:\s*(\{.*?\})(?:\r?\n\r?\n|$)",
    text,
    re.S,
):
    payload = json.loads(raw)
    fragment = payload.get("content")
    if not isinstance(fragment, str):
        fragment = (
            ((payload.get("choices") or [{}])[0].get("delta") or {}).get("content")
        )
    if isinstance(fragment, str):
        content_parts.append(fragment)
content = "".join(content_parts)
if not content.strip():
    raise SystemExit("SSE response has no content")
print(content)
PY
}

assert_status() {
  local turn_id="$1"
  local body="$WORK_DIR/status-${turn_id}.json"
  local code
  code="$(curl --fail --silent --show-error "${AUTH_ARGS[@]}" \
    "${BASE_URL}/api/v1/rag/chat/turns/${turn_id}?includeResponse=true" \
    -o "$body" -w '%{http_code}')"
  [[ "$code" == "200" ]] || {
    echo "Turn status returned HTTP ${code}." >&2
    cat "$body" >&2
    return 1
  }
  python3 - "$body" "$turn_id" <<'PY'
import json
import sys

payload = json.load(open(sys.argv[1]))
if payload.get("turnId") != sys.argv[2]:
    raise SystemExit("status turnId mismatch")
if payload.get("status") != "SUCCEEDED":
    raise SystemExit(f"status is not SUCCEEDED: {payload.get('status')!r}")
if payload.get("replayAvailable") is not True:
    raise SystemExit("status replayAvailable is not true")
if not (payload.get("response") or {}).get("answer"):
    raise SystemExit("status response snapshot has no answer")
print("status=SUCCEEDED replayAvailable=true response=true")
PY
}

health="$(curl --fail --silent --show-error "${BASE_URL}/actuator/health")"
echo "$health" >"$WORK_DIR/health.json"
python3 - "$WORK_DIR/health.json" <<'PY'
import json
import sys
if json.load(open(sys.argv[1])).get("status") != "UP":
    raise SystemExit("backend health is not UP")
PY

counter_before="$(get_counter)"
echo "provider_counter_before=${counter_before}"

JSON_KEY="real-chat-json-${RUN_ID}"
JSON_BODY="$WORK_DIR/json-request.json"
printf '%s' '{"message":"Reply with a short confirmation that the real provider is reachable.","mode":"PLAIN"}' >"$JSON_BODY"
curl -sS -D "$WORK_DIR/json-first.headers" \
  "${AUTH_ARGS[@]}" -X POST "${API}/chat/ask" \
  -H 'Content-Type: application/json' \
  -H "Idempotency-Key: ${JSON_KEY}" \
  --data-binary "@${JSON_BODY}" \
  -o "$WORK_DIR/json-first.json" \
  -w '%{http_code}' >"$WORK_DIR/json-first.status"
[[ "$(cat "$WORK_DIR/json-first.status")" == "200" ]] || {
  echo "Native JSON first request failed:" >&2
  cat "$WORK_DIR/json-first.json" >&2
  exit 1
}
JSON_TURN_ID="$(assert_uuid_header "$WORK_DIR/json-first.headers")"
assert_json_response "$WORK_DIR/json-first.json" "$JSON_TURN_ID" >"$WORK_DIR/json-first.answer"
echo "native_json_first=PASS turn_id=${JSON_TURN_ID}"

curl -sS -D "$WORK_DIR/json-replay.headers" \
  "${AUTH_ARGS[@]}" -X POST "${API}/chat/ask" \
  -H 'Content-Type: application/json' \
  -H "Idempotency-Key: ${JSON_KEY}" \
  --data-binary "@${JSON_BODY}" \
  -o "$WORK_DIR/json-replay.json" \
  -w '%{http_code}' >"$WORK_DIR/json-replay.status"
[[ "$(cat "$WORK_DIR/json-replay.status")" == "200" ]] || {
  echo "Native JSON replay failed:" >&2
  cat "$WORK_DIR/json-replay.json" >&2
  exit 1
}
assert_uuid_header "$WORK_DIR/json-replay.headers" "$JSON_TURN_ID" >/dev/null
grep -qi '^X-RAG-Idempotent-Replay:[[:space:]]*true' "$WORK_DIR/json-replay.headers" || {
  echo "Native JSON replay header is not true." >&2
  exit 1
}
assert_json_response "$WORK_DIR/json-replay.json" "$JSON_TURN_ID" >"$WORK_DIR/json-replay.answer"
cmp -s "$WORK_DIR/json-first.answer" "$WORK_DIR/json-replay.answer" || {
  echo "Native JSON replay answer changed." >&2
  exit 1
}
echo "native_json_replay=PASS"

counter_after_json="$(get_counter)"
python3 - "$counter_before" "$counter_after_json" <<'PY'
import sys
before, after = map(float, sys.argv[1:])
if after != before + 1:
    raise SystemExit(f"expected one provider call for JSON first/replay: {before} -> {after}")
PY
echo "provider_counter_after_json=${counter_after_json} (replay did not call provider)"

CONFLICT_BODY="$WORK_DIR/json-conflict.json"
printf '%s' '{"message":"This must conflict with the existing key.","mode":"PLAIN"}' >"$CONFLICT_BODY"
curl -sS -D "$WORK_DIR/json-conflict.headers" \
  "${AUTH_ARGS[@]}" -X POST "${API}/chat/ask" \
  -H 'Content-Type: application/json' \
  -H "Idempotency-Key: ${JSON_KEY}" \
  --data-binary "@${CONFLICT_BODY}" \
  -o "$WORK_DIR/json-conflict.response" \
  -w '%{http_code}' >"$WORK_DIR/json-conflict.status"
[[ "$(cat "$WORK_DIR/json-conflict.status")" == "409" ]] || {
  echo "Expected key conflict HTTP 409." >&2
  cat "$WORK_DIR/json-conflict.response" >&2
  exit 1
}
grep -q 'IDEMPOTENCY_KEY_REUSED' "$WORK_DIR/json-conflict.response" || {
  echo "Key conflict response did not expose IDEMPOTENCY_KEY_REUSED." >&2
  exit 1
}
echo "native_json_key_conflict=PASS"

SSE_KEY="real-chat-sse-${RUN_ID}"
SSE_BODY="$WORK_DIR/sse-request.json"
printf '%s' '{"message":"Reply with a short confirmation over SSE.","mode":"PLAIN"}' >"$SSE_BODY"
curl -sS -N -D "$WORK_DIR/sse-first.headers" \
  "${AUTH_ARGS[@]}" -X POST "${API}/chat/stream" \
  -H 'Content-Type: application/json' \
  -H "Idempotency-Key: ${SSE_KEY}" \
  --data-binary "@${SSE_BODY}" \
  --max-time 180 \
  -o "$WORK_DIR/sse-first.txt" \
  -w '%{http_code}' >"$WORK_DIR/sse-first.status"
[[ "$(cat "$WORK_DIR/sse-first.status")" == "200" ]] || {
  echo "Native SSE first request failed:" >&2
  cat "$WORK_DIR/sse-first.txt" >&2
  exit 1
}
SSE_TURN_ID="$(assert_uuid_header "$WORK_DIR/sse-first.headers")"
assert_sse_response "$WORK_DIR/sse-first.txt" "$SSE_TURN_ID" >"$WORK_DIR/sse-first.answer"
echo "native_sse_first=PASS turn_id=${SSE_TURN_ID}"

curl -sS -N -D "$WORK_DIR/sse-replay.headers" \
  "${AUTH_ARGS[@]}" -X POST "${API}/chat/stream" \
  -H 'Content-Type: application/json' \
  -H "Idempotency-Key: ${SSE_KEY}" \
  --data-binary "@${SSE_BODY}" \
  --max-time 30 \
  -o "$WORK_DIR/sse-replay.txt" \
  -w '%{http_code}' >"$WORK_DIR/sse-replay.status"
[[ "$(cat "$WORK_DIR/sse-replay.status")" == "200" ]] || {
  echo "Native SSE replay failed:" >&2
  cat "$WORK_DIR/sse-replay.txt" >&2
  exit 1
}
assert_uuid_header "$WORK_DIR/sse-replay.headers" "$SSE_TURN_ID" >/dev/null
grep -qi '^X-RAG-Idempotent-Replay:[[:space:]]*true' "$WORK_DIR/sse-replay.headers" || {
  echo "Native SSE replay header is not true." >&2
  exit 1
}
assert_sse_response "$WORK_DIR/sse-replay.txt" "$SSE_TURN_ID" >"$WORK_DIR/sse-replay.answer"
cmp -s "$WORK_DIR/sse-first.answer" "$WORK_DIR/sse-replay.answer" || {
  echo "Native SSE replay answer changed." >&2
  exit 1
}
echo "native_sse_replay=PASS"

counter_after_sse="$(get_counter)"
python3 - "$counter_after_json" "$counter_after_sse" <<'PY'
import sys
before, after = map(float, sys.argv[1:])
if after != before + 1:
    raise SystemExit(f"expected one provider call for SSE first/replay: {before} -> {after}")
PY
echo "provider_counter_after_sse=${counter_after_sse} (replay did not call provider)"

assert_status "$JSON_TURN_ID"
assert_status "$SSE_TURN_ID"

echo "REAL_CHAT_IDEMPOTENCY_SMOKE_OK"
echo "Evidence: ${WORK_DIR}"
