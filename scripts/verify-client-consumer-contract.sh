#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

ROOT="${CLIENT_CONTRACT_ROOT:-testdata/api-contract/consumer/v1}"
SPEC_FILE="${CLIENT_CONTRACT_SPEC_FILE:-}"

[[ -d "$ROOT" ]] || {
  echo "Missing client consumer contract root: $ROOT" >&2
  exit 2
}

node scripts/lib/client-consumer-contract.mjs --self-test
node scripts/test-support/client-recovery-self-test.mjs
python3 scripts/test-support/client-recovery-self-test.py

offline_args=(--root "$ROOT")
if [[ -n "$SPEC_FILE" ]]; then
  [[ -s "$SPEC_FILE" ]] || {
    echo "OpenAPI spec is empty: $SPEC_FILE" >&2
    exit 1
  }
  offline_args+=(--spec "$SPEC_FILE")
fi
node scripts/lib/client-consumer-contract.mjs "${offline_args[@]}"

if [[ "${CLIENT_CONTRACT_LIVE:-0}" == "1" ]]; then
  if [[ -z "${CLIENT_CONTRACT_BASE_URL:-}" || -z "${CLIENT_CONTRACT_API_KEY:-}" || -z "${CLIENT_CONTRACT_QUERY:-}" ]]; then
    echo "CONDITIONAL SKIP: live client contract probe requires CLIENT_CONTRACT_BASE_URL, CLIENT_CONTRACT_API_KEY and CLIENT_CONTRACT_QUERY"
    exit 0
  fi
  CLIENT_CONTRACT_BASE_URL="$CLIENT_CONTRACT_BASE_URL" \
    CLIENT_CONTRACT_API_KEY="$CLIENT_CONTRACT_API_KEY" \
    CLIENT_CONTRACT_QUERY="$CLIENT_CONTRACT_QUERY" \
    CLIENT_CONTRACT_MODEL="${CLIENT_CONTRACT_MODEL:-}" \
    CLIENT_CONTRACT_OPENAI_RAG_MODE="${CLIENT_CONTRACT_OPENAI_RAG_MODE:-}" \
    CLIENT_CONTRACT_OPENAI_RAG_MEMORY="${CLIENT_CONTRACT_OPENAI_RAG_MEMORY:-}" \
    node scripts/lib/client-consumer-contract.mjs --root "$ROOT" --live
else
  echo "Client consumer contract offline gate passed; live probe disabled"
fi
