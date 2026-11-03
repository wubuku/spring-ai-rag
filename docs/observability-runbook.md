# Observability Runbook: What To Check First

> One line: **traceId joins logs; metrics show trends**. `traceId` / `sessionId` / `principalId` stay in logs and responses, **never in metric labels** (to avoid time-series explosion).

## 1. First 30 Seconds

1. Get the trace ID: HTTP response header `X-Trace-Id`; SSE `done` event and stream payload `traceId`.
2. Query logs by trace ID: `grep '<traceId>'`; the `prod` profile emits JSON, so filter the `traceId` field.
3. Find the upstream failure log with the same trace: confirm `operation / provider / reason / retryable`.
4. Use metrics to size the impact: slow vs. failing, one provider vs. whole pipeline.

```bash
# JSON logs (prod/test profile)
kubectl logs deploy/spring-ai-rag | jq -c 'select(.traceId=="<traceId>")'
# Plain-text logs (default/dev/postgresql profile)
grep '<traceId>' app.log
```

## 2. Log Field Semantics

| Field | Source | Answers |
|-------|--------|---------|
| `traceId` | incoming header or generated; scheduled runs use `job-*` | All logs of one request/job |
| `spanId` | `rag.tracing.span-id-enabled` | Sub-calls inside one trace |
| `principalId` | Stable identity after authentication (`db:123`, etc.) | **Who** |
| `sessionId` / `domainId` | Chat session / Collection | Which session and collection |
| `operation` | Fixed call site (`chat`, `chat.stream`, `embedding.document`, `embedding.job`, `retrieval.embedding`) | Which step failed |
| `provider` | Chat model candidate ref or Embedding provider | **Which upstream** |
| `profile` / `profileId` | Embedding Profile | **Which vector space** |
| `documentId` / `jobId` | Document and embedding job | **Which document/job** |
| `attempt` | Embedding attempt count (`n/count` in chat logs) | **How many retries** |
| `reason` / `retryable` | `FailureDetails` classification | Failure type and retryability |

Typical lines:

```text
Embedding provider failure operation=embedding.document provider=siliconflow profile=bge-m3-1024 documentId=42 reason=UPSTREAM_RATE_LIMITED retryable=true
Embedding job attempt failed operation=embedding.job provider=siliconflow jobId=... documentId=42 profileId=7 attempt=2 reason=UPSTREAM_TIMEOUT retryable=true
Streaming chat attempt failed operation=chat.stream provider=primary traceId=... reason=UPSTREAM_TIMEOUT retryable=true
```

## 3. Metrics: Separate "Slow" From "Wrong"

| Metric | Labels | Purpose |
|--------|--------|---------|
| `rag.requests.total` / `.success` / `.failed` | — | Volume and success rate |
| `rag.response.time` | `outcome=success\|failure` | Latency histogram; **slow success** vs **failure** |
| `rag.upstream.failures` | `operation`, `provider`, `reason`, `retryable` | Bounded failure classification |
| `rag.dependency.calls` | `kind`, `outcome=success\|timeout\|cancelled\|rejected\|error` | Call volume, timeouts, cancellations, rejections |
| `rag.admission.active` / `.rejected` | `kind` | Saturation and rejection (queue backlog precursor) |
| `rag.integration.observation.queue.depth` / `.dropped` | — | Observation queue backlog and drops |
| `rag.cache.embedding.hit` / `.miss` / `.size` | — | Semantic/vector cache hit rate |
| `rag.model.calls.total` / `.errors.total` / `.latency` | bounded model labels | Model volume, errors, latency |
| `rag.document-processing.active` / `.stuck` / `.runs` | stage labels | Indexing progress and stuck jobs |

**High-cardinality red line**: never use `traceId`, `spanId`, `sessionId`, `principalId`, `requestId`, `query`, or `documentId` as metric labels; correlate through logs instead. `RagMetricsServiceTest#metricLabelsStayLowCardinality` guards this.

## 4. Failure Cheat Sheet

| Symptom | Log keywords | Metrics |
|---------|--------------|---------|
| Upstream timeout | `reason=UPSTREAM_TIMEOUT` + `retryable=true` | `rag.upstream.failures{reason="UPSTREAM_TIMEOUT"}`, `rag.dependency.calls{outcome="timeout"}` |
| Upstream 429 | `reason=UPSTREAM_RATE_LIMITED` | `...{reason="UPSTREAM_RATE_LIMITED"}`; check `Retry-After` |
| Quota/budget rejection | `RAG business error: [<code>]`, no upstream call | `rag.admission.rejected{kind}`, `rag.requests.failed` |
| Tenant forbidden | `RAG business error: [FORBIDDEN]` / `[ACCESS_DENIED]`, log carries `principalId` | `rag.integration.requests{status="403"}` (when integration observation is enabled) |
| Indexing failure | `Embedding job attempt failed ... jobId/documentId/profileId/attempt` | `rag.upstream.failures{operation="embedding.job"}`, `rag.document-processing.stuck` |
| Retrieval slow | `operation=embedding.retrieval` latency logs | `rag.advisor.*.duration`, `rag.slow_query.duration`, `rag.response.time{outcome="success"}` |

## 5. Cross-Thread And Scheduling

- `@Async` and dependency call pools **inherit the submitting thread's traceId** and restore it on exit, so pooled threads never leak context.
- Each `@Scheduled` run gets `traceId=job-*` and `job=scheduled`; cleanup/indexing logs of one run share that traceId.
- In-request SSE events carry `traceId`, so client bug reports can hand it back directly.

## 6. Related Tests (Must Stay Green)

- `ObservabilityDiagnosisTest`: injects an upstream 429 and asserts one log line plus metric tags identify "who / which document / which profile / which upstream / retryable".
- `MdcContextTest`: context propagation and cleanup across async pools and scheduled runs.
- `RagMetricsServiceTest`: `outcome` separates slow vs. wrong; high-cardinality label guard.
