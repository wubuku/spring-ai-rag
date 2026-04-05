# Multi-Model External Configuration — Sample

> This is a **sample** file showing the external JSON configuration format.
> Place this file outside the JAR (e.g., `/etc/spring-ai/models.json`) and set:
> ```bash
> export MODELS_CONFIG_FILE=/etc/spring-ai/models.json
> # or
> export MODELS_CONFIG_FILE=file:/etc/spring-ai/models.json
> ```

## Key Points

- **Location**: External filesystem (not inside JAR). Use absolute path or `file:` prefix.
- **Overrides YAML**: When this file exists, it **fully replaces** `app.models.*` from `application.yml` (no merge).
- **Model IDs**: Do **NOT** prefix with provider ID (use `"MiniMax-M2.7"`, not `"minimax/MiniMax-M2.7"`).
- **Model References**: Use `providerId/modelId` format (e.g., `"minimax/MiniMax-M2.7"`).

## Format

```json
{
  "models": {
    "providers": {
      "openrouter": {
      "displayName": "OpenRouter",
      "baseUrl": "https://openrouter.ai/api/v1",
      "apiKey": "${OPENROUTER_API_KEY}",
      "apiType": "openai-completions",
      "enabled": true,
      "priority": 1,
      "models": [
        {
          "id": "anthropic/claude-3-5-sonnet-20241022",
          "name": "Claude 3.5 Sonnet",
          "type": "chat",
          "inputModalities": ["text"],
          "cost": { "input": 3, "output": 15, "cacheRead": 0, "cacheWrite": 0 },
          "contextWindow": 200000,
          "maxTokens": 8192
        },
        {
          "id": "google/gemini-pro-1.5",
          "name": "Gemini Pro 1.5",
          "type": "chat",
          "inputModalities": ["text", "image"],
          "cost": { "input": 1.25, "output": 5, "cacheRead": 0, "cacheWrite": 0 },
          "contextWindow": 1000000,
          "maxTokens": 8192
        }
      ]
    },
    "minimax": {
      "displayName": "MiniMax",
      "baseUrl": "https://api.minimaxi.com",
      "apiKey": "${MINIMAX_API_KEY}",
      "apiType": "openai-completions",
      "enabled": true,
      "priority": 2,
      "models": [
        {
          "id": "MiniMax-M2.7",
          "name": "MiniMax M2.7",
          "type": "chat",
          "reasoning": false,
          "inputModalities": ["text"],
          "cost": { "input": 0, "output": 0, "cacheRead": 0, "cacheWrite": 0 },
          "contextWindow": 1000000,
          "maxTokens": 32000
        },
        {
          "id": "embo-01",
          "name": "Embo-01 Embedding",
          "type": "embedding",
          "inputModalities": ["text"],
          "cost": { "input": 0, "output": 0, "cacheRead": 0, "cacheWrite": 0 },
          "contextWindow": 32000,
          "dimension": 1024
        }
      ]
    },
    "siliconflow": {
      "displayName": "SiliconFlow",
      "baseUrl": "https://api.siliconflow.cn/v1",
      "apiKey": "${SILICONFLOW_API_KEY}",
      "apiType": "openai-completions",
      "enabled": true,
      "priority": 3,
      "models": [
        {
          "id": "BGE-M3",
          "name": "BGE-M3 Embedding",
          "type": "embedding",
          "inputModalities": ["text"],
          "cost": { "input": 0, "output": 0, "cacheRead": 0, "cacheWrite": 0 },
          "contextWindow": 8000,
          "dimension": 1024
        }
      ]
    },
    "zhipu": {
      "displayName": "Zhipu AI",
      "baseUrl": "https://open.bigmodel.cn/api/paas/v4",
      "apiKey": "${ZHIPU_API_KEY}",
      "apiType": "openai-completions",
      "enabled": false,
      "priority": 4,
      "models": [
        {
          "id": "GLM-5",
          "name": "GLM-5",
          "type": "chat",
          "inputModalities": ["text"],
          "cost": { "input": 0, "output": 0, "cacheRead": 0, "cacheWrite": 0 },
          "contextWindow": 128000,
          "maxTokens": 4096
        }
      ]
    }
  },
  "chatModel": {
    "primary": "minimax/MiniMax-M2.7",
    "fallbacks": ["openrouter/anthropic/claude-3-5-sonnet-20241022"]
  },
  "embeddingModel": {
    "primary": "siliconflow/BGE-M3",
    "fallbacks": ["minimax/embo-01"]
  }
  }
}
```

## Environment Variable

| Variable | Description | Default |
|----------|-------------|---------|
| `MODELS_CONFIG_FILE` | Absolute path or `file:` URL to external JSON | (empty — YAML only) |

```bash
# Example: load from external path
export MODELS_CONFIG_FILE=/etc/spring-ai/models.json

# Or with file: prefix
export MODELS_CONFIG_FILE=file:/etc/spring-ai/models.json
```
