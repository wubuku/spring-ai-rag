# Spring AI RAG 运营方管理控制台 UI/UX 全面审核报告

> 审核角色：资深互联网产品经理 + 全栈开发工程师  
> 审核日期：2026-11-28  
> 审核范围：`spring-ai-rag-operator-console/`、`docs/drafts/OPERATOR_CONSOLE_DESIGN_AND_PLAN.md`、`docs/drafts/OPERATOR_CONSOLE_UI_CONSTRUCTION_MANUAL.md`  
> 公司对标基线：BOS（`../AI-Native-Business-Operating-System/src/src/styles/tokens.css` 与 `PRODUCT-DESIGN-AND-IA-AS-IS.md`）、ESA 运营方控制台（`../AI-Agent-Operator-Console/` 与 `../enterprise-superagent/docs/drafts/operator-console-magicpatterns-prompts.md`）

---

## 1. 总结论与评分卡

**综合结论：视觉令牌底座已对齐公司统一设计语言（BOS 与 ESA 共享的 `classic-light` / `classic-dark`），但产品层面的「统一词汇表」「轻心智信息架构」与「交互打磨度」尚未过关，当前处于“工程师可用、运营/客户成功高心智负担”状态，需做一轮定向 UI/UX 精修后再进入全量真接验收。**

| 审核维度 | 当前评级 | 核心结论 |
| --- | --- | --- |
| **1. 统一视觉设计语言** | **A-（基本对齐）** | `src/index.css` 已完整采用 BOS + ESA 共同的 `classic-light` / `classic-dark` 色值（`#fafafa` / `#131e29` / `#0f55d6` / `#e74c3c` / `#16783e` / `#b06400` / `#b9231c`，暗色 `#0e1319` / `#151c24`），无渐变与装饰冗余；仅缺 `SectionHeader` 节奏统一与焦点态细化。 |
| **2. 统一词汇表（Glossary）** | **C（P0 缺陷）** | 未先建立独立词汇表；界面大量直接输出英文数据库枚举（`ACTIVE`/`ORPHAN_NO_KEY`/`UNVERIFIED`/`TENANT_CREATE`）与后端内部实现术语（`rag_tenant_quota_policy`、`CAS`、`rolsuper`、`Principal`、`C / D / E`）。 |
| **3. 信息架构（IA）与心智负担** | **B-（P1 需优化）** | 6 视图主干合理且守住了“只做租户粒度、不看文档正文”底线；但命名偏技术模块而非运营任务，且缺少 `AttentionStrip` 快捷异常筛选、列排序、筛选计数与一键清空。 |
| **4. 交互体验与状态完整性** | **B-（P1 需补齐）** | 缺少加载骨架屏（`SkeletonTable`）与空状态（`EmptyState`）；`DevPanel` 固定在左下角遮挡侧栏底部「边界守则」；弹窗缺 `Esc` 关闭与行内即时校验；键盘 `j`/`k` 未同步滚动与 DOM Focus。 |
| **5. 移动端与无障碍（A11y）** | **B（P2 需细化）** | 390px 已切换堆叠卡片无横向溢出，但卡片信息字段偏少、表格排序缺 `aria-sort`、部分按钮缺更明确的语义标签。 |

---

## 2. 跨产品对标：公司统一设计语言核验（BOS + ESA）

经核查代码确认，公司内 **BOS**（`../AI-Native-Business-Operating-System/src/src/styles/tokens.css`）与 **ESA 运营方控制台**（`../AI-Agent-Operator-Console/src/index.css`）已经执行完全一致的 **企业级统一视觉语言（`classic-light` / `classic-dark`，亦称 `xingmu-ui-contract-v1`）**：

- **色彩系统**：亮色底 `#fafafa`、卡片 `#ffffff`、深岩墨主字 `#131e29`、次字 `#4c5b6a`、细边 `#e5e7e9`、品牌蓝 `#0f55d6`、警示红 `#b9231c`、成功绿 `#16783e`、琥珀黄 `#b06400`；暗色冷岩底 `#0e1319`、卡片 `#151c24`、主蓝 `#6094f5`。
- **界面节奏**：48px 顶栏 + 224px 侧栏 + 单一连续 `.surface` 工作面 + 4px 克制圆角 + 1px 细边 + `font-mono tabular-nums` 统计数字 + **零 Emoji 状态图标** + **100% 中文化状态标签**（参考 `AI-Agent-Operator-Console/src/domain/mockRepository.ts` 中的 `CUSTOMER_STATUS_LABEL` 等映射表）。

---

## 3. 逐项问题清单（按优先级）

### P0-1：缺失独立「统一词汇表」，大量英文枚举与后端黑话直接暴露给运营人员

- **现状证据**：
  - `TenantsPage.tsx` / `TenantDrawer.tsx`：状态徽章直接渲染 `ACTIVE`、`SUSPENDED`、`DISABLED`、`SOFT_DELETED`；表头出现缩写 `知识库规模（C / D / E）`；描述出现 `TenantContextFilter`、`rag_tenant`、`DEFAULT_TENANT_PROTECTED`。
  - `CredentialsPage.tsx`：状态直接显示 `ORPHAN_NO_KEY`、`ROTATING`、`EXPIRED`、`REVOKED`；多处使用英文 `Principal`。
  - `QuotasPage.tsx`：副标题直接写表名 `rag_tenant_quota_policy`；概览带写 `CAS 预留中 Tokens`；徽章写 `ENABLED` / `UNLIMITED`。
  - `KnowledgeHealthPage.tsx`：表头写 `Collections / Docs / Embeddings`、`Emb / Purge`、`HNSW Recall@10`；状态写 `PASS` / `WARN` / `UNVERIFIED`。
  - `CompliancePage.tsx`：直接展示 PostgreSQL 系统列名 `rolsuper`、`rolbypassrls`、`is_table_owner`、`TENANT_PRIVATE（FORCE RLS）`。
  - `AuditSupportPage.tsx`：动作列直接渲染 `TENANT_CREATE`、`QUOTA_UPDATE`、`API_KEY_ISSUE`，结果列渲染 `SUCCESS` / `DENIED`。
- **整改方案（必须先落地 `src/domain/glossary.ts` 作为全站唯一中文映射源）**：

| 概念 / 枚举 | 禁止在主界面裸露的词 | 统一中文业务词汇（公司级） | 备注 |
| --- | --- | --- | --- |
| Tenant | customer/org 混用 | **租户**（对外客户场景可称「客户租户」） | 机读 ID 仍以等宽副行展示 `tenant_id` |
| TenantStatus | `ACTIVE` / `SUSPENDED` / `DISABLED` / `SOFT_DELETED` | **正常** / **已暂停** / **已禁用** / **已软删** | 下拉筛选同步使用中文 |
| Principal | `Principal` / `孤儿主体` | **访问主体** / **待发密钥主体** | `principal_id` 仅作等宽标识列 |
| CredentialStatus | `ACTIVE` / `ORPHAN_NO_KEY` / `ROTATING` / `EXPIRED` / `REVOKED` | **有效** / **未签发密钥** / **轮换中** / **已过期** / **已吊销** | 与客户工作台统一使用「吊销」 |
| Quota Switch | `ENABLED` / `UNLIMITED` / `CAS 预留中` | **已启用限额** / **不限额** / **进行中预占** | 去除 `rag_tenant_quota_policy` 与 `CAS` |
| Collection/Doc/Emb | `C / D / E`、`Collections` | **知识库 / 文档 / 向量分块** | 彻底消除英文缩写表头 |
| Recall Status | `PASS` / `WARN` / `UNVERIFIED` | **召回正常** / **召回偏低** / **待压测验证** | 鼠标悬停可看 `Recall@10` 指标 |
| DB Role Flags | `rolsuper` / `rolbypassrls` / `is_table_owner` | **非超级管理员** / **强制受 RLS 约束** / **非业务表拥有者** | 括号或 tooltip 保留原始技术项名供 DBA 核对 |
| Audit Action/Outcome | `TENANT_CREATE` / `SUCCESS` / `DENIED` | **新建租户** / **调整配额** / **签发密钥** / **成功** / **已拒绝** | 保持可读性与追责效率 |

---

### P1-1：缺失加载骨架屏（`SkeletonTable`）与空状态（`EmptyState`）

- **现状证据**：`TenantsPage`、`CredentialsPage`、`QuotasPage`、`KnowledgeHealthPage`、`AuditSupportPage` 均无加载骨架态；筛选结果为空时只显示一个空 `<tbody>`，用户无法判断是“正在加载”还是“筛选条件太严格”。
- **整改方案**：在 `src/ui/primitives.tsx` 补齐 `SkeletonTable` 与 `EmptyState`（参考 `AI-Agent-Operator-Console/src/ui/EmptyState.tsx`），筛选为空时提供一键「清除筛选」按钮。

### P1-2：列表页缺少列排序、筛选结果计数与 `AttentionStrip` 快捷入口

- **现状证据**：`TenantsPage` 不能按「今日用量」「创建时间」「状态」排序；无“已启用 N 个筛选 · 共 M 条结果 · 清除筛选”反馈；当存在 `SUSPENDED` 或超限租户时，顶部缺少像 `AI-Agent-Operator-Console/CustomersPage.tsx` 那样可一键点击筛选的 `AttentionStrip`。
- **整改方案**：为 `TenantsPage` 与 `QuotasPage` 补充表头排序按钮（`↑`/`↓`/`↕` + `aria-sort`）、筛选栏计数与清空按钮，并在 `TenantsPage` 顶部增加“需要关注”提示带（超配额 / 未签发密钥 / 已暂停租户一键直达）。

### P1-3：顶栏缺少平台健康与当前运营署名；`DevPanel` 悬浮位置遮挡侧栏底部

- **现状证据**：
  - `App.tsx` 中 `DevPanel` 固定在 `fixed bottom-4 left-4`，桌面端恰好盖住 224px 侧栏底部的「边界守则」。
  - 顶栏缺少当前运行模式（`STRICT`/`SINGLE`）、平台连通绿点与当前操作者署名（对比 `AI-Agent-Operator-Console/TopBar.tsx`）。
- **整改方案**：将 `DevPanel` 入口移至顶栏右侧「扳手图标（仅 Mock 模式可见）」，点击在右上角下拉展开；顶栏左侧增加平台状态点 + 运行模式徽章，右侧增加当前运营署名。

### P1-4：弹窗与键盘交互细节打磨

- **现状证据**：新建租户、签发密钥、调整配额、申请只读支持会话 4 个弹窗均未监听 `Esc` 关闭；新建租户的 `tenant_id` 正则校验未在输入框下方实时提示；表格按 `j`/`k` 移动高亮行时未调用 `scrollIntoView` 与 DOM Focus。
- **整改方案**：抽出通用 `Modal` 原语统一支持 `Esc` 关闭与焦点管理；表单补充即时行内校验提示；表格行加 `tabIndex={0}` 与 `scrollIntoView({ block: 'nearest' })`。

### P2-1：移动端（390px）堆叠卡片字段完整度补齐

- **现状证据**：`CredentialsPage` 移动端卡片未显示 Key 前缀与到期时间；`KnowledgeHealthPage` 未显示存储占用与待处理队列数。
- **整改方案**：采用 2 列 `<dl>` 紧凑网格（参考 `AI-Agent-Operator-Console/CustomersPage.tsx` 移动端卡片），在不产生横向溢出的前提下呈现全部关键决策字段。
