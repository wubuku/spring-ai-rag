# OpenAI 兼容服务就绪度与代码库上下文

> 📖 [English](openai-compatibility-readiness.md) · 📖 [中文](openai-compatibility-readiness-zh-CN.md)

> **用途**：记录 OpenAI Chat Completions 服务端兼容层的当前实现、受控预览边界和
> 公网/多实例生产仍需完成的安全工作。
> **代码基线**：当前交付基线，包含 Chat turn 可靠性、V48 受管 principal 加固与
> V55 有界 staged credential rotation。
> **最近复核**：2026-08-27
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

兼容层本身不是 Agent/subagent 编排器。它提供稳定的“RAG-as-a-model”边界；编排由
调用方或后续独立模块承担。

---

## 2. 与该能力相关的模块边界

| 模块 | 当前职责 | 对兼容层的约束 |
|------|----------|----------------|
| `spring-ai-rag-api` | DTO、SPI | OpenAI DTO 位于独立 `openai` package，不污染现有 `ChatRequest` 契约 |
| `spring-ai-rag-core` | RAG 实现和可运行应用 | 承载共享执行层、兼容 Controller、model alias registry 和错误映射 |
| `spring-ai-rag-starter` | 自动配置 | standalone/starter 拓扑都注册 `/v1` 所需鉴权、限流和观测组件 |
| `spring-ai-rag-documents` | 文档处理 | 不应依赖 OpenAI 协议 |
| `spring-ai-rag-webui` | React 管理台 | root unlock 按 stable principal 管理 policy CAS、quota、staged/immediate credential 轮换、吊销和 shown-once secret |

项目存在两种运行拓扑：

1. 直接运行 `spring-ai-rag-core` 中的应用。
2. 由其他 Spring Boot 应用引入 `spring-ai-rag-starter`。

安全能力不能只在其中一种拓扑生效。当前 `/api/*` 与 `/v1/*` 共用认证和限流 Filter，
并由 focused test 锁定 core standalone 与 starter consumer 的 Bean 装配边界。

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
- 原生 Chat SSE 继续提供 `tool_start`、`tool_result`、`sources`、`done` 等 RAG 事件；
  它与 `/v1` 标准流是两个独立契约。

详细架构见 [architecture-zh-CN.md](architecture-zh-CN.md)，现有 HTTP 契约见
[rest-api-zh-CN.md](rest-api-zh-CN.md) 和 [SSE-PROTOCOL.md](SSE-PROTOCOL.md)。

---

## 4. 两种“OpenAI 兼容”不能混淆

| 方向 | 状态 | 含义 |
|------|------|------|
| `spring-ai-rag -> OpenAI-compatible provider` | 已有 | 本项目作为客户端调用 OpenAI、DeepSeek、SiliconFlow 等上游 |
| `OpenAI client / Agent -> spring-ai-rag` | 受控预览，默认关闭 | 本项目作为服务端提供 `/v1/chat/completions` 和 Models API |

当前 `adapter/` 下的 `OpenAiCompatibleAdapter` 处理的是**上游模型消息能力差异**，
不是服务端 Chat Completions 协议适配器。新增能力不能直接复用其名称或假设其已经实现
服务端兼容。

---

## 5. 当前 API Key 能力

当前实现已经具备并验收了独立服务 MVP-0：

- raw secret 格式为 `rag_sk_` + 随机值，公开标识为 `rag_k_...`。
- 认证时使用 SHA-256 hash 查询。
- 支持创建、列举、吊销、即时轮换、有界 staged 轮换、过期时间和 `last_used_at`。
- 角色为 `ADMIN` / `NORMAL`。
- V24 增加 `allowed_collection_ids`，数据访问路径可按 Collection ACL 收敛。
- `RAG_ROOT_API_KEY` 提供 environment-root principal；root 模式自动保护 `/api/**`。
- root 可通过 `/webui/unlock` 解锁控制台，创建、列出、轮换和吊销业务 Key。
- root 创建的业务 Key 固定为 `FULL_RAG`，expiry 必填、必须在未来且不设固定最长有效期，
  外部调用方不需要 WebUI。
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

## 6. 外部生产调用的剩余边界

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

## 7. 当前实现与公开启用边界

1. 兼容能力默认关闭，通过 feature flag 显式启用。
2. `model` 表示 RAG alias，不允许客户端用任意底层模型名绕过检索和授权策略。
3. 默认无状态；只有显式策略允许时才启用服务端 memory。
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
10. core standalone 和 starter consumer 两种拓扑都必须有认证、授权、限流和观测测试。
11. V54/V55 混合 fleet 必须冻结 API Key 管理写和 staged prepare。只有全部实例运行 V55
    后才能启用 staged rotation；应用回滚到 V54 前，必须清零 enabled retiring
    credential 和 `PENDING` rotation operation，同时保留 V55 schema。

---

## 8. 维护与加固阅读顺序

1. 本文：确认受控预览的当前实现事实和安全边界。
2. [REST API](rest-api-zh-CN.md) 与 [配置参考](configuration-zh-CN.md)：当前可调用契约。
3. [项目上下文](project-context-zh-CN.md) 与 [测试指南](testing-guide-zh-CN.md)：
   稳定能力边界和当前验证入口。
4. [历史规划归档](drafts/archive/README-zh-CN.md)：仅在追溯设计来源和旧验证证据时阅读；
   与当前代码冲突时以本文和 live reference 为准。

---

## 9. 维护规则

- 当前实现变化时更新本文；目标设计变化时更新规划稿，避免把“现状”和“计划”混为一体。
- API 或配置落地后，同步更新 `rest-api*`、`configuration*` 和测试文档。
- 不在本文记录真实 API Key、Token、数据库密码或其他 secret。
- 中英文版本必须同步维护。
