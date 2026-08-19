# 下一批高价值功能实施进度

> 对应规划：[NEXT_HIGH_VALUE_FEATURES_PLAN.md](NEXT_HIGH_VALUE_FEATURES_PLAN.md)
>
> 本账本用于跨多次操作恢复实施上下文。当前代码、迁移、自动化测试和
> `docs/` 长青文档优先于本账本。

## 1. 实施目标

本批次按 `P0 -> P1 restore -> P1 keyword/vector decoupling` 实施：

1. 权威来源全量快照对账：`begin / batch-upsert / preview-missing / complete /
   abort / status`，支持安全的 `ONLINE_CUT + TOMBSTONE` 和默认安全的
   `OFFLINE_MANIFEST + NONE`。
2. 本地文档历史版本受控恢复：只允许 `FULL` 快照和本地文档，恢复创建新
   revision、新 `RESTORE` 版本，并复用已有派生任务 fencing。
3. 本地关键词派生与远程向量派生解耦：新正文先进入独立 local chunk/full-text
   状态；旧正文立即退出；provider 失败时公开 `KEYWORD_ONLY`。

硬约束：

- 不使用 `FOR UPDATE`、`SKIP LOCKED`、JPA 悲观锁或 advisory lock；
- 不修改或删除已经执行的 V1-V41 迁移；新增迁移从 V42 开始；
- 外部地址仍为 `collectionKey + sourceNamespace + externalId`，容量为
  `128 / 128 / 255`；
- 不把来源 reconciliation marker 当作 `sourceRevision`；
- 前端验证只使用 DOM、网络、JSON 和断言，不使用截图；
- 测试先一次性编写验收矩阵，再运行基本集成门禁；三轮 review 只处理本任务
  范围内的正确性、兼容性、安全、成本和数据一致性问题。

## 2. 验收矩阵

### P0

- PostgreSQL E2E：run 创建/重放、lease 冲突、过期、abort、batch 幂等、
  manifest 模式校验、preview token、missing tombstone、并发 mutation 保护、
  namespace 隔离、ACL/anti-enumeration、删除保护阈值、TEXT/JSON_RECORD 隔离。
- reconciliation tombstone 保留来源 revision，写入 server-owned marker；
  同 revision 仅允许完整状态一致的 reconciliation 恢复。
- reference client：静态 manifest 安全默认、online cut 两阶段、checkpoint、
  fingerprint、dry-run、冲突和重试。

### P1 restore

- FULL 快照恢复为新 revision 和 `RESTORE` 版本；
- 不回拨版本号、不覆盖历史；
- content 变化调度新 generation，metadata-only 不调用 provider；
- 外部文档、旧不完整快照、越权和 stale CAS 失败；
- WebUI Restore action 的 DOM 可见性、disabled 状态、请求 JSON、409 刷新。

### P1 keyword/vector

- 新 generation 的 local chunks 与旧 generation 隔离；
- local READY + provider FAILED => keyword 可检索、vector 不可用；
- retry 后进入 READY；
- 旧 worker commit 被 generation/lease fence 拒绝；
- Collection/metadata/JSON payload filters 保持；
- diagnostics 明确 `KEYWORD_ONLY`，不伪造 semantic score。

## 3. 进度

| 阶段 | 状态 | 说明 |
|---|---|---|
| 规划与代码边界核对 | 完成 | 已确认 V42/V43 起点、现有 mutation/version/job 边界 |
| P0 sync-runs | 完成 | V42、DTO、service/controller、mutation 接线、reference client 和专项 HTTP 验收已完成 |
| P0 验证与文档 | 完成 | 一次性 PostgreSQL、Flyway V1–V42、HTTP 闭环、禁悲观锁和项目文档门禁已通过 |
| P1 version restore | 完成 | 后端 FULL snapshot restore 与 WebUI action 已实现并通过 focused/backend/WebUI 验收 |
| P1 restore 验证与文档 | 完成 | API/WebUI 文案与契约已写入正式文档，生命周期总脚本和无截图 Playwright 已通过 |
| P1 keyword/vector decoupling | 待开始 | V43+、派生 worker、检索 provider |
| 全量三轮收敛检查 | 进行中 | statusPath 修复后的完整门禁已通过；固定范围三轮只读审查从 0 开始 |
| 提交与推送 | 待开始 | 所有阶段完成后执行 |

## 4. 实施日志

### 2026-08-19

- 从干净的 `main` 开始，确认当前 HEAD 为已推送的文档治理提交。
- 读取活跃规划、`DocumentMutationService`、`DocumentVersionService`、
  `DocumentLifecycleService`、Embedding Job/持久化路径、控制器、PostgreSQL
  集成测试和 reference client。
- 已确认没有阻断性未决决策；下一步从 P0 V42 和一次性验收测试开始。
- 新增 P0 API DTO/枚举、错误码、生命周期 feature flags 和
  `V42__document_sync_runs.sql`。
- 实现 `DocumentSyncRunService` / `DocumentSyncRunController`，覆盖 begin、
  batch-upsert、preview、complete、abort、get/list；lease 只存 hash，missing
  对账使用 namespace mutation sequence，不使用悲观锁。
- `DocumentMutationService` 新增 sync-run current-transaction 入口；文档 mutation、
  item ledger 与 last-seen marker 同事务提交。失败 item 使用独立短事务落
  `FAILED`，确保重试稳定。
- 显式来源 tombstone 写 `deletion_origin=SOURCE`；snapshot missing tombstone 写
  `RECONCILIATION` 且保留 source revision。同 revision 只允许完整状态一致的
  reconciliation 恢复。
- 实现本地 FULL 历史快照恢复 API：
  `POST /rag/documents/{id}/versions/{versionNumber}/restore`。恢复创建新
  document revision 和 `RESTORE` 历史，不覆盖旧版本，并复用现有 embedding
  generation fencing。
- WebUI 版本历史增加受控 Restore action；外部文档或非 FULL 快照禁用；请求携带
  当前 document revision，前端测试将只使用 DOM 和网络断言。
- 阶段性 `mvn -pl spring-ai-rag-core -am -DskipTests compile test-compile`
  已通过；正式硬门禁仍待验收测试完成后执行。
- `DocumentSyncRunControllerWebTest` 已通过（3 个控制器契约测试）。
- Testcontainers PostgreSQL 因本机 Docker API 客户端版本不兼容而被跳过，未计为
  通过；随后复用 `.env` 中的本机 PostgreSQL 16.8 创建一次性数据库，真实执行
  `DocumentSyncRunsPostgresIntegrationTest` 并通过，Flyway 从空库迁移至 V42 后自动
  删除临时数据库。
- 本次数据库验收已覆盖 V42 迁移、同 namespace 单 active run、run item 幂等、ledger
  不存正文/JSONB、`SOURCE`/`RECONCILIATION` 删除来源约束；尚未覆盖
  `DocumentSyncRunService` 的完整 HTTP + mutation + tombstone 事务路径。
- 真实一次性 PostgreSQL 验收执行了 `DocumentSyncRunsPostgresIntegrationTest`，3/3
  通过且 `skipped=0`；Flyway 从空库实际迁移到 V42。lifecycle 验收首次发现一个旧测试
  仍断言 V41，已最小修正为 V42，等待修正后的重跑。
- 已将 V42、Sync Run、FULL 版本恢复和当前 keyword/vector 解耦仍未实现的事实同步到
  `AGENTS.md`、`docs/project-context*`、`docs/rest-api*`、`docs/testing-guide*`、
  `docs/developer-reference*`、`docs/TODO*` 和 release 文档；未把 draft 当作唯一真相。
- `scripts/verify-document-sync-runs.sh` 首次执行发现 `set -e` 下 `.env` 条件判断
  提前退出，已补显式成功返回；随后发现验收脚本错误地把 JSON 标量当作非法 payload，
  已改为验证 JSON `null` 的失败重试幂等；最后将删除断言改为正式响应契约中的
  `sourceDeletedAt`。专项脚本最终完整通过。
- `scripts/verify-document-lifecycle.sh` 已在一次性 PostgreSQL 上完成 14/14：
  lifecycle PostgreSQL 8/8、参考 client 8/8、真实 Spring Boot HTTP 同步与版本恢复、
  全后端测试、WebUI 和项目文档门禁均通过。全后端 Maven 汇总为 API 539、
  Documents 74、Core 2843（7 个 PostgreSQL 环境测试跳过）、Starter 48，
  失败/错误均为 0；WebUI Vitest 213/213，Mock Playwright 10/10。
- 已将本地版本恢复的真实 HTTP 验收固化进
  `scripts/verify-document-lifecycle.sh`：创建本地 FULL v1、更新为 v2、恢复为
  `RESTORE` v3，随后验证正文和历史；同一流程还验证 stale document revision、
  外部托管文档和 `CONTENT_AND_METADATA_ONLY` 版本均返回 409。非 FULL 场景使用
  一次性验收数据库中的受控 fixture 更新，不修改业务实现。
- 修改后的 `scripts/verify-document-sync-runs.sh` 已单独重跑并通过；可追溯证据位于
  `.verification/document-sync-runs/<run-id>/summary.md`（`run-id` 由执行机器时钟生成）。
  本次运行实际完成一次性 PostgreSQL、Flyway V42、真实 Spring Boot HTTP 合同、无悲观锁
  门禁和 `git diff --check`；HTTP 结果为 `applied=2`、失败 item 重试 `2`、tombstone `1`。
- Sync Run begin 返回的 `statusPath` 已修复为携带 URL 编码后的
  `collectionKey` 与 `sourceNamespace` 查询参数；因此客户端可以直接 GET
  `statusPath`，无需自行拼接状态查询范围。专项脚本已增加该直接请求断言。
- statusPath 修复后的生命周期总门禁已完整通过：14/14 步骤成功；后端 API 539、
  Documents 74、Core 2843（7 个环境测试跳过）、Starter 48，WebUI Vitest
  213/213、生产构建、对齐检查和无截图 Mock Playwright 10/10 均通过；项目文档
  10 项门禁、无悲观锁检查和空白检查也通过。
- 收敛检查第 1 轮发现并修复两项实现缺陷和一项文档陈旧：
  `EXCLUSIVE_OFFLINE + TOMBSTONE` 原先没有显式确认，现要求
  `confirmExclusiveOffline=true`；Sync Run 后续操作原先只校验 lease，现每次重新应用
  当前 API Key 的 Collection ACL；中英文 external-sync client guide 原先仍声明全量快照
  尚未进入 API，现已补齐 V42 Sync Run 的模式、重试、ACL 和破坏性操作说明。
  随后发现并修复 statusPath 缺少查询范围参数的问题；因发生修改，固定范围三轮检查
  计数再次重置为 0，修复后的完整门禁现已通过。
- 在固定范围第 2 轮审查中发现并修复一个 Collection 生命周期竞态：
  Sync Run 开始时虽然校验了 Collection 为 active，但后续 batch upsert 原先没有
  复用同一生命周期版本的 CAS fencing；Collection 可能在 run 开始后被软删除，
  batch 随后仍把文档写入已删除 Collection。现已让 Sync Run 的 external upsert
  在写入开始时取得 `ActiveCollectionToken`，并在 unchanged、created、updated
  三条结果路径确认 `confirmActiveWrite`；Collection 生命周期变化会使写入失败，
  不会写入已删除 Collection。新增
  `DocumentMutationServiceTest.syncRunUpsertFencesCollectionLifecycleWithVersionCas`
  覆盖该边界，针对性 `DocumentMutationServiceTest` 9/9 通过。
- 上述代码和测试修复后，三轮收敛审查计数重置为 0；随后重新执行完整生命周期门禁，
  14/14 步骤通过：包含 V39-V42 PostgreSQL/Flyway 验收 8/8、reference client
  9/9、真实 Spring Boot HTTP 外部同步 E2E、本地版本恢复真实 HTTP E2E、全后端
  `mvn clean compile test-compile` 与测试、WebUI Vitest 213/213、生产构建、对齐
  检查、无截图 Mock Playwright 10/10、项目文档 10 项门禁、无悲观锁检查和 Git
  空白检查。固定范围三轮审查现在从 0 重新开始。
