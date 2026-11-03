# 可观测性 Runbook：出问题先看什么

> 一句话：**traceId 串日志，指标看趋势**。traceId / sessionId / principalId **只进日志与响应，不进指标标签**（避免时间序列爆炸）。

## 1. 30 秒定位顺序

1. 取 traceId：HTTP 响应头 `X-Trace-Id`；SSE 的 `done` 事件与流内 `traceId`。
2. 按 traceId 查日志：`grep '<traceId>'`；prod profile 是 JSON，可直接按 `traceId` 字段过滤。
3. 再按 traceId 找到同一轮的上游失败日志，确认 `operation / provider / reason / retryable`。
4. 用指标判断影响面与类型：是「慢」还是「错」，是单上游还是全链路。

```bash
# JSON 日志（prod/test profile）
kubectl logs deploy/spring-ai-rag | jq -c 'select(.traceId=="<traceId>")'
# 纯文本日志（default/dev/postgresql profile）
grep '<traceId>' app.log
```

## 2. 日志字段语义

| 字段 | 来源 | 用来回答 |
|------|------|----------|
| `traceId` | 请求头/自动生成；调度任务为 `job-*` | 同一轮请求/任务的所有日志 |
| `spanId` | `rag.tracing.span-id-enabled` | 同一 trace 内的子调用 |
| `principalId` | 认证后的稳定身份（`db:123` 等） | **谁** |
| `sessionId` / `domainId` | Chat 会话 / Collection | 哪个会话、哪个集合 |
| `operation` | 固定调用点（`chat`、`chat.stream`、`embedding.document`、`embedding.job`、`retrieval.embedding`） | 失败发生在哪一步 |
| `provider` | 模型候选 ref 或 Embedding provider | **哪个上游** |
| `profile` / `profileId` | Embedding Profile | **哪个向量空间** |
| `documentId` / `jobId` | 文档与嵌入任务 | **哪个文档/任务** |
| `attempt` | 嵌入任务尝试次数（Chat 日志用 `n/count`） | **重试几次** |
| `reason` / `retryable` | `FailureDetails` 分类 | 失败类型、能否重试 |

典型日志：

```text
Embedding provider failure operation=embedding.document provider=siliconflow profile=bge-m3-1024 documentId=42 reason=UPSTREAM_RATE_LIMITED retryable=true
Embedding job attempt failed operation=embedding.job provider=siliconflow jobId=... documentId=42 profileId=7 attempt=2 reason=UPSTREAM_TIMEOUT retryable=true
Streaming chat attempt failed operation=chat.stream provider=primary traceId=... reason=UPSTREAM_TIMEOUT retryable=true
```

## 3. 指标：先分清「慢」和「错」

| 指标 | 标签 | 用途 |
|------|------|------|
| `rag.requests.total` / `.success` / `.failed` | — | 总量、成功率 |
| `rag.response.time` | `outcome=success\|failure` | 延迟直方图；**成功慢**与**失败**分开看 |
| `rag.upstream.failures` | `operation`、`provider`、`reason`、`retryable` | 上游失败分类，定位具体原因 |
| `rag.dependency.calls` | `kind`、`outcome=success\|timeout\|cancelled\|rejected\|error` | 依赖调用量、超时/取消/拒绝 |
| `rag.admission.active` / `.rejected` | `kind` | 并发占满与拒绝（队列积压前兆） |
| `rag.integration.observation.queue.depth` / `.dropped` | — | 观测队列积压与丢弃 |
| `rag.cache.embedding.hit` / `.miss` / `.size` | — | 语义/向量缓存命中 |
| `rag.model.calls.total` / `.errors.total` / `.latency` | 模型相关有界标签 | 模型调用量、错误与延迟 |
| `rag.document-processing.active` / `.stuck` / `.runs` | 阶段相关 | 索引进度与卡住任务 |

**高基数红线**：`traceId`、`spanId`、`sessionId`、`principalId`、`requestId`、`query`、`documentId` 不得作为指标标签；需要时从日志关联。`RagMetricsServiceTest#metricLabelsStayLowCardinality` 会拦住回退。

## 4. 常见失败对照

| 现象 | 日志关键字 | 指标 |
|------|------------|------|
| 上游超时 | `reason=UPSTREAM_TIMEOUT` + `retryable=true` | `rag.upstream.failures{reason="UPSTREAM_TIMEOUT"}`、`rag.dependency.calls{outcome="timeout"}` |
| 上游 429 | `reason=UPSTREAM_RATE_LIMITED` | `...{reason="UPSTREAM_RATE_LIMITED"}`；观察 `Retry-After` |
| 配额/预算拒绝 | `RAG business error: [<code>]`，无上游调用 | `rag.admission.rejected{kind}`、`rag.requests.failed` |
| 租户越权 | `RAG business error: [FORBIDDEN]` / `[ACCESS_DENIED]`，日志带 `principalId` | `rag.integration.requests{status="403"}`（如已开启集成观测） |
| 索引失败 | `Embedding job attempt failed ... jobId/documentId/profileId/attempt` | `rag.upstream.failures{operation="embedding.job"}`、`rag.document-processing.stuck` |
| 检索变慢 | `operation=embedding.retrieval` 耗时日志 | `rag.advisor.*.duration`、`rag.slow_query.duration`、`rag.response.time{outcome="success"}` |

## 5. 跨线程与调度

- `@Async` 与依赖调用线程池会**继承提交方的 traceId**；线程池线程退出时还原，不串号。
- `@Scheduled` 每轮生成 `traceId=job-*`，并带 `job=scheduled`；同一轮清理/索引日志可按此 traceId 串联。
- 请求内 SSE 事件携带 `traceId`，前端报障时可直接回传。

## 6. 相关测试（改动必须保持绿）

- `ObservabilityDiagnosisTest`：模拟上游 429，断言一条日志 + 指标即可定位「谁 / 哪个文档 / 哪个 profile / 哪个上游 / 能否重试」。
- `MdcContextTest`：`@Async`/依赖线程池/调度任务的上下文传播与清理。
- `RagMetricsServiceTest`：`outcome` 标签区分慢与错；高基数标签守卫。
