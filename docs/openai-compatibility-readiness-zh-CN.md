# OpenAI 兼容服务就绪度与代码库上下文

> 📖 [English](openai-compatibility-readiness.md) · 📖 [中文](openai-compatibility-readiness-zh-CN.md)

> **用途**：记录实现 OpenAI Chat Completions 服务端兼容层和外部调用方 API Key
> 之前必须掌握的当前代码事实。
> **代码基线**：`main` / commit `3b61b26`；API Key MVP 实施提交：`ccc0e42`
> **最近复核**：2026-08-15
> **状态**：当前项目尚未暴露 `/v1/chat/completions`；本文是现状参考，不是已实现能力声明。

文档总入口：[index-zh-CN.md](index-zh-CN.md)。目标架构、迁移、测试和回滚方案见
[OpenAI Chat Completions 兼容实施规划](drafts/2026-07-21_OPENAI_CHAT_COMPLETIONS_COMPATIBILITY_PLAN.md)。
独立的凭据、授权、轮换、吊销和多实例配额工程见
[API Key 加固实施规划](drafts/2026-08-14_API_KEY_HARDENING_IMPLEMENTATION_PLAN.md)。

---

## 1. 结论

将项目暴露为 OpenAI Chat Completions 兼容服务有明确价值：

> 把带检索策略、知识范围、领域 Prompt 和模型路由的完整 RAG deployment
> 暴露为标准 `model`，使 OpenAI SDK、Agent 框架、IDE 和网关可以按模型服务接入。

但协议兼容和服务可用性是两件事。当前系统已经完成“root API Key 解锁 WebUI +
业务 API Key 分发”的独立 RAG 服务 MVP-0，并通过后端、前端和 standalone HTTP 验收；
它仍不足以支撑公网、多实例和 production-ready 的外部调用。**完整 API Key 生命周期、
授权和多实例配额加固仍是 `/v1` 上线前置条件，不能在兼容 Controller 上线后再补。**

兼容层本身不是 Agent/subagent 编排器。它提供稳定的“RAG-as-a-model”边界；编排由
调用方或后续独立模块承担。

---

## 2. 与该能力相关的模块边界

| 模块 | 当前职责 | 对兼容层的约束 |
|------|----------|----------------|
| `spring-ai-rag-api` | DTO、SPI | 新协议 DTO 可放在独立 package，不能污染现有 `ChatRequest` 契约 |
| `spring-ai-rag-core` | RAG 实现和可运行应用 | 承载内部执行层、兼容 Controller、deployment registry 和错误映射 |
| `spring-ai-rag-starter` | 自动配置 | 必须同步注册 `/v1` 所需鉴权、限流和观测组件 |
| `spring-ai-rag-documents` | 文档处理 | 不应依赖 OpenAI 协议 |
| `spring-ai-rag-webui` | React 管理台 | MVP 已提供 root-key unlock；不要求改用兼容接口，公网管理面仍需单独加固 |

项目存在两种运行拓扑：

1. 直接运行 `spring-ai-rag-core` 中的应用。
2. 由其他 Spring Boot 应用引入 `spring-ai-rag-starter`。

安全能力不能只在其中一种拓扑生效。当前鉴权和限流的
`FilterRegistrationBean` 位于 starter，这一点必须在实施前通过 characterization test 锁定。

---

## 3. 当前 RAG 对话执行事实

- 对外主路径是 `/api/v1/rag/**`；Chat 入口为 `/chat`、`/ask` 和 `/chat/stream`。
- `ChatRequest` 以单个 `message` 为核心，不等价于 Chat Completions 的完整 `messages[]`。
- `RagChatService` 目前按 `.system(...)` + `.user(...)` 构造请求。
- 默认 Advisor 链包含 Query Rewrite、Hybrid Search、Rerank 和
  `MessageChatMemoryAdvisor`。
- `AdvisorUtils` 可以从消息列表中提取最后一个非空 user message，这为后续完整消息映射
  提供了可复用基础。
- 非流式路径已有候选模型 fallback；流式路径没有完全对称的 fallback 和元数据语义。
- 非流式调用会写 `rag_chat_history`；流式路径的业务审计行为不对称。
- 当前 SSE 只输出部分 OpenAI-like `choices[].delta.content`，并使用自定义结束事件。
  它缺少完整标准 chunk 字段、OpenAI error envelope 和精确的 `data: [DONE]`，因此不能
  宣称 Chat Completions 兼容。

详细架构见 [architecture-zh-CN.md](architecture-zh-CN.md)，现有 HTTP 契约见
[rest-api-zh-CN.md](rest-api-zh-CN.md) 和 [SSE-PROTOCOL.md](SSE-PROTOCOL.md)。

---

## 4. 两种“OpenAI 兼容”不能混淆

| 方向 | 状态 | 含义 |
|------|------|------|
| `spring-ai-rag -> OpenAI-compatible provider` | 已有 | 本项目作为客户端调用 OpenAI、DeepSeek、SiliconFlow 等上游 |
| `OpenAI client / Agent -> spring-ai-rag` | 未实现 | 本项目作为服务端提供 `/v1/chat/completions` 和 Models API |

当前 `adapter/` 下的 `OpenAiCompatibleAdapter` 处理的是**上游模型消息能力差异**，
不是服务端 Chat Completions 协议适配器。新增能力不能直接复用其名称或假设其已经实现
服务端兼容。

---

## 5. 当前 API Key 能力

当前实现已经具备并验收了独立服务 MVP-0：

- raw secret 格式为 `rag_sk_` + 随机值，公开标识为 `rag_k_...`。
- 认证时使用 SHA-256 hash 查询。
- 支持创建、列举、吊销、轮换、过期时间和 `last_used_at`。
- 角色为 `ADMIN` / `NORMAL`。
- V24 增加 `allowed_collection_ids`，数据访问路径可按 Collection ACL 收敛。
- `RAG_ROOT_API_KEY` 提供 environment-root principal；root 模式自动保护 `/api/**`。
- root 可通过 `/webui/unlock` 解锁控制台，创建、列出、轮换和吊销业务 Key。
- root 创建的业务 Key 固定为 `FULL_RAG`，expiry 必填、必须在未来且不设固定最长有效期，
  外部调用方不需要 WebUI。
- root 模式支持 `Authorization: Bearer` 和 `X-API-Key` Header，拒绝 query credential；
  未配置 root 时保留 legacy static/query 兼容行为。
- WebUI credential 只保存在页面内存，旧 localStorage 凭据会在升级时清理。

相关实现：

- [RagApiKey](../spring-ai-rag-core/src/main/java/com/springairag/core/entity/RagApiKey.java)
- [ApiKeyManagementService](../spring-ai-rag-core/src/main/java/com/springairag/core/service/ApiKeyManagementService.java)
- [ApiKeyController](../spring-ai-rag-core/src/main/java/com/springairag/core/controller/ApiKeyController.java)
- [ApiKeyAuthFilter](../spring-ai-rag-core/src/main/java/com/springairag/core/filter/ApiKeyAuthFilter.java)
- [ApiKeyCollectionAccess](../spring-ai-rag-core/src/main/java/com/springairag/core/security/ApiKeyCollectionAccess.java)

---

## 6. 外部生产调用的关键缺口

| 缺口 | 当前代码事实 | 直接影响 |
|------|--------------|----------|
| 明文 secret schema | V23 和 `RagApiKey` 仍保留 `api_key` 字段及索引；service 当前虽未写入，schema 仍允许持久化 | 无法证明 secret 只出现一次且永不落库 |
| 创建和委派 | root MVP 已关闭 NORMAL 自助管理；legacy 模式仍保留历史创建/委派语义 | 完整 hardening 仍需收紧 legacy 兼容和 policy 委派边界 |
| 轮换身份 | rotate 禁用旧 key 后创建新的独立 key | role、owner、policy 和 quota 无稳定承载对象 |
| ADMIN 保护 | 没有事务化的最后一个 ADMIN 保护 | 并发操作可能使系统失去管理凭据 |
| Bootstrap | root MVP 模式已禁用空表 ADMIN/raw secret bootstrap；未配置 root 的 legacy 模式仍保留历史行为 | 完整 hardening 仍需统一 bootstrap/recovery 语义 |
| 吊销一致性 | 认证有 30 秒进程内正向缓存 | 多实例吊销不能立即、全局生效 |
| 使用时间写入 | 每次认证同步更新 `last_used_at` | 高频调用产生数据库写放大 |
| 限流 | MVP 已在认证后优先使用稳定 key ID，但仍是本进程内计数；多实例 shared quota 未实现 | 多副本配额被放大，尚不能提供全局 quota 语义 |
| raw key 进入限流 | root/已认证路径使用稳定 principal ID；legacy/未认证 fallback 仍可能使用 raw header | 完整 hardening 仍需彻底移除 raw secret 作为 limiter identifier |
| URL 和凭据格式 | root MVP 已为 `/api/**` 提供 Bearer/Header 认证并拒绝 query；`/v1/*` 仍未实现 | 新增 `/v1/*` 时仍需独立注册兼容鉴权和 Bearer 契约 |
| 故障语义 | 数据库 key 验证与 static fallback 并存 | 凭据存储故障时必须避免错误降级为绕过路径 |

这些问题不是协议细节，而是外部服务是否“基本可用”的前提。稳定 family/principal、
可轮换 version、显式 policy、共享 quota、迁移和 fail-closed 故障语义由
[API Key 加固实施规划](drafts/2026-08-14_API_KEY_HARDENING_IMPLEMENTATION_PLAN.md)
完整定义；兼容规划第 12 节只定义 `/v1` 如何消费这些能力。

---

## 7. 实施时必须保持的边界

1. 兼容能力默认关闭，通过 feature flag 显式启用。
2. `model` 表示 RAG deployment，不允许客户端用底层模型名绕过检索和授权策略。
3. 默认无状态；只有显式策略允许时才启用服务端 memory。
4. 新旧 HTTP/SSE 输出分别映射到共享的结构化内部执行结果，不能让一个 Controller
   调另一个 Controller。
5. Collection scope 必须由 deployment、API Key policy 和请求 override 取交集，并
   fail closed。
6. `/v1` 只接受数据库支持的 Bearer credential；不接受 query-string secret。
7. rate limit 使用稳定 principal/family ID，轮换不能重置配额。
8. 凭据、policy 或共享 limiter 不可用时返回服务不可用，不能回退成静态凭据放行；
   管理生命周期审计按独立 API Key 规划 fail closed。
9. 保持 `/api/v1/rag/**` 现有契约，兼容能力关闭后旧 API 仍独立工作。
10. core standalone 和 starter consumer 两种拓扑都必须有认证、授权、限流和观测测试。

---

## 8. 实施阅读顺序

1. 本文：确认当前实现事实和安全边界。
2. [API Key 加固实施规划](drafts/2026-08-14_API_KEY_HARDENING_IMPLEMENTATION_PLAN.md)：
   凭据模型、生命周期、授权、quota、迁移、测试和回滚。
3. [兼容实施规划 §1–§12](drafts/2026-07-21_OPENAI_CHAT_COMPLETIONS_COMPATIBILITY_PLAN.md)：
   产品决策、协议、执行架构和外部凭据集成契约。
4. 兼容实施规划 §16–§19：分阶段实施、测试、观测和上线。
5. 实施前重新读取当前代码和 Flyway 目录；规划文档可能落后于之后的代码变更。

---

## 9. 维护规则

- 当前实现变化时更新本文；目标设计变化时更新规划稿，避免把“现状”和“计划”混为一体。
- API 或配置落地后，同步更新 `rest-api*`、`configuration*` 和测试文档。
- 不在本文记录真实 API Key、Token、数据库密码或其他 secret。
- 中英文版本必须同步维护。
