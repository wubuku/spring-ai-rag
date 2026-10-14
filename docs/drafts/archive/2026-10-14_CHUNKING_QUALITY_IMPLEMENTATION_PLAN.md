# 第三批实施规划：分块正确性、质量校验与诊断

> 基线：`6c58187f`，2026-10-14，当前 `main` 工作区干净。
> 用户已批准实施第三批；沿用本会话直接在 `main` 实施及最终 commit/push 的安排。
> 状态：规划3/3已完成；实施和验收证据见[进度账本](2026-10-14_CHUNKING_QUALITY_IMPLEMENTATION_PROGRESS.md)。
> 对应[候选建议](2026-10-14_CHUNKING_QUALITY_NEXT_BATCH_RECOMMENDATIONS.md)。

## 1. 目标、非目标与已核对事实

目标：修复已复现的内容消失、超长块、原文位置错误和同级标题串接问题；提供可复现的新
Profile、严格 Validator、结构保护、长表表头继承及同链路只读预览。

不做：WebUI、新 parser SPI、parent/neighbor 检索装配、远程分块、tokenizer 平台、运行时
Profile CRUD、自动全库重嵌入；不修改外部参考仓库或 `.env`。

影响模块：documents 的纯算法与测试；api 的预览 DTO；core 的配置、唯一分块协调入口、
派生身份、预览 HTTP 与测试；scripts 的验收入口；双语正式文档。starter 自动扫描现有
core controller，无新增独立启动模块。

代码事实（均为一层直达）：

- [旧算法](../../../spring-ai-rag-documents/src/main/java/com/springairag/documents/chunk/HierarchicalTextChunker.java)
  `split` 先移除全部候选表格，再只放回 valid table；位置基于移除后的文本；sentence 分支
  没有约束单句。旧行为必须保留在显式兼容 Profile 中，不能在 `hierarchical-v2` 身份下换算法。
- [唯一协调入口](../../../spring-ai-rag-core/src/main/java/com/springairag/core/service/DocumentChunkingService.java)
  `prepare` 同时被本地关键词和 Embedding 使用；JSON_RECORD 是单块直通。
- [派生身份](../../../spring-ai-rag-core/src/main/java/com/springairag/core/service/DocumentDerivationDescriptorProvider.java)
  被提交、调度、缓存和 retrieval freshness 共用；当前 `hierarchical-v2:size:min:overlap`。
- [关键词持久化](../../../spring-ai-rag-core/src/main/java/com/springairag/core/service/KeywordIndexPersistenceService.java)
  已保存 chunk text、index、start/end 和 version；现有 schema 可承载新算法，无需迁移。
- [Embedding 准备](../../../spring-ai-rag-core/src/main/java/com/springairag/core/service/DocumentEmbedService.java)
  通过同一入口准备，chunking 失败在 provider 调用前发生；Job runtime exception 走既有错误/
  重试处理，不建立第二套恢复状态机。
- [文档读取 ACL](../../../spring-ai-rag-core/src/main/java/com/springairag/core/security/ApiKeyCollectionAccess.java)
  `requireDocumentAccess` 与旧 document GET 共用；能力 filter 已把 documents GET 归为 RAG_READ。
- [配置](../../../spring-ai-rag-core/src/main/java/com/springairag/core/config/RagChunkProperties.java)
  默认 size=1000/min=100/overlap=100；目前没有 profile/preview 限额。

## 2. 冻结设计决策

### 2.1 Profile 与发布边界

新增 `rag.chunk.profile` 枚举 `legacy-v2`（默认）与 `bounded-v3`（显式启用）。默认不静默使
现有索引失效。旧算法与旧 version 完全不变；v3 identity 使用
`bounded-v3:size:min:overlap:maxInputChars:maxChunks`，由原 descriptor provider 唯一产生。
JSON_RECORD identity 仍为 `json-record-v1:single`。

新增配置 `max-input-chars=1000000`、`max-chunks=20000`、`preview-max-chars=100000`。
v3 启动时验证 size>=32、0<=overlap<size、0<=min<=size、input/chunk 限额为正；所有 Profile
验证 preview 限额为正。防止非法预算造成非前进循环。size/min/overlap 维持现有值起步。
Profile 只允许启动配置选择；不开放每次请求覆盖参数，避免 preview 与生产不一致。

新 v3 类是唯一协调入口内的一种策略，不建立平行索引/Embedding 管道。不要把 legacy
缺陷包装成已修复：未 opt-in 的部署仍保持旧行为，preview 可显示其校验问题。

### 2.2 原文 span 与保护区

在未改写原文上单次扫描行界，保留 UTF-16 `[start,end)`；不能切断 surrogate pair。
标题用深度栈，同级替换、子级继承；原始标题行也保留为正文 span，避免仅标题文档被吞。

识别最多六级 Markdown ATX heading、反引号/波浪线围栏、独占行 `$$` 公式、合法 Markdown
pipe table。不是完整 CommonMark/LaTeX parser：其他形式作为普通文本保留，不删除。
围栏内的 `#` 和 table 不再次识别；未关闭围栏保留至 EOF 并输出 warning。

普通段落优先在换行/空白/句末附近切分，否则安全固定窗口；overlap 以原文范围表示，必须
前进。保护块能整体放入预算则整体保留；超限代码/公式按完整原文行拆分，长表按行拆分并
复制头两行作为 continuation 的生成上下文。超长单行或表头无法满足预算时抛安全异常，
禁止无限长例外、截断或暗中丢行。

标题路径是可选生成前缀；超过预算的一半时省略前缀并 warning，但原始标题仍在正文保留。
所有最终 payload（标题、表头、正文）均计入 size 硬限额。生成上下文与原文 span 分开统计；
不能要求 payload 与原文一个切片逐字相等，但 payload 的正文尾部必须对应该 span。

### 2.3 Validator、诊断与失败

documents 新增纯 Java `ChunkValidator`、结果/块描述 record 及安全 `ChunkingException`。
检查非空输入有输出、无空块、位置合法、正文 span 与 payload 对应、全部非空白原文被覆盖、
最终 payload 不超 size、块数不超限。coverage 以原文区间并集计算，overlap/生成前缀不计入。
碎片和短尾块只 warning，不丢弃；短文档始终保留。算法整体复杂度有界；Validator 按有序
区间线性扫缺口，不逐块扫描全文，不分配平方级矩阵。

v3 校验失败仅允许一次普通文本安全窗口回退；不得对保护块失败回退为随意截断原子结构。
预算、不可拆原子行错误直接失败。错误使用固定 code/摘要，不含 document 正文。
JSON_RECORD 不受普通分块 size/拆分规则影响，preview 标记 single-record，不谎报 size 合规。
legacy preview 执行诊断但不改变 legacy prepare 的拒绝/放行语义。

### 2.4 只读 preview HTTP

独立 `DocumentChunkPreviewController`，`@ApiVersion("v1")`，路由：
`GET /api/v1/rag/documents/{id}/chunks/preview?offset=0&limit=20`。
offset>=0，1<=limit<=100；查 document → 既有 ACL → preview 输入限额 → 同一 prepare 链。
不存在返回 404，ACL 拒绝403，参数/预算/算法安全错误400（固定 error code，不能含正文）。
沿用既有认证、RAG_READ、rate limit；不新增 POST 分类，不绕开权限。

响应冻结为 documentId、documentRevision、documentKind、profile、chunkerVersion、
totalChunks、offset、limit、hasMore、diagnostics、chunks。chunks 含 index、text、startPos、
endPos、kind、titlePath、generatedChars。diagnostics 含 valid、originalChars、
coveredNonWhitespaceChars、min/maxPayloadChars、generatedChars、overlapChars、warnings、
violations。legacy 元数据 kind=LEGACY；JSON_RECORD kind=JSON_RECORD。

响应只包含本页最多100块，preview 文档最大100000字符；计算是无副作用 CPU 操作，不写 DB、
不触发 Embedding/LLM。返回当前 documentRevision（读取时的快照），不保证与索引已入库版本
一致；修改文档后应重新 preview。限额错误不返回全文。诊断日志/metrics 不记录 text。

## 3. 实施切片与验收矩阵

1. documents：先缺陷回归测试，再原文 scanner、bounded splitter、Validator、错误/诊断 DTO；
   覆盖无效表格不丢、CJK/无标点、同级/嵌套标题、header-only、长表、围栏/公式/未闭合、
   CRLF、emoji、超长原子行、overlap、尺寸及内容守恒；固定种子随机输入验证前进与完整覆盖。
2. core/config：Profile/version、prepare 与 preview 共用结果、JSON_RECORD 与默认 legacy
   兼容；单测钉住身份变化、限额、snapshot 配置、无 provider 副作用。
3. API/controller：只读分页响应及400/403/404，MockMvc验证序列化、ACL、参数和RAG_READ分类。
4. 集成：用真实 PostgreSQL已有 lifecycle/embedding-profile/JSONB/processing gated IT回归
   派生路径；新增基于现有 lifecycle suite 的v3关键词落库/freshness/切换回滚测试，不建新开关。
5. 文档与脚本：双语 configuration/REST/testing/architecture/project-context说明开关、定位、
   预览和显式重索引/回滚；新增本批聚焦验收脚本。候选与本plan/progress完成后一起归档。

固定门禁（不因review增加范围）：

```bash
mvn -pl spring-ai-rag-core -am -Dtest=BoundedTextChunkerTest,ChunkValidatorTest,DocumentChunkingServiceTest,DocumentChunkPreviewControllerTest,RagChunkPropertiesTest,ApiCapabilityFilterTest -Dsurefire.failIfNoSpecifiedTests=false test
mvn clean compile test-compile
mvn test
./scripts/verify-gated-it.sh
./scripts/verify-no-pessimistic-locks.sh
./scripts/verify-project-docs.sh
git diff --check
```

没有前端修改，前端门禁不作为本批硬门槛。运行时在独立18083端口、一次性PostgreSQL库启动
postgresql profile，v3与legacy各一次启动/健康/preview；在v3用表格、代码、CJK合成goldenset
验证preview、真实BGE-M3 embedding及检索、引用来源的chunkIndex与preview一致。授权已允许
使用`.env`模型，Chat选上一批已验证M3临时registry（凭据占位引用，不改.env），调用有界。
不能把Mock或仅健康检查称为真实模型验收；失败实时排查，保留安全摘要。

## 4. 兼容、事务、发布与回滚

没有schema变化；TextChunk三字段不变，不改旧迁移。新元数据是纯计算预览，不引入父块FK。
索引仍经现有事务/CAS/lease提交；version变更自动使不匹配的派生物不fresh，不能绕过该门。
全局切v3会使既有v2内容不fresh，运营应先在隔离库验证，再安排显式重索引；不能宣传无缝
在线热切而忽略查询空窗。旧v2恢复需恢复配置并重启，对已经重建v3的文档再次显式重索引v2。

默认legacy是低风险兼容选择，不是宣称P0已在默认部署修复；文档明确启用v3才能获得新保证。
本批只交付配置级Profile，不承诺同一个进程中逐collection灰度，灰度通过独立部署/测试库。

## 5. 检查、进度与交付

按[工作流](../../delivery-workflow-zh-CN.md)先规划3/3，实施后先基本硬门槛和真实证据，再实现3/3。
实质问题修正计数归零；无问题轮次只向会话输出，不修改被检查正文。
`CHUNKING_QUALITY_IMPLEMENTATION_PROGRESS.md`记录切片、命令结果、环境、安全摘要与恢复入口。
本轮开始前没有WIP；保持main，不stash/reset/clean，提交前回看全部diff和secret scan。
收敛后提炼长青文档、归档plan/progress/candidate，commit→fetch/必要merge→复验→push，确认干净。
