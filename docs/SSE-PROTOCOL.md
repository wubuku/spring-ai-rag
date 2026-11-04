# SSE 流式协议

可选[回答契约](candidate-isolation-answer-contract-zh-CN.md#3-请求级回答契约)不增加事件类型或改变终态顺序；
声明契约的所有mode在校验前不发正文，摘要随原completed/final rag投影，溢出/违约返回安全说明。
无契约旧Client流行为不变；首事件后不切模型。

> 状态：**已实现** | 最近复核：2026-11-04
>
> Chat HTTP 请求与非流式响应见
> [REST API](rest-api.md#chat--rag-qa) /
> [中文 REST API](rest-api-zh-CN.md#chat--rag-问答)。

## 1. 协议边界

错误对象增量提供低敏 `failureDetails`；正常终态的 Native metadata/OpenAI rag 增量提供
`answerOutcome`，区分安全拒答、证据兜底、截断和缺模型结束信号。旧事件顺序和错误码不变，
HTTP 200 不能独立证明 SSE 成功；已发部分文本不得与重试结果自动拼接。
字段与恢复语义见[上游故障与 Client 恢复](upstream-client-recovery-zh-CN.md)。

来源的可选 `evidenceSnapshot` 记录检索版本和最终完整证据 digest，字段定义见
[REST API](rest-api-zh-CN.md#证据版本快照与容量错误)。版本冲突发出
`EVIDENCE_SNAPSHOT_CONFLICT` 错误而非成功 `done`，不提交成功历史；已发文本不可撤回。
请求准入拒绝可在建立 SSE 前返回 HTTP 429 与 `Retry-After: 1`，模型阶段的拒绝则可能作为流错误发出。

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

所有 SSE 响应体以 **UTF-8** 编码；`text/event-stream` 响应头不携带 `charset`
参数，编码由写入端固定。这一点对 `/v1/chat/completions` 尤其要紧：它发送的是
预序列化的 JSON 字符串而非对象，若按 Spring `StringHttpMessageConverter` 的
默认字符集（ISO-8859-1）写出，答案里的中文、模型别名和上游错误消息都会变成
`?`。实现侧见 `OpenAiCompatibilityController#utf8Frame`；回归测试见
`OpenAiCompatibilitySseLifecycleTailTest#nonAsciiAnswerAndModelAliasSurviveAsUtf8`。

`done` 与 `error` 互斥。成功流中 `sources` 在 `done` 前发送。心跳使用 SSE
comment，不是业务事件。

原生 Chat 业务事件（`content`、`tool_start`、`tool_result`、`sources`、`done`、`error`）
携带从 `1` 开始的正整数 `sequence`，同一响应内严格递增；心跳不编号。旧服务缺少该字段时，
客户端仍按旧协议处理。带 `Idempotency-Key` 的 keyed snapshot 重试会重复发送确定性快照，
WebUI 在同一 turn 内按 `sequence` 忽略重复或倒退事件。

### 1.1 断点续传（`id:` 与 `Last-Event-ID`）

**可续传范围**：只有带 `Idempotency-Key` 的 keyed turn。服务端把整轮结果持久化为确定性
快照（`content` → `sources` → `done`），并在每条事件上写 `id:`，值等于该事件的
`sequence`（`content=1`、`sources=2`、`done=3`；空答案/空来源时编号会前移）。

```text
id:1
event:content
data:{"sequence":1,"choices":[{"delta":{"content":"..."}}]}
```

客户端断线后带 `Last-Event-ID: <最后收到的 id>` 重试同一 `Idempotency-Key`，服务端只补
`id > Last-Event-ID` 的事件。因此：

- 已投递事件不会重复推送；缺失事件不会漏发（重连成功时终态事件仍在）。
- 非法或缺失 `Last-Event-ID`（非数字、负数）按"没有断点"处理，重放整轮，不报 400：
  一次网络抖动不该被升级成回答失败。
- 请求体或 `Idempotency-Key` 改变后不再属于同一 turn，续传语义不成立。
- 客户端必须保留重试前已收到的正文与来源（服务端不会重发），只清掉不会被补发的
  临时 UI 状态（例如"搜索中"的工具活动）。重复事件由 `sequence` 去重兜底。
- 重连次数有界：WebUI `useSSE` 最多 2 次尝试（1 次重试），失败后进入 `error` 终态，
  不自动自旋。

**不可续传范围**：不带 `Idempotency-Key` 的实时流。它逐块发送模型增量，服务端不写
`id:`、也不读 `Last-Event-ID`；每次重试都是新的 turn。客户端不得把它当作可续传流，
也不得在重连后把上一轮的正文与新 turn 的正文拼接。

断线重连带回的终态保持原语义：服务端已判定 `incomplete` 时，续传补发的 `done` 仍是
`incomplete`，不会因为重连升级为 `complete`；`complete` 也不会被重复发送两次。

### 1.2 并发连接与资源上限

一条 SSE 流在整轮生成期间占用一个 admission 名额。默认上限来自配置：

- `rag.admission.max-requests-per-principal`：**16**（同一个 API Key / principal）
- `rag.admission.max-requests`：**64**（单实例总数）
- `rag.timeout.chat-stream-ms`：**180000**，同时作为 `SseEmitter` 异步超时

超过单 principal 上限时**不排队**：请求在建立 SSE 前返回 HTTP 429、`Retry-After: 1`，
错误码 `TOO_MANY_REQUESTS`。断开或终态后名额立即归还；心跳由服务端共享的 2 个守护线程
统一发送，不按连接创建线程。实测数字与复现脚本见
[容量与规模退化说明](capacity-and-scale-zh-CN.md#39-sse-并发连接资源边界2026-11-实测)。

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
  "citationRegistry":{"version":1,"handlePrefix":"c","sourceCount":1,"handles":["c1"]},
  "sources":[{
    "citationId":"S1",
    "sourceHandle":"c1",
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

`sourceHandle` 是本次请求内的短期引用别名，不是数据库主键、下载 URL 或跨请求凭据；
`citationId` 保留用于历史客户端兼容。`citationRegistry.handles` 只列出最终 sources
快照中的句柄，顺序与 sources 一致。`score`、`vectorScore` 和 `fulltextScore` 是当前检索配置下的排序信号，不是概率或
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
  "evidenceAssessment":{
    "status":"SUPPORTED",
    "retrievalExecuted":true,
    "sourceCount":1,
    "citedSourceCount":1,
    "invalidCitationIds":[],
    "contextLimited":false,
    "safeAnswerApplied":false,
    "reasonCode":"citation_supported"
  },
  "sequence":3,
  "stepMetrics":[],
  "status":"complete"
}
```

字段可能因 provider 元数据能力而为空，但 key 保持稳定。`evidenceAssessment` 是新增
可选字段；旧客户端可忽略。对于 `KNOWLEDGE`，服务端在该评估完成前不会发送模型正文，
若安全门未通过，唯一的 `content` 终态正文是受控安全回答。收到 `done` 后客户端应停止
loading 状态；重复终态必须忽略。

`status` 为 `complete` 或 `incomplete`。当模型流缺少结束信号（`answerOutcome.status`
为 `OUTPUT_INCOMPLETE`）时，终态为 `done` 但 `status` 是 `incomplete`，客户端不能把
它当作完整回答；上游显式错误仍走 `error` 终态。

模型安全拒答是正常业务结果，不发送`error`终态，也不通过重试/切模型规避。上游明确以`refusal`
字段或`content_filter`结束时，即使原始正文为空，也返回可见安全提示；Native终态metadata可包含
`modelSafetyRefusal=true`，OpenAI兼容终帧在可选`rag.modelSafetyRefusal`投影同一信号。
KNOWLEDGE的明确结构化拒答使用`evidenceAssessment.reasonCode=provider_safety_refusal`。

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
6. 客户端收到 `sequence <= lastSequence` 的事件时忽略；非法序号事件不视为合法终态，
   若流没有后续合法 `done`，继续既有 retry/error 处理。
7. keyed turn 的每条事件带 `id:`（= `sequence`）。重试请求的 `Last-Event-ID` 只能取
   已投递事件的 id；服务端只补 `id > Last-Event-ID` 的事件，不得重发已收事件。
8. 未被服务端补发的事件必须来自客户端本地已保留的状态：正文与来源不能因为重试被清空。

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
  -H "Authorization: Bearer ${RAG_ROOT_API_KEY}" \
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
- keyed 流解析 `id:`，重试时发送 `Last-Event-ID`（仅补缺失事件）；
- 重试前保留已收到的正文与来源，只清理不会被补发的工具活动状态；
- 最多 2 次尝试，重连失败后进入 `error` 终态；
- `error` 后忽略 `done`，重复 `done` 只处理一次；
- stop/close 调用 reader `cancel()`；
- 历史重新加载时从 history API 恢复 sources。

对应测试位于：

- `spring-ai-rag-webui/src/hooks/useSSE.test.ts`
- `spring-ai-rag-webui/e2e/chat.spec.ts`
- `spring-ai-rag-webui/e2e/sse-resume.spec.ts`（Chromium + Firefox，可控断流）
- `spring-ai-rag-core/src/test/java/com/springairag/core/controller/RagChatControllerNativeSnapshotTest.java`
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
