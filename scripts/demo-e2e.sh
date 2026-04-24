#!/bin/bash
# scripts/demo-e2e.sh — Complete demo E2E verification script
# Automatically starts server -> waits for readiness -> executes curl tests -> reports results
#
# Usage: bash scripts/demo-e2e.sh [PORT]
# Example: bash scripts/demo-e2e.sh 8081

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
PORT="${1:-8081}"
BASE_URL="http://localhost:${PORT}"
MAX_WAIT=60

# Color output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

pass() { echo -e "${GREEN}[PASS]${NC} $1"; }
fail() { echo -e "${RED}[FAIL]${NC} $1"; }
info() { echo -e "${YELLOW}[INFO]${NC} $1"; }

cd "$PROJECT_DIR"

# Kill old processes occupying the port
info "Cleaning up port ${PORT}..."
lsof -ti:${PORT} -sTCP:LISTEN 2>/dev/null | xargs -r kill -9 2>/dev/null || true
sleep 2

# Load environment variables
if [ -f "$PROJECT_DIR/.env" ]; then
    info "Loading .env environment variables..."
    set -a
    source "$PROJECT_DIR/.env"
    set +a
fi

# Start server
info "Starting spring-ai-rag-core (port ${PORT})..."
mvn spring-boot:run -pl spring-ai-rag-core -Dserver.port="${PORT}" > /tmp/demo-server.log 2>&1 &
SERVER_PID=$!

# Wait for server readiness
info "Waiting for server startup (up to ${MAX_WAIT}s)..."
for i in $(seq 1 $MAX_WAIT); do
    if curl -sf "${BASE_URL}/api/v1/rag/health" > /dev/null 2>&1; then
        echo ""
        pass "Server ready (${i}s)"
        break
    fi
    if ! kill -0 $SERVER_PID 2>/dev/null; then
        echo ""
        fail "Server process exited unexpectedly"
        cat /tmp/demo-server.log | tail -30
        exit 1
    fi
    printf "."
    sleep 1
done

if [ $i -eq $MAX_WAIT ]; then
    echo ""
    fail "Server startup timeout (${MAX_WAIT}s)"
    cat /tmp/demo-server.log | tail -30
    kill $SERVER_PID 2>/dev/null || true
    exit 1
fi

# Execute tests
TOTAL=0
PASSED=0
FAILED=0

do_test() {
    local name="$1"
    local method="${2:-GET}"
    local url="$3"
    local data="$4"
    local expect_code="${5:-200}"

    TOTAL=$((TOTAL + 1))
    printf "%-45s ... " "$name"

    if [ "$method" = "GET" ]; then
        code=$(curl -sf -o /dev/null -w "%{http_code}" "${BASE_URL}${url}")
    else
        code=$(curl -sf -o /dev/null -w "%{http_code}" -X "$method" \
            -H "Content-Type: application/json" \
            -d "$data" "${BASE_URL}${url}")
    fi

    if [ "$code" = "$expect_code" ]; then
        pass "HTTP ${code}"
        PASSED=$((PASSED + 1))
    else
        fail "HTTP ${code} (expected ${expect_code})"
        FAILED=$((FAILED + 1))
    fi
}

echo ""
echo "=========================================="
info "Starting E2E Tests"
echo "=========================================="
echo ""

# 1. Health check
do_test "Health check" GET "/api/v1/rag/health"

# 2. Document operation - create document
DOC_ID=$(curl -sf -X POST "${BASE_URL}/api/v1/rag/documents" \
    -H "Content-Type: application/json" \
    -d '{"title":"E2E Test Document","content":"This is a test content to verify the RAG pipeline is working correctly.","collectionId":null}' | \
    python3 -c "import sys,json; print(json.load(sys.stdin).get('id','0'))" 2>/dev/null || echo "0")
if [ "$DOC_ID" != "0" ] && [ -n "$DOC_ID" ]; then
    pass "Created document ID=${DOC_ID}"
    TOTAL=$((TOTAL + 1)); PASSED=$((PASSED + 1))
else
    fail "Create document"
    TOTAL=$((TOTAL + 1)); FAILED=$((FAILED + 1))
    DOC_ID=""
fi

# 3. Document embedding (poll until complete)
if [ -n "$DOC_ID" ] && [ "$DOC_ID" != "0" ]; then
    info "Waiting for embedding to complete (up to 30s)..."
    for i in $(seq 1 30); do
        status=$(curl -sf "${BASE_URL}/api/v1/rag/documents/${DOC_ID}" | \
            python3 -c "import sys,json; print(json.load(sys.stdin).get('processingStatus','UNKNOWN'))" 2>/dev/null || echo "UNKNOWN")
        if [ "$status" = "COMPLETED" ]; then
            pass "Embedding complete (${i}s)"
            TOTAL=$((TOTAL + 1)); PASSED=$((PASSED + 1))
            break
        fi
        printf "."
        sleep 1
    done
    if [ $i -eq 30 ]; then
        fail "Embedding timeout"
        TOTAL=$((TOTAL + 1)); FAILED=$((FAILED + 1))
    fi

    # 4. Search
    do_test "Hybrid search" POST "/api/v1/rag/search" '{"query":"RAG pipeline test","maxResults":3}'

    # 5. Delete document
    do_test "Delete document" DELETE "/api/v1/rag/documents/${DOC_ID}"
fi

# 6. Metrics endpoint
do_test "Metrics endpoint" GET "/api/v1/rag/metrics"

# 7. Component health
do_test "Component health" GET "/api/v1/rag/health/components"

# 8. Chat history (empty session)
SESSION_ID="e2e-test-$(date +%s)"
do_test "Chat history (empty)" GET "/api/v1/rag/chat/history/${SESSION_ID}"

# 9. Document list
do_test "Document list" GET "/api/v1/rag/documents?page=0&size=10"

# 10. Collection list
do_test "Collection list" GET "/api/v1/rag/collections?page=0&size=10"

# Cleanup
kill $SERVER_PID 2>/dev/null || true
wait $SERVER_PID 2>/dev/null || true

echo ""
echo "=========================================="
echo "E2E Results: ${PASSED}/${TOTAL} passed"
if [ $FAILED -gt 0 ]; then
    echo -e "${RED}${FAILED} failed${NC}"
    exit 1
else
    echo -e "${GREEN}All passed${NC}"
    exit 0
fi
