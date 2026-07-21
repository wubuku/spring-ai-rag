# OpenAI Chat Completions 兼容 RAG 服务实施规划

> 状态：待评审、待批准，尚未开始实施
> 起草日期：2026-07-21
> 适用基线：本规划起草时的当前工作树；工作树包含尚未提交的多模型、Collection ACL 等改动
> 目标接口：`POST /v1/chat/completions`、`GET /v1/models`、`GET /v1/models/{id}`
> 实施约束：未经批准不得修改生产代码；实施时必须同步补测试、文档并通过项目既有 E2E 门禁
> 相关旧稿：[API-KEY-MANAGEMENT-PLAN.md](API-KEY-MANAGEMENT-PLAN.md) 解释了当前内部实现
> 的来源；涉及外部服务的 bootstrap、委派、secret 传输、轮换和 WebUI 安全决策，以本文
> 第 12 节为准。

## 1. 决策摘要

### 1.1 建议

**建议实施，但应把它定义为一个可选、默认关闭的协议适配层，而不是重写现有 RAG API。**

它的最大价值不是少写一个 Controller，也不是把当前 SSE 包一层 JSON，而是：

> **把“带检索策略、知识库范围、领域 Prompt 和模型路由的 RAG deployment”包装成标准 `model`，让现有 OpenAI SDK、Agent 框架、IDE 和网关无需专用 RAG 适配器即可接入。**

但“可被 SDK 调用”不等于“服务基本可用”。本规划把数据库 API Key principal、最小权限
policy、吊销/轮换和 per-key quota 设为同等重要的上线前置条件；没有这层身份与授权，
`/v1` 只能算协议 demo，不能作为外部服务开放。

例如，调用方只需要配置：

```python
from openai import OpenAI

client = OpenAI(
    base_url="http://localhost:8081/v1",
    api_key="rag_sk_xxx",
)

response = client.chat.completions.create(
    model="rag-default",
    messages=[
        {"role": "user", "content": "公司的退款规则是什么？"}
    ],
)
```

这里的 `rag-default` 不应只是底层 LLM 名称，而应是一个 RAG deployment/profile：

```text
rag-default
  ├─ 主模型及 fallback
  ├─ domainId / 服务端系统提示词
  ├─ 默认 Collection 范围
  ├─ 最大召回数
  ├─ Query Rewrite / Rerank 策略
  ├─ 是否允许请求覆盖
  └─ 是否允许服务端会话记忆
```

### 1.2 对 Agent / Subagent 的实际意义

兼容接口可以让外部编排器把本项目当作一个“知识型模型端点”：

```text
主 Agent
  ├─ 通用推理模型
  ├─ rag-legal     -> 法务知识 RAG deployment
  ├─ rag-support   -> 客服知识 RAG deployment
  └─ rag-product   -> 产品文档 RAG deployment
```

这适合：

- 把不同知识域配置成不同 subagent/model。
- 使用已有 OpenAI 客户端的重试、超时、流式消费和可观测能力。
- 让 API Gateway、评测平台和模型代理把 RAG 服务纳入统一模型目录。
- 在不暴露内部 Collection、Advisor 和 provider 细节的前提下提供稳定边界。

但需要明确：

- Chat Completions 兼容层本身**不是 Agent 编排器**。
- MVP 不实现 tool calling、函数调用、多模态或内部 agent loop。
- 如果目标是“让主 Agent 把检索作为工具调用”，MCP 或后续 `/v1/responses` 会比伪装成模型更自然。
- 本规划优先解决“RAG-as-a-model endpoint”；Agent 编排保持在调用方或独立编排模块。

### 1.3 有条件 Go 的原因

当前代码具备可复用基础：

- 已有完整 Advisor 链和 RAG 执行服务。
- `AdvisorUtils.extractUserMessage()` 已能从完整消息列表中提取最后一个非空用户消息。
- Spring AI 1.1.4 的 `ChatClientRequestSpec` 支持 `messages(List<Message>)` 和 `options(ChatOptions)`。
- `ChatClient.StreamResponseSpec` 支持 `Flux<ChatClientResponse>`，可取得流式元数据而不只是一串文本。
- `ChatResponseMetadata` 和 `Usage` 已能提供模型 ID、prompt/completion/total token。
- 当前工作树已有按 `provider/modelId` 创建、缓存和列举模型的能力。
- Collection ACL 已有可复用的核心规则。

但不能直接复用当前 Controller 输出并宣称兼容，因为：

- 当前请求只有单个 `message`，不是 `messages[]`。
- 当前 SSE 缺少标准 chunk 的关键字段和 `[DONE]` 终止符。
- 当前 `/v1/*` 会绕过仅注册到 `/api/*` 的鉴权、限流和 CORS。
- 当前错误格式是 RFC 7807/项目自定义格式，不是 OpenAI error envelope。
- 当前服务始终安装服务端 ChatMemory，与调用方完整提交历史的 Chat Completions 语义冲突。
- 当前流式与非流式在 fallback、审计历史和结果元数据上不对称。

## 2. 范围与非目标

### 2.1 MVP 范围

MVP 实现以下能力：

1. `POST /v1/chat/completions`
2. `GET /v1/models`
3. `GET /v1/models/{model}`
4. `Authorization: Bearer <database-backed-api-key>`
5. 文本 `system`、`developer`、`user`、`assistant` 消息
6. 字符串 content，以及只包含 text part 的 content 数组
7. 非流式 Chat Completion
8. 标准 SSE Chat Completion Chunk 和 `data: [DONE]`
9. `n=1`
10. 常用、provider-neutral 的生成参数
11. 模型名到 RAG deployment/profile 的解析
12. Collection ACL、限流、追踪和审计
13. 可选的 RAG 扩展字段，但标准客户端不使用扩展也能完成调用
14. 现有 `/api/v1/rag/chat/**` 对外契约保持不变

### 2.2 明确不在 MVP 中

- `/v1/responses`
- Assistants API
- Batch API
- tool calling / function calling
- MCP server
- 图片、音频、文件等多模态消息
- `n > 1`
- logprobs
- structured output / JSON Schema 强约束输出
- 音频输出
- OpenAI 服务端 `store=true` 语义
- OpenAI 托管 conversation、message、response 资源
- 内置 Agent loop、planner、tool executor 或 subagent 调度器
- 把任意底层 LLM 名称直接暴露成可绕过 RAG policy 的公共 model

### 2.3 后续路线

建议按以下顺序扩展：

1. Chat Completions 文本兼容 MVP。
2. 根据真实客户端使用情况增加 tool calling 透传或明确保持拒绝。
3. 增加 `/v1/responses`，承接新式 OpenAI Agent/Responses 生态。
4. 单独提供 MCP retrieval tool，让 Agent 可显式调用搜索而不是只能调用完整 RAG 回答。
5. 如兼容层形成独立发布需求，再从 core 中拆分为可选 adapter 模块。

## 3. 术语与边界

### 3.1 两种“OpenAI 兼容”不能混淆

项目当前已经支持的是：

```text
spring-ai-rag -> OpenAI-compatible provider
```

即本项目作为客户端，调用 OpenAI、OpenRouter、SiliconFlow 等兼容上游。

本规划新增的是：

```text
OpenAI client / Agent -> spring-ai-rag
```

即本项目作为服务端，接收 OpenAI Chat Completions 协议。

两者共用“OpenAI compatible”名称，但方向、DTO、鉴权、错误处理和模型语义完全不同。

### 3.2 Model 与 Deployment

本规划采用以下定义：

- **backend model**：实际调用的 provider/model，例如 `minimax/MiniMax-M2.7`。
- **RAG deployment/profile**：面向客户端的稳定模型 ID，例如 `rag-legal`。
- **requested model**：Chat Completions 请求体中的 `model`，其值必须解析为 RAG deployment。
- **resolved backend model**：某次调用实际成功的底层模型，可能是 deployment 的 fallback。

对外响应的 `model` 返回 requested deployment ID；底层模型放在可选的 `rag.backend_model` 扩展中，避免客户端绑定内部 provider。

## 4. 当前代码基线与差距

以下结论来自当前工作树，而不是仅依据可能滞后的说明文档。

| 主题 | 当前事实 | 对兼容层的影响 |
|---|---|---|
| 现有 Chat API | `RagChatController` 暴露 `/api/v1/rag/chat`、`/ask`、`/stream` | 新接口必须独立映射 `/v1`，不能使用 `@ApiVersion` |
| 请求模型 | `ChatRequest` 只有一个 `message`，另有 session、model、collection 等 RAG 字段 | 不能直接把 OpenAI DTO 硬转为旧 DTO，否则丢失完整消息历史 |
| Prompt 构建 | `RagChatService` 使用 `.system(...)` + `.user(...)` | 需要支持 `.messages(List<Message>)` |
| Advisor 查询提取 | `AdvisorUtils` 从后向前查找最后一个 `UserMessage` | 完整消息列表不会破坏当前检索查询提取方式 |
| ChatMemory | `RagChatService` 总是把 `MessageChatMemoryAdvisor` 加入默认 Advisor | OpenAI 兼容请求默认必须移除该 Advisor，避免历史重复 |
| 非流式 fallback | `orderedCandidates()` 后逐个尝试 | 可复用思路，但 deployment 应拥有明确候选链并保留 model ref |
| 流式 fallback | 只解析一个显式 model；无等价 fallback | 必须定义“首个 chunk 前可 fallback，首个 chunk 后不可切换” |
| 非流式内容留存 | 成功后写完整 user/assistant 到 `rag_chat_history`，默认清理周期 30 天 | 外部兼容路径不能无条件复用；默认只留元数据 |
| 流式内容留存 | 未显式写 `rag_chat_history` | 兼容路径按同一 retention policy 处理，不能因 stream 与否改变隐私语义 |
| Spring AI 流式 memory | `MessageChatMemoryAdvisor` 内部会聚合流并写 `spring_ai_chat_memory` | 服务端 memory 模式可复用，但不能替代业务审计表 |
| 现有 SSE | 只有 `choices[].delta.content`，结束为自定义 `event:done` | 不是完整 Chat Completions streaming contract |
| SSE 序列化 | Controller 手工拼 JSON 字符串 | 新接口必须使用 Jackson DTO 序列化和原始 SSE writer |
| 使用量 | `ModelComparisonService` 已读取 `ChatResponseMetadata.getUsage()` | 兼容结果可以返回真实 usage；上游不提供时应省略而不是伪造 |
| 模型路由 | `ChatModelRouter` + `ConfiguredChatModelFactory` 支持配置模型 ref | deployment registry 应位于 router 之上 |
| 模型能力适配 | `RerankAdvisor` 的 API adapter 在构造时按单一 base URL 固定 | 动态模型路由下可能使用错误 adapter，实施前需改为按候选模型解析 |
| Collection ACL | `ApiKeyCollectionAccess` 已支持 request 与 API key allow-list 校验 | deployment scope、request override、API key ACL 必须统一合成且 fail closed |
| 文档 ID 范围 | `CollectionDocumentResolver` 会将 documentIds 与 collectionIds 取交集 | 兼容层必须先形成有效 collectionIds，再调用现有 resolver |
| 鉴权来源 | 当前只接受 `X-API-Key` 或 `?apiKey=` | `/v1` 必须支持 Bearer；query key 不应出现在新接口 |
| Filter URL | starter 只把鉴权和限流注册到 `/api/*` | `/v1/*` 当前会绕过安全控制 |
| Filter 顺序 | 限流 order 0，鉴权 order 1；组件式 RequestTraceFilter 也是 order 1 | 限流先于 trace/auth，且同 order 组件顺序不够明确，需统一显式排序 |
| CORS | 只映射 `/api/**` | 浏览器客户端访问 `/v1/**` 时需增加覆盖 |
| SLO interceptor | `ApiSloConfig` 只映射 `/api/**` | `/v1/**` 需增加映射；stream 全生命周期仍需专用指标 |
| 错误格式 | ControllerAdvice 返回 RFC 7807；Filter 返回项目 `ErrorResponse` | `/v1/**` 需要 path-aware OpenAI error envelope |
| Model API | 已有 `/api/v1/rag/models`，返回 provider/backend model 信息 | `/v1/models` 应列 RAG deployments，不应原样复用 |
| API 文档 | 全局 customizer 给所有路径追加 RFC 7807 400/500 | `/v1/**` 需独立 OpenAPI group 或排除全局 customizer |
| 模块 | runnable main 在 core；filter auto-config 在 starter | 兼容 endpoint 和安全注册必须验证 standalone core 与 starter consumer 两种拓扑 |
| 请求开关 | `useHybridSearch`、`useRerank` 未进入 chat advisor context | 规划不能假设这些请求字段当前有效 |
| 领域扩展 | chat 当前只使用 system prompt；未调用 `getRetrievalConfig()`、`postProcessAnswer()`、`isApplicable()` | deployment 不能依赖这些尚未接入的行为 |

### 4.1 相关代码索引

- [ChatRequest](../../spring-ai-rag-api/src/main/java/com/springairag/api/dto/ChatRequest.java)
- [ChatResponse](../../spring-ai-rag-api/src/main/java/com/springairag/api/dto/ChatResponse.java)
- [RagChatService](../../spring-ai-rag-core/src/main/java/com/springairag/core/config/RagChatService.java)
- [RagChatController](../../spring-ai-rag-core/src/main/java/com/springairag/core/controller/RagChatController.java)
- [AdvisorUtils](../../spring-ai-rag-core/src/main/java/com/springairag/core/advisor/AdvisorUtils.java)
- [ChatModelRouter](../../spring-ai-rag-core/src/main/java/com/springairag/core/config/ChatModelRouter.java)
- [ConfiguredChatModelFactory](../../spring-ai-rag-core/src/main/java/com/springairag/core/config/ConfiguredChatModelFactory.java)
- [ApiKeyManagementService](../../spring-ai-rag-core/src/main/java/com/springairag/core/service/ApiKeyManagementService.java)
- [ApiKeyController](../../spring-ai-rag-core/src/main/java/com/springairag/core/controller/ApiKeyController.java)
- [ApiKeyBootstrapService](../../spring-ai-rag-core/src/main/java/com/springairag/core/service/ApiKeyBootstrapService.java)
- [RagApiKey](../../spring-ai-rag-core/src/main/java/com/springairag/core/entity/RagApiKey.java)
- [ApiKeyCollectionAccess](../../spring-ai-rag-core/src/main/java/com/springairag/core/security/ApiKeyCollectionAccess.java)
- [CollectionDocumentResolver](../../spring-ai-rag-core/src/main/java/com/springairag/core/service/CollectionDocumentResolver.java)
- [ApiKeyAuthFilter](../../spring-ai-rag-core/src/main/java/com/springairag/core/filter/ApiKeyAuthFilter.java)
- [RateLimitFilter](../../spring-ai-rag-core/src/main/java/com/springairag/core/filter/RateLimitFilter.java)
- [GeneralRagAutoConfiguration](../../spring-ai-rag-starter/src/main/java/com/springairag/starter/GeneralRagAutoConfiguration.java)
- [GlobalExceptionHandler](../../spring-ai-rag-core/src/main/java/com/springairag/core/controller/GlobalExceptionHandler.java)
- [SseEmitters](../../spring-ai-rag-core/src/main/java/com/springairag/core/util/SseEmitters.java)
- [现有 SSE 文档](../SSE-PROTOCOL.md)
- [代码审计报告](../2026-07-21_CODE_AUDIT_REPORT.md)

## 5. 目标协议

### 5.1 兼容级别

本规划把“兼容”拆为四级，避免用一个词掩盖差异：

| 级别 | 含义 | MVP |
|---|---|---|
| C0 | OpenAI SDK 可通过 `base_url`、API key 连接 | 是 |
| C1 | 文本 Chat Completions 请求/响应结构兼容 | 是 |
| C2 | 标准 SSE chunk、finish reason、usage、`[DONE]` | 是 |
| C3 | tools、多模态、Responses、Hosted Agent 语义 | 否 |

对外文档必须表述为“OpenAI Chat Completions text compatibility”，不能笼统声称“100% OpenAI API compatible”。

### 5.2 官方协议基线

实施时以以下官方资料为基准，并在实现 PR 中记录再次核对日期：

- [Create chat completion](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create)
- [Chat completion streaming events](https://developers.openai.com/api/reference/resources/chat/subresources/completions/streaming-events)
- [Streaming guide](https://developers.openai.com/api/docs/guides/streaming-responses)
- [Authentication](https://platform.openai.com/docs/api-reference/authentication)
- [Models API](https://developers.openai.com/api/reference/resources/models/methods/list)
- [OpenAI Python SDK SSE parser](https://github.com/openai/openai-python/blob/main/src/openai/_streaming.py)

### 5.3 Endpoint

```text
POST /v1/chat/completions
GET  /v1/models
GET  /v1/models/{deploymentId}
```

兼容 Controller 不加 `@ApiVersion`，因为项目的版本注解会自动添加 `/api/{version}`，从而错误地产生 `/api/v1/...`。

## 6. 核心架构决策

### 6.1 保留现有 API，新增适配层

```text
Legacy REST                        OpenAI-compatible REST
/api/v1/rag/chat/**               /v1/chat/completions
          │                                  │
Legacy request mapper             OpenAI request mapper
          └──────────────┬───────────────────┘
                         ▼
                 RagChatCommand
                         │
                         ▼
                 RagExecutionService
            ┌────────────┼─────────────┐
            ▼            ▼             ▼
       Deployment    Advisor chain   Model candidates
       + ACL scope   + memory mode   + fallback policy
            └────────────┼─────────────┘
                         ▼
              RagChatResult / StreamEvent
          ┌──────────────┴───────────────────┐
          ▼                                  ▼
 Legacy response mapper             OpenAI response/SSE mapper
```

不能采用：

```text
OpenAiChatCompletionRequest -> ChatRequest -> RagChatService
```

原因是旧 `ChatRequest` 无法表达完整消息列表、生成参数、stateless memory、标准 usage 和流式完成事件。硬转换会让适配逻辑散落在 Controller，并固化旧服务的不对称行为。

### 6.2 `model` 表示 RAG deployment

推荐：

```json
{"model": "rag-legal"}
```

不推荐直接公开：

```json
{"model": "openrouter/xiaomi/mimo-v2-pro"}
```

理由：

- 底层模型不是完整 RAG 行为。
- 直接暴露 backend model 容易绕过固定知识库、domain、fallback 和安全策略。
- deployment ID 可以稳定，底层 provider 可无感切换。
- `/v1/models` 可以成为“可供 Agent 选择的知识型能力目录”。

如确实需要后台调试 raw model，应继续走现有管理 API，或增加仅管理员可用的显式 debug override，不能默认开放。

### 6.3 默认无状态

Chat Completions 客户端通常在每次请求中提交完整 `messages[]`。若服务端再按 session 注入历史，会产生：

- 重复 user/assistant turn。
- token 量不受调用方控制。
- 对话截断策略冲突。
- 同一 session 被不同调用方复用时产生数据串扰。

因此：

- `/v1/chat/completions` 默认 `memory=stateless`。
- stateless 时不安装 `MessageChatMemoryAdvisor`，也不读写 `spring_ai_chat_memory`。
- memory 与内容留存是两个独立维度；stateless 不代表“自动保存完整对话”，server memory
  也不代表“永久业务审计”。
- `/v1` 默认 `content-audit-mode=METADATA_ONLY`：只保存 trace、family/key ID、
  deployment/backend、usage、状态、latency 和 scope 摘要，不持久化完整 prompt/answer。
- effective mode 由全局上限、deployment 配置和 API Key family policy 取最严格值；
  request body 不提供扩大或关闭安全元数据审计的入口。
- `FULL` 内容留存必须由 deployment/operator 显式启用，external Key policy 也必须允许；
  并要求独立、有限的 retention days、加密/访问控制和清理测试。此时才写
  `rag_chat_history`，其
  `auditConversationId = rag.session_id（调用方显式提供时）或 completionId`；生成该
  audit ID 不能导致安装 memory Advisor。
- `store=false` 只表示不实现 OpenAI 的服务端 store 产品语义，不等于关闭必要的安全/
  运营元数据；对外文档必须明确本服务的内容留存模式。
- 服务端 memory 只能通过显式 RAG 扩展开启。
- 启用服务端 memory 时必须提供 `session_id`。请求必须恰好包含一个当前 user turn，
  可以在其前面携带 `system` / `developer` 指令，但不能携带调用方提交的历史
  user/assistant/tool turn，防止双重历史和 session 串扰。

现有 `/api/v1/rag/chat/**` 保持服务端 memory 行为。

### 6.4 feature flag 默认关闭

建议配置：

```yaml
rag:
  openai-compatibility:
    enabled: false
```

关闭时：

- 不注册 `/v1/chat/completions` 和 `/v1/models`。
- filter registration 不增加 `/v1/*` pattern，CORS 不增加 `/v1/**` mapping。
- 现有 API 行为不变。

### 6.5 不在第一阶段增加 Maven 模块

第一阶段建议：

- OpenAI 协议 DTO 放 `spring-ai-rag-api`。
- compatibility controller、mapper、deployment registry、execution adapter 放 `spring-ai-rag-core`。
- core 提供受 feature flag 控制的 `RagOpenAiCompatibilityConfiguration`，显式注册兼容 Controller、mapper、registry 和相关 advice。
- core standalone 通过组件扫描加载该 configuration；starter consumer 由 `GeneralRagAutoConfiguration` 显式 `@Import`，不能依赖宿主应用额外扫描 `com.springairag`。
- filter 注册与 starter 条件装配复用 core 共享配置，同时验证 core standalone 也会注册。

暂不新增 `spring-ai-rag-openai-compat` 模块，原因：

- runnable main 当前位于 core。
- starter 依赖 core，新增模块很容易形成 controller 扫描、依赖方向或循环依赖问题。
- 当前内部执行服务是 core 具体实现，尚未形成适合独立 adapter 模块依赖的稳定 SPI。

待内部 `RagExecutionService` 稳定且出现独立发布需求后，再将协议层拆出。

## 7. Deployment 配置设计

建议新增独立配置类 `OpenAiCompatibilityProperties`，不要继续扩大已经较重的 `RagProperties`。

配置类可放在 `com.springairag.core.config`，以便当前 core 的 `@ConfigurationPropertiesScan` 发现；starter 同时通过 `@EnableConfigurationProperties` 显式注册。

示例：

```yaml
rag:
  openai-compatibility:
    enabled: false
    strict-validation: true
    default-deployment: rag-default
    allow-x-api-key: false
    expose-rag-extension: true
    content-audit-mode: metadata-only

    deployments:
      rag-default:
        enabled: true
        display-name: Default Knowledge RAG
        backend-models:
          - minimax/MiniMax-M2.7
          - openrouter/xiaomi/mimo-v2-pro
        domain-id: default
        collection-ids: []
        max-results: 8
        query-rewrite-enabled: true
        rerank-enabled: false
        allow-request-scope-override: false
        allow-generation-overrides: true
        allow-server-memory: false
        content-audit-mode: metadata-only

      rag-legal:
        enabled: true
        display-name: Legal Knowledge RAG
        backend-models:
          - openrouter/xiaomi/mimo-v2-pro
        domain-id: legal
        collection-ids:
          - 12
          - 13
        max-results: 10
        query-rewrite-enabled: true
        rerank-enabled: true
        allow-request-scope-override: false
        allow-generation-overrides: false
        allow-server-memory: false
        content-audit-mode: metadata-only
```

### 7.1 启动期校验

应用启动时必须 fail fast 校验：

- enabled 时至少存在一个 enabled deployment。
- `default-deployment` 必须存在且 enabled。
- deployment ID 非空、唯一，建议只允许 `[A-Za-z0-9._-]+`。
- `backend-models` 非空。
- 每个 backend model ref 均能由 `ChatModelRouter` 解析；缺少凭据等运行条件时可明确
  标记 unavailable。
- 每个 enabled deployment 启动完成后至少有一个确定 available 的 backend candidate；
  否则该 deployment 不得注册为 enabled。候选链中后续 unavailable candidate 可跳过并
  记录脱敏 warning，但不能让一个完全不可执行的 deployment 出现在 `/v1/models`。
- `collection-ids` 只能包含正数且去重。
- `max-results` 在 `1..50`。
- `domain-id` 如非空，应存在于 `DomainExtensionRegistry`；是否允许缺失应由显式配置控制，默认拒绝。
- `query-rewrite-enabled`、`rerank-enabled` 只能覆盖当前实际可执行的阶段。
- MVP retrieval mode 固定为 hybrid；不得暴露尚无实现支撑的 vector-only/keyword-only 配置。

### 7.2 Deployment Registry

新增 `RagDeploymentRegistry`，职责是：

- 加载和校验配置。
- 按 ID 解析 deployment。
- 返回默认 deployment。
- 列出当前 caller 可见的 deployments。
- 解析有序 backend model candidates。
- 不负责实际构建 ChatModel；该职责仍属于 `ChatModelRouter` / factory。

建议定义：

```java
record RagDeployment(
    String id,
    String displayName,
    List<String> backendModelRefs,
    String domainId,
    List<Long> collectionIds,
    int maxResults,
    boolean queryRewriteEnabled,
    boolean rerankEnabled,
    boolean allowRequestScopeOverride,
    boolean allowGenerationOverrides,
    boolean allowServerMemory,
    ContentAuditMode contentAuditMode
) {}
```

## 8. 请求契约

### 8.1 标准请求示例

```json
{
  "model": "rag-default",
  "messages": [
    {
      "role": "system",
      "content": "回答要简洁，并明确指出不确定之处。"
    },
    {
      "role": "user",
      "content": "退款规则是什么？"
    }
  ],
  "temperature": 0.2,
  "max_completion_tokens": 800,
  "stream": false
}
```

标准客户端无需认识任何 RAG 字段；知识库和策略由 `model=rag-default` 决定。

### 8.2 可选 RAG 扩展

高级客户端可以使用额外的 `rag` 对象：

```json
{
  "model": "rag-default",
  "messages": [
    {"role": "user", "content": "退款规则是什么？"}
  ],
  "rag": {
    "collection_ids": [1, 2],
    "document_ids": [10, 20],
    "max_results": 8,
    "include_sources": true,
    "memory": "stateless",
    "session_id": null,
    "metadata": {
      "request_source": "support-agent"
    }
  }
}
```

约束：

- deployment 未允许 scope override 时，提供 collection/document override 返回 400。
- request collection 必须是 deployment collection 的子集；不能通过 request 扩大 deployment 权限。
- 最终 scope 还必须通过 API key ACL。
- `memory=server` 必须由 deployment 允许并提供非空 `session_id`。
- `memory=server` 时 messages 必须恰好包含一个当前 user turn；允许 server 指令前的
  `system` / `developer`，拒绝 caller 历史 user/assistant/tool turn。
- metadata 必须限制 key 数、key 长度、value 类型和序列化后总大小。
- 标准 SDK 可通过其 extra body 能力发送扩展；不使用扩展不影响标准调用。

### 8.3 消息校验与映射

| role | MVP 行为 |
|---|---|
| `system` | 映射为 Spring AI `SystemMessage` |
| `developer` | 映射为 `SystemMessage`；记录为语义降级，因为 Spring AI 1.1.4 无独立 developer message |
| `user` | 映射为 `UserMessage` |
| `assistant` | 映射为 `AssistantMessage` |
| `tool` | 返回 400 `unsupported_value` |
| `function` | 返回 400 `unsupported_value` |

消息列表规则：

- 至少一条消息。
- 必须存在至少一条非空 user message。
- 检索 query 使用最后一个非空 user message。
- 保持 caller 消息顺序。
- server deployment/domain system prompt 放在 caller 消息之前。
- PromptCustomizer 只作用于 server domain prompt 和最后一条 user message，不改写历史消息。
- 限制消息条数、单条长度和请求总字符数，避免绕过旧 `message <= 10000` 的保护。
- 默认建议：最多 100 条消息、单条文本 10000 字符、总文本 100000 字符；最终值应通过配置并结合上游模型 context window 校准。

content 规则：

- 接受字符串。
- 接受只包含 `{ "type": "text", "text": "..." }` 的数组，并按顺序拼接。
- 拒绝 image、audio、file、input_audio 等 content part。
- 拒绝 assistant tool calls 和 tool call ID。

DTO 还必须保留“字段未提供 / 显式 `null` / 具体值”三态。不能对 OpenAI 可选字段一律
使用 Java primitive 或让 Jackson 默认值抹平 presence，因为严格校验需要区分：

- `stream` 未提供与显式 `false` 可按协议等价处理，但仍应由 mapper 明确归一化。
- unsupported 字段未提供或显式 `null` 可以接受，非 null 必须返回精确错误。
- `store=false` 可接受，`store=true` 拒绝；该字段不覆盖本服务公开声明的安全元数据和
  内容 retention policy。
- `n` 未提供使用 1，显式 `null` 与具体非法值不能因 primitive 默认值而混淆。

实现选择按优先级为：boxed type + 显式 presence 标志、presence-aware Jackson DTO，或在
协议边界保留 `JsonNode` 后映射到内部 typed command。DTO 应捕获未知字段并交给 strict
validator，不能依赖全局 `FAIL_ON_UNKNOWN_PROPERTIES`，以免改变现有 `/api/**` JSON 行为。

### 8.4 参数支持矩阵

| 参数 | MVP | 映射/限制 |
|---|---|---|
| `model` | 支持，必填 | 解析 RAG deployment |
| `messages` | 支持，必填 | 文本角色映射 |
| `stream` | 支持 | 默认 false |
| `stream_options.include_usage` | 支持 | 仅在 usage 可获得时发送 |
| `stream_options.include_obfuscation` | 有限支持 | 缺省/false 接受；true 返回 400，MVP 不生成 obfuscation padding |
| `temperature` | 支持 | deployment 允许覆盖时映射 |
| `top_p` | 支持 | deployment 允许覆盖时映射 |
| `frequency_penalty` | 支持 | provider capability 校验后映射 |
| `presence_penalty` | 支持 | provider capability 校验后映射 |
| `stop` | 支持 | string 或 string array，转 stop sequences |
| `max_completion_tokens` | 支持 | 优先参数，按 backend provider 映射 |
| `max_tokens` | 兼容支持 | 作为旧别名；不得与 `max_completion_tokens` 同时出现 |
| `n` | 仅支持 1 | 其他值返回 400 |
| `response_format` | 仅 text | 缺省或 `{type:"text"}`；JSON 模式返回 400 |
| `modalities` | 仅 text | 缺省或仅 `["text"]` |
| `user` | 接受 | deprecated；最多 64 字符，仅作审计标识，不传递给 provider |
| `safety_identifier` | 接受 | 最多 64 字符，仅作审计标识 |
| `metadata` | 有限接受 | 合并到受限审计 metadata，不实现 OpenAI store |
| `store` | 仅 false/null | true 返回 400 |
| `tools` / `tool_choice` | 不支持 | 非空即 400 |
| `functions` / `function_call` | 不支持 | 非空即 400 |
| `parallel_tool_calls` | 不支持 | 非默认值即 400 |
| `logit_bias` | 不支持 | 非空即 400 |
| `logprobs` / `top_logprobs` | 不支持 | 非 false/null 即 400 |
| `audio` | 不支持 | 非空即 400 |
| `reasoning_effort` | 不支持 | 非空即 400 |
| `service_tier` | 不支持 | 非空/非 auto 即 400 |
| `seed` | 不支持 | 非空即 400 |
| `prediction` | 不支持 | 非空即 400 |
| `prompt_cache_key` / `prompt_cache_options` | 不支持 | 非空即 400 |
| `prompt_cache_retention` | 不支持 | 官方已标记 deprecated；非空即 400 |
| `quality` / `preamble` | 不支持 | 非空即 400 |
| `verbosity` / `web_search_options` | 不支持 | 非空即 400 |
| 未识别字段 | strict 模式拒绝 | null 可忽略；非 null 返回 400 并指出 param |

原则：**不能静默接受会改变生成语义但实际未实现的字段。**

### 8.5 Provider capability

通用 `ChatOptions` 支持 model、temperature、topP、topK、penalties、stop sequences、max tokens，但不同 provider/推理模型不一定支持全部参数。

新增 `ChatGenerationOptionsFactory`：

- 输入 deployment、resolved candidate、OpenAI 请求参数。
- 识别 OpenAI、Anthropic、MiniMax 等 backend。
- 对 reasoning model 正确映射 `max_completion_tokens`。
- deployment 禁止 override 时拒绝请求参数，而不是忽略。
- provider 不支持参数时在调用上游前返回 400。
- 不把 public deployment ID 写入上游 `options.model`；backend model 已由 router 选择。

## 9. 响应契约

### 9.1 非流式响应

```json
{
  "id": "chatcmpl-rag_01J...",
  "object": "chat.completion",
  "created": 1784659200,
  "model": "rag-default",
  "choices": [
    {
      "index": 0,
      "message": {
        "role": "assistant",
        "content": "根据知识库，退款期限为……"
      },
      "finish_reason": "stop"
    }
  ],
  "usage": {
    "prompt_tokens": 438,
    "completion_tokens": 96,
    "total_tokens": 534
  },
  "rag": {
    "trace_id": "a1b2c3d4e5f6",
    "backend_model": "minimax/MiniMax-M2.7",
    "sources": [
      {
        "document_id": "123",
        "title": "退款政策",
        "chunk_text": "……",
        "score": 0.91
      }
    ],
    "step_metrics": [
      {
        "step_name": "HybridSearch",
        "duration_ms": 42,
        "result_count": 8
      }
    ]
  }
}
```

规则：

- `id` 每次 completion 唯一，前缀建议 `chatcmpl-rag_`。
- `created` 为 Unix epoch seconds。
- `model` 为 deployment ID。
- `choices` MVP 恰好一个。
- usage 仅在上游实际提供时返回；不以字符串长度伪造 token。
- `rag` 是可选扩展；可通过配置全局关闭。
- trace ID 同时保留在 `X-Trace-Id` header。

### 9.2 finish reason 映射

| Spring/provider reason | OpenAI response |
|---|---|
| stop / end_turn | `stop` |
| length / max_tokens | `length` |
| content_filter / safety | `content_filter` |
| tool_calls | MVP 不应出现；出现视为 backend contract error |
| 正常完成但 metadata 缺失 | `stop` |
| 未识别非空值 | 记录日志并按显式映射策略处理，不能直接泄漏任意 provider 字符串 |

### 9.3 流式事件序列

每个流使用同一个 `id`、`created`、`model`。

```text
data: {"id":"chatcmpl-rag_01J...","object":"chat.completion.chunk","created":1784659200,"model":"rag-default","choices":[{"index":0,"delta":{"role":"assistant","content":""},"finish_reason":null}]}

data: {"id":"chatcmpl-rag_01J...","object":"chat.completion.chunk","created":1784659200,"model":"rag-default","choices":[{"index":0,"delta":{"content":"根据"},"finish_reason":null}]}

data: {"id":"chatcmpl-rag_01J...","object":"chat.completion.chunk","created":1784659200,"model":"rag-default","choices":[{"index":0,"delta":{"content":"知识库"},"finish_reason":null}]}

data: {"id":"chatcmpl-rag_01J...","object":"chat.completion.chunk","created":1784659200,"model":"rag-default","choices":[{"index":0,"delta":{},"finish_reason":"stop"}],"rag":{"trace_id":"...","backend_model":"...","sources":[]}}

data: {"id":"chatcmpl-rag_01J...","object":"chat.completion.chunk","created":1784659200,"model":"rag-default","choices":[],"usage":{"prompt_tokens":438,"completion_tokens":96,"total_tokens":534}}

data: [DONE]
```

usage-only chunk 只在 `stream_options.include_usage=true` 且 usage 可取得时发送；此时此前
chunk 的 `usage` 为 `null`，最后一个 usage chunk 的 `choices` 为空。若上游没有可信
usage，省略 usage-only chunk 并在兼容说明中声明该 provider limitation。

### 9.4 流式实现约束

不能继续返回 `Flux<String>`。建议新增结构化事件：

```java
sealed interface RagChatStreamEvent {
    record Started(...) implements RagChatStreamEvent {}
    record Delta(String text) implements RagChatStreamEvent {}
    record Completed(RagChatResult result) implements RagChatStreamEvent {}
}
```

实现要点：

- 使用 `stream().chatClientResponse()`，保留 metadata、finish reason 和 advisor context。
- accumulator 跨所有 chunk 保存最后一个非空 response metadata 和最后一个包含 RAG 数据的 advisor context；不能假设最终 chunk 一定携带 sources/metrics。
- 非阻塞累积完整 assistant 文本用于 completed event；是否持久化由 content audit mode
  决定，默认 metadata-only。
- 首个内容 chunk 前允许切换到 deployment 的下一个 backend candidate。
- 首个 chunk 已发送后禁止 fallback，避免把两个模型的输出拼成一个回答。
- 客户端取消连接时 dispose 上游 subscription。
- emitter timeout、completion、error、client disconnect 都必须停止 heartbeat 和 subscription。
- 正常完成只写一次 metadata audit；仅 FULL 模式再写一次 `rag_chat_history`。
- 失败或取消只写状态/计量元数据，不写完整回答；如未来需要 partial content audit，
  应单独增加状态字段并显式配置，不能伪装成功。
- 发送 `[DONE]` 后立即正常 complete。
- 流中错误不发送 `[DONE]`；连接中断让标准客户端感知失败。
- SSE heartbeat 保持为 comment，验证官方客户端会忽略。

建议新增专用 `OpenAiSseWriter`，使用项目 `ObjectMapper` 序列化 DTO，并对最终原始字节做测试。不要复用手工 JSON escape 路径。

## 10. 内部执行抽象

### 10.1 RagChatCommand

建议在 core 内新增：

```java
record RagChatCommand(
    String deploymentId,
    List<Message> messages,
    String retrievalQuery,
    List<String> backendModelRefs,
    String domainId,
    RetrievalPolicy retrieval,
    MemoryPolicy memory,
    GenerationPolicy generation,
    AuditContext audit
) {}
```

关键字段：

- 完整 caller message list。
- 单独保存最后一个 user query，供检索、日志和审计使用。
- deployment 解析后的 backend candidate refs。
- deployment/request/ACL 合成后的 collection/document scope。
- stateless/server memory mode。
- provider-neutral generation options。
- caller key ID、user/safety identifier、trace ID 等审计数据。
- 由 global/deployment/family policy 解析出的 effective content audit mode 与 retention
  policy；request body 不覆盖。

### 10.2 RagChatResult

```java
record RagChatResult(
    String answer,
    String deploymentId,
    String backendModelRef,
    String finishReason,
    TokenUsage usage,
    String traceId,
    String sessionId,
    List<SourceDocument> sources,
    List<StepMetricRecord> stepMetrics
) {}
```

### 10.3 Resolved model candidate

当前 router 返回 `ChatModel`，执行层无法可靠知道最终成功的是哪个 ref，也无法按候选模型选择兼容 adapter。

建议新增：

```java
record ResolvedChatModel(
    String ref,
    ChatModel model,
    ModelCapabilities capabilities
) {}
```

`ChatModelRouter` 增加显式候选解析 API：

```java
List<ResolvedChatModel> resolveCandidates(List<String> modelRefs);
```

deployment 的候选列表必须严格按配置顺序执行，不应自动追加所有全局模型。旧 API 可继续使用其现有全局 fallback 规则。

### 10.4 Advisor 组合

把当前 `sortedAdvisors` 拆为：

- `baseRagAdvisors`：QueryRewrite、HybridSearch、Rerank/context injection、自定义 Advisor。
- `memoryAdvisor`：仅 server memory mode 添加。

每次执行根据 command 组合：

```text
stateless:
  baseRagAdvisors

server memory:
  baseRagAdvisors + MessageChatMemoryAdvisor
```

需要增加 request-context 开关：

- `rag.queryRewrite.enabled`
- `rag.rerank.enabled`
- `rag.retrieval.maxResults`
- `rag.retrieval.documentIds`
- `rag.retrieval.filterRequested`
- `rag.model.capabilities`

注意：当前 `RerankAdvisor` 同时承担“重排”和“把检索上下文注入 Prompt”。`rerank=false` 不能直接跳过整个 Advisor，否则 RAG context 不会进入模型。实施时应：

1. 只跳过排序算法，仍注入原始 retrieval results；或
2. 把 context injection 拆成独立 Advisor。

为降低改动，MVP 推荐方案 1；若测试显示职责继续耦合导致复杂，再拆分。

### 10.5 Prompt 组装顺序

建议顺序：

1. deployment/domain server system prompt。
2. caller `system` / `developer` / user / assistant messages，保持相对顺序。
3. Advisor 注入 retrieval context。
4. provider-specific normalization。
5. 模型调用。

动态 backend routing 时，provider-specific normalization 必须按当前 candidate 执行。当前 `RerankAdvisor` 构造时固定 adapter 的方式需要改造，不能继续按默认 provider 推断。

### 10.6 Legacy API 适配

现有 `RagChatService.chat(ChatRequest)` 改为构造 `RagChatCommand`：

- messages = 一个 user message，加现有 domain system prompt。
- memory = server。
- session ID 保持现有自动生成逻辑。
- collection/document scope 保持现有语义。
- model 字段按旧 router/fallback 规则处理。
- 对外 `ChatResponse` 字段和状态码不变。

现有 `chatStream(ChatRequest)` 同样适配到结构化流，再由旧 Controller 继续输出旧 SSE 协议。不要在同一个 writer 中混合旧 custom SSE 和新 OpenAI SSE。

## 11. Scope 与 ACL 合成

有效检索范围按以下顺序计算：

```text
deployment collection scope
        ∩
request rag.collection_ids（仅在允许 override 时）
        ∩
authenticated API key allowed collections
        ∩
request document_ids 所属 collections
```

具体规则：

1. deployment collections 为空表示 deployment 不主动限制。
2. 新 policy 的 API key Collection scope 必须显式为 `ALL`、`LIST` 或 `NONE`：
   `ALL` 才表示 ACL 不限制，`LIST` 使用指定集合，`NONE` 表示无 Collection 权限。
   现有 null/blank=unrestricted 只作为 legacy key 迁移语义，不得用于新 external key。
3. request 未提供 collection 时沿用 deployment scope。
4. deployment scope 为空、API key restricted 时，强制使用 key allow-list。
5. request 提供 deployment 之外的 collection，返回 403，不静默扩大也不静默截断。
6. deployment 固定 scope 与 key allow-list 部分重叠时，使用二者交集；这只会收窄权限。
7. deployment 固定 scope 与 key allow-list 完全无交集时返回 403，并且 `/v1/models` 对该 caller 隐藏该 deployment。
8. request override 必须同时是 deployment scope 和 key allow-list 的子集；不满足时返回 403，不能用静默取交集掩盖 caller 的越权请求。
9. collection 解析结果为空时必须形成显式 empty document filter，绝不能退化为全库检索。
10. documentIds 必须和最终 collections 取交集。
11. ACL 计算应抽取为共享 service，旧 Controller 和兼容 Controller 都调用，避免规则漂移。

## 12. 外部调用方 API Key、鉴权、限流与 CORS

### 12.1 结论：API Key 是兼容服务的上线前置能力

当前系统已经有 API Key 管理雏形，不需要从零开始；但它目前更接近内部工具，
**尚不能直接作为外部 RAG 服务的完整身份与授权系统**。

对 OpenAI-compatible 服务，API Key 的定义应是：

> 一个面向机器调用方的长期 service credential。它解析为稳定的 key principal，
> 再由 principal policy 决定可调用的 action、可见的 RAG deployment、可访问的
> Collection、过期时间和配额。

它不是：

- 上游 LLM provider key。
- OpenAI 请求体 `user` / `safety_identifier` 所声明的终端用户。
- 仅用于“请求头字符串相等”的全局共享密码。

OpenAI 请求中的 `user`、`safety_identifier` 只能作为受限长度的非可信审计提示，
不能参与认证或扩大权限。兼容层必须使用当前数据库 Key 的 `keyId` 作为 authenticated
principal。

因此设置硬门槛：

- `rag.openai-compatibility.enabled=true` 时，生产/非开发 profile 必须同时满足
  `rag.security.enabled=true`。
- `/v1/**` 只接受能够解析为数据库 principal 和 policy 的 Key。
- 外部兼容 Key 至少必须具备 action scope、deployment allow-list、Collection scope、
  过期/吊销状态和每 Key 限流。
- 上述条件未满足时 fail fast，不允许“接口先上线，安全后补”。

### 12.2 当前实现事实

| 能力 | 当前实现 | 结论 |
|---|---|---|
| Secret 格式 | `rag_sk_` + 去掉连字符的 UUID | 有约 122 bit 随机性，基础上可用 |
| Public ID | `rag_k_` + 12 个 hex 字符 | 可用于日志、列表和 principal ID；外部规模下应加长 |
| 存储与查找 | `SHA-256(rawKey)`，`key_hash` 唯一索引 | 正确方向；验证为索引查找 |
| 明文返回 | create/rotate response 返回一次 raw key | 正确方向 |
| 明文列 | V23 和 entity 仍存在 nullable `api_key` 及索引，但 service 当前不写 | 与“raw 永不存储”声明矛盾，必须清理 |
| 生命周期 | create、list、revoke、rotate、expiresAt、lastUsedAt | 基础能力已存在 |
| 时间语义 | `expires_at/last_used_at` 为无时区 TIMESTAMP，Java 使用 `LocalDateTime` | 多节点/跨时区下需明确 UTC 迁移 |
| 角色 | `ADMIN` / `NORMAL` | 只能表达粗粒度管理权限 |
| 创建角色 | create DTO 没有 role；service 依赖 entity 默认值创建 NORMAL，仅 bootstrap 再提升首个 Key | 当前文档“ADMIN 可创建任意角色”与代码不一致 |
| Collection ACL | `allowed_collection_ids` 逗号串；null/blank 表示 unrestricted | 已覆盖主要数据链路，但 secure default 不足 |
| 委派 | 任意有效 Key 可 create；受限 Key 只能委派 ACL 子集 | NORMAL 仍可无限创建子 Key；unrestricted NORMAL 可创建 unrestricted 子 Key |
| 轮换 | 禁用旧 Key，再按 name/expiry/Collection ACL 调用 generate | 丢失旧 role 和未来 policy；原 expiry 可能已过期或即将过期 |
| 静态 Key | `rag.security.api-key` 无数据库 entity | 无 keyId、owner、ACL、role、quota 或 policy |
| Bootstrap | 空表时自动生成 ADMIN，并把 raw key 写入启动日志 | 不适合集中日志和多副本生产部署 |
| 认证传输 | `X-API-Key` 或 `?apiKey=`；不支持 Bearer | 旧 API 可兼容，新 `/v1` 不应接受 query secret |
| Filter 装配 | auth/rate-limit 的 `FilterRegistrationBean` 只在 starter，且只映射 `/api/*`；core standalone 不加载 starter | core 自带运行入口可能连当前 `/api/**` 都未经过这两个 filter |
| 正向缓存 | JVM 本地 Caffeine，30 秒 TTL | 多实例吊销可能继续生效至其他实例缓存过期 |
| last used | 每次成功认证同步 UPDATE | 高频 RAG 请求会产生数据库写放大 |
| 限流 | JVM 本地固定 60 秒窗口；可按 IP、raw header 或 auth attribute | 多实例不共享；配置可能要求保存 raw key；当前 auth/filter 顺序有误 |
| 管理审计 | create/revoke/rotate 仅普通日志 | `rag_audit_log` 已存在，但 API Key 生命周期尚未接入，operator 也未填充 |
| 管理 UI | 可创建、列表、吊销、轮换、选 Collection；调用 credential 存在 localStorage，旧 stream 放入 query | 仅适合开发/内网，无 owner、deployment、endpoint scope、quota、分页/搜索 |

还需明确两个当前权限缺口：

1. static legacy key 或认证关闭时，Controller 得不到 `RagApiKey` entity；create 会把
   null caller 视为 unrestricted 并可生成 unrestricted NORMAL Key，rotate 的 caller
   检查也会因 `caller == null` 而放行任意 `keyId`。
2. 现有文档声称“最后一个 ADMIN 不可吊销”，当前 service/controller 并无对应事务校验。

这些事实应在兼容接口实施前修复；不能把文档描述当作已实现保证。

### 12.3 目标 Principal 与 Policy

认证后下游不应继续直接依赖可变 JPA entity，而应得到不可变快照：

```java
record ApiKeyPrincipal(
    String keyId,
    String credentialFamilyId,
    ApiKeyRole role,
    String tenantId,
    String projectId,
    String ownerId,
    long policyVersion,
    ApiKeyPolicy policy
) {}
```

`tenantId`、`projectId`、`ownerId` 第一阶段可以是外部身份系统分配的 opaque string；
无需为了兼容接口先建设完整用户目录。其目的，是让一个 Key 能明确归属到客户、项目或
service account，而不是只剩一个自由文本 name。这些字段在 MVP 是归属和审计属性，
不会自动形成行级租户隔离；真实数据授权仍由 action/deployment/Collection policy
决定。若未来开放租户管理员自助管理，需要另行引入人类身份、tenant-admin 角色和强制
tenant boundary，不能仅凭请求中的 tenantId 或 ownerId 信任 caller。

rotation 不改变调用方身份，只替换 secret；短 overlap window 又可能同时存在两个有效
secret version。因此不能把稳定 owner/policy/委派关系复制到每个可轮换 Key 行。目标模型
必须分成两层，并使用新表隔离 V18-V24 legacy schema：

**`rag_api_key_family`：稳定 principal / policy**

- `family_id`：至少 128 bit 的公开稳定标识，主键。
- `status`：`ACTIVE` / `REVOKED`。
- `name`、`tenant_id`、`project_id`、`owner_id`、`role`。
- `parent_family_id`、`delegation_depth`、`created_by_key_id`。
- `policy JSONB`、`policy_version`。
- `expires_at`、`created_at`、`revoked_at`、`revocation_reason`。

**`rag_api_key_version`：可轮换 secret**

- `key_id`：公开 version ID；`key_hash`：SHA-256 索引查找值。
- `family_id`：外键指向稳定 family。
- `status`：`ACTIVE` / `ROTATED` / `REVOKED`。
- `created_at`、`last_used_at`、`retire_at`、`revoked_at`。
- `rotated_from_key_id`、`revocation_reason`。

**`rag_api_key_operation`：管理幂等记录**

- `operator_principal_id`、`operation_type`、`idempotency_key_hash` 组成唯一键。
- `target_family_id`、`created_key_id`、`result_status`、`created_at`、`expires_at`。
- 不保存 raw secret、完整 request body 或可重放 response。
- 只允许同一已认证 operator 查询自己的重复 operation 结果，并设置有限 retention。

所有 lifecycle timestamp 使用 `TIMESTAMPTZ` / Java `Instant`；新的管理契约要求 RFC
3339 offset，不接受无时区 expiry。`rag_api_key` 作为 V18-V24 legacy compatibility
shadow 在 expand 窗口暂时保留，不再作为新 external credential 的存储位置。这样旧应用
无法把新 external Key 误当作只受粗粒度 ACL 约束的 legacy Key。

认证查询顺序是 `key_hash -> ACTIVE version -> ACTIVE family -> family expiry/policy`。
overlap 时旧 version 可暂时保持 `ACTIVE` 并设置有上限的 `retire_at`；即使异步清理尚未
把它改成 `ROTATED`，认证也必须在 `now >= retire_at` 时拒绝。policy 和 owner 只存于
family，因此 policy 更新会同时作用于 overlap 中的所有 version，rotation 也无需复制
可变授权数据。

`parent_family_id` 在创建后不可变；创建 child 时锁定 parent 并验证 depth/child count/
policy subset。rotation、version revoke 和 family revoke 都先 `SELECT ... FOR UPDATE`
锁定目标 family，再在事务内重算 active versions 和 ADMIN/family invariants，不能只靠
Controller 预检查。

项目已使用 Hibernate `@JdbcTypeCode(SqlTypes.JSON)` 和 PostgreSQL JSONB，可复用该
模式定义 typed `ApiKeyPolicy`，不应继续为 deployment/action 增加手工逗号字符串解析。

建议 policy：

```json
{
  "schemaVersion": 1,
  "actions": [
    "models.read",
    "chat.completions.invoke"
  ],
  "deploymentIds": [
    "rag-default"
  ],
  "collectionScope": {
    "mode": "LIST",
    "ids": [1, 2]
  },
  "limits": {
    "requestsPerMinute": 60,
    "maxConcurrentRequests": 4,
    "monthlyTokenBudget": null
  },
  "maxContentAuditMode": "METADATA_ONLY",
  "allowDelegation": false
}
```

规则：

- role 只决定粗粒度管理能力，真实数据面授权由 action + deployment + collection
  三层共同决定。新 authorization service 不得沿用当前
  `ApiKeyCollectionAccess.isUnrestricted()` 中“ADMIN 自动全库”的数据面捷径；迁移后的
  ADMIN 如需全库权限，应由显式 policy 表达。
- `schemaVersion` 表示 JSON policy 结构版本；family 行上的 `policyVersion` 表示同一
  principal 的并发修改 revision，两者不能复用。
- JSON policy 使用独立、严格的 ObjectMapper/schema validation；未知 action、非法
  scope mode、负数 limit 或未来 schemaVersion 必须 fail closed。
- action 使用稳定语义名，不直接把 URL 字符串存入 policy。
- 兼容层外部 Key 的默认 action 只有 `models.read` 和
  `chat.completions.invoke`，不能因此获得文档上传、删除、Collection 管理或 Key 管理权限。
- 新建外部 NORMAL Key 默认 `allowDelegation=false`。
- 新建外部 Key 必须显式选择 deployment；空列表表示无 deployment 权限，不能解释为 ALL。
- 新建 external family 的 expiry 必填、必须在未来且不得超过可配置最大 TTL（建议生产
  默认 90 天）；普通管理请求不能创建永不过期 external credential。
- Collection 需要显式 `mode=ALL|LIST|NONE`，消除当前 null/empty=ALL 的歧义。
  外部 NORMAL Key 默认 `LIST` 或 `NONE`；只有 ADMIN 明确选择时才允许 `ALL`。
- 新 external Key 默认 `maxContentAuditMode=METADATA_ONLY`；只有 operator 明确授权且
  deployment/global 同时允许时才可使用 FULL。
- `monthlyTokenBudget` 只有在上游提供可信 usage 时才能做硬扣减；MVP 的硬门禁是 RPM 和
  concurrency，token/cost budget 可作为后续阶段。

### 12.4 授权求值顺序

每个 `/v1` 请求按固定顺序执行：

```text
解析 Bearer credential
  -> hash lookup
  -> version ACTIVE / retireAt 校验
  -> family ACTIVE / expiresAt / revokedAt 校验
  -> ancestor families ACTIVE / expiry / policy chain 校验
  -> 形成含 effective policy 的 ApiKeyPrincipal
  -> action scope
  -> deployment allow-list
  -> deployment 是否 enabled 且至少一个 backend available
  -> deployment/request/key/document Collection scope 合成
  -> RPM / concurrency / optional budget
  -> RAG execution
  -> usage、last-used、audit
```

失败语义：

- credential 缺失、未知、过期或已吊销：401，不透露具体原因。
- credential store 不可用、family/version 数据不一致、policy JSON/schema 无法解析：
  503 `service_unavailable` + trace ID，并触发告警；不能伪装成 401，也不能回退 static key。
- principal 存在但 action 或 Collection 越权：403。
- requested deployment 不存在或对该 principal 不可见：统一返回 404
  `model_not_found`，避免通过差异响应枚举 deployment；models retrieve 和 chat 必须一致。
- quota/并发达到上限：429，并给出 `Retry-After` 和 trace ID。
- `/v1/models` 只返回 key policy 允许、Collection scope 有交集且当前可执行的
  deployments。
- `POST /v1/chat/completions` 必须再次校验 requested deployment；不能把 models
  列表过滤当作授权。

建议新增 `ApiKeyAuthorizationService`，输入 principal、semantic action、deployment
和 request scope，返回不可变 `AuthorizedRagScope`。Controller、filter 和 retrieval
代码不得分别重写授权公式。

### 12.5 Secret 生成、存储与传输

目标规则：

- 使用 `SecureRandom` 生成至少 256 bit 随机 secret；保留 `rag_sk_` 前缀兼容现有识别，
  不再依赖 UUID 文本生成。
- 新 public keyId 也使用至少 128 bit 随机标识；继续接受现有 12-hex keyId，但不再为新
  外部 Key 生成过短 ID。
- 对高熵随机 secret，SHA-256 索引查找是合理方案；无需套用低熵密码的慢 hash。
- 数据库只保存 hash 和 public keyId；raw secret 仅在 create/rotate 成功响应返回一次。
- create/rotate 只允许通过 TLS；响应设置 `Cache-Control: no-store`、`Pragma: no-cache`，
  管理 UI 不把新 raw secret 写入 localStorage、浏览器日志或 analytics。
- raw secret 不进入 entity、数据库、审计详情、普通日志、MDC、metrics tag、URL、
  exception message 或测试快照。
- V23 的 `api_key` 索引必须在 expand 时删除，列先受 `CHECK (api_key IS NULL)` 保护；
  contract 再随 legacy table 一起删除。如发现历史明文，停止迁移并要求轮换，而不是
  静默丢弃或继续使用。
- `Authorization`、`X-API-Key` 和 query 参数仍应由现有敏感日志 converter 覆盖，并增加
  回归测试验证嵌套 JSON、异常文本和代理 access log 配置。

`/v1/**`：

- 首选且默认只接受 `Authorization: Bearer <key>`。
- 可通过配置兼容 `X-API-Key`。
- 不接受 query string `apiKey`。
- 同时提供 Bearer 与 X-API-Key 且值不同，返回 401；相同值可接受但记录无 secret 的
  deprecated transport metric。

旧 `/api/**`：

- 为 WebUI 和 legacy SSE 暂时保留 `X-API-Key` 与 query fallback。
- 可增加 Bearer 支持并做回归测试。
- query key 应进入弃用路线；WebUI 能改用 `fetch` streaming header 后再移除。

### 12.6 管理面、创建与委派

`/v1/**` 是数据面，只消费 Key，不提供 Key 管理 endpoint。管理面继续位于
`/api/v1/rag/api-keys`。MVP 是 platform-operated provisioning，不建设公网租户自助
门户；外部客户拿到的是有限数据面 Key，不是管理权限。

管理面允许两类经过明确验证的 operator principal：

1. 机器自动化：数据库 API Key family，满足 ADMIN role 上限和对应 `keys.*` action。
2. 人类 WebUI：可信 IAP/OIDC/mTLS gateway 验证的 operator identity，经应用侧映射到
   `keys.*` action；不能仅因请求来自代理就视为 ADMIN，也不能把数据库 ADMIN key
   注入浏览器。

API Key 管理 endpoint 自身永远不能因 `rag.security.enabled=false` 变成匿名接口。
认证关闭时应不注册/返回 404，或只允许显式 local-development bootstrap mode；生产
profile 必须 fail closed。

管理面默认只在内网/VPN、API Gateway admin route、IAP 或 mTLS 后开放，不应与公网
`/v1` 数据面共享同等暴露范围。当前 WebUI 把 credential 持久化到 localStorage，且旧
stream 将其放入 query string；该模式只标记为 local-development。生产 WebUI 至少使用
页面内存中的短期 admin credential，优先由外部身份代理建立 HttpOnly/SameSite session，
并配置 CSP、CSRF 与严格 origin；不能把 sessionStorage 当作防 XSS 的安全边界。没有
身份代理时，WebUI 只能部署在可信管理网络。

第一阶段若尚无可信人类身份集成，生产管理面只开放机器自动化路径，WebUI 保持关闭或
仅部署在受控运维环境；不能用 localStorage ADMIN key 作为临时公网方案。

管理授权以 `keys.*` semantic action 为准；`role` 是可授予能力的上限，不是绕过 action
检查的捷径。只有 ADMIN family 可以持有跨主体 `keys.create/read/revoke/policy.write`
action；NORMAL 最多持有显式开启的 self action。

推荐权限：

| 操作 | 默认允许者 |
|---|---|
| 创建外部调用 Key | ADMIN 且有 `keys.create` |
| 列出所有 Key | ADMIN 且有 `keys.read` |
| 查看自己的 Key metadata | 有 `keys.read.self` |
| 吊销任意 family | ADMIN 且有 `keys.revoke` |
| 吊销自己 family 的单个 version | 有 `keys.version.revoke.self` |
| 轮换自己的 Key | 有 `keys.rotate.self` |
| 修改 policy | ADMIN 且有 `keys.policy.write` |

**MVP 关闭 NORMAL self-service create。** 这是管理面安全修复，不保留当前“任意 NORMAL
可创建 NORMAL”的兼容行为；如未来确有产品需求，只能通过下面的显式委派规则重新开放。

如产品明确需要委派，必须同时满足：

- parent policy `allowDelegation=true`。
- child actions、deployments、Collections 都是 parent 的真子集或相等。
- child expiry 不晚于 parent；parent 无 expiry 也不自动允许 child 永不过期。
- child RPM、concurrency、budget 不高于 parent。
- child role 不得提升。
- 保存稳定的 `credentialFamilyId`、`parentCredentialFamilyId` 和
  `delegationDepth`，并限制最大深度；`createdByKeyId` 只用于审计。
- 不能把 parent authority 只绑定到可轮换的 keyId，否则父 Key 轮换会被误判为父权限
  撤销。rotation 只在同一 family 下新增 version，不改变子 family 的 parent。
- 显式 revoke credential family 时，在同一事务内级联 revoke descendant families；
  单纯把旧 version 标记为 rotated 不触发级联。
- 每次 child 请求的 effective actions/deployments/Collections 是 child policy 与全部
  active ancestor policy 的逐层交集；任一 ancestor 过期、吊销或 policy 收窄都立即
  fail closed。不能只在创建 child 时校验一次后永久信任复制结果。
- RPM、concurrency 和 budget 同时记入 child 与全部 ancestor family 的 quota bucket；
  否则创建多个 child 会放大 parent 总配额。另设最大 delegation depth 和每 family
  active child 数量上限，防止无界 fan-out。

管理 API 需要：

- create request 显式包含 owner、role、expiry、actions、deployments、Collection mode
  和 limits；只有 ADMIN 可创建 ADMIN。
- expiry 超出 production max TTL 时拒绝。只有 bootstrap/break-glass ADMIN 流程可使用
  显式配置的例外，该操作必须进入强事务审计并触发告警。
- response 只在成功创建/轮换时含 raw key。
- create/rotate 接受 `Idempotency-Key` 并保存不含 raw secret 的 operation result。若首个
  成功响应丢失，重复请求不得创建第二个 family/version，也不得试图重放 raw secret；
  返回明确的 `secret_already_issued` 冲突和已创建 keyId。create 的未知 family 通过
  family revoke 清理；rotate 的未知新 secret 通过 version-only revoke 清理，不能为此
  误撤销整个 family。
- Idempotency-Key 至少 128 bit 随机、限制长度，并按 authenticated operator principal +
  operation type + path scope 哈希后保存；其他 operator 即使猜到同一值也不能枚举结果。
- list/search 返回 metadata 和 policy 摘要，不返回 hash/raw。
- 大规模使用前新增分页与 owner/status/deployment 搜索；为避免破坏当前数组响应，
  可新增 paginated management endpoint，再迁移 WebUI。
- 修改 policy 使用 optimistic locking / `policyVersion`，避免覆盖并发管理操作。

### 12.7 吊销、轮换与 ADMIN 保护

面向管理调用方的默认撤销对象是 credential family，而不是某一个可轮换 version：

- `DELETE /api/v1/rag/api-keys/{keyId}` 先解析 `keyId -> familyId`，再撤销整个 family。
  这样从旧 keyId 发起撤销也不会漏掉同 family 的新 version。
- `DELETE /api/v1/rag/api-key-versions/{keyId}` 只撤销该 version，用于 overlap 提前收口、
  单一 secret 泄漏和 rotate 响应丢失恢复；self action 只能操作 caller 自己的 family。
- family 撤销会拒绝其所有 version，并通过 `parent_family_id` 递归撤销所有 descendant
  families。实现可使用递归 CTE + 行锁，或先锁定受影响 family 集合后批量更新。
- version-only revoke 不替代 family revoke；两者使用不同路径和 action，避免操作语义
  含糊。撤销当前最后一个有效 version 时必须返回影响提示，但 ADMIN emergency 操作可
  显式确认。

吊销要求：

- 数据库状态变更与审计事件在一个业务事务中提交。API Key lifecycle audit 是安全记录，
  不能沿用当前 `AuditLogService` 吞错后继续成功的 best-effort 语义；审计写失败时管理
  操作必须回滚，或写入同事务 outbox 后再异步投递。
- 启用数据库 Key 管理时，lifecycle audit repository/outbox 必须存在；不能因为
  optional bean 缺失而静默关闭安全审计。
- 响应成功后，所有实例必须立即或在明确的极短上限内拒绝旧 key。
- 吊销已吊销 family 应幂等。
- 禁止撤销后不再存在可用 ADMIN family；需要在同一事务中锁定受影响 family tree 和
  ADMIN 保护行/集合，按 family 而不是 active version 行计数。
- 记录 operator keyId/familyId、target keyId/familyId、owner、reason、trace ID 和
  client IP，不记录 raw/hash。

轮换要求：

- 在同一 family 下创建新的 version；owner、tenant/project、role、policy、delegation
  和限额留在 family，不做容易漂移的逐行复制。
- 普通 rotation 在受上限约束的短 overlap 内允许新旧两个 ACTIVE version，旧 version
  必须带 `retireAt`；immediate cutover 才要求提交后只剩新 version ACTIVE。
- 并发 rotation 必须在 family 行锁内串行化；第二个请求看到第一个已创建的新 version 后
  按 idempotency/冲突规则结束，不能再创建第三个 active version。
- family 已过期时拒绝 rotation。默认保留仍有效的 family expiry且绝不自动延长；需要
  延长时必须由有权主体执行独立、带 optimistic locking 和审计的 expiry/policy 更新。
- 可用性优先的默认策略是创建新 version 后保留一个很短、可配置且有硬上限的 overlap
  window；旧 version 写入 `retireAt`，避免响应丢失时调用方立即失联。
- 紧急泄漏处置可由 ADMIN 显式请求 immediate cutover；普通 self rotation 不允许无限
  延长 overlap，任何路径都不能形成永久双活。
- 调用方应在 overlap 内部署新 secret、用新 secret 完成探活，再显式提前 retire 旧
  version；即使未确认，旧 version 也在 `retireAt` 自动失效。
- old/new keyId 通过 `rotatedFromKeyId` 关联，审计中可追踪；旧 version 使用明确的
  rotated/revoked reason，不能把 credential family 误标为整体撤销。

### 12.8 Bootstrap 与 legacy static key

生产环境不应把首次 ADMIN raw secret 写入集中日志。

推荐 bootstrap：

1. 运维在 Secret Manager / Kubernetes Secret 中提供一次性 secret；优先使用文件挂载
   `RAG_BOOTSTRAP_ADMIN_KEY_FILE`，环境变量 `RAG_BOOTSTRAP_ADMIN_KEY` 仅作为兼容方式。
2. 首实例在数据库锁保护下保存其 hash 和 ADMIN policy。
   expand 回滚窗口内同时维护 12.13 定义的 legacy ADMIN shadow；contract 后停止。
3. 应用不打印 raw secret；成功后提示删除 bootstrap 输入。
4. 多副本同时启动时使用 PostgreSQL advisory lock 或唯一 bootstrap marker，避免多个
   实例各自创建 ADMIN。
5. 表非空但不存在可用 ADMIN 时 readiness 失败并要求显式恢复流程；不能静默把任意最早
   NORMAL Key 提升为 ADMIN，也不能每次启动自动再生成管理员。

本地开发可保留显式 opt-in 的“生成并打印一次”模式，但 prod profile 必须拒绝该模式。

`rag.security.api-key` static legacy key：

- 迁移期只保留旧 `/api/**` 使用。
- 不允许访问 `/v1/**`，因为它无法表达 keyId、owner、deployment scope 和 quota。
- 不允许调用 Key 管理 endpoint 或作为 ADMIN。
- 文档标记 deprecated，并提供转换为数据库 Key 的运维步骤。

### 12.9 验证缓存、多实例与 last-used

当前 30 秒 JVM positive cache 会导致节点 A 吊销后，节点 B 最长约 30 秒仍接受旧
principal。对外兼容层首版推荐：

- 不缓存正向认证结果；每次使用唯一 `key_hash` 索引查数据库。
- RAG 调用的主要成本在检索和模型，一次索引查询是更可控的安全成本。

如压测证明必须缓存，缓存值至少包含当前 family 及 ancestor chain 的
`policyVersion/status/expiresAt`，并配合：

- Redis/pub-sub 或数据库通知的跨实例失效。
- 短 TTL 作为失效失败时的上限。
- revoke/rotate/policy update 的集成测试覆盖两个应用实例。

`last_used_at` 不应每请求同步写：

- 每个 key 最多每 5-15 分钟更新一次，或异步批量 flush。
- 业务请求不等待 last-used 写入。
- 失败只影响观测，不影响已通过的认证。

### 12.10 限流、并发和预算

当前 `RateLimitFilter` 实际是 JVM 本地固定窗口，不是分布式 sliding window；而
`key-limits` 在 `api-key` strategy 下以 raw header 字符串为配置 key，会迫使运维把
secret 放进配置。

目标：

- auth 先解析 principal，再由 rate limiter 使用稳定 `credentialFamilyId`；rotation 和
  overlap versions 共用同一 quota bucket，不能通过换 key 重置配额。
- 限额来自 `ApiKeyPolicy.limits`，不按 raw secret 配置。
- 至少支持每 Key RPM 和最大并发。
- delegated credential 的请求同时消耗自身和所有 ancestor family 的 quota。
- stream 请求从开始到 complete/error/cancel 全程占用一个 concurrency permit。
- 所有退出路径必须释放 permit。
- 单实例可使用本地 limiter；多副本生产必须使用共享 limiter（例如 gateway/Redis）。
  按实例折算不能提供稳定全局配额，只能作为明确的非生产诊断模式，并在 readiness 中暴露。
- gateway 只有在自身完成 credential -> family principal 校验，或消费应用签名的可信
  principal header 时才能按 family 限流；否则应由应用使用 Redis 等共享 backend，不能
  让 gateway 直接以 raw Bearer token 作为 limiter key。
- production 所需共享 limiter 不可用时启动/readiness 必须失败；运行期 backend 故障
  返回 503 `service_unavailable`，不能 fail open 绕过 quota。
- 未认证攻击流量另设 pre-auth IP limiter；不要让业务 per-key limiter承担该职责。
- client IP 只从受信任反向代理写入的 forwarded header 解析；直连或代理链不可信时使用
  socket remote address。不能像当前实现一样无条件信任 caller 提供的
  `X-Forwarded-For`。
- token/cost budget 只基于可信 usage 扣减。上游不返回 usage 时，必须事先选择
  “仅告警/拒绝该 deployment/不提供硬预算”之一，不能伪造 token。

Filter 顺序：

```text
RequestTraceFilter
  -> optional pre-auth IP limiter
  -> ApiKeyAuthFilter
  -> ApiKeyAuthorization / per-key limiter
  -> Controller
```

### 12.11 Filter 注册与运行拓扑

必须覆盖：

```text
/api/*
/v1/*
```

同时需要解决当前 runtime 拓扑：

- core 自带 runnable `SpringAiRagApplication`。
- starter 才包含 `GeneralRagAutoConfiguration`。
- core POM 不依赖 starter。

推荐把 filter registration bean 抽到 core 的共享 `RagWebFilterConfiguration`：

- core standalone 通过组件扫描加载。
- starter 通过 `@Import` 显式加载。
- 使用明确 bean name 和 conditional，防止重复注册。
- 给 trace、pre-auth limiter、auth、authorization/per-family limiter 分配不冲突的显式
  order；不能继续依赖两个 order=1 filter 的容器排序。
- 共享配置同时修复 core standalone 当前 `/api/*` 未注册 auth/rate-limit filter 的问题，
  并为两种启动拓扑增加集成测试。
- `/v1/*` pattern 只在 `rag.openai-compatibility.enabled=true` 时加入；关闭功能后不得
  仅因 filter 存在而改变 `/v1/**` 的 404/error 形态。

不能只在 starter 中加 `/v1/*`，否则 core standalone 启动路径仍可能不受保护。

为执行 action scope，推荐新增基于 annotation + `HandlerInterceptor` 的语义授权：

- `@RequiresApiAction("chat.completions.invoke")`
- `@RequiresApiAction("models.read")`
- Key 管理方法使用 `keys.*`

对带新 policy 的外部 Key，未标注 action 的业务 endpoint 默认拒绝；legacy DB Key 的
兼容策略必须显式配置和测试，不能因“未标注”默认为全权。

`ApiSloConfig` 同时映射 `/v1/**`。普通 MVC 请求可复用 interceptor；SSE 的 SLO 必须由
stream subscription 到 complete/error/cancel 的专用 timer 记录，不能把 Controller
返回 `SseEmitter` 的时间误当作完整请求延迟。

### 12.12 CORS

启用 CORS 时同时映射：

```text
/api/**
/v1/**
```

`/v1/**` mapping 同样受 `rag.openai-compatibility.enabled=true` 控制。

允许 `Authorization`、`Content-Type`、`X-API-Key`、`X-Trace-Id`。生产环境不建议
`allowed-origins: *` 与凭据组合；浏览器场景还应使用明确 origin allow-list。

### 12.13 数据迁移顺序

从当前 V24 基线开始，必须采用 expand/contract。当前 entity 映射了 `api_key` 且启动
使用 `ddl-auto=validate`；若先 drop column/table，再回滚到旧应用，旧版本会直接启动
失败。更重要的是，若把新 external Key 继续写入旧 `rag_api_key`，滚动升级期间的旧实例
会按粗粒度 legacy 规则接受它，可能绕过 action/deployment policy。因此新凭证必须写入
独立的 family/version 表。

**Expand release：**

1. 检查 `rag_api_key.api_key` 非空数据；active 行必须创建 replacement、完成调用方切换、
   吊销旧 key 后再清空旧行明文；disabled 行可在保留审计证据后清空。处理完成前阻止
   启用 compatibility。
2. 删除无用途的 plaintext index，并增加 `CHECK (api_key IS NULL)`，先保证任何版本都
   无法写入明文，但暂时保留列满足旧 entity validation。
3. 新建 `rag_api_key_family` 和 `rag_api_key_version`，包含 12.3 定义的状态、owner、
   lineage、policy、TIMESTAMPTZ lifecycle 字段、唯一约束和查询索引。
4. 为每个 legacy `rag_api_key` 建立一个独立 family 和初始 version，复用原 keyId/hash。
   `enabled=false` 映射为 revoked family/version，其余映射为 active。旧表存在非空
   lifecycle 值时，部署者必须通过 Flyway placeholder
   `legacyApiKeyTimezone` 提供有效 IANA source timezone，再使用
   `created_at/last_used_at/expires_at AT TIME ZONE <source>` 回填为 `Instant`；缺失或
   非法时 migration fail，不能按当前节点默认时区猜测。全新空表可安全使用 UTC。
5. 将 legacy role 和 `allowed_collection_ids` 映射为 family metadata 与显式 Collection
   scope。现有 ADMIN 仅自动获得恢复管理所需的 `keys.*` action，不自动获得 `/v1`
   deployment/data actions；现有 NORMAL 也不自动获得 `/v1`。调用 OpenAI-compatible
   数据面必须由管理员显式转换/授予，避免升级即扩大权限。
6. 新代码以 family/version 表作为 principal source of truth。旧 `/api/**` 的业务数据
   endpoint（chat/search/document/collection 等）在迁移期通过明确的 legacy
   authorization adapter 保持既有契约；**API Key 管理 endpoint 不进入该宽松 adapter**，
   新版本一律要求 family principal + `keys.*` action，static/null caller 和 NORMAL
   self-service create 不得被兼容回来。新 `/v1/**` 只读取新 policy。
7. 对迁移来的 legacy credential，revoke/status/Collection 收窄需要双写旧 shadow 表；
   effective Collection scope 取 legacy 与新 policy 的交集。旧实例全部退出前，在
   gateway 阻断 Key 管理 endpoint，禁止 create/rotate/policy grant，并保持
   compatibility disabled，避免旧 Controller 的 null/static caller 缺口继续暴露。
8. 新 external credential 只写 family/version 表，绝不写 legacy shadow。因而旧实例或
   回滚到 V24 时不会错误接受它；代价是该 credential 在旧版本上明确不可用。
9. expand 回滚窗口内，唯一例外是受控 platform break-glass ADMIN：其 active version
   需要双写 legacy shadow。这样全新环境回滚到 V24 时旧 bootstrap 不会因
   `rag_api_key.count()==0` 再生成并打印 raw ADMIN，且仍有明确的回滚管理 credential。
   该 shadow 只用于回滚，不授予普通 external family。
10. 发布移除 `RagApiKey.apiKey` 映射并使用新实体/Repository 的应用版本；确认所有实例
   升级后，才解除管理面维护门禁、创建 external Key 和启用 compatibility。

**Contract release：**

1. 经过回滚观察窗并确认所有认证、旧 `/api/**` 和管理面都使用新表后，删除整个 legacy
   `rag_api_key` shadow table；这同时永久删除 plaintext 列和 legacy enabled/ACL 字段。
2. 删除 dual-write 和 legacy authorization adapter。
3. contract release 的回滚目标必须是已使用 family/version 表、且不映射 legacy
   `rag_api_key` 的兼容版本，不能回滚到 V24 时代应用。

迁移必须有 Testcontainers PostgreSQL 集成测试，覆盖：全新数据库、V24 expand 升级、
新旧应用兼容窗口、new-only external credential、contract、存在 revoked/expired/admin/
normal key、异常 plaintext 行、legacy timezone backfill、migration rerun 和允许的回滚
目标。还必须验证 expand 回滚到 V24 时新 external Key 是“不可用”而不是“被宽松授权”，
且全新环境不会触发 V24 bootstrap 再生成日志 ADMIN。

## 13. 错误契约

标准 envelope：

```json
{
  "error": {
    "message": "Unknown model 'rag-missing'.",
    "type": "invalid_request_error",
    "param": "model",
    "code": "model_not_found"
  }
}
```

映射建议：

| 场景 | HTTP | type | code |
|---|---:|---|---|
| body/字段校验失败 | 400 | `invalid_request_error` | `invalid_request` |
| 不支持参数 | 400 | `invalid_request_error` | `unsupported_parameter` |
| 不支持 role/content | 400 | `invalid_request_error` | `unsupported_value` |
| deployment 不存在或 caller 不可见 | 404 | `invalid_request_error` | `model_not_found` |
| 缺失/错误 API key | 401 | `invalid_request_error` | `invalid_api_key` |
| action / Collection policy 拒绝 | 403 | `invalid_request_error` | `permission_denied` |
| 限流 | 429 | `rate_limit_error` | `rate_limit_exceeded` |
| circuit open | 503 | `server_error` | `service_unavailable` |
| 上游超时 | 504 | `server_error` | `upstream_timeout` |
| 上游 provider 失败 | 502 | `server_error` | `upstream_error` |
| 未分类内部错误 | 500 | `server_error` | `internal_error` |

实现要求：

- 新增 scope 到兼容 Controller 的 `@RestControllerAdvice`，或在全局 advice 中按 request path 分流。
- Auth/RateLimit Filter 在 MVC advice 之前执行，必须使用共享 `OpenAiErrorWriter` 做 path-aware 输出。
- provider 原始错误消息可能含 URL、key 片段或内部信息，必须脱敏后再对外。
- response header 保留 `X-Trace-Id`。
- `/api/**` 继续使用现有 RFC 7807，不得被兼容层改成 OpenAI error。

## 14. Models API

### 14.1 列表

```json
{
  "object": "list",
  "data": [
    {
      "id": "rag-default",
      "object": "model",
      "created": 1784659200,
      "owned_by": "spring-ai-rag"
    },
    {
      "id": "rag-legal",
      "object": "model",
      "created": 1784659200,
      "owned_by": "spring-ai-rag"
    }
  ]
}
```

### 14.2 Retrieve

`GET /v1/models/{deploymentId}`：

- 存在且 caller 可访问：返回 model object。
- 不存在或不可见：404 `model_not_found`。
- deployment ID 不允许 `/`，避免 path 解析歧义。

### 14.3 可见性

- 只列 enabled deployment。
- backend candidates 全部 unavailable 的 deployment 不列出。
- deployment scope 与 caller ACL 完全冲突时不列出。
- 不暴露 API key、provider base URL、内部 fallback 细节。
- 如需要管理视图，继续使用 `/api/v1/rag/models`。

## 15. 代码落点

建议文件组织：

```text
spring-ai-rag-api/
  src/main/java/com/springairag/api/openai/
    OpenAiChatCompletionRequest.java
    OpenAiChatCompletionResponse.java
    OpenAiChatCompletionChunk.java
    OpenAiChatMessage.java
    OpenAiErrorResponse.java
    OpenAiModelResponse.java
    OpenAiModelListResponse.java

spring-ai-rag-core/
  src/main/java/com/springairag/core/config/
    OpenAiCompatibilityProperties.java
    RagOpenAiCompatibilityConfiguration.java

  src/main/java/com/springairag/core/compat/openai/
    OpenAiChatCompletionsController.java
    OpenAiModelsController.java
    OpenAiRequestMapper.java
    OpenAiResponseMapper.java
    OpenAiSseWriter.java
    OpenAiCompatibilityExceptionHandler.java
    RagDeploymentRegistry.java

  src/main/java/com/springairag/core/chat/
    RagChatCommand.java
    RagChatResult.java
    RagChatStreamEvent.java
    RagExecutionService.java
    ChatGenerationOptionsFactory.java
    ResolvedChatModel.java

  src/main/java/com/springairag/core/config/
    RagWebFilterConfiguration.java

  src/main/java/com/springairag/core/security/
    ApiKeyPrincipal.java
    ApiKeyPolicy.java
    ApiKeyCredentialResolver.java
    ApiKeyAuthorizationService.java
    RequiresApiAction.java
    ApiKeyAuthorizationInterceptor.java
    AuthorizedRagScope.java

  src/main/java/com/springairag/core/service/
    ApiKeyManagementService.java
    ApiKeyBootstrapService.java

  src/main/java/com/springairag/core/entity/
    RagApiKeyFamily.java
    RagApiKeyVersion.java
    RagApiKeyOperation.java

  src/main/java/com/springairag/core/repository/
    RagApiKeyFamilyRepository.java
    RagApiKeyVersionRepository.java
    RagApiKeyOperationRepository.java

  src/main/resources/db/migration/
    V25+__api_key_*.sql

spring-ai-rag-starter/
  GeneralRagAutoConfiguration.java
```

包名可在实施时依据现有风格微调，但职责边界不应退回到单一巨型 Controller。

## 16. 分阶段实施步骤

### Phase 0A：锁定契约与回归基线

1. 将本规划转成 implementation checklist。
2. 保存当前 `mvn test`、关键 controller tests 和 E2E 的基线结果。
3. 为现有 `/api/v1/rag/chat` 非流式和流式添加行为快照测试。
4. 修正测试中“名称写 [DONE] 但未断言真实 SSE 字节”的误导。
5. 明确 feature flag 默认关闭。

完成标准：

- 尚未注册 `/v1/**`。
- 现有 API 行为由测试固定。

### Phase 0B：外部 API Key 基础加固

1. 为现有 API Key 行为补齐 characterization tests，包括 static key、auth disabled、
   NORMAL 委派、ADMIN 轮换、多实例 cache 边界，以及 core standalone/starter 的实际
   filter 装配差异。
2. 按 expand/contract 新增 migrations：在 legacy 表增加 plaintext null constraint，
   新建 family/version 表，并维护唯一 break-glass ADMIN shadow；经过观察窗后删除整个
   legacy table。
3. 新增 immutable `ApiKeyPrincipal`、`RagApiKeyFamily`、`RagApiKeyVersion`、typed JSONB
   `ApiKeyPolicy` 和 family policy version。
4. 每个 legacy Key 回填为独立 family/version，但不自动获得 `/v1` 权限；为新 external
   Key 使用 secure defaults。
5. 改用 `SecureRandom` 生成至少 256 bit secret，保持旧 `rag_sk_` key 可继续验证。
6. 修正管理授权：默认关闭 NORMAL self-service create；static/null caller 不可管理或
   轮换任意 Key；事务保护最后一个 ADMIN family。
7. 修正 rotate：同 family 创建新 version，默认不延长 family expiry，并定义 overlap /
   retireAt；`DELETE {keyId}` 明确定义为 family revoke 并级联 descendant families。
8. 生产 bootstrap 改为 Secret Manager/Kubernetes Secret 输入，不再打印 raw key。
9. external path 首版移除正向认证缓存；节流/异步更新 `last_used_at`。
10. create/revoke/rotate/policy update 写入结构化 audit，operator 使用 caller keyId。
11. create/rotate 响应增加 no-store headers，生产部署文档要求 TLS。
12. 增加 WebUI 所需的 owner、deployment、scope、limits 展示/编辑设计，并去除生产
    admin credential 的 localStorage/query 存储；UI 实现可跟随管理 API PR，不阻塞纯
    SDK 数据面验证。

完成标准：

- 可以创建一个只允许 `models.read`、`chat.completions.invoke` 和指定 deployments /
  Collections 的外部 Key。
- raw secret 只出现一次且不持久化、不进日志。
- revoke/rotate 后旧 secret 按定义立即失效。
- family policy 更新同时约束所有 active/overlap versions；family revoke 覆盖全部
  versions 和 descendants。
- static legacy key 不能调用 `/v1` 或管理数据库 Key。
- expand 回滚到 V24 时新 external Key 不可用，且不会被旧授权规则接受。
- 安全基础未完成前不得进入兼容 endpoint 上线阶段。

### Phase 1：内部执行模型

1. 新增 `RagChatCommand`、`RagChatResult`、`RagChatStreamEvent`。
2. 从 `RagChatService` 抽取 `RagExecutionService`。
3. 保留 legacy adapter，使现有 public service/controller 行为不变。
4. 分离 base advisors 与 memory advisor。
5. 支持完整 `List<Message>`。
6. 从完整消息中提取 latest user query。
7. 抽取 usage、finish reason、sources、step metrics、resolved model ref。
8. 统一非流式和流式 metadata audit，并按显式 mode 控制完整内容留存。
9. 增加流式 cancellation cleanup。

完成标准：

- legacy API 所有测试通过。
- stateless 执行不访问 ChatMemory。
- server memory 执行仍保持多轮行为。
- streaming 完成后只写一条 metadata audit；FULL 模式最多再写一条内容记录。

### Phase 2：Deployment 与模型能力

1. 新增 `OpenAiCompatibilityProperties`。
2. 新增 `RagOpenAiCompatibilityConfiguration`，明确 standalone 与 starter 两种装配路径。
3. 新增 `RagDeploymentRegistry` 与启动期校验。
4. router 返回带 ref/capabilities 的候选对象。
5. deployment fallback 不自动扩张到未配置模型。
6. provider adapter 改为按 resolved candidate 选择。
7. 新增 `ChatGenerationOptionsFactory`。
8. 增加 per-request query rewrite/rerank context 开关。
9. 确保 rerank disabled 时仍注入 retrieval context。

完成标准：

- deployment 可稳定映射 RAG policy。
- 动态 OpenAI/Anthropic/MiniMax candidate 的 Prompt normalization 使用正确 adapter。
- 不支持的 generation 参数在上游调用前被拒绝。

### Phase 3：协议 DTO、Controller 与错误

1. 在 api 模块增加 OpenAI DTO。
2. 实现 presence-aware request mapper、未知字段捕获和严格 validation。
3. 实现非流式 response mapper。
4. 实现 `/v1/chat/completions`。
5. 实现 `/v1/models` 和 retrieve。
6. 实现 OpenAI error envelope。
7. 让全局 RFC 7807 customizer 排除 `/v1/**`，或建立独立 OpenAPI group。

完成标准：

- curl 和 MockMvc 返回标准字段。
- unsupported fields 不被静默忽略。
- feature flag false 时 `/v1/**` 不存在。

### Phase 4：标准 SSE

1. 实现 `OpenAiSseWriter`。
2. 发送 role chunk、content chunks、finish chunk。
3. 按需发送 usage-only chunk。
4. 发送精确 `data: [DONE]\n\n`。
5. 保持稳定 completion ID、created 和 deployment model。
6. 实现首 chunk 前 fallback。
7. 实现 client disconnect cleanup。
8. 保留 comment heartbeat，并做真实客户端验证。

完成标准：

- 原始字节测试通过。
- 官方 OpenAI Python/Node SDK 均可消费。
- 不再把 custom `event:done` 当成 OpenAI completion 结束。

### Phase 5：鉴权、ACL、限流与运行拓扑

1. 接入 Phase 0B 的 credential resolver 和 immutable principal，支持 Bearer。
2. 新增 path-aware error writer。
3. filter 覆盖 `/v1/*`。
4. 调整 auth/rate limit 顺序。
5. action scope、deployment allow-list 和 Collection scope 复用统一 authorization service。
6. rate limit 使用 stable familyId 与 policy limits，并覆盖 stream concurrency 生命周期。
7. CORS 覆盖 `/v1/**`。
8. 验证 core standalone 与 starter demo 两种启动方式。
9. 多副本环境验证 revoke 可见性和 limiter 的共享/折算策略。
10. SLO interceptor 覆盖 `/v1/**`，stream 使用终态 timer。

完成标准：

- compatibility enabled 时无数据库 Key principal 必须 401。
- restricted key 无法通过 deployment/request/documentIds 绕过 Collection ACL。
- models 和 chat 对 deployment 的授权一致。
- rate limit 使用 authenticated stable familyId，不读取或配置 raw secret。
- static legacy key 被 `/v1` 拒绝。
- trace/auth/limit order 确定且无冲突；SLO 同时覆盖非流式和完整 stream 生命周期。

### Phase 6：文档、E2E 与发布

1. 增加中英文兼容 API 文档。
2. 更新 `docs/index-zh-CN.md` / `docs/index.md`。
3. 修正 `docs/SSE-PROTOCOL.md` 中“当前流已完整 OpenAI 兼容”的表述。
4. 更新配置文档和 `.env.example`，但不写入真实 key。
5. 增加 OpenAI SDK E2E 脚本。
6. 更新部署文档中的 ingress SSE 配置。
7. 运行完整测试链和真实 LLM smoke。

## 17. 测试规划

### 17.1 单元测试

Request mapper：

- 空 model、空 messages、无 user message。
- 可选字段未提供、显式 null、具体值的三态。
- unknown field 捕获；strict 模式下 non-null unknown 精确报错。
- role 映射与顺序。
- developer -> system 语义降级。
- string content。
- text parts 数组。
- multimodal part 拒绝。
- `n=1` 与 `n>1`。
- `max_tokens` / `max_completion_tokens` 冲突。
- unsupported field 精确 param。
- request size limits。

Deployment：

- default deployment。
- duplicate/invalid ID。
- empty candidate list。
- candidate 部分 unavailable 时跳过并保留可用顺序。
- enabled deployment 所有 candidate unavailable 时启动失败或不注册。
- domain 不存在。
- collection ID 非法。
- caller 可见性。

ACL：

- unrestricted deployment + unrestricted key。
- fixed deployment + unrestricted key。
- unrestricted deployment + restricted key。
- fixed deployment 是 key allow-list 子集。
- fixed deployment 与 key allow-list 部分重叠时只使用交集。
- fixed deployment 与 key 完全冲突。
- request override 超出 deployment。
- documentIds 被最终 collections 正确收缩。
- empty resolution 不退化为全库。

Execution：

- stateless 不安装 memory advisor。
- stateless audit 使用 supplied session_id 或 completionId，且不因此启用 memory。
- server memory 需要 session。
- server memory 只允许 system/developer + 恰好一个当前 user turn。
- server memory 拒绝 caller 历史 user/assistant/tool turn。
- messages 全量传给 ChatClient。
- latest user 作为 retrieval query。
- domain prompt 顺序。
- PromptCustomizer 只修改目标消息。
- legacy API 行为不变。
- non-stream fallback 顺序。
- stream 首 chunk 前 fallback。
- stream 首 chunk 后不 fallback。
- usage/finish reason/model ref 抽取。
- metadata audit 对成功/失败/取消各写一次状态记录。
- 默认 metadata-only 不写 `rag_chat_history`；FULL 模式成功一次、失败/取消零次。
- global/deployment/family mode 取最严格值；external Key 默认无法启用 FULL。

SSE：

- 首 chunk 包含 assistant role。
- content chunk JSON 转义。
- 所有 chunk 共用 id/created/model。
- finish chunk。
- include_usage true/false。
- `[DONE]` 精确字节。
- heartbeat comment 不影响 parser。
- error 不发送成功 finish 或 `[DONE]`。
- disconnect dispose。

Security：

- Bearer 成功、缺失、错误 scheme、空 token。
- X-API-Key compatibility flag。
- 双 header 相同/冲突。
- static key 调用 `/v1` 被拒绝。
- credential DB/policy 故障返回 503，且不会 fallback 到 static key。
- `/v1/*` filter pattern。
- auth 在 user rate limit 之前。
- action scope 拒绝未授权 endpoint。
- deployment allow-list 同时约束 models list/retrieve 和 chat。
- Collection scope 的 ALL/LIST/NONE 语义。
- ADMIN 数据面权限来自显式 policy，不因 role 自动绕过 scope。
- per-key RPM 与 concurrency；stream cancel/error 正确释放 permit。
- rotation/overlap versions 共用 family quota；多个 child 同时消耗 ancestor quota。
- shared limiter 不可用时 fail closed；不可信 X-Forwarded-For 不能绕过 pre-auth limit。
- 401/403/429 都是 OpenAI envelope。
- `/api/**` 仍是旧错误格式。

API Key 生命周期：

- SecureRandom secret 长度/熵和旧 key 兼容验证。
- entity、repository、日志、audit 均不含 raw secret。
- create/rotate response 有 no-store headers；生产入口强制 TLS。
- V24 -> family/version expand -> contract migration；发现 plaintext 行时 fail。
- expand 回滚到 V24 时 legacy Key 保持既定状态，新 external Key 安全地不可用；contract
  后只能回滚到使用 family/version 表的兼容版本。
- fresh install 的 break-glass ADMIN 在 expand 窗口有受控 legacy shadow，回滚 V24
  不会触发第二次 bootstrap 或把新 raw ADMIN 写入日志。
- 兼容窗口迁移 credential 的认证要求 legacy enabled 与 family/version status 均有效，
  Collection scope 取新旧交集。
- external key secure defaults；空 deployment/Collection 不解释为 ALL。
- external family 无 expiry、expiry 已过或超过 max TTL 时创建失败；break-glass 例外有
  审计和告警。
- NORMAL 默认不能创建 child key。
- 启用委派时 actions/deployments/Collections/expiry/limits 均不可提权。
- parent policy 收窄、过期或吊销立即约束 child；多个 child 共用 ancestor quota，不能
  通过 fan-out 放大 RPM/concurrency/budget。
- parent family 显式 revoke 会级联 child families；parent rotation 不会误伤 child。
- 并发 child create/rotate/revoke 在 family 行锁下仍满足 depth、child count、active
  version 数和最后 ADMIN 约束。
- `DELETE {keyId}` 从任一 version ID 都撤销完整 family。
- rotate 保持 credentialFamilyId，family owner/policy/limits/lineage 不发生复制或漂移。
- rotate 默认使用有上限的短 overlap；旧 version 在 retireAt 后即使未清理也无效，紧急
  模式可 immediate cutover。
- rotate 不延长过期时间；expired family 不能 rotate。
- create/rotate 同一 Idempotency-Key 不产生重复 family/version，也不会重放 raw secret。
- operation record 按 operator/operation/key hash 隔离、到期清理，跨 operator 不泄露结果。
- rotate response 丢失后可只撤销未知新 version，不会误撤销整个 family。
- 无 offset 的新 expiry 被拒绝；旧 TIMESTAMP 按显式 legacy timezone 正确迁移为 Instant。
- static/null caller 不能 rotate 任意 key。
- 最后一个可用 ADMIN family 在并发/cascade revoke 下仍受保护。
- revoke、policy update 立即使其他实例旧 principal 失效；rotation 按 immediate 或
  retireAt 语义使旧 version 失效。
- `last_used_at` 节流且写入失败不影响业务请求。
- bootstrap 多实例只有一个 ADMIN，prod 日志不含 raw secret。
- key management audit 不可用时管理操作 fail closed。
- 生产 WebUI 不把 admin key 放入 localStorage 或 query string。

### 17.2 Contract / MVC 测试

- `POST /v1/chat/completions` 非流式完整 JSON schema。
- `POST /v1/chat/completions` SSE 原始内容。
- `GET /v1/models` list envelope。
- `GET /v1/models/{id}`。
- 404 model error。
- `Content-Type`、cache headers、trace header。
- feature flag disabled。
- global OpenAPI 文档不把 RFC 7807 schema错误套到 `/v1/**`。
- management create 只返回一次 raw key；list/search 永不返回 raw/hash。
- management 401/403、self/admin、revoke/rotate/policyVersion 冲突。

### 17.3 SDK E2E

新增独立脚本，真实启动服务后执行：

```text
Python openai SDK
  - models.list()
  - chat.completions.create(stream=False)
  - chat.completions.create(stream=True)

Node openai SDK
  - models.list()
  - chat.completions.create(stream=False)
  - for await stream
```

SDK E2E 必须断言：

- 无自定义 HTTP 代码即可连接。
- 非流式能读取 `choices[0].message.content`。
- 流式能迭代 delta。
- 正常遇到 `[DONE]` 并结束。
- model 为 deployment ID。
- invalid key/model 转为 SDK 可识别异常。
- key A 只能看/调用其 allowed deployment；key B 看不到该 deployment。
- revoke key A 后，同一 SDK client 的下一次请求失败。
- 同一 key 超出 RPM/concurrency 时得到 SDK 可识别的 429。

### 17.4 项目门禁

实施完成前至少执行：

```bash
mvn test
scripts/e2e-test.sh
scripts/openai-compat-e2e.sh
scripts/real-llm-e2e-smoke.sh
```

数据库 migration 和多实例安全测试应使用 Testcontainers PostgreSQL，不得只用 mock
repository。若实现共享 limiter/cache invalidation，还需启动两个应用实例验证。

如改动 WebUI 对新 endpoint 的使用，再运行 Playwright；MVP 不要求 WebUI 改用兼容 API。

## 18. 可观测性与审计

建议增加：

- `rag.openai.compat.requests`，tag：deployment、stream、status。
- `rag.openai.compat.latency`。
- `rag.openai.compat.first_token_latency`。
- `rag.openai.compat.fallback.count`。
- `rag.openai.compat.unsupported_parameter`。
- `rag.openai.compat.client_disconnect`。
- `rag.openai.compat.tokens`，仅 usage 可用时记录。
- `rag.auth.api_key.validation`，tag 仅含 result/reason，不含 keyId。
- `rag.auth.api_key.policy_denied`，tag：action/reason。
- `rag.auth.api_key.lifecycle`，tag：operation/result。
- `rag.auth.api_key.last_used_write` 和 cache invalidation 指标。

日志必须包含：

- trace ID。
- deployment ID。
- resolved backend model ref。
- API key ID，不包含原始 key。
- stream/non-stream。
- fallback candidate index。
- effective collection IDs；如敏感，可只记录 count/hash。

异步流开始前必须把 trace ID 固化到 `RagChatCommand` / stream context；Reactor 回调、
heartbeat 和 completion audit 不得依赖请求线程结束后仍保留 MDC。必要时使用 Reactor
Context 或显式参数恢复日志上下文，并在所有终态清理。

审计 metadata 建议包含：

```json
{
  "protocol": "openai-chat-completions",
  "deployment": "rag-default",
  "backendModel": "minimax/MiniMax-M2.7",
  "stream": true,
  "memoryMode": "stateless",
  "apiKeyId": "rag_k_abc123",
  "tenantId": "tenant-acme",
  "projectId": "support-prod",
  "openaiUser": "external-user-id"
}
```

API Key create/revoke/rotate/policy update 必须写 `rag_audit_log`，补齐当前未使用的
`operator` 和 `client_ip` 字段；details 只保存 scope/policy 摘要和 before/after
policyVersion。

Chat 请求的运营审计默认只保存元数据；FULL content retention 必须显式配置并使用独立
retention。不要在普通日志、安全 lifecycle audit 或 metrics 中记录完整 Authorization
header、原始 API key、完整 Prompt/answer 或敏感 metadata。

## 19. 上线与回滚

### 19.1 上线顺序

1. 先上线 API Key schema、policy 和管理面加固，兼容 endpoint 仍保持 disabled。
2. expand 滚动升级期间在 gateway 阻断 Key 管理 endpoint，不创建/轮换/授权 external
   credential，并保持 compatibility disabled。
3. 完成全量实例升级，确认 plaintext check constraint 生效、family/version 回填正确、
   应用不映射 raw 字段、日志无 raw secret、revoke/rotate 和最后 ADMIN 保护通过。
4. 使用安全 bootstrap/recovery 流程确认 ADMIN，再创建一个有限 expiry/scope/quota 的
   new-only 测试 external key。
5. 验证回滚演练中该 external key 在 V24 上不可用，而不是获得 legacy 宽权限。
6. 测试环境启用单一 `rag-default` deployment。
7. 使用 mock upstream 跑 SDK E2E。
8. 使用真实 provider 跑 smoke。
9. 使用多个不同 policy 的 restricted keys 做 action/deployment/ACL 渗透测试。
10. 单实例压测 RPM/concurrency；多副本验证 revoke 和 limiter 语义。
11. 小流量启用。
12. 观察错误率、policy denial、429、首 token 延迟、fallback、连接取消和 token 用量。
13. 经过应用回滚观察窗后执行 contract migration 删除 legacy table。
14. 再开放更多 deployments。

### 19.2 回滚

首选回滚方式：

```yaml
rag:
  openai-compatibility:
    enabled: false
```

要求：

- 关闭 flag 后 `/v1/**` 立即停止暴露，但 API Key 加固 schema 保留并继续服务旧 API。
- expand 阶段可回滚到 V24 应用；legacy Key 状态由 dual-write 保持，新 external Key 在
  V24 上不可用。已迁移/轮换为 new-only secret 的调用方需要恢复到 legacy credential
  或取消应用回滚，runbook 必须列出受影响 family。
- platform break-glass ADMIN active version 在 expand 窗口保持 legacy shadow；回滚前
  必须验证其可用且旧 bootstrap 不会运行。
- contract 阶段后只能回滚到使用 family/version 表且不映射 legacy table 的版本。
- plaintext `api_key` 不得因回滚重新写入；expand 阶段 check constraint 始终保留。
- 现有 `/api/v1/rag/**` 不依赖兼容 Controller。
- deployment 配置删除不影响 backend model registry。
- external keys 可单独 revoke，作为比全局关闭更细的应急止损。

## 20. 风险清单

| 风险 | 严重度 | 缓解 |
|---|---|---|
| 把 partial SSE 误称完整兼容 | 高 | 原始字节测试 + 官方 SDK E2E |
| caller history 与 server memory 重复 | 高 | 默认 stateless；server mode 严格校验 |
| `/v1` 绕过 auth/rate limit | 高 | 两种 runtime 拓扑均做 filter 集成测试 |
| core standalone 当前 `/api` 未注册 auth/rate limit | 高 | shared core filter config；standalone/starter 回归测试 |
| static/null caller 绕过 Key 管理授权 | 高 | `/v1` 只收 DB principal；管理面显式 action scope |
| credential DB/policy 故障被当作 401 或回退 static key | 高 | 503 + alert；`/v1` 禁止 legacy fallback |
| NORMAL 自助创建 unrestricted 子 Key | 高 | external 默认禁委派；启用时做全 policy 子集校验 |
| rotate 丢失 ADMIN/owner/policy | 高 | principal metadata 只存 family；rotation 只新增 version |
| rotation overlap 中多个 version 的 policy 漂移 | 高 | policy 只存 family；version 只存 secret 生命周期 |
| create/rotate 响应丢失造成未知 secret 或停机 | 高 | 幂等 operation record；默认短 overlap；未知 secret 可定向撤销 |
| 新 external Key 被旧实例按 legacy 规则接受 | 高 | 新 Key 只写 family/version 表；全量升级前禁管理和启用 compatibility |
| fresh install 回滚 V24 再次日志生成 ADMIN | 高 | break-glass ADMIN 双写 legacy shadow；回滚测试断言 bootstrap 不运行 |
| V23 plaintext 列造成未来误存/泄漏 | 高 | expand 检查并加 null constraint；contract 删除 legacy table |
| 多实例撤销受 30 秒 JVM cache 延迟 | 高 | external 首版无 positive cache；后续分布式失效 |
| 本地限流在多副本被倍增 | 高 | 共享 limiter/gateway；readiness 暴露非全局模式 |
| limiter 故障或伪造 X-Forwarded-For 绕过限制 | 高 | production fail closed；trusted proxy 配置 |
| raw key 被限流配置、URL 或日志记录 | 高 | 限额按 familyId/policy；`/v1` 禁 query key；脱敏测试 |
| rotation/委派绕过配额 | 高 | family quota bucket；child 请求同时扣减全部 ancestor bucket |
| Bootstrap raw ADMIN 进入集中日志 | 高 | prod 由 Secret Manager 注入；数据库锁保证单次初始化 |
| 管理 WebUI localStorage/query 泄漏 ADMIN key | 高 | 管理面内网化；短期 session；移除 query secret；CSP |
| external Prompt/answer 被默认长期留存 | 高 | 默认 metadata-only；FULL 显式 opt-in + 独立 TTL/访问控制 |
| 无时区 expiresAt 在节点间解释不同 | 高 | RFC3339 offset + TIMESTAMPTZ/Instant + 显式 legacy timezone backfill |
| 每请求写 last_used 造成数据库写放大 | 中 | 节流或异步批量写 |
| 最后一个 ADMIN family 被并发/级联吊销 | 高 | 事务行锁、family 计数和并发集成测试 |
| deployment/request 绕过 Collection ACL | 高 | 统一 scope service；fail closed 矩阵测试 |
| 动态模型使用错误 Prompt adapter | 高 | candidate 携带 capabilities，执行时选择 adapter |
| stream 中途 fallback 拼接不同模型输出 | 高 | 只允许首 chunk 前 fallback |
| usage 不可用 | 中 | 省略 usage；不估算伪造 |
| unsupported 参数被静默忽略 | 中 | strict validation 和明确 error param |
| 兼容 DTO 跟随 OpenAI 演进 | 中 | DTO 独立 package；官方协议回归；兼容级别声明 |
| 新抽象破坏 legacy API | 高 | legacy contract tests 先行，旧 Controller 不改契约 |
| core/starter 重复或缺少 bean | 高 | 两种启动拓扑测试，明确 shared configuration |
| developer role 语义降级 | 中 | 文档说明；独立 mapper 测试；未来 Spring AI 支持后替换 |
| 上游返回 tool call | 中 | MVP 视为 backend contract error，不输出半兼容结果 |
| 客户端断开后上游继续生成 | 中 | SseEmitter callbacks dispose subscription |
| `rag` 扩展造成客户端类型差异 | 低 | 扩展可关闭；核心字段保持标准 |

## 21. 验收标准

全部满足才可认为完成：

### 协议

- [ ] 官方 OpenAI Python SDK 能 list models。
- [ ] 官方 OpenAI Python SDK 能完成非流式和流式调用。
- [ ] 官方 OpenAI Node SDK 能完成同样调用。
- [ ] 流包含稳定 id/object/created/model/index/delta/finish_reason。
- [ ] 正常流以精确 `[DONE]` 结束。
- [ ] usage 行为符合 include_usage 和上游可用性。
- [ ] unsupported 字段返回明确 OpenAI error。

### RAG

- [ ] model 解析为 deployment，不是裸 backend model。
- [ ] 完整 messages 保持顺序。
- [ ] 最后 user message 用于检索。
- [ ] retrieval context 正常注入。
- [ ] sources 和 step metrics 可通过可选扩展返回。
- [ ] stateless 不读取/写入 ChatMemory。
- [ ] server memory 显式开启且无重复历史。
- [ ] `/v1` 默认 metadata-only，不向 `rag_chat_history` 写完整 prompt/answer。
- [ ] FULL content retention 只能显式启用，caller 不能扩大，并有独立 TTL 和清理验证。

### 安全

- [ ] `/v1/*` 必须经过 auth 和 rate limit。
- [ ] production 中 compatibility enabled 强制 security enabled。
- [ ] Bearer auth 使用数据库 principal；static legacy key 不可调用 `/v1`。
- [ ] raw secret 只在 create/rotate 返回一次，数据库、日志、audit、metrics 不保存。
- [ ] create/rotate 只经 TLS 暴露并返回 no-store headers。
- [ ] expand 后 V23 plaintext 列受 null constraint 保护；contract 后 legacy table 已删除，
      异常历史明文会阻止 migration。
- [ ] external key 有 owner、expiry、action scope、deployment allow-list、Collection
      scope、RPM 和 concurrency。
- [ ] external expiry 必填且不超过 production max TTL；break-glass 例外可追踪。
- [ ] 新 expiry 使用 RFC3339 offset/Instant；legacy 无时区值按显式 source timezone 迁移。
- [ ] `/v1/models` 与 chat 使用相同 deployment authorization。
- [ ] restricted key 无法绕过 action、deployment 或 Collection scope。
- [ ] NORMAL 默认不能创建 child key；委派启用时不能提权。
- [ ] child effective policy 与 ancestor chain 取交集，所有 child 共享 ancestor quota。
- [ ] revoke、rotate、policy update 在多实例语义下按定义生效。
- [ ] family/version 分层，rotate 不复制或延长 family policy/expiry。
- [ ] rotate 默认提供有硬上限的短 overlap，response 丢失时旧 version 仍可用于恢复。
- [ ] create/rotate 幂等重试不生成重复 secret，服务端不存储或重放已签发 raw secret。
- [ ] 从任一 version keyId 撤销均覆盖整个 family，委派 descendants 按定义级联。
- [ ] 最后一个 ADMIN family 在并发和级联撤销下受事务保护。
- [ ] expand 回滚时 new-only external Key 不被 V24 接受。
- [ ] fresh install 回滚 V24 时 break-glass ADMIN 可用，旧 bootstrap 不生成第二个 Key。
- [ ] prod bootstrap 不把 raw ADMIN key 写入日志。
- [ ] API Key lifecycle audit 不可用时管理面 fail closed。
- [ ] 生产管理面不公开暴露，WebUI 不持久化 admin key 到 localStorage/query。
- [ ] rate limit 使用 stable familyId/policy，不需要在配置中写 raw key，rotation 不重置
      quota。
- [ ] error/filter 日志不泄漏 key。
- [ ] feature flag 默认关闭。

### 回归

- [ ] `/api/v1/rag/chat/**` 请求和响应兼容。
- [ ] legacy SSE 消费方不受影响。
- [ ] `mvn test` 全过。
- [ ] 既有 `scripts/e2e-test.sh` 全过。
- [ ] 新兼容 E2E 全过。
- [ ] 真实 LLM smoke 全过。

## 22. 工作量与建议拆分

在当前代码基础上，建议按 6 个可独立评审的 PR 实施：

| PR | 内容 | 估计 |
|---|---|---|
| 1 | API Key family/version schema、policy、管理授权、生命周期、bootstrap、审计 | 7–10 人日 |
| 2 | 内部 command/result、memory 分离、legacy adapter | 3–4 人日 |
| 3 | deployment registry、candidate capabilities、scope/ACL | 2–3 人日 |
| 4 | presence-aware DTO、非流式 endpoint、models、errors、auth | 3–4 人日 |
| 5 | 标准 SSE、fallback、cancellation、usage、concurrency | 3–4 人日 |
| 6 | SDK/多实例 E2E、WebUI 管理、文档、部署与全量回归 | 3–4 人日 |

合计约 21–29 人日。若已有 gateway/Redis 可复用，共享限流部分可能降低；若需要在本项目
新增分布式 limiter，需另计 2–4 人日。只做表面 Controller 会更快，但不满足本规划对
外部服务“基本可用”的定义。

## 23. 实施前批准项

开始修改生产代码前，需要明确批准以下产品/协议决策：

1. `model` 表示 RAG deployment，而非裸 LLM。
2. 默认 stateless。
3. MVP 为 text-only、`n=1`，不支持 tools。
4. `developer` role 暂映射为 system。
5. 标准响应允许可选 `rag` 扩展。
6. feature flag 默认关闭。
7. 第一阶段不新建 Maven 模块。
8. 允许为正确实现而抽取 `RagExecutionService`，同时保持 legacy contract。
9. `/v1` 只接受数据库 Key principal，不接受 legacy static key 或 query secret。
10. API Key policy 使用 owner + action + deployment + explicit Collection scope；external
    NORMAL 默认不可委派。
11. 新 external key 默认必须有限权限和 quota；`ALL` 只能显式授予。
12. 生产 bootstrap 使用运维提供的一次性 secret，不打印自动生成的 raw ADMIN key。
13. API Key 使用 family/version 两层新表；expand 保留受 null constraint 保护的 legacy
    shadow，contract 再删除 legacy table。发现历史明文时 migration fail。
14. MVP 硬配额为 RPM + concurrency；token/cost budget 仅在 usage 可靠后启用。
15. `DELETE /api-keys/{keyId}` 的目标是完整 credential family；rotation 仅替换 version，
    family expiry 默认不延长。

批准后应严格按 Phase 0 开始，不直接从 Controller/SSE 拼装代码切入。
