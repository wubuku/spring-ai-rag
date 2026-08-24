# heuristic rerank 的 Latin/数字词边界感知实施规划

> 状态：规划连续检查 `3/3` 完成，待一次性测试与实施
>
> 规划日期：2026-08-24
>
> 代码基线：本地 `main` / `origin/main` @ `cb222c21`
>
> 实施分支：`feat/boundary-aware-heuristic-rerank-20260824`
>
> worktree：`/Users/yangjiefeng/Documents/wubuku/spring-ai-rag`
>
> 配套进度：[NEXT_HIGH_VALUE_FEATURES_PROGRESS.md](NEXT_HIGH_VALUE_FEATURES_PROGRESS.md)
>
> 近距离上下文：[项目上下文](../project-context-zh-CN.md)、
> [架构说明](../architecture-zh-CN.md)、
> [生产质量默认值](../quality-defaults-zh-CN.md)、
> [测试指南](../testing-guide-zh-CN.md)和
> [交付工作流](../delivery-workflow-zh-CN.md)

本文是本轮小范围实施的自包含入口。上一轮标题感知 heuristic rerank 已经合入并推送
`main@6a1bbe8e`，归档收尾为 `main@cb222c21`；历史 plan/progress 位于
`docs/drafts/archive/2026-08-24_TITLE_AWARE_HEURISTIC_RERANK_*`，稳定行为已进入双语
长青文档。

本轮继续只做默认 heuristic reranker 的确定性本地质量改进。目标是消除普通 Latin/数字
查询项在更长字母数字串中的误命中，并让被句末或成对标点包裹的普通查询项仍可按完整词命中。
不新增权限、用量账本、配置、API、迁移、数据库查询、embedding、外部 rerank 或 Chat 模型调用。

## 1. 执行摘要

当前 `HeuristicRerankProvider` 会把 query 提取为最多 512 个词法特征，再对每个 term 执行：

```text
position = normalizedText.indexOf(term)
```

这对 CJK bigram 是有意的局部匹配，但对普通 Latin/数字项缺少词边界。例如：

```text
query "rag ai" 误命中 "storage chair"
query "9042" 误命中 "19042"
```

标题感知 relevance 已经让同一逻辑同时作用于正文和权威标题，因此短词误命中可能把高原始分
的无关候选保留在相关候选之前。另一方面，普通无 CJK query 按空白切分，`RAG?`、`"AI"`、
`(ZX-9042)` 等 term 会把句末或包裹标点带入匹配，从而漏掉正文中的完整词。

本轮先把 query 的有序词法特征一次性预计算为 relevance term，再执行匹配：

```text
relevanceTerms = queryTerms.map(queryTerm -> {
    matchTerm = stripOuterSentencePunctuation(queryTerm)
    boundaryAware = matchTerm 首尾为 Unicode 字母/数字且不含 CJK
})

if relevanceTerm.boundaryAware:
    查找 normalizedText 中第一个满足以下条件的完整 occurrence:
      occurrence 前方不存在非 CJK Unicode 字母/数字
      occurrence 后方不存在非 CJK Unicode 字母/数字
else:
    保持原有 substring occurrence 语义
```

`stripOuterSentencePunctuation` 只移除明确的句末/包裹标点，不移除 `+`、`#`、`-`、`_`、
`/` 等技术标识符内部或末尾符号。因此：

- `rag` 不再命中 `storage`，`ai` 不再命中 `chair`；
- `RAG?` 可命中 `RAG-based`；
- `(ZX-9042)` 可命中 `ZX-9042`；
- `SpringAI` 可命中 `中文SpringAI检索`，CJK/非 CJK script transition 视为边界；
- `C++`、`C#`、`api/v1`、`ZX-9042` 保留原 term；
- CJK bigram、单字符 CJK 和混合 CJK 提取保持原 substring 语义；
- 同一 rerank 中 query term 的标点剥离和边界分类只执行一次，正文与标题共同复用；
- position bonus 使用第一个合法完整 occurrence 的实际位置；
- relevance 公式、title 权重、diversity、raw score、排序和候选选择公式都不改变。

## 2. 已核对的当前事实

### 2.1 生产调用链

`HeuristicRerankProvider` 是默认生产 `rag.rerank.provider=heuristic` 的实现，也由
`HttpRerankProvider` 的失败降级路径复用。`ReRankingService` 是统一 facade，Search、
JSON record Search、KNOWLEDGE 的 Modular RAG post processor、Knowledge Tool、旧 advisor
兼容路径和评测执行器最终都通过该 facade 或同一 provider。

本轮只修改 provider 内部 relevance occurrence 判定，因此所有这些入口自动一致受益，
不需要修改 controller DTO、SSE、OpenAPI、前端请求或配置绑定。

### 2.2 当前词法特征

- query/chunk 通过 `Locale.ROOT` 小写规范化；
- 普通无 CJK segment 当前按空白保留为 term；
- 包含 CJK 的 segment 会提取相邻 Unicode code-point bigram；
- 混合 segment 中的 Latin/数字连续 run 独立形成 term；
- 每个输入最多提取 512 个有序 term；
- chunk 的 `similarityTerms` 只用于 diversity，本轮不得改变。

本轮不重写 `extractFeatures`，避免普通 chunk diversity、CJK 提取、特征上限和已交付排序
发生无关变化。标点剥离只发生在 query term 进入 relevance occurrence 查找时。

### 2.3 当前评分与标题边界

```text
contentRelevance = relevance(queryTerms, normalizedChunk)
titleRelevance = relevance(queryTerms, normalizedTitle)
effectiveRelevance = max(contentRelevance, 0.9 * titleRelevance)

finalScore =
    safeRawScore * (1 - diversityWeight)
    + effectiveRelevance * diversityWeight * 0.5
    + chunkDiversity * diversityWeight * 0.5
```

本轮不改变任何权重。权威 title 仍只参与 relevance，不参与 diversity；null/blank title
继续保持旧路径。HTTP rerank 成功请求仍只发送 chunk 正文。

### 2.4 候选、复杂度与数据边界

- production candidate limit 默认 20，配置硬上限 100；
- title 数据库列上限 255 字符；
- chunk/query 仍受既有 API、chunking 和 512 特征上限约束；
- occurrence 查找最多对当前 term 的多个 substring 候选做左右 code-point 边界检查；
- 不创建正则表达式，不在候选循环内重新提取 query/chunk 特征或重新分类 query term。

因此 query term 准备为 `O(q)` 且每次 rerank 只做一次；正文/标题匹配复杂度仍由有界候选数、
query term 数和候选文本长度决定。相比原始 `indexOf` 只增加命中候选位置的常数级 Unicode
边界判断。最坏情况下会跳过多个非法内嵌 occurrence，但不会产生数据库、网络、模型或额外
对象图 fan-out。

## 3. 目标、非目标与价值

### 3.1 目标

1. 普通 Latin/数字 query term 只在完整字母数字边界上贡献 relevance。
2. 句末或成对包裹标点不阻止普通 term 的完整词命中。
3. 保持 CJK bigram、混合 ID、技术符号词、title relevance 和 diversity 边界。
4. 用 provider、facade、HTTP fallback 和真实 PostgreSQL/pgvector 候选证明排序修复。
5. 不增加外部调用与配置复杂度，并保持 WebUI、Search、Chat 和真实 provider 链路可运行。

### 3.2 非目标

- 不实现词典分词、stemming、lemmatization、拼写纠正、同义词或繁简转换；
- 不重写全文 SQL、RRF、query rewrite、query expansion 或 embedding；
- 不改变 heuristic 权重、候选池、每文档 chunk 选择或 HTTP cross-encoder 协议；
- 不把 identifier prefix 搜索定义为新产品契约；
- 不修改 API、SSE、数据库 schema、Flyway、配置项或 WebUI 功能；
- 不处理 query term 去重、stop words、重复 term 权重或负向词；
- 不调整权限、Principal、quota 或用量账本。

### 3.3 为什么本轮优先

- 直接修复所有默认 heuristic/fallback 入口共用的确定性误排序；
- 标题感知上线后，短 Latin term 误命中权威标题的影响更明显；
- 完全本地、无额外 I/O，响应速度不会引入新的远端尾延迟；
- 范围集中在一个生产类和现有测试矩阵，适合快速实施、验证和提交；
- 与用户当前优先级一致：检索质量、Chat 证据质量和响应速度，高于权限与用量治理。

## 4. 冻结设计

### 4.1 query term 的外层标点

新增私有 helper，把 term 首尾连续的以下句末/包裹标点剥离：

```text
ASCII:
  . , ! ? ; : ' " ( ) [ ] { }

Unicode:
  ， 。 ！ ？ ； ：
  “ ” ‘ ’
  （ ）
  【 】 〔 〕
  《 》 〈 〉
  「 」 『 』
```

规则：

1. 只处理 term 首尾，内部字符不变；
2. `+`、`#`、`-`、`_`、`/`、`\` 不在剥离集合；
3. 剥离后为空时回退原 term，避免标点-only query 产生除零或空字符串全命中；
4. 不修改 `orderedTerms` 和 `similarityTerms`，而是一次性生成不可变
   `RelevanceTerm(value, boundaryAware)` 列表；
5. CJK term 通常已由 mixed extractor 去掉标点；即使进入 helper，也只影响包裹标点。

### 4.2 词边界

仅当以下条件全部成立时启用完整词边界：

1. term 非空；
2. term 不含 CJK code point；
3. 首 code point 和尾 code point 都是 `Character.isLetterOrDigit`。

合法 occurrence：

```text
leftBoundary =
    occurrence 位于文本开头
    OR occurrence 前一个 Unicode code point 不是字母/数字
    OR occurrence 前一个 Unicode code point 是 CJK

rightBoundary =
    occurrence 位于文本结尾
    OR occurrence 后一个 Unicode code point 不是字母/数字
    OR occurrence 后一个 Unicode code point 是 CJK
```

下划线、连字符、斜线和标点都视为分隔符，允许 `rag-based`、`rag_system`、`api/v1`
中的完整段命中。字母或数字相邻时视为同一连续标识符，拒绝 `rag`→`storage`、
`ai`→`openai`、`9042`→`19042`。CJK 与非 CJK 的 script transition 视为边界，
从而保持现有 mixed extractor 已承诺的 Latin/数字 run 语义，例如 `中文SpringAI检索`
中的 `SpringAI` 和 `型号9042说明` 中的 `9042` 仍可命中。

含 CJK term 或首尾不是字母数字的技术 term 保持原 `indexOf` substring 语义，例如 CJK
bigram、`C++` 和 `C#`。这避免把 Latin 空格词边界规则错误应用到连续 CJK 文本，也不破坏
符号型语言/技术名。

### 4.3 occurrence 与 position bonus

新增私有 `prepareRelevanceTerms(queryFeatures.orderedTerms())`，每次 `rerank` 在候选循环前
调用一次；public 测试 helper 的单次 relevance 计算也只准备一次。正文和标题 relevance
都接收同一个不可变 `List<RelevanceTerm>`。

新增私有 `findMatchPosition(normalizedText, relevanceTerm)`：

1. 从 offset `0` 调用 `indexOf`；
2. `boundaryAware=false` 直接返回首个 position；
3. `boundaryAware=true` 检查左右 code point；
4. 非法内嵌命中从当前 position 后至少一个 code point 继续搜索；
5. 没有合法 occurrence 返回 `-1`。

`calculateRelevanceScore` 的 match count、term denominator、position bonus 上限和最终
`[0,1]` clamp 保持原样，仅把裸 `indexOf` 替换为该 helper。

### 4.4 兼容与可逆边界

- 精确完整英文词、数字、产品 ID 和以分隔符连接的词继续命中；
- CJK 相关性与 diversity 不变；
- null/blank query、chunk、title 和标点-only query 继续返回有限值；
- 仅旧版“更长字母数字串内部 substring 算命中”的行为有意改变；
- 没有新配置开关。回滚只需撤销 provider、测试和文档提交；
- 需要紧急运行时回滚时，可用既有 `rag.rerank.enabled=false` 暂时关闭重排，但长期回滚
  应撤销本轮提交，不新增永久兼容开关。

## 5. 文件级实施切片

### Slice A：一次性测试与旧实现红灯

在生产修改前一次性扩展：

- `HeuristicRerankProviderTest`
- `ReRankingServiceTest`
- `HttpRerankProviderTest`
- `HybridRetrieverRrfPostgresIntegrationTest`

一次性覆盖所有冻结边界，并先运行 focused 和 PostgreSQL 测试证明旧实现只在新契约上失败。
测试 oracle 冻结后才修改生产类，避免 review 阶段零碎补测试。

### Slice B：单类生产实现

只修改：

- `spring-ai-rag-core/src/main/java/com/springairag/core/retrieval/rerank/HeuristicRerankProvider.java`

加入 query relevance term 一次性预计算、外层标点剥离、边界分类和合法 occurrence 搜索；
不修改抽取、diversity、配置或 facade。

### Slice C：双语长青文档

稳定事实同步到：

- `docs/architecture.md` / `docs/architecture-zh-CN.md`
- `docs/quality-defaults.md` / `docs/quality-defaults-zh-CN.md`
- `docs/testing-guide.md` / `docs/testing-guide-zh-CN.md`

规划与进度保持中文单语。行为落地后才写长青文档，不把外部 Client 特定背景带入本项目。

### Slice D：完整验收、收敛与 Git 交付

执行专项 runner、全量 Maven、静态门槛、连续三轮实现检查；提交并推送特性分支。若
`origin/main` 变化，先 merge 到特性分支并从头执行固定验收序列；最终合回并推送 `main`，
再归档本轮 plan/progress。

## 6. 一次性测试矩阵

### 6.1 provider 单元/行为测试

1. `rag` 不命中 `storage`，`ai` 不命中 `chair`；
2. `9042` 不命中 `19042`，但可命中 `ZX-9042`；
3. `RAG?`、`"AI"`、`(ZX-9042)` 可命中由分隔符包围的完整 term；
4. `C++`、`C#`、`api/v1`、`ZX-9042` 保持匹配；
5. CJK bigram、单字符 CJK、混合 CJK/Latin/数字现有用例继续通过，并覆盖
   `中文SpringAI检索`、`型号9042说明` 的无分隔 script transition；
6. 文本先出现非法内嵌 occurrence、后出现合法 occurrence 时，使用后者 position；
7. 标点-only、null/blank 和长文本分数有限；
8. title false-positive distractor 不再压过真正 title 命中；
9. title 不影响 diversity，blank title 精确兼容；
10. metadata、provenance、score 分项和排序 limit 继续复制/生效。

### 6.2 facade 与 HTTP fallback

1. `ReRankingService` 对 boundary false-positive 候选产生正确最终顺序；
2. HTTP rerank 成功请求/响应契约不变，仍只发送 chunk 正文；
3. HTTP 缺凭据、异常或非法响应时，heuristic fallback 使用边界规则；
4. provider/fallback 输出仍受 candidate limit、ranking depth 和最终 limit 约束。

### 6.3 PostgreSQL/pgvector 集成

使用真实 PostgreSQL 16 + pgvector、V1–V48 迁移：

1. 插入高 vector raw score 的 distractor，title/chunk 使用 `storage chair 19042`；
2. 插入稍低 raw score 的 relevant，权威 title 使用 `RAG AI ZX-9042`；
3. 真实向量 SQL 返回顺序先错，并映射正确 title/chunk/provenance；
4. 默认 heuristic 经 facade 后将 relevant 排到第一；
5. 测试类必须实际执行且 `tests>0`、`failures=0`、`errors=0`、`skipped=0`。

### 6.4 基本集成硬门槛

优先复用保存完整证据的专项 runner：

```bash
RERANK_DIVERSITY_RUN_ID=<run-id> \
RERANK_DIVERSITY_BACKEND_PORT=<isolated-backend-port> \
RERANK_DIVERSITY_FRONTEND_PORT=<isolated-webui-port> \
RERANK_DIVERSITY_MOCK_PORT=<isolated-mock-port> \
./scripts/verify-rerank-document-diversity.sh
```

它必须覆盖：

- focused 后端完整 rerank 调用链；
- 真实 PostgreSQL/pgvector；
- `mvn clean compile test-compile`；
- WebUI TypeScript、Vitest、生产 build、alignment；
- Search/Chat/Navigation 核心 Mock Playwright；
- `scripts/dev.sh` 隔离 PostgreSQL profile 后端和非 Mock WebUI；
- 真实 Search DOM/网络/JSON Playwright，不使用截图；
- goldenset、版本化质量回归、真实 provider baseline；
- Search/Chat 运行时结果、sources/citations、rerank latency 观测；
- 禁悲观锁、项目文档、响应契约和 whitespace 门禁。

随后独立执行：

```bash
mvn test
```

全量 Maven 的既有条件性 skipped 如实记录；本任务 PostgreSQL 类要求零 skipped。

### 6.5 真实 LLM 与运行时

- Mock/focused/PostgreSQL 先通过后才调用真实 provider；
- runner 使用 `.env`、一次性 PostgreSQL 和隔离端口通过 `scripts/dev.sh` 启动；
- 持续观察 runner 和后端日志，不静默等待；
- 真实 embedding/Chat 只验证 provider、Search/KNOWLEDGE、sources/citations 和代理协议；
- deterministic 边界排序由单元与 PostgreSQL fixture 证明，不以自然语言模型输出作 oracle；
- 凭据不可用时保留明确失败/限制，不以 Mock 冒充真实验证。

### 6.6 静态门槛

```bash
./scripts/verify-no-pessimistic-locks.sh
./scripts/verify-project-docs.sh
./scripts/run-retrieval-regression.sh --self-test
git diff --check
```

## 7. 规划与实现收敛

规划检查固定为：

1. 价值、范围、误命中样例、标点集合、词边界、兼容与非目标；
2. Unicode/code-point 实现、CJK/技术词隔离、复杂度、调用链和回滚；
3. 一次性测试、PostgreSQL、前端、真实 LLM、证据、文档和 Git 交付。

实现检查必须在全部基本硬门槛通过后执行，固定为：

1. term 标点剥离、Unicode 左右边界、非法后合法 occurrence、CJK/技术词和数值稳定性；
2. title/chunk relevance、diversity 隔离、facade/factory/HTTP fallback、候选限制和性能；
3. PostgreSQL/前端/真实运行证据、双语文档、草稿生命周期、回滚和 Git。

发现影响正确性、兼容、成本安全或数据一致性的实质问题时立即修复，重跑受影响测试与全部
基本硬门槛，并把实现检查计数重置为 `0/3`。风格偏好和可选优化不扩展本轮范围。

## 8. 风险、回滚与完成定义

| 风险 | 控制 |
|---|---|
| prefix/substring 用户依赖旧误命中 | 只改变 heuristic 本地重排，不改变召回；完整 ID 和分隔符段仍命中 |
| CJK 被 Latin 边界误伤 | 含 CJK term 明确保持 substring；现有 CJK 全矩阵回归 |
| C++/C#/ID 被标点剥离破坏 | 标点白名单不含 `+ # - _ / \`，专门测试 |
| Unicode surrogate / script 边界错误 | 使用 code point API，并把 CJK/非 CJK transition 明确定义为边界 |
| 非法 occurrence 搜索死循环 | 每次至少推进一个 code point，并覆盖先非法后合法测试 |
| 标题感知放大误判 | provider/title/facade/PostgreSQL 测试锁定 false-positive 排序 |
| 性能下降 | query term 每次 rerank 只准备一次；候选和 term 已有上限；无正则、无 I/O；运行时记录 rerank latency |
| HTTP provider 契约漂移 | 成功请求体测试锁定只发送 chunk |

只有以下全部成立才完成本轮：

1. 规划连续检查达到 `3/3`；
2. 一次性测试在生产实现前落下并证明旧实现红灯只命中新契约；
3. focused、真实 PostgreSQL、Maven 编译/全量测试和服务启动通过；
4. WebUI TypeScript/Vitest/build/alignment/核心 Mock Playwright 通过；
5. 隔离真实全栈、真实 Search Playwright 和获准真实 LLM 验证通过；
6. 双语长青文档和静态门槛通过；
7. 实现连续检查达到 `3/3`；
8. 特性分支提交并推送；必要时 merge `origin/main` 后从头复验；
9. 特性分支合回 `main`、推送，归档 plan/progress 并确认工作区干净。
