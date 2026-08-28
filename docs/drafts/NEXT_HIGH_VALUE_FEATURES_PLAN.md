# WebUI 全站工作上下文、文件可发现性与统一弹层实施规划

> 状态：修订后规划审查完成（3/3），待实施
> 日期：2026-08-28
> 当前基线：`main@284da44f`
> 配套进度：[NEXT_HIGH_VALUE_FEATURES_PROGRESS.md](NEXT_HIGH_VALUE_FEATURES_PROGRESS.md)
> 交付规则：[规划、实施与验收工作流](../delivery-workflow-zh-CN.md)

## 1. 问题与本轮范围

当前 WebUI 的服务端数据主要由 TanStack Query 管理，但路由页面会在导航时卸载，必要工作
上下文由 URL、浏览器存储和页面级 `useState` 混合管理。侧栏的 13 个入口却都固定跳转到功能
根路由，因此用户切到其他页面再返回时，即使原页面已经有合法深链，也会被带回默认视图；
Chat、Search 和 Files 的部分未提交工作草稿还会随组件卸载而丢失。

Files 页面还存在独立的可发现性问题：PDF 导入必须使用 UUID 目录作为稳定身份，现有
`fs_files` 平面模型又没有保存导入批次的原始文件名。根目录只能展示 UUID，用户无法快速判断
每个目录对应哪份 PDF，也不能按可读名称查找。

弹层则由多个页面各自实现。已确认 `ABTest.module.css` 使用未定义的
`--color-background`，浏览器会丢弃该背景声明，导致新建实验对话框和输入表面透明。其他弹层
还混用了不同背景令牌、数值 z-index、可访问属性、Escape、焦点和滚动策略；Embeddings 的修复
预览虽然声明 `role="dialog"`，却没有遮罩或真正的弹层布局。

本轮将三个问题作为同一批 WebUI 工作流改进交付：

1. V59 保存 PDF 导入批次的可读元数据，同时保留 UUID/path 的既有身份和深链；
2. Files 根目录以原始文件名/可读名称为主标签，UUID 为次要技术标识，并支持名称与 UUID
   搜索；
3. 建立通用 Dialog 原语、overlay/layer/design token 和 CSS 变量门禁，迁移现有模态弹层；
4. 建立版本化、有界的 WebUI session-state 层和全站主导航深链记忆，逐页证明必要状态跨路由
   返回，不以“只修三个示例页面”代替全站审计；
5. 用 DOM、ARIA、computed style、URL、网络和数据库断言验收，不使用截图。

## 2. 已核对的事实

### 2.1 路由与状态

- [App.tsx](../../spring-ai-rag-webui/src/App.tsx) 使用 React Router 的路由级 lazy page；
  页面切换会卸载前一页面。
- [Layout.tsx](../../spring-ai-rag-webui/src/components/Layout/Layout.tsx) 的侧栏链接固定指向
  `/chat`、`/search`、`/files` 等根路由，不记住上一次 session、query 或 file path。
- Chat session ID 已在 `/chat/:sessionId`；消息历史由后端重新读取，模型偏好在
  `localStorage`。但 mode、Collection scope 和未发送输入仍是局部状态。
- Search 的已提交 query/hybrid/scope 在 URL，Files 的 path/file/sort 在 URL，
  Documents 的 Collection/keyword/page 在 URL；从浏览器 Back 返回可恢复，但从固定侧栏
  根链接返回不会恢复最后深链。
- Settings、Evaluation、Alerts 的 tab 已进入 URL；Embeddings 的筛选和 job ID 也在 URL；
  ABTest 详情 ID 已进入 path。它们在浏览器 Back/Forward 下可恢复，但从固定侧栏入口返回时
  仍被重置。
- API Key 创建/轮换对话框包含只显示一次的 credential，任何新状态层都不得持久化这些值。
- 文档编辑正文、Evaluation judge context、modal visibility 和 in-flight request 也不得进入
  session storage。

### 2.2 Files 与 PDF 导入

- [V20](../../spring-ai-rag-core/src/main/resources/db/migration/V20__add_fs_files.sql) 的
  `fs_files` 以 `path TEXT PRIMARY KEY` 保存文件，没有目录行或导入批次元数据。
- [PdfImportService.java](../../spring-ai-rag-core/src/main/java/com/springairag/core/service/PdfImportService.java)
  每次生成 UUID，保存 `{uuid}/original.pdf`、`{uuid}/default.md` 和资源文件；返回值只包含
  UUID、entry Markdown 和数量。
- [PdfImportController.java](../../spring-ai-rag-core/src/main/java/com/springairag/core/controller/PdfImportController.java)
  从文件 path 合成目录；`FileTreeEntryResponse.name` 对根目录就是 UUID。
- `pdf-import:{uuid}/default.md`、现有 raw/preview URL 和 Search/Documents 的 provenance
  deep link 已是稳定合同，不能改名或用原始文件名替代 path。
- 历史 UUID 目录没有可信的原始文件名。`original.pdf` 是固定存储名，Markdown 标题也不能
  可靠反推出上传文件名，因此历史行必须明确回退到 UUID，不能猜测。
- 当前服务把 multipart 原始文件名直接用于临时 `Path.resolve`，而 Marker/PDFBox 又按临时
  basename 推导输出目录。原始文件名只能经过规范化后作为展示/RAG 元数据；转换输入固定写为
  工作目录内的 `source.pdf`，输出目录固定读取 `source/`，彻底切断客户端文件名与临时路径。

### 2.3 弹层与设计令牌

- [global.css](../../spring-ai-rag-webui/src/styles/global.css) 定义 `--color-bg`、
  `--color-surface`、`--color-text-muted` 等令牌，但没有 `--color-background`、
  `--color-text-secondary`、`--bg-primary`、`--border-color` 或 `--text-secondary`。
- ABTest 对话框的背景和输入依赖未定义的 `--color-background`，这是透明问题的直接原因。
- Documents、Collections、CreateCollectionModal、VersionHistoryModal、ApiKeys、ABTest 和
  Embeddings 都有各自的模态实现；只有部分具有 `role="dialog"`、`aria-modal` 或 Escape。
- Dialog、popover、mobile navigation、menu 和 toast 的 z-index 从 100 到 9999，缺少统一
  layer token。
- 现有测试已经使用 Playwright DOM/ARIA/network 断言，适合扩展 computed style、焦点、
  Escape、scroll lock 和跨路由状态恢复验收。

## 3. 冻结的状态保留策略

状态按语义分三类，不通过保持所有 React page mounted 来规避问题：

| 状态类型 | 权威位置 | 示例 |
|---|---|---|
| 可分享、刷新和 Back/Forward 可恢复 | URL path/query | Chat session/mode/scope、Search 已提交条件、Files path/file/sort/query、Documents filter/page、tab/job filter |
| 当前浏览器 tab 的必要工作草稿 | 版本化、有界 `sessionStorage` | Chat 未发送草稿、Search 未提交表单、Files Add-to-RAG Collection |
| 服务端数据 | TanStack Query + 后端 API | Chat 历史、文档/Collection/文件树、告警、Embedding job |

明确不保存：

- root/business API Key、shown-once credential、Authorization header 或任何 secret；
- Documents 编辑正文、Evaluation context/answer、上传文件内容和 provider payload；
- modal/popover/sidebar 的打开状态；
- loading、streaming、mutation、错误 toast 或 in-flight request；
- Chat 消息副本；已有 session 只从后端历史恢复。

### 3.1 全站主导航深链记忆

新增受白名单约束的 route-memory：

- 覆盖侧栏全部 13 个顶级功能：Dashboard、Documents、Collections、Chat、Search、Metrics、
  Evaluation、Embeddings、Alerts、ABTest、API Keys、Files 和 Settings；
- 每个顶级功能只保存最近一次合法的 `pathname + search`，不保存 hash；
- 侧栏点击优先进入该功能的最后深链，例如 `/chat/<session>?mode=AGENT...` 或
  `/files?path=<uuid>/&file=...`；
- 直接打开 URL、浏览器 Back/Forward 和显式“新建 Chat/返回列表”仍以当前 URL 为准；
- 显式 logout 或 401 导致 credential 自动失效时，都清理本项目前缀下的 session-state，
  避免后续重新解锁后复用上一身份/会话的工作上下文；
- 解析失败、版本不匹配、超过上限或非白名单路径时丢弃并回到默认根路由。

逐页状态合同：

| 页面 | 跨路由返回时恢复 | 明确不恢复 |
|---|---|---|
| Dashboard | 顶级路由；服务端卡片由 Query cache/API 恢复 | loading/error |
| Documents | `collectionKey`、`keyword`、`page` URL | preview/edit/relocate/version modal，编辑正文，上传与 mutation |
| Collections | 顶级路由；列表由 Query cache/API 恢复 | create/purge dialog、confirmation、mutation |
| Chat | session path、mode/scope/collections query、当前 session/new draft | 消息副本、streaming/tool activity、菜单/侧栏 |
| Search | 已提交 URL 条件、与 URL 指纹匹配的未提交草稿 | history popover、请求中状态 |
| Metrics | 顶级路由；指标由 Query cache/API 恢复 | polling/loading 和 raw `<details>` 展开状态 |
| Evaluation | `tab` URL | evaluate/judge/suite/run 表单正文与 mutation result |
| Embeddings | status/collection/batch/job URL | repair preview dialog 与 mutation |
| Alerts | tab/status/provider URL | SLO/Silence 内联创建表单及 mutation |
| ABTest | experiment ID path | create dialog 表单与 mutation |
| API Keys | 顶级路由；列表由 Query cache/API 恢复 | 所有 dialog、policy draft、shown-once credential |
| Files | path/file/sort/q URL、Add-to-RAG Collection session draft | upload对象/状态、embedding 状态、preview refresh counter |
| Settings | tab URL；已保存偏好继续由既有 localStorage 恢复 | 未保存修改、save toast |

“必要状态”指用户完成页面定位、筛选或继续主要输入所需的稳定上下文，不等同于保留所有组件
局部 state。涉及 secret、任意文档正文、危险确认、只显示一次的 credential、进行中的网络
操作或弹层可见性时，安全和数据新鲜度优先，离开页面即清理；本轮不通过 keep-alive 隐藏页面
来保留这些状态，避免后台轮询、流式连接、上传和危险 mutation 在不可见页面继续运行。

### 3.2 Chat

- `sessionId` 继续使用 path；
- `mode`、`scopeMode` 和重复 `collectionKey` 参数进入 URL；缺省值保持
  `KNOWLEDGE + CALLER_VISIBLE`，无效参数 fail closed 到缺省；
- mode/scope 控件更新 URL 时使用 `replace`，避免每次选择都污染 Back/Forward；新建 Chat、
  选择历史 session 和首轮 SSE `done` 跳转都保留当前合法 mode/scope query，只重置 session；
- mode 为 `PLAIN` 时从 URL 删除 Collection scope 参数，不把隐藏旧值发送到后端；
- 未发送 draft 使用 session storage，按 `new` 或具体 session ID 隔离，最大 8 KiB；
- draft 命中 credential/token/header 高风险模式时不写 storage，并删除旧值；
- Send 成功开始后清空当前 draft；409/Stop 按现有逻辑恢复输入并重新保存；
- streaming 状态不持久化；离开正在进行的流仍按现有取消/断连语义处理。

### 3.3 Search 与 Files

- Search 已提交条件继续由 URL 驱动；未提交 query/hybrid/scope/keys 保存为一个有界草稿，
  并记录草稿对应的 `baseSubmittedSearch`：
  - 当前 URL 与 `baseSubmittedSearch` 一致时，恢复未提交表单，但结果仍由 URL 中已提交条件
    驱动；
  - 显式打开不同 URL 时，新 URL 优先并重置不匹配草稿；
  - 提交成功后把新 URL 设为新的 base，并把表单标记为 clean。
- Files 增加 URL `q`；目录、文件、排序和查询共同构成可分享深链。
- `selectedCollectionKey` 保存为普通短字符串，不保存上传文件对象、上传状态或 embedding
  状态。
- 当前 `collectionPrefix` 控件对应的 multipart 参数已被服务端明确忽略；本轮从 WebUI
  移除该无效控件，后端参数仅为 API 兼容保留，避免用户误以为它会改变 UUID 目录位置。

## 4. V59 数据与 API 设计

新增 `V59__add_fs_import_batches.sql` 与 `fs_import_batches`：

| 字段 | 约束与用途 |
|---|---|
| `import_id UUID PRIMARY KEY` | 与 UUID 目录相同的稳定导入身份 |
| `source_type VARCHAR(32)` | 本轮固定 `PDF`，为未来通用导入保留清晰类型 |
| `original_filename VARCHAR(512)` | 经过 basename/控制字符/长度验证的上传文件名 |
| `display_name VARCHAR(512)` | 当前默认等于 original filename；未来允许独立重命名而不改 path |
| `entry_path TEXT UNIQUE` | `{uuid}/default.md` |
| `original_path TEXT UNIQUE` | `{uuid}/original.pdf` |
| `file_count INTEGER` | `>= 1` |
| `created_at/updated_at TIMESTAMPTZ` | 导入批次时间 |

`entry_path` 外键指向 `fs_files(path) ON DELETE CASCADE`，作为 batch 生命周期锚点；
`original_path` 是可定位原件的唯一逻辑路径，但不作为第二个级联锚点，避免只删除原件时意外
丢失仍可用于 Markdown 浏览与 RAG 的可读元数据。导入事务必须：

1. 使用固定临时输入 `source.pdf`，转换后只读取固定 `source/` 输出目录；
2. 验证输出中恰好形成一个可持久化的入口 Markdown，并映射为 `{uuid}/default.md`；
3. `saveAllAndFlush(fs_files)` 后保存 batch，使外键检查顺序明确；
4. 任一步失败都回滚 `fs_files` 和 batch，不能返回一个不存在的 `entryMarkdown`。

未来或测试通过 prefix 删除文件时，entry 删除会级联清理 batch，避免孤儿元数据。

兼容规则：

- 不回填历史行；没有 batch 的 UUID 目录返回 `displayName/originalFilename/importId = null`；
- `FileTreeEntryResponse.name/path/type/mimeType/size/createdAt` 保持原语义；
- `FileTreeEntryResponse` 新增可空字段 `displayName`、`originalFilename`、`importId`、
  `sourceType`；
- `FileTreeResponse` 新增可空 `importMetadata` envelope；直接请求
  `tree?path={uuid}/` 时也能获得当前目录的相同元数据，不能依赖用户先访问根目录或前端 query
  cache；
- 仅根目录的合成 UUID directory 使用 batch 元数据增强；普通目录和目录内文件不伪装为
  import batch；
- `PdfImportResponse` 追加 `originalFilename` 和 `displayName`；
- 上述 public record 必须保留现有 5/6 参数 `FileTreeEntryResponse`、3 参数
  `FileTreeResponse` 和 3 参数 `PdfImportResponse` 的委托构造器，新字段默认 `null`；
  `PdfImportResult` 同样保留现有 3 参数构造器。这样 HTTP JSON 仅追加可空属性，仓内及外部
  Java 调用方也不因 canonical constructor 扩展而被迫同步修改；
- 所有 `pdf-to-rag` JSON/SSE/ASYNC 路径使用 `PdfImportResult.originalFilename`，不再从
  `MultipartFile` 第二次读取未经规范化的名称；`RagDocument.originalFilename`、title/source、
  batch 元数据和响应必须保持同一名称；
- tree API 的 `createdAt` 对已知 batch 使用 batch `created_at`，历史目录继续使用后代文件的
  最新创建时间；
- 原始 UUID/path 仍作为链接、复制值和 API 技术身份。

导入文件名规范：

1. 把 `\` 归一为 `/` 并只取最后一个 segment；
2. trim 后要求非空、`.pdf` 后缀、最多 512 字符；
3. 拒绝 NUL、ASCII 控制字符和 DEL；
4. 规范化名称仅用于 batch 元数据、返回 DTO 和 RAG original filename；不得用于临时
   `Path.resolve` 或转换器输出定位；
5. 临时输入固定为 `source.pdf`，转换输出固定从 `source/` 读取，临时名称不暴露到 API 或
   持久元数据。

## 5. Files WebUI 设计

根目录 entry：

- 主标签：`displayName`，没有元数据时明确使用 UUID；
- 次标签：UUID/import ID，等宽、可选择；提供仅图标的复制按钮和 tooltip/ARIA label；
- 搜索输入按 `displayName`、`originalFilename`、`importId`、`name` 和 `path` 大小写不敏感
  过滤；
- 无匹配与真正空目录使用不同状态；
- 排序继续按导入时间，时间相同再按主标签和 UUID 稳定排序；
- 点击仍导航到 UUID path，现有 deep link 完全不变。

目录内部：

- 文件名仍为主标签；
- `q` 可过滤文件名/path；
- breadcrumb 的 UUID segment 使用 response envelope 中的 metadata 显示可读名称；技术 path
  不变，直接 deep link 与刷新同样有效；
- Add-to-RAG 仍从 UUID path 提取身份。

不在本轮实现服务端全文搜索、分页、批量改名或删除导入批次。

## 6. 统一 Dialog 与 layer 系统

新增 `components/Dialog` 与基于它的 `ConfirmDialog`，通过 React portal 渲染到
`document.body`，统一提供：

- `role="dialog"`、`aria-modal="true"`、labelledby/describedby 或显式 aria-label；
- 不透明 surface、border、shadow、统一 backdrop 和响应式 max-height/width；
- 打开时锁定 body scroll，关闭时恢复原值；
- 初始焦点进入显式 ref、首个可交互控件或 panel；
- Tab/Shift+Tab 焦点环；
- Escape 和 backdrop close，可由危险 mutation 临时禁用；
- 关闭后把焦点返回触发元素；
- reduced-motion 兼容。

迁移范围固定为：

1. ABTest CreateExperiment；
2. CreateCollectionModal 与 Collection purge；
3. Documents preview/edit/relocate；
4. VersionHistoryModal；
5. ApiKeys create/edit/rotate；
6. Embeddings repair preview。
7. Documents disable/permanent-delete/version-restore 原生 confirm；
8. ReembedAll force-reembed 原生 confirm。

SLO/Silence 表单当前是页面内展开表单，不改造成 modal。DocumentActionsMenu、Search history、
Chat export menu 和 Toast 保持各自交互语义，但改用统一 layer/design token。

全局令牌：

- canonical color：`--color-bg`、`--color-surface`、`--color-surface-hover`、
  `--color-border`、`--color-text`、`--color-text-muted`；
- overlay：`--color-backdrop`、`--shadow-dialog`；
- layer：`--z-raised`、`--z-navigation`、`--z-overlay`、`--z-dialog`、`--z-popover`、
  `--z-toast`，值严格递增并明确 toast 最高；组件内局部堆叠也通过 token 表达；
- 对历史别名先定义兼容映射，再把本轮触达的弹层改为 canonical token。

新增 CSS 静态门禁：

- 无 fallback 且全库未定义的 `var(--token)` 失败；
- CSS module 内出现裸数值 `z-index` 失败，必须使用 layer token；
- 该门禁进入 npm script、测试指南和项目文档验证。

## 7. 实施切片

### 切片 A：V59 文件元数据

1. migration、entity、repository；
2. 文件名规范化、事务内批次保存与扩展 result；
3. tree enrichment 与 DTO/OpenAPI；
4. service/controller/DTO/PostgreSQL 集成测试；
5. Files 类型、搜索、主次标签、复制与 fallback。

### 切片 B：WebUI 全站状态连续性

1. 版本化 storage codec、容量/敏感模式/白名单校验与 logout/401 credential-loss 清理；
2. Layout 对全部 13 个顶级功能的 route-memory；
3. Chat URL state 与 draft；
4. Search draft、Files Add-to-RAG Collection 选择，并移除无效 collection prefix；
5. 对其余页面验证现有 URL/服务端状态合同，无新增存储的页面也必须有明确断言；
6. Vitest 和 Playwright 的
   `设置状态 -> 导航离开 -> 侧栏返回 -> 状态恢复` 全站矩阵。

### 切片 C：弹层与设计语言

1. global tokens 和 CSS 静态门禁；
2. Dialog/ConfirmDialog portal、focus、Escape、scroll lock；
3. 按第 6 节固定清单迁移；
4. 清理未定义 token 和数值 z-index；
5. Dialog unit test 与跨页面 Mock Playwright。

### 切片 D：长青文档与交付

1. 双语 file-management/API/architecture/project-context；
2. 新增双语 WebUI interaction guideline，并加入索引；
3. developer/testing/release checklist 和 V59 inventory；
4. 归档本 plan/progress，提交、同步上游、复验、合并并推送 main。

## 8. 一次性验收矩阵

### 8.1 后端

- 空 PostgreSQL 执行 Flyway V1-V59；
- 使用测试内生成的最小合法 PDF、真实 PDFBox、真实 Spring HTTP controller 和
  Testcontainers PostgreSQL 完成一次 `/files/pdf` 导入，不能只 mock converter 或直接
  insert batch；
- 真实 repository/service/controller 路径验证：
  - 导入批次与 `fs_files` 同事务提交；
  - 转换输入固定为 `source.pdf`，路径型上传名不会逃逸工作目录，Marker/PDFBox 输出都从
    `source/` 正确导入；
  - 转换未产生入口 Markdown 时事务失败且不留下原始 PDF/batch；
  - root tree 返回 display name + UUID；
  - 历史目录明确 null/fallback；
  - prefix 删除 entry 后 metadata cascade；
  - 非 root 目录不错误增强；
  - 文件名 basename、控制字符、过长和非 PDF，且 PDF-to-RAG 各传输路径复用同一规范化名称；
- DTO/OpenAPI contract 测试；
- 既有 DTO/PdfImportResult 构造签名继续编译并产生 `null` 新字段；
- 相关测试一次性完成后运行 `mvn clean compile test-compile`、完整 Maven；
- `postgresql` profile 服务启动并检查 health/tree API。

### 8.2 前端

- Vitest：
  - session codec 版本、容量、损坏数据、敏感字符串和 clear；
  - route whitelist 和 remembered deep link；
  - Dialog/ConfirmDialog ARIA、focus trap、Escape、backdrop、scroll restore、focus return；
  - Files known/legacy metadata、filter、copy、stable sorting；
  - Chat URL/draft 和 Search/Files session draft；
  - Search 在旧结果 URL 上编辑未提交条件、切页返回后恢复表单但不提前发起新检索；显式不同
    URL 不被旧草稿覆盖；
  - 13 个顶级 route key 的白名单、非法/损坏/过期 route 清理和默认回退；
- TypeScript、production build、alignment、CSS custom-property/layer 门禁；
- 核心 Mock Playwright：
  - Chat session/mode/scope/draft 切页返回；
  - Search 未提交表单与已提交 URL 切页返回；
  - Files 可读名称/UUID search、路径和 Collection 选择返回；
  - Documents filter/page、Evaluation/Alerts/Settings tab、Embeddings filter/job 和 ABTest
    detail 从侧栏离开后再点击对应入口仍返回最后合法深链；
  - Dashboard、Collections、Metrics、API Keys 回访不产生伪造 query，不恢复 dialog 或
    mutation；全部 13 个入口都参与 browser Back/Forward；
  - ABTest、Collection、Documents、ApiKeys、Embeddings 弹层共享 ARIA/computed-style
    合同；
  - Documents 与 ReembedAll 的确认流程不再触发原生 browser dialog，危险操作在共享
    ConfirmDialog 中完成；
  - Escape、focus、scroll lock、toast/popover layer；
  - 不使用截图。

### 8.3 联合运行时与真实模型

- 后端 PostgreSQL 集成和前端 Mock 分别通过后，使用隔离端口启动真实后端与 WebUI；
- 复用真实 PDFBox HTTP 导入产生的 known metadata import，并只为历史兼容场景直接 seed 一个
  无 batch 的 UUID 目录；用真实 tree JSON + WebUI DOM 验证可读名称/fallback/deep link；
- 真实 Chat Playwright 至少完成一次 provider 调用，证明新增 URL mode/scope 与
  remembered session 不改变 SSE/历史合同；
- 不调用外部 Marker，也不为本轮 UI 合同重复真实 Embedding；真实 PDFBox 已在 PostgreSQL
  HTTP 集成验收覆盖，真实 Chat 只覆盖受影响的 SSE/历史合同。

### 8.4 通用门禁

- `./scripts/verify-no-pessimistic-locks.sh`
- `./scripts/verify-project-docs.sh`
- `git diff --check`
- Shell/Node syntax 与 added-line secret scan
- push 前 `git fetch origin --prune`；若 `origin/main` 前进则 merge 后重跑完整矩阵。

## 9. 风险、回滚与非目标

风险与控制：

- V59 为加表和 DTO 可空字段，旧 WebUI 忽略新字段；新 WebUI 对旧/历史 null 回退 UUID。
- route-memory 只保存白名单路径和短 query，不让任意 URL 或 hash 进入导航。
- session-state 版本/上限/敏感模式失败时 fail closed 删除，不阻止页面使用。
- Dialog portal 可能改变 CSS 继承和测试 selector；global theme token 在 `documentElement`
  上，module panel class 继续可用，迁移时逐个用 DOM 测试覆盖。
- batch 外键使删除 entry Markdown 同时清理元数据，符合文件目录生命周期；删除 original
  PDF 但保留 entry 时 batch 仍存在。

回滚：

- 应用可回滚到 V58 binary；V59 表为附加 schema，不影响旧 binary；
- 新 WebUI 可回滚，后端可空字段和表继续存在；
- 不做破坏性 down migration，不改写 V20 或既有 path。

非目标：

- 不让 UUID 消失或改写 storage path；
- 不回填或猜测历史原始文件名；
- 不持久化全部页面内部状态；
- 不保持所有 route component 永久在线；
- 不实现通用 desktop window manager、全站表单自动保存或跨浏览器同步；
- 不引入 Redux/Zustand、消息代理或新的外部基础设施。

## 10. 完成定义

只有以下全部满足才完成：

1. 规划连续 `3/3` 无修改；
2. V59、API、Files UI、状态层和全部固定范围 Dialog 已实现；
3. 后端 PostgreSQL 集成、Maven、服务启动通过；
4. 前端 Vitest、typecheck、build、alignment、CSS gate 和核心 Mock Playwright 通过；
5. 真实 tree/full-stack DOM 与至少一次真实 Chat SSE 通过；
6. 双语长青文档和门禁同步；
7. 实现阶段按当前任务要求以自动化证据为主完成收敛；
8. 特性分支同步最新 `origin/main` 后完整复验，合并推送 main；
9. `HEAD == origin/main` 且主工作区干净。
