# 有界的独立 rerank 候选池进度

> **状态**：生产实现与自动化验收进行中
>
> **开始日期**：2026-08-23
>
> **当前分支**：`codex/weighted-rrf-retrieval-20260823`
>
> **当前 worktree**：`/Users/yangjiefeng/Documents/wubuku/spring-ai-rag`
>
> **代码基线**：本地 `main` / `origin/main` @ `802cd991`
>
> **实施规划**：[NEXT_HIGH_VALUE_FEATURES_PLAN.md](NEXT_HIGH_VALUE_FEATURES_PLAN.md)

本文件是跨会话恢复账本，不替代代码、迁移或双语长青文档。每次取得关键进展时，先更新
本文件，再进入下一阶段。规划完成后本文件记录规划审查结果；实施开始后继续记录代码切片、
验证证据、审查计数和恢复入口。

## 1. 当前阶段

| 阶段 | 状态 | 说明 |
|---|---|---|
| 代码、长青文档和上一轮归档探索 | 已完成 | 已核对当前加权 RRF 基线、rerank provider、所有检索调用链和文档生命周期 |
| 上一轮 plan/progress 归档 | 已完成 | 已归档为 `2026-08-23_WEIGHTED_RRF_RETRIEVAL_*`，并修正归档后的相对链接 |
| 新规划编写 | 已完成 | 已冻结有界 candidate pool、最终数量语义和 Agent/Evaluation 漏接修复边界 |
| 规划连续审查 | `3/3` | 已完成三轮固定范围、只读复核；期间未再修改规划正文。最终规划 SHA-256 记录于本文件 §6 |
| 文档门禁、commit、push | 已完成 | 规划 `3/3`、文档门禁 `10/10`、commit `8a16a177` 和特性分支 push 已完成 |
| 生产代码实施 | 已完成，待合并后复验 | 配置、候选池、最终数量保护、Agent/Evaluation rerank 和 trace/cache 语义已实现；预合并硬门槛与连续 `3/3` 审查已完成 |

## 2. 已冻结的关键决策

- 本轮只有一个功能主题：有界的独立 rerank 候选池。
- `candidateLimit = max(requestedMaxResults, configuredCandidateLimit)`。
- 配置默认 `20`，setter/绑定边界为 `1..100`；推荐环境变量为
  `RAG_RERANK_CANDIDATE_LIMIT`。
- 只有请求启用 rerank、全局启用 rerank 且 provider 不是 `none`/`noop`/`off` 时扩大；
  纯向量/全文不可用时只扩大仍可用的向量分支，不额外强行启用全文。
- hybrid 分支延续现有每通道 `fusionLimit * 2` 召回比例，RRF 融合输出最多 `fusionLimit`。
- `maxResults` 仍是最终 Search/Chat/tool/record/evaluation 对外数量，不是候选池数量。
- `KnowledgeSearchTool` 和 `EvaluationCaseExecutor` 需要接入现有 `ReRankingService`，避免
  只扩大候选而不完成最终 rerank。
- 不改数据库、公开请求字段、权限、用量账本、WebUI 业务流程和新的 rerank provider。
- 旧版 GET Search 继续支持 `limit=1..1000`，因其显式 `useRerank=false`，不扩大候选池；
  candidate-limit 的 `1..100` 上界只适用于启用 rerank 的受管 `RetrievalConfig` 调用链。
- 旧 `HybridSearchAdvisor` 继续只负责检索，必须显式传入 `useRerank=false`；后续
  `RerankAdvisor` 的独立最终 `maxResults`、advisor 顺序和成本边界保持不变。

## 3. 已核对的调用链与风险

1. `ProjectDocumentRetriever` → `ProjectRerankPostProcessor` 已有 rerank，候选池扩大后
   只需确认输入/输出数量。
2. `KnowledgeSearchTool` 当前直接把 `HybridRetrieverService` 结果做 tool JSON，缺少
   `ReRankingService`；这是本轮必须修复的 Chat `AGENT` 风险。
3. `RagSearchController` POST、`JsonRecordService` 已有 rerank；GET 明确关闭 rerank，
   不应触发候选池。
4. `EvaluationCaseExecutor` 当前只执行检索，虽然 variant schema 支持 `rerank`，因此
   本轮必须明确其最终评估语义，不能让 candidate pool 伪装成最终结果。
5. 旧 `HybridSearchAdvisor` 后接 `RerankAdvisor`，两者的检索 limit/最终 limit 分离；本轮
   通过显式 `useRerank=false` 防止共享检索器误扩大候选池，不修改 advisor 顺序。
6. `HttpRerankProvider` 会把全部候选放入 `documents`，`top_n` 保持最终数量；候选上限
   是本轮的成本边界。

## 4. 规划审查记录

规划审查曾因以下实质缺口多次重置；最终已连续完成 `3/3` 轮无修改复核：

- 实现后的双语 `configuration`、`quality-defaults`、`troubleshooting` 和 `architecture`
  文件范围不完整；
- `KnowledgeSearchTool` 的 tool-level `limit` 没有写入传给 service 的 effective config，
  可能被会话级 `maxResults` 覆盖。
- `ReRankingService` 没有统一防御性截断 provider 的超量返回，无法仅靠 provider 契约证明
  所有调用方最终不超过 `maxResults`。
- Chat `maxUniqueSources` 默认小于 candidate pool 上限，中间候选若先写入 trace 可能使
  rerank 选中的后段结果没有 citation；已补充候选阶段不占 budget、最终结果优先于本次
  未登记候选、既有 source 不驱逐且 citation ID 稳定的规则。
- `KnowledgeSearchTool` 的 query cache 没有记录可覆盖的最终数量，大小请求可能互相泄漏
  或被较小缓存永久限制；已补充按请求数量复用/截断/重新检索规则。
- `ReRankingService` 对 provider `null` 结果没有统一错误语义；已补充显式失败与调用链降级
  断言。
- 规划的非目标曾笼统写成“不实现缓存”，与必须修复的既有 attempt-local query cache 边界
  冲突；已改为禁止新增跨请求/持久化缓存，仅允许修复既有缓存的数量覆盖语义。
- 当前 trace 的 `recordOutcome` 会立即占用 source/citation 预算；已补充候选阶段记录入口
  与最终结果记录入口，并将 `ProjectDocumentRetriever`/Agent tool 的调用顺序纳入实施范围。
- 仅修改 `RetrievalTraceCollector` 不足以落地候选/最终记录时序；已将
  `ProjectDocumentRetriever`、`ProjectRerankPostProcessor` 及其测试列入范围。
- Tool cache 不能用缓存结果条数推断覆盖范围；已要求缓存保存 `requestedLimit` coverage
  并据此决定命中、截断或重新检索。
- 旧版 GET Search 的 `limit=1000` 与本轮 candidate-limit `1..100` 的范围边界未在规划中
  明确；已补充兼容说明，避免实施时错误收窄既有 GET 契约。
- 旧 Advisor 链未在规划中明确 `HybridSearchAdvisor` 的 config 标记；已补充显式
  `useRerank=false`、生产文件范围和 AdvisorChainIntegrationTest 覆盖，避免把检索 limit
  错当成 rerank 最终 limit。
- 原先没有说明候选阶段 outcome 是否会与最终 outcome 在父 trace 中重复计数；已冻结为同一
  attempt 内由最终 outcome 替换候选阶段记录。
- 原先把 HTTP provider 内部 fallback 与调用链外层 degraded/error 诊断混为一谈；已明确
  provider 内部超时/空响应/非法 index 继续 heuristic fallback，只有异常或 null 冒泡到
  `ReRankingService`/调用链时才记录外层 degraded/error。
- 隔离 runtime 验收命令原先只激活 `postgresql`，会保留全局 rerank 关闭的基础配置，无法
  证明 candidate pool；已改为 `postgresql,prod`，同时保留 PostgreSQL 数据源与生产质量
  默认 rerank。

最终三轮无修改复核摘要：

1. 需求闭环与自包含性：核对目标、非目标、默认决策、source/citation/cache 语义、恢复入口；
   未发现问题。
2. 代码与契约可行性：交叉核对检索服务、配置绑定、provider fallback、Agent/Evaluation、
   GET/旧 Advisor、scope/filter、并发/超时和成本边界；未发现问题。
3. 实施与验证可交付性：核对文件范围、测试矩阵、PostgreSQL、前端 DOM/网络验收、隔离
   `scripts/dev.sh`、回滚和 Git 交付顺序；未发现问题。

三轮复核均未修改 `NEXT_HIGH_VALUE_FEATURES_PLAN.md`；规划检查计数达到 `3/3`。

固定检查范围见
[规划文档 §7](NEXT_HIGH_VALUE_FEATURES_PLAN.md#7-规划检查与实现收敛)：

1. 需求闭环、自包含性、非目标和默认决策；
2. 代码调用链、配置/数据/API/并发/失败语义和兼容性；
3. 文件范围、验收矩阵、隔离运行、回滚和 Git 交付。

发现实质问题时必须修改规划并将计数重置为 `0`；无问题轮次不改 plan/progress 正文，
避免破坏连续三轮无修改证据。达到 `3/3` 后才记录最终规划检查摘要和 SHA-256。

## 5. 实施恢复入口

规划交付后，下一位 Agent 应按以下顺序恢复：

1. 先读取本文件、规划 §3/§4/§5，并核对 `git status`、当前 HEAD 和 `origin/main`；
2. 在 `RagRerankPropertiesTest`、检索服务候选池测试、`HybridSearchAdvisorTest`、
   `KnowledgeSearchToolTest`、`EvaluationCaseExecutorTest` 和 `ReRankingServiceTest` 中
   一次性补齐验收断言；
3. 修改共享检索 limit，先固定旧 Advisor 的 `useRerank=false`，再接入两条漏接 rerank 链路；
4. 先运行聚焦后端和 PostgreSQL 集成，再执行 `mvn clean compile test-compile`；
5. 执行 WebUI tsc/Vitest/build/核心 Mock Playwright，再按需要启动隔离全栈；
6. 基本门槛全部通过后从实现审查 `0/3` 开始，严格按规划 §7.2 收敛；
7. 若 `origin/main` 有新提交，先 merge 到特性分支，按合并后固定顺序完整复验，再合并
   `main` 并 push。

### 5.1 2026-08-23 实施进展

- 当前实现分支仍为 `codex/weighted-rrf-retrieval-20260823`，基线提交为 `8a16a177`。
- 第一轮代码梳理已完成：核对了 `RagRerankProperties`、`ReRankingService`、
  `HybridRetrieverService`、`KnowledgeSearchTool`、`EvaluationCaseExecutor`、
  `HybridSearchAdvisor`、`ProjectDocumentRetriever`、`ProjectRerankPostProcessor` 和
  `RetrievalTraceCollector`，确认候选池与最终 rerank 的缺口与规划一致。
- 已完成第一批生产实现：`candidate-limit` 配置与边界、共享检索候选池、旧 Advisor 的
  `useRerank=false`、`ReRankingService` 最终数量/null 防御、Agent/Evaluation rerank 接入、
  候选阶段 trace 与 coverage-aware tool cache。
- 已通过 `mvn -pl spring-ai-rag-core -am -DskipTests test-compile`；聚焦测试首轮因旧
  mock 契约和测试 matcher 缺失失败，修正后再次通过 52 tests。
- 已补齐候选池条件矩阵、tool 后段 citation/cache、Evaluation 和 provider 防御测试；
  最新链路测试观察到 `13 tests` 全部通过，Maven 报告阶段已结束，待读取完整门槛结果。
- 当前下一步骤：完成四组双语正式文档同步，然后运行完整后端门槛、PostgreSQL 集成、
  前端门槛和隔离运行验证。
- 正式文档已同步到 `configuration*`、`quality-defaults*`、`architecture*` 和
  `troubleshooting*`；下一步先运行文档门禁、聚焦后端测试和 Maven 基本门槛。

## 6. 验证证据记录

当前处于实现验收阶段；所有证据均记录于本节和后续实施审查记录：

| 验证项 | 状态 | 证据 |
|---|---|---|
| 规划前代码/文档探索 | 已完成 | `HybridRetrieverService`、rerank provider、Agent/Evaluation/Search/JSON 调用链已核对 |
| 归档迁移链接修复 | 已完成 | 归档 plan/progress 的近距离链接已改为 archive 相对路径/同目录文件 |
| 规划三轮检查 | 已完成 | 固定范围连续 `3/3` 无修改；最终一轮完成后未再改 plan |
| `./scripts/verify-project-docs.sh` | 已通过 | 实现文档同步后 `10/10` 通过 |
| `./scripts/verify-no-pessimistic-locks.sh` | 已通过 | 未发现生产代码中的显式悲观锁或 advisory lock |
| `git diff --check` | 已通过 | 无 whitespace 错误 |
| 本轮聚焦后端测试 | 已通过 | `57 tests`, `0 failures`, `0 errors` |
| `mvn clean compile test-compile` | 已通过 | 五模块 `BUILD SUCCESS`；仅有既有弃用/unchecked 编译警告 |
| PostgreSQL/pgvector 集成矩阵 | 已通过 | 显式启用 `hybrid-rrf.it.enabled=true` 后，Testcontainers PostgreSQL 16 + Flyway V1–V48；`HybridRetrieverRrfPostgresIntegrationTest` `1/1` 通过；未启用开关的命令曾为 `0 tests`，不计入证据 |
| 前端 tsc | 已通过 | `npm run typecheck` 通过 |
| 前端 Vitest | 已通过 | `29 files`, `218 tests` 全部通过 |
| 前端生产构建 | 已通过 | `npm run build` 通过，Vite 产物生成成功 |
| 前端核心 Mock Playwright | 已通过 | Search/Navigation `14/14` 通过；仅使用 DOM、网络请求和自动化断言 |
| 隔离端口真实全栈 | 已完成 | 原隔离栈已通过 health/auth/proxy/Search curl；随后已停止并释放 `18083`、`15175`；未触碰其他项目占用的 `15174` |
| 真实 API Key Playwright | 不适用本轮结论 | `api-key-real.spec.ts` 进入真实栈但在既有列表行 DOM 定位处失败；临时 principal 已由 finally 撤销/删除，不作为本轮 rerank 证据 |
| 真实 Agent Chat Playwright | 环境限制 | `chat-real.spec.ts` 创建/嵌入探针成功，但 `/models` 无可用 `toolCalling=true` 模型；临时数据已确认清理 |
| 真实 retrieval goldenset/质量回归 | 部分通过 | goldenset `5/5` 用例 baseline/quality MRR、nDCG 均 `1.0000`；版本化回归在 `sofa-001` fixture embedding 阶段失败，记录为外部向量数据/embedding 依赖限制，未删除历史 fixture |

最终规划 SHA-256：

```text
79b892d095bbcd0e93bb0ce4b1f86d10cbdfa6ddb1fd90ae4e56e9b2538cc8ed
```

## 7. 实施审查记录

生产实现已完成，且已重新通过基本集成硬门槛。隔离真实运行已停止并释放
`18083`、`15175` 端口；前端 Mock Playwright 使用本项目 Vite 的 `15176`，完成后保留
其他项目占用的 `15174` 不动。误删事故已完成恢复评估，但 `document_id=1` 没有可信来源可恢复。
现在按规划固定范围执行三轮只读审查；
任何影响正确性、检索质量、响应成本、兼容性或验证可信度的实质修复都会把计数重置为
`0/3`，并重新运行受影响门槛。

### 7.1 2026-08-24 基本集成复验

- 文档门禁：`./scripts/verify-project-docs.sh`，`10/10` 通过。
- 并发规则与 whitespace：`verify-no-pessimistic-locks.sh`、`git diff --check` 通过。
- 后端聚焦：相关 rerank/candidate-pool/Agent/Evaluation/trace 测试 `58` 个全部通过。
- Maven：串行 `mvn clean compile test-compile`，五模块 `BUILD SUCCESS`。
- PostgreSQL：`TESTCONTAINERS_RYUK_DISABLED=true mvn ... -Dhybrid-rrf.it.enabled=true ...`，
  Flyway V1–V48，`HybridRetrieverRrfPostgresIntegrationTest` `1/1` 通过。
- WebUI：`npm run typecheck`、`npm run test:run`（`29 files / 218 tests`）、
  `npm run build` 全部通过。
- 核心 Mock Playwright：本项目 Vite 使用隔离端口 `15176`，Search/Navigation `14/14`
  通过；证据仅使用 DOM、请求和自动化断言。
- 说明：曾在未启动 WebUI 时误跑一次 Mock Playwright，`14` 项均为 `ERR_CONNECTION_REFUSED`；
  随后启动本项目 Vite 并以 `BASE_URL=http://127.0.0.1:15176/webui/` 重跑，最终以
  `14/14` 通过为准。

### 7.2 实现审查第 1 轮发现与修复

- 审查范围：候选池计算与配置边界、向量/全文 SQL limit、scope/filter、timeout、provider
  fallback 和各调用链的异常恢复。
- 发现问题：`RagSearchController` 的直接 Search rerank 异常分支在候选池大于请求
  `maxResults` 时，使用未截断的 `outcome.results()` 作为降级响应，可能让异常路径突破
  最终数量契约。
- 处理措施：异常降级时统一调用 `ReRankingService.limitResults`，再用截断后的列表创建
  degraded outcome 并返回；修改文件为
  `spring-ai-rag-core/src/main/java/com/springairag/core/controller/RagSearchController.java`。
- 结果：该实质修复使实现审查计数重置为 `0/3`；在重新完成受影响测试和基本集成门槛前，
  不采纳此前第 1 轮的“无问题”结论。

### 7.3 修复后门槛复验

- 新增 `RagSearchControllerTest.productionSearch_rerankFailure_truncatesCandidatePool`，
  直接覆盖候选池大于请求 `maxResults` 且 rerank 抛错的 Search 降级路径。
- 修复后聚焦测试：`79 tests`, `0 failures`, `0 errors`。
- 当前实现审查计数：`0/3`；基本集成门槛需在此修复后重新完整执行，不能沿用
  7.1 的合并前证据作为最终结论。

### 7.4 修复后基本集成硬门槛

- 文档门禁：`./scripts/verify-project-docs.sh`，`10/10` 通过。
- 并发规则：`./scripts/verify-no-pessimistic-locks.sh` 通过；`git diff --check` 通过；
  隔离端口 `18083`、`15175`、`15176` 均无残留监听。
- 后端编译：串行 `mvn clean compile test-compile`，API、Documents、Core、Starter
  和根项目全部 `BUILD SUCCESS`。
- 修复后聚焦后端：`79 tests`, `0 failures`, `0 errors`。
- PostgreSQL/pgvector：`TESTCONTAINERS_RYUK_DISABLED=true`、`DOCKER_API_VERSION=1.40`、
  `-Dhybrid-rrf.it.enabled=true`；Testcontainers PostgreSQL 16、Flyway V1–V48、
  `HybridRetrieverRrfPostgresIntegrationTest` `1/1` 通过。
- WebUI：`npm run typecheck`、`npm run test:run`（`29 files / 218 tests`）、
  `npm run build` 全部通过。
- 当前仍未完成：修复后核心 Mock Playwright、隔离端口真实全栈运行验证以及实现
  `3/3` 收敛审查。后续 §7.5 已补齐 Mock Playwright，§7.6 已补齐隔离真实全栈验证。

### 7.5 修复后实现审查第 2 轮发现

- 审查范围：Search/Chat/Agent/JSON/Evaluation 最终数量、旧 Advisor 两段式兼容、
  provider fallback、trace/citation/cache、HTTP 请求成本和既定验收矩阵覆盖。
- 发现问题：当前 `HybridRetrieverRrfPostgresIntegrationTest` 仍只证明上一轮 weighted
  RRF，没有用真实 PostgreSQL/pgvector 证明本轮 `candidate-limit` 在 rerank 有效时扩大、
  关闭时维持请求上限；`ProjectDocumentRetriever` 的候选阶段不占 citation budget 也缺少
  直接测试，`ProjectRerankPostProcessor` 尚未直接覆盖 provider 超量返回和异常降级的最终
  截断。这些都已在规划 §5.2/§6 中列为交付证据，属于验收可信度缺口。
- 处理措施：一次性补齐真实 PostgreSQL candidate-pool 开关矩阵、
  `ProjectDocumentRetrieverTest`、post-processor 成功/异常最终数量断言，并补强
  Evaluation rerank 异常传播测试；不扩大生产功能范围。
- 聚焦复验：新增测试夹具首次因 Mock outcome 缺少真实检索器会设置的
  `originalQuery` 导致缓存 coverage 断言失败；修正夹具后，受影响测试 `73/73` 通过。
  该失败不涉及生产代码。
- PostgreSQL 复验：显式启用 `hybrid-rrf.it.enabled=true` 后，Testcontainers PostgreSQL
  16、Flyway V1–V48，既有 weighted RRF 与新增 candidate-limit 开/关矩阵 `2/2`
  通过。
- 旧 Advisor 集成矩阵首次重跑时，`AdvisorChainIntegrationTest` 仍 Mock 四参数检索
  重载，导致 5 个用例返回默认空列表；已一次性迁移全部相关 stub/verify 到五参数重载，
  并直接断言 `useRerank=false`。修正后完整聚焦矩阵 `137/137` 通过。
- 后端基本门槛：`mvn clean compile test-compile` 五模块 `BUILD SUCCESS`；文档门禁
  `10/10`、禁止悲观锁检查和 `git diff --check` 均通过。
- 前端基本门槛：`npm run typecheck`、Vitest `29 files / 218 tests`、production build
  均通过；隔离 preview `15176` 上的 Search/Navigation 核心 Mock Playwright `14/14`
  通过，仅使用 DOM、可访问状态、网络请求和自动化断言。preview 已停止，端口已释放。
- 结果：实现审查计数保持 `0/3`。真实 PostgreSQL 用例和全部基本集成硬门槛重新通过后，
  才重新开始连续三轮限定范围审查。

### 7.6 修复后隔离真实全栈验证

- 在同一个持久 shell 中使用 `BACKEND_PORT=18083`、`FRONTEND_PORT=15175`、
  `SPRING_PROFILES_ACTIVE=postgresql,prod` 和临时环境 root credential 启动
  `scripts/dev.sh`；避免启动命令返回后子进程被执行环境提前清理。
- 后端 readiness 返回 `UP`；WebUI `/webui/search` 返回真实 SPA 入口；通过 Vite 代理访问
  `/api/v1/rag/auth/me`，确认 principal 为 `ENVIRONMENT_ROOT` 且包含
  `API_KEY_MANAGE`。
- 对既有 `GOLDENSET_DOC_*` 数据执行只读 GET Search，返回
  `GOLDENSET_DOC_RERANK` 且结果数量不超过请求上限；未创建、更新或删除数据库数据。
- 无 API Mock 的 Playwright 使用真实 WebUI 解锁，观察真实 `/api/v1/rag/search`
  请求与 `200` JSON 响应，断言请求携带内存 credential、结果包含
  `GOLDENSET_DOC_RERANK`，并确认对应标题和结果计数在 DOM 中可见；未使用截图验收。
- 验证完成后由同一 shell 调用 `scripts/dev.sh --stop`，`18083`、`15175` 均已释放。
- 当前基本集成硬门槛与真实全栈门槛均通过；实现审查从 `0/3` 开始。

### 7.7 实现审查重启后第 1 轮发现

- 审查范围：候选池计算、provider/fallback、scope/filter、SQL limit、超时和成本边界。
- 发现问题：`HybridRetrieverService.search(...)` 与
  `searchInScope(...)` 的无配置公共重载使用 `RetrievalConfig` 默认值
  `useRerank=true`。生产启用 rerank 时，这两个仅返回检索列表、不会自行执行最终
  rerank 的重载可能扩大候选池并把超过调用方 `limit` 的中间候选直接暴露给外部调用方。
- 处理决定：两个兼容重载显式使用 `useRerank=false`，保持历史“纯检索且最多返回
  limit”语义；增加直接回归测试证明生产开启 candidate pool 时它们仍使用原始 limit。
- 结果：属于兼容性和最终数量缺陷，审查计数保持 `0/3`；修复后重跑受影响测试和全部基本
  集成门槛，再重新开始三轮审查。

### 7.8 兼容重载修复后的基本集成硬门槛

- 聚焦后端矩阵增加兼容重载断言后 `138/138` 通过。
- PostgreSQL/pgvector：Testcontainers PostgreSQL 16、Flyway V1–V48，weighted RRF 与
  candidate-limit 开关矩阵 `2/2` 通过。
- `mvn clean compile test-compile`：API、Documents、Core、Starter 和根项目全部
  `BUILD SUCCESS`。
- 文档、并发和 whitespace：`verify-project-docs.sh` `10/10`、
  `verify-no-pessimistic-locks.sh`、`git diff --check` 全部通过。
- WebUI：`npm run typecheck`、Vitest `29 files / 218 tests`、生产构建全部通过。
- 核心 Mock Playwright：隔离 preview `15176` 上 Search/Navigation `14/14` 通过，
  未使用截图；完成后端口已释放。
- 隔离真实全栈：同一持久 shell 使用 `18083/15175` 和 `postgresql,prod` 启动
  `scripts/dev.sh`；readiness、静态 SPA、Vite proxy root identity 均通过。对现有 5 个
  goldenset 文档执行只读 POST Search，启用 rerank 且 `maxResults=1`，响应和公开
  retrieval trace 的最终 `resultCount` 均为 `1`。无 Mock Playwright 通过真实网络和 DOM
  确认 Search 返回并显示 `GOLDENSET_DOC_RERANK`；未使用截图。服务停止后
  `18083/15175` 已释放。
- 验收脚本曾尝试从公开 Search trace metadata 断言 rerank 候选输入数量，但现有 API
  契约只公开最终摘要，不暴露顶层 retrieval stage；该超出契约的断言已撤销。候选池数量
  继续由内部 outcome 测试和真实 PostgreSQL 集成矩阵证明，不修改诊断 API 范围。
- 当前基本集成与真实全栈门槛均通过；实现审查从 `0/3` 重新开始。

### 7.9 预合并连续三轮实现审查

兼容重载修复后的完整基本门槛与隔离真实全栈验证通过后，按固定且互不重叠的范围完成
三轮只读审查，期间没有修改生产实现、测试或正式文档：

1. 候选池计算、provider/fallback、scope/filter、向量与全文 SQL limit、timeout 和成本边界：
   未发现问题。
2. Search/Chat/Agent/JSON/Evaluation 最终数量、trace/citation、coverage-aware cache、JSON
   身份映射和 Evaluation 失败分类：未发现问题。
3. 测试证据、配置绑定、双语长青文档、运行时证据、回滚和 Git 交付完整性：未发现问题。

预合并实现审查计数达到连续 `3/3`。下一步先提交当前完整工作区，再 fetch 并把最新
`origin/main` merge 到特性分支；合并前的验收只保留为历史证据，最终结论必须来自合并后的
固定顺序完整复验和新一轮连续三轮审查。

## 8. 恢复账本：隔离验收数据清理事故

2026-08-24 隔离全栈验收的临时数据清理过程中，曾使用未按 TSV 行边界读取的 shell
循环处理删除参数，误把本地 PostgreSQL 中 `document_id=1`、预期 revision 为 `1` 的
文档删除请求发送到了后端。该请求返回 HTTP `200`；随后对目标 goldenset 文档的删除因
revision 参数不匹配返回 `409`，没有继续确认删除成功。

在完成恢复评估前，禁止继续执行任何批量删除，也禁止停止隔离服务。恢复评估顺序固定为：

1. 只读查询 `rag_document_versions`、`rag_audit_log` 中 `document_id=1` 的版本和审计记录；
2. 查询本地数据库备份、快照或可用的历史版本来源；
3. 若存在可信快照，优先通过项目已有文档写入/恢复路径重建并用只读查询验证；
4. 若没有可安全恢复的正文来源，记录为无法恢复，不凭测试 fixture 猜测内容；
5. 评估完成后，逐行读取并核验待清理的临时 goldenset 数据，禁止使用
   `for row in $(...)` 这类会按空白拆分 TSV 的循环；
6. 最后停止隔离服务，确认端口、进程、数据库临时数据和工作区状态。

恢复评估结果：`document_id=1` 的 API 查询为 `404`；版本表、文档审计记录和已知派生表
均没有该文档的残留；本地 PostgreSQL 数据卷没有可用备份/快照文件，项目 fixture、运行
日志和 shell 历史也没有该文档的正文来源。因此无法安全恢复，未执行猜测性重建。
数据库中仍保留的 `GOLDENSET_DOC_*` 文档创建于 2026-07-22，属于既有长期 goldenset，
不是本轮临时数据，不删除。此事故不改变本轮实现代码验收证据；后续结论继续明确区分
代码验证证据与本地测试数据事故。
