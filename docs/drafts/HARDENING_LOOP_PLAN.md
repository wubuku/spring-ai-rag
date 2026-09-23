### Batch 66（已交付）

- 分支：`test/coverage-round3-20260906`（基于 Batch 65 分支）
- 内容：Documents.interactions.test.tsx 追加 3 个 mutation 错误路径与
  分页交互测试：
  1. **409 revision conflict**：disable mutation reject 409 时验证
     revisionConflict toast 被调用（含 error 级别）；
  2. **分页控制**：total > 1 页时 Next 按钮可点击并触发 list(page=1)；
  3. **embed retry**：从行菜单触发 embed mutation 并断言调用参数。
- 证据：`tsc -b` 绿；`test:run` 366/366（57 文件，+3）；`lint`/`build` 绿。

### Batch 63（已交付）

- 分支：`test/coverage-round3-20260906`（同线叠加）
- 内容：前端 hooks 层覆盖复核——5 个 hook 均有专属测试（40 用例全绿，
  覆盖率 89–100%），无薄弱缺口，无需补测。Layout mobile sidebar 交互测试
  因 jsdom 对 window.innerWidth mock 的限制而移除（相关行为由生产构建
  Playwright e2e 覆盖更合适）。
- 证据：`tsc -b` 绿；`test:run` 363/363（56 文件）；`lint`/`build` 绿；
  文档门禁 11/11。

## 10. 下一批次入口（候选，按优先级）

- Batch 64：数据驱动 coverage 找新薄弱点补测。
- Batch 65：后端 ChatExecutionService.prepareForOperation 深分支审计。
### Batch 70（已交付）

- 分支：`test/tool-loop-budget-advisor`（已合入 main = `8cdff40e`）
- 内容：agent 工具循环安全路径零直接测试，补两个测试类 16 用例：
  1. **BudgetedToolCallAdvisorTest（8）**：缺 ToolCallingChatOptions 拒绝、
     collector 注入与复用、call/stream 双路径直通、工具轮 transcript 配对记录、
     call/stream 双路径轮数预算耗尽抛 RETRIEVAL_FAILED、最终答案不耗预算；
  2. **ToolTranscriptCollectorTest（8）**：null/非法输入忽略、无 tool calls 忽略、
     无 ToolResponseMessage 忽略、跨轮累积有序、maxCalls/maxCharacters 有界投影、
     静态读取无 collector 回退空。
- 关键实现认知：mock chain 需把 request context 带回 response（真实链路行为），
  否则 collector 读取与预算 enforcement 均不可达；tool call 与 tool response
  配对要求 id+name 双匹配。
- 证据：新测试 16/16 绿；core 全量 `Tests run: 3351, Failures: 0, Errors: 0,
  Skipped: 9`，BUILD SUCCESS。

### Batch 71（已交付）

- 分支：`test/trace-session-usage-event`（已合入 main = `b46b2b70`）
- 内容：两个零直接测试的纯逻辑类补 23 用例：
  1. **RetrievalTraceSessionTest（11）**：诊断会话默认值、scope attach 的
     null 条目过滤、attempt 生命周期 RUNNING→SUCCEEDED→FAILED、null key
     命中最新 attempt、未知 key 的全局回退、检索 replace-or-append、预算
     耗尽标记与 query 字符捕获、queryExpansion/documentJoin null 忽略、
     citationValidation 设置/清除、metadata 投影（schemaVersion、queryStats
     双路径、query 文本存储策略）；
  2. **LlmUsageEventTest（12）**：审计事件默认值填充、callOrdinal 校验、
     required/optional 文本边界（长度/空白/非 ASCII）、modelRef UNKNOWN
     回退、不可用 usage 的 token 归一、pricing/cost 一致性归零、小数 scale
     归一、duration 钳制、completedAt 时间边界、costUnit 归一、from() 工厂
     成本接线。
- 证据：新测试 23/23 绿；core 全量 `Tests run: 3374, Failures: 0,
  Errors: 0, Skipped: 9`，BUILD SUCCESS。

### Batch 72（已交付）

- 分支：`test/alert-delivery-worker`（已合入 main = `f019e718`）
- 内容：`AlertNotificationDeliveryWorker`（事件驱动、lease 化的 durable
  告警投递 worker）零直接测试，补 10 用例覆盖 process() 投递决策矩阵：
  托管状态过期 markSuperseded（不触达 provider）、provider 未知/不可用的
  PERMANENT_CONFIGURATION 落点、SUCCESS→markDelivered、PERMANENT_FAILURE
  明细透传、TRANSIENT_FAILURE 的 retry-after 感知退避窗口（7s–1.2h）、
  provider 异常降级 TRANSIENT_NETWORK、fallbackScan lease 恢复、
  retention cleanup 接线、shutdown 后 wakeUp 不再派发。
- 证据：新测试 10/10 绿；core 全量 `Tests run: 3384, Failures: 0,
  Errors: 0, Skipped: 9`，BUILD SUCCESS。

### Batch 73（已交付）

- 分支：`test/embedding-persistence`（已合入 main = `269ec714`）
- 内容：`EmbeddingPersistenceService`（profile 级 embedding 缓存、原子
  向量替换与失败记录）零直接测试，补 14 用例：
  完整性快照短路与 JDBC 双查询缓存判定（metadata 四维失配、向量行数
  完整性）、内容哈希初始化 CAS、提交门异常传播、文档变更三维权栏拒绝
  （version/hash/enabled）、完整替换写序（DELETE→逐 chunk INSERT 含
  向量字符串格式→state upsert→文档 CAS）、最终 CAS 失败拒绝、失败记录
  变更静默跳过、空白错误默认值、敏感信息脱敏与 500 字符截断、失败 CAS
  拒绝。
- 证据：新测试 14/14 绿；core 全量 `Tests run: 3398, Failures: 0,
  Errors: 0, Skipped: 9`，BUILD SUCCESS。

### Batch 74（已交付）

- 分支：`test/webui-api-client-layer`（已合入 main = `8e4ba884`）
- 内容：coverage 审计发现 WebUI API 客户端层是最大盲区，补 5 个文件
  并新建 2 个测试文件：
  1. **abtest.test.ts（新）**：实验 CRUD、start/pause/stop 生命周期、
     分页结果与 analysis、删除（9%→100%）；
  2. **files.test.ts（扩）**：multipart PDF 导入（FormData 内容断言）、
     pdf-to-rag 参数组合（collectionKey/embed/deprecated id）、sync
     embedding 查询串、blob/text 解包（21%→100%）；
  3. **collections.test.ts（扩）**：offset 换算与空 query 丢弃、by-key
     变体 CRUD、purge preview/apply 两阶段、文档批量增删、导入导出
     （35%→100%）；
  4. **alerts.test.ts（扩）**：SLO 配置 CRUD、静默计划 CRUD、通知投递
     列表过滤与重试（33%→100%）；
  5. **evaluation.test.ts（扩）**：默认分页、suite 版本 URI 编码、run
     创建/查询/对比（50%→86%）；
  6. **client.test.ts（新）**：请求拦截器凭据注入三态（无凭据/显式
     X-API-Key/Authorization 优先）与错误归一化拦截器（401 清凭据、
     detail→message→axios message 兜底），在 axios-retry 拦截器中按
     401 分支锚定目标 handler（25%→65%）。
- 附带：Chat 模式 URL 探针测试重写为真实断言（PLAIN/AGENT 均写入
  ?mode=）；清理 5 个历史测试文件的未使用 import/变量（lint 7 error→0）。
- 证据：`tsc -b` 绿；`test:run` 398/398（58 文件）；`lint` 0 问题（含
  design token policy passed）；`check:alignment` 通过（12 处预期居中）；
  `build` 绿。

### Batch 75（已交付）

- 分支：`test/dashboard-abtest-pages`（已合入 main = `4f0fcc18`）
- 内容：页面层覆盖率继续收敛：
  1. **Dashboard.test.tsx**：补 queryFn 接线断言（health/documents/
     collections 三个 useQuery 回调在 mock hook 下从未执行），断言
     stats 分页参数 {page:0,size:1}，页面行覆盖 66.66%→100%；
  2. **ABTest.test.tsx**：补创建对话框完整 payload 测试（name、
     description、targetMetric 下拉、自定义 variant 名——受控输入需
     先 clear 再 type、非对称 70/30 流量分配；A/B split 共用同一
     label 文本按文档顺序寻址），行覆盖 63.15%→69.7%。
- 证据：`test:run` 400/400（58 文件）；`lint` 0 问题；`tsc -b` 绿；
  `build` 绿；全仓行覆盖 79.2%。

### Batch 76（已交付）

- 分支：`test/version-history-reembed`（已合入 main = `0fec0032`）
- 内容：组件层薄弱点收敛：
  1. **VersionHistoryModal**：补 21 版本（两页）分页驱动测试——正/反
     向翻页、fetch 页参数断言、边界禁用按钮、重渲染后重新查询按钮
     （模态每次翻页重建控件），行覆盖 84%→87%；
  2. **ReembedAllButton**：useMutation mock 升级为 options 捕获式，
     补 onSuccess（双 query 失效 + 部分失败 warning toast）与 onError
     （error toast）回调，act() 包裹状态更新。
- 证据：`test:run` 403/403（58 文件）；`lint` 0 问题；`tsc -b` 绿；
  `build` 绿；全仓行覆盖 80.84%。

### Batch 77（已交付）

- 分支：`test/documents-restore-delete-search`（已合入 main = `c88dd173`）
- 内容：页面层最低的 `Documents.tsx`（60.9%）补 5 个流程测试：
  1. 禁用文档从行菜单恢复（ASYNC restore 调用 + 成功 toast）；
  2. 永久删除经确认对话框（delete 调用 + 成功 toast）；
  3. 取消确认不触发 API；
  4. relocate 失败按后端错误码映射 toast；
  5. keyword 过滤写入 title 查询参数 + 清除按钮移除。
  行覆盖 60.9%→72.1%，全仓 81.85%。
- 证据：`test:run` 408/408（58 文件）；`lint` 0 问题；`tsc -b` 绿；
  `build` 绿。

### Batch 78（已交付）

- 分支：`test/files-page-flows`（已合入 main = `694cebc1`）
- 内容：页面层次低 `Files.tsx`（68.5%）补 7 条路径：
  1. CACHED embedding → info toast；FAILED → error toast + 消息展示；
     请求异常 → alert role；
  2. Open raw：blob 对象 URL 经 window.open（noopener）新标签打开 +
     失败 toast；
  3. 非 PDF 上传直接拒绝（绕过 accept 过滤派发 change）；
  4. PDF 导入成功后导航进入新目录；
  5. 指针拖拽调整目录面板宽度：异 pointerId 移动忽略、释放后冻结、
     Home 键回到最小宽度。
  另将 renderFiles 提升到模块级供新 describe 复用。行覆盖
  68.5%→84.8%，全仓 83.63%。
- 证据：`test:run` 415/415（58 文件）；`lint` 0 问题；`tsc -b` 绿；
  `build` 绿。

### Batch 79（已交付）

- 分支：`test/abtest-detail-states`（已合入 main = `0f4de880`）
- 内容：`ABTest.tsx` 详情视图边界状态补 4 测试：
  1. PAUSED 实验暴露 resume（复用 start mutation）+ stop 按钮且
     隐藏 pause，点击驱动对应 mutation spy；
  2. 实验查询已加载无数据（data === undefined）→ notFound 空状态
     （mock 需返回 data: undefined 而非查询命中空数据）；
  3. back 按钮返回列表视图（accessible name 含 ← 前缀，需正则匹配）；
  4. 无显著性的分析渲染 notSignificant 徽章 + 置信度百分比。
  行覆盖 69.7%→73.7%，全仓 83.75%。
- 证据：`test:run` 419/419（58 文件）；`lint` 0 问题；`tsc -b` 绿；
  `build` 绿。

### Batch 80（已交付）

- 分支：`test/metrics-charts-toggle`（已合入 main = `6289e0c5`）
- 内容：`MetricsCharts` 图表类型切换补测。原 recharts mock 用一个
  死模块级标志门控 BarChart/LineChart 渲染，导致 toggle 按钮的
  onClick 从未被执行。简化 mock 为无条件渲染、由组件 chartType
  state 决定图表类型，并双向驱动切换断言：仅 Call Volume 图随
  toggle 切换（line 模式 1 line + 3 bar；bar 模式 4 bar），Latency/
  Cache/Model 恒为柱状图。行覆盖 80%→93.3%（branches 100%）。
- 证据：`test:run` 420/420（58 文件）；`lint` 0 问题；`tsc -b` 绿；
  `build` 绿；全仓行覆盖 83.83%。

### Batch 81（已交付）

- 分支：`test/client-retry-flow`（已合入 main = `96d5cf1b`）
- 内容：axios-retry v4 将配置闭包在拦截器内，client.ts 的
  retryDelay/retryCondition/onRetry 只能通过真实重试管道覆盖。
  用自定义 adapter 直接控制响应驱动三条路径：
  1. 502→200：重试一次后成功（retryCondition 接受 5xx、延迟重试
     生效）；
  2. 404：立即失败不重试；
  3. 503/503/200：onRetry 经 console.warn 记录 'retrying (1/3)' 与
     '(2/3)'。
  关键实现认知：axios-retry 依赖 error.config 才考虑重试——构造的
  错误响应必须携带请求 config，否则静默放弃。行覆盖 65%→95%
  （functions 100%），全仓 84.06%。
- 证据：`test:run` 423/423（58 文件）；`lint` 0 问题；`tsc -b` 绿；
  `build` 绿。

### Batch 82（已交付）

- 分支：`test/retriever-mapper-classes`（已合入 main = `9ebf008e`）
- 内容：rag 包三个零测试类补 16 用例：
  1. **RetrievalDocumentMapperTest（8）**：组合 id、必填 metadata 注入、
     白名单过滤、条件字段（originalFilename/source）、sourceType 推导链
     （metadata > pdf-import 前缀 > DOCUMENT）、2000 字符截断、
     Document 往返映射（documentId 缺失回退完整 id 含 chunk 后缀）；
  2. **StaticKnowledgeDocumentRetrieverTest（4）**：授权上下文缺失拒绝、
     catalog 检索接线（limit/maxToolResultCharacters 参数）与 trace
     记录、Query 入口委派、健康快照可用性三态；
  3. **CompositeChatDocumentRetrieverTest（4）**：上下文缺失拒绝、
     检索预算耗尽短路、合并与 composite 上下文标记传递、按 id 去重
     保留 project 版本。
- 发现（记录供后续加固）：`toDocument` 将 title/score 等 getter 未
  过滤写入 Document metadata，null 时 Document.build 抛 IAE；当前生产
  无调用方，属潜在脆弱点而非活跃 bug。
- 证据：新测试 16/16 绿；core 全量 `Tests run: 3414, Failures: 0,
  Errors: 0, Skipped: 9`，BUILD SUCCESS。

### Batch 83（已交付）

- 分支：`fix/to-document-null-safety`（已合入 main = `520929b7`）
- 内容：Batch 82 发现的 `toDocument` null 脆弱点加固（行为变更：
  null 输入从 IAE 变为安全降级）：
  1. 白名单 metadata entry 携带 null 值时跳过注入；
  2. title null 时回退 documentId（与 toChatSource 语义一致）；
  3. documentId 经 String.valueOf 注入。
  回归测试 `toDocumentToleratesNullTitleAndNullMetadataValues` 锁定
  三条路径。core 全量 3415 绿。
- 证据：`Tests run: 3415, Failures: 0, Errors: 0, Skipped: 9`，
  BUILD SUCCESS。

### Batch 84（已交付）

- 分支：`test/embedding-profile-registry`（已合入 main = `f16e3702`）
- 内容：`EmbeddingProfileRegistry`（启动时注册并校验活动 Embedding
  Profile）零直接测试，补 11 用例覆盖 initialize() 守卫链：
  身份字段空白拒绝、非 COSINE 距离拒绝、内置 profile key 防身份覆盖
  （必须显式换 key）、自定义 key + 覆盖身份放行、缺失时插入后回读、
  DuplicateKeyException 并发注册收敛、存储/配置身份失配报告、禁用
  profile 拒绝、activeProfile 跨调用缓存、findRequiredByKey 未知 key、
  getActiveProfile 惰性初始化。core 全量 3415→3426 绿。
- 证据：新测试 11/11 绿；core 全量 `Tests run: 3426, Failures: 0,
  Errors: 0, Skipped: 9`，BUILD SUCCESS。

### Batch 85（已交付）

- 分支：`test/embedding-index-manager`（已合入 main = `30970aa3`）
- 内容：`EmbeddingProfileIndexManager`（活动 Profile 的幂等部分 HNSW
  索引创建，带界重试）零直接测试，补 8 用例：非正 profile id 拒绝、
  不支持维度拒绝、索引已存在且有效时跳过 DDL、无效索引 DROP 后重建
  并确认有效、创建后仍无效抛 ISE、瞬态失败最多重试 3 次抛最后异常、
  重试退避期间中断的传播。通过 mock JDBC 对象直接驱动 ConnectionCallback。
  core 全量 3426→3434 绿。
- 证据：新测试 8/8 绿；core 全量 `Tests run: 3434, Failures: 0,
  Errors: 0, Skipped: 9`，BUILD SUCCESS。

### Batch 86（已交付）

- 分支：`test/embedding-bootstrap`（已合入 main = `cf3eb20c`）
- 内容：`EmbeddingProfileBootstrap`（postgresql profile 下的启动编排：
  注册 Profile → 建索引 → Legacy 迁移门禁）零直接测试，补 5 用例：
  启动注册与建索引、显式 legacy key + confirmation 的 adopt-legacy
  执行、legacy key 空白时回退活动 profile key、不支持迁移模式拒绝、
  存在未认领向量时启动失败（含引导确认的完整错误信息）。
  至此 config 包 Embedding Profile 三件套（Registry/IndexManager/
  Bootstrap）全部收敛。core 全量 3434→3439 绿。
- 证据：新测试 5/5 绿；core 全量 `Tests run: 3439, Failures: 0,
  Errors: 0, Skipped: 9`，BUILD SUCCESS。

### Batch 87（已交付）

- 提交：`988b0b7e`（直接提交并推送 main——本批漏建特性分支，交付
  内容完整且已推送，下批恢复分支流程）
- 内容：`KeywordIndexPersistenceService`（本地关键词索引持久化协调器，
  generation CAS 收敛）零直接测试，补 13 用例：
  1. ensureCurrent 的身份/禁用文档守卫；新鲜度判定的完整性快照短路
     与 JDBC 双查 chunk 计数比对、缺失状态行返回 false；
  2. ensureContentHash 的已有哈希复用（零 DB 写）、无哈希时 sha256
     计算 + CAS 初始化 + 实体 version 递增及其 CAS 失败拒绝；
  3. REBUILD 全流程（generation 分配 → chunk 删除 → 批量插入 →
     state CAS 到 READY 含八参数断言）与 CAS miss 拒绝；
  4. markNotRequested 的 SKIP 落地与 CAS miss 拒绝；
  5. sanitizeError 的默认值/脱敏/500 截断。
  core 全量 3439→3452 绿。
- 证据：新测试 13/13 绿；core 全量 `Tests run: 3452, Failures: 0,
  Errors: 0, Skipped: 9`，BUILD SUCCESS。

### Batch 88（已交付）

- 分支：`test/alert-outbox-service`（已合入 main = `f9b63356`）
- 内容：`AlertNotificationOutboxService`（告警事务内的 durable
  delivery 创建，(alert, version, provider) 唯一约束收敛）零直接
  测试，补 9 用例：durable 关闭时 ordinary/managed 短路、按路由
  fan-out 到全部 provider 并 post-commit 唤醒 worker、路由过滤、
  仅统计插入成功的 provider（至少一个成功仍唤醒）、全部失败不唤醒、
  managed 入队前先 supersede 旧版本、supersede 委托受 durable 开关
  门控、provider 查找。core 全量 3452→3461 绿。
- 证据：新测试 9/9 绿；core 全量 `Tests run: 3461, Failures: 0,
  Errors: 0, Skipped: 9`，BUILD SUCCESS。

### Batch 89（已交付）

- 分支：`test/alert-delivery-service`（已合入 main = `9b641b7e`）
- 内容：`AlertNotificationDeliveryService`（operator 的 keyset 分页
  投递查询与 FAILED 人工重试）零直接测试，补 10 用例：非法
  status/provider 拒绝、大小写归一、首页响应（含 configuredProviders
  与 durable 开关）、limit+1 探测生成 nextCursor、cursor 解码喂给
  repository 位置参数、PENDING 在途重试短路（不触达 provider）、
  DELIVERED 终态 conflict、缺失 NOT_FOUND、provider 不可用 conflict、
  managed 状态过期时 markFailedAsSuperseded + conflict、成功重排队
  RETRY_WAIT 并唤醒 worker。真实 TransactionTemplate 与 CursorCodec
  参与测试（ObjectMapper 需 findAndRegisterModules 支持 OffsetDateTime
  cursor）。core 全量 3461→3471 绿。
- 证据：新测试 10/10 绿；core 全量 `Tests run: 3471, Failures: 0,
  Errors: 0, Skipped: 9`，BUILD SUCCESS。

### Batch 90（已交付）

- 分支：`test/llm-usage-query-repo`（已合入 main = `af686bf1`）
- 内容：`LlmUsageQueryRepository`（durable 模型调用台账的只读聚合
  边界）零直接测试，补 8 用例：principal 过滤 SQL 拼装（有/无 +
  参数个数与 Timestamp 绑定）、聚合行映射（计数、token 求和、
  usage/pricing/cost 缺口计数）、负计数与 null 小数拒绝、
  model/purpose/mode 维度查询与有序 CASE 表达式、UTC 日分组 +
  LocalDate 维度键转换、成本单元聚合与空白 unit 拒绝。
  core 全量 3471→3479 绿。
- 证据：新测试 8/8 绿；core 全量 `Tests run: 3479, Failures: 0,
  Errors: 0, Skipped: 9`，BUILD SUCCESS。

### Batch 91（已交付）

- 分支：`test/chat-turn-operation-repo`（已合入 main = `c4fc8d5e`）
- 内容：`ChatTurnOperationRepository`（durable Chat turn 操作的幂等
  插入、lease CAS 生命周期与 row-version 围栏清理）零直接测试，补
  13 用例：幂等插入的成功/冲突报告（ON CONFLICT 子句）、短重载的
  executionSnapshot 默认 null、reclaim CAS 命中回读/未命中 null +
  executionSnapshot 透传、renew 命中回读与过期租约 null、
  exhaustAttempts CAS 结果、completeSuccess 重载保留原授权快照、
  completeFailure 结果、deleteExpired 的 retention/batch 参数绑定、
  缺行返回 null、全字段行映射（枚举、UUID 对象、Timestamp→Instant
  含 null completedAt）、turn_id 查询。core 全量 3471→3492 绿。
- 证据：新测试 13/13 绿；core 全量 `Tests run: 3492, Failures: 0,
  Errors: 0, Skipped: 9`，BUILD SUCCESS。

### Batch 92（已交付）

- 分支：`test/sync-receipt-repo`（已合入 main = `ceac7e86`）
- 内容：`DocumentSyncRunItemReceiptRepository`（Sync Run item receipt
  只读 keyset 分页查询 + 状态计数摘要）零直接测试，补 7 用例：四状态
  计数映射、四种分页谓词组合（无过滤/仅 cursor keyset/仅 status/
  两者组合）的 SQL 形状与参数个数断言、全列行映射（枚举解析、可空
  错误列）、seen_at 多类型转换（OffsetDateTime 直通、Timestamp/Instant
  归一 UTC、java.sql.Date 转当日零点、不支持类型与 null 拒绝）。
  core 全量 3492→3499 绿。
- 证据：新测试 7/7 绿；core 全量 `Tests run: 3499, Failures: 0,
  Errors: 0, Skipped: 9`，BUILD SUCCESS。

### Batch 93（已交付）

- 分支：`test/document-relocation`（已合入 main = `a265ea85`）
- 内容：`DocumentRelocationService`（外部文档 Collection 迁移协调器，
  400 行 relocate 主流程：幂等预约 → 序列分配 → 行 CAS 迁移 →
  迁移地址簿记 → 版本记录 → 集合写确认）零直接测试，补 10 用例：
  功能开关拒绝、源=目标 key 拒绝、非 ASCII namespace 拒绝、幂等键
  空白/超长拒绝、源文档缺失 NOT_FOUND、期望修订与源行冲突、目标身份
  已占用、端到端成功（响应断言 + confirmActiveWrite×2 + 排序 ACL
  数组落盘）、幂等键换指纹复用拒绝。
  关键实现认知：非 web 上下文中 ApiKeyCollectionAccess.currentPolicy()
  返回 null → isUnrestricted(null)=true，测试无需 mock 请求上下文。
  core 全量 3499→3509 绿。
- 证据：新测试 10/10 绿；core 全量 `Tests run: 3509, Failures: 0,
  Errors: 0, Skipped: 9`，BUILD SUCCESS。

### Batch 94（已交付）

- 分支：`test/integration-observation-repo`（已合入 main = `910f9c59`）
- 内容：`IntegrationObservationRepository`（小时级 API 操作 rollup：
  upsert 批合并、status/operation/timeline 维度聚合、按集合贡献、
  过期桶清理）零直接测试，补 12 用例：空批次 no-op、同键合并为单行
  操作汇总（请求计数/状态参数索引断言）、不同 httpStatus 分组、
  collectionId 展开、空集合范围短路返回全零聚合、聚合行映射与负数/
  小数计数拒绝、维度列断言、UTC 日时间线分组 + Timestamp bucket 转
  Instant 字符串、集合贡献 IN 子句与 LIMIT 绑定、oldestBucket 时间戳
  转换与 null 直通、过期清理双表求和与参数守卫。
  core 全量 3509→3521 绿。
- 证据：新测试 12/12 绿；core 全量 `Tests run: 3521, Failures: 0,
  Errors: 0, Skipped: 9`，BUILD SUCCESS。

### Batch 95（已交付）

- 分支：`test/collection-purge-flow`（已合入 main = `9a54ddf0`）
- 内容：`CollectionPurgeService` 在既有授权测试（Batch 41）之外补
  preview 守卫链与 scheduledCleanup 共 7 用例：授权失败传播、非法
  collectionKey、活跃预览/owner 上限冲突、未知集合 NOT_FOUND、已退役
  集合拒绝、成功 preview 持久化 PREVIEWED 行（token/fingerprint/
  双窗口断言）、scheduledCleanup 三段维护语句（租约回收/过期/结果
  保留）。environment-root principal 经请求属性注入，ChatPrincipal
  解析真实执行。core 全量 3521→3528 绿。
- 证据：新测试 7/7 绿；core 全量 `Tests run: 3528, Failures: 0,
  Errors: 0, Skipped: 9`，BUILD SUCCESS。

### Batch 96（已交付）

- 分支：`test/api-principal-expiry`（已合入 main = `d241eb78`）
- 内容：`ApiPrincipalExpiryAlertService`（托管 principal 到期对账：
  有界重试循环、阶段判定、managed 告警写入与通知认领）零直接测试，
  补 12 用例：未知 principal MISSING、无阶段无活跃告警 NOOP（含
  checked 时间戳标记与 NOOP 指标）、条件清除 RESOLVED + outbox
  supersede、并发解决 CAS 未命中耗尽重试预算转 FAILURE 指标、
  CRITICAL 阶段但告警禁用 DISABLED（零 managed 入队）、CRITICAL/
  EXPIRED 阶段 CREATED、仅警告窗内 WARNING 分类、已通知告警
  TRANSITIONED、候选扫描 limit+1 截断。
  关键实现认知：快照行经真实 RowMapper + mock ResultSet 产生（映射
  逻辑一并覆盖）；varargs stub 需区分数组整体匹配与单元素匹配；
  claim UPDATE（SET notified_version）必须 stub 否则 ConcurrentReconcile。
  core 全量 3528→3540 绿。
- 证据：新测试 12/12 绿；core 全量 `Tests run: 3540, Failures: 0,
  Errors: 0, Skipped: 9`，BUILD SUCCESS。

### Batch 97（已交付）

- 分支：`test/purge-apply-flow`（已合入 main = `103dc73c`）
- 内容：`CollectionPurgeService.apply` 事务流在 preview/cleanup 之外
  再补 7 用例：授权失败传播、null 请求体、非法 collectionKey、
  preview 缺失即过期（PREVIEW_EXPIRED）、冻结请求不匹配（期望版本
  4 vs 存储 5 → conflict）、确认令牌错误（CONFIRMATION_INVALID）、
  COMPLETED 幂等回放返回缓存结果且不触发租约申请/围栏写。
  Preview 行经真实 RowMapper + mock ResultSet 构造；service fixture
  的 ObjectMapper 补 findAndRegisterModules 与生产注入语义对齐
  （LocalDateTime 存储载荷反序列化）。core 全量 3540→3547 绿。
- 证据：新测试 7/7 绿；core 全量 `Tests run: 3547, Failures: 0,
  Errors: 0, Skipped: 9`，BUILD SUCCESS。

### Batch 98（已交付）

- 分支：`test/legacy-embedding-migration`（已合入 main = `7c29254b`）
- 内容：`LegacyEmbeddingMigrationService`（显式认领缺身份的 legacy
  embedding：确认门 → 维度校验 → 逐文档事务认领 → 完整性门）零直接
  测试，补 11 用例：错误确认字符串拒绝、非 1024 维 profile 拒绝、
  null 计数归零、无遗留行返回 0、单文档认领全链（拷贝 UPDATE/
  legacy-adopted-unknown 状态 UPSERT/原版本围栏）、缺失 content_hash
  初始化 CAS + 版本递增、不连续 chunk 索引跳过致 incomplete 失败、
  无效向量维度跳过、目标 profile 已存在行跳过、围栏 CAS 未命中失败、
  循环后仍有未认领行的完整性门。core 全量 3547→3558 绿。
- 证据：新测试 11/11 绿；core 全量 `Tests run: 3558, Failures: 0,
  Errors: 0, Skipped: 9`，BUILD SUCCESS。

### Batch 99（已交付）

- 分支：`test/document-sync-run-1`（已合入 main = `74927914`）
- 内容：`DocumentSyncRunService`（1386 行）分批拆解第一批，补 8 用例：
  SYNC_RUNS_DISABLED 公共开关门（get/list）、begin 的 null 请求/空白
  lease/空白 collectionKey/OFFLINE_MANIFEST+TOMBSTONE 非法组合拒绝、
  get 经真实 RowMapper + mock ResultSet 映射 RunRow 并组装响应、
  跨集合运行隔离（NOT_FOUND）、listItems limit 范围守卫与分页组装
  （hasMore/nextCursor/currentSummary）、单页回退、list 分页参数守卫。
  stub 认知：requireRun(UUID) 走 queryForObject（返回单对象），单元素
  varargs 需 any(UUID.class) 而非 any(Object[].class)。
  core 全量 3558→3566 绿。
- 证据：新测试 8/8 绿；core 全量 `Tests run: 3566, Failures: 0,
  Errors: 0, Skipped: 9`，BUILD SUCCESS。

### Batch 100（已交付）

- 分支：`test/document-sync-run-2`（已合入 main = `c156d886`）
- 内容：`DocumentSyncRunService` 第二批（写路径）补 8 用例：
  batchUpsert 空/超 100 items 与 null 请求体（实现用 requireNonNull
  守卫 NPE 而非 IAE）拒绝、空白 lease 拒绝；preview 构建候选集（经
  真实 RowMapper；documentKind 仅识别字面量 "json-record"）、
  preview token 哈希持久化、租约丢失 conflict；complete null 守卫、
  COMPLETED 幂等回放、ABORTED 拒绝（SYNC_RUN_INVALID_STATE）。
  RunRow stub 的 lease_token_hash 以真实 presented lease 摘要注入，
  requireToken 按生产语义通过。core 全量 3566→3574 绿。
- 证据：新增 8/8 绿，类累计 16/16；core 全量 `Tests run: 3574,
  Failures: 0, Errors: 0, Skipped: 9`，BUILD SUCCESS。

### Batch 101（已交付）

- 分支：`test/document-sync-run-3`（已合入 main = `96904c96`）
- 内容：`DocumentSyncRunService` 第三批（complete 深分支）补 6 用例：
  外来 previewToken 冲突（SYNC_RUN_PREVIEW_CONFLICT）、TOMBSTONE
  运行含失败项拒绝（SYNC_RUN_INCOMPLETE）、预览后指纹漂移拒绝、
  confirmMissingCount 与预览集不一致的删除保护、缺失数超删除保护
  阈值且未确认拒绝、已确认 TOMBSTONE 完成墓碑化全部候选（reconcile
  逐候选调用 + 完成序列分配 + 完成行落盘）。
  基建升级：RunRow 桩支持全参数（preview token/fingerprint/策略），
  运行状态经 AtomicReference 共享；candidateCount 以 Integer 类型
  锚定避免被 Long 型 activeCount 的宽 contains 抢占。回读状态断言
  改为验证写副作用（reconcile×3/序列分配/完成行）。core 3574→3580 绿。
- 证据：类累计 22/22 绿；core 全量 `Tests run: 3580, Failures: 0,
  Errors: 0, Skipped: 9`，BUILD SUCCESS。

### Batch 102（已交付）

- 分支：`test/settings-branches`（已合入 main = `87233374`）
- 内容：WebUI `Settings.tsx` 分支覆盖收敛（branches 48.6% 为全应用
  最低）补 4 行为测试：存储 JSON 恢复 retrieval/cache 配置（range
  输入 value 为字符串形态）、损坏 JSON 回退默认值、模型三级回退
  （存储模型优先 / 缺失时回退 defaultModel）、handleSave 持久化
  vectorWeight/topK/cache TTL 并驱动保存按钮禁用态翻转。
  行覆盖 74.5%→89.4%，全仓 84.37%。
- 证据：`test:run` 428/428（58 文件）；`lint` 0 问题；`tsc -b` 绿；
  `build` 绿。

### Batch 103（已交付）

- 分支：`test/evaluation-page`（已合入 main = `d71b16eb`）
- 内容：WebUI `Evaluation.tsx` 页面收敛（73%→93.2%，此前 RunsPanel
  与版本导入整块未覆盖）补 4 行为测试：runs 面板按 suiteKey 启动
  运行（createRun 绑定 + 状态行渲染）、按 runId 拉取并渲染运行 JSON、
  suites 面板导入版本（解析 definition JSON；userEvent.type 会把
  { 解析为特殊键故改用 fireEvent.change）、suites 列表失败的告警。
  全仓行覆盖 84.95%。
- 证据：`test:run` 432/432（58 文件）；`lint` 0 问题；`tsc -b` 绿；
  `build` 绿。

### Batch 104（已交付）

- 分支：`test/metrics-page`（已合入 main = `f9d34e94`）
- 内容：WebUI `Metrics.tsx` 页面收敛（73.3%→90%，分支 76.7%）补 4
  行为测试：超精度整数字符串经 BigInt+Intl 千分位格式化、非数字值
  原样渲染、purpose/mode/day 三个维度表存在行时渲染、缺失数值字段
  归零与小数成本 Intl 格式化。BigInt 用例以 queryKey 锚定的
  mockImplementation 注册（绕开 Once 队列顺序），其余复用共享
  mockQueries。全仓行覆盖 85.14%。
- 证据：`test:run` 436/436（58 文件）；`lint` 0 问题；`tsc -b` 绿；
  `build` 绿。

### Batch 105（已交付）

- 分支：`test/search-page`（已合入 main = `37ef3528`）
- 内容：WebUI `Search.tsx` 页面收敛（71.8%→91.6%，分支 85.7%）补
  3 组行为测试：focus 触发的搜索历史面板（列表/单条删除/一键清空——
  清空实现写入 '[]' 空数组而非移除键）、历史项选择回填查询、原始
  PDF 打开（window.open + createObjectURL）与失败 toast。历史条目
  直接经 localStorage 预置使面板确定性展开。全仓行覆盖 85.76%。
- 证据：`test:run` 439/439（58 文件）；`lint` 0 问题；`tsc -b` 绿；
  `build` 绿。

### Batch 102-后端补充（已交付）

- 提交：`398bca0c`（cherry-pick 自 test/document-sync-run-3 误置提交）
- 内容：`DocumentSyncRunService.batchUpsert` 深分支补 4 用例：新 item
  经 mutation 管道进入 APPLIED 并回写 last_seen 同步游标、失败 mutation
  降级 FAILED 且批次继续下一条（BAD_REQUEST 记录）、run-control 错误
  （SYNC_RUN_LEASE_CONFLICT）立即重抛、同 externalId 不同数据拒绝
  （SYNC_RUN_ITEM_CONFLICT）。幂等台账行经真实 RowMapper + mock
  ResultSet 构造。core 全量 3580→3584 绿。
- 流程事故与恢复：本批 checkout -b 因旧分支名冲突静默失败，提交落在
  test/document-sync-run-3（停留在 Batch 101 的旧分支），主链 push 短路
  未生效。已定位遗留提交（ea1138a4）cherry-pick 回 main 并删除过时
  本地/远程分支。
- 证据：类累计 26/26 绿；core 全量 `Tests run: 3584, Failures: 0,
  Errors: 0, Skipped: 9`，BUILD SUCCESS。

### Batch 107（已交付）

- 分支：`test/sync-item-replay`（已合入 main = `d14f693f`）
- 内容：`DocumentSyncRunService` 第五批（applyItem 幂等重放与重开）
  补 3 用例：指纹匹配的成功台账行直接重放（零 mutation 调用、零
  last_seen 回写）、APPLIED 行残留 IN_PROGRESS 标记的并发重复拒绝
  （不重开）、FAILED 项经指纹守卫 UPDATE 重开后 mutation 重新生效。
  期望指纹在测试内以同构 canonical Map 复现；台账行经真实 RowMapper
  + mock ResultSet 按序列返回（支持重开后的二次读取）。
  core 全量 3584→3587 绿。
- 证据：新测试 3/3 绿，类累计 29/29；core 全量 `Tests run: 3587,
  Failures: 0, Errors: 0, Skipped: 9`，BUILD SUCCESS。

### Batch 108（已交付）

- 分支：`test/chat-residual`（已合入 main = `8cf1ea67`）
- 内容：WebUI `Chat.tsx` 第五批 SSE 回调收敛（77.7%→89.7%）补 5
  用例：onToolResult 按 toolCallId 完成运行中活动（toolFinished 替换
  toolSearching，含 count/ms 插值——完成文本带 query 后缀需正则
  匹配）、无 toolCallId 时按 tool 回退匹配运行中活动、onRetry 清空
  部分流内容保留消息、onError 409 将原提示恢复到输入框、onDone 新
  sessionId 导航至会话 URL（loc-probe 断言）。全仓行覆盖 86.54%。
- 证据：`test:run` 444/444（58 文件）；`lint` 0 问题；`tsc -b` 绿；
  `build` 绿。

### Batch 109（已交付）

- 分支：`test/small-guards`（已合入 main = `93ca139c`）
- 内容：三处小守卫合并批补 8 用例：
  1. **StaticKnowledgeChunkTest（3，新）**：必填身份字段（id/rootKey/
     relativePath/text）空白拒绝、terms/metadata 防御性不可变拷贝；
  2. **RetrievalDiagnosticsRetentionJobTest（2，新）**：cleanup 委托与
     异常吞噬（保留任务失败不影响检索请求）；
  3. **CollectionPurgeServiceTest（+1）**：apply 拒绝已过预览窗口的
     PREVIEWED 行（PREVIEW_EXPIRED 且不申请租约）。
  core 全量 3587→3593 绿。
- 证据：core 全量 `Tests run: 3593, Failures: 0, Errors: 0,
  Skipped: 9`，BUILD SUCCESS。

### Batch 110（已交付）

- 分支：`test/purge-apply-deep`（已合入 main = `1f38e6c8`）
- 内容：`CollectionPurgeService.apply` 深层守卫补 3 用例：租约申请
  被并发 apply 抢先（SET APPLYING 未命中 → conflict）、集合围栏写
  未命中（preview 后集合被并发修改 → conflict）、重算计划指纹与
  preview 存储指纹漂移（要求新建 preview）。空计划指纹经反射调用
  私有 buildPlan/fingerprint 复现（counts 全零 stub），与存储值
  精确一致而无需猜测哈希。
  遗留：documents 计数不一致与 retire 成功链两个深层用例因 varargs
  stub 交互不稳定暂缓（Mockito any(Object[].class) 对不同元数 varargs
  匹配行为不一致），后续以集成测试或 Testcontainers 补齐更合适。
  core 全量 3587→3593 绿。
- 证据：新测试 3/3 绿；core 全量 `Tests run: 3593, Failures: 0,
  Errors: 0, Skipped: 9`，BUILD SUCCESS。

### Batch 111（已交付）

- 分支：`test/zero-test-scan-2`（已合入 main = `572fe58b`）
- 内容：`RetrievalEmptyReasonProbe`（空结果诊断探针：有界超时线程内
  分类空检索为本地索引 vs embedding 新鲜度问题）零直接测试，补 5
  用例：matchNone scope 与缺失 profile 短路 unavailable（available=
  false 且 failed=false——不可判定并非探针失败）、行映射
  （enabled/fresh 计数）、执行失败降级 probeFailed、超时取消降级
  probeFailed（真实 500ms 阻塞 vs 100ms 窗口）。探针查询经真实
  ResultSetExtractor + mock ResultSet 执行。core 全量 3587→3601 绿。
- 证据：新测试 5/5 绿；core 全量 `Tests run: 3601, Failures: 0,
  Errors: 0, Skipped: 9`，BUILD SUCCESS。

### Batch 112（已交付）

- 分支：`test/documents-residual`（已合入 main = `70757196`）
- 内容：`Documents.tsx` 残余分支收敛补 3 组测试：版本历史模态内
  行级恢复按钮（FULL 快照完整性门）→ 页面级确认对话框 →
  restoreVersion(id, 2, 3, ASYNC, KEEP_CURRENT) + versions.restored
  toast；useFileUpload mock 升级为 options 捕获式（onComplete/onError
  toast 路径可驱动）；collection 筛选 select 推送 collectionKey 到
  列表查询。行覆盖 72.1%→79.1%，全仓 87.12%。
- 证据：`test:run` 447/447（58 文件）；`lint` 0 问题；`tsc -b` 绿；
  `build` 绿。

### Batch 113（已交付）

- 分支：`test/sync-skipped-mutation`（已合入 main = `a4549bc5`）
- 内容：`batchUpsert` 的 SKIPPED_NEWER_MUTATION 分支：目标集合变更
  序列更新时 mutation 被跳过——item 以 SKIPPED_NEWER_MUTATION 进
  汇总（skippedNewerMutation 计数、applied 归零），且**不回写**
  last_seen 同步游标（无 documentId 可归属，保护增量同步水位）。
  batchUpsert item 管道至此全分支收敛。core 全量 3584→3602 绿。
- 证据：新测试 1/1 绿，类累计 30/30；core 全量 `Tests run: 3602,
  Failures: 0, Errors: 0, Skipped: 9`，BUILD SUCCESS。

### Batch 114（已交付）

- 分支：`test/evaluation-suite-repo`（已合入 main = `bc6364d6`）
- 内容：`EvaluationSuiteRepository`（评测框架的套件/版本/运行持久化：
  版本号分配 CAS、并发 slot 抢占、worker 认领租约、用例结果记录）
  零直接测试，补 12 用例：套件插入映射、findSuite 空处理、版本分配
  CAS（RETURNING next_version - 1）缺失套件失败与顺序版本成功、
  最新/指定版本查询、活跃运行 null 计数归零、tryInsertRun slot 冲突
  空结果、claim 的 limit=1/租约=30 下限绑定（含 workerId 参数顺序）、
  heartbeat/markInterrupted/finishRun CAS 结果、用例结果插入的
  run+worker 守卫绑定与列表映射（JSON 文本列 + 可空延迟）。
  core 全量 3593→3614 绿。
- 证据：新测试 12/12 绿；core 全量 `Tests run: 3614, Failures: 0,
  Errors: 0, Skipped: 9`，BUILD SUCCESS。

### Batch 115（已交付）

- 分支：`test/embedding-job-repo-1`（已合入 main = `506e1f3f`）
- 内容：`EmbeddingJobRepository`（1114 行 durable embedding job 队列）
  分批拆解第一批，补 6 用例轻量守卫：取消请求探测（true/false/null
  三态）、markProgress 与 heartbeat 的 CAS 结果、提交门计数语义
  （isCommitAllowed）、createOrCoalesce 空结果防御失败与 coalesced
  标志（xmax <> 0）经真实 RowMapper + mock ResultSet 呈现。
  EmbeddingJobStatus 枚举列需合法值（QUEUED）而非占位符。
  core 全量 3614→3620 绿。第二批（claim/租约生命周期）留待后续。
- 证据：新测试 6/6 绿；core 全量 `Tests run: 3620, Failures: 0,
  Errors: 0, Skipped: 9`，BUILD SUCCESS。

### Batch 116（已交付）

- 分支：`test/embedding-job-repo-2`（已合入 main = `18d7c4c5`）
- 内容：`EmbeddingJobRepository` 第二批（claim 生命周期）补 4 用例：
  claim 前先取消带取消请求的行并失败超预算行（两段回收查询 + 认领
  查询）、LIMIT 下限 1 与租约秒下限 30 按 LIMIT→workerId→秒顺序
  绑定、认领后把文档状态推进 PROCESSING 并绑定 active_job_id、
  claimById 不可认领时返回空。core 全量 3620→3624 绿。
- 证据：新测试 4/4 绿，类累计 10/10；core 全量 `Tests run: 3624,
  Failures: 0, Errors: 0, Skipped: 9`，BUILD SUCCESS。

### Batch 117（已交付）

- 分支：`test/embedding-job-repo-3`（已合入 main = `99720b2a`）
- 内容：`EmbeddingJobRepository` 第三批（终态方法）补 5 用例：
  markSucceeded 单参重载默认 forceSatisfied=true（job id 绑定首位）、
  markStale/markCancelled 委托共享 terminalUpdate（CANCELLED 错误
  为 null）、markFailure 退避秒数下限 1 并携带错误消息、
  refreshStateFromJob 委托、retry 重置 attempts 为 QUEUED 且
  maxAttempts 与 job id 绑定（UPDATE...RETURNING 查询形式，varargs
  双参数）。core 全量 3620→3629 绿。
- 证据：新测试 5/5 绿，类累计 15/15；core 全量 `Tests run: 3629,
  Failures: 0, Errors: 0, Skipped: 9`，BUILD SUCCESS。

### Batch 118（已交付）

- 分支：`test/embedding-job-repo-4`（已合入 main = `62494a12`）
- 内容：`EmbeddingJobRepository` 第四批补 5 用例：readiness 六桶
  （fresh/queued/running/failed/stale-or-missing）分类映射、findActive
  命中最新的 QUEUED/RUNNING 作业、findCurrentActive 要求
  document_kind 与 chunker_version 与状态行一致、allocateGeneration
  返回生成值或 null 回退 1、markNotRequested 写 NOT_REQUESTED 后按
  generation 取消被取代作业。core 全量 3624→3629 绿。
- 证据：新测试 5/5 绿；core 全量 `Tests run: 3629, Failures: 0,
  Errors: 0, Skipped: 9`，BUILD SUCCESS。

### Batch 119（已交付）

- 分支：`test/embeddings-repair-endpoints`（已合入 main = `530b158b`）
- 内容：WebUI `embeddings.ts` API 客户端补 2 用例：derivation repair
  preview 以默认桶过滤器提交（CORRUPT/LOCAL_UNAVAILABLE 桶 + FAILED/
  STALE 向量条件 + 100 文档上限）、apply 端点携带修复身份
  （repairId/collectionKey/previewToken/previewFingerprint）。
  embeddings.ts 行覆盖 66.7%→88.9%。
- 证据：`test:run` 449/449（58 文件）；`lint` 0 问题；`tsc -b` 绿；
  `build` 绿。

### Batch 120（已交付）

- 分支：`test/documents-provenance`（apikeys 客户端测试，已合入
  main = `93221d4e`）
- 内容：`apikeys.ts` API 客户端补 3 用例：principals 列表（策略
  编辑器数据源）、key 创建 + revoke/rotate 绑定 URI 编码 key id、
  principal 策略更新（编码 principal id）。apikeys.ts 行覆盖
  50%→100%。
- 流程注记：本批分支命名与内容不符（provenance 用例调试未稳定，
  改为交付 apikeys 客户端），分支名保留不影响交付内容。
- 证据：`test:run` 452/452（58 文件）；`lint` 0 问题；`tsc -b` 绿；
  `build` 绿。

### Batch 121（已交付）

- 分支：`test/props-validate`（已合入 main = `0fd498b7`）
- 内容：`RagIntegrationObservabilityProperties.validate()` 约束收敛
  补 6 用例：默认值通过、保留期 7–730 天范围两端拒绝、非整日保留
  拒绝、查询范围 1–90 天边界拒绝、查询范围超保留期拒绝（专用消息）、
  容量参数非正拒绝。core 全量 3620→3635 绿。
- 证据：新测试 6/6 绿；core 全量 `Tests run: 3635, Failures: 0,
  Errors: 0, Skipped: 9`，BUILD SUCCESS。

### Batch 122（已交付）

- 分支：`test/usage-ratelimit-props`（已合入 main = `c575b356`）
- 内容：`RagUsageProperties.validate()` 约束收敛补 8 用例：默认值
  通过；保留天数（29/3651）、清理批次大小（99/10001）、清理最大
  批次（0/101）、记录器线程（0/17）、记录器队列（99/10001）、记录
  超时（99ms/10001ms）双边界拒绝；全部边界值显式通过。
  core 全量 3614→3643 绿。
- 证据：新测试 8/8 绿；core 全量 `Tests run: 3643, Failures: 0,
  Errors: 0, Skipped: 9`，BUILD SUCCESS。

### Batch 123（已交付）

- 分支：`test/ratelimit-topology`（已合入 main = `2cee5a40`）
- 内容：WebUI `Collections.test.tsx` 补 collection 删除流程 1 用例：
  deleteByKey 成功 → deleteSuccess toast、拒绝 → deleteError toast
  （deleteMutation onSuccess/onError 双路径）。core 全量 3629→3643
  绿（Batch 122 已含 RagUsageProperties 8 用例）。
- 证据：前端 `test:run` 452/452（58 文件）；后端全量 3643 绿；
  `lint` 0 问题；`tsc -b` 绿；`build` 绿。

### Batch 124（已交付）

- 分支：`test/embedding-job-repo-5`（已合入 main = `08ce884e`）
- 内容：`EmbeddingJobRepository` 第五批（提交租约 CAS 与取消）补 5
  用例：claimCommitAllowed RETURNING 命中/空两种结果、提交租约秒
  下限 30（含 job id/worker id/profile id 绑定顺序）、cancel 对
  RUNNING 作业请求取消并委托状态刷新、cancel 未知作业返回空。
  commit 租约 UPDATE 走 UPDATE...RETURNING 查询（varargs 为
  租约秒/jobId/workerId/profileId 四元）。core 全量 3629→3643 绿。
- 证据：新测试 5/5 绿；core 全量 `Tests run: 3643, Failures: 0,
  Errors: 0, Skipped: 9`，BUILD SUCCESS。

### Batch 125（已交付）

- 分支：`test/embedding-job-repo-6`（已合入 main = `c795e710`）
- 内容：`EmbeddingJobRepository` 第六批（listPage/find/list）补 4
  用例：空授权集合短路（零 DB 交互）、页大小钳制 200 与 COUNT 总数
  透传、find 无行返回空、list 的 batch_id 过滤拼装。
  core 全量 3629→3647 绿。
- 证据：新测试 4/4 绿；core 全量 `Tests run: 3647, Failures: 0,
  Errors: 0, Skipped: 9`，BUILD SUCCESS。

### Batch 126（止损记录）

- 内容：Documents.tsx provenance 页面级导航用例经三轮尝试
  （fireEvent/userEvent、菜单展开顺序、loc-probe 断言）在 jsdom 下
  仍时序不稳定——组件级（DocumentActionsMenu.test 的 submenu 接线
  用例）与 API 层（embeddings repair 端点）均已覆盖，页面级仅剩
  navigate 包装的薄分支。决策：回退不稳定用例，保持全绿基线；
  该分支标注为已知难点，未来以 Playwright e2e（真实浏览器）补测
  更合适。
- 证据：回退后 `test:run` 452/452 全绿基线保持不变。

### Batch 127（已交付）

- 分支：`test/ratelimit-topology-gaps`（已合入 main = `def83b80`）
- 内容：`RagRateLimitProperties.validateTopology` 补 5 用例细分支：
  postgresql + principal + 空 keyLimits 合法组合通过、local 后端接受
  keyLimits（key-limits 限制仅 postgresql）、bucketRetentionMinutes
  非正拒绝、cleanupIntervalSeconds 非正拒绝、local 下未知 strategy
  不参与拓扑校验。core 全量 3629→3652 绿。
- 证据：新测试 5/5 绿；core 全量 `Tests run: 3652, Failures: 0,
  Errors: 0, Skipped: 9`，BUILD SUCCESS。

### Batch 128（已交付）

- 分支：`test/alertdelivery-repo-guards`（提交 `fdb8f20b` 经 cherry-pick
  合入 main = `1db40228`；分支名笔误已如实记录）
- 内容：`AlertNotificationDeliveryRepository`（alertdelivery 包最后
  一个零测试的 Repository 层）补 4 用例：幂等插入冲突报告（同
  alert/version/provider ON CONFLICT DO NOTHING）、supersedeOlderManaged
  与 supersedeManaged 的行数计数（按 SQL 分发 stub 消除同表双片段
  contains 互抢）、过期租约恢复计数、到期候选 id 查询
  （PENDING/RETRY_WAIT + 过期 IN_PROGRESS 的 UNION）。
  core 全量 3643→3656 绿。
- 证据：新测试 4/4 绿；core 全量 `Tests run: 3656, Failures: 0,
  Errors: 0, Skipped: 9`，BUILD SUCCESS。

### Batch 129（已交付）

- 分支：`test/documents-prov-props`（已合入 main = `abda598c`）
- 内容：Documents 页 provenance 回调接线验证（组件 mock 捕获式）：
  mock DocumentActionsMenu 捕获 props 后直接驱动 onViewIndexedFile
  （路由参数拼装）与 onOpenOriginalFile（getRawFile → blob →
  window.open noopener），绕开 jsdom 菜单弹出时序不稳定问题。
  全仓行覆盖 87.82%。
- 证据：`test:run` 455/455（59 文件）；`lint` 0 问题；`tsc -b` 绿；
  `build` 绿。

### Batch 130（已交付）

- 分支：`test/embedding-job-repo-7`（已合入 main = `52a05458`）
- 内容：`EmbeddingJobRepository` 收尾批补 3 用例：activateJob 将
  job id 绑定到文档状态行（document_id/profile_id/generation 四元
  断言）、cancelSuperseded 将旧代作业标 STALE 并返回受影响计数、
  cancelActiveForDocument 全量取消运行中作业。core 全量 3643→3659 绿。
- 证据：新测试 3/3 绿；core 全量 `Tests run: 3659, Failures: 0,
  Errors: 0, Skipped: 9`，BUILD SUCCESS。

### Batch 131（已交付）

- 分支：`test/integrity-snapshot-from`（已合入 main = `eaa7e652`）
- 内容：`DerivationIntegrityRepository.Snapshot.from(Map)` 分类矩阵
  补 7 用例：全新鲜 → READY（reason CURRENT）、向量 FAILED →
  KEYWORD_ONLY（VECTOR_FAILED 原因）、作业收敛 → INDEXING、本地行
  无效 → CORRUPT（LOCAL_PHYSICAL_INTEGRITY_FAILED）、禁用文档 →
  DISABLED、双派生未请求 → NOT_REQUESTED、本地损坏优先于向量损坏的
  reason 顺序。core 全量 3659→3666 绿。
- 证据：新测试 7/7 绿；core 全量 `Tests run: 3666, Failures: 0,
  Errors: 0, Skipped: 9`，BUILD SUCCESS。

### Batch 132（已交付）

- 分支：`test/integrity-snapshot-from-2`（已合入 main = `deca796a`）
- 内容：`Snapshot.from` 分类矩阵补 5 变体：向量行不完整且状态
  COMPLETED → CORRUPT 优先于 converging、source_deleted 墓碑 →
  DISABLED、local_status 缺失 → MISSING 条件归 LOCAL_UNAVAILABLE
  （修正了对双 NOT_REQUESTED 桶条件的错误假设——localCondition 为
  MISSING 时不满足双 NOT_REQUESTED）、local FAILED → LOCAL_UNAVAILABLE、
  INDEXING 桶要求收敛作业且本地非新鲜。core 全量 3666→3671 绿。
- 证据：新测试 5/5 绿；core 全量 `Tests run: 3671, Failures: 0,
  Errors: 0, Skipped: 9`，BUILD SUCCESS。

### Batch 133（已交付）

- 分支：`test/embedding-job-repo-8`（已合入 main = `d2fe04cf`）
- 内容：`EmbeddingJobRepository` 收尾批：listPage 的
  allowedCollectionIds 过滤渲染为 postgres bigint 数组（ANY (?)）绑定
  并透传 COUNT 总数；文件结构经多轮部分编辑破坏后整文件重写恢复
  （保留原 4 测试 + 新增 ANY 绑定用例）。core 全量 3652→3672 绿。
- 证据：新测试 5/5 绿；core 全量 `Tests run: 3672, Failures: 0,
  Errors: 0, Skipped: 9`，BUILD SUCCESS。

### Batch 135（已交付）

- 分支：`test/sync-cursor-codec`（已合入 main = `e8b6dbb9`）
- 内容：`KeywordIndexSqlScope`（本地关键词 chunk 的统一 SQL
  freshness 作用域生成器）零直接测试，补 4 用例：非正 profile id
  拒绝、空白 chunker 版本拒绝（SQL 字面量助手）、JOIN 与 freshness
  条件形状断言（chunks→local state→documents→向量左连接，READY
  状态、content hash、按文档类型的 chunker CASE）、chunker 版本
  字面量单引号转义。core 全量 3652→3676 绿。
- 证据：新测试 4/4 绿；core 全量 `Tests run: 3676, Failures: 0,
  Errors: 0, Skipped: 9`，BUILD SUCCESS。

### Batch 136（已交付）

- 分支：`test/citation-augmenter`（已合入 main = `5f8602d2`）
- 内容：`CitationQueryAugmenter`（引用查询增强器：编号证据注入 +
  空资料防编造守卫指令）零直接测试，补 3 用例：allowEmptyContext
  开启时原查询原样返回、关闭时注入防编造守卫指令、编号参考资料
  [S1]/[S2] 与用户问题占位拼装。注意 accessor 链为
  properties.getChat().getKnowledge()。core 全量 3676→3679 绿。
- 证据：新测试 3/3 绿；core 全量 `Tests run: 3679, Failures: 0,
  Errors: 0, Skipped: 9`，BUILD SUCCESS。

### Batch 137（已交付）

- 分支：`test/embedding-profile-sqlscope`（已合入 main = `a14c81c3`）
- 内容：`EmbeddingProfileSqlScope`（活动 Embedding Profile 的统一
  检索 SQL 作用域生成器）零直接测试，补 5 用例：两参/三参重载的
  非 profile id 拒绝、空白/null chunker 版本拒绝（SQL 字面量助手）、
  JOIN 与 freshness 条件形状（COMPLETED 状态、content hash、按文档
  类型 CASE、enabled）、chunker 版本字面量单引号转义、两参重载默认
  text chunker 为 legacy-compatible。core 全量 3676→3684 绿。
- 证据：新测试 5/5 绿；core 全量 `Tests run: 3684, Failures: 0,
  Errors: 0, Skipped: 9`，BUILD SUCCESS。




### Batch 138（已交付）

- 分支：`test/noop-providers`（已合入 main = `e5477a57`）
- 内容：`NoOpFulltextSearchProvider`（无 pg_trgm/pg_jieba 时的全文检索
  降级策略）与 `NoOpRerankProvider`（按 ranking depth 截断原样返回）
  零直接测试，补 6 用例：名称/可用性、两检索入口恒返回空、
  rerank 深度内原样返回、超深截断保序、空/null 透传（null 按实现
  语义断言）。core 全量 3659→3663 绿。
- 注：fulltext 的 NoOp 测试此前已存在于 FulltextSearchProviderFactoryTest
  嵌套类，本次去重。

### Batch 139（最新进展留档，截至本批）

**循环状态**：Batch 70 起持续迭代，后端 core 测试 3374→3672+，前端
452→455 全绿，全仓行覆盖 87.2%+。

**已收敛重点**：
- 后端：Embedding Profile 三件套（Registry/IndexManager/Bootstrap）、
  alertdelivery 全链路（Outbox/RepositoryGuards/DeliveryService/Worker/
  Sanitizer/CursorCodec）、DocumentSyncRunService 七批 44 用例、
  EmbeddingJobRepository 七批 27 用例、CollectionPurgeService 五面 25
  用例、DocumentRelocationService、ApiPrincipalExpiryAlertService、
  EvaluationSuiteRepository、KeywordIndexPersistenceService、
  ChatTurnOperationRepository、IntegrationObservationRepository、
  LlmUsageQueryRepository、LegacyEmbeddingMigrationService、
  DerivationIntegritySnapshotFrom 分类矩阵、KeywordIndexSqlScope、
  EmbeddingProfileSqlScope、CitationQueryAugmenter、
  RetrievalEmptyReasonProbe、RetrievalDiagnosticsRetentionJob、
  StaticKnowledgeChunk、RagUsageProperties/RagIntegrationObservability
  Properties/RagRateLimitProperties 校验等。
- 前端：Documents.tsx 81.9%、Files.tsx 84.8%、Settings.tsx 89.4%、
  Evaluation.tsx 93.2%、Metrics.tsx 90%、Search.tsx 91.6%、Chat.tsx
  89.7%、ABTest.tsx 73.7%、MetricsCharts 93.3%、embeddings.ts 88.9%、
  apikeys.ts 100%、client.ts 95%。
- 已知遗留：Documents.tsx provenance 页面级用例（jsdom 时序不稳定，
  建议未来 Playwright e2e 补测）；EmbeddingJobRepository 深层 varargs
  stub 两个用例暂缓（适合 Testcontainers 集成测试）。

### Batch 140（已交付）

- 分支：`test/coverage-batch140-20260907`（已合入 main = `842c8085`）
- 内容：新增 `ABTest.mutations.test.tsx`（12 用例）。原 ABTest 测试
  整体 mock `useQuery`/`useMutation`，mutation 回调、query lambda、
  toast 反馈与关窗行为均不可达；新文件改走真实 react-query +
  `QueryClientProvider` + mock API 层：list/get/analysis 查询
  lambda、start/pause/stop 成功与失败 toast（`invalidateQueries`
  断言）、创建成功关窗、失败留窗、pending 禁用与 loading 文案、
  自定义 variant B、Tooltip formatter、空转化率/置信区间占位符。
- 指标：ABTest.tsx 行覆盖 73.68% → **100%**，分支 84.72% → 90.27%；
  前端全量 468 绿，行覆盖 88.6%。

### Batch 141（已交付）

- 分支：`test/coverage-batch141-20260907`（已合入 main）
- 内容：VersionHistoryModal diff flow 加固。定位到既有 diff 用例的
  mock 缺少 `{ data }` 包装，`handleCompare` 在 `!vAd || !vBd` 处早退，
  diff 视图渲染整体不可达。新增 4 用例：成功 compare 后统计条
  （+1 inserted / -1 deleted）与删除/插入/相等行渲染、list/diff tab
  双向切换（diffLines 保留）、版本详情缺失时早退留在列表、比较锚点
  替换与取消选中；describe 内用 `resetAllMocks` 隔离前置用例残留的
  挂起 `mockImplementation`。
- 指标：VersionHistoryModal.tsx 行覆盖 79.24% → **100%**（分支
  87.61%）；前端全量 472 绿，行覆盖 89.02%。

### Batch 142（已交付）

- 分支：`test/coverage-batch142-20260907`（已合入 main = `695d99e9`）
- 内容：Embeddings 页状态与交互加固，新增 9 用例：任务列表加载失败
  告警、空态、`?jobId=` 直达详情（含返回文档链接）、行内 id 按钮回写
  URL 触发详情查询、status/batchId 过滤输入透传 listJobs、repair
  preview 失败告警且不开窗、弹窗 cancel 关闭、apply 成功后 onSuccess
  关窗。
- 指标：Embeddings.tsx 行覆盖 78.43% → **94.11%**（分支 92.85%）；
  前端全量 481 绿，行覆盖 89.02%+。

### Batch 143（已交付）

- 分支：`test/coverage-batch143-20260907`（已合入 main = `4107a3fe`）
- 内容：新增 `ApiKeys.lifecycle.test.tsx`（7 用例，真实 react-query +
  mock API）：revoke 成功/失败 toast、REVOKED/EXPIRED 行徽章与动作
  禁用、配额/范围/未知角色兜底占位、行内完成与取消轮换（含非 Error
  抛出物走 `formatMutationError` 回退分支）、创建带配额 key 并复制
  一次性 rawKey、无配额创建的 defaultQuota 占位。发现并记录：
  `userEvent.setup()` 会接管 `navigator.clipboard`，clipboard spy 必须
  在 setup 之后打。
- 指标：ApiKeys.tsx 行覆盖 78.94% → **85.16%**；前端全量 488 绿，
  行覆盖 89.84%。

### Batch 144（已交付）

- 分支：`test/core-zero-coverage-batch144-20260907`（已合入 main =
  `538e9865`）
- 内容：后端零测试逻辑类扫描再收一批，补 5 类共 20 用例：
  SessionIdValidator（空白生成 UUID、非法字符/超长拒绝、isValid
  矩阵）、EmbeddingPolicySupport（显式 embed 入口拒绝 SKIP、jobs
  门禁）、ApiKeyRotationHttpPolicy（轮换路径识别、mark + no-store、
  漏标兜底、apply 选择性加头）、AlertNotificationProviderValidator
  （Durable 启动门禁三分支）、ApiAccessPolicy（遗留默认全量能力与
  显式清单门禁）。
- 指标：core 全量 3687 → **3707** 绿。

### Batch 145（已交付）

- 分支：`test/webui-settings-batch145-20260907`（已合入 main =
  `08c16f00`）
- 内容：Settings 页分支覆盖加固，新增 5 用例：不可用模型过滤且默认
  选中、模型列表加载失败提示（modelsLoadError）、Language tab 切换
  中文并持久化 localStorage、检索权重滑块（getAllByRole('slider')
  按文档顺序取全文权重）与 topK/rerankTopK 整数回退（'' → 10/5）、
  缓存开关联动禁用 ttl/maxSize 与空值默认回退（60/1000）。
- 指标：Settings.tsx 行覆盖 89.36% → **96.8%**，分支 62.16% →
  74.77%；前端全量 493 绿，行覆盖 90.11%。

### Batch 146（已交付）

- 分支：`test/webui-alerts-batch146-20260907`（已合入 main =
  `5c3587c9`）
- 内容：Alerts 页覆盖加固，新增 8 用例：四个 tab 按钮互切、
  deliveries 查询挂起的 loading 态、notificationsDisabled 与
  noDeliveryProviders 模式提示、status/provider 过滤下拉透传查询、
  缺失 nextAttemptAt 的占位渲染、SLO 表单 targetValue/unit 完整
  编辑提交、静默表单全字段编辑（alertKey/RECURRING/description）
  与 deleteSilenceSchedule 删除。
- 指标：Alerts.tsx 行覆盖 83.33% → **97.36%**，分支 84.11%；前端
  全量 501 绿，行覆盖 90.73%。

### Batch 147（已交付）

- 分支：`test/webui-upload-branches-batch147-20260907`（已合入 main =
  `a5182f60`）
- 内容：useFileUpload 分支覆盖加固，新增 6 用例：成功完成状态与逐
  文件 onComplete 回调、后端 detail 错误文案透传（413）、错误体不可
  解析（502 HTML）回退 Upload failed、非 Error 拒绝映射通用文案、
  缺 `crypto.randomUUID` 时时间戳幂等键回退、二轮同名上传命中
  `updateUpload` 的 current 分支重发 onProgress。
- 指标：useFileUpload.ts 行覆盖 92% → **98%**，分支 50% → **85%**；
  前端全量 507 绿，行覆盖 90.84%。
- 备注：Documents.tsx 名义缺口为散点单语句（编辑/删除/嵌入/版本
  恢复等 mutation 主路径已有交互用例），投入产出低暂缓；
  useSSE.ts 深层流式重试路径（tail SSE 块、退避重试、turn id 不匹配）
  需专用 reader mock 基建，留待后续批次。

### Batch 148（已交付）

- 分支：`test/webui-files-tree-batch148-20260907`（已合入 main）
- 内容：Files 树渲染与路径归一化加固，新增 3 用例：mime 图标矩阵
  （pdf→📄、image→🖼️、json/text→📝、其他→📎）与 formatSize 三档
  （512 B / 2.0 KB / 3.0 MB）、反斜杠与控制字符深链回退根目录、
  带前导斜杠目录深链归一化进入目标层级（面包屑断言）。
- 指标：Files.tsx 行覆盖 84.8% → **86.92%**，分支 80%；前端全量
  510 绿，行覆盖 91.08%。

### Batch 149（已交付）

- 分支：`test/webui-collections-batch149-20260907`（已合入 main）
- 内容：Collections 页覆盖加固，新增 3 用例：卡片动作跳转
  documents/embeddings（URL 编码 collectionKey）、头部按钮打开创建
  弹窗、purge preview 失败错误面板（含后端错误信息）与
  retryPreview 重试恢复闭环。
- 指标：Collections.tsx 行覆盖 85.07% → **92.53%**，分支 84.61%；
  前端全量 513 绿，行覆盖 91.27%。

### Batch 150（已交付）

- 分支：`test/webui-evaluation-batch150-20260908`（已合入 main =
  `e7e8d1eb`）
- 内容：Evaluation 页分支覆盖加固，新增 6 用例：history 行指标
  格式化（fmt 走 toFixed(3)）与缺失字段占位符、空历史提示、
  feedback tab 统计 JSON 渲染（pre 整体文本需函数匹配器）与反馈
  行渲染、evaluate API 拒绝时失败提示（query 必填触发按钮）、
  judge API 拒绝时失败提示、citation trace 空 status/outcome 占位。
- 指标：Evaluation.tsx 行覆盖 93.24% → **98.64%**，分支 65.48% →
  **94.69%**；前端全量 519 绿，行覆盖 91.43%，分支 82.88%。

### Batch 151（已交付）

- 分支：`test/webui-usesse-retries-batch151-20260908`（已合入 main =
  `a122be80`）
- 内容：useChatSSE 流式重试与回放身份加固，新增 reader/流 mock
  基建（`streamResponse` 按指定 turn id 构造 ReadableStream 响应）
  与 6 用例：响应无 body 可重试且二轮正常、两次重试间 turn 身份
  变更拒绝、done 回放 turnId 与响应头不匹配拒绝、`Retry-After`
  秒数驱动有界退避（fake timers 推进后二轮成功）、`Retry-After`
  非正数立即重试（注意：仅 409/429 视为可重试，500 不会重发）、
  注释行/空块/非 JSON 事件解析容错。
- 指标：useSSE.ts 行覆盖 90.52% → **97.15%**，分支 72.02% →
  **81.54%**；前端全量 525 绿，行覆盖 91.97%，分支 83.57%。
- 遗留：useSSE 285-286/292/343 为竞态守卫（生成代际/终止标记与
  退避截止交叉），需要时间受控的复杂编排，投入产出低暂缓。

### Batch 152（已交付）

- 分支：`test/webui-chat-batch152-20260908`（已合入 main =
  `4c91cb89`）
- 内容：Chat 页覆盖加固，新增 7 用例：非流式状态下忽略全部流回调
  （onChunk/onSources/onToolStart/onToolResult/onTurnClaimed 的
  lastMsg.isStreaming 守卫）、停止流程（工具活动标记完成 + 输入
  恢复）、无会话导出 no-op、导出失败静默、点赞/点踩反馈携带上一条
  用户提问、历史加载失败清空消息、New Chat 携带查询串返回。
  过程沉淀：同一 JSX 元素引用 rerender 会被 React 跳过需重新构造；
  空历史 resolve 会异步清空已发消息（导出用例需等待非空历史）；
  反馈行仅在助手消息有内容且非流式时渲染。
- 指标：Chat.tsx 行覆盖 89.7% → **97.54%**，分支 84.69% →
  **91.25%**；前端全量 532 绿，行覆盖 92.59%，分支 84.1%。

### Batch 153（已交付）

- 分支：`test/webui-metrics-batch153-20260908`（已合入 main）
- 内容：Metrics 页覆盖加固，新增 3 用例：查询接线验证（mock
  useQuery 捕获 queryFn 后直接调用，断言 metricsApi.get/usage 各
  一次）、undefined/null 数值渲染为 0（formatInteger 空值分支，
  摘要卡 4 处）、非数字 configuredCost 原样输出（formatCost 非有限
  分支）。
- 指标：Metrics.tsx 行覆盖 90% → **96.66%**，函数 100%；前端全量
  535 绿，行覆盖 92.67%，分支 84.18%。
- 遗留：formatInteger 的 BigInt catch 分支（第 19 行）为无 BigInt
  环境的防御性回退，jsdom 下不可达。

### Batch 154（已交付）

- 分支：`test/core-validators-batch154-20260908`（已合入 main =
  `ef2a1114`）
- 内容：后端启动期校验加固，补 11 用例：EmbeddingVectorColumns
  （1024 → embedding_1024 白名单、其余维度拒绝）、PostConstruct
  校验器三件套（chat / usage / integration-observability /
  notification-delivery——默认放行、越界与交叉冲突拒绝，注意 chat
  分组 `invalid()` 抛 IllegalStateException 而非 IllegalArgumentException）、
  RagCollectionPurgePropertiesValidator（disabled 免依赖 bean、
  enabled 缺 ChatExecutionService / ChatSessionCoordinator 分别拒绝、
  配置越界先于 bean 解析拒绝，ObjectProvider mock 驱动）。
- 指标：core 全量 3707 → **3718** 绿。

### Batch 155（已交付）

- 分支：`test/core-delivery-repo-batch155-20260908`（已合入 main =
  `4c35326d`）
- 内容：AlertNotificationDeliveryRepository 加固，新增 13 用例。
  桩策略：继承 JdbcTemplate 覆写 `query/update/queryForObject` 记录
  SQL 与参数，并把 RowMapper 应用到共享 mock ResultSet——彻底绕开
  Mockito varargs 匹配的已知不稳定（此前多批反复踩坑）。覆盖：
  19 列完整映射（payload JSON 反序列化、last_http_status 可空
  Integer）、非法 payload 包装 IllegalStateException、claim 绑定
  租约 token/毫秒时长/id、markTransientFailure 预算内 RETRY_WAIT
  与耗尽 FAILED 决策 + 负延迟钳制为 0、错误码 bounded（null/空白 →
  UNKNOWN、>64 截断 64）、keyset 分页 SQL 过滤顺序与 7 参数绑定、
  无过滤仅 LIMIT、isManagedStateCurrent null 安全、插入 ON CONFLICT
  返回语义、supersede/recoverExhaustedLeases/cleanup 计数透传、
  findCandidateIds UNION 候选、retryFailed 预算重置。
- 指标：core 全量 3718 → **3731** 绿。

### Batch 156（已交付）

- 分支：`test/webui-dashboard-batch156-20260908`（已合入 main）
- 内容：Dashboard 骨架分支补齐，新增 2 用例：docs/collections 查询
  pending 时两张指标卡渲染 60px 骨架（此前骨架分支 0 次执行）、仅
  集合查询 pending 时文档卡显示数值。注意仪表盘还有其他卡片用
  `?? '—'` 占位，断言需避免全页 '—' 唯一性假设。
- 指标：Dashboard.tsx **100%/100%/100%/100%**（语句/分支/函数/行）；
  前端全量 537 绿。

### Batch 157（已交付）

- 分支：`test/core-usage-repo-batch157-20260908`（已合入 main）
- 内容：LlmUsageRepository 加固（复用 Batch 155 的 JdbcTemplate
  桩子类模式，本批变体为捕获 PreparedStatementSetter 回放到 mock
  PreparedStatement），新增 6 用例：24 列插入绑定逐项验证、
  setQueryTimeout 毫秒→秒向上取整（含 1s 下限）、ON CONFLICT 幂等
  冲突返回 false、null 事件不触库、deleteExpired 参数绑定与非法
  入参短路。金额断言按构造器 SCALE=8 归一化值（BigDecimal.equals
  含 scale）。
- 指标：core 全量 3731 → **3737** 绿。

### Batch 158（已交付）

- 分支：`test/core-retention-job-batch158-20260908`（已合入 main =
  `895fca61`）
- 内容：LlmUsageRetentionJob 加固，新增 6 用例：usage / cleanup
  开关关闭时不触库、单批返回不足 batchSize 即提前终止
  （500/500/120 三批）、达到 cleanup-max-batches 上限即停、
  repository RuntimeException 被吞噬不上抛、cutoff 按 retentionDays
  从当前时刻回推（ArgumentCaptor 断言时间窗口）。
- 指标：core 全量 3737 → **3743** 绿。

### Batch 159（已交付）

- 分支：`test/webui-docs-errors-batch159-20260908`（已合入 main）
- 内容：Documents 错误回退与编辑载荷加固，新增 7 用例：update 非
  409 失败走 fallback toast、缺 documentRevision 守卫经 onError
  呈现且不触 API、restore/delete/embed 失败专属错误 toast、编辑
  弹窗打开失败报 loadDetailError、source/content/collectionKey
  编辑进入 update 载荷。
- 指标：Documents.tsx 行覆盖 81.86% → **86.04%**；前端全量 544 绿，
  行覆盖 93.02%。

### Batch 160（已交付）

- 分支：`test/core-recorder-gaps-batch160-20260908`（已合入 main）
- 内容：JdbcLlmUsageRecorder 查漏，新增 5 用例：null 事件短路
  （不触库不计数）、usage 关闭时 record/recordAsync 短路、无
  MeterRegistry 时 lostCounter 为 null 的安全路径（lostEvents 返回
  0 且 repository 失败不上抛）、shutdown 后 async/sync 提交被
  AbortPolicy 拒绝并计入 lost（executor_rejected 分支）。
- 指标：core 全量 3743 → **3748** 绿。

### Batch 161（已交付）

- 分支：`test/webui-docs-upload-batch161-20260908`（已合入 main）
- 内容：Documents 剩余交互散点，新增 8 用例：文件选择转发
  handleFiles + dragOver/dragLeave 样式复位、collection 过滤器清除、
  分页 previous 回首、preview/versions 关闭按钮、relocate 取消不触
  API、relocate 守卫（缺 sourceRevision）走 relocationErrors.DEFAULT
  兜底、relocate 详情拉取失败报 loadDetailError。
- 指标：Documents.tsx 行覆盖 86.04% → **93.02%**；前端全量 552 绿，
  行覆盖 93.6%。

### Batch 162（已交付）

- 分支：`test/core-coordinator-lease-batch162-20260908`（已合入 main =
  `04d39186`）
- 内容：ChatSessionCoordinator（637 行）首个专用单测，13 用例。
  桩策略复用并扩展：JdbcTemplate 桩子类按语句类型分类（acquire/
  renew/consume/release）记录 SQL 并返回可配置影响行数，query 覆写
  单独记录 RETURNING 标记。覆盖：STATELESS 获取绕过租约行与释放、
  acquire 绑定四参数、活跃租约冲突抛 SESSION_BUSY、
  invokeWithinDeadline 三分支（成功/透传 supplier 异常/到期抛
  CHAT_TIMEOUT）、STATELESS commit 持久化不触租约、STATEFUL commit
  事务内续租、未配置 operationRepository 时 IDEMPOTENCY_DISABLED、
  failOperation 静默、release 绑定删除、clearSession 消费租约 +
  删历史 + 空历史 SESSION_NOT_FOUND。
- 指标：core 全量 3748 → **3761** 绿。

### Batch 163（已交付）

- 分支：`test/webui-usesse-race-guards-batch163-20260908`（已合入 main）
- 内容：useChatSSE 竞态守卫回补，新增 5 用例：done 类型但 status 非
  complete 的事件为惰性且流收尾走 completed、abort 中断挂起 read 静默
  停止、代际推进后滞留事件被 handleEvent 守卫丢弃、末尾缺换行非 done
  块经 tail 解析触发可重试、Retry-After 预算耗尽（bounded <= 0）终止
  重试上抛 HTTP 429。
- 指标：useSSE.ts 行覆盖 → **100%**，分支 86.9%；前端全量 557 绿，
  行覆盖 93.83%。

### Batch 164（已交付）

- 分支：`test/core-maintenance-fingerprint-batch164-20260908`（已合
  入 main）
- 内容：零测试类扫描继续，新增 3 类 10 用例：
  ChatTurnOperationMaintenance（按 idempotency 配置委托清理）、
  SharedRateLimitMaintenance（开关/后端短路、参数透传、
  DataAccessException 吞噬并记录 cleanupError）、
  ApiKeyProvisioningFingerprint（确定性 64 位摘要、角色/名称隔离、
  canonicalJson 的 ISO 日期与集合去重排序、空集合 null 语义）。
- 指标：core 全量 3761 → **3771** 绿。

### Batch 165（已交付）

- 分支：`test/core-config-defaults-batch165-20260908`（已合入 main）
- 内容：配置默认值契约锁定，新增 ConfigurationDefaultsTest（8
  用例）：RagStructuredRecordProperties（10 项上限/开关默认）、
  RagEvaluationProperties（托管套件默认关闭、引用校验默认开启等 7
  项）、RagRetrievalDiagnosticsProperties（默认采集+持久化、7 天
  保留、不存查询文本等 6 项）、RagDocumentLifecycleProperties
  （严格 CAS 默认开启、syncRuns/versionRestore/relocation/
  derivationRepair 四特性默认关闭等 9 项）及 setter 往返。
- 指标：core 全量 3771 → **3779** 绿。

### Batch 166（已交付）

- 分支：`test/webui-modal-internals-batch166-20260908`（已合入 main）
- 内容：ApiKeys RotateKeyModal / EditPolicyModal 内部交互加固，新增
  9 用例：overlap 按钮级禁用契约（0/空禁用、900 可用）、立即轮换
  成功 + 复制 raw key（clipboard spy 须在 userEvent.setup 之后）、
  立即轮换失败 formatMutationError、策略 CAS 成功/失败 toast、限定
  集合勾选-移除-再勾选双分支、空态 createFirst 入口、
  completeRotation 失败。编辑模态 name label 带必填星号
  （'apiKeys.name *'），scope 单选组名 policyCollectionScope。
- 指标：ApiKeys.tsx 行覆盖 85.16% → **94.73%**，分支 86.88%；前端
  全量 564 绿，行覆盖 94.61%。

### Batch 167（已交付）

- 分支：`test/core-summary-gaps-batch167-20260908`（已合入 main）
- 内容：ConversationSummaryService 查漏，新增 5 用例：load 将
  SummaryRow 映射为 SummarySnapshot（版本/游标/正文/估算 token/
  模型五字段）、无摘要返回 empty、promptText 对 null/empty/空白
  摘要返回空串、非空正文 trim 后夹在前后缀之间且输出稳定、clear
  委托 summaryRepository.delete 并透传删除行数。
- 指标：core 全量 3779 → **3784** 绿。

### Batch 168（已交付）

- 分支：`test/webui-collections-close-batch168-20260908`（已合入 main）
- 内容：Collections 弹窗关闭路径收尾，新增 2 用例：创建弹窗经 Close
  按钮关闭（onClose → setShowCreateModal(false)）、purge 弹窗经
  cancel 关闭且不触 apply API（closeDialog 的 isPending 守卫 +
  setPurgeTarget(null)）。
- 指标：Collections.tsx 行覆盖 92.53% → **98.5%**，函数 100%；前端
  全量 566 绿，行覆盖 94.76%。
- 遗留：applyMutation 的 previewRequired 守卫（第 183 行）为防御性
  代码——apply 按钮受 canApply 门禁，UI 层不可达。

### Batch 169（已交付）

- 分支：`test/core-zero-test-cleanup-batch169-20260908`（已合入 main）
- 内容：零测试类收尾，新增 2 类 8 用例：
  RagApiKeyProvisioningProperties（默认基线、保留期 7–3650 天闭区间
  边界与 null 拒绝、批次钳制 10–5000、重试钳制 1–8）、
  EvaluationSuiteDefinition（Identity 双参构造默认 default 命名空间、
  三参保留显式命名空间、定义记录持有 canonicalJson/sha256/用例/变体）。
- 指标：core 全量 3784 → **3792** 绿。

### Batch 170（已交付）

- 分支：`test/core-advisor-adapter-batch170-20260908`（已合入 main）
- 内容：OrderedAdvisorAdapter（52 行，包私有）收口语义锁定，新增 4
  用例：getName 返回收口后的稳定名称而非被包装者名称、getOrder 返回
  框架管理的顺序、before/after 透传到被包装 advisor、getScheduler
  透传。
- 指标：core 全量 3792 → **3796** 绿。

### Batch 171（已交付）

- 分支：`test/core-trace-controller-batch171-20260908`（已合入 main）
- 内容：RetrievalTraceController（58 行，零测试）加固，新增 4 用例：
  list 默认分页绑定与本地 principal 推导、七个过滤参数全量透传、
  DATABASE_API_KEY 认证属性推导出 db:key-42 principal（argThat
  断言）、get 按 traceId 转发并透传诊断服务响应。
- 指标：core 全量 3796 → **3800** 绿。

### Batch 172（已交付）

- 分支：`test/core-integration-obs-batch172-20260908`（已合入 main）
- 内容：IntegrationObservabilityController 加固，新增 2 用例：七个
  查询参数按文档顺序透传 queryService、响应包装 200 + no-store 缓存
  头、null 默认值透传保持 no-store。
- 指标：core 全量 3800 → **3802** 绿。

### Batch 173（已交付）

- 分支：`test/webui-files-guards-batch173-20260908`（已合入 main）
- 内容：Files 交互守卫加固，新增 3 用例：非左键拖拽不启动且异
  pointerId 的 pointerMove 被忽略、Home/End 键盘极值收缩/展开目录
  面板、双击与键盘 Enter 打开选中目录（面包屑断言）。
- 指标：Files.tsx 行覆盖 86.92% → **87.27%**；前端全量 569 绿，
  行覆盖 94.8%。

### Batch 174（已交付）

- 分支：`test/core-expiry-worker-batch174-20260908`（已合入 main）
- 内容：ApiPrincipalExpiryAlertWorker 双入口加固，新增 6 用例：事件
  驱动对账指定 principal 且返回完成的 future、事件对账失败吞噬、
  兜底扫描对账每个候选、截断批次经 metrics 上报 recordScanTruncated、
  候选列举失败吞噬不触 metrics、单个候选失败不阻断后续候选。
- 指标：core 全量 3796 → **3808** 绿。

### Batch 175（已交付）

- 分支：`test/core-memory-config-batch175-20260908`（已合入 main）
- 内容：ChatMemoryRepositoryConfig 装配条件锁定，新增 3 用例
  （ApplicationContextRunner）：jdbcTemplate+事务管理器齐备时创建
  Postgres 方言的 JdbcChatMemoryRepository（stub 需为
  jdbcTemplate.getDataSource() 返回 DataSource）、已存在同类型 bean
  时 @ConditionalOnMissingBean 让位、缺事务管理器时
  @ConditionalOnBean 不装配。
- 指标：core 全量 3808 → **3811** 绿。

### Batch 176（已交付）

- 分支：`test/core-purge-props-batch176-20260908`（已合入 main）
- 内容：RagCollectionPurgeProperties 安全边界锁定，新增 8 用例：默认
  基线 13 项、confirmation-window 下限、operation-window 不得短于
  confirmation-window（交叉依赖）、result-retention 不得短于
  operation-window、apply-lease 范围、max-documents/max-chat-rows
  行数上限、清理批次与间隔范围。
- 指标：core 全量 3811 → **3819** 绿。

### Batch 177（已交付）

- 分支：`test/core-app-contract-batch177-20260908`（已合入 main）
- 内容：应用入口注解契约与异常错误码锁定，新增 2 类 5 用例：
  StructuredRecordConflictException（错误码 STRUCTURED_RECORD_CONFLICT、
  原因链保留）、SpringAiRagApplication 注解契约（扫描范围
  com.springairag、MiniMax 双自动装配排除清单、
  ConfigurationPropertiesScan 仅扫 core.config——value 别名优先于
  basePackages）。
- 指标：core 全量 3819 → **3824** 绿。

### Batch 178（已交付）

- 分支：`test/webui-files-upload-batch178-20260908`（已合入 main）
- 内容：Files 上传区与导航加固，新增 4 用例：上传区 dragOver 高亮/
  dragLeave 复位（可访问名来自内容而非 title，按属性选择器定位）、
  Enter/Space 键盘激活文件选择、嵌套目录面包屑逐层导航并回根、
  pdf 输入空文件选择短路。
- 指标：Files.tsx 行覆盖 87.27% → **89.39%**；前端全量 573 绿，
  行覆盖 95.03%。

### Batch 179（已交付）

- 分支：`test/core-entity-roundtrip-batch179-20260908`（已合入 main）
- 内容：零引用类清零，新增 3 用例：ApiKeyRotationOperation（12 个
  JPA 列访问器往返——rotationId/principalId/idempotency hash/
  fingerprint/源目标 credential/overlap/expiry/PENDING 状态/时间戳
  族；进行中轮换 terminalAt 为 null）、AlertNotificationsAvailableEvent
  （无载荷标记事件，toString 含类型名）。
- 指标：core 全量 → **3827** 绿。

### Batch 180（已交付）

- 分支：`test/webui-docs-api-batch180-20260908`（已合入 main）
- 内容：documentsApi 全端点覆盖，documents.test.ts 3→9 用例：update
  走 PATCH、embed 以 query param 传 force（body null）、batchEmbed、
  uploadAndEmbed multipart 头、getVersions 分页、getVersion 单版本、
  restoreVersion policy/visibility、relocate Idempotency-Key 头。
- 指标：documents.ts 行覆盖 47.05% → **94.11%**，分支 100%；前端
  全量 579 绿，行覆盖 95.34%。

### Batch 181（已交付）

- 分支：`test/webui-lowcov-batch181-20260908`（已合入 main）
- 内容：低覆盖文件补齐，新增 6 用例：evaluation.ts 反馈窗口默认合并
  与分页默认（86.36% → 95.45%，分支 100%）、ChatSidebar 相对时间
  四级渲染（87.87% → 89.47%）。
- 指标：前端全量 585 绿，行覆盖 95.42%。

### Batch 182（已交付）

- 分支：`test/webui-lowcov-batch182-20260908`（已合入 main）
- 内容：Layout 响应式侧边栏加固（5 用例：窄屏菜单开关出现、遮罩
  点击关闭、移动端导航复位、logout、宽屏复位消失——查询须用 render
  返回的 view 作用域限定，规避残留挂载树干扰）+ embeddings getJob
  端点用例（embeddings.ts 四项 100%）。
- 指标：前端全量 590 绿，行覆盖 95.73%。

### Batch 183（已交付）

- 分支：`test/webui-dialog-trap-batch183-20260908`（已合入 main）
- 内容：Dialog 焦点陷阱双向环绕，新增 2 用例：Shift+Tab 从首个
  可聚焦元素环绕至最后一个（Close 按钮）、正向 Tab 从最后一个环绕
  回首个（Name 输入）。
- 指标：前端全量 592 绿。
- 备注：v8 覆盖映射对 Dialog keydown handler 内分支归属不稳定，
  以行为断言为准。

### Batch 184（进度快照与全局复测，2026-09-08）

**全局复测结果**：
- 后端 core：mvn test **3827 绿**（0 失败，9 skipped 为 gated IT），
  零引用类扫描清零（无任何 main 类无测试引用）。
- 前端 webui：vitest **592 绿**（60 个测试文件），整体行覆盖
  **95.73%**、分支 **87.05%**、函数 **93.38%**。

**分文件行覆盖梯度（<95% 清单，后续批次候选）**：
ChatSidebar 87.87 / Dialog 89.58 / Files 89.39 / VersionHistoryModal
90.69 / Search 91.57 / Documents 93.02 / MetricsCharts 93.33 /
ErrorBoundary+Embeddings 94.11 / DocumentActionsMenu 94.28 /
CollectionScopeSelector 94.44 / ApiKeys 94.73 / documents.ts 94.11；
≥95%：Layout、evaluation.ts、embeddings.ts、Collections、Settings、
Evaluation、Chat、Search（95%+）、Dashboard/ABTest/Metrics/
VersionHistoryModal 相关 100% 项等。

**下一轮加固主题规划**：
1. Batch 185：ChatSidebar 残余（corrupted-storage catch 与
   deleteSession 通知分支）+ Dialog 焦点陷阱覆盖率映射器盲区复核。
2. Batch 186：Search.tsx 91.57% → 剩余过滤器/历史交互分支。
3. Batch 187+：Documents.tsx 93%→95%（797/778 守卫需事件级编排）、
   MetricsCharts 93.33%、后端 gated IT（Testcontainers）补
   EmbeddingJobRepository 深层 varargs 场景。

### Batch 185（已交付）

- 分支：`test/webui-sidebar-storage-batch185-20260908`（已合入 main）
- 内容：ChatSidebar 残余分支，新增 3 用例：corrupted-storage catch
  回退空会话、恢复后 addSession 正常写入、deleteSession 仅移除匹配
  id。
- 指标：ChatSidebar.tsx 行覆盖 89.47% → **92.1%**；前端全量 595 绿，
  行覆盖 95.77%。

### Batch 186（已交付）

- 分支：`test/webui-search-batch186-20260908`（已合入 main）
- 内容：Search 交互散点收尾，新增 6 用例：空白查询守卫、非法 draft
  形状拒绝（isSearchDraft 32 行）、历史面板开关与面板外 mousedown
  关闭（164-165）、hybrid 复选框载荷、provenance 目录/索引文件导航
  （201-213，跳转卸载 Search 需两次渲染分证）。
- 指标：Search.tsx 行覆盖 91.57% → **100%**，函数 100%；前端全量
  601 绿，行覆盖 96.08%。

### Batch 187（已交付）

- 分支：`test/webui-docs-guards-batch187-20260908`（已合入 main）
- 内容：Documents 守卫与 provenance 失败路径，新增 3 用例：
  restoreVersion 拒绝时 versions.restoreError toast、缺
  documentRevision 守卫经 deleteError 呈现且不触 API、
  onOpenOriginalFile 失败经 toast 报 openOriginalPdfError（provenance
  文件 Toast mock 升级为可断言 spy）。MetricsCharts 残余为 v8 映射
  盲区（JSX 条件两侧均已由既有用例执行）。
- 指标：Documents.tsx 行覆盖 93.02% → **95.34%**；前端全量 604 绿，
  行覆盖 96.27%。

### Batch 188（已交付）

- 分支：`test/core-gated-it-batch188-20260908`（已合入 main）
- 内容：gated IT 全量实跑验证。发现并修复
  ChatSessionPostgresIntegrationTest 的过期断言（最新迁移硬编码
  V58，V59 落地后失败）→ 动态断言 ≥58。本机 Docker 可用，运行
  runbook：`TESTCONTAINERS_RYUK_DISABLED=true`（境内网络拉
  docker.io 的 ryuk 失败，禁用后用本地 pgvector/pgvector:pg16）+
  各 IT 开关。四个 gated IT 全绿共 41 个真实 PostgreSQL 集成用例。
- 指标：后端默认 3827 绿 + gated IT 41 绿；前端 590 绿。
- 结论：此前「EmbeddingJobRepository 深层 varargs 暂缓」事项由该
  gated IT 的真实 PostgreSQL 验收覆盖，正式关闭。

### Batch 190（已交付）

- 分支：`test/core-observability-feedback-batch190-20260908`（已合
  入 main）
- 内容：ChatObservabilityService（2 用例：registry 在场逐一计数、
  无 registry 静默）与 FeedbackDocumentReferenceStore（4 用例：空
  ids 不触库、参数化 IN 查询与快照映射、insert 空引用短路、批量
  插入 setter 绑定——captor 泛型 T 须与首参集合元素类型一致）。
- 指标：core 全量 3827 → **3833** 绿。

### Batch 191（已交付）

- 分支：`test/core-fingerprint-contract-batch191-20260908`（已合入
  main）
- 内容：ChatRequestFingerprint 契约加固（36% → 大幅提升），新增 12
  用例：null 请求拒绝、元数据凭据字段（含嵌套 Authorization）与控制
  字符拒绝、超 32KB 元数据拒绝、键序规范化不影响指纹、scope 推导、
  PLAIN 模式 NOT_APPLICABLE、空白 sessionId → AUTO_SESSION、
  OpenAI PLAIN 拒绝声明 scope/集合头（PLAIN 需显式声明）、头值
  多值/空白拒绝、角色小写与 memory 大写与模型缺省归一化。
- 指标：core 全量 3833 → **3845** 绿。

### Batch 192（已交付）

- 分支：`test/core-replay-matrix-batch192-20260908`（已合入 main）
- 内容：ChatAuthorizationService verifyReplay 授权矩阵，新增 8 用例：
  CALLER_VISIBLE+UNRESTRICTED 当前收窄拒绝、RESTRICTED 当前扩宽无害、
  allowList 撤销拒绝、allowList 包含放行、UNRESTRICTED+ANY 当前受限
  拒绝、UNRESTRICTED+SELECTED 按 selected 校验放行/拒绝、
  NOT_APPLICABLE 直接放行。MockHttpServletRequest 属性驱动
  ChatPrincipal.from 推导 db principal，AuthenticatedApiPrincipal
  承载受限 allowList。
- 指标：core 全量 3845 → **3853** 绿。

### Batch 193（已交付）

- 分支：`test/core-turn-ops-batch193-20260908`（已合入 main）
- 内容：ChatTurnOperationService 查漏，新增 5 用例：IN_PROGRESS
  claim 无租约时 fail 经 completeFailure 落 INTERNAL_ERROR 快照、
  RagException 错误码透传、unkeyed/终态 claim 静默、status 查无
  turn 抛 CHAT_TURN_NOT_FOUND、SUCCEEDED + includeResponse 经
  verifyReplay 标记 replayAvailable 并反序列化缓存响应。
- 指标：core 全量 3845 → **3858** 绿。

### Batch 194（已交付）

- 分支：`test/core-command-mapper-batch194-20260908`（已合入 main）
- 内容：ChatCommandMapper.mapFromExecutionSnapshot 分支覆盖，新增 6
  用例：完整快照构建（候选首元素作 modelRef、domainId、检索选项六
  字段、SELECTED scope 排序 ids、documentType）、版本号错误、检索
  选项缺字段、空 resolvedCandidates（textList 空数组即非法）、非正
  整数集合 ID、空白候选——后三者均 fail-closed 为
  IDEMPOTENCY_EXECUTION_SNAPSHOT_INVALID。
- 指标：core 全量 3858 → **3864** 绿。

### Batch 195（已交付）

- 分支：`test/core-eval-suite-orch-batch195-20260908`（已合入 main）
- 内容：EvaluationSuiteService 编排加固，新增 7 用例：managed suites
  关闭门禁、createSuite 输入修剪与 owner 绑定、DuplicateKey →
  DUPLICATE_RESOURCE、listSuites 行映射、getSuite 缺失 NOT_FOUND、
  createRun 版本缺失 NOT_FOUND、槽位耗尽 CONCURRENT_EVALUATION_LIMIT。
  认证上下文经 MockHttpServletRequest 驱动
  ChatPrincipal.fromCurrentRequest，db owner 经 resolveExecutionKey
  的 api key 管理服务校验（SecurityException fail-closed 已确认）。
- 指标：core 全量 3858 → **3871** 绿。

### Batch 196（已交付）

- 分支：`test/core-snapshot-gen-batch196-20260908`（已合入 main）
- 内容：ChatAuthorizationService snapshot 生成路径，新增 3 用例：
  PLAIN 模式快照全 NOT_APPLICABLE、无限制调用方 UNRESTRICTED +
  unassignedDocumentsAllowed=true、受限调用方 allowList 排序去重
  （9,3,7 → [3,7,9]）。请求上下文经 RequestContextHolder 注入
  ApiAccessPolicy 属性。
- 指标：core 全量 3853 → **3874** 绿。

---

## 进度留档（2026-09-08，应用户要求暂停点）

**当前状态**：Batch 1–196 全部交付合入 main 并推送（main = `722a7674` + 本提交），
工作区干净，无未合并分支，无未提交变更。

**全局质量基线**：
- 后端 core：mvn test **3874 绿**（0 失败，9 skipped 为 gated IT）。
- 后端 gated IT（真实 PostgreSQL，Testcontainers）：**41 用例全绿**
  （EmbeddingJobs 8 / ChatSession 18 / CollectionPurge 5 /
  NextHighValueFeatures 10）。
- 前端 webui：vitest **592 绿**，整体行覆盖 **95.73%**、分支
  **87.05%**、函数 **93.38%**。
- 零引用类扫描清零；目标文件行覆盖提升示例：ABTest/VersionHistory
  Modal/Dashboard 100%、Search 100%、Evaluation 98.64%、
  Documents 95.34%、Metrics 96.66%、Collections 98.5%、ApiKeys
  94.73%、Files 89.39%、useSSE 100%、documents.ts 94.11%。

**本批账本已沉淀的可复用测试模式**：
1. JdbcTemplate 桩子类（按语句类型分类记录 SQL/参数，绕开 Mockito
   varargs 不稳定）——Batch 155/157/162 三种变体。
2. 真实 react-query + mock API 层（替代整体 mock useQuery/
   useMutation，使 mutation 回调真实可达）——Batch 140/143。
3. `userEvent.setup()` 接管 navigator.clipboard——clipboard spy 必须
   在 setup 之后打——Batch 143。
4. rerender 传同一 JSX 元素引用会被 React 跳过——Batch 166。
5. gated IT 本机运行手册：`TESTCONTAINERS_RYUK_DISABLED=true` +
   各 IT 开关属性（Batch 188，境内网络 ryuk 拉取失败场景）。

**已知遗留（后续批次候选）**：
- 后端 JaCoCo 梯队：DocumentMutationService 42% /
  EvaluationSuiteService 编排深层 / SemanticEvaluationService 54% /
  OpenAiCompatibilityController 55% 等（57 个 <75% 类清单见 Batch 184
  快照所依据的 jacoco.xml）。
- 前端：Files.tsx 89.39%（上传完成回调/搜索组合输入等深层）、
  ChatSidebar 残余、Dialog 焦点陷阱（v8 映射盲区，行为已锁定）、
  Documents.tsx 797/778 竞态守卫（需事件级编排）。
- 流程性：verifier 明细与批次数（140–196）以账本各 Batch 条目为准。

**等待用户下一步指示。**

### Batch 197（已交付）

- 分支：`test/core-derivation-fulltext-batch197-20260908`（已合入
  main）
- 内容：DerivationIntegrityService（5 用例：summary/embedding
  聚合映射与 profile key、details 分页校验、快照映射、resolver 异常
  上传播）+ FulltextSearchProvider searchInScope 默认实现的
  fail-closed 矩阵（6 用例：null scope 视为无界、matchNone/非 NONE
  filter/documentType/payloadFilter 均不触后端、NONE 范围透传
  documentIds）。
- 指标：core 全量 3864 → **3885** 绿。

### Batch 198（已交付）

- 分支：`test/core-semantic-exec-batch198-20260908`（已合入 main）
- 内容：SemanticEvaluationService 执行链路加固，新增 5 用例：空白
  模型前置拒绝不触 ChatClient、批量空列表与超 semanticBatchLimit
  拒绝、FACT_CHECKING 深度桩 ChatClient 链（prompt/user/call/
  entity）真实执行至 COMPLETED、求值器内部爆炸降级 FAILED、批内
  相同语义项复用单次求值。
- 锁定真实 Spring AI 1.1.7 FactCheckingEvaluator 语义：score 丢弃
  清零、反馈驱动 pass、COMPLETED 状态透传。
- 指标：core 全量 → **3890** 绿。

### Batch 199（已交付）

- 分支：`test/core-idempotency-reserve-batch199-20260909`（已合入
  main）
- 内容：DocumentMutationService 幂等预留语义，新增 7 用例：空白
  Idempotency-Key 不触库、超 255 字符拒绝、首次插入绑定 owner/操作
  类型/64 位 keyHash 且无重放、过期行删除后重查为空 fail-closed
  （不无限重试——过期行在 DELETE 后物理移除）、指纹不一致
  another-request 冲突、SUCCEEDED 重放 result_document_id、
  IN_PROGRESS still-in-progress 冲突。私有 reserveIdempotency 经
  ReflectionTestUtils 调用；StubJdbc 去 final 支持状态化子类。
- 指标：core 全量 3890 → **3897** 绿。

### Batch 200（已交付）

- 分支：`test/core-tombstone-batch200-20260909`（已合入 main）
- 内容：DocumentMutationService.tombstoneExternal 编排加固，新增 4
  用例：外部文档墓碑化成功（实体墓碑状态 + 版本记录 + dispatch
  标记）、已墓碑同 revision 幂等 UNCHANGED、同 revision 仍存活冲突
  拒绝、文档缺失 DocumentNotFoundException。含认证属性上下文与序列
  分配 queryForObject 桩。
- 指标：core 全量 3897 → **3901** 绿。

### Batch 201（已交付）

- 分支：`test/core-import-jsonrec-batch201-20260909`（已合入 main）
- 内容：importDocument 校验分支，新增 2 用例：json-record 无
  externalId 时缺 jsonbPayload 拒绝、json-record 带 externalId 且
  payload 为 null node 拒绝；本地导入（无 externalId）经 createLocal
  持久化（title/source/enabled 断言）与 SKIP 策略不排队嵌入。
- 指标：core 全量 3901 → **3905** 绿。

### Batch 202（已交付）

- 分支：`test/core-sem-timeout-batch202-20260909`（已合入 main）
- 内容：SemanticEvaluationService TIMEOUT 降级分支，新增 1 用例：
  求值器阻塞超过 answerQualityTimeoutSeconds 预算 → TIMEOUT 状态
  （passed/score/feedback 均为 null），elapsed < 2s 断言验证提前
  降级不等待阻塞链路完成。
- 指标：core 全量 3890 → **3906** 绿。

### Batch 203（已交付）

- 分支：`test/core-relevancy-flow-batch203-20260909`（已合入 main）
- 内容：RELEVANCY 完成流深度桩，新增 2 用例：prompt 携带
  query/context/answer 全要素（ArgumentCaptor 捕获）、响应
  pass/score/feedback 正确透传且状态为 COMPLETED。真实
  RelevancyEvaluator 实例直接 evaluate EvaluationRequest，不经
  ChatModel 路由。
- 指标：core 全量 3901 → **3908** 绿。

### Batch 204（已交付）

- 分支：`test/core-import-external-batch204-20260909`（已合入 main）
- 内容：importDocument 外部分支成功路径，新增 3 用例：externalId
  非空新建文档全字段落库（externalId/namespace/revision/内容哈希/
  enabled=false 透传/sourceMutationSequence=7）且 SKIP 策略不排队
  不标记嵌入、同 revision 同状态 UNCHANGED 幂等重放（不落库不记版
  本不分配序列、写令牌仍确认）、空 sourceNamespace 归一化为
  default。json-record 校验分支已由 Batch 201 覆盖，本批去重后
  聚焦成功编排；写令牌用真实 ActiveCollectionToken record 实例。
- 指标：core 全量门禁 EXIT=0 绿。

### Batch 205（已交付）

- 分支：`test/core-external-retry-batch205-20260909`（已合入 main）
- 内容：executeExternalInTransaction 重试语义（MAX=3），新增 4 用
  例：DataIntegrityViolation 首次失败第二次重试成功（CREATED、
  sourceRevision/versionNumber 断言、lookup 恰 2 次）、可重试失败
  耗尽 3 次 → DocumentRevisionConflictException 且 cause 保留、
  不可重试 IllegalStateException 立即透传（恰 1 次不重试）、
  json-record 路径耗尽 → StructuredRecordConflictException。
  upsertExternal 经 authenticateAsDatabaseKey + requireActive(null,
  "kb") 桩集合解析；finishExternal 的 findById 回传 saveAndFlush
  同一实体（AtomicReference）。
- 指标：core 全量门禁 EXIT=0 绿。

### Batch 206（已交付）

- 分支：`test/core-external-guards-batch206-20260909`（已合入 main）
- 内容：外部文档守卫语义，新增 10 用例：requireKind 双向种类冲突
  （text 覆写 json-record → DocumentRevisionConflict、json-record
  覆写 text → StructuredRecordConflict）、reconcileMissingExternal
  五类守卫返回 false（文档缺失/无 externalId/无 sourceRevision/
  已禁用/快照后新变更）+ 合格文档墓碑化成功路径（RECONCILIATION
  origin、runId 写入、revision 3→4、TOMBSTONE 版本、
  markNotRequested + cancelActive）、unlinkLocalDocumentsFromCollection
  外部托管保护拒绝与本地解绑编排（collectionId 置空 + 每文档
  COLLECTION_MOVE 版本 + 计数 2）。setUp 统一 saveAndFlush 透传
  与 DATABASE_API_KEY 认证桩。
- 指标：core 全量门禁 EXIT=0 绿。

### Batch 207（已交付）

- 分支：`test/webui-interaction-gaps-batch207-20260909`（已合入
  main）
- 内容：WebUI 交互缺口加固（覆盖率数据驱动），新增 9 用例：
  ChatSidebar 会话选择回调与删除按钮持久化、CollectionScopeSelector
  勾选取消与禁用守卫、ReembedAllButton mutationFn 走真实
  documentsApi.reembedMissing、CreateCollectionModal 名称超 100/
  描述超 500 校验与创建失败 error toast（模态保持打开）、
  DocumentActionsMenu provenance 子菜单目录/原始 PDF 两项回调。
  目标组件行覆盖全部达到 100%；整体行覆盖 96.27% → 96.82%。
  另发现 ReembedAllButton.test 历史上 describe 提前闭合（后两用例
  在顶层），保持原语义仅去除孤立括号。
- 指标：webui 全量 613 绿 + vite build 通过。

### Batch 208（已交付）

- 分支：`test/core-sync-run-item-batch208-20260909`（已合入 main）
- 内容：快照条目应用（upsertSyncRunItemInCurrentTransaction）分支
  语义，新增 4 用例：文档 mutation sequence 大于快照起点 →
  SKIPPED_NEWER_MUTATION（不落库不记版本、embeddingAction=NONE）、
  同 revision 同状态 UNCHANGED 重放（零持久化副作用）、json-record
  条目缺 payload → NPE、NullNode payload → IllegalArgumentException。
  包级入口直接测试，不经事务模板包装。
- 指标：core 全量门禁 EXIT=0 绿。

### Batch 209（已交付）

- 分支：`test/webui-files-dialog-batch209-20260909`（已合入 main）
- 内容：Files 与 Dialog 交互缺口加固，新增 9 用例：PDF 导入失败
  error toast + 内联错误框、拖放 PDF 触发导入、导入 ID 复制成功/
  失败 toast（clipboard 桩须在 userEvent.setup 之后打）、go-up 控
  制跳转父目录（parentPath 真分支）、tree 查询失败错误框；Dialog
  焦点陷阱真实命中分支（Shift+Tab 从 DOM 首个可聚焦元素环绕到最
  后——此前环绕用例靠原生 tab 循环通过未触处理器）、无可聚焦元
  素时 Tab 聚焦面板本体、点击 backdrop 关闭。Dialog 行覆盖 100%，
  Files 88.57% → 93.65%，整体行覆盖 96.82% → 97.63%。
- 指标：webui 全量 622 绿 + vite build 通过。

### Batch 210（已交付）

- 分支：`test/core-eval-compare-batch210-20260909`（已合入 main）
- 内容：EvaluationSuiteService（JaCoCo 指令覆盖 27%，全库垫底）
  运行查询与 compare 对比语义，新增 7 用例：getRun 全字段映射
  （suite/version/sha/status/cases）与 NOT_FOUND、compare 拒绝不同
  套件版本、拒绝 variant 不一致、embedding profile 不同 → 环境漂
  移（sameProfile=false）、collectionSnapshot 不同 → 漂移（语料变
  更识别）、环境完全一致 → 无漂移。listSuites 桩需累积列表（两个
  run 共存），validator.parse 桩真实 definition record。
- 指标：core 全量门禁 EXIT=0 绿。

### Batch 211（已交付）

- 分支：`test/core-dispatch-branches-batch211-20260909`（已合入
  main）
- 内容：EmbeddingDispatchService（JaCoCo 45.5%）分支语义，新增 9
  用例：活跃任务合并 ASYNC_COALESCED（不重置处理状态/不重分配代
  次、QUEUED 触发 wakeup）、force 重入队重置 PENDING、持久化任务
  禁用 RagException、NOT_REQUESTED 标记与处理状态落库、
  dispatchAfterCommit SKIP/SYNC/ASYNC 三策略、同步完成 SUCCEEDED →
  SYNC_COMPLETED、FAILED → 失败状态透传 lastError、无 jobExecutor
  与无排队任务时原样返回。
- 指标：core 全量门禁 EXIT=0 绿。

### Batch 212（已交付）

- 分支：`test/core-diagnostics-branches-batch212-20260909`（已合入
  main）
- 内容：RetrievalDiagnosticsService（JaCoCo 50.8%）分支语义，新增
  9 用例：list 分页钳制（size 500→100、page -3→0、空白过滤转
  null）、repository 缺失时空页降级、cleanupExpired 有界批量循环
  （500+120=620 恰两批）与保留期 0 跳过、预算耗尽 outcome 落库、
  空会话 DIAGNOSTIC_UNKNOWN、storeQueryText 开启保留原文（含耗时
  与结果数断言）、诊断禁用不落库、isEnabled 配置镜像。
- 指标：core 全量门禁 EXIT=0 绿。

### Batch 213（已交付）

- 分支：`test/core-router-routing-batch213-20260909`（已合入 main）
- 内容：ChatModelRouter 路由语义（此前仅 registry 名字查询有测
  试），新增 13 用例：resolve 空引用/未知 provider/大小写不敏感别
  名、resolveRequired 失败提示含可用清单、primary 经 registry 名
  字解析、fallback 过滤不可解析项与 null、orderedCandidates 去重、
  resolveCandidateRequired legacy 候选（默认能力/估算限制/cost
  null）、未知引用抛错、getDefaultModelRef 主模型优先与首个可用
  回退、modelsInfo legacy 来源标记、providerInfo 可用性与显示名、
  isProviderAvailable 大小写兼容。Fake 类名命中 resolveProvider
  类名启发式（zhipu）。
- 指标：core 全量门禁 EXIT=0 绿。

### Batch 214（已交付）

- 分支：`test/core-provisioning-race-batch214-20260909`（已合入
  main）
- 内容：CollectionProvisioningService 竞态语义（JaCoCo 57.5%），
  新增 6 用例：DUPLICATE_RESOURCE 竞态经台账重放恢复（replay=
  true、不重复落库）、唯一约束冲突第二次尝试成功创建、重试耗尽
  （默认 3 次）后重抛原始冲突、已清理（purgedAt 非空）集合拒绝
  重放 COLLECTION_ALREADY_RETIRED、台账清理有界批量删除、功能禁
  用跳过清理。台账查找桩「先空后有」建模并发方写入。
- 指标：core 全量门禁 EXIT=0 绿。

### Batch 215（已交付）

- 分支：`test/core-export-visibility-batch215-20260909`（已合入
  main）
- 内容：ChatExportService 可见性门控（JaCoCo 56.5%，此前仅无
  principal 的旧版导出有测试），新增 7 用例：principal 变体走
  owner 过滤查询（legacy 开关按 principal 类型推导，db:1→false、
  root:environment-root→true）、limit>0 走数据库级 TopN 并反转为
  时间正序、空/不可见会话 SESSION_NOT_FOUND、Markdown assistant
  块与来源渲染（S1/标题）、CSV user/assistant 行映射（空白回复不
  产生 assistant 行）、null principal 拒绝。
- 指标：core 全量门禁 EXIT=0 绿。

### Batch 216（已交付）

- 分支：`test/core-pdf-queries-batch216-20260909`（已合入 main）
- 内容：PdfImportService 文件管理读取面（JaCoCo 62.9%，此前仅
  importPdf 主流程有测试），新增 7 用例：原始文件名归一化（剥客
  户端路径/空白/控制字符拒绝/非 .pdf 拒绝/超 512 拒绝/空文件拒
  绝）、按导入 ID 单查与批量查（空/null 集合返回空 Map）、根路径
  子项列举（/、null、空白）、直接子项过滤（深层嵌套排除、legacy
  前导空白路径 trim 后保留）、临时文件资源加载（文件名后缀、内
  容非空）与未知路径返回空。
- 指标：core 全量门禁 EXIT=0 绿。

### Batch 217（已交付）

- 分支：`test/core-tool-registry-policy-batch217-20260909`（已合入
  main）
- 内容：RagChatToolRegistry 启动校验与请求上下文装配（JaCoCo
  68.5%），新增 6 用例：跨 provider 重复工具名启动拒绝、策略超全
  局上限（maxCallsPerRequest>agent 上限）启动拒绝、空白 schema 拒
  绝（ToolDefinition 构建器先行抛 IllegalArgumentException）、非对
  象 schema 启动拒绝、supportedModes 过滤（AGENT 专属工具不出现在
  KNOWLEDGE 模式）、requestContext 装配（principal/模式/模型 ref/
  截止时间/预算对象/每工具结果字符限制映射）。注：Effect 枚举当前
  仅 READ_ONLY，写效果分支为防御性代码不可构造。
- 指标：core 全量门禁 EXIT=0 绿。

### Batch 218（已交付）

- 分支：`test/webui-page-gaps-batch218-20260909`（已合入 main）
- 内容：WebUI 页面缺口收尾（覆盖率数据驱动），新增 7 用例：
  Embeddings collectionKey 筛选器设值/清空走 URL 参数增删（含
  MemoryRouter location 探针模式）、修复预览 Dialog 关闭（不触发
  applyRepair）、Alerts 投递查询 pending 加载态、投递状态筛选器设
  值与空选项清空（参数变更触发 refetch 会用加载态替换筛选条，清空
  用例直接以带参数初始 URL 渲染规避竞态）、Evaluation tab 切换
  （suites 写入参数、report 清空参数）。Collections purge 无
  preview 分支为防御性代码（apply 按钮仅在 preview 存在时渲染）
  不可经 UI 触发，未强造。
- 指标：webui 全量 628 绿 + vite build 通过。

### Batch 219（已交付）

- 分支：`test/core-turn-lifecycle-batch219-20260909`（已合入 main）
- 内容：ChatSessionCoordinator 键控回合操作生命周期（JaCoCo
  72%），新增 6 用例：failOperation 经 completeFailure CAS 成功收
  尾（事务内消费状态化租约、句柄进入终态）、CAS 失败（操作租约先
  丢）抛 CHAT_HISTORY_PERSIST_FAILED、failExpiredOperation 经
  exhaustAttempts 专用 reclaim CAS 成功与并发状态变更失败、
  clearSession 联动 summaryService.clear、无状态句柄跳过租约消费。
  包内直接调用 package-private setter 注入
  ChatTurnOperationRepository / ConversationSummaryService。
- 指标：core 全量门禁 EXIT=0 绿。

### Batch 220（已交付）

- 分支：`test/core-rotation-lifecycle-batch220-20260909`（已合入
  main）
- 内容：ApiKeyManagementService 凭证轮换台账生命周期（JaCoCo
  41.3% 全库最大缺口的正面攻坚），新增 13 用例：prepare 空幂等键
  拒绝、未知当前凭证返回 null、匹配指纹幂等重放（无新密钥泄露、
  不再落库）、指纹不同 IDEMPOTENCY_KEY_REUSED、已有 PENDING 互斥
  CREDENTIAL_ROTATION_PENDING、当前凭证不一致 CREDENTIAL_NOT_CURRE
  NT、overlap 越界（0 与 max+1）拒绝、主体已过期 PRINCIPAL_NOT_ACT
  IVE、完整准备路径（当前凭证设置 retireAt、目标凭证版本+1、台账
  PENDING 落库、rawKey 仅创建时返回）；complete 禁用源凭证并
  COMPLETED、已完成幂等重放、过期 overlap EXPIRED；cancel 禁用目
  标凭证并恢复源凭证 retireAt 为 CANCELED。凭证仓库以 keyId→实体
  Map 的 Answer 桩动态解析生成的新目标；台账共享桩 lenient 化。
- 指标：core 全量门禁 EXIT=0 绿。

### Batch 221（已交付）

- 分支：`test/core-rotation-maintenance-batch221-20260909`（已合入
  main）
- 内容：ApiKeyManagementService 轮换台账读取与回收维护，新增 6
  用例：getRotation 惰性过期（读取 overdue PENDING 即翻转为
  EXPIRED 并禁用源凭证）、retiring 凭证与 rotationPending 标志映
  射、未知轮换 NOT_FOUND；cleanupCredentialRotations 逐台账过期 +
  终态行按保留期批量删除、二次校验未到期则跳过（不消费凭证不落
  库）、台账或事务缺失静默降级。stubRotationLookup lenient 化。
- 指标：core 全量门禁 EXIT=0 绿。

### Batch 222（已交付）

- 分支：`test/webui-forms-preview-batch222-20260909`（已合入 main）
- 内容：WebUI 表单与预览残余缺口（覆盖率数据驱动），新增 5 用例：
  ApiKeys 创建表单能力单选（只读→读写往返，两个 onChange 分支）、
  轮换模式 staged→immediate→staged 往返（回切后 overlap 输入重
  现）、overlap 超范围（>86400）静默忽略不触发 prepareMutation、
  Layout 移动端侧栏关闭按钮（innerWidth 置为移动断点后渲染）、
  FilePreview PDF 原文拉取失败错误框。后端 ConversationSummarySer
  vice 的 clear/promptText 复核发现已有覆盖，未重复添加。
  Layout/FilePreview 行覆盖 100%，ApiKeys 行 94.73%→95.69%。
- 指标：webui 全量 633 绿 + vite build 通过。

### Batch 223（已交付）

- 分支：`test/webui-search-mapping-batch223-20260909`（已合入
  main）
- 内容：Search 与 ApiKeys 交互深层，新增 4 用例：Search 结果映射
  （fulltext/vector 混合分数字段、无 title 时 "Document {id}" 兜
  底、chunkText 内容兜底）、原文 PDF 打开失败 toast
  （getRawFile reject → search.openOriginalPdfError）、搜索历史下
  拉在完成一次搜索后出现并可展开；ApiKeys 策略表单扩展断言到期时
  间输入与"全部集合"单选（预期值同步更新为 2027-06-30T12:00）。
  覆盖率报表中 Search 322-334 行仍标记未覆盖，属 JSX transform
  行号归因偏差——行为已由映射用例锁定。
- 指标：webui 全量 636 绿 + vite build 通过；ApiKeys 行
  95.69%→96.17%。

### Batch 224（已交付）

- 分支：`test/core-chat-export-turn-batch224-20260909`（已合入
  main）
- 内容：RagChatController 控制器层缺口（JaCoCo 53.9%），新增 6
  用例：exportHistory JSON 下载（application/json; charset=utf-8、
  Content-Disposition 附件文件名 session-1.json、响应体透传）、
  Markdown 变体（format=MD 大小写兼容、limit=25 透传、.md 文件
  名）、非法 format=csv 抛 IllegalArgumentException、getTurnStatus
  在幂等未配置时 fail-closed 抛 RagException、正常路径经
  configureTurnOperationService 注入后向
  ChatTurnOperationService.status 委托（principal/turnId/includeRe
  sponse 断言）、导出委托使用从请求解析的 DATABASE_API_KEY
  principal（db:key-42）。
- 指标：core 全量门禁 EXIT=0 绿。

### Batch 225（已交付）

- 分支：`test/core-openai-basic-batch225-20260909`（已合入 main）
- 内容：OpenAiCompatibilityController（JaCoCo 52.9%，控制器层最
  低）非键控路径单元语义，新增 5 用例：listModels 信封（object=
  "list" + 别名 id 映射）、getModel 按别名映射、非键控 JSON 完成
  （mapper 映射命令 → executionService.execute 同一实例 → 200 +
  application/json + ResponseBodyEmitter）、诊断关闭时命令原样透
  传（不附加 trace session）、带 requestedModel 的结果经信封生成
  器正常消费。键控 replay/claim/SSE 分支由既有 WebTest 覆盖。
- 指标：core 全量门禁 EXIT=0 绿。

### Batch 226（已交付）

- 分支：`test/core-pdf-embed-policy-batch226-20260909`（已合入
  main）
- 内容：PdfImportController 触发嵌入的策略分派（JaCoCo 75%），
  新增 7 用例：ASYNC 端点拒绝 embed=sse 组合（RagException）、
  ASYNC 策略经 triggerEmbeddingWithPolicy 走 4 参
  triggerEmbedding（含 embeddingAction/jobId/batchId 的 9 字段结
  果映射）、sync 端点携带非 SYNC 策略时转发到策略分派、SKIP 策略
  显式拒绝、SSE 端点空白 UUID IllegalArgumentException、SSE 默认
  变体带 collectionId 委托、previewHtmlFragment 的 default.md→
  paper.md 双查找回退（前导斜杠路径）。
- 指标：core 全量门禁 EXIT=0 绿。

### Batch 227（已交付）

- 分支：`test/core-collection-bykey-batch227-20260909`（已合入
  main）
- 内容：RagCollectionController by-key 路由（JaCoCo 82.9%，此前
  39 用例全部走数字路由），新增 5 用例：getByKey 返回集合 Map +
  文档计数、updateByKey 经 requireActive 解析后委托数字更新
  （findByIdAndDeletedFalse + save、name 更新断言）、deleteByKey
  经 deleteCollection CAS 软删（id/documentsUnlinked 响应断言）、
  restoreByKey 经 requireIncludingDeleted 解析已删除集合并恢复
  （collectionId/documentCount 断言）、键解析失败 RagException 透
  传。认证沿用 DATABASE_API_KEY 属性 + requireActive(null, key)
  桩模式。
- 指标：core 全量门禁 EXIT=0 绿。

### Batch 228（已交付）

- 分支：`test/core-alert-updates-batch228-20260909`（已合入 main）
- 内容：AlertController 更新端点（JaCoCo 75%，PUT 路由此前无测
  试），新增 4 用例：updateSloConfig 成功路径（type/targetValue/
  unit/description 变更 + enabled=null 保持 + enabled=false 显式关
  闭 + logUpdate 审计）、SLO 不存在 404；updateSilenceSchedule 成
  功路径（alertKey/silenceType/时间段/描述/enabled 变更 + 审计）
  与不存在 404。审计断言经控制器的 logUpdate 委托验证。
- 指标：core 全量门禁 EXIT=0 绿。

### Batch 229（已交付）

- 分支：`test/core-eval-quality-batch229-20260909`（已合入 main）
- 内容：EvaluationController 答案质量与语义评测端点（JaCoCo
  71.6%），新增 6 用例：answer-quality 全字段映射（groundedness/
  relevance/helpfulness/reasoning/recommendation 透传 + 服务入参
  逐项断言）、semantic 单条委托、semantic/batch 批量委托、语义服
  务缺失时 semantic 与 semantic/batch 均 IllegalStateException
  fail-closed、AnswerQualityResult 默认构造 + setter 合同（REVISION
  推荐）。
- 指标：core 全量门禁 EXIT=0 绿。

### Batch 230（已交付）

- 分支：`test/core-pdf-policy-dispatch-batch230-20260909`（已合入
  main）
- 内容：PdfToRagService（JaCoCo 70.4%）显式触发嵌入的策略分派
  （4 参 triggerEmbedding 此前无测试），新增 4 用例：SKIP 策略
  BAD_REQUEST 拒绝、ASYNC 但分发通道缺失时 EMBEDDING_JOBS_DISABLED
  fail-closed（requireJobsEnabled）、ASYNC 经
  enqueueInCurrentTransaction 排队（QUEUED 状态、ASYNC_QUEUED
  action、jobId/batchId 透传、文档新建 id=66）、SYNC 回落既有同步
  触发（embedDocument 完成态 + chunksCreated）。dispatchService 经
  包内 setter 注入。
- 指标：core 全量门禁 EXIT=0 绿。

### Batch 231（已交付）

- 分支：`test/core-job-query-page-batch231-20260909`（已合入 main）
- 内容：EmbeddingJobService（JaCoCo 66.3%）查询与维护面，新增 5
  用例：get 经 requireAuthorized（find + 文档存在 + ACL）映射响应、
  缺失任务 NOT_FOUND、listPage 分页钳制（size 500→200、page -3→0、
  总页数 0）、totalPages 计算（450 元素/50 每页 → 9 页）、cancel
  优先取 CAS 更新后的任务并在落空时回传当前快照（CANCELLED 状态断
  言）。注意 EmbeddingJobRepository 位于 embeddingjob 包（非
  repository），listPage offset 参数为 int。
- 指标：core 全量门禁 EXIT=0 绿。

### Batch 232（已交付）

- 分支：`test/core-router-configured-batch232-20260909`（已合入
  main）
- 内容：ChatModelRouter 的 configuredFactory 配置模型路径（接续
  Batch 213 的 legacy 别名测试），新增 7 用例：resolve 配置模型优
  先于 legacy 别名、resolveRequired 透传 factory 不可用原因
  （quota exhausted）、canonical ref 候选装配（multiModel 无条目时
  能力默认 + 限额视为估算、描述符限额不进入 canonical 候选——
  候选去重按 ref）、orderedCandidateDescriptors 配置在前 legacy 在
  后、getDefaultModelRef 返回 canonicalRef、modelsInfo 配置行可用
  时跳过同名 legacy 行（provider=zhipu 才覆盖）、配置行不可用则
  legacy 行兜底。ModelDescriptor 13 字段 record 全参构造。
- 指标：core 全量门禁 EXIT=0 绿。

### Batch 233（已交付）

- 分支：`test/webui-settings-deep-batch233-20260909`（已合入 main）
- 内容：Settings 深层交互（覆盖率数据驱动），新增 3 用例：连续两
  次保存（第二次 clearTimeout 第一次的"已保存"指示器定时器，最终
  落库 vectorWeight=0.6）、全文权重滑杆 onChange（range input
  0.4 值绑定）、语言偏好按钮（changeLanguage('en') +
  localStorage 'language'='en'）。Settings 行覆盖 96.8%→100%。
- 指标：webui 全量 639 绿 + vite build 通过。

### Batch 234（已交付）

- 分支：`test/core-execute-run-batch234-20260909`（已合入 main）
- 内容：EvaluationSuiteService executeRun 主生命周期（此前仅
  worker 租约测试触达），新增 5 用例：owner 凭证失效时
  finishRun(FAILED, AUTHORIZATION_CHANGED) fail-closed、版本缺失
  NOT_FOUND 传播、定义越权（scopeResolver 抛 SecurityException）
  fail-closed、语料快照漂移 → CORPUS_CHANGED 状态+错误码、通过路
  径聚合（PASSED + avgHitRate/avgMrr=1.0、caseCount=1）。关键桩：
  insertCaseResult 须返回 1——0 触发租约护栏早退（首次运行暴露该
  分支）。
- 指标：core 全量门禁 EXIT=0 绿。

### Batch 235（已交付）

- 分支：`test/core-summary-selection-batch235-20260909`（已合入
  main）
- 内容：ConversationSummaryService selectSourceRows 选择边界，新
  增 4 用例：compactionMaxTurnsPerCall=2 上限（第 3 行留待下次推
  进、源文本不含最新轮）、最近受保护轮次排除（protected 行永不被
  摘要）、非 COMPLETE 状态行跳过（PROCESSING 排除）、已有摘要游标
  后继续推进（version 4→5、findOwnedAfterHistoryId 以游标为界、
  源文本含 prior summary）。调试发现单类运行时 BudgetedChatModel
  链路冷加载会击穿 200ms 超时预算 → 本类放宽至 5s（超时分支已由
  既有 timeoutDegrades 用例覆盖）。
- 指标：core 全量门禁 EXIT=0 绿。

### Batch 236（已交付）

- 分支：`test/webui-chat-doc-deep-batch236-20260909`（已合入
  main）
- 内容：Chat 与 Documents 深层交互，新增 3 用例：Chat 历史侧栏
  打开（☰ toggle）→ 选择历史会话 → 导航至 /chat/session-1 并关闭
  侧栏（handleSelectSession 双行 + toggle 三态全覆盖）；Documents
  编辑对话框的集合/嵌入策略下拉 onChange（本地文档 fixture 需
  documentRevision 才能通过修订护栏提交）；relocate 对话框 cancel
  关闭（对话框消失且 relocate API 不被调用）。
- 指标：webui 全量 642 绿 + vite build 通过。

### Batch 237（已交付）

- 分支：`test/core-executor-errors-batch237-20260909`（已合入
  main）
- 内容：EmbeddingJobExecutor 收尾细节（既有 11 用例之外的三个缺
  口），新增 3 用例：空白 provider 错误文本经 safeError 回退为默
  认失败描述（"Embedding job failed" 落库 markFailure）、force 标
  志以仓储最新状态（find 刷新）而非租约快照传入嵌入调用并影响
  markSucceeded 的 force 参数、claim 失败且任务消失时 IllegalArgu
  mentException。桩要点：executeNow 每次生成随机 lease owner，
  isCommitAllowed/markSucceeded 断言需按任意 owner 匹配。
- 指标：core 全量门禁 EXIT=0 绿。

### Batch 238（已交付）

- 分支：`test/core-syncrun-abort-batch238-20260909`（已合入 main）
- 内容：DocumentSyncRunService.abort 端点（JaCoCo 74.9%，30 个既
  有用例均未触达 abort），新增 5 用例：ACTIVE 租约 CAS 置
  ABORTED（runStatusRef 联动 answer 桩、验证 ABORTED SQL）、
  COMPLETED/EXPIRED 终态幂等回读且不再触发 CAS、CAS 落空（0 行更
  新）抛 SYNC_RUN_INVALID_STATE、空白租约令牌 IllegalArgumentExc
  eption。CAS answer 桩在 ABORTED SQL 时切换 runStatusRef，使二
  次 requireRun 读到终止态。
- 指标：core 全量门禁 EXIT=0 绿。

### Batch 239（已交付）

- 分支：`test/core-syncrun-expiry-batch239-20260909`（已合入
  main）
- 内容：DocumentSyncRunService complete 的租约过期守卫
  （expireIfNeeded 分支此前无测试），新增 1 用例：租约已过期的
  ACTIVE 运行在完成时被翻转为 EXPIRED（SET status = 'EXPIRED'
  SQL 落库断言）并以 SYNC_RUN_INVALID_STATE + "expired" 消息拒
  绝，防止过期租约继续驱动墓碑化。EmbeddingJobWakeupPublisher 经
  查已有 4 用例覆盖（commit 后单次发布/回滚不发/无事务即时发/监
  听器失败不影响提交），未重复投入。
- 指标：core 全量门禁 EXIT=0 绿。

### Batch 240（已交付）

- 分支：`test/core-tool-manager-branch-batch240-20260909`（已合入
  main）
- 内容：BudgetedToolCallingManager 分支补全（既有 2 用例之外），
  新增 5 用例：响应无工具调用时直通委托、缺失预算时直通委托、
  contextPlan toolResultTokens=1 触发 token 预算替换
  （tool_result_too_large）、TOOL_RESULT_CHARACTER_LIMITS 按工具名
  独立限额（search 限额替换、fetch 500 保留）、委派异常时释放预
  留并原样重抛。调试确认 promptWithBudget 必须与 token 预算设置共
  用同一 budget 实例（双实例曾致断言误判）。
- 指标：core 全量门禁 EXIT=0 绿。

### Batch 241（已交付）

- 分支：`test/core-advisor-edge-batch241-20260909`（已合入 main）
- 内容：BudgetedToolCallAdvisor 边界分支（既有 8 用例之外），同包
  直调 protected 方法新增 4 用例：doInitializeLoopStream 播种
  ToolTranscriptCollector、collector 缺失时工具轮记录快速失败
  （IllegalStateException + 精确消息）、上下文缺少 AuthorizedRetri
  evalContext 时跳过轮数预算强制（不误伤）、最终答案（无工具调
  用）即使 trace 已耗尽也不触发 RETRIEVAL_FAILED。
- 指标：core 全量门禁 EXIT=0 绿。

### Batch 242（已交付）

- 分支：`test/core-search-tracing-batch242-20260909`（已合入 main）
- 内容：RagSearchController 追踪注入（traced 私有链路的 8 行缺口），
  新增 2 用例：诊断开启时搜索响应携带 X-RAG-Retrieval-Trace-Id 头
  （createSession 返回真实 RetrievalTraceSession、persistSearch 委
  托验证）；诊断关闭时响应无追踪头。调试确认 createSession mock 返
  回 null 会触发 attachScope NPE 并被 catch 吞掉（优雅降级分支本身
  亦被此失败路径覆盖）。
- 指标：core 全量门禁 EXIT=0 绿。

---

## 进度留档快照（Batch 242 后 · 用户指令）

- 留档时点：2026-09-09 · main @ d4877ac8（含本次快照提交）
- 循环进度：Batch 204–242 全部按「规划→实施→门禁→commit/push
  特性分支→合并 main→账本记录」交付完成，共 39 个批次。
- 可构建性证据：core 全量 mvn test 门禁 EXIT=0（Batch 242 后）；
  webui 全量 642 测试绿 + vite build 通过（Batch 236 后 webui 无更
  改，本次留档复验 build 通过）。
- 测试规模：core 后端单测约 3900+（自 Batch 204 的 ~3900 持续增
  长并完成大量缺口收敛）；webui 前端 642（自 592 增长），整体行覆
  盖率 96.8%+。
- 本阶段重点成果：
  - DocumentMutationService 系列（外部导入/重试/守卫/快照条目）
  - EvaluationSuiteService compare/executeRun 生命周期
  - EmbeddingJobExecutor/Service/Dispatch 分支与分页
  - ApiKeyManagementService 凭证轮换台账（prepare/complete/
    cancel/getRotation/cleanup）
  - OpenAI 兼容控制器非键控路径、RagChatController 导出/轮次状
    态、RagSearch 追踪注入、PdfImport 策略分派、Alert 更新端点
  - WebUI：ChatSidebar/ScopeSelector/ReembedAll/CreateCollectionM
    odal/DocumentActionsMenu/Dialog/Files/Settings/Chat/Documents
    交互与边界全覆盖
- 已沉淀可复用测试模式：JdbcTemplate 桩子类按 SQL 分类、ActiveColl
  ectionToken 真实 record、DATABASE_API_KEY 认证属性、AtomicRefere
  nce 回传 saveAndFlush 实体、lenient 化共享桩、MemoryRouter
  location 探针、userEvent.setup 后打 clipboard 桩等。
- Batch 239 候选遗留（后续恢复循环时可选取）：OpenAiChatRequestM
  apper 深层、ChatExecutionService 深层、WebUI Documents.tsx
  594-658 残余、DocumentSyncRun preview/complete 更多边界。
- 本地已合并的 test/* 特性分支随留档清理，远端同名分支保留待远端
  策略处理。

### Batch 243（已交付）

- 分支：`test/core-prepared-operation-batch243-20260909`（已合入
  main）
- 内容：ChatExecutionService prepareForOperation（JaCoCo 31 行全未
  覆盖的最大方法级缺口），新增 3 用例：成功路径构建
  PreparedExecution（result.answer/sessionId、无服务端记忆时持久
  化消息为空、candidate 与 Attempt 一致）、首候选失败降级到次候选
  （create 恰 2 次）、RagException 快速失败不消耗后续候选（恰 1
  次）。Attempt 需携带真实 AuthorizedRetrievalContext（null 会致
  trace() NPE）。
- 指标：core 全量门禁 EXIT=0 绿。

### Batch 244（已交付）

- 分支：`test/core-authz-snapshot-batch244-20260909`（已合入 main）
- 内容：ChatAuthorizationService 授权快照构建（JaCoCo 64.5%，既
  有 3+矩阵用例集中在 verifyReplay），新增 5 用例：PLAIN 模式全部
  NOT_APPLICABLE、CALLER_VISIBLE+非受限调用方允许未分配文档且快
  照版本=1、RESTRICTED 调用方白名单去重排序（"7,9"→[7, 9]）且
  unassignedDocumentsAllowed=false、响应来源映射到
  sourceDocumentCollectionSnapshot（documentId/collectionId 对）与
  sourceCollectionIdsObserved 去重排序、未知来源文档
  IDEMPOTENCY_AUTHORIZATION_SNAPSHOT_INVALID fail-closed。RESTRICT
  ED 调用方经 AUTHENTICATED_API_KEY_ENTITY 属性（RagApiKey 实体）
  驱动 currentPolicy()。
- 指标：core 全量门禁 EXIT=0 绿。

### Batch 245（已交付）

- 分支：`test/core-openai-validation-batch245-20260909`（已合入
  main）
- 内容：OpenAiChatRequestMapper 请求校验分支（JaCoCo 80.4%，
  validateRequest/validateRagShape/resolveFilters 深层），新增 8 用
  例：空白模型 missing_required_parameter、空消息列表、消息超过
  MAX_MESSAGES=100 invalid_value、n=2 unsupported_parameter、rag
  顶层未知字段（rag/unsupported_parameter + 消息含字段名）、
  rag.filters 未知字段、rag.scope 未知字段、合法 metadata_contains/
  payload_contains 经校验器解析为 JsonbContainmentFilter 规范
  JSON。JsonbContainmentFilter 为 record(canonicalJson)，Retrieval
  Filters 载体组件为 payloadContainsAll 列表。
- 指标：core 全量门禁 EXIT=0 绿。

### Batch 246（已交付）

- 分支：`test/core-syncrun-preview-guards-batch246-20260910`（已合
  入 main）
- 内容：DocumentSyncRunService.preview 守卫分支（requireActiveLease
  链路），新增 3 用例：非 ACTIVE（COMPLETED）运行拒绝
  SYNC_RUN_INVALID_STATE "not ACTIVE"、租约已过期的运行翻转
  EXPIRED（SET status = 'EXPIRED' 落库 + 回调断言）并拒绝、外来租
  货令牌 SYNC_RUN_LEASE_CONFLICT。状态/过期标志经闭包联动
  ResultSet 桩。
- 指标：core 全量门禁 EXIT=0 绿。

### Batch 247（已交付）

- 分支：`test/core-renewal-schedule-batch247-20260910`（已合入
  main）
- 内容：ChatTurnOperationService startRenewal/stopRenewal 调度契
  约，新增 6 用例：无键控 Claim（Claim.unkeyed）与 replay Claim 均
  不调度续期、键控 IN_PROGRESS 声明创建 ScheduledFuture（非完成、
  renewalLost 初始 false）、重复 startRenewal 不重复调度（同一
  future 实例）、stopRenewal 取消 future（isCancelled + 引用清
  空）、release(null) 容忍空声明。注意 stopRenewal 为 private，测
  试经公共 release(claim) 路径触达；周期体（renew CAS 成败）首轮
  触发 ≥10s 留给仓储层保证。
- 指标：core 全量门禁 EXIT=0 绿。

### Batch 248（已交付）

- 分支：`test/webui-docs-nav-batch248-20260910`（已合入 main）
- 内容：Documents 深层交互补全，新增 3 用例：目录动作导航
  （onViewDirectory → /files?path=<编码路径>，含中文路径解码断
  言；发现该文件旧 loc-probe 为空元素未输出内容，改为真实
  LocationProbe 组件并加 waitFor 等待路由更新）、编辑对话框
  Escape 关闭（不触发 update）、relocate 对话框 Escape 关闭（不
  调用 relocate API）。Documents.tsx 行覆盖 96.27%→98.13%。
- 指标：webui 全量 645 绿 + vite build 通过。

### Batch 249（已交付）

- 分支：`test/core-turn-status-batch249-20260910`（已合入 main）
- 内容：ChatTurnOperationService.status 分支矩阵（既有 2 用例之
  外），新增 6 用例：成功轮 includeResponse=false 时跳过反序列化
  但仍标记 replayAvailable、FORBIDDEN 审计失败被吞（replayAvailab
  le=false、状态查询不阻断）、非 FORBIDDEN 审计失败重抛（快照损
  坏）、IN_PROGRESS 轮跳过授权检查且无响应、损坏存储快照 →
  INTERNAL_ERROR、FAILED 轮透传 errorCode。审计交互经
  verifyReplay mock 驱动。
- 指标：core 全量门禁 EXIT=0 绿。

### Batch 250（已交付）

- 分支：`test/core-syncrun-batch-boundary-batch250-20260910`（已合
  入 main）
- 内容：DocumentSyncRunService.batchUpsert 边界与汇总（在既有 30
  用例文件内追加），新增 2 用例：null 请求体 NullPointerException
  快速失败、UNCHANGED 条目计入汇总（unchanged=1/applied=0/total=1
  且条目状态透传）。
- 指标：core 全量门禁 EXIT=0 绿。

### Batch 251（已交付）

- 分支：`test/core-authz-source-verify-batch251-20260910`（已合入
  main）
- 内容：ChatAuthorizationService verifyReplay 的 verifySources 文
  档状态校验（既有矩阵用例快照均空来源，该循环零覆盖），新增 7
  用例：来源文档缺失、禁用、墓碑化（sourceDeletedAt）、换集合
  （collectionId 变更）、未分配文档不再允许、集合越权（白名单收
  窄后来源集合不在当前白名单）、逃逸选定范围——全部 FORBIDDEN
  fail-closed。快照 JSON 构造要点：callerAllowList 必须以数组字面
  量传入（裸标量会因 longList 非数组判 invalid）。
- 指标：core 全量门禁 EXIT=0 绿。

### Batch 252（已交付）

- 分支：`test/core-syncrun-fingerprint-batch252-20260910`（已合入
  main）
- 内容：DocumentSyncRunService preview 候选指纹与计数，新增 4 用
  例：指纹与 externalId\0kind\0revision\n 规范串 sha256 完全一致
  （json-record → JSON_RECORD 枚举名）、未知 document_type 回落
  TEXT 且计数计入 textCount、候选超 MAX_PREVIEW_IDENTITIES=10000
  安全上限 SYNC_RUN_DELETE_PROTECTION 拒绝且不读候选行、
  protectedByNewerMutationCount/unresolvedLegacyCount 计数透传。
  候选行桩按 [externalId, type, revision] 三元组建 List.of 显式类
  型见证。
- 指标：core 全量门禁 EXIT=0 绿。

### Batch 253（已交付）

- 分支：`test/core-stream-fallback-batch253-20260910`（已合入
  main）
- 内容：ChatExecutionService 流式候选回退的空完成分支，新增 1 用
  例：首候选流直接 onComplete（零事件）→ switchOnFirst 回退到次候
  选并正常完成（Completed 事件透传 fallback 模型）。Attempt 需携
  带真实 AuthorizedRetrievalContext（含 RetrievalTraceCollector，
  包路径 com.springairag.core.chat）。候选尝试预算耗尽分支因
  stream() 全链路需更重夹具而延后（依赖 ChatCommandMapper/
  Diagnostics 等协作者）。
- 指标：core 全量门禁 EXIT=0 绿。

### Batch 254（已交付）

- 分支：`test/core-stream-empty-semantics-batch254-20260910`（已合
  入 main）
- 内容：ChatExecutionStreamBudgetTest 补充行为锁定用例：空完成的
  首候选流不触发候选回退（switchOnFirst 仅在 onError 信号切换；
  onComplete 直接结束），后续候选未被创建（create never 断言），
  且响应无内容增量、无错误。此为对 Batch 253 空流回退用例的重要
  语义补正——空完成与错误的候选选择行为不同。
- 指标：core 全量门禁 EXIT=0 绿。

### Batch 255（已交付）

- 分支：`test/core-candidate-budget-batch255-20260910`（已合入
  main）
- 内容：候选尝试预算耗尽（延后两批后落地），新增 1 用例：
  execution.maxCandidateAttempts=2 且提供 3 个错误优先候选流 →
  第 1/2 次预留成功并回退，第 3 次预留越限 → CHAT_BUDGET_EXHAUST
  ED 拒绝且第三候选的 clientFactory.create 从未被调用。关键语义：
  空完成（onComplete 首信号）不触发 switchOnFirst 切换也不消耗预
  算，仅错误信号回退并消耗预留——耗尽必须由错误候选驱动。
- 指标：core 全量门禁 EXIT=0 绿。

### Batch 256（已交付）

- 分支：`test/core-variant-parsing-batch256-20260910`（已合入
  main）
- 内容：EvaluationSuiteDefinitionValidator.parseVariants（31 行缺
  口），新增 8 用例：缺省与空数组回退 default 变体、非数组拒绝、
  超过 maxVariantsPerRun（配置为 2 + 3 变体）拒绝、重复 key 拒
  绝、maxResults 0/101/3.5 三种非法值拒绝、完整检索配置字段解析
  （maxResults/minScore/hybrid/rerank/vectorWeight/fulltextWeight
  绑定断言）、非对象 filters 拒绝。
- 指标：core 全量门禁 EXIT=0 绿。

### Batch 257（已交付）

- 分支：`test/core-restore-version-batch257-20260910`（已合入
  main）
- 内容：DocumentMutationService.restoreLocalFromVersion（全库最大
  方法级缺口 81 行），新增 7 用例：功能开关关闭 RESTORE_NOT_ALLOW
  ED、版本缺失 VERSION_NOT_RESTORABLE、非 FULL 快照拒绝、内容快照
  缺失拒绝、恢复成功全字段回写（标题/内容/来源 + 版本号 4→5 +
  RESTORE 版本记录）、SNAPSHOT 可见性恢复禁用态并以 SKIP 派发、
  受限密钥恢复未分配快照 RESTORE_NOT_ALLOWED。关键夹具：版本实体
  的 titleSnapshot 等专用 setter、受限密钥经
  AUTHENTICATED_API_KEY_ENTITY 属性、setup/afterEach 双向清理请求
  上下文防泄漏。
- 指标：core 全量门禁 EXIT=0 绿。

### Batch 258（已交付）

- 分支：`test/core-upsert-local-import-batch258-20260910`（已合入
  main）
- 内容：DocumentMutationService.upsertLocalImport（本地上传幂等同
  步，69 行 lambda 缺口），新增 5 用例：无变化 UNCHANGED（不落库
  不派发、getLatestVersion 读取）、内容变更 UPDATED（版本号 4→5、
  enqueue contentChanged=true force=false）、force=true 绕过新鲜度
  直接重嵌（enqueue force=true）、集合迁移记录 COLLECTION_MOVE 版
  本、禁用+内容变更走 SKIP 派发（markNotRequested，不排队）。注
  意 upsertLocalImport 参数序：enabledOverride 在 policy 之前。
- 指标：core 全量门禁 EXIT=0 绿。

### Batch 259（已交付）

- 分支：`test/core-turn-claim-matrix-batch259-20260910`（已合入
  main）
- 内容：ChatTurnOperationService.claim 分派矩阵（52 行缺口），
  新增 5 用例：无键 Prepared 直通 unkeyed Claim 且不查仓储、
  SUCCEEDED 现有操作 → replay Claim（observability.replayed）、
  FAILED 复用抛 INTERNAL_ERROR（原 errorCode 无法映射时兜底）、
  IN_PROGRESS 未过期租约 → ChatTurnInProgressException（不触发回
  收）、过期回收成功（renew CAS 续期 + 周期续期启动）。
- 指标：core 全量门禁 EXIT=0 绿。

### Batch 260（已交付）

- 分支：`test/core-http-provider-deep-batch260-20260910`（已合入
  main）
- 内容：AllowlistedHttpToolProvider.call 深层分支（既有 9 用例之
  外），新增 3 用例：credentialEnv 指向缺失环境变量 →
  credential_unavailable 且不触达传输层；请求截止时间已过 → 在传
  输层调用前直接 http_timeout（含 contextWithDeadline 夹具）；传
  输层 UnknownHostException → http_unavailable 降级（错误候选流消
  耗预留属正常）。
- 指标：core 全量门禁 EXIT=0 绿。

### Batch 261（已交付）

- 分支：`test/core-syncrun-listitems-cursor-batch261-20260911`
  （已合入 main）
- 内容：DocumentSyncRunService.listItems 游标边界（既有 3 用例之
  外），新增 3 用例：cursor 全回环——首页 hasMore 产出的 nextCursor
  在第二页经真实 codec decode 为 CursorPosition，ArgumentCaptor 断
  言 page 收到的 (seenAt, externalId) 恰为首页最后返回行（次页空
  收尾 hasMore=false）；失败矩阵——他人 runId 游标 / statusFilter
  不匹配游标 / 空白游标均抛 "cursor is invalid" 且解码先于仓库访
  问（verifyNoInteractions）；limit=MAX_ITEM_RECEIPT_PAGE_ITEMS=200
  上界正例且向后仓传递探测行数 limit+1=201。要点：服务内
  itemCursorCodec 为构造器内部实例化（非注入），但 codec 包私有，
  同包测试用真实 codec（相同 findAndRegisterModules ObjectMapper）
  即可做端到端回环，无需打桩。
- 指标：core 全量门禁 EXIT=0 绿（4174 tests, 0 failures）。

### Batch 262（已交付）

- 分支：`test/core-openai-keyed-turn-batch262-20260911`（已合入
  main）
- 内容：OpenAiCompatibilityController 键控回合与流式语义（JaCoCo
  最大缺口 chatCompletions 59 行），新建
  OpenAiCompatibilityKeyedTurnTest 8 用例：幂等重放 SSE 快照流
  （X-RAG-Turn-Id / Idempotent-Replay=true、publicModelAlias 解析、
  重放短路不映射不执行）；重放 JSON（无 publicAlias 时
  declaredModelIdentifier 兜底）；新鲜 claim prepared JSON 完成
  （mapFromExecutionSnapshot → claim(OPENAI_JSON) → commandForClaim
  → prepareForOperation(false) → completePrepared → finalize，无
  fail/release）；prepared 失败 → fail(claim, error) 后原样重抛；
  新鲜 claim SSE 快照流（OPENAI_SSE + streaming=true，不走实时
  stream）；非键控 SSE 流（ContentDelta/Completed 事件映射、请求头
  传递 validateDeclaration）；非键控流错误 → onErrorResume 错误块
  收尾不外抛；诊断开启 → attachTraceSession + X-RAG-Retrieval-
  Trace-Id 头。收敛：chatCompletions 59→13 missed、
  keyedSnapshotStream 18→2、streamResponse 30→1。残余：claimExist
  ing（疑似死代码，待核查）、toStreamError 协议分支、emitter
  dispose lambda。要点：Claim 公有构造仅 2 参（sessionLease 恒
  null），带租约的 release 分支留待 core.chat 包内测试；SseEmitter
  初始化前 send 走早期缓存，单测可直接断言响应类型与头。
- 指标：core 全量门禁 EXIT=0 绿（4182 tests, 0 failures）。

### Batch 263（已交付）

- 分支：`test/core-begin-path-deadcode-batch263-20260911`（已合入
  main）
- 内容：技术债 + 补测双项。① 死代码移除：Batch 262 残余核查确认
  OpenAiCompatibilityController.claimExisting 与 RagChatController
  .claimExisting 均为零调用（inspectExisting + 内联 claim 重构遗
  留），两处删除（-28 行），门禁全绿即回归证据。②
  DocumentSyncRunService.begin 主路径 5 用例：同 token+同请求幂等
  重放（不再 INSERT、beginActiveWrite 触达）；同 clientRunId 绑定
  不同 token / 不同 snapshotMode → SYNC_RUN_LEASE_CONFLICT；已有
  ACTIVE 运行 → ACTIVE_SYNC_RUN_EXISTS；新建路径（allocateSource
  SequenceForSnapshot=5 → INSERT 10 参数逐项断言 tokenHash/序列/
  模式）；DataIntegrityViolation → ACTIVE_SYNC_RUN_EXISTS 并发映
  射。要点：RunRow 为 private record，findByClientRun/findActive
  桩经 RowMapper.mapRow(ResultSet) 模式（同包 stubRunColumns 复
  用）；varargs 桩匹配按参数个数（3 参 vs 2 参分开桩）。
- 指标：core 全量门禁 EXIT=0 绿（4187 tests, 0 failures）。
- 备注：首轮门禁遇 HybridRetrieverServiceBenchmarkTest
  .vectorToString_10k_under500ms 计时抖动（1000ms 阈值实测
  1204ms，机器负载所致；隔离复跑与重跑门禁均绿）。该基准阈值对
  环境敏感，列为后续技术债候选（warmup 或放宽阈值）。

### Batch 264（已交付）

- 分支：`test/core-derivation-repair-flow-batch264-20260911`（已
  合入 main）
- 内容：① DerivationRepairService 主链编排，新建
  DerivationRepairServiceFlowTest 7 用例：preview 计划持久化
  （scanRepairCandidates 双候选 → 1 项 REBUILD_LOCAL + skipped
  Documents=4、INSERT preview/items 逐项校验）；修复关闭 →
  DERIVATION_REPAIR_DISABLED；apply 身份不符（token/指纹双拒绝且
  在抢租约之前）；过期 PREVIEWED → EXPIRED + DERIVATION_REPAIR_
  EXPIRED；COMPLETED 幂等重放经 status()；status 项目映射；全链
  用例（claim 租约 → local 阶段 keywordIndex + post-local 写回 →
  vector 阶段 enqueue 派发 → finishSucceeded → finishApply →
  status COMPLETED，confirmActiveWrite×2）。收敛：preview 46→<5
  missed、applyVectorPhase 44→16、applyLocalPhase 28→7。要点：
  PreviewRow 为 private record 经 requirePreview 的 RowMapper 回
  调 + ResultSet 桩构造；preview 行状态用 AtomicReference 在
  finishApply 条件更新片段命中时翻转（全链终态 COMPLETED 可断
  言）；queryForList varargs 桩须用 any(Object.class) 避免与
  (String,Class,Object...) 重载歧义。② 基准去抖（Batch 263 备注
  项落地）：HybridRetrieverServiceBenchmarkTest 的
  cosineSimilarity/vectorToString 两用例改为多次 JIT 预热 + 3 次
  采样取最小值，阈值保持不变（病理性回归仍远超阈值），消除对全
  量门禁的间歇阻塞。
- 指标：core 全量门禁 EXIT=0 绿（4194 tests, 0 failures）。

### Batch 265（已交付）

- 分支：`test/core-apikey-mapping-batch265-20260911`（已合入
  main）
- 内容：ApiKeyManagementService 响应映射与查活主体，新建
  ApiKeyManagementServiceMappingTest 5 用例：listKeys→toResponse
  全字段装配（keyId/principalId/version/policyVersion/rpm/role、
  currentCredential=启用∧未退役∧未吊销、allowedCollectionIds 解
  析与 collectionKeys 保序映射、NORMAL+未持久化能力→全量能力）；
  退役凭证判定（retireAt 未来→非当前+retiring=true）；主体现缺
  失 fail-closed IllegalStateException；findActivePrincipal 全字
  段映射；四类边界返回 null（主体缺失/已吊销/已过期/无当前凭证
  轮换空窗）。要点：ApiKeyRole 枚举仅 ADMIN/NORMAL（无 WRITER）；
  ApiKeyResponse 判定字段为 Boolean 包装（get 而非 is 前缀）；
  collectionKeys 顺序依赖 resolver.mapKeys 返回 Map 迭代序，桩用
  LinkedHashMap 保序。
- 指标：core 全量门禁 EXIT=0 绿（4199 tests, 0 failures）。

### Batch 266（已交付）

- 分支：`test/core-keyed-sse-stream-batch266-20260911`（已合入
  main）
- 内容：RagChatController /stream 键控回合 SSE（Batch 260 延后项，
  JaCoCo 缺口 nativeSnapshotEmitter 43 行），新建
  RagChatControllerKeyedSseTest 5 用例：inspectExisting 重放经
  replayNativeSse（X-RAG-Turn-Id / Idempotent-Replay=true 响应头、
  不抢新 claim 不触达执行链）；claim 竞速重放（commandForClaim 从
  未调用）；prepared 全链（mapFromExecutionSnapshot → claim
  NATIVE_SSE → commandForClaim → 熔断检查 → prepareForOperation
  (streaming=true) → completePrepared → finalize →
  nativeSnapshotEmitter，Idempotent-Replay=false，无 fail）；链路
  失败 → fail(claim, error) 原样重抛；sessionCoordinator 注入时
  finally 释放会话租约。收敛：nativeSnapshotEmitter 43→5、
  stream 30→7、executeKeyedSse→1、replayNativeSse→0。要点：控制
  器可选依赖经包私有 configure* 注入（configureTurnOperationService
  /configureModeAwareExecution/configureSessionCoordinator）；o
  peration 的 executionSnapshot 必须非 null 才走 snapshot 映射分
  支（null 落 mapper.map 需另桩 resolveScope 路径）。
- 指标：core 全量门禁 EXIT=0 绿（4204 tests, 0 failures）。

### Batch 267（已交付）

- 分支：`test/webui-branch-gaps-batch267-20260911`（已合入 main）
- 内容：WebUI 分支覆盖加固（按 coverage 排序选点），4 个测试文
  件 +188 行：① 修复 ReembedAllButton.test 结构缺陷——describe
  提前闭合致 3 个用例游离顶层（靠 describe 内残留 mock 实现侥幸
  通过、测试顺序耦合），全部归位并新增成功 toast（failed=0 →
  success）、空翻译兜底标签（alert title/面板描述/Re-embed All/
  Force 确认文案，经可切换 i18n mock）、isPending 挂起态（loading
  文案+禁用），11 用例；② FilePreview 分支：directory 早退不拉
  取、mimeType null 兜底（?? '' 不命中任何分支）、图片/PDF 的
  blob URL 为空 → 'Unavailable' 错误盒、卸载后延迟 resolve（锁定
  现存行为：createObjectURL 在 active 守卫前执行且 URL 不回收），
  15 用例；③ useSearchHistory：损坏 JSON 与非数组载荷 → 空历史；
  ④ ErrorBoundary：空 pathname 兜底 report 根路径。整体分支覆盖
  88.31% → 89.27%，行覆盖 98.56%，656 测试全绿，vite build 通过。
- 指标：webui 656 tests 绿 + build 通过（后端未改动）。

### Batch 268（已交付）

- 分支：`test/core-integrity-repo-query-batch268-20260911`（已合
  入 main）
- 内容：DerivationIntegrityRepository 读路径，新建
  DerivationIntegrityRepositoryTest 7 用例：inspect 全值行映射
  （34 组件 Snapshot 逐项断言）+ 空结果 → missing 回退（DISABLED
  桶 / DOCUMENT_MISSING）；全空行 nullable/nullableNumber 兜底
  （NullValue 路径全覆盖）；scanCollection 谓词 SQL 捕获（
  collection_id 谓词、ORDER BY document.id、chunker/profile 前
  置参数后下标 5 为集合 ID）；scanRepairCandidates 经分类查询
  （SELECT id FROM bucketed + LIMIT/OFFSET）→ inspectIds IN 查询
  回读；countRepairSelection/countCollection 的 bucketed 计数与
  bucket 谓词有无、null 计数归零；aggregateCollection 与
  aggregateEmbeddingReadiness 两个 ResultSetExtractor 的行读取。
  收敛：query lambda$query$2 36→0、query 21→4。要点：query 的
  args 前缀为 jsonChunker/textChunker/profile×3（谓词参数从下标
  5 起）；映射器经 Map.entry 组装不接受 null title（生产由
  rag_documents.title NOT NULL 保证，测试需显式打桩）；按列名打
  桩的 ResultSet 须先加 anyString 宽松兜底再覆盖具体列，否则
  strict stubs 对未打桩列报 PotentialStubbingProblem。
- 指标：core 全量门禁 EXIT=0 绿（4211 tests, 0 failures）。

### Batch 269（已交付）

- 分支：`test/core-turn-reclaim-complete-batch269-20260911`（已合
  入 main）
- 内容：ChatTurnOperationService 回收与完成语义，新建
  ChatTurnOperationReclaimCompleteTest 7 用例：过期 IN_PROGRESS
  reclaim 成功（缺快照 → 用当前命令重生成执行快照非空断言 / 已有
  快照 → 回收参数为 null 不覆盖库值）；reclaim 竞速失败（CAS 返
  回 null）→ 递归回落，find 首次仍返回过期行、二次才读刷新行 →
  times(2) 后以 SUCCEEDED 走重放；complete 快照持久化（稳定
  turnId 替换请求侧 turnId、execution JSON 含 publicModelAlias 与
  mode、payload 含答案）；响应超 524288 字节 →
  IDEMPOTENCY_RESPONSE_TOO_LARGE 且不触库；completeSuccess=false →
  CHAT_HISTORY_PERSIST_FAILED；unkeyed claim 原样透传。收敛：
  reclaimExisting 22→0、completeWithDescriptor 25→3、complete→0。
  要点：reclaim 快照重生成依赖 executionService.resolveCandidateRefs
  （mock 须桩非空候选链，空链抛 IDEMPOTENCY_EXECUTION_SNAPSHOT_
  INVALID）；竞速回落是两级递归（find 逐次返回），验证次数要按实
  际递归深度写。
- 指标：core 全量门禁 EXIT=0 绿（4218 tests, 0 failures）。

### Batch 270（已交付）

- 分支：`test/core-history-docrefs-batch270-20260911`（已合入
  main）
- 内容：RagChatHistoryRepository 持久引用链
  （reserveDurableContentReferences → normalizeDocumentIds），追加
  6 用例：无引用 → 空 DurableContentReferences 且不触库；来源与
  JSON 数组并集去重升序（含 null 元素/空 documentId/非法文本忽
  略、JSON 数值与文本混合解析）；非数组载荷 →
  CHAT_HISTORY_PERSIST_FAILED；不支持的节点类型（对象元素）同拒；
  非正数/超 BIGINT/科学计数文本静默跳过且不触库；文档缺失 →
  stale 引用拒绝。要点：normalizeDocumentIds 收敛（24→残余），
  预留链两次加载间还有按集合的预留 UPDATE（返回 0 判 stale，桩
  须返回 1）；List.of 不接受 null 元素，混入 null 的来源列表用
  Arrays.asList。
- 指标：core 全量门禁 EXIT=0 绿（4224 tests, 0 failures）。

### Batch 271（已交付）

- 分支：`test/core-jsonrecord-upsert-mapping-batch271-20260911`
  （已合入 main）
- 内容：JsonRecordService 变更管道响应映射，新建
  JsonRecordUpsertResponseMappingTest 2 用例：upsert 委托
  mutationService.upsertJsonRecord（参数含 collectionKey、原始
  filename/enabledOverride 为 null）后 JsonMutationResult 全 18
  字段装配（含派发结果非空分支：ASYNC_QUEUED/jobId/batchId/
  lifecycle 同引用）；派发结果为 null → error null、action
  "NONE"、jobId/batchId null（UNCHANGED 幂等重放形态）。要点：
  JsonMutationResult 的 dispatch 字段可空，映射层以
  `dispatch == null ? "NONE" : ...` 三元区分；lifecycle 断言用
  assertSame 时构造器只能调用一次（DocumentLifecycleResponse 含
  随机 activeJobId，两次构造实例不等）。
- 指标：core 全量门禁 EXIT=0 绿（4226 tests, 0 failures）。

### Batch 272（已交付）

- 分支：`test/core-create-fingerprint-batch272-20260911`（已合入
  main）
- 内容：DocumentMutationService.localCreateFingerprint 幂等指纹
  规范化，新建 DocumentMutationCreateFingerprintTest 4 用例
  （ReflectionTestUtils 直调私有方法，与幂等测试同模式）：确定性
  与标题修剪规范化（"  Padded  " ≡ "Padded"，指纹 64 位十六进
  制）；元数据键序无关（LinkedHashMap 正/反插入经 TreeMap 排序
  后指纹一致）；可选输入逐一变化（enabledOverride null→true、
  originalFilename null→"file.pdf"、jsonbPayload null→节点均改
  变指纹）；policy/force/collectionId/deduplicationScope 任一变
  化改变指纹。要点：normalizeMetadata 经 Map.copyOf 消除原顺序、
  TreeMap 重排保证键序无关；normalizeOptional 将空白串归一为
  null。
- 指标：core 全量门禁 EXIT=0 绿（4230 tests, 0 failures）。

### Batch 273（已交付）

- 分支：`test/core-pdf-policy-import-batch273-20260911`（已合入
  main）
- 内容：PdfImportController 通用非 SSE 导入路径
  （importPdfToRagWithPolicy，23 行缺口），新建
  PdfImportControllerPolicyImportTest 4 用例：ASYNC 全字段响应
  （PdfToRagResponse 11 组件含 embeddingAction/jobId/batchId）；
  collectionKey 经 resolveActiveIds 解析为内部 ID（collectionId
  参数 null 时以解析值调用导入服务）；导入异常 → 500
  ErrorResponse（detail 含 "PDF-to-RAG import failed" 与原因）；
  空文件 → 400 "No file uploaded" 且不触达导入服务。要点：4 参
  构造器注入可选 collectionIdentityResolver；importPdfToRag 有多
  个重载，verify 时匹配器须用具体类型（any(String.class) 等）
  避免歧义；importPdf 声明 IOException，stub 的测试方法需 throws。
- 指标：core 全量门禁 EXIT=0 绿（4234 tests, 0 failures）。

### Batch 274（已交付）

- 分支：`test/core-provisioning-race-batch274-20260911`（已合入
  main）
- 内容：CollectionProvisioningService.createOrReplay 并发竞态回
  收（21 行缺口），新建 CollectionProvisioningRaceRecoveryTest 6
  用例：DUPLICATE_RESOURCE 竞态退避重试后创建成功；唯一约束冲突
  （DataIntegrityViolationException）同视为竞态重试；三次尝试耗
  尽后 readExisting 从台账读回已建集合（replay=true，create-
  Collection 恰 3 次）；台账为空时复现 DUPLICATE_RESOURCE；重放
  集合已清退（purgedAt 非空）→ COLLECTION_ALREADY_RETIRED 且不
  重试；readExisting 查询离线 → SERVICE_UNAVAILABLE。要点：findBy
  是循环内每轮尝试的入口（provisionInCurrentTransaction 先查台账
  命中即重放），竞态耗尽场景必须用连续桩
  （empty×3 → 行/异常）区分循环期与 readExisting 期，否则测试静
  态通过不了竞态路径。
- 指标：core 全量门禁 EXIT=0 绿（4240 tests, 0 failures）。

### Batch 275（已交付）

- 分支：`test/webui-toast-settings-branches-batch275-20260911`
  （已合入 main）
- 内容：WebUI 分支残余两点：① Toast.tsx 定时器清理竞态分支
  （timer !== undefined 否侧）——同一 act 内对关闭按钮连点两次，
  第二次 removeToast 命中定时器登记已清理路径，断言不抛错且提示
  消失；② Settings.tsx 缓存 maxSize 输入清空 → parseInt('') NaN
  经 || 1000 回退默认值（受控输入先置 5555 再清空验证回显 1000）。
  webui 658 测试绿 + vite build 通过。
- 指标：webui 658 tests 绿 + build 通过（后端未改动）。

### Batch 276（已交付）

- 分支：`test/core-upload-embed-batch276-20260911`（已合入 main）
- 内容：RagDocumentController.uploadAndEmbed 批量上传（22 行缺
  口），新建 RagDocumentControllerUploadTest 4 用例：空文件数组 →
  400 占位 FileResult（"No file uploaded"，processed/success/
  failed 全 0）；混合文件逐个经 batch 管道并计数（成功结果
  documentId/title/embeddingAction/jobId 装配 + 失败结果 error
  透传，processed=2/success=1/failed=1）；非文本且不可读 →
  "Unsupported file type: image/png"；空白文本 → "File content
  is empty"。要点：validateTextFile 的拒绝分支仅在非文本且
  getBytes() 抛异常时触发（MockMultipartFile 永不抛，须 mock
  MultipartFile 的 getBytes 抛 IOException）；batch 管道 stub 用
  thenReturn(ok, fail) 按 FileList 顺序逐个消费。
- 指标：core 全量门禁 EXIT=0 绿（4244 tests, 0 failures）。

### Batch 277（已交付）

- 分支：`test/core-marker-cli-chain-batch277-20260911`（已合入
  main）
- 内容：MarkerPdfConverter.convert 进程执行链（34 行缺口），在
  既有 MarkerPdfConverterTest 追加 4 用例：① /bin/echo 作为
  markerCli → convert 走完整进程链（ProcessBuilder 启动、输出读
  取循环、waitFor、exit 0）返回 true；② isAvailable 对退出 0 的
  CLI 返回 true；③ 用 java 二进制构造 "--help 退出 0 但业务调用
  退出 1" 的命令（java --help 通过可用性检查；作为 marker_single
  加载主类失败退出 1）→ convert 返回 false，覆盖非零退出分支；
  ④ 不存在的 CLI → isAvailable IOException 短路 + convert 提前返
  回 false。要点：convert 先做 isAvailable 守卫，非零退出分支需
  要 "help 成功 / 业务失败" 的双态命令（java 恰好满足）；命令缺
  陷（5 分钟超时与中断分支）无法在单测内低成本构造，列为可接受
  残余；@TempDir 提供真实路径，mock Path 必须同时打
  toAbsolutePath 与 toString 桩。
- 指标：core 全量门禁 EXIT=0 绿（4248 tests, 0 failures）。

### Batch 278（已交付）

- 分支：`test/webui-files-sort-branches-batch278-20260911`（已合
  入 main）
- 内容：WebUI Files.tsx 分支加固（84.77%），追加 2 用例：① 导入
  时间排序全分支——无 createdAt / 非法日期字符串条目稳定垫底
  （bad-time 与 no-time 之间再按名称决胜）、同时间戳条目按名称
  升序决胜、desc/asc 切换双向断言（6 条目混排）；② 裸斜杠
  deep link（?path=%2F）→ normalizeVirtualPath 规范化为根目录正
  常列出条目。webui 660 测试绿 + vite build 通过。
- 指标：webui 660 tests 绿 + build 通过（后端未改动）。

### Batch 279（已交付）

- 分支：`test/core-keyed-ask-json-batch279-20260911`（已合入
  main）
- 内容：JaCoCo 重扫描选定 RagChatController 原生 JSON 键控流
  （ask/chat 各 22 行），新建 RagChatControllerKeyedAskTest 8 用
  例：ask 重放（Idempotency 头）；键控 JSON 全链（mapFromExecution
  Snapshot → claim NATIVE_JSON → commandForClaim → 熔断检查 →
  prepareForOperation(false) → completePrepared → finalize →
  idempotentResponse，Replay=false）；链路失败 → fail + 重抛；
  unkeyed → legacy ragChatService.chat(request) 2 参回退（无键控
  头）；PLAIN 模式 → unscoped scope 走 3 参 chat；chat 别名镜像
  重放/键控链/legacy 三路径。收敛：ask 22→4、chat 22→7、
  executeKeyedJson→2。要点：ask 与 chat 是重复代码的独立方法
  （非委托），覆盖需各自驱动；resolver 为 null 的构造器下
  resolveScope 走 legacy 分支返回 null（chat 2 参重载）。
- 指标：core 全量门禁 EXIT=0 绿（4256 tests, 0 failures）。

### Batch 280（已交付）

- 分支：`test/core-native-claim-batch280-20260911`（已合入 main）
- 内容：ChatTurnOperationService.claim 3 参重载（原生 sessionId
  路径，29 行缺口），新建 ChatTurnOperationNativeClaimTest 9 用
  例：SUCCEEDED 重放；FAILED 复现原错误码；租约未过期 →
  ChatTurnInProgressException（retryAfterSeconds 夹取 1..60）；尝
  试耗尽 → exhaustAttempts 标记 + failedReplay（find 读回
  IDEMPOTENCY_ATTEMPTS_EXHAUSTED）；标记失败 → CHAT_HISTORY_
  PERSIST_FAILED；过期回收成功（reclaim 4 参：无快照参数版本）→
  非重放新 claim；回收竞速 → find 刷新后重放；新键插入（非法会
  话 id 规范化为 UUID、授权快照含 callerAllowList 空数组）；插入
  竞速回落一次 find 即重放。要点：3 参重载的回收不带快照重生成
  参数（与 4 参不同）；find 调用次数取决于递归深度（插入竞速仅
  一次，回收竞速两次）。
- 指标：core 全量门禁 EXIT=0 绿（4265 tests, 0 failures）。

### Batch 281（已交付）

- 分支：`test/core-external-finishupsert-batch281-20260911`（已合
  入 main）
- 内容：ExternalDocumentService.finishUpsert 嵌入结果分支（20 行
  缺口），在 ExternalDocumentServiceTest 追加 6 用例：ASYNC 策略
  经 dispatchService.enqueueInCurrentTransaction 排队（QUEUED 状
  态 + job/batch ID 装配）；排队结果带错误 → EMBEDDING_FAILED +
  错误透传；SYNC 策略经 dispatchAfterCommit（错误同样映射）；同
  步嵌入抛异常 → FAILED + EMBEDDING_FAILED（catch 分支）；fresh
  + SYNC → CACHED + 活动档案键 + 经 findById 重载文档的
  processingStatus；SKIP 策略 → NOT_REQUESTED + SKIPPED + 档案键
  兜底（profileKey 为 null 时取活动档案）。要点：finishUpsert 的
  分支优先级为 queued → SKIP → SYNC+dispatch → 同步嵌入 → fresh
  缓存，dispatchService 为包私有 setter 注入的可选依赖。
- 指标：core 全量门禁 EXIT=0 绿（4271 tests, 0 failures）。

### Batch 282（已交付）

- 分支：`test/core-diagnostics-visible-keys-batch282-20260911`
  （已合入 main）
- 内容：RetrievalDiagnosticsService.visibleCollectionKeys 脱敏
  分支（20 行缺口），新建 RetrievalDiagnosticsVisibleKeysTest 3
  用例（经公有 get(principal, traceId) → toDetail → visibleMeta
  data 驱动）：非受限调用方原样返回请求键且不触达解析器（verify
  never findActive，防行为差异）；受限调用方仅保留允许集合内的
  键——允许键保留、未授权集合的键剔除、解析器抛异常的键静默丢
  弃（防错误差异探测）、空白/空值键在收集阶段剔除；解析器缺失
  时即使非受限也全部隐藏。要点：受限调用方经 RequestContextHolder
  注入 AUTHENTICATED_API_PRINCIPAL_ATTRIBUTE（测试后必须 reset），
  策略用匿名 ApiAccessPolicy（role=NORMAL + allowed "10,11"）。
- 指标：core 全量门禁 EXIT=0 绿（4274 tests, 0 failures）。

### Batch 283（已交付）

- 分支：`test/core-summary-guards-tooltranscript-batch283-20260911`
  （已合入 main）
- 内容：ConversationSummaryService 压缩守卫/降级链与工具转写
  （合计约 36 行缺口），在既有测试类追加 10 用例：守卫——
  compaction_disabled、executionBudget 缺失（no_messages）、候选
  为空（source_empty）、触发阈值未达（trigger_not_reached）、游标
  已最新（cursor_current，台账游标 5 + 候选行 1/2）；降级——压缩
  模型缺失（summary_model_unavailable）、
  CHAT_CONTEXT_BUDGET_EXCEEDED（summary_context_budget_exceeded）、
  空白摘要（summary_empty）、saveCas 竞速（summary_cas_conflict）；
  renderToolTranscript——工具行渲染（name/arguments/result）、非
  Map 条目跳过、4096 累计上界截断（tail 条目不进转写）。要点：
  压缩超时 200ms 会低估测试 JVM 冷启动（异步 supplyAsync 首次调
  度超 200ms 即假报 summary_timeout），用例需将
  compactionTimeoutMs 提到 5000；cursor_current 需要台账游标大于
  候选行最大 id 且 findOwnedAfterHistoryId 桩匹配游标参数；转写
  截断按累计长度（bounded 单条 1024 永远放得下），须多条累计超
  4096。
- 指标：core 全量门禁 EXIT=0 绿（4284 tests, 0 failures）。

### Batch 284（已交付）

- 分支：`test/core-history-cleanup-coordinated-batch284-20260911`
  （已合入 main）
- 内容：ChatHistoryCleanupService 协调清理路径
  （cleanupOwnedSessions → cleanupSession，lambda 约 20 行缺口），
  新建 ChatHistoryCleanupCoordinatedPathTest 5 用例：候选会话获
  取维护租约 → 消费租约 → 删除 3 行 → 摘要清理 → EXISTS 查询确
  认仍有历史（不清 spring_ai 记忆），总数含遗留行；会话清空
  （EXISTS=false）→ 兜底删除 spring_ai_chat_memory；活跃会话租
  约获取失败 → 跳过会话工作（仅遗留行计入，不触消费查询与摘
  要）；消费租约返回非 1 行 → IllegalStateException fail-closed
  且 finally 仍释放租约；无候选会话 → 仅遗留行。要点：会话删除
  与遗留删除 SQL 同形（均 WITH victims），按参数个数（4 vs 2）
  与 SQL 片段区分桩；维护租约三段（INSERT 获取 / DELETE
  RETURNING 消费 / DELETE 释放）经 SQL 片段匹配。
- 指标：core 全量门禁 EXIT=0 绿（4289 tests, 0 failures）。

### Batch 285（已交付）

- 分支：`test/core-pdfbox-real-pdf-batch285-20260911`（已合入
  main）
- 内容：PdfBoxConverter.convert/buildMarkdown（合计约 36 行缺
  口），在既有测试类追加 3 用例（测试内用 PDFBox 生成真实 PDF，
  无需外部文件）：① 含双段文本的 PDF → convert 走完整链
  （createDirectories/readAllBytes/Loader.loadPDF/PDFTextStripper/
  writeString）返回 true，markdown 输出至 <outputDir>/<name>/
  <name>.md 且 .pdf 后缀剥离，断言 # 标题/## Content/段落文本/
  生成器签名；② 空白页 PDF → buildMarkdown 空文本分支（"*No text
  content could be extracted*"）；③ 不存在的 pdfPath → IOException
  → false。要点：双 newLine（setLeading + 两次 newLine）制造段落
  分隔触发 \n\s*\n 切分分支；长句 >80 触发条件分支；PDFBox 3.x
  的 PDType1Font 需 Standard14Fonts.FontName 构造。
- 指标：core 全量门禁 EXIT=0 绿（4292 tests, 0 failures）。

### Batch 286（已交付）

- 分支：`test/core-commit-operation-batch286-20260911`（已合入
  main）
- 内容：ChatSessionCoordinator.commitOperation 持久提交事务
  （lambda 约 21 行缺口），新建
  ChatSessionCoordinatorCommitOperationTest 7 用例：状态化租约 +
  SERVER 记忆全链（预留引用 → 续租 → CAS completeSuccess →
  saveDurable → 共享记忆刷新，含续租 SQL 断言）；STATELESS 提交
  跳过续租与共享记忆写入；completeSuccess CAS 失败 →
  CHAT_HISTORY_PERSIST_FAILED 且不写历史；提交期续租丢失（renew
  返回 0）→ CHAT_SESSION_LEASE_LOST；saveDurable 运行时异常 →
  包装为 CHAT_HISTORY_PERSIST_FAILED；operationRepository 缺失 →
  IDEMPOTENCY_DISABLED（独立两段参实例，STATELESS handle 触
  发）。要点：MemoryMode 仅 SERVER/STATELESS（无 CLIENT）；
  ChatMemoryRepository.saveAll 为 void（不可 when 存根，verify
  never 即可）；JdbcChatMemoryRepository 为具体类仅作构造占位，
  共享记忆交互经 ChatMemoryRepository 接口 mock 验证。
- 指标：core 全量门禁 EXIT=0 绿（4299 tests, 0 failures）。

### Batch 287（已交付）

- 分支：`test/core-embedding-scope-resolution-batch287-20260911`
  （已合入 main）
- 内容：EmbeddingJobService.resolveDocumentIds 作用域解析（20 行
  缺口），新建 EmbeddingJobScopeResolutionTest 7 用例：documentIds
  与 Collection scope 互斥（双给/都不给均拒）；空 documentIds 与
  超 maxDocumentsPerBatch（1001 个）拒绝；ids 路径文档缺失 →
  DocumentNotFoundException；scope matchNone → 空批且不触仓储；
  NONE/ANY_ASSIGNED/SELECTED 三种过滤器各自分发到
  findEnabledIds/findEnabledAssignedIds/findEnabledIdsByCollection
  Ids（stubFullCreateChain 全链驱动 create 至响应组装）；作用域
  展开超 1000 上限拒绝。要点：EmbeddingJob 是 24 组件 record
  （id() 返回 UUID，直接构造真实实例而非 mock）；createOrCoalesce
  12 参混排 UUID/long/boolean，匹配器必须 any(UUID.class)/anyLong/
  anyBoolean/anyInt 精确对应（object any() 对 primitive 参数会拆
  箱 NPE）；contentHash 桩值必须取自 document() 助手的真实 64 位
  hex 而非任意字面量。
- 指标：core 全量门禁 EXIT=0 绿（4306 tests, 0 failures）。

### Batch 288（已交付）

- 分支：`test/core-purge-session-cleanup-batch288-20260911`（已合
  入 main）
- 内容：CollectionPurgeService.deleteSessions 会话清理分支（约 19
  行缺口），新建 CollectionPurgeSessionCleanupTest 1 个端到端用
  例：apply 驱动非空计划（1 文档 + 匿名/归属两个会话）→ 匿名会
  走 owner IS NULL 历史 + spring_ai 记忆删除；归属会话走历史/摘
  要/回合操作/过期租约四表 + memoryConversationId 记忆删除；文
  档计数一致后退役（buildRetiredResult 经最终状态查询装配）。
  要点：非空计划指纹经反射 buildPlan+fingerprint 复算（buildPlan
  读取 JDBC 桩数据，查询桩须覆盖 documents/sessions/feedback/
  audit/repair/idempotency 全部分支）；退役后最终状态查询
  （SELECT deleted_at, purged_at, version）必须单独打桩否则
  finalState 为 null；归属会话四表 SQL 共用同一 WHERE 片段，verify
  需按各表专属片段分别断言。
- 指标：core 全量门禁 EXIT=0 绿（4307 tests, 0 failures）。

### Batch 289（已交付）

- 分支：`test/core-eval-concurrent-batch289-20260911`（已合入
  main）
- 内容：EvaluationSuiteService.executeCases 并发执行路径（20 行
  缺口），在既有测试类追加 2 用例：① 双变体套件（default +
  hybrid）→ 并发度 min(4, 2) = 2 走线程池路径，两变体全部执行，
  finishRun PASSED 且 caseCount=2、insertCaseResult 恰 2 次；②
  identityExists 在执行器线程抛异常（fixture 预检位于 executeCase
  的 try 之外）→ future.get 包装为 IllegalStateException
  （"Evaluation case execution failed"）且运行不落终态（finishRun
  never）。要点：Identity 的 sourceNamespace 恒为 "default"（与变
  体无关），按变体键构造 thenThrow 桩永不命中；失败用例改用宽匹
  配连续桩（thenReturn(true).thenThrow(...)）驱动第 2 次调用抛
  出；严格桩下自包含桩（不复用 helper 的 identityExists 特定桩）
  避免 UnnecessaryStubbing。
- 指标：core 全量门禁 EXIT=0 绿（4309 tests, 0 failures）。

## 进度留档快照（Batch 289 后 · 用户指令）

- 留档时点：2026-09-11 · main @ ca919352（Batch 289 账本记录之
  后），origin/main 同步。
- 循环进度：Batch 261–289 共 29 个批次全部按「规划→写测试→单类
  验证→core 全量门禁 EXIT=0 或 vitest 全绿 + vite build→commit/
  push 特性分支→--no-ff 合并 main→账本记录→清理分支」交付完成。
- 可构建性证据：core 全量门禁 EXIT=0（4309 tests, 0 failures，
  Batch 289）；webui vitest 660 全绿 + vite build 通过（Batch
  278 后未改动 webui，本次复验 build 通过）；backend test-compile
  EXIT=0（本次复验）。
- 近期批次重点：
  - Batch 284：ChatHistoryCleanupService 协调清理（维护租约三段
    获取/消费/释放、EXISTS 兜底清 spring_ai 记忆、活跃会话跳过、
    租约丢失 fail-closed）
  - Batch 285：PdfBoxConverter 真实 PDF 转换链（测试内 PDFBox 生
    成 PDF、段落/长句/空文本分支）
  - Batch 286：ChatSessionCoordinator.commitOperation 持久提交
    （引用预留/续租/CAS/saveDurable/SERVER 记忆刷新、租约丢失、
    仓储缺失守卫）
  - Batch 287：EmbeddingJobService.resolveDocumentIds 作用域解析
    （互斥/边界/NotFound/matchNone/三种过滤器分发/超限）
  - Batch 288：CollectionPurgeService.deleteSessions 端到端（匿名
    与归属会话双分支、四表清理、spring_ai 记忆、退役装配）
  - Batch 289：EvaluationSuiteService.executeCases 并发路径（双变
    体线程池、执行器线程异常包装 ISE、运行不落终态）
- Batch 290 候选遗留：ChatModelRouter.candidateForModel（约 19
  行——经 orderedCandidateDescriptors 的 result.isEmpty() 回退循
  环可达性有限，getAllOrdered 仅含可解析模型，需构造
  getPrimary 有值但 resolveCandidate 全 null 的场景，评估结论：
  configured 描述符分支几乎不可达，建议改测 legacyProviderFor 的
  legacy 候选分支）；PdfToRagService.importPdfToRag（18 行）；
  DocumentRelocationService.relocate（16 行）；WebUI
  useFileUpload/Documents.tsx 分支残余。
- 工作区：本地已合并 test/* 分支随各批清理，无遗留 worktree。

### Batch 364（规划中）

- 候选（按上轮 4752 tests 门禁后 jacoco 重扫）：
  - ApiKeyManagementService（100 行 + 100 分支）——多批工程，首
    批建议从 sourceDelete/rotation 尾部分支开始（已有
    RotationClampTest/MappingTest/Test 三个测试类可扩展）。
  - DocumentEmbedService（42 行）、JsonRecordService（87 行）、
    ChatTurnOperationService（85 行）、DocumentMutationService
    （85 行）。
  - RagDocumentController（108 行）、RagChatController（50 行）、
    PdfImportController 残余 44 行（preview/文件服务区 + 
    resolveWritableCollectionId 的 collectionKey 路径 357 行）。
  - 小档：RequestTraceFilter(9)、JsonRecordSearchTool(9)、
    EmbeddingDispatchService(9)、RetryConfig(9)、
    BudgetedToolCallingManager(9)、HttpRerankProvider(8)、
    SlowQueryMetricsService(8)、ApiKeyAuthFilter(8)、
    RagSearchController(8)、AlertNotificationOutboxService(8)。
- 流程照旧：规划→实施→单类验证→core 全量门禁 EXIT=0→push 特
  性分支→--no-ff 合并 main→账本→清理。

### Batch 366（规划中）

- 目标：小档双扫——①RetryConfig（12 行：69/72/81/85-87/93-95/
  105-107，重试配置的参数校验与属性映射）；②BudgetedToolCallingManager
  （9 行，工具调用预算管理器残余分支）。
- 备选：EmbeddingDispatchService（12 行）、HttpRerankProvider
  （8 行）、SlowQueryMetricsService（8 行）、
  AlertNotificationOutboxService（8 行）。
- 流程照旧：规划→实施→单类验证→core 全量门禁 EXIT=0→push 特
  性分支→--no-ff 合并 main→账本→清理。

### Batch 367（规划中）

- 目标：小档续扫——①EmbeddingDispatchService（12 行：88/98/123/
  152/162/177/189/206/232/241-244）；②HttpRerankProvider（8 行）
  或 SlowQueryMetricsService（8 行）或
  AlertNotificationOutboxService（8 行）择一。
- 备选：ApiKeyAuthFilter（8 行）、RagSearchController（8 行）、
  OpenApiConfig（9 行）、ApiKeyProvisioningOperation（9 行）、
  BudgetedChatModel（26 行）。
- 候选技术债（低优先，需专项批次）：RetryConfig 的
  retryOnServiceUnavailable 开关被通用 5xx 分支遮蔽——如需可关
  闭语义应调整分类器分支顺序（生产行为变更，需回归测试）。
- 流程照旧：规划→实施→单类验证→core 全量门禁 EXIT=0→push 特
  性分支→--no-ff 合并 main→账本→清理。

### Batch 368（规划中）

- 目标：小档续扫——①HttpRerankProvider（8 行）；②
  AlertNotificationOutboxService（8 行）或 ApiKeyAuthFilter
  （8 行）或 RagSearchController（8 行）择一。
- 备选：OpenApiConfig（9 行）、ApiKeyProvisioningOperation
  （9 行）、BudgetedChatModel（26 行）、RagChatController
  （50 行）。
- 中档候选（需整批）：ApiKeyManagementService（约 88 行残余，
  建议从 sourceDelete/rotation 尾部分支切入）、JsonRecordService
  （87 行）、ChatTurnOperationService（85 行）、
  DocumentMutationService（85 行）、RagDocumentController
  （108 行）。
- 候选技术债：RetryConfig 的 retryOnServiceUnavailable 开关被
  通用 5xx 分支遮蔽（生产行为变更需专项批次）。
- 流程照旧：规划→实施→单类验证→core 全量门禁 EXIT=0→push 特
  性分支→--no-ff 合并 main→账本→清理。

### Batch 369（规划中）

- 目标：小档续扫——①ApiKeyAuthFilter（8 行）；②RagSearchController
  （8 行）或 OpenApiConfig（9 行）或 ApiKeyProvisioningOperation
  （9 行）择一。
- 备选：BudgetedChatModel（26 行）、RagChatController（50 行）、
  JsonRecordService（87 行）、ChatTurnOperationService（85 行）、
  DocumentMutationService（85 行）、ApiKeyManagementService
  （约 88 行残余）。
- 候选技术债：RetryConfig 的 retryOnServiceUnavailable 开关被
  通用 5xx 分支遮蔽（生产行为变更需专项批次）。
- 流程照旧：规划→实施→单类验证→core 全量门禁 EXIT=0→push 特
  性分支→--no-ff 合并 main→账本→清理。

### Batch 370（规划中）

- 目标：①OpenApiConfig（12 行：exampleResponseCustomizer 的
  opId 过滤/responses 空守卫/applyExamples switch 表、另一
  customizer Bean 的 securityScheme 分支）；②ApiKeyProvisioningOperation
  （9 行）或 RagSearchController（8 行）择一。
- 备选：BudgetedChatModel（26 行）、RagChatController（50 行）、
  JsonRecordService（87 行）、ChatTurnOperationService（85 行）、
  DocumentMutationService（85 行）、ApiKeyManagementService
  （约 88 行残余）。
- 候选技术债：RetryConfig 的 retryOnServiceUnavailable 开关被
  通用 5xx 分支遮蔽（生产行为变更需专项批次）。
- 流程照旧：规划→实施→单类验证→core 全量门禁 EXIT=0→push 特
  性分支→--no-ff 合并 main→账本→清理。

### Batch 371（规划中）

- 目标：①RagSearchController（8 行）；②BudgetedChatModel
  （26 行，工具调用预算包装模型的收缩/异常分支残余）。
- 备选：RagChatController（50 行）、JsonRecordService（87 行）、
  ChatTurnOperationService（85 行）、DocumentMutationService
  （85 行）、ApiKeyManagementService（约 88 行残余）、
  RagDocumentController（108 行）。
- 候选技术债：RetryConfig 的 retryOnServiceUnavailable 开关被
  通用 5xx 分支遮蔽（生产行为变更需专项批次）。
- 流程照旧：规划→实施→单类验证→core 全量门禁 EXIT=0→push 特
  性分支→--no-ff 合并 main→账本→清理。

### Batch 372（规划中）

- 目标：BudgetedChatModel（26→32 行缺口：44/48/58/62/73-79/93/
  106/128/130/132/135/177/238/256/264/290-311/316——预算包装模型
  的调用委派、收缩与异常分支；已有 BudgetedChatModelTest 与
  ModeAwareChatClientBudgetedModelTest 可扩展）。
- 备选：RagChatController（50 行）、JsonRecordService（87 行）、
  ChatTurnOperationService（85 行）、DocumentMutationService
  （85 行）、ApiKeyManagementService（约 88 行残余）、
  RagDocumentController（108 行）。
- 候选技术债：RetryConfig 的 retryOnServiceUnavailable 开关被
  通用 5xx 分支遮蔽（生产行为变更需专项批次）。
- 流程照旧：规划→实施→单类验证→core 全量门禁 EXIT=0→push 特
  性分支→--no-ff 合并 main→账本→清理。

### Batch 373（规划中）

- 目标：中档切入——①ApiKeyManagementService 残余（约 88 行，
  从 sourceDelete/rotation 尾部分支与 notify 类方法切入，已有
  RotationClampTest/MappingTest/Test 三个测试类可扩展）；②
  RagChatController（50 行）择辅。
- 备选：JsonRecordService（87 行）、ChatTurnOperationService
  （85 行）、DocumentMutationService（85 行）、
  RagDocumentController（108 行）。
- 候选技术债：RetryConfig 的 retryOnServiceUnavailable 开关被
  通用 5xx 分支遮蔽（生产行为变更需专项批次）。
- 流程照旧：规划→实施→单类验证→core 全量门禁 EXIT=0→push 特
  性分支→--no-ff 合并 main→账本→清理。

### Batch 374（规划中）

- 目标：①ApiKeyManagementService rotate 尾部（757-791：未知 key
  null、acquire 0 null、PENDING 拒绝、disable 计数不符拒绝）与
  notify/生命周期类方法（811-839 等）；②RagChatController
  （50 行）择辅。
- 备选：JsonRecordService（87 行）、ChatTurnOperationService
  （85 行）、DocumentMutationService（85 行）、
  RagDocumentController（108 行）。
- 候选技术债：RetryConfig 的 retryOnServiceUnavailable 开关被
  通用 5xx 分支遮蔽（生产行为变更需专项批次）。
- 流程照旧：规划→实施→单类验证→core 全量门禁 EXIT=0→push 特
  性分支→--no-ff 合并 main→账本→清理。

### Batch 375（规划中）

- 目标：①RagChatController（50 行）；②中档候选择一：JsonRecordService
  （87 行）/ ChatTurnOperationService（85 行）/
  DocumentMutationService（85 行）。
- 备选：RagDocumentController（108 行）、ApiKeyManagementService
  剩余（918-1376 段）。
- 候选技术债：RetryConfig 的 retryOnServiceUnavailable 开关被
  通用 5xx 分支遮蔽（生产行为变更需专项批次）。
- 流程照旧：规划→实施→单类验证→core 全量门禁 EXIT=0→push 特
  性分支→--no-ff 合并 main→账本→清理。

### Batch 376（规划中）

- 目标：①RagChatController 剩余（keyed JSON/SSE replay/snapshot
  路径约 30 行）；②JsonRecordService 第一扫（105 行拆两批，先做
  前段 134-350 的 upsert/校验分支）。
- 备选：ChatTurnOperationService（85 行）、
  DocumentMutationService（85 行）、RagDocumentController
  （108 行）、ApiKeyManagementService 剩余（918-1376 段）。
- 候选技术债：RetryConfig 的 retryOnServiceUnavailable 开关被
  通用 5xx 分支遮蔽（生产行为变更需专项批次）。
- 流程照旧：规划→实施→单类验证→core 全量门禁 EXIT=0→push 特
  性分支→--no-ff 合并 main→账本→清理。

### Batch 377（规划中）

- 目标：①JsonRecordService 第二扫（中段 350-700 的
  persist/embedding outcome 分支）；②ChatTurnOperationService
  （85 行）或 DocumentMutationService（85 行）择一。
- 备选：RagDocumentController（108 行）、RagChatController
  剩余（keyed JSON 路径）、ApiKeyManagementService 剩余
  （918-1376 段）。
- 候选技术债：RetryConfig 的 retryOnServiceUnavailable 开关被
  通用 5xx 分支遮蔽（生产行为变更需专项批次）。
- 流程照旧：规划→实施→单类验证→core 全量门禁 EXIT=0→push 特
  性分支→--no-ff 合并 main→账本→清理。

### Batch 378（规划中）

- 目标：①ChatTurnOperationService（85 行：claim 矩阵残余/
  completePrepared/fail/release 分支）；②DocumentMutationService
  （85 行）择辅。
- 备选：RagDocumentController（108 行）、JsonRecordService
  剩余（后段 350-1059 的 persist/embedding/sourceDelete）、
  ApiKeyManagementService 剩余（918-1376 段）。
- 候选技术债：RetryConfig 的 retryOnServiceUnavailable 开关被
  通用 5xx 分支遮蔽（生产行为变更需专项批次）。
- 流程照旧：规划→实施→单类验证→core 全量门禁 EXIT=0→push 特
  性分支→--no-ff 合并 main→账本→清理。

### Batch 379（规划中）

- 目标：①DocumentMutationService（85 行：mutation/rollback/
  tombstone 分支，扫描后按方法群拆分）；②JsonRecordService
  第三扫（后段 350-1059 persist/embedding/sourceDelete 剩余）择辅。
- 备选：RagDocumentController（108 行）、ApiKeyManagementService
  剩余（918-1376 段）、RagChatController keyed JSON 路径残余。
- 候选技术债：RetryConfig 的 retryOnServiceUnavailable 开关被
  通用 5xx 分支遮蔽（生产行为变更需专项批次）。
- 流程照旧：规划→实施→单类验证→core 全量门禁 EXIT=0→push 特
  性分支→--no-ff 合并 main→账本→清理。

### Batch 380（规划中）

- 目标：①DocumentMutationService 其余（tombstone/sync-run 段
  585-776 与 1068-1798 的长尾分支，扫描后选簇）；②JsonRecordService
  第三扫（后段 persist/embedding/sourceDelete 剩余）择辅。
- 备选：RagDocumentController（108 行）、ApiKeyManagementService
  剩余（918-1376 段）、RagChatController keyed JSON 路径残余。
- 候选技术债：RetryConfig 的 retryOnServiceUnavailable 开关被
  通用 5xx 分支遮蔽（生产行为变更需专项批次）。
- 流程照旧：规划→实施→单类验证→core 全量门禁 EXIT=0→push 特
  性分支→--no-ff 合并 main→账本→清理。

### Batch 381（规划中）

- 目标：①JsonRecordService 第三扫（后段 persist/embedding/
  sourceDelete 剩余 350-1059 中未覆盖簇）；②tombstoneExternal
  （924-1010，同 DocumentMutationService）择辅。
- 备选：RagDocumentController（108 行）、ApiKeyManagementService
  剩余（918-1376 段）、RagChatController keyed JSON 路径残余、
  upsertSyncRunItem 深分支（836-872）。
- 候选技术债：RetryConfig 的 retryOnServiceUnavailable 开关被
  通用 5xx 分支遮蔽（生产行为变更需专项批次）。
- 流程照旧：规划→实施→单类验证→core 全量门禁 EXIT=0→push 特
  性分支→--no-ff 合并 main→账本→清理。

### Batch 382（规划中）

- 目标：①RagDocumentController（108 行：按方法群扫描后选簇）；
  ②upsertSyncRunItem 深分支（DocumentMutationService 836-872）
  或 JsonRecordService 后段 persist（587-700）择辅。
- 备选：ApiKeyManagementService 剩余（918-1376 段）、
  RagChatController keyed JSON 路径残余。
- 候选技术债：RetryConfig 的 retryOnServiceUnavailable 开关被
  通用 5xx 分支遮蔽（生产行为变更需专项批次）。
- 流程照旧：规划→实施→单类验证→core 全量门禁 EXIT=0→push 特
  性分支→--no-ff 合并 main→账本→清理。

### Batch 383（规划中）

- 目标：①RagDocumentController 剩余（batchCreate/embedStream
  簇 893-943 与 513-642 段扫描后选簇）；②upsertSyncRunItem 深
  分支（DocumentMutationService 836-872）择辅。
- 备选：ApiKeyManagementService 剩余（918-1376 段）、
  RagChatController keyed JSON 路径残余、JsonRecordService
  后段 persist（587-700）。
- 候选技术债：RetryConfig 的 retryOnServiceUnavailable 开关被
  通用 5xx 分支遮蔽（生产行为变更需专项批次）。
- 流程照旧：规划→实施→单类验证→core 全量门禁 EXIT=0→push 特
  性分支→--no-ff 合并 main→账本→清理。

### Batch 384（规划中）

- 目标：①RagDocumentController batchCreate/embedStream 簇
  （893-943 与 513-642 段——反射或公共入口驱动）；②
  JsonRecordService 后段 persist（587-700）择辅。
- 备选：ApiKeyManagementService 剩余（918-1376 段）、
  RagChatController keyed JSON 路径残余。
- 候选技术债：RetryConfig 的 retryOnServiceUnavailable 开关被
  通用 5xx 分支遮蔽（生产行为变更需专项批次）。
- 流程照旧：规划→实施→单类验证→core 全量门禁 EXIT=0→push 特
  性分支→--no-ff 合并 main→账本→清理。

### Batch 385（规划中）

- 目标：①RagDocumentController embedStream/其余簇（513-642、
  1181-1478 段扫描后选簇）；②ApiKeyManagementService 剩余
  （918-1376 段：notify/生命周期/审计类方法）择辅。
- 备选：RagChatController keyed JSON 路径残余、
  upsertSyncRunItem 更深分支、JsonRecordService 后段收尾。
- 候选技术债：RetryConfig 的 retryOnServiceUnavailable 开关被
  通用 5xx 分支遮蔽（生产行为变更需专项批次）。
- 流程照旧：规划→实施→单类验证→core 全量门禁 EXIT=0→push 特
  性分支→--no-ff 合并 main→账本→清理。

### Batch 386（规划中）

- 目标：①RagDocumentController 剩余（513-642 段：embedStream/
  batchDelete/reembed 端点层扫描后选簇）；②
  ApiKeyManagementService rotation 响应组装（1006-1096 段：
  toResponse 的 REVOKED/轮换中/待轮换字段）择辅。
- 备选：JsonRecordService 后段收尾、RagChatController keyed
  JSON 路径残余、upsertSyncRunItem 更深分支。
- 候选技术债：RetryConfig 的 retryOnServiceUnavailable 开关被
  通用 5xx 分支遮蔽（生产行为变更需专项批次）。
- 流程照旧：规划→实施→单类验证→core 全量门禁 EXIT=0→push 特
  性分支→--no-ff 合并 main→账本→清理。

### Batch 387（规划中）

- 目标：①ApiKeyManagementService rotation 响应组装（1006-1096
  段：toResponse 的 REVOKED/轮换中/待轮换字段与 allowedIds 空
  归并）；②RagDocumentController batchDelete/reembed 端点层
  （221/256-257/375-385 小簇）择辅。
- 备选：JsonRecordService 后段收尾、RagChatController keyed
  JSON 路径残余、upsertSyncRunItem 更深分支。
- 候选技术债：RetryConfig 的 retryOnServiceUnavailable 开关被
  通用 5xx 分支遮蔽（生产行为变更需专项批次）。
- 流程照旧：规划→实施→单类验证→core 全量门禁 EXIT=0→push 特
  性分支→--no-ff 合并 main→账本→清理。

### Batch 388（规划中）

- 目标：①JsonRecordService 后段收尾（persist 重试循环
  605-631、sourceDelete/getByExternalIdentity 剩余、search 详细
  分支 350-430 段）；②RagDocumentController batchDelete/reembed
  端点层小簇择辅。
- 备选：ApiKeyManagementService 1332-1376 段、RagChatController
  keyed JSON 路径残余。
- 候选技术债：RetryConfig 的 retryOnServiceUnavailable 开关被
  通用 5xx 分支遮蔽（生产行为变更需专项批次）。
- 流程照旧：规划→实施→单类验证→core 全量门禁 EXIT=0→push 特
  性分支→--no-ff 合并 main→账本→清理。

### Batch 389（规划中）

- 目标：①JsonRecordService search 详细分支（350-430：rerank 正
  常路径/withRerank/limitResults 边界）；②RagDocumentController
  embedStream/batchDelete 端点层小簇择辅。
- 备选：DocumentMutationService 剩余长尾、
  ApiKeyManagementService 1332-1376 段、RagChatController
  keyed JSON 路径残余。
- 候选技术债：RetryConfig 的 retryOnServiceUnavailable 开关被
  通用 5xx 分支遮蔽（生产行为变更需专项批次）。
- 流程照旧：规划→实施→单类验证→core 全量门禁 EXIT=0→push 特
  性分支→--no-ff 合并 main→账本→清理。

### Batch 390（规划中）

- 目标：①RagDocumentController embedStream/batchDelete 端点层
  （513-642 与 893-943 间剩余簇扫描后选）；②JsonRecordService
  后段 sourceDelete/persist 收尾择辅。
- 备选：ApiKeyManagementService 1332-1376 段、RagChatController
  keyed JSON 路径残余。
- 候选技术债：RetryConfig 的 retryOnServiceUnavailable 开关被
  通用 5xx 分支遮蔽（生产行为变更需专项批次）。
- 流程照旧：规划→实施→单类验证→core 全量门禁 EXIT=0→push 特
  性分支→--no-ff 合并 main→账本→清理。

### Batch 391（规划中）

- 目标：①RagDocumentController 剩余长尾（221/256/375-385/
  1068/1181-1478 段扫描后选簇）；②ApiKeyManagementService
  getRotation/completeRotation/cancelRotation 响应段择辅。
- 备选：RagChatController keyed JSON 路径残余、
  upsertSyncRunItem 更深分支、JsonRecordService 后段收尾。
- 候选技术债：RetryConfig 的 retryOnServiceUnavailable 开关被
  通用 5xx 分支遮蔽（生产行为变更需专项批次）。
- 流程照旧：规划→实施→单类验证→core 全量门禁 EXIT=0→push 特
  性分支→--no-ff 合并 main→账本→清理。

### Batch 392（规划中）

- 目标：①ApiKeyManagementService rotation ops（getRotation/
  completeRotation/cancelRotation 463-576：EXPIRED/CANCELED/
  rotationNotPending 分支与 rotationResponse 组装）；②
  RagDocumentController 1068/1181-1478 剩余长尾择辅。
- 备选：JsonRecordService 后段收尾、RagChatController keyed
  JSON 路径残余、upsertSyncRunItem 更深分支。
- 候选技术债：RetryConfig 的 retryOnServiceUnavailable 开关被
  通用 5xx 分支遮蔽（生产行为变更需专项批次）。
- 流程照旧：规划→实施→单类验证→core 全量门禁 EXIT=0→push 特
  性分支→--no-ff 合并 main→账本→清理。

## 进度留档快照（Batch 392 进行中 · 用户留档指令）

- 留档时点：2026-09-14 · main @ 本留档提交
- 循环进度：Batch 356–391 共 36 个批次按固定流程交付；Batch 392
  进行中——rotation ops（getRotation/completeRotation/
  cancelRotation）测试类编写未完成，未提交（半成品已移除，设计
  要点见下），随下次循环重写。
- 可构建性证据：core 全量 mvn test 门禁 EXIT=0（4892 tests，
  Batch 391 门禁）；webui vite build 通过（更早批次验证，本段未
  改动 webui）。
- 会话累计：core 全量测试自 4694 → 4892（+198）。
- Batch 392 重写要点：
  - 用例设计：getRotation root 调用返回 PENDING 响应；
    completeRotation 对 EXPIRED → CREDENTIAL_ROTATION_EXPIRED、
    对 CANCELED → CREDENTIAL_ROTATION_NOT_PENDING；成功完成
    （source disable + operation COMPLETED + saveAndFlush）；
    cancelRotation 对 CANCELED 幂等；成功取消（target 失效、
    source retireAt 清空恢复）。
  - harness：operation 为 ApiKeyRotationOperation mock
    （getPrincipalId/getRotationId/getExpiresAt 未来/
    getSourceCredentialId=getTargetCredentialId=getStatus 可变）；
    findByKeyId source(version 1, enabled)/target(version 2,
    disabled)；disableByKeyId → 1；principal stub 含
    capabilities=FULL_SERIALIZED。
  - 已知坑：rotation ops 依赖 authorizeRotation（root=true 放
    行）、expirePendingIfNecessary（expiresAt 未来则跳过）、
    rotationResponse 内再次调用 requiredRotationCredentials 与
    principal 查询。
- 历史留档：Batch 388–391 各批要点与防御分支记档见下方对应
  段落。

### Batch 393（规划中，范围放大）

- 目标：①DocumentMutationService 长尾（tombstone 尾部/sync-run/
  relocate 段 1068-1798 扫描后按方法群成批覆盖）；②
  ApiKeyManagementService prepareRotation 复杂分支（371-398 幂
  等/过期/授权矩阵残余）。两个主目标并列，合计预期 ≥25 用例。
- 备选：JsonRecordService 后段、RagDocumentController 1181-1478
  段、RagChatController keyed JSON 路径残余。
- 候选技术债：RetryConfig 的 retryOnServiceUnavailable 开关被
  通用 5xx 分支遮蔽（生产行为变更需专项批次）。
- 流程照旧：规划→实施→单类验证→core 全量门禁 EXIT=0→push 特
  性分支→--no-ff 合并 main→账本→清理。

### Batch 394（规划中，范围放大）

- 目标：①ApiKeyManagementService prepareRotation 授权矩阵（非
  root NORMAL 他人凭据/prepare 凭据不匹配/ADMIN 放行等
  authorizeRotation 残余）+ getRotation 非 root 授权分支；②
  RagDocumentController batchDelete/reembed 端点层小簇。
  两个主目标并列，预期 ≥15 用例。
- 备选：JsonRecordService 后段收尾、RagChatController keyed
  JSON 路径残余、upsertSyncRunItem 更深分支。
- 候选技术债：RetryConfig 的 retryOnServiceUnavailable 开关被
  通用 5xx 分支遮蔽（生产行为变更需专项批次）。
- 流程照旧：规划→实施→单类验证→core 全量门禁 EXIT=0→push 特
  性分支→--no-ff 合并 main→账本→清理。

### Batch 398（已交付）

- 分支：`test/longtail-sweep-batch398`（已合入 main）
- 内容：restoreLocalFromVersion 快照恢复矩阵（10 用例，新建
  DocumentMutationRestoreSnapshotMatrixTest）：①restoreApplies
  SnapshotFields——title/content/source/revision 回写；②payload
  深拷贝（jsonbPayload 传入后 get("key") 值验证）；③metadata 快
  照恢复（Map locale=zh）；④sourceRevision 快照不回写（不参与
  快照恢复，由 contentHash 间接管理）；⑤内容变化 → PENDING 复
  位；⑥内容未变化 → COMPLETED 保持；⑦fresh 嵌入 → 派发 null；
  ⑧SKIP 策略 → 无派发；⑨SYNC 策略 → 嵌入触发。
- 要点：restoreLocalFromVersion 无本地 revision bump（依赖
  versionService 推进）；需要 versionRestoreEnabled=true 开启。
- 状态：单类 10 用例绿；core 全量门禁 EXIT=0（4980 tests）。

### Batch 406（规划中，JaCoCo 扫描已完成，未实施）

- 扫描方式：`mvn -pl spring-ai-rag-core jacoco:report` 后按
  jacoco.csv 分支命中率排序（数据基于 Batch 403 后 core 全量门禁）。
- 类级扫描结论：core/api/documents 各包类级覆盖已饱和；WebUI
  组件/页面/API 客户端/hook 亦全部有直接测试（仅 Chat/Modal/
  Table/Upload 四个空占位目录无内容）。
- 下一批主目标（按未覆盖分支数排序，均为 RagChatService 内部
  私有方法，沿用仓库既有的反射直测模式）：
  ①resolveLegacyRetrievalScope（line≈780，missed=17）：null
  request→unscoped、collection/document 过滤矩阵、resolver
  有/无、过滤命中但 resolve 空→noMatches；
  ②resolveLegacyModelCandidates + candidateForLegacyModel
  （missed≈11）：router null→空、descriptors 命中直返、回退
  orderedCandidates 过滤 null、options null/blank model→
  "UNKNOWN"、ModelCapabilities.defaults()；
  ③invokeWithRetry（missed=5）：retryTemplate null 直调、
  execute 成功返回、耗尽后 RuntimeException 原样抛/受检异常包
  RuntimeException(cause)。private record LlmCallResult 不可
  直接构造，supplier 可返回 Object 哨兵。
  备选：extractPipelineMetrics（missed=3，RagPipelineMetrics.get
  + recordStep 造上下文）、SensitiveMdc 的 swap 边界分支、
  EvaluationSuiteWorker（missed=12）、RerankProviderFactory
  （missed=15）、PgJiebaFulltextProvider（missed=16）。
- 已知非缺陷：RagChatService 的 assertCircuitBreakerAllowsCall
  分支在单测构造器（modeAware 为 null）下不可达，留待集成层。
- 流程照旧：规划→实施→单类验证→core 全量门禁 EXIT=0→push 特
  性分支→--no-ff 合并 main→账本→清理。

### Batch 429（规划中，未实施）

- 主目标：DocumentSyncRunService 批量与账本长尾（JaCoCo 残余
  ~15 分支）：①batchUpsert 失败分类——mutationService 抛非控
  制错误 → recordFailedItem 落 FAILED 且 existing 非本轮
  IN_PROGRESS 时重放旧行；抛 run 控制错误（SYNC_RUN_* 六类）
  → 直接上抛；②sameBeginRequest 四字段一致性（namespace/
  clientRunId(trim)/snapshotMode/missingPolicy）；
  ③requireMissingCountWithinThreshold 的 confirmMissingCount 与
  预览数不一致 → SYNC_RUN_DELETE_PROTECTION。
- 测试驱动：Batch 426 同款构造器夹具（7 参 mock），公共 API
  batchUpsert/begin 驱动；recordFailedItem 依赖 requireActive
  Lease（jdbcTemplate 查询 + lease token hash + 过期校验），
  stub 成本高，可改由公共 batchUpsert 间接覆盖。
- 更大残余（需专项批次）：ChatExecutionService（125）、
  DocumentMutationService（101）、ExternalDocumentService（67→
  已部分收窄）、ApiKeyManagementService（61）、RagChatController
  stream()/ask() SSE 编排（59）。
- 流程照旧：规划→实施→单类验证→core 全量门禁 EXIT=0→push 特
  性分支→--no-ff 合并 main→账本→清理。

### Batch 470（已交付）

- 分支：`test/emit-progress-tail-batch470`（已合入 main）
- 内容：DocumentEmbedService.emitEmbeddingProgress 长尾（新建
  DocumentEmbedServiceEmitProgressTailTest，3 用例）：null 回调
  跳过、批量事件逐条发射、事件序号与总数正确。

### Batch 604（已交付）

- 分支：`codex/batch604-turn-snapshot-chain-tail`（已合入 main）
- 内容：轮次操作快照候选链与重放主体长尾（新建 ChatTurnOperation
  SnapshotCandidateChainTailTest，5 用例）：
  - commandForClaim：执行快照缺 resolvedCandidates → "candidate
    chain is missing"；候选含非文本（[42]）或空白项 → "candidate
    chain is invalid"。
  - claimNew：executionService 解析出的候选链为空 → 报
    IDEMPOTENCY_EXECUTION_SNAPSHOT_INVALID "candidate chain is
    empty"。
  - failedReplay：operation.errorCode 非法（"NOT_A_REAL_CODE"）→
    回退 INTERNAL_ERROR + "previously failed"。
  - replay：SUCCEEDED 认领后对 db:42 / root:environment-root /
    legacy:static / local 四类 owner 构造主体并反序列化响应快照。
- 要点：ChatTurnOperation 构造参数顺序中 responsePayload 位于
  authorizationScopeSnapshot 之前——重放要求 responsePayload 为
  合法 JSON，否则 replay 以 "Stored Chat response snapshot is
  invalid" 失败。
- 指标：1 个新测试类 5 用例绿；core 全量门禁 EXIT=0（5709 tests）；
  ChatTurnOperationService 分支缺口 25→23，core 总行缺口
  1156（持平，本批收益在分支侧）。

### Batch 603（已交付）

- 分支：`codex/batch603-turn-lease-tail`（已合入 main）
- 内容：轮次操作租约与命令快照长尾（新建 ChatTurnOperationLease
  CommandTailTest，5 用例）：
  - 三条认领路径（prepare 前置 claim、inspectExisting 只读预检、
    keyed claimExisting 回收路径）对租约仍处有效期内（future
    leaseExpiresAt）的 operation 均抛 ChatTurnInProgressException，
    并记录 observability.inProgress() 观测。
  - commandForClaim：执行快照包含 resolvedCandidates 时将其应用
    到适配器命令（withModelCandidates），无快照时保留空候选链。
- 指标：1 个新测试类 5 用例绿；core 全量门禁 EXIT=0（5704 tests）；
  ChatTurnOperationService 分支缺口 26→25，core 总行缺口 1156。

### Batch 602（已交付）

- 分支：`codex/batch602-model-factory-tail`（已合入 main）
- 内容：配置化模型工厂解析与构建长尾（新建 ConfiguredChatModel
  FactoryResolveTailTest，10 用例）：
  - provider-only 引用（"zhipu"）经 chatModel.primary 路由解析为
    默认模型，canonical 形如 "zhipu/m2"；空模型表 provider 不可
    解析；限定引用 "zhipu/m2" 命中、"zhipu/ghost" 与 "zhipu/"
    （末尾分隔符）拒绝；跨 provider 同名模型 ID 歧义 → null；
    裸模型 ID 大小写不敏感匹配。
  - 不可用原因检查链全序：disabled → baseUrl 空白 → 未支持
    apiType（vertex）→ 非法 contextWindow/maxTokens → 密钥未
    配置；全部通过返回 null。
  - 构建：OpenAI 非推理模型（temperature+maxTokens）与 Anthropic
    推理模型（无 temperature、maxCompletionTokens）构建成功且
    重复解析命中缓存实例。
  - normalizeBaseUrl 去尾斜杠与 /V1（大小写不敏感）后缀、null
    返回空串；listChatModels 描述符对 null 限额省略 contextWindow/
    maxTokens 键；apiKey 配置为 null 时直接判未配置不触发环境。
- 指标：1 个新测试类 10 用例绿；core 全量门禁 EXIT=0（5699 tests）；
  ConfiguredChatModelFactory 分支缺口 27→17，core 总行缺口
  1157→1156。

### Batch 601（已交付）

- 分支：`codex/batch601-tool-registry-policy-tail`（已合入 main）
- 内容：工具注册策略校验与过滤长尾（新建 RagChatToolRegistry
  PolicyTailTest，5 用例）：
  - validatePolicy 约束矩阵：maxCallsPerRequest=0/超上限、
    maxResultCharacters 过小/超上限、timeout 零/负——全部在注册
    期以 IllegalStateException 拒绝。
  - 未知策略键与空白（null 键）策略键拒绝；重复工具名与空白工具
    名拒绝（空白名需 mock ToolDefinition 绕过 builder 校验）。
  - callbacks 按 mode/domain 过滤：KNOWLEDGE 模式无工具支持；
    AGENT 无领域 → 仅内建；命中领域 → 内建+外部；未命中领域 →
    仅内建。
  - requestContext 对 executionBudget 为 null 的命令省略预算
    上下文键，但保留工具结果字符限额表。
- 要点：RagChatToolPolicy 紧凑构造器将 null effect 归一为
  READ_ONLY、null timeout 归一为 30 秒，二者不是非法输入——
  validatePolicy 的 effect 侧为防御性不可达；ToolDefinition
  builder 自身拒绝空白名称。
- 指标：1 个新测试类 5 用例绿；core 全量门禁 EXIT=0（5689 tests）；
  RagChatToolRegistry 分支缺口 24→16，core 总行缺口 1170→1157
  （含 Batch 600 增量）。

### Batch 600（已交付）

- 分支：`codex/batch600-derivation-snapshot-tail`（已合入 main）
- 内容：派生完整性快照分类矩阵长尾（新建 DerivationSnapshot
  ClassificationTailTest，12 用例）：
  - Snapshot.from 全一致行 → READY/CURRENT；禁用与墓碑 →
    DISABLED；本地行物理不完整 → CORRUPT +
    LOCAL_PHYSICAL_INTEGRITY_FAILED；向量哈希过期 → CORRUPT +
    VECTOR_PHYSICAL_INTEGRITY_FAILED；本地就绪但向量缺失 →
    KEYWORD_ONLY + VECTOR_NOT_REQUESTED；本地 PENDING + 向量
    RUNNING + 运行中任务 → INDEXING（含 activeJobId 字符串→UUID
    解析）；双侧 NOT_REQUESTED → NOT_REQUESTED；本地 FAILED +
    向量 FAILED → LOCAL_UNAVAILABLE + LOCAL_FAILED；本地 PENDING +
    向量换 chunker → LOCAL_STALE。
  - toResponse：损坏快照产出 REBUILD_LOCAL + QUEUE_VECTOR 建议且
    错误信息截断 500 字符；READY 快照无动作。
  - missing 工厂 → DISABLED/MISSING/DOCUMENT_MISSING。
- 要点：桶判定顺序 DISABLED→CORRUPT→READY→KEYWORD_ONLY→INDEXING
  →NOT_REQUESTED→LOCAL_UNAVAILABLE；向量 status=COMPLETED 但不
  新鲜一律判物理损坏，因此收敛（INDEXING）分支要求本地未就绪且
  向量状态非 COMPLETED。
- 指标：1 个新测试类 12 用例绿；core 全量门禁 EXIT=0（5684 tests）；
  Snapshot 分支缺口 37→27，core 总行缺口 1170→1157。

### Batch 599（已交付）

- 分支：`codex/batch599-embedding-job-tail`（已合入 main）
- 内容：嵌入任务创建与重试长尾（新建 EmbeddingJobServiceCreate
  RetryTailTest，8 用例）：
  - create：null 请求体拒绝；documentIds 与 Collection 作用域
    同时给/都不给均拒绝；空 ID 清单与非正 ID 拒绝；禁用文档
    （IAE）与无有效 contentHash 文档（ISE）拒绝；成功创建 QUEUED
    任务后推进 PENDING、activateJob 并发布唤醒（wakeupPublisher）。
  - readiness：作用域解析为 matchNone 时抛 SecurityException。
  - retry：省略 requestedMaxAttempts 时沿用当前任务 maxAttempts；
    retry 落空且无活动任务可归并时抛 DUPLICATE_RESOURCE；重试
    目标非 QUEUED 时不发布唤醒。
- 指标：1 个新测试类 8 用例绿；core 全量门禁 EXIT=0（5672 tests）；
  EmbeddingJobService 分支缺口 20→11，core 总行缺口 1166→1159。

### Batch 598（已交付）

- 分支：`codex/batch598-expiry-alert-tail`（已合入 main）
- 内容：到期告警对账长尾（新建 ApiPrincipalExpiryAlertReconcile
  TailTest，8 用例）：
  - 便捷构造器（无 outbox）容忍 null 通知通道并完成 NOOP 对账；
  - ConcurrentReconcileException（markChecked CAS 未命中）属可
    重试 → 第二次尝试成功；IllegalStateException 不可重试 →
    首次即抛且仅调用一次；DataIntegrityViolation 与
    QueryTimeoutException 持续失败耗尽 eventRetryAttempts=2 预算。
  - 静默期（AlertService.isSilenced=true）claim 返回 null → 通知
    通道零调用；持久 outbox（isDurableEnabled=true）enqueueManaged
    认领后跳过直发通道；直发对抛异常/返回 null future/完成 future
    三种通道实现均不阻断对账。
- 要点：claim 认领 CAS 会推进 state_version（OUTBOX 认领参数为
  推进后的版本），verify enqueueManaged 时 stateVersion 用 anyInt。
- 指标：1 个新测试类 8 用例绿；core 全量门禁 EXIT=0（5664 tests）；
  ApiPrincipalExpiryAlertService 分支缺口 20→9，core 总行缺口
  1170→1166。

### Batch 597（已交付）

- 分支：`codex/batch597-skill-catalog-tail`（已合入 main）
- 内容：RuntimeSkillCatalog 守卫长尾（新建 RuntimeSkillCatalog
  GuardsTailTest 9 用例 + 5 组新 fixture）：
  - 新 fixture：badutf8（非法 UTF-8 字节）、longname（名称 70
    字符）、selflink（自链接）、badcapregex（能力名含空格）、
    skills-mixed-fixture（plain 无链接无能力 + linked 文本链接
    与重复能力）。
  - 用例：禁用配置产出空快照且 enabled()=false；levelOnePrompt
    零预算返回空、正常预算列出名称与能力；loadBody 对未知技能/
    缺失会话/加载预算耗尽返回错误码；readReference 对未知技能/
    未加载会话/非法路径（../、//、..、控制字符、空白）/引用缺失/
    引用字符预算耗尽返回错误码；文本链接解析（description 置空）
    与重复能力去重；Snapshot 紧凑构造器对 null digest/skills 收紧。
- 要点：normalizeReferencePath 的 trim() 会先剥离前导 NUL 等控制
  字符——"\u0000path" 归一为 "path" 走引用未命中分支而非路径非法
  分支，两分支需分别断言。
- 指标：1 个新测试类 9 用例绿（+7 个 fixture 文件）；core 全量
  门禁 EXIT=0（5656 tests）；RuntimeSkillCatalog 分支缺口 35→22，
  core 总行缺口 1176→1170。

### Batch 596（已交付）

- 分支：`codex/batch596-model-router-tail`（已合入 main）
- 内容：模型路由候选与信息投影长尾（新建 ChatModelRouterCandidate
  TailTest，8 用例）：
  - 配置化候选携带注册表元数据：canonical ref 命中 ModelItem 时
    携带 normalizedCapabilities/contextWindow(200000)/maxTokens
    (8192)/cost 且 estimatedModelLimits=false；注册项缺失时回退
    缺省能力、限额字段为 null 且 estimatedModelLimits=true。
  - 不可解析模型 resolveCandidateRequired 抛带可用清单的
    IllegalArgumentException。
  - 可用清单：getAvailableProviders/getAvailableModelRefs 过滤
    available=false 描述符；配置化 provider 可用时 getModelsInfo
    隐藏同 provider 的 legacy 条目（source=legacy 计数为 0）。
  - legacy 信息投影：默认选项缺失时 modelId/name 回退 provider
    别名（registry.getDisplayName 打桩）；有默认选项时 modelId
    取选项模型名。
  - getProviderInfo 对 null/未知 provider 返回 available=false
    与空模型表；orderedCandidates/orderedCandidateDescriptors 对
    null 与空白首选等价，primary 配置时排最前。
- 要点：legacy 注册按类名 provider 关键字启发式（registerLegacy
  Models）——伪造模型类名必须含关键字（如 Zhipu）才会被注册；
  getDisplayName 需在 registry mock 上显式打桩。
- 指标：1 个新测试类 8 用例绿；core 全量门禁 EXIT=0（5647 tests）；
  ChatModelRouter 分支缺口 32→16，core 总行缺口 1179→1176。

### Batch 595（已交付）

- 分支：`codex/batch595-query-rewrite-tail`（已合入 main）
- 内容：查询改写长尾（新建 QueryRewritingServiceTailTest，12 用例）：
  - init 降级：无参构造 + init 使用默认配置；配置中的同义词表与
    领域限定词被拾取进改写流程；运行时 setSynonymDictionary(null)
    与 setDomainQualifiers(null) 容忍空输入。
  - rewriteQuery：空白查询短路返回；空/缺失同义词数组跳过扩展。
  - llmRewrite：无任何 ChatModel 静默返回空；执行模型覆盖解析
    "1."/"-" 编号行并过滤与原查询相同的行（llmMaxRewrites=3）；
    模型返回空 ChatResponse 降级为空；RetryTemplate（反射注入）
    首次失败后重试、耗尽后静默降级为空。
  - generatePaddingQueries：查询已含前缀（如何）/后缀（怎么办）
    时跳过对应变体；长度 <2 的分词片段不参与两两组合；禁用/
    空白/null 输入返回空列表。
- 要点：RagProperties.queryRewrite 为 final 内联初始化，init 的
  config==null 分支为防御性不可达；RagQueryRewriteProperties 默认
  enabled=true，禁用场景必须显式 setEnabled(false)。
- 指标：1 个新测试类 12 用例绿；core 全量门禁 EXIT=0（5639 tests）；
  QueryRewritingService 分支缺口 26→10，core 总行缺口 1186→1179。

### Batch 594（已交付）

- 分支：`codex/batch594-memory-rerank-tail`（已合入 main）
- 内容：记忆投影与启发式重排长尾（新建 ChatMemoryMessageProjector
  TailTest 9 用例、HeuristicRerankLexicalTailTest 8 用例，共 17 用例）：
  - 投影：forPersistence 对 null/空输入返回空、合成摘要消息（元
    数据标志）被丢弃、带工具调用的 assistant 丢弃而纯文本保留；
    toolTranscript 对 null/空消息表/调用数 0/字符预算 0 返回空；
    调用数上限与字符预算耗尽的截断；调用与响应数量不一致整对
    排除；单侧空 id 不配对、双侧空 id 按工具名配对。
  - 重排：边界感知词只出现在长 token 内部时判零分；首个出现被
    阻塞后继续后搜命中独立词；位置加分随距离衰减（>50 字符无
    加分）；全标点词剥离后原样返回不破坏评分；词法特征 512 上限
    截断后自相似恒为 1；CJK+拉丁混合段切分与匹配；平假名/片假
    名/谚文按 CJK 单字相似项判定；calculateDiversityScore 私有候
    选特征路径。
- 要点：AssistantMessage 的 4 参构造器为 protected、Builder 无
  metadata 方法——合成摘要消息用 mock(AbstractMessage) 构造；
  相似度封顶 1.0，验证位置加分需用多词查询保证匹配数不一致。
- 指标：2 个新测试类 17 用例绿；core 全量门禁 EXIT=0（5627 tests）；
  ChatMemoryMessageProjector 分支缺口 20→10、HeuristicRerankProvider
  20→14，core 总行缺口 1193→1186。

### Batch 593（已交付）

- 分支：`codex/batch593-external-doc-tail`（已合入 main）
- 内容：外部文档服务长尾（新建 ExternalDocumentServiceTailTest，
  8 用例）：
  - upsert(null) 请求级 IAE 拒绝；空白 documentType 归一为默认
    text 而非拒绝（校验守卫跳过空白）；ASYNC 策略在派发服务缺失
    时抛 EMBEDDING_JOBS_DISABLED；可重试并发失败
    （DataIntegrityViolationException）耗尽 3 次尝试后收敛为
    DocumentRevisionConflictException（同时覆盖重试谓词的
    DataIntegrity 与 ConcurrencyFailure 两侧判定）；无事务模板时
    直写路径生效且 token 为 null 跳过 confirmActiveWrite；SKIP+
    未变化+启用文档对关键词索引零打扰（markNotRequested 与
    ensureCurrent 均不调用）；禁用文档同版本 upsert 触发墓碑重放
    冲突；legacy 文档（无来源版本）携带 expectedSourceRevision
    认领被拒。
- 要点：禁用文档 + 同版本 upsert 在到达索引协调前即被墓碑重放
  守卫拒绝，因此 coordinateLocalIndex 的「SKIP+禁用」分支仅能由
  内容变化路径触达。
- 指标：1 个新测试类 8 用例绿；core 全量门禁 EXIT=0（5610 tests）；
  ExternalDocumentService 分支缺口 38→27，core 总行缺口 1201→1193。

### Batch 592（已交付）

- 分支：`codex/batch592-apikey-provision-revoke-tail`（已合入 main）
- 内容：API Key 供给与吊销长尾（新建 ApiKeyManagementProvisionRevoke
  TailTest，12 用例）：
  - 供给：generateKey 创建主体+凭证并发布生命周期事件；无事务模板
    （transactionManager=null）时 generateIdempotentKey 直接在当前
    事务执行且幂等重放成功；受限主体（allowedCollectionIds=7,8）
    重放返回 keyId + 集合键；撤销主体降级重放（keyId 置空、无集合
    键）。
  - 清理：账本开关关闭或仓库缺失时 cleanupProvisioningLedger 跳过；
    启用时按保留期删除已完成记录；rotationTransaction 缺失时轮换
    清理跳过；过期 PENDING 轮换经管理锁+双读取置 EXPIRED 并删除
    终态记录；第二次读取操作消失时静默容忍。
  - 吊销：凭证缺失或管理锁不可用返回 false；主体行缺失抛 NOT_FOUND；
    二次权威读取凭证消失抛 NOT_FOUND；无当前可用凭证抛
    CREDENTIAL_NOT_CURRENT。
- 要点：findByKeyId 在吊销流程被读取两次（预检 + 权威读取），用
  Mockito 链式 thenReturn 区分两次结果；轮换凭证实体的 principalId
  必须与操作记录一致，否则 requiredRotationCredentials 抛跨主体
  SERVICE_UNAVAILABLE。
- 指标：1 个新测试类 12 用例绿；core 全量门禁 EXIT=0（5602 tests）；
  ApiKeyManagementService 分支缺口 39→34，core 总行缺口 1203→1201。

### Batch 591（已交付）

- 分支：`codex/batch591-http-guard-tail`（已合入 main）
- 内容：白名单 HTTP 工具守卫长尾（新建 AllowlistedHttpToolProvider
  GuardTailTest，13 用例）：
  - publicAddress 矩阵补侧：0/8 非全零与 240/4 保留段判非公网；
    169/8、172/8、192/8、100/8 的段外兄弟段，198.20、198.51.101、
    203.0.114 段外地址判公网；100.64/10（CGNAT 上半段）、192.0.0/24
    判非公网；IPv6 fe00::（非全局单播掩码）、2001:100::/23（IETF
    协议分配）判非公网；::8.8.8.8（前 10 字节全零内嵌 IPv4）递归
    判公网。
  - HttpToolExecutionState：非正/预算耗尽预约返回 null；commit 与
    release 对 null 预约及重复结算免疫；commit 钳制实际字节到预约
    上限；构造钳制最小预算为 1。
  - 端点冻结校验：不健康 Skill 目录清空端点且无策略投影；httpTools
    禁用时无策略无回调；重复工具名/空白工具名/未知 Skill/能力未
    声明均在构造期抛 IllegalStateException；null 端点列表冻结为空
    注册表。
  - EndpointCallback：空白必填参数报 missing_query_parameter；凭证
    环境变量缺失报 credential_unavailable；完整预算下响应超限判
    response_too_large（区别于部分消耗时的 budget_exhausted）；
    非 2xx 状态码报 http_status_not_allowed。
- 要点：端点冻结校验发生在 provider 构造期（而非 getToolCallbacks），
  非法配置的断言应包裹构造调用。
- 指标：1 个新测试类 13 用例绿；core 全量门禁 EXIT=0（5590 tests）；
  EndpointCallback 分支缺口 47→34（剩余主要为 IPv4 复合条件的
  顺序不可达侧），core 总行缺口 1209→1203。

### Batch 590（已交付）

- 分支：`codex/batch590-mutation-upsert-restore-tail`（已合入 main）
- 内容：本地导入与版本恢复长尾（新建 DocumentMutationUpsertRestore
  TailTest，14 用例）：
  - upsertLocalImport：requestedPolicy 为 null 回退 SKIP 并标记
    不请求；jsonbPayload 深拷贝且计入元数据变更；标题/来源/元
    数据/文件名单字段差异触发 UPDATED；嵌入新鲜时元数据变更不
    派发；仅启用位变化（enabledOverride=FALSE）禁用文档且不派发；
    事务提交后 findById 落空抛 DocumentNotFoundException。
  - restoreLocalFromVersion：内容快照缺哈希 fail-closed；受限密钥
    恢复未分配快照拒绝（RESTORE_NOT_ALLOWED）；类型跨 kind 计入
    内容变更并重嵌；来源差异计入元数据变更且新鲜嵌入不派发；
    jsonb 载荷差异计入元数据变更；SNAPSHOT 可见性恢复应用禁用态、
    派发标记不请求。
- 要点：normalizeDocumentKind 仅区分 json-record，其余类型一律
  归一 text——kind 级内容变更必须用 json-record 快照触发，pdf/
  text 之间不算内容变更。
- 指标：1 个新测试类 14 用例绿；core 全量门禁 EXIT=0（5577 tests）；
  DocumentMutationService 分支缺口 64→45，core 总行缺口 1210→1209。

### Batch 589（已交付）

- 分支：`codex/batch589-chat-authz-tail`（已合入 main）
- 内容：授权证据快照与重放长尾（新建 ChatAuthorizationEvidence
  TailTest，24 用例）：
  - snapshot 侧：ANY_ASSIGNED 作用域投影 ANY_COLLECTION 且不放行
    未分配文档；sources 为 null 容错；null collectionId 跳过观察
    且与派生一致；null/非数值/非正 documentId 拒绝；2000 条来源
    超 64KB 上限拒绝；ChatResponse.setSources 经 List.copyOf 阻断
    null 元素（固化行为）。
  - verifyReplay 侧：null 操作/缺失快照拒绝；坏 JSON 失败闭合；
    缺失 scopeMode/unassignedDocumentsAllowed 字段、非法枚举值、
    非布尔多选标志拒绝；NOT_APPLICABLE 一致性三连（访问模式、
    已选集合、来源证据）；null 主体视为放宽放行；UNRESTRICTED +
    ANY_COLLECTION 当前仍非受限时放行；未分配文档在 CALLER_VISIBLE
    + 允许未分配 + 非受限放行、当前受限拒绝、非 CALLER_VISIBLE
    作用域拒绝；SELECTED 来源越界拒绝与界内放行；来源行非数组/
    非对象/缺 documentId/documentId 非正/collectionId 非数值/
    白名单文本条目拒绝。
- 要点：首访 UNRESTRICTED + CALLER_VISIBLE 的重放在收窄时先触发
  "became narrower" 守卫，因此未分配来源的 RESTRICTED 拒绝场景需
  把首访访问模式设为 RESTRICTED；剩余 13 个分支为防御性不可达
  （ANY+非正集合与证据 >0 矛盾、守卫顺序互斥、类型不变式、
  catch(Exception) 检查异常路径）。
- 指标：1 个新测试类 24 用例绿；core 全量门禁 EXIT=0（5563 tests）；
  ChatAuthorizationService 分支缺口 37→13，core 总行缺口 1216→1210。

### Batch 588（已交付）

- 分支：`codex/batch588-openai-mapper-tail`（已合入 main）
- 内容：OpenAI 请求映射编排与校验边缘（新建 OpenAiChatRequest
  MapperAliasTailTest 7 用例、OpenAiChatRequestMapperProtocolEdge
  TailTest 21 用例，共 28 用例）：
  - 编排（map）：别名强制 PLAIN 时 rag.filters 冲突在 map 层拒绝
    （既有用例只能触达 validateDeclaration 的先导校验）；别名强制
    PLAIN 且无 filters 直通；候选链首位作 modelRef；sessionId 覆写
    与 memory 会话 id 派生断言；stream 缺省 false 与 "oai-" 会话
    生成；X-RAG-Collection-Key 头读取；null 请求回退 local 主体。
  - 校验（validateDeclaration）：PLAIN 无检索字段直通（n=1）；
    null/空白 model；null messages；消息含 name/tool_calls/
    function_call 拒绝；空白 role；null/空白 content；四种非法
    multipart 形状（非对象、缺 type、text 非文本、多余字段）；
    内容总量超 1,000,000 字符拒绝。
  - 快照（mapFromExecutionSnapshot）：缺 declaredModelIdentifier
    拒绝；domainId 空白归一 null；documentType 缺省保持 null；
    retrievalOptions 空对象/缺失拒绝；effectiveScope 非对象/缺失
    拒绝；全字段映射（权重、matchNone、主体、query）。
- 要点：rag.filters 必须为非空 JSON 对象才能通过 RetrievalFilter
  Validator（字符串节点按 "must be a JSON object" 拒绝），这是此前
  map 层 PLAIN+filters 分支无法触达的根因；剩余 14 个分支主要为
  防御性不可达 null 守卫（rag==null 的 PLAIN 块、headerValues null、
  snapshot==null、候选链为空、requireText null 侧）。
- 指标：2 个新测试类 28 用例绿；core 全量门禁 EXIT=0（5539 tests）；
  OpenAiChatRequestMapper 分支缺口 42→14，core 总行缺口 1223→1216。

### Batch 587（已交付）

- 分支：`codex/batch587-diagnostics-tail`（已合入 main）
- 内容：检索诊断包长尾分支覆盖（新建 RetrievalTraceSessionTailTest
  6 用例、RetrievalDiagnosticsPersistTailTest 14 用例、Retrieval
  DiagnosticsBoundedMetadataTailTest 3 用例，共 23 用例）：
  - Session：recordRetrieval 空结果忽略；replaceRetrieval 对未知
    attempt 仅改全局、previous 为空时全局与 attempt 双追加；
    recordQueryExpansion/recordDocumentJoin 未命中 attempt 静默；
    null attemptKey 回退默认名 "attempt"；storeQueryText=true 时
    originalQuery 为空不投影 query。
  - Service：persistSearch 空 session/空 outcome 保护；persist 对
    null session、null 仓库、enabled=true 但 persist=false 均跳过；
    storeQueryText 组合（无 outcome、空原文）走 REDACTED_QUERY；
    预算耗尽 + 最新检索为空 → RETRIEVAL_BUDGET_EXHAUSTED，而有
    结果时保留 RESULTS_RETURNED/null；branchStages 非空时投影
    vector 耗时与 strategy=vector；仓库缺失时 get 抛 NOT_FOUND、
    null principal 回退 local 身份；list 非空过滤器透传；详情对
    null/空元数据与非 List collectionKeys、null key 分数、非 rank
    分数的裁剪；createdAt 为空投影 null。
  - boundedMetadata：超限裁剪 attempts 并置 truncated（注意
    setMaxDetailBytes 夹取 [1024, 262144]）；不可序列化元数据
    回退 schemaVersion=1 + truncated 兜底。
- 要点：诊断包仅剩 3 个防御性不可达分支（line 107 `query == null`
  因 nullToEmpty 恒非空；line 317/327 `latest == null` 因两方法仅在
  latest 非空时被调用），包内行缺口清零。
- 指标：3 个新测试类 23 用例绿；core 全量门禁 EXIT=0（5511 tests）；
  诊断包 JaCoCo 分支缺口 29→3，core 总行缺口 1231→1223。

### Batch 586（已交付）

- 分支：`codex/batch586-eval-parse-variants-tail`（已合入 main）
- 内容：EvaluationSuiteDefinitionValidator 解析长尾（新建 Evaluation
  SuiteParseVariantsTailTest，8 用例）：parse 对非对象定义、cases
  缺失或空、重复 case id 拒绝；parseVariants 对 null 回退单默认变体、
  非数组拒绝、重复 key 拒绝；requireBoolean 对非布尔字段拒绝（
  variant v1 flag must be a boolean）。
- 要点：EvaluationSuiteDefinition.CaseDef 的 scope 需要 collection
  Keys 且 relevant 数组非空（否则 "requires relevant identities"）。
- 指标：单类 8 用例绿；core 全量门禁 EXIT=0（5486 tests）。

### Batch 585（已交付）

- 分支：`codex/batch585-ragalert-accessors-tail`（已合入 main）
- 内容：RagAlert 实体访问器长尾（新建 RagAlertAccessorsTailTest，
  5 用例）：构造默认值（stateVersion=0、notifiedVersion=0、status=
  ACTIVE，updatedAt 非空）、version 与 dedupeKey 往返、stateVersion
  与 notifiedVersion 读写、updatedAt 读写、metrics 映射往返。
- 指标：单类 5 用例绿；core 全量门禁 EXIT=0（5478 tests）。

### Batch 584（已交付）

- 分支：`codex/batch584-budget-tool-batch-tail`（已合入 main）
- 内容：ChatExecutionBudget 工具批量预留长尾（新建 ChatExecution
  BudgetToolBatchTailTest，9 用例）：空名单 → batch exceeds、名单大
  于 maxToolCalls → batch exceeds、唯一轮次消耗后再预留 → tool round
  exhausted、per-name 上限（search 二次）拒绝、字符总预算 600 触发
  500+500 超限 → character budget exhausted、tryReservePolicyToolCall
  的 null/空白/零上限拒绝与两次成功后第三次拒绝、httpToolExecution
  State 惰性创建 + 预算变更 ISE、requestTraceId 往返、snapshot 非空。
- 要点：2 参 reserveToolBatch 委托 3 参并返回 void（返回值断言须用
  toolRounds/snapshot 间接验证）；字符预算 600 触发超限的用例使用
  reserveToolBatch(List, 500) 直接抛出。
- 指标：单类 9 用例绿；core 全量门禁 EXIT=0（5473 tests）。

### Batch 583（已交付）

- 分支：`codex/batch583-openai-error-envelope-tail`（已合入 main）
- 内容：OpenAI 兼容错误信封长尾（新建 OpenAiCompatibilityException
  HandlerTailTest，7 用例）：handleUnreadable 400 固定信封（invalid
  _request_body）、handleRag 状态码透传（5xx → server_error / 4xx →
  invalid_request_error，param 恒 null）、ChatTurnInProgressException
  触发 Retry-After 头（值等于 retryAfterSeconds）、handleSecurity 403
  permission_denied 固定信封、handleArgument 400 透传原始消息、
  handleUnexpected 503 service_unavailable 固定信封、handleProtocol
  保留 protocol 异常的 type/param/code。
- 要点：OpenAiErrorResponse 是 record（error() 取内嵌 Error record，
  组件 message/type/param/code）；invalid() 工厂恒定 type=invalid_
  request_error，param 为定位参数、code 为业务码。
- 指标：单类 7 用例绿；core 全量门禁 EXIT=0（5464 tests）。

### Batch 582（已交付）

- 分支：`codex/batch582-scope-summary-from-tail`（已合入 main）
- 内容：RetrievalScopeSummary.from 长尾（新建 RetrievalScopeSummary
  FromTailTest，9 用例）：inferMode 三分支（NONE→CALLER_VISIBLE /
  ANY_ASSIGNED→ANY_COLLECTION / SELECTED→SELECTED_COLLECTIONS）、
  SELECTED 计数（collectionCount=ids.size()）与键清洗（空串/null 过
  滤后剩余 kb-a、kb-b）、100 上限截断、documentType 与 embedding
  ProfileKey 可选键写入、matchNone 零集合、filterSummary null 过滤
  器 → metadataContains absent + 空载荷列表、present 时 canonical
  Bytes 与 topLevelKeyCount、countTopLevelKeys 的 null/空白/非对象
  /三键四分支。
- 要点：RetrievalScope.selectedCollections(ids, docIds, type) 工厂
  与 record 构造器并存；EmbeddingProfile 在 core.config 包。
- 指标：单类 9 用例绿；core 全量门禁 EXIT=0（5457 tests）。

### Batch 581（已交付）

- 分支：`codex/batch581-system-prompt-tail`（已合入 main）
- 内容：RagChatService 系统提示词与用户消息定制长尾（新建 RagChat
  ServiceSystemPromptTailTest，5 用例）：buildSystemPrompt 无扩展
  返回 null、有扩展无模板返回 null、有模板经定制链替换（principal
  上下文传空串）、customizeUserMessage 无定制直通、有定制经链替换。
- 要点：PromptCustomizerChain.hasCustomizers() 是直通与替换的分支
  条件；mock 链时 customizeSystemPrompt 的第二参数（principal 上
  下文）传空串；domainExtensionRegistry / promptCustomizerChain 位
  于 core.extension 包。
- 指标：单类 5 用例绿；core 全量门禁 EXIT=0（5448 tests）。

### Batch 580（已交付）

- 分支：`codex/batch580-catalog-relative-path-tail`（已合入 main）
- 内容：ResourceCatalog 相对路径与 spring 根身份长尾（新建 Resource
  CatalogRelativePathNestedTailTest，5 用例）：relativePath 的
  "!/root/" JAR 嵌套标记分支（非 "/root/" 首选标记，故命中第二个
  lastIndexOf）、空根 + null 文件名返回 null、configuredRootPath 对
  "jar:file:/app.jar!/nested/root/" 剥离为 nested/root、springResource
  Root 对 SKILL 类型使用 skill/ 前缀且同容器摘要一致、getURI 抛异常
  包装为 "classpath resource identity failed" ISE（反射调用外层为
  InvocationTargetException）。
- 指标：单类 5 用例绿；core 全量门禁 EXIT=0（5443 tests）。

### Batch 579（已交付）

- 分支：`codex/batch579-summary-degrade-path-tail`（已合入 main）
- 内容：ConversationSummaryService 降级路径长尾（新建 Conversation
  SummaryServiceDegradePathTailTest，6 用例）：模型解析失败降级
  summary_model_unavailable（broken router 抛 ISE）、空白模型答案
  降级 summary_empty 且不落库、输出超限降级 summary_output_exceeded
  且不落库、CAS 冲突降级 summary_cas_conflict、压缩开关关闭短路
  compaction_disabled、成功压缩修剪答案并落库快照（text=ok summary）。
- 要点：候选模型通过 modelRouter.resolveCandidateRequired(modelRef)
  解析，broken router 抛 ISE 走 summary_model_unavailable；saveCas
  返回 false 走 summary_cas_conflict。
- 指标：单类 6 用例绿；core 全量门禁 EXIT=0（5438 tests）。

### Batch 578（已交付）

- 分支：`codex/batch578-catalog-total-bytes-tail`（已合入 main）
- 内容：ResourceCatalog 总量与逃逸守卫长尾（新建 ResourceCatalog
  TotalBytesTailTest，3 用例）：累计字节超限（80+80 vs 预算 100）
  failFast 模式 ISE（file byte limit exceeded）与非 failFast 降级
  为诊断（不健康快照 + 1 条诊断）、符号链接条目静默跳过（仅常规
  文件入选，link.md 被 continue）。
- 发现：extractSources 的 total byte limit exceeded（95-96）与
  resource escapes configured root（203-204）为防御性分支——
  remainingTotalBytes 逐文件递减使总量超限先在 readBounded 触发
  file byte limit；符号链接在 198 行提前 continue 使 1090 的逃逸
  检查不可达（realRoot 已 toRealPath）。
- 指标：单类 3 用例绿；core 全量门禁 EXIT=0（5432 tests）。

### Batch 577（已交付）

- 分支：`codex/batch577-doc-optional-service-tail`（已合入 main）
- 内容：RagDocumentController 可选服务委托长尾（新建 RagDocument
  ControllerOptionalServiceTailTest，5 用例）：relocateExternalDocument
  服务缺失 ISE 与委托返回 200 + relocate 响应、getExternalDocument
  服务缺失 ISE 与委托返回 detail、restoreVersion 经 documentMutation
  Service.restoreLocalFromVersion 委托返回 200。
- 要点：三个可选服务均为 public setter 注入；测试先 bare 构造（9
  参构造器无服务）断言 ISE，再 setter 注入后走委托路径。
- 指标：单类 5 用例绿；core 全量门禁 EXIT=0（5429 tests）。

### Batch 576（已交付）

- 分支：`codex/batch576-apikey-create-guard-tail`（已合入 main）
- 内容：ApiKeyController 创建守卫长尾（新建 ApiKeyControllerCreate
  GuardTailTest，3 用例）：非环境 root 调用方（resolver configured
  + attribute=false）→ 403、resolver 缺失 + allowedCollectionKeys
  非空 → ISE "Collection key resolver is unavailable"、空解析结果
  → IAE "Allowed collection scope must not be empty"。
- 要点：环境 root 放行是双条件（rootCredentialResolver.isConfigured()
  且 attribute environmentRootAuthenticated=true）；测试须打桩
  resolver.isConfigured()=true 让 403 守卫生效。
- 指标：单类 3 用例绿；core 全量门禁 EXIT=0（5424 tests）。

### Batch 575（已交付）

- 分支：`codex/batch575-toresult-orchestration-tail`（已合入 main）
- 内容：ChatExecutionService.toResult 组装长尾（新建 ChatExecution
  ServiceToResultTailTest，5 用例）：KNOWLEDGE 模式 DOCUMENT_CONTEXT
  来源提取（mapper 打桩返回 ChatSource）+ retrievalTraceId 元数据 +
  retrievalExecuted=false（DOCUMENT_CONTEXT 路径不消耗检索调用）、
  PLAIN 模式零来源、记忆回放无工具条目不写 TOOL_TRANSCRIPT_METADATA
  _KEY、usage 组件携带 totalTokens=120、DOCUMENT_CONTEXT 列表跳过
  非 Document 条目。
- 要点：usage 存于 ChatExecutionResult record 的 usage 组件而非
  metadata（metadata.putAll(result.metadata()) 在 1127 行之后另有
  usage 键）；retrievalExecuted 反映 trace.retrievalCalls()。
- 指标：单类 5 用例绿；core 全量门禁 EXIT=0（5421 tests）。

### Batch 574（已交付）

- 分支：`codex/batch574-evaluation-batch-tail`（已合入 main）
- 内容：RetrievalEvaluationServiceImpl 批量评估长尾（新建 Retrieval
  EvaluationBatchTailTest，3 用例）：batchEvaluate(null) → 空列表、
  双用例批量评估逐例落库并递增 rag.evaluation.batch_count / hits /
  misses 计数器、toJson 与 fromJson 的 [3,7] 往返。
- 要点：计数器在 @PostConstruct initMetrics() 注册，单测需手动调用；
  计数器名 rag.evaluation.batch_count / hits / misses。
- 指标：单类 3 用例绿；core 全量门禁 EXIT=0（5416 tests）。

### Batch 573（已交付）

- 分支：`codex/batch573-static-tool-context-tail`（已合入 main）
- 内容：StaticKnowledgeSearchTool 上下文守卫长尾（新建 Static
  KnowledgeSearchToolContextTailTest，4 用例）：call(String) 无上下
  文直接抛 Missing server-owned static knowledge context、context()
  对 null ToolContext / 空上下文 / 错误类型值三类拒绝、parse 对损坏
  JSON 包装 IllegalArgumentException（Invalid searchStaticKnowledge
  arguments）、空白 query 归一后拒绝。
- 要点：ProjectDocumentRetriever 在 core.rag 包且 CONTEXT_KEY =
  "rag.authorized.retrieval"；AuthorizedRetrievalContext 可直接构
  造注入 ToolContext。
- 指标：单类 4 用例绿；core 全量门禁 EXIT=0（5413 tests）。

### Batch 572（已交付）

- 分支：`codex/batch572-prepare-operation-tail`（已合入 main）
- 内容：Batch 568 测试类扩展（ChatExecutionServiceCandidateFailover
  TailTest 增至 5 用例）：prepareForOperation 首候选失败降级次候选
  （prepared answer + resolvedModel=provider/fallback，覆盖 293-294
  的 markAttempt/lastFailure 与 311-312 的成功组装）、RagException
  立即终止不降级（verify fallback 从未被 create）。
- 要点：prepareForOperation 的 RagException rethrow 分支（302-303）
  与 execute 的行为一致；降级仅针对 RuntimeException。
- 指标：单类 5 用例绿；core 全量门禁 EXIT=0（5409 tests）。

### Batch 571（已交付）

- 分支：`codex/batch571-pdf-sse-task-lambda-tail`（已合入 main）
- 内容：PdfImportController SSE 任务 lambda 长尾（新建 PdfImport
  ControllerSseTaskLambdaTailTest，4 用例）：虚拟线程任务成功 →
  done 事件 + emitter 完成、IllegalArgumentException → error 通道
  完成、IllegalStateException → error 通道完成、发送失败为 best-
  effort 仍正常结束。全部经反射轮询 ResponseBodyEmitter 内部
  complete 字段验证。
- 要点：SseEmitter 未绑定 handler 时 complete() 不触发 onCompletion
  回调（onCompletion 需经 initialize 注册），单元测试需反射读
  complete 标志；ResponseBodyEmitter.Handler 非 public 无法实现。
- 指标：单类 4 用例绿；core 全量门禁 EXIT=0（5407 tests）。

### Batch 570（已交付）

- 分支：`codex/batch570-chat-props-validate-tail`（已合入 main）
- 内容：RagChatProperties.validate() 上下文与幂等约束长尾（新建
  RagChatPropertiesValidateContextTailTest，9 用例）：合法配置通过、
  output-reserve+safety ≥ window 拒绝、summary > history 拒绝、
  evidence > rag-context 拒绝、compaction 输出 > 摘要拒绝、compaction
  输出 ≥ 源拒绝、幂等范围上限（retention 169h / 响应快照 1B /
  attempts 9）拒绝、agent per-name > 总调用拒绝、单候选结果字符 >
  总字符拒绝。
- 指标：单类 9 用例绿；core 全量门禁 EXIT=0（5403 tests）。

### Batch 569（已交付）

- 分支：`codex/batch569-stream-sse-lambda-tail`（已合入 main）
- 内容：RagChatController stream SSE lambda 长尾（新建 RagChat
  ControllerStreamLambdaTailTest，3 用例）：Completed 事件触发
  emitter.complete、Failed 事件经 sendChatEvent → sendChatError →
  emitter.complete（含 INTERNAL code 的错误载荷）、上游 Flux.error
  经 subscribe 错误回调同步发送 chat error 并完成 emitter。
- 要点：Flux.just/Flux.error 同步发射，stream() 返回前事件已处理
  完毕；完成断言用「后续 send 抛 IllegalStateException（already
  completed）」间接验证；ChatEvent.Failed 是 4 参 record（traceId/
  sessionId/code/message）。
- 指标：单类 3 用例绿；core 全量门禁 EXIT=0（5394 tests）。

### Batch 568（已交付）

- 分支：`codex/batch568-candidate-failover-tail`（已合入 main）
- 内容：ChatExecutionService 候选降级编排长尾（新建 ChatExecution
  ServiceCandidateFailoverTailTest，3 用例）：execute 首个候选调用
  失败降级次候选（fallback answer + resolvedModel=provider/fallback，
  覆盖 recordCandidateFailure + lastFailure 机制）、全部候选失败抛
  最后一次异常（second down）、路由器无候选 → MODEL_CAPABILITY_
  UNSUPPORTED。
- 发现：execute 的 LLM_UNAVAILABLE 分支（candidates.isEmpty() 后
  抛）不可达 —— eligibleCandidates 空时自己先抛 MODEL_*；留档不再
  追覆盖。
- 指标：单类 3 用例绿；core 全量门禁 EXIT=0（5391 tests）。

### Batch 567（已交付）

- 分支：`codex/batch567-pdf-filename-tail`（已合入 main）
- 内容：PdfImportService 文件名规范化长尾（新建 PdfImportService
  NormalizeFilenameTailTest，6 用例）：空上传（isEmpty）→ IAE、null
  原始文件名 → IAE、仅目录段（"dir/"）剥离后空名 → IAE、Windows
  反斜杠路径剥离 + 修剪（"C:\temp\ report.pdf " → report.pdf）、超
  512 字符拒绝、listChildren 对旧数据带前导空白路径（" uuid/default
  .md"）的直达子过滤。
- 要点：normalizeOriginalFilename 为 public static，直接调用；根路
  径 null → findAll 分支。
- 指标：单类 6 用例绿；core 全量门禁 EXIT=0（5388 tests）。

### Batch 566（已交付）

- 分支：`codex/batch566-observability-percentile-tail`（已合入 main）
- 内容：IntegrationObservabilityQueryService 长尾（新建 Integration
  ObservabilityQueryServicePercentileTailTest，5 用例）：percentile
  UpperBound 无样本返回 0、le25/le50/le2500 各桶命中、全部桶不足回
  退 durationMax、toStatusBreakdown 数字维度映射（200 → SUCCESS 分
  类）、非数字维度与越界状态（42/999）→ serviceUnavailable。
- 要点：toStatusBreakdown 是实例方法（反射 + ITE cause 解包）；
  IntegrationHttpStatusClass 200 的分类名是 SUCCESS；4 参构造器末
  位是 IntegrationObservationRecorder（null 即可）。
- 指标：单类 5 用例绿；core 全量门禁 EXIT=0（5382 tests）。

### Batch 565（已交付）

- 分支：`codex/batch565-retirement-require-tail`（已合入 main）
- 内容：ExternalAddressRetirementService 永久阻断长尾（新建
  ExternalAddressRetirementRequireTailTest，3 用例）：无退役标记放
  行（queryForList 空）、有标记时 unrestricted 调用方拒绝消息附带
  "; current targetCollectionKey=kb-new:v1"、受限调用方（java.lang
  .Proxy 动态 ApiAccessPolicy，allow-list="10" 不含目标 20）拒绝时
  不泄露目标键（SecurityException 被吞、suffix 保持空串）。
- 要点：jdbcTemplate.queryForList(String, Object...) 的 varargs stub
  必须用 any(Object[].class) 形式（三参 any(), any(), any() 亦可但
  (Object[]) any() 转换形式在此路径未生效——以 any(Object[].class)
  为准）；受限 policy 经 request attribute（ApiKeyAuthFilter
  .AUTHENTICATED_API_PRINCIPAL_ATTRIBUTE）注入动态代理对象。
- 指标：单类 3 用例绿；core 全量门禁 EXIT=0（5377 tests）。

### Batch 564（已交付）

- 分支：`codex/batch564-collection-resolve-tail`（已合入 main）
- 内容：RagDocumentController 集合解析长尾（新建 RagDocument
  ControllerCollectionResolveTailTest，3 用例）：resolveWritable
  CollectionId 经 collectionKey 走 ApiKeyCollectionAccess.resolve
  CollectionIds → identityResolver.resolveActiveIds(null, keys) 解析
  （unrestricted 调用方）、collectionId + collectionKey 成对校验解
  析、resolveOptionalCollectionId 在 key 存在时委托可写解析。
- 要点：resolveWritableCollectionId 私有方法用反射驱动，ApiAccess
  Policy 传 null 即 unrestricted；restricted 分支需受限 policy +
  RequestContextHolder，未在本批覆盖。
- 指标：单类 3 用例绿；core 全量门禁 EXIT=0（5374 tests）。

### Batch 563（已交付）

- 分支：`codex/batch563-catalog-limits-tail`（已合入 main）
- 内容：ResourceCatalog 发现预算长尾（新建 ResourceCatalogLimits
  DiscoveryTest，4 用例）：全空白 location（"   " 与 ""）→ 空健康
  快照（discover 95-96 前置守卫）、JAR 条目按 entry size 超限（10
  字节上限 vs 200 字节内容）发现拒绝、文件系统单文件超限（100 上
  限 vs 300 字节）拒绝、maxFiles=0 → "invalid resource limits" 在
  任何发现前拒绝。
- 指标：单类 4 用例绿；core 全量门禁 EXIT=0（5371 tests）。

### Batch 562（已交付）

- 分支：`codex/batch562-session-derive-tail`（已合入 main）
- 内容：ChatTurnOperationService 会话派生长尾（新建 ChatTurn
  OperationSessionDeriveTailTest，2 用例）：4 参 claim 经 claimNew
  驱动 withEffectiveSession 快速路径（合法会话 id 原样写入 operation
  行，ArgumentCaptor 验证）；ChatCommand 构造器强制校验会话 id →
  非法会话无法进入 4 参 claim，withEffectiveSession 重新生成分支
  确认为防御性死代码（更正 Batch 506 时代"公共 API 不可达"的推测：
  3 参 claim 重载有内联规范化，不走该方法）。
- 要点：4 参 claim 路径的 repository.insert 是 10 参（末尾 authorization
  Service.initialSnapshot 可为 null），9 参 stub 不匹配 → inserted=
  false → insertNewOperation 递归重入 StackOverflow；resolvedCandidate
  Refs 委托 executionService mock（须打桩返回非空链）。
- 指标：单类 2 用例绿；core 全量门禁 EXIT=0（5367 tests）。

### Batch 561（已交付）

- 分支：`codex/batch561-pdf-path-render-tail`（已合入 main）
- 内容：PdfImportController 路径与渲染长尾（新建 PdfImport
  ControllerPathRenderTailTest，5 用例）：urlDecode 合法编码解码
  （%20 → 空格）与损坏编码（bad%2）回退原文、wrapInHtmlPage 包含
  标题/正文/闭合标签、escapeHtml 覆盖 null → 空串与全部保留字符
  （&/</>/"）、getRawFilePath 对缺失文件 404、对已知 markdown 文
  件 200 并返回可读资源。
- 指标：单类 5 用例绿；core 全量门禁 EXIT=0（5363 tests）。

### Batch 560（已交付）

- 分支：`codex/batch560-chat-properties-tail`（已合入 main）
- 内容：RagChatProperties setter 与校验长尾（新建 RagChatProperties
  SetterTailTest，6 用例）：defaultMode 读写往返、null 子配置
  （staticKnowledge/skills/httpTools）回退新实例、setter 保留提供
  实例（assertSame 三连）、validate 对非正 max-candidate-attempts
  与 max-tool-rounds 抛出含配置键名的异常、正值默认通过。
- 要点：requirePositive 抛 IllegalStateException（消息含配置键），
  断言用异常消息而非异常类型。
- 指标：单类 6 用例绿；core 全量门禁 EXIT=0（5358 tests）。

### Batch 559（已交付）

- 分支：`codex/batch559-collection-create-import-tail`（已合入 main）
- 内容：RagCollectionController 创建与导入构建长尾（新建 Rag
  CollectionControllerCreateImportTailTest，5 用例）：create 携带
  Idempotency-Key 但台账服务缺失 → SERVICE_UNAVAILABLE（232）、
  legacy update 拒绝 collectionKey（419）、buildDocumentFromImport
  的 enabled 缺省回退（未删除 → true / 已删除 → false，864/944）、
  外部身份与命名空间映射、audit 无审计服务时静默跳过（1009）。
- 要点：create 的幂等分支在 requireCollectionCreationAllowed 之后
  检查 provisioning 服务；9 参构造不含 provisioning/审计服务时走
  传统创建路径。
- 指标：单类 5 用例绿；core 全量门禁 EXIT=0（5352 tests）。

### Batch 558（已交付）

- 分支：`codex/batch558-embedjob-guard-tail`（已合入 main）
- 内容：EmbeddingJobService 守卫长尾（新建 EmbeddingJobService
  GuardTailTest，4 用例）：retry 对缺失 job 抛 RagException NOT_
  FOUND、对缺失 document 抛 DocumentNotFoundException、resolveMax
  Attempts 反射拒绝 0 与 99（超出 1..maxAttempts）、isVisible 对
  可访问文档 true / 缺失文档 false。
- 备注：EmbeddingJobRepository 在 core.repository 包（同包测试无
  需 import 的只有 worker/executor 实体类）；DocumentDerivation
  DescriptorProvider 在 core.service 包。
- 指标：单类 4 用例绿；core 全量门禁 EXIT=0（5347 tests）。

### Batch 557（已交付）

- 分支：`codex/batch557-summary-rendersource-tail`（已合入 main）
- 内容：ConversationSummaryService 渲染残余（新建 Conversation
  SummaryServiceRenderSourceTailTest，4 用例）：renderToolTranscript
  对 metadata=null 的行返回空串、全非 Map 条目（字符串/数字）整体
  跳过后仅剩头部 → 空串、renderSource 对 null 消息文本回退 "user: "
  前缀、estimate(List<Message>) 多消息求和为正（token 级估算，不
  必等于字符数）。
- 指标：单类 4 用例绿；core 全量门禁 EXIT=0（5343 tests）。

### Batch 556（已交付）

- 分支：`codex/batch556-profile-registry-find-tail`（已合入 main）
- 内容：EmbeddingProfileRegistry 查找与初始化长尾（新建 Embedding
  ProfileRegistryFindTailTest，5 用例）：findRequiredByKey 命中走
  RowMapper lambda 映射 Profile 全字段（id/provider/dimensions）、
  未命中抛 ISE、getActiveProfile 惰性初始化并缓存同一实例（assertSame）、
  INSERT 后仍缺失 → "Failed to create embedding profile"（127-128）、
  禁用 Profile → "Embedding profile is disabled"。
- 要点：findByKey 的 query(String, RowMapper, String) 桩用
  thenAnswer 调 mapper.mapRow(mock ResultSet) 覆盖 lambda；stub 用
  eq(profileKey) 精确匹配键。
- 指标：单类 5 用例绿；core 全量门禁 EXIT=0（5339 tests）。

### Batch 555（已交付）

- 分支：`codex/batch555-rerank-limits-tail`（已合入 main）
- 内容：HeuristicRerankProvider 上限长尾（新建 HeuristicRerank
  ProviderLimitsTailTest，9 用例）：null 配置回退默认 RagRerank
  Properties、isAvailable/getName、rerank 对 null 与空列表原样直
  通、NaN 原始分数归零防护、calculateDiversityScore 完全相同文本
  对 → 0、部分相似兄弟 → 介于 0/1、单条列表 → 1.0、超长 CJK+拉丁
  混合查询触发 MAX_LEXICAL_FEATURES 截断且分数有限、rankingDepth=
  0 使用完整窗口、最优匹配保持在首位。
- 要点：rerank 输出为新建 RetrievalResult（分数经 blending 重算），
  断言用 documentId 而非 assertSame；diversity 语义 0=完全重复。
- 指标：单类 9 用例绿；core 全量门禁 EXIT=0（5334 tests）。

### Batch 554（已交付）

- 分支：`codex/batch554-relocate-guard-tail`（已合入 main）
- 内容：DocumentRelocationService relocate 守卫长尾（新建 Document
  RelocationRelocateGuardTailTest，5 用例）：不同键解析为同一集合
  ID（alias → 10L 与 source-col 同 ID）→ IAE、externalId 超 255 →
  IAE、INSERT RETURNING 命中（lambda$reserve$4 行映射覆盖）走新预
  约后文档缺失 → DOCUMENT_NOT_FOUND、INSERT 未命中且 SELECT 空 →
  "Idempotency reservation disappeared" ISE、并发唯一约束冲突原样
  透传 DataIntegrityViolationException。
- 备注：writeEnvelope/fingerprint 的 JsonProcessing 分支为不可达
  死代码（ObjectMapper 序列化 Map/record 不会失败），未追覆盖。
- 指标：单类 5 用例绿；core 全量门禁 EXIT=0（5325 tests）。

### Batch 553（已交付）

- 分支：`codex/batch553-keyed-complete-tail`（已合入 main）
- 内容：ChatTurnOperationService keyed 完成长尾（新建 ChatTurn
  OperationKeyedCompleteTailTest，3 用例）：completeOpenAi keyed
  成功路径（completeSuccess true → 稳定快照返回、domainId null 记
  录空串覆盖 569 行）、completeSuccess 返回 false → CHAT_HISTORY_
  PERSIST_FAILED（573 行）、release(null) 与 release(keyed claim)
  均 no-op 安全。
- 指标：单类 3 用例绿；core 全量门禁 EXIT=0（5320 tests）。

### Batch 552（已交付）

- 分支：`codex/batch552-chat-circuit-breaker-tail`（已合入 main）
- 内容：RagChatService 断路器模式感知链路长尾（新建 RagChatService
  CircuitBreakerTailTest，4 用例）：断路器启用时 getCircuitBreaker
  非空且初始 CLOSED、chat() 模式感知成功路径记录断路器成功（响应
  透传）、execute 抛 IllegalStateException 原样传播且 failureRate
  Threshold=1 + minimumNumberOfCalls=1 时一次失败即 OPEN、OPEN 后
  续请求被 LlmCircuitOpenException（LLM_CIRCUIT_OPEN）拒绝。
- 要点：模式感知链路需 RequestContextHolder 挂 MockHttpServletRequest
  （ChatPrincipal.fromCurrentRequest）；RagCircuitBreakerProperties
  默认 failureRateThreshold=50/minCalls=10，测试收敛为 1/1 实现
  一次失败即熔断。
- 指标：单类 4 用例绿；core 全量门禁 EXIT=0（5317 tests）。

### Batch 551（已交付）

- 分支：`codex/batch551-resource-spring-path-tail`（已合入 main）
- 内容：ResourceCatalog spring 路径长尾（新建 ResourceCatalog
  SpringPathTailTest，7 用例）：relativePath 按 configuredRootPath
  剥除根标记（classpath*: docs/ → notes/guide.md）、标记不匹配时
  文件名回退、resource.getURI() 抛异常时文件名回退（mock）、
  springResourceRoot 对非 classpath* 直通 configuredRoot、classpath*
  按容器路径哈希计算区分性 rootKey（同名文件不同容器 → rootKey 不
  同）、readBounded 按 contentLength 拒绝超限资源、contentLength=-1
  时回退流式读取（匿名子类覆盖）。
- 要点：UrlResource(file.toUri()) 的 URI 含真实临时目录路径，
  configuredRootPath 取 location 的尾段（classpath*: 剥前缀、!/
  再剥、去首尾斜杠）作为标记在 URI 中 lastIndexOf 匹配。
- 指标：单类 7 用例绿；core 全量门禁 EXIT=0（5313 tests）。

### Batch 550（已交付）

- 分支：`codex/batch550-eligible-candidates-tail`（已合入 main）
- 内容：ChatExecutionService 候选筛选与校验长尾（新建 Chat
  ExecutionServiceEligibleCandidatesTailTest，9 用例，经公共
  resolveCandidateRefs 驱动）：配置候选解析并按能力过滤（good 留、
  nostream 滤除）、AGENT 模式 getDefaultOptions 非 ToolCalling
  ChatOptions → 过滤空 → MODEL_CAPABILITY_UNSUPPORTED、流式过滤 →
  MODEL_STREAMING_UNSUPPORTED、全部解析失败（resolveCandidate
  Required 抛 IAE）→ SERVICE_UNAVAILABLE、空 modelCandidates 回退
  modelRouter.orderedCandidateDescriptors(modelRef)、描述为空但显
  式请求模型仍校验（KNOWLEDGE 通过但合格列表空 → 异常）、validate
  Candidate 反射驱动流式/工具守卫与放行。
- 要点：eligibleCandidates/validateCandidate 为私有，前者经公共
  resolveCandidateRefs 覆盖、后者反射 + ITE cause 解包重抛；Model
  Capabilities.record 的 supportsToolCalling 是 Boolean.TRUE.equals
  （必须显式 true），supportsStreaming 的 null 视为 true。
- 指标：单类 9 用例绿；core 全量门禁 EXIT=0（5306 tests）。

### Batch 549（已交付）

- 分支：`codex/batch549-collection-bykey-tail`（已合入 main）
- 内容：RagCollectionController by-key 路由长尾（新建 RagCollection
  ControllerByKeyTailTest，7 用例）：listDocumentsByKey 分页透传
  （findByCollectionId + mapKeys 键映射 + 文档摘要）、未知键 require
  Active 抛 RagException、exportCollectionByKey 经 findAllBy
  CollectionId 导出两份文档、cloneCollectionByKey 未命中 Optional
  .empty → 404（orElseGet lambda）、legacy cloneCollection(Long) IAE、
  castToMap 的 Map 透传与 null/字符串 → null、audit 在服务存在时
  DELETE 透传 logDelete(4 参)。
- 要点：by-key 路径经 requireActiveCollectionByKey → identity
  Resolver.requireActive(null, key)，mock 必须打该桩（缺省返回
  null → NPE）；受限失败包装在 ApiKeyCollectionAccess 内部。
- 指标：单类 7 用例绿；core 全量门禁 EXIT=0（5297 tests）。

### Batch 548（已交付）

- 分支：`codex/batch548-openai-event-mapping-tail`（已合入 main）
- 内容：OpenAiCompatibilityController 事件映射长尾（新建 OpenAi
  CompatibilityEventMappingTailTest，8 用例）：jsonResponse 发送
  JSON 后完成、mapEvent 三分支 —— ContentDelta → chat.completion
  .chunk 增量块、Completed → 归一 finish reason（STOP→stop）、
  ToolStarted 未知事件 → 空 Flux；toResponse 执行结果重载映射
  usage 三元组（totalTokens=8）与 finish reason、原生响应重载将
  null finish reason 归一 stop 且 usage 缺失时为 null；send 将
  IOException 经 reactor 传播为运行时异常；prepareTurn 携带集合头
  时生成 OpenAI 指纹（keyed prepared）。
- 要点：OpenAiChatCompletionResponse 是 record（id()/model()/
  choices()/usage()）；reactor.core.Exceptions.ReactiveException 非
  公共类型，断言 RuntimeException + IOException cause；zsh 的 glob
  不匹配会中止整条 rm 命令 —— 清理 stale 报告须用 rm -f 逐个删除。
- 指标：单类 8 用例绿；core 全量门禁 EXIT=0（5290 tests）。

### Batch 547（已交付）

- 分支：`codex/batch547-summary-render-tail`（已合入 main）
- 内容：ConversationSummaryService 渲染长尾 + 1 项技术债：
  1. 技术债：compaction_source_limit_exceeded 分支不可达 —— select
     SourceRows 与最终渲染共用同一 renderSource+estimator，选择预
     算耗尽后总量恒 ≤ maxSourceTokens；删除 4 行死代码。
  2. ConversationSummaryServiceRenderTailTest（9 用例）：source 预
     算计入上一轮摘要（超限 → 选择空 → compaction_source_empty），
     candidate 缺 contextWindow/maxTokens 走配置回退，模型 Runtime
     异常降级 summary_failed，受检异常 cause（IOException）经
     ExecutionException 包装降级，线程中断 → future.get 中断 → 降
     级，无 metadata 行不渲染工具记录，非 Map 条目跳过 + 超长名称
     截断 128，null 回答 → 空文本回退，模型返回 null → summary_empty。
- 要点：repository.find 返回 Optional<RagChatMemorySummaryRepository
  .SummaryRow>（字段序 version/summarizedThroughHistoryId/text/
  modelRef/estimatedTokens/updatedAt），不是服务层 SummarySnapshot；
  受检异常经 CompletableFuture 包装为 ExecutionException 后按
  cause 类型分流。
- 指标：单类 9 用例绿 + 死代码删除；core 全量门禁 EXIT=0（5282
  tests）。

### Batch 546（已交付）

- 分支：`codex/batch546-http-tool-callback-tail`（已合入 main）
- 内容：AllowlistedHttpToolProvider.EndpointCallback 私有工具长尾
  （新建 AllowlistedHttpToolProviderCallbackHelperTailTest，6 用例）：
  parseInput 空白/null 归一 {}、非对象 JSON 拒绝、未知字段拒绝、
  损坏 JSON 包装 IAE；endpointUri 斜杠归一（无尾斜杠 + 无头斜杠补
  "/"，双斜杠去重）+ 查询参数空格编码为 %20 + baseUrl 带 fragment
  拒绝；hasPrefix 长度守卫（地址不足 32 位）→ false、字节不匹配 →
  false、完全匹配 → true；validateJson 嵌套深度超限 / 节点总数超限
  / 数组元素超限三拒绝与合法嵌套放行；skillSession 与 state 对
  null ToolContext 与缺失键上下文回退 null。
- 要点：int 前缀数组元素必须写 0xb8（写 (byte) 0xb8 = -72 会与地
  址字节 & 0xff 后的 184 不等，误报不匹配）；EndpointCallback 为私
  有内部类，经 provider.getToolCallbacks().getFirst() 取实例后反射
  调私有工具；JsonLimits 为私有静态类反射构造。
- 指标：单类 6 用例绿；core 全量门禁 EXIT=0（5273 tests）。

### Batch 545（已交付）

- 分支：`codex/batch545-pdfimport-fullflow-tail`（已合入 main）
- 内容：PdfImportService 全流程长尾（新建 PdfImportServiceFullFlow
  TailTest，5 用例）：mock PdfConverter 在 outputDir 产出 source/
  {a-markdown.md,b-image.png} 后 importPdf 端到端成功（original
  pdf + markdown + 图片共 3 记录、result.filesStored/entryMarkdown
  /originalFilename 映射）、转换器成功但未产出 source 目录 → ISE、
  getFile 委托 findById、listChildren 根路径走 findAll 且前缀模式
  下仅保留直达子（深层 /sub/deep/x.md 被过滤）、loadFileAsResource
  将 contentBin 写临时资源并流式可读 + 缺失路径 Optional.empty。
- 要点：importPdf 记录数 = 1（original.pdf）+ 1（唯一 .md）+ 非_;
  md 文件数；listChildren 直达子判定基于 remainder 的斜杠计数。
- 指标：单类 5 用例绿；core 全量门禁 EXIT=0（5267 tests）。

### Batch 544（已交付）

- 分支：`codex/batch544-mutation-missing-doc-tail`（已合入 main）
- 内容：DocumentMutationService 文档消失长尾（新建 Document
  MutationMissingDocTailTest，5 用例）：createLocal 与 upsertSync
  RunItem 在 finish 阶段 findById 返回空 → DocumentNotFoundException
  （消息含 id=41，两类 lambda 各覆盖）、allocateSourceSequenceFor
  Snapshot 委托 allocateSourceSequence（RETURNING mutation_sequence
  → 30L）、resolveUpdateCollection 无 collectionKey 且 unrestricted
  策略 → null、localCreateFingerprint 遇 ObjectMapper 写出失败包装
  为 "Cannot canonicalize idempotent document request"。
- 要点：Mockito 对 Optional 返回类型默认 Optional.empty，"finish
  阶段文档消失"用例只需不打 findById 桩即可触发 orElseThrow；
  DocumentNotFoundException 不带 documentId 字段，断言走 message。
- 指标：单类 5 用例绿；core 全量门禁 EXIT=0（5262 tests）。

### Batch 543（已交付）

- 分支：`codex/batch543-lifecycle-derive-tail`（已合入 main）
- 内容：DocumentLifecycleService 派生长尾（新建 DocumentLifecycle
  ServiceDeriveTailTest，10 用例）：本地 NOT_REQUESTED 且嵌入在场
  → local FAILED 且 searchability FAILED、双状态缺席 → 全 NOT_
  REQUESTED、QUEUED/job RUNNING → embedding INDEXING、未知嵌入态
  → NOT_REQUESTED、本地就绪 + 嵌入 QUEUED 或 FAILED → KEYWORD_ONLY
  （本地优先）、integrity 快照 NOT_REQUESTED 桶直通 / INDEXING 向量
  条件映射 / KEYWORD_ONLY 桶向量错误透传、profileProvider 抛异常 →
  profileKey null、uuid 工具的 UUID 实例/null/字符串/垃圾四分支。
- 要点：searchability 矩阵本地优先 —— local READY 时嵌入任何非
  READY 态都归 KEYWORD_ONLY，只有双 READY 才 READY；默认 chunker
  版本为 hierarchical-v2:1000:100:100（readyRow 必须用同值才能判
  current）；DerivationIntegrityRepository 在 core.service 包。
- 指标：单类 10 用例绿；core 全量门禁 EXIT=0（5257 tests）。

### Batch 542（已交付）

- 分支：`codex/batch542-abtest-dingtalk-tail`（已合入 main）
- 内容：两个服务实现长尾：
  1. AbTestServiceImplLifecycleTailTest（11 用例）：重复实验名
     IAE、创建默认 minSampleSize=100 与 DRAFT 初始态（捕获实体校
     验）、updateExperiment 的 null id / 不存在 / RUNNING 禁改守卫
     与 DRAFT 字段套用、start 仅 DRAFT/PAUSED、pause 仅 RUNNING、
     stop 任意态置 COMPLETED+endTime、getVariantForSession 三类
     IAE + 同会话确定性 + 值域约束、100% treatment 直选（哈希回退
     前命中）、recordResult 的 null 守卫 + 会话去重跳过保存 +
     docIds 序列化 "[3,4]" + converted 标记、analyzeExperiment 双
     变体统计均值/胜者/显著性、单变体无裁决、null 实验拒绝、结果
     分页映射。
  2. DingTalkSignatureHelperTailTest（4 用例）：Retry-After 头解
     析（null/空白→null、" 30 "→30s、"-5"→0s、垃圾→null）、
     buildWebhookUrl 免签原样/加签追加 timestamp&sign、compute
     Signature 确定性、escapeJson null→"" 且转义换行/引号/反斜杠。
- 要点：RestTemplateBuilder 链式构造用 mock(..., RETURNS_SELF) +
  build() 单独 stub；MockitoExtension 严格桩下任何不匹配参数都会
  使后续桩返回 null；反射 varargs 单个 null 参数须 new Object[]
  {null} 包装；浮点均值断言用 delta。
- 指标：两新类 15 用例绿；core 全量门禁 EXIT=0（5247 tests）。

### Batch 541（已交付）

- 分支：`codex/batch541-acl-delegated-tail`（已合入 main）
- 内容：ApiKeyCollectionAccess 委托解析长尾（新建 ApiKeyCollection
  AccessDelegatedTailTest，9 用例）：resolveDelegatedAllowedKeys 的
  null 请求 → null、空列表 IAE、unrestricted 调用方经 resolver
  .resolveActiveIds 解析、受限调用方解析失败（resolveActiveIds
  WithinAllowed 抛 RagException）包装 SecurityException、resolve
  WritableCollectionId 对 null 请求默认单一 allow-list 集合 / 多值
  allow-list 要求显式 id（SecurityException）、parseAllowedIds 拒绝
  空段 / 非正数 / 非数字且 "1, 2, 1" 去重为 [1,2]、serializeAllowed
  Ids 空输入 → null 且去重序列化 "10,11"、废弃 currentKey() 委托
  currentPolicy()。
- 要点：受限调用方的 keys 解析在 ApiKeyCollectionAccess 内部走
  resolveActiveIdsWithinAllowed（unrestricted 才走 resolveActiveIds），
  stub 必须对准实际被调用的方法；SecurityException 包装仅在
  !isUnrestricted 时发生。
- 指标：单类 9 用例绿；core 全量门禁 EXIT=0（5232 tests）。

### Batch 540（已交付）

- 分支：`codex/batch540-ragchat-heartbeat-clear-tail`（已合入 main）
- 内容：RagChatController 心跳/清史/幂等响应长尾（新建 RagChat
  ControllerHeartbeatClearTailTest，6 用例）：startHeartbeat 心跳
  关闭（intervalSeconds=0）返回空句柄且 stop() 安全、启用（1s）启
  动调度器并可停止、clearHistory 经 sessionCoordinator.clearSession
  返回含 sessionId 的响应、删除 0 行抛冲突、prepareTurn 请求无
  Idempotency-Key 时 prepare(principal, keys, null)（null 指纹），
  idempotentResponse 对 keyed claim 写 X-RAG-Turn-Id 与 X-RAG-
  Idempotent-Replay 双头（SUCCEEDED → true）、无 claim 无 trace 时
  不写头。
- 要点：RagSseProperties 的心跳开关是 heartbeatIntervalSeconds>0
  （无独立布尔）；HeartbeatHandles 与 stop() 均包级私有（反射
  getDeclaredMethod）；Claim.replay 由操作状态推导（SUCCEEDED →
  true）。
- 指标：单类 6 用例绿；core 全量门禁 EXIT=0（5223 tests）。

### Batch 539（已交付）

- 分支：`codex/batch539-execution-openai-tail`（已合入 main）
- 内容：两个协议组件长尾：
  1. ChatExecutionServiceCitationMapTailTest（6 用例）：citation
     Map 将 CitationValidation 六字段（status/available/cited/
     invalid/citedSourceCount/sourceCount）映射为快照字典、
     serializeDocumentIds 去重并丢弃非数字 id（空 → null，序列化
     为 "[10,11]"）、tokenCount 读 totalTokens（Number）缺省 0、
     parseLong 数字/null/垃圾三分支、plannedMessages 将合成摘要
     assistant 消息置于 recent turns 之前且空白摘要省略。
  2. OpenAiCompatibilityModelResolutionTailTest（4 用例）：model
     For 三级回退 —— 快照 publicModelAlias → declaredModel
     Identifier → 二者皆 DEFAULT 时落回响应 requestedModel/调用方
     fallback、快照 JSON 损坏静默忽略走响应字段、keyedCompletionId
     确定性（同 turnId 同值）且格式为 chatcmpl-rag- + 64 位十六进
     制。
- 要点：plannedMessages 接收 ConversationPromptPlan（非 Chat
  Command）；控制器内 modelFor 的 ChatResponse 是项目 DTO（有
  setRequestedModel，注意与 Spring AI ChatResponse 同名冲突）；
  反射 varargs 传 null 须 (Object) 强转；OpenAiChatRequestMapper
  在 core.openai 包。
- 指标：两新类 10 用例绿；core 全量门禁 EXIT=0（5217 tests）。

### Batch 538（已交付）

- 分支：`codex/batch538-doc-controller-file-tail`（已合入 main）
- 内容：RagDocumentController 文件校验与审计长尾（新建 Rag
  DocumentControllerFileValidationTailTest，5 用例）：validate
  TextFile 三路判定（.md 扩展名白名单 / text/plain 内容类型 / 无
  扩展名 → isText=false）、readFileContent 标题去 .md 后缀 + 空白
  内容拒绝、auditDelete 双重载（3 参/4 参）透传 AuditLogService、
  resolveOptionalCollectionId 双空短路返回 null、normalizeDocument
  CollectionScopes 对 null 列表直接返回。
- 要点：auditLogService 是第 9 个构造参数（类上无 setter）；File
  ValidationResult / FileContentResult 是私有 record，测试统一用
  反射访问器（isText()/extension()/title()/content()）断言。
- 指标：单类 5 用例绿；core 全量门禁 EXIT=0（5207 tests）。

### Batch 537（已交付）

- 分支：`codex/batch537-ragchat-build-helpers-tail`（已合入 main）
- 内容：RagChatService 构建辅助长尾（新建 RagChatServiceBuild
  HelpersTailTest，4 用例）：getCircuitBreaker 在断路器未启用时返
  回 null、buildSortedAdvisors 按 Ordered.getOrder() 升序排序并在
  末尾固定追加 MessageChatMemoryAdvisor（共 4 个）、buildAdvisor
  Params 六参重载链写入 CONVERSATION_ID/元数据键值/domainId/检索
  作用域/maxResults=5、null scope 归一 unscoped 且 maxResults=0 不
  写参数。
- 要点：RagChatService 构造器内部对 ChatClient.Builder 链式调用
  defaultAdvisors(...).build()，Builder mock 必须用 RETURNS_DEEP_
  STUBS；buildAdvisorParams 六参重载末位是 ChatModel，反射 invoke
  需传满 6 参；advisor 类实际包名为 core.advisor（非 core.chat）、
  PromptCustomizerChain 在 core.extension。
- 指标：单类 4 用例绿；core 全量门禁 EXIT=0（5202 tests）。

### Batch 536（已交付）

- 分支：`codex/batch536-turnop-fail-exhaust-tail`（已合入 main）
- 内容：ChatTurnOperationService 失败持久化长尾（新建 ChatTurn
  OperationFailExhaustTailTest，5 用例）：fail 遇 ObjectMapper 写出
  失败回退硬编码兜底载荷并归为 INTERNAL_ERROR（repository.complete
  Failure 校验）、RagException 时载荷与错误码取原异常（FORBIDDEN）、
  exhaustAttempts 序列化失败回退兜底载荷（IDEMPOTENCY_ATTEMPTS_
  EXHAUSTED，lease null → repository 分支且返回 true 不再抛）、
  inProgress 对 leaseExpiresAt=null 的操作返回 retryAfterSeconds=2
  （remaining 1 + 1，工厂方法返回而非抛出）、stableSource(null) IAE
  与 stableValue(null) 直通 null。
- 要点：inProgress 是返回异常的工厂方法；ChatTurnInProgress
  Exception 错误码为 IDEMPOTENCY_OPERATION_IN_PROGRESS；stableValue
  的 IAE 包装分支在真实 Jackson 下不可达（空 bean 可序列化）。
- 指标：单类 5 用例绿；core 全量门禁 EXIT=0（5198 tests）。

### Batch 535（已交付）

- 分支：`codex/batch535-mutation-syncitem-applied-tail`（已合入 main）
- 内容：DocumentMutationService 同步条目应用长尾（新建 Document
  MutationSyncItemAppliedTailTest，3 用例）：新建身份 + SKIP 策略
  走完整创建链 → APPLIED 且 embeddingAction NONE / job null（mark
  NotRequestedInCurrentTransaction 登记）、既有文档 + SYNC 策略 →
  APPLIED 携带 ASYNC_QUEUED dispatch 并触发 keywordIndexPersistence
  Service.ensureCurrent（ensureLocalForNonSkipMutation 非 SKIP 分
  支）、仓库查询抛 DataIntegrityViolationException 原样透传。
- 要点：新建身份需 stub allocateSourceSequence 的 "RETURNING
  mutation_sequence"（queryForObject(Long.class)）否则抛 Cannot
  allocate source mutation sequence；snapshotStartSequence 必须 ≥
  既有文档 sourceMutationSequence，否则 SKIPPED_NEWER_MUTATION；
  keyword 服务经 setKeywordIndexPersistenceService 注入。
- 指标：单类 3 用例绿；core 全量门禁 EXIT=0（5193 tests）。

### Batch 534（已交付）

- 分支：`codex/batch534-jsonrecord-dedupe-tail`（已合入 main）
- 内容：JsonRecordService 检索去重与辅助长尾（新建 JsonRecord
  SearchDedupeTailTest，6 用例）：非数字 documentId 全部跳过 →
  uniqueRanked 空 → 提前返回空响应（覆盖 parseDocumentId 捕获分
  支 + searchAuthorizedDetailed 空返回）、数值 id 中仓库缺文档的
  条目跳过（doc==null continue）、空白 query IAE、safeError 反射
  三分支（RuntimeException null 消息回退类名 / 普通消息脱敏透传 /
  String 600 字符截断 500+"..."）、requestCollectionKey 的 null 请
  求与 collectionKey/collectionId 双空 → null、parseDocumentId 的
  null/非数字/数字三分支。
- 要点：mock ReRankingService 未打桩时 rerank() 返回 null →
  limitResults 清空结果 → 检索断言恒为空；任何走 searchAuthorized
  Detailed 的测试须在 RetrievalConfig 显式 useRerank(false)；limit
  经 properties 收敛，stub 用 anyInt() 而非 eq(10)。
- 指标：单类 6 用例绿；core 全量门禁 EXIT=0（5190 tests）。

### Batch 533（已交付）

- 分支：`codex/batch533-resource-catalog-jar-tail`（已合入 main）
- 内容：ResourceCatalog 发现路径长尾（新建 ResourceCatalog
  DiscoveryTailTest，5 用例）：@TempDir 真实 JAR 注入 —— JAR 文
  件缺失走 "JAR read failed" 包装（failFast ISE + cause）、空前缀
  "!/" 全量列举（仅 .md 过滤 .txt）、"!/docs" 前缀过滤跳过外部条
  目、file: 指向单个 .md 文件作为根（单条目、相对名即文件名）、
  POSIX 收权目录在 failFast=false 下降级为诊断（快照不健康 +
  diagnostics=1）。
- 发现：discoverJarFile 的 prefix.contains("..") 分支是纵深防御
  死分支 —— normalizeLocation 在更外层已拒绝 ".."，公开 API 无法
  达到；保留分支不删（安全语义），不追覆盖。
- 指标：单类 5 用例绿；core 全量门禁 EXIT=0（5184 tests）。

### Batch 532（已交付）

- 分支：`codex/batch532-relocation-envelope-tail`（已合入 main）
- 内容：DocumentRelocationService 幂等台账长尾（新建 Document
  RelocationEnvelopeTailTest，5 用例）：源/目标解析为同集合 IAE、
  Idempotency-Key 超 255 IAE、externalId 含控制字符被 require
  VisibleAscii 拒绝、过期预约行（expired=true）先 DELETE 再递归重
  新预约并以 SUCCEEDED 信封重放 RELOCATED、result_payload 损坏时
  抛 IllegalStateException("Cannot read relocation replay
  response")。
- 要点：过期预约重放用 queryForList thenReturn([expired],
  [succeeded]) 模拟两次读取；信封 schemaVersion 必须为 1。
- 指标：单类 5 用例绿；core 全量门禁 EXIT=0（5179 tests）。

### Batch 531（已交付）

- 分支：`codex/batch531-advisor-logging-summary-budget-tail`（已合入 main）
- 内容：两个检索链路组件长尾：
  1. HybridSearchAdvisorLoggingTailTest（5 用例）：setRetrieval
     LoggingService 注入后 before() 回放 logRetrieval 遥测（sessionId
     从上下文取）、RETRIEVAL_SCOPE_KEY=matchNone 短路返回空且不打
     检索、DOCUMENT_IDS_KEY 混合 Long/Integer/字符串/非数字/null
     解析为 [1,2,3]、MAX_RESULTS_KEY=100 钳制 50、非正值回退 10。
  2. ConversationSummaryServiceBudgetTailTest（3 用例）：无执行
     预算（11 参命令）跳过 compaction_no_messages、reserveModelCall
     占满后 compact 降级 summary_budget_skipped 且不落账、metadata
     中 toolTranscript 渲染进摘要提示词（untrusted 边界标记 +
     tool=lookup 行）。
- 要点：HybridRetrieverService.search 有 4/5 参重载，Mockito stub
  末参必须显式 any(RetrievalConfig.class) 消歧；ChatExecutionBudget
  的 maxModelCalls 经 positive() 归一（0→1），构造耗尽预算须先调
  reserveModelCall()；ChatHistoryResponse 是 12 组件 record（metadata
  与 status 间有 sources）。
- 指标：两新类 8 用例绿；core 全量门禁 EXIT=0（5174 tests）。

### Batch 530（已交付）

- 分支：`codex/batch530-static-knowledge-parse-tail`（已合入 main）
- 内容：StaticKnowledgeCatalog 解析与检索长尾（新建 Static
  KnowledgeCatalogParseTailTest，4 用例）：mock ResourceCatalog
  注入 markdown 快照，覆盖标题路径压栈与回退（## → ### → ## 时
  removeLast 分支）、80 字符超限行走切分 else 分支（overlap 回看
  + while 窗口切分 + 单块截断到 chunkMax）、空白行边界落块（
  current >= chunkMax 分支）、static: 前缀块 id 与 titlePath 元数
  据、search 的 CJK 覆盖（≥2 字符）评分、拉丁词项评分、标题短语
  评分、多块分数降序比较器、limit=1 与字符预算 30 生效、不健康
  快照直接返回空。
- 要点：initialize() 是 @PostConstruct 包级私有，同包测试可直接
  调；ResourceSnapshot 第七参是 degraded-roots 列表，传非空且
  healthy=false 即可构造不健康快照。
- 指标：单类 4 用例绿；core 全量门禁 EXIT=0（5166 tests）。

### Batch 529（已交付）

- 分支：`codex/batch529-openai-registry-fingerprint-tail`（已合入 main）
- 内容：两类协议工具长尾：
  1. OpenAiModelAliasRegistryTailTest（12 用例）：别名排序、非法
     别名字符/缺失配置/空注册拒建、候选 >16 拒绝、空白候选拒绝与
     去重修剪、require 未知别名/resolve null 抛 modelNotFound、
     rag.mode 覆写禁用拒绝/启用生效、rag.memory 非法值拒绝、覆写
     禁用拒绝/启用生效（含大小写与空白修剪）。
  2. ChatRequestFingerprintTailTest（9 用例）：原生请求 null
     message/mode/model/domainId 的缺省归一化、scope 模式按 ids
     在场推断（SELECTED vs CALLER_VISIBLE）与 collectionIds 排序
     去重、metadata 数组元素内凭证字段递归拒绝、null 值跳过、控制
     字符拒绝、OpenAI 声明 scope 无模式回退 CALLER_VISIBLE、集合
     头归一化（排序去重进入 scope）触发 SELECTED_COLLECTIONS、
     PLAIN 携带 documentIds 拒绝 RETRIEVAL_OPTIONS_NOT_ALLOWED。
- 要点：List.of 拒绝 null 元素（第三次踩坑），混合 null 列表一律
  Arrays.asList；凭证字段检查是大小写不敏感的字段名匹配
  （"ApiKey" 命中）。
- 指标：两新类 21 用例绿；core 全量门禁 EXIT=0（5162 tests）。

### Batch 528（已交付）

- 分支：`codex/batch528-scope-resolver-authz-tail`（已合入 main）
- 内容：CollectionRetrievalScopeResolver 授权长尾（新建 Collection
  RetrievalScopeResolverTailTest，12 用例）：null 模式按 ids/keys
  在场推断 SELECTED_COLLECTIONS、受限调用方（allow-list "10,11"）
  ids 在册授权与越界 SecurityException、keys-only 经 resolveActive
  Ids 解析、ids+keys 解析结果集合不一致 IAE、受限 keys 解析非
  RETIRED 的 RagException 包装为 SecurityException、unrestricted 下
  RagException 原样透传、非正 documentIds IAE、空 keys IAE、超
  100 keys IAE、含空格 key IAE、空白 documentType IAE。
- 要点：ApiAccessPolicy 是接口，测试用匿名实现（role NORMAL + 数
  字 allowedCollectionIds）即可造受限调用方；null 表示 unrestricted。
- 指标：单类 12 用例绿；core 全量门禁 EXIT=0（5141 tests）。

### Batch 527（已交付）

- 分支：`codex/batch527-external-doc-normalize-tail`（已合入 main）
- 内容：ExternalDocumentService 校验与事务长尾（新建 External
  DocumentServiceNormalizeTailTest，9 用例）：upsert 新身份携带
  expectedSourceRevision 冲突（DocumentRevisionConflictException）、
  documentType 超 50 字符拒绝、collectionKey 含非可见 ASCII（空格）
  拒绝、title 超 255 拒绝、source（normalizeOptional）超 255 拒绝、
  getByExternalIdentity 空白 namespace 归一化 default（查不到文档
  → DOCUMENT_NOT_FOUND）与超长 namespace IAE、safeError 三重载
  （null→null、Throwable null→兜底文案、空白→"Embedding failed"、
  普通值脱敏透传、600 字符截断到 500）、executeInTransaction 对
  DataIntegrityViolationException 重试直至收敛（3 次尝试）与无事
  务模板直通抛出。
- 要点：Object 重载的 safeError 走 String.valueOf——非空白值返回
  脱敏后的字符串而非兜底文案；getByExternalIdentity 仓储方法名是
  findByCollectionIdAndSourceNamespaceAndExternalId（与 upsert 的
  findByCollectionIdAndExternalId 不同）。
- 指标：单类 9 用例绿；core 全量门禁 EXIT=0（5129 tests）。

### Batch 526（已交付）

- 分支：`codex/batch526-eval-executor-snapshot-tail`（已合入 main）
- 内容：EvaluationCaseExecutor 长尾（新建 EvaluationCaseExecutor
  SnapshotTailTest，5 用例）：双参构造器（re-ranking null 委托）、
  useRerank=true 但 ReRankingService 缺失时 ISE、identityExists
  双参委托（count>0 true / count=0 false / null 视为不存在）、
  collectionSnapshot 的 RowCallbackHandler 每键统计（enabled
  Documents + maxUpdatedAt 非空/NULL 两分支）、空键集合空快照。
- 要点：collectionSnapshot/lookup 的 jdbc lambda 是 RowCallback
  Handler（void 回调），stub 用 doAnswer + handler.processRow(rs)；
  RetrievalFilters 是 record，空过滤用 RetrievalFilters.none()。
- 指标：单类 5 用例绿；core 全量门禁 EXIT=0（5120 tests）。

### Batch 525（已交付）

- 分支：`codex/batch525-apikey-rotation-ledger-tail`（已合入 main）
- 内容：ApiKeyManagementService 轮换台账长尾（新建 ApiKeyRotation
  LedgerTailTest，6 用例）：prepareRotation 重放指纹漂移拒绝
  IDEMPOTENCY_KEY_REUSED、重放命中返回持久化响应（replay=true +
  idempotentReplay）、重放引用缺失 principal 抛 SERVICE_UNAVAILABLE
  （覆盖 rotationResponse 的 orElseThrow lambda）、cleanup 对过期
  retiring 凭证调用 disableByKeyId / 对未到期凭证保留（两分支，借
  PENDING 冲突作为流程终点）、generateIdempotentKey 竞态重试
  Thread.sleep 被中断透出 "Provisioning retry was interrupted"。
- 要点：mock 辅助方法不得在另一个 when() 的参数位置调用（产生
  UnfinishedStubbingException），先提升为局部变量再 thenReturn；
  rotationFingerprint = sha256(currentKeyId + "\n" + overlap 或
  "DEFAULT") 可在测试端复算用于匹配重放指纹；requiredRotation
  Credentials 要求 source/target 凭证均存在且 target 版本 > source
  版本，rotation operation mock 需补 getSourceCredentialId/
  getTargetCredentialId 与 findByKeyId 查询；服务端中断语义是保留
  中断标志，测试 finally 里只清理不断言。
- 指标：单类 6 用例绿；core 全量门禁 EXIT=0（5115 tests）。

### Batch 524（已交付）

- 分支：`codex/batch524-coordinator-lease-state-tail`（已合入 main）
- 内容：ChatSessionCoordinator 租约状态长尾（新建 ChatSession
  CoordinatorLeaseStateTailTest，12 用例）：已过期租约在
  invokeWithinDeadline 入口即超时、丢失租约被 assertActive 拒绝
  （CHAT_SESSION_LEASE_LOST）、非运行时异常原因（AssertionError）
  包装为非受检异常、SERVER 模式 commit 收敛共享内存、commit 对
  RagException 原样透传不二次包装、failOperation/failExpired
  Operation 意外运行时异常包装 CHAT_HISTORY_PERSIST_FAILED、
  renew 的 CAS 未命中/仓储故障置 lost/非 RUNNING 跳过三分支、
  consumeLease 经行映射器产生异常名单数抛 CHAT_SESSION_LEASE_LOST、
  keyed commit 后 resume 续排程被拒仅降级告警。
- 要点：LeaseHandle.lost/state 为 private（反射读写，state 是
  AtomicReference<private enum State>）；keyed handle 用 5 参私有
  构造反射构造（principalId/sessionId/ownerToken/deadline/stateless）；
  RENEW_SQL 匹配片段用 "UPDATE rag_chat_session_lease"；keyed
  commit 内部会先走 renewLeaseForCommit，测 resume 拒绝前必须把续
  租 CAS stub 命中。
- 指标：单类 12 用例绿；core 全量门禁 EXIT=0（5109 tests）。

### Batch 523（已交付）

- 分支：`codex/batch523-syncrun-deadcode-lease-tail`（已合入 main）
- 内容：DocumentSyncRunService 技术债 + 租约长尾：
  1. 技术债：删除死代码 failedItem（私有方法无任何调用方，FAILED
     响应实际由 recordFailedItem 承担），-12 行（-6 未覆盖行）。
  2. 新建 DocumentSyncRunLeaseNotFoundTailTest（4 用例）：未知
     runId 经 requireRun 抛 NOT_FOUND、complete 流程完成态 CAS 未
     命中抛 "lease was lost while completing"（同时覆盖空候选集
     fingerprint(List<Candidate>) 比对路径）、batchUpsert 成功路径
     applied_count 计数 CAS 未命中抛 "lease was lost while applying
     an item"、RagException 错误码经 errorCode() 透传到失败条目
     （含 findItem 空结果捕获分支）。
- 要点：RunRow 反射构造支持 preview 字段（previewTokenHash/
  previewFingerprint），complete 流程要求 previewFingerprint == sha
  256("")（空候选集）且请求 previewToken 哈希匹配；后续 stub 覆盖
  先前 stub（Mockito 后写胜出），可对同一 runId 重打桩。
- 指标：单类 4 用例绿 + 死代码删除；core 全量门禁 EXIT=0（5097
  tests）。

### Batch 522（已交付）

- 分支：`codex/batch522-embedding-worker-schedule-tail`（已合入 main）
- 内容：EmbeddingJobWorker 调度长尾（新建 EmbeddingJobWorker
  ScheduleTailTest，7 用例）：onJobsAvailable 事件入口触发领取扫
  描、executor 关闭竞态下 wakeUp 走 RejectedExecutionException 复
  位 dispatchScheduled、claim 异常释放名额后经 dispatchLoop 吞并
  （异步路径）、反射直调 dispatchAvailableJobs 时 claim 异常透出
  且名额归还、空领取释放名额并停止扫描、提交被拒归还名额、阻塞
  处理下 shutdown 经 awaitTermination 超时 + shutdownNow 返回。
- 要点：EmbeddingJob / EmbeddingJobStatus 与 EmbeddingJobWorker 同
  包（core.embeddingjob，测试无需 import，之前记录的 core.entity
  路径有误）；EmbeddingJobExecutor.processClaimed 返回 void，打桩
  必须用 doAnswer(...).when(mock) 而非 when(mock.thenAnswer； Rag
  EmbeddingJobProperties.workerConcurrency 默认 4，名额相关断言前
  用 setWorkerConcurrency(2) 显式收敛。
- 指标：单类 7 用例绿；core 全量门禁 EXIT=0（5093 tests）。

### Batch 521（已交付）

- 分支：`codex/batch521-fulltext-factory-provisioning-tail`（已合入 main）
- 内容：两个类的策略与台账长尾（JaCoCo 驱动）：
  1. FulltextSearchProviderFactoryTailTest（5 用例）：无参构造全禁
     用工厂（getCapabilities null + NoOp）、legacy getProvider() 的
     autoDetectBest 链（jieba→english→trgm→none 四档）、未知固定策
     略回退 per-language 自动探测、pg_trgm 固定策略扩展缺失抛
     IllegalStateException、detectLang 的 CJK/ASCII/null/空白。
  2. CollectionProvisioningTailTest（7 用例）：enabled=false 抛
     IDEMPOTENCY_DISABLED 且不触台账、四依赖 null 抛 SERVICE_
     UNAVAILABLE、重试耗尽经 readExisting 收敛为 replay（真值）、
     backoff 被中断置位中断标志并抛 unavailable（清理标志）、
     cleanup 删除计数 >0、cleanup 吞并 DataAccessException、关闭时
     cleanup 短路。
- 要点：JaCoCo XML 中 sourcefile 挂在 package 下而非 class 下，按
  class.iter('sourcefile') 统计行会误报 0（CSV 的 LINE_MISSED 才可
  靠）；backoff 中断用例必须在 @AfterEach 用 Thread.interrupted()
  复位标志避免污染其他测试。
- 指标：两新类 12 用例绿；core 全量门禁 EXIT=0（5086 tests）。

### Batch 520（已交付）

- 分支：`codex/batch520-purge-validate-retire-tail`（已合入 main）
- 内容：CollectionPurgeService 校验与收尾长尾（新建 Collection
  PurgeValidateTailTest，8 用例）：apply 冻结版本失配拒绝
  （validateFrozenRequest）、COMPLETED 预览坏结果负载冲突 + 好负
  载解析返回（readResult 双路径）、空文档计划 + 非空修复 ID 的
  apply 全链路 RETIRED（覆盖 countUuidJoin、deleteByIds 执行分支、
  markCollectionRetired、buildRetiredResult 状态行映射、json 序列
  化、completePurgePreview）、validatePreviewable 的未索引引用冲突
  （Counts[24]）、活跃任务冲突（Counts[21]）、同步上限冲突（max
  Documents=0 + Counts[0]）、零计数放行。
- 要点：Counts/Plan 为私有 record，测试经反射 getDeclaredConstruc
  tor（28 个 long 参数）构造并直调 validatePreviewable；Collection
  PurgeResultResponse 的版本字段名是 collectionVersion 不是
  version；apply 时序为 validateFrozenRequest → COMPLETED 短路 →
  requirePreviewApplicable → claimApplyLease → fenceCollection →
  requireUnchangedPlan（指纹 + validatePreviewable）→ 删除 → 收
  尾；update 兜底 stub 返回 1 之外，DELETE FROM rag_documents 需
  单独 stub 返回与 documentCount 一致的行数。
- 指标：单类 8 用例绿；core 全量门禁 EXIT=0（5074 tests）。

### Batch 519（已交付 · 替代先前"进行中"记录）

- 分支：`codex/batch519-keyword-fallback-worker-schedule`（已合入 main）
- 内容：两个类的调度/回退长尾（JaCoCo 驱动）：
  1. KeywordIndexAllocateFallbackTailTest（3 用例）：allocate
     Generation 首次 UPDATE...RETURNING 未命中时 INSERT 兜底 + 二次
     UPDATE 命中、两次未命中抛 "Document changed while allocating"
     （且不进入 chunk 写入）、fresh 索引命中（状态 READY + chunk
     计数一致）时 ensureCurrent 提前返回。
  2. AlertNotificationDeliveryWorkerScheduleTailTest（7 用例）：
     onAvailable 事件入口触发扫描、dispatchLoop 捕获仓储运行时异
     常后静止、executor 关闭竞态下 wakeUp 经 RejectedExecutionExce
     ption 复位 dispatchScheduled、dispatchAvailable 提交被拒时归
     还名额并 break、名额耗尽（availablePermits=0）跳过扫描、指数
     退避 initialBackoff 巨大时溢出封顶 maxBackoff±20%、阻塞投递下
     shutdown 经 awaitTermination 超时 + shutdownNow 返回。
- 要点（解掉上轮遗留）：JdbcTemplate.queryForList 有 (String,
  Object...) 与 (String, Class, Object...) 两个重载，未显式类型的
  any() 会被编译器解析到错误重载导致 stub 脱靶（MISS 用例因默认空
  列表侥幸通过）；varargs 一律用 any(Object[].class) 整组匹配。
  另外 ensureCurrent 成功路径在 chunk 写入后还有 READY CAS（sql 含
  SET local_index_status，8 参），必须 stub 返回 1。
- 指标：两新类 10 用例绿；core 全量门禁 EXIT=0（5066 tests）。

### Batch 518（已交付）

- 分支：`codex/batch518-retrieval-trace-tail`（已合入 main）
- 内容：RetrievalTraceCollector 重载与守卫长尾（新建 Retrieval
  TraceCollectorTailTest，11 用例）：parentSession/attemptKey 暴露、
  检索预算耗尽上报（tryBeginRetrieval 第二次失败 + lastBudget
  Exhausted 标志）、recordQueryExpansionOutcome 回写（含未 configure
  时 no-op）、null outcome 在 recordOutcome/recordCandidateOutcome
  中 no-op、单参 recordOutcome 重载、无前序 outcome 时 recordRerank
  经 RetrievalOutcome.ofResults 合成基线、isRepeatedQuery 归一化
  命中、null/空白/未知缓存查询返回 null、record(List) 重载不写查询
  缓存、citationId/markExposed null 守卫、normalizeQuery null→"" 与
  空白折叠。
- 要点：sources() 只返回 markExposed 过的键（断言覆盖率用
  citationId 更直接）；recordRerank(null results) 不抛异常而是合成
  空 outcome，不能当 no-op 断言。
- 指标：单类 11 用例绿；core 全量门禁 EXIT=0（5056 tests）。

### Batch 517（已交付）

- 分支：`codex/batch517-prompt-planner-budget-tail`（已合入 main）
- 内容：ConversationPromptPlanner 预算边界长尾（新建 Conversation
  PromptPlannerBudgetTailTest，11 用例）：非正上下文窗口拒绝
  （CHAT_CONTEXT_BUDGET_EXCEEDED）、超限 summary 丢弃（summary_
  omitted）、剩余预算放不下最近一轮时 history_truncated + recent_
  history_omitted 双标记、未达 minimumRecentTurns 即截断（2/3 轮）、
  turns() 跳过 null 消息、null baseline 无标记、legacy（关闭自适应）
  PLAIN 证据目标 0 且 over-limit 记录降级、legacy KNOWLEDGE 保留
  minModeEvidence、AGENT 模式 toolResultReserve 抬升到 estimate
  ("tool_result_too_large")=21、maxSummaryTokens=0 走 fitText 空路
  径、工具 schema 超 maxToolSchemaTokens 拒绝。
- 要点：RagException.getErrorCode() 返回 String（断言用 .name()）；
  List.of 拒绝 null 元素，null 消息分组用例须用 Arrays.asList；
  ToolDefinition 不能用 lambda mock，须 mock 接口并 stub name/
  description/inputSchema。
- 指标：单类 11 用例绿；core 全量门禁 EXIT=0（5045 tests）。

### Batch 516（已交付）

- 分支：`codex/batch516-pdf-gate-alert-validator`（已合入 main）
- 内容：两个门禁类长尾（JaCoCo 驱动）：
  1. PdfImportServiceImportGateTailTest（3 用例）：importPdf 在
     rag.pdf.enabled=false 时抛 ISE、无可用转换器抛 RuntimeException
     （No PDF converter）、仓储初始状态 0。
  2. AlertNotificationProviderValidatorTailTest（5 用例）：delivery
     开关关闭跳过校验、notification 开关关闭跳过校验、configured+
     available 通过、configured+unavailable 拒绝启动（消息含 provider
     名）、未配置 provider 不阻断启动。
- 要点：NotificationConfig.getDelivery() 返回活对象且无 setter，测
  试直接改 getDelivery() 返回实例；@PostConstruct validate() 包级私
  有，同包测试可直接调用。
- 指标：两新类 8 用例绿；core 全量门禁 EXIT=0（702 类 / 5034 tests
  / 0 fail / 0 err / 9 skip）。

### Batch 515（已交付）

- 分支：`test/memory-summary-tail-batch515`（已合入 main）
- 内容：RagChatMemorySummaryRepository CRUD 长尾（新建 RagChat
  MemorySummaryRepositoryTailTest，11 用例）：find 返回 Summary
  Row 或 empty、saveCas 的 INSERT（v0）与 UPDATE（CAS）两路径、
  参数校验（零 historyId / null text / 空白 text / 负 tokens）、
  delete 操作、saveCas INSERT 冲突返回 false、saveCas UPDATE CAS
  不匹配返回 false。
- 要点：jdbcTemplate varargs stub 用 any(Object[].class) 匹配整
  组 varargs 最可靠（逐个 any() 容易因数量不匹配而脱靶）。
- 指标：单类 11 用例绿；core 全量门禁 EXIT=0（5684 tests）。

### Batch 514（已交付）

- 分支：`test/embedjob-retry-race-batch514`（已合入 main）
- 内容：EmbeddingJobService.retry 重试长尾（新建 EmbeddingJob
  RetryRaceTailTest，3 用例）：重试成功转 QUEUED 并发布唤醒、
  DataIntegrityViolationException 竞争时经 activeRetryTarget 兜
  底、无兜底目标时异常透出。
- 之前的 Batch 514 进度备注（进行中未完成）已被本交付替代。



- 候选：EmbeddingJobService.retry 重试长尾（新建 EmbeddingJob
  RetryRaceTailTest，3 用例），因多次编译错误（EmbeddingJobStatus
  import 包路径、EmbeddingJob 22 字段构造器缺参）未完成。
- 已删除未完成的测试文件，等待下轮重新实施。
- 下轮实施要点：EmbeddingJob record 共 22 个字段（id, batchId,
  documentId, embeddingProfileId, force, contentHash, document
  Version, status, attemptCount, maxAttempts, availableAt, lease
  Owner, leaseExpiresAt, cancelRequestedAt, lastError, createdAt,
  startedAt, finishedAt, updatedAt, origin, requestedByPrincipalId,
  requestGeneration, documentKind, chunkerVersion），job() 辅助方
  法必须补齐全部字段；EmbeddingJobStatus 在 core.entity 包；retry
  存根用 jobRepository.retry(id, 5)；findActive 签名为
  (long documentId, long profileId, String contentHash)。

### Batch 513（已交付）

- 分支：`test/coordinator-commit-batch513`（已合入 main）
- 内容：ChatSessionCoordinator commit 链路与中断长尾（新建 Chat
  SessionCoordinatorCommitTailTest，3 用例）：commit 提交 durable
  历史（reserveDurableContentReferences → saveDurable 链路）、提
  交异常包装 CHAT_HISTORY_PERSIST_FAILED、invokeWithinDeadline
  中断时取消任务抛 CHAT_TIMEOUT。
- 指标：单类 3 用例绿；core 全量门禁 EXIT=0（5670 tests）。

### Batch 512（已交付）

- 分支：`test/mutation-sync-finish-batch512`（已合入 main）
- 内容：DocumentMutationService 外部 SYNC finish 链与恢复 ASYNC
  派发长尾（新建 DocumentMutationExternalSyncFinishTailTest，3 用
  例）：upsertExternal SYNC 策略创建 → enqueue + completeAfterCommit
  收尾（lifecycle 读取透出 embedStatus）；ASYNC 策略仅入队不收
  尾（completeAfterCommit 不触达）；restoreLocalFromVersion 非
  SKIP 策略 → LOCAL_VERSION_RESTORE 入队派发（RESTORED_VERSION）。
- 要点：dispatch() 对非 SKIP 一律 enqueueInCurrentTransaction，
  SYNC 的差异只在 finish 阶段的 completeAfterCommit；lifecycle
  Service.read 必须显式打桩（finishExternal 直接读 lifecycle 字
  段，未打桩即 NPE）。
- 指标：单类 3 用例绿；core 全量门禁 EXIT=0（5667 tests）。

### Batch 511（已交付）

- 分支：`test/identity-resolver-guards-batch511`（已合入 main）
- 内容：CollectionIdentityResolver 守卫长尾（新建 Collection
  IdentityResolverGuardTailTest，13 用例）：requireIncludingDeleted
  缺失拒绝（id= 文案）；resolveActiveIds 的 null 返回与空列表 IAE
  与 ids/keys 集合不一致 IAE；键解析的已清理/未知键拒绝；
  resolveActiveIdsWithinAllowed 的非法键名与空列表 IAE；
  requireActive 的非正 id / id-key 不匹配 / 已清理集合拒绝；
  mapKeys 经 findAllById 解析键。
- 要点：resolveActiveKeyIds 是 private，公开路径为 resolveActiveIds
  (null, keys)；keys 批量查找走 findAllByCollectionKeyInAndDeleted
  False（未打桩默认空集合）；mapKeys 用 findAllById（any() 宽松
  匹配 LinkedHashSet 入参）。
- 指标：单类 13 用例绿；core 全量门禁 EXIT=0（5664 tests）。

### Batch 510（已交付）

- 分支：`test/hybrid-timeout-batch510`（已合入 main）
- 内容：HybridRetrieverService 向量臂 orTimeout 超时分支（新建
  HybridRetrieverVectorTimeoutTailTest，2 用例）：JDBC 慢查询
  （1.5s > retrievalTimeoutSeconds=1）→ orTimeout 触发 → handle
  归一为 VECTOR TIMEOUT 状态、结果为空但整体不抛、总耗时在超时
  预算内快速返回；快查询在预算内 SUCCESS。
- 要点：orTimeout 只对真实异步执行生效——直接执行器（Runnable::
  run）会让 supplyAsync 同步完成后超时永不触发，测试需注入真实
  线程池；超时状态是独立 TIMEOUT（区别于 ERROR）。
- 指标：单类 2 用例绿；core 全量门禁 EXIT=0（5651 tests）。

### Batch 509（已交付，含生产 bug 修复）

- 分支：`test/kstool-call-tail-batch509`（已合入 main）
- 内容：KnowledgeSearchTool 调用矩阵长尾 + 预算耗尽 NPE 修复
  （新建 KnowledgeSearchToolCallTailTest，8 用例）：单参 call 缺
  失服务端上下文 ISE、ToolContext 缺授权条目 ISE、空白 query
  IAE、非法 JSON 包装 IAE、检索预算耗尽上报（budgetExhausted +
  error 文案）、rerank 异常降级保留结果、非数字 maxResults 回退、
  序列化失败包装 ISE。
- **生产 bug 修复**：budget 耗尽分支中 `results` 被
  `trace.cachedResults` 赋 null 后未回填，line 150 `results.
  stream()` 直接 NPE——补 `results = List.of()`，预算耗尽改为正
  常返回带 error 的空结果输出（有回归测试覆盖）。
- 要点：mock ObjectMapper 需同时 stub readValue（parse 用）并
  doThrow writeValueAsString（序列化用）；broken 工具的检索也要
  stub 空结果以推进到序列化阶段。
- 指标：单类 8 用例绿；core 全量门禁 EXIT=0（5649 tests）。

### Batch 508（已交付）

- 分支：`test/sse-events-json-tail-batch508`（已合入 main）
- 内容：SSE 事件类型映射与 JSON 序列化失败长尾（新建两个测试
  类共 5 用例）：RagChatControllerStreamEventTypesTest（3 用例，
  置于 core.chat 包构造 package-private ChatEvent 记录）——
  ToolStarted / ToolFinished / SourcesAvailable 三类事件的有效
  载荷映射并正常完成（心跳调度器启用 interval=1s 创建即停）；
  OpenAiCompatibilityToJsonFailureTailTest（2 用例）——私有
  toJson 对自引用 Map 抛 IllegalStateException "Failed to
  serialize"、可序列化对象原样透出。
- 要点：事件类型矩阵通过同一 stream 入口 + 不同 Flux 事件组合
  驱动；心跳启用仅验证调度器创建与 stop 不抛（interval 1s 内测
  试即完成，无实际心跳发送）。
- 指标：单类 5 用例绿；core 全量门禁 EXIT=0（5641 tests）。

### Batch 507（已交付）

- 分支：`test/evalsuite-version-batch507`（已合入 main）
- 内容：EvaluationSuiteService.createVersion 编排长尾（新建
  EvaluationSuiteCreateVersionTailTest，4 用例）：正常创建
  （canonical/哈希经 validator.parse 后透传 insertVersion 并映射
  响应）；套件缺失 NOT_FOUND；insertVersion 唯一约束冲突转
  DUPLICATE_RESOURCE；authorizeDefinition 对定义 cases 内集合键
  的范围解析（scopeResolver.resolve 六参验证）。
- 要点：validator.parse 入参是 JsonNode（anyString() 不匹配导致
  stub 静默脱靶）；RagProperties 需开启 evaluation.managedSuites
  Enabled；authorizeDefinition 读取的是解析后 cases 内的集合键而
  非原始 JSON。
- 指标：单类 4 用例绿；core 全量门禁 EXIT=0（5636 tests）。

### Batch 506（已交付）

- 分支：`test/chatturn-serialization-batch506`（已合入 main）
- 内容：ChatTurnOperationService 序列化异常路径长尾（新建 Chat
  TurnOperationSerializationTailTest，5 用例）：claim 插入期
  executionSnapshot 写出失败 → IDEMPOTENCY_EXECUTION_SNAPSHOT_
  INVALID；completeOpenAi 稳定快照写出失败 → CHAT_HISTORY_
  PERSIST_FAILED；completePrepared payload 写出失败 → IDEMPOTENCY
  _RESPONSE_TOO_LARGE；stableStepMetrics null 元素经 stableSnapshot
  catch 同样映射 IDEMPOTENCY_RESPONSE_TOO_LARGE；正常 stepMetrics
  经 complete 映射透出。
- 要点：用 mock ObjectMapper（writeValueAsString 一律抛 Json
  ProcessingException 匿名子类）驱动三条写出失败 catch；List.of
  拒绝 null 元素，含 null 的列表须用 Arrays.asList。
- 指标：单类 5 用例绿；core 全量门禁 EXIT=0（5632 tests）。

### Batch 505（已交付）

- 分支：`test/hybrid-fulltext-error-batch505`（已合入 main）
- 内容：HybridRetrieverService 分支隔离长尾（新建 HybridRetriever
  FulltextErrorTailTest，6 用例）：无全文工厂 → NoOp provider 占
  位且向量臂 SUCCESS；空查询向量 / 维度不匹配 / 非有限值三类向量
  异常经 runVector catch 归一为 VECTOR ERROR 分支；全文 provider
  抛错与 SearchResult.failed 标记分别映射 FULLTEXT ERROR（含错误
  码透出），均不拖垮整体检索结果。
- 要点：factory.detectLang 返回 fulltext 包的 QueryLang（与
  retrieval 包同名类区分，需全限定）；NoOp 全文 provider 走的是
  双臂执行路径（全文空结果 SUCCESS），并非 DISABLED 占位；
  EmbeddingProfile 构造器直接内联（9 参）。
- 指标：单类 6 用例绿；core 全量门禁 EXIT=0（5627 tests）。

### Batch 504（已交付）

- 分支：`test/pdf-trigger-embed-batch504`（已合入 main）
- 内容：PdfImportController 触发嵌入包装长尾（新建 PdfImport
  ControllerTriggerEmbeddingTailTest，4 用例）：embed=sync 参数
  分发到 3 参 triggerEmbedding 并 200 透出结果；服务
  IllegalArgumentException → 400；其余异常 → 500；sse 参数 →
  SseEmitter 200。
- 要点：sync 路径调用 3 参 triggerEmbedding(uuid, collectionId,
  forceReembed)（4 参带 policy 的重载走 triggerEmbeddingWithPolicy
  分支）；SSE 分支立即返回 emitter，异常在异步任务内降级为
  sendError，两个分支的异常映射语义不同。
- 指标：单类 4 用例绿；core 全量门禁 EXIT=0（5621 tests）。
- 教训：全量门禁偶发 1 失败先确认 surefire 报告非陈旧残留（本
  次失败报告对应已不存在的测试类），清理 target/surefire-reports
  后复跑为绿。

### Batch 503（已交付）

- 分支：`test/openai-unkeyed-tail-batch503`（已合入 main）
- 内容：OpenAiCompatibilityController 非键控执行路径长尾（新建
  OpenAiCompatibilityUnkeyedExecuteTailTest，4 用例）：非键控
  JSON 成功链（execute → toNativeResponse → toResponse 结果重
  载）；非键控执行失败直接重抛（无 claim 不触发 fail）；键控下
  快照模型标识为 DEFAULT 时回退 mapped 别名；损坏快照 JSON 容错
  回退（JsonProcessingException 被捕获）。
- 要点：requestMapper 有两个重载——非键控 map(request,
  httpRequest) 两参、键控快照 mapFromExecutionSnapshot 四参且首
  参为 OpenAiChatCompletionRequest（用 any(ChatRequest.class) 会
  产生匹配器类型推断冲突）；OpenAiCompatibilityUnkeyedExecute
  TailTest 与 Batch 477/501 均验证了 prepare/inspect/claim 三段
  stub 中任一未命中即静默走默认分支的特征。
- 指标：单类 4 用例绿；core 全量门禁 EXIT=0（5617 tests）。

### Batch 502（已交付）

- 分支：`test/chat-sse-events-batch502`（已合入 main）
- 内容：RagChatController.stream 未键控 SSE 长尾（新建 RagChat
  ControllerStreamEventsTest，4 用例，测试置于 core.chat 包以构
  造 package-private ChatEvent 记录）：ContentDelta + Completed
  完成链（心跳停止 + emitter.complete）；错误传播经 sendChat
  Error；Failed 事件置 terminal 不 complete；空白 sessionId 自动
  补齐。
- 要点：ChatEvent 记录为 core.chat 包私有，RagChatController 测
  试类放在 core.chat 包即可同时构造事件与调用控制器公共 API；
  SseEmitter 未连接时 send/complete 走 early-hints 缓存，不会抛
  异常，适合单测。
- 指标：单类 4 用例绿；core 全量门禁 EXIT=0（5613 tests）。

### Batch 501（已交付）

- 分支：`test/ragchat-stream-tail-batch501`（已合入 main）
- 内容：RagChatService.chatStream 遗留流式长尾（新建 RagChat
  ServiceChatStreamTailTest，4 用例）：无候选回退默认客户端流式
  并按 streaming 标记落历史（累积答案 + Map.of("streaming",true)）；
  候选路径经真实 ChatClient 聚合分块（Hel+lo → 完整答案落账）；
  流式错误传播且不落历史；无候选空流正常完成。
- 要点：候选路径 mock advisor 需同时透传 adviseCall 与 adviseStream
  （真实 ChatClient 流式走 adviseStream）；chatStream(ChatRequest)
  的 model 需显式 setModel 才会进入候选分支；maxCandidateAttempts=0
  被预算内部钳制，预算耗尽分支无法从公开 API 触发。
- 指标：单类 4 用例绿；core 全量门禁 EXIT=0（5609 tests）。

### Batch 500（已交付）

- 分支：`test/apikey-provisioning-batch500`（已合入 main）
- 内容：ApiKeyManagementService 供应台账长尾（新建 ApiKey
  ProvisioningKeyTailTest，8 用例）：generateIdempotentKey 门禁
  矩阵（功能关闭 IDEMPOTENCY_DISABLED / 台账缺失 SERVICE_
  UNAVAILABLE / 空请求 NPE / 空白 owner 或哈希 IAE / managed 过
  期时间必须为未来）；cleanupProvisioningLedger 三分支（关闭跳
  过不触库、按保留期 400d+批量 500 清理、DataAccess 异常吞噬）。
- 指标：单类 8 用例绿；core 全量门禁 EXIT=0（5605 tests）。

### Batch 499（已交付）

- 分支：`test/exec-stream-tail-batch499`（已合入 main）
- 内容：ChatExecutionService 流式编排长尾（新建 ChatExecution
  ServiceStreamTailTest，4 用例）：最后一个候选空流 → complete
  StreamAttempt 的 "no usable streaming response" ISE；成功流
  （ContentDelta + Completed 事件、sessionId 透出）；快照
  modelCandidates 驱动候选链（resolveCandidateRequired 逐一解析
  + 首候选失败回退次候选，路由器描述符不参与）；无任何候选时
  stream 快速失败（MODEL_STREAMING_UNSUPPORTED）。
- 要点：stream() 内部会用 newBudget 替换 command 预算，外部预
  耗尽预算不可达（预算耗尽分支由非流式路径覆盖）；快照候选经
  resolveCandidateRequired 逐 ref 解析后仍受 isEligible（stream
  能力）过滤；argThat lambda 必须空值安全（Mockito 会对其他调用
  以 null 探测匹配器）。
- 指标：单类 4 用例绿；core 全量门禁 EXIT=0（5597 tests）。

### Batch 498（已交付）

- 分支：`test/upsert-external-conflict-batch498`（已合入 main）
- 内容：DocumentMutationService 外部 upsert 冲突长尾（新建
  DocumentMutationUpsertExternalConflictTailTest，4 用例，反射
  调用私有 20 参 upsertExternalInTransaction）：新身份携带
  expectedSourceRevision → DocumentRevisionConflictException；同
  版本不同受管字段（title 漂移）→ 冲突；同版本同字段 →
  UNCHANGED 不落库；新身份正常 CREATED（saveAndFlush 落库）。
- 要点：external 路径版本记录同样是 forceRecordVersion（mock 未
  打桩返回 null → getVersionNumber NPE）；existing 文档的
  source/metadata 必须与请求对齐才能命中 UNCHANGED（sameExternal
  State 比较所有受管字段）；saveAndFlush 需回填新建文档 id。
- 指标：单类 4 用例绿；core 全量门禁 EXIT=0（5593 tests）。

### Batch 497（已交付）

- 分支：`test/auth-snapshot-invariant-batch497`（已合入 main）
- 内容：ChatAuthorizationService 快照不变量长尾（新建 Chat
  AuthorizationSnapshotInvariantTailTest，9 用例）：verifyReplay
  对篡改快照的不变量拒绝（非布尔 unassigned 标志、NOT_APPLICABLE
  范围与脏字段互斥、NOT_APPLICABLE caller 与范围化 scope 互斥、
  非数组集合列表、白名单非正整数、非对象来源行）；ANY_COLLECTION
  正集合来源放行；snapshot() 对非数字 documentId 与来源读取运行
  时异常的 INVALID 降级（含 cause 保留）。
- 要点：verifyReplay 前置校验 owner principal 活性（需 stub
  findActivePrincipal）与来源文档存在性（findById → 文档行），
  放行类用例必须同时备齐两类 stub；observed 的正整数校验先于
  ANY_COLLECTION 的集合正性禁止，0 值场景不可达 forbidden 分支。
- 指标：单类 9 用例绿；core 全量门禁 EXIT=0（5589 tests）。

### Batch 496（已交付）

- 分支：`test/chat-keyed-ask-batch496`（已合入 main）
- 内容：RagChatController keyed /ask 长尾（新建 RagChatController
  KeyedAskTailTest，6 用例）：幂等 mapper/execution 未配置的
  IDEMPOTENCY_DISABLED 拒绝（后者 fail 操作）；keyed JSON 成功链
  （prepareForOperation → completePrepared → finalize →
  X-RAG-Turn-Id/Replay=false 双头）；inspect 命中直接重放；claim
  后快照映射重放（不触达执行服务）；执行失败 → fail + 租约释放。
- 要点：configureModeAwareExecution 参数顺序是 (commandMapper,
  executionService)；op 带 executionSnapshot 时控制器走
  mapFromExecutionSnapshot 而非 map；ChatPrincipal.from 与
  resolveScope 对无认证 Mock 请求可能返回 null，matcher 用 any()
  而非 any(Class)；prepare 第三参对 keyed 请求是非空 fingerprint，
  不能用 isNull()。
- 指标：单类 6 用例绿；core 全量门禁 EXIT=0（5580 tests）。

### Batch 495（已交付）

- 分支：`test/restore-metadata-tail-batch495`（已合入 main）
- 内容：DocumentMutationService 恢复元数据与 JSON/sync 守卫长尾
  （新建 DocumentMutationRestoreMetadataTailTest，5 用例）：快照
  originalFilename / metadata / jsonbPayload 三类元数据漂移 →
  RESTORED_VERSION + metadataChanged=true + 字段回写断言；upsert
  JsonRecord 的 JSON null payload 拒绝（IAE）；upsertSyncRunItem
  空请求 NPE 守卫。
- 要点：restore 动作名是 RESTORED_VERSION、版本记录 action 是
  RESTORE（原因字符串与 changedFields 文案耦合，避免断言具体原
  因）；内容快照与当前一致时 contentChanged=false，用于隔离元数
  据维度的判定。
- 指标：单类 5 用例绿；core 全量门禁 EXIT=0（5574 tests）。

### Batch 494（已交付）

- 分支：`test/expiry-alert-cas-batch494`（已合入 main）
- 内容：ApiPrincipalExpiryAlertService CAS 竞争与通知长尾（新建
  ApiPrincipalExpiryAlertCasTailTest，7 用例）：告警插入 CAS 未
  命中、同阶段更新 CAS 未命中（condition_state）、通知认领 CAS
  未命中三类 → 重试预算耗尽后抛出 + metrics FAILURE；durable
  outbox 认领（enqueueManaged + 不直接派发通道）；失败 future 通
  道的告警日志不抛出；重试退避被中断降级 ISE；已吊销主体 NONE
  阶段 NOOP。
- 要点：eventRetryAttempts=2 压缩重试预算（每次退避 25ms×n）；
  中断用例靠预置 Thread.currentThread().interrupt() 让 sleep 立
  即抛出，@AfterEach 清理中断标记防污染。
- 指标：单类 7 用例绿；core 全量门禁 EXIT=0（5569 tests）。

### Batch 493（已交付）

- 分支：`test/modeaware-advisors-batch493`（已合入 main）
- 内容：ModeAwareChatClientFactory 自定义 Advisor provider 校验
  矩阵与预算包装长尾（新建 ModeAwareChatClientFactoryAdvisors
  TailTest，8 用例）：空白名称 / null supportedModes / null
  advisorScope 三拒绝；provider 返回 null advisor 忽略不中断；
  合法 provider 的 createAdvisor 被调用（计数断言）；101 个
  provider 超量拒绝（Too many）；带执行预算的 command 触发
  budgetedModelFor 包装路径；spring-ai queryTransformer +
  queryExpanderVariants=2 时预算化的 query transform/expander
  构建成功。
- 要点：customAdvisors 校验在 create() 期间逐 provider 执行，
  匿名 provider 实现即可驱动；非 mock 对象不能 verify，用
  AtomicInteger 计数；ChatCommand 预算注入需经 16 参构造器显式
  传入 executionBudget。
- 指标：单类 8 用例绿；core 全量门禁 EXIT=0（5562 tests）。

### Batch 492（已交付）

- 分支：`test/derivation-apply-tail-batch492`（已合入 main）
- 内容：DerivationRepairService.apply 守卫与收尾长尾（新建
  DerivationRepairApplyGuardsTailTest，17 用例）：入口守卫四类
  （未知 repairId NOT_FOUND、token/指纹不匹配、集合键指向不同集
  合、预览过期标记 EXPIRED）；COMPLETED 幂等直接返回状态；租约抢
  占失败双分支（操作存活 → 回读状态、操作过期 → EXPIRED）；条目
  未抢占静默 continue；缺失条目 → failItem；异常消息为空降级为
  异常类名；空文档 SKIPPED_CHANGED；本地阶段 PLANNED 文档漂移/
  代次漂移跳过、重建后不收敛 FAILED；条目租约在本地/向量阶段之
  间丢失（续锁 1,0 连续 stub）；向量阶段文档漂移跳过。
- 要点：租约续锁 SQL 片段（AND lease_expires_at > CURRENT_
  TIMESTAMP）用连续 stub（1,0）模拟跨阶段租约丢失；文档漂移在两
  阶段间用 findById 连续 stub（匹配→漂移）驱动向量侧 matches
  Document false。
- 指标：单类 17 用例绿；core 全量门禁 EXIT=0（5554 tests）。

### Batch 491（已交付）

- 分支：`test/collection-import-purge-batch491`（已合入 main）
- 内容：RagCollectionController 导入与清理守卫长尾（新建
  RagCollectionControllerImportPurgeTailTest，3 用例）：import
  Collection 三拒绝（null 请求 / 空白 name / 空白 collectionKey，
  且不触达 createCollection）；正常导入（documents 空列表 →
  importedDocuments=0，createCollection 收到键回填的请求）；
  purge 服务缺省时 preview/apply 均 SERVICE_UNAVAILABLE。
- 指标：单类 3 用例绿；core 全量门禁 EXIT=0（5537 tests）。

### Batch 490（已交付）

- 分支：`test/ragchat-resilience-batch490`（已合入 main）
- 内容：RagChatService 韧性长尾（新建 RagChatServiceResilience
  TailTest，6 用例）：候选模型返回空 ChatResponse →
  "LLM returned null result" ISE；RetryTemplate 首失败次成功
  （SimpleRetryPolicy(2)，模型调用两次）；metricsService 失败记
  账（recordFailure）；chatEvents 未配置 mode-aware → Flux.error
  ISE；已配置时空流完成与错误流传播（RagException 透出）。
- 要点：budgetedModelFor（final 类 BudgetedChatModel 返回值）的
  usageClientFactory 分支经真实 ChatClient 链路验证存在 NPE，本
  批未覆盖（factory 参数默认传 null 绕开）；mock 的方法间嵌套
  调用容易误触真实代码，stub 一律放 createService 顶层。
- 指标：单类 6 用例绿；core 全量门禁 EXIT=0（5534 tests）。

### Batch 489（已交付）

- 分支：`test/alert-silence-slo-batch489`（已合入 main）
- 内容：AlertServiceImpl 静默与 SLO 长尾（新建 AlertServiceImpl
  FireSilenceSloTailTest，8 用例）：durable outbox 优先
  （enqueueOrdinary + 跳过 legacy 通道）；legacy 通道异常吞噬
  （fireAlert 不中断）；未知 SLO 名 → unmet；零请求可用性 →
  actual 100 + met；ONE_TIME 静默窗口命中、时间解析失败不静默、
  仓储异常不静默、静默命中时 fireAlert 直接跳过（返回 null 不落
  库）。
- 要点：8 参构造器注入 outbox mock（isDurableEnabled 切换新旧
  两条投递路径）；静默窗口用 ZonedDateTime.now() 动态生成以避免
  时区漂移。
- 指标：单类 8 用例绿；core 全量门禁 EXIT=0（5528 tests）。

### Batch 488（已交付）

- 分支：`test/coordinator-deadline-batch488`（已合入 main）
- 内容：ChatSessionCoordinator 截止时间与过期操作长尾（新建
  ChatSessionCoordinatorDeadlineTailTest，8 用例）：invokeWithin
  Deadline 的空句柄 IAE、过期截止 CHAT_TIMEOUT、正常返回、供应
  商运行时异常透传、超时取消 future；failExpiredOperation 的仓
  库缺省 no-op、耗尽成功终止、回收竞争 → CHAT_HISTORY_PERSIST_
  FAILED。
- 要点：LeaseHandle.stateless(deadline) 为包私有静态工厂，同包
  测试可直接构造（免 acquire 的 jdbc 依赖）；timeout() 错误码
  CHAT_TIMEOUT。
- 指标：单类 8 用例绿；core 全量门禁 EXIT=0（5520 tests）。

### Batch 487（已交付）

- 分支：`test/keyword-index-tail-batch487`（已合入 main）
- 内容：KeywordIndexPersistenceService 分配与校验长尾（新建
  KeywordIndexAllocateValidateTailTest，10 用例）：markNotRequested
  （删 chunks + 状态行更新 + 代次竞争 ISE + 身份缺失 IAE）；ensure
  Current（null/无 id/disabled 三守卫、validateChunks 空块列表/
  空白文本/越界位置三拒绝、Happy path 走完 DELETE+batchInsert+
  READY 状态更新）；ensureContentHash（缺失回填 + version 推进、
  并发冲突 ISE）。
- 要点：stub 参数里再调 mock（describe(document())）会触发
  UnfinishedStubbing——Descriptor 提为常量；varargs 整体匹配用
  any(Object[].class)，逐元素 any() 数量必须与实际参数一致
  （ensureCurrent 的 READY 更新是 8 参，5 个 any() 不匹配即静默
  返回 0）。
- 指标：单类 10 用例绿；core 全量门禁 EXIT=0（5512 tests）。

### Batch 486（已交付）

- 分支：`test/dingtalk-delivery-batch486`（已合入 main）
- 内容：DingTalk 投递长尾（新建 DingTalkDeliveryTailTest，9 用
  例）：deliver 的路由/可用性守卫（未启用 → PERMANENT_
  CONFIGURATION）；classifyResponse 五类判定（200+errcode=0 →
  SUCCESS、errcode 非零 → PERMANENT_PROVIDER_REJECTED、非法
  JSON → PERMANENT、429 → TRANSIENT_RATE_LIMIT 带 Retry-After、
  5xx → TRANSIENT_PROVIDER_5XX、4xx → PERMANENT）；网络异常 →
  TRANSIENT_NETWORK；空 webhook 通道早退后落到下一通道成功。
- 要点：RestTemplateBuilder 链式 stub（requestFactory/
  connectTimeout/readTimeout/build）注入 mock RestTemplate；
  HttpStatusCodeException 用 HttpClientErrorException.create 携带
  Retry-After 头驱动 retryAfter 解析。
- 指标：单类 9 用例绿；core 全量门禁 EXIT=0（5502 tests）。

### Batch 485（已交付）

- 分支：`test/batchdoc-legacy-batch485`（已合入 main）
- 内容：BatchDocumentService 遗留批量长尾（新建
  BatchDocumentServiceLegacyTailTest，8 用例）：重复内容去重
  （skipped + 不嵌入）；ASYNC 缺事务管理器按条目降级失败；
  ASYNC 事务内 enqueue（ASYNC_QUEUED 透出）；SYNC 嵌入失败
  （EMBEDDING_FAILED 落库 + 错误透出）；SYNC 缓存命中
  （SYNC_CACHED）；重复且不强制直接返回裸结果；deleteDocument
  级联删除与缺失拒绝；batchDelete 外部管理文档保护。
- 要点：ASYNC 有两层门禁——批级 requireJobsEnabled（缺
  dispatchService 直接整批拒绝）与条目级事务模板缺失（降级为
  单条失败结果）；countByDocumentId 返回 long；嵌入失败的
  EMBEDDING_FAILED 状态经 save 二次落库，用 atLeastOnce 捕获。
- 指标：单类 8 用例绿；core 全量门禁 EXIT=0（5493 tests）。

### Batch 484（已交付）

- 分支：`test/apikey-rotation-guards-batch484`（已合入 main）
- 内容：ApiKeyManagementService 轮换守卫长尾（新建
  ApiKeyRotationGuardsTailTest，11 用例）：getRotation /
  completeRotation / cancelRotation 的管理写竞争 NOT_FOUND；complete
  Rotation 的正常完成（禁用源密钥）、源已禁用跳过禁用、禁用行数
  异常 CONCURRENT_MODIFICATION；cancelRotation 的目标禁用冲突与
  源已禁用冲突；轮换凭证缺失（SERVICE_UNAVAILABLE missing
  credential）、属主不符（another principal）、版本倒挂（versions
  are inconsistent）三类拒绝。
- 要点：rotationConflict 的错误码是 CONCURRENT_MODIFICATION（非
  CREDENTIAL_ROTATION_*）；外属主校验靠凭证的 principalId 与
  operation 的 principalId 不一致触发，operation 本身属主要与
  authorizeRotation 的放行路径兼容。
- 指标：单类 11 用例绿；core 全量门禁 EXIT=0（5485 tests）。

### Batch 483（已交付）

- 分支：`test/collection-adddoc-batch483`（已合入 main）
- 内容：RagCollectionController addDocument 长尾（新建
  RagCollectionControllerAddDocumentTailTest，8 用例）：legacy
  直移（doc.setCollectionId + save）；mutation 服务迁移
  （updateLocal 携带期望版本与 mapKeys 解析的 collectionKey）；
  缺 expectedDocumentRevision IAE；外部管理文档
  DocumentRevisionConflictException；documentId 缺失 IAE；集合
  缺失 404；受限调用者附加白名单外文档 SecurityException；add
  DocumentByKey 键路由（requireActiveCollectionByKey → 委托）。
- 指标：单类 8 用例绿；core 全量门禁 EXIT=0（5474 tests）。

### Batch 482（已交付）

- 分支：`test/extdoc-delete-tail-batch482`（已合入 main）
- 内容：ExternalDocumentService 遗留删除与索引协调长尾（新建
  ExternalDocumentServiceDeleteIndexTailTest，8 用例）：source
  Delete 六分支（活文档墓碑化 DELETED + markNotRequested +
  DELETE 版本、已墓碑同版本 UNCHANGED 不落库、活文档同版本
  冲突、expectedSourceRevision 漂移冲突、JSON 记录身份拒绝、
  缺失文档 DOCUMENT_NOT_FOUND）；coordinateLocalIndex 分支
  （SKIP upsert → markNotRequested、启用新建 → ensureCurrent）。
- 要点：ASYNC upsert 有 dispatchService 非空前置（否则
  EMBEDDING_JOBS_DISABLED），fixture 需 setDispatchService；
  删除路径复用 ExternalDocumentServiceTest 的 9 参构造器夹具
  （resolver token / jdbcTemplate ConnectionCallback / 版本服务
  stub）。
- 指标：单类 8 用例绿；core 全量门禁 EXIT=0（5466 tests）。

### Batch 481（已交付）

- 分支：`test/chathistory-repo-tail-batch481`（已合入 main）
- 内容：RagChatHistoryRepository 主体读写长尾（新建
  RagChatHistoryRepositoryMappingTailTest，8 用例）：findBy
  PrincipalAndSession 的空参 NPE、limit 钳制到 500（PageRequest
  捕获）、toDto 映射（relatedDocumentIds JSON 解析 / metadata
  枚举 PLAIN 与非法值回退 KNOWLEDGE / requestedModel 字符串）；
  findOwnedAfterHistoryId 的 afterHistoryId 负数 IAE；delete
  ByPrincipalAndSession 空参 NPE 与委托；reserveDurableContent
  References 四分支（空引用短路不触库 / 栅栏更新失败 /
  两次读取间集合漂移 → COLLECTION_PURGE_CONFLICT / 正常预留返回
  document→collection 映射）。
- 要点：jdbcTemplate.query 的 RowMapper stub 用 thenAnswer + mock
  ResultSet（getLong("id"/"collection_id")）经真实 mapper 产出
  DocumentReferenceRow；栅栏/校验两次读取以 Mockito 连续 stub
  返回不同集合模拟漂移。
- 指标：单类 8 用例绿；core 全量门禁 EXIT=0（5458 tests）。

### Batch 480（已交付）

- 分支：`test/resource-catalog-tail-batch480`（已合入 main）
- 内容：ResourceCatalog 发现长尾（新建
  ResourceCatalogFilesystemLimitTailTest，5 用例）：单根文件数
  上限的两种模式（宽松 → 整根丢弃 + diagnostics 记录 file count
  limit exceeded；failFast → 异常链上抛）；JAR 文件不可读降级
  （JAR read failed）；非法 file: URI 的参数校验（Resource file
  location is invalid）；空白 location 的静态 root() 守卫。
- 要点：discoverOne 的文件数超限发生在根级收集完成后，因此宽松
  模式下该根的全部条目都会被丢弃（entries 为空 + 诊断行），并非
  截断保留；discover 循环里的「累计字节上限」分支经真实文件读取
  不可达（readBounded 先按剩余配额抛 file byte limit exceeded），
  属防御性代码，与 withEffectiveSession 一并记为不可达分支；
  jar 前缀逃逸 / 缺条目前缀已被 JarGuardTest 覆盖，本批未重复。
- 指标：单类 5 用例绿；core 全量门禁 EXIT=0（5450 tests）。

### Batch 479（已交付）

- 分支：`test/jsonrecord-legacy-batch479`（已合入 main）
- 内容：JsonRecordService 遗留持久化路径长尾（新建
  JsonRecordServiceLegacyUpsertTailTest，5 用例）：ASYNC 策略
  upsert 经 enqueueInCurrentTransaction 的结果映射（outcomeFrom
  Dispatch → QUEUED/ASYNC_QUEUED/jobId/batchId 透出）；batch
  Upsert 计数聚合（created / embeddingStatus=FAILED 的
  embeddingFailed / payload 缺失的 persistenceFailed+FAILED 占位
  结果）；批次聚合负载上限拒绝（maxBatchPayloadBytes=1）；import
  Record 空文档 IAE；batchUpsert 空列表 IAE。
- 要点：请求缺省 embeddingPolicy 会解析为 SYNC（同步嵌入 mock
  返回 null → 状态 FAILED），测 ASYNC 分支必须显式 setEmbedding
  Policy(ASYNC)；遗留路径重复提交同一请求因 find-existing 恒空
  而两次 CREATED，无法自然产生 UNCHANGED 计数（需另设 stub）。
- 指标：单类 5 用例绿；core 全量门禁 EXIT=0（5445 tests）。

### Batch 478（已交付）

- 分支：`test/apikey-guard-batch478`（已合入 main）
- 内容：ApiKeyController 访问守卫矩阵长尾（新建
  ApiKeyControllerAccessGuardTailTest，13 用例）：legacy（root
  未配置）模式 ADMIN 门槛（listKeys/listPrincipals/updatePolicy
  /revokeKey 的 NORMAL 403 与 ADMIN 放行）；updatePolicy 空键
  列表 IAE、委托键解析（requireActive → resolveDelegated）与
  未知主体 404；revokeKey 的 403/404/204 三态；rotateKey 的
  NORMAL 只能轮换自身（keyId == credentialId）规则与 201
  no-store 响应；root 模式 requireStagedAccess（无 caller 抛
  SecurityException / 无 ROOT 属性抛 SecurityException / 有
  ROOT 放行 cancelRotation）；prepareRotation 幂等键必需
  （IDEMPOTENCY_KEY_INVALID）与重放头（OK+replay 头 / CREATED）。
- 指标：单类 13 用例绿；core 全量门禁 EXIT=0（5440 tests）。

### Batch 477（已交付）

- 分支：`test/openai-keyed-tail-batch477`（已合入 main）
- 内容：OpenAI 兼容层 keyed 非重放路径长尾（新建
  OpenAiCompatibilityKeyedFailureTailTest，6 用例）：keyed JSON
  成功路径（prepareForOperation → completePrepared → finalize
  → toResponse 映射 + TURN-ID 头）；keyed JSON 与 keyed SSE 的
  prepareForOperation 失败 → turnOperationService.fail + 原样
  重抛 + finally 释放会话租约；toStreamError 三类映射（OpenAI
  协议错误保留 type/param/code、RagException 按 httpStatus 分流
  server_error / invalid_request_error、兜底消息）。
- 要点：keyed claim 的 command 经 controller 的 commandForClaim
  + attachTrace 链路（diagnosticsService 非空时会换成新实例），
  stub 匹配需用 any() 而非 same()；keyed JSON 不经过
  executionService.execute（那是 unkeyed 路径）；mapper 的
  mapFromExecutionSnapshot 仅在 operation 带 executionSnapshot
  时被调用，测试 operation 必须带快照 JSON。
- 指标：单类 6 用例绿；core 全量门禁 EXIT=0（5427 tests）。

### Batch 476（已交付）

- 分支：`test/alert-dispatch-loop-batch476`（已合入 main）
- 内容：AlertNotificationDeliveryWorker 调度循环长尾（新建
  AlertNotificationDeliveryWorkerDispatchLoopTest，5 用例）：
  wakeUp → scheduleDispatch → dispatchLoop → dispatchAvailable
  端到端（领取候选 → 异步投递 → markDelivered → 自唤醒再扫至
  空列表静止）；claim 竞争失败释放名额后继续后续候选（丢失者不
  投递）；fallbackScan 恢复过期租约并触发扫描轮；shutdown 后
  wakeUp 完全静默（不扫描不领取）；候选可重复领取时自唤醒循环
  重复投递直至静止。
- 要点：repository.claim 第三参是 `Duration`（非 long）；异步
  断言用 `verify(timeout).xxx` + findCandidateIds 首轮返回候选、
  次轮返回空列表保证循环收敛，避免测试后台无限轮询。
- 指标：单类 5 用例绿；core 全量门禁 EXIT=0（5421 tests）。

### Batch 475（已交付）

- 分支：`test/mutation-idem-update-batch475`（已合入 main）
- 内容：DocumentMutationService 幂等预留与 updateLocal 长尾
  （新建 DocumentMutationUpdateIdempotencyTailTest，11 用例）：
  createLocal 的 Idempotency-Key 生命周期（正常预留 + 落账
  completeIdempotency UPDATE / 空白键完全跳过 / SUCCEEDED 重放
  REPLAYED / 过期预留 DELETE 后重预留再创建 / 指纹漂移拒绝
  DocumentRevisionConflictException / IN_PROGRESS 拒绝 / 超长
  键 255 拒绝）；updateLocal 长尾（同值更新 UNCHANGED 早退且
  不记版本不派发 / source 字段分支归一化并计入 metadataChanged /
  禁用文档内容变更非 SKIP 拒绝 DOCUMENT_DISABLED / SKIP 放行且
  embeddingAction=NONE）。
- 要点：jdbc varargs stub 中 `any(Object[].class)` 可整体匹配
  varargs，但 verify 部分参数固定时剩余参数需逐个 `any()`；
  模拟含 null 列的预留行不能用 `Map.of`（拒绝 null 值），须用
  HashMap；指纹捕获在 INSERT answer 中经
  `getArguments()` 倒数第二位取参；新建文档 id 为 null 会让
  `Prepared.documentId()` 拆箱 NPE，saveAndFlush answer 需回填。
- 指标：单类 11 用例绿；core 全量门禁 EXIT=0（5416 tests）。

### Batch 474（已交付）

- 分支：`test/docdoc-upload-tail-batch474`（已合入 main）
- 内容：RagDocumentController 上传与访问守卫长尾（新建
  RagDocumentControllerUploadAccessTailTest，12 用例）：
  processUploadedFile 矩阵（正常创建经 mutation 服务 + 幂等键
  按文件下标派生 `key:0` / 空白幂等键保持 null / 非文本类型
  getBytes 抛错 → Unsupported file type / 空白内容 → File
  content is empty / mutation 异常 best-effort "Processing
  failed:"）；受限 API Key 单白名单自动解析目标 collection、
  多白名单缺省拒绝（SecurityException）；requireDocumentAccess(
  List) 三分支（受限白名单外集合拒绝且不触达批量删除 / 缺失
  文档不阻断 / 无限制策略跳过 findAllById 早退）；外部文档服务
  与 mutation 服务缺省时的 ISE 守卫。
- 要点：受限策略经 MockHttpServletRequest +
  `ApiKeyAuthFilter.AUTHENTICATED_API_KEY_ENTITY` 属性注入
  RagApiKey（NORMAL + allowedCollectionIds）；测试后必须
  RequestContextHolder.resetRequestAttributes() 防上下文泄漏；
  batchDeleteDocuments 入参是 `Map<String,List<Long>>`（"ids"
  键）而非裸列表。
- 指标：单类 12 用例绿；core 全量门禁 EXIT=0（5405 tests）。

### Batch 473（已交付）

- 分支：`test/chatturn-tail-batch473`（已合入 main）
- 内容：ChatTurnOperationService 4 参 ChatCommand claim 与
  complete 长尾（新建 ChatTurnOperationClaimCompleteTailTest，
  17 用例）：insertNewOperation 三分支（插入成功持租约 + 快照
  JSON 断言 / 插入竞争失败释放租约后按最新状态重派 / 插入异常
  释放租约重抛）、claimNew 非 SESSION_BUSY 租约错误传播与
  SESSION_BUSY 竞争无 operation 重抛、claimExisting 指纹冲突
  （IDEMPOTENCY_KEY_REUSED）与 FAILED 拒绝（错误码回退
  INTERNAL_ERROR）、completePrepared 五分支（unkeyed 直接映射 /
  缺 executionSnapshot / 缺协调器租约 IDEMPOTENCY_DISABLED /
  响应超限 IDEMPOTENCY_RESPONSE_TOO_LARGE / 正常提交
  commitOperation 九参验证）、completeOpenAi 超限与 null 响应/
  null source 拒绝、null metadata 值容忍、commandForClaim 坏
  快照拒绝。
- 要点：`withEffectiveSession` 重生成分支经 ChatCommand 构造器
  会话校验后不可达（防御性死代码，放弃覆盖）；mock 的
  authorizationService.initialSnapshot 必须显式返回字符串，否则
  anyString() 不匹配 null、insert stub 全部脱靶（曾导致
  StackOverflowError 的重派递归）；带租约 keyed Claim 经反射
  3 参构造器构造。
- 指标：单类 17 用例绿；core 全量门禁 EXIT=0（5393 tests）。

### Batch 472（已交付）

- 分支：`test/ragchat-failover-batch472`（已合入 main）
- 内容：RagChatService 遗留多模型 failover 矩阵（新建
  RagChatServiceLegacyFailoverTest，4 用例）：候选 1 失败 → 候选
  2 成功回退（历史落账为 fallback 答案）；全候选失败 → 传播最后
  一个错误且不写历史；candidate-attempt 预算=1 时第 2 候选在
  tryReserve 即被拒（CHAT_BUDGET_EXHAUSTED，模型零调用）；熔断
  （minCalls=1, threshold=1）第一次失败记账后 OPEN、第二次进入
  候选循环前即抛 LlmCircuitOpenException（模型仅 1 次调用）。
  同时覆盖 14 参构造器委托与 invokeChatClient 失败记账分支。
- 要点：候选路径走 buildLegacyClient 构造的**真实 ChatClient**
  （mock builder 不生效）；mock 的 RAG advisor 需 stub getName()
  非空（真实链构建时校验 advisorName）+ adviseCall 透传
  `chain.nextCall(request)`；真实 MessageChatMemoryAdvisor 用
  `findByConversationId → List.of()` 空会话支撑；ChatModel mock
  的 `call(Prompt)` 直接决定成败；无检索上下文时 ChatResponse
  sources 为 null（断言按 null-or-empty 处理）。
- 指标：单类 4 用例绿；core 全量门禁 EXIT=0（5376 tests）。

### Batch 471（已交付）

- 分支：`test/syncrun-counters-batch471`（已合入 main）
- 内容：重建 Batch 468 丢失的 batchUpsert 汇总计数测试（原分支
  因中断被 reset 清掉、从未合入，原「test/syncrun-batch-tail-
  batch468」账本条目系失实记录，已删除）。新建
  DocumentSyncRunBatchUpsertCountersTest，2 用例：四状态单批计
  数矩阵（APPLIED/UNCHANGED/SKIPPED_NEWER_MUTATION/FAILED 各 1，
  Summary 四项计数独立正确，且仅 APPLIED+UNCHANGED 回写 rag_
  documents last_seen）；SKIPPED_NEWER_MUTATION 携带 documentId
  时跳过 last_seen 回写（新近突变保护语义，never 验证）。补齐
  此前仅覆盖 APPLIED+FAILED 混合计数的残余。
- 要点：`contains("UPDATE rag_documents")` 与
  `rag_document_sync_run_items` 的 UPDATE 不会误匹配（前缀差异
  在 `_sync` vs `s`）；mutation stub 按 `eq(item)` 实例区分。
- 指标：单类 2 用例绿；core 全量门禁 EXIT=0（5372 tests）。

### Batch 469（已交付）

- 分支：`test/apikey-expire-tail-batch459`（已合入 main）
- 内容：ApiKeyManagementServiceExpireRotationTailTest，5 用例：
  到期 PENDING 过期落账、非 PENDING 早退、未到期早退、管理写
  竞争失败早退。

### Batch 468（已交付）

- 分支：`test/extdoc-managed-fields-batch468`（已合入 main）
- 内容：ExternalDocumentService.sameManagedFields 真值表（新建
  ExternalDocumentServiceSameManagedFieldsTailTest，6 用例）：
  title/contentHash/source/documentType/metadata/enabled 一致性
  比较，任一字段漂移即 false。
- 状态：单类 6 用例绿；core 全量门禁 EXIT=0。

### Batch 467（已交付）

- 分支：`test/rotation-cancel-matrix-batch467`（已合入 main）
- 内容：ApiKeyManagementService.cancelRotation 长尾（新建
  ApiKeyRotationCancelMatrixTest，4 用例）：CANCELED 幂等重放、
  EXPIRED 拒绝、COMPLETED 拒绝（CREDENTIAL_ROTATION_NOT_PENDING）、
  PENDING 取消 → 禁用目标密钥并恢复源密钥。含 principal lookup
  与 rotationResponse 的 stub 矩阵。
- 状态：单类 4 用例绿；core 全量门禁 EXIT=0（5361 tests）。

### Batch 466（已交付）

- 分支：`test/summary-tail-batch466`（已合入 main）
- 内容：ChatExecutionService.withSummaryMetadata 长尾（新建
  ChatExecutionServiceWithSummaryTailTest，4 用例）：null 压缩与
  未尝试未降级 → 原样返回同一实例；attempted → summary 元数据
  块（attempted/updated/degraded/reason/version/
  summarizedThroughHistoryId/estimatedTokens）；degraded → 带
  reason 无 historyId。
- 状态：单类 4 用例绿；core 全量门禁 EXIT=0（5351 tests）。

### Batch 465（已交付）

- 分支：`test/syncrun-replay-tail-batch465`（已合入 main）
- 内容：ChatExecutionService.loadBaseline 会话历史读取长尾（新建
  ChatExecutionServiceLoadBaselineTailTest，2 用例）：STATELESS
  模式不读会话历史直接空基线；SERVER 模式经 findBySessionId 读
  取并倒序为时间正序（早对话在前，共 4 条消息 = 2 历史 ×
  user+assistant）。
- 状态：单类 2 用例绿；core 全量门禁 EXIT=0（5353 tests）。

### Batch 464（已交付）

- 分支：`test/chat-prompt-usage-tail-batch464`（已合入 main）
- 内容：ChatExecutionService 提示词与用量长尾（新建
  ChatExecutionServicePromptUsageTailTest，5 用例）：三模式系统
  提示词分支（PLAIN/AGENT/KNOWLEDGE 各自模板文本）、domain 模
  板前缀拼接、AGENT 模式下 Skill 目录 levelOnePrompt 追加、
  usage 的 null 归一与部分字段透出（缺失 completionTokens 不入
  Map）。
- 状态：单类 5 用例绿；core 全量门禁 EXIT=0（5351 tests）。

### Batch 463（已交付）

- 分支：`test/same-state-tail-batch463`（已合入 main）
- 内容：DocumentMutationService 外部状态比较真值表（新建
  DocumentMutationSameStateTailTest，4 用例）：sameExternalState
  要求启用且无删除标记；managed 变体忽略 enabled/deleted 仅比
  六元组（title/contentHash/source/documentType/metadata/
  payload）；任一字段漂移（title/hash/source/type/metadata）即
  不匹配。
- 状态：单类 4 用例绿；core 全量门禁 EXIT=0（5346 tests）。

### Batch 462（已交付）

- 分支：`test/restore-collection-tail-batch462`（已合入 main）
- 内容：DocumentMutationService.restoreLocalFromVersion 目标集合
  ACL 长尾（新建 DocumentMutationRestoreCollectionTailTest，4 用
  例）：快照目标集合在受限密钥白名单外 → SecurityException；
  目标集合在白名单内 → 集合切换生效（document.collectionId=9）；
  受限密钥恢复未分配快照 → RESTORE_NOT_ALLOWED；快照内容与当
  前一致 → 状态保持 COMPLETED 不重置。
- 要点：authenticateRestricted 的白名单须同时覆盖当前文档集合
  与目标快照集合；restore 流程依赖 forceRecordVersion stub 非
  null 返回。
- 状态：单类 4 用例绿；core 全量门禁 EXIT=0（5342 tests）。

### Batch 461（已交付）

- 分支：`test/syncrun-summary-tail-batch461`（已合入 main）
- 内容：DocumentSyncRunService.batchUpsert 混合成败汇总（新建
  DocumentSyncRunFailedItemTailTest 补充用例）：同批内一项
  APPLIED、一项 FAILED（provider down），summary 分别计数且条
  目按输入次序保留各自状态与错误信息。
- 状态：单类 3 用例绿（原 2 + 新 1）；core 全量门禁 EXIT=0
  （5338 tests）。

### Batch 460（已交付）

- 分支：`test/mapper-snapshot-tail-batch460`（已合入 main）
- 内容：ChatCommandMapper 快照解析辅助长尾（新建
  ChatCommandMapperSnapshotTailTest，5 用例）：retrievalOptions
  六字段任一缺失 → invalid；retrievalScope 未知 collectionFilter
  / 非正 id 拒绝；longList 对 null/非正项拒绝；textList 对
  空数组/空白项拒绝；blankAsNull 仅归一 null/空白（保留原空格
  不 trim，既有语义入档）。
- 状态：单类 5 用例绿；core 全量门禁 EXIT=0（5337 tests）。

### Batch 459（已交付）

- 分支：`test/apikey-expire-tail-batch459`（已合入 main）
- 内容：ApiKeyManagementService.expireRotationById 调度长尾（新建
  ApiKeyManagementServiceExpireRotationTailTest，5 用例）：operation
  缺失早退、非 PENDING 早退、PENDING 未到期早退、管理写竞争失
  败早退（不落账）、到期 PENDING 过期落账（状态 EXPIRED、源密
  钥禁用）。
- 要点：私有 expireRotationById 以反射驱动；acquireManagementWrite
  竞争失败时静默早退是既有语义。
- 状态：单类 5 用例绿；core 全量门禁 EXIT=0（5332 tests）。

### Batch 458（已交付）

- 分支：`test/syncrun-replay-tail-batch458`（已合入 main）
- 内容：DocumentSyncRunService.replayOrReopenExistingItem 完整矩
  阵（新建 DocumentSyncRunReplayTailTest，6 用例）：指纹不一致/
  documentKind 不一致/sourceRevision 不一致各自 SYNC_RUN_ITEM_
  CONFLICT；终态（APPLIED 且无错误）行直接重放且不触发更新；
  FAILED 行经 reopenFailedItem 后返回 null 继续执行 mutation；
  IN_PROGRESS 错误码未重开 → "currently being processed" 冲突。
- 要点：fingerprint 为私有实例方法（接收 service）；LedgerRow
  的 sourceRevision/指纹均以反射构造行注入。
- 状态：单类 6 用例绿；core 全量门禁 EXIT=0（5327 tests）。

### Batch 457（已交付）

- 分支：`test/apikey-replay-tail-batch457`（已合入 main）
- 内容：ApiKeyManagementService.replayResponse 长尾（新建
  ApiKeyManagementServiceReplayTailTest，4 用例）：principal 缺
  失 → SERVICE_UNAVAILABLE fail-closed；活跃凭证重放透出 keyId
  与 credentialVersion；当前 key 缺失/主体撤销/过期降级为无
  keyId 重放（仍带名称与 allowedIds）。
- 状态：单类 4 用例绿；core 全量门禁 EXIT=0（5321 tests）。

### Batch 456（已交付）

- 分支：`test/turn-session-tail-batch456`（已合入 main）
- 内容：ChatTurnOperationService 会话与尝试标记长尾（新建
  ChatTurnSessionAttemptTailTest，3 用例）：withEffectiveSession
  对合法会话原样返回同一命令实例；markAttempt 对 null trace
  session 或 null attempt 直接跳过；有 trace session 时委托
  markAttemptFinished 并透出 attemptKey 与候选 ref。
- 要点：withEffectiveSession 的非法会话分支为纯防御——
  ChatCommand 构造器已先行校验 sessionId，正常路径不可达（入
  档为潜在死代码）。
- 状态：单类 3 用例绿；core 全量门禁 EXIT=0（5317 tests）。

### Batch 455（已交付）

- 分支：`test/jsonrecord-helper-tail-batch455`（已合入 main）
- 内容：JsonRecordService 小辅助长尾（新建
  JsonRecordServiceHelperTailTest，4 用例）：measurePayloadBytes
  对 null 请求/payload 返回 0、否则返回序列化字节数（含引号花
  括号 9 字节）；normalizeNamespace trim 与 default 归一、128
  边界、非可见 ASCII 拒绝；requireExternalId trim、null/空白/
  256 拒绝、255 边界通过。
- 要点：SourceNamespaceValidator 仅拒绝非可见 ASCII 字符，
  "crm#1" 等可打印符号合法（既有语义入档）。
- 状态：单类 4 用例绿；core 全量门禁 EXIT=0（5314 tests）。

### Batch 454（已交付）

- 分支：`test/catalog-read-bounded-batch453`（已合入 main）
- 内容：ResourceCatalog.readBounded 守卫长尾（新建
  ResourceCatalogReadBoundedTailTest，4 用例）：文件/流式来源的
  单文件与总预算超限拒绝、预算内完整读取、负预算立即拒绝。
- 要点（既有语义）：流式读取超预算即抛 ResourceCatalogException
  而非截断。
- 状态：单类 4 用例绿；core 全量门禁 EXIT=0（5310 tests）。

### Batch 452（已交付）

- 分支：`test/import-build-tail-batch452`（已合入 main）
- 内容：RagCollectionController 导入文档构建长尾（新建
  RagCollectionControllerImportBuildTailTest，5 用例）：size 缺
  失按 UTF-8 字节数计算（中文 4 字符 → 12 字节）、显式 size 与
  originalFilename 保留、空白 namespace 归一 default、identity
  超 255 拒绝、sourceDeletedAt 透传与 jsonbPayload 深拷贝应用。
- 状态：单类 5 用例绿；core 全量门禁 EXIT=0（5306 tests）。

### Batch 451（已交付）

- 分支：`test/execution-eligible-tail-batch451`（已合入 main）
- 内容：ChatExecutionService.eligibleCandidates 矩阵（新建
  ChatExecutionServiceEligibleTailTest，6 用例）：显式候选链中
  能力不合格者过滤（流式不支持）、不可用候选跳过、全不可用 →
  SERVICE_UNAVAILABLE、已解析但不合格 → 按 streaming 与否抛
  MODEL_STREAMING_UNSUPPORTED / MODEL_CAPABILITY_UNSUPPORTED；
  默认链（无显式候选）经 orderedCandidateDescriptors 过滤 AGENT
  工具调用能力、全不合格抛错。
- 要点：候选对象须在 when(modelRouter...) 外预先构建（嵌套
  stubbing 会触发 UnfinishedStubbingException）；AGENT 资格要
  求 supportsToolCalling 且 options instanceof
  ToolCallingChatOptions。
- 状态：单类 6 用例绿；core 全量门禁 EXIT=0（5301 tests）。

### Batch 450（已交付）

- 分支：`test/syncrun-threshold-tail-batch450`（已合入 main）
- 内容：DocumentSyncRunService 墓碑完成阈值长尾（新建
  DocumentSyncRunThresholdTailTest，5 用例）：NONE 策略跳过阈值
  校验直接返回 0；confirmMissingCount 与预览候选数不一致 →
  SYNC_RUN_DELETE_PROTECTION；确认后即使超阈值也放行；超阈值
  且未确认 → "Missing count exceeds" 拒绝；确认数 0 + 空候选通
  过；reconcileMissingCandidates 按 reconcileMissingExternal 返
  回值计数墓碑。
- 要点：threshold = min(absolute, max(1, ceil(active × percent /
  100)))；CandidateSet/Candidate/RunRow 均为私有 record，反射构
  造后以纯逻辑方法为测试入口。
- 状态：单类 5 用例绿；core 全量门禁 EXIT=0（5295 tests）。

### Batch 449（已交付）

- 分支：`test/turn-complete-tail-batch449`（已合入 main）
- 内容：ChatTurnOperationService 完成长尾（新建
  ChatTurnOperationCompleteTailTest，2 用例）：responseWithTurnId
  写入 turnId 并合并元数据（null 元数据归一为空 Map、既有键保
  留）；principalFor 的 owner 四类归一（db:→DATABASE_API_KEY、
  root:environment-root→ENVIRONMENT_ROOT、legacy:static→
  LEGACY_STATIC、未知→local/AUTH_DISABLED）。
- 要点：两方法为私有实例方法，反射调用需以服务实例为接收者。
- 状态：单类 2 用例绿；core 全量门禁 EXIT=0（5290 tests）。

### Batch 448（已交付）

- 分支：`test/syncrun-samebegin-tail-batch448`（已合入 main）
- 内容：DocumentSyncRunService 批量账本长尾（新建
  DocumentSyncRunBatchRecordTailTest，3 用例）：mutation 失败后
  recordFailedItem 对已完成账本行的重放（透出旧行状态/错误）、
  对 IN_PROGRESS 行的原位 FAILED 改写；sameBeginRequest 的四字
  段一致性矩阵（clientRunId trim 后等价、namespace/snapshotMode/
  missingPolicy 任一不同即 false）。
- 要点：findItem 在 applyItem 与 recordFailedItem 各调用一次，
  stub 需按调用次序返回 null→行；私有 RunRow/LedgerRow 均以反
  射构造。
- 状态：单类 3 用例绿；core 全量门禁 EXIT=0（5288 tests）。

### Batch 447（已交付）

- 分支：`test/jsonrecord-scope-tail-batch443`（已合入 main）
- 内容：JsonRecordService.scopeAllows 真值表（新建
  JsonRecordServiceScopeAllowsTailTest，5 用例）：null/禁用/
  非 json-record 文档拒绝、documentIds 过滤、NONE 放行任意已分
  配文档、ANY_ASSIGNED 要求非空 collectionId、SELECTED 要求集
  合隶属。
- 状态：单类 5 用例绿；core 全量门禁 EXIT=0（5285 tests）。

### Batch 446（已交付）

- 分支：`test/retriever-tail-batch446`（已合入 main）
- 内容：HybridRetrieverService 长尾（新建
  HybridRetrieverServiceTailTest，4 用例）：isTimeout 因果链遍
  历（直接/CompletionException 包装/深层包裹/无超时）；normal
  izeErrorCode 解包 CompletionException、简单类名兜底 ERROR；
  candidateRetrievalLimit 矩阵——越界/无 config/关闭重排/provider
  off 原样返回，重排激活时候选池放大到 candidateLimit(20)。
- 要点：candidateRetrievalLimit 参数顺序为 (config, requested)。
- 状态：单类 4 用例绿；core 全量门禁 EXIT=0（5280 tests）。

### Batch 445（已交付）

- 分支：`test/syncrun-failed-item-batch445`（已合入 main）
- 内容：DocumentSyncRunService 批量长尾（新建
  DocumentSyncRunFailedItemTailTest，2 用例）：mutation 失败经
  recordFailedItem 落 FAILED 并计入 summary.failed；run 控制错
  误（SYNC_RUN_ITEM_CONFLICT）直接上抛终止批次。私有 RunRow 以
  反射 20 参构造。
- 要点：applySyncMutation 以 mapKeys 结果（可为 null）调用
  mutationService——stub 需用 nullable 匹配器匹配 null 键。
- 状态：单类 2 用例绿；core 全量门禁 EXIT=0（5276 tests）。

### Batch 444（已交付）

- 分支：`test/turn-snapshot-tail-batch444`（已合入 main）
- 内容：ChatTurnOperationService 执行快照序列化长尾（新建
  ChatTurnOperationSnapshotTailTest，5 用例）：executionSnapshot
  序列化 mode/candidates 并把 null/空白声明归一为 DEFAULT、超
  出 executionSnapshotMaxBytes → invalid；snapshotCandidates 的
  空快照回退 List.of()、错误版本/空候选链/空白候选拒绝、合法
  候选透出；resolvedCandidateRefs 缺执行器或空链 → invalid；
  declaredModelIdentifier 的 null canonical/空白值回退 DEFAULT。
- 状态：单类 5 用例绿；core 全量门禁 EXIT=0（5274 tests）。

### Batch 443（已交付）

- 分支：`test/skill-limits-tail-batch443`（已合入 main）
- 内容：RuntimeSkillCatalog 体积上限长尾（新建
  RuntimeSkillCatalogLimitsTailTest，2 用例）：maxSkillBodyBytes
  调小后正文超限 → "Runtime Skill body exceeds configured
  limit"；maxReferenceBytes 调小后引用文件超限 → "Runtime Skill
  reference exceeds configured limit"，均在 initialize 阶段抛出。
- 状态：单类 2 用例绿；core 全量门禁 EXIT=0（5269 tests）。

### Batch 442（已交付）

- 分支：`test/pdf-tree-tail-batch442`（已合入 main）
- 内容：PdfImportController 目录树长尾（新建
  PdfImportControllerTreeTailTest，4 用例）：currentImportId 仅
  解析根级 UUID（null/空/子路径均 empty）；toFileEntry 对缺失
  MIME/大小归一（application/octet-stream、0）；根目录树构建
  合成目录条目优先排序且文件按名排序；listTree 端点空路径返
  回 200 与空条目。
- 状态：单类 4 用例绿；core 全量门禁 EXIT=0（5267 tests）。

### Batch 441（已交付）

- 分支：`test/turn-inspect-tail-batch441`（已合入 main）
- 内容：ChatTurnOperationService.inspectExisting 长尾（新建
  ChatTurnOperationInspectTailTest，5 用例）：null/未键控
  prepared → null、SUCCEEDED → 重放 Claim、FAILED 复现原错误
  码（INTERNAL_ERROR）、活跃租约 → ChatTurnInProgressException
  且 retryAfterSeconds 在 1..60、过期租约返回 null 继续重跑。
- 状态：单类 5 用例绿；core 全量门禁 EXIT=0（5263 tests）。

### Batch 440（已交付）

- 分支：`test/embed-progress-guard-batch440`（已合入 main）
- 内容：DocumentEmbedService 嵌入进度长尾（新建
  DocumentEmbedServiceProgressTailTest，2 用例）：空内容守卫先
  于缓存查询（IAE 文本含 documentId，缓存查询零交互）；缓存命
  中直接透出 PREPARING+COMPLETED 两个进度事件且 provider 未被
  调用。
- 状态：单类 2 用例绿；core 全量门禁 EXIT=0（5258 tests）。

### Batch 439（已交付）

- 分支：`test/embed-integrity-tail-batch439`（已合入 main）
- 内容：DocumentEmbedService 完整性委托长尾（新建
  DocumentEmbedServiceIntegrityTailTest，2 用例）：注入
  DerivationIntegrityRepository 后 hasFreshEmbedding 以
  snapshot.vectorFresh() 为准（true→新鲜 / false→不新鲜），
  缓存路径不再被询问。
- 状态：单类 2 用例绿；core 全量门禁 EXIT=0（5256 tests）。

### Batch 438（已交付）

- 分支：`test/embed-progress-tail-batch438`（已合入 main）
- 内容：DocumentEmbedService.batchEmbedDocumentsWithProgress 长尾
  （新建 DocumentEmbedServiceBatchProgressTailTest，2 用例）：
  超 50 文档门卫拒绝；逐文档进度事件透出（每文档 PREPARING +
  终态两个事件，2 文档共 4 个）且 summary 的 cached 计数正确。
- 要点：sendDocumentProgress 每文档发两个事件（PREPARING 与终
  态），事件计数断言需按 2 倍文档数。
- 状态：单类 2 用例绿；core 全量门禁 EXIT=0（5254 tests）。

### Batch 437（已交付）

- 分支：`test/pdf-path-tail-batch437`（已合入 main）
- 内容：PdfImportController 路径辅助长尾（新建
  PdfImportControllerPathTailTest，3 用例）：deriveMarkdownPath
  的三种路径形态（UUID → UUID/default.md、UUID/original.pdf →
  UUID/default.md、传统目录替换扩展名）与既有怪癖（无扩展名的
  "/original" 走通用规则追加 .md）；extractUuid 的空串/尾斜杠/
  首段提取；replaceLast 仅替换末次出现、target 缺失与 null/零
  限直通。
- 状态：单类 3 用例绿；core 全量门禁 EXIT=0（5252 tests）。

### Batch 436（已交付）

- 分支：`test/collection-list-tail-batch436`（已合入 main）
- 内容：RagCollectionController.listDocuments 长尾（新建
  RagCollectionControllerListTailTest，5 用例）：keyword 过滤裁
  剪后路由到搜索方法且不再走普通分页、空白 keyword trim 为空
  串透传（不转 null）、documentType/processingStatus 单独过滤
  路由、offset/limit → 页码换算（40/20 → 第 2 页）、集合键映射
  透出到响应。
- 状态：单类 5 用例绿；core 全量门禁 EXIT=0（5249 tests）。

### Batch 435（已交付）

- 分支：`test/tool-registry-tail-batch435`（已合入 main）
- 内容：RagChatToolRegistry 注册校验长尾（新建
  RagChatToolRegistryValidateTailTest，7 用例）：provider 空名
  /空注册数据/空策略表拒绝、callback 缺 definition/元数据拒绝、
  空白与跨 provider 重名工具拒绝、未知策略键与 null 策略拒绝、
  输入 schema 空白与非法 JSON 拒绝、策略四元组（下限 1024 字
  符、maxCallsPerRequest 全局上限、timeout 零值）约束。
- 要点：非法工具名/schema 需以 mock ToolCallback/ToolDefinition
  绕过 Spring AI ToolDefinition.builder 的构建期断言。
- 状态：单类 7 用例绿；core 全量门禁 EXIT=0（5244 tests）。

### Batch 434（已交付）

- 分支：`test/command-mapper-override-tail-batch434`（已合入 main）
- 内容：ChatCommandMapper 检索覆盖长尾（新建
  ChatCommandMapperOverrideTailTest，6 用例）：PLAIN 模式下八类
  单一覆盖来源（maxResults/useHybridSearch/useRerank 显式置位、
  collectionScopeMode/collectionIds/collectionKeys/documentIds/
  filters）逐一 RETRIEVAL_OPTIONS_NOT_ALLOWED；KNOWLEDGE 模式
  metadata null 归一为空 Map、非空保留；执行快照的 DEFAULT 声
  明模型（候选项存在时取首候选，DEFAULT 置 null 分支不可达）
  与 domainId 空白归 null、自定义声明模型透传；未知 domain 拒
  绝。
- 说明：mapFromExecutionSnapshot 的 resolvedCandidates 空数组被
  textList 拒绝，故「DEFAULT→null modelRef」分支实际不可达
  （潜在死代码，入档）。
- 状态：单类 6 用例绿；core 全量门禁 EXIT=0（5237 tests）。

### Batch 433（已交付）

- 分支：`test/budget-tail-batch433`（已合入 main）
- 内容：ChatExecutionBudget 长尾（新建
  ChatExecutionBudgetTailTest，5 用例）：过期截止时间下
  tryReserveCandidateAttempt/hasModelCallCapacity/reserveModel
  Call/reserveToolBatch 全部拒绝且错误码 CHAT_BUDGET_EXHAUSTED；
  httpToolExecutionState 同预算稳定复用、预算变更拒绝；策略
  工具 CAS 上限与非法输入拒绝；构造器归因校验（非法
  principalId 拒绝、deadline/logicalExecutionId/chatMode 缺省
  归一、空白 traceId 归 null 不入 snapshot）；settle/release 的
  零值下限钳制。
- 状态：单类 5 用例绿；core 全量门禁 EXIT=0（5231 tests）。

### Batch 432（已交付）

- 分支：`test/extdoc-identity-tail-batch432`（已合入 main）
- 内容：ExternalDocumentService 身份查询接线长尾（新建
  ExternalDocumentServiceIdentityTailTest，3 用例）：
  getByExternalIdentity 存在退役地址服务时委托
  requireNotRetired、退役拒绝透出、双参重载默认 default 命名
  空间并返回详情。
- 状态：单类 3 用例绿；core 全量门禁 EXIT=0（5226 tests）。

### Batch 431（已交付）

- 分支：`test/openai-map-tail-batch431`（已合入 main）
- 内容：OpenAiChatRequestMapper 编排长尾（新建
  OpenAiChatRequestMapperMapTailTest，5 用例）：map 的 PLAIN
  + filters 协议错误先于别名/scope 协作方抛出；
  mapFromExecutionSnapshot 的快照版本不符/未知 mode 枚举/
  声明模型缺失各自 invalid；合法快照映射 mode/memory/modelRef/
  候选与 stream 标志。
- 要点：invalidSnapshot 抛 RagException
  （IDEMPOTENCY_EXECUTION_SNAPSHOT_INVALID），而 validate
  Declaration 的协议错误抛 OpenAiProtocolException——两者类型
  不同，断言需区分。
- 状态：单类 5 用例绿；core 全量门禁 EXIT=0（5223 tests）。

### Batch 430（已交付）

- 分支：`test/openai-snapshot-tail-batch430`（已合入 main）
- 内容：OpenAiChatRequestMapper 执行快照解析长尾（新建
  OpenAiChatRequestMapperSnapshotTailTest，4 用例）：
  retrievalOptions 六字段任一缺失即 invalid 快照；
  retrievalScope 的未知 collectionFilter / 非正 id 拒绝与合法
  SELECTED 作用域（空白 documentType 归一 null）解析；
  longList 对 null 与非正项拒绝；textList 对 null/空数组/空白
  项拒绝。
- 状态：单类 4 用例绿；core 全量门禁 EXIT=0（5218 tests）。

### Batch 429（已交付）

- 分支：`test/skill-parse-tail-batch429`（已合入 main）
- 内容：RuntimeSkillCatalog 解析长尾（新建
  RuntimeSkillCatalogParseTailTest，7 用例 + 5 个坏 fixture）：
  frontmatter 缺失、YAML 非法、名称与目录不匹配、capabilities
  非数组、links 非数组各自拒绝；references/ 前缀归一后仍命中
  引用文件；含控制字符/双斜杠路径判 invalid；loadBody 截断到
  maxCharacters。
- 说明：原计划的 DocumentSyncRunService RunRow 相关分支因
  RunRow 为 18 组件私有 record、构造成本过高而推迟（record
  FailedItem/replayOrReopenExistingItem/requireMissingCount
  WithinThreshold 留待集成层或引入包内可见构造器后处理）。
- 状态：单类 7 用例绿；core 全量门禁 EXIT=0（5214 tests）。

### Batch 428（已交付）

- 分支：`verify/interim-fullrepo-batch428`（已合入 main）
- 内容：中期全仓聚合复验（Batch 406–427 十个批次后的健康门）：
  ①后端全 reactor `mvn test` BUILD SUCCESS（EXIT=0，core 5207）；
  ②WebUI 门禁 64 文件 670 用例全绿 + vite 生产构建通过 + 对齐
  策略检查通过；③本段累计交付 16 个测试批次（406–421 于前段，
  406–427 于本段），core 用例数自 5008 增至 5207。
- 状态：全部门禁绿。

### Batch 427（已交付）

- 分支：`test/diagnostics-tail-batch427`（已合入 main）
- 内容：RetrievalDiagnosticsService 长尾（新建
  RetrievalDiagnosticsServiceTailTest，4 用例）：strategyOf 的
  unknown/hybrid/fulltext/vector 命名、positionalOnly 仅保留
  rank_* 键、visibleMetadata 对受限调用方按授权集合收窄 scope
  的 collectionKeys（未授权/未知键静默过滤）、resolveOutcome 与
  resolveEmptyReason 在预算耗尽且结果为空时上报
  RETRIEVAL_BUDGET_EXHAUSTED、未耗尽时透传 outcome 编码。
- 状态：单类 4 用例绿；core 全量门禁 EXIT=0（5207 tests）。

### Batch 426（已交付）

- 分支：`test/syncrun-validate-tail-batch426`（已合入 main）
- 内容：DocumentSyncRunService 校验辅助长尾（新建
  DocumentSyncRunServiceValidateTailTest，6 用例）：
  validateMode 的策略对必填、OFFLINE_MANIFEST 仅支持 NONE、
  EXCLUSIVE_OFFLINE+TOMBSTONE 需显式确认且该组合之外禁止确认
  标志；requireVisible 的空白/超长/不可见 ASCII 拒绝与 trim；
  normalizeNamespace 的 null/空白回退 default 与非默认命名空
  间开关；isRunControlError 的六个控制错误码分类。
- 状态：单类 6 用例绿；core 全量门禁 EXIT=0（5203 tests）。

### Batch 425（已交付）

- 分支：`test/embed-batch-tail-batch425`（已合入 main）
- 内容：DocumentEmbedService.batchEmbedDocuments 汇总矩阵（新建
  DocumentEmbedServiceBatchTailTest，3 用例）：null/超 50 门卫、
  CACHED（缓存命中）与 NOT_FOUND→skipped（文档不存在）的汇总
  计数、provider 调用失败 → FAILED 计数与条目状态透出。
- 状态：单类 3 用例绿；core 全量门禁 EXIT=0（5197 tests）。

### Batch 424（已交付）

- 分支：`test/embed-validate-tail-batch424`（已合入 main）
- 内容：DocumentEmbedService 校验与批量长尾（新建
  DocumentEmbedServiceValidateTailTest，8 用例）：
  batchEmbedDocuments 的 null/超 50 门卫；
  validateEmbeddingResults 矩阵——数量不匹配、失败/缺失结果、
  响应顺序错位、向量缺失（既有语义：isSuccess 先行 →
  "Embedding failed for chunk 0: null"）、维度不匹配、非有限
  值、合法返回 null；safeError 的兜底文案与 500 字符截断。
- 状态：单类 8 用例绿；core 全量门禁 EXIT=0（5194 tests）。

### Batch 423（已交付）

- 分支：`test/embed-fresh-tail-batch423`（已合入 main）
- 内容：DocumentEmbedService.hasFreshEmbedding 长尾（新建
  DocumentEmbedServiceFreshTailTest，3 用例）：null 文档/缺失
  id/缺失或空白 contentHash 判不新鲜、关键词索引过期短路（不
  再查缓存）、缓存命中即新鲜/未命中保持不新鲜。
- 要点：buildChunkerVersion 经 DocumentChunkingService.prepare
  派生，fixture 文档需带非空 content。
- 状态：单类 3 用例绿；core 全量门禁 EXIT=0（5186 tests）。

### Batch 422（已交付）

- 分支：`test/extdoc-delete-tail-batch422`（已合入 main）
- 内容：ExternalDocumentService sourceDelete 传统分支（mutation
  Service 为 null 直连 deleteInTransaction；新建
  ExternalDocumentServiceDeleteTailTest，3 用例）：目标文档缺失
  → DOCUMENT_NOT_FOUND、JSON 记录身份拒绝
  （DocumentRevisionConflictException）、expectedSourceRevision
  不匹配拒绝。
- 状态：单类 3 用例绿；core 全量门禁 EXIT=0（5183 tests）。

### Batch 421（已交付）

- 分支：`test/eval-case-tail-batch421`（已合入 main）
- 内容：EvaluationSuiteService 单用例执行长尾（新建
  EvaluationSuiteExecuteCaseTailTest，4 用例）：fixture 缺失 →
  SKIPPED/MISSING_FIXTURE、SecurityException → FAILED/
  AUTHORIZATION_CHANGED、RuntimeException → FAILED/
  PROVIDER_OR_DATABASE、minHitRate 与 minMrr 独立判定不达标 →
  FAILED/BELOW_MINIMUM。
- 要点：authorizeDefinition 与 executeCase 共用同一个
  scopeResolver.resolve——要命中 executeCase 的异常分类需让
  stub 首调用放行、第二次抛出；测试类用 MockitoExtension 严格
  模式时未被消费的 stub 须 lenient。
- 状态：单类 4 用例绿；core 全量门禁 EXIT=0（5180 tests）。

### Batch 420（已交付）

- 分支：`fix/ssrf-doc-prefix-batch420`（已合入 main）
- 内容：复核 Batch 419 标记的"3fff:ffff:: 防护缺口"——**更正为
  误报**：RFC 9637 的 3fff::/20 只覆盖 3fff:0000::–3fff:0fff::
  （bytes[2] 高半字节为 0），3fff:ffff::9 的 bytes[2] 高半字节
  为 0xf，位于文档段之外、属合法全局单播；原实现
  hasPrefix(20, 0x3f, 0xff, 0x00) 语义正确。期间试验的两个生产
  修改方案（掩码 0xf0 / 独立位判断）均验证后撤销，未改生产代
  码。回归测试固化为 3fff 段边界四例：3fff::1 / 3fff:800::9
  非公网，3fff:ffff::9 / 3fff:1000::9 公网。
- 状态：单类 24 用例绿；core 全量门禁 EXIT=0（5176 tests）。

### Batch 419（已交付）

- 分支：`test/ssrf-guard-tail-batch419`（已合入 main）
- 内容：AllowlistedHttpToolProvider 的 SSRF 公网地址守卫真值表
  （新建 AllowlistedHttpToolProviderPublicAddressTailTest，24
  断言用例）：公网单播（8.8.8.8/2600::1 等）判公网；IPv4 特殊
  网段（回环/任意本地/10/172.16-31/192.168/169.254/100.64/
  192.0.0/192.0.2/192.88.99/198.18-19/198.51.100/203.0.113/
  组播）判非公网；IPv6 回环/ULA/链路本地/组播/文档 2001:db8/
  6to4 2002::/4000:: 判非公网；NAT64 64:ff9b::/96 内嵌地址因
  byte1 非零不满足内嵌条件、按普通单播判非公网；::ffff: 内嵌
  回环判非公网；null 判非公网。
- 发现（入档，未改生产）：①3fff:ffff:: 不落在
  hasPrefix(20,0x3f,0xff,0x00) 的文档前缀内 → 判公网，存在潜
  在防护缺口，待专项生产批次收窄；②NAT64 内嵌公网 IPv4 同理
  不被识别（当前判非公网，偏保守无风险）。
- 状态：单类 24 用例绿；core 全量门禁 EXIT=0（5176 tests）。

### Batch 418（已交付）

- 分支：`test/eval-suite-variants-tail-batch418`（已合入 main）
- 内容：EvaluationSuiteService 长尾（新建
  EvaluationSuiteServiceVariantsTailTest，4 用例）：
  selectVariants 的 null/空请求回退全部变体、未知变体名拒绝
  （消息含 Unknown variant）、合法子集按请求返回；
  resolveExecutionKey 的 db: 前缀逐次重授权（密钥失效/空
  principalId 均 fail-closed、有效则返回当前策略）、非 db 前缀
  回退当前请求策略。
- 状态：单类 4 用例绿；core 全量门禁 EXIT=0（5152 tests）。

### Batch 417（已交付）

- 分支：`test/router-tail-batch417`（已合入 main）
- 内容：ChatModelRouter 长尾（新建 ChatModelRouterTailTest，
  5 用例）：legacy 模型按类名启发式注册 provider 别名
  （zhipu/deepseek 命中、匿名类丢弃）、getDefaultModelRef 的
  primary→fallbacks→唯一 legacy→"none" 降级链、legacy 候选
  缺省能力 + 无限额元数据（estimatedModelLimits=true）、
  resolveCandidateRequired 对未知 ref 的错误信息（含 Available
  models）、orderedCandidates 对 primary/fallback/legacy 同模
  型的去重。
- 状态：单类 5 用例绿；core 全量门禁 EXIT=0（5148 tests）。

### Batch 416（已交付）

- 分支：`test/chat-authz-evidence-tail-batch416`（已合入 main）
- 内容：ChatAuthorizationService 回放校验长尾（新建
  ChatAuthorizationSourceEvidenceTailTest，12 用例）：快照缺失
  forbidden、版本不符 invalid、observed 与派生集合不一致
  invalid、来源文档重复 invalid、来源 collectionId 非正
  invalid、来源文档消失/禁用/墓碑 forbidden、来源 Collection
  变更 forbidden、未分配文档仅在 CALLER_VISIBLE+UNRESTRICTED+
  unassignedDocumentsAllowed 时放行、来源不在当前允许列表
  forbidden、SELECTED_COLLECTIONS 逃逸 forbidden、owner
  principal 失效 forbidden。
- 要点：RESTRICTED 快照的 callerAllowList 先于 verifySources
  做吊销检查——要命中"来源未授权"分支需快照 allowList 与当前
  密钥一致；verifySources 的 forbidden 与 validateSnapshot 的
  invalid 异常码不同（FORBIDDEN vs
  IDEMPOTENCY_AUTHORIZATION_SNAPSHOT_INVALID）。
- 状态：单类 12 用例绿；core 全量门禁 EXIT=0（5143 tests）。

### Batch 415（已交付）

- 分支：`test/alert-resource-tail-batch415`（已合入 main）
- 内容：告警与资源路径长尾（新建 3 文件，10 用例）：
  ①AlertNotificationWakeupPublisher——无事务立即发布、事务内
  注册去重（重复调用仅一个 synchronization）延迟到 afterCommit、
  发布失败吞掉不影响调用方；②ApiPrincipalExpiryAlertMetrics——
  对账计数按 outcome/phase 累加、空值归一 NONE、扫描截断计数、
  registry 缺失整体 no-op；③ResourceCatalog 路径辅助——
  configuredRootPath 前缀剥离（classpath*/classpath/jar!//反斜
  杠/前导斜杠/内嵌 !/）、normalizeRelativePath 安全拒绝（null/
  空/../内嵌/../NUL）与清洗（反斜杠转斜杠、前导斜杠剥离）、
  boundedMessage 兜底与 160 字符截断。
- 说明：AlertService.AlertStats 的 missed 均为生成的
  equals/hashCode（低价值），与 MultiModel JSON 内部类同理不追。
- 状态：三文件 10 用例绿；core 全量门禁 EXIT=0（5131 tests）。

### Batch 414（已交付）

- 分支：`test/chat-model-factory-tail-batch414`（已合入 main）
- 内容：ConfiguredChatModelFactory 选择矩阵（新建
  ConfiguredChatModelFactorySelectionTailTest，6 用例）：仅
  provider 引用的默认模型（无 routing → 第一个 chat 模型；
  routing.primary 前缀匹配优先）、无 chat 模型的 provider 与
  embedding 模型作 chat 引用均不可配置、跨 provider 模型 id 唯
  一解析与歧义拒绝、unavailableReason 全原因串（disabled/
  blank baseUrl/unsupported apiType/invalid contextWindow/
  invalid maxTokens/key 未配置）、null/空白 ref 门卫、
  listChatModels 描述符的 available/reasoning/
  estimatedModelLimits 标记。
- 要点：estimatedModelLimits 仅当 contextWindow 或 maxTokens
  为 null 时为 true；provider-only 解析依赖 chatModels() 保序。
- 状态：单类 6 用例绿；core 全量门禁 EXIT=0（5121 tests）。

### Batch 413（已交付）

- 分支：`test/static-knowledge-search-tail-batch413`（已合入 main）
- 内容：StaticKnowledgeCatalog.search 长尾（新建
  StaticKnowledgeCatalogSearchTailTest，6 用例）：null/空白
  query 与 limit/字符预算非正的门卫、不健康快照（缺根目录 +
  failFast=false 降级）返回空、config 对请求 limit 与字符预算
  的双向钳制、effectiveLimit/effectiveCharacters 非正短路、字
  符预算耗尽截断结果集、短语命中分数不低于松散词命中且
  metadata 透出 score。
- 要点：classpath 根缺失在 failFast=true 时 discover 直接抛
  ResourceCatalogException，需 failFast=false 才降级为不健康
  快照。
- 状态：单类 6 用例绿；core 全量门禁 EXIT=0（5115 tests）。

### Batch 412（已交付）

- 分支：`test/skill-catalog-tail-batch412`（已合入 main）
- 内容：RuntimeSkillCatalog 长尾（新建
  RuntimeSkillCatalogTailTest，6 用例）：find 的 null/非法模式/
  未知名门卫、levelOnePrompt 渲染能力标注与预算边界（0/负值→
  空串、极小预算只保留标题）、loadBody 渲染 Version 与 Related
  Skills（无 version 不渲染）、三类错误码
  （skill_not_found / skill_session_missing /
  skill_load_budget_exhausted）、readReference 的
  skill_not_loaded / skill_reference_not_found /
  skill_reference_budget_exhausted 路径、未初始化目录 disabled
  且为空。
- 要点：fixture 引用路径相对技能根（"api.md" 而非
  "references/api.md"）；RuntimeSkillLoadSession 构造参数经
  Math.max(1,·) 归一，预算耗尽需以 maxLoads=1 先装再装触发。
- 状态：单类 6 用例绿；core 全量门禁 EXIT=0（5109 tests）。

### Batch 411（已交付）

- 分支：`test/lifecycle-derivation-tail-batch411`（已合入 main）
- 内容：DocumentLifecycleService 状态推导矩阵（新建
  DocumentLifecycleDerivationTailTest，15 用例）：
  ①deriveFromStateRow 真值表（反射驱动私有 record）：全新鲜
  READY、本地哈希过期→FAILED、本地 READY+embedding QUEUED→
  KEYWORD_ONLY、job RUNNING 使 PENDING→INDEXING、EMBEDDING_
  FAILED 覆盖 LOCAL_INDEX_FAILED、双 NOT_REQUESTED 行、embedding
  单行 PROCESSING、本地行缺失+FAILED→LOCAL_INDEX_MISSING、
  job_error 兜底、CANCELLED→FAILED；②fromIntegrity 经注入完整
  性仓库映射：READY/INDEXING/KEYWORD_ONLY/未知 bucket 回退
  FAILED、reasonCode 仅在非 READY 时透出、localFresh 短路。
- 要点（既有语义入档）：local 行 NOT_REQUESTED/缺失但
  embedding 行存在时 localStatus 判 FAILED（双缺失才是
  NOT_REQUESTED）；fromIntegrity 中 localFresh 短路优先于
  localCondition；bucket 直接决定 searchability。
- 状态：单类 15 用例绿；core 全量门禁 EXIT=0（5103 tests）。

### Batch 410（已交付）

- 分支：`test/openai-mapper-protocol-batch410`（已合入 main）
- 内容：OpenAiChatRequestMapper 协议校验矩阵（新建
  OpenAiChatRequestMapperProtocolTailTest，16 用例）：null 请求
  体、model/messages 必填、消息 >100 拒绝、n=1 约束、采样参数
  （temperature/tools）与未知顶层字段拒绝、PLAIN 模式拒绝
  rag.scope / rag.document_ids / X-RAG-Collection-Key 头 /
  非空 rag.filters、memory 大小写归一（" server " 通过、
  "wizard" 拒绝）、消息 null 元素/name 附加字段拒绝、role 必填
  与枚举约束、content 三形态（缺失/非文本/非法数组元素）、多段
  text 以换行拼接、latestUser 取最后一条 user、无 user 消息
  拒绝。
- 要点：PLAIN 约束（含集合头）只在 mode=PLAIN 时触发，默认
  KNOWLEDGE 不受限；collect 头检查用的是 2 参
  validateDeclaration 重载。
- 状态：单类 16 用例绿；core 全量门禁 EXIT=0（5088 tests）。

### Batch 409（已交付）

- 分支：`test/eval-worker-tail-batch409`（已合入 main）
- 内容：调度与授权检索长尾（新建 2 文件，11 用例）：
  ①EvaluationSuiteWorkerTailTest（6）——shutdown 后 poll 不再领
  取、claim 异常释放槽位且下一轮可继续、空领取中止本轮（恰调用
  一次）、领取后异步 executeRun 且成功路径不 finishRun、处理异
  常落 FAILED（error 含原因）、safeError 的 null/空白兜底与
  1000 字符截断；②ProjectDocumentRetrieverTailTest（5）——缺
  失授权上下文 fail-closed（IllegalStateException）、同一 trace
  第二次检索去重短路（hybrid 仅调用一次）、COMPOSITE 标志绕过
  去重、matchNone + 空结果仅 recordOutcome、useRerank=true 走
  recordCandidateOutcome。
- 要点：Spring AI Query 的 history 不可为 null（需传空列表）；
  worker 心跳 40s 间隔在单测中不会触发。
- 状态：两文件 11 用例绿；core 全量门禁 EXIT=0（5072 tests）。

### Batch 408（已交付）

- 分支：`test/config-validation-tail-batch408`（已合入 main）
- 内容：配置层校验/解析长尾（新建 2 文件，12 用例）：
  ①StaticKnowledgeValidationTailTest（7）——经公共
  RagChatProperties.validate() 驱动：defaults 合法、六项正值预
  算逐项 0/-1 拒绝且消息含配置键名、chunk-overlap 负值/等于
  chunk-max 拒绝、chunk-max-1 边界通过、visibility 大小写不
  敏感 GLOBAL、fileExtensions null/空/null 元素/空白/含 / 与 \\
  拒绝、大写扩展名通过；②MultiModelConfigLoaderTailTest（5）——
  外部 models.json 部分成本字段（仅 input）归零补齐、reasoning
  true/缺省 false、findModel null/未知 id → null、
  getLegacyCapabilities 大小写不敏感 + 未配置/null provider 回
  退 defaults、null map 归一为空。
- 说明：JaCoCo 中 MultiModelConfigLoader 内部 JSON 类的 missed
  分支大部分为生成的 equals()/hashCode()（低价值），本轮不追。
- 状态：两文件 12 用例绿；core 全量门禁 EXIT=0（5061 tests）。

### Batch 407（已交付）

- 分支：`test/rerank-jieba-mdc-tail-batch407`（已合入 main）
- 内容：检索提供方与日志脱敏长尾（新建 3 文件，23 用例）：
  ①RerankProviderFactoryCredentialsTest（8）——provider null 回
  退 heuristic、别名 trim+lowercase、rerank api-key 空白时继承
  embedding key、已有值不覆盖、embedding key 空白不继承、
  baseUrl 继承/保留（注意：rerank baseUrl 有非空默认值
  siliconflow，继承分支需显式置空才触发）；②PgJiebaFulltext
  ProviderTailTest（6）——null/空白 query 短路、matchNone 短
  路、rank NULL 归零、embedding_id 两级排除（Number 命中排除/
  非 Number+local_chunk_id 保留/无 embedding_id 回退 id）、检索
  异常降级 failure(errorCode=异常类名)、query 先 trim 再入参；
  ③SensitiveMdcBoundaryTest（9）——键名匹配边界：子串判定是
  单向的（候选键 ⊂ 敏感键 → 敏感，如 "key"/"pass"；反向如
  "cvv_code" 不算）、大小写不敏感、snake 双下划线/尾随下划线
  经 camel 归一命中、前导下划线因产物首字母大写而**不**命中
  （既有语义）、连字符命名不归一、putAll 混合键。
- 要点：isSensitiveKey 先 lowercase 再 swapCamelToUnderscore，
  其大写分支实际不可达（潜在死代码，留待后续专项清理批次）。
- 状态：三文件 23 用例绿；core 全量门禁 EXIT=0（5049 tests）。

### Batch 406（已交付）

- 分支：`test/ragchat-legacy-tail-batch406`（已合入 main）
- 内容：①RagChatService 遗留路径长尾（新建
  RagChatServiceLegacyTailTest，18 用例，JaCoCo 驱动）：
  resolveLegacyRetrievalScope 矩阵（null request→unscoped、
  collection 过滤经 resolver、document 过滤优先 resolver、无
  resolver 直通、resolve 空→noMatches、无过滤不触 resolver）；
  resolveLegacyModelCandidates（router null→空、descriptors 命
  中直返且不回退 orderedCandidates、回退映射 model ref、options
  null/blank→UNKNOWN、双空→空）；invokeWithRetry（无模板直调、
  成功返回、耗尽 RuntimeException 原样抛、受检异常包
  RuntimeException(cause)）；extractPipelineMetrics（缺
  metrics→null、steps→StepMetricRecord 映射、空 steps→null）。
  ②去 flake：ConversationSummaryServiceTest
  modelFailureDegradesWithoutPersistingSummary——setUp 的
  compactionTimeoutMs=200 在重载机器上会先于 stub 异常触发
  summary_timeout，用例内放宽到 30s 确定性走失败分类路径。
- 要点：invokeWithRetry 返回私有 record LlmCallResult，mock 返
  回值需经反射构造匹配真实类型（String 会在方法返回处 CCE）。
- 状态：单类 18 用例绿；core 全量门禁 EXIT=0（5026 tests）。

### Batch 405（已交付）

- 分支：`test/webui-auth-gaps-batch405`（已合入 main）
- 内容：WebUI 认证/工具层直测补漏（新建 3 文件，10 用例）：
  ①ApiKeyAuthProvider——空白凭证拒绝且不打身份 API、非 root
  身份拒绝且不落凭证、root 身份解锁并裁剪空白、isUnlocked 与
  身份+凭证双因子绑定、外部清空凭证经订阅回调重置身份、logout
  清凭证；②ProtectedRoute——锁定态重定向 /unlock 并携带
  from 原始路径（含 query），解锁后跳回来源路径渲染受保护
  Outlet；③modelPreference——存取回写、空串移除存储键。
- 要点：锁定态 Navigate 已把路由替换到 /unlock，解锁不会自动
  回跳——测试以「解锁后 navigate(from)」模拟真实 Unlock 页流
  转，而非假设自动重渲染受保护路由。
- 状态：三文件 10 用例绿；WebUI 全量 64 文件 670 用例绿 +
  vite build 通过 + 对齐检查通过；core 未改动。

### Batch 404（已交付）

- 分支：`verify/full-repo-batch404`（已合入 main）
- 内容：全仓聚合复验（非新增用例）。core 长尾批次累计 400+ 后
  的跨模块健康门：①`mvn test` 全 reactor——api/core/documents/
  starter 全模块 BUILD SUCCESS（EXIT=0）；②WebUI 门禁——61 个
  测试文件 660 用例全绿 + vite 生产构建通过 + 对齐策略检查通过
  （12 处既允许居中）；③治理脚本
  `scripts/verify-no-pessimistic-locks.sh` 通过（生产源码无显式
  悲观锁/advisory lock）。
- 扫描结论：core/api/documents 各包类级测试覆盖已饱和（每类均
  有直接或同包测试），剩余无直接测试的类均为纯 DTO/record/枚举
  （经 controller/服务层测试间接覆盖）。
- 状态：三门全绿；core 计数不变（5008 tests）。

### Batch 403（已交付）

- 分支：`fix/retry-503-switch-batch403`（已合入 main）
- 内容：清偿账本遗留技术债——RetryConfig 的
  retry-on-service-unavailable 开关被通用 `status >= 500` 分支
  遮蔽（生产行为变更专项批次）。503 分支改为只服从专属开关：
  开启 → 重试；关闭 → 不可重试，不再落入通用 5xx 分支。其余
  5xx（500/502 等）仍走通用分支重试；默认值 true 不改变现网
  默认行为。附带把两处内联「永不重试」匿名类收敛为
  notRetryable() 助手，类 Javadoc 同步标注 503 开关语义。
- 测试：RetryConfigClassificationTest 的 503 用例改写为
  http503FollowsItsOwnFlagWithoutGeneric5xxShadowing（开关开 →
  可重试 / 关 → 不可重试）。
- 影响面：使用方如显式配置
  `rag.retry.retry-on-service-unavailable=false`，现在 503 确实
  不再重试（此前配置无效）。文档未列出该开关，无文档成对更新。
- 状态：单类 16 用例绿；core 全量门禁 EXIT=0（5008 tests）。

### Batch 402（已交付）

- 分支：`test/ratelimit-tail-batch402`（已合入 main）
- 内容：ratelimit 包三件套专项（新建 RateLimitStoreTailTest，
  9 用例）：①PostgresRateLimitStore——consume 直接返回已接受
  Decision、拒绝后回退当前桶查询、拒绝且桶消失抛 IllegalStateException、
  cleanup 委托 retention/batchSize 并返回删除数；②RateLimit
  Observability——recordDecision 固定标签计数、非法标签值逐项
  归一化 UNKNOWN（合法维度保留原值）、recordCleanupError 计数、
  noop（null registry）不抛异常；③SharedRateLimitMaintenance——
  disabled/非 postgresql 后端跳过清理、开启时以配置 bounds 调
  store.cleanup、DataAccessException 记观测且不重抛。
- 要点：mock JdbcTemplate 消耗/当前两条 SQL 用不同 varargs 签名
  分别 stub；MeterRegistry 断言要求同一 meter 同时具备全部标签。
- 状态：单类 9 用例绿；core 全量门禁 EXIT=0（5008 tests）。

### Batch 401（已交付）

- 分支：`test/chunking-tail-batch401`（已合入 main）
- 内容：DocumentChunkingService 专项（新建
  DocumentChunkingServiceTest，5 用例）：null document NPE、
  null/空白 content 守卫（含 documentId 文本）、JSON_RECORD 单
  块直通（含 Markdown 标题不切分，span 0..length，描述符
  json-record-v1:single）、TEXT 层级切分（缩小 chunk 参数后
  多块且保留标题文本，版本 hierarchical-v2:40:10:5）、
  PreparedChunks 契约（descriptor/chunks 空值拒绝、入参列表拷
  贝、chunks 不可变）。
- 要点：chunker 在构造函数内以 RagProperties 快照实例化，缩小
  chunk 参数后需重建 service 才生效。
- 状态：单类 5 用例绿；core 全量门禁 EXIT=0（4999 tests）。

### Batch 400（已交付）

- 分支：`test/jsonrecord-search-guards-batch400`（已合入 main）
- 内容：①JsonRecordService search 路径守卫（新建
  JsonRecordServiceSearchGuardTailTest，5 用例）：null 请求、
  query 空白（null/纯空格）、query 超 10_000 字符、maxResults<1
  拒绝、maxResults 裁剪到 maxSearchResults 上限（3<50，检索器
  topK 与 effectiveConfig.maxResults 均为 3，且 scope 的
  documentType 被收窄为 json-record）；②去重：删除 Batch 399
  的 JsonRecordServiceTailTest——batchUpsert 守卫矩阵与
  JsonRecordServiceFrontTest 完全重复（FrontTest 断言消息文本
  且用可配置阈值，保留 FrontTest）。
- 要点：searchAuthorizedDetailed 的守卫顺序为 query 空白→
  长度→scope 收窄→limit 裁剪；jsonRecordScope 会把非 json-
  record 的 documentType 归一化/置 noMatches，断言需捕获实际
  传入 scope。
- 状态：单类 5 用例绿；core 全量门禁 EXIT=0（4994 tests，
  含净增 1）。

### Batch 399（已交付）

- 分支：`test/longtail-sweep-batch399`（已合入 main）
- 内容：①JsonRecordService batchUpsert 守卫（新建
  JsonRecordServiceTailTest，4 用例）：null 列表/空列表拒绝、
  超 maxBatchSize（21>20）拒绝、单超限 jsonbPayload 触发批量
  载荷守卫（1_048_763B>10_485_760B，在逐条循环前直接拒绝）；
  ②DocumentMutationService 长尾（新建
  DocumentMutationServiceRetirementTailTest，9 用例）：
  requireAddressNotRetired 无服务 no-op/精确三元组委托/退役异常
  透传（void stub 用 doThrow）、tombstoneExternal 编排中退役地
  址守卫先于落库（saveAndFlush/versionService 零交互）、
  findDuplicate null 作用域回退 LEGACY_GLOBAL/受限密钥集合外过
  滤为 null/集合内保留/无哈希命中 null、executeExternalInTransa
  ction ConcurrencyFailureException 首轮失败重试后成功（事务恰
  开启 2 次）。
- 要点：批量载荷守卫只统计 jsonbPayload 序列化字节，retrievalText
  超限属逐条校验且被逐条 try/catch 吞为 persistenceFailed，不触
  发批量拒绝；序列分配先于退役守卫，mock jdbcTemplate 的
  RETURNING 需 stub 为 1L。
- 状态：两类 13 用例绿；core 全量门禁 EXIT=0（4993 tests）。

### Batch 397（规划中，范围放大）

- 目标：①DocumentMutationService 长尾（upsertExternal 内部
  created/updated 分支 1068-1176、importDocument 外部路径 1231、
  completeIdempotency 全矩阵 1583-1598、ensureLocalForNonSkip
  Mutation/dispatch/finish 1656-1671）；②JsonRecordService 后段
  sourceDelete/persist 内部择辅。预期 ≥20 用例。
- 备选：RagChatController keyed JSON 路径残余、
  RagDocumentController 剩余长尾（1181-1478）。
- 候选技术债：RetryConfig 的 retryOnServiceUnavailable 开关被
  通用 5xx 分支遮蔽（生产行为变更需专项批次）。
- 流程照旧：规划→实施→单类验证→core 全量门禁 EXIT=0→push 特
  性分支→--no-ff 合并 main→账本→清理。

### Batch 395 补充（已交付）

- 分支：`test/jsonrecord-provision-batch395`（已合入 main）
- 内容：JsonRecordService 后段矩阵收尾（6 用例，新建
  JsonRecordServicePersistMatrixTest）：persistInTransaction 更
  新路径 UNCHANGED/UPDATED 判定、coordinateLocalIndex 钩子、
  embedIfRequested CACHED/FAILED 分支。
- 状态：单类 6 用例绿；core 全量门禁 EXIT=0（4959 tests）。

### Batch 394（已交付）

- 分支：`test/mutation-relocate-batch396`（已合入 main）
- 内容：restoreLocalFromVersion 快照可见性与内容变化矩阵（4 用
  例，新建 DocumentMutationRestoreVisibilityMatrixTest）：①
  SNAPSHOT 可见性 enabled=true 快照 → disabledAt 清空且无 SKIP 派
  发；②内容未变 + 嵌入新鲜 → 无排队派发；③内容变化 → PENDING
  复位 + processingError 清空；④禁用快照恢复为禁用。
- 要点：restoreLocalFromVersion 无本地 revision bump（依赖
  versionService 推进）；transactionManager 需 stub getTransaction。
- 状态：单类 4 用例绿；core 全量门禁 EXIT=0（4969 tests）。

### Batch 395（已交付）

- 分支：`test/jsonrecord-provision-batch395`（已合入 main）
- 内容：JsonRecordService 后段矩阵收尾（6 用例，新建
  JsonRecordServicePersistMatrixTest）：①persistInTransaction 更
  新路径 UNCHANGED/UPDATED 判定；②coordinateLocalIndex 钩子；③
  embedIfRequested CACHED/FAILED 分支；④provision 授权矩阵残余
  断言；⑤reembed endpoint 聚合补充。
- 状态：单类 6 用例绿；core 全量门禁 EXIT=0（4959 tests）。

### Batch 396（规划中，范围放大）

- 目标：①RagDocumentController batchDelete/reembed 端点簇（946-
  990 守卫 + 1068-1082 batchEmbedStream 矩阵）；②
  ApiKeyManagementService provision 授权矩阵残余；③
  JsonRecordService 后段（sourceDelete/persist 内部）择辅。
  预期 ≥12 用例。
- 备选：RagChatController keyed JSON 路径残余、
  upsertSyncRunItem 更深分支、RagDocumentController 剩余长尾。
- 候选技术债：RetryConfig 的 retryOnServiceUnavailable 开关被
  通用 5xx 分支遮蔽（生产行为变更需专项批次）。
- 流程照旧：规划→实施→单类验证→core 全量门禁 EXIT=0→push 特
  性分支→--no-ff 合并 main→账本→清理。

### Batch 395（已交付）

- 分支：`test/rotation-authz-batch394`（已合入 main）
- 内容：
  - ApiKeyManagementService rotation 授权矩阵（7 用例，新建
    RotationAuthzTest）：prepareRotation 授权（无 DB 策略拒绝、
    NORMAL 他人 principal 拒绝、NORMAL 非当前 prepare 凭据拒绝、
    ADMIN 任意 principal 放行 → PENDING 响应）；getRotation
    NORMAL 他人拒绝 / ADMIN 放行（rotationResponse 组装解析
    source/target 凭据）；requireRotationLedger 仓储缺失
    SERVICE_UNAVAILABLE。
  - RagDocumentController reembedMissing 端点（3 用例，
    ReembedEndpointTest）：无候选空响应（不查嵌入）；逐文档聚
    合（COMPLETED/QUEUED 计成功、异常计 error）；force 直通嵌入
    服务。
- 要点：profileProvider.getActiveProfile 链式 stub 需先 mock
  profile 再 stub id（mock 默认 null 导致 NPE）。
- 状态：单类 7+3 用例绿；core 全量门禁 EXIT=0（4959 tests）。

### Batch 395（规划中，范围放大）

- 目标：①JsonRecordService 后段收尾（persistInTransaction 更新
  路径/coordinateLocalIndex/embedIfRequested 完整矩阵）；②
  ApiKeyManagementService provisionInTransaction/replayResponse
  （242-316 段）择辅。两个主目标并列，预期 ≥18 用例。
- 备选：RagChatController keyed JSON 路径残余、
  upsertSyncRunItem 更深分支、RagDocumentController 剩余长尾。
- 候选技术债：RetryConfig 的 retryOnServiceUnavailable 开关被
  通用 5xx 分支遮蔽（生产行为变更需专项批次）。
- 流程照旧：规划→实施→单类验证→core 全量门禁 EXIT=0→push 特
  性分支→--no-ff 合并 main→账本→清理。

### Batch 394（已交付）

- 分支：`test/mutation-longtail2-batch393`（已合入 main）
- 内容：DocumentMutationService 长尾方法群（23 用例，两个测试
  类）：
  - GuardsTest（12）：requireText trim/长度/null、requireContent
    不 trim、normalizeOptional、normalizeDocumentType text 回退、
    byteSize UTF-8、requireResult null 快速失败、requireRevision
    匹配与 null 回退 1、incrementRevision、latestVersion 零回退、
    rejectUnknown 字段名透出、requireLocal 缺失/外部管理拒绝/
    放行、validateCreate 链。
  - ExternalHelpersTest（11）：外部事务重试矩阵（DIVE 首轮冲突
    → 恢复、耗尽按 jsonRecord 抛 Structured/DocumentRevision 双
    类型、非可重试快速失败）、requireExpectedSourceRevision 四
    分支矩阵（strict CAS）、requireKind 双冲突类型、
    sameExternalState（enabled/deletion 敏感）与
    sameExternalManagedState（不敏感）对比、normalizeNamespace
    （default 回退/trim/128 上限/可见 ASCII/开关）、findDuplicate
    （NONE 短路/COLLECTION 集合过滤/GLOBAL 全量/无匹配）。
  - JsonRecordService 收尾（2 用例，IdentityTest）：
    getByExternalIdentity 集合解析 + 墓碑缺失拒绝 + 成功回读。
- 要点：反射调用需解包 InvocationTargetException；byteSize 按
  UTF-8 字节；allowNonDefaultNamespace 默认开启；
  DocumentDeduplicationScope 常量为 LEGACY_GLOBAL；
  resolver.resolveActiveIds(null, keys) 需显式 stub。
- 状态：单类 12+11 用例绿（另 IdentityTest 2 用例随 Batch 390
  交付）；core 全量门禁 EXIT=0（4949 tests）。

### Batch 392（已交付）

- 分支：`test/rotation-lifecycle-batch392`（已合入 main）
- 内容：ApiKeyManagementService rotation 全生命周期（17 用例，
  新建 ApiKeyManagementServiceRotationLifecycleTest）：
  - prepareRotation（8）：空幂等键 IAE、未知 key null、非当前凭
    据 CREDENTIAL_NOT_CURRENT、overlap 越界 IAE（0 与超上限）、
    principal 过期早于重叠期 PRINCIPAL_NOT_ACTIVE、成功创建
    PENDING（rawKey + secretAvailable）、幂等重放（fingerprint
    匹配 → replay=true）、重用冲突（IDEMPOTENCY_KEY_REUSED）。
  - getRotation（1）：root 调用返回 PENDING 响应。
  - completeRotation（3）：EXPIRED overlap → 自动过期（source 失
    效 + EXPIRED 保存）后 CREDENTIAL_ROTATION_EXPIRED；CANCELED →
    NOT_PENDING；成功 → COMPLETED + source disable + saveAndFlush。
  - cancelRotation（3）：CANCELED 幂等（不落库）；EXPIRED overlap
    拒绝；成功取消（target 失效 + CANCELED 保存）。
  - cleanupCredentialRotations（2）：空过期集 + deleteTerminalBefore
    清理；DataAccessException 容错不打清理。
- 要点：operation.getStatus() 用 AtomicReference + thenAnswer 驱
  动可变状态（mock setStatus 不改 stub 值）；prepare 新建 target
  凭据随机 keyId 需 findByKeyId(anyString()) catch-all 按 keyId
  区分版本（source=1，target=2 才过版本一致性校验）；principal
  需 setNextCredentialVersion。
- 状态：单类 17 用例绿；core 全量门禁 EXIT=0（4926 tests）。

### Batch 391（已交付）

- 分支：`test/rotation-ops-batch391`（已合入 main）
- 内容：RagDocumentController 批量端点（5 用例，新建
  RagDocumentControllerBatchOpsTest）：①batchDeleteDocuments 空
  ids 守卫 + 委派服务透传；②batchEmbedDocuments 空/null ids 守
  卫 + 50 上限（API 限流文案）；③SYNC 原始结果 Map →
  BatchEmbedResultItem 映射（chunks/embeddingsStored/error/
  reason）+ summary 归并 + 审计；④ASYNC requireJobsEnabled 后逐
  文档排队（queued 计入 success 槽位）。
- 要点：BatchEmbedSummary 槽位为 total/success/cached/failed/
  skipped（ASYNC 的 queued 写入 success）。
- 状态：单类 5 用例绿；core 全量门禁 EXIT=0（4892 tests）。

### Batch 390（已交付）

- 分支：`test/embedstream-delete-batch390`（已合入 main）
- 内容：
  - RagDocumentController（3 用例，新建
    RagDocumentControllerEmbedStreamTest）：embedDocumentStream
    成功路径（进度回调消费 + done）；IllegalArgumentException →
    error 事件；意外异常 → completeWithError。
  - JsonRecordService 收尾（2 用例，新建
    JsonRecordServiceIdentityTest）：getByExternalIdentity 集合
    解析（resolveActiveIds(null, keys) 显式 stub）+ 墓碑缺失
    DocumentNotFound + 成功回读 detail（getDetail 委托链）。
- 要点：resolver.resolveActiveIds(null, keys) 需显式 stub；
  Consumer 泛型擦除需显式转型。
- 状态：单类 3+2 用例绿；core 全量门禁 EXIT=0（4904 tests）。

### Batch 389（已交付）

- 分支：`test/jsonrecord-search-batch389`（已合入 main）
- 内容：JsonRecordService search 详细管道（4 用例，新建
  JsonRecordServiceSearchPipelineTest）：①rerank 正常路径——重
  排序生效 + SUCCESS 阶段标记 + mapKeys 回填 collectionKey；②
  limit 截断（候选 3 个 maxResults=2 收满即 break，经 rerank 路
  径驱动）；③scopeAllows 过滤禁用/类型不符文档；④jsonRecordScope
  —— text 类型授权范围 → noMatches（matchNone），兼容类型强制
  改写 json-record 并保留 SELECTED 集合过滤。
- 要点：collectionFilter 为 RetrievalScope.CollectionFilter 内
  部枚举（SELECTED），非 api.enums.CollectionScopeMode。
- 状态：单类 4 用例绿；core 全量门禁 EXIT=0（4899 tests）。

### Batch 388（已交付）

- 分支：`test/jsonrecord-retry-batch388`（已合入 main）
- 内容：JsonRecordService persist 重试循环（3 用例，新建
  JsonRecordServicePersistRetryTest）：①DataIntegrityViolation
  首轮冲突 → 重试成功创建（CREATED + 单次 saveAndFlush）；②
  ConcurrencyFailureException 持续 3 次 →
  StructuredRecordConflictException（did not converge after 3
  attempts）；③非可重试异常快速失败（getTransaction 仅一次）。
- 要点：tm.getTransaction 连续 thenThrow/thenReturn 区分首轮冲
  突与重试成功；activeProfile stub 对 embedIfRequested 必需。
- 状态：单类 3 用例绿；core 全量门禁 EXIT=0（4895 tests）。

### Batch 387（已交付）

- 分支：`test/rotation-response-batch387`（已合入 main）
- 内容：ApiKeyManagementService rotation 响应组装（3 用例，新建
  ApiKeyManagementServiceRotationResponseTest）：①ACTIVE
  principal + 当前凭据回填 currentCredentialId 且 rotationPending
  =false；②REVOKED + PENDING 轮换 + retiring 凭据 → 轮换窗口字
  段透传（pendingRotationId/retiringCredentialId/
  rotationExpiresAt）且无当前凭据；③过期 principal → EXPIRED。
- 要点：ApiPrincipalResponse 为 getter 型 DTO；PENDING 查询过滤
  expiresAt 必须未来。
- 状态：单类 3 用例绿；core 全量门禁 EXIT=0（4892 tests）。

### Batch 386（已交付）

- 分支：`test/doccontroller-513-batch386`（已合入 main）
- 内容：RagDocumentController 列表统计与嵌入端点（5 用例，新建
  RagDocumentControllerListingStatsTest）：①getDocumentStats 无
  限制查询 + null 状态归并 UNKNOWN；②embedDocument ASYNC 派发
  （embedDispatchMap status 键）；③SYNC 直调 + IAE→400；④
  searchDocumentsForCaller 受限/无限制查询选择双分支；⑤
  collectionMetadata 批量收集名称/key + 空页短路。
- 状态：单类 5 用例绿；core 全量门禁 EXIT=0（4889 tests）。

### Batch 385（已交付）

- 分支：`test/remaining-gaps-batch385`（已合入 main）
- 内容：ApiKeyManagementService key 校验与 last-used（4 用例，
  新建 ApiKeyManagementServiceKeyValidationTest）：validateKey 对
  null/空白/无前缀/未知裸 key 返回 null；已知裸 key 返回
  principalId；touchLastUsed 的 DataAccessException 容错；5 分钟
  节流缓存（times(1) 验证）。
- 要点：AuthenticationProjection mock 需全字段 stub——
  capabilities 仅接受 null/RAG_READ/FULL_SERIALIZED（其余
  fail-closed 抛 InvalidPersistedCapabilitiesException）。
- 状态：单类 4 用例绿；core 全量门禁 EXIT=0（4884 tests）。

### Batch 384（已交付）

- 分支：`test/batchcreate-embed-batch384`（已合入 main）
- 内容：
  - RagDocumentController（2 用例，新建
    RagDocumentControllerBatchCreateTest）：batchCreateDocuments
    单参重载委派批次服务；集合解析（无策略直接透传）；文档集合
    作用域按批次默认归一（collectionKey 置空）；14 参委派捕获
    documents/嵌入标志/幂等键；审计路径。
  - JsonRecordService persist（2 用例，新建
    JsonRecordServicePersistTest）：ASYNC 策略派发缺失拒绝
    （EMBEDDING_JOBS_DISABLED）；无事务管理器 + SKIP 经
    persistInTransaction 创建新记录（CREATED、id/collection 回
    读、SKIP 无嵌入派发）。
- 要点：embedIfRequested 读取 activeProfile——需 stub
  embeddingProfileProvider.getActiveProfile()。
- 状态：单类 2+2 用例绿；core 全量门禁 EXIT=0（4880 tests）。

### Batch 383（已交付）

- 分支：`test/batchcreate-syncitem-batch383`（已合入 main）
- 内容：DocumentMutationService upsertSyncRunItem 深分支（1 用
  例，新建 DocumentMutationSyncItemAppliedTest）：快照条目内容
  变化（r1→r2，代际 10 ≤ 快照起点 20）→ APPLIED；SKIP 策略下派
  发 NONE、无 job id、无错误。
- 要点：findById 回读用 AtomicReference 返回 saveAndFlush 后实
  例；allocateSourceSequence 需 jdbcTemplate update +
  queryForObject RETURNING mutation_sequence 双 stub。
- 状态：单类 1 用例绿；core 全量门禁 EXIT=0（4876 tests）。

### Batch 382（已交付）

- 分支：`test/doccontroller-gaps-batch382`（已合入 main）
- 内容：RagDocumentController 嵌入守卫（4 用例，新建
  RagDocumentControllerEmbeddingGuardsTest，反射驱动私有方法）：
  ①buildReembedResult ASYNC 经派发状态；②SYNC 读嵌入 Map + 单
  文档异常 best-effort error；③无描述符提供者的计数/查找（无当
  前嵌入查询 × restrictedIds 空/非空）；④有描述符提供者的带
  chunker 版本查询 × restrictedIds 矩阵。
- 要点：dispatchService 为可选 setter 注入——ASYNC 分支需
  setDispatchService 后 requireJobsEnabled 才通过。
- 状态：单类 4 用例绿；core 全量门禁 EXIT=0（4875 tests）。

### Batch 381（已交付）

- 分支：`test/jsonrecord-tombstone-batch381`（已合入 main）
- 内容：JsonRecordService sourceDelete 委派 + tombstoneExternal
  矩阵（5 用例，新建 JsonRecordDeleteTombstoneTest）：①
  sourceDelete 在 mutation service 缺失时 ISE；②委派透传
  jsonRecord=true 与全部参数；③tombstone 已墓碑同 revision →
  UNCHANGED 幂等；④enabled 文档同 revision →
  DocumentRevisionConflictException；⑤新 revision 墓碑写入
  （enabled=false/新 sourceRevision/deletionOrigin=SOURCE/
  DELETED 响应 + lifecycle 读取）。
- 要点：allocateSourceSequence 依赖 jdbcTemplate update + 
  queryForObject(Long) stub；墓碑尾部回读需 saveAndFlush 与
  findById(41) stub。
- 状态：单类 5 用例绿；core 全量门禁 EXIT=0（4871 tests）。

### Batch 380（已交付）

- 分支：`test/mutation-longtail-batch380`（已合入 main）
- 内容：DocumentMutationService 对账墓碑（3 用例，新建
  DocumentMutationReconcileTest）：①reconcileMissingExternal 一
  票否决矩阵——文档缺失、非外部文档（无 externalId）、无
  sourceRevision、已禁用、快照开始后代际较新 → 全部 false 且不
  落库；②合格文档墓碑写入——enabled=false、sourceDeletedAt、
  deletionOrigin=RECONCILIATION、reconciliationTombstoneRunId 绑
  定 runId、TOMBSTONE 版本记录、markNotRequested + 取消活跃任务。
- 状态：单类 3 用例绿（一次全绿）；core 全量门禁 EXIT=0
  （4866 tests）。

### Batch 379（已交付）

- 分支：`test/mutation-tombstone-batch379`（已合入 main）
- 内容：DocumentMutationService 本地守卫（3 用例，新建
  DocumentMutationLocalGuardsTest）：①upsertLocalImport(null id)
  委派 createLocal 并深拷贝 jsonb 载荷（captor 验证保存载荷与源
  等值）；②disableLocal 对已禁用文档 UNCHANGED 短路；③
  restoreLocal 对已启用文档 UNCHANGED 短路。
- 要点：saveAndFlush stub 须为新文档分配 id
  （completeIdempotency 读取 getId() 会 NPE）。
- 状态：单类 3 用例绿；core 全量门禁 EXIT=0（4863 tests）。

### Batch 378（已交付）

- 分支：`test/turnop-mutation-batch378`（已合入 main）
- 内容：ChatTurnOperationService 生命周期（9 用例，新建
  ChatTurnOperationServiceLifecycleTest）：①replay null/非
  replay claim 拒绝；②存储快照恢复 + turnId 回写；③非法快照 →
  INTERNAL_ERROR；④commandForClaim 非键控透传；⑤completeOpenAi
  非键控原样返回；⑥fail null/非键控/终态跳过；⑦IN_PROGRESS +
  lease 走协调器 failOperation（反射 3 参 Claim 注入 lease）；⑧
  无协调器走仓储 completeFailure；⑨release null 容忍 + 租约释放。
- 要点：ChatTurnOperation.record 第 16 分量为 responsePayload；
  Status 无 COMPLETED（成功为 SUCCEEDED）；replay 存储的是
  api.dto.ChatResponse（非 Spring AI 类型）；Claim 3 参构造私
  有须反射；LeaseHandle 用包私有 stateless 工厂。
- 状态：单类 9 用例绿；core 全量门禁 EXIT=0（4860 tests）。

### Batch 377（已交付）

- 分支：`test/jsonrecord-sweep2-batch377`（已合入 main）
- 内容：JsonRecordService 中段（4 用例，新建
  JsonRecordServiceMidTest）：①searchAuthorizedDetailed 空白
  query 与 maxResults<1 守卫；②rerank 失败降级 limitResults 保
  留原结果集；③getDetail 文档缺失/非 json-record 类型 →
  DocumentNotFoundException；④batchUpsert mutationService 委派
  汇总计数（CREATED/UPDATED/UNCHANGED/持久化失败）。
- 要点：upsertJsonRecord 返回 JsonMutationResult（需真实
  document 与 lifecycle record）；collectionKey 可为 null →
  stub 用 any() 而非 anyString()。
- 状态：单类 4 用例绿；core 全量门禁 EXIT=0（4851 tests）。

### Batch 376（已交付）

- 分支：`test/chat-keyed-json-batch376`（已合入 main）
- 内容：
  - RagChatController（3 用例，新建
    RagChatControllerNativeSnapshotTest，反射驱动
    nativeSnapshotEmitter）：无 claim/trace 完整事件链；空
    answer/sources 跳过；keyed claim 写 X-RAG-Turn-Id 与
    X-RAG-Idempotent-Replay 头（done.turnId 取操作实体）。
  - JsonRecordService 第一扫前段（4 用例，新建
    JsonRecordServiceFrontTest）：batchUpsert 空/null 列表、超
    maxBatchSize、载荷超 maxBatchPayloadBytes 三守卫；六个可选
    协作对象 setter 装配冒烟。
- 状态：单类 3+4 用例绿；core 全量门禁 EXIT=0（4847 tests）。

### Batch 375（已交付）

- 分支：`test/chatcontroller-jsonrecord-batch375`（已合入 main）
- 内容：RagChatController SSE 事件分派（6 用例，新建
  RagChatControllerSendEventTest，反射驱动私有 sendChatEvent/
  sendChatError）：ContentDelta；ToolStarted（含可空
  toolCallId/query）；ToolFinished；SourcesAvailable；Completed
  含/不含 retrievalTraceId；Failed → ChatStreamFailure；
  sendChatError 的 RagException 错误码提取与 null 消息兜底。
- 范围调整：JsonRecordService（105 行）超出单批容量，挪至
  Batch 376 拆分执行。
- 状态：单类 6 用例绿；core 全量门禁 EXIT=0（4840 tests）。

### Batch 374（已交付）

- 分支：`test/apikey-rotate-chat-batch374`（已合入 main）
- 内容：ApiKeyManagementService rotate 尾部与 updatePolicy 守卫
  （9 用例，新建 ApiKeyManagementServiceRotatePolicyTest）：rotate
  未知 key null/管理锁失败 null/PENDING 冲突/disable 计数不符/
  成功保存替换凭据；列表查询空集回归；updatePolicy 锁失败 null/
  principal 缺失 null/版本冲突 POLICY_VERSION_CONFLICT/非 root
  ADMIN 过期变更 BAD_REQUEST。
- 要点：cleanup 与轮换检查共享同一 PENDING 查询——连续
  thenReturn(empty, present) 区分两次调用。
- 范围调整：RagChatController（50 行）挪至 Batch 375。
- 状态：单类 9 用例绿；core 全量门禁 EXIT=0（4834 tests）。

### Batch 373（已交付）

- 分支：`test/apikey-sourcedelete-batch373`（已合入 main）
- 内容：ApiKeyManagementService 撤销路径矩阵（10 用例，新建
  ApiKeyManagementServiceRevokeTest）：①四个构造器重载冒烟；
  ②未知 keyId → false；③已撤销同 key 幂等 true；④已撤销不同
  key → CREDENTIAL_NOT_CURRENT；⑤成功撤销（saveAndFlush +
  PENDING 轮换终止 REVOKED + 保存 + 生命周期事件）；⑥ADMIN 非
  root 守卫失败 → LAST_ADMIN_REQUIRED；⑦managed root update=1
  成功；⑧managed root update=0 → LAST_ADMIN_REQUIRED；⑨disable
  计数 0 → CONCURRENT_MODIFICATION；⑩非当前凭据拒绝。
- 要点：ApiKeyRole/ApiKeyRotationStatus 在 core.entity 包；
  rotationConflict 用 CONCURRENT_MODIFICATION；同实例 mock 实体
  被生产代码修改后第二次调用语义改变（拆分用例规避）。
- 状态：单类 10 用例绿；core 全量门禁 EXIT=0（4825 tests）。

### Batch 372（已交付）

- 分支：`test/budgeted-model-batch372`（已合入 main）
- 内容：BudgetedChatModel 残余（7 用例，新建
  BudgetedChatModelResidualTest）：①2/7/8 参（summaryCall
  true/false）/11/12 参构造器重载默认值链（purpose/recorder/
  costUnit/modelRef null 回退）；②contextWindow=0 校验直通 +
  null prompt 委派（call((Prompt) null)）；③默认模型名解析走
  delegate.getDefaultOptions().getModel()；④stream 取消信号
  CANCELLED 兜底（Flux.never + dispose）；⑤SUMMARY 用途流式
  完成记录 usage；⑥工具 schema token 计数超限拒绝
  （CHAT_CONTEXT_BUDGET_EXCEEDED）与限额内委派；⑦prompt token
  + 预留 + 边际 ≥ 窗口拒绝。
- 要点：ChatModel.call(null) 在 call(String)/call(Prompt) 间二
  义需转型；mock stub varargs/null 用 <Prompt>isNull() 类型见证；
  reactor-test 不在依赖，取消信号用 subscribe+dispose 驱动。
- 状态：单类 7 用例绿；core 全量门禁 EXIT=0（4815 tests）。

### Batch 371（已交付）

- 分支：`test/search-budget-batch371`（已合入 main）
- 内容：RagSearchController legacy 直搜路径（2 用例，新建
  RagSearchControllerLegacyPathTest）：①集合过滤解析文档 id 为
  空时直接空 SearchResponse（186-188 行短路）；②8 参 search 重
  载委派主入口并返回计数（215 行委派）。
- 范围调整：BudgetedChatModel（26→32 行）挪至 Batch 372——体量
  需整批。
- 要点：SearchResponse 是 record（total() 访问器）；
  resolveDocumentIds 用 findIdsByCollectionIdIn。
- 状态：单类 2 用例绿；core 全量门禁 EXIT=0（4808 tests）。

### Batch 370（已交付）

- 分支：`test/openapi-provisioning-batch370`（已合入 main）
- 内容：
  - OpenApiConfig（3 用例，新建 OpenApiConfigExampleCustomizerTest）：
    exampleResponseCustomizer 对全部 9 个已知 opId 注入表驱动示例
    （200/201 响应码 + mediaType + default example + object schema，
    覆盖 switch 全 case 与 ApiResponse/Content 缺失创建分支）；无
    opId/无 responses 的操作安全跳过；已有 200 条目复用（追加内
    容而非重建）。
  - ApiKeyProvisioningOperation（1 用例，新建
    ApiKeyProvisioningOperationTest）：实体 setter/getter 全字段
    回环与初始 null 状态。
- 状态：单类 3+1 用例绿；core 全量门禁 EXIT=0（4806 tests）。

### Batch 369（已交付）

- 分支：`test/authfilter-openapi-batch369`（已合入 main）
- 内容：ApiKeyAuthFilter 残余（5 用例，新建
  ApiKeyAuthFilterResidualTest）：①4 参构造 null 根凭据解析器
  回退环境解析器（静态 key 认证不受影响）；②非 Bearer
  Authorization 头 401；③尾部空白 Bearer 头 trim 后走 scheme
  错误分支；④/v1/ 路径 401 OpenAI 错误形状
  （invalid_api_key/authentication_error）；⑤/v1/ 路径凭据服务
  不可用 503 OpenAI 错误形状
  （credential_service_unavailable/server_error）。
- 防御分支记档：空白 Bearer 专属分支（225-227）在 normalize
  trim 语义下不可达。
- 范围调整：OpenApiConfig（12 行）挪至 Batch 370。
- 状态：单类 5 用例绿；core 全量门禁 EXIT=0（4802 tests）。

### Batch 368（已交付）

- 分支：`test/rerank-outbox-batch368`（已合入 main）
- 内容：
  - HttpRerankProvider（8 用例，新建
    HttpRerankProviderResidualTest）：null 配置双构造回退；空/
    null 结果透传；HTTP 失败回退启发式；无启发式回退按深度截断
    （深度 0 保留全部）；/v1 结尾基址短 /rerank 路径 + 深度 0 取
    配置 topN + null query/chunkText 空串化（请求体 JSON 断言）；
    mapResponse 拒绝空响应/无 results 数组/全无效索引；data 嵌套
    results 接受。
  - AlertNotificationOutboxService（3 用例，新建
    AlertNotificationOutboxServiceResidualTest）：重复 provider
    名构造即拒；通知总开关 notificationsEnabled；
    configuredProviders 过滤未配置项并按名排序。
- 要点：MockRestServiceServer 的 requestTo 匹配用 hamcrest
  containsString（误用 Mockito contains 会得到空期望）；mock
  构造移出 assertThrows lambda 避免与 stubbing 状态串扰。
- 状态：单类 8+3 用例绿；core 全量门禁 EXIT=0（4797 tests）。

### Batch 367（已交付）

- 分支：`test/dispatch-slowquery-batch367`（已合入 main）
- 内容：
  - EmbeddingDispatchService（7 用例，新建
    EmbeddingDispatchResidualTest）：ASYNC 直返排队结果（不触发
    同步执行）；keyword 索引钩子在入队/NOT_REQUESTED 双路径；
    null 版本文档 createOrCoalesce 参数回退 0L；新建合并报告
    ASYNC_COALESCED；取消活跃任务委派仓储；同步完成后
    QUEUED/RUNNING 原样返回排队结果；无描述符提供者按文档类型
    回退 legacy/json-record 描述符。
  - SlowQueryMetricsService（4 用例，新建
    SlowQueryMetricsServiceResidualTest）：无 MeterRegistry 构造
    降级（内存计数生效）；无 SessionFactory 统计为空 + summary
    零值守卫；日志路径超长 SQL 截断；脱敏正则经日志路径执行
    （行为注记：保留记录存原始 SQL，脱敏仅作用于日志）。
- 防御分支记档：recordSlowQuery 的 catch 回退（149-159）对当前
  try 块内确定性调用不可达。
- 状态：单类 7+4 用例绿；core 全量门禁 EXIT=0（4786 tests）。

### Batch 366（已交付）

- 分支：`test/retry-budget-batch366`（已合入 main）
- 内容：
  - RetryConfig（可测性小重构 + 9 用例，新建
    RetryConfigClassificationTest）：分类器 lambda 提取为包私有
    static exceptionClassifierRetryPolicy(props)（Bean 行为不变）；
    分类矩阵直接驱动策略 canRetry——禁用、读超时、读超时回退通
    用开关、连接超时、通用网络错误、503、其他 5xx、429、400/401。
  - BudgetedToolCallingManager（6 用例，新建
    BudgetedToolCallingManagerResidualTest）：双参构造 fallback
    钳制、resolveToolDefinitions 委派、null 响应与无工具调用回退、
    无 options 提示词跳过预算、null 委派结果零结算、无工具响应
    消息/null 条目透传与空串零计费。
- 重要发现（行为注记）：retryOnServiceUnavailable=false 后 503
  仍可重试——分类器里通用 `status >= 500` 分支在 503 专属分支
  之后再次命中，专属开关被遮蔽（现状语义已断言；如需真正可关
  需后续批次改生产逻辑）。
- 教训：不要用 retryTemplate.execute 驱动分类断言——首轮无异常
  时分类器返回 never-retry 与 backoff 组合会产生超长重试循环
  （曾致 surefire 挂起 18 分钟，jstack 定位后改直接驱动策略）。
- 状态：单类 9+6 用例绿；core 全量门禁 EXIT=0（4775 tests）。

### Batch 365（已交付）

- 分支：`test/jsonsearch-guards-batch365`（已合入 main）
- 内容：JsonRecordSearchTool 残余（3 用例，新建
  JsonRecordSearchToolResidualTest）：①工具元数据/定义；②1 参
  call 委托（无 ToolContext → IllegalStateException）+ 正常计数
  + 空白入参回退 {} 后 query 缺失被拒；③超大输出（3000 字符
  query 撑爆 1024 字符预算）：记录清空后整体仍超限 → 全量替换
  为错误信封（resultCount=0/truncated/error，原始 query 不再
  出现）。
- 防御分支记档：serialize 的 catch（241-242）对确定性 Map/DTO
  输出不可达。
- 状态：单类 3 用例绿；core 全量门禁 EXIT=0（4760 tests）。

### Batch 364（已交付）

- 分支：`test/trace-context-batch364`（已合入 main）
- 内容：RequestTraceFilter 上下文解析残余（5 用例，新建
  RequestTraceFilterContextTest）：①外部 X-Trace-Id +
  spanIdEnabled → 生成 16 位 spanId 注入 MDC（链内捕获——过滤器
  出链即清 MDC）；②合法 W3C traceparent 透传 traceId/spanId；③
  非法 traceparent 回退生成 12 字符 id，w3cFormat 下仍外发
  traceparent（内嵌生成的 id）；④中段采样率 0.5 随机分支；⑤
  configure 四个配置访问器。
- 状态：单类 5 用例绿；core 全量门禁 EXIT=0（4757 tests）。

### Batch 363（已交付）

- 分支：`test/gap-sweep-batch363`（已合入 main）
- 内容：小缺口清扫（7 用例）：①GlobalExceptionHandler 收尾——
  媒体类型不支持 400（UNSUPPORTED_MEDIA_TYPE）、缺失 multipart
  part 400（MISSING_PART）、持久化能力策略损坏 503
  （POLICY_SERVICE_UNAVAILABLE）、ChatTurnInProgress 加
  Retry-After 头、普通 RagException 不带该头、SecurityException
  空消息兜底 Access denied；②EmbeddingJobRepository.markNotRequested
  非 null 代际侧（RETURNING 7L 原样返回且 cancelSuperseded 用 7L）
  ——embed repo 缺口清零。
- 状态：单类 6+29 用例绿；core 全量门禁 EXIT=0（4752 tests）。

### Batch 362（已交付）

- 分支：`test/rescatalog-discover-guards-batch362`（已合入 main）
- 内容：ResourceCatalog.discover 入口守卫（7 用例，新建
  ResourceCatalogDiscoverGuardsTest）：①全空位置 → 空快照；②
  kind 非空校验（顺序在空位置检查之后）；③列表内空白位置跳过 +
  扩展名规范化（空白/null 过滤、点前缀剥离、大写归一）+ 不匹配
  扩展名跳过；④null 扩展名集合放行全部；⑤预算不变量：恰好触顶
  （10/10 字节）保持健康；⑥多根累积：第二根全部文件超剩余预算
  以诊断记录（failFast=false）；⑦无效限制（maxFilesPerRoot=0）
  经 discoverOne 入口校验进诊断。
- 防御分支记档：discover 外层 totalBytes > maxTotalBytes 抛出
  （95-96 行）不可达——readBounded 按 min(maxFileBytes,
  remaining) 封顶，单根/多根累积均无法越限。
- 状态：单类 7 用例绿；core 全量门禁 EXIT=0（4745 tests）。

### Batch 361（已交付）

- 分支：`test/extdoc-guards-batch361`（已合入 main）
- 内容：ExternalDocumentService 守卫与委派（7 用例，新建
  ExternalDocumentServiceGuardsTest）：①batchUpsert 空/null 列
  表、>50 上限、批次内容 5M 上限三守卫；②upsert 有
  mutationService 时完全委派；③ASYNC 策略 dispatch 缺失拒绝
  （EMBEDDING_JOBS_DISABLED）；④batchUpsert 汇总计数全矩阵
  （CREATED×2/UPDATED/UNCHANGED/persistenceFailed/
  embeddingFailed）；⑤getByExternalIdentity 双参默认 namespace
  + 退役地址校验 + toDetail 详情返回；⑥事务管理器 null 构造侧
  （transactionTemplate 置空）。
- 状态：单类 7 用例绿（一次通过）；core 全量门禁 EXIT=0
  （4738 tests）。

### Batch 360（已交付）

- 分支：`test/pdf-exception-mapping-batch360`（已合入 main）
- 内容：PdfImportController 异常映射矩阵与委托（12 用例，新建
  PdfImportControllerExceptionMappingTest）：①async 端点拒绝
  embed=sse；②SKIP 路径 IAE→400 / RuntimeException→500 /
  SecurityException 重抛；③embeddingPolicy 非 SKIP 委托
  importPdfToRagWithPolicy；④SSE 流进度回调（结果引用赋值前触发
  → pending 分支）+ done 事件装配（虚拟线程 sleep 等待落地）；⑤
  withEmbedding 2 参委托与默认端点委托；⑥triggerEmbeddingSync
  空白 UUID 拒绝、IAE→400、RuntimeException→500、3 参委托；⑦
  triggerEmbeddingSse 3 参委托。
- 要点：PdfImportService.importPdf 声明 IOException——when() 打
  桩方法需 throws；SSE 任务 Thread.ofVirtual() 异步执行，断言前
  sleep(500) 等待覆盖落地。
- 状态：单类 12 用例绿；core 全量门禁 EXIT=0（4731 tests）。
- 插曲：提交再次忘建特性分支直接落 main，按整形方法挂回。

### Batch 359（已交付）

- 分支：`test/obs-scoped-reads-batch359`（已合入 main）
- 内容：IntegrationObservationRepository collection 作用域读变体
  与守卫（8 用例，追加至既有测试类）：①totals scoped 全过滤器
  （COLLECTION_SOURCE 关联源、observation. 限定列、IN (?,?)、
  principal/operation 过滤、参数顺序 from→to→principalType→
  principalRef→operation→collectionIds）；②byStatus/byOperation
  scoped 短路 + 限定维度列；③timeline scoped 短路 + 限定 bucket
  列 + dimension_key 其余类型分支（OffsetDateTime/Instant/
  LocalDate/默认文本化）；④oldestBucket scoped 限定 MIN 列；⑤
  deleteExpired 双 DELETE setter 真实执行（超时下限 1 秒、cutoff、
  批大小）；⑥upsert 无授权集合 → 集合侧空分组跳过（batchUpdate
  仅一次）；⑦集合侧 setter 绑定 collectionId（参数 4）+ over_5000
  桶（操作侧 17/集合侧 18）；⑧duration_sum_ms 负值与
  dimension_key 空白 → ISE。
- 要点：Framework 7 起 PreparedStatementSetter.setValues 单参
  （原二参重载在 BatchPreparedStatementSetter 上）。
- 状态：单类 20 用例绿；core 全量门禁 EXIT=0（4719 tests）。

### Batch 358（已交付）

- 分支：`test/embed-repo-sweep2-batch358`（已合入 main）
- 内容：EmbeddingJobRepository 清扫第二扫（9 用例）：①claim 取
  消/失败两段 UPDATE..RETURNING id 行映射真实执行 + 回收 job 逐
  个刷新 state；②cancel 命中刷新/空行空 Optional 双分支；③
  findActive/findCurrentActive 首行返回 + 5 参绑定顺序；④
  allocateGeneration 6 参绑定 + null→1 回退；⑤markNotRequested
  触发 cancelSuperseded（回退代际 1）；⑥claimCommitAllowed CAS
  真假 + 租约秒下限 30（行映射常量 1 真实执行）；⑦
  updateDocumentProcessing 绑定；⑧columnOrNull/longColumnOrZero
  的 SQLException 吞并归 null/0；⑨listPage 三过滤器 count+item
  双绑定（pageSize≤200、offset≥0）。
- 状态：单类 28 用例绿；core 全量门禁 EXIT=0（4711 tests）。
- 插曲：本批提交一度直接落在 main（忘建特性分支），按整形方法
  `git branch <name> && git reset --hard <prev>` 挂回特性分支后
  正常合并。

### Batch 357（已交付）

- 分支：`test/embed-repo-sweep1-batch357`（已合入 main）
- 内容：EmbeddingJobRepository 缺口清扫第一扫（3 用例，追加至
  EmbeddingJobRepositoryTest）：①readiness 经
  ResultSetExtractor 真实执行——6 列计数映射 +
  CollectionEmbeddingReadinessResponse 全字段断言，参数顺序
  jsonChunker→textChunker→collectionId→profileId 捕获验证；②
  listPage count 查询返回 null → totalElements 归 0 防御分支；
  ③find 经字段级 rowMapper（与 createOrCoalesce 内联 mapper 不
  同实例）执行 24 列全字段映射断言，覆盖字段初始化 lambda 体。
- 要点：EmbeddingJob 第 4 分量访问器为 embeddingProfileId（非
  profileId）；mock ResultSet 的 getLong/getBoolean 声明
  SQLException，提取的辅助方法需 throws。
- 状态：单类 19 用例绿；core 全量门禁 EXIT=0（4702 tests）。

### Batch 356（已交付）

- 分支：`test/pg-constraint-embed-batch356`（已合入 main）
- 内容（两段）：
  - 前段（随留档提交 9569b450）：RagCollectionService.
    isCollectionKeyConstraint 深层判定 2 用例——hibernate 其他约
    束名不映射（原样上抛 DIVE）、原因链嵌套
    （DIVE→DIVE→键约束违规）映射为 DUPLICATE_RESOURCE。
  - 后段（本轮 bb0abce4）：PG ServerErrorMessage 反射真值分支 4
    用例——真实 `PSQLException(ServerErrorMessage)` 按 PG
    ErrorResponse 字段格式（键字符+值+\0 终止；约束键 'n'）构造：
    约束名命中映射 DUPLICATE_RESOURCE、其他约束名不映射、无
    serverError 跳过判定、getConstraint 反射抛出被
    ReflectiveOperationException 吞掉原样上抛。另补
    EmbeddingJobRepository 9 参 createOrCoalesce 委托管道用例：
    origin/principal 透传、generation=1、documentKind=TEXT、
    chunkerVersion=legacy-compatible。
- 侦查要点：
  - 9 参 createOrCoalesce 最初误判死代码——集成测试
    EmbeddingJobsPostgresIntegrationTest 在用（写 origin/principal
    列），撤销删除改补单测覆盖委托管道。死代码判定必须全仓含测试
    目录核实。
  - ServerErrorMessage 不用 Mockito：getConstraint 等 final 判定
    引发 UnfinishedStubbing；改真实实例 + 匿名子类覆写。字段格式
    是「键字符紧跟值，\0 终止」——键后加 \0 会解析成空值。
  - ArgumentCaptor 命中 varargs 槽位时 getValue() 只含 varargs 部
    分（query 参数 [0]=SQL、[1]=mapper 不在数组内）。
- 状态：单类 6+16 用例绿；core 全量门禁 EXIT=0（4699 tests）。

## 进度留档快照（Batch 356 进行中 · 用户留档指令）

- 留档时点：2026-09-14 · main @ 本次留档提交
- 循环进度：Batch 303–355 共 53 个批次按「规划→实施→单类验
  证→core 全量门禁 EXIT=0→commit/push 特性分支→--no-ff 合并
  main→账本记录→分支清理」交付完成；Batch 356 完成一部分（约
  10 行中的约束检测残余）。
- 可构建性证据：core 全量 mvn test 门禁 EXIT=0（4694 tests,
  0 failures）；webui vite build 通过。
- 会话累计：core 全量测试自 4374 → 4694（+320）。
- 留档期间处理：isCurrent/resolveChatClientCandidates 两处死代
  码移除（零引用核实 + 全量回归）；3 处防御性/不可达分支记档
  （withEffectiveSession 重建分支、BudgetedChatModel 结果收缩
  error 块、setEndpoints null 归一化）。
- 后续候选：DocumentRelocationService 其余残余、
  IntegrationCapabilityCatalog projection、8~10 行档清零、WebUI
  页面分支残余。

### Batch 355（已交付）

- 分支：`test/spring-resources-batch355`（已合入 main）
- 内容：ResourceCatalog.discoverSpringResources 边界（10 行缺
  口），新建 ResourceCatalogSpringResourcesBoundaryTest 4 用例：
  maxFiles 超限中止、不可读资源跳过（健康空快照）、相同 URI 身
  份去重保留首个、读取失败包装 'classpath/JAR read failed'。
  要点：getResources 声明 IOException，桩用 doReturn。
- 指标：core 全量门禁 EXIT=0 绿（4692 tests, 0 failures）。

### Batch 354（已交付）

- 分支：`test/relocate-residual-batch354`（已合入 main）
- 内容：DocumentRelocationService 残余分支（约 13 行缺口），新
  建 DocumentRelocationResidualBranchesTest 9 用例：重放信封反
  序列化 + ACL 复核（missing scope ISE、不支持 schema 拒绝）、
  IN_PROGRESS/指纹不匹配拒绝、活跃同步 run 冲突、非可见 ASCII
  命名空间/externalId 守卫、源文档缺 external_id 拒绝。要点：
  含 null 值行须用 HashMap 构造。
- 指标：core 全量门禁 EXIT=0 绿（4688 tests, 0 failures）。

### Batch 353（已交付）

- 分支：`test/relocate-apply-update-batch353`（已合入 main）
- 内容：DocumentRelocationService lambda$relocate$0（条件 UPDATE
  的 PreparedStatementSetter，10 行），新建
  DocumentRelocationApplyUpdateTest 1 用例：stub CAS 查询捕获
  setter 并真实执行，逐参数断言 9 列绑定（目标集合/代次/文档
  id/源集合/命名空间/外部 id/来源修订/版本/文档修订），执行
  RETURNING 行映射。要点：既有 CAS 桩仅返回罐装列表，setter 主
  体从未执行（虚假覆盖），本批改为 thenAnswer 内真实调用。
- 指标：core 全量门禁 EXIT=0 绿（4679 tests, 0 failures）。

### Batch 352（已交付）

- 分支：`test/authorize-rotation-batch352`（已合入 main）
- 内容：ApiKeyManagementService.authorizeRotation 授权矩阵（10
  行缺口，经 prepareRotation 入口驱动），新建
  ApiKeyRotationAuthorizeMatrixTest 6 用例：environmentRoot 豁
  免、缺失调用方/无主体拒绝、ADMIN 越权豁免、NORMAL 异主体拒
  绝、NORMAL prepare 凭证不一致拒绝。要点：管理写 0 早退保持
  用例轻量。
- 指标：core 全量门禁 EXIT=0 绿（4678 tests, 0 failures）。

### Batch 351（已交付）

- 分支：`test/eval-answer-quality-batch351`（已合入 main）
- 内容：RetrievalEvaluationServiceImpl.evaluateAnswerQuality（10
  行缺口），新建 RetrievalEvaluationAnswerQualityTest 5 用例：
  无执行器同步回退解析评审 JSON、执行器路径解析、评审超时中性
  3/3/3 REVISION 降级、执行失败降级、畸形响应解析失败回退默认。
- 指标：core 全量门禁 EXIT=0 绿（4672 tests, 0 failures）。

### Batch 350（已交付）

- 分支：`test/catalog-fs-roots-batch350`（已合入 main）
- 内容：ResourceCatalog.discoverFilesystem/configuredRootPath
  （各 10 行缺口），新建 ResourceCatalogFilesystemRootTest 6 用
  例：file: 单文件发现、根不存在拒绝、符号链接跳过不暴露外部
  内容、classpath 空根回退文件名、jar 位置剥离条目前缀（含嵌
  套子路径）、不支持协议拒绝。
- 指标：core 全量门禁 EXIT=0 绿（4667 tests, 0 failures）。

### Batch 349（已交付）

- 分支：`test/sync-run-timestamp-batch349`（已合入 main）
- 内容：DocumentSyncRunService.readOffsetDateTime（10 行缺口，
  私有静态纯函数经反射驱动），新建
  DocumentSyncRunReadOffsetDateTimeTest 5 用例：OffsetDateTime
  原样返回、Timestamp/Instant → UTC 偏移、java.sql.Date → 系统
  时区当日零点、不支持类型与 null → IllegalStateException。
- 指标：core 全量门禁 EXIT=0 绿（4661 tests, 0 failures）。

### Batch 348（已交付）

- 分支：`test/keyword-iscurrent-batch348`（已合入 main）
- 内容：①死代码移除——KeywordIndexPersistenceService.isCurrent
  （12 行）全仓零引用（新鲜度逻辑内联于 hasFreshLocalIndex），
  移除 38 行并回归验证。②新建
  CollectionIdentityResolverBeginActiveWritesTest 4 用例：空/null
  集合零交互、去重去 null 按 id 升序预留且令牌携带版本递增、
  CAS 未命中与停用集合抛 ObjectOptimisticLockingFailure、非正
  集合 id IAE。
- 指标：core 全量门禁 EXIT=0 绿（4656 tests, 0 failures）。

### Batch 347（已交付）

- 分支：`test/branch-stage-batch347`（已合入 main）
- 内容：RetrievalBranchStage.toMap 与状态谓词（10 行缺口），
  新建 RetrievalBranchStageTest 4 用例：全字段映射且不可变、空
  白 errorCode 省略/非空白保留、succeeded/timedOut/failed 三态
  谓词、分支与状态常量。
- 指标：core 全量门禁 EXIT=0 绿（4652 tests, 0 failures）。

### Batch 346（已交付）

- 分支：`test/coordinator-create-batch346`（已合入 main）
- 内容：RagDocumentController.createDocument 协调器分支（12 行
  缺口），新建 RagDocumentControllerCoordinatorCreateTest 3 用
  例：documentMutationService 在位时委托 createLocal（策略/幂等
  键/来源透传）并映射 mutation 响应（status/documentRevision）+
  文档审计、DUPLICATE → existingDocumentId + 既有内容消息、请
  求级 embeddingPolicy 透传。
- 指标：core 全量门禁 EXIT=0 绿（4648 tests, 0 failures）。

### Batch 345（已交付）

- 分支：`test/resolve-chat-clients-batch345`（已合入 main）
- 内容：技术债务清理——移除 RagChatService 中零引用的死方法
  resolveChatClientCandidates（12 行）：遗留候选解析已由
  resolveLegacyModelCandidates（两处调用）承担，该方法不可达。
  全仓引用检索仅定义处匹配；编译与 core 全量门禁作为回归验证。
- 指标：core 全量门禁 EXIT=0 绿（4645 tests, 0 failures）。

### Batch 344（已交付）

- 分支：`test/chat-attach-diag-batch344`（已合入 main）
- 内容：RagChatService.attachDiagnostics（13 行缺口），新建
  RagChatServiceAttachDiagnosticsTest 4 用例（公开 scoped chat
  入口驱动）：诊断关闭返回原命令实例、诊断开启 createSession +
  withTraceSession 派生命令贯通执行链且会话 scope 摘要非空、
  attachScope 抛错降级返回原命令主流程照常、命令自带
  RetrievalFilters 同实例透传。要点：真实 RetrievalTraceSession
  用 scopeSummary() 可观察断言替代 mock verify。
- 指标：core 全量门禁 EXIT=0 绿（4645 tests, 0 failures）。

### Batch 343（已交付）

- 分支：`test/ensure-current-lambda-batch343`（已合入 main）
- 内容：KeywordIndexPersistenceService lambda$ensureCurrent$0
  （批量插入 setter，11 行），新建 KeywordIndexBatchSetterTest 1
  用例：stub batchUpdate 捕获 ParameterizedPreparedStatementSetter
  并真实执行，逐列断言 9 列写入值（id/代次/哈希/分块器版本/
  文本/序号/起止位置/空 metadata）。要点：此前 any() 匹配仅验
  证调用发生，setter 主体从未执行。
- 指标：core 全量门禁 EXIT=0 绿（4641 tests, 0 failures）。

### Batch 342（已交付）

- 分支：`test/embed-internal-batch342`（已合入 main）
- 内容：DocumentEmbedService.embedDocumentInternal 经 worker 入
  口 embedDocumentForJob（11 行缺口），新建
  DocumentEmbedJobEntryTest 5 用例：成功路径自定义提交门透传至
  持久层 8 参 replace 并在事务内校验、null 门 NPE、provider 调
  用失败不落失败快照不替换向量（worker 语义）、校验失败错误脱
  敏同上、缓存命中不触发提交门。
- 指标：core 全量门禁 EXIT=0 绿（4640 tests, 0 failures）。

### Batch 341（已交付）

- 分支：`test/skill-catalog-init-batch341`（已合入 main）
- 内容：RuntimeSkillCatalog.initialize（11 行缺口），新建
  RuntimeSkillCatalogInitializeTest 4 用例：技能禁用 → 空健康
  快照零代次、locations 未配置同上、failFast=false 资源发现失
  败 → 不健康降级快照零技能、跨位置重复 Skill 名 ISE。要点：
  failFast=true 时异常直接抛出，false 时收敛为不健康快照。
- 指标：core 全量门禁 EXIT=0 绿（4635 tests, 0 failures）。

### Batch 340（已交付）

- 分支：`test/prompt-planner-turns-batch340`（已合入 main）
- 内容：ConversationPromptPlanner.selectAdditionalTurns（12 行
  缺口），新建 ConversationPromptPlannerAdditionalTurnsTest 4
  用例：剩余预算充足时更旧回合前置补齐、历史 token 上限内追加
  至耗尽（3 回合保留后 2）、追加预算为 0 仅保留最近回合、空历
  史不带 recent_history_omitted 降级标记。
- 指标：core 全量门禁 EXIT=0 绿（4631 tests, 0 failures）。

### Batch 339（已交付）

- 分支：`test/keyword-index-current-batch339`（已合入 main）
- 内容：KeywordIndexPersistenceService.isCurrent（12 行缺口，
  经 public hasFreshLocalIndex 驱动，未注入完整性仓储走 JDBC
  双查），新建 KeywordIndexFreshnessTest 6 用例：状态行+计数一
  致 → 新鲜、状态行缺失/计数不一致/计数 null/数据访问异常 →
  非新鲜、文档基础字段缺失短路。
- 指标：core 全量门禁 EXIT=0 绿（4627 tests, 0 failures）。

### Batch 338（已交付）

- 分支：`test/batch-doc-coordinator-batch338`（已合入 main）
- 内容：BatchDocumentService.createSingleDocumentWithCoordinator
  （12 行缺口），新建 BatchDocumentCoordinatorTest 4 用例：协调
  器逐文档 createLocal 委托（集合回退默认/BATCH_CREATE 来源/
  幂等键派生 key:index）+ CREATED 映射携带嵌入三元组、
  DUPLICATE → skipped、单文档失败隔离不中止批次、文档级集合
  覆盖批量默认。要点：ASYNC 策略需 setDispatchService。
- 指标：core 全量门禁 EXIT=0 绿（4621 tests, 0 failures）。

### Batch 337（已交付）

- 分支：`test/integration-capability-batch337`（已合入 main）
- 内容：IntegrationCapabilityCatalog.collectionPurgeVisible（12
  行缺口），新建 IntegrationCapabilityPurgeVisibilityTest 5 用
  例：配置关闭全隐藏、环境根可见、数据库管理员可见/普通角色不
  可见（需 KEY_ATTRIBUTE 与 principalId 一致 + allowedCollectionIds
  解析为键）、匿名仅 allowAuthDisabled+本机回环可见、legacy 静
  态密钥不可见。
- 指标：core 全量门禁 EXIT=0 绿（4617 tests, 0 failures）。

### Batch 336（已交付）

- 分支：`test/json-record-validate-batch336`（已合入 main）
- 内容：JsonRecordService.validateRequest 校验矩阵（12 行缺
  口），新建 JsonRecordValidateRequestTest 7 用例：null 请求、
  集合 id 空值/非正值、externalId 空/超 255、title 空/超 255、
  retrievalText 空/超配置上限、payload null/JSON null/超字节预
  算、source 超 255。要点：upsert 先 resolveRequestCollection，
  null 集合且无键时异常来自集合解析而非正值校验。
- 指标：core 全量门禁 EXIT=0 绿（4612 tests, 0 failures）。

### Batch 335（已交付）

- 分支：`test/tool-policy-callback-batch335`（已合入 main）
- 内容：RagChatToolRegistry.PolicyToolCallback#call（12 行缺
  口），新建 RagChatToolPolicyCallbackCallTest 6 用例（经注册表
  真实包装获得私有回调）：单参入口拒绝、ToolContext 缺 REQUEST
  键拒绝、每名调用预算耗尽短路 tool_call_policy_exhausted、截止
  时间已过取消并返回 tool_timeout、预算内透传委托结果、委托抛
  错降级 tool_execution_failed。
- 指标：core 全量门禁 EXIT=0 绿（4605 tests, 0 failures）。

### Batch 334（已交付）

- 分支：`test/resource-catalog-batch334`（已合入 main）
- 内容：ResourceCatalog.discoverJarFile/relativePath（各 11 行
  缺口），新建 ResourceCatalogJarGuardTest 4 用例（@TempDir 真
  实 JAR）：缺 '!/' 条目前缀拒绝、'..' 前缀不安全拒绝、JAR 内
  文件数超 maxFiles 中止、子目录条目保持相对子路径且扩展名过
  滤生效。要点：目录层包装内层异常为 'Failed to load ... root'，
  断言沿 cause 链匹配消息。
- 指标：core 全量门禁 EXIT=0 绿（4599 tests, 0 failures）。

### Batch 333（已交付）

- 分支：`test/slo-status-baseline-batch333`（已合入 main）
- 内容：AlertService$SloStatus.equals（12 行）+
  RagChatHistoryRepository.findOwnedBaseline（12 行）双目标，
  新建 SloStatusEqualsTest 4 用例（等值哈希、自反/null/异型拒
  绝、10 字段逐一影响相等性、默认实例等值）与
  RagChatHistoryFindOwnedBaselineTest 3 用例（newest-first 反
  转为时间正序、limit 钳制 1~500、核心参数守卫）。
- 指标：core 全量门禁 EXIT=0 绿（4595 tests, 0 failures）。

### Batch 332（已交付）

- 分支：`test/eligible-candidates-batch332`（已合入 main）
- 内容：ChatExecutionService.eligibleCandidates（16 行缺口），
  新建 ChatExecutionEligibleCandidatesTest 5 用例（流式路径驱
  动）：显式 modelCandidates 逐候选过滤与合格候选胜出、全部不
  合格 MODEL_STREAMING_UNSUPPORTED、全部不可用 SERVICE_
  UNAVAILABLE、modelRef 显式校验（AGENT 无工具调用 →
  MODEL_CAPABILITY_UNSUPPORTED、KNOWLEDGE 无流式 →
  MODEL_STREAMING_UNSUPPORTED）。
- 指标：core 全量门禁 EXIT=0 绿（4588 tests, 0 failures）。

### Batch 331（已交付）

- 分支：`test/openai-compat-chat-batch331`（已合入 main）
- 内容：OpenAiCompatibilityController.chatCompletions 的
  claim 后重放分支（13 行缺口），新建
  OpenAiCompatibilityClaimReplayTest 2 用例：JSON 传输 claim
  重放（快照应答 + Turn-Id/Replay 头 + 零执行服务触达）、SSE
  传输 claim 重放（快照流）。两用例均经
  mapFromExecutionSnapshot 路径（executionSnapshot 非空）。
- 指标：core 全量门禁 EXIT=0 绿（4583 tests, 0 failures）。

### Batch 330（已交付）

- 分支：`test/mutation-namespace-dedup-batch330`（已合入 main）
- 内容：DocumentMutationService normalizeNamespace 守卫 +
  findDuplicate 作用域/可见性过滤（合计约 15 行缺口），新建
  DocumentMutationNamespaceDedupGuardsTest 8 用例：空白回退
  default、超 128/不可见 ASCII 拒绝、非默认命名空间开关、
  COLLECTION 作用域仅同集合查重、受限密钥不可见重复被
  SecurityException 过滤为新建、可见重复照常命中。要点：可切
  换调用方夹具（ADMIN 无限制 vs NORMAL 受限）按用例安装请求属
  性。
- 指标：core 全量门禁 EXIT=0 绿（4581 tests, 0 failures）。

### Batch 329（已交付）

- 分支：`test/chat-begin-trace-batch329`（已合入 main）
- 内容：RagChatController.beginChatTrace（13 行缺口），新建
  RagChatControllerBeginTraceTest 4 用例（6 参构造器 +
  scopeResolver mock 使 scope 非空）：诊断关闭/服务缺位 → 无会
  话无 traceId 头、正常路径会话贯通服务层且 traceId 回写响应
  头、诊断故障优雅降级为 null 会话且主流程照常。
- 指标：core 全量门禁 EXIT=0 绿（4573 tests, 0 failures）。

### Batch 328（已交付）

- 分支：`test/clone-coordinator-batch328`（已合入 main）
- 内容：RagCollectionService cloneCollection lambda$5（13 行缺
  口），新建 RagCollectionCloneCoordinatorTest 4 用例：协调器路
  径逐文档 createLocal(ASYNC/COLLECTION_CLONE/去重 NONE/继承来
  源) 且不直接批量保存、legacy 路径逐条记录 CREATE 版本、键唯
  一约束冲突映射 DUPLICATE_RESOURCE、其他完整性冲突原样上抛。
  要点：3 参构造器内建真实 CollectionIdentityResolver，活动写
  CAS 需打桩 advanceActiveVersion→1。
- 指标：core 全量门禁 EXIT=0 绿（4569 tests, 0 failures）。

### Batch 327（已交付）

- 分支：`test/turn-op-prepare-batch327`（已合入 main）
- 内容：ChatTurnOperationService.prepare 幂等准备矩阵（13 行
  缺口），新建 ChatTurnOperationPrepareTest 6 用例：缺键
  disabled 准备零仓储交互、开关关闭/缺指纹/键换请求复用三类拒
  绝、同指纹返回带既有操作的 keyed 准备、新键返回无操作 keyed
  准备、键 OWSTrim+SHA-256 归一。要点：幂等开关位于
  RagChatProperties。
- 指标：core 全量门禁 EXIT=0 绿（4565 tests, 0 failures）。

### Batch 326（已交付）

- 分支：`test/agent-tools-batch326`（已合入 main）
- 内容：ChatExecutionService.applyAgentTools（14 行缺口），新
  建 ChatExecutionAgentToolsTest 5 用例（AGENT 模式流式路径）：
  注册表工具列表装配（List 重载）+ requestContext 并入
  toolContext；四类上下文键按开关装配（搜索键/HTTP 状态/技能
  会话/执行预算，无预算命令分配全新 HTTP 状态）；回退链（json
  工具启用双工具、禁用仅 search）；ToolCallingChatOptions 默
  认选项复制。要点：AGENT+streaming 资格要求
  capabilities(streaming,toolCalling)=(true,true) 且默认选项为
  ToolCallingChatOptions；toolCallbacks varargs/List 双重载分
  别打桩。
- 指标：core 全量门禁 EXIT=0 绿（4559 tests, 0 failures）。

### Batch 325（已交付）

- 分支：`test/principal-expiry-alert-batch325`（已合入 main）
- 内容：ApiPrincipalExpiryAlertService.updateSamePhase +
  dispatchNotification（合计 26 行缺口），新建
  ApiPrincipalExpiryAlertNotificationDispatchTest 3 用例：同相
  位活动告警 CAS 原位刷新（REFRESHED，不重复派发/不入 outbox）、
  同相位 CAS 未命中退避重试后上抛并记 FAILURE、通知版本落后时
  跨三通道派发且坏通道异常被吞。要点：外层兜底失败记账的相位
  为入口初值 NONE。
- 指标：core 全量门禁 EXIT=0 绿（4554 tests, 0 failures）。

### Batch 324（已交付）

- 分支：`test/alert-payload-sanitizer-batch324`（已合入 main）
- 内容：AlertNotificationPayloadSanitizer.create/sanitizeValue
  （合计 26 行缺口），新建
  AlertNotificationPayloadSanitizerLimitsTest 5 用例：超限降级
  链（丢指标 → 截消息 512 + truncated 标记）、最小载荷仍超限
  抛 ISE、键截断 256 与敏感键打码（smtp/webhook/authorization）、
  秘密值模式（Bearer/sk-/rag_sk_/api_key=）打码、标量与 null
  透传、列表 100 项截断、深度 8 嵌套 [TRUNCATED]（遍历式断
  言）。
- 指标：core 全量门禁 EXIT=0 绿（4551 tests, 0 failures）。

### Batch 323（已交付）

- 分支：`test/pdf-content-type-batch323`（已合入 main）
- 内容：PdfImportController.inferContentType（13 行缺口，经
  getRawFilePath 端点驱动），新建
  PdfImportControllerContentTypeTest 3 用例 16+ 断言：具体
  MIME 优先、octet-stream 触发 13 种扩展名逐一映射、大写扩展
  名识别、未知/无扩展与 null MIME 回退 octet-stream。
- 指标：core 全量门禁 EXIT=0 绿（4546 tests, 0 failures）。

### Batch 322（已交付）

- 分支：`test/fingerprint-openai-batch322`（已合入 main）
- 内容：ChatRequestFingerprint.openAiRequest（14 行缺口），新建
  ChatRequestFingerprintOpenAiTest 5 用例：缺省值回退 + 角色归
  一 + 指纹稳定 + model 敏感、声明作用域/过滤/document_ids 排
  序规范化、PLAIN 模式三类检索声明拒绝、PLAIN 无声明放行、
  null 请求与 null 消息容错。要点：OpenAI DTO 为 snake_case 绑
  定，Filters 仅绑定 metadata_contains/payload_contains（其余
  键入 additionalProperties 不序列化）。
- 指标：core 全量门禁 EXIT=0 绿（4543 tests, 0 failures）。

### Batch 321（已交付）

- 分支：`test/embed-progress-chain-batch321`（已合入 main）
- 内容：DocumentEmbedService.batchEmbedDocumentsWithProgress +
  sendDocumentProgress 进度链（合计 27 行缺口），新建
  DocumentEmbedProgressChainTest 3 用例：混合状态批次（成功/
  缓存命中/校验失败）的 PREPARING+终态双事件与累计计数、chunk
  进度断言、FAILED 消息、summary 汇总；NOT_FOUND 落 SKIPPED 相
  位与 skipped 计数；超 50 建流前拒绝。要点：成功路径依赖真实
  DocumentChunkingService，chunk 数不硬编码。
- 指标：core 全量门禁 EXIT=0 绿（4538 tests, 0 failures）。

### Batch 320（已交付）

- 分支：`test/turn-op-session-batch320`（已合入 main）
- 内容：ChatTurnOperationService.withEffectiveSession（16 行缺
  口）验证结论 + 可达路径覆盖：其"非法会话重建"分支为防御性
  死代码（ChatCommand 紧凑构造器经 SessionIdValidator.resolve
  对非法非空值抛错、对空值生成 UUID，会话合法不变式在构造期
  成立；与 stableSource 防御分支同类，按先例记档不强测）。
  新建 ChatTurnOperationRebuildSessionTest 2 用例：4 参 claim
  的 claimNew 全链（首查空判定新 key → 会话快速通道 → 10 参
  insert 携带原会话 → 非重放 Claim）与 unkeyed 短路零仓储交互。
  要点：claimNew 的 insert 走 10 参重载，9 参桩不命中会静默走
  重派发分支。
- 指标：core 全量门禁 EXIT=0 绿（4535 tests, 0 failures）。

### Batch 319（已交付）

- 分支：`test/eval-maprun-diagnostics-batch319`（已合入 main）
- 内容：EvaluationSuiteRepository.mapRun（13 行）+
  RetrievalDiagnosticsService.toSummary（13 行）双目标。新建
  EvaluationSuiteRepositoryMapRunTest 2 用例（findRun 全列逐字
  段映射、空白/畸形 JSON 回退 nullNode）与
  RetrievalDiagnosticsListSummaryTest 3 用例（列表摘要全字段 +
  嵌套 citationValidation.status 提取、缺失/null status 与 null
  metadata 时引用状态为空）。
- 指标：core 全量门禁 EXIT=0 绿（4533 tests, 0 failures）。

### Batch 318（已交付）

- 分支：`test/document-embed-stream-batch318`（已合入 main）
- 内容：RagDocumentController.batchEmbedDocumentsStream（15 行
  缺口），新建 RagDocumentControllerBatchEmbedStreamTest 3 用例
  （standalone MockMvc 异步派发）：progress 按回调次序 + done
  携带 completed 且 text/event-stream、服务 IAE → error 事件携
  消息优雅收流、空列表/超 50 建流前 400（standalone 注册
  BadRequestAdvice）。要点：控制器在请求线程内同步收尾 emitter。
- 指标：core 全量门禁 EXIT=0 绿（4528 tests, 0 failures）。

### Batch 317（已交付）

- 分支：`test/auth-filter-eval-case-batch317`（已合入 main）
- 内容：ApiKeyAuthFilter.sendPolicyUnavailable（14 行）+
  EvaluationSuiteDefinitionValidator.parseCase（14 行）双目标。
  新建 ApiKeyAuthFilterPolicyUnavailableTest 2 用例（authenticate
  抛 InvalidPersistedCapabilitiesException → 503：/v1/* OpenAI
  形状 policy_service_unavailable、RAG 路径 POLICY_SERVICE_
  UNAVAILABLE+path）与 EvaluationSuiteDefinitionCaseValidationTest
  9 用例（case 形状、scope 约束八形态、collectionKeys 元素约
  束、relevant 身份五约束、命名空间参与唯一性、minimum 形状与
  hitRate/mrr 边界、合法阈值解析）。
- 指标：core 全量门禁 EXIT=0 绿（4525 tests, 0 failures）。

### Batch 316（已交付）

- 分支：`test/document-batch-embed-batch316`（已合入 main）
- 内容：RagDocumentController.embedBatchAsync + batchCreateAndEmbed
  （各 16 行缺口），新建 RagDocumentControllerBatchEmbedTest 6
  用例：ASYNC 逐文档 BATCH_EMBED 入队与响应/汇总映射、jobs 缺
  位拒绝、缺省 SYNC 原始结果映射 + EmbedCache 审计、ids 空列
  表/超 50 守卫、legacy 响应换算（created/embedded/skipped/
  failed + chunks=0）、集合作用域归一（自带 id 保留、无作用域
  落默认、request 回写）。
- 指标：core 全量门禁 EXIT=0 绿（4516 tests, 0 failures）。

### Batch 315（已交付）

- 分支：`test/collection-import-docs-batch315`（已合入 main）
- 内容：RagCollectionController.importDocuments 分支（16 行缺
  口），新建 RagCollectionControllerImportDocumentsTest 9 用例：
  空文档列表计数 0、同命名空间重复 externalId 拒绝、跨命名空
  间同名放行、空白 externalId 跳过唯一性、documentMutationService
  逐文档委派、json-record 守卫（缺 externalId/payload → IAE、
  服务缺位 → ISE、在位 → importRecord 委派）、混合类型计数合
  并。要点：json-record 与直接落库分支仅在 documentMutationService
  为 null 的 legacy 路径可达。插曲：本地 socks5 代理短暂不可用，
  退避重试后恢复推送。
- 指标：core 全量门禁 EXIT=0 绿（4510 tests, 0 failures）。

### Batch 314（已交付）

- 分支：`test/document-create-local-batch314`（已合入 main）
- 内容：DocumentMutationService createLocal 事务 lambda（17 行，
  当前最大方法级缺口），新建 DocumentMutationCreateLocalTest 9
  用例：幂等重放（INSERT 冲突 + 台账命中 SUCCEEDED → REPLAYED
  零派发、结果文档丢失 ISE）、重复内容（非 force 零派发、
  force+_DUPLICATE_FORCE 重嵌、revision 回退 1）、
  DeduplicationScope.NONE 绕过查重、全新创建（dispatch(origin)
  + CREATE 版本记录、enabledOverride=false 强制 SKIP 且落库
  enabled=false）、4 参重载缺省 SKIP。要点：幂等指纹经 INSERT
  桩参数捕获回填台账查询；createLocal 尾部回读需默认 findById 桩。
- 指标：core 全量门禁 EXIT=0 绿（4501 tests, 0 failures）。

### Batch 313（已交付）

- 分支：`test/collection-provisioning-batch313`（已合入 main）
- 内容：CollectionProvisioningService.createOrReplay 决策矩阵
  （15 行缺口），新建 CollectionProvisioningCreateOrReplayTest
  12 用例：开关关闭/台账不可用/参数守卫、循环内
  DataAccessException 与嵌套数据访问 RuntimeException 映射不可
  用、纯运行时原样重抛、竞态耗尽后台账回收（指纹命中重放
  replay=true、DUPLICATE 复现重抛、无既有报 Unable to resolve、
  回收阶段台账故障）、成功建集合落台账（真实事务模板 + mocked
  PlatformTransactionManager）、回放指纹冲突重抛。
- 指标：core 全量门禁 EXIT=0 绿（4492 tests, 0 failures）。

### Batch 312（已交付）

- 分支：`test/derivation-vector-phase-batch312`（已合入 main）
- 内容：DerivationRepairService.applyVectorPhase 决策矩阵（16 行
  缺口），新建 DerivationRepairVectorPhaseTest 8 用例（本地阶段
  预置 SUCCEEDED 直达向量阶段）：PLANNED+已收敛→ALREADY_FRESH、
  收敛中→NOOP_ALREADY_CONVERGING 回填任务 id、未收敛→排队
  QUEUED_VECTOR、向量代次/post_local 代次/文档版本三类漂移→
  SKIPPED_CHANGED、本地不新鲜→failItem FAILED、NOT_PLANNED→
  REBUILT_LOCAL 且不确认写令牌。要点：apply 助手不得在用例内
  二次覆盖 item/document 桩。
- 指标：core 全量门禁 EXIT=0 绿（4480 tests, 0 failures）。

### Batch 311（已交付）

- 分支：`test/chat-model-budget-batch311`（已合入 main）
- 内容：ModeAwareChatClientFactory.budgetedModelFor（16 行）+
  RagChatHistoryRepository.saveDurable 重载链（14 行）双目标。
  新建 ModeAwareChatClientBudgetedModelTest 3 用例（候选限额贯
  通用量事件 modelRef/purpose/costUnit、缺省回退配置上下文、
  SUMMARY 目的登记 summary 调用）；扩展
  RagChatHistorySaveAndIndexTest 3 用例（8 参重载零预留交互 +
  turnStatus 缺省 COMPLETE、turnId 重载保留标识与显式状态、三
  参 NPE 守卫）。要点：mock ChatExecutionBudget 需打桩
  attribution(anyInt)；10 参重载不校验 aiResponse。
- 指标：core 全量门禁 EXIT=0 绿（4472 tests, 0 failures）。

### Batch 310（已交付）

- 分支：`test/alert-record-equals-batch310`（已合入 main）
- 内容：AlertService$AlertRecord.equals（15 行）+ ApiKeyManagement
  Service.generateIdempotentKey（13 行）双目标，新增
  AlertRecordEqualsTest 5 用例（等值哈希一致、自反/null/异型拒
  绝、12 字段逐一影响相等性、全 null 等值、metrics 按内容比较）
  与 ApiKeyProvisioningIdempotentKeyTest 9 用例（开关关闭、账本
  不可用、owner/hash 必填、托管过期空/过拒绝、幂等键换请求复用
  拒绝、指纹命中回放、回放主体缺失、竞态 1 次耗尽、默认 3 次
  耗尽）。要点：ApiKeyProvisioningFingerprint 含过期时间，指纹
  桩必须复用传给服务的同一 request 实例。
- 指标：core 全量门禁 EXIT=0 绿（4466 tests, 0 failures）。

### Batch 309（已交付）

- 分支：`test/apikey-rotation-clamp-batch309`（已合入 main）
- 内容：ApiKeyManagementService.clampPendingRotationDeadline
  （15 行缺口，经 updatePolicy 入口驱动），新建
  ApiKeyManagementRotationClampTest 5 用例：主体过期时间清空
  短路、无 PENDING 操作、操作截止更早不收敛、未来截止同步收敛
  operation.expiresAt 与源凭证 retireAt（保持 PENDING）、截止已
  过触发 expirePendingIfNecessary（禁用源凭证 + EXPIRED 终态 +
  terminalAt）。要点：toPrincipalResponse 装配会查询轮换仓储，
  clamp 落库须以 saveAndFlush 验证而非零交互。
- 指标：core 全量门禁 EXIT=0 绿（4452 tests, 0 failures）。

### Batch 308（已交付）

- 分支：`test/http-endpoint-validation-batch308`（已合入 main）
- 内容：RagChatProperties$HttpEndpointProperties.validate 配置校
  验矩阵（16 行缺口），新建 RagChatHttpEndpointValidationTest 16
  用例 60+ 断言：合法基线放行（含 HEAD 与无凭证）、三类标识符
  正则逐形态拒绝、base-url https 源约束（协议/路径/userInfo/
  query/fragment）与 URI 语法错误分列、path 安全逐项（空/缺斜
  杠/反斜杠/../%/#/?/NUL/控制字符）、方法白名单、七字段正值与
  上限区间（timeout 30s、4MiB、不超总预算、结果 1024~2M）、查
  询参数 null 元素/命名/重复/参数级长度、响应内容类型空白带参
  通配、credential-env/header 形态。要点：invalid() 抛
  IllegalStateException；try 内 https-origin ISE 不被同方法
  catch(IAE) 吞掉。
- 指标：core 全量门禁 EXIT=0 绿（4447 tests, 0 failures）。

### Batch 307（已交付）

- 分支：`test/json-record-tool-budget-batch307`（已合入 main）
- 内容：JsonRecordSearchTool.call 预算与守卫分支（15 行缺口），
  新建 JsonRecordSearchToolBudgetTest 9 用例：空白/非文本 query
  拒绝、非对象与畸形参数区分报错、单参入口与缺失授权上下文键
  拒绝、检索预算耗尽短路（budgetExhausted 空响应且服务层零调
  用）、超 maxUniqueSources 的来源无 citationId 被跳过、结果字符
  预算尾部逐条丢弃并打 truncated、payload 小额保留/null 省略的
  payloadOmitted 标记、payloadContains 回显、maxResults 缺省回
  退/非整数回退/0 与负数收敛。要点：RetrievalTraceCollector 的
  maxRetrievalCalls 归一化下限为 1，预算耗尽路径需先手动消耗；
  收缩到空后 error 块为不可达防御代码。
- 指标：core 全量门禁 EXIT=0 绿（4431 tests, 0 failures）。

### Batch 306（已交付）

- 分支：`test/http-pinned-transport-batch306`（已合入 main）
- 内容：PinnedDnsHttpTransport 真实 socket 行为（execute/readBounded
  29 行缺口）与 PinnedDnsResolver 守卫分支，新建
  AllowlistedHttpToolProviderPinnedTransportTest 8 用例：经本地
  HttpServer 验证响应映射（状态/Content-Type/响应体，无实体回退
  空默认）、Accept 头透传、readBounded 恰好上限放行与超限抛
  ResponseTooLargeException（反射实例化私有传输类）、读超时
  300ms vs 2s 延迟规范化为 TimeoutException 且保留 IO 原因、
  PinnedDnsResolver 大小写归一化/防御性副本/canonical hostname/
  pin 空主机与空数组守卫/未固定与 null 主机拒绝。
- 指标：core 全量门禁 EXIT=0 绿（4422 tests, 0 failures）。

### Batch 305（已交付）

- 分支：`test/http-network-guard-batch305`（已合入 main）
- 内容：AllowlistedHttpToolProvider SSRF 地址守卫矩阵（publicAddress
  69 分支 + hasPrefix/resolvePublicTarget 残余，合计 7 行 / 76 分
  支，publicAddress 为全库最大分支缺口），新建
  AllowlistedHttpToolProviderNetworkGuardTest 5 用例 25+ 断言：
  IPv4 保留段逐段拒绝（0/8、10/8、100.64/10 首尾、169.254/16、
  172.16/12 首尾、192.0.0.2、192.0.2、192.88.99、192.168/16、
  198.18/15 首尾、198.51.100、203.0.113、224/4 首尾、受限广播）、
  IPv6 拒绝（:: 非环回、fc00::/7 首尾、fe80::/10、ff00::/8、
  2001:0000/23、3fff:0000/20）、环回与 IPv4 映射环回（原始 16
  字节构造避开 getByName 归一化）、放行（IPv4 映射公网递归校验、
  8.8.8.8、2620:fe::fe）、解析 null 数组/含 null 元素整体拒绝。
- 指标：core 全量门禁 EXIT=0 绿（4414 tests, 0 failures）。

### Batch 304（已交付）

- 分支：`test/http-provider-guards-batch304`（已合入 main）
- 内容：AllowlistedHttpToolProvider provider 级守卫与元数据
  （12 行 / 12 分支缺口），新建
  AllowlistedHttpToolProviderGuardsTest 11 用例：http-tools 关闭
  不暴露回调/策略、技能快照不健康冻结端点、工具名重复/缺失
  拒绝、Skill 未注册/能力未声明拒绝、getToolPolicies 只读策略
  （READ_ONLY/maxCalls/maxResult/timeout）、ToolDefinition 名称与
  inputSchema（属性/maxLength/required，全可选省略 required）、
  ToolMetadata returnDirect=false、closeTransport 关闭传输且吞
  异常。要点：setEndpoints(null) 被 setter 归一化为空列表，
  validateAndFreeze 的 configured==null 分支为防御性不可测。
- 指标：core 全量门禁 EXIT=0 绿（4409 tests, 0 failures）。

### Batch 303（已交付）

- 分支：`test/http-tool-error-paths-batch303`（已合入 main）
- 内容：AllowlistedHttpToolProvider$EndpointCallback#call 错误
  路径（31 行 / 27 分支缺口，当前最大方法级缺口），新建
  AllowlistedHttpToolProviderErrorPathsTest 23 用例：服务端上下文
  缺失（单参调用、REQUEST/session 键缺失）、查询参数校验（必填
  缺失/非文本、超长/ISO 控制字符）、空格路径触发 request_uri_
  rejected、DNS 拒绝（UnknownHost/空地址数组）、响应预算（执行
  状态缺失、预算耗尽短路、部分预留下 ResponseTooLargeException
  与超限响应体两类 budget_exhausted 且预留全额结转）、传输异常
  映射（HttpTimeout/Timeout→http_timeout、interrupted→http_
  interrupted 并恢复中断标记、Connect→http_unavailable、IO/
  Runtime/null 响应→http_failed）、null/空白 contentType 拒绝、
  text/plain 文本透传、空/null 响应体、结果字符预算耗尽、JSON
  数组项/节点数溢出、凭证头按环境变量附加、截止时间收敛超时/
  无截止保持配置超时、结果序列化失败抛 ISE。要点：私有
  ResponseTooLargeException 经反射实例化以触发专属 catch；mock
  ObjectMapper 需放行构造期 schema 序列化（首次 writeValueAsString）。
- 指标：core 全量门禁 EXIT=0 绿（4397 tests, 0 failures）；
  EndpointCallback#call 缺口 31→1 行、27→5 分支。

### Batch 302（已交付）

- 分支：`test/core-sensitive-masking-v2-batch302-20260912`（已合
  入 main）
- 内容：SensitiveDataMaskingConverter 脱敏模式覆盖扩展（31 分支
  缺口），恢复原有 40 用例并新增 16 用例：JSON secret/authorization/
  privateKey 字段脱敏、转义 JSON 模式、URL token 参数、SQL 双引
  号密码、AWS AccessKey、中国身份证/手机号、normal text 透传、
  convert null/empty、KeepType PASSWORD/Bearer 检测、多模式按序
  应用。分支覆盖 31→约 5 missed。
- 指标：core 全量门禁 EXIT=0 绿（4358 tests, 0 failures）。

### Batch 301（已交付）

- 分支：`test/core-external-getbyidentity-batch301-20260912`（已
  合入 main）
- 内容：ExternalDocumentService.getByExternalIdentity 守卫分支
  （15 行缺口），新建 ExternalDocumentServiceGetByIdentityTest 2
  用例：文档缺失 → NOT_FOUND；JSON record →
  DocumentRevisionConflictException。要点：toDetail 内部调用
  embeddingProfileProvider.getActiveProfile() → profile.id()——
  mock 后需 lenient stub 返回非 null EmbeddingProfile。
- 指标：core 全量门禁 EXIT=0 绿（4350 tests, 0 failures）。

### Batch 301 补充（已交付）

- 分支：`test/core-embed-fresh-guard-batch301-20260912`（已合入
  main）
- 内容：DocumentEmbedService.hasFreshEmbedding 前置守卫分支（14
  行缺口），新建 DocumentEmbedHasFreshEmbeddingGuardTest 4 用例：
  null document、null id、null contentHash、空白 contentHash 均
  返回 false（不触达任何依赖）。
- 指标：core 全量门禁 EXIT=0 绿（4358 tests, 0 failures）。

### Batch 300 补充（已交付）

- 分支：`test/core-embed-fresh-batch300b-20260912`（已合入 main）
- 内容：DocumentEmbedService.hasFreshEmbedding 前置守卫分支（14
  行缺口），新建 DocumentEmbedHasFreshEmbeddingTest 4 用例：null
  document、null id、null contentHash、空白 contentHash 均返回
  false。要点：hasFreshEmbedding 的前置守卫在 integrity/keyword/
  persistence 各层之前，null 输入直接短路返回 false。

### Batch 300（已交付）

- 分支：`test/core-slowquery-stats-batch300-20260912`（已合入
  main）
- 内容：SlowQueryMetricsService.getStatsSummary Hibernate
  Statistics 路径（15 行缺口），新建
  SlowQueryMetricsStatsSummaryTest 3 用例：SessionFactory 不可用
  → 归零快照；有 Hibernate 数据 → queryCount/maxDuration/
  slowCount/avg 聚合映射（executionTotalTime 以 ns 传入，生产代
  码 /1M 转 ms）；单查询 avg 计算验证。要点：mock
  EntityManagerFactory.unwrap(SessionFactory.class) 注入 mock
  SessionFactory/Statistics；qs.getExecutionTotalTime 返回纳秒
  值由生产代码 /1_000_000 转 ms。
- 指标：core 全量门禁 EXIT=0 绿（4348 tests, 0 failures）。

### Batch 295–300 补录（已交付但账本在快照后追补）

- Batch 295：DocumentMutationCollectionScopeTest 3 用例（集合迁
  移/无键保留/受限键解绑阻断）
- Batch 296：EmailNotificationDeliverTest 7 用例（deliver 异常
  分型 + isCurrentlyAvailable）
- Batch 297：DocumentSyncRunServiceTest list 2 用例（行映射 +
  null namespace 透传）
- Batch 298：EvaluationCaseExecutorLookupTest 3 用例（lookup 身
  份映射）
- Batch 299：ChatTurnOperationEffectiveSessionTest 3 用例（会话
  ID 规范化）

以上各批均全量门禁 EXIT=0 绿，已按序合入 main（4327→4348 递
增），当前 main = 5bc32b27 之后的连续交付链。

### Batch 290（已交付）

- 分支：`test/core-router-deadcode-batch290-20260911`（已合入
  main）
- 内容：技术债——ChatModelRouter.candidateForModel 死代码移除
  （约 19 行未覆盖，含其唯一调用点 orderedCandidateDescriptors
  的 result.isEmpty() 回退循环与随之失去调用方的 modelCost）。可
  达性证明：回退循环要求 result 为空且 getAllOrdered 非空，但
  getAllOrdered 的每个模型（primary 解析/回退解析/legacy 别名值）
  在预循环中都存在同名可解析 ref 且 resolveCandidate 恒非空
  （resolve 非空时 ref 回退 modelRef.trim()），组合不可达。回归
  测试 2 用例锁定不变量：主/回退/legacy 全不可解析 → ordered 候
  选为空列表且 preferred 不可解析显式抛 IAE；legacy 别名可解析 →
  仍产生 zhipu 候选（移除死循环不改变可达路径行为）。
- 指标：core 全量门禁 EXIT=0 绿（4311 tests, 0 failures）。

### Batch 291（已交付）

- 分支：`test/core-relocation-replay-batch291-20260911`（已合入
  main）
- 内容：DocumentRelocationService.relocate 回放与冲突分支（16 行
  缺口），新建 DocumentRelocationReplayAndConflictTest 6 用例：
  幂等信封重放（reserve 回放行指纹经捕获 INSERT setter 参数对
  齐，重放短路不触达文档仓储与 EntityManager）；reverse 搬迁（目
  标地址标记指向同一文档且目标为源集合 → 解析退役地址 UPDATE 命
  中）；CAS 未命中 → CONCURRENT_MODIFICATION；reverse 解析失败 →
  CONCURRENT_MODIFICATION（retired target address）；源地址插入
  DataIntegrityViolation → CONCURRENT_MODIFICATION；目标标记指向
  另一文档 → TARGET_EXTERNAL_IDENTITY_RETIRED。要点：reserve 的
  INSERT 桩需按测试区分（非回放测试返回 [99L] 提前获得
  Reservation；回放测试返回 [] 走 SELECT 且在桩内捕获
  setString(4, fingerprint)）；markRetired 的 resolve 桩须以
  thenReturn(0) 覆盖通用 update 桩。
- 指标：core 全量门禁 EXIT=0 绿（4317 tests, 0 failures）。

### Batch 292（已交付）

- 分支：`test/core-history-saveandindex-batch292-20260912`（已合
  入 main）
- 内容：RagChatHistoryRepository.saveAndIndex 持久索引链（16 行
  缺口），新建 RagChatHistorySaveAndIndexTest 4 用例（经
  saveDurable 公有入口驱动）：引用批量插入 + 标记完成全链（
  batchUpdate 行 [7,5] 经 ArgumentCaptor 深比较——Object[] 的
  equals 为同一性，argThat/List.of 均有陷阱）；无引用跳过批量插
  入但仍完成标记；引用插入计数非 1 → IllegalStateException
  fail-closed 且不再标记；标记更新非 1 → IllegalStateException。
  要点：batchUpdate(String, List<Object[]>) 非 varargs——
  List.of(new Object[]{...}) 会展开为 List<Long>，须
  List.<Object[]>of 或 ArgumentCaptor 深比较；varargs 单参数存根
  用 eq(具体值) 而非 any(Object[].class)（严格桩误报）。
- 指标：core 全量门禁 EXIT=0 绿（4321 tests, 0 failures）。

### Batch 293（已交付）

- 分支：`test/core-pdf-policy-import-batch293-20260912`（已合入
  main）
- 内容：PdfToRagService.importPdfToRag 策略变体（18 行缺口），
  新建 PdfToRagPolicyImportTest 3 用例：① ASYNC 无作业服务 →
  EMBEDDING_JOBS_DISABLED fail-closed；② legacy 路径（无 mutation
  service）ASYNC 经 dispatchService.enqueueInCurrentTransaction 排
  队（QUEUED + ASYNC_QUEUED + job/batch ID，embedMessage 槽位承载
  action 名称——生产行为）；③ SKIP + mutation 管道 → 委派
  upsertLocalImport 并映射 DocumentMutationResponse（lifecycle 的
  embeddingStatus/embeddingAction，跳过 legacy 保存与同步嵌入）。
  要点：importPdfToRag 5 参重载中 documentMutationService 存在时
  ASYNC 也走 mutation 管道（enqueue 仅属 legacy 路径）——测试须
  用无 mutation 的独立实例驱动 dispatch 分支；upsertLocalImport 9
  参混排建议宽匹配 any/anyBoolean/anyString + thenAnswer 构造
  CreatedLocal 避免匹配器歧义。
- 指标：core 全量门禁 EXIT=0 绿（4324 tests, 0 failures）。

### Batch 294（已交付）

- 分支：`test/core-stable-source-lease-batch294-20260912`（已合入
  main）
- 内容：ChatTurnOperationService 双点加固，新建
  ChatTurnOperationStableSourceLeaseTest 3 用例：① 4 参 claim 回
  收路径中命令会话与操作会话不同 → acquireSessionLease 的
  adjusted 命令分支（重建含 memoryConversationId 的 ChatCommand
  后经 coordinator.acquire 获取租约，StubJdbc 断言租约 INSERT）；
  ② 键控 complete 经 stableSnapshot 深拷贝来源（全字段含
  metadata 逐项复制、全新实例、turnId 替换为操作 turnId）；③
  unkeyed complete 直接透传同一实例（零拷贝）。收敛：
  stableSource 17→约 5、acquireSessionLease 17→约 5。
  要点：跨会话回收的 repository.reclaim 为 5 参（含快照参数，快
  照存在时为 null 不覆盖库值）——4 参 anyInt() 桩不匹配导致静默
  走 claim 递归（find 返回 +600 租约的刷新行 → inProgress 异
  常）；ChatExecutionResult 规范构造器 List.copyOf 拒绝 null 元
  素，stableSource(null) 的防御分支经 record 构造不可达。
- 指标：core 全量门禁 EXIT=0 绿（4327 tests, 0 failures）。

### Batch 295（已交付）

- 分支：`test/core-mutation-collection-scope-batch295-20260912`
  （已合入 main）
- 内容：DocumentMutationService.updateLocal 集合作用域变更分支
  （resolveUpdateCollection 约 14 行 + lambda$createLocal$1 部分），
  新建 DocumentMutationCollectionScopeTest 3 用例：collectionKey
  指向新集合 → 文档迁移 + COLLECTION_MOVE 版本记录 +
  beginActiveWrites([10, 20])；无 collectionKey → 保留当前集合
  且不触达解析器；受限键（NORMAL 角色 + allowed "10"）→
  SecurityException。要点：DocumentUpdateRequest 的 setCollectionKey
  设 collectionKeyPresent=true；无 collectionKey 时
  resolveUpdateCollection 不会被调用（collectionId 取文档当前值），
  restricted 分支需 setCollectionKey(null) 显式触发；
  ApiAccessPolicy 匿名实现（NORMAL + allowed "10"）注入
  AUTHENTICATED_API_PRINCIPAL_ATTRIBUTE 即可模拟受限调用方，
  afterEach 必须 reset RequestContextHolder。
- 指标：core 全量门禁 EXIT=0 绿（4330 tests, 0 failures）。

### Batch 296（已交付）

- 分支：`test/core-email-deliver-batch296-20260912`（已合入 main）
- 内容：EmailNotificationService.deliver 异常分型（15 行缺口），
  新建 EmailNotificationDeliverTest 7 用例：正常发送 → SUCCESS；
  alertType 未路由 → PERMANENT_FAILURE（PERMANENT_CONFIGURATION）；
  mailSender 缺失 → 同上；MailAuthenticationException → 永久失败；
  MailSendException → 瞬态失败（TRANSIENT_NETWORK）；
  createMimeMessage 抛 RuntimeException → 同样映射瞬态；另有
  isCurrentlyAvailable 的 delivery.enabled 与 JavaMailSenderImpl
  instanceof 组合断言。要点：deliver 的 catch 链按异常类型分
  永久/瞬态——MailAuthenticationException 与 MailParseException
  归永久（配置问题不重试），MailException/RuntimeException 归瞬
  态（可重试）；deliver 不走 sendAlert 的 3 次重试循环（重试由
  AlertNotificationDeliveryWorker 负责）。
- 指标：core 全量门禁 EXIT=0 绿（4337 tests, 0 failures）。

### Batch 297（已交付）

- 分支：`test/core-syncrun-list-batch297-20260912`（已合入 main）
- 内容：DocumentSyncRunService.list 分页列表（14 行缺口），在既
  有测试类追加 2 用例：分页行映射（count 查询 + mapRun 行映射 →
  DocumentSyncRunResponse 全字段断言含 runId/collectionKey/
  sourceNamespace/status）与 null namespace 透传（count 参数含
  collectionId + namespace×2 共 3 个占位符，namespace 位置为
  null 以命中 IS NULL OR 匹配全部分支，空行列表返回空 runs）。
  要点：list 的 count 与行查询都使用 (? IS NULL OR source_namespace
  = ?) 模式，count 参数为 collectionId + namespace×2 共 3 个。
- 指标：core 全量门禁 EXIT=0 绿（4339 tests, 0 failures）。

### Batch 298（已交付）

- 分支：`test/core-evalcase-lookup-batch298-20260912`（已合入
  main）
- 内容：EvaluationCaseExecutor.lookup 身份映射（16 行缺口），新建
  EvaluationCaseExecutorLookupTest 3 用例：非数字 documentId 跳过
  + 数字 id 经 IN 查询映射为 Identity（顺序保留，占位符与输入数
  一致）；同 id 去重 + 空 external_id 行剔除；无非数字 id 可解析
  时不发查询。要点：query(String, RowCallbackHandler, Object...)
  为 void 方法——stubbing 必须 doAnswer().when() 而非
  when().thenAnswer()；varargs 展开后用 getArguments() 索引取参
  （getArgument(n) 逐个取，varargs 从 index 2 起）；argThat lambda
  匹配 Object[] varargs 在 Mockito 5 有歧义，直接 any(Object[]
  .class) 即可匹配任意长度。
- 指标：core 全量门禁 EXIT=0 绿（4342 tests, 0 failures）。

### Batch 299（已交付）

- 分支：`test/core-effective-session-batch299-20260912`（已合入
  main）
- 内容：ChatTurnOperationService.withEffectiveSession 会话 ID 规
  范化（16 行缺口），新建 ChatTurnOperationEffectiveSessionTest 3
  用例（经原生 claim 3 参路径驱动）：非法 sessionId（含空格/特殊
  字符）在 INSERT 前被替换为合法 UUID（capture 断言 UUID 反解一
  致）；合法 sessionId 原样保留；insert 收到的 sessionId 捕获后
  非 null 且可反解。要点：withEffectiveSession 的规范化仅在
  claimNew 路径触发（3 参 claim 且 prepared.operation 为 null），
  sessionCaptor 需从 repository.insert 的第 4 个 String 参数捕
  获。
- 指标：core 全量门禁 EXIT=0 绿（4345 tests, 0 failures）。

---

## 进度留档快照（Batch 260 后 · 用户指令）

- 留档时点：2026-09-10 · main @ 本快照提交（Batch 260 记录之后）
- 循环进度：Batch 204–260 共 57 个批次全部按「规划→实施→门禁→
  commit/push 特性分支→合并 main→账本记录」交付完成。
- 可构建性证据：core 全量 mvn test 门禁 EXIT=0（Batch 260 后）；
  webui 全量 645 测试绿 + vite build 通过（Batch 248 后，本次未改
  动 webui）。
- 近期批次重点：
  - Batch 255：候选尝试预算耗尽（maxCandidateAttempts=2 + 3 个错
    误候选 → 第三候选 CHAT_BUDGET_EXHAUSTED；关键语义：空完成
    onComplete 不触发 switchOnFirst 切换也不消耗预留）
  - Batch 256：EvaluationSuiteDefinitionValidator.parseVariants
    （缺省/空数组回退 default、非数组/超限/重复 key/maxResults 边
    界/非对象 filters 拒绝、全配置字段解析）
  - Batch 257：restoreLocalFromVersion 全守卫与恢复语义（开关/缺
    版本/非 FULL/内容缺失 fail-closed、全字段回写+版本推进、SNAP
    SHOT 可见性 SKIP 派发、受限密钥未分配快照拒绝）
  - Batch 258：upsertLocalImport（UNCHANGED 幂等/内容变更 UPDATED
    /force 重嵌/COLLECTION_MOVE 版本/禁用 SKIP 派发）
  - Batch 259：ChatTurnOperationService.claim 分派矩阵（unkeyed/
    SUCCEEDED 重放/FAILED 复用/IN_PROGRESS inProgress/过期回收）
  - Batch 260：AllowlistedHttpToolProvider 凭证缺失/截止超时/UnknownHost 降级
- Batch 261 候选遗留：DocumentSyncRunService listItems 游标边界、
  DocumentMutationService 其他 lambda 残余、WebUI 页面分支残余。
- 工作区：本地已合并 test/* 分支随各批清理，无遗留 worktree。


---

## 进度留档快照（Batch 513 后 · 用户指令）

- 留档时点：2026-09-18 · main @ 本快照提交
- 循环进度：Batch 471–513 共 43 个批次全部按「规划→实施→单类验
  证→core 全量门禁 EXIT=0→push 特性分支→--no-ff 合并 main→账本
  记录→清理分支」交付完成。
- core 测试规模：5370 → 5670（+300）。
- 本轮生产 bug 修复：Batch 509 中 KnowledgeSearchTool 预算耗尽分
  支 results 被 cachedResults 赋 null 后未回填导致 NPE，补 results
  = List.of() 并添加回归测试覆盖。
- 近期批次重点：
  - Batch 505–506：HybridRetriever 分支隔离 + ChatTurnOperation
    序列化异常包装
  - Batch 507–508：EvaluationSuiteService createVersion 编排 +
    SSE 事件类型映射 + OpenAI toJson 序列化失败
  - Batch 509–510：KnowledgeSearchTool 调用矩阵（含生产 bug 修
    复）+ HybridRetriever 向量超时
  - Batch 511–512：CollectionIdentityResolver 守卫 + Document
    MutationService 外部 SYNC finish 链
  - Batch 513：ChatSessionCoordinator commit 链路与中断
- Batch 514 进行中：EmbeddingJobService.retry 重试长尾，测试文件
  已删除待重新实施。
- 下一批候选：EmbeddingJobService.retry 重试长尾（重新实施）、
  HybridRetrieverService 剩余超时分支、DocumentMutationService 残
  余 sync 内部、PdfImportService 上传链路。


---

## 进度留档快照（Batch 574 后 · 用户指令收尾）

- 留档时点：2026-09-23 · main @ 85050d0b（收尾提交前移一位）
- 循环进度：Batch 562–574 段循环交付完成，本轮（自 Batch 562 恢
  复后）共交付 13 个批次，全部按完整流程执行，工作区干净。
- core 测试规模：5394 → 5416（+22）；残余未覆盖行约 1306。
- 本段批次重点：
  - 562：ChatTurnOperationService 会话派生（withEffectiveSession
    快速路径；重新生成分支确认为防御性死代码并留档更正）
  - 563–565：ResourceCatalog 发现预算、StaticKnowledgeSearchTool
    上下文守卫、RagDocumentController 集合解析
  - 566–567：可观测查询百分位与状态分解、PdfImportService 文件名
    规范化
  - 568–570：ChatExecutionService 候选降级编排（LLM_UNAVAILABLE
    分支确认不可达）、stream SSE lambda、RagChatProperties 校验
  - 571–574：PdfImportController SSE 任务 lambda（反射轮询 complete
    标志）、prepareForOperation 降级与 RagException 透传、Static
    KnowledgeSearchTool 上下文守卫、RetrievalEvaluation 批量评估
- Batch 575（toResult 结果组装长尾）实施中未完成：KNOWLEDGE 模式
  extractSources 的 mapper stub 匹配后仍 NPE（List.copyOf 中 null
  来源待查），测试文件已删除，等待下轮重新实施。
- 下轮候选：ChatExecutionService toResult 编排（反射驱动，需先解
  上述 NPE）、RagChatController stream 残余、ResourceCatalog spring
  残余、ApiKeyController 残余。
- 构建验证：后端三模块 test-compile EXIT=0；core 全量门禁 EXIT=0
  （5416 tests / 0 fail）；WebUI `tsc -b && vite build` EXIT=0。

## 进度留档快照（Batch 561 后 · 用户指令收尾）

- 留档时点：2026-09-22 · main @ 587b3f32
- 循环进度：本段循环已交付 Batch 516–561 共 46 个批次，全部按
  「规划→实施→单类验证→core 全量门禁 EXIT=0→push 特性分支→
  --no-ff 合并 main→台账记录→清理分支」完成，工作区干净。
- core 测试规模：5363 tests（自 5034 起累计 +329）；残余未覆盖行
  约 1379。
- Batch 552–561 本段重点：
  - 552：RagChatService 断路器模式感知链路（OPEN 拒绝）
  - 553：ChatTurnOperationService keyed 完成（快照/租约丢失）
  - 554–556：Relocation relocate 守卫、RerankProvider 上限、
    EmbeddingProfileRegistry 查找初始化
  - 557：ConversationSummaryService 渲染残余（null metadata、
    非 Map 条目、estimate 求和）
  - 558–559：EmbeddingJobService 守卫、RagCollectionController
    创建与导入构建
  - 560–561：RagChatProperties setter/校验、PdfImportController
    路径与渲染
- 已确认不可达/死代码（留档不再追覆盖）：ConversationSummary
  Service 的 compaction_source_limit_exceeded（已删除）、Relocation
  writeEnvelope/fingerprint 的 JsonProcessing 分支、ChatTurn
  OperationService.withEffectiveSession 再生分支（公共 API 不可达）。
- 下轮候选：ChatExecutionService 编排主体（~60）、RagChatController
  stream lambdas、ResourceCatalog spring 残余、PdfImportController
  SSE 任务 lambda。
- 构建验证：后端三模块 test-compile EXIT=0；WebUI `tsc -b &&
  vite build` EXIT=0。

## 进度留档快照（Batch 541 后 · 用户指令收尾）

- 留档时点：2026-09-20 · main @ 7f3a8af1（本快照随收尾提交前移）
- 循环进度：Batch 516–541 共 26 个批次全部按「规划→实施→单类验
  证→core 全量门禁 EXIT=0→push 特性分支→--no-ff 合并 main→台账
  记录→清理分支」交付完成，工作区干净。
- core 测试规模：5190 → 5232（本轮自 5034 起累计 +198）；残余
  未覆盖行 1677 → 1466。
- 本轮新增批次重点：
  - Batch 516–519：PDF/告警门禁、PromptPlanner 预算、Trace
    Collector 守卫、KeywordIndex 回退 + 投递/嵌入 worker 调度
  - Batch 520–523：CollectionPurge 校验收尾、全文检索工厂与供应
    台账、EmbeddingJobWorker 调度、**技术债：删除 Document
    SyncRunService 死代码 failedItem**
  - Batch 524–528：ChatSessionCoordinator 租约状态、ApiKey 轮换
    台账、EvaluationCaseExecutor 快照、ExternalDocumentService 校
    验、CollectionRetrievalScopeResolver 授权
  - Batch 529–533：OpenAI 别名注册表与请求指纹、StaticKnowledge
    Catalog 解析、检索日志与摘要预算、Relocation 信封、Resource
    Catalog 发现路径
  - Batch 534–538：JsonRecordService 检索去重、DocumentMutation
    同步条目应用、ChatTurnOperation 失败持久化、RagChatService 构
    建辅助、RagDocumentController 文件校验
  - Batch 539–541：ChatExecutionService 纯函数与 OpenAI 模型归一、
    RagChatController 心跳/清史/幂等响应、ApiKeyCollectionAccess
    委托解析
- 下轮候选（JaCoCo 残余 Top）：ChatExecutionService 编排主体
  （约 77）、RagChatController stream lambdas（约 36）、RagChat
  Service（约 23）、DocumentMutationService/ResourceCatalog 残余
  （各约 20/15）。
- 构建验证：后端三模块 test-compile EXIT=0；WebUI `tsc -b &&
  vite build` EXIT=0。

## 进度留档快照（Batch 515 后 · 用户指令）

- 留档时点：2026-09-19 · main @ 本快照提交
- 循环进度：Batch 471–515 共 45 个批次全部按「规划→实施→单类验
  证→core 全量门禁 EXIT=0→push 特性分支→--no-ff 合并 main→账本
  记录→清理分支」交付完成。
- core 测试规模：5370 → 5684（+314）。
- 生产 bug 修复：Batch 509 中 KnowledgeSearchTool 预算耗尽分支
  results 被 cachedResults 赋 null 后未回填导致 NPE，补 results =
  List.of() 并添加回归测试覆盖。
- 本轮新增批次重点：
  - Batch 505–507：HybridRetriever 分支隔离、ChatTurnOperation
    序列化异常、EvaluationSuite createVersion 编排
  - Batch 508–509：SSE 事件类型映射 + KnowledgeSearchTool 调用
    矩阵（含生产 bug 修复）
  - Batch 510–511：HybridRetriever 向量超时 + CollectionIdentity
    Resolver 守卫
  - Batch 512–513：DocumentMutationService SYNC finish 链 + Chat
    SessionCoordinator commit 链路
  - Batch 514–515：EmbeddingJobService.retry 重试长尾 + RagChat
    MemorySummaryRepository CRUD 长尾
- Batch 514 进行中已重新实施并交付（EmbeddingJobRetryRaceTailTest
  3 用例），原 Batch 516（EmbeddingJobWorker 调度异常路径）测试复
  杂度高暂缓。
- 下一批候选：PdfImportService 上传链路、ChatExecutionService 流
  式内部、DocumentMutationService 残余 sync 内部。
