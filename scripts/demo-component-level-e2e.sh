#!/bin/bash
# scripts/demo-component-level-e2e.sh — demo-component-level module E2E verification
# Starts demo-component-level -> waits for readiness -> curl tests component-level integration endpoints
#
# Usage: bash scripts/demo-component-level-e2e.sh [PORT]
# Example: bash scripts/demo-component-level-e2e.sh 8084

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
PORT="${1:-8084}"
BASE_URL="http://localhost:${PORT}"
MAX_WAIT=90

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'
pass() { echo -e "${GREEN}[PASS]${NC} $1"; }
fail() { echo -e "${RED}[FAIL]${NC} $1"; }
info() { echo -e "${YELLOW}[INFO]${NC} $1"; }

cd "$PROJECT_DIR/demos/demo-component-level"

# Clean up port
info "Cleaning up port ${PORT}..."
lsof -ti:${PORT} -sTCP:LISTEN 2>/dev/null | xargs -r kill -9 2>/dev/null || true
sleep 2

# Load environment variables
if [ -f "$PROJECT_DIR/.env" ]; then
    info "Loading .env environment variables..."
    set -a; source "$PROJECT_DIR/.env"; set +a
fi

# Start demo-component-level
info "Starting demo-component-level (port ${PORT})..."
mvn spring-boot:run -Dserver.port="${PORT}" > /tmp/demo-component-server.log 2>&1 &
SERVER_PID=$!

# Wait for readiness
info "Waiting for server startup (up to ${MAX_WAIT}s)..."
for i in $(seq 1 $MAX_WAIT); do
    if curl -sf "${BASE_URL}/actuator/health" > /dev/null 2>&1; then
        echo ""; pass "Server ready (${i}s)"; break
    fi
    if ! kill -0 $SERVER_PID 2>/dev/null; then
        echo ""; fail "Server process exited unexpectedly"; cat /tmp/demo-component-server.log | tail -30; exit 1
    fi
    printf "."; sleep 1
done

if [ $i -eq $MAX_WAIT ]; then
    echo ""; fail "Server startup timeout (${MAX_WAIT}s)"
    cat /tmp/demo-component-server.log | tail -30
    kill $SERVER_PID 2>/dev/null || true; exit 1
fi

TOTAL=0; PASSED=0; FAILED=0

do_test() {
    local name="$1"; local method="${2:-GET}"; local url="$3"
    local data="$4"; local expect_code="${5:-200}"

    TOTAL=$((TOTAL + 1))
    printf "%-50s ... " "$name"

    if [ "$method" = "GET" ]; then
        code=$(curl -sf -o /dev/null -w "%{http_code}" "${BASE_URL}${url}")
    else
        code=$(curl -sf -o /dev/null -w "%{http_code}" -X "$method" \
            -H "Content-Type: application/json" -d "$data" "${BASE_URL}${url}")
    fi

    if [ "$code" = "$expect_code" ]; then
        pass "HTTP ${code}"; PASSED=$((PASSED + 1))
    else
        fail "HTTP ${code} (expected ${expect_code})"; FAILED=$((FAILED + 1))
    fi
}

do_text() {
    local name="$1"; local url="$2"
    TOTAL=$((TOTAL + 1)); printf "%-50s ... " "$name"
    response=$(curl -sf "${BASE_URL}${url}")
    if [ -n "$response" ] && [ "$response" != "null" ]; then
        pass "$(echo "$response" | head -c 60)"; PASSED=$((PASSED + 1))
    else
        fail "Empty response"; FAILED=$((FAILED + 1))
    fi
}

do_json() {
    local name="$1"; local method="${2:-POST}"; local url="$3"; local data="$4"
    TOTAL=$((TOTAL + 1)); printf "%-50s ... " "$name"
    response=$(curl -sf -X "$method" -H "Content-Type: application/json" -d "$data" "${BASE_URL}${url}")
    code=$(curl -sf -o /dev/null -w "%{http_code}" -X "$method" \
        -H "Content-Type: application/json" -d "$data" "${BASE_URL}${url}")
    if [ "$code" = "200" ] && [ -n "$response" ] && [ "$response" != "null" ]; then
        pass "HTTP ${code} ($(echo "$response" | head -c 80)...)"; PASSED=$((PASSED + 1))
    else
        fail "HTTP ${code} or empty response"; FAILED=$((FAILED + 1))
    fi
}

echo ""; echo "=========================================="; info "demo-component-level E2E Tests"; echo "=========================================="; echo ""

# Health check
do_test "actuator/health" GET "/actuator/health"

# Component-level integration — ChatClient + RAG Advisors
do_text "GET /demo/component/ask?q=hello" "/demo/component/ask?q=hello"

# Component-level integration — ChatClient + RAG Advisors + Memory (multi-turn)
do_json "POST /demo/component/chat (round 1)" POST "/demo/component/chat" \
    '{"message":"Hello","sessionId":"comp-e2e-'$RANDOM'"}'
do_json "POST /demo/component/chat (round 2)" POST "/demo/component/chat" \
    '{"message":"What did we just talk about?","sessionId":"comp-e2e-'$RANDOM'"}'

# Comparison: with memory vs without memory
SESSION_ID="compare-mem-$RANDOM"
do_json "POST /demo/component/compare-memory" POST "/demo/component/compare-memory?sessionId=${SESSION_ID}" \
    '{"q1":"My name is John","q2":"What is my name?"}'

# Kill server
kill $SERVER_PID 2>/dev/null || true; wait $SERVER_PID 2>/dev/null || true

echo ""; echo "=========================================="
echo "E2E Results: ${PASSED}/${TOTAL} passed"
if [ $FAILED -gt 0 ]; then
    echo -e "${RED}${FAILED} failed${NC}"; exit 1
else
    echo -e "${GREEN}All passed${NC}"; exit 0
fi
