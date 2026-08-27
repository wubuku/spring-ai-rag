# Configuration Reference

> 📖 English | 📖 中文

Complete reference for all Spring AI RAG configuration items. All business configurations are managed uniformly under the `rag.*` prefix.

## Quick Configuration

### Minimum Startup Configuration

```yaml
# application.yml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/spring_ai_rag_dev
    username: postgres
    password: postgres
  ai:
    openai:
      api-key: ${OPENAI_API_KEY}
      base-url: https://api.deepseek.com
      chat:
        enabled: false

app:
  llm:
    provider: openai

rag:
  embedding:
    api-key: ${SILICONFLOW_API_KEY}
```

## LLM Configuration

### Provider Switching

Switch LLM providers via `app.llm.provider`:

| Provider | `app.llm.provider` | Config Prefix | Notes |
|----------|-------------------|---------------|-------|
| DeepSeek | `openai` | `spring.ai.openai.*` | OpenAI-compatible interface |
| Zhipu GLM | `openai` | `spring.ai.openai.*` | OpenAI-compatible interface |
| Anthropic | `anthropic` | `spring.ai.anthropic.*` | Separate starter |

**Important**: `spring.ai.openai.chat.enabled` and `spring.ai.anthropic.chat.enabled` must be set to `false`; Beans are created manually by `SpringAiConfig`.

### OpenAI / DeepSeek Configuration

```yaml
spring:
  ai:
    openai:
      api-key: ${OPENAI_API_KEY}
      base-url: ${OPENAI_BASE_URL:https://api.deepseek.com}
      chat:
        enabled: false
        options:
          model: ${OPENAI_MODEL:deepseek-chat}
          temperature: ${OPENAI_TEMPERATURE:0.7}
```

| Property | Default | Description |
|----------|---------|-------------|
| `spring.ai.openai.api-key` | (required) | API Key |
| `spring.ai.openai.base-url` | `https://api.deepseek.com` | API endpoint; do not append `/v1` |
| `spring.ai.openai.chat.options.model` | `deepseek-chat` | Legacy/default model name |
| `spring.ai.openai.chat.options.temperature` | `0.7` | Generation temperature |

### Anthropic Configuration

```yaml
spring:
  ai:
    anthropic:
      api-key: ${ANTHROPIC_API_KEY}
      base-url: ${ANTHROPIC_BASE_URL:https://api.anthropic.com}
      chat:
        enabled: false
        options:
          model: ${ANTHROPIC_MODEL:claude-3-5-sonnet-20241022}
          temperature: ${ANTHROPIC_TEMPERATURE:0.7}
          max-tokens: ${ANTHROPIC_MAX_TOKENS:4096}
```

### Runtime Model Selection

`app.models.providers` defines independently instantiated chat models. Each
provider declares an API type, endpoint, credential placeholder, and one or more
model IDs:

```yaml
app:
  models:
    config-file: ${MODELS_CONFIG_FILE:}
    providers:
      openrouter:
        displayName: OpenRouter
        baseUrl: https://openrouter.ai/api
        apiKey: ${OPENROUTER_API_KEY:}
        apiType: openai-completions
        enabled: true
        priority: 1
        models:
          - id: xiaomi/mimo-v2-pro
            name: MiMo V2 Pro
            type: chat
            contextWindow: 600000
            maxTokens: 32000
    chatModel:
      primary: openrouter/xiaomi/mimo-v2-pro
      fallbacks: []
```

Use the returned `providerId/modelId` reference from
`GET /api/v1/rag/models` as the `model` field on both chat endpoints. An
explicit unknown or unavailable model returns HTTP 400; omitting `model` uses
the configured primary/fallback chain. An external JSON file fully replaces
the YAML model registry; see
[multi-model-external-config.md](multi-model-external-config.md).

### Durable Model-Invocation Usage Ledger

The usage ledger records one bounded terminal fact for each model invocation
that belongs to a Chat execution. It includes provider-reported token usage
when available, the model and invocation purpose, outcome, duration, and a
configuration-based cost estimate. It never stores prompts, answers, tool
arguments/results, credentials, or exception bodies. Recording is fail-open:
provider results and Chat responses do not fail because the ledger is
temporarily unavailable.

```yaml
rag:
  usage:
    enabled: ${RAG_USAGE_ENABLED:true}
    cost-unit: ${RAG_USAGE_COST_UNIT:CONFIGURED_MODEL_COST}
    retention-days: ${RAG_USAGE_RETENTION_DAYS:400}
    cleanup-enabled: ${RAG_USAGE_CLEANUP_ENABLED:true}
    cleanup-batch-size: ${RAG_USAGE_CLEANUP_BATCH_SIZE:1000}
    cleanup-max-batches: ${RAG_USAGE_CLEANUP_MAX_BATCHES:20}
    cleanup-cron: ${RAG_USAGE_CLEANUP_CRON:0 20 * * * *}
    recorder-threads: ${RAG_USAGE_RECORDER_THREADS:2}
    recorder-queue-capacity: ${RAG_USAGE_RECORDER_QUEUE_CAPACITY:1000}
    record-timeout-ms: ${RAG_USAGE_RECORD_TIMEOUT_MS:2000}
```

| Property | Default | Description |
|----------|---------|-------------|
| `rag.usage.enabled` | `true` | Record new invocation facts and enable durable usage aggregation |
| `rag.usage.cost-unit` | `CONFIGURED_MODEL_COST` | Printable identifier for the configured estimate currency/unit |
| `rag.usage.retention-days` | `400` | Retain events for 30–3650 days |
| `rag.usage.cleanup-enabled` | `true` | Enable bounded scheduled deletion of expired events |
| `rag.usage.cleanup-batch-size` | `1000` | Rows per cleanup batch, 100–10000 |
| `rag.usage.cleanup-max-batches` | `20` | Maximum batches per scheduled run, 1–100 |
| `rag.usage.cleanup-cron` | `0 20 * * * *` | Spring cron for retention cleanup |
| `rag.usage.recorder-threads` | `2` | Fixed recorder worker count, 1–16 |
| `rag.usage.recorder-queue-capacity` | `1000` | Bounded recorder queue, 100–10000 |
| `rag.usage.record-timeout-ms` | `2000` | Timeout for synchronous record confirmation, 100–10000 ms |

The ledger is additive and stored in `rag_llm_usage_event` by Flyway V53.
Non-streaming terminal facts wait only for the bounded record timeout;
streaming terminal facts are queued asynchronously. A full queue, timeout, or
database error increments the local lost-event metric and is not retried by
the provider path. Retention uses bounded batches and a short database
statement timeout.

`GET /api/v1/rag/usage` exposes principal-scoped aggregate data for an
inclusive UTC date range. A normal authenticated principal can query only
itself; an ADMIN or environment root may query all principals or one selected
principal. Token totals are `BigDecimal` aggregates because the response is
not limited to a single database integer. Missing provider usage, missing
model pricing, and unavailable cost estimates are represented explicitly
instead of being inferred as zero. See the [REST API reference](rest-api.md)
for the response shape and privacy boundary.

### OpenAI Chat Completions Server Compatibility

The controlled adapter is disabled by default. When enabled, it exposes
`GET /v1/models`, `GET /v1/models/{id}`, and `POST /v1/chat/completions`.
A public model alias identifies a RAG pipeline and backend candidate chain; it
does **not** contain a fixed Collection. Each request supplies Collection scope
through `rag.scope` or repeated `X-RAG-Collection-Key` headers, still bounded by
the API-key ACL.

```yaml
rag:
  openai-compatibility:
    enabled: ${RAG_OPENAI_COMPATIBILITY_ENABLED:false}
    require-explicit-scope: ${RAG_OPENAI_REQUIRE_EXPLICIT_SCOPE:false}
    models:
      rag-default:
        candidates: []
        mode: KNOWLEDGE
        memory: STATELESS
        allow-request-mode-override: false
        allow-request-memory-override: false
```

| Property | Default | Description |
|----------|---------|-------------|
| `rag.openai-compatibility.enabled` | `false` | Register `/v1/models` and `/v1/chat/completions` |
| `rag.openai-compatibility.require-explicit-scope` | `false` | Require every request to supply `rag.scope` or a Collection header |
| `rag.openai-compatibility.models.<alias>.candidates` | `[]` | Internal `provider/modelId` candidate chain; empty uses the global primary/fallback |
| `rag.openai-compatibility.models.<alias>.mode` | `KNOWLEDGE` | Default Chat mode for the alias |
| `rag.openai-compatibility.models.<alias>.memory` | `STATELESS` | Default memory mode for the alias |
| `allow-request-*-override` | `false` | Whether the request may override alias mode/memory |

The current controlled preview is text-only and supports `n=1`. Unsupported
OpenAI parameters return an explicit compatible error envelope instead of
being silently ignored. See [REST API](rest-api.md) for protocol and scope
examples.

## Embedding Model Configuration

Embedding model configuration is independent of the Chat provider and is always active.

```yaml
rag:
  embedding:
    api-key: ${SILICONFLOW_API_KEY}
    base-url: ${SILICONFLOW_URL:https://api.siliconflow.cn}
    model: ${SILICONFLOW_MODEL:BAAI/bge-m3}
    dimensions: ${SILICONFLOW_DIMENSIONS:1024}
    retry-max-attempts: ${RAG_EMBEDDING_RETRY_MAX_ATTEMPTS:10}
    profile-key: ${RAG_EMBEDDING_PROFILE_KEY:siliconflow-bge-m3-1024-v1}
    provider: ${RAG_EMBEDDING_PROVIDER:siliconflow}
    model-revision: ${RAG_EMBEDDING_MODEL_REVISION:unspecified}
    distance-metric: COSINE
    normalization: PROVIDER_DEFAULT
    migration-mode: ${RAG_EMBEDDING_MIGRATION_MODE:none}
    migration-legacy-profile-key: ${RAG_EMBEDDING_MIGRATION_LEGACY_PROFILE_KEY:}
    migration-confirm: ${RAG_EMBEDDING_MIGRATION_CONFIRM:}
```

| Property | Default | Description |
|----------|---------|-------------|
| `rag.embedding.api-key` | `""` | SiliconFlow API Key |
| `rag.embedding.base-url` | `https://api.siliconflow.cn` | API endpoint |
| `rag.embedding.model` | `BAAI/bge-m3` | Embedding model name |
| `rag.embedding.dimensions` | `1024` | Vector dimensions (must match model output) |
| `rag.embedding.retry-max-attempts` | `10` | Maximum attempts inside one provider call, range 1–10; independent from durable-job attempts |
| `rag.embedding.profile-key` | `siliconflow-bge-m3-1024-v1` | Immutable model-space identity used by writes and retrieval |
| `rag.embedding.provider` | `siliconflow` | Provider identity stored in the Profile |
| `rag.embedding.model-revision` | `unspecified` | Explicit revision identity; changing model semantics requires a new Profile |
| `rag.embedding.distance-metric` | `COSINE` | Distance metric; only `COSINE` is supported in this release |
| `rag.embedding.normalization` | `PROVIDER_DEFAULT` | Normalization semantics stored in the Profile |
| `rag.embedding.migration-mode` | `none` | Startup migration mode for explicit Legacy adoption |
| `rag.embedding.migration-legacy-profile-key` | `""` | Existing Profile key used for Legacy adoption |
| `rag.embedding.migration-confirm` | `""` | Exact confirmation required by the Legacy adoption operation |

The active Profile is registered in `rag_embedding_profiles` and is immutable after creation.
The current supported dimension is `1024`, stored in the fixed-length
`rag_embeddings.embedding_1024 VECTOR(1024)` column. New vectors are also written to the
legacy `embedding` column during the compatibility window. Changing models requires a new
Profile and a complete re-embedding; do not change only `dimensions`, cast old vectors, or
mix Profiles in one retrieval query. Legacy vector adoption is explicit and requires the
configured confirmation value.

`rag.embedding.retry-max-attempts` bounds Spring Retry inside one
worker/provider call. It retries only Spring AI transient exceptions and
network-access failures while preserving the default exponential backoff. It
is independent from the durable-task budget in
`rag.embedding-jobs.default-max-attempts` /
`rag.embedding-jobs.max-attempts`; production deployments should bound both
layers so one failed task cannot cause unbounded external calls.

### Persistent Embedding Jobs

The job worker provides durable, retryable embedding/reindexing. It is enabled
by default from V41 because both `SYNC` and `ASYNC` document-content mutations
persist a job in the same transaction; `SYNC` only performs a bounded wait on
that same job. After the transaction commits, a lightweight Spring event wakes
the bounded worker in the same application instance. The event carries neither
content nor reliable state; the database job table remains the sole source of
truth. Operational callers can create, inspect, cancel, and retry jobs through
`/api/v1/rag/embedding-jobs`.

```yaml
rag:
  embedding-jobs:
    enabled: ${RAG_EMBEDDING_JOBS_ENABLED:true}
    sync-wait-seconds: ${RAG_EMBEDDING_JOBS_SYNC_WAIT_SECONDS:30}
    poll-interval-ms: ${RAG_EMBEDDING_JOBS_POLL_INTERVAL_MS:30000}
    claim-batch-size: ${RAG_EMBEDDING_JOBS_CLAIM_BATCH_SIZE:4}
    lease-seconds: ${RAG_EMBEDDING_JOBS_LEASE_SECONDS:120}
    default-max-attempts: ${RAG_EMBEDDING_JOBS_DEFAULT_MAX_ATTEMPTS:3}
    max-attempts: ${RAG_EMBEDDING_JOBS_MAX_ATTEMPTS:5}
    max-documents-per-batch: 1000
    retry-backoff-seconds: ${RAG_EMBEDDING_JOBS_RETRY_BACKOFF_SECONDS:10}
    worker-concurrency: ${RAG_EMBEDDING_JOBS_WORKER_CONCURRENCY:4}
```

`poll-interval-ms` is the low-frequency recovery scan for missed notifications,
process restarts, and worker failures, not the primary trigger for ordinary
jobs. It defaults to `30000ms` and is clamped to at least `10000ms`. Multiple
jobs created in one transaction produce one after-commit event. Concurrent
wake-ups are coalesced inside the worker, while the existing semaphore, claim
batch, and database lease still bound concurrency. Rollbacks publish no event;
a lost local event cannot lose work because the recovery scan rediscovers the
durable job.

Workers use PostgreSQL leases and atomic `UPDATE ... RETURNING` statements with
state and expiry predicates for multi-worker claims. Before committing vectors,
they recheck the active Profile, lease, cancellation flag, request generation,
document kind, chunker version, and content hash. Active work for the same
generation is coalesced. A content mutation allocates a later generation and
cancels old work, so an old worker cannot commit into a newer document. An
expired lease is reclaimed while retry attempts remain. If the
final allowed attempt expires, the job is atomically marked `FAILED` and its
lease fields are cleared, so it cannot remain permanently `RUNNING`.

Ingestion and explicit embed endpoints accept optional `embeddingPolicy`:
`SYNC` / `ASYNC` / `SKIP`. When present it overrides the legacy `embed`
boolean; when omitted, `embed=true` stays `SYNC` and `embed=false` stays
`SKIP`. Explicit embed/re-embed rejects `SKIP`. `ASYNC` requires
`rag.embedding-jobs.enabled=true` or returns `503 EMBEDDING_JOBS_DISABLED`.

### Document Lifecycle And External Source Synchronization

```yaml
rag:
  document-lifecycle:
    strict-external-cas: ${RAG_DOCUMENT_STRICT_EXTERNAL_CAS:true}
    allow-non-default-namespace: ${RAG_DOCUMENT_ALLOW_NON_DEFAULT_NAMESPACE:true}
    idempotency-ttl-hours: ${RAG_DOCUMENT_IDEMPOTENCY_TTL_HOURS:24}
    sync-runs-enabled: ${RAG_DOCUMENT_SYNC_RUNS_ENABLED:false}
    version-restore-enabled: ${RAG_DOCUMENT_VERSION_RESTORE_ENABLED:false}
    relocation-enabled: ${RAG_DOCUMENT_RELOCATION_ENABLED:false}
    derivation-repair-enabled: ${RAG_DOCUMENT_DERIVATION_REPAIR_ENABLED:false}
    sync-run-max-missing-absolute: ${RAG_DOCUMENT_SYNC_RUN_MAX_MISSING_ABSOLUTE:1000}
    sync-run-max-missing-percent: ${RAG_DOCUMENT_SYNC_RUN_MAX_MISSING_PERCENT:20}
```

| Property | Default | Description |
|----------|---------|-------------|
| `rag.document-lifecycle.strict-external-cas` | `true` | A new revision for an existing external identity requires `expectedSourceRevision`; exact replay remains valid |
| `rag.document-lifecycle.allow-non-default-namespace` | `true` | Accept explicit external `sourceNamespace`; when disabled only the compatibility value `default` is accepted |
| `rag.document-lifecycle.idempotency-ttl-hours` | `24` | Retention for local create/upload `Idempotency-Key` records, clamped to 1–168 hours |
| `rag.document-lifecycle.sync-runs-enabled` | `false` | Enables the authoritative external snapshot Sync Run API and its durable item-receipt query; keep disabled until the disposable PostgreSQL/E2E acceptance passes |
| `rag.document-lifecycle.version-restore-enabled` | `false` | Enables local `FULL` historical-version restore; externally managed documents remain source-owned |
| `rag.document-lifecycle.relocation-enabled` | `false` | Enables atomic cross-Collection relocation for externally managed documents; run the relocation gate before enabling |
| `rag.document-lifecycle.derivation-repair-enabled` | `false` | Enables side-effecting derivation repair preview/apply; read-only derivation readiness remains available |
| `rag.document-lifecycle.sync-run-max-missing-absolute` | `1000` | Absolute missing-document safety threshold for `TOMBSTONE` runs; completion is rejected above it without explicit confirmation; clamped to 1–100000 |
| `rag.document-lifecycle.sync-run-max-missing-percent` | `20` | Relative missing-document safety threshold (percent) for `TOMBSTONE` runs; clamped to 1–100 |

Local documents use public `documentRevision` CAS for PATCH, disable, restore,
and permanent delete. External text and JSON records use
`collectionKey + sourceNamespace + externalId` plus an opaque
`sourceRevision`. Content mutations commit their durable derivation job in the
same transaction. Metadata, JSONB payload, and Collection-only mutations do
not request an embedding.

For enabled documents, every non-`SKIP` content mutation prepares local
keyword chunks in V43's `rag_document_chunks` table and records their
independent freshness in `rag_document_local_index_state`. This path does not
depend on an embedding provider or Profile. `SKIP` removes the current local
chunks and marks local indexing `NOT_REQUESTED`. Full-text retrieval can
therefore remain available as `KEYWORD_ONLY` while the remote embedding branch
is queued, processing, or failed. There is no separate configuration flag for
this behavior.

For ordinary external-text synchronization, `collectionKey` is required and
must identify a real active Collection. JSON-record upsert retains deprecated
numeric input but resolves it to the same canonical key-based address.
`sourceNamespace` may be omitted or blank and is
normalized to `default`; `allow-non-default-namespace` controls whether other
explicit namespaces are accepted. Current identifier limits are 128 characters
for `collectionKey` and `sourceNamespace`, and 255 for `externalId`. These
limits are part of the external client contract and must not be reduced by a
future migration. `default` is a compatibility namespace, not a default
Collection; a `NULL` Collection is only the local/unassigned state.

### Permanent Collection Purge And Retirement

The high-risk purge capability is disabled by default. Run the focused
PostgreSQL, WebUI, and real lifecycle acceptance before enabling it:

```yaml
rag:
  collection-purge:
    enabled: ${RAG_COLLECTION_PURGE_ENABLED:false}
    allow-auth-disabled: ${RAG_COLLECTION_PURGE_ALLOW_AUTH_DISABLED:false}
    confirmation-window: 15m
    operation-window: 1h
    result-retention: 24h
    apply-lease: 2m
    max-active-previews-per-owner: 20
    cleanup-batch-size: 500
    cleanup-interval: 1h
    max-documents: 10000
    max-embeddings: 100000
    max-versions: 100000
    max-derived-rows: 250000
    max-affected-chat-sessions: 1000
    max-chat-rows: 50000
```

| Property | Default | Description |
|----------|---------|-------------|
| `rag.collection-purge.enabled` | `false` | Publishes the caller-aware capability and preview/apply endpoints; disabled endpoints return `503` |
| `rag.collection-purge.allow-auth-disabled` | `false` | Explicit local-development exception when authentication is off; the direct Servlet peer must still be loopback and forwarded-address headers are ignored |
| `confirmation-window` | `15m` | Validity of the one-time plaintext token and preview, range 1m–1h |
| `operation-window` | `1h` | Total apply window; at least the confirmation window and at most 24h |
| `result-retention` | `24h` | Exact successful-result replay retention; at least the operation window and at most 7d |
| `apply-lease` | `2m` | APPLYING owner lease, range 15s–15m |
| `max-active-previews-per-owner` | `20` | Unterminated previews per owner, range 1–100 |
| `cleanup-batch-size` | `500` | Bounded expired preview/result cleanup batch, range 10–5000 |
| `cleanup-interval` | `1h` | Preview/result recovery and cleanup interval, range 1m–24h |
| `max-documents` | `10000` | Documents per synchronous purge, range 1–100000 |
| `max-embeddings` | `100000` | Vector rows per purge, range 1–1000000 |
| `max-versions` | `100000` | Document-version rows per purge, range 1–1000000 |
| `max-derived-rows` | `250000` | Other derived/control rows, range 1–2000000 |
| `max-affected-chat-sessions` | `1000` | Affected owner/session pairs, range 1–10000 |
| `max-chat-rows` | `50000` | Total history/memory/summary/turn-replay rows, range 1–500000 |

Startup validation fails fast for invalid combinations. When enabled, the
application also requires the durable Chat coordinator, transaction manager,
and reference-index dependencies; configuration cannot bypass integrity
checks. A preview above any limit returns a conflict instead of truncating or
partially applying a plan. See
[REST API](rest-api.md#guarded-collection-purge-and-retirement) for the
contract, authorization, and retention boundary. Run
`./scripts/verify-collection-purge.sh` as the focused gate.

## Retrieval Configuration

```yaml
rag:
  retrieval:
    vector-weight: 0.5
    fulltext-weight: 0.5
    default-limit: 10
    min-score: 0.3
```

| Property | Default | Description |
|----------|---------|-------------|
| `rag.retrieval.vector-weight` | `0.5` | Vector retrieval fusion weight |
| `rag.retrieval.fulltext-weight` | `0.5` | Full-text retrieval fusion weight |
| `rag.retrieval.fulltext-enabled` | `true` | Enable full-text retrieval (auto-degrades to pure vector if unavailable) |
| `rag.retrieval.fulltext-strategy` | `auto` | Full-text strategy (see table below) |
| `rag.retrieval.default-limit` | `10` | Default number of results to return |
| `rag.retrieval.min-score` | `0.3` | Minimum similarity for fuzzy full-text providers such as `pg_trgm`; `pg_jieba` and English FTS use `@@` for lexical matching and `ts_rank` for ordering |

> 💡 `vector-weight + fulltext-weight` is recommended to sum to `1.0`.
> The service validates each weight independently as a finite value in
> `0.0..1.0`; it does not normalize their sum. The recommendation keeps the
> scaled weighted-RRF score in an easy-to-read range.

**Full-text search strategy (`fulltext-strategy`):**

| Strategy | Description | Dependency |
|----------|-------------|------------|
| `auto` | Auto-detect: pg_jieba → pg_trgm → pure vector | — |
| `pg_jieba` | PostgreSQL Chinese tokenizer (recommended for Chinese) | `pg_jieba` extension |
| `pg_trgm` | Trigram fuzzy matching | `pg_trgm` extension |
| `none` | Disable full-text, pure vector only | — |

See [PostgreSQL Extensions Documentation](postgresql-extensions.md).

### Retrieval Diagnostics

```yaml
rag:
  retrieval-diagnostics:
    enabled: ${RAG_RETRIEVAL_DIAGNOSTICS_ENABLED:true}
    persist: ${RAG_RETRIEVAL_DIAGNOSTICS_PERSIST:true}
    retention-days: ${RAG_RETRIEVAL_DIAGNOSTICS_RETENTION_DAYS:7}
    store-query-text: ${RAG_RETRIEVAL_DIAGNOSTICS_STORE_QUERY_TEXT:false}
    max-detail-bytes: ${RAG_RETRIEVAL_DIAGNOSTICS_MAX_DETAIL_BYTES:32768}
```

The default stores outcome / empty-reason / filter summaries, not raw query
text. Persist failures are fail-open and do not break Search or Chat.

### Evaluation Suites And Citations

```yaml
rag:
  evaluation:
    managed-suites-enabled: ${RAG_EVALUATION_MANAGED_SUITES_ENABLED:false}
    citation-validation-enabled: ${RAG_EVALUATION_CITATION_VALIDATION_ENABLED:true}
    max-concurrent-runs: ${RAG_EVALUATION_MAX_CONCURRENT_RUNS:1}
    run-concurrency: ${RAG_EVALUATION_RUN_CONCURRENCY:4}
    max-cases-per-version: ${RAG_EVALUATION_MAX_CASES_PER_VERSION:200}
    max-variants-per-run: ${RAG_EVALUATION_MAX_VARIANTS_PER_RUN:4}
    semantic-batch-limit: ${RAG_EVALUATION_SEMANTIC_BATCH_LIMIT:50}
```

Citation validation only parses the agreed `[S1]` tokens. It is not a coverage
score. The managed-suite worker is disabled by default.
`max-concurrent-runs` bounds concurrently executing runs, while
`run-concurrency` bounds parallel retrieval cases inside one run and is capped
at 8.

## Query Rewrite Configuration

```yaml
rag:
  query-rewrite:
    enabled: true
    padding-count: 2
    synonym-dictionary:
      AI: [人工智能, Artificial Intelligence]
      机器学习: [ML, Machine Learning]
    domain-qualifiers: [皮肤科, 美容]
    llm-enabled: false
    llm-max-rewrites: 3
```

| Property | Default | Description |
|----------|---------|-------------|
| `rag.query-rewrite.enabled` | `true` | Enable query focusing and rewriting |
| `rag.query-rewrite.padding-count` | `2` | Number of expanded queries |
| `rag.query-rewrite.synonym-dictionary` | `{}` | Synonym dictionary |
| `rag.query-rewrite.domain-qualifiers` | `[]` | Domain qualifier terms |
| `rag.query-rewrite.llm-enabled` | `false` | Enable LLM-assisted rewriting |
| `rag.query-rewrite.llm-max-rewrites` | `3` | Max LLM rewrites per query |

These properties configure the legacy/component-level `QueryRewriteAdvisor`.
They do not control the production mode-aware Chat path. Production
`KNOWLEDGE` query transformation is configured under
`rag.chat.knowledge.query-transformer`; `AGENT` relies on the model to form
tool queries through Spring AI Tool Calling.

## Reranking Configuration

```yaml
rag:
  rerank:
    enabled: false
    provider: heuristic
    diversity-weight: 0.2
    top-n: 5
    candidate-limit: ${RAG_RERANK_CANDIDATE_LIMIT:20}
    preferred-max-chunks-per-document: ${RAG_RERANK_PREFERRED_MAX_CHUNKS_PER_DOCUMENT:2}
```

| Property | Default | Description |
|----------|---------|-------------|
| `rag.rerank.enabled` | `false` | Enable reranking |
| `rag.rerank.provider` | `heuristic` | Rerank provider; `none`, `noop`, and `off` disable candidate-pool expansion |
| `rag.rerank.diversity-weight` | `0.2` | Result diversity weight (prevents similar results stacking) |
| `rag.rerank.top-n` | `5` | Final result fallback when callers omit a positive `maxResults` |
| `rag.rerank.candidate-limit` | `20` | Internal pre-rerank candidate-pool limit when effective reranking is enabled; bounded to `1..100` |
| `rag.rerank.preferred-max-chunks-per-document` | `2` | First-pass preferred chunk cap per exact nonblank document ID; bounded to `0..100`, where `0` disables document diversification |

When the request sets `useRerank=true`, global reranking is enabled, and the provider is
not a no-op, retrieval uses `max(requestedMaxResults, candidate-limit)` for the
pre-rerank candidate pool. `maxResults` remains the final caller-visible bound for
Search, Chat, the Agent tool, JSON records, and Evaluation. Hybrid retrieval continues
to query each vector and full-text channel at `2x` the candidate-pool limit before
weighted RRF fusion. Disabling reranking, selecting a no-op provider, or using the
legacy GET Search path (which explicitly sets `useRerank=false`) keeps the original
retrieval limit.

`candidate-limit` is server-side configuration only. The recommended environment
variable is `RAG_RERANK_CANDIDATE_LIMIT`. Increasing it can improve rerank recall,
but increases database candidates, HTTP rerank request size, and latency. Compare
MRR/nDCG and latency with the retrieval goldenset before raising it.

When the candidate pool is larger than the final result limit, an effective
reranker ranks the bounded pool before final selection. The selector first prefers
at most `preferred-max-chunks-per-document` results for each exact, nonblank
`documentId`, then backfills skipped provider-ranked chunks when distinct documents
cannot fill the requested count. The setting is therefore a soft first-pass cap:
it improves evidence coverage without reducing provider output. Null or blank
document IDs are treated as independent results. Set
`RAG_RERANK_PREFERRED_MAX_CHUNKS_PER_DOCUMENT=0` to restore the previous provider
top-N selection.

## Document Chunking Configuration

```yaml
rag:
  chunk:
    default-chunk-size: 1000
    default-chunk-overlap: 100
    min-chunk-size: 100
```

| Property | Default | Description |
|----------|---------|-------------|
| `rag.chunk.default-chunk-size` | `1000` | Default chunk size (characters) |
| `rag.chunk.default-chunk-overlap` | `100` | Chunk overlap size (characters) |
| `rag.chunk.min-chunk-size` | `100` | Best-effort target for generated chunks; non-blank documents are not discarded |

## JSON Structured-Record Configuration

```yaml
rag:
  structured-records:
    max-jsonb-payload-bytes: ${RAG_STRUCTURED_RECORDS_MAX_JSONB_PAYLOAD_BYTES:1048576}
    max-retrieval-text-chars: ${RAG_STRUCTURED_RECORDS_MAX_RETRIEVAL_TEXT_CHARS:10000}
    max-batch-size: ${RAG_STRUCTURED_RECORDS_MAX_BATCH_SIZE:20}
    max-batch-payload-bytes: ${RAG_STRUCTURED_RECORDS_MAX_BATCH_PAYLOAD_BYTES:10485760}
    max-search-results: ${RAG_STRUCTURED_RECORDS_MAX_SEARCH_RESULTS:20}
    max-payload-filter-bytes: ${RAG_STRUCTURED_RECORDS_MAX_PAYLOAD_FILTER_BYTES:16384}
    max-payload-filter-depth: ${RAG_STRUCTURED_RECORDS_MAX_PAYLOAD_FILTER_DEPTH:8}
    agent-tool-enabled: ${RAG_JSON_AGENT_TOOL_ENABLED:false}
    agent-tool-max-results: 5
    agent-tool-max-payload-bytes: 32768
```

These limits protect JSONB request, batch, and response sizes. They do not
alter the embedding input: only caller-supplied `retrievalText` is chunked and
embedded. `jsonbPayload` is stored as JSONB and is not hashed; the API
intentionally has no payload hash setting. `payloadContains` uses PostgreSQL
`jsonb @>` exact subtree containment and defaults to a 16 KiB serialized limit
and maximum depth 8. The optional Spring AI `searchJsonRecords` tool is disabled
by default. When enabled, the server injects Collection/ACL scope; the model may
only supply the query, payload subtree, and result limit.

## Integration Operation Observability

```yaml
rag:
  integration-observability:
    enabled: ${RAG_INTEGRATION_OBSERVABILITY_ENABLED:true}
    retention: ${RAG_INTEGRATION_OBSERVABILITY_RETENTION:90d}
    max-query-range: ${RAG_INTEGRATION_OBSERVABILITY_MAX_QUERY_RANGE:31d}
    max-collection-breakdown-items: ${RAG_INTEGRATION_OBSERVABILITY_MAX_COLLECTION_BREAKDOWN_ITEMS:100}
    queue-capacity: ${RAG_INTEGRATION_OBSERVABILITY_QUEUE_CAPACITY:10000}
    flush-batch-size: ${RAG_INTEGRATION_OBSERVABILITY_FLUSH_BATCH_SIZE:500}
    flush-interval: ${RAG_INTEGRATION_OBSERVABILITY_FLUSH_INTERVAL:1s}
    shutdown-drain-timeout: ${RAG_INTEGRATION_OBSERVABILITY_SHUTDOWN_DRAIN_TIMEOUT:5s}
    cleanup-batch-size: ${RAG_INTEGRATION_OBSERVABILITY_CLEANUP_BATCH_SIZE:5000}
    cleanup-interval: ${RAG_INTEGRATION_OBSERVABILITY_CLEANUP_INTERVAL:1h}
```

| Property | Default | Validation / meaning |
|---|---:|---|
| `enabled` | `true` | Enables recording and the query API; disabled queries return `503` |
| `retention` | `90d` | Whole days, 7–730 days |
| `max-query-range` | `31d` | Whole days, 1–90 days, and no greater than retention |
| `max-collection-breakdown-items` | `100` | 1–1000 Collection contribution rows |
| `queue-capacity` | `10000` | 100–100000 request observations |
| `flush-batch-size` | `500` | 10–5000 and no greater than queue capacity |
| `flush-interval` | `1s` | 100 ms–60 s |
| `shutdown-drain-timeout` | `5s` | 0–30 s |
| `cleanup-batch-size` | `5000` | 100–50000 expired rows per bounded delete |
| `cleanup-interval` | `1h` | 1 minute–24 hours |

The recorder classifies a finite set of stable integration routes, captures
final HTTP status and wall duration, and asynchronously upserts UTC hourly
rollups. Queue/repository failure is fail-open for the business request and is
visible through fixed-reason counters. `GET /api/v1/rag/integration-observability`
returns best-effort aggregate data; it is not billing, quota, audit, or a
mutation receipt.

Micrometer uses only fixed low-cardinality tags:
`operation`, `status_class`, `principal_type`, `result`, and `reason`.
Principal IDs, Collection keys, external IDs, request paths, and payloads are
not tags. V54 stores stable principal/Collection references only in PostgreSQL
aggregate rows.

## Chat Execution Configuration

```yaml
rag:
  chat:
    default-mode: KNOWLEDGE
    knowledge:
      query-transformer: none
      query-transform-timeout-seconds: 30
      query-expander-variants: 2
      query-expander-include-original: true
      max-retrieval-queries: 3
      allow-empty-context: false
    agent:
      enabled: true
      max-tool-rounds: 3
      max-retrieval-calls: 3
      max-results-per-call: 10
      max-unique-sources: 20
      max-tool-result-characters: 24000
    history:
      lease-ttl-seconds: 30
      lease-renew-interval-seconds: 10
    idempotency:
      enabled: true
      retention-hours: 24
      response-snapshot-max-bytes: 524288
      execution-snapshot-max-bytes: 65536
      max-attempts: 3
      lease-grace-ms: 10000
      cleanup-batch-size: 500
      cleanup-interval-ms: 600000
      cleanup-initial-delay-ms: 60000
    static-knowledge:
      enabled: false
      locations: []
      file-extensions: [md, markdown, txt]
      max-files-per-root: 200
      max-file-bytes: 262144
      max-total-bytes: 10485760
      chunk-max-characters: 4000
      chunk-overlap-characters: 200
      retrieval-max-results: 5
      retrieval-max-result-characters: 24000
      fail-fast: true
      visibility: GLOBAL
    skills:
      enabled: false
      locations: []
      max-skills: 50
      max-skill-body-bytes: 131072
      max-reference-bytes: 262144
      max-catalog-characters: 24000
      max-loads-per-request: 4
      max-reference-reads-per-request: 8
      fail-fast: true
    http-tools:
      enabled: false
      max-total-response-bytes: 262144
      endpoints: []
```

| Property | Default | Description |
|---|---|---|
| `rag.chat.default-mode` | `KNOWLEDGE` | Mode used when `ChatRequest.mode` is omitted |
| `rag.chat.knowledge.query-transformer` | `none` | `none` or `spring-ai`; `postgresql`, `local`, and `prod` profiles use `spring-ai` |
| `rag.chat.knowledge.query-transform-timeout-seconds` | `30` | Timeout for the Spring AI history compression call; override with `RAG_CHAT_QUERY_TRANSFORM_TIMEOUT_SECONDS` |
| `rag.chat.knowledge.query-expander-variants` | `2` | Number of LLM-generated search variants when the `spring-ai` strategy is enabled; override with `RAG_CHAT_QUERY_EXPANDER_VARIANTS` |
| `rag.chat.knowledge.query-expander-include-original` | `true` | Keep the original request as an additional retrieval query; override with `RAG_CHAT_QUERY_EXPANDER_INCLUDE_ORIGINAL` |
| `rag.chat.knowledge.max-retrieval-queries` | `3` | Maximum planned retrieval queries per `KNOWLEDGE` attempt, bounded to `1..5`; override with `RAG_CHAT_KNOWLEDGE_MAX_RETRIEVAL_QUERIES` |
| `rag.chat.knowledge.allow-empty-context` | `false` | When false, an empty retrieval result produces an explicit no-evidence instruction |
| `rag.chat.agent.enabled` | `true` | Enable `AGENT` mode |
| `rag.chat.agent.max-tool-rounds` | `3` | Maximum Spring AI tool-call rounds per attempt |
| `rag.chat.agent.max-retrieval-calls` | `3` | Maximum uncached knowledge retrieval calls per attempt |
| `rag.chat.agent.max-results-per-call` | `10` | Server cap for one tool retrieval |
| `rag.chat.agent.max-unique-sources` | `20` | Maximum unique source chunks retained across tool calls |
| `rag.chat.agent.max-tool-result-characters` | `24000` | Maximum serialized tool-result characters; results/snippets are safely truncated as valid JSON |
| `rag.chat.history.lease-ttl-seconds` | `30` | Database lease TTL for one principal/session request |
| `rag.chat.history.lease-renew-interval-seconds` | `10` | Lease renewal interval |
| `rag.chat.idempotency.enabled` | `true` | Durable keyed Chat operation and replay switch; keyed requests return `IDEMPOTENCY_DISABLED` when off |
| `rag.chat.idempotency.retention-hours` | `24` | Retention for terminal operations and stale orphan cleanup; range 1–168 |
| `rag.chat.idempotency.response-snapshot-max-bytes` | `524288` | UTF-8 response snapshot limit; range 65536–2097152 |
| `rag.chat.idempotency.execution-snapshot-max-bytes` | `65536` | UTF-8 immutable execution snapshot limit; range 16384–262144 |
| `rag.chat.idempotency.max-attempts` | `3` | Maximum total durable execution attempts, including stale reclaim; range 1–8 |
| `rag.chat.idempotency.lease-grace-ms` | `10000` | Operation lease grace added to the Chat deadline; range 1000–60000 |
| `rag.chat.idempotency.cleanup-batch-size` | `500` | Maximum terminal/stale orphan rows deleted per maintenance batch; range 1–5000 |
| `rag.chat.idempotency.cleanup-interval-ms` | `600000` | Cleanup fixed-delay interval; range 10000–86400000 |
| `rag.chat.idempotency.cleanup-initial-delay-ms` | `60000` | Initial cleanup delay after startup; range 1–86400000 |
| `rag.chat.static-knowledge.enabled` | `false` | Enable the startup-built, non-embedding static-knowledge snapshot |
| `rag.chat.static-knowledge.locations` | `[]` | `classpath:`, `classpath*:`, `file:`, or bounded `jar:file:...!/prefix/` resource roots |
| `rag.chat.static-knowledge.file-extensions` | `[md, markdown, txt]` | Allowed UTF-8 file extensions |
| `rag.chat.static-knowledge.max-files-per-root` | `200` | File limit for each configured root |
| `rag.chat.static-knowledge.max-file-bytes` | `262144` | Per-file byte limit |
| `rag.chat.static-knowledge.max-total-bytes` | `10485760` | Cumulative byte limit across static-knowledge roots |
| `rag.chat.static-knowledge.chunk-max-characters` | `4000` | Target chunk character limit when splitting by Markdown heading and paragraph |
| `rag.chat.static-knowledge.chunk-overlap-characters` | `200` | Character overlap between adjacent chunks; must be smaller than the chunk limit |
| `rag.chat.static-knowledge.retrieval-max-results` | `5` | Result cap for one static lexical retrieval |
| `rag.chat.static-knowledge.retrieval-max-result-characters` | `24000` | Total returned text-character cap for one static retrieval |
| `rag.chat.static-knowledge.fail-fast` | `true` | Fail startup on load error; false degrades the entire snapshot and publishes no partial result |
| `rag.chat.static-knowledge.visibility` | `GLOBAL` | The only supported visibility; content is shared across authorized Chat principals |
| `rag.chat.skills.enabled` | `false` | Enable the AGENT runtime-Skill catalog and loading tools |
| `rag.chat.skills.locations` | `[]` | Classpath/filesystem/JAR roots containing `<skill-name>/SKILL.md` |
| `rag.chat.skills.max-skills` | `50` | Skill count limit |
| `rag.chat.skills.max-skill-body-bytes` | `131072` | Byte limit for one `SKILL.md` body |
| `rag.chat.skills.max-reference-bytes` | `262144` | Limit for one `references/` file and one reference read |
| `rag.chat.skills.max-catalog-characters` | `24000` | Character limit for the Level 1 catalog and one `loadSkill` result |
| `rag.chat.skills.max-loads-per-request` | `4` | Skill-load limit per request |
| `rag.chat.skills.max-reference-reads-per-request` | `8` | Skill-reference read limit per request |
| `rag.chat.skills.fail-fast` | `true` | Fail startup on Skill load error; false degrades the catalog and registers no Skill tools |
| `rag.chat.http-tools.enabled` | `false` | Enable server-configured, Skill-gated, read-only HTTPS tools |
| `rag.chat.http-tools.max-total-response-bytes` | `262144` | Cumulative HTTP response-byte budget for one logical request |
| `rag.chat.http-tools.endpoints` | `[]` | Fixed endpoint list; the model cannot provide a URL, method, header, or credential |

Mode behavior:

- `KNOWLEDGE` always executes Spring AI Modular RAG through the project hybrid
  retriever and optional reranker.
- In the normal `postgresql`, `local`, and `prod` profiles, the project uses
  Spring AI's built-in `CompressionQueryTransformer` for follow-up history and
  built-in `MultiQueryExpander` for the retrieval query. The project-owned
  `BoundedMultiQueryExpander` bounds fan-out before the advisor starts retrieval,
  trims blank variants, removes exact duplicate text, and preserves each Spring AI
  `Query` history/context. The expander keeps the original request and generates
  two additional variants by default. Its project-supplied prompt requires exact
  lexical variants to preserve product names, quoted phrases, identifiers, and
  other unusual terms. The project-owned `ProjectDocumentJoiner` merges results
  by stable chunk identity before reranking. This prevents a semantic rewrite from discarding
  an exact term such as `破皮沙发` and prevents an over-configured variant count
  from creating retrieval work beyond the server budget.
- `query-expander-variants` is the requested number of LLM-generated variants;
  `max-retrieval-queries` is the total cap for the original query plus effective
  variants. With `query-expander-include-original=true` and
  `max-retrieval-queries=1`, no multi-query expansion model call is made and the
  original/transformed query is retrieved once. `KNOWLEDGE` uses this independent
  budget; `AGENT` continues to use `rag.chat.agent.max-retrieval-calls`. The
  response `metadata.retrieval.queryExpansion` and persisted retrieval-trace
  attempt metadata contain only bounded integer/boolean summaries, never expanded
  query text.
- `AGENT` uses Spring AI Tool Calling. A model must declare
  `capabilities.toolCalling=true`; Collection/document/credential scope remains
  server-owned.
- `PLAIN` does not retrieve and rejects retrieval-specific request overrides.
- Client-cancelled partial turns are not persisted.

### Non-Embedding Static Knowledge

Static knowledge is read at startup into an immutable, bounded lexical snapshot.
It neither calls an embedding model nor writes `rag_documents` or
`rag_embeddings`. `KNOWLEDGE` combines it with project-document retrieval,
`AGENT` registers `searchStaticKnowledge` while the snapshot is healthy, and
`PLAIN` does not read it. Static sources use the `STATIC_KNOWLEDGE` type and
bypass the external reranker. Resource changes require an application restart.

```yaml
rag:
  chat:
    static-knowledge:
      enabled: true
      locations:
        - classpath*:knowledge/company-terms/
        - file:/opt/spring-ai-rag/knowledge/
```

`classpath:` reads one matching root, while `classpath*:` searches all matching
classpath/JAR roots. An explicit JAR directory may use
`jar:file:/opt/lib/policies.jar!/knowledge/`. Filesystem paths must be `file:`
URIs. The current `GLOBAL` visibility is not suitable for tenant-private
content; use the ACL-protected project document store for that data.

### Runtime Skills And Allowlisted HTTP Tools

Runtime Skills are available only in `AGENT`. Startup adds only a bounded
catalog of names, descriptions, and capabilities to the system prompt. The
model must call `loadSkill` before it receives the Skill body, then may call
`readSkillReference` or an HTTP tool gated by that Skill. Load state and read
budgets are request-local.

```text
skills/
  weather/
    SKILL.md
    references/
      response-schema.md
```

`SKILL.md` uses YAML frontmatter:

```markdown
---
name: weather
description: Query the configured weather service
version: "1.0"
capabilities:
  - weather.read
---

Load this Skill, then call the weather endpoint with a city name.
```

The server fixes each HTTP endpoint, and the Skill must declare its capability:

```yaml
rag:
  chat:
    skills:
      enabled: true
      locations:
        - classpath*:skills/
    http-tools:
      enabled: true
      max-total-response-bytes: 262144
      endpoints:
        - tool-name: getWeather
          skill-name: weather
          capability: weather.read
          base-url: https://weather.example.com
          path: /v1/current
          method: GET
          query-parameters:
            - name: city
              required: true
              max-length: 128
          response-content-types: [application/json]
          max-calls-per-request: 2
          timeout-ms: 5000
          max-response-bytes: 65536
          max-result-characters: 24000
          max-json-depth: 12
          max-json-nodes: 1000
          max-json-array-items: 100
          credential-env: WEATHER_API_TOKEN
          credential-header: Authorization
```

Endpoints allow only HTTPS `GET`/`HEAD`. Redirects,
private/loopback/link-local/metadata addresses, arbitrary headers, and
model-supplied URLs are rejected; fixed `path` values cannot contain percent
encoding. Every DNS result is checked for public reachability and the validated
address set is pinned to the actual connection while TLS still verifies the
configured hostname. Credentials come only from the process environment
variable named by `credential-env`. Each endpoint also has per-request call,
timeout, response-byte, JSON depth/node/array, and tool-result character limits.
Cumulative response-byte capacity is reserved before download so concurrent or
repeated calls cannot read first and exceed the logical-request budget later.

## Conversation Memory Configuration

```yaml
spring:
  ai:
    chat:
      memory:
        repository:
          jdbc:
            initialize-schema: always
            platform: postgresql

rag:
  memory:
    max-messages: 20
    message-ttl-days: 30        # 0=no expiry, non-zero=history older than N days is cleaned
    cleanup-cron: "0 0 3 * * *" # Daily cleanup at 3 AM (Asia/Shanghai timezone)
```

| Property | Default | Description |
|----------|---------|-------------|
| `rag.memory.max-messages` | `20` | Max messages retained per session |
| `rag.memory.message-ttl-days` | `30` | Chat history retention days (0=never expire) |
| `rag.memory.cleanup-cron` | `0 0 3 * * *` | History cleanup cron expression (3 AM daily) |

System maintains dual tables:
- `spring_ai_chat_memory`: Spring AI JDBC storage; the project commits only
  recoverable user/plain-assistant messages for recent LLM context
- `rag_chat_history`: Principal-owned business history with complete
  `user_message`, `ai_response`, source snapshots, mode/model metadata, bounded
  `toolTranscript`, and TTL cleanup

Completed turns update both stores atomically under a
`rag_chat_session_lease`. Keyed turns additionally write V47's
`rag_chat_turn_operations` row in the same transaction, persist an immutable
transport-neutral response snapshot, and bind the business history row to the
opaque `turn_id`. History, export, clear, and Memory baselines are scoped to
the authenticated principal. `GET /api/v1/rag/chat/turns/{turnId}` exposes
status without exposing the idempotency key or its hash.

## Async Thread Pool Configuration

```yaml
rag:
  async:
    core-pool-size: 4
    max-pool-size: 16
    queue-capacity: 100
```

| Property | Default | Description |
|----------|---------|-------------|
| `rag.async.core-pool-size` | `4` | Core thread count |
| `rag.async.max-pool-size` | `16` | Max thread count |
| `rag.async.queue-capacity` | `100` | Queue capacity |

## LLM API Timeout Configuration

```yaml
rag:
  timeout:
    connect-timeout-ms: 10000   # Connection establishment timeout
    read-timeout-ms: 60000     # Read timeout (time waiting for first byte)
    chat-ask-ms: 120000        # Non-streaming chat endpoint timeout
    chat-stream-ms: 180000     # Streaming chat endpoint timeout
    search-ms: 30000           # Search endpoint timeout
    embed-ms: 60000            # Embedding endpoint timeout
    model-compare-ms: 90000    # Per-model comparison timeout
```

| Property | Default | Description |
|----------|---------|-------------|
| `rag.timeout.connect-timeout-ms` | `10000` | HTTP connection establishment timeout (ms) |
| `rag.timeout.read-timeout-ms` | `60000` | Read timeout for LLM API responses (ms) |
| `rag.timeout.chat-ask-ms` | `120000` | Non-streaming chat call timeout (ms) |
| `rag.timeout.chat-stream-ms` | `180000` | Streaming chat call timeout (ms, longer for token generation) |
| `rag.timeout.search-ms` | `30000` | Search endpoint timeout (ms) |
| `rag.timeout.embed-ms` | `60000` | Embedding call timeout (ms) |
| `rag.timeout.model-compare-ms` | `90000` | Per-model comparison timeout (ms) |

Timeouts are applied at the `RestClient` level to all external LLM API calls (OpenAI, Anthropic, MiniMax). Increase values for complex queries or slow network conditions.

## Security Authentication Configuration

```yaml
rag:
  security:
    root-api-key: ${RAG_ROOT_API_KEY:}
    api-key: ${RAG_API_KEY:}
    enabled: false
  api-key-provisioning:
    enabled: ${RAG_API_KEY_PROVISIONING_ENABLED:true}
    retention: ${RAG_API_KEY_PROVISIONING_RETENTION:400d}
    cleanup-batch-size: ${RAG_API_KEY_PROVISIONING_CLEANUP_BATCH_SIZE:500}
    concurrent-retry-attempts: ${RAG_API_KEY_PROVISIONING_CONCURRENT_RETRY_ATTEMPTS:3}
  api-key-rotation:
    default-overlap: ${RAG_API_KEY_ROTATION_DEFAULT_OVERLAP:15m}
    max-overlap: ${RAG_API_KEY_ROTATION_MAX_OVERLAP:1h}
    operation-retention: ${RAG_API_KEY_ROTATION_OPERATION_RETENTION:400d}
    cleanup-interval-ms: ${RAG_API_KEY_ROTATION_CLEANUP_INTERVAL_MS:60000}
    cleanup-batch-size: ${RAG_API_KEY_ROTATION_CLEANUP_BATCH_SIZE:500}
  collection-provisioning:
    enabled: ${RAG_COLLECTION_PROVISIONING_ENABLED:true}
    retention: ${RAG_COLLECTION_PROVISIONING_RETENTION:400d}
    cleanup-batch-size: ${RAG_COLLECTION_PROVISIONING_CLEANUP_BATCH_SIZE:500}
    cleanup-interval-ms: ${RAG_COLLECTION_PROVISIONING_CLEANUP_INTERVAL_MS:3600000}
    concurrent-retry-attempts: ${RAG_COLLECTION_PROVISIONING_CONCURRENT_RETRY_ATTEMPTS:3}
```

| Property | Default | Description |
|----------|---------|-------------|
| `rag.security.root-api-key` | `""` | Standalone-service root credential; environment variable `RAG_ROOT_API_KEY` |
| `rag.security.enabled` | `false` | Enable API Key authentication |
| `rag.security.api-key` | `""` | Legacy static unrestricted key; ignored for authentication in root mode |

| Property | Default | Description |
|----------|---------|-------------|
| `rag.api-key-provisioning.enabled` | `true` | Enables keyed API-principal provisioning; when disabled, requests carrying `Idempotency-Key` fail closed with `503` |
| `rag.api-key-provisioning.retention` | `400d` | Successful provisioning ledger retention and guaranteed replay window; accepted range 7–3650 days |
| `rag.api-key-provisioning.cleanup-batch-size` | `500` | Maximum completed ledger rows deleted per scheduled cleanup, clamped to 10–5000 |
| `rag.api-key-provisioning.concurrent-retry-attempts` | `3` | Bounded attempts used to observe the winner of a same-owner/key unique-constraint race, clamped to 1–8 |

| Property | Default | Description |
|----------|---------|-------------|
| `rag.api-key-rotation.default-overlap` | `15m` | Default staged-rotation overlap; must be a positive whole-second duration no greater than `max-overlap` |
| `rag.api-key-rotation.max-overlap` | `1h` | Maximum caller-requested overlap; whole seconds from 1 second through 24 hours |
| `rag.api-key-rotation.operation-retention` | `400d` | Terminal operation retention and idempotent replay/status window; whole days from 7 through 3650 days |
| `rag.api-key-rotation.cleanup-interval-ms` | `60000` | Fixed delay for expiring pending operations and deleting old terminal rows; 1,000–86,400,000 ms |
| `rag.api-key-rotation.cleanup-batch-size` | `500` | Maximum expired/terminal operations processed per cleanup pass; 10–5000 |

| Property | Default | Description |
|----------|---------|-------------|
| `rag.collection-provisioning.enabled` | `true` | Enables caller-scoped keyed Collection creation; when disabled, `POST /collections` requests carrying `Idempotency-Key` fail closed with `503` |
| `rag.collection-provisioning.retention` | `400d` | Successful Collection-create ledger retention and guaranteed replay window; accepted range 7–3650 days |
| `rag.collection-provisioning.cleanup-batch-size` | `500` | Maximum completed Collection-operation rows deleted per cleanup, clamped to 10–5000 |
| `rag.collection-provisioning.cleanup-interval-ms` | `3600000` | Fixed delay between best-effort cleanup runs, clamped to 10,000–86,400,000 ms |
| `rag.collection-provisioning.concurrent-retry-attempts` | `3` | Bounded attempts used to observe the winner of a same-owner/key create race, clamped to 1–8 |

Collection provisioning stores only the server-derived owner, the
`Idempotency-Key` hash, the canonical request fingerprint, and the resulting
Collection ID. Disabling the feature does not disable ordinary unkeyed
Collection creation; it only rejects keyed requests instead of silently
discarding their retry guarantee.

Setting a valid `RAG_ROOT_API_KEY` enables standalone-service MVP security mode:

- The value must contain at least 32 printable non-whitespace ASCII characters;
  documented placeholder values fail startup.
- All `/api/**` requests automatically require the environment root or a valid
  database business key, regardless of `rag.security.enabled`.
- Only `Authorization: Bearer` and `X-API-Key` headers are accepted; query
  credentials are rejected.
- Only the environment root can create, list, rotate, or revoke business keys.
- The root is not stored in the database or logs. The WebUI keeps it only in
  page memory and requires it again after refresh.
- Empty-table ADMIN bootstrap and raw-secret logging are disabled.
- Root-created business keys may use read-only `RAG_READ` or full
  `RAG_READ + RAG_WRITE` data-plane capabilities and cannot manage keys.
  Omitting capabilities remains backward-compatible full read/write. A future
  expiry is required without a fixed maximum lifetime.

Without `RAG_ROOT_API_KEY`, legacy behavior remains: `rag.security.enabled`
controls authentication, while `rag.security.api-key` and database
ADMIN/NORMAL semantics continue to apply, including query-credential
compatibility.

Database business keys define their external scope with
`allowedCollectionKeys` through `POST /api/v1/rag/api-keys`; deprecated
`allowedCollectionIds` remains compatible. V48 separates the stable
`rag_api_principal` policy from versioned `rag_api_key` credentials. Rotation
therefore preserves the `db:{principalId}` owner, role, Collection ACL, expiry,
policy version, and quota. V49 adds canonical principal `capabilities`, allowing
only `RAG_READ` or `RAG_READ,RAG_WRITE`. Create omission defaults to full
read/write, policy-update omission preserves the current value, and rotation
inherits it. NORMAL-principal reads and explicit read-only POST routes require
`RAG_READ`; other mutations require `RAG_WRITE`, with rejection before shared
quota accounting. ADMIN, environment-root, legacy-static, and auth-disabled
compatibility paths remain unrestricted. V50 adds the successful provisioning
ledger used by optional `Idempotency-Key`; it stores only owner/key/request
hashes and result metadata, never raw credentials. Authentication queries the
authoritative credential and principal on every request; only the approximate
`last_used_at` write is suppressed for five minutes. The legacy `api_key`
column is retained for migration compatibility but constrained to `NULL`.
V55 adds bounded staged rotation. Prepare requires `Idempotency-Key`, creates
one new current credential, and marks the previous credential retiring until
the effective deadline. Both credentials remain tied to the same principal,
ACL, capabilities, owner, usage attribution, and shared quota. Authentication
checks the deadline directly; complete, cancel, expiry, or family revocation
converges back to at most one active credential. The operation ledger stores
hashes and metadata only and never stores or replays a raw secret. See
[rest-api.md](rest-api.md).

## API Rate Limiting Configuration

```yaml
rag:
  rate-limit:
    enabled: true
    backend: local
    requests-per-minute: 60
    strategy: ip
    key-limits:
      vip-key: 200
      basic-key: 60
```

| Property | Default | Description |
|----------|---------|-------------|
| `rag.rate-limit.enabled` | `false` | Enable API rate limiting |
| `rag.rate-limit.backend` | `local` | `local` process counter or shared `postgresql` fixed UTC-minute buckets |
| `rag.rate-limit.requests-per-minute` | `60` | Default max requests per minute |
| `rag.rate-limit.strategy` | `ip` | Local: `ip`, `api-key`, or `user`; PostgreSQL requires `principal` |
| `rag.rate-limit.key-limits` | `{}` | Local-only per-identifier limits; must be empty for PostgreSQL |
| `rag.rate-limit.bucket-retention-minutes` | `1440` | PostgreSQL bucket retention horizon |
| `rag.rate-limit.cleanup-interval-seconds` | `300` | Best-effort cleanup interval |
| `rag.rate-limit.cleanup-batch-size` | `10000` | Maximum rows removed by one cleanup pass |

**Rate limit strategy selection:**
- `ip`: Count per client IP independently, suitable for unauthenticated scenarios
- `api-key`: Rate limit by API Key (falls back to IP if no key), suitable for multi-tenant; unconfigured keys use default `requests-per-minute`

For multi-instance managed callers, use:

```yaml
rag:
  rate-limit:
    enabled: true
    backend: postgresql
    strategy: principal
    requests-per-minute: 60
```

The PostgreSQL backend uses the authenticated stable principal only. The
optional principal policy `requestsPerMinute` overrides the global default.
Credential rotation does not reset usage. Store failures fail closed with
`503`; there is no automatic local fallback.

Returns `429 Too Many Requests` when exceeded, with `Retry-After`, `X-RateLimit-Limit`, `X-RateLimit-Remaining` response headers.

## CORS Configuration

```yaml
rag:
  cors:
    enabled: true
    allowed-origins:
      - "https://example.com"
      - "http://localhost:3000"
    allowed-methods: "GET,POST,PUT,DELETE,OPTIONS"
    allowed-headers: "*"
    max-age: 3600
```

| Property | Default | Description |
|----------|---------|-------------|
| `rag.cors.enabled` | `false` | Enable CORS configuration |
| `rag.cors.allowed-origins` | `["*"]` | Allowed origins (production should specify concrete domains) |
| `rag.cors.allowed-methods` | `GET,POST,PUT,DELETE,OPTIONS` | Allowed HTTP methods |
| `rag.cors.allowed-headers` | `*` | Allowed request headers |
| `rag.cors.max-age` | `3600` | Preflight request cache time (seconds) |

`./scripts/dev.sh` enables CORS for the exact Vite origin calculated from
`FRONTEND_PORT`. Production deployments should continue to configure an
explicit allow-list.

## Cache Configuration

```yaml
rag:
  cache:
    maximum-size: 2000
    expire-after-write-minutes: 30
    embedding-maximum-size: 10000
    embedding-expire-after-write-hours: 2
```

| Property | Default | Description |
|----------|---------|-------------|
| `rag.cache.maximum-size` | `2000` | Retrieval result L1 cache max entries |
| `rag.cache.expire-after-write-minutes` | `30` | Retrieval result cache expiry after write (minutes) |
| `rag.cache.embedding-maximum-size` | `10000` | Embedding cache max entries |
| `rag.cache.embedding-expire-after-write-hours` | `2` | Embedding cache expiry after write (hours) |

Cache uses Caffeine for L1 in-memory cache with LRU eviction. Embedding cache avoids re-embedding unchanged documents based on content hash.

## Distributed Tracing Configuration

```yaml
rag:
  tracing:
    enabled: true
    sampling-rate: 1.0
    w3c-format: false
    span-id-enabled: false
```

| Property | Default | Description |
|----------|---------|-------------|
| `rag.tracing.enabled` | `true` | Enable request tracing |
| `rag.tracing.sampling-rate` | `1.0` | Sampling rate (0.0~1.0, 1.0=full tracing) |
| `rag.tracing.w3c-format` | `false` | Use W3C traceparent format output (32-char traceId) |
| `rag.tracing.span-id-enabled` | `false` | Generate spanId for nested tracing |

Trace info is passed via `X-Trace-Id` response header, MDC injects traceId into logs. Supports external `X-Trace-Id` header for cross-service trace propagation.

## API Versioning

The system supports API version coexistence via `@ApiVersion("v1")` annotation. All current endpoints are annotated `v1`, with base path `/api/v1/rag`.

For new versions, annotate new Controllers with `@ApiVersion("v2")` to coexist `/api/v1/rag` and `/api/v2/rag`.

## Internationalization

Error messages are internationalized via Spring `MessageSource`, auto-selecting language by `Accept-Language` request header:

| Language File | Language |
|---------------|----------|
| `messages.properties` | Default (Chinese) |
| `messages_en.properties` | English |
| `messages_zh_CN.properties` | Chinese (Simplified) |

## Database Configuration

```yaml
spring:
  datasource:
    url: jdbc:postgresql://${POSTGRES_HOST:localhost}:${POSTGRES_PORT:5432}/${POSTGRES_DATABASE:spring_ai_rag_dev}
    driver-class-name: org.postgresql.Driver
    username: ${POSTGRES_USER:postgres}
    password: ${POSTGRES_PASSWORD:postgres}
    hikari:
      maximum-pool-size: ${DB_POOL_SIZE:20}
      minimum-idle: ${DB_POOL_MIN_IDLE:5}
      idle-timeout: 300000
      max-lifetime: 1800000
      connection-timeout: 10000
      leak-detection-threshold: 60000
      pool-name: rag-hikari
  flyway:
    enabled: true
    locations: classpath:db/migration
    baseline-on-migrate: true
```

| HikariCP Property | Default | Description |
|-------------------|---------|-------------|
| `maximum-pool-size` | `20` | Max connections |
| `minimum-idle` | `5` | Min idle connections |
| `idle-timeout` | `300000` | Idle connection eviction time (ms) |
| `max-lifetime` | `1800000` | Connection max lifetime (ms) |
| `connection-timeout` | `10000` | Connection acquisition timeout (ms) |
| `leak-detection-threshold` | `60000` | Connection leak detection time (ms) |

## PostgreSQL Vector Storage

The application uses the application-owned `rag_embeddings` table, not Spring AI's
standalone vector-store table. The active 1024-dimensional Profile receives a
Profile-specific HNSW index on `embedding_1024`; index creation is managed by the
Embedding Profile registry. The `postgresql` profile supplies the PostgreSQL runtime
configuration. Flyway V25/V26 creates the Profile/state schema and removes the
unused `rag_vector_store` table when it is absent or empty. V27/V28 adds the
required, globally unique, immutable `rag_collection.collection_key`;
V29 adds JSONB structured-record columns and payload-aware version snapshots.
V30 adds external-document source revisions, tombstones, version snapshots, and
a Collection-scoped external identity constraint. V31 normalizes stored external
IDs using the same ASCII trim semantics as the API and rebuilds the partial
unique index. V32–V39 add Chat leases, durable jobs, filter/diagnostic/quality
operations, and non-pessimistic coordination. V40/V41 add document business
revisions, complete snapshots, source namespaces, generation fencing, and the
lifecycle/idempotency contract. V42 adds authoritative external snapshot
reconciliation runs and source/reconciliation deletion markers. V43 adds
profile-neutral local keyword chunks and independent local-index lifecycle
state. V44/V45 add external-document relocation and Collection derivation
repair control planes; V46/V47 add durable Chat summaries and turn operations;
V48–V50 add stable managed principals, operation capabilities, shared quota,
and principal-provisioning idempotency; V51 adds Sync Run item-receipt cursor
indexes; V52 adds the caller-scoped Collection-create idempotency ledger; V53
adds the principal-scoped model-invocation usage ledger; V54 adds bounded UTC
hourly integration-operation and authorized Collection-contribution rollups.

## Profile Overview

| Profile | Purpose |
|---------|---------|
| `local` | Local development, load keys from `.env` |
| `postgresql` | Enable pgvector auto-configuration |

## Server Configuration

```yaml
server:
  port: 8081
```

## Monitoring Configuration

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,metrics,info
  endpoint:
    health:
      show-details: always
```

Actuator endpoints:
- `GET /actuator/health` — Health check (DB, embedding profile, embedding model)
- `GET /actuator/metrics` — Metrics (retrieval latency, token usage, etc.)
- `GET /actuator/info` — Application info

## Logging Configuration

```yaml
logging:
  level:
    com.springairag: INFO
    org.springframework.ai: INFO
```

Production recommends `INFO` for `com.springairag`; switch to `DEBUG` for full RAG Pipeline logs (query rewrite → hybrid search → rerank → prompt assembly).

## Configuration Inheritance

Starter module users only need to configure:
1. Datasource (`spring.datasource.*`)
2. LLM provider (`spring.ai.openai.*` or `spring.ai.anthropic.*` + `app.llm.provider`)
3. Embedding model (`rag.embedding.*`)

All other configuration items have sensible defaults, override as needed.
