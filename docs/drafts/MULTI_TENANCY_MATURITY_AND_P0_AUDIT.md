# Spring AI RAG 多租户成熟度评估与 P0 问题审计

> 状态：草稿 / 审计结论（2026-11-27）  
> 范围：`spring-ai-rag-core/src/main/java/com/springairag/core/tenant/`、`core/filter/ApiKeyAuthFilter.java`、`core/controller/ApiKeyController.java`、`core/service/ApiKeyManagementService.java`、`core/resources/application.yml`、V75–V85 迁移与现有测试门禁。  
> 关联规划：[运营方管理控制台总设计与分阶段实施规划](OPERATOR_CONSOLE_DESIGN_AND_PLAN.md) | [UI 原型施工手册](OPERATOR_CONSOLE_UI_CONSTRUCTION_MANUAL.md) | [多租户实施进度账本](MULTI_TENANCY_IMPLEMENTATION_PROGRESS.md)

---

## 1. 总结论：多租户是否已经「基本完善」？

**结论：底层隔离地基已经扎实，但尚未达到「可上线运营的多租户 SaaS 完善度」。**

具体来说，应把 `spring-ai-rag` 的多租户现状拆成两层评价：

| 层次 | 成熟度判定 | 事实依据 |
| --- | --- | --- |
| **底层数据隔离与运行机制** | **85%（扎实）** | V75–V85 迁移已落地；76 张表完成三类清点（48 `TENANT_PRIVATE` / 24 `PLATFORM_GLOBAL` / 4 `CREDENTIAL`，机器真源 `TenantTableCatalog.java`）；`SINGLE`/`STRICT` 双模式、会话级 `app.current_tenant_id` GUC + 回读断言、`TenantEndpointGuard`、`TenantJobDispatcher`、`TenantQuotaEnforcer`、core 单测 8595/0/0 与 STRICT PostgreSQL 集成测试 44/44 均已合入 `main`。 |
| **生产安全边界 + 多租户开通闭环 + 运营治理面** | **45%（存在 P0 阻塞）** | 代码审计发现 **5 项 P0 缺陷/阻塞项**（含 RLS 校验可被绕过、新租户无法通过 REST API 签发可用 API Key、非 ACTIVE 租户请求未显式返回 403、`default` 租户无软删保护且非 root 运营者被阻断、混合语料 HNSW+RLS 召回与大表在线迁移未实测）以及 **4 项 P1 运维产品缺口**（缺租户列表/编辑、缺配额管理 API、审计仅打日志未落库、无运营方控制台）。 |

**行动建议**：在进入生产多租户（`rag.tenant.mode=STRICT`）或启动运营方管理控制台后端对接之前，必须先完成 **Phase 0（修复 P0-1 至 P0-4 + 启动 P0-5 专项压测）**。

---

## 2. P0 问题清单（必须优先修复）

### P0-1：`TenantRlsVerifier.verifyPolicySemantics()` 可被多条宽松策略或 `OR true` 绕过，且未校验 `WITH CHECK` 与非表主身份

- **代码证据**：
  - `spring-ai-rag-core/src/main/java/com/springairag/core/tenant/TenantRlsVerifier.java` 第 155–183 行：
    - `Map<String, Object> p = rows.get(0);` —— 仅检查 `pg_policies` 返回的第一条策略，忽略同表上的其他 `PERMISSIVE` 策略。PostgreSQL 对多条 `PERMISSIVE` 策略取 **逻辑 OR**，因此只要存在第二条 `USING (true)` 策略，RLS 隔离就会被彻底旁路，而启动校验依然放行。
    - `isPermissivePolicy(qual)` 仅判断 `qual.strip().equalsIgnoreCase("true")`，且仅用 `qual.contains("current_setting('app.current_tenant_id'")` 做子串匹配；既未拦截 `tenant_id = current_setting('app.current_tenant_id', true) OR true` 类退化谓词，也未校验 `with_check` 子句是否绑定了 `app.current_tenant_id`（只校验了 `with_check` 非空且不等于 `"true"`）。
    - 启动期未显式断言当前连接角色 `current_user` 不是业务表的 `tableowner`（若误用表主角色且缺 `FORCE RLS` 会绕过策略；虽 `relforcerowsecurity` 已检查，但非表主断言是双重保险）。
- **风险等级**：**P0（安全防线被静默削弱）**。
- **修复规格**：
  1. 遍历 `pg_policies` 返回的 **全部** `rows`：要求每张 `TENANT_PRIVATE` 表上不存在任何 `qual`/`with_check` 为 `true` 或含 `OR` 宽松旁路的策略，且对 `PERMISSIVE` 策略做规范化白名单比对（或要求唯一策略名 `rag_tenant_isolation` 且 `qual` 与 `with_check` 均匹配规范表达式）。
  2. 增加 `relrowsecurity = true AND relforcerowsecurity = true` 之外的非 `rolsuper` / 非 `rolbypassrls` / 非 `tableowner` 启动校验（与 `TenantModeGuard` 互补）。
  3. 补充负向 PostgreSQL 集成测试：向某张 `TENANT_PRIVATE` 表添加第二条 `USING (true)` 策略后，断言 `TenantRlsVerifier.verify()` 必须抛 `TenantModeViolationException` 拒绝启动。

---

### P0-2：新租户无法通过现有 REST API 签发可用 API Key（多租户开通闭环断裂）

- **代码证据**：
  - `spring-ai-rag-core/src/main/java/com/springairag/core/tenant/JdbcTenantSqlExecutor.java` 第 36–46 行：`TenantLifecycleService.create()` 创建 `rag_tenant` 记录后，仅在 `rag_api_principal` 插入一条初始主体（`role = 'NORMAL'`、`tenant_id = tenantId`），**并未创建任何 `rag_api_key` 记录**（代码注释写明「真实 key 仍由 `ApiKeyManagementService` 后续轮换/签发」）。
  - `spring-ai-rag-core/src/main/java/com/springairag/core/service/ApiKeyManagementService.java` 第 465–492 行：`createPrincipal(...)` 构造 `new RagApiPrincipal()` 时**从未调用 `principal.setTenantId(...)`**，导致所有通过 `POST /api/v1/rag/api-keys` 创建的主体和密钥全部静默落入 `tenant_id = 'default'`。
  - `spring-ai-rag-core/src/main/java/com/springairag/core/controller/ApiKeyController.java` 第 100–160 行：`POST /api/v1/rag/api-keys` 的 `ApiKeyCreateRequest` 没有 `tenantId` 字段；而 `rotateKey()` / `prepareRotation()` 均要求传入已存在的 `keyId`，无法对没有初始 Key 的 bootstrap principal 进行轮换。
- **复现路径**：
  1. 在 `STRICT` 模式下调用 `POST /api/v1/rag/tenants` 创建 `tenantId = acme`。
  2. 尝试通过 `POST /api/v1/rag/api-keys` 为 `acme` 签发密钥 → 新密钥实际归属 `default`，调用数据面接口只能访问 `default` 租户；而 `acme` 的初始 principal 因没有 `rag_api_key` 永远无法登录或轮换。
- **风险等级**：**P0（功能性阻断：STRICT 模式无法正常开户）**。
- **修复规格**：
  1. 为 `ApiKeyManagementService` 增加显式租户归属方法（如 `issueKeyForTenant(tenantId, request)` / `issueKeyForPrincipal(principalId, request)`），校验目标租户存在且为 `ACTIVE` 后，将 `RagApiPrincipal.tenantId` 显式写入目标 `tenantId`（并同步确保 `rag_api_key` 关联正确 `principal_id`）。
  2. 在运营方接口中提供 `POST /api/v1/rag/tenants/{tenantId}/api-keys`（或在 `ApiKeyCreateRequest` 中支持可选 `tenantId` 且仅限平台运营者/Root 指定非自身租户），并支持为 bootstrap principal 首次签发密钥（一次性返回明文，设 `Cache-Control: no-store`）。
  3. 补充 STRICT 模式双租户端到端测试：通过纯 HTTP API 创建租户 `t1`/`t2` → 分别为 `t1`/`t2` 签发 API Key → 验证双方写入与检索严格隔离。

---

### P0-3：非 `ACTIVE` 租户（`SUSPENDED` / `DISABLED` / 已软删）的请求未在边缘显式返回 `403`，而是静默放行

- **代码证据**：
  - `spring-ai-rag-core/src/main/java/com/springairag/core/filter/ApiKeyAuthFilter.java` 第 160–194 行：数据库 API Key 验签通过后直接放行进入下游过滤器，不校验所属租户的生命周期状态。
  - `spring-ai-rag-core/src/main/java/com/springairag/core/tenant/PrincipalTenantResolver.java` 第 17–26 行：仅对 `status = 'ACTIVE' AND deleted_at IS NULL` 的租户返回 `Optional.of(tenantId)`，对 `SUSPENDED` / `DISABLED` / 软删租户返回 `Optional.empty()`。
  - `spring-ai-rag-core/src/main/java/com/springairag/core/tenant/TenantContextFilter.java` 第 42–51 行：当 `resolved.isEmpty()` 时，过滤器**并未拦截返回 403**，而是直接执行 `filterChain.doFilter(request, response)` 且不设置 `TenantContext`。
- **实际后果**：
  - 租户被 `SUSPEND` 或 `DISABLE` 后，其数据库 API Key 仍能通过 `ApiKeyAuthFilter`，请求继续落到业务 Controller：
    - 读 `TENANT_PRIVATE` 表时，因 RLS GUC 为空，对外表现为误导性的 `200 []` 或 `404 Not Found`，客户端（如 ESA / 上游集成方）无法区分「无数据」与「租户已被暂停/禁用」。
    - 访问某些只读 `PLATFORM_GLOBAL` 元数据端点或未强绑 RLS 的接口时，甚至可能继续返回正常业务数据。
- **风险等级**：**P0（停服开关语义失真、安全与调用方契约不一致）**。
- **修复规格**：
  1. 扩展 `PrincipalTenantResolver` 返回结构化解析结果（`ACTIVE(tenantId)` / `SUSPENDED(tenantId)` / `DISABLED(tenantId)` / `DELETED(tenantId)` / `NOT_FOUND`）。
  2. 在 `TenantContextFilter` 中：若请求已通过数据库 API Key 认证（非 Environment Root），且解析结果非 `ACTIVE`，立即短路返回 **`403 Forbidden`**，携带稳定错误码（`TENANT_SUSPENDED` / `TENANT_DISABLED` / `TENANT_DELETED`）与 RFC 7807 `ErrorResponse`（对 `/v1/**` OpenAI 兼容路径返回 `OpenAiErrorResponse`）。
  3. 补充集成测试：租户 `suspend` 后原 API Key 调用 `/api/v1/rag/collections` 与 `/v1/chat/completions` 均立即返回 `403 TENANT_SUSPENDED`；`resume` 后恢复 `200`。

---

### P0-4：`default` 租户无防软删保护、`lifecycle-actors` 被硬编码 Root 检查阻断、以及 `TenantDataSourceBeanPostProcessor` 无条件包装所有 `DataSource`

- **代码证据**：
  1. `spring-ai-rag-core/src/main/java/com/springairag/core/tenant/TenantLifecycleService.java` 第 149–154 行与 `JdbcTenantSqlExecutor.java` 第 68–73 行：`softDelete(String tenantId, String actor)` 没有任何针对 `default` 租户的保护校验，`DELETE /api/v1/rag/tenants/default` 会直接把 `default` 标为 `deleted_at = CURRENT_TIMESTAMP`，导致系统默认路径与 `SINGLE` 模式整体瘫痪。
  2. `spring-ai-rag-core/src/main/java/com/springairag/core/tenant/TenantLifecycleController.java` 第 212–227、244–253 行：`requireEnvironmentRoot()` 硬编码要求 `ApiKeyAuthFilter.ROOT_AUTHENTICATED_ATTRIBUTE == true`，导致 `application.yml` 中已配置的 `rag.tenant.lifecycle-actors`（第 309 行，用于声明允许执行租户生命周期操作的非 root 运营主体）实际上永远被 403 拒绝。
  3. `spring-ai-rag-core/src/main/java/com/springairag/core/tenant/TenantDataSourceBeanPostProcessor.java` 第 16–22 行：对 Spring 容器中**所有** `DataSource` Bean 无条件包装为 `TenantGucDataSource`。后续一旦为运营方管理控制台引入独立的平台连接池（如 `platformDataSource`），也会被误包进会话级租户 GUC 拦截器。
- **风险等级**：**P0（高危误操作可击穿默认租户 + 运营者权限模型死锁）**。
- **修复规格**：
  1. 在 `TenantLifecycleService.softDelete()` 与 `disable()` 中硬编码保护 `TenantContext.DEFAULT_TENANT_ID`（受 `rag.tenant.protect-default-tenant=true` 默认开启控制），拒绝软删或禁用 `default`（返回 `409 DEFAULT_TENANT_PROTECTED`）。
  2. 统一平台运营者鉴权模型：允许 Environment Root **或** 经认证的平台运营主体（匹配 `rag.tenant.lifecycle-actors` 或未来独立的 `rag_operator_account` 凭证）调用运营管理 API；保留审计 `actor` 记录真实操作者 ID。
  3. 为 `TenantDataSourceBeanPostProcessor` 增加排除名单/Qualifier（如跳过 `@PlatformDataSource` 或 `platformDataSource` Bean），为运营方跨租户只读聚合查询预留清晰边界。

---

### P0-5：多租户混合语料下的 pgvector HNSW `Recall@K` 退化与大表在线 `CONCURRENTLY` 迁移尚未实测验证

- **代码证据与现状**：
  - 参见 [多租户实施进度账本](MULTI_TENANCY_IMPLEMENTATION_PROGRESS.md) 与 [升级演练记录](MULTI_TENANCY_UPGRADE_REHEARSAL.md)：当前仅在 2000 行合成数据集上验证了功能正确性，且测试日志显示 `planUsesIndex=false`。
  - 在多租户共享 `rag_embeddings` 全局 HNSW 索引场景下，若大租户占 90% 语料、小租户占 1% 语料，PostgreSQL 在 RLS 过滤下可能出现 HNSW 候选集被大租户挤满导致小租户 `Recall@K` 断崖式下降（pgvector 0.8+ 需验证 `hnsw.iterative_scan = relaxed_order` / `strict_order` 与显式 `WHERE tenant_id = ?` 的执行计划）。
  - 同时，这直接影响上游系统（如 `enterprise-superagent` 的 `KnowledgeQuery` 检索前过滤与质量门禁）。
- **风险等级**：**P0（大规模生产检索质量与升级停机窗口阻塞）**。
- **修复规格**：
  1. 构造非均匀分布压测集（至少 30 万行，大/中/小租户 80% / 15% / 5%），在真实 PostgreSQL 16 + pgvector 0.8 实测开启 `hnsw.iterative_scan` 前后的 `Recall@10`、P95 延迟与 `EXPLAIN (ANALYZE, BUFFERS)` 计划。
  2. 在百万级 `rag_embeddings` 上完成一次真实 `CREATE INDEX CONCURRENTLY` 与回填锁窗口演练，将实测指标回填至 [升级演练记录](MULTI_TENANCY_UPGRADE_REHEARSAL.md)。

---

## 3. P1 运营与产品缺口（运营方管理控制台建设前置依赖）

除上述 5 项 P0 外，若要真正支撑多租户日常运营（而非靠 DBA 直连数据库或手工 `curl`），还存在 4 项明显的 P1 缺口：

| 编号 | 缺口 | 现状代码证据 | 对运营方控制台的影响 |
| --- | --- | --- | --- |
| **P1-1** | **无租户列表/筛选/编辑 API** | `TenantLifecycleController.java` 仅提供 `POST /`、`GET /{tenantId}`、`POST /{tenantId}/{suspend,resume,disable}`、`DELETE /{tenantId}`，无 `GET /api/v1/rag/tenants` 与 `PATCH /{tenantId}`。 | 控制台「租户目录」页无法分页展示租户列表、按状态筛选或修改租户显示名。 |
| **P1-2** | **无租户配额策略 REST API** | `core/tenant/` 仅有 `TenantQuotaPolicyRepository` / `TenantQuotaEnforcer` / `TenantQuotaLedger`，无任何 REST Controller 暴露 `rag_tenant_quota_policy` 与 `rag_tenant_quota_window` 的读写。 | 运营方无法在线调整租户日/月 Token 或费用配额，也无法查看各租户窗口用量排行。 |
| **P1-3** | **平台生命周期审计仅打日志、未落库** | `TenantConfiguration.java` 第 114 行默认装配 `new TenantLifecycleAudit.Logging()`（仅 SLF4J 日志）。 | 控制台「审计与支持」页无法查询持久化操作流水，也无法支撑敏感操作的事后追责与合规导出。 |
| **P1-4** | **无运营方管理控制台 UI** | `spring-ai-rag-webui` 仅在 `Layout.tsx` 中提供一个只读 `data-testid="tenant-info"` 徽章，无独立的运营方控制台路由或前端工程。 | 运营人员无法可视化管理租户状态、API Key、配额、知识库健康度与模式守卫状态。 |

---

## 4. 建议修复顺序（与控制台实施对齐）

1. **Phase 0（先堵安全与闭环漏洞，预计 1 个独立 PR）**：
   - 修复 **P0-1**（`TenantRlsVerifier` 全策略 + `WITH CHECK` + 非表主断言 + 负向 PG IT）。
   - 修复 **P0-2**（`ApiKeyManagementService` 显式 `tenantId` 签发 + bootstrap principal 首次签发 + 双租户 HTTP E2E）。
   - 修复 **P0-3**（`TenantContextFilter` 对非 `ACTIVE` 租户显式返回 `403 TENANT_SUSPENDED/DISABLED/DELETED`）。
   - 修复 **P0-4**（`default` 租户防软删/防禁用保护 + 放通 `lifecycle-actors` + `TenantDataSourceBeanPostProcessor` 排除平台池）。
2. **Phase 1（并行推进 UI 原型）**：依据 [UI 原型施工手册](OPERATOR_CONSOLE_UI_CONSTRUCTION_MANUAL.md) 先构建 Mock-First 的运营方管理控制台前端原型，确立统一设计语言与冻结契约。
3. **Phase 2（后端运营 API + V86 迁移）**：补齐 P1-1 至 P1-3（见 [总设计与实施规划](OPERATOR_CONSOLE_DESIGN_AND_PLAN.md)），并同步推进 **P0-5**（30 万行非均匀 HNSW 召回压测 + 百万行 `CONCURRENTLY` 演练）。
4. **Phase 3–4（前后端真接 + 双租户 STRICT 验收）**：通过 `VITE_OPERATOR_CLIENT=real` 切换真实后端，完成双租户隔离 + 运营控制台 Playwright E2E 闭环验收。
