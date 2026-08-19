# 多 Collection 检索范围与性能调研

> 状态：Research complete / 改进建议待实施
> 调研日期：2026-08-15；通俗说明补充：2026-08-16
> 代码基线：`main` @ `f02afeee87c070bd23c6eb63952fe44c13d8349c`
> 范围：Chat、Search、JSON structured records、API Key ACL、WebUI、向量/全文/混合检索 SQL
> 目标：回答“能否指定若干 Collection 作为检索范围”“能否检索任意 Collection 中的文档”以及“能否高效完成”三个问题，并给出可实施、低风险的演进建议

## 1. 执行结论

### 1.1 当前能力

当前后端已经支持：

1. Chat 和 Search 通过非空 `collectionKeys: string[]` 指定一个或多个 Collection。
2. 多个 Collection 的语义是候选文档集合的**并集**，然后执行一次全局 top-K 检索。
3. unrestricted 调用方省略 Collection 范围时，检索全部可检索文档。
4. restricted API Key 省略 Collection 范围时，后端自动使用该 Key 的 Collection allow-list。
5. 同时提供 `documentIds` 时，与 Collection 范围取交集。
6. 显式空 Collection 列表返回 `400`；未知或未授权 key 按既有 ACL 规则 fail closed。
7. JSON structured-record search 也支持多个 Collection，但要求显式提供范围。

当前 WebUI 没有完整暴露后端能力：

- Chat 和 Search 都是单选下拉框，一次只能选择一个 Collection。
- 下拉框只加载前 200 个 Collection。
- “All Collections” 实际通过省略范围实现；对 unrestricted 调用方，它包含未归属任何
  Collection 的文档，因此更准确的名称是“All retrievable documents”。

### 1.2 当前性能判断

| 场景 | 当前功能 | 当前效率判断 |
|---|---|---|
| unrestricted，省略范围 | 支持 | 相对高效；不展开 Collection 或 document IDs，查询具备使用 Profile HNSW/全文索引的条件，实际计划由 PostgreSQL planner 决定 |
| restricted，省略范围 | 支持 | 功能正确；仍会把 allow-list 中所有 Collection 展开为完整 document ID 列表 |
| 指定少量 Collection，且总文档数较少 | 支持 | 可接受的 MVP |
| 指定多个大 Collection | 支持 | 不具备可预测的规模效率，不应宣称高效 |
| JSON record 多 Collection | 支持 | 与普通检索有相同扩展问题，还会先加载全部候选 record IDs |
| 每个选中 Collection 都必须贡献结果 | 不支持 | 当前只保证并集范围，不保证逐 Collection 覆盖 |
| 只检索“当前归属任意 Collection”的文档 | 无精确显式模式 | 省略范围会额外包含 `collection_id IS NULL` 文档 |

关键瓶颈不是 Collection key 本身，而是当前执行链：

```text
collectionKeys
  -> internal collection IDs
  -> SELECT every matching rag_documents.id
  -> load all IDs into JVM
  -> generate document_id IN (?, ?, ... )
  -> run vector and full-text queries with the same large parameter list
```

这使检索前置成本至少与范围内文档总数线性增长，并给 ANN 过滤召回、SQL 解析、JDBC
参数、网络传输和 JVM 内存带来额外风险。

### 1.3 推荐决策

推荐按以下顺序演进：

1. **保留现有 API 兼容语义**：省略范围继续表示调用方默认可见范围。
2. **增加显式范围模式**：区分“全部可见文档”“任意 Collection 中的文档”和“指定
   Collection 并集”。
3. **停止把 Collection 展开为 document IDs**：检索 SQL 已经 JOIN `rag_documents`，
   应直接按 `d.collection_id` 过滤。
4. **批量解析 Collection keys**：unrestricted 多 key 从 K 次查询收敛为一次查询。
5. **为 Chat/Search 增加显式列表数量上限**：推荐最多 100 个 Collection，与 API Key
   allow-list 上限保持一致。
6. **为 HNSW 过滤启用受控 iterative scan**：要求 pgvector `>= 0.8.0`，并通过真实
   recall/latency 基准确定 `strict_order`、`ef_search` 和扫描上限。
7. **WebUI 改为可搜索、分页的多选器**，同时把“全部文档”和“任意 Collection”分开。
8. **逐 Collection 保底召回作为独立可选模式**，不要改变默认全局 top-K。

第一阶段不需要修改数据库 schema。只有真实 `EXPLAIN (ANALYZE, BUFFERS)` 证明现有
索引不足时，才增加覆盖索引或讨论 embedding 表分区/冗余 Collection ID。

## 2. 需要区分的三种产品语义

用户问题中的“任意 Collection”可能有三种含义，不能用一个模糊的“All Collections”
选项同时表示。

### 2.1 先区分两个独立问题：范围与覆盖

理解本方案的关键，是先把下面两个问题分开：

1. **哪些文档有资格进入候选集？** 这是检索**范围**问题，由
   `CollectionScopeMode` 表达。
2. **候选结果是否要照顾每个 Collection？** 这是检索**覆盖**问题，由
   `CollectionCoverageMode` 表达。

`ANY_COLLECTION` 回答第一个问题，`EACH_COLLECTION` 回答第二个问题。它们不是同一
层级的互斥选项。

可以把一次检索理解为比赛：

- Scope 决定哪些文档有资格参赛；
- Coverage 决定是否要求每个选中的 Collection 都先派出候选；
- 最后的 top-K / rerank 决定哪些候选进入最终结果。

### 2.2 当前普通文档可以不属于任何 Collection

当前数据模型允许 `rag_documents.collection_id = NULL`：

- [`V1__init_rag_schema.sql`](../../../spring-ai-rag-core/src/main/resources/db/migration/V1__init_rag_schema.sql)
  中 `collection_id` 是可空外键；
- [`RagDocument`](../../../spring-ai-rag-core/src/main/java/com/springairag/core/entity/RagDocument.java)
  的 `collectionId` 没有 `nullable = false`；
- 普通 Document 创建、上传、PDF 和批量入口允许调用方不提供
  `collectionId` / `collectionKey`；
- 软删除 Collection 时，
  [`RagCollectionService`](../../../spring-ai-rag-core/src/main/java/com/springairag/core/service/RagCollectionService.java)
  会保留文档并批量清空它们的 `collection_id`。

因此，未归属文档主要来自两类场景：

1. 创建或上传普通文档时没有指定 Collection；
2. 文档原本属于某个 Collection，但该 Collection 后来被软删除并解除关联。

存在两个边界：

- JSON structured record 不是普通可选归属：当前 upsert 要求恰好指定一个 Collection；
- restricted API Key 写入时，若 allow-list 只有一个 Collection，后端会自动采用它；
  若有多个，则调用方必须明确指定，不能通过该受限 Key 创建未归属文档。

这解释了为什么现有“省略范围”不能等同于“所有 Collection”：

```text
CALLER_VISIBLE（unrestricted）
= 所有可检索文档
= 已归属 Collection 的文档 + 未归属文档
```

而建议新增的：

```text
ANY_COLLECTION
= 所有仍归属于某个 Collection 的可检索文档
= collection_id IS NOT NULL
```

### 2.3 指定若干 Collection，结果可来自其中任意一个

示例：

```json
{
  "query": "退款规则",
  "collectionKeys": [
    "customer-42:manual:v3",
    "customer-42:faq:v2"
  ]
}
```

目标候选集：

```text
documents(manual) UNION documents(faq)
```

然后对并集执行一次全局 top-K。当前后端已经实现该语义。

一个文档当前最多归属一个 Collection，因此这里不是“文档同时属于所有选中
Collection”的 AND 语义。

该语义对应建议中的 `SELECTED_COLLECTIONS + GLOBAL_TOP_K`。

### 2.4 所有当前归属某个 Collection 的文档

目标 SQL 语义近似：

```sql
d.collection_id IS NOT NULL
```

当前没有独立 API 模式表达该语义。unrestricted 调用方省略范围时会检索所有可检索
文档，包括：

- 当前归属某个 Collection 的文档；
- 从未归属 Collection 的文档；
- Collection 软删除时被解除关联的文档。

因此，现有 WebUI 的“All Collections”标签比实际语义更窄。

`ANY_COLLECTION` 的“ANY”表示：

> 一篇文档只要归属于任意一个 Collection，就有资格参加这次全局检索。

它不要求每个 Collection 都出现在结果中，也不需要调用方枚举所有 Collection key。

### 2.5 每个选中 Collection 都要参与召回

示例要求：

- 选择 5 个 Collection；
- 每个 Collection 至少尝试返回 2 个候选；
- 最后统一 rerank 并返回全局 top-K。

当前实现不保证这一点。一个高相关 Collection 可以占据所有 top-K 结果。

这是“覆盖模式”，与普通范围过滤不同。建议以后用独立字段表达：

```json
{
  "collectionScopeMode": "SELECTED_COLLECTIONS",
  "collectionKeys": ["a", "b", "c"],
  "collectionCoverageMode": "EACH_COLLECTION"
}
```

默认仍应为 `GLOBAL_TOP_K`，因为它更快，也更符合一般相关性检索。

`EACH_COLLECTION` 的“EACH”表示：

> 对每一个明确选中的 Collection 分别尝试召回候选，再统一融合和排序。

它保证的是“分别尝试召回”，不应为了配额强行返回明显无关的低质量文档。如果某个
Collection 没有相关内容，可以不贡献最终结果。

### 2.6 `ANY_COLLECTION` 与 `EACH_COLLECTION` 对比

| 对比项 | `ANY_COLLECTION` | `EACH_COLLECTION` |
|---|---|---|
| 所属维度 | Scope：候选资格 | Coverage：候选分配 |
| 核心问题 | 文档是否归属于某个 Collection | 每个选中 Collection 是否都尝试召回 |
| 是否需要显式 keys | 不需要 | 通常需要明确选择 Collection |
| 执行方式 | 所有合格文档统一检索一次 | 每个 Collection 独立取候选，再融合/rerank |
| 是否保证每个 Collection 参与 | 不保证 | 尝试保证 |
| 典型 SQL/执行 | `d.collection_id IS NOT NULL` | bounded fan-out / LATERAL 等分组候选方案 |
| 成本 | 接近一次普通范围检索 | 随 Collection 数增加，成本更高 |
| 推荐限制 | 受调用方可见范围约束 | 初始最多 20 个 Collection |

假设选择 A、B、C，最终需要 5 条结果：

```text
A 的内容相关性很高
B 的内容相关性一般
C 没有相关内容
```

默认 `GLOBAL_TOP_K` 可能返回：

```text
A1、A2、A3、A4、A5
```

范围确实包含 A、B、C，但 B、C 没有获得结果。使用 `EACH_COLLECTION` 时，可以先执行：

```text
A 召回最多 2 条候选
B 召回最多 2 条候选
C 召回最多 2 条候选
将候选统一融合/rerank，再返回最终 top-K
```

C 没有相关内容时不需要强行补齐。推荐支持的组合是：

| Scope | Coverage | 行为 |
|---|---|---|
| `CALLER_VISIBLE` | `GLOBAL_TOP_K` | 所有调用方可见文档统一竞争 |
| `ANY_COLLECTION` | `GLOBAL_TOP_K` | 所有已归属 Collection 的可见文档统一竞争 |
| `SELECTED_COLLECTIONS` | `GLOBAL_TOP_K` | 指定 Collection 的并集统一竞争 |
| `SELECTED_COLLECTIONS` | `EACH_COLLECTION` | 每个指定 Collection 分别召回，再统一排序 |

初期不应开放 `ANY_COLLECTION + EACH_COLLECTION`。该组合可能要求对调用方可见的数百
甚至数万个 Collection 分别执行检索，成本不可控。

## 3. 当前 API 与 UI 能力矩阵

### 3.1 Chat

端点：

- `POST /api/v1/rag/chat`
- `POST /api/v1/rag/chat/ask`
- `POST /api/v1/rag/chat/stream`

`ChatRequest` 已包含：

```java
List<String> collectionKeys;
List<Long> collectionIds;   // deprecated
List<Long> documentIds;
```

Controller 在同步与 SSE 路径都先调用
`ApiKeyCollectionAccess.resolveCollectionIds(...)`，再由
`RagChatService.resolveRetrievalScope(...)` 展开为 document IDs。

结论：

- 后端支持多个 Collection；
- 同步与 SSE 语义一致；
- WebUI Chat 当前只发送零个或一个 key。

### 3.2 Search

端点：

- `GET /api/v1/rag/search`
- `POST /api/v1/rag/search`

GET 使用重复 query parameter：

```text
collectionKeys=a&collectionKeys=b
```

POST 使用数组：

```json
{
  "query": "Spring AI",
  "collectionKeys": ["a", "b"]
}
```

两条路径都把 Collection 解析为 document IDs，再传给
`HybridRetrieverService.search(...)`。

结论：

- 后端支持多个 Collection；
- GET/POST 均支持；
- WebUI Search 当前只发送零个或一个 key。

### 3.3 JSON structured records

端点：

- `POST /api/v1/rag/json-records/search`

范围必填，`collectionKeys` 与 `collectionIds` 都限制为最多 50 个。

当前执行链：

```text
Collection scope
  -> findEnabledIdsByCollectionIdsAndDocumentType(...)
  -> candidate JSON record document IDs
  -> HybridRetrieverService.search(...candidateIds...)
  -> reload documents and JSONB payload
```

它不会执行全库 JSON record 搜索。这是合理的租户/业务隔离默认值，但同样会在大范围时
生成大型 document ID 候选列表。

### 3.4 API Key ACL

当前规则：

| 调用方 | 省略范围 | 显式范围 |
|---|---|---|
| unrestricted | `null`，不加 Collection 过滤 | 按请求解析 |
| restricted | 自动替换为 allow-list | 必须是 allow-list 子集 |

restricted 的未知或未授权 key 返回 `403`，避免通过 `404/403` 差异枚举 Collection。

API Key 创建时最多允许 100 个 Collection key/ID。这个上限没有同步应用到普通 Chat 和
Search 请求。

## 4. 当前执行链与复杂度

### 4.1 通俗示例：为什么展开全部 document ID 不适合大 Collection

假设调用方选择两个 Collection：

```text
Collection A：10 万篇文档
Collection B：20 万篇文档
```

当前实现不是直接告诉检索 SQL“只查 A 和 B”，而是先执行：

```text
collectionKeys: [A, B]
  -> 解析成两个内部 collection IDs
  -> 查询 A、B 下全部 30 万个 document ID
  -> 把 30 万个 Long 加载到 JVM
  -> 生成 document_id IN (?, ?, ... 30 万个参数)
  -> Vector 和 Full-text 查询分别携带完整过滤条件
```

这意味着真正开始相似度搜索之前，系统已经产生与范围内文档总数成比例的工作：

- PostgreSQL 要查询并返回全部 document ID；
- JVM 要分配内存保存完整列表；
- JDBC 要绑定大量参数；
- SQL 文本形状、解析和网络传输成本增加；
- Vector 与 Full-text 并行分支会重复携带过滤范围；
- HNSW 近似候选经过高选择性过滤后，可能出现结果不足或 recall 降低。

真正需要表达的业务条件其实只是：

```sql
文档属于 Collection A 或 Collection B
```

由于检索 SQL 已经 JOIN `rag_documents d`，推荐直接下推为：

```sql
AND d.collection_id = ANY (?::bigint[])
```

绑定参数只包含：

```text
[Collection A 的内部 ID, Collection B 的内部 ID]
```

无论 A、B 各有多少文档，参数数量都只与 Collection 数相关，不再与文档总数相关。
`ANY_COLLECTION` 则更简单：

```sql
AND d.collection_id IS NOT NULL
```

这项修改可以消除 document ID 预加载和大型 `IN (...)`，但不能据此承诺所有查询都会
自动高效。PostgreSQL 是否使用理想索引、HNSW 过滤后的 recall 和延迟，仍必须通过真实
`EXPLAIN (ANALYZE, BUFFERS)` 与基准测试确认。

### 4.2 Collection key 解析

unrestricted 多 key 当前在 `CollectionIdentityResolver.resolveActiveIds(...)` 中逐 key
调用 `findByCollectionKeyAndDeletedFalse(...)`。

复杂度：

```text
K 个 key -> K 次数据库查询
```

restricted 路径会一次加载 allow-list 对应的 Collection，再在内存中映射 key。由于 API
Key allow-list 最多 100 个，这条路径有上界，但仍应避免重复加载。

推荐：

- Repository 增加 active key 批量查询；
- 一次查询返回全部匹配 Collection；
- 按请求顺序重建结果；
- 任一 key 缺失时保持当前 404/403 语义；
- 去重后再进入后续范围计算。

### 4.3 Collection 展开为 document IDs

`CollectionDocumentResolver` 使用：

```java
SELECT d.id
FROM RagDocument d
WHERE d.collectionId IN :collectionIds
```

然后把所有 ID 加载到 Java `List<Long>`。

若同时提供显式 `documentIds`，当前交集实现对每个请求 ID 调用
`idsFromCollections.contains(id)`。由于 `idsFromCollections` 是 List，最坏复杂度为：

```text
O(requestDocumentCount * collectionDocumentCount)
```

短期即使不改 SQL，也至少应改为 `HashSet`。但推荐的最终方案是把交集保留在 SQL 中，
不再加载完整 Collection 成员列表。

### 4.4 Vector 查询

`HybridRetrieverService` 当前生成：

```sql
...
AND e.document_id IN (?, ?, ...)
ORDER BY e.embedding_1024 <=> CAST(? AS vector)
LIMIT ?
```

现有正面条件：

- 活动 Profile 有专属 partial HNSW；
- Profile predicate 使用固定值，可匹配 partial index；
- freshness state、文档 content hash 和 `d.enabled=true` 已在统一 SQL scope 中；
- 不限定 Collection 时没有大型参数列表。

现有风险：

1. 大型 `IN` 列表增加 SQL 文本、bind、解析和网络成本。
2. vector 与 full-text 并行执行时，两条查询各自携带完整列表。
3. HNSW 是近似索引；普通过滤条件可能在近似扫描后应用，选择性范围会造成返回不足或
   recall 降低。
4. 当前没有按范围选择 exact scan / ANN scan 的策略。
5. 当前性能测试 mock 了 JdbcTemplate，不覆盖数据库执行计划和 ANN recall。

pgvector 官方文档说明：

- 近似索引过滤默认在索引扫描后应用；
- 可以增加 `hnsw.ef_search`；
- pgvector 0.8.0 起支持 iterative index scans；
- 少量固定过滤值可考虑 partial index，大量不同值可考虑 partitioning。

参考：

- [pgvector Filtering](https://github.com/pgvector/pgvector#filtering)
- [pgvector Iterative Index Scans](https://github.com/pgvector/pgvector#iterative-index-scans)

2026-08-15 本机当前 `pgvector/pgvector:pg16` 镜像实测扩展版本为 `0.8.1`，支持
iterative scan；但 CI 和 Testcontainers 使用浮动 `pg16` 标签，生产最低版本没有被代码
门禁，因此不能默认所有环境都具备该能力。

### 4.5 Full-text 查询

pg_jieba、English FTS 和 pg_trgm 都使用同一个 document ID 过滤模式：

```sql
AND e.document_id IN (?, ?, ...)
AND <GIN/trgm predicate>
```

现有 GIN/trgm 索引可用于文本条件，`rag_embeddings.document_id` 也有 B-tree。实际是否
能得到理想 Bitmap/Join 计划取决于范围选择性和统计信息，必须用真实
`EXPLAIN (ANALYZE, BUFFERS)` 验证。

直接按 `d.collection_id` 过滤至少可以消除 ID 展开和大型 bind 列表；是否需要新增
covering index，应由执行计划决定。

### 4.6 不限定 Collection 的全局范围

unrestricted 调用方省略范围时：

```text
collectionIds = null
documentIds = null
```

检索 SQL 不生成 Collection/document filter，可由 PostgreSQL planner 在活动 Profile
范围内选择 HNSW/全文索引。这是当前最短执行路径，但是否实际使用索引仍应以
`EXPLAIN` 为准。

它仍会 JOIN freshness state 和 documents，但不会产生与 Collection 文档总数成比例的
Java 前置工作。

注意：这条路径包括 `collection_id IS NULL` 文档。

## 5. “是否可以高效做到”的准确回答

### 5.1 可以立即使用的场景

以下场景当前可以直接使用：

- 调用方指定 2-10 个 Collection；
- 范围内文档总数不大；
- top-K 较小；
- 不要求每个 Collection 都出结果；
- 已接受当前 MVP 的参数与 ANN 过滤边界。

API 调用方应直接发送多个 key，不要把 key 转回内部 ID。

### 5.2 不应直接承诺高效的场景

以下场景需要先实施改进和压测：

- 一个 Collection 有数万或更多文档；
- 受限 API Key 默认允许很多大 Collection；
- 调用方发送几十到上百个 Collection；
- JSON record 范围包含大量记录；
- 高选择性过滤下仍要求稳定 recall@K；
- `limit` 较大或高并发；
- 要求每个 Collection 有最低召回配额。

### 5.3 “全部 Collection”当前应如何调用

如果调用方真正想要“全部可检索文档”：

- unrestricted：省略 `collectionKeys`/`collectionIds`；
- restricted：同样省略，后端自动使用 allow-list。

不要先列出所有 Collection 再把全部 key 发回服务端。这样会触发 key 解析、Collection
成员展开和大型 SQL 参数，效率更差，还存在列表分页与并发变化问题。

如果调用方想要“只包含当前归属某个 Collection 的文档，不包含 unassigned 文档”，当前
没有精确模式，应等待下述 `ANY_COLLECTION` 能力。

## 6. 推荐的外部契约

### 6.1 保持兼容的默认推导

现有请求继续有效：

| 输入 | 推导模式 |
|---|---|
| 省略 mode 和 Collection 字段 | `CALLER_VISIBLE` |
| 非空 `collectionKeys` | `SELECTED_COLLECTIONS` |
| deprecated 非空 `collectionIds` | `SELECTED_COLLECTIONS` |
| 显式空列表 | `400` |

### 6.2 新增显式模式

推荐新增：

```java
enum CollectionScopeMode {
    CALLER_VISIBLE,
    ANY_COLLECTION,
    SELECTED_COLLECTIONS
}
```

语义：

| 模式 | unrestricted | restricted |
|---|---|---|
| `CALLER_VISIBLE` | 全部可检索文档，包括 unassigned | allow-list 中的文档 |
| `ANY_COLLECTION` | `collection_id IS NOT NULL` 的可检索文档 | allow-list 中的文档 |
| `SELECTED_COLLECTIONS` | 指定 Collection 并集 | 指定范围必须是 allow-list 子集 |

请求示例：

```json
{
  "query": "退款规则",
  "collectionScopeMode": "SELECTED_COLLECTIONS",
  "collectionKeys": ["manual:v3", "faq:v2"]
}
```

```json
{
  "query": "退款规则",
  "collectionScopeMode": "ANY_COLLECTION"
}
```

冲突规则：

- `SELECTED_COLLECTIONS` 必须提供非空 keys 或 deprecated IDs。
- `ANY_COLLECTION` 和 `CALLER_VISIBLE` 不得同时提供 keys/IDs。
- 同时提供 key 与 ID 时继续要求集合一致。
- restricted 调用方不能用 mode 绕过 allow-list。

不推荐使用 `"*"` 作为特殊 collection key。合法业务 key 允许可见 ASCII，通配哨兵会
污染 key 命名空间并增加 ACL 歧义。

### 6.3 可选的覆盖模式

只有产品明确要求“每个 Collection 都尝试召回”时，再新增：

```java
enum CollectionCoverageMode {
    GLOBAL_TOP_K,
    EACH_COLLECTION
}
```

默认 `GLOBAL_TOP_K`。

`CollectionScopeMode` 与 `CollectionCoverageMode` 应作为两个独立维度：

- Scope 先确定有资格参与检索的文档；
- Coverage 再决定是否按 Collection 分配候选配额；
- `EACH_COLLECTION` 初期只允许与 `SELECTED_COLLECTIONS` 组合；
- `ANY_COLLECTION + EACH_COLLECTION` 初期返回 400，避免对全部可见 Collection
  执行无界 fan-out。

`EACH_COLLECTION` 还需要：

- `perCollectionCandidateLimit`；
- Collection 数量硬上限，建议初始最多 20；
- 查询 embedding 只计算一次；
- 每个 Collection 独立取候选后统一融合/rerank；
- 明确“无相关文档时不保证强行返回低质量结果”。

## 7. 推荐的内部架构

### 7.1 引入统一 RetrievalScope

不要继续把“有范围”降维成 document ID list。推荐内部模型：

```java
record RetrievalScope(
        CollectionScopeMode mode,
        List<Long> collectionIds,
        List<Long> documentIds,
        String documentType,
        boolean filterRequested) {
}
```

要求：

- `null` 与 empty 有明确不同语义；
- `filterRequested=true` 且有效范围为空时直接返回空结果；
- ACL 在构造 scope 时完成；
- SQL 层只接收内部 Long ID；
- Collection key 永远不直接进入检索 SQL。

Chat、Search 和 JSON record 共用同一 resolver，避免三条路径继续演化出不同语义。

### 7.2 直接按 Collection 过滤

`EmbeddingProfileSqlScope` 已 JOIN `rag_documents d`，因此可以直接生成：

```sql
AND d.collection_id = ANY (?::bigint[])
```

`ANY_COLLECTION`：

```sql
AND d.collection_id IS NOT NULL
```

显式 document 范围：

```sql
AND e.document_id = ANY (?::bigint[])
```

JSON record：

```sql
AND d.document_type = 'json-record'
```

应按 scope 动态拼接必要 predicate，不要写成大量 `OR (? IS NULL ...)`，避免降低 planner
选择性判断。

使用 PostgreSQL array/JDBC `Array` 可以把 ID 列表作为一个 bind 传入；即使显式列表上限
不大，也能避免为每个元素重新生成 SQL 形状。

### 7.3 批量 key 解析

推荐 Repository 能力：

```java
List<RagCollection> findAllByCollectionKeyInAndDeletedFalse(
        Collection<String> collectionKeys);
```

Resolver：

1. 校验 key 数量与字符规则。
2. 去重但保留调用方顺序。
3. 一次查询。
4. 建立 `Map<String, Long>`。
5. 按原顺序返回 ID。
6. 任一缺失时按 unrestricted/restricted 规则返回 404/403。

### 7.4 请求规模上限

推荐：

| 字段 | 建议上限 |
|---|---:|
| Chat/Search `collectionKeys` | 100 |
| Chat/Search deprecated `collectionIds` | 100 |
| JSON record Collection scope | 保持 50 |
| 显式 `documentIds` | 1000，实施前核对现有客户端 |
| `EACH_COLLECTION` Collection 数 | 初始 20 |

范围上限是 API 滥用保护，不替代 SQL 优化。需要搜索“全部可见范围”的调用方应使用 mode，
而不是提交数百或数千个 key。

## 8. HNSW 与过滤策略

### 8.1 版本门禁

如果采用 iterative scan：

1. 启动时读取 `pg_extension.extversion`。
2. 配置要求 iterative scan 时，pgvector `< 0.8.0` 必须 readiness 失败或明确降级。
3. CI/Testcontainers 镜像应锁定版本或 digest，避免浮动 `pg16` 改变执行行为。
4. 正式文档记录最低版本和降级模式。

### 8.2 推荐默认

推荐先验证：

```sql
SET LOCAL hnsw.iterative_scan = strict_order;
```

理由：

- 结果顺序最容易与现有行为对齐；
- 先保证过滤后的 top-K 正确性；
- 后续可基于基准切换 `relaxed_order`。

如果使用 `relaxed_order`，应用应按重新计算的距离/score 再排序，然后进入 hybrid fusion。

不能在连接池连接上无恢复地执行普通 `SET`。应使用：

- 明确事务中的 `SET LOCAL`；或
- 经过版本检测的连接初始化配置；或
- 保证设置与查询使用同一连接并在 finally 恢复。

### 8.3 exact fallback

高选择性范围可能更适合先过滤后精确排序。推荐通过基准确定阈值，而不是先硬编码。

候选策略：

```text
estimated scoped vectors <= threshold
  -> exact filtered distance sort
else
  -> HNSW + iterative scan
```

需要比较：

- exact top-K；
- HNSW 默认；
- HNSW strict iterative；
- HNSW relaxed iterative；
- 不同 `ef_search`。

指标必须同时包含 latency 和 recall@K。

### 8.4 不推荐的首选方案

不建议第一阶段为每个 Collection 创建 HNSW partial index：

- Collection 数量可能高且动态变化；
- 索引数量、构建时间和维护成本会随 Collection 增长；
- 多 Collection 查询不能自然命中单个 Collection partial index；
- Collection 删除/恢复/文档移动增加运维复杂度。

也不建议立即把 `collection_id` 冗余到 `rag_embeddings`。当前 Collection 删除会解除文档
关联，冗余列需要额外一致性机制。先使用已有 JOIN 和直接过滤，只有真实执行计划证明
JOIN 成为主要瓶颈时再评估。

## 9. WebUI 建议

### 9.1 Search 与 Chat

将单选 `<select>` 改为可搜索、分页加载的多选器：

- selected values 使用 `collectionKey`；
- 支持清除；
- 选项显示名称和 key；
- 不一次性加载固定前 200 条；
- 提交时发送非空 `collectionKeys`；
- 显式模式使用 segmented control 或菜单：
  - All visible documents
  - Any collection
  - Selected collections

不要把“没有选中项”直接解释为显式 empty list。前端应：

- 默认范围发送 mode 或省略字段；
- selected 模式至少选一个，否则阻止提交；
- 永远不发送 `collectionKeys: []`。

### 9.2 标签修正

在新增 `ANY_COLLECTION` 前，当前“All Collections”应改为更准确的“All documents”或
“All accessible documents”。

这是行为说明修正，不改变后端兼容语义。

### 9.3 可选逐 Collection 覆盖

只有后端实现 `EACH_COLLECTION` 后才展示该控制项。建议作为高级选项，不作为默认。

## 10. 测试与验收建议

### 10.1 语义集成测试

使用真实 PostgreSQL 创建：

- Collection A：2 个文档；
- Collection B：2 个文档；
- Collection C：无文档；
- 1 个 unassigned 文档；
- 1 个 disabled 文档；
- 1 个被软删除 Collection 解除关联的文档；
- unrestricted 与 restricted API Key。

覆盖：

1. selected A+B 只返回 A/B。
2. selected C 返回空，不退化为全库。
3. `CALLER_VISIBLE` unrestricted 可包含 unassigned。
4. `ANY_COLLECTION` 不包含 unassigned。
5. restricted 省略范围只返回 allow-list。
6. restricted `ANY_COLLECTION` 不扩大权限。
7. keys 与 IDs 不一致返回 400。
8. 未授权/未知 key 返回 403，unrestricted 未知 key 返回 404。
9. Chat、SSE、GET Search、POST Search、JSON record 语义一致。

### 10.2 SQL 结构测试

验证：

- 多 Collection 不再调用 `findIdsByCollectionIdIn`。
- SQL 使用 `d.collection_id` predicate。
- 参数数量与 Collection 数相关，不与文档总数相关。
- explicit empty scope 不执行检索 SQL。
- JSON record 使用 `d.document_type` predicate，不预加载全部 record IDs。

### 10.3 PostgreSQL 性能矩阵

至少测试：

| 维度 | 值 |
|---|---|
| Collection 数 | 1 / 10 / 100 |
| 范围内文档数 | 100 / 10k / 100k |
| embedding chunks | 1k / 100k / 1M |
| 范围选择性 | 0.1% / 1% / 10% / 100% |
| top-K | 5 / 20 / 100 |
| 模式 | vector / FTS / hybrid |

采集：

- p50/p95/p99；
- `EXPLAIN (ANALYZE, BUFFERS)`；
- SQL 文本长度和 bind 数；
- JVM allocation/GC；
- exact baseline 对比 recall@K；
- vector 与 FTS 超时/降级次数。

现有 benchmark 使用 mock EmbeddingModel/JdbcTemplate，只能测 Java 层开销，不能作为上述
验收证据。

### 10.4 WebUI

Vitest 与 Mock Playwright 覆盖：

- 多选 2 个 Collection，实际请求出现两个 `collectionKeys`；
- selected 模式无选择时不能提交；
- All visible documents 不发送 empty list；
- Any collection 发送显式 mode；
- 超过一页的 Collection 可以搜索并选中；
- Chat SSE 与 Search GET 参数序列化一致。

## 11. 分阶段实施建议

### 阶段 0：立即可用说明

不改代码：

- 对外确认后端已支持多个 `collectionKeys`；
- unrestricted 全量检索使用省略范围；
- 明确当前是全局 top-K，不保证每个 Collection 出结果；
- 明确 WebUI 暂时仅单选。

### 阶段 1：低风险护栏

- Chat/Search Collection 列表增加 `@Size(max=100)`。
- unrestricted key 解析改为一次批量查询。
- `CollectionDocumentResolver` 的临时交集改用 Set。
- WebUI 修正“All Collections”标签。
- 增加多 key API/ACL 集成测试。

退出条件：

- 无语义变化；
- 过大显式范围稳定返回 400；
- 多 key 解析为一次 Repository 查询。

### 阶段 2：核心范围下推

- 引入统一 `RetrievalScope`。
- Chat/Search/JSON record 统一 resolver。
- Vector/FTS SQL 直接按 `d.collection_id`、`d.document_type` 过滤。
- 删除在线检索对完整 document ID 展开的依赖。
- 使用 PostgreSQL array 或固定 SQL shape。

退出条件：

- 参数规模不再与范围内文档总数相关；
- ACL/empty scope 保持 fail closed；
- 真实 PostgreSQL 语义测试通过。

### 阶段 3：ANN 过滤可靠性

- 锁定 pgvector 版本。
- 增加版本 capability/readiness。
- 验证并启用 iterative scan。
- 建立 exact vs ANN recall/latency 基准。
- 依据数据确定 exact fallback 和 HNSW 参数。

退出条件：

- 选择性范围下 recall@K 达到冻结阈值；
- p95/p99 满足性能预算；
- 不依赖浮动镜像行为。

### 阶段 4：WebUI 多选与显式模式

- 可搜索分页多选；
- `CALLER_VISIBLE` / `ANY_COLLECTION` / `SELECTED_COLLECTIONS`；
- Mock Playwright 和生产构建门禁；
- 同步正式 REST/API、架构和测试文档中英文版本。

### 阶段 5：可选逐 Collection 覆盖

只有真实产品需求确认后实施：

- `EACH_COLLECTION`；
- bounded fan-out 或 LATERAL 方案；
- query embedding 复用；
- per-Collection candidate quota；
- 全局 fusion/rerank；
- Collection 数量上限和专门性能预算。

## 12. 风险与可逆边界

| 风险 | 控制 |
|---|---|
| 新 mode 改变省略语义 | 省略继续映射 `CALLER_VISIBLE`，只新增显式能力 |
| direct filter 影响 ANN recall | iterative scan + exact baseline + recall 门禁 |
| pgvector 环境版本不一致 | 锁定镜像并启动检测 |
| 新索引扩大写入成本 | 先 EXPLAIN，确认必要后再新增迁移 |
| 多选 UI 加载不全 | 分页搜索，不固定加载 200 条 |
| `EACH_COLLECTION` 放大查询 | 独立模式、最多 20、bounded concurrency |
| restricted mode 扩权 | scope 先与 allow-list 求交/校验，SQL 只接收授权 ID |
| empty scope 退化为全库 | 保留 `filterRequested`，空范围直接返回空结果 |

## 13. 不建议在本轮顺带实施

- 不把 Collection key 直接写入 embedding 行。
- 不把内部 FK 从 Long 改为 String。
- 不为每个动态 Collection 自动创建 HNSW。
- 不用 `"*"` 充当特殊 Collection key。
- 不改变 JSON record 必须显式范围的安全默认。
- 不默认启用 `EACH_COLLECTION`。
- 不在没有真实基准时硬编码 selectivity 阈值或新 covering index。

## 14. 事实来源与代码入口

API/ACL：

- [`ChatRequest`](../../../spring-ai-rag-api/src/main/java/com/springairag/api/dto/ChatRequest.java)
- [`SearchRequest`](../../../spring-ai-rag-api/src/main/java/com/springairag/api/dto/SearchRequest.java)
- [`JsonRecordSearchRequest`](../../../spring-ai-rag-api/src/main/java/com/springairag/api/dto/JsonRecordSearchRequest.java)
- [`RagChatController`](../../../spring-ai-rag-core/src/main/java/com/springairag/core/controller/RagChatController.java)
- [`RagSearchController`](../../../spring-ai-rag-core/src/main/java/com/springairag/core/controller/RagSearchController.java)
- [`ApiKeyCollectionAccess`](../../../spring-ai-rag-core/src/main/java/com/springairag/core/security/ApiKeyCollectionAccess.java)
- [`CollectionIdentityResolver`](../../../spring-ai-rag-core/src/main/java/com/springairag/core/service/CollectionIdentityResolver.java)

执行链：

- [`CollectionDocumentResolver`](../../../spring-ai-rag-core/src/main/java/com/springairag/core/service/CollectionDocumentResolver.java)
- [`RagChatService`](../../../spring-ai-rag-core/src/main/java/com/springairag/core/config/RagChatService.java)
- [`HybridSearchAdvisor`](../../../spring-ai-rag-core/src/main/java/com/springairag/core/advisor/HybridSearchAdvisor.java)
- [`HybridRetrieverService`](../../../spring-ai-rag-core/src/main/java/com/springairag/core/retrieval/HybridRetrieverService.java)
- [`EmbeddingProfileSqlScope`](../../../spring-ai-rag-core/src/main/java/com/springairag/core/retrieval/EmbeddingProfileSqlScope.java)
- [`PgJiebaFulltextProvider`](../../../spring-ai-rag-core/src/main/java/com/springairag/core/retrieval/fulltext/PgJiebaFulltextProvider.java)
- [`PgEnglishFtsProvider`](../../../spring-ai-rag-core/src/main/java/com/springairag/core/retrieval/fulltext/PgEnglishFtsProvider.java)
- [`PgTrgmFulltextProvider`](../../../spring-ai-rag-core/src/main/java/com/springairag/core/retrieval/fulltext/PgTrgmFulltextProvider.java)
- [`JsonRecordService`](../../../spring-ai-rag-core/src/main/java/com/springairag/core/service/JsonRecordService.java)

Schema/index：

- [`V1__init_rag_schema.sql`](../../../spring-ai-rag-core/src/main/resources/db/migration/V1__init_rag_schema.sql)
- [`V25__embedding_profile_expand.sql`](../../../spring-ai-rag-core/src/main/resources/db/migration/V25__embedding_profile_expand.sql)
- [`V29__add_jsonb_structured_records.sql`](../../../spring-ai-rag-core/src/main/resources/db/migration/V29__add_jsonb_structured_records.sql)
- [`EmbeddingProfileIndexManager`](../../../spring-ai-rag-core/src/main/java/com/springairag/core/config/EmbeddingProfileIndexManager.java)

UI：

- [`Search.tsx`](../../../spring-ai-rag-webui/src/pages/Search.tsx)
- [`Chat.tsx`](../../../spring-ai-rag-webui/src/pages/Chat.tsx)
- [`useSSE.ts`](../../../spring-ai-rag-webui/src/hooks/useSSE.ts)

现有文档：

- [`rest-api-zh-CN.md`](../../rest-api-zh-CN.md)
- [`architecture-zh-CN.md`](../../architecture-zh-CN.md)
- [`project-context-zh-CN.md`](../../project-context-zh-CN.md)
- [`pgvector-index-comparison.md`](../../pgvector-index-comparison.md)

## 15. 最终回答

### 当前文档可以不属于任何 Collection 吗？

**可以。** 普通文档的 `collection_id` 当前允许为 `NULL`。创建或上传时未指定
Collection，以及 Collection 软删除后被解除关联，都会产生未归属文档。JSON structured
record 是例外，当前要求恰好归属一个 Collection。

### 用户能否指定若干 Collection 作为检索范围？

**可以。** 后端 Chat、Search 和 JSON record search 已接受多个 `collectionKeys`。语义是
这些 Collection 文档的并集，再执行全局 top-K。

### 用户能否指定“任意 Collection 中的文档都要检索”？

需要区分：

- “所有可见文档”：当前可通过省略范围实现。
- “所有仍归属某个 Collection 的文档”：当前没有精确显式模式，建议新增
  `ANY_COLLECTION`。
- “每个选中 Collection 都必须参与召回”：当前不支持，建议作为可选
  `EACH_COLLECTION` 覆盖模式。

二者的最短区别是：

> `ANY_COLLECTION` 表示“文档属于任意一个 Collection 就有资格参加全局检索”；
> `EACH_COLLECTION` 表示“每个明确选中的 Collection 都要分别尝试提供候选”。

前者是候选范围，后者是候选覆盖策略。

### 是否可以高效做到？

- **全局省略范围**：当前路径相对高效。
- **少量、小范围 Collection**：当前实现可用。
- **多个大 Collection 或严格过滤召回**：当前实现不具备可预测的规模效率。

要达到可扩展、可验证的高效实现，应先将 Collection predicate 下推到检索 SQL，取消
document ID 全量展开，再配合 pgvector iterative scan、exact baseline、真实
PostgreSQL 性能/recall 门禁和 WebUI 多选能力。
