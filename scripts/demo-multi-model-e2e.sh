#!/bin/bash
# scripts/demo-multi-model-e2e.sh — demo-multi-model module E2E verification
# Starts demo-multi-model -> waits for readiness -> curl tests multi-model endpoints
#
# Usage: bash scripts/demo-multi-model-e2e.sh [PORT]
# Example: bash scripts/demo-multi-model-e2e.sh 8083

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
PORT="${1:-8083}"
BASE_URL="http://localhost:${PORT}"
MAX_WAIT=90

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'
pass() { echo -e "${GREEN}[PASS]${NC} $1"; }
fail() { echo -e "${RED}[FAIL]${NC} $1"; }
info() { echo -e "${YELLOW}[INFO]${NC} $1"; }

cd "$PROJECT_DIR/demos/demo-multi-model"

# Clean up port
info "Cleaning up port ${PORT}..."
lsof -ti:${PORT} -sTCP:LISTEN 2>/dev/null | xargs -r kill -9 2>/dev/null || true
sleep 2

# Load environment variables
if [ -f "$PROJECT_DIR/.env" ]; then
    info "Loading .env environment variables..."
    set -a; source "$PROJECT_DIR/.env"; set +a
fi

# Start demo-multi-model
info "Starting demo-multi-model (port ${PORT})..."
mvn spring-boot:run -Dserver.port="${PORT}" > /tmp/demo-multi-server.log 2>&1 &
SERVER_PID=$!

# Wait for readiness
info "Waiting for server startup (up to ${MAX_WAIT}s)..."
for i in $(seq 1 $MAX_WAIT); do
    if curl -sf "${BASE_URL}/actuator/health" > /dev/null 2>&1; then
        echo ""; pass "Server ready (${i}s)"; break
    fi
    if ! kill -0 $SERVER_PID 2>/dev/null; then
        echo ""; fail "Server process exited unexpectedly"; cat /tmp/demo-multi-server.log | tail -30; exit 1
    fi
    printf "."; sleep 1
done

if [ $i -eq $MAX_WAIT ]; then
    echo ""; fail "Server startup timeout (${MAX_WAIT}s)"
    cat /tmp/demo-multi-server.log | tail -30
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

do_json_check_field() {
    local name="$1"; local method="${2:-POST}"; local url="$3"; local data="$4"; local field="$5"
    TOTAL=$((TOTAL + 1)); printf "%-50s ... " "$name"
    response=$(curl -sf -X "$method" -H "Content-Type: application/json" -d "$data" "${BASE_URL}${url}")
    code=$(curl -sf -o /dev/null -w "%{http_code}" -X "$method" \
        -H "Content-Type: application/json" -d "$data" "${BASE_URL}${url}")
    if [ "$code" = "200" ] && echo "$response" | grep -q "\"$field\""; then
        pass "HTTP ${code} (contains $field)"; PASSED=$((PASSED + 1))
    else
        fail "HTTP ${code} or missing $field"; FAILED=$((FAILED + 1))
    fi
}

echo ""; echo "=========================================="; info "demo-multi-model E2E Tests"; echo "=========================================="; echo ""

# Health check
do_test "actuator/health" GET "/actuator/health"

# Model registry
do_test "GET /demo/models (list)" GET "/demo/models"
do_test "GET /demo/models/deepseek" GET "/demo/models/deepseek" "" "200"
do_test "GET /demo/models/nonexistent (404)" GET "/demo/models/nonexistent" "" "404"

# Model Q&A (single model)
SESSION_ID="multi-e2e-$RANDOM"
do_json_check_field "POST /demo/chat (deepseek)" POST "/demo/chat?provider=deepseek" \
    '{"message":"Hello"}' "answer"

# Model comparison
do_json_check_field "POST /demo/compare (single model)" POST "/demo/compare" \
    '{"query":"Hello","providers":["deepseek"]}' "results"
do_json_check_field "POST /demo/compare (multi-model)" POST "/demo/compare" \
    '{"query":"What is RAG","providers":["deepseek"],"timeoutSeconds":20}' "results"

# Error cases
do_test "POST /demo/compare (empty query -> 400)" POST "/demo/compare" \
    '{"query":"","providers":["deepseek"]}' "400"
do_test "POST /demo/compare (empty providers -> 400)" POST "/demo/compare" \
    '{"query":"hi","providers":[]}' "400"

# Kill server
kill $SERVER_PID 2>/dev/null || true; wait $SERVER_PID 2>/dev/null || true

echo ""; echo "=========================================="
echo "E2E Results: ${PASSED}/${TOTAL} passed"
if [ $FAILED -gt 0 ]; then
    echo -e "${RED}${FAILED} failed${NC}"; exit 1
else
    echo -e "${GREEN}All passed${NC}"; exit 0
fi
