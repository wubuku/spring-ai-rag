# Collection 受保护清理与退役实施进度

> 对应规划：[COLLECTION_PURGE_AND_RETIREMENT_PLAN.md](COLLECTION_PURGE_AND_RETIREMENT_PLAN.md)
> 工作区：`/Users/yangjiefeng/Documents/wubuku/spring-ai-rag`
> 当前分支：`main`
> 基线：`90ee276c`（2026-08-27）

## 状态

- 当前阶段：规划完成，准备实施
- 规划审查计数：`3/3`
- 实现审查计数：尚未开始
- 工作区：开始任务时干净，未创建 worktree，未使用 stash

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

尚未开始。规划达到 `3/3` 前不得修改生产代码。

## 验证记录

尚未开始。

## 恢复入口

1. 阅读 plan 的第 2、3、4 节，确认当前代码事实和冻结契约。
2. 阅读本文件的最新进度和审查计数。
3. 先完成适用的基本硬门槛，再执行实现三轮固定范围检查。
