# heuristic rerank 的标题感知相关性实施进度

> 状态：规划连续检查 `3/3` 完成，待一次性测试与实施
>
> 开始日期：2026-08-24
>
> 当前分支：`feat/title-aware-heuristic-rerank-20260824`
>
> 当前 worktree：`/Users/yangjiefeng/Documents/wubuku/spring-ai-rag`
>
> 代码基线：`6d3c1d17`（本地 `main` / `origin/main`）
>
> 实施规划：[NEXT_HIGH_VALUE_FEATURES_PLAN.md](NEXT_HIGH_VALUE_FEATURES_PLAN.md)

本文是跨会话恢复账本。每次取得关键进展时先更新本文，再进入下一阶段；不记录密钥、完整
业务正文、真实模型输出或其他本地敏感状态。

## 1. 阶段状态

| 阶段 | 状态 | 说明 |
|---|---|---|
| 上一轮 main 交付 | 已完成 | heuristic CJK rerank 已合入并推送 `main@6d3c1d17`，完整门槛与 main 复验通过 |
| 新分支与基线 | 已完成 | 从最新本地/远端 `main@6d3c1d17` 创建 `feat/title-aware-heuristic-rerank-20260824` |
| 上一轮草案归档 | 已完成 | `2026-08-24_HEURISTIC_CJK_RERANK_{PLAN,PROGRESS}.md` 已归档，稳定事实已进入双语长青文档 |
| 代码与文档探索 | 已完成 | 已核对 title 来源、vector/fulltext 映射、provider/fallback/facade、PostgreSQL fixture 和专项 runner |
| 活动规划 | 已完成 | 已冻结 `max(content, 0.9 * title)`、diversity 隔离、预计算、兼容和一次性验收矩阵 |
| 规划连续检查 | `3/3` | 两次实质修正后，连续三轮固定范围无修改检查通过 |
| 一次性测试 | 已完成 | provider/facade/HTTP/PostgreSQL 测试已一次性落下，旧实现红灯只命中新契约 |
| 生产实现 | 已完成 | `HeuristicRerankProvider` 已按冻结公式加入 title relevance，未改变 diversity 或 HTTP 成功路径 |
| 长青文档 | 已完成 | architecture / quality-defaults / testing-guide 中英文已同步并通过项目文档门禁 `10/10` |
| 基本硬门槛 | 已完成 | 专项 runner `22/22` 零失败零跳过；全量 Maven `3615` 项、零失败零错误 |
| 实现连续检查 | `3/3` | 基本硬门槛后完成三轮互不重叠、固定范围、只读检查，期间无修改 |
| Git 与 main 交付 | 未开始 | 特性分支 push、必要时 merge `origin/main` 复验、合回并推送 main |

## 2. 已冻结决策

- 本轮只增强默认 heuristic provider 的 title relevance，不新增 API、配置、迁移或 I/O。
- `effectiveRelevance=max(contentRelevance, 0.9*titleRelevance)`。
- title 不参与 diversity，不和正文 relevance 相加。
- null/blank title 必须保持上一版本 score 和排序精确兼容。
- query/chunk 复用 CJK-aware、最多 512 特征的提取器；title 只使用
  `Locale.ROOT` 规范化一次，不生成不会参与 diversity 的 token 集合。
- HTTP rerank 成功路径不变，heuristic fallback 自动受益。
- deterministic 正确性由 provider/facade 测试和真实 PostgreSQL/pgvector 证明；
  真实 LLM 只验证协议与完整运行链路。
- 规划/进度为中文单语；稳定行为落地后同步双语长青文档。

## 3. 规划检查账本

固定范围：

1. 价值、范围、评分公式、title 来源、兼容和非目标；
2. 调用链、复杂度、预计算、diversity 隔离、provider/fallback 和回滚；
3. 一次性测试、PostgreSQL、前端、真实 LLM、文档、证据和 Git。

发现实质问题必须修改规划并把计数重置为 `0/3`。无问题轮次不在轮次之间修改文档；达到
`3/3` 后一次性写入最终摘要和规划 SHA-256。

| 连续轮次 | 时间 | 范围 | 发现/处理 | 结果 |
|---:|---|---|---|---|
| 0/3 | 2026-08-24 15:45 CST | 第 1 轮：价值、评分公式、title 数据边界与复杂度 | 原规划把 title 写成完整词法特征提取，并把 relevance 简化为 `O(n*f)`，与实现只需要 normalized target、`indexOf` 仍受文本长度影响的事实不符；已核对 `rag_documents.title VARCHAR(255)`，改为 title 一次规范化并修正复杂度 | 有实质修复，规划检查计数重置为 `0/3` |
| 0/3 | 2026-08-24 15:47 CST | 第 1 轮续查：异常 score 兼容边界 | 原规划称所有非有限 raw score 都归零，但现有实现只对 `NaN` 归零；已改为精确描述并明确不扩大本轮异常 score 范围 | 有实质修复，规划检查计数保持 `0/3` |

最终连续无修改检查：

| 轮次 | 时间 | 固定范围 | 发现问题 | 处理措施 | 结果 |
|---:|---|---|---|---|---|
| 1/3 | 2026-08-24 15:47 CST | 价值、评分公式、权威 title 来源、255 字符边界、blank 兼容和非目标 | 无 | 无修改；项目文档门禁 `10/10`、whitespace 通过 | 连续计数 `1/3` |
| 2/3 | 2026-08-24 15:48 CST | facade 六条生产入口、候选上限、预计算复杂度、diversity 隔离、factory 和 HTTP fallback | 无 | 无修改 | 连续计数 `2/3` |
| 3/3 | 2026-08-24 15:48 CST | 一次性测试、PostgreSQL、WebUI、Mock 后真实 LLM、证据、归档、回滚和 Git 交付 | 无 | 无修改；禁悲观锁、retrieval regression self-test、远端基线检查通过 | 连续计数 `3/3` |

最终规划 SHA-256：
`359d21a366e00596300da2cdc1f717757bfb0083760f3c16f50edc347d7d3af5`。

## 4. 一次性测试矩阵

生产实现前一次性覆盖：

- title-only 英文/CJK/混合产品 ID relevance 和默认权重排名提升；
- null/blank title 精确兼容、强 chunk relevance 不降低；
- title 不改变 chunk diversity；
- 255 字符边界和更长内部兼容 title 分数有限且不生成 title token；
- title、score 分项、metadata 和 provenance 完整复制；
- facade MRR、HTTP 成功契约和 heuristic fallback；
- 真实 PostgreSQL/pgvector 候选先错后由权威 title 纠正。

## 5. 验收证据

本轮使用：

```text
.verification/title-aware-heuristic-rerank/<run-id>/
```

专项 runner 的原始证据仍会写入：

```text
.verification/rerank-document-diversity/<run-id>/
```

进度账本必须记录实际测试数、失败、错误、跳过、commit、隔离端口和数据库类型；不得记录
API key。

## 6. 恢复入口

1. 读取本文和活动 plan；
2. 核对分支为 `feat/title-aware-heuristic-rerank-20260824`；
3. 核对 `git status`，保留并理解全部已有修改；
4. 先完成规划连续 `3/3`；
5. 按 Slice A 一次性写完测试并确认旧实现红灯；
6. 实施单类生产修改和 PostgreSQL fixture；
7. 每个关键切片完成后先更新本文；
8. 全部基本硬门槛通过后才做实现三轮只读审查；
9. 任何实质修改重置实现计数并重跑受影响门槛。

## 7. 实施记录

### 2026-08-24 一次性测试与红灯基线

- 已扩展 `HeuristicRerankProviderTest`、`ReRankingServiceTest`、
  `HttpRerankProviderTest` 和 `HybridRetrieverRrfPostgresIntegrationTest`。
- 测试一次性覆盖 title-only 英文/CJK/混合 ID、blank 精确兼容、强正文不降分、
  title/diversity 隔离、长内部 title、字段复制、facade MRR、HTTP 成功请求体/fallback
  和真实 PostgreSQL 权威 title。
- 旧实现 focused 共执行 `56` 项，`4` failures、`0` errors、`0` skipped；失败仅为
  provider title-only、长 title、facade title-only 和 HTTP fallback title-only。
- 真实 `HybridRetrieverRrfPostgresIntegrationTest` 共执行 `6` 项，`1` failure、
  `0` errors、`0` skipped。新增用例已证明真实向量 SQL 返回两个候选、原始顺序先错且
  `rag_documents.title` 映射正确；唯一失败是旧 heuristic 仍选择 distractor。
- 红灯证据：
  `.verification/title-aware-heuristic-rerank/20260824-red-baseline/`。
- 测试 oracle 无需修正；下一步只修改 `HeuristicRerankProvider`。

### 2026-08-24 生产实现与快速绿色验证

- `HeuristicRerankProvider` 已预计算每个候选的 normalized title，并按
  `max(contentRelevance, 0.9 * titleRelevance)` 计算有效 relevance。
- title 未进入 chunk token 或 diversity；null/blank title 保持原 score 与排序路径。
- focused 测试在 clean compile 后共执行 `56` 项，`0` failures、`0` errors、
  `0` skipped。
- 真实 PostgreSQL/pgvector 集成测试共执行 `6` 项，`0` failures、`0` errors、
  `0` skipped；测试容器为 PostgreSQL 16 + pgvector，并完整执行 V1–V48 迁移。
- 绿色证据：
  `.verification/title-aware-heuristic-rerank/20260824-fast-green/` 与
  `.verification/title-aware-heuristic-rerank/20260824-postgres-green/`。
- 下一步同步双语长青文档，然后运行完整专项验收 runner 与全量 Maven。

### 2026-08-24 双语长青文档

- 已在 `architecture*` 固化标题相关性公式、title/diversity 隔离、HTTP 成功路径和成本边界。
- 已在 `quality-defaults*` 记录默认 heuristic 的 title-only 质量收益与兼容边界。
- 已在 `testing-guide*` 补充 provider/facade/HTTP/PostgreSQL 的标题感知验收矩阵。
- `./scripts/verify-project-docs.sh` 共 `10/10` 检查通过，`git diff --check` 通过。
- 下一步运行隔离端口完整专项 runner；只有其全部阶段通过后才执行全量 Maven 和实现审查。

### 2026-08-24 完整专项验收

- 隔离端口：后端 `18091`、非 Mock WebUI `15183`、Mock preview `4203`。
- `verify-rerank-document-diversity.sh` 共 `22` 阶段通过，`0` failed、`0` skipped。
- 后端 focused 调用链 `170/170`；真实 PostgreSQL/pgvector `6/6`；Maven
  `clean compile test-compile` 通过。
- WebUI TypeScript、`218` 项 Vitest、生产构建、alignment 与 `24` 项核心 Mock
  Playwright 全部通过；真实 Search Playwright `1/1`，只使用 DOM、网络、JSON 和断言。
- retrieval goldenset 的 baseline/quality 均为 MRR `1.0`、nDCG `1.0`；版本化真实检索
  `6/6` case 通过，Hit Rate/MRR/Recall@5/nDCG 均为 `1.0`。
- 真实 SiliconFlow BGE-M3 embedding 与 MiniMax M3 Chat 探测均为 HTTP `200`；真实
  provider smoke `PASS=9`、`FAIL=0`，KNOWLEDGE diversity 返回 `5` sources、
  `4` unique documents、`5` citations。
- cap=`0`/cap=`2` 各采集 `20` Search 与 `5` Chat；cap=`2` 的 Search/Chat
  unique-document p50 均从 `3` 提升到 `4`。延迟只作观测，不作为本轮阈值。
- 完整证据：
  `.verification/title-aware-heuristic-rerank/20260824-title-aware-feature/`。
- 下一步执行独立全量 `mvn test`，记录条件性 skipped 后进入实现连续三轮只读审查。

### 2026-08-24 独立全量 Maven

- `mvn test` reactor 全部成功：API `539`、Documents `74`、Core `2958`、Starter `44`，
  合计 `3615` 项。
- 结果为 `0` failures、`0` errors、`7` skipped；这些是全量套件既有条件性 skip。
- 本任务要求的 `HybridRetrieverRrfPostgresIntegrationTest` 已在独立门槛和专项 runner
  中两次达到 `6/6`、`0` skipped。
- 原始日志：
  `.verification/title-aware-heuristic-rerank/20260824-full-maven/maven.log`。
- 基本集成硬门槛全部完成；下一步只做三轮互不重叠、固定范围、只读实现检查。

### 2026-08-24 实现连续检查 `3/3`

基本硬门槛通过后，从 2026-08-24 16:19 至 16:22 CST 完成以下连续无修改检查：

| 轮次 | 固定范围 | 发现问题 | 处理措施 | 结果 |
|---:|---|---|---|---|
| 1/3 | title/chunk 特征边界、`max` 公式、null/blank、数值与排序稳定性、测试 oracle | 无 | 无修改 | 连续计数 `1/3` |
| 2/3 | factory、HTTP 成功/fallback、六类 facade、候选池、权威标题来源、复制语义与复杂度 | 无 | 无修改 | 连续计数 `2/3` |
| 3/3 | PostgreSQL/前端/真实运行证据、双语文档、草案生命周期、回滚和 Git 变更边界 | 无 | 无修改；确认隔离服务、端口和临时数据库已清理 | 连续计数 `3/3` |

生产实现 SHA-256：
`b72b1f21195e6ce759dcea8595ec7c6361f8cf970c5fbd85cc9e96170fbe2e2e`。

实现检查完成后未修改生产代码或测试；下一步提交并推送特性分支，检查 `origin/main` 是否
变化，再按交付工作流处理 main 合并。
