# 多租户化实施规划

> [English](MULTI_TENANCY_IMPLEMENTATION_PLAN.md) | [中文](MULTI_TENANCY_IMPLEMENTATION_PLAN.md)
>
> 状态：**已按对抗式审核最小清单整改并归零重跑连续 `3/3`；待用户/审核者确认批准，禁止开始业务代码实现**。
>
> 语言：**仅中文**。本文是 `docs/drafts/` 活动规划，待规划批准后实施；不提供英文对照版，
> 双语索引指向同一文件。
>
> 调研输入：[多租户现状调研](MULTI_TENANCY_STATUS.md) ·
> [技术路线](MULTI_TENANCY_TECH_ROADMAP.md) ·
> [外部参考经验教训](MULTI_TENANCY_EXTERNAL_LESSONS.md)
>
> 规划标准：本仓 [规划、实施与验收工作流](../delivery-workflow-zh-CN.md) §2–§3；
> 参考 `../seedance-research/docs/PLANNING.md`（冷读者、九项闭合、连续三轮系统检查），
> 但该外部标准只作方法输入，实施契约全部改写为本仓自包含能力。该 `../` 路径按团队统一
> 工作区布局书写；本机实际落点与换算表见 [外部参考经验教训](MULTI_TENANCY_EXTERNAL_LESSONS.md) §2，
> 冷读者按字面路径扑空时以换算表为准。
>
> 基线：`main` @ `b83b5584`（规划初稿）；考古补遗见 `b2e8de96`；Flyway `V1–V74`。
> 本轮只改规划文档，未修改生产代码、配置、迁移或测试。
>
> 进度账本（待创建）：`docs/drafts/MULTI_TENANCY_IMPLEMENTATION_PROGRESS.md`；
> 规划批准并开始首次生产改动前写入初始 checkpoint，届时本文与进度文档开头互相链接。

---

## 0. 结论先行

本规划把多租户化拆成 **4 个阶段、12 个可独立验证工作包**，第一版交付「单租户 N=1 底座 +
多租户严格模式可启用 + 双租户 RLS 证明」，不一次性交付完整 SaaS 运营面。方向沿用技术路线 C：
**单仓、单 schema、表永远租户化、单租户固定 `default`、多租户强制 RLS**。

| 决策 | 冻结结果 |
|---|---|
| 范围 | 阶段一/二必做；阶段三运营面只做租户生命周期与配额最小闭环；阶段四加固门禁必做 |
| 租户来源 | v1 不把 `tenantId` 放 URL；内置 `rag_api_key` / `rag_api_principal` 绑定 `tenant_id`，提供可信 `TenantResolver` SPI |
| 隔离根 | `tenant_id CHAR(26)` 或 `VARCHAR(64)`？**冻结为 `VARCHAR(64)`**，slug 正则 `^[a-z0-9][a-z0-9_-]{0,63}$`，与 BlueCortexEE/技术路线一致；不用随机 UUID 作 GUC 值，避免运维排障与 header/日志契约漂移 |
| 单租户 | `default` 租户行在首次迁移插入；单租户模式**仍写 `tenant_id`，仍走同一 SQL/任务路径**，只关闭 RLS 与 verifier；**SINGLE 模式启动校验所有凭据 `tenant_id='default'`，且生命周期端点拒绝创建非 `default` 租户**，防止「关 RLS + 多租户凭据」形成越权路径 |
| 多租户 | 启动强制 `TenantMode.STRICT`：应用角色 `NOBYPASSRLS`、RLS `ENABLE+FORCE`、GUC 绑定、verifier、平台池隔离 |
| GUC 策略 | 采用 **session 级 `set_config(..., false)` 为主防线 + checkout/归还 `RESET` + Hikari `connection-init-sql: RESET` 三件套**；transaction-local `set_config(..., true)` 仅作事务内加固。理由：本仓 20 个文件含非事务 `jdbcTemplate.update/batchUpdate`，`SET LOCAL` 在自动提交连接上语句结束即失效，会造成 STRICT 下静默读 0 行；见 [技术路线](MULTI_TENANCY_TECH_ROADMAP.md) §4.4 与 [外部经验](MULTI_TENANCY_EXTERNAL_LESSONS.md) §4.9 |
| 后台任务 | 统一 `TenantJobDispatcher`：任务载荷自带 `tenant_id`/actor/audit，执行前一致性断言；无上下文 fail-closed |
| 回调/结果 | 长任务结果与 Provider 回调必须持久化 owner/tenant/receipt，禁止 volatile registry 与任意 task ID 查询 |
| SSE/MCP | 每次投递重查当前 Collection ACL + 校验事件 tenant/collection 与订阅一致；无上下文 fail-closed |
| Embedding 表 | `rag_embeddings`、chunk、同步 run、评估/反馈、文件、日志类表按租户归属；全局容量/单例表逐张裁决 |
| 非目标 | billing/hard-limit、OAuth/OIDC/SSO、完整租户管理台、非 PostgreSQL 后端、真实 LLM 行为变化、租户级彻底删除/导出（阶段三可选子项） |
| 真实 LLM | 本任务不改变模型行为；**不需要真实 LLM 联调**，`mvn test` + 真实 PG 集成测试 + WebUI 构建门禁即足够 |

---

## 1. 问题、成功标准与冷读者复述

### 1.1 问题

当前 spring-ai-rag 只有三层部分隔离：Collection ACL、principal 级资源归属、V71 起
文件/评估/反馈 owner 过滤。没有租户实体、`tenant_id`、租户配置、RLS、租户级配额/生命周期，
因此**不能把同一实例/数据库安全地交给互不信任的多个租户使用**；现在可用的兜底只有每租户独立实例/库。

详见 [现状调研](MULTI_TENANCY_STATUS.md) §1、§3–§7 与
[外部参考经验教训](MULTI_TENANCY_EXTERNAL_LESSONS.md) §3。

### 1.2 成功后可观察行为

1. 单租户存量库从 `V74` 升级到新迁移后，行为零变化：既有 API Key、Collection、文档、检索、Chat、
   WebUI 全部照常；所有业务行 `tenant_id='default'`。
2. 以 `TenantMode.STRICT` 启动并配置 `NOBYPASSRLS` 应用角色后，双租户集成测试证明：
   A 读不到 B、无 GUC 裸连接读 0 行、跨租户写被 `WITH CHECK` 拒绝、错误 GUC 绑定不会「报错」而是被工作项一致性断言挡住。
3. 21 个 `@Scheduled` 文件与 5 个 `@Async` 文件的任务路径都经过 `TenantJobDispatcher`；
   无租户上下文时不执行、告警且不读 0 行静默通过。
4. SSE 广播按 tenant 过滤并在每次投递重查 Collection ACL；撤权后旧订阅不再收到事件（本仓当前无 MCP 实现，未来引入时适用同一规则）。
5. API Key / 受管 principal 携带租户；认证、租户成员/状态、Collection ACL、端点 guard 是独立检查。
6. 新增租户表未进 `TenantTableCatalog`、verifier 关闭、应用角色 `BYPASSRLS`、宽松 policy、
   函数体依赖断裂时，STRICT 模式启动失败。
7. 文档与门禁明确写出「单租户关 RLS ≠ 可以做多租户」。

### 1.3 冷读者复述

陌生开发者读完本文应能复述：**这是一次给所有该隔离的表加 `tenant_id` 与复合约束、
把请求/任务/结果/推送全链路绑定租户、用 PostgreSQL RLS 做数据库兜底、并让单租户保持 N=1 兼容的改造；
第一版不交付运营计费与 SSO；实施按迁移底座 → 请求链路 → 运营最小闭环 → 加固发布推进；
每一步都有红绿测试与启动门禁；真实 LLM 不在本任务验证范围。**

---

## 2. 范围与非目标

### 2.1 受影响层

| 层 | 文件/模块 | 改动性质 |
|---|---|---|
| api | `spring-ai-rag-api/` | 新增 `TenantMode`、`TenantContext` 只读接口、`TenantResolver` SPI、`TenantJobDispatcher` 契约；不放实现 |
| core | `spring-ai-rag-core/` | 实体/Repository/JdbcTemplate/服务/控制器/过滤器/SSE/定时任务/配置/迁移/测试主改动面 |
| starter | `spring-ai-rag-starter/` | `GeneralRagAutoConfiguration` 条件装配 resolver、verifier、平台能力；默认单租户行为不变 |
| documents | `spring-ai-rag-documents/` | 文档处理入口若直接写库，需随 core 统一租户上下文；本规划先按 core 现有文档链路核对，不预设新增模块边界 |
| webui | `spring-ai-rag-webui/` | 第一版仅补「当前租户只读展示 / 多租户模式提示」，不新增租户切换器；`src/api/client.ts` 凭据模型保持 |
| demos | `demos/` | 更新样例配置为单租户默认；不新增多租户 demo 部署拓扑 |
| docs | `docs/` | 配置、安全边界、REST/SSE、测试、部署、项目上下文同步；中英成对 |
| scripts | `scripts/` | 新增 `verify-multi-tenancy-*.sh/.py` 门禁与升级演练脚本；命令入口写入 `developer-reference*` |

### 2.2 明确不做

- 不改 Moonshot AI / Embedding `base-url`、模型、维度、检索算法、RRF、rerank 行为。
- 不做真实 LLM 联调；不新增模型调用次数。
- 不做 OAuth/OIDC/SSO、SCIM、邀请码、席位计费、发票、License 校验。
- 不做租户级彻底删除/导出/擦除的正式交付（仅在阶段三保留最小软删/暂停/恢复）。
- 不支持非 PostgreSQL 后端；不引入 Redis 作为租户上下文存储。
- 不把 `tenantId` 放进 URL path；不放进可被客户端伪造的普通 header；`X-Tenant-Id` 仅开发模式且不能提升角色。
- 不新建第二套 schema、第二套实体、第二套迁移线、第二个 EE 仓。
- 不改变 V71 的 `owner_principal_id` / `collection_id` 归属语义；它们成为租户内二级边界。
- 不删除符号链接、不 `git reset --hard`、不 `git stash`；共享工作区只允许 `git commit` 建保护点。

---

## 3. 现状证据（规划基线）

| # | 事实 | 近距离证据 |
|---|---|---|
| 1 | Flyway 当前 `V1–V74`，69 张表；23 条 `CREATE UNIQUE INDEX` + 11 条列级 inline `UNIQUE`（原「约 56 处」为 `grep UNIQUE` 行数口径，含注释/字符串）；55 个 `JdbcTemplate` 文件、22 个 JPA Repository | `spring-ai-rag-core/src/main/resources/db/migration/`；[AGENTS.md](../../AGENTS.md) §2；[外部经验](MULTI_TENANCY_EXTERNAL_LESSONS.md) §4.9 / §7.3 |
| 2 | 认证过滤器只做 key 校验，无租户字段；`AuthenticatedApiPrincipal` 无 `tenantId` | [`ApiKeyAuthFilter.java`](../../spring-ai-rag-core/src/main/java/com/springairag/core/filter/ApiKeyAuthFilter.java)、[`AuthenticatedApiPrincipal.java`](../../spring-ai-rag-core/src/main/java/com/springairag/core/security/AuthenticatedApiPrincipal.java) |
| 3 | `RagSecurityProperties.enabled=false` 默认无认证；STRICT 模式必须显式拒绝此组合 | [`RagSecurityProperties.java`](../../spring-ai-rag-core/src/main/java/com/springairag/core/properties/RagSecurityProperties.java)、[安全边界](../security-boundary-zh-CN.md) |
| 4 | ACL 在凭证白名单上，范围求交与越权拒绝在 `ApiKeyCollectionAccess` | [`ApiKeyCollectionAccess.java`](../../spring-ai-rag-core/src/main/java/com/springairag/core/security/ApiKeyCollectionAccess.java)、`V24__api_key_allowed_collections.sql` |
| 5 | `collection_key` 全局唯一，没有租户命名空间 | `V27__add_collection_key_expand.sql`、`RagCollection.java` |
| 6 | 受管 principal、共享配额、per-principal 记账已存在；无租户级配额 | `V48__managed_api_principals_and_shared_quota.sql` |
| 7 | V71 给文件、评估、反馈补 owner/collection 归属；遗留无归属行 fail-closed | [`V71__security_boundary_ownership.sql`](../../spring-ai-rag-core/src/main/resources/db/migration/V71__security_boundary_ownership.sql) |
| 8 | `rag_embeddings` 检索直接查表、HNSW + `VECTOR(1024)`；若只给 documents 加 RLS 会退化成昂贵子查询 | [`HybridRetrieverService.java`](../../spring-ai-rag-core/src/main/java/com/springairag/core/retrieval/HybridRetrieverService.java)、`V1__init_rag_schema.sql` |
| 9 | 21 个 `@Scheduled` / 5 个 `@Async` 文件；现存 SSE 控制器与 `SseEmitters`；`@TransactionalEventListener` 0 处、实际 `TransactionSynchronization` 3 处；`src/main` 无 MCP 实现 | `rg -l '@Scheduled' spring-ai-rag-core/src/main/java`；`rg -l 'SseEmitter' ...`；`rg -l 'TransactionSynchronization' ...`；[`SseEmitters.java`](../../spring-ai-rag-core/src/main/java/com/springairag/core/util/SseEmitters.java) |
| 10 | WebUI 单一后端基址 + 单一凭据，无租户选择器 | [`client.ts`](../../spring-ai-rag-webui/src/api/client.ts) |
| 11 | 启动自动装配在 starter，存在 `@ConditionalOnMissingBean` 扩展点 | [`GeneralRagAutoConfiguration.java`](../../spring-ai-rag-starter/src/main/java/com/springairag/starter/GeneralRagAutoConfiguration.java) |
| 12 | 既有并发红线禁止显式悲观锁、`SKIP LOCKED`、advisory lock；使用 CAS/lease/唯一约束 | [AGENTS.md](../../AGENTS.md) §2.15；`scripts/verify-no-pessimistic-locks.sh` |
| 13 | 依赖版本只在根 `pom.xml` `dependencyManagement`；模块 pom 不写版本 | [AGENTS.md](../../AGENTS.md) §2.21；`scripts/verify-dependency-versions.mjs` |
| 14 | 20 个文件含 `jdbcTemplate.update/batchUpdate` 且全文无 `@Transactional`；决定 GUC 不能靠 transaction-local 作为唯一主防线 | `rg -l 'jdbcTemplate\.(batch)?update' spring-ai-rag-core/src/main/java` 后逐个核对 `@Transactional`；[外部经验](MULTI_TENANCY_EXTERNAL_LESSONS.md) §4.9；ROADMAP §4.4 |

外部经验教训的增量约束（认证/租户/ACL 分层、事务内切租户、after-commit 脏连接、回调权威、
结果 owner、SSE 逐次授权、真实 PG 函数依赖演练）见
[外部参考经验教训](MULTI_TENANCY_EXTERNAL_LESSONS.md) §4–§6；本文第 5、7 节已把它们改写为本仓契约，
实施不依赖外部项目背景。

---

## 4. 约束与不变量

1. **同一套表/查询/约束/任务路径**：禁止 `if (multiTenant) SQL else SQL`；单租户只是固定解析到 `default`。
2. **租户是安全边界**：Collection 是租户内知识库，`owner_principal_id` 是租户内二级边界；不允许跨租户共享 Collection。
3. **多租户由数据库兜底**：RLS `ENABLE + FORCE` + 规范 policy + `NOBYPASSRLS` 应用角色 + 启动 verifier；
   手写 `WHERE tenant_id = ?` 不作为唯一防线。
4. **无上下文 fail-closed**：读 0 行、写拒绝、任务不执行、SSE 不推送；不能默认全可见，也不能「读到 0 行就算成功」。
5. **事务/连接租借时序**：`TenantContext` 必须在开启事务/借出连接之前设置；事务内禁止切换租户；
   after-commit / `@Async` / `CallerRuns` 用显式 scope 包装与清理。
6. **平台能力最小化**：跨租户发现只返回最小身份投影，必须经登记的 capability、白名单和审计；
   普通业务包不得依赖平台池实现。
7. **复合唯一与外键**：所有租户私有唯一约束以 `tenant_id` 前导；父子外键含 `tenant_id`；
   `collection_key` 改 `(tenant_id, collection_key)`。
8. **Embedding 冗余租户列**：`rag_embeddings` 与 chunk/同步/评估等高频查询表必须有可直接使用的 `tenant_id`，
   避免 RLS 子查询拖垮 ANN。
9. **迁移不可逆点显式标出**：回填 `default`、重建唯一约束、切角色、删旧列（若发生）都是不可逆或高风险步骤；
   每步有升级演练与回退说明。
10. **并发红线**：CAS、版本号、唯一约束、lease、有界重试；不引入悲观锁 / `SKIP LOCKED` / advisory lock。
11. **成本与模型隔离**：本任务不新增 LLM 调用；确定性 Mock 与真实 PG 集成测试是主证据。
12. **文档/代码同步**：配置→`configuration*`，API→`rest-api*`，SSE→`SSE-PROTOCOL*`，架构→`architecture*`，
    命令→`developer-reference*`，测试→`testing-guide*`，部署→`DEPLOYMENT*`；中英成对。

---

## 5. 推荐方案与目标数据流

### 5.1 架构分层

```text
API Key / 受管 principal / 宿主可信登录态
        |
        v
TenantResolver  ──> TenantContext（tenant_id + actor + audit + mode）
        |
        +--> 请求过滤器 / Controller / Service：同一套 SQL，RLS 兜底
        +--> TenantJobDispatcher：21 scheduled + 5 async + SSE/MCP + after-commit
        +--> PlatformCapabilityRegistry：跨租户 discovery（最小投影、白名单、审计）
        |
        v
App DataSource（NOBYPASSRLS, transaction-local GUC）
Migrator 角色（Flyway）
Platform DataSource（BYPASSRLS，仅登记 capability）
```

### 5.2 数据模型与表归属裁决

新增 `rag_tenant` 主表：`tenant_id VARCHAR(64)` 主键、状态 `ACTIVE/SUSPENDED/DISABLED`、
`created_at`、`updated_at`、可选 `display_name`；首条迁移插入 `default`。

新增机器可读 `TenantTableCatalog`（Java 常量 + 测试可读取清单），三类归属：

| 类别 | 规则 | 示例 | RLS |
|---|---|---|---|
| 租户私有 | 行属于单一租户，必须 `tenant_id NOT NULL` + 复合唯一/外键 + tenant-leading 索引 | `rag_collection`、`rag_documents`、`rag_embeddings`、`rag_chat_history`、文件、评估、反馈、长任务 | 是 |
| 租户内共享 / 平台全局 | 单例、全局容量、全局配置、Flyway 控制、API admin guard | `rag_api_admin_guard`、`rag_long_running_operation_global_capacity`、`rag_slo_configs` | 否，仅平台池访问 |
| 认证凭据 | `rag_api_key`、`rag_api_principal` 需带 `tenant_id` 并回填 `default`；**这两张表为无 RLS 的平台全局表**（认证发生在确定租户之前），app 角色按 key digest 精确查找，加载后由应用层校验凭据绑定租户与解析结果一致 | `V18`/`V48` 系列 | 否；认证查找走 app 角色的 digest 精确查询，**不使用 migrator/superuser 通道**，平台池仅用于已登记 capability |

**逐张裁决是阶段一 WP1 的硬产物**：69 张表清单、类别、理由、是否加列、是否改唯一/外键、是否 RLS、索引策略、
平台池访问点。未进 catalog 的新表让 verifier 启动失败。

### 5.3 请求链路契约

1. `ApiKeyAuthFilter` / principal 加载得到 `AuthenticatedApiPrincipal`（扩展 `tenantId` + `tenantStatus`）。
2. `TenantResolver` 顺序：宿主可信 resolver → 凭据绑定 `tenant_id` → 单租户 `default`；
   **禁止**从不受信 header 推导租户；`X-Tenant-Id` 仅 dev profile 且不能提升角色。
3. `TenantContextFilter` 在借连接/开事务前设置 `TenantContext`；请求结束 `finally` 清理；
   记录 tenant/actor/audit 到 MDC 与审计事件，不记录 raw credential。
4. App DataSource 三件套：checkout 时 `RESET app.current_tenant_id`，随后用
   `SELECT set_config('app.current_tenant_id', ?, false)` 设 **session 级** GUC，归还连接时再次 `RESET`，
   并以 Hikari `connection-init-sql: RESET app.current_tenant_id` 作物理连接创建时的兜底；
   事务内可再用 `set_config(..., true)` 加固，但**不以 transaction-local 为唯一主防线**。
   无上下文不设置，RLS 返回 0 行，同时由服务层断言拒绝无上下文写路径。
   首条租户作用域读查询前做 **GUC 回读断言**（`current_setting('app.current_tenant_id', true)` 回显值
   必须等于 `TenantContext.tenantId`），不一致即 fail-closed 并计数；这把「读 0 行 ≠ 成功」从
   文档声明变成可测护栏（见 §4.4、§7）。
5. Controller/DTO **不新增 `tenantId` 入参**；Moonshot AI 兼容路径与现有 HTTP 契约保持。
6. `ApiKeyCollectionAccess` 继续做 Collection ACL；新增 `TenantMembershipGuard` 校验租户状态与成员关系；
   新增 endpoint guard 覆盖测试，防止新入口漏接。

### 5.4 后台任务、结果与回调

- 所有任务表/事件载荷加 `tenant_id`、actor、audit principal；`TenantJobDispatcher` 单租户只跑 `default`，
  多租户用平台池枚举 `ACTIVE` 租户后逐租户调用；每个租户独立事务与上下文，`finally` 清理。
- 执行前断言「工作项 tenant == 当前 scope tenant」；合法但错误的 GUC 不能靠 RLS 发现串台。
- 长任务结果、导入批次、评估/导出结果持久化 owner/tenant/receipt；任意 task ID 查询不得触发外部 Provider。
- 回调按 operation 校验 receipt、幂等 intent、replay、owner、manual audit principal；response-lost 重试不得二次 create。
- `@Async` / after-commit 回调（**以实际清点为准：当前 3 处 `TransactionSynchronization`，无 `@TransactionalEventListener`**）/
  `CallerRuns` 各自有显式 bootstrap—assert—restore—clear 测试。

### 5.5 SSE（MCP 为未来适用范围，非当前实现面）

- 事件对象带 `tenant_id`；广播按 tenant 过滤。
- 每次投递前重查当前 Collection ACL 与事件 tenant/collection 匹配；撤权后旧订阅停止推送。
- 无 `TenantContext` 时 fail-closed；多副本各自重查，不依赖建连时的旧授权结果。
- **本仓 `src/main` 当前无 MCP 实现**（`rg -i '\bmcp\b' src/main/java` 零命中），故本版不设
  「MCP 无上下文 fail-closed」验收项；若未来引入 MCP 投递面，按本节同一规则补齐端点 guard 与逐次授权测试。

### 5.6 兼容与失败语义

| 场景 | 行为 |
|---|---|
| 单租户默认模式 | 无新配置要求；`rag.tenant.mode=SINGLE`；resolver 固定 `default`；RLS 可关但表/列/约束/任务路径完整；**启动校验 `rag_api_key`/`rag_api_principal` 的 `tenant_id` 全为 `default`，否则拒绝启动**；**SINGLE 下租户生命周期端点拒绝创建非 `default` 租户**（不是文档声明，是 WP3/WP9 的机制） |
| 存量库升级 | Flyway 顺延新增迁移，回填 `default`，不改写已执行迁移；升级前后 API 行为零变化 |
| 切 STRICT | 必须配置 app `NOBYPASSRLS`、migrator、platform 三角色与 `rag.security.enabled=true`；verifier 失败拒绝启动 |
| 无 GUC 裸连接 | RLS 读 0 行；应用写路径由上下文断言拒绝，不产生「静默成功」；租户作用域读路径由 §5.3.4 GUC 回读断言区分「合法空结果」与「GUC 绑定失效」 |
| 租户 SUSPENDED/DISABLED | 请求 403；后台任务跳过该租户并记审计；不删除数据 |
| 平台池误用 | capability 白名单失败 + 审计 + 启动/静态边界测试失败 |
| 外部 Provider 回调丢失 | 按 receipt 重试同一 operation；不得因 response-lost 二次 create |
| HNSW + RLS 性能不达标 | 阻断发布；允许 iterative scan / over-fetch / 分区评估，但不在实施中临时改默认检索语义 |

### 5.7 为何不采用备选方案

| 备选 | 否决理由 |
|---|---|
| 另开 EE 仓 / 双 schema 插件 | 两条迁移线、持续漂移、单租户无法验证多租户约束；[技术路线](MULTI_TENANCY_TECH_ROADMAP.md) §3 已有代价表 |
| 只在应用层写 `WHERE tenant_id` | 55 个 JdbcTemplate + 22 个 Repository，新入口漏写即越权；V71 已证明新入口漏接 ACL 是真实问题 |
| 每租户独立 schema / DB | 运维与连接爆炸，违背单仓 starter 默认行为不变；仅作为当前未支持多租户时的临时兜底 |
| URL path 放 `tenantId` | 改动全站路由、Moonshot AI 兼容路径与 WebUI；客户端可猜租户；与本仓认证模型不匹配 |
| 仅用 session 级 GUC、缺少 checkout/归还 RESET 与 init-sql | seedance 红测证明连接池复用会残留上家 GUC；本规划用「session 级 set + checkout/归还 RESET + init-sql」三件套，transaction-local 仅作事务内加固 |
| transaction-local `SET LOCAL` 作为唯一主防线 | 本仓 20 个文件存在非事务 `jdbcTemplate.update/batchUpdate`，自动提交连接上 `set_config(..., true)` 随语句结束即失效，STRICT 下会静默读 0 行；ROADMAP §4.4 与 EE 教训已否决；本规划仅把它作为事务内加固 |
| 第一版即交付完整 SaaS 运营面 | 会把 69 表迁移与 SSO/billing/删除导出绑成一个不可验收批次；拆到阶段三/四 |

---

## 6. 实施步骤（文件级顺序，12 个工作包）

> 每个工作包必须「先红测 → 实现 → 绿测 → 更新进度账本 → 再进入下一包」。
> 工作包内不得修改生产配置默认值；`rag.tenant.mode` 默认 `SINGLE`。

### 阶段一：租户化底座（WP1–WP4）

**WP1 — 表归属清单与 catalog 骨架**
- 产物：`docs/drafts/MULTI_TENANCY_TABLE_CATALOG.md`（69 张表逐张裁决） +
  `spring-ai-rag-core/src/main/java/com/springairag/core/tenant/TenantTableCatalog.java` +
  catalog 完整性单测。
- 锚点：`spring-ai-rag-core/src/main/resources/db/migration/V*.sql`、现有实体与 Repository。
- 退出：清单覆盖 69 张表；每张有类别/理由/约束/RLS/索引/平台池访问点；单测拒绝「新增表未登记」样例。

**WP2 — `rag_tenant` 主表、`tenant_id` 回填、复合约束**
- 新增 `V75__tenant_base.sql`…`V7x`（按实际拆分，不改写 V1–V74）：建 `rag_tenant`、插 `default`、
  租户私有表加 `tenant_id NOT NULL DEFAULT 'default'`、复合唯一、复合外键、tenant-leading 索引、
  `collection_key` 改 `(tenant_id, collection_key)`、embedding 表冗余列。
- 大表专项：`rag_embeddings`（`VECTOR(1024)` + HNSW）加列/回填/tenant-leading 索引不得全部塞进
  单条事务迁移；`CREATE INDEX CONCURRENTLY` 不能在事务内执行，需 Flyway 显式非事务迁移配置，
  回填按批次进行，并把**锁窗口、每批行数、失败续跑**写进升级演练与回退说明。
- 产物：迁移脚本 + 全新库/存量库升级演练脚本 + 迁移内建终态断言（双列/残留旧唯一/函数依赖）+
  大表分批回填/并发索引脚本与锁窗口记录。
- 退出：真实 PostgreSQL 全新库执行成功；`V74` 存量库升级成功；旧 API 单测全绿；数据行数守恒；
  大表迁移在演练库上记录到实际锁窗口且可中断续跑。

**WP3 — RLS、三角色与启动 verifier**
- 新增 `V7x__tenant_rls.sql`：租户表 `ENABLE + FORCE ROW LEVEL SECURITY` + 规范 policy
  `USING (tenant_id = current_setting('app.current_tenant_id', true)) WITH CHECK (...)`。
- 新增 `TenantRlsVerifier`：角色非 superuser/非 `BYPASSRLS`、catalog 与 `pg_tables` 双向一致、
  policy 表达式语义校验（拒绝 `USING(true)`）、无 GUC 裸连接读 0 行、函数逐个可调用（GUC 有值）。
- 新增 `scripts/verify-multi-tenancy-rls.sh` 与启动负向测试。
- 新增 `TenantModeGuard`：SINGLE 模式启动时校验所有凭据 `tenant_id='default'`，否则拒绝启动；
  STRICT 模式校验三角色与 verifier 全项。
- 退出：STRICT 模式 verifier 关闭/宽松 policy/`BYPASSRLS`/漏 RLS 表/**宽松 platform 角色**五种负向用例启动失败；
  SINGLE 模式不要求三角色但 schema 完整，且「存在非 default 凭据」负向用例拒绝启动。

**WP4 — 双租户 RLS 集成测试与 HNSW 基准**
- 新增真实 PostgreSQL Testcontainers 集成测试：A 读不到 B、无 GUC 0 行、跨租户写拒绝、
  错误 GUC + 工作项串台被 dispatcher 断言挡住、连接池复用无残留。
- 新增 HNSW + RLS 召回/延迟基准；门槛写入 `docs/quality-defaults-zh-CN.md`。
- 退出：集成测试绿；基准结果记录；不达标则阻断后续阶段并回到 WP2/WP3 调整索引/查询。

### 阶段二：请求链路与后台任务（WP5–WP8）

**WP5 — principal / resolver / context / datasource**
- 扩展 `AuthenticatedApiPrincipal` 与凭据表加载逻辑；新增 `TenantResolver`、
  `SingleTenantResolver`、`PrincipalTenantResolver`、`TenantContext`、`TenantContextFilter`。
- 新增 app DataSource 的 **session 级 GUC 三件套**（checkout `RESET` → `set_config(..., false)` →
  归还 `RESET` → `connection-init-sql: RESET`），transaction-local 仅作事务内加固；
  首条租户作用域读查询前做 GUC 回读断言（回显值 == `TenantContext.tenantId`）；
  starter `GeneralRagAutoConfiguration` 条件装配。
- 退出：过滤器/数据源单测覆盖「先设上下文再借连接」「事务内禁止切租户」「checkout/归还 reset」
  「非事务自动提交路径 GUC 仍有效」「GUC 回读不一致即拒绝」「连接池复用无上家 GUC」；
  新增静态门禁清单核对 20 个非事务 JDBC 文件确实在租户作用域内正确绑定 GUC；
  现有认证与 Collection ACL 回归全绿。

**WP6 — 端点 guard、平台 capability、审计**
- `TenantMembershipGuard` + `PlatformCapabilityRegistry` + `PlatformOpsService` 等价物（本仓命名）：
  跨租户 discovery 最小投影、命名查询枚举、白名单、审计；ArchUnit/静态边界测试禁止业务包依赖平台实现。
- endpoint guard 覆盖测试：所有写/读端点必须声明 tenant + ACL 策略；新端点漏声明即红。
- 退出：静态覆盖测试绿；平台池误用负向测试绿。

**WP7 — 21 scheduled + 5 async + after-commit 收敛**
- 新增 `TenantJobDispatcher`；逐个改造 `@Scheduled`、`@Async`、after-commit 回调
  （**当前实际 3 处 `TransactionSynchronization`，无 `@TransactionalEventListener`**）、SSE 生产端；
  任务载荷加 tenant/actor/audit。**MCP 不在本仓当前实现面，不列为打勾项**。
- 退出：任务路径清单全部打勾（以实际清点为准，不写幽灵条目）；无上下文不执行并告警；
  CallerRuns 同线程 scope 清理测试绿；并发红线脚本 `scripts/verify-no-pessimistic-locks.sh` 通过。

**WP8 — 结果 owner、回调权威、SSE 逐次授权**
- 长任务/导入/评估结果表补 owner/tenant/receipt；任意 task ID 查询负向测试；
  Provider 回调 receipt/intent/replay/manual audit 测试；SSE 每次投递重查 ACL + 多副本模拟。
- 退出：反模式红测先失败、推荐实现绿测通过；SSE 撤权后立即停止推送；无外部 Provider 真实调用。

### 阶段三：运营最小闭环（WP9–WP10）

**WP9 — 租户生命周期与状态**
- `TenantLifecycleService`：创建/暂停/恢复/禁用、软删标记；管理端点仅平台 capability 可调；
  `rag_api_key`/`rag_api_principal` 绑定租户；WebUI 只读展示当前租户与模式提示。
  **SINGLE 模式下创建非 `default` 租户返回冲突并记审计**（与 WP3 `TenantModeGuard` 同一契约）；
  **租户行 + 初始 principal 单事务原子**，避免 seedance B4 「幽灵租户」。
- 退出：生命周期 403/冲突/幂等/审计测试 + SINGLE 拒绝非 default 创建负向测试 + 创建原子性测试；
  WebUI TypeScript + 构建 + 相关 Vitest/Mock Playwright 通过；不实现租户切换器与邀请码。

**WP10 — 租户级配额与用量**
- 在既有 per-principal quota 上增加 tenant 维度聚合与硬阈值（**不是 billing/hard-limit 结算**）；
  平台全局容量表逐张裁决结果落地；用量 API 增加 tenant 聚合且不暴露跨租户数据。
- 退出：配额冲突/并发/超限/恢复测试；`docs/configuration*`、`rest-api*` 同步。

### 阶段四：加固与发布（WP11–WP12）

**WP11 — 红队、升级演练、备份恢复**
- 红队矩阵：漏写谓词、平台池误用、header 伪造、异步丢上下文、函数体依赖断裂、
  policy 宽松、superuser 启动、verifier 关闭、SSE 撤权、任意 task ID。
- 升级演练：`V74` 存量库 → 新迁移；单租户→STRICT 切换；备份恢复与回滚说明。
- 退出：所有负向用例被门禁拒绝；升级演练脚本与证据落盘。

**WP12 — 文档、门禁、发布**
- 同步 `configuration*`、`rest-api*`、`SSE-PROTOCOL*`、`architecture*`、`project-context*`、
  `security-boundary*`、`testing-guide*`、`DEPLOYMENT*`、`developer-reference*`、`quality-defaults*`、
  `release-checklist*` 中英成对；新增 `verify-multi-tenancy-*.sh` 到门禁总入口；
  明确「单租户关 RLS ≠ 多租户」。
- 退出：`./scripts/verify-project-docs.sh`、`mvn clean compile test-compile`、
  相关 PG/HTTP 集成测试、WebUI 类型检查/生产构建/核心 Mock Playwright、`scripts/verify-release.sh`
  按发布清单通过；规划检查 `3/3` 留痕。

---

## 7. 一次性设计验收矩阵

| 维度 | 必跑证据 | 适用 | 先红后绿 | 阻断条件 |
|---|---|---|---|---|
| 迁移 | 全新库 + `V74` 存量库 Flyway 升级，行数/唯一约束/policy/函数终态守恒；**embedding 大表 CONCURRENTLY / 分批回填 / 锁窗口记录** | 后端 | 是（双列表、残留旧唯一、函数断裂） | 任一失败阻断 |
| RLS | A/B 双租户、无 GUC 0 行、跨租户写拒绝、错误 GUC 串台、连接池复用 | 后端/PG | 是 | 任一失败阻断 |
| 启动 verifier | superuser、`BYPASSRLS`、verifier 关闭、漏 RLS 表、`USING(true)`、函数静默失效、**宽松 platform 角色**、**SINGLE 下非 default 凭据** | 后端 | 是（负向启动） | 任一未拒绝阻断 |
| 请求链路 | 认证/principal/resolver/context/datasource 单测 + 现有 HTTP 集成回归 + **非事务自动提交路径 GUC 有效** + **GUC 回读断言** | 后端/HTTP | 是 | 事务内切租户、checkout 残留、非事务路径 GUC 失效、回读不一致阻断 |
| Endpoint guard | 全端点声明 tenant + ACL 策略；新增漏声明端点红测 | 后端 | 是 | 漏声明阻断 |
| 后台任务 | 21 scheduled + 5 async + **3 处 TransactionSynchronization**（无 `@TransactionalEventListener`）+ CallerRuns 清单与上下文清理 | 后端 | 是 | 无上下文静默执行阻断 |
| 结果/回调 | owner/tenant/receipt、任意 task ID、replay、response-lost、manual audit | 后端 | 是 | 任意 ID 触发外部查询阻断 |
| SSE（MCP 为未来适用范围） | 撤权后停止推送、跨租户事件拒绝、每次投递重查、多副本；**当前 `src/main` 无 MCP 实现，不设无法验证的 MCP 验收项** | 后端/SSE | 是 | 旧订阅继续收事件阻断 |
| SINGLE 模式守卫 | 启动时所有凭据 `tenant_id='default'` 校验 + SINGLE 下拒绝创建非 default 租户 + 非 default 凭据负向启动用例 | 后端 | 是（负向） | 存在非 default 凭据仍可启动、或 SINGLE 下能创建 B 租户即阻断 |
| 租户生命周期 / 原子创建（WP9） | 403/冲突/幂等/审计 + 创建原子性（租户行 + 初始 principal 同事务）+ SINGLE 拒绝非 default | 后端/HTTP | 是 | 幽灵租户或越权创建阻断 |
| 租户级配额（WP10） | 配额冲突/并发 CAS/超限/恢复 + `configuration*` / `rest-api*` 文档同步 | 后端/HTTP | 是 | 并发超限或跨租户用量可见阻断 |
| 检索质量 | HNSW + RLS 召回/延迟基准 | 后端/PG | 否（基准） | 不达标阻断发布 |
| 前端 | TypeScript、相关 Vitest、生产构建、核心 Mock Playwright（DOM/ARIA/请求响应） | WebUI | 是（租户只读展示/模式提示） | 构建或断言失败阻断 |
| 联合运行时 | `postgresql` profile 启动 + 健康检查 + 单租户存量 API 冒烟 | 联合 | 否 | 启动失败阻断 |
| 真实 LLM | **N/A**：任务不改模型/embedding/检索行为，不新增调用 | — | — | 不作为门禁 |
| 文档/门禁 | `verify-project-docs.sh`、`verify-release.sh`、`verify-no-pessimistic-locks.sh`、`verify-dependency-versions.mjs` | 全仓 | 否 | 门禁红阻断 |
| 真实依赖 | PostgreSQL 16 + pgvector，使用可处置测试库；Docker/OrbStack 不可用时报告环境缺口，不用 H2 冒充 | 后端 | 否 | 缺 PG 阻断 |

命令基线（实施时按 [developer-reference-zh-CN.md](../developer-reference-zh-CN.md) 复核）：

```bash
mvn clean compile test-compile
./scripts/verify-project-docs.sh
./scripts/verify-no-pessimistic-locks.sh
node scripts/verify-dependency-versions.mjs
# 新增：./scripts/verify-multi-tenancy-rls.sh / verify-multi-tenancy-upgrade.sh
# WebUI：npm run typecheck（实际脚本名）/ test:run / build / test:e2e:mock
```

证据优先级：真实 PostgreSQL 集成/迁移结果 > HTTP/SSE 集成 > 单测 > 静态扫描 > 文档门禁。
Mock Playwright 不替代真实 PG 集成；文档门禁不替代代码测试。

---

## 8. 发布、兼容窗口与恢复

1. **发布顺序**：先合并单租户兼容底座（WP1–WP5，默认 `SINGLE`）→ 观察一个发布周期 →
   再开放 `STRICT` 模式给隔离环境 → 双租户/红队通过后才允许生产多租户声明。
2. **兼容窗口**：`SINGLE` 模式无配置变化；`rag.tenant.mode` 新增配置默认 `SINGLE`；
   `X-Tenant-Id` 仅 dev profile；API 无新增 path/query 参数；Moonshot AI 兼容路径不变。
3. **不可逆点**：`tenant_id` 回填、全局唯一改复合唯一、外键重建、角色切换、旧列删除（本规划第一版**不删旧列**，
   遗留无归属行沿用 V71 fail-closed 语义）。每个不可逆点必须有升级演练与备份点。
4. **失败停止**：verifier 失败拒绝启动；迁移断言失败回滚事务；HNSW 基准不达标停止发布；
   红队用例未被拒绝视为阻断。
5. **回退**：`SINGLE` 回退只需把模式改回 `SINGLE` 并使用原应用角色；**不回滚已执行 Flyway 迁移**，
   不删除 `tenant_id` 列/约束；需要回到纯单库行为时按备份恢复流程重建库。
6. **可观测**：MDC/审计事件带 tenant/actor/audit；新增「无租户任务」「verifier 拒绝」「平台 capability 拒绝」
   「SSE 撤权拒绝」「跨租户写拒绝」计数；不记录 raw credential、密钥或租户私有正文。
7. **密钥**：三角色凭据只放 `.env`/部署 secret；文档与脚本不写任何真实 key；
   `scripts/verify-project-docs.sh` 的 added-line secret scan 必过。

---

## 9. 遗留事项（不阻断本规划）

| 项 | 推荐默认 | 影响 | 重新评估触发条件 |
|---|---|---|---|
| 完整租户管理台 / 租户切换器 | 第一版不做，WebUI 只读展示 | 运营需 API 或宿主系统管理租户 | 多租户生产试点需要自助运营 |
| 彻底删除/导出/擦除 | 只做软删/暂停/恢复 | 不能承诺合规级数据清除 | 合规或客户合同要求 |
| SSO / OIDC / SCIM / 邀请码 | 不做，宿主 `TenantResolver` 接入 | 身份 federation 由宿主负责 | 需要本仓内建身份体系 |
| 计费 / token hard limit | 不做，只做租户级配额聚合 | 不能当账单源 | 产品进入计费路线 |
| 非 PostgreSQL 后端 | 不支持 STRICT | 其他数据库只能继续单租户 | 明确新增数据库后端 |
| `bos_current_tenant_key()` 类 legacy 回退 | 本仓不引入 legacy tenant key；GUC 只认 `tenant_id` | 无历史兼容包袱 | 如未来引入外部租户 key 映射，需另立安全评审 |
| 每租户独立 collection/分区 | 第一版用共享 HNSW + RLS + 基准 | 租户极多时可能召回/吞吐下降 | HNSW 基准不达标或租户规模超过容量预算 |

---

## 10. 进度记录方式与完成定义

- 本规划采用**独立进度文档** `docs/drafts/MULTI_TENANCY_IMPLEMENTATION_PROGRESS.md`，
  在开始第一次业务代码/迁移/配置修改之前写入初始 checkpoint；本文与进度文档开头互相链接。
- 进度账本记录：基线 commit、当前唯一 `in_progress` 工作包、已完成切片、已运行/未运行验证、
  检查计数、阻塞、恢复入口、临时数据库/端口/后台任务。
- 任务列表始终最多一个 `in_progress`；关键节点先落盘再进入下一步；
  未运行或失败验证不得写成完成。
- 规划变化影响核心方案、范围、迁移、权限/并发边界、失败语义或验收标准时，检查计数归零并重跑 `3/3`。
- **完成定义**：12 个工作包全部按退出条件完成；验收矩阵全绿；中英文档与门禁同步；
  规划检查 `3/3` 且实施后实现检查 `3/3`；Git 交付按 [delivery-workflow-zh-CN.md](../delivery-workflow-zh-CN.md) §8
  合回并推送 `main`。

---

## 11. 规划检查范围与计数

冻结基线：本文 v1（`docs/drafts/MULTI_TENANCY_IMPLEMENTATION_PLAN.md`，2026-11-13）。
实质性问题门槛：会导致方案不可实施、形成直接越权路径、产生迁移/数据安全风险、
使核心验收无法验证，或让本仓文档门禁失败的问题；纯行号/措辞/排版不归零。

### 11.1 检查与整改日志

| 轮次 | 范围 | 发现 | 处理 | 计数 |
|---|---|---|---|---|
| R1 初稿 | 冷读者、九项闭合、范围/非目标、默认决策、遗留项 | WebUI 类型检查写成 `npm run type-check`，与实际脚本 `typecheck` 不符 | 改为 `npm run typecheck` | 归零 |
| R2 初稿 | 代码/数据/API/安全/并发/兼容可行性，锚点存在性 | 无实质问题 | 复核链接、迁移范围、表/任务/DAO 计数与硬规则 | 维持 |
| R3 初稿 | 实施顺序、验收矩阵、发布/回滚/恢复、交付风险 | 文件头把「批准后才创建」的进度账本写成 Markdown 链接，门禁判定链接目标缺失 | 改为行内代码文本 | 归零 |
| 对抗式审核（外部） | 独立复核 9 项：基线/迁移/RLS/请求链路/后台任务/性能/完整性/门禁/红队 | **REQUEST_CHANGES**：FAIL×2（① transaction-local 作为 GUC 主防线与本仓 20 个非事务 JDBC 文件矛盾，STRICT 下静默读 0 行；② SINGLE 模式 + 多租户凭据 = 跨租户越权路径无守卫）＋ CONCERN×4（凭据表 migrator 表述、读路径无「0 行≠成功」机制、MCP 与 `@TransactionalEventListener` 幽灵范围、§7 矩阵缺 WP9/WP10 + WP2 缺大表迁移机制 + seedance 路径扑空） | 按最小清单全部整改：§0/§5.3.4/§5.7/WP5 改为 session 级三件套 + transaction-local 仅事务内加固；§0/§5.6/WP3/WP9 增加 SINGLE 守卫（凭据全 default 校验 + 拒绝创建非 default）；§5.2 凭据表钉死无 RLS 平台表 + app 角色 digest 查找；§5.3.4 增加 GUC 回读断言；§5.4/§5.5/WP7/§7 MCP 与 after-commit 按实际清点降级（0 MCP / 3 处 TransactionSynchronization）；§7 补 SINGLE 守卫、WP9、WP10 三行并扩 verifier 负向集；WP2 增补 embedding 大表 CONCURRENTLY/分批回填/锁窗口；§3 修订唯一约束口径并补事实行 14；头部补 seedance 路径换算指针 | 归零 |

### 11.2 整改后连续三轮（归零重跑；期间未再修改规划正文）

| 轮次 | 固定范围 | 时间 | 发现 | 处理 | 结果 |
|---|---|---|---|---|---|
| R1 | 冷读者、九项闭合、范围/非目标、默认决策、遗留项 | 2026-11-13 | 无实质问题 | §1.3 冷读者复述、§2 范围/非目标、§0 冻结决策（含 GUC 三件套与 SINGLE 守卫）、§9 遗留默认与触发条件均闭合；无「实现时再看看」核心空洞 | `1/3` |
| R2 | 代码/数据/API/安全/并发/兼容可行性，锚点存在性 | 2026-11-13 | 无实质问题 | 重新实测：非事务 JDBC 文件 20、`TransactionSynchronization` 3、`@TransactionalEventListener` 0、MCP 0、`CREATE UNIQUE INDEX` 23、UNIQUE 行 56，与 §3 表述一致；所有相对链接可达；ROADMAP §4.4 / EXTERNAL_LESSONS §4.9 支撑 GUC 决策；门禁 17 项全绿 | `2/3` |
| R3 | 实施顺序、验收矩阵、发布/回滚/恢复、交付风险 | 2026-11-13 | 无实质问题 | WP1–WP12 依赖闭环（WP2 大表专项 → WP3 守卫 → WP5 三件套 → WP9 原子创建 → WP11 红队）；§7 矩阵覆盖全部 WP 退出条件（含新增 SINGLE/生命周期/配额三行）；§8 发布顺序、不可逆点、回退可执行；与 delivery-workflow §8 Git 交付顺序一致 | `3/3` |

最终结论：整改后连续三轮完整检查未发现实质问题，规划达到可实施状态；
但仍须获得用户明确批准（或外部审核者复核通过），方可按 §10 创建进度账本并进入业务实现。
