#!/bin/bash
# run-k6-vector-search.sh — Run k6 vector search stress test
#
# Usage:
#   ./scripts/run-k6-vector-search.sh              # 50 VUs, 30s (smoke)
#   ./scripts/run-k6-vector-search.sh load        # 100 VUs, 60s
#   ./scripts/run-k6-vector-search.sh stress      # 200 VUs, 120s
#   ./scripts/run-k6-vector-search.sh 150 90s     # Custom VUs and duration
#
# Requires: k6 (brew install k6), server running on localhost:8081

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

# Load .env if present
if [ -f "$PROJECT_DIR/.env" ]; then
  export $(grep -v '^#' "$PROJECT_DIR/.env" | xargs 2>/dev/null || true)
fi

PROFILE="${1:-smoke}"
shift || true

VUS=""
DURATION=""

case "$PROFILE" in
  smoke)
    VUS=10
    DURATION="10s"
    ;;
  load)
    VUS=100
    DURATION="60s"
    ;;
  stress)
    VUS=200
    DURATION="120s"
    ;;
  *)
    # Custom VUS and duration as arguments
    VUS="${PROFILE}"
    DURATION="${1:-30s}"
    shift
    ;;
esac

echo "============================================"
echo "k6 Vector Search Stress Test"
echo "Profile: ${PROFILE} | VUs: ${VUS} | Duration: ${DURATION}"
echo "Base URL: ${BASE_URL:-http://localhost:8081}"
echo "============================================"

k6 run \
  -e K6_VUS="$VUS" \
  -e K6_DURATION="$DURATION" \
  -e BASE_URL="${BASE_URL:-http://localhost:8081}" \
  "$SCRIPT_DIR/k6-vector-search-stress.js"

echo ""
echo "Done."
