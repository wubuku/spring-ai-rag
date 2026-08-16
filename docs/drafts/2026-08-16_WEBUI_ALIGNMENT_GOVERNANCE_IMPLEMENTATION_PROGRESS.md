# WebUI 水平对齐系统治理实施进度

> 状态：实现、验证、三轮审查、差异回看、提交和推送均已完成
> 日期：2026-08-16
> 配套规划：[WebUI 水平对齐系统治理实施规划](2026-08-16_WEBUI_ALIGNMENT_GOVERNANCE_IMPLEMENTATION_PLAN.md)

## 1. 目标

消除 Vite 模板 `#root { text-align: center; }` 造成的继承式全局污染，为管理后台建立
“普通内容默认从起始边对齐、仅明确空间语义允许居中”的长期规则；保留文件上传投放区等
合理居中，并通过自动检查、computed style 断言和视觉快照防止回归。

## 2. 执行硬规则

- 规划文档连续三轮系统性审查无实质问题且期间无修改后，才修改生产代码。
- 本次属于 UI 风格治理，不改变 API、状态管理、路由、表单行为、数据模型或业务流程。
- 不把 `align-items: center` / `justify-content: center` 与文本居中混为一谈。
- 文件上传投放区、完整空状态、阻断式错误页和预览占位等明确语义允许居中。
- 所有既有 Vitest 和 Playwright 功能测试必须继续通过，不通过降低断言掩盖回归。
- 基本验证全部通过后，再连续完成三轮无修改实现审查。
- 不执行 `git stash`、`git reset` 或 `git checkout`，不覆盖其他开发者修改。
- 每个关键阶段先更新本文件，再进入下一阶段。

## 3. 开始基线

- 开始时 Git 工作区干净，分支为 `main`。
- `main.tsx` 加载 `src/index.css`；`App.tsx` 加载 `src/styles/global.css`。
- `src/index.css` 存在 `#root { text-align: center; }`、固定宽度根容器和 Vite 模板样式。
- `src/App.css` 未被任何源码导入，仅保留模板样式。
- 当前代码中存在多处 `text-align: center`，其中既有上传区等合理例外，也有普通
  loading/empty 状态的无差别复制。
- 当前没有 Stylelint 或专用 CSS 对齐门禁。
- WebUI 已有 Vitest 和 Mock Playwright；Playwright 配置可通过 `BASE_URL` 指向 Vite
  dev server。
- 任务开始时已有 Vite dev server 监听 `127.0.0.1:15173`，本任务不终止或复用其进程
  所有权；需要隔离验证时使用其他端口。

## 4. 当前阶段

| 阶段 | 状态 | 结果 |
|---|---|---|
| 代码库与文档探索 | 已完成 | 已确认全局污染源、CSS Modules 局部规则、入口加载关系、现有测试与文档规范 |
| 规划初稿 | 已完成 | 已冻结对齐语义、实施边界、自动门禁和验收方案 |
| 规划三轮审查 | 已完成 | 修订后连续无修改 `3/3`，已允许实施 |
| 全局样式治理 | 已完成 | 已切换到 canonical `styles/global.css`，保留字号/字距基线，移除 Vite 模板入口与未使用模板文件 |
| 局部样式迁移 | 已完成 | 普通内容改为 `start`，上传/完整状态/预览等有限语义继续显式居中；移动端菜单与标题增加安全间距 |
| 防回归测试 | 已完成 | 新增 `check-alignment-policy.mjs`、`npm run check:alignment` 和 5 个 Playwright 对齐/视觉证据用例 |
| 基本集成验证 | 已完成 | 前端硬门槛全部通过，见第 7 节 |
| 实现三轮审查 | 已完成 | 固定范围连续三轮无修改，计数达到 `3/3` |
| 最终 diff 与文档门禁 | 已完成 | 完整差异已回看，文档门禁 `10/10`，`git diff --check` 通过 |
| 提交与推送 | 已完成 | 实现提交 `fc08ca4` 已推送到 `origin/main`；本行由后续文档收尾提交记录 |

## 5. 规划审查记录

| 时间 | 轮次与范围 | 发现问题 | 处理与结果 |
|---|---|---|---|
| 2026-08-16 18:52 CST | 初始第 1 轮：全局入口、模板样式删除、现有标题/code 使用、测试可执行性 | 直接删除 `index.css` 会把桌面根字号从 18px 改为 16px，并改变两个现有 code 展示，超出水平对齐治理范围；规划也没有说明视觉快照首次生成流程 | 已要求迁移当前响应式根字号、字距和字体渲染基线，把必要 code 样式下沉到实际 CSS Module，并明确实施前临时截图及 `--update-snapshots` 流程；文档已修改，连续无修改计数归零 |

## 6. 实施记录

| 时间 | 轮次与范围 | 发现问题 | 处理与结果 |
|---|---|---|---|
| 2026-08-16 19:12 CST | 实现审查第 1 轮：视觉测试跨平台可执行性 | Playwright 默认截图快照带 `chromium-darwin` 平台后缀，提交后在 Linux/其他开发机缺少匹配基线；确定性对齐断言不依赖快照 | 删除平台绑定快照，改为写入 `testInfo.outputPath()` 的截图证据；增加移动标题与菜单按钮的几何断言；同步规划，审查计数归零 |
| 2026-08-16 19:19 CST | 重启后的实现审查第 1 轮：模板 CSS 非对齐样式迁移 | 删除全局模板 `code` 规则后，API Key 和 CollectionScopeSelector 的两个现有 Collection 标识失去 padding、背景和 monospace 展示，与规划的低风险迁移要求不符 | 把必要 code 样式下沉到两个实际 CSS Module，避免恢复全局模板污染；审查计数保持 `0/3` |
| 2026-08-16 19:22 CST | 重启后的实现审查第 1 轮：规划验收项与 E2E 覆盖交叉核对 | 规划要求验证 Settings 表单文本、API Keys 状态和桌面根字号，但当前对齐测试尚未覆盖这些承诺 | 补充桌面 `18px` 根字号、Settings 标题/标签/说明、侧栏无刷新导航及 API Keys 标题/空状态的 computed style 断言；审查计数保持 `0/3` |

## 7. 验证记录

实施前基线：

- `npm run lint`：通过；
- `npx tsc -b`：通过；
- `npm run test:run`：`178/178` 通过；
- `npm run build`：通过；
- `BASE_URL=http://127.0.0.1:15173 npm run test:e2e`：`36/36` 通过；
- `./scripts/verify-project-docs.sh`：`10/10` 通过；
- 已在 Git 之外保存 Dashboard、Documents、Files 桌面截图；
- 当前 computed style 证实 `#root`、页面标题、Dashboard 卡片和 Files tree header
  均从根节点继承 `text-align: center`，根字号为 `18px`；Documents/Files 上传区为
  `center`，Documents 表格单元格因局部规则为 `left`。

问题修复后的基本验证：

- `npm run check:alignment`：通过，12 个居中声明均有语义注释；
- `npm run lint`：通过；
- `npx tsc -b`：通过；
- `npm run test:run`：`178/178` 通过；
- `npm run build`：通过；
- `BASE_URL=http://127.0.0.1:15175 npm run test:e2e`：`41/41` 通过，其中既有功能场景
  `36/36`，新增对齐场景 `5/5`；
- `./scripts/verify-project-docs.sh`：`10/10` 通过；
- `git diff --check`：通过；
- Playwright 已生成 Dashboard、API Keys、Documents、Files 桌面截图证据与 Documents
  移动截图证据；computed style 和几何断言确认普通内容为起始对齐，上传投放区及 Files
  预览占位保持居中，桌面根字号仍为 `18px`，移动端根字号为 `16px` 且标题未被菜单按钮遮挡。

## 8. 实现审查终止结果

在基本集成验证全部通过后，于 2026-08-16 19:23–19:27 CST 完成三轮固定范围审查，
期间未修改实现代码，连续无修改计数达到 `3/3`：

1. 全局样式入口、模板残留、对齐声明清单和自动门禁；
2. CSS Modules 逐文件语义、响应式布局、样式下沉和功能不变性；
3. E2E 验收覆盖、双语长青指南、索引可发现性、忽略产物和提交边界。

三轮均未发现需要修复的实质问题。审查结论以此前已经通过的自动化门禁为前提，不替代
lint、TypeScript、Vitest、生产构建和完整 Mock Playwright 的验证结果。
