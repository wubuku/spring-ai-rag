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

- 语料：10 个文档在运行时确定性生成，覆盖五种 PDF 版式（双栏正文、三列表格、
  两列表格、双栏正文含局部两列表格、单栏宽表格），以及段落 + 表格 DOCX、
  带代码块和表格的 Markdown、含 script/style 噪声的 HTML、GBK 纯文本；
  夹具见 `DocumentParsingCorpus`。
- 查询集与阈值：`spring-ai-rag-core/src/test/resources/retrieval/parsing-retrieval-quality-gate-v1.json`
  （16 条查询，每条固定目标 `documentId` 与必须同块出现的 `expectedTerms`）。
- embedding：测试内 FNV-1a 特征哈希，1024 维、签名哈希、L2 归一化；拉丁
  run 与 CJK unigram/bigram 分词。同一文本永远得到同一向量。
- 排序：`score desc → documentId` 字典序 `→ chunkIndex` 升序；同分结果不依赖
  Map/Hash 顺序。
- 相关性：命中目标文档，且 chunk 同时包含该查询的全部 `expectedTerms`。

基线（2026-11-05，固定语料 + 固定哈希 embedding，实测）：

| 指标 | 实测值 | 阈值 | 余量 |
| --- | --- | --- | --- |
| Recall@5 | 1.000 | 0.95 | 5 个百分点 |
| MRR | 1.000 | 0.95 | 5 个百分点 |
| nDCG@10 | 1.000 | 0.95 | 5 个百分点 |

阈值取“实测 1.0 下浮 5 个百分点”：16 条查询里丢掉任意 1 条，三项指标都降到
`15/16 = 0.9375`，低于 0.95；单条查询从第 1 掉到第 2 时 Recall 仍为 1.0、
MRR `(15 + 0.5)/16 ≈ 0.969`、nDCG 约 `0.977`，仍在阈值之上，允许轻微排序
抖动；丢掉 2 条即 `14/16 = 0.875`，稳定变红。

PDF 版式判据（`PdfTextExtractor.PageLayout` 逐页记录，并在
`ExtractedDocument.layoutDiagnostics()` 上对外可见）：

- 主导 gutter：最宽 x 簇间隙 ≥ 页宽 8%，且在三簇及以上时 ≥ 次宽间隙的 1.5 倍；
  满足才按栏重排。三列表格（120/100 两条相近间隙）因此保持行序。
- 恰好两个 x 簇时不能只看间隙：短单元格占比 ≥ 0.5 且左右逐行成对 ≥ 0.7 →
  判为表格，按行序；同一簇相邻长行（≥20 字符、无句末标点）的接续比例 ≥ 0.5，
  或长行占比 ≥ 0.6 → 判为正文双栏，按栏重排。
- 证据冲突或不足 → `ROW_ORDER` 降级并记录原因；单簇、无主导 gutter、单元格
  特征分别记录 `SINGLE_CLUSTER`、`NO_DOMINANT_GUTTER`、`CELL_LIKE_COLUMNS`，
  歧义记录 `AMBIGUOUS_MIXED_SIGNALS` 或 `AMBIGUOUS_FALLBACK`，不静默猜。

分块质量断言（任一条失败即红）：

- 没有空 chunk、没有超过 `size=1000` 的 chunk，`ChunkDiagnostics` 无
  `violations`，原文非空白字符覆盖率必须等于 100%。
- 双栏/混排 PDF 必须按 `columnOrder` 顺序输出（左栏整体先于右栏），chunk 内
  不得逐行交错。
- 表格行（两列/三列表格 PDF 同行单元格、双栏混排中的局部表格、DOCX/Markdown
  表格行）必须落在同一输出行，并完整落在同一个 chunk 内；`CODE` / `TABLE`
  的 protected block 元数据在检索侧仍可读。
- 每个 chunk 携带 `documentId`、连续 `chunkIndex`、非空 `title`；命中块必须
  能定位回目标文档。

剩余样本（实测记录，不隐藏）：

- `ambiguousTwoClusterPdf`（两个 x 簇、中长行、既无接续也无短单元格）→
  `ROW_ORDER` + `AMBIGUOUS_FALLBACK`，保真优先。
- 两栏各自都含等宽内嵌表格时可能出现多条接近等宽的间隙 →
  `NO_DOMINANT_GUTTER`，整页退化为行序（可预期降级）。
- 长自由文本单元格（≥20 字符且无句末标点）与正文换行在坐标上不可区分，仍可能
  被判为双栏；键值/短单元格两列表格已被固定测试覆盖。

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

## 真实 embedding 检索基线（人工标注 goldenset）

与「解析与分块检索质量门禁」是**两套东西**，不能互相替代：

- `ParsingRetrievalQualityGateTest` 使用测试内特征哈希假 embedding，只证明解析、
  分块与排序管线正确，能在 `mvn test` 里稳定复现；它**不代表语义检索质量**。
- 本节 goldenset 使用真实 embedding provider（BGE-M3 / 1024 维），只证明语义检索
  质量；默认不进 PR 快层，nightly 默认流程也不跑，只在显式开关下触发。

数据集与标注：

- 语料 32 篇，位于 `testdata/real-goldenset/corpus/`：多栏 PDF 10、DOCX 6、MD 6、
  HTML 5、TXT 4（含 GBK）、CSV 1；覆盖多栏正文、表格、中英混排与代码块。
- 查询 48 条，位于 `testdata/real-goldenset/queries.json`，**人工编写并标注**
  应命中的文档与片段关键词；`relevant[].terms` 必须全部出现在同一个 chunk 才算
  片段命中。查询类型分 normal / short / identifier / table-cell / cross-doc /
  paraphrase-zh。
- `scripts/test-support/generate-real-goldenset-corpus.py` 字节级可复现：PDF
  元数据/文档 ID 与 DOCX 时间戳均固定，重复生成同一份语料。

运行（真实调用、显式开关）：

```bash
# 隔离库 + .env 中的 RAG_ROOT_API_KEY / 嵌入 Key；不要指向共享生产库
python3 scripts/run-real-embedding-goldenset.py \
  --base-url http://127.0.0.1:18091 \
  --collection-key real-goldenset-<日期> \
  --ack-paid-models
```

没有 `--ack-paid-models`（或环境变量 `RAG_REAL_GOLDENSET_ACK_PAID_MODELS=1`）时
脚本直接拒绝运行；密钥只从 `.env` 读取且不打印。一次完整运行约产生 32 次文档
嵌入 + 5×48 次查询嵌入；`--skip-create --manifest <前次 manifest>` 只复测检索，
不再产生文档嵌入调用。

真实基线（2026-11-05，SiliconFlow `BAAI/bge-m3`，1024 维，`useRerank=false`，
`minScore=0`，top-10，48 查询）：

| 变体 | R@5 | R@10 | MRR | 片段 MRR | nDCG@10 |
|------|-----|------|-----|----------|---------|
| 纯向量（`useHybridSearch=false`） | 0.917 | 0.938 | 0.822 | 0.812 | 0.846 |
| 混合 50/50 | 0.917 | 0.938 | 0.835 | 0.828 | 0.857 |
| **混合 55/45（生产默认，基线）** | **0.917** | **0.938** | **0.838** | **0.828** | **0.858** |
| 混合 70/30 | 0.917 | 0.938 | 0.838 | 0.828 | 0.858 |
| 混合 30/70 | 0.917 | 0.938 | 0.838 | 0.828 | 0.858 |
| 纯关键词（0/1） | 0.125 | 0.125 | 0.125 | 0.125 | 0.125 |

同一语料重复运行时，远程 embedding 的浮点差异会让个别查询的位次互换，
MRR / nDCG@10 约有 ±0.004 的抖动；表中是 2026-11-05 最后一轮完整运行。

分组（生产默认 55/45）：

- 语言：en（20 条）R@5/R@10 1.000/1.000、MRR 0.913、nDCG@10 0.930；
  zh（26 条）0.846/0.885、MRR 0.768、nDCG@10 0.795；mixed（2 条）
  1.000/1.000、MRR 1.000、nDCG@10 0.960。
- 格式：HTML（5）与 CSV（1）全为 1.0；DOCX（8）R@10 1.000、MRR 0.854、
  nDCG@10 0.891；PDF（22）R@10 0.909、MRR 0.802、nDCG@10 0.821；
  MD（8）R@10 0.875、MRR 0.813、nDCG@10 0.822；TXT（4）R@10 1.000、
  MRR 0.813、nDCG@10 0.858。
- 失败类型：normal（17）全 1.0；table-cell（11）R@10 1.000、MRR 0.909；
  identifier（6）R@10 1.000、MRR 0.792；cross-doc（3）R@10 1.000、
  MRR 0.833；short（5）R@10 0.800、MRR 0.667；paraphrase-zh（6）
  R@10 0.667、MRR 0.440。

对比与噪声：

- 纯向量 → 混合：MRR 0.822→0.838、nDCG@10 0.846→0.858。对同一 48 条查询
  做 paired bootstrap（5000 次）得到 nDCG@10 差值 95% CI **[-0.036, 0.000]**，
  区间含 0，不能判定混合显著优于纯向量；只能记录方向一致，不能过度解读。
  CI 只覆盖“查询抽样”，不覆盖语料与模型版本。
- 权重：50/50、55/45、70/30、30/70 的 MRR / nDCG 差异不超过 0.004，且重复运行
  会互换名次（例如 50/50 与 70/30 在两轮里分别略低），没有稳定排序差异。
  当前没有证据支持改生产权重，保持 55/45。融合使用加权 RRF，K 固定且暂不可配，
  因此本实验只扫权重、未扫 K。
- 纯关键词通道 R@10 仅 0.125：英文/中文全文通道是 AND 语义且按语言路由，
  每条查询通常只有 0–2 个候选，权重再高也难进入前列。关键词只能当辅助信号，
  不能替代向量通道。

典型失败与归因（基线）：

- **跨语言同义改写（embedding）**：q-para-01「平台怎么处理重试才不会重复扣费？」
  目标是英文 PDF 的 `Idempotency keys are recorded for 24 hours`，top-10 未命中；
  top1 是中文 429/Retry-After 排查手册。`重复扣费 ↔ idempotency` 语义未对齐，
  与分块、融合权重无关。
- **短查询 + 跨语言表格事实（embedding + 关键词无候选）**：q-short-02「审计保留」
  目标是 `Audit retention` / `400 days` 表格，top-10 未命中；top1 是
  「Audit logs ... 90 days」。4 字查询信号太弱，且中文查询走 jieba 通道，
  英文表格没有关键词候选。
- **金标歧义（非系统错误）**：q-para-04「请求被限流后应该等多久再重试？」标注
  只收 `md-code-en.md` 的 `Retry-After`，但 top1 `md-mixed-zh.md` 同样给出
  `Retry-After` 与指数退避，语义上是正确答案；因此只拿到文档级分数。
  q-para-05「停止节点前要执行什么操作？」同理：top1 中文运维手册的
  「重启前先摘除流量…再停止进程」是正确答案，标注目标的英文
  `nebulactl drain --node` 未进 top-10。这两条保留在 goldenset 中作为已知标注
  边界，不修改标注去迎合系统。
- **编号/型号近邻（embedding + 关键词通道失效）**：q-id-02 `NEBULA-300` 的目标
  排第 2，第 1 是含 `NEBULA-400` 的总览；q-id-05 `RAG-4291` 的目标排第 2，
  第 1 是含 `RAG-4290` 的错误码大全。相邻型号/数字在向量空间过近；中文+编号
  查询又走 jieba 的 AND 语义，`websearch_to_tsquery` 的连字符短语匹配没有参与。
- **表格**：11 条 table-cell 查询没有出现“表格行被拆散”导致的失败；chunk 预览
  确认两列/三列表格的真实行保持完整。

已修问题与前后对比：

1. **多栏 PDF 版式误判（应用缺陷）**：`PdfTextExtractor` 原实现按整次
   `writeString` 记录行起点，PDFBox 会把同一基线上相距很远的左右栏合并进一次
   调用；中文续行尾段又被当成短单元格，导致 `pdf-two-column-zh-ops.pdf` 判成
   `ROW_ORDER` 并逐行交错。修复：按字间距切分 `VisualLine`、统计改用按栏合并
   后的行、续行不计入短单元格。修复前该语料 `lease 过期时间是 60 秒` 片段缺失
   （fragment loss），修复后 48 条标注全部可定位，且该题排第 1。新增回归测试
   `mixedScriptTwoColumnProseKeepsColumnOrder`。
2. **分块重叠窗口切词（应用缺陷）**：`BoundedTextChunker` 的重叠起点直接按字符数
   回退，产生 `...node uns` + `chedulable. ...`。修复：在 32 字符内回退到词/句
   边界。修复后基线指标不变（混合 55/45 的 MRR/nDCG 仍为 0.838/0.858；纯向量
   MRR -0.0035，约一条查询位次变化）。这是文本完整性修复，不是指标增益。新增
   `overlapStartSnapsToWordBoundary`。
3. **goldenset 语料生成器（只影响评测语料）**：`china-s` 字体宽度被低估会让双栏
   文字越过 gutter；PDF/DOCX 时间戳会导致不可复现。已按整字宽估算文本宽度，并
   固定 PDF 元数据/文档 ID 与 DOCX 时间戳。应用运行时不受影响。

已知质量边界：

- 32 文档 / 48 查询是小样本；bootstrap CI 宽，不能用来宣称小幅领先显著，
  也不能外推到其他语料或模型版本。
- 片段级判定是“去空白后的精确子串”，语义等价但换词的 chunk 不计分；nDCG 按
  文档去重并按 2/1/0 分级，分数偏保守。
- 中文改写、跨语言同义、相邻编号/型号是当前最弱项；关键词通道在这些场景帮助
  有限。
- 本基线固定 `useRerank=false`，不代表开启重排、换 embedding 模型或换分块
  Profile 后的质量。

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
