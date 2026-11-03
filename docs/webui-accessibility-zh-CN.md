# WebUI 可访问性与响应式基线

> [English](webui-accessibility.md) | [中文](webui-accessibility-zh-CN.md)

本文记录 `spring-ai-rag-webui` 在**真实浏览器**（Playwright + Chromium，不是 jsdom）
里的可访问性与响应式基线：哪些断言已经自动化、跑什么命令、当前修复了什么，
以及哪些问题仍然是有意保留的边界。

配套规则：[WebUI 设计语言](webui-design-language-zh-CN.md)（token、图标、共享基元）、
[WebUI 水平对齐指南](webui-alignment-guidelines-zh-CN.md)。

## 1. 怎么跑

```bash
cd spring-ai-rag-webui
npm run test:e2e:mock
```

`npm run test:e2e:mock` 会先 `npm run build`，再用 `vite preview`（15174）跑全部
非 `-real` 用例。只跑可访问性相关四个文件：

```bash
npx playwright test --config playwright.preview.config.ts \
  e2e/accessibility.spec.ts e2e/keyboard.spec.ts \
  e2e/responsive.spec.ts e2e/reduced-motion.spec.ts
```

这些用例都走 `e2e/api-mocks.ts` 的共享 mock，因此不需要后端、不需要模型 Key。
它们证明的是**前端实现**在真实渲染树里的行为；真实 LLM 联调仍然要用
`scripts/start-real-e2e-server.sh` + `scripts/real-llm-e2e-smoke.sh`。

## 2. 自动化基线

| 套件 | 断言 | 当前结果 |
|---|---|---|
| `e2e/accessibility.spec.ts` | 14 个页面各跑一次 axe-core WCAG 2.1 AA 扫描 | 14/14，**0 critical / 0 serious** |
| `e2e/keyboard.spec.ts` | Tab 焦点可见、模态框焦点陷阱 + Esc、菜单方向键、移动侧栏、SSE 焦点与 aria-live | 5/5 |
| `e2e/responsive.spec.ts` | 13 页 × 375/768/1440 无页面级横向滚动、表格在局部滚动容器内；375 下按钮/下拉 ≥24px；慢网与断网加载态 | 42/42 |
| `e2e/reduced-motion.spec.ts` | `emulateMedia({ reducedMotion: 'reduce' })` 后骨架与流式光标无 infinite 动画 | 2/2 |

axe 使用 `wcag2a`、`wcag2aa`、`wcag21a`、`wcag21aa` 四组标签。**只对
critical / serious 设门禁**：moderate / minor 多为最佳实践或需要页面级判断，
不是 WCAG 2.1 AA 的必要条件，因此不把它们变成一条要么关闭规则、要么长期红灯的门禁。
新增 critical/serious 违规时，用例失败消息会带 rule id、help 和受影响节点，
必须逐条修复，不允许加 `disableRules` 或放宽标签。

## 3. 本轮修复的实现缺陷

扫描第一次跑时，14 个页面全部因对比度与可访问名称失败。修复都落在实现与 token 上，
没有关闭任何规则：

| 问题 | 位置 | 修法 |
|---|---|---|
| `color-contrast`：`#3b82f6` 在浅灰底 3.48:1 | `design-tokens/tokens.json` | `primary.light` 改为 `#2563eb`，`primary-hover.light` 改为 `#1d4ed8`，`on-primary.light` 改为白色 |
| `color-contrast`：`#6b7280` 在 `#f3f4f6` 上 4.39:1 | `design-tokens/tokens.json` | `text-muted.light` 改为 `#4b5563` |
| `color-contrast`：图表系列色作为 tooltip 文字 | `MetricsCharts`、`ABTest` | tooltip 项目文字改用 `var(--color-text)`，系列色只保留在图元上 |
| `label-content-name-mismatch`：Files 排序按钮 | `src/pages/Files.tsx` | `aria-label` 以可见文案开头（`排序字段: 当前方向`） |
| SSE 结束后焦点丢到 `body` | `src/pages/Chat.tsx` | 输入框流式期间改 `readOnly`；发送/停止按钮用 `aria-disabled` 而不是 `disabled`，保持可聚焦 |
| 文档列表断网只有骨架屏 | `src/pages/Documents.tsx` | 错误态改为 `QueryErrorBanner` + `refetch` 重试入口，并保留 `documents-loading` 测试点 |

设计 token 修改后必须执行 `npm run tokens:build` 并提交生成的
`src/styles/tokens.css` 与 `src/design-system/tokens.generated.ts`。

## 4. 键盘可达性

`e2e/keyboard.spec.ts` 在真实 Chromium 里断言：

- **Tab 顺序**：首页连续 Tab 的每个停靠点都有可访问名称，且焦点指示器不是 `none`
  （浏览器默认 outline 或组件的 box-shadow 均可）。
- **模态框**：打开后焦点进入 `role="dialog"`；`Shift+Tab` 与连续 `Tab` 都不逃出；
  `Esc` 关闭并把焦点还给触发按钮。
- **菜单**：`role="menu"` 打开后聚焦第一个 `role="menuitem"`，方向键在菜单项之间移动，
  `Esc` 关闭并归还焦点。[DocumentActionsMenu](file:///Users/yangjiefeng/Documents/wubuku/spring-ai-rag/spring-ai-rag-webui/src/components/DocumentActionsMenu/DocumentActionsMenu.tsx)
  负责 ArrowUp/ArrowDown/Home/End。
- **移动侧栏**：375px 下关闭状态带 `inert`，键盘无法进入；菜单按钮有
  `aria-expanded` / `aria-controls`，Enter 可打开。
- **SSE 流式**：发送后焦点留在同一个按钮上（期间文案变为 Stop），`aria-live="polite"`
  的流式内容可被读屏感知；回答完成后焦点仍然留在按钮上，不会掉回 `body`。

后者是真实缺陷而不是测试苛刻：`disabled` 元素不可聚焦，浏览器会在按钮从 Stop
变回 disabled Send 的瞬间丢掉焦点。把可聚焦性与"现在不能提交"分开表达——
`aria-disabled` + `handleSend` 内部 guard——既保留键盘位置，也不改变可用性语义。

## 5. 响应式

三档宽度是 375 / 768 / 1440。每页断言：

- `document.documentElement.scrollWidth <= innerWidth + 1`：页面级不出现横向滚动条。
- 每个 `<table>` 若宽于父容器，父容器必须是 `overflow-x: auto|scroll`：
  宽表格由局部容器承载，不能把整页撑开。
- 375px 下所有可见 `button` / `select` 的命中区不小于 24×24（WCAG 2.5.8）。

加载态：

- **慢网**：文档请求被 gate 挂起时 `documents-loading` 必须可见；放行后骨架消失、
  表格出现，不会卡死。
- **断网**：请求 `abort('internetdisconnected')` 后，axios 会重试 3 次（1s/2s/3s）、
  React Query 再重试 1 次，因此失败态大约 15s 出现；出现后必须是带 **Retry** 的
  `role="alert"`，而不是永远转圈的骨架屏。这个延迟是有界重试的代价，不是挂死；
  测试用 20s 超时覆盖它，并把上限写死在这里，避免以后悄悄变成无限等待。

## 6. 可读性细节

- **对比度**：由 axe `color-contrast` 覆盖所有页面；token 的浅色/深色两侧都要满足 AA。
  填充背景上的文字用 `--color-on-*`，不要写 `color: white`。
- **减少动态**：`check-reduced-motion.mjs` 保证每个 `infinite` 动画都有
  `@media (prefers-reduced-motion: reduce)` 下的 `animation: none`；
  `e2e/reduced-motion.spec.ts` 再用 `emulateMedia` 在真实浏览器里读 computed style，
  确认骨架 shimmer 与聊天流式光标确实停了（不是声明存在但被层叠覆盖）。
- **流式回答**：流式中的助手消息带 `aria-live="polite"`、`aria-atomic="false"`，
  读屏逐段播报而不是每次重播整段。
- **表格**：Documents、Embeddings、Evaluation 的每个数据表都有 `<caption>`；
  不需要视觉展示时用 `.sr-only` 保留在无障碍树里。
- **表单错误**：错误控件带 `aria-invalid` 与 `aria-describedby` 指向错误文案，
  错误文案是 `role="alert"`；Unlock 页是当前实现范例。

## 7. 边界

- 以上都是 Mock E2E：接口由 `e2e/api-mocks.ts` 提供，不含真实模型输出长度、
  真实 PDF 转码等外部时序。
- axe 的 moderate / minor 不设门禁；新增页面只要求 critical/serious 为零，
  但 review 时应继续读 `results.violations` 而不是只看红灯。
- 断网失败态约 15s 是有意保留的重试策略；若产品决定更快失败，应改
  `src/api/client.ts` 的 `axiosRetry` 或 React Query 的 `retry`，并同步更新
  `e2e/responsive.spec.ts` 的超时与本文。

## 8. 维护规则

新增页面必须同时加进 `e2e/accessibility.spec.ts` 与 `e2e/responsive.spec.ts` 的
`PAGES` 列表；新增持续动画必须过 `check:reduced-motion`；新增模态框/菜单优先复用
`Dialog` 与 `Tabs`/菜单的方向键约定，而不是在页面里另写一套焦点逻辑。
