# 架构设计详解

> 📖 [English](architecture.md) · 📖 [中文](architecture-zh-CN.md)

> **spring-ai-rag** — 模型无关、领域解耦、组件化的通用 RAG 服务框架。
> 本文档面向核心开发者和架构评审者。
>
> 文档导航：[index-zh-CN.md](index-zh-CN.md) · 完成状态对照：[IMPLEMENTATION_COMPARISON.md](IMPLEMENTATION_COMPARISON.md)

---

## 1. 设计理念

| 原则 | 含义 | 实现方式 |
|------|------|---------|
| **模型无关** | 切换 LLM 只改配置，不改代码 | Spring AI ChatClient 抽象 + 三 Bean 模式 |
| **领域解耦** | 通用 RAG 核心与业务领域分离 | DomainRagExtension 接口 + SPI 注册 |
| **组件独立** | 每个 Advisor / Service 可单独使用 | 接口优先设计，Spring Bean 自动装配 |
| **可观测** | 每步 Pipeline 可追踪、可度量 | Micrometer 指标 + 检索日志 + A/B 实验 |

---

## 2. 模块结构

```
spring-ai-rag (parent pom)
├── spring-ai-rag-api          # 接口定义、DTO、DomainRagExtension 接口
├── spring-ai-rag-core         # 核心实现（所有业务逻辑）
│   ├── advisor/               # RAG Pipeline Advisors
│   ├── config/                # Spring 配置类
│   ├── controller/            # REST 端点
│   ├── entity/                # JPA 实体
│   ├── exception/             # 业务异常
│   ├── extension/             # 领域扩展机制
│   ├── filter/                # 认证过滤器
│   ├── metrics/               # 监控指标
│   ├── repository/            # 数据访问层
│   ├── retrieval/             # 检索服务（嵌入/改写/重排）
│   └── service/               # 业务服务层
├── spring-ai-rag-starter      # Spring Boot Starter 自动配置
├── spring-ai-rag-documents    # 文档处理组件（分块/清洗）
└── demos/
    ├── demo-basic-rag         # 基础 RAG 示例
    ├── demo-multi-model      # 多模型示例
    ├── demo-component-level   # 组件级集成示例
    └── demo-domain-extension  # 领域扩展示例
```

**依赖方向**：`api ← core ← starter`，`api ← documents`，`starter + documents ← demos`。

---

## 3. 核心设计模式

### 3.1 三 Bean ChatModel 模式

通过 `app.llm.provider` 配置切换模型，无需改代码：

```
                    ┌─────────────────────┐
                    │  app.llm.provider   │
                    │  openai | anthropic │
                    └─────────┬───────────┘
                              │
              ┌───────────────┼───────────────┐
              ▼                               ▼
   ┌──────────────────┐            ┌──────────────────┐
   │ openAiChatModel  │            │ anthropicChatModel│
   │ @Conditional...  │            │ @Conditional...   │
   │ provider=openai  │            │ provider=anthropic│
   └────────┬─────────┘            └────────┬─────────┘
            │                               │
            └───────────────┬───────────────┘
                            ▼
                 ┌─────────────────────┐
     @Primary →  │    chatModel        │
                 │ 自动选择可用的 Bean  │
                 └──────────┬──────────┘
                            ▼
                 ┌─────────────────────┐
                 │ ChatClient.Builder  │
                 │  (Spring AI 抽象)   │
                 └─────────────────────┘
```

**关键实现**：`SpringAiConfig.java`
- 未选中的 provider 返回 `null`（非错误）
- `chatModel` 用 `@ConditionalOnMissingBean` 避免冲突
- `ApiAdapterFactory` 自动检测 API 兼容性（如 MiniMax 不支持多 system 消息）

### 3.2 模式化 Chat Pipeline

生产 Chat 不使用语言相关正则猜测检索意图。公开请求显式选择模式；省略时保持 RAG
兼容默认值 `KNOWLEDGE`：

| 模式 | Spring AI 组合 | 检索行为 |
|---|---|---|
| `KNOWLEDGE` | `RetrievalAugmentationAdvisor` | 每轮固定执行一次授权 RAG |
| `AGENT` | `ToolCallAdvisor` + `searchKnowledge` | 模型可执行零到多次检索 |
| `PLAIN` | `ChatClient` + Memory | 不执行检索 |

```text
RagChatController
  -> CollectionRetrievalScopeResolver
  -> ChatCommandMapper
  -> ChatExecutionService
       -> request-local MessageChatMemoryAdvisor
       -> KNOWLEDGE:
            RetrievalAugmentationAdvisor
              -> CompositeChatDocumentRetriever
                   -> ProjectDocumentRetriever
                   -> 可选 StaticKnowledgeDocumentRetriever
              -> 有界候选池 -> 加权 RRF
              -> ProjectDocumentJoiner
              -> ProjectRerankPostProcessor
              -> CitationQueryAugmenter
       -> AGENT:
            BudgetedToolCallAdvisor
              -> KnowledgeSearchTool
              -> 可选 searchStaticKnowledge / Runtime Skill / allowlisted HTTP Tools
              -> 有界候选池 -> rerank -> 最终 top N
              -> 服务端 ToolContext
       -> PLAIN:
            仅 ChatClient
  -> ChatSessionCoordinator
       -> 原子提交 history + source snapshot + JDBC-compatible Memory 投影
```

`ProjectDocumentRetriever` 将项目更强的检索栈适配到 Spring AI Modular RAG
契约。向量检索、中英文全文检索、RRF 融合、有界 rerank 候选池、rerank、Embedding Profile 过滤、
Collection/API Key ACL、document type 和 document ID 范围因此由 Search、
KNOWLEDGE 与 AGENT 工具共享。

启用静态知识后，`CompositeChatDocumentRetriever` 还会组合启动期构建的
`StaticKnowledgeDocumentRetriever`。该分支仅执行有界 lexical retrieval，不调用
embedding、不写数据库，使用 `STATIC_KNOWLEDGE` 来源并跳过外部 reranker。Runtime Skill
只在 AGENT 暴露有界 catalog/load/reference；配置生成的 HTTP Tool 还要求当前请求已加载
匹配 Skill/capability，并受 HTTPS allowlist、SSRF 防护和共享工具预算约束。

在 `KNOWLEDGE` 的 `spring-ai` 查询策略中，`BoundedMultiQueryExpander` 位于
Spring AI advisor 的扩展与检索之间。它按服务端 `max-retrieval-queries` 限制原始 query
和变体的总 fan-out，按精确文本去重并沿用每个 `Query` 的 history/context；trace 同时记录
计划 query 数、预算是否收敛、去重数和 degraded 状态。这个摘要不写入 query 文本。
KNOWLEDGE 的 query budget 与 AGENT 的 tool retrieval budget 分开，避免调高 Agent 工具
上限隐式放大固定 RAG 的数据库和 embedding 调用。

全部 KNOWLEDGE query 完成检索后，`ProjectDocumentJoiner` 会在 rerank 前合并各自的
`Document` 列表。稳定 identity 为 `documentId:chunkIndex`；同一 identity 重复出现时
保留最高有限 score 的候选，无 identity 的对象保持独立。输出按有限 score 和稳定
identity 排序，避免 Spring AI 内部 `HashMap` 遍历顺序决定同分结果。该步骤只执行有界
本地处理，不增加 SQL、embedding、rerank provider 或 Chat 模型调用。

Chat 响应会在 `metadata.retrieval.documentJoin` 输出四个整数：输入文档数、唯一文档数、
删除重复数和按更高 score 替换次数；同一低基数摘要也会写入对应 retrieval trace
attempt。该摘要不包含 query 文本、Document ID、正文、metadata 值或模型输出。

会话窗口、查询压缩与长期摘要的区别，以及工具循环预算和非文档工具扩展边界，见
[Chat 记忆、RAG 与工具调用](chat-memory-rag-tool-calling-zh-CN.md)。

旧 `QueryRewriteAdvisor`、`HybridSearchAdvisor` 与 `RerankAdvisor` 仍可作为组件级/
兼容 API 使用，但不是生产 mode-aware Chat 的执行链。

### 3.3 模型调用级用量归因

`BudgetedChatModel` 是 Chat execution 的模型调用级持久用量边界。每一次属于
Chat execution 的 `ChatModel.call` 或流式订阅最多写入一条终态事件，内容包括
logical execution、call ordinal、principal/session/trace、规范化模型引用、Chat
模式、调用用途、结果、是否流式、provider usage、调用开始时捕获的价格快照以及有界
耗时。主回答、查询转换/扩展、摘要、fallback candidate、应用重试和 AGENT 工具调用
轮次，只要经过 mode-aware 或兼容 Chat 入口，都会进入同一归因链路。

V53 新增 append-only 的 `rag_llm_usage_event` 表。`JdbcLlmUsageRecorder` 对非流式
调用使用有界同步确认，对流式调用使用有界异步记录。记录超时、队列拒绝或数据库
故障均对 Chat 结果 fail-open，并只暴露进程本地丢失事件计数；保留任务按有界批次
删除过期事件。

`GET /api/v1/rag/usage` 只读取聚合结果。普通 principal 只能查询自身 owner 的事件；
ADMIN 和 environment root 可以查询全部 principal 或指定 principal。token 总量和配置
成本都是显式聚合值；provider usage 或价格缺失时计数但不猜测。该账本是可观测性数据，
不是 provider 账单、结算或 hard-limit 执行依据。

### 3.4 集成数据面可观测性

外部集成数据面使用独立的请求观测链路：

```text
IntegrationObservationFilter
  -> 固定 method/path 分类
  -> 最终 HTTP status + wall duration
  -> stable principal 投影
  -> 已授权 Collection request context
  -> 有界 IntegrationObservationRecorder queue
  -> 分组 PostgreSQL upsert
       -> rag_api_operation_hourly
       -> rag_api_collection_operation_hourly
```

V54 保存 UTC 小时级请求总量和已授权 Collection contribution。request 表中每个请求只
计一次；Collection 表记录在当前授权范围内成功解析的 Collection，其行是 contribution，
不能相加冒充请求总量。未知或未授权 Collection key 不会进入 Collection rollup。
两张 rollup 表都以数据库 `CHECK` 约束 operation 的有限枚举；后续迁移新增
`IntegrationOperation` 时，必须在同一迁移中同步扩展两张表的约束，并以真实 PostgreSQL
测试证明全局与 Collection rollup 都能写入。只修改 Java 枚举会使业务请求成功但异步
观测批次被数据库拒绝。

记录是异步且 fail-open 的。queue 溢出、repository 故障和有界停机 drain 丢失不会改变
业务响应，也不会触发 provider/mutation retry。查询 API 通过
`completeness.mode=BEST_EFFORT`、当前进程 dropped count、retention 与最早包含 bucket
明确暴露完整性边界。

`GET /api/v1/rag/integration-observability` 会先按当前授权校验范围，再读取历史聚合。
NORMAL principal 只能查询自身及其当前 Collection 范围；environment root 与数据库
ADMIN 可以查询全局或指定数据库 principal。查询受到时间窗口、HOUR/DAY 粒度、有限
operation 枚举和 Collection breakdown 上限约束。

Micrometer 只使用固定低基数维度暴露请求数量/耗时以及 queue、flush、cleanup、drop
信号。stable principal ID 和 Collection ID 只存在于 PostgreSQL 聚合；credential、
请求/响应正文、query、external ID、动态 URL 与异常正文都不会被记录。这些 rollup
用于故障定位，不是 billing、安全审计、hard quota 或 mutation 恢复依据。

### 3.5 有界分阶段 Credential 轮换

V55 把版本化 API credential 扩展为有界的双 credential 状态：

```text
一个 stable rag_api_principal
  -> 一个 current rag_api_key       (enabled, retire_at 为空)
  -> 至多一个 retiring key           (enabled, retire_at 为未来 deadline)
  -> 至多一个 PENDING rag_api_key_rotation operation
```

prepare 在同一个按 principal 串行化的事务中，把旧 current row 改为 retiring，并创建
下一个 credential version 作为 current。operation ledger 只保存服务生成的 rotation ID、
principal/credential 引用、幂等与请求 fingerprint hash、overlap/deadline、状态和生命周期
时间；绝不保存 raw secret 或原始 Header 值。

每次认证仍执行权威查询，并要求
`retire_at IS NULL OR retire_at > now`。因此即使定时 cleanup 延迟，overlap 也会在
deadline 立即关闭。complete 禁用 retiring credential；cancel 禁用 replacement 并把
retiring row 恢复为 current；expiry 禁用 retiring credential；principal revoke 禁用整个
family。partial unique index 保证每个 principal 至多一个 current、一个 retiring 和一个
pending operation。

两个 credential 投影同一 stable principal policy、Collection ACL、operation
capabilities、Chat/session owner、用量归因和 PostgreSQL quota。staged rotation 只替换认证
材料，不能创建第二个授权身份或 quota bucket。

### 3.6 双表对话记忆

| 表 | 用途 | 管理方 |
|---|------|--------|
| `spring_ai_chat_memory` | LLM 的近期、可恢复上下文窗口 | Spring AI JDBC + 项目投影 |
| `rag_chat_history` | 按 principal 归属的业务历史、来源与审计 | 应用事务 |

```
已完成 turn
  -> rag_chat_history（owner、user/assistant、sources、mode/model/status）
  -> spring_ai_chat_memory（仅可恢复 user/plain assistant 消息）
  -> rag_chat_history.metadata.toolTranscript（有界、完整配对工具交换）
  -> 由 rag_chat_session_lease 保护的同一事务
```

`ChatSessionCoordinator` 对 `owner_principal_id + session_id` 提供 single-flight，
续租带 token fencing 的数据库 lease，并原子提交 history 与 JDBC Memory。历史查询、
导出、清空和会话 baseline 都按认证 principal 隔离；不存在与属于其他 principal 的
session 都返回 `SESSION_NOT_FOUND`，避免会话枚举。

客户端取消会 dispose 模型流，不提交未完成 turn；流式 fallback 只允许发生在第一个
客户端可见事件之前。

### 3.7 领域扩展机制

通过 `DomainRagExtension` 接口实现显式领域定制：

```java
public interface DomainRagExtension {
    String getDomainId();
    String getDomainName();
    String getSystemPromptTemplate();
    default String getSystemPromptTemplate(ChatMode mode);
    default RetrievalConfig getRetrievalConfig();
}
```

**注册流程**：
1. 实现 `DomainRagExtension` 并标注 `@Component`
2. `DomainExtensionRegistry` 构造时自动发现所有实现
3. 请求显式携带 `domainId` 时激活对应扩展；未知 ID 返回 `UNKNOWN_DOMAIN`
4. 省略 `domainId` 时使用通用 Chat 默认值，不受 Spring Bean 注册顺序影响

检索上下文由 `CitationQueryAugmenter` 或 `KnowledgeSearchTool` 注入，领域提示词不应包含
`{context}`。旧模板在 `KNOWLEDGE` 中仍可兼容；在 `AGENT/PLAIN` 中必须覆盖模式感知方法，
否则返回 `DOMAIN_MODE_UNSUPPORTED`。`postProcessAnswer()` 与 `isApplicable()` 是 legacy
API，新生产 Chat 主链不调用。

---

## 4. 数据流

### 4.1 RAG 问答请求流

```
POST /api/v1/rag/chat/ask
  │
  ▼
RagChatController
  │ 解析 principal + Collection/document 范围
  ▼
ChatCommandMapper
  │ 合并请求覆盖项、领域检索配置和默认值
  ▼
ChatExecutionService
  ├── KNOWLEDGE -> Spring AI Modular RAG + 项目检索
  ├── AGENT     -> Spring AI Tool Calling + 授权检索工具
  └── PLAIN     -> 模型 + Memory，不检索
  ▼
ChatSessionCoordinator
  │ lease fencing + 原子提交 history/source/memory
  ▼
ChatResponse 或结构化 SSE 事件
```

### 4.2 Collection 范围检索

Collection 已经是实际检索边界，不只是管理页面中的文档分类。

```text
ChatRequest / SearchRequest
  collectionScopeMode? + collectionKeys? + deprecated collectionIds?
  + documentIds?
        │
        ▼
CollectionRetrievalScopeResolver
  - 省略 collectionScopeMode 时推导兼容模式
  - 校验模式/列表组合及 100/1000 项上限
  - 通过 CollectionIdentityResolver 批量解析 key -> Long ID
  - 先应用 ApiKeyCollectionAccess，再生成有效范围
        │
        ▼
RetrievalScope
  collectionFilter = NONE | ANY_ASSIGNED | SELECTED
  collectionIds + documentIds + 服务端 documentType + matchNone
        │
        ├── Chat -> RagChatService -> HybridSearchAdvisor
        ├── Search -> RagSearchController
        └── JSON record -> JsonRecordService
        │
        ▼
HybridRetrieverService.searchInScope
  + RetrievalScopeSql
  - NONE：不加 Collection predicate
  - ANY_ASSIGNED：d.collection_id IS NOT NULL
  - SELECTED：d.collection_id = ANY (?)，参数为 JDBC bigint[]
  - 文档：e.document_id = ANY (?)，参数为 JDBC bigint[]
  - JSON record：d.document_type = ?
        │
        ▼
Vector、English FTS、pg_jieba、pg_trgm 使用同一组 predicate
```

请求语义：

| 模式 | 不受限调用方 | 受限 API Key |
|------|--------------|---------------|
| `CALLER_VISIBLE` | 全部可检索文档，包括未归属文档 | Key 的 Collection allow-list 内文档 |
| `ANY_COLLECTION` | 所有 `collection_id IS NOT NULL` 的可检索文档 | Key 的 Collection allow-list 内文档，不会扩权 |
| `SELECTED_COLLECTIONS` | 指定 Collection 的并集 | 指定集合必须是 allow-list 子集 |

省略 `collectionScopeMode` 时，只要出现 Collection 列表就推导为
`SELECTED_COLLECTIONS`，否则使用 `CALLER_VISIBLE`。`CALLER_VISIBLE` 与
`ANY_COLLECTION` 不允许出现任何 Collection 列表；`SELECTED_COLLECTIONS`
必须提供非空 key 或 ID 列表。同时提供两种列表时，两者必须标识同一集合，忽略顺序。
`documentIds` 始终作为额外交集，不能绕过授权。请求最多接受 100 个 Collection 身份和
1000 个 document ID。

key 按原值、区分大小写地一次批量解析。不受限调用方的未知 key 返回 `404`；受限调用方
统一返回 `403`，避免泄露范围外 Collection。deprecated 的未知数字 ID 保持兼容：
对不受限调用方不匹配任何行，受限调用方返回 `403`。

数据模型和当前边界：

- `rag_collection.id` 继续作为内部 `BIGINT` 主键；
  `rag_collection.collection_key VARCHAR(128) COLLATE "C"` 是稳定外部身份。它全局唯一、
  不可变、区分大小写，软删除后仍保持占用。
- `rag_documents.collection_id` 是可空外键，所以一个文档最多属于一个 Collection；
  未归属文档不能通过 Collection 范围选中，但仍可出现在未限定 Collection 或显式
  `documentIds` 的检索中。数据库外键和检索 SQL 继续使用数字 ID。
- Collection 创建、导入和克隆必须由调用方提供 key。by-key CRUD、恢复、文档关联和导出
  使用 query parameter，不使用 path segment，因为合法 key 可以包含 URL 保留标点。
- V52 为 Collection 创建增加可选、按调用方隔离的持久化幂等。成功 Collection 与
  operation ledger 在同一事务中提交，身份为服务端派生 owner 加 key hash；精确 replay
  返回 Collection 当前状态且不重复写创建审计，语义复用冲突，账本关闭或不可用时
  fail closed。
- API Key 管理对外使用 `allowedCollectionKeys`；V48 在
  `rag_api_principal.allowed_collection_ids` 保存权威内部 ID ACL。活动 credential 保留
  兼容 snapshot，请求授权使用 indexed join 加载的不可变 principal policy。V49 在同一
  principal policy 中保存 `RAG_READ` / `RAG_WRITE`，认证后、共享限流前的中央过滤器
  对 NORMAL principal 执行操作能力；未知 mutation 默认要求 write。V50 按 requester
  owner 与 idempotency-key hash 保存成功的 principal provisioning operation。请求
  fingerprint 基于解析后的有效 policy；replay 读取 principal 当前 credential 状态，
  不保存或重建原始 secret。
- 删除 Collection 会尝试软删除集合；若存在 `externalId` 非空的外部托管文档则返回 `409`，
  不会执行删除，因为清空 `collection_id` 会破坏
  `collectionKey + sourceNamespace + externalId` 稳定身份。
  没有这类文档时才会批量清空普通 legacy 文档的 `collection_id`，不会删除文档或
  `rag_embeddings`；这些文档之后仍可能被全库检索命中。
- `rag_collection.embedding_model` 和 `dimensions` 尚未参与运行时模型路由；当前仍使用
  全局 EmbeddingModel，但每次写入和查询都会绑定到一个不可变 Embedding Profile。
- 向量检索要求活动 Profile、最新 content hash 对应的 `COMPLETED` 状态以及启用的文档。
  全文检索改为使用与 Profile 无关的本地 chunk generation 及对应 local-index 状态；
  向量任务排队或失败时，全文仍可通过 `KEYWORD_ONLY` 工作。
- Collection 条件通过 `d.collection_id` 直接下推到检索 SQL；selected ID 与显式
  document ID 使用 PostgreSQL `bigint[]` JDBC 数组参数。因此范围成本取决于选中的
  Collection 数量，而不是这些 Collection 包含的全部文档数。
- 可选 `filters.metadataContains` / `filters.payloadContains` 通过 PostgreSQL
  `@>` 下推到同一候选 SQL（V36 GIN）。这不是查询语言；未知字段或空对象返回 `400`。
- 检索诊断（V35）记录 outcome / empty-reason / 过滤器摘要，默认不存 query 明文。
  Search/Chat 空结果会返回 `X-RAG-Retrieval-Trace-Id`；写入失败 fail-open。
- 结果是在有效 Collection 并集上计算一次全局 top-k；“每个选中 Collection 都必须贡献
  结果”的独立 `EACH_COLLECTION` 行为尚未实现。
- WebUI Chat 与 Search 均提供三种模式；selected 模式支持服务端搜索、每页 50 项、
  跨页多选，最多选择 100 个 Collection key。

源码锚点：

- [CollectionIdentityResolver](../spring-ai-rag-core/src/main/java/com/springairag/core/service/CollectionIdentityResolver.java)
- [ApiKeyCollectionAccess](../spring-ai-rag-core/src/main/java/com/springairag/core/security/ApiKeyCollectionAccess.java)
- [CollectionRetrievalScopeResolver](../spring-ai-rag-core/src/main/java/com/springairag/core/service/CollectionRetrievalScopeResolver.java)
- [RetrievalScope](../spring-ai-rag-core/src/main/java/com/springairag/core/retrieval/RetrievalScope.java)
- [RetrievalScopeSql](../spring-ai-rag-core/src/main/java/com/springairag/core/retrieval/RetrievalScopeSql.java)
- [RagChatService](../spring-ai-rag-core/src/main/java/com/springairag/core/config/RagChatService.java)
- [HybridSearchAdvisor](../spring-ai-rag-core/src/main/java/com/springairag/core/advisor/HybridSearchAdvisor.java)
- [RagSearchController](../spring-ai-rag-core/src/main/java/com/springairag/core/controller/RagSearchController.java)
- [HybridRetrieverService](../spring-ai-rag-core/src/main/java/com/springairag/core/retrieval/HybridRetrieverService.java)

回归测试锚点：

- [CollectionRetrievalScopeResolverTest](../spring-ai-rag-core/src/test/java/com/springairag/core/service/CollectionRetrievalScopeResolverTest.java)
- [RetrievalScopeSqlTest](../spring-ai-rag-core/src/test/java/com/springairag/core/retrieval/RetrievalScopeSqlTest.java)
- [MultiCollectionRetrievalPostgresIntegrationTest](../spring-ai-rag-core/src/test/java/com/springairag/core/integration/MultiCollectionRetrievalPostgresIntegrationTest.java)
- [RagSearchControllerTest](../spring-ai-rag-core/src/test/java/com/springairag/core/controller/RagSearchControllerTest.java)
- [HybridSearchAdvisorTest](../spring-ai-rag-core/src/test/java/com/springairag/core/advisor/HybridSearchAdvisorTest.java)
- [ApiKeyCollectionAccessTest](../spring-ai-rag-core/src/test/java/com/springairag/core/security/ApiKeyCollectionAccessTest.java)

### 4.3 文档嵌入流

```
POST /api/v1/rag/documents/{id}/embed
  │
  ▼
DocumentEmbedService
  │ 1. 读取 RagDocument.content
  ▼
HierarchicalTextChunker
  │ 2. 按 Markdown 结构分块
  ▼
TextCleaner
  │ 3. 清洗（去 HTML、规范化空白）
  ▼
EmbeddingBatchService
  │ 4. 按 batchSize 调用 EmbeddingModel
  │ 5. 校验全部结果后，在短事务内原子替换活动 Profile 的
  │    rag_embeddings.embedding_1024 VECTOR(1024) 行
  ▼
完成
```

活动 Profile 注册在 `rag_embedding_profiles`。按文档和 Profile 保存缓存及完成状态的
`rag_document_embedding_state`。模型调用在写事务外执行；短事务只替换该 Profile 的向量行。
批量调用失败或结果不完整时，旧的已完成向量保持可用。

持久化路径由默认开启的 `rag.embedding-jobs.enabled=true` 提供：

```text
POST /api/v1/rag/embedding-jobs
  │ documentIds 或授权后的 Collection scope
  ▼
rag_embedding_jobs（V33/V37）
  │ active job coalesce + bounded retry
  │ origin / requested_by_principal_id
  │ 同事务提交后发布本地 Spring Event
  ▼
EmbeddingJobWorker
  │ 事件立即唤醒；30s 低频扫描仅恢复遗漏通知/重启任务
  │ 条件 UPDATE ... RETURNING 原子 claim + lease + heartbeat
  │ worker-concurrency 限制
  │ provider 调用前后检查取消/Profile/generation/chunker/content hash
  ▼
DocumentEmbedService.embedDocumentForJob
  │ commit guard 通过后原子替换活动 Profile 向量
  ▼
SUCCEEDED | FAILED | CANCELLED | STALE
```

任务表不复制文档正文。写入 API 可通过 `embeddingPolicy` 选择 `SYNC` / `ASYNC` /
`SKIP`；省略时保持 `embed=true→SYNC`、`embed=false→SKIP`。显式 embed/re-embed 拒绝
`SKIP`。`ASYNC` 要求 `rag.embedding-jobs.enabled=true`，否则 `503`。V41 起 job worker
默认开启，因为正文 mutation 的 `SYNC` 与 `ASYNC` 都先持久化任务。就绪接口按
Collection 返回互斥计数。提交后 Spring Event 只提供低延迟本地通知，数据库 job/state
继续承担可靠性、多实例互斥和恢复；同一事务的多个入队会合并成一次事件，worker 也会合并
并发唤醒。Scheduled 默认每 `30s` 扫描一次，只兜底事件丢失、实例重启或处理器异常。

### 4.4 JSON 结构化记录流程

```text
POST /api/v1/rag/json-records/upsert
  │
  ▼
JsonRecordService
  │ 保存 jsonbPayload + retrievalText
  │ 外部身份：collectionKey + sourceNamespace + externalId
  │ Resolver -> 内部 collectionId + namespace + json-record + externalId
  ▼
RagDocument.content = retrievalText
RagDocument.jsonbPayload = 业务 JSONB
  │
  ├──> DocumentEmbedService（只处理 retrievalText）
  │      ├──> 一个 record-level chunk
  │      └──> 活动 Profile embedding
  │
  └──> 版本快照（content + JSONB payload）

POST /api/v1/rag/json-records/search
  │
  ├── 可选 payloadContains -> jsonb_payload @> ?::jsonb
  │   下推到 vector / pg_trgm / English FTS / pg_jieba 候选 SQL
  │
  ▼
HybridRetrieverService -> 排序后的 document IDs
  │
  ▼
从 rag_documents 批量补充 payload
  │
  ▼
排序后的 JSON record 响应
```

`jsonbPayload` 不会复制到 embedding metadata 或普通聊天上下文。JSON 与
`retrievalText` 的语义对应关系由调用者负责，服务只保存二者，不尝试互相校验。
仅更新 payload 会创建审计版本，但保留新鲜 embedding。这是一条明确的 JSONB 专用路径，
公开 upsert/search 请求优先使用 Collection key，响应在 deprecated 内部 ID 旁返回 key；
持久化与检索候选仍使用内部 ID。同一外部身份由数据库唯一约束、JPA `@Version` 和有界
事务重试协调，不使用 advisory lock。
V34 为 enabled `json-record` 增加 partial GIN `jsonb_path_ops` 索引。
可选 `searchJsonRecords` Spring AI Tool 默认关闭；启用后由服务端注入授权范围，模型只能
提供自然语言 query、JSON 子树和结果数，不能提供 Collection、SQL 或 JSONPath。
未来可以按相同的 retrieval-text 边界增加 `xmlPayload`，而不引入泛化的 `payload` 列。

### 4.5 外部文档同步流程

```text
POST /api/v1/rag/documents/upsert
  │
  ▼
ExternalDocumentService / DocumentMutationService
  │ 通过 API Key ACL 解析可写 Collection
  │ 三元身份约束 + source/document revision CAS
  │ 精确重放判断 + 完整版本快照
  ▼
rag_documents（保留同一个 documentId）
  │ 内容变化 -> 新 content hash / request generation
  │ 同事务提交 state + durable job
  │ after-commit Spring Event
  ▼
EmbeddingJobWorker / EmbeddingJobExecutor（事务提交后）
  │ 分块 -> provider -> generation/hash/chunker/Profile/lease 提交门
  │ 原子替换活动 Profile 向量行
  ▼
当前 content hash 的 fresh COMPLETED，或 FAILED 状态
```

普通外部文档与 JSON record 共享唯一三元身份
`(collection_id, source_namespace, external_id)`。唯一索引是首次并发创建的最终仲裁者，
已有行更新使用 source revision/document revision CAS；冲突只做有界重试。
`sourceRevision` 是 opaque 令牌；版本只做相等判断和可选的
`expectedSourceRevision` compare-and-set，不按大小判断新旧。生产默认严格 CAS。
来源删除使用 tombstone
（`enabled=false` 加 `source_deleted_at`）。之后使用与 tombstone 不同的后续
`sourceRevision` 可以恢复同一个内部文档；服务不比较 revision 的大小或新旧。
检索要求文档 enabled 且活动 Embedding Profile 存在当前内容的 fresh completed 状态，
因此旧向量可以物理保留用于诊断，但不会返回给调用方。

本地文档 PATCH、disable、restore 与永久删除使用公开 `documentRevision`，不暴露会被
embedding-only 写入改变的 JPA `rowVersion`。正文变化会在一个事务中提交主记录、完整快照、
freshness state 和持久化 job；metadata、JSONB payload 或 Collection-only 变化不创建新
embedding generation。`DocumentLifecycleService` 把主记录、活动 Profile state 和任务归一
为 `READY/INDEXING/FAILED/NOT_REQUESTED/DISABLED`。

**数据访问并发规则**：生产数据访问层禁止显式 `SELECT ... FOR UPDATE`、`SKIP LOCKED`、
JPA `PESSIMISTIC_*` 和 PostgreSQL advisory lock。worker 使用带状态、owner、lease 和
快照条件的 `UPDATE/DELETE ... RETURNING`；版本分配使用原子计数器；并发槽位和外部身份
使用唯一约束与 `ON CONFLICT DO NOTHING`；文档提交使用版本/content hash CAS。普通短事务
写入仍会由 PostgreSQL 内部获取必要的行/索引锁，这不属于应用显式悲观协调。规则由
`scripts/verify-no-pessimistic-locks.sh` 守护。

### 4.6 Collection 受保护清理与退役

```text
POST /collections/by-key/purge/preview
  │ root / database ADMIN / 显式本地 loopback 授权
  │ 读取计数 + 引用完整性 + active run/repair/session lease
  ▼
rag_collection_purge_preview
  │ token hash + owner + fingerprint + Collection/Chat fence version
  │ 不保存正文或明文 token
  ▼
POST /collections/by-key/purge
  │ 校验 preview 并取得 APPLYING lease
  │ Collection-first 条件写入推进 version/chat fence
  │ 重建并比对冻结计划
  ▼
单事务删除 documents/derivations/referencing feedback/chat/audit
  │ rag_collection 保留最小 tombstone
  ▼
RETIRED + 有界成功结果 replay
```

V56 为 Chat history 和 feedback 建立规范化 document reference，并保存
`content_reference_index_complete`。历史 JSON 损坏不会阻断迁移，但任一 incomplete 行会
使 purge fail closed，直到修复并重建引用。一个持久化 Chat 会话只要引用目标文档，就按
owner/session 删除全部 history、Spring AI memory、summary 和 turn replay，避免回答或
工具结果中的正文摘录残留。

所有会创建、移动、恢复或复制 Collection 内容的生产写路径先按 Collection ID 排序消费
active-write token，再触碰 document/控制面行。purge 使用相同 Collection-first 顺序：
业务先提交会推进版本并使旧 preview 失效；purge 先提交会令业务 reservation 更新不到
active 行并回滚。Chat commit 另推进 `chat_commit_fence_version`，session lease 保持到
summary compaction 停止写入。embedding worker 的最终 generation/hash/profile/lease 提交
门在 document/job 被删除后失效，因此迟到 provider 响应不能回写退役 Collection。

最终 tombstone 永久占用 `collectionKey`，但固定化 name 并清空 description/metadata。
独立 `fs_files` 没有可靠 Collection 外键，不根据路径或文件名推断删除。能力默认关闭且有
同步事务上限；超过上限拒绝，不分段执行半完成清理。

### 4.7 OpenAI Chat Completions 兼容流

```text
OpenAI client
  │ GET /v1/models 或 POST /v1/chat/completions
  ▼
OpenAiCompatibilityController
  │ model alias + text-only messages[]
  ▼
OpenAiChatRequestMapper
  │ body rag.scope / repeated X-RAG-Collection-Key
  ▼
CollectionRetrievalScopeResolver + API Key ACL
  ▼
transport-neutral ChatCommand
  ▼
ChatExecutionService
  │
  ├── non-stream -> chat.completion JSON
  └── stream     -> chat.completion.chunk* -> data: [DONE]
```

兼容 Controller 默认不注册。model alias 只绑定 mode、memory 和内部模型候选链，不绑定
固定 Collection；Collection 必须按请求解析。它与原生 `/api/v1/rag/chat/stream`
共享执行内核，但使用独立 DTO、错误信封和标准 SSE 映射，不暴露原生 RAG
`tool_start/tool_result/sources/done` 事件。

### 4.8 受管质量套件与 Citation

Chat 在启用时把协议级 `citationValidation` 写入 metadata；只解析约定的 `[S1]` token，
不是覆盖率评分。受管套件（V38）默认关闭：version 一经创建不可变，相关文档必须使用
`collectionKey + sourceNamespace + externalId`。compare 只允许同一 version，环境漂移单独标记。suite
worker 会按 owner 的 `db:{principalId}` 重新加载当前数据库 principal 及当前
credential/policy，并在检索前再次授权定义中的 Collection；principal 缺失、吊销、过期
或 ACL 被收回时 run 以 `FAILED` /
`AUTHORIZATION_CHANGED` 结束。`local:` / `root:` / `legacy:` principal 与 HTTP
关闭鉴权时一样视为 unrestricted。可选 `POST /evaluation/semantic` 按 Spring AI
1.1.4 反射适配（`FactCheckingEvaluator.builder`、
`RelevancyEvaluator(ChatClient.Builder)`、带 `Document` 的 `EvaluationRequest`）；
类或 ChatClient 缺失时返回 `DISABLED`。

---

## 5. 数据库设计

### 5.1 ER 关系

```
rag_collection (1) ──→ (N) rag_documents
rag_documents  (1) ──→ (N) rag_document_embedding_state
rag_documents  (1) ──→ (N) rag_embeddings
rag_documents  (1) ──→ (N) rag_embedding_jobs
rag_documents  (1) ──→ (N) rag_document_chunks
rag_documents  (1) ──→ (1) rag_document_local_index_state
rag_embedding_profiles (1) ──→ (N) rag_document_embedding_state
rag_embedding_profiles (1) ──→ (N) rag_embeddings
rag_embedding_profiles (1) ──→ (N) rag_embedding_jobs
rag_chat_history (1) ──→ (N) rag_chat_history_source_document
rag_user_feedback (1) ──→ (N) rag_user_feedback_document
rag_collection (1) ──→ (N) rag_collection_purge_preview

rag_chat_history        # 对话历史与规范化来源引用
rag_retrieval_logs      # 检索日志
rag_ab_experiments      # A/B 实验定义
rag_ab_results          # A/B 实验结果
rag_user_feedback       # 用户反馈
rag_alerts              # 告警记录
rag_slo_config          # SLO 配置
rag_retrieval_evaluations  # 检索质量评估
rag_evaluation_suites      # 受管质量套件（V38，默认关闭）
rag_evaluation_suite_versions
rag_evaluation_runs
rag_evaluation_case_results
rag_audit_log           # 审计日志（集合操作）
```

### 5.2 关键表结构

| 表 | 关键列 | 说明 |
|---|--------|------|
| `rag_collection` | id, collection_key, name, description, embedding_model, purged_at, chat_commit_fence_version | 内部数字身份、稳定外部 key、退役 tombstone 与 Chat 提交围栏 |
| `rag_documents` | title, content, content_hash, collection_id, source_namespace, external_id, source_revision, document_revision, source_deleted_at, jsonb_payload | 文档真相源、业务 CAS、外部身份与结构化 payload |
| `rag_document_versions` | document_id, version_number, 完整快照字段, snapshot_completeness | 文档 mutation 的完整审计快照 |
| `rag_embedding_profiles` | profile_key, provider, model_name, dimensions, distance_metric | 不可变向量空间身份 |
| `rag_document_embedding_state` | document_id, embedding_profile_id, content_hash, chunker_version, request_generation, active_job_id, status, chunk_count | Profile 级 freshness、活动 generation 与完成状态 |
| `rag_embeddings` | document_id, chunk_index, embedding_profile_id, embedding_1024 VECTOR(1024), content | Profile 级文本块与向量 |
| `rag_embedding_jobs` | document_id, embedding_profile_id, content_hash, request_generation, document_kind, chunker_version, status, lease_expires_at, origin | generation-aware 持久化 embedding/reindex 状态机 |
| `rag_document_chunks` | document_id, local_index_generation, content_hash, chunker_version, chunk_text, chunk_index | 与 Profile 无关的本地关键词 chunk |
| `rag_chat_history_source_document` / `rag_user_feedback_document` | history_id/feedback_id, document_id | V56 规范化内容引用，用于安全 purge 归因 |
| `rag_collection_purge_preview` | owner_key, collection_id, token_hash, fingerprint, status, result_payload | 不保存正文或明文 token 的 preview/apply lease 与结果 replay |
| `rag_document_local_index_state` | document_id, local_index_status, local_index_generation, content_hash, chunker_version, chunk_count | 当前本地关键词 generation 与 freshness |
| `rag_api_principal` | principal_id, role, allowed_collection_ids, capabilities, policy_version, requests_per_minute, expiry_alert_checked_at | stable 调用方 owner、权威 policy 与 V57 到期告警公平扫描游标 |
| `rag_api_key` | key_id, principal_id, credential_version, key_hash, enabled, retire_at | 版本化 credential；每个 principal 至多一个 current 和一个有界 retiring version |
| `rag_alerts` | dedupe_key, condition_state, state_version, notified_version, status, version | 普通告警与 V57 受管条件告警；active dedupe、阶段升级和通知 claim 由 PostgreSQL/CAS 收敛 |
| `rag_alert_notification_delivery` | id, alert_id, notification_version, provider, status, attempt_count, attempt_budget, lease_token, lease_until | V58 durable at-least-once provider delivery ledger；稳定 UUID、唯一约束、lease/CAS 与低敏回执 |
| `rag_api_rate_limit_bucket` | principal_id, window_start, request_count | 共享 UTC 固定分钟 quota bucket |
| `rag_api_provisioning_operation` | owner_id, idempotency_key_hash, request_fingerprint_sha256, principal_id, completed_at | 不保存 raw credential 的成功 provisioning replay 账本（V50） |
| `rag_api_key_rotation` | rotation_id, principal_id, source_credential_id, target_credential_id, expires_at, status | 不保存 raw credential 或 Header 原值的有界 staged rotation 账本（V55） |
| `rag_collection_provisioning_operation` | owner_id, idempotency_key_hash, request_fingerprint_sha256, collection_id, completed_at | 不保存 raw key 或请求体的 Collection 创建成功 replay 账本（V52） |
| `rag_document_sync_runs` | id, collection_id, source_namespace, status, lease_token_hash, 累计计数 | 权威来源快照 run 控制面；lease 只保存 hash |
| `rag_document_sync_run_items` | run_id, external_id, document_kind, source_revision, status, error_message, seen_at | 幂等 item ledger 与持久化低敏 receipt 来源；不保存正文、payload 或 metadata |
| `rag_chat_history` | session_id, user_message, ai_response | 业务审计 |
| `rag_retrieval_logs` | query, strategy, result_count, latency_ms, outcome_code, empty_reason_code | 检索诊断（V35） |
| `rag_evaluation_suites` | suite_key, owner_principal_id | 受管质量套件（V38） |

**索引策略**：
- `rag_collection.collection_key`：命名的全局唯一约束/B-Tree；可见 ASCII CHECK 和
  UPDATE trigger 共同保证字符契约与不可变性
- `rag_embeddings.embedding_1024`：Profile 专属 partial HNSW 索引（向量近邻搜索）
- `rag_documents.content_hash`：B-Tree（哈希去重）
- `rag_document_chunks.search_vector_en`：English FTS 的 GIN 索引
- `rag_document_chunks.chunk_text`：可选 pg_trgm GIN 索引
- `rag_document_chunks`：安装 pg_jieba 时创建 `jiebacfg` 表达式 GIN 索引
- `rag_documents.jsonb_payload`：V34 partial GIN `jsonb_path_ops`（enabled JSON record 的 `@>` 包含过滤）
- `rag_documents.metadata`：V36 GIN（`metadataContains` `@>` 下推）
- `rag_embedding_jobs`：活动任务 partial unique、claim、batch、document 与 status/created 索引
- `rag_api_provisioning_operation`：owner/key-hash 唯一身份与 completed-at 清理索引
- `rag_api_key`：分别约束一个 enabled current（`retire_at IS NULL`）与一个 enabled
  retiring（`retire_at IS NOT NULL`）credential 的 partial unique index，以及 active
  retirement deadline 索引
- `rag_api_key_rotation`：principal/idempotency hash 唯一约束、single-PENDING partial
  unique index、expiry 扫描索引和 terminal retention 索引
- `rag_collection_provisioning_operation`：owner/key-hash 唯一身份、受约束的 Collection
  外键、hash 形状检查与 completed-at 清理索引
- `rag_document_sync_run_items`：V51 提供 `(run_id, seen_at, external_id)` 与
  `(run_id, status, seen_at, external_id)` B-Tree，分别支持未过滤和按状态过滤的有界
  keyset receipt 查询
- `rag_alert_notification_delivery`：alert/version/provider 唯一约束、eligible retry、
  expired lease 与 provider/status/operator 查询索引

### 5.3 全文检索配置

PostgreSQL 全文检索从当前 generation 的
`rag_document_chunks` 与 `rag_document_local_index_state` 读取：
- English FTS 使用 generated `search_vector_en`
- pg_trgm 使用可选的 `chunk_text gin_trgm_ops` 索引
- pg_jieba 使用可选的 `to_tsvector('jiebacfg', chunk_text)` 表达式索引
- 搜索配置：`jiebacfg`（基于 jieba 分词器）
- 支持中文 tokenization + ranking (`ts_rank`)
- `rag_embeddings` 仍是向量真相源；HybridRetrieverService 通过缩放加权 RRF
  （Reciprocal Rank Fusion）融合向量和全文结果。固定 `K=60`，提供方原始分数只用于
  确定各自通道内的名次；不同通道的加权贡献会在候选重叠时相加，最终分数相同则按稳定的
  文档 identity 顺序排序。向量和全文原始分数继续作为诊断字段保留，不跨提供方尺度直接比较。

### 5.4 有界 rerank 与文档覆盖

Search POST、`KNOWLEDGE`、Agent 检索工具、JSON record、Evaluation 和旧 Advisor
共享同一条有效 rerank 顺序：

```text
有界检索候选
  -> provider 对有界候选池排序
  -> 第一遍优先文档覆盖
  -> 覆盖不足时按 provider 顺序回填
  -> 调用方可见的最终 top N
```

provider 继续决定 score 和排名。文档选择器只从 provider 排名中选择一个子序列，并复用
原 `RetrievalResult` 对象。第一遍对每个精确、非空 `documentId` 优先最多保留
`preferred-max-chunks-per-document` 个 chunk；null 或 blank ID 仍视为独立结果。不同
文档不足以填满最终数量时，第二遍按 provider 原顺序恢复被跳过的 chunk，因此该偏好不会
减少调用方可获得的已排名结果数量。

内置 heuristic provider 的词法 relevance/diversity 对无 CJK 的空白 token 保持既有
语义；HAN、HIRAGANA、KATAKANA、HANGUL 和 BOPOMOFO 连续片段使用相邻 code-point
bigram，单字符片段保留单字符，混合片段中的 Latin/数字 run 独立参与匹配。每个 query
或 chunk 最多提取 512 个特征，并在一次 rerank 内预计算后复用；因此不会随正文长度产生
无界 token 集合，也不会为每个候选对重复拆词。diversity 按候选位置排除 self，另一条
完全相同的非空 chunk 会得到 similarity `1`，而 null/blank chunk 不会因缺少词法信息
获得 diversity 奖励。HTTP rerank 成功路径不受影响；HTTP 降级到 heuristic 时复用同一
行为。

heuristic relevance 同时读取候选的正文和权威文档标题：
`effectiveRelevance = max(contentRelevance, 0.9 * titleRelevance)`。标题只做一次
`Locale.ROOT` 规范化，不进入 chunk 特征集合或 diversity；因此标题命中可以纠正文档
正文片段未重复产品 ID、术语或主题名时的排序，而不会叠加放大正文命中或改变文档间相似度。
null/blank 标题精确保留原有评分路径。HTTP provider 成功请求仍只发送 chunk 正文，不发送
标题；只有 HTTP 降级到 heuristic 时使用标题相关性。该行为不增加 SQL、embedding、HTTP
或 Chat 模型调用。

正文和标题计算 relevance 前，query 的有序词法特征只准备一次，并供全部候选复用。对于
不含 CJK code point、且首尾为 Unicode 字母或数字的普通 term，匹配使用完整字母数字
边界：相邻的非 CJK 字母或数字会阻断 occurrence，标点、分隔符、文本边缘和 CJK/非 CJK
script transition 都视为边界。query term 外层明确的句末/包裹标点会被移除，但 `+`、
`#`、`-`、`_`、`/` 和 `\` 仍属于技术标识符。CJK 特征以及 `C++`、`C#` 等以符号结尾
的 term 继续使用 substring。这样 `rag`、`ai`、`9042` 不会误命中 `storage`、`OpenAI`
或 `19042`，同时保留 `RAG?` -> `RAG-based`、`SpringAI` -> `中文SpringAI检索` 和
`9042` -> `型号9042说明`。

选择器最多处理 `candidate-limit` 项，不增加 SQL、embedding、rerank provider 或 Chat
模型调用。配置为 `0` 时关闭该选择，恢复 provider top-N 行为。

---

## 6. 配置体系

### 6.1 RagProperties 统一配置

`@ConfigurationProperties(prefix = "rag")` 统一管理所有业务配置：

```yaml
rag:
  retrieval:
    default-limit: 10            # 返回结果数
    min-score: 0.3               # 最低相似度
    vector-weight: 0.5           # 向量/RRF 通道权重
    fulltext-weight: 0.5         # 全文/RRF 通道权重
  rerank:
    enabled: true
    provider: heuristic
    top-n: 5                      # 调用方未提供 maxResults 时的最终 fallback
    candidate-limit: 20           # rerank 前候选池上限，绑定范围 1..100
    preferred-max-chunks-per-document: 2  # 第一遍文档覆盖偏好
  chunk:
    default-chunk-size: 1000
    default-chunk-overlap: 100
  memory:
    max-messages: 20             # 对话记忆窗口
```

### 6.2 多环境配置

| 配置源 | 说明 |
|--------|------|
| `application.yml` | 默认配置 |
| `.env` | 环境变量（API Key、数据库密码） |
| `RagProperties` | 类型安全的业务配置 |
| `spring.ai.openai.*` / `spring.ai.anthropic.*` | LLM 配置 |
| `siliconflow.*` | 嵌入模型配置 |

---

## 7. 监控运维体系

### 7.1 指标采集（Micrometer）

| 指标 | 类型 | 标签 | 说明 |
|------|------|------|------|
| `rag.requests` | Counter | success/failure | 请求计数 |
| `rag.latency` | Timer | endpoint | 端到端延迟 |
| `rag.llm.tokens` | Counter | direction=in/out | Token 消耗 |
| `rag.retrieval.results` | Gauge | strategy | 检索结果数 |

### 7.2 告警机制

`AlertService` 基于 `rag_slo_config` 配置阈值：
- 延迟告警：P95 > 阈值触发 WARNING
- 错误率告警：错误率 > 阈值触发 CRITICAL
- 静默期：同一告警 60 分钟内不重复触发

V57 还提供受管 API principal 到期条件：

- principal 创建、expiry policy 更新和 family revoke 在事务提交后发布 Spring Event，
  有界异步 worker 立即按 principal 权威重读并对账；
- 默认每小时的 Scheduled 扫描只恢复漏事件和时间跨阈值，按最久未检查优先并限制单轮
  候选数量，避免高频轮询数据库；
- PostgreSQL partial unique index、JPA version 与条件 DML/CAS 保证同一 principal 最多
  一条 active 告警，阶段按 `WARNING → CRITICAL → EXPIRED` 原行升级；
- 延期出窗口或吊销会自动解决；通知按独立 state/notified version claim，重复事件不会
  重复发起同一阶段通知；
- Alerts 管理面只允许 environment root、数据库 ADMIN、legacy static 和
  auth-disabled direct loopback，普通业务 principal 在查询前即被拒绝。

V58 把外部 Email/DingTalk 投递从进程内 best-effort 升级为 durable outbox：

- 告警事实与匹配 provider delivery 在同一事务提交；Spring Event 只在提交后低延迟唤醒
  有界 worker，默认一分钟 Scheduled 扫描只恢复漏事件、重启和过期 lease；
- 多实例使用唯一约束、lease token、条件更新/CAS 与有界退避竞争，不使用消息代理或
  悲观锁；provider 网络调用始终位于数据库事务外；
- delivery 状态为 `PENDING`、`IN_PROGRESS`、`RETRY_WAIT`、`DELIVERED`、`FAILED`
  或 `SUPERSEDED`。语义是 at-least-once，稳定 delivery UUID 用于解释极少量重复；
- managed phase 升级或解除会 supersede 尚未开始的旧版本；人工 retry 保留累计
  attempt 审计并在发送前再次核对 source alert；
- operator API 与 WebUI 只显示 provider、状态、attempt、低敏错误码/HTTP status 和时间。
  ledger 不保存 webhook/SMTP secret、收件人、业务 payload、provider 响应正文或堆栈。

### 7.3 A/B 实验

`AbTestService` 支持检索策略对比：
1. 定义实验（策略 A vs B + 流量分配比例）
2. 请求按比例分流执行不同策略
3. 收集结果（延迟、准确率、用户评分）
4. 统计分析效果差异

---

## 8. 关键设计决策

### 为什么用 Advisor 链而不是 Pipeline 模式？

**选择**：Spring AI `BaseAdvisor` 链式调用
**备选**：MaxKB4j 的 `PipelineManage + AbsStep` 模式
**理由**：
- Advisor 与 Spring AI 原生集成，无需额外抽象
- 通过 `Ordered` 接口控制执行顺序，声明式配置
- context attributes 机制足够传递中间结果
- 每个 Advisor 可独立测试、独立使用

### 为什么用双表对话记忆？

**选择**：`spring_ai_chat_memory` + `rag_chat_history` 共存
**理由**：
- JDBC Memory 只保留最近 N 条可恢复的 user/plain assistant 消息，给 LLM 上下文用
- 业务审计表保留完整历史、来源和有界工具交换投影，支持查询、摘要和分析
- 两个表职责分离，互不干扰

### 为什么嵌入模型与 Chat Model 配置分离？

**选择**：`siliconflow.*` 独立配置嵌入模型
**理由**：
- 嵌入模型和对话模型可能来自不同提供商
- 嵌入模型切换频率低（需重建所有向量），对话模型切换频繁
- 分离配置降低误操作风险

---

## 附录：技术栈

| 组件 | 技术 | 版本 |
|------|------|------|
| 运行时 | Java | 21+ (LTS, 虚拟线程) |
| 框架 | Spring Boot | 3.5.x |
| AI 框架 | Spring AI | 1.1.x |
| 主数据库 | PostgreSQL + pgvector | 42.7.x / 0.7.x |
| ORM | Spring Data JPA | 3.3.x |
| 迁移工具 | Flyway | 10.x |
| 构建工具 | Maven | 3.9.x |
| 嵌入模型 | BGE-M3（via SiliconFlow） | 1024 维 |
| 分词 | pg_jieba | — |
| 缓存 | Caffeine | 3.x |
| 监控 | Micrometer + Actuator | — |
| API 文档 | SpringDoc OpenAPI | 2.x |
