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

LIVE_BASE_URL=""
LIVE_API_KEY=""
LIVE_QUERY=""
if [[ "$LIVE" == "1" ]]; then
  LIVE_BASE_URL="${CLIENT_API_E2E_BASE_URL:-}"
  LIVE_API_KEY="${CLIENT_API_E2E_API_KEY:-}"
  LIVE_QUERY="${CLIENT_API_E2E_QUERY:-}"
  if [[ -z "$LIVE_BASE_URL" || -z "$LIVE_API_KEY" || -z "$LIVE_QUERY" || "$LIVE_BASE_URL" == */v1 ]]; then
    echo "Live mode requires CLIENT_API_E2E_BASE_URL (service root without /v1)," >&2
    echo "CLIENT_API_E2E_API_KEY and CLIENT_API_E2E_QUERY." >&2
    exit 2
  fi
  if ! command -v curl >/dev/null 2>&1; then
    echo "Live mode requires curl to preflight OpenAI compatibility." >&2
    exit 2
  fi
  openai_models_status="$(curl --silent --show-error --connect-timeout 5 --max-time 15 \
    -H "X-API-Key: $LIVE_API_KEY" \
    -H "Authorization: Bearer $LIVE_API_KEY" \
    -o /dev/null -w '%{http_code}' \
    "$LIVE_BASE_URL/v1/models" || true)"
  if [[ "$openai_models_status" != "200" ]]; then
    echo "Live Client API E2E requires GET /v1/models (HTTP ${openai_models_status:-unknown})." >&2
    case "$openai_models_status" in
      404)
        echo "Enable RAG_OPENAI_COMPATIBILITY_ENABLED=true on the isolated service." >&2
        ;;
      401|403)
        echo "The endpoint rejected authentication; verify CLIENT_API_E2E_API_KEY and service authorization." >&2
        ;;
      000|"")
        echo "The service could not be reached; verify its URL, availability, and TLS configuration." >&2
        ;;
      *)
        echo "The service returned an unexpected status; inspect its health and API logs." >&2
        ;;
    esac
    exit 2
  fi
fi

if [[ "$SKIP_OFFLINE" -eq 0 ]]; then
  run_step "Consumer contract offline" ./scripts/verify-client-consumer-contract.sh
  run_step "SDK quickstart offline" ./scripts/verify-client-sdk-quickstart.sh --self-test
  run_step "Official SDK negative self-test" ./scripts/verify-openai-sdk-e2e.sh --self-test
fi

if [[ "$LIVE" == "1" ]]; then
  run_step "Consumer contract live Native/OpenAI JSON/SSE" env \
    CLIENT_CONTRACT_LIVE=1 \
    CLIENT_CONTRACT_BASE_URL="$LIVE_BASE_URL" \
    CLIENT_CONTRACT_API_KEY="$LIVE_API_KEY" \
    CLIENT_CONTRACT_QUERY="$LIVE_QUERY" \
    CLIENT_CONTRACT_MODEL="${CLIENT_API_E2E_MODEL:-}" \
    CLIENT_CONTRACT_OPENAI_RAG_MODE="${CLIENT_API_E2E_RAG_MODE:-}" \
    CLIENT_CONTRACT_OPENAI_RAG_MEMORY="${CLIENT_API_E2E_RAG_MEMORY:-}" \
    ./scripts/verify-client-consumer-contract.sh

  run_step "Python/Node SDK live JSON/SSE" env \
    CLIENT_SDK_LIVE=1 \
    CLIENT_SDK_BASE_URL="$LIVE_BASE_URL" \
    CLIENT_SDK_API_KEY="$LIVE_API_KEY" \
    RAG_MODEL="${CLIENT_API_E2E_MODEL:-rag-default}" \
    RAG_QUERY="$LIVE_QUERY" \
    ./scripts/verify-client-sdk-quickstart.sh
else
  echo
  echo "Client API live gate disabled; use --live only with an isolated running service."
fi

echo
echo "Client API E2E verification passed."
