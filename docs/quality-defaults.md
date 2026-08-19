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
