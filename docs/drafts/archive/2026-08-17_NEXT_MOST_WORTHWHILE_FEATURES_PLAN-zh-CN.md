# RAG 服务下一批高价值功能规划

> [English](2026-08-17_NEXT_MOST_WORTHWHILE_FEATURES_PLAN.md) | [中文](2026-08-17_NEXT_MOST_WORTHWHILE_FEATURES_PLAN-zh-CN.md)

> **状态**：规划稿；尚未授权实施生产代码。  
> **规划日期**：2026-08-17。  
> **代码基线**：`b8a478f`（`fix(chat): preserve exact terms with Spring AI query expansion`）。  
> **目标**：挑选真正能形成产品闭环、能复用现有能力、可低风险实施的下一批功能。  
> **规划门槛**：本文完成后必须连续三轮系统性检查，三轮期间不得修改本文；之后才允许进入实施阶段。

本文是目标设计，不是当前能力声明。当前事实以代码和 `docs/` 长青文档为准；实施时必须重新核对代码、迁移版本和依赖版本。

## 1. 结论先行

当前项目已经不是“缺少一个基本 RAG Demo”，而是已有较完整的能力底座：

- PostgreSQL + pgvector；
- 向量与全文混合检索、可选 Rerank、Embedding Profile freshness；
- Collection 范围解析、API Key Collection allow-list 和文档范围交集；
- Spring AI `RetrievalAugmentationAdvisor`、`ToolCallAdvisor`、`MultiQueryExpander`、
  `CompressionQueryTransformer` 和默认 DocumentJoiner；
- `KNOWLEDGE`、`AGENT`、`PLAIN` 三种 Chat 模式；
- Chat memory 隔离、session lease、来源追踪、SSE、fallback；
- 普通外部文档幂等同步；
- JSONB structured record；
- Evaluation、goldenset、反馈、A/B、指标和发布门禁。

因此下一批最值得做的不是继续增加自定义正则、另起一套 Agent 框架或优先支持更多文件格式，而是把已有能力连接成下面四个闭环：

| 优先级 | 功能 | 主要价值 | 推荐批次 | 估计体量 |
|---|---|---|---|---|
| P0 | 动态 Collection 范围的 OpenAI Chat Completions 适配层 | 让 OpenAI SDK、Agent、IDE 和网关可以按请求选择检索范围，同时不把 Collection 固定进模型 | A | M–L |
| P0 | 持久化嵌入/重索引任务 | 让大批量导入、重嵌入、失败恢复和跨重启运行可靠 | B | M |
| P1 | JSON structured record 检索闭环 | 让 `jsonbPayload + retrievalText` 支持受限结构过滤和 Agent 专用工具 | C | M |
| P1 | 真实检索回归门禁 | 把已有 MRR/nDCG/goldenset 变成可重复、可比较、可阻断回归的质量证据 | C | S–M |

**推荐执行顺序**：

1. 先做 Batch A 的请求级 Collection scope 与 OpenAI 适配层；
2. 再做 Batch B 的持久化摄入任务；
3. Batch C 中先把回归门禁作为每个功能的质量门槛，再并行完成 JSON 检索闭环；
4. 用真实数据和质量指标决定是否投入更重的连接器、覆盖召回或 GraphRAG。

## 2. 明确的取舍

### 2.1 Collection 不能固定进 model 或 deployment

这是本规划最重要的修正。

`model` 只能选择模型实例、Chat 模式和检索策略配置，不能隐含“固定搜索某个 Collection”。固定 Collection 对真实调用方不实用：同一个模型往往要服务多个租户、多个项目、多个请求范围，调用方会在每轮请求中切换知识库。

推荐的语义是：

```text
model
  -> 选择 backend model / fallback / Chat pipeline / 生成与检索默认值
request
  -> 动态提供 Collection scope
server
  -> request scope + 当前 API Key ACL + documentIds 交集
  -> 现有 CollectionRetrievalScopeResolver
```

没有请求级 scope 时，沿用现有 `CALLER_VISIBLE` 语义：它是按当前调用方动态计算的可见范围，不是某个固定 Collection。对需要强制显式选库的部署，可配置 `requireExplicitScope=true`，但仍然不能配置一个固定的默认 Collection。

### 2.2 不重新发明 Spring AI 的轮子

实施时必须优先复用：

- `ChatClient`；
- `RetrievalAugmentationAdvisor`；
- `ToolCallAdvisor`；
- `MultiQueryExpander`；
- `CompressionQueryTransformer`；
- Spring AI 默认 DocumentJoiner；
- 当前 `ProjectDocumentRetriever`；
- 当前 `KnowledgeSearchTool` 和 server-owned `AuthorizedRetrievalContext`。

项目自有实现只负责真正有差异化价值的部分：Collection/ACL 下推、混合检索、Embedding Profile freshness、JSONB 过滤、来源追踪、任务持久化和质量门禁。

禁止：

- 通过 Java 正则猜测“用户是不是想检索”来代替 Tool Calling；
- 在 OpenAI Controller 中复制一套检索 SQL；
- 让 Prompt、`user`、普通 metadata 或模型可见 Tool 参数承担授权；
- 自动从 JSON 生成自然语言描述，或验证调用者提供的 JSON 与描述是否一致；
- 为数据库任务队列先引入 Kafka、Redis 或工作流引擎。

### 2.3 本批明确延后

以下项目不进入本批优先级：

- API Key secret schema 加固、key family、轮换治理、共享配额、用量计费；
- XML、DOCX、PPTX、XLSX 和外部连接器；
- `EACH_COLLECTION` 覆盖召回；
- 每个 Collection 独立 EmbeddingModel；
- GraphRAG、实体链接、多模态检索；
- MCP 产品化、`/v1/responses`、通用 Agent 编排；
- 以“固定默认 Collection”解决 OpenAI 兼容协议的范围问题。

延后 API Key 加固不等于放开权限。OpenAI 适配层只复用当前认证和 Collection ACL，首版定位为内部或受控网络使用，不宣称公网商业生产就绪。若未来要公网开放，再单独实施 API Key 加固、共享限流和配额。

与现有 [OpenAI 兼容就绪度](../../openai-compatibility-readiness-zh-CN.md) 文档的关系：该长青文档继续代表当前事实，并保留“公网/多实例生产前必须完成 API Key 加固”的门槛；本规划只是额外定义一个默认关闭的受控网络 MVP。若未来批准实施，必须在 readiness 文档中明确区分“受控预览可用”和“公网生产就绪”，不能因为 `/v1` 端点存在就宣称后者。

## 3. 当前事实和缺口

### 3.1 模块与稳定能力

| 范围 | 当前事实 | 规划约束 |
|---|---|---|
| `spring-ai-rag-api` | DTO、枚举和 SPI | 新协议 DTO、任务 DTO 和 JSON filter DTO 放在独立 package，不污染旧 `ChatRequest` |
| `spring-ai-rag-core` | Controller、Service、检索、Chat、迁移和可运行应用 | 承载新适配层、任务 worker、JSON filter 和评估 runner |
| `spring-ai-rag-starter` | 自动配置、Filter 和 starter consumer 拓扑 | 新能力必须同时验证 core standalone 与 starter consumer |
| `spring-ai-rag-documents` | 清洗、分块和 PDF 相关处理 | 不依赖 OpenAI 协议或数据库 job 实现 |
| `spring-ai-rag-webui` | React 管理台，已有 Search、Chat、Documents、Evaluation | 只为确实需要人工操作的任务状态/评估结果增加 UI |
| 数据库 | PostgreSQL + pgvector，Flyway V1–V32 | 所有新 schema 使用 V33+ forward-only migration |

实现入口和稳定上下文：

- [项目上下文](../../project-context-zh-CN.md)
- [架构设计](../../architecture-zh-CN.md)
- [REST API](../../rest-api-zh-CN.md)
- [开发者参考](../../developer-reference-zh-CN.md)

### 3.2 Collection 当前已是真正的检索边界

现有链路已经是：

```text
collectionKeys / collectionIds
  -> CollectionRetrievalScopeResolver
  -> ApiKeyCollectionAccess
  -> RetrievalScope
  -> RetrievalScopeSql
  -> vector / full-text / rerank / Chat / JSON record
```

现有 scope：

- `CALLER_VISIBLE`：当前调用方可见的全部可检索文档；
- `ANY_COLLECTION`：所有已归属 Collection 的可检索文档；
- `SELECTED_COLLECTIONS`：显式选择 Collection 的并集；
- `documentIds`：与 Collection 范围取交集；
- 受限 API Key：任何 mode 都不能扩大 allow-list。

当前 `SELECTED_COLLECTIONS` 是全局 top-k 的并集检索，不保证每个 Collection 都贡献结果；这是有意边界，不是本批必须补的缺陷。详见 [Collection 检索范围语义](../../rest-api-zh-CN.md#collection-检索范围语义) 和 [EACH_COLLECTION TODO](../../TODO-zh-CN.md)。

### 3.3 Chat 已使用 Spring AI 内置编排

当前生产 Chat 执行内核位于 `ChatExecutionService`：

- `KNOWLEDGE` 使用 `RetrievalAugmentationAdvisor`；
- `AGENT` 使用 `ToolCallAdvisor`/`BudgetedToolCallAdvisor` 和 `KnowledgeSearchTool`；
- `PLAIN` 不检索；
- 查询扩展使用 Spring AI `MultiQueryExpander`；
- 有历史时使用 Spring AI `CompressionQueryTransformer`；
- `ProjectDocumentRetriever` 把当前项目混合检索器接入 Spring AI；
- `AuthorizedRetrievalContext` 将 scope、retrieval options、principal 和预算放在服务端上下文；
- Chat 支持候选模型 fallback、流式 fallback、来源快照和 session lease。

最近的 `b8a478f` 已将“精确词保留”写入 Spring AI Query Expander prompt，并默认保留原查询。后续质量工作应该通过回归数据验证这条链路，而不是再写一套自然语言正则。

### 3.4 五个事实缺口

| 缺口 | 当前事实 | 直接后果 |
|---|---|---|
| 外部协议 | 当前主要是 `/api/v1/rag/**`，没有标准 `/v1/chat/completions` 和 `/v1/models` | 外部 OpenAI SDK、IDE、Agent 和网关不能直接接入 |
| OpenAI 动态范围 | OpenAI 标准协议没有 Collection 字段；旧草稿曾把 Collection 固定进 deployment | 如果不设计扩展，要么只能模糊全可见，要么变成不可用的固定知识库 |
| 摄入任务 | `DocumentEmbedService` 提供同步、batch≤50、SSE 和 freshness，但没有 job 表、lease、worker、死信 | 大库重嵌入会占用 HTTP 请求，重启和失败恢复不可靠 |
| JSON 检索 | JSON record 通过 `jsonbPayload + retrievalText` 保存和混合检索，但 search 没有 payload filter，普通 Chat/Tool 不提供 JSON 专用出口 | 结构化数据可以被命中，但不能安全地按字段筛选或让 Agent 取回结构化结果 |
| 质量门禁 | Evaluation API 主要接收调用方已经生成的 retrieved/relevant IDs；goldenset 脚本能跑真实 search，但不是版本化基线/阈值门禁 | 修改检索策略时容易靠感觉判断质量，回归不能稳定阻断 |

不再单列“新增专业 Rerank 框架”：当前代码已经有 `RerankProvider` SPI、
heuristic provider、HTTP provider 和 fallback。下一步应在回归门禁中比较
heuristic 与实际 cross-encoder/Rerank API，并把可证明的配置结论写入质量文档；
只有现有 HTTP 契约无法覆盖目标 provider 时，才新增最小适配，而不是重做 SPI。

## 4. 总体架构与依赖

```text
动态请求 scope
    │
    ▼
OpenAI Chat Completions 适配层
    │  复用 ChatExecutionService / Spring AI Advisor / Tool Calling
    │
    ├───────────────┐
    ▼               ▼
持久化摄入任务       JSON structured-record 检索
    │               │
    └──────┬────────┘
           ▼
真实检索回归门禁
```

关键依赖：

1. Batch A 先抽出“请求级 scope 合成”作为 transport-neutral 服务，OpenAI Controller 只做协议映射；
2. Batch B 复用现有 `DocumentEmbedService`、`EmbeddingPersistenceService`、content hash、document version 和 freshness；
3. Batch C 的 JSON API/Tool 复用同一 `JsonRecordService`、`HybridRetrieverService` 和 `AuthorizedRetrievalContext`；
4. 所有批次先建立可重复的回归样例，避免在没有证据的情况下调整默认值。

## 5. Batch A：动态 Collection scope 的 OpenAI Chat Completions 适配层

### 5.1 产品目标

让调用方可以使用常见 OpenAI SDK：

```python
client.chat.completions.create(
    model="rag-default",
    messages=[{"role": "user", "content": "查找破皮沙发相关内容"}],
    extra_body={
        "rag": {
            "scope": {
                "mode": "SELECTED_COLLECTIONS",
                "collection_keys": ["furniture-cn", "support-faq"]
            }
        }
    },
)
```

`rag-default` 只表达模型/Chat pipeline，不表达固定知识库。调用方也可以使用重复请求头：

```http
X-RAG-Collection-Key: furniture-cn
X-RAG-Collection-Key: support-faq
```

标准客户端不传扩展时，服务使用动态的 `CALLER_VISIBLE`。如果某部署要求每次调用必须显式选择范围，则返回明确的 `RAG_SCOPE_REQUIRED`，而不是偷偷选一个固定 Collection。

### 5.2 范围协议和权限规则

#### 请求扩展

建议支持以下非标准、明确命名空间的扩展：

```json
{
  "model": "rag-default",
  "messages": [
    {"role": "user", "content": "查找相关内容"}
  ],
  "stream": true,
  "rag": {
    "scope": {
      "mode": "SELECTED_COLLECTIONS",
      "collection_keys": ["support-cn", "support-faq"]
    },
    "document_ids": [153, 154],
    "mode": "KNOWLEDGE",
    "memory": "STATELESS"
  }
}
```

字段边界：

- `model`：只选择配置好的 Chat model/pipeline alias；
- `rag.scope`：只表达本次请求的 Collection 范围；
- `rag.document_ids`：可选的额外交集；
- `rag.mode`、`rag.memory`：只允许配置允许的请求覆盖；
- 不接受模型名、Prompt、metadata 或 Tool 参数作为授权范围。

范围解析规则：

1. body 没有 `rag.scope` 且没有 Collection header：使用 `CALLER_VISIBLE`；
2. body 有 scope：只接受现有 `CALLER_VISIBLE`、`ANY_COLLECTION`、`SELECTED_COLLECTIONS` 语义；
3. 重复 `X-RAG-Collection-Key` 等价于 `SELECTED_COLLECTIONS + collectionKeys`；
4. body 和 header 同时存在时，必须解析为同一组 key，否则返回 400；
5. 最终调用现有 `CollectionRetrievalScopeResolver`，不在兼容 Controller 中自写解析；
6. 受限 API Key 的 allow-list 始终生效，不能被请求扩大；
7. `document_ids` 只能进一步收窄，不得绕过 Collection；
8. 显式空 scope、非法 key、超过上限、body/header 冲突返回 400；
9. 未授权 Collection 返回 403，不能静默删除越权项后继续搜索；
10. 没有命中时保持 `matchNone`/空结果语义，不能退化为全库搜索。

有效范围可以写成：

```text
effectiveScope
  = requestScope(or CALLER_VISIBLE)
  ∩ currentApiKeyCollectionAcl
  ∩ documentIdsIntersection
```

这里没有 `deploymentScope`，因为本规划不把固定 Collection 放进 model/deployment。

### 5.3 Model alias 和执行边界

首版使用 YAML 中独立的 alias 配置，不建立 deployment 数据库；现有外部
`models.json` 继续只负责后端 provider/model 配置，不复用其字段承载公开的 RAG
alias，避免把后端模型身份和外部协议模型身份混在一起：

```yaml
rag:
  openai-compatibility:
    enabled: false
    require-explicit-scope: false
    models:
      rag-default:
        candidates:
          - openrouter/model-a
          - openrouter/model-b
        mode: KNOWLEDGE
        memory: STATELESS
        allow-request-mode-override: false
        allow-request-generation-overrides: false
```

必须明确：

- alias 不保存 Collection key；
- alias 不能绕过 `ChatModelRouter` 的 capability 检查和 fallback；
- 未配置的 alias 返回模型未知错误；alias 已配置但没有可执行的 backend candidate
  或 provider 暂时不可用，属于服务不可用，不伪装成“模型不存在”；
- 响应 `model` 返回请求的 alias，实际 backend model 只进入受控 trace/可选扩展；
- 默认 `memory=STATELESS`，避免 OpenAI 调用方已提交完整 messages 后再被服务端重复注入历史；
- OpenAI 适配层可选择 `KNOWLEDGE` 或受控 `AGENT`，但不在本批实现外部 tool/function calling 透传。

执行链：

```text
OpenAI DTO
  -> message/content 解析
  -> model alias resolver
  -> request scope resolver
  -> ChatCommand / internal message list
  -> ChatExecutionService
      -> Spring AI RetrievalAugmentationAdvisor or ToolCallAdvisor
      -> ProjectDocumentRetriever / KnowledgeSearchTool
  -> OpenAI response/SSE mapper
```

不得调用旧 Controller，也不得复制检索 SQL。若现有 `ChatCommand` 只有单条 message，则应扩展内部 transport-neutral command，使完整 `messages[]` 在到达 ChatClient 前不丢失；旧 `/api/v1/rag/chat/**` DTO 契约不变。

消息映射的默认决策：

- `system` 和 `developer` 都映射为 Spring AI `SystemMessage`，按请求中的原始顺序拼接，并用稳定分隔符保留边界；
- `user` 和 `assistant` 映射为对应的 Spring AI message，顺序不变；
- 只接受字符串 content 或只包含 text 的 content parts；
- `tool`、function call、图片和音频首版返回明确的 `unsupported_message_type`，不得静默丢弃；
- `developer` 不进入普通 user prompt，也不作为 Collection/ACL 控制。

### 5.4 协议范围

MVP：

- `GET /v1/models`；
- `GET /v1/models/{id}`；
- `POST /v1/chat/completions`；
- text-only `system`、`developer`、`user`、`assistant` messages；
- `n=1`；
- 非流式响应；
- `stream=true` 的标准 `data:` SSE 和 `data: [DONE]`；
- provider 能提供时返回真实 usage，否则省略；
- OpenAI error envelope；
- `Authorization: Bearer` 映射到当前已有 API Key principal；
- `/v1/**` 复用现有认证、trace、SLO、CORS 和限流入口，但不实施完整 key hardening。

固定错误语义：

| 情况 | HTTP | OpenAI error type/code |
|---|---:|---|
| JSON、messages、scope、header 冲突或不支持参数 | 400 | `invalid_request_error` / 稳定项目 code |
| alias 不存在或未对当前调用方公开 | 404 | `invalid_request_error` / `model_not_found` |
| 请求超出当前 API Key 的 Collection ACL | 403 | `permission_error` / `collection_not_allowed` |
| 显式 scope 必填但未提供 | 400 | `invalid_request_error` / `RAG_SCOPE_REQUIRED` |
| 当前凭据无效或缺失 | 401 | `authentication_error` |
| alias 存在但没有可执行 candidate，或 provider/credential store 暂时不可用 | 503 | `server_error` / 稳定项目 code |

首版拒绝：

- 图片、音频、多模态；
- `n>1`、logprobs、structured output；
- Responses API、Assistants、Batch API；
- 外部 function/tool call 透传；
- 服务端托管 conversation；
- 用标准 `model` 字段编码 Collection。

### 5.5 实施分解

1. 增加 `RagCompatibilityProperties` 和 alias registry；启动时校验 alias、候选 model、能力和冲突配置；
2. 增加 OpenAI request/response/error DTO 与 `rag.scope` DTO；
3. 增加 `RequestRetrievalScopeAdapter`，统一 body/header 解析并委托 `CollectionRetrievalScopeResolver`；
4. 扩展内部 Chat command 支持完整消息列表、stateless memory 和请求级 pipeline；
5. 增加独立 `/v1` Controller 与 response/SSE mapper；
6. 将 Bearer、CORS、SLO、trace、限流的路径覆盖扩展到 `/v1/**`；
7. 保持旧 `/api/v1/rag/**` 行为不变；
8. 更新 `rest-api*`、`configuration*`、`SSE-PROTOCOL.md` 和 OpenAI readiness 文档；
9. 增加 `scripts/verify-openai-compatibility.sh`，使用 JSON/SSE/HTTP 断言，不使用截图。

### 5.6 验收与回滚

必须覆盖：

- 无 scope 时使用 caller-visible，且不固定到任何 Collection；
- selected scope 只能收窄，不能扩大 API Key ACL；
- body/header 同值通过、冲突 400；
- 未授权 scope 403、未知 alias 404（`model_not_found`）、显式空 scope 400；
- 空交集不执行全库查询；
- `/v1/models` 不泄露 Collection keys；
- 完整 messages 映射、system/developer 语义和 stateless memory；
- 非流式响应字段、真实 usage 省略语义、OpenAI error envelope；
- SSE chunk 顺序、`[DONE]`、首 chunk 前 fallback、首 chunk 后不切换；
- core standalone 与 starter consumer 两种拓扑；
- 原有 Chat/Search/Collection ACL 集成测试回归。

回滚边界：

- `rag.openai-compatibility.enabled=false` 即可关闭新入口；
- 不改 V1–V32；
- 不改变旧 `/api` 契约；
- alias registry 配置删除即可回退到原有模型 API。

## 6. Batch B：持久化嵌入/重索引任务

### 6.1 为什么优先

当前 `DocumentEmbedService` 已有同步单文档、batch≤50、SSE、cache/freshness 和失败记录，但 HTTP 请求本身仍承担 provider 调用。对几十、几百或几万条文档执行重嵌入时，存在：

- 请求超时；
- 服务重启丢失进度；
- provider 暂时失败只能由调用方重新发起；
- 没有统一的排队、租约、重试、失败列表和观察入口；
- 旧向量与新内容的竞争条件需要靠调用方避免。

首版 rollout gate 固定为 `rag.embedding-jobs.enabled=false`。关闭时不启动
worker，创建/重试任务接口返回稳定的 `503 EMBEDDING_JOBS_DISABLED`，不能静默创建
永远不会执行的任务；查询和取消已存在的任务仍可用，便于停 worker 后收尾。完成
PostgreSQL、并发、重启和 provider 失败验收后，再由 `application-prod.yml` 显式改为
`true`。该开关只控制异步任务面，不改变旧同步 embed API，也不改变 JSON upsert 的
默认同步语义。

### 6.2 推荐数据模型

使用 PostgreSQL job 表，不引入外部消息队列。新增 V33+ migration，建议表 `rag_embedding_jobs`：

| 字段 | 语义 |
|---|---|
| `id` | UUID 或数据库生成的外部 job ID |
| `job_type` | 首版固定为 `EMBED_DOCUMENT`；Profile 重嵌入是 fan-out 命令，不单独占一行任务类型 |
| `batch_id` | 同一批 fan-out 任务的可选分组 ID；单文档任务也可生成独立 batch ID |
| `document_id` | 任务目标文档 |
| `embedding_profile_id` | 创建时捕获的活动 Profile；首版 worker 只执行仍与当前活动 Profile 一致的任务 |
| `force` | 即使当前 embedding 新鲜也是否重新生成；普通补偿任务默认 `false`，重索引批次为 `true` |
| `content_hash` | 任务创建时的文本身份 |
| `document_version` | 记录创建任务时的 `RagDocument.version`；提交时与 `content_hash` 一起校验，防止旧任务覆盖新内容 |
| `status` | `QUEUED`、`RUNNING`、`SUCCEEDED`、`FAILED`、`CANCELLED`、`STALE` |
| `attempt_count` / `max_attempts` | 有界重试 |
| `available_at` | backoff 后再次可领取时间 |
| `lease_owner` / `lease_expires_at` | worker 租约 |
| `cancel_requested_at` | `RUNNING` 任务的协作式取消标记 |
| `progress` | 结构化、无敏感内容的进度 |
| `last_error` | 脱敏且限长 |
| `created_at` / `started_at` / `finished_at` / `updated_at` | 生命周期 |

首版每一行任务都必须指向一个具体文档和一个目标 Profile。按 Profile 或 scope
重嵌入时，服务先解析并授权文档，再为每个文档创建 `EMBED_DOCUMENT` 行，并用
`batch_id` 关联这一批任务；不把“全 Profile 重建”建模成缺少 `document_id` 的
特殊行。这样 worker 只处理同一种任务，查询和失败恢复也保持简单。

当前 embedding 写入链只支持一个活动 Profile，`DocumentEmbedService` 和
`EmbeddingBatchService` 也绑定当前活动 EmbeddingModel。因此首版创建 API 不接受
任意 `embeddingProfileId`：它捕获服务端当前活动 Profile。worker 调用 provider 前
再次比较活动 Profile；若已经切换，则把旧任务标为 `STALE`，由操作者针对新活动
Profile 创建新批次。首版不为历史 Profile 动态重建 EmbeddingModel，也不让旧任务
使用新模型写入旧 Profile。

当前 `DocumentEmbedService` 将 provider 调用和向量提交封装在同一同步方法中。
worker 不能直接调用该方法后再假设自己能拦截提交；实施时应增加最小的内部
worker-aware 提交门（例如 `EmbeddingCommitGuard` 回调，或 prepare/generate/commit
两阶段内部 API），由旧同步端点使用 allow-all guard，job worker 在最终
`EmbeddingPersistenceService.replace` 前重新检查 `cancel_requested_at`、活动
Profile、文档版本和 content hash。不要在 worker 中复制一套 embedding 分块/持久化
逻辑；最终仍由现有 persistence CAS 负责最后一致性保护。

活动任务使用 partial unique index 保证幂等，唯一列不包含会变化的 `status`：

```sql
UNIQUE (document_id, embedding_profile_id, content_hash)
WHERE status IN ('QUEUED', 'RUNNING')
```

`force` 不参与唯一键：如果已有相同文档/Profile/hash 的活动任务，新的请求复用
该任务；当新请求要求 `force=true` 而旧任务为 `false` 时，服务在同一事务中把
旧任务升级为 `force=true`，不创建第二个 provider 调用。`batch_id` 是该活动任务
身份的逻辑 coalescing 批次；复用时返回原 `batchId` 并在响应中标记 `COALESCED`，
避免一个 job 同时属于多个物理批次。

普通文档使用 `contentHash`；JSON record 使用 `retrievalText` 的 `contentHash`；外部文档还要保留现有 `sourceRevision`/版本语义。`document_version` 推荐直接使用现有 `RagDocument.version`，`DocumentVersion.versionNumber` 继续承担审计版本号，不用再发明第三套版本。不得将完整内容或 `jsonbPayload` 复制进 job。

若 Batch B 与 Batch C 在同一轮实施，推荐将 job 表放入 V33、JSONB filter 索引放入 V34；若其他已合并工作先占用了版本号，则使用当时最新版本之后的下一个连续版本，并同步更新进度台账和文档，不得改写 V1–V32。

### 6.3 Worker 与 API

worker：

1. `@Scheduled` 以有界批量轮询；
2. 事务内用 `FOR UPDATE SKIP LOCKED` 抢占 queued 或租约过期任务；
3. 如果抢到的任务已有 `cancel_requested_at`，直接标记 `CANCELLED`，不调用 provider；
4. provider 调用在事务外；
5. 确认任务 Profile 仍是当前活动 Profile，否则标记 `STALE`，不调用 provider；
6. 通过 worker-aware 的 `DocumentEmbedService` 内部 API 调用现有绑定活动 Profile
   的 freshness 校验，并传递任务的 `force`；
7. provider 返回后重新读取任务并再次比较当前活动 Profile；只有未请求取消、
   Profile 未切换且 `documentVersion + contentHash + profile` 仍匹配时才提交向量，
   否则标记 `STALE`；
8. 崩溃后租约到期可重试；
9. 超过次数进入 `FAILED`，不无限重试；
10. 记录 Micrometer 指标：queued/running/succeeded/failed/stale、等待时长、处理时长和 provider error 分类。

建议 API（首版创建请求只能提交 `documentIds` 或一个已授权的 Collection scope，
服务端最多展开 1000 个文档；响应返回 `batchId` 和逐文档 job 摘要）：

```json
{
  "documentIds": [153, 154],
  "force": true,
  "maxAttempts": 3
}
```

`documentIds` 与 Collection scope 互斥；scope fan-out 使用当前活动 Profile。
`force=false` 只补齐 stale/failed 任务，`force=true` 表示显式重索引。服务端对
`maxAttempts` 使用有界配置上限，不接受无限重试。

- `POST /api/v1/rag/embedding-jobs`：按 document IDs 或受限 scope 创建任务；
- `GET /api/v1/rag/embedding-jobs/{id}`：查询单文档任务状态；
- `GET /api/v1/rag/embedding-jobs`：按 `batchId`、status、Collection、文档分页；
- `POST /api/v1/rag/embedding-jobs/{id}/retry`；
- `POST /api/v1/rag/embedding-jobs/{id}/cancel`。

取消语义固定为：`QUEUED` 可立即变为 `CANCELLED`；`RUNNING` 只设置
`cancel_requested_at`，不强杀正在进行的 provider 调用。provider 返回后、替换向量
之前，worker 必须重新读取任务；若已请求取消，则跳过向量提交并标记 `CANCELLED`。
终态任务的重复取消/重试返回当前状态或明确的 409，不改变已完成向量。

旧的 `/{id}/embed`、`/batch/embed` 和 SSE 首版保持同步语义，不强制改成异步。外部 upsert/JSON upsert 可通过显式 `embedMode=ASYNC` 选择创建任务，默认不改变已有行为。

### 6.4 验收与回滚

必须覆盖：

- 两个 worker 并发时同一 job 只被一个领取；
- lease 到期后可恢复；
- 重启后 queued/running-expired 可继续；
- provider 失败按 backoff 重试并最终 FAILED；
- 新内容提交后旧 hash/version 任务进入 STALE，不能覆盖新向量；
- 活动 Profile 切换后，旧 Profile 的排队/过期任务进入 STALE，不能借用新模型执行；
- 活动 Profile 在 provider 调用期间切换时，返回结果也不能提交，任务进入 STALE；
- 相同活动任务幂等；
- `force=true` 能原子升级已有的 `force=false` 活动任务，并返回 `COALESCED`；
- `QUEUED` 与 `RUNNING` 状态变化期间 partial unique index 仍阻止重复活动任务；
- 运行中取消在 provider 返回后、向量提交前生效；
- worker 崩溃后，带取消标记的过期任务不会重新调用 provider；
- 任务创建、查询、重试、取消遵守 Collection ACL；
- 旧同步 API、JSON payload-only update 和 freshness 行为不变；
- PostgreSQL 集成测试、`mvn clean compile test-compile`、服务启动验证；
- `scripts/verify-embedding-jobs.sh` 输出稳定的 JSON/文本摘要。

回滚：

- 停止 worker 不影响旧同步接口；
- `rag.embedding-jobs.enabled=false` 时不会产生“已接收但无人执行”的新任务；
- 禁止创建新 async job 后，历史 job 仍可查询；
- migration 只新增表和索引，不删除现有向量或改写已执行迁移。

## 7. Batch C-1：JSON structured record 检索闭环

### 7.1 不改变的核心契约

调用者继续负责：

- `jsonbPayload`：原始业务 JSON；
- `retrievalText`：从 JSON 派生的自然语言描述；
- 两者是否对应、一致或最新。

RAG 服务继续：

- 只对 `retrievalText` 计算 `contentHash`；
- 只对 `retrievalText` 分块、全文索引和 embedding；
- 不增加 `payloadHash`；
- 不自动把 payload 转成文本；
- 不把 payload 默认放入普通 `KNOWLEDGE` prompt；
- payload-only update 不触发 embedding provider 调用，但保存版本快照。

### 7.2 第一阶段：受限 payload containment filter

在现有 `POST /api/v1/rag/json-records/search` 增加可选字段：

```json
{
  "query": "破皮沙发",
  "collectionKeys": ["furniture-records"],
  "payloadContains": {
    "status": "active",
    "category": "sofa"
  },
  "config": {
    "maxResults": 10
  }
}
```

首版只承诺 PostgreSQL JSONB containment：

```sql
jsonb_payload @> CAST(:payloadContains AS jsonb)
```

限制：

- 只接受 JSON object；
- 表示完整子树包含，object 内字段为 AND；
- 默认限制 `payloadContains` 序列化后不超过 16 KiB、嵌套深度不超过 8；
- 空 object 首版返回 400，避免把“没有结构过滤”伪装成已生效的过滤条件；
- 不能传任意 SQL、JSONPath、正则或表达式；
- 首版不承诺范围比较、数组元素任意匹配、大小写不敏感匹配；
- filter 必须作为候选资格下推到向量/全文 SQL，在各自 `LIMIT`、结果融合和 rerank
  之前生效；禁止先取 top-k 再在 Java 中过滤，否则会把真正命中的记录过滤掉；
- 推荐扩展 `HybridRetrieverService`/全文 provider 的服务端 filter 参数，或构造
  一个带参数绑定的 JSONB 预筛选子查询；不得把 JSONB filter 拼成 SQL 字符串；
- SQL 必须同时带 Collection、`document_type=json-record`、enabled 和 embedding freshness 条件；
- 新增 V33+ `jsonb_path_ops` GIN 索引前先用 PostgreSQL 集成测试确认 query plan；
- filter 不改变向量语义，只改变候选资格。

HTTP 结果仍返回当前 payload、retrievalText、score 和来源身份，不改变现有响应字段。

### 7.3 第二阶段：可选 JSON 专用 Agent Tool

在已有 Spring AI Tool Calling 上增加可选的 `searchJsonRecords`：

```json
{
  "query": "破皮沙发",
  "payloadContains": {"status": "active"},
  "maxResults": 5
}
```

模型可见参数不包含：

- Collection ID；
- API Key；
- principal；
- scope mode；
- 任意 SQL/JSONPath；
- payload 字段授权策略。

服务端从 `AuthorizedRetrievalContext` 注入：

- 已解析的不可变 Collection scope；
- `documentType=json-record`；
- max results；
- payload 输出预算；
- citation trace。

推荐默认限制：单个工具调用最多返回 5 条记录，单条 payload 最多 32 KiB，
工具总结果最多复用现有 Agent `maxToolResultCharacters` 预算；超过预算时按记录
边界省略 payload，不生成伪造的半截 JSON。

工具只在显式启用的 `AGENT` pipeline 中注册。返回：

- citationId、documentId、externalId、title；
- retrievalText 摘要；
- 受字节预算限制的 JSON payload；
- payload 是否被截断/省略；
- filter 命中摘要。

payload 超出预算时优先省略或返回合法的结构化截断标记，不返回破损 JSON。普通 `KNOWLEDGE` 仍只使用 retrievalText。

### 7.4 实施与验收

实施顺序：

1. DTO/校验和 filter canonicalization；
2. forward-only migration 与 GIN 索引；
3. `JsonRecordService` 的 scope + filter + freshness SQL；
4. controller 集成测试；
5. `searchJsonRecords` 复用同一 service；
6. AGENT tool schema、预算、citation 和配置开关；
7. JSONB E2E 脚本和文档。

必须覆盖：

- payload-only update 不调用 embedding；
- retrievalText 改变使旧 embedding 不可检索；
- filter 命中/不命中；
- nested object、空 object、超大 object、非法值；
- Collection ACL 和 document type 不能被 filter 绕过；
- 工具不能通过参数扩大 scope；
- payload 预算与合法 JSON 输出；
- PostgreSQL query plan 和索引使用；
- 现有 JSON upsert/search E2E 不回归。

## 8. Batch C-2：真实检索回归门禁

### 8.1 当前基础和真正缺口

当前已有 `RetrievalEvaluationServiceImpl`、Evaluation WebUI 和 `run-retrieval-goldenset.sh`。goldenset 脚本已经通过真实 Search API 获取结果，再调用 Evaluation API 计算 MRR、Precision@K 和 nDCG；因此不应重新设计一套评估算法。

真正缺口是：

- case 的相关文档身份主要依赖运行时 document ID 或脚本创建标题；
- 没有稳定的 dataset/version 标识；
- 没有统一记录 scope、retrieval config、active Embedding Profile 和代码版本；
- 没有明确的 baseline/threshold/delta 门禁；
- 失败、跳过和 provider 不可用容易被误读为“质量通过”；
- Search 和 Chat 的关键检索语义没有固定的精确词、范围和空结果回归案例。

### 8.2 推荐的低风险第一阶段

第一阶段先做**文件版数据集 + runner + CI gate**，不急着新增完整评估数据库：

```json
{
  "dataset": "retrieval-core",
  "version": 1,
  "k": 5,
  "cases": [
    {
      "id": "exact-term-sofa",
      "query": "破皮沙发",
      "scope": {
        "mode": "SELECTED_COLLECTIONS",
        "collectionKeys": ["furniture-records"]
      },
      "relevant": [
        {"collectionKey": "furniture-records", "externalId": "record-1"}
      ],
      "minimum": {"hitRate": 1.0, "mrr": 0.5}
    }
  ]
}
```

身份解析优先级：

1. JSON/external document 的 `collectionKey + externalId`；
2. 普通文档的稳定 source identity；
3. 仅测试 fixture 才使用 title；不把数据库 ID 写成长期基准。

runner 必须：

1. 创建/同步测试 fixture 或使用专用 Collection；
2. 以 case scope 调用真实 Search/检索 service；
3. 记录原始 query、effective query、scope 摘要、retrieved identities、latency；
4. 计算 Precision@K、Recall@K、MRR、nDCG、Hit Rate；
5. 与 baseline 和 minimum threshold 比较；
6. provider、embedding、数据库不可用时返回 FAILED/SKIPPED 并退出非 0，不能报告通过；
7. 输出机器可读 JSON artifact 和人可读摘要。

### 8.3 第二阶段

当文件版门禁在 CI/发布脚本稳定运行后，再考虑：

- `rag_eval_datasets`、`rag_eval_cases`、`rag_eval_runs` 持久化；
- WebUI dataset/run/trend 页面；
- Chat trace 与 Search trace 对齐；
- feedback 自动转为候选 case；
- A/B 实验引用同一 dataset；
- LLM-as-judge 作为独立的答案质量指标，不作为第一道检索阻断门禁。

### 8.4 必须加入的回归案例

- 原始精确词必须至少有一个 lexical query；
- query expansion 不能丢失中文专名、产品名、型号、代码和引号短语；
- Search 和 Chat 使用相同 Collection scope 时不能一个全库、一个空库；
- body/header scope 冲突；
- 无授权 Collection；
- payload-only JSON update；
- stale embedding；
- 相关文档不存在时的明确零命中。

建议脚本：

- `scripts/run-retrieval-regression.sh`；
- `scripts/verify-quality-regression.sh`。

## 9. 统一实施步骤

### Phase 0：实施前置

1. 记录 `git status --short`，不使用 `stash`、`reset --hard` 或回滚其他人的 WIP；
2. 重新读取 [AGENTS.md](../../../AGENTS.md)、[项目文档 Skill](../../../.agents/skills/project-docs/SKILL.md)、
   架构、REST、配置和测试文档；
3. 核对当前 commit、Flyway 最新版本、Spring AI 依赖和前端脚本；
4. 创建独立的 `docs/drafts/*_PROGRESS.md` 进度台账；
5. 先运行基本编译/文档门禁，确认基线没有把新问题误归因于本批。

### Phase 1：Batch A

1. 先写 scope 合成、body/header 冲突和 ACL 集成测试；
2. 再实现 alias、DTO、Controller 和 non-streaming；
3. 再实现 SSE、error envelope、starter wiring；
4. 添加 one-command HTTP contract script；
5. 更新 live API/configuration/SSE 文档。

### Phase 2：Batch B

1. migration、repository 和 claim/lease 测试；
2. 单文档任务；
3. retry/stale/restart；
4. reindex 扫描与 API；
5. WebUI 状态和一键脚本。

### Phase 3：Batch C

1. 先完善 file-based regression gate；
2. JSON payload filter 与索引；
3. JSON Agent Tool；
4. dataset persistence/UI 只有在文件版门禁稳定后再做。

## 10. 每批质量门禁

后端：

- 相关单元测试、Controller/契约测试；
- 尽可能端到端的 PostgreSQL/Testcontainers 集成测试；
- `mvn clean compile test-compile`；
- core standalone 与 starter consumer；
- 服务可启动；
- 旧 `/api/v1/rag/**` 回归；
- shell `bash -n`、`git diff --check`。

前端（只有改 WebUI 时）：

- `npm run tsc` 或项目实际 TypeScript 检查命令；
- production build；
- Vitest；
- Mock Playwright；
- 只用 DOM 可见性/可访问状态、网络请求/响应、接口 JSON、数据库只读查询和自动化断言，不使用截图。

文档和发布：

- `./scripts/verify-project-docs.sh`；
- 中英文成对更新；
- 不修改已执行 Flyway；
- 不提交密钥、payload dump 或 OpenClaw 本地状态；
- 中国境内构建沿用 `scripts/docker-build-local.sh` 和 [境内网络指南](../../china-network-guide-zh-CN.md)。

基本门禁全部通过后，固定范围执行三轮收敛检查：

1. 协议/API、Collection scope、ACL 和旧契约；
2. schema、并发、幂等、freshness、回滚；
3. WebUI、脚本、文档、默认值和实际运行入口。

任一轮发现实质问题，修复后计数器归零，并重新完成基本门禁和三轮检查。连续三轮无问题且期间无修改，才可宣称该批完成。

## 11. 价值排序、风险和可逆边界

| 功能 | 不做的直接代价 | 最大风险 | 可逆边界 |
|---|---|---|---|
| 动态 scope OpenAI 适配 | 外部生态无法直接消费 RAG；调用方继续写专用适配 | 协议层误放大范围、消息历史丢失 | feature flag、独立 `/v1`、旧 `/api` 不变 |
| 持久化任务 | 大库导入不可运营，失败恢复依赖人工 | 旧 job 覆盖新内容、重复 provider 调用 | 新表、新 worker、旧同步 API 不变 |
| JSON filter/tool | 结构化数据只能粗粒度语义搜，不能安全筛选/交给 Agent | 任意 JSONPath/大 payload 导致 SQL 或上下文风险 | 只开放 `@>`，独立配置和 JSON endpoint |
| 回归门禁 | 检索改动无法证明收益，用户问题反复出现 | 测试 fixture 不稳定导致假通过 | 文件数据集、脚本 gate、不开启时不阻断开发 |

## 12. 明确不应在实施时重新决策的事项

以下是本规划已经做出的默认决策：

| 事项 | 默认决策 | 理由 | 可逆边界 |
|---|---|---|---|
| Collection 是否写入 model/deployment | 不写入 | 真实请求需要动态切换范围 | 加 `requireExplicitScope`，但不固定 key |
| 无 scope 的 OpenAI 请求 | `CALLER_VISIBLE` | 保持现有语义，兼容普通 SDK | 配置改为显式 scope required |
| scope 传输 | `rag.scope` + 重复 header | 同时覆盖 extra body 和只能加 header 的客户端 | 仍保留标准无扩展路径 |
| task queue | PostgreSQL SKIP LOCKED；每个任务对应一个文档，Profile 重嵌入 fan-out 为一批任务 | 当前主数据面已是 PostgreSQL，低运维成本，避免特殊任务行 | 未来高负载时可替换 worker 后端 |
| JSON filter | `jsonb @>` object containment；16 KiB、深度 8、空 object 拒绝，并在 `LIMIT` 前下推 | 可索引、权限边界清晰、低风险 | 后续用独立 query DSL 扩展 |
| JSON 与 retrievalText 对应关系 | 调用者负责 | 项目目标是保存/检索，不是业务语义校验 | 可另立领域校验插件 |
| 评估第一阶段 | 文件数据集 + runner | 利用已有 API，先拿到质量证据 | 证据稳定后再持久化 dataset/run |
| API Key hardening/quota | 延后 | 当前最高价值是能力可用性，不是计费控制面 | 公网化前单独立项 |
| XML/Office | 延后 | 先解决任务可靠性和质量闭环 | 用真实需求与 goldenset 决定格式优先级 |

## 13. 规划完成标准

本文规划阶段完成时必须满足：

- 当前事实均能在代码或 live 文档中定位；
- 没有“固定默认 Collection”设计残留；
- `model` 只选择模型/pipeline，scope 按请求动态传入；
- OpenAI 扩展的 body/header/无扩展语义、ACL 和错误边界明确；
- Spring AI 内置机制和项目自有能力的职责边界明确；
- 每个候选功能都有实现边界、数据/API 形状、测试、脚本、文档和回滚方案；
- API Key 加固/配额、XML/Office 和其他低价值项目明确延后；
- 中英文结构和事实同步；
- 文档经过连续三轮无修改系统检查。

近距离参考：

- [项目上下文](../../project-context-zh-CN.md)
- [架构设计](../../architecture-zh-CN.md)
- [Collection 检索范围语义](../../rest-api-zh-CN.md#collection-检索范围语义)
- [OpenAI 兼容就绪度](../../openai-compatibility-readiness-zh-CN.md)
- [既有 OpenAI 兼容规划](2026-07-21_OPENAI_CHAT_COMPLETIONS_COMPATIBILITY_PLAN.md)
- [JSONB 实施规划](2026-08-15_JSONB_PAYLOAD_RETRIEVAL_IMPLEMENTATION_PLAN.md)
- [外部文档同步规划](2026-08-16_EXTERNAL_DOCUMENT_UPSERT_AND_REINDEX_IMPLEMENTATION_PLAN.md)
- [测试指南](../../testing-guide-zh-CN.md)
- [质量默认值](../../quality-defaults-zh-CN.md)
- [项目文档 Skill](../../../.agents/skills/project-docs/SKILL.md)
