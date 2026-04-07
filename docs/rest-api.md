# REST API Reference

📖 [English](rest-api.md) · 📖 [中文](rest-api-zh-CN.md)

> Swagger UI available at `/swagger-ui.html` after startup.
>
> Base path: `/api/v1/rag`

---

## General

### Authentication

When API Key authentication is enabled, include the following header in requests:

```
X-API-Key: your-api-key
```

Configure via: `rag.security.api-keys` (comma-separated for multiple keys).

### Rate Limiting

When `rag.rate-limit.enabled` is true, all API requests are subject to a sliding-window rate limit.

Two strategies are supported (`rag.rate-limit.strategy`):
- `ip`: Rate limit by client IP address
- `api-key`: Rate limit by API Key (falls back to IP if no key provided);分级限额可配置 `rag.rate-limit.key-limits`

**Rate limit response headers (on normal requests):**

| Header | Description |
|--------|-------------|
| `X-RateLimit-Limit` | Max requests per minute |
| `X-RateLimit-Remaining` | Remaining quota in current window |

**Rate limit exceeded response:**

```
HTTP/1.1 429 Too Many Requests
Retry-After: 60
X-RateLimit-Limit: 100
X-RateLimit-Remaining: 0
```

### Error Responses (RFC 7807 Problem Detail)

All error responses follow [RFC 7807](https://datatracker.ietf.org/doc/html/rfc7807) `application/problem+json` format:

```json
{
  "type": "about:blank",
  "title": "Bad Request",
  "status": 400,
  "detail": "message content must not be blank",
  "instance": "/api/v1/rag/chat/ask"
}
```

| Field | Description |
|-------|-------------|
| `type` | Problem type URI (defaults to `about:blank`) |
| `title` | HTTP status text |
| `status` | HTTP status code |
| `detail` | Specific error description |
| `instance` | Request path where the error occurred |

**Parameter validation errors** (400) merge multiple field errors:

```json
{
  "type": "about:blank",
  "title": "Bad Request",
  "status": 400,
  "detail": "message: must not be blank; sessionId: must not be blank",
  "instance": "/api/v1/rag/chat/ask"
}
```

---

## Chat — RAG Q&A

### `POST /api/v1/rag/chat/ask`

Non-streaming RAG Q&A, returns a complete answer.

**Request body:**

```json
{
  "message": "What is Spring AI?",
  "sessionId": "session-001",
  "domainId": "medical",
  "maxResults": 5,
  "metadata": {}
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `message` | string | ✅ | Query content (≤10000 chars) |
| `sessionId` | string | ✅ | Session ID (for multi-turn conversations) |
| `domainId` | string | | Domain extension ID |
| `maxResults` | int | | Number of retrieval results, default 5 |
| `metadata` | object | | Extended metadata |

**Response:**

```json
{
  "answer": "Spring AI is an AI application framework in the Spring ecosystem...",
  "sessionId": "session-001",
  "sources": [
    {
      "documentId": 1,
      "title": "Introduction to Spring AI",
      "score": 0.92,
      "chunk": "Spring AI provides ChatClient..."
    }
  ]
}
```

---

### `POST /api/v1/rag/chat/stream`

SSE streaming Q&A, returns answer chunks progressively.

**Request body:** Same as `/ask`.

**Response:** `text/event-stream`

```
data: {"chunk": "Spring AI is"}

data: {"chunk": " an AI framework"}

event: done
data: [DONE]
```

**curl example:**

```bash
curl -N -X POST http://localhost:8080/api/v1/rag/chat/stream \
  -H "Content-Type: application/json" \
  -d '{"message": "What is RAG?", "sessionId": "s1"}'
```

---

### `GET /api/v1/rag/chat/history/{sessionId}`

Query chat history for a session.

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `limit` | int | 50 | Number of records to return |

**Response:** `List<Map<String, Object>>`

```json
[
  {
    "id": 1,
    "session_id": "s1",
    "user_message": "What is RAG?",
    "ai_response": "RAG is Retrieval-Augmented Generation...",
    "created_at": "2026-04-02T16:00:00Z"
  }
]
```

---

### `DELETE /api/v1/rag/chat/history/{sessionId}`

Clear chat history for a session (only affects `rag_chat_history` table, not `spring_ai_chat_memory`).

**Response:**

```json
{
  "message": "Session history cleared",
  "sessionId": "s1",
  "deletedCount": 10
}
```

---

### `GET /api/v1/rag/chat/export/{sessionId}`

Export chat conversation history as a downloadable file.

| Parameter | Type | Location | Description |
|-----------|------|---------|-------------|
| `format` | string | query | `json` or `md` (default: `json`) |
| `limit` | int | query | Max messages to export (default: 50) |

**Response:** Binary file download with `Content-Type: application/octet-stream` and `Content-Disposition: attachment; filename="conversation-{sessionId}.{format}"`

**JSON format response body:**
```json
{
  "conversationId": "s1",
  "exportedAt": "2026-04-05T12:00:00Z",
  "messageCount": 10,
  "messages": [
    { "role": "user", "content": "Hello", "timestamp": "..." },
    { "role": "assistant", "content": "Hi!", "sources": [], "timestamp": "..." }
  ]
}
```

**Markdown format response body:**
```markdown
# Conversation: s1
Exported: 2026-04-05

---

## User (2026-04-05T10:00:00)
Hello

## Assistant (2026-04-05T10:00:01)
Hi!
```

---

## Search — Direct Retrieval

> Does not go through LLM generation; used for debugging and previewing retrieval results.

### `GET /api/v1/rag/search`

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `query` | string | ✅ | Search query text |
| `limit` | int | 10 | Number of results to return |
| `useHybrid` | bool | true | Use hybrid search |
| `vectorWeight` | double | 0.5 | Vector search weight |
| `fulltextWeight` | double | 0.5 | Full-text search weight |

**Response:**

```json
{
  "results": [
    {
      "documentId": 1,
      "title": "Introduction to Spring AI",
      "score": 0.92,
      "chunk": "Retrieved text snippet...",
      "metadata": {}
    }
  ],
  "total": 3,
  "query": "Spring AI"
}
```

---

### `POST /api/v1/rag/search`

Submit more complex retrieval configuration via request body.

**Request body:**

```json
{
  "query": "Spring AI",
  "documentIds": [1, 2, 3],
  "config": {
    "maxResults": 10,
    "useHybridSearch": true,
    "vectorWeight": 0.6,
    "fulltextWeight": 0.4,
    "minScore": 0.3
  }
}
```

---

## Documents — Document Management

### `POST /api/v1/rag/documents`

Create a document.

```json
{
  "title": "Introduction to Spring AI",
  "content": "Spring AI is...",
  "source": "manual",
  "documentType": "text",
  "metadata": {}
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `title` | string | ✅ | Document title |
| `content` | string | ✅ | Document content |
| `source` | string | | Source identifier |
| `documentType` | string | | Document type |
| `metadata` | object | | Extended metadata |

---

### `GET /api/v1/rag/documents`

Paginated document query.

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `page` | int | 0 | Page number |
| `size` | int | 20 | Page size |

---

### `GET /api/v1/rag/documents/{id}`

Get a single document by ID.

---

### `DELETE /api/v1/rag/documents/{id}`

Delete a document.

---

### `GET /api/v1/rag/documents/stats`

Get document statistics (total count, embedded count, etc.).

---

### `POST /api/v1/rag/documents/{id}/embed`

Generate embedding vectors for a specified document.

---

### `POST /api/v1/rag/documents/{id}/embed/vs`

Generate embedding vectors for a document via VectorStore.

---

### `POST /api/v1/rag/documents/batch`

Batch create documents (save only, no embedding). Follow up with `/batch/embed` to vectorize.

```json
{
  "documents": [
    { "title": "doc1", "content": "content 1" },
    { "title": "doc2", "content": "content 2" }
  ]
}
```

> **Tip:** To create and embed in one step, use `POST /documents/batch` with `embed=true` parameter instead.

---

### `DELETE /api/v1/rag/documents/batch`

Batch delete documents.

```json
{
  "documentIds": [1, 2, 3]
}
```

---

### `POST /api/v1/rag/documents/batch/embed`

Batch embed documents (documents must already exist).

```json
{
  "documentIds": [1, 2, 3]
}
```

### `POST /api/v1/rag/documents/batch/embed/stream`

Batch embed documents via SSE streaming with real-time progress events.

**SSE Events:**
- `progress` — `BatchEmbedProgressEvent` with current document index, total docs, phase (PREPARING/CHUNKING/EMBEDDING/STORING/COMPLETED/FAILED), success/cached/failed counts
- `done` — Final confirmation with total count and status
- `error` — Error details (on validation failure)

**Request body:**
```json
{
  "ids": [1, 2, 3]
}
```

**Example SSE progress event:**
```json
{
  "currentDocIndex": 2,
  "totalDocs": 10,
  "currentDocId": 42,
  "phase": "EMBEDDING",
  "current": 5,
  "total": 10,
  "message": "Document 3/10: Generating embedding for chunk 5/10",
  "successCount": 1,
  "failedCount": 0,
  "cachedCount": 1
}
```

---

### `POST /api/v1/rag/documents/upload`

Upload text files and embed in one step. Suitable for direct file submission from frontend.

**Content-Type:** `multipart/form-data`

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `files` | MultipartFile[] | ✅ | File list (max 100) |
| `collectionId` | Long | No | Target collection ID |
| `force` | boolean | No | `true` = force re-embed |

**Supported file types:** txt / md / json / xml / html / csv / log

**Response:**

```json
{
  "processed": 10,
  "success": 10,
  "failed": 0,
  "results": [
    {
      "filename": "Product Manual.txt",
      "documentId": 1,
      "title": "Product Manual",
      "embedded": true,
      "chunks": 5,
      "error": null
    }
  ]
}
```

---

### `GET /api/v1/rag/documents/{id}/versions`

Get document version history (recorded automatically when content_hash changes, newest first).

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `page` | int | 0 | Page number |
| `size` | int | 20 | Page size |

**Response:**

```json
{
  "documentId": 1,
  "totalVersions": 5,
  "page": 0,
  "size": 20,
  "versions": [
    {
      "versionNumber": 5,
      "contentHash": "a1b2c3...",
      "title": "Introduction to Spring AI",
      "size": 2048,
      "changeType": "CONTENT_CHANGED",
      "createdAt": "2026-04-03T00:30:00Z"
    }
  ]
}
```

---

### `GET /api/v1/rag/documents/{id}/versions/{versionNumber}`

Get a specific version of a document (includes content snapshot).

**Response:**

```json
{
  "versionNumber": 3,
  "contentHash": "d4e5f6...",
  "title": "Introduction to Spring AI",
  "content": "Full content of version 3...",
  "size": 1024,
  "changeType": "INITIAL",
  "createdAt": "2026-04-02T10:00:00Z"
}
```

---

## Collections — Knowledge Base Management

### `POST /api/v1/rag/collections`

Create a collection.

```json
{
  "name": "Medical Knowledge Base",
  "description": "Medical domain document collection",
  "embeddingModel": "BAAI/bge-m3",
  "dimensions": 1024,
  "enabled": true,
  "metadata": {}
}
```

---

### `GET /api/v1/rag/collections`

Paginated collection query.

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `page` | int | 0 | Page number |
| `size` | int | 20 | Page size |
| `keyword` | string | | Search keyword |

---

### `GET /api/v1/rag/collections/{id}`

Get collection details.

---

### `PUT /api/v1/rag/collections/{id}`

Update a collection.

---

### `DELETE /api/v1/rag/collections/{id}`

Delete a collection.

---

### `GET /api/v1/rag/collections/{id}/documents`

Get documents in a collection.

---

### `POST /api/v1/rag/collections/{id}/documents`

Add documents to a collection.

```json
{
  "documentIds": [1, 2, 3]
}
```

---

### `GET /api/v1/rag/collections/{id}/export`

Export a collection and its documents as JSON.

**Response:**

```json
{
  "name": "Medical Knowledge Base",
  "description": "Medical domain document collection",
  "embeddingModel": "BAAI/bge-m3",
  "dimensions": 1024,
  "enabled": true,
  "metadata": {},
  "documents": [
    {
      "title": "doc1",
      "content": "...",
      "source": "manual",
      "documentType": "text",
      "metadata": {},
      "size": 1024
    }
  ],
  "exportedAt": "2026-04-03T00:00:00Z",
  "documentCount": 10
}
```

---

### `POST /api/v1/rag/collections/import`

Import and create a new collection with documents from exported JSON data.

**Request body:** Use the JSON data returned by the `/export` endpoint.

**Response:**

```json
{
  "id": 5,
  "name": "Medical Knowledge Base",
  "importedDocuments": 10,
  "message": "Collection imported successfully"
}
```

---

## Evaluation — Retrieval Evaluation

### `POST /api/v1/rag/evaluation/evaluate`

Execute a single retrieval evaluation.

---

### `POST /api/v1/rag/evaluation/batch`

Execute batch evaluations.

---

### `GET /api/v1/rag/evaluation/metrics/calculate`

Calculate retrieval metrics (Precision, Recall, MRR, etc.).

---

### `GET /api/v1/rag/evaluation/report`

Get evaluation report.

---

### `GET /api/v1/rag/evaluation/history`

Get evaluation history.

---

### `GET /api/v1/rag/evaluation/metrics/aggregated`

Get aggregated metrics.

---

### `POST /api/v1/rag/evaluation/feedback`

Submit user feedback.

```json
{
  "query": "What is RAG?",
  "feedbackType": "helpful",
  "comment": "Accurate answer",
  "rating": 5
}
```

---

### `GET /api/v1/rag/evaluation/feedback/stats`

Get feedback statistics.

---

### `GET /api/v1/rag/evaluation/feedback/history`

Get feedback history.

---

### `GET /api/v1/rag/evaluation/feedback/type/{feedbackType}`

Query feedback by type.

---

## A/B Tests — Experiment Management

### `POST /api/v1/rag/ab/experiments`

Create an A/B experiment.

### `PUT /api/v1/rag/ab/experiments/{id}`

Update an experiment.

### `POST /api/v1/rag/ab/experiments/{id}/start`

Start an experiment.

### `POST /api/v1/rag/ab/experiments/{id}/pause`

Pause an experiment.

### `POST /api/v1/rag/ab/experiments/{id}/stop`

Stop an experiment.

### `GET /api/v1/rag/ab/experiments/running`

Get running experiments.

### `GET /api/v1/rag/ab/experiments/{id}/variant`

Get experiment variant assignment.

### `POST /api/v1/rag/ab/experiments/{id}/results`

Record experiment results.

### `GET /api/v1/rag/ab/experiments/{id}/analysis`

Get experiment analysis report.

### `GET /api/v1/rag/ab/experiments/{id}/results`

Get experiment results list.

---

## Alerts — Monitoring & Alerting

### `GET /api/v1/rag/alerts/active`

Get active alerts.

### `GET /api/v1/rag/alerts/history`

Get alert history.

### `GET /api/v1/rag/alerts/stats`

Get alert statistics.

### `POST /api/v1/rag/alerts/{alertId}/resolve`

Resolve an alert.

### `POST /api/v1/rag/alerts/silence`

Silence an alert.

### `POST /api/v1/rag/alerts/fire`

Manually trigger an alert (for testing).

### `GET /api/v1/rag/alerts/slos`

Get all SLO definitions.

### `GET /api/v1/rag/alerts/slos/{sloName}`

Get details of a specific SLO.

### `POST /api/v1/rag/alerts/slos`

Create a new SLO configuration.

**Request body:**
```json
{
  "sloName": "latency_p99",
  "sloType": "LATENCY",
  "targetValue": 200.0,
  "unit": "ms",
  "description": "P99 latency should be under 200ms",
  "enabled": true
}
```

**Response:** `201 Created` with created SLO config.

### `PUT /api/v1/rag/alerts/slos/configs/{sloName}`

Update an existing SLO configuration.

**Request body:** Same as POST.

**Response:** `200 OK` with updated SLO config, or `404 Not Found`.

### `DELETE /api/v1/rag/alerts/slos/configs/{sloName}`

Delete an SLO configuration.

**Response:** `204 No Content`, or `404 Not Found`.

### `GET /api/v1/rag/alerts/slos/configs`

List all SLO configurations.

**Response:** Array of SLO config objects.

### `GET /api/v1/rag/alerts/silence-schedules`

List all silence schedules.

### `POST /api/v1/rag/alerts/silence-schedules`

Create a new silence schedule.

**Request body:**
```json
{
  "name": "weekend-maintenance",
  "alertKey": "high-latency",
  "silenceType": "RECURRING",
  "startTime": "2026-04-10T02:00:00+08:00",
  "endTime": "2026-04-10T04:00:00+08:00",
  "description": "Scheduled maintenance window",
  "enabled": true
}
```

**Response:** `201 Created` with created schedule.

### `GET /api/v1/rag/alerts/silence-schedules/{name}`

Get a specific silence schedule.

### `PUT /api/v1/rag/alerts/silence-schedules/{name}`

Update a silence schedule.

### `DELETE /api/v1/rag/alerts/silence-schedules/{name}`

Delete a silence schedule.

**Response:** `204 No Content`.

---

## Health — Health Checks

### `GET /api/v1/rag/health`

Service health check.

**Response:**

```json
{
  "status": "UP",
  "timestamp": "2026-04-02T16:00:00Z",
  "components": {
    "database": "UP",
    "vectorStore": "UP",
    "embeddingModel": "UP"
  }
}
```

---

## Cache — Cache Monitoring

### `GET /api/v1/rag/cache/stats`

Get embedding cache statistics.

**Response:**

```json
{
  "hitCount": 1523,
  "missCount": 478,
  "hitRate": 0.761,
  "totalRequests": 2001,
  "cacheSize": 342,
  "timestamp": "2026-04-03T10:00:00Z"
}
```

### `DELETE /api/v1/rag/cache/invalidate`

Admin endpoint: clear the embedding Caffeine cache, forcing subsequent embedding requests to call the remote API again.

**Response:**

```json
{
  "cleared": 42,
  "message": "Cache invalidated"
}
```

**Field descriptions:**

| Field | Type | Description |
|-------|------|-------------|
| `cleared` | int | Number of cache entries cleared |
| `message` | string | Human-readable status |

---

## Metrics — RAG Metrics Monitoring

### `GET /api/v1/rag/metrics`

Get RAG service key metrics summary (request count, success rate, retrieval result count, token consumption).

**Response:**

```json
{
  "totalRequests": 1523,
  "successfulRequests": 1498,
  "failedRequests": 25,
  "successRate": 0.984,
  "totalRetrievalResults": 8764,
  "totalLlmTokens": 245321
}
```

**Field descriptions:**

| Field | Type | Description |
|-------|------|-------------|
| `totalRequests` | long | Total requests since service startup |
| `successfulRequests` | long | Successful requests (LLM returned normally) |
| `failedRequests` | long | Failed requests (LLM call exception) |
| `successRate` | double | Success rate (successful/total) |
| `totalRetrievalResults` | long | Cumulative retrieval result count |
| `totalLlmTokens` | long | Cumulative LLM token consumption |

---

### `GET /api/v1/rag/metrics/slow-queries`

Get slow query statistics from the database connection pool (HikariCP). Returns aggregated stats and recent slow query records.

Requires `rag.slow-query.enabled = true`.

**Response:**

```json
{
  "totalQueryCount": 15234,
  "totalQueryDurationMs": 4567890,
  "slowQueryCount": 12,
  "thresholdMs": 1000,
  "averageQueryDurationMs": 300,
  "recentSlowQueries": [
    {
      "timestampMs": 1712123456789,
      "sql": "SELECT * FROM rag_embeddings WHERE ...",
      "durationMs": 2345
    }
  ]
}
```

**Field descriptions:**

| Field | Type | Description |
|-------|------|-------------|
| `totalQueryCount` | long | Total query count since service startup |
| `totalQueryDurationMs` | long | Total query duration in ms |
| `slowQueryCount` | long | Number of queries exceeding threshold |
| `thresholdMs` | long | Configured slow query threshold in ms |
| `averageQueryDurationMs` | long | Average query duration in ms |
| `recentSlowQueries` | array | Last N slow query records (configurable via `rag.slow-query.keep-count`) |

---

### `GET /api/v1/rag/metrics/slo`

Get API SLO compliance metrics per endpoint using a sliding time window. Tracks p50/p95/p99 latency and compliance percentage against per-endpoint thresholds.

Requires `rag.slo.enabled = true`.

**Response:**

```json
{
  "enabled": true,
  "windowSeconds": 300,
  "endpoints": [
    {
      "endpoint": "rag.search.post",
      "method": "POST",
      "thresholdMs": 500,
      "compliancePercent": 98.5,
      "requestCount": 1523,
      "sloCount": 1500,
      "breachCount": 23,
      "stats": {
        "p50Ms": 45.2,
        "p95Ms": 380.5,
        "p99Ms": 490.0,
        "minMs": 12.3,
        "maxMs": 1200.5,
        "avgMs": 87.4
      }
    }
  ]
}
```

**Field descriptions:**

| Field | Type | Description |
|-------|------|-------------|
| `enabled` | boolean | Whether SLO tracking is active |
| `windowSeconds` | int | Sliding time window size in seconds |
| `endpoints[].endpoint` | string | Endpoint identifier (e.g., `rag.search.post`) |
| `endpoints[].thresholdMs` | long | SLO threshold in milliseconds |
| `endpoints[].compliancePercent` | double | Percentage of requests within SLO (0–100) |
| `endpoints[].requestCount` | int | Total requests in the window |
| `endpoints[].sloCount` | int | Requests meeting SLO |
| `endpoints[].breachCount` | int | Requests breaching SLO |

---

## Models — Multi-Model Management

### `GET /api/v1/rag/models`

Get all registered models and their routing status.

**Response:**

```json
{
  "multiModelEnabled": true,
  "defaultProvider": "openai",
  "availableProviders": ["openai", "minimax"],
  "fallbackChain": ["openai", "minimax"],
  "models": [
    {
      "provider": "openai",
      "available": true,
      "displayName": "OpenAI (DeepSeek/Compatible)",
      "className": "OpenAiChatModel"
    },
    {
      "provider": "minimax",
      "available": true,
      "displayName": "MiniMax",
      "className": "MiniMaxChatModel"
    }
  ]
}
```

### `GET /api/v1/rag/models/{provider}`

Get details of a specific provider's model.

**Path parameters:**

| Parameter | Type | Description |
|-----------|------|-------------|
| `provider` | string | Provider identifier (openai / anthropic / minimax) |

**Response (provider exists):**

```json
{
  "provider": "openai",
  "available": true,
  "displayName": "OpenAI (DeepSeek/Compatible)",
  "className": "OpenAiChatModel"
}
```

**Response (provider not found):** `404 Not Found`

### `POST /api/v1/rag/models/compare`

Compare responses from multiple models in parallel, for model effectiveness comparison.

**Request body:**

```json
{
  "query": "Explain the basic principles of quantum computing",
  "providers": ["openai", "minimax"],
  "timeoutSeconds": 30
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `query` | string | ✅ | Query text |
| `providers` | string[] | ✅ | List of providers to compare |
| `timeoutSeconds` | int | ❌ | Per-model timeout in seconds (default 30) |

**Response:**

```json
{
  "query": "Explain the basic principles of quantum computing",
  "providers": ["openai", "minimax"],
  "results": [
    {
      "modelName": "openai",
      "success": true,
      "response": "Quantum computing is...",
      "latencyMs": 1200,
      "promptTokens": 50,
      "completionTokens": 180,
      "totalTokens": 230,
      "error": null
    },
    {
      "modelName": "minimax",
      "success": true,
      "response": "The core of quantum computing is...",
      "latencyMs": 950,
      "promptTokens": 50,
      "completionTokens": 165,
      "totalTokens": 215,
      "error": null
    }
  ]
}
```

### `GET /api/v1/rag/metrics/models`

Get per-model invocation metrics (call count, error rate).

**Response:**

```json
{
  "multiModelEnabled": true,
  "models": [
    {
      "provider": "openai",
      "calls": 1523,
      "errors": 25,
      "errorRate": 0.016,
      "displayName": "OpenAI (DeepSeek/Compatible)"
    },
    {
      "provider": "minimax",
      "calls": 234,
      "errors": 3,
      "errorRate": 0.013,
      "displayName": "MiniMax"
    }
  ]
}
```

---

## Client Errors — WebUI Error Reporting

### `POST /api/v1/rag/client-errors`

Receive and record client-side errors from the WebUI for server-side aggregation and analysis. Used by the WebUI ErrorBoundary component.

**Request body:**

```json
{
  "errorType": "Error",
  "errorMessage": "Cannot read properties of undefined",
  "stackTrace": "TypeError: Cannot read properties of undefined\n    at Chat.render (Chat.tsx:42:10)",
  "componentStack": "at Chat (Chat.tsx:38)\nat App (App.tsx:12)",
  "pageUrl": "/webui/chat",
  "sessionId": "sess-abc123",
  "userId": null
}
```

**Field descriptions:**

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `errorType` | string | Yes | Error type (e.g., `Error`, `TypeError`, `ReferenceError`) |
| `errorMessage` | string | Yes | Error message text |
| `stackTrace` | string | No | JavaScript stack trace (max 8192 chars) |
| `componentStack` | string | No | React component stack trace (max 4096 chars) |
| `pageUrl` | string | No | Page URL where error occurred (max 512 chars) |
| `sessionId` | string | No | WebUI session identifier (max 64 chars) |
| `userId` | string | No | Authenticated user ID (max 64 chars) |

**Response:** `202 Accepted` (empty body)

---

### `GET /api/v1/rag/client-errors/count`

Get the total number of recorded client-side errors.

**Response:**

```json
{
  "type": "about:blank",
  "title": "Total client errors: 42",
  "status": 200
}
```

**Note:** The response uses RFC 7807 format with the count embedded in the `title` field.
