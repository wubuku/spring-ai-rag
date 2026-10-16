# Client Consumer Contract Kit

> [English](client-contract-kit.md) | [中文](client-contract-kit-zh-CN.md)

本指南说明外部 Client 如何使用和验收 `spring-ai-rag` 的 HTTP 契约。它面向不依赖 WebUI、
不引入本项目 Java 类的后端、CLI 和 SDK。完整的机器可读资产位于
`testdata/api-contract/consumer/v1/`。

## 1. 契约边界

- 兼容模式是 `ADDITIVE_ONLY`：旧 endpoint、method、旧请求字段、旧响应字段、SSE event 名称
  和 OpenAI 标准 body 不删除、不改名、不改类型。
- Native API 可以增量返回 `traceId`、`sources`、`evidenceAssessment` 等字段；旧 Client 应忽略
  不认识的新增字段，不应把它们作为解析失败条件。
- OpenAI-compatible JSON/SSE 保留所有标准 OpenAI 字段，并可增量增加顶层 `rag`。RAG 诊断、
  引用和失败分类不会替换或重命名标准字段；调用方可忽略未知的 `rag`，并按情况使用
  `X-Trace-Id`、`X-RAG-Failure-Class` 和 `X-RAG-Idempotent-Replay` 等 header。
- `collectionId`、`collectionIds`、数字 Collection 路由和已登记的 deprecated 路径继续兼容，
  但新 Client 必须使用 `collectionKey`、`collectionKeys` 和对应的新路由。详见
  [`deprecations.json`](../testdata/api-contract/consumer/v1/deprecations.json)。

## 2. 三层验收

### 离线契约验收（默认）

不启动服务、不连接数据库、不调用模型：

```bash
node scripts/lib/client-consumer-contract.mjs --self-test
./scripts/verify-client-consumer-contract.sh
```

这一步检查 fixture、JSON 类型、native SSE 终态、CRLF/多行 data/EOF、OpenAI 私有字段隔离、
失败闭环和 deprecated registry。它默认不产生 LLM 费用。

### Transport live 验收（显式）

在隔离端口启动服务后，只在明确需要时执行：

```bash
CLIENT_CONTRACT_LIVE=1 \
CLIENT_CONTRACT_BASE_URL=http://127.0.0.1:18081 \
CLIENT_CONTRACT_API_KEY="${RAG_ROOT_API_KEY}" \
CLIENT_CONTRACT_QUERY='返回服务能力的简短摘要' \
CLIENT_CONTRACT_MODEL="${CLIENT_CONTRACT_MODEL:-}" \
./scripts/verify-client-consumer-contract.sh
```

Native live probe 使用 `mode=PLAIN`，只验证 HTTP/JSON/SSE 消费协议，不代表 KNOWLEDGE 的证据
充分性。OpenAI 兼容路径未启用时，`/v1/models` 返回 `404/405/501` 会记录为 conditional skip，
不会伪造为通过。OpenAI live probe 默认发送标准 body；只有目标 alias 明确允许请求级覆盖时，才
通过 `CLIENT_CONTRACT_OPENAI_RAG_MODE`（可选配 `CLIENT_CONTRACT_OPENAI_RAG_MEMORY`）加入既有
`rag` 扩展。若显式启用了 live 但缺少必要环境变量，脚本也只输出 skip，不会声称完成 live 验收。

### 官方 SDK HTTP 配套验收

```bash
./scripts/verify-openai-sdk-e2e.sh
./scripts/verify-openai-sdk-e2e.sh --self-test
```

默认启动可丢弃 PostgreSQL、Mock 模型和两种真实 HTTP 拓扑：Core standalone 与仅依赖
Starter 的外部 Consumer。官方 Python `2.14.0`、Node `7.34.0` SDK 验证 JSON、SSE
role/content/finish 顺序和原始 `[DONE]`、错误与鉴权、幂等重放；隔离模式额外验证 Native
Collection/API Key 创建、ACL、共享配额、上游故障与 `/v1` 关闭时 Native API 仍可用。
runner self-test 对协议变异执行负向断言，避免错误请求意外成功却报告通过。
发布入口为 `scripts/verify-release.sh --with-openai-sdk`；该项需要 Docker、Java/Maven、
Node/npm 和 Python。只跑单拓扑可加 `--topology core` 或 `--topology starter`。
显式真实模型模式连接的是已启动的 **RAG 服务根地址**，不是上游 provider；配置见
[开发者参考](developer-reference-zh-CN.md#官方-openai-pythonnode-sdk-黑盒门禁)。

### KNOWLEDGE 证据验收

需要真实知识库、模型和授权范围时，使用 Evaluation/real-LLM 验收链。应检查最终答案、最终输入
证据和引用，而不能把本 Kit 的 PLAIN transport 结果当作知识质量结果。`evidenceAssessment.status`
的稳定值包括 `SUPPORTED`、`NO_EVIDENCE`、`MISSING_CITATION`、`INVALID_CITATION`、
`PARTIAL_CITATION`、`CONTEXT_LIMITED` 和 `EVALUATION_UNAVAILABLE`。

## 3. Native JSON 与 SSE

### Native JSON

```bash
curl --fail-with-body -sS "$CLIENT_CONTRACT_BASE_URL/api/v1/rag/chat" \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer ${CLIENT_CONTRACT_API_KEY}" \
  -d '{"message":"返回服务能力的简短摘要","mode":"PLAIN"}'
```

旧 Client 至少依赖 `answer`。`sources` 是数组；`traceId`、`sessionId`、`turnId` 不互相替代；
`evidenceAssessment` 是增量字段。

### Native SSE

```bash
curl --no-buffer -sS "$CLIENT_CONTRACT_BASE_URL/api/v1/rag/chat/stream" \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer ${CLIENT_CONTRACT_API_KEY}" \
  -d '{"message":"返回服务能力的简短摘要","mode":"PLAIN"}'
```

消费方应遵守：

1. 成功流的 `sources` 在 `done` 之前；`done` 与 `error` 只接受一个终态。
2. 任一终态后忽略后续事件；重复 `sequence` 可安全忽略，倒序 sequence 不能被当作新事件。
3. 支持 LF/CRLF、多行 `data:`、heartbeat comment（例如 `: heartbeat`）以及 EOF 前最后一个
   没有空行的完整事件。
4. `error` 的 `failureClass` 使用服务已发布的稳定枚举；流已提交后不要等待 HTTP header 改变。

最小 TypeScript 消费骨架：

```ts
type NativeEvent = { event: string; data: unknown };

export async function consumeNativeSse(response: Response): Promise<NativeEvent[]> {
  const text = await response.text();
  const events: NativeEvent[] = [];
  let name = 'message';
  let data: string[] = [];
  const flush = () => {
    if (data.length > 0) events.push({ event: name, data: JSON.parse(data.join('\n')) });
    name = 'message';
    data = [];
  };
  for (const line of text.replaceAll('\r\n', '\n').replaceAll('\r', '\n').split('\n')) {
    if (line === '') { flush(); continue; }
    if (line.startsWith(':')) continue;
    const separator = line.indexOf(':');
    const field = separator < 0 ? line : line.slice(0, separator);
    const value = (separator < 0 ? '' : line.slice(separator + 1)).replace(/^ /, '');
    if (field === 'event') name = value;
    if (field === 'data') data.push(value);
  }
  flush();
  return events;
}
```

## 4. OpenAI-compatible JSON/SSE

OpenAI JSON 请求只使用标准字段：

```bash
curl --fail-with-body -sS "$CLIENT_CONTRACT_BASE_URL/v1/chat/completions" \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer ${CLIENT_CONTRACT_API_KEY}" \
  -d '{"model":"fixture-model","messages":[{"role":"user","content":"返回服务能力的简短摘要"}]}'
```

流式请求增加 `"stream":true`，消费 `data:` JSON chunk，直到独立的 `data: [DONE]`。
`answer`、`sources`、`evidenceAssessment` 和 `traceId` 仍不是标准 OpenAI 字段；服务端
可能在非流式响应或 finish chunk 增加可选的顶层 `rag`。标准 Client 可以忽略它；需要读取
引用时只读取其中的脱敏 `sources`，不要期待 `chunkText` 或任意 metadata。

`rag` 的字段包括 `traceId`、`evidenceAssessment`、`sources` 和 `citationRegistry`。
`sources` 只来自当前请求已授权且最终实际暴露的来源；完整正文仍通过 Native API 获取。
普通 SSE 的 `rag` 只在 finish chunk 出现，keyed replay 的 JSON/SSE 与原执行保持一致。

Java `HttpClient` 最小骨架：

```java
HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/rag/chat"))
        .header("Content-Type", "application/json")
        .header("Authorization", "Bearer " + apiKey)
        .POST(HttpRequest.BodyPublishers.ofString(
                "{\"message\":\"返回服务能力的简短摘要\",\"mode\":\"PLAIN\"}"))
        .build();
HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
if (response.statusCode() / 100 != 2) {
    throw new IllegalStateException("RAG request failed: " + response.statusCode()
            + " trace=" + response.headers().firstValue("X-Trace-Id").orElse("unknown"));
}
JsonNode body = objectMapper.readTree(response.body());
String answer = body.path("answer").asText();
```

生产 Client 应使用成熟 SSE parser 或明确实现字段累积、CRLF、注释、EOF 和 `[DONE]` 规则；
不要用简单的 `split("data:")` 代替协议解析。

## 5. 错误动作映射

| 信号 | 默认 Client 动作 |
|---|---|
| `RATE_LIMITED` + `Retry-After` | 有界退避后重试 |
| `LLM_BUDGET_EXCEEDED` + `Retry-After` | 按响应头等待后重试，或切换到调用方自己的额度策略 |
| `LLM_BUDGET_SERVICE_UNAVAILABLE` | 不重试 provider；按 trace 记录并进行有界基础设施重试 |
| `CONFLICT` + `CHAT_TURN_IN_PROGRESS` | 查询状态或安全重放，不并发拼接回答 |
| `DEPENDENCY_UNAVAILABLE` | 有界重试，超过上限降级或人工处理 |
| `TIMEOUT` | 仅在请求可安全重放时重试，否则按 trace 查询 |
| `CLIENT_REQUEST` / `AUTHENTICATION` / `AUTHORIZATION` | 不重试，修正请求或凭据 |
| `NO_EVIDENCE` 等证据不足状态 | 展示安全回答并提示补充范围，不重试同一回答 |
| `MODEL_CAPABILITY_UNSUPPORTED` | 不重复请求同一模型，改用能力匹配模型 |
| `NOT_FOUND` / `INTERNAL` | 默认不自动重试，刷新资源或查询 trace |

## 6. 机器可读入口

- 契约包：`testdata/api-contract/consumer/v1/`
- 生产者兼容基线：`testdata/api-contract/v1-compatibility.json`
- validator：`scripts/lib/client-consumer-contract.mjs`
- 离线/live gate：`scripts/verify-client-consumer-contract.sh`
- SSE 细节：[SSE-PROTOCOL.md](SSE-PROTOCOL.md)
- 完整 HTTP 参考：[rest-api-zh-CN.md](rest-api-zh-CN.md)
