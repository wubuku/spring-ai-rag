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
#   ⏳ API 端点测试（待 Phase 3 实现 Controller 后启用）

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

run_psql() {
    PGPASSWORD=$DB_PASSWORD psql -h localhost -U $DB_USER -d $DB_NAME -t -c "$1" 2>/dev/null
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

# =====================================================
# 第一部分：数据库 Schema 验证
# =====================================================
echo -e "${BOLD}━━━ 数据库 Schema 验证 ━━━${NC}"
echo ""

# 表存在性
for table in rag_collection rag_documents rag_embeddings rag_chat_history spring_ai_chat_memory flyway_schema_history; do
    count=$(run_psql "SELECT COUNT(*) FROM information_schema.tables WHERE table_name='$table';" | tr -d ' ')
    check "表 $table 存在" "$count" "1"
done

# 关键字段类型
metadata_type=$(run_psql "SELECT data_type FROM information_schema.columns WHERE table_name='rag_documents' AND column_name='metadata';" | tr -d ' ')
check "rag_documents.metadata 类型" "$metadata_type" "jsonb"

embedding_type=$(run_psql "SELECT data_type FROM information_schema.columns WHERE table_name='rag_embeddings' AND column_name='embedding';" | tr -d ' ')
check "rag_embeddings.embedding 类型" "$embedding_type" "vector"

# 扩展
for ext in vector pg_jieba; do
    count=$(run_psql "SELECT COUNT(*) FROM pg_extension WHERE extname='$ext';" | tr -d ' ')
    check "扩展 $ext 已安装" "$count" "1"
done

# 索引
index_count=$(run_psql "SELECT COUNT(*) FROM pg_indexes WHERE tablename='rag_embeddings' AND indexname='idx_rag_emb_vector_hnsw';" | tr -d ' ')
check "HNSW 向量索引存在" "$index_count" "1"

echo ""

# =====================================================
# 第二部分：服务连接验证
# =====================================================
echo -e "${BOLD}━━━ 服务连接验证 ━━━${NC}"
echo ""

# 服务可达性
TOTAL_TESTS=$((TOTAL_TESTS + 1))
echo -n "  服务端口 8081 可达: "
if curl -s -o /dev/null -w "%{http_code}" http://localhost:8081/ 2>/dev/null | grep -qE "^[45]"; then
    echo -e "${GREEN}✅ (返回 HTTP 状态码)${NC}"
    PASSED_TESTS=$((PASSED_TESTS + 1))
else
    echo -e "${RED}❌ 服务不可达${NC}"
    FAILED_TESTS=$((FAILED_TESTS + 1))
fi

echo ""

# =====================================================
# 第三部分：API 端点测试（待 Controller 实现后启用）
# =====================================================
echo -e "${BOLD}━━━ API 端点测试（待 Phase 3 实现后启用）━━━${NC}"
echo ""

# TODO: POST /api/v1/rag/chat/ask
# TODO: POST /api/v1/rag/chat/stream
# TODO: POST /api/v1/rag/documents
# TODO: GET /api/v1/rag/search
# TODO: GET /api/v1/rag/chat/history/{sessionId}

echo -e "${YELLOW}  ⏳ API 端点待 Phase 3 实现后补充测试${NC}"
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
    echo -e "\n${GREEN}${BOLD}🎉 所有测试通过！${NC}"
    exit 0
else
    echo -e "\n${RED}${BOLD}⚠️  有 ${FAILED_TESTS} 个测试失败${NC}"
    exit 1
fi
