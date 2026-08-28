# WebUI 统一设计语言调研与实施规划

> **状态**：规划连续三轮无修改审查已达 `3/3`；可直接实施
> **日期**：2026-08-28
> **稳定代码基线**：`main@dae60044`
> **规划 checkpoint**：`main@495e7fce`
> **实施授权**：用户已授权规划完成后直接实施
> **规划位置**：当前主工作区 `main`
> **实施位置**：同一工作区的 `feature/webui-unified-design-language` 专用分支
> **未来进度文档**：实施开始时创建 `WEBUI_UNIFIED_DESIGN_LANGUAGE_PROGRESS.md`
> **交付规则**：[规划、实施与验收工作流](../delivery-workflow-zh-CN.md)
> **既有长青约束**：[WebUI 水平对齐指南](../webui-alignment-guidelines-zh-CN.md)

## 1. 执行结论

当前 WebUI **具有若干统一基础，但还不能确认已经形成完整、可持续执行的统一设计语言**。

现有积极基础包括：

- 全站使用同一个 Layout、React Router、TanStack Query、i18n 和 CSS Modules 技术栈；
- `global.css` 已提供 light/dark 基础语义色，ThemeToggle 支持显式亮/暗与跟随系统；
- 页面标题多数复用 `.page-title`，普通内容遵循 `text-align: start`；
- Dialog/ConfirmDialog 已统一 Portal、ARIA、focus trap/return、Escape、scroll lock 和关闭守卫；
- layer token、未定义 CSS variable 和数值 z-index 已有静态门禁；
- Skeleton、Toast、Collection scope、搜索结果、文件预览等已有局部共享组件；
- TypeScript、Vitest、生产构建、ESLint、alignment、design-token 和 Mock Playwright 当前均可通过。

但这些基础还没有形成一套由**语义 token、有限组件规格、页面布局规则、状态表达、图标、
可访问性、响应式策略和自动化门禁**共同约束的设计系统。当前页面可以功能正确，却仍能合法地
新增另一种按钮、另一组状态色、另一个只在浅色可读的局部样式，或跨页面借用 page CSS。

本规划建议把下一轮 WebUI 高价值工作定义为：

1. 冻结现有业务行为和页面可寻址状态，不借“统一 UI”修改工作流；
2. 建立单一、可生成、可校验的设计 token 源；
3. 建立适合运维型 RAG 控制台的有限 UI primitive 和页面构图规格；
4. 先迁移 Shell 与高频检索/知识管理工作流，再迁移运营和管理页；
5. 使用 DOM、ARIA、URL、网络 JSON、计算样式、对比度和几何断言验收，全程不使用截图
   作为通过证据；
6. 用版本化设计债务基线保证存量只减不增，避免为了“一次清零”扩大风险。

## 2. 调研方法与事实边界

### 2.1 真相来源

本规划按以下优先级判断现状：

1. `main@dae60044` 的 WebUI 源码、测试和构建产物；
2. 当前项目的双语长青文档与交付工作流；
3. 已完成并合入的上一轮 WebUI 规划/进度，只作为历史设计与验证证据；
4. 其他项目的设计语言经验只提炼为通用方法，不作为本项目的背景依赖或代码真相。

### 2.2 已执行探索

- 阅读 `spring-ai-rag-webui/src` 下全部页面、组件和样式文件清单；
- 深读 `global.css`、ThemeToggle、Layout、Dialog、代表性页面和现有 E2E；
- 统计 CSS 变量、字面颜色、圆角、阴影、z-index、响应式断点、focus 规则和按钮调用；
- 核对刚合入的工作上下文、统一 Dialog、layer token 和 design-token 检查器；
- 运行最新 main 的 TypeScript、Vitest、生产构建、ESLint、alignment 与 design-token 门禁；
- 阅读已有水平对齐指南以及参考经验中的设计语言规划、实施进度、主题开发指南和文档治理方法。

### 2.3 最新基线验证

在 `main@dae60044` 上实际执行：

| 验证 | 结果 |
|---|---|
| `npm run typecheck` | 通过 |
| `npm run test:run` | 34 个 test files、250 个 tests 通过 |
| `npm run lint` | 通过 |
| `npm run check:alignment` | 通过；12 个有理由的居中例外 |
| `npm run check:design-tokens` | 通过；30 个全局 token，未定义变量和数值 z-index 为 0 |
| `npm run build` | 通过；Vite 转换 806 个 modules，initial JS gzip 110.92 KiB |
| 上一轮合并后完整 Mock Playwright | 87/87 通过，无截图 |

这些结果证明当前 WebUI 功能基线稳定，**不证明设计语言已经统一**。现有门禁尚未系统验证
token light/dark 完整性、字面颜色增长、组件规格、全站主题对比度、focus 可见性和跨页面
视觉一致性。

## 3. 当前一致性审计

### 3.1 总体评分

| 维度 | 当前状态 | 结论 |
|---|---|---|
| 产品气质 | 页面整体是克制的管理控制台 | 方向一致，但缺少书面北极星和密度合同 |
| 颜色 token | `global.css` 有 30 个颜色/layer/alias token | 部分统一；手写源、暗色覆盖不完整、语义覆盖不足 |
| 主题 | 手动 light/dark 与无存储时跟随 system | 基础可用；缺少首屏防闪、跨 Tab 和全站计算样式门禁 |
| 页面标题/对齐 | 多数页面复用 `.page-title`；alignment 有长青规则 | 已有明确基础，应直接继承 |
| UI primitive | Dialog 已统一；无通用 Button/Field/Badge/PageHeader/Table 规格 | 部分统一 |
| 图标 | 导航主要使用 emoji，工具命令混用文字和符号 | 未统一，视觉宽度与平台渲染不可控 |
| 状态表达 | success/warning/error 有全局前景色，soft surface 各页硬编码 | 未统一，暗色风险高 |
| 表格/Toolbar/Tabs | 多页重复实现 | 未统一，密度和交互态不同 |
| 可访问性 | Dialog 与部分控件有 role/aria/focus 测试 | 部分统一；没有全局 focus 与严重度门禁 |
| 响应式 | Shell 与部分高频页有 media query | 部分统一；多张管理页无页面级窄屏策略 |
| 自动化治理 | 功能门禁良好，已有 alignment/token 初级检查 | 缺少完整 design-system gate |

### 3.2 量化信号

最新 main 当前包含：

- `28` 个 CSS Module，加 `global.css` 共约 `6288` 行样式；
- 约 `151` 个 JSX `<button>` 调用，但没有共享 Button/IconButton 合同；
- 源码中约 `400` 行包含 hex/rgb/hsl 字面颜色，共 `107` 个不同字面值；
- `global.css` 定义 30 个基础颜色、shadow、layer 和兼容 alias，未定义 variable 已清零；
- 8 个兼容 alias 仍被保留：`--color-background`、`--color-bg-secondary`、
  `--color-hover`、`--color-text-secondary`、`--bg-primary`、`--bg-secondary`、
  `--border-color`、`--text-secondary`；
- dark block 没有覆盖 `--color-surface-2` 等全部功能性颜色，现有检查器只验证“全局已定义”，
  不验证 light/dark 核心 key 对称；
- 圆角仍有 `2/3/4/5/6/8/12px/50%/9999px` 等十类写法；
- 数值 z-index 已清零，但 `transition: all` 仍有 10 处，非 Dialog 区域缺少统一 reduced-motion；
- 只有少量按钮/菜单使用 `:focus-visible`，多数表单只定义 `:focus`；
- 13 个顶级导航入口使用 emoji；ThemeToggle 也使用 emoji/字母组合；
- Embeddings 直接导入 `Evaluation.module.css`，说明页面样式被当作隐式共享层；
- Alerts、API Keys、AB Test、Evaluation 等密集管理页没有自己的窄屏 media query。

上述数字是**规划基线，不是完成目标的机械 KPI**。实施前如 main 变化必须重新统计；实现只要求
改动范围不新增设计债务，并按页面批次持续下降，不能为了追求数字删除仍承载行为的 class。

### 3.3 已有设计优势应保留

1. **CSS Modules 的所有权清晰**：当前没有大规模全局页面覆盖；只发现极少 `!important`、
   `[style]` 选择器，应保留 co-location，而不是改成一张全站 CSS。
2. **管理后台定位正确**：页面没有营销式 hero、装饰性 orb 或大面积视觉噱头；统一设计语言
   应继续服务扫描、比较和重复操作。
3. **URL 与请求合同已有测试**：Settings、Alerts、Evaluation、AB Test、工作上下文等已验证
   浏览器历史；这为“只改视觉、不改行为”提供了护栏。
4. **主题不是从零开始**：现有 light/dark/system 语义应兼容迁移，不需要引入第二套主题框架。
5. **Dialog 和 layer 已收敛**：下一轮应稳定复用，不能复制另一个 Modal 系统。
6. **对齐规则已经长青化**：普通内容 start、数值 end、投放区等少量居中例外应成为新设计
   系统的直接约束，而不是另写冲突规则。

### 3.4 上一轮能力边界

刚合入的上一轮功能已经交付：

- 通用 `Dialog` / `ConfirmDialog`；
- backdrop、shadow 与 z-index layer token；
- 未定义 CSS variable 和数值 z-index 检查；
- 全站路由工作上下文和文件可发现性改进；
- 250 个 Vitest、87 个 Mock Playwright、真实 Files 全栈与 V59 PostgreSQL 验收。

本规划复用这些稳定事实，不再规划第二套 Dialog、layer 或工作上下文。上一轮 plan/progress
在本规划提交前归档；长期事实继续以代码和双语长青文档为准。

## 4. 可吸收的设计语言治理经验

参考经验中最有价值的是以下可执行方法，而不是复制色板或页面造型：

1. **先冻结行为，再换肤**：导航、URL、权限、请求体、默认值、自动保存、危险确认、焦点和
   返回行为必须先有测试；“还能完成任务”不等于兼容。
2. **token 只有一个 canonical source**：主题值从结构化源生成 CSS/代码桥接产物；页面不得
   自建平行变量或手改生成文件。
3. **有限规格优于全能组件**：按钮、字段、标签、状态、页面头、弹层只提供有限 variant；
   没有跨页面重复证据时不抽象。
4. **明确 CSS 所有权**：token、global base、共享 primitive、领域组件和页面 module 分层；
   页面 CSS 不进入 token 文件，页面 module 也不被其他页面当共享库。
5. **存量只减不增**：硬编码颜色和旧 alias 用版本化基线追踪；新违规立即失败，迁移批次
   只删除自己证明已无调用的债务。
6. **行为证据高于截图**：主题验收使用 DOM、ARIA、computed style、对比度、尺寸、溢出和
   网络断言；截图不作为通过条件。
7. **先 foundation，再 page batches**：token/门禁、primitive、Shell 完成后再迁移页面；
   避免 6000 行 CSS 的大爆炸式重写。
8. **实施后写长青规则**：规划与进度归档，日常开发只读简洁的双语设计语言指南。

## 5. 产品设计北极星

### 5.1 产品气质

WebUI 是面向开发者和运维人员的 RAG 工作台，不是品牌营销站。统一后的界面应当：

- **安静**：中性 canvas/surface 为主，品牌色只表达主操作、选择和 focus；
- **准确**：状态、计数、权限和错误来自真实数据，不用装饰文案冒充事实；
- **紧凑**：在不牺牲可点击性和可读性的前提下支持高信息密度；
- **可扫描**：标题、工具栏、筛选、表格和详情层级固定，数值便于纵向比较；
- **可预测**：同一命令、危险动作、状态、loading、空态在全站具有相同视觉与交互语义；
- **克制**：不使用装饰渐变、单色堆叠、嵌套卡片、超大标题或无功能的视觉容器。

### 5.2 页面构图

标准业务页只使用以下层级：

```text
App Shell
  PageShell
    PageHeader（标题、简短描述、页面级主要命令）
    Toolbar / Filters（存在时）
    Content sections（无额外浮卡外框）
      repeated item / stat card / table frame（确有边界时）
    Inline feedback / Empty state
```

规则：

- 普通 section 不做“漂浮卡片”；卡片只用于重复项、指标项、Modal 或真正的工具面板；
- 不允许 card 内再放视觉同权重的 card；父子 surface 必须有可计算的层级差；
- 页面 H1 只使用一档，panel/modal 内标题使用较小规格；
- 页面主要命令每个视图最多一个 primary，其他命令为 secondary/ghost/danger；
- Toolbar 在宽屏单行、窄屏自然换行；固定按钮和输入必须有稳定高度，不因 loading 文案变形；
- 表格优先保留扫描能力，窄屏采用有标签的水平滚动或领域专用重排，不复制两套 DOM。

### 5.3 颜色使用

- 中性层：canvas、surface、surface-muted、surface-raised、hover、border、border-strong；
- 内容层：text、text-muted、text-subtle、link；
- 操作层：primary + on-primary + hover，accent/selected + on-accent，focus-ring；
- 状态层：success/warning/danger/info 各有 foreground、soft surface 和 border；
- 图表层：有限的 categorical palette，由 token/TypeScript bridge 提供；
- destructive 只表示不可逆或高风险动作，warning 不用于普通强调；
- 任意实心背景必须使用成对 foreground token，禁止假定“彩色背景永远配白字”。

## 6. 目标、非目标与成功标准

### 6.1 目标

1. light/dark/system 主题在首屏、Portal、跨 Tab 和系统切换时行为稳定；
2. 颜色、字体、间距、圆角、阴影、控件尺寸、layer 和 motion 来自单一 token 源；
3. 高频命令和状态使用有限、可访问、可测试的共享 primitive；
4. 13 个顶级页面遵循统一 PageShell/PageHeader/Toolbar/Section 规则；
5. 迁移不改变 URL、API、权限、默认值、持久化、请求次数或危险操作确认；
6. 桌面、窄屏、light/dark 下没有关键文字低对比、焦点不可见、控件变形和水平页面溢出；
7. 设计债务具名、版本化、只减不增；
8. 实施后的日常规则进入双语长青设计语言指南。

### 6.2 非目标

- 不修改后端 API、数据库 schema、认证、权限或 RAG 业务逻辑；
- 不重排或改名 13 个顶级导航入口，不改变路由和信息架构；
- 不借换肤修改 Chat/Search/Files 的工作上下文、URL 或自动恢复语义；
- 不引入 Tailwind、整套企业 UI 框架、CSS-in-JS 或 Storybook；
- 不用“重写所有页面”替代分批迁移；
- 不新增第二套 palette、多品牌主题或高对比主题；需要时另立规划；
- 不强制把所有表单、表格或领域组件包装成同一个巨型组件；
- 不追求本轮清零全部历史字面颜色；只要求迁移范围清零且全库基线不增长；
- 不使用截图作为验收证据。

### 6.3 业务成功标准

1. 用户在任意页面都能快速识别标题、主要命令、筛选、状态和危险动作；
2. 切换页面后，同语义按钮、字段、状态和空态的外观与键盘行为一致；
3. light/dark/system 不改变业务可读性、可点击性或图表含义；
4. 窄屏仍能完成 Chat、Search、Documents、Collections、Files 和管理页核心路径；
5. 开发者新增 UI 时有明确 token/primitive/页面规格和一键门禁，不再靠复制邻页 CSS 猜测。

## 7. 冻结的技术决策

| 事项 | 冻结默认 | 理由与可逆边界 |
|---|---|---|
| 实施起点 | 规划重新达到 `3/3` 后先在 `main` 提交并推送修正，再让实施分支基于最新 `origin/main` | 用户已授权直接实施；规划先落到主线并建立保护 checkpoint |
| 开发位置 | 当前工作区切换到 `feature/webui-unified-design-language` 专用分支 | 用户未要求并行开发，不创建额外 worktree |
| 视觉方向 | 安静、紧凑、技术工作台 | 符合管理后台和当前产品气质 |
| 主题 | 保持 light/dark/system，一套 palette | 不制造第二套页面 CSS 或主题维度 |
| token source | `spring-ai-rag-webui/design-tokens/tokens.json` | 结构化、可生成、可校验 |
| 生成产物 | `src/styles/tokens.css` + `src/design-system/tokens.generated.ts` | CSS 供页面，TS bridge 供 Recharts 等必须使用 JS 值的库 |
| 旧 token | 生成兼容 alias；只允许减少，不允许新调用 | 支持分批迁移，最终由引用扫描决定删除 |
| CSS 所有权 | generated token → global base → shared primitive → component/page module | 禁止页面样式进入 token/global，禁止跨页面 import page module |
| 运行时依赖 | 新增 `lucide-react`；不引入整套 runtime UI 框架 | 图标统一且 tree-shakable；现有 Dialog 继续稳定复用 |
| 可访问性检查 | `@axe-core/playwright` 仅作为 dev dependency | 使用成熟规则补充手写 DOM/ARIA 合同 |
| 页面迁移 | 按用户路径 5 个批次 | 可独立测试、review 和回退 |
| 字体基线 | 固定根字号 16px，不按 viewport 改根字号 | 避免全局缩放导致布局漂移；具体层级由 typography token 表达 |
| 圆角 | 4/6/8px + pill/circle 例外 | 管理工具克制；卡片不超过 8px |
| 验收 | DOM/ARIA/network/JSON/computed style/contrast/geometry | 明确禁止截图判断 |

## 8. Token 架构

### 8.1 文件与生成流程

```text
spring-ai-rag-webui/
  design-tokens/
    tokens.json
    design-debt-baseline.json
  scripts/
    build-design-tokens.mjs
    check-design-system.mjs
  src/
    styles/
      tokens.css                 # generated, tracked, no manual edits
      global.css                 # reset/base/typography only
    design-system/
      tokens.generated.ts       # generated, tracked
```

命令：

```bash
npm run tokens:build
npm run tokens:check
npm run check:design-system
npm run check:design-tokens   # 保留的兼容命令，委托给新门禁
npm run test:design-system
```

- `tokens:build` 从 JSON 确定性生成 CSS/TS；连续运行两次必须无 diff；
- `tokens:check` 在内存生成并与 tracked output 比较，CI 不静默改文件；
- `main.tsx` 固定先导入 generated `tokens.css`，再导入 `global.css`；组件和页面不得改变此
  顺序或自行二次导入主题文件；
- 保留现有 `check:design-tokens` package script 作为 `check:design-system` 的兼容入口，直到
  仓内调用者全部迁移；`lint` 继续聚合 ESLint、alignment 和新 design-system 门禁；
- JSON schema/validator 要求 light/dark 核心 key 完全对称、值类型合法、alias 无环；
- TypeScript bridge 只导出图表、状态解析等确实需要的 token name/CSS var 引用，不复制
  浏览器解析后的颜色常量；图表运行时用 `getComputedStyle` 读取最终主题值。

### 8.2 Token 组

首版只允许以下稳定组：

- `color`：canvas/surface/surface-muted/surface-raised/hover/border/border-strong/text/
  text-muted/text-subtle/link/primary/on-primary/primary-hover/accent/on-accent/focus；
- `status`：success/warning/danger/info 的 fg/bg/border；
- `chart`：category-1..6、grid、axis、tooltip surface/text/border；
- `typography`：font family、12/14/16/18/20/24px、line-height 1.25/1.5/1.6；
- `space`：4/8/12/16/20/24/32px；
- `radius`：4/6/8px 与 pill；
- `control`：height 32/36/40px、icon-button 32/36px；
- `shadow`：raised/dialog/focus，不提供装饰 glow；
- `layer`：raised/navigation-backdrop/navigation/overlay/dialog/popover/toast；
- `motion`：fast 120ms、normal 180ms、slow 240ms 与 standard easing。

约束：

- `letter-spacing` 全局为 `0`，不随 viewport 缩放字体；
- 不提供任意数值 token 逃生口；确有重复证据再扩；
- `transition: all` 禁止，新规则必须列出具体属性；
- `prefers-reduced-motion: reduce` 下非必要动画关闭；
- `color-scheme` 与 resolved theme 同步，使原生控件采用正确亮暗语义；
- 现有 `--color-*` 名称能表达语义时直接保留为 canonical，不为了命名美观制造平行体系；
  只有兼容 alias 作为临时迁移层，引用清零后删除。

### 8.3 颜色债务门禁

`check:design-system` 扫描 CSS、TS、TSX 和 SVG：

1. `var(--*)` 必须已定义；
2. 页面/组件不得使用数值 z-index；
3. 非 token source/generated output 不得新增 hex/rgb/hsl/命名颜色；CSS-wide keywords、
   `transparent`、`currentColor` 和 SVG `none` 作为结构语义白名单，不计入任意颜色逃生口；
4. 禁止 `transition: all`、非零 letter-spacing 和无理由 `!important`；
5. 禁止其他页面导入 `*.module.css`；
6. 兼容 alias 的调用只能减少；
7. 极少数第三方/SVG/透明 backdrop 特例使用同行或上一行
   `design-token-allow: <具体理由>`，无理由豁免失败。

`design-debt-baseline.json` 使用稳定指纹 `file + violation kind + normalized value + count`，
统一记录 raw color、transition、letter-spacing、跨页 module import、legacy alias 等全部存量债务，
不记录行号。新增违规、计数增加或基线扩张失败；修复后同步缩减。生成器和检查器都必须有
focused tests。

## 9. Theme 合同

### 9.1 状态模型

公开偏好固定为：

```ts
type ThemePreference = 'light' | 'dark' | 'system';
type ResolvedTheme = 'light' | 'dark';
```

- 兼容现有 storage：旧 `theme=light|dark` 直接迁移；无 key 视为 `system`；
- DOM 只写 `data-theme=light|dark`，业务组件不得读取 preference 自行配色；
- ThemeProvider/utility 统一负责 matchMedia、storage event、持久化和 DOM 更新；
- `index.html` 只放最短的同源 pre-paint bootstrap，React 挂载后结果必须一致；
- Portal 通过 document root token 自然继承，不在 Dialog 内再造主题 provider；
- system 变化与另一 Tab 修改在当前 Tab 可观察且不会产生事件循环。

### 9.2 ThemeToggle

- 使用 lucide `Sun`、`Moon`、`Monitor`；模式用单个三态菜单或分段控件，不保留 emoji + `A`
  的双按钮猜测语义；
- 每个选项有可访问名称和当前选中状态；
- 宽度固定，切换不会移动侧栏；
- tooltip/label 来自 i18n；
- 切换只改变视觉主题，不清理其他 session-state 或请求数据。

## 10. Shared UI primitive

新增 primitive 放在 `src/components/ui/`，每个组件拥有 co-located CSS Module、测试和 index
出口。已稳定的 `src/components/Dialog/` 保持 canonical 路径，不为目录整齐制造搬迁 diff；
只有两个以上真实页面需要的稳定模式才进入 `ui/`。

### 10.1 命令

| primitive | 有限规格 | 必守行为 |
|---|---|---|
| `Button` | primary/secondary/ghost/danger；sm/md；icon start/end；loading | 原生 button、type 明确、disabled/loading 尺寸不变 |
| `IconButton` | ghost/secondary/danger；32/36px | 必须提供 aria-label；陌生图标有 Tooltip |
| `Tooltip` | top/right/bottom/left | 键盘/hover 可达，不承载必须阅读的业务信息 |

每个页面级视图最多一个 primary。撤销、返回、关闭、复制、展开等熟悉命令优先图标；提交、
创建、删除等明确命令使用 icon + text 或 text。页面导航继续使用原生 anchor 语义的
`Link`/`NavLink`，只共享 token 与视觉规格，不由 Button 模拟链接。

### 10.2 表单

| primitive | 职责 | 非职责 |
|---|---|---|
| `Field` | label、required、hint、error、aria-describedby | 不拥有业务状态或提交 |
| `Input`/`Textarea`/`Select` | 尺寸、边框、focus、disabled、invalid | 不包装 URL、校验或 API mapping |
| `Checkbox`/`Toggle` | 二元设置 | 不用普通文本按钮模拟开关 |
| `SegmentedControl` | 2–4 个互斥短选项 | 不替代页面级 Tabs |

继续使用原生表单语义；没有明确行为缺口时不引入自定义 Select。

### 10.3 信息与布局

| primitive | 用途 |
|---|---|
| `PageShell` / `PageHeader` | 页面 padding、标题、描述、命令区、响应式换行 |
| `Toolbar` | 搜索、筛选、排序和批量操作的稳定布局 |
| `Tabs` | 负责 tablist/tab 语义、roving tabindex、方向键/Home/End 与 indicator；页面继续拥有选中值、URL 映射和数据加载 |
| `Badge` / `StatusBadge` | 中性 tag 与固定 intent 状态，绝不直接接受任意颜色 |
| `InlineAlert` | info/success/warning/error 的可读反馈 |
| `EmptyState` / `LoadingState` | 内容流内状态；只有完整区域空态可居中 |
| `TableFrame` | border、overflow、sticky header 选项和 accessible label |
| `StatCard` | 真正重复的指标项；不作为页面 section 包装器 |

### 10.4 既有组件关系

- 已交付的 Dialog/ConfirmDialog 先做 API/可访问性审计，通过后继续使用，不另建 Modal；
- Toast、Skeleton、DocumentActionsMenu、CollectionScopeSelector 优先迁移 token/primitive，
  保持公开 props 与行为；
- Recharts 通过 chart theme bridge 读取 computed token，不再在 TSX 中分支硬编码 hex；
- FilePreview/Markdown 内容使用专用 content surface token，不把富文本颜色混入全局操作 token；
- Embeddings 停止导入 Evaluation page module，抽出真实共享的 primitive 或拥有自己的 module。

## 11. 行为兼容合同

统一设计语言默认只改变视觉和可访问表达。以下行为在 PR-0 先冻结：

- 13 个导航项的名称、顺序、route、活动态和移动端关闭行为；
- route-memory、URL、session storage 与 logout/401 清理；
- Chat mode/scope/session/draft、SSE stop/retry、来源与工具活动；
- Search 已提交 URL、未提交草稿、scope、历史和请求体；
- Documents/Collections/Files 的上传、分页、深链、危险确认和 mutation 次数；
- Metrics/Alerts 的轮询、表格、tab、delivery 操作和服务端事实；
- Evaluation/Embeddings/AB Test 的 tab/detail、表单默认值和 mutation；
- API Keys 的权限、一次性 credential 不持久化、轮换/吊销确认；
- Settings 的 URL tab、provider/model 选择、保存请求和既有 localStorage；
- Dialog 的 Escape/backdrop/焦点/scroll lock/pending close guard；
- i18n key、可见文案含义和 API error 呈现。

任何确实需要改变上述行为的发现，必须单独记录为 UX/业务任务，不藏在本实施 diff 中。

## 12. 响应式与可访问性合同

### 12.1 固定验证视口

| 视口 | 目的 |
|---|---|
| 1440×900 | 宽桌面与数据密度 |
| 1280×800 | 常见笔记本 |
| 768×1024 | 窄桌面/平板边界 |
| 390×844 | 移动端核心路径 |

要求：

- 页面根不得水平溢出；需要宽表格时只允许 TableFrame 内部水平滚动；
- 固定格式控件有明确 min/max/aspect/track，不因 label、loading、badge 或图标造成 layout shift；
- Toolbar 和 PageHeader 命令在窄屏换行，文本不裁切、不遮挡；
- 不使用 viewport width 缩放字体；长 ID/URL 在专用容器中 wrap/ellipsis + 可访问完整值；
- 所有交互控件至少保持 32px 高；移动端主要命令目标至少 40px；
- hidden sidebar 不参与 tab order，Dialog 打开时焦点不逃逸。

### 12.2 可访问性

- 正文/控件文字对比度至少 4.5:1；大文本至少 3:1；非文本边界/focus 至少 3:1；
- hover、focus-visible、active、selected、disabled、loading、error 均有可区分状态；
- focus 不仅靠颜色微差；全局提供统一 ring，并尊重键盘/指针输入；
- icon-only button 有 aria-label；Tooltip 不能替代 aria-label；
- 状态不能只靠颜色，必须有文本/图标/可访问名称；
- Tabs、Dialog、menu、table、form error 使用正确原生或 ARIA 语义；
- 每个顶级 route 在 light/dark 和 desktop/mobile 上通过 axe serious/critical=0；
- 不用隐藏 `data-testid` 替代用户可感知的 role/name。

## 13. 实施切片

### Slice 0：最新 main 与碰撞复核

动作：

1. 规划重新达到 `3/3` 后在 `main` 提交并推送修正，确认 `main == origin/main`；
2. 检查当前工作区的未提交文件与同名任务，不覆盖、不 stash、不遗漏已有 WIP；如发现外部
   并行修改则按文件所有权避让；
3. 重新生成本规划第 3 节的量化基线；如事实影响方案，先在 main 修订规划并重新执行规划 `3/3`；
4. 在同一工作区切换到 `feature/webui-unified-design-language`，将其快进或合并到最新
   `origin/main`；
5. 创建进度文档，记录基线 SHA、工作区、端口和验证命令。

退出条件：已有 WIP 全部可追溯；没有未处理的文件所有权冲突；规划与最新 main 一致。

### Slice 1：PR-0 行为与设计验收基线

只增加/增强测试和工具，不修改生产页面视觉：

- 建 `e2e/support/designAssertions.ts`：contrast、focus、overflow、geometry、effective surface；
- 建 token/checker focused tests；
- 为 13 个 route 建导航、page header、主题、关键行为和 responsive smoke；
- 冻结第 11 节行为，复用现有 API mocks，不复制整套 fixture；
- 记录当前可接受设计债务 baseline，不把已有失败伪装成新规范通过。

退出条件：新增基线测试在最新 main 通过；测试失败能明确指出 route/theme/state，而不是依赖截图。

### Slice 2：Token、主题与机器门禁

- 增加结构化 token source、确定性 generator 和 tracked outputs；
- 将已有颜色/layer/shadow 归入 canonical source；
- 扩展 design checker 覆盖 CSS/TS/TSX/SVG、alias、z-index、motion、letter-spacing；
- 建版本化 design debt baseline；
- 增加 pre-paint bootstrap、ThemeProvider 与 light/dark/system/cross-tab 测试；
- ThemeToggle 迁移到 lucide 三态控制；
- Recharts 增加 token bridge，保持图表数据和 series 语义不变。

退出条件：generator 幂等；undefined variable=0；新增设计债务=0；theme/Portal/chart 合同通过。

### Slice 3：Primitive 与 Shell

- 实施 Button/IconButton/Tooltip、Field/native controls、Badge/StatusBadge、InlineAlert、
  PageShell/PageHeader/Toolbar/Tabs/TableFrame/EmptyState；
- 审计并稳定 Dialog/ConfirmDialog，与 Button/Field/token 组合；
- 迁移 Layout、Unlock、Dashboard、ThemeToggle、Toast、Skeleton、ErrorBoundary；
- 导航 emoji 替换为 lucide，保持可访问名称、顺序、route-memory 和移动行为；
- 建 design-system harness tests，不新增用户可见“组件展示页”。

退出条件：Shell 在四视口、light/dark/system 下无溢出、低对比或焦点丢失；公共组件 API 有测试。

### Slice 4：高频工作流迁移

#### 4A：Chat 与 Search

- PageHeader、mode/scope 控件、消息状态、composer、来源、结果卡和工具命令；
- 保持 SSE、URL、draft、retry、stop、source action 和请求次数；
- 覆盖空态/streaming/error/disabled/mobile。

#### 4B：Documents、Collections 与 Files

- upload zone、filters、tables/cards、pagination、file tree、preview、danger actions；
- 复用 Dialog/ConfirmDialog/Field/StatusBadge/TableFrame；
- 保持 UUID/path、Collection ACL、分页、上传和当前工作上下文合同。

退出条件：每批 focused Vitest + Mock Playwright + computed style/contrast/geometry 通过后独立提交。

### Slice 5：运营与管理页迁移

#### 5A：Metrics 与 Alerts

- stat card、chart palette/tooltip、table、tab、severity/status、delivery command；
- 保持 polling、数据精度、URL 和 mutation。

#### 5B：Evaluation、Embeddings 与 AB Test

- 统一 tab、form、stat、table、detail、repair/create dialog；
- 解开 Embeddings 对 Evaluation page CSS 的隐式依赖；
- 保持 URL/history、实验数据、repair preview/apply 和表单默认值。

#### 5C：API Keys 与 Settings

- 统一 toolbar、表格、role/capability badge、字段、credential warning 和设置 tab；
- 一次性 secret、权限和保存语义不变。

退出条件：13 个 route 全部进入统一 PageShell，迁移文件不再使用 raw color/legacy alias/数值 z-index。

### Slice 6：债务收口、文档与统一门禁

- 删除 `rg` 证明无调用的旧 class、alias、重复 modal/button/form/table 样式；
- 保留未迁移且有调用的债务，并写明 owner、触发条件和现有保护；
- 新建双语 `docs/webui-design-language-zh-CN.md` / `.md`，把日常规则长青化；
- 将水平对齐指南作为专题章节链接或合并，避免两套规则；
- 更新 AGENTS、docs index、testing/developer/release 文档的双语入口和命令；
- 完成后归档本 plan/progress。

退出条件：全量门禁通过；设计债务 baseline 未增长且迁移范围清零；长青文档可独立指导后续开发。

## 14. 页面迁移与验收矩阵

| 批次 | 页面/组件 | 必守行为 | 主要自动化锚点 |
|---|---|---|---|
| Shell | Layout、Unlock、ThemeToggle、Toast、ErrorBoundary | auth、route-memory、logout、mobile nav、theme | navigation/alignment/workspace continuity |
| Chat/Search | ChatSidebar、CollectionScopeSelector、SearchResults | URL、SSE、draft、mode/scope、history、request JSON | chat/search specs + real Chat smoke |
| Knowledge | Documents、Collections、Files、FilePreview、VersionHistory | upload、pagination、UUID/path、ACL、danger confirmation | documents/collections/files specs |
| Operations | Metrics、MetricsCharts、Alerts | durable facts、polling、tabs、delivery action | pages/alerts specs |
| Quality | Evaluation、Embeddings、ABTest | URL/history、form defaults、repair/experiment mutation | evaluation/embeddings/pages specs |
| Admin | ApiKeys、Settings | capability/role、shown-once secret、provider/model save | api-key/settings specs |

每批文件清单是所有权边界，不要求一次迁完所有细节。若一个批次仍过大，继续按完整用户路径拆小；
不得拆成“先改视觉，后补行为测试”。共享 primitive 的修改只在 Slice 3 或单独 checkpoint
完成，避免多个页面批次并发修改同一个核心组件。

## 15. 自动化验证计划

### 15.1 一次性测试设计

所有本任务验收在进入实现审查前按本节一次性完成。review 阶段不临时发明新矩阵；发现实质
缺陷时修复并重跑受影响门槛，审查计数归零。

### 15.2 Design-system focused tests

至少覆盖：

1. token schema、light/dark 对称、alias 无环和生成幂等；
2. undefined var、raw color、z-index、transition、letter-spacing、跨页 CSS import 扫描；
3. debt baseline 新增/减少/扩张语义和 allow 注释；
4. Button/IconButton/Field/Badge/Status/Dialog 的 variants、disabled/loading/error；
5. Tooltip keyboard/hover、可访问名称和 Portal；Tabs 的方向键、Home/End、roving tabindex
   与受控 URL 状态；
6. Theme preference/resolution/pre-paint/system/storage event；
7. chart token bridge 在主题切换后更新；
8. reduced motion 与 focus-visible。

### 15.3 Mock Playwright 设计矩阵

对 13 个 route 至少覆盖：

- light/dark；Theme contract 另覆盖 system；
- 1440×900 与 390×844；高风险 table/toolbar 另测 1280×800、768×1024；
- PageHeader、主要命令唯一性、状态/空态、关键 form/table/dialog；
- DOM 可见性、role/name、tab order、focus return、URL/history、local/session storage；
- 请求 method/path/body、Mock response JSON 与 mutation 次数；
- computed foreground/background/border、对比度、bounding rect、scrollWidth/clientWidth；
- axe serious/critical=0；
- 不调用 `page.screenshot`，Playwright config 继续 `screenshot: off`。

### 15.4 基本硬门槛

顺序固定：

1. `npm run tokens:check`；
2. `npm run check:design-system` 与 `npm run check:alignment`；
3. `npm run typecheck`；
4. `npm run test:run` 与 `npm run test:design-system`；
5. `npm run lint`；
6. `npm run build` 与 bundle budget；
7. 每批 focused Mock Playwright；
8. 13 route 核心 Mock Playwright；
9. `mvn clean compile test-compile`，证明内嵌 WebUI 所在项目仍可编译；
10. `scripts/dev.sh` 使用隔离端口启动、健康检查与真实前后端 Playwright；
11. project-docs、no-pessimistic-locks、secret scan 与 `git diff --check`。

后端代码和 schema 不在本任务范围，因此不新增本任务 PostgreSQL 行为矩阵；如果实施中被迫修改
任何后端/API，则立即暂停扩散、补独立后端规划和集成测试，不把它伪装成视觉改动。

### 15.5 真实 LLM 回归

本任务会迁移 Chat 的命令、mode/scope、composer 和状态外观。全部 Mock/构建/真实全栈门槛
通过后，从 main `.env` 加载真实配置，使用 `scripts/start-real-e2e-server.sh` 在隔离端口启动
后端，并以 `scripts/real-llm-e2e-smoke.sh` 为基础执行：

1. 一次 KNOWLEDGE 非流式问答；
2. 一次 AGENT SSE，观察检索来源/工具活动与 done；
3. Stop 或错误恢复路径只用 Mock 验证，不浪费真实 provider 调用；
4. 通过 provider/usage 计数证明一次提交没有重复调用；
5. 日志中不记录 secret 或完整模型正文。

真实 LLM 只做共享 UI/filter 回归，不能代替 Mock、Vitest 或设计系统验收。若 provider 返回
外部不可用错误，记录阻断和三次有界预检结果，不把它宣称为本地通过。

### 15.6 Bundle 与 CSS 预算

- 新 runtime 依赖默认只允许 tree-shaken `lucide-react`；其他依赖需单独说明；
- 记录实施起点的 Vite gzip manifest；当前参考值为 initial common JS `110.92 KiB`，最终
  相对实施起点增长不得超过 15 KiB；
- 记录实施起点的构建 CSS gzip 和重复声明基线；最终构建 CSS gzip 相对起点增长不超过
  10 KiB，迁移范围 raw color/legacy alias 为 0，跨页重复的 button/form/table/modal 声明
  必须下降；源码行数仅作解释性信号，不作为诱导压缩或删除必要样式的硬指标；
- 生成 token 不重复为每个页面打包完整主题；
- route lazy split 保持，不把所有页面组件引入 initial chunk。

## 16. 实现后三轮审查

基本硬门槛全部通过后执行三轮只读、互不重叠、每轮最多 45 分钟的检查：

1. **行为与可访问性**：URL、权限、请求、状态恢复、危险动作、keyboard、Dialog、ARIA；
2. **主题与几何**：token、light/dark/system、Portal、contrast、focus、responsive、overflow；
3. **所有权与交付**：generator、debt baseline、primitive API、CSS 分层、bundle、文档和回滚。

只修复影响正确性、可访问性、兼容性、隐私、成本或稳定布局的实质问题。任何实现修改都重跑
受影响门槛并把计数清零；风格偏好和可选优化进入具名 backlog，不在审查循环发散。

## 17. 风险与控制

| 风险 | 控制 |
|---|---|
| 实施时出现新并行 WIP | Slice 0 重新审计；保留他人修改并按文件所有权避让；不并发改核心 primitive |
| 换肤改变工作流 | PR-0 冻结 URL/请求/权限/状态/焦点；页面批次只改视觉与语义 |
| token 形成第二套系统 | 结构化单一源；旧名称只作有期限 alias；禁止页面自建变量 |
| shared component 过度抽象 | 只抽两个以上调用点；variant 有限；领域数据留在页面 |
| 暗色修复破坏浅色 | light/dark 成对 token + 双主题 computed contrast |
| CSS 债务大爆炸重写 | 版本化 baseline、存量只减不增、按用户路径迁移 |
| icon library 增大 bundle | tree-shaken named imports + initial gzip budget |
| 视觉测试脆弱 | 不测像素图；测语义、computed style、contrast 和 geometry 合同 |
| 移动端表格不可用 | TableFrame 局部滚动 + 页面级几何断言，不复制业务 DOM |
| 主题首屏闪烁 | 同源 pre-paint bootstrap 与 React 解析一致性测试 |

## 18. 发布、回退与 Git 交付

### 18.1 提交边界

每个 Slice/页面批次形成独立 checkpoint：

- token/tooling；
- primitive/Shell；
- Chat/Search；
- Documents/Collections/Files；
- operations/quality/admin；
- cleanup/docs。

不把所有页面压成一个不可 review 的提交，也不在页面批次中顺手改 API、数据库或权限。

### 18.2 回退

- token 迁移期间保留旧 alias，单批页面可回退到前一 checkpoint；
- primitive props 保持简单，迁移调用点按完整页面回退；
- 删除旧样式前用 `rg` 和测试证明无调用；
- Theme storage 兼容旧 `light|dark` 与无 key；回退不需要用户数据迁移；
- 不执行后端或数据库回滚。

### 18.3 Main 跟进与最终交付

1. feature 开发期间定期 fetch/merge 已推送的 `origin/main`；
2. 完成后再次 merge 最新 `origin/main`，记录合并后 SHA/端口/证据目录；
3. 按第 15 节从头执行合并后最终门槛，不沿用合并前结论；
4. 完成实现 `3/3` 后把 feature merge 回 main 并 push；
5. 更新双语长青文档，归档本 plan/progress；
6. 确认 main == origin/main、当前工作区干净且无测试服务；
7. 不使用 stash，不删除其他协作者修改或远端 feature branch。

## 19. 完成定义

只有全部满足才算完成：

1. 单一 token source 可确定性生成 CSS/TS，light/dark 核心 key 对称；
2. undefined CSS variable、数值 z-index、迁移范围 raw color/legacy alias 均为 0；
3. 全库设计债务 baseline 不增长并显著缩减；
4. Button/IconButton/Field/Status/PageHeader/Toolbar/TableFrame/Dialog 等有限规格稳定；
5. 13 个 route 全部遵循统一页面层级，不出现 card-in-card 或无功能控件；
6. 导航和工具图标统一使用 lucide，icon-only 命令可访问；
7. light/dark/system、Portal、chart、focus 和 reduced-motion 合同通过；
8. 1440/1280/768/390 视口的关键路径无页面级水平溢出、遮挡或布局跳变；
9. URL、请求体、权限、状态恢复、危险确认和 provider 调用次数无回归；
10. TypeScript、Vitest、lint、production build、Mock Playwright、真实全栈、Maven 编译和
    有界真实 LLM 回归全部通过；
11. 验收证据没有截图依赖、secret 或完整模型正文；
12. 实现连续三轮无修改审查达到 `3/3`；
13. 双语长青设计语言指南、索引、测试/开发参考和 release gate 已同步；
14. feature 合回并推送 main，main 与 `origin/main` 一致且当前工作区干净。

## 20. 规划审查规则与记录

规划审查固定为：

1. **现状与碰撞**：代码量化、历史/当前边界、并行工作区、范围与非目标；
2. **设计与可实施性**：token/主题/primitive/CSS 所有权、页面矩阵和迁移顺序；
3. **验收与交付**：Mock/真实全栈/真实 LLM、无截图证据、review 收敛和 Git 生命周期。

发现影响可实施性、兼容性、可访问性、成本或范围的实质问题时立即修订，并把连续无修改计数
清零。无问题轮次只在任务进度中汇报，不修改本文，避免破坏“无修改”条件。

| 时间 | 范围 | 发现问题 | 处理与计数 |
|---|---|---|---|
| 2026-08-28 | 初始探索 | stable main 在调研期间从 `34979bc3` 前进到 `dae60044`，Dialog/layer/token 检查已交付 | 切换到最新 main 重新量化和验证；删除重复实施范围；计数 0 |
| 2026-08-28 | 初始可实施性审查 | CSS 源码总行数硬预算可能诱导无意义压缩；颜色检查白名单和真实 LLM 脚本不够明确 | 改为构建产物与重复债务预算；明确结构性 CSS 关键字及仓库既有真实 LLM 脚本；计数 0 |
| 2026-08-28 | 文档生命周期审查 | 上一轮 plan/progress 下移到 archive 后，正文内的仓库相对链接需要增加一层 | 修复归档文档相互链接、长青文档和源码链接；计数 0 |
| 2026-08-28 15:20 +08:00 | 第 1 轮：现状、分支碰撞、量化基线与范围边界 | 无 | 未修改规划；计数 1 |
| 2026-08-28 15:20 +08:00 | 第 2 轮：token、主题、primitive、CSS 所有权与迁移顺序 | 无 | 未修改规划；计数 2 |
| 2026-08-28 15:20 +08:00 | 第 3 轮：验收矩阵、真实服务、发布回退与 Git 生命周期 | 无 | 未修改规划；计数 3，允许进入实施 |
| 2026-08-28 | 现状与碰撞审查 | 工作树被并行流程切到同 SHA 的空特性分支；重新统计颜色字面值为 400 行/107 种 | 不使用 stash，安全切回 `main@dae60044` 并保留全部文档修改；更新量化基线；计数 0 |
| 2026-08-28 | 实现边界预审 | “全部 primitive 搬目录”会给稳定 Dialog 制造无价值 churn；Button 与导航链接边界不够明确；SVG `none` 未列为结构语义 | 保持既有 Dialog canonical 路径；新 primitive 才进入 `ui/`；冻结 anchor 语义并补齐 checker 白名单；计数 0 |
| 2026-08-28 | 技术可实施性审查 | generated token CSS 导入顺序未冻结；替换 checker 可能破坏现有 package script 调用者 | 固定 `tokens.css` → `global.css`；保留 `check:design-tokens` 兼容入口并让 lint 聚合新门禁；计数 0 |
| 2026-08-28 | 债务模型审查 | baseline 文件名只表达 color，但方案实际追踪 motion、typography、CSS ownership 和 alias | 统一为 `design-debt-baseline.json`，以 violation kind 覆盖全部存量设计债务；计数 0 |
| 2026-08-28 | 可访问交互审查 | Tabs 若只共享样式/role，会让键盘行为继续散落并可能违反 ARIA pattern | 共享 Tabs 统一 roving tabindex 与方向键/Home/End；页面只拥有受控值、URL 和数据；计数 0 |
| 2026-08-28 | 并发内容漂移审查 | 并发流程把最新的“直接实施、单工作区特性分支”边界误改为“暂停、隔离 worktree” | 恢复最新用户授权和单工作区策略；保留 design debt、Tabs、导入顺序等有效技术改进；计数 0 |
| 2026-08-28 15:26 +08:00 | 修正后第 1 轮：现状、碰撞、量化基线与范围边界 | 无 | 未修改规划；计数 1 |
| 2026-08-28 15:26 +08:00 | 修正后第 2 轮：token、主题、primitive、CSS 所有权与迁移顺序 | 无 | 未修改规划；计数 2 |
| 2026-08-28 15:26 +08:00 | 修正后第 3 轮：验收矩阵、真实服务、回退与 Git 生命周期 | 无 | 未修改规划；计数 3，允许进入实施 |
| 2026-08-28 | Git 并发审查 | 并行流程提前把规划初稿提交并推送为 `main@495e7fce`，其中仍含旧授权表述 | 确认该提交仅改文档、WebUI 代码仍以 `dae60044` 为基线；保留远端历史并以纠正提交交付最终规划；计数 0 |
