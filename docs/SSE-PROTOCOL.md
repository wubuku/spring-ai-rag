# SSE 流式协议设计文档

> 状态：**已实现** | 日期：2026-04-06 | 更新：2026-04-06

## 1. 当前实现的协议格式

### 1.1 协议概述

采用 **OpenAI 兼容格式**，与 Spring AI OpenAI 集成保持兼容，同时支持 RAG 特有功能。

```
Content-Type: text/event-stream
Cache-Control: no-cache
X-Trace-Id: {traceId}   # 可选，传递请求追踪 ID

# Content 块（OpenAI 兼容）
data:{"choices":[{"delta":{"content":"你"}}]}
data:{"choices":[{"delta":{"content":"好"}}]}
data:{"choices":[{"delta":{"content":"，"}}]}
...

# 完成事件
event:done
data:{"traceId":"xxx","status":"complete"}
```

### 1.2 事件类型

| Event | Format | Description |
|-------|--------|-------------|
| `content` | `data:{"choices":[{"delta":{"content":"..."}}]}` | 文本内容块（无 event name，默认类型） |
| `done` | `event:done\ndata:{"traceId":"...","status":"complete"}` | 传输完成 |

### 1.3 Content 块格式

```json
// 单个 content 块
data:{"choices":[{"delta":{"content":"你"}}]}

// OpenAI 兼容结构
{
  "choices": [{
    "delta": {
      "content": "文本内容"
    }
  }]
}
```

### 1.4 Done 事件格式

```json
// 完成
event:done
data:{"traceId":"abc123","status":"complete"}

// 错误（如果发生）
event:error
data:{"error":{"message":"Service unavailable","type":"internal_error"}}
```

## 2. 后端实现

### 2.1 RagChatController.java

**文件**：`spring-ai-rag-core/src/main/java/com/springairag/core/controller/RagChatController.java`

```java
@PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter stream(@Valid @RequestBody ChatRequest request) {
    // ...
    SseEmitter emitter = new SseEmitter(0L); // 无超时

    ragChatService.chatStream(request.getMessage(), request.getSessionId(), request.getDomainId())
            .subscribe(
                    chunk -> {
                        // Content 块：OpenAI 兼容格式
                        String json = "{\"choices\":[{\"delta\":{\"content\":\"" + escapeJson(chunk) + "\"}}]}";
                        emitter.send(SseEmitter.event().data(json));
                    },
                    emitter::completeWithError,
                    () -> {
                        // Done 事件
                        String doneJson = "{\"traceId\":\"" + traceId + "\",\"status\":\"complete\"}";
                        emitter.send(SseEmitter.event().name("done").data(doneJson));
                        emitter.complete();
                    }
            );
    return emitter;
}
```

### 2.2 escapeJson 辅助函数

```java
private String escapeJson(String text) {
    if (text == null) return "";
    return text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
}
```

## 3. 前端实现

### 3.1 useSSE.ts

**文件**：`spring-ai-rag-webui/src/hooks/useSSE.ts`

```typescript
// SSE 协议：OpenAI 兼容格式
// - Content events: data:{"choices":[{"delta":{"content":"..."}}]}
// - Done event:     event:done\ndata:{"traceId":"...","status":"complete"}

export function useChatSSE(options: UseChatSSEOptions): UseChatSSEReturn {
    const readerRef = useRef<ReadableStreamDefaultReader<Uint8Array> | null>(null);
    const accumulatedContentRef = useRef<string>('');

    const send = useCallback(async (message: string, collectionId?: number, conversationId?: string) => {
        close();
        setIsConnected(true);
        accumulatedContentRef.current = '';

        const response = await fetch('/api/v1/rag/chat/stream', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ message, collectionId, sessionId: conversationId }),
        });

        const reader = response.body.getReader();
        const decoder = new TextDecoder();
        let buffer = '';

        while (true) {
            const { done, value } = await reader.read();
            if (done) break;

            buffer += decoder.decode(value, { stream: true });

            // Process complete SSE events (separated by \n\n)
            while (buffer.includes('\n\n')) {
                const eventEnd = buffer.indexOf('\n\n');
                const eventBlock = buffer.slice(0, eventEnd);
                buffer = buffer.slice(eventEnd + 2);

                const event = parseSSEEvent(eventBlock);
                if (!event) continue;

                if (event.type === 'content' && event.data) {
                    // OpenAI format: {"choices":[{"delta":{"content":"..."}}]}
                    const content = extractContentFromChoices(event.data);
                    if (content) {
                        onChunkRef.current?.(content);
                    }
                } else if (event.type === 'done') {
                    onDoneRef.current?.();
                }
            }
        }
    }, [close]);
}
```

### 3.2 SSE 事件解析器

```typescript
function parseSSEEvent(block: string): { type: string; data: string } | null {
    if (!block.trim()) return null;

    let eventType = 'content'; // Default to content (OpenAI format uses no event type)
    let eventData = '';

    const lines = block.split('\n');
    for (const line of lines) {
        if (line.startsWith('event:')) {
            eventType = line.slice(6).trim();
        } else if (line.startsWith('data:')) {
            eventData = line.slice(5);
        }
    }

    if (!eventData) return null;
    return { type: eventType, data: eventData };
}

function extractContentFromChoices(jsonStr: string): string | null {
    try {
        const parsed = JSON.parse(jsonStr);
        if (parsed.choices && parsed.choices.length > 0) {
            return parsed.choices[0].delta.content ?? null;
        }
    } catch { /* not JSON or wrong format */ }
    return null;
}
```

## 4. Chat.tsx 集成

**文件**：`spring-ai-rag-webui/src/pages/Chat.tsx`

```typescript
const { send, isConnected } = useChatSSE({
    onChunk: (content: string) => {
        // Append chunk to the last streaming assistant message
        setMessages(prev => {
            const lastMsg = prev[prev.length - 1];
            if (lastMsg?.isStreaming) {
                return prev.map(msg =>
                    msg.id === lastMsg.id
                        ? { ...msg, content: msg.content + content }
                        : msg
                );
            }
            return prev;
        });
    },
    onDone: () => {
        setMessages(prev =>
            prev.map(msg => (msg.isStreaming ? { ...msg, isStreaming: false } : msg))
        );
    },
});
```

## 5. curl 测试命令

```bash
# 测试 SSE 端点（显示原始格式）
curl -s -X POST http://localhost:8081/api/v1/rag/chat/stream \
  -H 'Content-Type: application/json' \
  -d '{"message":"你好"}' --no-buffer

# 期望输出：
# data:{"choices":[{"delta":{"content":"Hello"}}]}
# data:{"choices":[{"delta":{"content":"!"}}]}
# ...
# event:done
# data:{"traceId":"abc123","status":"complete"}
```

## 6. 与旧格式的对比

| 特性 | 旧格式 (event:chunk) | 新格式 (OpenAI 兼容) |
|------|---------------------|---------------------|
| Content 格式 | `event:chunk\ndata:纯文本` | `data:{"choices":[{"delta":{"content":"..."}}]}` |
| Done 格式 | `event:done\ndata:{...}` | `event:done\ndata:{...}` (相同) |
| 兼容性 | 自定义 | OpenAI/ Spring AI 兼容 |
| 前端解析 | 需识别 event name | 统一解析 data: JSON |

## 7. 已知限制

1. **sources 事件**：当前实现未发送 sources 事件（来源文档在 done 之后通过另一个 API 获取）
2. **error 事件**：错误通过 `completeWithError` 传递，未使用 `event:error`
3. **token 统计**：未发送 token 使用量元数据
4. **heartbeat**：无心跳保活（`SseEmitter(0L)` 表示无限超时）

## 8. 未来改进方向

- [ ] 添加 sources 事件（`event:sources\ndata:{"sources":[...]}`)
- [ ] 添加 error 事件规范化
- [ ] 添加 token 使用量元数据
- [ ] 添加 heartbeat 保活
