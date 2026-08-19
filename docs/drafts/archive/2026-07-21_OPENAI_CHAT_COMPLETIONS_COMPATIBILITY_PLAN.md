# OpenAI Chat Completions 兼容 RAG 服务实施规划

> 状态：规划与三轮系统检查已完成，待用户批准，尚未开始实施
> 起草日期：2026-07-21
> 最近复核：2026-08-14
> 适用基线：commit `9af7f666510b3a4df7cbfcd0b1ada3dad5178d48`；实施前仍须按 Phase 0A 重新核对代码与协议
> 目标接口：`POST /v1/chat/completions`、`GET /v1/models`、`GET /v1/models/{id}`
> 实施约束：未经批准不得修改生产代码；实施时必须同步补测试、文档并通过项目既有 E2E 门禁
> 当前代码上下文：[OpenAI 兼容服务就绪度与代码库上下文](../../openai-compatibility-readiness-zh-CN.md)
> 安全前置工程：[API Key 加固独立实施规划](2026-08-14_API_KEY_HARDENING_IMPLEMENTATION_PLAN.md)
> 相关旧稿：[API-KEY-MANAGEMENT-PLAN.md](API-KEY-MANAGEMENT-PLAN.md) 解释了当前内部实现
> 的来源；涉及 bootstrap、委派、secret、轮换、迁移和 WebUI 管理安全的目标决策，
> 以独立 API Key 加固规划为准。

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

- [ChatRequest](../../../spring-ai-rag-api/src/main/java/com/springairag/api/dto/ChatRequest.java)
- [ChatResponse](../../../spring-ai-rag-api/src/main/java/com/springairag/api/dto/ChatResponse.java)
- [RagChatService](../../../spring-ai-rag-core/src/main/java/com/springairag/core/config/RagChatService.java)
- [RagChatController](../../../spring-ai-rag-core/src/main/java/com/springairag/core/controller/RagChatController.java)
- [AdvisorUtils](../../../spring-ai-rag-core/src/main/java/com/springairag/core/advisor/AdvisorUtils.java)
- [ChatModelRouter](../../../spring-ai-rag-core/src/main/java/com/springairag/core/config/ChatModelRouter.java)
- [ConfiguredChatModelFactory](../../../spring-ai-rag-core/src/main/java/com/springairag/core/config/ConfiguredChatModelFactory.java)
- [ApiKeyManagementService](../../../spring-ai-rag-core/src/main/java/com/springairag/core/service/ApiKeyManagementService.java)
- [ApiKeyController](../../../spring-ai-rag-core/src/main/java/com/springairag/core/controller/ApiKeyController.java)
- [ApiKeyBootstrapService](../../../spring-ai-rag-core/src/main/java/com/springairag/core/service/ApiKeyBootstrapService.java)
- [RagApiKey](../../../spring-ai-rag-core/src/main/java/com/springairag/core/entity/RagApiKey.java)
- [ApiKeyCollectionAccess](../../../spring-ai-rag-core/src/main/java/com/springairag/core/security/ApiKeyCollectionAccess.java)
- [CollectionDocumentResolver](../../../spring-ai-rag-core/src/main/java/com/springairag/core/service/CollectionDocumentResolver.java)
- [ApiKeyAuthFilter](../../../spring-ai-rag-core/src/main/java/com/springairag/core/filter/ApiKeyAuthFilter.java)
- [RateLimitFilter](../../../spring-ai-rag-core/src/main/java/com/springairag/core/filter/RateLimitFilter.java)
- [GeneralRagAutoConfiguration](../../../spring-ai-rag-starter/src/main/java/com/springairag/starter/GeneralRagAutoConfiguration.java)
- [GlobalExceptionHandler](../../../spring-ai-rag-core/src/main/java/com/springairag/core/controller/GlobalExceptionHandler.java)
- [SseEmitters](../../../spring-ai-rag-core/src/main/java/com/springairag/core/util/SseEmitters.java)
- [现有 SSE 文档](../../SSE-PROTOCOL.md)
- [代码审计报告](../../2026-07-21_CODE_AUDIT_REPORT.md)

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

## 12. 外部凭据集成契约

### 12.1 独立前置工程

外部 API Key 的 schema、principal、policy、管理生命周期、bootstrap、审计、迁移、
WebUI 凭据安全和共享 quota 已剥离为独立工程：

[API Key 加固独立实施规划](2026-08-14_API_KEY_HARDENING_IMPLEMENTATION_PLAN.md)

该规划是本兼容层的安全前置条件，不是本节的子任务。兼容层不得复制或弱化其中的安全
不变量，也不得在前置工程未达到其验收标准时自行实现一套临时 Bearer Key。

本规划只定义 `/v1` 如何消费加固后的能力。

### 12.2 上线门禁

生产或非开发 profile 启用 `rag.openai-compatibility.enabled=true` 时，必须同时满足：

1. `rag.security.enabled=true`。
2. family/version credential resolver、immutable principal 和 typed effective policy 已可用。
3. 至少存在可用的数据库 ADMIN / break-glass 管理路径。
4. 当前部署模式满足 API Key 规划定义的吊销一致性和 quota readiness；多副本不能使用
   被标记为仅单实例有效的 local backend。
5. core standalone 与 starter consumer 两种拓扑都已注册相同的认证、授权和 quota 链。

任一条件不满足时，应用应 fail fast，或保持 `/v1/**` 未注册；不能把接口降级为 static key、
匿名访问或无全局配额的“临时可用”模式。

### 12.3 Credential transport 与 Principal

`/v1/**` 的固定契约：

- 默认只接受 `Authorization: Bearer <credential>`。
- 可用显式 compatibility flag 接受 `X-API-Key`，但不接受 query `apiKey`。
- Bearer 与 X-API-Key 同时存在且值不同时返回 401；相同值只记录无 secret 的弃用指标。
- 只接受 database-backed family principal；legacy static key、anonymous/null caller 和
  请求体 `user` / `safety_identifier` 都不能形成或扩大身份。
- downstream 只读取 request-scoped immutable `ApiKeyPrincipal`，不读取 raw header 或可变
  JPA entity。

兼容层至少依赖 principal 提供：

```text
keyId
credentialFamilyId
role
owner / tenant / project audit attributes
effective actions
effective deployment IDs
effective Collection scope
effective limits
policyVersion
```

`role` 是管理能力上限，不是数据面 bypass；ADMIN 调用 `/v1` 仍需显式 action、deployment
和 Collection 权限。

### 12.4 `/v1` 授权合成

固定求值顺序：

```text
principal valid
  -> semantic action
  -> requested deployment visible and executable
  -> deployment/request/key/document Collection scope
  -> family quota and concurrency
  -> RAG execution
```

Endpoint 对应 action：

| Endpoint | Action |
|---|---|
| `GET /v1/models` | `models.read` |
| `GET /v1/models/{id}` | `models.read` |
| `POST /v1/chat/completions` | `chat.completions.invoke` |

要求：

- `/v1/models` 只列出 policy 允许、Collection scope 有交集且当前可执行的 deployment。
- retrieve 和 chat 对同一 deployment 使用同一 authorization service；列表过滤不能替代
  chat 的逐请求授权。
- requested deployment 不存在或对 caller 不可见时统一返回 404 `model_not_found`。
- Collection 合成遵循第 11 节；空结果必须显式 deny，不能退化为全库。
- 请求中的 `model`、`rag.collection_ids`、`document_ids`、`user` 或任意自定义 Header 都
  只能收窄服务端 policy，不能扩权。

建议复用 API Key 工程提供的 `ApiKeyAuthorizationService`，由兼容层把 deployment 和
request scope 转为 `AuthorizedRagScope`；Controller、Models API 和 retrieval 代码不得各自
重写授权公式。

### 12.5 Quota 与流式生命周期

兼容层只消费 API Key 工程提供的 stable family quota：

- bucket key 是 `credentialFamilyId`，不是 raw secret 或可轮换 keyId。
- rotation 和 overlap version 不能重置 RPM 或并发。
- delegated family 的 ancestor 扣减语义由 API Key 工程保证。
- stream 从订阅开始持有 concurrency lease，并在 complete、error、cancel、timeout 和
  client disconnect 的所有终态释放。
- shared backend 不可用时返回 503；达到配额时返回 429 + `Retry-After`。
- usage 不可信或缺失时，不把估算 token 当作硬预算扣减。

### 12.6 Filter、错误与运行拓扑

两种运行拓扑必须使用同一共享安全配置。逻辑顺序为：

```text
RequestTraceFilter
  -> optional pre-auth IP limiter
  -> ApiKeyAuthenticationFilter
  -> ApiKeyQuotaFilter
  -> ApiKeyAuthorizationInterceptor
  -> Controller
```

`/v1/*` pattern 只在兼容 feature flag 启用时加入。Filter 在 MVC advice 之前失败时，使用
path-aware error writer 输出第 13 节的 OpenAI envelope；`/api/**` 仍保持现有错误格式。

错误分类：

| 情况 | HTTP | 兼容层行为 |
|---|---:|---|
| 缺失、未知、过期、吊销 credential | 401 | `invalid_api_key`，不区分内部原因 |
| action / deployment / Collection 拒绝 | 403 或隐藏式 404 | `permission_denied` 或 `model_not_found` |
| family quota / concurrency 超限 | 429 | `rate_limit_exceeded` + `Retry-After` |
| credential、policy 或 shared quota backend 不可用 | 503 | `service_unavailable`，不得 fallback |

trace、auth、quota 和完整 stream SLO 必须分别验证 core standalone 与 starter consumer，
不能只在 starter 中增加 `/v1/*` 注册。

### 12.7 CORS 与 Secret 边界

启用浏览器 CORS 时为 `/v1/**` 允许 `Authorization`、`Content-Type`、`X-API-Key` 和
`X-Trace-Id`，生产使用精确 origin allow-list。CORS 不是认证边界。

兼容层不得把 raw credential 写入 URL、日志、MDC、metrics、审计、错误或 response。
`/v1` 只消费已签发 credential，不提供 create/rotate/revoke endpoint；管理面及其 TLS、
no-store、审计和 WebUI 规则全部由独立 API Key 规划负责。

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

spring-ai-rag-starter/
  GeneralRagAutoConfiguration.java
```

API Key principal、authorization、quota 和共享 Web Security 文件由独立前置工程交付，
本规划只在兼容配置和 Controller 中消费。包名可在实施时依据现有风格微调，但职责边界
不应退回到单一巨型 Controller。

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

### Phase 0B：验证 API Key 前置工程

1. 按[独立 API Key 加固规划](2026-08-14_API_KEY_HARDENING_IMPLEMENTATION_PLAN.md)
   达到 Milestone A（external data-plane ready）。
2. 固化兼容层依赖契约：immutable principal、typed effective policy、Bearer resolver、
   stable family quota、path-neutral failure classification 和双运行拓扑共享装配。
3. 签发一个只具有 `models.read`、`chat.completions.invoke`、指定 deployments /
   Collections 和有限 quota 的测试 family。
4. 验证 legacy static/null caller 不可形成 `/v1` principal，现有 legacy credential 也
   不会因迁移自动获得 `/v1` action。

完成标准：

- 独立规划 Milestone A 的实现、测试、回滚演练和 readiness 门禁全部通过。
- 兼容层可用公开接口消费 principal/policy/quota，不读取 legacy entity 或 raw header。
- 安全前置未完成前不得进入兼容 endpoint 实现和上线阶段。

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

1. 接入独立 API Key 工程的 credential resolver 和 immutable principal，支持 Bearer。
2. 新增 path-aware error writer。
3. filter 覆盖 `/v1/*`。
4. 调整 auth/rate limit 顺序。
5. action scope、deployment allow-list 和 Collection scope 复用统一 authorization service。
6. rate limit 使用 stable familyId 与 policy limits，并覆盖 stream concurrency 生命周期。
7. CORS 覆盖 `/v1/**`。
8. 验证 core standalone 与 starter demo 两种启动方式。
9. 多副本环境验证 revoke 可见性和 shared limiter 的全局语义。
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

- 独立 API Key 加固规划 Milestone A 的验收和 readiness 已通过。
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
- stable family RPM 与 concurrency；rotation 后 quota 不重置，stream cancel/error 正确释放 permit。
- shared limiter 不可用时 fail closed；不可信 X-Forwarded-For 不能绕过 pre-auth limit。
- 401/403/429 都是 OpenAI envelope。
- `/api/**` 仍是旧错误格式。

API Key schema、生命周期、迁移、bootstrap、管理授权和 WebUI secret 回归由独立加固规划
第 26 节覆盖；本规划只保留 `/v1` 消费契约的集成测试，避免两处测试清单漂移。

### 17.2 Contract / MVC 测试

- `POST /v1/chat/completions` 非流式完整 JSON schema。
- `POST /v1/chat/completions` SSE 原始内容。
- `GET /v1/models` list envelope。
- `GET /v1/models/{id}`。
- 404 model error。
- `Content-Type`、cache headers、trace header。
- feature flag disabled。
- global OpenAPI 文档不把 RFC 7807 schema错误套到 `/v1/**`。

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

Phase 0B 必须先通过独立 API Key 规划定义的 PostgreSQL、migration 和多实例门禁；
本规划的 SDK E2E 仍需在两个应用实例上验证 revoke 可见性和 shared quota。

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
- `rag.openai.compat.auth`，tag 仅含 result/reason，不含 keyId。
- `rag.openai.compat.policy_denied`，tag：action/reason。
- `rag.openai.compat.quota`，tag：result/kind/backend，不含 familyId。

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

Chat 请求的运营审计默认只保存元数据；FULL content retention 必须显式配置并使用独立
retention。不要在普通日志、安全 lifecycle audit 或 metrics 中记录完整 Authorization
header、原始 API key、完整 Prompt/answer 或敏感 metadata。

## 19. 上线与回滚

### 19.1 上线顺序

1. 先完成独立 API Key 加固规划的实现、迁移、回滚演练和 readiness 验收，兼容 endpoint
   始终保持 disabled。
2. 使用有限 action、deployment、Collection 和 quota 的 external test family 验证
   第 12 节依赖契约。
3. 测试环境只启用一个 `rag-default` deployment。
4. 使用 mock upstream 跑协议、错误和 Python/Node SDK E2E。
5. 使用真实 provider 跑非流式、流式和 fallback smoke。
6. 使用多个 restricted family 做 action/deployment/ACL 渗透测试。
7. 在双实例环境验证 revoke 可见性、shared RPM/concurrency 和 stream lease 清理。
8. 小流量启用，观察错误率、policy denial、429、首 token 延迟、fallback、连接取消和
   token 用量。
9. 再开放更多 deployments。

### 19.2 回滚

首选回滚方式：

```yaml
rag:
  openai-compatibility:
    enabled: false
```

要求：

- 关闭 flag 后 `/v1/**` 立即停止暴露；已完成的 API Key 加固能力不随兼容层回滚。
- 现有 `/api/v1/rag/**` 不依赖兼容 Controller。
- deployment 配置删除不影响 backend model registry。
- 可先 revoke 受影响 external family，再全局关闭兼容 flag。
- API Key schema、credential migration 和管理面自身的回滚只按独立规划第 22、28 节执行，
  不能由本兼容层 runbook 临时改写。

## 20. 风险清单

| 风险 | 严重度 | 缓解 |
|---|---|---|
| API Key 前置工程未完成就开放 `/v1` | 高 | feature flag 门禁 + Phase 0B readiness 验证 |
| 把 partial SSE 误称完整兼容 | 高 | 原始字节测试 + 官方 SDK E2E |
| caller history 与 server memory 重复 | 高 | 默认 stateless；server mode 严格校验 |
| `/v1` 绕过 auth/rate limit | 高 | 两种 runtime 拓扑均做 filter 集成测试 |
| credential DB/policy 故障被当作 401 或回退 static key | 高 | 503 + alert；`/v1` 禁止 legacy fallback |
| shared limiter 故障被错误 fail open | 高 | 运行期返回 503；双实例故障测试 |
| stream 异常路径泄漏 concurrency lease | 高 | complete/error/cancel/timeout/disconnect 全终态测试 |
| external Prompt/answer 被默认长期留存 | 高 | 默认 metadata-only；FULL 显式 opt-in + 独立 TTL/访问控制 |
| deployment/request 绕过 Collection ACL | 高 | 统一 scope service；fail closed 矩阵测试 |
| 动态模型使用错误 Prompt adapter | 高 | candidate 携带 capabilities，执行时选择 adapter |
| stream 中途 fallback 拼接不同模型输出 | 高 | 只允许首 chunk 前 fallback |
| usage 不可用 | 中 | 省略 usage；不估算伪造 |
| unsupported 参数被静默忽略 | 中 | strict validation 和明确 error param |
| 兼容 DTO 跟随 OpenAI 演进 | 中 | DTO 独立 package；官方协议回归；兼容级别声明 |
| 新抽象破坏 legacy API | 高 | legacy contract tests 先行，旧 Controller 不改契约 |
| core/starter 的 `/v1` Bean 或 Filter 不一致 | 高 | 两种启动拓扑测试，明确 shared configuration |
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

- [ ] 独立 API Key 加固规划 Milestone A 的验收和 readiness 全部通过。
- [ ] `/v1/*` 必须经过 auth 和 rate limit。
- [ ] production 中 compatibility enabled 强制 security enabled。
- [ ] Bearer auth 使用 database-backed immutable principal；static、query、anonymous caller
      不可调用 `/v1`。
- [ ] `/v1/models` 与 chat 使用相同 deployment authorization。
- [ ] restricted key 无法绕过 action、deployment 或 Collection scope。
- [ ] quota 使用 stable familyId；rotation 不重置，shared backend 故障 fail closed。
- [ ] stream 在 complete/error/cancel/timeout/disconnect 后释放 concurrency lease。
- [ ] 401/403/404/429/503 在 Filter 和 MVC 路径均使用 OpenAI error envelope。
- [ ] core standalone 与 starter consumer 的 `/v1` 安全链和错误行为一致。
- [ ] URL、error、日志、MDC 和 metrics 不泄漏 raw credential。
- [ ] feature flag 默认关闭。

### 回归

- [ ] `/api/v1/rag/chat/**` 请求和响应兼容。
- [ ] legacy SSE 消费方不受影响。
- [ ] `mvn test` 全过。
- [ ] 既有 `scripts/e2e-test.sh` 全过。
- [ ] 新兼容 E2E 全过。
- [ ] 真实 LLM smoke 全过。

## 22. 工作量与建议拆分

以下工作量只计算 OpenAI 兼容层；API Key 加固由独立规划单独估算。建议按 5 个可独立
评审的 PR 实施：

| PR | 内容 | 估计 |
|---|---|---|
| 1 | 内部 command/result、memory 分离、legacy adapter | 3–4 人日 |
| 2 | deployment registry、candidate capabilities、scope/ACL | 2–3 人日 |
| 3 | presence-aware DTO、非流式 endpoint、models、errors、安全能力接线 | 3–4 人日 |
| 4 | 标准 SSE、fallback、cancellation、usage、concurrency lease | 3–4 人日 |
| 5 | SDK/双实例 E2E、文档、部署与全量回归 | 3–4 人日 |

兼容层合计约 14–19 人日。独立 API Key 加固规划估计 20–32 人日，因此从当前基线到可对外
服务的组合工作量约 34–51 人日；两项应分别评审和交付，不能用兼容层估算掩盖安全工程。

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
9. 接受独立 API Key 加固规划作为必须先完成的安全前置工程。
10. `/v1` 只接受其 database-backed principal，不接受 legacy static key 或 query secret。
11. Models 与 Chat 使用 `models.read` / `chat.completions.invoke`、deployment allow-list
    和 explicit Collection scope 的统一授权结果。
12. `/v1` 使用 stable family RPM + concurrency；多副本必须使用 shared backend，token
    budget 仅在 usage 可靠时启用。

批准后应严格按 Phase 0 开始，不直接从 Controller/SSE 拼装代码切入。
