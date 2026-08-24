# heuristic rerank 的 CJK 词法质量与有界计算进度

> 状态：规划连续检查 `3/3` 完成，待一次性测试与实施
>
> 开始日期：2026-08-24
>
> 当前分支：`feat/heuristic-cjk-rerank-20260824`
>
> 当前 worktree：`/Users/yangjiefeng/Documents/wubuku/spring-ai-rag`
>
> 代码基线：`5dac7af5`（本地 `main` / `origin/main`）
>
> 实施规划：[2026-08-24_HEURISTIC_CJK_RERANK_PLAN.md](2026-08-24_HEURISTIC_CJK_RERANK_PLAN.md)

本文是跨会话恢复账本。每次取得关键进展时先更新本文，再进入下一阶段；不记录密钥、
完整正文、模型输出或其他本地敏感状态。

## 1. 阶段状态

| 阶段 | 状态 | 说明 |
|---|---|---|
| 上一轮 main 交付 | 已完成 | `5dac7af5 feat: stabilize knowledge evidence joining` 已合入并推送 main，18 项完整复验通过 |
| 新分支与基线 | 已完成 | 从最新本地 `main@5dac7af5` 创建 `feat/heuristic-cjk-rerank-20260824` |
| 上一轮草案归档 | 已完成 | 归档为 `2026-08-24_KNOWLEDGE_EVIDENCE_JOINER_{PLAN,PROGRESS}.md` 并修复互链 |
| 代码与文档探索 | 已完成 | 已核对 provider/fallback/facade、prod 默认、候选上限、PostgreSQL 测试与专项门禁 |
| 活动规划 | 已完成 | 已冻结 CJK bigram、Latin 兼容、512 上限、预计算、重复 chunk 和验收矩阵 |
| 规划连续检查 | `3/3` | 修复归档链接和无效脚本范围后，连续三轮无修改检查通过 |
| 一次性测试 | 已完成 | provider/facade/fallback/PostgreSQL 测试已一次性落下；旧实现快速基线 49 项中 10 项按预期失败 |
| 生产实现 | 已完成 | 单文件实现 CJK bigram、混合 token、512 上限、预计算和 index-aware diversity |
| 基本硬门槛 | 已完成 | 专项完整门禁 `22/22`、全量 Maven `3608` 项、真实栈与真实 LLM 均通过 |
| 实现连续检查 | `3/3` | 三轮限定范围只读审查连续无问题、无生产代码或测试改动 |
| Git 与 main 交付 | 进行中 | 待本地提交、merge 最新 origin/main、复验、推送并合入 main |

## 2. 已冻结决策

- 本轮只增强本地 heuristic provider，不新增配置、迁移、API 或远程调用。
- CJK 使用 Unicode script 连续片段的相邻 bigram；单字符片段保留单字符。
- 普通无 CJK 空白 token 保持既有语义，避免无关英文排序漂移。
- 混合片段中的 Latin/数字 run 独立保留。
- 每个输入最多 512 个特征；一次 rerank 预计算 query 和候选特征。
- 生产 diversity 按候选 index 跳过 self，完全相同的另一 chunk 必须参与 similarity。
- null/blank 无词法信息，不获得 relevance 或 diversity 满分。
- HTTP rerank 成功路径不变，失败 fallback 自动受益。
- deterministic 正确性由单元测试和真实 PostgreSQL 候选重排证明；真实 LLM 证明链路，
  不拿自然语言输出当稳定排序 oracle。
- 规划/进度保持中文单语；稳定能力落地后同步双语长青文档。

## 3. 规划检查账本

固定范围：

1. 价值、范围、词法/评分契约和非目标；
2. Unicode、复杂度、兼容、fallback 和生产调用链；
3. 测试矩阵、PostgreSQL、前端、真实 LLM、文档、回滚和 Git。

发现实质问题必须修改规划并把计数重置为 `0/3`。无问题轮次不在轮次之间修改文档；
达到 `3/3` 后一次性写入最终摘要和规划 SHA-256。

| 连续轮次 | 时间 | 范围 | 发现/处理 | 结果 |
|---:|---|---|---|---|
| 0/3 | 2026-08-24 13:51 CST | 第三轮：验收命令、文档生命周期、隔离端口、无截图与 Git 交付 | 归档后的 evidence joiner plan 仍使用 `../` 指向 `docs/`，移动后链接层级错误，项目文档门禁失败；已统一改为 `../../` | 有实质修复，规划检查计数重置为 `0/3` |
| 0/3 | 2026-08-24 13:53 CST | 第二轮：Unicode、provider/fallback、候选上限和专项门禁可实施性 | 现有 rerank 专项门禁已经覆盖本轮修改的测试类与 PostgreSQL 集成类，规划仍要求扩展 focused 集合会造成无效脚本改动 | 改为直接复用现有门禁，仅在真实缺失时才修改脚本；计数重置为 `0/3` |

最终连续无修改检查：

| 轮次 | 时间 | 固定范围 | 发现问题 | 处理措施 | 结果 |
|---:|---|---|---|---|---|
| 1/3 | 2026-08-24 13:53 CST | 价值、用户优先级、生产范围、词法/评分契约和非目标 | 无 | 无修改 | 连续计数 `1/3` |
| 2/3 | 2026-08-24 13:54 CST | Java 21 UnicodeScript、code point、512 上限、index-aware duplicate、factory/fallback 和测试可编码性 | 无 | 无修改 | 连续计数 `2/3` |
| 3/3 | 2026-08-24 13:55 CST | PostgreSQL、Maven、WebUI、无截图 Mock Playwright、隔离真实 LLM、文档、回滚和 Git | 无 | 无修改；文档门禁 `10/10`、禁锁与 retrieval regression self-test 通过 | 连续计数 `3/3` |

最终规划 SHA-256：
`70559f33b4de5922c42fbf95c8b644056a9c4c96a446574b2b3621713bcc380d`。

## 4. 一次性测试矩阵

生产实现前一次性覆盖：

- 无空格 CJK 局部匹配、词序变化和默认权重排名提升；
- CJK Jaccard、单字符、混合语言、英文兼容、null/blank/标点、长输入；
- 完全重复 chunk 的 index-aware diversity；
- provenance、rankingDepth、HTTP fallback 和 facade 边界；
- 真实 PostgreSQL/pgvector 候选先错后经 heuristic 提升；
- Maven、服务启动、WebUI build/Mock Playwright 和真实 LLM 基线。

## 5. 验收证据

本轮使用：

```text
.verification/heuristic-cjk-rerank/<run-id>/
```

日志和 summary 必须记录实际测试数、失败、错误、跳过、commit、端口和数据库类型；
不得记录 API key。

## 6. 恢复入口

1. 读取本文和活动 plan；
2. 核对分支为 `feat/heuristic-cjk-rerank-20260824`；
3. 核对 `git status`，保留并理解全部已有修改；
4. 先完成规划连续 `3/3`；
5. 按 Slice A 一次性写完测试，再实现 Slice B/C；
6. 每个关键切片完成后先更新本文；
7. 基本硬门槛全部通过后才做实现三轮只读审查；
8. 任何实质修改重置实现计数并重跑受影响门槛。

## 7. 实施记录

### 2026-08-24 一次性测试与红灯基线

- 已扩展 `HeuristicRerankProviderTest`、`ReRankingServiceTest`、
  `HttpRerankProviderTest` 和 `HybridRetrieverRrfPostgresIntegrationTest`。
- 测试一次性覆盖 CJK 局部/词序、混合语言、英文兼容、blank、长输入、完全重复 chunk、
  默认权重排名、HTTP fallback、provenance 和真实 PostgreSQL 候选重排。
- 旧实现快速测试共执行 `49` 项，`10` 项失败、`0` error、`0` skipped；失败集中在本轮
  目标行为，证明测试能捕获缺陷。
- 红灯运行同时发现英文部分匹配的既有位置奖励使精确分数为 `0.8`，测试 oracle 已在
  生产实现前修正；这不是生产缺陷，也未改变冻结的英文兼容契约。

### 2026-08-24 词法内核实现与快速绿灯

- `HeuristicRerankProvider` 已按计划实现 `Locale.ROOT` 规范化、HAN/HIRAGANA/KATAKANA/
  HANGUL/BOPOMOFO bigram、单字符 CJK、混合 Latin/数字 run 和每输入 512 特征上限。
- 一次 rerank 只提取一次 query 和每个候选的特征；pairwise Jaccard 不再重复拆词，
  并按候选 index 排除 self，使另一条完全相同 chunk 得到 similarity `1`。
- null/blank 不再获得空字符串 relevance 或无信息 diversity 奖励。
- 快速 provider/facade/fallback 矩阵：`49` tests，`0` failures，`0` errors，
  `0` skipped。
- 真实 `HybridRetrieverRrfPostgresIntegrationTest`：`5` tests，`0` failures，
  `0` errors，`0` skipped；新增 CJK 用例证明 pgvector 候选先错、真实 heuristic
  facade 后正确提升。
- 双语 `architecture`、`quality-defaults` 和 `testing-guide` 已同步稳定行为与验证入口。

### 2026-08-24 基本集成硬门槛

- 专项完整门禁：
  `.verification/heuristic-cjk-rerank/20260824-pre-review/summary.md`。
- 门禁结果：`22` 个阶段全部 PASS，`0` failed、`0` skipped；包括 focused 后端调用链
  `163` tests、PostgreSQL/pgvector 集成、`mvn clean compile test-compile`、WebUI
  TypeScript/Vitest/生产构建/对齐检查、核心 Mock Playwright `24` tests、真实 Vite
  代理 Search DOM Playwright、goldenset、版本化质量回归、真实 Embedding/Chat、
  cap=0 与 cap=2 运行时对照和真实 KNOWLEDGE。
- 全量 `mvn test`：`3608` tests，`0` failures、`0` errors、`7` skipped，
  reactor `BUILD SUCCESS`。这 `7` 项为全库既有条件性跳过；本任务要求的真实
  PostgreSQL 集成另行执行 `5/5` 且无跳过。
- 隔离端口：后端 `18086`，WebUI `15178`；隔离 PostgreSQL 测试库由门禁创建并清理。
- 前端验收只使用 DOM、网络与自动化断言，没有使用截图。
- 真实 LLM 基线 `PASS=9 FAIL=0`；真实 KNOWLEDGE 返回 `5` 个 sources、
  `4` 个唯一文档和 `5` 个 citations。
- 实测 heuristic rerank stage p95 为 Search `1ms`、Chat `3ms`；Chat 端到端耗时
  主要受远端 LLM 波动影响，不作为本地词法实现的稳定阈值。

### 2026-08-24 实现连续检查

硬门槛通过后，按规划固定为三轮互不重叠的只读检查。三轮之间没有修改生产代码、
测试或长青文档；达到 `3/3` 后才一次性记录本节。

| 轮次 | 时间 | 固定范围 | 发现问题 | 处理措施 | 结果 |
|---:|---|---|---|---|---|
| 1/3 | 2026-08-24 14:17 CST | 生产 tokenizer/code-point 边界、512 上限、relevance/diversity、空白与英文兼容 | 无 | 无修改 | 连续计数 `1/3` |
| 2/3 | 2026-08-24 14:18 CST | provider/facade/fallback、候选深度、重复 chunk、provenance、性能与失败语义 | 无 | 无修改 | 连续计数 `2/3` |
| 3/3 | 2026-08-24 14:18 CST | PostgreSQL/pgvector、WebUI/真实运行证据、双语长青文档、归档链接与 Git 边界 | 无 | 无修改 | 连续计数 `3/3` |

实现文件 SHA-256：
`9bcfa69257e4d4321e3cceeccebbb7704056bc82161a0a9d31fd274875aecfa0`。
