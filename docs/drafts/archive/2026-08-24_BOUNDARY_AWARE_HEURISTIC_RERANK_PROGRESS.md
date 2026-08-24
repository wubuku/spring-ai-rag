# heuristic rerank 的 Latin/数字词边界感知实施进度

> 状态：已实施、验收、推送并合入 `main@51e2d00c`，历史归档
>
> 开始日期：2026-08-24
>
> 当前分支：`feat/boundary-aware-heuristic-rerank-20260824`
>
> 当前 worktree：`/Users/yangjiefeng/Documents/wubuku/spring-ai-rag`
>
> 代码基线：`cb222c21`（本地 `main` / `origin/main`）
>
> 实施规划：[2026-08-24_BOUNDARY_AWARE_HEURISTIC_RERANK_PLAN.md](2026-08-24_BOUNDARY_AWARE_HEURISTIC_RERANK_PLAN.md)

本文是跨会话恢复账本。每次取得关键进展时先更新本文，再进入下一阶段；不记录密钥、完整
业务正文、真实模型输出或其他本地敏感状态。

## 1. 阶段状态

| 阶段 | 状态 | 说明 |
|---|---|---|
| 上一轮 main 交付 | 已完成 | title-aware heuristic rerank 已合入 `main@6a1bbe8e`，归档收尾已推送 `main@cb222c21` |
| 新分支与基线 | 已完成 | 从最新本地/远端 `main@cb222c21` 创建 `feat/boundary-aware-heuristic-rerank-20260824` |
| 上一轮草案归档 | 已完成 | `2026-08-24_TITLE_AWARE_HEURISTIC_RERANK_{PLAN,PROGRESS}.md` 已归档，稳定事实已进入双语长青文档 |
| 候选探索 | 已完成 | 比较 query rewrite 跳过与 heuristic 边界修复；确认旧 rewrite 主要是组件兼容路径，本轮选择所有 rerank 入口共用的边界修复 |
| 活动规划 | 已完成 | 已冻结外层标点白名单、Latin/数字 Unicode 词边界、CJK/技术词兼容和一次性验收矩阵 |
| 规划连续检查 | `3/3` | 两次实质修正后，连续三轮固定范围无修改检查通过 |
| 一次性测试 | 已完成 | provider/facade/HTTP/PostgreSQL 测试已一次性落下；旧实现 focused `63` 项中 `6` 个预期失败，PostgreSQL `7` 项中仅新增排序契约 `1` 个预期失败 |
| 生产实现 | 已完成 | `HeuristicRerankProvider` 已加入一次性 relevance term 准备、外层标点剥离和 Unicode 边界 occurrence 搜索 |
| 长青文档 | 已完成 | architecture / quality-defaults / testing-guide 中英文已同步边界感知行为与测试矩阵 |
| 基本硬门槛 | 已完成 | 专项 runner `22/22` 全通过且零 skip；独立全量 `mvn test` 构建成功，共 `3622` 项测试通过、`7` 项按环境条件跳过 |
| 实现连续检查 | `3/3` | 连续三轮固定范围只读检查无修改通过 |
| Git 与 main 交付 | 已完成 | 特性提交 `72d8d13d` 已推送；远端 main 未变化，合并提交为 `51e2d00c`；本文件与规划随后归档 |

## 2. 已冻结决策

- 本轮只修复默认 heuristic/fallback 的 Latin/数字 relevance occurrence。
- query term 只在 relevance 查找时剥离明确的句末/包裹标点，不修改特征抽取和 diversity。
- `+ # - _ / \` 不作为可剥离外层标点，保护技术词和标识符。
- 不含 CJK、且首尾为 Unicode 字母/数字的 term 使用左右字母数字边界。
- CJK term 和 `C++`/`C#` 等首尾非字母数字 term 保持 substring。
- position bonus 使用首个合法 occurrence 的实际位置。
- title 权重、diversity、候选池、HTTP 成功协议和所有对外 API 不变。
- 规划/进度为中文单语；稳定行为落地后同步双语长青文档。

## 3. 规划检查账本

固定范围：

1. 价值、范围、标点集合、词边界、兼容和非目标；
2. Unicode 实现、CJK/技术词隔离、复杂度、调用链与回滚；
3. 一次性测试、PostgreSQL、前端、真实 LLM、文档、证据和 Git。

发现实质问题必须修改规划并把计数重置为 `0/3`。无问题轮次不在轮次之间修改文档；达到
`3/3` 后一次性写入最终摘要和规划 SHA-256。

| 连续轮次 | 时间 | 范围 | 发现/处理 | 结果 |
|---:|---|---|---|---|
| 0/3 | 2026-08-24 | 第 1 轮：需求闭环、边界语义与混合文本兼容 | 原规划把所有 Unicode 字母/数字都视为 Latin term 的连续字符，会让 `中文SpringAI检索`、`型号9042说明` 这类现有 mixed extractor 支持的无分隔文本失配；已改为只有相邻的非 CJK 字母/数字才阻断，并补充 script-transition 测试 | 有实质修复，规划检查计数重置为 `0/3` |
| 0/3 | 2026-08-24 | 第 2 轮：实现可行性、调用链与复杂度 | 初稿若在每个候选的正文/标题 relevance 内重复剥离和分类 query term，会产生最多 `2 × candidateCount` 次重复准备；已冻结每次 rerank 一次性生成不可变 `RelevanceTerm` 列表并在正文/标题间复用 | 有实质修复，规划检查计数重置为 `0/3` |

最终连续无修改检查：

| 轮次 | 时间 | 固定范围 | 发现问题 | 处理措施 | 结果 |
|---:|---|---|---|---|---|
| 1/3 | 2026-08-24 | 价值、误命中/漏命中样例、标点白名单、非 CJK 边界、CJK transition、技术词和非目标 | 无 | 无修改；项目文档门禁 `10/10`、whitespace 通过 | 连续计数 `1/3` |
| 2/3 | 2026-08-24 | Java 21 code-point API、surrogate 边界、query term 一次预计算、六条 facade、factory 和 HTTP fallback | 无 | 无修改；确认所有生产入口和既有测试覆盖可达 | 连续计数 `2/3` |
| 3/3 | 2026-08-24 | 一次性红灯 oracle、PostgreSQL fixture、WebUI、真实 LLM、证据、隔离端口、归档和 Git 交付 | 无 | 无修改；retrieval self-test、禁悲观锁和远端基线检查通过 | 连续计数 `3/3` |

最终规划 SHA-256：
`96a1ad4c3ea50641ef40939d530b403178d0fe913e81ae747a7c7d0adf954b59`。

## 4. 一次性测试矩阵

生产实现前一次性覆盖：

- Latin/数字内嵌 false positive 拒绝；
- 包裹/句末标点后的完整词命中；
- 先非法后合法 occurrence 和 position；
- CJK、混合 ID、`C++`、`C#`、`api/v1` 兼容；
- title/chunk relevance 与 diversity 隔离；
- facade、HTTP 成功契约与 fallback；
- 真实 PostgreSQL/pgvector 候选先错后由边界感知 title relevance 纠正。

## 5. 验收证据

本轮证据目录：

```text
.verification/boundary-aware-heuristic-rerank/<run-id>/
```

复用专项 runner 时其原始证据仍写入：

```text
.verification/rerank-document-diversity/<run-id>/
```

进度账本必须记录实际测试数、失败、错误、跳过、commit、隔离端口和数据库类型；不得记录
API key。

### 5.1 旧实现红线

focused provider/facade/HTTP 矩阵：

```text
Tests run: 63, Failures: 6, Errors: 0, Skipped: 0
```

六个失败均为本轮新增契约：Latin/数字内嵌误命中、外层标点漏命中、先非法后合法 occurrence、
title 排序、facade 排序和 HTTP fallback 排序。原始证据：

```text
.verification/boundary-aware-heuristic-rerank/20260824-red-baseline/focused-tests.log
```

clean `test-compile` 后，以真实 PostgreSQL 16 + pgvector 和 V1–V48 迁移重跑：

```text
Tests run: 7, Failures: 1, Errors: 0, Skipped: 0
```

唯一失败是新增 `realVectorCandidatesRejectEmbeddedLatinAndNumericTitleMatches`，旧实现错误地把
distractor 排在 relevant 之前；其余六条既有数据库链路通过。由于本机 Docker registry
拉取 Ryuk 时返回错误证书，本次设置 `TESTCONTAINERS_RYUK_DISABLED=true`，测试类仍自行启动
并在 `@AfterAll` 停止 pgvector 容器。原始证据：

```text
.verification/boundary-aware-heuristic-rerank/20260824-red-baseline-clean/postgres-tests.log
```

### 5.2 首轮绿线

与旧实现红线同构的 provider/facade/HTTP/factory focused 矩阵：

```text
Tests run: 63, Failures: 0, Errors: 0, Skipped: 0
```

真实 PostgreSQL 16 + pgvector、V1–V48：

```text
Tests run: 7, Failures: 0, Errors: 0, Skipped: 0
```

新增真实数据库用例已证明：向量 SQL 仍按原始分数先返回 distractor，但 heuristic facade 会
拒绝其 title 中的内嵌 `rag` / `ai` / `9042` 假命中，并把完整 title 命中的 relevant 排到
第一。证据：

```text
.verification/boundary-aware-heuristic-rerank/20260824-green-focused/focused-tests.log
.verification/boundary-aware-heuristic-rerank/20260824-green-postgres/postgres-tests.log
```

### 5.3 完整专项验收

隔离配置：

```text
run id: 20260824-boundary-aware-final
backend: 18092
WebUI: 15184
Mock preview: 4204
database: local disposable PostgreSQL + pgvector
```

专项 runner 最终结果：

```text
22 passed, 0 failed, 0 skipped
```

关键证据：

- focused 后端完整 rerank 调用链：`177/177`；
- PostgreSQL/pgvector：`7/7`，V1–V48；
- `mvn clean compile test-compile`：`BUILD SUCCESS`；
- WebUI TypeScript、生产 build、alignment 通过，Vitest `218/218`；
- 核心 Mock Playwright `24/24`，真实 Search DOM/代理 Playwright `1/1`；
- goldenset 与版本化真实检索回归全部通过，MRR/nDCG/Recall 均无回退；
- 真实 SiliconFlow BGE-M3 embedding 与 MiniMax Chat provider smoke：`9/9`；
- 真实 Chat 回答包含随机探针并返回 `1` 个 source/citation；
- 真实 KNOWLEDGE Chat 返回 `5` 个 sources/citations，覆盖 `4` 个 unique documents；
- cap=`0` 与 cap=`2` 的 Search/Chat rerank stage p95 都相同，均为 `1ms` / `5ms`；
  cap=`2` 的 Search/Chat unique document 均由 `3` 提升为 `4`；
- 项目文档 `10/10`、禁悲观锁、retrieval response contract、metrics self-test 和
  whitespace 均通过。

总摘要与完整原始日志：

```text
.verification/rerank-document-diversity/20260824-boundary-aware-final/summary.md
.verification/boundary-aware-heuristic-rerank/20260824-full-acceptance.log
```

### 5.4 独立 Maven 全量回归与环境清理

在专项 runner 之外独立执行：

```text
mvn test
```

结果为 `BUILD SUCCESS`，模块级结果：

```text
spring-ai-rag-api:       539 passed, 0 failed, 0 errors, 0 skipped
spring-ai-rag-documents:  74 passed, 0 failed, 0 errors, 0 skipped
spring-ai-rag-core:     2965 passed, 0 failed, 0 errors, 7 skipped
spring-ai-rag-starter:    44 passed, 0 failed, 0 errors, 0 skipped
total:                  3622 passed, 0 failed, 0 errors, 7 skipped
```

Core 的 `7` 项跳过来自普通 `mvn test` 不满足真实 PostgreSQL 条件的测试；本任务相关
PostgreSQL 类已在真实 PostgreSQL 16 + pgvector 中单独以 `7/7`、零跳过通过。原始日志：

```text
.verification/boundary-aware-heuristic-rerank/20260824-independent-maven/mvn-test.log
```

验收结束后 `scripts/dev.sh --status` 显示前后端均停止，隔离端口 `18092`、`15184`、
`4204` 均无监听进程。

## 6. 实现检查账本

基本硬门槛完成后，只读检查固定为三个互不重叠范围：

1. 外层标点、Unicode 边界、后续合法 occurrence、CJK/技术词兼容；
2. content/title relevance、diversity 隔离、facade/factory/HTTP fallback 和有界复杂度；
3. PostgreSQL、前端与真实运行证据、双语文档、生命周期、回滚和 Git 交付。

仅本任务内影响正确性、兼容性、性能或数据一致性的实质问题触发修改和计数归零；任何修改
后重跑受影响门槛，再重新从 `0/3` 开始。无问题轮次不在轮次之间修改文档，达到 `3/3`
后一次性写入最终摘要。

| 连续轮次 | 时间 | 固定范围 | 发现问题 | 处理措施 | 结果 |
|---:|---|---|---|---|---|
| 1/3 | 2026-08-24 17:11 +08:00 | 外层标点、Unicode code point、后续合法 occurrence、CJK transition 和技术词兼容 | 无 | 无修改；交叉核对 provider、focused 测试和冻结规划，whitespace 与禁悲观锁检查通过 | 连续计数 `1/3` |
| 2/3 | 2026-08-24 17:12 +08:00 | content/title relevance、diversity 隔离、facade/factory/HTTP fallback 和有界复杂度 | 无 | 无修改；确认 query term 每次 rerank 只准备一次，所有 fallback 共用实现，候选和特征上限不变 | 连续计数 `2/3` |
| 3/3 | 2026-08-24 17:14 +08:00 | PostgreSQL、前端与真实运行证据、双语文档、生命周期、回滚和 Git 基线 | 无 | 无修改；项目文档 `10/10`、retrieval response contract、metrics self-test、禁悲观锁和 whitespace 通过；`origin/main` 仍为 `cb222c21` | 连续计数 `3/3` |

最终结论：实现与规划边界一致，全部基本硬门槛和独立 Maven 全量回归通过，连续三轮限定
范围检查没有发现本任务内影响正确性、兼容性、性能或数据一致性的缺陷。审查期间未修改
生产代码、测试或长青文档。

## 7. 恢复入口

1. 读取本文和活动 plan；
2. 核对分支为 `feat/boundary-aware-heuristic-rerank-20260824`；
3. 核对 `git status`，保留并理解全部已有修改；
4. 先完成规划连续 `3/3`；
5. 按 Slice A 一次性写完测试并确认旧实现红灯；
6. 只修改 `HeuristicRerankProvider` 完成边界 occurrence；
7. 每个关键切片完成后先更新本文；
8. 基本硬门槛和实现连续检查 `3/3` 已完成；
9. Git 交付与归档已完成；后续工作以新的活动规划为准。
