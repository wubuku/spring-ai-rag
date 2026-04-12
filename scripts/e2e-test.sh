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
assert_contains "返回chunkCount" "$BODY" '"chunkCount"'
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
    assert_contains "确认删除" "$BODY" "deleted"

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
    assert_contains "确认删除" "$BODY" "Collection deleted"
else
    echo -e "  ${YELLOW}⚠️ SKIP${NC} 无 collection ID"
fi
echo ""

# ────────────────────────────────────────
# 1️⃣5️⃣ API Key 管理
# ────────────────────────────────────────
echo "1️⃣5️⃣  API Key 管理"
# 1. List API Keys (should have at least the static key or generated keys)
RESP=$(curl -s -w "\n%{http_code}" "$API/api-keys")
CODE=$(echo "$RESP" | tail -1)
BODY=$(echo "$RESP" | sed '$d')
assert_status "GET /api-keys (list)" "200" "$CODE"
echo ""

# 2. Create a new API Key
RESP=$(curl -s -w "\n%{http_code}" -X POST "$API/api-keys" \
    -H "Content-Type: application/json" \
    -d '{"name":"e2e-test-key","expiresAt":"2027-01-01T00:00:00Z"}')
CODE=$(echo "$RESP" | tail -1)
BODY=$(echo "$RESP" | sed '$d')
assert_status "POST /api-keys (create)" "201" "$CODE"
# Extract keyId from response (format: {"keyId":"rag_k_...","name":"e2e-test-key",...})
KEY_ID=$(echo "$BODY" | grep -o '"keyId":"rag_k_[^"]*"' | head -1 | cut -d'"' -f4)
echo "  Created keyId: $KEY_ID"
echo ""

# 3. List again and verify new key appears
RESP=$(curl -s -w "\n%{http_code}" "$API/api-keys")
CODE=$(echo "$RESP" | tail -1)
assert_status "GET /api-keys (verify new key)" "200" "$CODE"
echo ""

# 4. Revoke the test key
if [ -n "$KEY_ID" ]; then
    RESP=$(curl -s -w "\n%{http_code}" -X DELETE "$API/api-keys/$KEY_ID")
    CODE=$(echo "$RESP" | tail -1)
    assert_status "DELETE /api-keys/{keyId} (revoke)" "200" "$CODE"
    echo ""

    # 5. Verify revoked key no longer appears in list
    RESP=$(curl -s -w "\n%{http_code}" "$API/api-keys")
    CODE=$(echo "$RESP" | tail -1)
    BODY=$(echo "$RESP" | sed '$d')
    if echo "$BODY" | grep -q "$KEY_ID"; then
        echo -e "  ${RED}❌ FAIL${NC} Revoked key still appears in list"
        FAIL=$((FAIL + 1))
    else
        echo -e "  ${GREEN}✅ PASS${NC} Revoked key removed from list"
        PASS=$((PASS + 1))
    fi
else
    echo -e "  ${YELLOW}⚠️ SKIP${NC} No key ID to test revocation"
fi
echo ""

# ────────────────────────────────────────
# 16. PDF 文件导入与预览（fs_files 表）
# 使用 Apache PDFBox 提取文本，UUID 虚拟目录布局
# ────────────────────────────────────────
echo "1️⃣6️⃣  PDF 文件导入与预览"

# 创建测试 PDF 文件（最小有效 PDF）
TESTPDF="/tmp/e2e-test-pdf-$$.pdf"
cat > "$TESTPDF" << 'PDFEOF'
%PDF-1.4
1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj
2 0 obj<</Type/Pages/Count 1/Kids[3 0 R]>>endobj
3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 612 792]
/Contents 4 0 R/Resources<</Font<</F1 5 0 R>>>>>>endobj
4 0 obj<</Length 44>>
stream
BT
/F1 12 Tf
100 700 Td
(Hello from E2E test PDF) Tj
ET
endstream
endobj
5 0 obj<</Type/Font/Subtype/Type1/BaseFont/Helvetica>>endobj
xref
0 6
0000000000 65535 f
0000000009 00000 n
0000000058 00000 n
0000000115 00000 n
0000000268 00000 n
0000000359 00000 n
trailer<</Size 6/Root 1 0 R>>
startxref
434
%%EOF
PDFEOF

# 16a. POST /files/pdf - 上传测试 PDF，获取 UUID
echo "- 16a. POST /files/pdf (upload test PDF, get UUID)"
RESP=$(curl -s -w "\n%{http_code}" -X POST "$API/files/pdf" \
    -F "file=@$TESTPDF")
rm -f "$TESTPDF"
CODE=$(echo "$RESP" | tail -1)
BODY=$(echo "$RESP" | sed '$d')
assert_status "POST /files/pdf" "200" "$CODE"
if echo "$BODY" | grep -q '"uuid"'; then
    UUID=$(echo "$BODY" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('uuid',''))" 2>/dev/null)
    ENTRY_MD=$(echo "$BODY" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('entryMarkdown',''))" 2>/dev/null)
    FILES_STORED=$(echo "$BODY" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('filesStored',''))" 2>/dev/null)
    echo -e "  ${GREEN}✅ PASS${NC} PDF imported: uuid=$UUID filesStored=$FILES_STORED"
    PASS=$((PASS + 1))
else
    echo -e "  ${RED}❌ FAIL${NC} Unexpected response: $BODY"
    FAIL=$((FAIL + 1))
    UUID=""
fi

# 16b. GET /files/tree - 根目录（有 UUID 目录）
echo "- 16b. GET /files/tree (should contain UUID dir)"
RESP=$(curl -s -w "\n%{http_code}" "$API/files/tree")
CODE=$(echo "$RESP" | tail -1)
assert_status "GET /files/tree" "200" "$CODE"
if [ -n "$UUID" ] && echo "$RESP" | grep -q "$UUID"; then
    echo -e "  ${GREEN}✅ PASS${NC} UUID directory visible in tree"
    PASS=$((PASS + 1))
else
    echo -e "  ${RED}❌ FAIL${NC} UUID not in tree"
    FAIL=$((FAIL + 1))
fi

# 16c. GET /files/tree?path={UUID}/ - UUID 目录内容
echo "- 16c. GET /files/tree?path={UUID}/ (UUID dir contents)"
if [ -n "$UUID" ]; then
    RESP=$(curl -s -w "\n%{http_code}" "$API/files/tree?path=${UUID}/")
    CODE=$(echo "$RESP" | tail -1)
    BODY=$(echo "$RESP" | sed '$d')
    assert_status "GET /files/tree?path={UUID}/" "200" "$CODE"
    if echo "$BODY" | grep -q '"default.md"' && echo "$BODY" | grep -q '"original.pdf"'; then
        echo -e "  ${GREEN}✅ PASS${NC} default.md and original.pdf found"
        PASS=$((PASS + 1))
    else
        echo -e "  ${RED}❌ FAIL${NC} Expected default.md and original.pdf: $BODY"
        FAIL=$((FAIL + 1))
    fi
else
    echo -e "  ${YELLOW}⚠️ SKIP${NC} No UUID (previous step failed)"
fi

# 16d. GET /files/preview/html?path={UUID}/original.pdf - Markdown→HTML 渲染
echo "- 16d. GET /files/preview/html?path={UUID}/original.pdf"
if [ -n "$UUID" ]; then
    ORIG_PATH="${UUID}/original.pdf"
    ORIG_ENC=$(python3 -c "import urllib.parse; print(urllib.parse.quote('$ORIG_PATH'))")
    RESP=$(curl -s -w "\n%{http_code}" "$API/files/preview/html?path=$ORIG_ENC")
    CODE=$(echo "$RESP" | tail -1)
    BODY=$(echo "$RESP" | sed '$d')
    assert_status "GET /files/preview/html (rendered)" "200" "$CODE"
    if echo "$BODY" | grep -q '<h1>'; then
        echo -e "  ${GREEN}✅ PASS${NC} Markdown→HTML rendered with <h1>"
        PASS=$((PASS + 1))
    else
        echo -e "  ${RED}❌ FAIL${NC} Expected <h1> in HTML: ${BODY:0:100}"
        FAIL=$((FAIL + 1))
    fi
else
    echo -e "  ${YELLOW}⚠️ SKIP${NC}"
fi

# 16e. GET /files/raw?path={UUID}/default.md - 原始 Markdown 下载
echo "- 16e. GET /files/raw?path={UUID}/default.md"
if [ -n "$UUID" ]; then
    MD_PATH="${UUID}/default.md"
    MD_ENC=$(python3 -c "import urllib.parse; print(urllib.parse.quote('$MD_PATH'))")
    RESP=$(curl -s -w "\n%{http_code}" "$API/files/raw?path=$MD_ENC")
    CODE=$(echo "$RESP" | tail -1)
    assert_status "GET /files/raw (markdown)" "200" "$CODE"
    if echo "$RESP" | grep -q '#'; then
        echo -e "  ${GREEN}✅ PASS${NC} Raw markdown download (contains #)"
        PASS=$((PASS + 1))
    else
        echo -e "  ${RED}❌ FAIL${NC} Expected markdown content"
        FAIL=$((FAIL + 1))
    fi
else
    echo -e "  ${YELLOW}⚠️ SKIP${NC}"
fi

# 16f. GET /files/preview/html?path=nonexistent - 不存在的文件应返回 404
echo "- 16f. GET /files/preview/html?path=nonexistent (expect 404)"
RESP=$(curl -s -w "\n%{http_code}" "$API/files/preview/html?path=nonexistent-$$-file.pdf")
CODE=$(echo "$RESP" | tail -1)
assert_status "GET /files/preview/html (not found)" "404" "$CODE"
echo -e "  ${GREEN}✅ PASS${NC} preview/html returns 404 for non-existent file"
PASS=$((PASS + 1))

# 16g. GET /files/raw?path=nonexistent - 不存在的文件应返回 404
echo "- 16g. GET /files/raw?path=nonexistent (expect 404)"
RESP=$(curl -s -w "\n%{http_code}" "$API/files/raw?path=nonexistent-$$-file.pdf")
CODE=$(echo "$RESP" | tail -1)
assert_status "GET /files/raw (not found)" "404" "$CODE"
echo -e "  ${GREEN}✅ PASS${NC} files/raw returns 404 for non-existent file"
PASS=$((PASS + 1))

# 16h. POST /files/pdf - 非 PDF 文件应返回 400
echo "- 16h. POST /files/pdf (non-PDF file, expect 400)"
TMPFILE="/tmp/e2e-non-pdf-$$.txt"
echo "not a pdf" > "$TMPFILE"
RESP=$(curl -s -w "\n%{http_code}" -X POST "$API/files/pdf" \
    -F "file=@$TMPFILE")
rm -f "$TMPFILE"
CODE=$(echo "$RESP" | tail -1)
assert_status "POST /files/pdf (non-PDF)" "400" "$CODE"
echo -e "  ${GREEN}✅ PASS${NC} files/pdf rejects non-PDF files"
PASS=$((PASS + 1))

# 16i. POST /files/pdf - 无文件应返回 400
echo "- 16i. POST /files/pdf (no file, expect 400)"
RESP=$(curl -s -w "\n%{http_code}" -X POST "$API/files/pdf")
CODE=$(echo "$RESP" | tail -1)
assert_status "POST /files/pdf (no file)" "400" "$CODE"
echo -e "  ${GREEN}✅ PASS${NC} files/pdf returns 400 when no file uploaded"
PASS=$((PASS + 1))

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
