#!/usr/bin/env bash
# =============================================================================
# k6 Load Test Runner for Spring AI RAG Service
# =============================================================================
# Usage:
#   ./scripts/run-k6-test.sh [smoke|load|stress] [BASE_URL]
#
# Examples:
#   ./scripts/run-k6-test.sh                    # Default: load test, localhost:8081
#   ./scripts/run-k6-test.sh smoke              # Smoke test: 1VU × 10s
#   ./scripts/run-k6-test.sh load               # Load test: 20VUs × 60s
#   ./scripts/run-k6-test.sh stress             # Stress test: 200VUs × 120s
#   BASE_URL=http://staging:8081 ./scripts/run-k6-test.sh load
#   K6_PROFILE=stress MAX_VUS=100 ./scripts/run-k6-test.sh
#
# Prerequisites:
#   1. Install k6: brew install k6  (macOS)
#      Or: https://k6.io/docs/getting-started/installation/
#   2. Start Spring AI RAG Service:
#      mvn spring-boot:run -pl spring-ai-rag-core
# =============================================================================

set -euo pipefail

PROFILE="${1:-load}"
BASE_URL="${2:-${BASE_URL:-http://localhost:8081}}"
API_KEY="${API_KEY:-test-api-key}"

echo "============================================"
echo "k6 Load Test — Spring AI RAG Service"
echo "============================================"
echo "  Profile : $PROFILE"
echo "  Base URL: $BASE_URL"
echo "  API Key : ${API_KEY:0:8}..."
echo "============================================"

# Check if k6 is available
if ! command -v k6 &>/dev/null; then
  echo "ERROR: k6 is not installed."
  echo ""
  echo "Install on macOS:"
  echo "  brew install k6"
  echo ""
  echo "Or see: https://k6.io/docs/getting-started/installation/"
  exit 1
fi

K6_VERSION=$(k6 version 2>&1 | head -1)
echo "  k6 version: $K6_VERSION"
echo "============================================"

# Run k6
k6 run \
  --env K6_PROFILE="$PROFILE" \
  --env BASE_URL="$BASE_URL" \
  --env API_KEY="$API_KEY" \
  --env K6_ENV="${K6_ENV:-dev}" \
  --summary-export="target/k6-results-${PROFILE}.json" \
  "scripts/k6-load-test.js"
