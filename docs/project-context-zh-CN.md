# 项目上下文

> [English](project-context.md) | [中文](project-context-zh-CN.md)

> **用途**：为开发者和 Agent 提供稳定、代码支撑的项目认知。
> **最近复核**：2026-08-15。
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
- 非空 Collection / Document 范围解析后没有匹配文档时必须 fail closed，不能退化为全库检索。
- `RerankAdvisor` 将检索上下文注入用户消息，兼容限制多个 system message 的 provider。
- Spring AI memory 与业务审计历史分别存储。

### Collection 当前语义

Collection 不是仅用于展示的分类字段，而是已经进入写入、检索和权限链路的知识库边界：

- `rag_collection.id` 是内部 `Long` 主键/外键身份；调用方提供的 `collectionKey` 是推荐
  外部身份。它只能包含 1-128 个可见 ASCII 字符，区分大小写，全局唯一、不可变，软删除后
  仍保持占用。
- `rag_documents.collection_id` 建立 Collection 与 Document 的一对多关系；单个文档最多属于一个
  Collection，也可以不属于任何 Collection。
- Chat 与 Search 推荐多个 `collectionKeys`，deprecated 的 `collectionIds` 继续兼容。
  `CollectionIdentityResolver` 与 `ApiKeyCollectionAccess` 校验 ID/key 集合一致性并转换为
  已授权内部 ID，再由 `CollectionDocumentResolver` 展开并与显式 `documentIds` 取交集。
- 不受限调用方同时省略两种身份字段表示不限定 Collection；受限 API Key 省略范围时继承
  允许列表。显式空范围返回 `400`；受限调用方的未知或未授权 key 返回 `403`。已授权非空
  范围没有文档时返回空结果。
- Collection CRUD、恢复、克隆、文档关联、导入导出、文档写入、上传、PDF-to-RAG、
  WebUI 和 API Key 管理均在外部边界使用稳定 key；数据库关系和检索仍使用数字 ID。
- WebUI Chat 与 Search 当前单选 Collection key，后端协议支持多个 key；Collections、
  Documents、Files 和 API Keys 页面也在外部边界使用 key。

当前边界：

- Collection 的 `embeddingModel`、`dimensions` 当前是管理/导入导出元数据，不会为每个
  Collection 切换 EmbeddingModel；实际写入和查询仍使用全局 Embedding 配置。
- 向量和全文检索会排除 disabled 文档，并要求活动 Embedding Profile 存在新鲜的
  completed 状态。
- 删除 Collection 会软删除集合并解除文档关联，不会删除文档或 embeddings；解除关联后的文档
  仍可能出现在未限定 Collection 的全库检索中；被删除 Collection 的 key 不可复用。
- 当前实现先把 Collection 展开为 document IDs，再生成 `document_id IN (...)` 查询；
  超大 Collection 需要评估参数规模，并优先演进为数据库直接按 `collection_id` JOIN/过滤。

详细设计见 [architecture-zh-CN.md](architecture-zh-CN.md)。

## 4. 检索与质量

- Embedding 默认使用 SiliconFlow `BAAI/bge-m3`。
- Embedding 使用不可变的 `rag_embedding_profiles` 身份和固定长度的
  `rag_embeddings.embedding_1024 VECTOR(1024)` 列。文档只有在活动 Profile 的状态为
  `COMPLETED` 且 content hash 与当前文档一致时才算新鲜可服务。
- 支持 vector + full-text 混合检索。
- 生产 profile 推荐启用 query rewrite 和本地 heuristic rerank。
- Goldenset 使用 Precision@K、MRR 和 nDCG。

小型在线 goldenset 的 baseline 与 quality 组合都达到满分；重排增益由确定性 MRR 测试证明，不能把该样本解释为统计显著提升。

详见 [quality-defaults-zh-CN.md](quality-defaults-zh-CN.md)。

### JSON 结构化记录

JSON record API 将调用者负责的业务数据保存到
`RagDocument.jsonbPayload` / `rag_documents.jsonb_payload`，将调用者提供的自然语言描述
保存到现有 `content` 字段，并以 `retrievalText` 对外暴露。只有 `retrievalText` 会参与
hash、分块、全文索引、embedding 和普通 RAG Prompt 上下文；服务不会自动生成或校验
JSON 与描述是否一致。

JSON record 使用 `(collectionId, documentType=json-record, externalId)` 作为幂等身份，
不参与普通文档的全局 content-hash 去重，因此不同 payload 可以拥有相同描述。仅更新
payload 会创建可审计版本，但不会使新鲜 embedding 失效；设计上没有 `payloadHash`。
专用搜索 API 在检索排序完成后再批量补充当前 JSONB payload，不把 payload 复制到 embedding
metadata，也不自动放入普通聊天 Prompt。

普通非空短文档至少保留一个 chunk；`minChunkSize` 是尽力而为的分块质量目标，不是
静默丢弃文档的准入过滤器。JSON record 固定使用一个 record-level chunk。

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
- Flyway 当前为 V1–V29。
- V27/V28 负责新增、回填、校验、唯一约束及不可变 Collection 业务 key；V29 增加 JSONB
  结构化记录。
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
| `/json-records` | JSONB 结构化记录 upsert、检索与详情 |

契约见 [rest-api-zh-CN.md](rest-api-zh-CN.md) 和 [SSE-PROTOCOL.md](SSE-PROTOCOL.md)。

## 7. 安全与 Collection ACL

当前支持两种兼容运行模式。

独立服务 MVP 模式由 `RAG_ROOT_API_KEY` 显式启用：

- environment root 自动保护 `/api/**`，不依赖 legacy 认证开关。
- root 可通过 `/webui/unlock` 解锁管理台；凭据只保存在页面内存，刷新后重新输入。
- 只有 root 能创建、列出、轮换和吊销业务 Key。
- root 创建的 Key固定为 `FULL_RAG` 数据面能力，可读写 RAG 数据、可限制 Collection，
  但不能管理其他 Key。
- 业务 Key expiry 必填、必须在未来且不设固定最长有效期；raw secret 仅在创建或轮换
  响应中显示一次。
- root 模式只接受 Bearer / `X-API-Key` Header，拒绝 query credential，并禁用旧 ADMIN
  bootstrap/raw 日志分发。

未配置 root 时保留 legacy ADMIN/NORMAL/static-key 行为。

数据库 API Key 共同支持：

- hash 查询。
- `ADMIN` / `NORMAL` 角色。
- 过期、吊销、轮换和 `last_used_at`。
- `allowedCollectionIds`。
- Chat、Search、Collection、Document、PDF-to-RAG 数据面 ACL。

该 MVP 只承诺单实例、TLS、受控管理网络，还不是完整的多租户外部凭据系统：

- schema 仍保留明文列。
- NORMAL key 委派边界需要收紧。
- rotation 缺少稳定 principal / family。
- 缺少事务化最后一个 ADMIN 保护。
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
