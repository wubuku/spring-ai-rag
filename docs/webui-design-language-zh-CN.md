# WebUI 设计语言

> [English](webui-design-language.md) | [中文](webui-design-language-zh-CN.md)

本文是 `spring-ai-rag-webui` 前端开发的**长青参考**。它说明机器门禁实际拦截的规则、
已经存在的基元，以及这些规则背后的判断依据——让你改动时不必重新发现"为什么现在是这个形状"。

这里的规则不是愿望。标注 **门禁强制** 的每一条都由 `npm run check:design-system` 检查，
而该门禁是 `npm run lint` 链的一部分。**如果本文与门禁不一致，以门禁为准，本文即为过期。**

## 1. Token 的唯一事实源

`design-tokens/tokens.json` 是引入颜色、间距档位、字号档位的**唯一**文件。
以下两个产物由它生成，**绝不要手改**：

| 产物 | 消费方 | 重新生成 |
|---|---|---|
| `src/styles/tokens.css` | 所有样式表 | `npm run tokens:build` |
| `src/design-system/tokens.generated.ts` | TS/TSX、图表库 | `npm run tokens:build` |

`npm run tokens:check` 只比对不写盘，CI 应该跑这个。

### 1.1 新增 token

1. 加到 `design-tokens/tokens.json` 中正确的分组。
2. 如果该分组是 `themed: true`，**light 与 dark 两侧都必须给出**。
   只写一侧的分组会被拒绝——不对称的主题正是 `--color-surface-2`
   只在亮色下定义、到了暗色就渲染错的原因。
3. 跑 `npm run tokens:build` 并提交重新生成的产物。
4. 跑 `npm run check:design-system`。

alias 只用于迁移期，在构建期解析，且每条必须指向一个 canonical token。
Batch 757 已移除 8 条兼容 alias，不要重新引入。

### 1.2 在填充面上放文字或图标

当文字或图标落在填充的 `--color-primary`、`--color-error`、`--color-warning`、
`--color-success` 背景上时，用对应的 `on-*` token——
`--color-on-primary`、`--color-on-primary-hover`、`--color-on-error`、
`--color-on-warning`、`--color-on-success`。**不要写 `color: white`**：
Batch 757 实测 9 组「背景 × 主题」组合里，`white` 只有 1 组达到 AA。

`--color-on-primary-hover` 与 `--color-on-accent` 正是白色确实正确的两种情况。
规则是"不劣于白色且达到 AA"，而不是"永不使用白色"。

## 2. 图标

用可 tree-shake 的 `lucide-react` 组件。**禁止**用 emoji / dingbat 当界面图标。

禁止的理由是它们会以三种方式失效：各平台度量不同，无法与相邻文字对齐；
不能继承 `currentColor`，因而无法跟随主题；测试里唯一的断言方式是匹配 Unicode 码点，
既脆弱又容易写错——Batch 760 就出现过一次手写扫描报告"已清零"，
但那个匹配式只覆盖了象形符号区，结果漏掉 13 处活着的字形，**包括全应用每一个 `×` 关闭按钮**。

装饰性图标（旁边已有文字承载语义）加 `aria-hidden="true"`。纯图标控件必须提供显式可访问名称。

## 3. 共享基元

只有**两个及以上真实页面**需要的模式才配进 `src/components/ui/`。
只有一个调用方的基元还不值得存在——这正是 `PageHeader` 先迁 4 个页面而不是 13 个的原因，
也是另外 9 个只有裸 `h1` 的页面被放下的原因。

| 基元 | 提供 | 需要知道的契约 |
|---|---|---|
| `IconButton` | 纯图标命令按钮 | `label` 必填——纯图标控件没有文字可供推断名称。`type` 默认 `button`。 |
| `StatusBadge` | 语义状态 | 只提供 soft 变体。实心变体需要每个 tone 配可读前景色，而这正是 `on-*` token 现在提供的东西。 |
| `EmptyState` | 空列表 / 无数据 | 用判别联合建模：`colSpan` 只在 `as="td"` 时存在。渲染真正的 `<td>` 从根上消除了 `.table td` 特异性冲突，而不是用 `!important` 压过去。 |
| `Tabs` | WAI-ARIA tabs | `tablist`/`tab`/`tabpanel` + roving tabindex + 方向键/Home/End。`tabDomIds()` 两边共用，页面可以自持面板，不必把大块 JSX 搬进 render prop。 |
| `PageHeader` | 页面标题区 | 可选 `description` 通过 `aria-describedby` 与标题关联，副标题不再是"视觉上挨着但语义无关"的段落。 |
| `Tooltip` | 悬停/聚焦提示 | — |

`src/components/Dialog/` 保留自己的路径，它早于 `ui/` 存在。

## 4. 门禁拦截什么

`npm run check:design-system` 扫描 CSS/TS/TSX/SVG，拦截 11 类违规：

1. `undefined-variable` — `var(--x)` 没有定义
2. `numeric-z-index` — 字面量层级，而不是 `--z-*` token
3. `raw-color` — token 源之外的 hex、`rgb()`、`hsl()` 或 CSS **命名色**
4. `transition-all` — 必须逐项枚举属性的宽泛 `transition` 简写
5. `letter-spacing` — 非零字距
6. `important` — 没有说明理由的 `!important`
7. `cross-page-import` — 一个页面 import 另一个页面的 CSS module
8. `legacy-alias` — 兼容 alias 的调用点
9. `emoji-glyph` — 拿 emoji/dingbat 当界面图标
10. `css-syntax` — 解析不过的样式表
11. `weak-allow-reason` — 理由不足八个字符的 `design-token-allow` 注释，
    短到不可能是真正的理由

这份清单不靠人工维护。`scripts/__tests__/design-tokens.test.mjs` 会从门禁脚本
里抽出每一个 `kind`，再与本文中英文两版的清单比对，不一致就失败——所以新增规则
不可能没被写进文档，文档里写过的规则也不可能悄悄消失。

### 4.1 债务只能单调减少

存量债务记录在 `design-tokens/design-debt-baseline.json`，指纹为
`file|kind|value`。**新增违规、计数增加、基线过期三种情况都会失败。**
当前基线为空，所以门禁现在只阻止新增。

基线文件**不存在**表示"没有债务"；**读不了或格式坏了**属于报错——
读不动自己账本的检查器，没资格被信任去执行账本。

### 4.2 `css-syntax` 不可豁免

其他每一类都接受同行或上一行的
`/* design-token-allow: <具体理由> */`，理由过短会被单独判为 `weak-allow-reason`。

`css-syntax` 不接受任何豁免。解析不过的样式表不是风格偏好，
是浏览器根本加载不了的文件。在这条规则存在之前，只有 `npm run build` 会发现它——
Vitest 会 stub 掉 CSS module，而逐行规则看不见多余的花括号，
于是一个多了一个 `}` 的 `FilePreview.module.css` 同时通过了
typecheck、lint 和全部 765 个测试。

### 4.3 掩码注释，但不掩码字符串

`emoji-glyph` 跑在**注释被抹掉、字符串字面量被保留**的源码上。这个不对称是刻意的：

- 散文里可以用 `→` 讲数据流。源码里有十几处这么写，把它判违规会让规则没法用。
- 表达式里选出来的字形——`{open ? '⌃' : '⌄'}`——恰恰是界面图标进入 DOM 的方式，必须抓。

扫描器是字符串感知的，所以 `'https://x/📁'` 里的 `//` 不会被误当成注释开头。

### 4.4 不能失败的门禁比没有门禁更糟

本仓库已经产出过**五次同形状的假绿**，这个形状值得记住：

1. 某次扫描吞掉了工具缺失时的退出码，打印"干净"。
2. 某次手写扫描用的匹配式比它声称执行的规则更窄。
3. 安全断言写成 `if rg ...; then fail; fi; pass` —— 工具缺失时 `if` 条件为假，
   断言**通过**。这一条经过实测：仍然带着有效凭据的响应，以 exit 0 通过了检查。
4. 双语标题结构检查枚举了八个写死的文档对，于是仓库里另外 27 对**从未被检查过**，
   而其中 4 对确实已经漂移。
5. 一个写在行内示例里的 NUL 字节让 git 把一份 11923 行的账本当成二进制，
   它的 diff、blame 和文本检索被静默关闭。

第 4、5 条正是文档门禁现在改为**发现**而不是**列举**的原因：新文档一提交就被覆盖，
受跟踪的文本文件一律扫描 NUL 字节。登记的豁免必须写明原因、必须仍然是坏的，
并受一个只能减少的上限约束。

`verify-project-docs.sh` 现在包含一项检查：凡是用匹配工具的脚本都必须先检查工具存在，
并且两个安全门禁在把 ripgrep 从 `PATH` 移除后确实要非零退出。
这项检查的两半都通过"故意弄坏"验证过。文档完整性规则享有同样的保证：
`scripts/test-support/docs-integrity-self-test.mjs` 断言每条规则**确实拒绝**坏输入，
并且该套件做过变异测试——把行为退回旧的"只查列表"，套件立刻变红。

## 5. 表单可访问性

`npm run check:a11y-forms` 串在 `npm run lint` 里，扫描 `src/` 下每个 `.tsx`，
拦截三类违规，外加一条豁免规则：

- `control-no-name` —— 控件没有可访问名称。**placeholder 不是名称**：字段一旦有内容
  它就消失，控件随即退化成什么都不播报。`aria-label`、用 `htmlFor`/`id` 绑定的
  `label`、包裹式 `label`、或 `aria-labelledby` 都算数。
- `orphan-label` —— 既不指向控件也不包裹控件的 `<label>`。它看起来就是个标签，
  用户会去点，然后什么也不会发生。
- `click-non-interactive` —— 键盘够不到的 `onClick`。光有 `role` 并不够：该元素还必须
  声明 `tabIndex` 并处理按键，否则 role 只是给一个死元素贴了张标签。真正装饰性的
  点击目标应当声明 `aria-hidden="true"`，明确表示不指望键盘能到达它。
- `weak-allow-reason` —— 理由不足八个字符的 `a11y-allow` 注释。

这个门禁**刻意没有债务基线**。写它的时候存在的每一条违规都能修，基线只会变成一份
"机器同意不再上报的 bug 清单"。豁免用同一行或前一行的
`/* a11y-allow: <具体理由> */`；`npm run test:design-system` 会断言上面四类仍然被
强制执行、并且在两种语言里都有文档——与设计门禁同一套漂移检查，规则不可能悄悄消失。

### 5.1 它查出了什么

Batch 776 在写下这条规则之前先量了基线：`Alerts`、`ApiKeys`、`Settings` 一共
**15 个没有可访问名称的控件、15 个孤儿 label**，外加两处键盘完全够不到的点击处理。
其中一处比"够不到"更糟：`VersionHistoryModal` 的版本行在一个 `<div onClick>` 里
套了一个"看起来能聚焦"的 `readOnly` 复选框，于是读屏播报出一个按空格毫无反应的
复选框。现在该行带 `role="button"`、`tabIndex={0}`、`aria-pressed` 和按键处理，
复选框则 `aria-hidden`——按下状态属于这一行，不属于一个用户操作不了的控件。

选两个版本做对比是**循环**交互，不是布尔开关，所以该行是 toggle button，并且
**刻意不用** `role="checkbox"`：复选框承诺"按空格就翻转"，而
`handleSelectForCompare` 并不保证这一点。

## 6. 对齐与布局

`npm run check:alignment` 串在 `npm run lint` 里。居中文本只有在写明理由时才被允许，
目前有 11 处这样的豁免——每一处都是有意的决定，并记录在检查器中。

之所以做成机器规则：正文字块居中是把布局从"可读"拖到"不可读"最常见的单一原因，
而在代码审查里它是隐形的，因为 CSS 只有一行。

## 7. 开始一次界面改动之前

1. 先跑门禁与测试，确认起点是绿的：
   ```bash
   npm run check:design-system
   npm run check:a11y-forms
   npm run test:run
   ```
2. 写新东西之前，先找有没有现成基元。
3. 需要新 token 时，加在 `design-tokens/tokens.json`，不要加在样式表里。
4. 需要新共享基元时，先找到两个真实调用方。
5. 测试在同一个 batch 里写。把门禁弄红的事情没有做完。

## 8. 本文刻意不说的内容

- 不逐页罗列布局。那是代码，代码就是参考。
- 不复述 token 目录。读 `design-tokens/tokens.json`。
- 不叙述规则的历史。要看这个，请读
  `docs/drafts/HARDENING_LOOP_PLAN.md`，那里记录了每一批为什么存在。
