# Troubleshooting Guide

> 📖 English | 📖 中文

> Common problems and solutions, organized by symptom.

---

## Startup Issues

### Flyway Migration Failed

**Symptom**: `FlywayException` or `Migration failed` on startup

**Cause**: Required PostgreSQL extensions not installed

**Solution**:

```sql
-- Execute in the target database
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;
```

Verify extensions installed:

```sql
SELECT extname FROM pg_extension WHERE extname IN ('vector', 'pg_trgm');
```

---

### Database Connection Failed

**Symptom**: `Connection refused` or `FATAL: database does not exist`

**Troubleshooting**:

```bash
# 1. Check if PostgreSQL is running
pg_isready -h localhost -p 5432

# 2. Check if database exists
psql -h localhost -U postgres -l | grep spring_ai_rag

# 3. Check configuration
grep -A5 "spring.datasource" application.yml
```

**Common causes**:
- PostgreSQL not started
- Port not 5432
- Database name mismatch
- Wrong username/password

---

### Bean Creation Failed

**Symptom**: `NoSuchBeanDefinitionException` or `UnsatisfiedDependencyException`

**Troubleshooting**:

```bash
# Check if starter is included
mvn dependency:tree | grep spring-ai-rag-starter

# Check component scanning
grep -rn "@ComponentScan" src/
```

**Solution**: Ensure demo's main class is under `com.springairag` package, or add:

```java
@SpringBootApplication(scanBasePackages = "com.springairag")
```

---

## Embedding Issues

### Embedding Request Timeout

**Symptom**: Document embedding hangs indefinitely, then times out

**Cause**: SiliconFlow API rate limiting or network issue

**Solution**:

```yaml
rag:
  embedding:
    timeout-ms: 30000      # Increase timeout
    batch-size: 5           # Reduce batch size
    retry-count: 3          # Increase retry count
```

---

### Vector Dimension Mismatch

**Symptom**: `Vector dimension mismatch` or `expected 1024, got xxx`

**Cause**: Embedding model dimensions don't match database vector column

**Troubleshooting**:

```sql
-- Check pgvector column dimensions
SELECT column_name, data_type
FROM information_schema.columns
WHERE table_name = 'rag_embeddings' AND column_name = 'embedding';
```

**Solution**: Ensure `rag.embedding.dimensions` matches pgvector column definition. BGE-M3 is 1024 dimensions.

---

## Retrieval Issues

### Retrieval Results Empty

**Symptom**: Query returns empty list, but documents were imported

**Troubleshooting**:

```bash
# 1. Check if documents are embedded
curl http://localhost:8080/api/v1/rag/documents/stats

# 2. Call retrieval directly (skip LLM)
curl "http://localhost:8080/api/v1/rag/search?query=test&limit=5"

# 3. Lower similarity threshold
curl -X POST http://localhost:8080/api/v1/rag/search \
  -H "Content-Type: application/json" \
  -d '{"query": "test", "config": {"minScore": 0.1, "maxResults": 10}}'
```

**Common causes**:
- Document not embedded (created but no vectors generated)
- `minScore` threshold too high
- Query language doesn't match document language

---

### Poor Retrieval Quality

**Symptom**: Returned results are unrelated to the query

**Optimization strategies**:

1. **Enable query rewriting**:
```yaml
rag:
  query-rewrite:
    enabled: true
    llm-enabled: true  # LLM-assisted rewriting
```

2. **Adjust hybrid search weights**:
```bash
curl "http://localhost:8080/api/v1/rag/search?query=xxx&vectorWeight=0.7&fulltextWeight=0.3"
```

3. **Enable reranking**:
```yaml
rag:
  rerank:
    enabled: true
    top-k: 20  # Results to keep before reranking
```

---

## LLM Issues

### LLM Call 401/403

**Symptom**: `Unauthorized` or `Forbidden`

**Troubleshooting**:

```bash
# Check API Key configuration
echo $OPENAI_API_KEY | head -c 10

# Check base-url is correct
grep "base-url" application.yml
```

**Solution**: Confirm API Key is valid and not expired, base-url matches provider.

---

### LLM Response Slow

**Symptom**: Single Q&A takes >10 seconds

**Troubleshooting**:

```bash
# Check timing of each stage (check logs)
grep -i "retrieval\|llm\|total" logs/application.log | tail -10
```

**Optimization**:
- Reduce `maxResults` (number of retrieval results)
- Shorten system prompt
- Use faster model
- Enable streaming (`/stream`) to improve perceived speed

---

### Streaming Response Interrupted

**Symptom**: SSE stream disconnects mid-way

**Troubleshooting**:

```bash
# Check SSE connection
curl -N -X POST http://localhost:8080/api/v1/rag/chat/stream \
  -H "Content-Type: application/json" \
  -d '{"message": "test", "sessionId": "s1"}' 2>&1
```

**Common causes**:
- Reverse proxy (Nginx) timeout: increase `proxy_read_timeout`
- LLM response timeout: increase `rag.llm.timeout-ms`
- Client disconnect: check network stability

---

## API Issues

### 400 Bad Request

**Response**:

```json
{
  "status": 400,
  "error": "Bad Request",
  "message": "message content cannot be empty"
}
```

**Troubleshooting**: Check if request body meets field validation requirements:

| Field | Constraint |
|-------|-----------|
| `message` | Non-empty, ≤10000 characters |
| `sessionId` | Non-empty |
| `title` | Non-empty |
| `content` | Non-empty |
| `name` | Non-empty, ≤255 characters |

---

### 401 Unauthorized

**Cause**: API Key authentication enabled but not provided

**Solution**:

```bash
curl -H "X-API-Key: your-key" http://localhost:8080/api/v1/rag/chat/ask ...
```

Or temporarily disable authentication:

```yaml
rag:
  security:
    api-keys: ""  # Clear to disable auth
```

---

### 404 Not Found

**Symptom**: Document/collection ID doesn't exist

**Solution**: First verify ID exists:

```bash
curl http://localhost:8080/api/v1/rag/documents?page=0&size=10
curl http://localhost:8080/api/v1/rag/collections?page=0&size=10
```

---

## Monitoring Issues

### Alert False Positives

**Symptom**: Receiving frequent SLO alerts

**Troubleshooting**:

```bash
# View active alerts
curl http://localhost:8080/api/v1/rag/alerts/active

# View SLO configuration
curl http://localhost:8080/api/v1/rag/alerts/slos
```

**Adjust**:

```yaml
rag:
  slo:
    search-latency-ms: 1000  # Adjust search latency threshold
    chat-latency-ms: 10000   # Adjust chat latency threshold
    error-rate-percent: 5    # Adjust error rate threshold
```

---

## Performance Issues

### High Retrieval Latency (>500ms)

**Troubleshooting**:

```sql
-- Check if indexes exist
SELECT indexname, indexdef
FROM pg_indexes
WHERE tablename = 'rag_embeddings';

-- Check vector index type
SELECT * FROM pg_indexes WHERE indexdef LIKE '%hnsw%' OR indexdef LIKE '%ivfflat%';
```

**Optimization**:

```sql
-- If no vector index exists, create HNSW index
CREATE INDEX ON rag_embeddings
USING hnsw (embedding vector_cosine_ops)
WITH (m = 16, ef_construction = 200);
```

```yaml
rag:
  search:
    cache-enabled: true      # Enable retrieval cache
    cache-ttl-seconds: 300   # Cache for 5 minutes
```

---

### High Memory Usage

**Troubleshooting**:

```bash
# Check JVM memory usage
jmap -heap $(pgrep -f spring-ai-rag)
```

**Optimization**:

```bash
# Limit JVM memory
java -Xms512m -Xmx1024m -jar spring-ai-rag.jar
```

---

## Logging & Debugging

### Enable Detailed Logging

```yaml
logging:
  level:
    com.springairag: DEBUG
    org.springframework.ai: DEBUG
```

### Key Log Identifiers

| Log Keyword | Description |
|-------------|-------------|
| `HybridRetrieverService` | Retrieval execution |
| `QueryRewritingService` | Query rewriting |
| `ReRankingService` | Reranking |
| `RagChatService` | Q&A flow |
| `DomainExtensionRegistry` | Domain extension registration |
| `ApiKeyAuthFilter` | API authentication |

### Export Diagnostic Info

```bash
# Export health check
curl http://localhost:8080/api/v1/rag/health | jq .

# Export document statistics
curl http://localhost:8080/api/v1/rag/documents/stats | jq .

# Export alert statistics
curl http://localhost:8080/api/v1/rag/alerts/stats | jq .
```

---

## Getting Help

- [Architecture Design](architecture.md) — Understand system design
- [Configuration Reference](configuration.md) — All configuration items
- [Testing Guide](testing-guide.md) — How to write tests
- GitHub Issues — Submit bug reports
