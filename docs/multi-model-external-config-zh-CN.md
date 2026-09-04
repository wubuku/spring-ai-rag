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
        "apiKey": "${SILICONFLOW_API_KEY}",
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
