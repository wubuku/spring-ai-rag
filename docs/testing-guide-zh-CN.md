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

# 无需启动服务，检查回归 runner 对当前及兼容 embedding 成功态的判定
./scripts/run-retrieval-regression.sh --self-test

# 真实 LLM 服务通常由 scripts/start-real-e2e-server.sh 启动在 18081
./scripts/verify-release.sh --with-real-llm

# 完整本地门禁：自动启动 postgresql profile 服务，执行 HTTP E2E、
# goldenset、质量回归与真实 LLM smoke，归档日志后停止该服务
./scripts/verify-release.sh --with-local-runtime
```

`--with-local-runtime` 需要 PostgreSQL/pgvector 已运行，且 `.env` 中存在可用的数据库、Embedding 与 Chat LLM 配置；默认独占端口 `18081`，端口被占用时会失败，避免复用或误杀非本脚本启动的服务。可用 `RUNTIME_SERVER_PORT` 改端口。无论成功、失败或中断，脚本都会归档日志并清理自己启动的服务。

Docker 默认先用中国境内镜像并自动回退官方源；详见 [中国境内开发网络避坑指南](china-network-guide-zh-CN.md)。外部服务失败必须保留为失败或明确跳过，不能伪造发布通过。

用户允许真实 LLM 验收时，先完成相关 Mock 单元/集成测试和 Mock Playwright，再运行真实
smoke；不要用高延迟真实调用代替基本执行路径覆盖。运行期间另开日志观察窗口或周期性读取服务
日志，尽早识别认证、模型名、限流、超时和响应协议错误。非 `main` worktree 使用与其他开发栈
隔离的 `BACKEND_PORT`、`FRONTEND_PORT` 和可处置测试数据库；联合启动优先使用会加载 `.env`
的 `scripts/dev.sh`。

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

### 业务服务接入就绪验收门禁

```bash
./scripts/verify-business-client-readiness.sh
```

该门禁把业务 credential provisioning、当前 principal 自省和 JSON Record 数据面串成一条
真实合同，覆盖：

- root、restricted/unrestricted 数据库 principal，只读 query 与读写 dispatcher 画像，
  Header/Bearer 认证和有效 query credential 拒绝；
- `/auth/me` 的 role、精确 capability、access mode、完整 key allow-list、no-store 与
  无 secret；
- Collection key 1/128 成功、129/空白/控制字符/非 ASCII 拒绝，外部身份与 revision
  长度边界、双向跨 Collection ACL 和全数据面防枚举；
- JSON Record 精确重放、CAS、payload containment、tombstone/恢复和 payload-only
  不重嵌入；
- `ASYNC` 持久化 job 经真实 Spring AI embedding HTTP 路径收敛为 fresh；
- 确定性 provider `503` 使 job 收敛为 `FAILED`，但保留已提交的 Record identity、
  revision、payload、enabled 状态和 document revision；
- credential 一次性展示、轮换、旧 key 失效和吊销；
- 只读 query principal 可 lookup/search，但 upsert/delete 返回 `403`，且拒绝后 Record
  revision/state 不变；读写 dispatcher 和 rotation 保持完整能力；
- 代表性的租户/共享拓扑把一个只读 query principal 精确绑定到两个 Collection，租户与共享
  dispatcher 相互隔离，第三租户被拒绝；两种 payload scope 分路检索后确定性归并，投影剔除
  private transport/credential 材料并重建客户端安全 DTO，query credential 轮换后仍保留
  两个 binding；
- 通用 Client 记录 mutation envelope 会先编译为稳定哈希 external ID、opaque revision、
  检索文本和 allow-list JSON payload，再执行 `TENANT_PRIVATE` 的 CAS 更新、tombstone、恢复、
  轮换后再次 tombstone，以及 `SHARED_CATALOG` 的发布和撤销；原始 `privateAttachment`、
  URL、内部 record/event/fingerprint 字段不得进入 RAG 投影；
- 确定性回归测试和真实 envelope 快速更新/删除路径共同覆盖异步 embedding 完成与外部
  mutation 的乐观锁竞态，确认服务以全新事务有界重试，并且不把内部派生竞争误报成普通业务
  CAS 冲突；
- Flyway V49、明文 credential 为零、成功 embedding job 的 PostgreSQL 只读事实；
- WebUI typecheck、Vitest、生产构建、核心 Mock Playwright 与真实 API Key Playwright。

真实 HTTP 阶段除业务客户端合同外，还会把已部署 binding preflight 作为黑盒 client
执行：`READ_ONLY` 与 `READ_WRITE` 画像成功、能力画像不匹配失败、allow-list 精确不匹配
失败、Bearer canary mutation 成功，以及 provider 失败后的清理和最终 tombstone。测试会
校验 preflight 报告 schema，并确认报告不包含 credential、URL、Collection key、
external ID 或 payload。

最终候选 commit 应开启 clean-tree gate：

```bash
BUSINESS_CLIENT_REQUIRE_CLEAN_GIT=true \
./scripts/verify-business-client-readiness.sh
```

若客户端仓库已经能导出真实 mutation envelope，可通过
`BUSINESS_CLIENT_CLIENT_ENVELOPE_DIR` 指定目录。目录必须包含
`private-lifecycle-v1.json` 至 `private-lifecycle-v5.json`，以及
`shared-lifecycle-v1.json`、`shared-lifecycle-v2.json`。脚本只把编译后的安全投影发送给
RAG；不会把原始 envelope、私有 transport 引用或该目录路径写入 release manifest。未指定时
使用脚本内置的通用示例夹具，保证本仓门禁自包含；该示例协议不是 RAG 服务端 API 契约。

每次运行都会在证据目录生成 `release-manifest.json`，记录 PASS/FAIL、验证阶段、完整 Git
SHA、初始 tree state、项目/OpenAPI 版本、API base path、最新 Flyway migration、passed
steps、PostgreSQL image、HTTP 检查数和
`["READ_ONLY","READ_WRITE"]` 验证画像。未执行到的运行时事实为 JSON `null`；manifest
不保存 credential、URL、payload、external ID 或 private path。

聚焦复跑真实阶段：

```bash
BUSINESS_CLIENT_VERIFY_PHASE=real \
./scripts/verify-business-client-readiness.sh
```

脚本所有 PostgreSQL 数据库、端口和 credential 都是隔离且一次性的。private 文件权限为
`0600`，退出 trap 清理资源；证据不保存 raw secret 或完整业务 payload。前端断言只使用
DOM、网络请求/响应和 JSON，不以截图作为正确性证据。该脚本包含 `mvn clean`，必须与其他
使用同一 worktree `target/` 的 Maven 进程串行运行。

接入语义见[业务服务接入指南](business-client-integration-zh-CN.md)。

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

也可以单独运行 V42/V51 Sync Run HTTP 合同验收：

```bash
./scripts/verify-document-sync-runs.sh
```

它会把一次性数据库从空库迁移到 V51，启用认证后创建受限读写/只读 principal，并验证
Sync Run begin、批量幂等、失败精确重放、preview/complete tombstone、namespace 与
Collection ACL、防枚举、持久化 item receipt、状态过滤、`limit=1` cursor 分页、
active/terminal 遍历语义、`Cache-Control: no-store`、敏感错误脱敏和禁悲观锁门禁。
证据写入 `.verification/document-sync-runs/<run-id>/summary.md`，不保存 credential、
cursor、external ID 或业务 payload。

专项 PostgreSQL service 测试也可使用一个明确可清空的隔离数据库运行：

```bash
DOCUMENT_SYNC_RUNS_IT_JDBC_URL=jdbc:postgresql://127.0.0.1:5432/<disposable-db> \
DOCUMENT_SYNC_RUNS_IT_USERNAME=postgres \
DOCUMENT_SYNC_RUNS_IT_PASSWORD= \
DOCUMENT_SYNC_RUNS_IT_CLEAN_CONFIRM=YES \
mvn -pl spring-ai-rag-core -am \
  -Ddocument-sync-runs.it.enabled=true \
  -Dtest=DocumentSyncRunsPostgresIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

该测试会反复执行 `Flyway.clean()`，不得指向开发库或生产库。

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
因为它需要 pgvector，并验证 Flyway V1-V51、固定向量列、Profile 专属索引、原子替换、
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
Flyway V1-V51，并用实际 PostgreSQL `bigint[]` 绑定执行 Vector 查询：

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
启动 `pgvector/pgvector:pg16`，从空库执行 Flyway V1-V51，并验证 JSONB round-trip、
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
执行 V1–V51，验证 V33 active-job coalesce、force 原子升级和并发 worker 原子条件
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

### 受管 API Principal PostgreSQL 矩阵

在真实 PostgreSQL 上执行 V48→V50 迁移、credential lifecycle、operation capability、
幂等 provisioning/replay/conflict、owner 隔离、并发首次创建、rotation/revoke 后 replay
状态、policy concurrency、last-ADMIN、last-used、共享 quota 与有界清理矩阵：

```bash
TESTCONTAINERS_RYUK_DISABLED=true \
mvn -pl spring-ai-rag-core -am \
  -Dmanaged-api-principal.it.enabled=true \
  -Dtest=ManagedApiPrincipalPostgresIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

所有测试必须实际执行且 `skipped=0`。禁用 Ryuk 只是本地 Docker 兼容 workaround，测试
仍会停止自己创建的 PostgreSQL container。发布验收还需让两个隔离端口 backend 共用一个
数据库，证明吊销在下一次请求生效，并且请求 quota 全局只计一份。

完整发布门槛使用统一脚本：

```bash
./scripts/verify-managed-api-principals.sh

# Mock 门槛全部通过后，显式执行真实 provider 验收
MANAGED_API_REAL_ENV_FILE=.env \
./scripts/verify-managed-api-principals.sh --with-real-llm
```

脚本串行执行 PostgreSQL 迁移/并发矩阵、`mvn clean compile test-compile`、Maven 全量测试、
WebUI Vitest/TypeScript/生产构建/alignment、核心 Mock Playwright、禁锁与文档门禁；随后
启动两个共享一次性 PostgreSQL 的真实后端和一个 Vite 前端，验证只读 identity/GET 与
写入 `403`、轮换继承能力、非法能力不落库、认证 capability discovery、跨实例 keyed
provisioning 的 create/replay/conflict、rotation/revoke 后 replay、全局 quota、policy
CAS、跨实例轮换/撤销、quota store 故障关闭和无截图真实 Playwright。真实 LLM 模式还覆盖 native
JSON/SSE 与 OpenAI-compatible JSON/SSE。真实 Chat principal 显式只有 `RAG_READ`；
脚本先验证写请求 `403` 且 provider counter 不变，再证明幂等重放不产生重复模型调用，
并要求真实 provider 调用总数严格等于 5。证据写入
`.verification/managed-api-principals/<run-id>/summary.md`，敏感响应只保存在 gitignored、
权限受限的 `private/` 子目录。

这里的 5 次是该受管 principal 回归合同的确定性预期值，用于证明拒绝与 replay 不会产生
额外调用；它不是更广泛客户生命周期验收的调用上限。完整接入验收应按实际场景继续覆盖创建、
更新、删除、恢复、凭据轮换和重启后的真实 Chat/Embedding 路径。

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
Controller/集成和导出测试；V32 会话 lease/原子性、V46 摘要 CAS、V47 durable
Chat turn 以及
`NextHighValueFeaturesPostgresIntegrationTest` PostgreSQL 矩阵；`mvn clean
compile test-compile`；完整 `mvn test`；安装当前 reactor 产物并测试独立的
`demos/demo-domain-extension` 与 `demos/demo-tool-calling-sql` 消费者；使用临时
PostgreSQL 和 dummy 模型端点执行
Spring Boot 启动/健康烟测；WebUI Vitest、TypeScript、生产构建；Chat 核心 Mock
Playwright；project-docs 和空白检查。日志及 Markdown 结果写入
`.verification/chat-capability/<run-id>/summary.md`。

独立 demo 不属于根 reactor。直接在 demo 目录运行 Maven 可能链接本地仓库里的旧
`spring-ai-rag-starter:1.0.0`；一键脚本会先安装当前工作区产物，再运行 demo 测试，
以验证新增 API 对外部消费者确实可编译。

浏览器门禁只使用 DOM 可见性、请求/响应断言、URL 断言和测试断言，不使用截图作为正确性
证据。浏览器套件覆盖模式/模型请求、AGENT 工具生命周期、来源、历史来源恢复、选定
Collection、移动端横向溢出、一次 retry 内复用 `Idempotency-Key`、response header/done
turn identity、部分 SSE replay 不重复 assistant bubble、409 输入保留以及 stop 不触发
retry。

本轮 `KNOWLEDGE` 查询扩展的门禁还必须覆盖 `BoundedMultiQueryExpander` 和真实
PostgreSQL 检索链路。focused 测试断言默认三路、`max-retrieval-queries=1` 不调用扩展
模型、超配置变体在执行前收敛、空白/精确重复去重、授权 context/history 保留、KNOWLEDGE
与 AGENT budget 隔离，以及响应 metadata 与持久化 attempt metadata 使用同一有界摘要。
PostgreSQL 矩阵中的 `HybridRetrieverRrfPostgresIntegrationTest` 会通过真实
`ProjectDocumentRetriever` 和 `HybridRetrieverService` 执行扩展后的 query，并验证重复
变体不会产生第二次 embedding/SQL 检索。运行：

同一 focused 门禁还包含 `ProjectDocumentJoinerTest`、
`ModeAwareChatClientFactoryTest`、`RetrievalTraceCollectorTest` 和
`ChatExecutionServiceTest`。这些测试共同验证不依赖 Map 遍历的规范排序、最高有限
score 保留、匿名和非有限 score 边界、rerank 前去重、四整数
`metadata.retrieval.documentJoin` 契约、持久化 attempt 一致性，以及 AGENT 不出现该
字段。诊断 payload 不使用 query 文本、Document ID、正文或 metadata 值。

```bash
TESTCONTAINERS_API_VERSION=1.40 \
TESTCONTAINERS_RYUK_DISABLED=true \
./scripts/verify-chat-capability.sh
```

该脚本的浏览器阶段仍只使用 DOM、可访问状态、网络请求/响应和 JSON 断言，不使用截图；
`metadata.retrieval.queryExpansion` 不应出现原始 query、模型输出或异常堆栈。若 PostgreSQL
或 Docker 不可用，必须保留 `SKIP` 证据，不能把 focused Mock 测试当作真实数据库通过。

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
./scripts/verify-chat-capability.sh --with-real-llm
```

本轮 Chat turn 幂等性还提供一个更窄、可重复的真实 provider smoke。它固定使用
`PLAIN`，因此在只验证 Chat 幂等链路时不需要 Embedding key：

```bash
BASE_URL=http://127.0.0.1:18081 \
REAL_LLM_ENV_FILE=.env \
./scripts/real-llm-chat-idempotency-smoke.sh
```

该 smoke 必须连接已启动的隔离 PostgreSQL profile 服务，并验证 native JSON/SSE 首次
请求、相同 key 的完整响应重放、key conflict、`GET` turn status，以及 provider counter
在 replay 前后只增加一次。它不能替代需要真实 Embedding 的检索 smoke，也不能用 Mock
Playwright 或代码 review 代替真实 provider 证据。

脚本会从 `.env` 或调用环境读取真实模型配置，并要求真实 WebUI 路径提供
`RAG_ROOT_API_KEY`。它会创建一次性 PostgreSQL 数据库，在隔离端口启动
`scripts/dev.sh`（默认后端 `18083`、WebUI `15175`），执行真实 WebUI
`chat-real.spec.ts` 和 provider smoke，最后清理服务、临时环境文件和数据库。
需要时可用 `CHAT_REAL_BACKEND_PORT`、`CHAT_REAL_FRONTEND_PORT` 覆盖端口。未传该选项时，
真实 LLM 门禁会明确记录为 `SKIP`；本地测试和 Mock Playwright 不代表真实模型或真实
Tool Calling endpoint 已验证。

rerank 文档级证据多样化使用独立门禁：

```bash
./scripts/verify-rerank-document-diversity.sh
```

该门禁先通过单元测试和真实 PostgreSQL/pgvector 夹具证明两阶段选择器，再用 POST Search
JSON 验证多样化契约，用实际 GET Search 页面验证 DOM、认证和代理兼容。前端证据只使用
可见 DOM、请求/响应、JSON 和数据库支持的行为断言，不使用截图。真实 LLM baseline
通过后，它还会在同一可处置数据库与固定夹具上比较 cap=`0` 和 cap=`2`：每个变体默认先
预热，再采集 20 个 Search、5 个 Chat 请求，通过 trace ID 只读关联
`rag_retrieval_logs`。`runtime-comparison.json` / `runtime-comparison.md` 记录
Search/Chat retrieval p95、rerank stage p95、HTTP latency/payload 和最终 unique document
count；延迟与 payload 不设通过阈值，确定性正确性继续由 PostgreSQL 集成矩阵承担。

只读关联得到的数据库 `result_count` 表示 latest retrieval outcome 数量。Search 必须与
最终 HTTP result count 相等；KNOWLEDGE Chat 的最终 HTTP sources 来自 advisor
后处理后的 document context，query join、rerank 或 prompt budget 都可能使它与 latest
retrieval outcome 不同。运行时产物会记录两者是否一致，但不会把两个不同流水线阶段误当成
同一个契约。

运行时 Chat 采样对明确的瞬时 HTTP `429/502/503/504` 默认最多尝试两次，并输出每次重试
日志；Search、不可重试 HTTP、无效 payload 和尝试耗尽仍使门禁失败。只有需要调整这个正整数
上限时才使用 `RERANK_DIVERSITY_CHAT_MAX_ATTEMPTS`。

同一门禁的 `HeuristicRerankProviderTest`、`ReRankingServiceTest` 和
`HttpRerankProviderTest` 还覆盖无空格 CJK 局部匹配、混合语言、英文兼容、blank/长输入、
完全重复 chunk、默认权重排序、title-only 英文/CJK/混合 ID、blank title 精确兼容、
title/diversity 隔离、字段复制，以及 HTTP 成功请求不含 title、fallback 使用标题感知
heuristic；它们还覆盖拒绝更长标识符内嵌的 Latin/数字 term、外层标点、CJK/非 CJK
边界 transition、带符号技术词，以及先出现非法内嵌 occurrence、后出现合法 occurrence。
`HybridRetrieverRrfPostgresIntegrationTest` 使用真实 PostgreSQL/pgvector 先生成“原始
向量略高但正文词法无关”的第一候选，再分别证明 CJK chunk relevance 和来自
`rag_documents.title` 的权威标题可以通过真实 factory 与 `ReRankingService` 纠正排序；
它还证明 `storage` / `OpenAI` / `19042` 等 title 内嵌 substring 不会压过完整的
`RAG` / `AI` / `9042` title term。该整类测试必须 `failures=0`、`errors=0`、
`skipped=0`。

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
