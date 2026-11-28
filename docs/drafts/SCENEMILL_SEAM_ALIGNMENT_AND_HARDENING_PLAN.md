# Scenemill（星幕 AI）系统接缝对齐与加固规划

> **文档状态**：可实施规划（v1.1 冻结基线，已完成 3/3 连续系统审查）
> **适用仓库**：`spring-ai-rag`（`spring-ai-rag-api/`、`spring-ai-rag-core/`）
> **配套对端规划**：`seedance-research/docs/drafts/material-rag/星幕与Spring-AI-RAG系统接缝审查与改进规划-2026-11-28.md`
> **双系统概念映射与防腐层参考**：`seedance-research/docs/drafts/material-rag/README.md` §1.1–§1.2 与交互式说明页 `seedance-research/.archify/explainer-scenemill-rag-acl-concept-mapping-20261128-022000/scenemill-rag-acl-concept-mapping.html`（阐明星幕租户/项目/素材如何经防腐层映射至 RAG `jsonbPayload`、6 Collection 与不透明 `externalId`）
> **前置基线**：`spring-ai-rag` 当前 `main`（含 V75–V86 多租户与运营方管理控制台代码）、`docs/drafts/MULTI_TENANCY_MATURITY_AND_P0_AUDIT.md`

---

## 1. 问题与成功标准

### 1.1 背景与核心问题

星幕 AI（Scenemill）是 `spring-ai-rag` 最早也是最重的上游业务消费方，通过稳定集成契约（`spring-ai-rag-integration` v1.1）消费 `/api/v1/rag/auth/me`、`/api/v1/rag/integration-capabilities`、`/api/v1/rag/collections/by-key/{key}` 以及 `/api/v1/rag/json-records/**`。

星幕采用 **N:1 逻辑复用**（全部星幕客户组织共享 3 个部署级租户 Collection `tenant-{image,audio,video}` + 3 个平台共享 Collection `platform-{image,audio,video}`，在 `json-records/search` 中以 `payloadContains.tenantKey` 过滤）。随着 `spring-ai-rag` 已演进至 V75–V86 多租户双模式（`SINGLE` / `STRICT`），经逐行代码比对双仓系统接缝，发现 **3 项 P0 级接缝/性能缺陷** 与 **2 项 P1 级契约护栏与运维缺口**：

| 编号 | 级别 | 问题摘要 | 影响 |
|---|---|---|---|
| **RAG-P0-1** | **P0** | 检索层（`com.springairag.core.retrieval.*`）零处配置 pgvector 0.8+ `hnsw.iterative_scan`（虽 `V34` 已有 `jsonb_payload` GIN 索引，但 HNSW 图遍历仍受限于默认 `ef_search` 截断） | 在星幕 N:1 共享 Collection（以及 `STRICT` 模式多租户共享 HNSW 索引）中，当大租户占 90% 语料而小租户仅占 1% 时，带 `payloadContains.tenantKey` 或 `tenant_id` 过滤的向量检索会出现 `Recall@K` 断崖下跌甚至返回 0 条 |
| **RAG-P0-2** | **P0** | `/api/v1/rag/integration-capabilities`（`IntegrationCapabilityCatalog.principalProjection`）未返回 `tenantId`/`tenantMode`/`tenantStatus`，与 `/api/v1/rag/auth/me`（`ApiKeyIdentityController`）发生内部契约漂移 | 上游星幕 `MaterialRagBindingPreflightVerifier` 串联调用两接口核验 principal 一致性时，无法从 capabilities 快照获得租户状态，且需验证 `NORMAL` `DATABASE_API_KEY` 在 `SINGLE`/`STRICT` 双模式下的路由与 ACL 一致性 |
| **RAG-P0-3** | **P0** | `JsonRecordUpsertResponse` 缺少 `contentHash` 摘要字段，且需在数据面全链路固化非 `ACTIVE` 租户的 `403` 结构化错误码（`TENANT_SUSPENDED` / `TENANT_NOT_ACTIVE`）+ `X-RAG-Failure-Class: AUTHORIZATION` | 上游星幕为了校验写入完整性被迫在每次 `200 OK` 后额外发起 `GET /by-external-id` 导致流量翻倍；且上游需要稳定错误码以区分“租户暂停（可恢复退避）”与“凭据失效（熔断）” |
| **RAG-P1-1** | **P1** | `spring-ai-rag` 仓库缺少针对星幕消费契约的机器可读回归测试套件（`ScenemillSeamContractTest`） | 后续 2,000+ 次提交中任何字段改名、状态码微调或路由策略变化都可能悄无声息地破坏星幕的 Preflight / Delivery / Search |
| **RAG-P1-2** | **P1** | `IntegrationCapabilityCatalog.reliability` 已声明 `X-Trace-Id`、`X-RAG-Failure-Class` 和 `retryAfterStatusCodes=[429]`，但 `json-records` 与全局限流/异常路径缺少端到端接缝断言，且缺少多产品共用 `STRICT` 模式下“1 产品部署 = 1 RAG 租户（`scenemill-prod`）”的标准化 provisioning 模板 | 上游星幕启用 `Retry-After` 与 `X-Trace-Id` 对齐后缺乏后端保证；未来与 ESA/BOS 共用同一套 `spring-ai-rag` 实例时缺少开箱即用的租户/Collection/4-Key 编排脚手架 |

### 1.2 成功标准（验收完成定义）

1. **HNSW 迭代扫描与倾斜语料召回达标**：向量检索执行路径在 pgvector ≥ 0.8.0 且开关开启（默认 `true`）时，在查询事务内执行 `SET LOCAL hnsw.iterative_scan = relaxed_order`（并可配 `hnsw.max_scan_tuples`）；在旧版 pgvector 上启动探测自动降级并记录 `WARN`，不报错。300k（或等效比例 10k 单元集成）倾斜语料（90%/9%/1%）下，1% 小租户的 `payloadContains.tenantKey` 过滤向量 `Recall@10` ≥ 0.90。
2. **双端点 Principal 契约一致且向后兼容**：`IntegrationCapabilitiesResponse.Principal` 增量补充 `tenantId`、`tenantMode`、`tenantStatus`（与 `/auth/me` 语义完全一致），不破坏既有 5 字段顺序与 JSON 兼容；`NORMAL` 受限 Key 在 `SINGLE` 和 `STRICT` 模式下调用 `/auth/me`、`/integration-capabilities`、`/collections/by-key/{key}`、`/json-records/**` 全部行为一致。
3. **写回执摘要与租户暂停错误契约稳定**：`JsonRecordUpsertResponse` 增量返回 `contentHash`（与 `RagDocument.contentHash` 一致）；`SUSPENDED` / `OFFBOARDING` / `DISABLED` 租户的 Key 访问数据面稳定返回 HTTP `403`、JSON `code = "TENANT_SUSPENDED"`（或对应状态码）及 `X-RAG-Failure-Class: AUTHORIZATION`。
4. **星幕接缝契约测试成为常驻护栏**：新增 `ScenemillSeamContractTest`，同时在 `SINGLE` 和 `STRICT`（`scenemill-prod` 租户）双模式下锁死全部星幕依赖的 Wire 契约并 100% 通过。
5. **响应头与多产品 Provisioning 模板落地**：`429` 稳定返回正整数 `Retry-After`；所有响应回写 `X-Trace-Id` 与错误类 `X-RAG-Failure-Class`；提供可复用的 `scenemill-prod` `STRICT` 模式 provisioning 文档/脚本模板。

---

## 2. 范围与非目标

### 2.1 本期范围（In Scope）

- `spring-ai-rag-core` 向量检索层的 pgvector `hnsw.iterative_scan` 支持与能力探测。
- `spring-ai-rag-api` / `spring-ai-rag-core` 中 `IntegrationCapabilitiesResponse.Principal`、`IntegrationCapabilityCatalog`、`JsonRecordUpsertResponse`、`JsonRecordService` 的增量向后兼容扩展。
- 响应头一致性校验（`X-Trace-Id`、`X-RAG-Failure-Class`、`Retry-After`）与非 `ACTIVE` 租户 `403` 契约验证。
- 新增 `ScenemillSeamContractTest`（覆盖 `SINGLE` 与 `STRICT` 双模式）。
- 补充面向星幕的 `STRICT` 模式（`scenemill-prod` 租户 + 6 Collection + 4 受限 `NORMAL` Key）provisioning 参考指南，并同步 `docs/drafts/README*.md`。

### 2.2 明确非目标（Non-Goals）

- **不升级协议主版本**：`IntegrationCapabilityCatalog.CONTRACT_VERSION` 保持 `"1.1"`（所有 DTO 变更均为纯增量可选字段，严格向后兼容未升级的星幕 `MaterialRagBindingPreflightVerifier`）。
- **不放宽 `STRICT` 模式跨租户 RLS 隔离**：星幕在多产品共用场景下采用“1 产品部署 = 1 RAG 租户（`scenemill-prod`）”，平台共享素材与客户组织素材同处 `scenemill-prod` 租户内不同 Collection，绝不在数据库层开跨租户后门。
- **不新增 Flyway 迁移**：`V34__add_jsonb_payload_containment_index.sql` 已提供 `idx_rag_documents_jsonb_payload_path_ops` GIN 索引；`hnsw.iterative_scan` 属 pgvector 查询期 GUC 参数，无需表结构变更。
- **不修改运营方管理控制台前端页面**：前端精修由 `OPERATOR_CONSOLE_UI_UX_AUDIT.md` 单独推进。

---

## 3. 现状证据（代码级锚点）

以下事实全部取自 `spring-ai-rag` 当前源码：

1. **RAG-P0-1 缺少 `hnsw.iterative_scan`**：
   - 检索目录：`spring-ai-rag-core/src/main/java/com/springairag/core/retrieval/` 全目录 grep `iterative_scan` 为 **0 匹配**。
   - 索引现状：`spring-ai-rag-core/src/main/resources/db/migration/V34__add_jsonb_payload_containment_index.sql` 已创建 `idx_rag_documents_jsonb_payload_path_ops ON rag_documents USING GIN (jsonb_payload jsonb_path_ops)`，但 HNSW 过滤检索未开启迭代扫描，小租户倾斜语料仍会因 HNSW 图遍历提前耗尽 `ef_search` 而丢召回。
2. **RAG-P0-2 双端点 Principal 漂移**：
   - `ApiKeyIdentityController.java`（`GET /api/v1/rag/auth/me`）已返回 `tenantId`、`tenantMode`、`tenantStatus`。
   - `IntegrationCapabilityCatalog.java`（`databaseProjection` / `principalProjection`）构造 `IntegrationCapabilitiesResponse.Principal` 仅传入 5 个字段（`principalType`、`role`、`capabilities`、`collectionAccessMode`、`allowedCollectionKeys`），未携带多租户状态。
3. **RAG-P0-3 `JsonRecordUpsertResponse` 缺 `contentHash`**：
   - `JsonRecordService.java` 的 `toUpsertResponse(DocumentMutationService.JsonMutationResult)` 返回 18 个字段，而 `getDetail(Long)` 返回了 `doc.getContentHash()`，导致星幕无法仅凭 `upsert` 响应完成内容摘要核验。
   - 非 `ACTIVE` 租户 `403` 短路已在 `TenantContextFilter`（Phase 0 P0-3 修复）实现，需在星幕数据面契约测试中锁定其精确错误码与 `X-RAG-Failure-Class` 响应头。
4. **RAG-P1-1 / P1-2 契约护栏与响应头**：
   - `IntegrationCapabilityCatalog.describe()` 已在 `Reliability` 中声明 `traceHeader = "X-Trace-Id"`、`failureClassHeader = "X-RAG-Failure-Class"`、`retryAfterStatusCodes = List.of(429)`，需要端到端 MockMvc/集成测试确保 `RagJsonRecordController` 路径实际兑现该声明。

---

## 4. 约束与不变量

1. **Wire 严格向后兼容不变量**：所有响应 DTO 只允许**增量追加可空字段**，禁止删除、重命名或收窄任何既有 JSON 字段类型；保留旧参数构造器（委托到新构造器）防止破坏已有单测与外部扩展。
2. **事务内 `SET LOCAL` 不变量**：`SET LOCAL hnsw.iterative_scan = ...` 必须在只读或查询事务边界内执行（`SET LOCAL` 仅对当前事务生效，连接归还 HikariCP 后自动复位，绝不污染连接池）。
3. **双模式零回归不变量**：所有接缝端点在 `rag.tenant.mode=SINGLE`（默认 `default` 租户）和 `rag.tenant.mode=STRICT`（独立 `scenemill-prod` 租户）下必须遵循同一套 Wire 契约。
4. **安全不变量**：`/api/v1/rag/auth/me` 与 `/api/v1/rag/integration-capabilities` 对 `NORMAL` 受限 Key 只暴露当前 Key 自身授权的 `allowedCollectionKeys` 与所属 `tenantId`，严禁泄漏其他租户或未授权 Collection 信息。

---

## 5. 推荐方案与理由（已收敛决策）

| 决策项 | 采纳方案 | 理由与取舍 |
|---|---|---|
| **D1. HNSW 迭代扫描配置与探测** | 在 `RagProperties` 检索配置下新增 `hnswIterativeScan`（枚举/字符串：`OFF` / `RELAXED_ORDER` / `STRICT_ORDER`，默认 **`RELAXED_ORDER`**）和 `hnswMaxScanTuples`（默认 `20000`）。启动时（或首次查询时）通过一次 `SELECT setting FROM pg_settings WHERE name = 'hnsw.iterative_scan'` 缓存数据库是否支持该 GUC；支持则在向量检索事务内执行 `SET LOCAL hnsw.iterative_scan = 'relaxed_order'` 与 `SET LOCAL hnsw.max_scan_tuples = <N>`；不支持则降级为 no-op 并记录一次 `WARN` | `relaxed_order` 在 pgvector 0.8+ 中兼顾召回率与延迟；运行时能力探测确保兼容使用 pgvector 0.7 的旧测试库或旧 ECS 实例，零启动阻断 |
| **D2. `IntegrationCapabilitiesResponse.Principal` 扩展** | 增量追加 3 个字段：`String tenantId`、`String tenantMode`、`String tenantStatus`（保留原有 5 参重载构造器供旧测试兼容）。在 `IntegrationCapabilityCatalog.principalProjection()` 中复用与 `ApiKeyIdentityController` 同源的租户解析逻辑（`TenantContext` / `TenantRepository` / `RagTenantProperties`） | 保持 `/auth/me` 与 `/integration-capabilities` 租户视图单一事实源，零破坏性兼容星幕旧版 `PrincipalProjection` |
| **D3. `JsonRecordUpsertResponse` 扩展** | 增量追加 `String contentHash`（保留旧构造器重载，默认 `null`）。`JsonRecordService.toUpsertResponse()` 填入 `document.getContentHash()` | 让星幕快速路径无需二次 `GET /by-external-id` 即可核验写入内容摘要，直接帮助星幕削减 50% 投递 HTTP 流量 |
| **D4. 响应头与 `429 Retry-After` 保证** | 核查全局 Filter / `GlobalExceptionHandler`：确保任何进入 `/api/v1/rag/**` 的请求均透传或生成 `X-Trace-Id` 并写入响应头；错误响应（4xx/5xx）一律附带 `X-RAG-Failure-Class`；`429` 限流响应始终附带正整数秒 `Retry-After`（默认取限流窗口剩余秒数，最小 `1`） | 兑现 `IntegrationCapabilityCatalog.reliability` 承诺，打通星幕 `MaterialRagHttpClient` 链路追踪与退避控制 |
| **D5. 星幕接缝契约测试范围** | 新增 `ScenemillSeamContractTest`（Spring Boot MockMvc / 测试容器），按星幕真实调用序列覆盖 7 个契约场景，并参数化运行于 `SINGLE` 与 `STRICT`（`scenemill-prod`）双模式 | 把口头约定固化为 CI 机械门禁，防止后续迭代破坏星幕集成 |

---

## 6. 实施步骤（Work Packages）

### WP-1：向量检索 `hnsw.iterative_scan` 能力探测与事务级启用（RAG-P0-1）
- **目标文件**：
  - `spring-ai-rag-core/src/main/java/com/springairag/core/config/RagProperties.java`（新增 `hnswIterativeScan`、`hnswMaxScanTuples` 配置项）
  - `spring-ai-rag-core/src/main/java/com/springairag/core/retrieval/HybridRetrieverService.java`（已精确定位 `<=> CAST(? AS vector)` 两处向量检索 SQL 均在此类中）
- **具体变更**：
  1. 增加 pgvector `hnsw.iterative_scan` GUC 可用性探测组件（带缓存，避免每次查询查 `pg_settings`）。
  2. 在 `HybridRetrieverService` 执行带过滤条件（`collection_id`、`payloadContains`、`tenant_id`）的向量检索 SQL 之前，确保处于事务上下文并执行 `SET LOCAL hnsw.iterative_scan = ...` 与 `SET LOCAL hnsw.max_scan_tuples = ...`（白名单枚举校验防注入，严禁拼接用户输入）。
  3. 补充单测（验证 SQL/GUC 设置与旧版 pgvector 降级）及倾斜语料召回基准测试脚本/用例。

### WP-2：双端点 Principal 租户字段对齐与 `JsonRecordUpsertResponse.contentHash`（RAG-P0-2 + RAG-P0-3）
- **目标文件**：
  - `spring-ai-rag-api/src/main/java/com/springairag/api/dto/IntegrationCapabilitiesResponse.java`
  - `spring-ai-rag-core/src/main/java/com/springairag/core/service/IntegrationCapabilityCatalog.java`
  - `spring-ai-rag-api/src/main/java/com/springairag/api/dto/JsonRecordUpsertResponse.java`
  - `spring-ai-rag-core/src/main/java/com/springairag/core/service/JsonRecordService.java`
- **具体变更**：
  1. `IntegrationCapabilitiesResponse.Principal`（普通 POJO 类）增量添加 `@JsonInclude(NON_NULL)` 的 `tenantId`、`tenantMode`、`tenantStatus` 及 getter/setter，并保留既有 0 参和 5 参构造器向后兼容。
  2. `IntegrationCapabilityCatalog` 注入租户状态解析器（与 `ApiKeyIdentityController` 保持一致），填充上述 3 字段；检查 `ApiRoutePolicy` 与 `TenantEndpointDeclarationCatalog`，确保 `NORMAL` 受限 Key 访问 `/auth/me`、`/integration-capabilities`、`/collections/by-key/{key}`、`/json-records/**` 在 `SINGLE`/`STRICT` 下无 403 回归。
  3. `JsonRecordUpsertResponse`（当前为 18 参 Java `record`）增量追加第 19 个组件 `String contentHash`，并**必须新增 18 参向后兼容重载构造器**（委托传入 `contentHash = null`）以保证所有既有调用方与单测零编译破坏；`JsonRecordService.toUpsertResponse()` 填入 `document.getContentHash()`。

### WP-3：响应头一致性加固与 `ScenemillSeamContractTest` 契约套件（RAG-P1-1 + RAG-P1-2）
- **目标文件**：
  - 现有 Trace/Exception/RateLimit Filter 或 `GlobalExceptionHandler`（核查并补齐 `X-Trace-Id`、`X-RAG-Failure-Class`、`429 Retry-After`）。
  - 新增 `spring-ai-rag-core/src/test/java/com/springairag/core/seam/ScenemillSeamContractTest.java`。
  - 新增/更新多产品共用 `STRICT` 模式 provisioning 文档片段（`scenemill-prod` 租户 + 6 Collection + 4 受限 Key，复用 `issueKeyForTenant`）。

---

## 7. 验证计划

### 7.1 契约与单元测试（必过）

| 用例编号 | 覆盖缺陷 | 验证方式与断言 |
|---|---|---|
| **V-RAG-1** | RAG-P0-1 | 单元测试验证支持 `hnsw.iterative_scan` 时事务内执行 `SET LOCAL hnsw.iterative_scan = 'relaxed_order'` 与 `hnsw.max_scan_tuples`；不支持时安全降级且仅打一次 `WARN`；集成测试（PG + pgvector 0.8）验证 90%/9%/1% 倾斜语料下 1% 租户 `payloadContains.tenantKey` 召回率 ≥ 0.90 |
| **V-RAG-2** | RAG-P0-2 | MockMvc 验证 `NORMAL` 受限 Key 在 `SINGLE` 和 `STRICT` 模式下调用 `/auth/me` 与 `/integration-capabilities` 返回一致的 `tenantId`、`tenantMode`、`tenantStatus`、`allowedCollectionKeys` |
| **V-RAG-3** | RAG-P0-3 | 验证 `POST /api/v1/rag/json-records/upsert` 返回非空 `contentHash` 且与 `GET /by-external-id` 的 `contentHash` 完全一致；验证 `SUSPENDED` 租户 Key 调用 `upsert`/`search`/`source-delete` 稳定返回 `403` + `code=TENANT_SUSPENDED` + `X-RAG-Failure-Class: AUTHORIZATION` |
| **V-RAG-4** | RAG-P1-1 | `ScenemillSeamContractTest` 全量覆盖：① `/auth/me`、② `/integration-capabilities`（`protocol.name/version` + `features.dataPlane.jsonRecords.*` + `limits.structuredRecords.*`）、③ `/collections/by-key/{key}`、④ `upsert` + `CAS 409`、⑤ `source-delete` tombstone、⑥ `by-external-id`、⑦ `search`（`payloadContains` 过滤），在 `SINGLE` 和 `STRICT` 双模式下全绿 |
| **V-RAG-5** | RAG-P1-2 | 验证携带 `X-Trace-Id: trace-xyz` 请求在 200/403/409/429 下均原样回写 `X-Trace-Id: trace-xyz`；验证 `429` 返回正整数 `Retry-After` 与 `X-RAG-Failure-Class: RATE_LIMITED` |

执行命令：
```bash
mvn test -pl spring-ai-rag-api,spring-ai-rag-core -Dtest="ScenemillSeamContractTest,TenantP0FixesTest,IntegrationCapability*Test,JsonRecord*Test"
```

---

## 8. 发布与恢复

1. **纯向后兼容升级**：所有 JSON 字段为纯增量追加，无需与星幕同步停机发版；可先发 `spring-ai-rag`，再发星幕优化版本，或反向顺序发布，均零中断。
2. **HNSW 迭代扫描一键降级**：若生产 PG 负载异常，可通过环境变量 `RAG_RETRIEVAL_HNSW_ITERATIVE_SCAN=OFF` 热切换关闭迭代扫描，无需回滚代码。
3. **多产品共用切换建议**：星幕独占实例升级后默认维持 `rag.tenant.mode=SINGLE`；待未来接入 ESA/BOS 共用时，再按 `scenemill-prod` 租户模板切换 `STRICT` 模式。

---

## 9. 遗留事项与开放边界

1. **真实 300k 行生产级 pgvector 压测**：需在具备 pgvector 0.8+ 的独立 PostgreSQL 实例上补跑完整 300k 倾斜语料压测，记录 `p95` 延迟与最优 `hnsw.max_scan_tuples` 默认值。
2. **星幕 submodule 指针升级联动**：本规划完成后，需通知星幕仓执行联调验收并评估将 `services/spring-ai-rag` 从 `f1cdcba1` 推进到新基线。

---

## 附录：连续三轮系统审查记录（3/3 通过 · v1.1 冻结基线）

- **第 1 轮（代码锚点与 DTO 兼容性核实）**：实查源码精确定位 `<=> CAST(? AS vector)` 全部位于 `HybridRetrieverService.java`；核实 `IntegrationCapabilitiesResponse.Principal` 为普通 POJO 类、`JsonRecordUpsertResponse` 为 18 参 `record`，补充了 18 参重载构造器刚性要求以防编译破坏。实质缺陷清零后进入第 2 轮。
- **第 2 轮（双仓接缝闭环核对）**：核对星幕 `MaterialRagBindingPreflightVerifier`、`MaterialRagDeliveryResultApplier` 与本规划的双向契约——`CONTRACT_VERSION` 维持 `"1.1"`、新增字段全部增量可选、`403 TENANT_SUSPENDED` 与 `X-Trace-Id`/`X-RAG-Failure-Class`/`429 Retry-After` 语义两端完全一致。实质性缺陷：**0**。
- **第 3 轮（验证门禁、发布顺序与降级策略）**：核对 V-RAG-1～V-RAG-5 覆盖 `SINGLE` 与 `STRICT` 双模式、无 Flyway 迁移、支持 `RAG_RETRIEVAL_HNSW_ITERATIVE_SCAN=OFF` 一键降级及 pgvector 0.7 自动探针回退。实质性缺陷：**0**。结论：**3/3 审校通过，可交付 Coding Agent 实施**。
