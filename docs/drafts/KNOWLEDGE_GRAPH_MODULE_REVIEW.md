# 知识图谱模块评审：从外部方案到本仓可落地路径

> 状态：**前期技术讨论，未实施**。本文件不代表已批准的开发计划，只用于评审收敛。
>
> 评审对象：外部 AI 助手提交的《以实体与关系为核心的 Hybrid GraphRAG 模块》方案（Apache AGE + 关系表 + 图投影 + RRF 融合）。
>
> 评审基线：本仓当前工作区源码与 `docs/`（Flyway `V1–V73`，见 [AGENTS.md](../../AGENTS.md#L33)）。
>
> 评审约束：只读审查，未改动任何代码、配置、迁移，未执行 git 写操作。

---

## 0. 结论先行

**方案评分：6.5 / 10。方向可采纳，选型与落地细节需要重写。**

| 判断 | 内容 |
|------|------|
| **采纳** | 「图谱是可重建的派生索引」；「图检索是向量检索的补充」；「事实必须带证据」；「回答只回到原始 Chunk」；「先做 1–2 跳」；「不做社区发现/层次摘要」；「RRF 融合」。 |
| **否决** | **Apache AGE 作为第一版图存储**。不是因为它不好，而是它会把「一个可选能力」变成「框架全部消费者的部署前提」。 |
| **重写** | 数据模型中的 `tenant_id` / `knowledge_base_id` / `chunk_id + chunk_version` 三处：本仓没有租户层级，可见性属于文档而不属于实体，chunk 行 ID 在重建时会整批删除。 |
| **已有、不需新造** | 混合检索与 RRF、候选池预算、重排、证据快照、chunk 字符偏移、异步任务 claim+lease、预算与用量归账、检索诊断。 |

对方文本里有一句是对的：「核心资产不是 AGE，也不是三元组，而是规范化的实体、可信的关系、可追溯的证据，以及把这些知识重新带回原始 Chunk 的能力」。这份文档把这句话翻译成本仓可执行的东西。

---

## 1. 评审方法与证据口径

1. 先建立「本仓事实基线」（§2），逐项给出代码/迁移证据。
2. 再逐条核对对方断言（§3），每条给一个明确判定：**采纳 / 修正后采纳 / 否决 / 本仓已有**。
3. 冲突项单独列出代价（§5），不把「能写出来」等同于「能上线」。
4. 最后给修正版设计与分阶段门禁（§7、§8）。

证据约定：路径相对仓库根；`文件:行号` 指向当前工作区真实内容。对方方案内的判断以「对方：…」标注。

---

## 2. 本仓事实基线（决定方案能不能落地）

| # | 事实 | 证据 |
|---|------|------|
| 1 | **本仓没有 `VectorStore` / `PgVectorStore`**。向量检索是自研 JDBC SQL，不是 Spring AI 的向量抽象。 | 在 `spring-ai-rag-api` / `core` / `starter` 的 `src/main` 中检索 `VectorStore`、`PgVectorStore` 均无匹配。 |
| 2 | 混合检索 = 向量 + 全文，**RRF 已经实现且带权重与缩放**，`K=60`。 | [RetrievalUtils.java:17-18](../../spring-ai-rag-core/src/main/java/com/springairag/core/retrieval/RetrievalUtils.java#L17-L18)、[:230](../../spring-ai-rag-core/src/main/java/com/springairag/core/retrieval/RetrievalUtils.java#L230)；融合调用在 [HybridRetrieverService.java:437-441](../../spring-ai-rag-core/src/main/java/com/springairag/core/retrieval/HybridRetrieverService.java#L437-L441)。 |
| 3 | 向量维度**不是固定 1024**，而是 4 个维度列 + 每 Collection 一个活动 Profile，检索按 Profile 扇出（上限 4）。 | [V25__embedding_profile_expand.sql:26-27](../../spring-ai-rag-core/src/main/resources/db/migration/V25__embedding_profile_expand.sql#L26-L27)、[V67:8-10](../../spring-ai-rag-core/src/main/resources/db/migration/V67__durable_operations_and_embedding_profiles.sql#L8-L10)、[HybridRetrieverService.java:281](../../spring-ai-rag-core/src/main/java/com/springairag/core/retrieval/HybridRetrieverService.java#L281)、[RagRetrievalProperties.java:23](../../spring-ai-rag-core/src/main/java/com/springairag/core/properties/RagRetrievalProperties.java#L23)。 |
| 4 | 新鲜度是 SQL 里的一等条件：向量与 Profile 状态必须按 `embedding_profile_id` + `content_hash` 对齐。 | [EmbeddingProfileSqlScope.java:26-30](../../spring-ai-rag-core/src/main/java/com/springairag/core/retrieval/EmbeddingProfileSqlScope.java#L26-L30)。 |
| 5 | 证据快照只认 `VECTOR` / `KEYWORD` 两种 `indexKind`，其他值直接抛冲突。 | [EvidenceSnapshotGuard.java:31-56](../../spring-ai-rag-core/src/main/java/com/springairag/core/chat/EvidenceSnapshotGuard.java#L31-L56)。 |
| 6 | chunk 有 `local_index_generation`、`chunk_index`、`chunk_start_pos` / `chunk_end_pos`，可精确定位字符区间。 | [V43__decouple_keyword_chunks_from_embeddings.sql:6-38](../../spring-ai-rag-core/src/main/resources/db/migration/V43__decouple_keyword_chunks_from_embeddings.sql#L6-L38)。 |
| 7 | **重建会先删除该文档的全部 chunk 行再插入**，所以 chunk 行 ID 不稳定。 | [KeywordIndexPersistenceService.java:82](../../spring-ai-rag-core/src/main/java/com/springairag/core/service/KeywordIndexPersistenceService.java#L82)、[:157](../../spring-ai-rag-core/src/main/java/com/springairag/core/service/KeywordIndexPersistenceService.java#L157)。 |
| 8 | ACL 是**应用层 + SQL 谓词**（`ApiKeyCollectionAccess` 解析允许集合，`RetrievalScopeSql` 拼进查询），不是数据库 RLS。 | [ApiKeyCollectionAccess.java:27-31](../../spring-ai-rag-core/src/main/java/com/springairag/core/security/ApiKeyCollectionAccess.java#L27-L31)、[:131-161](../../spring-ai-rag-core/src/main/java/com/springairag/core/security/ApiKeyCollectionAccess.java#L131-L161)、[RetrievalScopeSql.java:60-62](../../spring-ai-rag-core/src/main/java/com/springairag/core/retrieval/RetrievalScopeSql.java#L60-L62)。 |
| 9 | 本仓**不是多租户平台**：只有 principal、API key、Collection 三层，没有 `tenant_id` / `knowledge_base_id` 概念。 | [security-boundary-zh-CN.md:12-18](../../docs/security-boundary-zh-CN.md#L12-L18)。 |
| 10 | 后台任务用**条件写入 claim + lease + fencing**，禁止悲观锁 / `SKIP LOCKED` / advisory lock。 | [EmbeddingJobWorker.java:133-158](../../spring-ai-rag-core/src/main/java/com/springairag/core/embeddingjob/EmbeddingJobWorker.java#L133-L158)、[AGENTS.md:41](../../AGENTS.md#L41)、[multi-instance-semantics-zh-CN.md:11-18](../../docs/multi-instance-semantics-zh-CN.md#L11-L18)。 |
| 11 | 每次模型调用必须过预算与用量账本，用途枚举当前只有 4 类。 | [BudgetedChatModel.java:28-31](../../spring-ai-rag-core/src/main/java/com/springairag/core/chat/BudgetedChatModel.java#L28-L31)、[LlmInvocationPurpose.java:6-11](../../spring-ai-rag-core/src/main/java/com/springairag/core/usage/LlmInvocationPurpose.java#L6-L11)。 |
| 12 | 官方开发容器是 **`pgvector/pgvector:pg16`**，没有 AGE；仓库也没有 `connection-init-sql` / `shared_preload_libraries` 配置。 | [docker-compose.yml:9](../../docker/docker-compose.yml#L9)；在 `docker/`、`k8s/`、`resources` 中检索 `connection-init-sql`、`shared_preload` 均无匹配。 |
| 13 | 检索分支诊断只有 `VECTOR` / `FULLTEXT` / `FUSION` / `RERANK` 四段。 | [RetrievalBranchStage.java:18-21](../../spring-ai-rag-core/src/main/java/com/springairag/core/retrieval/RetrievalBranchStage.java#L18-L21)。 |
| 14 | 候选池：10x overfetch、最小 100、**上限 1000**；检索 p50 约 50–90 ms，SQL 已接近常数。 | [capacity-and-scale-zh-CN.md:99](../../docs/capacity-and-scale-zh-CN.md#L99)、[:219](../../docs/capacity-and-scale-zh-CN.md#L219)、[:236](../../docs/capacity-and-scale-zh-CN.md#L236)。 |
| 15 | 重排候选上限来自 `rag.rerank.candidate-limit`，与 `topK` 分离。 | [HybridRetrieverService.java:1127-1142](../../spring-ai-rag-core/src/main/java/com/springairag/core/retrieval/HybridRetrieverService.java#L1127-L1142)。 |
| 16 | 文档写入完成后，检索经 `ProjectDocumentRetriever` → `HybridRetrieverService` → `RetrievalContextAssemblyPostProcessor`，后者依赖服务端授权的 `AuthorizedRetrievalContext`，并按 chunk 窗口扩展邻域。 | [ProjectDocumentRetriever.java:42-69](../../spring-ai-rag-core/src/main/java/com/springairag/core/rag/ProjectDocumentRetriever.java#L42-L69)、[RetrievalContextAssemblyPostProcessor.java:37-68](../../spring-ai-rag-core/src/main/java/com/springairag/core/rag/RetrievalContextAssemblyPostProcessor.java#L37-L68)。 |
| 17 | 接口兼容有 OpenAPI 基线门禁：新增可选字段不算破坏，删除/改类型/改语义算。 | [testing-guide-zh-CN.md:138](../../docs/testing-guide-zh-CN.md#L138)。 |

**基线结论**：本仓的检索不是「Spring AI 默认 RAG」，而是一套已经做过容量、并发、幂等、ACL、证据一致性的自研管线。任何新模块必须接进这套约束，而不是在旁边另起一套。

---

## 3. 逐条核对对方方案

### 3.1 总体架构与定位 — **采纳**

对方主张：图索引是入库分支；图检索是补充不是替代；两种检索最终回到原始 Chunk；不引入社区发现与全局摘要。

**核对结果：全部采纳，且与本仓现有分层天然契合。**

- 「入库分支」在本仓已有落点：分块结果写入 `rag_document_chunks`（[V43:6-38](../../spring-ai-rag-core/src/main/resources/db/migration/V43__decouple_keyword_chunks_from_embeddings.sql#L6-L38)），嵌入由后台任务消费。图谱抽取应作为**同一份 chunk 产物的第二个消费者**，不重跑解析、不重跑分块。
- 「回到原始 Chunk」在本仓是硬约束而不是建议：证据快照守卫只接受 VECTOR/KEYWORD（[EvidenceSnapshotGuard.java:31-56](../../spring-ai-rag-core/src/main/java/com/springairag/core/chat/EvidenceSnapshotGuard.java#L31-L56)），上下文装配也只对**已登记的项目文档 chunk** 做窗口扩展（[RetrievalContextAssemblyPostProcessor.java:37-68](../../spring-ai-rag-core/src/main/java/com/springairag/core/rag/RetrievalContextAssemblyPostProcessor.java#L37-L68)）。游离的三元组文本进不了这条链路。
- 「不做社区发现」正确。它解决的是全局摘要问题，而本仓当前诉求是跨文档实体关联。

**但对方少了一层本仓必须有、它没有提的东西：代际（generation）。** 图谱产物必须挂在文档当前代际上，否则旧事实会和新内容共存。见 §3.3 / §3.9。

### 3.2 存储与查询：AGE — **否决首发，保留为可选 SPI**

对方主张：AGE 是 PostgreSQL 扩展，可与 pgvector 共存；单一 `rag_kg` 图，用 `tenant_id` / `knowledge_base_id` 逻辑隔离；AGE 1.7.0 已支持 RLS；最后也承认「一到两跳未必比邻接表快，应做对照测试」。

**判定：技术判断基本正确，但作为「本仓第一版」不可接受。** 理由全部是本仓特有的，不是通用争论：

1. **框架级部署契约变更。** 本仓以 starter 形式被宿主应用引入。要求 AGE 等于要求所有宿主数据库安装扩展、配置 `shared_preload_libraries`、重启实例。这不是「可选模块」，这是全局前提。
2. **官方镜像不匹配。** 开发栈固定 `pgvector/pgvector:pg16`（[docker-compose.yml:9](../../docker/docker-compose.yml#L9)），AGE 需要另建镜像，牵动 `scripts/docker-build-local.sh`、Helm、境内网络构建文档（见 [china-network-guide-zh-CN.md](../../docs/china-network-guide-zh-CN.md)）。本仓明确禁止把区域镜像硬编码进 Dockerfile，AGE 会把这个成本再放大一层。
3. **与 Flyway 单线迁移冲突。** 扩展与 `ag_catalog` schema 必须在应用迁移之前存在；本仓迁移是单线 V1–V73（[AGENTS.md:33](../../AGENTS.md#L33)），且 `scripts/verify-flyway-schema-paths.sh`、`scripts/verify-clean-clone.sh` 会校验 schema 路径。把 AGE 塞进 Flyway 会让「克隆即跑」失效；不塞进 Flyway 又会出现「迁移记录干净但 schema 不干净」。
4. **没有任何连接初始化钩子。** AGE 查询需要在连接上设置 `search_path`，本仓检索是 `JdbcTemplate` + 默认 Hikari，没有 `connection-init-sql`（已核对无匹配）。要么加全局连接初始化（影响所有查询），要么每次查询前执行 `LOAD 'age'` / `SET search_path`（每次多一次往返，且污染 session 状态）。
5. **多实例与回滚成本。** AGE 投影写入需要 fencing；本仓已有 lease 模式（[EmbeddingJobWorker.java:133-158](../../spring-ai-rag-core/src/main/java/com/springairag/core/embeddingjob/EmbeddingJobWorker.java#L133-L158)），但 AGE 的图更新不是条件写入能直接表达的。邻接表可以用 `UPDATE … WHERE version = ?` 完成，AGE 需要额外投影版本列 + 重建整个图。
6. **备份恢复与运维。** 备份要同时覆盖扩展安装状态；恢复到不带 AGE 的目标库会失败。邻接表方案对这些脚本零改动。
7. **对方引用的「AGE 1.7.0 支持 RLS」在本仓没有意义。** 本仓根本不用数据库 RLS（[security-boundary-zh-CN.md:12-18](../../docs/security-boundary-zh-CN.md#L12-L18)），ACL 是应用层解析后拼 SQL 谓词。引入 RLS 会变成**第二套授权机制**，与既有单一路径冲突，且难以回归测试。

**结论（要拍板的技术决定）**：第一版用**普通邻接表 + 递归 CTE**；`GraphStore` 抽象保留，AGE 实现放到 Phase 3 作为可选 profile，用真实数据对照后再决定。对方自己也建议「把图存储接口抽象出来」，本评审只是把这个抽象提前到第一版，并把 AGE 从「首发」降为「候选」。

**顺带修正对方的规模判断**：它只给了「限制每跳分支数」，没有给硬上限。本仓必须给：2 跳 + 谓词白名单 + 每跳 `LIMIT` + 总节点上限 + 请求级 deadline，否则递归 CTE 在稠密实体上会直接打穿检索超时。

### 3.3 数据模型 — **修正后采纳（三处必须重写）**

对方给了 4 张核心表（`rag_kg_entity`、`rag_kg_entity_alias`、`rag_kg_fact`、`rag_kg_fact_evidence`）加一个建议的 `rag_kg_entity_mention`。骨架合理，但有 3 处与本仓事实直接冲突。

#### (1) `tenant_id` / `knowledge_base_id` 是虚构字段 — **必须删除**

本仓没有租户层级（[security-boundary-zh-CN.md:12-18](../../docs/security-boundary-zh-CN.md#L12-L18)）。真实身份是：principal、API key、Collection（可空关联）。

- 对应关系：`knowledge_base_id` → `collection_id`（**允许 NULL**，因为文档可以先入库后归集）；`tenant_id` → 不存在，删除。
- 但**不能只在实体上加 `collection_id` 就完事**。实体是跨文档聚合体：一个实体可能由 A Collection 和 B Collection 的文档共同支撑，而调用方只能看见 A。此时「实体属于哪个 Collection」这个问题本身没有答案。
- 所以 ACL 必须落在**证据 → 文档**这一层，而不是实体层。见 §3.9，这是本方案最大的 P0。

#### (2) `rag_kg_fact_evidence` 的 `chunk_id` + `chunk_version` 不可用 — **必须改成代际四元组**

本仓重建分块时执行 `DELETE FROM rag_document_chunks WHERE document_id = ?`，然后重新插入（[KeywordIndexPersistenceService.java:82](../../spring-ai-rag-core/src/main/java/com/springairag/core/service/KeywordIndexPersistenceService.java#L82)、[:157](../../spring-ai-rag-core/src/main/java/com/springairag/core/service/KeywordIndexPersistenceService.java#L157)）。含义：

- 行 ID 每次重建都会变，外键会悬空或被级联误删；
- `chunk_version` 在本仓没有对应列，真实维度是 `local_index_generation`（[V43:6-38](../../spring-ai-rag-core/src/main/resources/db/migration/V43__decouple_keyword_chunks_from_embeddings.sql#L6-L38)）。

证据表应存**稳定定位键**，而不是行 ID：

```
document_id
local_index_generation
chunk_index
char_start
char_end
content_hash
extraction_version
evidence_text
```

解析时按 `(document_id, local_index_generation, chunk_index)` 回查 chunk 行；若该代际已不存在，这条证据就是过期证据，必须能判定为 stale，而不是报外键错误。

#### (3) 缺少「抽取状态 / 运行 / 谓词词表」三张表 — **必须补**

对方的 `extraction_run_id` 只出现在证据表里，没有 run 实体，也没有文档级状态。结果就是：无法回答「这篇文档抽到哪了」「失败了要不要重试」「这次抽取花了多少 token」「换了谓词词表后哪些文档需要重抽」。

本仓已有同类范式可抄：`rag_document_local_index_state` 就是文档级索引状态（[V43:43-66](../../spring-ai-rag-core/src/main/resources/db/migration/V43__decouple_keyword_chunks_from_embeddings.sql#L43-L66)）。图谱应有一个平行的 `rag_kg_extraction_state`。

#### (4) 其余字段的判定

| 对方字段 | 判定 | 说明 |
|----------|------|------|
| `status`（ACTIVE/SUPERSEDED/INVALID） | 采纳 | 与「事实可失效但仍可追溯」一致。 |
| `valid_from` / `valid_until` | 采纳但不启用 | 只建列，不参与 v1 过滤；对方也说第一版不必复杂时序。 |
| `confidence REAL` | **降级** | 只能作为「模型自报信号」存储，不得当作过滤门槛。本仓对 score 的既有口径是「原始相似度 / 原始全文分数，不是校准概率」（见 [RetrievalResult.java:36-42](../../spring-ai-rag-api/src/main/java/com/springairag/api/dto/RetrievalResult.java#L36-L42)）。用未校准的 LLM 自评做硬过滤，会把正确但模型不自信的边全部删掉。 |
| `properties JSONB` | 采纳 | 字面量对象（版本号、数字、日期）先放这里，不建独立字面量节点，减少消歧面。 |
| `canonical_name` + `normalized_name` | 采纳 | 但需要唯一约束，且归一化规则要版本化，否则改规则后旧数据无法重算。 |
| `entity_mention`（建议项） | 采纳 | 这是图谱能回到 chunk 的关键，必须进 v1，不是可选。 |

### 3.4 抽取管线 — **采纳，补本仓的三条硬约束**

对方主张：Entity Extraction → Relation Extraction → Entity Resolution → Graph Construction；异步批处理、失败重试、按知识库开关、增量抽取、可重建。

**判定：流程正确，但缺本仓的并发、幂等、成本约束。**

1. **必须复用 claim + lease 任务范式，不许新造调度器。** 本仓后台任务的标准形态是数据库条件写入 claim、带过期 lease、fencing 旧 owner（[EmbeddingJobWorker.java:133-158](../../spring-ai-rag-core/src/main/java/com/springairag/core/embeddingjob/EmbeddingJobWorker.java#L133-L158)、[multi-instance-semantics-zh-CN.md:11-18](../../docs/multi-instance-semantics-zh-CN.md#L11-L18)）。对方只说「异步批处理」，没有多副本语义；如果实现成进程内线程池，多副本会重复抽取、重复计费。
2. **禁止悲观锁 / `SKIP LOCKED` / advisory lock**（[AGENTS.md:41](../../AGENTS.md#L41)）。AGE 方案下很容易顺手写 advisory lock 做图重建，这是明确违规。邻接表 + 唯一约束 + CAS 可以完全避开。
3. **模型调用必须过预算账本。** 抽取是新的调用用途，而 `LlmInvocationPurpose` 只有 4 个值（[LlmInvocationPurpose.java:6-11](../../spring-ai-rag-core/src/main/java/com/springairag/core/usage/LlmInvocationPurpose.java#L6-L11)）。必须新增一个用途并保证抽取走 [BudgetedChatModel.java:28-31](../../spring-ai-rag-core/src/main/java/com/springairag/core/chat/BudgetedChatModel.java#L28-L31)，否则抽取成本在用量账本里不可见，也没法被预算拦住。这是对方完全没提的。

**补充两条对方漏掉的生命周期规则：**

- **抽取失败必须与检索隔离。** 图谱是派生索引，抽取失败只能让图通道降级，不能让向量/全文检索报错。本仓已有「分支降级、不改变 HTTP 状态」的范式（`RetrievalBranchStage` 的 `UNAVAILABLE` / `TIMEOUT` / `CAPACITY_LIMITED`，[RetrievalBranchStage.java:23-28](../../spring-ai-rag-core/src/main/java/com/springairag/core/retrieval/RetrievalBranchStage.java#L23-L28)），图通道应接同一套。
- **重新抽取必须先发布新一代，再切换。** 对方写的是「撤销旧版本证据 → 重新计算事实状态」，这是原地破坏。正确做法：新代际写完后原子切换 `active_extraction_generation`，旧代际保留到切换成功；否则抽取期间检索会看到半成品图。

### 3.5 实体消歧 — **采纳方向，补「可逆」**

对方主张：精确匹配 → 别名 → 候选召回（trigram / 向量）→ LLM 语义消歧 → 固定 Entity ID；不确定时保留两个实体。

**判定：方向正确，但「保留两个实体」只解决了「错合并」，没解决「错分开」和「合并后反悔」。**

- 必须支持 **merge / split 的可逆操作**：合并要记录操作审计、迁移 alias、把 fact 的 subject/object 重指到规范 ID；拆分要能把被合并的实体还原并恢复其事实归属。
- 本仓已有 `pg_trgm` 作为全文可选策略（[RagRetrievalProperties.java:18](../../spring-ai-rag-core/src/main/java/com/springairag/core/properties/RagRetrievalProperties.java#L18)），候选召回可以复用该扩展，不要再引入新依赖。
- 消歧阈值不能凭经验拍。必须进入 goldenset 评测（`scripts/run-retrieval-goldenset.sh`），否则「消歧提升」只是主观感受。
- 对方建议「跨知识库实体链接需要明确授权」——这一点在本仓要更严格：跨 Collection 的实体合并会在 ACL 上产生信息聚合风险，v1 应**禁止跨 Collection 自动合并**，只允许同 Collection 内合并。

### 3.6 图检索与融合 — **本仓已有 RRF，不需要新造；但接口语义必须重写**

对方 Step 1 用 `vectorStore.similaritySearch(topK=30)`，Step 5 用 `1/(k+rank)` 的 RRF（k=60），最后交给现有 Reranker。

**核对结果：**

- **`VectorStore` 前提不存在。** 本仓没有该 Bean，检索入口是 [ProjectDocumentRetriever.java:42-69](../../spring-ai-rag-core/src/main/java/com/springairag/core/rag/ProjectDocumentRetriever.java#L42-L69) → [HybridRetrieverService.java:437-441](../../spring-ai-rag-core/src/main/java/com/springairag/core/retrieval/HybridRetrieverService.java#L437-L441)。对方给的示例代码不能照抄。
- **RRF 已经存在，但公式不同。** 本仓是带权重的缩放形式 `(K+1)*weight/(K+rank)`（[RetrievalUtils.java:230](../../spring-ai-rag-core/src/main/java/com/springairag/core/retrieval/RetrievalUtils.java#L230)），不是裸 `1/(K+rank)`。这意味着：
  - 直接加第三路而不重新定权，会让所有既有查询的 `score` 整体抬升；
  - `RetrievalResult.score` 的 OpenAPI 描述写死了「vector / fulltext 贡献」（[RetrievalResult.java:36-42](../../spring-ai-rag-api/src/main/java/com/springairag/api/dto/RetrievalResult.java#L36-L42)），加第三通道必须同步契约与基线。
- **对方建议的 `topK=30` 在本仓没有意义。** 本仓候选池由 `rerank.candidate-limit` 决定（[HybridRetrieverService.java:1141](../../spring-ai-rag-core/src/main/java/com/springairag/core/retrieval/HybridRetrieverService.java#L1141)），且 10x overfetch、上限 1000（[capacity-and-scale-zh-CN.md:99](../../docs/capacity-and-scale-zh-CN.md#L99)）。图种子数量应取 `min(graph.seed-limit, 已返回候选数)`，并优先用 `entity_mention` 而不是再调一次 LLM。
- **图候选必须解析回真实 chunk 行。** 这是本仓的硬约束：
  - `EvidenceSnapshotGuard` 只接受 `VECTOR` / `KEYWORD`（[:31-56](../../spring-ai-rag-core/src/main/java/com/springairag/core/chat/EvidenceSnapshotGuard.java#L31-L56)），新增 `GRAPH` kind 会被判冲突；
  - 上下文装配需要 `AuthorizedRetrievalContext` 与真实 chunk 窗口（[:37-68](../../spring-ai-rag-core/src/main/java/com/springairag/core/rag/RetrievalContextAssemblyPostProcessor.java#L37-L68)）；
  - 因此图通道输出不是「三元组文本」，而是「解析成功的 chunk + 它来自哪条物理索引的证据快照」。解析失败的图候选必须丢弃并计数，不能降级成自由文本。
- **诊断契约要扩。** 分支常量只有 4 个（[RetrievalBranchStage.java:18-21](../../spring-ai-rag-core/src/main/java/com/springairag/core/retrieval/RetrievalBranchStage.java#L18-L21)），新增图通道必须同时扩分支与 outcome，否则诊断会张冠李戴。
- **默认权重应为 0。** 先保证关闭时字节级等价（无新 SQL、无新字段、分数不变），再开启。对方的「第一版就融合」会让基线不可比，也不符合本仓「新能力默认启用但不得破坏既有路径」的兼容要求——图谱是**行为增强**，必须能回退到基线。

### 3.7 性能与成本 — **对方方向对，量化与上限缺失**

对方承认「AGE 不一定更快，一到两跳要做对照测试」，但没有给出任何预算。

本仓的现有基线是：检索 p50 约 50–90 ms，SQL 部分接近常数，候选池 1000 封顶（[capacity-and-scale-zh-CN.md:219](../../docs/capacity-and-scale-zh-CN.md#L219)）。

**必须补齐的量化口径：**

| 项 | 要求 |
|----|------|
| 图通道延迟 | 在 5 万 chunk / 20 万边的基准集上，p95 增量 ≤ 30 ms；超时则整通道降级，不拖垮主路径。 |
| 遍历规模 | 2 跳硬上限；每跳 `LIMIT`；总节点上限（建议 200）；谓词白名单；deadline 由既有检索超时统一控制。 |
| 抽取成本 | 按「chunk 数 × 每 chunk 输入 token × 单价」估算，必须能在开启前算出每 1000 chunk 的成本区间，并写入文档。 |
| 成本上限 | 每文档 token 上限、每 Collection 每日上限、失败重试次数上限、单次运行最长时间；超限即停并标记 `BUDGET_EXCEEDED`。 |
| 语料裁剪 | 对方提了「不是所有文档都值得抽取」，但没有策略。v1 应支持按文档大小 / 类型 / Collection 白名单；默认只对超过阈值且被显式开启的 Collection 抽取。 |

**成本是本方案最大的不确定性，不是 AGE。** 每 chunk 一次 LLM 抽取，在大语料上很可能比 embedding 贵一个数量级。没有上限就是不可上线。

### 3.8 迁移与运维 — **邻接表可行，AGE 不可行（见 §3.2）**

- 迁移只能新增 `V74+`，不能改写已执行迁移（[AGENTS.md:33](../../AGENTS.md#L33)）。
- 迁移里**不能做抽取**。抽取是长耗时、可失败、要计费的作业；迁移只建表和索引，作业由后台任务按开关补齐。
- 备份恢复：邻接表零改动；AGE 需要目标库预装扩展。
- 多实例：抽取走 lease；图投影写入走条件写入。AGE 的图更新缺乏等价 CAS 机制，需要额外版本列 + 全图重建，运维面更大。

### 3.9 权限与多租户 — **本方案最大的事实错误，必须重写**

对方写「只允许当前租户、当前知识库、当前权限范围内的实体及关系」「实际实现时，还要将租户、知识库和用户 ACL 条件放进查询」。这两句话在通用多租户系统里成立，在本仓不成立：

1. 本仓没有租户与知识库层级，只有 principal + Collection ACL（[security-boundary-zh-CN.md:12-18](../../docs/security-boundary-zh-CN.md#L12-L18)）。
2. ACL 是**请求级集合解析 + SQL 谓词注入**（[ApiKeyCollectionAccess.java:131-161](../../spring-ai-rag-core/src/main/java/com/springairag/core/security/ApiKeyCollectionAccess.java#L131-L161)、[RetrievalScopeSql.java:60-62](../../spring-ai-rag-core/src/main/java/com/springairag/core/retrieval/RetrievalScopeSql.java#L60-L62)），不是实体属性过滤。
3. **关键差异：实体与事实是跨文档聚合体，可见性不是它们的固有属性。** 同一条 fact 的证据可能来自 3 个文档，其中 1 个在当前 caller 不可见。若按「实体属于某 Collection」过滤，会出两种错：
   - 过滤太松：通过实体存在性、度数、路径长度泄漏不可见文档的信息（例如「某个不可见文档里提到的人」出现在邻居列表里）；
   - 过滤太严：可见文档支撑的事实被整条丢弃，召回反而下降。

**正确规则（P0）**：图通道只在「当前 caller 可见的文档集合」上构建子图。

- 种子实体：只允许来自可见文档的 `entity_mention`；
- 每一跳展开：边必须 `JOIN fact_evidence → documents`，并复用 `RetrievalScopeSql` 的谓词；
- 返回证据 chunk 前：再按文档 ACL 复核一次（对方说的「不能只依赖遍历过滤」是对的，但理由不同：这里是防聚合泄漏，不是防参数绕过）；
- 跨 Collection 自动合并实体：v1 禁止。

这条规则会让图通道的 SQL 比向量通道更长，但它是唯一能通过 [security-boundary-zh-CN.md](../../docs/security-boundary-zh-CN.md) 既有回归口径的实现方式。

### 3.10 前端与 API — **v1 不新增公开写接口**

对方没有讨论 API 面。本仓的约束是 OpenAPI 基线与 WebUI 设计语言（[testing-guide-zh-CN.md:138](../../docs/testing-guide-zh-CN.md#L138)）。

**建议**：v1 只做「检索内部能力 + 只读诊断」，不新增图谱管理写接口、不加 WebUI 页面。理由：

- 写接口会带来 ACL、幂等、审计、OpenAPI 基线扩张四份成本；
- 图谱是派生索引，本来就不该由人来手工维护事实；
- 只读诊断可以复用既有检索诊断返回，新增字段属于「新增可选字段」，不算破坏。

---

## 4. 站不住或需要补强的地方（优先级清单）

### P0（不做就不能上线）

1. `VectorStore.similaritySearch` 前提错误：本仓无该组件，示例代码不可落地。
2. `tenant_id` / `knowledge_base_id` 的 ACL 模型错误：会跨 Collection 泄漏实体存在性。
3. 证据表以 chunk 行 ID 为外键：重建即悬空（[KeywordIndexPersistenceService.java:82](../../spring-ai-rag-core/src/main/java/com/springairag/core/service/KeywordIndexPersistenceService.java#L82)）。
4. AGE 首发把可选能力变成全体宿主的部署前提（镜像、扩展、Flyway、备份恢复）。
5. 缺代际与 fencing：旧图存活、重复抽取、事实与内容不一致。

### P1（上线前必须补）

6. `confidence` 当过滤门槛：未校准，会系统性删掉正确边。
7. 缺 `extraction_run` 表与 `LlmInvocationPurpose` 新用途：成本不可见、不可封顶。
8. 实体合并不可逆：错合并无法回滚。
9. 删除只处理硬删除：本仓有软删/墓碑/Collection purge，图谱必须接同一失效链路，不能另开一条。
10. 无可观测指标：抽取覆盖率、失败率、图通道命中率、p95、图候选解析失败率。
11. 无回填与升级路径：换谓词词表 / 换抽取版本后如何重算。
12. 图候选解析失败的行为未定义。

### P2（可延后）

13. 谓词词表无版本与治理流程。
14. 每跳 degree cap 未给具体数。
15. 语料采样策略缺失（哪些文档值得抽取）。
16. 时序查询（`valid_from` / `valid_until`）只建列不启用，需明确「不承诺」。

---

## 5. 与本仓契约的冲突点与代价

| 契约 | 冲突 | 代价 / 处理 |
|------|------|-------------|
| 检索默认路径 | 加入第三通道会改变 `score` 分布 | 默认 `graph.weight=0`；开启前重跑 goldenset；`score` 语义写进文档与 OpenAPI 描述 |
| 证据快照 | 只认 VECTOR/KEYWORD（[EvidenceSnapshotGuard.java:31-56](../../spring-ai-rag-core/src/main/java/com/springairag/core/chat/EvidenceSnapshotGuard.java#L31-L56)） | 图候选必须解析为真实 chunk 并重建快照；**不新增 `GRAPH` kind** |
| 上下文装配 | 依赖服务端授权上下文与真实 chunk（[RetrievalContextAssemblyPostProcessor.java:37-68](../../spring-ai-rag-core/src/main/java/com/springairag/core/rag/RetrievalContextAssemblyPostProcessor.java#L37-L68)） | 图路径不得绕过 `ProjectDocumentRetriever` 直接塞 `Document` |
| OpenAPI 基线 | 新端点/改字段语义算破坏（[testing-guide-zh-CN.md:138](../../docs/testing-guide-zh-CN.md#L138)） | v1 不加端点；只在既有响应新增可选字段 |
| 容量曲线 | p50 50–90 ms 已是承诺（[capacity-and-scale-zh-CN.md:219](../../docs/capacity-and-scale-zh-CN.md#L219)） | 图通道必须同预算、可降级；容量文档新增一行实测 |
| Flyway 单线 | AGE 需预装扩展 | 邻接表可入 V74+；AGE 不能进 Flyway |
| 备份恢复 | AGE 扩展状态不在 dump 内 | 邻接表零改动；AGE 需运维前置条件 |
| 多实例语义 | 抽取必须跨副本唯一 | 复用 lease；禁止进程内调度器 |
| 并发约束 | 禁止悲观锁/`SKIP LOCKED`/advisory lock（[AGENTS.md:41](../../AGENTS.md#L41)） | 唯一约束 + CAS + 版本列 |
| 成本账本 | 抽取调用当前无法归因 | 新增 purpose；走 `BudgetedChatModel` |
| Starter 消费者 | 新增硬依赖会污染宿主 | `@ConditionalOnProperty`，默认关闭，零 AGE 依赖 |

---

## 6. 可行性边界：能落在哪一层

| 层 | 能做什么 | 不能做什么 |
|----|----------|------------|
| `spring-ai-rag-api` | 既有检索响应新增可选字段/枚举值（向后兼容） | 新增写端点；改既有字段语义 |
| `spring-ai-rag-core` | KG 领域包（抽取/消歧/存储/检索）、V74+ 迁移、后台任务、配置、指标 | 改动向量检索 SQL 的默认行为；引入 AGE 运行时依赖 |
| `spring-ai-rag-starter` | 条件化自动配置（默认关闭）；`GraphStore` SPI | 要求宿主安装扩展；默认启用高成本抽取 |
| `spring-ai-rag-documents` | 复用 chunk 产物作为抽取输入 | 新增解析器 / 重跑入库流程 |
| `spring-ai-rag-webui` | 后续只读诊断页 | v1 不改页面、不新增交互 |

**明确结论**：可以作为**可选模块**落在 `core + starter`，前提是三条硬边界——默认关闭、图结果必须回到 chunk 行、ACL 下推到证据文档层。越过任何一条，就会破坏检索默认路径或安全边界。

---

## 7. 修正后的 v1 设计（可直接拿去评审）

### 7.1 开关与配置

```
rag.kg.enabled=false                     # 总开关，默认关闭
rag.kg.extraction.enabled=false         # 抽取开关，可与检索分开
rag.kg.extraction.model-ref=...         # 复用多模型配置
rag.kg.extraction.max-chunks-per-document=500
rag.kg.extraction.max-tokens-per-document=...
rag.kg.extraction.daily-token-budget=...
rag.kg.retrieval.enabled=false          # 检索开关
rag.kg.retrieval.seed-limit=20
rag.kg.retrieval.max-hops=2
rag.kg.retrieval.max-nodes=200
rag.kg.retrieval.degree-cap=50
rag.kg.retrieval.weight=0.0             # 默认 0，保证基线等价
rag.kg.retrieval.timeout-ms=...
```

### 7.2 表结构（8 张，全部新增在 V74+）

| 表 | 关键列 | 说明 |
|----|--------|------|
| `rag_kg_extraction_state` | `document_id`、`content_hash`、`local_index_generation`、`extraction_version`、`status`、`chunk_count`、`error_code`、`updated_at` | 文档级状态；唯一键 `(document_id, content_hash, local_index_generation, extraction_version)`；`active_generation` 用于原子切换 |
| `rag_kg_extraction_run` | `run_id`、`document_id`、`extraction_version`、`model_ref`、`input_tokens`、`output_tokens`、`cost`、`outcome` | 成本与失败归因；与用量账本对齐 |
| `rag_kg_extraction_job` | `document_id`、`state`、`lease_owner`、`lease_until`、`attempts`、`next_attempt_at` | 复用 claim + lease 范式；禁止 `SKIP LOCKED` |
| `rag_kg_entity` | `id`、`collection_id`（可空）、`entity_type`、`canonical_name`、`normalized_name`、`properties`、`merged_into_id`、`status` | 跨文档聚合体；不承载 ACL |
| `rag_kg_entity_alias` | `entity_id`、`alias`、`normalized_alias`、`source` | 唯一键需含归一化名 |
| `rag_kg_fact` | `id`、`subject_entity_id`、`predicate`、`object_entity_id`、`properties`、`status`、`valid_from`、`valid_until`、`content_key` | `content_key` 用于幂等去重；`object_entity_id` 为空的字面量走 `properties` |
| `rag_kg_fact_evidence` | `fact_id`、`document_id`、`local_index_generation`、`chunk_index`、`char_start`、`char_end`、`content_hash`、`extraction_version`、`evidence_text` | **不引用 chunk 行 ID**；唯一键防重复证据 |
| `rag_kg_entity_mention` | `entity_id`、`document_id`、`local_index_generation`、`chunk_index`、`char_start`、`char_end`、`surface_form` | 种子召回与可解释性 |
| `rag_kg_predicate` | `name`、`description`、`is_symmetric`、`inverse_name`、`version`、`status` | 受控谓词词表 |

索引原则：所有查询先按文档可见性过滤，再按 `(subject_entity_id, predicate)` / `(object_entity_id, predicate)` 走索引；不允许在无界集合上做递归。

### 7.3 抽取状态机

```
PENDING → CLAIMED(lease) → RUNNING → (SUCCEEDED | FAILED | BUDGET_EXCEEDED | STALE)
                                      └→ 写新代际 → 原子切换 active_generation
```

- `STALE`：文档内容或分块代际在抽取期间变化，结果丢弃，重新入队。
- `FAILED`：有界重试（次数与退避写进配置），超过即停在 `FAILED`，不阻塞检索。
- 旧代际在切换成功前继续服务；切换后延迟清理。

### 7.4 检索算法（接进现有管线）

```
1. 现有向量 + 全文检索（保持不变）        → 候选 chunk（已应用 ACL 谓词）
2. 实体召回：从候选 chunk 的 mention 取实体 + query 的 alias 精确匹配（可选 LLM）
3. 子图展开：1–2 跳；每跳 JOIN fact_evidence → documents 并复用 RetrievalScopeSql
4. 证据解析：把 fact 证据映射为 (document_id, local_index_generation, chunk_index)
             → 回查 chunk 行 → 重建 VECTOR/KEYWORD 证据快照
5. 融合：三通道加权 RRF；图候选解析失败则丢弃并记 metric
6. 重排：沿用既有 rerank
7. 上下文装配：沿用既有窗口扩展；关系路径只作为有边界的元数据，不塞自由文本
```

关键点：第 4 步失败即丢弃，不允许「用三元组文本补位」。

### 7.5 失效与删除

- 文档软删 / 墓碑：立即让该文档证据不参与子图（SQL 条件），异步清理。
- Collection purge：复用既有 purge 语义；图谱清理必须幂等，且不能反向阻塞文档删除。
- 文档更新：内容 hash 或分块代际变化 → 旧代际标记 stale，不直接删事实（事实可能仍被其他文档支撑）。
- 事实失效：证据集合为空 → `INVALID`，保留历史。

### 7.6 可观测性

- 抽取：成功率、失败原因分布、平均 token、P95 耗时、积压量。
- 检索：图通道命中率、图候选解析失败率、图通道 p95、降级原因分布。
- 质量：goldenset 上的 Recall@K / nDCG@K / Evidence Coverage 对比向量基线。

---

## 8. 分阶段落地与验收门禁

### Phase 0：可行性探针（先证明值得做，不写生产代码）

- 用现有 goldenset 标注少量「跨文档实体关联」问题；
- 手工构造实体/关系（不跑 LLM 全量抽取），只验证「图通道能否把正确 chunk 拉回候选」；
- **验收**：在固定 K 下 Recall@K 相对向量基线有可判定提升，或证明无提升 → 停止投入。  
  这是最重要的一关，对方的 §9 也承认要先建评测集。

### Phase 1：数据与抽取（检索仍关闭）

- V74+ 迁移、8 张表、`GraphStore` SPI（邻接表实现）；
- 抽取任务 + 预算归账 + 代际切换；
- **验收门禁**：
  - `mvn test` 全绿、0 skipped；
  - `scripts/verify-concurrency-idempotency.py`：并发重复抽取不产生重复事实；
  - `scripts/verify-crash-consistency-e2e.py`：kill 后 lease 接管，无半成品图；
  - `scripts/verify-flyway-schema-paths.sh`、`scripts/verify-clean-clone.sh`；
  - `scripts/verify-no-pessimistic-locks.sh`；
  - 成本：单文档超限即 `BUDGET_EXCEEDED`，用量账本可见。

### Phase 2：图检索（默认关闭）

- 实体召回、子图展开、证据解析、三通道 RRF、诊断扩展；
- **验收门禁**：
  - 关闭时与基线**字节级等价**（分数、字段、SQL 路径不变）；
  - `scripts/run-retrieval-regression.sh` 不退化；
  - `scripts/run-retrieval-goldenset.sh` 开启后达标；
  - `scripts/verify-openapi-breaking-change.mjs` 通过；
  - `scripts/verify-security-boundary.py`：受限 key 不因图通道看到不可见文档的实体/事实；
  - 容量：图通道 p95 增量 ≤ 30 ms（5 万 chunk 基准），写入 [capacity-and-scale-zh-CN.md](../../docs/capacity-and-scale-zh-CN.md)。

### Phase 3：进阶（可选）

- AGE store 对照实现（同一个 SPI，真实数据 A/B）；
- 实体合并撤销 UI/接口；
- 时序事实查询；
- 谓词词表治理流程。

**Phase 3 的前置条件**：Phase 2 已证明收益；否则不做。

---

## 9. 风险与成本上限

| 风险 | 影响 | 缓解 |
|------|------|------|
| 抽取成本失控 | 不可上线 | 三重上限（文档/日/运行）+ 预算账本 + 默认关闭 |
| 实体消歧错误传播 | 多跳后给错证据 | 谓词白名单、最多 2 跳、confidence 不作硬门、可逆合并 |
| 图与内容不同步 | 幽灵事实 | 代际 + 内容 hash + stale 判定 |
| ACL 聚合泄漏 | 安全问题 | 只以可见文档建子图；证据层 ACL |
| 延迟拖垮主路径 | 检索承诺失效 | 独立 deadline、通道降级、默认权重 0 |
| 与另一会话的工作区冲突 | 误提交 | 本文件只新增，不改代码、不执行 git 写操作 |

---

## 10. 未决问题与需要拍板的决策

> 以下写成待决项，不阻塞评审流程；由维护者在进入 Phase 1 前决定并回填 §11。

**D1. 图存储首发选型**
- 选项 A：邻接表 + 递归 CTE 首发，AGE 作为 Phase 3 可选 SPI（本评审推荐）。
- 选项 B：AGE 首发。
- 决定依据：是否接受「所有 starter 宿主必须安装 AGE」这一部署前提。若答案是「不接受」，只能选 A。

**D2. 默认开关与成本责任**
- 图谱抽取是唯一会显著抬高运行成本的新能力。需要决定：默认关闭（按 Collection 显式开启）还是默认开启小范围采样。
- 本评审倾向：默认关闭；开启后必须有每日上限，超限必须停而不是排队。

**D3. 发布形态与对外接口**
- v1 是否只做内部检索能力（推荐），还是同时提供图谱查询/维护 API 与 WebUI 页面。
- 若要做 API，需先确认 OpenAPI 基线扩容与 ACL 审计的成本由谁承担。

**D4. 跨 Collection 实体合并策略**
- 本评审建议 v1 禁止跨 Collection 自动合并；若产品要求跨 Collection 关联，需要单独的安全评审（聚合泄漏）。

---

## 11. 记录在案的实现决策（Decision Log）

| ID | 决策 | 理由 | 状态 |
|----|------|------|------|
| DEC-1 | 图谱定位为「可重建的派生索引」，不替代向量检索 | 与对方核心判断一致；失败可降级 | 已收敛 |
| DEC-2 | 回答只用原始 chunk，三元组只做导航 | 证据快照守卫与上下文装配的硬约束 | 已收敛 |
| DEC-3 | 图存储第一版用邻接表 + 递归 CTE | 避免框架级部署前提；Flyway/镜像/备份零改动 | 待 D1 确认 |
| DEC-4 | 保留 `GraphStore` SPI，AGE 进 Phase 3 | 保留对方「可换实现」的价值，但不预付成本 | 已收敛 |
| DEC-5 | 证据表使用 `(document_id, generation, chunk_index, char ranges)` | chunk 行 ID 在重建时被整批删除 | 已收敛 |
| DEC-6 | 不新增 `GRAPH` 证据 kind | `EvidenceSnapshotGuard` 只接受 VECTOR/KEYWORD | 已收敛 |
| DEC-7 | ACL 下推到「证据 → 文档」层，实体不承载 ACL | 实体是跨文档聚合体，可见性属于文档 | 已收敛 |
| DEC-8 | `confidence` 只存不用作门 | 未校准；本仓既有口径不把 score 当概率 | 已收敛 |
| DEC-9 | 抽取复用 claim + lease，禁止悲观锁 | `AGENTS.md` 硬规则与多实例语义 | 已收敛 |
| DEC-10 | 抽取调用必须走预算账本并新增 purpose | 否则成本不可见、不可封顶 | 已收敛 |
| DEC-11 | 图检索默认权重 0，关闭时字节级等价 | 保护检索默认路径与容量承诺 | 已收敛 |
| DEC-12 | v1 不新增公开写接口与 WebUI 页面 | 控制 OpenAPI/ACL/审计成本 | 待 D3 确认 |

---

## 12. 证据索引

代码：

- `spring-ai-rag-core/src/main/java/com/springairag/core/retrieval/HybridRetrieverService.java:281`（Profile 扇出入口）、`:437-441`（RRF 融合）、`:1127-1142`（重排候选上限）、`:1302`（向量 `<=>` 排序）
- `spring-ai-rag-core/src/main/java/com/springairag/core/retrieval/RetrievalUtils.java:17-18`（K=60）、`:230`（加权 RRF 公式）
- `spring-ai-rag-core/src/main/java/com/springairag/core/retrieval/RetrievalScopeSql.java:60-62`（Collection 谓词）
- `spring-ai-rag-core/src/main/java/com/springairag/core/retrieval/EmbeddingProfileSqlScope.java:26-30`（Profile 新鲜度）
- `spring-ai-rag-core/src/main/java/com/springairag/core/retrieval/RetrievalBranchStage.java:18-28`（分支与结果码）
- `spring-ai-rag-core/src/main/java/com/springairag/core/chat/EvidenceSnapshotGuard.java:31-56`（VECTOR/KEYWORD）
- `spring-ai-rag-core/src/main/java/com/springairag/core/chat/BudgetedChatModel.java:28-31`
- `spring-ai-rag-core/src/main/java/com/springairag/core/usage/LlmInvocationPurpose.java:6-11`
- `spring-ai-rag-core/src/main/java/com/springairag/core/security/ApiKeyCollectionAccess.java:27-31`、`:131-161`
- `spring-ai-rag-core/src/main/java/com/springairag/core/service/KeywordIndexPersistenceService.java:82`、`:157`
- `spring-ai-rag-core/src/main/java/com/springairag/core/rag/ProjectDocumentRetriever.java:42-69`
- `spring-ai-rag-core/src/main/java/com/springairag/core/rag/RetrievalContextAssemblyPostProcessor.java:37-68`
- `spring-ai-rag-core/src/main/java/com/springairag/core/embeddingjob/EmbeddingJobWorker.java:133-158`
- `spring-ai-rag-core/src/main/java/com/springairag/core/properties/RagRetrievalProperties.java:11-23`
- `spring-ai-rag-api/src/main/java/com/springairag/api/dto/RetrievalResult.java:15-19`、`:36-42`

迁移与部署：

- `spring-ai-rag-core/src/main/resources/db/migration/V25__embedding_profile_expand.sql:26-27`
- `spring-ai-rag-core/src/main/resources/db/migration/V43__decouple_keyword_chunks_from_embeddings.sql:6-38`、`:43-66`
- `spring-ai-rag-core/src/main/resources/db/migration/V67__durable_operations_and_embedding_profiles.sql:8-10`
- `docker/docker-compose.yml:9`

文档：

- `docs/security-boundary-zh-CN.md:12-18`
- `docs/multi-instance-semantics-zh-CN.md:11-18`
- `docs/capacity-and-scale-zh-CN.md:99`、`:219`、`:236`
- `docs/testing-guide-zh-CN.md:138`
- `AGENTS.md:33`、`:41`（规则 11：境内构建网络；规则 15：禁止悲观锁）

---

## 13. 下一步（若决定继续）

1. 确认 D1/D2/D3/D4。
2. 用 Phase 0 探针证明收益；无收益则归档本稿，不做实现。
3. 若收益成立，再按 [delivery-workflow-zh-CN.md](../../docs/delivery-workflow-zh-CN.md) 走「规划 3/3 → 实施 → 基本集成硬门槛 → 限定范围实现收敛 → Git 交付」。
