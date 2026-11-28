# Spring AI RAG 运营方管理控制台 UI 原型施工手册（Mock-First）

> 状态：草稿 / 施工手册（2026-11-27）  
> 适用对象：MagicPatterns Agent、Claude Code、Cursor、Codex 或任意前端 Coding Agent  
> 配套总设计：[运营方管理控制台总设计与分阶段实施规划](OPERATOR_CONSOLE_DESIGN_AND_PLAN.md) | [多租户成熟度评估与 P0 问题审计](MULTI_TENANCY_MATURITY_AND_P0_AUDIT.md)  
> 视觉与工程范式参考（只读相对路径）：`../enterprise-superagent/docs/drafts/operator-console-magicpatterns-prompts.md`、`../AI-Agent-Operator-Console/`

---

## 1. 施工手册使用原则

本手册用于执行 **Phase 1（Mock-First UI 原型）**：在后端 `/api/v1/rag/operator/**` 尚未全部完工前，先交付一套**视觉统一、交互真实、数据确定、契约冻结**的可运行前端原型，确立统一 UI 与统计语言，并为 Phase 3 通过 `VITE_OPERATOR_CLIENT=real` 零重写切换真实后端打好基础。

**四条施工铁律**：
1. **按顺序执行 Prompt 0 → Prompt 8**，每完成一步即运行 `npm run build` 并自检，确认无报错再进入下一步。
2. **绝不伪装已接真后端**：当 `VITE_OPERATOR_CLIENT=mock` 时，顶部栏必须长期显示 `原型 · mock · 未接真` 徽章；所有写入操作仅修改内存 `mockRepository` 并实时追加单调递增的审计记录。
3. **只做租户粒度，绝不画文档/分块正文**：任何页面和抽屉均不得出现租户文档原文、分块内容或聊天记录浏览器。
4. **保持冻结契约稳定**：`src/domain/operatorContract.ts` 在 Prompt 0 定义完毕后，后续 Prompt 1–8 只消费不随意改名字段，确保后端 Phase 2 与前端 Phase 3 对齐同一份契约。

---

## 2. 统一视觉与统计语言（`xingmu-ui-contract-v1`）

### 2.1 技术栈基线
- React 19 + TypeScript 5.9 + Vite + Tailwind CSS v4 + `lucide-react`（轻量图标，16px 标准尺寸）。
- 独立目录建议：`spring-ai-rag-operator-console/`（默认端口 `5174`），不污染 `spring-ai-rag-webui/`。

### 2.2 设计令牌（必须原样写入 `src/index.css`）
默认主题为 **Classic Light**，支持 `[data-theme='dark']` 切换；严禁渐变背景、发光阴影、玻璃拟态、营销式 Hero 横幅与装饰性插画。

```css
:root {
  --background: #fafafa;
  --card: #ffffff;
  --raised: #ffffff;
  --muted: #f3f4f6;
  --foreground: #131e29;
  --muted-foreground: #4c5b6a;
  --border: #e5e7e9;
  --border-strong: #d2d6da;
  --primary: #0f55d6;
  --primary-hover: #0c46b4;
  --primary-foreground: #ffffff;
  --primary-soft: #e9f0fc;
  --primary-soft-foreground: #0b40a3;
  --accent: #e74c3c;
  --accent-soft: #fdeeeb;
  --destructive: #b9231c;
  --destructive-foreground: #ffffff;
  --success: #16783e;
  --success-soft: #e7f5ec;
  --warning: #b06400;
  --warning-soft: #fff4de;
  --ring: #0f55d6;
}

[data-theme='dark'] {
  --background: #0e1319;
  --card: #151c24;
  --raised: #1b232d;
  --muted: #1e2732;
  --foreground: #e6ebf0;
  --muted-foreground: #a0adba;
  --border: #29333f;
  --border-strong: #3c4856;
  --primary: #6094f5;
  --primary-hover: #80aaf8;
  --primary-foreground: #09142a;
  --primary-soft: #162848;
  --primary-soft-foreground: #a8c6fa;
  --accent: #f07668;
  --accent-soft: #401c1a;
  --destructive: #ee6860;
  --success: #54c480;
  --warning: #ecb048;
  --ring: #80aaf8;
}
```

### 2.3 排版、表面与统计语言规范
- **间距与圆角**：4px/8px 栅格；卡片与工作面 `.surface` 圆角 `4px`（弹窗最大 `8px`），统一 `1px solid var(--border)` 边框。
- **统计数字规范**：所有指标数字、配额、百分比、时间戳、机读 ID（`tenant_id`、`principal_id`、`key_id`、`seq`）一律使用等宽字体（`font-mono`）与 `font-variant-numeric: tabular-nums`，确保表格列与概览带对齐。
- **核心 UI 原语**（置于 `src/ui/`）：
  - `AppShell`（224px 左侧导航 + 48px 顶栏 + 主工作区）
  - `PageHeader`（H1 20px + 简短描述 + 阶段/模式元标签 + 右侧主操作按钮）
  - `OverviewBand`（4 格或 3 格等宽指标带，无独立浮动卡片堆砌，靠 1px 竖线分隔）
  - `AttentionStrip`（异常/P0 告警条，仅在存在异常时渲染）
  - `FilterBar` + `DataTable` / `StackedTable`（1440px 宽屏标准表格，390px 窄屏自动切换为堆叠卡片，无横向溢出）
  - `Drawer`（右侧详情抽屉，宽度 520px，支持 `Esc` 关闭）
  - `ConfirmModal`（破坏性操作二次确认弹窗：要求输入目标 `tenant_id` 与非空 `reason`）
  - `Badge` / `StatusDot` / `Bar`（轻量水平进度条，80% `warning`，100% `destructive`）

---

## 3. 冻结领域契约（`src/domain/operatorContract.ts`）

Prompt 0 必须将以下接口完整落地到 `src/domain/operatorContract.ts`：

```ts
export type TenantMode = 'SINGLE' | 'STRICT';
export type TenantStatus = 'ACTIVE' | 'SUSPENDED' | 'DISABLED' | 'SOFT_DELETED';
export type QuotaPeriod = 'DAY' | 'MONTH';
export type CredentialStatus = 'ACTIVE' | 'ROTATING' | 'EXPIRED' | 'REVOKED' | 'ORPHAN_NO_KEY';
export type OperatorErrorCode =
  | 'UNAUTHORIZED'
  | 'FORBIDDEN'
  | 'CSRF_REQUIRED'
  | 'TENANT_CONFLICT'
  | 'DEFAULT_TENANT_PROTECTED'
  | 'VALIDATION_ERROR';

export interface OperatorError extends Error {
  code: OperatorErrorCode;
  status: number;
  detail: string;
}

export interface TenantSummary {
  tenantId: string;
  displayName: string;
  status: TenantStatus;
  protectedDefault: boolean;
  principalCount: number;
  activeKeyCount: number;
  orphanPrincipalCount: number;
  dayTokenUsed: number;
  dayTokenLimit: number | null;
  monthCostUsedUsd: number;
  monthCostLimitUsd: number | null;
  collectionCount: number;
  documentCount: number;
  embeddingCount: number;
  createdAt: string;
  updatedAt: string;
}

export interface QuotaPolicyRecord {
  tenantId: string;
  displayName: string;
  period: QuotaPeriod;
  enabled: boolean;
  maxTokens: number | null;
  maxCostUsd: number | null;
  usedTokens: number;
  usedCostUsd: number;
  reservedTokens: number;
  windowStart: string;
  windowEnd: string;
}

export interface CredentialRecord {
  principalId: string;
  tenantId: string;
  tenantStatus: TenantStatus;
  role: 'NORMAL' | 'ADMIN';
  keyId: string | null;
  keyPrefix: string | null;
  status: CredentialStatus;
  expiresAt: string | null;
  lastUsedAt: string | null;
  createdAt: string;
}

export interface IssuedApiKeyResult {
  tenantId: string;
  principalId: string;
  keyId: string;
  keyPrefix: string;
  rawKeyOnce: string; // 仅本次展示，关闭弹窗后不可再查看
  createdAt: string;
}

export interface KnowledgeHealthRow {
  tenantId: string;
  displayName: string;
  status: TenantStatus;
  collectionCount: number;
  documentCount: number;
  embeddingCount: number;
  estimatedStorageMb: number;
  embeddingQueuePending: number;
  purgeJobsPending: number;
  failedJobs24h: number;
  lastRecallCheckStatus: 'PASS' | 'WARN' | 'UNVERIFIED';
  lastRecallAt10: number | null;
}

export interface ComplianceSnapshot {
  tenantMode: TenantMode;
  rlsVerifierStatus: 'PASS' | 'WARN' | 'FAIL';
  rlsVerifierCheckedAt: string;
  rlsFindings: Array<{
    severity: 'P0' | 'P1' | 'INFO';
    code: string;
    message: string;
  }>;
  tableCatalog: {
    total: number;
    tenantPrivate: number;
    platformGlobal: number;
    credential: number;
  };
  endpointGuard: {
    totalRoutes: number;
    declaredRoutes: number;
    platformControlRoutes: number;
  };
  dbRoleChecks: {
    currentUser: string;
    isSuperuser: boolean;
    hasBypassRls: boolean;
    isTableOwner: boolean;
  };
}

export interface PlatformAuditEvent {
  seq: number;
  eventId: string;
  occurredAt: string;
  actor: string;
  actorType: 'ENVIRONMENT_ROOT' | 'LIFECYCLE_ACTOR' | 'OPERATOR_SESSION';
  action:
    | 'TENANT_CREATE'
    | 'TENANT_UPDATE'
    | 'TENANT_SUSPEND'
    | 'TENANT_RESUME'
    | 'TENANT_DISABLE'
    | 'TENANT_SOFT_DELETE'
    | 'API_KEY_ISSUE'
    | 'API_KEY_ROTATE'
    | 'API_KEY_REVOKE'
    | 'QUOTA_UPDATE'
    | 'SUPPORT_SESSION_START'
    | 'SUPPORT_SESSION_REVOKE';
  targetTenantId: string | null;
  targetResourceId: string | null;
  reason: string;
  outcome: 'SUCCESS' | 'DENIED';
}

export interface SupportSessionRecord {
  sessionId: string;
  tenantId: string;
  operatorActor: string;
  ticketRef: string;
  reason: string;
  readOnly: true;
  createdAt: string;
  expiresAt: string;
  revokedAt: string | null;
}

export interface TenantDetail {
  summary: TenantSummary;
  quotas: QuotaPolicyRecord[];
  credentials: CredentialRecord[];
  health: KnowledgeHealthRow;
  recentAudits: PlatformAuditEvent[];
}

export interface OperatorClient {
  readonly mode: 'mock' | 'real';
  listTenants(params?: { status?: TenantStatus | 'ALL'; q?: string; includeDeleted?: boolean }): Promise<TenantSummary[]>;
  getTenantDetail(tenantId: string): Promise<TenantDetail>;
  createTenant(input: { tenantId: string; displayName: string; issueBootstrapKey: boolean; reason: string }): Promise<{ tenant: TenantSummary; bootstrapKey?: IssuedApiKeyResult }>;
  updateTenant(tenantId: string, input: { displayName: string; reason: string }): Promise<TenantSummary>;
  transitionTenant(tenantId: string, action: 'suspend' | 'resume' | 'disable' | 'soft_delete', reason: string): Promise<TenantSummary>;
  listCredentials(params?: { tenantId?: string; status?: CredentialStatus | 'ALL'; orphanOnly?: boolean }): Promise<CredentialRecord[]>;
  issueTenantApiKey(tenantId: string, input: { principalId?: string; label: string; reason: string }): Promise<IssuedApiKeyResult>;
  revokeApiKey(keyId: string, reason: string): Promise<void>;
  listQuotas(params?: { period?: QuotaPeriod }): Promise<QuotaPolicyRecord[]>;
  updateQuota(tenantId: string, period: QuotaPeriod, input: { enabled: boolean; maxTokens: number | null; maxCostUsd: number | null; reason: string }): Promise<QuotaPolicyRecord>;
  listKnowledgeHealth(): Promise<KnowledgeHealthRow[]>;
  getComplianceSnapshot(): Promise<ComplianceSnapshot>;
  listAuditEvents(params?: { tenantId?: string; action?: string }): Promise<PlatformAuditEvent[]>;
  listSupportSessions(): Promise<SupportSessionRecord[]>;
  startSupportSession(input: { tenantId: string; ticketRef: string; reason: string; ttlMinutes: number }): Promise<SupportSessionRecord>;
  revokeSupportSession(sessionId: string, reason: string): Promise<void>;
}
```

---

## 4. 确定性 Fixture 规格（`src/domain/fixtures.ts`）

- 统一固定时间基准：`export const MOCK_NOW = '2026-11-27T10:00:00Z';`（严禁调用 `Date.now()` 导致截图与测试抖动）。
- 预置 **6 个覆盖全部边界状态的合成租户**：
  1. `default`（默认租户，`ACTIVE`，`protectedDefault: true`，演示禁用/软删强制保护）；
  2. `acme-legal`（高活跃正常租户，`ACTIVE`，日 Token 使用率 86% 触发 `warning`）；
  3. `northstar-med`（超配额租户，`ACTIVE`，日 Token 使用率 104% 触发 `destructive` 限流提示，且有 2 条失败异步任务）；
  4. `orbit-fin`（新开通孤儿凭据租户，`ACTIVE`，`activeKeyCount: 0`、`orphanPrincipalCount: 1`，直接直观演示 P0-2 补发首把 Key 流程）；
  5. `harbor-edu`（已暂停租户，`SUSPENDED`，演示恢复操作与 403 状态说明）；
  6. `sunset-legacy`（已禁用租户，`DISABLED`，演示受保护软删二次确认）。
- 预置 12 条以上按 `seq` 单调递增的初始 `PlatformAuditEvent` 与 1 条有效只读 `SupportSessionRecord`。
- `mockRepository.ts` 支持 **安全负向开关**（通过右下角折叠 `DevPanel` 触发）：可一键模拟 `UNAUTHORIZED` (401)、`FORBIDDEN` (403)、`CSRF_REQUIRED` (403)、`TENANT_CONFLICT` (409) 及网络延迟（300ms），用于验证所有页面的错误态与重试按钮。

---

## 5. 渐进式 9 步施工提示词套件（Prompt 0 – Prompt 8）

> 使用方式：将每一条 Prompt 完整发给前端 Coding Agent（或 MagicPatterns），待当前步 `npm run build` 通过并目检无误后，再发送下一条。

### Prompt 0：底座、设计令牌、冻结契约、Mock 仓库与 Tenants 骨架
> 请在 `spring-ai-rag-operator-console/` 中初始化 React 19 + TypeScript + Vite + Tailwind CSS v4 + lucide-react 项目，遵循以下要求：
> 1. 将 `xingmu-ui-contract-v1` 的亮色（默认）与暗色（`[data-theme='dark']`）CSS 变量及 `.surface` / `.overview-band` / `.filter-bar` / `.data-table` / `.page-summary` 样式原样写入 `src/index.css`；严禁渐变、阴影浮夸卡片与玻璃拟态。
> 2. 完整落地 `src/domain/operatorContract.ts`、`src/domain/fixtures.ts`（`MOCK_NOW = '2026-11-27T10:00:00Z'`，6 个覆盖全边界状态的合成租户）与内存版 `src/domain/mockRepository.ts`（含单调递增 `seq` 审计记录写入与 `default` 租户保护校验）。
> 3. 实现 `src/domain/clientFactory.ts`，根据 `import.meta.env.VITE_OPERATOR_CLIENT`（默认 `mock`）导出统一的 `OperatorClient` 实例。
> 4. 实现 `src/ui/` 基础原语（`AppShell`、`PageHeader`、`OverviewBand`、`AttentionStrip`、`Badge`、`StatusDot`、`Bar`、`Button`、`Drawer`、`ConfirmModal`、`EmptyState`、`ToastViewport`）。
> 5. 搭建左侧 6 项导航（租户目录 `/tenants`、凭据治理 `/credentials`、配额与用量 `/quotas`、知识库健康 `/knowledge-health`、模式与合规 `/compliance`、审计与支持 `/audit-support`）与顶栏（含「原型 · mock · 未接真」诚实徽章、主题切换、`Cmd+K` 占位按钮），并先让 `/tenants` 渲染真实可交互的租户表格，其余 5 页放置简洁骨架。运行 `npm run build` 确保零错误。

### Prompt 1：`Tenants` 租户目录、详情抽屉与生命周期二次确认
> 在 Prompt 0 基础上完善 `/tenants` 租户目录页与 `TenantDrawer` 详情抽屉：
> 1. 页面顶部放置 4 格 `.overview-band`（租户总数、ACTIVE 租户、高水位/超限租户、无 Key 待补发租户）及状态/关键字筛选栏（支持勾选「包含已软删」）。
> 2. 点击「新建租户」打开弹窗：输入 `tenantId`（小写字母/数字/连字符校验）、`displayName`、勾选「同时签发首个 API Key（默认开启）」与 `reason`；提交后写入 `mockRepository`，若签发了 Key 则弹出一次性明文展示框（带复制按钮与「关闭后不可再次查看」警示）。
> 3. 点击任意租户行打开右侧 `TenantDrawer`：分节展示基本信息（支持内联编辑 `displayName`）、配额快照、凭据概览、知识库聚合指标与最近审计流水。
> 4. 在抽屉中提供生命周期操作按钮：`暂停`（ACTIVE→SUSPENDED）、`恢复`（SUSPENDED→ACTIVE）、`禁用`（→DISABLED）、`软删`（→SOFT_DELETED）。所有破坏性动作必须经过 `ConfirmModal`（要求输入目标 `tenantId` 与非空 `reason`）；`default` 租户的 `禁用` 与 `软删` 必须置灰并提示 `默认租户受保护（DEFAULT_TENANT_PROTECTED）`。每次操作均实时追加审计事件并弹出 Toast。

### Prompt 2：`Credentials` 租户级凭据治理页
> 实现 `/credentials` 凭据治理页：
> 1. 若存在 `orphanPrincipalCount > 0` 的租户（如 `orbit-fin`），页面顶部渲染 `.attention-strip` 提示「存在尚未签发 API Key 的孤儿 Principal，点击一键筛选并补发」。
> 2. 提供按租户、凭据状态（`ACTIVE`/`ROTATING`/`EXPIRED`/`REVOKED`/`ORPHAN_NO_KEY`）及「仅看孤儿主体」的筛选栏。
> 3. 表格列仅展示元数据（`tenantId`、`principalId`、`role`、`keyPrefix`、状态、过期时间、最近使用时间），严禁展示任何明文或哈希。
> 4. 支持「为指定租户签发 API Key」弹窗（可选复用孤儿 `principalId` 或新建 Principal），提交后一次性展示 `rawKeyOnce`；支持对有效 Key 执行「吊销」（二次确认 + 填原因），所有动作同步写入审计流水。

### Prompt 3：`Quotas & Usage` 配额与窗口用量页
> 实现 `/quotas` 配额与用量页（严格遵守「只做配额与用量数字，不做账单计费」边界）：
> 1. 顶部 4 格 `.overview-band`：全平台今日 Token 消耗、本月费用估算总额、触发 80% 预警租户数、已超限（≥100%）租户数。
> 2. 提供 `DAY` / `MONTH` 周期切换分段器与租户配额表：每行展示租户、启停状态、已用/上限（`tabular-nums`）、预留中额度、水平用量进度条（`Bar`，≥80% 琥珀色，≥100% 红色）与窗口截止时间。
> 3. 每行提供「调整配额」弹窗：可修改 `enabled`、`maxTokens`（允许留空表示不限）、`maxCostUsd` 与必填 `reason`；保存后立即刷新进度条与概览带并写入审计。

### Prompt 4：`Knowledge & Health` 知识库聚合健康页
> 实现 `/knowledge-health` 知识库聚合健康页：
> 1. 顶部放置信息提示条（`.context-note`）：明确声明「本页仅展示各租户聚合规模、异步队列与检索健康指标，不读取或展示任何租户文档正文、分块文本或聊天内容」。
> 2. 顶部 4 格 `.overview-band`：全平台 Collection 总数、Document 总数、Embedding 向量总量、近 24 小时失败异步任务总数。
> 3. 表格按租户展示规模分布（Collection / Document / Embedding / 存储估算 MB）、待处理 Embedding/Purge 队列数、24h 失败数、以及 HNSW+RLS `Recall@10` 最近验证状态（`PASS` / `WARN` / `UNVERIFIED`）；对 `UNVERIFIED` 或 `WARN` 行提供清晰的诊断说明（对应混合语料 HNSW 压测未完成或低召回提醒）。

### Prompt 5：`Mode & Compliance` 运行模式与隔离合规页
> 实现 `/compliance` 模式与合规页（纯只读安全驾驶舱）：
> 1. 顶部状态栏展示当前运行模式徽章（`STRICT` / `SINGLE`）、`TenantRlsVerifier` 状态（`PASS`/`WARN`/`FAIL`）及最近校验时间。
> 2. 展示数据库角色安全检查卡片（`current_user`、是否 `SUPERUSER`、是否 `BYPASSRLS`、是否 `tableowner` — 全部为 `false` 时显绿灯）。
> 3. 展示 `TenantTableCatalog` 76 表三类归属汇总（48 `TENANT_PRIVATE` / 24 `PLATFORM_GLOBAL` / 4 `CREDENTIAL`）与 `TenantEndpointGuard` 路由覆盖率。
> 4. 展示 `rlsFindings` 列表（默认在 mock 中包含一条提示性的 P0/P1 检查项示例，演示告警条与明细联动）。

### Prompt 6：`Audit & Support` 持久化审计流水与只读支持会话页
> 实现 `/audit-support` 页，采用上下或标签页双区块布局：
> 1. **平台审计流水**：只读表格按单调序号 `#seq` 倒序展示操作时间、操作者与类型、动作标签、目标租户、目标资源、原因与结果；支持按租户和动作筛选。验证此前在 Tenants / Credentials / Quotas 页执行的操作均实时出现在顶部。
> 2. **受审计只读支持会话**：展示当前有效与历史支持会话（工单号、目标租户、原因、剩余倒计时、状态）；提供「申请只读支持会话」弹窗（必填目标租户、工单号、原因、TTL 15/30 分钟）与「立即撤销」按钮。激活会话时页面顶部显示只读警示横幅，绝不提供任何修改租户业务数据的入口。

### Prompt 7：双客户端抽象与安全负向调试面板
> 完成 Phase 3 真接铺路与负向状态验证能力：
> 1. 新建 `src/domain/httpOperatorClient.ts`，实现完整的 `OperatorClient` 接口（统一 `fetch` 封装、映射到 `/api/v1/rag/operator/**`、自动传递 `Idempotency-Key` 与 `X-CSRF-Token`、将后端 RFC 7807 `ErrorResponse` 映射为 `OperatorError`）。
> 2. 更新 `src/domain/clientFactory.ts`：`VITE_OPERATOR_CLIENT=real` 时实例化 `httpOperatorClient`，为 `mock` 时实例化 `mockRepository`；仅当 `client.mode === 'mock'` 时渲染顶栏「原型 · mock · 未接真」徽章与 `DevPanel`。
> 3. 实现右下角可折叠 `DevPanel`（仅 mock 模式可见）：允许一键注入 `UNAUTHORIZED`、`FORBIDDEN`、`CSRF_REQUIRED`、`TENANT_CONFLICT` 错误以及 300ms 延迟、重置内存 fixture 数据，并确认各页面均优雅展示错误横幅与重试按钮而不白屏崩溃。

### Prompt 8：键盘导航、响应式断点、对比度与 Playwright 冒烟脚本
> 执行收口打磨与自动化验证：
> 1. 增加全局键盘快捷键：`Cmd+K`（或 `Ctrl+K`）打开快速命令面板（支持跳转 6 个视图与快速搜索租户）、表格页 `j`/`k` 移动焦点、`Enter` 打开详情抽屉、`Esc` 关闭弹窗/抽屉、`[` 折叠左侧导航。
> 2. 检查 **1440px / 768px / 390px** 三个断点：390px 窄屏下所有主表格切换为堆叠卡片（`StackedTable`），无任何水平滚动条溢出；校验亮/暗双主题正文与状态文字对比度 ≥ 4.5:1。
> 3. 全量文案巡检：清除任何英文残留、未翻译占位符、`Lorem` 或暗示已连生产数据库的措辞。
> 4. 编写 `e2e/operator-console.mock.spec.ts` Playwright 冒烟测试，覆盖：6 视图渲染、创建租户并展示一次性 Key、暂停与恢复租户、`default` 租户保护校验、修改配额、审计流水新增验证、DevPanel 403 错误态、390px 无横向溢出。运行 `npm run build` 与 Playwright 测试确保全绿。

---

## 6. 常见漂移修复提示词（审校纠偏用）

若前端 Agent 在某一步出现风格或边界漂移，直接发送对应短提示修复：

1. **视觉过重 / 出现渐变与大阴影**：
   > 请移除所有背景渐变、大范围 `box-shadow` 和营销式头部卡片；恢复 `xingmu-ui-contract-v1` 的 1px 边框 `.surface` 与 `.overview-band` 平铺节奏，数字与 ID 统一 `font-mono tabular-nums`。
2. **越界画出租户文档/分块内容**：
   > 运营方控制台严格只做租户粒度管理，请移除所有文档正文、分块预览或对话记录展示区域，保留聚合计数与任务健康状态，并在页面顶部明确标注不展示原文。
3. **缺少二次确认或未保护 `default` 租户**：
   > 租户暂停、禁用、软删与吊销 API Key 必须走 `ConfirmModal`（强制输入 `tenantId` 与 `reason`）；`default` 租户的禁用与软删按钮必须置灰并在点击/悬停时提示 `DEFAULT_TENANT_PROTECTED`。
4. **Mock 与 Real 耦合写死在组件内**：
   > 页面组件不得直接导入 `mockRepository` 或 `fixtures`，必须统一通过 `OperatorClient` 接口调用，并确保 `VITE_OPERATOR_CLIENT=mock|real` 可无感切换。

---

## 7. Phase 1 验收清单（交付确认门禁）

| 序号 | 检查项 | 验收标准 |
| --- | --- | --- |
| 1 | 构建与类型检查 | `tsc --noEmit` 与 `npm run build` 0 错误 0 阻塞警告 |
| 2 | 视觉统一性 | 亮/暗双主题完整命中 `xingmu-ui-contract-v1` 令牌，无渐变/霓虹/装饰性大图 |
| 3 | 边界红线 | 全站 0 处文档正文/分块预览、0 处账单/支付字段、0 处 `localStorage` 存放 Root Key |
| 4 | 诚实标识 | Mock 模式下顶部常驻「原型 · mock · 未接真」徽章，且可一键在 `DevPanel` 演示 401/403/409 |
| 5 | 核心交互闭环 | 开户→展示一次性 Key→补发孤儿 Key→调配额→暂停/恢复→审计流水实时可见，全流程可复现 |
| 6 | 响应式与无障碍 | 1440 / 768 / 390 三断点零横向溢出；`j`/`k`/`Enter`/`Esc`/`Cmd+K` 键盘操作正常 |
| 7 | 自动化冒烟 | `e2e/operator-console.mock.spec.ts` 在 Chromium 上 100% 通过 |
