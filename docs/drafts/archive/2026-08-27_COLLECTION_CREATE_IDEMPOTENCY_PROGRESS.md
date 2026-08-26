# Collection 创建持久化幂等性实施进度

> **状态**：已完成（本地与 Mock 验收通过；真实 Chat provider 验收受外部服务阻塞）
>
> **对应规划**：[Collection 创建持久化幂等性规划](2026-08-27_COLLECTION_CREATE_IDEMPOTENCY_PLAN.md)
>
> **规划基线**：`main` / `origin/main` @ `61c728c2`（2026-08-26）
>
> **规划工作区**：
> `/Users/yangjiefeng/.hermes/workspace/spring-ai-rag-main-delivery`
>
> **实施分支**：`feat/collection-create-idempotency-20260826`
>
> **实施 worktree**：
> `/Users/yangjiefeng/.hermes/workspace/spring-ai-rag-collection-create-idempotency`

本文是跨会话恢复账本，不是稳定架构事实。不得记录 credential、原始 idempotency key、
Authorization、完整 metadata、业务 payload、`.env` 内容或外部项目路径。

## 1. 当前状态

- [x] 上一轮 plan/progress 已按主题归档。
- [x] 上一轮功能已合入并推送 `main`，对应 feature worktree 已移除。
- [x] `main == origin/main == 063184dc`，规划工作区干净。
- [x] 核对通用业务 Client 的 P1/P2 类生产接入缺口与当前实现。
- [x] 确认 operation-scoped capability、最小权限 principal、principal provisioning
  idempotency、capability discovery 和 Sync Run item receipt 已交付。
- [x] 选定 Collection 创建持久化幂等性作为当前最高价值缺口。
- [x] 编写自包含活动规划和本恢复账本。
- [x] 规划连续 `3/3` 无修改审查。
- [x] 提交并推送规划 checkpoint。
- [x] 从最新 `main` 创建专用 feature 分支与隔离 worktree。
- [x] Slice A：公共 owner/fingerprint、配置、V52、entity/repository。
- [x] Slice B：Collection provisioning service 与 HTTP 契约。
- [x] Slice C：capability、PostgreSQL、双实例 HTTP 和业务 Client gate。
- [x] Slice D：双语长青文档。
- [x] 后端、前端、业务 Client 和合并后完整验收。
- [x] 真实 Embedding 验收通过。
- [x] 真实 Chat ask/stream、真实 WebUI Chat 和幂等回放已进入真实 provider 路径；
  provider 返回 `503 no_available_account`，应用返回 `504 CHAT_TIMEOUT`，因此不能记为
  真实 Chat 通过。
- [x] 同步最新 `origin/main` 后按合并后基线完整复验。
- [ ] 提交最终进度账本、归档文档并清理已合并的 feature worktree。

## 2. 已冻结的关键决策

1. 可选 `Idempotency-Key` 只作用于 `POST /api/v1/rag/collections`，不扩展 import/clone。
2. 无 header 创建继续返回 `200`；keyed 首次 `201`，exact replay `200` 并带 replay header。
3. owner 由认证上下文派生，database 使用 stable principal ID。
   root/legacy/auth-disabled 是部署级共享 owner，只有 database principal 提供逐 principal
   隔离。
4. 原始 key 不落库，只保存 SHA-256；Collection 请求只保存 canonical fingerprint。
5. V52 使用独立 `rag_collection_provisioning_operation`，不复用 principal ledger。
6. Collection 与 operation 同事务提交；唯一约束和有界新事务重读处理并发。
7. replay 返回 Collection 当前状态和当前 document count；软删除不恢复，物理缺失 fail closed。
8. replay 不重复写 create audit。
9. keyed feature disabled 或 ledger unavailable 返回 `503`，不退回普通创建。
10. capability protocol 保持 `1.0`，增加 additive
    `collectionCreateIdempotencyKey` 并保留旧 Java constructor。
11. WebUI 的 `collectionsApi.create` 每次调用生成一个 key，Axios 自动重试复用同一值；
    不新增用户输入、持久化或可见状态。
12. 实施前一次性完成 PostgreSQL、双实例/restart、ACL、故障恢复、前端和真实 provider
    验收矩阵，避免 review 阶段零碎补测试。
13. 仓库代码和文档只描述通用业务 Client 需求，不引入任何外部项目背景。

## 3. 规划审查账本

发现实质问题并修改规划时在此记录，连续计数重置为 `0`。无问题轮次不在三轮之间修改
plan/progress；达到 `3/3` 后一次性写入最终结果。

| 轮次 | 时间 | 范围 | 发现/处理 | 连续计数 |
|---|---|---|---|---:|
| 初稿 | 2026-08-26 | 当前代码、V27/V28/V50/V51、Collection API/ACL、principal provisioning、capability、测试与长青文档 | 已形成完整方案，等待固定范围审查 | 0 |
| 1 | 2026-08-26 | 需求闭环、自包含性、审计与异常恢复可实施性 | 发现现有 audit 是事务提交后的 best-effort 记录，不能宣称跨崩溃必有一条；另发现 `ON DELETE RESTRICT` 下不能在正常 PostgreSQL 中直接制造 dangling ledger。已收紧为首次路径至多一次/replay 不调用，并将异常引用验证拆为 FK 数据库测试与 service fail-closed 测试；计数重置。 | 0 |
| 2 | 2026-08-26 | API、事务、权限与现有 Client 重试行为 | 发现 WebUI Axios 对网络错误和 5xx 自动重试 POST；若 WebUI 保持无 header，后端能力无法解决其响应丢失问题。已把每次 create 调用生成一次 key、同次 Axios retry 复用、后续提交换 key及网络断言纳入范围；计数重置。 | 0 |
| 3 | 2026-08-26 | owner 安全边界与多调用方隔离 | 发现规划只列 owner 映射但未明确 root、legacy 和 auth-disabled 都是部署级共享作用域，容易被误读为逐调用者隔离。已补充隔离强度、生产限制和必须使用 database principal 证明不同 owner 的验收方式；计数重置。 | 0 |
| 4 | 2026-08-26 | schema、事务、cleanup 与运行配置可实施性 | 发现规划要求 scheduled cleanup，却没有冻结调度间隔，会形成未文档化的隐式运维参数。已增加默认一小时、范围 10 秒至 24 小时的 `cleanup-interval-ms` 与环境变量，并纳入属性测试；计数重置。 | 0 |

最终连续三轮在封板前保持 plan/progress 哈希不变：

| 连续轮次 | 时间 | 固定范围 | 发现问题 | 处理措施 | 结果 |
|---|---|---|---|---|---|
| 1/3 | 2026-08-26 | 需求闭环、自包含性、推荐默认、非目标、通用 Client 表述 | 无 | 无修改 | PASS |
| 2/3 | 2026-08-26 | Java/Spring 事务、PostgreSQL 约束、owner、Axios retry、capability、锁策略 | 无 | 无修改 | PASS |
| 3/3 | 2026-08-26 | 验收矩阵、故障注入、启动、发布/回滚、双语文档、Git/worktree | 无 | 无修改 | PASS |

封板前哈希：

```text
plan     b9b5dc25759b107f912b8b116d54e046c8e77cdd0a17832a5a157b15353e13b2
progress dce13f02913fd506099477cb9ba2f529fbf25bd27fc03b0867a7a681e188a140
```

## 4. 验证账本

| 时间 | 阶段 | 命令/范围 | 结果 | 证据 |
|---|---|---|---|---|
| 2026-08-26 | 基线 | `git status`、`main`/`origin/main`、worktree list | PASS | `61c728c2`，规划 worktree 干净 |
| 2026-08-26 | 需求探索 | Collection controller/service/entity/repository、V27/V28、V50 ledger、capability DTO/catalog、P1/P2 接入缺口、现有 PostgreSQL/HTTP gates | PASS | 当前代码、迁移、测试和双语长青文档 |
| 2026-08-26 | 规划 checkpoint | 文档 11 项、禁悲观锁、diff、added-line secret scan、commit/push | PASS | `main == origin/main == 333988ce` |
| 2026-08-26 | 实施基线 | 从最新本地 `main` 创建专用分支/worktree | PASS | `feat/collection-create-idempotency-20260826` @ `333988ce` |
| 2026-08-26 | 后端核心实现 | owner/fingerprint、配置、V52 ledger、事务服务、HTTP 契约、capability、audit replay 抑制 | PASS | 实现与兼容构造器已落地；keyed disabled/unavailable 均 fail closed |
| 2026-08-26 | 后端快速门槛 | core 及依赖模块 compile/test-compile、11 个聚焦测试类 | PASS | compile 与 test-compile 通过；聚焦矩阵 121 tests 全通过 |
| 2026-08-26 | PostgreSQL 验收 | 空库与 V51→V52、约束/FK、current replay、owner、同/异请求并发、回滚、cleanup、故障、JPA validate | PASS | `CollectionProvisioningPostgresIntegrationTest` 9 tests，0 failure/error/skip |
| 2026-08-26 | WebUI 实现与聚焦验收 | 每次 create 生成新 key、Axios retry 复用、后续提交换 key | PASS | typecheck；Vitest 9/9；生产 build；Mock Playwright `collections.spec.ts` 1/1，无截图 |
| 2026-08-26 | 双实例真实 HTTP 首轮 | capability、两个后端、首次 keyed create | FAIL / 已修复 | capability 返回 true，但 `@ConditionalOnBean` 在组件扫描阶段过早判定，运行时未注册 `CollectionProvisioningService`，首请求返回 `503`。已移除不可靠条件并增加完整 Spring 上下文 wiring 断言；按完整门槛重跑，不沿用首轮结果。 |
| 2026-08-26 | 双实例真实 HTTP 第二轮 | 首次/跨实例/canonical replay、冲突、owner、ACL、软删除、审计 | FAIL / 已修复 | 前述 HTTP 语义均通过；数据库断言发现同类条件装配问题也使 `AuditLogService` 在生产上下文缺失，创建审计为 0。已改为正常 repository 依赖装配、增加完整上下文断言，并让脚本输出精确事实差异；完整门槛再次从头重跑。 |
| 2026-08-26 | 双实例真实 HTTP 最终门槛 | 聚焦合同、PostgreSQL 9 项、两个后端、跨实例/restart、owner/ACL、软删除、审计、账本故障、数据库事实 | PASS | `scripts/verify-collection-provisioning.sh` 7 steps 全通过；V52、ledger=2、collections=2、明文 credential=0；首次创建审计恰好 1 条，replay 不重复写。 |
| 2026-08-26 | 双语长青文档首轮 | API、配置、架构/上下文、业务 Client、测试/发布/TODO、V52 入口与文档门禁 | FAIL / 已修复 | 文档门禁发现业务 Client 指南缺少 `verify-collection-provisioning.sh` 的直接入口；已补齐中英文专项门禁，避免新能力可实现但不可发现。 |
| 2026-08-26 | 双语长青文档最终门槛 | `scripts/verify-project-docs.sh` 全部 11 项 | PASS | 链接、双语结构、业务 Client 可发现性、V52 固定约定、脚本、Shell、diff 与 added-line secret scan 全通过。 |
| 2026-08-26 | 完整静态基线 | 文档复验、禁悲观锁、diff、added-line secret、外部项目专名残留 | PASS | 文档 11/11；生产源码无显式悲观锁/advisory lock；diff 与密钥扫描无命中；活动代码和文档无外部项目专名。 |
| 2026-08-26 | 后端全量测试首轮 | `mvn test` | FAIL / 已修复 | 3068 tests 中 `ApiKeyRootModeWebIntegrationTest` 因 Web slice 未导入新增的 `ProvisioningOwnerResolver` 而出现 6 个 ApplicationContext errors，其余无 failure。已补齐真实组件导入；后端门槛从受影响测试开始重跑，不沿用首轮结果。 |
| 2026-08-26 | 后端受影响测试复验 | `ApiKeyRootModeWebIntegrationTest` | PASS | 6 tests，0 failure/error/skip；真实 security filter、controller 与 `ProvisioningOwnerResolver` 在 Web slice 中完成装配。 |
| 2026-08-26 | 后端全量测试复验 | `mvn test` | PASS | API 541、Documents 74、Core 3068（7 个按配置设计的集成测试跳过）、Starter 44；无 failure/error。 |
| 2026-08-26 | 业务 Client PostgreSQL 矩阵首轮 | `verify-business-client-readiness.sh`（新隔离端口） | FAIL / 已修复 | PostgreSQL 迁移成功到 V52，但既有 `ManagedApiPrincipalPostgresIntegrationTest` 仍断言终点为 V51；已更新为 V52，并将从 gate 起点完整重跑。 |
| 2026-08-26 | 业务 Client PostgreSQL 修复复验 | `ManagedApiPrincipalPostgresIntegrationTest` | PASS | 13 tests，0 failure/error/skip；V47 旧数据升级、credential 生命周期、并发 rotation、capability 与 quota 契约均在当前 V52 schema 上通过。 |
| 2026-08-26 | 业务 Client PostgreSQL 矩阵第二轮 | `verify-business-client-readiness.sh`（隔离端口） | FAIL / 已修复 | `DocumentLifecyclePostgresIntegrationTest` 的两个 V39/V42 升级场景仍断言迁移终点为 V51；已更新为 V52，需从 gate 起点完整重跑。 |
| 2026-08-27 | 业务 Client readiness 最终重跑 | `BUSINESS_CLIENT_VERIFY_RUN_ID=20260827-collection-final-rerun5 BUSINESS_CLIENT_BACKEND_PORT=18086 BUSINESS_CLIENT_EMBEDDING_PORT=18087 BUSINESS_CLIENT_MOCK_FRONTEND_PORT=15186 BUSINESS_CLIENT_REAL_FRONTEND_PORT=15187 ./scripts/verify-business-client-readiness.sh` | PASS | 17/17 steps；251 条真实 HTTP 合同断言；双实例 Collection provisioning、PostgreSQL 矩阵、`mvn clean compile test-compile`、WebUI typecheck/Vitest 219/build/Mock Playwright、脚本/文档/锁/diff/密钥门禁全部通过。证据：`.verification/business-client-readiness/20260827-collection-final-rerun5/summary.md`；Collection 专项：`.verification/collection-provisioning/20260827-023328-70646/summary.md`。 |
| 2026-08-27 | Chat 真实验收首轮 | `CHAT_VERIFY_RUN_ID=20260827-collection-real-rerun1 ... ./scripts/verify-chat-capability.sh --with-real-llm` | FAIL / 已修复 | Chat PostgreSQL 集成测试仍把全量迁移终点及方法名写成 V51；当前 schema 已到 V52。已更新为 V52，旧流程结果作废，须从 Chat gate 起点完整重跑。 |
| 2026-08-27 | Chat 真实验收第二轮 | `CHAT_VERIFY_RUN_ID=20260827-collection-real-rerun2 ... ./scripts/verify-chat-capability.sh --with-real-llm` | FAIL / 已修复 | PostgreSQL 矩阵继续发现 `NextHighValueFeaturesPostgresIntegrationTest` 与 `ChatTurnOperationPostgresIntegrationTest` 仍断言最新迁移为 V51；已统一更新为 V52，旧流程结果作废，须从完整 gate 起点重跑。 |
| 2026-08-27 | Chat 真实验收最终当前轮 | `CHAT_VERIFY_RUN_ID=20260827-collection-real-rerun3 ... ./scripts/verify-chat-capability.sh --with-real-llm` | FAIL / 外部依赖阻塞 | 本地/Mock/数据库门槛 17 项全部通过；真实 WebUI SSE 等待 180 秒后未收到 200，原生 JSON Chat 返回 `504 CHAT_TIMEOUT`。隔离服务日志记录 provider `503 no_available_account`；使用同一主工作区 `.env` 的直接兼容接口探测返回 `HTTP 403`、`error code: 1010`。真实 Embedding 请求已完成。该结果不能作为真实 Chat 通过，待 provider 恢复后必须从真实 Chat 步骤重跑。 |
| 2026-08-27 | 合并后验证基线 | `git fetch origin`、`git rev-parse main origin/main`、`git status` | BASELINE | `main == origin/main == 063184dc`（merge: make collection creation idempotent）；以下验收全部按该合并提交重新执行，不沿用合并前结果。 |
| 2026-08-27 | 合并后 Chat 专项完整验收 | `CHAT_VERIFY_RUN_ID=20260827-main-collection-chat-final ./scripts/verify-chat-capability.sh --with-real-llm` | 17 PASS / 1 FAIL（外部依赖阻塞） | 本地 focused backend、PostgreSQL、`mvn clean compile test-compile`、全量 Maven、demo、启动、WebUI typecheck/Vitest/build、Mock Playwright、锁/文档/diff/密钥门禁全部通过；真实 Embedding 通过；真实 Chat/WebUI 因 provider `503 no_available_account`，最终 `504 CHAT_TIMEOUT`，直接 provider 探测为 `403 error code: 1010`。证据：`.verification/chat-capability/20260827-main-collection-chat-final/summary.md`。 |

## 5. 恢复入口

真实 Chat 当前受 provider 不可用阻塞；恢复后使用主仓库 `.env` 在隔离端口和临时
PostgreSQL 数据库重跑真实 Chat ask/stream、真实 WebUI Chat 及幂等回放。当前代码、
本地数据库、前端和 Mock 验收不因该外部阻塞回退或重做；任何后续真实 provider 重跑都必须
另建验证 run，并明确区分本地实现门槛与外部 provider 门槛。
