# 第一批改进规划：统一文档处理阶段状态与恢复

> 状态：**已完成实现、连续 3/3 检查、最终门禁和 Git 交付**
>
> 规划日期：2026-10-12
>
> 参考调研：[WeKnora 参考项目调研与借鉴规划](../WEKNORA_REFERENCE_RESEARCH.md)
>
> 规划标准：[规划、实施与验收工作流](../../delivery-workflow-zh-CN.md)

## 0. 恢复入口与本批结论

本文件是从 WeKnora 借鉴的第一批改进的唯一实施入口。即使任务中断，后续 Agent 也应先读
本文件，再按“实施切片”继续；不要仅根据聊天记录、旧 plan 或文件名推断设计。

本批只实施一个方向：**在现有持久化 Embedding Job 之上增加统一的文档处理运行模型，补齐
阶段进度、取消、重试、死信和 stuck-task 恢复**。本批不提前实施引用协议、SSE 解码器、自适应
分块、检索上下文组装、route policy self-check、Parser SPI、资源句柄或 CLI/MCP。

最终采用的低风险方案是：

1. 新增 `DocumentProcessingRun` 和固定阶段记录，但**不替换**现有 `EmbeddingJob`。
2. 现有 Job 继续负责真正的排队、认领、租约、重试和提交 fencing；Processing Run 负责把一次
   文档派生请求的阶段和终态对外解释清楚。
3. 输入快照校验、分块、嵌入、索引/新鲜度、最终收尾先在现有执行链中显式报告，不为了阶段树提前拆成
   多个外部队列或多个 worker 池。
4. 所有并发协调继续使用条件更新、CAS、generation、版本号、lease 和有界重试；禁止
   `FOR UPDATE`、`SKIP LOCKED`、advisory lock。
5. 新建/重试的处理请求都获得可查询的 Processing Run；既有 Job HTTP API 保持可用并增加可选
   关联字段，旧字段和旧状态不删除、不改名。
6. 第一批交付后端、数据库和稳定 HTTP 读写契约；WebUI 不在本批改造，预留字段和验收夹具，
   后续单独做任务时间线 UI。

### 0.1 实施前置条件

- 用户已批准按本批范围推进，当前直接在 `main` 工作区实施，不创建额外分支或 worktree。
- 实施前已完成本文件规定的规划 `3/3`；实施后的实现 `3/3` 仍是最终完成门槛。
- 当前工作区已有调研文档改动，实施前必须保留，不得使用 `reset --hard`、`stash` 或覆盖他人
  WIP。
- 运行和验收默认使用 `postgresql` profile；不把 API Key、Token 或真实 provider 密钥写入文档、
  测试或 Git。

### 0.2 已冻结的非阻断默认

| 项目 | 默认值 | 理由 | 可逆边界 |
|---|---|---|---|
| Processing Run 开关 | `rag.document-processing.enabled=true` | 新模型只包裹现有 Job，失败时可回退旧 Job 读写 | 设为 `false` 后保留现有 Embedding Job 路径；不再创建或推进新的 Run，Processing Run API 统一返回既有 `EMBEDDING_JOBS_DISABLED`（HTTP 503）；旧 Job API 和旧 lifecycle 继续可用，lifecycle 的 `processing` 为 `null` |
| Housekeeping | 开启，默认每 60 秒 | 没有巡检就无法可靠收尾 crashed/stuck run | `housekeeping-enabled=false` 可先只使用 lease 恢复；恢复后重新开启 |
| stuck 阈值 | 300 秒 | 高于现有 120 秒 Job lease，避免正常 provider 调用被误判 | 配置限制在 60–3600 秒；真实长调用以新鲜 Job lease/heartbeat 为准 |
| 阶段持久化 | 每次阶段边界必写；细粒度进度按最短 1 秒合并 | 保证恢复所需信息，又避免每个 chunk 写一行数据库 | 只调整合并窗口，不改变阶段终态和 CAS 条件 |
| 死信形态 | Run 的 `DEAD_LETTERED` 终态及时间/原因字段，不新建第二套死信表 | Run 本身已是可授权、可重试、可审计的任务历史，避免重复账本 | 后续若需要跨任务死信工作台，可从该字段和索引演进，不影响本契约 |
| 旧 Job 兼容 | 新请求完整建 Run；历史 terminal Job 不强制批量补历史，活跃旧 Job 采用惰性 bridge | 不在迁移中引入 UUID 生成扩展或大批量不可回滚写入 | 后续可增加有界历史 backfill；不改变新 Run 契约 |
| WebUI | 本批不改页面，仅保证接口和 Mock 可被后续接入 | 避免把后端状态模型与 UI 设计语言、双语文案同时耦合 | 后续 UI 可只消费新接口和 lifecycle 摘要 |

## 1. 当前基线与问题陈述

### 1.1 已核对的代码事实

以下事实来自规划建立时的当前代码、V1–V60 迁移和现有自动化测试，不来自旧规划的假设；本批
实施新增 V61，实施后的最终事实以代码、V61 和长青文档为准。

| 领域 | 当前事实 | 入口 |
|---|---|---|
| 持久化任务 | `EmbeddingJob` 已有 `QUEUED/RUNNING/SUCCEEDED/FAILED/CANCELLED/STALE`、attempt、max attempts、available time、lease、取消请求、错误和 finished time | `spring-ai-rag-core/src/main/java/com/springairag/core/embeddingjob/EmbeddingJob.java`、`EmbeddingJobStatus.java` |
| 任务认领 | `claim` 用条件 `UPDATE ... RETURNING` 认领 queued 或 lease 过期的 running job，并在达到最大次数时转为 `FAILED` | `EmbeddingJobRepository.claim` |
| 过期提交防护 | `isCommitAllowed`、`claimCommitAllowed`、`markSucceeded`、`markFailure`、`terminalUpdate` 都检查 job 状态、owner、lease 和取消条件；generation、content hash、profile、document kind、chunker version 共同防止旧结果提交 | `EmbeddingJobRepository`、`EmbeddingJobExecutor`、`EmbeddingPersistenceService.replace` |
| worker | 事件唤醒 + 30 秒低频扫描兜底；固定线程池、Semaphore 限制并发；worker 退出后依赖 lease 恢复 | `EmbeddingJobWorker` |
| 执行链 | 当前执行器只把 `CLAIMED`、`EMBEDDING`、`COMMITTING` 写进 Job 的 `progress` JSON；输入快照校验、分块、索引 freshness、最终收尾没有独立持久化阶段 | `EmbeddingJobExecutor`、`DocumentEmbedService` |
| 文档处理 | `DocumentEmbedService` 先确保本地关键词索引，再做缓存检查/准备 chunk、调用 embedding provider、校验结果、替换向量并更新状态 | `DocumentEmbedService`、`KeywordIndexPersistenceService`、`EmbeddingPersistenceService` |
| 生命周期读模型 | 已有 `DocumentLifecycleResponse`，公开 `documentState`、`searchability`、local index、embedding、active job、错误和 retryable；它是派生读模型，不是完整运行账本 | `DocumentLifecycleService`、`DocumentLifecycleResponse`、`RagDocumentController.getDocument` |
| 旧投影 | `rag_documents.processing_status/processing_error` 是早期粗粒度字段；Embedding Job 创建时会写 `PENDING`，成功/失败时仍有写入路径 | `V1__init_rag_schema.sql`、`EmbeddingJobRepository.updateDocumentProcessing` |
| Embedding 状态 | `rag_document_embedding_state` 按 document + profile 保存 content hash、chunker version、request generation、active job、chunk count 和处理错误 | `V25__embedding_profile_expand.sql`、`V40__document_lifecycle_expand.sql`、`V60__backfill_embedding_state_generation.sql` |
| 本地索引状态 | `rag_document_local_index_state` 有独立的 `local_index_generation`，不能与 embedding `request_generation` 混用 | `V43__decouple_keyword_chunks_from_embeddings.sql`、`KeywordIndexPersistenceService` |
| 外部同步运行 | 已有 `DocumentSyncRun`，按 collection + source namespace 管理外部来源快照、lease、active run、missing/tombstone；它不是文档派生处理 run | `V42__document_sync_runs.sql`、`DocumentSyncRunService`、`DocumentSyncRunController` |
| 现有 API | 已有 `/api/v1/rag/embedding-jobs` 的创建、查询、列表、取消、重试；文档还有同步 SSE `/documents/{id}/embed/stream` 和批量 SSE | `EmbeddingJobController`、`RagDocumentController` |
| 权限 | Job 查询、动作和列表已有 document/collection ACL；通用 `ApiCapabilityFilter` 对 GET 要求 read、POST/写操作要求 write（管理员例外） | `EmbeddingJobService`、`ApiKeyCollectionAccess`、`ApiCapabilityFilter` |
| 迁移 | 当前最高迁移是 `V60__backfill_embedding_state_generation.sql`；新增 schema 必须从 V61 开始 | `spring-ai-rag-core/src/main/resources/db/migration/` |

### 1.2 当前真实处理路径

异步路径的实际顺序是：

```text
文档 mutation / embedding API
  -> EmbeddingDispatchService 或 EmbeddingJobService
  -> allocateGeneration / createOrCoalesce / activateJob
  -> after-commit 唤醒 EmbeddingJobWorker
  -> claim job + heartbeat
  -> DocumentEmbedService.embedDocumentForJob
       -> ensureCurrent（本地关键词 chunk/index freshness）
       -> prepareForEmbedding（内容、hash、cache、chunk）
       -> embeddingBatchService.createEmbeddingsBatch
       -> vector result validation
       -> EmbeddingPersistenceService.replace（CAS + vector replacement）
  -> markSucceeded / markFailure / markStale / markCancelled
  -> refreshStateFromJob
```

这个路径的可靠性基础已经不错，但有四个产品和运维缺口：

1. 一个客户端只能看到 Job 的粗粒度 `progress.stage` 或 lifecycle 的 `INDEXING/FAILED`，无法可靠
   知道卡在输入快照校验、chunk、provider、写向量还是收尾。
2. provider 失败会由 Job 处理 retry/backoff，但“本次文档处理请求”没有独立历史，无法区分一次
   手动重试、同一 Job 的自动 attempt 和新 generation。
3. queued/running 的取消有 Job 语义，但没有统一的阶段收尾；当前直接同步/SSE 路径是一次性执行且
   不产生 Processing Run，而持久化 Job 的生命周期又独立于客户端连接，二者容易被误解。
4. worker 进程在关键中间点宕机后，现有 Job lease 可以恢复，但缺少一个扫描器把“任务已 terminal、
   run 未收尾”“没有新鲜 job 但 run 一直 active”等状态显式归档为 stale/dead-letter。

### 1.3 WeKnora 借鉴边界

WeKnora 值得借鉴的是：每次处理有独立 attempt、阶段/span、取消关系、错误和耗时，最后一次
失败进入 dead-letter，并由 housekeeping 根据心跳、队列存在性和状态收尾。不能照搬其
`FOR UPDATE SKIP LOCKED` 队列实现，因为本项目明确禁止悲观锁；本项目必须继续使用现有的
CAS/lease/generation/fencing 方式。完整调研证据见
[WEKNORA_REFERENCE_RESEARCH.md](../WEKNORA_REFERENCE_RESEARCH.md) §4.1、§10.5。

## 2. 目标、非目标与受影响边界

### 2.1 目标

本批完成后，应满足：

1. 每个进入持久化 `EmbeddingJob` 路径的新建或手动重试文档派生请求都有可查询的 Processing Run
   和固定阶段记录；当前直接调用 `DocumentEmbedService` 的同步 `embed` 分支不伪造 Run，继续保留
   原有同步响应语义，并在回归测试中证明没有被新模型破坏。
2. 阶段至少包括 `PARSING`、`CHUNKING`、`EMBEDDING`、`INDEXING`、`FINALIZING`；其中 `PARSING`
   在本批表示读取/校验已经持久化的文档输入快照，不引入新的 parser 实现。每阶段有状态、
   开始/结束/更新时间、进度计数、错误码/脱敏错误和执行 attempt。
3. 现有 Embedding Job 继续作为真实执行单元，Processing Run 的状态只能在匹配的 Job lease、
   generation、content hash、profile、document kind、chunker version 条件下更新。
4. 取消、自动 retry、手动 retry、supersede、stale、最大次数失败和 dead-letter 都有稳定语义，
   幂等且可恢复。
5. worker/应用重启后，能通过 Job lease 恢复任务，并由 housekeeping 收尾孤立 run；不能把仍有
   新鲜 lease 或可重试 queued job 的 run 误判为 stuck。
6. `DocumentLifecycleResponse` 保持旧字段兼容，同时提供当前 Processing Run 摘要；完整阶段详情
   通过独立 API 查询。
7. 所有 run 读取和动作复用 document/collection ACL，错误信息脱敏且不写正文、向量、API Key 或
   lease token。
8. 用单元、MockMvc、PostgreSQL migration/CAS/concurrency 和启动验证证明实现正确；不以首次人工
   手测或 code review 代替测试。

### 2.2 非目标

- 不替换 `EmbeddingJob` 表、现有 claim SQL、现有 vector commit fencing 或现有 worker 并发模型。
- 不把解析、分块、embedding、索引拆成外部消息队列或新的多进程 worker 集群。
- 不把 `DocumentSyncRun` 改造成 Processing Run，也不改变 source snapshot、tombstone、sync lease
  或外部文档身份契约。
- 不改变现有 `DocumentLifecycleResponse` 的 `READY`、`KEYWORD_ONLY`、`INDEXING`、`FAILED`、
  `NOT_REQUESTED`、`DISABLED` 等旧值含义。
- 不实现请求级 Citation Registry、SSE replay/decoder、Chunking Profile/Validator、Retrieval
  Context Assembly、route policy self-check、Parser SPI、稳定资源句柄或 CLI/MCP。
- 不在本批重写 `HierarchicalTextChunker`、PDF parser、全文/向量检索或 Spring AI Chat 主链。
- 不把当前直接执行 `DocumentEmbedService`/`batchEmbedDocumentsWithProgress` 的同步路径改造成持久化
  Job；包括单文档 `/documents/{id}/embed`、批量 `/documents/batch/embed`、批量 SSE、PDF 导入/触发
  的同步或 SSE 分支。这些直连路径不产生 Processing Run，后续若需要可单独规划同步执行账本。不要把
  所有 `EmbeddingPolicy.SYNC` 都排除在本批之外：`DocumentMutationService`/`PdfToRagService` 通过
  `EmbeddingDispatchService` 先创建持久化 Job、再调用 `completeAfterCommit` 的路径仍属于 Job 路径，
  必须创建并推进 Processing Run。
- 不在 WebUI 增加任务时间线页面；仅保证新 JSON 字段不会破坏现有 Mock/UI。
- 不做历史所有 terminal Embedding Job 的无限期 backfill、死信自动删除或死信批量归档。

### 2.3 模块边界

| 模块 | 本批动作 |
|---|---|
| `spring-ai-rag-api` | 新增 Processing Run/Stage 状态枚举和 DTO；向已有 lifecycle/job response 增加可选字段及兼容构造器 |
| `spring-ai-rag-core` | 新增 processing domain/repository/service/controller/housekeeping；桥接现有 Job、worker、executor、lifecycle |
| `spring-ai-rag-core` migration | 新增 V61；不修改 V1–V60 |
| `spring-ai-rag-webui` | 不改生产页面；只在需要时增加 API 类型的兼容性测试夹具，优先不动 |
| `documents` | 不改 chunker 行为；只由 core 在既有调用边界报告 `CHUNKING` |
| `demos` | 不改；若编译受 DTO 构造变化影响，使用兼容构造器，不在 demo 引入新 API |
| docs | 实施完成后同步 configuration、rest-api、architecture、testing-guide 和 developer-reference 的中英文对应长青文档；本文件在完成/取消后归档 |

## 3. 术语与状态机

### 3.1 三类运行对象必须分开

#### A. `DocumentProcessingRun`

表示“某个 document 在某个 embedding profile、generation 和派生快照下的一次完整处理请求”。
它是面向用户和运维的历史账本，包含 stage、终态、错误、死信标记和 supersede 关系。

#### B. `EmbeddingJob`

表示“worker 可以认领和执行的一项 embedding 工作”。它仍是 lease、attempt_count、available_at、
provider retry、commit fencing 的真实协调对象。一个新 Processing Run 通常对应一个新 Job；
同一 Run 的自动重试仍复用同一个 Job，并增加 Job 的 execution attempt。

#### C. `DocumentSyncRun`

表示“一个 collection + source namespace 的外部来源快照同步”。它控制外部身份、缺失项和 tombstone，
不承担单文档解析、分块或向量写入的阶段树。外部同步触发 embedding 时，应由其调用链创建独立的
DocumentProcessingRun，并以 `origin=EXTERNAL_SYNC` 关联来源；不能复用 sync run ID 充当处理 run ID。

### 3.2 Run 状态

公开 enum 固定为：

```text
QUEUED
RUNNING
COMPLETED
FAILED
CANCELLED
STALE
DEAD_LETTERED
```

语义和允许转换：

| 当前 | 触发 | 下一状态 | 备注 |
|---|---|---|---|
| 新建 | Job 入队成功 | `QUEUED` | 所有 stage 为 `PENDING`，还没有 worker lease |
| `QUEUED` | Job 被合法 claim | `RUNNING` | `PARSING` 进入 `RUNNING`；只在 Job 条件更新成功后更新 |
| `RUNNING` | 全部必要 stage 成功或缓存命中 | `COMPLETED` | `FINALIZING` 成功后写入；旧 lifecycle 由底层 freshness 决定 |
| `RUNNING` | 当前执行可重试失败且尚未达到 max attempts | `QUEUED` | 保留同一 Run，写 `nextRetryAt`/错误；失败 stage 和下游 stage 在下一 attempt 前重置 |
| `RUNNING` | 不可重试错误 | `FAILED` | 输入/结果校验确定失败，或文档仍存在但已不可处理；不设置死信时间 |
| `RUNNING` | 达到 max attempts | `DEAD_LETTERED` | 最后一次自动尝试失败；必须设置 `dead_lettered_at`，并保留最后错误 |
| `RUNNING/QUEUED` | 显式 cancel，且 Job 已终止 | `CANCELLED` | running Job 先记录 cancel request，worker 在安全边界收尾 |
| `RUNNING` | generation/文档快照/profile/chunker 不再匹配，或被新 Run supersede | `STALE` | 旧 worker 不能影响新 Run 或新向量 |
| terminal | 重复 GET/cancel/retry | 保持 terminal | GET 幂等；cancel 返回当前状态；retry 只创建新 Run，不重开历史 Run |

Run 不允许从 terminal 回到 active。手动 retry/reprocess 创建新 `run_id`、新 `EmbeddingJob` 和新
generation（如果当前请求需要新的派生快照），新 Run 写 `supersedes_run_id` 指向旧 Run，旧 Run 保持原终态；
这样一次重试不会抹掉旧的 attempt 和错误历史。

`DocumentProcessingSummary.retryable` 是服务端派生字段，不等同于“状态不是 terminal”：`QUEUED`、
`RUNNING`、`COMPLETED` 均为 `false`；`FAILED`、`DEAD_LETTERED`、`CANCELLED` 在文档仍存在且输入快照、
profile 和 descriptor 可重新校验时为 `true`；`STALE` 只有在没有更新的 active Run 且当前快照仍可重建时为
`true`。被新 generation 取代的旧 Run 对外为 `false`：如果新 generation 已有 active target，retry
只能合流到该 target；如果没有 active target，服务必须重新校验当前快照，校验失败则返回已有的校验/禁用
错误而不建 Run，不能因为状态是 `STALE` 就无条件重试。

当新 generation 取代旧 generation 时，创建事务必须先以 `request_generation <> newGeneration`、
`status IN ('QUEUED','RUNNING')` 和当前文档/profile 条件，将旧 active Run CAS 标记为 `STALE`，再
插入新 Run；这不会等待旧 worker 的 lease 过期，因为 generation mismatch 已经使旧 Run 失去提交资格。
同 generation 的重复请求不走该路径，而是合流到现有 active Run。

### 3.3 Stage 状态

固定 stage 顺序和状态：

```text
PARSING -> CHUNKING -> EMBEDDING -> INDEXING -> FINALIZING

stage status:
PENDING | RUNNING | SUCCEEDED | FAILED | SKIPPED | CANCELLED | STALE
```

规则：

- 每个 Run 创建时插入五行 stage，避免依赖缺行推断“尚未执行”。
- 阶段边界必须按 CAS 更新；阶段细粒度进度可以合并，但不能跨 lease 写入。
- `stage.execution_attempt` 记录最近一次把该 stage 从 `PENDING` 推进到 `RUNNING` 的 Job attempt；
  从未执行过的 `PENDING` 或因上游失败而 `SKIPPED` 的 stage 为 `0`，重试成功的 stage 保留最近一次
  成功执行对应的 attempt。Run 的 `execution_attempt` 则镜像当前 Job 的 `attempt_count`。
- 缓存命中时，未实际调用 provider 的 `EMBEDDING`、未替换向量的 `INDEXING` 可标记 `SKIPPED`；
  `FINALIZING` 仍必须 `SUCCEEDED`。
- 本批为了不重排现有调用，`PARSING` 阶段只记录 content hash、document version、enabled 和
  document kind 的输入快照校验；当前文档正文已在 mutation/import 阶段落库，因此这里不声称完成了
  PDF/OCR 等解析。`CHUNKING` 阶段包含现有 `KeywordIndexPersistenceService.ensureCurrent`
  和远程 `DocumentChunkingService.prepare` 的派生准备；通过进度 detail 区分本地 index 与远程
  chunk，不把本地 generation 和 embedding generation 合并。
- `INDEXING` 专指向量替换、embedding state 完成和提交门之后的索引状态收尾；本地关键词索引的
  freshness 仍以 `rag_document_local_index_state` 为事实源。
- provider 失败时当前 stage 为 `FAILED`，但若 Job 会重新排队，Run 仍为 `QUEUED`；下一 attempt
  保留失败 stage 和 `active_stage` 作为待重试诊断，等合法 claim 后再将其置为 `RUNNING`，下游 stage
  保持 `PENDING`。
- Job 因 lease 过期被新 worker 重新 claim 时，先以新 owner/attempt 的 CAS 将上一次遗留的
  `RUNNING` stage 及其下游未完成 stage 重置为 `PENDING`，并保留已 `SUCCEEDED/SKIPPED` 的阶段；随后从
  当前第一段未完成的 stage 重新执行。重置动作写入 `LEASE_RECLAIMED` 诊断码但不覆盖旧的 terminal
  stage 历史，`stage.execution_attempt` 和 Run `execution_attempt` 使用新 Job attempt。这样旧 worker
  的迟到 heartbeat/update 不会留下永久 `RUNNING` stage，新 worker 也不会重复覆盖已提交的有效向量。
- 取消时，所有仍为 `PENDING`/`RUNNING` 的 stage 条件更新为 `CANCELLED`；已成功或已跳过的历史
  stage 保持原状态。generation 失效或被 supersede 时，所有仍为 `PENDING`/`RUNNING` 的 stage
  条件更新为 `STALE`；已完成的历史 stage 保持原状态。不可重试失败或达到最大自动 attempt
  时，当前失败 stage 为 `FAILED`，所有尚未执行的下游 stage 为 `SKIPPED`，并写入稳定的
  `UPSTREAM_STAGE_FAILED` 错误码；已成功或已跳过的历史 stage 保持原状态。这样不会出现 Run
  已 terminal 但 stage 永久 `RUNNING`，也不会把“未执行”伪装成“执行失败”。

### 3.4 自动 attempt 与手动 retry 的边界

- **自动 attempt**：同一 Run、同一 Job，在 provider 临时错误或 lease 恢复时复用；`EmbeddingJob.attemptCount`
  是执行次数，Run 的 `executionAttempt` 是对外摘要，二者必须一致或记录明确的 bridge 状态。
- **手动 retry/reprocess**：新 Run、新 Job、新历史记录；默认沿用原 `contentHash/documentVersion/profile/
  chunkerVersion`，只有调用方明确要求重新派生或文档已变更时才分配新 generation。
- **新内容/新 chunker/profile**：必须通过现有 `allocateGeneration` 取得新的 request generation，
  旧 active Job 由 `cancelSuperseded` 处理：`QUEUED` Job 立即变为 `STALE`，`RUNNING` Job 保留运行
  状态但写入失效/取消请求，待持有有效 lease 的 worker 或恢复路径按 generation fencing 收尾为
  `STALE`；不能只重置 status。
- **现有 `/embedding-jobs/{id}/retry`**：保留路由和返回 DTO，但改成 Processing Run 的兼容 façade。
  若已有对应 Run，创建新 Run/Job 并返回新的 retry target；若没有对应 Run，先建立 legacy bridge，
  再走同一新 retry 逻辑。`coalesced=true` 表示请求合流到已有 active target，不承诺返回原 Job ID。

## 4. 数据模型与迁移设计

### 4.1 新表：`rag_document_processing_runs`

新增 `V61__document_processing_runs.sql`，下表中的表名、列名、状态值和约束语义均为实施契约；
不得在实施切片中另行决定物理命名，也不得删除、合并或改变下列契约语义。

V61 同时在现有 `rag_embedding_jobs` 增加一个可空的 `terminal_reason VARCHAR(32)`，只用于可靠区分
`FAILED` 的来源；这不是替换 Job 表或改变旧 API。允许值为 `NULL`、`NON_RETRYABLE`、
`RETRY_EXHAUSTED`，并增加对应 check：普通重试中的 Job 必须为 `NULL`，永久失败写
`NON_RETRYABLE`，达到最大次数写 `RETRY_EXHAUSTED`；`terminal_reason` 非空时 Job 必须是
`FAILED`。普通重试重新排队、成功、取消或 stale 时都必须清空该字段。旧历史 Job 的 `NULL` 在 bridge 时按
`attempt_count >= max_attempts` 兼容推导为 `RETRY_EXHAUSTED`，否则按 `NON_RETRYABLE` 处理，且只影响
新 Run 的镜像语义。

`EmbeddingJob` record 的新 `terminalReason` 组件必须追加在现有组件之后；保留当前 canonical/convenience
构造器的兼容重载，并让旧构造器默认传入 `null`。`EmbeddingJobRepository.COLUMNS`、`mapJob`、所有
`RETURNING job.*` 查询和相关测试 fixture 一起更新；不能通过改变现有构造器参数顺序或直接删除构造器来
“顺手”完成字段接入。该字段默认不加入 `EmbeddingJobResponse`，对外由 Processing Run 的稳定错误码和
`deadLettered` 摘要表达，避免旧 Job API 出现必需新字段。

| 字段 | 类型/约束 | 用途 |
|---|---|---|
| `id` | `UUID PK` | Processing Run 身份，由应用生成 |
| `document_id` | `BIGINT NOT NULL FK rag_documents ON DELETE CASCADE` | 文档范围 |
| `embedding_profile_id` | `BIGINT NOT NULL FK rag_embedding_profiles` | 当前 embedding profile |
| `embedding_job_id` | `UUID FK rag_embedding_jobs ON DELETE SET NULL` | 当前/历史执行 Job 关联；不反向改 Job 表 |
| `request_generation` | `BIGINT NOT NULL CHECK > 0` | embedding 派生 fencing |
| `document_version` | `BIGINT NOT NULL` | 创建请求时的文档快照版本 |
| `content_hash` | `VARCHAR(64) NOT NULL CHECK (content_hash ~ '^[0-9a-fA-F]{64}$')` | 文档内容快照，不存正文 |
| `document_kind` | `VARCHAR(32) NOT NULL` | `TEXT` / `JSON_RECORD` |
| `chunker_version` | `VARCHAR(128) NOT NULL` | 与现有 descriptor 一致 |
| `attempt_no` | `INTEGER NOT NULL CHECK >= 1` | 手动处理历史序号；同一文档/profile 递增 |
| `execution_attempt` | `INTEGER NOT NULL DEFAULT 0` | 当前 Job 自动执行次数摘要 |
| `max_attempts` | `INTEGER NOT NULL CHECK 1–10` | 对外 retry 上限 |
| `status` | `VARCHAR(20) NOT NULL` | Run 状态枚举 |
| `active_stage` | `VARCHAR(32)` | 当前 stage；terminal 时为空或保留最后 stage，API 统一约定为空 |
| `next_retry_at` | `TIMESTAMPTZ` | Job 暂不可执行时的下一次可重试时间；终态为空 |
| `last_heartbeat_at` | `TIMESTAMPTZ` | stuck 判断和运维观测；Job lease 仍是协调事实 |
| `cancel_requested_at` | `TIMESTAMPTZ` | 记录用户取消请求时间 |
| `dead_lettered_at` | `TIMESTAMPTZ` | 达到最终失败时写入 |
| `dead_letter_reason` | `VARCHAR(500)` | 脱敏、截断后的最终原因 |
| `last_error_code` | `VARCHAR(64)` | 稳定机器码 |
| `last_error` | `VARCHAR(500)` | 脱敏、截断后的错误摘要 |
| `origin` | `VARCHAR(32) NOT NULL CHECK (origin IN ('API','LOCAL_CREATE','FILE_UPLOAD','DOCUMENT_EMBED','REEMBED_MISSING','BATCH_EMBED','DERIVATION_REPAIR','DOCUMENT_MUTATION','PDF_TO_RAG','EXTERNAL_SYNC','OTHER'))` | Processing Run 的规范化来源枚举；不直接复制 Job 的任意原始来源字符串 |
| `requested_by_principal_id` | `VARCHAR(128)` | principal ID，不存 secret |
| `supersedes_run_id` | `UUID FK self ON DELETE SET NULL` | 新 Run 取代的历史 Run |
| `legacy_bridge` | `BOOLEAN NOT NULL DEFAULT false` | 是否由旧 Job 惰性桥接，避免伪造完整阶段历史 |
| `created_at/started_at/finished_at/updated_at` | `TIMESTAMPTZ` | 生命周期审计时间 |

Run 表的 `status` 必须 check 为 `QUEUED/RUNNING/COMPLETED/FAILED/CANCELLED/STALE/DEAD_LETTERED`；
`active_stage` 为空或只能取五个固定 stage 名称（实现为显式 `CHECK`）。`dead_lettered_at`、`dead_letter_reason` 只在
`DEAD_LETTERED` 时写入，其他终态必须为空；`next_retry_at` 只允许在 `QUEUED` 且存在下一次
自动 attempt 时写入。`document_kind`、hash、generation 和 `max_attempts` 继续使用表中声明的
范围约束。

Run `origin` 的公开枚举固定为 `API`、`LOCAL_CREATE`、`FILE_UPLOAD`、`DOCUMENT_EMBED`、
`REEMBED_MISSING`、`BATCH_EMBED`、`DERIVATION_REPAIR`、`DOCUMENT_MUTATION`、`PDF_TO_RAG`、
`EXTERNAL_SYNC`、`OTHER`。创建 Run 时将现有 Job 的原始 `origin` 规范化：先去掉
`_DUPLICATE_FORCE` 后缀，再按下表映射；表中的原始值来自当前所有会进入
`EmbeddingDispatchService.enqueueInCurrentTransaction` 的生产入口，实施时必须为映射函数补充单测。

| 现有 Job `origin` 原始值 | Processing Run `origin` | 说明 |
|---|---|---|
| `API` | `API` | `/embedding-jobs` API 创建 |
| `LOCAL_CREATE`、`BATCH_CREATE`、`COLLECTION_CLONE`、`COLLECTION_IMPORT` | `LOCAL_CREATE` | 本地创建、批量创建、集合克隆/导入；当前集合导入通常为 `SKIP`，保留映射以覆盖未来异步入口和历史 Job |
| `FILE_UPLOAD` | `FILE_UPLOAD` | 文本文件上传 |
| `DOCUMENT_EMBED` | `DOCUMENT_EMBED` | 单文档异步嵌入 |
| `REEMBED_MISSING` | `REEMBED_MISSING` | 缺失向量批量修复 |
| `BATCH_EMBED` | `BATCH_EMBED` | 指定文档批量嵌入 |
| `DERIVATION_REPAIR` | `DERIVATION_REPAIR` | 派生完整性修复 |
| `LOCAL_PATCH`、`LOCAL_RESTORE`、`LOCAL_VERSION_RESTORE` | `DOCUMENT_MUTATION` | 本地文档后续变更/恢复 |
| `PDF_TO_RAG` | `PDF_TO_RAG` | PDF 导入后的 Markdown 派生 |
| `EXTERNAL_UPSERT`、`JSON_RECORD_UPSERT`、`SYNC_RUN_ITEM` | `EXTERNAL_SYNC` | 外部文档、JSON record 和 `DocumentSyncRun` item；不能把 `DocumentSyncRun` ID 当作 Processing Run ID |
| 其他值 | `OTHER` | 保留 Job 原始值供旧查询使用，但不扩大 Run 公开枚举 |

现有 Job 表继续保留原始 `origin` 字符串和兼容查询，不新增 DB enum，也不因新 Run 的低基数要求
拒绝既有调用入口。动态来源如 `LOCAL_CREATE_DUPLICATE_FORCE` 必须先去后缀再按基值映射；来源
规范化不能因为未知值而使原 Job 入队失败。

索引和唯一约束：

- `UNIQUE (embedding_job_id) WHERE embedding_job_id IS NOT NULL`，避免一个 Job 同时被两个 Run 解释。
- `UNIQUE (document_id, embedding_profile_id, attempt_no)`，保证手动处理历史序号不重复；序号分配采用
  有界的“尝试插入 + 唯一冲突后重读重试”，不使用显式锁。
- `UNIQUE (document_id, embedding_profile_id) WHERE status IN ('QUEUED','RUNNING')`，保证同一文档/profile
  只有一个 active Processing Run；并发创建用唯一约束兜底，不用锁。
- `(document_id, created_at DESC, id)`：历史和 document detail 查询。
- `(status, updated_at, id)`、`(dead_lettered_at, updated_at, id)`：housekeeping、稳定分页和死信列表；
  housekeeping 的 active 扫描仍应优先使用 `status IN ('QUEUED','RUNNING')` 条件。
- `CHECK` 与现有 Job 的 status、document kind、generation、hash 约束保持等价。

不保存：文档正文、chunk 文本、向量、provider response、API Key、lease token、无限制 JSON payload。

### 4.2 新表：`rag_document_processing_stages`

字段冻结如下：

| 字段 | 类型/约束 | 用途 |
|---|---|---|
| `run_id` | `UUID NOT NULL REFERENCES rag_document_processing_runs(id) ON DELETE CASCADE` | 所属 Run |
| `stage_name` | `VARCHAR(32) NOT NULL CHECK (stage_name IN ('PARSING','CHUNKING','EMBEDDING','INDEXING','FINALIZING'))` | 五个固定 stage |
| `stage_order` | `SMALLINT NOT NULL CHECK 1–5` | 稳定排序，不依赖字符串排序；必须与 stage_name 一一映射为 1/2/3/4/5 |
| `status` | `VARCHAR(20) NOT NULL CHECK (status IN ('PENDING','RUNNING','SUCCEEDED','FAILED','SKIPPED','CANCELLED','STALE'))` | Stage 状态 |
| `execution_attempt` | `INTEGER NOT NULL DEFAULT 0` | 最近执行次数 |
| `progress_current/progress_total` | `INTEGER NOT NULL DEFAULT 0 CHECK (progress_current >= 0 AND progress_total >= 0 AND (progress_total = 0 OR progress_current <= progress_total))` | 有界计数；total=0 表示未知 |
| `progress_label` | `VARCHAR(128)` | 低敏阶段诊断，例如 `LOCAL_INDEX`、`REMOTE_CHUNKS`；不存正文 |
| `last_error_code` | `VARCHAR(64)` | 阶段错误机器码 |
| `last_error` | `VARCHAR(500)` | 脱敏阶段错误 |
| `started_at/finished_at/last_heartbeat_at/updated_at` | `TIMESTAMPTZ` | 阶段时间线 |

主键为 `(run_id, stage_name)`；数据库约束还必须用 `CASE`/等价 check 保证 `PARSING=1`、
`CHUNKING=2`、`EMBEDDING=3`、`INDEXING=4`、`FINALIZING=5`。数据库约束不能仅靠普通行级 check
保证“每个 Run 恰有五行”。Run 创建事务必须一次插入五个固定 stage，repository
和 PostgreSQL 集成测试必须验证五行完整性。进度更新使用同一 Job owner/lease/generation 条件，
不能因 HTTP 查询而写入。

### 4.3 迁移和兼容策略

1. V61 创建两张新表、约束、索引和注释，并对 `rag_embedding_jobs` 做一次向后兼容的可空字段扩展；
   不改写 V1–V60，不删除旧字段。
2. 不在 V61 中批量为历史 Job 生成 UUID Run；避免依赖未在当前迁移基线声明的 UUID 扩展，也避免
   在一次 Flyway migration 中写入不可逆的大量历史状态。
3. 新 Job 创建事务内必须同时创建 Run 和五个 stage；`EmbeddingJobRepository.createOrCoalesce`
   合流到已有 active Job 时，优先读取该 Job 的 Run，缺失才由 `DocumentProcessingRunBridge` 创建。
4. `attempt_no` 不依赖 `MAX()+1` 的无保护读：创建 Run 时尝试写入候选序号，若唯一约束冲突则在
   有界次数内重新读取并重试；超限返回可重试错误，不留下第二条 Run。
5. worker claim、Job GET/当前分页返回的 Job list、文档 lifecycle read 和新 Run list 对“active Job 没有
   Run”的情况执行有界惰性 bridge：先核对该 Job 是否仍等于 `rag_document_embedding_state.active_job_id` 且 generation 是当前
   generation。若是当前 Job，建立 `legacy_bridge=true` 的 active Run；若已被新 generation supersede，
   则建立 `STALE` 的历史 bridge，不占用 active Run 唯一槽。两种 bridge 都依据 `progress.stage` 做尽可能
   准确的 stage 投影，API 明确 `stageHistoryComplete=false`，不伪装成完整历史。
6. 已结束且从未被查询的历史 Job 可以保持没有 Run；旧 `/embedding-jobs` API 仍是这些历史记录的事实
   入口。Job list 只允许为当前返回页内的 active Job 做有界 bridge，不得让一次读请求扫描并补全全部历史。
7. 若同一时刻两个 bridge 竞争，依靠 `UNIQUE embedding_job_id` 或 active unique 捕获冲突，然后重新
   查询已创建 Run；失败不得生成第二条 active Run。

## 5. 对外 API 与兼容契约

### 5.1 新增 API DTO

在 `spring-ai-rag-api/src/main/java/com/springairag/api/` 增加：

- `DocumentProcessingRunStatus`：对应 Run 状态。
- `DocumentProcessingStageName`：`PARSING/CHUNKING/EMBEDDING/INDEXING/FINALIZING`。
- `DocumentProcessingStageStatus`：对应 stage 状态。
- `DocumentProcessingStageResponse`：`stageName/stageOrder/status/executionAttempt/progressCurrent/
  progressTotal/progressLabel/startedAt/finishedAt/lastHeartbeatAt/updatedAt/lastErrorCode/lastError`。
- `DocumentProcessingSummary`：放进 lifecycle 的轻量摘要，至少含 `runId/status/activeStage/executionAttempt/
  maxAttempts/nextRetryAt/retryable/deadLettered/stageHistoryComplete/updatedAt/lastErrorCode/lastError`；
  这里的 `executionAttempt` 只表示当前 Job 的自动执行次数，不表示手动处理历史序号。
- `DocumentProcessingRunResponse`：完整 Run 快照，至少包含 `runId/documentId/embeddingProfileId/
  embeddingJobId/requestGeneration/documentVersion/contentHash/documentKind/chunkerVersion/attemptNo/
  executionAttempt/maxAttempts/status/activeStage/nextRetryAt/cancelRequestedAt/deadLetteredAt/
  deadLetterReason/lastErrorCode/lastError/origin/supersedesRunId/legacyBridge/createdAt/startedAt/
  finishedAt/updatedAt/stages`；其中 `attemptNo` 是手动处理历史序号，`executionAttempt` 是当前 Job
  自动执行次数，
  不返回 lease owner/token、正文或向量；增加可选 `coalesced`，GET/普通 cancel 为 `null`，retry
  返回 `true/false` 表示是否合流到已有 active target。
- `DocumentProcessingRunPageResponse`：与现有 Job 列表保持一致的稳定分页 envelope，固定字段为
  `items/page/size/totalElements/totalPages`；默认 `page=0,size=50`，服务端将 `size` 限制在 1–200。

### 5.2 新增 Processing Run API

新增独立 `DocumentProcessingRunController`，避免继续把文档 mutation、SSE、旧 Job API 全塞进
`RagDocumentController`。路径固定为：

```text
GET  /api/v1/rag/document-processing-runs/{runId}
GET  /api/v1/rag/document-processing-runs
POST /api/v1/rag/document-processing-runs/{runId}/cancel
POST /api/v1/rag/document-processing-runs/{runId}/retry
```

源码必须遵循当前版本化 Controller 约定：使用 `@ApiVersion("v1")` 与
`@RequestMapping("/rag/document-processing-runs")`，上面列出的 `/api/v1/...` 是对外有效 URL，
不要把 `/api/v1` 再直接写进 Controller 的 `@RequestMapping`。

契约：

- GET 单项：在全局 `ApiCapabilityFilter` 已通过 `read` 能力检查后，返回完整 stage 时间线；Run 不存在、
  文档不存在或 document/collection ACL 不可见时统一返回 not found 语义，避免泄露文档/集合存在性。
  缺少 API capability 仍遵循现有过滤器的标准 `403`，不由本资源层伪装成 `404`。
- GET 列表：支持 `documentId`、`collectionKey`、`status`、`deadLettered`、`page`、`size`；先按 ACL
  解析可见 collection，再在 SQL 中过滤，不把所有 Run 查出内存后过滤；`status` 使用公开 Run enum，
  `deadLettered` 只作为 `DEAD_LETTERED` 的便捷筛选，默认 `page=0,size=50`；结果固定按
  `created_at DESC, id DESC` 排序，分页不能依赖数据库默认顺序。
- cancel：`200` 返回当前快照；重复调用 terminal Run 仍返回当前状态；queued Job 可立即 terminal，
  running Job 只设置取消请求，由 worker 在 heartbeat/commit 边界收尾。若 cancel CAS 影响 0 行，
  service 必须重新读取 Job/Run 后返回最终快照，不能返回进入请求时缓存的 `RUNNING` 或其他旧状态。
- retry：只接受源 Run 为 `FAILED/DEAD_LETTERED/CANCELLED/STALE`，并且先按
  `DocumentProcessingSummary.retryable` 规则校验；源 Run 为 `QUEUED/RUNNING` 时返回 `409
  CONCURRENT_MODIFICATION`，不改变它。对可重试终态，若已有相同 document/profile/快照的 active target，
  返回 `202` 的合流 target，并以 `coalesced=true` 表示没有重复创建；没有 target 时才新建 Run/Job，返回
  `202 Accepted` 的新 Run 快照。被新 generation 取代且当前没有 target 的 `STALE` Run，必须重新校验
  当前文档快照，校验失败沿用现有 `DOCUMENT_DISABLED`/校验错误，不创建孤立 Run，也不能把不可重试的
  `STALE` 当作普通 retry 接受；可选 `maxAttempts` query 参数沿用现有 Job retry 的校验和 1–10 上限。
- Processing Run 创建不新增一个与 `/embedding-jobs` 重复的公开 create API；创建入口仍是现有
  `/api/v1/rag/embedding-jobs`、文档 ASYNC dispatch、外部文档 mutation 的既有入口。文档
  `EmbeddingPolicy.SYNC` 直接调用 `DocumentEmbedService` 的路径不创建 Run，保持现有同步契约。
- 不新增 SSE replay。本批客户端通过 GET 轮询或生命周期摘要恢复；现有直接同步/SSE endpoint 保持
  原语义且不创建 Run，客户端断开不改变该次直连执行的既有行为；经持久化 Job 的异步路径也不因
  客户端断开而自动取消，取消必须调用 Job/Run action。

### 5.3 现有 API 的兼容扩展

1. `EmbeddingJobResponse` 增加可选 `processingRunId`；保留现有字段、构造器和 Job status 字符串。
2. `EmbeddingJobService.create/get/list/cancel/retry` 在存在 Run 时返回关联 ID；没有 Run 的历史 Job 返回
   null，不阻断旧客户端。单项查询允许一次关联查询，分页列表必须对返回页的 Job ID 批量查询 Run 映射，
   不为每个 Job 执行 N+1 查询；active legacy bridge 也只处理当前返回页。
3. `EmbeddingJobService.retry` 改为调用统一 retry façade。旧 endpoint 保持路径和 HTTP 形态，但响应的
   `id` 可能是新 retry target；`coalesced=true` 明确表示请求没有新建独立 active target。
4. `DocumentLifecycleResponse` 保留现有九个字段，新增可选 `processing` 摘要。必须提供兼容旧参数的
   构造器，避免现有 core 测试、demos 和 WebUI mock 编译/反序列化破坏。
5. `DocumentDetailResponse`、`DocumentSummary`、mutation response 通过已有 lifecycle 嵌入获得摘要，
   不另造一套互相矛盾的 processing 字段。
6. `processingStatus` 文档列表过滤继续解释旧 `rag_documents.processing_status`，不在本批偷偷改成新
   Run status；若需要按 Run 筛选，使用新列表 API。

`DocumentLifecycleService` 的 `integrityRepository` 分支和 SQL fallback 分支必须调用同一个
`DocumentProcessingStateMapper`/summary 查询 helper，不能只在其中一条路径拼接 `processing`。摘要选择
规则固定为：`rag.document-processing.enabled=false`、禁用或 tombstone 文档返回 `processing=null`；否则优先返回当前 document/profile 下与
`rag_document_embedding_state` 当前 `request_generation/content_hash/chunker_version` 匹配的 active Run，
没有 active Run 时返回同一当前派生快照下最近的 terminal/stale Run；旧 generation 被新 generation
取代的 Run 不得覆盖当前摘要。直接同步 `DocumentEmbedService` 路径没有 Run 时保持 `processing=null`。

### 5.4 兼容状态映射

| Processing Run | 旧 lifecycle `embeddingStatus` | 旧 `searchability` |
|---|---|---|
| `QUEUED/RUNNING` 且底层 freshness 未完成 | `INDEXING` | 由现有 local/vector freshness 推导，通常 `INDEXING` 或 `KEYWORD_ONLY` |
| `COMPLETED` 且 vector/local freshness 当前 | `READY` | `READY` |
| `FAILED/DEAD_LETTERED` 且 local fresh | `FAILED` | `KEYWORD_ONLY` |
| `FAILED/DEAD_LETTERED` 且 local 也不 fresh | `FAILED` | `FAILED` |
| `CANCELLED/STALE` | 保持已有 state row/job 推导 | 不用 Run 单字段覆盖已有 searchability |
| disabled/tombstoned document | `DISABLED` | `DISABLED` |

Run 是过程账本，`DocumentLifecycleService` 仍必须以 local state、embedding state、向量实际行数、
content hash、chunker version 和 active profile 推导搜索可用性；不能为了显示进度而把 `COMPLETED`
直接等同于 `READY`。

## 6. 事务、并发、失败和安全语义

### 6.1 创建与合流事务

在现有 `EmbeddingDispatchService.enqueueInCurrentTransaction` / `EmbeddingJobService.create` 的同一
事务中按以下顺序执行：

1. 已完成 document/collection ACL、enabled、content hash、profile 和 descriptor 校验。
2. 查询同 document/profile/content hash/document kind/chunker version 的 active Job；若有 active Job，
   查询对应 Run，force/max attempts 做合流更新，不新增第二个 active Run。
3. 没有 active Job 时按现有逻辑 `allocateGeneration`、`cancelSuperseded`；随后先 CAS 标记旧
   generation 的 active Run 为 `STALE`，再调用现有 `createOrCoalesce` 建立或取得 Job。只有返回
   `coalesced=false`（本事务真正创建了 Job）时，才插入新 Run 和五条 stage、写入
   `embedding_job_id`，最后 `activateJob`。返回 `coalesced=true` 时不得先留下临时 Run：事务内回读
   该 Job 的既有 Run；若缺失，只能按 §4.3 建立 `legacy_bridge=true` 的 Run 并通过唯一约束竞争收敛。
   旧 Run 的唯一索引因此不会阻塞合法的新 generation。
4. 更新旧 `rag_documents.processing_status='PENDING'` 作为兼容投影，但不把它当作 Run 真相。
5. 事务提交后才发布现有 `EmbeddingJobsAvailableEvent`；事务失败不得唤醒一个不存在的 Job。

`createOrCoalesce` 使用 `INSERT ... ON CONFLICT DO UPDATE`，合流不会以异常形式暴露；实现必须显式
检查 `CreateResult.coalesced`。若新建 Job 后 Run/stage 插入失败，整个事务回滚；若取得的是已有 Job，
则只回读或竞争创建其 Run，失败时有界重读，不插入无 Job 的 provisional Run，也不删除并发请求已经
提交的既有 Run。所有写入方法都返回影响行数并校验，不静默忽略 CAS 失败。

### 6.2 Worker 阶段更新

不改现有 Job claim 的锁实现，新增 Run/Stage repository 方法时使用类似下面的条件：

```sql
UPDATE rag_document_processing_runs run
SET status = ?, active_stage = ?, last_heartbeat_at = CURRENT_TIMESTAMP,
    updated_at = CURRENT_TIMESTAMP
WHERE run.id = ?
  AND run.embedding_job_id = ?
  AND run.request_generation = ?
  AND run.status IN ('QUEUED', 'RUNNING')
  AND EXISTS (
      SELECT 1
      FROM rag_embedding_jobs job
      WHERE job.id = run.embedding_job_id
        AND job.status = 'RUNNING'
        AND job.lease_owner = ?
        AND job.lease_expires_at >= CURRENT_TIMESTAMP
        AND job.request_generation = run.request_generation
        AND job.content_hash = run.content_hash
  )
```

实施时 SQL 必须同时带上 `run.id`、`run.embedding_job_id`、`run.request_generation`、
`run.content_hash`、`run.embedding_profile_id`、`run.document_kind`、`run.chunker_version`，以及
`run.status IN ('QUEUED','RUNNING')`、`job.status = 'RUNNING'`、`job.lease_owner = ?`、
`job.lease_expires_at >= CURRENT_TIMESTAMP`、`job.cancel_requested_at IS NULL`、
`job.request_generation = run.request_generation`，并通过 `rag_document_embedding_state.active_job_id = job.id`
确认该 Job 仍是当前活动 Job。Stage 更新还必须限定预期的 `stage_name` 和当前 stage 状态，避免
重试或完成事务覆盖后续状态。允许用 join/exists 表达这些条件，但不能改成 `FOR UPDATE` 或先查后
无条件 update；本段冻结的是可直接实现的条件集合和并发语义。

上面的条件集合只适用于正常进度、heartbeat 和成功路径。取消有两条独立的 mirror 路径：queued Job
被 HTTP cancel 直接置为 `CANCELLED` 后，以 `job.status='CANCELLED'`、同一 job/generation/hash/profile
和 Run active 条件收尾 Run/Stage；running Job 则由持有有效 lease 的 worker 在发现
`cancel_requested_at IS NOT NULL` 后，以 owner/lease/generation 条件将 Job 置为 `CANCELLED`，再
收尾 Run/Stage。取消 mirror 不得复用要求 `cancel_requested_at IS NULL` 的正常进度 SQL，也不能在
失去 lease 后写入。

- 影响行数为 0 表示 worker 已失去资格：停止继续写 Run/Stage，不覆盖新 worker/新 generation。
- heartbeat 同时刷新 Job lease 和 Run `last_heartbeat_at`；先后顺序都必须能在失去任一条件时安全退出。
- `DocumentEmbedService` 增加受控 stage callback/adapter，而不是在每个 chunk 上直接写数据库；保留
  `embedDocument`、`embedDocumentWithProgress`、`embedDocumentForJob` 现有调用签名和直接同步/SSE
  语义，新 callback 只由 Job executor 注入。现有 direct path 不能因为复用内部方法而意外创建 Run 或
  改变 provider failure 的返回/记录方式。
- 现有 `EmbeddingCommitGuard` 仍在向量替换事务内调用 `claimCommitAllowed`；只有提交门成功才能将
  `INDEXING` 设为成功。成功路径随后先把 `FINALIZING` 标为 `SUCCEEDED`，再以现有 owner/lease 条件
  执行 Job `markSucceeded`；只有该更新影响 1 行，Run 才能转为 `COMPLETED`。Job 已成功但 Run 镜像
  尚未来得及写入时，由 housekeeping 按 §6.4 的 terminal mirror 收尾；Job CAS 失败时不得把 Run
  伪装成完成，必须按取消或 stale 竞态处理。

`cancelSuperseded` 当前复用 Job 的 `cancel_requested_at` 标记，不能单凭该字段把失效任务判为用户取消。
worker 处理 `EmbeddingCommitRejectedException`、快照不匹配或 lease 失效时，必须先用
`rag_document_embedding_state.request_generation <> job.request_generation` 或
`active_job_id IS DISTINCT FROM job.id` 判定是否已经被新 generation 取代；generation 失效优先映射为
`STALE`，即使 `cancel_requested_at` 非空。只有 generation 仍是当前 active Job 时，`cancel_requested_at`
才映射为用户 `CANCELLED`。对应的 Job/Run/Stage mirror 和测试必须覆盖“supersede 写取消标记后旧 worker
收尾”的竞态，不能沿用当前 `terminalWithoutCommit` 先检查取消的顺序。

### 6.3 失败、取消、stale 和 dead-letter

- **provider/校验临时失败**：调用现有 `markFailure`；若 `attempt_count < max_attempts`，Job 回到
  `QUEUED`，Job 清空 `terminal_reason`，Run 保持可重试状态并记录错误与 `availableAt`；失败 stage 保留本次错误并在下一次
  attempt 开始前重置为 `PENDING`，下游 stage 保持 `PENDING`。若达到上限，Job `FAILED`，Run
  `DEAD_LETTERED`，最后失败 stage 为 `FAILED`，下游未执行 stage 为 `SKIPPED`，并使用
  `UPSTREAM_STAGE_FAILED` 表示未执行原因。
- **确定性、不可重试失败**：在输入快照/结果校验能确定重试不会改变结果时，新增与现有 owner、lease、
  generation 条件一致的原子 `markNonRetryableFailure`，直接把 Job 置为 `FAILED` 并写
  `terminal_reason=NON_RETRYABLE`；Run 置为 `FAILED` 而不是 `DEAD_LETTERED`，不写 `dead_lettered_at`。
  executor 必须使用内部的 typed failure classification（不新增对外 DTO）：文档不存在/已禁用、
  不支持的 document kind/chunker、确定性 embedding 结果维度/数量校验失败归为 `NON_RETRYABLE`；
  provider 暂时不可用、网络错误、限流、超时等归为 retryable 并继续走有界 `markFailure`，达到上限时写
  `terminal_reason=RETRY_EXHAUSTED`，不能被错误地归为永久失败。当前文档已变更导致的快照/generation
  不匹配仍按 stale 竞态处理，不走这两类失败。
- **不可提交/stale**：只有在重新读取后确认 document snapshot/profile/chunker/generation 已不再匹配，
  或 `active_job_id IS DISTINCT FROM job.id` 时，`EmbeddingCommitRejectedException`/snapshot mismatch
  才进入 `STALE`，并清空 Job `terminal_reason`；generation 失效判定优先于复用的
  `cancel_requested_at`，不得把 supersede 误报为用户取消，也不得调用普通失败 retry。若只是当前 worker
  丢失 owner/lease、但 Job 仍是当前 generation 的 `QUEUED/RUNNING` active Job，则不写 terminal 状态，
  保留 Run active，交给既有 claim/lease recovery；旧 Job 结果不得改写当前 state。
- **显式取消**：queued Job 立即 `CANCELLED`；running Job 只写 `cancel_requested_at`，provider 调用
  不强杀，heartbeat/commit guard 发现取消后不写向量，最终 Job/Run/Stage 变为 `CANCELLED`，并清空
  Job `terminal_reason`。
- **取消与完成竞态**：谁先成功取得带 owner/lease/状态条件的终态更新谁生效；另一方必须重新读取
  当前 Job/Run 状态，不返回“已取消但实际已完成”或“仍运行但已取消”的伪结果。HTTP cancel、worker
  terminal mirror 和 housekeeping 都适用该规则。
- **手动 retry**：不复活旧 Run，不清空历史错误；以新 Run/new Job 进入 `QUEUED`，新 Run 的
  `supersedes_run_id` 指向旧 Run，从而保留可追溯关系。
- **错误脱敏**：沿用 `SensitiveDataMaskingConverter`，Run、Stage、Job 的公开错误最多 500 字符，
  不包含 Authorization、API key、lease token、完整 provider payload。

Worker lease 过期且已达到 `max_attempts` 的现有 claim 分支也必须写
`terminal_reason=RETRY_EXHAUSTED`；手动 retry 将其清空并创建新的 Run/Job，不复活原 Run。

### 6.4 stuck-task housekeeping

新增有界的 `DocumentProcessingHousekeeping`，不创建第二个任务队列。每次扫描最多处理配置上限的
active Run，按以下顺序：

1. 对应 Job `SUCCEEDED/FAILED/CANCELLED/STALE` 而 Run 仍 active：按固定映射补写 Run/Stage——
   `SUCCEEDED -> COMPLETED`、`CANCELLED -> CANCELLED`、`STALE -> STALE`；`FAILED` 按
   `terminal_reason=RETRY_EXHAUSTED` 映射为 `DEAD_LETTERED`，按 `NON_RETRYABLE` 映射为 `FAILED`。
   历史 Job 的 `NULL` 使用 §4.3 的兼容推导。Stage 沿用 §3.3 的 terminal 收尾规则。这覆盖
   “Job terminal 与 Run mirror 之间进程崩溃”的窗口。
2. 对应 Job `QUEUED` 且 `available_at` 尚未到期：不判 stuck，保留 Run queued。
3. 对应 Job `RUNNING` 且 lease 未过期或 Run heartbeat 新鲜：不判 stuck。
4. 对应 Job `RUNNING` 且 lease 已过期：本轮只交给既有 `EmbeddingJobWorker.claim` 抢救，不在同一轮把
   Run 标 stale；下一次扫描若已没有对应的 `QUEUED/RUNNING` Job 且 `last_heartbeat_at` 超过阈值，才进入
   下一步，避免与 worker 争抢。
5. Run active、没有对应 active Job、且以 `COALESCE(last_heartbeat_at, updated_at, created_at)` 计算的
   最后活动时间超过阈值：以 CAS 标为 `STALE`，所有非 terminal stage 标为 `STALE`，错误码为
   `PROCESSING_RUN_STUCK`。新建后尚未被 claim 的 Run 也因此不会因为 `last_heartbeat_at` 为 `NULL` 而
   永久留在 active 状态；仍有 queued Job 的 Run 必须先按前两步保留。
6. 任何一步都必须带 `status`、`run_id`、generation 和“无更新 active target”的条件；新 generation/
   新 Run 出现后旧扫描不得改写它。

Housekeeping 不自动无限 retry，不删除 dead-letter，不凭“没有队列记录”立即判失败；它只做有界
reconcile/stale 收尾。worker 自身的 lease 恢复仍是主路径。

### 6.5 安全与数据范围

- 单项 GET/cancel/retry：Run → document → collection，调用 `ApiKeyCollectionAccess.requireDocumentAccess`；
  不授权时返回 not found 语义。
- 列表：复用 Job 列表的 `allowedCollectionIds` SQL 过滤和 `collectionKey` scope 解析；禁止先查全表
  再内存过滤。
- 新 controller 的 GET/POST 路径必须纳入当前 `ApiCapabilityFilter` 的 read/write 规则；本批不扩大
  capability 种类，不把 retry/cancel 错标成 read。
- `requested_by_principal_id` 只存规范化 ID；API response 不返回 lease owner/token；`origin` 只允许
  有限枚举/长度。
- run/stage 的错误和 progress 只能存计数、机器码、短摘要；不把文档正文、chunk 文本、向量、
  外部同步 lease token 或 provider secret 写入数据库。

## 7. 配置、观测与成本边界

### 7.1 配置

新增 `RagDocumentProcessingProperties` 并挂到 `RagProperties`，在 `application.yml` 增加：

```yaml
rag:
  document-processing:
    enabled: ${RAG_DOCUMENT_PROCESSING_ENABLED:true}
    housekeeping-enabled: ${RAG_DOCUMENT_PROCESSING_HOUSEKEEPING_ENABLED:true}
    housekeeping-interval-ms: ${RAG_DOCUMENT_PROCESSING_HOUSEKEEPING_INTERVAL_MS:60000}
    stuck-threshold-seconds: ${RAG_DOCUMENT_PROCESSING_STUCK_THRESHOLD_SECONDS:300}
    max-housekeeping-runs-per-scan: ${RAG_DOCUMENT_PROCESSING_MAX_HOUSEKEEPING_RUNS_PER_SCAN:100}
    stage-progress-write-interval-ms: ${RAG_DOCUMENT_PROCESSING_STAGE_PROGRESS_WRITE_INTERVAL_MS:1000}
```

setter 必须有边界：interval 至少 10 秒、stuck 至少 60 秒且不超过 3600 秒、单次 housekeeping 至少
1 且不超过 1000、progress interval 至少 1000 毫秒。`EmbeddingJobProperties` 的现有 lease、retry、
worker concurrency 仍是单一事实源，不在新配置中复制。

### 7.2 日志和 Micrometer

每次状态转换至少记录结构化字段：`runId`、`jobId`、`documentId`、`requestGeneration`、`stage`、
`from`、`to`、`attempt`、`origin`、`errorCode`。禁止记录正文、向量、token。

新增或复用以下低基数指标：

- `rag.document-processing.active` gauge：active Run 数。
- `rag.document-processing.runs` counter：按 `status`、`origin`、`terminal_reason` 统计；不带 documentId。
- `rag.document-processing.stage.duration` timer：按 stage、结果统计。
- `rag.document-processing.housekeeping.reconciled` counter：按动作 `terminal_sync/stale/dead_letter/skip`。
- `rag.document-processing.stuck` counter：按 stage，不带文档、collection、principal。

所有 label 必须是有限枚举；不能把 runId、documentId、错误文本放进 metric label。日志错误摘要沿用
现有 masking 和 500 字符上限。

### 7.3 成本和性能上限

- 阶段边界写入最多每个 Run 10 次左右；chunk 级进度只内存聚合，按 1 秒窗口写一次，不能每个 chunk
  一次 SQL。
- 每次 housekeeping 默认最多处理 100 个 Run，配置硬上限为 1000；分页按 `(updated_at,id)` 稳定排序，
  单次扫描超时下次继续。
- stage 表只存计数和短错误，不存 chunk/payload，避免大文档导致 DB 膨胀。
- 新 API 分页上限 200，与现有 Job API 保持一致；不提供无限历史导出。

## 8. 文件级实施顺序与独立交付切片

下面是实施顺序，不是当前已完成清单。每个切片完成后先更新对应 progress，再进入下一切片；不得
先改后补文档。

### Slice 1：API 状态和读模型骨架

修改/新增：

- `spring-ai-rag-api/src/main/java/com/springairag/api/enums/DocumentProcessingRunStatus.java`
- `spring-ai-rag-api/src/main/java/com/springairag/api/enums/DocumentProcessingRunOrigin.java`
- `spring-ai-rag-api/src/main/java/com/springairag/api/enums/DocumentProcessingStageName.java`
- `spring-ai-rag-api/src/main/java/com/springairag/api/enums/DocumentProcessingStageStatus.java`
- `spring-ai-rag-api/src/main/java/com/springairag/api/dto/DocumentProcessingStageResponse.java`
- `spring-ai-rag-api/src/main/java/com/springairag/api/dto/DocumentProcessingSummary.java`
- `spring-ai-rag-api/src/main/java/com/springairag/api/dto/DocumentProcessingRunResponse.java`
- `spring-ai-rag-api/src/main/java/com/springairag/api/dto/DocumentProcessingRunPageResponse.java`
- `spring-ai-rag-api/src/main/java/com/springairag/api/dto/DocumentLifecycleResponse.java`
- `spring-ai-rag-api/src/main/java/com/springairag/api/dto/EmbeddingJobResponse.java`

先冻结 enum、JSON 字段和兼容构造器；DTO 不包含内部 lease owner/token。

### Slice 2：V61 schema 与属性

修改/新增：

- `spring-ai-rag-core/src/main/resources/db/migration/V61__document_processing_runs.sql`
- `spring-ai-rag-core/src/main/java/com/springairag/core/config/RagDocumentProcessingProperties.java`
- `spring-ai-rag-core/src/main/java/com/springairag/core/config/RagProperties.java`
- `spring-ai-rag-core/src/main/resources/application.yml`

先跑 migration/version wiring 测试，确认 V61 在空库、V60 数据库、重复启动和 rollback 前置检查下
可执行。不得修改 V60。

### Slice 3：Processing domain、repository、bridge

实际落地目录及文件：

```text
spring-ai-rag-core/src/main/java/com/springairag/core/documentprocessing/
  DocumentProcessingRun.java
  DocumentProcessingStage.java
  DocumentProcessingRunRepository.java
  DocumentProcessingRunService.java
```

重点实现：创建五阶段、active unique 竞争、stage CAS、heartbeat、terminal reconcile、legacy bridge、
分页查询和 ACL 需要的 document scope。Repository 不得引入悲观锁。依赖方向固定为：processing service
可以依赖现有 document/profile/descriptor/job repository 和 ACL；最终 bridge 和 summary/state mapping
行为由 `DocumentProcessingRunService`、`DocumentProcessingRunRepository` 与生命周期服务的兼容读路径
共同承担，没有额外的 `DocumentProcessingRunBridge` 或 `DocumentProcessingStateMapper` 类。生命周期服务
通过可选注入使用 Run 服务；旧的直接构造测试在未装配账本时仍保留 fallback，未形成循环依赖。

### Slice 4：接入现有 Job 创建和执行链

修改：

- `spring-ai-rag-core/src/main/java/com/springairag/core/embeddingjob/EmbeddingDispatchService.java`
- `spring-ai-rag-core/src/main/java/com/springairag/core/embeddingjob/EmbeddingJobService.java`
- `spring-ai-rag-core/src/main/java/com/springairag/core/embeddingjob/EmbeddingJobRepository.java`
- `spring-ai-rag-core/src/main/java/com/springairag/core/embeddingjob/EmbeddingJobExecutor.java`
- `spring-ai-rag-core/src/main/java/com/springairag/core/embeddingjob/EmbeddingJobWorker.java`
- `spring-ai-rag-core/src/main/java/com/springairag/core/service/DocumentEmbedService.java`
- `spring-ai-rag-core/src/main/java/com/springairag/core/embeddingjob/EmbeddingJob.java`（追加
  `terminalReason`，保留现有构造器兼容重载）

接入顺序：新建/合流先建立 Run；claim 成功后把旧 Job 的 `CLAIMED` 视为 Run 已进入 `RUNNING`，先执行
lease-reclaim 的 stage reset，再启动
`PARSING`；输入快照校验成功后推进 `CHUNKING`，现有 `EMBEDDING` progress 只映射为新
`EMBEDDING` stage，现有 `COMMITTING` 只映射为 `INDEXING`，向量提交门成功后再执行
`FINALIZING` 并镜像 Run/Job terminal。provider 失败、commit reject、success、cancel 分别按 §6.3
镜像 Run/Stage；在 Job record/mapper 中同步 `terminal_reason`，heartbeat 同时更新 Run；不改现有
Job fencing 的条件。

### Slice 5：统一 lifecycle 与 HTTP actions

新增/修改：

- `spring-ai-rag-core/src/main/java/com/springairag/core/controller/DocumentProcessingRunController.java`
- `spring-ai-rag-core/src/main/java/com/springairag/core/service/DocumentLifecycleService.java`
- `spring-ai-rag-core/src/main/java/com/springairag/core/controller/EmbeddingJobController.java`
- `spring-ai-rag-core/src/main/java/com/springairag/core/controller/RagDocumentController.java`（只在需要
  传递/刷新 lifecycle 摘要时修改）
- `spring-ai-rag-core/src/main/java/com/springairag/core/util/DocumentMapper.java`

先做单项 read/action，再做列表和兼容响应；`DocumentLifecycleService` 的 integrity/fallback 两条读路径
都要覆盖 processing 摘要选择规则；所有 route 做 ACL/capability 测试。

### Slice 6：housekeeping、死信和恢复

新增：

- `spring-ai-rag-core/src/main/java/com/springairag/core/documentprocessing/DocumentProcessingHousekeeping.java`

接入 Spring scheduled；与现有 EmbeddingJobWorker 的低频扫描并行但职责分开。实现 terminal mirror、
stuck CAS、dead-letter 统计和有界分页，不自动删除历史。

### Slice 7：测试、文档、发布门禁

新增/更新测试后，再同步：

- `docs/configuration-zh-CN.md` / `docs/configuration.md`
- `docs/rest-api-zh-CN.md` / `docs/rest-api.md`
- `docs/architecture-zh-CN.md` / `docs/architecture.md`
- `docs/testing-guide-zh-CN.md` / `docs/testing-guide.md`
- `docs/developer-reference-zh-CN.md` / `docs/developer-reference.md`（迁移范围、配置和验证命令）
- `docs/release-checklist-zh-CN.md` / `docs/release-checklist.md`（V1–V61 发布事实）
- `AGENTS.md`（仅将当前迁移上限从 V1–V60 更新为 V1–V61；不展开新正文）
- 如需 WebUI contract mock，只更新对应 API fixture，不改页面行为。

## 9. 一次性验收矩阵

实现前按下表设计测试；实现过程中新增测试必须归入现有矩阵，而不是等 review 临时补洞。

| 风险 | 验证 | 必须证明 |
|---|---|---|
| 状态非法转换 | `DocumentProcessingStateMapper`/状态矩阵单测 | active→terminal、terminal 幂等、失败→queued、retry 新 Run、stage reset 全覆盖 |
| Run/Job 创建竞争 | service 单测 + PostgreSQL 并发集成 | 同一 doc/profile 只有一个 active Run/Job；合流不丢 force/max attempts |
| V61 schema | `MigrationVersionsTest`、Flyway PostgreSQL 集成 | V1–V61 顺序正确；两张 Run/Stage 表及 Job `terminal_reason` 的约束、索引、FK、重复 migration 启动正确；不改旧迁移 |
| stage CAS | repository 单测 + PostgreSQL CAS 测试 | owner/lease/generation/hash/profile/chunker 任一不匹配影响 0 行；旧 worker 不能写新 Run |
| lease reclaim stage | PostgreSQL worker recovery | 新 worker claim 后只重置遗留 `RUNNING`/下游未完成 stage，保留已完成 stage；旧 worker 迟到更新全部被拒绝 |
| 成功路径 | executor/service 单测 | 五阶段正确推进；cache hit 的 provider/indexing skip 语义正确；最终 Run/Job/state 一致 |
| provider retry | executor + PostgreSQL | 未到 max attempts 时 Run queued、错误保留、可按 availableAt 重试；达到上限才 dead-letter |
| permanent failure | executor + PostgreSQL | 确定性校验失败原子终止为 Run `FAILED`；不设置 dead-letter；旧 worker 不能再次重试 |
| commit rejection 分类 | executor + PostgreSQL 竞态 | generation/快照失效才标 `STALE`；单纯 owner/lease 丢失保持 active 并可被新 worker 恢复 |
| cancel | service/controller + PostgreSQL | queued 立即取消；running 只请求取消并在安全边界收尾；重复 cancel 幂等；不会写新向量 |
| stale/supersede | generation 竞争集成 | 新内容/新 profile/chunker 创建新 generation；旧结果只得到 STALE，不能覆盖新 state |
| legacy bridge | bridge + PostgreSQL 并发集成 | 当前 Job 缺 Run 建 active bridge；已被新 generation supersede 的旧 Job 只建 STALE bridge，不违反 active unique |
| 手动 retry | service/controller | terminal Run 不复活；新 Run/new Job 可追溯；重复 retry 在 active target 存在时合流 |
| stuck recovery | housekeeping PostgreSQL | fresh lease、不久前 heartbeat、未来 availableAt 均不误判；孤立 active Run 最终 STALE；扫描幂等 |
| terminal mirror | 模拟 Job terminal 与 Run 更新间崩溃 | housekeeping 能补齐 Run/Stage；不会把新的 active generation 标 stale |
| ACL | `EmbeddingJobControllerWebTest`、新 controller WebTest、collection scope 集成 | 单项、列表、cancel、retry 都 fail closed；不可见 collection 不泄露 404/列表总数 |
| capability | `ApiCapabilityFilter` classifier/HTTP tests | GET 需要 read，cancel/retry 需要 write；管理员兼容；不扩大现有 capability |
| API 兼容 | MockMvc JSONPath + DTO constructor tests | 旧 Job/lifecycle 字段和值仍存在；新增字段可选；旧客户端不需要 processing 字段 |
| lifecycle 正确性 | `DocumentLifecycleService*Test` + `DocumentLifecyclePostgresIntegrationTest` | integrity/fallback 两条读路径都选择同一当前 generation Run；Processing summary 不覆盖 freshness 真相；KEYWORD_ONLY/FAILED/READY 映射准确 |
| 错误与隐私 | masking 单测、日志/response 断言 | API key/token/Authorization/正文/向量不出现；错误 ≤500 字符且有稳定 code |
| observability | Micrometer test/日志结构测试 | 指标低基数；stage duration、stuck、dead-letter 可查询；不带 documentId/runId label |
| SSE 边界 | 现有 SSE tests/regression | 单文档/批量/PDF 直接同步或 SSE 行为不变且不创建 Run；持久化 Job 不因客户端断开自动取消；本批不声称支持 replay |
| 同步直连兼容 | `RagDocumentController`/`PdfImportController`/`PdfToRagService`/`DocumentEmbedService` 回归测试 | 直接 `DocumentEmbedService` 路径仍返回原同步结果、不创建 Run；通过 `EmbeddingDispatchService` 的 `SYNC` mutation 仍按 Job 路径创建并推进 Run |
| WebUI 影响 | 现有 Mock Playwright/API mocks | 页面仍能读取旧 lifecycle/embedding job；本批不新增必须存在的 UI 字段；若 mock 需要扩展只加可选字段 |
| 编译与基础测试 | `mvn -pl spring-ai-rag-core -am test` | core/api/documents 相关测试全绿，无 skipped 关键测试 |
| 专项门禁 | `scripts/verify-embedding-jobs.sh`、`scripts/verify-document-lifecycle.sh`、`scripts/verify-no-pessimistic-locks.sh` | 现有任务、生命周期、禁锁门禁全绿 |
| 启动恢复 | `postgresql` profile + disposable PostgreSQL | Flyway V61 后服务启动，重启后 worker/housekeeping 可继续/收尾 |
| 真实 provider | `scripts/start-real-e2e-server.sh`/真实 smoke | 本批不要求真实 LLM；若执行，只证明现有 provider 主链未回归，不把外部网络失败算实现通过 |

### 9.1 PostgreSQL 并发场景最小集合

必须在真实 PostgreSQL 而不是 H2/mock SQL 中验证：

1. 两个请求同时 create：只有一条 active Run 和一条 active Job。
2. worker A lease 过期后 worker B claim；A 的 heartbeat、markFailure、markSucceeded、stage update 全部
   被拒绝，B 可以完成。
3. 新 generation 在 A provider 调用期间创建；A 的 commit guard 和 Run update 均被拒绝。
4. cancel 与 success 竞争；终态只有一个获胜，旧调用不能二次改变 state。
5. housekeeping 与 worker recovery 同时运行；不产生第二个 Run，不误判新鲜 Job。
6. Job terminal 后模拟 Run mirror 未执行，下一次 housekeeping 能幂等补齐。

## 10. 发布、回滚与运维操作

### 10.1 发布顺序

1. 先发布包含 V61、DTO、repository 和配置但仍能关闭 `document-processing` 的版本。
2. 在 disposable PostgreSQL 上跑 migration、CAS、worker、controller 和生命周期集成测试。
3. 保持 `RAG_DOCUMENT_PROCESSING_ENABLED=true`、housekeeping 开启；观察 active Run、dead-letter、
   stuck、stage duration 和旧 Job success rate。
4. 若运行稳定，再让文档详情和后续 WebUI 消费 `lifecycle.processing`；本批不强制前端切换。

### 10.2 回滚

- **运行时回退**：先将 `RAG_DOCUMENT_PROCESSING_HOUSEKEEPING_ENABLED=false`，再视情况将
  `RAG_DOCUMENT_PROCESSING_ENABLED=false`；现有 Embedding Job API、claim、commit fencing 继续工作。
- **数据库回滚**：不得手工删 V61 表，也不得回滚已经执行的 Flyway migration。运行时关闭后保留无害
  的新表/历史数据，修复版本再继续使用；若必须移除，另写经评审的前向清理 migration。
- **接口回滚**：新增 JSON 字段可被旧客户端忽略；旧 endpoint 不删除。若新 controller 有问题，关闭
  processing 后仍允许旧 Job 查询/取消/重试。
- **数据安全**：run/stage 是附加账本，不直接持有正文/向量；删除 document 仍由 FK cascade 清理，
  不允许为了回滚执行破坏性全库操作。

### 10.3 运维恢复路径

1. 先 GET Run 看 `status/activeStage/executionAttempt/lastErrorCode/deadLettered`。
2. `QUEUED/RUNNING` 且业务需要停止时调用 cancel，等待 worker/housekeeping 收尾，不直接改数据库。
3. `DEAD_LETTERED/FAILED/STALE/CANCELLED` 先确认文档仍 enabled、content hash/profile/chunker 合法，再
   调 retry；retry 返回新 Run ID。
4. `STALE` 但存在新的 active Run 时，不要重复 retry 旧 Run；以新 Run 为当前事实。
5. 如果 Run 与 Job 不一致，先等待一个 housekeeping 周期；不能手工把 `rag_documents.processing_status`
   改成 COMPLETED 伪造成功。

## 11. 规划检查范围、进度与完成定义

### 11.1 规划检查范围

实施前严格执行三轮且只检查实质问题：

1. **需求闭环、自包含性、默认决策、非目标**：新开发者能否不依赖聊天记录实施；是否还有阻断性
   决策空洞；WeKnora 借鉴是否被错误扩展到本批。
2. **代码、数据、API、安全、并发、兼容性**：每个设计是否对应当前代码入口；V61/字段/SQL 是否
   可迁移；CAS 是否满足项目禁锁规则；ACL、旧 DTO、lifecycle 和 sync run 边界是否一致。
3. **实施顺序、验收、发布、回滚、恢复和风险**：切片是否可按顺序落地；测试是否覆盖失败竞态；
   关闭/回滚/housekeeping 是否不会误伤现有 Job；成本和观测是否可执行。

每轮检查前重新查看需要的代码、迁移、测试和文档；发现内容错误、逻辑矛盾、关键遗漏或不可实施
设计，立即修改本文件并将计数重置为 `0`。行号漂移、格式和实施时自然暴露的局部命名不触发重置。
无问题轮次只在任务输出总结，不修改本文件。

### 11.2 实施进度记录方式

实施开始后，在同目录创建 `WEKNORA_FIRST_BATCH_DOCUMENT_PROCESSING_PROGRESS.md`，至少记录：

- 分支、基线、工作区已有 WIP；
- Slice 1–7 状态和关键决策；
- 每次验证命令、结果和证据；
- 规划/实现检查计数；
- 已知限制、下一步和恢复入口；
- 不记录任何密钥。

### 11.3 完成定义

只有同时满足以下条件才能报告本批完成：

- 本文件规划检查连续 `3/3`，实施后实现检查也连续 `3/3`。
- V61 migration、Run/Stage/Job CAS、worker、housekeeping、API、ACL、兼容映射和错误脱敏均有自动化证据。
- `mvn` 相关测试、PostgreSQL 并发矩阵、专项脚本和适用启动验证均真实执行并通过；未执行项明确写限制。
- `scripts/verify-no-pessimistic-locks.sh` 通过，没有 `FOR UPDATE`、`SKIP LOCKED` 或 advisory lock。
- `DocumentLifecycleResponse`、Embedding Job API、外部 sync API、SSE 旧契约未被无意破坏。
- 配置、API、架构、测试、开发者命令等行为变化已同步到对应双语长青文档。
- WebUI 未纳入本批的事实已明确，后续 UI 不依赖聊天记录即可接入新 summary/run API。
- `git diff --check`、`./scripts/verify-project-docs.sh` 通过，工作区只含有意修改。

## 12. 当前规划状态

- 本文件：规划检查连续 `3/3` 已通过并获得实施授权；实现后检查在修复 legacy bridge 终态范围
  与 active Run gauge 初始化问题后，已连续 `3/3` 通过。
- 生产代码、V61 迁移、配置和测试已按本规划修改；WebUI 按非目标保持未改。
- 已完成 Slice 1–7 的实现与专项测试；新增账本专项 IT 已覆盖并发合流、lease reclaim、旧 worker
  fencing、取消、supersede、stuck、terminal mirror、不可重试失败和 retry exhausted/dead-letter
  终态矩阵。专项 PostgreSQL 测试为 13/13，修复后相关专项回归为 53/53。
- 最终规划检查（2026-10-12）：第三轮按“实施顺序、验收、发布、回滚、恢复和风险”范围重新核对了
  代码入口、迁移基线、现有 Job/CAS/lease 约束、API 版本化方式、文档门禁和本批变更边界；未发现
  需要修正的内容。`git diff --check` 与 `./scripts/verify-project-docs.sh` 均通过。
- 实施中发现并已修复的门禁/兼容问题：Processing Run PostgreSQL 测试补齐 Testcontainers 回退；
  `verify-gated-it.sh` 改为使用 `-am` 编译 API 依赖；旧 Embedding Job cancel façade 保留取消后的
  更新快照；关闭 document-processing 时新 Run API fail closed、旧 Job API 仍可用且不会继续推进
  Processing Run；`STALE` 终态会清空 `next_retry_at` 以满足 V61 约束；普通历史 terminal Job
  不再被 `processingRunId()` 惰性创建 bridge，只有活跃 Job 或明确 `STALE` Job 才补 bridge。
- 2026-10-14 新增执行器错误分类测试：文档不存在、embedding 维度校验失败进入 `NON_RETRYABLE`；
  provider timeout 仍进入可重试路径。新增 housekeeping 真实 PostgreSQL 测试证明终态镜像、重复扫描
  幂等、fresh queued/running 不误判，以及 lease recovery 与 housekeeping 并发不产生第二条 Run。
- 2026-10-14 全量后端门禁发现一个旧 `EmbeddingJobRepositoryTest` 对 `markStale/markCancelled` 的
  SQL 调用形态断言回归；已恢复共享 `terminalUpdate` 的参数/契约形态，同时保留新增的
  generation/active-state CAS 条件。修复后仓储、bridge、执行器相关 38 个测试全部通过。
- 2026-10-14 实现后第 3 轮检查发现新 Run 创建后 active gauge 未立即刷新；已在创建成功路径补齐
  `refreshActiveMetric()`，修复后专项 53/53、生命周期门禁 14/14、全量 gated IT 24 套件/170 测试、
  文档门禁 16 项和禁锁门禁均通过。随后重新执行实现后连续三轮只读检查，计数达到 `3/3`。
- Git 交付已完成：2026-10-14 创建提交 `5378364f feat: add document processing lifecycle recovery`，
  已推送至 `origin/main`；最终核对 `main...origin/main = 0 0`，工作区干净。
- 实施落点与原始候选文件清单的差异已在 Slice 3 记录：生产包名采用
  `documentprocessing`，bridge/state mapper 保持为服务与 repository 内部行为，以减少新增抽象和兼容风险。
- 本 plan/progress 已按草稿生命周期归档；当前代码事实以代码、V61 迁移、自动化测试和双语长青文档为准。
- 后续批次入口：引用与流式安全、分块 Profile/Validator、检索上下文组装、route policy、Parser/资源
  句柄；见 [WEKNORA_REFERENCE_RESEARCH.md](../WEKNORA_REFERENCE_RESEARCH.md) §6。
