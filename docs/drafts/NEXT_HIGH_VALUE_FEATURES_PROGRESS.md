# WebUI 工作上下文、文件可发现性与统一弹层实施进度

> 对应规划：[NEXT_HIGH_VALUE_FEATURES_PLAN.md](NEXT_HIGH_VALUE_FEATURES_PLAN.md)
> 工作区：`/Users/yangjiefeng/Documents/wubuku/spring-ai-rag`
> 当前分支：`feature/webui-workspace-continuity`
> 规划基线：`main@34979bc3`

## 当前状态

- 阶段：Files 文件管理器式 UX 收敛完成，进入完整前后端验收与长青文档收敛
- 规划审查计数：`3/3`
- 实施：已开始
- worktree：只使用主工作区；未创建额外 worktree，未使用 stash

## Git checkpoint

- `34979bc3 docs: plan WebUI workspace continuity`
- 已推送到 `origin/main`，本地 `main == origin/main == 34979bc3`。
- 已在同一工作区创建 `feature/webui-workspace-continuity`，未创建额外 worktree。

## 已完成实施

- 新增 V59 `fs_import_batches`、实体与 repository。
- PDF 导入固定使用 `source.pdf` / `source/`，原始文件名只作规范化元数据。
- 转换结果必须恰好包含一个入口 Markdown；文件和 batch 在同一事务内持久化。
- tree/PDF import DTO 已追加可空元数据并保留旧构造签名。
- root tree 与 UUID 目录 envelope 已支持已知批次增强和历史目录回退。
- PDF-to-RAG 各路径改用 `PdfImportResult.originalFilename`。
- Files 已以可读 PDF 名称为主标签、UUID 为次要技术标识，支持名称/UUID/path 的 URL
  `q` 搜索；UUID/path 仍是唯一权威身份。
- 2026-08-28 用户反馈后，Files 又完成一次限定范围 UX 收敛：全局栏仅保留面包屑和刷新；
  RAG 集合操作移入右侧当前目录上下文；列表明确使用文件/文件夹语义；空目录和无匹配状态
  保留返回上一级；目录切换清除当前文件夹筛选；导入控件补齐键盘按钮语义。
- 全部 13 个侧栏入口已使用合法深链记忆；Chat、Search、Files 的必要草稿/选择按版本化、
  有界 session state 恢复，敏感状态、请求中状态和弹层开关不持久化。
- ABTest、Collections、Documents、Version History、API Keys、Embeddings 与 re-embed
  confirmation 已迁移到共享 portal Dialog/ConfirmDialog；原生 `confirm()` 已清除。
- 新增 design token/layer 门禁；共享 Dialog 已覆盖不透明表面、ARIA、focus trap/return、
  Escape、scroll lock 与 mutation-time close blocking。
- 新增 `PdfImportPostgresIntegrationTest`：测试内生成真实 PDF，经版本化 MockMvc HTTP、
  PDFBox、JPA、Flyway V1-V59 与 Testcontainers PostgreSQL 完成导入和事务验收。

## 已执行验证

- `mvn -pl spring-ai-rag-core -am -DskipTests test-compile`：通过。
- `PdfImportServiceTest,PdfImportControllerTest`：48 项通过。
- `spring-ai-rag-webui: npm run typecheck`：通过；新增 workspace state、共享 Dialog、
  Chat/Search 状态改造已在类型层闭合。
- 前端 workspace/Dialog/Files/Chat/Search 聚焦 Vitest：38 项通过。
- 前端 Collections/API Keys/Documents/Version 聚焦 Vitest：27 项通过。
- Mock Playwright `workspace-continuity.spec.ts` 6/6 通过：全部 13 个 remembered sidebar
  destination；Chat/Search/Files 页面卸载后的状态恢复；ABTest、Collection 创建/销毁、
  Document 确认/版本历史、强制重嵌入、API Key 创建和 Embedding repair 的共享 Dialog
  DOM/ARIA/不透明 computed background/focus/Escape；并断言无原生 browser confirm。
- `PdfImportPostgresIntegrationTest`：测试逻辑 `2/2` 通过，证明：
  - 空库迁移到 V59；
  - 路径型上传名只保留安全 basename，真实 PDFBox 固定读取 `source.pdf`；
  - root/direct-directory tree 返回可读 metadata，历史 UUID 返回显式 null；
  - entry 删除由数据库级联删除 batch；
  - batch 保存强制失败时，已经 `saveAllAndFlush` 的 `fs_files` 仍整体回滚。
- PostgreSQL 测试首次运行后曾遇到旧损坏 `target/jacoco.exec`；从 `clean` 基线重跑后
  `PdfImportPostgresIntegrationTest` 2/2 和 reactor 均为 `BUILD SUCCESS`。
- `scripts/test-dev-launcher.sh` 通过；真实 `./scripts/dev.sh --force-kill` 清理 15173 上
  非托管 Vite PID 47962 后，后端 18082、Vite 15173、HMR、root identity 与管理写探针
  均达到 ready。
- Files 文件管理工作区聚焦验收通过：
  - Vitest `11/11`，覆盖命令栏稳定挂载、中文 IME、键盘调宽和 session 宽度记忆；
  - Mock Playwright `6/6`，以 URL、DOM、disabled 状态和 bounding box 证明中文组合输入结束前
    不导航、pointer 调宽有效、根目录/子目录切换不再插入 RAG 区块或改变工作区/预览几何；
  - typecheck、lint、alignment 与 design-token 门禁同步通过。
- 2026-08-28 UX 收敛后的增量验证：
  - Files Vitest `12/12`；
  - Files 核心 Mock Playwright `7/7`；
  - typecheck、lint、alignment、design-token 和 `git diff --check` 通过；
  - 断言根目录没有 `files-rag-actions`，导入目录的 RAG 操作位于预览面板，且空筛选目录
    仍可返回上一级。
- 2026-08-28 UX 二次收敛：
  - 将导入目录的 RAG 操作从预览面板顶部独立横条移入当前文件夹摘要，避免进入子目录时
    预览内容被一整条工具栏向下推移；根目录仍不渲染 RAG 控件。
  - 文件夹行补齐“文件夹”详情列，保留可读名称为主标签、UUID 为次级技术身份。
  - 导入成功后的文件树查询失效使用 `refetchType=all`，避免根目录缓存继续显示导入前列表。
  - 该收敛后的 Files typecheck、lint、design-token 和 `Files.test.tsx` `12/12` 已通过；
    核心 Playwright 在下一项全量前端门槛中复跑。
- 2026-08-28 UX 三次收敛（文件管理器交互）：
  - 用户反馈 Files 仍不像正常文件管理器；根因不是单个区块，而是目录浏览、搜索、详情和
    RAG 业务动作的交互层级不符合文件管理器习惯。
  - Files 改为单击选中、双击或 Enter 打开目录；单击目录不再立即切换当前位置，右侧先展示
    当前选中目录的名称、路径、类型和打开动作，RAG 操作只作为该目录详情中的上下文动作。
  - 搜索提升到稳定的当前位置工具栏，保持当前目录筛选语义；列表明确分为名称、类型、详情
    三列，根目录的可读文件名和 UUID 继续分别作为主标签与次级身份。
  - 该收敛后的 Files Vitest `13/13`、typecheck、lint、alignment、design-token、生产构建
    和 Files Mock Playwright `7/7` 已通过。
- 前端完整硬门槛通过：
  - typecheck、lint、alignment、design-token 和 production build 全部成功；
  - 完整 Vitest `248/248`；
  - 明确排除 `*-real.spec.ts` 后的全部 Mock Playwright `86/86`，覆盖 Chat scope/URL、
    Documents Dialog lifecycle、API Key shown-once/rotation、Files IME/几何与全站工作上下文；
  - Toast 已改为 4 秒自动清理，提示正文不再拦截底层交互，仅关闭按钮接受 pointer。

## Checkpoint 与真实运行时证据

- 2026-08-28 已建立并推送 checkpoint：
  - 分支：`feature/webui-workspace-continuity`
  - 提交：`b2fed296 feat: make files webui a stable file manager`
  - 推送目标：`origin/feature/webui-workspace-continuity`
- 真实 Files 浏览器生命周期：通过 `spring-ai-rag-webui/e2e/files-real.spec.ts`，`1/1`。
  - 使用隔离 PostgreSQL 容器、真实 Vite 代理和真实后端；
  - 通过真实 multipart PDF 导入、可读文件名与 UUID 次级身份、目录树刷新、面板拖宽记忆、
    目录深链、Markdown 预览、RAG embedding、中文 IME 查询、向量/全文搜索和文档回答。
- 真实 Chat 上游预检：未通过，原因已确认在外部服务而非本地代码。
  - 使用 `.env` 中的 OpenAI-compatible 配置向 `api.openai-next.com` 连续发起 3 次独立
    最小请求；
  - 三次均返回 HTTP `503`，错误码为 `no_available_account`；
  - 因上游没有可用账号，未将真实 Chat SSE 结果宣称为通过；此前 Mock Chat、SSE 和
    workspace 状态测试仍已通过。
- 真实服务启动：后端在 `18083` 启动成功，Flyway 从空库完成 V1-V59，Embedding profile
  使用 `BAAI/bge-m3`/1024 维，OpenAI ChatModel 成功创建；服务健康检查为 `UP`。
- 前端全量硬门槛：Vitest `250/250`、Mock Playwright `87/87`、typecheck、lint、alignment、
  design-token 和 production build 全部通过。
- 文档与安全门禁：project-docs `11/11`、悲观锁检查、Shell syntax、`git diff --check`、
  新增行密钥扫描全部通过。

## 合并后基线复验

- 2026-08-28 执行 `git fetch origin --prune`：`origin/main` 仍为 `34979bc3`，特性分支
  已包含该提交，无需额外合并冲突处理。
- 后端门槛：`mvn clean compile test-compile` 成功；启用
  `TESTCONTAINERS_RYUK_DISABLED=true TESTCONTAINERS_CHECKS_DISABLE=true` 与
  `-Dpdf-import.it.enabled=true` 后，`PdfImportPostgresIntegrationTest` `2/2` 通过，
  从空 PostgreSQL/pgvector 库执行 V1-V59 迁移并完成导入元数据、历史回退、级联删除和
  事务回滚断言。
- 前端门槛：在合并后重新执行 typecheck、Vitest、lint、alignment、design-token、生产构建
  和排除真实外部服务用例的 Mock Playwright，结果分别为通过、`250/250`、通过、通过、通过、
  通过、`87/87`。
- 合并后没有源代码变更；真实 Files 浏览器验收和真实服务启动证据对应的提交内容与该基线
  完全一致。真实 Chat 仍只受上游服务返回的 `503 no_available_account` 阻断，不能将其
  归因于本地实现。

## 当前验收切片

- 代码 checkpoint 已建立；前端和真实 Files 生命周期已完成验收。
- 真实 Chat SSE 仍等待上游恢复可用账号后重试；当前证据明确记录为外部阻断。
- 待同步最新 `origin/main` 并按合并后基线复验，再完成特性分支与 `main` 的 Git 交付。

## 用户提醒与硬边界

- UUID 是必要的稳定内部身份，但 Files WebUI 不能只显示 UUID。
- 不做 ABTest 单点透明背景修补；全面统一 modal/dialog/overlay/popover/menu/toast 的设计令牌
  与层级，其中 modal 必须统一可访问行为。
- 路由切换后，侧栏全部 13 个顶级入口都应恢复各自最后合法工作上下文；不能只修
  Chat/Search/Files 三个示例。必要状态不等于所有局部 state，仍不得保存 API Key、
  shown-once credential、文档正文、modal 开关或 in-flight 状态。
- 前端验收只使用 DOM、ARIA、computed style、URL、network 和 JSON，不使用截图。
- 用户在验收期间补充：`scripts/dev.sh --force-kill` 必须能显式清理目标前后端端口上的
  非托管旧监听，再继续启动；默认无参数仍必须保守失败，且不能扫描或误杀其他端口。
- 真实验收使用的 `scripts/start-real-e2e-server.sh` 必须尊重调用方显式传入的安全开关、
  root key 和 PostgreSQL 连接覆盖，不能在重新加载 `.env` 时把隔离验收参数静默覆盖。
- Files 必须呈现为稳定、可预期的文件管理工作区，不能因为从根目录进入子目录时才插入
  “RAG 集合”区块而使页面纵向跳变。位置/命令栏应始终占位，只切换上下文操作的可用状态；
  目录列表与预览区几何在目录导航期间保持稳定。
- 用户进一步反馈：当前 Files 仍不像正常文件管理器。全局位置/通用操作与当前导入目录的
  RAG 上下文操作必须分开；根目录不应出现看起来可操作但实际禁用的 RAG 区块；目录列表应
  明确呈现为文件/文件夹列表，目录切换不能改变整页布局。
- Files 的目录列表遵循可预期的文件管理器交互：普通点击只选择条目并更新右侧详情，
  双击或 Enter 才打开目录；路径栏、搜索和列表列头在根目录与子目录中保持同一位置。
- 文件管理器式工作区还必须覆盖空目录和空筛选结果下的“返回上一级”入口，搜索文案必须准确
  说明当前只筛选当前目录，不能暗示已经实现跨目录全局搜索。
- Files 桌面目录列表必须支持 pointer 拖动和键盘调整宽度，并在当前 tab 内记忆；移动端使用
  明确的单列布局。根目录双行身份与目录内单行文件必须使用稳定行高，不能推动面板尺寸。
- Files “查找文件”必须正确支持中文等 IME：组合输入期间只更新本地 draft，不改 URL 或触发
  路由重渲染；组合结束后再提交查询。验收使用 DOM、URL 和 bounding box 断言，不用截图。
- 真实浏览器导入发现导入控件的“空闲双行提示 -> 完成单行文本”会让工作区纵向移动约
  `23px`；Files 顶部导入控件必须使用固定工具栏尺寸，异步状态只替换内容、不改变页面几何。
- 真实浏览器导入后返回根目录时，`files-tree` 的 30 秒缓存会继续展示导入前旧列表；导入成功
  必须失效全部文件树查询，使根目录和直接深链立即看到同一批新文件。
- 隔离端口真实 WebUI 验收必须向后端注入该 Vite 实例的精确 CORS origin；浏览器 multipart
  PDF 导入必须穿过真实代理与 CORS，不能用无 `Origin` 的 APIRequestContext 结果替代。
- 当前批次直接在现有工作区推进；不创建隔离 worktree。
- 关键提醒必须进入本进度或长青文档，不能只留在会话。

## 已完成探索

- 已核对 App/Layout 路由卸载和固定根链接。
- 已逐页盘点 `useState`、URL、local/session storage 与敏感状态。
- 已再次盘点全部 13 个侧栏入口：Documents/Alerts/Evaluation/Embeddings/Settings/ABTest
  已有可恢复 URL 状态，核心缺口是侧栏固定根链接；Dashboard/Collections/Metrics/API Keys
  没有需要伪造持久化的页面定位状态。
- 已核对 V20 `fs_files`、PdfImportService、tree API、DTO 和 provenance deep link。
- 已确认历史 UUID 目录不能可靠推断原始文件名。
- 已定位 ABTest 透明背景的未定义 CSS variable，并盘点现有 modal 和 z-index。
- 已核对现有 Vitest、Mock Playwright、真实 Chat 和 PostgreSQL 测试入口。

## 已冻结决策

- V59 新增 `fs_import_batches`，不改 UUID/path。
- root tree 用可空增强字段，历史数据回退 UUID。
- 状态分 URL、session draft、TanStack Query 三层。
- Layout 记住每个顶级功能最后合法深链。
- 全站逐页状态合同明确区分 URL、低风险 session draft、Query/backend 和离页即清理状态；
  禁止用 keep-alive 让隐藏页面中的轮询、SSE、上传或 mutation 继续运行。
- session state 版本化、有界、敏感模式 fail closed，logout 清理。
- 通用 Dialog 使用 portal、ARIA、focus trap、Escape、scroll lock 与 focus return。
- 所有固定范围 modal 迁移到同一原语；popover/menu/toast 使用统一 layer token。
- 真实 LLM 只验证受影响的 Chat URL/SSE/历史合同，不把模型调用当文件/弹层正确性证据。

## 规划审查日志

### 初始第 1 轮：用户工作流闭环

- 时间：2026-08-28
- 范围：Files 可读身份、直接深链、状态分类和现有控件的真实后端语义。
- 发现：
  - Files 的 `collectionPrefix` 会提交 multipart `collection`，但 `PdfImportService` 明确忽略
    该参数；把它纳入状态恢复会继续强化一个无效控件；
  - 只在根目录 entry 返回 import metadata 时，用户直接打开
    `/files?path={uuid}/...` 无法获得可读 breadcrumb，方案隐含依赖先访问根目录。
- 处理：
  - 冻结从 WebUI 移除无效 collection prefix，后端参数只保留兼容；
  - 为 `FileTreeResponse` 增加可空 current-directory `importMetadata` envelope，使直接深链、
    刷新和根目录点击具有一致可读上下文。
- 结果：规划已修改，连续无修改计数重置为 `0/3`。

### 初始第 2 轮：数据、API、安全与兼容

- 时间：2026-08-28
- 范围：PDF 临时路径、转换器输出约定、V59 外键/事务顺序、PDF-to-RAG 名称传播和失败原子性。
- 发现：
  - 规划原先让规范化上传名继续作为临时文件名，但 Marker/PDFBox 都按临时 basename 推导输出
    目录；客户端名称仍会参与文件系统路径，且输出定位与元数据语义耦合；
  - 现有导入即使没有形成 `default.md` 也可能保存原始 PDF 并返回入口路径；V59 的
    `entry_path` 外键会把这个隐含失败变成写 batch 时的异常，需要在持久化前显式验证；
  - `pdf-to-rag` 的多个 JSON/SSE/ASYNC 路径会再次从 `MultipartFile` 读取原始名称，可能与
    V59 保存的规范化名称分叉。
- 处理：
  - 冻结临时输入为 `source.pdf`、输出目录为 `source/`，规范化名称只作元数据；
  - 要求入口 Markdown 验证、`saveAllAndFlush` 后保存 batch，并以事务保证失败无残留；
  - 要求所有 PDF-to-RAG 路径使用 `PdfImportResult.originalFilename`。
- 结果：规划已修改，连续无修改计数重置为 `0/3`。

### 初始第 3 轮：弹层全量盘点与身份边界

- 时间：2026-08-28
- 范围：所有 modal/overlay/popover/menu/toast、原生浏览器确认框、z-index 和认证失效路径。
- 发现：
  - Documents disable/permanent-delete/version-restore 与 ReembedAll 仍调用原生
    `confirm()`；仅迁移 CSS modal 不能满足“全面统一对话框”的目标；
  - 现有数值 z-index 包含局部 `1`、导航、overlay、menu 和 toast 多层，规划中的 token
    不足以覆盖局部 raised 层；
  - API 401 会通过 axios interceptor 自动清除 credential，若状态层只在显式 logout 清理，
    重新解锁后仍可能恢复失效身份之前的 tab 工作上下文。
- 处理：
  - 增加共享 `ConfirmDialog`，固定迁移全部四处原生确认流程；
  - 增加 `--z-raised` 并冻结完整递增层级；
  - session-state 清理同时挂接显式 logout 与 credential-loss/401。
- 结果：规划已修改，连续无修改计数重置为 `0/3`。

### 初始第 4 轮：跨路由状态闭环

- 时间：2026-08-28
- 范围：Layout route-memory 与 Chat/Search/Files URL/session state 的交互、Back/Forward 语义。
- 发现：
  - Search 可能停留在一个已提交结果 URL，但表单已被用户改成未提交的新条件；若返回时无条件
    让 URL 覆盖 session draft，侧栏深链记忆仍会丢失用户刚才的草稿；
  - Chat mode/scope 如果每次变化都 push history 会污染 Back/Forward，且新建/选择
    session/SSE 首轮跳转若不保留 query 会再次重置工作上下文。
- 处理：
  - Search draft 增加 `baseSubmittedSearch`，仅在 URL 指纹匹配时叠加恢复表单，结果始终由
    URL 驱动；不同显式 URL 清除不匹配草稿；
  - Chat mode/scope 使用 replace，所有 session 跳转保留合法 query，新建 Chat 只重置
    session。
- 结果：规划已修改，连续无修改计数重置为 `0/3`。

### 初始第 5 轮：需求闭环复查

- 时间：2026-08-28
- 范围：用户三个问题、状态分类表、非目标与完成定义。
- 发现：
  - 状态分类表仍把已决定删除的 Files `collectionPrefix` 列为 session draft，和后文“移除
    无效控件”的冻结决策矛盾。
- 处理：
  - 从 session-state 示例删除 collection prefix，仅保留有效的 Add-to-RAG Collection 选择。
- 结果：规划已修改，连续无修改计数重置为 `0/3`。

### 初始第 6 轮：Java/API 源码兼容

- 时间：2026-08-28
- 范围：V59 DTO 扩展、record canonical constructor、仓内及潜在外部 Java 调用方。
- 发现：
  - `FileTreeEntryResponse`、`FileTreeResponse`、`PdfImportResponse` 与内部
    `PdfImportResult` 均有现有构造调用；只描述“追加字段”会改变 canonical constructor，
    造成不必要的源码兼容破坏。
- 处理：
  - 冻结保留全部既有参数签名的委托构造器，新字段默认 `null`；HTTP JSON 保持追加可空字段
    的兼容演进。
- 结果：规划已修改，连续无修改计数重置为 `0/3`。

### 初始第 7 轮：真实导入验收路径

- 时间：2026-08-28
- 范围：实施顺序、后端集成层次、真实全栈 Files 证据和外部依赖边界。
- 发现：
  - 规划虽然要求验证固定 `source.pdf` 与 V59 事务，却允许联合运行时只直接 seed metadata，
    并明确不触发真实 converter；这样最关键的新导入路径仍可能只由 mock 覆盖。
- 处理：
  - 增加测试内生成最小合法 PDF，穿过真实 PDFBox、Spring HTTP controller 和 Testcontainers
    PostgreSQL 的集成验收；
  - 全栈 Files 验收复用真实导入结果，只对历史无 metadata fallback 直接 seed；
  - 仍不引入外部 Marker 或与本轮无关的重复真实 Embedding 调用。
- 结果：规划已修改，连续无修改计数重置为 `0/3`。

## 已失效的上一版最终规划审查

- 第 1 轮：2026-08-28 10:23 CST；需求闭环、自包含性、默认决策和非目标；无问题、无修改。
- 第 2 轮：2026-08-28 10:24 CST；数据、API、安全、事务与兼容；无问题、无修改。
- 第 3 轮：2026-08-28 10:25 CST；实施顺序、验收矩阵、运行时、回滚和 Git 交付；无问题、
  无修改。
- 结果：该结论在 2026-08-28 用户把状态连续性明确扩大到“所有页面”后失效。规划已实质修改，
  计数重置为 `0/3`，必须按新范围重新完成连续三轮无修改审查。

## 修订后最终规划审查

- 第 1 轮：2026-08-28 10:30 CST；全站需求闭环、逐页状态合同、安全优先边界和非目标；
  无问题、无修改。
- 第 2 轮：2026-08-28 10:31 CST；13 个真实路由与侧栏入口、storage 白名单、认证失效清理、
  V59/API/事务兼容；无问题、无修改。
- 第 3 轮：2026-08-28 10:31 CST；实施切片、后端真实集成、前端全站验收、真实 Chat、回滚和
  Git 交付；无问题、无修改。
- 结果：修订后的规划连续 `3/3` 通过，可以建立规划 checkpoint 并进入实施。

## 下一步

1. 执行文档、diff 和密钥门禁；
2. 提交并推送规划 checkpoint；
3. 在当前工作区创建基于最新 `main` 的专用特性分支，不创建额外 worktree；
4. 按规划切片 A-D 实施和验证。
