# REST API 参考

> 启动后可访问 Swagger UI：`/swagger-ui.html`
>
> 基础路径：`/api/v1/rag`

---

## 通用说明

### 认证

启用 API Key 认证时，请求头需携带：

```
X-API-Key: your-api-key
```

配置项：`rag.security.api-keys`（逗号分隔多个 key）。

### 限流

启用 `rag.rate-limit.enabled` 后，所有 API 请求受滑动窗口限流。

支持两种策略（`rag.rate-limit.strategy`）：
- `ip`：按客户端 IP 限流
- `api-key`：按 API Key 限流（无 Key 回退 IP）；`rag.rate-limit.key-limits` 可配置不同 Key 的分级限额

**限流响应头（正常请求）：**

| 响应头 | 说明 |
|--------|------|
| `X-RateLimit-Limit` | 每分钟最大请求数 |
| `X-RateLimit-Remaining` | 当前窗口剩余配额 |

**超限响应：**

```
HTTP/1.1 429 Too Many Requests
Retry-After: 60
X-RateLimit-Limit: 100
X-RateLimit-Remaining: 0
```

### 错误响应（RFC 7807 Problem Detail）

所有错误响应遵循 [RFC 7807](https://datatracker.ietf.org/doc/html/rfc7807) `application/problem+json` 格式：

```json
{
  "type": "about:blank",
  "title": "Bad Request",
  "status": 400,
  "detail": "消息内容不能为空",
  "instance": "/api/v1/rag/chat/ask"
}
```

| 字段 | 说明 |
|------|------|
| `type` | 问题类型 URI（默认 `about:blank`） |
| `title` | HTTP 状态文本 |
| `status` | HTTP 状态码 |
| `detail` | 具体错误描述 |
| `instance` | 出错的请求路径 |

**参数校验错误**（400）会合并多个字段错误：

```json
{
  "type": "about:blank",
  "title": "Bad Request",
  "status": 400,
  "detail": "message: 消息内容不能为空; sessionId: 会话ID不能为空",
  "instance": "/api/v1/rag/chat/ask"
}
```

---

## Chat — RAG 问答

### `POST /api/v1/rag/chat/ask`

非流式 RAG 问答，返回完整回答。

**请求体：**

```json
{
  "message": "什么是 Spring AI？",
  "sessionId": "session-001",
  "domainId": "medical",
  "maxResults": 5,
  "metadata": {}
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `message` | string | ✅ | 问题内容（≤10000 字符） |
| `sessionId` | string | ✅ | 会话 ID（用于多轮对话） |
| `domainId` | string | | 领域扩展 ID |
| `maxResults` | int | | 检索结果数量，默认 5 |
| `metadata` | object | | 扩展元数据 |

**响应：**

```json
{
  "answer": "Spring AI 是 Spring 生态的 AI 应用框架...",
  "sessionId": "session-001",
  "sources": [
    {
      "documentId": 1,
      "title": "Spring AI 介绍",
      "score": 0.92,
      "chunk": "Spring AI 提供了 ChatClient..."
    }
  ]
}
```

---

### `POST /api/v1/rag/chat/stream`

SSE 流式问答，逐块返回回答。

**请求体：** 同 `/ask`。

**响应：** `text/event-stream`

```
data: {"chunk": "Spring AI 是"}

data: {"chunk": "一个 AI 框架"}

event: done
data: [DONE]
```

**curl 示例：**

```bash
curl -N -X POST http://localhost:8080/api/v1/rag/chat/stream \
  -H "Content-Type: application/json" \
  -d '{"message": "RAG 是什么？", "sessionId": "s1"}'
```

---

### `GET /api/v1/rag/chat/history/{sessionId}`

查询会话历史。

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `limit` | int | 50 | 返回条数 |

**响应：** `List<Map<String, Object>>`

```json
[
  {
    "id": 1,
    "session_id": "s1",
    "user_message": "什么是 RAG？",
    "ai_response": "RAG 是检索增强生成...",
    "created_at": "2026-04-02T16:00:00Z"
  }
]
```

---

### `DELETE /api/v1/rag/chat/history/{sessionId}`

清空会话历史（仅 `rag_chat_history` 表，不影响 `spring_ai_chat_memory`）。

**响应：**

```json
{
  "message": "会话历史已清空",
  "sessionId": "s1",
  "deletedCount": 10
}
```

---

## Search — 直接检索

> 不经过 LLM 生成，用于调试和预览检索效果。

### `GET /api/v1/rag/search`

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `query` | string | ✅ | 查询文本 |
| `limit` | int | 10 | 返回数量 |
| `useHybrid` | bool | true | 混合检索 |
| `vectorWeight` | double | 0.5 | 向量权重 |
| `fulltextWeight` | double | 0.5 | 全文权重 |

**响应：**

```json
{
  "results": [
    {
      "documentId": 1,
      "title": "Spring AI 介绍",
      "score": 0.92,
      "chunk": "检索到的文本片段...",
      "metadata": {}
    }
  ],
  "total": 3,
  "query": "Spring AI"
}
```

---

### `POST /api/v1/rag/search`

通过请求体提交更复杂的检索配置。

**请求体：**

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

## Documents — 文档管理

### `POST /api/v1/rag/documents`

创建文档。

```json
{
  "title": "Spring AI 介绍",
  "content": "Spring AI 是...",
  "source": "manual",
  "documentType": "text",
  "metadata": {}
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `title` | string | ✅ | 文档标题 |
| `content` | string | ✅ | 文档内容 |
| `source` | string | | 来源标识 |
| `documentType` | string | | 文档类型 |
| `metadata` | object | | 扩展元数据 |

---

### `GET /api/v1/rag/documents`

分页查询文档。

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `page` | int | 0 | 页码 |
| `size` | int | 20 | 每页数量 |

---

### `GET /api/v1/rag/documents/{id}`

获取单个文档详情。

---

### `DELETE /api/v1/rag/documents/{id}`

删除文档。

---

### `GET /api/v1/rag/documents/stats`

获取文档统计信息（总数、已嵌入数量等）。

---

### `POST /api/v1/rag/documents/{id}/embed`

为指定文档生成嵌入向量。

---

### `POST /api/v1/rag/documents/{id}/embed/vs`

通过 VectorStore 为指定文档生成嵌入向量。

---

### `POST /api/v1/rag/documents/batch`

批量创建文档。

```json
{
  "documents": [
    { "title": "doc1", "content": "内容1" },
    { "title": "doc2", "content": "内容2" }
  ]
}
```

---

### `DELETE /api/v1/rag/documents/batch`

批量删除文档。

```json
{
  "documentIds": [1, 2, 3]
}
```

---

### `POST /api/v1/rag/documents/batch/embed`

批量嵌入文档。

```json
{
  "documentIds": [1, 2, 3]
}
```

---

### `GET /api/v1/rag/documents/{id}/versions`

获取文档版本历史（content_hash 变更自动记录，最新在前）。

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `page` | int | 0 | 页码 |
| `size` | int | 20 | 每页数量 |

**响应：**

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
      "title": "Spring AI 介绍",
      "size": 2048,
      "changeType": "CONTENT_CHANGED",
      "createdAt": "2026-04-03T00:30:00Z"
    }
  ]
}
```

---

### `GET /api/v1/rag/documents/{id}/versions/{versionNumber}`

获取文档指定版本详情（含内容快照）。

**响应：**

```json
{
  "versionNumber": 3,
  "contentHash": "d4e5f6...",
  "title": "Spring AI 介绍",
  "content": "版本 3 的完整内容...",
  "size": 1024,
  "changeType": "INITIAL",
  "createdAt": "2026-04-02T10:00:00Z"
}
```

---

## Collections — 知识库管理

### `POST /api/v1/rag/collections`

创建知识库集合。

```json
{
  "name": "医疗知识库",
  "description": "医疗领域文档集合",
  "embeddingModel": "BAAI/bge-m3",
  "dimensions": 1024,
  "enabled": true,
  "metadata": {}
}
```

---

### `GET /api/v1/rag/collections`

分页查询集合。

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `page` | int | 0 | 页码 |
| `size` | int | 20 | 每页数量 |
| `keyword` | string | | 搜索关键词 |

---

### `GET /api/v1/rag/collections/{id}`

获取集合详情。

---

### `PUT /api/v1/rag/collections/{id}`

更新集合。

---

### `DELETE /api/v1/rag/collections/{id}`

删除集合。

---

### `GET /api/v1/rag/collections/{id}/documents`

获取集合中的文档列表。

---

### `POST /api/v1/rag/collections/{id}/documents`

向集合添加文档。

```json
{
  "documentIds": [1, 2, 3]
}
```

---

### `GET /api/v1/rag/collections/{id}/export`

导出集合及其文档为 JSON。

**响应：**

```json
{
  "name": "医疗知识库",
  "description": "医疗领域文档集合",
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

从导出的 JSON 数据导入创建新集合及其文档。

**请求体：** 使用 `/export` 端点返回的 JSON 数据。

**响应：**

```json
{
  "id": 5,
  "name": "医疗知识库",
  "importedDocuments": 10,
  "message": "集合导入成功"
}
```

---

## Evaluation — 检索评估

### `POST /api/v1/rag/evaluation/evaluate`

执行单次检索评估。

---

### `POST /api/v1/rag/evaluation/batch`

批量执行评估。

---

### `GET /api/v1/rag/evaluation/metrics/calculate`

计算检索指标（Precision、Recall、MRR 等）。

---

### `GET /api/v1/rag/evaluation/report`

获取评估报告。

---

### `GET /api/v1/rag/evaluation/history`

获取评估历史。

---

### `GET /api/v1/rag/evaluation/metrics/aggregated`

获取聚合指标。

---

### `POST /api/v1/rag/evaluation/feedback`

提交用户反馈。

```json
{
  "query": "什么是 RAG？",
  "feedbackType": "helpful",
  "comment": "回答准确",
  "rating": 5
}
```

---

### `GET /api/v1/rag/evaluation/feedback/stats`

获取反馈统计。

---

### `GET /api/v1/rag/evaluation/feedback/history`

获取反馈历史。

---

### `GET /api/v1/rag/evaluation/feedback/type/{feedbackType}`

按类型查询反馈。

---

## A/B Tests — 实验管理

### `POST /api/v1/rag/ab/experiments`

创建 A/B 实验。

### `PUT /api/v1/rag/ab/experiments/{id}`

更新实验。

### `POST /api/v1/rag/ab/experiments/{id}/start`

启动实验。

### `POST /api/v1/rag/ab/experiments/{id}/pause`

暂停实验。

### `POST /api/v1/rag/ab/experiments/{id}/stop`

停止实验。

### `GET /api/v1/rag/ab/experiments/running`

获取正在运行的实验。

### `GET /api/v1/rag/ab/experiments/{id}/variant`

获取实验变体分配。

### `POST /api/v1/rag/ab/experiments/{id}/results`

记录实验结果。

### `GET /api/v1/rag/ab/experiments/{id}/analysis`

获取实验分析报告。

### `GET /api/v1/rag/ab/experiments/{id}/results`

获取实验结果列表。

---

## Alerts — 监控告警

### `GET /api/v1/rag/alerts/active`

获取活跃告警。

### `GET /api/v1/rag/alerts/history`

获取告警历史。

### `GET /api/v1/rag/alerts/stats`

获取告警统计。

### `POST /api/v1/rag/alerts/{alertId}/resolve`

解决告警。

### `POST /api/v1/rag/alerts/silence`

静默告警。

### `POST /api/v1/rag/alerts/fire`

手动触发告警（测试用）。

### `GET /api/v1/rag/alerts/slos`

获取所有 SLO 定义。

### `GET /api/v1/rag/alerts/slos/{sloName}`

获取指定 SLO 详情。

---

## Health — 健康检查

### `GET /api/v1/rag/health`

服务健康检查。

**响应：**

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

## Cache — 缓存监控

### `GET /api/v1/rag/cache/stats`

获取嵌入缓存统计信息。

**响应：**

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

## Metrics — RAG 指标监控

### `GET /api/v1/rag/metrics`

获取 RAG 服务关键指标汇总（请求数、成功率、检索结果数、Token 消耗）。

**响应：**

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

**字段说明：**

| 字段 | 类型 | 说明 |
|------|------|------|
| `totalRequests` | long | 自服务启动以来的总请求数 |
| `successfulRequests` | long | 成功请求数（LLM 正常返回） |
| `failedRequests` | long | 失败请求数（LLM 调用异常） |
| `successRate` | double | 成功率（successful/total） |
| `totalRetrievalResults` | long | 累计返回的检索结果数量 |
| `totalLlmTokens` | long | 累计 LLM 消耗 Token 数 |

---

## Models — 多模型管理

### `GET /api/v1/rag/models`

获取所有已注册模型列表及路由状态。

**响应：**

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
      "displayName": "OpenAI (DeepSeek/兼容)",
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

获取指定 provider 的模型详情。

**路径参数：**

| 参数 | 类型 | 说明 |
|------|------|------|
| `provider` | string | Provider 标识（openai / anthropic / minimax） |

**响应（provider 存在）：**

```json
{
  "provider": "openai",
  "available": true,
  "displayName": "OpenAI (DeepSeek/兼容)",
  "className": "OpenAiChatModel"
}
```

**响应（provider 不存在）：** `404 Not Found`

### `POST /api/v1/rag/models/compare`

并行对比多个模型的响应，用于模型效果对比。

**请求体：**

```json
{
  "query": "解释量子计算的基本原理",
  "providers": ["openai", "minimax"],
  "timeoutSeconds": 30
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `query` | string | ✅ | 查询文本 |
| `providers` | string[] | ✅ | 要对比的 provider 列表 |
| `timeoutSeconds` | int | ❌ | 单模型超时秒数（默认 30） |

**响应：**

```json
{
  "query": "解释量子计算的基本原理",
  "providers": ["openai", "minimax"],
  "results": [
    {
      "modelName": "openai",
      "success": true,
      "response": "量子计算是一种...",
      "latencyMs": 1200,
      "promptTokens": 50,
      "completionTokens": 180,
      "totalTokens": 230,
      "error": null
    },
    {
      "modelName": "minimax",
      "success": true,
      "response": "量子计算的核心是...",
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

获取各模型的调用指标（调用量、错误率）。

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
      "displayName": "OpenAI (DeepSeek/兼容)"
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
