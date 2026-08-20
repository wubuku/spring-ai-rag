# 下一批高价值功能规划进度

> 对应规划：[2026-08-21_NEXT_HIGH_VALUE_FEATURES_PLAN.md](2026-08-21_NEXT_HIGH_VALUE_FEATURES_PLAN.md)
>
> 当前阶段只完成规划，不实施业务功能。本文记录调研、规划检查和后续恢复上下文。

## 1. 当前目标

规划并在后续实施：

1. P0 外部文档跨 Collection 原子迁移；
2. P1 Collection 派生索引完整性诊断与 preview-first 修复。

硬约束：

- 外部地址继续是 `collectionKey + sourceNamespace + externalId`；
- 首版 relocation 只改变 Collection；
- 保留 document ID、版本历史和派生数据，不重新 chunk/embed；
- 双边 ACL、Collection 生命周期 CAS、Sync Run sequence fencing；
- 禁止悲观锁；
- P1 修复复用现有 local/vector 正式业务路径；
- 前端验收不使用截图。

## 2. 规划调研记录

### 2026-08-20

- 确认当前 Flyway 为 V1–V43；V42 已交付权威 Sync Run，V43 已交付本地关键词与远程
  向量解耦。
- 确认普通外部 upsert 修改 `collectionKey` 会寻址另一身份，无法保留同一
  `documentId` 和连续历史。
- 确认派生表均以 `document_id` 关联，Collection-only 迁移不需要重建 local chunks、
  vectors、states 或 jobs。
- 确认现有幂等账本只保存 `result_document_id/result_batch_id`，不足以在文档之后再次
  变化时精确重放原 relocation 响应；规划扩展受控 `result_payload JSONB`。
- 确认 Sync Run missing/protected 语义依赖 namespace mutation sequence；首版 relocation
  拒绝源/目标未过期 active run，并通过同一 namespace scope 的条件 DML 处理 begin 竞态。
- 确认现有 Collection embedding readiness 不验证 V43 local chunk 物理完整性，且只表达
  活动 Profile 向量分支；规划新增独立 derivation readiness。
- 确认 `KeywordIndexPersistenceService.ensureCurrent` 和
  `EmbeddingDispatchService` 可作为 repair 的正式入口，不应直接修改状态表伪造 READY。
- 修复 `.agents/skills/project-docs/SKILL.md` frontmatter，并增强
  `scripts/verify-project-docs.sh` 对项目 Skill 的 BOM/frontmatter/必填字段校验。
- 修正 project-docs Skill 中 Flyway V1–V41 的过时审计提示为 V1–V43。

## 3. 状态

| 阶段 | 状态 | 结果 |
|---|---|---|
| Skill frontmatter 修复 | 完成 | 两个项目 Skill 均通过 `quick_validate.py` |
| 当前能力与缺口调研 | 完成 | 主线收敛为 relocation + derivation integrity |
| 自包含规划 | 完成 | API/schema/事务/Sync Run/ACL/WebUI/验收均已给出默认决策 |
| 规划连续三轮检查 | 进行中 | 已多次发现并修正实质问题；当前计数为 0 |
| 文档门禁 | 待执行 | `verify-project-docs.sh`、Skill validator、`git diff --check` |
| commit / merge / push | 待执行 | 本轮只提交规划和 Skill/门禁修复 |

## 4. 规划检查日志

仅记录发现问题并发生修改的轮次；连续无问题轮次在执行总结中记录，不修改本文，以免破坏
“连续三轮无修改”的终止条件。

### 2026-08-20 15:33 CST：检查计数重置为 0

- 检查范围：P0 API/source revision、V44 幂等 schema、Collection/Sync Run 并发、P1
  readiness 物理不变量和 repair ledger。
- 发现问题：
  1. 仅 placement 变化不应要求 client 伪造新 `sourceRevision`；
  2. 现有幂等 FK 会在文档 hard-delete 后清空 `result_document_id`，不能把它作为历史
     response replay 的必备条件，且 replay 需要重新应用当前 Collection ACL；
  3. 只分配源 sequence 不能阻止迁移前已开始的源 Sync Run 在旧地址重新创建文档；
  4. `rag_embeddings` 不含 content hash/chunker/generation，规划的不变量表述错误；
  5. repair 停在无租约的 `APPLYING` 无法在进程崩溃后可靠恢复。
- 处理措施：relocation 保留 source revision；幂等账本增加不可变响应和授权 Collection
  IDs；首版拒绝源/目标 active Sync Run 并覆盖 begin 竞态；修正向量不变量；为 repair
  preview/items 增加 apply/item lease、接管与稳定终态重放语义。
- 结果：规划已修正，连续无问题检查从第 1 轮重新开始。

### 2026-08-20 15:38 CST：检查计数重置为 0

- 检查范围：规划检查第 1 轮，聚焦 schema 版本、relocation/Sync Run 条件 DML 顺序和
  migration 可执行性。
- 发现问题：
  1. P0 明确占用 V44 后，P1 仍写成“V44 可同时创建”，会诱导修改已执行迁移；
  2. relocation 与 Sync Run begin 竞态只写“最后检查”，没有说明如何在无悲观锁条件下
     建立可证明的顺序。
- 处理措施：P1 固定使用 V45；明确双方通过 V40 namespace scope 行的
  `UPDATE ... RETURNING` 分配 sequence 形成条件 DML 顺序，并在分配后复查 V42 ACTIVE
  run。
- 结果：规划已修正，连续无问题检查再次从第 1 轮开始。

### 2026-08-20 15:40 CST：检查计数重置为 0

- 检查范围：P0 run 生命周期阻塞边界、P1 bucket 互斥语义、repair 中断恢复契约。
- 发现问题：
  1. 数据库中 lease 已过期但尚未惰性转为 EXPIRED 的 run 会被“ACTIVE”检查永久阻塞；
  2. 原 bucket 同时混合可检索性和故障原因，vector FAILED/INDEXING 且 local READY 的文档
     可能被错误归类，且示例字段与互斥总和矛盾；
  3. durable repair ledger 没有公开状态查询 endpoint，HTTP 中断后 client 无法恢复；
  4. WebUI 仍要求输入新 source revision，与 relocation 保留来源 revision 的决策冲突。
- 处理措施：迁移前条件过期 run；bucket 以当前检索能力互斥分类，将 vector repair 改为
  非互斥诊断维度；增加受 ACL 保护的 repair operation 查询；修正 relocation 表单。
- 结果：规划已修正，连续无问题检查重新从第 1 轮开始。

### 2026-08-20 15:44 CST：检查计数重置为 0

- 检查范围：P1 API 可寻址性、现有 local freshness 实现、复合 repair 事务边界和 bucket
  示例算术。
- 发现问题：
  1. preview/apply 未返回和携带 `repairId`，无法可靠寻址 durable operation；
  2. `KeywordIndexPersistenceService.ensureCurrent` 只核对物理 count，面对“count 相同但
     chunk index 不连续”等损坏会 early-return，不能完成规划承诺的 repair；
  3. local/vector 同一事务会让 vector 排队失败回滚已成功的 local 修复；
  4. readiness 示例的互斥 bucket 数字没有加总到 enabled documents。
- 处理措施：API 增加 repairId 和 owner/ACL/token 联合校验；新增复用现有持久化逻辑的
  force `rebuildCurrent`；ledger 分开记录并提交 local/vector 子动作；修正摘要示例和
  preview filter。
- 结果：规划已修正，连续无问题检查重新从第 1 轮开始。

### 2026-08-20 15:48 CST：检查计数重置为 0

- 检查范围：P1 enabled 文档分类的完备性与面向运营者的命名。
- 发现问题：原 `FAILED_LOCAL` 只覆盖显式失败，无法稳定容纳 local state 缺失、stale
  或“vector 当前但 local 不可用”等合法数据库状态，互斥分类仍可能漏项。
- 处理措施：固定六个互斥 bucket：
  `CORRUPT/READY/KEYWORD_ONLY/INDEXING/NOT_REQUESTED/LOCAL_UNAVAILABLE`；
  local/vector 具体原因作为正交 condition 和推荐动作返回。
- 结果：分类已完备，连续无问题检查重新从第 1 轮开始。

### 2026-08-20 15:52 CST：检查计数重置为 0

- 检查范围：P0 JDBC/JPA 快照一致性，以及 P1 多子动作 repair 的 preview fingerprint
  与崩溃恢复。
- 发现问题：
  1. relocation 条件 JDBC 更新后若直接使用旧 persistence-context entity，可能写出源
     Collection 的错误 FULL snapshot/lifecycle；
  2. local rebuild 会合法改变 generation，恢复 vector 子动作时若重新比对原 preview
     fingerprint，会把自身变化误判为并发 mutation。
- 处理措施：迁移后强制 refresh/reload entity；repair item ledger 分别保存业务前态、
  local/vector 前态和 post-local 状态，恢复时接受本 operation 已记录的变化，只拒绝外部
  变化。
- 结果：阻断性设计空洞已补齐，连续无问题检查从第 1 轮重新开始。

### 2026-08-20 15:50 CST：检查计数重置为 0

- 检查范围：正式第 1 轮，API、schema、幂等、迁移与 durable repair 可执行性。
- 发现问题：
  1. 反向 relocation 的回滚说明仍要求新 source revision，与纯 placement 语义冲突；
  2. repair fingerprint/table 绑定 `RagCollection.version`，而正常
     `ActiveCollectionToken` 会推进该 version，批次会自我失效；
  3. item claim 描述了 lease，但 item schema 未列出 lease/attempt 字段；
  4. local rebuild 失败后 vector 子动作的依赖终态未定义。
- 处理措施：反向迁移保持 source revision；repair 只绑定 Collection ID/key，并在每个
  短事务重新取得 lifecycle token；补齐 item lease/attempt；local 失败时 vector 标记
  `LOCAL_REPAIR_FAILED`。
- 结果：规划已修正，连续无问题检查计数归零。

### 2026-08-20 15:53 CST：检查计数重置为 0

- 检查范围：重新执行第 1 轮，重点交叉验证 relocation 对 active embedding worker 的
  version fencing 影响，以及 vector READY 的物理证明。
- 发现问题：`rag_embeddings` 不存 content hash/chunker/generation，只验证 Profile、
  count、index 和非空向量，无法排除旧向量文本与当前分块数量恰好相同的损坏状态。
- 处理措施：vector 完整性必须按 chunk index 与当前 local generation 的
  `rag_document_chunks` 对齐 chunk text 和 position；专项测试增加“数量相同但内容旧”的
  fixture。
- 结果：active job 可保留的结论经代码确认；vector 不变量已补齐，连续检查计数归零。

### 2026-08-20 15:57 CST：检查计数重置为 0

- 检查范围：第 1 轮继续核对幂等响应持久化与滚动升级兼容。
- 发现问题：若 `result_payload` 直接序列化当前 DTO，后续 response 字段演进可能让新代码
  无法在 TTL 内读取旧成功结果，或错误地按当前文档重建响应。
- 处理措施：定义带 `schemaVersion` 的 response envelope；reader 必须在 TTL + 滚动发布
  窗口内保留旧版本兼容，并直接重放存储语义。
- 结果：规划已修正，连续无问题检查计数归零。

### 2026-08-20 15:58 CST：检查计数重置为 0

- 检查范围：第 1 轮继续核对 legacy external identity 和物理向量损坏的实际 repair
  路径。
- 发现问题：
  1. 旧数据可能 `sourceRevision IS NULL`，relocation 的 expected revision 契约没有给出
     可操作结果；
  2. `DocumentEmbedService.hasFreshEmbedding` 当前只核对向量 count；物理内容损坏但 count
     相同时，普通 `force=true` 会 preserve COMPLETED，使坏向量在 repair 排队期间继续
     可检索。
- 处理措施：legacy identity 先通过普通 upsert claim 非空 revision；新增复用现有
  generation/job 状态机但强制 `preserveCompleted=false` 的 corrupt-vector repair enqueue，
  repair 接受后立即让坏向量退出 freshness。
- 结果：规划已修正，连续无问题检查计数归零。

### 2026-08-20 16:00 CST：检查计数重置为 0

- 检查范围：第 1 轮继续验证 relocation 完成后的增量 CRUD、batch 和后续 Sync Run。
- 发现问题：只移动 `rag_documents` 行后，延迟 webhook/CDC 或新的旧 placement snapshot
  仍会在旧三元地址重新创建第二个文档，所谓“原子迁移”只对单次事务成立，不对外部同步
  生命周期成立。
- 处理措施：V44 增加永久 active retired-address ledger；所有外部地址入口在 create/404
  前检查并返回 `EXTERNAL_IDENTITY_RELOCATED`；同文档反向 relocation 原子 resolve 目标
  marker 并退休当前源地址；不自动 TTL 清理。
- 结果：外部 CRUD 生命周期缺口已补齐，连续无问题检查计数归零。

### 2026-08-20 16:03 CST：检查计数重置为 0

- 检查范围：retired-address guard 与普通 external mutation 的事务交错，以及连续多次
  relocation 的地址解析。
- 发现问题：
  1. 仅在 mutation 开始时检查 marker 仍有 check-then-act 竞态，旧地址 upsert 可能在
     relocation marker 提交前读到空；
  2. A→B→C 若只保留逐跳 target，会形成 redirect chain，错误响应和反向迁移语义复杂化。
- 处理措施：所有外部 mutation 在 namespace sequence 顺序点后、写入前复查 marker；
  每次 relocation 将同一 document 的全部 active marker 扁平更新到当前目标。
- 结果：地址并发和多跳迁移语义已补齐，连续无问题检查计数归零。

### 2026-08-20 16:07 CST：检查计数重置为 0

- 检查范围：P1 新诊断与现有 lifecycle、embedding cache、旧 Collection readiness 的
  一致性。
- 发现问题：若只新增严格 diagnostics，现有 `DocumentLifecycleService`、
  `hasFreshEmbedding` 和 `/embedding-readiness` 仍可能把“行数相同但物理内容损坏”的
  向量判为 READY/fresh，形成多个冲突真相源。
- 处理措施：新增只读共享 `DerivationIntegrityRepository`，让单文档 lifecycle/cache、
  旧 readiness 和新 diagnostics 使用同一 local/vector 物理不变量。
- 结果：P1 从孤立诊断页收敛为统一 freshness 真相源，连续无问题检查计数归零。

### 2026-08-20 16:10 CST：检查计数重置为 0

- 检查范围：retired-address ledger 与现有 local hard-delete、Collection soft-delete
  生命周期。
- 发现问题：marker 的 Collection FK 删除策略和未来 external purge 边界未明确，错误的
  CASCADE 或放宽通用 hard-delete 都会静默丢失历史地址保护。
- 处理措施：Collection FK 使用 RESTRICT/NO ACTION；保持 external document 禁止 local
  hard-delete；未来 purge 必须专用事务删除主记录及全部 markers。
- 结果：删除生命周期已闭合，连续无问题检查计数归零。

### 2026-08-20 16:12 CST：检查计数重置为 0

- 检查范围：V44 retired marker 外键、TTL cleanup 和字段约束。
- 发现问题：永久 marker 若以默认 RESTRICT 引用会按 TTL 删除的 idempotency operation，
  将阻塞既有幂等账本清理。
- 处理措施：operation FK 使用 `ON DELETE SET NULL`；补充 marker 标识长度、可见字符和
  active/resolved 一致性约束。
- 结果：V44 生命周期约束已闭合，连续无问题检查计数归零。

### 2026-08-20 16:14 CST：检查计数重置为 0

- 检查范围：P1 repair preview、崩溃接管、结果重放和 cleanup 生命周期。
- 发现问题：单一 `expires_at` 无法同时表达短 preview 有效期、进行中 operation 接管期和
  completed result 保留期，可能过早删除结果或让卡死 operation 永不收敛。
- 处理措施：拆分 15 分钟 `preview_deadline`、1 小时 `operation_deadline` 和完成后 24
  小时 `result_expires_at`；cleanup 只删除结果保留期已到的终态 operation。
- 结果：repair 生命周期已闭合，连续无问题检查计数归零。

### 2026-08-20 16:16 CST：检查计数重置为 0

- 检查范围：P1 修复入口与现有 local/vector 正式服务的复用关系。
- 发现问题：规划新增 `rebuildCurrent` 和 corrupt-vector enqueue，但共享 freshness 收紧
  后，现有 `ensureCurrent` 与 force enqueue 已能分别重建 local、以
  `preserveCompleted=false` 排除损坏向量；新方法会形成重复轮子。
- 处理措施：删除两个专用方法设计，让 repair 复用现有 chunking/generation/job 路径；
  只增强共享 freshness 和对应回归测试。
- 结果：实现面缩小，连续无问题检查计数归零。

### 2026-08-20 16:18 CST：检查计数重置为 0

- 检查范围：P1 local/vector 子动作成本门，以及前端硬门槛命令与当前 `package.json`。
- 发现问题：
  1. local 缺失时 preview 不能证明 vector 是否损坏；local 重建后若不重新判断，会产生
     不必要的 embedding provider 调用；
  2. 当前 WebUI 没有 `npm run typecheck`，规划门禁引用了不存在的命令。
- 处理措施：local 成功后重新计算 strict vector freshness，已 fresh 则
  `ALREADY_FRESH`；Phase A 明确新增 `typecheck` script，并使用现有 `test:run`。
- 结果：成本门和可执行前端门禁已补齐，连续无问题检查计数归零。

### 2026-08-20 16:21 CST：检查计数重置为 0

- 检查范围：最终第 1 轮，legacy document 的 local repair 与后续 vector 子动作恢复。
- 发现问题：legacy 文档可能缺少 content hash；`ensureCurrent` 会通过
  `ensureContentHash` 合法修改 hash/JPA version。ledger 若只记录 post-local generation，
  接管者会把本 operation 的变化误判为外部 mutation。
- 处理措施：item ledger 同时记录 post-local document JPA version/content hash；后续子
  动作接受该受控变化，仍要求 document revision/Collection/enabled 不变。
- 结果：legacy repair 恢复语义已闭合，连续无问题检查计数归零。
