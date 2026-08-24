# rerank 后文档级证据去冗余进度

> **状态**：实现、完整验收、连续三轮审查与 main 基线同步已完成；待 Git 交付
>
> **开始日期**：2026-08-23
>
> **当前分支**：`feature/rerank-document-diversity-20260824`
>
> **当前 worktree**：`/Users/yangjiefeng/Documents/wubuku/spring-ai-rag`
>
> **代码基线**：本地 `main` / `origin/main` @ `0fd37b6d`
>
> **实施规划**：[2026-08-24_NEXT_HIGH_VALUE_FEATURES_PLAN.md](2026-08-24_NEXT_HIGH_VALUE_FEATURES_PLAN.md)

本文件是跨会话恢复账本。每次取得关键进展时，先更新本文件，再进入下一阶段。它不替代
当前代码、自动化测试或双语长青文档；实施完成后应与 plan 一起归档。

## 1. 当前阶段

| 阶段 | 状态 | 说明 |
|---|---|---|
| 最新 main 与文档生命周期核对 | 已完成 | `main`、`origin/main` 和上一轮交付均为 `0fd37b6d`；上一轮 plan/progress 已归档，稳定事实已进入双语长青文档 |
| 下一批候选探索 | 已完成 | 对比文档级去冗余、跨入口多查询扩展、`EACH_COLLECTION`、权限与用量账本 |
| 新规划编写 | 已完成 | 冻结 provider 完整排名、两阶段文档选择、默认 cap=2、回填和验收矩阵 |
| 规划连续审查 | `3/3` | 三轮固定范围、只读检查连续无实质问题；规划正文期间未修改 |
| 文档门禁、commit、push | 已完成 | 门禁通过；规划提交 `721cd157` 已推送到专用远端分支 |
| 生产代码实施 | 已完成 | 功能、测试、专项验收 runner 与双语长青文档已完成 |
| 基本集成硬门槛 | 已完成 | 最新完整专项 runner 为 22 项通过、0 失败、0 跳过 |
| 实现连续审查 | `3/3` | 三轮固定范围、只读检查连续无实质问题，期间未修改实现 |
| 跟进 main 与最终交付 | 进行中 | `origin/main` 未变化，无需合并；正在归档并执行 Git 交付 |

## 2. 已冻结的关键决策

- 本轮只实施 rerank 后文档级证据去冗余。
- 新配置 `rag.rerank.preferred-max-chunks-per-document`，默认 `2`、范围 `0..100`；
  `0` 明确关闭。
- provider 在已有有界候选池内返回完整有序排名；不增加 SQL、embedding 或 Chat LLM 调用。
- 配置值是多样化第一遍的软上限；候选文档不足时按 provider 原排名回填同文档 chunk，
  最终列表可以超过该值。
- 最终结果是 provider 排名的子序列，保持 score 顺序；数量为
  `min(finalLimit, providerRankedCount)`，不补齐 provider 自身欠量。
- null/blank `documentId` 不互相合并，避免错误丢弃未知身份候选。
- provider 异常的外层 degraded fallback 保持现有原候选截断，不执行新 cap。
- 只有全局 rerank、有效 provider、候选数和 candidate limit 都大于 final limit 且
  `0 < cap < finalLimit` 时才扩大 provider ranking depth。
- selector active 时，service 将 provider 输入和 ranking depth 一起限制到
  `candidate-limit <= 100`，避免直接调用方放大 HTTP 请求体。
- 设置 cap=`0` 可恢复当前 provider top N 行为。
- 跨入口 query expansion、`EACH_COLLECTION`、权限和用量账本不进入本轮。

## 3. 探索结论

1. 上一轮 candidate pool 已提供默认 20、最大 100 的安全候选上界。
2. 所有生产 rerank 调用方已经集中使用 `ReRankingService`，适合在共享层实现。
3. heuristic 的文本 diversity 是软分数且不知道 document identity；HTTP provider 不共享。
4. provider 当前按 final N 提前截断，因此必须先分离 ranking depth 与 final limit。
5. 两阶段选择后按原 index 输出，可同时保证优先文档覆盖、必要回填、结果数量和 score 顺序。
6. 现有 goldenset 每文档单 chunk，不能证明收益；需要真实 PostgreSQL 的确定性多 chunk
   集成夹具，并继续跑既有真实 embedding 回归防止退化。
7. 本轮不改前端，但 Search JSON/Chat sources 是共享契约，前端门槛仍适用。
8. 第一轮契约审查发现 `100` 不是所有调用方的绝对关闭值，且关闭/no-op 路径不应扩大
   provider 返回深度；已改为 `0` 关闭并冻结 selector 生效条件，检查计数保持 `0/3`。
9. 后续契约审查发现回填会突破绝对每文档上限，已将配置重命名为
   `preferred-max-chunks-per-document` 并明确为第一遍软上限，检查计数重置为 `0/3`。
10. provider/成本审查发现共享 service 不能假设所有直接调用方都传入不超过 100 项，
    已增加 selector active 时按 `candidate-limit` 同时限制 provider 输入和 ranking depth，
    检查计数重置为 `0/3`。
11. 数量语义审查发现 provider 自身可能少返回，已将选择器数量不变量精确为
    `min(finalLimit, providerRankedCount)`，不从未排名原候选伪造补齐，检查计数重置为 `0/3`。
12. 验收审查发现原方案没有冻结 `dev.sh` 多 chunk fixture 的可处置数据库边界，且
    `<1ms` 墙钟断言会形成脆弱门禁；已增加专用 acceptance runner、隔离 env/端口/数据库
    与清理规则，并改用确定性 O(n) 复杂度断言加运行时 p95/HTTP payload 观测，检查计数
    重置为 `0/3`。
13. runner 可实施性审查发现可处置数据库不能使用 goldenset `--skip-create`，临时 root key
    必须显式传给 real Playwright，且同一 worktree 的 `.dev` 状态不能覆盖已有运行栈；
    已冻结 fixture 创建、shell-only credential、启动前拒绝占用和多文档 KNOWLEDGE smoke
    规则，检查计数保持 `0/3`。
14. citation 审查发现 AGENT 多次检索会保留 attempt 内 first-seen citation ID，不能笼统
    声称每次最终列表都重新分配连续 `[S1]`、`[S2]`；已改为选择器不改 citation、首次检索
    按最终顺序分配、跨 tool call 保持稳定 ID，检查计数重置为 `0/3`。
15. 正式代码可行性审查发现外层异常策略并非所有调用链都降级：受管 Search、
    `ProjectRerankPostProcessor`、`KnowledgeSearchTool` 和 `JsonRecordService` 会回退，
    兼容 Search、Evaluation 与旧 Advisor 会传播异常；已按现状冻结分路径语义，并把
    `RetrievalTraceCollectorTest`、`RerankAdvisorTest` 加入聚焦矩阵，检查计数重置为
    `0/3`。
16. 重启后的代码可行性审查发现 selector 不能重新解释原始 provider 配置，否则 HTTP
    别名、未知名称回落 heuristic 和显式注入 no-op provider 会与实际执行对象分叉；已冻结
    使用规范化后的 `provider.getName()`，并增加相应测试矩阵，检查计数重置为 `0/3`。
17. 全范围预收敛发现当前 Search UI 不展示 `chunkIndex` 或原始数值 `score`，真实
    Playwright 不能要求 DOM 对这些字段逐项复现；已改为网络 JSON 断言完整检索字段，DOM
    断言标题、片段、展示顺序和结果计数，检查计数保持 `0/3`。
18. 算法预收敛发现“按原 index 输出”若保持无排序 O(n) 实现，清晰方案是第一遍选择、
    第二遍回填、第三遍按原顺序输出；已把过严的“两遍/每项最多两次”测试改为常数次线性
    扫描且总访问不超过 `3 * candidateCount`，检查计数保持 `0/3`。
19. 正式代码可行性审查发现独立 `demos/demo-component-level` 仍调用已移除的单参数
    rerank 构造器。实际执行 `mvn -f demos/demo-component-level/pom.xml clean
    test-compile -DskipTests` 证明基线共有 5 个 API 漂移编译错误，另外 4 个与本轮无关；
    该 demo 不在根 reactor。已把“所有生产调用方”限定为 core，并冻结本轮不修 demo、
    不恢复旧构造器、保持 SPI 签名不变，检查计数重置为 `0/3`。
20. 前端入口审查确认 WebUI Search 只调用 GET `/search`，而该入口明确
    `useRerank=false`；页面请求不能证明本功能。已改为 real Playwright 经 Vite proxy
    直接调用 POST Search 证明配置与 JSON，再用实际 Search 页面 GET 验证 DOM/代理兼容，
    检查计数重置为 `0/3`。
21. 验收 runner 认证审查发现 goldenset 只读取 `API_KEY/RAG_API_KEY`，不会自动读取
    临时 `RAG_ROOT_API_KEY`；root 模式下沿用 `.env` 的旧业务 key 可能被拒绝。已冻结
    goldenset 显式传 `API_KEY`、regression/LLM/Playwright 传 `RAG_ROOT_API_KEY`，均使用
    同一个 shell-only 临时 root key，检查计数重置为 `0/3`。
22. 最终测试矩阵核对发现 provider 别名和未知名称 fallback 已成为冻结契约，但聚焦清单
    未包含工厂测试；已把 `RerankProviderFactoryTest` 纳入一次性矩阵，检查计数保持
    `0/3`。

## 4. 规划检查规则

固定三轮范围：

1. 价值、目标/非目标、默认值、回填、score/order 和兼容语义；
2. provider、共享服务、调用链、identity、citation、异常降级、配置和成本；
3. PostgreSQL/质量/性能/前端/真实运行/LLM、文档、回滚和 Git 交付。

发现实质问题立即修改 plan 并把计数重置为 `0`。无问题轮次不修改 plan/progress。
达到连续 `3/3` 后一次性记录三轮摘要和 plan SHA-256。

## 5. 最终规划检查记录

冻结规划 SHA-256：
`3f82f3b25e5998a6f5d6e454f59b5f8b8bbe6eccd9412f1b360f90f2f55179db`。

| 轮次 | 检查时间 | 固定范围 | 发现问题 | 处理措施 | 结果 |
|---|---|---|---|---|---|
| 1 | 2026-08-23 20:03 UTC | 价值、目标/非目标、默认值、回填、score/order 与兼容语义 | 无 | 无修改 | 连续计数 `1/3` |
| 2 | 2026-08-23 20:04 UTC | provider/SPI、共享服务、core 调用链、identity、citation、分路径失败语义、既有测试与 demo 边界 | 无 | 无修改 | 连续计数 `2/3` |
| 3 | 2026-08-23 20:07 UTC | PostgreSQL 夹具、Maven/前端门槛、真实 POST Search 与 GET DOM、可处置运行时、质量脚本、真实 LLM、双语文档、回滚与 Git 交付 | 无 | 无修改 | 连续计数 `3/3`，规划检查完成 |

## 6. 规划交付门禁

2026-08-23 20:07 UTC 已通过：

- `./scripts/verify-project-docs.sh`：10 项检查全部通过；
- `./scripts/verify-no-pessimistic-locks.sh`：生产源码未发现显式悲观锁或 advisory lock；
- `git diff --check`：通过；
- 活跃 plan/progress 尾随空白和文件末尾换行检查：通过；
- plan SHA-256 复核：仍为
  `3f82f3b25e5998a6f5d6e454f59b5f8b8bbe6eccd9412f1b360f90f2f55179db`。

## 7. 下一恢复入口

1. 读取 plan §4、§5、§6 和本文件；
2. 核对 `git status`、当前分支、HEAD 与 `origin/main`；
3. 规划审查已达到 `3/3`，不得重新修改冻结规划正文；如发现实质设计问题，记录原因并重置；
4. 规划提交 `721cd157` 已推送到
   `origin/docs/next-retrieval-quality-plan-20260823`；
5. 2026-08-23 20:08 UTC fetch 后，本地 `main` 与 `origin/main` 均为 `0fd37b6d`，
   无需合并远端变化；
6. 2026-08-24 已建立 `feature/rerank-document-diversity-20260824`；该分支包含已推送的
   规划提交，且 `origin/main@0fd37b6d` 是其祖先；
7. 下一步按 plan §5 一次性完成生产实现、测试、验收 runner 与双语长青文档，再执行
   plan §6 的基本集成硬门槛。

## 8. 实施记录

### 2026-08-24 核心选择器与 provider 契约

- 新增 `preferred-max-chunks-per-document` 配置，默认 `2`、范围 `0..100`；
- 新增固定三遍、O(n) 的文档级选择器，保留 provider 子序列并直接复用结果对象；
- `ReRankingService` 已分离 provider ranking depth 与最终 caller limit，激活时把直接输入
  防御性限制到 `candidate-limit`；
- provider SPI 的 Java 方法签名未变化，只明确第三参数语义；
- `application.yml` 与 `application-prod.yml` 已增加环境变量绑定；
- 聚焦开发测试：
  `RagRerankPropertiesTest,ReRankingServiceTest,RerankResultSelectorTest,`
  `HeuristicRerankProviderTest,HttpRerankProviderTest,RerankProviderFactoryTest`；
  共 50 项，失败 0、错误 0、跳过 0。

### 2026-08-24 PostgreSQL/pgvector 集成夹具

- `HybridRetrieverRrfPostgresIntegrationTest` 已扩展为同一文档四个高排名 chunk 加两个替代
  文档的确定性夹具；
- cap=`1` 已证明最终结果增加文档覆盖，cap=`0` 已证明恢复 provider 的同文档 top N，
  替代文档不足时已证明按原排名回填且不减少结果数量；
- 使用真实 PostgreSQL/pgvector 容器执行 3 项集成测试，失败 0、错误 0、跳过 0；
- 测试启动时完整应用 Flyway V1-V48，数据库 schema 与当前生产迁移一致。

### 2026-08-24 真实全栈验收资产

- 新增无截图的 `rerank-document-diversity-real.spec.ts`：
  - 经真实 Vite proxy 发送 POST Search，断言 trace header、JSON 字段、score 顺序、
    文档覆盖和每文档优先 chunk 上限；
  - 再使用实际 Search 页面 GET，按真实响应断言认证 header、Collection scope、标题、
    片段、顺序和结果数量；
- 新增 `verify-rerank-document-diversity.sh`：
  - 聚合后端调用链、PostgreSQL、Maven、前端、Mock Playwright 和文档门禁；
  - 启动前拒绝覆盖同 worktree 的 `.dev` 栈，固定使用隔离端口 `18083`/`15175`；
  - 优先创建本机可处置 PostgreSQL，缺少可用 pgvector 时回退独占 Docker；
  - root key 只保存在 shell，证据写入 `.verification/`，退出时只清理本脚本拥有的栈和数据库；
  - 创建一个多 chunk 主文档和三个独立替代文档，随后运行真实 Search、goldenset、
    版本化质量回归、真实 LLM provider baseline 与 KNOWLEDGE sources/citation 检查；
- 新验收资产已通过 `bash -n`、`git diff --check` 和 WebUI TypeScript 检查。

### 2026-08-24 双语长青文档同步

- `configuration*` 已记录配置默认值、范围、环境变量、软上限、回填与 `0` 回滚语义；
- `architecture*` 已记录
  `bounded candidates -> provider ranking -> document preference -> backfill -> final top N`
  的共享调用链，以及不增加远程调用的成本边界；
- `quality-defaults*` 已记录默认 `2` 的取舍、`1`/`3+`/`0` 调参语义和应观察的质量/延迟；
- `troubleshooting*` 已增加同一文档 chunk 过多时的诊断和回滚步骤；
- `developer-reference*` 与 `testing-guide*` 已加入专项 runner 命令、隔离资源边界和无截图
  验收证据说明。

### 2026-08-24 基本集成硬门槛首次执行

- 专项 runner 证据目录：
  `.verification/rerank-document-diversity/20260824-100101/`；
- 19 个步骤中 18 个通过：
  - 聚焦后端调用链 149 项测试；
  - PostgreSQL/pgvector 3 项集成测试与 Flyway V1-V48；
  - `mvn clean compile test-compile`；
  - WebUI TypeScript、218 项 Vitest、生产构建、alignment gate；
  - 核心 Mock Playwright 24 项；
  - 无悲观锁、项目文档与 whitespace 门禁；
  - 隔离 `dev.sh`、可处置 PostgreSQL、多文档真实夹具；
  - 真实 Search proxy/DOM Playwright、retrieval goldenset；
  - 真实 embedding/chat provider baseline；
  - 真实 KNOWLEDGE 回答 5 个 sources、4 个不同 document、citation 合法。
- 唯一失败是既有 `scripts/run-retrieval-regression.sh` 仍只接受历史同步 embedding
  成功态 `COMPLETED/CACHED`；当前统一生命周期 upsert 已返回 `READY`，实际向量与后续
  检索均正常。该脚本契约漂移阻断版本化质量门禁，不是 rerank 选择器质量回退。
- 处理计划：更新回归脚本接受当前稳定成功态 `READY`，保留旧成功态兼容；补充无需真实
  服务的脚本级契约验证；随后重跑受影响质量门禁和完整专项 runner。因为发生代码修改，
  实现审查连续无修改计数保持 `0/3`。
- runner 退出后 `dev.sh --status` 显示前后端均停止，隔离数据库已删除；另有上轮手工
  启动留下的无监听空壳 shell 进程，需单独清理，不影响端口或数据库隔离。

### 2026-08-24 质量回归契约修复

- `run-retrieval-regression.sh` 现在接受统一生命周期成功态 `READY`，同时兼容旧
  `COMPLETED/CACHED`；`INDEXING/NOT_REQUESTED/FAILED/STALE` 仍明确失败；
- 新增 `--self-test`，无需 HTTP 服务即可验证上述成功/失败状态集合；专项 runner 已把它
  纳入独立门禁；
- 中英文 testing/developer-reference 长青文档已同步命令与契约；
- 已通过 response-contract self-test、两个 Shell 文件 `bash -n`、`git diff --check`
  与项目文档 10 项门禁；
- 上轮空壳 shell 进程已清理，`dev.sh` 前后端停止、隔离数据库不存在；
- 下一步从头重跑完整专项 runner；实现审查计数仍为 `0/3`。

### 2026-08-24 基本集成硬门槛通过

- 完整专项 runner：
  `.verification/rerank-document-diversity/20260824-100906/summary.md`；
- 结果为 20 项通过、0 失败、0 跳过，覆盖聚焦后端、PostgreSQL/pgvector、
  `mvn clean compile test-compile`、WebUI typecheck/Vitest/build/alignment、核心 Mock
  Playwright、真实 Search/DOM、goldenset、版本化质量回归和真实 LLM；
- 版本化回归 6 个 case 的 hitRate/MRR/recall@5/nDCG 均为 `1.0`；
- 真实 LLM provider baseline 通过；KNOWLEDGE 回答返回 5 个 sources、4 个不同
  document、5 个有效 citation；
- runner 退出后 `dev.sh` 前后端均停止，`18083/15175` 无监听，隔离数据库已删除；
- 基本集成硬门槛已满足，开始按固定范围执行连续三轮只读实现审查，计数 `0/3`。

### 2026-08-24 实现审查发现验收证据缺口

- 第 1 轮只读检查覆盖配置、选择器、provider、排序、identity 与成本边界，无实质问题；
- 第 2 轮只读检查覆盖 Search、Chat、Agent、JSON record、Evaluation、citation 与分路径
  fallback，无实质问题；
- 第 3 轮检查验收 runner 与冻结规划 §6.5 时发现实质缺口：现有 runner 已证明功能正确、
  质量不退化和真实链路可用，但没有在同一数据库、同一夹具和固定请求样本下生成 cap=`0`
  与 cap=`2` 的 Search/Chat retrieval p95、rerank latency、HTTP 响应 payload 和最终
  unique document count 对比产物；
- 该问题影响验收证据完整性，不改变冻结行为契约。实现审查连续计数已重置为 `0/3`；
- 处理边界：复用公开响应中的 trace ID，并只读查询可处置测试库的
  `rag_retrieval_logs.total_time_ms` / `rerank_time_ms`，不扩大生产 API；对延迟只记录
  固定样本观测值，不增加脆弱阈值断言；
- 下一步先扩展专项 runner 产出 JSON/Markdown 对比证据，并把 regression self-test 中的
  Python `assert` 改为显式失败；随后重跑受影响门槛和完整专项 runner，再从 `0/3`
  重新执行连续三轮限定范围只读审查。

### 2026-08-24 真实前后对比证据已实现

- 新增标准库-only 的 `scripts/rerank-document-diversity-metrics.py`：
  - 对 Search/Chat 执行固定样本采集并只保留 trace、耗时、payload 字节、结果数和不同
    document 数，不保存 answer 或文档正文；
  - 通过服务返回的 trace ID 与只读数据库结果做一一关联；
  - 使用 nearest-rank 统计生成 p50/p95/min/max/mean 和 feature-minus-baseline；
  - 提供无需服务的 `self-test`，显式验证百分位、聚合和 delta 计算；
- 专项 runner 现在在真实 LLM provider baseline 通过后，对同一可处置数据库和同一 fixture
  依次重启 cap=`0`、cap=`2` 服务，默认每个变体预热后采集 20 个 Search 和 5 个 Chat
  样本，最后保持 cap=`2` 继续 KNOWLEDGE 验收；
- runner 同时支持本机 PostgreSQL 与独占 Docker PostgreSQL 的只读
  `rag_retrieval_logs` 查询，产出 `runtime-comparison.json` /
  `runtime-comparison.md`；延迟与 payload 只记录观测值，不设置墙钟阈值；
- `run-retrieval-regression.sh --self-test` 已移除 Python `assert`，改为不会受优化模式影响的
  显式失败；
- 中英文 `developer-reference*`、`testing-guide*` 已同步新证据与门禁语义；
- 当前已通过两个 Python/Shell self-test、Python 编译、Shell 语法和 `git diff --check`；
  因发生实质修改，实现审查计数仍为 `0/3`。

### 2026-08-24 完整门槛首次验证对比采集器

- 完整专项 runner：
  `.verification/rerank-document-diversity/20260824-103111/summary.md`；
- 结果为 21 项通过、1 项失败、0 跳过；后端 149 项聚焦测试、PostgreSQL/pgvector、
  `mvn clean compile test-compile`、WebUI typecheck/Vitest/build/alignment、24 项 Mock
  Playwright、真实 Search/DOM、goldenset、版本化回归、真实 provider baseline 和真实
  KNOWLEDGE 均通过；
- 唯一失败是新指标工具把 Chat JSON 的 12 位端到端 `traceId` 当成检索诊断 UUID。代码核对
  确认非流式 Chat 的检索诊断 UUID 位于响应头 `X-RAG-Retrieval-Trace-Id`，与 Search
  契约一致；采集器已改为读取该响应头；
- 失败前已观察到同一真实夹具的 20 个 Search 样本稳定从 cap=`0` 的 3 个不同 document
  提升到 cap=`2` 的 4 个不同 document；该观察尚未形成最终 JSON/Markdown，因此不作为
  完成证据；
- runner 退出后前后端均停止，`18083/15175` 无监听，一次性数据库已删除；
- 因发生实质修复，实现审查计数保持 `0/3`；下一步先重跑指标自测，再从头运行完整专项
  runner，不沿用本次失败前的门槛结果。

### 2026-08-24 完整专项验收与真实前后对比通过

- 完整专项 runner：
  `.verification/rerank-document-diversity/20260824-103816/summary.md`；
- 结果为 22 项通过、0 失败、0 跳过，完整覆盖：
  - 聚焦后端调用链 149 项测试；
  - PostgreSQL/pgvector 3 项集成测试与 Flyway V1-V48；
  - `mvn clean compile test-compile`；
  - WebUI TypeScript、218 项 Vitest、生产构建、alignment gate；
  - 核心 Mock Playwright 24 项；
  - 无悲观锁、项目文档、脚本契约、自测与 whitespace 门禁；
  - 隔离真实 dev 栈、多文档 fixture、真实 Search proxy/DOM Playwright；
  - retrieval goldenset、版本化质量回归、真实 embedding/chat provider baseline；
  - cap=`0` 与 cap=`2` 的真实 Search/Chat 固定样本对比；
  - 真实 KNOWLEDGE 回答的 sources、document coverage 与 citation。
- `runtime-comparison.md/json` 已成功生成；固定夹具与样本的主要观测为：
  - Search 不同文档数 min/p50 从 `3/3` 提升到 `4/4`，最终结果 p50 保持 `5`；
  - Chat 不同文档数 min 从 `3` 提升到 `4`，p50 保持 `4`，最终结果 p50 保持 `5`；
  - Search retrieval p95 为 `9ms -> 11ms`，rerank p95 为 `2ms -> 4ms`；
  - Chat retrieval p95 为 `189ms -> 153ms`，rerank p95 均为 `5ms`；
  - 延迟与 payload 均为观测值，不作为脆弱的通过阈值。
- 版本化质量回归的 hitRate/MRR/recall@5/nDCG 继续全部为 `1.0`；
- 真实 KNOWLEDGE 返回 5 个 sources、4 个不同 document、5 个有效 citation；
- runner 退出后 `dev.sh` 前后端均停止，`18083/15175` 无监听，一次性数据库已删除；
- 冻结规划 SHA-256 仍为
  `3f82f3b25e5998a6f5d6e454f59b5f8b8bbe6eccd9412f1b360f90f2f55179db`；
- 基本集成硬门槛已经满足。下一步执行三轮固定范围、只读实现审查，当前连续计数 `0/3`。

### 2026-08-24 实现连续审查完成

冻结规划 SHA-256 复核仍为
`3f82f3b25e5998a6f5d6e454f59b5f8b8bbe6eccd9412f1b360f90f2f55179db`。

| 轮次 | 检查时间 | 固定范围 | 发现问题 | 处理措施 | 结果 |
|---|---|---|---|---|---|
| 1 | 2026-08-24 10:48 +0800 | 配置绑定、选择器、provider ranking depth、排序、identity 与成本边界 | 无 | 无修改 | 连续计数 `1/3` |
| 2 | 2026-08-24 10:50 +0800 | Search、KNOWLEDGE、AGENT、JSON record、Evaluation、citation 与分路径 fallback | 无 | 无修改 | 连续计数 `2/3` |
| 3 | 2026-08-24 10:52 +0800 | 专项 runner、PostgreSQL、WebUI、真实 LLM、指标产物、双语文档、密钥边界与资源清理 | 无 | 无修改 | 连续计数 `3/3` |

第 3 轮同时复核并通过：

- `bash -n`：专项 runner 与 retrieval regression runner；
- runtime metrics self-test 与 retrieval regression response-contract self-test；
- `python3 -m py_compile`；
- `./scripts/verify-project-docs.sh`：10 项通过；
- `./scripts/verify-no-pessimistic-locks.sh`；
- `git diff --check`；
- `dev.sh --status`：前后端停止，隔离端口无监听；
- `.verification/` 继续由 Git ignore，真实密钥未进入本任务待提交 diff。

实现审查已连续 `3/3` 无修改。下一步 fetch 最新 `origin/main`；如基线变化则合并到特性
分支，并严格按合并后的代码重新执行完整专项验收和连续三轮审查。

### 2026-08-24 main 基线同步

- 2026-08-24 10:53 +0800 执行 `git fetch origin --prune`；
- `origin/main` 与本地 `main` 均仍为 `0fd37b6d`，没有自本轮基线之后的新提交；
- `origin/main` 是当前特性分支 HEAD 的祖先，分叉计数为特性分支领先 2、main 领先 0；
- 因不存在待合并的 main 变化，未产生合并提交，也不存在“合并前验收不可沿用”的情形；
- 当前最终实现代码对应的完整专项验收仍是
  `.verification/rerank-document-diversity/20260824-103816/summary.md`：
  22 项通过、0 失败、0 跳过；
- 下一步把 plan/progress 归档，重跑轻量 Git/文档门禁，提交并推送特性分支，然后合并并
  推送 `main`。
