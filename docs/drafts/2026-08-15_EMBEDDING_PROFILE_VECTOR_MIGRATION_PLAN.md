# Embedding Profile、固定维度向量列与旧 VectorStore 路径清理实施规划

**状态**：已实施并验证（2026-08-15；修正后连续三轮无修改审查通过）
**创建日期**：2026-08-15
**规划基线**：`main` 分支，Flyway `V1-V24`，当前活动向量维度 `1024`
**实施快照**：Flyway `V1-V29`；固定向量列 `embedding_1024 VECTOR(1024)`；验证记录见
[实施进度账本](2026-08-15_EMBEDDING_PROFILE_VECTOR_MIGRATION_PROGRESS.md)
**实施边界**：后端、数据库迁移、配置、测试与正式文档；首版不增加 WebUI 页面
**规划性质**：已冻结的目标设计与实施对照记录；代码和正式文档是当前行为真相源

> 本文是单语规划稿。按项目文档规范，由
> [中文索引](../index-zh-CN.md)和[英文索引](../index.md)共同链接本文件，
> 不创建内容空洞的英文副本。

---

## 1. 执行摘要

当前项目真正参与写入和检索的是 `rag_embeddings.embedding VECTOR(1024)`。
与此同时，项目还通过 Spring AI `PgVectorStore` 暴露了
`POST /api/v1/rag/documents/{id}/embed/vs`，将数据写入独立的
`rag_vector_store`。主检索链完全不读取该表，因此这条路径会制造“写入成功但
RAG 检索不可见”的数据孤岛，应当删除。

当前正式向量记录也没有保存生成它的模型身份。只要模型发生变化，即使新旧模型
维度都为 `1024`，系统也可能把两个不兼容的向量空间混在一起比较；文档级缓存
`rag_documents.embedded_content_hash` 同样无法表达“某个文档是否已由某个模型
完成嵌入”。

本规划采用以下目标架构：

1. 引入不可变的 `rag_embedding_profiles`，用稳定 `profile_key` 标识
   provider、模型、revision、维度、距离度量和归一化语义。
2. `rag_embeddings` 通过 `embedding_profile_id` 关联 Profile；同一个文档块
   可以为多个 Profile 并存多份向量。
3. 当前正式向量列迁移为 `embedding_1024 VECTOR(1024)`；不预建未使用维度。
   将来真实采用新维度时再增加 `embedding_<dimensions>` 固定长度列和索引。
4. 新增 `rag_document_embedding_state`，按
   `(document_id, embedding_profile_id)` 管理缓存、覆盖率和失败状态。
5. 写入改为“事务外完整生成并校验，短事务内原子替换”；任何 chunk 失败都不
   删除既有可用向量。
6. 在线检索只使用一个明确的活动 Profile，向量检索和所有全文检索分支都必须
   过滤相同 Profile，查询向量必须由该 Profile 对应的模型生成。
7. 换模采用“新 Profile 并行重嵌入、验证、切换、保留旧 Profile 回滚”的流程；
   禁止原地改变向量列维度，禁止把旧向量 cast 为新维度。
8. 删除 `/embed/vs`、`VectorStoreConfig`、PGVector Store starter 依赖及相关
   配置和文档。`rag_vector_store` 不存在或为空时可安全删除；非空时迁移必须
   停止并要求人工审计，绝不静默丢数据。

该设计首版仍保持“一个在线活动 Embedding Profile”，不是面向终端用户的任意
多模型选择功能。它先解决模型身份、固定维度索引、换模安全和数据一致性问题。

---

## 2. 目标、非目标与验收定义

### 2.1 目标

- 每条可服务的 embedding 都能追溯到确定的模型 Profile。
- 相同维度但不同模型的向量不会被同一检索请求混合比较。
- 固定长度 pgvector 列继续获得维度校验和 HNSW 索引能力。
- 模型更换期间旧 Profile 可继续服务，新 Profile 可并行回填和验证。
- 单个 chunk 或远程模型调用失败不会破坏已有可用向量。
- 缓存、缺失向量统计和重嵌入状态均以 Profile 为维度。
- 无用的 `rag_vector_store` 写入路径和依赖被完整清除。
- 老数据迁移必须显式确认其 Profile 身份，不能由当前环境配置静默推断。

### 2.2 非目标

- 首版不允许 API 调用方在请求中任意指定 embedding 模型或向量列。
- 首版不提供 Profile CRUD WebUI，也不把换模操作开放为普通业务 API。
- 首版不实现多个 Profile 同时参与一次融合检索。
- 首版不预创建 `384`、`768`、`1536` 等未实际使用的向量列。
- 首版不把 Collection 的 `embeddingModel` / `dimensions` 自动升级为
  Collection 级路由配置；它们继续作为兼容元数据，避免扩大本次变更范围。
- 不在本次迁移中自动删除未被本项目识别的自定义 VectorStore 表。
- 不把 `EmbeddingModelRouter` 现有 fallback 列表视为可直接用于向量生成的
  容灾链；跨模型透明 fallback 会污染向量空间，必须禁止。

### 2.3 核心验收

实施完成必须同时满足：

1. 新文档写入后，`rag_embeddings` 中每行都有 Profile 身份，且只有与 Profile
   维度匹配的固定长度向量列有值。
2. 同一文档、同一 chunk、两个不同 Profile 可以并存，唯一约束不会互相覆盖。
3. 在线检索 SQL 和查询向量生成均绑定同一活动 Profile；全文分支不会因多 Profile
   数据产生重复结果。
4. 模拟部分 embedding 失败时，目标 Profile 的旧向量和 COMPLETED 状态保持不变。
5. 内容、chunk 配置、Profile 任一变化时缓存失效；完全相同时命中缓存。
6. Legacy 向量存在但未显式确认 Profile 时，切换命令拒绝继续，不会自动贴标签。
7. `rag_vector_store` 非空时清理迁移失败且数据保留；为空或不存在时可安全完成。
8. `/embed/vs` 不再出现在 Controller、OpenAPI 和正式 REST 文档中。
9. 目标 PostgreSQL 上的 `EXPLAIN (ANALYZE, BUFFERS)` 证明活动 Profile 查询使用
   对应的 HNSW 索引，而不是全表排序。
10. 后端相关集成测试、`mvn clean compile test-compile`、服务真实启动和文档门禁
    全部通过。

---

## 3. 当前仓库事实

### 3.1 数据库与实体

- `V1__init_rag_schema.sql` 创建 `rag_embeddings`：
  - `document_id`
  - `chunk_text`
  - `chunk_index`
  - `embedding VECTOR(1024) NOT NULL`
  - chunk 位置、metadata、created time
- 现有 HNSW 索引 `idx_rag_emb_vector_hnsw` 直接建立在 `embedding` 上。
- `RagEmbedding` 将列映射为 `float[1024]`，没有模型或版本字段。
- `V8__add_embedded_content_hash.sql` 在 `rag_documents` 增加了
  `embedded_content_hash`，它只描述最后一次内容哈希，不描述 embedding 模型。
- `V15`、`V16` 等迁移在同一 `rag_embeddings` 表上增加全文检索列和索引。

### 3.2 写入链

主要入口位于 `DocumentEmbedService`：

1. 读取文档并按 `rag.chunk.*` 分块。
2. `EmbeddingBatchService` 使用全局 `EmbeddingModel` 调用远程服务。
3. 通过 `JdbcTemplate` 写入 `rag_embeddings.embedding`。
4. 设置 `rag_documents.processing_status=COMPLETED` 和
   `embedded_content_hash=content_hash`。

当前风险：

- `@Transactional` 覆盖了远程模型调用，形成不必要的长事务。
- 生成新向量之前先 `deleteByDocumentId`，失败会丢失旧向量。
- 只要部分 chunk 成功就标记 COMPLETED，造成不完整文档进入检索。
- 删除操作按 document 删除所有向量，没有 Profile 作用域。
- 缓存只按 document、内容哈希和“是否存在任意 embedding”判断。

### 3.3 检索链

- `HybridRetrieverService` 使用同一个全局 `EmbeddingModel` 生成 query vector。
- 向量 SQL 只读 `rag_embeddings`，没有模型或维度过滤。
- `PgTrgmFulltextProvider`、`PgJiebaFulltextProvider`、
  `PgEnglishFtsProvider` 也直接查询 `rag_embeddings`。
- 如果同一 chunk 为多个 Profile 保存多行，所有全文分支都会返回重复项，除非它们
  同样过滤活动 Profile。
- `EmbeddingModelRouter` 虽然存在 primary/fallback API，但当前主写入和主检索链
  没有通过它建立 Profile 一致性；不能假定项目已经具备安全的多 embedding 模型能力。

### 3.4 Collection 元数据

`rag_collection.embedding_model` 和 `dimensions` 会出现在实体、API、导入导出和
WebUI 中，但当前不会驱动每个 Collection 的实际模型选择。现阶段强行把它们解释为
运行时路由，会让既有数据和 API 语义突然变化，因此本次保持为管理元数据，并在正式
文档中明确说明。

### 3.5 无效 VectorStore 路径

- `VectorStoreConfig` 在 `postgresql` profile 下创建 `PgVectorStore`。
- `application-postgresql.yml` 指定表名 `rag_vector_store`。
- `/api/v1/rag/documents/{id}/embed/vs` 调用 `VectorStore.add()` 写该表。
- `HybridRetrieverService` 和全文 Provider 都不读取该表。
- `DocumentEmbedService` 构造的 document ID 为 `<documentId>-<chunkIndex>`，
  而 Spring AI 表结构和 ID 管理与正式 `rag_embeddings` 路径不同。

结论：它不是可切换的等价实现，而是不可见的数据旁路，应删除而不是继续维护。

---

## 4. 冻结的设计决策

| 议题 | 首版决策 | 理由 | 可逆边界 |
|------|----------|------|----------|
| 在线 Profile 数 | 一个活动 Profile | 保持现有 API 简单，先解决数据正确性 | 后续可增加 Collection 级路由 |
| 当前向量列 | `embedding_1024 VECTOR(1024)` | 固定长度可校验并有效建立 ANN 索引 | 新维度通过新迁移加列 |
| 预建常见维度列 | 不预建 | 避免空列、索引和维护成本 | 采用模型时再增加 |
| Profile 存储 | 独立不可变注册表 + FK | 避免在每行重复完整模型描述 | 可增加展示字段，不改变身份 |
| Profile 在线选择 | `rag.embedding.profile-key` | 稳定、显式、可审计 | 未来可由 Collection 引用 |
| Legacy 身份 | 显式确认后回填 | 不能从当前环境证明历史模型 | 可选择重新嵌入而非认领 |
| 写入事务 | 模型调用在事务外，短事务原子替换 | 防止长事务和半成品 | 不可放宽 |
| 部分失败 | 整个文档/Profile 失败，不替换旧数据 | 检索需要完整 chunk 集 | 不可放宽 |
| 模型 fallback | 禁止跨 Profile 透明 fallback | 不同模型向量空间不可混用 | 仅同 Profile 的等价服务副本可重试 |
| 换维度 | 新列 + 重嵌入 | 向量不能通过 cast 获得新语义 | 旧列可在回滚窗后删除 |
| HNSW | Profile 专属部分索引 | 避免 ANN 取候选后再过滤导致召回下降 | 单 Profile 初期可短暂使用全局索引 |
| `rag_vector_store` | 移除；非空时拒绝自动 drop | 防止误导和数据丢失 | 审计导出后再人工清理 |
| Collection 模型字段 | 暂保留兼容元数据 | 当前不参与实际路由 | 后续单独规划语义升级 |

---

## 5. 目标数据模型

### 5.1 `rag_embedding_profiles`

推荐最终结构：

```sql
CREATE TABLE rag_embedding_profiles (
    id BIGSERIAL PRIMARY KEY,
    profile_key VARCHAR(128) NOT NULL UNIQUE,
    provider VARCHAR(64) NOT NULL,
    model_name VARCHAR(255) NOT NULL,
    model_revision VARCHAR(128) NOT NULL,
    dimensions INTEGER NOT NULL CHECK (dimensions > 0),
    distance_metric VARCHAR(32) NOT NULL,
    normalization VARCHAR(32) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_rag_embedding_profile_identity UNIQUE (
        provider,
        model_name,
        model_revision,
        dimensions,
        distance_metric,
        normalization
    )
);
```

语义约束：

- `profile_key` 是部署和运维使用的稳定标识，例如
  `siliconflow-bge-m3-1024-v1`；不是任意用户输入。
- provider、model、revision、dimensions、distance、normalization 构成模型空间
  身份。已有 Profile 的这些字段不可更新。
- `enabled` 可切换，但被活动配置引用的 Profile 必须 enabled。
- 禁止硬删除仍被 embedding 或 state 引用的 Profile。
- `model_revision` 无可靠上游 revision 时使用显式值 `unspecified`；换供应商、
  模型实现或输出语义时必须创建新 Profile，不能覆盖旧行。
- 首版只允许 `COSINE`，配置其他距离度量时启动失败；先把字段纳入身份，避免以后
  无法解释历史数据。

### 5.2 `rag_embeddings`

过渡期保留旧 `embedding`，新增：

```sql
ALTER TABLE rag_embeddings
    ADD COLUMN embedding_profile_id BIGINT,
    ADD COLUMN embedding_1024 VECTOR(1024);

ALTER TABLE rag_embeddings
    ADD CONSTRAINT fk_rag_embedding_profile
    FOREIGN KEY (embedding_profile_id)
    REFERENCES rag_embedding_profiles(id);

CREATE UNIQUE INDEX uq_rag_embedding_chunk_profile
    ON rag_embeddings(document_id, chunk_index, embedding_profile_id)
    WHERE embedding_profile_id IS NOT NULL;
```

切换稳定后的逻辑结构：

```text
id
document_id
chunk_index
chunk_text
chunk_start_pos
chunk_end_pos
embedding_profile_id
embedding_1024        VECTOR(1024), nullable for future other dimensions
metadata
search_vector_zh
search_vector_en
created_at
```

不变量：

- 一行表示“一个 document chunk + 一个 Profile + 一个匹配维度的向量”。
- 同一 `(document_id, chunk_index, embedding_profile_id)` 只能有一行。
- 当前只支持 `1024`，因此 Profile dimensions 必须为 `1024`，
  `embedding_1024` 必须非空且长度由 PostgreSQL 类型保证。
- 将来增加其他维度后，每行只能有一个维度列非空。新增维度迁移必须同步扩展
  CHECK 约束和代码 allowlist。
- `embedding_profile_id` 在 expand 阶段允许 NULL 仅用于未认领 Legacy 数据；
  contract 阶段必须收紧为 NOT NULL。
- 不能把 model name 只放在 JSON metadata 中代替 FK；JSON 无法提供身份约束。
- 兼容窗口内旧 `embedding VECTOR(1024) NOT NULL` 仍存在。新代码写入 `1024`
  Profile 时必须在同一个短事务内把同一向量双写到 `embedding` 和
  `embedding_1024`，直到 contract 删除旧列。否则新行无法满足旧列非空约束，
  旧应用回滚后也看不到兼容窗口中新写入的数据。
- 首版只实施 `1024` Profile，因此上述双写可行。未来第一次跨维度迁移必须发生在
  本次 contract 完成、旧应用不再是可回滚目标之后；不能向旧 `VECTOR(1024)` 列
  写入其他维度。

### 5.3 `rag_document_embedding_state`

```sql
CREATE TABLE rag_document_embedding_state (
    document_id BIGINT NOT NULL REFERENCES rag_documents(id) ON DELETE CASCADE,
    embedding_profile_id BIGINT NOT NULL
        REFERENCES rag_embedding_profiles(id),
    content_hash VARCHAR(64) NOT NULL,
    chunker_version VARCHAR(128) NOT NULL,
    status VARCHAR(20) NOT NULL,
    chunk_count INTEGER NOT NULL DEFAULT 0,
    processing_error TEXT,
    completed_at TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (document_id, embedding_profile_id),
    CONSTRAINT ck_rag_document_embedding_state_status
        CHECK (status IN ('PROCESSING', 'COMPLETED', 'FAILED'))
);
```

用途：

- 缓存键：Profile + content hash + chunker version。
- 覆盖率：活动 Profile 下有多少文档真正 COMPLETED。
- 检索资格：只有 state 为 COMPLETED，且 state content hash 等于文档当前
  content hash 的记录才可服务。
- 失败诊断：记录首次嵌入失败；已有 COMPLETED 数据的失败重试不覆盖已完成状态。

`rag_documents.content_hash` 当前 schema 允许 NULL。任何写入或 Legacy 认领在创建
state 前，都必须用项目统一的 SHA-256 规则从文档 content 确定性计算并持久化缺失的
content hash；不得把 NULL、空字符串或临时随机值写入 state。

`chunker_version` 使用确定性 fingerprint，而不是人工随意填写。fingerprint 至少包含：

```text
chunker implementation id
defaultChunkSize
minChunkSize
defaultChunkOverlap
影响 chunk 边界的算法版本
```

### 5.4 旧文档字段

- `rag_documents.embedded_content_hash` 在首版保留，避免一次性破坏实体和已有 API。
- 新代码不再把它作为缓存真相源。
- `rag_documents.processing_status` 暂时继续反映活动 Profile 的用户可见聚合状态，
  但 Profile 级真相源是 `rag_document_embedding_state`。
- 在完成 API 和 WebUI 依赖审计后，可另开 contract 迁移删除
  `embedded_content_hash`；本次不强行删除。

### 5.5 向量列 allowlist

应用代码必须集中维护维度到列的映射：

```text
1024 -> embedding_1024
```

约束：

- 列名不能来自请求参数、数据库自由文本或未经校验的环境变量。
- SQL 中无法参数化的列标识符，只能由该编译期 allowlist 选择。
- Profile dimensions 不在 allowlist 时，启动、写入和检索都必须 fail fast。
- 新增维度必须先有 Flyway 列、类型、CHECK、索引和测试，再扩展 allowlist。

---

## 6. 配置与 Profile 注册

### 6.1 新增配置

在现有 `rag.embedding` 下增加：

```yaml
rag:
  embedding:
    profile-key: ${RAG_EMBEDDING_PROFILE_KEY:siliconflow-bge-m3-1024-v1}
    provider: ${RAG_EMBEDDING_PROVIDER:siliconflow}
    model: ${SILICONFLOW_MODEL:BAAI/bge-m3}
    model-revision: ${RAG_EMBEDDING_MODEL_REVISION:unspecified}
    dimensions: ${SILICONFLOW_DIMENSIONS:1024}
    distance-metric: COSINE
    normalization: PROVIDER_DEFAULT
```

默认值与当前 BGE-M3 基线一致。生产换模时必须显式设置新 `profile-key`；只修改
`model` 而复用旧 key 会因身份冲突而启动失败。

内置默认 `siliconflow-bge-m3-1024-v1` 只在 provider、model、dimensions、
distance 和 normalization 全部仍为内置默认值时可自动采用。任一身份字段被覆盖而
`RAG_EMBEDDING_PROFILE_KEY` 未显式提供时，启动必须失败，避免用带有旧模型名称的
key 注册新模型。

### 6.2 `EmbeddingProfileRegistry`

新增集中组件，负责：

1. 按 `profile-key` 读取 Profile。
2. 不存在时，以当前配置创建 Profile。
3. 已存在时逐字段校验身份；任何不一致都拒绝启动。
4. 校验 Profile enabled、维度 allowlist、距离度量和模型绑定。
5. 对应用其余部分只暴露不可变的 `ActiveEmbeddingProfile`。

注册过程不得：

- 根据现有向量猜测 Profile。
- 修改已有 Profile 的身份字段。
- 自动把 `embedding_profile_id IS NULL` 的 Legacy 行归到活动 Profile。

在线模式启动还必须执行 Legacy guard：

- `embedding_profile_id IS NULL` 的 legacy 行数大于 0 时，默认拒绝进入 ready 状态，
  并输出 adopt/re-embed 维护命令。
- 只有专用 migration mode 可以在未认领数据存在时运行。
- 不提供“临时把 NULL 行当活动 Profile 读取”的兼容开关，因为它会重新引入未知
  模型向量混用。
- 若操作者选择不认领旧数据，必须先在维护模式中备份并显式隔离/删除这些行，再启动
  在线模式。

### 6.3 模型绑定与 fallback

活动 Profile 必须绑定一个确定的 `EmbeddingModel`：

- 文档向量和 query vector 使用同一绑定。
- 每次模型响应都校验向量数量、顺序和维度。
- `EmbeddingBatchService` 的“批量失败后逐条重试”可以保留，但必须使用同一个
  Profile 的同一个模型绑定。
- 不允许自动切换到 `models.json` 中另一个 embedding fallback，即使维度相同。
- 如果确需供应商级容灾，只能把多个等价 endpoint 封装为同一 Profile 的传输层
  重试；必须有运维证据证明模型 revision 和输出语义一致。

---

## 7. 写入、缓存与状态机

### 7.1 新写入流程

`DocumentEmbedService` 拆成准备阶段和提交阶段：

1. 读取并校验文档、活动/目标 Profile。
2. 计算 content hash 和 chunker version，并捕获文档的 optimistic-lock version。
3. 按 `(document, profile, content hash, chunker version)` 检查缓存。
4. 在数据库事务外完成分块。
5. 在数据库事务外调用模型生成全部 chunk 向量。
6. 严格校验：
   - results 数量等于 chunks 数量；
   - 每项成功；
   - 顺序与输入一致；
   - 每个向量非空且长度等于 Profile dimensions；
   - 不存在 NaN / Infinity。
7. 任一校验失败：
   - 不删除目标 Profile 已有 embedding；
   - 若此前没有 COMPLETED state，记录 FAILED；
   - 若此前已有 COMPLETED state，保留其服务状态和向量，只记录日志、指标和
     请求失败结果。
8. 全部成功后，在一个短事务中：
   - 锁定或重新读取文档，复核 document version、content hash 和 enabled 状态；
   - 任一值与准备阶段不一致时，判定本次结果已过期，整批放弃且不替换任何向量；
   - 只删除目标 `(document_id, embedding_profile_id)` 的旧行；
   - batch insert 全部新行；
   - upsert state 为 COMPLETED；
   - 更新兼容的 document 聚合状态。
9. 事务失败则整体回滚，旧 Profile 和其他 Profile 数据不受影响。

事务外模型调用意味着文档可能在生成期间被编辑。提交前的 version/hash 复核是硬性
并发保护，不能仅依赖“最后写入者获胜”；旧请求绝不能覆盖更新内容对应的新状态。

### 7.2 状态转换

```text
没有 state
  -> 首次请求可写 PROCESSING
  -> 全量成功: COMPLETED
  -> 失败: FAILED

已有 COMPLETED
  -> 相同 cache key: CACHED，不调用模型
  -> cache key 变化: 事务外重新生成
       -> 成功: 原子替换并写入新的 COMPLETED state
       -> 失败: 保留旧 COMPLETED state 和旧向量
```

如果文档内容已变化，旧 state 的 content hash 与当前文档不一致，检索层会排除旧
向量；保留旧数据的意义是避免破坏性删除，并允许故障排查或内容回退，不代表继续
用旧内容回答。

### 7.3 缓存命中

缓存命中必须同时满足：

```text
state.status == COMPLETED
state.embedding_profile_id == target profile
state.content_hash == document.content_hash
state.chunker_version == current chunker fingerprint
state.chunk_count > 0
实际 embedding 行数 == state.chunk_count
```

`force=true` 跳过缓存，但仍遵守全量成功后原子替换，不再表示“先删再试”。

### 7.4 批处理

- 单个文档是原子单位；一个文档失败不回滚其他文档。
- 不再用包住整个批次和远程调用的单一长事务。
- 批量结果应增加 `embeddingProfileKey`，便于审计。
- 批次中每个文档都执行相同的 Profile 和维度校验。

---

## 8. 检索一致性

### 8.1 活动 Profile 作用域

`HybridRetrieverService` 在一次请求开始时解析一次活动 Profile，并将其传给：

- query embedding 生成；
- vector SQL；
- `PgTrgmFulltextProvider`；
- `PgJiebaFulltextProvider`；
- `PgEnglishFtsProvider`。

一次请求中禁止重新解析并切换 Profile。

### 8.2 向量 SQL

查询必须同时限制：

```sql
e.embedding_profile_id = <trusted active profile id literal>
AND e.embedding_1024 IS NOT NULL
AND s.status = 'COMPLETED'
AND s.content_hash = d.content_hash
```

并按 allowlist 选择 `embedding_1024` 计算 `<=>`。

Profile ID 不是请求输入，而是从已校验的数据库 Profile 得到的正整数。为了让
PostgreSQL 能证明部分索引谓词成立，查询模板可以将这个受信整数作为 SQL literal；
query vector、document IDs、limit 等仍使用绑定参数。严禁直接拼接任意字符串。

### 8.3 全文检索

所有全文 Provider 必须加同一个 `embedding_profile_id` 和 state freshness 过滤。
否则同一 chunk 的多 Profile 行会重复参与全文召回并扭曲融合分数。

首版保留全文数据随每个 Profile embedding 行重复存储的现状，避免拆分 chunk 表。
如果未来 Profile 数量显著增加，再单独评估把 chunk 文本规范化到独立表；不在本次
迁移中扩大 schema。

### 8.4 HNSW 索引

当只有一个 Profile 时，全局 HNSW 可以工作；一旦同维 Profile 并存，ANN 搜索后再
过滤 Profile 可能减少有效候选，影响召回。因此切换新 Profile 前必须存在专属部分索引：

```sql
CREATE INDEX CONCURRENTLY idx_rag_emb_p_<profileId>_1024_hnsw
ON rag_embeddings
USING hnsw (embedding_1024 vector_cosine_ops)
WITH (m='16', ef_construction='64')
WHERE embedding_profile_id = <profileId>
  AND embedding_1024 IS NOT NULL;
```

实现内部 `EmbeddingProfileIndexManager`，由活动 Profile bootstrap 和迁移命令复用：

- Profile ID 只能是数据库读取的正整数。
- 维度列只能来自 allowlist。
- 索引名由固定模板生成并检查长度。
- 使用 PostgreSQL advisory lock 防止并发创建。
- `CREATE INDEX CONCURRENTLY` 在事务外执行。
- 创建后检查 `pg_indexes` 和 `indisvalid`。
- 切换前运行 `EXPLAIN (ANALYZE, BUFFERS)`，确认命中该索引。

活动 Profile 注册完成后，bootstrap 必须幂等确保对应部分索引存在；fresh database
也不能在缺少 HNSW 的情况下默默进入 ready。多实例同时启动由 advisory lock 收敛为
一次创建。索引创建失败或 `indisvalid=false` 时 readiness 失败，并给出维护命令；
不能降级为长期无索引在线服务。

测试数据量较小时优化器可能选择顺序扫描，集成测试应验证索引定义和过滤正确性；
生产前性能门禁使用足够规模数据和 `enable_seqscan=off` 辅助诊断，但最终仍需在正常
优化器配置下验证计划合理。

---

## 9. Legacy 数据迁移

### 9.1 原则

现有 `rag_embeddings.embedding` 没有模型身份。即使当前 `.env` 配置是 BGE-M3，
也不能证明历史所有向量都由该模型生成。因此：

- 不允许 Flyway 自动把全部旧行标记为当前 Profile。
- 不允许应用普通启动流程静默认领。
- 操作者只能选择“显式认领为某个 Legacy Profile”或“重新生成”。

### 9.2 Expand

使用实施时下一个可用 Flyway 版本，本文统一写作 `V{next}`，不得硬编码 `V25`：

1. 创建 Profile 和 state 表。
2. 给 `rag_embeddings` 增加 nullable `embedding_profile_id` 与
   nullable `embedding_1024`。
3. 建立 FK、唯一索引和普通辅助索引。
4. 保留旧 `embedding`、旧 HNSW 和旧应用读写能力。
5. 新安装也通过同一迁移得到目标结构。

同一发布物支持 maintenance 和 online 两种启动模式。升级已有数据库时，先运行
maintenance mode 让 Flyway expand 并完成 adopt/re-embed；只有 Legacy guard 通过后
才启动 online mode。这样不依赖一个仍按旧列检索的中间应用版本，也不会让旧数据在
切换时静默消失。

### 9.3 显式认领命令

提供一次性、可重复执行的维护命令模式，例如：

```bash
java -jar spring-ai-rag-core.jar \
  --spring.profiles.active=postgresql \
  --rag.embedding.migration.mode=adopt-legacy \
  --rag.embedding.migration.legacy-profile-key=<confirmed-profile-key> \
  --rag.embedding.migration.confirm=I_HAVE_VERIFIED_THE_LEGACY_MODEL
```

命令行为：

1. 要求数据库备份或快照确认。
2. 输出 legacy 行数、文档数、`vector_dims(embedding)` 分布和抽样信息。
3. 校验指定 Profile 已存在且 dimensions 与所有 legacy 行一致。
4. 按 document 预检 chunk index 是否重复、缺号以及向量是否完整；异常文档保持
   `embedding_profile_id IS NULL`，输出明确清单，要求重新嵌入或备份后清理。
5. 只有确认字符串完全匹配才执行：
   - `embedding_1024 = embedding`
   - `embedding_profile_id = confirmed profile id`
6. 按 document 回填 state：
   - `content_hash` 为空时，先按正常文档写入使用的 SHA-256 算法计算并持久化；
   - content hash 使用文档当前 `content_hash`；
   - chunker version 标记为显式
     `legacy-adopted-unknown`，不能假装等于当前 chunker；
   - status 仅在 chunk index 连续、计数有效且向量均非空时标记 COMPLETED；
     否则 FAILED 并要求重嵌入。
7. 重跑只处理仍为 NULL 的 legacy 行，不复制已迁移数据。
8. 输出迁移后校验报告并以非零退出码表示不完整。

为避免大表长事务，维护命令按 document ID 分批并提交。每个 document 必须作为
最小原子单位：只有该文档全部 chunk 复制、Profile 赋值和 state 校验完成后才提交。
批次中断后可从仍为 NULL 的行继续；不能出现“同一文档只有部分 chunk 被认领却标记
COMPLETED”的状态。

由于 `legacy-adopted-unknown` 不等于当前 chunker fingerprint，普通缓存检查会要求
重新嵌入。这是保守默认：认领让旧数据可审计和临时服务，但不能永久掩盖未知分块版本。
如果操作者能独立证明历史 chunker 配置完全相同，可在维护命令中使用另一个显式确认
参数写入当前 fingerprint；该路径必须有测试，不作为默认。

### 9.4 重新嵌入选项

如果无法确认历史模型：

1. 保留 legacy 行和旧列。
2. 用目标 Profile 对文档执行完整重嵌入，写入 `embedding_1024` 和 state。
3. 验证全部覆盖后再删除未认领 legacy 行。

### 9.5 Cutover 与 Contract

Cutover 前置条件：

- `embedding_profile_id IS NULL` 的行数为 0；选择放弃的旧数据必须已在维护模式中
  完成备份和显式删除，不能留在在线表中。
- 活动 Profile 覆盖率达到部署定义的门槛，默认要求 100% enabled 文档。
- Profile 专属 HNSW 索引有效。
- 检索质量和性能通过。

Cutover：

- 兼容窗口内，新应用对 `1024` Profile 同事务双写旧 `embedding` 与
  `embedding_1024`，并写 Profile/state；在线读取只使用新列和 Profile 过滤。
- 新检索只读 Profile-aware 列。
- `/embed/vs` 已删除。

Contract 在至少一个稳定回滚窗口后单独执行：

- `embedding_profile_id SET NOT NULL`。
- 删除旧 `embedding` 列和旧 HNSW 索引。
- 删除过渡期 dual-write 代码。
- 评估是否删除 `rag_documents.embedded_content_hash`。

Contract 不与首次 cutover 绑定，避免发布后无法快速回滚旧应用。

---

## 10. 换模 Runbook

### 10.1 同维度模型切换

示例：旧、新模型都输出 `1024`：

1. 创建新的不可变 Profile，不能复用旧 key。
2. 迁移模式显式绑定新 Profile 和新模型。
3. 保持在线 `rag.embedding.profile-key` 指向旧 Profile。
4. 批量为所有目标文档生成新 Profile 向量，写同一 `embedding_1024` 列的新行。
5. 失败只影响新 Profile，不删除旧 Profile 数据。
6. 检查：
   - state COMPLETED 覆盖率；
   - 每文档 chunk 数；
   - 向量维度和有限值；
   - Profile 专属 HNSW；
   - goldenset、延迟和人工抽样。
7. 把在线配置切到新 Profile 并滚动重启。
8. 观察一个完整回滚窗口。
9. 如有问题，只需把活动 key 切回旧 Profile。
10. 稳定后再按保留策略清理旧 Profile 数据。

即使维度相同，也绝不能在一次查询中混合旧、新 Profile。

### 10.2 跨维度模型切换

示例：`1024 -> 768`：

1. 新 Flyway 迁移增加 `embedding_768 VECTOR(768)`。
2. 扩展 CHECK 约束和代码 allowlist。
3. 新 Profile dimensions 为 `768`。
4. 使用新模型重新生成所有向量；禁止复制、截断、补零或 cast 旧向量。
5. 为新 Profile + `embedding_768` 建部分 HNSW。
6. 完成与同维切换相同的覆盖率、质量和性能验证。
7. 切换活动 Profile。
8. 回滚窗口内保留 `embedding_1024` 和旧 Profile。
9. 只有当不再存在引用 `1024` 的 Profile 数据时，才可另行规划删除旧列。

### 10.3 回滚条件

出现以下任一情况立即停止切换或回滚：

- 新 Profile 覆盖率低于门槛。
- 发现维度不匹配、NaN、Infinity、chunk 缺失或重复。
- `EXPLAIN` 未使用预期索引且延迟明显回退。
- goldenset 指标超出允许回归阈值。
- 新模型错误率、限流或成本不可接受。
- Profile 配置和数据库身份不一致。

---

## 11. `rag_vector_store` 清理

### 11.1 代码与依赖

完整移除：

- `spring-ai-rag-core/.../config/VectorStoreConfig.java`
- `DocumentEmbedService` 的 `VectorStore` 注入
- `embedDocumentViaVectorStore(...)`
- `isVectorStoreAvailable()`
- 构造 Spring AI `Document` 的辅助方法
- `RagDocumentController` 的 `/embed/vs`
- `spring-ai-starter-vector-store-pgvector` 依赖
- `application.yml`、`SpringAiRagApplication`、demo、Kubernetes 中只为该路径存在的
  `PgVectorStoreAutoConfiguration` exclude
- `application-postgresql.yml` 的 `spring.ai.vectorstore.pgvector.*`
- 对应单元测试、集成测试 Mock 和 OpenAPI 断言

保留 `spring-ai-advisors-vector-store` 与否必须按实际 import 再判断；不能因名字相近
误删仍被其他 Advisor 使用的依赖。

### 11.2 数据库 fail-safe 清理

迁移仅处理默认表 `public.rag_vector_store`：

```text
表不存在 -> 跳过
表存在且 count(*) = 0 -> 删除
表存在且 count(*) > 0 -> RAISE EXCEPTION，Flyway 失败
```

非空时的操作手册：

1. 记录行数、schema、表定义和创建时间。
2. 导出表或创建数据库快照。
3. 判断数据是否需要按正式文档重新嵌入到 `rag_embeddings`。
4. 由操作者显式清空/重命名该表。
5. 重跑迁移。

不尝试自动解析 Spring AI metadata 并转移到正式表，因为无法可靠证明 chunk、
document 和模型身份。自定义 `vector-table-name` 产生的其他表只在文档中提示审计，
不自动发现和删除。

### 11.3 API 兼容

`/embed/vs` 是无效旁路，不保留 deprecated 转发。移除后返回标准 404。
发布说明必须明确：

- 调用方改用 `POST /api/v1/rag/documents/{id}/embed` 或 streaming/batch 等正式入口。
- 旧端点曾写入的数据不参与检索。
- 升级前必须检查 `rag_vector_store` 是否非空。

---

## 12. 实施阶段

### 阶段 A：测试基础和 Progress Ledger

1. 新建同名 progress 文档记录实施状态、验证命令和三轮代码审查计数。
2. 建立 pgvector Testcontainers 集成测试基础，复用项目依赖管理。
3. 一次性写出本任务的核心验收测试骨架，避免实施后按 review 发现逐个补测试。

### 阶段 B：Expand Schema

1. 新建 `V{next}__embedding_profile_expand.sql`。
2. 新建 Profile、state 表。
3. 增加 `embedding_profile_id`、`embedding_1024` 和约束/普通索引。
4. 不删除旧列，也不解除旧列 `NOT NULL`；首版新写入执行 1024 双写。
5. 增加 `V{next+1}__remove_unused_rag_vector_store.sql` 的 fail-safe 检查。
6. 实施时先重新列出 migration 目录，选用真正下一个版本。

### 阶段 C：Profile Runtime

1. 扩展 `RagEmbeddingProperties`。
2. 新增 Profile entity/repository/value object/registry。
3. 新增向量列 allowlist。
4. 将活动 Profile 与当前 `EmbeddingModel` 绑定并 fail fast 校验。
5. 新增 Legacy migration command 和 index manager。

### 阶段 D：原子写入与 Profile 状态

1. 重构 `DocumentEmbedService` 事务边界。
2. 新增事务型 replace service，避免 self-invocation 导致 `@Transactional` 失效。
3. 批量插入目标 Profile 行。
4. Profile 级缓存和 state upsert。
5. 更新 batch、SSE 和 PDF 自动嵌入调用链。
6. 文档删除清理所有 Profile 的 embedding 和 state。

### 阶段 E：Profile-aware Retrieval

1. query vector 绑定活动 Profile。
2. vector SQL 使用 allowlisted 列和 Profile/state 过滤。
3. 三个全文 Provider 使用同一 Profile/state 过滤。
4. 增加多 Profile 去重和隔离集成测试。
5. 实现 Profile 部分索引创建与验证。

### 阶段 F：删除旧 VectorStore 路径

按第 11 节清单删除代码、依赖、配置、测试和 API 文档。

### 阶段 G：Cutover 文档与运维

同步更新：

- `docs/architecture.md` / `architecture-zh-CN.md`
- `docs/configuration.md` / `configuration-zh-CN.md`
- `docs/rest-api.md` / `rest-api-zh-CN.md`
- `docs/project-context.md` / `project-context-zh-CN.md`
- `docs/postgresql-extensions.md`
- `docs/testing-guide.md` / `testing-guide-zh-CN.md`
- `docs/troubleshooting.md` / `troubleshooting-zh-CN.md`
- `docs/developer-reference.md` / `developer-reference-zh-CN.md`
- `docs/IMPLEMENTATION_COMPARISON.md`
- `.env.example`
- 必要的 release notes / changelog

正式文档必须区分：

- 当前活动 Profile；
- Legacy adoption；
- 同维与跨维换模；
- `rag_collection` 模型字段仍只是元数据；
- `/embed/vs` 已删除。

---

## 13. 文件级改动清单

实施时以实际引用搜索为准，以下是核心范围而非穷举行号：

| 范围 | 预计改动 |
|------|----------|
| `core/entity` | Profile、document-profile state、`RagEmbedding` 新字段 |
| `core/repository` | Profile/state repository，Profile 级 embedding 查询与删除 |
| `core/config` | embedding properties、registry、活动 Profile、移除 VectorStore config |
| `core/service/DocumentEmbedService` | 事务外生成、完整校验、Profile 缓存 |
| `core/service` 新组件 | 原子 replace、Legacy migration、Profile index manager |
| `core/retrieval/EmbeddingBatchService` | 完整结果/维度/有限值校验，禁止跨 Profile fallback |
| `core/retrieval/HybridRetrieverService` | Profile-aware vector SQL |
| `core/retrieval/fulltext/*` | Profile/state freshness 过滤 |
| `core/controller/RagDocumentController` | 删除 `/embed/vs`，正式 embed 响应增加 Profile 信息 |
| `core/service/BatchDocumentService` | 删除/批量删除与所有 Profile 数据一致 |
| `db/migration` | expand、VectorStore fail-safe、未来 contract |
| `application*.yml` / `.env.example` | Profile 配置，移除 VectorStore 配置 |
| `spring-ai-rag-core/pom.xml` | 删除不再使用的 PGVector Store starter |
| `k8s/`、`demos/` | 清理无效 auto-config exclude 和示例 |
| `core/src/test` | 单元、Testcontainers 集成、OpenAPI contract、启动测试 |
| `docs/` | 第 12 节列出的双语正式文档 |

WebUI 首版不需要新功能，但如果后端 Collection DTO 或现有展示字段发生变化，仍须运行
前端 typecheck、生产构建和核心 Mock Playwright，确认没有回归。

---

## 14. 测试与验证计划

### 14.1 一次性验收测试矩阵

在主要实现完成前先建立下列测试，避免 review 阶段临时扩散：

#### 数据库迁移集成测试

- 从 V1-V24 schema + 空数据升级成功。
- 有标准 legacy `rag_embeddings` 数据时 expand 不丢数据。
- `content_hash` 为 NULL 的 legacy 文档可按统一 SHA-256 规则补齐后迁移。
- `rag_vector_store` 不存在：成功。
- `rag_vector_store` 为空：删除成功。
- `rag_vector_store` 非空：迁移失败且原表、行数不变。
- Profile FK、唯一约束、固定维度类型和 state PK 生效。

#### Profile 注册集成测试

- 首次配置创建 Profile。
- 同 key、同身份重复启动幂等。
- 同 key、不同 model/revision/dimensions 启动失败。
- disabled Profile 不能成为活动 Profile。
- 未支持维度启动失败。

#### Legacy migration 集成测试

- 无确认字符串拒绝。
- 维度不一致拒绝且零更新。
- 确认后 Profile、向量列和 state 正确回填。
- 重跑幂等。
- 中途失败后按 document 边界恢复，不产生部分文档 COMPLETED。
- chunk 缺失/重复时不错误标记 COMPLETED。

#### 写入端到端集成测试

使用真实 PostgreSQL/pgvector 和 fake `EmbeddingModel`，从 service 或 HTTP 入口覆盖：

- 首次写入生成完整 Profile-aware 行和 state。
- 缓存命中不调用模型。
- content/profile/chunker 任一变化触发重嵌入。
- 部分 chunk 失败不删除旧向量。
- 维度错误、NaN、Infinity 拒绝提交。
- `force=true` 仍为原子替换。
- 生成期间文档被更新时，旧请求提交失败且不覆盖新内容的向量/state。
- 同维两个 Profile 并存。
- 删除文档清理全部 Profile 行和 state。

#### 检索端到端集成测试

- query model 与活动 Profile 一致。
- 两个 Profile 有相似/重复 chunk 时，只返回活动 Profile。
- vector-only、trgm、jieba、English FTS 都应用 Profile 过滤。
- state 非 COMPLETED 或 content hash 过期的数据不参与检索。
- documentIds / collection ACL 过滤继续生效。
- Profile 切换后结果来自新 Profile，切回旧 Profile可回滚。

#### API / Contract 测试

- `/embed` 响应含 Profile 标识。
- `/embed/vs` 不在 OpenAPI，HTTP 请求返回 404。
- batch、streaming 和 PDF 自动嵌入未回归。

### 14.2 后端硬门槛

在进入代码三轮收敛审查之前，至少执行：

```bash
mvn clean compile test-compile
mvn test
```

并单独运行本任务 Testcontainers 集成测试。若 Docker 环境导致 Testcontainers
不可用，不能把它当成功；应记录阻断并使用项目 PostgreSQL dev 实例完成等价端到端
验证。

### 14.3 真实启动与 smoke

使用 `postgresql` profile 启动服务，确认：

- Flyway 完成。
- JPA `ddl-auto=validate` 通过。
- 活动 Profile 日志不泄露密钥。
- health/readiness 正常。
- 用 fake/local model 或 `.env` 中可用 embedding key：
  - 创建文档；
  - embed；
  - search；
  - 再 embed 命中缓存；
  - 检查数据库 Profile/state/向量行。

如使用真实模型，`base-url` 仍不得带 `/v1`。

### 14.4 前端回归门槛

即使首版没有新增 UI，也要对独立 WebUI 执行：

```bash
npx tsc -b
npm run build
npm run test:e2e -- <核心 Mock Playwright 测试文件>
```

并运行集合/文档主流程的核心 Mock Playwright。只有后端响应字段完全向后兼容且
前端无代码影响时，才可在 progress 文档中说明为何无需新增 Playwright 场景。

### 14.5 性能门槛

在接近生产的数据规模上：

```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT ...
FROM rag_embeddings e
...
WHERE e.embedding_profile_id = <active-id>
ORDER BY e.embedding_1024 <=> CAST(:query AS vector)
LIMIT :limit;
```

记录：

- 使用的索引名；
- planning/execution time；
- rows removed by filter；
- shared hit/read buffers；
- 相比旧查询的 P50/P95；
- goldenset precision/recall 变化。

### 14.6 文档门槛

```bash
./scripts/verify-project-docs.sh
git diff --check
```

并检查中英文正式文档结构和事实一致、无真实密钥、无失效 `/embed/vs` 描述。

---

## 15. 发布、回滚与数据安全

### 15.1 发布顺序

1. 备份数据库并记录 preflight 统计。
2. 用新发布物的 maintenance mode 执行 Flyway expand 和 Profile 注册。
3. 显式认领、重新生成或备份后删除 Legacy 数据。
4. 确认 Legacy guard 已通过。
5. 创建并验证 Profile 专属索引。
6. 完成覆盖率、质量、性能门禁。
7. 启动 Profile-aware online mode。
8. 观察回滚窗口。
9. 后续独立 contract 发布删除旧列。

### 15.2 回滚

- 应用回滚：contract 前旧 `embedding` 列仍在，旧应用可恢复。
- 模型回滚：把活动 `profile-key` 切回旧 Profile。
- 数据回滚：新 Profile 行独立存在，不覆盖旧 Profile。
- schema rollback：expand 只增加表/列，通常不需要逆向 DDL；发生问题优先回滚应用。
- `rag_vector_store` 非空时迁移本身停止，不需要从备份恢复被删除数据。

### 15.3 数据安全不变量

实施和 review 不得破坏：

1. 未确认的 Legacy 向量不获得模型身份。
2. 远程调用失败不删除已完成向量。
3. 不同 Profile 的行永不被 re-embed 操作互相删除。
4. 不同 Profile 的向量不在同一次相似度比较中混用。
5. 维度变化必须重新生成向量。
6. 非空未知表不自动 drop。
7. Profile 身份字段不可原地修改。
8. 用户输入不能控制 SQL 列名、索引名或 Profile ID literal。

---

## 16. 可观测性与运维输出

新增低基数指标或健康详情：

- 当前活动 `profile_key`、model、dimensions（不得包含 API key）。
- embed 请求成功、失败、缓存命中计数，标签只使用受控 Profile key。
- embedding 维度校验失败计数。
- Profile 覆盖文档数、FAILED state 数。
- Legacy 未认领行数。
- Profile 专属 HNSW 是否存在且 valid。

日志要求：

- 每次 embed 记录 document ID、Profile key、chunk 数、cache/replace 结果。
- 不记录完整文档内容、完整向量或 API key。
- Profile 配置冲突要输出字段名和非敏感的 expected/actual。

---

## 17. 实施完成定义

只有以下全部满足，任务才可宣告完成：

- 本规划通过连续三轮无修改系统审查。
- progress ledger 记录了每个实施阶段和验证结果。
- 代码、迁移、测试和正式文档全部完成。
- 后端相关集成测试、全量测试、编译、test-compile 和真实启动通过。
- 前端 typecheck、生产构建和核心 Mock Playwright 通过。
- 基本集成验证先通过，之后连续三轮固定范围代码审查均未修改代码。
- `git diff` 已完整回看，无有价值内容丢失，无无关修改。
- 文档门禁和 `git diff --check` 通过。
- 工作区只包含本任务有意变更；提交和推送仅在用户另行要求时执行。

---

## 18. 规划审查规则

规划基线冻结后，按以下固定范围检查：

1. 数据模型、约束、Legacy expand/cutover/contract 和回滚。
2. 写入事务、缓存、删除、检索隔离、索引和模型绑定。
3. 文件范围、测试矩阵、启动/性能/文档验收与运维可执行性。

只有会导致方案不可实施、产生直接数据安全风险、形成模型空间混用、使迁移无法回滚、
或让核心验收不可验证的问题才触发修改并把连续无修改计数归零。行号偏差、措辞格式、
非穷举文件清单和实施中自然暴露的次要细节不触发归零。

必须连续三轮完整检查无任何文档修改后，才可开始业务代码实施。
