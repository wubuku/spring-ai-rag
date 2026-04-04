# spring-ai-rag 多模型支持增强方案

> 创建时间：2026-04-04 11:05 GMT+8
> 状态：🚧 实施中
> 参考：OpenClaw models.json 架构 + english-learning agent

---

## 📌 背景与目标

### 现状
当前 `SpringAiConfig` 是**三 Bean 模式**，每次只能激活**一个 ChatModel**：
- `openAiChatModel`（DeepSeek/SiliconFlow 等 OpenAI 兼容）
- `anthropicChatModel`（Anthropic）
- `chatModel`：主入口，通过 `@ConditionalOnMissingBean` 选择

切换靠 `app.llm.provider` 配置，重启生效，无法动态切换。

### 目标
| 能力 | 说明 |
|------|------|
| **多模型共存** | 同时接入 OpenAI / MiniMax / Anthropic / 智谱等多个 provider |
| **运行时路由** | 根据请求参数或配置自动选择目标模型 |
| **Fallback 链** | 主模型失败自动切换到备选模型 |
| **统一配置** | 类似 OpenClaw `models.json` 的声明式模型配置中心 |
| **模型对比** | 与现有 A/B 实验框架整合，支持多模型对比 |

---

## 🏗️ 架构设计

```
┌─────────────────────────────────────────────┐
│           ModelRegistry（模型注册中心）        │
│  providers: openai / minimax / anthropic / zhipu  │
└──────────────────┬──────────────────────────┘
                   │ ChatModel 实例
        ┌──────────┼──────────┬────────────┐
        ▼          ▼          ▼            ▼
  OpenAiChatModel MiniMax   Anthropic   ChatModel
   (DeepSeek等)   ChatModel  ChatModel   (智谱)
                   │
         ┌─────────┴──────────┐
         │   ChatModelRouter │
         │  （根据策略选择模型） │
         └─────────┬──────────┘
                   │ User Request
                   ▼
         ┌─────────────────────┐
         │  FallbackChain      │
         │  （主模型失败自动切换） │
         └─────────────────────┘
```

---

## 📁 目录结构

```
spring-ai-rag-core/src/main/java/com/springairag/core/config/
├── ModelRegistry.java          # 模型注册中心（新增）
├── ChatModelRouter.java        # 动态路由（新增）
├── SpringAiConfig.java         # 改造：注册多个 ChatModel Bean
└── MultiModelProperties.java   # 多模型配置绑定类（新增）

spring-ai-rag-core/src/main/resources/
├── models.json                 # 模型配置中心（新增）
└── application.yml            # 改造：新增 multi-model.* 配置

spring-ai-rag-api/src/main/java/com/springairag/api/dto/
└── ChatRequest.java           # 改造：新增 model 字段
```

---

## 实施阶段

### Phase 1：MiniMax ChatModel 接入 ✅
**目标**：在现有 OpenAI 兼容架构下，接入 MiniMax 模型，跑通链路

**工作内容**：
1. ✅ 确认 `spring-ai-starter-model-minimax` 依赖可用（Spring AI 1.1.4）
2. ✅ 在 `SpringAiConfig` 中注册 `miniMaxChatModel` Bean
3. ✅ `application.yml` 新增 `spring.minimax.*` 配置节点
4. ✅ `.env.example` 添加 MiniMax 配置项
5. ✅ Spring AI 1.1.2 → 1.1.4（MiniMax starter 支持）

**验收标准**：
- ✅ `mvn test` 通过
- API Key：`english-learning` agent `models.json`

**状态**：✅ 2026-04-04 11:09 完成

---

### Phase 2：ModelRegistry 模型注册中心 ✅
**目标**：建立统一的模型注册机制，支持多模型同时注册

**工作内容**：
1. ✅ 新建 `ModelRegistry` 配置类（@Component）
2. ✅ `init()` 自动收集 openai/anthropic/minimax Bean
3. ✅ `get(provider)` / `getDefault()` / `availableProviders()` / `getAllModelsInfo()`
4. ✅ `ModelRegistryTest`：10 个纯 Mock 单元测试

**状态**：✅ 2026-04-04 11:14 完成

---

### Phase 3：models.json 配置中心 ⏭️ 跳过
**目标**：将模型配置外部化，支持声明式配置

**决策**：直接使用现有 `application.yml` 的 `app.multi-model.*` 配置，更简洁。

**状态**：⏭️ 跳过（使用 application.yml 替代）

---

### Phase 4：ChatModelRouter 动态路由 ✅
**目标**：支持请求级别动态选择模型

**工作内容**：
1. ✅ 新建 `ChatModelRouter` 组件
2. ✅ `getModel(providerHint)` / `getNextFallback(failedProvider)`
3. ✅ `app.multi-model.enabled=false` 默认关闭，逐步启用
4. ✅ `ChatModelRouterTest`：9 个纯 Mock 单元测试

**状态**：✅ 2026-04-04 11:24 完成

---

### Phase 5：REST 端点（GET /models） ✅
**目标**：暴露模型列表和状态管理 API

**工作内容**：
1. ✅ 新建 `ModelController`
2. ✅ `GET /api/v1/rag/models` - 列出所有模型（含开关状态）
3. ✅ `GET /api/v1/rag/models/{provider}` - 查看指定 provider 详情
4. ✅ `ModelControllerTest`：3 个 @WebMvcTest 单元测试

**状态**：✅ 2026-04-04 11:28 完成

---

### Phase 6：指标埋点 + 监控 ⏳ 待实施
**目标**：量化各模型的调用情况

**工作内容**：
1. ChatModelRouter 记录各 provider 的调用次数、延迟、错误率
2. 复用现有 `RagMetricsService` 埋点
3. 新增 `/api/v1/rag/metrics/models` 端点查看各模型状态

**指标**：
```
model_calls_total{provider="minimax"}
model_latency_seconds{provider="minimax"}
model_errors_total{provider="minimax"}
```

**状态**：⏳ 待实施

---

### Phase 7：与 A/B Test 框架整合 ⏳ 待实施
**目标**：利用现有 `AbTestService` 实现多模型对比实验

**工作内容**：
1. 扩展 `AbTestExperiment` 支持 `model_providers` 列表
2. `ModelComparisonService`（已有）重构为使用 `ChatModelRouter`
3. A/B 实验可配置多个 provider，系统自动并行查询并记录结果

**状态**：⏳ 待实施

---

## API 变更

### 新增端点
| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/rag/models` | 查看可用模型列表 |
| GET | `/api/v1/rag/models/{provider}` | 查看指定 provider 详情 |
| GET | `/api/v1/rag/metrics/models` | 查看各模型调用指标 |

### 改造端点
| 方法 | 路径 | 变更 |
|------|------|------|
| POST | `/api/v1/rag/chat` | 新增 `model` 查询参数 |

---

## 配置变更

### application.yml 新增
```yaml
app:
  multi-model:
    enabled: true
    default-provider: ${APP_LLM_PROVIDER:openai}
    fallback-chain:
      - openai
      - minimax
      - zhipu
```

### models.json（新增）
外部化模型配置，支持多 provider 声明式配置。

---

## 测试策略

| 层级 | 工具 | 要求 |
|------|------|------|
| 单元测试 | JUnit 5 | ModelRegistry / ChatModelRouter / MultiModelProperties |
| 集成测试 | @SpringBootTest | 真实数据库 + Mock ChatModel |
| E2E 测试 | Shell + curl | 各 provider 完整调用链路 |

---

## 风险与依赖

| 风险 | 缓解措施 |
|------|----------|
| MiniMax starter 与 Spring AI 1.1.x 兼容性 | 先在 demo 模块验证，再集成 core |
| 多模型并发调用性能下降 | Router 加本地缓存，指标异步上报 |
| API Key 安全 | 通过 `${VAR}` 引用环境变量，不硬编码 |

---

## 进度日志

| 时间 | 阶段 | 内容 |
|------|------|------|
| 2026-04-04 11:05 | - | 方案创建 |
| 2026-04-04 11:09 | Phase 1 ✅ | MiniMax ChatModel 支持：Spring AI 1.1.2→1.1.4，添加 spring-ai-starter-model-minimax，miniMaxChatModel Bean |
| 2026-04-04 11:14 | Phase 2 ✅ | ModelRegistry 模型注册中心：自动收集所有 ChatModel Bean，提供统一访问接口，10 个单元测试 |
| 2026-04-04 11:24 | Phase 4 ✅ | ChatModelRouter 动态路由：请求级模型选择，FallbackChain，9 个单元测试 |
| 2026-04-04 11:27 | 🚧 推进中 | Phase 5：REST 端点（GET /models）|
