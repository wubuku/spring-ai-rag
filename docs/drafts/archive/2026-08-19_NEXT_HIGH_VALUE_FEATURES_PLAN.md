# 下一批高价值功能实施规划

> **状态：规划完成，尚未实施。**
>
> 本文是当前活跃规划；实施过程如需跨多个会话继续，应在同目录增加
> `NEXT_HIGH_VALUE_FEATURES_PROGRESS.md`。实施完成、取消或被替代后，先把仍有效的事实
> 提炼到双语长青文档，再将本文移入 `docs/drafts/archive/`。

## 1. 目标与结论

本批次不追求继续扩大 RAG 的“能力清单”，而是补齐当前文档生命周期最有价值的三个
生产闭环：

1. **权威来源全量快照对账**：外部来源发生事件丢失时，仍能安全发现缺失对象并按明确
   策略生成 tombstone。
2. **历史版本受控恢复**：把已保存的完整版本快照恢复为一个新的文档 revision，并自动
   触发现有的索引/嵌入联动，不覆盖历史、不回拨版本号。
3. **本地关键词索引与远程向量解耦**：embedding provider 暂时不可用时，新正文仍可在
   本地全文索引中检索，最终再升级为语义 + 关键词的 `READY`。

这三个功能直接服务于“文档 CRUD 后索引/嵌入如何连带更新”和“外部 client 如何正确接入”：

```text
来源主数据
  -> 增量 CRUD + CAS
  -> 权威全量快照对账
  -> 文档主记录 / revision / tombstone
  -> 本地 chunk / full-text 派生
  -> 远程 embedding 派生
  -> READY 或 KEYWORD_ONLY
```

推荐实施顺序为 **P0 → P1 → P1（较大）**。本轮只编写规划，不修改生产代码。

## 2. 规划依据与当前代码事实

### 2.1 当前事实

以下事实已根据当前 `main` 代码、V40/V41 迁移、测试和正式文档交叉核对；历史计划只
作为设计来源，不能替代当前代码。

| 领域 | 当前事实 | 代码/文档锚点 |
|---|---|---|
| 外部身份 | 普通文档和 JSON record 使用 `collectionKey + sourceNamespace + externalId`；`sourceRevision` 和 `expectedSourceRevision` 支持严格 CAS 与精确重放 | `DocumentMutationService`、`ExternalDocumentUpsertRequest`、`docs/external-document-sync-client-guide*` |
| 增量同步 | 支持单条/批量 upsert、来源 tombstone、恢复同一内部 `documentId`；reference client 只实现 JSONL `apply-events` | `ExternalDocumentService`、`DocumentMutationService`、`examples/external-sync-client/` |
| 全量对账 | 当前没有 sync-run、manifest、begin/upload/complete 或“missing tombstone”协议；不允许从不完整批次推断删除 | `docs/project-context*`、`docs/external-document-sync-client-guide*` |
| namespace 顺序 | V40 已有 `rag_document_source_namespaces`；外部 mutation 通过 `UPDATE ... RETURNING` 分配 `mutation_sequence` | `V40__document_lifecycle_expand.sql`、`DocumentMutationService.allocateSourceSequence` |
| 文档 CAS | 本地文档使用公开 `documentRevision`；内部 JPA `rowVersion` 仅用于短事务乐观协调 | `DocumentMutationService`、`RagDocument`、`docs/project-context*` |
| 版本快照 | V40/V41 已保存 title/source/type/filename/Collection/namespace/enabled/deletion/metadata/content/JSONB 等字段；`snapshot_completeness` 区分 `FULL` 与旧兼容快照 | `RagDocumentVersion`、`V40__document_lifecycle_expand.sql` |
| 版本 API | 目前只有版本列表、版本详情和 WebUI diff，没有 restore endpoint，也没有把快照转成新 revision 的服务方法 | `RagDocumentController`、`DocumentVersionService`、`VersionHistoryModal` |
| 派生任务 | 持久化 embedding jobs 有 generation、lease、commit fencing 和 `SYNC/ASYNC/SKIP` 策略；正文变更会先排除旧派生结果 | `EmbeddingDispatchService`、`EmbeddingJobRepository`、`EmbeddingPersistenceService` |
| 当前全文索引 | pg_trgm / FTS provider 读取 `rag_embeddings.chunk_text`，并通过 `rag_document_embedding_state.status = COMPLETED`、content hash、chunker version 判断 freshness | `EmbeddingProfileSqlScope`、`PgTrgmFulltextProvider`、`PgEnglishFtsProvider`、`PgJiebaFulltextProvider` |
| provider 失败语义 | 当前正文变更到新 embedding 完成前，或 embedding provider 失败时，文档同时退出向量和关键词检索；公开 lifecycle 尚未真正提供独立的 local-index 状态 | `DocumentLifecycleService`、`EmbeddingPersistenceService`、`docs/project-context*` |
| 外部标识长度 | 当前 `collectionKey` 与 `sourceNamespace` 最多 128 字符，`externalId` 最多 255 字符；这些上限不得被后续迁移缩短 | V27/V40、DTO validation、`docs/configuration*` |
| 并发约束 | 禁止 `FOR UPDATE`、`SKIP LOCKED`、JPA `PESSIMISTIC_*` 和 advisory lock；使用条件 DML/CAS、唯一约束、lease 和有界重试 | `AGENTS.md`、`docs/project-context*`、`scripts/verify-no-pessimistic-locks.sh` |
| 数据库版本 | 当前 Flyway 为 V1–V41；下一项 schema 迁移从 V42 开始 | `spring-ai-rag-core/src/main/resources/db/migration/` |

### 2.2 直接缺口

当前系统已经能做到：

- 文档正文发生变化时，旧 chunk/embedding 不再参与检索；
- 新 revision 与持久化 embedding job 在同一事务提交；
- provider 失败后可通过 embedding operations 重试；
- 外部 client 可以安全处理乱序、重复、超时和显式删除。

但还不能做到：

- 证明一次外部来源全量快照中“没有出现”的对象确实已经从来源删除；
- 让人工从版本历史恢复一次错误修改，同时保持 revision/CAS/embedding fencing 正确；
- 让 provider 故障期间的新正文先通过本地关键词检索可用。

### 2.3 外部身份与 `sourceNamespace` 的取舍

本规划明确采用以下兼容模型，不把 `sourceNamespace` 升格为检索范围或 ACL 维度，也不
把它降级为无语义备注：

```text
RAG 投放地址 = collectionKey + sourceNamespace + externalId
数据库唯一约束 = (collection_id, source_namespace, external_id)
```

选择理由：

1. 当前项目没有独立的 `tenantKey` 或 connector 资源；Collection 同时承担投放目标和
   API Key ACL 边界。
2. 同一个来源对象可能被有意投放到多个 Collection。全局唯一
   `sourceNamespace + externalId` 会禁止这种合法的多份 RAG 投放，也会迫使服务在没有
   租户边界时承担跨 Collection 的身份冲突风险。
3. 同一个 Collection 由多个 connector 写入时，namespace 可以避免相同 `externalId`
   冲突，并把全量快照、tombstone 和 mutation sequence 限定在正确的来源分区。
4. `sourceNamespace` 不是备注：备注不能安全支持幂等定位、来源级对账或唯一约束。
5. `sourceNamespace` 也不是检索范围：默认 Search/Chat 跨授权 Collection 中的所有
   namespace。若未来出现明确的“只搜索某来源”需求，应增加授权后的
   `filters.sourceNamespaces` 交集过滤，不新增第二套 Collection scope mode。

兼容规则：

- `sourceNamespace` 在 API 中可省略，省略规范化为 `default`；
- 单来源 Collection 的 client 推荐省略它；
- 多来源 Collection 或使用来源级 snapshot reconciliation 的 client 必须固定并显式发送
  一个稳定值，例如 `cms-main` 或 `erp-products`；
- 不定义 `__DEFAULT__` 之类的特殊 sentinel；`default` 是兼容 namespace 字面值，不是默认
  Collection；
- `externalId` 只需在 `(collectionKey, sourceNamespace)` 范围内稳定，不要把 namespace
  再重复编码进 `externalId`；
- `collectionKey`、`sourceNamespace`、`externalId` 的客户端契约上限分别为
  `128`、`128`、`255` 个字符，后续 schema 迁移不得降低这三个上限；
- WebUI 只读展示外部文档的来源 namespace，不把它做成普通用户的 Collection 选择器；
- 未来只有在引入真正的租户/来源所有者边界后，才重新评估
  `tenantKey + sourceNamespace + externalId` 的全局唯一模型。

跨 Collection 移动仍是独立的 placement mutation，不是改变 namespace 的理由。当前普通
upsert 不提供保留同一内部文档和历史的原子移动；需要该能力时另行规划 relocation，
不混入本批次。当前缺口和兼容做法继续由 `docs/TODO*` 与外部同步 Client 指南记录。

## 3. 优先级与范围决策

### 3.1 P0：权威来源全量快照对账

**价值最高，先实施。** 增量 webhook/CDC 解决“已收到的事件”，不能解决事件丢失、订阅
中断或 connector 重启造成的缺失。快照对账是外部客户敢于把该服务作为检索派生系统的
必要运营能力。

首版只支持：

- 一个 `collectionKey + sourceNamespace` 一次最多一个 active run；
- `TEXT` 和 `JSON_RECORD` 两种 item；
- `ONLINE_CUT + TOMBSTONE`（推荐）；
- `OFFLINE_MANIFEST + NONE`（安全但只 upsert，不按 missing 删除）；
- `EXCLUSIVE_OFFLINE + TOMBSTONE`（显式危险选项，不由 reference client 默认开启）；
- bounded batch、bounded run lease、可重试 complete、可 abort、可预览 missing。

不在首版支持：

- 隐式遍历整个服务的所有 Collection；
- 等待全部 embedding 完成后才 complete；
- connector 自定义 SQL/JSONPath；
- 由服务猜测 offline manifest 是否“最新”；
- 通过普通 batch upsert 的部分成功结果推断来源删除。

### 3.2 P1：历史版本受控恢复

这是对文档 CRUD 的直接补强，体量中等，排在快照对账之后。

首版只恢复**本地管理文档**，因为本地文档由 RAG 服务拥有主数据。外部托管文档的历史
状态必须由外部来源重新发布，避免 RAG 服务越权改变来源真相；服务可以继续提供版本
详情和可复制的快照内容，但不直接替外部 connector 写回。

首版只允许 `snapshotCompleteness=FULL` 的版本恢复。旧的
`CONTENT_AND_METADATA_ONLY` 版本继续保留为审计/diff 数据，不猜测缺失字段。

### 3.3 P1（较大）：本地关键词索引与远程向量解耦

该项价值高但改变检索存储路径，必须在前两项稳定后单独实施，不能与快照协议混在一个
发布中。目标不是保留旧正文结果，而是：

```text
新正文提交
  -> 旧派生结果立即退出
  -> 本地 chunk/full-text READY
  -> embedding provider 成功后 vector READY
  -> 两者均可用时整体 READY
```

不采用“provider 失败时继续返回旧正文向量”的做法；旧内容会造成事实过期和引用错误。

## 4. P0 设计：权威来源全量快照对账

### 4.1 核心语义

一次 run 表示：

> 对某个 `collectionKey + sourceNamespace`，connector 声明一份受控快照中的完整对象
> 集合；run 完成后，快照中不存在且没有被更新事件保护的对象才可能被 tombstone。

run 的正确性必须建立在来源快照模式上：

| `snapshotMode` | 含义 | 允许的 `missingPolicy` |
|---|---|---|
| `ONLINE_CUT` | 先建立 RAG run，再由来源建立一致性 cut；cut 后新事件拥有更高服务端 mutation sequence | `NONE` / `TOMBSTONE` |
| `OFFLINE_MANIFEST` | manifest 在 begin 前生成；服务不能证明其期间来源没有变化 | 只允许 `NONE` |
| `EXCLUSIVE_OFFLINE` | connector 明确保证 manifest 生成到 complete 期间来源独占写入 | `NONE` / `TOMBSTONE`，需显式确认 |

API 不为这两个安全关键字段提供隐式组合默认值。connector 必须明确声明模式和 missing
策略。reference client 对一个调用前已经存在的静态 manifest 只使用
`OFFLINE_MANIFEST + NONE`；只有先 begin、再由来源建立一致性 cut 并生成 manifest 的两阶段
流程才能使用 `ONLINE_CUT + TOMBSTONE`。如果 connector 无法建立一致性 cut，服务必须拒绝
`TOMBSTONE`，不能“尽力删除”。

### 4.2 API 契约

#### Begin

```http
POST /api/v1/rag/document-sync-runs
X-RAG-Sync-Lease: <opaque lease token>
```

```json
{
  "collectionKey": "customer-42:manual:v3",
  "sourceNamespace": "cms-main",
  "clientRunId": "cms-snapshot-2026-08-19T12:00:00Z",
  "snapshotMode": "ONLINE_CUT",
  "missingPolicy": "TOMBSTONE",
  "leaseSeconds": 900
}
```

规则：

- client 在请求前生成至少 128 bit 随机 lease token；服务只保存 token hash；
- `snapshotMode` 和 `missingPolicy` 必填，不由服务猜测；
- `clientRunId` 在 `(collection, namespace)` 内幂等；
- 同 token + 同请求重放返回同一 run，不增加 generation 或 mutation sequence；
- 同 `clientRunId` + 不同 token 返回 `409 RUN_LEASE_CONFLICT`；
- 未过期 active run 返回 `409 ACTIVE_SYNC_RUN_EXISTS`；
- 过期 run 只能被条件 CAS 标记为 `EXPIRED` 后，再安装新的 active run；
- 返回 `runId`、`syncGeneration`、`snapshotStartSequence`、lease expiry、状态和 polling
  地址；
- token 不进入日志、URL、数据库明文或错误响应。

#### Batch upsert

```http
POST /api/v1/rag/document-sync-runs/{runId}/batch-upsert
X-RAG-Sync-Lease: <same token>
```

每项使用已有完整期望状态：

- `documentKind=TEXT`：`title`、`content`、source、metadata、sourceRevision；
- `documentKind=JSON_RECORD`：`title`、`retrievalText`、`jsonbPayload`、source、metadata、
  sourceRevision；
- 两者都要求 `externalId`，并继承 run 的 Collection/namespace，不允许 item 改写范围；
- 默认 `embeddingPolicy=ASYNC`；
- 每批 bounded，建议首版最多 100 项、总正文/描述不超过现有单批限制；
- item 成功后记录 `lastSeenSyncRunId` 和规范化 fingerprint，不把正文复制到 run ledger；
- item 已被 begin 之后的增量 mutation 更新时，必须返回 `SKIPPED_NEWER_MUTATION`，不能被
  旧快照覆盖；
- item 精确重放返回原 item 结果，不重复创建 revision/job。

#### Preview missing

```http
POST /api/v1/rag/document-sync-runs/{runId}/preview-missing
X-RAG-Sync-Lease: <same token>
```

preview 只返回安全摘要：

- candidate count；
- 按 document kind 的计数；
- 最多 bounded 的 external identity 摘要；
- 被新 mutation 保护的对象数；
- 不返回完整正文、JSONB payload 或不可见 Collection 的 document ID。

preview token 绑定 run generation 和 missing candidate fingerprint；complete 必须携带
preview token，避免 connector 在 preview 后悄悄改变 manifest 但仍执行原删除。

#### Complete

```http
POST /api/v1/rag/document-sync-runs/{runId}/complete
X-RAG-Sync-Lease: <same token>
```

```json
{
  "previewToken": "opaque-token",
  "confirmMissingCount": 18
}
```

规则：

- `NONE`：封存 run，不产生 missing tombstone；
- `TOMBSTONE`：只处理 `sourceMutationSequence <= snapshotStartSequence` 且本 run 未成功
  看到的对象；
- complete 本身必须再次取得 namespace mutation sequence，防止 preview 与 complete 之间
  插入未受保护的 mutation；
- 被 complete 之前的新 mutation 修改过的对象不能 tombstone；
- complete 不等待 embedding；响应返回 accepted/upserted/unchanged/skipped/tombstoned、
  job queued、lifecycle failed 等摘要；
- 重复 complete 返回同一最终摘要；
- 过期或已 abort 的 run 不能完成；
- 超过删除保护阈值（推荐默认 1,000 或当前 namespace 活跃文档的 20%，取较小者）时，
  没有显式 `confirmMissingCount` 不允许执行 tombstone，只允许 preview；
- sync-run 的 batch item 必须提供非空 `sourceRevision`，即使兼容性的单条 JSON-record
  upsert 仍允许省略它；这样权威对账不会把没有来源版本的 legacy 行误判为可安全删除。
- reconciliation tombstone **不得伪造或覆盖 `sourceRevision`**。服务应保留最近一次已接受的
  来源 revision，并另存 server-owned 的 `reconciliation_tombstone_run_id` 与
  `deletion_origin=RECONCILIATION`；显式来源删除则使用
  `deletion_origin=SOURCE` 并由 connector 提供新的删除 revision。缺少来源 revision 的
  legacy 文档不自动 tombstone，只在 run 摘要中报告为 excluded/unresolved。
- 来源恢复时，connector 以该文档保留的最近来源 revision 作为
  `expectedSourceRevision`。如果来源返回新的 revision，走普通 CAS；如果来源对象重新出现但
  revision 未改变，只有在 `deletion_origin=RECONCILIATION` 且完整受管状态与保存快照一致时，
  才允许同 revision 恢复。显式来源 tombstone 不允许该例外。服务端 run marker 只用于审计
  和幂等，不作为来源业务版本。这样 `sourceRevision` 始终属于来源系统，RAG 不会伪造或
  强迫上游制造主数据版本。

#### Abort / status

```http
POST /api/v1/rag/document-sync-runs/{runId}/abort
GET  /api/v1/rag/document-sync-runs/{runId}
GET  /api/v1/rag/document-sync-runs
```

abort 只结束 run，不修改文档；status 只返回 caller ACL 可见的计数和状态。列表默认按
namespace/Collection 过滤并分页。

### 4.3 数据模型与 V42 迁移

建议新增 `V42__document_sync_runs.sql`，只做 expand，不删除 V41 字段：

`rag_document_sync_runs`：

- `id UUID`；
- `collection_id`、`source_namespace VARCHAR(128)`；
- `client_run_id`、`lease_token_hash`；
- `sync_generation`、`snapshot_start_sequence`、`complete_sequence`；
- `snapshot_mode`、`missing_policy`、`status`；
- `lease_expires_at`、`preview_token_hash`、`preview_fingerprint`；
- counts、created/updated/completed/aborted/expired timestamps；
- unique `(collection_id, source_namespace, client_run_id)`；
- active run partial unique index；
- 普通查询索引 `(collection_id, source_namespace, status, created_at)`。

`rag_document_sync_run_items`：

- `run_id`、规范化 `external_id VARCHAR(255)`、`document_kind`；
- `item_fingerprint`、`source_revision`、`document_id`；
- `status`：`APPLIED`、`UNCHANGED`、`SKIPPED_NEWER_MUTATION`、`FAILED`；
- `seen_at`、安全错误码；
- unique `(run_id, external_id)`；
- 不保存正文、JSONB payload 或 lease token。

`rag_documents` 增加：

- `last_seen_sync_run_id UUID NULL`；
- `last_seen_sync_generation BIGINT NULL`。
- `reconciliation_tombstone_run_id UUID NULL`；
- `deletion_origin VARCHAR(32) NULL`，只允许 `SOURCE` / `RECONCILIATION`；V42 将已有
  `source_deleted_at IS NOT NULL` 行回填为 `SOURCE`。

该字段只作为 missing 计算的加速/审计提示，最终资格仍必须同时判断
`source_mutation_sequence <= snapshotStartSequence`，不能只相信 run ID。
首版不硬删除已被文档 marker 引用的 sync run；run retention/归档另行规划，不能级联清除
文档上的 reconciliation 审计来源。

### 4.4 并发与无悲观锁实现

所有涉及同一 namespace 的短事务使用统一顺序：

1. `INSERT ... ON CONFLICT DO NOTHING` 确保 namespace 协调行存在；
2. 通过条件 `UPDATE rag_document_source_namespaces ... RETURNING mutation_sequence` 获取
   当前 mutation sequence；
3. 再读写 run/document/item；
4. 事务提交。

禁止：

- `SELECT ... FOR UPDATE`；
- `SKIP LOCKED`；
- advisory lock；
- “先查询 MAX 再 INSERT”；
- 依靠 Java synchronized 保证跨实例互斥。

begin、普通增量 mutation、snapshot item、complete 之间的覆盖规则：

- begin 先拿到 `snapshotStartSequence`；
- begin 之后的增量 mutation 必须拿到更高 sequence；
- snapshot item 只能更新 sequence 不高于 snapshot start 的旧状态；
- complete 只 tombstone 仍不高于 start 且未被当前 run 看见的对象；
- 唯一约束和条件 UPDATE 失败时返回明确 409/重试语义。

lease 续租使用：

```sql
UPDATE rag_document_sync_runs
SET lease_expires_at = CURRENT_TIMESTAMP + (? * INTERVAL '1 second'),
    updated_at = CURRENT_TIMESTAMP
WHERE id = ?
  AND lease_token_hash = ?
  AND status = 'ACTIVE'
  AND lease_expires_at >= CURRENT_TIMESTAMP
RETURNING id, lease_expires_at
```

### 4.5 Reference client 与外部最佳实践

在 `examples/external-sync-client/sync_client.py` 增加静态 manifest 与在线 cut 两类命令，
保留现有 `apply-events` 不变。

对调用前已经存在的 manifest，安全默认只做 upsert，不推断删除：

```bash
python3 examples/external-sync-client/sync_client.py sync-manifest \
  --manifest examples/external-sync-client/sample-manifest.jsonl \
  --checkpoint .external-sync/catalog-snapshot.sqlite3 \
  --snapshot-mode OFFLINE_MANIFEST \
  --missing-policy NONE \
  --dry-run
```

需要 missing tombstone 时使用显式两阶段流程：

```bash
python3 examples/external-sync-client/sync_client.py begin-online-snapshot \
  --checkpoint .external-sync/catalog-online.sqlite3 \
  --missing-policy TOMBSTONE

# begin 成功后，connector 才从来源建立一致性 cut，并生成不可变 manifest。

python3 examples/external-sync-client/sync_client.py complete-online-snapshot \
  --manifest /path/generated-after-begin.jsonl \
  --checkpoint .external-sync/catalog-online.sqlite3 \
  --confirm-source-cut-after-begin \
  --dry-run
```

`--confirm-source-cut-after-begin` 是 connector 对来源一致性的显式声明，不是服务端能够自行
验证的事实。client 至少检查 manifest 文件在 begin 之后创建、fingerprint 未变化，但文件
时间只能发现明显误用，不能替代来源系统自己的 snapshot/transaction/cursor 语义。没有该
确认时，online complete 只能按 `NONE` 封存，不能 tombstone。

client 必须：

- 在本地 checkpoint 只保存 run ID、token hash/受保护 token、offset、input file fingerprint、
  last response digest；不保存 API key、完整正文或 JSONB payload；
- 将 manifest 当作不可变输入；恢复时校验文件 fingerprint；
- 对预先存在的静态 manifest 固定使用 `OFFLINE_MANIFEST + NONE`，拒绝
  `OFFLINE_MANIFEST + TOMBSTONE`；
- online 模式必须先持久化 begin checkpoint，再由调用方建立来源 cut 和生成 manifest；
- begin/batch/preview/complete 都使用同一 lease token；
- 网络断开时精确重放同一请求；
- complete 前先 preview，删除数量变化时停止并要求人工/显式确认；
- 对 409 重新读取来源和 RAG 状态，不自动以 last-write-wins 覆盖；
- 只在 complete 成功后提交“快照已对账” checkpoint；
- 继续用 `ASYNC`，另行轮询 run/document/Collection readiness；
- 不把 server reconciliation marker 当作 `sourceRevision`。来源恢复时以响应中的最近来源
  revision 作为 `expectedSourceRevision`；来源 revision 未变化时精确重放 manifest item，
  由服务按“仅 reconciliation tombstone + 完整状态一致”的窄规则恢复；遇到 409 时重新读取
  来源和 RAG 状态。

需要新增：

- `sample-manifest.jsonl`；
- `manifest.schema.json`；
- `sync_client.py` 的命令、checkpoint 和错误分类单测；
- 中英文 `docs/external-document-sync-client-guide*` 的 manifest 章节。

## 5. P1 设计：历史版本受控恢复

### 5.1 API

```http
POST /api/v1/rag/documents/{documentId}/versions/{versionNumber}/restore
```

```json
{
  "expectedDocumentRevision": 12,
  "embeddingPolicy": "ASYNC",
  "visibilityMode": "KEEP_CURRENT"
}
```

推荐字段：

- `expectedDocumentRevision`：必填；
- `embeddingPolicy`：沿用 `SYNC/ASYNC/SKIP`，默认 `ASYNC`；
- `visibilityMode`：`KEEP_CURRENT`（默认）或 `SNAPSHOT`；
- 首版固定只允许 `snapshotCompleteness=FULL`。

契约：

- 仅允许 `externalId` 为空的本地文档；
- 版本属于目标 document 且存在，否则 404；
- 版本不是 `FULL`，返回 `409 VERSION_NOT_RESTORABLE`，不猜测缺失字段；
- 当前 revision 不匹配返回 `409 DOCUMENT_REVISION_CONFLICT`；
- 不修改历史版本行、不回拨 `documentRevision`、不重用历史 `versionNumber`；
- 将快照内容作为新的完整 desired state 写入当前 document；
- 变更后创建一个新的 `RESTORE` 版本快照；
- content/document kind 变化走现有 derivation impact 和 durable job；
- 只变 metadata/title/source/Collection 时不调用 provider；
- `KEEP_CURRENT` 不改变当前 enabled 状态；需要重新上线时显式调用现有 restore/enable
  契约或使用 `visibilityMode=SNAPSHOT`；
- Collection 变化必须同时通过旧 Collection 和新 Collection ACL；
- 返回统一 `DocumentMutationResponse`，包括新的 revision、version、lifecycle 和 job。

外部文档的历史恢复不进入首版 API。外部 connector 应把恢复后的状态作为来源的新完整
upsert；这样来源 revision 仍由来源拥有，RAG 不会伪造外部主数据。

### 5.2 实现边界

不要在 `RagDocumentController` 或 `VersionHistoryModal` 中复制 mutation 逻辑。推荐：

```text
DocumentVersionService
  -> loadRestorableFullSnapshot(...)

DocumentMutationService
  -> restoreLocalFromVersion(documentId, versionNumber, request)
       - require local + expected documentRevision
       - validate FULL snapshot and ACL
       - classify content/metadata/scope/visibility impact
       - persist current document + revision + new RESTORE snapshot
       - dispatch existing embedding coordinator
```

版本服务只负责读取/资格判断；所有写入仍走 `DocumentMutationService`。

### 5.3 WebUI

在现有 `VersionHistoryModal` 增加：

- 仅对本地管理文档显示 Restore action；
- 对 `FULL` 版本显示可恢复，对旧兼容版本显示 disabled 状态和原因；
- 二次确认中明确“恢复会创建新版本，不删除后续历史”；
- 提交当前 document revision、策略和 visibility mode；
- 成功后关闭/刷新版本列表和文档列表；
- 对 409 自动刷新当前文档和版本状态，不自动覆盖；
- 以 DOM 文本、按钮 disabled/visible 状态和网络 JSON 验证，不使用截图。

## 6. P1（较大）设计：本地关键词与远程向量解耦

### 6.1 目标状态

新增的公开生命周期组合：

| local index | vector embedding | `searchability` |
|---|---|---|
| `READY` | `READY` | `READY` |
| `READY` | `QUEUED/FAILED/NOT_REQUESTED` | `KEYWORD_ONLY` |
| `QUEUED/FAILED` | 任意 | `INDEXING` 或 `FAILED`，不可返回旧正文 |
| `SKIPPED` | `SKIPPED` | `NOT_REQUESTED` |
| 任意 | 任意 | disabled/tombstone 仍为 `DISABLED` |

“新正文先关键词可用”不等于返回旧正文向量。每个派生结果都必须匹配当前
`contentHash + documentKind + chunkerVersion + generation`。

### 6.2 存储方案

推荐新增 `V43__local_keyword_derivation.sql` 或在 restore 的无 schema 发布后使用下一可用
版本：

`rag_document_chunks`：

- `id BIGSERIAL`；
- `document_id`；
- `request_generation`；
- `content_hash`、`document_kind`、`chunker_version`；
- `chunk_index`、`chunk_text`、start/end position、metadata；
- created_at；
- 唯一 `(document_id, request_generation, chunk_index)`；
- 普通 `document_id/generation` 索引；
- pg_trgm/tsvector 索引建在该表，而不是继续只建在 `rag_embeddings`。

`rag_document_keyword_state`：

- `(document_id)` 主键；
- 当前 generation/hash/chunker；
- status：`QUEUED`、`PROCESSING`、`READY`、`FAILED`、`NOT_REQUESTED`；
- chunk_count、error、active job、timestamps；
- 使用条件 UPDATE/CAS，不使用显式行锁。

`rag_embeddings` 增加 nullable `chunk_id` 和/或 derivation generation 兼容字段；V43 expand
期间保留现有 document/chunk_index 查询，contract 阶段再收紧新写入。

不要立刻物理删除 `rag_embeddings` 中的旧向量。先让 freshness 和 generation 排除旧结果，
物理清理由独立 retention/GC 规划处理。

### 6.3 任务流程

复用持久化 embedding job 的 durable lease，但把本地 chunk 写入与 provider 调用分成两个
明确阶段：

1. 文档 mutation 在同一事务中更新当前 generation，并创建需要的 derivation job；
2. worker claim 后，根据 `DocumentDerivationDescriptorProvider` 生成 deterministic chunks；
3. 在短事务中以 generation/hash/chunker CAS 原子替换 `rag_document_chunks`，将 keyword state
   置为 `READY`；
4. 若策略要求远程 embedding，再调用 provider；
5. provider 成功后以同一 generation/lease commit fence 替换 vectors，vector state 为
   `COMPLETED`；
6. provider 失败时保留 keyword chunks，vector state 为 `FAILED`，公开 lifecycle 为
   `KEYWORD_ONLY`、`retryable=true`；
7. 旧 worker 或旧 generation 不能改写当前 chunk/state/vector；
8. disable/tombstone/hard delete 立即排除/清理当前文档的 keyword 和 vector 结果；
9. retry 只重试当前 generation，不重复生成无界历史 chunk。

### 6.4 API 与兼容

现有 `embeddingPolicy` 继续只表示远程 provider 是否运行：

- `SYNC`：本地 chunk 完成且远程 embedding 在有界等待内完成才返回 READY，否则返回
  accepted/indexing；
- `ASYNC`：提交 durable job 后返回；
- `SKIP`：不调用远程 provider，但在新 keyword-index feature 开启时仍允许本地
  `KEYWORD_ONLY`；
- 如调用方确实需要完全不生成本地派生，增加显式 `keywordIndexPolicy=SKIP`，不能让
  `SKIP` 的含义继续隐式承担两种职责。

兼容 rollout：

1. 首个发布默认关闭 `rag.document-lifecycle.keyword-index.enabled`，保持当前行为；
2. shadow/后台生成 chunks 并比较结果数量与延迟；
3. 对新 client 和 WebUI 逐步开启，返回生命周期字段；
4. 确认 goldenset 和 provider 失败演练通过后，再将新默认设为开启；
5. 旧 client 看到 `KEYWORD_ONLY` 时仍可按 HTTP 成功处理，只有需要语义检索的流程应检查
   `embeddingStatus`。

### 6.5 检索层

新增 `KeywordChunkRepository`/provider，保留现有 `FulltextSearchProvider` SPI：

- `PgTrgmFulltextProvider`、`PgEnglishFtsProvider`、`PgJiebaFulltextProvider` 读取
  `rag_document_chunks`；
- 共享 `RetrievalScopeSql`、metadata/payload filters、Collection ACL；
- freshness 条件必须绑定当前 keyword state/generation/hash/chunker；
- vector provider 继续读取 `rag_embeddings` 并使用独立 vector freshness；
- hybrid fusion 允许只有 keyword 分支返回，vector 分支状态在 diagnostics 中明确为
  unavailable/failed；
- 不伪造 vector score，不把 keyword-only 结果标为 semantic；
- retrieval trace 增加 `localIndexStatus`、`embeddingStatus`、fallback reason。

## 7. 不纳入本批次

以下事项明确延后，不因它们看起来“平台化”就抢占本批次：

- API Key 加固、配额、用量/计费和多实例吊销；
- XML/Office 导入；
- 固定默认 Collection；
- `EACH_COLLECTION` 覆盖召回；
- GraphRAG、多模态；
- 每 Collection 独立 embedding 模型；
- 重新发明 Spring AI 的 Chat/Tool/Memory 基础设施；
- 由 OpenAI 兼容协议隐式猜测 Collection 范围。

OpenAI 兼容服务端若后续扩展，Collection scope 仍必须通过已有请求级 `rag.scope` 或
`X-RAG-Collection-Key` 机制表达；本批次不改变该协议。

## 8. 失败、回滚与兼容策略

### 8.1 Feature flags

建议：

```yaml
rag:
  document-lifecycle:
    sync-runs:
      enabled: false
    version-restore:
      enabled: false
    keyword-index:
      enabled: false
```

- P0/P1 API 默认关闭，迁移先 expand；
- 关闭 flag 后保留已创建数据，只停止新入口；
- keyword-index 关闭时，旧检索 SQL 继续运行；
- 不删除 V42/V43 表，使用 roll-forward 修复；
- V41 之后不承诺直接回退到不理解新 schema 的旧应用；回滚是关闭 flag + 部署兼容应用，
  必要时恢复数据库备份。

### 8.2 数据安全

- 任何 CAS 冲突都不自动覆盖；
- snapshot complete 的大删除保护必须 fail closed；
- run lease/preview token 失败不得执行 tombstone；
- restore 只能从 `FULL` 快照构造完整状态；
- provider 失败不得回滚已接受文档，也不得重新开放旧正文；
- 所有错误日志只记录安全 identity、run ID、job ID 和 error code，不记录 secret、全文或
  JSONB payload。

## 9. 验收与自动化验证

实施时必须先编写一次性验收矩阵，再运行硬门禁，不能在 review 阶段发现一个问题就临时
添加一个测试并无限全量重跑。

### 9.1 P0 后端 PostgreSQL E2E

从 V41 数据库升级并覆盖：

1. begin/batch/preview/complete 成功；
2. complete 精确重放返回同一摘要；
3. begin 响应丢失后用相同 `clientRunId + lease` 恢复；
4. 同 namespace active run 冲突、过期 run、abort；
5. `ONLINE_CUT + TOMBSTONE` 删除 missing；
6. `OFFLINE_MANIFEST + TOMBSTONE` 被拒绝；
7. 静态 manifest client 默认 `OFFLINE_MANIFEST + NONE`，不能通过默认值产生删除；
8. online client 在 begin 前已有 manifest、缺少 cut 确认或 manifest 被替换时拒绝 tombstone；
9. preview 后 manifest 改变导致 preview token 失效；
10. complete 期间并发 webhook/CDC 更新不会被旧 snapshot 覆盖或误删；
11. 不同 namespace 不互相 tombstone；
12. reconciliation tombstone 保留来源 revision，并写入 run marker/deletion origin；
13. 同 revision 只可恢复完整状态一致的 reconciliation tombstone，显式来源 tombstone
    仍要求新的来源 revision；
14. run ACL、API Key Collection ACL、跨 Collection 越权和 anti-enumeration；
15. TEXT/JSON_RECORD kind 不可互换；
16. embedding job 与文档 mutation 的 generation/fencing 正确；
17. provider 失败只影响 lifecycle/readiness，不回滚 source mutation；
18. 大删除阈值 fail closed；
19. 无 `FOR UPDATE`、`SKIP LOCKED`、advisory lock。

### 9.2 P1 restore 后端与 WebUI

后端：

1. FULL 快照 restore 创建新 document revision 和 RESTORE 版本；
2. 旧版本、后续版本均保留；
3. content restore 调度新 generation，metadata-only restore 不调用 provider；
4. stale revision、越权 Collection、外部文档、旧不完整快照均按契约失败；
5. restore 与并发 PATCH 只允许一个通过 CAS；
6. disable/restore 与 embedding worker race 不可让旧 job commit。

前端：

- `tsc`；
- production build；
- Vitest 覆盖 FULL/旧快照 disabled、确认、409 刷新；
- Mock Playwright 只断言 DOM 可见性、可访问状态、网络请求/响应和 JSON；
- 不使用截图。

### 9.3 Keyword/vector decoupling

后端 PostgreSQL E2E：

1. content mutation 排除旧 chunk；
2. local chunk 成功 + provider 失败时可 keyword search，不能 vector search；
3. provider retry 成功后进入 READY；
4. old generation worker commit 被拒绝；
5. Collection scope、metadata/payload filter 和 JSON record retrieval 保持；
6. `SKIP`/`keywordIndexPolicy=SKIP` 的边界明确；
7. chunker version 改变会重新生成 local chunks 和 vectors；
8. legacy `rag_embeddings` 数据 expand/migrate/contract 可回滚到兼容模式；
9. 检索诊断正确报告 keyword-only，不伪造语义分数。

### 9.4 一键脚本与文档

实施完成后新增三个聚焦脚本，并扩展当前已经存在的聚合编排器：

- `scripts/verify-document-sync-runs.sh`
- `scripts/verify-document-version-restore.sh`
- `scripts/verify-keyword-index-fallback.sh`
- 扩展 `scripts/verify-next-high-value-features.sh`，在保留现有检索诊断、过滤、
  embedding operations、受管质量和无悲观锁门禁的基础上编排上述新增门禁

脚本必须：

- 明确区分 skipped（Docker/数据库不可用）与 failed；
- 支持 `TESTCONTAINERS_PG_IMAGE` 和境内镜像配置，不把国内镜像硬编码进 Dockerfile；
- 输出测试类、迁移版本、摘要和日志路径；
- 不输出 API key、token、完整正文或 JSONB payload；
- 允许外部 disposable PostgreSQL URL，但必须使用显式 clean confirm；
- 可被 `docs/testing-guide*` 和 `docs/developer-reference*` 复制运行。

## 10. 实施顺序与提交边界

### Phase 0：文档与契约准备

- 先更新 `docs/rest-api*`、`docs/external-document-sync-client-guide*`、`docs/project-context*`
  和 `docs/TODO*`；
- 新增 API DTO/错误码 OpenAPI 契约测试；
- 新增 progress 文档，记录迁移前检查、验收矩阵和回滚点。

### Phase 1：P0 sync runs

- V42 expand migration；
- run/item repository、service、controller；
- CAS/lease/preview/complete；
- reference client `sync-manifest`；
- PostgreSQL E2E 和 client unit tests；
- WebUI 只增加只读 run/readiness 状态，若不影响外部 connector 可延后。

### Phase 2：P1 version restore

- restore service/controller；
- API DTO/OpenAPI；
- WebUI VersionHistoryModal；
- 后端集成测试和 Mock Playwright；
- 双语长青文档同步。

### Phase 3：P1 keyword/vector decoupling

- V43+ expand migration；
- local chunk table/state；
- worker 两阶段派生；
- fulltext provider 切换；
- hybrid diagnostics/lifecycle；
- migration rehearsal、quality goldenset、故障演练。

每个 Phase 都必须独立通过基本集成门禁后再进入下一 Phase。不要把三项压成一个大提交；
每项完成后才把其事实提炼进 live docs，并将本规划/进度账本在用户明确要求归档或工作
生命周期结束时移入 `docs/drafts/archive/`。

## 11. 实施后必须更新的长青文档

每个已实施行为都必须同步中英文版本：

- `docs/project-context-zh-CN.md` / `.md`：当前事实、状态组合、并发边界；
- `docs/rest-api-zh-CN.md` / `.md`：新 endpoint、错误码、响应、scope/ACL；
- `docs/external-document-sync-client-guide-zh-CN.md` / `.md`：manifest、lease、快照模式、
  checkpoint、删除保护；
- `docs/developer-reference-zh-CN.md` / `.md`：一键脚本和 PostgreSQL E2E；
- `docs/testing-guide-zh-CN.md` / `.md`：测试门禁和非截图前端证据；
- `docs/architecture-zh-CN.md` / `.md`：文档主记录与本地/远程派生拓扑；
- `docs/TODO-zh-CN.md` / `.md`：移除已完成项，只保留后续缺口；
- `docs/index-zh-CN.md` / `.md`：只链接活跃规划或 live docs，不链接单份归档稿。

## 12. 完成定义

本批次不能以“代码能编译”作为完成。每个 Phase 的完成定义是：

1. 代码、迁移、API 契约、测试和 client 示例一致；
2. 相关 PostgreSQL E2E 通过；
3. 后端 `mvn clean compile test-compile` 通过；
4. WebUI 修改时 `tsc`、production build、Mock Playwright 通过；
5. `scripts/verify-no-pessimistic-locks.sh` 和 `./scripts/verify-project-docs.sh` 通过；
6. 一键验收脚本可复现并记录证据；
7. 双语长青文档已更新；
8. 只读、限定范围的三轮代码检查均未发现影响正确性、兼容性、成本、安全或数据一致性的
   问题；
9. 关闭 feature flag 后旧 API 行为仍符合当前文档；
10. 工作区提交前不包含本地 Agent 状态、secret 或未声明的临时产物。

达到以上条件前，不应把本批次描述为“已完成”或“生产就绪”。
