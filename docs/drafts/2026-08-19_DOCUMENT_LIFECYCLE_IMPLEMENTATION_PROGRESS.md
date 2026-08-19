# 文档生命周期 Batch A 实施进度

> **状态**：实施完成，硬门槛通过，三轮收敛审查完成，待提交
>
> **目标规划**：
> [2026-08-18_DOCUMENT_LIFECYCLE_AND_INDEX_CONSISTENCY_PLAN.md](2026-08-18_DOCUMENT_LIFECYCLE_AND_INDEX_CONSISTENCY_PLAN.md)
>
> **实施范围**：`Phase 0 + Batch A`。不包含 Batch B 权威快照对账、Batch C 历史版本恢复、
> Batch D 本地全文与远程向量存储拆分。
>
> **开始日期**：2026-08-19
> **代码基线**：`3b8b15c`
> **迁移起点 / 当前版本**：V39 / V41

## 1. 本次必须交付

- 本地文档：独立创建、CAS 修改、禁用、恢复、带 revision 的永久删除。
- 外部 TEXT/JSON：`collectionKey + sourceNamespace + externalId` 身份，
  source revision CAS、精确重放和 tombstone。
- 派生索引：正文变化立即使旧结果 stale，持久化任务与文档 mutation 同事务；
  metadata/payload/Collection-only 变化不触发 embedding。
- 生命周期读模型：文档状态、可检索状态、活动 Profile、任务和可重试错误。
- 现有 create/batch/upload/PDF/external/JSON 写入路径收敛到统一 mutation/derivation 服务。
- WebUI：编辑、禁用/恢复、生命周期状态和本地/外部管理边界。
- 外部 client：增量 `apply-events` reference CLI、schema、示例和中英文最佳实践。
- 一键验证：后端 PostgreSQL 集成、编译/全量测试、前端构建/Mock Playwright、
  reference client live HTTP E2E、文档门禁。

## 2. 验收硬门槛

- [x] V40 expand 与 V41 contract 迁移从 V39 数据库升级通过。
- [x] CRUD + 索引连带更新专项 PostgreSQL 集成测试通过。
- [x] external TEXT/JSON namespace、revision、重放、冲突和 tombstone 测试通过。
- [x] reference client 增量事件、重试、checkpoint 和密钥不落盘测试通过。
- [x] `mvn clean compile test-compile` 通过。
- [x] 全量后端测试通过。
- [x] WebUI TypeScript、生产构建和核心 Mock Playwright 通过；不使用截图验收。
- [x] `scripts/verify-document-lifecycle.sh` 一键通过。
- [x] 项目文档、禁悲观锁和 diff 门禁通过。
- [x] 通过验证硬门槛后，连续三轮限定范围只读审查无修改。

## 3. 进度

| 阶段 | 状态 | 说明 |
|---|---|---|
| 基线与验收矩阵 | 完成 | 已固定 V1-V39、Batch A 边界和验收门槛 |
| Schema / API | 完成，专项已验证 | 已新增 V40/V41、业务 revision、完整快照、CRUD DTO/端点、namespace 和生命周期响应 |
| Mutation / derivation | 完成，专项已验证 | 本地/外部 TEXT/JSON 已走统一事务；generation-aware job、提交门和持久化 SYNC/ASYNC 已接入 |
| 入口收敛 | 完成，专项已验证 | create/batch/upload、PDF 普通 SYNC/ASYNC、Collection import/clone/unlink 已接入统一 mutation；legacy SSE 保持兼容路径 |
| WebUI | 完成，门禁通过 | 已实现本地编辑、禁用/恢复/永久删除、生命周期状态、namespace 展示和外部来源只读边界 |
| Reference client / guide | 完成，门禁通过 | 已提供 `apply-events`、schema、样例、SQLite checkpoint、重试/CAS、真实 Spring Boot HTTP/DB 验收和双语长青指引 |
| 自动化验证 | 完成 | `verify-document-lifecycle.sh` 已连续执行 13 个门禁步骤并全部通过 |
| 三轮收敛审查 | 完成 | 固定范围连续 3 轮无修改；仅覆盖本批正确性、兼容性、成本和数据一致性 |

## 4. 关键决策

- `contentHash` 只表示派生输入 freshness，不作为业务身份。
- 公开 CAS 使用 `documentRevision`；JPA `@Version` 只作为内部 `rowVersion`。
- 正文 mutation 与持久化 embedding job 同事务；`SYNC` 只改变等待行为。
- 禁用、tombstone 和正文变化立即阻止旧 chunk/vector 被检索。
- 不使用 `FOR UPDATE`、`SKIP LOCKED`、JPA 悲观锁或 advisory lock。
- Batch A 不新增外部 multipart/PDF stable-identity API；connector 自行提取文本后调用
  external TEXT upsert。

## 5. 验证记录

- 2026-08-19：`mvn -q -DskipTests compile test-compile` 通过（首轮 Schema、本地 CRUD、
  generation-aware job 改造后的增量编译门槛）。
- 2026-08-19：接手续查确认 external TEXT/JSON 已具备 namespace、revision、重放和
  tombstone 的统一 mutation 骨架；下一步先修 legacy JSON 无 revision 更新兼容，再统一
  Collection/PDF/batch/upload 写入口。
- 2026-08-19：create/batch/upload 已接入协调器与可选 `Idempotency-Key`；Collection
  add/import/clone/unlink 通过 revisioned mutation；PDF 普通 SYNC/ASYNC 导入通过持久化
  job；batch hard delete 预先拒绝 external-managed 文档。随后
  `mvn -q -DskipTests compile test-compile` 通过。
- 2026-08-19：WebUI 已补齐本地文档编辑、禁用、恢复和永久删除，外部来源文档只展示
  namespace/identity 并隐藏本地写操作；新增生命周期状态和中英文文案。专项 Vitest
  `16/16`、`npm run build` 和 `npm run check:alignment` 通过，Mock Playwright 用例已编写，
  待统一 E2E 门禁执行。
- 2026-08-19：新增 `DocumentLifecycleControllerWebTest` 和
  `DocumentMutationServiceTest`，覆盖 PATCH/revision、metadata-only 不重嵌入、正文更新排队、
  禁用/恢复、永久删除和 external-managed 边界；新增用例合计 `16/16` 通过。
- 2026-08-19：`DocumentLifecyclePostgresIntegrationTest` 使用一次性本地 PostgreSQL
  完整执行 `6/6`、`skipped=0`，覆盖 V39→V41、namespace 三元身份、freshness、
  generation fencing、事务整体回滚和硬删除级联。
- 2026-08-19：新增 `scripts/verify-document-lifecycle.sh`，将禁悲观锁、专项测试、
  一次性 PostgreSQL、参考 client、clean compile、全量后端、WebUI Vitest/build/alignment、
  DOM/网络 Mock Playwright、文档门禁和 diff 检查固化为单一入口。脚本显式检查 PostgreSQL
  验收没有 assumption skip，并优先使用 `.env` 本地 PostgreSQL，Docker 仅作为后备。
- 2026-08-19：一键脚本补齐 reference client 真实应用链路：启动隔离
  `SpringAiRagApplication`，通过实际 Collection/外部文档 HTTP API 和同一一次性 PostgreSQL
  执行 `UPSERT r1 -> UPSERT r2 -> TOMBSTONE r3`，断言三元身份、source revision、
  tombstone、禁用状态和 `documentRevision=3`；使用 `embeddingPolicy=SKIP` 保持门禁确定性。
- 2026-08-19：最终一键验收运行 `20260819-125914` 完整通过 `13/13`，证据索引位于
  `.verification/document-data-plane/20260819-125914/summary.md`。其中生命周期专项测试
  `145/145`；V39→V41 PostgreSQL 验收 `6/6`、`skipped=0`；reference client 单测
  `4/4` 并完成真实 Spring Boot HTTP/DB E2E；`mvn clean compile test-compile` 和全量
  Maven reactor 通过（core `2836`、starter `48`，零失败）；WebUI Vitest `213/213`、
  TypeScript/生产构建、alignment 门禁和 Mock Playwright 文档套件 `10/10` 通过。
  Playwright 验收只使用 DOM、网络与自动化断言，没有截图判断；禁悲观锁、项目文档和
  git whitespace 门禁同时通过。
- 2026-08-19：收敛审查第 1 轮发现 legacy JSON upsert 兼容分支回归：未携带
  `sourceRevision` 的完全相同请求会错误增加业务 revision/版本。已在统一 mutation 中恢复
  `UNCHANGED` 幂等语义，并补直接覆盖生产 mutation 路径的回归测试；审查计数重置为 0，
  重新执行完整硬门槛后再开始三轮只读审查。
- 2026-08-19：修复后最终一键验收运行 `20260819-131121` 再次完整通过 `13/13`，证据索引
  位于 `.verification/document-data-plane/20260819-131121/summary.md`。生命周期专项测试
  增至 `146/146`，全量 core 增至 `2837` 项；其余 PostgreSQL `6/6`、reference client
  单测 `4/4`、真实 Spring Boot HTTP/DB E2E、starter `48`、WebUI Vitest `213/213`、
  Mock Playwright `10/10`、文档/禁悲观锁/whitespace 门禁继续全部通过。
- 2026-08-19：重新开始的收敛审查第 1 轮发现正文校验错误复用了标识字段的 `trim()`
  规范化，可能静默改变本地 PATCH、external TEXT 和 JSON `retrievalText` 的首尾空白、
  content hash 与派生输入。已拆分为“拒绝全空白但保留原值”的正文校验，并调整专项测试
  覆盖首尾空白保真；审查计数再次重置为 0。
- 2026-08-19：正文保真修复后的最终一键验收运行 `20260819-131751` 完整通过
  `13/13`，证据索引位于
  `.verification/document-data-plane/20260819-131751/summary.md`。生命周期专项测试
  `146/146`；V39→V41 PostgreSQL 验收 `6/6`、`skipped=0`；reference client 单测
  `4/4` 并完成真实 Spring Boot HTTP/DB E2E；`mvn clean compile test-compile` 和全量
  Maven reactor 通过（core `2837`、starter `48`，零失败）；WebUI Vitest `213/213`、
  TypeScript/生产构建、alignment 门禁和 Mock Playwright 文档套件 `10/10` 通过。
  Playwright 验收只使用 DOM、网络与自动化断言，没有截图判断；禁悲观锁、项目文档和
  git whitespace 门禁同时通过。三轮限定范围只读审查从 `0/3` 重新开始。
- 2026-08-19：收敛审查第 1 轮发现真实并发 CAS 的 HTTP 契约缺口：两个请求同时通过
  `expectedDocumentRevision` 前置校验时，后提交者会被 JPA 乐观锁正确拒绝，但异常原先
  落入通用 `DataAccessException` 处理器并返回 `500 DATABASE_ERROR`。现已新增
  `409 CONCURRENT_MODIFICATION` 映射和回归测试，并在双语 REST 文档中明确 client
  必须重新读取 revision 后再决定是否重试；审查计数重置为 `0/3`，重新执行完整硬门槛。
- 2026-08-19：并发 CAS 修复后的最终一键验收运行 `20260819-132728` 完整通过
  `13/13`，证据索引位于
  `.verification/document-data-plane/20260819-132728/summary.md`。生命周期专项
  `146/146`、PostgreSQL `6/6` 且无 skip、reference client `4/4` 与真实 HTTP/DB E2E、
  `mvn clean compile test-compile`、全量 core `2838`、starter `48`、WebUI Vitest
  `213/213`、Mock Playwright `10/10`、文档/禁悲观锁/whitespace 门禁全部通过。
  三轮限定范围只读审查从 `0/3` 重新开始。
- 2026-08-19：重新开始的第 2 轮审查集中发现五项同源数据面缺口：显式
  embedding-job API 仍创建固定 generation/chunker 的 legacy job；任务取消、重试及
  租约耗尽终态没有同步 lifecycle state；Collection readiness 与空结果诊断没有校验当前
  chunker；Controller 直接发起的 ASYNC 重索引没有统一事务包住 generation/state/job；
  常规 PDF SYNC 导入的外层事务使 provider 调用早于真实提交。审查计数重置为 `0/3`，
  以上问题作为一个修复批次完成后统一重跑一键硬门槛，不再继续发散审查。
- 2026-08-19：上述五项缺口已集中修复：显式任务和调度统一使用当前 generation、
  Profile 与 chunker descriptor 并合并同 generation 活动任务；取消、重试和租约耗尽
  终态同步 lifecycle state；readiness/空结果诊断同时校验正文 hash、generation、
  Profile 与当前 chunker；Controller ASYNC 调度由事务化 dispatch 统一提交；普通 PDF
  SYNC 去除覆盖 coordinator 提交边界的外层事务。专项测试增至 `147/147`，PostgreSQL
  生命周期验收增至 `8/8`。
- 2026-08-19：完整验收运行 `20260819-135235` 在新增的 current-chunker PostgreSQL
  场景稳定发现 `RetrievalEmptyReasonProbe` SQL 占位符与 Java 参数顺序不一致，导致诊断
  探针返回 unavailable。已将 JSON chunker、TEXT chunker、Profile ID 的绑定顺序与 SQL
  对齐；定向 PostgreSQL 验收重新执行 `8/8` 通过，审查计数保持重置为 `0/3`。
- 2026-08-19：修复后的完整一键验收运行 `20260819-135643` 通过 `13/13`，证据索引位于
  `.verification/document-data-plane/20260819-135643/summary.md`。生命周期专项
  `147/147`；V39→V41 PostgreSQL 验收 `8/8` 且无 skip；reference client `4/4` 并完成
  真实 Spring Boot HTTP/DB E2E；`mvn clean compile test-compile` 和全量 Maven reactor
  通过（API `539`、documents `74`、core `2839`、starter `48`，零失败；core 仅有
  7 个既有条件跳过）；WebUI Vitest `213/213`、TypeScript/生产构建、alignment 门禁和
  Mock Playwright `10/10` 全部通过。Playwright 只使用 DOM、网络和自动化断言，没有
  截图判断；禁悲观锁、项目文档和 git whitespace 门禁同时通过。连续三轮限定范围只读
  审查从 `0/3` 开始。
- 2026-08-19：重新开始的第 2 轮审查发现 PDF-to-RAG SSE 组合方法仍残留外层
  `@Transactional`，导致统一 mutation 完成后调用 embedding provider 时数据库事务尚未
  提交。已移除该外层事务，并新增精确回归断言，确保 SSE provider 调用不会再次被
  `PdfToRagService` 的声明式事务包裹；审查计数重置为 `0/3`，完整硬门槛通过后再开始
  三轮限定范围只读审查。
- 2026-08-19：PDF SSE 修复后的最终一键验收运行 `20260819-140944` 通过 `13/13`，
  证据索引位于 `.verification/document-data-plane/20260819-140944/summary.md`。
  生命周期专项测试 `147/147`；V39→V41 PostgreSQL 验收 `8/8` 且无 skip；reference
  client 单测 `4/4` 并完成真实 Spring Boot HTTP/DB E2E；`mvn clean compile
  test-compile` 通过；全量 Maven core `2840`（既有条件 skip `7`）、starter `48`；
  WebUI Vitest `213/213`、TypeScript/生产构建、alignment 门禁和 Mock Playwright
  `10/10` 全部通过。Playwright 只使用 DOM、网络和自动化断言，没有截图判断；禁悲观锁、
  项目文档和 whitespace 门禁同时通过。
- 2026-08-19：第 2 轮限定范围只读审查完成，无影响正确性、兼容性、成本、安全或数据
  一致性的缺陷，未修改代码。范围包括 embedding generation/Profile/chunker 一致性、
  claim/lease/commit fencing、取消/重试/租约终态、readiness/空结果诊断，以及所有本地、
  外部 TEXT/JSON、PDF、上传、批量和 Collection 写入入口。
- 2026-08-19：第 3 轮限定范围只读审查完成，无缺陷、未修改代码。范围包括 WebUI 文档
  生命周期交互、外部文档 reference client、REST/architecture/project-context/client
  指南的中英文契约同步，以及 legacy fallback 与兼容边界。三轮审查最终计数为 `3/3`。
