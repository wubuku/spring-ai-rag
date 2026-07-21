#!/usr/bin/env bash
# =============================================================================
# Start Spring AI RAG for REAL LLM end-to-end testing (not mock).
#
# Defaults (validated 2026-07-21):
#   - Port:           18081  (avoids clashing with 8080/8081)
#   - Profile:        postgresql
#   - Chat provider:  minimax  (SPRING_AI_MINIMAX_* / MiniMax-M3 pay-as-you-go)
#   - Embedding:      SiliconFlow BAAI/bge-m3 (SILICONFLOW_API_KEY)
#   - Security:       disabled for local smoke (RAG_SECURITY_ENABLED=true to enable)
#
# .env mapping used:
#   LLM_PROVIDER / APP_LLM_PROVIDER          → app.llm.provider  (default: minimax)
#   SPRING_AI_MINIMAX_API_KEY | MINIMAX_API_KEY | ANTHROPIC_API_KEY
#   SPRING_AI_MINIMAX_BASE_URL | MINIMAX_BASE_URL | ANTHROPIC_BASE_URL(stripped /anthropic)
#   SPRING_AI_MINIMAX_CHAT_OPTIONS_MODEL | MINIMAX_MODEL | ANTHROPIC_MODEL
#   SILICONFLOW_API_KEY | SILICONFLOW_URL | SILICONFLOW_MODEL
#
# Alternate providers:
#   LLM_PROVIDER=openai     → spring.ai.openai.* (OpenAI-compatible, e.g. SiliconFlow chat)
#   LLM_PROVIDER=anthropic  → spring.ai.anthropic.* (e.g. MiniMax Anthropic-compatible gateway)
#
# Usage:
#   ./scripts/start-real-e2e-server.sh
#   SERVER_PORT=18081 LLM_PROVIDER=minimax ./scripts/start-real-e2e-server.sh
#   BASE_URL=http://127.0.0.1:18081 ./scripts/real-llm-e2e-smoke.sh
#
# Stop:
#   lsof -ti :18081 | xargs kill -9
# =============================================================================
set -euo pipefail
cd "$(dirname "$0")/.."

SERVER_PORT="${SERVER_PORT:-18081}"
LOG_FILE="${LOG_FILE:-/tmp/spring-ai-rag-real-e2e.log}"

# Preserve caller overrides before loading .env
_PRESERVE_LLM_PROVIDER="${LLM_PROVIDER-}"
_PRESERVE_APP_LLM_PROVIDER="${APP_LLM_PROVIDER-}"
_PRESERVE_MM_KEY="${SPRING_AI_MINIMAX_API_KEY-}"
_PRESERVE_MM_BASE="${SPRING_AI_MINIMAX_BASE_URL-}"
_PRESERVE_MM_MODEL="${SPRING_AI_MINIMAX_CHAT_OPTIONS_MODEL-}"
_PRESERVE_SF_KEY="${SILICONFLOW_API_KEY-}"
_PRESERVE_SF_URL="${SILICONFLOW_URL-}"
_PRESERVE_OPENAI_KEY="${SPRING_AI_OPENAI_API_KEY-}"
_PRESERVE_OPENAI_BASE="${SPRING_AI_OPENAI_BASE_URL-}"
_PRESERVE_OPENAI_MODEL="${SPRING_AI_OPENAI_CHAT_OPTIONS_MODEL-}"
_PRESERVE_ANTHROPIC_KEY="${ANTHROPIC_API_KEY-}"
_PRESERVE_ANTHROPIC_BASE="${ANTHROPIC_BASE_URL-}"
_PRESERVE_ANTHROPIC_MODEL="${ANTHROPIC_MODEL-}"

if [[ -f .env ]]; then
  # shellcheck disable=SC2046
  export $(grep -v '^#' .env | grep -v '^$' | xargs) || true
fi

# Restore non-empty caller overrides
[[ -n "${_PRESERVE_LLM_PROVIDER}" ]] && export LLM_PROVIDER="${_PRESERVE_LLM_PROVIDER}"
[[ -n "${_PRESERVE_APP_LLM_PROVIDER}" ]] && export APP_LLM_PROVIDER="${_PRESERVE_APP_LLM_PROVIDER}"
[[ -n "${_PRESERVE_MM_KEY}" ]] && export SPRING_AI_MINIMAX_API_KEY="${_PRESERVE_MM_KEY}"
[[ -n "${_PRESERVE_MM_BASE}" ]] && export SPRING_AI_MINIMAX_BASE_URL="${_PRESERVE_MM_BASE}"
[[ -n "${_PRESERVE_MM_MODEL}" ]] && export SPRING_AI_MINIMAX_CHAT_OPTIONS_MODEL="${_PRESERVE_MM_MODEL}"
[[ -n "${_PRESERVE_SF_KEY}" ]] && export SILICONFLOW_API_KEY="${_PRESERVE_SF_KEY}"
[[ -n "${_PRESERVE_SF_URL}" ]] && export SILICONFLOW_URL="${_PRESERVE_SF_URL}"
[[ -n "${_PRESERVE_OPENAI_KEY}" ]] && export SPRING_AI_OPENAI_API_KEY="${_PRESERVE_OPENAI_KEY}"
[[ -n "${_PRESERVE_OPENAI_BASE}" ]] && export SPRING_AI_OPENAI_BASE_URL="${_PRESERVE_OPENAI_BASE}"
[[ -n "${_PRESERVE_OPENAI_MODEL}" ]] && export SPRING_AI_OPENAI_CHAT_OPTIONS_MODEL="${_PRESERVE_OPENAI_MODEL}"
[[ -n "${_PRESERVE_ANTHROPIC_KEY}" ]] && export ANTHROPIC_API_KEY="${_PRESERVE_ANTHROPIC_KEY}"
[[ -n "${_PRESERVE_ANTHROPIC_BASE}" ]] && export ANTHROPIC_BASE_URL="${_PRESERVE_ANTHROPIC_BASE}"
[[ -n "${_PRESERVE_ANTHROPIC_MODEL}" ]] && export ANTHROPIC_MODEL="${_PRESERVE_ANTHROPIC_MODEL}"

# Default provider: minimax (validated with MiniMax-M3 pay-as-you-go + SF embed)
LLM_PROVIDER="${LLM_PROVIDER:-${APP_LLM_PROVIDER:-minimax}}"
LLM_PROVIDER="$(echo "$LLM_PROVIDER" | tr '[:upper:]' '[:lower:]')"
SECURITY_ENABLED="${RAG_SECURITY_ENABLED:-false}"

# --- Embedding: always SiliconFlow BGE-M3 for real e2e (independent of chat provider) ---
EMB_KEY="${SILICONFLOW_API_KEY:-}"
EMB_BASE=$(echo "${SILICONFLOW_URL:-https://api.siliconflow.cn}" | sed 's|/$||; s|/v1$||')
EMB_MODEL="${SILICONFLOW_MODEL:-BAAI/bge-m3}"

# --- Chat provider resolution ---
strip_v1() { echo "$1" | sed 's|/$||; s|/v1$||'; }
# MiniMax Anthropic gateway often ends with /anthropic — keep path, only strip trailing slash
strip_slash() { echo "$1" | sed 's|/$||'; }

JAVA_ARGS=(
  -Dspring.profiles.active=postgresql
  -Dserver.port="${SERVER_PORT}"
  -Drag.security.enabled="${SECURITY_ENABLED}"
  -Dspring.datasource.url="jdbc:postgresql://${POSTGRES_HOST:-localhost}:${POSTGRES_PORT:-5432}/${POSTGRES_DATABASE:-spring_ai_rag_dev}"
  -Dspring.datasource.username="${POSTGRES_USER:-postgres}"
  -Dspring.datasource.password="${POSTGRES_PASSWORD:-postgres}"
  -Dapp.llm.provider="${LLM_PROVIDER}"
  -Drag.embedding.api-key="${EMB_KEY}"
  -Drag.embedding.base-url="${EMB_BASE}"
  -Drag.embedding.model="${EMB_MODEL}"
)

case "${LLM_PROVIDER}" in
  minimax)
    MM_KEY="${SPRING_AI_MINIMAX_API_KEY:-${MINIMAX_API_KEY:-${ANTHROPIC_API_KEY:-}}}"
    MM_BASE_RAW="${SPRING_AI_MINIMAX_BASE_URL:-${MINIMAX_BASE_URL:-https://api.minimaxi.com}}"
    # If only Anthropic-compatible URL is set, derive OpenAI-compatible MiniMax host
    if [[ -z "${SPRING_AI_MINIMAX_BASE_URL:-}" && -z "${MINIMAX_BASE_URL:-}" && -n "${ANTHROPIC_BASE_URL:-}" ]]; then
      MM_BASE_RAW=$(echo "${ANTHROPIC_BASE_URL}" | sed 's|/anthropic/*$||')
    fi
    MM_BASE=$(strip_v1 "$MM_BASE_RAW")
    MM_MODEL="${SPRING_AI_MINIMAX_CHAT_OPTIONS_MODEL:-${MINIMAX_MODEL:-${ANTHROPIC_MODEL:-MiniMax-M3}}}"
    JAVA_ARGS+=(
      -Dspring.ai.minimax.api-key="${MM_KEY}"
      -Dspring.ai.minimax.base-url="${MM_BASE}"
      -Dspring.ai.minimax.chat.options.model="${MM_MODEL}"
      -Dspring.ai.minimax.chat.options.temperature="${MINIMAX_TEMPERATURE:-0.2}"
    )
    CHAT_DESC="minimax model=${MM_MODEL} base=${MM_BASE} key_len=${#MM_KEY}"
    ;;
  anthropic)
    ANTH_KEY="${ANTHROPIC_API_KEY:-${SPRING_AI_MINIMAX_API_KEY:-}}"
    ANTH_BASE=$(strip_slash "${ANTHROPIC_BASE_URL:-https://api.minimaxi.com/anthropic}")
    ANTH_MODEL="${ANTHROPIC_MODEL:-${SPRING_AI_MINIMAX_CHAT_OPTIONS_MODEL:-MiniMax-M3}}"
    JAVA_ARGS+=(
      -Dspring.ai.anthropic.api-key="${ANTH_KEY}"
      -Dspring.ai.anthropic.base-url="${ANTH_BASE}"
      -Dspring.ai.anthropic.chat.options.model="${ANTH_MODEL}"
      -Dspring.ai.anthropic.chat.options.temperature="${ANTHROPIC_TEMPERATURE:-0.2}"
      -Dspring.ai.anthropic.chat.options.max-tokens="${ANTHROPIC_MAX_TOKENS:-4096}"
    )
    CHAT_DESC="anthropic model=${ANTH_MODEL} base=${ANTH_BASE} key_len=${#ANTH_KEY}"
    ;;
  openai|*)
    # OpenAI-compatible (SiliconFlow chat, DeepSeek, etc.)
    OA_KEY="${SPRING_AI_OPENAI_API_KEY:-${OPENAI_API_KEY:-${SILICONFLOW_API_KEY:-}}}"
    OA_BASE=$(strip_v1 "${SPRING_AI_OPENAI_BASE_URL:-${OPENAI_BASE_URL:-https://api.siliconflow.cn}}")
    OA_MODEL="${SPRING_AI_OPENAI_CHAT_OPTIONS_MODEL:-${OPENAI_MODEL:-Qwen/Qwen2.5-7B-Instruct}}"
    JAVA_ARGS+=(
      -Dspring.ai.openai.api-key="${OA_KEY}"
      -Dspring.ai.openai.base-url="${OA_BASE}"
      -Dspring.ai.openai.chat.options.model="${OA_MODEL}"
    )
    CHAT_DESC="openai-compat model=${OA_MODEL} base=${OA_BASE} key_len=${#OA_KEY}"
    ;;
esac

if [[ -z "${EMB_KEY}" ]]; then
  echo "WARN: SILICONFLOW_API_KEY empty — embedding will fail until set in .env"
fi

echo "Compiling..."
mvn -pl spring-ai-rag-core -am -q -DskipTests compile
mvn -pl spring-ai-rag-core -am -q dependency:build-classpath -Dmdep.outputFile=/tmp/rag-cp.txt -DincludeScope=runtime
CP="spring-ai-rag-core/target/classes:spring-ai-rag-api/target/classes:spring-ai-rag-documents/target/classes:spring-ai-rag-starter/target/classes:$(cat /tmp/rag-cp.txt)"

if lsof -ti ":${SERVER_PORT}" >/dev/null 2>&1; then
  echo "Killing existing process on :${SERVER_PORT}"
  lsof -ti ":${SERVER_PORT}" | xargs kill -9 2>/dev/null || true
  sleep 1
fi

echo "Starting SpringAiRagApplication on :${SERVER_PORT}"
echo "  chat: ${CHAT_DESC}"
echo "  embed: siliconflow model=${EMB_MODEL} base=${EMB_BASE} key_len=${#EMB_KEY}"
echo "  log: ${LOG_FILE}"

nohup java -cp "$CP" \
  "${JAVA_ARGS[@]}" \
  com.springairag.core.SpringAiRagApplication \
  >"${LOG_FILE}" 2>&1 &
echo "PID $!"

for i in $(seq 1 90); do
  if curl -sf "http://127.0.0.1:${SERVER_PORT}/actuator/health" >/dev/null 2>&1; then
    echo "UP http://127.0.0.1:${SERVER_PORT} (iter $i)"
    curl -s "http://127.0.0.1:${SERVER_PORT}/actuator/health" | head -c 220; echo
    # Confirm which chat bean was selected
    if grep -q "Using MiniMax ChatModel as primary\|Creating MiniMax ChatModel\|Using Anthropic\|Using OpenAI" "${LOG_FILE}" 2>/dev/null; then
      grep -E "Using .* ChatModel|Creating MiniMax|Creating Anthropic|Creating OpenAI|Creating EmbeddingModel" "${LOG_FILE}" | tail -5
    fi
    echo "Next: BASE_URL=http://127.0.0.1:${SERVER_PORT} ./scripts/real-llm-e2e-smoke.sh"
    exit 0
  fi
  if ! pgrep -f "com.springairag.core.SpringAiRagApplication" >/dev/null 2>&1; then
    echo "Process died — last log lines:"
    tail -60 "${LOG_FILE}"
    exit 1
  fi
  sleep 2
done
echo "TIMEOUT waiting for health"
tail -60 "${LOG_FILE}"
exit 1
