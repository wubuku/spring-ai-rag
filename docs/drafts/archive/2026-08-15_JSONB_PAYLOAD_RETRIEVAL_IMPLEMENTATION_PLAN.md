# JSONB 结构化记录导入、嵌入与检索实施规划

> **状态**：已实施；本文保留冻结的设计依据、验收标准和实现边界。
> **规划日期**：2026-08-15
> **代码基线**：`main` / `2fb37dedc5cd`；实际实现包含工作区内并行
> Collection Key 与 Embedding Profile WIP，当前 Flyway 上限为 V29。
> **并行工作边界**：当前另有 Embedding Profile / 固定维度向量迁移 WIP，
> 已占用拟议的 Flyway V25/V26。本文所有迁移均写作 `V{next}`，实施时选择
> rebase 后的下一个可用版本，禁止覆盖或重编号他人的迁移。
> **实施进度**：[实施进度跟踪](2026-08-15_JSONB_PAYLOAD_RETRIEVAL_IMPLEMENTATION_PROGRESS.md)。
> 连续三轮无修改检查的时间、范围和结论只在会话中汇报，不写入本文，避免为了
> 记录检查而破坏“连续无修改”的终止条件。

## 1. 执行摘要

本功能把一条“需要被语义检索的 JSON 数据”建模为同一条
`rag_documents` 记录中的两个职责明确的字段：

- `jsonbPayload` / `jsonb_payload JSONB`：调用者提交、服务保存并在专用检索结果中
  原样按 JSON 语义返回的业务数据；
- `retrievalText` / `rag_documents.content TEXT`：调用者为该 JSON 数据提供的自然语言
  描述，只有它参与 SHA-256、分块、全文索引、embedding 和 RAG prompt。

服务不生成 `retrievalText`，也不校验它与 `jsonbPayload` 是否一致。两者的业务对应
关系由调用者负责，这是明确的责任边界。

首版采用“同表扩展 + 专用 API + 复用现有检索链”的方案：

1. 在 `rag_documents` 增加 `jsonb_payload` 和 `external_id`。
2. 结构化 JSON 记录的类型固定为 `json-record`。
3. 用 `(collection_id, document_type, external_id)` 作为调用者可重放的记录身份。
4. 不使用普通文档的全局 `content_hash` 去重；不同 JSON 可以拥有相同描述。
5. 每条 JSON 记录只生成一个 record-level chunk，避免同一 payload 因多 chunk 重复返回。
6. 专用 search 先复用混合检索排序，再按 ranked document IDs 批量加载 JSONB，
   保持排序并避免 N+1。
7. payload-only 更新记录版本，但不改变 `content_hash`、不使 embedding 过期、
   不触发重新嵌入。
8. 修复普通分块器“完整短文档小于 `minChunkSize` 就被静默丢弃”的全局缺陷。
9. 为未来 XML 增加独立 `xmlPayload` 留出清晰扩展点，但本次不创建泛化 payload 框架。

本文选择的方案保持现有普通文档、PDF、`.json` 文本文件上传和 chat 行为兼容。

## 2. 目标与非目标

### 2.1 本次目标

- 支持单条和批量 JSON 结构化记录的幂等导入 / 更新。
- 调用者必须同时提交 `jsonbPayload` 与 `retrievalText`。
- 只对 `retrievalText` 建全文索引和 embedding。
- 检索时返回 ranking 信息、当前 `retrievalText` 和对应 `jsonbPayload`。
- 强制 collection 作用域并复用 API Key Collection ACL。
- payload-only 更新不重新嵌入。
- 内容相同的不同结构化记录可以同时存在。
- JSONB 可参与 collection clone / export / import 和版本审计。
- 修复有效短文本被分块器丢弃的问题。
- 提供真实 PostgreSQL / JSONB 集成测试和可重复的一键验证脚本。
- 同步正式中英文文档和文档索引。

### 2.2 明确非目标

- 不从 JSON 自动生成自然语言描述。
- 不验证 JSON 与自然语言描述是否一致、完整或事实正确。
- 不对 `jsonbPayload` 本身生成 embedding。
- 不把 JSON 自动拼进 chat prompt。
- 不提供 JSONPath、JSONB containment 或字段级数据库查询 API。
- 首版不建 JSONB GIN 索引；当前只按文档身份读取和返回 payload。
- 不保留原始 JSON 的空白、对象 key 顺序、重复 key 或字节级表示。
- 不引入 `payloadHash` 或 `jsonbPayloadHash`。
- 不把 JSONB 复制到 `rag_documents.metadata` 或 `rag_embeddings.metadata`。
- 不改变普通文档现有的 content-hash 去重策略。
- 不把现有 `.json` / `.xml` 文本文件上传端点改造成结构化记录导入。
- 首版不实现 WebUI 的 JSON 编辑器或结构化记录检索页。
- 本次不实现 XML 导入。

## 3. 当前代码事实与问题评价

### 3.1 普通文档与去重

当前 `rag_documents.content TEXT NOT NULL` 是 embedding 的文本来源，
`content_hash` 是该文本的 SHA-256：

- `RagDocumentController#createDocument` 按 `content_hash` 全局查询重复内容；
- `BatchDocumentService` 的单条创建也按 `content_hash` 全局复用已有文档；
- `PdfToRagService` 把 PDF 转换后的 Markdown 按 `content_hash` 去重；
- 文件上传支持 `.json` / `.xml`，但只是按 UTF-8 读取原始文本并走普通文档路径。

对普通文档而言，这个行为暂时保留。对结构化记录而言不能复用，因为两个不同的 JSON
业务对象完全可能拥有相同的自然语言描述；若按 `retrievalText` 去重，会错误地让它们
指向同一 payload。

### 3.2 短文本会被静默丢弃

`HierarchicalTextChunker#filterAndSortChunks` 当前统一执行：

```java
.filter(c -> c.text().length() >= minChunkSize)
```

默认 `rag.chunk.min-chunk-size=100`。因此一份非空、完整且有效但不足 100 字符的普通
文档会产生零 chunk，随后 `DocumentEmbedService` 返回
`Document content too short, no chunks created`。

**评价：该行为作为全局默认不合理。**

`minChunkSize` 可以作为长文档切分后抑制无意义碎片的质量目标，但不应成为文档准入
门槛。完整短文档通常包含高密度事实、代码、产品名、ID 说明或结构化记录摘要；
静默删除会造成不可检索且难以发现的数据损失。

冻结后的新不变量：

- `null`、空字符串和纯空白输入仍返回零 chunk；
- 任何非空完整文档至少保留一个 chunk；
- 长文产生的短尾优先与相邻 chunk 合并或重平衡；
- 无法在不严重破坏边界时合并的短片段也保留，绝不静默丢文本；
- `minChunkSize` 是 best-effort 质量目标，不是硬过滤器；
- JSON / 未来 XML 结构化记录绕过层级分块，固定为一个 record-level chunk。

### 3.3 检索结果目前不包含业务 payload

`HybridRetrieverService` 从 `rag_embeddings` 返回 `RetrievalResult`，
其中有 document ID、chunk text、score 和 metadata，没有 `jsonbPayload`。

专用 JSON 检索不能在向量表复制 payload，否则会：

- 每个 chunk 重复大块 JSON；
- payload 更新需要同步多行；
- 增加泄露到 chat prompt 或 embedding metadata 的风险。

因此必须先得到 ranked document IDs，再一次性从 `rag_documents` 批量加载 payload。

### 3.4 PDF 的可借鉴边界

当前 PDF 流程分为两层：

1. `PdfImportService` 把原始 PDF、转换 Markdown 和图片保存到 `fs_files`；
2. `PdfToRagService` 把转换后的 Markdown 写入 `rag_documents.content`；
3. 只有 Markdown 进入分块和 embedding，原 PDF 二进制不参与。

JSON 的对应关系是：

| PDF 路径 | JSON 结构化记录 |
|----------|-----------------|
| 原始 PDF / `fs_files` | `jsonbPayload` / `jsonb_payload` |
| 转换后的 Markdown | 调用者提供的 `retrievalText` / `content` |
| Markdown embedding | `retrievalText` embedding |
| PDF 与 Markdown 转换由服务负责 | JSON 与描述的对应关系由调用者负责 |

两者共同原则是“保存源数据，索引派生的自然语言表示”。不同点是 JSON 不需要服务端
转换器，也不需要独立文件树。

### 3.5 版本表现状阻塞 payload-only 版本

`rag_document_versions` 当前有：

```sql
UNIQUE (document_id, content_hash)
```

`DocumentVersionService#forceRecordVersion` 声称忽略 hash 去重，但数据库约束仍会阻止
相同 `content_hash` 的第二个版本。payload-only 更新恰好保持 content hash 不变，
所以现状无法正确记录。

版本约束必须改为 `(document_id, version_number)` 唯一；普通 `recordVersion` 是否按
content hash 跳过，继续由应用逻辑决定。

### 3.6 测试现状

- documents 模块单测明确断言短文本被过滤，实施时必须改写这些旧期望。
- core 中名为 integration 的主要 controller 测试使用 `@WebMvcTest` 和 mocked DB。
- 项目已声明 Testcontainers PostgreSQL 依赖，但缺少覆盖 Flyway + PostgreSQL JSONB +
  Hibernate `JsonNode` 映射的真实数据库测试。
- Docker Compose 已使用 `pgvector/pgvector:pg16`，可作为测试容器默认镜像。

## 4. 冻结的领域模型

### 4.1 字段职责

| API 字段 | 实体字段 | 数据库列 | 职责 |
|----------|----------|----------|------|
| `jsonbPayload` | `RagDocument.jsonbPayload` | `jsonb_payload JSONB` | 业务 JSON 数据 |
| `retrievalText` | `RagDocument.content` | `content TEXT` | 唯一被检索 / 嵌入的自然语言 |
| `externalId` | `RagDocument.externalId` | `external_id VARCHAR(255)` | 调用者稳定身份 |
| 无单独字段 | `RagDocument.contentHash` | `content_hash VARCHAR(64)` | `retrievalText` SHA-256 |
| 固定值 | `RagDocument.documentType` | `document_type` | `json-record` |

不新增 `payloadHash`。JSONB 不是字节保真存储，hash 原始输入或重序列化结果都会混淆
“业务 JSON 语义”和“原始字节身份”。本功能不需要 payload hash 才能实现幂等。

### 4.2 记录身份

调用者可重复提交同一条记录，身份键固定为：

```text
collectionId + documentType("json-record") + externalId
```

规则：

- `collectionId` 必填且必须通过 ACL；
- `externalId` 必填、trim 后非空、最大 255 字符、大小写敏感；
- `externalId` 创建后不提供 rename 语义；
- 更换 collection 或 external ID 等价于创建新记录并删除旧记录；
- 同 collection 内同 external ID 的 JSON 记录只能有一条；
- 不同 collection 可使用相同 external ID；
- 未来 `xml-record` 可与 `json-record` 使用相同 external ID。

### 4.3 JSONB 语义

`jsonbPayload` 接受任意非 null JSON value：object、array、string、number 或 boolean；
JSON `null` 拒绝。首版不强制必须是 object，避免不必要限制调用者的数据模型。

必须在 API 文档明确：

- JSONB 不保留对象 key 顺序和无意义空白；
- 重复 key 在解析 / 入库时会折叠；
- 数字可能按 JSONB 语义规范化；
- 返回值保证 JSON 语义，不保证原始字节完全一致；
- 如未来需要签名验证或字节保真，应另增原始 `TEXT` / `BYTEA` 字段，不改变
  `jsonbPayload` 的语义。

### 4.4 大小和批量默认值

新增 `rag.structured-records.*` 配置，默认值：

| 配置 | 默认 | 原因 |
|------|------|------|
| `max-jsonb-payload-bytes` | `1048576`（1 MiB） | 控制 DB、序列化和响应放大 |
| `max-retrieval-text-chars` | `10000` | 单 record-level embedding 的保守上限 |
| `max-batch-size` | `20` | 限制单请求 DB 和远程 embedding 压力 |
| `max-batch-payload-bytes` | `10485760`（10 MiB） | 避免 20 个最大 payload 形成 20 MiB+ 请求 |
| `max-search-results` | `20` | payload 搜索响应可能远大于普通文本结果 |

边界：

- payload 字节数用 Jackson 将 `JsonNode` 序列化为 UTF-8 后计算；
- batch 同时校验 item 数和所有 payload 的序列化总字节数；
- `retrievalText` 只 trim 用于 blank 判断，实际存储和 hash 保留调用者提交文本；
- 默认值可通过配置调整，不进入 schema；
- 超限统一返回 400，不截断 payload 或 retrieval text。

## 5. 数据库迁移

实施时创建 rebase 后的 `V{next}__add_jsonb_structured_records.sql`，不得预先假定编号。

### 5.1 `rag_documents`

```sql
ALTER TABLE rag_documents
    ADD COLUMN IF NOT EXISTS external_id VARCHAR(255),
    ADD COLUMN IF NOT EXISTS jsonb_payload JSONB;

CREATE UNIQUE INDEX IF NOT EXISTS uk_rag_doc_structured_identity
    ON rag_documents (collection_id, document_type, external_id)
    WHERE collection_id IS NOT NULL AND external_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_rag_doc_collection_type
    ON rag_documents (collection_id, document_type);
```

不增加 `jsonb_payload` GIN 索引。首版没有 JSONPath / containment 查询，GIN 只会增加
写入和存储成本。

不增加 `document_type='json-record'` 时 collection / external / payload 必须非空的
数据库 CHECK，原因是现有 collection soft-delete 会把文档 `collection_id` 清空。
API 和 service 对新建 / upsert 强制这些字段；schema 保持对现有 collection 生命周期
兼容。

### 5.2 `rag_document_versions`

同一迁移或紧随其后的 `V{next}` 迁移执行：

```sql
ALTER TABLE rag_document_versions
    ADD COLUMN IF NOT EXISTS jsonb_payload_snapshot JSONB;

ALTER TABLE rag_document_versions
    DROP CONSTRAINT IF EXISTS uk_doc_version_hash;

ALTER TABLE rag_document_versions
    ADD CONSTRAINT uk_doc_version_number
    UNIQUE (document_id, version_number);

ALTER TABLE rag_document_versions
    ADD CONSTRAINT fk_doc_version_document
    FOREIGN KEY (document_id)
    REFERENCES rag_documents(id)
    ON DELETE CASCADE;
```

迁移前需检查是否已有重复 `(document_id, version_number)`；正常代码按递增编号写入，
理论上没有重复。若发现异常数据，迁移必须 fail fast 并输出文档 ID，不可静默删历史。

当前 V9 没有 document foreign key，现有 document delete 也没有调用
`DocumentVersionService#deleteVersions`，所以版本 orphan 是已确认的 schema 缺口，
不是实施时再判断的事项。增加 FK 前必须先检查：

```sql
SELECT v.document_id, COUNT(*)
FROM rag_document_versions v
LEFT JOIN rag_documents d ON d.id = v.document_id
WHERE d.id IS NULL
GROUP BY v.document_id;
```

若有结果，迁移 fail fast 并要求操作者先导出 / 处置 orphan，不自动删除审计历史。
若 rebase 后已有等价 FK，则通过 `pg_constraint` 条件检查跳过重复创建。新 FK 固定
`ON DELETE CASCADE`，使 document、embedding state 和 versions 具有一致删除语义。

### 5.3 迁移与并行 Embedding Profile 的顺序

实施顺序：

1. 先完成并合并 Embedding Profile / 固定维度迁移；
2. `git pull --rebase` 或等价方式更新迁移目录；
3. 选择下一个可用 Flyway 版本；
4. JSONB 迁移只做上述字段、约束和普通 B-Tree 索引；
5. 不修改 V25/V26 文件，不把 JSONB DDL 塞进并行迁移。

## 6. Java 实体与依赖

### 6.1 API 模块

`spring-ai-rag-api` 的 JSON DTO 使用 Jackson `JsonNode`，因此在
`spring-ai-rag-api/pom.xml` 直接声明：

```xml
<dependency>
    <groupId>com.fasterxml.jackson.core</groupId>
    <artifactId>jackson-databind</artifactId>
</dependency>
```

不能依赖 Spring AI 偶然传递 Jackson；API 模块公开类型应直接声明其编译依赖。

### 6.2 `RagDocument`

增加：

```java
@Column(name = "external_id", length = 255)
private String externalId;

@JdbcTypeCode(SqlTypes.JSON)
@Column(name = "jsonb_payload", columnDefinition = "jsonb")
private JsonNode jsonbPayload;
```

`metadata` 继续使用 `Map<String,Object>`，二者不能互相替代。

### 6.3 `RagDocumentVersion`

增加：

```java
@JdbcTypeCode(SqlTypes.JSON)
@Column(name = "jsonb_payload_snapshot", columnDefinition = "jsonb")
private JsonNode jsonbPayloadSnapshot;
```

`fromDocument` 保存 payload 深拷贝。普通文档该字段为 null。

### 6.4 文档类型常量

在 core 内新增单一常量来源，例如：

```java
public static final String JSON_RECORD = "json-record";
```

控制器、service、repository 和测试不得散落重复字符串。未来 XML 增加
`XML_RECORD = "xml-record"`。

## 7. 专用 HTTP API

新增 `RagJsonRecordController`：

```text
@ApiVersion("v1")
@RequestMapping("/rag/json-records")
```

外部完整路径如下。

### 7.1 单条幂等导入

```http
POST /api/v1/rag/json-records/upsert
Content-Type: application/json
```

请求：

```json
{
  "collectionId": 12,
  "externalId": "product:sku-10001",
  "title": "SKU-10001 无线键盘",
  "retrievalText": "一款支持蓝牙与 2.4G 双模连接的紧凑型无线键盘。",
  "jsonbPayload": {
    "sku": "10001",
    "price": 299.0,
    "stock": 18,
    "tags": ["keyboard", "wireless"]
  },
  "source": "catalog-v3",
  "metadata": {
    "tenant": "demo"
  },
  "embed": true
}
```

校验：

- `collectionId`：必填、正数；
- `externalId`：必填，最大 255；
- `title`：必填，最大 255，与 DB 列一致；
- `retrievalText`：必填，最大值来自配置；
- `jsonbPayload`：必填、不得是 JSON null、大小受配置限制；
- `source`：可选，最大 255；
- `metadata`：可选；
- `embed`：可选，默认 `true`。

响应：

```json
{
  "documentId": 301,
  "collectionId": 12,
  "externalId": "product:sku-10001",
  "action": "CREATED",
  "contentChanged": true,
  "payloadChanged": true,
  "versionNumber": 1,
  "embeddingStatus": "COMPLETED",
  "embeddingProfileKey": "default-1024"
}
```

`action` 取值：

- `CREATED`：新记录；
- `UPDATED`：至少一个持久字段变化；
- `UNCHANGED`：完整重放，无 DB 更新、无新版本；若缺少 fresh embedding 且
  `embed=true`，仍允许修复 embedding。

`versionNumber` 始终返回当前最新版本号；`UNCHANGED` 只返回已有值，不创建新版本。

`embeddingStatus` 取值：

- `COMPLETED`：本次生成并提交 embedding；
- `CACHED`：`embed=true` 且已有 fresh embedding；
- `NOT_REQUESTED`：`embed=false`；
- `FAILED`：导入已提交，但 embedding 生成或提交失败。

若记录保存成功但 embedding 失败，HTTP 仍返回 200，`embeddingStatus=FAILED` 并带
安全的错误摘要。原因是导入事实已经提交，不能用 5xx 暗示整个操作可无条件重放；
调用者可修复 embedding 服务后重试同一 upsert。

### 7.2 批量幂等导入

```http
POST /api/v1/rag/json-records/batch-upsert
```

请求为 `items` 数组；每个 item 与单条请求相同。批量规则：

- 最大 item 数和总 payload 字节数来自配置；
- 每条记录是独立事务和独立失败单元；
- 一个 item 失败不回滚其他 item；
- 不在远程 embedding 调用期间持有整批数据库事务；
- 返回结果保持输入顺序；
- 响应 summary 包含 created / updated / unchanged /
  persistenceFailed / embeddingFailed；
- batch HTTP 200 表示批处理已执行，item 级状态表达部分失败。

### 7.3 专用检索

```http
POST /api/v1/rag/json-records/search
```

请求：

```json
{
  "query": "库存中的双模无线键盘",
  "collectionIds": [12],
  "config": {
    "maxResults": 10,
    "useHybridSearch": true,
    "useRerank": true,
    "vectorWeight": 0.55,
    "fulltextWeight": 0.45
  }
}
```

规则：

- `query` 必填，最大 10000 字符，与普通 search 一致；
- `collectionIds` 必填、非空，最多 50 个；
- API Key ACL 对请求 collection IDs 取有效范围；
- `maxResults` 不能超过 `rag.structured-records.max-search-results`；
- 只召回 `document_type='json-record'` 且 enabled 的文档；
- rerank 行为复用现有 `ReRankingService`；
- 相同 document 如出现多条 chunk 结果，防御性地只保留最高分一条；
- payload 批量加载，结果顺序必须保持 ranking 顺序。

响应：

```json
{
  "query": "库存中的双模无线键盘",
  "results": [
    {
      "documentId": 301,
      "collectionId": 12,
      "externalId": "product:sku-10001",
      "title": "SKU-10001 无线键盘",
      "source": "catalog-v3",
      "retrievalText": "一款支持蓝牙与 2.4G 双模连接的紧凑型无线键盘。",
      "jsonbPayload": {
        "sku": "10001",
        "price": 299.0,
        "stock": 18,
        "tags": ["keyboard", "wireless"]
      },
      "score": 0.91,
      "vectorScore": 0.89,
      "fulltextScore": 0.93,
      "metadata": {
        "tenant": "demo"
      }
    }
  ]
}
```

### 7.4 单条详情

```http
GET /api/v1/rag/json-records/{documentId}
```

用于按已知 document ID 读取当前 `retrievalText` 和 `jsonbPayload`。必须：

- 验证文档存在；
- 验证 `document_type='json-record'`；
- 调用 `ApiKeyCollectionAccess.requireDocumentAccess`；
- 不在普通列表端点返回 payload；
- 删除继续复用 `DELETE /api/v1/rag/documents/{id}`，不新增重复删除 API。

### 7.5 DTO

在 `spring-ai-rag-api` 增加独立 DTO，不给普通 `DocumentRequest` 塞入条件字段：

- `JsonRecordUpsertRequest`
- `JsonRecordUpsertResponse`
- `JsonRecordBatchUpsertRequest`
- `JsonRecordBatchUpsertResponse`
- `JsonRecordSearchRequest`
- `JsonRecordSearchResponse`
- `JsonRecordSearchResult`
- `JsonRecordDetailResponse`

这样可以用 Bean Validation 和 OpenAPI 明确表达 JSON 记录不变量，避免出现
“documentType=json-record 时某些普通字段突然必填”的隐式契约。

## 8. Upsert 生命周期

### 8.1 两阶段流程

单条 upsert 采用：

```text
阶段 A：短事务
  ACL / validation
  -> 结构化身份加锁
  -> find by identity
  -> compare fields
  -> create/update RagDocument
  -> record version if changed
  -> commit

阶段 B：事务外模型调用
  if embed && (created || retrievalText changed || no fresh embedding)
  -> DocumentEmbedService
  -> profile-aware atomic embedding replace
  -> return combined response
```

不能把远程 embedding 调用包在阶段 A 的数据库事务中。

### 8.2 并发控制

唯一索引是最终一致性防线，但单纯“先查再插”仍会在并发首次写入时竞争。
`JsonRecordService` 在阶段 A 开头获取 PostgreSQL transaction-scoped advisory lock：

```sql
SELECT pg_advisory_xact_lock(
    hashtextextended(? /* collectionId + type + externalId */, 0)
);
```

要求：

- key 使用绑定参数，不能拼 SQL；
- lock 只持有到短事务提交；
- 同一结构化身份的 upsert 串行化；
- hash 碰撞最多造成无关记录短暂串行，不造成错误合并；
- DB 唯一索引仍保留，防止绕过 service 的写入；
- 已有记录继续受 `RagDocument.@Version` 乐观锁保护。

事务边界用独立 persistence service 或 `TransactionTemplate`，避免同类方法调用导致
`@Transactional` 失效。

### 8.3 字段比较与状态

比较规则：

- `retrievalText` 按 Java String 精确比较；
- `jsonbPayload` 用 `JsonNode` 结构比较，不计算 hash；
- title / source / metadata 分别比较；
- incoming payload 先通过大小校验，再参与比较；
- no-op 不执行 save，不增加 JPA version，不写 document version。

创建：

- `documentType=json-record`；
- `content=retrievalText`；
- `contentHash=SHA-256(retrievalText UTF-8)`；
- `size=retrievalText UTF-8 bytes`；
- `processingStatus=PENDING`，若不要求 embed 仍保持待嵌入状态；
- 保存 `externalId`、`jsonbPayload`、collection 和 metadata。

更新：

| 变化 | content hash | embedding freshness | 是否调用 embedding |
|------|--------------|---------------------|--------------------|
| retrievalText | 重算 | 过期 | `embed=true` 时是 |
| jsonbPayload only | 不变 | 不变 | 否 |
| title/source/metadata only | 不变 | 不变 | 否 |
| 完全无变化 | 不变 | 不变 | 否 |

### 8.4 与运行中 embedding 的竞争

Embedding Profile WIP 使用 document `version + content_hash` 防止旧请求覆盖新内容。
payload-only 更新会增加 JPA version，但 content hash 不变，因此实施时要补一个
**仅提交重试**：

1. embedding 向量已生成，原子 replace 因 document version 改变而失败；
2. 重新读取当前 document；
3. 若 enabled、content hash、目标 profile 和 chunker version 均未变化，
   用同一批已生成向量和新的 document version 再尝试一次 DB replace；
4. 若 content hash 已变，必须丢弃旧向量，不能重试提交；
5. 最多重试一次，避免活锁。

这不会因 payload 更新重新调用 embedding 模型，只避免已经生成的正确向量被无关字段
更新浪费。对应行为必须进入 `DocumentEmbedService` / `EmbeddingPersistenceService`
测试。

## 9. Embedding 与分块

### 9.1 JSON record-level chunk

`DocumentEmbedService` 在读取 `RagDocument` 后按类型选择：

```text
json-record -> [TextChunk(retrievalText, 0, retrievalText.length)]
其他类型    -> HierarchicalTextChunker.split(content)
```

首版不必引入通用 strategy registry；一个集中私有方法或小型 selector 足够。
未来增加 XML 时在同一处增加 `xml-record`，仍不需要泛化 payload 存储。

结构化记录只有一个 chunk 的理由：

- 一条 payload 对应一个检索实体；
- 避免同 payload 在结果中按多个 chunk 重复；
- payload 不被切分；
- caller 已负责把 JSON 派生为适合检索的描述；
- 大文本由 `max-retrieval-text-chars` 在入口限制。

### 9.2 修复普通短文档

`HierarchicalTextChunker` 的后处理改为：

1. 丢弃 null / blank chunk；
2. 按 `startPos` 排序；
3. 如果只有一个非空 chunk，直接保留，不看 `minChunkSize`；
4. 多 chunk 时，将短尾 / 短段优先与前一个或后一个相邻 chunk 合并；
5. 必要时在相邻 chunk 之间重平衡边界；
6. 若合并会使边界严重失真或无法保持内容顺序，则保留短 chunk；
7. 最终不得因长度过滤删除非空内容。

`maxChunkSize` 和 `minChunkSize` 都是分块质量目标。允许为了保留完整短尾让合并后的
chunk 小幅超过 max；实现应优先重平衡，超限仅作为最后选择。

### 9.3 chunker fingerprint

普通分块算法行为变化后，fingerprint 从 `hierarchical-v1` 升为
`hierarchical-v2`，确保缓存不会把旧 chunk 边界误判为新结果。

结构化记录使用独立 fingerprint，例如：

```text
json-record-v1:single
```

实现不得让 JSON record 因普通 `minChunkSize` 配置变化而无意义地全量失效。
`maxRetrievalTextChars` 只是 API 准入限额，不改变已接收文本的 chunk 输出，因此也不得
进入 fingerprint。只有实际改变 chunk 文本或边界的算法版本才升级该值。

### 9.4 失败信息

`DocumentEmbedService` 只应对 blank content 报空内容错误。非空但短的普通文档和 JSON
记录都必须至少有一个 chunk；`Document content too short` 这一错误分支应删除或改成
仅表示真正的 chunker invariant violation。

## 10. 检索与 payload enrichment

### 10.1 候选限制

`RagDocumentRepository` 增加按 collection + type + enabled 查询 ID 的方法。
专用 search 流程：

1. ACL 解析 effective collection IDs；
2. 查询这些 collection 下 enabled 的 `json-record` IDs；
3. 无候选立即返回空结果，禁止退化成全库搜索；
4. 把 IDs 传给 `HybridRetrieverService`；
5. 可选 rerank；
6. 按 document ID 去重；
7. `findAllById` 一次批量加载文档；
8. 建 map 后按 retrieval ranking 顺序输出。

首版沿用现有 collection resolver 的 candidate-ID 模式，不在本次改造所有 vector /
full-text provider 的 SQL scope 接口。若单 collection 达到数十万记录并出现巨大
`IN (...)` 性能瓶颈，再单独把 collection/type scope 下推到检索 SQL。

### 10.2 一致性校验

payload enrichment 时必须再次验证：

- 文档仍存在且 enabled；
- 文档仍为 `json-record`；
- 文档 collection 仍在 effective collection IDs；
- `content_hash` 与参与检索的 embedding state 保持 freshness；
- 未通过者从结果移除，不用空 payload 填充。

### 10.3 Chat 安全边界

现有 chat / Advisor 仍只消费 `RetrievalResult.chunkText`。不得：

- 把 `jsonbPayload` 写入 embedding metadata；
- 在 `HybridRetrieverService` 的普通结果中偷偷增加 payload；
- 自动将 payload 注入 prompt；
- 在日志中输出完整 payload。

调用者如需让 LLM 使用 JSON，应先调用专用 search，再由调用者决定如何裁剪、验证和
传入模型。这符合“payload 与描述一致性由调用者负责”的边界。

## 11. ACL、安全与隐私

### 11.1 写入

- 单条和 batch item 都调用 `ApiKeyCollectionAccess.resolveWritableCollectionId`；
- JSON 记录不允许无 collection 写入；
- restricted key 只能写 allowed collection；
- 不通过 ACL 时返回 403，而不是 404 / 500。

### 11.2 读取

- search 使用 `resolveCollectionIds`，且请求必须显式提供非空 collection scope；
- detail 使用 `requireDocumentAccess`；
- collection export / clone / import 延续各自现有 ACL；
- version detail 先验证 parent document 权限。

### 11.3 日志与审计

日志只记录：

- collection ID；
- external ID 的安全摘要或长度；
- document ID；
- action；
- retrieval text / payload 字节数；
- content hash 前缀；
- embedding status。

不记录完整 `retrievalText`、`jsonbPayload`、metadata 或 API Key。

审计记录 action、collection、document、external ID 和 changed-field names，
不保存 payload 内容。

## 12. 版本历史

### 12.1 记录规则

结构化记录：

- CREATE：强制记录初始 `content_snapshot + jsonb_payload_snapshot`；
- UPDATE：任何持久字段变化记录一个新版本；
- payload-only UPDATE：content hash 相同，仍强制记录新版本；
- UNCHANGED：不记录；
- EMBED：不因重复 embedding 记录 payload 版本。

`change_type` 继续使用短值 `CREATE` / `UPDATE`，具体 changed fields 放入
`change_description`，避免扩大现有 20 字符列和枚举契约。

### 12.2 版本 API

`DocumentVersionResponse` 增加可空 `jsonbPayloadSnapshot`。

同时修复当前列表 / 详情共用完整快照的问题：

- `GET /documents/{id}/versions` 列表不返回 `contentSnapshot` 和
  `jsonbPayloadSnapshot`；
- `GET /documents/{id}/versions/{versionNumber}` 经 ACL 后返回两种快照；
- 普通文档 payload snapshot 为 null。

### 12.3 回滚语义

当前版本服务只查询历史，没有实现内容回滚 endpoint。本次不新增回滚 API。
版本快照用于审计和未来回滚；若以后实现回滚，必须同时恢复
`content + contentHash + jsonbPayload`，并按 content 是否变化决定 re-embed。

## 13. Collection clone / export / import

### 13.1 Clone

`RagCollectionService#cloneDocument` 必须复制：

- content、contentHash；
- externalId；
- jsonbPayload；
- documentType；
- originalFilename、source、metadata、size、enabled。

新 collection ID 使结构化唯一键自然不同。embedding 不复制，状态保持 PENDING。
clone 不复制源文档的历史版本；每条克隆后的 `json-record` 在目标 collection 创建一个
新的 `CREATE` 初始快照，描述中记录 source document / collection ID。

### 13.2 Export

`CollectionExportResponse.ExportedDocumentSummary` 以可空字段增加：

- `externalId`
- `jsonbPayload`

这是向后兼容的响应加字段。JSONB payload 只在已经通过 collection ACL 的 export 中
出现。

### 13.3 Import

collection import 读取新增字段：

- 普通旧 export 没有字段时继续兼容；
- `documentType=json-record` 时必须有 externalId 和非 null jsonbPayload；
- content 视为 retrievalText；
- 导入后计算 contentHash；
- 同一新 collection 内重复 external ID 的 import item 返回明确失败；
- JSON record 通过共享的 structured persistence 路径以 `embed=false` 导入，并记录
  `CREATE` 初始版本；
- 不自动嵌入，延续 collection import 的 PENDING 行为。

现有 Map 强转式 import 风险较高。本次冻结为新增 typed
`CollectionImportRequest` / `ImportedDocument` DTO，并保持旧 export JSON 字段兼容；
不继续维护 controller 内的裸 Map 强转。非法 documents 结构返回 400，禁止
`ClassCastException` 变 500。

### 13.4 Collection soft-delete 边界

现有 soft-delete 会清空所有文档的 `collection_id`，restore 不自动重连。本次保持该
既有语义，避免扩大 collection 生命周期改造。

结果：

- 被 unlink 的 JSON record 只能由 unrestricted / ADMIN key 通过 document ID 管理；
  restricted key 因 document collection 为 null 会被 `requireDocumentAccess` 拒绝；
- 专用 JSON search / upsert 不处理无 collection 的 orphan record；
- 重新加入 collection 后恢复专用检索资格；
- 因此 schema 不对 JSON record 增加 collection NOT NULL CHECK。

这是现有 collection 模型的限制，应在正式文档中明确；后续可单独评估“soft delete
保留关联”方案。

## 14. 未来 XML 扩展

未来 XML 不塞进 `jsonbPayload`，也不创建泛化的 `payload` 字段。推荐独立增加：

```text
API: xmlPayload
Entity: xmlPayload
DB: xml_payload
documentType: xml-record
```

可直接复用：

- caller-provided `retrievalText`；
- collection + documentType + externalId 身份；
- ACL；
- record-level 单 chunk；
- content hash / embedding freshness；
- ranked IDs 后批量加载 payload；
- payload-only 不重嵌入；
- version snapshot 思路。

XML 存储类型在 XML 功能规划时独立决定。推荐默认先用 `TEXT` 以保留调用者文本表示，
如确有 PostgreSQL XML 查询需求再评估 `XML` 类型和索引；该选择不阻断本次 JSONB。

禁止现在为了未知格式建立：

- `payload_type + payload_blob`；
- 通用 payload serializer registry；
- JSON/XML 共用字符串列；
- payload hash 抽象。

格式专用字段更明确，也允许 JSONB、XML、二进制等按各自存储语义演进。

## 15. WebUI 范围

首期只实现 backend API、OpenAPI 和文档，不增加 WebUI 页面。

原因：

- JSON 编辑器、schema validation、diff 和大 payload 展示需要独立 UX 设计；
- API 可先服务程序化调用者；
- 现有普通文档页不应误把 JSONB 展开成 metadata；
- 减少首版跨前后端 blast radius。

现有 WebUI 若读取普通 document DTO，新增可空字段必须确保不会破坏反序列化。
后续 UI 可新增：

- JSON record import / batch import；
- retrievalText 与 payload 双栏编辑；
- payload-only version diff；
- structured search result inspector。

## 16. 失败、事务和恢复

### 16.1 持久化失败

- 阶段 A 失败：事务回滚，不调用 embedding；
- 唯一约束冲突：返回 409 `STRUCTURED_RECORD_CONFLICT`，不映射成 500；
- 非法 JSON 由 Spring/Jackson 返回 400；
- payload 超限返回 400；
- DB JSONB 映射失败返回统一 problem detail，不泄露 SQL。

`GlobalExceptionHandler` 增加明确的 409 映射；不能让
`DataIntegrityViolationException` 一律变成 500。

### 16.2 Embedding 失败

- 文档和版本已存在；
- 没有历史 COMPLETED state 时，当前 profile state 和 document 聚合状态标为 FAILED；
- 已有历史 COMPLETED state 时，保留旧 state 和旧向量，不用失败尝试覆盖可诊断的
  最后成功数据；JSON upsert 响应仍返回 `embeddingStatus=FAILED`；
- content 已变化时，旧 state 的 hash 与当前 document hash 不同，检索 freshness
  条件会排除旧向量，因此“保留”不等于继续服务 stale 内容；
- payload-only 更新不改变 content hash，原有 COMPLETED state 仍然 fresh；
- 新建或 content changed 且 embedding 失败时不得返回 stale 内容；
- 调用者重放同一 upsert 时，如缺少 fresh embedding，即使字段 UNCHANGED 也应重试
  embedding，并把 action 保持 `UNCHANGED`。

### 16.3 删除

复用现有 document delete 路径，并通过本次 migration 补齐
`rag_document_versions.document_id -> rag_documents.id ON DELETE CASCADE`。
真实 PostgreSQL 集成测试必须确认一次 document delete 会同时清理：

- 所有 profile 的 embedding rows；
- embedding state；
- document versions；
- JSON payload 只随 document 主行删除，不残留副本。

### 16.4 应用回滚

schema 变更是 additive，旧应用可以忽略 `external_id` / `jsonb_payload`。
版本唯一约束从 hash 改为 version number 后也兼容旧 `recordVersion` 应用逻辑。

如必须人工回滚：

1. 停止 JSON record 写入；
2. 导出 JSON records；
3. 确认没有相同 document + content hash 的 payload-only 多版本；
4. 才能尝试恢复旧 hash 唯一约束；
5. 列删除必须另发 forward-only Flyway，不修改已执行迁移。

## 17. 文件级实施顺序

### Phase A：schema 和依赖

1. rebase 并确认最新 Flyway 编号。
2. 增加 JSONB / external ID / version snapshot migration。
3. API 模块直接声明 Jackson databind。
4. 更新 `RagDocument`、`RagDocumentVersion` 和 repository。
5. 先写真实 PostgreSQL migration / mapping 测试。

### Phase B：DTO、配置和持久化

1. 增加 `RagStructuredRecordProperties` 并挂到 `RagProperties`。
2. 增加全部 JSON record DTO 和 Bean Validation。
3. 增加 document type 常量。
4. 实现 payload size validator。
5. 实现 identity advisory lock、upsert 比较和版本记录。
6. 增加 409 conflict exception / handler。

### Phase C：分块和 embedding

1. 修改 `HierarchicalTextChunker`，去除非空短文本丢弃。
2. 改写短文本 / 短尾测试。
3. `DocumentEmbedService` 增加 JSON single-chunk 路径。
4. chunker fingerprint 升级。
5. 增加 version-only conflict 的 embedding commit 单次重试。
6. 与 Embedding Profile state freshness 测试交叉验证。

### Phase D：API 和检索

1. 增加 `RagJsonRecordService` / persistence boundary。
2. 增加 `RagJsonRecordController`。
3. 增加 JSON candidate repository query。
4. 复用 hybrid + rerank。
5. 实现 ranked batch payload enrichment。
6. 增加 ACL、no-scope、empty-candidate 和 stale-result 测试。

### Phase E：版本与 collection 生命周期

1. 修复 version constraint 和 DTO summary/detail 快照边界。
2. clone 复制 JSON 字段和 content hash，并创建目标记录初始版本。
3. export/import 改为 typed contract，复制 JSON 字段并创建导入初始版本。
4. 加版本、clone、export/import 集成测试。

### Phase F：文档和验证

1. 更新中英文 API / architecture / configuration / project context。
2. 更新 testing / developer reference。
3. 加一键验证脚本和 live E2E 脚本。
4. 跑完整门禁。
5. 只在全部验证通过后 commit / push。

## 18. 测试矩阵

### 18.1 Documents 单元测试

- null / empty / whitespace 仍返回空；
- 10 字符完整文档返回一个 chunk；
- 短标题段不丢失；
- 长文短尾合并或保留，总文本不丢失；
- 表格短 chunk 不被静默删除；
- chunk 按位置排序；
- overlap / max size 原有行为不回归；
- 旧 `split_shortPlainText -> empty` 断言改为保留。

### 18.2 DTO / validation

- required fields；
- title / external ID / retrievalText 长度；
- JSON null 拒绝；
- object / array / scalar JSON 接受；
- 单 payload / batch total 超限；
- batch size；
- search collection scope 和 max result。

### 18.3 Upsert service

- create；
- exact replay -> UNCHANGED；
- same retrieval text + different external IDs -> 两条文档；
- payload-only -> content hash 不变、无 embedding 调用、有新版本；
- retrieval-only -> content hash 变化、触发 embedding；
- metadata-only -> 不触发 embedding；
- unchanged but embedding missing -> 重试 embedding；
- embedding failure 保留记录；
- 首次 embedding 失败写 FAILED state；
- 已有 COMPLETED state 后的失败保留旧 state，但 stale hash 不参与检索；
- 同 identity 并发 create 最终一条；
- 不同 identity 不错误合并；
- advisory lock key 使用绑定参数；
- restricted API Key allow / deny。

### 18.4 Embedding

- JSON 短 retrieval text 生成一个 chunk；
- JSON 较长但在配置上限内仍是一个 chunk；
- payload 从未传给 embedding model；
- payload-only 不改变 embedding state；
- running embedding + payload-only update 可复用已生成向量重试提交；
- running embedding + retrieval change 必须丢弃旧生成结果；
- generic short doc 现在可嵌入；
- fingerprint `hierarchical-v2` 与 `json-record-v1:single` 分离；
- 调整纯准入限额不会使 JSON record embedding 缓存失效。

### 18.5 Search

- 必须 collection scoped；
- ACL 限制；
- 只返回 json-record；
- 空 collection 候选返回空，不全库回退；
- 保持 hybrid / rerank 排序；
- 批量加载，无 N+1；
- payload 与 document ID 对应；
- 防御性 document 去重；
- payload 不出现在普通 search / chat prompt；
- disabled / stale / moved document 不返回。

### 18.6 Version

- CREATE 含 payload snapshot；
- payload-only 相同 hash 可创建下一版本；
- `(document_id, version_number)` 唯一；
- 普通 `recordVersion` 仍按 hash 跳过；
- version list 不返回快照；
- version detail 经 ACL 返回 payload snapshot；
- unauthorized key 返回 403。

### 18.7 Collection

- clone 保留 external ID / payload / retrieval text；
- clone 新 collection 不违反唯一键；
- clone 只创建目标初始版本，不复制源历史；
- export 包含 payload；
- import 旧格式兼容；
- import JSON record 校验；
- import JSON record 创建初始版本；
- duplicate external ID 明确失败；
- soft-delete unlink 后 JSON search 不返回；
- restricted key 不能读取 orphan JSON record；
- reattach 后恢复检索资格。

### 18.8 真实 PostgreSQL / Testcontainers

新增真正连接 `pgvector/pgvector:pg16` 的测试：

- 从空库运行全部 Flyway；
- `JsonNode` -> JSONB -> `JsonNode` round trip；
- object key 顺序变化不作为字节保真承诺；
- unique partial index；
- payload-only 多版本；
- document delete 对 versions 的 FK cascade；
- Hibernate optimistic version；
- clone / export / import；
- vector 表与新增 migration 共存。

镜像允许通过 `TESTCONTAINERS_PG_IMAGE` 覆盖；境内环境按
[中国境内网络指南](../../china-network-guide-zh-CN.md) 使用预拉取或可达镜像，
不能把区域镜像硬编码进测试或 Dockerfile。

## 19. 一键验证与记录在案

### 19.1 本地一键门禁

新增：

```bash
./scripts/verify-jsonb-records.sh
```

脚本按顺序：

1. 检查 Docker / Testcontainers 可用；
2. 运行 documents chunker 定向测试；
3. 运行 api DTO 测试；
4. 运行 core JSON record / ACL / version / collection 定向测试；
5. 运行真实 PostgreSQL JSONB integration test；
6. 运行 `mvn clean compile test-compile`；
7. 运行相关模块完整 `mvn test`；
8. 运行 `./scripts/verify-project-docs.sh`；
9. 运行 `git diff --check`；
10. 输出每步耗时、PASS / FAIL 和失败命令。

脚本使用 `set -euo pipefail`，任何步骤失败立即非零退出。

### 19.2 Live API E2E

新增：

```bash
BASE_URL=http://127.0.0.1:18081 \
RAG_API_KEY=... \
./scripts/jsonb-records-e2e.sh
```

流程：

1. 创建临时 collection；
2. upsert 两条相同 retrievalText、不同 payload / external ID 的记录；
3. 验证两条都存在；
4. 执行 search 并校验 payload 对应；
5. payload-only upsert，验证 content hash 和 embedding profile state 未变化；
6. retrievalText update，验证重新嵌入；
7. 用受限 API Key 验证 allowed / forbidden collection；
8. clone / export / import round trip；
9. 清理临时 collection 和文档；
10. 脱敏输出验证摘要。

真实模型 E2E 不应把 key 或 payload 写入日志。Mock 测试不能替代该脚本。

### 19.3 验证记录

实施完成后在合适的 progress / release readiness 文档记录：

- 日期、commit；
- Flyway 实际版本；
- 一键脚本版本；
- 单测 / integration / E2E 结果；
- 使用的 embedding profile；
- 已知限制；
- 境内镜像覆盖方式；
- 不记录任何真实 key。

## 20. 正式文档更新

实施时同步：

| 主题 | 中文 | 英文 |
|------|------|------|
| 稳定能力与边界 | `project-context-zh-CN.md` | `project-context.md` |
| 数据流与表结构 | `architecture-zh-CN.md` | `architecture.md` |
| HTTP 契约 | `rest-api-zh-CN.md` | `rest-api.md` |
| 配置和限额 | `configuration-zh-CN.md` | `configuration.md` |
| 测试命令 | `testing-guide-zh-CN.md` | `testing-guide.md` |
| 开发命令 | `developer-reference-zh-CN.md` | `developer-reference.md` |
| 常见问题 | `troubleshooting-zh-CN.md` | `troubleshooting.md` |
| 文档导航 | `index-zh-CN.md` | `index.md` |

`AGENTS.md` 只在 Flyway 范围、硬性命令或文档地图变化时做短链接更新，不复制本文。

正式文档必须明确区分：

- 普通 `.json` 文件上传：原始 JSON 文本直接作为普通 document content；
- JSON structured record API：`jsonbPayload + retrievalText` 双字段、只嵌入描述；
- PDF：原文件存 `fs_files`、Markdown 进入 RAG。

## 21. 风险、默认值与可逆边界

| 风险 | 默认决策 | 理由 | 可逆边界 |
|------|----------|------|----------|
| 同描述不同 JSON 被去重 | 专用 identity，不用 content dedup | 业务对象身份与描述不同 | 不影响普通文档 |
| JSONB byte hash 不稳定 | 不建 payloadHash | 本功能只需语义存储 | 字节保真未来另加列 |
| payload 响应过大 | 1 MiB/item，search max 20 | 控制放大 | 配置可调 |
| record 描述太长 | 单 chunk + 10000 chars | 保持一 payload 一结果 | 配置可调；未来可做聚合 |
| 大 collection ID 列表 | 首版沿用 candidate IDs | 最小改动复用检索 | 后续下推 SQL scope |
| payload-only version 被约束挡住 | version number 唯一 | 修复 force 语义 | 普通 hash 去重留在应用 |
| JSON 自动进入 prompt | 明确禁止 | 安全和责任边界 | 调用者显式处理 |
| collection soft-delete orphan | 保持现状并文档化 | 不扩大生命周期改造 | 后续独立方案 |
| WebUI 缺失 | backend first | 降低首版 blast radius | API 稳定后增加 |
| 未来格式泛化过早 | JSON/XML 独立字段 | 存储语义不同 | 真实第三种格式再评估 |

## 22. 验收标准

只有全部满足才算实施完成：

1. 调用者可用 `jsonbPayload + retrievalText + collectionId + externalId` 幂等 upsert。
2. JSONB 存 PostgreSQL `JSONB`，API 字段名严格为 `jsonbPayload`。
3. 不存在 `payloadHash` 字段、列、索引或隐式 hash。
4. `content_hash` 只对 `retrievalText` 计算。
5. 相同 retrievalText 的不同 JSON records 不会被合并。
6. payload-only update 不调用 embedding，content hash 和 freshness 不变。
7. retrievalText update 会使旧 embedding 失效，并在成功后原子替换。
8. JSON record 始终一个 record-level chunk。
9. 非空普通短文档不再因 `minChunkSize` 产生零 chunk。
10. 专用 search 必须 collection scoped、遵守 API Key ACL、返回对应 JSONB。
11. JSONB 不自动进入普通 search metadata、chat prompt 或日志。
12. payload-only 版本可以在相同 content hash 下连续记录。
13. 删除 document 会通过 FK cascade 清理版本历史，不产生 version orphan。
14. collection clone / export / import 保留 JSONB 和 external identity。
15. Flyway 从空库和升级库都通过真实 PostgreSQL 测试。
16. `./scripts/verify-jsonb-records.sh` 一键通过。
17. live JSON record E2E 通过。
18. 中英文正式文档同步，项目文档门禁和 `git diff --check` 通过。
19. 并行 Embedding Profile WIP 未被覆盖或回退。

## 23. 实施前唯一前置条件

用户批准本文后，实施者先确认 Embedding Profile / V25-V26 WIP 已稳定合并，并重新核对：

- 最新 Flyway 编号；
- `EmbeddingPersistenceService` 的 freshness SQL；
- vector 和 full-text provider 是否都按 profile state 排除 stale content；
- 当前工作区是否还有并发改动。

这些是 rebase 后的事实核对，不是待产品决策。本文其余核心设计已冻结，实施时不应再
临时重新决定字段命名、hash 语义、identity、API 职责或 XML 泛化方向。
