# SSE 流式协议设计文档

> 状态：规划中 | 日期：2026-04-06

## 1. 当前实现

### 1.1 当前协议格式

```
event:trace
data:{traceId}

event:chunk
data:{text content}

event:sources
data:{sources JSON}

event:done
data:{"traceId":"xxx","status":"complete"}
```

### 1.2 当前实现代码

**后端**（`RagChatController.java`）：
```java
// trace 事件
emitter.send(SseEmitter.event().name("trace").data(traceId));

// chunk 事件（当前：发送纯文本）
emitter.send(SseEmitter.event().name("chunk").data(chunk));

// sources 事件
emitter.send(SseEmitter.event().name("sources").data(json));

// done 事件
emitter.send(SseEmitter.event().name("done").data("{\"traceId\":\"...\",\"status\":\"complete\"}"));
```

**前端**（`useSSE.ts`）：
- 读取 SSE 流，按 `\n\n` 分割事件
- 根据 `event:` 类型处理：`chunk`/`sources`/`done`/`trace`

### 1.3 当前问题

| 问题 | 描述 |
|------|------|
| 非标准格式 | 使用自定义 `event:chunk` 命名事件，非主流 SSE 实践 |
| 无 Content-Type | 没有声明 `text/event-stream` Content-Type |
| 错误处理粗糙 | 仅通过 `completeWithError` 传递错误 |
| 缺少 metadata | 没有 token 使用量、模型名称等元数据 |
| sources 格式不明确 | sources 事件的 JSON 结构未文档化 |
| 缺少 heartbeat | 长时间传输没有心跳保活 |

## 2. 参考协议设计

### 2.1 OpenAI SSE 格式（最佳实践）

参考 `spring-ai-skills-demo/docs/drafts/多模态输入支持规划文档.md` 第 11 章：

```
Content-Type: text/event-stream
Cache-Control: no-cache
Connection: keep-alive

data:{"choices":[{"delta":{"content":"你"}}]}
data:{"choices":[{"delta":{"content":"好"}}]}
...
data:{"choices":[{"delta":{"content":"！"},"finish_reason":"stop"}]}
data:[DONE]
```

**特点**：
- 标准 SSE `data:` 行格式（无 `event:` 命名事件）
- OpenAI Chat Completions 兼容的 JSON 结构
- `choices[0].delta.content` 包含增量内容
- `finish_reason` 标识结束原因
- `data:[DONE]` 是标准的结束标记

### 2.2 多模态扩展（参考）

```json
// 视觉块事件
data:{"type":"vision","image_url":"data:image/png;base64,..."}

// 文本块事件
data:{"type":"content","content":"文本内容"}

// 元数据事件
data:{"type":"metadata","model":"gpt-4","tokens":125,"usage":{"prompt":10,"completion":115}}
```

## 3. 协议设计改进

### 3.1 推荐协议：OpenAI 兼容格式

**目标**：与 Spring AI OpenAI 集成保持兼容，同时支持 RAG 特有功能。

```
Content-Type: text/event-stream
Cache-Control: no-cache
Connection: keep-alive
X-Content-Type-Options: nosniff

data:{"choices":[{"delta":{"content":"你"}}]}
data:{"choices":[{"delta":{"content":"好"}}]}
...
data:{"choices":[{"delta":{"content":"！"},"finish_reason":"stop"}]}
data:{"choices":[{"delta":{"content":"[SOURCES]"},"finish_reason":"sources"}]}
data:{"id":"chatcmpl-xxx","object":"chat.completion.chunk","model":"qwen-2.5-7b","usage":{"prompt_tokens":10,"completion_tokens":50,"total_tokens":60}}
data:[DONE]
```

### 3.2 事件类型定义

| Event | Format | Description |
|-------|--------|-------------|
| `content` | `data:{"choices":[{"delta":{"content":"..."}}]}` | 文本内容块 |
| `sources` | `data:{"choices":[{"delta":{"content":"[SOURCES]"}}]}` | 标识 sources 元数据即将发送 |
| `metadata` | `data:{"id":"...","object":"chat.completion.chunk","usage":{...}}` | 元数据（token 统计等） |
| `done` | `data:[DONE]` | 传输完成 |
| `error` | `data:{"error":{"message":"...","type":"invalid_request_error"}}` | 错误信息 |

### 3.3 sources 传输机制

RAG 特有需求：在回复结束后返回检索来源。

**方案 A**：在 content 中发送 `[SOURCES]` 标识符，后跟 metadata 事件
```
data:{"choices":[{"delta":{"content":"根据搜索结果..."}}]}
data:{"choices":[{"delta":{"content":"[SOURCES]"}}]}
data:{"id":"...","object":"chat.completion.chunk","usage":{"prompt_tokens":10,"completion_tokens":50},"rag":{"sources":[{"documentId":"106","title":"...","score":0.66}]}}
data:[DONE]
```

**方案 B**（推荐）：使用 Server-Sent Events 的 `event:` 类型扩展
```
event:content
data:{"choices":[{"delta":{"content":"根据搜索结果..."}}]}

event:sources
data:{"sources":[{"documentId":"106","title":"...","score":0.66}]}

event:done
data:{"traceId":"xxx","status":"complete","usage":{"total_tokens":60}}
```

### 3.4 推荐的最终协议

采用**混合方案**：
- 主通道使用 OpenAI 兼容的 `data:` 格式
- RAG 特有功能（sources）使用 `event:` 命名事件扩展

```
Content-Type: text/event-stream
Cache-Control: no-cache
X-Trace-Id: {traceId}

# 标准 OpenAI 格式
data:{"choices":[{"delta":{"content":"你好"}}]}
data:{"choices":[{"delta":{"content":"，"}}]}
...

# RAG sources（命名事件）
event:sources
data:{"sources":[{"documentId":"106","title":"痘痘肌肤护理指南","chunkText":"...","score":0.66}]}

# 完成事件
event:done
data:{"traceId":"xxx","status":"complete","usage":{"total_tokens":60,"prompt_tokens":10,"completion_tokens":50}}

# 错误事件（如果发生）
event:error
data:{"error":{"message":"Service unavailable","type":"rate_limit_error","code":429}}
```

## 4. 改进计划

### 4.1 后端改动

| 改动 | 文件 | 描述 |
|------|------|------|
| 修改 SSE 发送格式 | `RagChatController.java` | 改用 `data:{"choices":[{"delta":{"content":"..."}}]}` 格式 |
| 添加 Content-Type 头 | `RagChatController.java` | 显式设置 `text/event-stream` |
| 分离 sources 事件 | `RagChatService.java` | 返回结构化事件对象流 |
| 添加 metadata 事件 | `RagChatController.java` | 发送 token 统计等元数据 |
| 错误事件规范化 | `RagChatController.java` | 使用 `event:error` 格式 |

### 4.2 前端改动

| 改动 | 文件 | 描述 |
|------|------|------|
| 简化 SSE 解析 | `useSSE.ts` | 移除 `event:` 类型判断，专注 `data:` 解析 |
| 处理 OpenAI 格式 | `useSSE.ts` | 解析 `choices[0].delta.content` |
| 处理 sources 事件 | `useSSE.ts` | 识别 `event:sources` 获取来源 |
| 处理 done 事件 | `useSSE.ts` | 识别 `event:done` 或 `data:[DONE]` |
| 类型定义更新 | `useSSE.ts` | `ChatStreamChunkEvent` 适配 OpenAI 格式 |

### 4.3 类型定义

```typescript
// OpenAI 兼容格式
interface SSEContentEvent {
  id: string;
  object: 'chat.completion.chunk';
  choices: Array<{
    index: number;
    delta: { content?: string; role?: string };
    finish_reason?: 'stop' | 'length';
  }>;
}

interface SSESourcesEvent {
  sources: Array<{
    documentId: string | number;
    title?: string;
    content?: string;
    chunkText?: string;
    score?: number;
  }>;
}

interface SSEErrorEvent {
  error: {
    message: string;
    type: string;
    code?: string | number;
  };
}
```

## 5. 实施步骤

### Phase 1: 后端协议改造
1. 修改 `chatStream` 返回 `Flux<String>` 为 `Flux<SseEvent>`（结构化事件）
2. 重构 `RagChatController` SSE 端点
3. 添加 `text/event-stream` Content-Type
4. 测试 curl 验证协议格式

### Phase 2: 前端适配
1. 更新 `useSSE.ts` 解析器
2. 更新 `Chat.tsx` 事件处理
3. 验证打字机效果

### Phase 3: 完善错误处理和 metadata
1. 添加 token 使用量统计
2. 规范化错误事件格式
3. 添加 heartbeat（可选）

## 6. 附录：curl 测试命令

```bash
# 测试 SSE 端点（显示原始格式）
curl -s -X POST http://localhost:8081/api/v1/rag/chat/stream \
  -H 'Content-Type: application/json' \
  -d '{"message":"你好"}' --no-buffer

# 期望输出格式：
# data:{"choices":[{"delta":{"content":"你"}}]}
# data:{"choices":[{"delta":{"content":"好"}}]}
# ...
# event:sources
# data:{"sources":[{"documentId":"106","title":"...","score":0.66}]}
# event:done
# data:{"traceId":"xxx","status":"complete","usage":{"total_tokens":50}}
```
