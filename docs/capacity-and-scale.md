# Capacity And Scale Degradation

> This round validates behavior **after the corpus grows**. The goal is not absolute
> numbers but the **degradation curve**: does latency grow linearly when data doubles,
> and are there N+1 queries or full table scans? Reproduce everything with
> `scripts/run-scale-benchmark.py`.

## 1. Measurement Setup

- Corpus: `scale-bench-1000` / `scale-bench-2000` / `scale-bench-3000` /
  `scale-bench-5000`. The largest single collection is **5,000 docs / 50,000 chunks**;
  together with the base corpus the database held about
  **11.2k docs / 112k vectors** during the final run.
- Vectors: deterministic fake embeddings (1024 dimensions, 32 normalized vectors in
  rotation); no real model calls.
- Environment: Apple M4 Pro / 64 GB, PostgreSQL 16.8 + pgvector 0.8.2.
- Requests hit the real HTTP endpoints. Each request includes one mock embedding call
  (~30–50 ms, independent of corpus size), so compare **ratios**, not absolutes.
- The scale-up run used `requests=10`, `warmup=2` (p95 has only 10 samples and is
  indicative); the earlier 1k→2k run used `requests=50`, `warmup=8`. Conclusions rely
  on p50 plus SQL execution plans.

## 2. Reproduce

```bash
# 1) Start mock embeddings + the app (placeholder credentials)
node scripts/mock-llm-server.js --port 18096
SPRING_PROFILES_ACTIVE=postgresql \
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/spring_ai_rag_scale \
RAG_EMBEDDING_BASE_URL=http://127.0.0.1:18096 RAG_EMBEDDING_API_KEY=dummy \
RAG_ROOT_API_KEY='32-ascii-chars-placeholder-key-000' \
java -jar demos/demo-basic-rag/target/demo-basic-rag-1.0.0.jar --server.port=18095

# 2) Seed and benchmark (bounded: docs hard-capped at 5000)
python3 scripts/run-scale-benchmark.py --ack-write \
  --dsn 'postgresql://postgres:postgres@localhost:5432/spring_ai_rag_scale' \
  --base-url http://127.0.0.1:18095 \
  --api-key '32-ascii-chars-placeholder-key-000' \
  --docs 3000 --scale-docs 5000 --chunks-per-doc 10 \
  --requests 10 --warmup 2 --output /tmp/scale.json

# 3) Import throughput only: add --skip-http
#    TTL only: stop the app, then use --seed-ttl-rows 10000
# 4) Remove fixtures: add --cleanup
```

The script only writes `scale-bench-*` collections; `--ack-write` is the destructive
write guard.

## 3. Results

### 3.1 Bulk Import And Vector Writes

| Phase | 1k docs / 10k chunks | 2k docs / 20k chunks | ×2 ratio |
|-------|----------------------|----------------------|----------|
| Document insert | 74.9 ms (13.3k/s) | 64.8 ms (30.9k/s) | 0.87x |
| Chunk insert | 2.31 s (4.3k/s) | 3.75 s (5.3k/s) | 1.63x |
| Vector insert (incl. HNSW maintenance) | 10.7 s (937/s) | 21.0 s (952/s) | 1.97x |
| `ANALYZE` | 5.45 s | 6.52 s | 1.20x |
| Total | 18.5 s | 31.4 s | 1.70x |

Document/chunk inserts scale **sublinearly** (batch amortization). Vector writes scale
near-linearly at a stable **~950 vectors/s** with the HNSW index present; insertion is
the dominant cost.

### 3.2 HNSW Index Build

- 31k vectors: `CREATE INDEX CONCURRENTLY ... USING hnsw` takes **4.7 s**
  (~157 µs/vector); `DROP INDEX CONCURRENTLY` 26 ms.
- On startup `EmbeddingProfileIndexManager` waits for a valid index (60 s cap). A missing
  or invalid index blocks startup; this is the first capacity boundary operators feel at
  scale or with multiple replicas.

### 3.3 Retrieval Latency And Degradation

| Endpoint (p50 / p95, ms) | 3k docs (30k chunks) | 5k docs (50k chunks) | p50 ratio |
|--------------------------|----------------------|----------------------|-----------|
| Vector-only search | 105.2 / 163.2 | 101.7 / 455.4 | 0.97x |
| Hybrid search | 150.8 / 206.7 | 203.1 / 277.5 | 1.35x |
| Fulltext-weighted search | 143.5 / 164.0 | 211.0 / 252.2 | 1.47x |
| Document list (first/deep) | 36.4 / 54.6 · 19.2 / 36.7 | 17.5 / 26.7 · 23.8 / 36.1 | 0.48x / 1.24x |
| Collection list | 7.1 / 11.4 | 5.5 / 14.8 | 0.78x (tiny absolute) |
| Document-list keyword (trigram index) | not measured | 4.2 / 6.0 | — |
| Document-list keyword (no index) | not measured | 8.4 / 21.1 | — |
| Streaming export | 82.3 / 92.4 | 58.6 / 59.1 | 0.71x |
| Evaluation | 7.9 / 11.4 | 5.5 / 6.4 | 0.70x |

The earlier 1k→2k result remains in git history as the baseline: after the HNSW
candidate CTE fix, the 255→451 ms p50 linear degradation disappeared. In this
3k→5k run (1.67x documents) every p50 ratio is below 1.47x, and export/list p50
even dropped with batching/cache effects. The 455 ms vector p95 is a single cold
query among 10 samples; p50 stayed flat.

The document-list `title` keyword filter was verified separately: `V73` creates
`idx_rag_documents_title_trgm` (`GIN (lower(title) gin_trgm_ops)`) when `pg_trgm`
exists. At 5k docs the same query moved from `Seq Scan` (2.134 ms) to
`Bitmap Index Scan` (0.103 ms); HTTP p50 dropped from 8.4 ms to 4.2 ms. After a
bulk import run `VACUUM ANALYZE rag_documents`, otherwise new trigram entries sit
in the GIN pending list and queries may still scan sequentially — the benchmark
seed flow now performs this step.

**Key finding (fixed)**: the original vector SQL put `rag_embeddings`, the freshness
state table, and `rag_documents` into one `ORDER BY <=> LIMIT` query. The planner could
not use the partial HNSW index and degraded to "scan every vector in scope, then sort",
so p50 grew 255 ms → 451 ms when the corpus doubled (≈linear). The fix uses a
**MATERIALIZED candidate CTE**: HNSW selects candidates inside a CTE that only touches
`rag_embeddings` (10x overfetch, minimum 100, maximum 1000), then the outer query applies
freshness joins and the final ordering. p50 is now stable at 50–60 ms with a ~1.1x
doubling ratio.

`resolveEmbeddingProfiles` also stopped scanning `rag_documents` when the authorized
scope is a specific collection; it reads `rag_collection.active_embedding_profile_id`
directly.

### 3.4 List N+1s (Fixed)

- Collection list: previously one `countByCollectionId` per collection (20 per page);
now one `GROUP BY collection_id` query.
- Document list: previously one chunk-count query and one integrity snapshot per
document; now one grouped count plus one batched `inspectIds` call per profile group.
- Processing summaries: `summaryForDocument` used to query per document (generation,
current run, latest run, active job). `summariesForDocuments` now groups the page by
embedding profile and issues **4 batched queries** (`findCurrentGenerations`,
`findExactBatch`, `findLatestPerDocument`, `findCurrentActiveBatch`) with the same
semantics as the single-document reader. The only remaining per-document write is
`ensureBridgeForJob` when there is no run but an active job exists; it is bounded by
active jobs and is normally zero in steady state.

### 3.5 Streaming Export (Fixed)

- The old implementation loaded the whole collection with `findAllByCollectionId`
before serializing, so memory grew linearly with document count and could not be
released before the response started.
- `CollectionExportService` now reads with a **primary-key cursor**
`id > afterId ORDER BY id LIMIT 200` and writes JSON while reading. The payload is
byte-compatible with `CollectionExportResponse`, so existing clients need no change.
- Memory bound = 200 rows + the current `JsonGenerator` buffer, **independent of the
  total number of rows**. `documentCount` is captured by `countByCollectionId` before
  streaming and is a start-time snapshot: concurrent deletes can make the emitted
  document list slightly shorter, but never cause an error or an infinite loop.
- New Flyway `V72__document_export_keyset_index.sql` adds a `(collection_id, id)`
composite index. Without it, each batch walks the primary key and discards rows from
other collections (measured: 10 ms for the first batch, 6,200 rows discarded). With
it, later batches take 0.37 ms and read 20 buffers.
- Response headers for benchmarks and operations:
`X-Rag-Export-Batch-Size: 200`, `X-Rag-Export-Documents`,
`X-Rag-Export-Planned-Batches = ceil(documentCount / 200)`.
- Measured: 3k docs / 1.63 MB / 15 batches, p50 82.3 ms; 5k docs / 2.73 MB /
25 batches, p50 58.6 ms. The `measure_export` path in
`scripts/run-scale-benchmark.py` **streams the response to disk** and asserts batch
count and JSON document count; the script exits non-zero on assertion failure.

### 3.6 TTL Cleanup

- 10,000 expired rows (100 sessions): candidate session query 4.2 ms, batched DELETE
**287 ms** (~29 µs/row).
- Cleanup advances in session batches (50) and row batches (500); no single unbounded
delete, and no lock contention or long transactions were observed.

### 3.7 Cross-Browser Verification

- `playwright.config.ts` now has `firefox` and `webkit` projects that run only the
  safety suite (`accessibility.spec.ts`, `keyboard.spec.ts`, `responsive.spec.ts`).
  Other business specs stay Chromium-only because they depend on mock timing and
  cross-engine reruns add little. This is an explicit `testMatch`, not a silent skip.
- 61 tests per engine, 182 total, all passing. The only WebKit difference is platform
  behavior: a mouse click does not focus a button. The keyboard case now calls
  `focus()` and then presses Enter, testing the real keyboard path; the product focus
  policy is unchanged.
- Mock navigation moved from `networkidle` to `domcontentloaded`: background polling
  can prevent `networkidle` forever, which flaked under three-engine parallelism.
  Readiness is still asserted through URL and element expectations.
- WebKit occasionally wedges after roughly 50 contexts in one browser process while
  running the 61-test safety suite: `goto` times out and `browserContext.close`
  times out too. Chromium / Firefox never show this, and the same test passes in
  isolation or when the responsive suite runs alone. `openProtectedPage` retries the
  WebKit navigation once on a fresh Page, which recovers some cases; if the whole
  context is wedged it rethrows the original error and lets the Playwright test-level
  retry (`retries=2` in CI) recover, without masking the root error. The WebKit
  project allows 20s per navigation and a 90s test timeout.
- Conclusion: this is a Playwright + WebKit browser-process / navigation stability
  boundary, not a page defect. Nothing is silently skipped: the tests still run, and
  a failure surfaces explicitly as flaky in the report. If an environment cannot
  launch WebKit, add an explicit `test.skip` with the reason and keep results from the
  other engines.

### 3.8 WebUI at Scale (measured this round)

Environment: production build (`npm run build`) behind a plain reverse proxy
(**no gzip**, i.e. the worst-case transfer), Chromium at 1440×900; data was
8000 documents / 80k vectors (two collections of 3k and 5k) plus 2000 evaluation
history rows; search and chat used mock embedding / mock chat.

- **Document list (pagination bug found and fixed)**: the WebUI sent `page`/`size`
  while the backend only understands `offset`/`limit`, so every page after the
  first returned the same newest 20 rows. Before the fix, `page=0` and `page=399`
  both started with id 8000; after the fix, `page=399` starts with the oldest
  167/202. The page still renders only 20 rows (510 DOM nodes, 9.5 MB JS heap);
  a page turn is ready in ~83 ms.
- **Collection list**: the page pinned its index to 0, so collections beyond the
  first 20 were unreachable. A shared `Pager` plus `?page=` now exposes them; the
  request remains 20 rows per page.
- **Evaluation history**: 2000 rows are rendered 20 at a time; it also pinned
  page 0, so pagination was added (feedback history included).
- **Search**: the UI sends no `limit`, so the default 10 applies; hybrid retrieval
  on 5k documents took 374 ms and 815 ms end to end. A 200-result injection
  through the real render path took 53–70 ms, 1188 nodes, with no heap growth —
  no virtual list is needed while the UI stays bounded at 10.
- **Chat**: one live retrieval returned 4 citation chips; the 1.5 s end-to-end is
  dominated by retrieval and the model, not rendering.
- **Export (wired into the UI, streamed to disk)**: each Collection card now has
  an Export action built on `fetch` + `ReadableStream`, consuming and writing
  chunk by chunk. Browsers that expose the File System Access API (Chrome /
  Edge) write straight to the file the user picks, so memory stays independent
  of collection size; browsers without a write API (Firefox / Safari) fall back
  to a Blob, which is a platform boundary rather than an implementation choice.
  Progress reports bytes **already written**: the response is chunked and has no
  `Content-Length`, so the UI shows bytes downloaded and only shows a ratio
  when a total is available. Cancel aborts the read through `AbortController`
  and discards received chunks, so no partial file is produced. A 5k collection
  measured 2.73 MB, 12–13 ms to first byte and 75–78 ms total. Mock E2E drives
  an 8 MB chunked stream and asserts progress grows before the stream ends and
  that cancelling produces no download.

Frontend performance budget vs measured (re-measured by the gate in 2026-11):

| Metric | Budget | Measured (2026-11) | Notes |
|--------|--------|--------------------|-------|
| Initial resources (gzip) | ≤160 KB | 130.4 KB | index 115.6 + jsx 5.1 + Query 4.6 + CSS 4.1 + runtime; **29.6 KB (18.5%) headroom** |
| Initial resources (uncompressed) | ≤500 KB | 403.8 KB | worst case, proxy without gzip; 96.2 KB headroom |
| Critical resource count | ≤8 | 6 | script + 3 modulepreloads + 1 stylesheet + runtime |
| Local FCP | ≤300 ms | 40 ms | re-measured by the gate |
| Slow 4G FCP (1.6 Mbps / 150 ms) | ≤3.0 s | 2.44 s | no gzip; **560 ms (18.7%) headroom** |
| Document list ready | ≤1.5 s | 1.48 s (unlock + 4 APIs) | page turn 83 ms |
| Search end to end (10 rows) | ≤1.0 s | 815 ms | retrieval 374 ms |
| 200 synthetic rows render | ≤100 ms | 53–70 ms | render path only, not a UI-reachable cap |
| Export first byte | ≤100 ms | 12–13 ms | streamed response |

**First-screen composition** (read from `dist/index.html` by the gate, 2026-11):

| Resource | Raw | Gzip | Main content |
|----------|-----|------|--------------|
| `index-*.js` | 358.7 KB | 115.6 KB | React DOM ~174 KB, i18next 41 KB, react-router 38 KB, EN+ZH locale JSON ~66 KB, react-query core ~15 KB, Layout/App/auth/design-system ~25 KB |
| `jsx-runtime-*.js` | 12.8 KB | 5.1 KB | React JSX runtime |
| `QueryClientProvider-*.js` | 13.5 KB | 4.6 KB | TanStack Query provider and shared primitives |
| `createLucideIcon-*.js` | 0.9 KB | 0.6 KB | Lucide icon factory (only used icons load first) |
| `index-*.css` | 17.3 KB | 4.1 KB | design tokens, global styles, first-screen component CSS |
| `rolldown-runtime-*.js` | 0.7 KB | 0.4 KB | chunk loading runtime |
| **Total** | **403.8 KB** | **130.4 KB** | |

Resources intentionally **not** on the first screen (check these before adding a
new first-paint dependency):

- `client-*.js` 64.6 KB (axios + retry + auth request stack): loaded on unlock
  or the first page request; first paint only imports the `BASE_URL` constant
  from `src/api/baseUrl.ts`. This split is what moved gzip from 156.7 KB to
  130.4 KB this round.
- `useChartTheme-*.js` 350.5 KB (recharts): loaded only by the metrics / ABTest
  pages.
- Every other page and `Unlock` are route-level `lazy` chunks; `Unlock` changed
  from a static import to lazy this round.

These numbers are no longer hand-recorded: `npm run check:performance-budget`
runs [`performance-budget-self-test.mjs`](../spring-ai-rag-webui/scripts/test-support/performance-budget-self-test.mjs),
then [`check-performance-budget.mjs`](../spring-ai-rag-webui/scripts/check-performance-budget.mjs)
rebuilds the app, reads the initial resources from `dist/index.html`, computes
gzip/uncompressed size and resource count, then measures local and Slow 4G FCP
with Playwright Chromium against a local static server (median of 3 runs). The
backend-free CI job runs this command and fails on any overage; budgets live in
[`performance-budget.json`](../spring-ai-rag-webui/scripts/testdata/performance-budget.json).
The budget was not raised; the headroom comes from splitting first-screen
dependencies. New first-paint dependencies must be checked against the table
above and should be lazy-loaded or split unless they are genuinely required.

Not covered: mock embedding/chat does not represent real model latency; the proxy
did not enable gzip; only Chromium was measured.

### 3.9 SSE Concurrent Connection Resource Boundaries (measured 2026-11)

Reproduce: `python3 scripts/verify-sse-concurrency.py --burst 20` (real
PostgreSQL + real Spring Boot process + mock provider, ports 18088/18089,
bounded concurrency; the script refuses `--burst > 64`).

**Limits come from configuration, not hard-coding**

| Key | Default | Effect |
|-----|---------|--------|
| `rag.admission.max-requests` | 64 | Total requests admitted per instance (an SSE stream holds one slot for the whole turn) |
| `rag.admission.max-requests-per-principal` | 16 | Concurrent requests for one API key / principal |
| `rag.sse.heartbeat-interval-seconds` | 30 | SSE comment heartbeat interval |
| `rag.timeout.chat-stream-ms` | 180000 | Streaming execution budget; now also the `SseEmitter` async timeout |
| `rag.timeout.read-timeout-ms` | 60000 | Read timeout for non-streaming RestClient; streaming uses WebClient, so this does not backstop it |

The heartbeat scheduler is **two shared daemon threads**
(`RagChatController.SSE_HEARTBEAT_SCHEDULER`), not one scheduler per connection.
Tomcat's default 200 threads is not the first limit reached.

**Measured (one API key, default 16 per principal)**

- Burst of 20 SSE requests: **16 HTTP 200 streams established and kept
  streaming**, **4 HTTP 429** with `code=TOO_MANY_REQUESTS` and
  `Retry-After: 1`; **no queueing** — request 17 is rejected immediately.
  Reaching the global 64 requires multiple API keys; only the single-key 16 was
  measured this round.
- Peak `rag.admission.active{kind=request}` = **16**; it returns to **0** after
  all streams complete or disconnect.
- Threads: baseline 79 → peak 91 (+12); heartbeat threads stay at **2** (the old
  implementation used 16 — one single-thread `ScheduledExecutorService` per
  connection). The rest of the delta is Tomcat NIO pool expansion, which
  remains in the pool afterwards; that is pool semantics, not a per-connection
  leak.
- Memory: process RSS 820.6 MB → 823.6 MB peak (**+3.0 MB** for 16 connections,
  roughly <200 KB per connection including socket and async request state;
  RSS includes JVM heap noise, so use it as an order of magnitude only). It
  fell back to 779.0 MB afterwards.
- Client disconnect (close the socket right after the first `content`): the
  admission lease returns to 0 within ~1.8 s; the heartbeat task is cancelled
  in `onCompletion` and leaves no residue.
- Idle upstream (mock sends headers then no token for 9 s): with
  `chat-stream-ms=6 s`, the stream ends at **6.02 s** with an `error` event and
  the lease returns to 0. The default is 180 s. Heartbeats keep the client
  connection alive through proxies but do not extend the upstream lifetime.

**Two leaks fixed this round**

1. One single-thread scheduler per SSE connection → one shared 2-thread pool:
   16 concurrent streams went from 16 heartbeat threads to 2.
2. `SseEmitter.onCompletion` cancelled the model subscription but not the
   heartbeat task → `heartbeat.stop()` added; the emitter timeout changed from
   `0` (never) to `rag.timeout.chat-stream-ms`, so a silent upstream cannot
   hold the connection, upstream socket and admission slot forever.

### 3.10 Saturation and Degradation Semantics (measured 2026-11)

Reproduce: `python3 scripts/verify-resource-saturation-e2e.py` (disposable pgvector
container + real Spring Boot process + mock provider; `DB_POOL_SIZE=2`,
`DB_CONNECTION_TIMEOUT_MS=1500`, `DB_STATEMENT_TIMEOUT_MS=3000` shrink the wait window
to a measurable size).

| Scenario | Measured semantics |
|----------|--------------------|
| Pool exhaustion (two requests occupy the pool) | The third request returns **503 `DATABASE_UNAVAILABLE`** after **1.51 s**, `X-RAG-Failure-Class: DEPENDENCY_UNAVAILABLE`, `Retry-After: 5`; **bounded queueing, not 500 and not a hang** |
| Slow query / lock wait | A single request is cancelled by `statement_timeout` at **3.02 s** with **504 `DATABASE_TIMEOUT`**, `X-RAG-Failure-Class: TIMEOUT`, `Retry-After: 1` |
| Recovery after release | No restart needed; requests return 200, health is UP, `hikaricp.connections.active=0` (no leak) |
| Database temporarily unreachable | **1.51 s** returns 503 `DATABASE_UNAVAILABLE`; `/actuator/health` is DOWN |
| Database back | Recovers automatically without a restart: 200 and health UP; no `Apparent connection leak detected` in the log |

**Health split**: the Helm `liveness` group contains only `livenessState`, so database
saturation/unavailability never triggers a restart; the `readiness` group includes `db`,
so exhaustion or an unreachable database turns readiness DOWN and removes the pod from
the Service. The readiness probe `timeoutSeconds` must exceed
`DB_CONNECTION_TIMEOUT_MS` (10 s default); a probe aborted early leaves a waiter inside
Hikari and makes saturation worse.

**Sustained concurrency + long transactions + client aborts (re-measured 2026-11)**

Scenario 3 of the same script: 6 threads hit `documents` continuously for 8 s while `psql`
repeatedly runs `BEGIN; LOCK TABLE rag_documents ACCESS EXCLUSIVE; pg_sleep(1.5); ROLLBACK;`,
plus 39 client aborts that close the socket immediately after sending the request.

- 1,933 requests and 39 aborts: only 200 / 429 / 503 / 504, **zero 500s**, nothing hung
  beyond 20 s;
- requests recover to 200 after the long transactions, `hikaricp.connections.active ≤ 2`
  (pool size 2), and `pg_stat_activity` shows no `idle in transaction`;
- no `Apparent connection leak detected` in the log.

**Fixes this round**:

1. Pool exhaustion arrives as `CannotCreateTransactionException`, which previously
   bypassed the `DataAccessException` handler and became a generic 500
   (`Could not open JPA EntityManager for transaction`). It now shares the same
   classifier as an unreachable database and returns 503 + `Retry-After`.
2. `connection-init-sql: SET statement_timeout` bounds lock waits and slow queries so
   they cannot hold a connection forever.
3. Multipart oversize/unwritable temp storage no longer becomes 500: see §3.11 and
   [rest-api.md](rest-api.md#error-responses-rfc-7807-problem-detail).

### 3.11 Upload and Storage Boundaries (measured 2026-11)

Reproduce: `python3 scripts/verify-upload-boundaries-e2e.py` (real process,
`max-file-size=2MB`, `max-request-size=2.5MB`, multipart location in a controlled temp
directory).

| Scenario | Measured semantics |
|----------|--------------------|
| Single file over `max-file-size` | The container rejects it while parsing: **413 `PAYLOAD_TOO_LARGE`** in **0.01 s**, `X-RAG-Failure-Class: CLIENT_REQUEST`; the controller never receives the file |
| Request over `max-request-size` | **413 `PAYLOAD_TOO_LARGE`** in **0.00 s** |
| 5 concurrent uploads | All 200; multipart temp directory has no residue |
| Client aborts an upload | No temp residue within 2 s; service health stays UP |
| Multipart temp directory not writable | **503 `UPLOAD_STORAGE_UNAVAILABLE`**, `Retry-After: 5`; error body leaks no path or internal class |
| PDF over `rag.pdf.max-upload-bytes` | 400 before copy/conversion; after 4 concurrent failures all `pdf-import-*` temp directories are cleaned |

`server.tomcat.max-swallow-size` defaults to 100MB (matching `max-request-size`) so a
typical oversize request still receives a structured 413 instead of a reset connection;
swallowed bytes are discarded, not buffered, but they do consume bandwidth. The text
upload rejection path now uses file metadata only and no longer calls `getBytes()` to
read the whole part before rejecting it.

### 3.12 Concurrency Baseline and Regression Gate (measured 2026-11)

Reproduce:

```bash
python3 scripts/verify-performance-baseline.py --run --profile quick \
  --baseline scripts/testdata/performance-baseline.json
```

The script provisions its own database and mock provider, runs concurrency **1 / 8 / 32**
with `requests-per-worker=2`, and writes the baseline to
[performance-baseline.json](../scripts/testdata/performance-baseline.json). Environment:
14-core macOS, JDK 25, PostgreSQL 16.8 + pgvector, pool 20, fixture 200 docs / 800 chunks.
The fixture is intentionally small for CI runtime; index regression is covered separately
by the 9,600-vector scenario in §3.13.

| Concurrency | Workload | Throughput (rps) | p50 (ms) | p95 (ms) | p99 (ms) | Error rate |
|-------------|----------|------------------|----------|----------|----------|------------|
| 1 | search_vector | 12.0 | 73 | 125 | 125 | 0% |
| 1 | search_hybrid | 10.4 | 96 | 129 | 129 | 0% |
| 1 | export_collection | 100.7 | 10 | 10 | 10 | 0% |
| 1 | document_create | 30.0 | 25 | 85 | 85 | 0% |
| 1 | upload_parse | 4.4 | 72 | 579 | 579 | 0% |
| 1 | embedding_pipeline | 20.2 | 47 | 80 | 80 | 0% |
| 8 | search_vector | 58.1 | 89 | 185 | 185 | 0% |
| 8 | search_hybrid | 65.0 | 106 | 159 | 159 | 0% |
| 8 | export_collection | 296.0 | 20 | 24 | 24 | 0% |
| 8 | document_create | 93.5 | 62 | 134 | 134 | 0% |
| 8 | upload_parse | 3.2 | 1788 | 4307 | 4307 | 0% |
| 8 | embedding_pipeline | 83.3 | 74 | 132 | 132 | 0% |
| 32 | search_vector | 51.5 | 458 | 736 | 813 | 0% |
| 32 | search_hybrid | 33.9 | 875 | 973 | 994 | 0% |
| 32 | export_collection | 239.7 | 76 | 108 | 116 | 0% |
| 32 | document_create | 47.2 | 557 | 917 | 970 | 0% |
| 32 | upload_parse | 8.1 | 3515 | 7004 | 7637 | 0% |
| 32 | embedding_pipeline | 140.9 | 158 | 309 | 429 | 0% |

**Gate** (`verify-performance-baseline.py`, any failure turns the run red):

- Relative to baseline: p95 ≤ **2.5×**, p99 ≤ **3.0×**, throughput ≥ **0.3×**;
- Generous absolute caps: search_vector 3 s, search_hybrid 4 s, export 3 s,
  document_create 4 s, upload_parse 20 s, embedding_pipeline 6 s;
- Error rate ≤ **5%**.

**Headroom**: relative thresholds are the primary constraint. At c=32 the tightest cases
are `upload_parse` (p95 7.0 s vs 20 s cap, 2.9×) and `search_hybrid` (p95 0.97 s vs 4 s cap,
4.1×). The 0.3× throughput floor tolerates a clearly slower shared CI machine; absolute
caps are a backstop, not a normal-jitter gate.

**Two issues fixed this round that only sustained load exposed**:

1. **OSIV held one database connection per request for the whole request.** The demo
   `application.yml` shadowed the core jar's `spring.jpa.open-in-view: false`, so OSIV
   stayed enabled. At c=32 Hikari showed `total=20, active=20, idle=0, waiting=32`, p50
   13–20 s, and many 503s; 177 `Apparent connection leak` warnings pointed at
   `CollectionIdentityResolver.resolveActiveKeyIds` and `DocumentQueryService.findById`.
   All four demos now disable OSIV explicitly; c=32 is 0 errors / 0 waiters.
2. **Verification stacks never activated the `postgresql` profile, so the HNSW index did
   not exist.** `EmbeddingProfileBootstrap` is `@Profile("postgresql")`; the shared
   `LocalStack` and the demos' default startup did not set it, so the bootstrap and three
   background workers were skipped. Across 9,600 vectors ANN fell back to Seq Scan (24 ms);
   with HNSW it is 1.9 ms. `scripts/lib/rag_local_stack.py` and all four demo
   `application.yml` files now default to the `postgresql` profile.

### 3.13 Slow SQL and Query Plans (measured 2026-11)

Reproduce:

```bash
python3 scripts/verify-query-plans.py --docker --documents 1200 --chunks-per-doc 8
```

The script starts a temporary pgvector container with `pg_stat_statements` preloaded (the
host's everyday database usually has not preloaded it), seeds 9,600 vectors, resets stats,
runs 40 rounds of vector/hybrid search plus 10 exports, then reports top SQL and EXPLAINs.

| Top SQL | Calls | Total (ms) | Mean (ms) |
|---------|-------|------------|-----------|
| Vector candidate CTE (`WITH candidates AS MATERIALIZED`) | 80 | 476.4 | 5.96 |
| Evidence backfill (`local_chunk_id` / `embedding_id` join) | 40 | 1740.8 | 43.52 |
| Freshness/state counts (`COUNT(*) FILTER`) | 40 | 169.5 | 4.24 |
| Document list batch query | 60 | 22.9 | 0.38 |
| retrieval_logs insert/update, collection/profile lookups | 80–90 | < 7 | < 0.1 |

Key plans (`EXPLAIN (ANALYZE, BUFFERS)`):

| Path | Plan | Time |
|------|------|------|
| Vector ANN (with collection scope) | `Nested Loop` + `Index Scan(idx_rag_emb_p_1_1024_hnsw)` + `Memoize` + `Index Scan(rag_documents_pkey)`, **no Seq Scan** | **1.94 ms** |
| Vector ANN (no scope, diagnostic) | `Index Scan(idx_rag_emb_p_1_1024_hnsw)` | 0.72 ms |
| Export keyset | `Index Scan(rag_documents_pkey)` | 0.14 ms |
| Title keyword | `Bitmap Index Scan(idx_rag_documents_title_trgm)` | 0.07 ms |

**Conclusion**: the key retrieval path still uses HNSW and has not regressed to a Seq Scan;
export and keyword paths hit their indexes. The verifier asserts
`vector_ann.hnsw_used=true` and no Seq Scan on `rag_embeddings`.

**Index-availability constraint**: HNSW is a **partial index** with predicate
`embedding_profile_id = <id> AND embedding_<dim> IS NOT NULL`. The profile id in SQL must
stay an **inlined literal** (as in production). If it becomes a bind parameter, the planner
cannot prove that `embedding_profile_id = $1` implies the partial-index predicate and
silently falls back to Seq Scan. Any change to the ANN SQL must preserve the literal and
re-run this verifier.

### 3.14 Long soak: resource trends and GC (12 minutes, measured 2026-11)

Reproduce:

```bash
python3 scripts/run-bounded-soak.py --minutes 12 --concurrency 4 \
  --pid <app-pid> --dsn <postgres-dsn> --sample-seconds 15
```

`--pid` / `--dsn` are optional; without them the script only drives load and skips the
resource assertions. Nightly runs the short form with `--minutes 10 --sample-seconds 15`.
The workload is 3 continuous mixed-search workers plus 1 chat worker (2 s gap between
turns) against mock provider + real PostgreSQL, sampled 48 times.

Gate: compare the **median of the first and last quarter** of samples, requiring
`last ≤ first × relative + absolute`. Sawtooth from GC is fine; monotonic growth is not.
Any breach sets `degraded=true` and exits 1.

| Metric | First-quarter median | Last-quarter median | Ratio | Limit | Result |
|--------|---------------------|---------------------|-------|-------|--------|
| Java threads | 84 | 84 | 1.00 | 1.5× + 8 | pass |
| Heap used | 255 MB | 335 MB | 1.31 | 1.5× + 64 MB | pass (sawtooth, 0 full GCs) |
| Process RSS | 803 MB | 671 MB | 0.84 | 2.0× + 128 MB | pass |
| File descriptors | 55 | 56 | 1.02 | 1.5× + 20 | pass |
| DB connections | 7 | 8 | 1.14 | 1.5× + 4 | pass (pool cap 20) |

Load: 5,858 searches (0 errors, p50 350 ms, p95 523 ms, p99 645 ms, second-half p95 drift
1.04) and 213 chats (0 errors, p50 1,367 ms, p95 1,598 ms, drift 0.88). GC: 194 young
collections, 0 old, 2.24 s total pause (0.31% of wall clock). `idle in transaction`
appeared once at 2 connections and was 0 in the other 47 samples — an in-flight request,
not a leak.

**Real leak found by the soak (fixed)**: `ai-advisor-*` threads grew linearly with request
count, from 153 to 725 (4.3×) in 12 minutes. Root cause: Spring AI 1.1.8's
`RetrievalAugmentationAdvisor` creates a fresh core=4 / max=16 `ThreadPoolTaskExecutor`
per advisor instance when no `TaskExecutor` is injected, and never shuts it down; this
repository rebuilds the advisor on every chat attempt, leaking 3–4 threads per request.
Fix: `PerformanceConfig` now exposes a shared bounded `ragAdvisorExecutor` (core 8 /
max 16 / queue 256 / `CallerRunsPolicy`, propagating MDC and request context), and
`ModeAwareChatClientFactory` injects it via `setAdvisorTaskExecutor` and passes it to the
advisor builder. After the fix the same load keeps threads at 84–86 with zero
`ai-advisor-*` threads; `ModeAwareChatClientFactoryTest` gained a regression case that
asserts thread count is unchanged across 20 attempts and that retrieval runs on the
injected executor.

## 4. Capacity Boundaries

| Dimension | Current | Boundary / guidance |
|-----------|---------|---------------------|
| Validated scale | 5k docs / 50k chunks in one collection; ~11.2k docs / 112k vectors in the DB | Script hard cap is 5k docs; larger scales are untested and not extrapolated |
| Write throughput | ~950 vectors/s (HNSW present) | 100k chunks ≈ 105 s of pure writes; plan a maintenance window for bulk imports |
| Index build | 4.7 s for 31k vectors | ~16 s projected for 100k; startup wait cap is 60 s, tune it or pre-warm asynchronously |
| Retrieval | p50 ~50–90 ms | Dominated by the embedding call; SQL is near-constant. Candidate pool caps at 1000, so heavy staleness can reduce recall |
| Lists | Fixed ≤20 rows per page | N+1 growth removed; processing summaries use 4 batched queries per page |
| WebUI first paint | 130.4 KB gzip / 403.8 KB uncompressed; Slow 4G FCP 2.44 s | Budget and gate in §3.8; gzip headroom 29.6 KB and Slow 4G headroom 18.7%; axios and recharts are kept out of the first screen |
| SSE concurrency | 16 streams per key, 64 globally; 16 streams peak at +45 threads (Tomcat/boundedElastic expansion); after two rounds `SseEmitter` returns to 0 with exactly 2 heartbeat threads | Limits in §3.9; request 17 for one key gets HTTP 429 `TOO_MANY_REQUESTS` with no queueing; emitter timeout = `chat-stream-ms` (180 s default) |
| Concurrency baseline | c=32: search p95 0.74–0.97 s, export 108 ms, document create 0.92 s, upload parse 7.0 s (200-doc fixture) | Gate: p95 ≤ 2.5× baseline and under absolute caps; upload parse is the tightest boundary, see §3.12 |
| Retrieval plan | 9.6k-vector ANN uses HNSW in 1.9 ms, no Seq Scan; export keyset 0.14 ms | ANN SQL must keep the profile id as a literal or the partial-index predicate no longer matches and silently degrades, see §3.13 |
| Soak | 12-minute mixed load: threads 84→84, heap 255→335 MB (sawtooth), FDs 55→56, DB connections 7→8; 194 young / 0 old GCs, pause share 0.31% | Gate compares first/last quarter medians (1.5× + absolute cap); monotonic growth fails, see §3.14 |
| Advisor executor | Shared `ragAdvisorExecutor`: core 8 / max 16 / queue 256, `CallerRunsPolicy` | Do not remove `taskExecutor(...)` from the advisor builder, or every chat attempt rebuilds a 4/16 pool, see §3.14 |
| WebUI lists | 20 rows per page, 510 DOM nodes, 9.5 MB heap | Documents, collections and evaluation history are paginated; 200 synthetic rows render in 53–70 ms |
| WebUI export | Built into Collection cards; fetch + ReadableStream writes as it reads, with progress and cancel; 2.73 MB / 12–13 ms first byte at 5k docs | Browsers without a write API fall back to a Blob (platform boundary); ~27 MB at 50k docs |
| Export | Streaming, 200 rows per batch, batch count in response headers | Memory does not grow with row count; limited by network and client consumption. Export very large collections off-peak |
| Document-list keyword | `LOWER(title) LIKE '%kw%'`; uses `idx_rag_documents_title_trgm` when pg_trgm is available | At 5k docs: index SQL 0.103 ms / HTTP p50 4.2 ms; no index SQL 2.134 ms / HTTP p50 8.4 ms. **Keywords need ≥3 characters** for trigram extraction; 1–2 characters still scan. If `pg_trgm` is absent the migration logs NOTICE and degrades to a sequential scan without failing startup |
| Fulltext search (retrieval path) | pg_jieba / English FTS / pg_trgm auto-selected by capability | Separate from the document-list keyword filter; fulltext-weighted p50 ~158 ms at 5k docs, dominated by the embedding call |
| Connection pool | core default max=20/min=5; four demos default max=10 | A saturated request queues at most `DB_CONNECTION_TIMEOUT_MS` (10 s default), then gets 503 `DATABASE_UNAVAILABLE` + `Retry-After`; slow queries are bounded by `DB_STATEMENT_TIMEOUT_MS` (30 s default) and return 504. See §3.10 |
| TTL cleanup | 10k rows in 287 ms, batched | Rows grow linearly but batches stay bounded; for millions, shorten the schedule instead of enlarging batches |

## 5. Known Limitations

1. Fake embeddings validate scale and degradation only; real-model latency, rate limits,
   and dimension differences are out of scope.
2. Single-node PostgreSQL + HNSW only; multi-replica, read replicas, and external vector
   stores are not covered.
3. p95 contains GC/cold-cache noise (document first page once hit 138 ms); use p50 and
   SQL execution plans for capacity decisions.
4. Vector candidates use 10x overfetch (minimum 100). If a scope has a very high stale
   vector ratio, the freshness filter may return fewer than the requested rows; re-embed
   to repair.
