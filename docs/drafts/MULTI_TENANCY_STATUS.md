# 多租户现状调研

> 只读调研：基于当前仓库代码、Flyway 迁移与 `docs/` 正式文档，未启动服务、未修改代码。
> 行号指向调研时的仓库快照，供复核使用。
>
> **2026-11-04 复核**：V71 已补齐文件子系统与评估/反馈面的归属边界，本文第 5 节
> 缺口 1、2 随之关闭；其余结论（无租户实体、无 RLS、ACL 只到 Collection）不变。
>
> **配套方案**：[多租户平台技术路线与可插拔方案](MULTI_TENANCY_TECH_ROADMAP.md)
> —— 方向已定、代码未实施。

## 1. 结论

**当前不支持真正意义上的多租户。** 全仓没有租户实体、`tenant_id` 字段或租户配置；
没有租户层级、租户级配额、租户管理面，也没有数据库级行隔离（RLS）。

现有能力是三层"部分隔离"：

1. **Collection 级 ACL**：API Key / 受管 principal 可限制到若干 Collection，Search、
   Chat、Document 列表、上传、PDF-to-RAG、导出在该白名单内收敛。
2. **principal 级资源归属**：聊天历史、Spring AI memory、检索诊断、评估套件、
   用量与配额按 stable principal 隔离。
3. **安全边界归属（V71 起）**：文件子系统、评估历史与用户反馈按
   `owner_principal_id`（文件另加 `collection_id`）过滤；受限 principal 看不到
   他人的历史行，遗留无归属行对受限 principal 不可见（fail-closed）。

因此可用于"单实例 + 多业务 Key / 多知识库"场景，不能直接作为多租户平台。

## 2. 已确定的技术方向（2026-11-04）

> 这是方向决策，不是实现状态。代码与迁移尚未做多租户改造；当前真实能力仍以本文
> 第 3–7 节为准。系统化方案、代价表与外部考古见
> [多租户平台技术路线与可插拔方案](MULTI_TENANCY_TECH_ROADMAP.md)。

结论：**选 C —— 单仓、单 schema、表永远租户化。**

- 不在本仓之外另开 EE 仓，也不做"单租户无 `tenant_id` 列 / 多租户有列"的双 schema 插件。
- 所有该隔离的表从第一天就带 `tenant_id`；唯一约束、外键、索引、RLS 都按租户设计，
  只有一条迁移线。
- **单租户 = N=1**：固定解析到 `default`，代码、表、查询与多租户完全相同。
- **多租户 = 严格模式**：真实解析租户，强制 RLS + 独立 `NOBYPASSRLS` 应用角色 + 启动校验。
- 可插拔只落在 `TenantResolver`、`TenantJobDispatcher` 与可选运营模块；schema 不插拔。
- 若必须闭源企业版，选 C2：core 保持开源单 schema，企业能力做私有模块，仍不 fork 仓库。

与既有事实的关系：

- V71 的 `owner_principal_id` / `collection_id` 归属是**租户内二级边界**，继续保留；
  不能替代租户隔离。
- `collection_key` 当前仍是全局唯一（第 5 节缺口 5），将在第一阶段改为
  `(tenant_id, collection_key)`。
- ACL 仍停留在 Collection、且无 RLS 兜底（第 5 节缺口 4）；多租户模式由 RLS 补齐
  数据库级兜底。
- 当前"默认无认证"（第 5 节缺口 3）在多租户模式必须关闭；单租户模式保持现状，
  但不得被误当成多租户。

六条方向的摘要（完整"建议 / 理由 / 代价"见方案文档第 9 节）：

1. 商业模式：一仓开源 + 可选企业模块；闭源则私有模块，不 fork。
2. 租户是安全边界，Collection 是租户内知识库；不跨租户共享 Collection。
3. 多租户模式强依赖 PostgreSQL RLS，并需 migrator / app / platform 三角色。
4. 单租户默认关 RLS，但 schema/列/约束/任务路径必须完整；CI 始终跑双租户 RLS。
5. 租户来源用 API Key 绑定 + 宿主 `TenantResolver`；v1 不在 URL 放 `tenantId`。
6. 平台全局表单张裁决，不一刀切加 `tenant_id`。

**未实现声明**：以上是设计与决策输入；截至本记录，仓库仍无 `tenant` 表、`tenant_id` 列、
RLS 迁移、`TenantContext` 或租户管理面。

## 3. 现有隔离能力

### 3.1 Collection ACL

- ACL 落在凭证上：`rag_api_key.allowed_collection_ids VARCHAR(2048)`，
  受管 principal 使用同名字段
  （`spring-ai-rag-core/src/main/resources/db/migration/V24__api_key_allowed_collections.sql:L1-L6`、
  `.../V48__managed_api_principals_and_shared_quota.sql:L3-L15`）。
- 范围求交与越权拒绝在
  `spring-ai-rag-core/src/main/java/com/springairag/core/security/ApiKeyCollectionAccess.java:L138-L167`。
- 受限 Key 不能创建、导入或克隆 Collection（同文件 `:L309-L314`）。

### 3.2 principal 级归属

- 聊天历史与检索日志带 `owner_principal_id`
  （`.../entity/RagChatHistory.java:L43`、`.../entity/RagRetrievalLog.java:L74-L75`）。
- 历史查询按 principal 过滤，他人的 session 返回 `SESSION_NOT_FOUND`
  （`.../controller/RagChatController.java:L757-L772`）。
- 配额按 principal 记账
  （`.../V48__managed_api_principals_and_shared_quota.sql:L74-L81`）。
- 文件、反馈与评估历史带归属列
  （`.../V71__security_boundary_ownership.sql:L14-L43`）：
  `fs_files` / `fs_import_batches` 增加 `owner_principal_id` 与 `collection_id`，
  `rag_user_feedback` / `rag_retrieval_evaluations` 增加 `owner_principal_id`。
- 受限 key 的文件树与读取按"owner 或允许的 Collection"过滤
  （`.../repository/FsFileRepository.java:L79-L104`）。
- 评估历史与反馈历史在受限 principal 下按 owner 过滤
  （`.../service/RetrievalEvaluationServiceImpl.java:L236-L283`、
  `.../service/UserFeedbackServiceImpl.java:L150`）。

## 4. 证据

### 4.1 数据模型

- `rag_collection` 只有名称、描述、嵌入配置、启用状态与 metadata；
  `rag_documents` 只有 `collection_id`，没有租户列
  （`spring-ai-rag-core/src/main/resources/db/migration/V1__init_rag_schema.sql:L39-L49`、
  `:L52-L54`）。
- 全量迁移检索 `tenant` 零匹配；实体目录唯一命中是
  `.../entity/RagCollection.java:L12-L15` 的 Javadoc"multi-tenant isolation"，仅为注释。
- `collection_key` 全局唯一，无租户命名空间
  （`.../entity/RagCollection.java:L16-L21`、`:L31`）。
- 没有 `ROW LEVEL SECURITY` 迁移，隔离完全依赖应用层 SQL 拼接。

### 4.2 检索

- 统一过滤片段：`SELECTED` → `AND d.collection_id = ANY (?)`，`ANY_ASSIGNED` →
  `collection_id IS NOT NULL`，`NONE` → 不过滤
  （`.../retrieval/RetrievalScopeSql.java:L41-L65`）。
- 受限调用方默认收敛到白名单：未指定范围时走 `CALLER_VISIBLE`
  （`.../service/CollectionRetrievalScopeResolver.java:L69-L74`），
  受限时返回 `selectedCollections(allowedIds)`（`:L132-L148`），
  请求越界 id/key 报 403（`:L169-L203`）。
- 各入口统一走 resolver：Search
  （`.../controller/RagSearchController.java:L159-L175`）、
  Chat（`.../controller/RagChatController.java:L869-L893`）、
  文档列表（`.../controller/RagDocumentController.java:L422-L430`、`:L444-L464`）、
  Collection 列表（`.../controller/RagCollectionController.java:L361-L367`）、
  OpenAI 兼容（`.../openai/OpenAiRequestRetrievalScopeAdapter.java:L85-L99`）。

### 4.3 接口与认证

- 没有租户请求上下文（无 `X-Tenant` / `tenantId`），只有 `Bearer` / `X-API-Key`。
- 受保护路由以 `/api/` 与 `/v1/` 前缀识别
  （`.../security/ApiRoutePolicyRegistry.java:L29-L30`、`:L170-L176`）；
  未配置 root 且 `rag.security.enabled=false` 时认证过滤器直接放行
  （`.../filter/ApiKeyAuthFilter.java:L120-L124`）。
- 认证默认关闭：`RagSecurityProperties.enabled = false`
  （`.../config/RagSecurityProperties.java:L19`）；
  `application.yml` 仅配置 root key（`spring-ai-rag-core/src/main/resources/application.yml:L313-L316`），
  `prod` profile 才置 `true`（`.../application-prod.yml:L7-L11`）。

### 4.4 WebUI

- 单一后端基址与单一凭据，无租户选择器或租户字段
  （`spring-ai-rag-webui/src/api/client.ts:L16-L29`）；全目录 `tenant` 仅出现在
  测试数据 `'tenant:catalog:v1'`（`spring-ai-rag-webui/src/api/collections.test.ts:L76-L88`）。

## 5. 缺口与风险

| # | 缺口 | 证据 | 风险 |
|---|------|------|------|
| 1 | ~~文件子系统全局共享~~ **V71 已修复**：`fs_files` / `fs_import_batches` 增加 `owner_principal_id` 与 `collection_id`；文件树、原始下载与预览对受限 key 按"owner 或允许的 Collection"过滤 | `.../V71__security_boundary_ownership.sql:L14-L31`；`.../repository/FsFileRepository.java:L79-L104`；回归：`scripts/verify-security-upgrade-path.py`（受限 key 看不到 V70 历史文件、root 仍可见） | 遗留无归属行对受限 key 不可见（fail-closed）；需要可见时用 `scripts/backfill-security-ownership.py`（默认 dry-run）显式回填 |
| 2 | ~~评估/反馈为全局视图~~ **V71 已修复**：`rag_user_feedback` / `rag_retrieval_evaluations` 增加 `owner_principal_id`，受限 principal 的历史与统计只读自己的行 | `.../V71__security_boundary_ownership.sql:L34-L43`；`.../service/RetrievalEvaluationServiceImpl.java:L236-L283`；`.../service/UserFeedbackServiceImpl.java:L150` | 遗留无归属行对受限 key 不可见；root/不受限 key 仍保留全局视图，符合既有语义 |
| 3 | 默认无认证：本地 `enabled=false`，未配 root 时整站开放 | `.../config/RagSecurityProperties.java:L19`；`.../filter/ApiKeyAuthFilter.java:L120-L124` | 多实例/多业务部署若漏配 root，等于零隔离 |
| 4 | ACL 粒度只到 Collection，且为 CSV 字段、无数据库兜底 | `.../V24__api_key_allowed_collections.sql:L1-L6`；`.../security/ApiKeyCollectionAccess.java:L111-L124` | 新入口漏接 ACL 时无 RLS/约束兜底；无 ACL 的 NORMAL Key 全域可见 |
| 5 | `collection_key` 全局唯一，无租户命名空间 | `.../entity/RagCollection.java:L16-L21`、`:L31` | 不同租户无法复用同一业务 key，补齐需迁移唯一约束与 by-key 路由 |
| 6 | 无租户层级、成员、租户级配额与计费；仅 per-principal 配额 | `.../V48__managed_api_principals_and_shared_quota.sql:L74-L81`；`docs/TODO-zh-CN.md:L39` | 无法表达"租户下多 Key 共享配额"或租户级账单 |

补充：`collection_id IS NULL` 的遗留文档对 unrestricted 调用方可见（`NONE` scope 不加
过滤），对受限调用方不可见（走 `SELECTED`）。这是兼容行为，但共享实例需注意遗留数据。

方向裁决后的处置：第 2 节的 C 方案已把这些缺口收敛为实施项，但**尚未实现**。
缺口 4、5 在阶段一由 RLS 与 `(tenant_id, collection_key)` 解决；缺口 6 在阶段三补；
缺口 3 在多租户模式必须关闭，单租户模式保持现状，但不得被误当成多租户。

## 6. 文档与代码一致性

主体一致：正式文档把边界描述为"API Key/principal + Collection ACL"，并明确不是完整
租户平台。

- `docs/project-context-zh-CN.md:L464-L506`（"安全与 Collection ACL"）、
  `:L549-L551`（"不是完整的租户身份平台"）。
- `docs/rest-api-zh-CN.md:L320-L327`（`allowedCollectionKeys` 的作用范围）。
- `docs/architecture-zh-CN.md:L148-L151`（Collection/API Key ACL 为共享检索边界）、
  `:L288-L291`（会话按 principal 隔离）。
- `docs/TODO-zh-CN.md:L39`（OAuth/OIDC 与独立租户层级为后续独立规划）。

偏差与易误解：

1. `.../entity/RagCollection.java:L12-L15` 的 Javadoc 写"multi-tenant isolation"，
   实际只是 Collection 级 ACL。
2. `docs/configuration-zh-CN.md:L1425` 的"按 API Key 限流 ... 适合多租户场景"只是限流
   建议，容易被读成租户能力。
3. ~~正式文档尚未提示第 5 节缺口 1、2（文件子系统与评估/反馈面无 ACL 边界）。~~
   2026-11-04 复核：V71 已修复该边界，正式文档按"API Key/principal + Collection ACL
   + 资源归属"描述；本节保留为历史调研记录。
4. `docs/2026-07-21_CODE_AUDIT_REPORT.md:L177`"无 tenantId/ownerId/workspace"是
   V24 之前的历史结论，不代表当前 ACL 状态；该报告已加历史声明。

## 7. 若要真多租户需要补的四层

1. **数据层**：租户维度与复合唯一/索引（或 RLS），迁移 `collection_key` 等全局唯一约束。
2. **认证上下文**：把租户绑定到 principal/token，并在请求上下文中传递。
3. **数据面统一 ACL**：V71 已补齐文件、评估历史与反馈的归属过滤；要避免新入口
   漏接，仍需 RLS 或统一 SQL fragment 兜底。
4. **运营面**：租户级配额/用量、WebUI 租户选择与文档同步。

在此之前，多租户部署的可行兜底是"每租户独立实例/独立数据库"，并强制配置
`RAG_ROOT_API_KEY`、禁用 auth-disabled 路径。

## 8. 现状更新记录

| 日期 | 变更 | 证据 |
|------|------|------|
| 2026-11-04 | 缺口 1、2 关闭：文件、评估历史、反馈增加 owner/collection 归属并按 principal 过滤；遗留无归属行对受限 key fail-closed，可选用回填脚本 | `V71__security_boundary_ownership.sql`、`scripts/verify-security-upgrade-path.py`、`scripts/backfill-security-ownership.py` |
| 2026-11-04 | 技术方向确定：选 C（单仓单 schema、表永远租户化、单租户固定 `default` 的 N=1、多租户强制 RLS；插件只插解析/任务分发/运营模块）；**代码未实施** | [多租户平台技术路线与可插拔方案](MULTI_TENANCY_TECH_ROADMAP.md) |

截至本节记录，仍未实现：缺口 3（默认无认证）、4（ACL 只到 Collection、无 RLS 兜底）、
5（`collection_key` 全局唯一）、6（无租户层级/租户级配额）。第 2 节的方向只是实施输入，
不是完成状态。
