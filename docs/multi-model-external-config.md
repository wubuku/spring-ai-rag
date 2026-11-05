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
- Because this file fully replaces the YAML model registry, a stale local file
  can override the Chat provider and routing even when the normal
  `SPRING_AI_OPENAI_*` variables are correct. Check the startup log and
  `GET /api/v1/rag/models` after changing it. For a direct provider smoke
  without an external registry, unset `MODELS_CONFIG_FILE` or set the explicit
  sentinel `MODELS_CONFIG_FILE=none` (`off`, `disabled` and `-` are accepted
  too). The sentinel makes the startup log print
  `disables the external registry; using YAML config only`.
- The common "I configured LongCat but another provider answered" case is
  exactly this: a checked-in `.dev/models.json` still declares siliconflow as
  primary with an unresolved key placeholder. Use `MODELS_CONFIG_FILE=none` for
  the LongCat path in `scripts/start-real-e2e-server.sh` rather than relying on a
  file being absent by accident.

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

### Measured base-url composition (LongCat, 2026-11-05)

LongCat's OpenAI-compatible base is `https://api.longcat.chat/openai`, configured
**without** `/v1`. Spring AI sends `POST /openai/v1/chat/completions`, i.e. it
always appends a `/v1` segment instead of using the last segment of the base.
Configuring `.../openai/v1` therefore produces `/openai/v1/v1/chat/completions`
and fails. The real E2E harness guards this composition: the proxy in
`scripts/verify-real-llm-failure-e2e.py` records and asserts the actual request
path.

MiniMax does not use the OpenAI-compatible client; its path is determined by the
MiniMax Spring AI implementation. The same Chat injection point and request body
are reused, and only `LLM_PROVIDER` configuration changes — no business code.

### Measured provider matrix (2026-11-05)

| Capability | LongCat (`LongCat-2.5`) | MiniMax (`MiniMax-M2.7`) |
|---|---|---|
| Wire protocol | OpenAI-compatible `apiType=openai-completions` | Anthropic Messages `apiType=anthropic-messages` |
| Actual request path | `POST /openai/v1/chat/completions` | `POST /anthropic/v1/messages` |
| Non-stream chat | Passed | Same code path; this run blocked by account quota |
| SSE streaming | Passed (non-empty content + `done`) | Same code path; no real answer obtained |
| AGENT tool calling + citations | Passed (real retrieval with `sourceHandle`/`citationId`) | **Unsupported**: `capabilities.tool-calling=false` |
| 429 meaning | Rate limit; recoverable with bounded retry | Token Plan quota exhausted (`rate_limit_error`, code 2056); retry cannot help |

MiniMax produced no successful sample this run because the real upstream returned
429 for an exhausted `Token Plan` quota
(`已达到 Token Plan 用量上限：请升级 Token Plan 套餐或购买积分补充用量。 (2056)`),
not because of an incompatibility in this repository. The harness sanitizes and
truncates that upstream body into `failureDetails.upstreamMessage`, which
distinguishes a plain rate limit from quota exhaustion instead of reporting one
generic 429. After topping up the quota, re-run `--providers minimax` to complete
the success sample; the nightly `real-llm-longcat` job is unaffected by MiniMax
quota.

### Fallback Semantics And Budget Boundaries (real-process run, 2026-11-04)

Candidate order is fixed: explicit `modelRef` (strict, no fallback) ->
`chatModel.primary` -> `chatModel.fallbacks` -> the legacy default model when
still configured. `UpstreamFailureClassifier` decides whether to advance:

- **Retryable**: upstream 5xx / timeout / 429, classified as
  `UPSTREAM_UNAVAILABLE`, `UPSTREAM_TIMEOUT`, or `UPSTREAM_RATE_LIMITED`; the log
  records `reason=... retryable=true` and the next candidate is attempted.
- **Non-retryable**: 401/403/404 credential or configuration errors are logged
  as `retryable=false` and fail immediately. They **do not silently switch to a
  fallback**, so a wrong credential or wrong model name cannot be masked as
  "another model happened to work".
- **Budget boundary**: a fallback is not a free retry. Every candidate call
  passes through `ChatExecutionBudget`
  (`rag.chat.execution.max-candidate-attempts`) and `BudgetedChatModel`
  token/cost reservation; when the budget denies, no further candidate call is
  made.
- **Accounting**: every attempt writes a `rag_llm_usage_event` row with
  `model_ref`, `purpose`, `outcome`, and `configured_cost` computed from that
  model's own prices. A failed primary is `FAILED`; a successful fallback is
  `SUCCEEDED`; costs never cross models.
- **Log distinction**: primary failure is
  `Chat candidate N/M failed (<ref>) reason=... retryable=...`; fallback success
  is `Chat fallback succeeded: requested=..., resolved=...`.

External `models.json` is now guaranteed to load before `ChatModelRouter` /
`EmbeddingModelRouter` initialize (`@DependsOn("multiModelConfigLoader")`), so
the "file parsed but routing still uses the old single-provider snapshot" failure
mode cannot recur.

Reproducible evidence: `python3 scripts/verify-model-fallback-e2e.py` (requires
Docker and Maven; not part of the default fast gate). It covers a healthy primary,
upstream 500, upstream timeout, upstream 401/404, candidate-budget exhaustion,
token-budget denial, usage/cost attribution, and log assertions on a real process.

### Key-Name Validation (Important)

JSON keys are always **camelCase** (`chatModel`, `embeddingModel`,
`inputModalities`, `toolCalling`, ...), unlike the kebab-case used in YAML
(`chat-model`, `input-modalities`). The loader applies two levels of
validation:

- **kebab-case keys -> startup failure**. Such a key makes its whole config
  silently no-op (for example, routing falls back to the legacy default
  model), so this fails closed: the error message lists the offending key's
  full path and suggests the camelCase spelling (e.g. `chat-model (did you
  mean 'chatModel'?)`).
- **unknown keys -> WARN**. Possibly a typo or a retired field; loading
  continues and the log lists the full path.

Real incident: writing `"chat-model"` loaded the providers normally while
the routing silently no-op'd, so all chat traffic fell back to a dead legacy
endpoint and finally surfaced as 504 timeouts - see hardening loop ledger
Batches 30/31.
