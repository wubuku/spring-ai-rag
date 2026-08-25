# LLM 模型调用级用量账本与成本可观测性实施候选

> **状态**：非活跃实施候选；规划检查在第 1 轮发现覆盖缺口后停止，未达到 `3/3`
>
> **候选整理日期**：2026-08-25
>
> **规划基线**：本地 `main` / `origin/main` @ `97e946d3`，Spring Boot `3.5.16`，
> Spring AI `1.1.8`，Java `21`，Flyway V1-V48
>
> **规划工作区**：
> `/Users/yangjiefeng/.hermes/workspace/spring-ai-rag-main-delivery`
>
> **计划实施分支**：`feat/llm-invocation-usage-ledger-20260825`
>
> **计划实施 worktree**：
> `/Users/yangjiefeng/.hermes/workspace/spring-ai-rag-llm-usage-ledger`
>
> **近距离上下文**：[项目上下文](../../project-context-zh-CN.md)、
> [Chat 记忆、RAG 与工具调用](../../chat-memory-rag-tool-calling-zh-CN.md)、
> [多模型配置](../../multi-model-external-config-zh-CN.md)、
> [REST API](../../rest-api-zh-CN.md)、
> [测试指南](../../testing-guide-zh-CN.md)、
> [交付工作流](../../delivery-workflow-zh-CN.md)

本文保留一轮已停止的规划探索，作为未来可能重新评估的实施候选。它不是当前活跃规划，
没有完成连续三轮无修改检查，不能直接作为生产实施指令。未来若重新选择该候选，必须先按
当时最新代码重新核对所有公开 Chat 入口、Spring AI 版本、迁移号和测试矩阵，再建立新的
活跃 plan/progress。

2026-08-23 的
[历史 Token 用量账本草案](2026-08-23_TOKEN_USAGE_LEDGER_PLAN.md)
只计划为最终成功提交的 Chat turn 写一条事件。该方案已经归档且被本规划替代，不能作为
当前实施入口。本规划修正的核心问题是：一次逻辑 Chat 可能产生多次真实模型调用，最终
响应中的 usage 不能代表全部调用。

## 1. 执行摘要

当前项目已经具备：

- V48 stable API principal、credential rotation 和共享 request quota；
- `ChatExecutionBudget` 对 candidate、模型调用、工具轮次和 deadline 的逻辑请求预算；
- `BudgetedChatModel` 对主回答、KNOWLEDGE 辅助模型、AGENT 工具循环和摘要调用的统一包装；
- Spring AI provider response 中的 prompt/completion/total token usage；
- `models.json` / YAML 中按百万 token 配置的 input/output/cache 成本；
- Chat 响应、历史 metadata 和进程内 Micrometer 指标。

但系统仍没有一个可信的、可按 stable principal 查询的持久模型用量事实：

1. `rag_chat_history.metadata.usage` 只保存最终 Chat response 的 usage；
2. Spring AI `ToolCallAdvisor` 在非流式工具循环结束时返回最后一次模型响应，不累计前面
   的 tool-call response usage；
3. Spring AI `MessageAggregator` 对流式 usage 保留最近的非零值，不会跨 AGENT 递归、
   query transform、query expansion 或 summary 调用求和；
4. KNOWLEDGE 默认 profile 可先调用模型做 query compression 和 multi-query expansion，
   最后才调用模型生成答案；
5. fallback、应用级 retry 和失败 candidate 已经产生的模型调用不属于最终成功 response；
6. 当前 `rag.llm.tokens.total` 是进程内计数，重启丢失，也不能按 Principal、模型、日期
   或调用用途聚合；
7. 当前 request-per-minute quota 只限制 HTTP 请求数，无法解释一个请求内的模型 fan-out
   和成本。

本轮新增 **模型调用级持久用量账本**：

```text
一个逻辑 Chat 请求
  -> 主回答 CHAT 调用
  -> 可选 QUERY_TRANSFORM 调用
  -> 可选 QUERY_EXPAND 调用
  -> 可选 AGENT 后续 CHAT 调用
  -> 可选 SUMMARY 调用
  -> 每次 BudgetedChatModel call/stream subscription 各写一条 usage event
  -> stable principal/model/purpose/day 聚合 API
  -> WebUI Metrics 用量视图
```

账本记录 Spring AI `ChatModel` **调用边界**，不是 HTTP provider 内部重试次数。每次通过
prompt/budget 前置校验并真正准备进入 `delegate.call(...)` 或订阅
`delegate.stream(...)` 时，才分配一个单调 call ordinal；同一逻辑请求中的工具循环、辅助
调用、fallback 和应用级 retry 都会形成独立事件。前置校验或预算拒绝没有发生 provider
调用，不写事件。幂等 replay 不执行模型，因此不会增加事件。

本轮只建立应用侧 best-effort 计量、查询、保留和丢失告警，不直接实施 token 或金额 hard
limit。终态写入失败会 fail open，进程在 provider 已执行但终态事件尚未提交时崩溃也可能留下
无法持久化观测的缺口；因此它不是供应商账单，也不能直接作为强配额结算源。准确的硬限额需要
预授权、预留、结算、跨实例超额保护和崩溃恢复；在账本准确性尚未经过真实 provider 验证前将
二者绑在同一批次，会把计量缺口变成错误拒绝或成本绕过风险。

## 2. 为什么本轮选择这个功能

### 2.1 候选比较

| 候选 | 当前收益 | 主要风险 | 本轮决定 |
|---|---|---|---|
| 模型调用级用量账本与成本可观测性 | 为外部 Client 提供 stable principal 级容量、成本和 fan-out 事实；补齐 V48 后最直接的运营缺口 | 跨模型调用、流式、数据库和 WebUI，但已有统一包装边界 | **实施** |
| `EACH_COLLECTION` 召回覆盖 | 对明确要求每个知识库都有机会出证据的场景有价值 | bounded fan-out、融合、质量数据和 UI 语义尚需独立产品需求 | 延后 |
| OAuth/OIDC 与独立 tenant | 公网身份 federation 价值高 | issuer/JWKS、tenant ACL、token 生命周期和迁移范围过大 | 独立规划 |
| 多 Embedding Profile 运行时路由 | 支持 Collection 使用不同向量空间 | 触及写入、job、检索、readiness、repair 和迁移全链路 | 独立规划 |
| 继续微调 heuristic rerank | 可能有局部质量收益 | 最近多轮已经覆盖 CJK、标题和边界，边际收益下降 | 不进入本轮 |

### 2.2 为什么不能直接实施历史成功-turn方案

假设一次 KNOWLEDGE 请求启用了 query transform 和两个 query variants：

```text
call 1: QUERY_TRANSFORM
call 2: QUERY_EXPAND
call 3: CHAT answer
```

历史方案只会从最终 `ChatExecutionResult.usage` 写一条记录，因此最多看到 call 3。

假设一次 AGENT 请求：

```text
call 1: model asks for searchKnowledge
tool:   searchKnowledge
call 2: model asks for another tool
tool:   another read-only tool
call 3: model returns final answer
```

Spring AI `ToolCallAdvisor` 的非流式实现会循环调用 model，但最终返回 call 3 的 response。
历史方案会把一次三调用请求记成一次调用。若首选 candidate 失败后 fallback 成功，失败
candidate 的调用也会完全丢失。

因此账本必须靠近 `BudgetedChatModel`，不能从最终 history 或 response 反推。

## 3. 已核对的当前代码与框架事实

### 3.1 统一模型调用边界

`ModeAwareChatClientFactory` 当前在一个 request 共享的 `ChatExecutionBudget` 下创建
`BudgetedChatModel`：

- 主 PLAIN/KNOWLEDGE/AGENT 回答使用该 wrapper；
- `CompressionQueryTransformer` 使用同一 wrapper 和 budget；
- `MultiQueryExpander` 使用同一 wrapper 和 budget；
- Spring AI `ToolCallAdvisor` 的每一轮递归都会再次调用同一个 wrapper；
- `ConversationSummaryService` 直接创建带 `summaryCall=true` 的 wrapper；
- fallback candidate 和应用级 retry 继续共享同一个 budget。

这是三模式 `ChatExecutionService` 路径中唯一同时覆盖模型调用 fan-out、deadline 和逻辑
请求计数的边界，但它还不是整个项目的完整公共调用边界。正式第 1 轮规划检查确认：

- `RagChatService.chat(String...)` 在 mode-aware service 可用时仍直接进入 legacy
  `executeChat(...)`；
- legacy `chatStream(...)` 也直接使用未包装的 `ChatClient`；
- `demo-basic-rag` 和 `demo-domain-extension` 的 quick 入口仍调用这些公开 overload。

因此未来实施不能只修改 `BudgetedChatModel` 就声称覆盖整个项目。推荐默认是把这些公开
兼容 overload 映射为等价 `ChatRequest`，统一进入 `ChatExecutionService`；若兼容语义无法
等价迁移，则必须在 legacy 路径使用同一 attribution/recorder 抽象并增加 demo 集成测试。
在该缺口解决前，本候选不满足“每次项目 ChatModel invocation 都入账”的目标。

### 3.2 最终 response usage 不是逻辑请求总量

本地依赖源码已核对 Spring AI `1.1.8`：

- `ToolCallAdvisor.adviseCall(...)` 在工具循环中不断替换 `chatClientResponse`，最后返回
  最后一轮 response；
- `ToolCallAdvisor.adviseStream(...)` 为每轮流式 model call 单独聚合，再递归下一轮；
- `MessageAggregator` 对一个 Flux 的 usage 使用最近的正值，不跨独立 model invocation 求和。

因此当前 `ChatExecutionService.toResult(...)` 从最终
`springResponse.getMetadata().getUsage()` 生成的 map 只能继续作为最终公开 response usage；
不能把它重新解释为账本总量。本轮保持该兼容契约不变，并新增独立 usage API。

### 3.3 stable principal、replay 与 request identity

- `ChatPrincipal.id()` 当前为 `root:environment-root`、`db:{stablePrincipalId}`、
  `legacy:static` 或 `local:auth-disabled`；
- database credential rotation 不改变 stable principal；
- 带 `Idempotency-Key` 的成功 replay 直接读取 V47 response snapshot，不执行
  `ChatExecutionService`，因此不会进入 `BudgetedChatModel`；
- reclaim 后重新执行模型代表一次新的实际成本，应使用新的 logical execution ID 和事件；
- 非 keyed 请求没有 durable turn ID，但仍有 stable principal、session、HTTP trace 和本轮
  新生成的 logical execution ID。

账本以 stable principal 授权，以 `logical_execution_id + call_ordinal` 保证一次执行内幂等，
不依赖可被 TTL 删除的 history row。

### 3.4 usage 与 pricing 的真实边界

- Spring AI `Usage` 只稳定提供 prompt/completion/total；provider 缺失 usage 时可能返回
  `null`、`EmptyUsage` 或流式全零聚合；
- `Usage.getNativeUsage()` 是 provider-specific object，本轮不把未知结构写入数据库；
- `MultiModelProperties.ModelCost` 当前包含 input/output/cacheRead/cacheWrite 的
  `double`，语义是每 1,000,000 token 的配置成本；
- 当前没有稳定的 cache read/write token 统一契约，也没有货币字段；
- configured model ref 可以查到 `ModelItem.cost`；legacy provider bean 可能没有 pricing。

本轮只使用 prompt/completion token 和 input/output 配置成本。cache cost 不参与计算；
缺失 usage 或 pricing 必须显式计数，不能猜测。

### 3.5 当前 metrics/WebUI 缺口

- `RagMetricsService` 的 token gauge 是内存 `AtomicLong`；
- `ModelMetricsService` 提供进程内 provider call/error/latency，但当前生产 Chat 没有在
  `BudgetedChatModel` 统一调用它；
- `/api/v1/rag/metrics` 返回的字段与 `MetricsCharts` 期望字段并不完全一致，页面大量依赖
  默认零值和 Raw JSON；
- Metrics 页面已经是合适的运营入口，但新用量 API 必须有独立 typed client、loading、
  empty 和 error 状态，不能继续依赖松散对象。

本轮不删除旧 metrics API；新 durable usage 区域与旧进程指标并存，避免破坏既有调用方。

## 4. 目标与非目标

### 4.1 目标

1. V49 新增 `rag_llm_usage_event`，每次 Spring AI `ChatModel` 调用/流订阅一条事件。
2. 覆盖 `CHAT`、`QUERY_TRANSFORM`、`QUERY_EXPAND`、`SUMMARY` 四类调用用途。
3. 覆盖非流式成功/失败、流式成功/失败/取消、AGENT 多轮、fallback 和应用级 retry。
4. 使用 stable principal、session、logical execution ID、call ordinal 和 model ref 归属。
5. 保存 provider usage 的规范化 token、usage availability、配置价格可用性、价格快照和
   成本估算可用性。
6. 账本写入使用独立短事务；写入失败不触发模型重试或把已成功回答改成失败，但必须产生
   Micrometer 丢失计数和脱敏错误日志。
7. 提供按 UTC 日期、当前 Principal、模型、用途和结果聚合的只读 API。
8. root/ADMIN 可以查询全局或指定数据库 Principal；普通 Principal 只能查询自己。
9. 提供默认 400 天、可配置、批次有界的自动清理，避免事实表无限增长。
10. WebUI Metrics 展示最近 30 天 invocation、token、配置成本、缺失计量和按模型/Chat
    mode/用途分解。
11. 通过 Mock、PostgreSQL、真实服务、真实 LLM 和只读数据库查询证明账本覆盖真实 fan-out。

### 4.2 非目标

- 不实施 token/cost hard limit、余额、预付、账单、发票或支付。
- 不改变 V48 requests-per-minute quota。
- 不统计 embedding、rerank HTTP、PDF provider 或外部工具数据库的成本。
- 不把 provider HTTP client 内部自动 retry 拆成多条事件；一条事件表示一次
  `ChatModel.call` 或一次 `ChatModel.stream` subscription。
- 不保存 prompt、answer、tool arguments/result、retrieval query、source、API key、
  provider native usage、异常 message 或 stack trace。
- 不修改公开 Chat/OpenAI response usage 语义，不声称它等于逻辑请求总量。
- 不为普通 Principal 暴露其他 Principal 的用量或全局 principal breakdown。
- 不在本轮重做整个 Metrics 页面或删除旧 metrics endpoint。
- 不引入 Redis、Kafka、消息队列、分布式事务或异步批处理管线。

## 5. 冻结的行为与数据契约

### 5.1 调用用途

新增内部枚举 `LlmInvocationPurpose`：

| 值 | 触发位置 | 说明 |
|---|---|---|
| `CHAT` | PLAIN/KNOWLEDGE/AGENT 的主 `ChatClient` | AGENT 每个 model/tool round 都是独立 CHAT invocation |
| `QUERY_TRANSFORM` | `CompressionQueryTransformer` | 把 history-aware follow-up 压缩为检索 query |
| `QUERY_EXPAND` | `MultiQueryExpander` | 生成有界 query variants |
| `SUMMARY` | `ConversationSummaryService` | 已提交历史的 best-effort 摘要压缩 |

未来增加其他辅助模型用途必须新增枚举和测试，不能都塞进 `CHAT`。

### 5.2 logical execution 与 ordinal

`ChatExecutionBudget` 新增：

- `logicalExecutionId: UUID`：每次真正开始执行 Chat 时生成；
- `UsageAttribution`：stable principal ID、session ID、Chat mode、request trace ID；
- `reserveModelCall()` 返回从 `1` 开始的 call ordinal，而不是只做 void increment；
- snapshot 继续输出 `modelCalls`，不公开 raw principal。

`requestTraceId` 在创建 budget 的同步请求线程上从 `RequestTraceFilter` MDC 捕获；若 MDC
为空但存在 `RetrievalTraceSession`，使用其 trace UUID；两者都不存在时保持 `null`。异步流和
summary 只读取 budget 中已冻结的 attribution，不在线程切换后重新访问 request/MDC。外部
trace 值只有满足 `1..128` 个可打印 ASCII 字符时才持久化，否则置空，不能因超长或控制字符
导致整条 usage event 丢失。

唯一约束：

```text
UNIQUE (logical_execution_id, call_ordinal)
```

同一 keyed request 的 replay 不创建 budget，因此没有新 logical execution；过期 operation
被 reclaim 后重新执行会创建新的 logical execution，正确反映重复发生的真实调用。

### 5.3 V49 `rag_llm_usage_event`

新增字段：

| 字段 | 类型/约束 | 语义 |
|---|---|---|
| `id` | `BIGSERIAL PRIMARY KEY` | 内部顺序 ID |
| `invocation_id` | `UUID NOT NULL UNIQUE` | 单次 invocation identity |
| `logical_execution_id` | `UUID NOT NULL` | 一次实际 Chat execution |
| `call_ordinal` | `INTEGER NOT NULL CHECK > 0` | execution 内单调序号 |
| `owner_principal_id` | `VARCHAR(128) NOT NULL` | stable principal |
| `session_id` | `VARCHAR(255) NOT NULL` | 关联维度，不作为授权依据 |
| `request_trace_id` | `VARCHAR(128) NULL` | HTTP/日志关联；没有时为空 |
| `model_ref` | `VARCHAR(255) NOT NULL` | canonical candidate ref；缺失时 `UNKNOWN` |
| `chat_mode` | `VARCHAR(16) NOT NULL` | 发起 execution 的 `PLAIN`、`KNOWLEDGE` 或 `AGENT` |
| `purpose` | `VARCHAR(32) NOT NULL` | 四类用途 |
| `streaming` | `BOOLEAN NOT NULL` | call 或 stream |
| `outcome` | `VARCHAR(16) NOT NULL` | `SUCCEEDED`、`FAILED`、`CANCELLED` |
| `prompt_tokens` | `BIGINT NOT NULL CHECK >= 0` | 有效 usage；缺失为 0 |
| `completion_tokens` | `BIGINT NOT NULL CHECK >= 0` | 有效 usage；缺失为 0 |
| `total_tokens` | `BIGINT NOT NULL CHECK >= 0` | provider total 或安全回退 |
| `usage_available` | `BOOLEAN NOT NULL` | provider 是否返回可识别 usage |
| `input_cost_per_million` | `NUMERIC(20,8) NOT NULL CHECK >= 0` | 调用时配置快照 |
| `output_cost_per_million` | `NUMERIC(20,8) NOT NULL CHECK >= 0` | 调用时配置快照 |
| `pricing_available` | `BOOLEAN NOT NULL` | model cost 配置是否存在且合法 |
| `configured_cost` | `NUMERIC(20,8) NOT NULL CHECK >= 0` | 本次估算 |
| `cost_available` | `BOOLEAN NOT NULL` | usage、pricing 和计算结果是否都足以形成估算 |
| `cost_unit` | `VARCHAR(32) NOT NULL` | 默认 `CONFIGURED_MODEL_COST`，用于防止跨单位混加 |
| `duration_ms` | `BIGINT NOT NULL CHECK >= 0` | wrapper 观察的调用时长 |
| `started_at` | `TIMESTAMPTZ NOT NULL` | invocation 开始时间 |
| `completed_at` | `TIMESTAMPTZ NOT NULL` | 终态时间 |
| `created_at` | `TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()` | 落库时间 |

索引：

- unique `(logical_execution_id, call_ordinal)`；
- `(owner_principal_id, started_at DESC)`；
- `(owner_principal_id, model_ref, started_at DESC)`；
- `(started_at DESC)`，供 root/ADMIN 全局日期聚合和 retention。

V49 同时为 principal/session/model/unit 长度、三类 chat mode、四类 purpose、三类 outcome、
非负 token/rate/cost/duration、正 ordinal 增加数据库 CHECK，并冻结以下不变量：

- `usage_available=false` 时三个 token 必须全为 0；
- `pricing_available=false` 时两个 rate 必须全为 0；
- `cost_available=false` 时 configured cost 必须为 0；
- `cost_available=true` 必须蕴含
  `usage_available=true AND pricing_available=true`。

`session_id` 继续遵循现有 36 字符公共契约，列宽保留 255 只为兼容。duration 使用
`System.nanoTime()` 差值，wall clock 只用于 started/completed 时间和 UTC 日期归属。

不设置 history 外键，不保存 turn body，不增加 UPDATE API。自动 retention 会按主键批次删除
过期事件，因此不增加阻止 DELETE 的 immutable trigger。

### 5.4 usage normalization

对每次 model response：

1. `Usage == null` 或 `EmptyUsage` 为 unavailable；
2. 流式调用只要任一 chunk 提供非空、非 `EmptyUsage` 且至少一个非负 token 或非空 native
   usage，即认为 available；
3. token 值只接受非负整数；`null`、负数或溢出视为缺失；
4. prompt/completion 缺失写 0；
5. total 合法时原样保存；total 缺失时使用 prompt + completion，并做溢出保护；
6. usage unavailable 时三个 token 都写 0；
7. 失败或取消时若此前已经收到 usage chunk，仍保存可用 usage；否则 unavailable；
8. 不序列化 `nativeUsage`。

Spring AI 的 token 类型当前为 `Integer`，数据库使用 `BIGINT` 是为聚合和未来 provider
兼容，不表示本轮接受任意精度输入。

### 5.5 configured cost

新增配置：

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
    cleanup-zone: ${RAG_USAGE_CLEANUP_ZONE:UTC}
    recorder-threads: ${RAG_USAGE_RECORDER_THREADS:2}
    recorder-queue-capacity: ${RAG_USAGE_RECORDER_QUEUE_CAPACITY:1000}
```

约束：

- `cost-unit` 规范化为非空、最长 32 字符的可打印 ASCII；
- `retention-days` 范围 `30..3650`，至少覆盖默认 30 日 API 窗口；若小于 API 最大 366
  日范围，较早区间自然返回已保留的数据；
- batch 范围 `100..10000`；
- cleanup max batches 范围 `1..100`，默认小时级最多删除 20,000 条；高于该吞吐的部署
  必须显式提高 batch/max-batches 或缩短 cron，并观察 cleanup deleted/error 指标；
- recorder threads 范围 `1..16`，queue 范围 `100..10000`，应用关闭时最多等待 10 秒
  完成已接收的终态写入；
- pricing 在 invocation 开始时从 `modelRef -> ModelItem.cost` 解析并冻结；
- legacy model 或无 cost 时 `pricing_available=false`，rate/cost 为 0；
- `double` 先要求 finite/non-negative，再用 `BigDecimal.valueOf`；
- 公式只包含：

  ```text
  configuredCost =
      promptTokens     * inputCostPerMillion  / 1_000_000
    + completionTokens * outputCostPerMillion / 1_000_000
  ```

- scale 固定 8，`HALF_UP`；
- 只有 usage available、pricing available 且计算可表示时
  `cost_available=true`；缺失 usage、溢出或不能表示在 `NUMERIC(20,8)` 时 cost
  unavailable，不阻断模型调用；
- cacheRead/cacheWrite 不参与本轮；
- 每个 event 保存 cost unit。API 不跨 unit 求和，而是返回 `costs[]`。

### 5.6 写入失败语义

账本写入发生在 provider invocation 结束后，使用 `REQUIRES_NEW` 短事务。失败处理固定为：

- insert 使用 `ON CONFLICT (logical_execution_id, call_ordinal) DO NOTHING`；同一终态因
  回调竞争重复提交时视为已记录，不产生第二条事件或 lost counter；
- 不抛回 Chat 调用方；
- 不让 Spring Retry 因账本失败重复调用 provider；
- `rag.llm.usage.events.lost` counter +1，tag 只包含 `purpose`、`outcome`、`reason` 的低基数值；
- error log 只包含 invocation ID、logical execution ID、model ref 和异常类型，不包含
  prompt、answer、session、principal 或 exception message；
- readiness 不因单次失败变 DOWN；
- PostgreSQL 集成测试必须证明 ledger repository 失败时 provider 成功 response 仍只返回一次。

选择 fail-open 是为了避免“记账失败导致模型重试并产生更多未记账成本”。本轮 API 必须返回
`localLostEventsSinceStart` 的当前进程补充值，旧 metrics 也暴露同一 counter；它不是多实例
全局值，进程崩溃窗口也不能保证被该 counter 捕获。持久聚合只代表成功写入的事件，文档不得
描述为供应商最终账单。

非流式 recorder 在 provider 调用返回/抛错后同步执行短事务。流式 recorder 不能在 provider
或 Reactor 网络事件线程直接执行阻塞 JDBC：

- 正常完成和 provider error 将终态持久化组合进 reactive terminal path，并切换到专用的
  有界 usage-recorder executor；完成写入或 fail-open 后才向下游发出最终 terminal；
- client cancellation 无法等待已取消的下游，使用同一有界 executor 异步提交
  `CANCELLED`；测试必须有界轮询事件，不假设 dispose 返回时已经落库；
- executor 拒绝任务按 recorder failure 处理并增加 lost counter，不回退到调用线程执行
  阻塞 JDBC；
- recorder 自身错误永远不替换原 provider error，也不触发 retry。

### 5.7 自动 retention

`LlmUsageRetentionJob`：

- 默认每小时第 20 分钟执行；
- 每轮最多删除 `cleanup-batch-size` 个 `started_at < cutoff` 的最旧事件；
- 使用单条 bounded conditional delete，不使用 `FOR UPDATE`、`SKIP LOCKED` 或 advisory lock；
- 多实例可以重复选择同一批，DELETE 幂等，不要求 leader election；
- 单次 schedule 最多循环 `cleanup-max-batches` 个 batch，某批少于 batch size 时提前停止；
- `enabled=false` 只暂停新事件写入；API 仍返回历史聚合并标记
  `recordingEnabled=false`；
- `cleanup-enabled=false` 才暂停 retention；默认即使 recording 暂停也继续执行保留策略；
- retention 不删除 Chat history，也不受 Chat session lease 影响。

### 5.8 只读 Usage API

新增：

```text
GET /api/v1/rag/usage
```

参数：

| 参数 | 默认 | 约束 |
|---|---|---|
| `from` | UTC 今天前 29 天 | ISO `YYYY-MM-DD` |
| `to` | UTC 今天 | 含首尾，必须 `to >= from` |
| `principalId` | 普通 Principal 为自身；root/ADMIN 省略为全局 | 仅 root/ADMIN 可指定 |

最大范围 366 个 UTC 日。普通 Principal 显式传入与自身不同的 canonical stable ID 返回
`403`；root/ADMIN 可以传 `db:{stablePrincipalId}`、`root:environment-root`、
`legacy:static` 或 `local:auth-disabled`。未知 Principal 返回零聚合，不提供存在性探测错误。

响应：

```json
{
  "recordingEnabled": true,
  "localLostEventsSinceStart": 0,
  "scope": {
    "type": "SELF",
    "principalId": "db:principal-1"
  },
  "from": "2026-07-27",
  "to": "2026-08-25",
  "logicalExecutions": 8,
  "invocations": 17,
  "succeededInvocations": 15,
  "failedInvocations": 1,
  "cancelledInvocations": 1,
  "promptTokens": 12000,
  "completionTokens": 3500,
  "totalTokens": 15500,
  "invocationsWithUsage": 14,
  "invocationsWithoutUsage": 3,
  "costs": [
    {
      "unit": "CONFIGURED_MODEL_COST",
      "configuredCost": 0.12345678,
      "costedInvocations": 13,
      "uncostedInvocations": 4,
      "invocationsWithoutPricing": 3
    }
  ],
  "byModel": [
    {
      "modelRef": "openai/grok-4.5",
      "invocations": 17,
      "totalTokens": 15500,
      "invocationsWithoutUsage": 3,
      "costs": []
    }
  ],
  "byPurpose": [
    {
      "purpose": "CHAT",
      "invocations": 10,
      "totalTokens": 10000
    }
  ],
  "byMode": [
    {
      "mode": "KNOWLEDGE",
      "logicalExecutions": 4,
      "invocations": 9,
      "totalTokens": 8200,
      "costs": []
    }
  ],
  "byDay": [
    {
      "day": "2026-08-25",
      "logicalExecutions": 2,
      "invocations": 5,
      "totalTokens": 4200,
      "costs": []
    }
  ]
}
```

规则：

- 全局 scope 的 `principalId=null`；
- 全局响应不返回 by-principal breakdown；
- `localLostEventsSinceStart` 只表示当前服务实例，不参与 Principal 聚合；
- 所有列表按稳定顺序：model ref、mode、purpose、day；
- `BigDecimal` JSON 保持数值，不转 `double`；
- 空范围返回零和空列表；
- `uncostedInvocations` 包含 usage 缺失、pricing 缺失或计算溢出的事件；
  `invocationsWithoutPricing` 只统计 `pricing_available=false`；
- API 只读，无事件明细、删除或修改端点；
- OpenAI-compatible `/v1/*` 不新增该管理端点。

### 5.9 WebUI Metrics

Metrics 页面新增 durable usage 区域：

- 默认请求最近 30 个 UTC 日；
- 顶部显示 logical executions、invocations、total tokens、configured costs；
- 多 cost unit 分别显示，绝不把不同 unit 折成一个顶部总额；
- 明确标注“configured estimate，不是 provider invoice”；
- 显示 recording paused、当前实例 lost event、usage missing、pricing missing、
  uncosted 和 failed/cancelled invocation；
- 按 model、Chat mode 和 purpose 使用紧凑表格，不增加大型装饰图；
- root/ADMIN 默认显示全局，普通 Principal 显示自身；本轮不做 Principal 选择器；
- loading、empty、error 使用可访问状态，错误使用 `role=alert`；
- 不把 principal、用量或凭据写入 URL/localStorage；
- 保留旧 metrics 区域和 Raw JSON，但新 API 使用 typed DTO；
- 前端验收只使用 DOM、网络和 JSON 断言，不使用截图。

## 6. 实施设计与文件级切片

### Phase 0：建立隔离实施分支

1. 从已推送的最新 `origin/main` 创建
   `feat/llm-invocation-usage-ledger-20260825`；
2. 建立独立 worktree；
3. 将本 plan/progress 带入分支；
4. 记录 main、feature HEAD、V48、端口和测试数据库基线；
5. 实施过程中每次关键进展先更新 progress。

### Phase 1：配置、领域对象与 V49

新增或修改：

- `RagUsageProperties`，接入 `RagProperties`；
- `LlmInvocationPurpose`、`LlmInvocationOutcome`；
- `LlmUsageAttribution`、`LlmUsageSnapshot`、`LlmUsageEvent`；
- `V49__add_llm_usage_ledger.sql`；
- `application.yml`、`application-prod.yml`。

先完成配置 validation、usage normalization 和 cost calculator 的纯测试，再接入数据库。

### Phase 2：repository、recorder 与 retention

新增：

- `LlmUsageRepository`：单事件 insert、聚合、bounded cleanup；
- `LlmUsageRecorder`：`REQUIRES_NEW`、fail-open observability；
- `LlmUsageCostCalculator`；
- `LlmUsageRetentionJob`；
- 有界 `llmUsageRecorderExecutor`，拒绝策略不得回退到 provider 调用线程。

Repository 使用 `JdbcTemplate`，不新增 JPA entity；聚合 SQL 显式使用 UTC day
`(started_at AT TIME ZONE 'UTC')::date`。所有日期查询使用半开区间
`[from 00:00Z, to + 1 day 00:00Z)`。

### Phase 3：统一包装层埋点

修改：

- `ChatExecutionBudget.reserveModelCall()` 返回 ordinal，并保存 attribution；
- `BudgetedChatModel` 增加 model ref、purpose、streaming finalizer 和 recorder；
- `ModeAwareChatClientFactory` 为主调用、query transform、query expansion 传入明确 purpose；
- `ConversationSummaryService` 为 summary 传入 `SUMMARY`；
- `ChatExecutionService.newBudget(...)` 生成 logical execution ID 和 attribution。

非流式：

```text
validate prompt + reserve ordinal
  -> delegate.call
  -> record SUCCEEDED with usage
catch
  -> record FAILED without/raw-free error detail
finally
  -> exactly one terminal event
```

流式：

```text
subscription -> reserve ordinal
chunks       -> retain latest recognizable usage only for this invocation
complete     -> bounded recorder executor -> SUCCEEDED -> downstream complete
error        -> bounded recorder executor -> FAILED -> original downstream error
cancel       -> bounded recorder executor async -> CANCELLED
AtomicBoolean -> exactly one terminal event
```

前置 prompt/budget 拒绝不分配 invocation/event。provider 调用异常必须原样传播；recorder
异常被 recorder 自身吞掉并计 lost metric。`SUCCEEDED` 只表示该 `ChatModel` invocation
正常返回/完成，不表示后续 history commit、citation validation 或整个 Chat 请求成功。

### Phase 4：API 与授权

新增 API DTO、`LlmUsageService` 和 `LlmUsageController`：

- 复用 `ChatPrincipal.from(request)`；
- 日期和 principal scope 在 service/controller 边界显式验证；
- 通过注入 `Clock` 计算 UTC 默认日期和范围，避免测试依赖系统当前时间；
- root 与 database ADMIN 使用 `principal.admin()`；
- 普通 principal 的 scope 固定为自己；
- controller test 覆盖 400/403/empty/global/self；
- 更新 OpenAPI fixture 和双语 REST 文档。

### Phase 5：WebUI

新增：

- `src/api/usage.ts` typed client；
- `Metrics.tsx` durable usage query；
- 小型 summary/table 组件或在页面内的无嵌套布局；
- EN/ZH i18n；
- Vitest 和 Mock Playwright。

不把旧松散 metrics response 和新 durable usage response 合并成一个不稳定接口。

### Phase 6：专项验收脚本与长青文档

新增 `scripts/verify-llm-usage-ledger.sh`，聚合：

- focused unit/controller；
- PostgreSQL V1-V49 matrix；
- Maven compile/test；
- WebUI typecheck/Vitest/build/alignment/Mock Playwright；
- 隔离 `dev.sh`；
- real native/OpenAI-compatible Chat；
- KNOWLEDGE helper calls、AGENT tool loop、stream cancel 和 replay 的 DB 只读断言；
- docs/no-locks/diff gates。

稳定事实实施后同步：

- `project-context*`；
- `architecture*`；
- `configuration*`；
- `rest-api*`；
- `multi-model-external-config*`；
- `testing-guide*` / `developer-reference*`；
- `TODO*`。

## 7. 一次性验收矩阵

### 7.1 快速后端测试

一次性编写：

- `LlmUsageNormalizerTest`：
  null、`EmptyUsage`、全零流、部分字段、total 回退、负数和溢出保护；
- `LlmUsageCostCalculatorTest`：
  configured/legacy model、usage/pricing/cost availability 组合、NaN/Infinity、免费模型、
  scale、rounding、overflow、cost unit；
- `BudgetedChatModelTest`：
  preflight 拒绝不记账、call success/error、stream success/error/cancel、executor reject、
  exactly-once finalizer、ordinal；
- `ModeAwareChatClientFactoryTest`：
  CHAT、QUERY_TRANSFORM、QUERY_EXPAND purpose，AGENT 两轮形成两个 CHAT 事件；
- `ConversationSummaryServiceTest`：
  SUMMARY 事件、summary failure 仍记录 invocation；
- `LlmUsageRecorderTest`：
  repository failure fail-open、lost counter、日志参数不包含敏感正文；
- `LlmUsageControllerTest`：
  self/global/admin filter、普通 principal 403、日期范围、mode/purpose 和 stable ordering；
- 现有 Chat/OpenAI response usage contract 回归。

### 7.2 PostgreSQL 集成

新增 `LlmUsagePostgresIntegrationTest`，从空库执行 V1-V49，覆盖：

1. 表、约束、唯一键和索引；
2. `(logical_execution_id, call_ordinal)` 防重复；
3. success/failure/cancel 事件；
4. usage available/unavailable；
5. usage/pricing/cost availability 约束与多 cost unit 不混加；
6. UTC 日边界和 366 日查询；
7. self/global/model/mode/purpose/day 聚合；
8. stable principal rotation continuity；
9. 普通 Principal ACL 和 ADMIN/root；
10. bounded retention，事件删除不影响 history；
11. concurrent duplicate insert 只有一条；
12. V48 plaintext secret 约束和 shared quota 继续通过。

### 7.3 Chat 编排集成

使用可控 Mock `ChatModel` 和真实 Spring AI advisor：

1. PLAIN 一轮：1 个 CHAT event；
2. KNOWLEDGE + query transform + expand：辅助调用和最终 CHAT 分别计数；
3. AGENT 两轮工具调用：2 个 CHAT event，而最终 Chat response usage 仍保持兼容的最后一轮；
4. 首选 candidate 失败、fallback 成功：FAILED + SUCCEEDED 两条；
5. 应用级 retry：每次 `delegate.call` 独立 ordinal；
6. streaming 完成：1 条 SUCCEEDED；
7. streaming provider error：1 条 FAILED；
8. client cancel：1 条 CANCELLED，不提交 history；
9. summary compaction：额外 SUMMARY；
10. keyed first execution + replay：replay 不增加事件；
11. reclaim 重新执行：新 logical execution，事件增加；
12. recorder DB/executor failure：provider 只调用一次，Chat 仍完成，lost metric 增加；
13. provider 正常返回但后续 Chat 编排失败：invocation 仍为 SUCCEEDED，避免把业务结果混入
    model-call outcome。

### 7.4 API 与前端门槛

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

Mock Playwright 必须断言：

- `/metrics` 和 `/usage` 网络请求；
- summary、model/purpose rows、missing usage/pricing、cost disclaimer 的 DOM；
- Chat mode rows 和多 cost unit 不混加的 DOM；
- API error `role=alert`；
- 普通 Principal 页面不出现其他 principal selector；
- 不使用截图。

### 7.5 隔离真实全栈与真实 LLM

Mock 和 PostgreSQL 全部通过后，使用 `.env`、隔离端口和可处置数据库：

1. `scripts/dev.sh` 启动前后端，持续观察日志；
2. 真实 PLAIN 请求：DB invocation count 增加 1；
3. 真实 KNOWLEDGE 请求：使用同一 session 的第二轮 history-aware 问题，并显式启用
   Spring AI query transformer 和至少一个 query expansion variant；根据
   `executionBudget.modelCalls` 与 DB 同一 logical execution 的 invocation count 对齐，
   至少覆盖 query transform、query expand 和 final answer；
4. 真实 AGENT 请求：使用现有受控知识 fixture 和明确要求检索的 prompt；若 provider
   确实发起工具调用，DB 必须出现多条 CHAT invocation；若模型拒绝工具调用，记录为真实模型
   行为但不能用它替代 Mock 的确定性多轮覆盖；
5. 真实 native 与 OpenAI-compatible Chat 都进入同一 ledger；
6. 同一 Idempotency-Key replay 后 count 不增加；
7. 轮换 credential 后按 stable principal 查询累计值连续；
8. streaming 正常完成与客户端取消各验证一次；
9. `GET /usage`、WebUI DOM 和数据库只读聚合一致；
10. provider 不返回 usage 时 event 仍存在且 `usageAvailable=false`；
11. 最终停止 dev stack、释放隔离端口、销毁一次性数据库。

真实 LLM 验收不以“回答文本看起来合理”为通过条件，证据是网络 JSON、预算 metadata、
账本行、聚合 API、DOM 状态和日志中的无敏感低基数事件。

## 8. 安全、隐私、并发与成本边界

- stable principal 是唯一授权维度；session 和 trace 不能授权。
- 不保存原始 prompt/answer/tool/query/native usage。
- cost 只是配置估算，不宣称 provider invoice。
- recorder 使用独立短事务，不包围 provider 网络调用。
- 流式 recorder 使用有界 executor，禁止在 provider/Reactor 网络线程执行 JDBC。
- dedupe 使用唯一约束，不使用显式悲观锁。
- retention 使用 bounded conditional delete，不使用 `SKIP LOCKED`。
- API 最大 366 天，列表只返回低基数聚合。
- root/ADMIN 全局响应不返回 principal breakdown。
- model ref、mode、purpose、outcome、unit 都有长度和枚举约束，防止高基数污染。
- lost metric 不带 principal/session/model 动态 tag；model ref 只允许出现在脱敏日志字段。
- 真实测试不打印 `.env`、Authorization 或 API key。

## 9. 兼容、发布与回滚

- V49 additive；旧应用可忽略表，但不会写新事件。
- Chat/OpenAI response、SSE done usage 和 history metadata 不改变。
- 新 API 独立增加，不修改旧 `/metrics`。
- `rag.usage.enabled=false` 是运行时写入回滚开关；关闭后模型调用不写新 event，API 仍返回
  历史数据并明确返回 `recordingEnabled=false`。
- 回滚应用不删除 V49；重新启用后继续追加。
- cost 配置变化只影响新事件，历史事件保存调用时快照。
- cost unit 变化后 API 分 unit 返回，不把历史与新单位相加。
- retention 默认 400 天；修改前需确认 API 查询和审计要求。

## 10. 实施与交付顺序

```text
规划 3/3 + docs gate + commit/push main
  -> 最新 origin/main 创建隔离 feature worktree
  -> V49/config/domain tests
  -> repository/recorder/retention
  -> BudgetedChatModel 全调用路径埋点
  -> API/ACL
  -> WebUI
  -> 一次性专项测试与 runner
  -> 基本集成硬门槛
  -> 连续三轮实现审查
  -> fetch 并 merge 最新 origin/main
  -> 记录 merge 后验证基线
  -> PostgreSQL + Maven
  -> WebUI tsc/build/Mock Playwright
  -> 隔离真实全栈 + 真实 LLM
  -> 连续三轮 merge 后审查
  -> merge feature -> main
  -> push main + status clean
  -> 安全移除 feature worktree
```

合并 `origin/main` 后的测试必须重跑，不能沿用合并前结论。任何实质修复重置实现审查计数。

## 11. 规划与实现审查范围

规划审查固定为：

1. 价值、问题闭环、调用粒度、目标/非目标、默认值和旧方案替代关系；
2. Spring AI/代码调用链、V49、流式终态、usage/cost、ACL、fail-open 和并发可实施性；
3. API、WebUI、retention、测试、真实 LLM、发布、回滚和 Git 交付。

实现审查固定为：

1. exactly-once event、流式取消、dedupe、事务、retention 和数据约束；
2. stable principal ACL、usage/cost 语义、API/DOM 兼容和敏感数据边界；
3. Mock/PostgreSQL/真实 provider 证据、脚本清理、文档和 merge/push/worktree 交付。

只有影响正确性、成本安全、兼容性、隐私或数据一致性的缺陷触发修改和计数重置；风格和可选
优化不在收敛 review 阶段扩展。

## 12. 完成定义

只有以下条件全部满足才算实施完成：

1. V49 从空库迁移，schema/constraints/index/retention 由 PostgreSQL 集成证明；
2. 主回答、query transform、query expand、AGENT 多轮、summary、fallback/retry 和流式终态
   均由确定性自动化测试覆盖；
3. 一次实际 model call 只有一个 terminal event；replay 不增加事件；
4. recorder 失败不重复 provider 调用，lost metric 可观察；
5. stable principal/self/global/admin 权限和 model/mode/purpose/day 聚合由 HTTP +
   PostgreSQL 测试覆盖；
6. usage/pricing/cost availability 缺失和多 cost unit 不被伪造或错误合并；
7. WebUI typed usage、DOM/error/empty 状态、tsc/Vitest/build/Mock Playwright 通过；
8. `mvn clean compile test-compile`、全量 Maven、服务启动、no-locks/docs/diff 门禁通过；
9. 真实 LLM 验证覆盖 PLAIN、KNOWLEDGE、native/OpenAI、replay、rotation、stream；
10. 双语长青文档同步；
11. 实现连续三轮无实质修改；
12. 跟进最新 `origin/main` 后完整复验，特性分支合回并推送 `main`，最终工作区干净且
    feature worktree 已安全移除。

## 13. 停止时的规划检查状态

正式规划检查原定为：

1. 价值、问题闭环、调用粒度、目标/非目标、默认值和旧方案替代关系；
2. Spring AI/代码调用链、V49、流式终态、usage/pricing/cost、ACL、fail-open 和并发可实施性；
3. API、WebUI、retention、测试、真实 LLM、发布、回滚和 Git 交付。

第 1 轮于 2026-08-25 执行时发现上述 legacy `RagChatService` 公共入口漏覆盖问题，因此没有
增加连续无修改计数。用户随后要求停止该方向并保留为未来实施候选。本文未完成 `3/3`，
未创建 V49，未修改生产代码，也未运行实现验收。
