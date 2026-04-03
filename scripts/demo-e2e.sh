#!/bin/bash
# scripts/demo-e2e.sh — 完整的 Demo E2E 验证脚本
# 自动启动服务器 → 等待就绪 → 执行 curl 测试 → 报告结果
#
# 用法: bash scripts/demo-e2e.sh [PORT]
# 示例: bash scripts/demo-e2e.sh 8081

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
PORT="${1:-8081}"
BASE_URL="http://localhost:${PORT}"
MAX_WAIT=60

# 颜色输出
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

pass() { echo -e "${GREEN}[PASS]${NC} $1"; }
fail() { echo -e "${RED}[FAIL]${NC} $1"; }
info() { echo -e "${YELLOW}[INFO]${NC} $1"; }

cd "$PROJECT_DIR"

# 杀掉占用端口的旧进程
info "清理端口 ${PORT}..."
lsof -ti:${PORT} -sTCP:LISTEN 2>/dev/null | xargs -r kill -9 2>/dev/null || true
sleep 2

# 加载环境变量
if [ -f "$PROJECT_DIR/.env" ]; then
    info "加载 .env 环境变量..."
    set -a
    source "$PROJECT_DIR/.env"
    set +a
fi

# 启动服务器
info "启动 spring-ai-rag-core (端口 ${PORT})..."
mvn spring-boot:run -pl spring-ai-rag-core -Dserver.port="${PORT}" > /tmp/demo-server.log 2>&1 &
SERVER_PID=$!

# 等待服务器就绪
info "等待服务器启动 (最多 ${MAX_WAIT}s)..."
for i in $(seq 1 $MAX_WAIT); do
    if curl -sf "${BASE_URL}/api/v1/rag/health" > /dev/null 2>&1; then
        echo ""
        pass "服务器已就绪 (${i}s)"
        break
    fi
    if ! kill -0 $SERVER_PID 2>/dev/null; then
        echo ""
        fail "服务器进程意外退出"
        cat /tmp/demo-server.log | tail -30
        exit 1
    fi
    printf "."
    sleep 1
done

if [ $i -eq $MAX_WAIT ]; then
    echo ""
    fail "服务器启动超时 (${MAX_WAIT}s)"
    cat /tmp/demo-server.log | tail -30
    kill $SERVER_PID 2>/dev/null || true
    exit 1
fi

# 执行测试
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
        fail "HTTP ${code} (期望 ${expect_code})"
        FAILED=$((FAILED + 1))
    fi
}

echo ""
echo "=========================================="
info "开始 E2E 测试"
echo "=========================================="
echo ""

# 1. 健康检查
do_test "健康检查" GET "/api/v1/rag/health"

# 2. 文档操作 - 创建文档
DOC_ID=$(curl -sf -X POST "${BASE_URL}/api/v1/rag/documents" \
    -H "Content-Type: application/json" \
    -d '{"title":"E2E 测试文档","content":"这是一段测试内容，用于验证 RAG 管道是否正常工作。","collectionId":null}' | \
    python3 -c "import sys,json; print(json.load(sys.stdin).get('id','0'))" 2>/dev/null || echo "0")
if [ "$DOC_ID" != "0" ] && [ -n "$DOC_ID" ]; then
    pass "创建文档 ID=${DOC_ID}"
    TOTAL=$((TOTAL + 1)); PASSED=$((PASSED + 1))
else
    fail "创建文档"
    TOTAL=$((TOTAL + 1)); FAILED=$((FAILED + 1))
    DOC_ID=""
fi

# 3. 文档嵌入（轮询直到完成）
if [ -n "$DOC_ID" ] && [ "$DOC_ID" != "0" ]; then
    info "等待嵌入完成 (最多 30s)..."
    for i in $(seq 1 30); do
        status=$(curl -sf "${BASE_URL}/api/v1/rag/documents/${DOC_ID}" | \
            python3 -c "import sys,json; print(json.load(sys.stdin).get('processingStatus','UNKNOWN'))" 2>/dev/null || echo "UNKNOWN")
        if [ "$status" = "COMPLETED" ]; then
            pass "嵌入完成 (${i}s)"
            TOTAL=$((TOTAL + 1)); PASSED=$((PASSED + 1))
            break
        fi
        printf "."
        sleep 1
    done
    if [ $i -eq 30 ]; then
        fail "嵌入超时"
        TOTAL=$((TOTAL + 1)); FAILED=$((FAILED + 1))
    fi

    # 4. 检索
    do_test "混合检索" POST "/api/v1/rag/search" '{"query":"RAG 管道测试","maxResults":3}'

    # 5. 删除文档
    do_test "删除文档" DELETE "/api/v1/rag/documents/${DOC_ID}"
fi

# 6. 指标端点
do_test "指标端点" GET "/api/v1/rag/metrics"

# 7. 组件健康检查
do_test "组件健康" GET "/api/v1/rag/health/components"

# 8. 历史记录（空session）
SESSION_ID="e2e-test-$(date +%s)"
do_test "对话历史(空)" GET "/api/v1/rag/chat/history/${SESSION_ID}"

# 9. 文档列表
do_test "文档列表" GET "/api/v1/rag/documents?page=0&size=10"

# 10. 集合列表
do_test "集合列表" GET "/api/v1/rag/collections?page=0&size=10"

# 清理
kill $SERVER_PID 2>/dev/null || true
wait $SERVER_PID 2>/dev/null || true

echo ""
echo "=========================================="
echo "E2E 测试结果: ${PASSED}/${TOTAL} 通过"
if [ $FAILED -gt 0 ]; then
    echo -e "${RED}${FAILED} 项失败${NC}"
    exit 1
else
    echo -e "${GREEN}全部通过${NC}"
    exit 0
fi
