# Customer Service Bot — RAG Integration Demo

> Scenario: An existing Spring Boot application gains full RAG capabilities by adding a single `spring-ai-rag-starter` dependency.

## Quick Start

### 1. Install spring-ai-rag-starter to local Maven repository

```bash
# From the spring-ai-rag project root
cd ../..
mvn clean install -DskipTests
```

### 2. Start this Demo

```bash
cd demos/demo-basic-rag
export DEEPSEEK_API_KEY=sk-xxx
export SILICONFLOW_API_KEY=sk-xxx
mvn spring-boot:run
```

### 3. Test

```bash
# Customer service Q&A
curl -X POST http://localhost:8080/api/v1/rag/chat/ask \
  -H "Content-Type: application/json" \
  -d '{"message": "What is your return and exchange policy?", "sessionId": "customer-001"}'

# Upload a knowledge base document
curl -X POST http://localhost:8080/api/v1/rag/documents \
  -H "Content-Type: application/json" \
  -d '{"title": "Return Policy", "content": "You may apply for returns or exchanges within 7 days of receiving the product...", "source": "policy"}'
```

---

## Integrating into an Existing Project

### Step 1: Add Maven Dependency

```xml
<dependency>
    <groupId>com.springairag</groupId>
    <artifactId>spring-ai-rag-starter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### Step 2: Configure application.yml

See `src/main/resources/application.yml` for reference.
Only configure:
- `spring.datasource.*` (PostgreSQL + pgvector)
- `app.llm.*` (LLM Provider)
- `siliconflow.*` (Embedding Model)

### Step 3: Use Directly

```java
@RestController
public class CustomerServiceController {

    @Autowired
    private RagChatService ragChatService;

    @PostMapping("/chat")
    public Map<String, String> chat(@RequestBody ChatRequest req) {
        String answer = ragChatService.chat(req.getMessage(), req.getSessionId());
        return Map.of("answer", answer, "sessionId", req.getSessionId());
    }
}
```

---

## What You Get with One Line of Code

| Feature | Effort Without Starter | With Starter |
|---------|----------------------|--------------|
| Vector Search | Write JDBC SQL + HNSW | ✅ Automatic |
| Full-text Search | Write tokenizer + similarity SQL | ✅ Automatic |
| Query Rewrite | Call LLM to rewrite query | ✅ Automatic |
| Reranking | Implement ReRank yourself | ✅ Automatic |
| Chat Memory | Write tables + CRUD | ✅ Automatic |
| REST API | Write Controller yourself | ✅ 40+ endpoints |
| Health Checks | Write Probe | ✅ Actuator auto |
| Document Embedding | Write embedding logic | ✅ Automatic |

## Database Setup

```bash
createdb spring_ai_rag_dev
psql spring_ai_rag_dev -c "CREATE EXTENSION IF NOT EXISTS vector;"
psql spring_ai_rag_dev -c "CREATE EXTENSION IF NOT EXISTS pg_trgm;"
```

Flyway automatically creates all tables on startup (HNSW indexes, full-text search configuration, etc.).

## Switching LLM Provider

```yaml
# DeepSeek (default)
app.llm.provider: openai

# Zhipu GLM
app.llm.provider: openai
spring.ai.openai.base-url: https://open.bigmodel.cn/paas/v4

# Anthropic
app.llm.provider: anthropic
```
