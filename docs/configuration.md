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
      base-url: https://api.deepseek.com/v1
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
  openai:
    api-key: ${OPENAI_API_KEY}
    base-url: ${OPENAI_BASE_URL:https://api.deepseek.com/v1}
    chat:
      enabled: false
      options:
        model: ${OPENAI_MODEL:deepseek-chat}
        temperature: ${OPENAI_TEMPERATURE:0.7}
```

| Property | Default | Description |
|----------|---------|-------------|
| `spring.openai.api-key` | (required) | API Key |
| `spring.openai.base-url` | `https://api.deepseek.com/v1` | API endpoint |
| `spring.openai.chat.options.model` | `deepseek-chat` | Model name |
| `spring.openai.chat.options.temperature` | `0.7` | Generation temperature |

### Anthropic Configuration

```yaml
spring:
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

## Embedding Model Configuration

Embedding model configuration is independent of the Chat provider and is always active.

```yaml
rag:
  embedding:
    api-key: ${SILICONFLOW_API_KEY}
    base-url: ${SILICONFLOW_URL:https://api.siliconflow.cn/v1}
    model: ${SILICONFLOW_MODEL:BAAI/bge-m3}
    dimensions: ${SILICONFLOW_DIMENSIONS:1024}
```

| Property | Default | Description |
|----------|---------|-------------|
| `rag.embedding.api-key` | `""` | SiliconFlow API Key |
| `rag.embedding.base-url` | `https://api.siliconflow.cn/v1` | API endpoint |
| `rag.embedding.model` | `BAAI/bge-m3` | Embedding model name |
| `rag.embedding.dimensions` | `1024` | Vector dimensions (must match model output) |

> ⚠️ When changing embedding models, `dimensions` must be updated synchronously, and pgvector table indexes must be rebuilt.

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
| `rag.retrieval.min-score` | `0.3` | Minimum similarity threshold |

> 💡 `vector-weight + fulltext-weight` is recommended to sum to `1.0`; the system auto-normalizes.

**Full-text search strategy (`fulltext-strategy`):**

| Strategy | Description | Dependency |
|----------|-------------|------------|
| `auto` | Auto-detect: pg_jieba → pg_trgm → pure vector | — |
| `pg_jieba` | PostgreSQL Chinese tokenizer (recommended for Chinese) | `pg_jieba` extension |
| `pg_trgm` | Trigram fuzzy matching | `pg_trgm` extension |
| `none` | Disable full-text, pure vector only | — |

See [PostgreSQL Extensions Documentation](postgresql-extensions.md).

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
| `rag.query-rewrite.enabled` | `true` | Enable query rewriting |
| `rag.query-rewrite.padding-count` | `2` | Number of expanded queries |
| `rag.query-rewrite.synonym-dictionary` | `{}` | Synonym dictionary |
| `rag.query-rewrite.domain-qualifiers` | `[]` | Domain qualifier terms |
| `rag.query-rewrite.llm-enabled` | `false` | Enable LLM-assisted rewriting |
| `rag.query-rewrite.llm-max-rewrites` | `3` | Max LLM rewrites per query |

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
| `rag.chunk.min-chunk-size` | `100` | Minimum chunk size (characters) |

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
- `rag_chat_history`: Business audit table, retains complete `user_message` + `ai_response`, auto-cleaned by TTL

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

## Security Authentication Configuration

```yaml
rag:
  security:
    api-key: ${RAG_API_KEY:}
    enabled: false
```

| Property | Default | Description |
|----------|---------|-------------|
| `rag.security.enabled` | `false` | Enable API Key authentication |
| `rag.security.api-key` | `""` | API Key value |

When enabled, all `/api/v1/**` requests must carry the `X-API-Key` header.

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
| `idle-timeout` | `300000` | Idle connection回收 time (ms) |
| `max-lifetime` | `1800000` | Connection max lifetime (ms) |
| `connection-timeout` | `10000` | Connection acquisition timeout (ms) |
| `leak-detection-threshold` | `60000` | Connection leak detection time (ms) |

## pgvector Vector Store Configuration

```yaml
# Activated via postgresql profile
spring:
  ai:
    vectorstore:
      pgvector:
        enabled: true
        vector-table-name: rag_vector_store
        distance-type: COSINE_DISTANCE
        index-type: HNSW
        dimensions: 1024
```

Activation: `--spring.profiles.active=postgresql`

| Property | Default | Description |
|----------|---------|-------------|
| `vector-table-name` | `rag_vector_store` | Vector table name |
| `distance-type` | `COSINE_DISTANCE` | Distance algorithm (COSINE_DISTANCE / EUCLIDEAN_DISTANCE / NEGATIVE_INNER_PRODUCT) |
| `index-type` | `HNSW` | Index type (HNSW / IVFFlat) |
| `dimensions` | `1024` | Vector dimensions |

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
- `GET /actuator/health` — Health check (DB, vector store, embedding model)
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
