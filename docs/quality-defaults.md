# Production quality defaults

> 📖 [English](quality-defaults.md) · 📖 [中文](quality-defaults-zh-CN.md)

> When `SPRING_PROFILES_ACTIVE` includes **`prod`**, the following apply
> (see `spring-ai-rag-core/src/main/resources/application-prod.yml`).

| Setting | Prod default | Why |
|---------|--------------|-----|
| `rag.security.enabled` | `true` | Require API keys |
| `rag.circuit-breaker.enabled` | `true` | Fail fast when LLM is down |
| `rag.rerank.enabled` | `true` (heuristic) | Local quality boost; no external rerank fee |
| `rag.rerank.provider` | `heuristic` | Override with `http` + SiliconFlow/Cohere for cross-encoder |
| `rag.rerank.candidate-limit` | `20` | Bounded pre-rerank candidate pool; final output remains governed by request `maxResults` |
| `rag.rerank.preferred-max-chunks-per-document` | `2` | Prefer broader document evidence while retaining up to two complementary chunks before backfill |
| `rag.query-rewrite.enabled` | `true` | Better recall |
| retrieval weights | vector 0.55 / fulltext 0.45 | Slight vector bias; tune with goldenset |

## Prove gains with goldenset

```bash
# Server running (any profile). Prefer real embed keys for search-based cases.
./scripts/run-retrieval-goldenset.sh
# Or: BASE_URL=http://127.0.0.1:18081 ./scripts/run-retrieval-goldenset.sh
```

Goldenset file: `testdata/goldenset/retrieval-goldenset.json`

The runner executes the same cases twice through `POST /api/v1/rag/search`:

- `baseline`: `useRerank=false`
- `quality`: `useRerank=true`

It persists both evaluations through `POST /api/v1/rag/evaluation/evaluate`,
prints average Precision@K, MRR, and nDCG plus the delta, and fails if MRR or
nDCG regresses.

## Versioned Live Retrieval Regression

The goldenset compares reranking modes. The versioned regression gate prevents
later commits from degrading live retrieval:

```bash
BASE_URL=http://127.0.0.1:18081 ./scripts/verify-quality-regression.sh
```

- Dataset: `testdata/regression/retrieval-core-v1.json`
- Baseline: `testdata/regression/retrieval-core-v1-baseline.json`
- Stable relevant identity: `collectionKey + sourceNamespace(default) + externalId`
- Metrics: Hit Rate, MRR, Recall@K, nDCG
- Safety assertions: selected Collections do not leak decoys, and an explicit
  empty JSONB case remains empty

The gate checks both absolute minimums and allowed regression from baseline.
Provider, database, embedding, or HTTP failures return nonzero.
`verify-release.sh --with-quality-regression` adds it against an existing
service, while `--with-local-runtime` includes it by default.

### Compare baseline vs quality

Run the server with the `prod` profile so the quality variant uses the
recommended heuristic reranker:

```bash
SPRING_PROFILES_ACTIVE=postgresql,prod bash scripts/start-server.sh
BASE_URL=http://127.0.0.1:8081 API_KEY=rag_sk_... \
  ./scripts/run-retrieval-goldenset.sh
```

The request-level `useRerank` switch only disables an enabled global reranker;
it cannot enable reranking when `rag.rerank.enabled=false`.

### Candidate Pool And Final Result Count

Effective reranking first retrieves
`max(requestedMaxResults, rag.rerank.candidate-limit)` candidates, then the reranker
selects the final `requestedMaxResults`. The default candidate limit is `20` and
configuration binding bounds it to `1..100`. It is not a new request parameter and
does not change the final count contract of Search, Chat, the Agent tool, or
Evaluation. Legacy GET Search explicitly disables reranking and keeps its existing
retrieval limit.

After increasing the candidate pool, observe all of the following:

- MRR, nDCG, and Recall@K, rather than result count alone;
- p95 retrieval latency for Search and Chat;
- HTTP provider request size and timeout/degraded rates;
- final `maxResults` bounds for Agent output, citations, and Evaluation.

For a quality or latency regression, first set `RAG_RERANK_CANDIDATE_LIMIT` back to
`1`, or temporarily disable global reranking, then rerun the goldenset and the
versioned quality regression.

### KNOWLEDGE Multi-Query Evidence Join

When KNOWLEDGE uses multiple retrieval queries, the project merges repeated
`documentId:chunkIndex` candidates before reranking and keeps the complete
candidate with the highest finite score. This is an internal default rather
than a tunable request or application setting. It prevents a lower-scored
occurrence from winning because of Spring AI Map iteration and avoids sending
the same chunk through rerank and prompt budgeting more than once.

The join is bounded local work over the already retrieved candidates and adds
no database, embedding, rerank-provider, or Chat-model calls. Use
`metadata.retrieval.documentJoin` to compare input, unique, removed-duplicate,
and score-replacement counts. Continue using the retrieval goldenset,
versioned regression, Chat source/citation checks, and p95 observation for
end-to-end quality and latency conclusions.

### Document Coverage After Rerank

The production default prefers at most two chunks from the same exact document
identity during the first selection pass. Two is a conservative balance: it
reduces adjacent duplicate evidence while retaining room for complementary
sections from one long document. If the ranked pool lacks enough alternatives,
the selector backfills skipped chunks in provider order, so this is not an
absolute per-document result cap.

Use `1` when document coverage matters more than continuity, and `3` or higher
when questions regularly require several sections from one document. Use `0`
to restore provider top-N behavior. After changing the value, compare unique
document count, MRR/nDCG/Recall@K, Search and Chat p95 latency, and final citation
quality. The selector itself is bounded local O(n) work and does not add model
or database calls.

### HTTP rerank (optional)

```yaml
rag:
  rerank:
    enabled: true
    provider: http
    api-key: ${SILICONFLOW_API_KEY}
    model: BAAI/bge-reranker-v2-m3
    base-url: https://api.siliconflow.cn
```
