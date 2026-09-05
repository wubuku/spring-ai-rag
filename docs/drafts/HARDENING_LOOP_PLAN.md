# 加固循环批次规划（HARDENING LOOP）

> **状态**：活跃循环
> **开始日期**：2026-09-05
> **工作区**：`/Users/yangjiefeng/Documents/wubuku/spring-ai-rag`
> **运行模式**：用户授权的持续循环——规划一个 batch → 实施/验收 → commit + push 特性分支 → 规划下一个 batch；用户喊停前不退出。
> **本阶段优先级**：代码特别是测试的加固、WebUI UI/UX 增强与重构、技术债务处理（充分回归测试覆盖）。**新功能不是本阶段重点。**

本档是循环的恢复账本：记录批次队列、每个批次的规划要点、验证证据与交付状态。
单个批次的深度设计仍以代码事实为准；批次完成后在此追加记录，不另开 plan/progress 对。

## 1. 批次队列（按优先级排序，可被实施中发现的事实调整）

| # | 批次 | 类型 | 来源证据 | 状态 |
|---|------|------|----------|------|
| 1 | WebUI 测试加固：6 个零测试文件补测 + coverage include pages + thresholds 门禁 | 测试加固 | 前端扫描：ABTest/Unlock/ConfirmDialog/DocumentActionsMenu/FilePreview/ReembedAllButton 无测试；vitest coverage 无 thresholds 且不含 pages | **进行中** |
| 2 | Design token 机器门禁：check-design-tokens.mjs 增加字面颜色禁令（白名单基线）；MetricsCharts 内联主题与 ReembedAllButton 深色主题失明修复 | 技术债务 + UX | 前端扫描：416 处颜色字面量、门禁不防回潮；MetricsCharts.tsx:71-144 isDark 手工分支 | 待开始 |
| 3 | Button/Card primitive 提取：消除 ABTest/ApiKeys `.btn*` 复制粘贴与散落 25+ button 类 | UI 重构 | ABTest.module.css:7-58 ≈ ApiKeys.module.css:7-57 | 待开始 |
| 4 | a11y 加固：ABTest/Settings/Evaluation/Documents 表单 label 关联、ChatSidebar 删除按钮 aria-label、button 嵌套复核 | UX/可访问性 | ABTest 7 input 0 关联；ChatSidebar.tsx:99-107 | 待开始 |
| 5 | 后端 deprecated/dead code 清理：RagChatService 弃用 SourceDocument→ChatSource；删除 RagApiKeyRepository.findFirstByPrincipalIdAndEnabledTrue、ApiKeyManagementService.validateKeyEntity 死代码 | 技术债务 | 后端扫描：ChatResponse.java:194 弃用仍被 RagChatService 调用 | 待开始 |
| 6 | 后端单测加固：DocumentSyncRunService、CollectionPurgeService、EmbeddingJobExecutor 补单测 | 测试加固 | 仅 gated IT 覆盖，CI 静默跳过 | 待开始 |
| 7 | 前端行为加固：useFileUpload abort、revokeObjectURL setTimeout 清理统一、Settings saved timer 清理 | 技术债务 | useFileUpload.ts:80 无 abort；Search.tsx:219 等 60s setTimeout | 待开始 |
| 8 | JaCoCo check 覆盖率门禁 + 评估 CI 打开 gated IT | 测试加固 | pom.xml:126-160 无 check goal | 待开始 |

## 2. 循环约束（每个批次适用）

1. 每个批次基于最新本地 `main` 建独立特性分支 `feat/<主题>-<日期>`；前一批次未合入 main 时，后续分支基于前一批次分支叠加，push 时连同依赖分支一起推。
2. 批次完成即 commit + push 特性分支；**不合并 main**（等用户指令或积累后统一合并）。
3. 每批次适用门禁：前端改动的跑 typecheck / `test:run` / lint（含 alignment + design-token）/ production build；后端改动的跑 `mvn clean compile test-compile` 与相关测试。涉共享契约才加 Mock Playwright / 联合验收。
4. 每批次实现后做一轮只读 review（正确性/回归风险），发现问题修复并重跑受影响门禁。
5. 行为变化同步长青文档；纯测试/内部重构不需文档变更，但需在本文档记录证据。
6. 不丢弃、不 stash 工作区并发修改；开工与提交前核对 `git status`。

## 3. Batch 1 规划：WebUI 测试加固

### 3.1 问题陈述

前端 30 个页面/组件中 6 个完全没有测试；vitest coverage 的 `include` 不含 `src/pages/**` 且无 `thresholds`，覆盖率数字失真且无防回归门禁。这 6 个文件里包括鉴权入口 `Unlock.tsx` 和最大无测试页面 `ABTest.tsx`，风险最高。

### 3.2 目标与非目标

- **目标**：
  1. 为 6 个零测试文件补 Vitest 单元/行为测试（渲染、关键交互、边界与清理行为）；
  2. `vitest.config.ts` coverage include 加入 `src/pages`；
  3. 实测新基线后设置 `thresholds`（lines/statements/functions/branches），锁住不回退。
- **非目标**：不修改任何生产组件行为；不追求覆盖率数字最大化；不引入新依赖（如 @testing-library/user-event 需先确认已有）。

### 3.3 已核对事实

- 零测试文件清单（前端扫描）：
  - `src/pages/ABTest.tsx`（384 行，7 个无关联 input，9 处颜色字面量）
  - `src/pages/Unlock.tsx`（74 行，鉴权入口）
  - `src/components/Dialog/ConfirmDialog.tsx`
  - `src/components/DocumentActionsMenu/DocumentActionsMenu.tsx`（4 个全局监听 + 定位逻辑）
  - `src/components/FilePreview/FilePreview.tsx`（objectUrl 生命周期）
  - `src/components/ReembedAllButton/ReembedAllButton.tsx`（react-query refetchInterval 轮询）
- 测试基线：34 个 Vitest 文件、251 个测试全绿（2026-08-28 记录）。
- 测试栈：Vitest + @testing-library/react，已有 25 个页面/组件测试可作惯例参照。

### 3.4 实施顺序

1. 读 6 个目标文件 + 2 个现有测试样例，确认 mock 惯例（api client、react-query、router）。
2. 按风险从高到低补测：Unlock → ABTest → DocumentActionsMenu → FilePreview → ReembedAllButton → ConfirmDialog。
3. 改 `vitest.config.ts` coverage include + 跑 `test:coverage` 实测基线。
4. 设置 thresholds = 实测值向下取整（留极小余量防环境抖动），写入配置。
5. 全量门禁 + review + commit/push。

### 3.5 验收矩阵

| 项 | 适用 | 说明 |
|---|---|---|
| npx tsc -b | ✓ | 类型门禁 |
| npm run test:run | ✓ | 全量单测含新增 |
| npm run test:coverage | ✓ | 产出新基线并断言 thresholds 生效 |
| npm run lint | ✓ | 含 alignment + design-token 链 |
| npm run build | ✓ | 生产构建 |
| Mock Playwright | ✗ | 未改任何生产行为/路由/DOM 结构 |
| 后端 mvn 门禁 | ✗ | 未触碰后端文件 |
| 真实 LLM | ✗ | 无模型路径变更 |

### 3.6 完成定义

6 个测试文件全部落盘且通过；coverage thresholds 生效（故意低于阈值时 test:coverage 失败——用临时下调验证一次后还原）；门禁全绿；特性分支已推送。

### 3.7 实施记录与验证证据（2026-09-05）

- 新增 6 个测试文件：`Unlock.test.tsx`(5)、`ABTest.test.tsx`(8)、`DocumentActionsMenu.test.tsx`(9)、
  `FilePreview.test.tsx`(8)、`ReembedAllButton.test.tsx`(5)、`ConfirmDialog.test.tsx`(4)，共 39 个新测试。
- `vitest.config.ts`：coverage include 加入 `src/pages/**`；thresholds = stmts 64 / branch 63 /
  funcs 44 / lines 65（实测基线 65.19/64.54/45.49/65.98，留 ~1pt 余量）。
- 门禁结果：`tsc -b` 通过；`test:run` 290/290（40 文件）；`lint`（eslint+alignment+design-token）通过；
  `build` 通过；thresholds 拦截验证通过（临时 statements=99 → `Coverage does not meet global threshold`，已还原）。
- 实施中发现的事实（供后续批次参考）：
  - Vitest 的 CSS Module 类名是哈希值（`_skeleton_daa00f`），测试不能按裸类名断言，用角色/文本/DOM 结构。
  - i18n mock 的 `t` 只回 key 不插值，插值文案断言要按 key 断。
  - CI（`.github/workflows/ci.yml`）不运行前端 `test:coverage`，coverage thresholds 当前是本地门禁；
    接入 CI 归入后续 CI 加固批次（Batch 8 范围）。
- Review 结论（一轮只读复核）：无生产代码改动；测试无 flaky 信号（全量重复运行两次均绿）。

## 4. 批次记录（按时间序）

### Batch 1（已交付）

- 分支：`feat/webui-test-hardening-20260905`（基于 main@d185dbfa）
- 提交：`88dc1248`，已推送 origin；未合并 main。
- 内容：6 个零测试文件补 39 个测试；coverage include pages + thresholds（64/63/44/65）。
- 证据：`test:run` 290/290；threshold 拦截验证（临时 99 → ERROR）；tsc/lint/build 绿。

### Batch 2（已交付）

- 分支：`feat/webui-design-token-gate-20260905`（基于 Batch 1 分支）
- 内容：
  1. `scripts/check-design-tokens.mjs` 扩展字面颜色禁令：扫描 src 下 css/ts/tsx
     （hex 3/4/6/8 位 + rgb/hsl 函数；排除 global.css 与 *.test.*），基线
     `scripts/design-token-color-baseline.json` 按文件等值锁死——新增失败、
     减少必须同步下调（防基线虚占）。
  2. global.css 新增 4 个 warning 面色 token（bg/bg-hover/border/text，明暗两套）。
  3. `ReembedAllButton.module.css` 全量 token 化（原 19 处字面量清零，深色主题可用）。
  4. 新增 `src/hooks/useChartTheme.ts`：recharts 用色从 CSS 变量解析
     （SVG attribute 不支持 var()），MutationObserver 监听 data-theme 切换；
     `MetricsCharts.tsx` 删除全部 isDark 三元与字面颜色（原 22 处清零），
     亮色主题取值与原字面量一致，视觉无回归。
- 基线变化：389 处/26 文件 → 343 处/24 文件。
- 证据：`tsc -b` 绿；`test:run` 293/293（41 文件，新增 useChartTheme 3 测试）；
  `lint`（含新门禁）绿；`build` 绿；`verify-project-docs.sh` 11/11；
  门禁负向验证：干净文件加临时 `#123456` → 失败；基线人为膨胀 → stale 失败；均正确拦截。
- 文档同步：`developer-reference-zh-CN.md` / `developer-reference.md` §6 补充
  lint 链与 coverage 阈值说明。

### Batch 3（已交付）

- 分支：`feat/webui-button-primitive-20260905`（基于 Batch 2 分支）
- 内容：
  1. 新增 `src/components/Button` primitive（forwardRef、variant primary/secondary/
     danger/link、默认 `type="button"`、默认 variant secondary、className 合并、
     rest 透传 `form` 等 attribute；样式仅用 token，含 disabled 统一语义）。
  2. `ABTest.tsx`（10 处）与 `ApiKeys.tsx`（16 处）全部迁移到 `<Button>`；
     `styles.btnBack` 保留为页面局部样式。JSX 保留 `type="submit"` + `form` 透传。
  3. 两个 module.css 删除逐字重复的 `.btn*` 块（ABTest 5 块、ApiKeys 7 块），
     danger 色从 `#ef4444` 收敛到 `--color-error`。
- 基线变化：343 → 341（等值基线门禁在实施中正确拦截了「减了颜色但没下调基线」）。
- 证据：`tsc -b` 绿；`test:run` 297/297（42 文件，新增 Button 4 测试）；
  `lint`（含 design-token 门禁）绿；`build` 绿；页面既有测试（ApiKeys 13 + rotation 5 +
  ABTest 8）全部无修改通过，证明行为无回归。
- Review 结论：默认 variant 从 primary 改为 secondary（强调样式应显式选择）并补测。

### Batch 4（已交付）

- 分支：`feat/webui-a11y-hardening-20260905`（基于 Batch 3 分支）
- 内容：
  1. `ChatSidebar` 接入 i18n（原组件全部硬编码英文）：newChat/noHistory 复用现有 key，
     新增 `chat.deleteSession`/`timeJustNow`/`timeMinutesAgo`/`timeHoursAgo`（en + zh-CN）；
     删除会话按钮补 `aria-label`（含会话名插值）与 title。
  2. 复核扫描报告的「button 嵌套 button」：实为 div 内兄弟节点，无嵌套问题，无需修改。
  3. `ABTest` 创建实验弹窗 7 个表单控件全部补 `htmlFor`/`id` 关联。
  4. 顺带技术债：两份 locale JSON 的历史遗留非标格式（行首逗号）规范化为标准
     2 空格缩进；语义完整性已用脚本验证（除新增 4 key 外键值零变化）。
- 证据：`tsc -b` 绿；`test:run` 300/300（42 文件，新增 ChatSidebar 渲染 2 测试 +
  ABTest label 关联 1 测试）；`lint`/`build` 绿；locale 完整性脚本校验通过。
- 剩余 a11y 债务（后续批次）：Settings/Evaluation/Documents 表单 label 关联、
  Alert/ApiKeys badge 对比度 token 化。

### Batch 5（已交付）

- 分支：`fix/backend-deprecated-cleanup-20260905`（基于 Batch 4 分支）
- 内容：
  1. `RagChatService`（6 处）与 `RagChatControllerTest`（4 处夹具）从弃用
     `ChatResponse.SourceDocument` 迁移到现行 `ChatSource`。弃用类本身保留
     （公开 API DTO，1.0 不做破坏性删除；`DtoTest` 中对其的直接测试保留，
     类继续被覆盖）。
  2. 删除零调用方死代码：`RagApiKeyRepository.findFirstByPrincipalIdAndEnabledTrue`、
     `ApiKeyManagementService.validateKeyEntity`（删前 grep 全模块验证无调用方）。
  3. 删除 webui 死代码包装 `documentsApi.batchCreateAndEmbed`（仅定义、无调用，
     指向弃用端点 `/batch/create-and-embed`；后端端点保留为公开 API 兼容）。
- 证据：`mvn clean compile test-compile` BUILD SUCCESS；定向测试
  DtoTest 468 / RagChatControllerTest 32 / ApiKeyManagementServiceTest 12 全绿；
  `verify-no-pessimistic-locks.sh` 通过；`git diff --check` 通过；
  webui `tsc -b` + `test:run` 300/300 + `lint` 绿。

### Batch 6（已交付）

- 分支：`test/backend-unit-hardening-20260905`（基于 Batch 5 分支）
- 内容：为 CI 中因 gated IT 静默跳过的高风险路径补纯单元测试：
  1. `EmbeddingJobExecutorTest`（11 测试）：claim 未取得/任务未知、COMPLETED 全阶段
     推进、stale profile → markStale/markCancelled 分支、commit 门拒绝、CACHED
     直接成功与「CACHED→强制重嵌入升级」递归、provider FAILED、运行时异常的
     错误脱敏 + 500 字符截断、embedding 中途 commit 拒绝。
  2. `CollectionPurgeAuthorizationTest`（9 测试）：功能开关关闭、null request、
     environment root、数据库 ADMIN/NORMAL、principal 缺失、auth-disabled 回退
     的 loopback + 显式 opt-in + legacy key 排除、FORBIDDEN/DISABLED 错误码区分、
     loopback 判定边界（IPv6/非法地址/空值）。
- 证据：`mvn -q clean compile test-compile` 通过；定向套件 EmbeddingJob 扇区
  （Executor 11 / Worker 7 / Dispatch 2 / Service 8）+ CollectionPurge
  （Authorization 9 / ControllerWebTest 3）共 40/40 绿。
- 后续：`DocumentSyncRunService`（1299 行）依赖较多，值得独立一个批次做单测拆解。

### Batch 7（已交付）

- 分支：`fix/webui-behavior-hardening-20260905`（基于 Batch 6 分支）
- 内容：
  1. `useFileUpload` 增加 AbortController：fetch 透传 signal、新增 `cancelUpload()`、
     卸载时中断在途上传；AbortError 归一为 `Upload cancelled` 失败状态。
  2. 新增 `useBlobUrlOpener` hook：集中 Search/Files/Documents 三处重复的
     「popup 打开 blob + 60s 延迟 revoke」模式，卸载时取消未触发的 revoke 定时器。
     Chat.tsx 下载路径本就是即时 revoke，保持不变。
  3. `Settings.tsx` 的 saved 提示定时器改为 ref 持有 + 卸载/重排前清理。
- 证据：`tsc -b` 绿；`test:run` 304/304（43 文件，新增 abort 2 测试 +
  useBlobUrlOpener 2 测试）；`lint`/`build` 绿。

### Batch 8（已实施，等待推送）

- 内容：`.github/workflows/ci.yml` 新增独立 `webui` job（Node 24 + npm cache）：
  `npm ci` → `tsc -b` → `lint`（ESLint + alignment + design-token）→
  `test:run` → `test:coverage`（thresholds 门禁）→ `build` + 上传 coverage 产物。
- gated IT 摸底结论（未开启，留待用户决策）：20+ 个 `*.it.enabled` 门控属性中，
  检索/嵌入相关 IT 依赖真实 EmbeddingModel/ChatModel provider，而 CI 的
  API key 只有 `test-key` 兜底；盲目打开会使 CI 必然失败。可行路径是配置
  本地 mock provider 或提供真实 CI secrets。
- 交付状态：提交 `aa9af630` 位于本地分支
  `ci/webui-job-pending-workflow-scope`。GitHub 拒绝当前 OAuth 凭据推送
  修改 workflow 的提交（缺少 `workflow` scope），需用户以带 scope 的凭据执行
  `git push origin ci/webui-job-pending-workflow-scope` 后方可合并。

### Batch 9（已交付）

- 分支：`refactor/webui-token-fallback-cleanup-20260905`（基于 Batch 7 分支）
- 内容：
  1. 全量移除 src CSS 中冗余的 `var(--token, 颜色)` 颜色回退（169 处，8 个文件）——
     design-token 门禁本就保证引用的 token 必有定义，回退是死重量。
  2. 字面颜色基线从 341 → **172**（-49%，23 个文件）。
  3. `Settings.tsx` 8 个表单控件（2 select + 6 input）补 `htmlFor`/`id` 关联
     （checkbox 为包裹式关联、语言栏为按钮组，无需处理），新增检索 tab 的
     label 关联回归测试。
- 证据：`tsc -b` 绿；`test:run` 305/305（43 文件，+1 测试）；`lint`/`build` 绿。

### Batch 10（已交付）

- 分支：`test/sync-run-service-unit-20260905`（基于 Batch 9 分支）
- 范围调整说明：原计划对 `DocumentSyncRunService` 主链（complete/applyItem）做单测，
  实施前复核发现这些方法是 JdbcTemplate + ResultSet 映射密集型——mock 整套查询
  只会复制 SQL 语义、产生脆弱测试；其真实行为已由 gated IT 与 ControllerWebTest
  覆盖。因此本批改为加固同子系统中真正纯逻辑、且零测试的**游标契约层**：
  1. `DocumentSyncRunItemCursorCodecTest`（7 测试）：roundtrip（含/无状态过滤）、
     seenAt 的 UTC 归一化、run/status 绑定拒绝、null/空白/超长/非法 base64/
     错误版本游标拒绝、externalId 安全规则（空白/填充/控制字符/非 ASCII/超 255）。
  2. `AlertNotificationCursorCodecTest`（5 测试）：同样的 roundtrip/绑定/拒绝语义
     与 UTC 归一化。
- 证据：`mvn -q clean compile test-compile` 通过；两套 12/12 绿。

### Batch 11（已交付）

- 分支：`refactor/webui-token-fallback-cleanup-20260905`（基于 Batch 9 分支）
- 内容（原计划中的 Card/Modal primitive 顺延为 Batch 12）：
  1. global.css 新增 severity/info 面色 token（success/error 的 bg/border/text、
     info 三件套、primary-border，均含明暗两套，全局 token 达 44 个）。
  2. `ApiKeys.module.css`（24→0）与 `Documents.module.css`（24→0）全量 token 化：
     凭证状态徽章、rotate 警示/danger/info 面板、生命周期徽章统一到语义 token；
     两个近似色的 rgba 主色光晕改用 `color-mix`。
  3. `ABTest.tsx`：STATUS_COLORS 状态徽章改用 CSS 变量；分析图表的两个 Bar 填充
     从字面 indigo/green 收敛到 `useChartTheme` 调色板（与 MetricsCharts 同语言）。
- 基线变化：172 → **115**（20 文件，达成 <120 目标）。
- 证据：`tsc -b` 绿；`test:run` 305/305；`lint`（含 design-token 门禁）绿；`build` 绿。

### Batch 12（已交付）

- 分支：`refactor/webui-token-fallback-cleanup-20260905`（与 Batch 9–11 同线叠加）
- 内容：`Files.module.css`（19→0）、`FilePreview.module.css`（16→0）、
  `VersionHistoryModal.module.css`（19→0）全量 token 化。GitHub 风格预览中性色
  （#f6f8fa/#dfe2e5/#6a737d/#0366d6 等）映射到 surface/border/text-muted/primary，
  差异视图红绿底色映射到 severity 面色 token，两个黑色半透明阴影改用
  `color-mix(var(--color-text))` 以随主题变化。diff 高亮的浅黄/浅蓝徽章一并收敛。
- 基线变化：115 → **61**（17 文件，达成 <80 目标；自 Batch 2 起 389 → 61，-84%）。
- 证据：`tsc -b` 绿；`test:run` 305/305；`lint`/`build` 绿；stale-baseline 拦截在
  实施中再次正确触发（FilePreview 清零后未同步基线即被门禁点名）。

### Batch 13（已交付）

- 分支：`refactor/webui-token-fallback-cleanup-20260905`（同线叠加）
- 内容：复核 Evaluation/Documents 的表单 a11y。关键发现：早期扫描按
  htmlFor/aria-label 统计，**漏掉了包裹式 label**——Evaluation 全部 10 个控件
  与 Documents 编辑/relocate 表单本来就是包裹式关联，无需修改。真实缺口仅
  Documents 3 处：搜索框、集合筛选 select 补 `aria-label`（复用现有 key），
  ✕ 清除按钮补可访问名（新增 `documents.clearSearch`，en + zh-CN）。
- 证据：`tsc -b` 绿；`test:run` 305/305；`lint`/`build` 绿；locale 完整性脚本校验
  仅新增 1 key。

### Batch 14（已交付）

- 分支：`refactor/webui-card-primitive-20260906`（基于 Batch 13 所在线）
- 内容：新增 `components/Card` primitive（div 皮肤：surface 底 + border + 8px 圆角
  + 1rem padding，className 合并、rest 透传），迁移 Dashboard（4）、Collections（2）、
  Evaluation（1）、Skeleton（1）共 8 处 `.card` 复制粘贴；Skeleton 的 flex 布局
  拆为页面本地 `.layout` 类，皮肤统一走 primitive。Dashboard 20px / Evaluation
  0.75rem 的 padding 差异有意统一为 1rem（设计系统收敛）。
- 证据：`tsc -b` 绿；`test:run` 307/307（44 文件，新增 Card 2 测试）；
  `lint`/`build` 绿；页面既有测试全部无修改通过。

### Batch 15（已交付）

- 分支：`refactor/webui-small-debt-sweep-20260906`（基于 Batch 14 分支）
- 内容：
  1. **修复 Batch 14 的跨文件回归**：`Embeddings.tsx` 一直在借用
     `Evaluation.module.css` 的 `.card`，Batch 14 删除该类后其卡片皮肤丢失
     （既有冒烟测试未兜住视觉回归）——已迁移到 `Card` primitive，并审计确认
     无其他跨页 CSS 借用。
  2. 剩余 61 处字面颜色全部清零：severity/中性色收口到语义 token；
     8 处黑色半透明阴影统一到新 `--shadow-color` token（暗色主题加深到 0.5，
     阴影从此随主题变化）；聚焦环/光晕用 `color-mix(var(--color-error))`；
     三处 `#fff` 白字改用 `white` 关键字（与 Button primitive 一致）；
     App.tsx 删除最后的 var() 颜色回退；ApiKeys.tsx 内联色改 CSS 变量。
- 基线变化：61 → **0**（全局 token 45 个）。自 Batch 2 门禁落地以来
  389 → 0，**-100%**；`design-token-color-baseline.json` 保留为空对象，
  任何未来字面颜色将直接被门禁拒绝。
- 证据：`tsc -b` 绿；`test:run` 307/307（44 文件）；`lint`/`build` 绿；
  门禁输出 `0 file(s) with grandfathered literal colors`。

### Batch 16（已交付）

- 分支：`refactor/webui-small-debt-sweep-20260906`（与 Batch 15 同线叠加）
- 内容：`DocumentSyncRunService` 两个超长方法的行为保持拆分（纯私有方法提取，
  无逻辑/SQL/错误消息变更）：
  1. `complete`（~100 行 → 20 行编排 + 6 个聚焦 helper）：requireMatchingPreviewToken、
     requireNoFailedItemsForTombstone、requireUnchangedPreview、
     reconcileMissingCandidates（含 requireMissingCountWithinThreshold）、
     markRunCompleted、completeInTransaction。
  2. `applyItem`（~110 行 → 30 行编排 + 5 个 helper）：replayOrReopenExistingItem
     （返回可重放行或 null 继续）、reopenFailedItem、insertInProgressItem、
     applySyncMutation、finalizeItemLedger。
- 回归护栏（关键）：本地 Docker 可用，gated IT 可用
  `TESTCONTAINERS_RYUK_DISABLED=true TESTCONTAINERS_PG_IMAGE=postgres:16-pgvector`
  跑真实 PostgreSQL（Testcontainers 拉取 ryuk 受境内网络限制，禁用后正常）。
  重构前先跑通基线（4/4），重构后复跑同样全绿。
- 证据：`mvn clean compile test-compile` 绿；DocumentSyncRunsPostgresIntegrationTest
  4/4（真实 PG + Flyway 全量迁移）；DocumentSyncRunControllerWebTest 5/5；
  OpenAPI 契约测试绿。
- 附注：Testcontainers 需要的本地运行参数已验证，可写入 china-network-guide
  供后续 IT 回归使用（留待文档批次）。

### Batch 17（已交付）

- 分支：`refactor/webui-small-debt-sweep-20260906`（同线叠加）
- 内容：
  1. **Modal 外壳统一复核结论（原 Batch 17 计划为空操作）**：CreateCollectionModal
     与 VersionHistoryModal 早已使用共享 `Dialog` 组件，初版扫描所称的
     「各自 overlay/panel 重复」不成立于当前代码，无需改动。
  2. Embeddings 卡片渲染回归测试（原 Batch 18）：readiness mock 补齐 +
     `?collectionKey=` 启用查询，断言 5 个统计值渲染且落在哈希 Card 皮肤类
     `_card_*` 内—— precisely 防住 Batch 15 发现的「跨文件样式借用静默丢失」回归类。
- 证据：`tsc -b` 绿；`test:run` 308/308（44 文件，+1 回归测试）；`lint`/`build` 绿。

### Batch 18（已交付）

- 分支：`refactor/purge-service-split-20260906`（基于 Batch 17 分支）
- 内容：`CollectionPurgeService.applyTransaction`（~140 行 → 20 行编排 + 9 个
  聚焦 helper，行为保持）：requirePreviewApplicable、claimApplyLease、
  fenceCollection、requireUnchangedPlan、deletePurgeTargets、retireCollection
  （编排 markCollectionRetired / buildRetiredResult / completePurgePreview /
  writePurgeAuditLog）。无逻辑/SQL/错误消息变更。
- 回归护栏：重构前 `collection-purge.it.enabled` 基线 5/5（Testcontainers 真实
  PostgreSQL），重构后复跑 5/5；CollectionPurgeControllerWebTest 3/3；
  `mvn clean compile test-compile` 绿。
- 至此两处最高风险超长方法（DocumentSyncRunService + CollectionPurgeService）
  均在 gated IT 护栏下完成拆分。

### Batch 19（已交付）

- 分支：`refactor/embeddings-own-styles-20260906`（基于 Batch 18 分支）
- 内容：`Embeddings.tsx` 不再借用 `Evaluation.module.css`——新建自有
  `Embeddings.module.css`（13 个类，其中 `sectionHeader` 在被借模块里本就缺失、
  属于第二处静默样式缺口，本次一并补齐；选择器组改写为仅含所需类）。
  跨页 CSS 借用清零。
- 证据：`tsc -b` 绿；`test:run` 308/308（含 Batch 17 的卡片皮肤回归测试）；
  `lint`/`build` 绿。

### Batch 20（已交付）

- 分支：`refactor/embeddings-own-styles-20260906`（同线叠加）
- 内容：`china-network-guide-zh-CN.md` / `china-network-guide.md` 新增
  「直接运行 gated PostgreSQL 集成测试」小节：`TESTCONTAINERS_RYUK_DISABLED` +
  `TESTCONTAINERS_PG_IMAGE=postgres:16-pgvector` + `-D<prefix>.it.enabled=true`
  的完整命令、开关命名规则与「基线/复跑必须一致」的重构纪律。文档门禁 11/11。
- 至少一个可复制路径现已存在，后续后端重构批次都能用真实 PostgreSQL 护栏。

### Batch 21（已交付）

- 分支：`refactor/purge-service-split-20260906`（同线叠加）
- 内容：
  1. `ChatTurnOperationService.claim`（~160 行 → 25 行分派 + 5 个聚焦 helper）：
     claimExisting（幂等分派）、reclaimExisting、claimNew、withEffectiveSession、
     insertNewOperation。会话租约参数由局部变量改为 `effectiveCommand.sessionId()`
     （同一值），无逻辑/错误消息变更。
  2. **顺带修复 gated IT 的预存缺陷**：
     `ChatTurnOperationPostgresIntegrationTest` 硬编码「最新迁移 = 58」，V59 落地后
     该 IT 即使被开启也必然失败（平时跳过从未暴露）。改为动态断言
     「成功迁移链已执行到已安装的最新版本」，之后新增迁移不再破坏该测试。
- 证据：`mvn clean compile test-compile` 绿；ChatTurnOperationServiceTest 9/9；
  ChatTurnOperationPostgresIntegrationTest 7/7（Testcontainers 真实 PG）；
  `verify-no-pessimistic-locks.sh` 通过。

### Batch 22（已交付）

- 分支：`refactor/chat-execution-split-20260906`（基于 Batch 21 分支）
- 内容：`ChatExecutionService` 生产 Chat 主链的行为保持拆分：
  1. `execute`（~135 行）与 `prepareForOperation`（~110 行）的候选循环收缩为
     编排器（各 ~30 行），共享的「预算化单候选调用」提取为唯一的
     `candidateInvocation`（消除两份 30 行的逐字重复）+ `withRetries` 重试包装；
  2. execute 成功收尾提取为 `commitExecutedTurn`（协调器提交/持久化、摘要压缩、
     预算元数据、指标与 fallback 日志）；prepareForOperation 成功收尾提取为
     `buildPreparedExecution`（只组装、不提交任何持久状态，Javadoc 契约不变）；
  3. 失败路径统一为 `recordCandidateFailure`（指标 + 参数化标签日志，两个原日志
     前缀 "Chat candidate"/"Durable Chat candidate" 保持不变）。
- 护栏：重构前 `ChatExecutionServiceTest` 基线 20/20，重构后复跑 20/20；
  下游 `ChatTurnOperationServiceTest` 9/9；`verify-no-pessimistic-locks.sh` 通过；
  `mvn clean compile test-compile` 绿。

### Batch 23（已交付）

- 分支：`refactor/chat-execution-split-20260906`（同线叠加）
- 内容：
  1. 主流程 Mock Playwright 覆盖评估结论：search（10 用例：scope 三模式、分页
     跨页选择、移动端溢出、结果恢复、URL 重载恢复、鉴权 PDF 原文）与 chat
     （10 用例：SSE body、AGENT 工具活动、PLAIN 无检索、会话寻址恢复、幂等
     冲突、部分流重放、stop 中断）覆盖扎实，仅一处缺口——`hybrid` 开关
     （keyword-only，且属 URL 可寻址契约）无请求断言，已补
     「关闭 hybrid → 请求 `useHybrid=false` + URL `hybrid=false`」用例。
  2. 发现并定位一个**预存环境性 flake**：search 分页用例在 Vite 冷编译窗口的
     全量跑中可能出现 checkbox 连续 detach 超时（隔离必过、预热后全量也过，
     单 worker 11/11）；spec 内已留 KNOWN FLAKE 标记，根因调查另立批次。
     本批未触碰任何应用代码，可排除本批引入。
- 证据：`tsc -b` 绿；`test:run` 308/308；search+chat e2e 20/20；`lint`/`build` 绿；
  新 hybrid 用例在多次全量与单测中稳定通过。

### Batch 24（已交付）

- 分支：`refactor/chat-execution-split-20260906`（脚本部分）+
  `ci/webui-job-pending-workflow-scope`（CI 步骤部分，仍待用户推送）
- 内容：
  1. 评估结论修正：并非所有 gated IT 都需要真实模型 provider——本循环已验证的
     三个套件（DocumentSyncRuns / CollectionPurge / ChatTurnOperation）是**纯
     DB 型**，Testcontainers PostgreSQL 即可全绿，无需 mock provider。这直接
     解锁了 CI 接入的可行路径。
  2. 新增 `scripts/verify-gated-it.sh`：一键回归上述纯 DB 型 gated IT 矩阵
     （默认禁 Ryuk + 本地 postgres:16-pgvector 镜像，均可环境变量覆盖；
     支持按类名筛选与套件登记制）。验证：全量 16/16（4+5+7），单套件筛选、
     无匹配错误路径均正确。
  3. CI 步骤（运行同一脚本）已提交到待推送的 workflow 分支，且脚本本体也已
     cherry-pick 到该分支使其自包含（0aeebb90）；与其 webui job 一起等待用户
     以 workflow scope 凭据执行
     `git push origin ci/webui-job-pending-workflow-scope`。检索/嵌入类 IT 仍需 mock
     provider 或真实 secrets，维持独立规划。
- 证据：脚本 `bash -n` 通过；`verify-gated-it.sh` 全量 16/16、单套件 4/4、
  无匹配退出路径正确。

### Batch 25（已交付）

- 分支：`fix/search-pagination-flake-20260906`（基于 Batch 24 分支）
- **根因结论**：失败瞬间的页面快照（error-context.md）显示列表仍停在第一页——
  Next 的点击落在模式切换后取数窗口内的列表重挂载上，被静默吞掉（React 合成
  事件未触发，Page Two Target 从未出现）。属测试交互时机问题，非应用逻辑缺陷。
- 修复：切换 scope 后先等 Page One 复选框可见再点 Next；点 Next 后等
  Page Two Target 可见再勾选。无应用代码变更。
- 证据：search spec 连续 3 次全量 11/11（修复前每次全量必挂）；排除 `-real`
  用例的全套件 89/89（4 个 `*-real` 失败为预期——需真实后端；连带干扰的
  workspace-continuity 在隔离与排除 `-real` 后均复绿）；`tsc -b`/`lint`/`build` 绿。
- 经验：跑 Vite dev server 上的 e2e 期间编辑被服务的源文件会引入 HMR/整页
  reload 干扰（vite log 13 次 page reload 可证），应避免。

### Batch 26（已交付）

- 分支：`refactor/controller-long-methods-20260906`（基于 Batch 25 分支）
- **盘点结果**：RagDocumentController 最长 processUploadedFile 69 / batchEmbedDocuments 68 /
  listDocuments 67；PdfImportController 最长 streamPdfToRagWithEmbedding 51 /
  importPdfToRagWithoutEmbedding 50。SSE 流方法（闭包 + 虚拟线程结构）与注解密集型
  端点拆分收益低，保留原样。
- 拆分（行为保持，纯私有方法提取）：
  1. `processUploadedFile`：提取 buildUploadDocumentRequest /
     createViaMutationService / createViaBatchService（实施中 review 发现并修正了
     embeddingPolicy 未透传的提取错误）；
  2. `batchEmbedDocuments`：提取 requireValidBatchIds / embedBatchAsync /
     embedBatchSync；
  3. `listDocuments`：提取 searchDocumentsForCaller（ACL 分支）/ collectionMetadata
     （批量取集合名防 N+1）/ toDocumentSummaries；
  4. PdfImport `importPdfToRagWithoutEmbedding`：提取 importMarkdownWithoutEmbedding。
- 证据：`mvn -q clean compile test-compile` 绿；护栏 RagDocumentControllerTest 44/44、
  PdfImportControllerTest 34/34、DocumentLifecycleWebTest 4/4、DocumentAclTest 3/3、
  ExternalDocumentWebTest 6/6；`verify-no-pessimistic-locks.sh` 通过。

### Batch 27（已交付）

- 分支：`test/webui-thin-coverage-20260906`（基于 Batch 26 分支）
- **盘点结论**：全项目覆盖率最低的区块是 `src/api/*` 契约层（0–25%，此前仅
  apikeys/collections 有测试）。API 包装层是最便宜的契约锁——mock apiClient
  断言端点/方法/参数即可固化对外 HTTP 契约。
- 补齐 7 个模块的契约测试（13 个）：models（list）、health（无参读取）、
  auth（凭据只进 header 不进 URL）、chat（POST body、会话 id URL 编码、
  blob 导出、delete 清史）、search（hybrid/scope 参数 + 可选参数省略 +
  `indexes: null` 序列化器）、embeddings（job 过滤、生命周期动作、readiness
  按 collectionKey）、metrics（三个读取端点）。
- 证据：`test:run` 全绿（308 → 321）；`tsc -b`/`lint`/`build` 绿。

### Batch 28（已交付）

- 分支：`fix/search-pagination-flake-20260906`（同线叠加）
- 内容：
  1. **排查上轮 dev 栈被 kill 的原因**：不是 OOM（64GB），而是 (a) 工具沙箱在
     命令结束时清理 `nohup &` 派生的进程组；(b) 后台 shell 无 node PATH。用
     `run_in_background` + 显式 PATH 后 dev 栈稳定启动（backend 18082 与
     frontend 15173 均 200）。
  2. `-real` 用例实测与需求清单化：api-key-real 通过（RAG_ROOT_API_KEY）；
     alerts-real 需预置 ALERT_DELIVERY_EXPECTED_ALERT_ID/DELIVERY_ID；
     chat-real 需 /models 暴露可用 tool-calling 模型；files-real 依赖真实
     provider 时延（慢则 504）；rerank-real 需 RERANK_DIVERSITY_FIXTURE_FILE。
  3. 运行手册写入 testing-guide 双语（WebUI `-real` Runbook 小节）。
- 证据：dev 栈健康检查 200×2；api-key-real 1/1 通过；文档门禁 11/11。

### Batch 29（已交付）

- 分支：`test/api-contracts-rest-20260906`（基于 Batch 28 分支）
- 内容：补齐剩余 API 契约测试 4 个模块 9 个测试——documents（list 过滤、
  生命周期 mutation 携带 expectedDocumentRevision、batch/embed-status）、
  files（tree 根/子目录路径、preview/raw 端点）、evaluation（report/聚合/
  evaluate/answer-quality/feedback）、alerts（active/history/fire/resolve/
  silence）。
- 覆盖率变化：src/api 全模块有契约测试（此前 11 个 0 覆盖）。
- 证据：`tsc -b` 绿；`test:run` 330/330（55 文件，+9）；`lint`/`build` 绿。

### Batch 30（已交付）

- 分支：`test/api-contracts-rest-20260906`（同线叠加）
- 内容：**chat-real 首次真实跑通**（~2 分钟，AGENT SSE + tool-calling + 历史恢复全链路）。
  排查路径与关键发现：
  1. legacy openai provider（.env 的 SPRING_AI_OPENAI_*）指向的 openai-next 端点已失效
     （503 no_available_account），是此前 504 的直接原因；
  2. curl 实测确认 SiliconFlow + Qwen/Qwen3.5-27B 返回规范 function call；
  3. 采用外部 models.json（`MODELS_CONFIG_FILE`，.dev/ 下 gitignored）配置
     siliconflow provider：Qwen3.5-27B（toolCalling: true）+ BGE-M3 embedding；
  4. **踩坑**：JSON 字段名必须 camelCase（`chatModel`），误写 `chat-model`
     会静默不生效回退 legacy（providers 部分生效而 routing 失效的混合状态很迷惑）；
  5. 修正后 /models 暴露 `available: true + toolCalling: true`，PLAIN chat 8.6s 正常返回。
- 运行手册双语更新：chat-real 标记已跑通、files-real 标注 chat-ask-ms=120s 约束、
  models.json 配置要点与两个坑。文档门禁 11/11。
- 遗留：files-real 仍受模型速度限制（KNOWLEDGE 长上下文 >120s），换更快模型或
  调大 chat-ask-ms 可解，留待需要时处理。

### Batch 31（已交付）

- 分支：`fix/models-json-schema-guard-20260906`（基于 Batch 30 分支）
- 内容：`MultiModelConfigLoader` 加载外部 models.json 时增加 schema 校验
  （`validateSchema`）：
  1. **键含连字符（kebab-case 误用，如 chat-model）→ 抛 IllegalStateException
     阻止启动**，错误消息列出完整路径并给出 camelCase 建议（fail-closed：
     静默回退 legacy 正是 Batch 30 排查的 504 事故根因）；
  2. 未知键 → WARN 列出完整路径，不阻断加载（向后兼容）；
  3. 实现：readTree 后递归收集问题，再 treeToValue；KNOWN_KEYS 集合为合法键全集。
- 测试：MultiModelConfigLoaderTest 追加 2 个（kebab-case 抛异常且 YAML 配置保持
  不被替换；未知键 WARN 且正常加载，用 ListAppender 断言日志）。21/21。
- 真实端到端验证：向 .dev/models.json 注入 `"chat-model"` → dev.sh 启动失败，
  后端日志出现「uses kebab-case keys ... (did you mean 'chatModel'?)」；恢复
  camelCase 后 dev 栈恢复正常且 default 模型正确。
- 证据：`mvn -q clean compile test-compile` 绿；MultiModelConfigLoaderTest 21/21；
  dev 栈故障注入/恢复两端验证；锁扫描不涉及（无数据访问改动）。

### Batch 32（已交付）

- 分支：`test/component-interaction-depth-20260906`（基于 Batch 31 分支）
- **盘点结论**：SearchResults 的溯源回调（onViewDirectory/onViewIndexedFile/
  onOpenOriginalFile 的参数断言）既有测试已覆盖，不缺；真正缺口是
  **VersionHistoryModal 的版本恢复交互**——canRestore 门控矩阵（FULL 快照、
  非 externallyManaged、documentRevision 存在、onRestoreVersion 提供）、
  restorePending 禁用态、版本号参数传递全部未测，而恢复是有副作用的写操作。
- 补 5 个门控矩阵测试（VHM 8 → 13）。
- 证据：`tsc -b` 绿；`test:run` 330 → 335；`lint`/`build` 绿。

### Batch 33（已交付）

- 分支：`test/component-interaction-depth-20260906`（同线叠加）
- 内容：multi-model-external-config 双语文档新增「键名校验」小节：camelCase
  强约束、kebab-case fail-closed（含真实事故复盘：providers 生效而 routing
  静默回退 legacy → 504）、未知键 WARN 语义。与 Batch 31 的校验代码配套。
  文档门禁 11/11（顺带修复 ZH 文档 EOF 多余空行）。

### Batch 34（已交付）

- 分支：`audit/chat-turn-medium-methods-20260906`（基于 Batch 33 分支）
- **审计结论**：`inspectExisting`（27 行）与 `replay`（25 行）结构紧凑、无需拆分；
  真正缺口是测试覆盖——`replay` 此前**零单测**（含「损坏快照 → INTERNAL_ERROR」
  与授权失败透传两个安全相关分支），`inspectExisting` 仅覆盖 in-progress 分支。
- 补 6 个分支测试：replay 拒绝非 replay/null claim、反序列化存储快照并盖 turnId、
  损坏快照转 INTERNAL_ERROR、授权失败原样透传；inspectExisting 无 keyed
  operation 返回 null、SUCCEEDED 返回 replay claim 并计数。
  Claim 构造器私有，测试经公共 `claim()` + mocked repository 获得 replay claim。
- 证据：`mvn -q clean compile test-compile` 绿；ChatTurnOperationServiceTest
  9 → 15 全绿；gated IT（chat.idempotency）7/7；`verify-no-pessimistic-locks` 通过。

### Batch 35（已交付）

- 分支：`test/component-interaction-depth-20260906`（同线叠加）
- 内容（防呆落地为脚本级，非仅文档）：
  1. 新增 `playwright.preview.config.ts` + `npm run test:e2e:mock`：Mock e2e
     跑在 `vite preview`（生产构建，端口 15174，自动起停）上——无文件 watch，
     「e2e 期间编辑源文件」结构性免疫；排除需真实后端的 `-real` 用例。
  2. 双语 testing-guide 更新：推荐 preview 方式。
- **重要发现（超出本批预期）**：preview（生产构建）下
  `workspace-continuity › Chat, Search, and Files restore...` **稳定失败**
  （复跑两次均失败；dev server 下 6/6）——从其它页面返回 Chat 后
  `mode=AGENT` 丢失（draft 恢复了、mode 没恢复），违反「可寻址状态进 URL」
  契约。这是 dev 时序掩盖的生产构建真实缺陷，非 flake，立 **Batch 36 修复项**
  （优先级提升：真实用户在生产会遇到）。
- 证据：`test:e2e:mock` 88/89（唯一失败即上述生产缺陷）；tsc/lint/build 绿
  （本批未改应用代码）。

### Batch 36（已交付）

- 分支：`fix/chat-mode-restore-production-20260906`（基于 Batch 35 分支）
- **根因**：Layout 导航链接的 `href` 在渲染时从 sessionStorage 读取
  （`rememberedRoute`），但 `rememberRoute` 的写入发生在 effect 里且不触发
  Layout 重渲染——链接 href 永远是「上一次渲染时」的快照。测试点击 Chat 链接
  时 href 还是 `/webui/chat`（无 mode=AGENT），导航后 mode 丢失。dev 与
  preview 的差异只是渲染时序窗口不同。
- 修复：Layout 增加 `routeMemoryVersion` state；`rememberRoute` effect 写入
  sessionStorage 后 bump，使导航链接 href 立即重读最新路由记忆。
- 验证：调试 spec 抓现场（sessionStorage 正确而 href 过期）→ 修复后
  `test:e2e:mock`（preview 生产构建）**90/90 全绿**（含此前稳定失败的
  workspace-continuity）；dev server 下 workspace-continuity + search
  16/16 无回归；单测 335/335；`tsc -b`/`lint`/`build` 绿。

### Batch 37（已交付）

- 分支：`test/turn-op-remaining-branches-20260906`（基于 Batch 36 分支）
- 内容：`commandForClaim` 补齐剩余分支测试（15 → 17）：
  1. `commandForClaimPassesThroughNullAndUnkeyedClaims`：null command /
     null claim / unkeyed claim 三种透传（身份断言）；
  2. `commandForClaimAdoptsTheDurableSessionIdFromTheOperation`：durable
     operation 的 sessionId 覆盖调用方 sessionId，同时快照候选链生效——
     既有测试两值相同，无法证明 withSessionId 真正生效，此处补齐。
- 证据：`mvn -q clean compile test-compile` 绿；ChatTurnOperationServiceTest
  17/17；`verify-no-pessimistic-locks.sh` 通过。

### Batch 38（已交付）

- 分支：`test/layout-route-memory-20260906`（基于 Batch 37 分支）
- 内容：Layout 路由记忆 href 同步（Batch 36 修复）补 2 个 Vitest 回归单测：
  1. 同路由内 query 变化（/chat → /chat?mode=AGENT）后，导航链接 href 必须
     立即更新——精确锁定 Batch 36 缺陷；负向验证：临时撤销 routeMemoryVersion
     bump 后该测试失败、恢复后通过，证明锁定有效；
  2. 预先 rememberRoute 的 query 会反映到导航链接 href。
- 证据：`test:run` 335 → 337（55 文件）；`tsc -b`/`lint`/`build` 绿。

### Batch 39（已交付）

- 分支：`audit/medium-method-map-20260906`（基于 Batch 38 分支）
- 内容：controller/service/chat 全量扫描（方法 ≥60 行），**剩余 15 个**，
  已在 Batch 22/26/30 拆掉 ChatExecution/ChatTurnOp/SyncRun/Purge 四处大头。
  **终态地图（Batch 47 收敛，2026-09-06）**：

  | 方法 | 行数 | 终态 | 依据 |
  |---|---|---|---|
  | DocumentLifecycleService.read | 160 | **已拆**（Batch 40） | 护栏 7/7 先行，deriveFromStateRow 纯函数化 |
  | DerivationRepairService.apply | 87 | **已拆**（Batch 45） | 决策矩阵 11/11 + gated IT 10/10 前后一致 |
  | PdfImportService.importPdf | 144 | **已拆**（Batch 43） | Fake converter 护栏 6/6，collectOutputRecords 纯静态 |
  | ExternalDocumentService.persistInTransaction | 104 | **不拆**（Batch 44） | 专属单测已直接驱动主链；补 JSON-record 守卫 3 测 |
  | LegacyEmbeddingMigrationService.adoptDocument | 82 | **不拆**（Batch 46） | 直线守卫链是正确形态；gated IT 实证护栏可用 |
  | DerivationIntegrityRepository.classificationQuery | 148 | 不拆 | SQL 文本主体，拆分无收益 |
  | DerivationRepairService.preview / applyVectorPhase | 68/64 | 不拆 | 决策核心已提取（plan 矩阵 11/11），主体为编排 |
  | RagCollectionService.cloneCollection | 87 | 待评估（低优） | 页面/接口测试间接覆盖 |
  | KeywordIndexPersistenceService.ensureCurrent | 82 | 待评估（低优） | 无专属测试 |
  | DocumentRelocationService.reserve | 67 | 待评估（低优） | ExternalDocumentWebTest 间接 |
  | DocumentSyncRunService.findCandidates | 76 | 不拆 | gated IT 覆盖（Batch 25 flake 修复后已验证） |
  | ChatHistoryCleanupService.cleanupSession | 76 | 不拆 | ChatHistoryCleanupTest 覆盖 |
  | IntegrationCapabilityCatalog.describe | 74 | 不拆 | 契约测试覆盖 |
  | RagChatToolRegistry.validateAndFreeze | 66 | 不拆 | 间接覆盖充分 |

  收敛结论：4 个已拆（各配护栏）、5 个有据不拆、3 个低优先待评估、3 个已足。
  审计闭环完成；「待评估」三项不阻塞任何当前工作。

- 证据：静态扫描脚本输出（本批未改应用代码，无需应用门禁）。

### Batch 40（已交付）

- 分支：`test/lifecycle-read-guard-20260906`（基于 Batch 39 分支）
- 内容（按地图最高项执行「先护栏后拆分」）：
  1. 新增 `DocumentLifecycleServiceTest`（7 测试）：disabled/tombstoned 分支、
     状态行缺失 → NOT_REQUESTED、全 current → READY（无错误码）、
     local READY + embedding QUEUED → KEYWORD_ONLY/INDEXING、embedding FAILED →
     EMBEDDING_FAILED 错误码、local_error → LOCAL_INDEX_FAILED、
     integrityRepository 存在时走 fromIntegrity 路径。
  2. `read`（160 行）行为保持拆分：状态推导提取为纯静态
     `deriveFromStateRow`（返回 DerivedLifecycle record），内部分解为
     deriveLocalStatus / deriveEmbeddingStatus / deriveSearchability /
     firstPresentError / deriveErrorCode。read 主体缩至 ~20 行。
- 证据：`mvn -q clean compile test-compile` 绿；护栏 7/7（拆分前后同绿）；
  DocumentLifecycleControllerWebTest 4/4；`verify-no-pessimistic-locks.sh` 通过。

### Batch 41（已交付）

- 分支：`test/lifecycle-read-guard-20260906`（同线叠加）
- **取舍结论**：选择放宽本地超时（方案 B）而非换快模型（方案 A）——方案 A
  会把 models.json 的 primary 从实测质量良好的 Qwen3.5-27B 换成 9B，
  牺牲 chat-real 已验证的质量；方案 B 只放宽本地 dev 环境的
  `rag.timeout.chat-ask-ms`（`.env` 设 `RAG_TIMEOUT_CHATASKMS=300000`，
  relaxed binding 实测生效），不动共享配置。
- 实测结果：files-real **通过**（1.9 分钟）；`-real` 套件 3/5 通过 +
  2 个预期失败（alerts-real 缺预置告警 id、rerank-real 缺 fixture 文件，
  均为运行手册已文档化的环境前置）。
- 证据：dev 栈健康 200；运行手册双语更新 files-real 行；文档门禁 11/11。

### Batch 42（已交付）

- 分支：`test/derivation-repair-guard-20260906`（基于 main@e8a6ebe2，即含 CI
  合并后的最新 main；用户已解决凭据并推送 main，凭据阻塞解除）
- 内容：
  1. `DerivationRepairServiceTest` 新增 11 个纯决策单测：`plan(Snapshot)` 的
     action 判定矩阵（tombstoned/disabled/INDEXING bucket 不修、local+vector
     全重建、仅 local、仅 vector、vector INDEXING 视为新鲜）、
     `validateSelection` 白名单（空/非法/合法）、`upperSet` 归一化。
     `plan`/`validateSelection`/`upperSet`/`PlanItem` 放宽为包可见供同包直测；
     主链（preview/apply 的 JDBC 编排）维持 gated IT + WebTest 护栏，不做脆 mock。
  2. **修复第二个预存 IT 缺陷**：`NextHighValueFeaturesPostgresIntegrationTest`
     同样硬编码「最新迁移 = 58」（Batch 31 所修缺陷的兄弟实例），改为动态断言
     迁移链执行到最新版本。
- 证据：`mvn -q clean compile test-compile` 绿；DerivationRepairServiceTest
  11/11；gated IT（next-high-value）10/10（修复前 9/10 因旧断言失败）；
  `verify-no-pessimistic-locks.sh` 通过。

### Batch 43（已交付）

- 分支：`test/pdf-import-guard-20260906`（基于 main@df5f797b）
- 内容（按地图推进 PdfImportService.importPdf，144 行）：
  1. 新增 `PdfImportServiceTest`（6 测试）：Fake converter 驱动完整管线
     （无需真实 PDF/CLI）——happy path（original + entry md + image 的
     records 装配、batch 保存、临时目录清理断言）、disabled 快速失败、
     无可用 converter、convert 失败透出 converter 名、缺 source/ 目录、
     多入口 Markdown 拒绝。
  2. 行为保持拆分：records 装配提取为纯静态 `collectOutputRecords`
     （入口 Markdown 恰好一个、路径去重、mimeType 回退），importPdf 缩至
     ~60 行（try/finally 编排）。
- 证据：`mvn -q clean compile test-compile` 绿；护栏 6/6（拆分前后同绿）；
  PdfImportControllerTest 34/34；`verify-no-pessimistic-locks.sh` 通过。

### Batch 44（已交付）

- 分支：`audit/external-persist-tx-20260906`（基于 main@df5f797b）
- **审计结论（修正地图）**：`ExternalDocumentService.persistInTransaction`（104 行）
  的行为已被专属单测 `ExternalDocumentServiceTest`（9 测试，直接驱动 upsert 主链）
  + WebTest + gated IT 三层覆盖——Batch 39 地图标注「仅 WebTest 间接」系低估，
  已修正。104 行中近半为直线参数归一序列，拆分收益低，**不拆分**。
- 实质产出：补齐 persistInTransaction 的两个 **JSON-record 守卫分支**测试
  （此前零覆盖）：`documentType=json-record` 拒绝（且不落库）、外部身份已属于
  JSON record 的冲突拒绝；外加 blank documentType 归一为 text 的持久化断言。
- 证据：`mvn -q clean compile test-compile` 绿；ExternalDocumentServiceTest
  9 → 12 全绿；`verify-no-pessimistic-locks.sh` 通过。

### Batch 45（已交付）

- 分支：`refactor/repair-apply-split-20260906`（基于 Batch 44 分支）
- 内容：`DerivationRepairService.apply`（87 行）行为保持拆分——编排器收缩至
  ~18 行，提取 5 个聚焦 helper：requirePreviewIdentity、requirePreviewNotExpired、
  claimApplyLease（返回租约哈希或 null）、handleUnclaimedPreview、
  processPlannedItems（三阶段逐项 + failItem）、finishApply。
  无逻辑/SQL/错误消息变更。
- 护栏（基线→拆分后一致）：决策矩阵单测 11/11；DerivationRepairControllerWebTest
  1/1；gated IT 10/10 前后一致；`mvn -q clean compile test-compile` 绿；锁扫描通过。

### Batch 46（已交付）

- 分支：`audit/legacy-migration-disposition-20260906`（基于 Batch 45 分支）
- **处置结论：不拆分、护栏已足**。`LegacyEmbeddingMigrationService.adoptDocument`
  （82 行）是直线守卫链（索引连续性 → 维度校验 → 目标行冲突 → content_hash
  初始化 → 原子认领 + 计数验证），每步「校验不过即 return false」——拆分只会
  破坏守卫序列的整体可读性；且属显式确认（`ADOPT_CONFIRMATION`）的一次性
  迁移运维代码，生命周期有限。
- 护栏实证：`EmbeddingProfilePostgresIntegrationTest`（gated，外部 JDBC URL
  模式，一次性 postgres:16-pgvector 容器）7 个用例中 adoptLegacy 认领/拒绝
  路径全部通过。已知限制：vector 检索用例在裸 pgvector 容器上因环境差异失败
  （该 IT 设计依赖完整扩展与 fixture），与本处置无关，已记录。
- 证据：`EmbeddingProfilePostgresIntegrationTest` 实测（adoptLegacy 用例绿）；
  本批无应用代码改动。

### Batch 48（已交付）

- 分支：`test/coverage-driven-round2-20260906`（基于 main@50a790c4）
- 内容（数据驱动盘点：coverage JSON 找出非 api 层最薄弱文件）：
  Embeddings 页深度交互——6 张派生统计卡片渲染断言、job 行 cancel/retry
  动作经真实 react-query mutation 断言 api 调用、派生修复
  preview → dialog → apply 全流程测试。共 +3 测试。
- 证据：`test:run` 337 → 340（55 文件）；`tsc -b`/`lint`/`build` 绿。

### Batch 49（已交付）

- 分支：`test/alerts-deep-interactions-20260906`（基于 main@21451d85）
- 内容：Alerts 测试迁移到**真实 react-query + mock alertsApi 层**（沿用
  Embeddings 惯例，替代 mockUseQuery），7 → 10 测试：
  - 迁移：page title、loading（真实 pending promise）、empty、alert items、
    invalid firedAt fallback、deliveries 模式（retry 动作改为断言
    `retryNotificationDelivery('delivery-1')`）、direct/empty ledger 区分；
  - 新增：SLO configs tab 渲染（latency-p99 配置 + 创建按钮）、
    SLO 配置删除（`deleteSloConfig('latency-p99')` 断言）、
    silence schedules tab 渲染（weekend-maintenance + 创建按钮）。
- 证据：`tsc -b` 绿；`test:run` 340 → 343（55 文件）；lint/build 绿
  （随 Batch 41 收尾提交一并验证的 runbook 更新包含在内）。

### Batch 50（已交付）

- 分支：`test/documents-deep-interactions-20260906`（基于 Batch 49 分支）
- 内容：新增 `Documents.interactions.test.tsx`（独立文件——vi.mock 为文件级，
  与既有全 mock react-query 的 Documents.test.tsx 隔离）：
  1. **disable 确认链**：行菜单 → ConfirmDialog（documents.disableConfirm）→
     确认 → `documentsApi.disable(1, 3)`（revision 透传断言）；
  2. **版本历史模态**：行菜单 → versions.button → VersionHistoryModal 打开
     （dialog 含 versions.title 与 Local Doc，getVersions mock 驱动）。
  真实 react-query + mock api 层（Documents 惯例延续 Embeddings 模式）。
- 证据：`tsc -b` 绿；`test:run` 345/345（56 文件，+2）；`lint`/`build` 绿。

### Batch 51（已交付）

- 分支：`test/alerts-form-flows-20260906`（基于 Batch 50 分支）
- 内容：Alerts 新增 2 个表单提交流程测试（真实 react-query mutation）：
  1. SLO 创建表单：填 sloName + targetValue + 切 AVAILABILITY → 提交断言
     `createSloConfig` 收到解析后的数字 targetValue 与完整形状；成功后
     表单收起（onHideForm）；
  2. Silence schedule 表单：两个 datetime-local 输入 + name → 提交断言
     `createSilenceSchedule` 收到 objectContaining({name, silenceType})。
- 证据：`tsc -b` 绿；`test:run` 345 → 347；`lint`/`build` 绿。

### Batch 52（已交付）

- 分支：`test/evaluation-interactions-20260906`（基于 Batch 51 分支）
- 内容：Evaluation 页新增 3 个深度交互测试（真实 react-query mutation）：
  1. manual evaluate 表单（包裹式 label 关联）：填写 query/retrieved/relevant →
     提交断言 `evaluate` 收到**逗号+空白解析后的 doc id 数组**（'doc1, doc2,' →
     ['doc1','doc2']——过滤空项的解析契约）；
  2. judge 表单：query/context/answer 提交 → `answerQuality` 参数断言；
  3. suites tab：suiteKey/suiteName 创建 → `createSuite` 参数断言。
- 证据：`tsc -b` 绿；`test:run` 345 → 348（56 文件，+3）；`lint`/`build` 绿。

### Batch 53（已交付）

- 分支：`test/documents-preview-relocate-20260906`（基于 Batch 52 分支）
- 内容：`Documents.interactions.test.tsx` 追加 2 个流程测试：
  1. **preview 对话框**：点击标题按钮 → `documentsApi.get(id)` 取全文 →
     预览渲染完整内容；
  2. **relocate 流程**（外部管理文档）：菜单 → relocate → 详情加载 →
     目标集合 select（过滤排除源集合）→ 提交断言 `documentsApi.relocate`
     收到完整五元组请求 + Idempotency-Key UUID（expect.any(String)）。
- 证据：`tsc -b` 绿；`test:run` 349/349（57 文件，+4）；`lint`/`build` 绿。

### Batch 54（已交付）

- 分支：`test/evaluation-citations-tab-20260906`（基于 Batch 53 分支）
- 内容：Evaluation citations tab 补 2 个测试——trace 行渲染
  （traceId/citationStatus/outcome，null 值回退 '—'）与加载失败
  （citationsFailed 告警）。
- 证据：`tsc -b` 绿；`test:run` 350 → 352（56 文件，+2）；`lint`/`build` 绿。

### Batch 55（已交付）

- 分支：`test/preview-degrade-20260906`（基于 Batch 54 分支）
- 内容：Documents preview 降级场景测试——列表 API 不回传正文、
  `documentsApi.get` 失败时，预览仍以列表数据打开（dialog 渲染文档标题），
  get 恰好尝试一次。处理逻辑与 Batch 53 的 happy path 形成对照
  （成功路径取全文更新内容 / 失败路径保留列表数据打开）。
- 证据：`tsc -b` 绿；`test:run` 355/355（57 文件，+1）；`lint`/`build` 绿。

### Batch 56（已交付）

- 分支：`audit/embedding-repo-20260906`（基于 Batch 55 分支）
- **审计结论：EmbeddingJobRepository（1114 行，40 方法）不拆分、护栏已足**：
  - 最长方法仅 42 行（cancelActiveForDocument 单条 UPDATE）；该类是「一方法
    一 SQL 语义」的数据访问集合，拆分只会打散 SQL 与其映射的对应关系；
  - gated IT `EmbeddingJobsPostgresIntegrationTest`（Testcontainers 真实 PG，
    `embedding-jobs.it.enabled`）8/8 通过，实测覆盖核心并发语义：force 合并
    与单 worker 认领、after-commit 事件处理、过期租约终结、过期租约写入拒绝
    与二次认领等——executor 侧另有 Batch 6 的 11 个决策矩阵单测。
- 证据：静态扫描（40 方法最长 42 行）+ gated IT 实测 8/8 绿；
  本批无应用代码改动。

### Batch 57（已交付）

- 分支：`audit/json-record-service-20260906`（基于 Batch 56 分支）
- **审计结论：JsonRecordService（1063 行，40 方法）不拆分、护栏已足**：
  - 最长方法 49 行（batchUpsert 批量循环），无超长方法；
  - 三层护栏实测全绿：服务级专属单测 JsonRecordServiceTest 23/23、
    RagJsonRecordControllerWebTest 7/7、gated IT
    JsonbStructuredRecordsPostgresIntegrationTest 3/3（真实 PG）；
  - 锁扫描通过。
- 与 Batch 56 同结论：数据访问/编排类大文件的「一方法一职责 + 行为护栏」
  状态良好，无需为行数而拆。

## 10. 下一批次入口（候选，按优先级）

- Batch 58：LegacyEmbeddingMigrationService 与 PdfImportController 剩余方法
  的同模式快速审计（沿用本批验证方式）。
- Batch 59：前端 hooks 层覆盖复核收尾。