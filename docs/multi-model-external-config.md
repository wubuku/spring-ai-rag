# Multi-Model External Configuration

> 📖 [English](multi-model-external-config.md) · 📖 [中文](multi-model-external-config-zh-CN.md)

Use an external `models.json` when model inventory and credentials must be
managed outside the application JAR.

## Enable

```bash
export MODELS_CONFIG_FILE=/etc/spring-ai/models.json
# `file:` URIs are also supported:
export MODELS_CONFIG_FILE=file:/etc/spring-ai/models.json
```

- The path must be absolute or a valid `file:` URI.
- A successfully loaded file fully replaces `app.models.*`; it is not merged
  with YAML.
- A missing or invalid file leaves the YAML configuration active.
- OpenAI-compatible `baseUrl` values must not end in `/v1`.
- A model ID is the provider-native ID. Do not prepend the local provider ID.
  Requests use `providerId/modelId`, such as
  `openrouter/xiaomi/mimo-v2-pro`.

## Complete Example

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

Supported chat `apiType` values are `openai`, `openai-chat`,
`openai-completions`, `anthropic`, and `anthropic-messages`.

`capabilities.streaming` is backward-compatible and defaults to `true` when
omitted. `capabilities.toolCalling` is conservative and defaults to `false`.
Set Tool Calling to `true` only after verifying that the concrete
provider/model accepts tool schemas and returns tool calls through the selected
API type. Spring AI adapter support alone is not sufficient evidence.

## Verify

After startup, query the effective registry:

```bash
curl http://localhost:8081/api/v1/rag/models
```

The response includes `defaultModel`, model-level `ref` values, normalized
`capabilities`, availability, and an `unavailableReason` when credentials or
provider settings are missing. `AGENT` Chat requires
`capabilities.toolCalling=true`.
