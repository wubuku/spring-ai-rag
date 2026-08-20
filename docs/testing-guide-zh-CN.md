# 测试指南

> 📖 [English](testing-guide.md) · 📖 [中文](testing-guide-zh-CN.md)

Spring AI RAG 项目对测试的态度是"测试是生产代码"——写代码必须同步写测试，`mvn test` 不通过就不算完成。

> **永久规则**（来自项目要求，不可弱化）：  
> - 写生产代码必须同步写测试，两者同等重要  
> - `mvn test` 全部通过才算「完成」  
> - 有 REST 端点改动后运行 E2E（`scripts/e2e-test.sh`）  
> - 涉及 WebUI 修改时必须跑 Playwright（`scripts/webui-e2e-test.js` / `npm run test:e2e`）  
> - 重要改进后：重启服务 → 确认 `http://localhost:8081` 可用 → 再跑回归  

总文档导航：[index-zh-CN.md](index-zh-CN.md) · 命令速查：[developer-reference-zh-CN.md](developer-reference-zh-CN.md) · 规划到交付：[delivery-workflow-zh-CN.md](delivery-workflow-zh-CN.md)

## 测试金字塔

```
    ┌──────────┐
    │  E2E 测试  │  scripts/e2e-test.sh
    ├──────────┤
    │ 集成测试    │  @SpringBootTest
    ├──────────┤
    │ 单元测试    │  JUnit 5 + Mockito
    └──────────┘
```

## 快速开始

```bash
# 运行全部单元测试 + 集成测试
export $(cat .env | grep -v '^#' | xargs) && mvn test

# 只测试特定模块
mvn test -pl spring-ai-rag-core

# 只运行某个测试类
mvn test -pl spring-ai-rag-core -Dtest=RagDocumentControllerTest

# 跳过测试构建
mvn clean package -DskipTests
```

## 一键文档验证

`scripts/verify-project-docs.sh` 固化项目文档检查清单：OpenClaw 本地状态隔离、`.agents/skills/` 可跟踪性、相对链接、中英文标题结构、入口行数、项目固定约定、文档命令、Shell 语法、空白和新增行敏感信息扫描。

```bash
./scripts/verify-project-docs.sh
```

默认发布验证也会执行该门禁。

## 一键发布验证

`scripts/verify-release.sh` 固化 1.0 发布门禁，包括内嵌 WebUI 入口引用、资源存在性与 Git 可跟踪性检查，并将每一步的 stdout/stderr、状态表和 Markdown 汇总写入 `target/release-verification/<run-id>/`：

```bash
# 默认：静态检查、Maven、WebUI、Playwright、Helm、Docker
./scripts/verify-release.sh

# 已有 node_modules 时可省略 npm ci
./scripts/verify-release.sh --skip-npm-ci

# 对已启动的 PostgreSQL profile 服务追加 HTTP E2E 与 goldenset
BASE_URL=http://127.0.0.1:8081 \
  ./scripts/verify-release.sh --with-runtime-e2e --with-goldenset

# 对已启动服务追加版本化真实检索回归
BASE_URL=http://127.0.0.1:18081 \
  ./scripts/verify-release.sh --with-quality-regression

# 真实 LLM 服务通常由 scripts/start-real-e2e-server.sh 启动在 18081
./scripts/verify-release.sh --with-real-llm

# 完整本地门禁：自动启动 postgresql profile 服务，执行 HTTP E2E、
# goldenset、质量回归与真实 LLM smoke，归档日志后停止该服务
./scripts/verify-release.sh --with-local-runtime
```

`--with-local-runtime` 需要 PostgreSQL/pgvector 已运行，且 `.env` 中存在可用的数据库、Embedding 与 Chat LLM 配置；默认独占端口 `18081`，端口被占用时会失败，避免复用或误杀非本脚本启动的服务。可用 `RUNTIME_SERVER_PORT` 改端口。无论成功、失败或中断，脚本都会归档日志并清理自己启动的服务。

Docker 默认先用中国境内镜像并自动回退官方源；详见 [中国境内开发网络避坑指南](china-network-guide-zh-CN.md)。外部服务失败必须保留为失败或明确跳过，不能伪造发布通过。

## 测试分类

### 单元测试（JUnit 5 + Mockito）

**目标**：验证单个类/方法的逻辑，不依赖外部服务。

**命名规范**：`{ClassName}Test.java`

**位置**：各模块的 `src/test/java/`

**示例**：
```java
@SpringBootTest
@AutoConfigureMockMvc
class RagDocumentControllerTest {

    @MockBean
    private RagDocumentService ragDocumentService;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void shouldReturnDocumentById() throws Exception {
        when(ragDocumentService.findById(1L)).thenReturn(Optional.of(doc));

        mockMvc.perform(get("/api/v1/rag/documents/1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.title").value("Test"));
    }
}
```

**Mock 要点**：
- 使用 `@MockBean` 替代 `@Mock`（Spring 上下文集成）
- Service 层可以 `@Mock` + `@ExtendWith(MockitoExtension.class)` 纯单元测试
- 涉及数据库的用 `@DataJpaTest` 切片测试

### 集成测试（@SpringBootTest）

**目标**：验证组件协作，用真实 Spring 上下文。

**命名规范**：`{ClassName}IntegrationTest.java`

**关键注解**：
```java
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers   // 如果需要 PostgreSQL
class RagChatControllerIntegrationTest {
    // 测试完整的 RAG Pipeline：查询 → 改写 → 检索 → 重排 → LLM
}
```

### E2E 测试（Shell + curl）

**目标**：验证 HTTP 端点完整链路（真实服务运行）。

**脚本**：`scripts/e2e-test.sh`

**用法**：
```bash
# 启动服务
export $(cat .env | grep -v '^#' | xargs) && bash scripts/start-server.sh

# 在另一个终端运行 E2E 测试
export $(cat .env | grep -v '^#' | xargs) && bash scripts/e2e-test.sh
```

E2E 测试覆盖的端点：
1. `GET /api/v1/rag/health` — 健康检查
2. `POST /api/v1/rag/collections` 及 by-key 获取/更新/列表/删除 — Collection Key 生命周期
3. `POST /api/v1/rag/documents` — 创建文档
4. `GET /api/v1/rag/documents/{id}` — 获取文档（含文档 metadata）
5. `GET /api/v1/rag/documents` — 文档列表（分页）
6. `POST /api/v1/rag/documents/{id}/embed` — 生成嵌入向量
7. `GET /api/v1/rag/search` — 直接检索
8. `POST /api/v1/rag/chat/ask` — RAG 问答
9. `POST /api/v1/rag/chat/stream` — 流式响应（SSE）
10. `GET /api/v1/rag/chat/history/{sessionId}` — 对话历史
11. `DELETE /api/v1/rag/documents/{id}` — 删除文档 + 验证 404

脚本每次运行生成唯一的可见 ASCII `collectionKey`，并在 Collection 路由、文档写入及检索
请求中优先使用 by-key 和 `collectionKey(s)`，避免重复执行与全局唯一约束冲突。

JSONB 结构化记录的真实 HTTP 验收使用：

```bash
BASE_URL=http://127.0.0.1:18081 \
RAG_API_KEY="$RAG_ROOT_API_KEY" \
./scripts/jsonb-records-e2e.sh
```

它覆盖 JSON record upsert、限定 Collection 的检索、详情、payload-only 更新、
`retrievalText` 更新、clone/export/import 和临时受限 API Key 的 allow/deny。
脚本需要真实 embedding provider；没有 root 凭据时只能显式使用 `--skip-acl`，
并在验证记录中注明。该脚本不调用 Chat LLM，也不会输出 API Key 或完整 payload。

### 外部文档同步验收门禁

普通外部文档路径具备 Service、MockMvc、OpenAPI、迁移和真实 HTTP 覆盖。对已经启动的
PostgreSQL profile 服务运行：

```bash
BASE_URL=http://127.0.0.1:18081 \
RAG_API_KEY="$RAG_ROOT_API_KEY" \
./scripts/external-documents-e2e.sh
```

它覆盖稳定身份、精确重放、来源版本 CAS、同 revision 冲突、批量隔离、按外部身份查询、
tombstone 删除与重放、恢复和 embedding freshness。默认运行需要可用的 embedding
provider 来验证内容变化更新；只有在明确记录 provider 故障时才使用
`EXTERNAL_DOCUMENT_E2E_EMBED=false`。该模式可以验证持久化与 no-embedding 状态，但不计入
完整 embedding 验收门禁。

迁移矩阵默认使用 Testcontainers：

```bash
mvn -pl spring-ai-rag-core \
  -Dtest=ExternalDocumentSyncPostgresIntegrationTest \
  -Dexternal-document.it.enabled=true \
  test
```

Docker 不可用时，可通过 `EXTERNAL_DOCUMENT_IT_JDBC_URL`、
`EXTERNAL_DOCUMENT_IT_USERNAME` 和 `EXTERNAL_DOCUMENT_IT_PASSWORD` 指向专用的一次性
PostgreSQL 数据库，并显式设置 `EXTERNAL_DOCUMENT_IT_CLEAN_CONFIRM=YES`。该测试会反复
调用 `Flyway.clean()`，绝不能指向开发库或生产库。

<a id="document-lifecycle-verification"></a>

### 文档 CRUD 与派生索引生命周期门禁

```bash
./scripts/verify-document-lifecycle.sh
```

该脚本串行执行：

1. 禁悲观锁静态门禁；
2. 本地 CRUD、外部 TEXT/JSON、Collection/PDF/batch 入口和 generation job 聚焦测试；
3. 一次性 PostgreSQL 上的 V39→V45、三元身份、freshness、本地/向量 generation fencing、
   事务回滚
   和硬删除级联验收，并解析 Surefire XML 强制 `skipped=0`；
4. reference client 的 HTTP 重试、CAS、checkpoint 恢复和密钥不落盘测试；
5. `mvn clean compile test-compile` 与全量后端测试；
6. WebUI Vitest、production build、alignment 和 Documents Mock Playwright；
7. 双语项目文档与 `git diff --check`。

也可以单独运行 V42 Sync Run HTTP 合同验收：

```bash
./scripts/verify-document-sync-runs.sh
```

它会把一次性数据库迁移到 V45，并验证 Sync Run begin、批量幂等、失败重试、
preview/complete tombstone、namespace 隔离和禁悲观锁门禁。

### 本地关键词 / 向量解耦验收门禁

```bash
KEYWORD_VECTOR_VERIFY_RUN_ID=full-gate-4 \
KEYWORD_VECTOR_PLAYWRIGHT_PORT=4191 \
./scripts/verify-keyword-vector-decoupling.sh
```

该一键门禁会把隔离 PostgreSQL 从空库迁移到 V45，执行本地 chunk 生命周期以及
English/中文/pg_trgm 全文集成测试，并强制 `skipped=0`；随后执行
`mvn clean compile test-compile`，以及 WebUI TypeScript、Vitest、production build、
alignment 和无截图 Mock Playwright。它同时执行禁悲观锁静态门禁，证据写入
`.verification/keyword-vector-decoupling/<run-id>/`。

PostgreSQL 选择顺序为：显式 `DOCUMENT_LIFECYCLE_IT_JDBC_URL`、使用当前 shell/`.env`
的 `POSTGRES_*` 创建一次性数据库、最后使用 Docker CLI 启动 pgvector。调用方 JDBC URL
必须是可清空的一次性数据库。Mock Playwright 只使用 DOM、网络和断言，配置中关闭截图。
证据写入 `.verification/document-data-plane/<run-id>/summary.md`。

### 文档迁移与派生完整性专项门禁

```bash
./scripts/verify-document-relocation.sh
./scripts/verify-derivation-integrity.sh
```

迁移门禁覆盖 V44、双 Collection ACL、幂等精确重放、活动 Sync Run fencing、旧地址永久
阻断，以及 TEXT/JSON 共用的数据面语义。派生门禁覆盖 V45、严格物理 freshness、分页与
聚合诊断、preview token/fingerprint、短事务 repair item lease，以及只在确有需要时入队的
向量修复。两者都要求 PostgreSQL 测试 `skipped=0`，并执行后端编译、前端构建与基于
DOM/网络/接口断言的 Mock Playwright；截图不作为验收证据。

复用一次性数据库时使用 `NEXT_HIGH_VALUE_IT_JDBC_URL`、
`NEXT_HIGH_VALUE_IT_USERNAME`、`NEXT_HIGH_VALUE_IT_PASSWORD`，并设置
`NEXT_HIGH_VALUE_IT_CLEAN_CONFIRM=YES`。门禁会反复执行 `Flyway.clean()`，不得使用开发
库或生产库。

## 覆盖率

JaCoCo 已集成到所有模块：

```bash
# 生成覆盖率报告
mvn clean test jacoco:report

# 报告位置
# spring-ai-rag-core/target/site/jacoco/index.html
# spring-ai-rag-api/target/site/jacoco/index.html
# spring-ai-rag-documents/target/site/jacoco/index.html
# spring-ai-rag-starter/target/site/jacoco/index.html
```

**覆盖率目标**：
- 指令覆盖率 ≥ 90%
- 分支覆盖率 ≥ 75%

**查看覆盖率**：
```bash
# 快速查看（终端输出）
mvn jacoco:check

# 合并多模块报告
mvn jacoco:report-aggregate
```

## 测试数据库

单元测试使用 Mock 或 H2 兼容路径。Embedding Profile 迁移使用显式 PostgreSQL 集成测试，
因为它需要 pgvector，并验证 Flyway V1-V45、固定向量列、Profile 专属索引、原子替换、
Legacy 认领、检索新鲜度和 Spring Data Repository 查询。

启动 PostgreSQL 16 + pgvector 数据库后执行：

```bash
mvn -pl spring-ai-rag-core -am \
  -Dtest=EmbeddingProfilePostgresIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Drag.it.jdbc-url=jdbc:postgresql://127.0.0.1:35267/embedding_profile_test \
  -Drag.it.username=postgres \
  -Drag.it.password=postgres \
  test
```

未设置 `rag.it.jdbc-url` 时该测试会跳过，因此上述显式命令是本次迁移的必要验收门槛。

### Collection Key 验收门禁

Collection 身份具备 DTO、Resolver、ACL、Service、Controller、MockMvc、OpenAPI 和
PostgreSQL 专项覆盖。真实 PostgreSQL/Testcontainers 测试执行 V27/V28，并验证 legacy
回填候选冲突避让、1/128 字符边界、可见 ASCII 约束、大小写敏感、软删除保留 key、
SQL 层不可变和并发唯一性：

```bash
TESTCONTAINERS_RYUK_DISABLED=true \
mvn -pl spring-ai-rag-core -am \
  -Dapi.version=1.40 \
  -Dcollection-key.it.enabled=true \
  -Dtest=CollectionKeyPostgresIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  test
```

运行时验收前，串行执行编译和聚焦集成门禁：

```bash
mvn clean compile test-compile

mvn -pl spring-ai-rag-core -am \
  -Dtest='*Collection*,*ApiKey*,OpenApiContractTest,RagControllerIntegrationTest,RagSearchControllerTest,RagChatControllerTest,PdfImportControllerTest' \
  -Dsurefire.failIfNoSpecifiedTests=false \
  test
```

WebUI 验收要求 `npm run test:run`、`npm run build` 和 Mock API Playwright 全部通过。随后
运行时 smoke 使用唯一 key 覆盖创建、by-key 获取/更新、使用新目标 key 克隆、使用新 key
导出/导入、软删除、恢复、重复冲突，以及文档/Search/Chat 的 key 输入。

### 多 Collection 检索范围验收门禁

范围实现具备 DTO、Resolver、ACL、SQL fragment、Vector/Full-text provider、
Chat/Search/JSON、MockMvc、OpenAPI、WebUI 和 PostgreSQL 覆盖。真实
PostgreSQL/Testcontainers 测试会启动 `pgvector/pgvector:pg16`，从空 schema 执行
Flyway V1-V45，并用实际 PostgreSQL `bigint[]` 绑定执行 Vector 查询：

```bash
TESTCONTAINERS_RYUK_DISABLED=true \
mvn -pl spring-ai-rag-core -am \
  -Dapi.version=1.40 \
  -Dmulti.collection.it.enabled=true \
  -Dtestcontainers.pg.image=pgvector/pgvector:pg16 \
  -Dtest=MultiCollectionRetrievalPostgresIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  test
```

该矩阵验证：不受限 `CALLER_VISIBLE` 包含未归属文档；`ANY_COLLECTION` 排除未归属文档；
selected A+B 不会返回其他 Collection；空 selected Collection 保持空结果；selected
Collection 与显式 document ID 在 SQL 中取交集；JSON record 遵守 `document_type`；
disabled、stale 或错误 Profile 的文档仍被排除。English FTS、pg_jieba 与 pg_trgm
是否使用相同 `RetrievalScopeSql` predicate，由各 provider 的聚焦测试单独验证。

WebUI 验收覆盖三种模式、多选、服务端 Collection 搜索和分页、selected 空范围禁止提交，
以及 Chat SSE object request。执行 `npm run test:run`、`npx tsc -b --pretty false`、
`npm run build` 和核心 Mock Playwright。

### JSONB 结构化记录验收门禁

JSONB 实现同时具备 Mock HTTP/Service 覆盖和真实 PostgreSQL/Testcontainers 测试。后者会
启动 `pgvector/pgvector:pg16`，从空库执行 Flyway V1-V45，并验证 JSONB round-trip、
嵌套 `payloadContains`、V34 GIN planner、仅更新 payload 的版本记录、相同描述下不同
记录共存以及级联清理：

```bash
TESTCONTAINERS_RYUK_DISABLED=true \
mvn -pl spring-ai-rag-core -am \
  -Dapi.version=1.40 \
  -Djsonb.it.enabled=true \
  -Dtest=JsonbStructuredRecordsPostgresIntegrationTest \
  test
```

聚焦的全层一键门禁：

```bash
./scripts/verify-jsonb-records.sh
```

该脚本还会运行 API DTO、分块器、JSON service/controller/OpenAPI、Maven 编译、WebUI 构建、
Mock Playwright、project-docs 和空白检查，并将结果记录到
`.verification/jsonb-verification/<run-id>/summary.md`。
浏览器 preview 对 `JSONB_PLAYWRIGHT_PORT`（默认 `4174`）使用严格端口绑定；若本地已有
服务占用该端口，请指定空闲端口。完整门禁必须串行运行，因为 Maven clean 会删除模块的
`target/` 输出。

### OpenAI 兼容预览验收门禁

```bash
./scripts/verify-openai-compatibility.sh
```

固定范围包括 model alias、body/Header Collection scope 合并、API Key ACL、完整
text-only messages、未知 alias 错误、非流式 JSON、SSE role/content/finish chunk 顺序和
最终 `[DONE]`。脚本还执行 focused tests、`test-compile`、Shell 语法和空白检查，证据写入
`.verification/openai-compatibility/<run-id>/`。

### 持久化 Embedding Jobs 验收门禁

```bash
./scripts/verify-embedding-jobs.sh
```

脚本串行执行 service/worker/Controller focused tests，自动启动隔离 PostgreSQL 并从空库
执行 V1–V45，验证 V33 active-job coalesce、force 原子升级和并发 worker 原子条件
claim，再执行 `test-compile`、Shell 语法和空白检查。已有隔离数据库时可用
`EMBEDDING_JOBS_IT_JDBC_URL` 覆盖。

### 下一轮高价值能力验收门禁

```bash
./scripts/verify-retrieval-diagnostics.sh
./scripts/verify-retrieval-filters.sh
./scripts/verify-embedding-operations.sh
./scripts/verify-managed-quality.sh
./scripts/verify-no-pessimistic-locks.sh
# 或一次串行跑完 A–D：
./scripts/verify-next-high-value-features.sh
```

固定范围分别覆盖检索诊断（V35）、metadata/payload 过滤（V36）、embedding 运营
（V37，含 SYNC/ASYNC/SKIP、ACL 分页与就绪接口）、citation / 受管套件（V38），以及
V39 后禁止显式悲观锁的静态门禁。完整聚合脚本必须串行运行，因为 Maven clean 会删除
模块 `target/`。

### 真实检索质量回归门禁

```bash
BASE_URL=http://127.0.0.1:18081 \
  ./scripts/verify-quality-regression.sh
```

门禁先校验 `testdata/regression/retrieval-core-v1.json` 与提交的 baseline 契约，再调用
真实 embedding/search API。当前 fixture 使用稳定
`collectionKey + sourceNamespace(default) + externalId` 判断 relevant
identity，检查 Hit Rate、MRR、Recall@K、nDCG、每 case minimum、相对 baseline 回退、
Collection decoy 泄漏和 JSONB 明确空结果。外部 provider、数据库或 embedding 失败必须
返回非零，不能伪装为质量通过。

### Chat 对话能力重构验收门禁

Chat 实现被明确验证为三种模式：

- `KNOWLEDGE`：Spring AI Modular RAG，内部使用项目自己的混合检索和 rerank。
- `AGENT`：Spring AI Tool Calling，检索范围由服务端控制。
- `PLAIN`：普通 ChatClient 加 Memory，不执行知识检索。

运行可重复的本地门禁：

```bash
./scripts/verify-chat-capability.sh
```

该脚本会执行 Chat 执行链、Tool Calling、Memory/历史、结构化 SSE、
Controller/集成和导出测试；V32 PostgreSQL lease/原子性测试；`mvn clean
compile test-compile`；完整 `mvn test`；安装当前 reactor 产物并测试独立的
`demos/demo-domain-extension` 消费者；使用临时 PostgreSQL 和 dummy 模型端点执行
Spring Boot 启动/健康烟测；WebUI Vitest、TypeScript、生产构建；Chat 核心 Mock
Playwright；project-docs 和空白检查。日志及 Markdown 结果写入
`.verification/chat-capability/<run-id>/summary.md`。

独立 demo 不属于根 reactor。直接在 demo 目录运行 Maven 可能链接本地仓库里的旧
`spring-ai-rag-starter:1.0.0`；一键脚本会先安装当前工作区产物，再运行 demo 测试，
以验证新增 API 对外部消费者确实可编译。

浏览器门禁只使用 DOM 可见性、请求/响应断言、URL 断言和测试断言，不使用截图作为正确性
证据。浏览器套件覆盖模式/模型请求、AGENT 工具生命周期、来源、历史来源恢复、选定
Collection 和移动端横向溢出。

PostgreSQL 门禁默认执行。如果 Docker 不可用，或 daemon 拒绝当前协商的 API 版本，脚本会
把 Docker 和 PostgreSQL 门禁都记录为 `SKIP`；`PASS_WITH_SKIPS` 不等于完整发布门禁。本项目
曾遇到 Testcontainers 协商 Docker API `1.32`，而本机 daemon 要求最低 `1.40`。可使用：

```bash
TESTCONTAINERS_API_VERSION=1.40 \
TESTCONTAINERS_RYUK_DISABLED=true \
./scripts/verify-chat-capability.sh
```

`ChatSessionPostgresIntegrationTest` 也支持一次性外部数据库，便于绕过 Testcontainers
Docker API 协商问题；该测试会执行 `Flyway.clean()`，不得指向开发库或生产库：

```bash
mvn -pl spring-ai-rag-core \
  -Dtest=ChatSessionPostgresIntegrationTest \
  -Dchat.it.enabled=true \
  -Dchat.it.jdbc-url=jdbc:postgresql://127.0.0.1:5432/disposable_chat_test \
  -Dchat.it.username=postgres \
  -Dchat.it.password=postgres \
  -Dchat.it.clean-confirm=YES \
  test
```

只有在环境确实不可用时才使用 `--skip-postgres`，并保留生成的 summary。境内 registry/证书、
Docker API 和 Ryuk 的排障经验见 [china-network-guide-zh-CN.md](china-network-guide-zh-CN.md)
和 [troubleshooting-zh-CN.md](troubleshooting-zh-CN.md)。

后端启动烟测默认使用严格绑定的 `CHAT_STARTUP_PORT=4210`，可在端口冲突时覆盖。只有
明确不具备 Docker 环境时才使用 `--skip-startup`，并保留该 `SKIP` 记录。

真实 Provider 验证必须显式开启：

```bash
RAG_API_KEY="$RAG_ROOT_API_KEY" \
./scripts/verify-chat-capability.sh --with-real-llm
```

脚本会从 `.env` 读取真实模型配置；如果服务启用了 `RAG_ROOT_API_KEY`，会使用
`X-API-Key` 访问数据面。未传该选项时，真实 LLM 门禁会明确记录为 `SKIP`；本地测试
和 Mock Playwright 不代表真实模型或真实 Tool Calling endpoint 已验证。

## 编写新测试的规则

1. **先写测试再写实现**（TDD 友好）
2. **每个 public 方法至少一个正向测试 + 一个边界测试**
3. **Controller 测试**用 `MockMvc`，不启动真实 HTTP
4. **Service 测试**用 `@Mock` 纯单元测试或 `@SpringBootTest` 集成测试
5. **不要用 `@Ignore` 跳过测试**——修好它或删掉它
6. **测试名称用 `should_描述预期行为` 格式**
7. **每个测试独立**——不依赖执行顺序

## 性能基准测试

`RetrievalBenchmarkTest` 验证核心操作在合理时间内完成：

| 操作 | 目标 | 实测 |
|------|------|------|
| 向量检索 | < 500ms | ~1.9ms |
| 融合检索 | < 500ms | ~6ms |
| Cosine 计算 (10万次) | < 200ms | ~75ms |

## 常见问题

### `mvn test` 报错 "Connection refused"

PostgreSQL 未启动或 `.env` 未加载：

```bash
# 确认 PostgreSQL 运行
pg_isready -h localhost -p 5432

# 加载环境变量
export $(cat .env | grep -v '^#' | xargs)
```

### 嵌入模型测试太慢

嵌入模型调用（SiliconFlow API）需要网络，在 CI 中可以 Mock：

```java
@MockBean
private EmbeddingModel embeddingModel;

@BeforeEach
void setup() {
    when(embeddingModel.embed(any(String.class)))
        .thenReturn(new float[1024]); // 返回固定向量
}
```

### JaCoCo 覆盖率不准确

```bash
# 清理后重新测试
mvn clean test jacoco:report
```
