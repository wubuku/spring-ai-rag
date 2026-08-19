# 多 Collection 检索范围改进实施规划

> 状态：规划中，连续三轮无修改检查通过后进入实施
> 规划日期：2026-08-16
> 代码基线：`main` @ `722a645c757b113b8107c86b76bc9b6513c43c33`
> 关联调研：[多 Collection 检索范围与性能调研](2026-08-15_MULTI_COLLECTION_RETRIEVAL_SCOPE_RESEARCH.md)
> 实施范围：Chat、Search、JSON structured records、API Key ACL、Vector/Full-text SQL、WebUI、测试和正式文档

## 1. 执行摘要

本轮把现有“Collection 先展开为全部 document IDs，再生成大型
`document_id IN (...)`”的检索范围实现，改造成“先解析并授权 Collection，再将
Collection predicate 直接下推到 Vector 和 Full-text SQL”。

必须交付：

1. Chat 和 Search 新增三种显式范围模式：
   `CALLER_VISIBLE`、`ANY_COLLECTION`、`SELECTED_COLLECTIONS`。
2. 省略新字段时保持现有兼容语义：
   非空 `collectionKeys` / `collectionIds` 自动推导为
   `SELECTED_COLLECTIONS`，全部省略自动推导为 `CALLER_VISIBLE`。
3. unrestricted 与 restricted API Key 均保持现有权限边界和防枚举行为。
4. 多 key 批量解析，避免每个 key 一次 Repository 查询；deprecated 数字 ID 保持
   既有直通/ACL 子集校验语义。
5. 统一内部 `RetrievalScope`，由 Chat、Search、JSON record 共用。
6. Vector、pg_jieba、English FTS、pg_trgm 直接使用
   `d.collection_id = ANY (?)` / `d.collection_id IS NOT NULL`。
7. 显式 `documentIds` 与 Collection 范围在 SQL 内取交集。
8. JSON record 的 `document_type='json-record'` 条件下推到检索 SQL，不再预加载
   全部候选 record document IDs。
9. WebUI Chat/Search 使用共享的可搜索、分页、多选 Collection 范围控件。
10. 后端真实 PostgreSQL 集成测试、MockMvc/API 契约测试、前端 Vitest、生产构建和
    Mock Playwright 全部通过。

本轮不实施 `EACH_COLLECTION`。它是覆盖策略而非范围过滤，需要独立的候选配额、
bounded fan-out、融合和性能预算。其产品语义和后续边界见
[调研 §2.5-2.6](2026-08-15_MULTI_COLLECTION_RETRIEVAL_SCOPE_RESEARCH.md#25-每个选中-collection-都要参与召回)。

本任务不新增数据库表或列。规划开始时 Flyway 为 V1-V29；实施期间并行的外部文档同步
WIP 新增 V30，本任务只接受该基线变化，不修改 V30 业务内容。

## 2. 当前代码事实

本节只记录实施时必须掌握的近距离上下文；完整问题分析和性能判断见
[调研 §3-5](2026-08-15_MULTI_COLLECTION_RETRIEVAL_SCOPE_RESEARCH.md#3-当前-api-与-ui-能力矩阵)。

### 2.1 外部请求

- [`ChatRequest`](../../../spring-ai-rag-api/src/main/java/com/springairag/api/dto/ChatRequest.java)
  已有 `collectionKeys`、deprecated `collectionIds` 和 `documentIds`。
- [`SearchRequest`](../../../spring-ai-rag-api/src/main/java/com/springairag/api/dto/SearchRequest.java)
  有相同三个字段。
- GET Search 使用重复 query parameter 接收 `collectionKeys` / `collectionIds`。
- [`JsonRecordSearchRequest`](../../../spring-ai-rag-api/src/main/java/com/springairag/api/dto/JsonRecordSearchRequest.java)
  要求显式 Collection 范围，列表上限为 50。

Chat/Search 当前没有 `collectionScopeMode`，省略 Collection 字段时靠 `null` 推导。

### 2.2 ACL 和 Collection 身份解析

- [`ApiKeyCollectionAccess`](../../../spring-ai-rag-core/src/main/java/com/springairag/core/security/ApiKeyCollectionAccess.java)
  对 unrestricted 调用方保留请求范围；对 restricted 调用方把省略范围替换为
  allow-list，并拒绝 allow-list 外的 Collection。
- restricted 调用方的未知/未授权 key 统一返回 `403`；unrestricted 未知 key 返回
  `404`。
- [`CollectionIdentityResolver`](../../../spring-ai-rag-core/src/main/java/com/springairag/core/service/CollectionIdentityResolver.java)
  对 unrestricted 多 key 当前逐个调用
  `findByCollectionKeyAndDeletedFalse(...)`。
- restricted 多 key 会先 `findAllById(allow-list)`，再在内存映射。

### 2.3 当前范围展开

[`CollectionDocumentResolver`](../../../spring-ai-rag-core/src/main/java/com/springairag/core/service/CollectionDocumentResolver.java)
执行：

```text
collection IDs
  -> SELECT every rag_documents.id
  -> Java List<Long>
  -> 与显式 documentIds 在 JVM 中求交
```

Chat、GET Search、POST Search 和 JSON record 均依赖这种展开，只是调用位置不同。

### 2.4 当前检索 SQL

[`HybridRetrieverService`](../../../spring-ai-rag-core/src/main/java/com/springairag/core/retrieval/HybridRetrieverService.java)
的 Vector SQL，以及三个
[`FulltextSearchProvider`](../../../spring-ai-rag-core/src/main/java/com/springairag/core/retrieval/fulltext/FulltextSearchProvider.java)
实现，均只接受 `List<Long> documentIds`。

[`EmbeddingProfileSqlScope`](../../../spring-ai-rag-core/src/main/java/com/springairag/core/retrieval/EmbeddingProfileSqlScope.java)
已经 JOIN：

```sql
JOIN rag_documents d ON d.id = e.document_id
```

因此 Collection predicate 可以直接引用 `d.collection_id`，不需要 schema 变更。

### 2.5 当前 Chat Advisor 传递

[`RagChatService`](../../../spring-ai-rag-core/src/main/java/com/springairag/core/config/RagChatService.java)
有一个私有 `RetrievalScope` record，但只包含展开后的 document IDs、
`filterRequested` 和 `maxResults`。

[`HybridSearchAdvisor`](../../../spring-ai-rag-core/src/main/java/com/springairag/core/advisor/HybridSearchAdvisor.java)
通过 context key 读取 `documentIds`，无法表达
`ANY_COLLECTION` 或直接 Collection predicate。

### 2.6 当前 WebUI

- [`Search.tsx`](../../../spring-ai-rag-webui/src/pages/Search.tsx) 和
  [`Chat.tsx`](../../../spring-ai-rag-webui/src/pages/Chat.tsx) 都是单选。
- 两者固定加载 Collection 第 0 页、最多 200 条。
- 空选项显示“All Collections”，但 unrestricted 时实际包含未归属文档。
- Collection 列表后端已有 offset/limit 和 name 过滤，但没有同时按 name/key 搜索。

## 3. 目标与非目标

### 3.1 目标

- 对外明确表达调用方可见范围、任意已归属 Collection、指定 Collection 并集。
- 保持旧请求完全兼容，不要求现有调用方立即发送 mode。
- 所有范围决策在进入 SQL 前完成 ACL 校验。
- 参数数量与 Collection 数相关，不再与 Collection 内文档总数相关。
- deprecated 数字 ID 不新增存在性查询，未知/已删除 ID 继续形成零命中范围。
- Chat 同步、Chat SSE、GET Search、POST Search 和 JSON record 使用相同内部范围模型。
- 显式空范围、未知 key、未授权 key 和零命中范围全部 fail closed。
- UI 能选择多个 Collection，并能在超过 200 个 Collection 时分页搜索。
- 用真实 PostgreSQL 验证 SQL 语义，而不是只依赖 mocked `JdbcTemplate`。

### 3.2 非目标

- 不改变一个 Document 最多归属一个 Collection 的数据模型。
- 不要求普通 Document 必须归属 Collection。
- 不把 Collection key 写入 embedding 行。
- 不把内部 FK 从 `Long` 改为 `String`。
- 不新增每 Collection 一个 HNSW 索引。
- 不在本轮实现 `EACH_COLLECTION`。
- 不在没有真实 recall/latency 基准前自动修改 `hnsw.ef_search`、
  `hnsw.iterative_scan` 或强制 exact scan。
- 不改变 JSON record 必须显式指定 Collection 范围的安全默认。
- 不删除 deprecated `collectionIds` 或旧 `HybridRetrieverService` 重载。

## 4. 冻结的设计决策

### 4.1 外部 Scope 模式

在 `spring-ai-rag-api` 新增：

```java
public enum CollectionScopeMode {
    CALLER_VISIBLE,
    ANY_COLLECTION,
    SELECTED_COLLECTIONS
}
```

精确定义：

| 模式 | unrestricted | restricted |
|---|---|---|
| `CALLER_VISIBLE` | 全部可检索文档，包括 `collection_id IS NULL` | API Key allow-list 中的文档 |
| `ANY_COLLECTION` | 所有 `collection_id IS NOT NULL` 的可检索文档 | API Key allow-list 中的文档 |
| `SELECTED_COLLECTIONS` | 指定 Collection 并集 | 指定集合必须是 allow-list 子集 |

`CALLER_VISIBLE` 表示调用方默认可见范围，不命名为 `ALL_COLLECTIONS`，避免误导。

### 4.2 兼容推导

| mode | keys/IDs | 推导或结果 |
|---|---|---|
| 省略 | 全部省略 | `CALLER_VISIBLE` |
| 省略 | 任一非空 | `SELECTED_COLLECTIONS` |
| 省略 | 任一显式空列表 | `400` |
| `CALLER_VISIBLE` | 全部省略 | 接受 |
| `CALLER_VISIBLE` | 任一存在，包括空列表 | `400` |
| `ANY_COLLECTION` | 全部省略 | 接受 |
| `ANY_COLLECTION` | 任一存在，包括空列表 | `400` |
| `SELECTED_COLLECTIONS` | key/ID 任一非空 | 接受 |
| `SELECTED_COLLECTIONS` | 两者均省略或任一显式空 | `400` |

同时提供 key 与 ID 时，继续要求两者解析为同一集合；顺序忽略，集合不一致返回 `400`。

### 4.3 数量限制

| 输入 | 上限 | 处理 |
|---|---:|---|
| Chat/Search `collectionKeys` | 100 | 超出返回 `400` |
| Chat/Search deprecated `collectionIds` | 100 | 超出返回 `400` |
| JSON record Collection 范围 | 保持 50 | 继续由 DTO 校验 |
| Chat/Search `documentIds` | 1000 | 超出返回 `400` |

Collection 上限与 API Key allow-list 上限一致。它是滥用保护，不代替 SQL 优化。

### 4.4 JSON record 范围

JSON record search 保持“必须显式指定非空 Collection 范围”：

- 不新增 `collectionScopeMode`；
- 继续接受 `collectionKeys` 和 deprecated `collectionIds`；
- 解析后构造 effective `SELECTED` 范围；
- 在检索 SQL 中同时加入 `d.document_type = ?`；
- 不再先调用 `findEnabledIdsByCollectionIdsAndDocumentType(...)`。

### 4.5 `EACH_COLLECTION`

本轮不在 DTO 中增加 `collectionCoverageMode`，避免发布一个只接受默认值或行为尚未
实现的字段。

后续如实施：

- 新增 `GLOBAL_TOP_K` / `EACH_COLLECTION`；
- `EACH_COLLECTION` 初期只允许配合 `SELECTED_COLLECTIONS`；
- Collection 上限 20；
- query embedding 只计算一次；
- 每 Collection 有独立候选上限；
- 候选统一 fusion/rerank；
- 无相关内容时不强制返回低质量结果。

### 4.6 SQL 数组绑定

直接使用固定形状 predicate：

```sql
AND d.collection_id = ANY (?)
AND e.document_id = ANY (?)
```

参数通过 Spring JDBC 6.2 已有的：

```java
new SqlArrayValue("bigint", values.toArray())
```

绑定为 JDBC `Array`。不手工拼接 PostgreSQL 数组字符串，不把调用方 key 直接进入 SQL。

`collectionIds` 和 `documentIds` 在进入 SQL 前必须：

- 非空值均为正数；
- 去重并保持调用方顺序；
- 使用不可变 List；
- key 已解析为内部 Long ID；
- restricted 范围已经通过 ACL。

### 4.7 ANN 参数

本轮保持现有 HNSW 查询参数和索引不变。理由：

- direct Collection filter 本身已经移除主要 JVM/参数放大问题；
- filtered ANN 的 recall 需要真实数据分布；
- pgvector 生产最低版本尚未锁定；
- `SET LOCAL` 必须与查询共用事务/连接，不能草率对连接池设置 session 状态。

本轮 PostgreSQL 集成测试记录 `extversion`，但不以 `>=0.8.0` 作为启动硬失败。
iterative scan 留给独立性能阶段。

### 4.8 数据库迁移

不新增 Flyway migration：

- `rag_documents.collection_id` 已有 B-tree 索引 `idx_rag_doc_collection`；
- 检索 SQL 已 JOIN `rag_documents`；
- 本轮没有 schema 或持久化契约变化。

本任务自身不新增 migration。若并行工作新增 migration，以当前工作区最大版本更新
`AGENTS.md` 和长青文档，但不得修改并行 migration 的业务内容；当前工作区为 V30。

## 5. API 契约

### 5.1 ChatRequest

新增：

```java
private CollectionScopeMode collectionScopeMode;
```

并补充：

```java
@Size(max = 100)
private List<@Positive Long> collectionIds;

@Size(max = 100)
private List<@ValidCollectionKey String> collectionKeys;

@Size(max = 1000)
private List<@Positive Long> documentIds;
```

更新 getter/setter、`equals`、`hashCode`、`toString` 和 OpenAPI schema。

同步与 SSE 使用同一 scope resolver，不允许两条路径语义漂移。

### 5.2 SearchRequest

增加与 Chat 相同的 `collectionScopeMode` 和列表上限。

POST 示例：

```json
{
  "query": "退款规则",
  "collectionScopeMode": "SELECTED_COLLECTIONS",
  "collectionKeys": ["manual:v3", "faq:v2"],
  "documentIds": [10, 11],
  "config": {
    "maxResults": 10
  }
}
```

### 5.3 GET Search

新增可选 query parameter：

```text
collectionScopeMode=CALLER_VISIBLE|ANY_COLLECTION|SELECTED_COLLECTIONS
```

示例：

```text
GET /api/v1/rag/search?query=refund&collectionScopeMode=ANY_COLLECTION
```

```text
GET /api/v1/rag/search?query=refund
  &collectionScopeMode=SELECTED_COLLECTIONS
  &collectionKeys=manual:v3
  &collectionKeys=faq:v2
```

GET 没有显式 `documentIds` 参数，本轮不新增。

GET 参数不经过 request-body Bean Validation，Controller 必须在调用 scope resolver 前或由
resolver 统一检查：

- 原始 `collectionIds` / `collectionKeys` 数量不超过 100；
- 数字 ID 非 null 且为正数；
- key 满足 1-128 个 visible ASCII 字符；
- 空字符串、仅空白 key 和显式空值均返回 `400`；
- 无效 enum 继续由 Spring 参数绑定转换为 `400`。

### 5.4 错误语义

| 条件 | 状态 |
|---|---:|
| 无效 mode 枚举 | 400 |
| mode 与 keys/IDs 冲突 | 400 |
| 显式空 keys/IDs | 400 |
| Collection 数超过上限 | 400 |
| document IDs 数超过上限 | 400 |
| ID/key 集合不一致 | 400 |
| unrestricted 未知/软删除 key | 404 |
| restricted 未知/未授权 key | 403 |
| unrestricted deprecated 未知/软删除数字 ID | 200 + 空结果 |
| restricted allow-list 外数字 ID | 403 |
| selected Collection 当前无文档 | 200 + 空结果 |
| valid scope 但无 fresh embedding | 200 + 空结果 |

任何错误都不得退化为无范围检索。

### 5.5 响应

本轮不改变 ChatResponse、SearchResponse、RetrievalResult 和 SSE 事件格式。

## 6. ACL 与 effective scope

### 6.1 统一 resolver

新增：

```text
spring-ai-rag-core/.../service/CollectionRetrievalScopeResolver.java
```

输入：

```java
resolve(
    CollectionScopeMode requestedMode,
    List<Long> requestedIds,
    List<String> requestedKeys,
    List<Long> documentIds,
    String documentType,
    RagApiKey caller)
```

输出共享的内部 `RetrievalScope`。

### 6.2 解析算法

1. 检查 Collection/document 列表数量。
2. 区分 `null` 和显式 empty。
3. 根据 §4.2 推导 requested mode。
4. 校验 mode 与 key/ID 组合。
5. `SELECTED_COLLECTIONS` 中，key 经批量身份解析后得到内部 IDs；deprecated 数字 ID
   只执行正数、去重及 ACL 子集校验；两者同时存在时比较集合。
6. restricted `CALLER_VISIBLE` / `ANY_COLLECTION` 读取 allow-list，effective filter
   均为 `SELECTED`。
7. unrestricted `CALLER_VISIBLE` 的 effective filter 为 `NONE`。
8. unrestricted `ANY_COLLECTION` 的 effective filter 为 `ANY_ASSIGNED`。
9. 去重、冻结 document IDs。
10. 返回 immutable scope。

### 6.3 防枚举

resolver 必须复用 `ApiKeyCollectionAccess` 的现有异常转换：

- unrestricted 调用全局批量 key 解析，缺失为 `COLLECTION_NOT_FOUND`；
- restricted 只在 allow-list 内解析，任何缺失转换为 `SecurityException`；
- 日志不得输出 allow-list 或未授权 Collection 的存在性。

### 6.4 disabled 与 soft-deleted Collection

当前检索身份解析只排除 soft-deleted Collection，不排除 `enabled=false` Collection；
文档检索自身只检查 `d.enabled=true`。

本轮不顺带改变该历史语义。是否让 disabled Collection 自动退出检索范围，需作为单独
产品决策，不能在范围 SQL 重构中静默改变。

## 7. 内部 RetrievalScope

### 7.1 模型

新增：

```text
spring-ai-rag-core/.../retrieval/RetrievalScope.java
```

建议结构：

```java
public record RetrievalScope(
        CollectionFilter collectionFilter,
        List<Long> collectionIds,
        List<Long> documentIds,
        String documentType,
        boolean matchNone) {

    public enum CollectionFilter {
        NONE,
        ANY_ASSIGNED,
        SELECTED
    }
}
```

不用外部 `CollectionScopeMode` 直接充当 effective filter，因为 restricted
`CALLER_VISIBLE` 和 restricted `ANY_COLLECTION` 都必须收敛为 allow-list 的
`SELECTED` predicate。

### 7.2 不变量

- `NONE`：`collectionIds` 必须为空。
- `ANY_ASSIGNED`：`collectionIds` 必须为空。
- `SELECTED`：`collectionIds` 必须非空；若调用链意外传空则构造 `matchNone=true`。
- `documentIds` 为 null/空时不添加 document predicate，保持现有兼容语义。
- `documentType` 只能由服务端传入，不从普通 Chat/Search 请求读取。
- `matchNone=true` 时 SQL 必须加入 `AND 1 = 0`。
- 所有列表在构造时复制为不可变、去重、保序 List。

### 7.3 兼容工厂

保留旧调用方：

```java
RetrievalScope.forDocumentIds(List<Long> documentIds)
RetrievalScope.unscoped()
```

旧 `HybridRetrieverService.search(query, documentIds, ...)` 重载委托新的
`searchInScope(query, scope, ...)` 入口。

## 8. Collection key 批量解析与数字 ID 兼容

### 8.1 Repository

新增：

```java
List<RagCollection> findAllByCollectionKeyInAndDeletedFalse(
        Collection<String> collectionKeys);
```

`Collection` 参数类型便于 Set/List 调用。

### 8.2 unrestricted keys

`resolveActiveIds(...)`：

1. 校验全部 key。
2. 去重保序。
3. 一次 Repository 查询。
4. 构造 `Map<String, RagCollection>`。
5. 按请求顺序重建 ID。
6. 第一个缺失 key 继续抛 `COLLECTION_NOT_FOUND`。

### 8.3 deprecated numeric IDs

数字 ID 保持当前兼容语义，不新增 Repository 存在性查询：

- unrestricted：校验非 null、正数、去重保序后直接作为内部 ID 范围；
- restricted：继续要求是 allow-list 子集，并保持防枚举的 `403`；
- 未知或 soft-deleted 数字 ID 对 unrestricted 调用方形成有效但零命中的 selected
  范围，保持当前 `200 + 空结果`；
- ID/key 同时提供时，key 先批量解析，再比较两者的 Set；
- 只有 key 的未知/soft-deleted 语义为 `404`。

这一区分是 deprecated 数字兼容边界，不应推广到新的 key-first API。

### 8.4 restricted keys

保留“一次加载 allow-list，再在内存映射”的防枚举方式，不调用全局 key 查询。

## 9. SQL 下推设计

### 9.1 共享 predicate builder

新增：

```text
spring-ai-rag-core/.../retrieval/RetrievalScopeSql.java
```

输出：

```java
record Fragment(String sql, List<Object> args) {}
```

规则：

```sql
-- NONE
-- no Collection predicate

-- ANY_ASSIGNED
AND d.collection_id IS NOT NULL

-- SELECTED
AND d.collection_id = ANY (?)

-- explicit documents
AND e.document_id = ANY (?)

-- server-owned document type
AND d.document_type = ?

-- match none
AND 1 = 0
```

数组参数使用 `SqlArrayValue("bigint", ...)`。

predicate 顺序固定：

1. Collection；
2. document IDs；
3. document type；
4. provider-specific predicate。

固定顺序便于 SQL 测试和诊断，不依赖顺序获得正确性。

### 9.2 Vector

为 [`HybridRetrieverService`](../../../spring-ai-rag-core/src/main/java/com/springairag/core/retrieval/HybridRetrieverService.java)
新增：

```java
searchInScope(query, RetrievalScope scope, excludeIds, limit)
searchInScope(query, RetrievalScope scope, excludeIds, limit, config)
```

Vector 查询：

```sql
SELECT ...
FROM rag_embeddings e
JOIN rag_document_embedding_state s ...
JOIN rag_documents d ...
WHERE e.embedding_profile_id = <active-profile>
  AND s.status = 'COMPLETED'
  AND s.content_hash = d.content_hash
  AND d.enabled = true
  <scope predicate>
ORDER BY e.embedding_1024 <=> CAST(? AS vector)
LIMIT ?
```

query vector 仍只计算一次。scope 对 Vector 和 Full-text 使用同一 immutable 对象。

### 9.3 Full-text SPI

`FulltextSearchProvider` 新增 scope 版本：

```java
searchInScope(query, RetrievalScope scope, excludeIds, limit, minScore, profileId)
```

保留旧 document IDs default method，委托 `RetrievalScope.forDocumentIds(...)`。

以下实现全部使用 `RetrievalScopeSql`：

- `PgJiebaFulltextProvider`
- `PgEnglishFtsProvider`
- `PgTrgmFulltextProvider`
- `NoOpFulltextSearchProvider`

不能只改 Vector，否则 hybrid 结果会跨出 Collection 范围。

### 9.4 Chat Advisor

新增 context key：

```java
HybridSearchAdvisor.RETRIEVAL_SCOPE_KEY
```

`RagChatService`：

- 删除私有 document-only `RetrievalScope`，改用共享 record；
- Controller 解析 effective scope 后调用新 overload：
  `chat(request, scope)` / `chatStream(request, scope)`；
- advisor context 直接保存 scope；
- `maxResults` 继续独立传递。

`HybridSearchAdvisor`：

- 优先读取共享 scope；
- 没有新 scope 时兼容旧 `documentIds` context；
- `matchNone=true` 直接返回空结果，不调用 retriever；
- 其他 scope 调用新的 HybridRetriever overload。

### 9.5 Search

GET/POST 均：

1. 构造 effective scope；
2. 不调用 `CollectionDocumentResolver`；
3. 直接调用 `HybridRetrieverService.searchInScope(...scope...)`；
4. rerank 行为保持不变。

### 9.6 JSON record

删除检索前的：

```java
findEnabledIdsByCollectionIdsAndDocumentType(...)
```

改为：

```java
RetrievalScope scope = selectedCollections(
        collectionIds,
        null,
        RagDocument.JSON_RECORD);
hybridRetrieverService.searchInScope(query, scope, null, limit, config);
```

结果回表仍使用：

```java
findByIdInAndDocumentTypeAndEnabledTrue(...)
```

并保留 Collection ID 二次校验，作为 defense in depth。

### 9.7 CollectionDocumentResolver

本轮不立即删除 bean 和 Repository 的 `findIdsByCollectionIdIn(...)`，原因：

- 降低对潜在外部/测试调用者的兼容风险；
- 允许实现期间对比新旧行为。

完成后生产 Chat/Search/JSON 路径不得调用它。后续单独清理 deprecated 代码。

## 10. Collection 列表搜索

### 10.1 API

`GET /api/v1/rag/collections` 新增可选 `query`：

- 同时匹配 `LOWER(name)` 和 `LOWER(collectionKey)`；
- 保留现有 `name` 参数；
- `query` 与 `name` 同时存在时取 AND：
  `name` 继续只匹配名称，`query` 匹配名称或 key；
- ACL、enabled、分页行为不变。

此契约兼容现有调用方，同时让选择器可以按稳定 key 查找。

### 10.2 Repository

`searchCollections` 和 `searchCollectionsByIds` 增加 `query` 参数：

```sql
AND (
  COALESCE(:query, '') = ''
  OR LOWER(c.name) LIKE LOWER(CONCAT('%', :query, '%'))
  OR LOWER(c.collectionKey) LIKE LOWER(CONCAT('%', :query, '%'))
)
```

无需数据库迁移。Collection 数量极大时再由 `EXPLAIN` 决定是否增加 trigram/functional
索引；本轮不预先增加。

## 11. WebUI 实施

### 11.1 共享类型

新增：

```ts
export type CollectionScopeMode =
  | 'CALLER_VISIBLE'
  | 'ANY_COLLECTION'
  | 'SELECTED_COLLECTIONS';
```

Search API 和 Chat SSE body 都发送：

```ts
collectionScopeMode?: CollectionScopeMode;
collectionKeys?: string[];
```

规则：

- `CALLER_VISIBLE` / `ANY_COLLECTION` 不发送 `collectionKeys`；
- `SELECTED_COLLECTIONS` 只在至少选择一个 key 时允许提交；
- 永远不发送 `collectionKeys: []`。

### 11.2 共享组件

新增：

```text
spring-ai-rag-webui/src/components/CollectionScopeSelector/
```

组件职责：

- Scope segmented/radio control；
- `SELECTED_COLLECTIONS` 下显示搜索输入；
- 每页加载建议 50 个 Collection；
- 显示 name、key 和当前列表 API 返回的关联 `documentCount`；
- checkbox 多选；
- 已选项跨搜索和分页保留；
- 已选数量明确显示；
- 支持上一页/下一页或“加载更多”；
- loading、error、empty 状态完整；
- disabled 状态用于 Chat streaming；
- 最大选择 100，达到上限时禁用未选项；
- 不把说明文字做成额外营销卡片。

### 11.3 默认值和标签

默认：

```text
CALLER_VISIBLE
```

中英文标签：

| mode | 中文 | English |
|---|---|---|
| `CALLER_VISIBLE` | 全部可见文档 | All visible documents |
| `ANY_COLLECTION` | 任意集合中的文档 | Documents in any collection |
| `SELECTED_COLLECTIONS` | 指定集合 | Selected collections |

不再用“All Collections”表示省略范围。

### 11.4 Search

状态：

```ts
scopeMode
selectedCollectionKeys
```

React Query key 必须包含 mode 和排序后的 selected keys，避免缓存串范围。

提交时：

- selected 且零选项：禁用 Search；
- 其他 mode 不发送 key；
- query、hybrid 参数保持现有行为。

### 11.5 Chat

Chat composer 使用同一组件。

SSE hook 改为接收一个 options object，避免继续扩展位置参数：

```ts
send({
  message,
  conversationId,
  model,
  collectionScopeMode,
  collectionKeys
})
```

为了降低迁移风险，可暂时保留旧位置参数 overload，但页面代码必须使用 object 版本。

### 11.6 i18n 和响应式

同步更新：

- `src/i18n/locales/en.json`
- `src/i18n/locales/zh-CN.json`
- Search/Chat CSS

移动端组件不能溢出 composer/form；checkbox 行、key 文本和分页按钮必须换行或截断。

### 11.7 构建产物

`npm run build` 后，将 `dist/` 同步到：

```text
spring-ai-rag-core/src/main/resources/static/webui/
```

只提交当前构建生成的 hash 文件，不保留孤儿旧资产。

## 12. 正式文档同步

行为实施后同步中英文：

- `docs/rest-api.md` / `docs/rest-api-zh-CN.md`
- `docs/architecture.md` / `docs/architecture-zh-CN.md`
- `docs/project-context.md` / `docs/project-context-zh-CN.md`
- `docs/SSE-PROTOCOL.md`
- `docs/testing-guide.md` / `docs/testing-guide-zh-CN.md`
- `spring-ai-rag-webui/README.md` / `README-zh-CN.md`（若使用流程变化）

必须删除正式文档中“当前会展开为 document IDs”的现行描述，改为 direct predicate，
同时保留 migration/兼容说明。

## 13. 测试计划

### 13.1 API DTO

覆盖：

- mode getter/setter/equals/hashCode/toString；
- Collection 100 边界；
- Collection 101 校验失败；
- document IDs 1000 边界；
- document IDs 1001 校验失败；
- invalid enum JSON 返回 400。

### 13.2 Scope resolver

建立完整输入矩阵：

1. omitted + unrestricted -> `NONE`。
2. omitted + restricted -> allowed `SELECTED`。
3. `CALLER_VISIBLE` + keys -> 400。
4. `ANY_COLLECTION` + unrestricted -> `ANY_ASSIGNED`。
5. `ANY_COLLECTION` + restricted -> allowed `SELECTED`。
6. `SELECTED_COLLECTIONS` + keys -> resolved `SELECTED`。
7. selected 缺范围 -> 400。
8. explicit empty -> 400。
9. key/ID mismatch -> 400。
10. restricted outside key -> 403。
11. duplicate keys/IDs 去重保序。
12. document IDs 与 Collection 范围同时保留。

### 13.3 CollectionIdentityResolver

- 多 key 只执行一次 batch Repository 查询；
- 第一个缺失 key 错误稳定；
- soft-deleted 不可解析；
- ID/key 集合比较忽略顺序；
- restricted 路径不调用全局 key 查询。

数字 ID 的正数、去重、ACL 子集和未知 ID 零命中兼容语义由 scope resolver /
`ApiKeyCollectionAccess` 测试覆盖，不要求 `CollectionIdentityResolver` 查询其存在性。

### 13.4 SQL 单元测试

对 Vector 和三个 Full-text provider 分别验证：

- `NONE` 不含 Collection predicate；
- `ANY_ASSIGNED` 含 `d.collection_id IS NOT NULL`；
- `SELECTED` 含 `d.collection_id = ANY (?)`；
- document IDs 含 `e.document_id = ANY (?)`；
- 同时提供时为 AND；
- JSON record 含 `d.document_type = ?`；
- `matchNone` 含 `AND 1 = 0`；
- 参数为 `SqlArrayValue`，不是每个 ID 一个 placeholder；
- profile/freshness/enabled 条件不丢失。

### 13.5 Controller / MockMvc

覆盖 Chat `/chat`、`/chat/ask`、`/chat/stream`，GET/POST Search：

- 三种 mode；
- 兼容推导；
- 冲突 400；
- max size 400；
- restricted ACL；
- selected 零结果不退化；
- 同步/SSE 传递相同 scope；
- OpenAPI 暴露 mode enum。
- GET key/ID 逐元素校验与 POST Bean Validation 产生一致的 `400`。
- deprecated 未知数字 ID 保持 `200 + 空结果`，未知 key 保持 `404`。

### 13.6 JSON record

- 仍要求显式范围；
- 不调用候选 ID 预加载 Repository 方法；
- retriever 收到 selected Collection scope + `json-record` type；
- rerank、结果去重、payload 回表保持；
- 结果不会跨 Collection。

### 13.7 真实 PostgreSQL 集成测试

新增 opt-in Testcontainers 测试：

```text
MultiCollectionRetrievalPostgresIntegrationTest
```

数据：

- Collection A：相关文档 2 个；
- Collection B：相关文档 2 个；
- Collection C：0 文档；
- unassigned 普通文档 1 个；
- JSON record 1 个；
- disabled 文档 1 个；
- 两个 Embedding Profile，确保只查 active Profile；
- fresh 与 stale embedding state。

至少验证：

1. unrestricted `CALLER_VISIBLE` 可返回 unassigned。
2. `ANY_COLLECTION` 排除 unassigned。
3. selected A+B 不返回其他 Collection。
4. selected C 返回空。
5. selected + document IDs 在 SQL 内求交。
6. JSON scope 只返回 `json-record`。
7. disabled、stale、错误 Profile 不返回。
8. SQL 数组参数在 PostgreSQL 真实执行成功。
9. `findIdsByCollectionIdIn` 不参与检索链。
10. 查询 `pg_extension.extversion` 并记录日志。

测试优先使用 vector-only，避免外部 LLM/Embedding 调用；EmbeddingModel 使用固定向量
mock，数据库和 pgvector 为真实。

### 13.8 WebUI Vitest

共享组件：

- 默认 mode；
- 切换 mode；
- 分页请求；
- query 搜索；
- 跨页保留 selection；
- 达到 100 上限；
- selected 空时状态；
- loading/error/empty；
- disabled。

Search/Chat：

- `CALLER_VISIBLE` 发送 mode、不发送 keys；
- `ANY_COLLECTION` 发送 mode、不发送 keys；
- selected 多 key 正确发送；
- 永不发送空 keys；
- Chat SSE object request；
- mode/keys 进入 React Query key。

### 13.9 Mock Playwright

核心验收：

1. Search 选择 `ANY_COLLECTION`，请求 query parameter 正确。
2. Search 选择两个 Collection，发送两个重复 `collectionKeys`。
3. Chat 选择两个 Collection，SSE JSON body 正确。
4. 切换回 `CALLER_VISIBLE` 后不残留 keys。
5. 分页/搜索 mock 能找到第 2 页 Collection 并选择。
6. 移动视口下控件不重叠、不溢出。

## 14. 性能验证

### 14.1 本轮硬门槛

真实 PostgreSQL 测试中至少断言：

- Collection 数组参数个数固定为一个 JDBC Array；
- 不查询完整 Collection document IDs；
- SQL 结果语义正确。

### 14.2 诊断基准

新增可单独运行的测试或脚本，数据规模至少：

```text
10 Collections x 1,000 documents
2 Collections x 50,000 documents（资源允许时）
```

对比：

- 旧 document ID 展开查询；
- 新 direct Collection predicate；
- unscoped；
- `ANY_COLLECTION`；
- selected 1/5/20/100 Collections。

采集：

- 端到端检索延迟；
- SQL 执行时间；
- `EXPLAIN (ANALYZE, BUFFERS)`；
- 返回数量；
- JVM 中间 document ID 数；
- PostgreSQL plan；
- pgvector extension version。

性能数字不作为普通 CI 的稳定断言，避免共享机器抖动；CI 断言结构和语义。

### 14.3 后续 iterative scan 门槛

只有满足以下条件才另行启用：

- 生产最低 pgvector `>= 0.8.0`；
- scoped ANN recall@K 对 exact baseline 达标；
- `SET LOCAL` 与查询在同一事务/连接；
- 有配置开关和 readiness 信息；
- 参数通过真实数据集确定。

## 15. 实施阶段

### 阶段 0：基线和进度记录

- 创建实施进度文档；
- 记录 HEAD、工作区状态、测试命令和两个检查计数器；
- 运行目标模块 compile/test 基线；
- 确认 Flyway 当前最大版本；本任务开始时为 V29，实施期间并行 WIP 已推进到 V30。

退出条件：基线可复现，其他开发者改动已识别且不被覆盖。

### 阶段 1：API 与批量身份解析

- 新增 `CollectionScopeMode`；
- 修改 Chat/Search DTO；
- 增加数量校验；
- Repository batch key；
- 重写 `CollectionIdentityResolver` key 批量路径，保留数字 ID 兼容边界；
- 完成 DTO/resolver/ACL 测试。

退出条件：API 模块和 resolver 测试通过。

### 阶段 2：共享 scope 和 SQL

- 新增 `RetrievalScope`、resolver、SQL fragment；
- 改 Hybrid Retriever；
- 改全部 Full-text provider；
- 改 HybridSearchAdvisor；
- 保留兼容重载；
- 完成 SQL 单元测试。

退出条件：Vector/FTS 所有 scope 结构测试通过。

### 阶段 3：端点接入

- Chat Controller/Service 同步和 SSE；
- GET/POST Search；
- JSON record；
- Collection 列表 query；
- Controller/MockMvc/OpenAPI 测试。

退出条件：所有入口语义矩阵通过，生产路径不调用 CollectionDocumentResolver。

### 阶段 4：真实 PostgreSQL

- 新增 Testcontainers 集成测试；
- 验证 SQL array、active Profile、freshness、enabled、unassigned、JSON type；
- 记录 pgvector version；
- 必要时只修正 SQL 绑定/执行问题，不在此阶段加入未经规划的索引。

退出条件：真实 PostgreSQL 集成测试通过。

### 阶段 5：WebUI

- 共享 selector；
- Search；
- Chat/SSE；
- Collection query API；
- i18n/CSS；
- Vitest；
- Mock Playwright；
- 构建并同步静态资源。

退出条件：tsc、生产构建、核心浏览器验收通过。

### 阶段 6：正式文档

- 同步 §12 文件；
- 运行项目文档门禁；
- 确认中英文结构一致。

### 阶段 7：基本集成验证硬门槛

顺序执行：

1. 本任务后端相关测试；
2. 真实 PostgreSQL 集成测试；
3. `mvn clean compile test-compile`；
4. WebUI `npm run test:run`；
5. WebUI 独立 `tsc -b`；
6. WebUI production build；
7. Maven `webui` profile 清理并同步 Spring Boot 内嵌静态资源；
8. 核心 Mock Playwright；
9. 服务启动 smoke；
10. 文档门禁和 `git diff --check`。

所有硬门槛通过前，不进入实现代码三轮收敛检查。

### 阶段 8：连续三轮实现代码检查

固定范围：

- API/兼容/ACL；
- Scope 不变量和 fail-closed；
- Vector/所有 FTS/JSON SQL；
- Chat 同步/SSE；
- Search GET/POST；
- UI request payload 与分页多选；
- 测试覆盖、正式文档、静态资源；
- 性能和连接池风险。

任一轮发现实质问题：

1. 立即修改；
2. 运行必要测试；
3. 重新通过阶段 7 的受影响门禁；
4. 检查计数归零。

只有连续三轮无问题、无代码或文档修改后结束。

## 16. 验证命令

### 16.1 后端目标测试

```bash
mvn -pl spring-ai-rag-api -Dtest='*DtoTest' test
```

```bash
mvn -pl spring-ai-rag-core -am \
  -Dtest='CollectionIdentityResolverTest,ApiKeyCollectionAccessTest,*RetrievalScope*,HybridRetrieverServiceTest,HybridSearchAdvisorTest,Pg*FulltextProviderTest,RagSearchControllerTest,RagChatControllerTest,JsonRecordServiceTest,RagControllerIntegrationTest,OpenApiContractTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

### 16.2 PostgreSQL

```bash
TESTCONTAINERS_RYUK_DISABLED=true \
mvn -pl spring-ai-rag-core -am \
  -Dapi.version=1.40 \
  -Dmulti.collection.it.enabled=true \
  -Dtestcontainers.pg.image=pgvector/pgvector:pg16 \
  -Dtest=MultiCollectionRetrievalPostgresIntegrationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

### 16.3 编译

```bash
mvn clean compile test-compile
```

### 16.4 WebUI

```bash
cd spring-ai-rag-webui
npm run test:run
npx tsc -b --pretty false
npm run build
```

从仓库根目录使用既有 Maven profile 清理旧 hash 资产并同步生产 bundle：

```bash
mvn -pl spring-ai-rag-core -Pwebui generate-resources
diff -qr spring-ai-rag-webui/dist \
  spring-ai-rag-core/src/main/resources/static/webui
```

```bash
cd spring-ai-rag-webui
npx vite preview --host 127.0.0.1 --port 4175 --strictPort
```

```bash
cd spring-ai-rag-webui
BASE_URL=http://127.0.0.1:4175 \
npx playwright test e2e/search.spec.ts e2e/chat.spec.ts
```

### 16.5 服务启动

优先按
[`developer-reference-zh-CN.md`](../../developer-reference-zh-CN.md)
使用 `postgresql` profile。服务至少完成：

```text
application started
health endpoint reachable
GET Search 参数绑定可用
```

若 Mock/集成测试均通过但启动失败，再使用
`scripts/start-real-e2e-server.sh` 的 18081 隔离流程排查。真实 LLM 不是 SQL 范围改造
的首选验证手段，只有 Chat 完整链路仍有疑点时才调用。

### 16.6 文档和 Git

```bash
./scripts/verify-project-docs.sh
git diff --check
git status --short
```

## 17. 预计文件影响

### API

```text
spring-ai-rag-api/src/main/java/com/springairag/api/enums/CollectionScopeMode.java
spring-ai-rag-api/src/main/java/com/springairag/api/dto/ChatRequest.java
spring-ai-rag-api/src/main/java/com/springairag/api/dto/SearchRequest.java
spring-ai-rag-api/src/test/
```

### Core

```text
spring-ai-rag-core/src/main/java/com/springairag/core/repository/RagCollectionRepository.java
spring-ai-rag-core/src/main/java/com/springairag/core/service/CollectionIdentityResolver.java
spring-ai-rag-core/src/main/java/com/springairag/core/service/CollectionRetrievalScopeResolver.java
spring-ai-rag-core/src/main/java/com/springairag/core/retrieval/RetrievalScope.java
spring-ai-rag-core/src/main/java/com/springairag/core/retrieval/RetrievalScopeSql.java
spring-ai-rag-core/src/main/java/com/springairag/core/retrieval/HybridRetrieverService.java
spring-ai-rag-core/src/main/java/com/springairag/core/retrieval/fulltext/
spring-ai-rag-core/src/main/java/com/springairag/core/advisor/HybridSearchAdvisor.java
spring-ai-rag-core/src/main/java/com/springairag/core/config/RagChatService.java
spring-ai-rag-core/src/main/java/com/springairag/core/controller/RagChatController.java
spring-ai-rag-core/src/main/java/com/springairag/core/controller/RagSearchController.java
spring-ai-rag-core/src/main/java/com/springairag/core/controller/RagCollectionController.java
spring-ai-rag-core/src/main/java/com/springairag/core/service/JsonRecordService.java
spring-ai-rag-core/src/test/
```

### WebUI

```text
spring-ai-rag-webui/src/components/CollectionScopeSelector/
spring-ai-rag-webui/src/pages/Search.tsx
spring-ai-rag-webui/src/pages/Search.module.css
spring-ai-rag-webui/src/pages/Chat.tsx
spring-ai-rag-webui/src/pages/Chat.module.css
spring-ai-rag-webui/src/api/search.ts
spring-ai-rag-webui/src/api/chat.ts
spring-ai-rag-webui/src/api/collections.ts
spring-ai-rag-webui/src/hooks/useSSE.ts
spring-ai-rag-webui/src/types/api.ts
spring-ai-rag-webui/src/i18n/locales/
spring-ai-rag-webui/src/**/*.test.tsx
spring-ai-rag-webui/e2e/
spring-ai-rag-core/src/main/resources/static/webui/
```

### 文档

见 §12，并新增实施进度记录。

## 18. 风险与控制

| 风险 | 控制 |
|---|---|
| mode 改变旧省略语义 | 省略按 §4.2 兼容推导 |
| restricted mode 扩权 | requested mode 先经 ACL 转 effective scope |
| deprecated 未知数字 ID 被意外改成 404 | 不查询存在性；保留零命中兼容测试 |
| selected 空集合退化为全局 | resolver 拒绝；内部意外空值 `matchNone` |
| 只改 Vector 导致 FTS 越界 | Fulltext SPI 强制接收同一 scope |
| JSON record 跨类型 | SQL `document_type` + 回表二次校验 |
| JDBC Array 绑定差异 | `SqlArrayValue` + 真实 PostgreSQL 测试 |
| HNSW filtered recall | 不伪造保证；保留后续 iterative/exact 基准阶段 |
| UI 发空数组 | 共享 request builder 和 unit/e2e 断言 |
| UI 搜索丢失已选项 | selected key 独立于当前 page data |
| Collection 列表很大 | 服务端 query + 分页，不固定加载前 200 |
| 大量构造器测试破坏 | 保留兼容 overload/constructor，逐步迁移 |
| 其他开发者并行改动 | 不 stash、不回退，只做最小适配 |

## 19. 回滚与可逆边界

- API 新 mode 是增量字段；回滚后旧客户端仍可按 key 列表工作。
- 旧 Hybrid Retriever overload 保留，可在紧急回滚时切回 document IDs。
- 无 schema migration，代码回滚不涉及数据恢复。
- WebUI selector 可独立回滚为旧单选，不影响后端 mode。
- Collection list `query` 为新增可选参数。
- `EACH_COLLECTION` 未进入当前契约，不产生半成品兼容负担。
- ANN 参数未改变，回滚不涉及索引或连接池 session 状态。

## 20. 验收标准

- [ ] Chat/Search 三种 mode 契约和兼容推导全部实现。
- [ ] restricted API Key 不能通过 mode 扩大 allow-list。
- [ ] key/ID 冲突、显式空、超限均返回 400。
- [ ] unrestricted 未知 key 404，restricted 未知/未授权 key 403。
- [ ] deprecated 未知/soft-deleted 数字 ID 对 unrestricted 调用方保持 200 + 空结果。
- [ ] 多 key 使用 batch Repository 查询。
- [ ] 生产 Chat/Search/JSON 路径不展开 Collection document IDs。
- [ ] Vector、pg_jieba、English FTS、pg_trgm 使用同一 scope。
- [ ] SQL 使用 `d.collection_id` predicate 和 JDBC bigint array。
- [ ] Collection + explicit document IDs 在 SQL 内取交集。
- [ ] unrestricted `CALLER_VISIBLE` 包含 unassigned。
- [ ] `ANY_COLLECTION` 排除 unassigned。
- [ ] JSON record 保持显式 Collection 范围并下推 document type。
- [ ] selected 空 Collection 返回空结果，不搜索全库。
- [ ] WebUI 支持分页搜索和多选，不发送空 key 列表。
- [ ] WebUI 标签不再把 unscoped 误称为“All Collections”。
- [ ] 后端目标测试和真实 PostgreSQL 集成测试通过。
- [ ] `mvn clean compile test-compile` 通过。
- [ ] WebUI Vitest、独立 tsc、production build 和核心 Mock Playwright 通过。
- [ ] Maven `webui` profile 同步后的内嵌静态资源与 `dist/` 逐文件一致。
- [ ] 服务可使用 `postgresql` profile 启动并响应健康检查。
- [ ] 正式中英文文档同步。
- [ ] 基本门禁通过后，连续三轮实现代码检查无问题且无修改。

## 21. 默认决策与待讨论项

### 21.1 已给出默认，不阻断实施

| 事项 | 默认 | 理由 | 可逆边界 |
|---|---|---|---|
| mode 省略 | 兼容推导 | 不破坏客户端 | 后续大版本可要求显式 |
| Collection 上限 | 100 | 与 API Key 对齐 | 配置化前可调整常量 |
| document IDs 上限 | 1000 | 控制数组和滥用 | 依据真实客户端调整 |
| SQL 数组 | `SqlArrayValue` | 标准 JDBC 资源管理 | 可换 NamedParameter/自定义 binder |
| JSON mode | 仅 selected | 保持安全默认 | 未来显式评审后扩展 |
| HNSW iterative | 不自动启用 | 缺生产版本和 recall 基准 | 独立配置阶段 |
| EACH | 不实施 | 独立覆盖语义和成本 | 后续增量字段 |
| Collection query | name/key 模糊匹配 | 适配业务 key 查找 | 大规模时增加索引 |

### 21.2 无阻断待讨论项

当前没有阻断实施的领域缺口。

唯一需要在后续性能阶段补充的数据是生产 Collection/embedding 规模和 filtered ANN
recall 基线；它不阻断本轮范围语义与 SQL 下推。

## 22. 中断后恢复入口

恢复实施时按以下顺序读取：

1. 本文 §4-9：冻结的 API、ACL、内部 scope 和 SQL 设计。
2. [关联调研 §2](2026-08-15_MULTI_COLLECTION_RETRIEVAL_SCOPE_RESEARCH.md#2-需要区分的三种产品语义)：
   范围与覆盖的通俗语义。
3. 本文 §13：一次性测试矩阵。
4. 本文 §15：实施阶段和退出条件。
5. 实施进度文档：查看当前阶段、门禁和检查计数器。

若代码基线发生变化，以当前代码和正式 `docs/` 为事实来源，但不得在没有记录设计变更
的情况下改变以下核心语义：

- `CALLER_VISIBLE` / `ANY_COLLECTION` / `SELECTED_COLLECTIONS`；
- restricted 调用方不能扩权；
- selected 空范围 fail closed；
- Collection predicate 直接下推；
- JSON record 必须显式范围；
- 本轮不实现 `EACH_COLLECTION`；
- 本轮不自动调优 ANN session 参数。
