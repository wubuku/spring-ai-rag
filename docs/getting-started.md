# Getting Started Guide

> 📖 English | 📖 中文

> Get spring-ai-rag running from scratch — first RAG Q&A in 5 minutes.

---

## Prerequisites

| Dependency | Version | Notes |
|------------|---------|-------|
| JDK | 21+ (verified on 21 and 25; compiled for 21) | Required |
| Maven | 3.9+ | Build tool |
| PostgreSQL | 15+ | Requires `vector` and `pg_trgm` extensions |
| API Keys | LLM + Embedding | DeepSeek/OpenAI + SiliconFlow |

---

## 1. Clone the Project

```bash
git clone https://github.com/wubuku/spring-ai-rag.git
cd spring-ai-rag
```

## 2. Prepare the Database

```bash
createdb spring_ai_rag_dev
psql spring_ai_rag_dev -c "CREATE EXTENSION IF NOT EXISTS vector;"
psql spring_ai_rag_dev -c "CREATE EXTENSION IF NOT EXISTS pg_trgm;"
# Optional: Chinese word segmentation
# psql spring_ai_rag_dev -c "CREATE EXTENSION IF NOT EXISTS pg_jieba;"
```

Flyway creates the schema automatically on first startup (V1 through latest).

## 3. Configure Environment Variables

`.env` is gitignored and never committed. Start from the template:

```bash
cp .env.example .env
```

Replace at least the following (names must match `.env.example`; the legacy
`OPENAI_API_KEY` / `OPENAI_BASE_URL` / `OPENAI_MODEL` names still work as fallbacks):

```bash
# Database (must match the database created above)
POSTGRES_HOST=localhost
POSTGRES_PORT=5432
POSTGRES_DATABASE=spring_ai_rag_dev
POSTGRES_USER=postgres
POSTGRES_PASSWORD=yourpassword

# LLM (default: DeepSeek, OpenAI-compatible; base-url must NOT include /v1)
SPRING_AI_OPENAI_API_KEY=sk-xxx
SPRING_AI_OPENAI_BASE_URL=https://api.deepseek.com
SPRING_AI_OPENAI_CHAT_OPTIONS_MODEL=deepseek-chat

# Embedding model (example: SiliconFlow BGE-M3)
RAG_EMBEDDING_API_KEY=sk-xxx
RAG_EMBEDDING_BASE_URL=https://api.siliconflow.cn
RAG_EMBEDDING_MODEL=BAAI/bge-m3
RAG_EMBEDDING_DIMENSIONS=1024
```

Without real credentials, use the bundled mock provider to exercise the pipeline
(it validates the flow, not answer quality):

```bash
./scripts/run-mock-llm.sh --port 8086 &
# Then set in .env:
# SPRING_AI_OPENAI_API_KEY=mock-key
# SPRING_AI_OPENAI_BASE_URL=http://127.0.0.1:8086
# SPRING_AI_OPENAI_CHAT_OPTIONS_MODEL=mock-chat
# RAG_EMBEDDING_API_KEY=mock-key
# RAG_EMBEDDING_BASE_URL=http://127.0.0.1:8086
# RAG_EMBEDDING_MODEL=mock-embedding
# RAG_RETRIEVAL_MIN_SCORE=0
```

## 4. Build & Test

```bash
# Load .env (more robust than export $(cat ...) when values contain spaces or quotes)
set -a; source .env; set +a

# Install this repo's modules first: the demos depend on
# com.springairag:spring-ai-rag-starter:1.0.0, which is not on Maven Central,
# so it must be installed from source into the local repository.
mvn -B -DskipTests install

# Run all backend tests (uses the real database from .env)
mvn clean test
```

## 5. Start a Demo

```bash
cd demos/demo-basic-rag
set -a; source ../../.env; set +a
mvn spring-boot:run
```

Service starts at `http://localhost:8081`. Confirm the health check first:

```bash
curl -sf http://localhost:8081/actuator/health
```

## 6. First RAG Q&A

### Import a Document

```bash
curl -X POST http://localhost:8081/api/v1/rag/documents \
  -H "Content-Type: application/json" \
  -d '{
    "title": "Introduction to Spring AI",
    "content": "Spring AI is Spring's AI application framework, providing ChatClient, VectorStore, Advisor, and other abstractions.",
    "source": "manual"
  }'
```

The `id` in the response is the document ID (do not assume it is 1):

```bash
DOC_ID=$(curl -s -X POST http://localhost:8081/api/v1/rag/documents \
  -H "Content-Type: application/json" \
  -d '{"title":"Introduction to Spring AI","content":"Spring AI is Spring AI application framework.","source":"manual"}' \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["id"])')
```

### Embed the Document

```bash
curl -X POST "http://localhost:8081/api/v1/rag/documents/${DOC_ID}/embed"
```

### Search (no LLM)

```bash
curl -s "http://localhost:8081/api/v1/rag/search?query=Spring%20AI&limit=3"
```

### RAG Q&A

```bash
curl -X POST http://localhost:8081/api/v1/rag/chat/ask \
  -H "Content-Type: application/json" \
  -d '{"message": "What is Spring AI?"}'
```

### Streaming Q&A

```bash
curl -N -X POST http://localhost:8081/api/v1/rag/chat/stream \
  -H "Content-Type: application/json" \
  -d '{"message": "Explain how RAG works"}'
```

> The whole flow is codified in `scripts/verify-clean-clone.sh`: it clones into a
> temporary directory, creates a temporary database, and runs health → documents →
> embed → search → chat against the mock provider, then cleans up. Run it anytime
> with `./scripts/verify-clean-clone.sh`.

---

## Project Structure

```
spring-ai-rag/
├── spring-ai-rag-api/              # Interfaces, DTOs
├── spring-ai-rag-core/             # Core implementation
│   ├── advisor/                    # RAG Pipeline Advisors
│   ├── controller/                 # REST endpoints
│   ├── config/                     # Configuration classes
│   ├── retrieval/                  # Hybrid retrieval, query rewriting
│   ├── service/                    # Business services
│   └── entity/                     # JPA entities
├── spring-ai-rag-starter/          # Spring Boot auto-configuration
├── spring-ai-rag-documents/        # Document processing
│   └── chunk/                      # Chunking strategies
└── demos/
    ├── demo-basic-rag/             # Basic RAG demo
    ├── demo-multi-model/           # Multi-model demo
    ├── demo-component-level/       # Component-level demo
    └── demo-domain-extension/      # Domain extension demo
```

---

## Switching LLM Providers

### Using Anthropic

```yaml
app:
  llm:
    provider: anthropic

spring:
  ai:
    anthropic:
      api-key: ${ANTHROPIC_API_KEY}
```

### Using Zhipu

```yaml
app:
  llm:
    provider: openai

spring:
  ai:
    openai:
      api-key: ${ZHIPU_API_KEY}
      base-url: https://open.bigmodel.cn/paas/v4
```

More configuration options in [Configuration Reference](configuration.md).

---

## Development Mode

### Hot Reload

```bash
# spring-boot:run with devtools support
cd demos/demo-basic-rag
mvn spring-boot:run -Dspring-boot.run.jvmArguments="-Dspring.devtools.restart.enabled=true"
```

### Unit Tests

```bash
# Run core module tests only
mvn test -pl spring-ai-rag-core

# Run specific test class
mvn test -pl spring-ai-rag-core -Dtest=RagChatControllerTest
```

### JaCoCo Coverage

```bash
mvn test jacoco:report
# Reports in each module's target/site/jacoco/index.html
```

---

## FAQ

**Q: Flyway migration fails?**

Ensure PostgreSQL has the `vector` extension installed. Execute in the database:

```sql
CREATE EXTENSION IF NOT EXISTS vector;
```

**Q: Embedding request timeout?**

SiliconFlow free API has rate limits. Check `rag.embedding.batch-size` (default 10) and `rag.embedding.timeout-ms`.

**Q: How to customize chunking strategy?**

Implement `TextChunker` interface or configure `HierarchicalTextChunker` with `rag.chunking.*` parameters. See [Architecture](architecture.md#document-processing) for details.

---

## Next Steps

- [Architecture Design](architecture.md) — Understand the RAG Pipeline and extension mechanism
- [Configuration Reference](configuration.md) — All configuration items explained
- [Domain Extension Guide](extension-guide.md) — Plug in your business domain
- [REST API Reference](rest-api.md) — Complete endpoint documentation
