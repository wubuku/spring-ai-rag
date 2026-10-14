# 知识库 Chat：有界证据组装与依据完整性回归规划

> 2026-10-14；状态：实施完成，待归档；规划连续3/3、实现连续3/3均通过；基线 main/e5f87d5f。
> 已获用户实施授权；沿用指定 main 工作区/分支。
> 标准：[交付工作流](../../delivery-workflow-zh-CN.md)；不修改参考仓库。

## 1. 目标与范围

仅实施“检索后证据上下文组装”，答案依据完整性回归作为交付门槛。不做路由授权、
parent-child 重建、多数据库、强制 Collection 覆盖或自然语言 claim coverage 产品。
目标是补全命中附近的定义、表头、步骤/条件，消除正文 overlap，保留 anchor 与引用身份；
不改变 Search 排序/数量/DTO、AGENT/PLAIN、JSON_RECORD、静态知识和 SSE/replay 协议。

## 2. 已探索的事实与实施入口

- [工厂](../../../spring-ai-rag-core/src/main/java/com/springairag/core/chat/ModeAwareChatClientFactory.java)：
  Spring AI 1.1.8 的 KNOWLEDGE 链为 rerank → prompt budget → citation augmenter。
  保留旧构造签名；新处理器由生产 Spring 注入，测试可显式注入。
- [Joiner](../../../spring-ai-rag-core/src/main/java/com/springairag/core/rag/ProjectDocumentJoiner.java)：
  只按稳定 chunk identity 去重，不做邻居扩展/原文范围合并。
- [本地 SQL freshness](../../../spring-ai-rag-core/src/main/java/com/springairag/core/retrieval/KeywordIndexSqlScope.java)
  与 [scope/filter](../../../spring-ai-rag-core/src/main/java/com/springairag/core/retrieval/RetrievalScopeSql.java)
  已包含 hash/chunker/generation 与授权范围、metadata/payload predicate，复用而非另造。
- [V43](../../../spring-ai-rag-core/src/main/resources/db/migration/V43__decouple_keyword_chunks_from_embeddings.sql)
  已有 index、Java UTF-16 start/end、generation/hash/chunker。无需 schema 迁移，保持 V63。
- [Validator](../../../spring-ai-rag-documents/src/main/java/com/springairag/documents/chunk/ChunkValidator.java)：
  chunk.text 可含合成标题/表头前缀，真实源区间是尾部。不能用 PostgreSQL substring 解释
  Java UTF-16 偏移（PostgreSQL 按字符），不能按文本相似度去重。
- [Mapper](../../../spring-ai-rag-core/src/main/java/com/springairag/core/rag/RetrievalDocumentMapper.java)、
  [Augmenter](../../../spring-ai-rag-core/src/main/java/com/springairag/core/rag/CitationQueryAugmenter.java)、
  [最终来源提取](../../../spring-ai-rag-core/src/main/java/com/springairag/core/chat/ChatExecutionService.java)：
  handle 按 anchor identity 分配，KNOWLEDGE 来源取最终 advisor Documents。无须改 Registry
  或把邻居注册为新来源，不能污染 query cache/AGENT source 状态。
- [预算](../../../spring-ai-rag-core/src/main/java/com/springairag/core/rag/PromptBudgetDocumentPostProcessor.java)：
  当前前缀截断可能丢掉扩展窗口中的 anchor，需要先回退 anchor。token 估算可能格式化
  metadata，私有 anchor 必须从被估算的 metadata 中去掉。
- [质量指南](../../quality-defaults-zh-CN.md)/[测试指南](../../testing-guide-zh-CN.md)：复用现有检索
  回归，补最终模型输入、引用范围和必要事实，不另造评测平台。

## 3. 冻结契约与默认

新增 rag.chat.context-assembly：enabled=true，neighbor-radius=1（0–2），max-seeds=20
（1–40），max-window-characters=4000（256–16000）。应急关闭只停止扩展；默认无需配置。
链路：检索/Joiner → rerank → assembly → budget → citation。处理器不新增模型调用或来源数量。
只扩展前 max-seeds 个可定位、已注册引用的数字 documentId/chunkIndex；其余保持原结果。

每次最多一个参数化 SQL，用 VALUES 传 ordinal/id/index/text。当前 anchor 必须与原 seed
文本完全相等，再按当前一致快照重新定位，绝不使用 seed.metadata 的位置/身份作为信任源。
复用 freshness 与 scope/filter，补 deleted/JSON 边界；邻居必须同 document/generation/
hash/chunker。限制 seed 数、radius、行文本长度；不读取整篇正文、不做 N+1。
无一致 anchor、无本地索引、DB故障则不增加内容，保留原检索结果，记录有限降级 code；
不能把降级描述为扩展成功，不输出 SQL 参数、原文或异常 message。

由 text.length-(end-start) 分离合成前缀/真实 payload，按 UTF-16 区间裁掉正文 overlap；
相同合成前缀可单独去重，不同前缀保留。gap 用分隔符连接并保留各段范围，不伪造连续
substring，不补入未检索原文。坏 range 或 surrogate 边界拒绝该窗口，回退原 anchor。
窗口必须包含 anchor，过字符上限就回退。多个窗口只在同身份、chunk范围交叠/相邻且
合并后仍有界时合并；输出保留最高排名 anchor 的 id/score/citation 与顺序。

## 4. 引用、预算、诊断

安全 source metadata 增加 contextAssembly：version=1、anchorChunkIndex、contentHash/
chunkerVersion/localIndexGeneration、有序 ranges（chunkIndex/start/end/
syntheticPrefixCharacters）。chunkIndex 仍指 anchor，新映射明确这是证据组。
Mapper 只放行这个安全映射，不放行私有原 anchor Document。

窗口超剩余 token 时先恢复原 anchor，再按现有预算裁剪。裁剪后的 Document 移除范围组
映射并设置 contextBudgetTruncated，不能宣称未展示邻居仍被引用；不拆 surrogate。
私有 anchor 不参与 token 格式化/来源投影。adaptive planning 显式关闭但本次实际新增
窗口时仍遵守 max-rag-context-tokens；没有窗口则保留旧开关语义。

attempt summary 与持久 diagnostics 增加低敏 contextAssembly：候选/窗口/新增邻居/
合并/overlap去重/文档覆盖数量、SQL次数、elapsedMs、有限 degradedCode；预算后补
最终证据数/估计token/anchor回退/丢弃/截断。summary 不含ID/hash/原文/异常。
复用 trace/parent session，不新增高基数指标。范围和身份仅在已授权 source metadata 中。

## 5. 文件级实施顺序

1. 新恢复无关的配置类、RagChatProperties/application.yml 默认与校验单测。
2. RetrievalContextRepository 与 RetrievalContextAssemblyPostProcessor，窗口/合并/映射。
3. 工厂接入、PromptBudget回退/安全截断、Mapper白名单、trace/parent session诊断。
4. 配置/算法/预算/mapper/工厂/diagnostics 单测；扩展已有 MultiCollectionRetrievalPostgres
   IntegrationTest，沿用 multi.collection.it.enabled 与 ALL_SUITES。
5. 版本化 testdata/regression/context-assembly-v1.json 夹具，以及可重复的真实 Chat
   冒烟工具：创建/清理自有fixture、验证范围/必要事实/引用/诊断，失败返回非零；
   有限真实调用、不打印secret。新聚合 runner 在 gate-registry 登记 manual，不豁免门禁。
6. configuration、architecture/project-context、REST（source metadata加法）、testing/
   quality-defaults 双语同步；plan/progress导航，完成后归档。

## 6. 验收矩阵（实施前冻结）

| 层次 | 必须证明 |
|---|---|
| 配置 | 默认开、边界校验；关闭不查询、不扩展 |
| 算法 | 前后邻居；overlap去重/gap分隔；不同身份隔离；标题/表头前缀；中文/emoji；坏range/空/重复/过限；排名anchor保真 |
| 预算/引用 | token有界；不够先恢复anchor；裁剪去虚假范围；surrogate安全；handle不变；邻居不耗新引用；私有anchor不泄漏 |
| 主链 | advisor实际模型输入含必要事实；sources与输入一致；Search/AGENT/PLAIN/JSON/static不改；无额外invocation |
| PG | 同generation/hash/chunker；Collection/document/metadata过滤；禁用/删除/内容/chunker变化；seed文本不一致；关键词-only；空库V63且无schema修改；一个有界SQL |
| 质量 | 版本化多场景夹具：baseline仅anchor缺事实，候选完整且去重复；表格/流程/跨块定义/多文档；预算不足保anchor；真实答案包含必要事实而非只要求非空 |
| 后端 | clean compile test-compile、默认全量mvn test、全部PG门控、已有真实检索质量回归、禁锁/secret/diff与静态测试链 |
| 前端 | 仅metadata加法，无前端代码；全量Vitest、TypeScript/生产构建；专项Mock Playwright N/A；真实dev.sh检验WebUI/代理 |
| 真实运行 | 自有PG/隔离端口/env，实际dev.sh；.env已授权BGE与Chat、有限调用，校验引用/范围/必要事实；不碰他人服务/凭据 |
| 交付 | 双语文档门禁、实现连续3/3、归档冻结、commit/fetch安全合并/push main，干净后停止 |

真实调用只证明有限用例，不宣称普遍准确率提升。发布主门槛是稳定的模型输入与必需证据/
引用/预算断言，不能只依赖易变的 LLM judge。真实必要事实缺失按失败处理，不放宽断言。

## 7. 风险、回退与收敛

成本为一次有界DB读取和既有预算内可能增加的prompt token，非额外invocation。噪声由
radius=1/字符预算/质量矩阵控制。无法证明位置就回退，不猜。metadata为加法，关闭无需
数据回滚；没有重嵌入、迁移或运维正常发布步骤。公开检索排名语义保持不变。

发现实质问题立即修订并归零，连续规划3/3后才修改代码。progress保存可恢复实施上下文。
基本门槛通过后实现只读3/3；无问题轮次只在会话输出。提炼稳定事实、归档再冻结，随后
Git交付。无阻断性待决项，不扩大到第3项路由授权。

规划收敛：21:48/21:48/21:49（Asia/Shanghai），依次检查范围/默认/自包含、SQL/
UTF-16/引用/预算、验收/回退/交付。连续三轮无问题、无需修改；本记录在3/3后集中写入。
