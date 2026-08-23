# 加权 RRF 检索融合进度

> **状态**：交付准备完成；生产实现、聚焦测试、PostgreSQL 集成、Maven、WebUI、隔离端口真实全栈验收和实现连续三轮收敛审查均已通过，待提交并合并到 `main`
>
> **开始日期**：2026-08-23
>
> **当前分支**：`codex/weighted-rrf-retrieval-20260823`
>
> **当前 worktree**：`/Users/yangjiefeng/Documents/wubuku/spring-ai-rag`
>
> **代码基线**：`main` / `origin/main` @ `7dc7ab9d`
>
> **实施规划**：[2026-08-23_WEIGHTED_RRF_RETRIEVAL_PLAN.md](2026-08-23_WEIGHTED_RRF_RETRIEVAL_PLAN.md)

本文件是跨会话恢复账本。每次取得关键进展时，先更新本文件，再进入下一阶段；它不替代
代码、迁移或双语长青文档。

## 1. 当前阶段

| 阶段 | 状态 | 说明 |
|---|---|---|
| 最新 main 与候选功能探索 | 已完成 | 已确认当前真实高价值缺口是融合实现与 RRF 文档不一致 |
| 上一轮账本规划归档 | 已完成 | 已归档为 `2026-08-23_TOKEN_USAGE_LEDGER_*` |
| 新规划编写 | 已完成 | 已冻结加权 RRF、分数尺度、稳定排序和范围 |
| 规划连续审查 | `3/3` | 2026-08-23 完成连续三轮无实质问题审查 |
| 生产代码实施 | 已完成 | `RetrievalUtils` 已切换到缩放加权 RRF，HTTP 权重有限值校验已补齐 |
| 聚焦单元测试 | 已完成 | `RetrievalUtilsTest` 与 `RagSearchControllerTest` 共 83 项通过 |
| PostgreSQL 集成测试 | 已完成 | `TESTCONTAINERS_RYUK_DISABLED=true DOCKER_API_VERSION=1.40` 下真实 pgvector PostgreSQL、Flyway V1–V48 与真实服务链路通过 1/1 |
| 前端类型/单元/构建 | 已完成 | `tsc`、Vitest 218/218、Vite production build 通过 |
| 基本集成硬门槛 | 已完成 | 串行 `mvn clean compile test-compile`、PostgreSQL 集成 1/1、WebUI `tsc`/Vitest/build、核心 Mock Playwright 14/14 均通过 |

## 2. 已冻结决策

- 固定 `RRF_K=60`，不新增配置项。
- 使用 `(RRF_K + 1)` 缩放加权 RRF，保持融合 `score` 的可读量级。
- rank 使用每个 provider 返回列表的 1-based 顺序；跨通道同一 identity 累加贡献。
- 保留 `vectorScore`、`fulltextScore` 和 provenance 原始字段。
- 对融合分数相同的结果按 `documentId`、`chunkIndex` 稳定排序。
- 只修改检索融合、相关测试、双语语义文档和权重配置说明；不用 migration、WebUI、
  权限或用量账本。

## 3. 规划审查记录

规划审查计数因连续检查发现归档迁移后的长青文档相对链接错误，以及第 2 轮发现权重上界和
非有限 provider 分数的契约表述不够精确，均已修正文档并重置为 `0/3`。本次复核又发现
兼容性小节残留旧的 `0..1` 上界表述，也已同步修正并重置为 `0/3`。每轮检查固定范围见
[规划文档 §7](2026-08-23_WEIGHTED_RRF_RETRIEVAL_PLAN.md#7-规划审查与实现收敛)。无问题轮次只在达到
`3/3` 后一次性写入最终记录，避免在连续无修改证据期间改写规划正文。

本次修正明确：两个权重独立限制在 `0..1`、不强制归一化，缩放后融合分数上界为权重之和；
provider 非有限原始分数按有限值优先、稳定 tie-break 排序，且不得污染融合分数。另将
配置双语文档中“系统自动归一化”的错误表述纳入本轮同步修正，改为“建议总和为 `1.0`，
服务不会自动归一化”。

第 3 轮检查又发现验收矩阵没有把合并后隔离端口的 `scripts/dev.sh` 与真实前后端
Playwright 写成可执行步骤，且相关 Maven 测试命令未使用 `-am`；规划已补充隔离端口
联调和 `-am -Dsurefire.failIfNoSpecifiedTests=false`，审查计数重置为 `0/3`。

随后第 3 轮复核发现 HTTP GET 权重校验允许 `NaN`/无穷值绕过比较，可能破坏“融合分数
必须有限”的算法边界。规划已补充有限权重契约、Controller 文件范围和回归测试要求，
审查计数保持重置为 `0/3`，后续必须重新完成连续三轮规划检查。

重新进行的第 2 轮检查又发现底层工具对绕过 HTTP 的非法权重缺少明确处理方式。规划已
冻结为 `IllegalArgumentException` 拒绝非有限或越界权重，并将该行为纳入单元测试；规划
审查计数再次重置为 `0/3`。

2026-08-23 规划最终检查结果：连续三轮检查均未发现新的正确性、兼容性、验证可信度或交付
风险问题，期间未修改规划正文；随后仅按工作流更新状态并记录校验值。最终规划 SHA-256：
`5984b9e4a26d11852a21f83ffe5ae6bc0c9321854ed5f0ed2f6f80f834e2902c`。
三轮范围依次为：目标闭环与非目标；算法/调用链/兼容性与测试可行性；文件范围、验收顺序、
隔离运行、回滚和 Git 交付。`./scripts/verify-project-docs.sh` 与 `git diff --check` 均通过。

## 4. 实施进度

规划通过后已完成以下实施切片：

1. 记录规划正文 SHA-256、`git diff --check` 和当前 Git 状态；
2. 已补齐 RRF 单元、Controller 边界和服务链路集成测试；
3. 已实现 `RetrievalUtils` 的 weighted/scaled RRF 和稳定排序；
4. 已更新 REST/架构/配置双语分数与权重语义；
5. 已完成后端、PostgreSQL、Maven、WebUI、核心 Mock Playwright 和文档门禁；
6. 规划状态文字已与实际实现同步；实现连续三轮收敛审查从 `0/3` 重新开始；
7. 审查完成后跟进 `origin/main`，按合并后基线复验，合入 `main` 并 push。

## 5. 已完成验证

- 规划前置文档门禁：`./scripts/verify-project-docs.sh` 通过。
- 规划 SHA-256：`5984b9e4a26d11852a21f83ffe5ae6bc0c9321854ed5f0ed2f6f80f834e2902c`。
- 测试先行基线：旧融合实现按预期被 RRF、稳定排序、非法权重和非正 limit 用例捕获。
- 生产实现后的聚焦测试：`mvn -pl spring-ai-rag-core -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest=RetrievalUtilsTest,RagSearchControllerTest test`，83 项通过；随后测试证据修复后再次通过 83 项。
- PostgreSQL 集成测试首次尝试未形成业务结论：Testcontainers 在拉取 `testcontainers/ryuk:0.11.0`
  时因 Docker Hub TLS 代理证书错误失败；不将该次中断运行记为通过或失败的业务测试。
- PostgreSQL 集成测试复验通过：`TESTCONTAINERS_RYUK_DISABLED=true DOCKER_API_VERSION=1.40`
  配合 `-Dapi.version=1.40 -Dhybrid-rrf.it.enabled=true`，`HybridRetrieverRrfPostgresIntegrationTest`
  通过 1/1；真实容器为 `pgvector/pgvector:pg16`，Flyway 空库迁移成功到 V48。
- WebUI 基本验证通过：`npx tsc -b --pretty false`、`npm run test:run`（29 files / 218 tests）、
  `npm run build`。
- 测试证据修复后串行 `mvn clean compile test-compile` 通过；此前并发 Maven 争用
  `target` 的失败不作为代码失败结论。
- 测试证据修复后 PostgreSQL 集成复验通过：`HybridRetrieverRrfPostgresIntegrationTest`
  1/1，Testcontainers `pgvector/pgvector:pg16`，Flyway V1–V48。
- 测试证据修复后核心 Mock Playwright 复验通过：`e2e/search.spec.ts` 与
  `e2e/navigation.spec.ts` 共 14/14；只使用 DOM、请求/响应和自动化断言。此前一次
  `vite preview` 未监听端口导致的 14 项连接拒绝仅记录为环境启动失败，不作为业务测试
  结论；修正就绪探测与退出码处理后复验通过。

当前恢复入口：上一处权重边界修复后的聚焦回归已通过；现在重新执行完整基本门槛。
基本门槛完成后，从 `0/3` 开始实现连续三轮收敛审查，再同步 `origin/main` 并执行合并后验收。
实现审查当前计数：`0/3`。

## 5. 实现审查记录

基本集成硬门槛通过后，按规划固定的三轮范围执行。任何实质修复都将计数重置为 `0/3`，
并重跑受影响的验证。

第二轮审查已检查 `HybridRetrieverService` 的调用兼容性、候选召回量级、REST/Chat 配置
入口、rerank 输入和正式文档契约。生产实现未发现实质正确性或兼容性问题；但发现原有
“provider 原始分数尺度不影响排序”单测每个通道只有一个候选，旧的最大值归一化实现也能
通过，验收证据不足。该测试需要改为保持通道名次不变、只改变分数间距，并确认 RRF 排序
与分数保持不变；测试修复后实现审查计数重置为 `0/3`，必须重新通过基本门槛并开始三轮
连续无问题检查。

基本集成硬门槛已重新通过，审查计数为 `0/3`。第一次 Playwright 启动因 `mvn clean`
删除了临时 `target` 日志目录而未执行测试；创建目录后复跑通过，不属于产品或测试失败。

实现第一轮审查发现 `docs/hybrid-search-enhancement-plan.md` 仍保留“权重隐式、未来再增加
显式权重”和未加权 RRF 公式的旧设计表述，与当前生产实现及配置文档冲突。该文档需要
同步为固定 `K=60` 的缩放加权 RRF 语义；修正文档后实现审查计数重置为 `0/3`，已重新通过
文档门禁，下面重新执行基本门槛后开始三轮连续无问题检查。

第 3 轮审查发现 Chat/OpenAI 快照使用的 `RetrievalOptions` 仅通过 `<`/`>` 判断权重范围，
`NaN` 和无穷值可能绕过校验；同时 `RetrievalResult` 的 OpenAPI 描述没有明确混合模式下
的缩放加权 RRF 语义。两处均属于本轮权重契约的兼容性/可观测性缺口，已纳入修复范围；
修复后实现审查计数重置为 `0/3`，必须重新通过受影响基本门槛并重新完成连续三轮检查。

已完成该修复：`RetrievalOptions` 现在拒绝非有限权重，`RetrievalResult.score` 的 OpenAPI
说明已同步 weighted/scaled RRF 语义，并新增 `RetrievalOptionsTest`。受影响聚焦回归
`125/125` 通过；接下来重新执行 PostgreSQL 集成、完整 Maven 编译和前端基本门槛，然后
从 `0/3` 开始实现收敛审查。

## 6. 修复后硬门槛执行记录

| 检查项 | 状态 | 证据 |
|---|---|---|
| 受影响聚焦回归 | 已完成 | `125/125`，见上文 |
| 完整 Maven 编译 | 已完成 | `mvn clean compile test-compile`，Reactor 全部 SUCCESS |
| PostgreSQL/pgvector 集成 | 已完成 | `HybridRetrieverRrfPostgresIntegrationTest` `1/1`，Flyway V1–V48 |
| WebUI 类型、单元、生产构建 | 已完成 | `tsc`、Vitest `29/218`、Vite build |
| 核心 Mock Playwright | 已完成 | `search.spec.ts`、`navigation.spec.ts` `14/14` |
| 文档与并发锁门禁 | 已完成 | 两项脚本均通过 |

基本门槛完成后，实现连续三轮收敛审查从 `0/3` 开始。审查只处理本任务内会影响正确性、
检索质量、响应性能、兼容性、验证可信度或交付安全的问题；任何生产代码或测试实质修复
都会将计数重置为 `0/3`，并重新执行受影响门槛。当前实现审查已连续 `3/3` 无实质问题。

## 7. 实现收敛审查记录

1. 第 1 轮：核对 RRF 公式、权重与 provider 分数边界、重复 identity、稳定排序、双通道
   累加及结果字段保留；未发现实质问题。
2. 第 2 轮：核对 `HybridRetrieverService` 候选召回量级、融合调用、REST/Chat 配置入口、
   降级路径和 rerank 交互；未发现实质问题。
3. 第 3 轮：核对测试覆盖、真实 PostgreSQL 证据、双语文档、启动/回滚边界和 Git 交付
   路径；未发现实质问题。

三轮均为只读检查，期间未修改生产代码或测试；实现收敛计数达到 `3/3`。接下来先 fetch
并检查 `origin/main`，如有新提交则 merge 到特性分支，再按合并后基线重新验收。

## 8. 合并前最终基线

- `git fetch origin` 已完成。
- 当前特性分支 HEAD：`7dc7ab9d9d689ba89ac936e42383ab99b1aaebeb`。
- `origin/main`：`7dc7ab9d9d689ba89ac936e42383ab99b1aaebeb`。
- `git rev-list --left-right --count HEAD...origin/main`：`0 0`，无需 merge。
- 当前实现未修改数据库 schema，不需要新增 Flyway migration 或回滚迁移。
- 下一步按交付工作流重新执行后端 PostgreSQL/Maven、前端 Mock 和隔离端口真实全栈验证。

## 9. 合并后最终验收记录

| 检查项 | 状态 | 证据 |
|---|---|---|
| 合并后 Maven 编译 | 已完成 | `mvn clean compile test-compile`，Reactor 全部 SUCCESS |
| 合并后文档与锁门禁 | 已完成 | `verify-project-docs.sh`、`verify-no-pessimistic-locks.sh` |
| 合并后 WebUI 类型、单元、生产构建 | 已完成 | `tsc`、Vitest `29/218`、Vite build |
| 合并后 PostgreSQL/pgvector 集成 | 已完成 | `HybridRetrieverRrfPostgresIntegrationTest` `1/1`，Flyway V1–V48 |
| 合并后核心 Mock Playwright | 已完成 | `search.spec.ts`、`navigation.spec.ts` `14/14` |
| 合并后隔离端口真实全栈启动 | 已完成 | `scripts/dev.sh`，后端 `18083`、前端 `15175`，健康/HMR/代理校验通过 |
| 合并后隔离端口真实全栈 Playwright | 已完成 | 同一 shell 生命周期内 `14/14`；首次单独命令的连接拒绝确认为会话回收后台子进程，非产品失败 |

合并后固定验收序列已完成。隔离栈使用 `postgresql` profile、后端 `18083`、前端 `15175`；
`scripts/dev.sh` 的健康检查、Vite HMR、代理身份校验和真实全栈 Playwright 均通过，结束后
已执行 `./scripts/dev.sh --stop` 并确认由本 worktree 启动的端口已释放。未执行真实 Chat LLM
调用：本轮只改检索融合和检索入口边界，没有修改 Chat provider 或生成路径。

## 10. 交付前状态

- 规划审查：`3/3`。
- 实现审查：`3/3`。
- 合并后固定验收序列：已完成。
- 下一步：重新检查完整工作区 diff，提交全部修改，push 特性分支，合并到 `main` 并 push。
