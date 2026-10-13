# 第一批改进实施进度：统一文档处理阶段状态与恢复

> 对应规划：[第一批改进规划](WEKNORA_FIRST_BATCH_DOCUMENT_PROCESSING_PLAN.md)
>
> 本账本只记录实施状态、验证证据和恢复入口；实现完成后，随规划一并按 `docs/drafts/README-zh-CN.md`
> 的生命周期归档。

## 当前状态

- 分支：`main`
- 工作区：开始实施前干净；直接在当前 `main` 工作区实施。
- 规划状态：规划检查和实现后检查均连续 `3/3` 已通过；专项状态矩阵已补齐，当前仅待最终门禁和
  Git 交付。
- 本批范围：Processing Run/Stage、Job 镜像、CAS/lease 恢复、HTTP actions、housekeeping、V61。
- 明确不做：Citation Registry、SSE Decoder、Chunking Profile/Validator、Retrieval Context Assembly、
  Route Policy Self-check、Parser SPI、Resource Handle、CLI/MCP、WebUI 任务时间线。

## 切片进度

| 切片 | 状态 | 退出条件/证据 |
|---|---|---|
| Slice 1：API 状态和读模型骨架 | 完成 | DTO、状态枚举、稳定分页和错误摘要契约已编译；API 来源映射测试通过 |
| Slice 2：V61 schema 与属性 | 完成 | 真实 PostgreSQL 从空库迁移至 V61；V61 表/约束/索引完成 |
| Slice 3：Processing domain、repository、bridge | 完成 | 五阶段初始化、Run/Stage CAS、legacy bridge 和终态镜像已实现并通过专项 IT |
| Slice 4：接入现有 Job 创建和执行链 | 完成 | 新建、合流、worker claim、heartbeat、成功/失败/取消/stale/dead-letter 镜像已接入 |
| Slice 5：统一 lifecycle 与 HTTP actions | 完成 | lifecycle summary、ACL SQL 过滤、分页、cancel/retry 与旧 Job façade 已接入 |
| Slice 6：housekeeping、死信和恢复 | 完成 | stuck CAS、lease reclaim stage reset、generation supersede 和 bounded housekeeping 已实现 |
| Slice 7：测试、文档、发布门禁 | 完成 | 生命周期门禁 14/14、全量 gated PostgreSQL 24 套件/158 测试、账本专项 PostgreSQL 5/5、文档/锁/差异门禁均通过 |

## 实施记录

### 2026-10-13：开始实施

- 已核对 `main`、当前工作区状态和规划 `3/3`。
- 已复核 `EmbeddingJob`、repository、service、worker、dispatch、lifecycle 和 V1–V60 migration。
- 关键约束：不改旧 migration；不使用 `FOR UPDATE`、`SKIP LOCKED` 或 advisory lock；旧 Job API 保持可用。

### 2026-10-13：核心实现与门禁收敛

- 已完成 API/领域 DTO、Run/Stage repository/service/controller、V61 migration、配置绑定、Job
  创建/执行/镜像、lifecycle summary、ACL 过滤、cancel/retry 和 housekeeping。
- 已补齐双语 architecture/configuration/REST API/release-checklist/developer-reference/
  testing-guide，以及 gated IT 清单登记。
- 实施中发现的三个问题已修复：新 PostgreSQL IT 默认 Testcontainers 回退；gated runner 使用
  `mvn ... -pl spring-ai-rag-core -am`；旧 Job cancel façade 返回 repository 的更新快照；关闭
  document-processing 时 Run API 返回既有 `EMBEDDING_JOBS_DISABLED`，旧 Job 路径不受影响。

### 2026-10-13：最终验证收敛

- 最新 `./scripts/verify-document-lifecycle.sh` 通过 14/14：V61 生命周期 PostgreSQL 13 测试、
  外部同步 HTTP E2E、本地版本恢复 HTTP E2E、后端全量、WebUI、Mock Playwright、项目文档和
  `git diff --check` 均通过；后端全量为 7718 运行、0 failures、0 errors、158 skipped。
- 最新 `./scripts/verify-gated-it.sh` 通过全部 24 个 PostgreSQL 套件，共 158 个测试，
  `failures=0/errors=0/skipped=0`；每个空库均从 V1 迁移到 V61。
- 一次直接运行带 Testcontainers 的定向命令曾因 Docker Hub 拉取 `ryuk` 时 TLS handshake timeout
  中止；随后使用仓库门禁的 `TESTCONTAINERS_RYUK_DISABLED=true` 和本地
  `postgres:16-pgvector` 镜像完成了全量验证。这是环境网络路径差异，不是代码失败。

### 2026-10-13：实现后检查发现并发证据缺口

- 第 3 轮实现后只读检查发现：当前新增 `DocumentProcessingRunPostgresIntegrationTest` 只有五阶段
  成功路径，尚未直接证明规划验收矩阵要求的 Run/Stage 并发创建、lease reclaim、取消、supersede、
  stuck、terminal mirror 和重试终态矩阵；这属于测试证据缺口，不降低实现契约。
- 已将实现后检查计数重置为 `0/3`；下一步补充真实 PostgreSQL 的高价值状态矩阵和关闭账本开关的
  服务契约测试，随后重新完成连续三轮只读检查。

### 2026-10-13：补齐专项状态矩阵

- 新增 `DocumentProcessingRunPostgresIntegrationTest` 的 4 个状态矩阵用例，连同原成功路径共 5 个
  用例，覆盖并发合流、lease reclaim 后 stage reset、旧 worker fencing、取消、supersede、stuck、
  terminal mirror 和 retry exhausted/dead-letter。
- 修复两个终态约束遗漏：`markSuperseded`、`markStuck` 将 Run 置为 `STALE` 时显式清空
  `next_retry_at`，满足 V61 的 `ck_rag_processing_run_retry_time`。
- 专项命令 `TESTCONTAINERS_RYUK_DISABLED=true mvn -pl spring-ai-rag-core -am
  -Ddocument-processing.it.enabled=true -Dtest=DocumentProcessingRunPostgresIntegrationTest
  -Dsurefire.failIfNoSpecifiedTests=false test` 通过：5 tests，0 failures，0 errors，0 skipped。

### 2026-10-13：实现后第 2 轮检查修复开关边界

- 第 2 轮只读检查发现：账本关闭时，旧 Embedding Job 创建路径仍可能调用
  `markSuperseded`，从而继续推进已有 Processing Run；这与关闭开关后的 fail-closed 账本契约不一致。
- 修复 `DocumentProcessingRunService.markSuperseded`：`rag.document-processing.enabled=false` 时直接
  返回 0，不修改 V61 账本；旧 Job API 仍保持自己的可用性。
- 新增 `DocumentProcessingRunServiceTest.disabledLedgerDoesNotAdvanceSupersededRuns`，并与旧
  `EmbeddingJobServiceTest` 一起验证：9 个测试，0 failures、0 errors、0 skipped。
- 按严格流程，发现并修复问题后实现后检查计数重置为 `0/3`，从第 1 轮重新开始。

## 验证记录

| 时间 | 命令/范围 | 结果 |
|---|---|---|
| 2026-10-13 | 实施前 `git status --short --branch` | `main`，工作区干净 |
| 2026-10-13 | `mvn -pl spring-ai-rag-core -am -DskipTests compile` | 通过 |
| 2026-10-13 | `mvn -pl spring-ai-rag-api -am -Dtest=DocumentProcessingRunOriginTest -Dsurefire.failIfNoSpecifiedTests=false test` | 1 通过 |
| 2026-10-13 | `./scripts/verify-gated-it.sh DocumentProcessingRunPostgresIntegrationTest` | 真实 PostgreSQL，V1–V61，1 通过 |
| 2026-10-13 | `./scripts/verify-embedding-jobs.sh` | 6 步通过；专项 PostgreSQL 通过 |
| 2026-10-13 | `./scripts/verify-document-lifecycle.sh`（首次） | 14 步中完整后端发现 1 个旧 façade 回归；已修复，不作为最终证据 |
| 2026-10-13 | `mvn -pl spring-ai-rag-core -am -Dtest=EmbeddingJobQueryPageTest -Dsurefire.failIfNoSpecifiedTests=false test` | 5 通过 |
| 2026-10-13 | `./scripts/verify-document-lifecycle.sh`（修复后） | 14 步全部通过；full backend 7718 运行、0 failures、0 errors、158 skipped |
| 2026-10-13 | `./scripts/verify-project-docs.sh` | 16 项通过 |
| 2026-10-13 | `./scripts/verify-no-pessimistic-locks.sh` | 通过 |
| 2026-10-13 | `git diff --check`、gated 脚本 `bash -n` | 通过 |
| 2026-10-13 | `./scripts/verify-document-lifecycle.sh`（最新修复后） | 14/14 通过；后端 7718 运行、0 failures、0 errors、158 skipped；生命周期 PostgreSQL 已迁移至 V61 |
| 2026-10-13 | `./scripts/verify-gated-it.sh`（全量） | 24 个套件、158 个测试，0 failures、0 errors、0 skipped；所有空库迁移至 V61 |
| 2026-10-13 | `DocumentProcessingRunPostgresIntegrationTest`（补齐状态矩阵后） | 5 个测试，0 failures、0 errors、0 skipped；真实 PostgreSQL，空库迁移至 V61 |
| 2026-10-13 | 实现后连续三轮只读检查 | 3/3 连续无修改通过：事务/CAS/lease/迁移、API/ACL/兼容/开关、测试/文档/发布/回滚/交付范围均通过 |

## 实现后检查计数

- 当前连续计数：`3/3`。
- 检查要求：只读复核事务/CAS/lease/迁移、API/ACL/兼容/开关、测试/文档/发布/回滚；无问题才递增，
  发现实质问题必须修复并重置为 `0/3`。
- 当前结果：专项证据缺口已关闭，实现后 `3/3` 已完成；最终门禁通过后执行 Git 交付。

## 恢复入口

中断后先读本文件和对应规划的 Slice 进度，再检查 `git status`。不要使用 `git reset --hard`、`git stash`、
`git clean` 或覆盖已有 WIP；从最后一个已通过退出条件的切片继续。
