# 托管调用方幂等 provisioning 与运行时能力发现实施进度

> **状态**：已完成并归档
>
> **对应规划**：
> [2026-08-26_MANAGED_PROVISIONING_CAPABILITY_DISCOVERY_PLAN.md](2026-08-26_MANAGED_PROVISIONING_CAPABILITY_DISCOVERY_PLAN.md)
>
> **规划基线**：`main` / `origin/main` @ `cf740943`（2026-08-26）
>
> **实施分支**：`feat/managed-provisioning-capability-discovery-20260826`
>
> **实施 worktree**：
> `/Users/yangjiefeng/.hermes/workspace/spring-ai-rag-managed-provisioning-capability-discovery`

本文是跨会话恢复账本，不是稳定架构事实。不得记录 raw credential、完整
`Idempotency-Key`、Authorization、API Key、完整请求体、业务 payload 或外部项目路径。

## 1. 当前状态

- [x] 已核对上一轮归档规划，确认 provisioning 幂等和 capability discovery 是明确的
  后续独立缺口。
- [x] 已核对当前 `main`、`origin/main`、工作区和 worktree；当前规划工作区干净且同步。
- [x] 已核对 V48 明文 credential 禁止约束，冻结本轮不保存/不重放 raw secret。
- [x] 已核对现有 `IdempotencyKeyValidator`、Chat 幂等错误码、API principal 认证和
  WebUI create retry 相关实现。
- [x] 完成本规划三轮无修改审查。
- [x] 提交并推送规划，建立保护 checkpoint。
- [x] 创建最新 `main` 基础的隔离特性 worktree。
- [x] Slice A：公共 DTO、配置、V50 迁移和 provisioning ledger 持久化模型。
- [x] Slice B：幂等 provisioning service/controller 与 capability discovery。
- [x] Slice C：API/service/PostgreSQL/并发/生命周期/OpenAPI/能力投影验收测试完成。
- [x] Slice D：脚本和双语长青文档。
- [x] 完成基本硬门槛和必要真实服务验收。
- [x] 完成实现三轮无修改审查。
- [x] 同步 `origin/main` 后完成最终组合完整复验（13/13，真实 provider 5 calls）。
- [x] 合并、推送 `main`、确认干净并移除特性 worktree。

## 2. 已冻结的关键决策

1. `Idempotency-Key` 可选；有 key 时 fail closed，不能在账本不可用时静默走非幂等路径。
2. 幂等 owner 是请求实际认证 principal 的稳定身份；root 使用固定
   `root:environment-root`。
3. 数据库只保存 key hash、请求 fingerprint hash 和结果 metadata，绝不保存 raw secret。
4. 首次 keyed create 返回 `201 + rawKey`；精确 replay 返回 `200 + rawKey:null`、
   `secretAvailable:false`、`idempotentReplay:true` 和 replay header。
5. 仍使用唯一约束/CAS/条件写入，不使用显式悲观锁、`SKIP LOCKED` 或 advisory lock。
6. capability discovery 需要认证，返回版本化、低敏、与当前 principal projection 对齐
   的能力合同；ACL 无法完整解析时 `503`。
7. 本轮不改变 Chat/LLM 行为；真实 LLM 仅在既有全量 gate 适用时执行，不作为本轮
   provisioning 正确性替代证据。

## 3. 规划审查账本

| 轮次 | 时间 | 范围 | 发现/处理 | 计数 |
|---|---|---|---|---:|
| 1 | 2026-08-26 19:09 CST | 需求闭环、自包含性、默认决策与非目标 | 发现幂等 owner 未覆盖 legacy static/auth-disabled；未明确 Collection key 与 numeric ID 的等价请求必须共享解析后内部 ID 指纹。已记录并修正规划，计数重置。 | 0 |
| 1（复查） | 2026-08-26 19:13 CST | 需求闭环、自包含性、默认决策与非目标 | 发现目标摘要仍遗漏新增的兼容 owner 映射，已修正规划，计数继续为 0。 | 0 |
| 1（复查 2） | 2026-08-26 19:16 CST | 需求闭环、自包含性、默认决策与非目标 | 发现默认表、V50 字段说明和真实合同用例仍残留不完整 owner 摘要，已修正规划，计数继续为 0。 | 0 |
| 2 | 2026-08-26 19:24 CST | 代码、schema、API、安全、并发和兼容可实施性 | 发现 retention 到期后的幂等保证边界未明示；已补充保证期限和调用方约束，计数重置。 | 0 |
| 2（复查） | 2026-08-26 19:31 CST | 代码、schema、API、安全、并发和兼容可实施性 | 发现 principal/credential ID 既有兼容约定、bindingPreflight 语义和四类 capability projection 未冻结；已记录并修正规划，计数重置。 | 0 |
| 2（复查 2） | 2026-08-26 19:37 CST | 代码、schema、API、安全、并发和兼容可实施性 | 发现 capability feature flag 字段和 replay 在 rotation/revoke 后的 current credential response schema 未定义；已记录并修正规划，计数重置。 | 0 |
| 2 | 2026-08-26 | 代码、schema、API、安全、并发和兼容可实施性 | 对照现有 JPA/JDBC 事务、V48/V49 约束、认证与 capability filter、配置绑定、错误处理和 PostgreSQL 测试模式，未发现实质问题。 | 2 |
| 3 | 2026-08-26 | 实施顺序、验收矩阵、发布、回滚、文档和交付风险 | 对照验收矩阵、服务启动、前端门禁、文档/锁/密钥检查、发布回滚、Flyway 兼容和 worktree 交付顺序，未发现实质问题。规划达到连续 3/3。 | 3 |

## 4. 验证账本

| 时间 | 阶段 | 命令/范围 | 结果 | 证据 |
|---|---|---|---|---|
| 2026-08-26 | 规划前探索 | 当前 main、V48/V49、principal/service/controller/repository、历史 plan/TODO、WebUI retry | PASS | 本地代码与归档文档 |
| 2026-08-26 | 实施基线 | 特性分支从 `cf740943` 创建；进度账本 checkpoint `9a4ab5cf` | PASS | `git status --short --branch`、`git log` |
| 2026-08-26 | Slice A/B | 新增 V50 ledger、幂等 create/replay、owner projection、capability discovery endpoint 和认证例外 | PASS（编译+既有专项 45/45） | `mvn -pl spring-ai-rag-core -am -DskipTests compile`；API key/capability tests |
| 2026-08-26 | PostgreSQL 初次验收 | 容器启动、V1→V50 迁移和 13 个测试均执行；发现 replay unrestricted ACL 的空值处理缺陷，另发现 capability 顺序测试夹具与语义不一致 | FAIL（已定位，待重跑） | `TESTCONTAINERS_RYUK_DISABLED=true mvn -pl spring-ai-rag-core -am -Dtest=ManagedApiPrincipalPostgresIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false -Dmanaged-api-principal.it.enabled=true test` |
| 2026-08-26 | PostgreSQL 第二次验收 | 空值缺陷已修复；唯一剩余失败为重试夹具重新生成了不同 `expiresAt`，不符合幂等请求必须复用同一请求体的约束 | FAIL（已修正夹具，待重跑） | 同上命令；失败位于 `idempotentProvisioningReplaysAndRejectsFingerprintConflict` |
| 2026-08-26 | PostgreSQL 专项验收 | V1→V50、明文 secret 禁写、幂等 replay/conflict、owner 隔离、并发唯一竞争、rotation/revoke 后状态与 cleanup | PASS（13/13） | `ManagedApiPrincipalPostgresIntegrationTest`，真实 `pgvector/pgvector:pg16` |
| 2026-08-26 | cleanup 事务复核 | 发现 service 外层事务捕获数据库异常后仍可能在提交阶段抛出 rollback-only；删除事务下沉到 repository，service 在事务边界外捕获完整失败 | FIXED（待受影响门槛重跑） | `ApiKeyProvisioningOperationRepository.deleteCompletedBefore` |
| 2026-08-26 | cleanup 修复回归 | service 专项与真实 PostgreSQL 矩阵重跑 | PASS（11/11 + 13/13） | `ApiKeyManagementServiceTest`；`ManagedApiPrincipalPostgresIntegrationTest` |
| 2026-08-26 | API/能力/OpenAPI 合同 | 四类 identity projection、restricted/unrestricted ACL、完整解析失败 503、feature flag、create 200/409/503、OpenAPI path/schema/response | PASS（71/71） | `IntegrationCapabilityCatalogTest`、controller/filter tests、`OpenApiContractTest` |
| 2026-08-26 20:44 CST | Slice D 文档与脚本 | capability/provisioning 公共合同、V50 inventory、接入可发现性、Shell 语法、链接、双语结构、密钥与 whitespace | PASS（文档 11/11） | `./scripts/verify-project-docs.sh`；`bash -n scripts/verify-managed-api-principals.sh`；`git diff --check` |
| 2026-08-26 20:46 CST | 首次统一 PostgreSQL 矩阵 | 广域 Hibernate schema validation 与历史 PostgreSQL migration expectations | FAIL（已停止后续阶段并修复） | V50 hash 列 `CHAR(64)` 与实体 `VARCHAR(64)` 不一致；3 个最新迁移断言及 2 个 lifecycle 断言仍为 V49 |
| 2026-08-26 20:51 CST | PostgreSQL/证据脚本修复 | V50 hash 列改为 `VARCHAR(64)` 并保留 hex CHECK；最新迁移断言更新为 V50；新增列类型回归断言；中断运行必须记录 FAIL | FIXED（待矩阵重跑） | V50 migration、5 个 PostgreSQL integration assertions、`verify-managed-api-principals.sh` |
| 2026-08-26 20:53 CST | 修复后 PostgreSQL 核心矩阵 | Chat session、Chat turn operation、既有 high-value control planes、managed principal/V50 provisioning | PASS（46/46，skipped=0） | 4 个真实 `pgvector/pgvector:pg16` integration suites |
| 2026-08-26 20:55 CST | 文档生命周期迁移回归 | V39/V42→V50 升级、本地索引 backfill 与 lifecycle 合同 | PASS（12/12，skipped=0） | `DocumentLifecyclePostgresIntegrationTest` |
| 2026-08-26 21:00 CST | 首次修复后统一门槛 | PostgreSQL、Maven、双实例后端合同通过；隔离 worktree 未安装 `node_modules` | PARTIAL（前端命令 127，真实 WebUI 未启动） | run `20260826-205633`；后端 create/replay/conflict/rotation/revoke、capability、quota、V50 终态均 PASS |
| 2026-08-26 21:07 CST | 前端依赖与门槛 | `npm ci` 后运行 Vitest、TypeScript、生产构建、alignment 与核心 Mock Playwright | PASS（218/218 + build + 1/1） | `spring-ai-rag-webui/package-lock.json` 锁定安装；无截图断言 |
| 2026-08-26 21:14 CST | 预合并统一验收 | PostgreSQL 46/46、Maven clean/compile/test-compile、全量 Maven、WebUI Vitest/TypeScript/build/alignment、Mock Playwright、文档/锁/whitespace、双实例真实全栈与真实 provider | PASS（13/13 steps） | run `20260826-final-premerge`；API 541、Documents 74、Core 3043（7 个环境门控 skip）、Starter 44；WebUI 218/218；真实 WebUI 1/1；真实 provider 5 calls |
| 2026-08-26 21:31 CST | 最终组合基线 | fetch 后 `origin/main` 仍为 `cf740943`，是特性分支 merge-base；无新增上游提交，不创建空 merge | READY | feature `a61e38ed`；PostgreSQL 一次性数据库；后端 `18181/18182`；前端 `15181`；真实 provider 配置来自 main worktree `.env` |
| 2026-08-26 21:33 CST | 最终组合首次统一复验 | 前 12 步及双实例/真实 WebUI 合同通过；首个真实 native JSON Chat 未成功 | FAIL（12/13） | run `20260826-final-postmerge`；供应商连续返回 `503 no_available_account`，应用在既定 deadline 后返回 `504 CHAT_TIMEOUT`；provider counter 增量为 0，未观察到成功 provider invocation |
| 2026-08-26 21:42 CST | 最终组合完整重跑 | PostgreSQL 46/46、document lifecycle 12/12、Maven clean/compile/test-compile、全量 Maven、WebUI Vitest/TypeScript/build/alignment、Mock Playwright、文档/锁/whitespace、双实例真实全栈、真实 WebUI 与真实 provider | PASS（13/13 steps） | run `20260826-final-postmerge-rerun1`；API 541、Documents 74、Core 3043（7 个环境门控 skip）、Starter 44；WebUI 218/218；真实 WebUI 1/1；真实 provider 5 calls；主体连续性与只读权限均通过 |
| 2026-08-26 21:49 CST | Git 交付与清理 | 特性分支推送；main 专用 worktree fast-forward 合并并推送；核对 `main == origin/main == 67f69bfe`；移除隔离 worktree 与本地特性分支 | PASS | 远端特性分支保留为保护 checkpoint；main 工作区干净 |

## 5. 实现审查账本

| 轮次 | 时间 | 固定范围 | 发现/处理 | 连续计数 |
|---|---|---|---|---:|
| 1 | 2026-08-26 21:17 CST | 事务、迁移、并发、清理、secret 与失败恢复 | 未发现实质问题。 | 1 |
| 2 | 2026-08-26 21:20 CST | HTTP/OpenAPI、认证授权、能力/ACL 投影与兼容主路径 | 未发现实质问题。 | 2 |
| 3 | 2026-08-26 21:23 CST | 验收脚本、测试证据、文档、发布回滚、密钥与 Git 风险 | 发现架构表使用概念列名且误称存在 `principal_id` 查询索引；已按 V50 实际 schema 修正中英文文档，计数重置。 | 0 |
| 1（重启） | 2026-08-26 21:25 CST | V50 事务、迁移、并发、清理、secret 与失败恢复 | 未发现实质问题。 | 1 |
| 2（重启） | 2026-08-26 21:27 CST | HTTP/OpenAPI、认证授权、能力/ACL 投影与兼容主路径 | 未发现实质问题。 | 2 |
| 3（重启） | 2026-08-26 21:29 CST | 统一脚本、真实调用证据、长青文档、发布回滚、密钥与 Git 风险 | 未发现实质问题；实现达到连续 `3/3`。 | 3 |

## 6. 恢复入口

本轮已完成规划、实施、真实 PostgreSQL/HTTP/WebUI/provider 验收和连续 `3/3` 收敛检查。
最终 `main` / `origin/main` 为 `67f69bfe`，隔离 worktree 已安全移除，稳定行为已提升到
双语长青文档。本文和对应规划自此只用于历史追溯。
