#!/bin/bash
# k6 Session Stress Test Runner — Spring AI RAG Service
# Stress tests ChatMemory locking by having multiple VUs share the same sessionId.
#
# Usage:
#   ./run-k6-session-stress.sh [options]
#
# Options:
#   --base-url URL       API base URL (default: http://localhost:8081)
#   --api-key KEY        API key (default: test-api-key)
#   --session-id ID      Shared session ID (default: auto-generated)
#   --start-vus N        Starting VU count (default: 5)
#   --end-vus N          Max VU count (default: 100)
#   --stage-duration DUR Stage duration (default: 15s)
#   --stage-step N       VUs added per stage (default: 10)
#   --profile smoke|moderate|extreme  Preset profiles

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BASE_URL="http://localhost:8081"
API_KEY="test-api-key"
SESSION_ID="shared-session-stress-$(date +%s)"
START_VUS=5
END_VUS=100
STAGE_DUR="15s"
STAGE_STEP=10

while [[ $# -gt 0 ]]; do
  case $1 in
    --base-url)       BASE_URL="$2";      shift 2 ;;
    --api-key)        API_KEY="$2";       shift 2 ;;
    --session-id)     SESSION_ID="$2";    shift 2 ;;
    --start-vus)      START_VUS="$2";     shift 2 ;;
    --end-vus)        END_VUS="$2";       shift 2 ;;
    --stage-duration) STAGE_DUR="$2";     shift 2 ;;
    --stage-step)     STAGE_STEP="$2";    shift 2 ;;
    --profile)
      case $2 in
        smoke)     START_VUS=1;  END_VUS=5;   STAGE_DUR="10s"; STAGE_STEP=2 ;;
        moderate)  START_VUS=5;  END_VUS=50;  STAGE_DUR="15s"; STAGE_STEP=5 ;;
        extreme)   START_VUS=10; END_VUS=200; STAGE_DUR="20s"; STAGE_STEP=10 ;;
        *) echo "Unknown profile: $2"; exit 1 ;;
      esac
      shift 2 ;;
    *) echo "Unknown option: $1"; exit 1 ;;
  esac
done

if ! command -v k6 &> /dev/null; then
  echo "❌ k6 not found. Install: brew install k6"
  exit 1
fi

echo "╔═══════════════════════════════════════════════╗"
echo "║  Spring AI RAG — Session Stress Test          ║"
echo "╠═══════════════════════════════════════════════╣"
echo "║  Session ID:    $SESSION_ID"
echo "║  Base URL:      $BASE_URL"
echo "║  VU Range:      $START_VUS → $END_VUS (step $STAGE_STEP)"
echo "║  Stage Duration: $STAGE_DUR"
echo "╚═══════════════════════════════════════════════╝"
echo ""

NUM_STAGES=$(( (END_VUS - START_VUS) / STAGE_STEP + 1 ))
echo "📊 $NUM_STAGES stages, ~$(( NUM_STAGES * 15 )) seconds estimated"
echo ""

k6 run \
  -e BASE_URL="$BASE_URL" \
  -e API_KEY="$API_KEY" \
  -e SESSION_ID="$SESSION_ID" \
  -e START_VUS="$START_VUS" \
  -e END_VUS="$END_VUS" \
  -e STAGE_DURATION="$STAGE_DUR" \
  -e STAGE_STEP="$STAGE_STEP" \
  "$SCRIPT_DIR/k6-session-stress.js"
