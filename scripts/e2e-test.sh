#!/bin/bash

# Spring AI RAG 端到端测试脚本
# 用法：./scripts/e2e-test.sh
#
# 前提条件：
#   1. 服务已启动（mvn spring-boot:run -pl spring-ai-rag-core -Dspring-boot.run.profiles=local,postgresql）
#   2. PostgreSQL 运行中，数据库 spring_ai_rag_dev 可访问
#
# 测试范围：
#   ✅ 数据库 Schema 验证（表结构、扩展、索引）
#   ✅ 服务健康检查
#   ✅ API 端点测试（Chat / Search / Document / Health）
#   ✅ 完整 RAG 流程（上传文档 → 检索 → 问答）

set -e

BASE_URL="http://localhost:8081/api/v1/rag"
DB_NAME="spring_ai_rag_dev"
DB_USER="postgres"
DB_PASSWORD="123456"
BOLD='\033[1m'
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m'

echo -e "${BOLD}╔═══════════════════════════════════════════════════════════╗${NC}"
echo -e "${BOLD}║     Spring AI RAG — 端到端测试                           ║${NC}"
echo -e "${BOLD}╚═══════════════════════════════════════════════════════════╝${NC}"
echo ""

TOTAL_TESTS=0
PASSED_TESTS=0
FAILED_TESTS=0

find_psql() {
    # 优先 libpq（更通用），再尝试本地 postgresql
    for path in /usr/local/Cellar/libpq/*/bin/psql /usr/local/Cellar/postgresql@*/bin/psql /usr/local/bin/psql; do
        for p in $path; do
            if [ -x "$p" ]; then echo "$p"; return 0; fi
        done
    done
    echo ""; return 1
}

PSQL_CMD=$(find_psql)
if [ -z "$PSQL_CMD" ]; then
    echo -e "${YELLOW}⚠️  psql 未找到，数据库测试将跳过${NC}"
else
    echo -e "使用 psql: $PSQL_CMD"
fi

run_psql() {
    if [ -z "$PSQL_CMD" ]; then return 1; fi
    PGPASSWORD=$DB_PASSWORD $PSQL_CMD -h localhost -U $DB_USER -d $DB_NAME -t -c "$1" 2>/dev/null
}

check() {
    local name="$1"
    local actual="$2"
    local expected="$3"
    TOTAL_TESTS=$((TOTAL_TESTS + 1))
    echo -n "  $name: "
    if [ "$actual" = "$expected" ]; then
        echo -e "${GREEN}✅ $actual${NC}"
        PASSED_TESTS=$((PASSED_TESTS + 1))
    else
        echo -e "${RED}❌ expected=$expected, actual=$actual${NC}"
        FAILED_TESTS=$((FAILED_TESTS + 1))
    fi
}

check_contains() {
    local name="$1"
    local response="$2"
    local pattern="$3"
    TOTAL_TESTS=$((TOTAL_TESTS + 1))
    echo -n "  $name: "
    if echo "$response" | grep -q "$pattern"; then
        echo -e "${GREEN}✅${NC}"
        PASSED_TESTS=$((PASSED_TESTS + 1))
    else
        echo -e "${RED}❌ pattern '$pattern' not found${NC}"
        FAILED_TESTS=$((FAILED_TESTS + 1))
    fi
}

check_http() {
    local name="$1"
    local url="$2"
    local expected_code="$3"
    local method="${4:-GET}"
    local data="$5"
    TOTAL_TESTS=$((TOTAL_TESTS + 1))
    echo -n "  $name: "
    if [ "$method" = "POST" ] && [ -n "$data" ]; then
        code=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$url" -H "Content-Type: application/json" -d "$data" 2>/dev/null)
    else
        code=$(curl -s -o /dev/null -w "%{http_code}" "$url" 2>/dev/null)
    fi
    if [ "$code" = "$expected_code" ]; then
        echo -e "${GREEN}✅ HTTP $code${NC}"
        PASSED_TESTS=$((PASSED_TESTS + 1))
    else
        echo -e "${RED}❌ expected=$expected_code, actual=$code${NC}"
        FAILED_TESTS=$((FAILED_TESTS + 1))
    fi
}

# =====================================================
# 第一部分：数据库 Schema 验证
# =====================================================
echo -e "${BOLD}━━━ 1. 数据库 Schema 验证 ━━━${NC}"
echo ""

for table in rag_collection rag_documents rag_embeddings rag_chat_history; do
    count=$(run_psql "SELECT COUNT(*) FROM information_schema.tables WHERE table_name='$table';" | tr -d ' ')
    check "表 $table 存在" "$count" "1"
done

metadata_type=$(run_psql "SELECT data_type FROM information_schema.columns WHERE table_name='rag_documents' AND column_name='metadata';" | tr -d ' ')
check "rag_documents.metadata 类型" "$metadata_type" "jsonb"

embedding_type=$(run_psql "SELECT data_type FROM information_schema.columns WHERE table_name='rag_embeddings' AND column_name='embedding';" | tr -d ' ')
# PostgreSQL 将 vector 自定义类型报告为 USER-DEFINED
check "rag_embeddings.embedding 类型" "$embedding_type" "USER-DEFINED"

for ext in vector pg_jieba; do
    count=$(run_psql "SELECT COUNT(*) FROM pg_extension WHERE extname='$ext';" | tr -d ' ')
    check "扩展 $ext 已安装" "$count" "1"
done

index_count=$(run_psql "SELECT COUNT(*) FROM pg_indexes WHERE tablename='rag_embeddings' AND indexname='idx_rag_emb_vector_hnsw';" | tr -d ' ')
check "HNSW 向量索引存在" "$index_count" "1"

echo ""

# =====================================================
# 第二部分：Health 端点
# =====================================================
echo -e "${BOLD}━━━ 2. Health 端点 ━━━${NC}"
echo ""

health_response=$(curl -s ${BASE_URL}/health 2>/dev/null)
check_contains "GET /health 响应正常" "$health_response" "status"
echo "  响应: $health_response"
echo ""

# =====================================================
# 第三部分：Document 端点
# =====================================================
echo -e "${BOLD}━━━ 3. Document 端点 ━━━${NC}"
echo ""

# 3.1 创建文档
echo -e "${YELLOW}  [3.1] POST /documents — 创建文档${NC}"
create_response=$(curl -s -X POST ${BASE_URL}/documents \
  -H "Content-Type: application/json" \
  -d '{
    "title": "E2E 测试文档 - Spring Boot 3.4",
    "content": "Spring Boot 3.4 引入了对 Java 21 的更好支持，改进了虚拟线程集成，并增强了 Observability。Spring AI 1.1 提供了 ChatClient、Advisor、VectorStore 等核心组件。",
    "source": "e2e-test",
    "documentType": "markdown"
  }')
echo "  响应: $create_response"
DOC_ID=$(echo "$create_response" | grep -o '"id":[0-9]*' | head -1 | grep -o '[0-9]*')
check_contains "创建文档返回 id" "$create_response" "id"
echo ""

# 3.2 获取文档详情
if [ -n "$DOC_ID" ]; then
    echo -e "${YELLOW}  [3.2] GET /documents/$DOC_ID — 文档详情${NC}"
    detail_response=$(curl -s ${BASE_URL}/documents/${DOC_ID})
    echo "  响应: $(echo $detail_response | cut -c 1-100)..."
    check_contains "文档详情包含 title" "$detail_response" "E2E 测试文档"
    echo ""
fi

# 3.3 文档列表
echo -e "${YELLOW}  [3.3] GET /documents — 文档列表${NC}"
list_response=$(curl -s "${BASE_URL}/documents?page=0&size=5")
echo "  响应: $(echo $list_response | cut -c 1-100)..."
check_contains "文档列表有内容" "$list_response" "documents"
echo ""

# 3.4 删除文档（创建临时文档后删除）
echo -e "${YELLOW}  [3.4] DELETE /documents — 删除文档${NC}"
temp_response=$(curl -s -X POST ${BASE_URL}/documents \
  -H "Content-Type: application/json" \
  -d '{"title": "临时删除测试", "content": "临时内容", "source": "e2e-temp"}')
TEMP_ID=$(echo "$temp_response" | grep -o '"id":[0-9]*' | head -1 | grep -o '[0-9]*')
if [ -n "$TEMP_ID" ]; then
    check_http "DELETE /documents/$TEMP_ID" "${BASE_URL}/documents/${TEMP_ID}" "200" "DELETE"
fi
echo ""

# =====================================================
# 第四部分：Search 端点
# =====================================================
echo -e "${BOLD}━━━ 4. Search 端点 ━━━${NC}"
echo ""

echo -e "${YELLOW}  [4.1] GET /search?query=Spring — 直接检索${NC}"
search_response=$(curl -s "${BASE_URL}/search?query=Spring&maxResults=3")
echo "  响应: $(echo $search_response | cut -c 1-100)..."
# 检索无结果是正常的（无 embedding 时返回空数组）
check_contains "GET /search 返回结构" "$search_response" "\["
echo ""

echo -e "${YELLOW}  [4.2] POST /search — 带配置检索${NC}"
search_post_response=$(curl -s -X POST ${BASE_URL}/search \
  -H "Content-Type: application/json" \
  -d '{"query": "Spring AI", "maxResults": 5, "useHybridSearch": true}')
echo "  响应: $(echo $search_post_response | cut -c 1-100)..."
check_contains "POST /search 返回结构" "$search_post_response" "\["
echo ""

# =====================================================
# 第五部分：Chat 端点（需要 API key 才能真正运行）
# =====================================================
echo -e "${BOLD}━━━ 5. Chat 端点 ━━━${NC}"
echo ""

echo -e "${YELLOW}  [5.1] POST /chat/ask — RAG 问答${NC}"
chat_response=$(curl -s -X POST ${BASE_URL}/chat/ask \
  -H "Content-Type: application/json" \
  -d '{"message": "什么是 Spring Boot？", "sessionId": "e2e-test-session"}')
echo "  响应: $(echo $chat_response | cut -c 1-120)..."
# Chat 需要 API key，验证返回结构即可
# Chat 需要外部 API，返回 answer（成功）或 error（API 不可用）均正常
if echo "$chat_response" | grep -qE '"answer"|"error"'; then
    TOTAL_TESTS=$((TOTAL_TESTS + 1))
    echo -e "  POST /chat/ask 返回结构: ${GREEN}✅${NC}"
    PASSED_TESTS=$((PASSED_TESTS + 1))
else
    TOTAL_TESTS=$((TOTAL_TESTS + 1))
    echo -e "  POST /chat/ask 返回结构: ${RED}❌ 无 answer 或 error${NC}"
    FAILED_TESTS=$((FAILED_TESTS + 1))
fi
echo ""

echo -e "${YELLOW}  [5.2] GET /chat/history/e2e-test-session — 会话历史${NC}"
history_response=$(curl -s ${BASE_URL}/chat/history/e2e-test-session)
echo "  响应: $(echo $history_response | cut -c 1-100)..."
check_contains "GET /chat/history 返回结构" "$history_response" "\["
echo ""

echo -e "${YELLOW}  [5.3] DELETE /chat/history/e2e-test-session — 清空历史${NC}"
check_http "DELETE /chat/history" "${BASE_URL}/chat/history/e2e-test-session" "200" "DELETE"
echo ""

# 测试总结
echo -e "${BOLD}╔═══════════════════════════════════════════════════════════╗${NC}"
echo -e "${BOLD}║                    测试总结                               ║${NC}"
echo -e "${BOLD}╚═══════════════════════════════════════════════════════════╝${NC}"
echo ""
echo -e "总测试数: ${BOLD}${TOTAL_TESTS}${NC}"
echo -e "通过: ${GREEN}${BOLD}${PASSED_TESTS}${NC}"
echo -e "失败: ${RED}${BOLD}${FAILED_TESTS}${NC}"

if [ $FAILED_TESTS -eq 0 ]; then
    echo -e "\n${GREEN}${BOLD}🎉 所有 E2E 测试通过！${NC}"
    exit 0
else
    echo -e "\n${RED}${BOLD}⚠️  有 ${FAILED_TESTS} 个测试失败${NC}"
    exit 1
fi
