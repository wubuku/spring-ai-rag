# 多租户平台技术路线与可插拔方案

> 状态：**技术方向已定，代码尚未实施**。本文是设计输入与决策记录，不是"已实现"声明。
> 当前真实能力仍以 [MULTI_TENANCY_STATUS.md](MULTI_TENANCY_STATUS.md) 为准。
>
> **补充考古（2026-11-13）**：[多租户外部参考经验教训](MULTI_TENANCY_EXTERNAL_LESSONS.md)
> 补充考察 `../seedance-research/`（可运行多租户 PoC）与
> `../AI-Native-Business-Operating-System/`（真实 PG 迁移演练）；本路线方向不变，
> 但第 8 / 11 节实施与验收前应吸收该文第 6 节「需补强 / 新增盲点」清单。
>
> 调研日期：2026-11-04。调研对象：
> `docs/drafts/MULTI_TENANCY_STATUS.md`、`docs/security-boundary-zh-CN.md`、
> `docs/project-context-zh-CN.md`、`docs/architecture-zh-CN.md`、
> `docs/multi-instance-semantics-zh-CN.md`，以及 Flyway V24 / V48 / V71。
> 外部考古对象：`../Blueforce-Tech-Inc/BlueCortexEE/` 与 `../Blueforce-Tech-Inc/BlueCortexCE/`
> （相对路径基准与本机落点换算表见[补充考古](MULTI_TENANCY_EXTERNAL_LESSONS.md)第 2 节；
> 本文所有外部路径均为**行内代码相对路径文本**，不含个人主目录绝对路径）。
>
> 外部仓只读：只使用 `git log` / `git remote -v`、文件读取与文本检索，未构建、未启动、
> 未写文件。EE/CE 的隔离行为来自代码、迁移与文档，**没有实际运行它们的测试**。

## 1. 结论先行

**选 C：就在本仓改造，单仓、单 schema、表永远租户化；插件只插在租户解析、任务分发和运营模块，不做"两套 schema 的插件"。**

一句话说清楚：

- **表结构永远租户化**：该隔离的表都有 `tenant_id`，唯一约束、外键、索引、RLS 都按租户设计，只有一条迁移线。
- **单租户 = N=1 的退化模式**：所有请求固定解析到 `default` 租户，代码、表、查询与多租户完全相同。
- **多租户 = 同一套代码的严格模式**：从 API Key 或宿主认证解析真实租户，强制 RLS、独立应用角色、启动校验。
- **插件只插三个地方**：`TenantResolver`（租户从哪来）、`TenantJobDispatcher`（后台任务怎么按租户跑）、可选的租户运营模块（生命周期、配额、计费、导出）。**不要让插件决定"有没有 `tenant_id` 列"。**

不推荐 A（另开 EE 仓）：EE/CE 已经用真实工程证明了双仓移植和单向同步的持续成本，见第 3 节。

不推荐 B 的原始形态（单租户无 `tenant_id` 列 + 多租户有列，或仓内两套 schema）：那不是插件，是"仓内分叉"，成本接近 A，而且单租户模式天然测不出多租户的约束和越权缺陷。

如果商业上必须闭源企业版，选 **C2**：核心仓保持开源、单 schema、单迁移线；企业能力做成私有 Maven 模块，依赖已发布的 core 工件，**仍然不要 fork 整个仓库**。

**不要把草案读成已实现。** 本文所有"必须""应""建议"都是实施前的设计约束；当前代码仍然没有租户实体、`tenant_id`、RLS 或租户管理面。

## 2. 本仓起点与不可动摇的不变量

### 2.1 当前能力

当前只有三层"部分隔离"：

1. Collection 级 ACL；
2. principal 级资源归属；
3. V71 起的文件、评估历史、反馈归属过滤（`owner_principal_id`、`collection_id`）。

证据：`docs/drafts/MULTI_TENANCY_STATUS.md:L9-L24`、`docs/security-boundary-zh-CN.md:L23-L35`。

V71 是**租户内的二级边界**，不是租户隔离：它解决"受限 principal 看不到别人的行"，但没有解决"两个租户的数据如何在数据库层互不可见"。多租户方案必须保留 V71 的归属语义，并在其外增加租户这一层。

### 2.2 规模与耦合面

本次只读统计：

- **69 张表**、**73 个迁移**、约 **56 处唯一约束/唯一索引语句**；
- **55 个文件**直接用 `JdbcTemplate`，**22 个 JPA Repository**；
- **21 个文件带 `@Scheduled`**，**5 个文件带 `@Async`**；
- **1,106 个测试文件**；**42 份中文文档、52 份英文文档**。

具体障碍：

- `collection_key` 全局唯一，没有租户命名空间：
  `spring-ai-rag-core/src/main/resources/db/migration/V27__add_collection_key_expand.sql:L49-L50`。
- `rag_embeddings` 没有 `collection_id`，检索直接查它；若 RLS 只加在 `rag_documents` 上，
  就需要昂贵的子查询，所以 `tenant_id` 必须冗余到 embedding 表：
  `spring-ai-rag-core/src/main/resources/db/migration/V1__init_rag_schema.sql:L70-L82`、
  `spring-ai-rag-core/src/main/java/com/springairag/core/retrieval/HybridRetrieverService.java:L1295-L1301`。
- 认证 principal 里没有租户：
  `spring-ai-rag-core/src/main/java/com/springairag/core/security/AuthenticatedApiPrincipal.java:L12-L22`；
  过滤器只做 key 校验：
  `spring-ai-rag-core/src/main/java/com/springairag/core/filter/ApiKeyAuthFilter.java:L44-L59`。
- 存在全局单例/容量表，必须逐张裁决"平台全局还是租户内共享"：
  `rag_api_admin_guard` 单例（`V48__managed_api_principals_and_shared_quota.sql:L88-L92`）、
  `rag_long_running_operation_global_capacity` 单例（`V67__durable_operations_and_embedding_profiles.sql:L122-L126`）。
- 配额目前是 per-principal，不是 per-tenant：
  `V48__managed_api_principals_and_shared_quota.sql:L74-L81`。
- WebUI 是单一后端基址 + 单一凭据，没有租户选择器：
  `spring-ai-rag-webui/src/api/client.ts:L16-L29`。

**真正的工程量不是"加 `tenant_id`"，而是 69 张表的归属裁决、复合唯一/外键、后台任务租户传播、租户级删除与导出。**

### 2.3 不变量

以下四条一旦破坏，多租户方案就会退化成不可验证的补丁：

1. **同事**：单租户与多租户走同一套表、同一套查询、同一套约束。禁止 `if (multiTenant) SQL else SQL`。
2. **租户是安全边界，Collection 只是租户内的知识库**。不允许跨租户共享 Collection。
3. **多租户模式必须由数据库兜底**：RLS + 独立 `NOBYPASSRLS` 应用角色 + 启动校验。
4. **无租户上下文必须 fail-closed**：读 0 行、写拒绝、任务不执行，而不是"默认全可见"。

## 3. 路线对比与代价

### 3.1 三条路线

| 路线 | 形态 | 结论 |
|---|---|---|
| **A. 另开 EE 仓** | CE 仓保持单租户，EE 仓从 CE 移植后改写 | 不推荐，除非业务强制"两个独立产品、独立发布" |
| **B. 本仓可插拔，但插件换 schema** | 单租户插件不带 `tenant_id`，多租户插件带列/带 RLS | 不推荐，本质是仓内分叉 |
| **C. 本仓单 schema + 可插拔解析/运营** | 表永远租户化；单租户固定 `default`；多租户严格 RLS | **推荐** |
| **C2. 核心开源 + 私有企业模块** | 同一套 core/schema；企业能力独立私有模块 | 商业闭源要求下的推荐替代 |

### 3.2 代价对比

| 维度 | A 双仓 | B 原始"双 schema 插件" | C 单仓单 schema |
|---|---|---|---|
| 代码重复 | 高。EE 后端 **50,209 行 Java**，CE **27,351 行**；EE 租户包 **42 个文件、6,625 行** | 高。两套实体/查询/迁移分支，接近仓内 fork | 低。一套实体、一套查询；新增租户上下文与迁移 |
| 分支漂移 | 持续。单向 `ce-sync.sh` 移植；CE 与 EE 都占用 `V21+` | 持续。两条迁移线在同一版本号内并行 | 无跨仓漂移；一条迁移线 |
| 发布/版本 | 两套 CI、两套产物；两仓当前都叫 **0.1.0-beta**，无法从版本识别能力 | 一套版本、两套 schema 产物，配置矩阵翻倍 | 一套版本、一套产物；多租户是运行时模式 |
| CI/测试 | EE **101 个测试文件**，CE **83 个**；EE 还要 PG 双角色 + RLS 集成测试，矩阵约 2× | 需要两套 DB fixture 与迁移回归，约 2× | 一套矩阵；增加一个双租户 RLS 集成测试 |
| 文档 | 两套文档；EE 独立多租户文档；中英成对维护翻倍 | 两套部署/升级文档 | 一套文档；补多租户章节 |
| starter 兼容 | 宿主必须换 artifact；旧 CE starter 无法平滑升级 | 需要两套自动装配 | 同一 starter，默认单租户行为不变 |
| 升级路径 | CE→EE 要专门 rehearsal；迁移版本冲突需人工移植 | 单→多要换 schema/重建 | Flyway 顺延升级；单→多只开模式、换角色 |
| 长期人力 | 每个 CE 功能都要判断是否移植、是否越界 | 每个功能都要维护两条路径 | 一次底座成本，之后按普通功能开发 |

数字来源：对两仓 `backend/src/main/java` 做 `find ... -name '*.java' | xargs wc -l`，对
`backend/src/test` 统计 `*Test*.java`，对 `backend/src/main/resources/db/migration` 统计 `*.sql`；
本仓数字用同样的只读统计。统计时点 2026-11-04。

### 3.3 为什么 A 的代价是真实发生过的

- EE **55 个迁移**，CE **23 个迁移**；CE 的 `V21__observation_embedding_state.sql` 与
  EE 的 `V21__create_tenant_model.sql` **同号不同义**。
  `../Blueforce-Tech-Inc/BlueCortexEE/backend/scripts/ce-sync.sh:L27-L29`
  明确写了 "Every CE migration is hard-blocked"，迁移必须人工改号。
- EE 的两份血缘记录都不完全一致：
  `../Blueforce-Tech-Inc/BlueCortexEE/backend/PROVENANCE.md:L8-L12`
  记来源提交 `af9bf942`；
  `../Blueforce-Tech-Inc/BlueCortexEE/backend/scripts/ce-sync.sh:L2-L4`
  记 `eee7785`、`be99f23`。这本身就是"手工移植、持续同步"会产生漂移的实证。
- EE 的 `ce-sync.sh` 只搬 backend；CE 的 webui/admin-panel/docs 明确不在范围内：
  `../Blueforce-Tech-Inc/BlueCortexEE/backend/scripts/ce-sync.sh:L23-L30`。

## 4. EE / CE 考古事实

### 4.1 仓库形态：两个仓库，不是同一套代码里的开关

结论：**EE 和 CE 是两个独立仓库，EE 由 CE 后端一次性移植后分叉；EE 内部有单租户兼容开关，但 CE 源码里没有租户实现。**

证据：

- EE 后端血缘明确写着"代码基线直接取自 BlueCortexCE 的 `backend/`"，并且"租户化改造从
  Flyway V21 起顺延，不修改 CE 已有的 V1–V20"：
  `../Blueforce-Tech-Inc/BlueCortexEE/backend/PROVENANCE.md:L3-L13`、
  `../Blueforce-Tech-Inc/BlueCortexEE/backend/PROVENANCE.md:L21-L28`。
- CE 没有 `tenant` 代码目录；CE 的 `MemoryScopeKey` 明确写 "scope is not a tenant boundary"：
  `../Blueforce-Tech-Inc/BlueCortexCE/backend/src/main/java/com/ablueforce/cortexce/identity/MemoryScopeKey.java:L8-L13`。
- CE README 仍把 "Multi-tenant support" 放在 "Enterprise Features (Coming Soon)"：
  `../Blueforce-Tech-Inc/BlueCortexCE/README.md:L374-L378`。
- EE 内部有单租户兼容模式，但这是 **EE 代码内部的运行时开关**，不是 CE 代码：
  `compatDefaultTenant=true`、`defaultTenantId=default`、`headerTrustEnabled=true`、
  `requireRls=false`：
  `../Blueforce-Tech-Inc/BlueCortexEE/backend/src/main/java/com/ablueforce/cortexce/tenant/TenantProperties.java:L18-L31`。

### 4.2 `tenant_id` 的落点

EE 采用 **共享 `public` schema + 行级 `tenant_id` + RLS**，没有 schema-per-tenant。迁移里没有
`CREATE SCHEMA`，只有 `search_path` 安全设置。

- `tenant` 主表，`id` 就是绑定到 GUC 的 slug：`VARCHAR(64)`，正则
  `^[a-z0-9][a-z0-9_-]{0,63}$`；同时建 `team`、`team_member`、`tenant_api_key`、
  `audit_event`、`usage_meter`，并插入 `default` 租户：
  `../Blueforce-Tech-Inc/BlueCortexEE/backend/src/main/resources/db/migration/V21__create_tenant_model.sql:L19-L118`。
- `tenant_api_key` 是**平台表，无 RLS**，因为必须先查 key 才能知道租户；只存 SHA-256：
  `../Blueforce-Tech-Inc/BlueCortexEE/backend/src/main/resources/db/migration/V21__create_tenant_model.sql:L62-L80`。
- `V22` 给 7 张 `mem_*` 表加 `tenant_id`，回填 `default`，回填不完整直接 `RAISE EXCEPTION`；
  随后 `SET DEFAULT current_setting('app.current_tenant_id', true)` 并 `SET NOT NULL`：
  `../Blueforce-Tech-Inc/BlueCortexEE/backend/src/main/resources/db/migration/V22__tenant_columns_and_backfill.sql:L19-L80`。
- 所有全局唯一都改成租户复合唯一：会话外部 ID 改为 `(tenant_id, platform_source, content_session_id)`，
  scope 改为 `(tenant_id, scope_type, scope_key_sha256)`：
  `../Blueforce-Tech-Inc/BlueCortexEE/backend/src/main/resources/db/migration/V22__tenant_columns_and_backfill.sql:L104-L116`。
- 父子关系改成**带 `tenant_id` 的复合外键**，保证子行不能指向别的租户的父行：
  `../Blueforce-Tech-Inc/BlueCortexEE/backend/src/main/resources/db/migration/V22__tenant_columns_and_backfill.sql:L140-L180`。
- 增加 tenant-leading 索引，避免 RLS 谓词导致全表扫描：
  `../Blueforce-Tech-Inc/BlueCortexEE/backend/src/main/resources/db/migration/V22__tenant_columns_and_backfill.sql:L185-L199`。
- RLS catalog 当前有 **28 张租户表**、8 张平台表、1 张 `tenant` 主表：
  `../Blueforce-Tech-Inc/BlueCortexEE/backend/src/main/java/com/ablueforce/cortexce/tenant/RlsTenantTableCatalog.java:L20-L68`。

### 4.3 隔离执行：RLS + GUC + 双连接池

EE 的隔离不是应用层 `WHERE tenant_id = ?`，而是 PostgreSQL RLS，并且用
`FORCE ROW LEVEL SECURITY` 让表 owner 也受约束。

每条策略的统一形式：

```sql
ENABLE ROW LEVEL SECURITY
FORCE ROW LEVEL SECURITY
ALTER COLUMN tenant_id SET DEFAULT current_setting('app.current_tenant_id', true)
CREATE POLICY tenant_isolation USING (
    tenant_id = current_setting('app.current_tenant_id', true)
) WITH CHECK (
    tenant_id = current_setting('app.current_tenant_id', true)
);
```

证据：
`../Blueforce-Tech-Inc/BlueCortexEE/backend/src/main/resources/db/migration/V23__enable_tenant_rls.sql:L9-L56`、
`../Blueforce-Tech-Inc/BlueCortexEE/docs/multi-tenancy.md:L20-L41`。

关键点：

- GUC 未设置时，`current_setting(..., true)` 返回 `NULL`，比较为 `NULL`，因此**未绑定租户的连接读到 0 行**，是 fail-closed。
- 应用角色必须 `NOBYPASSRLS`；superuser / `BYPASSRLS` 会绕过 RLS。
- 迁移用 migrator 角色跑；应用池和平台池是两个凭据：
  `../Blueforce-Tech-Inc/BlueCortexEE/backend/src/main/resources/application-rls.yml:L1-L36`。
- 平台池故意 `BYPASSRLS`，只允许两个用途：定时任务枚举租户、`/api/platform/**` 控制面；
  普通请求过滤器会跳过平台路径，平台控制器也不调用租户服务：
  `../Blueforce-Tech-Inc/BlueCortexEE/backend/src/main/java/com/ablueforce/cortexce/platform/PlatformPool.java:L22-L42`、
  `../Blueforce-Tech-Inc/BlueCortexEE/docs/multi-tenancy.md:L5-L18`。

### 4.4 数据库连接绑定方式

EE 没有用 Hibernate 的 DATABASE multi-tenancy，也没有用 `SET LOCAL`。原因写得很清楚：
CE 有大量**非事务的 `JdbcTemplate` 写入**，`SET LOCAL` 在语句结束就失效；
`auto-commit=false` 又会在归还连接时回滚这些写入。

EE 的做法：

- 借出连接时 `SELECT set_config('app.current_tenant_id', ?, false)`，session 级；
- 归还/关闭时 `RESET app.current_tenant_id`；
- 无租户上下文借出连接时先 `RESET`，防止继承上一个请求的 GUC；
- Hikari `connection-init-sql: RESET app.current_tenant_id` 兜底。

证据：
`../Blueforce-Tech-Inc/BlueCortexEE/backend/src/main/java/com/ablueforce/cortexce/tenant/TenantAwareDataSource.java:L17-L41`、
`../Blueforce-Tech-Inc/BlueCortexEE/backend/src/main/java/com/ablueforce/cortexce/tenant/TenantAwareDataSource.java:L47-L50`、
`../Blueforce-Tech-Inc/BlueCortexEE/backend/src/main/java/com/ablueforce/cortexce/tenant/TenantAwareDataSource.java:L79-L164`。

这对本仓是**直接适用的教训**：本仓有 55 个文件用 `JdbcTemplate`，也有非事务写入，
不能照搬 `SET LOCAL`。

### 4.5 上下文注入、跨租户与启动校验

EE 的顺序：

1. `X-API-Key` / `Authorization: Bearer bcx_...` → SHA-256 查 `tenant_api_key`，得到 tenant + role；
2. `X-Tenant-Id` 仅在 header-trust 开发模式下接受，且不能因 header 提升角色；
3. 兼容模式 → `default` 租户；
4. 都没有 → **403 `tenant_required`，不进入 controller**；
5. 再检查 suspended / disabled / license / quota；
6. 调用结束后记 usage，清理 `TenantContext`。

证据：
`../Blueforce-Tech-Inc/BlueCortexEE/backend/src/main/java/com/ablueforce/cortexce/tenant/TenantContextFilter.java:L31-L45`、
`../Blueforce-Tech-Inc/BlueCortexEE/backend/src/main/java/com/ablueforce/cortexce/tenant/TenantContextFilter.java:L123-L190`、
`../Blueforce-Tech-Inc/BlueCortexEE/docs/multi-tenancy.md:L55-L72`。

启动校验检查五件事：

1. 当前角色不是 superuser、没有 `BYPASSRLS`；
2. 所有租户表都 `ENABLE+FORCE`；
3. 每张表恰好一条规范 policy；
4. 有 `tenant_id` 列但不在 catalog 里的表 → 直接失败；
5. 无 GUC 的裸连接读 `mem_observations` 必须 0 行。

证据：
`../Blueforce-Tech-Inc/BlueCortexEE/backend/src/main/java/com/ablueforce/cortexce/tenant/StartupRlsVerifier.java:L18-L35`、
`../Blueforce-Tech-Inc/BlueCortexEE/backend/src/main/java/com/ablueforce/cortexce/tenant/StartupRlsVerifier.java:L58-L83`。

**陷阱：EE 默认配置里 verifier 是关闭的**，只有 `rls` profile 才打开 `require-rls`：
`../Blueforce-Tech-Inc/BlueCortexEE/backend/src/main/java/com/ablueforce/cortexce/tenant/TenantProperties.java:L30-L31`、
`../Blueforce-Tech-Inc/BlueCortexEE/backend/src/main/java/com/ablueforce/cortexce/tenant/TenantProperties.java:L71-L83`、
`../Blueforce-Tech-Inc/BlueCortexEE/backend/src/main/resources/application-rls.yml:L13-L19`。
本仓不能复制这个默认值。

跨租户查询：

- **应用角色：不行**。RLS + `FORCE` + `NOBYPASSRLS`。
- **平台池：可以，而且是设计允许的**。
- **`tenant_api_key`：无 RLS**，但查询方式是 SHA-256 digest 精确查找，应用层仍校验 tenant。
- **误配置：可能**。superuser 启动、verifier 关闭、`rls` profile 未激活，都会让 RLS 变成摆设。
- **测试证据**：`TenantIsolationDatabaseIT` 在真实 PostgreSQL 上建 A/B 两个租户，断言 A 读不到 B、
  无 GUC 读 0 行、跨租户写被 `WITH CHECK` 拒绝：
  `../Blueforce-Tech-Inc/BlueCortexEE/backend/src/test/java/com/ablueforce/cortexce/tenant/TenantIsolationDatabaseIT.java:L22-L31`。
  测试代码存在，但本次没有运行。

### 4.6 层级、配额、计费、管理面

层级是：**tenant → team → team_member(subject) → API Key**。

- `team` 只是组织分组，**不是隔离边界**；`tenant_id` 才是。
- `team_member` 用 `member_subject`，不建用户表；身份来自 API Key / SSO / SCIM。
- API Key 绑定 tenant + role（owner/admin/member/viewer）。
- 配额与计费包括 `tenant_license`、`tenant_quota`、`usage_meter`、`usage_daily`、
  `tenant_usage_counter`，以及 V56 的账单、发票、支付表。

证据：
`../Blueforce-Tech-Inc/BlueCortexEE/backend/src/main/resources/db/migration/V21__create_tenant_model.sql:L30-L80`、
`../Blueforce-Tech-Inc/BlueCortexEE/backend/src/main/resources/db/migration/V25__tenant_lifecycle_license_quota.sql:L94-L127`、
`../Blueforce-Tech-Inc/BlueCortexEE/backend/src/main/resources/db/migration/V56__billing_invoices_and_payments.sql:L13-L64`、
`../Blueforce-Tech-Inc/BlueCortexEE/docs/multi-tenancy.md:L90-L96`。

管理面与生命周期：

- 平台控制面独立 token，`/api/platform/**` 不经过租户过滤器；
- 生命周期状态机：`active ⇄ suspended`，`active/suspended/disabled → deleted → active`，`deleted → purge`；
- 软删除带保留期，到期后 `TenantDisposalJob` 按子表→父表分批永久删除；
- 租户导出为 NDJSON，按 `information_schema` 发现表，排除 API Key 与 vector 列，失败表写入 trailer；
- 默认租户受 `protect-default-tenant` 保护，不能软删/硬删。

证据：
`../Blueforce-Tech-Inc/BlueCortexEE/docs/multi-tenancy.md:L106-L121`、
`../Blueforce-Tech-Inc/BlueCortexEE/backend/src/main/java/com/ablueforce/cortexce/platform/TenantLifecycleService.java:L20-L60`、
`../Blueforce-Tech-Inc/BlueCortexEE/backend/src/main/java/com/ablueforce/cortexce/platform/TenantDataExporter.java:L19-L45`。

### 4.7 有没有插件 / SPI

**没有。** 在 EE 后端搜索 `TenantResolver` / `TenantProvider` / `TenantStrategy` /
`interface *Tenant` 没有找到代码级 SPI。EE 的可插拔性来自：

- `bluecortex.multitenancy.*` 配置；
- `rls` profile；
- 平台池独立配置。

也就是说：**EE 是"硬编码的 RLS 方案 + 运行时兼容开关"，不是"可替换隔离实现"的框架。**
这正好说明：如果本仓要做真正的可插拔，不能照抄 EE 的结构，必须自己定义 SPI。

### 4.8 EE 里不能直接搬的模式

EE 的租户生命周期、邀请、队列租约使用了：

- `SELECT ... FOR UPDATE` 行锁；
- `SKIP LOCKED` 队列抢占；
- PostgreSQL advisory lock；
- `SECURITY DEFINER` 函数。

证据：`../Blueforce-Tech-Inc/BlueCortexEE/docs/multi-tenancy.md:L98-L121`。

但本仓 [AGENTS.md](../../AGENTS.md) 第 15 条硬性规则**禁止显式悲观锁、`SKIP LOCKED`、
advisory lock**，要求条件写入/CAS、版本号、唯一约束、lease 与有界重试。

所以 EE 的**思路**可以借鉴，**SQL 不能照搬**。租户生命周期的并发控制必须改成：

- `UPDATE ... WHERE id=? AND status=? AND version=?`；
- 唯一约束防重复邀请；
- lease + 有界重试防 purge 与恢复并发；
- 不能再用 `FOR UPDATE` 一把锁。

### 4.9 第三个相关仓库

`../Blueforce-Tech-Inc/claude-mem` 存在，但它是 **TypeScript 上游基线**，
不是租户实现；在 `src` 里搜 `tenant_id` 为 0 命中。EE 的 PROVENANCE 也写明"不是从
claude-mem 重写"。因此它不进入本报告的租户结论。

## 5. 可插拔插件模型

### 5.1 两个必需 SPI

1. **`TenantResolver`**
   - `SingleTenantResolver`：永远返回 `default`；
   - `PrincipalTenantResolver`：从 `rag_api_key` / `rag_api_principal` 的 `tenant_id` 解析；
   - 宿主可以提供 `HostTenantResolver`，把宿主自己的登录态/租户映射进来。
2. **`TenantJobDispatcher`**
   - 单租户：只跑 `default`；
   - 多租户：用平台池枚举 active 租户，逐租户执行并汇总失败。

可选模块（条件装配，不强制进 core）：

- `TenantLifecycleService`：创建/暂停/恢复/软删/恢复/清除；
- `TenantQuotaPolicy`：租户级 request/token 配额；
- `TenantDataExporter`：NDJSON 导出；
- `TenantAdminController`：平台管理面；
- 计费/许可/SSO/Webhook：企业模块。

**不要**把 `TenantResolver` 设计成"决定 schema 有没有 `tenant_id` 列"。schema 是 core 的，
不插拔。

### 5.2 逐层边界

| 层 | 必须进核心 | 可 SPI / 条件装配 | 说明 |
|---|---|---|---|
| **schema / 迁移** | 是 | 否 | `tenant` 主表、所有业务表 `tenant_id`、复合唯一、复合外键、tenant-leading 索引、RLS。只有一条迁移线。 |
| **数据访问层** | 是 | 否 | 连接级 GUC 绑定。55 个 `JdbcTemplate` + 22 个 JPA Repository，逐条加 `WHERE tenant_id` 不可维护；RLS 是唯一集中 choke point。 |
| **认证上下文** | 是 | `TenantResolver` | core 提供 `TenantContext` + 过滤器集成；解析器可换单租户/principal/宿主。 |
| **检索过滤** | 是 | 否 | 保留 Collection ACL；RLS 兜底租户。`rag_embeddings` 冗余 `tenant_id`。 |
| **缓存** | 是 | 否 | embedding 缓存按文本，天然租户无关；任何缓存"行级结果"的缓存必须带 tenant key。 |
| **后台任务 / 异步 / SSE** | 是 | `TenantJobDispatcher` | 21 个 `@Scheduled`、5 个 `@Async`；SSE 广播必须带 tenant 标签，无租户上下文要丢弃而不是广播给所有人。 |
| **配额** | 现有 per-principal 配额留 core | `TenantQuotaPolicy` | 租户级共享配额是新表 + 原子预留；单租户退化为全局。 |
| **导出 / 删除** | 否 | 条件装配的企业模块 | 租户生命周期、purge 顺序、NDJSON 导出。必须按本仓规则用 CAS/version/lease。 |
| **WebUI** | 否 | 条件装配的管理台 | 普通用户用租户 API Key 即可，现有单基址/单凭据不需要改；租户管理台是新增界面。 |

### 5.3 哪些进核心、哪些做条件装配

**必须进核心**：

- `tenant` 主数据与 `tenant_id` 迁移；
- 复合唯一、复合外键、tenant-leading 索引；
- RLS 策略、角色 bootstrap、启动 verifier；
- `TenantContext` 与认证过滤器集成；
- `TenantAwareDataSource`；
- 异步/定时任务上下文传播；
- 检索与缓存的租户键。

**做 SPI**：

- `TenantResolver`；
- `TenantJobDispatcher`；
- `TenantQuotaPolicy`（可选）。

**做条件装配**：

- 租户生命周期与管理控制器；
- 租户导出与删除；
- 计费、许可、SSO、Webhook；
- 租户管理 WebUI。

### 5.4 测试护栏

多租户不是"单租户测试全绿就能上线"的功能。以下测试必须始终存在，并在 CI 跑：

1. **双租户真实 PostgreSQL 集成测试**：A 读不到 B；无 GUC 读 0 行；跨租户写被拒绝。
2. **启动校验负向测试**：superuser / `BYPASSRLS` / 漏 policy / 漏 `FORCE` 必须启动失败。
3. **后台任务测试**：无租户上下文时任务不执行；多租户时分发到每个 tenant，失败可汇总。
4. **升级测试**：单租户存量库从当前 schema 升级到租户化 schema，数据完整、`collection_key` 仍可用。
5. **检索基准**：HNSW + RLS 下的召回与延迟不低于门槛。

即使默认部署是单租户，第 1 条也必须跑。单租户模式测不出漏写谓词、漏改唯一约束、漏建 RLS。

## 6. 固定 `tenant_id` 的可行性判断

**可行，但必须重新定义"插件"。**

正确的定义：

- 单租户插件 = **固定解析到 `default` 的 N=1 模式**；
- 表里始终有 `tenant_id`，唯一约束/外键/索引始终按租户设计；
- 查询路径只有一套，不允许 `if (multiTenant) SQL else SQL`；
- 多租户插件 = **真实解析 + 强制 RLS**。

不可行的定义：

- 单租户插件"不加 `tenant_id` 列"，多租户插件"加列"；
- 两套实体、两套迁移、两套查询；
- 这等于仓内 fork，代价接近 A，而且单租户模式无法验证多租户的约束。

三个隐患与判断：

1. **"固定值让唯一约束失效"——不准确。**
   - 如果唯一约束是 `(tenant_id, collection_key)`，固定租户时它退化成原来的全局唯一，语义不变，不会失效。
   - 真正的风险是：**现在不改全局唯一，等做多租户时才发现两个租户不能复用同一个 `collection_key`**。
     本仓 `uk_rag_collection_collection_key` 就是全局唯一
     （`spring-ai-rag-core/src/main/resources/db/migration/V27__add_collection_key_expand.sql:L49-L50`），
     必须在第一阶段就改成 `(tenant_id, collection_key)`。

2. **"忘记带条件导致越权"——单租户模式确实测不出来。**
   - 单租户下所有数据都属于 `default`，漏写条件不会暴露。
   - 多租户下由 RLS 兜底；但前提是 RLS 必须开、角色必须 `NOBYPASSRLS`、verifier 必须过。
   - 因此 CI 必须始终跑一个**双租户 + RLS** 的集成测试，即使默认部署是单租户。

3. **"迁移必须带列"——无法避免，而且必须现在做。**
   - 69 张表里，预计 50+ 张要加 `tenant_id`，约 56 处唯一约束要复核，30+ 张要 RLS。
   - 如果先做"单租户无列"，以后再加列，就是两条迁移线、两次回填、两次约束重建，成本和风险都翻倍。

还有一个隐蔽风险：

4. **固定值会掩盖"后台任务丢上下文"。**
   - 单租户下 job 没有租户上下文也能跑；多租户下 RLS 会让它读到 0 行，任务静默停摆。
   - 所以即使是单租户，job 也必须走 `TenantJobDispatcher` / `TenantContext.callWith`，
     不能有"无租户也能跑"的特殊路径。

**结论：** 固定 `tenant_id` 本身不是问题；"固定值 + 两套 schema"才是问题。只要坚持
"单 schema、同查询、同约束、同任务路径"，单租户就是多租户的 N=1 特例，成本可控。

## 7. RLS 还是手写谓词，以及 HNSW 风险

### 7.1 RLS 是唯一可维护的集中防线

本仓有 69 张表、55 个 `JdbcTemplate` 文件、22 个 JPA Repository。手写
`WHERE tenant_id = ?` 意味着 100+ 个插入点，任何一个新入口漏写就是越权；V71 已经证明
"新入口漏接 ACL"是真实发生过的问题（`docs/security-boundary-zh-CN.md:L23-L35`）。

因此：

- **多租户模式必须用 RLS**，这是唯一能兜住"新入口漏写"的集中防线。
- **单租户模式可以关 RLS**（为了 starter 宿主的使用便利），但必须明确：单租户部署不是
  多租户隔离边界；CI 仍要跑多租户 RLS 测试。
- 如果未来必须支持非 PostgreSQL，那就不能依赖 RLS，只能 schema-per-tenant 或应用层过滤，
  成本和风险都会显著上升。**本仓当前是 PostgreSQL + pgvector 为主，建议明确"多租户模式仅支持 PostgreSQL"**。

### 7.2 HNSW + RLS 的性能风险

本仓检索直接查 `rag_embeddings`
（`spring-ai-rag-core/src/main/java/com/springairag/core/retrieval/HybridRetrieverService.java:L1295-L1301`），
向量列是 `VECTOR(1024)`，并有 HNSW 索引。

RLS 会在 ANN 查询上再加 `tenant_id = ...` 谓词。租户很多时，全局 HNSW 的 top-k 可能被
其他租户的向量占满，导致本租户召回不足。这不是理论问题，必须在目标 pgvector 版本上实测，
备选方案：

- 开启 pgvector iterative scan（如果版本支持）；
- 按租户分区 / 租户-leading 索引；
- 增大 `ef_search` 或 over-fetch 后再过滤；
- 评估"每租户独立 collection/分区"的代价。

**这是第一阶段必须过的性能门，不是上线后再优化。**

## 8. 四阶段落地

### 阶段一：决策与租户化底座

- 拍板第 9 节的六条决策；
- 产出 69 张表的归属清单：哪些加 `tenant_id`，哪些是平台全局
  （`rag_api_admin_guard`、`rag_long_running_operation_global_capacity`、
  `rag_collection_purge_preview_admission_global`、`rag_embedding_profiles`、
  `rag_slo_configs`、`flyway_schema_history` 等）；
- 新增 `tenant` 主表、租户成员/密钥/审计/用量骨架；
- 所有业务表加 `tenant_id`，回填 `default`，`NOT NULL`；
- 全局唯一改租户复合唯一，父子外键改复合外键；
- 加 tenant-leading 索引；
- 开 RLS（ENABLE + FORCE + 规范 policy）；
- 应用角色 / migrator / platform 三角色 bootstrap；
- 启动 verifier；
- 单租户默认行为零变化；升级演练通过；
- HNSW + RLS 基准测试跑通。

### 阶段二：请求链路与后台任务

- `TenantResolver` 接入认证；
- `TenantContext` + 过滤器；
- 21 个 `@Scheduled` / 5 个 `@Async` 收敛到 `TenantJobDispatcher`；
- SSE / MCP 广播带 tenant，无上下文 fail-closed；
- 缓存审计，行级缓存加 tenant key；
- 双租户真实 PG 集成测试：A 读不到 B、无 GUC 0 行、跨租户写拒绝。

### 阶段三：运营面

- 租户生命周期（创建/暂停/恢复/软删/恢复/清除）；
- 成员/邀请/席位；
- 租户级配额与用量；
- NDJSON 导出、租户级删除/擦除；
- 可选计费/许可/SSO；
- 租户管理台（WebUI 可选）。

### 阶段四：加固与发布

- 红队测试：漏写谓词、平台池误用、header 伪造、异步丢上下文；
- HNSW + RLS 召回与吞吐基准；
- 备份恢复、租户级导出/删除演练；
- 单租户→多租户升级指南；
- 中英文档成对更新。

## 9. 六条决策

### 9.1 商业模式：一仓开源 + 可选企业模块

**建议**：选 C；核心仓保持单 schema、单迁移线。如果必须闭源，选 C2：企业能力放私有 Maven
模块，依赖已发布的 core 工件，不 fork 仓库。

**理由**：本仓是 starter 形式被宿主引入，价值在"一条升级路径"。双仓会让宿主必须在
CE/EE artifact 之间二选一，并承担迁移版本冲突。

**代价/前提**：私有模块边界要提前画清；企业模块不得反向修改 core schema，只能消费 SPI 和
稳定表结构；许可证与构建发布流程要能接受"开源 core + 私有模块"。

### 9.2 租户是安全边界，Collection 是租户内的知识库

**建议**：`tenant_id` 是隔离单元；Collection 只在租户内共享，不允许跨租户共享 Collection。

**理由**：Collection 是业务知识库，不是安全主体；把 Collection 当租户会导致"同一 Collection
被多个租户引用"的越权模型，RLS 也无法简单表达。

**代价/前提**：现有 `collection_key` 全局唯一必须改成 `(tenant_id, collection_key)`；
`by-key` 路由必须带租户上下文；跨租户复制/共享 Collection 要做显式的导出导入，而不是直接引用。

### 9.3 多租户强依赖 PostgreSQL RLS

**建议**：多租户模式只支持 PostgreSQL；用 RLS + 独立 `NOBYPASSRLS` 应用角色 + 启动 verifier。

**理由**：69 张表、55 个 `JdbcTemplate` 文件、22 个 JPA Repository，手写谓词不可维护；
RLS 是唯一能兜住新入口漏写的集中防线。EE 也选了同一条路。

**代价/前提**：部署必须准备 migrator / app / platform 三个角色；app 不能是 superuser 或
`BYPASSRLS`；平台池必须独立凭据；运维要能理解"0 行"是 fail-closed 而不是 bug。
如果未来必须支持非 PostgreSQL，需要另立方案，不能假设 RLS 可移植。

### 9.4 单租户默认关 RLS，但 schema 必须完整

**建议**：单租户模式默认不启用 RLS，保证 starter 宿主开箱即用；但 `tenant_id` 列、复合唯一、
复合外键、索引、任务路径必须齐全。多租户模式启动时强制 verifier，失败拒绝启动。

**理由**：让所有单租户宿主一上来就配置双角色和 RLS，会显著提高采用成本；而单租户部署
本来就不是多租户隔离边界。把严格性放在多租户模式，是成本与安全的平衡。

**代价/前提**：CI 必须始终跑双租户 + RLS 测试；单租户模式不得出现"无租户也能跑"的特殊 SQL；
文档必须明确"单租户关 RLS ≠ 可以做多租户"；从单租户切多租户时必须先过 verifier。

### 9.5 租户来源：API Key 绑定 + 宿主 Resolver，不放 URL

**建议**：v1 不在 URL path 放 `tenantId`；内置 `rag_api_key` / `rag_api_principal` 绑定租户，
同时提供 `TenantResolver` 给宿主接入自己的登录态。`X-Tenant-Id` 只允许开发模式，且不能提升角色。

**理由**：URL 加租户会改动全站路由、OpenAI 兼容路径和 WebUI；API Key 绑定最简单，也最符合
现有认证模型。宿主 Resolver 让本仓能嵌入已有租户体系，而不是强迫宿主复制一张租户表。

**代价/前提**：API Key 表要加 `tenant_id` 并回填 `default`；principal 缓存、审计、限流都要带
租户键；宿主 Resolver 必须是可信代码，不能从不受信 header 直接推导租户。

### 9.6 平台全局表逐张裁决

**建议**：不要"一刀切全加 `tenant_id`"。单例、全局容量、全局配置、Flyway 逐张判定：
平台全局、租户内共享、租户私有。

**理由**：`rag_api_admin_guard`、`rag_long_running_operation_global_capacity` 这类表如果
强行租户化，会破坏全局不变量；如果全部平台化，又会让租户级配额/审计缺失。

**代价/前提**：第一阶段必须产出完整表清单和每张表的裁决理由；平台全局表只能由平台池访问；
租户表必须 RLS；新增表必须同时更新 catalog 与测试，否则启动 verifier 会失败。

## 10. 三大风险与对策

### 风险 1：RLS 静默失效，直接跨租户泄露

- 应用用 superuser 启动、忘了开 `rls` profile、verifier 关闭，任何一种都会让 RLS 变成摆设。
- EE 自己的默认值就是 `compatDefaultTenant=true`、`headerTrustEnabled=true`、`verifier=false`，
  只有 `rls` profile 才严格。这是现成的反面教材。
- **对策**：多租户模式**启动即校验**，校验失败拒绝启动；应用角色强制 `NOBYPASSRLS`；
  平台池独立凭据；禁止在生产使用 header trust；CI 跑启动校验负向测试。

### 风险 2：后台任务、异步、SSE、缓存丢租户上下文

- 21 个 `@Scheduled`、5 个 `@Async`，再加上 SSE/MCP 长连接；只要有一条路径没带租户，
  就会"读到 0 行静默停摆"，或者更糟——用平台池处理了错误的租户。
- **对策**：所有任务统一走 `TenantJobDispatcher`；所有异步用 `TenantContext.wrap`；
  SSE 广播按 tenant 过滤；无上下文 fail-closed；加"无租户任务"告警。

### 风险 3：升级与性能

- 69 张表、73 个迁移、约 56 处唯一约束要重建；存量数据回填、复合外键、角色切换，
  任何一步失败都可能让宿主升级卡住。
- HNSW + RLS 的召回/吞吐没有实测前，不能承诺多租户检索质量。
- **对策**：先做升级演练和基准测试；迁移分批、可回滚；单租户默认路径零变化；
  多租户上线前必须过双租户隔离测试和 ANN 基准。

次高风险：**租户级删除/导出**。它要覆盖全部租户表、处理 vector/bytea、保证可恢复/可证明，
工作量不比加列小；不要把它当成"最后补一个接口"。

## 11. 验收清单

在宣布"多租户可用"之前，至少要有以下证据：

- [ ] 69 张表归属清单完成，平台全局表有逐张理由；
- [ ] 所有租户表有 `tenant_id`、复合唯一、复合外键、tenant-leading 索引；
- [ ] RLS `ENABLE+FORCE`，每表一条规范 policy；
- [ ] app 角色 `NOBYPASSRLS`，启动 verifier 负向测试通过；
- [ ] 双租户集成测试：A 读不到 B、无 GUC 0 行、跨租户写拒绝；
- [ ] 21 个定时任务 + 5 个异步路径全部带租户上下文；
- [ ] SSE / MCP 无租户上下文时 fail-closed；
- [ ] HNSW + RLS 召回/延迟基准达标；
- [ ] 单租户存量库升级演练通过，`collection_key` 仍可用；
- [ ] 租户删除/导出演练通过，无残留、可证明；
- [ ] 中英文档同步，明确"单租户关 RLS ≠ 多租户"。

## 12. 参考

- 当前状态：[MULTI_TENANCY_STATUS.md](MULTI_TENANCY_STATUS.md)
- 安全边界：[security-boundary-zh-CN.md](../security-boundary-zh-CN.md)
- 项目上下文：[project-context-zh-CN.md](../project-context-zh-CN.md)
- 架构：[architecture-zh-CN.md](../architecture-zh-CN.md)
- 多实例语义：[multi-instance-semantics-zh-CN.md](../multi-instance-semantics-zh-CN.md)
- 协作硬规则：[AGENTS.md](../../AGENTS.md)
