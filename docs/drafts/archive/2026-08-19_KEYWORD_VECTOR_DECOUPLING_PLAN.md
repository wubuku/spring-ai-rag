# 下一批高价值功能实施规划：本地关键词索引与远程向量派生解耦

> **状态：已实施并归档。**
>
> 本文是本批唯一实施依据。本文已完成连续三轮固定范围检查并据此完成实施。实施过程使用同目录的
> [进度账本](2026-08-19_KEYWORD_VECTOR_DECOUPLING_PROGRESS.md) 记录关键进展、验证证据和恢复上下文。
>
> 本文是单语实施草稿；稳定行为落地后，必须同步提炼到双语长青文档，再按
> [文档治理 Skill](../../../.agents/skills/project-docs/SKILL.md) 归档本文和进度账本。

## 1. 执行结论

本批只解决一个直接影响产品可用性的主问题：

> 文档 CRUD 已经成功提交，但远程 embedding provider 暂时失败时，如何让新正文仍可
> 通过关键词检索，同时保证旧正文立即退出，且向量检索不会返回过期内容。

推荐实现顺序：

1. **V43：本地关键词派生存储**：新增独立的 `rag_document_chunks`，不再让全文检索
   读取 `rag_embeddings.chunk_text`。
2. **CRUD/导入统一联动**：所有正文 mutation、JSON `retrievalText` mutation、PDF
   添加到 RAG、手工 embedding 和 durable embedding job 都确保当前本地 chunks 先就绪；
   `SKIP` 明确删除当前本地派生。
3. **准确生命周期状态**：公开 `localIndexStatus`、`embeddingStatus` 和
   `searchability=KEYWORD_ONLY/READY/...` 的真实组合。
4. **检索和运营验证**：全文 provider 从本地 chunks 读取，向量 provider 仍只读
   `rag_embeddings`；现有 retry/job API 继续负责向量恢复；增加端到端 PostgreSQL
   验收和一键脚本。
5. **客户指引与长青文档**：明确 client 不能把 `embeddingStatus=FAILED` 等同于
   “完全不可检索”，应以 `searchability` 和分支状态做降级判断。

本批不实施 Collection 原子迁移、API Key 配额/用量、XML/Office、EACH_COLLECTION
召回、OpenAI 兼容协议扩展或新的来源 namespace 检索过滤。它们会继续保留在 TODO，
不应混入本次 schema 和发布风险。

## 2. 为什么现在做这个

当前 CRUD 和 embedding job 已经具备较完整的 generation/CAS/lease 防护，但派生存储仍
有一个结构性耦合：

```text
rag_documents.content
        │
        ├── chunk -> rag_embeddings.chunk_text + vector
        │                         │
        └── provider failure ─────┘
```

当前 pg_trgm、pg_jieba、English FTS 都从 `rag_embeddings` 读取 chunk，并要求活动
Profile 的 `rag_document_embedding_state.status = COMPLETED`。因此：

- 正文更新后，旧向量会因 content hash freshness 被排除，这是正确的；
- 但新 chunk 只有在远程 provider 成功后才进入 `rag_embeddings`；
- provider 失败会同时阻断关键词和向量检索；
- 已有 `DocumentLifecycleResponse.localIndexStatus` 字段，但实现仍将本地索引与向量
  状态基本同步，`KEYWORD_ONLY` 只是预留文案，不是真实状态；
- `keyword-index-enabled` 等历史配置/设计不能替代独立的持久化边界。

目标状态：

```text
正文 CRUD 成功
  │ 同一短事务
  ├── 旧本地 chunks 不再满足 freshness
  ├── 新 rag_document_chunks READY
  └── embedding job QUEUED/同步调用
          │
          ├── provider 成功 -> rag_embeddings READY
          └── provider 失败 -> 保留本地 chunks，向量 FAILED

公开 searchability:
  local READY + vector READY                -> READY
  local READY + vector QUEUED/PROCESSING/FAILED -> KEYWORD_ONLY
  local READY + vector NOT_REQUESTED        -> KEYWORD_ONLY
  local NOT_REQUESTED + vector NOT_REQUESTED -> NOT_REQUESTED
  enabled=false -> DISABLED
```

关键一致性规则：

1. **旧正文立即退出**：本地和向量查询都必须匹配当前
   `rag_documents.content_hash` 与当前派生描述版本；绝不在 provider 失败时继续返回旧正文。
2. **本地先于远程**：本地 chunk 写入与主文档 mutation/当前派生准备在同一数据库事务内
   完成；provider 调用仍在事务外。
3. **向量独立提交**：远程向量替换只写 `rag_embeddings` 和向量状态，不删除本地 chunks。
4. **无显式悲观锁**：只使用唯一约束、条件 `UPDATE ... RETURNING`、现有
   generation/CAS/lease；禁止 `FOR UPDATE`、`SKIP LOCKED`、JPA
   `PESSIMISTIC_*` 和 advisory lock。
5. **状态可解释**：`KEYWORD_ONLY` 表示新正文已可用关键词分支，但语义向量尚未达到
   当前内容的新鲜状态；不是“搜索结果质量百分比”。

## 3. 已核对的当前上下文

### 3.1 文档身份和 CRUD

- 外部文档稳定地址是
  `collectionKey + sourceNamespace + externalId`，数据库唯一键为
  `(collection_id, source_namespace, external_id)`。
- 外部 upsert、JSON record upsert、source tombstone、V42 Sync Run 都经由
  `DocumentMutationService` / `ExternalDocumentService` 的生命周期路径。
- 本地文档公开 `documentRevision`，正文 mutation 会创建完整版本快照并通过
  `EmbeddingDispatchService` 进入 `SYNC/ASYNC/SKIP` 分发。
- JSON record 把调用者的自然语言 `retrievalText` 存入 `rag_documents.content`；
  `jsonbPayload` 不参与 embedding。
- PDF 添加到 RAG 最终也是写入普通 `rag_documents.content`，因此本批不需要 PDF 专用
  第二套实现。

代码锚点：

- `spring-ai-rag-core/src/main/java/com/springairag/core/service/DocumentMutationService.java`
- `spring-ai-rag-core/src/main/java/com/springairag/core/service/ExternalDocumentService.java`
- `spring-ai-rag-core/src/main/java/com/springairag/core/service/JsonRecordService.java`
- `spring-ai-rag-core/src/main/java/com/springairag/core/embeddingjob/EmbeddingDispatchService.java`
- `spring-ai-rag-core/src/main/java/com/springairag/core/service/DocumentEmbedService.java`

### 3.2 当前向量 job 和并发边界

- 活动 Profile 是不可变的 `rag_embedding_profiles` 行，当前向量写入
  `rag_embeddings.embedding_1024 VECTOR(1024)`。
- `rag_document_embedding_state` 按 document/Profile 保存 content hash、chunker version、
  request generation、active job 和 embedding 状态。
- `rag_embedding_jobs` 由 `EmbeddingJobRepository` 负责 coalesce、generation、条件
  claim、lease、heartbeat、重试和完成 fencing。
- worker provider 调用不在事务内；`EmbeddingPersistenceService.replace` 在短事务内
  校验文档版本/hash/enabled 并替换向量行。
- `claimCommitAllowed` 使用条件 DML 取得短暂提交资格，不是悲观锁；本批必须保持这个
  决策不变。

代码锚点：

- `spring-ai-rag-core/src/main/java/com/springairag/core/embeddingjob/EmbeddingJobRepository.java`
- `spring-ai-rag-core/src/main/java/com/springairag/core/embeddingjob/EmbeddingJobExecutor.java`
- `spring-ai-rag-core/src/main/java/com/springairag/core/service/EmbeddingPersistenceService.java`
- `spring-ai-rag-core/src/main/java/com/springairag/core/service/DocumentLifecycleService.java`

### 3.3 当前全文和向量查询

- 向量查询由 `HybridRetrieverService` 使用
  `EmbeddingProfileSqlScope.fromAndFreshness(...)`，读取 `rag_embeddings e`。
- pg_trgm、pg_jieba、English FTS 使用 `RetrievalScopeSql` 下推 Collection、document
  type、document ID、metadata/payload containment。
- 全文 provider 当前读取 `rag_embeddings.chunk_text`，并检测该表上的 GIN/tsvector 索引。
- `RetrievalResultProvenance` 依赖查询结果带有 `document_id`、标题、source、original
  filename 等字段，因此新表查询必须保留同样的别名和 provenance 字段。

代码锚点：

- `spring-ai-rag-core/src/main/java/com/springairag/core/retrieval/HybridRetrieverService.java`
- `spring-ai-rag-core/src/main/java/com/springairag/core/retrieval/EmbeddingProfileSqlScope.java`
- `spring-ai-rag-core/src/main/java/com/springairag/core/retrieval/RetrievalScopeSql.java`
- `spring-ai-rag-core/src/main/java/com/springairag/core/retrieval/fulltext/PgTrgmFulltextProvider.java`
- `spring-ai-rag-core/src/main/java/com/springairag/core/retrieval/fulltext/PgJiebaFulltextProvider.java`
- `spring-ai-rag-core/src/main/java/com/springairag/core/retrieval/fulltext/PgEnglishFtsProvider.java`

### 3.4 迁移和验证基线

- 当前 Flyway 是 V1–V42；本批新增 V43，不改写已执行迁移。
- 一键生命周期门禁是 `scripts/verify-document-lifecycle.sh`；专项历史验证脚本和
  `scripts/verify-project-docs.sh` 已存在。
- 后端硬门槛必须包含：
  `mvn clean compile test-compile`，本批相关 PostgreSQL integration test，以及
  服务启动 smoke。
- WebUI 修改必须运行 TypeScript、生产构建、Vitest 和无截图 Playwright。Playwright
  只使用 DOM 可见性、网络请求/响应、接口 JSON 和自动化断言；配置已有
  `screenshot: 'off'`，不得引入截图断言。

## 4. 数据模型设计

### 4.1 V43 新增本地派生表

新增 `rag_document_chunks`：

| 列 | 约束/用途 |
|---|---|
| `id` | `BIGSERIAL` 主键 |
| `document_id` | FK `rag_documents(id)`，文档删除级联 |
| `local_index_generation` | 本地 chunk generation，单调递增，独立于远程 job generation |
| `content_hash` | 生成 chunk 时的文档描述 hash |
| `chunker_version` | 生成 chunk 的 chunker identity |
| `chunk_text` | 非空本地检索文本 |
| `chunk_index` | 非负 chunk 顺序 |
| `chunk_start_pos` / `chunk_end_pos` | 原文位置；JSON record 为完整文本范围 |
| `metadata` | 可选 chunk metadata；当前写入可为 null，保留查询兼容性 |
| `search_vector_en` | generated `tsvector`，供 English FTS GIN |
| `created_at` | 生成时间 |

索引/约束：

- `UNIQUE(document_id, local_index_generation, chunk_index)`；
- `INDEX(document_id, local_index_generation)`；
- `GIN(search_vector_en)`；
- pg_trgm 可用时创建 `GIN(chunk_text gin_trgm_ops)`；
- pg_jieba 和 `jiebacfg` 可用时创建
  `GIN(to_tsvector('jiebacfg', chunk_text))` 表达式索引；
- content hash、chunker version 只作为 freshness 证据，不作为唯一键。
- `chunk_text` 必须满足 `BTRIM(chunk_text) <> ''`；
- `chunk_index >= 0`、`chunk_start_pos >= 0`、`chunk_end_pos >= chunk_start_pos`；
- `chunk_start_pos`/`chunk_end_pos` 可受文档长度进一步校验，但不能用数据库约束读取
  业务正文逐行计算；服务层必须在插入前完成该校验。

本地 chunk **不绑定 embedding profile**。chunker 输入只有文档 kind、正文和 chunker
配置；更换 embedding profile 不应重复创建关键词索引。全文 provider 的
`embeddingProfileId` 参数为兼容现有 SPI 保留，但新实现不使用它决定 local chunk
归属或 freshness。该参数只允许用于把已有 `excludeIds`（其契约仍是
`rag_embeddings.id`）映射到同一 document/chunk 的活动 Profile 向量行。

不能照搬旧 `rag_embeddings.search_vector_zh` 的命名和实现：V15 实际用 `simple`
生成该列，而 pg_jieba 查询使用 `jiebacfg`。V43 的中文路径必须直接使用与查询表达式
一致的 `to_tsvector('jiebacfg', chunk_text)` 条件索引，避免 capability 显示可用但
查询无法命中对应 GIN 索引。

不在 V43 删除或改写 `rag_embeddings` 的已有列和索引。旧表保留用于语义向量和兼容
诊断；全文 provider 切换到新表后，旧全文索引不再是新路径的真相源。

### 4.2 新增独立的本地索引状态表

新增 `rag_document_local_index_state`，一条文档只有一行：

```text
document_id                 BIGINT PRIMARY KEY REFERENCES rag_documents(id) ON DELETE CASCADE
local_index_status          VARCHAR(20) NOT NULL DEFAULT 'NOT_REQUESTED'
content_hash                VARCHAR(64)
chunker_version             VARCHAR(128)
local_index_generation      BIGINT NOT NULL DEFAULT 0
chunk_count                 INTEGER NOT NULL DEFAULT 0
processing_error            VARCHAR(500)
updated_at                  TIMESTAMP(6) NOT NULL
```

V43 必须为 `local_index_status` 增加数据库 `CHECK`：

```text
local_index_status IN ('READY', 'FAILED', 'NOT_REQUESTED')
local_index_generation >= 0
chunk_count >= 0
local_index_status <> 'READY'
  OR (content_hash IS NOT NULL AND chunker_version IS NOT NULL AND chunk_count > 0)
```

状态列不能只依赖 Java enum 或 lifecycle 代码校验；数据库必须拒绝未知状态，避免
不同版本服务写出彼此不能解释的 local state。

允许的 `local_index_status`：

- `READY`：当前文档 hash/chunker 的本地 chunks 已存在且数量大于 0；
- `FAILED`：本地 chunk 持久化失败；本批默认失败会回滚主 mutation，不应常态出现，
  但保留状态供诊断；
- `NOT_REQUESTED`：调用者明确选择 `SKIP` 或尚未请求派生；
- 文档 disabled/tombstoned 时，公开 lifecycle 归一为 `DISABLED`；数据库 local state
  仍只保存上述三种值，可保留上一次 READY 信息，但检索永远过滤 disabled 文档。

local state 与 `rag_document_embedding_state` 完全独立：

- local state 不记录 embedding profile、job ID 或 provider 错误；
- embedding state 继续按 document/Profile 保存远程向量状态；
- lifecycle 同时读取两张 state 表再计算公开三元状态；
- 文档只有一个当前 local generation，所有活动 Profile 的全文检索共享它。

V43 数据迁移：

1. 创建新表和约束；
2. 对已有 `rag_embeddings` 按 `document_id + chunk_index` 去重，选择存在
   `rag_document_embedding_state.status = 'COMPLETED'`、content hash 与当前文档一致的
   来源组，把 chunk 文本复制到 `rag_document_chunks`；复制的 `chunker_version`
   来自来源 state，不由 SQL 猜测；
3. 只有复制行数等于来源 state.chunk_count、chunk_index 连续且文档 enabled 时，才把
   local state 标记为 READY；不完整或多来源冲突时保留 `NOT_REQUESTED`；
4. 迁移是正常的单次 Flyway migration，必须确定性支持空库和已有 V42 数据库；不以
   `IF NOT EXISTS` 掩盖半迁移状态，也不承诺脱离 Flyway 重复执行。

迁移不调用 embedding provider，不读取 JSONB payload，不改写 document revision。旧
chunker 版本即使被复制，也必须由运行时 freshness 判断排除，直到正常 CRUD/reindex
按当前 descriptor 重建。

## 5. 服务和状态设计

### 5.1 新增 `KeywordIndexPersistenceService`

新增组件：

`spring-ai-rag-core/src/main/java/com/springairag/core/service/KeywordIndexPersistenceService.java`

职责：

1. 接收 `RagDocument`、chunker descriptor 和 chunks，不接收或查询活动 Profile；
2. 判断当前 local index 是否已经匹配当前 `content_hash + chunker_version`，匹配则幂等返回；
3. 使用条件 DML 为本地 generation 分配新值；
4. 删除该 document 的旧本地 chunks，插入新 generation 的 chunks；
5. 在同一事务内更新独立的 `rag_document_local_index_state`；
6. `markNotRequested` 删除/排除旧本地 chunks并更新 local 状态；
7. 错误信息使用现有敏感信息脱敏和长度上限。

实现约束：

- 删除旧 chunks 和插入新 chunks 必须在同一短事务；
- 不使用 JPA `PESSIMISTIC_*` 或 `SELECT FOR UPDATE`；
- 使用 `INSERT ... ON CONFLICT` / `UPDATE ... RETURNING` 和唯一约束；
- 不修改 `rag_documents.document_revision`；
- 本地 generation 与 embedding request generation 分离，避免同步直嵌入路径二次推进
  远程 job generation；
- generation 分配和最终 READY 更新都必须校验数据库中当前文档仍 enabled 且
  `content_hash` 与调用快照一致；stale 调用必须整体回滚，不能覆盖新正文的 local state；
- 插入前验证 chunks 非空、chunk index 连续、文本非空、位置合法；
- JSON record 仍只有一个 record-level chunk，文本文档继续复用
  `HierarchicalTextChunker`。当前 chunker 已将 `minChunkSize` 作为质量目标而非准入
  门槛，并已有单元测试保证非空短段保留；本批不能重新发明第二套短文本 fallback，
  只需让 local/vector 共享该现有分块结果，并在跨层验收中锁定这一行为。

### 5.2 共享 chunking descriptor

当前 `DocumentEmbedService` 自己持有 chunker、`splitForEmbedding` 和
`buildChunkerVersion`。为避免本地和远程派生使用两个隐含版本，新增轻量的
`DocumentChunkingService`，集中提供：

```java
Descriptor describe(RagDocument document);
List<TextChunk> split(RagDocument document, String content);
```

`DocumentEmbedService` 和 `KeywordIndexPersistenceService` 都复用它。推荐返回包含
descriptor 和 chunks 的不可变 `PreparedChunks`，使直接同步 embedding 的 local/vector
写入使用同一份分块结果。任何 chunker 参数变化必须改变 descriptor version；旧 local
chunks 和旧 vectors 都自然失效，随后由正常 dispatch/job 重建。

### 5.3 统一 CRUD 联动点

在 `EmbeddingDispatchService` 中：

- `ASYNC` / `SYNC` / 显式 re-embed 在创建或复用当前 embedding generation 前，确保
  local chunks 已准备；
- 提供 `ensureLocalIndexInCurrentTransaction(document)`，并让 local mutation、external
  upsert/sync、JSON record、PDF bridge 在每次 enabled 文档成功保存后调用它一次；
  该操作按 hash/chunker 幂等，已有 local READY 时不生成新 generation。这样不能因为
  “向量已经 fresh”而漏掉历史数据的 local 回填；
- active job coalesce 时不得反复创建新 local generation；只在 hash/chunker 不匹配时
  重建；
- `SKIP` 调用 `KeywordIndexPersistenceService.markNotRequested`，确保旧正文不参与
  全文检索；
- 不影响 metadata-only、payload-only、Collection-only mutation 的既有“不重嵌入”语义：
  如果 retrieval text 未变化，则不创建新的远程 job；已有 local index 不重建，只有历史
  local state 缺失/过期时才幂等补齐。

因此，所有现有 `hasFreshEmbedding()` 早退判断必须改成“local 与 active vector 都
fresh”的联合判断，或先显式执行 local ensure 再判断 vector freshness；禁止单独用
vector cache 命中绕过 local index。restore、JSON record 的 payload-only/legacy 路径、
external upsert 的 no-op/metadata 路径都必须纳入测试。

`JsonRecordService` 和 `ExternalDocumentService` 当前仍保留可选的 legacy fallback，
本批保留这些兼容构造路径并注入同一个 local coordinator：不能在 `dispatchService` 或
`mutationService` 缺失时静默返回一个没有 local state 的“成功”；`SKIP` 必须能写入
local/embedding 的 `NOT_REQUESTED` 状态，无法做到时应明确失败。主 Spring 上下文仍以
`DocumentMutationService` 为唯一生产 mutation 路径。

在 `DocumentEmbedService` 的直接同步/legacy 入口中：

- provider 调用前也必须 ensure local chunks；
- provider 失败返回 `FAILED` 时 local chunks 保留；
- provider 成功时只替换向量，不删除本地 chunks。

在 `EmbeddingPersistenceService` 中：

- `replace` 只删除/插入 `rag_embeddings`；
- `recordFailureIfNoCompleted` 只改变 embedding 状态和 error，不把 local READY
  改成 FAILED；
- 现有 generation/hash/version/commit guard 逻辑保持不变。

现有预留配置 `rag.document-lifecycle.keyword-index-enabled` 从未形成可用行为。本批
不保留真假两套派生路径：删除该未使用配置及环境变量示例，本地关键词派生成为
SYNC/ASYNC 的标准行为；`SKIP` 是唯一显式不准备派生的选择。这样不会出现 schema 已
切换而默认值仍为 false、导致全文检索静默为空的假开关。

### 5.4 生命周期归一规则

`DocumentLifecycleService` 必须一次查询同时读取 local 和 embedding 字段，并按当前
文档 hash/chunker/count 判断 freshness：

| local | embedding | `localIndexStatus` | `embeddingStatus` | `searchability` |
|---|---|---|---|---|
| current READY | current COMPLETED | `READY` | `READY` | `READY` |
| current READY | QUEUED/PROCESSING | `READY` | `INDEXING` | `KEYWORD_ONLY` |
| current READY | current FAILED/CANCELLED | `READY` | `FAILED` | `KEYWORD_ONLY` |
| current READY | NOT_REQUESTED | `READY` | `NOT_REQUESTED` | `KEYWORD_ONLY` |
| not current | QUEUED/PROCESSING | `FAILED` | `INDEXING` | `INDEXING` |
| not current | current COMPLETED | `FAILED` | `READY` | `FAILED` |
| not current | FAILED | `FAILED` | `FAILED` | `FAILED` |
| NOT_REQUESTED | NOT_REQUESTED | `NOT_REQUESTED` | `NOT_REQUESTED` | `NOT_REQUESTED` |
| disabled | any | `DISABLED` | `DISABLED` | `DISABLED` |

`READY` 的语义是关键词和向量都针对当前派生输入新鲜。`KEYWORD_ONLY` 的语义是
关键词分支可用，向量分支尚未针对当前内容新鲜。`retryable=true` 的条件包括：
当前 local 缺失/失败，或向量状态不是 current READY 且文档 enabled。

若历史兼容数据没有 local state，生命周期可返回 `NOT_REQUESTED`；不能把旧
`rag_embeddings` 的存在数量直接伪装成新 local READY。若出现“当前向量 READY 但
local state 缺失/过期”的不变量破坏，公开 `searchability=FAILED`、
`localIndexStatus=FAILED` 且 `retryable=true`，提示重新派生；不能声称两条分支均已
READY。

## 6. 检索 SQL 改造

### 6.1 全文 provider

`PgTrgmFulltextProvider`、`PgJiebaFulltextProvider`、`PgEnglishFtsProvider`：

- `FROM rag_document_chunks e`
- JOIN `rag_document_local_index_state s`，校验：
  - `s.local_index_status = 'READY'`
  - `s.content_hash = d.content_hash`
  - `s.chunker_version` 等于当前文档 kind descriptor
  - `s.local_index_generation = e.local_index_generation`
  - `e.content_hash = d.content_hash`
  - `e.chunker_version` 等于当前 descriptor
  - `d.enabled = true`
- 保留现有 `RetrievalScopeSql` 的 alias：`e.document_id`、`d.collection_id`、
  `d.metadata`、`d.jsonb_payload`，确保 Collection/metadata/payload/type 过滤不回退；
- select 中继续返回 `chunk_text`、`document_id`、`chunk_index`、metadata、title、source、
  original filename，并额外返回可选的活动 Profile 向量行 `v.id AS embedding_id`；
  local chunk 自身的 `id` 不能冒充旧 embedding ID；
- provider capability 检测改为检查 `rag_document_chunks` 的对应索引。

`excludeIds` 不能直接与 `rag_document_chunks.id` 比较，因为其既有契约是
`rag_embeddings.id`，两张表的 sequence 没有可比性。三个全文 provider 需要按
`document_id + chunk_index + active embedding profile` 可选 LEFT JOIN 当前向量行，
只用该向量行 ID 应用 `excludeIds`；keyword-only chunk 没有向量 ID，因此不会被一个
无关的本地 chunk ID 误排除。全文可用性和 freshness 仍只依赖 local state，LEFT JOIN
不得把 keyword-only 退化成必须有向量。

新增 `KeywordIndexSqlScope` 或等价的单一 helper，禁止三个 provider 各自复制 freshness
判断。向量路径继续使用 `EmbeddingProfileSqlScope` 和 `rag_embeddings`，不把 null 或
缺失向量行混入向量 SQL。

### 6.2 空结果诊断和 readiness

- `RetrievalEmptyReasonProbe` 的 fresh count 改为“local keyword 或 vector 任一当前可用”
  的文档，不能将 KEYWORD_ONLY 文档计为完全不可检索；
- 诊断响应应能区分：
  - enabled 文档存在但无任何 local/vector current 派生；
  - local keyword-only 文档存在但 vector 未就绪；
  - 两个分支都 ready 但 query 没有候选；
- `CollectionEmbeddingReadinessResponse` 现有 embedding 计数保持兼容，本批不新增或
  重命名该响应字段；它仍是 embedding 运营统计，不宣称全文 readiness。关键词可用性
  通过已有 lifecycle/detail 响应表达。

本批推荐不改现有 search response 结构；客户端通过已有 lifecycle/detail 或诊断 API
获知状态，检索结果继续沿用现有 provenance 和 score 字段。

## 7. WebUI 和外部 client 契约

### 7.1 WebUI

当前 Documents 页面和翻译已经预留 `KEYWORD_ONLY`。实施只需确保：

- Documents 列表、详情、mutation response 使用后端真实 lifecycle；
- `KEYWORD_ONLY` 显示“仅关键词可检索/Keyword searchable”，不得显示“失败”；
- `FAILED` 的重试动作继续调用现有 embed/job retry 路径；
- tooltip 或错误文本说明远程 embedding 失败不等于关键词检索不可用；
- 不新增本地状态与后端状态同名但含义不同的推断；
- 增加 Vitest 与无截图 Mock Playwright：验证 DOM 文案、状态 class、retry 请求和
  JSON，不使用截图。

### 7.2 外部 client 最佳实践

正式 external sync guide 追加以下明确语义：

1. 对正文/retrievalText mutation，先等待 HTTP 持久化成功，再按响应的
   `searchability` 判断降级能力。
2. `searchability=KEYWORD_ONLY`：可以继续提供精确/关键词搜索；语义搜索结果可能
   暂时不完整，不应把旧向量当作新内容结果。
3. `embeddingStatus=FAILED`：只代表当前 embedding 分支失败；若
   `localIndexStatus=READY`，文档不是“完全不可用”。
4. 兼容字段 `embeddingFresh` 只表示活动 Profile 的向量 freshness，不能用它判断关键词
   索引是否可用；client 应优先使用 `lifecycle.searchability` 和
   `localIndexStatus`。
5. `embeddingJobId` 由 client 或运营侧通过已有 job GET/retry 轮询；不要高频重复
   upsert 来“催促” embedding。
6. `embeddingPolicy=SKIP` 是明确选择不准备任何派生，不是“暂时跳过向量但保留旧
   关键词索引”；正文变更后旧 local/vector 都不再参与检索。
7. source revision/document revision 幂等和 CAS 语义不变；metadata、JSON payload
   变化不会触发新的本地 chunk/向量重建，但历史 local state 缺失时允许一次幂等回填。

## 8. 实施文件范围

### 后端生产代码

- `spring-ai-rag-core/src/main/resources/db/migration/V43__...sql`
- `.../service/DocumentChunkingService.java`
- `.../service/KeywordIndexPersistenceService.java`
- `.../service/DocumentEmbedService.java`
- `.../service/EmbeddingPersistenceService.java`
- `.../embeddingjob/EmbeddingDispatchService.java`
- `.../embeddingjob/EmbeddingJobRepository.java`
- `.../service/DocumentLifecycleService.java`
- `.../service/ExternalDocumentService.java`、`.../service/JsonRecordService.java`
  的现有 legacy fallback 适配
- `.../retrieval/KeywordIndexSqlScope.java`
- `.../retrieval/fulltext/FulltextSearchProvider.java` 的 `excludeIds` 契约注释、
  三个全文 provider、`SearchCapabilities`
- `.../retrieval/RetrievalEmptyReasonProbe.java` 和必要的 readiness/diagnostics 代码
- `.../config/RagDocumentLifecycleProperties.java`、`application.yml`：删除未使用的
  `keyword-index-enabled` 配置

### 测试

- 新增本地 chunk persistence 单元测试；
- 新增 PostgreSQL integration test，至少从空库迁移到 V43；
- 扩展 document lifecycle、embedding jobs、fulltext provider 测试；
- 扩展 controller/API 契约测试；
- WebUI `Documents.test.tsx`、`documents.spec.ts` 和必要 mock fixtures；
- 不为修复 review 中偶然发现的无关问题无限扩展测试范围。

### 文档/脚本

- 新增 `scripts/verify-keyword-vector-decoupling.sh`，必须固定 PostgreSQL、后端、
  API/SQL 和 WebUI 证据，并支持 `--webui-only` 预览验证模式；
- `docs/configuration*.md`、`docs/project-context*.md`、`docs/architecture*.md`、
  `docs/rest-api*.md`、
  `docs/external-document-sync-client-guide*.md`、`docs/testing-guide*.md`、
  `docs/developer-reference*.md`、`docs/TODO*.md` 成对同步；
- Flyway 固定口径更新为 V1–V43；
- 所有新增验证脚本写入 `.verification/<topic>/<run-id>/`，不把运行产物提交。

## 9. 一次性验收矩阵

### 9.1 PostgreSQL integration

必须覆盖：

1. 空库 Flyway V1–V43；
2. V42 已有 current vector state 迁移为 local chunks；
3. 文本、多 chunk 和 JSON record 单 chunk；
4. local chunk hash/chunker/generation freshness；
5. 同一文档并发 local ensure 不出现重复 generation/chunk；
6. 已有短文本 chunker 行为在 local/vector 共享路径中保持：不足默认 `minChunkSize`
   的非空文本仍生成一个 local/vector 共享 chunk；
7. metadata-only、payload-only、Collection-only mutation 不重建派生；
8. content/retrievalText mutation 使旧 local/vector 同时退出；
9. provider failure 后 local READY、embedding FAILED、lifecycle KEYWORD_ONLY；
10. retry/provider success 后向量 READY、lifecycle READY；
11. stale worker/vector commit 不能覆盖新内容；
12. `SKIP` 后全文和向量均无当前派生；
13. disabled/tombstoned 文档不能被 local fulltext 命中；
14. Collection、metadata、JSON payload 过滤继续下推；
15. `excludeIds` 仍按活动 Profile 的 `rag_embeddings.id` 生效，不能与 local chunk ID
    混用；
16. pg_trgm/English provider 从 `rag_document_chunks` 读取，中文 provider 可用时
    也不回退到旧表。

### 9.2 HTTP/API

使用 dummy embedding endpoint 验证：

- external text upsert：provider 成功、provider 失败、retry；
- JSON record `retrievalText` 更新；
- local document PATCH/restore；
- lifecycle JSON 中三个状态字段的组合；
- `embeddingPolicy=SKIP` 的明确语义；
- existing `embeddingJobId` retry/cancel contract 不回归。

### 9.3 WebUI

先由一键脚本构建 Vite preview，再用 `BASE_URL` 指向该 preview；当前
`spring-ai-rag-webui/playwright.config.ts` 明确 `webServer: undefined`，不能把直接执行
`npm run test:e2e` 当作 Mock 验收。只使用：

- `npm run test:run`
- `npm run build`
- `npm run check:alignment`
- 一键脚本启动的 preview + 核心 Mock Playwright（至少
  `e2e/documents.spec.ts` 和 `e2e/embeddings.spec.ts`）

断言：

- `KEYWORD_ONLY` DOM 文案可见；
- FAILED 与 KEYWORD_ONLY 的 action/tooltip 不混淆；
- retry 请求路径、method、body 正确；
- no screenshot、无 canvas pixel 断言。

### 9.4 硬门槛命令

代码实现后按以下顺序执行，任一步失败不得进入三轮实现 review：

```bash
mvn clean compile test-compile
mvn test -pl spring-ai-rag-core -Dtest='*Keyword*Test,*DocumentLifecycle*Test,*Embedding*Test'
cd spring-ai-rag-webui
npx tsc -b --pretty false
npm run test:run
npm run build
npm run check:alignment
cd ..
./scripts/verify-keyword-vector-decoupling.sh --webui-only
```

其中 `--webui-only` 仍必须启动 preview、设置 `BASE_URL` 并运行核心 Mock suite，
不能绕过浏览器门禁。然后执行本批完整一键脚本、
`./scripts/verify-project-docs.sh`、
`./scripts/verify-no-pessimistic-locks.sh` 和 `git diff --check`。

## 10. 规划文档连续三轮检查规则

在开始代码实施前，只对本文执行固定范围检查，检查计数器初始为 0：

### 第 1 轮：代码事实和数据模型

- 重新核对 V1/V25/V33/V40/V41/V42 migration；
- 核对所有全文 provider、vector SQL、lifecycle 和 dispatch 调用点；
- 验证本文没有把 `rag_embeddings` 误称为可存储 null vector 的本地索引；
- 验证 V43/回填/兼容策略没有删除旧列或改变 public identity。

### 第 2 轮：并发、状态和 CRUD 联动

- 逐项检查 create/update/disable/restore/delete、external upsert/delete、JSON payload、
  PDF bridge、SYNC/ASYNC/SKIP；
- 检查旧正文退出、provider failure、retry、stale worker 和 disabled 文档；
- 检查没有引入悲观锁，没有把 request generation 与 local generation 错用；
- 检查状态表组合和 client 指引没有互相矛盾。

### 第 3 轮：验收、文档和可执行性

- 核对测试矩阵覆盖本批所有生产文件；
- 核对命令、端口、Flyway 版本、WebUI 无截图约束；
- 核对脚本、长青文档同步点和不做事项；
- 检查本文链接、术语、边界、默认决策是否足够让中断后的 Agent 直接实施。

发现任何影响正确性、兼容性、数据一致性、成本/安全或实施可行性的问题，必须立即
修改本文，计数器归零，从第 1 轮重新开始。只要连续三轮无问题且本文没有被修改，才
允许实施。行号变化、措辞偏好和实施中自然暴露的格式细节不触发归零。

## 11. 实施后固定范围三轮代码检查

代码实施必须先通过第 9 节硬门槛，再进行三轮互不重叠、只读、限定范围检查：

1. **数据/SQL轮**：V43 schema、回填、唯一索引、全文/vector freshness、迁移兼容；
2. **服务/并发轮**：CRUD dispatch、local generation、job lease/CAS、错误和 retry；
3. **API/WebUI/文档轮**：生命周期 JSON、client 契约、WebUI DOM/网络、双语文档和
   一键脚本。

只修复会影响本任务正确性、兼容性、安全/成本或数据一致性的缺陷。若修改代码，必须
重新通过硬门槛，三轮计数归零；禁止“发现一个 review 点就新增一个测试并无限全量重跑”的
发散流程。最终将所有关键验证和三轮结果写入进度账本。

## 12. 完成定义

只有以下全部满足，才算本批完成：

- V43 在空库和 V42 数据库升级成功；
- CRUD、外部同步、JSON record、PDF bridge 的当前正文 local chunks 与状态正确；
- provider 失败时新正文可关键词检索、旧正文不可检索；
- provider 恢复后向量和生命周期升级为 READY；
- 后端 API/集成测试、`mvn clean compile test-compile`、服务启动 smoke 通过；
- WebUI TypeScript、生产构建、Vitest、无截图 Mock Playwright 通过；
- 项目文档、无悲观锁、空白检查通过；
- 长青中英文文档同步，active plan/progress 归档；
- 固定范围实现代码连续三轮无问题；
- 之后才允许 `git commit`，先 merge remote 再 push，最后 `git status` 干净。
