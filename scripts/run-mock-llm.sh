#!/bin/bash
# Run mock LLM server for testing spring-ai-rag without real API keys.
# Usage: ./run-mock-llm.sh [--port 8086] [--delay 100] [--error-rate 0]
#
# Examples:
#   ./run-mock-llm.sh                        # default port 8086
#   ./run-mock-llm.sh --port 9000            # custom port
#   ./run-mock-llm.sh --delay 500            # 500ms response delay
#   ./run-mock-llm.sh --error-rate 0.1       # 10% error rate for chaos testing

PORT=8086
DELAY=100
ERROR_RATE=0

while [[ $# -gt 0 ]]; do
  case $1 in
    --port) PORT="$2"; shift 2 ;;
    --delay) DELAY="$2"; shift 2 ;;
    --error-rate) ERROR_RATE="$2"; shift 2 ;;
    *) echo "Unknown option: $1"; exit 1 ;;
  esac
done

export MOCK_PORT="$PORT"
export MOCK_DELAY_MS="$DELAY"
export MOCK_ERROR_RATE="$ERROR_RATE"

cd "$(dirname "$0")"
echo "[mock-llm] Starting on port $PORT (delay=${DELAY}ms, error_rate=$ERROR_RATE)"
node mock-llm-server.js
