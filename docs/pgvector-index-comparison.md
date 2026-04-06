# pgvector Index Comparison: HNSW vs IVFFlat

> Performance tuning guide for spring-ai-rag vector search optimization

---

## Overview

pgvector supports two index types for approximate nearest neighbor (ANN) search:

| Property | HNSW | IVFFlat |
|---|---|---|
| Algorithm | Hierarchical Navigable Small World (graph-based) | Inverted File Index (clustering-based) |
| Build time | Medium (O(n log n)) | Fast (O(n)) |
| Query time | Fast (log n) | Medium (√n) |
| Memory usage | Higher | Lower |
| Recall | Very high (configurable) | Moderate |
| Index size | Larger | Smaller |
| Update support | None (rebuild required) | None (rebuild required) |
| Parameters | `m`, `ef_construction`, `ef_search` | `lists` |

spring-ai-rag currently uses **HNSW** (`m=16`, `ef_construction=64`) by default.

---

## Algorithm Comparison

### HNSW — Hierarchical Navigable Small World

HNSW builds a multi-layer graph:
- **Top layers**: sparse long-range connections (fast traversal)
- **Bottom layers**: dense short-range connections (precise results)
- Search starts from top layer, narrows down progressively

```
Layer 2:  ○-----------○
Layer 1:  ○---○---○---○---○
Layer 0:  ○-○-○-○-○-○-○-○-○  ← full graph at base layer
```

**Pros**: Excellent query performance, high recall, robust to data distribution
**Cons**: Higher memory usage, slower build, no incremental updates

### IVFFlat — Inverted File Index with Flat storage

IVFFlat partitions vectors into `lists` clusters using k-means:
- Query searches only relevant clusters (inverted list)
- Vectors within a cluster are stored flat (no compression)

**Pros**: Lower memory footprint, faster build for large datasets
**Cons**: Slower queries, recall depends on cluster count, data distribution sensitive

---

## When to Use Which

### Choose HNSW when:

- Dataset size: < 1M vectors (HNSW shines on medium datasets)
- Query latency is critical (sub-50ms target)
- High recall is required (>95%)
- Memory budget allows ~2-3x raw vector size for index

### Choose IVFFlat when:

- Dataset size: > 1M vectors with limited memory
- Build time is critical (batch ingestion, frequent rebuilds)
- Memory is severely constrained
- Recall target: 90-95% is acceptable

### Choose No Index (Sequential Scan) when:

- Dataset size: < 10k vectors (index overhead > scan cost)
- Exact nearest neighbor is required
- Dataset is static and small enough

---

## spring-ai-rag Configuration

### Current HNSW Settings (Default)

```yaml
# application-postgresql.yml
spring:
  ai:
    vectorstore:
      pgvector:
        index-type: HNSW
        distance-type: COSINE_DISTANCE
```

```sql
-- V1__init_rag_schema.sql
CREATE INDEX idx_rag_emb_vector_hnsw ON rag_embeddings
    USING hnsw (embedding vector_cosine_ops) WITH (m='16', ef_construction='64');
```

### Switching to IVFFlat

To switch to IVFFlat, create a new migration:

```sql
-- V15__switch_to_ivfflat_index.sql
DROP INDEX IF EXISTS idx_rag_emb_vector_hnsw;

CREATE INDEX idx_rag_emb_vector_ivfflat ON rag_embeddings
    USING ivfflat (embedding vector_cosine_ops) WITH (lists='100');
```

Then update `application-postgresql.yml`:

```yaml
spring:
  ai:
    vectorstore:
      pgvector:
        index-type: IVFFlat
```

### Recommended Parameters

#### HNSW Tuning Knobs

| Parameter | Default | Range | Effect |
|---|---|---|---|
| `m` | 16 | 4-64 | Connections per node. Higher = better recall, more memory |
| `ef_construction` | 64 | 16-512 | Search width during build. Higher = better recall, slower build |
| `ef_search` | 40 | 10-400 | Search width during query. Higher = better recall, slower query |

```sql
-- High recall HNSW
CREATE INDEX idx_rag_emb_vector_hnsw ON rag_embeddings
    USING hnsw (embedding vector_cosine_ops) WITH (m='32', ef_construction='128');

-- Fast build HNSW (slightly lower recall)
CREATE INDEX idx_rag_emb_vector_hnsw ON rag_embeddings
    USING hnsw (embedding vector_cosine_ops) WITH (m='8', ef_construction='32');
```

#### IVFFlat Tuning Knobs

| Parameter | Default | Range | Effect |
|---|---|---|---|
| `lists` | 100 | Dataset size / 1000 to Dataset size / 10 | Number of clusters. Higher = better recall, slower query |

```sql
-- 100k vectors → 100-1000 lists
CREATE INDEX idx_rag_emb_vector_ivfflat ON rag_embeddings
    USING ivfflat (embedding vector_cosine_ops) WITH (lists='200');

-- 1M vectors → 100-10000 lists
CREATE INDEX idx_rag_emb_vector_ivfflat ON rag_embeddings
    USING ivfflat (embedding vector_cosine_ops) WITH (lists='1000');
```

**Rule of thumb**: `lists = sqrt(n)` where `n` = number of vectors.

---

## Benchmark Methodology

### Test Setup

```bash
# 1. Create test dataset
INSERT INTO rag_embeddings (document_id, chunk_index, content_hash, embedding, created_at)
SELECT 
    'bench-doc-' || i / 100,
    i % 100,
    md5(random()::text),
    ai_demo.get_embedding('text-' || i),
    NOW()
FROM generate_series(1, 100000) i;

# 2. Create index (run separately for HNSW vs IVFFlat)
CREATE INDEX idx_test_hnsw ON rag_embeddings USING hnsw (...) WITH (m='16', ef_construction='64');
CREATE INDEX idx_test_ivfflat ON rag_embeddings USING ivfflat (...) WITH (lists='316');

# 3. Run EXPLAIN ANALYZE
EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)
SELECT id, 1 - (embedding <=> $1) AS similarity
FROM rag_embeddings
ORDER BY embedding <=> $1
LIMIT 20;
```

### Metrics to Collect

| Metric | Tool |
|---|---|
| Query latency p50/p95/p99 | `EXPLAIN ANALYZE` + `pg_stat_statements` |
| Recall (vs brute-force) | Application-level comparison |
| Build time | `EXPLAIN ANALYZE` for `CREATE INDEX` |
| Index size | `pg_relation_size('idx_rag_emb_vector_hnsw')` |
| Memory usage | `shared_buffers` + `work_mem` monitoring |

### Quick Recall Benchmark Query

```sql
-- Exact (brute-force) baseline
SELECT id FROM rag_embeddings ORDER BY embedding <=> $1 LIMIT 20;

-- HNSW/IVFFlat result
SELECT id FROM rag_embeddings ORDER BY embedding <=> $1 LIMIT 20;

-- Recall = (intersection of both sets) / 20
```

---

## Performance Characteristics (Typical)

Based on typical pgvector benchmarks with 1024-dimensional BGE-M3 vectors:

| Scenario | HNSW (m=16, ef=64) | IVFFlat (lists=316) | No Index |
|---|---|---|---|
| 10k vectors, query | ~1-2ms | ~5-10ms | ~10-20ms |
| 100k vectors, query | ~3-5ms | ~20-50ms | ~100-200ms |
| 1M vectors, query | ~5-10ms | ~100-300ms | ~2000ms+ |
| Build time (100k) | ~30s | ~5s | N/A |
| Index size (100k×1024f) | ~400MB | ~400MB | N/A |
| Memory overhead | High (graph) | Low (cluster centers) | None |

---

## Migration Between Index Types

### HNSW → IVFFlat

```sql
-- 1. Drop HNSW index
DROP INDEX idx_rag_emb_vector_hnsw;

-- 2. Build IVFFlat
CREATE INDEX idx_rag_emb_vector_ivfflat ON rag_embeddings
    USING ivfflat (embedding vector_cosine_ops) WITH (lists='316');

-- 3. ANALYZE to update planner statistics
ANALYZE rag_embeddings;
```

### IVFFlat → HNSW

```sql
-- 1. Drop IVFFlat index
DROP INDEX idx_rag_emb_vector_ivfflat;

-- 2. Build HNSW
CREATE INDEX idx_rag_emb_vector_hnsw ON rag_embeddings
    USING hnsw (embedding vector_cosine_ops) WITH (m='16', ef_construction='64');

-- 3. ANALYZE
ANALYZE rag_embeddings;
```

---

## Spring AI pgvector Properties Reference

When using Spring AI's `PgVectorStore` with auto-configuration or manual `VectorStoreConfig`:

| Property | Type | Default | Description |
|---|---|---|---|
| `spring.ai.vectorstore.pgvector.index-type` | String | `HNSW` | `HNSW` or `IVFFlat` or `FLAT` |
| `spring.ai.vectorstore.pgvector.distance-type` | String | `COSINE_DISTANCE` | `COSINE_DISTANCE`, `EUCLIDEAN_DISTANCE`, `DOT_PRODUCT` |
| `spring.ai.vectorstore.pgvector.dimensions` | int | 1024 | Must match embedding model output |
| `spring.ai.vectorstore.pgvector.vector-table-name` | String | `rag_vector_store` | Table name for vectors |

---

## See Also

- [pgvector Installation](https://github.com/pgvector/pgvector)
- [pgvector HNSW Documentation](https://github.com/pgvector/pgvector#hnsw)
- [pgvector IVFFlat Documentation](https://github.com/pgvector/pgvector#ivfflat)
- Spring AI PgVectorStore: `spring-ai-contradictory-my-contradiction/pgvector/VectorStore.java`
- spring-ai-rag `VectorStoreConfig.java` — current index configuration
- `V1__init_rag_schema.sql` — current index DDL

---

*Last reviewed: 2026-04-06 | spring-ai-rag 1.0.0 | pgvector 0.7.x*
