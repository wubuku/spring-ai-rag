# WebUI 统一设计语言实施进度

> **对应规划**：[WEBUI_UNIFIED_DESIGN_LANGUAGE_PLAN.md](WEBUI_UNIFIED_DESIGN_LANGUAGE_PLAN.md)
> **日期**：2026-08-28（Slice 1）· 2026-10-02（Slice 2、Slice 3A）· 2026-10-03（Slice 5 图标收口）
> **状态**：Slice 1、2、3A、3B-1/2/3、4A、4B 已交付，设计债务清零；Slice 5 图标统一收口已交付（Batch 760），其余 Slice 5 待开始
> **工作区**：`/Users/yangjiefeng/Documents/wubuku/spring-ai-rag`
> **分支**：`feature/webui-design-tokens-theme-gates-20261002`（Slice 2）· `feature/webui-shell-primitives-nav-20261002`（Slice 3A）· `feature/webui-status-badge-unify-20261002`（Slice 3B-1）· `feature/webui-empty-state-motion-debt-20261002`（Slice 3B-2）· `feature/webui-oncolor-contrast-20261002`（Slice 3B-3）· `feature/webui-chat-search-page-shell-20261002`（Slice 4A）· `feature/webui-page-header-20261002`（Slice 4B）· `feature/webui-icon-unification-20261002`（Slice 5 图标收口）
> **实施基线**：Slice 1 `origin/main@c36bd43e` · Slice 2 `main@86ae9049`

## 1. 当前约束

- 用户已授权规划完成后直接实施，不等待中途决策。
- 当前为串行开发，只使用主工作区，不创建额外 worktree。
- 不覆盖、不 stash、不丢弃工作区已有或并发产生的修改。
- 规划/进度属于单语过程文档；稳定规则在交付前提升到双语长青文档。
- 前端验收只使用 DOM、ARIA、URL、网络、JSON、computed style、contrast 和 geometry，
  不使用截图作为通过证据。
- Mock 门槛通过后执行真实前后端与必要的真实 LLM/Embedding 验收。
- 后端非本任务生产范围；仍必须通过 `mvn clean compile test-compile` 和服务启动门槛。
- 顶级页面之间不得泄漏滚动位置；路由 pathname 切换后，Layout 主滚动容器必须回到顶部，
  同一页面内仅 query/state 变化时不强制重置。

## 2. 已完成

| 阶段 | 状态 | 证据 |
|---|---|---|
| 上一轮 plan/progress 归档 | 完成 | `docs/drafts/archive/2026-08-28_NEXT_HIGH_VALUE_FEATURES_*` |
| 新规划与 drafts 索引 | 完成 | `main@495e7fce` |
| 并发规划漂移纠正 | 完成 | 保留 design debt、Tabs、token 导入等有效改进，恢复直接实施与单工作区边界 |
| 规划连续三轮无修改审查 | 完成 | `main@58dac02c`，项目文档门禁 11/11 |
| 特性分支同步 main | 完成 | `feature/webui-unified-design-language@58dac02c` |
| 页面滚动泄漏复现 | 完成 | API Keys 滚到底后进入 Files 会继承同一个 `<main>` scrollTop |
| 最新主线同步 | 完成 | 保留现有 WIP，将特性分支快进到 `origin/main@c36bd43e` |
| 滚动回归单元测试 | 完成 | Layout Vitest 3/3；覆盖 `<main>`、documentElement、body 的滚动清零 |
| 滚动回归 Mock Playwright | 完成 | API Keys 80 条真实 DOM 长列表，连续三次切换到 Files，滚动位置均为 0 |
| 前端基本门槛 | 完成 | typecheck、Vitest 251/251、lint/alignment/design-token、production build |
| 完整 Mock Playwright | 完成 | `BASE_URL=http://127.0.0.1:15173`，88/88 通过 |
| 仓库级交付门禁 | 完成 | 文档 11/11、禁悲观锁、shell 语法、diff whitespace、added-line secret scan 全部通过 |
| 后端编译门槛 | 完成 | `mvn clean compile test-compile` BUILD SUCCESS |

## 3. 实施切片

| 切片 | 状态 | 下一退出条件 |
|---|---|---|
| Slice 1：行为与设计验收基线 | 已交付 | 滚动泄漏闭环；Mock 与交付门禁通过 |
| Slice 2：Token、Theme 与机器门禁 | **已交付（Batch 753）** | generator 幂等；undefined variable=0；新增设计债务=0；theme/Portal/chart 合同通过 |
| Slice 3A：命令 primitive + 导航图标 + Shell | **已交付（Batch 754）** | `Layout.module.css` 债务清零；导航图标统一；可访问名称与顺序冻结 |
| Slice 3B-1：StatusBadge 与跨页徽章统一 | **已交付（Batch 755）** | 徽章跨页重复消除；warning 对比度缺陷修复；债务 81→79 |
| Slice 3B-2：EmptyState 与 motion/特异性债务 | **已交付（Batch 756）** | `transition-all`/`!important`/`letter-spacing` 三类归零；债务 79→65 |
| Slice 3B-3：填充面对比度 + alias 收口 | **已交付（Batch 757）** | 债务 65→0；`on-*` 双主题达 AA；兼容 alias 移除 |
| Slice 4A：Tabs primitive 与 tab 语义修复 | **已交付（Batch 758）** | Alerts 从无语义按钮组变为真 tablist；三页统一方向键导航 |
| Slice 4B：PageHeader primitive | **已交付（Batch 759）** | 4 个有主命令/副标题的页面迁移；副标题与标题建立 aria 关联 |
| Slice 5：图标统一收口 | **已交付（Batch 760）** | 渲染标记中 emoji/dingbat 归零；`emoji-glyph` 成为第 9 类门禁违规且基线为空 |
| Slice 4：高频工作流迁移 | 待开始 | 每批 focused Vitest + Mock Playwright + computed style/contrast/geometry 通过后独立提交 |
| Slice 5：运营与管理页迁移 | 进行中（外壳已收口） | **Batch 805**：13/13 路由实测统一——各有且仅有 1 个 `h1._title_…`（父级 `div._titles_…`，24px），`<main>` padding 一律 `24px`；9 个仍在手写 `<h1 className="page-title">` 的页面已全部迁到 `PageHeader`，全局 `.page-title` 类已删除，`check:page-shell` 门禁守护。退出条件已按实测改写（原写的 `PageShell` 组件本仓库不存在） |
| Slice 6：债务收口与双语长青文档 | 待开始 | 全量门禁、文档与交付材料完成 |

## 4. 验证基线

- 当前 WebUI：34 个 Vitest 文件、251 个测试；完整 Mock Playwright 88/88。
- 当前样式：28 个 CSS Module、约 6288 行 CSS、151 个 JSX button、约 400 行字面颜色。
- 当前 dev 栈：后端 `18082`、前端 `15173`，由 `scripts/dev.sh` 管理。
- 最终隔离验收会使用不同端口，避免复用日常服务状态。

## 5. 本批次实现

- `Layout` 为顶级路由 pathname 变化建立滚动边界：同步清零 `<main>`、`documentElement` 和 `body` 的纵横向位置，并在下一帧再次清零，以覆盖路由内容装载和浏览器滚动锚定时序。
- 同一页面内仅 query/state 变化时不触发该重置，因此不会破坏 Search、Files 等页面的 URL 状态恢复。
- Layout 单元测试验证主滚动容器及文档滚动容器；Navigation Mock Playwright 使用 80 条真实 DOM 长列表，连续三次从 API Keys 切换到 Files，断言 window/document/main 均回到顶部。
- 本批次未修改后端、API、数据库、Embedding 或 LLM 路径，真实 LLM/Embedding 验收对本批次不适用。

## 6. 最终验证证据

1. `npm run typecheck`：通过。
2. `npm run test:run`：251/251 通过。
3. `npm run lint`：ESLint、alignment、design-token 检查通过。
4. `npm run build`：生产构建通过。
5. `BASE_URL=http://127.0.0.1:15173 npm run test:e2e`：88/88 通过；包含三次滚动泄漏回归和全部核心 Mock 页面。
6. `mvn clean compile test-compile`：`BUILD SUCCESS`。
7. `./scripts/verify-project-docs.sh`：11/11 通过。
8. `./scripts/verify-no-pessimistic-locks.sh`：通过。
9. `find scripts -type f -name '*.sh' -print0 | xargs -0 -n1 bash -n`、`git diff --check`、新增行密钥扫描：通过。

## 7. 恢复入口

下一步提交并推送当前特性分支，随后合并并推送 `main`，确认 `main == origin/main` 且工作区干净。本批次交付后暂停，不自动开启下一轮规划。

---

# Slice 2：Token、Theme 与机器门禁（Batch 753，2026-10-02）

## S2.1 实施边界

- 只交付规划 §Slice 2 的 foundation 内容：token 源、生成器、设计门禁、主题合同、
  ThemeToggle 三态、Recharts token bridge。**不迁移任何页面样式**，不引入 primitive。
- 冻结既有业务行为、URL 状态、请求体与权限；本切片只改视觉 token 的**来源**和
  **约束**，不改页面外观。
- 后端、API、schema、Embedding 与 LLM 路径全部未触碰，因此不需要真实 LLM 验收。
- 沿用主工作区 + 专用分支（`AGENTS.md` 规则 15），不创建隔离 worktree。
- 交接给 Slice 3 的边界：设计债务基线仍有 47 条指纹，其中 `legacy-alias` 44、
  `raw-color` 28、`transition-all` 8、`important` 3、`letter-spacing` 2。
  页面迁移批次负责让它们单调下降。

## S2.2 勘察发现（与规划基线的偏差）

规划基线写于 `main@dae60044`（2026-08-28），实施前重新统计发现两处必须纠正的事实：

1. **raw color 债务已是 0**。`scripts/design-token-color-baseline.json` 早已为空，
   旧 `check-design-tokens.mjs` 报告 "0 file(s) with grandfathered literal colors"。
2. **旧门禁看不见命名颜色**。它只匹配 hex/rgb/hsl，因此
   `color: white`、`color-mix(in srgb, black …)` 全部漏检。新门禁补上 CSS 命名颜色后
   立刻查出 28 处真实 raw-color 债务。

另外发现两个与 Slice 2 直接相关的真实缺陷：

- `--color-surface-2` 在暗色块中**从未被覆盖**，却被 `Evaluation` 与 `Embeddings`
  两个页面使用——暗色下这两处会拿到浅色值。新门禁的 light/dark 对称性校验从结构上
  消除了这类遗漏。
- `lucide-react@0.468.0` 存在于 `node_modules`，但既不在 `package.json` 也不在
  `package-lock.json`（幽灵依赖）。Slice 2 正式声明并锁定。
- `scripts/verify-no-pessimistic-locks.sh` 在缺少 `rg` 的机器上会打印
  "No explicit pessimistic locks found" 并 `exit 0`——因为 `rg … || true` 吞掉了
  command-not-found。这是一个会放过真实违规的假绿门禁，已补 `command -v` 前置检查。

## S2.3 交付内容

| 交付 | 位置 | 说明 |
|---|---|---|
| Canonical token 源 | `design-tokens/tokens.json` | color/status/chart/shadow/typography/space/radius/control/layer/motion 十组 + 8 条兼容 alias |
| 确定性生成器 | `scripts/build-design-tokens.mjs` | 生成 `src/styles/tokens.css` 与 `src/design-system/tokens.generated.ts`；`--check` 只比对不写盘 |
| 设计门禁 | `scripts/check-design-system.mjs` | 8 类违规：undefined var、数值 z-index、raw color（含命名色）、`transition: all`、非零 letter-spacing、无理由 `!important`、跨页 module import、legacy alias |
| 债务基线 | `design-tokens/design-debt-baseline.json` | 指纹 `file\|kind\|value`；新增/增长/过期三种情况都失败 |
| 主题合同 | `src/design-system/theme.ts` · `themeContext.ts` · `ThemeProvider.tsx` | 偏好与解析主题分离；DOM 只写 `data-theme=light\|dark` |
| Pre-paint bootstrap | `index.html` | 首屏前解析偏好，深色用户不再闪白 |
| ThemeToggle | `src/components/ThemeToggle/` | emoji + `A` 双按钮猜测语义 → lucide `Sun`/`Moon`/`Monitor` 单组三态 radio |
| 图表 token bridge | `src/hooks/useChartTheme.ts` | 通过生成桥接读实时主题值；series 语义与数据不变 |

生成器内置校验：themed 组 light/dark 必须对称、颜色组必须是颜色字面量、CSS 变量名
唯一且 kebab-case、alias 只能指向 canonical token（结构上不可能成环）、TS bridge 键
组限定且组内唯一。

## S2.4 验证证据

1. `npm run tokens:check`：通过；连续两次 `tokens:build` 产物零 diff。
2. `npm run check:design-system`：通过；98 个 token/var 名称、47 条指纹在基线内。
3. `npm run check:alignment`：通过；12 个有意居中例外。
4. `npm run typecheck`：通过。
5. `npm run test:run`：67 文件 **695/695 通过**（原 251 用例基线已随页面增长）。
6. `npm run test:design-system`：**39/39 通过**（生成器与门禁 focused 测试）。
7. `npm run lint`：ESLint 0 error 0 warning、alignment、design-system 全通过。
8. `npm run build`：通过；initial `index` chunk **108.36 KiB gzip**，低于 110.92 KiB
   起点；构建 CSS 合计 17.26 KiB gzip；route lazy split 保持。
9. `mvn clean compile test-compile`：见仓库交付记录。
10. `./scripts/verify-project-docs.sh`：11/11；`./scripts/verify-no-pessimistic-locks.sh`：
    有 `rg` 通过、无 `rg` 明确失败。

## S2.5 顺带修复的技术债

- `CreateCollectionModal.test.tsx` 三处用 `user.type` 逐字输入 101/128/501 字符，
  在全量负载下撞 5s 超时（695 用例中出现 1 次偶发失败，单独复跑全绿）。改为
  `user.click` + `user.paste`：语义不变（测的是长度上限，不是按键处理），事件数从
  O(n) 降到 O(1)，该文件测试耗时 3.15s → 1.21s，全量 22.55s → 19.21s。
- `ThemeProvider.tsx` 同时导出组件与 `useTheme`，触发 `react-refresh/only-export-components`
  警告；拆分出 `themeContext.ts` 后 lint 归零。
- 新门禁扫描前先剥离块注释并保持行号，使样式表可以正当地写明自己被禁止的模式。

## S2.6 下一切片入口

Slice 3 实施 `src/components/ui/` primitive 与 Shell 迁移，前置条件已全部就位：
token 单一来源、门禁可拦截、主题合同稳定、图表走桥接。迁移批次必须让 47 条基线指纹
单调下降，且不允许以 `design-token-allow` 批量豁免换绿。

---

# Slice 3A：命令 Primitive、导航图标与 Shell（Batch 754，2026-10-02）

## S3A.1 实施边界

- 只做 Shell 层的两个命令 primitive、导航图标替换和 Layout 迁移；
  **不迁移业务页面**，不实现 Button/Field/Badge/TableFrame 等其余 primitive。
- `src/components/Dialog/`、`src/components/Button/` 等既有稳定组件保持 canonical
  路径，不为目录整齐制造搬迁 diff。
- 导航的 route-memory、顺序、可访问名称、移动端开合行为全部冻结。

## S3A.2 交付内容

| 交付 | 位置 | 说明 |
|---|---|---|
| IconButton | `src/components/ui/IconButton/` | ghost/secondary/danger × 32/36px；`label` 必填；`type` 默认 button；focus-visible 用 token |
| Tooltip | `src/components/ui/Tooltip/` | 4 个方位；hover + focus 可见；Escape 关闭且不移焦；关闭时 `aria-hidden` |
| 导航图标 | `src/components/Layout/Layout.tsx` | 13 个 emoji → lucide，图标 `aria-hidden`，链接文本承担名称 |
| Shell 迁移 | `Layout.tsx` / `Layout.module.css` | 关闭/菜单按钮迁到 IconButton + Tooltip；CSS 债务清零 |

按钮标签补齐 i18n：`nav.openSidebar` / `nav.closeSidebar`（en + zh-CN 成对），
消除了此前硬编码英文 aria-label 与其余界面不一致的问题。

## S3A.3 验证证据

1. `npm run test:run`：69 文件 **719/719 通过**（Batch 753 为 695，+24）。
2. `npm run test:design-system`：39/39 通过。
3. `npm run typecheck`、`npm run lint`：通过，0 error 0 warning。
4. `npm run check:design-system`：通过；指纹 47 → 45。
5. `npm run check:alignment`：通过；12 个有意居中例外。
6. `npm run build`：通过；initial chunk **110.25 KiB gzip**，仍低于 110.92 KiB
   起点基线（预算上限 125.92 KiB）；构建 CSS 17.61 KiB gzip。
7. `scripts/verify-project-docs.sh` 11/11、`scripts/verify-no-pessimistic-locks.sh`、
   shell 语法、`git diff --check`、新增行密钥扫描：通过。
8. 本切片未触碰后端、API、schema 与 LLM 路径。

## S3A.4 债务变化

| kind | Batch 753 | Batch 754 | delta |
|---|---|---|---|
| legacy-alias | 44 | 41 | -3 |
| raw-color | 28 | 27 | -1 |
| transition-all | 8 | 8 | 0 |
| important | 3 | 3 | 0 |
| letter-spacing | 2 | 2 | 0 |
| **合计** | **85** | **81** | **-4** |

`Layout.module.css` 现已完全无设计债务。

## S3A.5 下一切片入口

Slice 3B：`PageShell`/`PageHeader`/`Toolbar`/`Tabs`/`TableFrame`/`EmptyState`/
`StatusBadge` primitive，迁移 Dashboard、Unlock、Toast、Skeleton、ErrorBoundary。
要求同 Batch 754：债务继续单调下降，`design-token-allow` 不作为批量换绿手段。

---

# Slice 3B-1：StatusBadge 与跨页徽章统一（Batch 755，2026-10-02）

## S3B1.1 勘察结论

徽章是当前**跨页重复最严重**的一处，且重复已经产生行为分叉：

- `ABTest.module.css` 与 `ApiKeys.module.css` 各自定义一份近乎相同的 `.badge`；
- `ApiKeys` 单页内并存两套徽章视觉：实心 `active/expired/disabled` 与柔和
  `admin/normal/pending`，同一概念两种表现；
- `ABTest` 用 `style={{ background: STATUS_COLORS[...] }}` 内联覆盖调色板，
  配合 `.badge { color: white }`——白字配 warning（`#f59e0b`）对比度约 2:1，
  低于 WCAG AA 的 4.5:1，属于真实可读性缺陷，不只是风格不一致。

## S3B1.2 关键设计决定：只提供 soft 变体

实心徽章需要为每个 tone 配一个可读前景色，而现有 status token 组只提供
bg / border / text 三元组，没有实心所需的 `on-*` 色对。强行补齐会引入一组
没有真实使用证据的 token，违反"有限规格优于全能组件"。因此首版只提供 soft，
直接消费既有三元组，两种主题下对比度都成立。

这条决定已写进组件注释，避免后续被当成"缺功能"而不是"有意收敛"。

## S3B1.3 交付与验证

| 项 | 结果 |
|---|---|
| `src/components/ui/StatusBadge/` | 6 tone、soft 单一变体、co-located CSS、8 个 focused 测试、index 出口 |
| `ApiKeys.tsx` | 6 处 call site、6 个 CSS 变体改走 primitive |
| `ABTest.tsx` | `STATUS_COLORS` 内联背景 → 显式 `STATUS_TONES` 语义映射 |
| 页面 CSS | 删除重复规则：ABTest 214→206 行，ApiKeys 394→363 行 |

- `npm run test:run`：70 文件 **731/731 通过**（Batch 754 为 719，+12）。
- `npm run test:design-system`：39/39；typecheck、lint、tokens:check、
  check:design-system、check:alignment 全通过。
- `npm run build`：通过；initial chunk 110.25 KiB gzip 不变。
- 设计债务 **81 → 79**（raw-color 27→25），指纹 45 → 43。
- `scripts/verify-project-docs.sh` 本批实际拦下 CSS 删除遗留的 EOF 空行，已修正。

## S3B1.4 下一切片入口

Slice 3B-2：`EmptyState` / `PageHeader` / `TableFrame` / `Tabs` primitive，
迁移 Alerts、Metrics、Evaluation、Settings 等仍使用 `transition: all`（8 处）与
跨页 form/table 声明的页面。

---

# Slice 3B-2：EmptyState 与 motion / 特异性债务清零（Batch 756，2026-10-02）

## S3B2.1 勘察结论

空态是本项目**跨页重复最严重**的一处：9 个文件共 15 处调用，7 份页面 CSS 各自
重写几乎相同的三条声明。更关键的是重复已经催生了一个 hack——Documents 的空态挂在
`<td>` 上，被 `.table td { padding: 0.75rem 1rem }` 压过，于是写了
`padding: 2rem !important`。

## S3B2.2 关键决定

1. **宿主用判别联合建模**：`as="td"` 才允许 `colSpan`，`as="div"` 不允许。
   把两者混成一个宽松联合会让错误在运行时才暴露。
2. **渲染真正的 `<td>` 而非 div 套 class**：这让特异性冲突从根上消失，
   而不是用 `!important` 换一个更高优先级。
3. **两处大写标签字距归零，而不是给门禁加分类**：规划冻结了"字距全局为 0"。
   为了两个调用点去扩宽自己刚建的规则，正是"改门禁换绿"，因此选择遵守规则。

## S3B2.3 债务变化

| kind | Batch 755 | Batch 756 | delta |
|---|---|---|---|
| transition-all | 8 | **0** | -8 |
| important | 3 | **0** | -3 |
| letter-spacing | 2 | **0** | -2 |
| legacy-alias | 41 | 40 | -1 |
| raw-color | 25 | 25 | 0 |
| **合计** | **79** | **65** | **-14** |

指纹 43 → 32；CSS 净减 67 行（27 增 / 94 删）。三类规则**彻底归零**。

## S3B2.4 验证证据

- `npm run test:run`：71 文件 **737/737 通过**（Batch 755 为 731，+6）。
- `npm run test:design-system`：39/39；typecheck、lint、tokens:check、
  check:design-system、check:alignment 全通过。
- `npm run build`：通过；initial chunk 110.28 KiB gzip；构建 CSS 17.59 KiB gzip。
- `scripts/verify-project-docs.sh` 11/11、`verify-no-pessimistic-locks.sh`、
  shell 语法、`git diff --check`、新增行密钥扫描：通过。
- 本切片未触碰后端、API、schema 与 LLM 路径。

## S3B2.5 下一切片入口

Slice 4：Chat / Search / Documents 高频工作流迁移。剩余债务集中在
`legacy-alias`（40）与 `raw-color`（25），二者都指向 `color: white` 一类
已不存在的旧契约，适合按页面批次逐个迁移。

---

# Slice 3B-3：填充面对比度修复与兼容 alias 收口（Batch 757，2026-10-02）

## S3B3.1 核心发现：这是可访问性缺陷，不是风格分歧

Filled 按钮与徽章此前一律使用 `color: white` 配彩色背景。按 WCAG 相对亮度公式
实算，9 组「背景色 × 主题」组合中 **8 组低于 AA 要求的 4.5:1**：

| 背景 | 主题 | 白字对比度 | 修复后 |
|---|---|---|---|
| warning `#f59e0b` | light | **2.15** | 8.72 |
| warning `#fbbf24` | dark | **1.67** | 11.22 |
| primary `#60a5fa` | dark | **2.54** | 7.36 |
| primary `#3b82f6` | light | 3.68 | 5.09 |
| error `#ef4444` | light | 3.76 | 4.98 |

按钮文字约 `0.9rem` 常规字重，不满足 large text 豁免条件，因此适用 4.5:1。

## S3B3.2 关键决定

1. **前景值由计算得出而非目测**，并且要求**每个 token 在两个主题下都达标**——
   这就是为什么 `on-primary-hover` 必须按主题取不同值（light 白色 5.17 /
   dark 近黑 5.09），而其余几个可以统一取近黑。
2. **不写"永不使用白色"的规则**：白色在 primary-hover 与 accent 上才是正确选择。
   可执行断言写成"所选前景不劣于白色且达标"，而不是一个本身错误的绝对规则。
   这一点是测试先写错、跑红后改对的。
3. **顺带修正了 Batch 753 遗留的错误值**：`--color-on-primary` 当时 light 是白色
   （3.68，不达标）。新增测试第一次运行就抓到了它。

## S3B3.3 债务与 token 体系收口

| 阶段 | 债务 | 指纹 |
|---|---|---|
| Batch 753 立项 | 85 | 48 |
| Batch 756 后 | 65 | 32 |
| **Batch 757 后** | **0** | **0** |

8 条兼容 alias（`--color-background`、`--color-text-secondary` 等）在调用点归零后
已从 `tokens.json` 移除，token 体系不再有双轨命名。

## S3B3.4 验证证据

- `npm run test:run`：71 文件 **737/737 通过**。
- `npm run test:design-system`：**50/50 通过**（+11，含对比度断言）。
- typecheck、lint、tokens:check、check:design-system（0 债务基线）、
  check:alignment 全通过；build initial chunk 110.28 KiB gzip。
- `scripts/verify-project-docs.sh` 11/11、无悲观锁门禁、shell 语法、
  `git diff --check`、新增行密钥扫描：通过。

## S3B3.5 下一切片入口

Slice 4：Chat / Search / Documents 高频工作流迁移（PageHeader、Toolbar、
TableFrame、Tabs）。设计债务已清零，门禁此后只阻止**新增**，
baseline 保持空基线即可。

---

# Slice 4A：Tabs primitive 与 tab 语义修复（Batch 758，2026-10-02）

## S4A.1 勘察结论：重复已经分叉成 a11y 缺陷

三个页面各写了一套 tab 条，且不是"风格不同"那么简单：

| 页面 | 现状 |
|---|---|
| Alerts | 纯 `<button>`，**无任何 tab 语义**；无 `aria-selected` |
| Settings | 有 `role="tablist"` / `role="tab"` / `aria-selected`，但**无 `tabpanel`** |
| Evaluation | 有 `role="tablist"` / `role="tab"`，**无 `aria-selected`** |

三套视觉（下划线 / 圆角顶 / 描边胶囊）也各不相同，且都没有方向键导航。
Alerts 的问题最严重：屏幕阅读器会把它读成四个互不相关的按钮，用户无法知道
当前处于哪个标签、也无法把标签与内容关联起来。

## S4A.2 关键决定：两种使用模式

`Tabs` 是受控组件（`activeId` + `onChange`），页面继续持有 active id——这是三页
把标签映射到 `?tab=` URL 的既有约定，不能因为抽组件而丢掉。

面板归属分两种：

- **primitive 自持面板**（Alerts）：item 带 `render`，primitive 渲染
  `role="tabpanel"` 并自动接线，省掉页面里四个近乎相同的按钮块。
- **页面自持面板**（Settings、Evaluation）：面板分别是 275 行和 7 个 tab 的大块
  JSX，塞进 render prop 会造成大量无价值搬运。因此 item 的 `render` 可选，
  页面用导出的 `tabDomIds(idPrefix, id)` 复现同一组 id，手动接线。
  两种模式共用一套 id 生成逻辑，不会漂移。

## S4A.3 测试暴露的问题

迁移后 7 个既有测试失败。逐个查看后发现，它们**固化了错误的语义**：

- `getByRole('button', { name: 'alerts.sloConfig' })` 断言的是标签页**本应是按钮**
  ——而这正是要修的缺陷。改为 `getByRole('tab', ...)`。
- `findByLabelText('evaluation.tabSuites')` 原本只匹配按钮；加了
  `aria-labelledby` 之后面板也被该标签关联，查询命中两个元素。改为断言
  `aria-selected="true"` 与 `aria-labelledby` 指向 tab id——比原来更强。

这类失败是"测试跟不上契约改进"的信号，不应通过回退实现来消除。

## S4A.4 结果

- `npm run test:run`：72 文件 **753/753 通过**（Batch 757 为 737，+16）。
- `npm run test:design-system`：50/50；typecheck、lint、tokens:check、
  check:design-system（0 债务）、check:alignment 全通过。
- `npm run build`：通过；initial chunk 110.28 KiB gzip 不变。
- 删除三页共 **95 行**重复 tab CSS。
- `scripts/verify-project-docs.sh` 11/11、无悲观锁门禁、shell 语法、
  `git diff --check`、新增行密钥扫描：通过。

## S4A.5 下一切片入口

Slice 4B：`PageHeader` primitive（标题 + 可选副标题 + 页面级主命令），
先迁移确有主命令或副标题的页面（Collections、Files、Embeddings、Chat），
验证 primitive 值得存在之后，再批量迁移其余仅含裸 `h1.page-title` 的页面。

---

# Slice 4B：PageHeader primitive（Batch 759，2026-10-02）

## S4B.1 勘察结论

13 个页面都写 `<h1 className="page-title">`，其中 4 个页面的标题区已各自长出不同的
头部行：

| 页面 | 头部现状 |
|---|---|
| Collections | `.header` flex + 创建按钮 |
| Files | `.header` + `.actions`（上传投放区） |
| Embeddings | 标题 + `<p className={styles.muted}>` 副标题 |
| Chat | `.header` > `.headerLeft`（☰ + 标题）+ 导出菜单 + 新会话 |

四套 flex 规则、四套间距。Embeddings 的副标题尤其值得修：它和标题在视觉上相邻，
语义上却毫无关联。

## S4B.2 关键决定

1. **副标题用 `aria-describedby` 与标题关联**，从"挨着的段落"变成真正的描述关系。
2. **本批只迁 4 个页面**。其余 9 个只有裸 `h1.page-title`，一次全量替换属于为凑
   覆盖率而抽象；先证明 primitive 在真实复合场景下成立，再谈批量。
3. **顺带修 Chat 的 `☰` emoji** → lucide `PanelLeft` + `IconButton`，
   这是 Slice 3 图标统一遗留的部分。

## S4B.3 过程记录

迁移 Files 时走了两次弯路，最终采用最小外科式替换（只换外层包裹与闭合标签，
子树原地不动）：

- 第一次按估算行号切割，偏移错误导致 JSX 结构断裂；
- 第二次改用 prettier 修复格式，结果在 1000+ 行文件上产生 **342 增 / 376 删**的
  无关重排，随即 `git checkout` 还原并放弃对该文件运行 prettier。

教训记录在此：行号定位 + 全文件格式化会制造与任务无关的巨大 diff。

## S4B.4 结果

- `npm run test:run`：73 文件 **762/762 通过**（Batch 758 为 753，+9）。
- `npm run test:design-system`：50/50；typecheck、lint、tokens:check、
  check:design-system（0 债务）、check:alignment 全通过。
- `npm run build`：通过；initial chunk 110.29 KiB gzip。
- 删除三页共 **40 行**各自为政的头部 CSS。
- `scripts/verify-project-docs.sh` 11/11、无悲观锁门禁、shell 语法、
  `git diff --check`、新增行密钥扫描：通过。

## S4B.5 下一切片入口

Slice 5 的两块已勘察清楚：

1. **图标统一收口**（Slice 3 未完成部分）：Files 的文件类型图标
   （📁📄🖼️📝📎）、Documents（📁✕）、Chat（👍👎）、Settings（🇺🇸🇨🇳）、
   ReembedAllButton（⚠️▲▼）、ErrorBoundary（⚠️）、FilePreview、SearchResults（🔍）。
   Files 的 `FileIcon` 是最大聚集点，且已有测试断言具体 emoji 字符。
2. **其余 9 个裸标题页面的批量迁移**，以及 Alerts/Settings/Evaluation 之外的
   运营页统一。
