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

样例集：`testdata/goldenset/retrieval-goldenset.json`（**40 例 / 28 文档 / 3 集合**，
另含 2 个诱饵文档；`deterministic: true`）。

覆盖类别（数量即回归信号强度，不是"一两个例子"）：

| 类别 | 例数 | 说明 |
| --- | --- | --- |
| `zh-semantic` | 6 | 中文纯语义命中（查询词与正文不同词） |
| `en-semantic` | 8 | 英文纯语义命中 |
| `keyword` | 6 | 精确关键词命中（中英各半） |
| `hybrid` | 6 | 混合检索融合排序 |
| `multi-collection` | 4 | 跨 3 个集合检索与集合内排序 |
| `metadata-filter` | 4 | `metadataContains` 过滤（`lang`/`product`/`version`） |
| `forbidden` | 2 | 退役/不安全文档不得排在同题答案之上 |
| `no-answer` | 4 | 语料无答案，分数必须低于 `noAnswerMaxScore` |

确定性：固定语料、固定 query、只调 `POST /api/v1/rag/search`，不调用聊天模型，
指标不依赖采样；同一 embedding 提供方下两次运行逐用例数值一致（实测两轮
`mrr=0.9583 ndcg=0.9710` 完全相同）。

脚本会对同一批用例执行两轮：`baseline`（`useRerank=false`）与 `quality`
（`useRerank=true`）。`quality` 的 MRR/nDCG 不得低于 `baseline`；默认配置
`rag.rerank.enabled=false`，此时两轮数值相同，这是配置事实而不是"重排无效"的证据。
结果只打印到 stdout/日志，不再写 `POST /api/v1/rag/evaluation/evaluate`。

门槛与校准（`thresholds` 写在数据集里，脚本只读）：

- **门禁只用 MRR 与 nDCG@5**（对顺序敏感，打乱 top-k 必然变红）。P@5 仍然计算并
  打印，但不设门槛：每例只有一个目标文档，P@5 的结构上限是 `1/5=0.20`，且对
  "同一批结果换顺序"完全不敏感，不能承担灵敏度验证。
- 门槛 = 实测值下浮 0.07–0.10：`overall` MRR 0.82 / nDCG 0.84；`zh-semantic`、
  `keyword`、`hybrid`、`metadata-filter` 0.90/0.90；`en-semantic`、
  `multi-collection` 0.75/0.78；`forbidden` 0.85/0.85。
- `forbidden` 的可执行契约是"诱饵不得排在正确答案之上"：`maxResults>0` 时接口
  总会返回 top-k，要求诱饵完全不出现是不可构造的断言。
- `no-answer`：top-5 中超过 `noAnswerMaxScore`（0.62）的命中数不得超过
  `noAnswerMaxHits`（1）。

灵敏度验证（证明门禁会红，而不是永远绿）：

```bash
BASE_URL=http://127.0.0.1:18081 ./scripts/run-retrieval-goldenset.sh \
  --skip-create --mutation=all
```

`--mutation` 对真实检索结果做三种确定性恶化——`shuffle`（反转 top-k）、
`demote`（把相关文档移到末尾）、`drop`（移除相关文档）——再用同一套门槛判定；
三种都必须被拒绝，脚本打印 `MUTATION_DETECTED_OK`，否则 `exit 1`。实测：
`shuffle` MRR 0.2218 / nDCG 0.4171，`demote` 0.2167 / 0.4127，`drop` 0/0，全部
低于门槛。变异只作用于内存中的排序，不写语料，无需回滚。

## 解析与分块检索质量门禁（无模型 / 无数据库）

解析层改动不能只看“有没有抽出字”。`ParsingRetrievalQualityGateTest` 把同一批
格式语料送进抽取、分块和检索，度量 Recall@5、MRR、nDCG@10，任何一项跌破阈值
都会让 `mvn test` 变红。它不调用真实 LLM、不连 Postgres、不需要 Docker。

```bash
mvn -o -pl spring-ai-rag-core -am \
  -Dtest=ParsingRetrievalQualityGateTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

- 语料：6 个文档在运行时确定性生成——双栏 PDF、三列表格 PDF、段落 + 表格
  DOCX、带代码块和表格的 Markdown、含 script/style 噪声的 HTML、GBK 纯文本；
  夹具见 `DocumentParsingCorpus`。
- 查询集与阈值：`spring-ai-rag-core/src/test/resources/retrieval/parsing-retrieval-quality-gate-v1.json`
  （9 条查询，每条固定目标 `documentId` 与必须同块出现的 `expectedTerms`）。
- embedding：测试内 FNV-1a 特征哈希，1024 维、签名哈希、L2 归一化；拉丁
  run 与 CJK unigram/bigram 分词。同一文本永远得到同一向量。
- 排序：`score desc → documentId` 字典序 `→ chunkIndex` 升序；同分结果不依赖
  Map/Hash 顺序。
- 相关性：命中目标文档，且 chunk 同时包含该查询的全部 `expectedTerms`。

基线（2026-11-05，固定语料 + 固定哈希 embedding，实测）：

| 指标 | 实测值 | 阈值 | 余量 |
| --- | --- | --- | --- |
| Recall@5 | 1.000 | 0.94 | 6 个百分点 |
| MRR | 1.000 | 0.92 | 8 个百分点 |
| nDCG@10 | 1.000 | 0.92 | 8 个百分点 |

阈值取“实测 1.0 下浮 6–8 个百分点”，不是拍脑袋：9 条查询里丢掉任意 1 条相关
证据会使 Recall 降到 `8/9 = 0.889`，必然低于 0.94；单条查询从第 1 掉到第 2
只会让 MRR 降到 `(8 + 0.5)/9 ≈ 0.944`、nDCG 降到约 `0.959`，仍在阈值之上，
属于允许的轻微排序抖动；丢掉 2 条即约 `0.78–0.81`，变红。

分块质量断言（任一条失败即红）：

- 没有空 chunk、没有超过 `size=1000` 的 chunk，`ChunkDiagnostics` 无
  `violations`，原文非空白字符覆盖率必须等于 100%。
- 双栏 PDF 必须“左栏整段 → 右栏整段”，chunk 内不得出现 `RIGHT-*` 先于
  `LEFT-*` 的逐行交错。
- 表格行（PDF 同行单元格、DOCX/Markdown 表格行）必须落在同一输出行，并完整
  落在同一个 chunk 内；`CODE` / `TABLE` 的 protected block 元数据在检索侧仍可读。
- 每个 chunk 携带 `documentId`、连续 `chunkIndex`、非空 `title`；命中块必须
  能定位回目标文档。

确定性与幂等：

- 同一语料连续两次索引，抽取文本、chunk 列表与 `ChunkDiagnostics` 逐项相等。
- 无命中查询（全 0 分）验证 tie-break：结果严格按 `documentId`、`chunkIndex`
  排序，重复排序完全一致。
- 外部文档同步路径的幂等由
  `DocumentMutationExternalCasTailTest#unchangedWhenSameRevisionAndSameManagedState`
  固定：相同 `sourceRevision` 且托管字段未变化时返回 `UNCHANGED`，不重复写库。

灵敏度反证：门禁测试自身包含两个反向用例——移除所有相关 chunk 后必须
`0/0/0`，反转排序后 MRR/nDCG 必须低于阈值；两者都通过才说明阈值真的会拦下
召回或排序下滑，而不是恒绿。

## 检索结果可复现性

检索正确性由 gated 套件 `RetrievalCorrectnessPostgresIntegrationTest` 固定，使用
1024 维确定性假 embedding 与固定语料，不依赖真实模型质量：

```bash
./scripts/verify-gated-it.sh RetrievalCorrectnessPostgresIntegrationTest
```

可复现边界：

- 当前活动 1024 维 Profile 的向量列 `embedding_1024` **没有**近似索引，检索走精确扫描；
  同一语料、同一参数重复检索得到相同的文档集合、顺序与分数。
- 向量通道、`pg_trgm`/英文全文通道以及最终融合都使用 `documentId`、`chunkIndex` 作为
  同分 tie-break，因此通道内与融合后的顺序都是确定的。
- 只有在运维显式给活动向量列新增 HNSW/IVFFlat 索引后，才可能出现近似检索抖动：
  此时只能保证同参数下的文档集合落在分数容差内，top-k 边界和同分顺序不保证一致。
  新增近似索引必须同步更新 gated 断言与本节容忍说明，不能沿用当前“完全一致”契约。
- goldenset 在 nightly 使用全新隔离数据库与真实 embedding 提供方，同一
  provider/model/revision 下可稳定复现；跨 provider 或换模型版本时绝对分数不可比，
  回归比较同一次运行内 `useRerank=false/true` 的相对差值（重排未启用时两者相同）。
  脚本每次使用带时间戳的新 collection，重复运行不会污染已有数据；本地调试可用
  `--skip-create` 复用同一批语料。

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

### KNOWLEDGE 检索后证据组装

`KNOWLEDGE` 默认在重排后、prompt token 预算前，对已注册且可定位的文本 chunk 读取同一
本地索引 generation 的有限前后邻居。默认半径为 `1`、最多20个 seed、单窗口最多4000字符。
它只补全附近定义、表头和步骤，不改变 Search 排序/数量，不新增模型调用，也不扩展
`AGENT`、`PLAIN`、JSON record 或静态知识路径。正文 overlap 按 Java UTF-16 区间去重，
合成标题/表头前缀单独保留；不能证明当前 hash/chunker/generation 或 seed 原文一致时，
安全退回原命中块。

组装后的 source 仍以原 anchor 的 citation handle 表示；`metadata.contextAssembly` 只描述
已授权的范围映射，不把邻居伪装成新的 citation。token 预算不足时先恢复 anchor，再按旧规则
裁剪；`metadata.retrieval.contextAssembly` 记录 seed、邻居、合并、去重、预算回退/截断和
降级 code 等低基数诊断。关闭开关是应急回退，无需数据迁移。

质量判断必须同时看：最终模型输入是否包含必要事实、引用是否对应最终展示证据、token/延迟
是否有界，以及现有 retrieval goldenset/版本化回归是否没有退化；不能只看非空答案或单一
LLM judge 分数。

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
    api-key: ${RERANK_API_KEY}
    model: BAAI/bge-reranker-v2-m3
    base-url: https://api.siliconflow.cn
```
