# PostgreSQL / pgvector Support Matrix

This page answers three questions: **which versions are supported, what was actually
tested on each version, and what pgvector version is required**. The matrix comes from
real `pgvector/pgvector` official image containers, not a mocked database.

## Conclusion

| PostgreSQL | Official image | pgvector | Full V1..latest migrations | Core gated suites | Status |
|---|---|---|---|---|---|
| 15 | `pgvector/pgvector:pg15` (15.19) | 0.8.7 | Pass | 62 tests pass | Supported |
| 16 | `pgvector/pgvector:pg16` (16.15) | 0.8.7 | Pass | Full nightly gated suite + 62 tests pass | Supported (default) |
| 17 | `pgvector/pgvector:pg17` (17.11) | 0.8.7 | Pass | 62 tests pass | Supported |
| 18 | `pgvector/pgvector:pg18` (18.6) | 0.8.7 | Pass | 62 tests pass | Supported |

- The **default and CI baseline** is PostgreSQL 16, image `pgvector/pgvector:pg16`.
- 15/17/18 run as a compatibility matrix in nightly (see CI cost below).
- PostgreSQL 14 and older are unverified and unsupported; pgvector below 0.5.0 is unsupported.

### Minimum pgvector version

| Capability | Minimum pgvector | Note |
|---|---|---|
| `vector(1024)` storage, cosine `<=>` | 0.5.0 | The project fixes 1024 dimensions as `VECTOR(1024)` |
| `USING hnsw (embedding vector_cosine_ops)` | **0.5.0** | HNSW exists since 0.5.0; every project HNSW index depends on it |
| Version verified by this project | **0.8.7** | Carried by all four official PostgreSQL images today |

Use 0.8.x in production (official images carry 0.8.7). 0.5.0–0.7.x should work in
principle but this matrix does not verify each version, so it is not a promise; run
`scripts/verify-postgres-version-matrix.sh` before using an older 0.x release.

## What is tested per version

Each version runs:

1. **Full migrations**: apply every script V1..latest under
   `spring-ai-rag-core/src/main/resources/db/migration/` in Flyway order to catch
   version-specific syntax/behaviour differences;
2. **Extension version**: `SELECT extversion FROM pg_extension WHERE extname='vector'`,
   asserted to be ≥ 0.5.0;
3. **HNSW**: create `USING hnsw (embedding vector_cosine_ops)` and check the index definition;
4. **Retrieval correctness**: a 1024-dimension cosine nearest-neighbour query returns the
   same-direction vector rather than the opposite one;
5. **`CREATE INDEX CONCURRENTLY`**: exercise the concurrent index path used by
   `EmbeddingProfileIndexManager` and confirm the index is visible;
6. **`SET NOT NULL`**: confirm the columns tightened by V28/V41/V48
   (`rag_collection.collection_key`, `rag_embedding_jobs.document_kind` /
   `chunker_version`, `rag_api_key.principal_id` / `credential_version`) have
   `attnotnull = true` after migration;
7. **RLS fact**: the current schema enables **no RLS** and has no policies; tenant
   isolation is enforced at the application layer. The matrix only confirms this has not
   drifted with the PostgreSQL version — it is not a claim that RLS is supported.

On top of that, the **core gated suites** cover the main retrieval and data-lifecycle paths:

- `RetrievalCorrectnessPostgresIntegrationTest` — retrieval correctness (vector/keyword/hybrid)
- `HybridRetrieverRrfPostgresIntegrationTest` — RRF fusion
- `MetadataFiltersPostgresIntegrationTest` — metadata filters
- `MultiCollectionRetrievalPostgresIntegrationTest` — multi-collection retrieval
- `RetrievalDiagnosticsPostgresIntegrationTest` — retrieval diagnostics
- `DocumentLifecyclePostgresIntegrationTest` — document lifecycle
- `CollectionPurgePostgresIntegrationTest` — permanent purge/retirement
- `EmbeddingProfileMigrationLockPostgresIntegrationTest` — embedding profiles and concurrent index creation
- `JsonbStructuredRecordsPostgresIntegrationTest` — JSONB structured records

pg16 additionally runs the full nightly gated suite (all 33 classes); the matrix job runs
only the core set above to avoid duplicated cost.

## Version differences and boundaries

- **No version-specific defect was found**: 15/16/17/18 behave consistently on the paths
  above and all carry pgvector 0.8.7.
- `SET NOT NULL` validates the whole table under `ACCESS EXCLUSIVE` on all four versions
  with identical semantics; large-table upgrade windows still need the migration guidance.
- `CREATE INDEX CONCURRENTLY` cannot run inside a transaction block on any version;
  `EmbeddingProfileIndexManager` already executes it non-transactionally, and
  `IF NOT EXISTS` behaves the same.
- GUCs such as `hnsw.ef_search` are registered by the extension; the application does not
  tune them at runtime, so they are not a version difference.
- PostgreSQL 18 image and core paths are verified, but 18 is not yet in the “full gated
  nightly” set; promoting 18 to default requires running the full suite first.
- RLS: the repository currently has no RLS/policy. If RLS is introduced, the
  “RLS not implicitly enabled” check must become per-table policy assertions.

## Reproducing locally

```bash
# Real-container probe: full migrations + HNSW / CONCURRENTLY / SET NOT NULL / RLS facts
./scripts/verify-postgres-version-matrix.sh --versions 15,16,17,18

# Core gated suites (pick a version)
TESTCONTAINERS_PG_IMAGE=pgvector/pgvector:pg15 \
  ./scripts/verify-gated-it.sh \
  RetrievalCorrectnessPostgresIntegrationTest,HybridRetrieverRrfPostgresIntegrationTest,\
MetadataFiltersPostgresIntegrationTest,MultiCollectionRetrievalPostgresIntegrationTest,\
RetrievalDiagnosticsPostgresIntegrationTest,DocumentLifecyclePostgresIntegrationTest,\
CollectionPurgePostgresIntegrationTest,EmbeddingProfileMigrationLockPostgresIntegrationTest,\
JsonbStructuredRecordsPostgresIntegrationTest
```

If pulls fail behind a regional network, pull through a mirror and `docker tag` it as
`pgvector/pgvector:pg<N>`; neither the script nor CI hardcodes a mirror. See the
[China network guide](china-network-guide.md).

## CI cost and nightly decision

- **pg16**: nightly `gated-postgres` runs the full suite as the release baseline.
- **15/17/18**: a new `postgres-version-matrix` job runs the full-migration probe plus the
  nine core gated classes per version (about 8–10 minutes per version locally). Three
  runners execute in parallel, so wall-clock cost stays close to one version; that is
  affordable for nightly rather than every PR.
- If the matrix job proves flaky, degrade in this order: keep the probe (migrations/HNSW/DDL),
  then reduce the core suites to retrieval correctness + lifecycle; do not delete the
  matrix outright, or version regressions become a blind spot again.
