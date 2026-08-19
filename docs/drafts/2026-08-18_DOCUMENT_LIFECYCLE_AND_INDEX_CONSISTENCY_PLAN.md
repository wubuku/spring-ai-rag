# 文档生命周期与派生索引一致性：下一批高价值功能规划

> **状态：Phase 0 + Batch A 已实施并完成验收；实施进度见下方进度文档。**
>
> 本文是目标设计、实施顺序和验收标准的历史基线。Phase 0 + Batch A 的实时进度见
> [2026-08-19_DOCUMENT_LIFECYCLE_IMPLEMENTATION_PROGRESS.md](2026-08-19_DOCUMENT_LIFECYCLE_IMPLEMENTATION_PROGRESS.md)；
> 当前事实以代码、Flyway V1-V41 和已跟踪长青文档为准。
>
> **规划日期**：2026-08-18
> **最近复核**：2026-08-19
> **代码基线**：`3b8b15c`（`feat: deliver next high-value RAG operations`）
> **Spring AI**：1.1.4
> **规划基线 Flyway**：V1-V39；Phase 0 + Batch A 实施后为 V1-V41
> **主目标**：让文档增加、修改、删除、恢复与分块、全文索引、向量 embedding
> 形成明确、可靠、可观测、可重试的生命周期；同时给外部 client 一条不会因重复投递、
> 乱序投递、部分失败或来源漏删而破坏数据的标准接入路径。

## 1. 结论先行

下一批最高价值不应继续扩展文件格式、配额、通用 Agent 或更多检索选项，而应优先完成
文档数据面的产品化收口。

推荐顺序如下：

| 优先级 | 批次 | 功能 | 核心价值 | 体量 |
|---|---|---|---|---|
| P0 | A | 统一文档变更协调器、namespace 身份、完整快照、生命周期读模型与增量 reference client | 所有创建/更新/删除入口遵守同一套索引失效、任务调度、CAS、完整审计和状态语义；外部 client 立即获得可执行的增量 CRUD 接入路径 | M-L |
| P0 | B | 权威快照对账与 reference client 全量同步模式 | 让 connector 安全表达“来源当前全集”，自动 tombstone 已消失对象，不靠人工比对 | L |
| P1 | C | 基于完整快照的受控恢复 | 让误更新/误删除可恢复，并把恢复视为一次新的、可审计且会连带重索引的变更 | M |
| P1 | D | 本地 chunk/full-text 与远程 vector embedding 解耦 | embedding provider 故障时，新内容仍可关键词检索；两个派生阶段可独立重试和观测 | L-XL |

每个相关批次必须同时交付：

- 后端集成测试和真实 PostgreSQL 验收；
- API 契约与状态机测试；
- 一键验证脚本；
- 外部 client 最佳实践和可运行 reference client；Batch A 先交付增量模式，Batch B
  再扩展权威快照模式；
- 实施完成后更新中英文长青文档。

### 1.1 为什么先做这四项

当前系统已经有很多正确的底层构件：

- `collectionKey + externalId` 稳定身份；
- 普通外部文档 `sourceRevision`、精确重放和可选 CAS；
- tombstone 删除与同内部 `documentId` 恢复；
- `rag_document_embedding_state` 的 Profile 级 freshness；
- 持久化 embedding job、lease、heartbeat、取消、重试和提交 fencing；
- 版本快照；
- JSONB record 的 `retrievalText` / `jsonbPayload` 分离；
- Collection ACL 和检索范围 SQL 下推。

但这些能力分散在普通创建、批量创建、上传、PDF、普通外部文档和 JSON record
多条写入路径中。对外部 client 来说，目前也只有“单条 upsert 怎么调用”的说明，
还没有来源级完整对账协议和可执行参考实现。

因此当前风险不在于“没有 embedding”，而在于：

1. 不同入口对文档身份、去重、更新和索引调度的语义不一致。
2. client 能提交新增和修改，却需要自己找出来源中已经消失的对象并逐条删除。
3. 版本能查看但不能恢复。
4. 全文索引与向量共同存放在 `rag_embeddings`，embedding provider 失败时两种检索都不可用。

## 2. 当前事实与准确评价

### 2.1 当前写入入口不是一套统一 CRUD

| 入口 | 当前身份 | 更新 | 删除 | embedding |
|---|---|---|---|---|
| `POST /documents` | 自动内部 ID；按全局 `contentHash` 查重 | 无对应 update API | `DELETE /documents/{id}` 硬删除 | 创建后另调 embed |
| `POST /documents/batch` | 同上 | 实质是批量 create | 批量硬删除另有端点 | `SYNC/ASYNC/SKIP` |
| `POST /documents/upload` | 文件内容经 batch create | 再上传可能被全局 hash 复用 | 通过文档 ID 硬删除 | `SYNC/ASYNC/SKIP` |
| PDF-to-RAG | `pdf-import:<path>` source | 同 source 可更新；普通重复导入常产生新 UUID | RAG 文档与 `fs_files` 产物分离 | `SYNC/ASYNC/SKIP` |
| `POST /documents/upsert` | `collectionKey + externalId` | `sourceRevision` + 可选 CAS | 外部身份 tombstone | `SYNC/ASYNC/SKIP` |
| `POST /json-records/upsert` | `collectionKey + externalId` | 可幂等覆盖，但没有 source revision CAS | 无 JSON 专用删除 API | `SYNC/ASYNC/SKIP` |

评价：

- **普通外部文档是当前最接近推荐 client 契约的路径。**
- 普通 create/upload 的全局内容哈希查重把“内容指纹”误当成“业务身份”。
  相同内容属于不同 Collection、不同来源对象或不同 metadata 时，仍可能是不同文档。
- JSON record 已正确避免全局内容哈希去重，但缺少普通外部文档已有的
  revision/CAS/tombstone 能力。
- PDF 写入有自己的 source 识别和更新逻辑，没有统一复用外部文档生命周期。

### 2.2 当前 freshness 已能阻止旧内容被检索

向量和全文 provider 都通过
`EmbeddingProfileSqlScope.fromAndFreshness(profileId)` 查询 `rag_embeddings`，要求：

```text
embedding_state.status = COMPLETED
embedding_state.content_hash = rag_documents.content_hash
rag_documents.enabled = true
```

因此正文更新后，只要新的 `contentHash` 已经提交：

- 旧向量和旧 chunk 即使仍物理存在，也会立即退出向量和全文检索；
- 新 embedding 完成前，该文档不可检索；
- provider 返回后还要通过 document version/content hash/enabled 和 worker lease
  提交门，过期任务不能覆盖新版本。

这是正确的 **fresh-only** 一致性默认值，应保留。不能为了短暂可用性偷偷返回旧内容。

### 2.3 当前全文检索并不独立于 embedding

`pg_trgm`、English FTS 和中文 FTS 都查询 `rag_embeddings.chunk_text`；`tsvector`
生成列和 GIN/trigram 索引也建在该表。

后果：

- 新内容必须等完整 embedding 流程成功并原子替换 `rag_embeddings` 后，全文检索才能看到；
- embedding provider 失败时，关键词检索也失去新内容；
- 相同 chunk 文本随 Embedding Profile 重复保存；
- “本地分块失败”和“远程向量失败”无法独立表达。

这不是立即阻断正确性的 bug，因为 freshness 会防止旧结果泄漏；但它限制了故障降级能力，
是 Batch D 的主要理由。

### 2.4 字段变化并不都需要重嵌入

目标设计必须按影响分类，而不是每次 update 都调用 embedding provider：

| 变化 | 文档主记录 | 本地 chunk/full-text | vector embedding | 检索影响 |
|---|---|---|---|---|
| `content` / `retrievalText` | 更新 + 新 hash + 新版本 | 必须刷新 | 必须刷新 | 旧派生结果立即 stale |
| chunker 配置版本 | 不改正文 | 必须刷新 | 必须刷新 | 旧派生结果 stale |
| 活动 Embedding Profile | 不改正文 | 不必刷新 | 新 Profile 必须生成 | 只使用活动 Profile fresh 向量 |
| `title` / `source` / `originalFilename` | 更新 + 版本 | 当前不进入 chunk；无需刷新 | 无需刷新 | 返回结果时读取当前值 |
| `metadata` | 更新 + 版本 | 无需刷新 | 无需刷新 | filter/结果 metadata 立即读取当前值 |
| `jsonbPayload` | 更新 + 版本 | 无需刷新 | 无需刷新 | payload filter/详情立即读取当前值 |
| Collection 归属 | 更新 + 版本 | 无需刷新 | 无需刷新 | SQL scope 立即读取当前归属 |
| `enabled=false` / tombstone | 更新 + 版本 | 无需物理删除 | 无需物理删除 | 立即退出所有检索 |
| 硬删除 | 显式删除 legacy embeddings 后删除主记录 | state/job/version 依外键级联 | legacy embeddings 显式删除 | 永久不可恢复 |

若未来决定让 title 参与检索文本，必须更新“派生输入 hash”或 `chunkerVersion`；
不能只改 SQL 而继续把 `contentHash` 当作完整索引身份。

当前检索 SQL 虽然以 `d.metadata` 做 filter，却仍可能从 `rag_embeddings.metadata` 构造结果。
Batch A 必须把 title/source/original filename/Collection/document metadata/payload 等当前文档
属性统一从 `rag_documents` 读取；embedding/chunk 行只保存和返回真正的 chunk 级派生 metadata。
否则 metadata-only 更新虽能过滤到新值，citation/tool response 仍可能泄露旧值。

### 2.5 当前版本历史不足以安全恢复

`rag_document_versions` 当前保存：

- content/content hash；
- metadata；
- JSONB payload；
- source revision；
- size、change type、description。

但没有完整保存 title、source、document type、original filename、Collection、enabled/tombstone
等状态，且 API 只有 list/detail，没有 restore。

因此当前只能用于审计和人工读取，不能宣称支持完整 rollback。

另外，实体的 JPA `@Version` 会在 embedding 成功/失败提交时递增，而当前详情响应并不暴露
它。该字段适合内部 optimistic fencing，不适合作为外部 client 的业务 CAS token：
异步 embedding 完成不应让一个只修改 title/metadata 的 client 无故收到版本冲突。

### 2.6 当前 client 指引的优点与缺口

[REST API：外部文档幂等同步](../rest-api-zh-CN.md#external-documents-idempotent-synchronization)
已经正确说明：

- 稳定 `externalId`；
- opaque `sourceRevision`；
- 精确重放；
- `expectedSourceRevision`；
- tombstone；
- embedding failure 的重试；
- connector 使用受限 API Key。

仍缺少：

- 多 connector 共享一个 Collection 时的稳定来源 namespace；
- 全量快照完成后如何安全删除缺失项；
- connector 崩溃、超时、重跑和租约过期的标准算法；
- 大批量更新如何使用 ASYNC、如何等待 Collection readiness；
- `409`、`FAILED`、`STALE`、`CANCELLED` 的分类重试策略；
- 可运行的 reference client，而不只是零散 curl 示例。

## 3. 设计原则与不可破坏的不变量

### 3.1 核心不变量

1. **内容指纹不是业务身份。** `contentHash` 只用于 freshness、缓存和审计。
2. **正文提交即使旧派生结果失效。** 新派生结果未完成时返回“未就绪”，不返回旧正文 chunk。
3. **派生结果只能提交到仍匹配的派生输入。** 提交门校验正文 hash、文档形态、
   chunker 版本、Embedding Profile 和 worker lease/job generation；不能要求 JPA
   `rowVersion` 保持不变，否则并发的 metadata/payload/Collection 更新会错误丢弃仍有效的结果。
4. **删除/禁用立即影响检索。** 不等待物理向量清理。
5. **metadata、payload、Collection 等非文本变化不产生不必要的 embedding 成本。**
6. **所有重试必须幂等或受 CAS 保护。**
7. **外部 client 永远以稳定外部身份操作，不持久依赖内部 document ID。**
8. **来源级权威删除必须有明确 namespace 和显式 complete，不允许一次失败的半批导入触发漏删。**
9. **数据访问层不使用显式悲观锁。** 禁止 `FOR UPDATE`、`SKIP LOCKED`、
   JPA `PESSIMISTIC_*` 和 advisory lock；使用唯一约束、乐观版本、lease 和条件
   `UPDATE ... RETURNING`。
10. **不重新实现 Spring AI 已提供的 Chat/RAG/Tool 抽象。** 本规划的数据生命周期能力
    位于项目持久化和派生索引边界，与 Spring AI Advisor 保持解耦。

### 3.2 兼容原则

- 保留现有 v1 路径，增量增加字段和端点。
- `sourceNamespace` 省略时兼容为 `default`，既有
  `collectionKey + externalId` 调用继续工作。
- 现有 `DELETE /documents/{id}` 对本地管理文档保持硬删除语义，不静默改成软删除；
  对 `externalId` 非空的来源管理文档必须拒绝，防止绕过 external identity、revision 和对账。
- `SYNC` 仍保留同步等待体验，不强制 client 改成 `ASYNC`；但底层同样先落持久化 job。
- 新增的 local PATCH/restore 和 external sync-run item 默认 `embeddingPolicy=ASYNC`；
  reference client 和 WebUI 必须显式发送策略。既有 create/upload/batch/external/JSON 端点在
  兼容窗口继续按当前 `embed` 字段和缺省值映射，不能因升级静默增加调用成本；但一旦显式提供
  `embeddingPolicy`，它优先于 legacy `embed`。所有映射最终都进入同一 derivation 协调器，
  `SKIP` 也必须持久化为 `NOT_REQUESTED`，不能留下含糊的 `PENDING`。
- Batch A 将 `rag.embedding-jobs.enabled` 的默认值和 prod 推荐值改为 `true`，并在发布说明中
  标记配置语义变化。显式关闭时，读请求和不影响派生输入的更新仍可用，正文变更的
  `SYNC/ASYNC` 返回 503；禁止退回不可恢复的提交后直调 provider 模式。
- 现有历史版本不伪造缺失的快照字段。
- 所有 Flyway 迁移 forward-only，不改写 V1-V39。

## 4. 目标领域模型

### 4.1 文档身份

区分两类文档：

#### 本地管理文档

- 身份：内部 `documentId`；
- 适用：WebUI 手工上传、临时文档、没有上游稳定主键的内容；
- 更新必须使用服务端返回的 `expectedDocumentRevision`；
- 不允许依赖全局 content hash 作为身份。

#### 外部管理文档

- 身份：`collectionKey + sourceNamespace + externalId`；
- `sourceNamespace` 表示一个独立来源/connector 的权威空间，例如
  `cms-main`、`erp-products`、`support-kb`；
- `sourceRevision` 表示该来源对象的 opaque revision；
- `expectedSourceRevision` 用于 CAS；
- 内部 `documentId` 只用于诊断和 UI 深链接。

JSON record 是外部管理文档的一种内容形态，不应继续拥有较弱的并发/删除语义。

`sourceNamespace` 是**身份和权威对账边界，不是授权边界**。本批继续使用 Collection ACL：
有权写某 Collection 的 key 可以写其中任一 namespace。只有互相信任、由同一运营边界管理的
connector 才应共享 Collection；不互信 connector 必须使用不同 Collection。namespace 级
API Key ACL 属于后续安全加固，不应混入本批 CRUD 主线，也不能在 client guide 中宣称已隔离。

### 4.2 规范化生命周期状态

API 不再要求 client 自己组合 `processingStatus`、`embeddingFresh`、job status 和
chunk count 来猜测状态。新增服务端派生的读模型：

```json
{
  "documentState": "ACTIVE",
  "searchability": "READY",
  "localIndexStatus": "READY",
  "embeddingStatus": "READY",
  "activeEmbeddingProfileKey": "bge-m3-1024",
  "activeJobId": null,
  "lastErrorCode": null,
  "retryable": false
}
```

枚举建议：

`documentState`：

- `ACTIVE`
- `DISABLED`
- `TOMBSTONED`

`searchability`：

- `READY`：至少存在当前内容的可检索派生结果；
- `KEYWORD_ONLY`：Batch D 后，本地全文已就绪但 vector 未就绪；
- `INDEXING`：当前内容尚无可检索派生结果，任务正在排队/运行；
- `FAILED`：最近派生尝试失败；
- `NOT_REQUESTED`：调用方明确 `SKIP`；
- `DISABLED`：文档 tombstoned/disabled。

在 Batch D 前不会返回 `KEYWORD_ONLY`，因为全文和向量仍共用 `rag_embeddings`。

这个读模型不能只从 job 表临时猜测。Batch A 必须把“当前 document hash + 活动 Profile”
的派生状态持久化到 `rag_document_embedding_state`：

- 正文变更 + `SYNC`：文档事务内写入当前 hash 的 `QUEUED`，提交后进入 `PROCESSING`；
- 正文变更 + `ASYNC`：文档和 job 同事务提交，state 为 `QUEUED`；
- 正文变更 + `SKIP`：state 为 `NOT_REQUESTED`；
- provider/worker 失败：当前 hash 的 `FAILED`；
- 取消且没有后继 job：当前 hash 的 `CANCELLED`；
- 成功：原子替换 chunk/vector，并将当前 hash 置为 `COMPLETED`。

V40 扩展 state status check constraint 以接受
`QUEUED/PROCESSING/COMPLETED/FAILED/NOT_REQUESTED/CANCELLED`。旧 hash 的向量可以物理保留，
但 state 已指向当前 hash 且非 `COMPLETED` 时，检索仍会排除它。现有
`rag_documents.processing_status` 只作为兼容镜像，不再是 Profile 级生命周期的权威来源。

公开映射固定为：`QUEUED/PROCESSING -> INDEXING`、`COMPLETED -> READY`、
`FAILED -> FAILED`、`NOT_REQUESTED -> NOT_REQUESTED`；当前派生输入的 `CANCELLED` 映射为
`searchability=FAILED`、`lastErrorCode=INDEXING_CANCELLED`、`retryable=true`。force 维护任务
不改变 state 的 `COMPLETED`，因此取消时仍是 READY，只更新 `lastReindexError`。

显式 force re-embed 是例外：若同一 content hash 已有 fresh `COMPLETED`，排队或执行重建
不能把它降级为不可检索；旧完成态持续服务，job 单独报告进度，只有新的完整结果通过
commit guard 后才原子替换。只有**文档派生输入发生变化**时，当前 freshness 才立即失效。
force job 失败、取消或耗尽重试时，state 仍保持原 `COMPLETED`，`searchability=READY`；
读模型通过 `activeJobId/lastReindexError` 暴露维护任务结果，不能显示成正文不可检索。

`SYNC` 和 `ASYNC` 都必须在文档 mutation 的同一事务中创建持久化 embedding job（或等价
outbox 记录），不能在提交后只做一次不可恢复的直接 provider 调用。两者差别只在响应等待：

- `SYNC`：提交后由当前请求尝试原子 claim 该 job，并最多等待配置的短超时（推荐 30 秒）；
  请求中断或到期时，普通 worker 仍能接管/继续同一 job；
- `ASYNC`：提交成功后立即返回 queued；
- `SKIP`：不创建 job，明确记录 `NOT_REQUESTED`。

因此“jobs disabled”表示持久化任务基础设施不可用时，`SYNC` 与 `ASYNC` 的正文变更都应拒绝
并回滚；不能为了表面同步语义重新引入 crash window。纯 metadata/payload/scope mutation
不需要 job，仍可正常提交。

### 4.3 变更影响分类

内部统一产生 `DocumentMutationResult`：

```text
identityChanged        false for normal update
contentChanged         affects chunk/vector derivation
derivationInputChanged content/documentKind/chunker changes
metadataChanged        no embedding
payloadChanged         no embedding
scopeChanged           no embedding
enabledChanged         immediate search visibility
versionNumber
documentRevision       business mutation CAS token
derivationAction       NONE | SYNC | QUEUED | SKIPPED
```

所有 Controller 只能映射请求/响应、做认证授权和 HTTP 状态转换；不能各自复制
hash、版本、job 或 embedding 判断。

`documentRevision` 是新增的服务端单调业务修订号，只在 create/update/disable/restore/
tombstone 等文档业务变更时增加。现有 JPA `@Version` 的概念改称内部 `rowVersion`
（数据库列可保持 `version`），继续保护持久化和 embedding commit，但不进入公开 CAS 契约。
详情、列表和所有 mutation 响应都必须返回 `documentRevision`。

这里的“保护 embedding commit”不表示要求开始与提交时 `rowVersion` 完全相等。
`rowVersion` 只保护短事务内的行更新；跨远程 provider 调用的最终提交使用
`derivationFingerprint = contentHash + documentKind + chunkerVersion + embeddingProfileId`
和 worker lease/job generation 仲裁。非派生字段更新即使改变了 `rowVersion`，仍允许同一
fingerprint 的结果提交；正文/文档形态/chunker/Profile 改变或 job 被 supersede 时必须拒绝。
这里的 `documentKind` 是影响派生算法的规范化形态（首版仅 `TEXT|JSON_RECORD`），不是任意
展示型 `documentType` 字符串。external identity 的 kind 不可变；普通 text/PDF/Markdown
均归一为 `TEXT`，避免无意义的重嵌入。

## 5. Batch A：统一文档变更协调器与 CRUD 生命周期

### 5.1 产品目标

完成后应满足：

- 普通本地文档可创建、读取、CAS 更新、禁用/恢复和硬删除；
- 外部普通文档和 JSON record 使用一致的 revision/CAS/tombstone 语义；
- upload、batch、PDF 和外部 upsert 最终走同一个 mutation + derivation 协调层；
- 所有内容变化自动按 `SYNC/ASYNC/SKIP` 连带处理 embedding；
- metadata/payload-only 更新不会重嵌入；
- 删除会阻止在途旧任务提交，并尽快取消尚未执行的任务；
- API 明确返回文档和派生索引的最终/当前状态。

### 5.2 服务拆分

建议新增：

```text
DocumentMutationService
  - createLocal(...)
  - updateLocal(...)
  - upsertExternal(...)
  - tombstoneExternal(...)
  - disableLocal(...)
  - restoreLocal(...)
  - hardDelete(...)

DocumentDerivationCoordinator
  - classifyImpact(before, after)
  - dispatch(documentSnapshot, policy, origin)
  - cancelSupersededJobs(documentId, currentHash)
  - readLifecycle(documentId, activeProfile)

DocumentIdentityService
  - local ID / external identity resolution
  - source namespace normalization
  - ACL and immutable identity checks
```

`ExternalDocumentService`、`JsonRecordService`、`BatchDocumentService` 和
`PdfToRagService` 逐步变成适配层。不能一次保留五套独立更新判断。

### 5.3 API 变化

#### 本地文档 CAS 更新

```http
PATCH /api/v1/rag/documents/{documentId}
```

```json
{
  "expectedDocumentRevision": 12,
  "title": "更新后的标题",
  "content": "更新后的正文",
  "metadata": {"locale": "zh-CN"},
  "collectionKey": "customer-42:manual:v3",
  "embeddingPolicy": "ASYNC"
}
```

规则：

- 只适用于 `externalId` 为空的本地管理文档；
- 首版可变字段固定为 `title`、`content`、`source`、`metadata` 和 `collectionKey`；
  `documentType`、`originalFilename`、external identity、JSON payload 和 `enabled` 不接受于该端点；
- DTO 必须显式记录每个字段是否出现，不能用普通 nullable Java 字段混淆“省略”和
  “显式 null”；还必须用 DTO 级 unknown-field 捕获/校验明确拒绝其余字段，不能依赖项目当前
  并不存在的全局 Jackson 严格模式。请求语义固定如下：

  | 字段 | 省略 | 显式 `null` | 空字符串/空对象 |
  |---|---|---|---|
  | `title` | 不修改 | 拒绝 | 空白拒绝；最大 255，与数据库一致 |
  | `content` | 不修改 | 拒绝 | 空白拒绝；短但非空允许 |
  | `source` | 不修改 | 清空 | 空白规范化为 `null` |
  | `metadata` | 不修改 | 清空为 `{}` | `{}` 表示清空；对象整体替换，不做隐式 merge |
  | `collectionKey` | 不修改 | 解除 Collection 归属 | 空白拒绝 |

- metadata 的业务空值固定为 `{}`；读取和 exact/no-op 比较时 legacy SQL `NULL` 与 `{}` 等价，
  新 mutation 写入 `{}`，避免同一 revision 因存储表示差异被误判冲突；
- 解除 Collection 归属只允许 ADMIN/不受限 caller；Collection 受限 key 不能把文档移入
  自己不可见、且可能被不受限 caller 看见的 unassigned scope；
- `expectedDocumentRevision` 必填，冲突返回 `409 DOCUMENT_REVISION_CONFLICT`；
- content 变化自动计算 hash、记录版本并调度派生；
- 非 content 变化不调 embedding provider；
- 可见性只能通过专用 disable/restore 端点修改，普通 patch 不接受 `enabled`；
- Collection 变化必须同时通过旧、新 Collection ACL；
- 禁止把本地文档通过 patch 直接“认领”为外部文档；
- disabled 本地文档允许 metadata/scope 修改，也允许以 `embeddingPolicy=SKIP` 离线修改正文；
  disabled 状态下正文变更若请求 `SYNC/ASYNC` 返回 409，避免创建因 `enabled=false` 注定不能
  提交的任务。随后 restore 按其请求策略为当前内容复用 fresh 结果或创建持久化 job；
- 规范化后没有任何变化的请求返回 `action=UNCHANGED`，不增加
  `documentRevision`、版本快照或 embedding job；只提交 revision 而没有任何可变字段时返回
  `400 EMPTY_PATCH`。

#### 本地文档禁用与恢复

```http
POST /api/v1/rag/documents/{documentId}/disable
POST /api/v1/rag/documents/{documentId}/restore
```

两者都要求 `expectedDocumentRevision`。restore 不自动重嵌入已经 fresh 的同一内容；
若当前内容没有 fresh embedding，则按请求 `embeddingPolicy` 调度。

WebUI 和新 client 对本地文档的日常“删除”默认调用 `disable`，以便恢复；永久删除作为
单独的危险操作继续调用 `DELETE /documents/{documentId}`，要求
`expectedDocumentRevision`，并明确返回删除的派生记录计数。该端点仅接受本地管理文档。

revision 的 HTTP 传输固定为：

- `PATCH`：请求体中的 `expectedDocumentRevision`；
- `POST .../disable`：请求体只包含 `{"expectedDocumentRevision": 12}`，DTO 自行捕获并拒绝
  其他字段；
- `POST .../restore`：请求体包含
  `{"expectedDocumentRevision": 12, "embeddingPolicy": "ASYNC"}`；
- `DELETE /documents/{documentId}`：
  `expectedDocumentRevision` 使用必填 query parameter，首版不接受 DELETE body；
- 成功响应统一返回新的 `documentRevision`；冲突响应返回安全的当前 revision 和重读链接，
  但不回显正文。

#### 外部普通文档

保留：

```http
POST   /api/v1/rag/documents/upsert
GET    /api/v1/rag/documents/by-external-id
DELETE /api/v1/rag/documents/by-external-id
```

请求/查询增加可选 `sourceNamespace`，默认 `default`。

外部文档 exact replay 仍可不带 `expectedSourceRevision`；创建新 identity 时该字段必须为空。
对既有 identity 使用新 `sourceRevision` 的 update/delete，目标契约要求
`expectedSourceRevision` 必填，避免 opaque revision 的乱序投递覆盖新状态。兼容窗口内旧调用
可通过显式 `LEGACY_LAST_WRITE_WINS` 配置继续运行并收到 deprecation warning；prod 推荐和
reference client 默认严格 CAS。该规则与 JSON record 共用同一实现，不允许两套强弱语义。

外部 upsert 是**完整期望状态**，不是 merge patch。`title` 和 `content/retrievalText` 必填；
`source` 省略或 `null` 都表示清空，`metadata` 省略、`null` 或 `{}` 都表示空对象；
JSON record 的 `jsonbPayload` 必填且不得是 JSON `null`，继续接受 object、array、string、
number 或 boolean。client 必须在重试时重放同一规范化完整状态和同一 revision，不能依赖
服务端保留上一次遗漏的可选字段。该语义必须写入 OpenAPI、client guide 和 reference
client schema。受管字段的比较与请求 fingerprint 将 metadata 的 legacy SQL `NULL` 和 `{}`
视为同一个业务空值；下一次真实 mutation 再规范化写入 `{}`。

`sourceNamespace` 不是只改 external upsert 的局部字段。Batch A 必须同步升级所有稳定身份消费者：

- repository 查询统一使用 `collectionId + sourceNamespace + externalId`，禁止保留返回
  `Optional` 的二元身份查询；
- document/JSON detail、summary、upsert/delete response 都返回 namespace；
- Collection export/import 保存 namespace，导入时按 namespace + external ID 检查重复；
- managed evaluation suite 的相关文档身份扩展为
  `collectionKey + sourceNamespace + externalId`，旧 suite 定义默认 namespace=`default`；
- 一键 goldenset 和 reference client manifest 也显式携带 namespace；
- WebUI 对来源管理文档展示 namespace，但仍不依赖内部 document ID 做写操作。

#### JSON record

增加：

```http
GET    /api/v1/rag/json-records/by-external-id
DELETE /api/v1/rag/json-records/by-external-id
```

`JsonRecordUpsertRequest` 增加：

```json
{
  "sourceNamespace": "erp-products",
  "sourceRevision": "rowversion:9182",
  "expectedSourceRevision": "rowversion:9171"
}
```

兼容策略：

- 既有未提供 revision 的 JSON upsert 在过渡期继续可用，标记为
  `LEGACY_LAST_WRITE_WINS`；
- reference client 和新文档一律要求 revision；
- 对既有 identity 的新 revision 更新/删除要求 `expectedSourceRevision`；配置项允许兼容
  legacy last-write-wins，但生产默认拒绝；
- 同 revision 不同 retrievalText/payload/受管字段返回 409；
- payload-only 更新创建版本但不触发 embedding。

#### 文本文件与 PDF 的首批边界

既有 `POST /documents/upload` 和 PDF-to-RAG 继续表示**本地管理导入**，WebUI 手工上传
默认使用这条路径；其创建/更新和派生任务必须改由统一 mutation/derivation 协调层执行，
但文件产物生命周期继续与 RAG 文档分离。

Batch A **不新增**外部 multipart/PDF 专用 stable-identity 端点。外部 connector 首版应在
自身边界完成文件解析或 PDF-to-text/Markdown 转换，再通过普通 external TEXT upsert 提交
稳定 `collectionKey + sourceNamespace + externalId + sourceRevision` 和提取后的正文。
这样可以先验证统一 CRUD、CAS 和索引连带更新，不把文件上传协议、转换产物 retention 与
来源同步混入同一发布。

以后只有在真实 connector 反复需要“由 RAG 服务托管上传、转换和外部身份”时，才单独增加
`upsert-file` / `pdf-to-rag/upsert` adapter；它们必须复用
`DocumentMutationService.upsertExternal(...)`，不能形成新的生命周期实现。PDF 每次转换可
产生新的 `fs_files` artifact，旧 artifact retention 仍属于文件子系统；不能在 RAG 文档
事务里级联删除文件产物。

### 5.4 去重语义修正

`contentHash` 不再作为 canonical 文档身份。

实施策略：

1. 外部 client 一律使用 external identity upsert。
2. WebUI upload 和新本地 create 默认创建独立文档。
3. 旧 batch/create 的全局 hash 复用增加显式兼容枚举：
   `LEGACY_GLOBAL`、`COLLECTION`、`NONE`。
4. 旧请求未传字段时暂时保持 `LEGACY_GLOBAL`，但返回 deprecation warning；
   WebUI 和所有新示例显式使用 `NONE`。
5. 后续大版本才考虑移除 `LEGACY_GLOBAL`。

`LEGACY_GLOBAL` 只是兼容名称，查重查询必须先应用 caller 可见 Collection scope；受限 key
不能命中、复用或得知不可见 Collection 的 document ID。auth-disabled/ADMIN 调用者才等价于
真正全局范围。必须增加跨 Collection 同内容的越权回归测试。

内容 hash 不再承担身份后，本地 create/upload/batch 需要独立的网络重试幂等：

- 接受 `Idempotency-Key` header，WebUI 和新 client 对每次逻辑创建生成随机 key；
- 服务端只保存 key hash，并以
  `(ownerPrincipalId, operationType, idempotencyKeyHash)` 唯一；
- 同时保存 canonical validated request fingerprint、状态和成功结果引用，不保存完整正文、
  文件或 raw key；
- 同 key + 同 fingerprint 的进行中请求返回同一 operation/job 状态，成功重放返回同一
  document/result；同 key + 不同 fingerprint 返回 409；
- JSON body 的 fingerprint 基于规范化受管字段和 content hash；multipart 基于解析后正文/
  文件 hash、Collection、metadata 和策略，不依赖 multipart boundary；
- operation 行与文档 mutation、版本和持久化 job 同事务完成，响应丢失后仍可重放；
- 兼容期允许旧 client 不传 key，但明确标记“非网络幂等”；WebUI 不得走该兼容分支。

### 5.5 派生任务处理

内容变化事务提交时：

1. 新 hash 立即使旧 `embedding_state` 不匹配；
2. `SYNC`：在文档事务内创建持久化 job，提交后由当前请求 claim/执行并等待；
3. `ASYNC`：在文档事务内创建同一种持久化 job并立即返回；
4. `SKIP`：保留 stale/not-requested 状态，明确返回不可检索；
5. 对同文档旧 hash 的 queued job 标为 `STALE`；
6. 对 running job 设置 cancel request；即使 provider 已在执行，最终 commit guard 也必须拒绝；
7. tombstone/disable 同样执行第 5、6 步；
8. hard delete 先显式删除旧 `rag_embeddings` 行，再删除 document；V25 state、V29 versions
   和 V33 jobs 依赖已有 `ON DELETE CASCADE`，并验证运行 worker 对缺失文档安全终止。

不要求同步删除旧向量；正确性由 freshness 保证，物理清理由独立 retention job 处理。
跨 provider 调用的 commit guard 不使用公开 `documentRevision` 或内部 `rowVersion`
作为唯一依据；否则 title/metadata/payload/Collection-only 更新会让有效结果丢失。

删除权限和身份规则：

- local disable/restore/hard delete 使用内部 ID + `expectedDocumentRevision`；
- external TEXT/JSON/file/PDF 删除只能使用
  `collectionKey + sourceNamespace + externalId + sourceRevision` tombstone；
- `DELETE /documents/{id}` 和 batch hard delete 遇到 `externalId` 非空时逐项拒绝；
- external tombstone 的物理 purge 只允许独立的 ADMIN/retention 运维能力，必须有保留期、
  审计原因和明确 scope，不作为 connector CRUD 的一部分。

### 5.6 状态响应与 HTTP 语义

- persistence/CAS 失败：4xx/5xx，文档变更不提交；
- persistence 成功、SYNC 在等待期内成功：HTTP 200，`searchability=READY`；
- persistence 成功、SYNC 达到有界等待时间但 job 仍 queued/running/retrying：HTTP 202，
  返回 job ID、`searchability=INDEXING` 和 polling 链接；
- persistence 成功、SYNC 在等待期内进入 terminal failure：HTTP 200，mutation 成功，
  `searchability=FAILED`、`retryable=true`；
- ASYNC 入队成功：HTTP 202 或现有兼容端点 HTTP 200 + `embeddingAction=ASYNC_QUEUED`；
- 精确重放：HTTP 200，`action=UNCHANGED`，不重复创建版本和任务；
- stale CAS：HTTP 409；
- jobs disabled 但正文变更请求 SYNC/ASYNC：HTTP 503，文档事务必须回滚，不能出现
  “文档已改但没有可恢复任务”；
- SKIP：明确 `NOT_REQUESTED`，不能伪装为成功索引。

### 5.7 数据迁移

建议 `V40__document_lifecycle_expand.sql`：

- `rag_documents.source_namespace VARCHAR(128) NOT NULL DEFAULT 'default'`；
- `rag_documents.document_revision BIGINT NOT NULL DEFAULT 1`；
- `rag_documents.next_history_version INTEGER NOT NULL DEFAULT 1`，迁移时按既有最大
  `version_number + 1` 回填；
- `rag_documents.source_mutation_sequence BIGINT NOT NULL DEFAULT 0`；
- 新建 `rag_document_source_namespaces` 协调表，至少包含
  `(collection_id, source_namespace)` 唯一键、`mutation_sequence`、`sync_generation`、
  `active_run_id` 和 `row_version`；外部来源变更通过该行的条件
  `UPDATE ... RETURNING` 分配 namespace 内单调 mutation sequence；
- `rag_document_embedding_state.request_generation BIGINT NOT NULL DEFAULT 0`；
- `rag_embedding_jobs` 增加 `request_generation BIGINT NOT NULL DEFAULT 0`、
  `document_kind VARCHAR(32)`、`chunker_version VARCHAR(128)`；generation=0 或派生指纹不完整
  只表示迁移前历史任务，不能通过新 commit guard；
- 为 job generation 增加普通查询索引；V40 暂不建立新的活动唯一索引，也不删除旧的
  `(document_id, embedding_profile_id, content_hash)` 活动索引；
- 新建 `(collection_id, source_namespace, external_id)` partial unique，但暂时保留旧的
  `(collection_id, external_id)` 唯一索引；
- 为 source namespace + enabled/source deletion 增加对账索引；
- 增加 `disabled_at`，明确区分本地 disable 与来源 tombstone；
- 扩展 `rag_document_embedding_state.status`，并允许 mutation 事务写入当前 hash 的
  `QUEUED/NOT_REQUESTED/CANCELLED`；
- 为 `rag_document_versions` 增加完整快照字段：
  `title_snapshot`、`source_snapshot`、`document_type_snapshot`、
  `original_filename_snapshot`、`collection_id_snapshot`、
  `source_namespace_snapshot`、`enabled_snapshot`、`disabled_at_snapshot`、
  `source_deleted_at_snapshot`；
- 为版本行增加 `snapshot_completeness`，schema 默认值和既有行统一为
  `CONTENT_AND_METADATA_ONLY`；只有 generation-aware 新 mutation 协调器显式写 `FULL`，
  防止兼容窗口内旧应用写入被误标为完整快照；
- 新建作用域化 `rag_document_idempotency_operations`，保存 principal、operation type、
  key hash、request fingerprint、状态、结果 document/batch ID 和过期时间；
- 不删除 `embedded_content_hash` 等兼容字段。

V40 对应 **A1 expand/compat 发布阶段**：

- 新应用读写三元身份字段，但 feature flag 限制 `sourceNamespace=default`，因此仍兼容
  旧二元唯一索引和旧应用；
- repository/API/导入导出/evaluation/WebUI 已完成三元身份改造，但不能在 A1 宣称或接受
  同 Collection 跨 namespace 复用 external ID；
- 新 job 协调器使用 generation CAS，并在创建新 generation 前把该 document/Profile 的旧活动
  job 置为 `STALE`；V40 期间仍由旧 content-hash 活动索引提供兼容约束；
- A1 保留旧列/旧索引，因此可在维护窗口回滚到 V39 应用做只读诊断；但 V39 不维护
  `documentRevision`、完整快照和 job generation，回滚期间必须冻结文档/Collection mutation
  并停用 embedding worker。恢复写流量前必须重新部署 generation-aware 新应用并执行
  reconciliation，不能把“schema 可兼容启动”误解为“旧应用可继续安全写入”。

随后建议 `V41__document_lifecycle_contract.sql` 作为 **A2 contract/enable 发布阶段**：

- 前置检查所有生产读写进程都已运行三元身份/generation-aware 代码；
- 确认不存在 generation=0 或派生指纹不完整的 active job；
- 删除旧 `(collection_id, external_id)` 唯一索引，保留三元 partial unique；
- 删除旧 content-hash 活动 job 索引，建立
  `(document_id, embedding_profile_id, request_generation)` partial unique；
- 将 `document_kind`、`chunker_version` 等新 job 字段收紧为 `NOT NULL`/check；
- contract 成功后才开启非 `default` namespace 和 Batch B sync-run feature；
- V41 之后不支持直接回滚到不理解三元身份/generation 的旧应用；故障处理采用关闭 feature、
  保持 schema 的应用回滚或 roll-forward，必要时依赖发布前数据库备份。

迁移前检查：

- 既有外部文档全部回填 `default`；
- normalized namespace 只允许 1-128 visible ASCII；
- 验证新唯一键无冲突；
- 在 V41 删除旧 `(collection_id, external_id)` 唯一索引前，所有读写查询必须已切换到
  三元身份；expand/migrate/contract 顺序要有 PostgreSQL 集成测试；
- 迁移失败必须保持旧索引和数据不变。

存量 embedding job 必须执行明确的 expand/migrate/contract 流程，不能直接给历史表增加
无法回填的 `NOT NULL` 派生指纹：

1. 发布前停止旧版本 worker，并等待当前短事务结束；不能让不认识 generation 的旧 worker
   与新提交门并行运行。
2. V40 先增加兼容默认/nullable 列。历史 job 的 `documentKind` 从当前文档规范化为
   `TEXT|JSON_RECORD`；`chunkerVersion` 只在同 document/Profile state 的 content hash
   与 job hash 一致时从 state 回填，否则标记 `legacy-unknown`。
3. V40 将所有仍为 `QUEUED/RUNNING` 且
   `requestGeneration=0` 的 legacy job 条件更新为 `STALE`，清除 lease，并保留审计字段；
   不能猜测它在远程 provider 中是否仍对应当前派生输入。该顺序也处理旧 content-hash
   唯一索引曾允许同一 document/Profile 存在多个不同 hash 活动任务的情况。
4. 新应用随后启动 reconciliation。对 enabled 且当前派生结果不 fresh 的文档，由统一协调器创建
   generation>=1、完整记录 content hash/document kind/chunker version/Profile 的 replacement
   job；已有 fresh `COMPLETED` state 不重复产生费用。
5. 新代码只创建 generation>=1 的完整 job，commit guard 明确拒绝 generation=0、
   `legacy-unknown` 或派生指纹缺失的任务。
6. 观察一个发布窗口且确认没有 legacy active job 后，由 V41 收紧非空/check 约束、删除
   旧 content-hash 活动索引并建立 generation 活动唯一索引。

迁移验收必须从包含 `QUEUED/RUNNING/SUCCEEDED/FAILED` 历史 job 的 V39 数据库升级：
终态审计记录保留；legacy active job 不能提交；需要派生的当前文档恰好得到一个 replacement
generation；已 fresh 文档不重复调用 provider。

所有版本快照编号通过文档行上的 `next_history_version` 条件
`UPDATE ... RETURNING` 原子分配，不再使用“查询 MAX 后插入”。业务 mutation 在同一短事务
中校验/递增 `document_revision`、分配快照号并写入完整快照；embedding 只改变内部
`rowVersion` 和派生状态，不改变 `documentRevision`。

版本快照统一表示 **mutation 提交后的完整文档状态**：

- `CREATE/UPDATE/DISABLE/RESTORE/TOMBSTONE/COLLECTION_MOVE` 都记录变更后的状态；
- `TOMBSTONE` 快照自身仍是 deleted 状态；恢复历史内容时选择之前的 active snapshot，
  或使用当前文档 restore 端点重新启用同一内容；
- 每个成功业务 mutation 恰好产生一个新 snapshot 和一个新 `documentRevision`；
- embedding job 成败、重试、force re-embed 不产生业务版本快照；
- 快照写入、document mutation 和持久化 derivation job 必须同事务，任一失败整体回滚。

Collection 级操作同样不能绕开该契约：

- 删除 Collection 时，对本地管理文档的批量 unlink 必须通过 set-based mutation 协调器，
  为每份文档递增 revision、分配完整 `COLLECTION_MOVE` 快照并立即改变检索 scope；
- Collection 内存在 external-managed 文档时继续拒绝 unlink/delete，必须先由来源
  tombstone/purge，不能破坏其三元身份；
- Collection clone 默认把复制出的文档创建为本地管理文档，清空 external identity、
  source revision/tombstone 字段；若未来需要复制来源身份，必须由调用方提供新的 namespace；
- clone/create 的新文档按目标策略创建持久化 embedding jobs，不能只留永久 `PENDING`。

每个 document/Profile 的派生请求由 `request_generation` 单调编号：

- 正文/文档形态/chunker 输入变化时，当前 Profile 的 generation +1，state 指向新输入并置
  `QUEUED/NOT_REQUESTED`；
- force re-embed 同样 generation +1，但若同一输入已有 fresh `COMPLETED`，state 保持
  `COMPLETED` 供在线查询，进度从 job 读取；
- job 记录 generation、content hash、document kind、chunker version 和 profile；
- commit 必须匹配 state 当前 generation、派生输入和有效 lease；
- metadata/payload/Collection-only 更新不增加 generation；
- 检索 freshness 除 content hash 外还要校验当前文档形态对应的 `chunker_version`，
  修复现有 SQL 只比较 content hash 的缺口。

“当前应使用的 chunker 版本”不能只从 state 自证。Batch A 增加单一
`DocumentDerivationDescriptorProvider`，按规范化 `documentKind` 返回当前版本：
首版为 `TEXT -> hierarchical-v2:<size>:<min>:<overlap>`、
`JSON_RECORD -> json-record-v1:single`。所有 embedding 调度、commit guard、reconciliation
和检索 SQL 共用该 provider。检索 SQL 对混合文档使用按 kind 的 `CASE` 并绑定这两个当前
版本参数，要求 `state.chunker_version` 等于对应当前值；配置或实现版本变化后，旧结果会立即
退出检索，reconciliation 再为受影响文档创建新 generation。不得让各 provider 各自拼版本
字符串，也不得只比较 state 自己保存的旧值。

`source_mutation_sequence` 不使用独立 PostgreSQL sequence。sequence 的取号顺序不等于事务
提交顺序，无法可靠判断一个 webhook 与 snapshot begin 的先后。所有普通 external
upsert/delete、run begin、run batch item 和 complete 都必须在各自 mutation 事务中，首先
对同一 namespace 协调行执行条件 `UPDATE ... RETURNING`，再访问 run/document 行。该统一
锁序既建立提交顺序，也避免 namespace/run/document 多行交叉更新产生死锁：

- 普通增量 mutation 取得新 `mutation_sequence` 并写入 document；
- begin 取得并保存新的水位作为 `snapshotStartSequence`；
- run item 先取得新 sequence，再确认目标 document 的旧 sequence 不晚于 begin 水位并写入；
- complete 取得一个新的 `completeMutationSequence`，随后将该值写入本次全部 missing
  tombstone；并发增量 mutation 只能发生在 complete 事务之前或之后，不能插入候选计算中间；
- 同一协调行上的普通短 `UPDATE` 由数据库提供事务内写入仲裁，但应用不使用
  `FOR UPDATE`、`SKIP LOCKED` 或任何显式悲观锁。

这样，若旧 webhook 事务先取得协调更新，begin 会等待其提交后取得更大水位；若 begin 先取得，
webhook 必然得到更大的 sequence，complete 会保护该实时更新。

## 6. Batch B：权威来源快照对账与 reference client

### 6.1 产品目标

外部 client 能表达：

> “这是 `collectionKey + sourceNamespace` 在某次完整来源快照中的全部对象。
> 本次成功上传的对象应新增/更新；此前存在但本次没有出现的对象应 tombstone。”

协议必须保证：

- 上传一半崩溃不会删除其余文档；
- complete 可安全重试；
- 同 namespace 同时最多一个 active run；
- 过期 run 不能在新 run 之后完成；
- 不同 namespace 互不删除；
- 文档 upsert 与 embedding 可以并行推进；
- complete 不等待所有 embedding 完成，但返回 readiness 摘要。

### 6.2 推荐协议

#### 开始 run

```http
POST /api/v1/rag/document-sync-runs
```

```json
{
  "collectionKey": "customer-42:manual:v3",
  "sourceNamespace": "cms-main",
  "clientRunId": "cms-snapshot-2026-08-18T12:00:00Z",
  "snapshotMode": "ONLINE_CUT",
  "missingPolicy": "TOMBSTONE",
  "leaseSeconds": 900
}
```

`snapshotMode`：

- `ONLINE_CUT`：先 begin，再在上游建立一致性 snapshot/cut；
- `OFFLINE_MANIFEST`：manifest 在 begin 前已经生成；
- `EXCLUSIVE_OFFLINE`：离线 manifest，但 connector 明确保证生成到 complete 期间独占来源写入。

`missingPolicy`：

- `NONE`：只新增/更新，不按 missing 删除，安全默认；
- `TOMBSTONE`：complete 时 tombstone missing，仅允许
  `ONLINE_CUT` 或显式 `EXCLUSIVE_OFFLINE`。

client 在 begin 前生成至少 128 bit 随机 `leaseToken`，通过 `X-RAG-Sync-Lease`
请求头发送，并以仅当前用户可读的本地权限保存到 checkpoint。服务端只保存 token hash。
返回 server `runId`、generation、lease expiry 和当前 namespace 状态。
`clientRunId` 在 namespace 内唯一；以相同 token 精确重放 begin 返回原 run，因此
“服务已创建 run 但响应丢失”不会使 run 失去控制。后续 batch/seal/preview/complete/abort
继续提交该 header，防止旧 connector 进程仅凭 run ID 写入。
begin 通过 namespace 协调行的条件 `UPDATE ... RETURNING` 增加
`mutation_sequence`，将返回值记录为 `snapshotStartSequence`。它与普通来源 mutation
共享同一 CAS 水位，因此 run 开始后的 mutation 必然取得更大的序号。

begin 必须先按 namespace + `clientRunId` 查找既有 run：同 token hash 的精确重放直接返回，
不得递增 namespace sequence 或 generation；同 clientRunId 不同 token 返回 409。只有确实
创建新 run 时才进入 namespace CAS。若已有 active run，未过期则拒绝；已过期时在同一事务
先 CAS 标记旧 run `EXPIRED`，再增加 `syncGeneration` 并安装新 `activeRunId`。

#### 批量应用当前对象

```http
POST /api/v1/rag/document-sync-runs/{runId}/batch-upsert
```

每项沿用普通文档或 JSON record 的受管字段和 `sourceRevision`，默认
`embeddingPolicy=ASYNC`。服务端为成功项记录 `lastSeenSyncRunId`，不把完整正文复制到 run 表。
snapshot batch 不要求 client 为每项提供 `expectedSourceRevision`：它使用
`snapshotStartSequence` 和 namespace 协调水位作为该 run 专用 CAS。普通 webhook/CDC 单条
upsert/delete 仍要求 `expectedSourceRevision`。

这个水位只排序**已到达 RAG 服务**的来源 mutation，不能证明一份在 begin 前预生成的离线
manifest 是最新来源状态。外部 client 必须选择以下安全模式之一：

1. **在线一致性快照（推荐）**：使用 `ONLINE_CUT + TOMBSTONE`，先 begin，再在上游建立
   一致性 snapshot/cut，读取并上传；
   cut 之后的 CDC/webhook 继续投递，RAG 水位会阻止旧 snapshot item 覆盖已到达的新 mutation。
2. **离线 manifest + 基线 CAS**：使用 `OFFLINE_MANIFEST + NONE`；每个既有 item 携带
   connector checkpoint 中的 `expectedSourceRevision`，服务端仅在当前 revision 匹配时应用。
   这能安全 upsert，但**不能**据 missing 删除，因为 manifest 缺失项没有逐项 CAS。
3. **独占来源窗口**：使用 `EXCLUSIVE_OFFLINE + TOMBSTONE`；connector 明确保证 manifest
   生成到 complete 期间没有其他写入者。服务端记录审计声明并要求额外确认，
   reference client 不默认开启。

如果 client 既不能建立一致性 cut，也没有基线 revision，还不能独占来源写入，则无法诚实地
保证 snapshot 不覆盖或误删较新数据；服务端必须强制 `missingPolicy=NONE`，而不是猜测顺序。

item 使用显式 `documentKind=TEXT|JSON_RECORD`：

- `TEXT` 提供 `content`；
- `JSON_RECORD` 提供 `retrievalText + jsonbPayload`；
- 两者共享 title/source/metadata/revision 字段；
- 一个 namespace 可以包含两种 kind，但同一 external identity 的 kind 不可变。

批次行为：

- 最多 50 项或沿用配置上限；
- 单项隔离失败，保持输入顺序；
- 返回 accepted/unchanged/conflict/persistence failed/embedding queued；
- client 必须修复 conflict 后再 complete；
- 每批成功后可 heartbeat/续租；
- run 过期、被 supersede 或 namespace 不匹配时拒绝写入。
- 同一 run 中相同 `externalId` 通过 run-item 唯一键幂等合并；同 ID 不同内容/revision
  记为 conflict，而不是重复增加 `seenCount`。
- `expectedItemCount` 指 manifest 按 external identity 去重后的数量，不是 JSONL 原始行数；
  reference client 使用临时 SQLite/磁盘哈希账本校验重复 identity 并计算计数，保持正文流式、
  内存有界；无法确定时不得猜测计数。
- 对既有文档的首次 run 写入：
  - `sourceMutationSequence <= snapshotStartSequence` 时可按 snapshot CAS 正常应用；
  - sequence 晚于 begin，但 `sourceRevision` 和全部受管字段与当前状态完全一致时，只新增
    成功 run-item ledger 并计为 seen，不修改业务文档/revision/job；
  - sequence 晚于 begin 且 revision 或受管字段不同，返回
    `CONCURRENT_SOURCE_UPDATE`，不允许旧快照覆盖新状态，并阻断 complete。
- 已经由同一 run 成功写入的 item 只允许精确重放。

每个需要变更文档的 batch item，其 document mutation、`sourceMutationSequence` 分配、
run-item upsert、可选 `lastSeenSyncRunId/generation` 镜像和 run 计数必须在同一个短事务内
提交。exact-current item 只写 ledger/counters。任何一步失败都整项回滚，不能出现
“文档已更新但 ledger 未 seen”或“ledger 已 seen 但文档未更新”。批次仍按项隔离事务，
避免一个冲突回滚整批。

处理 item 前先读取 `(runId, externalId)` ledger：已成功且 fingerprint 相同则直接返回持久化
结果，不递增 namespace sequence、seen count 或创建新 job；fingerprint 不同返回 conflict。
并发首次写仍通过 run-item 唯一键仲裁，失败方重新读取后按上述规则收敛。

#### 封存 run

```http
POST /api/v1/rag/document-sync-runs/{runId}/seal
```

```json
{
  "snapshotRevision": "cms-export:8841",
  "expectedItemCount": 12500,
  "sourceCutEstablishedAt": "2026-08-18T12:00:00Z"
}
```

规则：

- 只有 `ACTIVE` run 可执行新的 seal 状态转换，且 run item 无未决 persistence conflict；
- `expectedItemCount` 是磁盘账本去重后的 identity 数，必须等于成功/unchanged ledger 数；
- `ONLINE_CUT` 必须提供 begin 之后建立的 `sourceCutEstablishedAt`；
- `OFFLINE_MANIFEST` / `EXCLUSIVE_OFFLINE` 的 revision 即使在 begin 时已知，也统一在 seal
  固化，避免 begin 成功响应丢失后出现两套不可重放参数；
- seal 使用 lease token + generation CAS 将 run 变为 `SEALED`，并持久化最终 count/revision；
- run 已为 `SEALED` 时，相同 lease token/generation 和相同 canonical seal payload 的精确
  重放返回持久化的同一结果；不同 seal payload 返回 409；
- seal 后 batch-upsert 一律拒绝；
- preview 和 complete 只接受 `SEALED` run；abort 接受 `ACTIVE/SEALED`。

#### 预览

```http
POST /api/v1/rag/document-sync-runs/{runId}/preview
```

返回：

- seen/expected；
- created/updated/unchanged/conflicted；
- complete 后将 tombstone 的 missing 数量；
- queued/running/failed/fresh/stale embedding 摘要；
- `canComplete` 与阻断原因；
- 绑定 run generation、namespace mutation sequence、seen/conflict、missing 数量和计算时刻
  的短期 `previewConfirmationToken`。

token 是服务端生成的至少 128 bit 随机 nonce，只在 preview response 返回；数据库仅保存
SHA-256 hash、对应 namespace mutation sequence、counters、missing count 和推荐 5 分钟
过期时间，不引入额外签名密钥。后续成功 batch-upsert、普通 external upsert/delete、
abort、supersede 或 generation/lease owner 变化都会使 namespace sequence 或 token 条件失效；
单纯 heartbeat 可保留。preview 是会生成并持久化确认 token 的状态变更操作，因此必须使用
`POST`，响应设置 `Cache-Control: no-store`；同一 run 再次 preview 时原子替换旧 token，
旧 token 立即失效。preview 不返回所有 missing external ID，避免超大响应；提供受限分页
诊断端点。

#### 完成

```http
POST /api/v1/rag/document-sync-runs/{runId}/complete
```

```json
{
  "previewConfirmationToken": "opaque-random-nonce",
  "expectedMissingCount": 37,
  "confirmLargeDeletion": false
}
```

规则：

1. 只有当前 namespace generation/lease owner 可 complete；
2. 必须提交尚未过期且与当前 run counters/missing 集合摘要一致的 preview token；
3. namespace 当前 mutation sequence 必须等于 preview 保存值，且
   `expectedMissingCount` 必须与服务端重算一致；
4. 默认要求唯一成功 external identity 的 `seenCount == expectedItemCount`；
5. 仍有 persistence conflict 时拒绝 complete；
6. missing 以 durable run-item ledger 为真相源：只包括当前 namespace 中不存在该 run
   成功 item、且 `sourceMutationSequence <= snapshotStartSequence` 的 active 文档；
   `lastSeenSyncRunId/generation` 仅是可重建查询优化，不能单独决定删除。run 开始后由
   webhook/其他增量请求更新的对象不会被本 run 误删；
7. `missingPolicy=NONE` 时 complete 只冻结 summary，不 tombstone；`TOMBSTONE` 仅接受
   `ONLINE_CUT` 或已审计确认的 `EXCLUSIVE_OFFLINE`；
8. missing 超过配置阈值或 active namespace 的推荐 20% 时，要求
   `confirmLargeDeletion=true`；`seenCount=0` 且 namespace 原有 active 文档非零时默认拒绝，
   只能通过单独 ADMIN break-glass 运维流程处理；
9. 同一短事务中以 set-based 条件 UPDATE tombstone missing 文档并 `RETURNING` 变更后状态，
   再从返回集插入完整 `DELETE` 版本快照；任一步失败整体回滚；
10. tombstone revision 使用 run 的 `snapshotRevision` 加 server run identity，
   保证 complete 重放稳定；
11. 所有 missing 文档写入 complete 事务取得的同一个 `completeMutationSequence`；
12. 标记 superseded embedding jobs；
13. run 进入 immutable `COMPLETED`；
14. run 已 `COMPLETED` 时，同一 lease token/owner 的 complete 网络重放直接返回持久化 summary，
    不再要求未过期 preview token；不同 owner/token 仍拒绝；
15. 不等待 embedding jobs 完成。

complete 先以 `WHERE mutation_sequence = preview_namespace_sequence` 条件更新 namespace
协调行并取得 `completeMutationSequence`，再用条件
`UPDATE ... RETURNING` 将 run 从 `SEALED` 切到 `COMPLETING`，校验 generation、lease、
owner、seen/conflict。batch item 同样先更新 namespace 行，随后在提交前对 run 做条件 CAS
确认其仍为 `ACTIVE`；若 complete 已取得状态转换，item 整项回滚。随后 complete 在同一事务
执行 missing tombstone 和 `COMPLETED` summary，消除“最后一批与 complete 交错”的窗口。

首版对单 namespace 的 active 文档数设置明确上限，推荐默认 100,000；超过上限拒绝
complete 并要求拆分 namespace，避免一次 set-based tombstone 形成不可控的大事务。
普通 `UPDATE` 的数据库内部短事务锁允许使用，但不能引入应用显式悲观锁。

complete 生成的 tombstone revision 是当前文档状态的 opaque CAS token。后续 snapshot run
可依据新 generation 恢复而不要求 client 预先知道该 token；增量 webhook 若以旧
`expectedSourceRevision` 遇到 409，应先按外部身份 GET 当前状态，再基于来源事实重试。

批量版本快照不能逐条调用“查最大版本号再插入”而扩大事务和竞态窗口。complete 使用
set-based CTE：先对每个 missing document 条件递增 `next_history_version` 和
`document_revision`、写入 tombstone/source revision/complete mutation sequence 并返回
变更后完整状态与已分配编号，再插入完整快照。依赖 document revision/source mutation
sequence、唯一约束和 namespace generation CAS 作为最终仲裁。并发冲突使整个 complete
回滚并可安全重试。

#### 放弃

```http
POST /api/v1/rag/document-sync-runs/{runId}/abort
```

只终止 run，不回滚已经成功的 upsert，也不删除 missing 文档。该语义必须在文档中突出。

### 6.3 并发与无悲观锁设计

建议表：

```text
rag_document_source_namespaces  # V40 创建，V42 扩展 active-run 字段/约束
  collection_id
  source_namespace
  mutation_sequence
  sync_generation
  active_run_id
  row_version

rag_document_sync_runs
  id
  client_run_id
  collection_id
  source_namespace
  generation
  snapshot_revision  # seal 时固化
  snapshot_mode
  missing_policy
  snapshot_start_sequence
  expected_item_count  # seal 时固化
  source_cut_established_at
  seen_count
  conflict_count
  status
  lease_token_hash
  lease_expires_at
  preview_token_hash
  preview_namespace_sequence
  preview_seen_count
  preview_conflict_count
  preview_missing_count
  preview_expires_at
  owner_principal_id
  summary jsonb
  timestamps

rag_document_sync_run_items
  run_id
  external_id
  document_kind
  document_id
  source_revision
  request_fingerprint
  result
  error_code
  first_seen_at
  updated_at

rag_documents
  source_namespace
  last_seen_sync_run_id         # 可选、可重建优化
  last_seen_sync_generation     # 可选、可重建优化
  source_mutation_sequence
```

协调方式：

- active namespace run 使用 partial unique index；
- begin 使用 `INSERT ... ON CONFLICT` 和 namespace row-version CAS；
- heartbeat 使用 run 条件更新；batch/seal/complete 使用状态与 lease CAS，其中
  batch/complete 先更新 namespace 协调行，再使用
  `UPDATE ... WHERE id/status/generation/lease_token/lease_expires_at ... RETURNING`；
- run item 以 `(run_id, external_id)` 唯一，保存请求指纹和结果但不复制正文/payload；
  preview/complete 的 seen/conflict 由这些 durable items 统计；
- complete 的 missing tombstone 使用一条有 scope/generation/snapshot-watermark 条件的
  批量 UPDATE，并写入同一个 `completeMutationSequence`；
- 普通增量 external upsert/delete、run begin 和 run batch 都在 mutation 事务中通过同一
  namespace 协调行分配 `source_mutation_sequence`；complete 的 snapshot-start 条件保护
  并发增量写；
- 不能使用显式行锁或 advisory lock；
- owner principal 和 API Key Collection ACL 必须同时满足。

### 6.4 外部 client 最佳实践

专题文档和 reference client 分两步交付：

- **Batch A**：增量 webhook/CDC 的 identity、CAS、精确重放、tombstone、embedding
  readiness、checkpoint 和错误分类；交付可运行的 `apply-events`。
- **Batch B**：在同一文档和 client 上增加 authoritative snapshot、lease、seal/preview/
  complete、大删除保护和崩溃恢复；交付 `sync-manifest`。

实施后的最终形态应为中英文长青文档，例如：

- `docs/external-document-sync-client-guide.md`
- `docs/external-document-sync-client-guide-zh-CN.md`

必须包含：

1. 如何选择 `collectionKey`、`sourceNamespace`、`externalId`；
2. 如何选择 opaque `sourceRevision`；
3. webhook/CDC 增量模式；
4. 定期 authoritative snapshot 对账模式；
5. sync run tombstone 后增量 webhook 遇到 revision conflict 的 GET/retry 流程；
6. snapshot run 遇到 `CONCURRENT_SOURCE_UPDATE` 时保留实时更新并重新生成快照；
7. 重复和乱序投递；
8. CAS conflict 重新读取来源的算法；
9. 429/5xx 指数退避与 jitter；
10. 网络超时后重放同一请求；
11. ASYNC job 观测与 Collection readiness；
12. embedding failure 不回滚来源文档的含义；
13. tombstone 与 hard delete 的区别；
14. 受限 API Key 和 Collection ACL；
15. namespace 是身份/对账边界而非权限边界；不互信 connector 使用不同 Collection；
16. 日志中不得记录 secret 或完整敏感正文；
17. client 的 shutdown/restart checkpoint；
18. 字段影响矩阵：正文/retrieval text 变化会使旧派生结果立即 stale，metadata/payload/
    Collection-only 变化不触发 embedding，以及如何等待 `searchability/readiness` 收敛。

### 6.5 可运行 reference client

新增一个不依赖业务框架的 Python reference CLI，建议：

```text
examples/external-sync-client/
  README.md
  sync_client.py
  event.schema.json             # Batch A
  sample-events.jsonl           # Batch A
  manifest.schema.json          # Batch B
  sample-manifest.jsonl         # Batch B
```

运行时契约固定为 Python 3.11+，首版只使用标准库
（`argparse/json/sqlite3/urllib/ssl/hashlib/secrets/time`），不要求用户先安装第三方包，
也不读取项目 `.env`。HTTP base URL 和 API Key 只从显式参数与环境变量获取，其中 API Key
禁止通过命令行参数传入，避免出现在 shell history 和进程列表中。

能力：

- Batch A 的 `apply-events` 流式读取 JSONL event；Batch B 的 `sync-manifest` 流式读取
  JSONL manifest；两者都不得把全部正文加载到内存；
- 提供两个清晰入口：`apply-events` 流式消费增量 CDC JSONL（显式
  `UPSERT|TOMBSTONE`、source revision、expected revision），`sync-manifest` 执行
  authoritative begin/batch/seal/preview/complete；两者复用同一身份规范化、重试分类和
  checkpoint 组件；
- 使用权限为 `0600` 的临时 SQLite/磁盘账本做 identity 去重、offset 和结果 checkpoint，
  不用无界内存 Set；
- 可恢复 authoritative run 必须使用可 seek 的普通文件并提供稳定 `manifestId`；checkpoint
  同时保存 canonical path、文件大小、mtime、完整文件 SHA-256、已处理 byte offset、
  line number 和 manifest ID。首次运行先以流式预扫描计算 digest 和去重计数，恢复时重新
  流式计算 digest；任一身份字段不匹配即拒绝继续，不能在变化后的文件上盲目复用 offset；
  `stdin` 只允许 dry-run 或 `missingPolicy=NONE` 的不可恢复增量导入，不能执行
  `tombstone-missing`；
- Batch B 的 begin / batch-upsert / seal / preview / complete；
- bounded retry + jitter；
- 409 分类处理；
- 本地 checkpoint 保存 run/client identity、offset 和 sync lease token，但不保存 API Key；
  文件权限必须为 `0600`，token 不得写入日志；
- 支持 `--dry-run`、`--abort`、`--wait-for-readiness`；
- 默认支持带 `expectedSourceRevision` 的离线 manifest；只有显式
  `--exclusive-source-snapshot --tombstone-missing` 才允许离线 manifest 按 missing 删除，
  并在摘要中警告；预生成 manifest 默认 `missingPolicy=NONE`；
- 默认从环境变量读取 key；
- 输出结构化摘要，不输出全文或 payload；
- 可以直接用于一键 E2E fixture。

reference client 与 API 文档必须共同作为验收对象，不能只写伪代码。

交付顺序固定为：

1. Batch A 创建目录、schema、示例和共享 client 基础设施，并完成 `apply-events` live HTTP
   E2E；此时 `sync-manifest` 不得以未实现占位命令伪装可用。
2. Batch B 在同一 CLI 中增加 `sync-manifest`、SQLite identity ledger、lease/preview token
   持久化和 authoritative snapshot E2E。

## 7. Batch C：基于完整快照的受控恢复

### 7.1 产品目标

- 查看某个历史版本的完整可恢复字段；
- 将历史版本恢复为一个新的当前版本；
- 恢复后自动按字段影响决定是否重新派生索引；
- 外部管理文档的恢复不会绕过来源 revision；
- tombstone 恢复有明确语义；
- 旧版本数据缺字段时不猜测。

### 7.2 快照前提

完整快照 schema 和写入已在 Batch A 的 V40 落地，避免 Batch A/B 期间产生新的不可恢复历史。
Batch C 不新增另一套版本表或补写逻辑，只消费统一 mutation 已记录的快照。

API 返回 `snapshotCompleteness`：

- `FULL`
- `CONTENT_AND_METADATA_ONLY`

不对旧行进行不可靠回填。

### 7.3 恢复 API

本地管理文档：

```http
POST /api/v1/rag/documents/{documentId}/versions/{versionNumber}/restore
```

本地文档请求：

```json
{
  "expectedDocumentRevision": 19,
  "restoreMode": "FULL",
  "embeddingPolicy": "ASYNC"
}
```

外部管理文档不能依赖内部 ID 写入，使用稳定身份路径：

```http
GET  /api/v1/rag/documents/by-external-id/versions
GET  /api/v1/rag/documents/by-external-id/versions/{versionNumber}
POST /api/v1/rag/documents/by-external-id/versions/{versionNumber}/restore
```

三个端点的查询参数都包含 `collectionKey`、`sourceNamespace`、`externalId`；restore 请求体要求：

```json
{
  "expectedSourceRevision": "cms:8841",
  "sourceRevision": "manual-restore:8842",
  "restoreMode": "FULL",
  "embeddingPolicy": "ASYNC"
}
```

规则：

- restore 不是把数据库版本号倒退，而是创建一个新的 `RESTORE` 版本；
- external identity 和 source namespace 永不从历史版本恢复或改写；
- 外部文档必须提供新的 source revision；
- `CONTENT_ONLY` 可用于旧的不完整快照；
- `FULL` 遇到不完整快照返回 409/422，不猜测字段；
- content 变化会使旧派生结果 stale 并调度；
- payload-only restore 不调用 embedding；
- 恢复 disabled/tombstone 状态必须显式 `restoreVisibility=true`，避免误启用。

## 8. Batch D：本地全文索引与远程向量解耦

### 8.1 产品目标

将派生流水线拆成：

```text
document current content
  -> deterministic local chunk generation
  -> fresh chunk/full-text index
  -> per-Embedding-Profile vector generation
```

完成后：

- embedding provider 故障时，当前正文仍能参与关键词/FTS 检索；
- vector 分支独立显示 queued/running/failed/ready；
- chunk 只保存一份，不随 Profile 复制文本；
- Profile 切换只生成向量，不重复分块；
- chunker 版本变化明确使 chunk 与所有依赖向量 stale。

### 8.2 数据模型

建议 `V43__separate_document_chunks_and_vectors.sql`：

```text
rag_document_chunk_sets
  document_id
  content_hash
  chunker_version
  status
  chunk_count
  error
  completed_at

rag_document_chunks
  id
  document_id
  content_hash
  chunker_version
  chunk_index
  chunk_text
  start_pos
  end_pos
  metadata
  search_vector_zh
  search_vector_en

rag_chunk_embeddings
  chunk_id
  embedding_profile_id
  vector column(s)
  created_at
```

唯一性：

- chunk set：`document_id + content_hash + chunker_version`；
- chunk：`document_id + content_hash + chunker_version + chunk_index`；
- vector：`chunk_id + embedding_profile_id`。

### 8.3 实施分段

#### D1：expand + dual write

- 新建 chunk 表和索引；
- 新内容先写 fresh chunk set，再继续写现有 `rag_embeddings`；
- 旧检索仍读原表；
- backfill 只选择 freshness 匹配的当前内容。

#### D2：全文读取切换

- pg_trgm/English/Jieba 改读 `rag_document_chunks`；
- freshness 要求 chunk set hash/version 与当前文档匹配；
- vector 仍读旧表；
- hybrid 可返回 `KEYWORD_ONLY`。

#### D3：vector 读取切换

- vector 改读 `rag_chunk_embeddings`；
- commit guard 同时验证 chunk set；
- Profile 级 embedding state 引用 chunker version。

#### D4：兼容清理

- 停止 dual write；
- 保留旧列一个发布窗口；
- 数据和回滚验证完成后再单独迁移删除，不在本批直接 drop。

### 8.4 查询行为

- hybrid 的全文和向量分支分别报告 readiness；
- vector 不可用但全文可用时，不返回“完全无结果”，诊断标记降级；
- `useHybridSearch=false` 的纯 vector 请求仍只接受 fresh vector；
- minScore 和融合逻辑不因表拆分而偷偷改变；
- citation/source 仍引用稳定 document/chunk identity。

## 9. 次优先能力与明确延后

### 9.1 后续再做

完成 Batch A-B 后，再重新排序：

- bounded evidence context 与 citation/source fidelity；
- authenticated Chat-turn feedback -> evaluation case；
- managed end-to-end Chat answer quality suites；
- embedding in-memory cache identity/metrics 修正；
- 文档派生 retention/物理垃圾回收。

这些仍有价值，但当前不应排在文档 CRUD 和来源同步闭环之前。

### 9.2 明确不进入本轮

- API Key 计费、配额、轮换治理；
- XML/Office 专用 payload 或转换器；
- 固定默认 Collection；
- GraphRAG、多模态和通用 connector 平台；
- 每 Collection 独立 embedding 模型；
- `EACH_COLLECTION` 覆盖召回；
- 自研替代 Spring AI 的 Chat Advisor、Tool Calling 或 Memory 框架；
- 把 PDF 转换产物与 RAG 文档强行做同事务删除。

## 10. 实施顺序与提交边界

### Phase 0：基线和验收矩阵

1. 新建实施进度文档。
2. 固定当前 V1-V39 migration baseline。
3. 一次性列出测试矩阵，禁止进入 review 后零碎补测试。
4. 补一条 PostgreSQL 事实测试：content hash 变化后旧 vector/full-text 都不可见。
5. 固定 global content-hash dedup 的当前行为回归，作为兼容变更证据。

### Batch A

1. V40 expand 和存量 job 迁移。
2. API DTO 和错误码。
3. `DocumentMutationService` 与 impact classifier。
4. local PATCH/disable/restore。
5. external/JSON revision 和 tombstone 收敛。
6. job supersede/cancel 与 reconciliation。
7. lifecycle read model。
8. WebUI 文档编辑、禁用/恢复、明确索引状态。
9. 增量 external client guide 和 `apply-events` reference client。
10. A1 compatibility 验证与观察窗口。
11. V41 contract，随后开启非 default namespace。
12. Batch A 一键验证。
13. 中英文长青文档。

### Batch B

1. V42 sync-run schema，并扩展 V40 namespace 协调表的 active-run 字段/约束。
2. begin/batch/seal/preview/complete/abort。
3. namespace/generation/lease CAS。
4. 将 reference client 扩展为 `sync-manifest`。
5. 大批量和崩溃恢复测试。
6. Batch B 一键验证。
7. client guide 中英文长青文档。

### Batch C

1. restore service/API。
2. WebUI 版本详情和恢复确认。
3. restore + embedding 联动测试。
4. 文档和一键验证。

### Batch D

严格按 D1-D4 分提交；每个读路径切换都需要独立 rollback 开关，不能一次完成所有表替换。

## 11. 验收测试矩阵

### 11.1 Batch A 后端

真实 PostgreSQL 集成测试至少覆盖：

1. create + SYNC -> fresh/READY；
2. create + ASYNC -> job 与文档同事务；
3. jobs disabled + SYNC/ASYNC -> 整体回滚；
4. content update -> 旧 vector 和旧全文立即不可见；
5. provider 失败 -> 新内容持久化、旧内容不返回、状态 FAILED；
6. retry -> 当前 hash 成功变 READY；
7. metadata-only update -> 不调用 provider，filter 立即看到新值；
8. JSON payload-only update -> 不调用 provider，payload filter 立即看到新值；
9. Collection move -> 无重嵌入，旧 scope 不可见、新 scope 可见；
10. disable/tombstone -> 立即不可检索；
11. restore same content -> 复用 fresh embedding；
12. restore changed content -> 调度新 embedding；
13. stale worker/provider response -> commit 被拒绝；
14. queued old-hash job -> STALE/CANCELLED；
15. hard delete -> 显式删除 legacy embeddings，state/jobs/version 由外键级联；
16. local document revision CAS conflict；
17. external source revision CAS conflict；
18. JSON same revision/different payload conflict；
19. 同内容跨 Collection 不发生越权复用；
20. 精确重放不新增版本/job；
21. embedding 运行时 metadata/payload/Collection-only 更新 -> 结果仍可提交且不产生第二个 job；
22. 相同正文先变更再回退或 force re-embed 并发 -> 只有当前 job generation 可提交；
23. SYNC 请求在提交后、provider 完成前中断 -> worker 接管同一 job 并最终收敛；
24. external-managed 文档按内部 ID hard delete -> 拒绝且原文档/tombstone 状态不变；
25. local hard delete revision 过期 -> 409，当前文档和派生记录不变；
26. 同 Collection 不同 namespace 可复用 external ID，GET/update/delete/JSON/evaluation
    各自只命中目标身份；
27. 旧 evaluation suite/Collection import 未带 namespace -> 稳定映射到 `default`；
28. force re-embed 失败/取消 -> 旧 fresh 结果继续 READY，同时暴露 maintenance error。
29. metadata-only 更新 -> filter、search result、citation/tool response 都返回当前 metadata；
30. 外部新 revision 更新/删除未带 expected revision -> 严格模式拒绝，exact replay 仍幂等。
31. local create/upload 同 Idempotency-Key 网络重放 -> 返回同一 document/job，不重复创建；
32. 同 Idempotency-Key 改变请求 -> 409；
33. 受限 key 的 LEGACY_GLOBAL 查重 -> 不命中或泄露不可见 Collection 文档；
34. Collection delete 批量 unlink 本地文档 -> 每份文档 revision/完整快照单调增加，
    无 embedding 调用且 scope 立即变化；
35. Collection delete 包含 external-managed 文档 -> 整体拒绝且不部分 unlink；
36. Collection clone -> 新文档无 external identity，版本完整，embedding job 可恢复；
37. 版本快照、document mutation、job enqueue 任一步失败 -> 整体回滚；
38. local PATCH 区分省略与显式 null：可清空 source/metadata，非 ADMIN 不能解除 Collection；
39. local PATCH 规范化后无变化 -> `UNCHANGED` 且不增加 revision/version/job；
40. 只含 revision 的 PATCH -> `400 EMPTY_PATCH`；
41. 小于 `minChunkSize` 的短非空 TEXT/JSON retrievalText -> 至少一个 chunk 且可检索，
    `minChunkSize` 仅是分块质量目标；只有空白正文被拒绝或产生明确失败；
42. external TEXT/JSON upsert 的可选字段按完整状态替换，精确重放不会意外保留旧
    source/metadata。
43. `apply-events` reference client 对 UPSERT/TOMBSTONE、网络超时重放、409 分类、
    checkpoint 恢复和 API Key 不落盘执行 live HTTP E2E。

### 11.2 Batch A 前端

只使用 DOM、网络和接口断言，不使用截图：

- 编辑正文后发送 PATCH + expected document revision + embedding policy；
- 409 显示冲突并刷新当前版本；
- 文档行显示 READY/INDEXING/FAILED/NOT_REQUESTED/DISABLED；
- metadata-only 编辑不错误提示“正在重嵌入”；
- disable/restore 操作与确认；
- 本地日常删除默认 disable；永久删除要求 revision 和二次确认；
- external-managed 文档不显示本地 patch，提示使用来源同步；
- create/upload 为一次逻辑操作生成并在自动重试中复用同一 Idempotency-Key；
- failed 状态可跳转 embedding job 或重试；
- TypeScript、production build、Mock Playwright 全通过。

### 11.3 Batch B

1. begin 精确重放；
2. 同 namespace active run 唯一；
3. 不同 namespace 并行；
4. 批次部分失败不增加 seen count；
5. client 中途崩溃，未 complete 不 tombstone；
6. expected count 不符拒绝 seal，run 保持 `ACTIVE`；
7. seal 精确重放返回同一结果，不同 payload 返回 409；
8. seal 后拒绝 batch；seal 前拒绝 preview/complete；
9. conflict 未解决拒绝 seal/complete；
10. complete 只 tombstone 本 namespace missing 文档；
11. complete 重放幂等；
12. 旧/过期 generation 不能 seal/complete；
13. lease heartbeat；
14. ACL 收回后 run 失败且不能继续；
15. 12500+ manifest 分页时内存有界；
16. ASYNC embeddings 最终 readiness 收敛；
17. reference client 在网络超时后重放成功；
18. abort 不删除 missing；
19. batch item 在文档写入与 run-item 记账之间失败 -> 整项回滚；
20. complete 与最后一批并发 -> 只有一种顺序生效，不出现已写文档被误判 missing；
21. stale preview token / expectedMissingCount 不匹配 -> complete 拒绝且不 tombstone；
22. 空快照或高比例 missing -> 默认拒绝或要求显式大删除确认；
23. preview 后 webhook 更新但 missing 数量不变 -> namespace sequence 不匹配，complete 拒绝；
24. begin/batch 精确重放 -> 不递增 namespace sequence、generation、seen count 或 job 数；
25. 再次 `POST preview` 原子替换旧 token，旧 token 不能 complete，响应包含
    `Cache-Control: no-store`；
26. complete 与 webhook 并发 -> webhook 要么被水位保护，要么观察到 complete 后 revision。

### 11.4 Batch C

- FULL/CONTENT_ONLY；
- 本地与外部 restore CAS；
- JSON payload restore；
- tombstone visibility 显式恢复；
- incomplete legacy snapshot；
- restore 新版本单调增加；
- restore 后旧派生结果不泄漏。

### 11.5 Batch D

- embedding provider 故障但关键词检索当前内容成功；
- vector-only 不返回 stale；
- hybrid 标记 keyword-only degradation；
- chunker version 改变使 chunk/vector stale；
- 多 Profile 共享 chunk；
- dual-write 和 backfill 可重放；
- 读开关回滚；
- 老表与新表结果集/排序允许误差边界明确。

## 12. 一键验证脚本

建议按批新增：

```text
scripts/verify-document-lifecycle.sh
scripts/verify-authoritative-document-sync.sh
scripts/verify-document-version-restore.sh
scripts/verify-derived-index-separation.sh
scripts/verify-document-data-plane.sh
```

聚合脚本顺序：

1. 静态禁悲观锁；
2. focused unit/contract tests；
3. 隔离 PostgreSQL 从空库迁移到最新版本；
4. 本批 PostgreSQL 集成测试；
5. `mvn clean compile test-compile`；
6. 全量后端测试；
7. WebUI `tsc` + production build + Mock Playwright；
8. reference client live HTTP E2E；
9. 文档门禁；
10. `git diff --check`。

“一键”不得隐含人工预启动 dev 服务。确定性 HTTP 验收由专用
`@SpringBootTest(webEnvironment = RANDOM_PORT)` + Testcontainers PostgreSQL 测试夹具启动
隔离应用，并以子进程运行 `examples/external-sync-client/sync_client.py`；测试负责传入临时
base URL、临时 API Key、manifest 和权限为 `0600` 的 checkpoint 目录，结束时销毁数据库与
本地状态。Docker/Testcontainers 不可用时，沿用现有专项迁移测试的显式
`*_IT_JDBC_URL` + `*_IT_CLEAN_CONFIRM=YES` 模式连接一次性数据库，绝不能自动复用开发库。

聚合脚本提供两个明确模式：

- 默认模式：使用可控 embedding stub，完整覆盖并发、失败、重试、CAS、WebUI 和 reference
  client live HTTP E2E，可稳定进入 CI；
- `--with-real-embedding`：在默认门禁之后读取当前 shell/`.env` 的 embedding 配置，再运行
  至少一条 create/update -> provider -> fresh search 的真实验收。实施完成和发布验收必须
  留存至少一次该模式通过的记录；外部 provider 不可用时只能记为明确失败或跳过，不能把
  stub 结果冒充真实 provider 通过。

脚本产物写入 `.verification/document-data-plane/<run-id>/`，只保存状态、ID、计数和脱敏错误，
不保存 API Key、完整正文或 JSON payload。

真实 embedding 测试允许读取 `.env`，但脚本不能打印 key。Batch A 的 fresh/READY
验收必须至少有一次真实 embedding provider 调用；其他并发、失败和回滚场景使用可控 stub。

## 13. 可观测性与运营

指标建议：

- `rag.document.mutation{operation,result,document_type}`
- `rag.document.derivation{stage,status,policy}`
- `rag.document.searchability{state}`
- `rag.document.sync.run{status}`
- `rag.document.sync.items{result}`
- `rag.document.sync.missing`
- `rag.document.version.restore{result}`
- `rag.document.index.keyword_only`

日志只记录：

- document ID / external identity 的 hash 或安全短标识；
- collection key/source namespace；
- revision 的脱敏/截断值；
- content hash 前缀；
- job/run ID；
- 状态和 error code。

不记录完整 content、payload、Prompt 或 secret。

## 14. 回滚与发布策略

### Batch A

- 新端点和字段可通过配置关闭；
- 旧 create/upsert 路径保留；
- lifecycle read model 是增量响应；
- V40 只 expand，不 drop 旧列/旧索引，source namespace 限定为 `default`；
- 回滚到 V39 应用只允许维护/只读窗口，必须冻结文档写入和 worker；旧应用不能继续维护
  新生命周期不变量；
- V41 contract 后才启用非 default namespace；此后不能回滚到不理解三元身份/generation
  的旧应用，但可关闭新 feature 并保持 schema 回滚当前应用版本。

### Batch B

- sync run 默认 feature flag 关闭；
- 关闭后已有单条 external upsert 不受影响；
- 已开始 run 可 abort/过期；
- complete 必须在 flag 开启且 run 有效时执行。

### Batch C

- restore 默认可配置关闭；
- 版本读取继续可用；
- 新 snapshot 列不影响旧应用。

### Batch D

- dual-write/read switch 分离；
- 全文和 vector 读路径分别有开关；
- 旧表至少保留一个发布窗口；
- 禁止同一迁移直接删除旧 chunk/vector 数据。

## 15. 实施完成后的长青文档更新

规划/进度文档可以只写中文；实现完成后，下列长青文档必须中英文同步：

- `docs/project-context*.md`：稳定生命周期、不变量和推荐入口；
- `docs/architecture*.md`：mutation/derivation/sync-run 状态机；
- `docs/rest-api*.md`：全部端点、状态和冲突语义；
- `docs/configuration*.md`：feature flags、lease、batch、默认 embedding policy；
- `docs/testing-guide*.md`：专项和真实 HTTP E2E；
- `docs/developer-reference*.md`：一键命令；
- `docs/file-management-and-pdf-rag*.md`：upload/PDF 稳定身份边界；
- 新增 `docs/external-document-sync-client-guide*.md`；
- `docs/index*.md` 增加 client guide 导航；
- `AGENTS.md` 只有在迁移版本或硬性规则变化时做短入口更新。

## 16. 完成定义

本规划的“下一批文档数据面”只有满足以下条件才算完成：

1. create/update/delete/restore 的索引影响矩阵由统一服务执行，而不是只写在文档里；
2. 正文更新后旧向量和旧全文绝不返回；
3. 非文本更新不会产生多余 embedding 调用；
4. JSON record 具备 revision/CAS/tombstone；
5. 外部来源可通过 namespace + sync run 安全完成全量对账；
6. reference client 可实际运行并通过崩溃/重试 E2E；
7. 版本恢复产生新版本并正确连带索引；
8. Batch D 完成后，embedding 故障时当前内容仍可关键词检索；
9. 所有相关路径执行 Collection ACL；
10. 无显式悲观锁；
11. 一键脚本、真实 PostgreSQL、clean compile、全量后端、前端 DOM/网络验收和文档门禁通过；
12. 中英文长青文档同步。

## 17. 推荐实施范围

为了控制风险，下一次实际实施建议先批准：

```text
Phase 0 + Batch A
```

Batch A 已能独立解决用户最关心的文档 CRUD、索引连带更新、外部三元身份、增量同步最佳
实践和可运行 client，不需要把 authoritative snapshot 的大事务与状态机同时压进第一次实施。

Batch A 通过完整硬门禁并观察一个兼容窗口后，再实施 Batch B；Batch C 可紧随其后。
Batch D 涉及存储模型 expand/dual-write/read switch，应作为独立实施任务，不能与前三批混在
一个无法回滚的大提交中。
