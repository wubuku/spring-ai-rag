# 下一批高价值功能实施规划

> **状态：实施与验收完成，已归档。**
>
> 本批次聚焦外部文档 CRUD 的两个生产缺口：跨 Collection 原子迁移，以及文档主记录与
> 本地关键词/远程向量派生之间的完整性诊断和受控修复。实施过程使用
> [2026-08-21_NEXT_HIGH_VALUE_FEATURES_PROGRESS.md](2026-08-21_NEXT_HIGH_VALUE_FEATURES_PROGRESS.md)
> 记录可恢复进度；完成后先把稳定事实同步到双语长青文档，再归档 plan/progress。

## 1. 执行结论

下一批按以下顺序实施：

1. **P0：外部文档跨 Collection 原子迁移**
   - 在一个数据库事务中把外部文档从源 Collection 移到目标 Collection；
   - 保留内部 `documentId`、版本历史、本地关键词 chunk、向量、embedding state/job；
   - 同时校验源/目标 ACL 和 Collection 生命周期；
   - 使用强制 `Idempotency-Key` 提供网络超时后的精确响应重放；
   - 不重新 chunk/embed，因为正文和派生描述符没有变化。
2. **P1：Collection 派生索引完整性诊断与 preview-first 修复**
   - 同时检查文档主记录、本地关键词派生和当前活动 Embedding Profile 的向量派生；
   - 提供互斥摘要、有限详情、带指纹 preview 和有界 apply；
   - 复用 `KeywordIndexPersistenceService`、`EmbeddingDispatchService` 和持久化 job，
     不直接“修表”伪造 READY；
   - 将 WebUI 的 Embeddings 页面升级为派生索引运营入口。

两项共享 Collection ACL、生命周期 CAS、文档 freshness 和运维验收基础，适合放入同一
规划；实施和提交必须保持 **P0 可单独完成、P1 可独立延期**。若 P0 完成后剩余预算不足，
应先完整交付、验证和记录 P0，不提交半成品 P1。

本批不实施 API Key 配额、XML/Office、`EACH_COLLECTION`、公网 OpenAI 兼容加固，也不
把 `sourceNamespace` 扩展为检索或 ACL 范围。

## 2. 已核对的当前事实

以下结论来自当前代码、V40–V43 迁移、自动化测试和双语长青文档；归档规划只用于历史
追溯。

| 领域 | 当前事实 | 关键锚点 |
|---|---|---|
| 外部地址 | `collectionKey + sourceNamespace + externalId` 唯一定位外部文档；`sourceNamespace` 省略时规范化为字面值 `default` | `DocumentMutationService`、V40、`docs/external-document-sync-client-guide*` |
| 标识容量 | `collectionKey` / `sourceNamespace` / `externalId` 上限为 128 / 128 / 255 字符，后续迁移不得缩短 | V27、V40、DTO validation |
| 普通 upsert | 修改 `collectionKey` 会寻址另一元组，可能创建第二个文档；当前兼容流程是旧地址 tombstone 后在目标地址 upsert | `docs/rest-api*`、`docs/TODO*` |
| CRUD 联动 | 正文变化创建新 revision/version，并更新本地关键词派生、持久化新 generation embedding job；metadata/payload/source revision-only 变化在向量当前时不调用 provider | `DocumentMutationService`、`EmbeddingDispatchService` |
| 本地关键词派生 | V43 使用 `rag_document_chunks` 和 `rag_document_local_index_state`，与 Embedding Profile 解耦；`KeywordIndexPersistenceService.ensureCurrent` 通过 generation/CAS 收敛 | V43、`KeywordIndexPersistenceService` |
| 向量派生 | `rag_document_embedding_state` 和 `rag_embedding_jobs` 使用 generation、lease、唯一约束和 commit fencing；支持持久化重试/取消 | `EmbeddingJobRepository`、`EmbeddingJobService` |
| 当前 Collection readiness | `/collections/embedding-readiness` 只统计当前活动 Profile 的 fresh/queued/running/failed/stale，不表示本地 chunk 完整性 | `CollectionEmbeddingReadinessResponse`、`EmbeddingJobRepository.readiness` |
| Sync Run | V42 对一个 `collectionKey + sourceNamespace` 提供权威快照对账；missing 判断依赖 `source_mutation_sequence`、`last_seen_sync_run_id/generation` | `DocumentSyncRunService`、V42 |
| 版本历史 | FULL 快照保存 Collection、namespace、正文和 JSONB 等托管状态；版本号通过条件 `UPDATE ... RETURNING` 分配 | `RagDocumentVersion`、`DocumentVersionService` |
| 幂等账本 | V40 的 `rag_document_idempotency_operations` 按 principal + operation + key hash 唯一，只保存结果 document/batch ID，不能保存迁移发生时的不可变响应 | V40、`DocumentMutationService.reserveIdempotency` |
| Collection 生命周期 | `ActiveCollectionToken` 读取乐观版本后用条件 UPDATE 消耗；生命周期并发变化使事务回滚 | `CollectionIdentityResolver` |
| 锁策略 | 数据访问层禁止 `FOR UPDATE`、`SKIP LOCKED`、JPA 悲观锁和 advisory lock；允许条件 DML/CAS、唯一约束、lease 和有界重试 | `AGENTS.md`、`scripts/verify-no-pessimistic-locks.sh` |
| 数据库基线 | 当前 Flyway 为 V1–V43；本批首个 schema 迁移必须从 V44 开始 | `spring-ai-rag-core/src/main/resources/db/migration/` |

### 2.1 为什么这两项现在价值最高

当前增量 CRUD、权威快照、历史恢复和 keyword/vector 解耦已经解决“如何持续写入”和
“provider 故障时如何退化”。剩余最直接的生产缺口是：

- 外部文档调整投放 Collection 时，无法在不丢内部身份/历史且没有重复或空窗的情况下
  完成迁移；
- 系统有 freshness 状态，但运营者无法证明“READY 对应的派生行确实完整”，也没有一个
  preview-first、可审计、有限批量的修复入口。

这两项都直接加强本项目文档 CRUD 与索引/嵌入的连带一致性，适用于任何遵循公开外部
同步契约的 Client，而不是扩大低优先级格式或协议表面。

## 3. P0：外部文档跨 Collection 原子迁移

### 3.1 产品语义

迁移改变的是文档的 **placement address**，不是正文更新：

```text
(sourceCollectionKey, sourceNamespace, externalId)
    -> (targetCollectionKey, sourceNamespace, externalId)
```

首版只允许改变 Collection：

- `sourceNamespace` 保持不变；
- `externalId` 保持不变；
- `documentType` 和 JSON/TEXT kind 保持不变；
- 正文、title、metadata、`jsonbPayload`、enabled/tombstone 状态保持不变；
- `sourceRevision` 保持不变；客户用 `expectedSourceRevision` 对当前源状态做 CAS。

`sourceRevision` 表示来源对象的完整期望状态。仅改变 RAG 投放位置不代表来源正文或业务
状态发生变化，因此服务不能要求 client 伪造一个新来源版本。relocation 自身由
`Idempotency-Key`、`documentRevision`、namespace mutation sequence 和 `RELOCATE` 历史
快照审计。若同一业务事件还改变了来源内容，client 必须先用普通 upsert 提交新
`sourceRevision`，再以该 revision 执行纯 placement relocation；首版不合并两个 mutation。

若未来需要改 namespace 或 externalId，应增加独立 rename/rekey 能力，不能让首版 relocation
同时承担三个身份维度的任意变换。

### 3.2 API 契约

新增：

```http
POST /api/v1/rag/documents/relocate
Idempotency-Key: <required, opaque, max 255 chars>
Content-Type: application/json
```

请求：

```json
{
  "sourceCollectionKey": "customer-42:staging:v1",
  "targetCollectionKey": "customer-42:published:v1",
  "sourceNamespace": "cms-main",
  "externalId": "article:100",
  "expectedSourceRevision": "etag:8"
}
```

响应：

```json
{
  "documentId": 42,
  "sourceCollectionKey": "customer-42:staging:v1",
  "targetCollectionKey": "customer-42:published:v1",
  "sourceNamespace": "cms-main",
  "externalId": "article:100",
  "sourceRevision": "etag:8",
  "action": "RELOCATED",
  "documentRevision": 7,
  "versionNumber": 9,
  "contentChanged": false,
  "derivationAction": "PRESERVED",
  "lifecycle": {
    "searchability": "READY"
  }
}
```

契约规则：

1. `Idempotency-Key` 必填。迁移提交后旧地址立即消失，缺少显式幂等键时客户端无法安全
   判断超时请求是否已经成功。
2. `sourceCollectionKey` 与 `targetCollectionKey` 必须不同且都为活动 Collection。
3. 源地址必须存在外部托管文档；本地文档返回 `409 DOCUMENT_NOT_EXTERNAL_MANAGED`。
4. 目标三元地址已存在时返回 `409 TARGET_EXTERNAL_IDENTITY_EXISTS`；首版不 merge、不覆盖。
5. `expectedSourceRevision` 必须等于当前非空 revision；迁移后保留该 revision。revision
   只按不透明字符串比较，不比较大小或时间。
   V30/V39 遗留的 `sourceRevision IS NULL` 身份返回
   `409 LEGACY_EXTERNAL_IDENTITY_REQUIRES_CLAIM`；client 必须先用普通 upsert 声明完整状态和
   非空 source revision，再执行 relocation。
6. 同 principal、同幂等键、同规范请求精确重放原始响应；同键不同请求返回
   `409 IDEMPOTENCY_KEY_REUSED`；未完成的并发重放返回
   `409 IDEMPOTENCY_OPERATION_IN_PROGRESS`。
7. 目标不可见或无权限时遵循现有 anti-enumeration 规则，不暴露 Collection 是否存在。
8. 返回 HTTP 200；迁移不会调用 embedding provider，也不会返回新 job。

### 3.3 V44 schema 与幂等重放

新增不可变迁移：

```text
V44__document_relocation_idempotency.sql
```

扩展 `rag_document_idempotency_operations`：

```sql
ALTER TABLE rag_document_idempotency_operations
    ADD COLUMN result_payload JSONB,
    ADD COLUMN authorization_collection_ids BIGINT[];
```

同时新增永久的外部地址退休账本：

```text
rag_document_relocated_addresses
```

字段至少包含：

- `id BIGSERIAL`；
- `source_collection_id`、`source_namespace`、`external_id`；
- `document_id`（允许文档以后被受控 purge 时 `ON DELETE SET NULL`）；
- `target_collection_id`；
- `relocation_idempotency_operation_id`；
- `active BOOLEAN`；
- `created_at/resolved_at`。

`source_collection_id` 和 `target_collection_id` 对 `rag_collection` 使用默认
`RESTRICT/NO ACTION`，不能 `ON DELETE CASCADE`；否则 Collection 物理清理会静默移除地址
保护。当前 Collection API 是 soft-delete，且含 external-managed 文档时已拒绝删除，本批
保持该边界。

`relocation_idempotency_operation_id` 对幂等账本使用 `ON DELETE SET NULL`，避免 TTL
清理被永久 marker 阻塞。namespace/external ID 的数据库约束继续使用 128/255 字符和
非空可见字符规则；`active/resolved_at` 增加一致性 CHECK。

增加 partial unique index，保证一个旧三元地址最多一个 active marker：

```sql
UNIQUE (source_collection_id, source_namespace, external_id)
WHERE active = true
```

约束与边界：

- `result_payload` 保存受控、显式版本化的 relocation response envelope：
  `{"schemaVersion":1,"response":{...}}`，不保存正文、metadata、JSONB 业务 payload、
  API Key、raw `Idempotency-Key` 或 lease token；
- `operation_type='EXTERNAL_RELOCATE'` 的 `SUCCEEDED` 行必须有 `result_payload` 和两个
  去重、排序后的 `authorization_collection_ids`。`result_document_id` 可以在文档以后
  hard-delete 时由现有 FK `ON DELETE SET NULL`，不能作为历史重放的必备字段；
- 旧 operation type 继续只使用现有结果列，保持兼容；
- TTL 沿用 `rag.document-lifecycle.idempotency-ttl-hours`；
- 精确重放按 `schemaVersion` 读取并返回存储的语义响应，不能把 JSON 直接绑定到“当前
  最新 DTO”后再根据文档状态动态重建。API 后续新增 response 字段必须保持旧 envelope
  reader，至少覆盖 idempotency TTL + 滚动发布窗口。文档以后再次迁移、更新或删除，也
  不改变前一次幂等响应；
- request fingerprint 使用稳定 canonical JSON，包含源/目标 Collection key、规范化
  namespace、externalId、expected source revision，不包含 raw 幂等键；
- replay 返回前必须根据 `authorization_collection_ids` 重新应用当前 API Key ACL；
  ACL 被收紧后不能仅凭原 principal 读取旧结果。replay 不要求 Collection 仍为 active，
  因为原操作成功后 Collection 可能被软删除，但 restricted caller 仍必须对两个 ID 都有
  权限；
- 幂等 reservation、业务 mutation、`SUCCEEDED + result_payload` 必须在同一事务完成。
  业务失败时整行 reservation 回滚；不能遗留永久 `IN_PROGRESS`。
- 精确重放保证只在 TTL 内成立；client 不应在 idempotency TTL 过期后盲目重放一个响应
  未知的 relocation，应先按目标地址和内部审计信息对账。
- retired-address marker 不使用 TTL。只要旧 placement 仍可能收到延迟 webhook/CDC 或旧
  snapshot，自动过期就会重新允许创建重复文档。
- 所有外部 mutation 必须复用 namespace sequence 作为地址顺序点：先按现有规则分配该
  `collectionId + sourceNamespace` 的 mutation sequence，再在同一事务中复查 active
  marker，最后才允许 update/create/delete。只在请求开头“先查一次 marker”存在
  check-then-act 竞态，禁止采用。
- 所有外部地址入口必须检查 active marker：
  - TEXT/JSON 普通 upsert；
  - source tombstone/delete；
  - external identity lookup；
  - batch upsert；
  - Sync Run item。
  写入返回 `409 EXTERNAL_IDENTITY_RELOCATED`，不能在旧地址重新 create；lookup 返回
  `409` 和稳定 error code，不伪装普通 `404`。只有调用方同时有目标 Collection ACL 时，
  error details 才可包含 `targetCollectionKey`；否则不泄露目标身份。
- 首版不提供普通 client 删除 retired marker 的 endpoint。清理只能由同一文档的反向
  relocation 原子完成；未来受控 purge 必须另行设计，不能用 TTL 猜测延迟事件已耗尽。
- 当前 `DELETE /documents/{id}` 和 batch hard-delete 继续只允许 local document，
  relocated external document 仍必须使用来源 tombstone；不得为了清 marker 放宽
  `requireLocal`。未来若实现 external purge，必须在专用双边 ACL/CAS 事务中删除主记录和
  该 document 的全部 retired markers，并显式处理历史地址复用策略。
- 同一文档连续 A→B→C 时，不形成 marker 链。每次 relocation 都把该 document 的所有
  active 历史 marker 的 `target_collection_id` 更新为当前目标 C，再为刚离开的 B 创建
  marker。lookup/error 最多一次查询即可得到当前 placement。

`result_payload` 是对现有通用幂等账本的最小兼容扩展，不新建平行幂等系统。

### 3.4 原子事务算法

推荐新增 `DocumentRelocationService`，控制器只做 DTO/HTTP 映射。一个事务执行：

1. 规范化请求并构造 fingerprint，解析当前 principal。
2. reserve 通用幂等账本；若命中 SUCCEEDED replay，则按账本中的两个 Collection ID
   重新校验当前 ACL 并返回不可变响应，不再要求旧地址存在。
3. 新操作同时解析活动源/目标 Collection，并应用双边 ACL。
4. 对源/目标 scope 先用 V42 相同条件 DML 将 lease 已过期的 ACTIVE run 标记为
   `EXPIRED`，再检查没有未过期 ACTIVE run；存在时返回
   `409 ACTIVE_SYNC_RUN_CONFLICT`。
5. 按内部 Collection ID 升序取得两个 `ActiveCollectionToken`。这里的“取得”只是读取
   version；不使用数据库锁。
6. 读取源三元地址，校验 external-managed 和 expected revision。
7. 检查目标三元地址不存在；数据库唯一索引仍是最终并发保护。同时读取目标地址的
   active retired marker：
   - 不存在：普通前向迁移；
   - marker 的 `document_id` 是当前源文档，且 marker 的 `target_collection_id` 是当前
     源 Collection：这是同一文档的反向迁移，允许在本事务中 resolve 该 marker；
   - 其他情况：返回 `409 TARGET_EXTERNAL_IDENTITY_RETIRED`，不复用不相关旧地址。
8. 分配源 namespace 和目标 namespace 各一个 mutation sequence。两次
   `UPDATE ... RETURNING` 按 Collection ID 升序执行，降低交叉迁移的死锁概率；
   这也让 relocation 与 Sync Run begin 在同一个 `rag_document_source_namespaces` scope
   行上按条件 DML 顺序收敛。分配完成后必须再次查询两个 scope 均无 ACTIVE run；如果
   begin 已先完成，则 relocation 回滚；如果 relocation 的 sequence 更新先完成，后续
   begin 取得的 `snapshotStartSequence` 必然晚于迁移。遇到数据库
   deadlock/serialization failure 只允许整个事务有界重试。
9. 使用带 `id + JPA version + documentRevision + 源地址 + sourceRevision` 条件的
   `UPDATE ... RETURNING` 更新同一 `rag_documents` 行：
   - `collection_id = targetCollectionId`；
   - `source_revision` 保持不变；
   - `source_mutation_sequence = targetSequence`；
   - `last_seen_sync_run_id = NULL`；
   - `last_seen_sync_generation = NULL`；
   - `document_revision += 1`、JPA `version += 1`；
    - 其他托管字段和派生状态不变。
10. 若第 7 步识别反向迁移，先条件更新目标旧 marker 为 `active=false` 并写
    `resolved_at`。随后把该 document 其余 active 历史 marker 的
    `target_collection_id` 扁平更新为本次目标，再为刚离开的源地址插入 active marker，
    指向同一 document 和目标 Collection。marker 变更、文档更新和幂等结果在同一事务。
11. 清理或 refresh 当前 persistence context，再从数据库重读迁移后的 `RagDocument`；
    不能把 JDBC 更新前的 JPA entity 交给版本或 lifecycle 服务。
12. 写入一个 FULL `RELOCATE` 版本快照。快照代表迁移后的目标地址；响应同时返回旧/新
   Collection key 以保留操作语义。历史旧版本自身的 Collection snapshot 不修改。
13. 依次 `confirmActiveWrite` 两个 token，仍按内部 ID 升序。任一 Collection 在事务期间
    被删除或生命周期变化，整个事务回滚。
14. 保存不可变响应、两个授权 Collection ID，将幂等操作标记为 `SUCCEEDED`，提交事务。

不能通过 JPA 先 `saveAndFlush` 再在事务外完成幂等结果；也不能先 tombstone 再 create。

### 3.5 Sync Run 交互

迁移必须与 V42 的来源级对账语义兼容。首版采用保守且可证明的边界：**源或目标
namespace 存在 ACTIVE Sync Run 时拒绝 relocation**。

- **源侧**：迁移开始时分配一个源 namespace mutation sequence，作为源地址的退出边界。
  新 retired-address ledger 使迁移完成后的延迟 Sync Run item 无法重新创建旧 placement；
  item 以 `EXTERNAL_IDENTITY_RELOCATED` 失败，TOMBSTONE run 在 failed item 解决前不能
  complete。
- **目标侧**：迁移后的文档使用目标 namespace 新 sequence，并清空
  `last_seen_sync_run_id/generation`。后续新 run 的 snapshot boundary 将晚于该 sequence。
- `reconciliation_tombstone_run_id` 和 `deletion_origin` 按当前文档状态原样保留。迁移
  tombstoned 文档是允许的，便于保留来源删除历史；它仍不可检索。
- relocation 不加入 run item ledger，也不伪造“本 run 已看到”。
- 拒绝检查和迁移事务之间仍可能并发 begin Sync Run。两边都先通过 namespace scope 的
  `UPDATE ... RETURNING` 分配 sequence，再读取 ACTIVE run：begin 先完成则 relocation
  回滚；relocation 先完成则 begin 的 snapshot boundary 晚于迁移。实现复用 V40
  namespace 行和 V42 active-run 唯一索引，不能使用显式锁。
- retired-address ledger 已为未来放宽 active-run 冲突提供必要条件，但首版仍拒绝 active
  source/target run，避免 connector 在一次 manifest 内混用新旧 placement。后续放宽必须
  有独立协议和 E2E 证据。

### 3.6 派生数据语义

Collection 只用于检索范围和 ACL 过滤；现有本地 chunk、向量、状态和 job 都通过
`document_id` 关联。因此 relocation：

- 保留 `rag_document_chunks`；
- 保留 `rag_document_local_index_state`；
- 保留 `rag_embeddings`；
- 保留 `rag_document_embedding_state`；
- 保留历史/终态 `rag_embedding_jobs`；
- 若当前存在 QUEUED/RUNNING job，也保留并让其继续；后续 job 查询与 commit 时通过当前
  文档 Collection 执行授权/范围判断；
- 不更改 content hash、chunker version 或 generation；
- 不调用 `KeywordIndexPersistenceService.ensureCurrent`；
- 不调用 `EmbeddingDispatchService`。

迁移响应的 `derivationAction=PRESERVED` 是事实，不等同于承诺当前状态一定 READY；响应
中的 lifecycle 应读取迁移后的真实状态，可能是 `READY`、`KEYWORD_ONLY`、`INDEXING`、
`FAILED`、`NOT_REQUESTED` 或 `DISABLED`。

### 3.7 并发与失败语义

必须覆盖：

- 两个请求把同一源文档移到不同目标：document revision/source revision CAS 只允许一个
  成功；
- 两个不同源文档竞争同一目标三元地址：唯一索引只允许一个成功；
- A→B 与 B→A 交叉迁移：所有 namespace sequence 和 Collection token 按稳定 ID 顺序
  操作，数据库死锁作为可重试事务错误，不使用悲观锁；
- 源或目标 active Sync Run，以及 relocation 与 run begin 的竞态；
- 迁移期间源或目标 Collection 被 soft delete：token 消耗失败，事务回滚；
- 提交成功但 HTTP 响应丢失：同幂等键返回原响应；
- relocation 完成后旧地址收到普通 upsert/delete/lookup、batch 或 Sync Run item；
- 旧地址 mutation 与 relocation 在 marker 插入前后交错，sequence 后复查只允许一个
  placement 生效；
- 同一文档反向 relocation 原子 resolve 目标旧 marker 并创建新的源 marker；
- A→B→C 以及 C→B 后所有历史 marker 直接指向当前 Collection，不形成 redirect chain；
- 不相关文档试图迁入 active retired address；
- 同文档正文更新与迁移并发：document row optimistic version / document revision CAS
  使其中一个回滚，客户端重新读取后重试；
- 目标 key 指向 soft-deleted Collection：拒绝，不自动 restore；
- 目标地址冲突、ACL、CAS、幂等冲突均不得留下版本、sequence、idempotency 成功记录或
  部分移动。

### 3.8 Client 最佳实践

实施完成后双语外部同步指南必须增加：

1. placement 变化只调用 relocation，不用普通 upsert 模拟；
2. 每次 relocation 使用新的随机 `Idempotency-Key`，持久化到该业务事件完成；
3. 网络错误或 5xx 使用同 key、同 body 重试；
4. 409 按错误码区分 stale revision、目标冲突和 key 复用；
5. 成功后将本地 checkpoint 原子更新为目标 Collection；source revision 保持不变；
6. relocation 不重新 embedding，客户端不应等待新 embedding job，但应读取 lifecycle
   判断迁移前已有派生是否健康；
7. 需要同时改 namespace/externalId 时，首版仍使用显式旧地址 tombstone + 新地址
   upsert，不能伪装为 relocation。
8. 收到 `EXTERNAL_IDENTITY_RELOCATED` 表示 connector placement/checkpoint 已过期；有
   目标 ACL 时可使用响应目标修正 checkpoint，否则交由有双边权限的运营流程处理。不要
   改随机 externalId 绕过冲突。

reference client 可增加一个独立 `relocate` 命令；不能把该操作隐式混入普通
`apply-events`。

## 4. P1：派生索引完整性诊断与受控修复

### 4.1 目标与边界

现有 lifecycle 回答单文档“当前是否可检索”，embedding readiness 回答 Collection 的向量
任务分类；两者都不是完整性扫描。新控制面回答：

> 对一个授权 Collection，文档主记录、本地关键词派生和活动 Profile 向量派生是否在
> 数据行层面互相一致？若不一致，系统计划复用哪些正式业务路径修复？

首版只扫描一个显式 `collectionKey`，不支持全服务、`ANY_COLLECTION` 或跨所有授权
Collection 的隐式 fan-out。

### 4.2 新 API

#### 摘要

```http
GET /api/v1/rag/collections/derivation-readiness?collectionKey=...
```

返回互斥文档 bucket：

```json
{
  "collectionKey": "customer-42:published:v1",
  "activeEmbeddingProfileKey": "bge-m3-1024",
  "enabledDocuments": 120,
  "readyDocuments": 104,
  "keywordOnlyDocuments": 9,
  "indexingDocuments": 4,
  "localUnavailableDocuments": 2,
  "vectorRepairNeededDocuments": 3,
  "notRequestedDocuments": 1,
  "corruptDocuments": 0,
  "disabledDocuments": 8,
  "scannedAt": "2026-08-20T12:00:00Z"
}
```

上例的互斥 enabled buckets 为 `104 + 9 + 4 + 2 + 1 + 0 = 120`；
`vectorRepairNeededDocuments=3` 是交叉诊断计数。分类必须固定且互斥，保证 enabled
buckets 总和等于 `enabledDocuments`。bucket 表示**当前
用户可见的检索能力**；local/vector 的具体故障和修复动作在详情字段中表达，避免把一个
仍可关键词检索的文档仅因向量失败归入“不可用”：

1. `CORRUPT`：任一分支的 state 宣称 READY/COMPLETED，但其物理行数量、hash、chunker、
   generation 或向量列不满足不变量；
2. `READY`：local 和活动 Profile vector 都当前且物理行完整；
3. `KEYWORD_ONLY`：local 当前但 vector 不当前，包括 vector QUEUED/RUNNING/FAILED/
   NOT_REQUESTED。详情通过 `vectorCondition=INDEXING/FAILED/NOT_REQUESTED/STALE`
   和 `recommendedActions` 区分修复方式；
4. `INDEXING`：local 不当前、没有 terminal local error，且当前向量 generation 有
   QUEUED/RUNNING job，系统正在收敛；
5. `NOT_REQUESTED`：local state 明确为 NOT_REQUESTED、vector 不存在或明确为
   NOT_REQUESTED，且没有 active job；
6. `LOCAL_UNAVAILABLE`：其余 enabled 文档中 local 不当前的情况，包括 local state
   缺失、FAILED、hash/chunker/generation stale，或 vector 虽当前但全文分支不可用。
   详情用 `localCondition=MISSING/FAILED/STALE` 区分。

响应中的 `vectorRepairNeededDocuments` 是非互斥诊断计数，可以是 `KEYWORD_ONLY` 或
`CORRUPT` 的子集，不参与 enabled bucket 求和；`corruptDocuments` 仍是互斥 bucket。

disabled/tombstoned 文档单独统计，不进入 enabled buckets，也不自动修复。

#### 有限详情

```http
GET /api/v1/rag/collections/derivation-readiness/documents
  ?collectionKey=...
  &bucket=LOCAL_UNAVAILABLE
  &page=0
  &size=50
```

`size` 限制 1–100。详情只返回：

- `documentId`、title；
- `documentRevision`；
- 外部身份摘要（namespace/externalId，可为空）；
- local/vector 状态、generation、期望/实际 chunk count；
- active job ID/status；
- 受控 `reasonCode` 和最多 500 字符的脱敏错误；
- `repairable` 和推荐动作。

不返回正文、metadata、`jsonbPayload`、向量或 chunk 文本。

#### 查询 repair operation

```http
GET /api/v1/rag/collections/derivation-repairs/{repairId}
```

必须重新校验当前 principal 和 Collection ACL，返回 preview/apply 状态、逐项终态和 job
ID。apply 请求中断或进程重启后，client 使用该 endpoint 恢复，不依赖原 HTTP 连接。

#### Preview repair

```http
POST /api/v1/rag/collections/derivation-repairs/preview
```

```json
{
  "collectionKey": "customer-42:published:v1",
  "buckets": ["CORRUPT", "LOCAL_UNAVAILABLE"],
  "vectorConditions": ["FAILED", "STALE"],
  "maxDocuments": 100
}
```

返回：

- `repairId`；
- 最多 100 个稳定排序的 document IDs 和动作；
- `previewFingerprint`；
- opaque `previewToken`；
- 每种动作计数；
- skipped/disabled/unrepairable 计数；
- token expiry（推荐 15 分钟）。

preview token 只保存 hash；fingerprint 绑定 Collection ID/key、active Profile ID、
document ID/revision/content hash、local/vector generation、reason/action，不包含正文。
每个 item plan 分开保存：

- 不因 repair 改变的业务前态：document ID/revision/JPA version/content hash/enabled/
  Collection；
- local 初始 generation/state/hash/chunker/count；
- vector 初始 profile/generation/state/hash/chunker/count/active job；
- 计划动作和 reason。

这里不绑定 `RagCollection.version`。当前 `ActiveCollectionToken` 会在正常文档写入时推进
该 version；把它放进整个 repair 的长期 fingerprint，会让第一个成功 item 使后续 items
自我失效。apply 开始和每个子动作都重新要求 Collection active，并用当时取得的短生命周期
token 做 CAS fencing。

#### Apply repair

```http
POST /api/v1/rag/collections/derivation-repairs/apply
```

```json
{
  "repairId": "a8e271d5-cc0f-4de4-bc3a-68c477193d8c",
  "collectionKey": "customer-42:published:v1",
  "previewToken": "...",
  "previewFingerprint": "sha256..."
}
```

返回逐项结果和摘要。apply 必须校验 `repairId + owner principal + token hash +
Collection ACL`，再重新读取并核对 fingerprint；任一文档已变化时，该项返回
`SKIPPED_CHANGED`，不能对新 revision 执行旧计划。批次允许部分成功。

### 4.3 完整性不变量

#### 本地关键词分支

`READY` 必须同时满足：

- document enabled；
- state `local_index_status='READY'`；
- state content hash 等于 document content hash；
- state chunker version 等于当前 descriptor；
- generation > 0、chunk count > 0；
- 当前 generation 的物理 chunk 数量等于 state count；
- chunk index 连续为 `0..count-1`；
- 每行 document ID/generation/hash/chunker 一致；
- chunk text 非空且 position 合法（数据库约束已覆盖部分条件）。

#### 向量分支

`READY` 必须同时满足：

- 活动 Profile state `COMPLETED`；
- state content hash/chunker version 与当前文档 descriptor 一致；
- state request generation > 0、chunk count > 0；
- 当前 Profile 的物理向量行数量等于 state count；
- chunk index 连续；
- 每行 Profile ID 与当前 state 一致；`rag_embeddings` 不存 content hash/chunker/
  generation，因此不能只凭 state 和数量证明 freshness；
- 每个向量行必须按 `chunk_index` 与当前
  `rag_document_local_index_state.local_index_generation` 的
  `rag_document_chunks` 一一对应，且 `chunk_text` 和 chunk position 相同。V43 之后 local
  chunks 是当前正文/chunker 的 profile-neutral 权威分块；这项内容对齐可以排除“旧向量
  行数量恰好相同但文本已过期”；
- 固定向量列非空且维度符合活动 Profile；
- active job 若存在，必须与 state generation/hash/profile/descriptor 一致，不能用旧 job
  解释当前状态。

扫描 SQL 必须是集合级 bounded 聚合，不能对每个文档触发 N+1。

### 4.4 共享 freshness 真相源

新诊断不能成为与现有 lifecycle/cache/readiness 相互矛盾的第二套判断。实施时新增
`DerivationIntegrityRepository`，以相同 SQL 不变量提供：

- 单文档 local/vector 完整性快照；
- Collection 互斥摘要和分页详情；
- 旧 embedding-only readiness 所需的向量 bucket。

并替换以下宽松判断：

- `KeywordIndexPersistenceService.hasFreshLocalIndex` 和内部 `isCurrent`：从“state + 行数”
  收紧为完整 local 不变量；`ensureCurrent` 因此可自然发现 stale/missing，只有明确
  `CORRUPT` repair 才使用 force rebuild；
- `EmbeddingPersistenceService.findCacheState`：从“state + 非空向量行数”收紧为向量行
  与当前 local generation 的 chunk text/position 一致；
- `DocumentLifecycleService.read`：使用共享快照，不能在物理行损坏时返回 `READY`；
- `EmbeddingJobRepository.readiness` / 旧 `/collections/embedding-readiness`：保留 DTO
  和 endpoint，但 fresh/stale 分类改用同一物理完整性标准；
- `DocumentEmbedService.hasFreshEmbedding`：通过共享 local/vector freshness，不允许
  metadata-only mutation 因损坏但数量相同的向量而错误跳过 repair。

共享 repository 只读，不负责修复，也不依赖上述 service，避免循环依赖。SQL 需要根据
白名单 `EmbeddingVectorColumns.columnFor(profile.dimensions())` 选择固定向量列，不能拼接
调用方输入。

### 4.5 修复动作矩阵

| 诊断 | 默认动作 | 禁止动作 |
|---|---|---|
| local 缺失/陈旧/物理损坏，document 可用 | 收紧共享 `isCurrent` 后复用 `KeywordIndexPersistenceService.ensureCurrent(document)`；strict false 会自然分配新 generation 并重建 | 直接插入伪造 READY state |
| vector stale/缺失/FAILED/物理损坏 | 收紧共享 `hasFreshEmbedding` 后复用 `EmbeddingDispatchService.enqueueInCurrentTransaction(..., force=true, origin="DERIVATION_REPAIR")`；strict fresh=false 会让现有 `allocateGeneration` 使用 `preserveCompleted=false` | 同步循环调用 provider；直接更新 COMPLETED |
| local 和 vector 都损坏 | 先提交 local rebuild 子动作，再在第二个短事务排队 vector；ledger 分别记录两个子动作 | vector 排队失败时回滚已经成功的 local rebuild |
| active job 已 QUEUED/RUNNING 且匹配 | `NOOP_ALREADY_CONVERGING` | 创建重复 generation |
| document revision/hash 在 preview 后变化 | `SKIPPED_CHANGED` | 按旧 preview 修复 |
| disabled/tombstoned | `SKIPPED_DISABLED` | 自动 enable/恢复 |
| Collection 生命周期变化 | 该项回滚并报告冲突 | 跨已删除 Collection 写入 |

本批不新增第二套 chunker 或第二套 embedding enqueue。共享 strict freshness 修复后，
现有 `ensureCurrent` 会绕过旧的 false-positive early return；现有 force enqueue 会在
strict fresh=false 时自然使用 `preserveCompleted=false`。旧物理向量行可以留到 worker
原子替换，但 retrieval freshness 必须立即通过新 state generation/status 排除它们。
repair 只保证动作被正确接受或排队，不等待全部远程 embedding 完成；响应返回 job ID，
调用方随后查询 derivation readiness 或现有 embedding job。

### 4.6 Repair ledger 与并发

P1 使用独立迁移 `V45__derivation_repair_control_plane.sql` 创建控制面表；P0 的 V44 一旦
执行就不得再修改：

```text
rag_derivation_repair_previews
```

字段至少包含：

- `id UUID`；
- `owner_principal_id`；
- `collection_id`；
- `active_embedding_profile_id`；
- `preview_token_hash`；
- `preview_fingerprint`；
- `request_payload JSONB`，只保存 bucket/max/action 摘要；
- `plan_payload JSONB`，只保存 document ID/revision/hash/generation/action/reason；
- `status`：`PREVIEWED/APPLYING/COMPLETED/EXPIRED`；
- `apply_lease_owner_hash/apply_lease_expires_at`；
- `preview_deadline/operation_deadline/result_expires_at`；
- `created_at/completed_at`。

硬约束：

- token 明文不落库、不进日志；
- plan 不含正文、chunk、向量、metadata 或业务 JSONB；
- preview 默认必须在创建后 15 分钟内开始 apply；过期的 PREVIEWED operation 条件更新为
  EXPIRED，只能重新 preview；
- apply 开始后默认最多 1 小时完成或被同 token 接管；超过 `operation_deadline` 的未完成
  items 终止为 FAILED/EXPIRED，已经提交的 local rebuild 或 vector job 不回滚；
- COMPLETED/EXPIRED 结果默认保留 24 小时供 status 查询和精确重放，之后才能由 bounded
  cleanup 删除。三个期限均可配置但必须有上限；
- 首次 apply 通过条件 `UPDATE PREVIEWED -> APPLYING` 安装有时限的 apply lease；同 token
  重放读取 item ledger。lease 未过期时不启动第二个 worker，过期后可由同 token 条件
  接管未完成 items；
- 增加 `rag_derivation_repair_items`，按 preview ID + document ID 唯一保存
  action/status/job/result/error；每项保存 `local_action_status` 和
  `vector_action_status`，取值为
  `NOT_PLANNED/PLANNED/APPLYING/SUCCEEDED/SKIPPED/FAILED`，另有聚合 item status；
- item 表包含 `lease_owner_hash/lease_expires_at/attempt_count/updated_at`。item claim
  使用带状态、lease expiry 和 attempt count 上限的条件 UPDATE，完成结果写入同一短事务。
  接管只处理 `PLANNED` 或 lease 已过期的 `APPLYING` item，不重复执行已有终态；
- local/vector 子动作分别提交。进程在两者之间崩溃时，接管者从 ledger 继续未完成子
  动作；已经成功的 local rebuild 不重复、不因 vector 失败回滚；
- 第一个子动作前验证完整 preview 前态。local 成功后把新的 local generation/hash/
  chunker/count，以及 document 的 post-local JPA version/content hash 写入 item ledger；
  legacy document 的 `ensureContentHash` 可能合法初始化 hash 并推进 JPA version。恢复
  vector 子动作时，必须验证 document revision/Collection/enabled 未变化，JPA version/
  content hash 等于本 operation 记录的 post-local 值，local 状态等于 post-local 状态，
  vector 状态仍等于 preview 前态。这样 repair 自己产生的合法变化不会触发
  `SKIPPED_CHANGED`，外部 mutation 或其他 worker 的变化仍会；
- local 子动作成功后重新计算 strict vector freshness。local 缺失时原 preview 可能无法
  证明已有向量与当前分块一致；若重建 local 后证明 vector 已完整，则 vector 子动作写
  `SKIPPED` + `ALREADY_FRESH`，不产生 provider 调用；
- vector 子动作成功后保存 job ID/request generation；重放只返回 ledger，不再次
  enqueue；
- vector 动作依赖 local current。local 子动作终态为 FAILED 时，vector 子动作写
  `SKIPPED` + `LOCAL_REPAIR_FAILED`，不调用 `EmbeddingDispatchService`；下一次运营
  repair 在 local 问题排除后重新 preview；
- 所有 item 终态后条件更新 preview 为 `COMPLETED`；同 token 后续重放返回相同结果；
- 只有 `result_expires_at` 已到的终态 operation 才由有界清理 SQL 删除；文档和 job 不
  级联删除；
- Collection 生命周期通过一个 preview token 和每个 apply 文档事务中的
  `ActiveCollectionToken` 双重校验。

如果实施时发现 P1 ledger 使批次显著超过预算，可将 P1 整体拆到下一提交，不能删掉
preview/apply 幂等语义后交付一个不可靠的批量修复按钮。

### 4.7 与现有 embedding readiness 的兼容

- 保留 `/collections/embedding-readiness` 及其 DTO，不改变字段含义；但修复其当前未核对
  物理 vector rows 的 false-fresh 缺陷；
- 新增 `/collections/derivation-readiness`，不向旧 DTO 追加会混淆 local/vector 的字段；
- WebUI 可以在过渡期同时调用两个 API，但新的派生摘要是运营主视图；
- 旧 endpoint 后续是否 deprecated 属于可逆、非阻断事项，本批不删除。

## 5. WebUI

### 5.1 Relocation

文档列表中仅对 external-managed 文档显示“迁移 Collection”操作：

- modal 使用 Collection selector，不允许选择当前 Collection；
- 显示只读 namespace、externalId、当前 source revision；
- expected source revision 自动取当前详情并只读展示；relocation 不要求输入新 revision；
- 浏览器生成并在请求生命周期内保留 `Idempotency-Key`；自动重试必须复用；
- 409 显示按错误码区分的可操作信息，并刷新文档详情；
- 成功后失效源/目标 Collection 文档列表、详情、readiness 和版本查询缓存；
- 不显示“重新嵌入中”，而显示真实 lifecycle。

### 5.2 Derivation operations

将当前 Embeddings 页面扩展为“派生索引运营”：

- Collection 必选；
- local 与 vector 两条分支分别显示，不把数字包装成概率；
- 摘要 bucket 可点击进入有限详情；
- Repair 先打开 preview，显示动作和数量，再由用户确认 apply；
- 默认选择 `CORRUPT/LOCAL_UNAVAILABLE`，并额外启用
  `vectorCondition=FAILED/STALE` 的 repair filter；不默认 force 重建 READY；
- apply 后显示逐项 `REBUILT_LOCAL/QUEUED_VECTOR/NOOP/SKIPPED/FAILED`；
- 轮询有上限，可手动刷新，不等待所有远程 job 才解除页面操作；
- Restricted API Key 只看到授权 Collection。

前端验收只能使用 DOM 可见/可访问状态、网络请求/响应和自动化断言，不使用截图。

## 6. 文件级实施顺序

### Phase A：先一次性写验收骨架

1. 列出后端 PostgreSQL/HTTP、WebUI Mock、client contract 的完整验收矩阵。
2. 新增 V44 migration test 和最小 fixture helper。
3. 新增 relocation E2E 和 derivation diagnostics/repair E2E 的测试骨架。
4. 在 WebUI `package.json` 增加稳定的 `typecheck: "tsc -b --pretty false"` script；
   当前仓库没有 `npm run typecheck`，不能在门禁中引用不存在的命令。
5. 更新一键验证脚本，使后续实现只需填满契约，而不是 review 阶段零碎补测试。

### Phase B：P0 relocation

1. `spring-ai-rag-api`：request/response DTO、error code。
2. V44：幂等 `result_payload`、授权 Collection IDs、retired-address ledger 及约束。
3. `DocumentIdempotencyService`：从 `DocumentMutationService` 私有方法提取通用
   reserve/replay/complete；先保持现有 local create 行为回归通过。
4. `DocumentRelocationService`：双 ACL、双 Collection token、双 namespace sequence、
   文档 CAS、版本、响应快照。
5. `RagDocumentController`：新增 endpoint。
6. reference client、WebUI relocation。
7. `rest-api*`、`external-document-sync-client-guide*`、`project-context*`、`TODO*`、
   testing/developer reference 双语同步。

### Phase C：P1 diagnostics/repair

1. V45：repair preview/item ledger；不修改已执行的 P0 V44。
2. API DTO/enums/error code。
3. `DerivationIntegrityRepository`：提供共享单文档 snapshot、Collection bucket/detail
   和旧 embedding readiness；替换 lifecycle/cache/readiness 的宽松 freshness。
4. `KeywordIndexPersistenceService` 和 `EmbeddingPersistenceService` 接入共享 strict
   freshness；补“行数相同但物理内容损坏”的回归测试。
5. `DerivationRepairService`，复用现有 `ensureCurrent` 和 force enqueue。
6. controller 与 ACL。
7. WebUI 派生运营视图。
8. 双语长青文档与一键脚本。

### Phase D：验证、三轮只读审查、归档

1. 先通过所有硬门槛。
2. 再执行三轮互不重叠、固定范围、只读审查；只有正确性、成本安全、兼容性或数据一致性
   缺陷才触发修改和重新验证。
3. 提炼稳定事实到长青文档。
4. 将本 plan/progress 用日期前缀移入 `docs/drafts/archive/`，更新草稿索引。

## 7. 自动化验收矩阵

### 7.1 后端 P0 PostgreSQL/HTTP E2E

- Flyway 从空库执行 V1–V44；
- TEXT 和 JSON_RECORD relocation；
- 保留 document ID、旧版本、正文/JSONB、local chunks、vectors、states、jobs；
- 不新增 chunk/vector/job，不调用 provider；
- revision/version/source sequence 正确递增；
- relocation FULL snapshot 的目标 Collection 正确，旧历史不改；
- mandatory idempotency、精确重放、同键不同请求、并发 in-progress、ACL 收紧后的 replay；
- V44 `result_payload` schemaVersion=1，以及新代码对旧 envelope 的 TTL 内兼容重放；
- 成功后文档再次更新/迁移，旧 key 仍返回原始响应；
- 所有旧地址入口被 retired marker 阻断；restricted caller 不泄露目标 Collection；
- relocation 与旧地址 mutation 的并发交错通过 namespace sequence + marker recheck 收敛；
- 反向迁移 resolve 目标 marker 并退休源地址；不相关 retired target 冲突；
- A→B→C/C→B marker flattening；
- 源/目标 ACL 全组合与 anti-enumeration；
- target identity conflict；
- stale expected revision、source revision 原样保留；
- legacy null source revision 必须先 claim；
- Collection soft-delete race；
- 同源竞争、同目标竞争、A→B/B→A；
- 源/目标 active Sync Run 拒绝，以及 run begin 竞态回滚；
- tombstoned external document relocation；
- local permanent-delete 仍拒绝 external document；Collection soft-delete/restore 不丢
  retired markers；
- restricted job/readiness 查询在迁移后只按目标 Collection 可见；
- 无悲观锁门禁。

### 7.2 后端 P1 PostgreSQL/HTTP E2E

- 每个互斥 bucket、vector repair 非互斥诊断计数和总和不变量；
- state READY 但 chunk/vector 物理行缺失、重复/不连续、hash/chunker/generation 错配；
- vector 行数量相同但 chunk text/position 与当前 local generation 不一致；
- 同一损坏 fixture 在 document lifecycle、`hasFreshEmbedding`、旧 embedding readiness
  和新 derivation readiness 中结论一致；
- vector 维度/活动 Profile freshness；
- bounded page、bounded preview、token expiry、repair status ACL；
- preview deadline、operation deadline、24h result replay 和 bounded cleanup；
- preview fingerprint 在 revision/hash/generation/Collection/Profile 变化后失效；
- local 成功后进程中断，接管者识别本 operation 的 post-local generation 并只继续
  vector 子动作；
- legacy missing content hash 由 local repair 初始化后，接管者接受 ledger 记录的
  post-local JPA version/hash；
- local 重建后 vector strict freshness 已恢复时，不排队、不调用 provider；
- local repair 通过正式 service 重建；
- local 物理行 count 相同但 chunk index 不连续时，strict `ensureCurrent` 确实产生新
  generation；
- vector repair 创建/合并持久化 job；
- COMPLETED state 物理损坏时，repair 接受后旧向量立即退出检索，worker 成功后再 READY；
- active matching job 为 NOOP；
- disabled/tombstone 不修；
- apply/status 按 repairId + owner + ACL 授权；重放返回稳定结果，不重复 local/vector
  子动作或 job；
- restricted ACL/anti-enumeration；
- Collection soft-delete race；
- 批次部分失败不回滚其他文档；
- ledger 不保存正文/JSONB/chunk/vector/token 明文。

### 7.3 前端

- TypeScript、Vitest、生产构建、alignment gate；
- Mock Playwright relocation：按钮资格、modal、请求 body/header、成功 cache refresh、409；
- Mock Playwright derivation：Collection scope、bucket/详情、preview/apply、bounded polling、
  ACL 空状态；
- 所有 Playwright 只使用 DOM、网络 JSON 和断言，不截图。

### 7.4 一键脚本

新增或扩展：

```text
scripts/verify-document-relocation.sh
scripts/verify-derivation-integrity.sh
scripts/verify-document-lifecycle.sh
```

脚本要求：

- 创建一次性 PostgreSQL 数据库，真实执行 Flyway；
- 运行专项 Spring 集成测试与 HTTP 契约；
- 运行 `mvn clean compile test-compile` 和相关测试；
- 运行前端 tsc/Vitest/生产 build/核心 Mock Playwright；
- 运行 `verify-no-pessimistic-locks.sh`、`verify-project-docs.sh`、`git diff --check`；
- bounded timeout、持续轮询后台 session、退出时只清理脚本拥有的进程/数据库；
- 输出 `.verification/<feature>/<run-id>/summary.md`，目录保持 gitignore。

真实 embedding provider 不属于 relocation 验收，因为该操作必须证明“不调用 provider”；
P1 可使用 deterministic test embedding 验证行一致性，最终真实 LLM/embedding 冒烟是可选
补充，不替代 PostgreSQL E2E。

## 8. 硬门槛与实施后收敛检查

代码 review 前必须全部通过：

```bash
mvn clean compile test-compile
# 本任务后端专项 PostgreSQL/HTTP 集成测试

cd spring-ai-rag-webui
npm run typecheck
npm run test:run
npm run build
# 本任务核心 Mock Playwright，无截图

./scripts/verify-no-pessimistic-locks.sh
./scripts/verify-project-docs.sh
git diff --check
```

硬门槛通过后执行连续三轮：

1. **事务/数据一致性**：迁移原子性、幂等、CAS、Sync Run、派生行不变量、migration。
2. **API/ACL/客户端兼容**：anti-enumeration、错误码、重试契约、WebUI 请求和 client 指引。
3. **测试/运营/文档**：验收矩阵覆盖、一键脚本、成本上限、双语长青同步和回滚。

若任一轮发现本任务范围内影响正确性、成本安全、兼容性或数据一致性的缺陷：

- 立即修复；
- 重跑受影响专项门禁和全部基本硬门槛；
- 检查计数重置为 0。

不在 review 阶段处理纯风格或可选优化，也不临时扩大范围。

## 9. 发布、回滚与可观测性

### 9.1 发布

- feature flags 推荐：
  - `rag.document-lifecycle.relocation-enabled=false`；
  - `rag.document-lifecycle.derivation-repair-enabled=false`；
- readiness 只读诊断可随 P1 默认开启；有副作用的 repair 默认关闭，完成验收后再给生产
  推荐值；
- 先迁移 schema，再部署兼容代码；旧节点必须能忽略新增 nullable 列/新表；
- 多实例滚动发布期间，唯一约束、CAS 和 idempotency ledger 是正确性边界。

### 9.2 业务回滚

- relocation 不提供自动“数据库回滚”；需要撤销时调用一个新的反向 relocation，保持
  当前 source revision，并使用新的 Idempotency-Key，原子切换 retired markers，形成
  可审计历史；
- repair 只重建派生，不改正文。local 重建失败保持非 READY；vector job 可取消/重试；
- 已执行 Flyway 不修改、不 repair checksum；应用回退只关闭 feature flag。

### 9.3 指标与日志

至少增加：

- relocation success/conflict/replay/latency；
- relocation preserved lifecycle 分布；
- derivation bucket counts；
- preview/apply documents、skipped changed、local rebuilt、vector queued、failed；
- 所有日志只记录 document ID、Collection ID、reason code 和 hash 摘要，不记录正文、
  JSONB payload、raw idempotency/preview token。

## 10. 默认决策与可逆边界

| 决策 | 本批默认 | 理由 | 可逆边界 |
|---|---|---|---|
| relocation 可变字段 | 只改 Collection | 低风险，保持 identity 其他维度稳定 | 后续独立 rename/rekey |
| Idempotency-Key | 必填 | 旧地址消失后没有安全隐式重放依据 | 不能降为可选，除非另有 durable operation ID |
| 目标冲突 | 409，不 merge/overwrite | 避免隐式数据丢失 | 后续可增加显式 conflict policy |
| tombstoned 文档 | 允许迁移并保持 tombstone | 保留来源历史和 placement 管理能力 | 可用策略开关收紧 |
| active Sync Run | 源或目标存在 active run 时 409 | 旧源 run 否则可能重新创建旧地址 | 有 placement-exit ledger 后可放宽 |
| retired address 生命周期 | 不自动过期，只由同文档反向迁移 resolve | 延迟事件没有可靠最长寿命 | 未来显式 purge 协议 |
| 派生处理 | 原样保留，不重建 | Collection 不参与 chunk/vector 描述符 | 若未来派生模型包含 Collection，则 descriptor 版本变化会自然触发重建 |
| readiness API | 新 endpoint | 不污染旧 embedding-only 语义 | 旧 endpoint 后续可 deprecate |
| repair 模式 | preview-first、最多 100 | 控制成本和误操作 | 上限可配置但必须 bounded |
| repair 等待 | 只排队，不等待远程完成 | 防止长请求和不可控成本 | client 可轮询 readiness |
| P1 ledger | durable preview + item result | 支持崩溃/重放和审计 | 可换实现，不能删除语义 |

## 11. 明确非目标

- 不支持本地文档 relocation；本地文档已有普通 Collection 编辑语义，可另行统一。
- 不支持 relocation 同时更新正文/metadata/payload。
- 不支持 namespace/externalId rename。
- 不把 namespace 变成 Search/Chat filter 或 ACL。
- 不自动遍历所有 Collection 修复。
- 不自动修复 disabled/tombstoned 文档。
- 不 force 重建已经完整 READY 的派生。
- 不引入悲观锁、分布式锁或新的消息队列。
- 不实现 API Key quota、XML/Office、`EACH_COLLECTION` 或公网 OpenAI 生产加固。

## 12. 完成定义

本批完成必须满足：

1. P0 至少完整交付；P1 若开始则必须完整交付，不能保留半成品 endpoint/UI。
2. API、Flyway、后端 E2E、前端 DOM/网络验收、一键脚本全部通过。
3. `mvn clean compile test-compile`、前端 typecheck/test/build、禁悲观锁和文档门禁通过。
4. 连续三轮固定范围实现检查无修改。
5. 稳定事实同步到双语 `rest-api*`、`project-context*`、client guide、testing/developer
   reference 和 TODO。
6. plan/progress 归档，草稿索引恢复为无活跃计划。
7. commit、merge remote、push 后工作区干净（不追赶随后产生的他人 WIP）。
