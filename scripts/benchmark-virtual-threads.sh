#!/bin/bash
# =============================================================================
# 虚拟线程性能压测脚本
# 验证 Spring Boot 3.5 + Java 21 虚拟线程在高并发下的表现
# =============================================================================

set -e

BASE_URL="${BASE_URL:-http://localhost:8081}"
CONCURRENT="${CONCURRENT:-50}"
REQUESTS="${REQUESTS:-200}"
DURATION="${DURATION:-30}"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

pass() { echo -e "${GREEN}✅ PASS${NC} $1"; }
fail() { echo -e "${RED}❌ FAIL${NC} $1"; }
info() { echo -e "${YELLOW}ℹ️  INFO${NC} $1"; }

echo "=========================================="
echo "  虚拟线程性能压测"
echo "=========================================="
echo "  Base URL:    $BASE_URL"
echo "  Concurrent:   $CONCURRENT 并发连接"
echo "  Total:       $REQUESTS 请求"
echo "  Duration:    ${DURATION}s"
echo "=========================================="

# Check server is up
echo ""
info "检查服务器健康状态..."
HEALTH=$(curl -s --connect-timeout 3 "$BASE_URL/api/v1/rag/health" 2>/dev/null | python3 -c "import sys,json; print(json.load(sys.stdin).get('status','DOWN'))" 2>/dev/null || echo "DOWN")
if [ "$HEALTH" != "UP" ]; then
    fail "服务器未就绪 (status=$HEALTH)"
    exit 1
fi
pass "服务器健康: $HEALTH"

# Pre-warm
echo ""
info "预热请求..."
for i in {1..5}; do
    curl -s --connect-timeout 2 --max-time 5 "$BASE_URL/api/v1/rag/health" > /dev/null 2>&1 || true
done
pass "预热完成"

# Simple concurrent test using curl parallel
echo ""
info "开始压测..."

START_TIME=$(date +%s%3N)
SUCCESS=0
FAIL=0
TIMEOUT=0

# Use GNU parallel or xargs for concurrency
if command -v parallel &>/dev/null; then
    # Use GNU parallel
    seq 1 $REQUESTS | parallel -j $CONCURRENT --halt soon,fail=1 "\
        curl -s --connect-timeout 3 --max-time 10 \
        -X POST '$BASE_URL/api/v1/rag/chat/ask' \
        -H 'Content-Type: application/json' \
        -d '{\"message\":\"并发测试 $(({1}%10)+1)\",\"sessionId\":\"bench-{} \"}' \
        > /dev/null 2>&1 && echo 1 || echo 0" \
        | awk '{if($1==1) success++; else fail++} END{print "success="success,"fail="fail}'
else
    # Fallback: sequential with background jobs
    for i in $(seq 1 $REQUESTS); do
        (
            curl -s --connect-timeout 3 --max-time 10 \
            -X POST "$BASE_URL/api/v1/rag/chat/ask" \
            -H "Content-Type: application/json" \
            -d "{\"message\":\"并发测试 $((i%10+1))\",\"sessionId\":\"bench-$i\"}" \
            > /dev/null 2>&1 && echo 1 || echo 0
        ) &
        # Throttle to avoid too many processes
        if (( i % CONCURRENT == 0 )); then wait; fi
    done | awk '{if($1==1) success++; else fail++} END{print "success="success,"fail="fail}'
fi > /tmp/bench_result.txt

END_TIME=$(date +%s%3N)
ELAPSED=$((END_TIME - START_TIME))

# Parse results
RESULT=$(cat /tmp/bench_result.txt 2>/dev/null || echo "success=0 fail=0")
SUCCESS=$(echo "$RESULT" | sed 's/.*success=\([0-9]*\).*/\1/')
FAIL=$(echo "$RESULT" | sed 's/.*fail=\([0-9]*\).*/\1/')
TOTAL=$((SUCCESS + FAIL))

echo ""
echo "=========================================="
echo "  压测结果"
echo "=========================================="
echo "  总请求:     $TOTAL"
echo "  成功:       $SUCCESS"
echo "  失败:       $FAIL"
echo "  耗时:       ${ELAPSED}ms"
echo "  QPS:        $(echo "scale=2; $TOTAL * 1000 / $ELAPSED" | bc)"
echo "=========================================="

# Evaluate results
if [ "$TOTAL" -eq 0 ]; then
    fail "没有收到任何响应"
    exit 1
fi

SUCCESS_RATE=$(echo "scale=1; $SUCCESS * 100 / $TOTAL" | bc)
info "成功率: ${SUCCESS_RATE}%"

if [ "$SUCCESS_RATE" -ge 95 ]; then
    pass "压测通过 (成功率 >= 95%)"
    exit 0
elif [ "$SUCCESS_RATE" -ge 80 ]; then
    info "成功率一般 (80-95%)"
    exit 0
else
    fail "成功率过低 (< 80%)"
    exit 1
fi
