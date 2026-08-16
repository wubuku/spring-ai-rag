# WebUI 水平对齐系统治理实施规划

> 状态：已实施并验证（规划审查 `3/3`，实现审查 `3/3`）
> 日期：2026-08-16
> 配套进度：[WebUI 水平对齐系统治理实施进度](2026-08-16_WEBUI_ALIGNMENT_GOVERNANCE_IMPLEMENTATION_PROGRESS.md)

## 1. 执行摘要

当前 WebUI 大量内容呈现为水平居中，并不是每个页面都经过了有意识的设计决策。主要
根因是 Vite 初始模板样式仍由应用入口加载，其中 `#root { text-align: center; }` 会把
居中对齐继承给所有没有显式覆盖的页面标题、卡片、表单说明和普通文本。真正的应用主题
样式由另一份全局文件加载，但没有重置该继承属性，因而形成了难以察觉的“全局魔法”。

本次治理不把所有 `center` 一刀切，也不逐页凭感觉修补。目标是建立以下可持续约束：

1. 管理后台的文本默认使用逻辑起始方向 `text-align: start`；
2. 只有具备明确空间语义的区域才使用文本居中；
3. 允许文件上传投放区、完整空状态、阻断式错误页、预览占位等有意居中；
4. 页面正文、卡片、表单、表格数据、行内加载和错误提示默认从起始边对齐；
5. 通过自动检查要求每一个 `text-align: center` 都携带明确的例外标记和理由；
6. 通过 Playwright 同时验证“普通内容不再被继承居中”和“文件上传等合理区域仍然居中”。

本次属于 UI 风格治理。除非测试揭示真实功能漏洞，否则不改变 API 调用、状态管理、路由、
表单行为、数据模型或业务流程。

## 2. 问题证据与当前实现

### 2.1 两套全局样式叠加

[`main.tsx`](../../spring-ai-rag-webui/src/main.tsx) 当前加载：

```ts
import './index.css';
```

[`App.tsx`](../../spring-ai-rag-webui/src/App.tsx) 又加载：

```ts
import './styles/global.css';
```

实施前的 `spring-ai-rag-webui/src/index.css` 仍保留 Vite 模板风格，并包含：

```css
#root {
  width: 1126px;
  max-width: 100%;
  margin: 0 auto;
  text-align: center;
  border-inline: 1px solid var(--border);
}
```

`text-align` 是可继承属性，因此普通页面即使没有写任何居中规则，也会从 `#root` 继承
`center`。这解释了 Dashboard 指标卡、Collections 卡片、API Key 页面和 Settings
表单等区域为何无差别居中。

### 2.2 模板样式污染不止一个声明

`index.css` 同时保留了不适合当前管理后台的模板内容：

- 与 `styles/global.css` 并行的另一组颜色变量；
- `font: 18px/145%`、`color-scheme: light dark`；
- 全局 `h1 { font-size: 56px; letter-spacing: -1.68px; }`；
- 固定宽度根容器及装饰边框；
- `#center`、`.hero`、`#next-steps`、`.ticks` 等当前应用未使用的模板选择器。

实施前的 `spring-ai-rag-webui/src/App.css` 也只包含模板样式，代码库中没有导入它。
它虽然不影响运行时，但会误导后续维护者，增加模板规则被重新引入的风险。

### 2.3 局部居中既有合理用法，也有无差别复制

当前合理示例包括：

- Documents 的文件上传投放区；
- Files 的 PDF 上传区域；
- ErrorBoundary 的阻断式错误页面；
- FilePreview 的不可用/回退占位；
- SearchResults 和 Chat 初始画面的完整空状态；
- Modal overlay 的空间定位；
- 图标按钮内部、Spinner、工具栏图标与文字的 Flex 对齐。

当前需要治理的示例包括：

- Collections、Metrics、ABTest、ApiKeys、Alerts 等页面把普通
  `.loading` / `.empty` 无条件设为文本居中；
- MetricsCharts、ChatSidebar、VersionHistoryModal 重复定义相同的居中状态文本；
- 页面和卡片依赖根节点继承，而不是从可靠的 `start` 基线出发。

`align-items: center` 和 `justify-content: center` 不能与文本居中混为一谈。横向 Flex
中的 `align-items: center` 通常只是垂直对齐图标与文字；Modal overlay、Spinner、
图标按钮和完整占位画布使用 `justify-content: center` 也通常合理。本次自动门禁不禁止
这些声明。

## 3. 对齐设计规则

### 3.1 默认规则

| 内容类型 | 默认水平对齐 | 理由 |
|---|---|---|
| 页面标题、正文、说明、卡片内容 | `start` | 支持快速扫描，符合管理后台阅读流 |
| 表单 label、hint、validation error | `start` | 保持字段与反馈的视觉锚点一致 |
| 表格文本与标识符列 | `start` | 便于逐行比较 |
| 数值、金额、百分比等可比较数字列 | `end` | 小数位和数量级更易比较；仅在适用列显式使用 |
| 操作列 | `end` 或按按钮组布局 | 操作贴近表格尾部，不依赖文本居中 |
| 行内 loading/error/empty 提示 | `start` | 它们是当前内容流的一部分，不是独立展示画布 |

使用 CSS 逻辑值 `start` / `end`，不再新增 `left` / `right` 文本对齐声明。

### 3.2 允许居中的语义

`text-align: center` 仅允许用于以下边界清晰的场景：

1. **上传投放区**：例如“将文件拖动到这里”，用户视线和拖放目标都指向整个区域中心；
2. **完整空状态**：占据明确的大块空白区域，并以图标、主提示和可选下一步组成独立画面；
3. **阻断式全页状态**：错误边界、首次解锁、路由级加载等暂时替代主要工作区的状态；
4. **预览占位**：文件/PDF/差异预览面板内没有可展示内容时的独立占位；
5. **短小且等宽的离散控件**：分段选项、diff 行前缀等，中心对齐是控件本身的视觉语义；
6. **跨列表整行的空数据单元格**：表格 `colSpan` 空行可作为有边界的列表空状态居中。

每个保留的 `text-align: center` 必须在声明前使用统一注释：

```css
/* alignment-policy: allow-center -- upload drop zone */
text-align: center;
```

注释不是为了绕过检查，而是强制提交者说明该区域属于哪一种语义例外。

### 3.3 不纳入文本居中门禁的规则

以下规则继续按布局需要使用：

- `align-items: center`；
- `justify-content: center`；
- `place-items: center`；
- `margin-inline: auto`；
- 图片、PDF 或阅读列的最大宽度居中；
- Modal 容器在 viewport 中居中。

它们控制盒子或子元素的位置，不会像根节点 `text-align` 一样无差别污染文本。

## 4. 实施范围与硬边界

### 4.1 本次范围

- 清除 Vite 模板全局样式及重复的全局入口；
- 在唯一全局样式中建立 `html`、`body`、`#root` 的可靠 `start` 基线；
- 将已有 `text-align: left/right` 改为 `start/end`；
- 删除普通 loading/error/empty 状态上无语义依据的文本居中；
- 为保留的居中声明添加统一理由标记；
- 增加对齐策略自动检查并接入 `npm run lint`；
- 增加桌面和移动 viewport 的 Playwright 对齐断言与稳定视觉快照；
- 新增长青 WebUI 对齐指南，并从中英文文档索引发现；
- 保留本规划和实施进度，记录审查、验证和提交结果。

### 4.2 明确非目标

- 不重新设计配色、字体层级、间距系统、导航图标或页面信息架构；
- 不借机重写 React 组件或引入新的组件库；
- 不修改 API、React Query、认证、路由、i18n 或上传/检索逻辑；
- 不禁止所有 Flex/Grid 居中；
- 不把每个 `.loading` / `.empty` 强制抽象成新的 React 共享组件；
- 不修复与水平对齐无关的既有产品问题。

### 4.3 功能零回归约束

- 现有 Vitest 和 Playwright 行为断言必须继续通过；
- 不通过删除断言、放宽业务期望或改写 Mock 响应来掩盖回归；
- 若只为视觉测试增加选择器，只允许增加无行为的 `data-testid`；
- CSS 修改不得改变隐藏 file input、拖放命中区、按钮可点击范围、Modal 层级或滚动容器；
- 如果发现功能漏洞，单独记录并只在确实阻塞本任务验证时做最小修复。

## 5. 目标样式架构

### 5.1 单一全局入口

目标加载关系：

```text
main.tsx
  -> styles/global.css
  -> App.tsx and route components
  -> CSS Modules
```

实施动作：

1. `main.tsx` 改为直接导入 `styles/global.css`；
2. `App.tsx` 删除重复全局样式导入；
3. 删除 `index.css`；
4. 删除未被导入的 `App.css`；
5. `styles/global.css` 接管 reset、主题变量和应用根节点规则。

删除 `index.css` 前必须区分“模板污染”和“当前仍在生效的排版基线”。本次不顺带重做
全站字号：

- 把当前桌面 `18px`、`max-width: 1024px` 时 `16px` 的根字号迁入
  `styles/global.css`；
- 保留当前 `0.18px` 字距、font smoothing、`font-synthesis` 和
  `text-rendering` 行为；
- 不迁移 Vite 的第二套颜色变量、`color-scheme`、全局 `h1/h2`、固定根宽度、边框和
  hero 选择器；
- 当前 `<code>` 只出现在 API Key Collection 选项和 CollectionScopeSelector；
  如它们依赖模板 `code` 的 padding/background/font-family，则把等价的必要规则下沉到
  对应 CSS Module，不保留全局模板 `code` 样式；
- 在实施前后用 computed style 和截图对比根字号及这两个 code 展示，避免无关排版漂移。

`#root` 只保留应用挂载所需的尺寸和默认文本方向：

```css
html,
body,
#root {
  min-height: 100%;
}

#root {
  min-height: 100svh;
  text-align: start;
}
```

不再由根节点设置固定内容宽度、自动 margin、装饰边框或模板主题变量。

### 5.2 局部迁移分类

实施时按语义而不是按声明数量处理：

**改为 `start` 或删除局部声明**

- 普通页面 loading/error/empty；
- 卡片、表单、页面说明和状态 banner；
- Modal 内普通错误和加载文本；
- 依赖根节点继承的 Dashboard、Collections、Settings、ApiKeys 等内容。

**保留并标记 intentional center**

- Documents / Files 上传区；
- SearchResults 完整空结果画面；
- Chat 完整初始状态；
- ErrorBoundary；
- FilePreview 回退占位；
- Documents 跨列表空行；
- VersionHistory diff 空画布和 diff 行前缀；
- CollectionScopeSelector 的等宽分段选项；
- 其他经检查确属第 3.2 节语义的有限选择器。

**不因本任务修改**

- 工具栏图标与文字的 `align-items: center`；
- Modal overlay 的 Flex 居中；
- Spinner 和图标按钮内部居中；
- 分页容器、图片或阅读列等盒级布局，除非视觉验收显示它们受根文本继承污染。

## 6. 自动防回归机制

新增无第三方解析依赖的 Node 检查脚本
`spring-ai-rag-webui/scripts/check-alignment-policy.mjs`：

1. 扫描 `src/**/*.css`、`src/**/*.tsx`；
2. 禁止未标记的 `text-align: center` 和 JSX `textAlign: 'center'`；
3. 禁止新增 `text-align: left/right`，要求使用 `start/end`；
4. 禁止全局根选择器使用文本居中；
5. 检查 `styles/global.css` 存在 `#root` 的 `text-align: start` 基线；
6. 检查 `main.tsx` 只加载 canonical 全局样式；
7. 输出文件、行号和修复提示，便于本地及 CI 定位。

`package.json` 增加：

```json
"check:alignment": "node scripts/check-alignment-policy.mjs",
"lint": "eslint . && npm run check:alignment"
```

脚本只治理高风险、可继承的文本对齐，不扫描或禁止 Flex/Grid 的中心布局。

## 7. 测试与验收设计

### 7.1 静态与构建门禁

在 `spring-ai-rag-webui/` 执行：

```bash
npm run check:alignment
npm run lint
npx tsc -b
npm run test:run
npm run build
```

验收：

- 没有模板 CSS 入口；
- 没有未解释的文本居中；
- 所有既有单元测试通过；
- TypeScript 和生产构建无错误。

### 7.2 Playwright 功能回归

使用现有 `e2e/api-mocks.ts` 和源码 Vite dev server，完整执行：

```bash
BASE_URL=http://127.0.0.1:<vite-port> npm run test:e2e
```

所有既有页面导航、上传、检索、API Key、流式交互测试必须通过。不得修改业务期望来适配
样式变更。

### 7.3 对齐策略 E2E

新增 `e2e/alignment.spec.ts`，至少验证：

- `#root`、主工作区、页面标题、Dashboard 卡片的 computed `text-align` 为 `start`；
- Documents 普通表格文本为 `start`；
- Documents 上传投放区为 `center`；
- Files 上传区域为 `center`，文件树/预览标题为 `start`；
- Settings 表单 label、说明和错误反馈为 `start`；
- API Key 空状态或列表说明不再无条件居中；
- 桌面和窄屏 viewport 均满足上述规则。

测试应使用 computed style，不只检查 class 名，以覆盖继承污染。

### 7.4 视觉证据

为具有代表性的 Documents、Files 和至少一个数据密集页面生成截图测试产物：

- 桌面：`1440 x 900`；
- 移动：`390 x 844`；
- 禁用动画；
- 避免包含动态时间，必要时只截取稳定区域或 mask 动态节点；
- 截图写入 Playwright `testInfo.outputPath()` 作为当前运行的视觉证据，不提交带
  `chromium-darwin` 等平台后缀的二进制快照；
- 截图重点观察标题、工具栏、上传区、表格/卡片和空状态的层级与对齐。

实施前先把同一组代表页面截图保存到 Git 之外的临时目录，用于人工确认没有发生无关的
字号、控件尺寸或 code 标签样式漂移。实施后人工回看 Playwright 测试产物；computed
style、几何关系和功能 E2E 是跨平台的确定性门禁，截图是辅助视觉证据。

不提交平台绑定的视觉快照，避免开发机和 CI 操作系统差异造成无意义的快照缺失或字体
差异失败。

## 8. 实施顺序

1. 保存当前 CSS/测试基线和工作区状态；
2. 冻结本规划，连续三轮无实质问题后才修改生产代码；
3. 整合全局样式入口，删除模板样式文件；
4. 运行 TypeScript、Vitest 和现有 Playwright，立即识别全局基线影响；
5. 按第 5.2 节逐类处理局部文本对齐；
6. 增加自动检查脚本和明确的居中例外标记；
7. 增加 computed style 与视觉快照 Playwright；
8. 完整执行 lint、tsc、Vitest、build 和 Mock Playwright；
9. 在基本门禁全部通过后，连续完成三轮固定范围实现审查；
10. 更新长青指南、进度账本、双语索引和最终验证结果；
11. `git diff --check`、文档门禁和最终 diff 回看；
12. 只提交本任务改动并推送。

## 9. 风险与缓解

| 风险 | 缓解 |
|---|---|
| 删除 `index.css` 后主题或字体变化超出预期 | 保留 `styles/global.css` 的当前主题变量和字体；通过代表页面截图复核 |
| 删除模板 CSS 顺带把根字号从 18px 改为 16px | 显式迁移当前响应式根字号、字距和字体渲染基线，并断言 computed font-size |
| 全局模板 `code` 删除令现有 Collection 标识样式退化 | 把必要规则下沉到两个实际使用 code 的 CSS Module，截图复核 |
| 根容器固定宽度移除导致布局扩展 | Layout 自身已经管理 sidebar/main；桌面和移动截图验证宽度、滚动和覆盖层 |
| 误删上传区居中 | Playwright 明确断言 Documents/Files 上传区 computed style 为 `center` |
| 把 Flex 中心布局误判为文本居中 | 门禁只约束 `text-align` / JSX `textAlign` |
| 大量页面局部 CSS 修改引入功能回归 | 不改业务 TSX；完整运行现有 Vitest/Playwright |
| 截图因动态数据或操作系统差异不稳定 | 固定 Mock 数据、局部截图或 mask；截图只作为测试产物和人工证据，computed style/几何/功能 E2E 承担确定性门禁 |
| 注释白名单被滥用 | 只接受紧邻声明的统一标记；代码审查按第 3.2 节判断语义 |

## 10. 回滚策略

本次不涉及数据库、API 或持久化迁移。若样式回归：

1. 单独回退造成问题的局部 CSS 规则；
2. 保留 `#root { text-align: start; }` 和单一全局入口，不恢复继承式居中；
3. 如某个完整状态确需居中，为该选择器添加有理由的局部例外；
4. 重新运行对齐检查、构建和相关 Playwright。

回滚不需要服务端操作，也不影响已有用户数据。

## 11. 文档落档

实施期间持续更新配套进度文档。实施通过后新增长青指南，至少包含：

- 管理后台默认对齐规则；
- 允许居中的语义；
- `alignment-policy` 例外标记格式；
- 本地检查和 Playwright 命令；
- 新页面/组件的审查清单。

该长青指南从 `docs/index.md` 和 `docs/index-zh-CN.md` 直接可发现。本规划保留设计依据和
实施边界，进度文档保留审查与验证证据。

## 12. 规划审查与实施终止条件

规划审查只把以下问题视为需要修改并重置计数的实质性缺陷：

- 方案会改变或破坏现有功能行为；
- 删除全局样式会遗漏当前运行时所需规则；
- 自动门禁误伤常见、合理的盒级居中；
- 合理的上传/完整状态居中无法通过验收；
- 验收无法证明继承污染已消除；
- 文档、测试或实施范围不足以阻止同类问题重新出现。

行号偏差、措辞、格式、非穷举文件清单和实施中自然暴露的次要适配不触发计数重置。
文档必须连续三轮系统性审查无实质问题且期间无修改，才允许开始修改生产代码。
