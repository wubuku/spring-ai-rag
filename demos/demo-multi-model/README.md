# Multi-Model RAG — Demo

> Demonstrates spring-ai-rag's multi-model capabilities: model registry, dynamic routing, and A/B comparison.

## Features

This demo showcases three multi-model usage scenarios:

### 1. Model Registry (ModelRegistry)
View all registered models and their status.

```bash
GET /demo/models
GET /demo/models/{provider}
```

### 2. Specify Model for Q&A
Use the `provider` parameter to select which model answers.

```bash
POST /demo/chat?provider=openai
POST /demo/chat?provider=anthropic
POST /demo/chat?provider=minimax
```

### 3. Model Comparison
Send the same question to multiple models in parallel and compare response quality and performance.

```bash
POST /demo/compare
```

## Quick Start

### 1. Install spring-ai-rag to local Maven repository

```bash
cd ../..
mvn clean install -DskipTests
```

### 2. Configure environment variables

```bash
export DEEPSEEK_API_KEY=sk-xxx          # OpenAI-compatible models (DeepSeek, etc.)
export ANTHROPIC_API_KEY=sk-ant-xxx     # Anthropic Claude
export MINIMAX_API_KEY=eyxxx            # MiniMax Text-01
export SILICONFLOW_API_KEY=sk-xxx      # Embedding Model
```

### 3. Start the demo

```bash
cd demos/demo-multi-model
mvn spring-boot:run
```

### 4. Test the endpoints

```bash
# List all available models
curl http://localhost:8080/demo/models

# Ask using DeepSeek
curl -X POST http://localhost:8080/demo/chat?provider=openai \
  -H "Content-Type: application/json" \
  -d '{"message": "What is RAG?"}'

# Compare multiple models in parallel
curl -X POST http://localhost:8080/demo/compare \
  -H "Content-Type: application/json" \
  -d '{
    "query": "What are the core components of a RAG system?",
    "providers": ["openai", "anthropic"],
    "timeoutSeconds": 30
  }'
```

## Supported Providers

| Provider | Description | API Compatible |
|----------|-------------|----------------|
| `openai` | DeepSeek, Zhipu, other OpenAI-compatible models | ✅ |
| `anthropic` | Anthropic Claude series | ✅ |
| `minimax` | MiniMax Text-01 (no system message support) | ⚠️ Auto-converted |

## API Reference

### GET /demo/models

Returns the model registry information.

**Response Example:**
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

Q&A using a specified model.

**Parameters:**
- `provider` (query): Model provider (default `openai`)
- `message` (body): User question

**Response Example:**
```json
{
  "answer": "RAG (Retrieval-Augmented Generation) is a technique that combines retrieval systems with large language models...",
  "sessionId": null,
  "sources": [],
  "stepMetrics": []
}
```

### POST /demo/compare

Compare multiple models in parallel.

**Request Body:**
```json
{
  "query": "Question content",
  "providers": ["openai", "anthropic"],
  "timeoutSeconds": 30
}
```

**Response Example:**
```json
{
  "query": "What are the core components of a RAG system?",
  "providers": ["openai", "anthropic"],
  "results": [
    {
      "provider": "openai",
      "success": true,
      "response": "Core RAG components include...",
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

## Troubleshooting

### MiniMax returns "invalid message role: system"

MiniMax API does not support `role:system` messages. spring-ai-rag automatically converts system messages to user messages (adding `[System]` prefix).

### Model unavailable

Check that the corresponding Provider's API Key is correctly configured in the environment variables.
