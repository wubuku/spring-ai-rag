# 下一批高价值功能规划进度

> 对应规划：[2026-08-21_NEXT_HIGH_VALUE_FEATURES_PLAN.md](2026-08-21_NEXT_HIGH_VALUE_FEATURES_PLAN.md)
>
> 当前阶段已进入实施。本文记录调研、规划检查、实施切片、验证证据和后续恢复上下文。

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
| 规划连续三轮检查 | 完成 | 项目边界校正后连续三轮无修改，计数为 `3/3` |
| 文档门禁 | 完成 | `verify-project-docs.sh` 10 项通过，129 个文件、878 个相对链接有效 |
| P0 原子迁移 | 后端完成 | 原子迁移、精确重放、双 scope fencing 与旧地址 guard 已落地 |
| P1 派生完整性 | 后端完成 | strict freshness、readiness、durable preview/apply/status 已落地 |
| WebUI / 长青文档 / 一键门禁 | 完成 | WebUI、Mock 验收、双语 API/配置/测试/项目上下文和两个专项脚本已落地 |
| 实现基本硬门槛 | 完成 | Profile fencing 后专项、3508 全量测试与 V45 独立启动通过 |
| 实现连续三轮检查 | 完成，`3/3` | 三个限定范围连续只读检查无问题、无实现修改 |
| commit / merge / push | 进行中 | 归档后提交、同步远端并推送 |

## 4. 实施恢复点

### 2026-08-21：开始实施

- 基线：`main@07cb2099`，Flyway V1-V43，工作区干净；规划连续检查 `3/3` 已完成。
- 当前切片：一次性建立 P0/P1 后端 PostgreSQL/HTTP、WebUI Mock 与一键门禁验收骨架；随后
  实施 P0 V44/API/事务，再实施 P1 V45/共享 freshness/repair。
- 硬边界：不使用悲观锁；不在 review 阶段零碎扩展验收矩阵；前端不使用截图证据；所有
  实现修改完成后先通过基本集成硬门槛，再开始实现 `3/3`。
- 下一恢复入口：规划第 6-8 节和当前代码中的 `DocumentMutationService`、
  `DocumentSyncRunService`、`CollectionIdentityResolver`、`KeywordIndexPersistenceService`、
  `EmbeddingDispatchService`、Documents/Embeddings WebUI 与对应集成测试。

### 2026-08-21 03:50 CST：后端垂直切片与真实 PostgreSQL 门禁

- 新增不可变 V44/V45，空库真实执行 45 个 migration 成功。
- P0 已实现 `POST /documents/relocate`、版本化幂等响应、双 Collection/namespace
  fencing、永久 retired-address ledger，以及普通/批量/Sync Run 共用 mutation 路径的
  sequence 后复查；外部 identity lookup 返回稳定 409。
- P1 已实现共享 strict freshness、Collection summary/details、durable
  preview/apply/status；local 修复复用正式 chunk service，vector 修复只创建持久化 job。
- `NextHighValueFeaturesPostgresIntegrationTest` 使用本机一次性 PostgreSQL 数据库运行：
  `4 tests, 0 failures, 0 errors, 0 skipped`。覆盖迁移、迁移保留派生/精确重放/旧地址阻断、
  active Sync Run 回滚，以及“向量行数相同但文本不一致”不得命中 cache。
- 后端 `compile` 与 `test-compile` 当前通过；尚未执行最终 `clean` 硬门槛。
- 下一步：WebUI API/交互、Mock Playwright、双语长青文档与一键脚本。

### 2026-08-21 04:02 CST：后端预验收集通过

- 修复新控制器测试的版本配置引用，并将缺少必填 `Idempotency-Key` 的对外响应从误报
  500 收敛为稳定 400。
- `mvn -pl spring-ai-rag-core -am -DskipTests test-compile` 通过。
- 真实 PostgreSQL 专项集成测试扩展为 `5 tests, 0 failures, 0 errors, 0 skipped`，新增覆盖
  preview/apply 持久账本、token 仅保存 hash，以及 vector repair 只排队、不调用 provider。
- HTTP 切片验收 `ExternalDocumentControllerWebTest,DerivationRepairControllerWebTest` 共
  `7 tests, 0 failures, 0 errors, 0 skipped`。
- 下一步：补齐有界完整性查询和旧 readiness 的共享真相源，然后完成 WebUI、脚本和双语
  长青文档；最终 clean 硬门槛仍在所有实现收束后统一执行。

### 2026-08-21 04:25 CST：实现与长青文档收束，准备进入硬门槛

- WebUI 已完成文档迁移入口、派生完整性 summary/details、preview-first repair 交互、
  API client、双语文案、Mock 和 Playwright 断言；此前 typecheck、214 个 Vitest、生产
  构建、alignment 与 14 个核心 Mock Playwright 均通过。
- `verify-document-relocation.sh` 与 `verify-derivation-integrity.sh` 已固化禁锁、HTTP、
  PostgreSQL、clean compile/test-compile、前端构建/Mock、文档和空白门禁。
- 配置、REST API、外部同步客户端、项目上下文、开发/测试参考、发布清单和索引的中英文
  长青文档已同步到 V44/V45。
- 硬门槛前完整性核对修复三处同范围缺口：tombstone 不再计为 enabled/repairable；单文档
  active job 必须严格匹配 document/profile/generation/hash/chunker；JSON 外部身份查询也
  执行 retired-address guard。对应断言已加入既有 PostgreSQL/Service 验收集。
- 下一步：先运行聚焦编译与测试确认上述收束修改，再执行两个完整专项门禁和服务启动。

### 2026-08-21 04:36 CST：启动门槛发现装配缺陷，审查计数维持 0/3

- relocation 与 derivation-integrity 首轮完整专项门禁均通过 `8/8`，包括真实 PostgreSQL、
  clean compile/test-compile、214 个 Vitest、生产构建、alignment、Documents 12/12 与
  Embeddings 2/2 无截图 Mock Playwright。
- PostgreSQL profile 启动验证先发现两个新 Service 错误地直接注入嵌套
  `RagDocumentLifecycleProperties`；已改为按项目惯例注入 `RagProperties` 并读取
  `documentLifecycle`。
- 继续启动发现已有 `EmbeddingJobWorker` 多构造器缺少主构造器标注；最小修复为在正式
  `EmbeddingJobExecutor` 构造器上添加 `@Autowired`，不改变 worker 行为。
- 修复后服务在端口 18083、独立数据库上成功启动，Flyway 当前版本为 45；
  `/actuator/health` 返回 `UP` 且 DB 为 `UP`，随后正常停止服务。
- 因发生实现修改，基本硬门槛和三轮实现检查均重新从头开始；下一步重跑两个专项脚本。

### 2026-08-21 04:42 CST：仓库级回归发现兼容性缺口，审查计数维持 0/3

- relocation 与 derivation-integrity 已在最新注入修复后再次完整通过 `8/8`；真实
  PostgreSQL 均迁移到 V45 且专项测试无跳过，前端 214 个 Vitest、生产构建、alignment、
  Documents `12/12` 与 Embeddings `2/2` Mock Playwright 再次通过。
- 随后的仓库级 `mvn test` 发现 30 个 error，经报告归因后只有两个根因：无 JPA 的
  OpenAPI 合约上下文未 mock 新增 relocation service，造成 29 个级联上下文错误；legacy
  JSON exact replay 在确认无变化前错误分配 source mutation sequence，造成 1 个既有幂等
  契约回归。
- 处理决策：OpenAPI producer contract 显式 mock relocation service；外部 upsert 先完成
  retired-address guard 与 exact-replay 判断，仅对真实写入分配 mutation sequence。修复后
  从专项门禁和仓库级全量测试重新验证，三轮实现检查仍保持 `0/3`。

### 2026-08-21 04:50 CST：基本硬门槛全部通过，开始三轮只读审查

- 修复后 relocation 与 derivation-integrity 专项脚本分别通过 `8/8`；两者均执行真实
  PostgreSQL V1-V45 迁移与专项测试且无跳过，并重复通过 `mvn clean compile
  test-compile`、214 个 Vitest、生产构建、alignment、Documents `12/12`、Embeddings
  `2/2` 无截图 Mock Playwright、文档和空白门禁。
- 仓库级 `mvn test` 全 Reactor 通过：API 539、documents 74、core 2847、starter 48，合计
  3508 tests，0 failures、0 errors；core 的 7 个 skip 是未配置通用外部 PostgreSQL 条件的
  既有测试，两个专项 PostgreSQL 验收集均明确为 0 skip。
- 当前构件重新安装后以 `postgresql` profile、端口 18083 和独立空数据库启动成功；Flyway
  schema version 为 45，`/actuator/health` 整体与 DB 均为 `UP`；随后正常停止，端口和两个
  一次性数据库均已清理。
- 下一步只进行三轮固定范围只读审查：数据/API/并发；事务/安全/成本；前端/契约/文档/
  脚本。只处理影响正确性、成本安全、兼容性或数据一致性的本任务缺陷。

### 2026-08-21 04:55 CST：第 2 轮审查发现 repair 收敛缺口，计数重置为 0/3

- 第 1 轮数据/API/并发只读审查无问题，计数曾达到 `1/3`；第 2 轮检查派生修复事务、
  lease、成本门和 retention 时发现三项同域缺陷。
- apply 使用 preview token 派生固定 lease owner，且阶段/终态写入不校验当前 lease owner；
  超时接管后旧执行者仍可能覆盖接管者结果。修复为每次 apply 随机 lease owner，并让 item
  claim、阶段状态、终态、失败和 preview completion 全部执行 owner+有效期条件写入。
- repair candidate 会包含无实际动作的 `INDEXING` 和 `KEYWORD_ONLY + vector INDEXING`，
  可能返回错误的成功结果。修复为共享 `repairable` 判定只允许至少一个正式动作，并让详情
  与 preview 使用相同语义。
- V45 已定义 24 小时结果保留期，但没有清理路径。修复为 preview 前执行有界、只删除终态
  且 `result_expires_at` 已到期的 opportunistic cleanup；补 PostgreSQL 断言。
- 发生实现修改后，基本硬门槛和三轮审查均从零开始。

### 2026-08-21 05:00 CST：repair 接管边界补充，计数维持 0/3

- 对上一轮 lease/retention 修复做可执行性核对时，补充确认三个同范围边界：旧 preview
  owner 失去 batch lease 后不得继续认领新 item；local 子动作已提交后的接管者必须接受
  ledger 中的 post-local version/hash；终态结果的 24 小时保留期必须从实际完成时起算。
- 处理措施：item claim 同时校验有效 preview lease；local phase 根据已提交子动作选择
  planned/post-local 快照；所有 COMPLETED/EXPIRED 写入重置 `result_expires_at`。PostgreSQL
  验收一次性覆盖收敛中候选排除、过期终态清理、过期 lease 接管和完成后保留期。
- 当前快速 `test-compile` 已通过；发生实现修改后仍须从头执行两个专项脚本、仓库级测试、
  clean compile/test-compile、前端门禁与服务启动，三轮只读审查尚未重新开始。

### 2026-08-21 05:06 CST：repair 修复专项预验收通过，开始完整硬门槛

- 一次性本地 PostgreSQL 从空库执行 V1-V45，`NextHighValueFeaturesPostgresIntegrationTest`
  共 `8 tests, 0 failures, 0 errors, 0 skipped`。
- 新增断言已实际覆盖：收敛中无动作候选不进入 preview、终态过期结果有界清理、过期
  preview/item lease 接管、完成时重置 24 小时保留期，以及 local 子动作提交后按
  post-local ledger 继续 vector 子动作。
- 派生专项脚本选择器已固定包含上述用例。下一步从零执行两个完整专项脚本，再执行仓库级
  全量测试和独立服务启动；三轮审查计数仍为 `0/3`。

### 2026-08-21 05:09 CST：两个专项硬门槛通过，继续仓库级回归

- relocation 与 derivation-integrity 专项脚本分别通过 `8/8`，均重新执行禁悲观锁、HTTP
  合约、一次性 PostgreSQL V1-V45、`mvn clean compile test-compile`、WebUI typecheck、
  214 个 Vitest、生产构建、alignment、无截图 Mock Playwright、文档与 whitespace 门禁。
- relocation PostgreSQL `3/3`、Documents Playwright `12/12`；derivation PostgreSQL
  `6/6`、Embeddings Playwright `2/2`，全部 0 failure/error/skip。
- 下一步执行仓库级 `mvn test` 和独立 PostgreSQL profile 服务启动；通过后才能重新开始
  固定范围三轮只读审查。

### 2026-08-21 05:12 CST：基本硬门槛全部通过，冻结实现进入三轮审查

- 仓库级 `mvn test` 全 Reactor 通过：API 539、documents 74、core 2847、starter 48，合计
  3508 tests，0 failures、0 errors；core 的 7 个 skip 是未配置通用外部 PostgreSQL 条件的
  既有测试，两个专项 PostgreSQL 验收均为 0 skip。
- 服务以 `postgresql` profile、端口 18083 和独立空数据库启动成功；Flyway schema version
  为 45，`/actuator/health` 返回 `UP`。验证后服务进程、端口和一次性数据库均已清理。
- 最新两套专项证据分别位于 `.verification/relocation/20260821-0506-final/summary.md` 与
  `.verification/derivation-integrity/20260821-0507-final/summary.md`；启动证据位于
  `.verification/startup/20260821-final/summary.txt`。
- 从本节点起冻结实现，按数据/API/并发、事务/安全/成本、前端/契约/文档/脚本三个互不
  重叠范围执行只读审查。若发现本任务正确性缺陷并修改，审查计数重置且全部硬门槛重跑。

### 2026-08-21 05:16 CST：第 1 轮发现 namespace sequence 排序缺口，计数重置为 0/3

- 检查数据、API 与并发边界时确认：普通 external upsert 的 retired-address guard 位于
  namespace sequence 条件写入之前。若 upsert 先读到 marker 不存在、随后等待 relocation
  持有的 sequence 行锁，relocation 提交后 upsert 会基于陈旧判断继续在旧地址创建文档。
- relocation 对目标文档和目标 marker 的判断也位于 sequence 排序点之前；等待并发 mutation
  后可能得到陈旧结论，最少会把稳定的 409 冲突退化成数据库约束异常。
- 处理范围一次性限定为：所有真实 external upsert 在取得 sequence 后复查 marker；relocation
  在取得源/目标 sequence 后读取目标文档与 marker 并决定 reverse；新增 PostgreSQL 并发验收
  固定“先读、等待、迁移提交、旧地址写入被阻断”的时序。
- 发生实现与测试修改，三轮审查计数重置为 `0/3`，两个专项脚本、全量 Maven、独立服务启动
  与全部前端门禁均须从头重跑。
- 首次重跑还发现脚本在 Testcontainers 不可用时会接受 Surefire 的 `0 tests`；已让脚本解析
  PostgreSQL 验收 XML，分别强制 relocation `4/4`、derivation `6/6` 且零
  failure/error/skip。该次假绿运行已主动终止，不计入验收证据。

### 2026-08-21 05:24 CST：并发修复后的基本硬门槛全部通过

- 新增 PostgreSQL 并发验收实际证明：upsert 首次 guard 后等待 relocation 的 namespace
  sequence，relocation 写 marker 并提交后，upsert 在排序点后的第二次 guard 返回
  `EXTERNAL_IDENTITY_RELOCATED`，自身 sequence 增量回滚且旧地址没有文档。
- relocation 专项 `.verification/relocation/20260821-0520-final/summary.md` 通过 `8/8`：HTTP
  `6/6`、PostgreSQL `4/4`、Documents Mock Playwright `12/12`，且 PostgreSQL XML 强制零
  failure/error/skip。
- derivation 专项 `.verification/derivation-integrity/20260821-0521-final/summary.md` 通过
  `8/8`：HTTP `4/4`、PostgreSQL `6/6`、Embeddings Mock Playwright `2/2`，同样强制零
  failure/error/skip；两套均通过 clean compile/test-compile、214 Vitest、生产构建、
  alignment、文档与 whitespace。
- 仓库级 `mvn test` 再次通过：API 539、documents 74、core 2847、starter 48，合计 3508，
  0 failures、0 errors；独立 PostgreSQL profile 服务迁移到 V45，健康端点为 `UP`，证据位于
  `.verification/startup/20260821-0524-final/summary.txt`。所有服务和一次性数据库均已清理。
- 实现重新冻结；从 `0/3` 开始执行三个既定、互不重叠的只读检查范围。

### 2026-08-21 05:28 CST：第 2 轮发现 active Profile fencing 缺口，计数重置为 0/3

- 第 1 轮数据/API/并发检查无问题，计数曾达到 `1/3`；第 2 轮检查 repair 安全、成本和
  恢复语义时发现 V45 保存了 `active_embedding_profile_id`，但 apply 未读取或校验该值。
- preview 的 candidates、fingerprint 和 ledger 还分别调用活动 Profile provider；若 Profile
  在其间或 preview 后切换，旧 fingerprint 可能对新 Profile 排队，违反“Profile 变化后
  preview 失效”的规划契约并产生错误的 embedding 成本。
- 处理范围限定为：preview 一次固定 Profile ID并在持久化前复查；fingerprint 显式使用该
  ID；apply 读取 ledger Profile，未完成 operation 在开始及 vector 排队事务中要求活动
  Profile 一致；已完成结果仍可稳定读取；新增 PostgreSQL 用例断言切换后冲突且不排队。
- 发生实现与测试修改，三轮审查重置为 `0/3`，全部专项、全量 Maven、前端门禁和独立启动
  再次从头执行。

### 2026-08-21 05:35 CST：Profile fencing 修复后的基本硬门槛全部通过

- Profile 切换 PostgreSQL 用例从空库迁移到 V45 后通过：preview 使用固定 Profile，切换后
  apply 返回 `DERIVATION_REPAIR_CONFLICT`、operation 保持 `PREVIEWED`、dispatch 零调用。
- relocation `.verification/relocation/20260821-0530-final/summary.md` 与 derivation
  `.verification/derivation-integrity/20260821-0532-final/summary.md` 均通过 `8/8`；真实
  PostgreSQL 分别强制 `4/4`、`7/7` 且零 failure/error/skip，全部前端与文档门禁再次通过。
- 仓库级 `mvn test` 再次为 3508 tests、0 failures、0 errors；独立 PostgreSQL profile
  启动迁移到 V45，`/actuator/health=UP`，证据位于
  `.verification/startup/20260821-0535-final/summary.txt`。服务、端口和临时数据库已清理。
- 实现再次冻结，三轮只读检查从 `0/3` 重新开始。

### 2026-08-21 05:50 CST：实现连续三轮只读检查达到 3/3

1. 数据、API 与并发：核对 V44 约束、双 ACL、精确幂等重放、Collection CAS、源/目标
   namespace sequence 顺序点、active Sync Run 冲突和 retired-address 竞态；结合真实
   PostgreSQL `4/4` 结果，未发现问题。
2. 事务、安全与成本：核对 V45 token hash、owner/ACL、Profile fencing、父子 lease、
   崩溃接管、post-local ledger、终态保留与 cleanup；确认 apply 仅重建本地派生并创建
   持久化向量任务，不直接调用模型。结合真实 PostgreSQL `7/7` 结果，未发现问题。
3. 前端、契约、脚本与文档：核对 HTTP/OpenAPI、WebUI request/response、Mock 网络与可访问
   DOM 断言、验收脚本精确测试数防假绿，以及双语长青文档；未使用截图，未发现问题。

三轮之间没有修改任何实现、测试或文档。终止计数达到 `3/3` 后才写入本总结；随后只执行
文档、锁禁令和 whitespace 无副作用门禁，再进入归档与 Git 交付。

## 5. 规划检查日志

仅记录发现问题并发生修改的轮次；连续无问题轮次在执行总结中记录，不修改本文，以免破坏
“连续三轮无修改”的终止条件。

### 2026-08-21：边界校正后的连续检查达到 3/3

1. 冷读者与需求闭环：活动规划、双语 TODO 和草稿索引只使用 spring-ai-rag 自身概念，
   不依赖任何外部项目的领域背景；通过。
2. 代码、数据与 API：V43 基线、外部身份、幂等账本、Sync Run、派生状态、ACL/CAS 和
   禁悲观锁边界与当前代码一致；通过。
3. 验证与交付：PostgreSQL/HTTP E2E、clean compile、前端 typecheck/test/build、无截图
   Mock Playwright、文档门禁和修改后重置规则完整；通过。

三轮之间没有修改方案或实现。随后仅写入本检查结果，并重跑无副作用的文档门禁。

### 2026-08-21：项目边界校正，检查计数重置为 0

- 发现问题：上一版活动规划把另一个独立项目的领域模型、分支和交付步骤大量写入当前
  仓库，使 spring-ai-rag 的路线图依赖外部项目背景，破坏文档自包含边界。
- 处理措施：将该版 plan/progress 归档；恢复以当前代码、公开 API、迁移和测试为事实源的
  通用规划。活动主线重新确定为外部文档原子 Collection 迁移，以及派生索引完整性诊断与
  preview-first 修复。
- 边界：外部采用方只能作为公开契约的验收者；其领域表、业务 API、UI、分支和实施进度
  只在自身仓库维护，不进入本项目活动规划或长青事实。
- 结果：规划检查计数归零，按冷读者、代码数据、验证交付三个固定范围重新执行。

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
