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
identical per-case numbers (measured twice at `mrr=0.9583 ndcg=0.9710`). With a
real remote embedding provider, float jitter between the two query embeddings can
swap two documents that were nearly tied. One verification run showed
`quality < baseline` (en-semantic-2 was a near tie) and an otherwise identical
rerun restored equality. That jitter is remote-model noise, not randomness in
retrieval logic; rerun the gate unchanged before treating it as a regression.

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

## Parsing And Chunking Retrieval Quality Gate (No Model / No Database)

A parsing change cannot be accepted on "text was extracted" alone.
`ParsingRetrievalQualityGateTest` runs the same format corpus through extraction,
chunking, and retrieval, then measures Recall@5, MRR, and nDCG@10. Any metric
below its floor turns `mvn test` red. It calls no real LLM, needs no Postgres,
and requires no Docker.

```bash
mvn -o -pl spring-ai-rag-core -am \
  -Dtest=ParsingRetrievalQualityGateTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

- Corpus: ten documents generated deterministically at runtime, covering five
  PDF layouts (two-column body text, three-column table, two-column table, two
  column body text with an embedded two-column table, and a single-column wide
  table), plus a DOCX with paragraphs and a table, a Markdown file with a code
  block and a table, an HTML file with script/style noise, and GBK plain text.
  Fixtures live in `DocumentParsingCorpus`.
- Queries and floors:
  `spring-ai-rag-core/src/test/resources/retrieval/parsing-retrieval-quality-gate-v1.json`
  (sixteen queries, each with a fixed target `documentId` and `expectedTerms`
  that must co-occur in one chunk).
- Embedding: in-test FNV-1a feature hashing, 1024 dimensions, signed hashing,
  L2-normalized; Latin runs and CJK unigrams/bigrams are tokenized. The same
  text always yields the same vector.
- Ordering: `score desc → documentId` lexicographic `→ chunkIndex` ascending;
  equal scores never depend on Map/Hash iteration order.
- Relevance: the chunk belongs to the target document and contains every
  `expectedTerm` of the query.

Baseline (measured 2026-11-05 with the fixed corpus and hashed embedding):

| Metric | Measured | Floor | Headroom |
| --- | --- | --- | --- |
| Recall@5 | 1.000 | 0.95 | 5 points |
| MRR | 1.000 | 0.95 | 5 points |
| nDCG@10 | 1.000 | 0.95 | 5 points |

The floors are "measured 1.0 minus 5 points": losing any one of the sixteen
queries drops all three metrics to `15/16 = 0.9375`, below 0.95. A single query
moving from rank 1 to rank 2 leaves Recall at 1.0, MRR at `(15 + 0.5)/16 ≈
0.969`, and nDCG around `0.977`, still above the floors, so mild ranking jitter
is tolerated. Losing two queries gives `14/16 = 0.875` and turns the gate red.

PDF layout criteria (recorded per page in `PdfTextExtractor.PageLayout` and
exposed through `ExtractedDocument.layoutDiagnostics()`):

- Dominant gutter: the widest x-cluster gap must be ≥ 8% of the page width and,
  with three or more clusters, ≥ 1.5× the second-widest gap; only then is the
  page reordered by column. A three-column table (two similar gaps of 120/100)
  therefore stays in row order.
- With exactly two x clusters the gap alone is not enough: short cells ≥ 0.5
  plus paired left/right rows ≥ 0.7 select row order as a table; a continuation
  ratio ≥ 0.5 among long adjacent lines (≥ 20 chars, no sentence-final
  punctuation), or a long-line ratio ≥ 0.6, selects column reordering.
- Conflicting or insufficient evidence degrades to `ROW_ORDER` with a recorded
  reason: `SINGLE_CLUSTER`, `NO_DOMINANT_GUTTER`, `CELL_LIKE_COLUMNS`,
  `AMBIGUOUS_MIXED_SIGNALS`, or `AMBIGUOUS_FALLBACK`. Nothing is guessed
  silently.

Chunk-quality assertions (any failure turns red):

- No blank chunk, no chunk longer than `size=1000`, no
  `ChunkDiagnostics.violations`, and 100% coverage of the source's
  non-whitespace characters.
- Two-column and mixed-layout PDFs must follow `columnOrder` (the whole left
  column before the right column); no chunk may interleave them row by row.
- Table rows (same-row cells in two/three-column PDFs, embedded tables in mixed
  layouts, and DOCX/Markdown table rows) must stay on one output line and fall
  entirely inside one chunk; `CODE`/`TABLE` protected-block metadata remains
  readable on the retrieval side.
- Every chunk carries `documentId`, contiguous `chunkIndex`, and a nonblank
  `title`; a hit must locate back to its target document.

Residual samples (measured and reported, not hidden):

- `ambiguousTwoClusterPdf` (two x clusters, medium-length lines, neither
  continuation nor short cells) degrades to `ROW_ORDER` with
  `AMBIGUOUS_FALLBACK`, preserving fidelity.
- When both columns contain embedded tables of similar width, several nearly
  equal gaps yield `NO_DOMINANT_GUTTER` and the whole page falls back to row
  order (a predictable degradation).
- Long free-text cells (≥ 20 chars, no sentence-final punctuation) are
  geometrically indistinguishable from wrapped body lines and may still be
  treated as two-column body text; key/value and short-cell two-column tables
  are covered by fixed tests.

Determinism and idempotency:

- Indexing the same corpus twice produces identical extracted text, chunk list,
  and `ChunkDiagnostics`, item by item.
- A zero-hit query (all scores 0) verifies the tie-break: results are ordered
  strictly by `documentId`, then `chunkIndex`, and repeated ranking is identical.
- External-document sync idempotency is pinned by
  `DocumentMutationExternalCasTailTest#unchangedWhenSameRevisionAndSameManagedState`:
  the same `sourceRevision` with unchanged managed fields returns `UNCHANGED`
  and writes nothing.

Sensitivity proof: the gate contains two reverse cases — removing every relevant
chunk must yield `0/0/0`, and reversing the ranking must push MRR/nDCG below
their floors. Both passing proves the floors actually reject recall or ranking
regressions instead of staying permanently green.

## Retrieval Result Reproducibility

Retrieval correctness is pinned by the gated
`RetrievalCorrectnessPostgresIntegrationTest`, which uses deterministic 1024-dimensional
fake embeddings and a fixed corpus, independent of real model quality:

```bash
./scripts/verify-gated-it.sh RetrievalCorrectnessPostgresIntegrationTest
```

Reproducibility boundaries:

- Since Flyway V81 the active 1024-dimensional vector column `embedding_1024` carries an
  HNSW index (`idx_rag_embeddings_embedding_1024_hnsw`, cosine); retrieval may use an
  approximate scan. Repeated searches over the same corpus and parameters are only
  guaranteed to agree on the document set within a score tolerance; top-k boundaries and
  tie order are no longer guaranteed (the former exact-equality contract is replaced by
  tolerance-based agreement since V81).
- The vector channel, the `pg_trgm`/English full-text channels, and fusion all use
  `documentId`, then `chunkIndex` as the equal-score tie-break, so both in-channel and
  fused order are deterministic.
- The gated `RetrievalCorrectnessPostgresIntegrationTest` uses deterministic fake
  embeddings and a fixed corpus; after V81, if the gated assertions show top-k jitter,
  revise the assertions to this tolerance-based contract instead of dropping the index.
- Goldenset runs in nightly against a fresh isolated database and a real embedding
  provider; it reproduces reliably within the same provider/model/revision. Absolute
  scores are not comparable across providers or model versions, and the regression gate
  compares only the relative `useRerank=false/true` delta within one run (identical when
  rerank is not enabled). Each run uses a timestamped collection, so repeated runs do not
  pollute existing data; local debugging can reuse one corpus with `--skip-create`.

## Real-Embedding Retrieval Baseline (Human-Labeled Goldenset)

This is a **separate layer** from the parsing/chunking quality gate:

- `ParsingRetrievalQualityGateTest` uses feature-hashed fake embeddings. It proves the
  parsing, chunking, and ranking pipeline is correct and reproducible under `mvn test`;
  it does **not** represent semantic retrieval quality.
- This goldenset uses a real embedding provider (BGE-M3 / 1024 dims) and measures only
  semantic quality. It is not part of the PR fast layer and is not run by default in
  nightly; it requires an explicit opt-in.

Dataset and labels:

- 32 documents under `testdata/real-goldenset/corpus/`: 10 multi-column PDFs, 6 DOCX,
  6 MD, 5 HTML, 4 TXT (one GBK), and 1 CSV; they cover multi-column prose, tables,
  mixed Chinese/English content, and code blocks.
- 48 queries under `testdata/real-goldenset/queries.json`, **written and labeled by a
  human**. A fragment is a hit only when every `relevant[].terms` entry appears in the
  same chunk. Query types: normal / short / identifier / table-cell / cross-doc /
  paraphrase-zh.
- `scripts/test-support/generate-real-goldenset-corpus.py` is byte-for-byte
  reproducible: PDF metadata/document ID and DOCX timestamps are fixed.

Run (real paid calls, explicit switch):

```bash
# Isolated database plus RAG_ROOT_API_KEY / embedding key from .env;
# never point this at a shared production database.
python3 scripts/run-real-embedding-goldenset.py \
  --base-url http://127.0.0.1:18091 \
  --collection-key real-goldenset-<date> \
  --ack-paid-models
```

Without `--ack-paid-models` (or `RAG_REAL_GOLDENSET_ACK_PAID_MODELS=1`) the script
refuses to run. Keys are read only from `.env` and never printed. A full run issues
about 32 document embeddings plus 5x48 query embeddings; rerunning with
`--skip-create --manifest <previous manifest>` measures retrieval only and issues no
document embeddings.

Real baseline (2026-11-05, SiliconFlow `BAAI/bge-m3`, 1024 dims, `useRerank=false`,
`minScore=0`, top-10, 48 queries; the table reflects the keyword-channel OR fix):

| Variant | R@5 | R@10 | MRR | Fragment MRR | nDCG@10 |
|---------|-----|------|-----|--------------|---------|
| Vector only (`useHybridSearch=false`) | 0.917 | 0.938 | 0.822 | 0.812 | 0.846 |
| Hybrid 50/50 | 0.917 | 0.938 | 0.881 | 0.875 | 0.892 |
| **Hybrid 55/45 (production default, baseline)** | **0.917** | **0.938** | **0.871** | **0.864** | **0.884** |
| Hybrid 70/30 | 0.917 | 0.938 | 0.875 | 0.864 | 0.886 |
| Hybrid 30/70 | 0.917 | 0.938 | 0.871 | 0.864 | 0.884 |
| Keyword only (0/1) | 0.823 | 0.823 | 0.802 | 0.802 | 0.802 |

Remote embedding float differences can swap individual query ranks between runs;
MRR / nDCG@10 jitter by about +/-0.004. The table is the last full run on 2026-11-05.

Group breakdown (production default 55/45):

- Language: en (20) R@5/R@10 1.000/1.000, MRR 0.950, nDCG@10 0.959; zh (26)
  0.846/0.885, MRR 0.800, nDCG@10 0.821; mixed (2) 1.000/1.000, MRR 1.000,
  nDCG@10 0.960.
- Format: HTML (5) and CSV (1) are all 1.0; DOCX (8) R@10 1.000, MRR 0.917,
  nDCG@10 0.938; PDF (22) R@10 0.909, MRR 0.847, nDCG@10 0.854; MD (8) R@10
  0.875, MRR 0.854, nDCG@10 0.860; TXT (4) R@10 1.000, MRR 0.750, nDCG@10 0.815.
- Failure type: normal (17) all 1.0; table-cell (11) R@10 1.000, MRR 1.000,
  nDCG@10 1.000; identifier (6) R@10 1.000, MRR 0.917, nDCG@10 0.938; cross-doc
  (3) R@10 1.000, MRR 0.833, nDCG@10 0.821; short (5) R@10 0.800, MRR 0.800,
  nDCG@10 0.800; paraphrase-zh (6) R@10 0.667, MRR 0.302, nDCG@10 0.391.

Comparison and noise:

- Keyword-only R@10 moves from 0.125 to 0.823. A paired bootstrap over the same
  48 queries (10000 resamples) gives a 95% CI for the R@10 delta of
  **[+0.563, +0.823]**, excluding 0: the channel improvement is **significant**.
  MRR moves from 0.125 to 0.802 (CI `[+0.552, +0.802]`).
- Hybrid 55/45 before/after the fix: MRR 0.838 to 0.871 (delta CI
  `[-0.016, +0.087]`) and nDCG@10 0.858 to 0.884 (delta CI `[-0.009, +0.065]`).
  The direction is positive but the intervals include 0; 48 queries cannot
  establish a significant overall hybrid change.
- Within the new run, vector-only to hybrid 55/45: MRR 0.822 to 0.871 (CI
  `[+0.007, +0.097]`) and nDCG@10 0.846 to 0.884 (CI `[+0.008, +0.074]`); both
  intervals exclude 0. This is the first time this goldenset supports "hybrid is
  better than vector-only" as significant, but the interval covers query
  sampling only, not other corpora or model versions.
- Weights: 50/50 is numerically highest in this run (MRR 0.881, nDCG@10 0.892),
  but its delta versus 55/45 has a 95% CI of MRR `[0.000, +0.031]` and nDCG
  `[0.000, +0.023]`; the lower bound touches 0, so this is noise. The production
  default stays at 55/45. Fusion is weighted RRF with a fixed, non-configurable K,
  so this experiment sweeps weights but not K.

Representative failures and attribution (post-fix baseline):

- **Cross-language paraphrase (embedding; no keyword candidate)**: q-para-01,
  "How does the platform handle retries without double charging?", targets the
  English PDF sentence "Idempotency keys are recorded for 24 hours" and misses
  top-10; top1 is the Chinese 429/Retry-After troubleshooting manual. The Chinese
  query is routed to jieba, English documents contain no Chinese lexemes, and
  pure keyword search cannot cross languages; `double charging` and `idempotency`
  still rely on the embedding.
- **Short query plus cross-language table fact (embedding-primary)**: q-short-02,
  "audit retention", targets the "Audit retention / 400 days" table and misses
  top-10; top1 is "Audit logs ... 90 days". A four-character query is too weak,
  and a Chinese query cannot produce keyword candidates in an English table; this
  remains an embedding failure.
- **Label ambiguity (not a system error)**: q-para-04 annotates only `Retry-After`
  in `md-code-en.md`, but top1 `md-mixed-zh.md` also gives `Retry-After` and
  exponential backoff, which is a correct answer; the query therefore earns
  document-level credit only. q-para-05 is similar: top1 from the Chinese
  operations manual says to drain traffic and wait for an empty queue before
  stopping a process, which is correct, while the annotated English
  `nebulactl drain --node` misses top-10. Both stay in the goldenset as known
  label boundaries; labels are not rewritten to fit the system.
- **Tables**: none of the 11 table-cell queries failed because a table row was
  split; chunk previews confirm that real two- and three-column rows stay intact.

Fixes and before/after:

1. **Multi-column PDF layout misclassification (application defect)**: the old
   `PdfTextExtractor` recorded one line start per `writeString` call, but PDFBox merges
   left and right columns on the same baseline into one call; Chinese continuation
   tails were also counted as short cells, so `pdf-two-column-zh-ops.pdf` was classified
   as `ROW_ORDER` and emitted line by line interleaved. The fix splits `VisualLine` by
   glyph gaps, computes statistics on merged side lines, and excludes continuation
   lines from the short-cell ratio. Before the fix the fragment "lease expires after 60
   seconds" was missing (a fragment loss); after the fix all 48 annotations are present
   and that query ranks first. Regression test:
   `mixedScriptTwoColumnProseKeepsColumnOrder`.
2. **Chunk overlap splitting words (application defect)**: `BoundedTextChunker` stepped
   the overlap start back by a raw character count, producing "...node uns" plus
   "chedulable. ...". The fix snaps the start back to a word/sentence boundary within
   32 characters. Baseline metrics at that time were unchanged (hybrid 55/45
   MRR/nDCG stayed at 0.838/0.858; vector-only MRR moved by -0.0035, roughly one
   query rank). This is a text-integrity fix, not a metric gain. Regression test:
   `overlapStartSnapsToWordBoundary`.
3. **Goldenset corpus generator (evaluation data only)**: the `china-s` width estimate
   was too low and let two-column text cross the gutter; PDF/DOCX timestamps made the
   output non-reproducible. Text width now uses full glyph width, and PDF metadata /
   document ID and DOCX timestamps are fixed. Application runtime is unaffected.
4. **Keyword channel failure (application defect, 2026-11-05)**: the English and
   Chinese full-text channels used `websearch_to_tsquery` (AND semantics), so long
   queries returned only 0-2 candidates and keyword-only R@10 was 0.125. The fix:
   - The database tokenizes the query once (`tsvector_to_array`), joins lexemes with
     OR into `to_tsquery`, and ranks by `ts_rank_cd`; the GIN index still selects the
     candidates, and `ORDER BY rank + LIMIT` truncates the list.
   - The document title is weighted separately via
     `ts_rank_cd(to_tsvector(config, title), q)` (weight 0.35).
   - Hyphenated identifiers/model numbers in the query (up to 3, for example
     `NEBULA-300` or `RAG-4291`) get a +1.0 exact-substring boost, so `NEBULA-300`
     no longer loses to `NEBULA-400`. Identifiers inside Chinese queries still hit
     the same identifier in English documents because jieba keeps the numeric part
     as a lexeme and the substring boost compares raw text.
   - Noise control: when the query has 3 or more lexemes, a chunk must match at
     least half of them (rounded up); an exact identifier substring hit passes the
     floor directly (otherwise the Chinese remainder would exclude the only correct
     identifier chunk). The coverage subquery only runs on rows already selected
     by the GIN scan.
   - `EXPLAIN (ANALYZE, BUFFERS)` confirms a `Bitmap Index Scan` for both
     `search_vector_en` and the jieba expression index; the coverage subquery
     `loops` equals the candidate count, so there is no sequential scan. No new
     migration or index is needed (V43 GIN indexes are reused).
   Result: keyword-only R@10 0.125 to 0.823 (CI `[+0.563, +0.823]`, significant);
   hybrid 55/45 MRR 0.838 to 0.871 and nDCG@10 0.858 to 0.884; within the same run
   vector-only to hybrid becomes significant (nDCG CI `[+0.008, +0.074]`).
   identifier group MRR 0.792 to 0.917; table-cell 0.909 to 1.000; short 0.667 to
   0.800. q-id-02 ranks first in the keyword-only channel (MRR 1.000), and the
   adjacent-identifier interference in q-id-02/q-id-05 is gone.
   Residual and rollback decision: the TXT group MRR moves from 0.812 to 0.750 and
   nDCG@10 from 0.858 to 0.815, all
   from a symmetric RRF tie in q-zh-08 (`txt-utf8-zh` and `txt-gbk-zh` are two
   encodings of the same content, each slightly stronger in a different channel, so
   near-equal weighted RRF sums flip the order). The paraphrase-zh group MRR moves
   from 0.440 to 0.302 because q-para-03 has no literal overlap with its answer and
   a Chinese chunk with 3/5 coverage is inserted one rank above. Neither is a
   systematic regression: Chinese aggregate MRR moves from 0.768 to 0.800, English
   from 0.912 to 0.950, and overall hybrid MRR/nDCG improve. Reverting OR would
   also discard the +0.698 keyword-only R@10 gain, so this FTS change is not rolled
   back. Cross-language paraphrase still relies on vectors; the keyword channel does
   not pretend to solve it.
   Long English queries (for example q-en-02, q-cross-01/02) return no keyword
   candidates when literal coverage stays below half; vectors carry them. This is
   the expected cost of the coverage floor, not a channel failure.

Known quality boundaries:

- 32 documents and 48 queries are a small sample. The bootstrap CI is wide, so small
  leads must not be called significant, and results do not extrapolate to other corpora
  or model versions.
- Fragment scoring is an exact whitespace-normalized substring check; semantically
  equivalent chunks with different wording do not count. nDCG de-duplicates by document
  and uses a 2/1/0 scale, so scores are conservative.
- Chinese paraphrase and cross-language synonyms remain the weakest areas: a Chinese
  query is routed to jieba, English documents contain no Chinese lexemes, and pure
  keyword search cannot cross languages, so vectors remain primary. Adjacent
  identifiers/model numbers are fixed by the exact-substring boost.
- Long queries can still admit low-coverage literal matches. The current guard is the
  "at least half the lexemes" floor plus top-k truncation; no more aggressive
  semantic filtering is applied.
- Cross-language / synonym query expansion (LLM rewriting or glossary translation
  expansion) is **not implemented and was terminated**: there is no default-on
  expansion channel and no expansion budget/fallback implementation, so Chinese
  queries still rely on vectors to reach English documents. Any restart must first
  show Chinese-group gains without p95 regression before default-on is considered.
- The baseline fixes `useRerank=false`; it says nothing about reranking, another
  embedding model, or a different chunking profile.

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

## Multi-tenant HNSW + RLS benchmark (WP4)

Proven by the real-PostgreSQL integration test
`TenantHnswRlsBenchmarkPostgresIntegrationTest`, explicitly opt-in:

```bash
mvn -o -pl spring-ai-rag-core test \
  -Dtest=TenantHnswRlsBenchmarkPostgresIntegrationTest \
  -Dtenant-hnsw-bench.it.enabled=true
```

The report is written to `spring-ai-rag-core/target/tenant-hnsw-rls-benchmark.json`.
The benchmark uses 2000 deterministic synthetic 1024-dimensional vectors in a
task-scoped `pgvector/pgvector:pg16` Testcontainers database, never a shared database.

### Hard gates (any failure blocks release)

1. The active vector column `embedding_1024` must carry a valid HNSW/IVFFlat index
   (`annIndexOnEmbedding1024 >= 1`, non-INVALID). The legacy `embedding` column from V1
   was dropped in V68 together with its index; V81 adds
   `idx_rag_embeddings_embedding_1024_hnsw`.
2. Query latency with RLS enabled must not exceed 3x the latency with RLS disabled:
   `rlsQueryMs <= 3 * noRlsQueryMs` (coarse single-host Testcontainers gate on synthetic
   data; production scale must be re-measured).

### Recorded facts and known gaps (not silently passed)

- `planUsesIndex`: the planner choice for the RLS query over 2000 synthetic rows.
  Currently measured `false` (`Seq Scan + top-N heapsort`) because a full scan is cheaper
  on a small dataset with a global HNSW plus an RLS Filter. This is **not** a missing ANN index.
- `annIndexUsable` / `pureAnnUsesHnsw`: must be `true`, proving the HNSW index itself is
  usable (the former with `enable_seqscan=off`, the latter without RLS).
- Plan §9 lists "global HNSW top-k crowded out by other tenants under RLS" as a
  phase-one performance gate. This benchmark covers **latency** and **index presence** only;
  it does **not** cover Recall@K on a mixed multi-tenant corpus. Do not claim production
  multi-tenant retrieval quality from this benchmark until either:
  1. a production-scale, mixed-tenant Recall@K benchmark passes; or
  2. pgvector iterative scan / over-fetch-then-filter / tenant partitioning is enabled
     and proven by a dedicated benchmark.
- `planUsesIndex=false` and "mixed-tenant recall unmeasured" remain known WP4 gaps /
  downstream blockers, recorded in the progress ledger; entering WP5+ does not clear them.
