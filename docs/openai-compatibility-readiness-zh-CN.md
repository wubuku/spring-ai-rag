# OpenAI 兼容服务就绪度与代码库上下文

> 📖 [English](openai-compatibility-readiness.md) · 📖 [中文](openai-compatibility-readiness-zh-CN.md)

> **用途**：记录 OpenAI Chat Completions 服务端兼容层的当前实现、受控预览边界和
> 公网/多实例生产仍需完成的安全工作。
> **代码基线**：`main@f1cdcba1`，包含 Chat turn 可靠性、V48 受管 principal 加固与
> V55 有界 staged credential rotation。
> **最近复核**：2026-08-28
> **状态**：`/v1/models` 与 `/v1/chat/completions` 已实现但默认关闭；本文不宣称公网生产就绪。

文档总入口：[index-zh-CN.md](index-zh-CN.md)。当前可调用契约和配置以
[REST API](rest-api-zh-CN.md) 与 [配置参考](configuration-zh-CN.md) 为准；旧目标设计
只在需要审计历史时从 [规划归档](drafts/archive/README-zh-CN.md) 查阅。

---

## 1. 结论

将项目暴露为 OpenAI Chat Completions 兼容服务有明确价值：

> 把带检索策略、知识范围、领域 Prompt 和模型路由的完整 RAG deployment
> 暴露为标准 `model`，使 OpenAI SDK、Agent 框架、IDE 和网关可以按模型服务接入。

协议兼容和公网生产就绪是两件事。当前系统已经完成独立 RAG 服务 MVP、V48 stable
managed principal 与默认关闭的 `/v1` 兼容适配层。stable owner、versioned credential、
有界 staged rotation、即时跨实例吊销、PostgreSQL 共享请求 quota 和 quota store
fail-closed 已经落地。兼容层仍是受控预览而非全面公网生产声明，因为 legacy 兼容、
身份 federation、operator recovery、部署控制与 token/cost 治理仍是独立问题。

当前准确定位是：**Core 独立服务拓扑已经具备可供可信网络基本接入的 Chat Completions
兼容子集，但不是完整 OpenAI API 替代品，也尚未形成公网生产就绪声明。**兼容层本身
不是 Agent/subagent 编排器。它提供稳定的“RAG-as-a-model”边界；编排由调用方或后续
独立模块承担。

---

## 2. 与该能力相关的模块边界

| 模块 | 当前职责 | 对兼容层的约束 |
|------|----------|----------------|
| `spring-ai-rag-api` | DTO、SPI | OpenAI DTO 位于独立 `openai` package，不污染现有 `ChatRequest` 契约 |
| `spring-ai-rag-core` | RAG 实现和可运行应用 | 承载共享执行层、兼容 Controller、model alias registry 和错误映射 |
| `spring-ai-rag-starter` | 自动配置 | 明确导入 `/v1` 共用的鉴权、限流和观测配置；兼容 Controller 与执行 Bean 仍依赖宿主扫描 `com.springairag` 或等价显式导入 |
| `spring-ai-rag-documents` | 文档处理 | 不应依赖 OpenAI 协议 |
| `spring-ai-rag-webui` | React 管理台 | root unlock 按 stable principal 管理 policy CAS、quota、staged/immediate credential 轮换、吊销和 shown-once secret |

项目存在两种运行拓扑：

1. 直接运行 `spring-ai-rag-core` 中的应用。
2. 由其他 Spring Boot 应用引入 `spring-ai-rag-starter`。

Core 独立服务通过 `SpringAiRagApplication` 扫描 `com.springairag`，会完整注册兼容入口。
Starter 的 `GeneralRagAutoConfiguration` 当前只显式导入共享 Web 安全配置，没有自动导入
`OpenAiCompatibilityController`、mapper、alias registry 及其完整依赖；示例应用通过
`scanBasePackages = "com.springairag"` 补足。因而不能把“只引入 Starter 依赖即可获得
`/v1`”视为已验收事实。安全 Filter 的共享装配已有测试，但缺少不依赖额外组件扫描的
Starter consumer `/v1` HTTP 集成测试。

---

## 3. 当前 RAG 对话执行事实

- 原生主路径仍是 `/api/v1/rag/**`；`/v1/**` 只有显式启用 feature flag 时注册。
- OpenAI 请求支持 text-only `messages[]`，映射到 transport-neutral `ChatCommand`；
  原生 `ChatRequest` 的单 message 契约保持不变。
- `model` 必须是公开 alias。alias 绑定 Chat mode、memory 和内部模型候选链，不绑定
  固定 Collection。
- Collection scope 来自 body `rag.scope` 或重复 `X-RAG-Collection-Key`，再委托统一
  `CollectionRetrievalScopeResolver` 与 API Key ACL。
- 非流式返回 `chat.completion`；流式返回完整 `chat.completion.chunk` 序列和精确
  `data: [DONE]`。认证、限流和 Controller 错误使用 OpenAI error envelope。
- `/v1` 没有客户端可指定的稳定会话 ID。不同请求默认生成不同内部 session；多轮客户端
  应像标准 Chat Completions 一样重传完整 `messages`。当前不应把 alias 的 `SERVER`
  memory 解释为对外稳定的跨请求会话合同。
- 可选 `Idempotency-Key` 复用持久化 Chat turn。精确重放保持 completion ID 和结果稳定；
  携带该 Header 的 `stream=true` 会先完成并持久化 turn，再发送快照式 SSE，不提供逐 token
  首包延迟语义。
- 原生 Chat SSE 继续提供 `tool_start`、`tool_result`、`sources`、`done` 等 RAG 事件；
  它与 `/v1` 标准流是两个独立契约。

详细架构见 [architecture-zh-CN.md](architecture-zh-CN.md)，现有 HTTP 契约见
[rest-api-zh-CN.md](rest-api-zh-CN.md) 和 [SSE-PROTOCOL.md](SSE-PROTOCOL.md)。

---

## 4. 2026-08-28 实现状态评估

### 4.1 成熟度结论

| 评估面 | 当前状态 | 结论 |
|--------|----------|------|
| Core 独立服务基本接入 | 已实现并有聚焦测试 | 可用于可信网络中的基本 OpenAI Chat Completions 客户端接入 |
| 基础 SDK wire shape | 基本兼容 | 标准 JSON 字段可被 OpenAI Python SDK 解析；仓库尚无提交内 SDK HTTP 回归门禁 |
| RAG 语义 | 已实现 | model alias、请求级 Collection/document scope、JSONB filter 和 API Key ACL 复用统一执行链 |
| 安全数据面 | 已实现基础加固 | Bearer/Header API Key、能力、ACL、共享 quota、吊销和 rotation 已进入 `/v1` Filter 链 |
| Starter consumer | 部分集成 | 安全配置会自动导入；完整 `/v1` 入口仍依赖宿主额外组件扫描，缺少真实 consumer HTTP 验收 |
| 完整 Chat Completions 协议 | 未实现 | 仅 text-only、`n=1`；不支持采样、tools、structured output、多模态等 |
| 公网生产服务 | 未宣称就绪 | 仍需关闭 legacy 边界，并完成身份、预算、TLS、网络、恢复和运营控制 |

因此，“支持 OpenAI Chat Completions”只能用于描述**受控兼容子集**。对外能力声明应写成
“OpenAI Chat Completions-compatible preview”，不能写成“完整 OpenAI API compatible”。

### 4.2 当前协议矩阵

| 类别 | 已支持 | 明确不支持或受限 |
|------|--------|------------------|
| 端点 | `GET /v1/models`、`GET /v1/models/{id}`、`POST /v1/chat/completions` | Responses、Embeddings、Files、Batches、旧 Completions 等其他 OpenAI API |
| 消息 | `system`、`developer`、`user`、`assistant`；字符串或纯 text parts；最多 100 条、总计 1,000,000 字符 | image/audio/file parts、tool/function message、`name`、空白 content；请求必须至少有一条 user message |
| 生成参数 | `model`、`stream`、`n=1` | `temperature`、`top_p`、token 上限、`n>1`、logprobs、tools/functions、`response_format`、`stream_options`；未知字段也 fail closed |
| RAG 扩展 | `rag.scope`、`rag.document_ids`、`rag.filters`、受 alias 策略约束的 `rag.mode` / `rag.memory`；重复 `X-RAG-Collection-Key` | `PLAIN` 模式不接受检索 scope/filter；请求覆盖默认关闭；结构化 sources 不进入 OpenAI 响应 |
| 非流式响应 | 标准 `chat.completion`、单 choice、assistant text、`finish_reason`、可选 usage | 不返回结构化 citation/source/tool-call 对象 |
| 流式响应 | role chunk、content chunks、finish chunk、`data: [DONE]` | 不支持 `stream_options.include_usage`；运行中失败只能在已建立的 SSE 中发送 error envelope |
| 幂等 | 可选 `Idempotency-Key`、稳定 replay、`X-RAG-Turn-Id` 与 replay Header | keyed streaming 是完成后快照式 SSE，不是实时 token stream |
| 会话 | 每个请求可携带完整 messages | 没有客户端可控 session ID；不同请求没有稳定的服务端 conversation 合同 |

基本的 OpenAI SDK 调用通常可接入：把 SDK `base_url` 指向服务的 `/v1`，API key 使用
服务签发的 RAG credential，`model` 使用 `/v1/models` 返回的 alias，并且只发送上表支持的
字段。会自动发送 tools、JSON schema、采样参数或多模态 content 的 Agent/IDE 需要先关闭
这些能力，否则会收到明确的 `unsupported_parameter`，不能假定静默降级。

### 4.3 代码导航

| 关注点 | 代码入口 |
|--------|----------|
| OpenAI DTO | `spring-ai-rag-api/.../api/openai/` |
| `/v1` Controller 与 JSON/SSE 映射 | `spring-ai-rag-core/.../controller/OpenAiCompatibilityController.java` |
| 请求校验、messages 与 `ChatCommand` 映射 | `spring-ai-rag-core/.../openai/OpenAiChatRequestMapper.java` |
| model alias 与 mode/memory/candidate policy | `spring-ai-rag-core/.../openai/OpenAiModelAliasRegistry.java` |
| Collection Header/body 合并与 ACL | `spring-ai-rag-core/.../openai/OpenAiRequestRetrievalScopeAdapter.java` |
| OpenAI 错误信封 | `OpenAiCompatibilityExceptionHandler.java`、`OpenAiProtocolException.java` |
| 认证、能力和配额 | `ApiKeyAuthFilter.java`、`ApiCapabilityFilter.java`、`RateLimitFilter.java` |
| 共享 Chat 执行 | `ChatExecutionService.java`、`ChatTurnOperationService.java` |
| 聚焦测试和一键门禁 | `OpenAi*Test.java`、`OpenAiCompatibilityControllerWebTest.java`、`scripts/verify-openai-compatibility.sh` |

这里的 `...` 都位于对应模块的 `src/main/java/com/springairag/` 或
`src/test/java/com/springairag/` 下；表格用于快速定位，不替代代码搜索。

### 4.4 当前验证证据与证据缺口

2026-08-28 在 `main@f1cdcba1` 执行：

```bash
./scripts/verify-openai-compatibility.sh
```

结果为 4 个步骤全部通过，其中 Maven 聚焦集合共 55 个测试，覆盖 alias、scope mapper、
完整 text-only messages、非流式信封、协议错误、SSE 顺序、`[DONE]`、共享 Chat fallback 和
Web 安全装配；随后 `test-compile`、脚本语法与 `git diff --check` 通过。一次性 wire-shape
探测也确认标准非流式响应可由本机 OpenAI Python SDK 解析。

这些证据**尚不能**证明以下事项：

1. 从隔离 Spring Boot 服务出发，由官方 Python/JavaScript SDK 完成 JSON 与 SSE 的提交内
   端到端回归；当前专项脚本主要是 MockMvc/单元级合同测试。
2. 只引入 Starter、且不扫描 `com.springairag` 的普通 consumer 会自动拥有 `/v1` 端点。
3. feature flag 关闭/开启、Bearer 鉴权、只读能力、Collection ACL、共享 quota 和真实 HTTP
   流在同一专项矩阵中的完整组合。
4. 常见第三方 Agent、IDE 和网关不会自动发送当前不支持的 OpenAI 参数。

### 4.5 后续改进优先级

1. **P0：建立 SDK 级专项 E2E。** 启动隔离 PostgreSQL、Mock 上游模型和真实 Spring Boot
   服务，使用官方 Python 与 JavaScript SDK 验证 models、JSON、SSE、错误、Bearer、只读
   principal、Collection ACL、feature flag 和 `[DONE]`；测试只用 DOM/HTTP/JSON/数据库
   只读断言，不依赖截图。
2. **P0：明确 Starter 产品边界。** 二选一并固化测试：自动配置完整 `/v1` Bean 图，或明确
   声明兼容服务只由 Core standalone 提供。不要继续依赖示例应用的宽包扫描掩盖边界。
3. **P1：补齐客户端接入指南。** 提供官方 SDK 的最小 text-only 示例、`base_url=/v1`、
   model alias、完整 messages、Collection Header/body、错误处理和 keyed stream 语义。
4. **P1：决定来源返回合同。** 当前 OpenAI 响应不含结构化 RAG sources；需要来源追溯的
   客户应继续使用原生 API。只有确定跨 SDK 的扩展字段策略后，才考虑兼容层引用扩展。
5. **P2：按真实 client 需求扩协议。** 优先基于客户端矩阵决定 sampling、structured output、
   tools 或多模态，不为追求字段数量而绕过 alias policy 或服务端工具授权。
6. **独立生产工作流：** legacy 关闭、OAuth/OIDC、token/cost hard limit、TLS、网络隔离、
   Secret 管理、备份恢复与告警继续按生产 readiness 单独规划。

---

## 5. 两种“OpenAI 兼容”不能混淆

| 方向 | 状态 | 含义 |
|------|------|------|
| `spring-ai-rag -> OpenAI-compatible provider` | 已有 | 本项目作为客户端调用 OpenAI、DeepSeek、SiliconFlow 等上游 |
| `OpenAI client / Agent -> spring-ai-rag` | 受控预览，默认关闭 | 本项目作为服务端提供 `/v1/chat/completions` 和 Models API |

当前 `adapter/` 下的 `OpenAiCompatibleAdapter` 处理的是**上游模型消息能力差异**，
不是服务端 Chat Completions 协议适配器。新增能力不能直接复用其名称或假设其已经实现
服务端兼容。

---

## 6. 当前 API Key 能力

当前实现已经具备并验收了独立服务 MVP-0：

- raw secret 格式为 `rag_sk_` + 随机值，公开标识为 `rag_k_...`。
- 认证时使用 SHA-256 hash 查询。
- 支持创建、列举、吊销、即时轮换、有界 staged 轮换、过期时间和 `last_used_at`。
- 角色为 `ADMIN` / `NORMAL`。
- V24 增加 `allowed_collection_ids`，数据访问路径可按 Collection ACL 收敛。
- `RAG_ROOT_API_KEY` 提供 environment-root principal；root 模式自动保护 `/api/**` 与
  `/v1/**`。
- root 可通过 `/webui/unlock` 解锁控制台，创建、列出、轮换和吊销业务 Key。
- root 创建的业务 Key 可选择只读 `RAG_READ` 或完整 `RAG_READ + RAG_WRITE`；省略时兼容为
  完整读写。expiry 必填、必须在未来且不设固定最长有效期，外部调用方不需要 WebUI。
- root 模式支持 `Authorization: Bearer` 和 `X-API-Key` Header，拒绝 query credential；
  未配置 root 时保留 legacy static/query 兼容行为。
- WebUI credential 只保存在页面内存，旧 localStorage 凭据会在升级时清理。
- V48 将 stable principal policy 与 versioned credential hash 分离；rotation 保留
  `db:{principalId}` owner 与 policy。
- V55 允许每个 stable principal 同时有一个 current 与至多一个受 deadline 约束的
  retiring credential。prepare 幂等但不重放一次性 replacement secret；complete、
  cancel、deadline expiry、policy expiry clamp 与 family revoke 会收敛，且不会扩大
  ACL 或 quota。
- 每次认证都执行权威 credential/principal 联查；吊销在其他实例的下一次认证立即生效。
- retiring credential 的 deadline 也直接由认证查询强制执行，不依赖 cleanup 是否及时。
- PostgreSQL UTC 固定分钟 backend 按 stable principal 共享请求 quota，且故障 fail
  closed，不使用 raw key 或 IP fallback。
- legacy 明文列被约束为只能是 `NULL`；legacy ADMIN 吊销有事务化 last-ADMIN guard。

相关实现：

- [RagApiKey](../spring-ai-rag-core/src/main/java/com/springairag/core/entity/RagApiKey.java)
- [RagApiPrincipal](../spring-ai-rag-core/src/main/java/com/springairag/core/entity/RagApiPrincipal.java)
- [ApiKeyManagementService](../spring-ai-rag-core/src/main/java/com/springairag/core/service/ApiKeyManagementService.java)
- [ApiKeyController](../spring-ai-rag-core/src/main/java/com/springairag/core/controller/ApiKeyController.java)
- [ApiKeyAuthFilter](../spring-ai-rag-core/src/main/java/com/springairag/core/filter/ApiKeyAuthFilter.java)
- [ApiKeyCollectionAccess](../spring-ai-rag-core/src/main/java/com/springairag/core/security/ApiKeyCollectionAccess.java)

---

## 7. 外部生产调用的剩余边界

| 缺口 | 当前代码事实 | 直接影响 |
|------|--------------|----------|
| 创建和委派 | root MVP 已关闭 NORMAL 自助管理；legacy 模式仍保留历史创建/委派语义 | 完整 hardening 仍需收紧 legacy 兼容和 policy 委派边界 |
| Bootstrap 与 recovery | root 模式禁用空表 ADMIN/raw secret bootstrap；legacy 模式无可用 ADMIN 时只记录低基数错误 | operator 仍需明确的 environment root provision/recovery 流程 |
| URL 和凭据格式 | `/v1/*` 已接入 Bearer/Header 认证；root 模式拒绝 query credential，并使用 OpenAI 错误信封 | 公网启用仍应关闭 legacy query/static 兼容并明确只允许受管 principal |
| 身份 federation | managed principal 是服务签发 secret，不是 OAuth/OIDC 身份 | 公网多租户可能需要本 credential family 之外的 issuer、audience、tenant 与 revocation 契约 |
| 成本治理 | 共享 quota 按 UTC 固定分钟统计请求数 | token、provider cost、日预算和 billing ledger 仍是独立能力 |
| 运营 | 代码层共享 quota、staged rotation 与即时吊销已经实现 | TLS、网络隔离、数据库容量、告警、备份恢复、Secret 存储集成和轮换 runbook 仍属部署职责 |

这些是 service readiness 问题，不是协议细节。V48 的历史设计理由在本轮交付完成前仍可
从活跃规划追溯；当前事实以 live API 和配置参考为准。

---

## 8. 当前实现与公开启用边界

1. 兼容能力默认关闭，通过 feature flag 显式启用。
2. `model` 表示 RAG alias，不允许客户端用任意底层模型名绕过检索和授权策略。
3. 默认无状态；`/v1` 不提供客户端可控 session ID，跨请求多轮调用必须重传完整 messages。
4. 新旧 HTTP/SSE 输出分别映射到共享的结构化内部执行结果，不能让一个 Controller
   调另一个 Controller。
5. model alias 不包含固定 Collection；有效范围由请求 scope 与 API Key ACL 解析，
   未授权范围 fail closed。
6. root 模式下 `/v1` 只接受 Bearer / `X-API-Key`，拒绝 query-string secret。
7. PostgreSQL 限流按认证后的 stable principal 跨实例共享，rotation 不重置；local backend
   继续作为显式单实例兼容选项。
8. credential 与 PostgreSQL quota store 故障均 fail closed；公网启用仍应移除 legacy
   static/query 兼容。
9. 保持 `/api/v1/rag/**` 现有契约，兼容能力关闭后旧 API 仍独立工作。
10. core standalone 已有聚焦合同测试；Starter 目前只锁定共享安全配置装配，完整 consumer
    `/v1` HTTP Bean 图仍是待补 P0 验证项。
11. V54/V55 混合 fleet 必须冻结 API Key 管理写和 staged prepare。只有全部实例运行 V55
    后才能启用 staged rotation；应用回滚到 V54 前，必须清零 enabled retiring
    credential 和 `PENDING` rotation operation，同时保留 V55 schema。

---

## 9. 维护与加固阅读顺序

1. 本文：确认受控预览的当前实现事实和安全边界。
2. [REST API](rest-api-zh-CN.md) 与 [配置参考](configuration-zh-CN.md)：当前可调用契约。
3. [项目上下文](project-context-zh-CN.md) 与 [测试指南](testing-guide-zh-CN.md)：
   稳定能力边界和当前验证入口。
4. [历史规划归档](drafts/archive/README-zh-CN.md)：仅在追溯设计来源和旧验证证据时阅读；
   与当前代码冲突时以本文和 live reference 为准。

---

## 10. 维护规则

- 当前实现变化时更新本文；目标设计变化时更新规划稿，避免把“现状”和“计划”混为一体。
- API 或配置落地后，同步更新 `rest-api*`、`configuration*` 和测试文档。
- 不在本文记录真实 API Key、Token、数据库密码或其他 secret。
- 中英文版本必须同步维护。
