#!/bin/bash
# scripts/demo-component-level-e2e.sh — demo-component-level 模块 E2E 验证
# 启动 demo-component-level → 等待就绪 → curl 测试组件级集成端点
#
# 用法: bash scripts/demo-component-level-e2e.sh [PORT]
# 示例: bash scripts/demo-component-level-e2e.sh 8084

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

# 清理端口
info "清理端口 ${PORT}..."
lsof -ti:${PORT} -sTCP:LISTEN 2>/dev/null | xargs -r kill -9 2>/dev/null || true
sleep 2

# 加载环境变量
if [ -f "$PROJECT_DIR/.env" ]; then
    info "加载 .env 环境变量..."
    set -a; source "$PROJECT_DIR/.env"; set +a
fi

# 启动 demo-component-level
info "启动 demo-component-level (端口 ${PORT})..."
mvn spring-boot:run -Dserver.port="${PORT}" > /tmp/demo-component-server.log 2>&1 &
SERVER_PID=$!

# 等待就绪
info "等待服务器启动 (最多 ${MAX_WAIT}s)..."
for i in $(seq 1 $MAX_WAIT); do
    if curl -sf "${BASE_URL}/actuator/health" > /dev/null 2>&1; then
        echo ""; pass "服务器已就绪 (${i}s)"; break
    fi
    if ! kill -0 $SERVER_PID 2>/dev/null; then
        echo ""; fail "服务器进程意外退出"; cat /tmp/demo-component-server.log | tail -30; exit 1
    fi
    printf "."; sleep 1
done

if [ $i -eq $MAX_WAIT ]; then
    echo ""; fail "服务器启动超时 (${MAX_WAIT}s)"
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
        fail "HTTP ${code} (期望 ${expect_code})"; FAILED=$((FAILED + 1))
    fi
}

do_text() {
    local name="$1"; local url="$2"
    TOTAL=$((TOTAL + 1)); printf "%-50s ... " "$name"
    response=$(curl -sf "${BASE_URL}${url}")
    if [ -n "$response" ] && [ "$response" != "null" ]; then
        pass "$(echo "$response" | head -c 60)"; PASSED=$((PASSED + 1))
    else
        fail "空响应"; FAILED=$((FAILED + 1))
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
        fail "HTTP ${code} 或空响应"; FAILED=$((FAILED + 1))
    fi
}

echo ""; echo "=========================================="; info "demo-component-level E2E 测试"; echo "=========================================="; echo ""

# 健康检查
do_test "actuator/health" GET "/actuator/health"

# 组件级集成 — ChatClient + RAG Advisors
do_text "GET /demo/component/ask?q=hello" "/demo/component/ask?q=hello"

# 组件级集成 — ChatClient + RAG Advisors + Memory（多轮）
do_json "POST /demo/component/chat (第一轮)" POST "/demo/component/chat" \
    '{"message":"你好","sessionId":"comp-e2e-'$RANDOM'"}'
do_json "POST /demo/component/chat (第二轮)" POST "/demo/component/chat" \
    '{"message":"我们刚才说了什么？","sessionId":"comp-e2e-'$RANDOM'"}'

# 对比：带记忆 vs 不带记忆
SESSION_ID="compare-mem-$RANDOM"
do_json "POST /demo/component/compare-memory" POST "/demo/component/compare-memory?sessionId=${SESSION_ID}" \
    '{"q1":"我叫李四","q2":"我叫什么名字？"}'

# 杀掉服务器
kill $SERVER_PID 2>/dev/null || true; wait $SERVER_PID 2>/dev/null || true

echo ""; echo "=========================================="
echo "E2E 测试结果: ${PASSED}/${TOTAL} 通过"
if [ $FAILED -gt 0 ]; then
    echo -e "${RED}${FAILED} 项失败${NC}"; exit 1
else
    echo -e "${GREEN}全部通过${NC}"; exit 0
fi
