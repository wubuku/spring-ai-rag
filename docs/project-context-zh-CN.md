# 项目上下文

> [English](project-context.md) | [中文](project-context-zh-CN.md)

> **用途**：为开发者和 Agent 提供稳定、代码支撑的项目认知。
> **最近复核**：2026-08-14。
> 本文记录当前事实；目标设计和未实施能力必须明确标注为规划。

文档总入口：[index-zh-CN.md](index-zh-CN.md)。命令参考：[developer-reference-zh-CN.md](developer-reference-zh-CN.md)。

## 1. 项目定位

spring-ai-rag 是基于 Spring AI 的通用 RAG 框架，目标是：

- 模型无关：Chat 与 Embedding provider 解耦。
- 领域解耦：通过 `DomainRagExtension` 扩展 Prompt 和检索策略。
- 组件化：API、核心实现、Starter、文档处理和 WebUI 分离。
- 可观测：覆盖检索日志、评估、反馈、A/B、告警和指标。
- 可交付：提供 Docker、Helm、WebUI bundle 和发布门禁。

## 2. 模块边界

| 模块 | 职责 |
|------|------|
| `spring-ai-rag-api` | DTO、SPI；不承载业务实现 |
| `spring-ai-rag-core` | RAG 实现、Controller、Advisor、服务和可运行应用 |
| `spring-ai-rag-starter` | Spring Boot 自动配置和嵌入式集成 |
| `spring-ai-rag-documents` | 文档分块、清洗和处理 |
| `spring-ai-rag-webui` | React 管理台 |
| `demos` | basic、component、domain、multi-model 示例 |

系统支持两种运行拓扑：

1. 直接运行 Core 应用。
2. 由其他 Spring Boot 应用引入 Starter。

安全、限流和自动配置变更必须验证两种拓扑。

## 3. RAG 执行链

默认 Advisor 顺序：

```text
QueryRewriteAdvisor (+10)
  -> HybridSearchAdvisor (+20)
  -> RerankAdvisor (+30)
  -> MessageChatMemoryAdvisor
```

关键规则：

- 对话与检索支持 Collection / Document 范围。
- 请求明确给出空范围时必须 fail closed，不能退化为全库检索。
- `RerankAdvisor` 将检索上下文注入用户消息，兼容限制多个 system message 的 provider。
- Spring AI memory 与业务审计历史分别存储。

详细设计见 [architecture-zh-CN.md](architecture-zh-CN.md)。

## 4. 检索与质量

- Embedding 默认使用 SiliconFlow `BAAI/bge-m3`。
- 向量维度固定为 `1024`，必须与 PostgreSQL `VECTOR(1024)` 一致。
- 支持 vector + full-text 混合检索。
- 生产 profile 推荐启用 query rewrite 和本地 heuristic rerank。
- Goldenset 使用 Precision@K、MRR 和 nDCG。

小型在线 goldenset 的 baseline 与 quality 组合都达到满分；重排增益由确定性 MRR 测试证明，不能把该样本解释为统计显著提升。

详见 [quality-defaults-zh-CN.md](quality-defaults-zh-CN.md)。

## 5. 多模型

- 旧 provider Bean 路径仍用于兼容默认模型。
- `ConfiguredChatModelFactory` 按 `provider/modelId` 创建并缓存真实模型实例。
- `ChatModelRouter` 负责显式模型选择、默认模型和 fallback。
- Chat、Settings 和模型对比支持具体模型引用。
- 外部 `models.json` 可以覆盖 YAML 模型配置。

详见 [multi-model-external-config-zh-CN.md](multi-model-external-config-zh-CN.md)。

## 6. 数据与 API

### 数据库

- PostgreSQL + pgvector。
- Flyway 当前为 V1–V24。
- `vector` 必需，`pg_trgm` 推荐，`pg_jieba` 可选。
- Chat memory、业务历史、检索日志、评估、反馈、A/B、告警、API Key 和文件数据分别持久化。

### HTTP

主要路径为 `/api/v1/rag/**`：

| 区域 | 能力 |
|------|------|
| `/chat`, `/chat/stream` | RAG 对话 |
| `/documents` | 文档管理与 embedding |
| `/search` | 混合检索 |
| `/collections` | 知识库 |
| `/evaluation` | 评估与反馈 |
| `/api-keys` | API Key 管理 |
| `/files` | PDF / 文件导入 |

契约见 [rest-api-zh-CN.md](rest-api-zh-CN.md) 和 [SSE-PROTOCOL.md](SSE-PROTOCOL.md)。

## 7. 安全与 Collection ACL

当前内部 API Key 支持：

- hash 查询。
- `ADMIN` / `NORMAL` 角色。
- 过期、吊销、轮换和 `last_used_at`。
- `allowedCollectionIds`。
- Chat、Search、Collection、Document、PDF-to-RAG 数据面 ACL。

但它还不是适合外部模型服务的完整凭据系统：

- schema 仍保留明文列。
- NORMAL key 委派边界需要收紧。
- rotation 缺少稳定 principal / family。
- 缺少事务化最后一个 ADMIN 保护。
- bootstrap secret 通过日志分发。
- 多实例吊销、共享限流和写放大尚未解决。

这些边界见 [openai-compatibility-readiness-zh-CN.md](openai-compatibility-readiness-zh-CN.md)，
实施顺序和验收标准见
[API Key 加固实施规划](drafts/2026-08-14_API_KEY_HARDENING_IMPLEMENTATION_PLAN.md)。

## 8. OpenAI 兼容方向

不要混淆两个方向：

```text
已有：spring-ai-rag -> OpenAI-compatible provider
未实现：OpenAI client / Agent -> spring-ai-rag
```

项目当前没有标准 `POST /v1/chat/completions` 或 Models API。现有 SSE 只有部分 OpenAI-like delta，不能宣称 Chat Completions 兼容。

规划中的兼容层将完整 RAG deployment 暴露为 `model`，默认关闭、默认无状态，并要求先完成外部 API Key、Bearer 鉴权和多实例限流加固。

目标方案见 [OpenAI Chat Completions 兼容规划](drafts/2026-07-21_OPENAI_CHAT_COMPLETIONS_COMPATIBILITY_PLAN.md)。

## 9. 1.0 稳定基线

已落地：

- 生产质量默认值和 goldenset。
- Collection ↔ API Key ACL。
- 运行时多模型实例和 UI 选模。
- Maven、Demo、OpenAPI、Helm、Docker 统一为 `1.0.0`。
- WebUI 生产 bundle 内嵌到 Core。
- 中国境内友好的 Docker 构建路径。
- 一键发布验证。

2026-07-21 完整门禁：

```text
19 passed, 0 failed, 0 skipped
Maven 3213 tests
Vitest 153
Playwright 37
HTTP E2E 66/66
Real LLM 10/10
```

验证记录见 [P1 / 1.0 就绪实施进度](drafts/2026-07-21_P1_10_READINESS_PROGRESS.md)。

## 10. 明确边界

- 不可变 `1.0.0` source/image Tag 尚未创建，留给正式发布流水线。
- OpenAI 服务端兼容仍是规划，不是当前能力。
- OpenClaw 的 `TOOLS.md`、`MEMORY.md`、`memory/`、`HEARTBEAT.md` 等是本地状态，不属于项目文档体系。
- 项目级 Skills 位于 `.agents/skills/`，工作流可以引用本文，但不复制项目事实。

## 11. 真相顺序

发生冲突时按以下顺序判断：

1. 当前代码和迁移。
2. `docs/` 中的 live reference / guide。
3. `AGENTS.md` 和 `CLAUDE.md` 的入口规则。
4. `docs/drafts/` 与 `*-plan.md`。
5. 本地 Agent 状态文件。
