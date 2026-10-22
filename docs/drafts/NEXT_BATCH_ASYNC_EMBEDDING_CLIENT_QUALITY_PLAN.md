# 下一批改进规划：持久化长任务、多 Embedding Profile、Client 契约与质量漂移

> 状态：规划已通过连续 `3/3` 检查；当前处于实施与验收阶段，尚未完成本批验收或 Git 交付。
>
> 本文是本批实施的自包含入口。稳定事实以代码、Flyway 迁移、自动化测试和 `docs/` 正式文档为准；
> 本文冻结本批目标、边界、默认值、数据迁移、实施顺序和验收方法。规划检查完成 `3/3` 后才进入代码实施。
>
> 相关入口：[规划、实施与验收工作流](../delivery-workflow-zh-CN.md) · [测试指南](../testing-guide-zh-CN.md) ·
> [REST API](../rest-api-zh-CN.md) · [架构](../architecture-zh-CN.md) · [项目上下文](../project-context-zh-CN.md)

## 1. 基线与目标

### 1.1 基线

- 基线提交为 `771f0683`，当前工作分支为
  `feat/async-ops-embedding-profile-client-contracts`；开始本批时工作区干净，未修改生产代码。
- Flyway 当前最高版本为 `V66__conservative_expired_budget_settlement.sql`。
- 文档处理已有 `rag_document_processing_runs`、stage ledger、lease、heartbeat、取消、重试和
  dead-letter 基础；本批应复用这些 CAS/lease 模式，不新造一套互不相容的协调机制。
- Collection purge 当前是 preview-first、owner-scoped、单事务同步清理：小规模请求按已有路径完成，
  超过 `rag.collection-purge.max-*` 安全上限时 fail closed；不得截断删除计划。
- `rag_embedding_profiles`、`rag_embeddings.embedding_profile_id`、`rag_document_embedding_state` 和
  `embedding_1024 VECTOR(1024)` 已存在，但 `EmbeddingProfileProvider` 只有全局活动 Profile，
  `EmbeddingBatchService`/检索路径仍主要注入单一 `EmbeddingModel`。
- `EmbeddingPersistenceService.replace` 是当前生产向量写入路径，同时写旧 `embedding`、固定列和
  `embedding_profile_id`；旧 `embedding` 已是兼容双写列。`LegacyEmbeddingMigrationService` 仍读旧列，且
  `EmbeddingProfileBootstrap` 会因未归属 Profile 的历史行阻断启动；因此删除旧列必须同时移除这两条
  legacy 启动/认领路径，并自动排队重嵌入，而不能只加一条裸 `DROP COLUMN`。
- 默认 Profile 行由 `EmbeddingProfileRegistry.initialize()` 在 Flyway 之后按运行配置注册，迁移时不能假定该行
  已存在。V68 删除旧列还与旧二进制双写不兼容，必须冻结多实例升级时序，不能让新旧版本混跑。
- Collection 的 `embeddingModel`/`dimensions` 是兼容元数据，不是真实 Profile 路由；当前检索 SQL 已按
  `embedding_profile_id` 和固定列建立 Profile 级作用域/索引能力，但尚未按 Collection 绑定 Profile。
- `/api/v1/rag/**` 是主要外部服务边界；`/v1/models`、`/v1/chat/completions` 是受控、text-only、`n=1`
  的 OpenAI 兼容增量子集。新增字段/端点不得改变既有 envelope、旧状态码、旧 SSE 顺序和旧默认行为；
  既有大 Collection purge preview 的 `409` 也必须原样保留。
- Collection 已有 `version` 条件更新写栅栏（`CollectionIdentityResolver.beginActiveWrite(s)`），用于与 purge
  以 Collection-first CAS 顺序串行化；Chat 另有 `chat_commit_fence_version`。本批复用这两个既有 token，不新增
  重复 `write_fence_generation` 列；需补齐/验证本批新路径（尤其 Embedding/processing worker 最终提交）参与同一 CAS。
- 真实质量验收已有版本化 KNOWLEDGE QA、证据/引用断言、上游失败/超时/安全拒答分类和诊断脚本；本批把
  模型、Embedding Profile、配置、语料、策略、延迟、token、预算和失败分类纳入同一可比较基线。
- `docs/TODO*.md` 中 `EACH_COLLECTION` 的状态已于本批规划前收敛为已交付，并将复核日期更新为
  `2026-10-20`；当前实现语义以 REST API 和项目上下文为准。

### 1.2 本批目标

按以下顺序完成：

1. 为超过同步安全边界的 Collection purge 和跨文档 Profile 迁移增加持久化、可恢复、幂等的 Collection
   Operation；普通文档长任务继续使用现有持久化 Processing Run/Embedding Job，不重复建设任务账本；小规模旧请求保持同步兼容。
2. 将固定维度向量列真正接入多 Embedding Profile 路由：Collection 绑定 Profile，写入、查询、索引、
   readiness、重嵌入和清理使用同一 Profile 身份。
3. 将 Client API 契约治理与 OpenAI 兼容层生产化：能力发现、版本化 fixture、增量字段、错误/重试元数据、
   兼容性 diff 和 SDK JSON/SSE 黑盒回归形成长期门禁。
4. 把真实 Embedding/LLM 质量漂移基线作为本批和后续批次的共同验收能力，不让 WebUI、Mock 或单次冒烟代替
   外部 API 与真实模型证据。

### 1.3 非目标

- 不在本批实现完整 OpenAI API（tools、结构化输出、多候选、音频、图像等）；不静默接受不支持字段。
- 不按每个模型无界增加物理向量列；不支持任意运行时动态 SQL 列名。
- 不把 Profile 切换做成同步请求内的大规模远程重嵌入；不在 Flyway 事务内调用外部模型。
- 不改变既有 `/api/v1/rag/**` 的必填字段、成功 envelope、错误状态码、SSE 事件顺序和默认检索语义。
- 不把安全拒答、上游超时或上游错误改写成“质量通过”；它们作为生产场景单独验证业务层响应、恢复提示、
  trace 和 usage/budget 结果。
- 不做 WebUI-first 交付；如果共享 DTO 或静态契约受影响，WebUI 只做必要的兼容构建/Mock 验证。

## 2. 冻结的设计决策

### 2.1 固定维度向量列与 Profile 身份

采用“有限固定维度列 + `embedding_profile_id` 隔离模型空间”的设计：

- 首批保留现有 `embedding_1024 VECTOR(1024)`，新增 `embedding_384 VECTOR(384)`、
  `embedding_768 VECTOR(768)`、`embedding_1536 VECTOR(1536)`；只支持实际配置和 PostgreSQL/pgvector
  索引已验证的维度。是否启用某列由迁移和白名单共同决定，不能只改 `dimensions` 绕过校验。
- 同维度的不同模型共用同一物理列，但必须使用不同 `embedding_profile_id`；不同模型的向量空间绝不混检。
- `EmbeddingVectorColumns` 继续只从受控白名单返回列名；不拼接用户输入，不动态创建任意维度列。
- 每个启用 Profile 建立独立部分 HNSW 索引：固定列 + 对应 `embedding_profile_id` + 非空谓词。PostgreSQL 索引谓词
  不支持 bind parameter；生成 DDL 时只允许把从数据库读取并验证为正数的 numeric Profile ID 作为数字字面量，其余标识符只能来自
  `EmbeddingVectorColumns` 白名单，绝不拼接外部字符串。索引创建继续使用并发 DDL、幂等检查和失败重试，不使用悲观锁。
- 全局最多允许 8 个 enabled Profile（与单次 fan-out 硬上限一致）；超限在启动/配置变更时 fail closed，避免
  无界增加 HNSW 索引、启动时 DDL 和检索 fan-out。配置项显式含 `enabled`，Profile 配置是期望运行态的来源，数据库
  `rag_embedding_profiles.enabled` 是其持久投影；disabled Profile 不建立检索路由/索引，但其既有向量和 Profile 行保留。
  不允许禁用仍绑定活动 Collection 的 Profile；活动定义为 `enabled=true AND deleted=false AND purged_at IS NULL`。
  Profile 只被已停用/软删除/已退役 Collection 引用时，可禁用或移除其运行时 model/API Key 配置，Collection 与向量行仍保留；
  重新启用/恢复该 Collection 前，API 必须验证其 Profile 已重新精确配置、启用且 readiness 完成，否则以稳定 `409`
  拒绝恢复，不得自动切换到默认 Profile。若 Profile 仍被任何活动 Collection 引用，必须先完成已验收的 Profile switch。
- 新增受管 Profile 配置列表，规范形态为 `rag.embedding.profiles.<profileKey>`；每项提供 provider、model、revision、
  dimensions、归一化、base URL、模型选项和仅由环境变量/外部配置占位符提供的 API Key。运行时建立
  `profileKey -> EmbeddingModel` 精确映射，同一 provider 的多个模型必须可以并存。首批传输协议范围固定为当前
  `EmbeddingModelConfig` 已使用的 OpenAI-compatible `/v1/embeddings`；Profile 的 `provider` 是稳定身份元数据，
  不代表本批要新增多个供应商 SDK/协议适配器。每项配置由专用 factory 独立创建 `OpenAiApi` 与
  `OpenAiEmbeddingModel`（独立 base URL、model、API Key、请求选项、超时和现有重试策略），不能复用同 provider
  下第一个模型实例；旧 `rag.embedding.*` 自动生成默认 Profile，未显式声明的其他 Profile 不会从数据库猜配置。
  真实请求返回向量后验证长度与声明维度；配置缺失、provider 返回维度不符、请求协议不支持时该 Profile fail closed，
  不回落其他模型。数据库只保存非敏感身份；capability 不暴露密钥、base URL、内部候选链或可推导凭据的信息。
  Profile 路由不再以 provider、模型名子串或“第一个可用模型”猜测 Profile。缺少精确模型实例、
  响应向量维度不符或身份不匹配时拒绝该 Profile，不能静默回落到另一个模型/向量空间。
- Registry 必须启动时加载和校验全部声明的 Profile，而非只注册默认 Profile：所有活动 Collection 引用的 Profile 必须有
  精确匹配的 `enabled=true` 配置和可创建的 model factory；缺配置/重复 key/身份变化/维度越界/超过 8 个 enabled 时 fail closed。
  对没有任何活动 Collection 引用、且配置缺失或显式 disabled 的持久 Profile，bootstrap 以幂等条件更新把 DB `enabled` 投影
  置为 false，事务外并发移除该 Profile 专属 HNSW 索引；保留 Profile 行和向量数据。配置缺失的 inactive-only Profile 不需要
  model/API Key，也不参与检索/索引/readiness，不能阻断应用启动。再次激活引用该 Profile 的 Collection 前，服务先要求
  Profile 重新精确配置/启用、model factory 可创建、索引 ready，否则以稳定 `409` 拒绝激活；不得静默改绑默认 Profile。
  所有 enabled Profile 的列和索引均 ready 后才宣告多 Profile 路由 readiness；有活动 Collection 引用时不得禁用其 Profile。
- Collection 新增 `active_embedding_profile_id`（外键 Profile）。V67 先允许历史行暂为 NULL；Flyway 完成后，
  Profile bootstrap 注册并校验当前默认 Profile，再幂等绑定所有历史 Collection（包括软删除/已退役行），并验证
  不存在未绑定行。该校验纳入 readiness，在绑定完成前数据面不接流量；新 Collection 必须在创建事务内写入 Profile ID。
  历史 Collection 自动绑定当前默认 Profile；
  若现有向量确属该 Profile 且固定列/状态完整则无需重嵌入，否则按下一节的自动补建账本重嵌入。
  旧 `embeddingModel`/`dimensions` 保留为兼容输入/输出投影，但不再作为路由真相。
- 创建 Collection 可增加可选 `embeddingProfileKey`；省略时绑定默认 Profile。未知、禁用、维度不支持或模型
  Bean 不可用时 fail closed，并返回现有错误体系可兼容的 400/409。
- Collection 创建/响应/列表/clone/import 只新增可选 profile key 和响应标识；旧 `embeddingModel`/`dimensions` 保持
  原样序列化、Java DTO 原签名构造器兼容，并且不能因旧字段值而改变历史路由。
- Profile 路由只改变服务内部选择 query/document EmbeddingModel 的方式；所有既有入口原有的同步/异步执行时机、
  HTTP 状态码、成功/失败 envelope、`embeddingStatus` 和 retry 语义均保持不变。批量重嵌入通过新增 Operation/既有
  Processing Run 暴露进度，不把原同步 Client 请求悄然改成后台接受式响应。
- Clone 未提供新可选字段时继承源 Collection 的 active Profile；显式目标 Profile 则验证后为克隆文档排队生成目标
  向量。Import 未提供字段时绑定当前默认 Profile；旧导出里的 `embeddingModel`/`dimensions` 仍按兼容投影读取，
  不得将其当作 Profile 身份。Profile 不可用时明确失败，不静默换模型。
- Collection profile 切换不是直接改字段。Operation 启动后 Collection 仍以旧 Profile 提供查询；迁移期间新增/更新
  文档同时为旧活动 Profile 与目标 Profile 排队，确保新 mutation 不被切换漏掉；按文档当前 revision/content hash
  验证目标 Profile 完整后，对 Collection profile ID/version 做 CAS 原子切换。CAS 失败则继续补缺或明确冲突，不能切换
  半成品。取消只允许 CAS 切换前；旧 Profile 的向量和索引至少保留至切换成功、rollback window 与 retention 到期，
  后续按 Collection 文档范围有界清理，不能误删仍被其他 Collection 使用的数据。
- 文件导入、PDF、普通文档 create/update、external sync/upsert/relocation、clone/import 和显式 re-embed 均按目标
  Collection Profile 分发 jobs。跨 Collection 移动先在目标 Profile 生成并验证向量，再 CAS 移动归属；失败时文档仍留
  在源 Collection，避免丢失可检索性。未归属文档使用默认 Profile。
- 多 Collection 检索按 Profile 分组，每组只生成一次 query embedding，使用对应固定列/索引；Profile 内各候选列表保留
  hybrid rank。不同 Profile 的 cosine score 不可直接比较，跨 Profile 统一使用固定 Reciprocal Rank Fusion（RRF，`k=60`）
  去重融合，再交给既有 reranker/最终 top-k；禁止跨 Profile 按原始 cosine distance 混排。`CALLER_VISIBLE`/`ANY_COLLECTION`
  可覆盖多个 Profile，按唯一 Profile 数量限制 fan-out，默认上限 4、
  可配置硬上限 8；超过时固定返回稳定 machine-readable `422 EMBEDDING_PROFILE_FANOUT_LIMIT_EXCEEDED`，不能静默漏召回。
  请求已有 Collection/document scope
  时按其归属分组；未归属文档只路由默认 Profile。
  必须同时保持既有 `collectionScopeMode` 与 `collectionCoverageMode` 语义：省略或 `GLOBAL` 继续走旧全局 top-k，显式
  `EACH_COLLECTION + SELECTED_COLLECTIONS` 仍按每个授权 Collection 给出贡献机会；Profile 分组/RRF 不得改变
  `X-RAG-Collection-Coverage`、Chat/OpenAI additive metadata、权限过滤或结果上限。
  对需要多个 Profile 的同一请求，任一必需 Profile 的 query embedding 上游失败都不得静默丢弃该分支、改用另一 Profile
  或伪造 lexical-only 成功；整个 retrieval 返回当前既有 Embedding 错误状态/envelope，附上脱敏 `failureDetails` /
  trace；仅在新增的 additive Retry-After policy 能确定安全等待时附该 header，避免部分召回被误认为完整结果。此行为只规定新增的多 Profile 请求；既有单 Profile 错误
  HTTP 状态不变，符合错误映射的新增失败分类/响应头只能是 additive。

### 2.2 旧 `embedding` 列自动迁移与删除

用户确认旧 `embedding` 已是双写兼容列，要求本批用数据迁移移除，不留给运维手工操作。冻结方案：

1. `V67` 先建立 nullable Collection Profile 绑定、固定维度列和可恢复迁移账本；`V68` 在同一批次数据库升级中
   删除旧 `embedding` 列。两条迁移只做 schema/SQL 元数据变化，不调用 Embedding provider；旧 Collection 绑定由
   Flyway 后的 Profile bootstrap 完成。
2. 已有 `embedding_profile_id` 且对应固定维度列非空的向量保留原样；它们已经是当前检索真相，删除冗余旧列
   不影响检索。`embedding_profile_id IS NULL` 或活动 Profile 固定列缺失的旧向量属于不可证明模型身份的派生
   缓存：不按维度猜 Profile、不把旧向量认领为新模型，而是把文档登记到持久化补建账本，迁移后由默认 Profile
   的文档处理 Run 自动重嵌入。
3. 在 Flyway 执行期间不得调用外部模型或扫描/物化全部 document ID。`V67` 建立安装级 durable backfill control
   （迁移 key、状态、keyset cursor、lease/fencing、候选/已排队/成功/失败计数）；`V68` 将该控制行置为待处理后删除旧列。
   源文档正文继续保留。`rag_embeddings` 行及其 document/chunk key 在删除向量列后保留，作为 legacy-only 候选索引；
   应用启动后 worker 以 keyset cursor 仅扫描存在 `embedding_profile_id IS NULL` legacy-only 行，或存在明确 Profile
   embedding state/job 但当前 Collection Profile 对应向量/状态缺失的文档。未曾请求 embedding 且无上述历史证据的普通
   文档不自动发起模型调用；disabled/deleted 文档、已退休 Collection 不生成新 Job，记录为跳过/清理候选。确认目标
   Collection Profile、文档 enabled 状态及当前 revision/content hash 后，只将需要重嵌入的文档幂等提交给现有
   `EmbeddingJobService`，并由 `rag_document_processing_runs` 记录逐文档阶段、进度、
   lease/recovery、重试、取消、dead-letter 与最终状态；job 是执行队列，processing run 是逐文档处理事实/读模型，
   本批不为这些维度另造 task/job 表。作业创建与 backfill cursor 推进使用同一事务/CAS，失败可重扫，不会漏任务。
   安装级 backfill control 只编排扫描和汇总：只有扫描结束且所有目标 Profile processing run 成功才标记 `COMPLETED`；
   有不可自动修复失败时进入 `DEGRADED`，不伪装成有 owner 的客户端 Operation，也不阻断其他 Collection 启动。
  provider 暂时失败沿用既有 job 有界 retry；耗尽后由 backfill coordinator 对可恢复的 transient/upstream failure
  以 bounded cooldown 使用现有 Run retry API/创建 superseding Run 的语义重新入队（每个文档最多 3 个恢复周期，间隔
  5 分钟、30 分钟、6 小时）。不直接改写历史 Run 状态、不改变既有 Client API 显式 retry 语义。永久数据/配置错误保留
  源文档、失败分类和 cursor，报告 `DEGRADED`；修复配置后 coordinator 自动续跑，不要求手工 SQL、重索引或 `adopt-legacy`。
4. `EmbeddingProfileBootstrap` 顺序为：注册/验证 Profile 配置与运行时 model mapping → 幂等绑定历史 Collection → 验证固定列和索引 →
   校验 Collection 绑定完整 → 启动 durable backfill worker；不能再因未归属 legacy 行直接退出。完成迁移后移除
   `LegacyEmbeddingMigrationService`、`RagEmbedding.embedding` 映射、旧列双写与所有旧列查询。V68 与旧二进制写入
   不兼容：发布采用自动化的全实例 drain/stop → 新版本迁移并启动，不支持旧/新二进制混跑；这是本批唯一需要
   编排层配合的发布边界，不要求运维执行手工 SQL、数据认领或重索引。为保证这一边界实际成立，本批修改 Helm
   Deployment 的升级策略为 `Recreate`，确保 V68 Flyway 启动前先终止全部旧 Pod；单实例 Docker Compose 保持先停止旧
   容器再创建新容器的默认路径，并增加发布验证。采用该破坏性 schema migration 的标准部署会有一次短暂服务中断，
   不承诺零停机或旧/新二进制混跑；若 Helm 继续 `RollingUpdate`，release gate 必须阻止该 chart/应用版本组合发布。
   回退依赖已验证备份恢复或发布前滚修复，
   不宣称旧二进制兼容已删除列。`V67` 与 `V68` 均在各自 Flyway 事务内设置 `SET LOCAL lock_timeout = '5s'`；
   遇到长事务持锁时当前迁移整体失败、Flyway 不记录该版本成功，业务连接不被无限期阻塞，释放冲突后可安全重试。
5. 迁移必须验证：全量双写行保留且可检索；旧列单独有向量的行被登记并自动重新生成；坏数据不泄漏、不误认领；
   升级中断后重复启动继续处理；未完成重嵌入只影响相应文档 readiness，不阻断其他 Profile/Collection 服务。

### 2.3 持久化长任务与异步资源治理

- 分层复用而不重复造逐文档账本：`rag_document_processing_runs` 是逐文档阶段、进度、取消、retry/dead-letter、
  恢复和最终状态的权威记录；`rag_embedding_jobs` 是执行队列/lease。现有 `DocumentProcessingRunController` 与
  `EmbeddingJobController` 是单文档客户端观察/动作边界，本批不得改变其旧状态、重试语义、响应 envelope 或授权。
  新增 `rag_long_running_operations` 仅编排跨文档 Collection purge 与 Profile re-embedding/switch；它不复制子 Run
  的 stage、lease、error 或 retry ledger。V67 同时新增 `rag_long_running_operation_run_refs`，作为父 Operation 与
  Processing Run 的持久关系表，至少包含 `operation_id`、`run_id`、`created_by_operation`、`created_at`，主键为
  `(operation_id, run_id)`，两端外键均级联清理，并为 `run_id` 建反向索引。它只保存关系及“该 Operation 是否实际创建此 Run”
  的所有权事实，不复制子 Run 的状态、进度或错误。仅 Profile re-embedding Operation 建立此关系；purge 仍通过 Collection
  scope 查询并排空现有活动任务，不把被动观察到的 Run 纳入其进度或取消所有权。Operation 保存目标范围/Profile、扫描或 purge
  cursor、协调 lease、幂等摘要、聚合进度和最终 receipt；Profile 操作的进度/失败由关联 child Processing Run 聚合得到。
  不改 V61 的 Run 状态、`origin` 枚举或既有客户端 DTO，因此不需要把 Profile switch 塞进既有来源字段。
- 创建/复用 child 时按 Embedding Job → Processing Run → Operation–Run reference 顺序写入；关系与该 Operation 的文档扫描 cursor
  和上述 child 写入必须在同一个 PostgreSQL 事务中完成；
  重放时按主键幂等，不得出现 cursor 已越过文档但没有可恢复 child 引用的窗口。Run 是否由本 Operation 创建必须由本事务
  实际插入结果确定，不能从 `origin`、时间戳或当前状态推断。每次既有 Processing Run retry 产生 superseding Run 时，
  在同一事务增加新关系行并保留旧 attempt 关系；不覆盖历史 Run 或关系。
- 只有与 Profile 操作目标完全匹配的 Run 才能关联为该文档的有效工作：`document_id`、`embedding_profile_id`、
  `request_generation`、`document_version`、`content_hash`、`document_kind` 与 `chunker_version` 必须匹配扫描时的目标输入。
  已有活动 Run 若输入快照不匹配，不得错误聚合或取消；等待其按既有语义结束后重读文档并为当前快照排队。完成判断沿
  `supersedes_run_id` 链选择该目标快照的最新 attempt，并核对 `rag_document_embedding_state` 中相同 revision/profile 的就绪
  状态。最终切换 Collection Profile 前再验证所有当前未删除文档的目标 Profile 派生状态与最新 revision 一致；扫描期间有
  文档变化则补处理新快照，不能用旧 Run 的成功状态通过切换。
- Profile re-embedding Operation 的文档发现和 job/run 创建必须幂等；Operation lease/cursor 负责集合级调度，文档级
  claim、运行、取消、重试、死信和恢复继续由既有 job/run 完成。Operation `SUCCEEDED` 仅当目标 scope 所有当前 revision
  的 child run 成功且 CAS profile switch 成功；存在永久失败为 `DEGRADED`，不得将父操作标成功或伪造完整率。
  父级 retry 只能经现有 Processing Run retry 语义为失败子项创建/恢复新 attempt，不得直接覆盖终态历史或越过文档授权。
- Parent Operation 状态是批次编排状态而非逐文档状态：`QUEUED -> RUNNING -> SUCCEEDED|RETRY_WAIT|CANCELLED|STALE|DEGRADED`；
  `RETRY_WAIT -> QUEUED` 仅在 `next_retry_at` 到达或有权限的显式 retry。`DEGRADED` 表示不可自动修复的 child Run/目标完整性失败，
  仍占用 Collection slot，但释放全局执行队列槽；授权 owner 可在修复配置后显式 retry 原 Operation，或仅在 Profile 尚未切换且无破坏性
  工作时显式 cancel。运行中的 cancel 先 CAS 写入 `cancel_requested_at`，receipt 追加 `cancelRequested=true`，父状态保持 `RUNNING`；
  coordinator 停止发现/派发新 child；仅向本 Operation 创建、且未被其他活动父 Operation 关联的 child Run/job 发出既有
  协作式 cancel。被复用/观察的 Run，或被其他活动父 Operation 共享的 Run，不得由本 Operation 取消。父 Operation 必须等到
  本 Operation 所有自有且未共享的 child 均终态、没有 worker 还能提交向量/派生写入后，才转为 `CANCELLED` 并释放 Collection
  slot；`QUEUED` 且无 child 的操作可直接转终态。共享/观察到的 Run 可继续完成并留下目标 Profile 的安全派生缓存，但已取消的
  Profile Operation 永远不得据此 CAS 切换 Collection Profile。关联关系在 Operation receipt 保留期内保留，终态清理时按外键
  一并回收；关系表不延长文档正文或 Run 的保留期。
  `DEAD_LETTERED` 不复刻为父/子新状态，逐文档 dead-letter 继续由 Processing Run API 表达。async purge 在
  `purge_fence_completed_at` 尚为空时可取消，包括 worker 尚未 fence 的 `QUEUED/RUNNING` 窗口；fence 提交后不可取消，只能继续恢复。
  Profile switch 在最终 Collection CAS 前可请求取消，但必须按上述规则等 child 写入安全
  收敛，不能以“已发出 cancel”冒充终态或提前释放 slot。
- 新增 `rag_long_running_operations` 只承载大 Collection purge 与 Profile re-embedding/switch 两类跨文档 Operation。
- 小规模 purge 请求保持原 preview/apply JSON、HTTP `200`、结果 DTO、权限、冲突和重放语义。
- 当前 `CollectionPurgeService.preview()` 在创建 preview 前就以同步上限拒绝；此旧 endpoint 的成功、`409`、权限、
  body 和 envelope 全部保持不变。新增 `POST /api/v1/rag/collections/by-key/purge/async/preview` 与
  `POST /api/v1/rag/collections/by-key/purge/async` 两个端点，默认随服务可用，并由 capability discovery 公布，
  不增加运维开关。异步 preview 保持既有 query `collectionKey` 和确认字段语义，复用 `rag_collection_purge_preview`
  的 body-free owner/token/fingerprint/计数记录，V67 增加默认 `SYNC` 的 `execution_mode`，新 preview 标记为
  `ASYNC`；不保存全量 ID，也不另建重复 preview 表。超容量但安全的计划使用流式 fingerprint/count，不在 JVM 聚合
  全量 ID。新 apply 沿用现有 previewId、confirmationToken、fingerprint、expectedCollectionVersion 与
  expectedChatCommitFenceVersion，请求头新增必需 `Idempotency-Key`，返回 `202 Accepted`、`Location`、`Retry-After`
  和 operation receipt。旧同步 apply 若收到新端点生成的 `ASYNC` preview，只返回新增 machine-readable `409`
  `COLLECTION_PURGE_ASYNC_PREVIEW_REQUIRES_ASYNC_APPLY`；既有 `SYNC` preview 及其成功、`409`、权限、body 和 envelope
  均保持不变。旧同步 endpoint 可在 `409` 的可选
  problem detail 中提示新能力，但状态码、既有错误字段和错误路径不改。不得对缺引用、活跃同步 run、活动 Chat
  session、ACL 失败、计划 fingerprint 改变等硬安全阻断自动降级/绕过。
- Async preview 本身仍是同步 HTTP 响应，但其扫描有硬边界：默认最多 1,000,000 个文档、10,000,000 条派生/引用行、
  总扫描 wall-clock 60 秒（硬上限 120 秒）、每条 SQL statement timeout 10 秒（硬上限 30 秒）；对应配置可调低但不能
  超过硬上限。超过文档/派生行计数限制稳定返回新增 `422`
  `COLLECTION_PURGE_ASYNC_PREVIEW_LIMIT_EXCEEDED`，不签发 token、不留可 apply 的部分 preview。V67 增加带 lease/token
  的 preview admission 槽：每 owner 同时最多 1 个扫描、全局最多 2 个；在开始扫描前通过条件 DML/CAS 预留，完成/失败
  立即释放，进程异常由 lease 到期回收。Preview lease 默认 180 秒、硬上限 240 秒；每页和最终签发前都以条件 DML
  校验/续租当前 fencing token，statement timeout 不得大于 lease 剩余时间，失去 token 的请求停止扫描且不得签发 preview。
  启动校验要求 preview lease 严格大于总扫描 deadline、最大单 statement timeout 加 30 秒提交/回收余量；默认值及其硬上限
  必须满足该关系，配置只能调低各工作负载 deadline/statement timeout，不能调短 lease 破坏安全余量。超过总
  wall-clock deadline 返回 `503 COLLECTION_PURGE_PREVIEW_TIMEOUT` + `Retry-After`；计数和超时均整次失败，不能签发部分
  preview。满载返回 `429` + `Retry-After`。该 preview 路由虽属于异步清理流程，HTTP 调用仍同步等待；API 文档和官方 Python/Node
  示例必须说明调用方 read/request timeout 应大于实际配置的 preview deadline，并额外留 15 秒响应/网络余量。样例对该 endpoint
  单独设为 150 秒，不提高其他请求的全局 timeout；项目 Helm default/production values 在启用 Ingress 时均使用
  `proxy-read-timeout=300s`，覆盖 preview 硬上限及余量。外部自管 Ingress 必须配置大于其 preview deadline 加 15 秒的读取超时；
  release gate 只验证仓库提供的 Helm values，不声称能验证未输入的外部代理设置。
  验收同时覆盖 30 秒默认 SDK 对小预览成功，以及针对长 deadline 显式配置大于 deadline 的 timeout 后收到结构化
  `503`/`Retry-After`，不让客户端本地 timeout 冒充服务端业务错误。这只限制新增异步 preview，不改变旧同步 preview。
- 新增持久化 Operation 表（`V67`）：owner、resource/collection、operation kind、request
  fingerprint、status、attempt、lease owner/token/expiry、heartbeat、cancel request、purge fence completion time、next retry、
  terminal reason、progress、result/error payload、created/updated/finished/expires。唯一键保证 owner + idempotency
  key hash + operation kind + resource 的精确重放。V67 另建 `rag_collection_operation_slot`，以 `collection_id`
  为主键，原子保留一个跨 Operation kind 的未完成资源槽，覆盖 `QUEUED`、`RUNNING`、`RETRY_WAIT` 和
  `DEGRADED`，防止不同幂等键或双实例并发启动互斥任务。只有 `SUCCEEDED`、`CANCELLED` 或安全边界前的
  `STALE` 释放资源槽；`DEGRADED` 仍阻止同 Collection 的新 purge/Profile switch，只能恢复原 Operation，
  或在尚未进入不可逆阶段时按授权显式取消。原始 secret/body 不落库。
- Collection 互斥必须同时覆盖仍保持同步语义的旧 purge apply：异步 purge/Profile switch 在接受 Operation 的事务内
  插入并持有上述资源槽；资源槽以 `collection_id` 为主键，含 `holder_kind` 与可空 `operation_id`（非空时外键指向 Operation，
  并唯一），数据库 `CHECK` 保证 `SYNC_PURGE` 当且仅当 `operation_id IS NULL`，异步 purge/Profile switch 必须关联有效 Operation。
  同步 purge 只在原 apply 事务内临时
  插入同一槽，完整成功与退休同事务提交前删除该临时槽。槽被占用时
  返回稳定、增量的 `409 COLLECTION_OPERATION_IN_PROGRESS`；既有同步 preview/apply 的请求、成功 envelope 和一般冲突
  状态保持不变。旧 preview 不预占槽，以免过期 preview 长期阻塞。不能只依赖 Operation 表自身的 partial unique index，
  因同步旧路径不在该表内。
- 资源上限冻结为：每个 Collection 同时最多 1 个 destructive/Profile-switch Operation；每个 owner 最多 4 个未收敛
  Operation（含 `DEGRADED`）；
  全局最多 500 个 QUEUED/RUNNING/RETRY_WAIT 父 Operation，满载时新建异步任务返回 `429` + `Retry-After`；两个 purge worker 并发，
  Profile coordinator 每个 Collection 限制 1 个切换 Operation，文档级模型执行完全复用既有 Embedding job worker
  concurrency。可通过新配置提高/降低但不能
  超过硬上限（活动 2,000、worker 8）。Purge 每个事务默认最多处理 500 个 document ID，且所有显式 FK 依赖行合计
  默认最多删除 5,000 行/事务、硬上限 10,000 行；行预算先到即提交，不因单个高扇出文档而越界。行预算可调低，不可提高硬上限；
  每个删除事务的 `statement_timeout` 默认 30 秒、硬上限 60 秒，超时回滚当前批并以同一 cursor 有界重试。所有限制含 startup
  validation 和 PostgreSQL 并发 admission test。V67 建立全局 queue-capacity 单行及按 owner 的 capacity 行；创建 Operation
  时通过同事务条件 DML/CAS `UPDATE ... WHERE active_count < limit RETURNING` 原子预留。owner reservation 覆盖
  `QUEUED/RUNNING/RETRY_WAIT/DEGRADED`，global queue reservation 覆盖 `QUEUED/RUNNING/RETRY_WAIT`；终态与
  `DEGRADED` 转换、reservation 释放/迁移和 Operation 状态变更同事务。显式恢复 `DEGRADED` 前重新原子取得 global queue
  槽，lease reclaim 不重复计数。capacity 行不存在时以唯一约束下幂等插入后再条件更新，不能用易竞态的 `SELECT COUNT`
  后再插入。并发申请只能在硬上限内成功，超额请求稳定返回 `429` + `Retry-After`。
- 为避免条件 DML 隐式行锁逆序等待，新增 Operation 控制面事务按固定资源顺序执行：Operation 行（新建时先插入；已有操作
  先做条件状态/lease CAS）→ owner capacity 行 → global capacity 行 → `rag_collection_operation_slot` → 对应 purge preview 行
  （如本事务需更新）→ Collection version/chat fence → Embedding Job → Processing Run →
  Operation–Run reference/cursor → Document/派生数据；owner capacity 行总在 global 行之前，各类批量 child 行按稳定主键升序。
  未涉及的资源可跳过，但不得在同一事务中反向获取更早的资源。Async apply 先只读校验 preview 快照，再同事务依次插入
  Operation、预留 owner/global capacity、取得 Collection slot、条件更新 preview 为 `APPLYING`；任一冲突使整笔事务回滚。
  终态、取消、retry、
  `DEGRADED` 转换按 Operation、capacity（owner 后 global）、slot 顺序更新；Profile 最终切换持有 slot 后才执行 Collection CAS。
  Purge 首次 fence 按 Operation CAS 后 Collection CAS，不更新 capacity/slot；若 Collection version/chat CAS 失败，fence 事务必须
  整笔回滚且不删除/退休任何数据，随后用独立的 Operation→owner/global capacity→slot 事务以条件 CAS 终结 `STALE` 并释放 reservation。
  在该短暂窗口中 Operation 保持可恢复的 `RUNNING`/lease 状态，不得签发 destructive batch；若取消先赢，则由取消路径按相同顺序
  终结，不覆盖其状态。同步旧 purge 无 Operation/capacity 行，先取得共享 slot，再条件更新 purge preview，之后执行 Collection
  fence/依赖删除；此为相容后缀顺序。普通写路径仍按 Collection-first CAS 后写内容，不触碰 Operation/capacity/slot。
  Async preview admission 自身不创建 Operation，owner preview 槽先于 global preview 槽条件更新；释放/reclaim 也使用相同 owner→global 次序。
  新增 PostgreSQL 双实例竞争验收覆盖 Operation、容量行、共享 slot 与 Collection fence 同时竞争，必须无死锁、无 reservation 泄漏，
  且请求在有界时间内收敛到唯一状态。
- Operation 完整结果在不可逆终态后默认保留 7 天，随后脱敏 payload；最小状态/idempotency tombstone 保留至
  `max(created_at + 30 days, finished_at + 7 days)`。因此长于 23 天的 Operation 仍至少保有 7 天结果窗口；tombstone 窗口内
  相同 key 精确返回已过期 `410`，不重新执行也不回传 payload；仅在上述最晚期限后清理 tombstone，key 才可再次使用。
  `DEGRADED` 是可恢复、非终态，继续保留 owner/idempotency/resource identity、Collection slot 和脱敏诊断，不由
  terminal cleaner 清理；恢复或有权取消并终结后才开始 retention。终态 cleaner 不得删除仍持有 slot、capacity reservation、
  lease、未过期幂等 tombstone 或审计关联的记录。
- 状态机冻结为 `QUEUED -> RUNNING -> SUCCEEDED|RETRY_WAIT|CANCELLED|STALE|DEGRADED`；`RETRY_WAIT -> QUEUED`
  仅在到达 `next_retry_at` 或 owner 显式 retry 时发生；协调器重试耗尽进入可恢复的 `DEGRADED`，不得新增 `FAILED`/
  `DEAD_LETTERED` 子任务状态。`SUCCEEDED`、`CANCELLED`、`STALE` 是不可逆终态；`DEGRADED` 保留 Collection slot，
  经授权修复条件后显式 retry 可恢复。lease 过期只允许新 fencing token 续接同一 Operation，不创建第二份破坏性任务。
- Worker 使用条件更新/CAS、短 lease、heartbeat、fencing token、固定并发和有界重试；Operation lease 默认 120 秒、硬上限
  180 秒，heartbeat 默认 20 秒且不得超过 lease 的三分之一。Purge 单批事务 wall-clock deadline 默认 60 秒、硬上限
  120 秒；Operation lease 启动校验必须严格大于事务 deadline 加 15 秒提交余量，且所有配置组合必须满足各自硬上限。
  PostgreSQL `statement_timeout` 是逐语句而非逐事务限制，worker 必须以单调时钟维护批次 deadline；每条 SQL 开始前将
  effective statement timeout 设为 `min(configured statement_timeout, remaining batch budget - 15s commit margin)`，余量不足时
  不得开始下一条语句。事务总预算包含所有依赖删除、计数/cursor CAS 与提交余量；任一语句超时或批次 wall-clock deadline
  到达时整批回滚、不得推进 cursor，之后由新 fencing token 从同一 cursor 有界重试。不得用只限制单条 SQL 的 timeout
  冒充事务时长上限。不得使用 `FOR UPDATE`、
  `SKIP LOCKED`、advisory lock。每个 purge destructive transaction 都必须在同一事务内以条件 DML 校验/续租当前
  fencing token 后才删除，并原子提交删除计数与 phase/key cursor；旧 token 更新 0 行即整批回滚。批次 statement timeout
  与总 wall-clock deadline 都必须在租约和提交余量内完成，避免活跃事务过期后被另一 worker 重领。重启后由
  housekeeping 将过期 RUNNING 以新 fencing token 重新入队；协调器/基础设施重试耗尽时父 Operation 转入可恢复的
  `DEGRADED` 并保留 Collection slot 与恢复 cursor，不新增父级 dead-letter 状态。只有逐文档 child Processing Run
  使用既有 dead-letter 状态和冷却恢复语义。
- 异步 preview 在 `READ COMMITTED` 下以 JDBC keyset 流式遍历受影响键，逐条计算 fingerprint/count，不物化全量 ID，
  不使用长时间 `REPEATABLE READ` 快照；开始和结束各读取一次 Collection version/chat fence，若任一改变则拒绝预览并
  要求重试。所有会改变 purge target/count/引用/活动任务的写入都必须与已有 version/chat fence 同事务提交；worker
  首次执行 fence 时再以预览中的相同 token CAS，故扫描期间、apply 后到 worker fence 前并发变更不会被误删或漏计。
  Async apply 先只读验证仍有效的 preview，在同一短事务中依次插入 Operation、预留 owner/global capacity、取得 Collection slot，
  最后用条件 DML 将 preview 置为 `APPLYING`；但不提前
  关闭 Collection 写入；Operation 返回 `202` 后 Collection 仍可用，直到 worker 首次执行 fence。Worker 首个 fence 事务先用
  `status='RUNNING' AND cancel_requested_at IS NULL AND purge_fence_completed_at IS NULL` 条件更新 Operation，随后在同一事务
  以预览捕获的 Collection version/chat fence CAS 关闭新写入并设置 `deleted=true, enabled=false`，提交时记录
  `purge_fence_completed_at`。若 Collection version/chat fence CAS 失败，首次 fence 事务整体回滚；之后 worker 以独立的
  Operation→owner/global capacity→slot CAS 事务转为 `STALE` 并释放 reservation，不做任何删除，客户端需重新预览/提交；不得以旧
  preview 覆盖并发写入。两事务之间不派发 destructive batch；取消可以先赢并依其 CAS 结果转为 `CANCELLED`，STALE 终结 CAS
  失败时不得覆盖取消状态。取消请求与成功 fence 通过 Operation 条件更新竞争：取消先提交则 worker
  不得 fence/删除并转为 `CANCELLED`；fence 先提交则之后取消稳定返回 `409` 并继续恢复删除。同步 purge
  apply 在其原事务内先取得 shared Collection operation slot，再按原逻辑 fence、校验和删除；Profile switch 和 async purge
  在接受请求时已持有该槽。由此保持旧 preview 非排他、旧 apply 原子语义，同时避免新旧互斥任务交叉。
  所有同步文档/外部导入/同步/relocation/文件/PDF/反馈/修复/处理 worker/Chat 提交写路径必须在业务事务内使用
  `CollectionIdentityResolver.beginActiveWrite(s)` 的 `Collection.version` CAS；Chat 同时继续使用既有
  `chat_commit_fence_version`。异步 purge 以预览捕获的 version/chat fence 做条件更新，把 Collection 设为
  `deleted=true, enabled=false` 并递增 version。并发业务事务在同一行 CAS 上串行化：先提交的写入使 preview version
  失效，purge 必须重新预览；purge 先提交后旧写入 CAS 影响 0 行并回滚。不得以仅在事务开头读取 `deleted` 代替
  提交边界 CAS。Embedding/processing worker 在持久化最终向量/派生状态的同一事务内也必须获取 active write token，
  因而已开始的远程调用若 purge 先完成 fence，不会在删除后写回孤儿数据；失败进入现有 Processing Run/Job
  retry/dead-letter。既有 Processing Run GET/cancel/retry 端点和 JSON envelope 保持兼容；profile 路由改造后的 retry
  必须按目标 Run 中的 `embedding_profile_id` 重新执行，而不是错误地按当前全局默认 Profile 处理。
  若发现任何 purge 目标写入路径不受 version/chat fence 保护，异步 purge 不得开放 destructive worker。在途工作未
  排空或 CAS 失败时不得开始删除。该 CAS 与业务写在同一事务内，关闭
  check-then-write 竞态；若不能证明覆盖全部写入路径，异步 purge 不得开放 destructive worker，旧同步端点仍保持
  原行为。fence 成功后按 document ID keyset 分页，并按既有 FK 删除顺序执行；Operation cursor 必须持久化当前删除 phase、
  document key 和 phase 内最后一个主键。每个短事务同时受 500-document 与 5,000-row 默认预算约束，任一预算先到即提交，
  并在同一事务内推进 phase/key cursor 与聚合计数；允许一个高扇出文档跨多个事务，禁止把全量依赖行放进单事务或依赖
  无界 cascade。任意批次失败/超时均回滚该批且不推进 cursor，重启后从同一位置安全重放；完成所有依赖 phase 后才删除对应
  document 行，最后保留 Collection tombstone 与 receipt。只在首个 destructive batch 前校验原 preview fingerprint；
  之后恢复以已提交 fence、原始 preview 摘要和 cursor 为准，不因部分删除改变 fingerprint 而卡死。preview 和
  apply 不得把全量 ID 列表载入 JVM。当前同步路径的依赖删除顺序来自
  `CollectionPurgeService.deletePurgeTargets()`，实施时逐一覆盖该方法中的引用表和 FK。
- 取消按业务可逆性区分：Profile re-embedding 可在 Collection CAS 切换前取消；purge 仅在
  `purge_fence_completed_at IS NULL` 时可取消（worker 尚未关闭 Collection 写入）。fence 一旦提交，即使首个 destructive batch
  尚未开始也拒绝取消（`409` + 机器可读原因），继续恢复直至完成，不能产生“已退役/部分删除但报告取消”
  的 Collection。运行中的普通文档 Job 仍使用现有协作式取消语义。
- 新增 `POST /api/v1/rag/collections/by-key/embedding-profile/operations`，请求含
  `collectionKey`、`targetProfileKey`、`expectedCollectionVersion`，要求 `Idempotency-Key`，返回 `202` operation receipt；
  Collection 创建使用可选 `embeddingProfileKey`。另新增 `GET /api/v1/rag/operations/{operationId}`、
  `POST .../cancel`、`POST .../retry` 和 `GET .../result`；
  新端点统一返回现有 REST 风格的直接 DTO（不加新的 envelope）：receipt 必含 `operationId`、`operationKind`、
  `collectionKey`、`status`、`progress`、`createdAt`、`updatedAt`、`statusUrl`、`resultUrl`；Profile switch 的
  `targetProfileKey`、`nextRetryAt`、`retryAfterSeconds` 和脱敏的 machine-readable `failure` 为可选增量字段。
  cancel 请求期间追加可选 `cancelRequested=true`，直到所有 child 不再运行且不能提交为止。`GET operation` 对保留期内任意状态返回 `200` 当前 receipt；cancel/retry 被接受时返回 `202` 和更新后的 receipt；
  `GET result` 仅在终态结果可用时返回 `200`，运行中返回 `409 OPERATION_RESULT_NOT_READY`，完整结果过期但 tombstone 尚在时返回 `410`；
  tombstone 仅保留最小状态/idempotency 信息，其有效期为 `max(created_at + 30 days, finished_at + 7 days)`。新请求参数错误 `400`、
  ACL 按下述 `403/404`、状态冲突 `409`、
  容量不足 `429`，所有错误继续使用既有可消费错误 envelope；不暴露文档正文、完整 provider exception 或 secret。
  每次查询/修改均重新校验 Operation owner 和对应资源 ACL。Purge 新旧入口统一保留
  `CollectionPurgeAuthorization` 的完整现有规则：环境 root、数据库 API Key `ADMIN`，以及仅在
  `rag.collection-purge.allow-auth-disabled=true` 时允许 Servlet 直接 loopback 的本地开发请求；该配置默认 `false`，
  不信任 forwarded/代理来源 Header，远程匿名访问始终拒绝，不扩权给普通 principal。未认证 loopback 使用现有
  `ChatPrincipal.AUTH_DISABLED_ID` (`local:auth-disabled`) 作为持久 owner，明确属于共享本机开发身份；每次读取/变更仍重验
  当前授权与 owner，不能从 Operation ID 推断权限。Profile re-embedding 使用 Collection 的现有写权限。新的异步 purge apply 必须携带
  `Idempotency-Key`，缺失时返回新增端点专属 `400`，同 key 同语义精确 replay、同 key 异语义返回稳定 `409`；
  Operation owner 不匹配时返回不泄漏存在性的 `404`，有资源可见权但缺少写 capability 时 `403`；receipt 到期后保留
  最小 tombstone 并返回 `410`，不回传已过期结果 payload；其保留至创建后 30 天与终态后 7 天两者中较晚的时间点；
  只有新端点新增 receipt，旧 purge endpoint 的
  response DTO 与 HTTP 状态码保持不变。
- Async apply 先只读校验 `ASYNC` preview，再在同一事务依次创建尚未 fence 的 Operation、预留 owner/global capacity、取得
  Collection slot、条件更新 preview 为 `APPLYING`；V67 Operation 以唯一 nullable
  `source_preview_id` 单向关联 purge preview，唯一且 `ON DELETE RESTRICT`（不在 preview 再存反向 operation id，避免循环
  FK/双真相，也防止 cleaner 删除 preview 时级联误删 Operation）；preview token
  的 preview/operation deadline 只限制被接受前的提交。接收后，异步 worker 的 lease/cursor/parent status 是恢复真相，旧
  preview cleaner 必须仅回收 `execution_mode='SYNC'` 的 APPLYING/expired/completed 行，以及不存在任何
  `rag_long_running_operations.source_preview_id = preview.id` 关联的过期 `ASYNC` preview；存在 parent 引用的 preview 绝不可复位、
  过期或删除。Operation 与 preview 同一事务转终态，只有 parent
  已终态且 receipt retention 到期后才联合脱敏/清理：在同一清理事务中先删除已过期 Operation 行（FK 方向为 Operation→preview，
  删除 Operation 不级联 preview），再删除其 source preview；任一步失败整体回滚；
  `DEGRADED` parent 仍可恢复，不进入普通 cleaner。并发 apply/preview-cleaner 竞争由 preview 状态 CAS、唯一 FK 和 `ON DELETE RESTRICT`
  fail closed，绝不能丢失已接受 Operation。同步 apply 发现
  `execution_mode='ASYNC'` 或存在 `source_preview_id` parent 引用时稳定返回专属 `409`，绝不执行同步删除。
- 保留 `rag.collection-purge.enabled` 现有高风险总开关及 root/ADMIN gate；“新端点默认启用”指在此既有安全能力
  已启用时不再额外要求新 feature flag，不表示默认开放危险删除能力。
- 资源治理：结果 payload 脱敏并设置过期时间；后台只清理已过期终态结果；运行中的资源、引用的 Collection、
  lease 和审计记录不能被清理任务误删。并发上限按 owner、全局和 operation kind 分层。

### 2.6 已核对的实施入口

| 范围 | 主要实现入口 | 实施时必须连带覆盖 |
|---|---|---|
| Collection purge/异步操作 | `spring-ai-rag-core/src/main/java/com/springairag/core/service/CollectionPurgeService.java`、`spring-ai-rag-core/src/main/java/com/springairag/core/controller/RagCollectionController.java`、`spring-ai-rag-core/src/main/java/com/springairag/core/service/CollectionPurgeAuthorization.java`、`spring-ai-rag-core/src/main/java/com/springairag/core/config/RagCollectionPurgeProperties.java`、`spring-ai-rag-core/src/main/resources/db/migration/V56__collection_purge_and_content_references.sql` | preview/apply DTO、`ApiRoutePolicyRegistry`、`CollectionPurgeControllerWebTest`、`CollectionPurgePostgresIntegrationTest`、审计和完整 FK 删除顺序 |
| Profile/config/model | `spring-ai-rag-core/src/main/java/com/springairag/core/config/EmbeddingProfileRegistry.java`、`EmbeddingModelRouter.java`、`EmbeddingModelConfig.java`、`RagEmbeddingProperties.java`、`EmbeddingVectorColumns.java`、`EmbeddingProfileIndexManager.java` | exact model identity、secret-safe profile discovery、V25/V60 数据、HNSW 索引、配置缺失/同 provider 多模型拒绝 |
| Embedding 写入/job/run | `spring-ai-rag-core/src/main/java/com/springairag/core/service/EmbeddingPersistenceService.java`、`DocumentEmbedService.java`、`spring-ai-rag-core/src/main/java/com/springairag/core/embeddingjob/EmbeddingJobService.java`、`EmbeddingJobExecutor.java`、`EmbeddingDispatchService.java`、`spring-ai-rag-core/src/main/java/com/springairag/core/documentprocessing/DocumentProcessingRunService.java` | 所有调用点从全局 active Profile 改为目标 Collection/Profile；事务性创建/复用 Run、Operation–Run 关系和扫描 cursor；重试/取消/reclaim/CAS 仍引用同一 Profile |
| Ingestion/Collection | `spring-ai-rag-core/src/main/java/com/springairag/core/service/DocumentMutationService.java`、`ExternalDocumentService.java`、`CollectionProvisioningService.java`、`spring-ai-rag-core/src/main/java/com/springairag/core/repository/RagCollectionRepository.java`、`RagCollectionService.java`、`spring-ai-rag-core/src/main/java/com/springairag/core/util/CollectionMapper.java`、`spring-ai-rag-core/src/main/java/com/springairag/core/entity/RagCollection.java`、`spring-ai-rag-api/src/main/java/com/springairag/api/dto/CollectionRequest.java`、`CollectionResponse.java`、`CollectionCloneRequest.java`、`CollectionImportRequest.java` | file/PDF/JSON/external-sync/relocation、collection create/update/clone/import/export、公开 DTO 原构造器兼容 |
| Retrieval/readiness/integrity | `spring-ai-rag-core/src/main/java/com/springairag/core/retrieval/HybridRetrieverService.java`、`EmbeddingProfileSqlScope.java`、`RetrievalScopeSql.java`、`spring-ai-rag-core/src/main/java/com/springairag/core/repository/RagEmbeddingRepository.java`、`spring-ai-rag-core/src/main/java/com/springairag/core/service/DocumentLifecycleService.java`、`DerivationIntegrityService.java`、`DerivationIntegrityRepository.java`、`DerivationRepairService.java`、`ChunkingUpgradeService.java` | Search/Chat/Agent/OpenAI scopes、freshness、readiness、repair、upgrade、evaluation snapshot 与 Profile 一致 |
| Client/quality | `spring-ai-rag-core/src/main/java/com/springairag/core/controller/OpenAiCompatibilityController.java`、`spring-ai-rag-core/src/main/java/com/springairag/core/openai/OpenAiChatRequestMapper.java`、`spring-ai-rag-core/src/main/java/com/springairag/core/security/ApiRoutePolicyRegistry.java`、`scripts/verify-api-compatibility.sh`、`scripts/verify-client-contract-governance.sh`、`scripts/verify-openai-sdk-e2e.sh`、`scripts/verify-quality-real-e2e.py`、`testdata/knowledge-qa-quality/` | OpenAPI/JSON/SSE fixtures、SDK 黑盒、错误/重试/Retry-After、真实质量基线与凭据脱敏 |

完整 Producer API 快照扩展既有 `testdata/api-contract/consumer/v1/openapi-surface.json`，由
`scripts/lib/client-consumer-contract.mjs` 校验；不得另建 `testdata/api-contract/v2/`，避免与已有
consumer 快照形成双重兼容真相。既有 `testdata/api-contract/v1-compatibility.json` 与
`scripts/lib/api-contract-check.mjs` 继续维护其 Producer 兼容策略/关键路由检查，consumer v1 的
fixture 负责客户端可消费语义；本批只扩展既有资产，不另造平行 Contract Kit 或重复 manifest。

### 2.4 Client API 契约长期治理与 OpenAI 兼容增量生产化

- 扩展既有 Native API 和 OpenAI 子集 OpenAPI surface snapshot 与 JSON/SSE fixture：快照必须覆盖本批基线中
  所有既有 `/api/v1/rag/**` 和受支持 `/v1/**` 的 method/path、required/query/header、request/response schema、
  已声明的响应状态码、错误 envelope 与权限画像；另为 SSE 保存事件顺序和 terminal frame。由于
  `OpenAiCompatibilityController` 受 `rag.openai-compatibility.enabled` 条件控制，基线快照必须在专用契约验收配置中
  显式设为 `true`，从基线应用 `/v3/api-docs` 获取所有受支持 `/v1/**` 路由；生产默认关闭语义保持不变并单独回归。
  请求/响应 schema 和状态码从 OpenAPI 提取；权限画像从真实 `ApiRoutePolicyRegistry` 快照提取，不从 URL 或 UI 推断。
  扩展既有 `scripts/lib/client-consumer-contract.mjs` 以校验当前 surface 对基线不发生破坏，包括已声明状态码、
  required/query/header、字段类型、错误 envelope 和 route policy。兼容基线固定从本批基线提交
  `771f06837828f6c85e1b324c388cb3833e1af972` 提取，并写入现有
  `testdata/api-contract/consumer/v1/openapi-surface.json`；新增 optional request/response 字段与新 endpoint
  允许，删除/改名、改类型、把 optional 改 required、删状态码或改变 SSE 顺序均失败。实现时如从最新 `origin/main`
  合并了新增契约，应在合并后的代码上更新“本批前”基线，不覆盖历史 fixture，也不把当前修改误当兼容基线。
- `GET /api/v1/rag/integration-capabilities` 增加 operation、embedding profile、async operation、OpenAI
  compatibility 的能力投影；保持原字段不变，只追加字段，并设置 no-store。
- OpenAI 兼容保持 text-only、`n=1` 的受控范围；现有 `id`/`created`/`usage`、JSON envelope 和 SSE chunk 顺序
  属于冻结契约，不重复当成新增功能。支持的新增字段优先为 `system_fingerprint`、关联 `traceId`/request ID、
  machine-readable error details，以及错误/限流场景的 `Retry-After`。对不支持的 `temperature`、tools、
  structured output 等继续返回 `unsupported_parameter`，禁止静默忽略或降级。
- 全量 API surface contract diff 由既有 consumer contract gate 纳入 CI/release 门禁：比较基线提交快照与当前 OpenAPI，新增 optional request
  字段、response 字段和新 endpoint 可通过；删除/改名/改类型/改 required/删既有状态码/改变既有 JSON envelope 或
  SSE 顺序均失败。OpenAPI snapshot 是结构兼容底线，JSON/SSE fixture 和外部 HTTP consumer runner 验证真实运行时语义。
- Python/Node SDK 黑盒 runner 扩展：Native JSON、Native SSE、OpenAI JSON、OpenAI SSE、429/409/502/503、
  安全拒答、超时和重试头；必须验证 SDK 端可安全读取新字段而非只检查 HTTP 200。
- Native API 的兼容性治理和新增 async/Profile API 随版本默认可发现、默认可用，不增加新 feature flag；主动真实
  评测仍由请求方显式发起，不增加普通 Chat 成本。现有 OpenAI `/v1` 兼容入口继续默认关闭：当前安全配置允许
  `rag.security.enabled=false` 且未配置 root key 时数据 API 无认证，默认开放可触发上游 LLM 费用的新入口会扩大
  匿名成本面。仅显式配置 `RAG_OPENAI_COMPATIBILITY_ENABLED=true` 后启用，并复用 API Key 认证、现有
  `ApiRoutePolicyRegistry` capability（当前 `GET /v1/models` 为 `RAG_READ`、`POST /v1/chat/completions` 为
  `RAG_WRITE`）、rate limit、budget、scope ACL 和当前 alias 白名单；不得为兼容入口降级或绕过当前 capability。
  若显式启用 `/v1` 但 `rag.security.enabled=false`，启动配置校验必须拒绝该组合；不能因兼容 flag 为 true 就把原本可匿名访问的
  部署变成匿名付费 Chat 入口。OpenAI 契约验收 profile 必须同时开启
  API Key security 并用真实认证请求；生产默认关闭仍需单独回归。不新造宽松旁路。此为已核实的安全/成本例外，
  而不是把新协议默认退回 legacy。

### 2.5 真实模型质量漂移基线

- 版本化每个 QA case 的问题、语料/Collection、预期来源、必要事实、禁止事实、证据不足条件、拒答条件和
  允许的安全拒答类别；快照模型、Embedding Profile、Prompt/策略、配置摘要、代码版本和语料摘要。
- 主动执行时才调用真实模型，普通 Chat 不额外付费。每次 run 记录 provider/model、embedding profile、配置、
  延迟、token、预算预留/结算、引用覆盖、证据充分性、HTTP/业务状态、upstream 分类和安全拒答分类。
- 确定性断言是硬门槛：来源授权、必要事实、禁止事实、证据不足拒答、状态码/envelope/SSE、无越权泄露。
  LLM judge 只能作为辅助证据，不能单独宣布通过。
- 同一 case 支持基线比较和漂移报告；原始失败、上游超时/错误、安全拒答必须保留，不通过改断言或重跑刷绿。
  安全拒答若符合 case 的拒答条件，标为业务处置正确而非正向内容失败。
- 新增跨批通用门禁脚本/报告 schema，供真实 Embedding、Grok、MiniMax 等 provider（凭据从 `.env` 注入，
  报告不得包含密钥）使用；provider 不可用时报告明确环境限制，不冒充通过。
- 质量 fixture 和真实验收 Collection 仅使用专门构造的非生产测试语料，不抓取真实客户对话/文档。`.verification/` 中
  的私有 run artifact 可保留原始模型结果以便复现，但必须保持 gitignored、不得复制进公开文档/CI artifact；通用摘要只导出
  case ID、模型/Profile 身份、确定性断言、分类、token/延迟和经脱敏的最小证据，清理期限默认 7 天，密钥、Authorization
  Header、完整请求凭据和不相关用户内容一律禁止持久化。
- 真实成功路径必须调用实际 Embedding/LLM provider；确定性上游 HTTP 错误、连接超时和重试恢复通过注入式
  fault proxy/受控测试端点验证同一业务异常映射，不把 provider 自发超时当成可重复测试前提，也不把 mock
  调用冒充真实模型质量结果。

## 3. 数据迁移与发布策略

### 3.1 Flyway 顺序

- `V67`：在迁移事务内设置 5 秒 `lock_timeout`；建立 nullable Collection `active_embedding_profile_id`、backfill control、Operation 表（含 purge 专属唯一
  `source_preview_id ON DELETE RESTRICT` 外键）、`rag_long_running_operation_run_refs` 父子关联表、Collection slot、
  owner/global 容量行、异步 preview admission lease 表和受支持维度列；给既有 `rag_collection_purge_preview` 增加默认
  `SYNC` 的 `execution_mode`；复用既有 Collection version/chat fence，不新增重复写栅栏列；保留 `embedding` 到下一条
  紧邻迁移，不调用模型、不假设默认 Profile 数据行已存在。固定向量列在迁移中创建；Profile registry 注册并绑定完成后，
  Application bootstrap 通过现有 `EmbeddingProfileIndexManager` 在 Flyway 事务外并发创建/校验每个 enabled Profile 的 HNSW
  索引，索引全部有效、Profile 路由 readiness 达标前不开放对应检索。
- `V68`：在迁移事务内设置 5 秒 `lock_timeout`；将 backfill control 置为 `PENDING`，删除旧 `rag_embeddings.embedding` 列；不在 Flyway 中全表枚举、
  不清理 Profile-null 行、不调用 provider。profile-bound 且固定列可检索的向量保留；无法证明归属的 cache 不猜测、
  不迁移为新 Profile。后台 worker 在目标 Profile 向量成功提交后，删除该文档旧的 Profile-null 派生行。
- 应用启动完成 Profile registry 和 Collection 默认绑定后，由 durable worker 按持久 keyset cursor 扫描候选文档，逐页把缺少
  当前 Profile 有效向量的文档转成对应
  Collection Profile 的 Processing Run/Embedding Job，外部模型调用只发生在后台 worker；操作不要求手工开关、
  SQL、重索引命令或 `adopt-legacy`。
- 版本号实施前复核，永不改写已执行迁移。所有迁移必须在真实 PostgreSQL 上验证 fresh database、V66→latest
  upgrade、重复启动、部分失败恢复、自动回填及无可归属向量仍能启动。另在独立 V66 upgrade 数据库中分别对 `rag_collection`
  和 `rag_embeddings` 持有冲突事务锁，验证 `V67`/`V68` 均在 5 秒界限内失败且整条迁移回滚、Flyway schema history 不误记成功；
  释放锁后重复启动必须完成升级，既有数据与可重试状态保持一致。

### 3.2 默认升级与回滚

- 历史 Collection 自动绑定当前默认 Profile，默认行为保持 1024；新 Collection 默认绑定默认 Profile。
- Profile 路由新路径默认启用；旧 `embeddingModel`/`dimensions` 仅作为兼容投影，不作为绕过路由的 legacy 开关。
  Clone 默认继承源 Profile，Import/Create 默认使用当前 Profile；只有可选 `embeddingProfileKey` 显式选择其他 Profile。
- 目标 Profile 未就绪时旧 Profile 继续服务；切换由 CAS 完成，失败自动保留旧活动 Profile。
- V68 版本的本项目 Helm Deployment 固定使用 `strategy.type=Recreate`，控制器先终止旧 ReplicaSet 的全部 Pod，再创建新 Pod、
  执行 Flyway；发布验收分别渲染默认及 production values 并检查每个 Deployment 的策略为 `Recreate`。单实例 Docker Compose
  的受支持升级使用服务级 recreate，确保旧容器退出后才启动会触发 V68 的新容器。此批接受一次短暂 downtime 换取无需人工
  SQL 和无旧新版本混跑。外部自管编排不属于本项目 release gate 可自动检查的输入；V68 发布文档必须明确该版本不支持
  RollingUpdate/新旧二进制混跑，并要求外部部署在迁移前先停止全部旧实例。若发布包接收用户提供的 Deployment manifest，
  gate 只对实际收到的 manifest 做同一 `Recreate` 断言，不宣称能拒绝未提供给 gate 的任意外部配置。
- schema 迁移不可自动回滚；旧 `embedding` 删除后不支持旧二进制直接回滚，数据恢复依赖已验证备份，正常修复
  使用向前兼容的新迁移。文档正文不删除，丢失的只是旧派生向量，并由自动 durable re-embedding 恢复。

## 4. 文件级实施顺序

1. **文档事实与进度**：保持本计划、progress、双语 TODO、API/架构/配置/测试/开发者参考及 Client SDK 示例同步；更新 Helm
   `Recreate` 策略、单实例 compose 升级说明和对应 release assertion。
2. **异步 Operation 基础**：V67 Operation/Run-ref/slot/capacity/preview-admission 表、枚举/DTO、repository、
   Run 关联及 cursor 原子事务、CAS 状态机、lease/heartbeat/housekeeping、operation controller 和 purge 超限桥接；
   先覆盖小规模同步兼容。
3. **Profile 路由基础**：固定列白名单、Profile registry/model resolver、Collection 绑定/mapper/DTO 增量字段、
   写入和 readiness 按绑定 Profile；再接多 Profile 查询分组与 fan-out 上限。
4. **重嵌入与旧列迁移**：实现 V68 durable backfill control/keyset cursor 与旧列删除；以固定列现存向量保持可用，
   其他文档自动创建 document processing runs/jobs；Profile 切换在 readiness 完成后 CAS 原子提交。
5. **契约治理**：capability 投影、OpenAPI/fixture diff、Native/OpenAI JSON/SSE、Python/Node SDK runner、
   错误/Retry-After/trace 增量字段。
6. **质量基线**：扩展 QA definition/runner/report/baseline compare；接入发布验收和真实 Embedding/LLM 场景。
7. **统一验收与文档提升**：先基本硬门槛，再实现 `3/3`，再真实模型、`scripts/dev.sh`、外部 API 黑盒、
   迁移/恢复和双实例测试；将稳定事实提升到双语长青文档，归档 plan/progress。

## 5. 测试与验收矩阵

### 5.1 迁移/PostgreSQL

- Fresh database 从 V1 到 latest；V66 数据升级到 latest；双写向量保持可检索；仅 legacy-only / 明确应有 Profile
  但向量缺失的候选由 control cursor 分页发现并自动重嵌入；未请求过 embedding 的普通文档不产生付费调用。
  provider 暂时失败或进程中断时服务可启动且 backlog 可恢复；既有 child Run dead-letter 冷却恢复不改历史 Run，重复启动、
  同一候选不重复生成内容版本；永久异常呈现父 `DEGRADED` 而非全量成功。
- 在真实 PostgreSQL upgrade 测试中分别持有阻止 `V67` 修改 `rag_collection`、阻止 `V68` 修改 `rag_embeddings` 的长事务锁；
  断言各迁移按 5 秒 `lock_timeout` 失败、单条迁移无部分提交且 Flyway 版本记录准确，释放锁后重启成功完成迁移。
- Collection profile 绑定、Profile 禁用、维度白名单、同维度不同 Profile 隔离、索引谓词中的数据库 numeric ID 字面量
  和固定列白名单、索引存在/有效、readiness 和
  完整性失败均用 PostgreSQL 集成测试覆盖；仅被 inactive Collection 引用的缺配置 Profile 不阻断启动，但恢复该 Collection
  必须因 profile 未配置/readiness 未就绪而稳定拒绝，补回配置后自动重建索引并恢复成功；活动 Collection 引用缺配置 Profile
  必须 fail closed；inactive-only Profile 自动禁用 DB 投影/移除专属索引但保留向量和 profile row。
  组合验证现有 `GLOBAL` 默认和显式
  `EACH_COLLECTION + SELECTED_COLLECTIONS` 在跨 Profile fan-out/RRF 后的来源覆盖、权限、header/metadata 和排序不回归。
- V67 fresh/upgrade schema 验证 Run-ref 的 operation/run 外键、唯一键、反向索引和级联清理；删除/过期任一父 Operation
  或因文档生命周期删除 child Run 时，只回收对应关联行，不改变仍存活的 Operation/Run 及其余引用。

### 5.2 异步与恢复

- 小 purge 保持同步返回；大 purge 202 + operation receipt；重复 Idempotency-Key 精确 replay；语义冲突拒绝。
  preview cleaner 不复位/过期/删除已关联活动 Operation 的 `ASYNC` preview，child/parent 成功、失败和重启后关联保持；
  未 apply 的过期 `ASYNC` preview 可回收，既有 `SYNC` preview 清理和重放保持兼容；注入 apply 与 preview cleaner 并发，验证
  `source_preview_id ON DELETE RESTRICT` 阻止误删已接受 Operation，清理失败整笔回滚后可安全重试。
- purge apply 返回 `202` 后至 worker 首次 fence 前 Collection 仍可读写；取消先赢则无 fence/删除且释放 slot，worker fence
  先赢则取消返回 `409` 并恢复完成；并发写先于 worker fence 时 fence 事务完整回滚，后续单独 stale-terminal CAS 释放 slot，
  Collection 数据完整保留且必须重新 preview；并验证取消与该 stale-terminal CAS 竞争不会覆盖胜出状态。
- worker claim、120 秒默认/180 秒硬上限 Operation lease、20 秒 heartbeat fencing、重启恢复、父 Operation 取消/显式 retry、子 Processing Run
  retry/dead-letter；故障注入验证 child 创建、Operation–Run 关系和扫描 cursor 原子提交，重放不漏链/重复计数；
  superseding Run 仍关联同一父 Operation 且不改写历史 Run；不匹配文档 revision 的活动 Run 不被误聚合或误取消；
- parent 取消仅取消本 Operation 创建且未被其他活动 parent 共享的 Run；对已观察/共享 Run 保留原状态；双实例取消竞争验证
  父任务在自有 worker 无法再提交前不释放 slot，已取消 Profile Operation 即使观察到的 Run 后续成功也绝不切换 Collection Profile；
  过期结果清理、owner/ACL 隔离、双实例无重复 destructive commit；不同 idempotency key 并发申请同一 Collection
  的 purge/Profile switch 只能一个成功，旧同步 purge apply 与 Profile switch/async purge 也经共享槽互斥；
  父 `DEGRADED` 占用资源槽直至原 Operation 安全收敛，子 `DEAD_LETTERED` 由 Processing Run 保持原语义；
  owner/global 并发容量在双实例压力下不超限。
- RUNNING Profile switch cancel 采用协作式收敛：cancel 请求后不得派发新 child，Collection slot 保持占用，直到所有在途 Embedding
  worker 都无法再提交；在此之前对同 Collection 新 Profile/purge Operation 返回 `409`。双实例测试覆盖 cancel 与远程调用/最终 CAS 的竞争。
- 异步 preview 超过文档/派生行/时间上限 fail closed、不产生可提交的 partial preview；180 秒默认/240 秒硬上限 admission lease
  在 crash/超时/reclaim 下不重复签发，lease/deadline/statement-timeout 配置关系校验正确；owner/global preview lease 限额在
  双实例并发、进程中断和 lease reclaim 下不超限；operation 结果终态后 7 天脱敏、tombstone 保留至
  `max(created_at + 30 days, finished_at + 7 days)`、到期 `410` 及其后幂等 key 可复用均有数据库集成验收，含超过 23 天的合成时长用例。
- purge 在不可逆 fence 前必须检查并排空/安全取消活动文档 Processing Run、Embedding Job、Sync Run、派生修复和
  Chat commit；在途 worker 还必须受相同 Collection version/chat CAS fence 保护。不得因只检查旧的 sync/repair/chat
  计数便假设 Embedding 任务静止。
- 两实例并发写与 purge race：覆盖 writer 先 CAS、purge 先 CAS、远程 embedding 已开始但最终提交晚到、Chat fence
  变更、反馈/引用新增、preview 扫描中 version 改变；结果只能完整保留并可重试，或完整 fence 后删除，无孤儿行/半状态。
- 高扇出单文档的 purge 必须在 5,000-row 默认批次预算内跨事务推进 phase/key cursor；注入批次中断、statement timeout 和重启，
  并用多条延迟 SQL 验证逐语句 timeout 随剩余预算递减；超过事务 deadline 时整批回滚、cursor 不推进、下一 fencing token
  从相同位置安全重试，已提交行不重删、未提交 phase 不跳过、无 FK 悬挂且事务不超过硬上限。
- 文档长任务与 purge 共享通用状态/资源治理边界，但不强行合并业务表；stage progress 和最终 receipt 可追踪。

### 5.3 外部 API/SDK

- 既有 `/api/v1/rag/**` 的旧 JSON、SSE、状态码、错误 envelope、权限和幂等回归；增量字段兼容旧 client。
- Profile 路由改造后既有同步/异步入口的调用时机、`embeddingStatus`、状态码/envelope 与 Processing Run retry
  行为保持一致；新增 Operation 只用于新增跨文档批量流程。
- 新 async purge preview/apply、Profile 切换 Operation、operation GET/cancel/retry/result、capability、Profile 选择和 readiness 端点覆盖
  200/202、400、403、404、409/not-ready、410/过期、429、502/503、超时和安全拒答，并断言 receipt/error DTO 的冻结字段。
  异步 preview SDK 样例的专用 read timeout 为 150 秒且大于其 deadline 加 15 秒；普通 SDK 请求仍保留原有短 timeout。
- OpenAI `/v1/models`、`/v1/chat/completions` JSON/SSE；Python/Node SDK；不支持字段保持机器可读拒绝；旧 SSE
  role/content/finish 顺序不变；Retry-After/trace/usage additive。
- `/v1` 默认关闭；显式开启但 `rag.security.enabled=false` 必须启动失败。安全启用时验证匿名拒绝、`/v1/models` 的
  `RAG_READ` 与 Chat 的 `RAG_WRITE`、scope ACL、rate limit、budget 和 alias allowlist，不得出现匿名或降级能力旁路。
- 异步 purge 复用现有授权路径：默认关闭 auth-disabled loopback 时匿名 loopback 拒绝；显式启用后只有 Servlet 直接来源为
  loopback 才能使用，伪造 forwarded 地址仍拒绝；root/数据库 ADMIN 通过，普通数据库 principal 仍拒绝；本机未认证操作
  的 owner 固定为现有共享 `local:auth-disabled`，其他 owner 不可读取或修改其 receipt。
- `scripts/verify-api-compatibility.sh`、`verify-client-contract-governance.sh`、
  `verify-client-consumer-contract.sh` 和 SDK runner 均执行；只改 API 不依赖 WebUI 通过来宣称完成。

### 5.4 真实模型

- 真实 Embedding：至少配置并实际调用 3 个隔离的测试 Profile/model identity：其中 2 个为相同维度、不同模型，另 1 个为不同维度；
  写入、查询、维度/固定列、索引命中、同维度身份隔离及跨维度路由都要由真实 provider 响应验证，使用受控测试 Collection。
  Provider 无法提供满足上述三种身份的真实模型时，不得把相应真实路由覆盖标为通过，需在结果中明确未覆盖的身份类型与环境限制。
- 多 Profile 检索必须验证不同 Profile 的原始 cosine 分数不会直接混排；固定 RRF (`k=60`) 结果、相同分数的稳定 tie-break、
  reranker 次序和 fan-out 超限均有确定性集成断言。故障注入验证一个必需 Profile 的 query embedding 超时、429/5xx 或
  非法维度响应时不返回无标记 partial results、不跨 Profile fallback，并保留既有 HTTP 状态/envelope、脱敏失败分类和 Retry-After。
- 真实 LLM：至少正向 KNOWLEDGE、证据不足、安全拒答、上游错误、上游超时、重试/Retry-After、引用与 trace；
  provider 失败必须验证业务层返回可消费的错误/恢复建议，而不是把上游异常当作服务崩溃。
- Grok/MiniMax/其他 `.env` 配置模型分开运行，保留逐 provider 原始结果、失败分类和成本；不输出敏感信息。
- 真实调用只由主动验收脚本触发，普通 Chat 不增加评测费用；未执行的 provider 明确记录限制。

### 5.5 启停、回归和发布

- `mvn clean compile test-compile`、相关单测/集成测试、`./scripts/verify-no-pessimistic-locks.sh`、
  `./scripts/verify-project-docs.sh`、`git diff --check`。
- `scripts/dev.sh` 在隔离端口启动/健康检查/停止/重复启动，验证 Flyway、API、后台 worker 和无残留进程；Helm render/release
  gate 使用默认及 production values 渲染并断言本项目所有 Deployment 为 `Recreate`。单实例 compose upgrade acceptance
  确认旧容器停止后再运行新版本迁移；部署文档明确外部编排边界与 V68 不可滚动混跑要求。
- WebUI 若未受影响只运行共享契约所需构建/Mock；不得将 WebUI 结果作为总体验收。
- 基于最新 `origin/main` merge 后重新跑核心 PostgreSQL/API/真实模型/启停/三轮实现 review，最后再合并 main、
  push 并确认工作区干净。

## 6. 风险、默认值与可逆边界

| 风险/未知项 | 推荐默认 | 可逆边界 |
|---|---|---|
| 旧列存在未知模型向量 | 不猜 Profile；保留源文档，删除派生列后由 durable keyset backfill 自动重嵌入 | 仅对应文档短期缺少向量，其他文档和 Collection 继续服务 |
| 维度超出首批白名单 | 明确拒绝，不动态建列 | 未来新增白名单列和迁移 |
| Profile 切换期间目标未就绪 | 旧 Profile 继续服务 | CAS 切换前可取消/重试 |
| 大 purge 删除耗时过长 | 202 Operation、批次化；worker fence 前可取消，fence 后不可取消 | 小请求仍走原同步路径；取消/fence 以同一 Operation CAS 决出唯一赢家 |
| 多 Profile fan-out 超限 | `422 EMBEDDING_PROFILE_FANOUT_LIMIT_EXCEEDED`，不静默漏召回 | 调整上限/请求拆分 |
| OpenAI-compatible `/v1` 默认仍关闭 | 默认部署可无认证且兼容 Chat 会消耗上游预算；显式启用须同时启用 API Key security，并复用当前 READ/WRITE capability、限流和预算门禁 | 显式开启并使用受保护凭据；Native API 与 Profile/Operation 新能力不受影响 |
| V68 与旧二进制混跑 | Helm Recreate/单实例 Compose recreate 自动先停旧实例；release gate 拒绝外部 RollingUpdate | 接受一次短暂 downtime；数据库备份恢复或向前修复；旧客户端 HTTP API 兼容性不受影响 |
| 异步 purge 与在途写入竞态 | Collection mutation-fence CAS 必须覆盖每个写路径；未覆盖即不执行破坏性任务 | fence 前冲突可重试；fence 后 worker 只恢复，不取消 |
| OpenAI 不支持参数 | `unsupported_parameter` | 后续逐项增加 capability，不改变旧字段 |
| provider 安全拒答 | 按业务拒答合同返回，不标记服务崩溃 | 正向 case 与拒答 case 分离 |
| 真实 provider 不可用 | 保存限制，不伪造通过 | 重新注入凭据后重跑主动验收 |

## 7. 完成定义

只有同时满足以下条件才可报告本批完成：

1. 本计划和 progress 可从中断处恢复，规划/实现分别连续 `3/3` 无实质问题；
2. V67+ 迁移在真实 PostgreSQL fresh/upgrade/失败恢复场景通过；legacy cache 不被猜测认领，旧列自动删除且
   源文档经后台可恢复地重新嵌入；
3. 小 purge 和既有 API 完整兼容，大 purge 有持久化 Operation、恢复、取消、重试和 ACL 证据；逐文档状态仍由既有
   Processing Run/Embedding Job 承载，V67 关系表只建立可恢复的 parent-child 引用和创建所有权，不复制任务状态；
4. 至少三个真实配置 Embedding Profile：两个相同维度的不同模型、一个不同维度模型；三者均在固定维度列和 Profile
   作用域上真实隔离，Collection 路由和原子切换可验收；历史 Collection 自动绑定发生在 profile registry 初始化之后，
   未绑定时数据面 readiness 不就绪；
5. Native/OpenAI JSON/SSE、Python/Node SDK、错误/权限/幂等/旧 envelope 回归通过；
6. 真实 Embedding 与多个真实 LLM 场景已执行；安全拒答、上游失败/超时均验证业务层妥善处置；
7. `scripts/dev.sh` 启停、双实例、V68 Recreate 部署策略、无悲观锁、文档门禁和完整构建通过；
8. 双语长青文档同步，plan/progress 归档，特性分支 commit、merge、push 完成，最终工作区干净。
