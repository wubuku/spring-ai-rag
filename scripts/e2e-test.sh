#!/bin/bash
# ============================================================
# spring-ai-rag 端到端测试脚本
# 用法：export $(cat .env | grep -v '^#' | xargs) && bash scripts/e2e-test.sh
# ============================================================

set -e

BASE_URL="${BASE_URL:-http://localhost:8081}"
API="${BASE_URL}/api/v1/rag"
PASS=0
FAIL=0
DOC_ID=""
COLLECTION_ID=""

# 颜色
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m'

assert_status() {
    local desc="$1" expected="$2" actual="$3"
    if [ "$expected" = "$actual" ]; then
        echo -e "  ${GREEN}✅ PASS${NC} $desc (HTTP $actual)"
        PASS=$((PASS + 1))
    else
        echo -e "  ${RED}❌ FAIL${NC} $desc (期望 HTTP $expected, 实际 HTTP $actual)"
        FAIL=$((FAIL + 1))
    fi
}

assert_contains() {
    local desc="$1" body="$2" expected="$3"
    if echo "$body" | grep -q "$expected"; then
        echo -e "  ${GREEN}✅ PASS${NC} $desc"
        PASS=$((PASS + 1))
    else
        echo -e "  ${RED}❌ FAIL${NC} $desc (未找到: $expected)"
        FAIL=$((FAIL + 1))
    fi
}

echo "=========================================="
echo "  spring-ai-rag 端到端测试"
echo "  Base URL: $BASE_URL"
echo "=========================================="
echo ""

# ────────────────────────────────────────
# 1. 健康检查
# ────────────────────────────────────────
echo "1️⃣  健康检查"
RESP=$(curl -s -w "\n%{http_code}" "$API/health")
CODE=$(echo "$RESP" | tail -1)
BODY=$(echo "$RESP" | sed '$d')
assert_status "GET /health" "200" "$CODE"
assert_contains "返回 status=UP" "$BODY" '"status":"UP"'
assert_contains "返回 database=UP" "$BODY" '"database":"UP"'
echo ""

# ────────────────────────────────────────
# 2. Collection CRUD
# ────────────────────────────────────────
echo "2️⃣  Collection CRUD"
# 2a. 创建 Collection
RESP=$(curl -s -w "\n%{http_code}" -X POST "$API/collections" \
    -H "Content-Type: application/json" \
    -d '{"name":"e2e-test-collection","description":"E2E自动化测试Collection","domainId":"default"}')
CODE=$(echo "$RESP" | tail -1)
BODY=$(echo "$RESP" | sed '$d')
assert_status "POST /collections" "200" "$CODE"
assert_contains "返回 collection ID" "$BODY" '"id"'
assert_contains "返回 name" "$BODY" '"name"'
COLLECTION_ID=$(echo "$BODY" | grep -o '"id":[0-9]*' | grep -o '[0-9]*')
echo "  📦 创建的 Collection ID: $COLLECTION_ID"

# 2b. 列出 Collections
RESP=$(curl -s -w "\n%{http_code}" "$API/collections?offset=0&limit=10")
CODE=$(echo "$RESP" | tail -1)
BODY=$(echo "$RESP" | sed '$d')
assert_status "GET /collections" "200" "$CODE"
assert_contains "返回 collections 数组" "$BODY" '"collections"'
assert_contains "返回 total" "$BODY" '"total"'

# 2c. 获取单个 Collection
if [ -n "$COLLECTION_ID" ]; then
    RESP=$(curl -s -w "\n%{http_code}" "$API/collections/$COLLECTION_ID")
    CODE=$(echo "$RESP" | tail -1)
    BODY=$(echo "$RESP" | sed '$d')
    assert_status "GET /collections/{id}" "200" "$CODE"
    assert_contains "返回正确 name" "$BODY" '"name"'
    assert_contains "返回 description" "$BODY" '"description"'
else
    echo -e "  ${YELLOW}⚠️ SKIP${NC} 无 collection ID"
fi

# 2d. 更新 Collection
if [ -n "$COLLECTION_ID" ]; then
    RESP=$(curl -s -w "\n%{http_code}" -X PUT "$API/collections/$COLLECTION_ID" \
        -H "Content-Type: application/json" \
        -d '{"name":"e2e-updated-collection","description":"E2E 自动化测试 Collection（已更新）","domainId":"default"}')
    CODE=$(echo "$RESP" | tail -1)
    BODY=$(echo "$RESP" | sed '$d')
    assert_status "PUT /collections/{id}" "200" "$CODE"
    assert_contains "返回更新后的 name" "$BODY" "e2e-updated-collection"
else
    echo -e "  ${YELLOW}⚠️ SKIP${NC} 无 collection ID"
fi

# 2e. 获取 Collection 内的文档列表
if [ -n "$COLLECTION_ID" ]; then
    RESP=$(curl -s -w "\n%{http_code}" "$API/collections/$COLLECTION_ID/documents?offset=0&limit=10")
    CODE=$(echo "$RESP" | tail -1)
    BODY=$(echo "$RESP" | sed '$d')
    assert_status "GET /collections/{id}/documents" "200" "$CODE"
    assert_contains "返回 documents 数组" "$BODY" '"documents"'
else
    echo -e "  ${YELLOW}⚠️ SKIP${NC} 无 collection ID"
fi
echo ""

# ────────────────────────────────────────
# 3. 创建文档（含 JSONB metadata）
# ────────────────────────────────────────
echo "3️⃣  创建文档"
RESP=$(curl -s -w "\n%{http_code}" -X POST "$API/documents" \
    -H "Content-Type: application/json" \
    -d '{"title":"E2E自动化测试文档","content":"这是端到端测试创建的文档，用于验证CRUD和嵌入向量生成。Spring AI RAG 提供混合检索、查询改写和结果重排能力。向量存储使用PostgreSQL的pgvector扩展，支持HNSW索引实现高效的相似度搜索。嵌入模型使用BGE-M3，输出1024维向量。对话记忆通过Spring AI的MessageChatMemoryAdvisor实现，支持短期和长期记忆。领域扩展通过DomainRagExtension接口实现，支持自定义Prompt模板和检索配置。文档分块使用HierarchicalTextChunker，支持Markdown标题和段落级别的智能分块。","source":"e2e-test","documentType":"text","metadata":{"author":"e2e-script","priority":"high"}}')
CODE=$(echo "$RESP" | tail -1)
BODY=$(echo "$RESP" | sed '$d')
assert_status "POST /documents" "200" "$CODE"
assert_contains "返回文档ID" "$BODY" '"id"'
assert_contains "返回CREATED状态" "$BODY" '"CREATED"'
DOC_ID=$(echo "$BODY" | grep -o '"id":[0-9]*' | grep -o '[0-9]*')
echo "  📄 创建的文档ID: $DOC_ID"

# 3f. 将文档添加到 Collection
if [ -n "$DOC_ID" ] && [ -n "$COLLECTION_ID" ]; then
    RESP=$(curl -s -w "\n%{http_code}" -X POST "$API/collections/$COLLECTION_ID/documents" \
        -H "Content-Type: application/json" \
        -d "{\"documentId\":$DOC_ID}")
    CODE=$(echo "$RESP" | tail -1)
    BODY=$(echo "$RESP" | sed '$d')
    assert_status "POST /collections/{id}/documents (关联)" "200" "$CODE"
    assert_contains "返回 documentId" "$BODY" '"documentId"'
else
    echo -e "  ${YELLOW}⚠️ SKIP${NC} 无 document ID 或 collection ID"
fi
echo ""

# ────────────────────────────────────────
# 3. 获取文档详情
# ────────────────────────────────────────
echo "4️⃣  获取文档详情"
RESP=$(curl -s -w "\n%{http_code}" "$API/documents/$DOC_ID")
CODE=$(echo "$RESP" | tail -1)
BODY=$(echo "$RESP" | sed '$d')
assert_status "GET /documents/$DOC_ID" "200" "$CODE"
assert_contains "返回正确标题" "$BODY" "E2E自动化测试文档"
assert_contains "返回metadata(JSONB)" "$BODY" '"author":"e2e-script"'
assert_contains "返回embeddingCount" "$BODY" '"embeddingCount"'
echo ""

# ────────────────────────────────────────
# 4. 文档列表（分页）
# ────────────────────────────────────────
echo "5️⃣  文档列表"
RESP=$(curl -s -w "\n%{http_code}" "$API/documents?offset=0&limit=5")
CODE=$(echo "$RESP" | tail -1)
BODY=$(echo "$RESP" | sed '$d')
assert_status "GET /documents" "200" "$CODE"
assert_contains "返回documents数组" "$BODY" '"documents"'
assert_contains "返回total" "$BODY" '"total"'
echo ""

# ────────────────────────────────────────
# 5. 生成嵌入向量
# ────────────────────────────────────────
echo "6️⃣  生成嵌入向量"
if [ -n "$DOC_ID" ]; then
    RESP=$(curl -s -w "\n%{http_code}" -X POST "$API/documents/$DOC_ID/embed")
    CODE=$(echo "$RESP" | tail -1)
    BODY=$(echo "$RESP" | sed '$d')
    assert_status "POST /documents/$DOC_ID/embed" "200" "$CODE"
    assert_contains "返回chunksCreated" "$BODY" '"chunksCreated"'
    assert_contains "返回COMPLETED状态" "$BODY" '"COMPLETED"'
else
    echo -e "  ${YELLOW}⚠️ SKIP${NC} 无文档ID"
fi
echo ""

# ────────────────────────────────────────
# 6. 直接检索
# ────────────────────────────────────────
echo "7️⃣  直接检索"
RESP=$(curl -s -w "\n%{http_code}" "$API/search?query=Spring%20AI&maxResults=3")
CODE=$(echo "$RESP" | tail -1)
BODY=$(echo "$RESP" | sed '$d')
assert_status "GET /search" "200" "$CODE"
assert_contains "返回results数组" "$BODY" '"results"'
echo ""

# ────────────────────────────────────────
# 7. RAG 问答
# ────────────────────────────────────────
echo "8️⃣  RAG 问答 (非流式)"
RESP=$(curl -s -w "\n%{http_code}" -X POST "$API/chat/ask" \
    -H "Content-Type: application/json" \
    -d '{"message":"Spring AI是什么？","sessionId":"e2e-test-session","maxResults":3}')
CODE=$(echo "$RESP" | tail -1)
BODY=$(echo "$RESP" | sed '$d')
assert_status "POST /chat/ask" "200" "$CODE"
# 如果没有配置LLM key，可能返回错误，但端点本身应该可达
if [ "$CODE" = "200" ]; then
    assert_contains "返回answer或error" "$BODY" '"answer"'
else
    echo -e "  ${YELLOW}⚠️ INFO${NC} /chat/ask 返回非200（可能未配置LLM key）"
fi
echo ""

# ────────────────────────────────────────
# 8. 流式响应
# ────────────────────────────────────────
echo "9️⃣  流式响应 (SSE)"
HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$API/chat/stream" \
    -H "Content-Type: application/json" \
    -H "Accept: text/event-stream" \
    -d '{"message":"你好","sessionId":"e2e-stream-test"}')
assert_status "POST /chat/stream" "200" "$HTTP_CODE"
echo ""

# ────────────────────────────────────────
# 9. 对话历史
# ────────────────────────────────────────
echo "🔟 对话历史"
RESP=$(curl -s -w "\n%{http_code}" "$API/chat/history/e2e-test-session?limit=10")
CODE=$(echo "$RESP" | tail -1)
BODY=$(echo "$RESP" | sed '$d')
assert_status "GET /chat/history/{sessionId}" "200" "$CODE"
echo ""

# ────────────────────────────────────────
# 10. 删除文档
# ────────────────────────────────────────
echo "1️⃣1️⃣ 删除文档"
if [ -n "$DOC_ID" ]; then
    RESP=$(curl -s -w "\n%{http_code}" -X DELETE "$API/documents/$DOC_ID")
    CODE=$(echo "$RESP" | tail -1)
    BODY=$(echo "$RESP" | sed '$d')
    assert_status "DELETE /documents/$DOC_ID" "200" "$CODE"
    assert_contains "确认删除" "$BODY" "文档已删除"

    # 验证已删除
    RESP2=$(curl -s -w "\n%{http_code}" "$API/documents/$DOC_ID")
    CODE2=$(echo "$RESP2" | tail -1)
    assert_status "GET 已删除文档返回404" "404" "$CODE2"
else
    echo -e "  ${YELLOW}⚠️ SKIP${NC} 无文档ID"
fi
echo ""

# ────────────────────────────────────────
# 1️⃣2️⃣ 缓存统计
# ────────────────────────────────────────
echo "1️⃣2️⃣ 缓存统计"
RESP=$(curl -s -w "\n%{http_code}" "$API/cache/stats")
CODE=$(echo "$RESP" | tail -1)
BODY=$(echo "$RESP" | sed '$d')
assert_status "GET /cache/stats" "200" "$CODE"
assert_contains "返回 hitCount" "$BODY" '"hitCount"'
echo ""

# ────────────────────────────────────────
# 1️⃣3️⃣ RAG 指标概览
# ────────────────────────────────────────
echo "1️⃣3️⃣ RAG 指标概览"
RESP=$(curl -s -w "\n%{http_code}" "$API/metrics")
CODE=$(echo "$RESP" | tail -1)
BODY=$(echo "$RESP" | sed '$d')
assert_status "GET /metrics" "200" "$CODE"
assert_contains "返回指标数据" "$BODY" '"totalRequests"'
echo ""

# ────────────────────────────────────────
# 1️⃣4️⃣ 清理 Collection
# ────────────────────────────────────────
echo "1️⃣4️⃣ 清理 Collection"
if [ -n "$COLLECTION_ID" ]; then
    RESP=$(curl -s -w "\n%{http_code}" -X DELETE "$API/collections/$COLLECTION_ID")
    CODE=$(echo "$RESP" | tail -1)
    BODY=$(echo "$RESP" | sed '$d')
    assert_status "DELETE /collections/{id}" "200" "$CODE"
    assert_contains "确认删除" "$BODY" "集合已删除"
else
    echo -e "  ${YELLOW}⚠️ SKIP${NC} 无 collection ID"
fi
echo ""

# ────────────────────────────────────────
# 汇总
# ────────────────────────────────────────
echo "=========================================="
TOTAL=$((PASS + FAIL))
echo -e "测试完成: ${GREEN}$PASS 通过${NC} / ${RED}$FAIL 失败${NC} / $TOTAL 总计"
echo "=========================================="

if [ $FAIL -gt 0 ]; then
    exit 1
fi
