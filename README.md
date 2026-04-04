# spring-ai-rag

📖 [English](README.md) · 📖 [中文](README-zh-CN.md)

---

A general-purpose RAG (Retrieval-Augmented Generation) service framework built on [Spring AI](https://docs.spring.io/spring-ai/reference/).

**Model-Agnostic · Domain-Decoupled · Component-Based**

## Why spring-ai-rag?

| Pain Point | spring-ai-rag Solution |
|------------|------------------------|
| Switching models requires code changes? | Configure the LLM in one line — OpenAI / DeepSeek / Anthropic / Zhipu all supported |
| Poor RAG quality? | Hybrid search (vector + fulltext) + query rewriting + reranking, layer-by-layer optimization |
| Only works for one domain? | Implement `DomainRagExtension` to support N vertical domains in one service |
| Too heavy to integrate? | Every Advisor / Service can be used independently — no bundling required |

## Features

- **Hybrid Search**: Vector search (pgvector HNSW) + fulltext search (pg_jieba tokenizer / pg_trgm trigram)
- **Fulltext Strategy**: Configurable `auto` (auto-detect) / `pg_jieba` / `pg_trgm` / `none`
- **Advisor Chain Pipeline**: QueryRewrite → HybridSearch → Rerank → Context Injection
- **Multi-Model Support**: OpenAI-compatible + Anthropic, switch providers with a single config change
- **Domain Extension**: Implement `DomainRagExtension` to inject domain prompts and retrieval strategies
- **SSE Streaming**: Server-Sent Events for real-time responses
- **A/B Experiment Framework**: Parallel multi-model comparison with automatic latency / token / quality metrics
- **Retrieval Evaluation**: Precision@K / MRR / NDCG evaluation + user feedback loop
- **Caching**: Embedding result cache + Caffeine L1 cache, externally configurable
- **Observability**: Micrometer metrics + Actuator health checks + request tracing (traceId)
- **API Key Auth**: Built-in security filter + per-user rate limiting (sliding window)
- **API Versioning**: `@ApiVersion` annotation for automatic `/api/v1/` path mapping

## Quick Start

### 1. Database

```bash
createdb spring_ai_rag_dev
psql spring_ai_rag_dev -c "CREATE EXTENSION IF NOT EXISTS vector;"
psql spring_ai_rag_dev -c "CREATE EXTENSION IF NOT EXISTS pg_trgm;"
# Optional (better for Chinese text):
# psql spring_ai_rag_dev -c "CREATE EXTENSION IF NOT EXISTS pg_jieba;"
```

Flyway runs V1–V10 migrations automatically on startup (tables + HNSW indexes + fulltext GIN indexes).

### 2. Add Dependency

```xml
<dependency>
    <groupId>com.springairag</groupId>
    <artifactId>spring-ai-rag-starter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### 3. Configure

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/spring_ai_rag_dev
    username: postgres
    password: ${DB_PASSWORD}

  # Flyway auto-migration (V1–V10)
  flyway:
    enabled: true

app:
  llm:
    provider: openai          # openai | anthropic
  ai:
    openai:
      api-key: ${DEEPSEEK_API_KEY}
      base-url: https://api.deepseek.com/v1
      chat:
        options:
          model: deepseek-chat
          temperature: 0.7

siliconflow:
  api-key: ${SILICONFLOW_API_KEY}
  embedding:
    model: BAAI/bge-m3
    dimensions: 1024

rag:
  retrieval:
    fulltext-enabled: true
    fulltext-strategy: auto   # auto | pg_jieba | pg_trgm | none
    vector-weight: 0.5
    default-limit: 10
    min-score: 0.3
```

### 4. Use

```bash
# RAG Q&A
curl -X POST http://localhost:8080/api/v1/rag/chat/ask \
  -H "Content-Type: application/json" \
  -d '{"message": "What is RAG?"}'

# Streaming Q&A
curl -N -X POST http://localhost:8080/api/v1/rag/chat/stream \
  -H "Content-Type: application/json" \
  -d '{"message": "Explain how RAG works in detail"}'

# Upload document
curl -X POST http://localhost:8080/api/v1/rag/documents \
  -H "Content-Type: application/json" \
  -d '{"title": "RAG Introduction", "content": "RAG stands for Retrieval-Augmented Generation..."}'

# Embed document
curl -X POST http://localhost:8080/api/v1/rag/documents/1/embed

# Search (no LLM)
curl "http://localhost:8080/api/v1/rag/search?query=RAG&limit=5"
```

After starting, visit `http://localhost:8080/swagger-ui.html` for the full API reference.

## Architecture

```
Request
  │
  ▼
QueryRewriteAdvisor (+10)  — Query rewriting for better recall
  │
  ▼
HybridSearchAdvisor (+20)  — Vector + fulltext hybrid retrieval
  │   ├─ pgvector HNSW (vector)
  │   └─ pg_jieba / pg_trgm (fulltext, GIN-indexed)
  │
  ▼
RerankAdvisor (+30)        — Result reranking
  │
  ▼
ChatClient.call()          — LLM response generation
  │
  ▼
MessageChatMemoryAdvisor  — Conversation memory
  │
  ▼
Response
```

```
spring-ai-rag/
├── spring-ai-rag-api/          # API interfaces, DTOs
├── spring-ai-rag-core/         # Core implementation
│   ├── advisor/                # QueryRewrite / HybridSearch / Rerank Advisors
│   ├── retrieval/              # Retrieval service + fulltext strategies
│   ├── retrieval/fulltext/     # pg_jieba / pg_trgm / no-op Providers
│   ├── controller/             # REST controllers
│   ├── service/                # Business services
│   ├── config/                 # RagChatService / RagProperties
│   └── metrics/                # Micrometer metrics
├── spring-ai-rag-starter/      # Spring Boot auto-configuration
├── spring-ai-rag-documents/    # Document processing (HierarchicalTextChunker)
└── demos/                      # Integration demo projects
```

## REST API Endpoints (40+)

| Module | Endpoint | Description |
|--------|----------|-------------|
| Chat | `POST /api/v1/rag/chat/ask` | Non-streaming RAG Q&A |
| Chat | `POST /api/v1/rag/chat/stream` | SSE streaming Q&A |
| Chat | `GET /api/v1/rag/chat/history/{sessionId}` | Session history |
| Search | `GET /api/v1/rag/search` | Hybrid search (no LLM) |
| Document | `POST /api/v1/rag/documents` | Create document |
| Document | `GET /api/v1/rag/documents/{id}` | Get document |
| Document | `POST /api/v1/rag/documents/{id}/embed` | Generate embedding |
| Document | `GET /api/v1/rag/documents/{id}/versions` | Version history |
| Collection | `/api/v1/rag/collections` | Knowledge base management |
| Evaluation | `/api/v1/rag/evaluations` | Retrieval quality evaluation |
| Feedback | `/api/v1/rag/feedbacks` | User feedback |
| A/B | `/api/v1/rag/ab-tests` | Experiment management |
| Alert | `/api/v1/rag/alerts` | Monitoring alerts |
| Cache | `GET /api/v1/rag/cache/stats` | Embedding cache hit rate |
| Health | `/actuator/health` | Actuator health check |

## Two Integration Modes

See `demos/README.md`:

| Mode | Demo | Use Case |
|------|------|----------|
| Full Starter | `demo-basic-rag` | Quick integration, one dependency |
| Component-level | `demo-component-level` | Existing Spring AI project, selective Advisor use |

## Build & Test

```bash
# Compile
mvn clean compile

# Test (real database, 1000+ tests)
export $(cat .env | grep -v '^#' | xargs)
mvn test

# Package
mvn clean package -DskipTests

# Run demo tests
cd demos/demo-basic-rag && mvn test
```

## Documentation

- [Architecture](docs/architecture.md)
- [Configuration Reference](docs/configuration.md)
- [REST API Reference](docs/rest-api.md)
- [PostgreSQL Extensions](docs/postgresql-extensions.md) (pg_jieba / pg_trgm / pgvector)
- [Domain Extension Guide](docs/extension-guide.md)
- [Testing Guide](docs/testing-guide.md)
- [Getting Started](docs/getting-started.md)
- [Troubleshooting](docs/troubleshooting.md)
- [CHANGELOG](CHANGELOG.md)
- [Contributing](CONTRIBUTING.md)

## License

Apache License 2.0
