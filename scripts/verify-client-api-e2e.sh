#!/usr/bin/env bash
# Unified external-client contract gate.
#
# Offline mode is deterministic and never calls an LLM. Live mode is explicit:
# it checks Native/OpenAI JSON+SSE and the Python/Node SDKs against a running
# service. The real-model data-quality matrix remains opt-in through
# real-llm-e2e-smoke.sh and the managed Evaluation Suite runner.
set -euo pipefail
umask 077

cd "$(dirname "$0")/.."

LIVE="${CLIENT_API_E2E_LIVE:-0}"
SKIP_OFFLINE=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --live) LIVE=1; shift ;;
    --offline-only) LIVE=0; shift ;;
    --skip-offline) SKIP_OFFLINE=1; shift ;;
    --help)
      cat <<'USAGE'
Usage: ./scripts/verify-client-api-e2e.sh [--live] [--offline-only] [--skip-offline]

Offline mode validates the versioned consumer fixtures and SDK dependencies.
Live mode additionally requires CLIENT_API_E2E_BASE_URL, CLIENT_API_E2E_API_KEY,
and CLIENT_API_E2E_QUERY, then validates Native/OpenAI JSON+SSE and both SDKs.
USAGE
      exit 0
      ;;
    *) echo "Unknown argument: $1" >&2; exit 2 ;;
  esac
done

run_step() {
  local label="$1"
  shift
  echo
  echo "=== $label ==="
  "$@"
  echo "OK: $label"
}

if [[ "$SKIP_OFFLINE" -eq 0 ]]; then
  run_step "Consumer contract offline" ./scripts/verify-client-consumer-contract.sh
  run_step "SDK quickstart offline" ./scripts/verify-client-sdk-quickstart.sh --self-test
  run_step "Official SDK negative self-test" ./scripts/verify-openai-sdk-e2e.sh --self-test
fi

if [[ "$LIVE" == "1" ]]; then
  BASE_URL="${CLIENT_API_E2E_BASE_URL:-}"
  API_KEY="${CLIENT_API_E2E_API_KEY:-}"
  QUERY="${CLIENT_API_E2E_QUERY:-}"
  if [[ -z "$BASE_URL" || -z "$API_KEY" || -z "$QUERY" || "$BASE_URL" == */v1 ]]; then
    echo "Live mode requires CLIENT_API_E2E_BASE_URL (service root without /v1)," >&2
    echo "CLIENT_API_E2E_API_KEY and CLIENT_API_E2E_QUERY." >&2
    exit 2
  fi

  run_step "Consumer contract live Native/OpenAI JSON/SSE" env \
    CLIENT_CONTRACT_LIVE=1 \
    CLIENT_CONTRACT_BASE_URL="$BASE_URL" \
    CLIENT_CONTRACT_API_KEY="$API_KEY" \
    CLIENT_CONTRACT_QUERY="$QUERY" \
    CLIENT_CONTRACT_MODEL="${CLIENT_API_E2E_MODEL:-}" \
    CLIENT_CONTRACT_OPENAI_RAG_MODE="${CLIENT_API_E2E_RAG_MODE:-}" \
    CLIENT_CONTRACT_OPENAI_RAG_MEMORY="${CLIENT_API_E2E_RAG_MEMORY:-}" \
    ./scripts/verify-client-consumer-contract.sh

  run_step "Python/Node SDK live JSON/SSE" env \
    CLIENT_SDK_LIVE=1 \
    CLIENT_SDK_BASE_URL="$BASE_URL" \
    CLIENT_SDK_API_KEY="$API_KEY" \
    RAG_MODEL="${CLIENT_API_E2E_MODEL:-rag-default}" \
    RAG_QUERY="$QUERY" \
    ./scripts/verify-client-sdk-quickstart.sh
else
  echo
  echo "Client API live gate disabled; use --live only with an isolated running service."
fi

echo
echo "Client API E2E verification passed."
