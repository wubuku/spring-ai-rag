# Client SDK 快速入门

> 📖 [English](client-sdk-quickstart.md) · 📖 [中文](client-sdk-quickstart-zh-CN.md)

这是一份面向外部 Client 的最小接入指南。它不依赖本项目 WebUI，也不要求调用方扫描
`com.springairag`；调用方只需要一个服务根地址、RAG API Key 和 `/v1/models` 返回的
公开 model alias。

## 1. 兼容边界

- 服务根地址不要带 `/v1`；SDK 的 `base_url` 才拼接 `/v1`。
- 现有 OpenAI JSON/SSE 字段、事件顺序、`data: [DONE]`、错误 envelope 和
  `Idempotency-Key` 语义保持兼容。
- 非流式响应和 SSE finish chunk 可以额外出现顶层 `rag`；标准 OpenAI Client 应忽略未知字段。
- `rag.sources` 只包含当前请求已授权且最终暴露的来源摘要，不包含 `chunkText` 或任意
  `metadata`。需要完整来源正文时使用 Native API 的 `sources`。
- `stream=true` 时，`rag` 只出现在 finish chunk，不新增私有 SSE event。
- 该扩展不会重新执行检索或模型调用；JSON、普通 SSE 和 keyed replay 使用同一执行结果。

## 2. 最小 Python 接入

示例文件：[`examples/client-sdk/python/chat_completion.py`](../examples/client-sdk/python/chat_completion.py)。

```bash
python3 -m venv .venv
. .venv/bin/activate
python -m pip install -r examples/client-sdk/python/requirements.txt

export RAG_BASE_URL=http://127.0.0.1:18081
export RAG_API_KEY="$RAG_ROOT_API_KEY"
export RAG_MODEL=rag-default
python examples/client-sdk/python/chat_completion.py
python examples/client-sdk/python/chat_completion.py --stream
```

示例只打印请求是否成功、completion ID、model、choice 数量和是否收到 `rag`，不会打印
API Key、完整 prompt、答案、source 正文或异常全文。

## 3. 最小 Node 接入

示例文件：[`examples/client-sdk/node/chat-completion.mjs`](../examples/client-sdk/node/chat-completion.mjs)。

```bash
cd examples/client-sdk/node
npm ci --ignore-scripts --no-audit --no-fund

export RAG_BASE_URL=http://127.0.0.1:18081
export RAG_API_KEY="$RAG_ROOT_API_KEY"
export RAG_MODEL=rag-default
node chat-completion.mjs
node chat-completion.mjs --stream
```

生产 Client 应继续使用官方 SDK 的 SSE 迭代器或成熟 SSE parser，不要用字符串
`split("data:")` 代替协议解析。

## 4. 离线自测与真实 HTTP 验收

离线自测只检查 Python/Node 语法和示例中的 secret-safety，不调用模型：

```bash
./scripts/verify-client-sdk-quickstart.sh --self-test
```

对已启动的服务执行真实 HTTP 检查时，显式设置 `CLIENT_SDK_LIVE=1`：

```bash
CLIENT_SDK_LIVE=1 \
CLIENT_SDK_BASE_URL=http://127.0.0.1:18081 \
CLIENT_SDK_API_KEY="$RAG_ROOT_API_KEY" \
./scripts/verify-client-sdk-quickstart.sh
```

该脚本默认不调用真实 LLM；没有凭据时只能报告离线检查，不得把离线或 Mock 通过解释为
真实模型质量证据。live 模式会分别执行 Python/Node 的 JSON 与 SSE 请求；Node 依赖通过
提交的 lockfile 安装。完整的版本化契约、Native API、OpenAI JSON/SSE、错误动作和
KNOWLEDGE 证据验收见 [Client Consumer Contract Kit](client-contract-kit-zh-CN.md)。

## 5. 调用方建议

1. 启动时读取 `/v1/models`，不要硬编码不存在的 alias。
2. 多轮调用重传完整 `messages`；当前 `/v1` 不提供客户端可寻址的稳定 session ID。
3. 只有在请求可以安全重放时才使用 `Idempotency-Key`；keyed streaming 是完成后快照式 SSE。
4. 将 `traceId`、`X-Trace-Id` 和 `X-RAG-Turn-Id` 分开记录，不要互相替代。
5. 需要知识质量、引用合法性或证据不足判断时，执行 KNOWLEDGE 证据验收，而不是只做
   PLAIN transport smoke。

相关参考：

- [REST API：OpenAI 兼容预览](rest-api-zh-CN.md#openai-chat-completions-兼容预览)
- [OpenAI 兼容就绪度](openai-compatibility-readiness-zh-CN.md)
- [Client Consumer Contract Kit](client-contract-kit-zh-CN.md)
