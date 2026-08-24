# 生产质量默认值

> 📖 [English](quality-defaults.md) · 📖 [中文](quality-defaults-zh-CN.md)

> 当 `SPRING_PROFILES_ACTIVE` 包含 **`prod`** 时，以下默认值生效
>（见 `spring-ai-rag-core/src/main/resources/application-prod.yml`）。

| 配置 | 生产默认值 | 原因 |
|------|------------|------|
| `rag.security.enabled` | `true` | 强制 API Key 认证 |
| `rag.circuit-breaker.enabled` | `true` | LLM 不可用时快速失败 |
| `rag.rerank.enabled` | `true`（heuristic） | 本地质量增强，无额外重排 API 成本 |
| `rag.rerank.provider` | `heuristic` | 可改为 `http`，接入 cross-encoder |
| `rag.rerank.candidate-limit` | `20` | rerank 前的有界候选池；最终返回数量仍由请求 `maxResults` 决定 |
| `rag.rerank.preferred-max-chunks-per-document` | `2` | 回填前优先扩大文档覆盖，同时允许同一文档保留两个互补 chunk |
| `rag.query-rewrite.enabled` | `true` | 提升召回 |
| 检索权重 | vector 0.55 / fulltext 0.45 | 略偏向向量，可通过 goldenset 调优 |

## 使用 goldenset 证明增益

```bash
# 服务运行后执行；真实 Embedding Key 才能反映实际检索质量
./scripts/run-retrieval-goldenset.sh
# 或：
BASE_URL=http://127.0.0.1:18081 ./scripts/run-retrieval-goldenset.sh
```

样例集：`testdata/goldenset/retrieval-goldenset.json`

脚本会对同一批用例执行两轮 `POST /api/v1/rag/search`：

- `baseline`：`useRerank=false`
- `quality`：`useRerank=true`

两轮结果均通过 `POST /api/v1/rag/evaluation/evaluate` 持久化。脚本输出平均
Precision@K、MRR、nDCG 及其差值；MRR 或 nDCG 回退时返回失败。

## 版本化真实检索回归

Goldenset 用于比较重排开关；版本化回归用于阻止真实检索能力在后续提交中退化：

```bash
BASE_URL=http://127.0.0.1:18081 ./scripts/verify-quality-regression.sh
```

- 数据集：`testdata/regression/retrieval-core-v1.json`
- baseline：`testdata/regression/retrieval-core-v1-baseline.json`
- 稳定 relevant identity：`collectionKey + sourceNamespace(default) + externalId`
- 指标：Hit Rate、MRR、Recall@K、nDCG
- 安全断言：selected Collection 不泄漏 decoy；明确 JSONB 空结果保持零命中

脚本会对 absolute minimum 和 baseline 允许回退量同时判定，provider、数据库、embedding
或 HTTP 失败均返回非零。`verify-release.sh --with-quality-regression` 可对已启动服务追加
该门禁，`--with-local-runtime` 默认包含它。

### 对比基线与生产质量组合

```bash
SPRING_PROFILES_ACTIVE=postgresql,prod bash scripts/start-server.sh
BASE_URL=http://127.0.0.1:8081 API_KEY=rag_sk_... \
  ./scripts/run-retrieval-goldenset.sh
```

请求级 `useRerank` 只能关闭已启用的全局重排；当
`rag.rerank.enabled=false` 时，不能通过单次请求启用重排。

### 候选池与最终结果数量

启用有效 rerank 的受管请求先使用
`max(requestedMaxResults, rag.rerank.candidate-limit)` 召回候选，再由 reranker 选择
最终的 `requestedMaxResults`。候选池默认是 `20`，配置绑定限制为 `1..100`；它不是
新的请求参数，也不会改变 Search/Chat/Agent 工具的最终数量契约。旧版 GET Search
明确关闭 rerank，因此继续使用原有查询上限。

候选池扩大后应同时观察：

- MRR、nDCG、Recall@K 是否改善，而不是只看返回条数；
- Search 和 Chat 的 p95 检索延迟；
- HTTP provider 的请求体大小与超时/降级次数；
- Agent 工具、citation 和 Evaluation 是否仍不超过请求的 `maxResults`。

质量或延迟回归时，先将 `RAG_RERANK_CANDIDATE_LIMIT` 调回 `1`，或暂时关闭全局
rerank，再重新运行 goldenset 和版本化质量回归。

### KNOWLEDGE 多查询证据合并

KNOWLEDGE 使用多条检索 query 时，项目会在 rerank 前合并重复的
`documentId:chunkIndex` 候选，并保留最高有限 score 对应的完整对象。这是内部默认
行为，不是可调请求参数或应用配置。它避免较低分候选因 Spring AI Map 遍历顺序被保留，
也避免同一 chunk 重复进入 rerank 和 prompt budget。

join 只对已经检索出的有界候选执行本地处理，不增加数据库、embedding、rerank provider
或 Chat 模型调用。可通过 `metadata.retrieval.documentJoin` 比较输入数、唯一数、删除
重复数和按分数替换数；端到端质量与延迟结论仍需结合 retrieval goldenset、版本化回归、
Chat source/citation 检查和 p95 观测。

### heuristic 的 CJK 词法默认

生产默认 heuristic reranker 对普通无 CJK 文本继续按空白 token 计算 relevance 和
Jaccard diversity。对中文、日文、韩文和注音连续片段，它使用相邻 Unicode code-point
bigram；单字符片段保留单字符，混合文本中的 Latin/数字 run 仍可匹配。这样，无空格
CJK query 不必整句原样出现在 chunk 中，词序变化或局部短语重叠也能贡献确定性的词面分。

每个 query/chunk 最多提取 512 个特征，并在一次 rerank 中预计算后复用。这个内部上限
不是请求参数，不增加数据库、embedding、HTTP 或 Chat 模型调用。完全相同的另一 chunk
会被视为 similarity `1`，null/blank chunk 不会获得无信息 diversity 奖励。HTTP rerank
成功响应不变；缺少凭据、超时或非法响应而降级 heuristic 时会使用同一 CJK 规则。

正文和标题分别计算 relevance，最终使用
`max(contentRelevance, 0.9 * titleRelevance)`。权威文档标题只规范化一次，不生成
diversity 特征；这使产品 ID、术语或主题名只出现在标题时仍能提升候选，同时避免标题与
正文重复命中被相加放大。null/blank 标题保持原评分和排序路径。HTTP provider 成功路径
仍只发送 chunk 正文，不把标题加入外部请求；fallback 才复用标题感知 heuristic。

relevance 对不含 CJK、且首尾为 Unicode 字母或数字的普通 term 要求完整字母数字边界。
相邻的非 CJK 字母或数字会阻断匹配，标点、分隔符、文本边缘和 CJK script transition
则是合法边界。明确的外层句末/包裹标点会被忽略，但技术标识符字符 `+`、`#`、`-`、`_`、
`/` 和 `\` 会保留；CJK 特征和以符号结尾的 term 继续使用 substring。这样可消除 `rag`
在 `storage`、`ai` 在 `OpenAI`、`9042` 在 `19042` 中的确定性假命中，同时不破坏
`中文SpringAI检索`、`型号9042说明`、`C++`、`C#` 或 `api/v1` 查询。query relevance
term 在每次 rerank 中只准备一次，并由正文和标题复用。

该策略是轻量词面增强，不是词典分词、语义 reranker 或繁简/同义词归一化。质量评估仍应
比较中英文 goldenset 的 MRR/nDCG/Recall@K 和 Search/Chat p95；需要更高排序上限时使用
HTTP cross-encoder provider。

### rerank 后的文档覆盖

生产默认值会在第一遍选择时，对同一个精确文档身份优先最多保留两个 chunk。`2` 是保守
平衡：减少相邻重复证据，同时允许一个长文档提供两个互补段落。如果排名池中缺少足够的
替代文档，选择器会按 provider 原顺序回填跳过的 chunk，所以它不是最终结果的绝对
每文档上限。

更看重文档覆盖时使用 `1`；问题经常需要同一文档多个章节时使用 `3` 或更高；设置为
`0` 可恢复 provider top-N 行为。调整后应比较 unique document count、
MRR/nDCG/Recall@K、Search/Chat p95 延迟和最终 citation 质量。选择器只执行有界的本地
O(n) 工作，不增加模型或数据库调用。

### 可选 HTTP 重排

```yaml
rag:
  rerank:
    enabled: true
    provider: http
    api-key: ${SILICONFLOW_API_KEY}
    model: BAAI/bge-reranker-v2-m3
    base-url: https://api.siliconflow.cn
```
