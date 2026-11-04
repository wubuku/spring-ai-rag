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
| Pool exhaustion | `Database unavailable:`, `hikaricp.connections.active` at max, `Connection is not available` | `hikaricp.connections.pending` > 0, `hikaricp.connections.active` = max; response 503 + `Retry-After: 5` |
| Slow query / lock wait | `Database query timed out:`, `canceling statement due to statement timeout` | Response 504 `DATABASE_TIMEOUT`; `pg_stat_activity` rows with `state=active` and `wait_event_type=Lock` |
| Database unreachable | `Connection refused` / `Could not open JPA EntityManager` | `/actuator/health` DOWN; requests 503 `DATABASE_UNAVAILABLE` |
| Upload rejected | `Upload rejected by multipart size limit` | 413 `PAYLOAD_TOO_LARGE`, no `Retry-After` |
| Upload storage unavailable | `Multipart temp storage failed` | 503 `UPLOAD_STORAGE_UNAVAILABLE` + `Retry-After: 5`; check the disk behind the multipart location |

### 4.1 Database Saturation And Unavailability

**Expected semantics** (measured 2026-11; see
[capacity-and-scale.md](capacity-and-scale.md#310-saturation-and-degradation-semantics-measured-2026-11)):

- Pool exhaustion: a request waits at most `DB_CONNECTION_TIMEOUT_MS` (10 s default)
  in the Hikari queue, then returns **503 `DATABASE_UNAVAILABLE`**,
  `X-RAG-Failure-Class: DEPENDENCY_UNAVAILABLE`, `Retry-After: 5`. It **does not become
  500 and does not hang forever**.
- Slow query / lock wait: cancelled by `DB_STATEMENT_TIMEOUT_MS` (30 s default) and
  returns **504 `DATABASE_TIMEOUT`**, `Retry-After: 1`.
- Database temporarily unreachable: also 503; when it returns, the pool reconnects
  **without an application restart**.
- Health: `/actuator/health` is DOWN because of the `db` indicator; Helm `readiness`
  removes the pod from the Service, while `liveness` contains only `livenessState` and
  **does not restart the pod**. The readiness probe timeout must exceed
  `DB_CONNECTION_TIMEOUT_MS` (Helm default 12 s vs 10 s).

**Triage order**:

1. Read the status: 503 means "dependency temporarily unavailable", 504 means "one
   statement timed out". Do not treat either as a 500.
2. Check `hikaricp.connections.active/pending`: pending growing means the pool is too
   small or connections are held.
3. Inspect `pg_stat_activity`: distinguish connection count, lock waits
   (`wait_event_type=Lock`) and genuinely slow queries.
4. When a slow query holds the connection, find the lock-holding session first. **Do not**
   blindly restart the app: that drops in-flight requests and does not release a
   database-side lock.
5. After the database recovers, confirm `/actuator/health` returns UP automatically. If
   it stays DOWN, check for non-retryable configuration such as wrong credentials.

**Do not** treat `DB_STATEMENT_TIMEOUT_MS=0` as a routine fix; it restores "slow query
holds the connection forever". Turn it off only for a maintenance window and restore it
afterwards.

## 5. Cross-Thread And Scheduling

- `@Async` and dependency call pools **inherit the submitting thread's traceId** and restore it on exit, so pooled threads never leak context.
- Each `@Scheduled` run gets `traceId=job-*` and `job=scheduled`; cleanup/indexing logs of one run share that traceId.
- In-request SSE events carry `traceId`, so client bug reports can hand it back directly.

## 6. Related Tests (Must Stay Green)

- `ObservabilityDiagnosisTest`: injects an upstream 429 and asserts one log line plus metric tags identify "who / which document / which profile / which upstream / retryable".
- `MdcContextTest`: context propagation and cleanup across async pools and scheduled runs.
- `RagMetricsServiceTest`: `outcome` separates slow vs. wrong; high-cardinality label guard.
