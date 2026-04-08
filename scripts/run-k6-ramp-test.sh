#!/bin/bash
# k6 Exploratory Load Test Runner — Ramp to Saturation
# Finds the throughput ceiling of the Spring AI RAG Service.
#
# Usage:
#   ./run-k6-ramp-test.sh [options]
#
# Options:
#   --base-url URL       API base URL (default: http://localhost:8081)
#   --api-key KEY        API key (default: test-api-key)
#   --start-vus N        Starting VU count (default: 5)
#   --end-vus N          Max VU count (default: 200)
#   --stage-duration DUR Stage duration, e.g. "20s" (default: 20s)
#   --stage-step N       VUs added per stage (default: 10)
#   --profile smoke|load|stress  Shorthand presets

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BASE_URL="http://localhost:8081"
API_KEY="test-api-key"
START_VUS=5
END_VUS=200
STAGE_DUR="20s"
STAGE_STEP=10

# Parse arguments
while [[ $# -gt 0 ]]; do
  case $1 in
    --base-url)       BASE_URL="$2";      shift 2 ;;
    --api-key)        API_KEY="$2";       shift 2 ;;
    --start-vus)      START_VUS="$2";     shift 2 ;;
    --end-vus)        END_VUS="$2";       shift 2 ;;
    --stage-duration) STAGE_DUR="$2";     shift 2 ;;
    --stage-step)     STAGE_STEP="$2";    shift 2 ;;
    --profile)
      case $2 in
        smoke)  START_VUS=1; END_VUS=5;  STAGE_DUR="10s"; STAGE_STEP=2 ;;
        load)   START_VUS=5; END_VUS=50; STAGE_DUR="20s"; STAGE_STEP=5 ;;
        stress) START_VUS=10; END_VUS=200; STAGE_DUR="30s"; STAGE_STEP=10 ;;
        *) echo "Unknown profile: $2"; exit 1 ;;
      esac
      shift 2 ;;
    *) echo "Unknown option: $1"; exit 1 ;;
  esac
done

# Check dependencies
if ! command -v k6 &> /dev/null; then
  echo "❌ k6 not found. Install: brew install k6 (macOS) or https://k6.io/docs/getting-started/installation/"
  exit 1
fi

echo "╔═══════════════════════════════════════════════╗"
echo "║  Spring AI RAG — Exploratory Load Test        ║"
echo "╠═══════════════════════════════════════════════╣"
echo "║  Base URL:      $BASE_URL"
echo "║  VU Range:      $START_VUS → $END_VUS  (step $STAGE_STEP)"
echo "║  Stage Duration: $STAGE_DUR"
echo "╚═══════════════════════════════════════════════╝"
echo ""

# Calculate estimated total time
NUM_STAGES=$(( (END_VUS - START_VUS) / STAGE_STEP + 1 ))
DUR_SECS=$(echo "$STAGE_DUR" | sed 's/s$//' | sed 's/m$/*60/' | sed 's/h$/*3600/')
TOTAL_SECS=$(( NUM_STAGES * DUR_SECS ))
echo "📊 ~$(( TOTAL_SECS / 60 )) minute$([ $TOTAL_SECS -ge 120 ] && echo "s" || echo "") estimated run time ($NUM_STAGES stages)"
echo ""

k6 run \
  -e BASE_URL="$BASE_URL" \
  -e API_KEY="$API_KEY" \
  -e START_VUS="$START_VUS" \
  -e END_VUS="$END_VUS" \
  -e STAGE_DURATION="$STAGE_DUR" \
  -e STAGE_STEP="$STAGE_STEP" \
  "$SCRIPT_DIR/k6-ramp-to-saturation.js"
