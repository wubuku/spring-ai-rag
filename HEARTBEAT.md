# HEARTBEAT.md

## 🔴🔴 必须主动监控 Cron

**每次会话第一件事**：检查 cron 任务 `43a31dc1-ab83-4724-8879-1d39a78498a9`（Spring AI RAG 构建推进）的执行状态。
- 如果长时间没收到飞书汇报 → 立即 `openclaw cron runs --id 43a31dc1-ab83-4724-8879-1d39a78498a9 --limit 3` 查看状态
- 如果 error/timeout → 分析原因，手动修复（如：简化任务、修复代码问题）
- 如果调度卡住 → `openclaw cron run` 手动触发
- 汇报要发到飞书群

## 🔴 核心铁律

1. **7×24 不停歇** — 不等指令，主动找事做
2. **构建驱动** — 每个代码改动必须先通过 `mvn clean compile`
3. **🔴 测试是生产代码** — 每次写生产代码必须同步写测试，测试和代码同等重要
4. **🔴 E2E 验证不可省** — 有 REST 端点后必须运行 `scripts/e2e-test.sh` 验证完整链路
5. **测试失败 = 未完成** — `mvn test` 有失败就不提交、不汇报完成
6. **文档跟代码** — 更新代码就同步更新文档
7. **诚实报告** — 报告失败，不掩盖问题
8. **宁少勿错** — 没验证的不写，说错话会误导开发者

> ⚠️ **测试铁律来源**：用户明确要求——"对测试要像生产代码一样重视"，"端到端测试保证整个链路是真的通的"。此要求永久生效，不可删除或弱化。

## 🟡 巡检清单（每轮执行）

按顺序执行：

### 第一步：读参考资源

> ⚠️ 编写任何代码前，必须先参考这些资源。不确定的地方不要写。

**实施规划文档**（核心指南）：
- `/Users/yangjiefeng/Documents/wubuku/RuiChuangQi-AI/src/dermai-rag-service/docs/drafts/GeneralRAGService-Implementation-Plan.md`

**参考项目**（验证实现模式）：
- `/Users/yangjiefeng/Documents/wubuku/spring-ai-skills-demo` — ⭐ Spring AI 用法、ChatClient/Advisor/VectorStore 配置
- `/Users/yangjiefeng/Documents/taisan/MaxKB4j` — Pipeline 模式、模型提供者抽象
- `/Users/yangjiefeng/Documents/wubuku/RuiChuangQi-AI/src/dermai-rag-service` — 迁移来源、混合检索/重排/查询改写实现

### 第二步：构建验证

1. **构建检查**：`mvn clean compile`（项目骨架搭建阶段可跳过）
2. **运行测试**：`mvn test`（有测试时执行）

### 第三步：执行任务

3. **查看当前任务** → 有活跃任务则继续，没有则扫描改进点
4. **轮换关注领域**（按顺序循环）：
   - 轮 A：项目骨架与模块结构
   - 轮 B：核心配置类（SpringAiConfig、EmbeddingModelConfig、VectorStoreConfig）
   - 轮 C：RAG Pipeline（Advisor 实现、检索组件、文档处理）
   - 轮 D：API 层、测试、文档
5. **发现问题立即修复**，修复后重跑构建/测试
6. **提交并汇报**

### 迭代改进——ChatResponse 返回引用来源（新增）

| # | 任务 | 状态 | 备注 |
|---|------|------|------|
| I13 | ChatResponse.sources 填充 | ✅ 完成 | RerankAdvisor 存 context → RagChatService 用 chatClientResponse() 提取 → 填充 SourceDocument |

## 🟢 当前任务

> ⚠️ 做完一项立刻扫描下一项。全部做完就回头审阅改进。永远不要说"没事做"。

### Phase 1：基础框架搭建 + 底层依赖替换

| # | 任务 | 状态 | 备注 |
|---|------|------|------|
| 1 | 创建 Maven 多模块项目骨架 | ✅ 完成 | api / core / starter / documents，mvn compile 通过 |
| 2 | 配置 Spring Boot 3.4.2 + Spring AI 1.1.2 | ✅ 完成 | 注意：1.1.2 无 spring-ai-core，改用 spring-ai-model + spring-ai-client-chat |
| 3 | 实现 SpringAiConfig（三 Bean 模式） | ✅ 完成 | openAiChatModel + anthropicChatModel + chatModel |
| 4 | 实现 EmbeddingModelConfig | ✅ 完成 | SiliconFlow BGE-M3 |
| 5 | 实现 VectorStoreConfig | ✅ 完成 | PgVectorStore + HNSW，@Profile("postgresql") |
| 6 | Flyway 数据库迁移脚本 | ✅ 完成 | V1__init_rag_schema.sql（collection + embeddings + documents + chat_history） |
| 7 | 实现 RagChatService + Starter 自动配置 | ✅ 完成 | ChatClient + Advisors 整合 |
| 8 | 验证 mvn clean compile | ✅ 完成 | 全部 5 模块 BUILD SUCCESS |

### Phase 2：核心 RAG 组件迁移（待 Phase 1 完成）

| # | 任务 | 状态 | 备注 |
|---|------|------|------|
| 8 | 迁移 HybridRetrieverService | ✅ 完成 | HybridRetrieverService + EmbeddingModel + JdbcTemplate + pg_trgm 全文检索，融合分数去重 |
| 9 | 迁移 QueryRewritingService | ✅ 完成 | 移除领域硬编码词典，同义词/限定词通过 setter 配置 |
| 10 | 迁移 ReRankingService | ✅ 完成 | 无外部依赖，多维度相关性+多样性重排 |
| 11 | 迁移 HierarchicalTextChunker + TextCleaner | ✅ 完成 | 纯 Java 直接迁移，支持 Markdown 标题/表格/句子级分块 |
| 12 | 迁移 EmbeddingBatchService | ✅ 完成 | 适配 EmbeddingModel.embed() |
| — | **Phase 2 全部完成** | ✅ | 5 个核心组件全部迁移，mvn compile 通过 |

### Phase 3：RAG Pipeline + REST API

| # | 任务 | 状态 | 备注 |
|---|------|------|------|
| 13 | 实现 QueryRewriteAdvisor | ✅ 完成 | BaseAdvisor，order +10 |
| 14 | 实现 HybridSearchAdvisor + RerankAdvisor | ✅ 完成 | HybridSearch(+20)检索存context，Rerank(+30)取结果重排注入prompt |
| 15 | 更新 RagChatService + 对话记忆双表 | ✅ 完成 | Advisor 链 + ChatMemory + RagChatHistoryRepository 双表，@Value 构造函数注入 |
| 16 | 实现 REST Controller | ✅ 完成 | 4 个控制器：Chat/Search/Document/Health，77 测试全部通过 |
| 17 | 单元测试 + 集成测试 | ✅ 完成 | 核心 53 + 文档 15 + API 9 = 77 个测试，与控制器同步编写 |
| 18 | git commit + push | ✅ 完成 | commit 9f880a4，已推送 |

### Phase 4：领域扩展示例

| # | 任务 | 状态 | 备注 |
|---|------|------|------|
| 19 | DomainRagExtension 基础设施 | ✅ 完成 | 接口 + Registry + PromptCustomizerChain + DefaultDomainRagExtension（先前已完成） |
| 20 | RagChatService 集成领域扩展 | ✅ 完成 | 支持 domainId 参数，系统提示词+PromptCustomizer 链式调用 |

### 迭代改进（有空就做，不阻塞主流程）

> 先保证实现健壮、测试覆盖完整。不做 Redis 缓存等额外复杂度。

| # | 任务 | 优先级 | 说明 |
|---|------|--------|------|
| I1 | 向量索引性能调优 | 中 | HNSW 参数调优，基准测试 |
| I2 | E2E：嵌入生成端到端 | 中 | 上传 → 嵌入 → 检索 → 验证向量 |
| I3 | E2E：RAG 问答全链路 | 中 | 嵌入文档 → chat/ask → 验证引用 |
| I4 | E2E：SSE 流式输出 | 低 | 测试 /chat/stream SSE 格式 |
| I5 | 混合检索集成测试 | 中 | 向量+全文融合验证 |
| I6 | 错误处理完善 | ✅ 完成 | GlobalExceptionHandler 新增 6 种异常类型（400/404/405/500），测试 4→10 |
| I7 | 查询改写集成测试 | 低 | 同义词扩展效果验证 |
| I8 | 对话记忆验证 | 低 | 多轮对话上下文保持 |
| I9 | RagChatController 单元测试 | ✅ 完成 | 9 cases 覆盖 ask/stream/history/clearHistory |
| I10 | Starter 模块测试 | ✅ 完成 | Properties(4) + AutoConfiguration 注解验证(4) |
| I11 | README.md 补全 | ✅ 完成 | 快速开始、API 示例、架构图、领域扩展、监控 |
| I12 | @SpringBootTest 集成测试 | 低 | 用 Testcontainers 或嵌入式数据库测试完整上下文 |

### Phase 5：运维支持

| # | 任务 | 状态 | 备注 |
|---|------|------|------|
| 21 | RagMetricsService（Micrometer） | ✅ 完成 | Timer/Counter/Gauge，追踪请求成功率/响应时间/LLM tokens |
| 22 | RagHealthIndicator（Actuator） | ✅ 完成 | 集成 /actuator/health，检查 DB + 表数据 + 指标 |
| 23 | RagChatService 集成指标 | ✅ 完成 | 自动记录每次请求耗时，metricsService 可选 |
| 24 | Starter 自动配置更新 | ✅ 完成 | 条件注册 metrics/health Bean |
| 25 | 测试 | ✅ 完成 | RagMetricsServiceTest(8) + RagHealthIndicatorTest(3) |
| 26 | git commit + push | ✅ 完成 | commit 781e5ae，已推送 |
| 27 | DEPLOYMENT.md 部署文档 | ✅ 完成 | 环境要求/数据库/配置/构建/Docker/监控/故障排查 |
| 28 | 性能优化（连接池/缓存） | ✅ 完成 | Caffeine 嵌入缓存 + HikariCP 调优 + 检索线程池 |

## 🔵 轮换关注记录

| 领域 | 上次检查 | 状态 |
|------|----------|------|
| 轮 A：项目骨架 | 2026-03-31 14:14 | ✅ 5 模块编译通过 |
| 轮 B：核心配置 | 2026-03-31 14:14 | ✅ SpringAiConfig + EmbeddingModelConfig + VectorStoreConfig |
| 轮 C：RAG Pipeline | 2026-04-01 03:49 | ✅ RetrievalUtils 提取 + 检索组件测试 247 全通 |
| 轮 D：API + 测试 + 文档 | 2026-04-01 03:49 | ✅ 247 测试全通，73 源文件 |

## 📊 质量基线

- 模块数：5（parent + api + core + starter + documents）+ 2 demos
- Java 源文件数：84（主项目 75 + demo-basic-rag 3 + demo-domain-extension 6）
- 测试数：262（主项目 249 + demo-domain-extension 13）
- 构建状态：✅ BUILD SUCCESS（mvn clean compile + test）
- Git 提交：32 次（最新 329637f）
- 文档数：6（README.md + docs/DEPLOYMENT.md + demos/README.md + demo-basic-rag/README.md + demo-domain-extension/README.md + 实施规划文档）

## ⏰ Cron 任务

- **任务 ID**：43a31dc1-ab83-4724-8879-1d39a78498a9
- **名称**：Spring AI RAG 构建推进
- **频率**：*/15 * * * *（每 15 分钟，随机延迟最多 5 分钟）
- **超时**：900 秒（15 分钟/轮）
- **投递**：飞书 oc_88169e26b29cf029d2173cfb6c368433

## 📝 进度日志

- ✅ 2026-04-01 05:18 新增 demo-domain-extension 领域扩展示例——MedicalRagExtension（专业问诊提示词+高召回检索配置+关键词适用性校验）+ MedicalPromptCustomizer（领域消息格式化）+ MedicalRagController（3 接口：完整问诊/快速问诊/普通问答对比）+ 13 个单元测试全通 + README.md（三步添加新领域指南）。commit 329637f。已推送。
- ✅ 2026-04-01 04:46 新增 demo-basic-rag 示例项目 + GitHub Actions CI——demo-basic-rag: BasicRagDemoApplication + DemoController（展示 RagChatService 两种调用方式）+ application.yml 完整配置模板 + README.md（前置条件/启动/API 测试/模型切换/领域扩展）。.github/workflows/ci.yml: Maven CI（compile → test → package）。75 源文件 | 249 测试全通。commit 3c2d3cd。已推送。
- ✅ 2026-04-01 04:13 代码质量改进——添加 Bean Validation 输入校验：spring-boot-starter-validation(core) + jakarta.validation-api(api) 依赖。ChatRequest 添加 @NotBlank message/sessionId + @Size(max=10000)。SearchRequest.query、DocumentRequest.title/content 添加 @NotBlank。3 个 Controller 方法添加 @Valid。GlobalExceptionHandler 新增 MethodArgumentNotValidException 处理器（单字段/多字段校验失败返回 400 + 结构化错误信息）。新增 2 个测试，总计 249 个全部通过。commit 1aabe80。已推送。
- ✅ 2026-04-01 03:49 代码重构 + 测试覆盖深化——提取 RetrievalUtils 工具类（cosineSimilarity/vectorToString/parseVector/fuseResults），消除 HybridRetrieverService 中重复的私有算法代码。新增 RetrievalUtilsTest(26)：余弦相似度边界/高维/零向量、向量解析多格式、分数融合去重/排序/权重。重写 QueryRewritingServiceTest(23)：用 ReflectionTestUtils 注入 enabled，覆盖同义词/限定词/padding/disabled 场景。增强 ReRankingServiceTest(20)：覆盖 enabled/disabled、相关性/多样性评分、文本相似度。总计 247 个测试全部通过。commit 325a71b。已推送。
- ✅ 2026-04-01 03:19 功能增强——ChatResponse 返回引用来源（sources）：RerankAdvisor 新增 RERANKED_RESULTS_KEY 将重排结果存入 request context（Spring AI ChatModelCallAdvisor 自动复制到 response context），RagChatService 重构为 executeChat() 私有方法，使用 chatClientResponse() 从 context 提取检索结果填充 SourceDocument（含 documentId/chunkText/score）。chat(String) 向后兼容，chat(ChatRequest) 返回完整响应。新增 3 个测试 + RerankAdvisorTest 补充 context 验证。总计 185 测试全部通过。commit 736938c。已推送。
- ✅ 2026-04-01 02:48 代码质量改进——RagChatController.clearHistory() 实现真实删除（原为 stub），提取 SimpleJsonUtil 消除 RagChatHistoryRepository 和 RagDocumentController 中重复的 toJson/escapeJson，新增 SimpleJsonUtilTest(11)。总计 183 个测试全部通过。commit 42c4ccf。已推送。
- ✅ 2026-04-01 01:50 代码质量改进——提取 AdvisorUtils 消除 3 个 Advisor 中 extractUserMessage() 重复代码，新增 AdvisorUtilsTest(5)。总计 169 个测试全部通过。commit 69b9e43。已推送。

- ✅ 2026-04-01 01:21 迭代改进完成——新增 RagChatController 单元测试(9)、Starter 模块测试(8)、GlobalExceptionHandler 错误处理完善(6 种异常)、README.md 补全。总计 164 个测试全部通过。commits: ceebd64, 291e9dc, 38b8ec3。已推送。
- ✅ 2026-04-01 00:33 测试覆盖率补充——新增 4 个测试文件：QueryRewriteAdvisorTest(10)、EmbeddingBatchServiceTest(8)、GlobalExceptionHandlerTest(4)、DefaultDomainRagExtensionTest(6)。同时修复 GlobalExceptionHandler.handleBadRequest 中 Map.of() 不接受 null 值的 NPE bug。总计 113 个测试全部通过。commit e847aa0
- ✅ 2026-04-01 00:18 Task 28 完成——性能优化：CacheConfig（Caffeine 嵌入缓存 10k/2h + 默认缓存 2k/30min）、PerformanceConfig（缓存嵌入模型包装器 + 4 线程 ragSearchExecutor）、HybridRetrieverService 注入专用线程池、HikariCP 连接池调优（maxSize=20, idle/leak detection）。新增 8 个测试，总计 104 个全部通过。commit c109824
- ✅ 2026-03-31 23:35 Phase 5 Task 21-26 完成——实现 Micrometer 监控指标（RagMetricsService: Timer/Counter/Gauge 追踪请求成功率/响应时间/LLM tokens）+ Actuator 健康检查（RagHealthIndicator: 检查数据库连接+表数据+指标摘要）+ RagChatService 集成自动指标记录 + Starter 条件注册。新增 11 个测试（8+3），总计 97 个全部通过。commit 781e5ae
- ✅ 2026-03-31 23:37 Task 27 完成——DEPLOYMENT.md 部署文档：环境要求/数据库准备/配置示例/构建运行/Docker/监控端点/指标说明/领域扩展集成/故障排查。commit 3c834c2
- ✅ 2026-03-31 20:40 Phase 3 Task 16-18 完成——实现 4 个 REST 控制器：RagChatController（/ask, /stream, /history）、RagSearchController（GET/POST 直接检索）、RagDocumentController（文档 CRUD + 嵌入标记）、RagHealthController（健康检查）。新增 15 个单元测试，总计 77 个全部通过。commit 9f880a4

- ✅ 2026-03-31 19:04 Task 15 完善——RagChatService @Value 改为构造函数注入，修复 maxMessages 在构造时为 0 的问题。删除编译失败的 RagSearchController（Task 16 待实现）。commit bb323c6
- ✅ 2026-03-31 17:55 Phase 3 Task 14 完成——实现 HybridSearchAdvisor + RerankAdvisor。HybridSearchAdvisor(order +20)调用 HybridRetrieverService 混合检索，结果存入 context attributes；RerankAdvisor(order +30)从 context 取结果调用 ReRankingService 重排，注入 Prompt 系统消息。16 个单元测试（7+9），全部 44 个测试通过。commit 5b68660
- ✅ 2026-03-31 15:41 Phase 3 Task 13 完成——实现 QueryRewriteAdvisor（BaseAdvisor，从 Prompt 提取 user text，调用 QueryRewritingService.rewriteQuery()，结果存入 context 供 HybridSearchAdvisor 使用），mvn compile + test 通过
- ✅ 2026-03-31 15:16 Phase 2 Task 8 完成——迁移 HybridRetrieverService（适配 Spring AI EmbeddingModel + JdbcTemplate + pg_trgm 全文检索，融合分数去重），mvn compile 通过
- ✅ 2026-03-31 15:17 Phase 2 Task 9 完成——迁移 QueryRewritingService（移除领域硬编码词典，同义词/限定词通过 setter 配置），mvn compile 通过
- ✅ 2026-03-31 15:19 Phase 2 Task 10 完成——迁移 ReRankingService（多维度相关性+多样性重排），mvn compile 通过
- ✅ 2026-03-31 15:22 Phase 2 Task 11 完成——迁移 HierarchicalTextChunker + TextCleaner（纯 Java 直接迁移），mvn compile 通过
- ✅ 2026-03-31 15:23 Phase 2 全部完成——5/5 核心组件迁移，mvn compile 通过
- ✅ 2026-03-31 15:08 应用启动验证——Flyway 迁移成功、ChatModel/ChatClient 创建正常、PostgreSQL 连接正常
- ✅ 2026-03-31 14:26 Git push 完成，commit 9bca499（25 files，+1587 行，首个版本）
- ✅ 2026-03-31 14:25 测试通过——10/10 单元测试全部通过
- ✅ 2026-03-31 14:19 Cron 任务创建——每 30 分钟自动推进
- ✅ 2026-03-31 14:14 Phase 1 完成——Maven 多模块骨架 + 全部配置类 + Flyway 迁移脚本，mvn compile 全部通过
- ✅ 2026-03-31 13:41 PM-24x7 技能适配——SOUL/HEARTBEAT/TOOLS 完善
- ✅ 2026-03-31 13:29 项目初始化——工作区文件、.gitignore
