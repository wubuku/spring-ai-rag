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

### Persistent Embedding Jobs

The job worker provides durable, retryable embedding/reindexing. It is enabled
by default from V41 because both `SYNC` and `ASYNC` document-content mutations
persist a job in the same transaction; `SYNC` only performs a bounded wait on
that same job. Operational callers can create, inspect, cancel, and retry jobs through
`/api/v1/rag/embedding-jobs`.

```yaml
rag:
  embedding-jobs:
    enabled: ${RAG_EMBEDDING_JOBS_ENABLED:true}
    sync-wait-seconds: ${RAG_EMBEDDING_JOBS_SYNC_WAIT_SECONDS:30}
    poll-interval-ms: ${RAG_EMBEDDING_JOBS_POLL_INTERVAL_MS:1000}
    claim-batch-size: ${RAG_EMBEDDING_JOBS_CLAIM_BATCH_SIZE:4}
    lease-seconds: ${RAG_EMBEDDING_JOBS_LEASE_SECONDS:120}
    default-max-attempts: ${RAG_EMBEDDING_JOBS_DEFAULT_MAX_ATTEMPTS:3}
    max-attempts: ${RAG_EMBEDDING_JOBS_MAX_ATTEMPTS:5}
    max-documents-per-batch: 1000
    retry-backoff-seconds: ${RAG_EMBEDDING_JOBS_RETRY_BACKOFF_SECONDS:10}
    worker-concurrency: ${RAG_EMBEDDING_JOBS_WORKER_CONCURRENCY:4}
```

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
| `rag.document-lifecycle.sync-runs-enabled` | `false` | Enables the authoritative external snapshot Sync Run API; keep disabled until the disposable PostgreSQL/E2E acceptance passes |
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

> 💡 `vector-weight + fulltext-weight` is recommended to sum to `1.0`; the system auto-normalizes.

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
    diversity-weight: 0.2
```

| Property | Default | Description |
|----------|---------|-------------|
| `rag.rerank.enabled` | `false` | Enable reranking |
| `rag.rerank.diversity-weight` | `0.2` | Result diversity weight (prevents similar results stacking) |

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
    max-jsonb-payload-bytes: 1048576
    max-retrieval-text-chars: 10000
    max-batch-size: 20
    max-batch-payload-bytes: 10485760
    max-search-results: 20
    max-payload-filter-bytes: 16384
    max-payload-filter-depth: 8
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
```

| Property | Default | Description |
|---|---|---|
| `rag.chat.default-mode` | `KNOWLEDGE` | Mode used when `ChatRequest.mode` is omitted |
| `rag.chat.knowledge.query-transformer` | `none` | `none` or `spring-ai`; `postgresql`, `local`, and `prod` profiles use `spring-ai` |
| `rag.chat.knowledge.query-transform-timeout-seconds` | `30` | Timeout for the Spring AI history compression call; override with `RAG_CHAT_QUERY_TRANSFORM_TIMEOUT_SECONDS` |
| `rag.chat.knowledge.query-expander-variants` | `2` | Number of LLM-generated search variants when the `spring-ai` strategy is enabled; override with `RAG_CHAT_QUERY_EXPANDER_VARIANTS` |
| `rag.chat.knowledge.query-expander-include-original` | `true` | Keep the original request as an additional retrieval query; override with `RAG_CHAT_QUERY_EXPANDER_INCLUDE_ORIGINAL` |
| `rag.chat.knowledge.allow-empty-context` | `false` | When false, an empty retrieval result produces an explicit no-evidence instruction |
| `rag.chat.agent.enabled` | `true` | Enable `AGENT` mode |
| `rag.chat.agent.max-tool-rounds` | `3` | Maximum Spring AI tool-call rounds per attempt |
| `rag.chat.agent.max-retrieval-calls` | `3` | Maximum uncached knowledge retrieval calls per attempt |
| `rag.chat.agent.max-results-per-call` | `10` | Server cap for one tool retrieval |
| `rag.chat.agent.max-unique-sources` | `20` | Maximum unique source chunks retained across tool calls |
| `rag.chat.agent.max-tool-result-characters` | `24000` | Maximum serialized tool-result characters; results/snippets are safely truncated as valid JSON |
| `rag.chat.history.lease-ttl-seconds` | `30` | Database lease TTL for one principal/session request |
| `rag.chat.history.lease-renew-interval-seconds` | `10` | Lease renewal interval |

Mode behavior:

- `KNOWLEDGE` always executes Spring AI Modular RAG through the project hybrid
  retriever and optional reranker.
- In the normal `postgresql`, `local`, and `prod` profiles, the project uses
  Spring AI's built-in `CompressionQueryTransformer` for follow-up history and
  built-in `MultiQueryExpander` for the retrieval query. The expander keeps the
  original request and generates two additional variants by default. Its
  project-supplied prompt requires exact lexical variants to preserve product
  names, quoted phrases, identifiers, and other unusual terms. Spring AI's
  built-in `ConcatenationDocumentJoiner` merges and de-duplicates all results.
  This prevents a semantic rewrite from discarding an exact term such as
  `破皮沙发`.
- `AGENT` uses Spring AI Tool Calling. A model must declare
  `capabilities.toolCalling=true`; Collection/document/credential scope remains
  server-owned.
- `PLAIN` does not retrieve and rejects retrieval-specific request overrides.
- Client-cancelled partial turns are not persisted.

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
- `spring_ai_chat_memory`: Spring AI auto-management, for LLM context
- `rag_chat_history`: Principal-owned business history with complete
  `user_message`, `ai_response`, source snapshots, mode/model metadata, and TTL
  cleanup

Completed turns update both stores atomically under a
`rag_chat_session_lease`. History, export, clear, and Memory baselines are
scoped to the authenticated principal.

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
```

| Property | Default | Description |
|----------|---------|-------------|
| `rag.security.root-api-key` | `""` | Standalone-service root credential; environment variable `RAG_ROOT_API_KEY` |
| `rag.security.enabled` | `false` | Enable API Key authentication |
| `rag.security.api-key` | `""` | Legacy static unrestricted key; ignored for authentication in root mode |

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
- Root-created business keys have the fixed `FULL_RAG` data-plane profile,
  cannot manage keys, and require a future expiry without a fixed maximum
  lifetime.

Without `RAG_ROOT_API_KEY`, legacy behavior remains: `rag.security.enabled`
controls authentication, while `rag.security.api-key` and database
ADMIN/NORMAL semantics continue to apply, including query-credential
compatibility.

Database business keys define their external scope with
`allowedCollectionKeys` through `POST /api/v1/rag/api-keys`; deprecated
`allowedCollectionIds` remains compatible. The controller resolves keys to
internal IDs, and Flyway V24 storage remains
`rag_api_key.allowed_collection_ids`; null/blank means unrestricted. An
explicit empty key list is rejected instead of silently granting unrestricted
access. See [rest-api.md](rest-api.md).

## API Rate Limiting Configuration

```yaml
rag:
  rate-limit:
    enabled: true
    requests-per-minute: 60
    strategy: ip
    key-limits:
      vip-key: 200
      basic-key: 60
```

| Property | Default | Description |
|----------|---------|-------------|
| `rag.rate-limit.enabled` | `true` | Enable API rate limiting |
| `rag.rate-limit.requests-per-minute` | `60` | Default max requests per minute |
| `rag.rate-limit.strategy` | `ip` | Rate limit strategy: `ip` (by IP) / `api-key` (by API Key, falls back to IP if no key) |
| `rag.rate-limit.key-limits` | `{}` | Per-API-Key tiered limits (key → requests-per-minute) |

**Rate limit strategy selection:**
- `ip`: Count per client IP independently, suitable for unauthenticated scenarios
- `api-key`: Rate limit by API Key (falls back to IP if no key), suitable for multi-tenant; unconfigured keys use default `requests-per-minute`

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
state.

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
