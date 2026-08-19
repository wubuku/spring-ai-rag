# 外部文档幂等更新与重索引实施规划

> 状态：实施已完成，验收通过
> 规划日期：2026-08-16  
> 实施授权：已完成连续三轮系统性审查，无实质问题且期间无修改；可直接开始实施  
> 进度账本：[外部文档幂等更新与重索引实施进度](2026-08-16_EXTERNAL_DOCUMENT_UPSERT_AND_REINDEX_IMPLEMENTATION_PROGRESS.md)

## 1. 执行摘要

当前系统能够创建普通文档并单独触发嵌入，也已经为 JSON structured record
实现了 `collectionKey + externalId` 幂等 upsert，但普通外部文档仍没有稳定身份更新
接口。调用方只能：

1. 再次调用普通创建接口，得到内容哈希去重结果或创建另一条文档；
2. 先按内部 `documentId` 删除，再创建和嵌入；
3. 自行保存 RAG 内部 ID，并组合多个非原子 API。

这些做法都不适合外部内容源的持续同步：内部 ID 泄漏到调用方状态、网络重试不安全、
并发更新可能乱序、删除和重建期间存在检索空窗，且无法明确判断当前向量是否对应最新
外部版本。

本次实施新增普通外部文档同步契约：

```text
稳定身份：collectionKey + externalId
外部版本：sourceRevision
并发前置条件：expectedSourceRevision（可选）
内容新鲜度：当前 contentHash + 活动 Embedding Profile 状态
```

本次必须落地：

- 单条 upsert、批量 upsert、稳定身份查询和幂等源删除；
- 同一内部文档原位更新，不因内容变化创建新的 `documentId`；
- `sourceRevision` 重放与可选 CAS，防止乱序覆盖；
- 内容变化后同步重新切分和嵌入，短事务原子替换向量；
- 新内容嵌入失败时旧向量物理保留但不可检索，并正确记录当前失败状态；
- tombstone 删除，保留身份、版本和审计，可由后续新版本恢复；
- Collection ACL、版本历史、导入导出、克隆、OpenAPI 和 WebUI 状态展示；
- PostgreSQL/Flyway、Controller、Service、Embedding、前端和 Playwright 验收。

本次不建设外部 URL 抓取、定时轮询、Connector 配置中心或异步任务平台。外部系统负责
发现变化、读取源数据和调用同步 API；RAG 服务负责稳定身份、幂等、并发控制、持久化、
嵌入与检索一致性。

## 2. 规划边界与实质性缺陷标准

### 2.1 本次范围

- 文本型普通文档，`documentType != json-record`。
- PostgreSQL 主 profile。
- 活动 Embedding Profile 的同步嵌入。
- API Key Collection ACL。
- 管理 WebUI 的可观测性与手工重试。
- Collection export/import/clone 对新增字段的兼容。

### 2.2 明确非目标

- 不替换现有普通 `POST /documents`、上传、PDF、按内部 ID 删除接口。
- 不改变 JSON structured record 的 payload/retrievalText 专用 API。
- 不让服务端主动访问任意 URL，避免 SSRF、上游凭据、代理和轮询责任进入核心服务。
- 不在本次增加 ingestion-only/write-only API Key 角色；调用方使用独立业务 Key 并通过
  `allowedCollectionKeys` 限定 Collection。
- 不引入 Kafka、outbox、后台 worker 或异步 job API。
- 不复制 embedding 到 Collection clone；clone 后仍需重新嵌入。
- 不实现文档版本 rollback；版本表在本次仍承担快照和审计职责。
- 不在首轮给 multipart 文件和 PDF API 增加外部身份参数。它们的后续适配必须委托同一
  upsert service，不能实现第二套身份或并发语义。

### 2.3 规划审查中的实质性缺陷

只有以下问题需要修改规划并把连续无修改计数归零：

- 方案无法按当前模块和依赖实现；
- 出现绕过 API Key Collection ACL 的路径；
- Flyway 可能静默覆盖、合并或丢失既有身份数据；
- 并发或重试可令旧内容覆盖新内容；
- 更新后旧向量仍可能参与检索；
- 核心验收缺少可执行验证方法。

行号偏差、措辞、格式、非穷举文件清单和实施中自然暴露的次要适配不触发计数归零。

## 3. 当前实现快照

### 3.1 普通文档路径

[`RagDocumentController`](../../../spring-ai-rag-core/src/main/java/com/springairag/core/controller/RagDocumentController.java)
当前提供普通创建、详情、列表、按内部 ID 硬删除、批量创建、上传和嵌入相关端点。
普通创建通过全局 `contentHash` 查重：

- 相同内容可能直接返回另一条已有文档；
- 查重不表达“这是同一个外部对象”；
- 没有 `PUT/PATCH/upsert`；
- 没有按外部身份查询或删除；
- 普通创建没有自动写入完整版本历史。

[`BatchDocumentService`](../../../spring-ai-rag-core/src/main/java/com/springairag/core/service/BatchDocumentService.java)
也按内容哈希创建或复用文档，不适合作为外部同步入口。

### 3.2 已有 JSON structured record 参照实现

[`JsonRecordService`](../../../spring-ai-rag-core/src/main/java/com/springairag/core/service/JsonRecordService.java)
已证明以下模式可在本项目中工作：

- `collectionKey + externalId` 解析为内部 Collection 身份；
- PostgreSQL `pg_advisory_xact_lock` 串行化同一身份；
- 持久化事务与远程 embedding provider 调用分离；
- `CREATED / UPDATED / UNCHANGED`；
- retrievalText 变化时重嵌入；
- payload-only 变化时保留新鲜向量；
- 完全重放但缺少新鲜向量时可重试 embedding；
- 批量请求按项隔离失败并保持输入顺序。

V29 迁移
[`V29__add_jsonb_structured_records.sql`](../../../spring-ai-rag-core/src/main/resources/db/migration/V29__add_jsonb_structured_records.sql)
当前只保证：

```sql
(collection_id, document_type, external_id)
WHERE document_type = 'json-record'
```

唯一。普通文档可写 `external_id`，但数据库没有 Collection 级统一唯一约束。

### 3.3 Embedding 原子性与 freshness

[`DocumentEmbedService`](../../../spring-ai-rag-core/src/main/java/com/springairag/core/service/DocumentEmbedService.java)
在数据库事务外完成切分和 provider 调用，校验结果数量、顺序、维度和有限数值后，再调用
[`EmbeddingPersistenceService`](../../../spring-ai-rag-core/src/main/java/com/springairag/core/service/EmbeddingPersistenceService.java)
提交。

持久化事务会：

1. `FOR UPDATE` 锁定文档；
2. 校验 JPA version、`content_hash` 和 enabled；
3. 删除活动 Profile 的旧 chunks；
4. 写入全部新 chunks；
5. 将 Profile state 更新为 `COMPLETED`；
6. 更新文档处理状态。

任一向量插入失败会回滚整个替换事务，旧向量不会被部分覆盖。

[`EmbeddingProfileSqlScope`](../../../spring-ai-rag-core/src/main/java/com/springairag/core/retrieval/EmbeddingProfileSqlScope.java)
要求：

```text
活动 embedding_profile_id
state.status = COMPLETED
state.content_hash = rag_documents.content_hash
rag_documents.enabled = true
```

所以内容更新后旧向量即使仍存在，也不会继续参与检索。

当前缺口是 `recordFailureIfNoCompleted()`：只要已有旧 `COMPLETED` state，就不记录新内容
的失败。检索安全没有被破坏，但文档可能停留在 `PENDING`，state 仍显示旧 hash 的
`COMPLETED`，失败原因不可观测。本次必须修复。

### 3.4 版本、导入导出和克隆

[`DocumentVersionService`](../../../spring-ai-rag-core/src/main/java/com/springairag/core/service/DocumentVersionService.java)
支持强制快照，但普通文档创建未统一调用。JSON record 和 clone 的部分路径会写版本。

Collection export 已包含 `externalId`；import 对 JSON record 委托专用 service，对普通
文档直接保存；clone 会复制 `externalId`，但只为 JSON record 写初始版本。新增
`sourceRevision` 后必须让这些路径保持字段完整和 Collection 内唯一。

### 3.5 API Key 与 Collection ACL

认证过滤器允许环境 root、数据库业务 Key 和 legacy 模式访问数据面。Collection ACL
通过 [`ApiKeyCollectionAccess`](../../../spring-ai-rag-core/src/main/java/com/springairag/core/security/ApiKeyCollectionAccess.java)
将稳定 `collectionKey` 解析为当前 Key 可访问的内部 ID。

本次所有新入口必须通过同一解析器：

- 限定 Key 不得探测未授权 Collection 是否存在；
- upsert、查询和源删除都要求目标 Collection 可访问；
- 每个外部 Connector 推荐使用独立业务 Key，只允许它负责的 Collection。

## 4. 目标行为与不变量

### 4.1 身份不变量

一个非空外部身份在同一 Collection 内最多对应一条文档：

```text
UNIQUE (collection_id, external_id)
WHERE collection_id IS NOT NULL AND external_id IS NOT NULL
```

`documentType` 不参与唯一身份。这样不会出现同一上游对象被普通文档和 JSON record
各自占用一次的歧义。

`externalId`：

- 由调用方生成并长期稳定；
- 区分大小写；
- 去除首尾空白后存储；
- 最大 255 字符；
- 不允许在已有文档上修改；
- 推荐值是上游数据库主键、CMS canonical ID、对象存储 key 或稳定 URL，而不是标题。

### 4.2 来源版本不变量

`sourceRevision` 是调用方提供的 opaque、区分大小写、最大 255 字符的版本令牌。
本端点强制要求非空。调用方可使用：

- 数据库行版本；
- ETag；
- Git commit/blob ID；
- 上游 `updated_at` 加主键；
- 调用方计算的 canonical payload SHA-256。

不得使用 JPA `@Version` 作为外部版本，因为 embedding 提交、metadata 更新等内部操作也
会递增该字段。

### 4.3 检索一致性不变量

- 文档当前内容没有活动 Profile 的 fresh `COMPLETED` state 时，不得被向量或全文路径
  召回。
- 源删除文档 `enabled=false`，不得被检索。
- 新 embedding 全部验证并成功提交前，不删除旧向量。
- 新内容嵌入失败后，旧向量可物理保留用于诊断/重试，但 state 必须改为当前 hash 的
  `FAILED`，因此不可检索。
- metadata/title/source/sourceRevision 变化而内容不变、且当前内容已有 fresh embedding
  时，不调用 embedding provider；若 `embed=true` 且 freshness miss，仍必须补做 embedding。

### 4.4 重试与并发不变量

- 精确重放必须优先于 CAS 判断，以支持“提交成功但响应丢失”后的原请求重试。
- 同一 `sourceRevision` 不得代表两个不同的文档表示；发生差异返回 409。
- 提供 `expectedSourceRevision` 时，只有当前来源版本匹配才允许写入。
- 不提供 `expectedSourceRevision` 时采用最后到达者覆盖；客户最佳实践默认要求有顺序
  风险的 Connector 使用 CAS。
- 同一外部身份的普通文档与 JSON record 使用同一个 advisory lock key。

## 5. API 契约

### 5.1 单条 upsert

```http
POST /api/v1/rag/documents/upsert
Content-Type: application/json
X-API-Key: ...
```

请求：

```json
{
  "collectionKey": "customer-42:manual:v3",
  "externalId": "cms:article:10001",
  "sourceRevision": "etag:8b4d9f",
  "expectedSourceRevision": "etag:7a3c21",
  "title": "Refund policy",
  "content": "The current refund policy is ...",
  "source": "https://kb.example.com/articles/10001",
  "documentType": "markdown",
  "metadata": {
    "locale": "en-US",
    "category": "policy"
  },
  "embed": true
}
```

字段：

| 字段 | 必填 | 规则 |
|---|---:|---|
| `collectionKey` | 是 | 稳定 Collection key；只接受单个目标 |
| `externalId` | 是 | trim 后 1–255；Collection 内稳定唯一 |
| `sourceRevision` | 是 | trim 后 1–255；opaque、区分大小写 |
| `expectedSourceRevision` | 否 | CAS 前置版本；精确重放先于该检查 |
| `title` | 是 | 1–255，匹配数据库列 |
| `content` | 是 | 1–1,000,000 字符 |
| `source` | 否 | 最大 255 |
| `documentType` | 否 | 最大 50，默认 `text`，禁止 `json-record` |
| `metadata` | 否 | JSON object |
| `embed` | 否 | 默认 `true` |

成功响应统一为 HTTP 200：

```json
{
  "documentId": 42,
  "collectionKey": "customer-42:manual:v3",
  "externalId": "cms:article:10001",
  "sourceRevision": "etag:8b4d9f",
  "action": "UPDATED",
  "contentChanged": true,
  "versionNumber": 4,
  "embeddingStatus": "COMPLETED",
  "embeddingProfileKey": "bge-m3-1024",
  "embeddingFresh": true,
  "processingStatus": "COMPLETED",
  "error": null
}
```

`action`：

- `CREATED`：首次出现；
- `UPDATED`：已有身份发生字段或来源版本变化；
- `UNCHANGED`：精确重放。

`embeddingStatus`：

- `COMPLETED`：本次生成并提交；
- `CACHED`：当前内容已有活动 Profile 的 fresh embedding；
- `NOT_REQUESTED`：`embed=false`；
- `FAILED`：文档持久化成功，但 embedding 失败。

Embedding 失败仍返回 HTTP 200，因为文档写入已经提交，响应中的 `embeddingStatus`、
`embeddingFresh=false` 和脱敏 `error` 明确表达部分成功。客户端不得把该响应重试成
“重新创建”；应以相同请求重放，服务会为同一文档重试 embedding。

### 5.2 单条冲突矩阵

| 当前状态 | 请求 | 结果 |
|---|---|---|
| 身份不存在 | 无 `expectedSourceRevision` | 创建 |
| 身份不存在 | 有 `expectedSourceRevision` | 409 |
| 当前 revision = 请求 revision，所有受管字段一致，未删除 | `expected` 任意 | `UNCHANGED`；必要时修复 embedding |
| 当前 revision = 请求 revision，但任一受管字段不同 | 任意 | 409 |
| 当前 revision != 请求 revision | `expected` 与当前一致 | 更新 |
| 当前 revision != 请求 revision | `expected` 缺省 | 更新，最后到达者覆盖 |
| 当前 revision != 请求 revision | `expected` 与当前不一致 | 409 |
| 当前为 tombstone | 新 revision，CAS 通过 | 恢复同一 `documentId` |
| 当前为 tombstone | tombstone revision 精确重放 upsert | 409，不允许旧删除版本复活 |
| 身份属于 `json-record` | 普通 upsert | 409，要求使用 JSON record API |

“所有受管字段”包括 title、content hash、source、documentType、metadata、启用/删除状态。
服务器自身维护的 processing/embedding 字段不参与 revision 冲突比较。

### 5.3 批量 upsert

```http
POST /api/v1/rag/documents/batch-upsert
```

```json
{
  "items": [
    {
      "collectionKey": "customer-42:manual:v3",
      "externalId": "cms:article:10001",
      "sourceRevision": "etag:8b4d9f",
      "title": "Refund policy",
      "content": "...",
      "embed": true
    }
  ]
}
```

规则：

- 1–50 项；
- 累计 content 不超过 5,000,000 字符；
- 保持输入顺序；
- 每项独立事务，单项持久化或 embedding 失败不回滚其他项；
- 复用单条 upsert 的全部 ACL、冲突和 freshness 语义；
- HTTP 200 返回逐项结果与 summary；
- 冲突项使用 `action=PERSISTENCE_FAILED`，`errorCode=DOCUMENT_REVISION_CONFLICT`；
- 错误文本必须脱敏且最多 500 字符。

summary 至少包含：

```text
total, created, updated, unchanged,
persistenceFailed, embeddingFailed
```

### 5.4 按稳定身份查询

```http
GET /api/v1/rag/documents/by-external-id
  ?collectionKey=customer-42%3Amanual%3Av3
  &externalId=cms%3Aarticle%3A10001
```

返回扩展后的 `DocumentDetailResponse`。不存在时 404，未授权时 403 且不泄漏 Collection
是否存在。

详情和列表新增：

```text
externalId
sourceRevision
sourceDeletedAt
processingError
embeddingFresh
```

`embeddingFresh` 由活动 Profile 的 fresh chunk count 和 `enabled` 推导，不使用
`embeddedContentHash` 作为唯一真相。

### 5.5 幂等源删除

```http
DELETE /api/v1/rag/documents/by-external-id
  ?collectionKey=customer-42%3Amanual%3Av3
  &externalId=cms%3Aarticle%3A10001
  &sourceRevision=deleted%3A2026-08-16T09%3A30%3A00Z
  &expectedSourceRevision=etag%3A8b4d9f
```

行为：

- 找不到身份：404；
- 身份属于 JSON record：409；
- 当前未删除且删除 revision 与当前 revision 相同：409，删除事件必须使用新 revision；
- CAS 不匹配：409；
- 首次成功：`enabled=false`、写 `source_deleted_at`、更新 `source_revision`、记录 `DELETE`
  版本和审计；
- 相同 tombstone revision 重放：HTTP 200，`action=UNCHANGED`；
- 已删除文档收到调用方确认的新删除 revision：CAS 通过后更新 tombstone revision，
  记录新的 `DELETE` 版本；本系统不对 opaque revision 做大小排序；
- 不物理删除 embedding；
- 后续普通 upsert 使用新的 sourceRevision 且 CAS 通过时恢复同一文档。

旧接口 `DELETE /documents/{id}` 保持物理删除语义，作为管理员显式 purge 操作兼容保留。

## 6. 数据模型与 Flyway

### 6.1 V30 迁移

实施前必须再次确认迁移目录的最新版本。按当前基线新增：

```text
V30__add_external_document_sync.sql
```

不得修改 V1–V29。

新增列：

```sql
ALTER TABLE rag_documents
    ADD COLUMN source_revision VARCHAR(255),
    ADD COLUMN source_deleted_at TIMESTAMP(6);

ALTER TABLE rag_document_versions
    ADD COLUMN source_revision_snapshot VARCHAR(255);
```

现有数据不回填虚构 revision。只有通过新同步 API 管理的文档要求非空
`source_revision`；普通旧创建和 JSON record 可保持 null。

### 6.2 唯一约束升级

创建新唯一索引前执行冲突预检：

```sql
WITH trim_chars AS (
    SELECT STRING_AGG(CHR(code), '' ORDER BY code) AS chars
    FROM GENERATE_SERIES(1, 32) AS codes(code)
)
SELECT d.collection_id,
       BTRIM(d.external_id, trim_chars.chars) AS normalized_external_id,
       COUNT(*)
FROM rag_documents d
CROSS JOIN trim_chars
WHERE d.collection_id IS NOT NULL
  AND d.external_id IS NOT NULL
  AND BTRIM(d.external_id, trim_chars.chars) <> ''
GROUP BY d.collection_id, BTRIM(d.external_id, trim_chars.chars)
HAVING COUNT(*) > 1;
```

预检按服务入口的 Java `trim()` 规范化规则执行：移除首尾 ASCII `1–32` 控制空白，
包括普通空格、制表符和换行。若存在任何规范化后的冲突，迁移直接 `RAISE EXCEPTION`，
不得自动选赢家、重命名或删除数据。若没有冲突，迁移可以把历史 external ID 的首尾
空白规范化为同一规则下的值；这不是选择赢家，而是把历史值对齐到公共 API 已冻结的
trim 语义。规范化后的空字符串不属于外部托管身份，不参与唯一索引。

预检通过后：

```sql
DROP INDEX IF EXISTS uk_rag_doc_structured_identity;

CREATE UNIQUE INDEX uk_rag_doc_external_identity
    ON rag_documents (collection_id, external_id)
    WHERE collection_id IS NOT NULL
      AND external_id IS NOT NULL
      AND external_id <> '';
```

保留 `idx_rag_doc_collection_type`。

### 6.3 实体与快照

`RagDocument`：

- 将 `externalId` 注释改为通用调用方稳定身份；
- 新增 `sourceRevision`；
- 新增 `sourceDeletedAt`。

`RagDocumentVersion`：

- 新增 `sourceRevisionSnapshot`；
- `fromDocument()` 自动复制来源版本；
- `DocumentVersionResponse` 暴露该字段；
- `changeType` 继续使用 20 字符列，新增实际值 `DELETE`，恢复使用 `UPDATE` 并在描述中
  标注 restored，避免引入新枚举兼容成本。

### 6.4 导出、导入和克隆

Collection export/import DTO 增加：

```text
sourceRevision
sourceDeletedAt
```

导入规则：

- 在创建 Collection 后、写文档前，对所有非空 `externalId` 做全类型重复预检；
- JSON record 继续走 `JsonRecordService`；
- 普通 external document 走统一 persistence 路径，`embed=false`；
- V30 以前导出的普通文档可能有 `externalId` 但没有 `sourceRevision`。内部 import 模式
  允许原样保存 null revision，但公共 upsert 仍强制非空；该文档第一次通过新 API
  同步时，调用方省略 `expectedSourceRevision`，用新的非空 revision 认领并进入正常
  CAS 生命周期；
- tombstone 保留 `enabled=false/sourceDeletedAt`；
- 普通无 externalId 文档保留 legacy 直接导入路径；
- 整个 Collection import 仍是事务，任一身份冲突回滚新 Collection。

clone 规则：

- 目标 Collection 不同，因此可保留 `externalId`；
- 保留 `sourceRevision` 和 `sourceDeletedAt` 作为来源快照；
- 不复制 embedding，所有 enabled 文档初始 `PENDING`；
- tombstone 仍 disabled；
- 为所有 cloned documents 写 `CREATE` 版本，不再只处理 JSON record。

## 7. 后端分层设计

### 7.1 API DTO

在 `spring-ai-rag-api` 新增：

```text
ExternalDocumentUpsertRequest
ExternalDocumentUpsertResponse
ExternalDocumentBatchUpsertRequest
ExternalDocumentBatchUpsertResponse
ExternalDocumentDeleteResponse
```

命名使用 `ExternalDocument`，避免误导现有 `DocumentRequest` 已支持更新，也避免与 JSON
record 的专用 DTO 混淆。

`ExternalDocumentUpsertResponse` 增加可空 `errorCode`，使批量项可机器判断 409 等失败。

`ErrorCode` 新增：

```text
DOCUMENT_REVISION_CONFLICT -> HTTP 409
```

新增 `DocumentRevisionConflictException`，由全局 `RagException` handler 自动生成 RFC 7807。

### 7.2 Controller

在现有 `RagDocumentController` 注入 `ExternalDocumentService` 并增加四个入口，同时给
Controller 增加 `@Validated`，使 GET/DELETE query 参数上的 `@NotBlank/@Size` 生效。
Service 仍执行同样的 trim、长度和必填防御校验，避免内部调用绕过约束。Controller
只负责：

- Bean Validation；
- 请求参数绑定；
- Timed/OpenAPI 注解；
- 调用 service；
- 审计由 service 或现有 AuditLogService 的统一位置完成。

稳定身份解析、ACL、并发、版本、embedding 决策不得放在 Controller。

### 7.3 ExternalDocumentService

新增独立 service，避免继续扩大 Controller 和 `BatchDocumentService`：

```text
upsert(request)
batchUpsert(items)
getByExternalIdentity(collectionKey, externalId)
sourceDelete(collectionKey, externalId, sourceRevision, expectedSourceRevision)
importExternalDocument(collectionId, importedDocument)
```

依赖：

- `RagDocumentRepository`
- `DocumentVersionService`
- `DocumentEmbedService`
- `EmbeddingProfileProvider`
- `CollectionIdentityResolver`
- `AuditLogService`（可选）
- `JdbcTemplate`
- `PlatformTransactionManager` / `TransactionTemplate`

### 7.4 Collection 与身份解析

公共 API 只接受 `collectionKey`。Service 调用：

```text
ApiKeyCollectionAccess.resolveCollectionIds(
    null, List.of(collectionKey), currentKey, collectionIdentityResolver)
  -> require exactly one resolved ID
  -> ApiKeyCollectionAccess.resolveWritableCollectionId(resolvedId, currentKey)
```

这与现有文档创建/PDF 导入的 Collection 写入解析模式一致：先按稳定 key 做
anti-enumeration ACL 解析，再经过 writable collection resolver。当前 `NORMAL` Key
同时具备 RAG 读写能力；这里的 writable resolver 负责 Collection allow-list 和缺省
Collection 约束，不在本任务新增读写角色层级。

import 已在 Collection 事务内部获得目标 ID，可调用内部方法，但仍必须执行
`requireCollectionId()`，防止未来复用时绕过 ACL。

Repository 新增：

```text
Optional<RagDocument> findByCollectionIdAndExternalId(
    Long collectionId, String externalId)
```

JSON service 可以暂时保留类型化查询，但 advisory lock key 必须统一；数据库唯一索引是最终
竞态保护。

## 8. 持久化、事务与并发算法

### 8.1 单条 upsert 时序

```text
validate DTO
  -> resolve collectionKey + ACL
  -> transaction
       -> advisory lock(collectionId, externalId)
       -> SELECT existing identity
       -> exact replay / conflict / CAS decision
       -> INSERT or UPDATE same rag_documents row
       -> forceRecordVersion when business representation changed
       -> commit
  -> if embed=true
       -> fresh cache check
       -> provider call outside transaction
       -> atomic embedding replacement or FAILED state
  -> reload document after embedding attempt
  -> build response from current document and active Profile state
```

advisory lock key固定为：

```text
collectionId + ":external-document:" + externalId
```

普通 external service 与 `JsonRecordService` 都使用该格式。

### 8.2 创建

- `expectedSourceRevision` 非空时返回 409；
- 设置 normalized externalId/sourceRevision；
- 计算 UTF-8 `size` 和 SHA-256 `contentHash`；
- `enabled=true`、`sourceDeletedAt=null`；
- `processingStatus=PENDING`、`processingError=null`；
- `saveAndFlush()`；
- 强制记录 `CREATE` 版本。

### 8.3 精确重放

在 CAS 前比较当前 `sourceRevision`：

- revision 相同且所有受管字段相同、当前非 tombstone：`UNCHANGED`；
- revision 相同但字段不同：409；
- revision 与当前 tombstone 相同：普通 upsert 409。

`UNCHANGED` 不写文档、不写版本，但 `embed=true` 且 freshness miss 时仍调用 embedding，
用于恢复先前 provider 失败或先前 `embed=false`。

若 existing identity 来自旧导入且当前 `sourceRevision=null`，第一个合法的新 API 请求
不做精确重放判断：`expectedSourceRevision` 必须省略，随后按普通更新写入首个非空
revision。服务不会为旧数据虚构初始版本。

### 8.4 更新

revision 不同时：

1. 若给出 `expectedSourceRevision`，要求与当前严格相等；
2. 比较 content hash；
3. 更新 title/content/source/type/metadata/sourceRevision；
4. 恢复 tombstone 时设置 `enabled=true/sourceDeletedAt=null`；
5. 内容变化时设置 `PENDING` 并清除 processing error；
6. 内容不变时保留已有 embedding processing 状态；若 `embed=true` 且 freshness miss，
   在持久化提交后补做 embedding，fresh cache 命中则不调用 provider；
7. `saveAndFlush()`；
8. 强制记录 `UPDATE` 版本，描述中列出 `content`、`metadata/title/source/type/revision`
   或 `restored`。

即使只有 sourceRevision 变化也写版本，因为这代表调用方确认了新的外部快照。

### 8.5 Embedding 失败

将 `recordFailureIfNoCompleted()` 重构为表达真实语义的 `recordFailure()`：

- 仍先锁文档并校验 version/hash；
- 不因旧 `COMPLETED` state 存在而提前返回；
- upsert 当前 hash 的 `FAILED` state、`chunk_count=0` 和脱敏错误；
- 更新文档 `processingStatus=FAILED`、`processingError`；
- 不删除旧 chunks；
- freshness SQL 因 state 不再是 `COMPLETED` 而排除旧 chunks。

Provider 成功后，现有 `replace()` 删除旧 chunks、写新 chunks并将状态恢复为
`COMPLETED`。无论 provider 成功、失败还是 cache hit，ExternalDocumentService 都在构造
响应前重新读取文档并查询活动 Profile freshness，确保 `processingStatus`、
`processingError` 和 `embeddingFresh` 反映提交后的数据库状态，而不是持久化事务返回的
旧实体快照。

### 8.6 源删除

源删除与 upsert 使用相同 transaction/advisory lock：

- 找到文档并验证类型；
- 先处理 tombstone revision 精确重放；
- 再验证 sourceRevision 差异和 CAS；
- 设置 `enabled=false`、新 `sourceRevision`、`sourceDeletedAt=now()`；
- 保留 content/hash/processing state/chunks；
- 写 `DELETE` 版本；
- 写审计日志；
- 提交。

### 8.7 并发场景

必须覆盖：

- 两个首次创建同一身份：advisory lock 串行，最多一条文档；
- 同 revision 相同内容并发：一个创建/更新，一个 `UNCHANGED`；
- 同 revision 不同内容并发：一个成功，一个 409；
- r2/r3 都 expected r1：只有先获得锁者成功，另一个 409；
- embedding 期间发生 metadata/revision-only 更新：现有一次 version retry 可复用同内容向量；
- embedding 期间内容更新：提交 hash 校验失败，不得把旧生成结果写给新内容；
- upsert 与 source delete 并发：同一 identity lock 决定顺序，CAS 防止调用方未预期覆盖。

## 9. 查询、响应与 WebUI

### 9.1 Mapper 与响应

`DocumentSummary`、`DocumentDetailResponse` 和 `DocumentMapper` 增加：

```text
externalId
sourceRevision
sourceDeletedAt
processingError
embeddingFresh
```

`RagEmbeddingRepository.countFreshChunksByDocumentIdAndProfileId()` 增加
`d.enabled = true`，使 disabled tombstone 的 `chunkCount=0/embeddingFresh=false` 与实际
可检索性一致。

为既有 Java 调用保留兼容构造器，减少非相关测试和下游源码破坏。

### 9.2 Documents 页面

[`Documents.tsx`](../../../spring-ai-rag-webui/src/pages/Documents.tsx) 本次做管理面增强：

- 列表增加紧凑的“来源身份”和“索引状态”列；
- 来源身份显示 `externalId`，次级文本显示 `sourceRevision`；非 external 文档显示 `—`；
- 状态区分 `Fresh / Pending / Failed / Deleted`；
- `Failed` 使用 `processingError` 的安全摘要或 tooltip；
- enabled 且不 fresh 的文档显示带图标的“重试嵌入”按钮，复用
  `POST /documents/{id}/embed`；
- 成功后 invalidate document list/detail/version queries；
- tombstone 行不显示重试按钮；
- 保留现有管理员硬删除和版本按钮。

本次不在 WebUI 增加 Connector 配置、批量同步表单或 sourceRevision 编辑器。外部系统使用
API；WebUI 负责观察和修复。

### 9.3 i18n

WebUI 已有中英文 locale，新增的用户可见字符串必须同步：

- external identity；
- source revision；
- fresh/pending/failed/deleted；
- retry indexing；
- retry success/failure。

## 10. JSON record、旧 API 与其他路径兼容

### 10.1 JSON record

- 专用 API 和 JSONB payload 语义不变；
- V30 后 external identity 在 Collection 内跨类型唯一；
- JSON service 与普通 external service 使用相同 advisory lock key；
- 现有 JSON record 的 `sourceRevision` 保持 null，本次不强制迁移；
- 普通 external endpoint 遇到 JSON record 身份返回 409；
- 后续可单独为 JSON record 增加 sourceRevision/CAS，但不阻塞本次。

### 10.2 普通 create/batch/upload/PDF

- 旧接口不写 externalId/sourceRevision，因此不受新唯一索引影响；
- 旧 contentHash 查重行为保持兼容；
- 新客户同步不得使用旧 create + delete 组合；
- 后续 multipart/PDF 适配要求 `externalId` 和 `sourceRevision` 成对出现，文本提取完成后
  委托 `ExternalDocumentService`；
- `embed-vector-reembed` 继续是运维修复，不是正常外部同步协议。

### 10.3 移动文档到其他 Collection

现有“添加文档到 Collection”会修改 `collection_id`。对于有 `externalId` 的文档，这会
改变稳定身份命名空间并可能触发目标 Collection 唯一冲突。

本次必须加保护：

- external-managed 文档不允许通过旧 add-document API 改 Collection；
- 返回 409，并提示通过目标 Collection 新身份 upsert，再显式 purge 旧文档；
- 无 externalId 文档保持原行为。

这避免绕开 advisory lock、版本和 CAS 改变身份。

## 11. 安全、审计与日志

- 所有新 public endpoint 都受现有 API Key filter 保护。
- 每次 upsert/get/delete 先解析 Collection ACL，再查文档。
- restricted Key 请求未知或无权 key 时保持现有 anti-enumeration 403。
- 日志记录 collectionId/documentId/action，不记录全文、API Key 或完整 metadata。
- 响应和日志中的 provider/数据库错误通过 `SensitiveDataMaskingConverter` 脱敏并截断。
- 审计至少记录 CREATE、UPDATE、DELETE、embedding retry；详情只包含字段名和版本号。
- API Key 最佳实践：每个 Connector 独立 Key、最小 Collection allow-list、独立轮换和撤销。

## 12. 测试与验收方案

测试必须先整体设计完成，再实施。禁止以 review 代替测试，也不采用“发现一个问题再补
一个测试”的零散模式。

### 12.1 API DTO 与 validation

覆盖：

- 必填 collectionKey/externalId/sourceRevision/title/content；
- 255/50/1,000,000 边界；
- embed 默认 true；
- batch empty、超过 50、累计 content 超限；
- response 序列化不包含内部 JPA version。

### 12.2 ExternalDocumentService 单元测试

一次性覆盖：

- create；
- exact replay；
- replay 后修复 missing/failed embedding；
- content update；
- fresh 命中的 metadata/revision-only update 不调用 provider；缺少 fresh embedding
  且 `embed=true` 时必须调用 provider；
- same revision divergent fields 409；
- expected revision success/mismatch；
- expected revision on missing identity 409；
- legacy null revision 首次认领与 import round-trip；
- JSON identity conflict；
- tombstone delete/replay/new delete revision；
- restore same content 使用 cache；
- restore changed content 重嵌入；
- batch 顺序、partial failure、summary；
- 错误脱敏与长度；
- ACL 解析使用稳定 collectionKey；
- advisory lock key 与 JSON service 一致。

### 12.3 Controller / MockMvc

新增聚焦 WebMvc 测试：

- 四个新路径存在；
- valid body/query 正确委托 service；
- Bean Validation 400；
- revision conflict 409 RFC 7807；
- not found 404；
- response 字段；
- OpenAPI 声明 200/400/403/404/409。

### 12.4 PostgreSQL/Flyway 集成测试

新增 opt-in Testcontainers 测试，例如：

```text
ExternalDocumentSyncPostgresIntegrationTest
-Dexternal-document.it.enabled=true
```

使用 `pgvector/pgvector:pg16`，覆盖：

1. 空库执行 Flyway V1–V30；
2. 从 V29 升级到 V30；
3. source revision/tombstone/version snapshot round-trip；
4. 跨 documentType 的 Collection externalId 唯一；
5. 构造重复身份后 V30 迁移安全失败且数据不变；
6. 并发创建同一身份只有一个内部文档；
7. 两个 CAS 更新只有一个成功；
8. content 变化后旧向量因 hash/state 不匹配不可检索；
9. 旧 `COMPLETED` state 存在时，新内容 embedding 失败会：
   - 保留旧 chunk 行；
   - state 变为当前 hash 的 `FAILED`；
   - 文档状态为 `FAILED`；
   - 检索返回空；
10. 随后成功重试原子替换旧 chunk 并恢复可检索；
11. tombstone 后向量保留但检索为空，恢复后同内容可重新可见。

若测试环境无法启动 Docker，不能把“跳过”当成通过；应使用现有可用 PostgreSQL
连接执行等价测试，或明确报告外部环境阻塞。

### 12.5 后端 HTTP 集成与真实服务冒烟

新增 `scripts/external-documents-e2e.sh`，复用现有脚本模式且不输出 Key/全文，覆盖：

- 创建临时 Collection；
- upsert r1；
- exact replay；
- GET by external identity；
- metadata-only r2（fresh 命中不调用 provider；无 fresh 时按 `embed=true` 补嵌入）；
- content r3 + 检索新 token；
- stale expected revision 409；
- same revision divergent content 409；
- batch partial failure；
- source delete r4 + 检索不可见；
- delete replay；
- upsert r5 restore；
- restricted Collection API Key 可写允许 Collection、拒绝其他 Collection；
- 清理临时数据。

脚本默认 `BASE_URL=http://127.0.0.1:18081`，从环境读取 API Key，不打印凭据。

### 12.6 WebUI

Vitest 覆盖：

- externalId/sourceRevision 渲染；
- Fresh/Pending/Failed/Deleted；
- failed 文档显示 retry；
- deleted/fresh 文档不显示 retry；
- retry 成功 invalidates queries；
- retry 失败 toast。

Mock Playwright 覆盖：

- Documents 页面显示外部身份和失败状态；
- 点击 retry 发出 embed 请求；
- 成功后状态刷新为 Fresh；
- tombstone 显示 Deleted 且没有 retry。

### 12.7 基本验证硬门槛

实现完成后、代码三轮审查前，必须全部通过：

```bash
# 聚焦后端测试，包含真实 PostgreSQL/Flyway
mvn -pl spring-ai-rag-core -am \
  -Dexternal-document.it.enabled=true \
  -Dtest=ExternalDocumentServiceTest,ExternalDocumentControllerWebTest,\
ExternalDocumentSyncPostgresIntegrationTest,DocumentEmbedServiceTest,\
EmbeddingProfilePostgresIntegrationTest,OpenApiContractTest \
  -Dsurefire.failIfNoSpecifiedTests=false test

# 后端编译与测试编译
mvn clean compile test-compile

# WebUI
cd spring-ai-rag-webui
npx tsc -b
npm run build
npm run test:run -- Documents
npx playwright test e2e/documents.spec.ts
```

然后使用隔离端口和数据库启动当前后端，确认：

- Flyway 到 V30；
- readiness 为 UP；
- OpenAPI 可获取；
- 运行 `external-documents-e2e.sh`。

如可用 embedding API Key 已存在于 `.env`，真实 HTTP 冒烟执行真实 embedding。若 Key
无效，先完成 deterministic fake/provider 集成测试并明确报告真实 provider 阻塞，不能
伪报真实调用通过。

## 13. 实施步骤

### 阶段 0：工作区保护

1. 记录 `git status --short` 和并行修改文件。
2. 再次确认 Flyway 最大版本。
3. 不 stash、不 reset、不 checkout 他人文件。
4. 对重叠文件先查看 working-tree diff，再做最小增量编辑。

### 阶段 1：测试契约与 API DTO

1. 一次性创建 DTO、ErrorCode、异常和对应测试。
2. 扩展 OpenAPI required schemas/path assertions。
3. 创建 Controller WebMvc 测试骨架和完整行为矩阵。

### 阶段 2：V30、实体与 Repository

1. 新增迁移预检、列和唯一索引。
2. 更新 RagDocument/RagDocumentVersion。
3. 更新 Repository identity/fresh count。
4. 增加 PostgreSQL 迁移与约束测试。

### 阶段 3：Service 与 Embedding

1. 实现 ExternalDocumentService。
2. 统一 external identity advisory lock key。
3. 修复 embedding failure state。
4. 完成 Service 和 PostgreSQL 并发/freshness 测试。

### 阶段 4：Controller 与兼容路径

1. 接入四个 endpoint。
2. 更新 detail/list mapper。
3. 更新 import/export/clone。
4. 阻止 external-managed 文档通过旧接口移动 Collection。
5. 跑 API/core 聚焦测试。

### 阶段 5：WebUI

1. 扩展 TypeScript API types/client。
2. 增加状态列和 retry 操作。
3. 同步中英文 locale。
4. 更新 Vitest、Mock 和 Playwright。

### 阶段 6：长青文档与 E2E

同步中英文：

- `docs/rest-api.md` / `docs/rest-api-zh-CN.md`：完整 API 与客户最佳实践；
- `docs/architecture.md` / `docs/architecture-zh-CN.md`：身份、事务、freshness 和 tombstone；
- `docs/testing-guide.md` / `docs/testing-guide-zh-CN.md`：PostgreSQL 与 HTTP E2E；
- `docs/project-context.md` / `docs/project-context-zh-CN.md`：稳定能力与 Flyway V30；
- `docs/developer-reference.md` / `docs/developer-reference-zh-CN.md`：新脚本命令；
- `docs/index.md` / `docs/index-zh-CN.md`、`AGENTS.md` 和 project-docs Skill 中的 Flyway
  范围仅做必要的 V29→V30 更新。

面向客户的最佳实践至少明确：

1. 每个来源对象使用稳定 externalId；
2. 每次上游变更生成新 sourceRevision；
3. 有乱序风险时总是发送 expectedSourceRevision；
4. 请求超时后原样重放，不改 revision；
5. 只有 `embeddingFresh=true` 才视为可检索完成；
6. `embeddingStatus=FAILED` 时原样重放；
7. 删除发送独立 deletion revision；
8. 每个 Connector 使用独立且 Collection 限定的 API Key；
9. 不使用旧 create/delete 或批量 reembed 作为同步协议。

### 阶段 7：验证与实现审查

1. 通过第 12.7 节全部硬门槛。
2. 更新进度账本记录命令与结果。
3. 固定范围执行实现审查：
   - 第 1 轮：API、ACL、身份、CAS、并发；
   - 第 2 轮：迁移、数据安全、Embedding freshness、失败恢复；
   - 第 3 轮：WebUI、文档、测试覆盖、发布回滚。
4. 任一轮修改代码则计数归零，并重跑受影响验证后重新开始三轮。

## 14. 发布、迁移和回滚

### 14.1 发布前

- 查询生产数据是否存在 `(collection_id, external_id)` 跨类型重复；
- 估算带 externalId 行数和索引创建时间；
- 确认所有运行实例都兼容 V30；
- 备份数据库；
- 停止旧写入实例或使用维护窗口执行迁移。

本项目当前不是为 online concurrent index migration 设计，V30 使用普通事务迁移，不在
Flyway transaction 中尝试 `CREATE INDEX CONCURRENTLY`。

### 14.2 发布顺序

1. 停止旧版本写入。
2. 部署新版本，Flyway 执行 V30。
3. 检查 migration、Hibernate validate、活动 Embedding Profile。
4. 启动服务。
5. 执行临时 Collection 冒烟。
6. 开放 Connector 流量。

### 14.3 回滚

Flyway 没有自动 down migration。应用回滚边界：

- V30 新列对旧应用无害；
- 新唯一索引可能拒绝旧应用原本允许的跨类型重复 externalId，这是有意保护；
- 若必须回滚应用，先停止新 external API 流量；
- 不删除 V30 列和数据；
- 只有确认业务允许重新出现身份歧义时，才通过新的 forward-only migration 调整唯一索引；
- 任何 schema 回退都不得编辑已执行的 V30。

## 15. 风险与缓解

| 风险 | 后果 | 缓解 |
|---|---|---|
| 既有 externalId 跨类型重复 | V30 无法迁移 | 预检后 fail closed，不自动改数据 |
| 调用方复用 revision 但改变内容 | 幂等语义破坏 | 409 |
| 调用方不使用 expected revision | 乱序最后写入覆盖 | API 支持 CAS，文档将其列为默认最佳实践 |
| provider 失败 | 新内容不可检索 | 持久化 FAILED、旧 chunk 保留、原请求可重放 |
| embedding 慢 | HTTP 延迟高 | 本阶段同步换取简单一致性；异步 worker 是后续可逆扩展 |
| tombstone 被旧事件恢复 | 已删除内容重新出现 | 新 revision + 可选 CAS；同 tombstone revision upsert 409 |
| 旧 add-to-collection 改变身份命名空间 | 唯一冲突或身份漂移 | external-managed 文档禁止移动 |
| WebUI 误把物理 chunk 当 fresh | 状态误报 | 使用 active Profile fresh state + enabled |
| 与多集合检索并行修改冲突 | 覆盖他人工作 | 不 stash；重叠文件逐段 working-tree diff |
| 大批量请求占用 provider | 延迟和限流 | 50 项、累计 5M 字符、逐项隔离 |

## 16. 后续可逆扩展

以下不阻塞本次：

- async upsert：在保持相同 identity/revision/CAS 契约下增加 job/outbox；
- multipart/PDF external sync：提取文本后委托 ExternalDocumentService；
- source connector SDK；
- ingestion-only API Key role；
- JSON record sourceRevision/CAS；
- bulk tombstone 和全量 reconciliation manifest；
- 来源版本排序策略。当前 revision 是 opaque，只做相等比较，避免错误假设所有来源版本都
  可排序。

## 17. 完成定义

只有同时满足以下条件才算完成：

- V30 空库和 V29 升级验证通过；
- 单条/批量 upsert、查询、source delete API 实现并进入 OpenAPI；
- ACL、幂等、CAS、并发和冲突矩阵有自动化测试；
- 内容更新后旧向量不可检索；
- 旧 completed state 存在时的新内容失败能正确落为 FAILED；
- 成功重试能原子替换并恢复检索；
- tombstone 删除和恢复通过真实 PostgreSQL/HTTP 验证；
- WebUI 能显示来源身份与真实索引状态并重试；
- Maven compile/test-compile、聚焦测试、服务启动、前端 tsc/build/Vitest/Playwright
  全部通过；
- 实现代码连续三轮无修改审查通过；
- 中英文长青文档同步；
- 文档门禁、`git diff --check` 和最终 diff 边界检查通过；
- 没有丢弃、覆盖或 stash 并行工作区修改。
