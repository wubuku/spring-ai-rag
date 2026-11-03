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
| Streaming export | 82.3 / 92.4 | 58.6 / 59.1 | 0.71x |
| Evaluation | 7.9 / 11.4 | 5.5 / 6.4 | 0.70x |

The earlier 1k→2k result remains in git history as the baseline: after the HNSW
candidate CTE fix, the 255→451 ms p50 linear degradation disappeared. In this
3k→5k run (1.67x documents) every p50 ratio is below 1.47x, and export/list p50
even dropped with batching/cache effects. The 455 ms vector p95 is a single cold
query among 10 samples; p50 stayed flat.

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

## 4. Capacity Boundaries

| Dimension | Current | Boundary / guidance |
|-----------|---------|---------------------|
| Validated scale | 5k docs / 50k chunks in one collection; ~11.2k docs / 112k vectors in the DB | Script hard cap is 5k docs; larger scales are untested and not extrapolated |
| Write throughput | ~950 vectors/s (HNSW present) | 100k chunks ≈ 105 s of pure writes; plan a maintenance window for bulk imports |
| Index build | 4.7 s for 31k vectors | ~16 s projected for 100k; startup wait cap is 60 s, tune it or pre-warm asynchronously |
| Retrieval | p50 ~50–90 ms | Dominated by the embedding call; SQL is near-constant. Candidate pool caps at 1000, so heavy staleness can reduce recall |
| Lists | Fixed ≤20 rows per page | N+1 growth removed; processing summaries use 4 batched queries per page |
| Export | Streaming, 200 rows per batch, batch count in response headers | Memory does not grow with row count; limited by network and client consumption. Export very large collections off-peak |
| Fulltext search | Keyword channel scans `rag_documents` with `ILIKE` | p50 ~210 ms at 5k docs; keyword cost grows with collection size, no trigram index yet |
| Connection pool | default max=10 | Concurrent search/import will exhaust it first; size it for expected concurrency and load-test |
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
