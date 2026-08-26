# Sync Run 持久化 item receipt 与游标状态查询实施进度

> **状态**：隔离特性分支最终验收完成，待 Git 交付
>
> **对应规划**：[NEXT_HIGH_VALUE_FEATURES_PLAN.md](NEXT_HIGH_VALUE_FEATURES_PLAN.md)
>
> **规划基线**：`main` / `origin/main` @ `67f69bfe`（2026-08-26）
>
> **规划工作区**：
> `/Users/yangjiefeng/.hermes/workspace/spring-ai-rag-main-delivery`
>
> **实施分支 / worktree**：
> `feat/sync-run-item-receipts-20260826` /
> `/Users/yangjiefeng/.hermes/workspace/spring-ai-rag-sync-run-item-receipts`

本文是跨会话恢复账本，不是稳定架构事实。不得记录 raw credential、cursor、externalId、
完整错误、业务 payload、Authorization、API Key、`.env` 内容或外部项目路径。

## 1. 当前状态

- [x] 上一轮 plan/progress 已按主题归档。
- [x] 已确认上一轮 feature 合入并推送 `main`，隔离 worktree 已清理。
- [x] 已核对通用外部 Client P1 类需求均已交付。
- [x] 已探索 JSON Record batch、Document Sync Run、embedding job、ACL 和 capability 现状。
- [x] 已选定 durable Sync Run item receipt/status 查询作为下一轮高价值功能。
- [x] 已编写自包含实施规划。
- [x] 规划连续 `3/3` 无修改审查。
- [x] 提交并推送规划 checkpoint。
- [x] 创建最新 `main` 基础的隔离特性 worktree。
- [x] Slice A：DTO、cursor 和 capability contract。
- [x] Slice B：V51、repository、service/controller。
- [x] Slice C1：PostgreSQL service、认证 HTTP 与权限验收。
- [x] Slice C2：真实全栈与 provider 回归。
- [x] Slice D：双语长青文档。
- [x] 基本硬门槛与交付前完整验收。
- [x] 同步 `origin/main` 后最终完整复验。
- [ ] 推送特性分支、合入并推送 `main`、清理 worktree。

## 2. 已冻结的关键决策

1. 扩展已有 Sync Run ledger，不创建第二套通用 mutation operation 表。
2. 新增 `GET /document-sync-runs/{runId}/items`，需要 Collection binding，不需要 lease。
3. GET 由中央 capability filter 要求 `RAG_READ`。
4. 使用 `seen_at + external_id` opaque keyset cursor；terminal 稳定，active 仅作最终一致观察，
   并在进入终态后从无 cursor 起点复扫。
5. response 不返回 fingerprint、payload、metadata、lease/hash 或 credential。
6. `currentSummary` 从 item ledger 实时聚合，不重定义既有累计 run counters。
7. V51 只新增 cursor/status 索引。
8. receipt 不代表 embedding readiness；派生状态继续使用 lifecycle/readiness/job API。
9. 新错误写入与即时 batch response 先 masking 再截断；receipt 读取再次 masking，兼容
   V42 以来可能存在的历史未脱敏错误。
10. cursor 使用 Jackson JSON 并绑定 run/status；先做 Collection/run authorization，再解码
    cursor。V51 普通索引迁移安排维护窗口并观察 PostgreSQL lock wait。
11. capability protocol 保持 `1.0`；`optional` 新字段是旧 Client 必须忽略的 additive
    JSON 扩展，并保留旧双参数 Java constructor。
12. filtered/unfiltered page 使用两条固定参数化 SQL，避免 nullable status OR 影响 V51
    索引计划。
13. Sync Run HTTP acceptance 改为认证模式，用临时 root 创建 restricted read-write/read-only
    principal 验证权限矩阵；真实全栈固定运行 business-client readiness 与
    managed-principal `--with-real-llm`。
14. 按用户后续明确指示，本轮跳过实现代码的连续三轮 review；正确性信心来自一次性规划的
    PostgreSQL、HTTP、Mock、真实双实例和真实 provider 自动化验收。交付前仍执行一次限定
    范围只读检查，只处理正确性、安全、兼容性或数据一致性缺陷。

## 3. 规划审查账本

发现实质问题并修改规划时记录在这里并把计数重置为 `0`。无问题轮次只在会话输出中总结；
达到连续 `3/3` 后一次性记录最终结果。

| 轮次 | 时间 | 范围 | 发现/处理 | 连续计数 |
|---|---|---|---|---:|
| 1 | 2026-08-26 22:08 CST | 需求闭环、自包含性、默认决策和非目标 | 发现 `seen_at` 不能保证并发事务提交顺序，active cursor 不应宣称 at-least-once 或不漏项。已收紧为 eventually-consistent 观察，并要求 terminal 后从头稳定复扫；计数重置。 | 0 |
| 2 | 2026-08-26 22:10 CST | 规划与恢复账本交叉一致性 | 发现进度账本的冻结决策仍残留 active cursor `at-least-once` 表述，与规划正文冲突。已统一为最终一致观察和终态后从头复扫；计数重置。 | 0 |
| 3 | 2026-08-26 22:12 CST | cursor 安全语义与可实施性 | 发现 Base64 只能隐藏内部结构，不能保护 `externalId` 的机密性。已明确 opaque 不等于加密、cursor 可解码且必须按业务敏感数据处理，并禁止承载任何 secret；计数重置。 | 0 |
| 4 | 2026-08-26 22:16 CST | ledger error 安全事实与公开读取边界 | 发现现有 `error_message` 只截断、不保证脱敏，规划原先错误假设它已 masking。已冻结新写入与即时响应统一 masking，并在 receipt 读取时再次 masking 以兼容历史行；计数重置。 | 0 |
| 5 | 2026-08-26 22:17 CST | cursor binding、授权顺序与迁移风险 | 发现 cursor 未显式绑定 run、解码先于 ACL 会造成差异化错误，且普通索引的写阻塞风险未写入发布边界。已改为 Jackson JSON 绑定 run/status、授权后解码，并冻结维护窗口与锁等待处置；计数重置。 | 0 |
| 6 | 2026-08-26 22:19 CST | capability 版本与旧 Client 兼容 | 发现“新增字段但保持 `1.0`”缺少兼容规则；直接升 `1.1` 又会破坏现有指南要求精确 `1.0` 的 Client。已冻结为保持 `1.0`、旧 Client 忽略未知 optional field，并增加旧 constructor/JSON 序列化兼容测试；计数重置。 | 0 |
| 7 | 2026-08-26 22:20 CST | PostgreSQL 查询计划与索引匹配 | 发现 nullable status OR 可能削弱 status cursor 索引的可预测性。已冻结 filtered/unfiltered 两条固定参数化 SQL，分别匹配两个 V51 索引；计数重置。 | 0 |
| 8 | 2026-08-26 22:25 CST | HTTP 权限验收与真实运行门禁 | 发现现有 Sync Run 脚本关闭认证，无法证明规划中的 `RAG_READ`/ACL 合同。已冻结临时 root + restricted read-write/read-only principal 的真实 HTTP 矩阵，并写明 business-client、managed-principal 与真实 LLM 命令；计数重置。 | 0 |

## 4. 验证账本

| 时间 | 阶段 | 命令/范围 | 结果 | 证据 |
|---|---|---|---|---|
| 2026-08-26 22:00 CST | 下一轮探索 | main/远端状态、V42/V50、Sync Run service/schema/script、JSON Record batch、embedding jobs、capability endpoint、P1/P2 通用缺口 | PASS | 本地代码、迁移、测试与长青文档 |
| 2026-08-26 22:28 CST | 规划最终审查 | 需求闭环 → schema/SQL/cursor/ACL/兼容 → 测试/发布/回滚/Git；最终 plan/progress SHA-256 分别为 `8a6b973e...` / `bf7ba555...` | PASS（连续 `3/3` 无修改） | 三轮会话审查输出与固定文件哈希 |
| 2026-08-26 22:33 CST | 实施基线 | 规划 checkpoint 已提交并推送，最新 `main` 创建专用特性分支与隔离 worktree | READY | `main == origin/main == 82cf3db5`；feature 起点 `82cf3db5` |
| 2026-08-26 22:43 CST | Slice A/B 生产代码骨架 | 三个 DTO、Jackson cursor、V51、receipt repository、Service/Controller、capability 字段及专项测试代码 | COMPILE PASS | `mvn -pl spring-ai-rag-core -am -DskipTests compile` |
| 2026-08-26 22:52 CST | 恢复与固定范围核查 | 核对 feature 基线、完整 diff、新增 DTO/cursor/repository、授权顺序、错误脱敏、V51 索引与认证 HTTP 验收矩阵 | READY | `feature/main/origin-main` 基线一致；无后台进程；下一步执行语法检查和定向测试 |
| 2026-08-26 22:55 CST | 脚本语法与后端定向测试 | Bash 语法通过；53 项定向测试中 52 项通过，OpenAPI 契约发现 `collectionKey` 未投影 `minLength: 1` | FIX REQUIRED | 为 query 参数补充显式 `@Size(min = 1, max = 128)` 后重跑完整定向集合 |
| 2026-08-26 22:57 CST | OpenAPI 契约修复复验 | Springdoc 未把方法参数 Bean Validation 长度约束投影到 query parameter schema；第二次整组仍为 52/53 | FIX REQUIRED | 保留运行时 Bean Validation，并按项目既有模式为全部新增 query 参数增加显式 `@Parameter/@Schema` |
| 2026-08-26 22:59 CST | 后端定向测试最终复验 | Controller、capability、filter、cursor 与 OpenAPI 合同完整集合 | PASS | 53 tests，0 failures，0 errors |
| 2026-08-26 23:01 CST | 首次认证 HTTP/PostgreSQL 验收 | V1-V51 迁移和认证后端启动成功；验收在 FAILED exact replay 累计计数假设处失败 | FIX REQUIRED | 持续失败重试事务会回滚 reopening 标记，既有 FAILED receipt 原样返回且不重复累计；改用 transient embedding 故障恢复场景证明累计计数与当前摘要分离 |
| 2026-08-26 23:07 CST | transient provider 场景核查 | Sync Run item mutation 在同一事务内只创建 embedding job，不执行真实 provider；关闭 job 时固定失败，开启后直接 APPLIED | FIX REQUIRED | 删除不符合架构的 provider stub；HTTP 验证 FAILED exact replay 去重，PostgreSQL service 集成验证累计 run counter 与当前 ledger summary 独立 |
| 2026-08-26 23:12 CST | 认证 HTTP/PostgreSQL 验收 | 临时 PostgreSQL、V1-V51 Flyway、认证后端、restricted writer/reader、ACL/binding/cursor、active/terminal receipt、FAILED replay、missing reconciliation、no-store、锁策略 | PASS | `.verification/document-sync-runs/20260826-231149/summary.md`；证据 JSON 不含 credential、cursor、externalId 或业务 payload |
| 2026-08-26 23:19 CST | PostgreSQL service 专项 | 本机隔离数据库、V1-V51 Flyway、`DocumentSyncRunsPostgresIntegrationTest`；首次测试 4/4 通过但旧 `jacoco.exec` 损坏导致 report 失败，随后以 `clean test` 从干净覆盖率基线完整重跑 | PASS | 4 tests，0 failures，0 errors；Maven reactor `BUILD SUCCESS`；隔离数据库已自动删除 |
| 2026-08-26 23:30 CST | 双语长青文档与可发现性 | REST、外部同步 Client、业务接入、配置、架构、项目上下文、测试、开发者参考、发布/TODO、索引、AGENTS 和 project-docs Skill；文档门禁增加 receipt capability/endpoint 可发现性断言 | PASS | `verify-project-docs.sh` 11 checks；双语结构 8 pairs；1182 个相对链接；`git diff --check` 与 added-line secret scan 通过 |
| 2026-08-26 23:53 CST | V51 既有 PostgreSQL 控制面回归 | 完整 business-client readiness 首次运行发现 6 个测试断言及 managed-principal 脚本仍把“最新迁移”硬编码为 V50；只更新最新版本基线和测试名，保留描述 V50 功能来源的历史事实，随后重跑受影响的五套 PostgreSQL 集成测试 | PASS（58/58，skipped=0） | `ManagedApiPrincipalPostgresIntegrationTest`、`ChatSessionPostgresIntegrationTest`、`ChatTurnOperationPostgresIntegrationTest`、`NextHighValueFeaturesPostgresIntegrationTest`、`DocumentLifecyclePostgresIntegrationTest`；Maven reactor `BUILD SUCCESS` |
| 2026-08-26 深夜 | 业务 Client 生命周期验收 | 聚焦后端/合同 137 tests、PostgreSQL 矩阵、Maven 编译门槛、WebUI typecheck/Vitest 218/build、无截图 Mock Playwright、文档/锁/密钥/diff、双实例真实 HTTP/WebUI 251 条断言、服务故障恢复与受限读取 | PASS（16/16） | `.verification/business-client-readiness/20260826-sync-run-receipts-client-rerun/summary.md`；release manifest 标记 migration=51、READ_ONLY/READ_WRITE |
| 2026-08-26 深夜 | 真实 LLM 与完整回归 | PostgreSQL 矩阵、`mvn clean compile test-compile`、全量 Maven（API 541、documents 74、core 3049、starter 44）、WebUI Vitest 218/typecheck/build/alignment、无截图 Mock Playwright、文档/锁/diff、双实例真实全栈和真实 provider | PASS（13/13） | `.verification/managed-api-principals/20260827-000052/summary.md`；真实原生/OpenAI-compatible JSON/SSE 共 5 次 provider 调用，read-only 与 replay 均为 0 额外调用，轮换后 principal continuity=true |
| 2026-08-26 深夜 | 限定范围交付检查 | V51/SQL/cursor/summary、HTTP/OpenAPI/ACL/低敏 response、验收脚本、双语文档、外部名称、锁策略、密钥和 diff | PASS（无实质问题、无代码修改） | `verify-project-docs.sh` 11/11；1182 links；8 bilingual pairs；`verify-no-pessimistic-locks.sh`、Bash syntax、`git diff --check` 通过 |
| 2026-08-26 深夜 | 最终远端基线 | 当前实现已提交为 `ff45de00`；fetch 后 `origin/main` 仍为 `82cf3db5`，且是特性分支祖先；显式 merge 返回 `Already up to date` | READY | 最终复验基线 `ff45de00` + 本行进度记录；无远端冲突或额外代码变化 |
| 2026-08-26 深夜 | 最终业务 Client 生命周期复验 | 聚焦后端/合同 137 tests、PostgreSQL V1-V51、Maven 编译门槛、WebUI typecheck/Vitest 218/build、无截图 Mock Playwright、文档/锁/密钥/diff、251 条真实 HTTP/WebUI 断言、服务故障恢复 | PASS（16/16） | `.verification/business-client-readiness/20260826-sync-run-receipts-final/summary.md` |
| 2026-08-26 深夜 | 最终真实 LLM/full-stack 复验 | PostgreSQL V1-V51、Maven 编译与全量测试、WebUI 全门槛、无截图 Mock Playwright、双实例真实全栈、真实原生/OpenAI-compatible JSON/SSE | PASS（13/13） | `.verification/managed-api-principals/20260827-001913/summary.md`；5 次 provider 调用，read-only 与幂等 replay 均为 0 额外调用，轮换后 principal continuity=true |

## 5. 恢复入口

规划 checkpoint 与实现提交均已完成。Slice A/B/C/D、定向与全量测试、认证 Sync Run
HTTP acceptance、业务 Client 生命周期、双实例真实全栈、真实 LLM/provider 以及双语
长青文档门禁均已通过；最终复验基线为 `ff45de00`，`origin/main` 仍为 `82cf3db5`。
下一步只需提交本进度账本、推送特性分支、合入并推送 `main`，随后确认工作区干净并安全
移除隔离 worktree。
