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
| `QueryErrorBanner` | 读操作失败的可见呈现 | `role="alert"` 而非 `role="status"`。`onRetry` 可选，接 react-query 的 `refetch`；`detail` 承载抛出的错误消息。第 9 节说明为什么一次失败的读不允许看起来像"空的"。 |

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

**这个豁免直到 Batch 878 才真的生效。** 理由被记录在每一条违规上，而读这个字段的
只有一处：决定要不要多打一行提示。门禁据以判红的计数是**从所有违规**建起来的，
豁免与否一视同仁——于是一条带着完全站得住脚的豁免理由的代码照样红，
而门禁自己的报错信息正是在教读者去写这个注释。真实树从未用过这个逃生舱，
这正是它活下来的原因：一次端到端探针把真违规放进真实文件、加上注释、
看着退出码纹丝不动停在 1。成功信息是同一种毛病：它报的
"N grandfathered debt fingerprint(s) at baseline" 里的 N 其实是**发现的**不同违规数，
这个巧合只在该数为 0 时成立。两处现在都按真实含义措辞，
基线周围那套债务契约——"计数只能减"、"陈旧条目要失败"、
"读不动账本属于报错而不是没有账本"——被抽成有测试的函数。
约七十行门禁逻辑在注释里做了这三条承诺，背后却没有任何一条断言。

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
拦截五类违规，外加一条豁免规则：

- `control-no-name` —— 控件没有可访问名称。**placeholder 不是名称**：字段一旦有内容
  它就消失，控件随即退化成什么都不播报。`aria-label`、用 `htmlFor`/`id` 绑定的
  `label`、包裹式 `label`、或 `aria-labelledby` 都算数。
  `<button>` 同样在覆盖范围内（Batch 876），判据是它的**文本内容**而不是属性——
  因为按钮播报的就是文本。`aria-label=""` 会上报：属性存在不等于属性可用。
  `aria-hidden="true"` 子树里的文字**不算**名称——可访问名的计算会跳过它——
  因此 `<button><span aria-hidden="true">…</span></button>` 是一个无名按钮，
  `DocumentActionsMenu` 的触发器正是这个形状。`{t('x')}` 表达式和 `{...rest}`
  透传展开都算有名：两者静态都不可判定，报了就是在报正确的代码。
- `orphan-label` —— 既不指向控件也不包裹控件的 `<label>`。它看起来就是个标签，
  用户会去点，然后什么也不会发生。
- `click-non-interactive` —— 键盘够不到的 `onClick`。光有 `role` 并不够：该元素还必须
  声明 `tabIndex` 并处理按键，否则 role 只是给一个死元素贴了张标签。真正装饰性的
  点击目标应当声明 `aria-hidden="true"`，明确表示不指望键盘能到达它。
  `<a>` 也在覆盖范围内（Batch 876），并且是唯一**不**豁免的原生交互元素：
  它只有在带 `href` 时才算链接、才算可聚焦。没有 `href` 的 `<a onClick={go}>`
  只是一个更差劲的 div，这个操作对 Tab 顺序完全不可见。

  属性名按属性匹配，不按后缀匹配。`data-href` 不是 `href`，`data-aria-hidden`
  也不会把元素移出可访问性树；两者此前都被当真了，而两个方向都是 fail-open——
  规则恰好跳过了本该上报的那个元素。
- `weak-allow-reason` —— 理由不足八个字符的 `a11y-allow` 注释。
- `dialog-title-can-be-empty` —— `title` 可能求值为空串的 `<Dialog>`。对话框用
  指向自身 `<h2>` 的 `aria-labelledby` 给自己命名，标题一空，读屏就只播报一个
  无名的 "dialog"，视觉上标题栏也是空的，明眼用户同样不知道自己打开了什么。
  而 `` `${前缀} — ${用户数据}` `` 这种形状永远不可能为空，因此不拦——
  `VersionHistoryModal` 的标题正是这一类。字面量 `title=""` 同样会上报：
  `aria-labelledby` 仍指向那个空的 `<h2>`，读屏播报无名对话框，标题栏视觉上
  也是空的。（Batch 857：空标题原本被 `continue` 跳过，而那恰恰是这条规则
  要防的缺陷最直接的制造方式。）
- `component-accessible-name-empty` —— 可访问名经由 prop 传入、且可能为空的自定义
  组件：`<IconButton label="" />` 给 `<button>` 命名的方式与 IconButton 内部完全
  一样，而这个空字面量既不满足规则 1（它从元素上读可访问名），也不满足
  `check-hardcoded-copy`（它找的是未翻译的文案，不是空值）。prop 名字是**发现**的，
  不是列出来的——见 `scripts/lib/accessible-name-props.mjs`：组件声明 `p?: string`
  且把 `p` 透传进 `aria-label` / `title` 才算；`ReactNode` 类型的 prop 被排除，
  因为在那儿写字面量连类型错误都不是，报了就是误报，而误报会被 allowlist 掉。
  （Batch 857）

这个门禁**刻意没有债务基线**。写它的时候存在的每一条违规都能修，基线只会变成一份
"机器同意不再上报的 bug 清单"。豁免用同一行或前一行的
`/* a11y-allow: <具体理由> */`；`npm run test:design-system` 会断言上面六类仍然被
强制执行、并且在两种语言里都有文档——与设计门禁同一套漂移检查，规则不可能悄悄消失。

`check-hardcoded-copy` 带的是另一种名单：七条**有意**保持英文的字符串，按 `path:copy`
索引，每条都写着它"正确"而不只是"被容忍"的理由。它是本仓库唯一一份直接写在门禁源码里
的 `Object.freeze({...})` 名单——所以"会不会烂"是最明显的风险。
**Batch 880 把"烂"变成了失败。** 头注释一直写着"没用的条目要删掉，不能让它烂着"，
那是一句关于纪律的承诺而不是一项检查，而**没有任何东西在强制它**。
烂掉的条目并不是惰性的：因为键是 `path:copy`，字符串一旦消失，这条条目就会继续
静默放行同一文件里**下一次**出现的同样文本，而它援引的理由是几个月前为那个
已经不存在的字符串写的。成功信息是同一种毛病——它数的是**命中次数**却按
**条目数**来印，于是一条命中两次、另一条零命中，它照样打 "7"。
现在两个数都印出来，而它们只可能朝"失败"的方向不一致。

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

## 6. 写操作必须报告失败

`npm run check:mutation-errors` 串在 `npm run lint` 里，扫描 `src/` 下每个 `.tsx`，
拦截五类违规：

- `silent-mutation` —— 既没有传 `onError`（通常是 `showToast`），
  也没有在同一个文件里渲染它自己的 `.isError` 的 `useMutation`。
- `no-op-error-handler` —— `onError` 存在但函数体是空的。
  **吞掉错误的处理器不是处理器。**
- `unreasoned-failure` —— `onError` 用一句固定的文案报告失败，
  而那句话里没有任何来自失败本身的内容。
  **"失败了"不等于"为什么失败"。**
- `interpolated-reason` —— 失败消息被手工拼装、或被塞进翻译句子里，
  也就是**未经任何过滤**就到达用户。
  **"到达用户"不等于"在途中被过滤过"。**
- `swallowed-rejection` —— `catch` 块丢掉了失败，却没说清为什么丢掉是正当的。

Batch 791 把 `src/` 里全部 **37 个** mutation 过了一遍，查出 **6 个失败不可见**：
`Embeddings` 的 `cancelM`、`retryM`、`applyRepairM`，以及 `Evaluation` 的
`createM`、`versionM`、`startM`。它们全都有 `onSuccess`，却没有报告失败的途径。
在某个任务的后端返回 500 时按下"取消任务"，页面不会闪动、不作解释，
任务看起来原封不动——一次被拒绝的写操作和一个坏掉的按钮完全无法区分，
用户只会再按一次。

`apiClient` 的响应拦截器救不了它们：它归一化消息、在 401 时清掉凭据，然后 reject。
除非组件自己决定把它显示出来，否则屏幕上什么都不会出现。

**Batch 798 发现第一条规则整整七个批次都在问错问题。** 它只检查 `onError`
这个键在不在，而 `onError: () => {}` 正好满足。`Alerts.tsx` 就带着四个这样的
处理器——SLO 配置与静默计划的创建和删除各一个——而门禁一直是绿的。
在两个创建 mutation 上它比静默写更糟：`onSuccess` 会调 `onHideForm()`，
于是**被拒绝的创建会关掉表单并清空字段**。那看起来就像保存成功了。
规则现在检查函数体；其中两条的文案（`alerts.createError`、`alerts.deleteError`）
原来就一直躺在两种语言文件里，正是为这个处理器写的，却从未被任何地方引用。

`swallowed-rejection` 是这里最弱的一条规则——谁都可以写一句 `// ignore`，
而这正是它的用意。`src/` 里每一个正当的 `catch` 都带着一句说明：
"存储在受限浏览器环境下可能不可用"、"错误上报绝不能弄坏界面"、
"主题在这个标签页里仍然生效"。要求这句话，只是在做决定的那一刻多花一行。
**写 console 不属于这条规则**：console 轨迹是一个有可见痕迹的决定，
而其中哪些该给用户看、哪些不该，是产品判断。

**Batch 859 发现上面三条规则在一种处理器上全都会放行：它存在、它不是空函数、
而它什么也没回答。** 十一个 mutation 写着
`onError: () => showToast(t('alerts.deleteError'), 'error')`——固定文案、
没有参数，服务器的原因在**签名处**就被丢掉了，而不是在函数体里。
`api/client.ts` 早就把 `response.data.message` 提到了 `Error.message` 里，
信息一直就在浏览器里。而门禁自己的总结句写的是
"every write action reports its failure"，对这十一个而言那是假的：
用户报一句"删除失败了"，运维无法判断是集合仍被引用、密钥已被吊销，
还是网络断了。

`unreasoned-failure` 只问一个很窄的问题：**toast 的第一个实参本身**是不是裸的
`t('字面量')`？凡是带原因的写法都放行——共享的
`failureMessage(t, key, error)` 把 `t` 当值传而不是当调用传；本地格式化函数
`formatMutationError(t('k'), error)` 里它是调用的实参；插值写法
`t('k', { error: msg })` 有第二个实参；而由失败本身拼出来的 key
（`t(\`documents.relocationErrors.${code}\`)`）**就是**具体原因，不是耸肩。
这个规则的第一版写得更粗，实测在全部十四个带名参数的处理器上**命中 0 处**，
于是没有上线：一条永远不触发的规则，会让人一直以为它管住了。

规则报在 `onError` 那一行，这样 `mutation-error-allow` 豁免注释就能像另外两条
规则一样，紧贴在决策的正上方。它**跟不进本地 helper**：`Documents.tsx` 曾有
五个 mutation 共用一个 `handleMutationError`，原因死在 helper 内部，
任何规则都看不见；Batch 859 修的是那个 helper，而不是教门禁去追进函数里。

**Batch 861 追问的是：在"原因确实到达了用户"的那些路径上，会发生什么。**
其中九处把消息拼成 `t('files.importError', { error: msg })` 或者
`` `${t('k')}: ${message}` ``，原因就是 `catch` 块碰巧产出的那个东西。
`api/client.ts:54` 在网络失败时 reject `new Error('Failed to fetch')`，
于是用户读到的是"Import failed: Failed to fetch"——一句讲连接而非讲写入的话，
没有长度上限，也无法与服务器的回答区分开。
`utils/failureReason.ts` 提供的每一个过滤器（传输层哨兵表、200 字上限、
丢弃堆栈）在那条路径上**根本不存在**。Batch 860 把这九处搬到了
`failureMessage` 上；这条规则就是防止它们长回来的东西。

**作用域是这条规则的要害。** `files.embedFailed` 同样在插值——
`t('files.embedFailed', { message: result.embedMessage })`——而它是对的，
因为那个消息来自 **200 响应**、说明嵌入为何没完成，并且位于 `try` 块里。
一次成功请求的状态说明不是失败原因，把它送进哨兵表才是错误。
所以规则只看 `onError` 函数体与 `catch` 块：当前 103 个组件文件里 0 违规，
而 Batch 860 之前是 9 个。**误报数为零是实测，不是愿望**——那个数字是把规则
跑在 Batch 860 之前的那棵树上量出来的。

和上面那条规则一样，它跟不进本地 helper。

修复里有两处细节必须做对，两处都有测试钉住：

- 两个兄弟动作共用一条提示时，提示必须**指名是哪一个失败**，
  否则用户会去怪另一个按钮。
- 模态框打开期间发生的失败，提示必须渲染在**模态框内部**，因为模态框会盖住页面。
  `applyRepairM` 失败时对话框保持打开，写在它背后的提示用户永远看不到。

这个门禁和可访问性门禁一样**刻意限定在文件范围内**。
把 mutation 传给子组件、在子组件里渲染错误的形态，检查器跟不进去；
而会误报的门禁只会被忽略。豁免用行内
`/* mutation-error-allow: <具体理由> */`；目前没有登记任何豁免。

## 7. 你要的每个键都必须在每种语言里存在，而没人用的键也不许留在那儿

`npm run check:i18n-keys` 串在 `npm run lint` 里，强制四条规则：

- `missing-locale-key` —— 组件里用了 `t('some.key')`，
  但 `en.json` 或 `zh-CN.json` 里没有 `some.key`。
- `locale-key-asymmetry` —— 两个语言文件的键集不相等。
- `dead-translation-fallback` —— `t('x') || 某物`。
- `dead-locale-key` —— **两个语言文件都有、却没有任何源码能到达**的键（Batch 818）。

i18next 遇到缺失的键不会大声失败，它返回**键名本身**，而那个字符串是真值：

```ts
i18next.t('common.next');                   // "common.next"（英文，Batch 792 之前）
i18next.t('common.next', { lng: 'zh-CN' }); // "下一页"
```

所以 `t(...) || '英文兜底'` **不是**安全网——它永远不会触发。
Batch 792 找出 16 处这样的写法，其中 3 处正是一个真缺失的键与
页面渲染出裸的 `documents.loadError` 之间唯一的东西。

那时共有 **7 个键缺失**。`common.next` 和 `common.previous` 在 `zh-CN.json` 里有、
在 `en.json` 里没有，于是版本历史的翻页按钮在英文界面上显示的是 `common.next`。
`documents.searchPlaceholder`、`documents.loadError`、`search.history`
两种语言都没有。而 `common.preview` 两种语言都没有——
**因为 Batch 789 引入了它却没有加上翻译**，正是这道门禁现在能在下一批里抓住的那类回归。

前三条规则指向同一个方向：**代码在向 locale 要一个键**。第四条指向相反方向。
它之所以存在，是因为一道只观察"要"这个方向的门禁，在结构上就看不见
**没有任何东西渲染的文案**——而那种文案照样在两个语言文件里各占一行，
并且在它所属命名空间的每一次 diff 里都占位置。

### 对死键规则来说，什么算"引用"

另外三条规则只认 `t('字面量')`，因为前缀不是键，而动态调用不跑组件就没法解析。
死键规则必须宽容得多，否则它会把活的文案报成死的。它把"**组件源码里任何一个
等于该键的字符串字面量**"都算作引用——这条最粗的判据恰好覆盖了本仓库真正在用的
五种动态取键形态：

| 形态 | 例子 |
|------|------|
| 模板前缀 |  `` t(`theme.${mode}`) `` —— `theme.` 下的键全部算可达 |
| 别名翻译函数 | `translate` 是作为参数传进辅助函数的 `t` |
| i18next 复数族 | `t('search.resultsCount', { count })` 到达 `…_one` 与 `…_other` |
| 查找表 | `CALLER_VISIBLE: 'collectionScope.callerVisible'` |
| 数据数组 | `['report', 'evaluation.tabReport']` |

刻意选最粗的判据是**为了让失败方向朝安全的那一侧**：它只可能**漏报**引用，
不可能凭空造出一个引用，因此门禁永远不会给出它没有挣到的"可以删"结论。
把每种形态都精确建模，则会在第六种形态出现的那一刻变成错的——而这里的错误
答案是去删掉别人还在渲染的文案。

这份宽容同时也是门禁对你提出的常设要求：**如果某个键真的是运行时拼出来的，
就在源码里用字符串字面量把它写出来。** 本仓库的查找表和数据数组已经是这样
把键"续命"的，这比让检查器去学第六种形态便宜得多。

### 那个不可行动的数字

Batch 818 之前，这道门禁的最后一行是一个数字：*176 key(s) are reached only
through dynamic template calls or are unused*。它听上去像测量结果，其实不是——
它把两个需要**相反**处理的总体加在了一起：其中一部分键是活的、经由模板到达；
另一部分是死的。这个和没人能处理，于是这行字被读过一次就再没人看，
**50 个死键就这样活了下来**，两个语言都有。

判据本身在可信之前被修正过，而且每一次修正都是可测量的。旧门禁认出 745 个键中的
569 个，把剩下 **176** 个一起塞进"动态或未使用"。加上模板前缀与复数处理、但**不加**
字面量判据时，认出 613 个，剩 **132** 个。再加上字面量判据，剩 **50** 个——
而这 50 个才是真正死掉的，两个语言里都已删除。随后再做一次变异（在清理后的树上
再次去掉字面量判据）显示：该判据对幸存的 **82** 个键是承重的；没有它，门禁会再一次
把活着的文案报成垃圾。

这 50 个键是在删除前逐个手工确认的。为防巧合匹配兜底，每个候选键的**叶子名**
都在全源码里扫了一遍：11 处命中，全属巧合（`'collection'` 作范围值、
`'search'` 作路由段）。**门禁报告是一份待核查的嫌疑人名单，不是一份可以直接
执行的删除清单。**

行内 `/* i18n-allow: <具体理由> */` 是**一条标注，不是一个豁免**；目前没有登记任何标注。
在 Batch 879 之前，本文、门禁自己的报错信息、门禁的实际行为三方互相矛盾：
报错信息把这条注释列为三条补救措施之一，而门禁照样红——只不过把理由当作
`[allowed: …]` 打了回来。一次端到端探针把这件事定了性。

**在**这里**选择"标注"有一个值得说清楚的理由，因为本仓库两种门禁都有，
而它们不可互换：缺失的翻译键不是看不见的债务。i18next 会把键名本身返回，
于是用户是在屏幕上读 `documents.loadError`。这里没有"交给评审判断"的空间——
文案要么在，要么不在。而两个 `*-allow:` 注释**确实**该放行的门禁
（`check-design-system` 与 `check-double-submit`）守的都是**用户看不见**的债务，
这就是两种立场的分界线。`check-query-errors` 与 `check-mutation-errors`
出于同样的理由站在这一侧，而且各自都在自己的输出里写明了。

键总数现在是 **695**，且 695 个全部可从源码到达。

## 8. 写按钮在请求进行中必须停止接受点击

`npm run check:double-submit` 串在 `npm run lint` 里，强制一条规则：

- `unguarded-write` —— 某个 mutation 在文件里被触发
  （`someM.mutate(...)` 或 `mutateAsync`），而**同一个文件里没有任何地方**
  读过 `someM.isPending`。

React Query 不会对 `mutate()` 调用去重。第二次点击会发出第二次请求，
后果并不均一：把任务取消两次只是浪费，而 `createM` 点两次会用同一个键
建出两套件，`startM` 点两次会启动两次评估运行、把预算烧两遍。

Batch 796 把每个 `onClick={() => someM.mutate(...)}` 过了一遍，
查出 **9 个**没有守卫的控件：`ABTest` 的 `startMut`×2、`pauseMut`、`stopMut`，
`Embeddings` 的 `cancelM`、`retryM`，`Evaluation` 的 `createM`、`versionM`、`startM`。
九个现在都会在请求进行中禁用自己并显示 loading 文案。

**这个检查刻意很粗，而且它有一个已知的漏检。** 它问的是"`isPending` 有没有在
这个文件里出现过"，而不是"这个按钮有没有读它"。两个理由：

1. 找出这些缺陷的勘察脚本解析的是 `<button>` 开标签，
   把 `ApiKeys.tsx:1042` 报成未守卫——而**下一行**就是
   `disabled={immediateMutation.isPending}`。带嵌套花括号的多行 JSX
   足以让这种解析失手，而会对正确代码误报的门禁，一周内就会被忽略。
2. 文件范围的失败方式是**漏报**，从来不是误报。把 mutation 传给子组件、
   或把 `isPending` 存进另一个变量名的形态不会被拦——方向是对的。

这个漏检是真实的，而且被演示过：把 `ABTest` 的四个 `disabled` 守卫删掉之后，
**这道门禁仍然是绿的**，因为同一个文件在按钮**文案**里还在读 `isPending`。
控件显示"加载中"，却依然完全可点。只有行为测试能抓住它——
`ABTest.mutations.test.tsx` 现在有四个参数化用例：用永不结束的请求点击，
断言控件已禁用、且 API 只被调用一次。这个盲区同时被写成一条自测用例钉住，
让它保持可见，而不是被悄悄忘掉。

豁免用行内 `/* double-submit-allow: <具体理由> */`；目前没有登记任何豁免。

## 9. 读操作必须报告失败

`npm run check:query-errors` 串在 `npm run lint` 里，对 `src/` 里每一个
`useQuery` 执行三条规则：

- `silent-query` —— 一次读的失败既没有 `onError` 选项处理，渲染里也无处可见
- `empty-panel-on-error` —— `{q.data && <section>}` 这种守卫没有错误分支，
  正是把"请求失败"变成"这里什么都没有"的那种形态
- `empty-state-on-error` —— 错误分支**确实渲染了东西**，但渲染的是
  `EmptyState`。前两条规则问的是"失败有没有被处理"，而两者都接受"有地方读了
  `isError`"作为答案；这一条问的是那个分支**到底往屏幕上放了什么**。
  `ApiKeys.tsx` 靠前两条规则活了多年，同时把"取不到凭据"和"你还没有密钥"
  用同一个原语、同一个盒子、同样的分量印在相隔三行的地方。

写操作失败静默，得到的是一个按了没反应的按钮。**读操作失败静默更糟，因为
它通常一点也不像坏了——它像数据。** Batch 797 勘察了全部 37 个查询，
找出 21 个失败时什么都不说，分两种形态。

命名形态（`const reportQ = useQuery(…)`）9 个。最糟的是 `Evaluation.tsx`：
失败时它渲染 `data ?? {}`，于是产出一份完整、正常、每个数字都是 `—` 的
评测报告。它看上去不像错误页，它看上去像**测过了**。

解构形态（`const { data, isPending } = useQuery(…)`）12 个，而**这个门禁的第一版
根本没检查这一种。** 它只匹配命名形态，于是判了 37 个里的 17 个，然后打印
"every read reports its failure"。这个盲区才是本批更大的发现，
而这 12 个里有**两个在报否定结论**：

- `Alerts.tsx` 在请求失败时渲染"暂无活跃告警"。在活跃告警页签上，
  这等于告警页在告诉运维没东西在烧——而它只是连不上服务器。
  它自己的 `AlertDetail`，在一百行之下，已经做对了。
- `ABTest.tsx` 对网络错误渲染"不存在"，因为
  `if (!exp) return <EmptyState>Not found</EmptyState>` 分不清
  "记录不存在"和"这次读失败了"。

`ReembedAllButton.tsx` 写的是 `if (isLoading || !status)`，这是**永久**的：
重试耗尽后 `isLoading` 变 false 而 `status` 仍然是 undefined，
于是这一块永远停在骨架屏上。`Search.tsx` 在表单下面什么都不渲染，
失败的检索和仍在进行的检索长得一模一样。`Chat.tsx` 把 `availableModels`
塌成 `[]`，静默地把模型下拉框禁用掉。

用 `QueryErrorBanner` 修：它接一句话、一个可选的 `onRetry`
（react-query 本来就把 `refetch` 递过来了）、一个可选的 `detail`。
它渲染 `role="alert"` 而不是 `role="status"`：它不由用户动作触发，
且报告的是功能的丧失。

**有两种形态是正当的，所以是被真正修好而不是被豁免。** 剩下的可以用行内
`/* query-error-allow: <reason> */`，但它只给失败输出加注，**不会**让门禁变绿——
一条能消音的注释就是谁都能写的注释。所以那 4 处 fail closed 的读被真的修了：

- `Collections.tsx` 读集成能力。失败时 purge 按钮正确地保持隐藏——那是安全的
  方向——但页面现在会说明原因，而不是让一个破坏性操作无缘无故地消失。
- `Dashboard.tsx` 过去给每个磁贴渲染 `?? '—'`，而代表"服务端什么都没说"的
  破折号，和代表"我们根本没问到"的破折号**无法区分**，而这两者要求的反应完全
  相反。磁贴现在带 `data-unavailable` 和一个重试，另外用独立的
  `systemUnreachable` 横幅把"连不上健康端点"和"系统不健康"分开——
  旧代码把两者都报成不健康，那个方向至少是安全的。

**命名形态的检查是文件级的，并且它有一个已登记的漏检。** 它无法分辨一个文件里
是哪个子组件渲染了横幅，所以一个真正静默的查询可以躲在兄弟查询的 `isError`
背后。它只会**漏报**，绝不会误报。自测把这个case钉住了，让缺口保持可见。

## 10. 不可逆操作必须确认

Batch 812 普查了 `src/` 里所有会毁掉东西的操作，找出**四个一点即发、没有任何确认**的：
删除 SLO 阈值、删除静默计划、删除集合、吊销 API Key。最后一个最尖锐——吊销凭证不可恢复，
而它就坐在同一行里、同一种颜色里，离"编辑策略"只有一个按钮的距离。

它能存活下来的原因值得记下来，因为那不是无知。这几个页面**已经**在正确的位置放了正确的组件：
`Documents` 在删文档和重新嵌入前会确认，`Collections` 在 purge 前要求手打集合名。门禁不缺失，
范式也不缺席——那四个操作只是四个正确操作的**邻居**，而 `npx tsc -b` 对"少了一个确认"
没有任何意见。

**变的不是组件，是测试。**这四处各自都有一条通过的测试：点一次，断言 API 已被调用。
那条测试把不安全的行为钉死了，所以修复必须从"让测试要求点两次"开始。现在 `src/` 里
每条破坏性路径都有同一对臂：**取消不调用**，以及**确认恰好调用一次**。第一条臂才是关键——
没有它，日后任何一次"把一键删除改回来"的重构都能通过现有全部断言。

目前还没有对应的机器门禁，诚实的理由是"破坏性"没有静态标记：
`onClick={() => setTarget(row)}` 和 `onClick={() => deleteMutation.mutate(row)}`
是同样的三个 token。靠标识符（`delete`/`revoke`/`purge`）计数的启发式，会连**打开**对话框的
那个 `onClick` 一起报出来——而那正是正确的代码。要分清两者需要数据流规则，而在安全属性上
只有 80% 正确的规则比一个诚实的缺口更糟。**把那两条臂当成规则读**：不可逆操作需要一个
`ConfirmDialog`，以及两条臂。

## 11. 对齐与布局

`npm run check:alignment` 串在 `npm run lint` 里。居中文本只有在写明理由时才被允许，
目前有 11 处这样的豁免——每一处都是有意的决定，并记录在检查器中。

之所以做成机器规则：正文字块居中是把布局从"可读"拖到"不可读"最常见的单一原因，
而在代码审查里它是隐形的，因为 CSS 只有一行。

## 12. 开始一次界面改动之前

1. 先跑门禁与测试，确认起点是绿的：
   ```bash
   npm run check:design-system
   npm run check:a11y-forms
   npm run check:mutation-errors
   npm run check:i18n-keys
   npm run check:double-submit
   npm run check:query-errors
   npm run test:run
   ```
2. 写新东西之前，先找有没有现成基元。
3. 需要新 token 时，加在 `design-tokens/tokens.json`，不要加在样式表里。
4. 需要新共享基元时，先找到两个真实调用方。
5. 操作会毁掉东西时，包一层 `ConfirmDialog`，并把两条臂都写出来——取消不调用、
   确认调用一次（见第 10 节）。
6. 测试在同一个 batch 里写。把门禁弄红的事情没有做完。

## 14. 只说自己名字的标题

`npm run check:page-shell` 还要求每个受保护页面给 `PageHeader` 传一个
`description`——这条要求之所以存在，是因为那个槽一直空着。

`PageHeader` 本来就是为两样东西建的：标题，以及一行说明这页是干什么的。
description 正是 `aria-describedby` 关联到 `h1` 的那个节点，于是读屏会把标题和
它的含义一起念出来，而不是只念一个"检索"。到 Batch 817 为止，每个受保护页面都把
标题走了组件——**十三个里只有一个**传了 description。十二个标题只写着
"检索""系统指标""告警中心"。

什么都没坏。页面正常渲染，测试全过，构建干净。正因如此才值得立条规则：
**只采用一半的约定，远看像是采用了**，而被丢掉的那一半恰好是告诉用户"你在哪"的那一半。
这和它之前的标题约定是同一种失败形态，所以两条现在住在同一个门禁里。

扫描是花括号感知的，因为"扫到第一个 `>`"会把 `leading` 里嵌套的
`<IconButton … />` 的 `>` 当成开标签的结束，然后去报那些其实有 description 的页面。
会误报的规则早晚会被关掉，所以新增的七条自测用例专门钉住那些别扭的形状：
多行标签、写在嵌套元素之后的 description、用 children 而非属性的 header，以及豁免页。

**页面具体说什么是作者的事，不是门禁的事。**现有的 `Embeddings` 导语是照抄的样板——
它既解释了这页做什么，又纠正了一个误解（"这些不是概率"）。
只把标题复述一遍的导语比没有更糟，因为它看起来像是在提供方向，实际不是。

**Batch 862 发现标题规则自己有同样的盲区。** 它是以被删掉的 `page-title` 类名
为锚的，于是只能抓到**恰好带着那个类名**的重复标题——正是 805 找到九个、
此后一个也没有的那种写法。同样的重复改用 CSS Module 类名、或者干脆不带类名，
就完全看不见了：`<PageHeader>` 在那儿供门禁发现，而游离的 `<h1>` 就躺在它
下面没人管。`PageHeader` 自己就渲染页面的 h1，于是那个页面有两个顶级标题、
把同一个标题念了两遍。规则现在是结构性的：**渲染了 `<PageHeader>` 的页面
不得再自己写 `<h1>`。** 页面标题下的 h2 是正常结构，放行；豁免名单仍然覆盖
Unlock 那个 h1 属于它自己的页面。

**Batch 877 发现描述规则有一面镜像的盲区：必填不等于有值。**
`description=""` 和 `description={undefined}` 都能满足"属性在不在"的检查，
而两者都是缺陷：`PageHeader` 的守卫是
`description !== undefined && description !== null`，所以空字符串走的是**渲染**分支
却没有任何内容可渲染——一个空的 `<p>`，而 `h1` 仍通过 `aria-describedby` 指着它；
`undefined` 则让整个槽位消失，正是 817 那条规则要防的"烂回未使用"。
规则现在把**缺失**（`missing-page-description`）与**可判定为空**
（`page-description-empty`）分开。表达式仍然不报：`{t('x')}` 静态不可判定，
而真实树十三个页面全传的是它。

同一批还关掉了这个门禁的另外两个洞。它是七个前端门禁里**最后一个**自带
`stripComments` 副本的，而那份副本是正则实现，会把字符串字面量里的 `//`
误当成注释并抹掉该行余下内容——于是页面只要把重复标题和一个文档链接放在同一行，
门禁就看不见。另外页面遍历只有一层深，`src/pages/admin/` 下新增的页面能进路由、
进测试，却进不了这里的任何一条规则。豁免名单也改成按路径索引：按文件名索引的话，
`admin/Unlock.tsx` 会继承解锁页的豁免，直接走出这道门禁。

## 15. 本文刻意不说的内容


- 不逐页罗列布局。那是代码，代码就是参考。
- 不复述 token 目录。读 `design-tokens/tokens.json`。
- 不叙述规则的历史。要看这个，请读
  `docs/drafts/HARDENING_LOOP_PLAN.md`，那里记录了每一批为什么存在。
