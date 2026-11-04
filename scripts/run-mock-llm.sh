#!/bin/bash
# Run mock LLM server for testing spring-ai-rag without real API keys.
# Usage: ./run-mock-llm.sh [--port 8086] [--delay 100] [--error-rate 0]
#
# Examples:
#   ./run-mock-llm.sh                        # default port 8086
#   ./run-mock-llm.sh --port 9000            # custom port
#   ./run-mock-llm.sh --delay 500            # 500ms response delay
#   ./run-mock-llm.sh --error-rate 0.1       # 10% error rate for chaos testing
#   ./run-mock-llm.sh --stream-delay 200 --stream-chunks 24  # slow SSE stream

PORT=8086
DELAY=100
ERROR_RATE=0
STREAM_DELAY=20
STREAM_CHUNKS=0
STREAM_FIRST_DELAY=0

while [[ $# -gt 0 ]]; do
  case $1 in
    --port) PORT="$2"; shift 2 ;;
    --delay) DELAY="$2"; shift 2 ;;
    --error-rate) ERROR_RATE="$2"; shift 2 ;;
    --stream-delay) STREAM_DELAY="$2"; shift 2 ;;
    --stream-chunks) STREAM_CHUNKS="$2"; shift 2 ;;
    --stream-first-delay) STREAM_FIRST_DELAY="$2"; shift 2 ;;
    *) echo "Unknown option: $1"; exit 1 ;;
  esac
done

export MOCK_PORT="$PORT"
export MOCK_DELAY_MS="$DELAY"
export MOCK_ERROR_RATE="$ERROR_RATE"
export MOCK_STREAM_DELAY_MS="$STREAM_DELAY"
export MOCK_STREAM_CHUNKS="$STREAM_CHUNKS"
export MOCK_STREAM_FIRST_DELAY_MS="$STREAM_FIRST_DELAY"

cd "$(dirname "$0")"
echo "[mock-llm] Starting on port $PORT (delay=${DELAY}ms, error_rate=$ERROR_RATE, stream_delay=${STREAM_DELAY}ms, stream_chunks=${STREAM_CHUNKS})"
# exec 让 node 取代 shell 成为被启动进程本身：清理时 terminate 才能杀掉真正的服务，
# 否则只会杀掉包装 shell，node 变成占着端口的孤儿进程。
exec node mock-llm-server.js
