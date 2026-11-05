# 多模型外部配置

> 📖 [English](multi-model-external-config.md) · 📖 [中文](multi-model-external-config-zh-CN.md)

当模型清单和凭据需要脱离应用 JAR 管理时，可使用外部
`models.json`。

## 启用

```bash
export MODELS_CONFIG_FILE=/etc/spring-ai/models.json
# 也支持 `file:` URI：
export MODELS_CONFIG_FILE=file:/etc/spring-ai/models.json
```

- 路径必须是绝对路径或合法的 `file:` URI。
- 文件成功加载后会完整替换 `app.models.*`，不会与 YAML 合并。
- 文件不存在或解析失败时继续使用 YAML 配置。
- OpenAI 兼容端点的 `baseUrl` 末尾不能带 `/v1`。
- 模型 ID 使用 provider 原生 ID，不要再添加本地 provider 前缀。
  请求中的模型引用格式为 `providerId/modelId`，例如
  `openrouter/xiaomi/mimo-v2-pro`。
- 该文件成功加载后会完整替换 YAML 模型注册表，因此本地过期文件可能在
  `SPRING_AI_OPENAI_*` 正确时仍覆盖 Chat provider 和路由。修改后应检查启动日志及
  `GET /api/v1/rag/models`。如果希望在一次直接 provider smoke 中不使用外部注册表，
  请对该进程取消设置 `MODELS_CONFIG_FILE`，或显式写成哨兵值
  `MODELS_CONFIG_FILE=none`（同样接受 `off` / `disabled` / `-`）。哨兵值会让启动日志
  打印 `disables the external registry; using YAML config only`。
- 常见的“明明配了 LongCat 却走了别的 provider”就是这样发生的：仓库里的
  `.dev/models.json` 仍然声明 primary 为 siliconflow，而 key 是未解析占位。
  `scripts/start-real-e2e-server.sh` 的 LongCat 路径请使用
  `MODELS_CONFIG_FILE=none`，不要依赖“文件不存在”这种隐性条件。

## 完整示例

```json
{
  "models": {
    "providers": {
      "openrouter": {
        "displayName": "OpenRouter",
        "baseUrl": "https://openrouter.ai/api",
        "apiKey": "${OPENROUTER_API_KEY}",
        "apiType": "openai-completions",
        "enabled": true,
        "priority": 1,
        "models": [
          {
            "id": "xiaomi/mimo-v2-pro",
            "name": "MiMo V2 Pro",
            "type": "chat",
            "reasoning": false,
            "inputModalities": ["text"],
            "cost": {
              "input": 0,
              "output": 0,
              "cacheRead": 0,
              "cacheWrite": 0
            },
            "contextWindow": 600000,
            "maxTokens": 32000,
            "capabilities": {
              "streaming": true,
              "toolCalling": true
            }
          }
        ]
      },
      "minimax": {
        "displayName": "MiniMax",
        "baseUrl": "https://api.minimaxi.com/anthropic",
        "apiKey": "${MINIMAX_API_KEY}",
        "apiType": "anthropic-messages",
        "enabled": true,
        "priority": 2,
        "models": [
          {
            "id": "MiniMax-M2.7",
            "name": "MiniMax M2.7",
            "type": "chat",
            "reasoning": false,
            "inputModalities": ["text"],
            "cost": {
              "input": 15,
              "output": 60,
              "cacheRead": 2,
              "cacheWrite": 10
            },
            "contextWindow": 200000,
            "maxTokens": 8192,
            "capabilities": {
              "streaming": true,
              "toolCalling": false
            }
          },
          {
            "id": "embo-01",
            "name": "Embedding V01",
            "type": "embedding",
            "inputModalities": ["text"],
            "dimension": 1024
          }
        ]
      },
      "siliconflow": {
        "displayName": "SiliconFlow",
        "baseUrl": "https://api.siliconflow.cn",
        "apiKey": "${RAG_EMBEDDING_API_KEY}",
        "apiType": "openai-chat",
        "enabled": true,
        "priority": 3,
        "models": [
          {
            "id": "BGE-M3",
            "name": "BGE-M3 Embedding",
            "type": "embedding",
            "inputModalities": ["text"],
            "dimension": 1024
          }
        ]
      }
    },
    "chatModel": {
      "primary": "minimax/MiniMax-M2.7",
      "fallbacks": ["openrouter/xiaomi/mimo-v2-pro"]
    },
    "embeddingModel": {
      "primary": "siliconflow/BGE-M3",
      "fallbacks": ["minimax/embo-01"]
    }
  }
}
```

Chat 模型支持的 `apiType` 为 `openai`、`openai-chat`、
`openai-completions`、`anthropic` 和 `anthropic-messages`。

省略 `capabilities.streaming` 时为兼容旧配置默认按 `true` 处理；
`capabilities.toolCalling` 保守地默认为 `false`。只有确认具体 provider/model 会通过
所选 API 类型接受 tool schema 并返回 tool call 后，才设置为 `true`；仅仅因为 Spring
AI adapter 支持工具选项并不足以证明上游模型支持。

## 验证

启动后查询实际生效的模型注册表：

```bash
curl http://localhost:8081/api/v1/rag/models
```

响应包含 `defaultModel`、模型级 `ref`、规范化 `capabilities` 和可用状态；当凭据或
provider 配置缺失时还会包含 `unavailableReason`。`AGENT` Chat 要求
`capabilities.toolCalling=true`。

### base-url 实际拼接（LongCat 2026-11-05 实测）

LongCat 的 OpenAI 兼容 base 是 `https://api.longcat.chat/openai`，配置里**不带** `/v1`。
Spring AI 发出的是 `POST /openai/v1/chat/completions`——也就是说它固定再拼一段 `/v1`，
而不是直接使用 base 的最后一段。配置若写成 `.../openai/v1`，实际会变成
`/openai/v1/v1/chat/completions` 并失败。这个拼接由真实端到端脚本的代理路径断言守护：
`scripts/verify-real-llm-failure-e2e.py` 会记录并校验真实请求路径。

MiniMax 走的不是 OpenAI 兼容客户端，路径由 MiniMax 自己的 Spring AI 实现决定；
同一个 Chat 注入点、同一个请求体，只换 `LLM_PROVIDER` 配置，业务代码不变。

### Provider 实测矩阵（2026-11-05）

| 能力 | LongCat（`LongCat-2.5`） | MiniMax（`MiniMax-M2.7`） |
|---|---|---|
| 接入协议 | OpenAI 兼容 `apiType=openai-completions` | Anthropic Messages `apiType=anthropic-messages` |
| 实际请求路径 | `POST /openai/v1/chat/completions` | `POST /anthropic/v1/messages` |
| 非流式 Chat | 通过 | 代码路径一致；本次被账户额度阻断（见下） |
| SSE 流式 | 通过（内容非空 + `done` 收口） | 代码路径一致；本次未取得真实回答 |
| AGENT 工具调用 + 引用 | 通过（真实触发检索，带 `sourceHandle`/`citationId`） | **不支持**：`capabilities.tool-calling=false` |
| 429 语义 | 频率限流；有界重试可恢复 | 账户 Token Plan 额度耗尽（`rate_limit_error`，错误码 2056），重试不可恢复 |

MiniMax 这次没有拿到成功样本，原因是真实上游 429：账户 `Token Plan` 用量上限
（`已达到 Token Plan 用量上限：请升级 Token Plan 套餐或购买积分补充用量。 (2056)`），
不是本仓兼容性问题。脚本把这条上游正文经脱敏、截断后写入
`failureDetails.upstreamMessage`，因此能区分“普通限流”和“配额耗尽”，不会笼统报成
同一个 429。充值额度后重跑 `--providers minimax` 即可补齐成功样本；
nightly 的 `real-llm-longcat` 不受 MiniMax 额度影响。

### Fallback 语义与预算边界（2026-11-04 真实进程实测）

候选顺序固定为：显式 `modelRef`（严格，不 fallback）→ `chatModel.primary` →
`chatModel.fallbacks` → legacy 默认模型（若仍配置）。切 fallback 的判定来自
`UpstreamFailureClassifier`：

- **可重试**：上游 5xx / 超时 / 429 等 `UPSTREAM_UNAVAILABLE`、`UPSTREAM_TIMEOUT`、
  `UPSTREAM_RATE_LIMITED`，记录 `reason=... retryable=true` 后进入下一个候选。
- **不可重试**：401/403/404 等凭据或配置错误，记录 `retryable=false` 并直接失败，
  **不会静默切到 fallback**，避免把错误凭据或错误 model 名掩盖成“另一个模型可用”。
- **预算边界**：fallback 不是“免费重试”。每次候选调用都经过
  `ChatExecutionBudget`（`rag.chat.execution.max-candidate-attempts`）和
  `BudgetedChatModel` 的 token/cost 预留；预算拒绝时不会再发起任何候选调用。
- **归账**：每次尝试都写 `rag_llm_usage_event`，包含 `model_ref`、`purpose`、
  `outcome` 与按该模型价格计算的 `configured_cost`。主模型失败行为 `FAILED`，fallback
  成功行为 `SUCCEEDED`，成本不会串账。
- **日志区分**：主失败为
  `Chat candidate N/M failed (<ref>) reason=... retryable=...`，fallback 成功为
  `Chat fallback succeeded: requested=..., resolved=...`。

外部 `models.json` 现在保证在 `ChatModelRouter` / `EmbeddingModelRouter` 初始化之前
加载（`@DependsOn("multiModelConfigLoader")`），不会出现“文件已读取但路由仍用旧的
单 provider 快照”。

可复现证据：`python3 scripts/verify-model-fallback-e2e.py`（需要 Docker 与 Maven，
不在默认快速门禁内）。它用真实进程覆盖：健康主模型、上游 500、上游超时、上游
401/404、候选预算耗尽、token 预算拒绝，以及 usage/cost 归账和日志断言。

### 键名校验（重要）

JSON 键名一律使用 **camelCase**（`chatModel`、`embeddingModel`、`inputModalities`、
`toolCalling`……），与 YAML 的 kebab-case（`chat-model`、`input-modalities`）不同。
加载器会做两档校验：

- **kebab-case 键 → 启动失败**。此类键会整体静默失效（例如 routing 回退 legacy
  默认模型），因此 fail-closed：错误消息会列出违规键的完整路径并给出 camelCase
  建议（如 `chat-model (did you mean 'chatModel'?)`）。
- **未知键 → WARN**。可能是拼写错误或废弃字段；加载继续，日志列出完整路径。

真实事故示例：`"chat-model"` 写法使 providers 正常加载而 routing 静默失效，
chat 流量全部回退到一个已失效的 legacy 端点，最终以 504 超时呈现——
排查记录见加固循环账本 Batch 30/31。
