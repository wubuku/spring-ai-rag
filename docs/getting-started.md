# Getting Started Guide

> 📖 English | 📖 中文

> Get spring-ai-rag running from scratch — first RAG Q&A in 5 minutes.

---

## Prerequisites

| Dependency | Version | Notes |
|------------|---------|-------|
| JDK | 21+ (LTS, Virtual Threads) | Required |
| Maven | 3.9+ | Build tool |
| PostgreSQL | 15+ | Requires `vector` and `pg_trgm` extensions |
| API Keys | LLM + Embedding | DeepSeek/OpenAI + SiliconFlow |

---

## 1. Clone the Project

```bash
git clone https://github.com/your-org/spring-ai-rag.git
cd spring-ai-rag
```

## 2. Prepare the Database

```sql
CREATE DATABASE spring_ai_rag;

-- After connecting to the new database, execute:
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;
```

Flyway automatically creates the schema on first startup.

## 3. Configure Environment Variables

Create `.env` in the project root:

```bash
# LLM (default: DeepSeek, OpenAI-compatible)
OPENAI_API_KEY=sk-xxx
OPENAI_BASE_URL=https://api.deepseek.com/v1

# Embedding model (SiliconFlow BGE-M3)
SILICONFLOW_API_KEY=sk-xxx

# Database
DB_URL=jdbc:postgresql://localhost:5432/spring_ai_rag
DB_USERNAME=postgres
DB_PASSWORD=yourpassword
```

## 4. Build & Test

```bash
# Load env vars and compile
export $(cat .env | grep -v '^#' | xargs)
mvn clean compile

# Run all tests (700+)
mvn test
```

## 5. Start a Demo

```bash
cd demos/demo-basic-rag
export $(cat ../../.env | grep -v '^#' | xargs)
mvn spring-boot:run
```

Service starts at `http://localhost:8080`.

## 6. First RAG Q&A

### Import a Document

```bash
curl -X POST http://localhost:8080/api/v1/rag/documents \
  -H "Content-Type: application/json" \
  -d '{
    "title": "Introduction to Spring AI",
    "content": "Spring AI is Spring's AI application framework, providing ChatClient, VectorStore, Advisor, and other abstractions.",
    "source": "manual"
  }'
```

### Embed the Document

```bash
# Assuming document ID is 1
curl -X POST http://localhost:8080/api/v1/rag/documents/1/embed
```

### RAG Q&A

```bash
curl -X POST http://localhost:8080/api/v1/rag/chat/ask \
  -H "Content-Type: application/json" \
  -d '{"message": "What is Spring AI?"}'
```

### Streaming Q&A

```bash
curl -N -X POST http://localhost:8080/api/v1/rag/chat/stream \
  -H "Content-Type: application/json" \
  -d '{"message": "Explain how RAG works"}'
```

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
