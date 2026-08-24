# heuristic rerank 的 CJK 词法质量与有界计算实施规划

> 状态：实施与验收完成，实现连续检查 `3/3`，待 Git 交付
>
> 规划日期：2026-08-24
>
> 代码基线：本地 `main` / `origin/main` @ `5dac7af5`
>
> 实施分支：`feat/heuristic-cjk-rerank-20260824`
>
> worktree：`/Users/yangjiefeng/Documents/wubuku/spring-ai-rag`
>
> 配套进度：[2026-08-24_HEURISTIC_CJK_RERANK_PROGRESS.md](2026-08-24_HEURISTIC_CJK_RERANK_PROGRESS.md)
>
> 近距离上下文：[项目上下文](../../project-context-zh-CN.md)、
> [架构说明](../../architecture-zh-CN.md)、[配置参考](../../configuration-zh-CN.md)、
> [生产质量默认值](../../quality-defaults-zh-CN.md)、
> [测试指南](../../testing-guide-zh-CN.md)和
> [交付工作流](../../delivery-workflow-zh-CN.md)

本文是本轮小范围实施的自包含入口。上一轮 KNOWLEDGE 多查询证据合并已经合入
`main@5dac7af5`，规划和进度已归档为
`docs/drafts/archive/2026-08-24_KNOWLEDGE_EVIDENCE_JOINER_*`，稳定行为已经进入双语
长青文档。本轮只改进默认 heuristic reranker 的本地词法内核，不引入外部客户背景，
不扩大权限、用量账本、多 Collection 或远程模型调用范围。

## 1. 执行摘要

生产 profile 默认启用 `rag.rerank.provider=heuristic`。当前
`HeuristicRerankProvider` 的 relevance 和 diversity 都使用
`String.split("\\s+")`：

- 无空格的中文、日文和韩文文本通常被当成一个完整 token；
- 只有整段 query 原样出现在 chunk 中才容易获得 relevance，词序变化或局部短语匹配
  基本失效；
- 两个相关 CJK chunk 的 Jaccard 词集合通常完全不相交，diversity 不能识别近似证据；
- rerank 每处理一个候选，都会为它和其他候选重复拆词，候选池达到默认 20、上限 100
  时产生不必要的重复本地计算；
- 当前以“文本不相等”代替“不是当前候选”来跳过 self comparison，完全相同的两个
  chunk 反而不会互相产生 similarity=1 的重复惩罚。

本轮在 `HeuristicRerankProvider` 内增加确定性、Unicode-aware、有界的词法特征提取：

```text
query / candidate chunk
  -> Locale.ROOT 小写
  -> 普通无 CJK 空白词保持既有 token 语义
  -> CJK 连续片段生成相邻 bigram；单字符片段保留单字符
  -> 混合片段中的 Latin/数字连续 run 独立保留
  -> 每段输入最多 512 个特征
  -> rerank 内预计算 query 与每个候选的特征
  -> relevance / pairwise diversity 复用特征
```

对 `n` 个候选、每个文本扫描长度 `m`、有界特征数 `f<=512`，提取从当前反复执行的
近似 `O(n^2*m)` 收敛为 `O(n*m)`；pairwise Jaccard 仍为
`O(n^2*f)`，而 `n<=100`、`f<=512`。不增加 SQL、embedding、HTTP rerank 或 Chat
模型调用，不增加配置和数据库迁移。

## 2. 为什么现在做

### 2.1 已核对的事实

- `application-prod.yml` 默认启用 heuristic rerank，`diversity-weight=0.2`，
  `candidate-limit=20`。
- `RerankProviderFactory` 对默认、空白以外的未知 provider 都回落到
  `HeuristicRerankProvider`。
- `HttpRerankProvider` 在缺少凭据、超时、协议错误或非法响应时也可回落 heuristic，
  因而本轮同时改善远程 provider 的降级质量。
- `ReRankingService` 是 Search POST、KNOWLEDGE、AGENT tool、JSON record、
  Evaluation 和 legacy Advisor 的共享 facade；本轮无需修改这些调用方。
- 候选池已由 `rag.rerank.candidate-limit` 限制为 `1..100`，适合做有界内存特征计算。
- 现有 `HybridRetrieverRrfPostgresIntegrationTest` 已能从真实 PostgreSQL/pgvector
  生成有序候选，再交给真实 `ReRankingService`，可以直接增加中文质量断言。
- 旧归档规划已经记录“中文无空格文本的词集合效果有限”，但尚无实现或自动化质量证据。

### 2.2 候选项比较

| 候选 | 质量收益 | 延迟/成本 | 风险 | 本轮决定 |
|---|---|---|---|---|
| CJK-aware heuristic 词法特征 | 直接改善生产默认和 HTTP fallback 的中文 relevance/diversity | 本地有界；预计算减少重复拆词 | 单 provider、可确定性验证 | 实施 |
| 跨请求检索缓存 | 重复 query 可能更快 | 需要 mutation、Collection、profile、ACL 失效 | 返回陈旧或越权证据风险高 | 延后 |
| Search/AGENT/Evaluation 多查询扩展 | 可能提升召回 | 增加 embedding/SQL/LLM fan-out | 跨入口预算和诊断 | 延后 |
| 自适应 vector/fulltext 权重 | 可能提升不同语言召回 | 需要更完整 goldenset | 默认排序变化大 | 延后 |
| 权限、用量账本 | 不改善当前核心质量和速度 | 跨 schema/运营 | 用户明确低优先 | 不实施 |

## 3. 冻结范围

### 3.1 生产代码

只修改：

- `spring-ai-rag-core/src/main/java/com/springairag/core/retrieval/rerank/HeuristicRerankProvider.java`

允许在该类中增加私有 record/helper/常量，但不新增公共 SPI、Spring bean 或配置字段。
`RerankProvider`、`ReRankingService`、`HttpRerankProvider` 的构造器和方法签名不变。

### 3.2 测试

- 扩展 `HeuristicRerankProviderTest`：直接覆盖 token、relevance、similarity、重复 chunk、
  混合语言、空白和 provenance。
- 保留并扩展 `ReRankingServiceTest` 的 facade 兼容断言，英文既有测试不得回退。
- 扩展 `HybridRetrieverRrfPostgresIntegrationTest`：真实 pgvector 候选先把无关文档
  排在前面，真实 heuristic rerank 再把局部 CJK bigram 相关文档提升到第一。
- 运行 HTTP fallback 既有测试，证明远程失败路径仍复用新 heuristic 且契约不变。

### 3.3 长青文档

行为落地后同步：

- `docs/architecture.md` / `docs/architecture-zh-CN.md`
- `docs/quality-defaults.md` / `docs/quality-defaults-zh-CN.md`
- `docs/testing-guide.md` / `docs/testing-guide-zh-CN.md`

配置项和 REST schema 不变，因此不修改 `configuration*` 或 `rest-api*`。如果实现中没有
产生新的运维故障模式，不扩展 `troubleshooting*`。

### 3.4 非目标

- 不实现中文词典分词、jieba、ICU BreakIterator 或外部 tokenizer；
- 不改变 PostgreSQL pg_jieba/pg_trgm/full-text 的 query 或索引；
- 不新增 tokenizer 配置、语言检测开关或用户请求参数；
- 不改变 HTTP rerank 成功响应的排序；
- 不调整 `diversity-weight`、candidate limit 或文档覆盖选择器默认值；
- 不增加 metrics/trace 字段，不记录词法 token 或正文；
- 不修改 WebUI 交互或公开 API schema；
- 不以真实 LLM 输出文本作为 deterministic 排序正确性的唯一证据。

## 4. 冻结的词法契约

### 4.1 规范化

1. null、空字符串和纯空白输入产生空特征，relevance/similarity 为 `0`；
2. 使用 `toLowerCase(Locale.ROOT)`，避免依赖宿主机 locale；
3. 本轮不做 NFKC/NFC，不折叠繁简体，不做同义词或词干化；
4. relevance 的位置奖励继续使用规范化文本中的首次 `indexOf`，保持既有
   “越靠前越高、最多 0.3”语义；
5. 最终 relevance 仍限制在 `[0,1]`。

### 4.2 普通 Latin/数字 token

- 一个不含 CJK code point 的非空白片段继续作为一个 token，保持现有英文、数字、
  `C++`、`spring-ai` 等空白切词语义；
- relevance 保留单字符普通 token 的既有行为；
- similarity 继续忽略长度小于 2 的普通 token；
- 不趁本轮把所有标点改造成分隔符，避免无关的英文排序漂移。

### 4.3 CJK 和混合片段

CJK code point 使用 `Character.UnicodeScript` 判断，范围包括：

- `HAN`
- `HIRAGANA`
- `KATAKANA`
- `HANGUL`
- `BOPOMOFO`

每个连续 CJK 片段：

- 长度为 1：产生一个单字符特征，保证短 query 可工作；
- 长度大于等于 2：按 Unicode code point 产生相邻 bigram，不额外产生 unigram；
- 标点、空白、emoji 和其他 script 会终止当前 CJK 连续片段；
- 同一混合片段中的 Latin/数字连续 run 独立形成 token；
- bigram 只描述词面局部重叠，不宣称语义分词。

示例：

```text
"中文检索质量" -> ["中文", "文检", "检索", "索质", "质量"]
"SpringAI中文RAG" -> ["springai", "中文", "rag"]
"中" -> ["中"]
```

### 4.4 特征上限与重复

- relevance 使用按出现顺序保留的特征列表，延续重复 query token 可重复贡献的语义；
- similarity 使用去重集合计算 Jaccard；
- 每个 query 或 chunk 最多保留前 `512` 个提取特征；
- 达到上限后停止继续生成特征，但不截断原始正文、返回对象或对外数据；
- 上限是内部安全常量，不新增配置，避免调用方放大 CPU/内存；
- 512 足以覆盖默认 1000 字符 chunk 的主要局部特征，同时把 100 候选的 pairwise
  计算限制在明确上界；未来只有基准和 goldenset 证明需要时才调整。

## 5. 冻结的评分与重复语义

### 5.1 relevance

公式保持现有结构：

```text
termMatchScore = matchedFeatures / queryFeatures
positionBonus = min(sum(earlyPositionPoints) / 10, 0.3)
relevance = min(termMatchScore + positionBonus, 1.0)
```

变化只在 query features 的提取。对无 CJK 英文 query，特征与现有空白 token 一致。
对无空格 CJK query，相关 chunk 即使词序不同或只匹配局部短语，也能按 bigram 比例得分。

### 5.2 diversity

- 每个 candidate 的 similarity 特征在一次 rerank 中只提取一次；
- candidate `i` 只跳过同一个列表位置 `i`，不再跳过所有文本相等的候选；
- 两个完全相同的非空 chunk 得到 similarity `1`，因此 diversity `0`；
- 无任何词法特征的 null/blank chunk 得到 diversity `0`，不因“无法比较”获得满分；
- 单一非空候选保持 diversity `1`；
- 其他候选的 diversity 为 `1 - maxJaccardSimilarity`；
- 不改变原始 score、relevance 和 diversity 的现有组合权重。

`calculateDiversityScore(text, allResults)` 作为 test-visible helper 没有候选 index：
它在列表中最多跳过第一个与目标文本相等的元素，把它视为 self；后续相同文本仍参与比较。
生产 rerank 使用 index-aware 私有路径，不依赖这个近似。

### 5.3 输出兼容

- 继续创建新的 `RetrievalResult`，完整复制 document/title/chunk/score 分项、metadata 和
  provenance；
- 继续按最终 score 降序并遵守 `rankingDepth`；
- 不改变 `RerankResultSelector` 的后续文档覆盖行为；
- HTTP provider 成功路径不变；只有 heuristic 默认或 fallback 使用新词法内核；
- 空结果、null 结果列表和禁用 rerank 的 facade 行为不变。

## 6. 实施切片

### Slice A：一次性测试矩阵

在生产实现前落下本轮完整测试，不在 review 阶段零碎追加：

1. 无空格中文 query 对词序变化文本产生非零 relevance；
2. CJK 部分重叠 similarity 在 `(0,1)`；
3. 单字符 CJK 可匹配，普通单字符 similarity 仍忽略；
4. 混合 Latin/CJK/数字片段可提取且大小写稳定；
5. 纯英文 relevance/similarity 的既有结果不变；
6. null/blank/标点和长 CJK 输入返回有限、稳定结果；
7. 完全重复 chunk 的 diversity 低于不同 chunk；
8. 默认权重下，CJK 相关候选可越过略高原始分的无关候选；
9. provenance 继续完整复制；
10. HTTP fallback 和 facade 数量/排序边界回归通过。

### Slice B：词法内核与预计算

1. 增加内部 `MAX_LEXICAL_FEATURES=512`；
2. 增加 Unicode script 判断和有界特征提取；
3. 在 `rerank` 开始时只提取一次 query features 和每个 chunk features；
4. 用 index-aware pairwise diversity 替换重复拆词；
5. 保留 test-visible helper，并让它们复用同一实现；
6. 不引入缓存到请求外，不保存正文或 token。

### Slice C：PostgreSQL 质量证据

1. 给 PostgreSQL fixture helper 增加可传入 `chunkText` 的重载；
2. 插入“原始向量略高但词法无关”和“原始向量略低但 CJK bigram 相关”两个 chunk；
3. 断言真实向量 SQL 的候选顺序先错误；
4. 用真实 factory 创建 heuristic provider，经 `ReRankingService` 后断言相关文档第一；
5. 集成测试必须 `tests>0`、`failures=0`、`errors=0`、`skipped=0`。

### Slice D：长青文档与专项验证

1. 双语记录 heuristic 的 CJK bigram、兼容和有界计算；
2. 复用现有 rerank 专项门禁：它已经包含本轮 provider/facade 测试类和整个
   PostgreSQL 集成类；只有实际发现缺失时才修改脚本，不复制新的大脚本；
3. 运行文档、禁悲观锁和 whitespace 门禁；
4. 完成基本硬门槛后再进入实现三轮只读检查。

## 7. 一次性验收矩阵

### 7.1 后端快速与集成

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

解析 Surefire XML，PostgreSQL 测试不得跳过。

### 7.2 基本硬门槛

```bash
mvn clean compile test-compile
mvn test

cd spring-ai-rag-webui
npm run typecheck
npm run test:run
npm run build
npm run check:alignment
```

虽然不改 WebUI 或 API schema，Search/Chat 都消费共享 rerank 输出，因此仍运行核心 Mock
Playwright：

```bash
BASE_URL=http://127.0.0.1:<isolated-port> \
  npx playwright test e2e/search.spec.ts e2e/chat.spec.ts --project=chromium
```

证据只使用 DOM、可访问状态、网络和 JSON 断言，不使用截图。

### 7.3 启动与真实依赖

- 使用 `postgresql,prod` 在隔离端口启动后端并验证 health；
- 真实 PostgreSQL CJK 排序正确性由 deterministic 集成测试证明；
- Mock 全部通过后，使用 `.env` 和隔离端口运行既有真实 LLM Chat/KNOWLEDGE smoke；
- 真实调用期间持续读取日志，出现认证、模型或协议错误时立即处理；
- 不要求真实 LLM 自然语言逐字稳定，只断言请求成功、来源/检索链路和协议；
- 如复用 `verify-chat-capability.sh --with-real-llm`，结果必须无失败和无跳过。

### 7.4 静态门禁

```bash
./scripts/verify-no-pessimistic-locks.sh
./scripts/verify-project-docs.sh
./scripts/run-retrieval-regression.sh --self-test
git diff --check
```

## 8. 规划与实现收敛

规划检查固定为：

1. 价值、范围、词法/评分契约和非目标；
2. Unicode、复杂度、兼容、fallback 和生产调用链；
3. 一次性测试矩阵、PostgreSQL、前端、真实 LLM、文档、回滚和 Git。

实现检查必须在硬门槛通过后执行，固定为：

1. tokenizer/code-point 边界、上限、relevance/diversity 和英文兼容；
2. provider/facade/fallback、候选池、重复 chunk、性能和失败语义；
3. PostgreSQL/前端/真实运行证据、双语文档、归档和 Git 交付。

发现影响正确性、兼容、成本安全或数据一致性的实质问题时立即修复，重跑受影响门槛并把
实现计数重置为 `0/3`。风格或可选优化不扩展本轮范围。

## 9. 风险、回滚与完成定义

| 风险 | 控制 |
|---|---|
| CJK bigram 过度匹配 | 不使用 unigram（单字符片段除外），保留原始 score 主权重 |
| 长文本产生大量 token | 每段最多 512 特征，候选最多 100 |
| 英文排序漂移 | 无 CJK 片段保持现有空白 token，增加精确回归测试 |
| 完全重复 chunk 排序变化 | 这是明确质量修复；只影响 heuristic/fallback |
| HTTP provider 行为变化 | 成功路径不变，既有 fallback 测试覆盖 |
| 真实模型输出波动 | deterministic PostgreSQL/单测证明排序；真实 LLM 只证明协议和链路 |

快速回滚只需撤销 `HeuristicRerankProvider` 和对应测试/文档提交，不涉及配置、schema、
数据迁移或重嵌入。

只有以下全部成立才完成本轮：

1. 规划连续检查达到 `3/3`；
2. 一次性测试矩阵已先于 review 落下；
3. focused、真实 PostgreSQL、Maven 编译/全量测试、服务启动通过；
4. WebUI typecheck/Vitest/build/alignment/核心 Mock Playwright 通过；
5. 获准的真实 LLM 隔离 smoke 通过；
6. 实现连续检查达到 `3/3`；
7. 双语长青文档和项目门禁通过；
8. 特性分支已提交/push，最新 `origin/main` 已按需合入并复验；
9. 特性分支合回并 push `main`，main 合并后完整复验通过且 worktree 干净；
10. 本轮 plan/progress 归档后，开始探索下一批小范围高价值功能。
