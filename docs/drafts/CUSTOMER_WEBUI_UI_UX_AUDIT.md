# Spring AI RAG 客户（租户）Web UI 保守对齐审核报告

> 审核角色：资深互联网产品经理 + 全栈开发工程师  
> 审核日期：2026-11-28  
> 审核范围：`spring-ai-rag-webui/design-tokens/tokens.json`、`src/styles/tokens.css`、`src/components/Layout/Layout.tsx`、`src/i18n/locales/zh-CN.json`  
> 改动原则：**严格保守、零风险**——不新增/删减任何功能特性、不改路由与交互流程、不改根字号/间距布局、不破坏现有 20+ 条前端静态检查与自动化测试门禁；只做「设计语言令牌对齐」与「统一词汇表文案对齐」。

---

## 1. 总结论：为什么可以“安全、低成本”对齐公司统一设计语言？

`spring-ai-rag-webui` 的前端工程化基础非常扎实：
1. **设计令牌单一真源已完备**：所有颜色均由 `design-tokens/tokens.json` 经 `npm run tokens:build` 自动生成 `src/styles/tokens.css`，且配套 `npm run tokens:check`、`npm run check:design-system` 与 `npm run test:design-system`（自动校验亮/暗双主题 WCAG ≥ 4.5:1 对比度）。
2. **文案全部托管在 i18n 语言包**：所有界面标签集中在 `src/i18n/locales/zh-CN.json`（及对应 `en.json`），且有 `npm run check:i18n-keys` 和 `npm run check:hardcoded-copy` 守护。

因此，客户侧 Web UI **无需重构任何页面组件**，只需通过**两处极小、可控的配置级修改**（`design-tokens/tokens.json` + `src/i18n/locales/zh-CN.json`），即可安全靠拢公司统一设计语言（BOS + ESA `classic-light` / `classic-dark`）与统一词汇表。

---

## 2. 视觉设计语言差异清单（保守对齐方案）

### 2.1 建议本轮对齐的色彩令牌（修改 `design-tokens/tokens.json` 后运行 `npm run tokens:build`）

| 令牌 Key | 现有值（Light / Dark） | 公司统一值（BOS & ESA `classic`） | 对齐收益 |
| --- | --- | --- | --- |
| `color.bg` | `#ffffff` / `#0f0f0f` | `#fafafa` / `#0e1319` | 亮色减少刺眼纯白，暗色对齐公司冷青深岩底色 |
| `color.surface` | `#f8f9fa` / `#1a1a1a` | `#ffffff` / `#151c24` | 与 BOS/ESA 卡片表面层次完全一致 |
| `color.surface-2` | `#f1f5f9` / `#232323` | `#f3f4f6` / `#1e2732` | 表格表头与次级容器底色统一 |
| `color.border` | `#e5e7eb` / `#2d2d2d` | `#e5e7e9` / `#29333f` | 1px 边框色调与深岩底色协调 |
| `color.border-strong` | `#d1d5db` / `#3f3f46` | `#d2d6da` / `#3c4856` | 输入框/按钮边框统一 |
| `color.text` | `#111827` / `#e5e7eb` | `#131e29` / `#e6ebf0` | 品牌深墨字色一致 |
| `color.text-muted` | `#4b5563` / `#9ca3af` | `#4c5b6a` / `#a0adba` | 次级说明文字对比度更清晰 |
| `color.primary` / `link` / `focus` | `#2563eb` / `#60a5fa` | `#0f55d6` / `#6094f5` | 从默认 Tailwind 蓝切换为公司品牌蓝 |
| `color.primary-hover` | `#1d4ed8` / `#3b82f6` | `#0c46b4` / `#80aaf8` | 悬停态一致 |
| `color.primary-bg` | `#eff6ff` / `#1e3a5f` | `#e9f0fc` / `#162848` | 选中导航/柔和背景统一 |
| `color.accent` | `#7c3aed` / `#a78bfa`（紫色） | `#e74c3c` / `#f07668`（警示珊瑚红） | 消除与 BOS/ESA 体系冲突的紫色强调色 |
| `status.success` / `success-text` | `#22c55e` (`#166534`) / `#4ade80` | `#16783e` / `#54c480` | 状态绿与公司体系一致 |
| `status.warning` / `warning-text` | `#f59e0b` (`#856404`) / `#fbbf24` | `#b06400` / `#ecb048` | 琥珀黄对比度更稳健 |
| `status.error` / `error-text` | `#ef4444` (`#dc2626`) / `#f87171` | `#b9231c` / `#ee6860` | 破坏性红与运营方控制台一致 |
| `chart.category-1..5` | Tailwind 默认色 | 同步对齐 `#0f55d6`、`#16783e`、`#b06400`、`#b9231c` 等 | 图表色彩与页面主色和谐统一 |

### 2.2 本轮明确「禁止改动」的保守红线
- **不动 `--typography-root-size: 18px` 与 `--space-*` / `--control-height-*`**：防止触发布局折行与视觉截图基线大面积漂移。
- **不动 `Layout.tsx` 13 项导航结构与路由路径**：不改变老客户肌肉记忆，不破坏 `Layout.test.tsx` 与路由记忆逻辑。
- **所有 `on-*` 前景色**在改色后必须立刻运行 `npm run test:design-system`，若某对前背景对比度低于 4.5:1，以门禁校验结果微调 `on-*` 或 `*-bg` 确保 100% 通过。

---

## 3. 统一词汇表文案差异清单（仅改 `zh-CN.json` 文案值，不改 Key）

经逐行核查 `spring-ai-rag-webui/src/i18n/locales/zh-CN.json`，发现 **5 类明显的词汇分裂与中英混杂问题**，均可在不改动业务逻辑与 i18n Key 的前提下安全收口：

| 类别 | 现状问题（具体 Key 与原文） | 保守收口建议 |
| --- | --- | --- |
| **1. 产品名称区分** | `unlock.title`: `"解锁管理控制台"`、`notFound.subtitle`: `"管理控制台中没有这个地址。"`、`unlock.logout`: `"退出控制台"`（与「运营方管理控制台」重名，易混淆客户侧与平台侧） | 客户侧统一称 **「知识库工作台」**（如 `"解锁知识库工作台"`、`"退出工作台"`）；`Layout.tsx` 左上角 Logo 建议保持 `Spring AI RAG`（首字母规范，不动 testid）。 |
| **2. Collection 一物三名** | `nav.collections` 叫 `"知识库"`，`documents.collection` / `collectionScope.*` / `apiKeys.collectionAccess` 叫 `"集合"`，`collections.purge.*` / `documents.relocate` / `embeddings.collectionKey` 直接写英文 `"Collection"` | 面向客户统一收口为 **「知识库」**（仅在需要输入机读键的地方写 `"知识库标识（Collection Key）"`），彻底消除同一页面里同时出现「知识库」「集合」「Collection」三种叫法。 |
| **3. Revoke 撤销/吊销混用** | `apiKeys.revoke`: `"撤销"`、`apiKeys.revoked`: `"已撤销"`，但同文件 `apiKeys.revokeConfirm` 与 `apiKeys.subtitle` 写的是 `"吊销"` | 与运营方控制台统一收口为 **「吊销」** / **「已吊销」**（凭据失效用「吊销」，轮换中止用「取消」）。 |
| **4. 中英混杂与工程黑话** | `embeddings.subtitle`: `"查看 job 状态、Collection 就绪度和 cancel/retry。标签不是概率。"`；`collections.subtitle`: `"...执行 purge 或重新嵌入。"`；`documents.failedWithoutReasonHint`: 含 `provider`、`derivation readiness`；`alerts.principal`: `"Principal"`；`apiKeys.adminCapabilitiesHint`: `"ADMIN principal..."` | 全部润色为自然中文业务表达：`"查看嵌入任务状态、知识库就绪度以及取消或重试操作。"`、`"将文档组织为知识库，统一执行清理或重新嵌入。"`、`"访问主体"`、`"管理员主体"`。 |
| **5. Emoji 状态与只读租户徽章** | `dashboard.systemHealthy`: `"✅ 系统正常"`、`dashboard.systemUnhealthy`: `"❌ 系统异常"`（违反公司「禁止 Emoji 替代状态点」视觉规范）；`tenant.readOnly`: `"当前租户：{{tenantId}} · {{mode}} · {{status}}（只读）"` 直接把英文 `SINGLE`/`ACTIVE` 暴露给客户 | 去掉 `✅`/`❌` Emoji（保留 `"系统正常"` / `"系统异常"`，由组件自身色点承载状态）；`tenant.readOnly` 保持插值兼容或将 `SINGLE`/`STRICT` 与 `ACTIVE` 在展示处映射为中文（注意同步检查 `Layout.test.tsx` 断言是否匹配原文，若测试断言了原字符串则需同步更新测试）。 |

---

## 4. 客户 Web UI 保守改造安全验证门禁

任何对 `spring-ai-rag-webui/` 的修改都必须执行并全绿通过以下既有门禁（零放水）：
1. `npm run tokens:build && npm run tokens:check`
2. `npm run lint`（涵盖 `eslint`、`typecheck`、`check:design-system`、`check:i18n-keys`、`check:hardcoded-copy`、`test:design-system` 等全部 20+ 项检查）
3. `npm run test:run`（Vitest 全量单测，重点核查 `Layout.test.tsx` 与语言包断言）
4. `npm run build`
5. 若存在视觉回归基线测试，按需核验 `npm run test:visual`（仅预期色彩令牌变化，布局几何 0 漂移）。
