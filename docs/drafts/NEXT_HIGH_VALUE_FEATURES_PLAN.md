# Sync Run 持久化 item receipt 与游标状态查询实施规划

> **状态**：规划审查 `3/3` 通过，实施与验收中
>
> **规划日期**：2026-08-26
>
> **规划基线**：`main` / `origin/main` @ `67f69bfe`；Spring Boot `3.5.16`；
> Spring AI `1.1.8`；Java `21`；Flyway `V1-V50`
>
> **规划工作区**：
> `/Users/yangjiefeng/.hermes/workspace/spring-ai-rag-main-delivery`
>
> 本文是当前仓库的单语过程文档。它描述通用大规模同步 Client 的可恢复状态需求，不依赖
> 任何外部项目的名称、领域模型、私有协议或部署背景。实施完成后，稳定事实提升到双语
> 长青文档，本文与进度账本归档。

## 1. 执行摘要

本轮为已有 Document Sync Run 增加**授权后的持久化 item receipt/status 查询**：

```text
GET /api/v1/rag/document-sync-runs/{runId}/items
```

当前 Sync Run 已经把每个 manifest item 的外部身份、请求 fingerprint、当前状态、错误、
document ID 和最近处理时间持久化到 `rag_document_sync_run_items`。但这些逐项事实只在
`batch-upsert` 的同步响应中返回；响应丢失、Client 重启或 run 包含很多 batch 时，公开 API
只能查询 run 级状态，不能分页列出当前失败项、确认某项是否已经落地，或形成可恢复的
运营对账。

新增能力直接读取现有权威 ledger，不复制正文、JSONB payload、lease token 或 credential：

1. 按 run + Collection binding 授权；
2. 按当前 item 状态可选过滤；
3. 使用 `seen_at + external_id` 的 opaque keyset cursor 有界分页；
4. 返回当前 item 状态汇总，明确区别于已有 run 级累计处理结果计数；
5. 对 terminal run 提供稳定遍历；active run 只提供 eventually-consistent 观察，并要求
   调用方在终态后从头复扫，避免把分页 cursor 误当作跨事务快照；
6. 在 runtime capability contract 中声明该可选能力；
7. 通过 V51 索引、真实 PostgreSQL、真实 HTTP、权限合同和完整构建门槛验证。

这比新建另一套通用 mutation operation 表风险更低：Sync Run 已经具备稳定 run identity、
Collection/namespace binding、item fingerprint、失败重试和 durable 状态，本轮只补齐读取
控制面，不改变 mutation、CAS、tombstone、embedding 或 lease 语义。

## 2. 为什么这是下一批高价值功能

### 2.1 已交付能力排除了旧候选

截至基线 `67f69bfe`，以下生产接入缺口已经交付：

- stable principal、versioned credential、即时吊销和共享 PostgreSQL quota；
- `RAG_READ` / `RAG_WRITE` 操作级强制授权；
- 按职责创建 restricted principal；
- API principal 可选 `Idempotency-Key` provisioning；
- `/integration-capabilities` 机器可读运行时合同；
- JSON Record 的外部三元身份、opaque revision、CAS、exact replay、tombstone/restore；
- bounded Document Sync Run、item fingerprint ledger、失败重试和 missing preview/complete。

因此，继续重复设计授权、provisioning 或 capability discovery 不再是高价值工作。真正剩余的
可恢复性缺口是：已有 durable item ledger 没有公开、授权、可扩展的读取 API。

### 2.2 当前失败场景

```text
client -> POST run/{id}/batch-upsert (100 items)
server -> commits item states
network X response lost
client restarts
```

Client 当前只有三种选择：

1. 重放整批。精确 fingerprint 可以保证 mutation 幂等，但会增加请求、日志和数据库工作，
   且无法直接列出 run 中其他历史失败项；
2. 逐个读取业务 Document。它需要重新拼装外部身份，并且 Document 当前状态不等于该 run
   item 的处理状态；
3. 只读 run 级 `failedCount`。该字段是累计处理结果计数，失败项重试成功后不会倒减，不能
   代表 ledger 中“当前仍失败”的 item 数量。

这些做法都无法形成可靠的批量 receipt/status 工作流。

### 2.3 为什么优先扩展 Sync Run

- V42 已有 durable run/item schema 和安全边界；
- item ledger 不保存正文或 payload，符合低敏 receipt 目标；
- run 已绑定 `collection_id + source_namespace`，可复用现有 ACL 与 anti-enumeration；
- item 主键 `(run_id, external_id)` 和 `seen_at` 足以为 terminal run 建立稳定 keyset
  cursor；active run 的限制单独写入并发合同；
- `batch-upsert` 已有 exact replay 和 FAILED retry，不需要再引入第二套幂等状态机；
- 大规模增量投递仍可继续使用普通 JSON Record upsert；只有需要完整 manifest、逐项 receipt
  或 missing reconciliation 的工作流才启用 Sync Run。

## 3. 当前代码、schema 与契约事实

### 3.1 主链

```text
DocumentSyncRunController
  -> DocumentSyncRunService
     -> rag_document_sync_runs
     -> rag_document_sync_run_items
     -> DocumentMutationService
     -> CollectionIdentityResolver + ApiKeyCollectionAccess
```

代码锚点：

- `spring-ai-rag-core/src/main/java/com/springairag/core/controller/DocumentSyncRunController.java`
- `spring-ai-rag-core/src/main/java/com/springairag/core/service/DocumentSyncRunService.java`
- `spring-ai-rag-core/src/main/resources/db/migration/V42__document_sync_runs.sql`
- `spring-ai-rag-api/src/main/java/com/springairag/api/dto/DocumentSyncRunItemResponse.java`
- `scripts/verify-document-sync-runs.sh`

近距离长青入口：

- [REST API：权威来源全量快照对账](../rest-api-zh-CN.md#权威来源全量快照对账)
- [外部文档同步 Client 指南](../external-document-sync-client-guide-zh-CN.md)
- [配置参考：document lifecycle](../configuration-zh-CN.md#文档生命周期配置)
- [测试指南](../testing-guide-zh-CN.md)
- [交付工作流](../delivery-workflow-zh-CN.md)

### 3.2 V42 item ledger 已保存的事实

`rag_document_sync_run_items` 当前列：

| 列 | 语义 |
|---|---|
| `run_id` | 所属 Sync Run |
| `external_id` | run namespace 内的外部身份；与 `run_id` 组成主键 |
| `document_kind` | `TEXT` / `JSON_RECORD` |
| `item_fingerprint` | canonical item 请求摘要 |
| `source_revision` | 本次 item 的 opaque 来源 revision |
| `document_id` | 成功或跳过后可关联的当前 Document |
| `status` | `APPLIED` / `UNCHANGED` / `SKIPPED_NEWER_MUTATION` / `FAILED` |
| `error_code` / `error_message` | error code 与最多 500 字符的失败信息；现有写路径只截断，历史行不保证已脱敏 |
| `seen_at` | 最近一次处理或重试落账时间 |

表中没有 content、retrieval text、JSONB payload、metadata、lease 明文、credential 或
provider secret。本轮不得增加这些字段。

### 3.3 当前计数语义

`rag_document_sync_runs.applied_count`、`unchanged_count`、`skipped_count`、
`failed_count` 在每次 item 进入相应结果时递增。FAILED item 之后成功重试时，历史
`failed_count` 不倒减，新的成功结果继续递增。因此这些字段是**累计处理结果计数**，不是
当前 ledger 状态分布。

本轮不重定义或回写这些既有字段，避免破坏历史响应。新 item page 单独返回从 item ledger
实时聚合的 `currentSummary`，明确表示当前每个唯一 item 的最终可见状态。

### 3.4 当前授权和 feature flag

- `rag.document-lifecycle.sync-runs-enabled=false` 默认关闭全部 Sync Run API；
- run 的 begin/batch/preview/complete/abort 使用 Collection ACL，每次操作重新授权；
- `GET` 路径由中央 `ApiCapabilityFilter` 自动要求 `RAG_READ`；
- restricted caller 对未授权 Collection 统一得到 `403`；授权 Collection 下 run ID 或
  namespace 不匹配返回 `404`；
- status 查询不需要 `X-RAG-Sync-Lease`。lease 只证明 active run mutation 所有权，不应成为
  读取 receipt 的长期 secret；
- `/integration-capabilities` 已能反映 `documentSyncRuns` feature flag，但尚未声明 item
  receipt/status 查询能力。

## 4. 目标、非目标与冻结决策

### 4.1 目标

1. 新增授权的 Sync Run item page API，支持 terminal 和 active run。
2. 支持可选 `status` 过滤，首版枚举与 ledger 当前四种状态完全一致。
3. 使用 opaque keyset cursor，不使用 OFFSET 扫描大 run。
4. 返回当前唯一 item 状态汇总和 bounded page。
5. 保持响应低敏：不返回 fingerprint、正文、payload、metadata、lease/hash 或 credential。
6. 明确 active run 的并发分页语义和 terminal run 的稳定语义。
7. 通过 runtime capability endpoint 暴露 feature availability。
8. 用 V51 索引保证按 run、status、cursor 的查询路径可预测。
9. 对新写入、即时 batch 响应和 durable receipt 统一执行敏感信息 masking；读取时再次
   masking，覆盖 V42 以来可能存在的历史未脱敏行。
10. 更新现有一键 Sync Run 验收脚本，而不是新建平行脚本。

### 4.2 非目标

- 不新增通用异步 mutation queue、outbox、webhook 或 callback；
- 不为普通 `json-records/batch-upsert` 新建另一套 operation/receipt ledger；
- 不保存或重放 batch 原始响应；
- 不改变 Sync Run begin、lease、item fingerprint、mutation、missing preview 或 complete；
- 不把 receipt 状态等同于 embedding readiness；embedding 继续使用 lifecycle/readiness/job
  API；
- 不允许按 run ID 绕过 Collection/namespace binding；
- 不允许客户端通过 cursor 注入 SQL、跨 status 复用内部位置或读取任意 external ID；
- 不在 WebUI 增加 Sync Run 页面；该能力面向后端同步 Client 和运维自动化；
- 不引入显式悲观锁、`SKIP LOCKED` 或 advisory lock；
- 不在 metrics tag、日志摘要或验证 manifest 中写 externalId。

### 4.3 推荐默认与可逆边界

| 事项 | 冻结默认 | 理由与可逆边界 |
|---|---|---|
| endpoint | `GET /document-sync-runs/{runId}/items` | 是既有 run 资源的只读子资源 |
| binding 参数 | 必填 `collectionKey`，`sourceNamespace` 默认 `default` | 与既有 `GET /{runId}` 一致，阻止裸 run ID 枚举 |
| 状态过滤 | 可选单个 `status` | 首版保持查询与索引简单；未来可增加多值过滤 |
| page size | `limit=100`，范围 `1..200` | 足以覆盖当前 batch 上限并保持响应/SQL 有界 |
| cursor | URL-safe Base64、无 padding、版本化 JSON payload | 绑定 run/status，隐藏内部结构但不提供加密；不得承载 secret，未来可升级版本 |
| 排序 | `seen_at ASC, external_id ASC` | 对 terminal run 稳定；active run 中把通常的重试移动到后续位置 |
| active scan | eventually consistent，可能重复或遗漏并发变化 | 仅用于观察；Client 必须在 terminal 后无 cursor 从头复扫 |
| summary | 每次从当前 ledger `GROUP BY status` | 与累计 run counters 分离，保证当前失败项可判断 |
| retention | 沿用 Sync Run 现有生命周期 | 本轮不引入自动删除历史 run，后续单独规划 |
| feature flag | 复用 `sync-runs-enabled` | receipt 不应在主功能关闭时单独暴露 |

## 5. HTTP 契约

### 5.1 请求

```http
GET /api/v1/rag/document-sync-runs/{runId}/items
    ?collectionKey=customer-42:records:v1
    &sourceNamespace=default
    &status=FAILED
    &limit=100
    &cursor=<opaque>
X-API-Key: <business credential>
```

参数：

| 参数 | 规则 |
|---|---|
| `runId` | UUID path parameter |
| `collectionKey` | 必填，1-128 visible ASCII；按当前 principal ACL 解析 |
| `sourceNamespace` | 可选，默认 `default`；沿用现有 namespace 规则 |
| `status` | 可选，四种 `DocumentSyncItemStatus` |
| `limit` | 默认 100，范围 1-200 |
| `cursor` | 可选，最多 1024 字符；非法、版本不支持或与 status filter 不匹配返回 400 |

状态码：

- `200`：返回 page；
- `400`：参数或 cursor 非法；
- `401`：未认证；
- `403`：当前 principal 无 `RAG_READ`，或 Collection 不在 allow-list；
- `404`：授权 Collection 下没有该 run，或 namespace 不匹配；
- `503 SYNC_RUNS_DISABLED`：feature flag 关闭。

响应必须带 `Cache-Control: no-store`，因为 error 和 external identity 可能包含业务信息。

### 5.2 响应

```json
{
  "runId": "2e3be660-4c08-4d07-9607-7ccca4c0ae4e",
  "runStatus": "ACTIVE",
  "statusFilter": "FAILED",
  "items": [
    {
      "externalId": "record-42",
      "documentKind": "JSON_RECORD",
      "status": "FAILED",
      "documentId": null,
      "sourceRevision": "opaque-r7",
      "errorCode": "BAD_REQUEST",
      "error": "jsonbPayload is required",
      "seenAt": "2026-08-26T13:55:00Z"
    }
  ],
  "currentSummary": {
    "total": 101,
    "applied": 96,
    "unchanged": 2,
    "skippedNewerMutation": 1,
    "failed": 2
  },
  "limit": 100,
  "hasMore": false,
  "nextCursor": null
}
```

约束：

- `items` 只来自指定 run；
- `statusFilter` 显式返回当前过滤条件，未过滤时为 `null`；
- `currentSummary` 始终是 run 全部唯一 item 的当前状态分布，不受当前 page/status filter
  限制；
- `currentSummary` 的 total 与各状态计数使用 JSON integer / Java `long`，不复用 run 表的
  `INTEGER` 累计计数类型；
- `error` 使用 `SensitiveDataMaskingConverter.maskSensitiveData(...)` 后再截断到 500 字符；
  receipt 读取时再次执行相同处理，避免历史行原样暴露；
- 不返回 `itemFingerprint`、lease/hash、正文、payload、metadata 或 embedding provider
  信息；
- `embeddingAction` / `embeddingJobId` 不进入 durable receipt，因为 V42 ledger 没有保存
  它们。Client 根据 `documentId` 使用 document lifecycle、Collection readiness 或
  embedding job API 查询派生状态。

### 5.3 Cursor

服务端 cursor codec 使用 UTF-8 canonical payload：

```json
{
  "v": 1,
  "r": "2e3be660-4c08-4d07-9607-7ccca4c0ae4e",
  "s": "FAILED",
  "t": "2026-08-26T13:55:00Z",
  "e": "record-42"
}
```

使用 Jackson 解析/生成结构化 JSON，再编码为 URL-safe Base64 without padding；禁止使用
分隔符拆分等 ad hoc parser。cursor 是不透明分页位置，不是授权 token：

- opaque 只表示 Client 不应依赖内部结构，不表示内容加密；Base64 payload 可被解码，
  因此 cursor 按业务敏感数据处理；
- 每次请求仍重新执行 principal、Collection、namespace 和 run binding 授权；
- cursor 中 run ID 或 status 与当前请求不一致时返回 400；
- 时间或 externalId 无法解析、长度超限、版本未知时返回 400；
- cursor 原文不写入日志或数据库；
- SQL 使用绑定参数和 row comparison，不拼接 cursor 内容。

repository 根据是否存在 status filter 选择两条固定 SQL，不使用
`(:status IS NULL OR status = :status)`，避免可选 OR 让 PostgreSQL 产生不稳定计划。
未过滤查询：

```sql
WHERE run_id = :runId
  AND (
    :cursorSeenAt IS NULL
    OR (seen_at, external_id) > (:cursorSeenAt, :cursorExternalId)
  )
ORDER BY seen_at, external_id
LIMIT :limitPlusOne
```

过滤查询在同一条件中额外使用 `status = :status`，对应
`(run_id, status, seen_at, external_id)` 索引。两条 SQL 都只使用绑定参数。

读取 `limit + 1` 行判断 `hasMore`。返回的 `nextCursor` 指向本页最后一条实际返回 item。

### 5.4 并发语义

- terminal run 的 item 不再被 batch retry 修改，完整 cursor scan 稳定且每项一次；
- active run 中，FAILED item 重试会更新 `seen_at`，通常会在后续 page 再次出现；
- 但 `CURRENT_TIMESTAMP` 不能表达事务提交顺序，并发 insert/update 可能在跨页时产生重复或
  遗漏，因此 active scan 不是 snapshot，也不承诺 at-least-once；
- Client 在 active run 上只能把 page 当作观察结果，并按 `externalId` 去重；
- 若 Client 需要最终稳定清单，应等待 run 进入 `COMPLETED`、`ABORTED` 或 `EXPIRED` 后从
  无 cursor 起点重新扫描；
- 本轮不持有长事务 snapshot，也不创建服务端 cursor session。

## 6. 数据库与实现设计

### 6.1 V51

新增 `V51__document_sync_run_item_receipt_indexes.sql`：

```sql
CREATE INDEX idx_rag_sync_run_item_cursor
    ON rag_document_sync_run_items(run_id, seen_at, external_id);

CREATE INDEX idx_rag_sync_run_item_status_cursor
    ON rag_document_sync_run_items(run_id, status, seen_at, external_id);
```

V51 只新增索引：

- 不改写 V42；
- 不增加正文或敏感字段；
- 不回填表；
- 对已有 run 立即可用；
- 应使用普通事务内 `CREATE INDEX`，保持 Flyway 默认语义；不使用
  `CREATE INDEX CONCURRENTLY` 破坏事务假设；
- 普通 `CREATE INDEX` 会在构建期间阻塞该 ledger 的写入。发布前应统计 item 表规模并安排
  维护窗口，部署时观察 PostgreSQL lock wait 与 Flyway 日志；若无法获得锁或构建超出窗口，
  让迁移失败并保留旧应用，不绕过 Flyway 手工标记成功。

### 6.2 DTO

在 API 模块新增：

- `DocumentSyncRunItemReceiptResponse`
- `DocumentSyncRunItemPageResponse`
- `DocumentSyncRunItemCurrentSummary`

`DocumentSyncRunItemResponse` 继续表示同步 `batch-upsert` 的即时结果，不复用为 durable
receipt，避免给历史 ledger 伪造 `embeddingAction=NONE` / `embeddingJobId=null` 的含义。

### 6.3 Service

`DocumentSyncRunService.listItems(...)`：

1. `requireEnabled()`；
2. 校验公开的 limit/status 参数；
3. `requireReadableCollection(collectionKey)`；
4. `requireRun(runId, collectionId, namespace)`；
5. 解码 cursor，并验证 version、run ID、status、时间与 externalId；因此未授权 Collection
   或错误 run binding 不会因 cursor 内容不同而从 `403/404` 变成 `400`；
6. 查询当前状态 summary；
7. 按可选 status + keyset cursor 读取 `limit + 1`；
8. 对历史 `error_message` 再次 masking + 截断，构建低敏 item receipt 和 next cursor；
9. 返回 run 当前 status 与 page metadata。

同一 service 内把现有错误写入和即时 `batch-upsert` response 收口到一个
`sanitizeError(...)` helper：先调用 `SensitiveDataMaskingConverter.maskSensitiveData(...)`，
再按 500 字符上限截断。这样新 ledger 行与即时响应不再产生新的未脱敏值；read-time masking
负责兼容既有行，不依赖破坏性回填。

为了避免继续扩大单个 service 的 SQL 体积，可新增包内
`DocumentSyncRunItemReceiptRepository`，使用 `JdbcTemplate` 封装：

- `currentSummary(runId)`；
- `page(runId, status, cursor, limitPlusOne)`；
- row mapper。

repository 不执行授权；授权与 run binding 保持在 service。
repository 为 filtered/unfiltered page 使用两条固定 SQL，分别匹配 V51 的 status cursor
索引和普通 cursor 索引。

### 6.4 Runtime capability

`IntegrationCapabilitiesResponse.OptionalFeatures` 增加
`documentSyncRunItemReceipts`：

- 保留现有双参数 constructor，内部默认新字段与 `documentSyncRuns` 一致，降低 Java
  source/binary 兼容风险；
- catalog 在 `sync-runs-enabled=true` 时返回 `true`；
- runtime protocol 继续报告 `1.0`：该字段位于 optional feature catalog，属于旧 Client
  必须忽略的 additive JSON 字段；同步更新双语 Client 指南，明确同一协议版本内允许新增
  optional field；
- 删除字段、收紧旧字段或改变既有字段语义时不能借 additive 规则掩盖，必须按 protocol
  兼容策略升级版本并重新验收；
- capability endpoint 自身仍不暴露 run、Collection 或 item 数据。

### 6.5 Controller

`DocumentSyncRunController` 新增 `GET /{runId}/items`：

- 使用 validation annotation 限制参数；
- `Cache-Control: no-store`；
- 不接收 lease header；
- OpenAPI 明确 active/terminal cursor 语义和 400/401/403/404/503；
- GET 已由中央 capability classifier 要求 `RAG_READ`，仍增加 classifier 回归断言防止
  后续误改。

## 7. 兼容性、安全与可观测性

### 7.1 兼容性

- 只新增 endpoint、DTO、capability JSON 字段和索引；
- 既有 begin/batch/preview/complete/get/list 响应保持不变；
- V51 可与旧应用共存，旧应用忽略新索引；
- 不改变 API 版本 `1.0.0`；runtime integration protocol 保持 `1.0`。新增 optional JSON
  字段不改变既有字段含义，旧 Client 必须忽略未知 optional field；
- rollback 应保留 V51 索引，不执行破坏性 schema downgrade。

### 7.2 安全

- Collection ACL 在读取 run 前执行；
- restricted caller 的未知/未授权 key 继续 `403`，不泄露 run 是否存在；
- Collection 已授权但 run ID/namespace 不匹配返回统一 `404`；
- cursor 解码在上述 Collection/run binding 之后执行；未授权或错误 binding 不因 malformed
  cursor 获得差异化错误；
- endpoint 需要 `RAG_READ`，不需要 `RAG_WRITE`；
- cursor 不是身份凭证，不能替代每次授权；
- cursor 不含 credential、lease、fingerprint、payload 或其他 secret；尽管经过 Base64，
  仍按可解码的业务敏感数据处理；
- response `no-store`；
- 日志、metrics 和验证 summary 不记录 cursor、externalId、error 明文、payload 或 credential；
- SQL 全部参数绑定；
- error 在新写入、即时 batch response 和 receipt 读取三个边界都使用同一 masking +
  truncate 规则；历史未脱敏 ledger 行不能绕过 read-time masking。

### 7.3 可观测性

只增加低基数指标：

- `rag.document-sync-runs.items` timer；
- outcome 依赖现有 HTTP status metrics；
- 可选统计 page size、hasMore、filtered/unfiltered，但不能把 run ID、Collection key、
  principal ID、externalId、cursor 或 error code 作为 tag。

## 8. 文件级实施顺序

### Slice A：公共契约与 cursor

1. 新增 receipt/page/summary DTO。
2. 新增 package-private cursor value/codec，覆盖 round-trip、非法输入、status mismatch、
   run mismatch、长度和 UTC 时间；使用 Jackson，不手写 delimiter parser。
3. 扩展 runtime capability DTO/catalog 和测试：覆盖新字段序列化、旧双参数 constructor
   以及 protocol `1.0` 保持不变。

### Slice B：数据库读取主链

1. 新增 V51 两个索引。
2. 新增 receipt repository。
3. `DocumentSyncRunService` 增加统一错误脱敏和授权后的 listItems。
4. Controller 增加 GET endpoint、no-store 和 OpenAPI。
5. 更新 capability filter 回归测试。

### Slice C：验收

1. 扩展 `DocumentSyncRunsPostgresIntegrationTest`：
   - V1→V51；
   - 索引存在；
   - current summary；
   - status filter；
   - terminal cursor 无重复无遗漏；
   - active retry 的 eventually-consistent 行为，不把 cursor 断言为一致性快照；
   - cursor 参数化、run/status binding，未授权 malformed cursor 不改变 `403/404`；
   - 新错误写入和历史 error receipt 均屏蔽 credential-like 原值。
2. 扩展 controller Web 测试：
   - 参数映射；
   - no-store；
   - JSON schema；
   - invalid cursor / disabled / not found 传播。
3. 扩展 `scripts/verify-document-sync-runs.sh`：
   - 从当前 auth-disabled 模式改为启用认证，运行时生成临时 environment-root credential；
   - 用 root 创建目标/非目标 Collection，再创建 restricted read-write principal 执行
     begin/batch/complete，并创建只含 `RAG_READ` 的 restricted principal 查询 receipt；
   - 临时 credential 只存在于进程环境或权限收紧的临时文件，不写命令行、日志、evidence
     或 Git；
   - 响应丢失后的 receipt 查询；
   - `limit=1` 多页 cursor；
   - FAILED filter/current summary；
   - read-only principal 可查；
   - 未授权 Collection `403`；
   - run/namespace mismatch `404`；
   - capability flag；
   - evidence 不写 externalId、credential 或 URL。

### Slice D：长青文档

同步中英文：

- `docs/rest-api*`
- `docs/external-document-sync-client-guide*`
- `docs/configuration*`
- `docs/architecture*`
- `docs/project-context*`
- `docs/testing-guide*`
- `docs/developer-reference*`
- `docs/release-checklist*`
- `docs/TODO*`
- `AGENTS.md`、project-docs Skill 和 migration inventory（V1-V51）

## 9. 一次性验收矩阵

### 9.1 快速专项

```bash
mvn -pl spring-ai-rag-core -am \
  -Dtest=DocumentSyncRunControllerWebTest,IntegrationCapabilityCatalogTest,\
DocumentSyncRunItemCursorTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：

- controller/cursor/capability 全绿；
- 未启动外部 provider；
- 无测试被意外跳过。

### 9.2 真实 PostgreSQL 与 HTTP

```bash
./scripts/verify-document-sync-runs.sh
```

脚本必须从一次性 PostgreSQL 执行 V1→V51，并启动真实 Spring Boot。至少验证：

- migration/index；
- begin + batch；
- 丢失 batch response 后 GET receipt 恢复；
- current summary 与累计 run counter 区别；
- status filter；
- cursor 全遍历；
- active 观察与 terminal 后从头复扫；
- 即时 batch error 和 durable receipt 均不暴露测试注入的 credential-like 原值；
- Collection ACL / RAG_READ；
- no-store；
- response 不含 fingerprint/payload/lease/hash；
- 禁悲观锁。

### 9.3 后端硬门槛

```bash
mvn clean compile test-compile
mvn test
```

服务必须使用 `postgresql` profile 启动并通过 health/readiness。

### 9.4 前端共享契约门槛

即使不改 WebUI 页面，也要验证公共 OpenAPI/DTO 和静态资源构建未回归：

```bash
cd spring-ai-rag-webui
npm run typecheck
npm run test:run
npm run build
npm run check:alignment
npx playwright test e2e/api-key-mvp.spec.ts --project=chromium
```

Playwright 只使用 DOM、可访问状态和网络断言，不使用截图判定。

### 9.5 真实全栈与 provider 回归

Mock 和专项门槛通过后：

1. 在隔离端口运行扩展后的 Sync Run HTTP acceptance；
2. 运行 `./scripts/verify-business-client-readiness.sh`，确认通用 Client 认证、Collection
   binding、真实 API Key Playwright 与共享数据面未回归；
3. 运行 `./scripts/verify-managed-api-principals.sh --with-real-llm`，使用 main worktree
   `.env` 的真实 provider 配置覆盖双实例 principal/credential 生命周期、真实浏览器
   DOM/网络断言和真实 LLM 路径；持续观察脚本 summary 与 backend 日志；
4. 若需单独定位 provider，使用隔离端口运行 `scripts/start-real-e2e-server.sh`，再执行
   `BASE_URL=<isolated-url> ./scripts/real-llm-e2e-smoke.sh`，不得把 credential 写入命令记录；
5. 本功能不改变模型输出；真实 provider 证据用于证明共享认证/filter/application 配置没有
   回归，不能替代 receipt 的 PostgreSQL/HTTP 断言。

### 9.6 文档与安全

```bash
./scripts/verify-project-docs.sh
./scripts/verify-no-pessimistic-locks.sh
bash -n scripts/verify-document-sync-runs.sh
git diff --check
```

补充扫描：

- 新增行密钥扫描；
- 外部项目名称扫描；
- response/schema 不含 `itemFingerprint`、lease/hash、payload；
- evidence summary 不含 externalId、credential、URL 或本机外部路径。

## 10. 规划与实现收敛范围

### 10.1 规划 3/3

1. 需求闭环、自包含性、默认决策和非目标；
2. schema/SQL/cursor、授权、安全、并发和兼容可实施性；
3. 测试矩阵、发布、回滚、文档和 Git/worktree 交付。

发现实质问题立即修正规划并重置计数。连续三轮无修改后才实施。

### 10.2 实现 3/3

基本硬门槛全部通过后，固定范围检查：

1. V51、SQL cursor、active retry、current summary、事务与数据一致性；
2. HTTP/OpenAPI、ACL、RAG_READ、低敏 response 和兼容路径；
3. PostgreSQL/HTTP/provider 证据、文档、发布回滚、密钥与 Git 风险。

只修复影响正确性、成本安全、兼容性或数据一致性的缺陷。任何实质修改重置计数并重跑受影响
门槛，避免在 review 阶段发散增加可选功能。

## 11. 发布、回滚与完成定义

### 发布

1. 规划在 `main` 完成 `3/3`，commit/push 建立 checkpoint；
2. 从最新本地 `main` 创建专用分支和隔离 worktree；
3. 实施期间记录 progress；
4. 基本门槛、实现 `3/3`、最终全量验收；
5. fetch `origin/main`，如有变化 merge 到特性分支；
6. 合并后从头执行最终验证序列；
7. 推送特性分支，合入并推送 `main`；
8. 确认 main/origin/main 和工作区干净，安全移除特性 worktree。

### 回滚

- 应用回滚时保留 V51 索引；
- 旧应用不会调用新 endpoint，也不受索引影响；
- 已依赖 receipt 的 Client 必须在服务版本回退时 fail closed，不能把 run counters 当成
  current item 状态；
- 不删除历史 run/item ledger。

### 完成定义

- endpoint、cursor、summary、ACL 和 capability flag 按本规划实现；
- V1→V51 真实 PostgreSQL 与真实 HTTP acceptance 通过；
- `mvn clean compile test-compile`、全量 Maven、服务启动通过；
- 前端 typecheck/Vitest/build/alignment/核心 Mock Playwright 通过；
- 真实全栈和真实 provider 回归通过；
- 文档/锁/diff/密钥/外部名称检查通过；
- 实现达到连续 `3/3`；
- 双语长青文档同步；
- 特性分支和 main 均推送，隔离 worktree 清理。

## 12. 进度记录

实施进度单独维护在
[NEXT_HIGH_VALUE_FEATURES_PROGRESS.md](NEXT_HIGH_VALUE_FEATURES_PROGRESS.md)。每次关键进展
先更新进度账本，再进入下一阶段；不得记录 raw credential、cursor、externalId、完整 error、
payload、外部项目路径或 `.env` 内容。
