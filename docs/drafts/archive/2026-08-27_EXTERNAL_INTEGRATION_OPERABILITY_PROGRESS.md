# 外部业务接入合同完备性与数据面可运维性实施进度

> **状态**：实施与最终验收完成；已归档，Git 交付进行中
>
> **对应规划**：
> [2026-08-27_EXTERNAL_INTEGRATION_OPERABILITY_PLAN.md](2026-08-27_EXTERNAL_INTEGRATION_OPERABILITY_PLAN.md)
>
> **实施基线**：`main` / `origin/main` @ `0993b702`（2026-08-27）
>
> **实施分支**：`feat/external-integration-operability-20260827`
>
> **实施 worktree**：
> `/Users/yangjiefeng/.hermes/workspace/spring-ai-rag-external-integration-operability`

本文是跨会话恢复账本，不是稳定架构事实。每次关键进展先更新本文，再进入下一阶段。不得记录
API key、Authorization、原始请求/响应、业务 payload、外部 ID、Collection key、principal ID、
私有 URL、`.env` 内容或其他敏感信息。

## 1. 当前状态

- [x] V53 模型调用用量账本已完整验收、合入并推送 `main`。
- [x] 旧 usage plan/progress 已归档，稳定事实已进入双语长青文档。
- [x] 已确认主工作区干净且 `main == origin/main == 0993b702`。
- [x] 已从最新 `origin/main` 创建专用分支和隔离 worktree。
- [x] 已核对规划与 V53 合并后代码边界，V54、独立 endpoint/package、无 WebUI 的冻结决策仍成立。
- [x] Slice 0 文档门禁通过。
- [x] Slice A：运行时限制 capability 与共享限制常量。
- [x] Slice B：V54、配置、小时级 rollup repository、retention。
- [x] Slice C：HTTP observation filter、Collection context、异步 recorder、低基数 metrics。
- [x] Slice D：self/root/ADMIN 查询 API、Collection-scoped 聚合、授权和 OpenAPI 合同。
- [x] Slice E：preflight/readiness、双语长青文档与专项门禁实现和文档同步完成。
- [x] 基本硬门槛、真实全栈和真实 LLM 回归。
- [x] 同步最新 `origin/main` 后完整复验。
- [ ] feature/main Git 交付与 worktree 清理。

## 2. 冻结决策

1. 使用 V54 新增 `rag_api_operation_hourly` 与
   `rag_api_collection_operation_hourly`，不修改 V53 usage ledger。
2. `/integration-capabilities` 只做 additive 扩展，协议保持 `1.0`，旧字段和构造器兼容。
3. 运行时可配置限制直接来自 `RagProperties`；固定限制由验证、服务、OpenAPI 和 capability
   共用常量。
4. observation filter 位于认证、capability 和 rate-limit filter 外层，只按固定 route/method
   分类，不解析 body，不记录动态 URI。
5. request total 每次请求只计一次；Collection 行是 contribution，不能相加冒充请求数。
6. stable principal/Collection ID 只允许进入 PostgreSQL，不进入 Micrometer 标签、日志或证据。
7. recorder 使用有界队列、分组 batch upsert、fail-open、有限 drain；观测失败不得改变业务响应
   或触发 mutation/provider retry。
8. 普通 principal 仅可查询自己；root/数据库 ADMIN 可管理查询；legacy/static 拒绝；
   auth-disabled 只提供本地全局视图。
9. 查询默认最近 24 小时，最大 31 天，HOUR/DAY；保留期默认 90 天。
10. 本轮不新增 WebUI 页面，但必须通过现有前端 typecheck、Vitest、build、alignment 和核心
    Mock Playwright。

## 3. 实施清单

### Slice 0：基线

- [x] 核对分支、worktree、main/origin/main 和 Flyway V53。
- [x] 归档上一轮 plan/progress。
- [x] 更新本轮规划状态、基线和实际 worktree。
- [x] 创建本进度账本。
- [x] 运行文档门禁与 `git diff --check`。

### Slice A：机器可读限制

- [x] 扩展 capability DTO 并保留 Java source compatibility。
- [x] 让 capability catalog 投影 structured-record 动态配置和 observability 配置。
- [x] 抽取 Sync Run batch/list/receipt 共享常量。
- [x] 扩展 capability、OpenAPI、preflight 合同测试。

### Slice B：持久化与配置

- [x] 新增并校验 `RagIntegrationObservabilityProperties`。
- [x] 新增 operation/principal/status/latency 领域模型。
- [x] 新增 V54 migration、repository、并发 upsert、bounded query 和 retention。
- [x] 完成 V1-V54 PostgreSQL 集成矩阵。

### Slice C：请求记录

- [x] 新增固定 route classifier 和安全 principal projection。
- [x] 新增 request attribute Collection scope accessor，并在授权成功后捕获内部 ID。
- [x] 新增 bounded async recorder、flush lifecycle、shutdown drain、drop/flush meters。
- [x] 验证 2xx/401/403/404/409/429/5xx、unknown route、fail-open 和敏感信息边界。

### Slice D：查询 API

- [x] 新增 `/api/v1/rag/integration-observability` controller、DTO 和 query service。
- [x] 完成 OpenAPI 路径、参数、schema 和响应合同。
- [x] 实现 NORMAL self、root/ADMIN、auth-disabled、legacy deny。
- [x] 实现时间、粒度、operation、Collection 过滤以及 `Cache-Control: no-store`。
- [x] 覆盖 policy change、soft delete、disabled recorder 和空窗口合同。

### Slice E：文档与门禁

- [x] 扩展 business-client binding preflight/readiness；preflight 主脚本、本地 self-test、
  capability stub 和真实 readiness 合同已完成。
- [x] 新增非默认运行时限制配置，验证 capability 响应与实际 `400` 边界一致。
- [x] 完成统一专项验证脚本使用的脱敏 `release-manifest.json` 字段和证据边界。
- [x] 完成长青文档同步，并将 Flyway、API、配置、架构、接入、测试与发布事实更新到 V54。
- [x] 文档、链接、双语结构、脚本语法、密钥和空白门禁通过。
- [x] 运行完整后端、前端、文档、锁策略、密钥和核心 E2E 门禁。

## 4. 验证账本

| 时间 | 阶段 | 命令/范围 | 结果 | 证据 |
|---|---|---|---|---|
| 2026-08-27 | Slice 0 基线 | branch、HEAD、`origin/main`、worktree clean、Flyway/规划边界 | PASS；实施基线冻结为 `0993b702` | 当前 Git 状态 |
| 2026-08-27 | Slice 0 文档门禁 | `verify-project-docs.sh`、`git diff --check` | PASS；11 项文档检查通过；归档后相对链接已修复 | 当前命令输出 |
| 2026-08-27 | Slice A 实现 checkpoint | capability DTO/catalog、observability 配置与 validator、Sync Run 共享常量、application.yml、聚焦测试 | 实现完成；已通过聚焦验证 | 当前特性 worktree |
| 2026-08-27 | Slice A 聚焦验证 | `mvn -pl spring-ai-rag-core -am -Dtest=IntegrationCapabilityCatalogTest,IntegrationCapabilitiesControllerTest,DocumentSyncRunControllerWebTest,RagPropertiesTest ... test` | PASS；30 项测试通过；修复方法级校验异常错误映射后，超限请求返回 400 | Maven/Surefire |
| 2026-08-27 | Slice B 编译 checkpoint | `mvn -pl spring-ai-rag-core -am -DskipTests compile test-compile` | PASS；API、Documents、Core 均成功编译，覆盖 V54、观测 repository/filter/recorder 的新增类型契约 | Maven 输出：`BUILD SUCCESS` |
| 2026-08-27 | Slice B PostgreSQL 验收 | `mvn -pl spring-ai-rag-core -am -Dtest=IntegrationObservabilityPostgresIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false -Dintegration-observability.it.enabled=true ... test` | PASS；5 项：V1-V54 全迁移、约束与索引、6 路并发精确累加、双 Collection contribution、UTC HOUR/DAY 与时间边界、事务回滚和有界 retention | Testcontainers `pgvector/pgvector:pg16`；Maven `BUILD SUCCESS` |
| 2026-08-27 | Slice C 关键修复 checkpoint | `ApiKeyAuthFilter` 增加认证要求 request attribute；批量 Collection key 解析仅在整批成功后写入 observation context；观测 filter 收尾保护为 fail-open | 实现已完成并通过 `mvn -pl spring-ai-rag-core -am -DskipTests compile test-compile`；聚焦行为测试正在补齐 | 当前特性 worktree |
| 2026-08-27 | Slice C 分类、身份与 Collection context | classifier、principal projection、observation/context、auth filter、Collection resolver、安全配置聚焦测试 | PASS；96 项；覆盖 17 路由、认证失败与 auth-disabled 区分、批量解析成功/失败捕获和 filter 顺序 | Maven `BUILD SUCCESS` |
| 2026-08-27 | Slice C recorder/filter | queue/flush/cleanup/shutdown、2xx/401/403/404/409/429/5xx、异常传播、unknown/disabled、fail-open 与 meter 标签 | PASS；26 项；repository/cleanup/recorder 故障不改变业务结果，动态 principal/Collection/URI 不进入 meter 标签 | Maven `BUILD SUCCESS` |
| 2026-08-27 | Slice C 合并聚焦回归 | Slice C 全部 classifier/principal/context/recorder/filter/auth/Collection/config tests | PASS；114 项，未发现 RequestContextHolder 或 meter registry 测试间污染 | Maven `BUILD SUCCESS` |
| 2026-08-27 | Slice D 验收矩阵 checkpoint | query service、Web、capability filter 与 OpenAPI 固定范围 | 已冻结一次性测试范围：NORMAL self/跨 principal、restricted/unrestricted Collection、root/ADMIN/auth-disabled/legacy、disabled、输入边界、空结果/排序/分位/溢出/异常维度/软删除、200/400/403/503 RFC 7807、`RAG_READ` 与 OpenAPI 参数响应 | 本进度账本 |
| 2026-08-27 | Slice D OpenAPI 门禁修复 | `OpenApiContractTest` | PASS；35 项；通用响应媒体类型门禁接受 `application/json`、RFC 7807 `application/problem+json` 和 Springdoc 兼容 `*/*` | Maven `BUILD SUCCESS` |
| 2026-08-27 | Slice D 合并聚焦回归 | `IntegrationObservabilityQueryServiceTest`、`IntegrationObservabilityControllerWebTest`、`ApiCapabilityFilterTest`、`OpenApiContractTest` | PASS；60 项；覆盖 self/root/ADMIN/auth-disabled/legacy、Collection ACL/软删除、输入边界、稳定排序、400/403/503、`no-store`、`RAG_READ` 和完整 OpenAPI 合同 | Maven `BUILD SUCCESS` |
| 2026-08-27 | Slice E preflight 快速合同 | `business-client-binding-preflight-self-test.sh` | PASS；11 个既有安全/输入负例和 5 个 capability 场景；覆盖默认兼容、items/payload 最低要求、观测 feature、协议不兼容、非法最低值以及证据脱敏 | Shell + 本地 HTTP stub |
| 2026-08-27 | Slice E 实现 checkpoint | capability preflight/readiness、非默认限制、V54 数据面观测、manifest 脱敏字段、统一专项门禁接入 | 实现完成；正式文档同步后进入完整验收 | 当前特性 worktree |
| 2026-08-27 | Slice E 长青文档与治理门禁 | REST API、业务接入、配置、架构、项目上下文、测试、开发者参考、发布清单、TODO、部署、索引、AGENTS、project-docs skill、`.env.example`、`verify-project-docs.sh` | PASS；11 项项目文档检查、相对链接、双语结构、Shell/Python 语法、added-line secret scan 与 `git diff --check` 全部通过；最新迁移事实统一为 V54 | `./scripts/verify-project-docs.sh` |
| 2026-08-27 | 完整 readiness 首次启动 | 默认端口 `18084`、`18085`、`15184`、`15185` 前置检查 | BLOCKED；`18084` 已被其他进程占用，脚本在启动任何测试资源前安全退出；不终止现有进程，改用确认空闲的隔离端口重跑 | `.verification/business-client-readiness/external-operability-premerge-20260827/` |
| 2026-08-27 16:00 +0800 | 完整 readiness 隔离端口重跑 | `external-operability-premerge-20260827-r2`；端口 `18094`、`18095`、`15194`、`15195` | FAIL；聚焦后端共运行 227 项，6 项 `ApiKeyRootModeWebIntegrationTest` 因同一 ApplicationContext 装载失败报错。根因是测试切片导入 `RagWebSecurityConfiguration`，但不装配完整观测 repository/recorder；`integrationObservationFilterRegistration` 却无条件要求 `IntegrationObservationRecorder`。该结果使 pre-merge 验收与后续审查计数全部失效，修复后必须先跑受影响聚焦回归，再从头执行完整 readiness | `.verification/business-client-readiness/external-operability-premerge-20260827-r2/` |
| 2026-08-27 16:02 +0800 | WebMvc 兼容性首次修复聚焦回归 | `ApiKeyRootModeWebIntegrationTest`、`RagWebSecurityConfigurationTest`、observation recorder/filter | FAIL；34 项中 33 项通过。原始 6 个 WebMvc 用例已全部恢复；新增正向配置断言发现方法级 `@ConditionalOnBean` 受同轮用户配置解析顺序影响，即使测试配置声明 recorder 也可能不创建 registration。改用 `ObjectProvider` 延迟解析，并在依赖不完整时创建禁用的 pass-through registration，随后重新执行相同聚焦范围 | Maven/Surefire |
| 2026-08-27 16:06 +0800 | WebMvc 兼容性修复聚焦回归 | `mvn -pl spring-ai-rag-core -am -Dtest=ApiKeyRootModeWebIntegrationTest,RagWebSecurityConfigurationTest,IntegrationObservationRecorderTest,IntegrationObservationFilterTest -Dsurefire.failIfNoSpecifiedTests=false test` | PASS；34/34。精简 WebMvc 切片缺少 recorder 时安全配置可启动且 observation registration 禁用；完整依赖存在时真实 `IntegrationObservationFilter` 启用并保持 order `-20`；原始 root/business key 生命周期 6/6 通过 | Maven `BUILD SUCCESS` |
| 2026-08-27 16:09 +0800 | 完整 readiness 修复后重跑 | `external-operability-premerge-20260827-r3`；端口 `18104`、`18105`、`15204`、`15205` | FAIL；前置检查、前端依赖、聚焦后端与合同 227/227、隔离 PostgreSQL 启动均通过。PostgreSQL 矩阵在 `ManagedApiPrincipalPostgresIntegrationTest.migrationBackfillsV47CredentialAndForbidsPlaintextSecrets` 失败：V1-V54 迁移成功，但旧断言仍期望 latest version `52`，实际为 `54`。需扫描迁移版本硬编码并更新陈旧测试基线，随后先重跑数据库矩阵，再从头重跑完整 readiness | `.verification/business-client-readiness/external-operability-premerge-20260827-r3/` |
| 2026-08-27 16:13 +0800 | PostgreSQL 失败项首次聚焦重跑 | `ManagedApiPrincipalPostgresIntegrationTest` 使用 Testcontainers | INFRA BLOCKED；本机 Docker Registry TLS 返回与 Docker Hub 主机名不匹配的证书，Testcontainers 无法拉取缺失的 Ryuk 镜像。已主动终止重复拉取；不视为代码验证结果。改用本地已有 `pgvector/pgvector:pg16` 直接启动一次性数据库，并通过测试的外部 JDBC 注入口重跑 | Maven/Testcontainers 控制台日志 |
| 2026-08-27 16:15 +0800 | PostgreSQL 失败项外部 JDBC 重跑 | 本地 `pgvector/pgvector:pg16` 一次性数据库；`ManagedApiPrincipalPostgresIntegrationTest` | TEST PASS / BUILD FAIL；13/13 测试通过，证明 V47→V54 升级断言修复有效；但此前被中断的 Maven/Testcontainers 运行留下损坏的 `target/jacoco.exec`，测试后的 JaCoCo report 报 `malformed input`，命令整体失败。必须执行 `mvn clean` 后重跑，只有测试与 Maven 生命周期同时成功才计为通过 | Maven/Surefire 与 JaCoCo 输出 |
| 2026-08-27 16:18 +0800 | PostgreSQL 失败项干净聚焦重跑 | `mvn clean` 后，本地 `pgvector/pgvector:pg16` 一次性数据库与外部 JDBC 注入口；`ManagedApiPrincipalPostgresIntegrationTest` | PASS；13/13，V47 fixture 升级到 V54、明文密钥清理、principal/capability/provisioning、轮换/撤销、并发与共享 quota 全部通过；JaCoCo report 和 Maven reactor 同时 `BUILD SUCCESS`，一次性容器已删除 | Maven/Surefire/JaCoCo |
| 2026-08-27 16:24 +0800 | 完整 readiness PostgreSQL 矩阵继续重跑 | `external-operability-premerge-20260827-r4`；端口 `18114`、`18115`、`15214`、`15215` | FAIL；前置检查、前端依赖、聚焦后端与合同 227/227、隔离 PostgreSQL 启动和 managed principal 矩阵均通过。`DocumentLifecyclePostgresIntegrationTest` 的 V39、V42 升级场景仍把当前最新迁移写死为 V52，V1-V54 实际迁移成功后两项断言失败。全库扫描同时发现 Collection provisioning、Chat session/turn 和上一轮控制面集成测试存在同类“当前最新版本”陈旧断言；本轮将一次性修正这些基线，历史升级 fixture 与边界语义保持不变 | `.verification/business-client-readiness/external-operability-premerge-20260827-r4/` |
| 2026-08-27 16:27 +0800 | Document lifecycle V54 聚焦回归 | `mvn clean` 后，随机本地端口的一次性 `pgvector/pgvector:pg16` 与外部 JDBC 注入口；`DocumentLifecyclePostgresIntegrationTest` | PASS；12/12。空库及 V39/V42 历史 fixture 均成功迁移到 V54，文档 CRUD、版本快照、派生状态与 CAS 行为通过；JaCoCo 和 Maven reactor 同时 `BUILD SUCCESS`，测试容器已自动删除 | Maven/Surefire/JaCoCo |
| 2026-08-27 16:30 +0800 | V54 current-latest 兼容性矩阵 | 一个随机本地端口的隔离 `pgvector/pgvector:pg16`、5 个独立数据库；Collection provisioning、Chat session、Chat turn operation、next-high-value control plane、LLM usage | PASS；5 个测试类共 47/47，V51→V54 和空库→V54 场景均通过；Collection 幂等、Chat history/lease/summary、turn 幂等、relocation/repair 与 V53 usage ledger 合同保持有效；JaCoCo 和 Maven reactor `BUILD SUCCESS`，测试容器已自动删除 | `.verification/external-integration-operability/latest-migration-contracts-20260827.log` |
| 2026-08-27 | 完整 readiness V54 修复后重跑 | `external-operability-premerge-20260827-r5`；端口 `18124`、`18125`、`15224`、`15225` | INFRA BLOCKED；前置检查、前端依赖、聚焦后端与合同 227/227、隔离 PostgreSQL、managed principal 13/13、document lifecycle 12/12、JSONB 3/3 均通过。随后 `IntegrationObservabilityPostgresIntegrationTest` 无条件自行启动 Testcontainers，触发本地缺失 Ryuk 镜像拉取；Docker Registry TLS 证书与主机名不匹配，重试已主动终止。readiness 清理成功，隔离端口和容器均已释放。该结果是测试基础设施耦合，不是产品断言失败；需让该测试复用 readiness 的受保护外部 JDBC 数据库后从头重跑 | `.verification/business-client-readiness/external-operability-premerge-20260827-r5/` |
| 2026-08-27 | 可观测性 PostgreSQL 基础设施解耦 | `IntegrationObservabilityPostgresIntegrationTest`、`verify-business-client-readiness.sh` | 实现完成；测试仅在显式 `INTEGRATION_OBSERVABILITY_IT_CLEAN_CONFIRM=YES` 时接受外部 JDBC，默认 Testcontainers 路径保持不变；readiness 为它创建独立数据库并注入隔离凭据。Shell 语法与变更空白检查通过，进入一次性数据库专项验证 | 当前特性 worktree |
| 2026-08-27 16:41 +0800 | 可观测性 PostgreSQL 外部 JDBC 专项 | 本地 `pgvector/pgvector:pg16` 一次性数据库；`mvn clean ... IntegrationObservabilityPostgresIntegrationTest` | PASS；5/5。V1-V54 迁移、索引/约束、6 路并发精确累加、双 Collection contribution、UTC HOUR/DAY 与时间边界、事务回滚和有界 retention 均通过；JaCoCo 与 Maven reactor `BUILD SUCCESS`，一次性容器已删除 | `.verification/external-integration-operability/observability-external-jdbc-20260827.log` |
| 2026-08-27 16:48 +0800 | 完整 readiness 外部 JDBC 修复后重跑 | `external-operability-premerge-20260827-r6`；端口 `18134`、`18135`、`15234`、`15235` | FAIL；前置检查、前端依赖、聚焦后端与合同 227/227、四组 PostgreSQL 矩阵 33/33、全仓 `mvn clean compile test-compile` 均通过。双实例 Collection provisioning 的 13 个 HTTP 生命周期断言也全部通过，但末尾只读数据库事实门禁仍硬编码最新迁移 V52，实际正确结果为 `54,2,2,0`，因此脚本失败。所有隔离进程、容器与端口已清理。将一次性扫描并改为从迁移目录动态推导最新版本，再重跑专项与完整 readiness | `.verification/business-client-readiness/external-operability-premerge-20260827-r6/`；`.verification/collection-provisioning/20260827-164649-49641/` |
| 2026-08-27 | 发布脚本迁移基线收敛 | `verify-collection-provisioning.sh`、`verify-managed-api-principals.sh` 与 `scripts/*.sh` 扫描 | 已将 Collection provisioning 的 V52 和 managed principal 的 V51 数据库事实断言改为从 Flyway 迁移目录动态推导；未发现其他 V48-V53 脚本级最新版本硬编码。Shell 语法与空白检查通过，进入双实例专项复验 | 当前特性 worktree |
| 2026-08-27 | Collection provisioning 动态迁移基线专项 | `external-operability-migration-baseline-20260827`；双实例端口 `18241`、`18242` | PASS；5/5 步骤及 13 个 HTTP 生命周期断言通过。跨实例重放、重启、owner 隔离、ACL、软删除当前态、单次创建审计和 ledger 故障 fail-closed 均成立；数据库事实为 `migration_54 ledger_2 collections_2 plaintext_credentials_0`，隔离进程与容器已清理 | `.verification/collection-provisioning/external-operability-migration-baseline-20260827/` |
| 2026-08-27 17:03 +0800 | 完整 readiness 动态迁移基线修复后重跑 | `external-operability-premerge-20260827-r7`；端口 `18144`、`18145`、`15244`、`15245` | FAIL；前置检查、前端依赖、聚焦后端与合同 227/227、四组 PostgreSQL 矩阵 33/33、全仓 `mvn clean compile test-compile`、双实例 Collection 生命周期、WebUI typecheck/Vitest 222/222/build、核心 Mock Playwright、脚本自测、锁策略、文档、密钥和空白门禁均通过。真实服务 HTTP/WebUI 验收已通过 capability、Collection/principal 生命周期、认证、ACL、429 恢复、preflight 和运行时 JSON batch 边界；唯一失败为成功的 `JSON_RECORD_BATCH_UPSERT` 未在等待窗口内形成可查询 rollup。隔离服务与资源已清理。需限定排查 filter/recorder 运行状态、后端日志与轮询边界，修复后从头重跑完整 readiness | `.verification/business-client-readiness/external-operability-premerge-20260827-r7/` |
| 2026-08-27 17:09 +0800 | `r7` rollup 失败根因与修复 | 真实服务日志、V54 repository、query service、DTO、双语长青文档与 `business-client-contract-e2e.sh` 固定范围交叉验证 | ROOT CAUSE / FIXED；同一 operation 已发送一个 `200` 和两个预期 `400`，request totals 的正确稳定值为 3，而验收脚本错误等待并断言 totals 精确等于 1，同时又要求 `byStatus={"200":1,"400":2}`，合同内部矛盾导致在 recorder 正确持久化 3 后必然超时。已将 operation 等待值和 totals 断言修正为 3；Collection contribution 仍保持 1，因为两个参数级 `400` 在 Collection 授权解析前被拒绝 | 当前特性 worktree；`r7/backend.log` 无 recorder flush/drop 错误 |
| 2026-08-27 17:17 +0800 | 完整 readiness observability 修复后重跑 | `external-operability-premerge-20260827-r8`；端口 `18154`、`18155`、`15254`、`15255` | FAIL；前置检查、聚焦后端与合同 227/227、四组 PostgreSQL 矩阵 33/33、全仓 Maven 编译、双实例 Collection 生命周期、WebUI typecheck/Vitest 222/222/build、核心 Mock Playwright、脚本、锁策略、文档、密钥和空白门禁均通过。真实服务合同确认 operation totals `3`、`byStatus=200:1/400:2`、Collection contribution `1`、跨 principal/Collection 拒绝和 preflight 全部通过，证明 `r7` 修复正确。随后 ASYNC JSON Record 已成功持久化主记录与 durable job，但 deterministic embedding stub 路径未在等待窗口内变为 fresh，验收失败并完成资源清理。需限定检查 worker/job 终态、stub 请求日志和 freshness 轮询条件 | `.verification/business-client-readiness/external-operability-premerge-20260827-r8/` |
| 2026-08-27 17:24 +0800 | `r8` ASYNC freshness 失败根因与修复 | 后端 worker 日志、deterministic embedding stub、Collection readiness 聚合语义和 E2E 场景顺序交叉验证 | ROOT CAUSE / FIXED；worker 已在目标文档创建后约 0.9 秒完成 embedding，产品路径正常。此前 batch 上限/observability 场景先在 Collection A 用 `SKIP` 创建 3 个 enabled 非 fresh 记录，后续 readiness 却要求 Collection A `enabledDocuments=1/freshDocuments=1`，导致跨场景污染后的断言必然超时。已将三个 batch 请求及其 self/Collection/ACL observability 断言迁到限流恢复专用 Collection 与 principal；Collection A 重新只承载真实 embedding 生命周期 | `r8/backend.log`；当前特性 worktree |
| 2026-08-27 | 后续 worktree 默认规则 | 用户明确要求：单任务/串行功能开发默认在当前工作区和分支实施，不创建隔离 worktree；只有用户明确安排多人或多任务并行时才使用 worktree | DONE；已同步 `AGENTS.md` 与双语 `delivery-workflow*`，删除“大块特性默认/推荐隔离 worktree”的旧规则，同时保留专用分支、持续同步 `origin/main`、合并后完整复验和并行 worktree 所有权/清理约束。本次既有 worktree 不迁移，避免增加切换风险 | 当前特性 worktree |
| 2026-08-27 17:36 +0800 | 合并前完整 readiness 最终重跑 | `external-operability-premerge-20260827-r9`；端口 `18164`、`18165`、`15264`、`15265` | PASS；17/17 阶段全部通过：聚焦后端与合同 227/227、四组 PostgreSQL 矩阵 33/33、`mvn clean compile test-compile`、双实例 Collection 生命周期 13 个 HTTP 断言、WebUI typecheck/Vitest 222/222/build、核心 Mock Playwright、脚本/锁策略/文档/密钥/空白门禁，以及真实服务客户合同 261 项和真实 WebUI Playwright。`r7` operation rollup 合同修复与 `r8` 场景隔离修复均被真实 HTTP 验收覆盖；异步 embedding、服务重启、未知 mutation 结果重放和数据库身份恢复均通过 | `.verification/business-client-readiness/external-operability-premerge-20260827-r9/summary.md`；脱敏 release manifest |
| 2026-08-27 17:50 +0800 | 真实 LLM 隔离全栈首次验收 | `external-operability-real-provider-premerge-20260827-r1`；主工作区 `.env`；隔离端口 `18174`/`15274` 和一次性数据库 | PROVIDER BLOCKED；进入 provider 前 Chat focused tests、全仓 Maven test、编译、两个 demo、WebUI typecheck/Vitest 222/222/build、文档/锁/空白门禁均通过。应用正确选择 OpenAI-compatible provider 和配置模型，但上游连续返回 HTTP 503 `no_available_account`；真实 WebUI SSE 在 180 秒内无 200，native JSON 最终按本地 deadline 返回 RFC 7807 `CHAT_TIMEOUT` 504。脚本已清理服务、端口、临时 env 和数据库。shutdown 后两个失败调用的 usage 记录因 executor 已关闭而 fail-open 丢弃，需在最终收敛前判断关闭时序是否应修复 | `.verification/chat-capability/external-operability-real-provider-premerge-20260827-r1/summary.md`；`.dev/backend.log`（本地、不提交） |
| 2026-08-27 17:52 +0800 | 真实 provider 脱敏可用性探针 | 主工作区 `.env` 的 OpenAI-compatible 与 Embedding endpoint；不落响应正文 | PARTIAL PASS；模型清单仅包含两个可用标识，配置模型连续 3 次 HTTP 503 `no_available_account`；备用模型首次 HTTP 200 且有内容。真实 Embedding HTTP 200，向量维度 1024。后续仅通过调用方临时覆盖使用备用模型，不修改仓库默认值或主 `.env` | 当前终端脱敏状态输出 |
| 2026-08-27 18:15 +0800 | 真实 LLM 隔离全栈备用模型重跑 | `external-operability-real-provider-premerge-20260827-r2`；临时模型覆盖；隔离端口 `18175`/`15275` 和一次性数据库 | CORE PROVIDER PASS / WEBUI AGENT TIMEOUT；应用确认使用备用模型，native JSON 与 SSE 各完成 1 次真实 provider 调用，两个幂等 replay 均复用同一 turn/response 且 provider counter 不增加，key 冲突 409、turn status `SUCCEEDED` 和 response snapshot 均通过。真实 WebUI 的 Agent+检索场景已先完成真实 1024 维 embedding，但 Chat 在项目 120 秒 deadline 返回 `CHAT_TIMEOUT`，Playwright 180 秒超时；未出现 provider 503。脚本已清理端口、临时数据库和服务。需固定范围判断 Agent 场景是否为模型工具行为/预算问题，并另跑真实 Embedding→检索→RAG Chat 生命周期 | `.verification/chat-capability/external-operability-real-provider-premerge-20260827-r2/summary.md`；`.verification/real-chat/20260827-181238-27139/` |
| 2026-08-27 18:18 +0800 | 独立真实 RAG 生命周期首次启动 | 隔离端口 `18176`、一次性数据库、备用真实 Chat 模型和真实 BGE-M3 | HARNESS FAIL；启动脚本曾确认健康并正确选择两个真实 provider，但其后台 JVM 随当前命令会话结束，随后 smoke 发现服务不可达。smoke 的早退 cleanup 同时因空 `API_AUTH_ARGS` 在 `set -u` 下展开而二次报错。前者改用持续 PTY 会话托管；后者属于验收脚本健壮性缺陷，修复并先做 shell/早退专项验证后重跑 | 当前终端输出；隔离 backend log |
| 2026-08-27 18:23 +0800 | 独立真实 RAG 生命周期第二次启动 | 持续 PTY 后端、隔离端口 `18176`、一次性数据库 | HARNESS ENV FAIL；后端持续健康且真实 Chat/Embedding 配置正确，但 smoke 所在 worktree 没有 `.env`，未显式继承主工作区 `SILICONFLOW_API_KEY`，因此在任何业务写入前以 preflight 退出。保持同一服务，显式加载主 `.env` 后重跑 | 当前终端输出 |
| 2026-08-27 18:28 +0800 | 独立真实 RAG 生命周期第二次重跑 | `external-operability-real-rag-premerge-20260827-r2`；持续 PTY、端口 `18176`、一次性数据库、备用真实 Chat 模型和真实 BGE-M3 | PARTIAL PASS；provider 探针、真实 1024 维 embedding、Collection/Document 隔离检索和 SSE RAG 均通过，SSE 答案包含唯一校验码。默认 120 秒非流式期限返回规范 RFC 7807 `CHAT_TIMEOUT` 504：真实 query rewrite 用时约 110 秒，仅留下约 10 秒给检索与回答。数据库确认 2 次 `QUERY_EXPAND` 和 2 次 `CHAT` 均由真实 provider 成功完成，Chat 路由按设计不进入本轮 operation rollup。服务在全部上游任务完成后优雅停止，关闭日志无 `executor_rejected`；两个一次性数据库已删除，端口已释放 | `.verification/integration-operability/external-operability-real-rag-premerge-20260827-r2/` |
| 2026-08-27 18:38 +0800 | 独立真实 RAG 长期限复验 | `external-operability-real-rag-premerge-20260827-r3`；隔离端口 `18177`、一次性数据库、备用真实 Chat 模型和真实 BGE-M3；只临时把非流式 Chat deadline 提高到 300 秒 | PASS；9/9。provider 探针、真实 1024 维 embedding、隔离检索和非流式 RAG 全部通过，答案包含唯一校验码并返回 1 个来源。数据库确认 V54、1 次成功 `QUERY_EXPAND`、1 次成功 `CHAT`、`COMPLETE` 会话历史和非空来源快照；Chat 路由未误入 operation rollup。服务优雅停止且无 usage recorder 丢失告警，一次性数据库和端口已清理 | `.verification/integration-operability/external-operability-real-rag-premerge-20260827-r3/` |
| 2026-08-27 18:41 +0800 | 最终远端同步基线 | `git fetch origin --prune`；比较 feature HEAD、local `main`、`origin/main` | PASS；三者均为 `0993b702`，ahead/behind 为 `0/0`，没有需要合并的远端提交。该确认后的工作树作为最终完整复验基线；不沿用合并前 `r9` 作为最终结论 | Git refs 与 rev-list |
| 2026-08-27 18:52 +0800 | 远端同步后完整 readiness | `external-operability-final-20260827-r1`；端口 `18184`、`18185`、`15284`、`15285` | PASS；17/17。聚焦后端与合同 227/227、四组 PostgreSQL 矩阵 33/33、`mvn clean compile test-compile`、双实例 Collection 生命周期、WebUI typecheck/Vitest 222/222/build、核心 Mock Playwright、文档/锁/密钥/空白门禁、真实服务客户合同 261 项和真实 WebUI Playwright 全部通过；重启、未知结果精确重放、异步 embedding 恢复、credential 轮换和双 Collection 查询均成立 | `.verification/business-client-readiness/external-operability-final-20260827-r1/summary.md`；脱敏 release manifest |
| 2026-08-27 18:53 +0800 | 最终资源与客户痕迹检查 | readiness 隔离端口/容器、全跟踪代码与文档、历史归档、`git diff --check`、no-lock | PASS；隔离端口全部释放，无残留测试容器；未发现特定外部客户名称或项目背景痕迹；空白和无悲观锁门禁通过 | 当前命令输出 |

## 5. 下一恢复点

实施和最终验收已经完成。下一步仅执行文档归档、feature commit/push、合并并 push `main`，
确认两个工作区干净且无测试资源后，安全移除本轮已有的 feature worktree。
