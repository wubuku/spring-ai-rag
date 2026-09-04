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

### Batch 1 交付

- 分支：`feat/webui-test-hardening-20260905`（基于 main@d185dbfa）
- 提交：`test(webui): add tests for untested pages/components and enforce coverage thresholds`
- 推送：已推送 origin；未合并 main（按循环约束等待统一合并）。

## 4. 下一批次入口

Batch 2：design-token 机器门禁（`scripts/check-design-tokens.mjs` 增加字面颜色禁令 + 白名单基线），
修复 `MetricsCharts.tsx` 内联 isDark 主题分支与 `ReembedAllButton.module.css` 深色主题失明。
新增门禁必须以当前字面颜色清单为基线白名单，只防新增不回溯。
