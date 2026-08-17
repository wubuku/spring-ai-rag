# Troubleshooting Guide

> 📖 [English](troubleshooting.md) · 📖 [中文](troubleshooting-zh-CN.md)

> Common problems and solutions, organized by symptom.
>
> Doc hub: [index.md](index.md)

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

### Duplicate RagProperties Bean

**Symptom**: Application fails to start with error:
```
No qualifying bean of type 'com.springairag.core.config.RagProperties' available:
expected single matching bean but found 2: rag-com.springairag.core.config.RagProperties,ragProperties
```

**Cause**: `RagProperties` is registered by multiple sources:
1. `SpringAiConfig` via `@EnableConfigurationProperties(RagProperties.class)`
2. `GeneralRagAutoConfiguration` via `@Bean public RagProperties ragProperties()`
3. `BasicRagDemoApplication` via `@ConfigurationPropertiesScan`

**Solution**:

1. In `SpringAiConfig.java`, remove `RagProperties.class` from `@EnableConfigurationProperties`:
```java
@EnableConfigurationProperties({RagMemoryProperties.class, RagPdfProperties.class})
// RagProperties 不再需要在这里注册
```

2. In `GeneralRagAutoConfiguration.java`, mark the bean as `@Primary`:
```java
@Bean
@Primary
public RagProperties ragProperties() {
    return new RagProperties();
}
```

**Startup Command Tips**:

```bash
# 正确方式：使用 && 链接命令（但需要注意环境变量加载）
cd demos/demo-basic-rag && export $(cat ../../.env | grep -v '^#' | xargs) && mvn spring-boot:run

# 如果 .env 文件格式有问题，直接导出环境变量
export SPRING_PROFILES_ACTIVE=postgresql
export OPENAI_API_KEY="your-key"
export OPENAI_BASE_URL="https://api.siliconflow.cn"
export SILICONFLOW_API_KEY="your-key"
export POSTGRES_HOST="localhost"
export POSTGRES_PORT="5432"
export POSTGRES_DATABASE="spring_ai_rag_dev"
export POSTGRES_USER="postgres"
export POSTGRES_PASSWORD="123456"

mvn spring-boot:run
```

**Debugging**: 如果启动失败，检查端口是否被占用：
```bash
# 查找占用端口的进程
lsof -i:8081 -sTCP:LISTEN

# 终止进程
kill -9 <PID>
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
curl http://localhost:8081/api/v1/rag/documents/stats

# 2. Call retrieval directly (skip LLM)
curl "http://localhost:8081/api/v1/rag/search?query=test&limit=5"

# 3. Lower similarity threshold
curl -X POST http://localhost:8081/api/v1/rag/search \
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
curl "http://localhost:8081/api/v1/rag/search?query=xxx&vectorWeight=0.7&fulltextWeight=0.3"
```

3. **Enable reranking**:
```yaml
rag:
  rerank:
    enabled: true
    top-k: 20  # Results to keep before reranking
```

---

### Search Shows Results but Chat Says Nothing Was Found

**Symptom**: A short term returns semantic results on the Search page, but a full
command such as “find content related to X” makes Chat say that the references contain
nothing relevant.

**Causes and interpretation**:

1. Search and Chat use the same retrieval service, but command words can change the
   query embedding and fill Chat's smaller Top-K with unrelated chunks.
2. `fulltextScore=0` means the result came only from vector semantics; it does not prove
   that the document contains the keyword.
3. PDF extraction can produce visually similar but code-point-distinct CJK Radical or
   Kangxi Radical characters. For example, `风格基调` and `⻛格基调` look similar but
   are not equal to full-text search.

**Troubleshooting**:

```bash
# Compare the short subject with the complete command
curl "http://localhost:8081/api/v1/rag/search?query=visual-tone&limit=5"
curl --get "http://localhost:8081/api/v1/rag/search" \
  --data-urlencode 'query=find content related to "visual tone"' \
  --data 'limit=5'
```

With Pipeline INFO logging, the original and focused retrieval queries should appear:

```text
original query: "find content related to \"visual tone\"" → retrieval query: "visual tone"
```

If PDF character mapping is suspected, inspect the stored text around a neighboring
term. Reimport and re-embed affected documents after fixing extraction normalization.

---

## LLM Issues

### LLM Call 401/403

**Symptom**: `Unauthorized` or `Forbidden`

**Troubleshooting**:

```bash
# Check API Key configuration
echo $OPENAI_API_KEY | head -c 10
echo $SPRING_AI_OPENAI_API_KEY | head -c 10

# Check base-url is correct
grep -n "base-url\|baseUrl" spring-ai-rag-core/src/main/resources/application*.yml
```

**Solution**:

1. Confirm the API Key is valid and not expired, and that the base-url matches the provider.  
2. **Do not include a `/v1` suffix on `base-url`**. Spring AI’s OpenAI-compatible clients append `/v1/chat/completions` or `/v1/embeddings`; if `/v1` is already present, the final path becomes `/v1/v1/...` → 401/404.  
   - Correct: `https://api.deepseek.com`, `https://api.siliconflow.cn`  
   - Incorrect: `https://api.deepseek.com/v1`  
   - See: [Spring AI #710](https://github.com/spring-projects/spring-ai/issues/710)

### Config seems ignored after start / cannot reach model or DB

**Symptom**: `.env` is filled in, but the app still uses defaults or fails auth/connection.

**Cause**: After only `source .env`, `mvn spring-boot:run` may fork a JVM that does not inherit every variable.

**Solution**:

```bash
# Preferred: project script passes args explicitly
bash scripts/start-server.sh

# Or export, then start with the right profile
export $(cat .env | grep -v '^#' | xargs)
export SPRING_PROFILES_ACTIVE=postgresql
mvn spring-boot:run -pl spring-ai-rag-core -DskipTests
```

Local default port is **8081** (not 8080).

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
curl -N -X POST http://localhost:8081/api/v1/rag/chat/stream \
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
curl -H "X-API-Key: your-key" http://localhost:8081/api/v1/rag/chat/ask ...
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
curl http://localhost:8081/api/v1/rag/documents?page=0&size=10
curl http://localhost:8081/api/v1/rag/collections?page=0&size=10
```

---

## Monitoring Issues

### Alert False Positives

**Symptom**: Receiving frequent SLO alerts

**Troubleshooting**:

```bash
# View active alerts
curl http://localhost:8081/api/v1/rag/alerts/active

# View SLO configuration
curl http://localhost:8081/api/v1/rag/alerts/slos
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
curl http://localhost:8081/api/v1/rag/health | jq .

# Export document statistics
curl http://localhost:8081/api/v1/rag/documents/stats | jq .

# Export alert statistics
curl http://localhost:8081/api/v1/rag/alerts/stats | jq .
```

---

## Mainland China Network Issues

### Docker repeatedly times out while pulling base images

**Symptom**: the build stalls at `FROM` / `load metadata`, or access to `gcr.io` or Docker Hub repeatedly times out.

**Resolution**:

```bash
# Pull from docker.m.daocloud.io first, then fall back to official sources
./scripts/docker-build-local.sh

# Use a team registry
MIRROR_BASE_URL=your.registry.example ./scripts/docker-build-local.sh

# Force Docker Hub when direct access is more reliable
./scripts/docker-build-local.sh --official
```

The release Dockerfile no longer depends on `gcr.io` and accepts `MAVEN_IMAGE` / `RUNTIME_IMAGE` build arguments. See the [mainland China network guide](china-network-guide.md) for architecture, Maven/npm/Playwright downloads, and proxy notes.

### JSONB PostgreSQL test cannot start its container

**Symptoms**:

- Testcontainers reports that Docker API `1.32` is below the daemon minimum
  `1.40`.
- The Ryuk helper image fails with a proxy-issued certificate or registry
  timeout.

**Solution**:

```bash
TESTCONTAINERS_RYUK_DISABLED=true \
./scripts/verify-jsonb-records.sh --skip-playwright
```

The verifier passes `-Dapi.version=1.40` and uses
`pgvector/pgvector:pg16` by default. Override
`TESTCONTAINERS_API_VERSION`, `TESTCONTAINERS_RYUK_DISABLED`, or
`TESTCONTAINERS_PG_IMAGE` when the local Docker environment differs. These
are test-environment overrides; they do not belong in application YAML or the
Dockerfile. Re-enable Ryuk in CI/shared environments where the registry and
certificate chain are trusted.

### JSONB verifier preview port is occupied

The verifier's Mock Playwright phase starts its own Vite preview with strict
port binding. If the default `4174` is already used by another development
server, select an unused port instead of reusing the existing process:

```bash
JSONB_PLAYWRIGHT_PORT=4199 ./scripts/verify-jsonb-records.sh
```

The verifier checks the preview process and uses bounded readiness requests.
An occupied port is a verification failure, not permission to kill or reuse
another project's server.

---

## Getting Help

- [Architecture Design](architecture.md) — Understand system design
- [Configuration Reference](configuration.md) — All configuration items
- [Testing Guide](testing-guide.md) — How to write tests
- [Mainland China Network Guide](china-network-guide.md) — Docker / Maven / npm / Playwright
- GitHub Issues — Submit bug reports
