# Collection 受保护清理与退役实施规划

> 状态：待实施
> 日期：2026-08-27
> 配套进度：[2026-08-28_COLLECTION_PURGE_AND_RETIREMENT_PROGRESS.md](2026-08-28_COLLECTION_PURGE_AND_RETIREMENT_PROGRESS.md)
> 交付规则：[规划、实施与验收工作流](../../delivery-workflow-zh-CN.md)

## 1. 目标与范围

当前系统已经具备 Collection 软删除、外部文档稳定身份、来源 tombstone、同步运行、
文档版本、embedding job、独立关键词索引和文档迁移地址退休能力，但还不能在不破坏这些
约束的情况下完成一座知识库的最终清理：

- 普通 Collection 删除会解除本地文档关联；
- 只要仍有外部托管文档，普通删除就会拒绝，避免稳定外部身份被静默破坏；
- 外部文档的 source delete 只创建 tombstone，不删除正文、版本和派生数据；
- 直接物理删除 Collection 还会受到创建幂等、聚合观测等历史引用约束，并会释放
  `collectionKey`，违反现有“key 不重命名、不复用”的契约。

本批次实现一个通用的、受保护的 Collection 内容清理和退役流程：

1. 以 Collection key 创建短期、owner-scoped 的清理预览；
2. 预览列出将被永久删除的文档及派生数据计数，并冻结 Collection 生命周期版本；
3. 使用一次性确认令牌和预览指纹 apply；
4. 在单个短事务中通过数据库级级联及必要的控制表清理，物理删除 Collection 下的
   所有 `rag_documents`，并删除持久化了这些文档 citation/content 的会话级产物；
5. 保留最小化的 `rag_collection` tombstone，将它标记为已删除且已退役，保持 key 永久
   占用，同时清空可能承载业务内容的 name/description/metadata；
6. 退役后不可 restore、写入、检索、导出、clone 或再次用于外部同步；
7. 保留不含文档正文或正文衍生自由文本的计费/usage、HTTP 聚合、检索评估和可追溯
控制面历史；删除可精确归属于目标文档/Collection 且可能保存标题、Collection 名称或
用户摘录的反馈、Document 审计和 Collection 审计行。

这里的“内容清理”包括系统保存的原始正文、JSON payload、版本快照、chunk、embedding
输入文本，以及 Chat citation/tool transcript 中复制的正文片段。模型生成的回答本身无法
可靠区分“仅由该 Collection 推导的文本”和其他会话内容，因此只要某个持久化会话曾引用
待清理文档，就删除该 owner-scoped 会话的业务历史、Spring AI memory、摘要和幂等 replay，
而不是只过滤 citation 后继续保留可能含摘录的回答。

本批次不实现异步大任务队列、对象存储清理、Collection key 回收、逐文档选择性 purge
和跨 Collection 批量 purge。`fs_files` 是独立管理的原始 PDF、转换 Markdown 和资源
产物，没有 Collection/document 外键；`original_filename`、`source` 或路径字符串都不是
可安全推断所有权的关系，因此本批次不会猜测并删除 `fs_files`。文件产物清理需要未来单独
定义显式注册关系和生命周期契约。大规模异步清理可在后续以相同的 preview/lease/CAS
模型扩展。

## 2. 已确认的代码与数据事实

实现必须以以下代码和迁移为准，而不能假设旧 plan 仍准确：

- Collection 生命周期：
  - [RagCollectionService.java](../../../spring-ai-rag-core/src/main/java/com/springairag/core/service/RagCollectionService.java)
    的 `deleteCollection` 是带 Collection version CAS 的软删除，并拒绝外部托管文档；
  - [RagCollectionController.java](../../../spring-ai-rag-core/src/main/java/com/springairag/core/controller/RagCollectionController.java)
    已有按 key 和数字 ID 的 delete/restore 路由；
  - [RagCollection.java](../../../spring-ai-rag-core/src/main/java/com/springairag/core/entity/RagCollection.java)
    的 `collectionKey` 不可更新，且仓库唯一约束覆盖已删除行。
- 文档 mutation：
  - [DocumentMutationService.java](../../../spring-ai-rag-core/src/main/java/com/springairag/core/service/DocumentMutationService.java)
    的 `hardDeleteLocal` 只允许本地文档，并取消活动 embedding job、删除向量后物理删文档；
  - 外部文档通过 `tombstoneExternal` 进入禁用状态；外部身份是
    `collectionKey + sourceNamespace + externalId`。
- 派生数据：
  - V1 的 `rag_embeddings.document_id` 使用默认 `RESTRICT`，现有迁移没有改成
    `ON DELETE CASCADE`，因此 purge 必须先显式删除向量行；
  - V25 的 `rag_document_embedding_state`、V33/V40 的 `rag_embedding_jobs`；
  - V43 的 `rag_document_chunks`、`rag_document_local_index_state`；
  - V9/V29/V40 的 `rag_document_versions`；
  - 除 V1 向量表外，上述状态、任务、chunk、local state 和 version 表对
    `rag_documents` 使用 `ON DELETE CASCADE`，可由删除主记录清理。
- 外部同步/迁移：
  - V42 的 `rag_document_sync_runs` 对 Collection `ON DELETE CASCADE`，其 item 对文档
    使用 `ON DELETE SET NULL`；
  - V44 的 `rag_document_relocated_addresses` 对文档使用 `ON DELETE SET NULL`，对 source
    和 target Collection 使用默认 `RESTRICT`；
  - [DocumentSyncRunService.java](../../../spring-ai-rag-core/src/main/java/com/springairag/core/service/DocumentSyncRunService.java)
    和 [DocumentRelocationService.java](../../../spring-ai-rag-core/src/main/java/com/springairag/core/service/DocumentRelocationService.java)
    使用 active-run 检查、CAS 和 lease，不能在 purge 中绕过。
- 其他持久化数据：
  - V45 的 `rag_derivation_repair_previews` 直接引用 Collection，items 的
    `document_id` 没有 `ON DELETE CASCADE`；
  - V52 的 `rag_collection_provisioning_operation` 和 V54 的
    `rag_api_collection_operation_hourly` 引用 Collection，必须保留 Collection 行；
  - V40 的 `rag_document_idempotency_operations.result_document_id` 为 `SET NULL`；
  - `rag_user_feedback` 明确保存 retrieved/selected document IDs、自由文本 `comment`
    和扩展 `metadata`；评论可能摘录检索内容，因此引用待清理文档的反馈行必须整体删除，
    不能作为无内容历史保留；
  - 单文档审计描述会保存文档标题；`entity_type=Document` 且 `entity_id` 精确等于待清理
    document ID 的审计行必须删除。批量审计只保存计数，Collection 审计只描述保留的
    tombstone，可继续保留；
  - 检索日志、A/B 结果、检索评估、受管评估、usage 和 HTTP 聚合按其正式写入路径只保存
    查询、文档/稳定身份、分数、配置或计数，不复制检索 chunk、文档 JSON payload 或模型
    回答，可作为历史事实保留；
  - `rag_chat_history.sources` 的 `ChatSource.chunkText` 最多保存 2000 字正文片段，
    `metadata.toolTranscript` 还能保存 agent 工具结果；`rag_chat_turn_operations.response_payload`
    会再次保存包含 `chunkText` 的完整 Chat response；
  - `spring_ai_chat_memory` 和 `rag_chat_memory_summary` 保存回答或会话摘要。它们没有可靠的
    逐文档归因，不能在 purge 时只删除某一个 citation；
  - V32 后的 durable chat history 同时拥有 owner、session 和 source snapshot，适合建立
    规范化文档引用索引，并据此执行会话级清理。
- 安全：
  - [ApiKeyAuthFilter.java](../../../spring-ai-rag-core/src/main/java/com/springairag/core/filter/ApiKeyAuthFilter.java)
    区分 environment root、数据库 principal、legacy static 和 auth-disabled；
  - [ApiAccessPolicy.java](../../../spring-ai-rag-core/src/main/java/com/springairag/core/security/ApiAccessPolicy.java)
    目前只有 `RAG_READ`/`RAG_WRITE` 能力，不能把高风险 purge 随意交给普通
    `RAG_WRITE`；
- [ChatPrincipal.java](../../../spring-ai-rag-core/src/main/java/com/springairag/core/chat/ChatPrincipal.java)
    可提供稳定 owner namespace，且不保存原始凭据。
  - 当前 WebUI 管理控制台只允许 environment root 解锁；数据库 `ADMIN` 是 API 管理
    principal，但不会通过现有控制台 unlock 流程进入 WebUI。

## 3. 冻结的对外契约

### 3.1 预览

`POST /api/v1/rag/collections/by-key/purge/preview?collectionKey={key}`

要求管理权限。预览响应至少包含：

- `previewId`：UUID；
- `collectionId`、`collectionKey`；
- `collectionVersion`：创建预览时的 Collection `version`；
- `chatCommitFenceVersion`：创建预览时的 Chat 提交围栏版本；
- `status=PREVIEWED`；
- `documentCount`、`externalDocumentCount`、`localDocumentCount`；
- `embeddingCount`、`embeddingJobCount`、`versionCount`、`keywordChunkCount`；
- `repairPreviewCount`、`repairItemCount`、`derivedRowCount`；
- `documentIdempotencyOperationCount`：将删除的文档 mutation/relocation replay 行；
  - `feedbackCount`、`feedbackDocumentReferenceCount`：将删除的反馈及其规范化文档引用数；
  - `documentAuditCount`：将删除的、按 document ID 精确归属的 Document 审计行数；
  - `collectionAuditCount`：将删除的、按 Collection ID 精确归属的历史 Collection 审计
    行数；
- `relocationMarkerCount`：将保留但会按数据库约束清空 `document_id` 的地址 fence 数量；
- `affectedChatSessionCount`、`chatHistoryCount`、`chatMemoryCount`、
  `chatSummaryCount`、`chatTurnOperationCount`：将被整体删除的受影响会话产物；
- `activeSyncRunCount`、`activeDerivationRepairCount`；
- `activeChatSessionCount`：受影响会话中仍持有有效 session lease 的数量；
- `unindexedChatReferenceCount`、`unindexedFeedbackReferenceCount`：必须为 0，否则
  preview 以 409 fail closed，不签发 confirmation token；
- `confirmationToken`：只在创建响应中返回明文一次；
- `previewExpiresAt`、`operationExpiresAt`；
- `fingerprint`：不包含明文 token 和正文。

预览允许 active 或已软删除但未退役的 Collection。若目标 Collection 本身存在有效 active
sync run，或待删除文档仍被其他 Collection 的 active sync run item 引用，返回 409。对
derivation repair 同样使用并集：`preview.collection_id` 是目标 Collection，或其 item
引用任一待删除文档，且 preview/item 仍处于有效 `APPLYING` 状态时都必须阻断。不能只按
repair preview 的 `collection_id` 计算，也不能只按文档引用计算；前者会遗漏文档迁移后的
跨 Collection 引用，后者会遗漏尚无 item 的目标 Collection preview。
若受影响 Chat 会话仍持有有效 session lease，同样返回 409。受影响会话通过规范化
`rag_chat_history_source_document` 引用表识别；一个会话只要有任一 history source 指向
待删除文档，该 owner + session 的全部历史、memory、summary 和 turn operation 都计入
预览，避免留下后续轮次对旧回答的复制或摘要。
预览不读取或返回任何文档正文、JSON payload、credential 或 token。

### 3.2 apply

`POST /api/v1/rag/collections/by-key/purge`

请求体：

```json
{
  "collectionKey": "knowledge-base-a",
  "previewId": "00000000-0000-0000-0000-000000000000",
  "confirmationToken": "returned-only-on-preview",
  "fingerprint": "sha256",
  "expectedCollectionVersion": 7,
  "expectedChatCommitFenceVersion": 12
}
```

apply 必须同时满足：

- preview 属于当前 owner，状态未完成且未过期；
- Collection key、Collection ID、Collection version、Chat 提交围栏版本与预览一致；
- confirmation token hash 和 fingerprint 匹配；
- Collection 仍未退役；
- active sync run、active repair、受影响会话 lease 和生命周期 CAS 检查通过；
- `expectedCollectionVersion`、`expectedChatCommitFenceVersion` 与预览冻结值一致。

成功响应至少包含 `status=RETIRED`、Collection 标识、`purgedDocumentCount`、
`purgedExternalDocumentCount`、`purgedLocalDocumentCount`、`deletedAt`、`purgedAt`、
`collectionVersion`。在完成结果的 24 小时保留窗口内，重试同一个已成功 preview 返回同一
最小结果 envelope，不重复删除；结果行超过 `resultExpiresAt` 被 cleanup 删除后不再承诺
replay，后续同一请求统一返回不泄露 Collection 内容的 preview expired/not available 409。

### 3.3 状态和错误

新增 `purgedAt`/退役状态只用于识别不可恢复的 Collection tombstone。既有普通软删除的
restore 保持兼容，但对已退役 Collection 返回明确 409。

新增错误码，使用 RFC 7807 现有统一错误格式：

- `COLLECTION_PURGE_DISABLED`（503）：能力被配置关闭；
- `COLLECTION_PURGE_FORBIDDEN`（403）：不是 root 或数据库 ADMIN；
- `COLLECTION_PURGE_CONFLICT`（409）：版本、活动运行或状态冲突；
- `COLLECTION_PURGE_PREVIEW_EXPIRED`（409）：预览/操作窗口过期；
- `COLLECTION_PURGE_CONFIRMATION_INVALID`（409）：token/fingerprint 不匹配；
- `COLLECTION_ALREADY_RETIRED`（409）：Collection 已完成退役。

不泄露受限 caller 不应知道的 Collection 或外部身份信息。管理权限判断在 service/controller
入口和 apply 的事务内都执行；数据库 apply 使用 owner 和 preview ID 重新校验，不能只信
Servlet 请求里保存的实体。

## 4. 数据模型与事务设计

### 4.1 Collection tombstone

新增 V56：

- `rag_collection.purged_at TIMESTAMP(6)`，为空表示尚未完成最终清理；
- `rag_collection.chat_commit_fence_version BIGINT NOT NULL DEFAULT 0`，只用于把带 RAG
  source 的 Chat 持久化提交与 Collection 生命周期写串行化，不参与公开 Collection version；
- 约束：`purged_at IS NULL OR deleted = TRUE`；
- 索引/实体映射按需要补齐；
- `CollectionMapper` 和 Collection detail/list 输出 `purgedAt` 与稳定状态字段；
- `restore` 条件改为 `deleted = true AND purged_at IS NULL`；
- lifecycle fence 同时写入 `enabled=false`，最终 tombstone 保持 disabled。
- 最终 tombstone 保留 `id`、`collection_key`、embedding profile/dimensions、生命周期版本
  和必要时间戳；`name` 改为固定非业务值 `Retired collection`，`description` 与
  `metadata` 置空。`collection_key` 为永久地址 fence，无法在不破坏不复用契约的前提下
  匿名化；API/文档必须把这一点作为 purge 的明确保留边界。

新增 `rag_chat_history_source_document`：

- `history_id BIGINT NOT NULL REFERENCES rag_chat_history(id) ON DELETE CASCADE`；
- `document_id BIGINT NOT NULL`，故意不建立到 `rag_documents` 的外键，使引用本身仍可作为
  历史定位索引且不会改变现有单文档 hard-delete 行为；
- 主键 `(history_id, document_id)`，并增加 `(document_id, history_id)` 索引；
- V56 从 `rag_chat_history.sources[*].documentId` 和历史兼容字段
  `related_document_ids[*]` 的并集中只回填可安全转换为正 `BIGINT` 的值；回填 SQL 必须先
  检查 JSON 类型和数值范围，格式错误或越界值只跳过，不能使 migration 失败；
- 后续 durable history 写入在同一事务中按 `sources` 与 `related_document_ids` 的并集写
  该引用表。`ChatSource.documentId` 当前是字符串字段，静态知识和其他非数据库 source 的
  非十进制正整数 ID 不写入；
- `rag_chat_history` 增加 `content_reference_index_complete BOOLEAN NOT NULL`。V56 对
  `sources` 和 `related_document_ids` 完成结构校验与回填后才设为 true；合法的静态
  non-numeric source ID 不算异常，损坏的 JSON 结构或无法判定的 legacy TEXT 算异常。
  后续 durable writer 只有在 history 与全部 refs 同事务成功后才写 true。

新增 `rag_user_feedback_document`：

- `feedback_id BIGINT NOT NULL REFERENCES rag_user_feedback(id) ON DELETE CASCADE`；
- `document_id BIGINT NOT NULL`，不建立到 `rag_documents` 的外键，避免改变普通文档删除
  和历史反馈的既有生命周期；
- 主键 `(feedback_id, document_id)`，并增加 `(document_id, feedback_id)` 索引；
- V56 从 `retrieved_document_ids` 与 `selected_document_ids` 的并集中只回填可安全转换为
  正 `BIGINT` 的 JSON array 元素；不是 array、元素类型错误、非正数或越界值都只跳过，
  不能使 migration 失败；
- `rag_user_feedback` 增加 `content_reference_index_complete BOOLEAN NOT NULL`；V56
  只有在两个 legacy ID 字段均为空或是结构完整、可判定的 JSON array 时才设为 true。
  非正数/越界数不可能对应现有 document ID，可安全忽略；损坏 JSON、非 array 或混合未知
  类型保持 false。后续 writer 与 refs 同事务写 true；
- 后续反馈写入在保存 feedback 的同一事务中写入两个 ID 列表的去重并集。purge 只要发现
  任一引用命中待清理文档，就删除整个 feedback 行，由级联删除规范化引用，避免保留可能
  引用正文的 comment/metadata；
- 新 feedback 若携带 document IDs，必须重新读取这些文档、执行 caller ACL 校验，并对
  它们当前所属的全部 Collection 消费 ordered active-write token；任一文档不存在、已
  失效、caller 不可见或 Collection 已退役都拒绝整次 feedback。这样 purge 先提交时不会
  被陈旧客户端重新写回 comment，feedback 先提交时会推进 Collection version 并使旧
  preview 失效。没有 document ID 的一般反馈不声称可由 Collection purge 归因或删除。

preview 在构造任何删除计划前全局检查两类 `content_reference_index_complete=false` 行。
由于损坏 legacy TEXT 无法可靠归属到某个 Collection，不能用字符串模糊匹配，也不能假设
与目标无关；存在任一内容引用索引异常即返回 409，只暴露分类计数和修复提示，不返回原始
字段。管理员必须修复为受支持 JSON 后重建 refs，或按保留策略删除异常行，再重新 preview。

不删除 `rag_collection`，因此：

- `collectionKey` 永久不可复用；
- Collection 创建幂等、Collection 级聚合观测、迁移地址历史和审计的 FK 不断裂；
- 退役状态可以被安全识别，但不会再作为 active retrieval/write scope 返回。

### 4.2 Durable preview

新增 `rag_collection_purge_preview`，建议字段：

- `id UUID PRIMARY KEY`；
- `owner_principal_id`、`collection_id`、`collection_version`、
  `chat_commit_fence_version`；
- `confirmation_token_hash`、`fingerprint`；
- 所有预览计数；
- `status`：`PREVIEWED`、`APPLYING`、`COMPLETED`、`EXPIRED`；
- `apply_lease_owner_hash`、`apply_lease_expires_at`；
- `preview_deadline`、`operation_deadline`、`result_expires_at`；
- `result_payload JSONB`、`created_at`、`completed_at`。

Collection 自身的 `purged_at` 沿用现有 `deleted_at` 的 `TIMESTAMP(6)` /
`LocalDateTime` 映射，避免同一实体出现两套时间语义；preview、lease、operation 和结果保留
时间使用控制面已有的 `TIMESTAMPTZ` / `OffsetDateTime` 约定。

数据库约束禁止把明文 token、正文、JSONB 业务 payload 或 response 中的正文写入预览表。
确认 token 使用 `SecureRandom` 生成 32 字节随机值并采用无 padding 的 Base64URL 编码，
数据库只保存 SHA-256。`fingerprint` 不是授权凭据，而是对版本化、排序稳定且不含正文的
完整删除计划做 SHA-256：至少覆盖 Collection ID/key、两个冻结版本、全部计数、待删除
document ID 集合、受影响 owner/session 集合、feedback ID 集合、Document 审计 ID 集合、
Collection 审计 ID 集合、repair/idempotency 控制行 ID 集合和保留的 relocation marker
ID 集合。

默认生命周期冻结为：preview 确认窗口 15 分钟、operation 窗口 1 小时、完成结果保留 24
小时、apply lease 2 分钟、每 owner 最多 20 个未过期 preview。每次 preview 前和每小时
调度一次有界 cleanup，每批最多 500 行：超过 `operation_deadline` 的
`PREVIEWED`/`APPLYING` 标记为 `EXPIRED`；仍在 operation window 内但 lease 已过期的异常
`APPLYING` 行清空 lease 并退回 `PREVIEWED`；最后删除超过 `result_expires_at` 的终态行。
正常 apply 的 claim、fence、删除与 `COMPLETED` 写回在同一事务中，失败或进程退出会整体
回滚，因此不会提交一个半完成的 `APPLYING`；上述 stale lease 恢复只用于防御异常/历史
状态。以上参数由
`rag.collection-purge` 绑定并限制在安全区间，不能通过配置得到零时限、无限 lease 或
无界 cleanup。

### 4.3 清理顺序

apply 在 transaction template 中执行，顺序固定。**生命周期 fence 必须先于任何活动
运行检查后的物理删除**，否则新的 upsert/sync 可能在检查与删除之间取得旧的 Collection
版本并写入将被清理的文档集合：

1. 重新读取 preview、Collection 状态和两个冻结版本，校验 owner、key、指纹、token、
   预览版本和有效期，使用条件更新 claim apply lease；
2. 立即用单条条件更新建立生命周期 fence：条件同时要求 `purged_at IS NULL`、
   `version` 和 `chat_commit_fence_version` 仍等于预览冻结值；成功后将
   `deleted=true`、`enabled=false`、设置 `deleted_at`（已有值则保留）并将 `version`
   增加 1。这个 fence 即使 Collection 原本已经软删除也必须执行；它必须在当前事务中
   提交前完成，任何后续失败都会回滚。fence 之后，新的写入、同步、迁移、repair preview、
   Chat commit 和 restore 只能因版本/active 状态 CAS 失败，不能进入目标 Collection；
3. 在 fence 成功后重新构造完整删除计划并计算 fingerprint；它必须与 preview 完全相同。
   这一步既防止 preview 后新增 Chat 引用被静默扩大删除范围，也防止新增 repair、
   idempotency 或其他控制行未向用户展示就被清理。任何差异都返回
   `COLLECTION_PURGE_CONFLICT` 并整体回滚，要求重新 preview；
4. 重新检查目标 Collection 直接拥有或通过 item 引用待删除文档的
   active sync runs 和 active/applying derivation repairs；两类检查都使用“直接 scope
   或文档引用”的并集。同时重新计算受影响 Chat 会话，并拒绝仍持有有效
   `rag_chat_session_lease` 的会话。发现活动运行即整体回滚，不进行任何物理删除；
5. 对每个受影响的 owner + session，删除其全部 `rag_chat_history`、规范化 source refs、
   `rag_chat_memory_summary`、`rag_chat_turn_operations`、过期 session lease，以及
   `spring_ai_chat_memory` 中由 `ChatPrincipal.memoryConversationId(sessionId)` 派生的
   conversation；legacy null-owner history 额外清理原始 session ID memory。这里删除整个
   会话而不是只改写 source JSON，保证历史回答、tool transcript、summary 和 exact replay
   不再保存待清理内容；
6. 删除 `rag_user_feedback_document` 命中任一待删除 document ID 的完整 feedback 行，
   由级联删除其规范化引用；删除 `rag_audit_log` 中 `entity_type=Document` 且
   `entity_id` 精确等于待删除 document ID 十进制字符串的行，以及
   `entity_type=Collection` 且 `entity_id` 精确等于目标 Collection ID 十进制字符串的
   历史行。不要按 description/details 模糊匹配，也不要删除只含计数且无法精确归属的
   batch/upload 审计；
7. 删除所有引用待删除文档的、已不再活动的 derivation repair previews（级联其 items），
   而不只是 `preview.collection_id` 等于目标 Collection 的 preview；
8. 删除所有 `result_document_id` 指向待删除文档，或
   `authorization_collection_ids` 包含目标 Collection 的
   `rag_document_idempotency_operations`。relocation marker 的 operation FK 依靠
   `ON DELETE SET NULL` 保留；删除 replay 行可避免 purge 后继续返回已不存在的文档结果，
   对相同 idempotency key 的后续重试会进入正常业务校验并因 Collection 已退役而失败；
9. 先显式删除待删除文档的 `rag_embeddings`；再物理删除
   `rag_documents WHERE collection_id = ?`，依赖 V25/V29/V33/V43 的级联删除 embedding
   state、embedding jobs、chunks、local index state 和 versions；对删除后应为 SET NULL
   的外部同步 item、迁移地址和文档幂等结果做只读计数确认；
10. 条件更新 `rag_collection`：`purged_at=now()`、`version=version+1`、
   `name='Retired collection'`、`description=NULL`、`metadata=NULL`，条件是 fence 后的
   版本且仍未退役；此更新把临时 fence 转换为最小化、不可恢复的退役 tombstone；
11. 写入不含 token/正文的审计事件；
12. 把版本化、最小结果 envelope 写回 preview `COMPLETED`，清空 lease。

生命周期 fence 与最终退役都使用条件写入。任何在 fence 前已经持有旧 Collection 版本的
并发写入，都会在数据库行版本可见后失败；任何在 fence 后尝试开始的同步或派生修复，也
会因 Collection 已退役/非 active 而失败。apply 自身若在活动检查、删除或最终标记阶段
失败，事务整体回滚，不能留下 fence、`purged_at` 或部分文档删除。

所有显式选择 Collection 的读取/执行入口都必须通过统一的 active/retired scope
解析语义，包括 REST search、JSON Record search/lookup、Chat DTO/string/streaming、
OpenAI-compatible Chat、Collection documents/export/clone、sync/repair 和生产 service
入口。调用方对该 Collection 已有访问权时，显式选择已退役 key/ID 返回
`COLLECTION_ALREADY_RETIRED`（OpenAI-compatible 路径映射为等价的协议错误），而不是把
退役误报为空检索；无权调用方继续按现有 404/403 防枚举语义处理。未显式选择 Collection
的全局/默认检索只排除退役 tombstone，不因历史 tombstone 使整个请求失败。

带数据库文档 source 的 Chat 在写 history/memory/turn replay 前，必须按 Collection ID
排序执行条件更新：

```sql
UPDATE rag_collection
SET chat_commit_fence_version = chat_commit_fence_version + 1
WHERE id = ?
  AND deleted = FALSE
  AND enabled = TRUE
  AND purged_at IS NULL
```

任一 source Collection 更新不到一行，Chat 提交失败且不持久化结果。这个条件写入与 purge
的生命周期 CAS 更新同一 Collection 行：Chat 先取得写序时，purge 等待其提交后再删除刚写
入的会话；purge 先取得写序时，Chat 等待后看到 Collection 已删除并失败。因此 purge 提交
后不会由在途 Chat 重新写回 citation、tool transcript、memory 或 replay。流式请求在
provider 已发送的网络字节无法撤回，本契约保证的是服务端持久化和后续 replay/retrieval
边界。

Chat 提交事务不能信任 response 中的 Collection key。它从 `sources` 与
`related_document_ids` 的合法数据库 document ID 并集重新读取当前文档归属，拒绝已经
删除、禁用、无 Collection 或位于非活动/已退役 Collection 的引用，然后按 Collection ID
排序推进围栏并写 history/source refs/memory/replay。这样 source 引用索引、Collection
围栏和正文副本始终在同一事务内提交。

当前 summary compaction 在 history/memory commit 之后执行，不能让 coordinator 在
summary 落库前消费 session lease。实施时 session lease 必须覆盖模型调用、durable
history/memory/replay commit 以及本轮所有 summary 持久化；只有 summary 成功、明确降级
跳过或失败并停止写入后才能释放 lease。这样 purge 要么先观察到有效 lease 并返回 409，
要么在 Chat 完整释放 lease 后删除 history/memory/summary。进程在主提交后崩溃时，lease
按既有有界 TTL 过期；purge 等待过期再执行，不能让迟到的后台 compaction 无 lease 写回。

`RagChatService` 的所有生产公共入口都必须遵守同一提交边界，包括请求 DTO、字符串便利
重载、流式重载和框架内调用。purge 能力开启时，`ChatExecutionService`、
`ChatSessionCoordinator`、durable history/source-ref writer 和 summary coordinator 必须
全部存在；不能落入仅供旧单元夹具使用的 `historyRepository.save` 或 Spring AI advisor
自动持久化兼容路径。无法提供 durable coordinator 时启动失败，而不是 fail open。

sync run `begin` 和 derivation repair `preview` 当前只读取 active Collection，没有在写
控制面行之前预占 Collection 写序。实施时必须让这两个“创建活动控制面状态”的入口先执行
统一 active-write reservation，再插入 run/preview：若 purge fence 先提交，reservation
失败并使业务事务回滚；若它们先预占，purge 等待其提交后看到 Collection version 已前进，
旧 purge preview 失效。这样 purge 在 fence 后重算 fingerprint 时不会漏掉一个尚未提交的
新 run/repair preview。

同一约束必须覆盖**所有会在 Collection 下创建、移动、恢复或改写持久化文档内容的生产
入口**，不能只修 sync/repair：

- `DocumentMutationService` 的 local create、local import upsert、local update、
  enable/disable、version restore 和 standalone external/json upsert/tombstone；
- PDF-to-RAG、普通/批量上传、Collection add-document 等经 mutation coordinator
  进入的路径；
- external relocation 同时消费 source/target token；
- Collection clone 消费 source Collection token，防止 purge 完成后才提交另一份正文；
- Collection import 在新 Collection 与文档同一外层事务中原子提交，未提交前 purge
  不可见；其后续普通 mutation 仍使用相同 token 规则。

实现提供一个统一的 ordered active-write reservation helper：先读取本次实际涉及的非空
Collection ID 去重并排序，捕获各自 `version`；**在任何 document、version、embedding
job、sync/repair、feedback 或其他依赖行发生写入前**，按同一顺序执行条件更新并将 version
增加 1，条件至少包含 `deleted=false`、`enabled=true`、`purged_at IS NULL` 和冻结
version。reservation 的行锁由当前业务事务持有到提交/回滚，业务写之后不再做第二次
Collection confirm。涉及 scope move/restore 的写必须同时预占原 Collection 与目标
Collection，不能只保护目标；clone 在读取/复制 source 文档前预占 source。带 document ID
的 feedback 可先只读解析文档和 ACL、捕获所属 Collection/version，再预占全部 Collection，
并在 reservation 后于同一事务重新确认文档归属/状态后才写 feedback。

这一固定锁序避免“业务先锁 document、purge 先锁 Collection”形成反向等待：所有受保护
写入和 purge 都先按 Collection ID 顺序写 Collection 行，再触碰 document/控制面依赖行。
若 purge 先提交，reservation 更新不到行并使业务事务回滚；若业务先预占，purge 等待其
提交后发现 version 变化并拒绝旧 preview。reservation 是条件更新/CAS，不使用
`FOR UPDATE`、显式悲观锁、`SKIP LOCKED` 或 advisory lock。purge 开启时生产配置必须要求
mutation coordinator 和 reservation helper 可用，不能退回仅供隔离单元测试的 repository
直写兼容分支。

所有新写入的单文档和单 Collection audit 都必须改为 content-free：description/details
只保存稳定 operation、document ID、Collection ID 和计数，禁止保存 document title、
source、filename、Collection name/description、metadata、payload 或正文。当前 Controller
在 mutation 事务返回后才 best-effort 写 audit，因此不能依靠 purge 事务阻止迟到审计；
依靠“历史精确归属行由 purge 删除 + 新行永久无内容”才能封闭该竞态。

活动 embedding worker 不作为 purge 的额外阻断条件。worker 在 provider 调用后必须通过
`rag_embedding_jobs` 与 `rag_documents` 的事务内提交门；purge 删除 job、state、embedding
和 document 后，晚到 worker 无法取得提交资格。若 worker 先进入提交事务，purge 的删除会
等待其完成，然后删除其刚写入的派生数据。验收必须覆盖两种事务次序。

relocation marker 属于永久地址阻断账本，不是待清理的派生正文。目标 Collection 的文档被
purge 后，marker 的 `document_id` 依靠 `ON DELETE SET NULL` 清空，`source_collection_id`
与 `target_collection_id` 继续指向保留的 Collection tombstone；marker 的 `active` 状态
不因 purge 改写。这样旧地址不会被重新利用，也不会因为正文已删除而错误指向其他文档。

所有步骤在同一事务内；任意异常整体回滚。事务不执行显式悲观锁、`FOR UPDATE`、
`SKIP LOCKED` 或 advisory lock。Collection version 条件写入和 preview apply lease 是
并发协调原语。

### 4.4 规模边界

本批次的 apply 采用单事务并明确上限，避免无人知情地执行无限大事务。推荐默认：

- `maxDocuments=10_000`；
- `maxEmbeddings=100_000`；
- `maxVersions=100_000`；
- `maxDerivedRows=250_000`，统计 embedding、embedding state/job、version、keyword chunk、
  local index state、repair preview/item、document idempotency operation、feedback/
  feedback refs、Document audit、chat history/source refs/memory/summary/turn operations
  等本次会删除的全部派生控制行；
- `maxAffectedChatSessions=1_000`、`maxChatRows=50_000`，避免一次 purge 因历史会话扩散为
  无界事务；
- 超过任一上限时 preview 返回 `COLLECTION_PURGE_CONFLICT` 并提示后续异步清理能力，
  apply 不得开始部分删除。

这些上限由 `rag.collection-purge` 配置绑定并在启动时限制在安全范围；没有异步实现时，
宁可拒绝超限而不是截断或分批造成半退役状态。

## 5. 实施切片

1. **API 与配置**
   - API module 增加 request/preview/apply/response DTO；
   - 增加错误码；
   - 增加 `RagCollectionPurgeProperties` 和默认配置；
   - 增加 root/ADMIN 管理权限辅助判断。
2. **Schema 与实体**
   - 添加 V56 migration；
   - 映射 `purgedAt`；
   - 增加 chat commit fence 字段和规范化 history-source 引用表，完成历史 backfill；
   - 增加规范化 feedback-document 引用表，完成历史安全 backfill；
   - 为 chat history/feedback 增加 reference-index completeness 标记，迁移坏数据时记录
     fail-closed 状态而不是中止 Flyway；
   - 增加 preview 表的 JDBC service/repository；
   - 明确约束和索引。
3. **Service**
   - 实现 preview 计数、owner/token/fingerprint；
   - 实现 apply lease、CAS、active-run/repair 检查、级联清理、结果 replay；
   - durable Chat 保存 source refs，并在提交 history/memory/replay 前执行 Collection
     chat commit fence；
   - session lease 保持到本轮 summary compaction 完全停止写入后再释放；全部
     `RagChatService` 生产入口统一经过 durable coordinator，purge 开启时缺少任一必要
     coordinator bean 都 fail-fast；
   - purge 识别受影响 owner-scoped sessions，清理其全部持久化会话产物；
   - feedback 保存时同步写文档引用；purge 清理受影响 feedback 和精确归属的 Document
     audit、Collection audit；
   - 带 document IDs 的 feedback 保存前执行文档存在性/ACL/Collection token 校验；
     将所有单文档和单 Collection audit 改为只保存无内容身份事实；
   - 为所有会创建、移动、恢复或改写 Collection 文档内容的生产入口统一接入按
     Collection ID 排序的 active-write token；scope move/restore 同时保护 source/target，
     clone 保护 source；
   - 调整 Collection restore、写入和 identity 入口拒绝退役 Collection；
   - Collection provisioning replay 遇到已退役结果时返回
     `COLLECTION_ALREADY_RETIRED`，不能把历史 create replay 成可用 Collection；
   - 删除会产生过期结果的 document mutation/relocation idempotency rows。
4. **Controller / observability**
   - 暴露两个按 key 的端点，更新 OpenAPI contract；
   - 为新 endpoint 加入 `COLLECTION_PURGE_PREVIEW` /
     `COLLECTION_PURGE_APPLY` IntegrationOperation 和观测分类；
   - capability contract 增加 caller-aware 的 `optional.collectionPurge` 与 purge
     limits：只有服务开关开启且 caller 是 environment root、数据库 `ADMIN` 或显式允许的
     auth-disabled 本地身份时为 true；这是向后兼容字段扩展，contract version 升为 `1.1`；
   - 记录无正文审计详情。
5. **WebUI**
   - active Collection 卡片仅为已解锁的 environment root 显示受保护的“永久清理并退役”
     动作；当前 active-only 列表不新增 deleted Collection 浏览器，已软删除 Collection
     仍通过管理 API 按稳定 key 处理；
     数据库 `ADMIN` 通过 HTTP API 执行，不扩展本批次既有 console unlock 契约；
   - 交互必须先 preview，再显示计数和明确确认输入，apply 时显示 pending/成功/失败；
     成功后先在 modal/toast 中保留可访问的退役结果，再刷新 active-only 列表并移除该卡片，
     不声称刷新后仍在 Collection 卡片上显示退役状态；
   - 只通过 DOM、可访问状态和网络 JSON 验证，不用截图。
6. **测试与文档**
   - 单元测试覆盖 token、owner、状态、权限和上限；
   - PostgreSQL 集成测试从空库跑 V56，覆盖完整级联链、外部文档、同步/repair 冲突、
     replay、并发 CAS 和退役后拒绝；
   - WebUI Vitest/Mock Playwright 覆盖 preview/apply 主路径和错误路径；
   - 更新双语 `rest-api`、`configuration`、`architecture`、`testing-guide`、
     `developer-reference`、`project-context`（按实际影响取最小集合）；
   - 归档本 plan/progress 前执行文档门禁和密钥检查。

## 6. 一次性验收矩阵

### 后端

- `mvn clean compile test-compile`；
- V56 空库迁移与升级路径；
- PostgreSQL HTTP/service 集成：
  - active Collection preview；
  - soft-deleted Collection preview/apply；
  - local + external + JSON record 混合数据；
  - embeddings、embedding state/job、keyword chunks/local state、versions 全部删除；
  - sync item、relocation marker、idempotency result 的预期 SET NULL/保留行为；
  - document mutation/relocation replay 行清除，Collection provisioning replay 对退役
    结果明确失败；
  - relocation marker 的 source/target tombstone 与 active 地址 fence 在 purge 后仍保留，
    但不再引用已删除文档；
  - active sync / active repair 阻断且无部分状态；
  - preview 后新增 sync run / repair preview 会使冻结版本失效，不能在 purge fence 后
    留下活动控制面状态；
  - local create/update/import、standalone external/json upsert/tombstone、PDF-to-RAG、
    add-document、scope move/version restore、relocation 和 clone 分别覆盖“业务写先提交”
    与“purge fence 先提交”两种事务顺序；断点测试确认所有路径先锁 Collection 再写
    document/控制面行，不出现反向锁序死锁；purge 完成后不能出现新正文、移动后的漏删
    文档或 clone 副本；
  - citation history、tool transcript、Spring AI memory、summary 和 turn replay 对受影响
    会话全部删除，未引用目标文档的会话不受影响；
  - 引用目标文档的 feedback（包括 comment/metadata）及其规范化 refs 全部删除，未引用
    目标文档的 feedback 保留；feedback 两个历史 ID 字段的损坏/越界值不会阻断 V56；
  - feedback 与 purge 两种事务次序均不会在退役后留下新 comment；不存在、越权或已退役
    文档 ID 的 feedback 被拒绝且无部分行；
  - 精确归属于目标 document ID 的 Document 审计行及目标 Collection ID 的历史 Collection
    审计行删除；batch/upload 和新 purge 审计保留且不包含正文。在 document/Collection
    mutation 提交后、audit 写入前执行 purge 的测试证明迟到 audit 也不含 title/source/
    filename/Collection name/description/metadata/payload；
  - active affected chat session 阻断；
  - preview 后新增已提交 Chat 引用会因 `chatCommitFenceVersion` 变化而拒绝旧 apply，
    不会静默扩大已确认的删除范围；
  - Chat commit 与 purge 两种事务先后次序都不会在 purge 后留下 citation/history；
  - 在 history/memory commit 与 summary compaction 之间暂停请求时，purge 因 session
    lease 返回 409；释放 lease 后 purge 删除 summary，不能出现迟到 summary 回写；
  - DTO/string/streaming Chat 公共入口都写规范化 refs 并受同一 fence/lease 保护；构造
    缺失 durable coordinator 的 purge-enabled 应用上下文必须启动失败；
  - 历史 `sources`、`related_document_ids` 以及两者并存/损坏/越界值的 V56 backfill；
  - 损坏 legacy Chat/feedback 引用不会使 V56 失败，但任一 incomplete 标记都会阻断
    preview；修复字段并重建 refs 后 preview 才能成功；
  - embedding worker 与 purge 两种提交次序均无 purge 后回写；
  - token、fingerprint、owner、Collection version、权限拒绝；
  - auth-disabled 即使显式开关开启，非 loopback 连接仍返回 403，且不信任转发 IP header；
  - 并发 apply 只有一个执行删除；24 小时结果保留窗口内重试精确 replay，结果 cleanup 后
    返回稳定的 expired/not available 409；
  - 显式 REST/JSON/Chat/OpenAI-compatible/service scope 对已授权退役 Collection 返回
    retired 错误，未授权 caller 不获得存在性信息；未显式 scope 的检索只排除 tombstone；
  - 退役后 restore/upsert/search/export/clone 失败，key 仍不可创建复用。
  - 独立文件边界：
  - purge 不按 `original_filename`、`source=pdf-import:*` 或路径字符串删除 `fs_files`；
  - PostgreSQL 验收证明关联 RAG 文档清理后独立文件产物仍存在，并在 API/长青文档中明确
    这是文件子系统的独立生命周期，而不是 purge 漏删。
  - tombstone 最小化：
    - `collectionKey`、ID、版本和技术 profile 保留，name 使用固定值，description/metadata
      清空；
    - 预览、结果和长青文档明确 collectionKey 因永久不复用而保留，不能把 purge 描述成
      对该稳定标识的匿名化。
- `scripts/verify-no-pessimistic-locks.sh`、`git diff --check`。

### 前端

- `npx tsc -b --pretty false`；
- `npm run test:run`；
- `npm run build`；
- 核心 Mock Playwright：
  - collection card 显示正确动作状态；
  - preview 请求、计数与 token 确认表单可访问；
  - apply 请求携带预览字段；
  - 成功结果在 modal/toast 中可访问，随后刷新 active-only 列表并移除原卡片；
  - 403/409/过期错误可见且不会重复提交。

### 运行时与真实依赖

- 使用 postgresql profile 启动并检查 health；
- 按项目脚本在隔离端口运行真实前后端生命周期；
- Mock 门槛通过后，使用 `.env` 中配置的真实 provider 仅执行必要的 chat/检索回归，
  证明退役前后 Collection 的可见性变化不会污染 chat/RAG 路径。真实测试不得记录密钥、
  正文或完整模型响应。

## 7. 发布、回滚与完成定义

- V56 只新增列和表，不改写历史迁移；上线前先完成 migration，再启用 purge endpoint；
- `rag.collection-purge.enabled` 默认 `false`，生产明确开启后才可执行。开启后：
  environment root 和数据库 `ADMIN` 可以执行；数据库 `NORMAL`、legacy static
  credential 无论是否拥有 `RAG_WRITE` 都返回 `COLLECTION_PURGE_FORBIDDEN`。
  auth-disabled 仅在另一个显式的本地开发开关
  `rag.collection-purge.allow-auth-disabled=true` 且 Servlet 连接的
  `request.getRemoteAddr()` 是 loopback 地址时允许，默认仍为 `false`；不信任
  `Forwarded`/`X-Forwarded-For` 来扩大该权限；
  这样“关闭鉴权”不会意外把生产高风险操作变成可匿名调用。
- WebUI 只代表 environment root 管理控制台；显式允许 auth-disabled 的本地 API 或数据库
  `ADMIN` 不会因此自动获得 WebUI 入口，但后端契约和权限测试必须完整覆盖。
- rollback 只允许关闭能力或恢复数据库备份；已完成物理 purge 不承诺恢复；
- 若 apply 失败，事务回滚，preview 保持可重试或过期，Collection 不得出现
  `purged_at` 已写入但文档仍残留的状态；
- 任何实质修复都重跑相关门槛，实现 review 计数归零；
- 最终必须同时满足：规划 3/3、基本硬门槛、真实必要验证、实现 3/3、双语长青文档、
  Git commit/push、`main` 与 `origin/main` 一致且工作区干净。
