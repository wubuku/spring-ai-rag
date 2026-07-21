📖 [English](rest-api.md) · 📖 [中文](rest-api-zh-CN.md)

---

# REST API 参考

📖 [English](rest-api.md) · 📖 [中文](rest-api-zh-CN.md)

> 启动后可通过 `/swagger-ui.html` 访问 Swagger UI。
>
> 基础路径：`/api/v1/rag`

---

## 通用约定

### 认证

启用 API Key 认证（`rag.security.enabled=true`）后，请求需携带以下任一凭据：

```
X-API-Key: your-api-key          # Header（推荐）
?apiKey=your-api-key            # Query 参数（SSE / EventSource）
```

角色权限：

| Key 角色 | `/api-keys` 列表 | `/api-keys` 创建 | `/api-keys` 删除 |
|----------|------------------|------------------|------------------|
| ADMIN | ✅ 全部 | ✅ 任意角色 | ✅ 任意 Key |
| NORMAL | ❌ 403 | ✅ 仅 NORMAL（自助） | ❌ 403 |

数据库 Key 可通过 `allowedCollectionIds` 限制集合访问：

- `null` 或 `[]`：可访问全部集合
- 非空列表：Search、Chat、Collection、Document、上传、PDF-to-RAG 均限制在这些集合内
- 显式请求范围外集合返回 `403`
- 受限 Key 未提供集合过滤时，检索自动收敛到其允许列表
- ADMIN 与旧版静态 `rag.security.api-key` 保持全库权限
- 受限 NORMAL Key 不能创建权限更宽的子 Key；轮换会保留原集合权限

### API 密钥管理

#### `GET /api/v1/rag/api-keys`

ADMIN 列出全部 Key。响应不会包含原始密钥：

```json
[{
  "keyId": "rag_k_abc123",
  "name": "Production Server",
  "role": "NORMAL",
  "allowedCollectionIds": [1, 2],
  "enabled": true,
  "createdAt": "2026-04-15T00:00:00",
  "lastUsedAt": null,
  "expiresAt": null
}]
```

#### `POST /api/v1/rag/api-keys`

创建 Key。`expiresAt` 与 `allowedCollectionIds` 均可省略；省略或传空集合表示全库权限。

```json
{
  "name": "My API Key",
  "expiresAt": "2027-01-01T00:00:00",
  "allowedCollectionIds": [1, 2]
}
```

原始密钥仅在 `201 Created` 响应中返回一次：

```json
{
  "keyId": "rag_k_xyz789",
  "rawKey": "rag_sk_...",
  "name": "My API Key",
  "allowedCollectionIds": [1, 2],
  "expiresAt": "2027-01-01T00:00:00"
}
```

#### `DELETE /api/v1/rag/api-keys/{keyId}`

ADMIN 撤销 Key；最后一个 ADMIN Key 不允许删除。

### API 限流

启用 `rag.rate-limit.enabled` 后，所有 API 请求受滑动窗口限流约束。

支持两种策略（`rag.rate-limit.strategy`）：
- `ip`：按客户端 IP 限流
- `api-key`：按 API Key 限流；未提供 Key 时回退到 IP，分级限额使用 `rag.rate-limit.key-limits`

**正常响应中的限流 Header：**

| Header | 说明 |
|--------|------|
| `X-RateLimit-Limit` | 每分钟最大请求数 |
| `X-RateLimit-Remaining` | 当前窗口剩余额度 |

**超限响应：**

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

## Chat — RAG 问答

### `POST /api/v1/rag/chat/ask`

非流式 RAG 问答，返回完整答案。

**请求体：**

```json
{
  "message": "什么是 Spring AI？",
  "sessionId": "session-001",
  "domainId": "medical",
  "model": "openrouter/xiaomi/mimo-v2-pro",
  "collectionIds": [1, 2],
  "documentIds": [10, 20],
  "maxResults": 5,
  "useHybridSearch": true,
  "useRerank": true,
  "metadata": {}
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `message` | string | ✅ | 问题内容（不超过 10000 字符） |
| `sessionId` | string | | 会话 ID，最长 36 字符；省略时自动生成 |
| `domainId` | string | | 领域扩展 ID |
| `model` | string | | `GET /rag/models` 返回的运行时模型引用；省略时使用默认链 |
| `collectionIds` | long[] | | 仅检索这些集合 |
| `documentIds` | long[] | | 仅检索这些文档；与集合范围取交集 |
| `maxResults` | int | | 检索结果数，默认 5 |
| `useHybridSearch` | boolean | | 是否启用向量 + 全文检索，默认 true |
| `useRerank` | boolean | | 是否启用重排序，默认 true |
| `metadata` | object | | 扩展元数据 |

**响应：**

```json
{
  "answer": "Spring AI 是 Spring 生态的 AI 应用框架……",
  "sessionId": "session-001",
  "sources": [
    {
      "documentId": 1,
      "title": "Spring AI 介绍",
      "score": 0.92,
      "chunk": "Spring AI provides ChatClient..."
    }
  ]
}
```

---

### `POST /api/v1/rag/chat/stream`

SSE 流式问答，逐块返回答案。

**请求体：** 与 `/ask` 相同。

`sessionId` 同样限制为最长 36 字符；超限请求在进入 Chat Memory 前返回 `400 VALIDATION_FAILED`。

**响应：** `text/event-stream`

```
data: {"choices":[{"delta":{"content":"Spring AI 是"}}]}

data: {"choices":[{"delta":{"content":" Spring 生态的 AI 框架"}}]}

event:done
data:{"traceId":"...","status":"complete"}
```

**curl 示例：**

```bash
curl -N -X POST http://localhost:8081/api/v1/rag/chat/stream \
  -H "Content-Type: application/json" \
  -d '{"message": "什么是 RAG？", "sessionId": "s1", "model": "openrouter/xiaomi/mimo-v2-pro"}'
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

## Models — 运行时选模

### `GET /api/v1/rag/models`

获取可写入 `ChatRequest.model` 的 provider/model 引用。

**响应：**

```json
{
  "multiModelEnabled": true,
  "defaultProvider": "minimax",
  "defaultModel": "minimax/MiniMax-M2.7",
  "availableProviders": ["openrouter", "minimax"],
  "fallbackChain": ["openrouter/xiaomi/mimo-v2-pro"],
  "models": [
    {
      "ref": "openrouter/xiaomi/mimo-v2-pro",
      "provider": "openrouter",
      "providerName": "OpenRouter",
      "modelId": "xiaomi/mimo-v2-pro",
      "name": "MiMo V2 Pro",
      "apiType": "openai-completions",
      "available": true,
      "reasoning": false,
      "contextWindow": 600000,
      "maxTokens": 32000,
      "source": "configured"
    },
    {
      "ref": "minimax/MiniMax-M2.7",
      "provider": "minimax",
      "providerName": "MiniMax",
      "modelId": "MiniMax-M2.7",
      "name": "MiniMax M2.7",
      "apiType": "anthropic-messages",
      "available": true,
      "reasoning": false,
      "contextWindow": 200000,
      "maxTokens": 8192,
      "source": "configured"
    }
  ]
}
```

已配置但缺少凭据的模型仍会返回，并带有 `available: false` 和
`unavailableReason`。

### `GET /api/v1/rag/models/{provider}`

获取 provider 摘要及其模型级条目。

**路径参数：**

| 参数 | 类型 | 说明 |
|------|------|------|
| `provider` | string | Provider 标识，例如 `openrouter` 或 `minimax` |

**Provider 存在时：**

```json
{
  "available": true,
  "details": {
    "provider": "openrouter",
    "available": true,
    "displayName": "OpenRouter",
    "models": [
      {
        "ref": "openrouter/xiaomi/mimo-v2-pro",
        "modelId": "xiaomi/mimo-v2-pro",
        "available": true
      }
    ]
  }
}
```

**Provider 不存在：** `404 Not Found`

### `POST /api/v1/rag/models/compare`

并行比较多个模型引用的响应。

**请求体：**

```json
{
  "query": "解释量子计算的基本原理",
  "providers": [
    "openrouter/xiaomi/mimo-v2-pro",
    "minimax/MiniMax-M2.7"
  ],
  "timeoutSeconds": 30
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `query` | string | ✅ | 问题文本 |
| `providers` | string[] | ✅ | 要比较的 provider 或 `provider/model` 引用 |
| `timeoutSeconds` | int | ❌ | 单模型超时秒数，默认 30 |

**响应：**

```json
{
  "query": "解释量子计算的基本原理",
  "providers": [
    "openrouter/xiaomi/mimo-v2-pro",
    "minimax/MiniMax-M2.7"
  ],
  "results": [
    {
      "modelName": "openrouter/xiaomi/mimo-v2-pro",
      "success": true,
      "response": "Quantum computing is...",
      "latencyMs": 1200,
      "promptTokens": 50,
      "completionTokens": 180,
      "totalTokens": 230,
      "error": null
    },
    {
      "modelName": "minimax/MiniMax-M2.7",
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

获取每个模型的调用次数和错误率。

**响应：**

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
