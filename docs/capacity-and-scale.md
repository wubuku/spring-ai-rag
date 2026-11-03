# Capacity And Scale Degradation

> This round validates behavior **after the corpus grows**. The goal is not absolute
> numbers but the **degradation curve**: does latency grow linearly when data doubles,
> and are there N+1 queries or full table scans? Reproduce everything with
> `scripts/run-scale-benchmark.py`.

## 1. Measurement Setup

- Corpus: `scale-bench-1000` (1,000 docs / 10,000 chunks) and `scale-bench-2000`
  (2,000 docs / 20,000 chunks). With the pre-existing base corpus:
  **3,200 docs / 31,000 vectors**.
- Vectors: deterministic fake embeddings (1024 dimensions, 32 normalized vectors in
  rotation); no real model calls.
- Environment: Apple M4 Pro / 64 GB, PostgreSQL 16.8 + pgvector 0.8.2.
- Requests hit the real HTTP endpoints. Each request includes one mock embedding call
  (~30–50 ms, independent of corpus size), so compare **ratios**, not absolutes.
- `requests=50`, `warmup=8`; p50 is more stable than p95, so conclusions use p50 with
  p95 as a reference.

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
  --docs 1000 --scale-docs 2000 --chunks-per-doc 10 \
  --requests 50 --warmup 8 --output /tmp/scale.json

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

| Endpoint (p50 / p95, ms) | 1k docs | 2k docs | p50 ratio | Before fix (p50) |
|--------------------------|---------|---------|-----------|------------------|
| Vector-only search | 52.5 / 118.9 | 59.2 / 90.1 | 1.13x | 255 → 451 (1.77x) |
| Hybrid search | 57.0 / 99.4 | 82.8 / 109.4 | 1.45x | 251 → 439 (1.75x) |
| Fulltext-weighted search | 58.7 / 90.0 | 80.0 / 109.1 | 1.36x | 244 → 446 (1.83x) |
| Document list (first/deep) | 23.2 / 24.9 | 29.4 / 31.4 | 1.27x | roughly flat |
| Collection list | 3.2 / 6.0 | 6.0 / 10.5 | 1.88x (tiny absolute) | roughly flat |
| Export (one collection) | 10.4 | 11.4 | 1.10x | roughly flat |
| Evaluation | 10.3 | 5.2 | 0.50x | roughly flat |

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
- Remaining bounded lookups: processing-summary queries are still per document, but the
page size is fixed (≤20) and does not grow with corpus size.

### 3.5 TTL Cleanup

- 10,000 expired rows (100 sessions): candidate session query 4.2 ms, batched DELETE
**287 ms** (~29 µs/row).
- Cleanup advances in session batches (50) and row batches (500); no single unbounded
delete, and no lock contention or long transactions were observed.

## 4. Capacity Boundaries

| Dimension | Current | Boundary / guidance |
|-----------|---------|---------------------|
| Validated scale | 3.2k docs / 31k vectors | Script hard cap is 5k docs; larger scales are untested and not extrapolated |
| Write throughput | ~950 vectors/s (HNSW present) | 100k chunks ≈ 105 s of pure writes; plan a maintenance window for bulk imports |
| Index build | 4.7 s for 31k vectors | ~16 s projected for 100k; startup wait cap is 60 s, tune it or pre-warm asynchronously |
| Retrieval | p50 ~50–90 ms | Dominated by the embedding call; SQL is near-constant. Candidate pool caps at 1000, so heavy staleness can reduce recall |
| Lists | Fixed ≤20 rows per page | N+1 growth removed; processing summaries remain bounded per-row lookups |
| Export | `GET /rag/collections/by-key/export` loads the whole collection | **O(n) memory/response**, no pagination; large collections should not be exported online |
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
