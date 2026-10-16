#!/usr/bin/env bash
# Offline-safe syntax and secret-safety gate for the minimal external SDK examples.
# Set CLIENT_SDK_LIVE=1 with CLIENT_SDK_BASE_URL and CLIENT_SDK_API_KEY to run
# the examples against a running service; this is never enabled implicitly.
set -euo pipefail
umask 077

cd "$(dirname "$0")/.."

SELF_TEST=0
if [[ "${1:-}" == "--self-test" ]]; then
  SELF_TEST=1
  shift
fi
if [[ $# -gt 0 ]]; then
  echo "Usage: ./scripts/verify-client-sdk-quickstart.sh [--self-test]" >&2
  exit 2
fi

command -v python3 >/dev/null || { echo "Missing required command: python3" >&2; exit 2; }
command -v node >/dev/null || { echo "Missing required command: node" >&2; exit 2; }

python3 -m py_compile examples/client-sdk/python/chat_completion.py
node --check examples/client-sdk/node/chat-completion.mjs

if rg -n "rag_sk_|sk-[A-Za-z0-9]|Bearer [A-Za-z0-9._-]{12,}" \
    examples/client-sdk docs/client-sdk-quickstart* 2>/dev/null; then
  echo "Potential secret found in SDK examples or guide" >&2
  exit 1
fi

if [[ "$SELF_TEST" == "1" ]]; then
  echo "Client SDK quickstart self-test passed (syntax and secret-safe fixtures)."
  exit 0
fi

if [[ "${CLIENT_SDK_LIVE:-0}" != "1" ]]; then
  echo "Offline SDK quickstart check passed. Set CLIENT_SDK_LIVE=1 for live HTTP." 
  exit 0
fi

base_url="${CLIENT_SDK_BASE_URL:-}"
api_key="${CLIENT_SDK_API_KEY:-}"
if [[ -z "$base_url" || -z "$api_key" || "$base_url" == */v1 ]]; then
  echo "Live mode requires CLIENT_SDK_BASE_URL (service root, without /v1) and CLIENT_SDK_API_KEY" >&2
  exit 2
fi

run_dir="${CLIENT_SDK_RUN_DIR:-.verification/client-sdk-quickstart/$(date +%Y%m%d-%H%M%S)}"
mkdir -p "$run_dir/python" "$run_dir/node"
python3 -m venv "$run_dir/python/venv"
"$run_dir/python/venv/bin/python" -m pip install --disable-pip-version-check \
  --quiet -r examples/client-sdk/python/requirements.txt
npm install --prefix "$run_dir/node" --ignore-scripts --no-audit --no-fund \
  --package-lock=false examples/client-sdk/node >/dev/null
cp examples/client-sdk/node/chat-completion.mjs "$run_dir/node/chat-completion.mjs"

env RAG_BASE_URL="$base_url" RAG_API_KEY="$api_key" \
  "$run_dir/python/venv/bin/python" examples/client-sdk/python/chat_completion.py
env RAG_BASE_URL="$base_url" RAG_API_KEY="$api_key" \
  node "$run_dir/node/chat-completion.mjs"

echo "Client SDK quickstart live check passed."
