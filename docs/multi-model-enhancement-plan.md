# spring-ai-rag 多模型支持增强方案

> 创建时间：2026-04-04 11:05 GMT+8
> 更新时间：2026-04-05 21:51 GMT+8
> 状态：📋 待审批后实施

---

## 📌 背景与目标

### 现状
- 当前 `SpringAiConfig` 是**三 Bean 模式**，每次只能激活**一个 ChatModel**（通过 `@ConditionalOnMissingBean` 切换 provider）
- 切换靠 `app.llm.provider` 配置，重启生效，无法动态切换
- 曾实现过多模型配置，但因架构问题回退了

### 目标
| 能力 | 说明 |
|------|------|
| **多模型共存** | 同时接入 OpenAI / MiniMax / Anthropic / 智谱等多个 provider |
| **外部化配置** | 通过 JSON 文件动态加载模型配置，优先级高于 YAML |
| **Fallback 链** | 主模型失败自动切换到备选模型 |
| **Embedding 模型** | 同时支持 chat 模型和 embedding 模型的路由与 fallback |
| **统一配置格式** | YAML 和 JSON 使用相同结构，仅 naming case 不同 |

---

## 🗂️ 配置格式设计

### 核心概念

| 概念 | 说明 |
|------|------|
| **Provider** | 模型供应商，如 openrouter / minimax / zhipu / volces |
| **Model** | 具体模型（chat 或 embedding），ID 在 provider 内唯一 |
| **模型引用格式** | `providerId/modelId`，如 `minimax/MiniMax-M2.7` |
| **模型 ID 格式** | 不带 provider 前缀，如 `MiniMax-M2.7`、`bge-m3` |

### JSON 外部化配置（核心参考格式）

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
            "id": "xiaomi/mimo-v2-pro",
            "name": "MiMo V2 Pro",
            "type": "chat",
            "reasoning": false,
            "inputModalities": ["text"],
            "cost": { "input": 0, "output": 0, "cacheRead": 0, "cacheWrite": 0 },
            "contextWindow": 600000,
            "maxTokens": 32000
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
            "cost": { "input": 15, "output": 60, "cacheRead": 2, "cacheWrite": 10 },
            "contextWindow": 200000,
            "maxTokens": 8192
          },
          {
            "id": "embo-01",
            "name": "Embedding V01",
            "type": "embedding",
            "inputModalities": ["text"],
            "dimension": 1024,
            "cost": { "input": 0, "output": 0 }
          }
        ]
      },
      "zhipu": {
        "displayName": "智谱 GLM-5",
        "baseUrl": "https://open.bigmodel.cn/api/anthropic",
        "apiKey": "${ZHIPU_API_KEY}",
        "apiType": "anthropic-messages",
        "enabled": true,
        "priority": 3,
        "models": [
          {
            "id": "GLM-5",
            "name": "GLM-5",
            "type": "chat",
            "reasoning": true,
            "inputModalities": ["text"],
            "cost": { "input": 5, "output": 15, "cacheRead": 0, "cacheWrite": 0 },
            "contextWindow": 131072,
            "maxTokens": 8192
          }
        ]
      },
      "volces": {
        "displayName": "字节豆包",
        "baseUrl": "https://ark.cn-beijing.volces.com/api/coding",
        "apiKey": "${VOLCES_API_KEY}",
        "apiType": "anthropic-messages",
        "enabled": true,
        "models": [
          {
            "id": "Doubao-Seed-2.0-pro",
            "name": "Doubao Seed 2.0 Pro",
            "type": "chat",
            "inputModalities": ["text"],
            "cost": { "input": 0, "output": 0 },
            "contextWindow": 256000,
            "maxTokens": 16000
          }
        ]
      },
      "siliconflow": {
        "displayName": "SiliconFlow",
        "baseUrl": "https://api.siliconflow.cn/v1",
        "apiKey": "${SILICONFLOW_API_KEY}",
        "apiType": "openai-chat",
        "enabled": true,
        "models": [
          {
            "id": "BGE-M3",
            "name": "BGE-M3 Embedding",
            "type": "embedding",
            "inputModalities": ["text"],
            "dimension": 1024,
            "cost": { "input": 0, "output": 0 }
          }
        ]
      }
    },

    "chatModel": {
      "primary": "minimax/MiniMax-M2.7",
      "fallbacks": ["openrouter/xiaomi/mimo-v2-pro", "zhipu/GLM-5"]
    },

    "embeddingModel": {
      "primary": "siliconflow/BGE-M3",
      "fallbacks": []
    }
  }
}
```

### YAML 配置格式（与 JSON 结构完全一致，仅 snake_case）

```yaml
app:
  models:
    config-file: /path/to/models.json   # 可选，有此文件时覆盖 YAML

    providers:
      openrouter:
        displayName: "OpenRouter"
        baseUrl: "https://openrouter.ai/api/v1"
        apiKey: ${OPENROUTER_API_KEY}
        apiType: openai-completions
        enabled: true
        priority: 1
        models:
          - id: xiaomi/mimo-v2-pro
            name: MiMo V2 Pro
            type: chat
            reasoning: false
            inputModalities: [text]
            cost:
              input: 0
              output: 0
            contextWindow: 600000
            maxTokens: 32000

      minimax:
        displayName: "MiniMax"
        baseUrl: https://api.minimaxi.com/anthropic
        apiKey: ${MINIMAX_API_KEY}
        apiType: anthropic-messages
        enabled: true
        priority: 2
        models:
          - id: MiniMax-M2.7
            name: MiniMax M2.7
            type: chat
            reasoning: false
            inputModalities: [text]
            cost:
              input: 15
              output: 60
              cacheRead: 2
              cacheWrite: 10
            contextWindow: 200000
            maxTokens: 8192
          - id: embo-01
            name: Embedding V01
            type: embedding
            inputModalities: [text]
            dimension: 1024

      siliconflow:
        displayName: SiliconFlow
        baseUrl: https://api.siliconflow.cn/v1
        apiKey: ${SILICONFLOW_API_KEY}
        apiType: openai-chat
        enabled: true
        models:
          - id: BGE-M3
            name: BGE-M3
            type: embedding
            dimension: 1024

    chatModel:
      primary: minimax/MiniMax-M2.7
      fallbacks:
        - openrouter/xiaomi/mimo-v2-pro
        - zhipu/GLM-5

    embeddingModel:
      primary: siliconflow/BGE-M3
      fallbacks: []
```

### 优先级规则

| 来源 | 优先级 | 说明 |
|------|--------|------|
| 外部 JSON 文件 | **最高** | `app.models.config-file` 指定路径 |
| application.yml | 最低 | 默认配置 |

**当外部 JSON 文件存在时，完全覆盖 YAML 配置（不支持合并）**

---

## 🏗️ 架构设计

```
┌─────────────────────────────────────────────────┐
│      MultiModelProperties + MultiModelConfigLoader │
│   绑定 app.models.* 或外部 models.json          │
└──────────────────────┬──────────────────────────┘
                       │
            ┌──────────▼──────────┐
            │   ModelRegistry    │
            │ providers: Map<String, ProviderConfig> │
            └──────────┬──────────┘
                       │
      ┌────────────────┼────────────────┐
      ▼                ▼                ▼
┌──────────┐    ┌──────────┐    ┌──────────┐
│ Provider1 │    │ Provider2 │    │ Provider3 │
│ (openrouter)│    │ (minimax) │    │ (zhipu)   │
│            │    │            │    │            │
│ models:   │    │ models:   │    │ models:   │
│ [ModelItem,│    │ [ModelItem,│    │ [ModelItem,│
│  ModelItem]│    │  ModelItem]│    │  ModelItem]│
└──────────┘    └──────────┘    └──────────┘
                       │
            ┌──────────▼──────────┐
            │  ModelRouter       │
            │ primary → fallbacks│
            │ (providerId/modelId format) │
            └───────────────────┘
```

**关键点**：
- 每个 Provider 的 `models[]` 数组**混合存储** chat 模型和 embedding 模型
- 路由使用 `providerId/modelId` 格式引用模型
- ModelRegistry 根据模型引用解析出 provider 和 modelId，再到对应 ProviderConfig 中查找

---

## 📁 目录结构

```
spring-ai-rag-core/src/main/java/com/springairag/core/config/
├── MultiModelProperties.java          # 配置绑定（@ConfigurationProperties）
├── MultiModelConfigLoader.java         # JSON 外部配置加载器（新增）
├── ModelRegistry.java                  # 模型注册中心
├── ChatModelRouter.java              # 动态路由（已有，改造）
└── SpringAiConfig.java               # 改造：支持多 ChatModel Bean

spring-ai-rag-core/src/main/resources/
├── models.json                        # 外部化模型配置（新增，可选）
└── application.yml                   # 改造：新增 app.models.* 配置

spring-ai-rag-api/src/main/java/com/springairag/api/dto/
├── ProviderModel.java                 # 单个 provider 模型配置（Record，新增）
├── ModelProviderConfig.java           # provider 配置（Record，新增）
└── ChatRequest.java                 # 改造：新增 model 参数
```

---

## 🔧 核心类型设计（Java Record）

```java
// 单个模型的 cost 信息
public record ModelCost(
    double input,
    double output,
    double cacheRead,
    double cacheWrite
) {}

// 单个模型的配置（chat 和 embedding 混合）
public record ModelItem(
    String id,                    // 模型 ID，不含 provider 前缀，如 "MiniMax-M2.7"
    String name,                  // 人类可读名称
    String type,                  // "chat" 或 "embedding"
    boolean reasoning,             // 仅 chat 模型
    List<String> inputModalities, // ["text"] 等
    ModelCost cost,
    Integer contextWindow,         // 仅 chat 模型
    Integer maxTokens,            // 仅 chat 模型
    Integer dimension             // 仅 embedding 模型
) {}

// 单个 Provider 的完整配置
public record ProviderConfig(
    String displayName,
    String baseUrl,
    String apiKey,
    String apiType,               // openai-completions | anthropic-messages | openai-chat
    boolean enabled,
    Integer priority,
    List<ModelItem> models      // 混合 chat 和 embedding 模型
) {}

// 路由配置（引用格式为 "providerId/modelId"）
public record ModelRouting(
    String primary,               // 如 "minimax/MiniMax-M2.7"
    List<String> fallbacks       // 按顺序尝试的备用模型
) {}

// 顶层多模型配置
public record MultiModelConfig(
    Map<String, ProviderConfig> providers,  // key = provider ID
    ModelRouting chatModel,
    ModelRouting embeddingModel
) {}
```

**YAML 配置绑定类**（`@ConfigurationProperties`）：

```java
@ConfigurationProperties(prefix = "app.models")
public class MultiModelProperties {
    private String configFile;                              // 外部 JSON 路径
    private Map<String, ProviderConfig> providers = new HashMap<>();
    private ModelRouting chatModel;
    private ModelRouting embeddingModel;
    // getters & setters
}
```

---

## 实施阶段

### Phase M1：MultiModelProperties 配置绑定（1 cron）
**目标**：建立多模型配置的基础绑定类，支持 YAML 和 JSON 双格式

**工作内容**：
1. 新建 `MultiModelProperties.java`（`@ConfigurationProperties(prefix = "app.models")`）
2. 新建 `ProviderModel.java`、`ChatModelConfig.java`、`EmbeddingModelConfig.java`、`ModelCost.java`、`ModelRoutingConfig.java` 等 Record
3. 新建 `MultiModelConfigLoader.java`：检测并加载外部 `models.json`（优先级高于 YAML）
4. `application.yml` 新增 `app.models.*` 配置节点
5. 改造 `SpringAiConfig`：不再使用 `@ConditionalOnMissingBean`，改为显式注册所有 provider

**验收标准**：
- `mvn compile` 通过
- YAML 配置可正确绑定到 `MultiModelProperties`

### Phase M2：ModelRegistry 重构（1 cron）
**目标**：将 ModelRegistry 改造为基于 `MultiModelProperties` 而非 Spring Bean

**工作内容**：
1. 改造 `ModelRegistry`，从 `MultiModelProperties.providers` 获取模型信息
2. `availableProviders()` / `getProvider(name)` / `getPrimaryChatModel()` / `getPrimaryEmbeddingModel()`
3. 支持 `app.models.config-file` 指定外部 JSON 路径
4. 当外部 JSON 存在时，`MultiModelConfigLoader` 覆盖 YAML 配置
5. 单元测试覆盖 JSON 加载和 YAML 覆盖逻辑

**验收标准**：
- `mvn test` 通过
- JSON 文件存在时，YAML 配置被完全覆盖

### Phase M3：ChatModelRouter + Fallback 链（1 cron）
**目标**：实现主模型 + 备用模型的自动切换

**工作内容**：
1. 改造 `ChatModelRouter.getChatModel(providerHint)`：支持 `primary → fallbacks` 链
2. 改造 `EmbeddingModelRouter.getEmbeddingModel()`：同上
3. 当主模型调用抛出异常时，自动切换到下一个 fallback
4. `ChatModelRouterTest`：覆盖 primary/fallback 切换路径

**验收标准**：
- 主模型失败时正确 fallback
- Fallback 全部失败时抛出明确错误

### Phase M4：Embedding 模型多模型支持（1 cron）
**目标**：Embedding 模型也支持多 provider + fallback（已内含于 M1~M3 的 `models[]` 数组中）

**工作内容**：
1. embedding 模型无需单独处理，已在 `models[]` 数组中统一配置
2. 只需在 `embeddingModel.primary/fallbacks` 中引用 `providerId/modelId` 即可
3. 单元测试覆盖 embedding fallback 路径

### Phase M5：与现有组件整合（1 cron）
**目标**：确保 ModelComparisonService、AbTestService 等现有组件兼容新架构

**工作内容**：
1. 确认 `ModelComparisonService.compareProviders()` 仍可正常工作
2. 确认 A/B Test 实验中的多模型对比不受影响
3. 确认 `GET /api/v1/rag/models` 端点返回正确信息
4. 运行完整测试套件

### Phase M6：E2E 验证（WebUI cron 执行）
**目标**：用 Playwright E2E 验证多模型配置正常工作

**工作内容**：
1. 启动后端服务（带外部 `models.json`）
2. Playwright E2E 测试：调用 `/api/v1/rag/chat` 使用不同模型
3. 验证 fallback 链路工作正常

---

## 配置变更

### application.yml（新增）

```yaml
app:
  models:
    # 外部 JSON 配置文件路径（可选，不填则只用 YAML 配置）
    # 当此文件存在时，完全覆盖下方 YAML 配置
    config-file: /path/to/models.json

    providers:
      openrouter:
        displayName: "OpenRouter"
        baseUrl: "https://openrouter.ai/api/v1"
        apiKey: ${OPENROUTER_API_KEY}
        apiType: openai-completions
        enabled: true
        chatModel:
          modelId: "xiaomi/mimo-v2-pro"
          maxTokens: 32000
          temperature: 0.7
        cost:
          input: 0
          output: 0

      minimax:
        displayName: "MiniMax"
        baseUrl: "https://api.minimaxi.com/anthropic"
        apiKey: ${MINIMAX_API_KEY}
        apiType: anthropic-messages
        enabled: true
        chatModel:
          modelId: "minimax/MiniMax-M2.7"
          maxTokens: 8192
          temperature: 0.7
        embeddingModel:
          modelId: "embo-01"
          dimension: 1024
        cost:
          input: 15
          output: 60
          cacheRead: 2
          cacheWrite: 10

      zhipu:
        displayName: "智谱 GLM-5"
        baseUrl: "https://open.bigmodel.cn/api/anthropic"
        apiKey: ${ZHIPU_API_KEY}
        apiType: anthropic-messages
        enabled: true
        chatModel:
          modelId: "zhipu/GLM-5"
          maxTokens: 8192
        cost:
          input: 5
          output: 15

      volces:
        displayName: "字节豆包"
        baseUrl: "https://ark.cn-beijing.volces.com/api/coding"
        apiKey: ${VOLCES_API_KEY}
        apiType: anthropic-messages
        enabled: true
        chatModel:
          modelId: "volces/doubao-seed-2.0-pro"
          maxTokens: 16000
        cost:
          input: 0
          output: 0

    chatModel:
      primary: minimax
      fallbacks:
        - openrouter
        - zhipu

    embeddingModel:
      primary: siliconflow
      fallbacks: []
```

### 外部 models.json（可选）

放在 `spring-ai-rag-core/src/main/resources/models.json` 或任意路径：

```bash
# 挂载到容器内
-v /opt/spring-ai-rag/models.json:/app/config/models.json
```

配置项：
```yaml
app:
  models:
    config-file: /app/config/models.json
```

---

## 测试策略

| 层级 | 工具 | 要求 |
|------|------|------|
| 单元测试 | JUnit 5 | MultiModelProperties / MultiModelConfigLoader / ChatModelRouter |
| 集成测试 | @SpringBootTest | 真实 JSON 加载 + YAML 覆盖 |
| E2E 测试 | Playwright | 多模型 chat 调用 + fallback |

---

## 风险与缓解

| 风险 | 缓解措施 |
|------|----------|
| JSON 覆盖 YAML 后难以调试 | 日志中打印 `"Using external models.json, YAML config overridden"` |
| 多模型并发初始化性能 | 延迟初始化（首次使用时才创建 ChatModel 实例） |
| API Key 安全 | 通过 `${VAR}` 环境变量引用，不硬编码 |
| embedding fallback 未测试 | Phase M4 专项覆盖 |

---

## 进度日志

| 时间 | 阶段 | 内容 |
|------|------|------|
| 2026-04-05 21:51 | - | 方案重构：增加外部 JSON 配置支持，统一 YAML/JSON 配置格式 |
