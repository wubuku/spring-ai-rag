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

Goldenset file: `testdata/goldenset/retrieval-goldenset.json` — **40 cases over
28 documents in 3 collections**, plus 2 decoy documents; `deterministic: true`.

Coverage (the count is the regression signal, not a couple of examples):

| Category | Cases | What it pins |
| --- | --- | --- |
| `zh-semantic` | 6 | Chinese pure-semantic hits (query wording differs from the document) |
| `en-semantic` | 8 | English pure-semantic hits |
| `keyword` | 6 | Exact keyword hits (half Chinese, half English) |
| `hybrid` | 6 | Hybrid retrieval fusion ordering |
| `multi-collection` | 4 | Search across all 3 collections and ordering inside each |
| `metadata-filter` | 4 | `metadataContains` filters (`lang`, `product`, `version`) |
| `forbidden` | 2 | Retired/unsafe documents must not outrank the answer |
| `no-answer` | 4 | No answer in corpus; score must stay below `noAnswerMaxScore` |

Determinism: fixed corpus, fixed queries, only `POST /api/v1/rag/search`, no chat
model call, no sampled metric. With the same embedding provider two runs produce
identical per-case numbers (measured twice at `mrr=0.9583 ndcg=0.9710`).

The runner executes the same cases twice: `baseline` (`useRerank=false`) and
`quality` (`useRerank=true`). `quality` may not regress MRR/nDCG against
`baseline`; the default configuration ships `rag.rerank.enabled=false`, so the two
passes coincide — that is a configuration fact, not evidence that rerank is
useless. Results are printed only; they are no longer persisted through
`POST /api/v1/rag/evaluation/evaluate`.

Floors and calibration (stored in the dataset, the runner only reads them):

- **Only MRR and nDCG@5 gate.** They are order-sensitive, so a shuffled top-k must
  turn red. P@5 is still computed and printed but not gated: with one target
  document per case its structural maximum is `1/5 = 0.20`, and it is blind to
  re-ordering, so it cannot carry the sensitivity check.
- Floors = measured value minus 0.07–0.10: `overall` MRR 0.82 / nDCG 0.84;
  `zh-semantic`, `keyword`, `hybrid`, `metadata-filter` 0.90/0.90;
  `en-semantic`, `multi-collection` 0.75/0.78; `forbidden` 0.85/0.85.
- The enforceable `forbidden` contract is "a decoy must not outrank the answer".
  With `maxResults > 0` the API always returns top-k, so "the decoy never appears"
  is not a constructible assertion.
- `no-answer`: at most `noAnswerMaxHits` (1) hit may exceed `noAnswerMaxScore`
  (0.62) inside top-5.

Sensitivity check (proves the gate can fail instead of being always green):

```bash
BASE_URL=http://127.0.0.1:18081 ./scripts/run-retrieval-goldenset.sh \
  --skip-create --mutation=all
```

`--mutation` applies three deterministic degradations to the real retrieved
lists — `shuffle` (reverse top-k), `demote` (move relevant hits to the end),
`drop` (remove relevant hits) — and reuses the same floors. All three must be
rejected or the script exits 1; success prints `MUTATION_DETECTED_OK`. Measured:
`shuffle` MRR 0.2218 / nDCG 0.4171, `demote` 0.2167 / 0.4127, `drop` 0/0, all
below their floors. The mutation only reorders in-memory results, so there is no
corpus to roll back.

## Retrieval Result Reproducibility

Retrieval correctness is pinned by the gated
`RetrievalCorrectnessPostgresIntegrationTest`, which uses deterministic 1024-dimensional
fake embeddings and a fixed corpus, independent of real model quality:

```bash
./scripts/verify-gated-it.sh RetrievalCorrectnessPostgresIntegrationTest
```

Reproducibility boundaries:

- The active 1024-dimensional profile's vector column `embedding_1024` has **no**
  approximate index, so retrieval performs an exact scan. Repeated searches over the
  same corpus and parameters return the same document set, order, and scores.
- The vector channel, the `pg_trgm`/English full-text channels, and fusion all use
  `documentId`, then `chunkIndex` as the equal-score tie-break, so both in-channel and
  fused order are deterministic.
- Jitter only becomes possible if an operator explicitly adds an HNSW/IVFFlat index on
  the active vector column. In that case only the document set within a score tolerance
  is guaranteed; top-k boundaries and tie order are not. Adding such an index requires
  updating the gated assertions and this tolerance note instead of keeping the current
  exact-equality contract.
- Goldenset runs in nightly against a fresh isolated database and a real embedding
  provider; it reproduces reliably within the same provider/model/revision. Absolute
  scores are not comparable across providers or model versions, and the regression gate
  compares only the relative `useRerank=false/true` delta within one run (identical when
  rerank is not enabled). Each run uses a timestamped collection, so repeated runs do not
  pollute existing data; local debugging can reuse one corpus with `--skip-create`.

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

### KNOWLEDGE Post-Retrieval Evidence Assembly

By default, `KNOWLEDGE` now performs bounded post-rerank assembly before prompt
budgeting. It reads previous/next chunks from the same local-index generation
for registered, locatable text hits. Defaults are radius `1`, at most `20` seed
hits, and `4000` characters per assembled window. It does not change Search
ordering/counts, add model calls, or apply to `AGENT`, `PLAIN`, JSON records, or
static knowledge. Java UTF-16 ranges remove repeated payload overlap while
synthetic headings/table headers remain distinct; a hash/chunker/generation or
anchor-text mismatch safely keeps the original hit.

The assembled source keeps the anchor citation handle. `metadata.contextAssembly`
describes only the authorized range mapping and does not create new citations for
neighbors. If the prompt budget is insufficient, the anchor is restored before
the existing truncation policy runs. `metadata.retrieval.contextAssembly` exposes
bounded, low-cardinality seed/neighbor/merge/dedup/budget/degradation diagnostics.
Disabling the switch is an emergency rollback and requires no data migration.

Quality acceptance must verify necessary facts in the final model input, citation
alignment with displayed evidence, bounded token/latency cost, and no regression
in the retrieval goldenset/versioned regression. A non-empty answer or a single
LLM-judge score is not sufficient evidence.

### Heuristic CJK Lexical Defaults

The production-default heuristic reranker keeps whitespace-token relevance
and Jaccard diversity for ordinary non-CJK text. Contiguous Chinese,
Japanese, Korean, and Bopomofo runs use adjacent Unicode code-point bigrams;
one-character runs keep that character, and Latin/digit runs in mixed text
remain matchable. A no-whitespace CJK query therefore does not need to appear
verbatim as one full sentence before reordered or partial phrase overlap can
contribute a deterministic lexical score.

Each query or chunk contributes at most 512 features, precomputed and reused
within one rerank. This internal bound is not a request parameter and adds no
database, embedding, HTTP, or Chat-model call. Another chunk with identical
nonblank text has similarity `1`, while null or blank chunks receive no
information-free diversity reward. Successful HTTP rerank responses are
unchanged; missing credentials, timeouts, or invalid responses that fall back
to heuristic use the same CJK behavior.

Content and title relevance are calculated separately, with
`max(contentRelevance, 0.9 * titleRelevance)` used as the effective relevance.
The authoritative document title is normalized once and does not create
diversity features. Product IDs, terms, or topic names that appear only in the
title can therefore promote a candidate without double-counting a title and
content match. Null or blank titles preserve the previous score and ordering
path. Successful HTTP-provider requests still send chunk content only; title
awareness is reused only by heuristic fallback.

For relevance, ordinary non-CJK terms that start and end with a Unicode letter
or digit require complete alphanumeric boundaries. Adjacent non-CJK letters or
digits block a match, while punctuation, separators, text edges, and CJK script
transitions are valid boundaries. Explicit outer sentence/wrapper punctuation
is ignored, but technical identifier characters `+`, `#`, `-`, `_`, `/`, and
`\` are preserved. CJK features and symbol-ending terms retain substring
matching. This removes deterministic false positives such as `rag` in
`storage`, `ai` in `OpenAI`, and `9042` in `19042` without breaking mixed
`中文SpringAI检索`, `型号9042说明`, `C++`, `C#`, or `api/v1` queries. Query
relevance terms are prepared once per rerank and reused for content and title.

This is a lightweight lexical improvement, not dictionary segmentation,
semantic reranking, or simplified/traditional/synonym normalization. Continue
comparing Chinese and English goldensets for MRR/nDCG/Recall@K and Search/Chat
p95. Use an HTTP cross-encoder provider when a higher ranking ceiling is
required.

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
    api-key: ${RERANK_API_KEY}
    model: BAAI/bge-reranker-v2-m3
    base-url: https://api.siliconflow.cn
```
