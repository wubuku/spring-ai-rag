# SSE 流式协议

> 状态：**已实现** | 最近复核：2026-08-17
>
> Chat HTTP 请求与非流式响应见
> [REST API](rest-api.md#chat--rag-qa) /
> [中文 REST API](rest-api-zh-CN.md#chat--rag-问答)。

## 1. 协议边界

`POST /api/v1/rag/chat/stream` 返回 `text/event-stream`。文本增量沿用
OpenAI-like `choices[].delta.content` 结构，但本端点不是标准
`POST /v1/chat/completions`，还包含 RAG 专用事件。默认关闭的 OpenAI 兼容预览已经提供
独立的 `/v1/chat/completions`：该端点只发送标准 `data: <chunk>` 和最终
`data: [DONE]`，不发送本页的 event name、工具或来源事件。

当前事件：

| Event | 作用 |
|---|---|
| `content` | 模型文本增量 |
| `tool_start` | AGENT 模式开始调用知识检索工具 |
| `tool_result` | AGENT 模式完成一次知识检索 |
| `sources` | 本轮最终引用来源快照 |
| `done` | 成功终态 |
| `error` | 失败终态 |

`done` 与 `error` 互斥。成功流中 `sources` 在 `done` 前发送。心跳使用 SSE
comment，不是业务事件。

## 2. 事件格式

### 2.1 `content`

```text
event:content
data:{"choices":[{"delta":{"content":"Spring AI"}}]}
```

WebUI 解析器同时兼容旧的无 event name 内容块：

```text
data:{"choices":[{"delta":{"content":"Spring AI"}}]}
```

### 2.2 `tool_start`

主要出现在 `AGENT` 模式：

```text
event:tool_start
data:{"tool":"searchKnowledge","toolCallId":"call-1","query":"风格基调"}
```

`toolCallId` 在上游未提供时可能省略。工具 schema 只允许模型提供 `query` 和可选
`maxResults`；Collection、document 和 API Key 范围由服务端 `ToolContext` 注入，
模型不能扩大授权范围。

### 2.3 `tool_result`

```text
event:tool_result
data:{"tool":"searchKnowledge","toolCallId":"call-1","resultCount":2,"elapsedMs":18}
```

该事件只表示一次工具调用完成。最终去重后的引用以 `sources` 事件为准。
同一 Chat turn 内 citation ID 按来源首次出现顺序稳定分配；多次工具调用命中同一
chunk 时复用原 ID，并与最终 `sources` 快照保持一致。超出唯一来源预算的结果不会暴露
为模型可引用的 source，也不计入 `resultCount`。工具输出字符预算裁掉的来源同样不会
进入最终 `sources` 快照。

### 2.4 `sources`

```text
event:sources
data:{
  "sessionId":"session-001",
  "sources":[{
    "citationId":"S1",
    "documentId":"42",
    "chunkIndex":0,
    "title":"Brand Guide",
    "chunkText":"The visual tone is restrained.",
    "score":0.73,
    "vectorScore":0.81,
    "fulltextScore":0.12,
    "originalFilename":"brand-guide.pdf",
    "documentType":"PDF",
    "collectionKey":"brand:guides",
    "sourceType":"DOCUMENT"
  }]
}
```

`score`、`vectorScore` 和 `fulltextScore` 是当前检索配置下的排序信号，不是概率或
百分比。客户端不应显示为“73% 相关”。

### 2.5 `done`

```text
event:done
data:{
  "traceId":"a1b2c3",
  "sessionId":"session-001",
  "requestedModel":"openrouter/xiaomi/mimo-v2-pro",
  "resolvedModel":"openrouter/xiaomi/mimo-v2-pro",
  "mode":"KNOWLEDGE",
  "usage":{"promptTokens":120,"completionTokens":36,"totalTokens":156},
  "finishReason":"STOP",
  "stepMetrics":[],
  "status":"complete"
}
```

字段可能因 provider 元数据能力而为空，但 key 保持稳定。收到 `done` 后客户端应停止
loading 状态；重复终态必须忽略。

### 2.6 `error`

```text
event:error
data:{
  "error":{
    "code":"MODEL_CAPABILITY_UNSUPPORTED",
    "message":"Model does not support tool calling"
  },
  "traceId":"a1b2c3",
  "sessionId":"session-001"
}
```

发生错误后不会再发送 `done`。HTTP headers 已提交后，流内业务错误通过该事件表达；
连接建立前的校验错误仍使用普通 HTTP Problem Detail。

## 3. 顺序与终态

典型 `KNOWLEDGE` 流：

```text
content* -> sources -> done
```

典型 `AGENT` 流：

```text
tool_start -> tool_result -> content* -> sources -> done
```

工具与内容事件的具体交错由模型 provider 和 Spring AI Tool Calling 流决定。客户端只
能依赖以下不变量：

1. `sources` 在成功 `done` 前。
2. `done` 与 `error` 只取其一。
3. 任一终态后忽略后续事件。
4. 每个事件可使用 LF 或 CRLF；多个 `data:` 行按 SSE 规则拼接。
5. EOF 前没有空行的最后一个完整事件仍应被处理。

## 4. 取消、超时与 fallback

- 浏览器停止生成会调用 stream reader `cancel()`。
- `SseEmitter` completion、timeout 和 error 都会 dispose 后端 Reactor
  subscription。
- 客户端取消的未完成 turn 不写入 `rag_chat_history`，也不提交到
  `spring_ai_chat_memory`。
- 流式 fallback 只允许发生在第一个客户端可见事件之前。一旦已经发出
  `content`、工具事件或其他业务事件，后续失败不会切换模型拼接另一条流。
- `rag.timeout.chat-stream-ms` 控制服务端流式 deadline。

## 5. Heartbeat

`rag.sse.heartbeat-interval-seconds` 默认 `30`。启用时服务端发送 SSE comment：

```text
: heartbeat
```

comment 只用于避免代理关闭空闲连接，客户端不得把它显示为消息。配置为 `0` 时关闭
心跳。

## 6. 请求示例

```bash
curl --no-buffer -X POST \
  http://localhost:8081/api/v1/rag/chat/stream \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer ${RAG_BUSINESS_API_KEY}" \
  -d '{
    "message":"查找风格基调相关资料",
    "mode":"AGENT",
    "model":"openrouter/xiaomi/mimo-v2-pro",
    "collectionScopeMode":"SELECTED_COLLECTIONS",
    "collectionKeys":["brand:guides"]
  }'
```

`AGENT` 要求所选模型在 `GET /api/v1/rag/models` 中声明
`capabilities.toolCalling=true`。

## 7. 前端实现要求

当前 WebUI `useSSE`：

- 支持 `content/tool_start/tool_result/sources/done/error`；
- 支持 CRLF、多行 `data:` 和 EOF 尾块；
- `error` 后忽略 `done`，重复 `done` 只处理一次；
- stop/close 调用 reader `cancel()`；
- 历史重新加载时从 history API 恢复 sources。

对应测试位于：

- `spring-ai-rag-webui/src/hooks/useSSE.test.ts`
- `spring-ai-rag-webui/e2e/chat.spec.ts`
- `spring-ai-rag-core/src/test/java/com/springairag/core/integration/RagControllerIntegrationTest.java`
- `spring-ai-rag-core/src/test/java/com/springairag/core/controller/OpenAiCompatibilityControllerWebTest.java`

## 8. 其他 SSE 入口

PDF 导入与 Embedding 进度端点仍使用各自的进度事件，不采用 Chat 的工具/来源事件：

- `POST /api/v1/rag/files/pdf-to-rag`
- `POST /api/v1/rag/files/{uuid}/embed`
- Document batch embedding SSE

这些端点的 Collection 身份优先使用 URL 编码后的 `collectionKey`；deprecated 的
`collectionId` 继续兼容。

OpenAI 兼容流的请求、scope 和错误信封见
[中文 REST API](rest-api-zh-CN.md#openai-chat-completions-兼容预览) /
[English REST API](rest-api.md#openai-chat-completions-compatibility-preview)。
