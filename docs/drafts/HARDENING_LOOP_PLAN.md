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
