# 下一批实施规划：LLM 预算预留/硬限制与统一数据治理

> 状态：规划已完成并已实施；规划检查与实施前置门禁均已通过。实施结果和最后收尾证据见
> [LLM_BUDGET_AND_DATA_GOVERNANCE_PROGRESS.md](LLM_BUDGET_AND_DATA_GOVERNANCE_PROGRESS.md)。
> 本规划面向外部 Client API 服务形态；WebUI 只作为辅助验证，不作为本批完成依据。

## 1. 执行结论

本批一次性实施两项互相关联、但可独立回滚的生产能力：

1. **LLM Budget Reservation + Hard Limit**：在当前请求级调用次数/上下文预算和 V53
   事后用量账本之上，增加按稳定 API principal 维度的跨实例 LLM token/成本预算预留、结算、
   过期回收和超限拒绝。默认启用，不改变既有 endpoint、请求 body、SSE event 或 OpenAI
   标准 body。
2. **统一数据保留、删除与隐私治理**：把当前分散的 audit、client error、evaluation、
   chat history、retrieval diagnostics、LLM usage、integration observability 清理策略
   收敛到一个有界协调器，并增加新的 preview/apply 数据擦除 API。审计日志和 Collection
   purge 的安全语义保持独立，不把不可变审计轨迹误当作可删除业务内容。

本批的核心判断：现有用量账本是 fail-open 的“发生后记录”，不能作为 hard limit 的依据；
现有治理 policy 已经投影多个类别，但自动清理仍由多个 scheduler 分散负责，且没有统一的
调用方可执行擦除凭据。因此必须新增持久化 reservation/erasure 状态，而不能只增加配置项或
在内存中计数。

## 2. 当前基线与已核对事实

### 2.1 外部 Client 和 API 兼容基线

- 当前分支是 `main`，最近提交为 Client Consumer Contract Kit；工作区在规划开始时干净。
- `docs/client-contract-kit-zh-CN.md` 和 `testdata/api-contract/consumer/v1/` 已覆盖 Native
  JSON/SSE、OpenAI JSON/SSE、OpenAPI additive-only、错误/Trace/Retry 和旧 Client fixture。
- 预算、聊天记录、检索诊断和用量账本使用的 canonical owner 是 `ChatPrincipal.id()`：环境根为
  `root:environment-root`，数据库 API principal 为 `db:<principalId>`，legacy static 为
  `legacy:static`，未启用鉴权为 `local:auth-disabled`。它与 `rag_api_principal.principal_id`
  的数据库 key（没有 `db:` 前缀）不是同一个字符串；本批不得混用两种身份。
- 对外服务的稳定边界是 HTTP endpoint、既有请求字段、既有响应字段、SSE event、OpenAI 标准
  body；新增字段只能是可忽略的增量，新增 endpoint 不影响旧 Client。
- OpenAI 兼容输出不得泄漏 Native 专有的 budget、governance、trace 或 evidence 私有字段。

### 2.2 当前 LLM 调用预算与用量

- `ChatExecutionBudget` 已限制一次 Chat 的 model call、tool round、tool call、结果字符数和
  deadline；它是单请求内存状态，不是跨请求或跨实例额度。
- `BudgetedChatModel` 包装同步和流式模型调用，在调用前消耗请求级 model-call budget，调用后
  生成 `LlmUsageEvent`；其 `ChatExecutionBudget.attribution(...)` 已带有 principal、session、
  logical execution、ordinal 和 trace 归因，可作为 durable reservation 的幂等键来源。
- V53 `rag_llm_usage_event` 是 append-only 终态事实；`LlmUsageRecorder` 明确 fail-open，
  记录超时或失败不会阻断原始模型响应。因此它不能直接承担 hard limit、预留或结算职责。
- 当前 `rag_api_rate_limit_bucket` 只按稳定 principal 做 HTTP 请求/分钟计数；不能代表 token、
  cost 或一次请求内的多次模型 invocation。
- `FailureClass` 已按 HTTP 429 归类为 `RATE_LIMITED`；`ErrorCode` 已有 429 `TOO_MANY_REQUESTS`
  和 422 的请求级 Chat budget 错误，但没有持久化 LLM account budget 的专用错误码。

### 2.3 当前数据保留与删除

- `RagDataGovernanceProperties` 目前集中配置 audit log、client error、retrieval evaluation
  三类清理；`DataGovernanceCleanupService` 按类别独立事务和有界批次执行。
- LLM usage、retrieval diagnostics、chat history、rate-limit bucket 等仍有自己的 retention
  job 或维护类；`GET /api/v1/rag/data-governance/policy` 已投影多个类别，但部分类别的
  `automaticCleanup` 仍由独立任务表达，调用方无法把它当成一个统一执行计划。
- 已有 chat history export/clear、Collection purge preview/apply 和 audit log 查询；本批不
  重定义这些既有 endpoint，也不删除审计记录。Collection purge 继续负责知识库内容及其
  引用关系的受保护清理。
- `rag_retrieval_logs`、`rag_llm_usage_event`、`rag_chat_history`、受管 evaluation
  suite/run、client error 和 API operation rollup 已具备 owner/session/trace 或时间索引，
  可以在不读取 prompt/answer 的前提下做有界 preview/apply；但 `rag_client_error` 的历史
  行目前只有 session/user 字段，不能假造 principal 归属，V64 只为新写入补充 nullable
  owner/trace 字段，历史无主行仅参加 retention，不参加普通 principal erasure。

## 3. 目标、非目标与冻结决策

### 3.1 目标

#### A. LLM Budget Reservation + Hard Limit

1. 对每次真实 model invocation 在 provider 调用前进行 durable reservation；调用结束后按已知
   usage 结算，未知 usage 以保守估算结算，过期 reservation 可被有界回收。
2. 按 stable principal 的日/月窗口同时保护 token；cost limit 只有在 provider usage 和
   pricing 都可计算时才参与强制判断，不能把未知 cost 当作零。
3. 跨线程、跨实例、重试、流式取消、provider 异常和进程崩溃都不允许把同一 reservation
   重复计算或永久占用额度。
4. 超限在调用 provider 前拒绝，返回新的机器可读错误；预算存储不可用时也不调用 provider，
   避免 hard limit 在故障时悄悄退化成无限制。
5. 把预算能力加入既有 integration capabilities 和 Native 诊断投影；OpenAI body 仍保持
   标准字段，Budget 私有信息只进 header/Native 增量字段/错误 envelope。

#### B. 统一数据保留、删除与隐私治理

1. 用一个 central coordinator 调度外部 Client 可感知的数据类别，保留每类独立事务、固定
   batch、短 statement timeout 和失败隔离。
2. 保留既有环境变量和 retention 默认值；新增统一类别清单、cleanup 状态/指标和能力发现，
   不要求运维手工重写配置或迁移数据。
3. 增加 session/principal/trace 范围的 preview/apply erasure API，支持权限校验、短期
   preview、一次性确认、幂等重放、CAS 状态推进和结果回执。
4. 删除顺序先处理业务内容和派生/诊断数据，再处理用量与 evaluation 派生数据；审计日志
   保留并只通过 retention 清理，Collection 内容仍必须走既有 Collection purge。
5. 擦除执行不返回 prompt、answer、document text、token、secret 或完整错误正文，只返回
   类别、计数、状态、trace/operation id 和失败的稳定错误码。

### 3.2 非目标

- 不实现 OAuth/OIDC、租户层级、billing 发票或供应商账单对账；本批只做服务内预算保护和
  数据治理。
- 不把 `rag_llm_usage_event` 改造成可更新账本，也不把 best-effort usage recording 改成
  provider 调用的同步依赖。
- 不替换现有 HTTP rate limit、ChatExecutionBudget、Collection purge、Chat clear/export 或
  OpenAI compatibility；不删除 legacy endpoint。
- 不在本批新增 Parser SPI、多 Embedding Profile 路由或 `EACH_COLLECTION` 召回模式。
- 不把 WebUI 的页面可用性作为核心验收；如果没有 UI 行为变化，WebUI 只执行现有构建/测试门禁。
- 不在本批新增预算策略管理 endpoint；策略初始值来自配置和 V64 自动物化，后续管理 API
  另行设计，避免把本批扩展成 billing/租户治理产品。

### 3.3 冻结默认值与可逆边界

| 项目 | 推荐默认 | 理由与边界 |
|---|---|---|
| 预算 enforcement | `true` | 新特性默认启用；普通调用只受高位安全上限影响，异常消耗才被阻断 |
| 预算粒度 | stable API principal + UTC day/month | 与现有认证/quota 归属一致，跨实例可判定，不依赖 IP 或 session |
| token limit | 默认日 `5,000,000`、月 `50,000,000`、单次 `200,000` token | 对正常 RAG 流量留出余量，同时保持真正有限；均可由 `rag.llm-budget.defaults.*` 覆盖 |
| cost limit | 默认只在 pricing/usage 完整时强制 | 未知 cost 不当作零；token hard limit 始终是安全底线 |
| reservation TTL | 默认 `900` 秒，并取不超过请求 deadline 的正值 | 防止崩溃遗留 reservation 永久占额度；清理使用 CAS，不用锁 |
| budget service 故障 | 新 invocation 返回 `503`，不调用 provider | hard limit 不能在不可判定时静默放行；不影响已经返回的模型结果 |
| central cleanup | 默认启用；旧 retention 配置继续生效 | 新 coordinator 接管调度，旧 job 变为兼容委托/不重复执行 |
| erasure | 新 API 默认可用，普通 principal 只能擦除自身范围 | preview/apply 有权限与确认边界；既有清理 endpoint 保持原行为 |
| migration | 新增 V64；为已有 managed principal 以 `db:` canonical 前缀创建默认 policy，其他身份首次 reserve 时幂等物化 | 旧数据不需要人工迁移；旧代码忽略新表，部署前先执行 Flyway |

## 4. 目标设计

### 4.1 Budget policy、window 与 reservation

新增 V64 迁移，包含三类预算表和一类擦除操作表。预算表中的 `principal_id` 一律保存
canonical `ChatPrincipal.id()`，长度为 128，不对 `rag_api_principal` 建外键：

1. `rag_llm_budget_policy`
   - `principal_id` 主键、`enabled`、`policy_version`、`policy_source`、`defaults_fingerprint`；
   - `daily_token_limit`、`monthly_token_limit`、可选 daily/monthly cost limit；
   - `max_request_tokens`、可选 `max_request_cost`、`reservation_ttl_seconds`、时间戳；
   - 为已有 `rag_api_principal` 以 `db:` 前缀自动插入默认 policy；环境根、legacy static 和
     auth-disabled 使用同一组配置默认值，在第一次 reserve 时通过幂等 upsert 物化；不保存
     secret、prompt 或 answer。
   - 本批所有 policy 都标记为 `CONFIG_DEFAULT`，应用启动和首次 reserve 都用当前
     `rag.llm-budget.defaults.*` 计算 fingerprint，并通过版本条件写入自动收敛已存在的默认
     行；不需要运维手工 UPDATE。未来若增加人工 policy 管理，只有显式非默认来源才能阻止
     该 reconcile，本批不实现该管理入口。
2. `rag_llm_budget_window`
   - `(principal_id, window_kind, window_start)` 唯一键；
   - `reserved_tokens/cost`、`settled_tokens/cost`、版本号和更新时间；
   - reservation 时使用带额度条件的 `UPDATE ... WHERE` 或唯一键 upsert/CAS，不能使用
     `FOR UPDATE`、`SKIP LOCKED` 或 advisory lock。
3. `rag_llm_budget_reservation`
   - UUID reservation、principal、logical execution、call ordinal、估算/实际 token/cost；
   - `RESERVED/SETTLED/RELEASED/EXPIRED` 状态、expires/finalized 时间；
   - `(logical_execution_id, call_ordinal)` 唯一键保证重试不重复预留；状态推进带版本/状态条件。

`BudgetReservationService` 作为新抽象，不复用 `LlmUsageRecorder`：

- `reserve(request)` 在 `BudgetedChatModel.call/stream` 调用 delegate 之前执行；估算包含已有
  prompt token estimator、tool schema、output reserve 和 model cost snapshot。
- `settle(reservation, actualUsage/outcome)` 在同步返回或流式 `doFinally` 中执行；已知 usage
  不超过预留时释放差额，未知 usage 使用保守预留结算。结算失败不修改已取得的模型结果，但
  reservation 保留到 TTL，由恢复任务处理，下一次 reserve 继续 fail closed。
- `release/cancel/expire` 只能通过 reservation 状态条件和 window CAS 生效；重复调用返回同一
  终态，不重复扣减或释放。
- 预算服务不可用、额度不足和 policy 不可解析必须区分为不同内部原因，但对外只暴露稳定
  `LLM_BUDGET_EXCEEDED` 或 `LLM_BUDGET_SERVICE_UNAVAILABLE`，不返回 SQL/配置细节。
- 估算无法得到 provider usage 时，预留量视为本次最终扣减量；有真实 usage 时按真实量结算并
 释放差额。任何路径都不得用“未知 = 0”绕过 hard limit。

### 4.2 BudgetedChatModel 接入边界

- 保留现有所有构造器和测试用的 NOOP 行为；新增构造器参数使用重载，不破坏外部扩展。
- `ModeAwareChatClientFactory`、`ConversationSummaryService` 和其他创建 `BudgetedChatModel`
  的路径注入同一个 reservation service，保证 Chat、query transform/expand、summary、
  fallback 和 Agent 轮次都经过相同的预留协议。
- 现有 `ChatExecutionBudget.reserveModelCall()` 仍先保护单请求模型次数；durable reservation
  通过后才调用 provider。二者任何一个拒绝都不产生 provider 请求。
- Native 成功响应只增加可选 `budget`/usage diagnostics；OpenAI JSON/SSE 不增加私有 body
  字段。失败响应沿用既有 RFC 7807/Native error envelope，并通过已有 `FailureClass.RATE_LIMITED`
  分类。
- capabilities 增加可选 budget contract：是否 enforced、支持的窗口、token/cost 是否可用、
  超限错误和 headers；旧 Client 忽略新增字段。
- 生产 Spring wiring 必须提供 `ModeAwareChatClientFactory` 与 `BudgetReservationService`；
  `RagChatService` 的旧构造器和 `usageClientFactory == null` raw-model 分支仅允许测试/手工
  兼容夹具使用，生产启动时不能静默退回未保护的 provider 调用。预算 enforcement 为 `true`
  且缺少必需 bean 时，启动校验直接失败，不降低安全等级。

### 4.3 统一 retention coordinator

把现有类别登记成稳定 machine-readable category：

`AUDIT_LOG`、`CLIENT_ERROR`、`RETRIEVAL_EVALUATION`、`CHAT_HISTORY`、
`RETRIEVAL_DIAGNOSTICS`、`LLM_USAGE`、`INTEGRATION_OBSERVABILITY`、
`BUDGET_RESERVATION`。

- `DataGovernanceCleanupService` 负责调度和 summary；每个类别通过 adapter 调用现有 repository
  或 retention job 的批量删除方法。
- central coordinator 是唯一默认 scheduled entrypoint；`LlmUsageRetentionJob`、
  `RetrievalDiagnosticsRetentionJob`、`ChatHistoryCleanupService` 的公开 cleanup 方法保留给
  测试/兼容调用，但其默认 `@Scheduled` 触发必须关闭或改为委托，并由测试证明不会重复删除。
- 每个类别记录删除批次数、删除行数、失败类别和 last-run 时间；只使用低基数 metric，
  不把 principal、session、trace 放入 metric label。
- audit log 继续只按 retention 清理；API key provisioning、idempotency、notification
  delivery、rate-limit bucket 等有专门安全生命周期的内部表继续由各自 job 管理，但在 policy
  中标记为 `INTERNAL_OPERATIONAL`，不允许普通 Client 通过 erasure API 直接删除。

保留期必须复用现有配置，不另造隐藏默认：`AUDIT_LOG=400` 天、`CLIENT_ERROR=30` 天、
`RETRIEVAL_EVALUATION=400` 天、`CHAT_HISTORY=rag.memory.message-ttl-days`（默认 30，0
表示不过期）、`RETRIEVAL_DIAGNOSTICS=7` 天、`LLM_USAGE=rag.usage.retention-days`（默认
400）、`INTEGRATION_OBSERVABILITY=90` 天、`BUDGET_RESERVATION=30` 天。统一 policy 只是
投影这些现有配置，不能覆盖旧环境变量的语义。

### 4.4 Erasure preview/apply API

新增 endpoint，不改变已有 API：

- `POST /api/v1/rag/data-governance/erasures/preview`
- `GET /api/v1/rag/data-governance/erasures/{operationId}`
- `POST /api/v1/rag/data-governance/erasures/{operationId}/apply`

请求范围只允许一个 principal 绑定的有限组合。请求 DTO 冻结为
`DataErasurePreviewRequest(targetPrincipalId?, sessionId?, traceId?)`：

- 普通 principal：只能自身 `principalId`，可进一步指定 `sessionId` 或 `traceId`；客户端不能
  指定其他 principal。
- ADMIN/root：可指定一个目标 principal，再指定 session/trace；不开放任意 SQL/filter。
- 至少提供 principal、session 或 trace 中一个有效范围；大小、字符集和过期时间有限制。

普通 principal 未提供 `targetPrincipalId` 时，服务端将认证 principal 作为唯一 target；提交
自己的 canonical id 也必须与认证 principal 完全相等，否则返回 403。ADMIN/root 可省略 target
表示自身，也可指定一个目标 principal。空的 target/session/trace 组合代表 target principal
全范围擦除，必须先显式创建 preview，不允许直接 apply。

preview 返回 200 和 `operationId`、`status=PREVIEWED`、按 category 的低敏计数、
`confirmationToken`、`fingerprint`、`previewExpiresAt`、`operationExpiresAt`；不返回行内容。
apply 请求 DTO 冻结为 `DataErasureApplyRequest(operationId, confirmationToken, fingerprint)`，
沿用 Collection purge 的 200 同步 apply 语义；重复 apply 返回同一终态结果，处理中或部分失败
由 GET status 和同一 confirmation 重试继续推进。GET status 返回 200 和 operation 状态、已完成
类别/计数、未完成类别、低敏稳定错误码。确认 token/fingerprint 过期或不匹配分别返回新增
`DATA_ERASURE_PREVIEW_EXPIRED` / `DATA_ERASURE_CONFIRMATION_INVALID`（409）。preview 默认
15 分钟有效，operation 默认 1 小时有效；session 最长 36 字符、trace 最长 128 字符，所有
字符串只允许可打印 ASCII。

新增 V64 的 `rag_data_erasure_operation` 保存 operation id、owner/target principal、范围摘要、
preview counts、状态、确认 hash、expires、版本号和结果摘要。执行顺序：

1. owner 可识别的 Chat history 及其派生 source-reference；
2. retrieval diagnostics；
3. LLM usage events；
4. owner-scoped evaluation suites/runs/results；
5. client errors；
6. principal-scoped integration operation rollups；
7. budget reservations/windows（只删除终态/过期内部记录，不影响活动 reservation）。

没有 owner 的历史 chat/client-error 行不由普通 principal erasure 猜测归属；它们只按 retention
删除，ADMIN/root 也只能通过明确的内部维护路径处理。`rag_client_error` 的 V64 新字段为
`owner_principal_id`、`request_trace_id`，由新写入链路填充；旧行保持 NULL。每一步都用固定
batch、CAS/版本和独立事务；已完成步骤不回滚，重试只继续未完成步骤。审计
日志保留并写入一条不含原始数据的“erasure requested/completed/failed”审计事件。Collection
内容必须由现有 purge API 处理，erasure API 不绕过 Collection fence。

### 4.5 兼容和错误契约

- 不删除/改名现有 endpoint、method、request required field、response old field、SSE event 或
  OpenAI body。
- 新 error code 只增量加入 API enum、OpenAPI、consumer fixture 和 failure mapping；已有
  422 `CHAT_BUDGET_EXHAUSTED` 继续表示单请求 budget，不重定义为 account quota。
- `LLM_BUDGET_EXCEEDED` 对外返回 429，并由全局异常处理器增加 `Retry-After` 秒数（取下一
  个受影响 UTC 日/月窗口的最早恢复时间，至少为 1）；
  `LLM_BUDGET_SERVICE_UNAVAILABLE` 返回 503。错误 body 不含剩余额度的敏感内部明细，只含
  有界 window/status 摘要。
- Data Governance policy 增量加入统一 category、erasure capability、coordinator version
  和 operation endpoints；旧 Client 只读取原有字段仍可工作。

## 5. 文件级实施顺序

### Slice A：规划、契约和迁移

1. 完成本文与 progress 的规划检查 `3/3`。
2. 新增 API DTO、错误码、OpenAPI/consumer fixture 的增量契约；不改旧 fixture 语义。
3. 新增 V64 budget policy/window/reservation、erasure operation 表及 client-error owner/trace
   可空列；以 canonical owner 回填 managed principal，编写 migration/backfill 与 Flyway
   PostgreSQL 验证，并覆盖 legacy/local/root 身份和历史 NULL owner 行。

### Slice B：Budget Reservation + Hard Limit

1. 新增 properties、validator、policy/window/reservation entity/repository/service。
2. 以 CAS/唯一键实现 reserve、settle、release、expire 和可恢复清理。
3. 接入 `BudgetedChatModel` 所有生产创建路径；保留兼容构造器和 NOOP 测试路径，但生产
   enforcement 开启时禁止 raw-model fallback。
4. 接入错误映射、响应 header/Native diagnostics、capability discovery 和 usage ledger
   reconciliation。

### Slice C：统一数据治理与 Erasure

1. 扩展 data governance properties/category registry 和 central coordinator。
2. 将 usage/diagnostics/chat history 清理接入 coordinator，关闭重复 scheduler 路径。
3. 新增 preview/status/apply DTO、controller、operation repository/service、权限和审计。
4. 为 session/principal/trace/category 增加有界批量删除与 PostgreSQL integration tests。

### Slice D：文档、Client 验收与全面回归

1. 更新双语 `configuration`、`rest-api`、`architecture`、`testing-guide`、`developer-reference`
   和 `project-context`；同步 integration capabilities 和 Client Contract Kit。
2. 新增真实外部 Client 黑盒覆盖：预算超限/服务不可用、erasure preview/apply/status、权限、
   幂等重放、旧 endpoint 回归、Native/OpenAI JSON/SSE 兼容。
3. 运行 Maven/PostgreSQL/Flyway、项目 gate、文档 gate、release gate、无悲观锁 gate、
   `scripts/dev.sh`；在 `.env` 允许时做有界真实 LLM smoke，不能把 WebUI 通过替代 API 证据。
4. 实施检查连续 `3/3` 后，把稳定事实同步到长青文档并归档 plan/progress。

## 6. 验收矩阵

| 层级 | 必须验证 | 结果要求 |
|---|---|---|
| API/DTO | 新增 error、policy、capability、erasure DTO；旧 JSON 反序列化 | 增量且类型稳定 |
| Budget 单元 | token 估算、cost 不可用、同步/流式、取消、provider error、重复 settle | 0 重复扣减/释放 |
| Budget 并发 | 两线程、两实例、窗口边界、policy version、reservation expiry | PostgreSQL CAS 一致 |
| Budget HTTP | 429/503、Retry-After、FailureClass、trace、安全错误摘要 | 不调用 provider 后拒绝 |
| Budget Chat | Chat/query transform/expand/summary/fallback/Agent | 所有生产 model path 受保护 |
| Erasure | preview、权限、确认过期、幂等 apply、部分失败重试、终态查询 | 有界、可恢复、无原文泄漏 |
| Retention | 中央调度、旧 job 不重复、每类批量/失败隔离、指标 | 不阻塞在线 API |
| Migration | 全新库、已有 V63 库、已有 principal、空表/脏边界 | Flyway 可重复验收，无人工修复 |
| Client compatibility | Native JSON/SSE、OpenAI JSON/SSE、旧请求、OpenAPI additive-only | 旧 Client 继续可用 |
| Runtime | `scripts/dev.sh`、PostgreSQL、停止清理、无残留端口 | 可启动、可停止 |
| Real provider | 有界真实模型调用和预算拒绝后的不调用证明 | 真实 API 证据，非 mock |
| Repository gates | `verify-project-tests.sh`、`verify-project-docs.sh`、`verify-release.sh`、`verify-client-consumer-contract.sh`、`verify-no-pessimistic-locks.sh` | 全部通过；任何未执行都明确记录为限制 |

## 7. 安全、并发、成本和回滚

- 所有 budget 写入只保存 token/cost 摘要和稳定标识，不保存 prompt、answer、tool payload、
  secret 或 provider exception body。
- 不使用显式悲观锁、`SKIP LOCKED`、advisory lock；所有状态通过条件写入、版本号、唯一约束、
  lease 和有界重试收敛。
- 预算 hard limit 在 provider 调用前执行；结算故障不能伪造“已成功释放”，恢复任务必须能
  依据 reservation 状态和 TTL 收敛。
- erasure 的 preview 不返回原文；apply 使用确认 hash 和 operation id，重复请求返回同一结果。
- V64 先迁移再滚动部署新代码；旧节点忽略新增表。出现 budget 误配置时可显式关闭新 enforcement
  作为应急开关，但不能把 legacy 计数当作默认实现。
- 本批无删除既有数据的强制迁移；新增默认 policy 是可回滚的配置/状态初始化，已有业务数据只在
  retention 到期或 Client 明确 apply erasure 时删除。

本批最终命令序列固定为：

```bash
mvn test
./scripts/verify-project-tests.sh
./scripts/verify-client-consumer-contract.sh
./scripts/verify-no-pessimistic-locks.sh
./scripts/verify-project-docs.sh
./scripts/verify-release.sh
SPRING_PROFILES_ACTIVE=postgresql RAG_DEV_OPEN_BROWSER=false ./scripts/dev.sh
./scripts/dev.sh --status
./scripts/dev.sh --stop
# 仅在 .env 凭据和真实服务条件满足时：
./scripts/start-real-e2e-server.sh
BASE_URL=http://127.0.0.1:18081 ./scripts/real-llm-e2e-smoke.sh
lsof -ti :18081 | xargs -r kill
```

其中真实数据库迁移矩阵必须在 PostgreSQL 上执行，不以 H2 或纯 mock 替代；`dev.sh --status`
必须显示受管进程，`--stop` 后端口与 pid/state 文件必须清理；真实 E2E 服务必须在 smoke
结束后停止。真实 LLM 不可用时只记录环境性 skip，不得把 mock 结果写成通过。

## 8. 规划检查与实施检查

### 8.1 规划检查

实施前连续三轮只读检查：

1. 需求闭环、默认启用、API 兼容、外部 Client 价值和非目标；
2. 代码、Flyway、并发、权限、错误契约和现有 job 交叉验证；
3. 测试矩阵、真实 provider、发布、回滚、成本、恢复和中断续作。

发现实质问题立即修正文档并将计数重置为 `0`；无问题轮次不修改规划正文，连续 `3/3` 后才开始
代码实施。

### 8.2 实施检查

实现基本硬门槛通过后，再连续三轮只读检查：

1. reservation/CAS/settlement/expiry 和所有 model path；
2. erasure/retention/ACL/隐私/API 兼容；
3. 全量测试、真实 Client、启动、发布门禁、Git 交付。

任一实质问题修复都将实施计数重置为 `0`，修复后重新开始。

## 9. 完成定义

只有以下条件全部满足才算完成：

1. 本规划和 progress 自包含，规划检查连续 `3/3`；
2. V64 migration、budget reservation/hard limit、统一 coordinator、erasure API 和测试存在；
3. 外部 Client API 兼容性、Native/OpenAI JSON/SSE、错误/权限/幂等/恢复均有自动验收；
4. Maven、PostgreSQL/Flyway、项目测试/文档/release gate 和 `scripts/dev.sh` 通过；
5. 必要的真实 LLM/HTTP 黑盒验收通过，或明确记录环境性 skip；
6. 实施检查连续 `3/3`，双语长青文档已同步；
7. Git commit、push 后 `main` 与 `origin/main` 同步且工作区干净。

## 10. 恢复入口

中断后先阅读本文和对应 progress，再执行：

```bash
git status --short --branch
git diff --check
./scripts/verify-client-consumer-contract.sh
```

确认当前 migration、`BudgetedChatModel`、`DataGovernanceCleanupService`、Client Contract Kit
和 API 文档状态后，从 progress 中的下一个未完成 Slice 继续；禁止使用 `git reset --hard`、
`git stash` 或 `git clean`。
