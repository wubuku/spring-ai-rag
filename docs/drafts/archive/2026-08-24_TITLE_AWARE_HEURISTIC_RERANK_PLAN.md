# heuristic rerank 的标题感知相关性实施规划

> 状态：已实施、验收并合入 `main@6a1bbe8e`，历史归档
>
> 规划日期：2026-08-24
>
> 代码基线：本地 `main` / `origin/main` @ `6d3c1d17`
>
> 实施分支：`feat/title-aware-heuristic-rerank-20260824`
>
> worktree：`/Users/yangjiefeng/Documents/wubuku/spring-ai-rag`
>
> 配套进度：[2026-08-24_TITLE_AWARE_HEURISTIC_RERANK_PROGRESS.md](2026-08-24_TITLE_AWARE_HEURISTIC_RERANK_PROGRESS.md)
>
> 近距离上下文：[项目上下文](../../project-context-zh-CN.md)、
> [架构说明](../../architecture-zh-CN.md)、
> [生产质量默认值](../../quality-defaults-zh-CN.md)、
> [测试指南](../../testing-guide-zh-CN.md)和
> [交付工作流](../../delivery-workflow-zh-CN.md)

本文是本轮小范围实施的自包含入口。上一轮 heuristic CJK 词法增强已经合入
`main@6d3c1d17`，规划和进度已归档为
`docs/drafts/archive/2026-08-24_HEURISTIC_CJK_RERANK_*`，稳定行为已经进入双语长青
文档。本轮继续聚焦检索质量和响应速度，只让默认 heuristic reranker 利用候选中已经存在
的权威文档标题，不新增权限、用量账本、数据库访问、模型调用或前端功能。

## 1. 执行摘要

`RetrievalResult` 已包含 `title`。真实向量 SQL 和三个 PostgreSQL 全文 provider 都读取
`rag_documents.title AS document_title`，再由 `RetrievalResultProvenance` 写入候选；
metadata title 只在权威文档标题缺失时作为兼容回退。当前
`HeuristicRerankProvider` 会完整复制 title，却只用 `chunkText` 计算 relevance 和
diversity。

这会漏掉一类高价值信号：产品代码、规范名称、术语或主题只在简洁标题中出现，而命中的正文
chunk 是摘要、表格说明或跨段落上下文。向量或全文召回已经把候选带回后，默认 reranker
仍可能让“原始分略高但主题无关”的候选排在前面。

本轮将每个候选的有效相关性定义为：

```text
contentRelevance = relevance(queryFeatures, chunkFeatures.normalizedText)
titleRelevance = relevance(queryFeatures, normalizedTitle)
effectiveRelevance = max(contentRelevance, 0.9 * titleRelevance)
```

最终评分的其他部分保持不变：

```text
finalScore =
    safeRawScore * (1 - diversityWeight)
    + effectiveRelevance * diversityWeight * 0.5
    + chunkDiversity * diversityWeight * 0.5
```

标题是补充信号，不和正文 relevance 相加；因此标题不能重复放大已经很强的正文命中。
`0.9` 让纯标题命中足以纠正接近候选，又略低于同等正文命中。query 和 chunk 继续使用
上一轮已实现的 CJK-aware、最多 512 个特征的本地提取；title 作为 relevance 目标只需
使用 `Locale.ROOT` 规范化一次，不构造不会参与 diversity 的 token 集合。不增加 SQL、
embedding、HTTP rerank 或 Chat 模型调用。

## 2. 为什么本轮选择它

### 2.1 已核对的生产事实

- `RetrievalResult.title` 是现有公开响应字段，不需要修改 API schema。
- `rag_documents.title` 是 `VARCHAR(255)`，常规创建请求也限制为 255 字符；内部兼容输入
  即使更长也只做一次规范化，不产生无界 title token 集合。
- `HybridRetrieverService` 的向量查询和 `PgEnglishFtsProvider`、
  `PgTrgmFulltextProvider`、`PgJiebaFulltextProvider` 都选择权威文档标题。
- `RetrievalResultProvenance.applyDocumentFields` 优先使用 `document_title`，仅在其为空时
  回退到 metadata 的 `title`。
- `HeuristicRerankProvider` 已完整复制 `title`、metadata 和来源 provenance，但评分只读
  `chunkText`。
- `ReRankingService` 是 Search POST、KNOWLEDGE、Agent 检索工具、JSON record、
  Evaluation 和旧 Advisor 的共享 facade；本轮无需修改调用方。
- `HttpRerankProvider` 成功路径继续只把 chunk 正文发给远程 provider；缺少凭据、超时或
  非法响应时的 heuristic fallback 会自动获得本轮能力。
- 候选池最多 100，query/chunk 最多 512 个词法特征；每个候选只增加一次 title
  规范化和有界 query-term 查找。
- 现有 `HybridRetrieverRrfPostgresIntegrationTest` 可以从真实 PostgreSQL/pgvector
  生成带权威 title 的候选，并通过真实 factory 和 `ReRankingService` 证明排序变化。

### 2.2 候选项比较

| 候选 | 质量收益 | 延迟/成本 | 风险 | 本轮决定 |
|---|---|---|---|---|
| heuristic 标题感知 relevance | 利用现有高信号标题纠正接近候选 | 有界本地计算，无 I/O | 单 provider、易回滚 | 实施 |
| 自适应跳过多查询扩展 | 简单 query 可能更快 | 需要可靠分类和 trace 语义 | 可能损失召回 | 延后 |
| 跨请求检索缓存 | 重复 query 可能更快 | 需要 mutation/profile/scope 失效 | 陈旧或越界证据风险高 | 延后 |
| 自适应 RRF 权重 | 可能改善语言/查询类型适配 | 需要更完整 goldenset | 默认排序面较大 | 延后 |
| 权限、用量账本 | 不直接改善核心质量或速度 | 跨 schema/运营 | 用户明确低优先 | 不实施 |

## 3. 冻结范围

### 3.1 生产代码

只修改：

- `spring-ai-rag-core/src/main/java/com/springairag/core/retrieval/rerank/HeuristicRerankProvider.java`

允许在该类中增加私有常量、record 或 helper。不得新增公共 SPI、Spring bean、配置项、
数据库迁移或请求参数。`RerankProvider`、`ReRankingService`、`HttpRerankProvider`
的公开签名不变。

### 3.2 测试

- 扩展 `HeuristicRerankProviderTest`：直接覆盖标题相关性、兼容、diversity 隔离、长标题
  和字段复制。
- 扩展 `ReRankingServiceTest`：通过真实 heuristic provider 证明 facade 可利用标题提升
  reciprocal rank。
- 扩展 `HttpRerankProviderTest`：证明 HTTP 不可用时的 heuristic fallback 使用标题；
  成功 HTTP 请求体和结果映射保持不变。
- 扩展 `HybridRetrieverRrfPostgresIntegrationTest`：真实 pgvector 候选正文相同且原始
  向量顺序先错，只有权威文档标题包含 query；真实 factory/facade 必须纠正顺序。
- 复用 `scripts/verify-rerank-document-diversity.sh` 的 focused、PostgreSQL、WebUI、
  Mock/真实 Playwright、goldenset、真实 provider 和运行时门槛。它已覆盖上述测试类，
  不为一个单类增强复制新的大型验收脚本。

### 3.3 长青文档

行为落地后同步：

- `docs/architecture.md` / `docs/architecture-zh-CN.md`
- `docs/quality-defaults.md` / `docs/quality-defaults-zh-CN.md`
- `docs/testing-guide.md` / `docs/testing-guide-zh-CN.md`

配置和 REST schema 不变，因此不修改 `configuration*` 或 `rest-api*`。规划和进度保留
中文单语；稳定行为只在双语长青文档中维护。

### 3.4 非目标

- 不把 title 拼接进 chunk，也不改变 HTTP rerank 成功请求体；
- 不让 title 参与 Jaccard diversity 或文档覆盖选择器；
- 不把 title relevance 与 chunk relevance 相加；
- 不新增可调 title weight、开关、语言检测或请求字段；
- 不改变全文 SQL、向量 SQL、索引、schema 或 Embedding Profile；
- 不改变 `diversity-weight`、candidate limit 或每文档 chunk 偏好的默认值；
- 不做同义词、别名、拼写纠错、繁简转换或语义标题模型；
- 不记录 query/title token，不增加敏感 trace 或 metrics 字段；
- 不修改 WebUI 交互，不以截图或人工首次验收证明正确性。

## 4. 冻结的评分契约

### 4.1 标题权重

新增私有常量：

```text
TITLE_RELEVANCE_WEIGHT = 0.9f
```

决策理由：

1. 标题通常是高密度主题信号，纯标题命中应能纠正原始分非常接近的候选；
2. 标题可能是宽泛名称，不能与正文同权后再叠加，避免过度提升；
3. `max(content, weightedTitle)` 保证强正文命中不被降低，也不因标题重复命中而加倍；
4. 固定内部常量保持本轮小而可逆，未来只有 goldenset 和运行时数据证明必要时才配置化。

权重只作用于 title relevance。若 `diversityWeight=0`，和现有行为一样，所有词法
relevance 都不影响最终分数。

### 4.2 null、blank 与兼容

- null、空字符串和纯空白 title 提取为空特征，`titleRelevance=0`；
- 此时 `effectiveRelevance=contentRelevance`，最终分数和排序与上一版本精确一致；
- 普通无 CJK、CJK、混合 Latin/数字标题统一使用 `Locale.ROOT` 规范化；CJK-aware
  query 特征通过 `indexOf` 在规范化标题中匹配；
- query 和 chunk 各自最多 512 个特征；title 不生成 similarity token，也不和 chunk
  拼接；
- relevance 仍使用规范化文本中的首次 `indexOf` 位置奖励并限制在 `[0,1]`；
- `NaN` raw score 继续按现有规则归零；其他 raw score 行为不在本轮调整。

### 4.3 diversity 隔离

- diversity 继续只比较 `chunkText` 的 `similarityTerms`；
- 标题相同、不同或为空，都不能改变两个 chunk 的 Jaccard similarity；
- 完全相同的另一非空 chunk 继续得到 similarity `1`；
- null/blank chunk 继续得不到无信息 diversity 奖励；
- 文档级覆盖仍只看 `documentId` 和 provider 排名。

### 4.4 输出与 provider 兼容

- 继续创建新的 `RetrievalResult` 并复制 document/title/chunk、原始分项、metadata 和
  provenance；
- 继续按最终 score 降序并遵守 `rankingDepth`；
- heuristic 默认、未知 provider 回落和 HTTP heuristic fallback 使用标题感知逻辑；
- HTTP provider 成功路径不变：仍只发送 chunk 列表，远程分数仍是最终 provider 分数；
- no-op provider、禁用 rerank、空结果和数量边界不变；
- 不改变任何 API JSON 字段，仅可能改变 heuristic 生效时的候选顺序与最终 score。

## 5. 复杂度与数据边界

对 `n` 个候选：

```text
query features: 1 次
chunk features: n 次
normalized title: n 次
preprocessing: O(queryChars + sum(chunkChars + titleChars))
relevance worst case:
    O(n * queryFeatureCount * (chunkChars + titleChars))
chunk diversity: O(n^2 * f)
```

其中候选池 `n<=100`，query/chunk 的特征数 `f<=512`，权威数据库标题最多 255 字符。
relevance 延续现有“每个 query term 在目标文本中执行首次 `indexOf`”的语义；新增标题
开销由候选数、query 特征上限和标题长度共同约束。实现使用一个候选内部 record 保存
chunk 特征和规范化 title，确保不会在评分循环中重复规范化或提取。它不增加外部调用，
也不在请求之间缓存文本或 token。

## 6. 实施切片

### Slice A：一次性测试矩阵

在生产实现前一次性写完本轮测试：

1. 只有 title 命中时，英文、CJK 和混合产品 ID 至少覆盖一条确定性提升路径；
2. null/blank title 与无 title 候选的最终 score 和顺序精确相同；
3. chunk relevance 高于 `0.9 * titleRelevance` 时，标题不能降低或覆盖正文分；
4. 相同 chunk 使用不同 title 时，diversity 结果不变；
5. 255 字符边界和更长内部兼容 title 都返回有限分数，不产生 title token 集合；
6. title、metadata、vector/fulltext score 和 provenance 继续完整复制；
7. `rankingDepth`、空列表和既有 CJK/英文排序回归继续通过；
8. `ReRankingService` 的 title-only golden case 提升 MRR；
9. HTTP 成功路径请求体不包含 title，失败 fallback 的 title-only case 可提升；
10. 真实 PostgreSQL/pgvector 候选先错后由权威 title 和真实 heuristic facade 纠正。

先运行测试获得旧实现红灯，确认失败只集中在新契约；测试 oracle 如有问题必须在写生产代码前
一次性修正，不能把 review 阶段变成零碎补测试循环。

### Slice B：单类生产实现

1. 增加 `TITLE_RELEVANCE_WEIGHT=0.9f`；
2. 一次性预计算 query features；
3. 为每个候选一次性预计算 chunk features 和 normalized title；
4. 按冻结公式计算 `effectiveRelevance`；
5. diversity 只读取 chunk features；
6. 保持输出复制、排序、limit 和 test-visible helper 兼容。

### Slice C：PostgreSQL 证明

1. 插入两个正文相同、标题不同的真实文档；
2. 相关文档 title 包含 CJK + 产品 ID query，无关文档 title 不匹配；
3. 让无关文档的真实向量 score 略高，断言 SQL 候选顺序先错；
4. 断言候选中的 title 来自 `rag_documents.title`；
5. 用 `RerankProviderFactory` 和 `ReRankingService` 断言相关文档成为 top 1；
6. 整类测试必须 `tests>0`、`failures=0`、`errors=0`、`skipped=0`。

### Slice D：长青文档与验收

1. 双语记录 title relevance 的公式、权重、边界、复杂度与 HTTP 成功路径不变；
2. 更新 rerank 专项测试说明；
3. 执行完整专项 runner、全量 Maven 和静态门槛；
4. 所有硬门槛通过后才进行实现连续三轮只读检查。

## 7. 一次性验收矩阵

### 7.1 后端快速与 PostgreSQL

```bash
mvn -pl spring-ai-rag-core -am \
  -Dtest='HeuristicRerankProviderTest,ReRankingServiceTest,HttpRerankProviderTest,RerankProviderFactoryTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test

TESTCONTAINERS_RYUK_DISABLED=true DOCKER_API_VERSION=1.40 \
  mvn -pl spring-ai-rag-core -am \
    -Dhybrid-rrf.it.enabled=true \
    -Dtest=HybridRetrieverRrfPostgresIntegrationTest \
    -Dsurefire.failIfNoSpecifiedTests=false test
```

PostgreSQL 结果必须解析 Surefire XML，不能把条件跳过报告为通过。

### 7.2 基本集成硬门槛

优先复用会保存完整证据的专项 runner：

```bash
RERANK_DIVERSITY_RUN_ID=<run-id> \
RERANK_DIVERSITY_BACKEND_PORT=<isolated-backend-port> \
RERANK_DIVERSITY_FRONTEND_PORT=<isolated-webui-port> \
RERANK_DIVERSITY_MOCK_PORT=<isolated-mock-port> \
./scripts/verify-rerank-document-diversity.sh
```

它必须覆盖：

- focused backend 测试和真实 PostgreSQL/pgvector；
- `mvn clean compile test-compile`；
- WebUI TypeScript、Vitest、生产构建和 alignment；
- Search/Chat/Navigation 核心 Mock Playwright；
- `scripts/dev.sh` 启动隔离 PostgreSQL profile 后端和非 Mock WebUI；
- 真实 Search DOM/网络/JSON Playwright；
- retrieval goldenset、版本化质量回归和真实 provider baseline；
- cap=`0` / cap=`2` 的 Search/Chat 运行时观测；
- 禁悲观锁、项目文档、响应契约和 whitespace 门禁。

前端证据只使用 DOM 可见性、可访问状态、网络、JSON 和自动化断言，不使用截图。

专项 runner 后单独执行：

```bash
mvn test
```

全量 Maven 的既有条件性 skipped 必须如实记录；本任务 PostgreSQL 类另行要求零 skipped。

### 7.3 真实 LLM 与服务启动

- 只有 Mock/focused/PostgreSQL 先通过后才进入真实调用；
- runner 使用 `.env`、一次性 PostgreSQL 和隔离端口通过 `scripts/dev.sh` 启动；
- 真实 provider 调用期间持续观察 runner 和后端日志；
- 断言健康检查、真实 Embedding/Chat、Search/KNOWLEDGE 协议、sources/citations 和
  WebUI 代理路径；
- 不把真实自然语言逐字文本作为 deterministic 排序 oracle；
- `.env` 凭据不可用时保留明确失败/限制，不以 Mock 冒充真实验证。

### 7.4 静态和文档门槛

```bash
./scripts/verify-no-pessimistic-locks.sh
./scripts/verify-project-docs.sh
./scripts/run-retrieval-regression.sh --self-test
git diff --check
```

专项 runner 已执行的门槛仍需在 summary 中逐项确认，不能只看脚本退出码。

## 8. 规划与实现收敛

规划检查固定为：

1. 价值、范围、评分公式、title 权威来源、兼容和非目标；
2. 生产调用链、预计算、复杂度、diversity 隔离、provider/fallback 与回滚；
3. 一次性测试、PostgreSQL、前端、真实 LLM、证据、文档和 Git 交付。

实现检查必须在全部基本硬门槛通过后执行，固定为：

1. title/chunk 特征边界、权重公式、null/blank、有限分数和排序稳定性；
2. facade/factory/HTTP fallback、diversity 隔离、候选限制、复制语义和性能；
3. PostgreSQL/前端/真实运行证据、双语文档、草稿生命周期、回滚和 Git。

发现影响正确性、兼容、成本安全或数据一致性的实质问题时立即修复，重跑受影响测试与全部
基本硬门槛，并把实现检查计数重置为 `0/3`。风格偏好和可选优化不扩展本轮范围。

## 9. 风险、回滚与完成定义

| 风险 | 控制 |
|---|---|
| 宽泛标题过度提升 | title relevance 乘 `0.9`，与正文取 max 而非相加 |
| 标题和正文重复放大 | `max` 保证只选更强信号 |
| 标题污染 diversity | diversity 只读取 chunk features，并有专门回归 |
| 长标题增加计算 | 权威标题最多 255 字符、候选最多 100、只规范化一次且不生成 title token |
| 英文/CJK 旧排序漂移 | blank title 精确兼容和现有整套词法回归 |
| HTTP provider 契约变化 | 成功请求体测试锁定只发送 chunk，只有 fallback 变化 |
| 真实模型波动 | 排序由 deterministic 单测和 PostgreSQL 集成证明 |

快速回滚只需撤销 `HeuristicRerankProvider` 和对应测试/文档提交，不涉及配置、schema、
数据迁移、重嵌入或数据修复。必要时也可临时设置 `rag.rerank.enabled=false` 回到无重排
路径，但这不是首选长期回滚。

只有以下全部成立才完成本轮：

1. 规划连续检查达到 `3/3`；
2. 完整测试矩阵在生产实现前一次性落下并证明旧实现红灯；
3. focused、真实 PostgreSQL、Maven 编译/全量测试和服务启动通过；
4. WebUI TypeScript/Vitest/build/alignment/核心 Mock Playwright 通过；
5. 隔离真实全栈、真实 Search Playwright 和获准真实 LLM 验证通过；
6. 双语长青文档和静态门槛通过；
7. 实现连续检查达到 `3/3`；
8. 特性分支提交并推送；若 `origin/main` 已变化，merge 后从头复验；
9. 特性分支合回 `main`、推送，并确认 `main`、`origin/main` 和工作区状态。
