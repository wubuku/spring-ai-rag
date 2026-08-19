# RAG 服务下一批高价值功能规划

> **状态：等待用户 review，尚未授权实施。**
>
> 本文是规划文档，不是当前能力声明。当前代码、已跟踪长青文档和已提交迁移才是
> 当前事实来源。本文完成规定的规划审查后仍然不会自动进入实施，必须等待用户明确
> 放行。
>
> **规划日期**：2026-08-18  
> **代码基线**：`37454de`（`feat: add scoped OpenAI APIs and durable retrieval workflows`）  
> **Spring AI**：1.1.4  
> **当前 Flyway**：V1–V34  
> **范围**：只规划下一批真正高价值、可实施、可验证的能力；不修改生产代码。

## 1. 结论先行

上一批能力已经把项目从“有检索 Demo”推进到了较完整的基础闭环：

- Spring AI `RetrievalAugmentationAdvisor`、`ToolCallAdvisor`、查询扩展和标准
  `DocumentJoiner` 已进入生产 Chat 链路；
- `KNOWLEDGE`、`AGENT`、`PLAIN` 三种 Chat 模式已经明确；
- Collection 已进入写入、检索、OpenAI 兼容协议和 API Key allow-list；
- 普通外部文档同步、JSONB structured record、payload containment 和
  `searchJsonRecords` 工具已经存在；
- V33 已提供持久化 embedding job 的状态机、租约、重试、取消和幂等合并；
- 文件版真实检索回归和发布质量门禁已经存在；
- Chat memory、session lease、来源快照、SSE 和稳定 citation ID 已存在。

因此下一批最值得做的不是增加更多文件格式、再写一套 Agent 框架、继续堆 Prompt
正则，或把 Collection 固定到模型配置，而是把已经存在的底座变成四个可运营闭环：

| 优先级 | 功能 | 直接价值 | 推荐批次 | 预估体量 |
|---|---|---|---|---|
| P0 | 检索诊断与“为什么没有结果” | 把搜索/对话中的黑盒失败变成可定位事实，直接减少排障成本 | A | M |
| P0 | 普通文档安全 metadata 过滤 | 让已有 `rag_documents.metadata` 真正参与检索，支持来源、租户、版本等业务筛选 | B | M |
| P1 | 嵌入/重索引任务运营控制面 | 把 V33 从“可调用的队列”推进为可用于导入、重嵌入和日常运营的系统能力 | C | M–L |
| P1 | 受管质量套件与 citation 可信度 | 把质量回归、配置比较和回答引用正确性固定成可重复证据 | D | M–L |

### 1.1 加权排序

评分采用 1–5 分，分数越高越值得优先做。权重如下：

- 产品/用户价值：30%
- 当前缺口紧迫度：25%
- 对现有能力复用程度：20%
- 风险与实施成本：15%
- 对后续功能的杠杆：10%

| 功能 | 价值 | 缺口 | 复用 | 风险/成本 | 杠杆 | 加权分 |
|---|---:|---:|---:|---:|---:|---:|
| 检索诊断 | 5 | 5 | 4 | 4 | 5 | **4.65** |
| metadata 过滤 | 5 | 4 | 5 | 4 | 4 | **4.50** |
| 嵌入任务运营 | 4 | 4 | 5 | 3 | 5 | **4.15** |
| 质量套件与 citation | 4 | 4 | 4 | 3 | 5 | **3.95** |

这个排序的关键判断是：没有诊断和质量证据时，继续加功能会把失败原因留给人工猜测；
metadata 过滤和任务运营则分别补齐“能搜什么”和“数据是否准备好”这两个产品边界。

### 1.2 推荐实施顺序

1. **Batch A：检索诊断**，先建立统一的 trace 和失败原因模型。
2. **Batch B：metadata 过滤**，复用诊断中的 filter 描述和现有 JSONB SQL 下推模式。
3. **Batch C：嵌入任务运营**，把同步导入逐步接到 V33，不改变现有调用方默认行为。
4. **Batch D：质量套件与 citation**，使用前面三批提供的 trace、filter、job 状态作为质量证据。

每一批都必须先有后端集成测试和一键验证脚本，再进入下一批。单元测试或代码 review
不能替代端到端验收。

## 2. 明确不做与不重新发明

### 2.1 本批明确延后

以下项目不进入本规划的实现范围：

- API Key secret schema 加固、轮换治理、共享配额、计费和用量结算；
- XML、DOCX、PPTX、XLSX 等新文档格式；
- 外部连接器、同步调度平台；
- 固定默认 Collection；
- `EACH_COLLECTION` 覆盖召回；
- 每个 Collection 独立 EmbeddingModel；
- GraphRAG、实体链接、多模态检索；
- MCP 产品化、`/v1/responses` 和通用 Agent 编排；
- Kafka、Redis 或新的工作流引擎；
- 为“用户是否想检索”继续增加 Java 正则猜测。

这些项目不是永远不做，而是当前没有高于四个闭环的投入回报。尤其是固定默认
Collection 不解决真实调用方动态切库问题；当前请求级 scope 才是正确边界。

### 2.2 复用 Spring AI 和现有项目能力

实施时必须优先复用：

- Spring AI `ChatClient`、`RetrievalAugmentationAdvisor`、`ToolCallAdvisor`；
- Spring AI `QueryExpander`、`MultiQueryExpander`、标准 `DocumentJoiner`；
- 当前 `ProjectDocumentRetriever`、`ProjectRerankPostProcessor`；
- 当前 `KnowledgeSearchTool`、`JsonRecordSearchTool` 和服务端
  `AuthorizedRetrievalContext`；
- 当前 `HybridRetrieverService`、`RetrievalScopeSql`、Collection resolver 和 ACL；
- 当前 V33 `EmbeddingJobService`、`EmbeddingJobRepository`、worker 状态机；
- 当前 `RetrievalEvaluationService`、回归 runner、Spring AI 1.1.4 的
  `FactCheckingEvaluator` / `RelevancyEvaluator`。

项目自己的实现只负责真正有差异化价值的部分：混合检索、Collection/ACL 下推、
Embedding Profile freshness、JSONB 过滤、任务持久化、诊断解释和质量门禁。不得在
Controller、Prompt 或 Tool 内复制一套检索 SQL/Agent 编排。

## 3. 当前事实与代码锚点

### 3.1 模块与依赖边界

| 范围 | 当前事实 | 本规划约束 |
|---|---|---|
| `spring-ai-rag-api` | DTO、枚举和 SPI | 新请求字段保持增量兼容；内部实现类型不泄漏到 API |
| `spring-ai-rag-core` | Controller、Service、检索、Chat、Flyway、可运行应用 | 新能力的主要实现位置 |
| `spring-ai-rag-starter` | 自动配置和嵌入式集成 | 每批至少验证 core standalone 与 starter consumer |
| `spring-ai-rag-documents` | 清洗、分块、PDF 相关处理 | 不承担数据库 job 或 HTTP 协议职责 |
| `spring-ai-rag-webui` | React 管理台 | 只增加能降低运营成本的页面/标签，不做装饰性 UI |
| `demos` | basic/component/domain/multi-model | 只在 API 或 Starter 契约变化时适配 |

稳定入口：

- [项目上下文](../../project-context-zh-CN.md)
- [架构设计](../../architecture-zh-CN.md)
- [REST API](../../rest-api-zh-CN.md)
- [开发者参考](../../developer-reference-zh-CN.md)
- [测试指南](../../testing-guide-zh-CN.md)
- [上一批实施进度](2026-08-17_NEXT_MOST_WORTHWHILE_FEATURES_PROGRESS.md)

### 3.2 检索链现状

生产 Chat 的事实链是：

```text
RagChatController
  -> CollectionRetrievalScopeResolver
  -> ChatCommandMapper
  -> ChatExecutionService
     -> KNOWLEDGE: RetrievalAugmentationAdvisor
        -> ProjectDocumentRetriever
        -> ProjectRerankPostProcessor
     -> AGENT: ToolCallAdvisor/BudgetedToolCallAdvisor
        -> KnowledgeSearchTool / JsonRecordSearchTool
     -> PLAIN: ChatClient + Memory，不检索
  -> ChatSessionCoordinator
  -> history + source snapshot + JDBC Memory
```

`ProjectDocumentRetriever` 和工具都把查询交给 `HybridRetrieverService`。当前
`RetrievalTraceCollector` 只记录 effective query、retrieval call 数、tool round 数和
source count；它不记录向量/全文分支状态、timeout、融合前后数量、过滤条件或明确的空结果原因。

`HybridRetrieverService` 当前把向量和全文分支的异常/超时降级成空列表。这个恢复策略
有价值，但会丢失“哪一个分支失败”的解释。当前直接 Search 和生产 Chat 也没有统一持久化
这些细节。

### 3.3 已有检索日志和评估基础

- V3 已有 `rag_retrieval_logs`，包含 query、strategy、各阶段耗时、result count、
  result scores 和 metadata。
- `RetrievalLoggingService` 目前主要被 legacy `HybridSearchAdvisor` 使用；
 生产 Chat 和直接 Search 没有完整接入。
- V4 已有 `rag_retrieval_evaluations` 与 Precision@K、Recall@K、MRR、nDCG、
  Hit Rate 计算。
- `scripts/run-retrieval-regression.sh` 已能对稳定
  `collectionKey + externalId` fixture 调用真实 Search API，并生成 baseline；
  但还没有受管 suite/version/run 对象。
- Chat 来源使用稳定 `S1`、`S2` 等 citation ID，提示词要求回答引用；回答正文目前
  没有确定性引用合法性检查。

### 3.4 已有 metadata、JSONB 和 Scope 基础

- `RagDocument.metadata` 已是 JSONB，普通文档和 JSON record 都可携带它。
- V34 已为 `jsonb_payload` 增加 partial GIN `jsonb_path_ops` index，并以
  PostgreSQL `@>` 完成 payload containment。
- `RetrievalScopeSql.build(...)` 已把 Collection、document ID、document type 和
  payload filter 下推到向量/全文 SQL 的 `LIMIT` 之前。
- 三个全文 provider 都复用 `RetrievalScopeSql`；因此 metadata filter 应扩展这一层，
  不能在 Java 拿到 top-k 后再过滤。

### 3.5 已有 V33 embedding jobs 的边界

V33 `rag_embedding_jobs` 已有：

- `QUEUED/RUNNING/SUCCEEDED/FAILED/CANCELLED/STALE` 状态；
- `SKIP LOCKED` claim；
- lease owner/expiry；
- bounded retry；
- active job coalesce；
- cancel/retry API；
- `progress JSONB` 列。

当前缺口：

- 普通 external document、JSON record、PDF、upload、batch create 仍主要同步嵌入；
- worker claim 一批后串行处理，没有真正的有界 worker 并发；
- provider 调用期间没有 lease heartbeat；
- job list 先分页再用 Java 检查 Collection ACL，分页可能丢掉后续可见记录；
- WebUI 没有任务运营页，也没有 Collection readiness 摘要；
- 当前 API 没有统一的 `SYNC/ASYNC/SKIP` 语义。

## 4. 总体架构与数据迁移顺序

```text
Batch A  Retrieval Trace
    -> 统一 retrieval outcome、branch trace、empty reason、trace API
    -> V35 扩展现有 rag_retrieval_logs

Batch B  Metadata Filter
    -> 固定字段 JSONB containment
    -> RetrievalFilters + RetrievalScopeSql
    -> V36 metadata partial GIN

Batch C  Embedding Operations
    -> SYNC/ASYNC/SKIP dispatcher
    -> V33 queue integration + worker heartbeat/concurrency
    -> V37 job origin/audit fields and indexes

Batch D  Quality + Citation
    -> immutable suite/version/run + deterministic citation validation
    -> V38 managed quality tables
    -> optional Spring AI semantic evaluators
```

迁移必须 forward-only：

1. `V35__extend_retrieval_diagnostics.sql`
2. `V36__add_document_metadata_containment_index.sql`
3. `V37__extend_embedding_job_operations.sql`
4. `V38__add_managed_evaluation_suites.sql`

禁止改写 V1–V34，禁止通过 Hibernate 自动建表替代 Flyway。若某一批被取消，已执行
迁移保持向后兼容，功能通过配置关闭；不要求立即提供 down migration。

## 5. Batch A：检索诊断与“为什么没有结果”

### 5.1 产品目标

用户在 Search 或 Chat 中看到空结果时，系统应能回答以下可验证问题：

- 本次实际使用的 query/expanded query 是什么；
- 生效的 Collection、document 和 filter 范围是什么；
- 向量分支和全文分支是否执行、使用了什么 provider、返回多少候选；
- 是否发生 timeout、provider error、rerank degradation 或 budget exhaustion；
- 空结果是授权范围为空、候选为空、最低分过滤、分支失败，还是确实没有命中；
- 这次 trace 是否与 Chat 的 tool calls、来源和 HTTP 请求关联。

诊断只记录检索元数据，不记录 chunk 正文、JSON payload、完整 Prompt、API Key 或
模型原始响应。

### 5.2 推荐内部模型

保留现有 list-returning API，新增内部详细结果模型，例如：

```text
RetrievalOutcome
  traceId
  results
  originalQuery
  effectiveQueries[]
  scopeSummary
  filterSummary
  branchStages[]
  fusionStage
  rerankStage
  outcomeCode
  emptyReasonCode
  elapsedMs
```

现有 `search(...)` / `searchInScope(...)` 方法继续返回 `List<RetrievalResult>`，内部
委托详细方法后只取 `results`，避免破坏 Starter、demo 和既有 Java 调用方。

`ProjectDocumentRetriever`、`KnowledgeSearchTool`、`JsonRecordSearchTool`、直接
Search Controller 都使用同一个详细结果入口。新增 server-owned
`RetrievalTraceSession`，由项目 Chat mapper/OpenAI mapper 或直接 Search Controller
在调用执行服务前创建，并附着到内部 `ChatCommand`/retrieval execution spec。这样
Controller 在订阅流之前已经知道 trace ID，可以可靠设置响应头。

owner 身份必须复用现有 `ChatPrincipal` 的规范化 ID（包括 database key、legacy static、
environment root 和 local 的既有命名空间），直接 Search、项目 Chat 和 OpenAI transport
不得各自拼接 principal 字符串。V37 的 `requested_by_principal_id` 和 V38 的 owner 字段
也复用同一个解析器，避免不同 credential 类型发生 ID 碰撞。

一次 Chat turn 使用一个父 trace ID；每个 model candidate/fallback attempt 使用独立
attempt segment，AGENT 多次 tool call 再记录为该 attempt 下的 bounded stage。当前
`ModeAwareChatClientFactory` 会为每个 attempt 新建 collector，实施时必须让 collector
引用父 session 并保留失败 attempt；不能只保存最终成功 attempt，也不能为每个 tool call
创建不可关联的孤立记录。流式 fallback 也遵循相同规则。V35 首版每个父 trace 只写一条
`rag_retrieval_logs` 记录；attempt、tool call 和 branch stage 作为有界、版本化数组写入
该行的 `metadata JSONB`，不为子段复用父 `trace_id` 创建多行。

### 5.3 可观察字段和确定性原因

#### Scope/filter 摘要

只保存服务器已经解析和授权后的摘要：

- `collectionScopeMode`；
- 有效 Collection 数量；只有显式 `SELECTED_COLLECTIONS` 才保存已经授权的稳定 key
  （上限沿用 100），`CALLER_VISIBLE`/`ANY_COLLECTION` 不枚举全库 key；
- document ID 数量；
- `metadataContains` / `payloadContains` 是否存在、canonical JSON 字节数和顶层 key
  数量，不保存 filter 值或可逆 fingerprint；
- `documentType`；
- 当前 Embedding Profile key。

#### Branch stage

每个阶段只保存：

- `branch`: `VECTOR`、`FULLTEXT`、`FUSION`、`RERANK`；
- provider 名称；
- `status`: `SUCCESS`、`DISABLED`、`UNAVAILABLE`、`TIMEOUT`、`ERROR`；
- elapsed milliseconds；
- candidate/result counts；
- normalized error code，不保存 stack trace 和 provider 原始响应。

#### Reason code

只使用能由事实支撑的代码。推荐首版集合：

| 代码 | 触发条件 |
|---|---|
| `RESULTS_RETURNED` | 最终有结果 |
| `SCOPE_MATCH_NONE` | 服务端 scope 明确为 match-none |
| `NO_ELIGIBLE_DOCUMENTS` | 空结果诊断探针确认 scope/filter 下无 enabled 文档 |
| `NO_FRESH_EMBEDDINGS` | 探针确认有 enabled 文档但没有当前 Profile 的 fresh completed state |
| `NO_CANDIDATES` | scope 有可检索文档，但两个分支均未返回候选 |
| `BELOW_MIN_SCORE` | 至少有候选，但全部在可观测阶段被 min-score 排除 |
| `VECTOR_TIMEOUT` / `FULLTEXT_TIMEOUT` | 对应分支超时且另一个分支无有效结果 |
| `VECTOR_ERROR` / `FULLTEXT_ERROR` | 对应分支失败且另一个分支无有效结果 |
| `PARTIAL_VECTOR` / `PARTIAL_FULLTEXT` | 只有一个分支成功并产生结果 |
| `RERANK_DEGRADED` | rerank 失败后使用了原排序结果 |
| `RETRIEVAL_BUDGET_EXHAUSTED` | AGENT retrieval/tool budget 阻止了调用 |
| `DIAGNOSTIC_UNKNOWN` | 诊断探针自身超时/失败，避免编造原因 |

`NO_FRESH_EMBEDDINGS` 和 `NO_ELIGIBLE_DOCUMENTS` 只允许在空结果时执行一个有界、
只读的计数探针后产生；不能仅凭“结果为空”猜测。探针失败必须回退到
`DIAGNOSTIC_UNKNOWN` 或 `NO_CANDIDATES`。

### 5.4 V35 设计

扩展现有 `rag_retrieval_logs`，不创建第二套 retrieval log 表：

```sql
ALTER TABLE rag_retrieval_logs
    ADD COLUMN trace_id UUID,
    ADD COLUMN owner_principal_id VARCHAR(128),
    ADD COLUMN operation VARCHAR(32),
    ADD COLUMN outcome_code VARCHAR(64),
    ADD COLUMN empty_reason_code VARCHAR(64);

CREATE UNIQUE INDEX ... ON rag_retrieval_logs(trace_id)
    WHERE trace_id IS NOT NULL;

CREATE INDEX ... ON rag_retrieval_logs(owner_principal_id, created_at DESC);
CREATE INDEX ... ON rag_retrieval_logs(operation, created_at DESC);
```

完整 stage/scope/filter 快照复用已有 `metadata JSONB`，并由一个明确的
`schemaVersion`（首版为 `1`）包裹。这样不会把一套不断变化的诊断字段硬编码成几十个
迁移列，同时 trace API 可以只读需要的结构。

新生产路径不在 `result_scores` 中保存 document ID。若保留 score 摘要，只使用
`rank_1`、`rank_2` 等位置键并限制最多 20 项；trace API 不返回 legacy 行的
document-ID-to-score map。这样即使 API Key 的 Collection ACL 后续收窄，也不会通过
历史 trace 泄漏已不可见的文档身份。

旧 V3 记录的新增字段保持 NULL；旧 `HybridSearchAdvisor` 日志继续可读，但不自动伪造
owner 或 trace ID。

### 5.5 HTTP/API 设计

新增只读端点：

```text
GET /api/v1/rag/retrieval-traces
GET /api/v1/rag/retrieval-traces/{traceId}
```

列表参数：

- `page`、`size`，size 最大 100；
- `operation`；
- `outcomeCode`；
- `emptyReasonCode`；
- `sessionId`。

端点只返回当前认证 principal 的 trace。第一版不提供“管理员查看所有 principal”
的隐式越权能力；若未来需要集中运维，应另行设计明确的 admin audit scope。
读取 trace detail 时还要重新解析 metadata 中显式保存的 Collection keys，并按当前
Collection ACL 过滤；权限已收窄的 key 不再返回，且不能通过错误差异探测不可见
Collection。只有 Collection count、scope mode 等非身份摘要可原样保留。

Search response body 保持不变，增加 `X-RAG-Retrieval-Trace-Id` 响应头。项目 Chat
非流式响应在 metadata 中增加 `retrievalTraceId`，同时返回同名响应头。项目流式 Chat
在首个事件前设置响应头，并在 `done` 事件 metadata 中重复该 ID。

OpenAI Chat Completions 当前 response/chunk DTO 没有通用 metadata 扩展，因此首版只在
HTTP 响应头返回 `X-RAG-Retrieval-Trace-Id`，不向标准 JSON/SSE chunk 私自增加字段。
若以后要增加 `rag` response extension，应作为独立兼容契约设计并更新 readiness 文档。

### 5.6 配置与隐私

推荐配置：

```yaml
rag:
  retrieval-diagnostics:
    enabled: true
    persist: true
    retention-days: 7
    store-query-text: false
    max-detail-bytes: 32768
```

默认不持久化 query/effective query 明文；trace 只记录 query 次数、字符数和 stage。
由于 V3 的 `query` 列当前是 `NOT NULL`，新诊断记录在
`store-query-text=false` 时明确写入固定占位值 `[redacted]`。默认不保存普通 SHA-256
query fingerprint，因为低熵查询可被字典枚举；trace ID 已足够关联一次请求。开发者需要
人工对照查询时，可以显式打开 `store-query-text`，但该设置必须在配置文档中标注隐私
代价。现有 legacy log 的 query 行为不在本批悄悄改变。

清理任务按 created_at 分批删除过期记录，失败不得影响检索请求。诊断写入失败只记录
指标和日志，不能让 Search/Chat 失败。

### 5.7 验收

后端必须覆盖：

- vector/full-text 都成功；
- vector timeout、full-text timeout、单分支异常和双分支异常；
- match-none scope fail closed；
- 无 fresh embedding 的空结果探针；
- min-score 导致空结果；
- AGENT 重复 query、budget exhaustion 和多 tool trace；
- 旧 list API 和 Starter consumer 不回归；
- 不保存 chunk、payload、Prompt、Token；
- principal A 不能读取 principal B 的 trace；
- trace retention 和写入失败不影响结果。

PostgreSQL 集成测试必须从 V1–V35 迁移，在真实数据库中验证 JSONB metadata、
索引、trace list/detail 和 owner predicate。

## 6. Batch B：普通文档安全 metadata 过滤

### 6.1 产品目标与边界

让调用者在检索普通文档时按业务 metadata 收窄范围，例如：

```json
{
  "filters": {
    "metadataContains": {
      "tenant": "acme",
      "language": "zh-CN",
      "documentClass": "policy"
    }
  }
}
```

这是**候选资格过滤**，不是排序提示，也不是把 metadata 塞进 Prompt。metadata 过滤必须
和 Collection、API Key ACL、documentIds、enabled、Embedding Profile freshness
同时生效。

首版只实现固定 JSONB containment：

- `metadataContains`：对 `rag_documents.metadata` 使用 PostgreSQL `@>`；
- `payloadContains`：对 JSON record 的 `rag_documents.jsonb_payload` 使用已有 `@>`；
- 两个条件同时提供时取 AND；
- 不支持 SQL、JSONPath、regex、动态字段排序、任意表达式或自定义查询语言。

PostgreSQL 数组和嵌套对象沿用 `jsonb @>` 的 containment 语义，文档必须明确这不是
“字符串包含”也不是所有数组元素的任意匹配。

### 6.2 API 契约

在 `spring-ai-rag-api` 增加可复用的 `RetrievalFilterRequest`：

```text
metadataContains: JsonNode|null
payloadContains: JsonNode|null
```

接入位置：

| API | 处理 |
|---|---|
| `POST /api/v1/rag/search` | 支持 `filters.metadataContains` 和 `filters.payloadContains` |
| `POST /api/v1/rag/chat/ask` / stream | 支持同一 `filters`，进入服务端 ChatCommand |
| JSON record search | 保留现有顶层 `payloadContains`；增加 `metadataContains`；若未来增加 `filters`，与旧字段冲突时 400 |
| OpenAI `/v1/chat/completions` | 支持 `rag.filters.metadata_contains` / `payload_contains` |
| Search GET | 首版不增加 JSON query-string filter，避免不明确的编码和大小限制 |

OpenAI 的 filter 只是一种 transport mapping，最终必须构造成同一个内部
`RetrievalFilters`，不能在 OpenAI Controller 复制查询逻辑。

### 6.3 Agent 安全语义

调用者传入的 filter 进入不可变 `AuthorizedRetrievalContext`，模型不能替换、清空或
扩大它：

- `searchKnowledge` schema 不暴露 metadata/payload filter；
- `searchJsonRecords` 已有的 `payloadContains` 参数最多作为额外的更窄条件；
- 调用者 filter 与模型 filter 必须取 AND；
- 模型不能提供 Collection、document ID、SQL、JSONPath 或 principal；
- PLAIN 模式拒绝任何 retrieval filter；
- 未授权/未知 Collection 仍由原有 resolver/ACL 处理。

### 6.4 校验与 SQL 设计

抽取一个共享 validator，使用 Jackson 树而非字符串拼接：

- 顶层必须是非空 JSON object；
- 单个 filter canonical JSON 最大 16 KiB；
- 最大嵌套深度 8；
- 总 filter 字节数最大 32 KiB；
- canonical serialization 用于绑定参数、trace 摘要和测试；
- canonical JSON 必须递归按 object key 排序、保留 array 顺序，并以 UTF-8 无多余空白
  序列化；不得依赖调用者字段顺序或普通 `Map.toString()`；
- 非法 JSON、空 object、超限、过深结构返回 400；
- filter 内容永远作为 JDBC 参数绑定。

内部模型推荐：

```text
RetrievalFilters
  metadataContains: JsonbContainmentFilter?
  payloadContainsAll: List<JsonbContainmentFilter>
```

公开请求仍只接收一个 `payloadContains`；列表仅用于内部保留“调用者条件”和
`searchJsonRecords` tool 条件两个独立 conjunct。禁止把两个 JSON object 直接 merge，
因为相同 key 的不同值无法通过 merge 正确表达 AND。

`RetrievalScopeSql.build(scope, filters)` 生成固定 SQL 片段：

```sql
AND d.metadata @> CAST(? AS jsonb)
AND d.jsonb_payload @> CAST(? AS jsonb)
AND d.jsonb_payload @> CAST(? AS jsonb) -- tool 额外条件存在时
```

`payloadContainsAll` 中每个元素各生成一个参数绑定 predicate。所有条件必须在向量/
全文 SQL 的 `ORDER BY ... LIMIT` 之前生效。三个全文 provider、vector search、rerank
前的候选召回都必须使用同一过滤片段。

V36 增加：

```sql
CREATE INDEX idx_rag_documents_metadata_path_ops
    ON rag_documents USING GIN (metadata jsonb_path_ops)
    WHERE enabled = true AND metadata IS NOT NULL;
```

实施前需以 PostgreSQL 集成测试和 `EXPLAIN` 确认索引可被合理选择；小数据量下 planner
选择普通索引不算产品失败，测试应使用足够的低选择性噪声数据。

### 6.5 验收

- metadata 顶层、嵌套对象和数组 containment；
- 不命中时返回空且不扩大范围；
- filter 与 Collection、documentIds 的交集；
- filter 不绕过 disabled/freshness；
- vector、pg_trgm、English FTS、Jieba FTS 都在 LIMIT 前过滤；
- Search、KNOWLEDGE、AGENT、JSON record 和 OpenAI transport 行为一致；
- Tool filter 只能收窄，不能扩大；
- 不支持字段、SQL/JSONPath、非法 JSON 返回 400；
- V36 GIN planner 证据；
- metadata 过滤不进入普通 RAG Prompt，payload 仍不自动进入普通 Chat 上下文。

## 7. Batch C：嵌入/重索引任务运营控制面

### 7.1 统一 Embedding Policy

新增公开枚举：

```text
SYNC   # 持久化后在当前请求中嵌入
ASYNC  # 持久化后创建 V33 durable job，返回 job identity
SKIP   # 只持久化文档，不创建/执行嵌入
```

一般 DTO 的兼容规则：

1. 新 `embeddingPolicy` 被提供时，它是权威字段；
2. 未提供时，完全保持各旧 endpoint 的 `embed` 默认值和行为；
3. 旧 `embed=true` 等价于 `SYNC`，`embed=false` 等价于 `SKIP`；
4. 不因为新配置默认值而偷偷把已有同步调用改成异步；
5. 明确请求 `ASYNC` 但 `rag.embedding-jobs.enabled=false` 时返回明确错误
   `EMBEDDING_JOBS_DISABLED`，不能静默退回同步；
6. `ASYNC` 仍需在文档持久化事务提交后对外可见，job snapshot 使用 document version、
   content hash 和 active profile；
7. payload/metadata/title-only 更新不创建新的 provider embedding job，因为 content
   hash 没变；如果同一 document/profile/hash 已有 active job，必须原子更新该 job 的
   `document_version` snapshot，使它不会因非内容版本变化被错误标为 STALE。

PDF 端点需要额外保持当前两套既有语义：

- `/files/pdf-to-rag` 的 legacy `embed=true|false` 继续分别映射为
  `SYNC + SSE progress` 和 `SKIP + JSON`；
- `/files/{uuid}/embed` 的 legacy `embed=sync|sse` 是响应传输方式，不是是否嵌入；
  该参数继续只控制同步 JSON 或同步 SSE；
- 新 `embeddingPolicy=ASYNC` 时返回 JSON job identity，不打开 SSE；若同时请求
  `embed=sse`，返回 400 说明异步任务应通过 job API 观察；
- `/files/{uuid}/embed` 是显式嵌入端点，因此 `embeddingPolicy=SKIP` 无意义并返回 400；
- 其他显式 re-embed/reindex 端点同样只接受 `SYNC` 或 `ASYNC`，`SKIP` 返回 400；
- 不重命名或复用 legacy `embed` 参数承载第三种含义。

接入范围：

- external document upsert / batch upsert；
- JSON record upsert；
- PDF-to-RAG；
- 文本文件 upload；
- batch document create；
- 现有显式 re-embed/reindex 入口。

新增响应字段保持增量：

```text
embeddingAction: SYNC_COMPLETED | SYNC_CACHED | ASYNC_QUEUED |
                 ASYNC_COALESCED | SKIPPED
embeddingJobId?: UUID
embeddingBatchId?: UUID
```

现有 `embeddingStatus`、HTTP status 和旧字段继续可用。ASYNC 不改变文档已经持久化的
成功语义，只表示向量稍后完成。

### 7.2 Dispatcher 与事务边界

新增内部 `EmbeddingDispatchService`：

```text
persist document
  -> resolve policy
  -> SYNC: reuse DocumentEmbedService
  -> ASYNC: enqueue V33 job in same DB transaction
  -> SKIP: return without job
```

当前 external document/JSON record 会先在短事务中完成 identity/CAS/version 决策，再
在事务外同步调用 provider。ASYNC 不能在现有 `persist(...)` 返回并提交后才补写 job：
实现时应把 policy 传入短事务，在 document save/version 和 job enqueue 之间保持同一
数据库事务；SYNC 仍在事务提交后调用 provider。

job 必须保存：

- document ID；
- active embedding profile；
- document version；
- content hash；
- force；
- origin endpoint；
- requesting principal ID（不保存 API secret）。

worker 只有在事务提交后才能 claim。若文档事务回滚，job 也必须回滚。若 embedding
provider 失败，使用 V33 retry/backoff，不阻塞其他文档。

### 7.3 V37 与 worker 改造

V37 只增加运营所需的审计字段和查询索引，不另建队列表：

```sql
ALTER TABLE rag_embedding_jobs
    ADD COLUMN origin VARCHAR(32),
    ADD COLUMN requested_by_principal_id VARCHAR(128);

CREATE INDEX idx_rag_embedding_job_status_created
    ON rag_embedding_jobs(status, created_at DESC);
```

现有 job 行允许 NULL。`progress JSONB` 已经存在，改造 response/worker 以输出有限、
不含文本的阶段信息，例如 `CLAIMED`、`EMBEDDING`、`COMMITTING`、`RETRY_WAIT` 和
chunk count。

worker 必须满足：

- 使用有界 worker concurrency；
- claim 数不超过可用 worker 槽位，避免刚 claim 就在本地排队导致 lease 过期；
- provider 调用期间按 lease 的 1/3 周期 heartbeat；
- heartbeat 失败、取消、Profile 变化、document version/hash 变化都使 commit guard
  失效；
- 保留 V33 的 coalesce、SKIP LOCKED、retry、cancel、STALE 语义；
- 应用关闭时停止 claim，等待有界时间后释放本地 worker；
- 失败信息继续使用已有 sensitive-data masking 和长度限制。

### 7.4 ACL 与列表一致性

当前 job list 在 SQL 分页后用 Java 过滤可见文档，这会造成受限调用者看到少于 page
size 的结果并漏掉后续可见 job。改造要求：

- 在 SQL 中 join `rag_documents`；
- 对受限 API Key 在 `LIMIT/OFFSET` 前加入有效 Collection allow-list predicate；
- 对 unrestricted/admin 保持现有全量语义；
- job detail/cancel/retry 继续执行 document ACL；
- 返回总数时也使用相同 predicate；
- 不用 job 的 `requested_by_principal_id` 代替文档/Collection ACL。

现有 job create/detail/cancel/retry 路径保持不变；list 增加 `collectionKey` 可选参数并
改为稳定分页信封：

```text
GET /api/v1/rag/embedding-jobs
  ?batchId=&status=&collectionKey=&page=0&size=50

response:
  items[]
  page
  size
  totalElements
  totalPages
```

`size` 最大 200。`collectionKey` 先通过当前 principal 的 Collection resolver，再进入
SQL predicate；未知或未授权 key 遵循现有 anti-enumeration 语义。

### 7.5 Readiness 和 WebUI

新增只读 endpoint：

```text
GET /api/v1/rag/collections/embedding-readiness?collectionKey={collectionKey}
```

它复用现有 by-key Collection resolver/ACL/anti-enumeration 语义，至少返回：

```text
collectionKey
activeEmbeddingProfileKey
enabledDocuments
freshDocuments
queuedDocuments
runningDocuments
failedDocuments
staleOrMissingDocuments
```

分类必须互斥并可加总；fresh 的定义必须复用当前
`rag_document_embedding_state` + active Profile + content hash + chunk_count 条件。
首版分类优先级固定为：

1. fresh completed state；
2. 当前 profile/hash 的 RUNNING active job；
3. 当前 profile/hash 的 QUEUED active job；
4. 当前 profile/hash 的 FAILED embedding state，或当前 profile/hash 的最新 terminal
   job 为 FAILED；
5. 其余归入 stale/missing。

`enabledDocuments` 必须等于上述五类之和；disabled 文档单独统计或不进入该总数，不能
在多个状态中重复计数。

WebUI 增加一个面向运营的 Embeddings/Operations 页面，提供：

- job 列表、状态、batch、document、origin、attempt、progress、last error；
- status/batch/Collection 筛选；
- detail、cancel、retry；
- Collection readiness；
- 从 Documents/Collections 页面跳转到对应 job；
- ASYNC 导入后的 job 链接。

不要新建“看起来有进度但不能操作”的卡片；每个状态都要有对应 API、empty/loading/
error 状态和可访问名称。前端只通过 DOM、网络响应、JSON 和断言验证，不使用截图。

### 7.6 验收

- 每个 ingestion endpoint 的 SYNC/ASYNC/SKIP；
- 旧 embed 默认行为；
- async job 与 document 事务一致性；
- active job coalesce、force upgrade、retry、cancel、stale；
- provider 调用超过 lease 时 heartbeat 与 commit fence；
- 双 worker 并发无重复 commit；
- ACL 在 SQL 分页前生效；
- readiness counts 与数据库只读查询一致；
- WebUI list/detail/cancel/retry 和 error states；
- V1–V37 PostgreSQL 集成测试；
- 真实 embedding provider 至少执行一条 ASYNC smoke，成本可控。

## 8. Batch D：受管质量套件与 citation 可信度

Batch D 分成三个互相独立但共享数据的子批次：

- D1：确定性 citation 合法性检查，低风险、默认可启用；
- D2：immutable quality suite/version/run，支持配置比较；
- D3：可选 Spring AI 语义 evaluator，不作为默认阻断门禁。

### 8.1 D1：确定性 citation validation

回答完成后，对受控 citation token 做协议级解析。当前 citation ID 是服务端分配的
`S1`、`S2`，因此解析引用属于协议校验，不是猜测用户意图的自然语言正则。

输出结构：

```text
citationValidation:
  status: NOT_APPLICABLE | VALID | MISSING_CITATION | INVALID_CITATION | PARTIAL
  availableIds: ["S1", "S2"]
  citedIds: ["S1"]
  invalidIds: ["S9"]
  citedSourceCount: 1
  sourceCount: 2
```

规则：

- PLAIN 始终为 `NOT_APPLICABLE`；
- KNOWLEDGE/AGENT 没有来源且回答也没有 citation token 时为 `NOT_APPLICABLE`；
- KNOWLEDGE/AGENT 没有来源但回答出现 `[Sx]` 时为 `INVALID_CITATION`；
- 有来源但没有一个合法 citation 为 `MISSING_CITATION`；
- 引用了不存在的 ID 为 `INVALID_CITATION`；
- 同时有合法和非法 ID 为 `PARTIAL`；
- 全部引用合法且至少一条为 `VALID`；
- 首版只解析约定的 `[S1]` 形式及连续 token，不对自然语言句子做 claim coverage
  推断；
- 默认只警告，不修改答案、不自动重试、不把回答变成 HTTP 失败；
- 将 validation 放入 Chat response metadata、SSE `done` metadata、history snapshot
  和 trace metadata；
- WebUI 在 sources 附近显示清晰的“引用校验状态”，不要显示没有解释的分数。

### 8.2 D2：immutable suite/version/run

当前文件版 `testdata/regression/retrieval-core-v1.json` 继续作为 Git 中的 smoke/
release fixture。新增受管 suite 是它的运行时补充，不替代文件门禁。

V38 推荐四张表：

```text
rag_evaluation_suites
  id UUID
  suite_key
  name
  owner_principal_id
  created_at
  UNIQUE(owner_principal_id, suite_key)

rag_evaluation_suite_versions
  id UUID
  suite_id
  version INTEGER
  definition JSONB
  definition_sha256
  created_at
  UNIQUE(suite_id, version)
  UNIQUE(suite_id, definition_sha256)

rag_evaluation_runs
  id UUID
  suite_version_id
  owner_principal_id
  status
  configuration_snapshot JSONB
  code_revision
  embedding_profile_key
  aggregate_metrics JSONB
  lease_owner
  lease_expires_at
  started_at / finished_at / error

rag_evaluation_case_results
  run_id
  variant_key
  case_id
  status
  retrieved_identities JSONB
  metrics JSONB
  latency_ms
  trace_id
  error_code
  PRIMARY KEY(run_id, variant_key, case_id)
```

所有 owner、suite/version/run 外键和 status/check constraints 必须由 V38 明确定义；
`suite_key` 只在 owner principal 内唯一，避免不同调用方互相占用 key 或通过冲突探测
存在性。suite version 的 `definition` 一经创建不可修改。`definition_sha256` 使用与
filter 相同的 canonical JSON 规则：递归排序 object key、保留 array 顺序、UTF-8
无多余空白；这样同一逻辑定义不会因字段顺序不同产生不同 checksum。

首版 managed suite 为了可复现，case scope 必须使用
`SELECTED_COLLECTIONS + collectionKeys`：

- 不接受 `CALLER_VISIBLE`、`ANY_COLLECTION`；
- 不接受长期 numeric collection IDs；
- 每次 create version 和 run 都重新解析 key 并执行当前 ACL；
- definition 保存 stable keys，不保存内部 ID。

相关文档身份首版统一使用 `collectionKey + externalId`。没有 externalId 的手工文档应先
通过 external-document upsert 获得稳定身份，或只留在 Git fixture runner 中；不把
`source`/title 当成受管生产 suite 的长期身份。

case 以稳定身份表示：

1. JSON/external document：`collectionKey + externalId`；
2. title/source 只允许 Git 中的测试 fixture runner 使用；
3. 绝不把数据库自增 document ID 写成长期基准。

case 至少包含：

```json
{
  "id": "exact-sofa",
  "query": "破皮沙发",
  "scope": {
    "mode": "SELECTED_COLLECTIONS",
    "collectionKeys": ["furniture"]
  },
  "relevant": [
    {"collectionKey": "furniture", "externalId": "sofa-001"}
  ],
  "minimum": {"hitRate": 1.0, "mrr": 0.5}
}
```

run 支持最多 4 个 bounded `variant`，每个 variant 是固定的
`RetrievalConfig` 和 metadata/payload filter snapshot。首版只比较直接 Search
能够按请求表达的 maxResults、minScore、hybrid、rerank、weights 和 filters，不宣称
比较 query expansion；后者属于 Chat pipeline 配置，应在未来单独的 Chat quality
suite 中设计。所有 variant 都不改变生产默认值。

成本与负载默认边界：

- 每个 suite version 最多 200 cases；
- 每个 run 最多 4 variants，即最多 800 次 retrieval case execution；
- `max-concurrent-runs=1`，单个 run 内并发默认 4 且可配置上限 8；
- 超限在创建 version/run 时返回 400，不在后台静默截断。

run 必须记录：

- suite/version/checksum；
- variant configuration；
- active embedding profile；
- git revision；
- 每个参与 Collection 的 enabled document count 和 `MAX(updated_at)` 快照；
- retrieved stable identities；
- Precision@K、Recall@K、MRR、nDCG、Hit Rate；
- latency；
- trace ID；
- missing fixture、provider、database、embedding 和 authorization 错误。

缺失 fixture、provider 不可用或数据库错误必须使持久化 run 为 `FAILED`/`SKIPPED`；
CLI/一键验证脚本必须退出非零。HTTP 查询 run 只返回明确状态，不把“没有执行”算成质量
通过。

### 8.3 Suite API 和授权

推荐端点：

```text
POST /api/v1/rag/evaluation/suites
GET  /api/v1/rag/evaluation/suites
GET  /api/v1/rag/evaluation/suites/{suiteKey}
POST /api/v1/rag/evaluation/suites/{suiteKey}/versions
POST /api/v1/rag/evaluation/runs
GET  /api/v1/rag/evaluation/runs/{runId}
GET  /api/v1/rag/evaluation/runs/compare?leftRunId={left}&rightRunId={right}
```

第一版 suite/run 只允许创建者 principal 读取和操作；每次创建 version、启动 run
和读取结果都重新执行 Collection ACL，不能因为 suite 过去有权限就绕过当前权限。
`owner_principal_id` 是隔离维度，不是 Collection ACL 的替代品。

compare 只接受同一 suite version 且具有相同 variant keys 的两个 run，否则返回 400。
响应必须并列显示 code revision、Embedding Profile 和 Collection snapshot 是否一致；
任一环境项不同就标记为 `ENVIRONMENT_DRIFT`，不能把指标差异宣称为纯配置增益。run
执行前后也各取一次 Collection snapshot；执行期间发生变化时 run 标为
`CORPUS_CHANGED`，不产出“通过”结论。

run 首版使用 bounded application worker，不引入新的外部队列。worker 使用
`FOR UPDATE SKIP LOCKED` claim、有限租约和 heartbeat；只有 lease 已过期的 `RUNNING`
run 才能被恢复流程标为 `FAILED`，error code 为 `RUN_INTERRUPTED`。应用启动不得无条件
终止其他实例仍在 heartbeat 的 run。首版不自动重跑，避免重复模型成本；调用者可显式
基于同一 immutable version 创建新 run。

### 8.4 D3：Spring AI 语义 evaluator

Spring AI 1.1.4 已提供：

- `FactCheckingEvaluator`：回答是否被上下文支持；
- `RelevancyEvaluator`：回答是否与 query/context 相关；
- `EvaluationRequest` / `EvaluationResponse`。

规划只做一个薄适配层，把这些 evaluator 作为**显式选择的离线指标**：

- D3 不加入首版 retrieval suite run，因为后者没有 answer/context；
- 复用现有 `AnswerQualityRequest` 的 query/context/answer 输入边界，新增独立的
  `POST /api/v1/rag/evaluation/semantic` 和
  `POST /api/v1/rag/evaluation/semantic/batch` endpoint/adapter；现有
  `/answer-quality` 自定义 judge 契约保持可用，不在本批静默改写响应；
- 默认不调用，避免每个检索请求增加 LLM 成本；
- 运行时必须指定 evaluator model/provider；
- 单次语义评估批次最多 50 items，并按规范化
  `(query, context, answer, evaluator, model)` tuple 去重；
- evaluator timeout/error 单独记录，不与 deterministic retrieval metrics 混为一谈；
- 不把 LLM judge 作为第一道发布阻断门禁；
- 不再自造“句子覆盖率”算法冒充 groundedness。

### 8.5 WebUI

在现有 Evaluation 页面增加可寻址 tabs：

- `suites`：suite、version、checksum、owner；
- `runs`：status、variant、aggregate metrics、delta；
- `citations`：最近 citation validation 状态；
- 保留现有 report/history/feedback/judge。

`citations` tab 不新建平行存储。D1 完成时在 retrieval trace list 增加可选
`citationStatus` filter，并在 list item 返回 citation validation 摘要；页面通过现有
trace detail link 下钻。只有当前 principal 且当前 Collection ACL 仍允许的 trace 才能
出现。

UI 必须支持：

- 创建/导入 suite version；
- 启动 bounded run；
- 查看 case failure 和 trace link；
- 比较两个同 suite version 的 run；
- 显示“未执行/失败/跳过”与“通过”不同状态。

不把 MRR/nDCG、citation 状态或 source rank 渲染成概率；所有标签必须说明指标含义。

### 8.6 验收

- version immutable 和 checksum 稳定；
- stable identity 解析、missing fixture、ACL 变化；
- baseline/minimum/max regression；
- 多 variant 比较不改变生产默认；
- run 中断恢复/失败；
- citation valid/missing/invalid/partial；
- history 与 SSE metadata 保留 validation；
- Spring AI evaluator 的真实调用、timeout、disabled；
- WebUI 只使用 DOM/网络/JSON/断言；
- V1–V38 PostgreSQL 集成测试；
- 质量脚本失败时退出非零，不能假绿。

## 9. 一键验证和硬门槛

实施时不得把“最后手动点一下”作为主要验收方式。推荐新增一个聚合脚本：

```text
scripts/verify-next-high-value-features.sh
```

它按固定顺序调用以下专项脚本：

```text
scripts/verify-retrieval-diagnostics.sh
scripts/verify-retrieval-filters.sh
scripts/verify-embedding-operations.sh
scripts/verify-managed-quality.sh
```

每个脚本必须：

- 使用隔离 PostgreSQL；
- 显式运行 Flyway V1 到目标版本；
- 输出 `.verification/<feature>/<timestamp>/summary.md` 和 machine-readable JSON；
- 失败返回非零；
- 不依赖截图；
- 不覆盖、不删除用户已有工作区文件；
- 清理自己启动的 server、preview 和数据库资源。

### 9.1 后端硬门槛

在进入实现代码的三轮收敛审查前，必须先通过：

1. 本任务相关 unit/controller tests；
2. PostgreSQL/Testcontainers 或外部隔离 PostgreSQL 的端到端集成测试；
3. `mvn clean compile test-compile`；
4. 完整 Maven test，若被其他人的测试夹具阻塞，只做最小测试适配，不改业务实现；
5. core standalone 启动，`/actuator/health` 为 `UP`；
6. starter consumer 编译并运行其 focused tests；
7. 若 `.env` 可用，至少一条真实 embedding 检索和一条真实 Chat/citation smoke。

### 9.2 前端硬门槛

若改动 WebUI，必须执行：

```bash
cd spring-ai-rag-webui
npm run test:run
npx tsc -b
npm run build
npm run check:alignment
npx playwright test <本任务核心 spec>
```

Playwright 只允许以以下证据验收：

- DOM 可见性和可访问状态；
- URL/router 状态；
- 网络请求方法、请求体、响应码、响应 JSON；
- 页面断言、表格/状态/错误文案断言；
- 必要时数据库只读查询。

禁止使用截图判断页面是否正确，也不把截图 artifact 当验收证据。

### 9.3 真实模型和收敛策略

真实模型调用只用于已规划的最小 smoke/goldenset，不把无限扩大真实 case 当成验证：

- embedding：创建一个稳定 fixture，确认 async job 最终 fresh；
- Chat：使用一个已知 query，确认 scope、source、citation validation；
- provider 超时/失败使用 deterministic mock，不用消耗真实额度制造错误场景。

执行策略是“硬门槛先行，固定范围三轮后审查”：

1. 先把相关集成测试、一键脚本、Maven、WebUI 门槛全部跑绿；
2. 再做三轮互不重叠、只读、固定文件范围的代码审查；
3. 只修复本任务范围内会影响正确性、数据一致性、安全、兼容性或成本的缺陷；
4. 任一轮发现并修改代码，计数器归零并重新跑硬门槛；
5. 连续三轮无修改后结束，不继续无限审查。

## 10. 兼容性、安全、隐私和回滚

### 10.1 API 兼容

- 现有 Search/Chat/JSON/upsert 字段保持可用；
- 新字段均为 optional；
- 旧 `embed` 行为不因新 policy 改变；
- Search response body 不为 trace header 改形；
- Chat/SSE 只增加 metadata/event 字段；
- OpenAI transport 只增加 `rag.filters`，未知字段继续明确拒绝；
- 旧 V3 retrieval logs 和 V4 evaluations 继续可读。

### 10.2 安全

- 所有 trace、job、suite、run detail 都在服务端重新执行 principal/Collection ACL；
- filter 只接受固定 JSON object，使用参数绑定；
- trace 不存 secret、Token、chunk、payload、完整 Prompt 或原始 LLM response；
- Tool 的 Collection、document、principal 和授权 filter 由服务端上下文拥有；
- citation ID 只引用当前 turn 的服务端 sources；
- quality suite 不能因为历史授权快照绕过当前授权。

### 10.3 默认值和 feature flags

推荐默认：

```yaml
rag:
  retrieval-diagnostics:
    enabled: true
    persist: true
    store-query-text: false
  embedding-jobs:
    enabled: false              # 与当前 V33 兼容
    ingestion-async-enabled: false
  evaluation:
    managed-suites-enabled: false
    citation-validation-enabled: true
```

解释：

- 诊断是低敏、短 retention 的产品能力，默认可开；
- embedding job 的已有开关保持 false，先通过显式配置启用 async；
- suite worker 在验收和运营策略稳定前默认关闭；
- citation validation 是被动、无 LLM 成本的确定性检查，可默认打开。

### 10.4 回滚

不执行 down migration。回滚顺序：

1. 关闭新 WebUI入口和对应 feature flag；
2. 关闭 ingestion async，继续使用旧 SYNC/SKIP；
3. 关闭 quality suite worker 和 citation warning；
4. trace 写入失败 fail-open，必要时关闭 persist；
5. 代码回滚时保留 V35–V38 空表/新增列，旧版本忽略未知 schema；
6. 不删除已有 job、trace、suite/run 数据，避免破坏审计和诊断证据。

## 11. 实施分解和完成定义

### Phase 0：基线

- 建立本轮 progress 文档；
- 固化测试矩阵和目标迁移版本；
- 记录当前基线 commit、Spring AI 版本、Flyway 版本；
- 先运行现有 quality regression，确认不是新任务前已经红。

### Phase A：诊断

- `RetrievalOutcome`/stage model；
- V35；
- Search/Chat/OpenAI 均返回 trace header，项目 Chat 另返回 metadata；
- trace list/detail + principal；
- diagnostics 一键脚本；
- 后端集成和正式文档。

### Phase B：filter

- API DTO/internal `RetrievalFilters`；
- V36；
- vector/fulltext/provider 下推；
- Search/Chat/JSON/OpenAI；
- filter 一键脚本；
- REST/config/architecture 正式文档。

### Phase C：operations

- policy/dispatcher；
- V37；
- ingestion integration；
- worker concurrency/heartbeat；
- ACL SQL pagination；
- readiness + WebUI；
- operations 一键脚本；
- API/config/developer/testing 正式文档。

### Phase D：quality

- citation validation；
- V38 suite/run tables；
- suite API/worker/compare；
- optional Spring AI evaluator adapter；
- Evaluation WebUI tabs；
- quality 一键脚本；
- quality/release/SSE/rest 正式文档。

### 总完成定义

只有以下全部成立才称为“本批完成”：

- 功能实现、相关测试和一键脚本都存在；
- 相关 PostgreSQL 集成测试通过；
- `mvn clean compile test-compile` 通过，服务可启动；
- WebUI 改动已通过 TypeScript、production build 和无截图 Mock Playwright；
- 真实 embedding/Chat smoke 在可用 `.env` 下通过；
- 正式中英文文档同步；
- 连续三轮固定范围实现审查无修改；
- `./scripts/verify-project-docs.sh` 和 `git diff --check` 通过；
- 工作区状态由实施者按用户后续指示处理，不在规划阶段 commit/push。

## 12. 实施后需要更新的长青文档

本规划是中文单语 draft，不在规划阶段复制英文正文。实施通过后，按
`.agents/skills/project-docs/SKILL.md` 成对更新：

- `docs/project-context.md` / `docs/project-context-zh-CN.md`
- `docs/architecture.md` / `docs/architecture-zh-CN.md`
- `docs/rest-api.md` / `docs/rest-api-zh-CN.md`
- `docs/configuration.md` / `docs/configuration-zh-CN.md`
- `docs/testing-guide.md` / `docs/testing-guide-zh-CN.md`
- `docs/developer-reference.md` / `docs/developer-reference-zh-CN.md`
- `docs/quality-defaults.md` / `docs/quality-defaults-zh-CN.md`
- `docs/SSE-PROTOCOL.md`
- `docs/openai-compatibility-readiness.md` / 中文对应文档
- `docs/release-checklist.md` / 中文对应文档
- `docs/troubleshooting.md` / 中文对应文档
- `docs/index.md` / `docs/index-zh-CN.md`
- 必要时更新 `AGENTS.md` 的 Flyway 版本、入口和规则摘要。

必须明确区分：

- 当前已实施能力；
- 默认关闭的受控预览；
- 仅在本文规划、尚未实施的能力；
- API Key 公网生产门槛仍未被本批替代。

## 13. 规划审查记录与停止条件

本文完成后执行三轮连续审查，计数器初始为 0：

### 第 1 轮：代码事实与依赖

- 逐项核对代码锚点、Spring AI 1.1.4 类、V33/V34、当前 API 和默认配置；
- 检查是否把已完成能力误列为待实施；
- 检查 API/迁移名称是否与现有命名冲突。

### 第 2 轮：兼容性、安全、数据一致性

- 检查 ACL、principal、scope、filter、tool context、trace 隐私；
- 检查旧字段、旧 endpoint、旧 migration、Starter 和 OpenAI 协议的兼容边界；
- 检查事务、job lease、retry、version/hash freshness、回滚。

### 第 3 轮：实施可行性、验证和文档

- 检查每个功能是否有明确 API、schema、测试、一键脚本和失败语义；
- 检查前端验收是否完全排除截图；
- 检查正式中英文文档更新清单、索引、release gate 和完成定义；
- 检查是否存在“实施时再决定”的阻断性空洞。

若任一轮发现会影响正确性、兼容性、安全、数据一致性或实施可行性的内容，立即修改
文档并将计数器归零，从第 1 轮重新开始。措辞、格式和实施时自然可从代码发现的非
阻断细节不触发重置。连续三轮无修改后，运行文档门禁并停止。

**明确停止点：本文规划完成、三轮审查无修改、文档门禁通过后，等待用户 review。**
在用户明确批准前，不实施任何上述生产功能，不创建 V35–V38 migration，不修改 WebUI，
不启动长期 dev server。规划文档本身可按用户指示 commit/push。
