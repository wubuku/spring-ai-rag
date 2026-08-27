# Collection 受保护清理与退役实施进度

> 对应规划：[COLLECTION_PURGE_AND_RETIREMENT_PLAN.md](COLLECTION_PURGE_AND_RETIREMENT_PLAN.md)
> 工作区：`/Users/yangjiefeng/Documents/wubuku/spring-ai-rag`
> 当前分支：`feature/collection-purge-retirement-20260827`
> 基线：`90ee276c`（2026-08-27）

## 状态

- 当前阶段：完整 Mock/数据库门禁已通过，真实 LLM/Embedding 生命周期验收进行中
- 规划审查计数：`3/3`
- 实现审查计数：尚未开始
- 工作区：直接使用主工作区的专用分支，未创建 worktree，未使用 stash

## 已完成探索

- 核对 Collection 软删除、restore、按 key 路由和 Collection version CAS。
- 核对本地永久文档删除与外部文档 tombstone 的分界。
- 核对 V25、V29、V33、V40、V42、V43、V44、V45、V52、V54 的外键及级联/SET NULL 行为。
- 冻结“保留 Collection tombstone、只物理删除其文档内容”的设计，避免 key 复用和历史 FK 断裂。

## 规划审查日志

### 第 1 轮：需求闭环与自包含性

- 时间：2026-08-27
- 范围：目标、非目标、权限默认、API 契约、恢复入口。
- 发现：按 Collection 删除 repair preview 不足以覆盖文档迁移后仍引用目标文档的
  其他 preview；auth-disabled/legacy static 的 purge 权限默认也未冻结清楚。
- 处理：将 repair 检查/删除范围改为按待删除文档集合全局处理；明确
  environment root、数据库 ADMIN、数据库 NORMAL、legacy static 和 auth-disabled
  的权限默认及显式本地开关。
- 结果：已修订 plan，计数重置为 `0`，重新开始三轮检查。

### 第 2 轮：代码、数据、安全与并发可行性

- 时间：2026-08-27
- 范围：实体/迁移、FK、事务顺序、CAS/lease、权限和错误语义。
- 发现：原清理顺序在物理删除文档之后才把 Collection 标记退役；在活动运行检查和
  删除之间存在并发窗口，新的外部 upsert/sync 可能取得旧 Collection 版本并写入正在
  清理的文档集合。仅依赖 apply lease 不能阻止 Collection 写入者。
- 处理：将 Collection version CAS 生命周期 fence 前移到所有活动运行检查和物理删除
  之前；明确 fence 对已软删除但未退役 Collection 也必须执行；fence 后重新按待删除
  文档 ID 集合检查 active sync/repair，最后再以 fenced version 写入 `purged_at`。
  任何失败整体回滚，不能留下临时 fence 或部分删除。
- 结果：已修订 plan，计数重置为 `0`，重新开始三轮检查。

### 第 3 轮：需求闭环与数据删除可行性

- 时间：2026-08-27
- 范围：第 1 轮重审中的需求闭环、自包含性、外部地址和规模安全边界。
- 发现：规划没有明确 Collection 作为 relocation source/target 时的永久地址 fence
  语义；只限制文档、embedding 和 version 数也无法约束大量 keyword/repair 派生行。
- 处理：明确保留 relocation marker，删除正文时仅依靠 `ON DELETE SET NULL` 清空
  `document_id`，保留 source/target Collection tombstone 和 active 地址 fence；预览增加
  marker 与 repair 计数，并新增全部待删除派生行的总上限。
- 结果：已修订 plan，计数重置为 `0`，重新开始三轮检查。

### 第 4 轮：外键删除顺序与 WebUI 权限来源

- 时间：2026-08-27
- 范围：第 1 轮再次重审中的实际 FK、向量删除路径、当前 principal 投影和控制台解锁边界。
- 发现：V1 的 `rag_embeddings.document_id` 使用默认 `RESTRICT`，现有迁移未改成
  `ON DELETE CASCADE`，原计划直接删除文档会失败；WebUI 当前只允许 environment root
  解锁，不能把数据库 `ADMIN` 假设为可进入控制台的身份。
- 处理：清理顺序增加显式向量删除；其余派生表才依赖级联。WebUI 动作限定为已解锁的
  environment root，数据库 `ADMIN` 和显式 auth-disabled 仅通过 HTTP API 使用。
- 结果：已修订 plan，计数重置为 `0`，重新开始三轮检查。

### 第 5 轮：Chat 正文副本、会话归因与提交竞态

- 时间：2026-08-27
- 范围：重新第 1 轮中的 ChatSource、history、tool transcript、Spring AI memory、
  conversation summary、turn idempotency replay 和并发提交。
- 发现：原规划错误地把 Chat citation 视为“不含正文”的历史事实。实际
  `ChatSource.chunkText` 最多持久化 2000 字正文，agent tool transcript 和
  `rag_chat_turn_operations.response_payload` 也可能复制检索片段；memory、summary 和
  后续回答无法可靠按单一文档局部脱敏。另有在途 Chat 已检索但尚未提交时，单纯
  Collection lifecycle fence 不能阻止其在 purge 后重新写入 history/memory/replay。
- 处理：新增规范化 `rag_chat_history_source_document` 引用索引；只要会话引用目标文档，
  purge 就预览并整体删除该 owner-scoped 会话的 history、memory、summary 和 turn
  operation。新增 `chat_commit_fence_version` 条件写入，让 Chat 持久化提交和 Collection
  生命周期 CAS 在同一行上排序；受影响会话仍有有效 lease 时 purge fail closed。
- 结果：已修订 plan，计数保持 `0`，从完整方案重新开始三轮检查。

### 第 6 轮：控制面并集、过期 replay 与 WebUI 可达性

- 时间：2026-08-27
- 范围：重新第 1 轮中的 sync/repair scope、document/Collection idempotency replay、
  Collection active-only 列表和 WebUI 操作入口。
- 发现：sync/repair 检查若只按目标 Collection 或只按待删除文档引用都会漏掉另一类冲突；
  document mutation/relocation 成功 replay 会在文档 purge 后返回过期结果；Collection
  provisioning replay 也需要识别 tombstone。WebUI 当前只列 active Collection，不能假设
  已软删除对象仍有卡片入口。
- 处理：冲突与清理范围改为“目标 Collection 直接 scope 或引用目标文档”的并集；purge
  删除受影响 document idempotency operations，并让 Collection provisioning replay 对
  已退役结果明确失败；WebUI 只承诺 active 卡片入口，soft-deleted Collection 使用管理
  API 按 key 操作。
- 结果：已修订 plan，计数保持 `0`，重新开始三轮检查。

### 第 7 轮：Chat 冻结版本、历史引用回填与控制面创建竞态

- 时间：2026-08-27
- 范围：重新第 1 轮中的 preview/apply 冻结语义、Chat source 字段类型、历史兼容数据、
  sync/repair 创建事务和 embedding worker 提交门。
- 发现：只冻结 Collection `version` 不能发现 preview 后新提交的 Chat 引用，旧 apply
  可能删除预览中未展示的新会话；V56 若只从 `sources` 回填会漏掉历史
  `related_document_ids`；sync run begin 与 repair preview 只读取 active Collection，
  未消费 lifecycle token，可能与 purge fence 并发创建控制面状态。另需明确在途 embedding
  worker 为什么不会在 purge 后回写。
- 处理：preview/apply 同时冻结 `chat_commit_fence_version`，并在 fence 后重构完整删除
  计划校验 fingerprint；规范化引用表从 `sources` 与 `related_document_ids` 并集安全回填；
  sync begin 和 repair preview 增加 Collection version CAS；记录 embedding job/document
  提交门与 purge 两种事务顺序的安全性和验收用例。同步冻结 32 字节 SecureRandom token、
  preview/operation/result/lease/cleanup 默认值，以及 caller-aware capability contract。
- 结果：已修订 plan，计数保持 `0`，从完整方案重新开始三轮检查。

### 第 8 轮：反馈自由文本、Document 审计与独立文件资产边界

- 时间：2026-08-27
- 范围：重新第 1 轮中的全部持久化内容副本、反馈/审计写入路径、检索与评估历史，以及
  `fs_files` 与 RAG 文档的生命周期关系。
- 发现：原规划错误地把 feedback 一并归为无内容历史，但 `rag_user_feedback` 保存
  retrieved/selected document IDs、自由文本 comment 和任意 metadata，可能直接保留文档
  摘录；单文档 audit description 也会保存文档标题。另一方面，`fs_files` 虽保存原 PDF
  和转换 Markdown，却没有 Collection/document 外键，不能通过文件名、source 或路径猜测
  归属后联动删除。
- 处理：V56 增加 feedback-document 规范化引用表并安全回填，后续 feedback 写入同事务
  维护引用；preview/fingerprint/规模上限纳入 feedback、feedback refs 和精确 Document
  audit，apply 删除命中目标文档的完整 feedback 与精确归属审计。明确检索/评估/usage
  历史只保留身份、查询、分数、配置或计数；`fs_files` 属独立文件生命周期，本批次明确
  不做字符串启发式删除并增加验收断言。
- 结果：已修订 plan，计数保持 `0`，从完整方案重新开始三轮检查。

### 第 9 轮：完整文档写路径与 purge 生命周期 fence

- 时间：2026-08-27
- 范围：重新第 1 轮中的 Collection active-write token、local/external/json/PDF/batch
  mutation、scope move/version restore、relocation、clone、sync/repair 和 auth-disabled
  高风险权限。
- 发现：原规划只明确要求 sync begin、repair preview 和 Chat commit 消费 Collection
  fence，不能阻止较早读到 active 状态的普通 local create/update、standalone external
  upsert、PDF-to-RAG、版本恢复或 clone 在 purge fence 后提交正文。另一个“本地开关”
  只有配置开关而没有网络边界，不足以保证 auth-disabled purge 仅供本机开发。
- 处理：把有序 active-write token 扩展到所有会创建、移动、恢复或改写 Collection 文档
  内容的生产入口；scope move/restore 同时消费 source/target，clone 消费 source，purge
  开启时禁止落入 repository 直写兼容分支。auth-disabled 还必须满足直接 Servlet
  remote address 为 loopback，且不信任 forwarded headers。验收增加所有关键写路径与
  purge 的双事务顺序。
- 结果：已修订 plan，计数保持 `0`，从完整方案重新开始三轮检查。

### 第 10 轮：Chat 公共入口与迟到 summary 回写

- 时间：2026-08-27
- 范围：重新第 1 轮中的 `RagChatController`、`RagChatService` 公共重载、
  `ChatExecutionService`、`ChatSessionCoordinator`、Spring AI memory、turn replay 和
  conversation summary 提交顺序。
- 发现：原规划虽然要求 durable Chat commit fence，却没有覆盖字符串/legacy stream
  便利入口的兼容持久化；更关键的是当前 summary compaction 在 history/memory commit 后
  单独执行，而 session lease 已在 commit 中消费，purge 可能先删除会话，迟到 summary
  随后重新写入内容。
- 处理：purge 开启时所有生产 Chat 公共入口必须统一经过 durable coordinator，缺少
  execution/session/history-ref/summary 组件时启动失败而非回退。session lease 延长到本轮
  summary 成功、降级跳过或失败并停止写入后才释放；验收覆盖主提交与 summary 之间的并发
  暂停、字符串/DTO/streaming 入口以及缺 bean 启动失败。
- 结果：已修订 plan，计数保持 `0`，从完整方案重新开始三轮检查。

### 第 11 轮：purge 后 feedback 与审计迟到回写

- 时间：2026-08-27
- 范围：重新第 1 轮中的 feedback 提交事务、document ACL、规范化引用、Controller
  best-effort audit 调用时序和单文档审计内容。
- 发现：仅靠无 FK 的 feedback-document 索引清理历史行，不能阻止客户端在 purge 后用
  陈旧 document IDs 写入新的 comment/metadata；Controller 又是在 document mutation
  事务返回后单独写 audit，purge 可能先完成，再由迟到 audit 保存文档标题。
- 处理：带 document IDs 的 feedback 提交必须重新验证文档存在性、caller ACL 和当前
  Collection，并消费全部 ordered active-write token；purge 与 feedback 两种提交次序均
  fail closed。所有新单文档 audit 永久改为 content-free，不再保存 title/source/filename/
  metadata/payload；purge 删除历史精确 Document audit，迟到新 audit 只可保留身份事实。
- 结果：已修订 plan，计数保持 `0`，从完整方案重新开始三轮检查。

### 第 12 轮：legacy 引用回填的 fail-closed 完整性

- 时间：2026-08-27
- 范围：重新第 1 轮中的 V56 Chat/feedback 引用回填、损坏 JSON/TEXT、正文归因证明和
  purge preview 的安全失败语义。
- 发现：原规划要求损坏、越界或类型错误的 legacy 引用只跳过以保证 migration 成功，
  但若完全不记录索引不完整状态，purge 无法证明这些包含回答、tool transcript 或 comment
  的旧行没有引用目标文档。
- 处理：Chat history 与 feedback 增加 content-reference-index completeness 标记；V56
  对合法数据回填 refs 并标记完成，对无法判定的损坏结构保留 incomplete 而不使 Flyway
  失败。preview 在签发 token 前全局 fail closed，只返回异常分类计数和修复提示；修复或
  删除异常行并重建 refs 后才能继续。
- 结果：已修订 plan，计数保持 `0`，从完整方案重新开始三轮检查。

### 第 13 轮：apply 状态机、结果 replay 与退役 scope 可观察语义

- 时间：2026-08-27
- 范围：重新第 1 轮中的 preview/apply 事务、lease cleanup、capability、显式 Collection
  scope、OpenAI-compatible 路径和 WebUI active-only 列表。
- 发现：原规划没有把成功 replay 限定在结果保留窗口内；把 stale `APPLYING` 直接标记
  `EXPIRED` 又与 operation window 内可重试冲突；“刷新并显示退役状态”不适用于只展示
  active Collection 的 WebUI。另需防止显式选择已退役 Collection 的搜索/Chat 仅返回空
  结果，从而把“拒绝退役 scope”实现成静默降级。
- 处理：明确 24 小时内精确 replay、cleanup 后稳定 409；operation deadline 前 stale lease
  回退 `PREVIEWED`，超时才 `EXPIRED`，正常单事务 apply 失败整体回滚；统一 REST、JSON、
  Chat、OpenAI-compatible 和 service 的显式 retired scope 语义，同时保留未授权防枚举；
  WebUI 成功结果先在 modal/toast 可访问，再刷新并移除 active 卡片。补充
  `TIMESTAMP(6)` 与 `TIMESTAMPTZ` 的字段约定。
- 结果：已修订 plan，计数保持 `0`，从完整方案重新开始三轮检查。

### 第 14 轮：Collection 自身自由文本与审计残留

- 时间：2026-08-27
- 范围：重新第 1 轮的全部 schema/entity 内容字段、Collection tombstone、审计写入路径和
  内容保留边界。
- 发现：原规划保留完整 `rag_collection` 行，但 `name`、`description`、`metadata` 都可能
  保存业务内容；历史 Collection 审计明确会保存 Collection name，Controller 的事务后
  best-effort audit 还会形成 purge 后迟到写入。
- 处理：最终 tombstone 只保留永久 key、ID、技术 profile、版本和时间，name 替换为固定值，
  description/metadata 清空；preview/fingerprint/apply 纳入精确 Collection audit 删除；
  所有未来单 Collection audit 与单文档 audit 一并改为 content-free，并明确 collectionKey
  因永久地址保留而不属于可匿名化字段。
- 结果：已修订 plan，计数保持 `0`，从完整方案重新开始三轮检查。

### 第 15 轮：Collection/document 反向锁序

- 时间：2026-08-27
- 范围：重新并发轮中的 active-write token、purge fence、local/external mutation、
  relocation/restore/clone、sync/repair 和 feedback 事务锁序。
- 发现：原规划要求业务先写 document/控制面行、事务末尾再 confirm Collection CAS，而
  purge 先 CAS Collection 再删除 document；并发时会形成 `document -> collection` 与
  `collection -> document` 的反向锁序，存在 PostgreSQL 死锁风险。
- 处理：将 helper 改为业务首次依赖写入前按 Collection ID 排序执行条件更新并持有事务写
  序，之后才写 document/控制面行，不再末尾二次 confirm；feedback 预占后重验文档，
  clone 在读取/复制 source 前预占。所有生产写路径和 purge 统一为 Collection-first 锁序，
  并补充断点并发验收。
- 结果：已修订 plan，计数保持 `0`，从完整方案重新开始三轮检查。

## 规划收敛结果

- 时间：2026-08-27
- 第 1 轮：需求闭环、数据删除集合、最小 tombstone 与保留边界，无实质问题。
- 第 2 轮：事务、CAS/lease、Collection-first 锁序与并发双顺序，无实质问题。
- 第 3 轮：API、权限、防枚举、capability、WebUI 与验收可实施性，无实质问题。
- 结果：连续三轮未修改规划正文，规划审查达到 `3/3`，允许开始生产实现。

## 实施记录

### 2026-08-28：后端第一切片

- 新增 V56：Collection 退役字段、Chat 提交 fence、Chat/feedback 规范化文档引用、
  引用完整性标记和 durable purge preview。
- 新增 purge 配置、启动校验、DTO、错误码、调用方授权和 preview/apply 服务。
- 新增 Collection purge HTTP 路由、集成操作分类和 caller-aware capability `1.1`。
- purge 功能默认关闭；auth-disabled 模式还要求显式开关和直接 loopback 地址。
- 已确认需要先修复的实现问题：
  - apply fence 后必须使用 preview 冻结版本重建计划，不能把 fence 后版本带入 fingerprint。
  - Document audit 与 Collection audit 必须分别计数。
  - V56 必须在真实 PostgreSQL 上验证，不能以 Java 编译代替 migration 验证。

### 2026-08-28：异步嵌入触发架构核对

- 已确认外部文档使用 `SYNC` 时，请求内直接执行持久化 embedding job，成功响应代表
  当前 embedding profile 的向量已可检索。
- 已确认外部文档使用 `ASYNC` 时，文档、keyword 派生和 durable embedding job 会先落库；
  keyword 检索可立即使用，但向量检索需等待 worker 完成。
- 发现当前 `EmbeddingJobWorker` 仅由默认 `1000ms` 的 `@Scheduled` 轮询触发，没有
  after-commit Spring Event 唤醒；因此 Scheduled 实际是主触发器，不符合项目约定的
  “DB table + Spring Events 模拟 message broker，Scheduled 只兜底”设计。
- 修正要求已加入当前执行计划：
  - durable job 表继续作为唯一可靠事实来源，事件本身不承载不可恢复的业务状态；
  - 创建或合并 `ASYNC` job 的事务成功提交后发布轻量 Spring Event；
  - 监听器使用有界、可合并的异步唤醒机制驱动现有 lease worker，避免每个事件创建线程、
    无界排队或并发超额；
  - 回滚事务不得唤醒，重复事件、丢失事件和多实例并发必须保持安全；
  - Scheduled 改为可配置的低频恢复扫描，默认目标为 `30s`，只处理遗漏通知、实例重启和
    worker 异常恢复，不再承担正常低延迟路径；
  - 验收覆盖提交后近实时向量可检索、事务回滚不消费、通知丢失后定时恢复、空闲期低频
    扫描和多实例 lease 互斥。

### 2026-08-28：Chat 引用索引与提交围栏

- durable Chat commit 会规范化 `sources[*].documentId` 与
  `relatedDocumentIds` 的数字文档 ID 并集；合法静态知识 ID 不会被误判。
- commit 在写 history、Spring AI memory 和 turn replay 前，按 Collection ID 排序消费
  `chat_commit_fence_version`，随后重新验证文档仍属于 active、未退役 Collection。
- history、规范化引用和 `content_reference_index_complete=true` 在同一事务内提交；
  任一引用失效会回滚整次 history/memory 写入。
- 成功 commit 不再提前消费 session lease；lease 会续期并保持到 summary compaction
  完成或停止写入，最后由外层统一释放。
- V56 升级兼容修复：V32 刻意保留的非法 legacy `session_id` 行不会被 V56 更新，
  原行继续存在且 `content_reference_index_complete=false`，因此 migration 可升级且
  purge 保持 fail closed。
- 验证结果：
  - Chat 聚焦 Mock 测试：`46/46` 通过。
  - 隔离 PostgreSQL Chat 集成套件：`18/18` 通过。
  - 覆盖 V31→V56 升级、全量迁移、引用并集、Collection fence、无效引用回滚、
    history/memory 原子性和 summary 前 lease 生命周期。

### 2026-08-28：Feedback 引用索引、ACL 与 content-free audit

- 新 feedback 对 retrieved/selected document ID 分别去重排序，并以两者并集维护
  `rag_user_feedback_document`。
- 带引用的提交先校验文档存在、启用、已归属 Collection 和 caller ACL，再按 Collection
  ID 排序预占 active-write version；预占后重新读取文档归属，发生迁移或状态变化时整次
  提交回滚。
- feedback 行、`content_reference_index_complete=true` 和规范化 refs 在同一事务提交；
  无引用 feedback 直接标记完整，不产生空引用写入。
- 单次 feedback 的每类文档 ID 列表限制为 1000 项；非法、缺失、禁用、未归属、越权或
  已退役引用均 fail closed。
- `AuditLogService` 对所有 Document/Collection 审计统一使用固定 description，只保留
  数值或布尔技术事实，字符串 title/source/filename/name/metadata/payload 不会写入。
  该边界同时保护 mutation 提交后的迟到 best-effort audit。
- 验证结果：
  - Maven source compile：通过。
  - Feedback 与 audit 聚焦 Mock 测试：`46/46` 通过。
  - preview INSERT 的列、占位符和参数数量必须由集成测试确认。
- 后续仍需完成所有生产写路径的 Collection-first reservation、Chat/feedback 引用提交、
  退役 scope 拒绝、恢复/幂等 replay 边界、后端集成测试和 WebUI。

### 2026-08-28：Collection purge PostgreSQL 矩阵与退役边界收敛

- 新增真实 PostgreSQL purge 集成矩阵，覆盖空/非空 Collection 的 preview、apply、
  成功 replay、内容与派生数据删除、无关数据保留、独立文件保留、active sync/repair/
  Chat lease 阻断、引用索引 fail closed、owner/角色/loopback 权限和结果过期清理。
- completed preview 的 replay 现在仍会校验原始 Collection key、confirmation token、
  fingerprint、Collection version 与 Chat commit fence version；只有精确相同的成功请求
  才返回持久化结果，修改任一冻结字段都会稳定失败。
- 显式数字 Collection scope 不再把未知或退役 ID 当作 zero-hit scope；已授权调用方会
  得到 `COLLECTION_ALREADY_RETIRED`，OpenAI-compatible HTTP 路径保持 OpenAI error
  envelope。
- Collection 详情、文档列表、add-document、导出以及 service 级 delete/restore/clone
  对已退役 tombstone 返回明确 409；普通缺失或普通软删除仍保持既有 404/empty 语义。
- 已更新 Collection-first CAS 后的旧单测期望：active-write token 返回推进后的版本，
  delete/clone 测试显式模拟版本预占。
- 验证结果：
  - 固定范围 resolver/service/controller/OpenAI Mock 测试：通过。
  - `CollectionPurgePostgresIntegrationTest`：`5/5` 通过。
  - 每个 PostgreSQL 场景均从空 schema 执行 V1-V56 全量迁移。

### 后端 PostgreSQL purge 端到端验收

- 新增 `CollectionPurgePostgresIntegrationTest`，使用可清理的独立
  `pgvector/pgvector:pg16` 数据库，从空库执行 V1-V56 全量迁移，并接入真实
  JPA `RagCollectionRepository`、`JpaTransactionManager` 和 JDBC purge service。
- 一次性验收矩阵覆盖：
  - 空 Collection 的 preview、apply、最小 tombstone 和结果精确 replay；
  - local/external 混合文档及 embedding、job、version、keyword chunk、local/vector
    state 的完整清理；
  - 引用目标文档的 Feedback、Chat history、Spring AI memory、summary 和 turn replay
    整体删除，同时保留无关 Collection/文档/会话/反馈；
  - completed sync run、sync item、relocation address fence 和独立 `fs_files` 的保留边界，
    以及相关 `document_id`/operation FK 按约束置空；
  - 历史 Document/Collection 内容审计删除，新 purge 审计仅保存技术事实；
  - 全局 incomplete Chat/Feedback 引用、active sync、active repair 和有效 Chat session
    lease 的 fail-closed 阻断；
  - preview 后删除计划变化时 apply 整体回滚，不留下 Collection fence 或部分删除；
  - environment root、数据库 ADMIN/NORMAL、auth-disabled direct-loopback 和 forwarded
    address 不受信任边界；
  - 完成结果超过保留窗口并 cleanup 后返回稳定 preview unavailable/expired。
- 验证结果：`CollectionPurgePostgresIntegrationTest` **5/5 通过**。
- Maven reactor `compile test-compile` 通过；单独编译 `core` 会误用本地旧 `api`
  artifact，因此本任务所有 Maven 门槛固定使用 `-am`。
- 下一步：补齐 purge HTTP/RFC 7807 契约和所有显式 Collection scope 的 retired
  语义测试，再进入 WebUI preview-confirm-apply。

## 验证记录

- `mvn -pl spring-ai-rag-core -am -DskipTests compile` 已执行两次，均通过。
- `./scripts/verify-embedding-jobs.sh` 已通过：
  - 聚焦 service/worker/HTTP 测试 26/26；
  - 真实 PostgreSQL V1–V56 migration 与 embedding job 集成测试 8/8；
  - 新增用例证明事务提交后由 Spring Event 唤醒 worker，在未调用恢复 `poll()` 的情况下
    作业进入 `SUCCEEDED`；
  - Maven `test-compile`、脚本语法和 `git diff --check` 同步通过。
- 首次 PostgreSQL 门禁发现并修复：
  - V56 JSON 数组展开查询中的 `value` 列歧义；
  - 旧集成夹具缺少 V40/V41 后必须的 `document_kind`、`chunker_version`、
    `request_generation` 和状态行；
  - 新事件集成夹具误用不存在的 profile `active` 列，已改为 `enabled`。
- 事件驱动切片的配置、架构与双语长青文档已更新；Scheduled 恢复扫描默认 `30s`，
  配置下限 `10s`。

### 2026-08-28：Chat 引用与会话 fence 实施基线

- 已逐一核对普通 JSON Chat、普通 SSE、原生 keyed JSON/SSE 和 OpenAI-compatible
  keyed JSON/SSE 的提交与最终释放顺序。
- 当前 durable commit 在保存 history/memory 前消费并删除 session lease，而 conversation
  summary 在事务提交后才执行；这会允许 purge 在两者之间删除会话，随后由迟到 summary
  重新写入内容。
- 当前 `rag_chat_history` writer 尚未映射
  `content_reference_index_complete`，也没有在同一事务中维护
  `rag_chat_history_source_document`。本切片冻结以下实现顺序：
  1. 从 `sources[*].documentId` 和 `relatedDocumentIds` 解析、去重正整数文档 ID；
  2. 读取并验证文档及其 active Collection；
  3. 按 Collection ID 排序消费 Collection-first active-write token；
  4. 重新验证文档仍存在、enabled 且所属 Collection 未删除/未退役；
  5. 保存 history、规范化引用并最后标记完整性为 true；
  6. 成功提交只校验并延长 session lease，不删除 lease；摘要成功、降级跳过或失败停止
     写入后，由现有外层 `finally` token-fenced 释放。
- 合法的非数字静态知识 source ID 不进入数据库引用索引；缺失、disabled、未分配或属于
  已删除/已退役 Collection 的数字文档引用会拒绝整次 durable 提交，不允许部分 history、
  memory 或 replay 写入。
- Collection purge 的完整 PostgreSQL migration、任务矩阵、Maven clean 门槛、前端门槛
  和真实依赖验收仍待完成。

### 2026-08-27：WebUI preview-confirm-apply

- Collection 页面仅在已解锁身份为 `ENVIRONMENT_ROOT`，且 caller-aware
  `/integration-capabilities` 返回 `features.optional.collectionPurge=true` 时显示
  “永久清理”入口；数据库 ADMIN 和 auth-disabled API 能力不会扩大管理控制台入口。
- 点击入口后立即创建 body-free preview；confirmation token 只保存在 React 内存状态，
  不显示在 DOM、日志或确认输入中。
- dialog 展示文档、embedding、版本、关键词、反馈、会话和审计删除计数；只有输入完整、
  区分大小写的 `collectionKey` 后才允许 apply。
- apply 请求完整携带 preview ID、token、fingerprint、Collection version 和 Chat fence
  version；pending 时禁止重复提交，409/过期等 RFC 7807 detail 保持可见且不会自动重试。
- 成功后 dialog 保留最小退役结果和最终版本，同时失效 active Collection query，使已退役
  Collection 卡片从列表移除。
- 定向前端验证：
  - `npm run typecheck`：通过；
  - Collection API/页面 Vitest：`7/7` 通过；
  - `npm run check:alignment`：通过；
  - 前端相关 `git diff --check`：通过。
- 下一步：启动 Mock WebUI 服务执行 Collection purge Playwright 主路径和冲突路径，然后
  补齐双语长青文档与完整前后端门槛。

### 2026-08-27：WebUI 浏览器验收与长青文档

- Collection Mock Playwright `3/3` 通过：
  - Collection create 的 Axios retry 幂等回归；
  - preview、计数、token 不渲染、精确 key 确认、完整 apply JSON、成功结果保留和
    active-only 卡片移除；
  - apply `409` detail 可见、按钮恢复且不会自动重复提交。
- `npm run lint`、`npm run build` 通过；浏览器证据只使用 DOM、可访问状态、请求 JSON 和
  网络响应，Playwright 配置保持 `screenshot: off`。
- 新增 `scripts/verify-collection-purge.sh`，固定 9 阶段专项门禁并将逐步证据写入
  `.verification/collection-purge/<run-id>/summary.md`；支持 Testcontainers 或显式一次性
  PostgreSQL，要求 5 个数据库场景全部执行且零 skip。
- 双语长青文档已同步：
  - capability protocol `1.1`、caller-aware purge flag 与同步上限；
  - preview/apply、权限、内容/引用删除集合、永久 key tombstone 和独立文件边界；
  - 配置范围、V56 schema、Collection-first 并发顺序、测试命令与滚动升级约束；
  - V55/V56 混部期间必须保持 purge 关闭，完成任一 purge 后不得让 V55 binary 承担
    数据面流量。
- `verify-project-docs.sh`：`11/11` 通过；链接、双语结构、Flyway V56、脚本、密钥扫描和
  空白检查均通过。
- 下一步：执行 `verify-collection-purge.sh` 完整 Mock/数据库/Maven/WebUI 硬门槛，再进入
  隔离服务的真实 LLM/Embedding 生命周期验收。

### 2026-08-27：合并前完整 Mock/数据库硬门槛

- 使用一次性 PostgreSQL 16/pgvector 数据库执行：
  `COLLECTION_PURGE_VERIFY_RUN_ID=pre-real-20260827-r1
  ./scripts/verify-collection-purge.sh`。
- 专项门禁 **9/9 通过**，证据摘要位于
  `.verification/collection-purge/pre-real-20260827-r1/summary.md`：
  - 禁止悲观锁门禁通过；
  - purge 聚焦后端测试 **145/145** 通过；
  - PostgreSQL V1-V56 purge 验收 **5/5** 通过；
  - `mvn clean compile test-compile` 通过；
  - WebUI TypeScript typecheck 通过；
  - WebUI Vitest **233/233** 通过；
  - ESLint、alignment policy 与生产构建通过；
  - Collection Mock Playwright **3/3** 通过；
  - 项目文档门禁 **11/11**、shell 语法和 `git diff --check` 通过。
- 下一步：在隔离端口和隔离数据库启动真实 provider 服务，完成“创建 Collection → 写入
  真实文档 → 真实 embedding 就绪 → 自然语言检索/Chat 命中 → preview/apply purge →
  显式退役 scope 拒绝 → 默认 scope 排除 → 数据库只读事实核对”的完整生命周期。

### 2026-08-27：真实 provider 生命周期与 observability 兼容修复

- 新增 `scripts/real-collection-purge-e2e-smoke.sh`，HTTP 响应只在内存中解析；持久化
  `summary.json` 只保留状态、模型、回答长度、布尔断言、Collection/document/job ID 和
  来源 ID。请求正文、完整 LLM 回答、文档正文和明文 confirmation token 不落证据目录。
- 使用隔离 PostgreSQL、MiniMax Chat 与 SiliconFlow BGE-M3 完成完整生命周期 **11/11**：
  - 外部 ASYNC upsert 后，Spring Event 在 60 秒 Scheduled 恢复扫描前启动 worker；
  - 真实 embedding、readiness 与纯向量自然语言检索通过；
  - 两轮原生真实 Chat 和一轮 OpenAI-compatible Chat 命中目标事实；
  - preview/apply、精确 replay、Search/Chat/OpenAI 退役拒绝、默认范围排除和最小
    tombstone 数据库事实全部通过。
- 日志观察发现 purge observation 批次因 `DataIntegrityViolationException` 被丢弃。
  根因是 V56 新增 `COLLECTION_PURGE_PREVIEW/APPLY` Java 枚举和路由时，未扩展 V54 两张
  rollup 表的 operation `CHECK` 约束。
- V56 现同步替换两张 operation 约束；`IntegrationObservabilityPostgresIntegrationTest`
  从空库运行 V1-V56，新增两种 purge operation 的全局与 Collection rollup 写入断言，
  同时保留未知 operation 拒绝。隔离 PostgreSQL 验证 **6/6** 通过，路由聚焦测试
  **41/41** 通过。
- `verify-collection-purge.sh` 已把该 6 场景兼容矩阵纳入 PostgreSQL 硬门槛。下一步重跑
  完整专项门禁，并重建隔离真实服务确认日志不再出现 observation drop。

### 2026-08-28：真实生命周期复验发现 Collection 观测归因缺口

- 修复 operation `CHECK` 约束后，隔离服务再次完成真实 embedding、两轮原生 Chat、
  OpenAI-compatible Chat、purge 和退役拒绝路径；全局 purge rollup 正常写入，后端日志
  不再出现 observation batch drop。
- 只读数据库核对发现 Collection 级 purge rollup 仍为 `0`。根因不是 repository 或
  migration，而是 purge 服务直接读取 Collection/preview，没有像常规
  `CollectionIdentityResolver` 路径一样，把已完成存在性、owner 与管理员权限校验的
  Collection ID 加入当前 HTTP 请求的 `IntegrationObservationContext`。
- 同时发现 macOS 自带 Bash 3.2 不会因裸 `(( false ))` 配合 `set -e` 稳定退出，导致真实
  smoke 在四项 rollup 断言为假时仍可能打印 PASS。
- 处理决策：
  - preview 在权限校验和 Collection lookup 后显式记录 Collection ID；
  - apply 在 owner-scoped preview 加载后显式记录 Collection ID，覆盖首次 apply 与成功
    replay；
  - PostgreSQL 服务级集成测试直接断言两个独立 HTTP request 的观测上下文；
  - smoke 使用显式条件和错误消息校验四项正整数，不再依赖 arithmetic command 的
    `errexit` 行为。
- 上一份 `real-provider-20260828-r7` 证据因 Collection 计数为零判定为无效，不作为最终
  验收结论。修复后必须重新跑相关 Mock/PostgreSQL 门槛和完整真实生命周期。

### 2026-08-28：Collection 观测归因修复后的完整硬门槛

- purge preview 在权限校验和 Collection lookup 后、apply 在 owner-scoped preview
  加载后，将 Collection ID 显式加入当前请求的观测上下文；首次 apply、成功 replay 和
  已识别 tombstone 的 preview 拒绝都可按 Collection 归因。
- `CollectionPurgePostgresIntegrationTest` 使用独立 Mock HTTP request 验证 preview、
  apply 与 replay 的授权 Collection ID，避免只测试 rollup repository 而漏掉 HTTP
  上下文生产路径。
- 真实 smoke 的四项全局/Collection rollup 改为显式正整数校验；不再依赖 Bash 3.2 对
  arithmetic command 的 `set -e` 行为。
- Testcontainers 首次尝试因 Docker registry TLS 代理证书错误无法拉取 Ryuk，未进入业务
  测试；随后改用已运行的隔离 pgvector/PostgreSQL 容器创建一次性数据库，重跑相同门槛。
- 修复后专项门禁 **9/9** 通过，证据位于
  `.verification/collection-purge/post-collection-attribution-fix-20260828-r2/summary.md`：
  - 禁止悲观锁门禁通过；
  - purge 聚焦后端测试 **186/186**；
  - PostgreSQL purge **5/5** 与 observability V56 兼容矩阵 **6/6**，合计 **11/11**；
  - `mvn clean compile test-compile` 通过；
  - WebUI typecheck、Vitest **233/233**、lint、alignment 与生产构建通过；
  - Collection Mock Playwright **3/3**；
  - 项目文档门禁 **11/11**、shell 语法和 `git diff --check` 通过。
- 下一步：重启隔离真实服务以加载修复后的 classes，重新执行完整真实
  LLM/Embedding/purge 生命周期，并要求四项观测计数全部为正。

### 2026-08-28：修复后的真实 LLM/Embedding 生命周期 12/12

- 使用隔离端口 `18087/15187`、隔离 PostgreSQL 数据库、MiniMax Chat 与 SiliconFlow
  BGE-M3 重启服务，确认运行的是归因修复后的 classes。
- `real-provider-20260828-r8` 完整生命周期 **12/12** 通过：
  - environment root identity、异步 embedding 与 purge capability 可见；
  - 外部 ASYNC upsert 后，Spring Event 在 60 秒恢复扫描前启动 durable worker；
  - 真实 embedding 收敛为 fresh，纯向量自然语言检索只命中目标文档；
  - 两轮原生真实 Chat 保持会话和 durable citation，OpenAI-compatible Chat 命中目标事实；
  - preview、apply、精确 replay、三条显式退役路径 `409`、默认范围排除和最小 tombstone
    全部通过；
  - 全局 preview/apply rollup 为 `2/4`，本次 Collection preview/apply rollup 为 `1/2`。
- 后端日志无 observation drop、`DataIntegrityViolationException`、SQL 异常、ERROR 级日志
  或 Provider 异常；预期的两条 `COLLECTION_ALREADY_RETIRED` 业务警告与验收断言一致。
- `summary.json` 脱敏扫描通过，不包含 confirmation token、测试文档正文、完整回答、
  `chunkText` 或 API key。
- 合并前真实 Provider 验收完成；下一步执行文档/脚本/diff/密钥门禁，随后同步最新
  `origin/main` 并从合并后基线完整复验。

### 2026-08-28：合并前保护检查点

- 合并前完整专项门槛的有效证据为
  `.verification/collection-purge/post-collection-attribution-fix-20260828-r2/summary.md`：
  **9/9** 阶段通过，聚焦后端 **186/186**、PostgreSQL **11/11**、WebUI Vitest
  **233/233**、Collection Mock Playwright **3/3**，并通过 Maven clean
  compile/test-compile、前端 typecheck/lint/alignment/build、文档、shell、锁策略与
  whitespace 门禁。
- 合并前真实 Provider 的有效脱敏证据为
  `.verification/collection-purge/real-provider-20260828-r8/summary.json`：
  **12/12** 通过，确认真实 Chat/Embedding、Spring Event 在 60 秒恢复扫描前唤醒 worker、
  向量检索、两轮原生 Chat、OpenAI-compatible Chat、purge/replay/退役拒绝/最小
  tombstone，以及全局和 Collection 级 purge 观测计数均为正。
- `real-provider-20260828-r7` 因未发现 Collection 级观测归因缺口而判定无效，任何交付
  结论都不得引用该轮证据。
- 文档门禁、锁策略、diff、密钥扫描和仓库内客户特定名称扫描均已通过。当前全部实现和
  文档修改将在本地保护提交后再 fetch/merge 最新 `origin/main`。
- 合并后的结果必须视为新基线：使用新的一次性 PostgreSQL 数据库重跑 9 阶段门槛，
  重启隔离真实服务并重新执行完整真实 Provider 生命周期；不得沿用合并前结果作为最终
  交付结论。

## 恢复入口

1. 阅读 plan 的第 2、3、4 节，确认当前代码事实和冻结契约。
2. 阅读本文件的最新进度和审查计数。
3. 先完成适用的基本硬门槛，再执行实现三轮固定范围检查。
