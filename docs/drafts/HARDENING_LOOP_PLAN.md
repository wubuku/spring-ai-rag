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
