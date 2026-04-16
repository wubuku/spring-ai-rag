# Multi-Model RAG — 演示

> 展示 spring-ai-rag 的多模型能力：模型注册、动态路由、A/B 对比。

## 功能说明

本演示展示三种多模型使用场景：

### 1. 模型注册中心（ModelRegistry）
查看所有已注册模型及其状态。

```bash
GET /demo/models
GET /demo/models/{provider}
```

### 2. 指定模型问答
通过 `provider` 参数指定使用哪个模型回答。

```bash
POST /demo/chat?provider=openai
POST /demo/chat?provider=anthropic
POST /demo/chat?provider=minimax
```

### 3. 模型效果对比（Model Comparison）
将同一问题并行发送给多个模型，对比回答质量和性能。

```bash
POST /demo/compare
```

## 快速开始

### 1. 安装 spring-ai-rag 到本地仓库

```bash
cd ../..
mvn clean install -DskipTests
```

### 2. 配置环境变量

```bash
export DEEPSEEK_API_KEY=sk-xxx          # OpenAI 兼容模型（DeepSeek 等）
export ANTHROPIC_API_KEY=sk-ant-xxx     # Anthropic Claude
export MINIMAX_API_KEY=eyxxx            # MiniMax Text-01
export SILICONFLOW_API_KEY=sk-xxx      # 嵌入模型
```

### 3. 启动演示

```bash
cd demos/demo-multi-model
mvn spring-boot:run
```

### 4. 测试端点

```bash
# 查看所有可用模型
curl http://localhost:8080/demo/models

# 使用 DeepSeek 回答
curl -X POST http://localhost:8080/demo/chat?provider=openai \
  -H "Content-Type: application/json" \
  -d '{"message": "什么是 RAG？"}'

# 并行对比多个模型
curl -X POST http://localhost:8080/demo/compare \
  -H "Content-Type: application/json" \
  -d '{
    "query": "RAG 系统的核心组件有哪些？",
    "providers": ["openai", "anthropic"],
    "timeoutSeconds": 30
  }'
```

## 支持的 Provider

| Provider | 说明 | API 兼容 |
|----------|------|---------|
| `openai` | DeepSeek、智谱等 OpenAI 兼容模型 | ✅ |
| `anthropic` | Anthropic Claude 系列 | ✅ |
| `minimax` | MiniMax Text-01（不支持 system 消息） | ⚠️ 自动转换 |

## API 参考

### GET /demo/models

返回模型注册中心信息。

**响应示例：**
```json
{
  "availableProviders": ["openai", "anthropic"],
  "models": [
    {
      "provider": "openai",
      "displayName": "DeepSeek V3",
      "available": true
    },
    {
      "provider": "anthropic",
      "displayName": "Claude 3.5 Sonnet",
      "available": true
    }
  ]
}
```

### POST /demo/chat

使用指定模型进行问答。

**参数：**
- `provider`（query）：模型提供者（默认 `openai`）
- `message`（body）：用户问题

**响应示例：**
```json
{
  "answer": "RAG（检索增强生成）是一种结合检索系统和大型语言模型的技术...",
  "sessionId": null,
  "sources": [],
  "stepMetrics": []
}
```

### POST /demo/compare

并行对比多个模型。

**请求体：**
```json
{
  "query": "问题内容",
  "providers": ["openai", "anthropic"],
  "timeoutSeconds": 30
}
```

**响应示例：**
```json
{
  "query": "RAG 系统的核心组件有哪些？",
  "providers": ["openai", "anthropic"],
  "results": [
    {
      "provider": "openai",
      "success": true,
      "response": "RAG 核心组件包括...",
      "latencyMs": 150,
      "promptTokens": 100,
      "completionTokens": 50,
      "totalTokens": 150,
      "error": null
    },
    {
      "provider": "anthropic",
      "success": true,
      "response": "A RAG system consists of...",
      "latencyMs": 200,
      "promptTokens": 120,
      "completionTokens": 80,
      "totalTokens": 200,
      "error": null
    }
  ]
}
```

## 故障排除

### MiniMax 返回 "invalid message role: system"

MiniMax API 不支持 `role:system` 消息。spring-ai-rag 会自动将 system 消息转为 user 消息（添加 `[System]` 前缀）。

### 模型不可用

检查对应 Provider 的 API Key 是否正确配置在环境变量中。
