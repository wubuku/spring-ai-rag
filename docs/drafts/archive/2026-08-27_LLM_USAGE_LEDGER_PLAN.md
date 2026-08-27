# 模型调用级持久用量账本与成本可观测性实施规划

> **状态**：活动规划，规划审查进行中，尚未开始生产代码实施
>
> **规划日期**：2026-08-26
>
> **规划基线**：`main` / `origin/main` @ `7b6f01ad`；Spring Boot `3.5.16`；
> Spring AI `1.1.8`；Java `21`；Flyway `V1-V52`
>
> **规划工作区**：
> `/Users/yangjiefeng/.hermes/workspace/spring-ai-rag-main-delivery`
>
> 本文是当前任务的单语、自包含过程文档。它描述通用 RAG 服务对模型调用用量进行持久
> 观测的方案，不依赖任何外部项目名称、内部领域模型、私有协议或部署背景。实施完成后，
> 仍有效的稳定事实提升到双语长青文档，本文与进度账本归档。

## 1. 执行摘要

当前 Chat 已经有逻辑请求级预算、工具循环上限、Spring AI JDBC Memory、历史摘要、模型
候选链和进程内 Micrometer 指标，但没有一个能够跨重启、按认证 principal 和模型查询的
模型调用事实。

一次逻辑 Chat 可能包含以下多个独立模型调用：

```text
QUERY_TRANSFORM -> QUERY_EXPAND -> CHAT
                         |
                         +-> AGENT 的多个 CHAT/tool round
fallback candidate / application retry -> 额外 CHAT
post-commit memory compaction -> SUMMARY
```

当前公开 response 的 `usage` 只代表 Spring AI 最终 response；它不能代表上述调用总量。
本轮新增 V53 持久 invocation ledger，并把记录边界放在 `BudgetedChatModel`。同时把旧的
`RagChatService` 公开兼容入口也接入同一 recorder，避免“主链可观测、legacy/demo 不可观测”
的假完整性。

本轮的“全调用边界”限定为 Chat 公开执行面：mode-aware `ChatExecutionService`、OpenAI
兼容 Chat 入口以及 `RagChatService` 的公开 Chat/stream 方法。`ModelComparisonService`、
`SemanticEvaluationService`、`RetrievalEvaluationServiceImpl` 等独立评估/比较功能不属于
Chat execution ledger；它们的直接模型调用不在本轮承诺范围内，避免把不同业务操作错误归因
到一个 Chat logical execution。legacy Chat 中可选的 `QueryRewriteAdvisor` LLM 改写属于
Chat 执行的一部分，必须通过同一 execution context 记录；仅规则式改写不产生事件。

本轮交付：

1. 每次真正进入 `ChatModel.call` 或一次 `ChatModel.stream` subscription，最多写一条终态
   invocation event。
2. 记录 stable principal、session、logical execution、调用序号、canonical model ref、
   Chat mode、purpose、streaming、outcome、规范化 token、配置价格快照和配置成本估算。
3. 覆盖非流式成功/失败、流式成功/失败/取消、AGENT 多轮、query transform、query expand、
   summary、fallback 和应用 retry。
4. 提供 principal 自助或 root/ADMIN 管理查询的只读聚合 API。
5. 提供 bounded retention、写入失败的 fail-open 语义和低基数丢失指标。
6. 在 WebUI Metrics 页面增加轻量用量区域，明确区分持久用量与旧进程指标。

本轮不实施 token/cost hard limit、余额、账单、发票或支付。账本是应用侧可观测性，不是供应商
账单，也不是可以直接用于拒绝请求的强配额事实。

## 2. 规划基线与已核对事实

### 2.1 当前 Chat 调用拓扑

生产 mode-aware 路径是：

```text
RagChatController
  -> ChatCommandMapper
  -> ChatExecutionService
       -> ChatExecutionBudget
       -> ModeAwareChatClientFactory
            -> BudgetedChatModel
            -> MessageChatMemoryAdvisor
            -> KNOWLEDGE: RetrievalAugmentationAdvisor
                 -> HistoryAwareQueryTransformer
                 -> BoundedMultiQueryExpander
            -> AGENT: BudgetedToolCallAdvisor
       -> ChatSessionCoordinator
       -> history + JDBC Memory 原子提交
       -> ConversationSummaryService（提交后的 best-effort SUMMARY）
```

已确认的代码入口：

- `spring-ai-rag-core/src/main/java/com/springairag/core/chat/ChatExecutionService.java`
- `spring-ai-rag-core/src/main/java/com/springairag/core/chat/ModeAwareChatClientFactory.java`
- `spring-ai-rag-core/src/main/java/com/springairag/core/chat/BudgetedChatModel.java`
- `spring-ai-rag-core/src/main/java/com/springairag/core/chat/ChatExecutionBudget.java`
- `spring-ai-rag-core/src/main/java/com/springairag/core/chat/ConversationSummaryService.java`
- `spring-ai-rag-core/src/main/java/com/springairag/core/chat/ChatTurnOperationService.java`
- `spring-ai-rag-core/src/main/java/com/springairag/core/controller/RagChatController.java`
- `spring-ai-rag-core/src/main/java/com/springairag/core/controller/OpenAiCompatibilityController.java`
- `spring-ai-rag-core/src/main/java/com/springairag/core/advisor/QueryRewriteAdvisor.java`
- `spring-ai-rag-core/src/main/java/com/springairag/core/retrieval/QueryRewritingService.java`

### 2.2 legacy 兼容入口不能被遗漏

`RagChatService` 仍保留以下公开 Java API：

- `chat(String userMessage, String sessionId)`
- `chat(String userMessage, String sessionId, String domainId, Map metadata)`
- `chatStream(String userMessage, String sessionId)`
- `chatStream(String userMessage, String sessionId, String domainId)`

这些入口在没有 mode-aware execution wiring 的单测、demo 或兼容使用场景下会直接构建
`ChatClient` 并调用原始 `ChatModel`。因此本轮不能只给
`ModeAwareChatClientFactory` 加 recorder；legacy 路径必须按每次公开调用创建自己的
logical execution，并用相同的 `BudgetedChatModel`/recorder 边界包装每个 fallback candidate
和 streaming model。

legacy 路径没有来自 `ChatCommandMapper` 的请求预算，因此实施时必须为每次公开
`RagChatService.chat*` 调用创建一个独立的默认 `ChatExecutionBudget`，其 deadline、候选尝试、
模型调用和工具预算沿用 `RagChatProperties` 中已有的 Chat/Agent 上限；它只承担 invocation
归因和既有安全上限，不改变 legacy API 的响应契约。实现应把 legacy 候选解析提升为
`ChatModelRouter.ChatModelCandidate` 描述链，而不是只传裸 `ChatClient`，以便每个 fallback、
application retry 和 stream subscription 都获得 canonical model ref、能力和可用价格快照。
未能从 legacy provider bean 取得模型级价格时，必须沿用 `pricingAvailable=false`，不能用
provider 默认价或当前全局配置猜测。

legacy advisor 的 LLM query rewrite 也必须纳入同一公开 Chat 调用：构建每个 legacy
candidate 的 advisor 参数时，把当前 candidate 的 `ChatModel`、canonical/legacy model ref、
不可变价格快照和该次调用的 `ChatExecutionBudget` 放入 request context；`QueryRewriteAdvisor`
只从这个 execution-scoped context 读取 override，并调用 purpose 为 `QUERY_TRANSFORM` 的
`BudgetedChatModel`。它不得继续使用 singleton 默认 `ChatModel`，也不得从线程本地或可变全局
状态推断归因。没有该 context 的独立 `QueryRewritingService` 调用保持原兼容行为，但不在本轮
ledger 覆盖范围内。

legacy 非流式入口当前已有候选 fallback 和应用 retry，必须记录实际发生的每次调用；legacy
流式入口当前只解析一个候选且没有应用 retry，实施时只记录该实际 subscription，不借记新的
fallback/retry，也不借此改变既有 legacy 行为。任何扩大 legacy 流式 fallback 都属于另一轮
兼容性变更。

### 2.3 Spring AI `Usage` 边界

本地依赖为 Spring AI `1.1.8`：

- `Usage` 稳定提供 `promptTokens`、`completionTokens`、`totalTokens` 和 provider-specific
  `nativeUsage`；
- `EmptyUsage` 代表没有可识别 usage；
- `MessageAggregator` 不会把多个独立 model invocation 的 usage 进行逻辑请求级求和；
- Tool Calling advisor 的最终 response 不能反推出前面的工具循环调用；
- 本轮不持久化 `nativeUsage`、prompt、answer、工具参数、工具结果或异常正文。

公开 Chat response 和 SSE `done` 中的现有 usage 语义保持不变；新 ledger 另行表达全量
invocation 事实。

### 2.4 模型、身份与现有文档事实

- `ChatPrincipal` 提供稳定 ID：环境 root、database principal、legacy static 或
  auth-disabled。
- database credential rotation 不改变 stable principal。
- `ChatModelRouter.ChatModelCandidate` 已携带 canonical ref、capabilities、context
  window 和 max tokens；配置模型的 `MultiModelProperties.ModelItem` 已包含
  `ModelCost(input, output, cacheRead, cacheWrite)`。
- legacy provider bean 通常只有 provider alias，没有可靠的配置价格，必须明确记为
  `pricingAvailable=false`。
- 当前 `RagMetricsService` 的 token gauge 是进程内内存值，重启丢失，且不能按 principal、
  purpose 或日期查询。
- 当前 `Metrics` 页面已存在，可作为新增持久用量区域的入口，但旧 metrics API 和 Raw JSON
  必须保留。

近距离长青入口：

- [项目上下文](../../project-context-zh-CN.md)
- [架构](../../architecture-zh-CN.md)
- [Chat 记忆、RAG 与工具调用](../../chat-memory-rag-tool-calling-zh-CN.md)
- [多模型配置](../../multi-model-external-config-zh-CN.md)
- [REST API](../../rest-api-zh-CN.md)
- [测试指南](../../testing-guide-zh-CN.md)
- [交付工作流](../../delivery-workflow-zh-CN.md)

## 3. 价值、目标与非目标

### 3.1 价值

在多模型、工具调用、查询辅助和失败转移存在时，最终 response token 不能回答以下运营
问题：

- 一个 principal 在指定时间窗口实际触发了多少次模型调用？
- query transform、query expansion、AGENT 和 summary 的成本比例是多少？
- fallback 失败是否已经产生 token 消耗？
- 哪些 provider/model 缺失 usage 或价格配置？
- 服务重启后历史用量是否仍可查询？

持久 invocation ledger 是后续 hard limit、容量规划、成本治理和模型路由优化的事实基础。
先把“实际发生了什么”记录准确，再单独规划带授权预留、结算和崩溃恢复的强制预算。

### 3.2 目标

1. 新增 V53 `rag_llm_usage_event`，每个真实 ChatModel invocation 只产生一个终态事件。
2. 通过统一包装覆盖 mode-aware 和 legacy Chat 公开入口，包括 legacy Chat 中启用的 LLM
   query rewrite advisor。
3. 区分 `CHAT`、`QUERY_TRANSFORM`、`QUERY_EXPAND`、`SUMMARY` 四种用途。
4. 覆盖成功、失败、取消、fallback、应用 retry、AGENT 多轮和 summary。
5. 记录 stable principal 和 logical execution，但不记录敏感正文。
6. 对 provider usage 做明确的 available/unavailable 归一化。
7. 对配置的 input/output price 做调用开始时快照并计算可解释的 configured estimate。
8. 提供最大 366 个 UTC 日范围的 principal/model/purpose/mode/day 聚合查询。
9. root/ADMIN 可以查询全局或指定 principal；普通 principal 只能查询自己。
10. ledger 写入失败不触发 provider 重试、不改变 Chat 结果，并通过低基数指标暴露丢失。
11. 提供 bounded retention，避免事实表无限增长。
12. WebUI Metrics 提供有界、可访问、无需 principal 选择器的用量摘要。

### 3.3 非目标

- 不实施 token/cost hard limit、余额、预付、账单、发票、结算或支付。
- 不改变现有 request-per-minute quota、Chat execution budget 或工具调用上限。
- 不统计 embedding、rerank HTTP、PDF provider 或外部工具服务的成本。
- 不拆分 provider SDK/HTTP 内部自动 retry；一个 `call` 或一次 `stream` subscription
  对应一个 ledger invocation。
- 不保存 prompt、answer、retrieval query、source、tool arguments/result、API key、
  Authorization、native usage、异常 message 或 stack trace。
- 不改变 Chat/OpenAI response usage、history metadata 或 SSE done usage 的现有含义。
- 不删除或重做旧 `/api/v1/rag/metrics`、`/metrics/models`。
- 不引入 Redis、Kafka、消息队列、分布式事务或全新异步事件管线。
- 不把持久 ledger 直接当作强配额拒绝依据。

## 4. 冻结决策

| 事项 | 冻结默认 | 理由与可逆边界 |
|---|---|---|
| migration | `V53__add_llm_usage_event.sql` | 当前生产 schema 为 V52，增量表不改旧表 |
| ledger 开关 | `rag.usage.enabled=true` | 可在异常时停止新增记录，保留历史查询 |
| purpose | `CHAT`、`QUERY_TRANSFORM`、`QUERY_EXPAND`、`SUMMARY` | 对应当前 Chat 真实调用角色；legacy LLM query rewrite 也归入 `QUERY_TRANSFORM`，新增角色必须扩枚举 |
| outcome | `SUCCEEDED`、`FAILED`、`CANCELLED` | 反映单次 model invocation 终态，不等于整轮 Chat 终态 |
| logical execution | 每次真实执行生成 UUID；幂等 replay 不生成 | 把重放和真实重复执行区分开 |
| ordinal | execution 内从 1 递增 | 便于审计调用顺序和唯一去重 |
| principal | 从请求认证上下文捕获 stable ID | session/trace 不参与授权 |
| model ref | 配置模型使用 canonical `provider/model`；legacy 使用 provider alias | 不能把可变显示名当身份 |
| usage 缺失 | token 全部为 0，`usageAvailable=false` | 禁止猜测或从正文估算 |
| total 缺失 | prompt + completion，溢出则 unavailable | 只在输入足够且安全时回退 |
| price | 调用开始时快照 input/output，每百万 token | 价格配置可能变化，历史不能随配置漂移 |
| cache price | 本轮不计入 | Spring AI usage 没有跨 provider 稳定 cache token 契约 |
| cost unit | `CONFIGURED_MODEL_COST` | 明确是配置估算，不是供应商货币账单 |
| recorder failure | fail open + Micrometer lost counter | 不因记账失败重试模型，避免额外成本 |
| stream recorder | 使用有界专用 executor | 不在 provider/Reactor 网络线程阻塞 JDBC |
| retention | 默认 400 天、每小时 bounded cleanup | 覆盖默认 API 查询窗口并限制表增长 |
| API | `GET /api/v1/rag/usage` | 管理查询与旧 metrics 解耦 |
| API date | UTC，默认最近 30 天，最大 366 天，含首尾 | 跨实例查询语义稳定 |
| WebUI | Metrics 页面默认最近 30 天，显示 configured estimate | 运营入口清晰且不改变旧页面契约 |

## 5. 数据与事件契约

### 5.1 `LlmInvocationPurpose`

| 值 | 触发位置 |
|---|---|
| `CHAT` | PLAIN/KNOWLEDGE/AGENT 的每次 ChatModel call 或 stream subscription；AGENT 每轮单独计数 |
| `QUERY_TRANSFORM` | Spring AI `CompressionQueryTransformer`；legacy `QueryRewriteAdvisor` 中实际启用的 LLM rewrite |
| `QUERY_EXPAND` | Spring AI `MultiQueryExpander` |
| `SUMMARY` | `ConversationSummaryService` 的摘要模型调用 |

### 5.2 `ChatExecutionBudget` 增强

保留现有计数和快照字段，新增不可变 attribution：

- `UUID logicalExecutionId`
- `String principalId`
- `String sessionId`
- `String requestTraceId`，仅保存 1-128 个可打印 ASCII 字符，否则为 null
- `ChatMode chatMode`
- `AtomicInteger` ordinal counter

`reserveModelCall()` 改为返回从 1 开始的 ordinal；保留一个兼容的 void 调用适配器或让
所有当前调用点一次性迁移。预算拒绝发生在 provider 之前，不产生 ledger event。预算本身
只提供 logical execution attribution；每个 `BudgetedChatModel` 实例还必须携带明确的
`LlmInvocationPurpose`，不能用当前的 `summaryCall` 布尔值推断用途。主 Chat、查询转换、
查询扩展和摘要分别创建 purpose-aware wrapper。

现有 7 参数 `ChatExecutionBudget` 构造器和无归因的测试夹具必须继续可用；新增归因构造器
通过重载或工厂提供默认的 `AUTH_DISABLED`、本地 session 和新 UUID 值，不得要求所有既有
Java 扩展调用方同时改签名。`reserveModelCall()` 的兼容适配只能丢弃 ordinal，不能绕过
预算计数。

Budget 只保存低基数 attribution 和计数，不保存 prompt、response、工具参数或检索正文。

### 5.3 V53 `rag_llm_usage_event`

字段：

| 字段 | 类型/约束 | 语义 |
|---|---|---|
| `id` | `BIGSERIAL PRIMARY KEY` | 内部主键 |
| `invocation_id` | `UUID NOT NULL UNIQUE` | 单次 invocation ID |
| `logical_execution_id` | `UUID NOT NULL` | 一次实际 Chat execution |
| `call_ordinal` | `INTEGER NOT NULL CHECK > 0` | execution 内顺序 |
| `owner_principal_id` | `VARCHAR(128) NOT NULL` | stable principal |
| `session_id` | `VARCHAR(255) NOT NULL` | 关联维度，不作为授权依据 |
| `request_trace_id` | `VARCHAR(128)` | 可选关联值 |
| `model_ref` | `VARCHAR(255) NOT NULL` | canonical 模型引用或 `UNKNOWN` |
| `chat_mode` | `VARCHAR(16) NOT NULL` | PLAIN/KNOWLEDGE/AGENT |
| `purpose` | `VARCHAR(32) NOT NULL` | 四类用途 |
| `streaming` | `BOOLEAN NOT NULL` | call 或 stream |
| `outcome` | `VARCHAR(16) NOT NULL` | SUCCEEDED/FAILED/CANCELLED |
| `prompt_tokens` | `BIGINT NOT NULL CHECK >= 0` | 缺失为 0 |
| `completion_tokens` | `BIGINT NOT NULL CHECK >= 0` | 缺失为 0 |
| `total_tokens` | `BIGINT NOT NULL CHECK >= 0` | provider total 或安全回退 |
| `usage_available` | `BOOLEAN NOT NULL` | 是否收到可识别 usage |
| `input_cost_per_million` | `NUMERIC(20,8) NOT NULL CHECK >= 0` | 调用时输入价格 |
| `output_cost_per_million` | `NUMERIC(20,8) NOT NULL CHECK >= 0` | 调用时输出价格 |
| `pricing_available` | `BOOLEAN NOT NULL` | 价格是否可用 |
| `configured_cost` | `NUMERIC(20,8) NOT NULL CHECK >= 0` | 本次估算 |
| `cost_available` | `BOOLEAN NOT NULL` | 是否形成可靠估算 |
| `cost_unit` | `VARCHAR(32) NOT NULL` | `CONFIGURED_MODEL_COST` |
| `duration_ms` | `BIGINT NOT NULL CHECK >= 0` | wrapper 观察时长 |
| `started_at` | `TIMESTAMPTZ NOT NULL` | 开始时间 |
| `completed_at` | `TIMESTAMPTZ NOT NULL` | 终态时间 |
| `created_at` | `TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()` | 写入时间 |

约束：

- `UNIQUE(logical_execution_id, call_ordinal)`；
- mode、purpose、outcome 使用 CHECK 限制到已冻结枚举；
- `usage_available=false` 时三个 token 必须为 0；
- `pricing_available=false` 时两个价格必须为 0；
- `cost_available=false` 时 configured cost 必须为 0；
- `cost_available=true` 必须同时满足 usage/pricing available；
- 所有长度、金额、token、时长非负且有界：单个 prompt/completion token 不超过
  `2_147_483_647`，total 不超过 `4_294_967_294`，单价不超过 `1_000_000`
  （每百万 token），configured cost 不超过 `9_999_999_999.99999999`，duration
  不超过 `86_400_000` 毫秒；
- `completed_at >= started_at`，`request_trace_id` 为空或仅含 1-128 个可打印 ASCII
  字符；应用在 invocation 开始时通过可注入 `Clock` 捕获 `started_at`，使用
  `System.nanoTime()` 计算 duration，并把终态墙钟钳制为
  `completed_at=max(clock.instant(), started_at)`，避免 NTP 或人工校时回拨导致合法事件
  违反约束；
- 不建立 history 外键，不保存正文，不增加 update/delete API。

索引：

- `(logical_execution_id, call_ordinal)` unique；
- `(owner_principal_id, started_at DESC)`；
- `(owner_principal_id, model_ref, started_at DESC)`；
- `(started_at DESC)`。

### 5.4 usage 归一化

1. `Usage == null` 或 `EmptyUsage`：unavailable，三个 token 为 0。
2. `promptTokens`、`completionTokens` 必须都存在且为非负整数；`totalTokens` 可以缺失但必须
   能由前两者安全相加。任一字段为负数、异常、溢出或 prompt/completion 部分缺失时，整组
   token 标记 unavailable 并全部写 0，不用部分字段制造看似完整的成本。
3. 流式保留本次 subscription 收到的最后一个可识别 usage snapshot；任意 chunk 提供
   完整可识别的 prompt/completion usage 即 available；只出现 partial snapshot 不足以
   形成可计费用量。
4. 不直接信任 `Usage#getTotalTokens()` 的 `Integer` 默认求和：prompt、completion 和
   total 先提升为 `long`，逐项检查非负和溢出；显式合法 total 原样保存，total 缺失且
   prompt/completion 可安全相加时使用两者之和。无法安全归一化时整组 token 标记
   unavailable 并写 0。
5. provider 异常或取消前如果已经收到 usage，仍保存该 usage；否则 unavailable。
6. `nativeUsage` 永不落库。

### 5.5 配置成本

新增 `RagUsageProperties`：

```yaml
rag:
  usage:
    enabled: ${RAG_USAGE_ENABLED:true}
    cost-unit: ${RAG_USAGE_COST_UNIT:CONFIGURED_MODEL_COST}
    retention-days: ${RAG_USAGE_RETENTION_DAYS:400}
    cleanup-enabled: ${RAG_USAGE_CLEANUP_ENABLED:true}
    cleanup-batch-size: ${RAG_USAGE_CLEANUP_BATCH_SIZE:1000}
    cleanup-max-batches: ${RAG_USAGE_CLEANUP_MAX_BATCHES:20}
    cleanup-cron: ${RAG_USAGE_CLEANUP_CRON:0 20 * * * *}
    recorder-threads: ${RAG_USAGE_RECORDER_THREADS:2}
    recorder-queue-capacity: ${RAG_USAGE_RECORDER_QUEUE_CAPACITY:1000}
    record-timeout-ms: ${RAG_USAGE_RECORD_TIMEOUT_MS:2000}
```

绑定约束：

- retention `30..3650` 天；
- cleanup batch `100..10000`，max batches `1..100`；
- recorder threads `1..16`，queue `100..10000`；
- synchronous record timeout `100..10000` milliseconds；
- cost unit 为 1-32 个可打印 ASCII 字符；
- input/output `double` 先检查 finite/non-negative，再转换为 `BigDecimal`；
- 公式固定为：

```text
configuredCost =
    promptTokens     * inputCostPerMillion  / 1_000_000
  + completionTokens * outputCostPerMillion / 1_000_000
```

- scale 固定 8，`HALF_UP`；
- usage、pricing、计算任一不可用时 cost 不可用且写 0；
- cost 配置只影响新事件，历史使用事件内快照；
- 不跨不同 cost unit 合计。

`ChatModelRouter.ChatModelCandidate` 必须提供可选的 `ModelCost` 快照或等价的不可变
价格投影。配置模型从解析时的 `ModelItem.cost` 捕获；legacy provider 没有模型级价格
时保持 unavailable。wrapper 在 invocation 开始时读取该快照并把经过 finite、
non-negative 校验的输入/输出单价传给 recorder；不得在 recorder 异步执行时重新读取
可变配置。

### 5.6 recorder 失败语义

`LlmUsageRecorder` 使用独立短事务：

- 非流式调用返回或抛错后记录；
- 流式正常完成或 provider error 在专用有界 executor 上记录；
- 下游取消异步记录 `CANCELLED`，不假设 dispose 返回时已经完成；
- executor 拒绝时只增加 lost counter，不回退到 provider/Reactor 线程执行阻塞 JDBC；
- `ON CONFLICT(logical_execution_id, call_ordinal) DO NOTHING`，重复终态提交视为已记录；
- recorder 异常不替换原 provider 异常、不触发模型 retry、不让成功回答变失败；
- lost metric 只带 `purpose/outcome/reason` 低基数标签，日志只带 invocation ID、logical
  execution ID、model ref 和异常类型，不带 prompt/answer/session/principal/异常正文。

`record-timeout-ms` 是 recorder 等待一次数据库提交的硬上限：repository 同时设置 JDBC
statement query timeout，并在 PostgreSQL 事务内设置不超过该值的 `statement_timeout`。
超时后调用路径立即按 fail-open 继续；若数据库驱动无法取消已经发出的语句，后台迟到的
`ON CONFLICT DO NOTHING` 仍可最终写入，`lost` 计数表示本次提交未在等待窗口内确认，不宣称
数据库中绝对不存在该事件。验收需分别覆盖“及时提交”和“超时后原调用不阻塞”，并等待
recorder executor 排空后再读取最终数据库事实。

`SUCCEEDED` 只表示本次 ChatModel invocation 正常完成，不表示之后的 history commit、
摘要、citation 或整个 Chat request 成功。
非流式终态记录在原调用线程上完成，确保成功返回前事件已经提交或已明确计入 `lost`；
流式终态允许通过有界 executor 异步提交，因此流式 `done`/错误事件不承诺客户端收到传输
终止时数据库写入已完成。查询 API 和验收脚本必须等待 recorder 队列排空后再断言；这段
最终一致窗口不能被解释成重复 provider 调用。

## 6. 只读 API 与授权

新增：

```http
GET /api/v1/rag/usage
```

参数：

| 参数 | 默认 | 约束 |
|---|---|---|
| `from` | UTC 今天前 29 天 | `YYYY-MM-DD` |
| `to` | UTC 今天 | `to >= from`，含首尾 |
| `principalId` | 普通 principal 为自身；root/ADMIN 省略为全局 | 仅 root/ADMIN 可指定 |

最大范围 366 天，查询使用半开 UTC 区间。

响应保持稳定、低基数并包含：

- `recordingEnabled`
- `localLostEventsSinceStart`
- `scope(type, principalId)`；`type` 固定为 `SELF`、`ALL`、`PRINCIPAL`，普通 principal
  只能得到 `SELF`，root/ADMIN 省略 `principalId` 得到 `ALL`，root/ADMIN 指定后得到
  `PRINCIPAL`
- `from/to`
- `totals`：`logicalExecutionCount`、`invocationCount`、`succeededCount`、
  `failedCount`、`cancelledCount`、`promptTokens`、`completionTokens`、`totalTokens`、
  `usageAvailableCount`、`usageUnavailableCount`、`pricingUnavailableCount`、
  `costUnavailableCount`
- `costs[]`：每个元素包含 `unit`、`configuredCost`、`invocationCount` 和
  `costAvailableCount`；不同 `unit` 不相加
- `byModel[]`、`byPurpose[]`、`byMode[]`、`byDay[]`：每个元素包含对应的
  `modelRef`/`purpose`/`mode`/`day` 维度键，以及与 `totals` 相同的计数、token 和
  availability 字段；breakdown 不跨 cost unit 计算成本

所有计数使用非负 `long` JSON 数值，token 和 `configuredCost` 使用
`BigDecimal` JSON 数值；服务层对 SQL 结果做非负校验，异常或溢出结果使请求失败而不是
静默截断。`byModel` 按 `modelRef` 升序，`byPurpose` 按冻结枚举顺序，
`byMode` 按 `PLAIN`、`KNOWLEDGE`、`AGENT` 顺序，`byDay` 按日期升序，`costs` 按
`unit` 升序；相同键不重复。

规则：

- 普通 principal 查询他人返回 `403`，不能探测是否存在；
- root/ADMIN 可查询全局或指定 stable principal；
- 全局不返回按 principal 的 breakdown；
- 未知 principal 返回零聚合；
- `principalId` 必须是 1-128 个可打印 ASCII 字符；日期格式错误、缺失区间或超出
  366 天返回统一的 `400` 错误，不泄露数据库细节；
- 所有列表稳定排序；
- `BigDecimal` 作为 JSON 数值输出；
- token 聚合使用 PostgreSQL `numeric` 求和并以 `BigDecimal` 返回，避免大量事件时
  `BIGINT SUM` 溢出；单事件 token 仍受表约束，API 不把聚合强制缩窄为 Java `long`；
- 空范围返回零和空列表；
- 不提供 invocation 明细、正文、删除或修改 API；
- `/v1/chat/completions` 不增加该管理端点。

## 7. WebUI 范围

在 `spring-ai-rag-webui/src/pages/Metrics.tsx` 增加 typed durable usage query：

- 默认最近 30 个 UTC 日；
- 顶部显示 executions、invocations、total tokens、按 unit 的 configured cost；
- 明确显示“configured estimate，不是 provider invoice”；
- 显示 recording paused、当前实例 lost events、usage missing、pricing missing、
  uncosted 和 failed/cancelled；
- model、mode、purpose、day 使用紧凑表格；
- 不新增 principal 选择器，不把 principal、用量或凭据写入 URL/localStorage；
- loading、empty、error 使用 DOM 可访问状态，错误使用 `role=alert`；
- 保留旧 metrics 图表和 Raw JSON；
- 验收只使用 DOM、网络请求/响应和 JSON，不使用截图。

## 8. 文件级实施切片

### Slice A：配置、领域类型与 schema

- 新增 `RagUsageProperties` 并接入 `RagProperties`；
- 新增 purpose/outcome、attribution、usage snapshot/normalizer/cost calculator；
- 增强 `ChatExecutionBudget`；
- 在模型 candidate 中提供不可变的可选价格快照；每个 purpose 使用独立 wrapper，legacy
  路径统一迁移到 candidate descriptor，
  不保留只传裸 `ChatClient` 而丢失 model/cost attribution 的旁路；
- 新增 V53 migration；
- 先完成纯单元测试和配置 validation。

### Slice B：repository、recorder、retention

- 新增 `LlmUsageRepository`，使用 `JdbcTemplate`；
- 新增 `LlmUsageRecorder` 和有界 executor；
- 新增 `LlmUsageRetentionJob`；
- 聚合 SQL 使用 UTC 日期和半开区间；retention 按 `created_at` 清理，使用有序、分批
  的条件 `DELETE`，不使用 `SKIP LOCKED`、悲观锁或 advisory lock；
- PostgreSQL 测试覆盖约束、唯一键、聚合、cleanup 和 recorder failure。

### Slice C：全调用边界

- `ModeAwareChatClientFactory` 为主调用、query transform、query expand、summary 传明确
  purpose；不得通过 summary 布尔值或调用方线程状态推断；
- `BudgetedChatModel` 记录 call/stream subscription 的 exactly-once 终态；
- `ChatExecutionService.newBudget` 捕获 stable attribution；
- `RagChatService` legacy `chat`/`chatStream` 为每个公开调用建立 attribution，并包装
  fallback/retry；把当前 candidate/budget 通过 advisor context 传给
  `QueryRewriteAdvisor`，使其可选 LLM rewrite 也经过 purpose-aware wrapper；规则 rewrite
  不调用模型、不产生事件；
- 幂等 replay 不创建 budget、不调用模型、不增加事件；
- OpenAI compatibility 复用 mode-aware execution，不另建 ledger 语义。

### Slice D：API、前端与文档

- 新增 API DTO、service、controller、日期/权限验证；
- 更新 OpenAPI contract fixture；
- 更新 `scripts/verify-project-docs.sh` 及相关长青文档中的最新 Flyway 版本断言为 V53；
- 新增 WebUI typed client、Metrics usage 区域、i18n、Vitest；
- 更新双语 REST、configuration、architecture、project-context、testing/developer
  reference、TODO。

### Slice E：专项验收与交付

- 新增 `scripts/verify-llm-usage-ledger.sh`；
- 聚合 focused unit、PostgreSQL V1-V53、Maven、WebUI、Mock Playwright、隔离真实全栈；
- 在 Mock 完成后使用 `.env` 执行真实 LLM 必要调用并观察日志；
- 更新 progress，完成基本硬门槛后执行实现三轮审查；
- merge 最新 `origin/main`，按合并后基线完整复验，合并回 main、push、清理 worktree。

## 9. 一次性验收矩阵

### 9.1 纯单元与组件测试

一次性编写并运行：

- `LlmUsageNormalizerTest`：null、EmptyUsage、零值、部分字段、total 回退、负数、溢出；
- `LlmUsageCostCalculatorTest`：配置/legacy、免费模型、精度、rounding、overflow、unit；
- invocation snapshot/clock 测试：单调 duration、墙钟回拨时 completed time 钳制和最大时长；
- `ChatExecutionBudgetTest`：logical ID、attribution、ordinal、预算拒绝；
- `BudgetedChatModelTest`：call success/error、stream success/error/cancel、exactly-once、
  preflight 不记账、executor reject；
- `ModeAwareChatClientFactoryTest`：四种 purpose 和 model price 传递；
- `QueryRewriteAdvisorTest` / `QueryRewritingServiceTest`：legacy execution context 注入当前
  candidate/budget/price，LLM rewrite 经过 `QUERY_TRANSFORM` wrapper；无 context 时保持兼容，
  规则 rewrite 不记账；
- `ConversationSummaryServiceTest`：SUMMARY 成功/失败；
- `RagChatServiceTest`：legacy non-stream/stream、fallback/retry 进入 recorder；
- `LlmUsageRecorderTest`：fail-open、lost counter、日志敏感字段边界；
- recorder timeout test：及时提交、JDBC/statement timeout、超时后 provider 结果不被替换、
  executor 排空后的最终事实；
- `LlmUsageControllerTest`：self/global/admin、403、日期、空结果、稳定排序；
- 既有 Chat/OpenAI response usage compatibility 回归。

### 9.2 PostgreSQL 集成矩阵

新增 `LlmUsagePostgresIntegrationTest`，从空库执行 V1-V53，覆盖：

1. 表、索引、CHECK、unique；
2. success/failure/cancel event；
3. usage/pricing/cost availability 不变量；
4. `(logical_execution_id, call_ordinal)` 去重；
5. UTC 日边界和 366 日查询；
6. self/global/model/purpose/mode/day 聚合；
7. stable principal rotation continuity；
8. bounded retention，且不影响 chat history；
9. concurrent duplicate insert；
10. V52 既有 principal/collection ledger 仍可迁移和查询。

### 9.3 Chat 编排集成

使用确定性 Mock `ChatModel` 和真实 Spring AI advisor：

1. PLAIN 一次 CHAT；
2. KNOWLEDGE query transform + query expand + final CHAT；
3. AGENT 两轮工具调用生成两个 CHAT event；
4. primary failure + fallback success；
5. application retry 每次 call 独立 ordinal；
6. stream complete/error/cancel；
7. summary compaction 额外 SUMMARY；
8. keyed first execution + replay 不增加事件；
9. operation reclaim 重新执行产生新的 logical execution；
10. recorder repository/executor failure 不重复 provider；
11. provider 成功但后续 history/citation 失败仍保留 SUCCEEDED invocation；
12. legacy `RagChatService` non-stream/stream 也有事件，启用 LLM query rewrite 时额外有
    `QUERY_TRANSFORM` 事件，规则 rewrite 不额外记账。

### 9.4 后端、前端和 Mock 门槛

后端：

```bash
mvn clean compile test-compile
mvn test
./scripts/verify-no-pessimistic-locks.sh
./scripts/verify-project-docs.sh
git diff --check
```

前端：

```bash
cd spring-ai-rag-webui
npx tsc -b --pretty false
npm run test:run
npm run build
npm run check:alignment
```

Mock Playwright 必须断言 `/metrics` 与 `/usage` 网络 JSON、summary/table/error/empty DOM、
cost unit 分组、免责声明和无 principal selector；不得用截图。

### 9.5 真实全栈与真实 LLM

Mock 和 PostgreSQL 通过后，在隔离端口、可处置数据库上用 `.env` 启动：

1. 真实 PLAIN：API JSON、DB event 和 usage 聚合一致；
2. 真实 KNOWLEDGE：同 session 多轮，观察 query transform/expand/final call 是否入账；
3. 真实 AGENT：明确要求调用受控知识工具；若 provider 不触发工具，记录模型行为，但
   Mock 多轮仍是确定性通过证据；
4. native 与 OpenAI-compatible 入口均验证；
5. keyed replay 不增加事件；
6. credential rotation 后 stable principal 聚合连续；
7. 正常 stream 和取消各验证一次；
8. WebUI DOM 与 API/数据库只读聚合一致；
9. provider 缺失 usage 时事件仍存在并标记 unavailable；
10. 过程中持续观察日志，停止服务并清理临时资源。

真实 LLM 通过条件是：调用进入预期 provider 路径，API/DB/预算/日志证据一致；回答文本
是否“看起来合理”不是唯一通过条件。provider 不可用时必须记录外部阻塞，不能用 Mock 代替
真实结论。

## 10. 安全、并发、兼容与回滚

- stable principal 是唯一授权维度；session、trace 和 model ref 不参与权限判断。
- recorder 不包围 provider 网络调用，避免长事务和数据库连接占用。
- 唯一约束和 `ON CONFLICT DO NOTHING` 提供去重；禁止悲观锁、`SKIP LOCKED`、advisory lock。
- 聚合只返回低基数数据；不提供按 prompt 或 tool 内容检索。
- recorder fail-open，lost counter 不带动态 principal/session/model 标签。
- V53 是 additive migration；旧应用可忽略新表，回滚应用不删除迁移。
- `rag.usage.enabled=false` 停止新写入但保留查询；重新打开继续追加。
- `cleanup-enabled=false` 只暂停 retention；不影响记录或查询。
- 价格配置变化不修改历史事件。
- API 不改变已有 response usage、history、SSE 或 OpenAI compatibility body。
- V53 交付同时更新仓库质量门禁对“最新迁移版本”的断言；历史归档文档中描述过去版本的
  内容不改写为当前事实，但 live 文档和检查脚本必须一致。

## 11. 规划审查与实施收敛

规划审查固定为三轮：

1. 价值、问题闭环、目标/非目标、默认决策和自包含性；
2. 当前代码/Spring AI、schema、事务、流式终态、权限、legacy 覆盖和成本边界；
3. 文件切片、验收矩阵、真实 LLM、发布、回滚、文档和 Git/worktree 交付。

发现会影响正确性、成本安全、兼容性、隐私、数据一致性或可实施性的实质问题，立即修改
规划并将计数重置为 0。无问题轮次不修改正文；连续三轮无修改后，才进入实施。

实现完成后先通过基本集成硬门槛，再进行三轮互不重叠的只读实现审查：

1. migration、事务、并发、exactly-once、stream cancel 和失败恢复；
2. API/ACL、usage/cost 语义、legacy/前端兼容和敏感数据边界；
3. 自动化证据、真实 provider、文档、发布、回滚和 worktree 交付。

任何实质修复都重跑受影响门槛并把实现审查计数重置为 0。

## 12. 完成定义

只有全部满足才算本轮完成：

1. 规划达到连续 `3/3` 无修改并提交；
2. V53 从空库迁移并由 PostgreSQL 集成测试证明；
3. mode-aware 与 legacy 所有目标调用边界都有确定性事件证据；
4. 每次真实 invocation 最多一个终态事件，replay 不增加事件；
5. success/failure/cancel、usage/pricing 缺失、fallback/retry、AGENT、summary、stream
   均通过测试；
6. self/global/admin ACL、聚合排序、retention 和 fail-open 通过 HTTP/数据库测试；
7. WebUI tsc/Vitest/build/Mock Playwright 通过，且验收不使用截图；
8. `mvn clean compile test-compile`、全量 Maven、服务启动、锁/文档/diff 门禁通过；
9. 真实 LLM 必要调用已实际执行并如实记录 provider 结果；
10. 双语长青文档更新并通过文档门禁；
11. 实现连续 `3/3` 无实质修改；
12. 跟进最新 `origin/main` 后完整复验，feature 合回 main、push 成功、状态干净并移除
    已合并 worktree。
