# Spring AI RAG 运营方管理控制台（多租户管理后台）总设计与分阶段实施规划

> 状态：草稿 / 规划包（2026-11-27）  
> 前置审计：[多租户成熟度评估与 P0 问题审计](MULTI_TENANCY_MATURITY_AND_P0_AUDIT.md)  
> 配套施工手册：[UI 原型施工手册（Mock-First）](OPERATOR_CONSOLE_UI_CONSTRUCTION_MANUAL.md)  
> 外部参考（只读相对路径，非仓库内链接）：`../enterprise-superagent/docs/drafts/enterprise-superagent-operator-console-plan.md`、`../enterprise-superagent/docs/drafts/multitenancy-and-operator-console-batch-plan-2026-11-28.md`、`../AI-Agent-Operator-Console/`

---

## 1. 定位、目标与硬性边界

### 1.1 我们要建什么
为 `spring-ai-rag` 建设一套独立的 **运营方管理控制台（Operator Console）**，供平台运维 / 客户成功 / 安全管理员在 `STRICT`（多租户）或 `SINGLE`（单租户）模式下，以 **租户（Tenant）粒度** 统一完成：
1. **租户生命周期管理**：开户、查看、暂停、恢复、禁用、受保护软删；
2. **租户级凭据治理**：为指定租户签发首个或新增 API Key、阶段性轮换、吊销与过期巡检；
3. **配额与窗口用量治理**：配置 `rag_tenant_quota_policy`（日/月 Token 与费用上限）并查看 `rag_tenant_quota_window` 消耗排行；
4. **知识库与异步任务聚合健康**：查看各租户 Collection/Document/Embedding 聚合规模、Embedding/Purge 队列积压与 HNSW+RLS 检索健康；
5. **多租户模式与隔离合规自检**：实时展示 `SINGLE`/`STRICT` 状态、`TenantRlsVerifier` 校验结果、`TenantTableCatalog` 76 表归属与 `TenantEndpointGuard` 覆盖度；
6. **平台审计与受审计只读支持**：查询持久化平台管理操作流水，并在必要时发起限时、只读、强审计的支持代看会话。

### 1.2 五条硬性边界（不可突破）
借鉴 `enterprise-superagent` 运营方控制台设计原则，本控制台从第一天起执行以下硬性边界：

| 边界项 | 硬性规则 | 理由 |
| --- | --- | --- |
| **1. 租户粒度原则** | **只做租户粒度仪表盘与开关，严禁做成跨租户业务明细操作台**。不得提供跨租户浏览文档正文、分块内容（`rag_chunks`/`rag_embeddings` 文本）、对话记录或检索结果的界面。 | 防止运营后台演变为跨租户数据泄露面；保持 RLS 租户隔离语义清晰。 |
| **2. 独立前端与路由隔离** | 运营方控制台与租户工作台（`spring-ai-rag-webui`）**物理隔离**（独立包目录 `spring-ai-rag-operator-console/` 或独立构建产物与独立路由前缀 `/operator/**`），不得混入租户导航栏。 | 租户永远看不到运营入口；两者鉴权模型与部署访问面（内网/VPN vs 公网）完全不同。 |
| **3. 独立控制面 API 与能力声明** | 所有控制台后端接口统一挂载于 `/api/v1/rag/operator/**`（或兼容既有 `/api/v1/rag/tenants/**`），并全部在 `TenantEndpointDeclarationCatalog` 中声明为 `PLATFORM_CONTROL`，在 `PlatformCapabilityRegistry` 中绑定明确能力码。 | 杜绝租户数据面 API Key 越权调用控制面接口。 |
| **4. 只做配额与用量，不做账单计费** | 仅展示与配置 Token/Cost 配额策略及窗口消耗数字，**不引入**发票、支付渠道、订阅计划、税率、对账单等商业计费系统。 | 保持 RAG 基础设施边界纯粹，计费由上游商业系统（如 BOS / 外部 Billing）承担。 |
| **5. 诚实原型与双客户端切换** | Phase 1 先基于冻结 TypeScript 契约与确定性 mock 数据交付 UI 原型，界面显式标注「原型 · mock · 未接真」，并从第一天内置 `VITE_OPERATOR_CLIENT=mock|real` 切换点。 | 避免 mock 假象误导验收，确保 Phase 3 真接时零页面重写。 |

---

## 2. 信息架构（6 个核心视图 + 1 个全局详情抽屉）

对照 `../AI-Agent-Operator-Console/` 的页面节奏与 `spring-ai-rag` 领域特性，控制台采用左侧固定导航（224px，窄屏可折叠）+ 顶部操作栏（48px，含 `Cmd+K` 命令面板、模式标识、主题切换）+ 单一连续工作面（`.surface`）的布局，共 6 个一级视图：

| 视图路由 | 中文标题 | 核心职责 | 关键交互与防呆设计 |
| --- | --- | --- | --- |
| `/tenants` | **租户目录** | 全量租户分页表（`tenant_id`、显示名、状态、Key 数、日配额使用率、文档/向量规模、更新时间）+ 顶部状态筛选与搜索 | 点击行打开右侧 **`TenantDrawer`（租户详情抽屉）**；新建租户弹窗；`SUSPEND` / `DISABLE` / `SOFT_DELETE` 必须走**二次确认弹窗**（输入 `tenant_id` + 填写 `reason`）；`default` 租户的禁用/软删按钮强制置灰并展示保护说明。 |
| `/credentials` | **凭据治理** | 跨租户查看 `rag_api_principal` 与 `rag_api_key` 元数据（按租户筛选、状态筛选、过期预警、**无 Key 孤儿 Principal 告警**） | 为指定租户签发首个或新增 API Key（修复 P0-2 后），明文仅一次性弹窗展示并附带复制按钮；支持发起/完成/取消阶段性轮换、立即吊销（二次确认）。 |
| `/quotas` | **配额与用量** | 平台汇总带（4 格 `.overview-band`：活跃租户数、今日全平台 Token、触发限流租户数、高水位租户数）+ 租户配额策略表与窗口消耗排行 | 行内打开「编辑配额」弹窗（配置 `DAY`/`MONTH` 的 `maxTokens` 与 `maxCostUsd`、启停状态）；超 80% 标 `warning`、超 100% 标 `destructive`；不出现任何金额收款或账单字段。 |
| `/knowledge-health` | **知识库健康** | 各租户聚合资源规模（Collection 数、Document 数、Embedding 向量数、存储占用估算）+ 异步任务队列状态（Embedding / Async Purge 积压与失败数）+ HNSW+RLS 检索健康诊断卡 | 明确标注「仅展示聚合元数据与任务状态，不展示原始文档与分块正文」；支持按租户筛选失败任务摘要与重跑入口（仅限安全幂等操作）。 |
| `/compliance` | **模式与合规** | 展示当前 `rag.tenant.mode`（`SINGLE`/`STRICT`）、`TenantRlsVerifier` 启动校验明细、`TenantModeGuard` 角色权限断言、`TenantTableCatalog` 76 表三类统计（48/24/4）与 `TenantEndpointGuard` 路由声明覆盖率 | 只读安全驾驶舱；任何校验异常（如缺 `FORCE RLS`、多余 permissive 策略）在顶部 `.attention-strip` 高亮阻断告警。 |
| `/audit-support` | **审计与支持** | `rag_platform_audit_event` 持久化审计流水表（按操作者、动作、目标租户、时间筛选）+ 受审计的只读支持会话申请/记录表 | 审计流水严格只读、按单调序号 `seq` 倒序；只读支持会话强制限时（默认 15 分钟）、强制填写工单号与原因、到期自动失效。 |

---

## 3. 安全、认证与数据库隔离架构

### 3.1 认证与鉴权路径
1. **阶段 A（Phase 2 首发）**：复用并修正 `spring-ai-rag` 现有平台控制面鉴权——允许 **Environment Root** 或在 `rag.tenant.lifecycle-actors` 中声明的受信任平台主体（修复 P0-4）访问 `/api/v1/rag/operator/**`；所有响应强制 `Cache-Control: no-store`，防敏感运维数据被代理缓存。
2. **阶段 B（后续演进，可选）**：如部署为浏览器直连的内部运营后台，增加独立的 Operator Session（Cookie `HttpOnly` + `SameSite=Lax` + CSRF Token 校验，对应前端契约中的 `UNAUTHORIZED` / `CSRF_REQUIRED` / `FORBIDDEN` 错误语义），**绝不在浏览器 `localStorage` 中长期明文存放 Environment Root Key**。

### 3.2 平台连接池与 RLS 边界（关键架构约束）
- 业务数据面继续使用受 `TenantGucDataSource` 包装的 **非表主、`NOBYPASSRLS` 应用连接池**（`rag_app`）。
- 运营方控制台需要执行跨租户只读聚合查询（例如统计各租户文档数、配额窗口排行、全量凭据视图）：
  - **方案**：在 P0-4 改造 `TenantDataSourceBeanPostProcessor` 排除 `@PlatformDataSource` 后，为运营方只读聚合查询与平台控制面提供独立的、低连接数上限的 `platformDataSource`（或在 `STRICT` 下按 `rag_tenant` 列表显式逐租户聚合，二选一并在启动期校验）。
  - **硬约束**：`platformDataSource` 仅允许注入到标注 `@PlatformControlOnly` 的运营方只读仓储与平台审计/租户生命周期服务中，严禁注入任何租户数据面 Service/Repository，且在启动期由架构测试（ArchUnit / 单元测试）守卫。

### 3.3 路由与能力登记
所有新增 `/api/v1/rag/operator/**` 端点必须同步登记至：
- `TenantEndpointDeclarationCatalog`：分类为 `PLATFORM_CONTROL`（确保 `TenantEndpointGuard` 启动自检 100% 覆盖）；
- `PlatformCapabilityRegistry`：按最小权限拆分能力码（`TENANT_READ`、`TENANT_WRITE`、`CREDENTIAL_MANAGE`、`QUOTA_MANAGE`、`HEALTH_READ`、`COMPLIANCE_READ`、`AUDIT_READ`、`SUPPORT_SESSION`）。

---

## 4. 后端 API 契约与 V86 迁移设计（Phase 2 蓝图）

### 4.1 后端 API 清单（含现有复用与新增缺口）

| 模块 | Method & Path | 状态 | 说明与契约要点 |
| --- | --- | --- | --- |
| **租户目录** | `GET /api/v1/rag/operator/tenants` | **新增** | 分页列出租户；支持 `status`、`q`（匹配 `tenantId`/`displayName`）、`includeDeleted`、`page`/`size`、`sort`；返回含聚合摘要（Key 数、日配额使用率、Collection/Doc 数）。 |
| 租户详情 | `GET /api/v1/rag/operator/tenants/{tenantId}` | **新增**（扩展现有 `GET /api/v1/rag/tenants/{tenantId}`） | 返回租户全量元数据、配额策略与当前窗口、凭据统计、知识库聚合规模与最近 10 条审计记录。 |
| 创建租户 | `POST /api/v1/rag/operator/tenants` | **复核/扩展** | 复用 `TenantLifecycleService.create()`，增加可选参数 `issueBootstrapKey: boolean`（默认 `true`），创建时一步返回一次性初始 Key（解决 P0-2）。 |
| 编辑租户 | `PATCH /api/v1/rag/operator/tenants/{tenantId}` | **新增** | 修改 `displayName` 与备注，写入持久化审计。 |
| 生命周期变更 | `POST /api/v1/rag/operator/tenants/{tenantId}/{suspend\|resume\|disable}` | **复用+加固** | 要求请求体携带非空 `reason`；`disable` 对 `default` 返回 `409 DEFAULT_TENANT_PROTECTED`（P0-4）；变更后立即触发 `TenantContextFilter` 403 生效（P0-3）。 |
| 软删租户 | `DELETE /api/v1/rag/operator/tenants/{tenantId}` | **复用+加固** | 要求 `reason` 与 `confirmTenantId` 一致；对 `default` 返回 `409 DEFAULT_TENANT_PROTECTED`。 |
| **凭据治理** | `GET /api/v1/rag/operator/credentials` | **新增** | 跨租户列出 Principal 与 API Key 元数据（脱敏 prefix，绝不返回 hash/明文）；支持 `tenantId`、`status`、`orphanOnly=true` 筛选。 |
| 租户签发 Key | `POST /api/v1/rag/operator/tenants/{tenantId}/api-keys` | **新增（P0-2）** | 为指定 `ACTIVE` 租户的新建或既有 bootstrap principal 签发 API Key；响应 `Cache-Control: no-store`，仅本次返回明文；支持 `Idempotency-Key`。 |
| 轮换/吊销 | `POST /api/v1/rag/operator/api-keys/{keyId}/rotate` / `DELETE .../{keyId}` | **复用** | 代理至 `ApiKeyManagementService`，统一写入平台持久化审计表。 |
| **配额与用量** | `GET /api/v1/rag/operator/quotas` | **新增** | 返回平台汇总指标 + 各租户 `DAY`/`MONTH` 配额策略与当前窗口用量排行。 |
| 更新配额 | `PUT /api/v1/rag/operator/tenants/{tenantId}/quotas/{period}` | **新增** | 读写 `rag_tenant_quota_policy`（`period ∈ {DAY, MONTH}`），设置 `maxTokens`、`maxCostUsd`、`enabled`，写入审计。 |
| **知识库健康** | `GET /api/v1/rag/operator/knowledge-health` | **新增** | 聚合各租户 Collection/Document/Embedding 计数、异步 Embedding/Purge 队列状态与近期失败任务摘要（不含文档正文）。 |
| **模式与合规** | `GET /api/v1/rag/operator/compliance` | **新增** | 返回当前 `rag.tenant.mode`、最近一次 `TenantRlsVerifier` 校验报告、`TenantTableCatalog` 76 表分类汇总与 `TenantEndpointGuard` 覆盖率。 |
| **审计与支持** | `GET /api/v1/rag/operator/audit-events` | **新增** | 分页查询 `rag_platform_audit_event`（按 `seq DESC`，支持 `tenantId`、`action`、`actor`、`from`/`to`）。 |
| 只读支持会话 | `POST /api/v1/rag/operator/support-sessions` / `POST .../{id}/revoke` | **新增（可选 Phase 2b）** | 申请/撤销限时只读支持会话（记录工单号、原因、15 分钟 TTL、审计流水）。 |

### 4.2 数据库迁移 V86 设计（`PLATFORM_GLOBAL` 表）
新增迁移 `V86__operator_console_audit_and_support.sql`（两张新表均须登记到 `TenantTableCatalog.java` 的 `PLATFORM_GLOBAL` 类，确保 78 表清点门禁通过）：
1. **`rag_platform_audit_event`**：
   - `seq BIGSERIAL PRIMARY KEY`（单调递增序号，便于增量拉取与审计防篡改）
   - `event_id VARCHAR(64) NOT NULL UNIQUE`
   - `occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP`
   - `actor VARCHAR(128) NOT NULL`
   - `actor_type VARCHAR(32) NOT NULL`（`ENVIRONMENT_ROOT` / `LIFECYCLE_ACTOR` / `OPERATOR_SESSION`）
   - `action VARCHAR(64) NOT NULL`（如 `TENANT_CREATE`、`TENANT_SUSPEND`、`API_KEY_ISSUE`、`QUOTA_UPDATE`）
   - `target_tenant_id VARCHAR(64)`
   - `target_resource_id VARCHAR(128)`
   - `reason TEXT`
   - `outcome VARCHAR(16) NOT NULL`（`SUCCESS` / `DENIED` / `FAILED`）
   - `detail_json JSONB`（脱敏前后对比快照，严禁记录明文 Key 或文档内容）
   - 索引：`(target_tenant_id, seq DESC)`、`(action, seq DESC)`、`(occurred_at DESC)`。
2. **`rag_operator_support_session`**（Phase 2b）：
   - `session_id VARCHAR(64) PRIMARY KEY`、`tenant_id VARCHAR(64) NOT NULL REFERENCES rag_tenant(tenant_id)`、`operator_actor VARCHAR(128) NOT NULL`、`ticket_ref VARCHAR(128) NOT NULL`、`reason TEXT NOT NULL`、`read_only BOOLEAN NOT NULL DEFAULT TRUE CHECK (read_only = TRUE)`、`created_at TIMESTAMPTZ NOT NULL`、`expires_at TIMESTAMPTZ NOT NULL`、`revoked_at TIMESTAMPTZ`。

---

## 5. 分阶段实施路线图与验收门禁

每一步必须有可复现的验证命令与可确认的交付物，**严禁跳步**。

| 阶段 | 目标与范围 | 交付物 | 退出门禁（Exit Gate） |
| --- | --- | --- | --- |
| **Phase 0：P0 安全与闭环修复** | 修复 [P0 审计](MULTI_TENANCY_MATURITY_AND_P0_AUDIT.md) 中的 P0-1 至 P0-4（RLS 全策略校验、指定租户签发 Key、非 ACTIVE 租户 403 短路、`default` 保护与 `lifecycle-actors` 放通、DataSource 包装排除） | 1 个聚焦后端 PR + 负向与双租户 PG IT | `mvn test` 全绿；STRICT PG IT 含「第 2 条 `USING (true)` 拒启」「suspend 后立即 403」「纯 HTTP 创建双租户并签发 Key 隔离读写」全部通过。 |
| **Phase 1：Mock-First UI 原型** | 按 [UI 原型施工手册](OPERATOR_CONSOLE_UI_CONSTRUCTION_MANUAL.md) 构建独立前端原型 `spring-ai-rag-operator-console/`（6 视图 + 抽屉 + `xingmu-ui-contract-v1` + 冻结契约 + `mockRepository` + `VITE_OPERATOR_CLIENT` 开关） | 可运行的前端原型工程 + Playwright mock 冒烟脚本 | `npm run build` 零错；1440/768/390 三断点零溢出；亮/暗主题对比度 ≥ 4.5:1；6 视图与二次确认、负向状态（401/403/409）全可演示；显著标注「原型 · mock · 未接真」。 |
| **Phase 2：后端运营 API 与 V86 迁移** | 落地 V86 迁移（`rag_platform_audit_event` 等）、`TenantTableCatalog` 更新、6 组 `/api/v1/rag/operator/**` Controller/Service、持久化 `JdbcTenantLifecycleAudit` 替换日志实现；同步推进 **P0-5**（30 万行非均匀 HNSW 压测 + 百万行 `CONCURRENTLY` 演练） | 后端运营 API PR + OpenAPI 契约 + P0-5 压测报告 | 所有新增表/端点通过 `TenantTableCatalog` 与 `TenantEndpointGuard` 启动自检；全量接口单元与 STRICT PG IT 通过；P0-5 实测指标回填至 [升级演练记录](MULTI_TENANCY_UPGRADE_REHEARSAL.md)。 |
| **Phase 3：前后端真接（Dual-Client Cutover）** | 实现 `src/domain/httpOperatorClient.ts` 对接 Phase 2 真实 API；保留 `VITE_OPERATOR_CLIENT=mock` 供无后端快速预览，切换 `VITE_OPERATOR_CLIENT=real` 联调；移除真实模式下的「原型 · mock」徽章 | 真接客户端 + 联调配置文档 | 零页面组件改动完成 mock→real 切换；真实后端下完成开户→发 Key→调配额→暂停→恢复→查审计全链路验证。 |
| **Phase 4：双租户 STRICT 端到端与上线门禁** | 启动真实 PostgreSQL + `rag.tenant.mode=STRICT` + 真实后端 + 运营方控制台，运行 Playwright Real E2E 与安全回归（确认租户 Key 访问 `/api/v1/rag/operator/**` 必返 403、平台不泄露任何租户文档正文） | 验收报告 + 归档本规划包并沉淀至常青 `docs/` | Playwright Real E2E 100% 通过；安全回归 0 缺陷；文档检查 `./scripts/verify-project-docs.sh` 通过。 |

---

## 6. 决策记录（实施前需确认项）

在进入 Phase 1 / Phase 2 编码前，仅需确认以下 2 个轻量决策（均有推荐默认值，不阻塞立即启动 Phase 0 与 Phase 1）：
1. **前端工程落点**：默认在仓库内新建独立目录 `spring-ai-rag-operator-console/`（与 `spring-ai-rag-webui/` 平级，独立 `package.json`、独立 Vite 构建与端口，如 `5174`）；若希望像 `../AI-Agent-Operator-Console/` 那样先放独立仓库做原型、Phase 3 再迁入，也可无缝兼容，因为契约文件 `operatorContract.ts` 完全独立。
2. **只读支持会话（Phase 2b）**：首版（Phase 2a）可先上线 5.5 视图（审计流水完整上线，支持会话先留只读架构占位或作为 Phase 2b 交付），避免拉长首版上线周期。
