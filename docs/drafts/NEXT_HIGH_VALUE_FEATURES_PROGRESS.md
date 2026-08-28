# WebUI 工作上下文、文件可发现性与统一弹层实施进度

> 对应规划：[NEXT_HIGH_VALUE_FEATURES_PLAN.md](NEXT_HIGH_VALUE_FEATURES_PLAN.md)
> 工作区：`/Users/yangjiefeng/Documents/wubuku/spring-ai-rag`
> 当前分支：`main`
> 规划基线：`main@284da44f`

## 当前状态

- 阶段：修订后规划 checkpoint 待提交
- 规划审查计数：`3/3`
- 实施：未开始
- worktree：只使用主工作区；未创建额外 worktree，未使用 stash

## 用户提醒与硬边界

- UUID 是必要的稳定内部身份，但 Files WebUI 不能只显示 UUID。
- 不做 ABTest 单点透明背景修补；全面统一 modal/dialog/overlay/popover/menu/toast 的设计令牌
  与层级，其中 modal 必须统一可访问行为。
- 路由切换后，侧栏全部 13 个顶级入口都应恢复各自最后合法工作上下文；不能只修
  Chat/Search/Files 三个示例。必要状态不等于所有局部 state，仍不得保存 API Key、
  shown-once credential、文档正文、modal 开关或 in-flight 状态。
- 前端验收只使用 DOM、ARIA、computed style、URL、network 和 JSON，不使用截图。
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
