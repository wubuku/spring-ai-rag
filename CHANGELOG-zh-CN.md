# Changelog

📖 [English](CHANGELOG.md) · 📖 [中文](CHANGELOG-zh-CN.md)

---

本项目所有值得关注的变更都记录在本文件中。

本文件格式遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)。

## [未发布] - 2026-08-17

### 新增
- 默认关闭的 OpenAI Chat Completions 兼容预览：model alias、完整 text-only messages、标准 JSON/SSE 信封与请求级 Collection 范围
- Flyway V33 持久化 embedding/reindex jobs：活动任务合并、lease、有界重试、取消、陈旧写入 fencing 与管理 API
- JSONB `payloadContains`：SQL 下推的 PostgreSQL containment、V34 partial GIN 索引和可选的服务端授权 `searchJsonRecords` Spring AI Tool
- 版本化真实检索 fixture、提交的 baseline、一键质量门禁与 release verifier 集成

### 变更
- Flyway 迁移范围更新为 V1–V34
- OpenAI、embedding jobs、JSONB、文档和质量回归均提供独立一键验证脚本

## [1.0.0] - 2026-07-21

### 新增
- 生产检索默认组合：查询改写、本地 heuristic rerank，以及可复现的 goldenset 脚本，输出 Precision@K、MRR、nDCG 增益
- API Key `allowedCollectionIds` 集合 ACL，覆盖 Chat、Search、Collection、Document、上传与 PDF-to-RAG 数据面
- 运行时选模：按配置为 OpenAI 兼容与 Anthropic 端点创建、缓存独立 ChatModel 实例
- 模型级注册 API、WebUI Settings/Chat 选模、偏好持久化，以及按 `provider/model` 对比
- Flyway V24 API Key collection ACL 迁移

### 变更
- 全部 Maven 模块与 Demo 发布为 `1.0.0`
- 生产、Docker Compose 与 Helm 默认端口统一为 `8081`
- Docker 与 Helm 默认使用不可变 `1.0.0` tag
- Dockerfile 基础镜像可覆盖；本地构建默认使用中国境内镜像并自动回退官方源
- OpenAI 兼容 `base-url` 配置与文档统一不带 `/v1`
- 外部 `models.json` 完整覆盖 YAML 模型清单，并支持绝对路径和 `file:` URI
- 发布验证支持 `--with-local-runtime`，一条命令管理本地服务并执行 HTTP E2E、goldenset 与真实 LLM smoke
- 发布门禁校验内嵌 WebUI 入口引用、资源存在性与 Git 可跟踪性，防止发布包引用未提交的 hash 资源

### 安全
- 受限 NORMAL Key 无法创建超出自身集合权限的子 Key
- Key 轮换保留 collection ACL；ADMIN 与可选静态 Key 保持全库权限

### 修复
- Chat ask/stream 对 `sessionId` 执行 36 字符上限校验，避免超长值触发 Chat Memory 数据库 500
- PDF-to-RAG E2E 正确解析 SSE `event:done`，并区分 curl、HTTP、SSE error 与完成事件
- 真实 LLM smoke 使用独立 Collection/Document 范围，ask/stream 均验证本轮 code，并自动清理测试数据
- 真实 LLM stream 校验按 SSE JSON 分片拼接 `delta.content`，避免 code 跨分片时误报失败

### 文档
- 新增中英文生产质量默认值、外部模型配置和发布清单
- 新增中英文中国境内开发网络避坑指南，以及可留存逐项日志的一键发布验证脚本
- 更新 README、上手、配置、REST API、部署、Docker、Helm 与排障文档中的端口和版本口径

## [1.0.0-SNAPSHOT] - 2026-04-11

### 新增
- `evaluateAnswerQuality` 有界超时 + 降级：`CompletableFuture.orTimeout()` 10 秒超时，LLM 调用挂起或超时时优雅降级为 "unknown" 质量
- `EmailNotificationService` 指数退避重试：SMTP 失败重试 3 次，指数退避（2s/4s/8s），并对可重试异常分类
- `RetrievalEvaluationServiceImpl` Micrometer 指标：`rag.evaluation.duration`（Timer）+ `rag.evaluation.count/batch_count/hits/misses`（Counter）
- 文档日期范围过滤：`listDocuments` 端点新增 `GET /documents?createdAfter=&createdBefore=` 参数
- `CollectionMapper` 工具类抽取：把 `toCollectionSummary/toDocumentSummary/toCollectionResponse` 从 `RagCollectionController` 中抽出以便复用
- `DocumentMapper` 工具类抽取：把 `toDocumentResponse/toBatchEmbedResult/toReembedResult` 从 `RagDocumentController` 中抽出
- `EmbeddingCircuitBreaker`：包裹 `EmbeddingBatchService.embedBatch()` 的熔断器——连续失败后打开、半开探测、恢复后继续
- SSE 心跳机制：`SseEmitters.sendHeartbeat()` + `RagSseProperties.heartbeat-interval-seconds`（默认 30 秒），防止代理关闭空闲连接
- SSE emitter 辅助工具：从 `RagChatController` 中抽出 `SseEmitters` 工具类（`sendProgress/sendDone/sendError/sendHeartbeat/completeWithError`）
- 6 个可变实体加 `@Version` 乐观锁（`RagDocument`、`RagCollection`、`RagAlert`、`RagAbExperiment`、`RagRetrievalLog`、`RagUserFeedback`）
- k6 压测套件（K6-1 到 K6-7）：Collection CRUD / A/B 实验 / 告警与反馈 / 聊天延迟拆分 / 爬坡到饱和 / 持久会话压力 / 向量检索压力
- Mock LLM 服务 `scripts/mock-llm-server.js`：OpenAI 兼容的 `/v1/chat/completions` + `/v1/embeddings`，支持 SSE 流式与可配置延迟/错误率
- WebUI：SSE 流式打字机效果、文件上传进度、深色模式自动跟随系统 + 手动开关锁定
- WebUI：搜索历史 localStorage 持久化 + 去重
- WebUI：错误边界与客户端错误上报（`POST /api/v1/rag/client-errors`）
- WebUI：基于 `react-i18next` 的 i18n 框架（英文 + 中文，设置页语言切换）
- WebUI：W12 文档版本对比界面（LCS diff 算法，双版本并排）
- WebUI：W13 A/B 测试实时看板（Recharts 柱状图、显著性检验、变体表）

### 修复
- `listDocuments` N+1 查询：单次 `findAllById()` 批量取回所有 Collection 名称——数据库往返 O(N)→O(1)
- `evaluateAnswerQuality` 异常路径：`TimeoutException` 返回质量 "unknown" 并附 "timed out" 原因；`InterruptedException` 向上传播并附 "interrupted" 原因
- `batchEmbedDocumentsWithProgress` ClassCastException：`countByDocumentId` 返回的 `embeddingsStored` 是 Long 而非 Integer
- `RagChatService.invokeChatClient()` 空检查：访问 `getContent()` 前补上 LLM 结果的空值保护
- SSE `sendHeartbeat()` 格式：改用 `.comment()` SSE API 输出正确的注释格式，而不是 `.name("heartbeat")`
- SSE `sendError()` 兜底：发送成功时正常完成，仅在失败时才回退到 `completeWithError()`
- `ChatModelRouter.resolve()` NPE：为 `providerId` 增加空检查，避免多模型路由中触发 NPE
- 移除 MiniMax base-url 的 `/v1`（MiniMax 端点本身已包含 `/v1`）
- MiniMax `role:system` 不兼容：`ApiCompatibilityAdapter` 自动识别并把 system 转为带 `[System]` 前缀的 user
- V17 Flyway 迁移：修正表名（`rag_document`→`rag_documents`、`rag_alert`→`rag_alerts` 等）
- OpenApiContractTest 上下文加载：补充 `@MockBean RagClientErrorRepository`（`ClientErrorServiceImpl` 构造函数变更后需要）
- CorsConfig 导致测试失败：RagControllerIntegrationTest 改用静态 `@TestConfiguration` 提供真实 `RagProperties` 实例
- `RetrievalUtils` NaN 分数：`fuseResults` 现在能优雅处理全零向量/全文检索分数（不再出现除零产生的 NaN）
- 检索分数 NaN：`ddText` 分数空值安全回退为 `?? 0.0`
- SSE 流式解析：改用 `split('\n\n')` 正确分隔 SSE 事件

### 变更
- `RetrievalEvaluationServiceImpl`：LLM 调用有界超时 10 秒，超时时降级为 quality=3/3/3 "unknown"
- `ApiCompatibilityAdapter`：新增 8 个边界用例（6→14），`supportsSystemMessage()`/`normalizeMessages()` 全覆盖
- `ModelRegistry`：新增 27 个路由用例（10→37），`getPrimaryEmbeddingModel()`/`getEmbeddingModelByProvider()` 全覆盖
- `DocumentEmbedService`：`batchEmbedDocumentsWithProgress` 重构——抽出 `sendDocumentProgress/updateBatchCounters/phaseForStatus/phaseMessage/buildBatchResult`
- OpenAPI 合同测试：排除 `DataSourceAutoConfiguration` + `HibernateJpaAutoConfiguration` 以避免依赖数据库
- SSE 流式：采用 OpenAI 兼容的 `data:{"choices":[{"delta":{"content":"..."}}]}` 格式并做 JSON 转义
- Vite 配置：开发服务器把 `/api` 代理到 `http://localhost:8081`（支持只启动前端、连接真实后端开发）

### 文档
- `docs/pgvector-index-comparison.md`：HNSW 与 IVFFlat 算法对比、决策矩阵、参数调优、迁移 SQL
- `docs/grafana/rag-service-dashboard.json`：44 面板 Grafana 看板（Advisor/Model/Cache/SlowQuery 面板）
- `docs/SSE-PROTOCOL.md`：SSE 流式协议文档（OpenAI 兼容格式）
- `docs/hybrid-search-enhancement-plan.md`：混合检索增强 Phase 1-4 路线图
- `IMPLEMENTATION_COMPARISON.md` 统计更新：1513+ 测试，零 TODO/FIXME

### 变更（技术栈）
- Java 17 → **Java 21**（LTS，虚拟线程）
- Spring Boot 3.4.x → **3.5.3**
- Spring AI 1.1.2 → **1.1.4**
- Maven 3.9.x → **3.9.14**
- `spring.threads.virtual.enabled=true`（I/O 密集型操作使用虚拟线程）
- GitHub Actions：Java 21 + `setup-java cache=maven` + Codecov 覆盖率上传
- Dockerfile：多阶段（Maven 构建 → jlink JRE → distroless），eclipse-temurin:17-jre，非 root 用户，<200MB

### 测试
- JaCoCo 覆盖率：Core 93% 指令 / 78% 分支
- 全部 13 个 controller 都有独立的单元测试文件（100% 覆盖）
- 全部 service 类都有单元测试文件
- 142 个 vitest 测试（WebUI）+ 12/12 Playwright E2E

## [1.0.0-SNAPSHOT] - 2026-04-10

### 新增
- 6 个可变实体的 `@Version` 乐观锁：RagDocument、RagCollection、RagAlert、RagAbExperiment、RagRetrievalLog、RagUserFeedback
- `V17__add_version_column.sql`：为乐观锁添加 version 列的 Flyway 迁移
- `SilenceSchedule` 集成：`AlertServiceImpl` 在触发告警前检查生效的静默窗口——`SilenceScheduleRequest`/`SilenceAlertRequest` DTO
- `ApiSloHandlerInterceptor` 12 个单元测试：覆盖全部 HTTP 方法、SLO 达标率计算、并发延迟记录
- `RagCircuitBreakerProperties` 4 个单元测试：校验全部属性字段
- `ModelController` 多模型覆盖：`multiModelEnabled=false` 用例，`listModels/getModel/compareModels` 全部测试
- `RagAlertTest`（9 个）+ `RagAbExperimentTest`（8 个）实体单元测试
- `RagRetrievalLogRepositoryTest` 22 个测试：全部查询方法
- `RagRetrievalLogEntityTest` 7 个测试：version/tostring/默认值
- `DocumentMapper` 抽取：把 `toDocumentResponse/toBatchEmbedResult/toReembedResult` 从 RagDocumentController 抽出
- `CollectionMapper` 抽取：把 `toCollectionSummary/toDocumentSummary/toCollectionResponse` 从 RagCollectionController 抽出

### 修复
- `CollectionMapper.toCollectionSummary` N+1：单次查询批量取回所有文档计数
- `ChatModelRouter` NPE：在 `resolve()` 中为 providerId 增加空检查
- `SseEmitters.sendHeartbeat()`：改用 `.comment()` SSE API 输出正确的心跳注释格式
- `SseEmitters.sendError()`：发送成功时正常完成，仅在失败时才回退到 `completeWithError()`
- WebUI `useSearchHistory` 测试稳定性：把顺序操作拆进独立的 `act()` 块

## [1.0.0-SNAPSHOT] - 2026-04-09

### 新增
- `EmbeddingCircuitBreaker`：包裹 `EmbeddingBatchService.embedBatch()`——连续失败后打开、半开探测、恢复后继续
- SSE 心跳：`SseEmitters.sendHeartbeat()` + 可配置间隔（默认 30 秒），穿过代理保持连接
- `SseEmitters` 工具类：`sendProgress/sendDone/sendError/sendHeartbeat/completeWithError`——从 `RagChatController` 抽出
- k6 压测 K6-5/K6-6/K6-7：爬坡到饱和的 VU 探测 + 持久会话压力 + 向量检索压力
- 4 个实体的 `@Version` 乐观锁：RagDocument、RagCollection、RagAlert、RagAbExperiment
- `listDocuments` N+1 修复：单次数据库调用批量取回 Collection 名称——往返 O(N)→O(1)
- `evaluateAnswerQuality` 异常路径：`TimeoutException` + `InterruptedException` 测试覆盖
- `ModelRegistry` 路由测试：新增 27 个（10→37），覆盖 `getPrimaryChatModel/getModelByProvider/getPrimaryEmbeddingModel`
- R6 主源码 i18n：26 个文件的 Javadoc/注释翻译为英文
- R6 测试 DisplayName i18n：HybridSearchAdvisorTest、QueryRewriteAdvisorTest、RagChatServiceTest、AlertServiceImplTest、ChatMemoryMultiTurnTest、metrics 包
- `DigestUtils` 抽取：把 SHA-256 工具从 `BatchDocumentService` 中抽出
- `OpenApiConfig` 重构：9 个 `if` 块改为表驱动 switch 表达式 + `ExampleDef` 记录

### 修复
- `evaluateAnswerQuality` 超时：`CompletableFuture.orTimeout()` 10 秒并优雅降级
- `batchEmbedDocumentsWithProgress` ClassCastException：`embeddingsStored` 从 Long 转型（countByDocumentId 返回 Long）
- 检索分数 NaN：`ddText` 分数空值安全 `?? 0.0`
- SSE `sendHeartbeat()` 格式：改用 `.comment()` 输出正确的 SSE 注释格式
- SSE `sendError()` 兜底：成功时正常完成，仅在失败时 `completeWithError()`
- `ChatModelRouter` NPE：在 `resolve()` 中为 `providerId` 增加空检查

## [1.0.0-SNAPSHOT] - 2026-04-08

### 新增
- `RetrievalUtils` 新增 `euclideanDistance` + `dotProduct`：pgvector `<#>` 运算符所需的 L2 距离与内积
- `LLM-as-judge` 答案质量评估：`RetrievalEvaluationService.evaluateAnswerQuality()` 用 LLM 给 RAG 答案打分（三个维度：相关性/覆盖度/简洁性）
- `RagRetrievalLogRepositoryTest`：覆盖全部查询方法的 22 个单元测试
- `SearchCapabilitiesTest`：15 个单元测试，覆盖无参/init=false/init=true，以及中文 FTS/英文 FTS/trigram 探测
- `PgEnglishFtsProviderTest`：英文全文检索 provider 的 10 个单元测试
- `SilenceSchedule` CRUD REST API：`SilenceScheduleRequest/Response` DTO、`SilenceAlertRequest`，以及静默窗口内的告警抑制
- `NotificationConfig` 11 个单元测试：钉钉 HMAC-SHA256 + 邮件 SMTP，告警类型过滤
- `Collection clone` 端点：`POST /{id}/clone` 深度复制整个 Collection（新名称加 "(Copy)"）及其全部文档
- 多 Collection 检索：`POST /search` 的 `collectionIds` 参数支持跨 Collection 检索
- WebUI W12：基于 LCS diff 算法的文档版本对比界面
- WebUI W13：基于 Recharts 柱状图 + 显著性检验的 A/B 测试实时看板
- WebUI W14：深色模式自动跟随系统 + 手动开关锁定 + 按 A 恢复自动

### 修复
- `RetrievalUtils` 的 `fuseResults` 产生 NaN：全零向量/全文检索分数现在返回 0.0 而非 NaN
- V17 Flyway：修正表名（`rag_document`→`rag_documents`、`rag_alert`→`rag_alerts`）
- SSE 流式：改用 `split('\n\n')` 正确分隔事件
- `OpenApiContractTest` 上下文加载：解决 53 个测试错误——CorsConfig 需要 RagProperties
- 移除 MiniMax base-url 的 `/v1`（MiniMax API 端点本身已包含 `/v1`）
- WebUI `VersionHistoryModal`：`getVersion` 响应的空值安全

## [1.0.0-SNAPSHOT] - 2026-04-07

### 新增
- `HybridRetrieverService` 语言自适应 FTS：`QueryLang` 枚举（ZH/EN_OR_OTHER）+ 使用 CJK Unicode 区段检测的 `LanguageDetector`
- `SearchCapabilities` 类：用于中文 FTS/英文 FTS/trigram 探测的 `detectLang/getCapabilities/isAvailable`
- `PgEnglishFtsProvider`：使用 `search_vector` tsvector + `websearch_to_tsquery` 的英文全文检索
- `PgJiebaFulltextProvider` 增强：`websearch_to_tsquery('jiebacfg', ?)` + 预建的 `search_vector_zh` GIN 索引
- V15/V16 Flyway 迁移：`search_vector` 列 + GIN 索引（英文 FTS）+ 条件 trigram 索引
- `ApiSloTrackerService` 16 个单元测试：并发延迟记录、按端点达标率、SLO 违约计数
- `AlertController` 新增 14 个 CRUD 测试：SLO 配置与静默计划端点全覆盖
- SSE 流式嵌入进度：`POST /documents/{id}/embed/stream` + `BatchEmbedProgressEvent`
- `ChatHistoryCleanupServiceTest`：6 个测试（TTL 关闭/异常/正常路径/null 截止时间）
- `DocumentEmbedService` 重构：`maybeEmit()` 空值安全回调 + `emitEmbeddingProgress()` 批量进度

### 修复
- SSE 心跳：`sendHeartbeat()` 改用 `.comment()` SSE API 输出正确注释格式
- SSE `sendError()` 兜底：成功时正常完成，仅在失败时 `completeWithError()`
- `RetrievalUtils` NaN：全零输入分数返回 0.0 而非 NaN
- V17 迁移：修正表名（`rag_document`→`rag_documents`、`rag_alert`→`rag_alerts`）
- `ComponentHealthService` NPE：为 `ChatModelRouter` 的 `getModelForRequest()` 增加空检查

### 变更
- 全部 13 个 controller：Swagger/OpenAPI 注解 100% 英文（@Tag/@Operation/@ApiResponse/@Parameter 描述）
- 全部 API DTO：@Schema 描述翻译为英文（35 个文件）
- Service 接口：Javadoc 翻译为英文（RetrievalEvaluationService、UserFeedbackService、DocumentVersionService）

## [1.0.0-SNAPSHOT] - 2026-04-06

### 新增
- `EmailNotificationService`：基于 JavaMailSender 的 SMTP 告警投递（可选依赖），重试 3 次
- `DingTalkNotificationService` 韧性：修正 escapeJson 顺序 + HTTP 指数退避重试
- `C40` `@Indexed` 注解评审：V13 补齐缺失的索引（rag_collection(name)、rag_documents(document_type)、rag_documents(enabled)）
- `C24` HikariCP 慢查询监控：`RagSlowQueryProperties` + `SlowQueryMetricsService` + `GET /api/v1/rag/metrics/slow-queries`
- `C41` Advisor 链 Micrometer：8 个 meter（`rag.advisor.{step}.duration/count/results/skipped`）覆盖 QueryRewrite/HybridSearch/Rerank
- `C26` SpringDoc 片段 + 示例响应：`OpenApiConfig.exampleResponseCustomizer()` 覆盖 9 个端点
- `C16` pgvector HNSW 与 IVFFlat 对比指南：`docs/pgvector-index-comparison.md`，含算法对比 + 迁移 SQL
- `C38` HikariCP 生产调优：`validation-timeout=5000ms`、`initialization-fail-timeout=10000ms`、`register-mbeans=true`、预编译语句缓存
- `C20` Dockerfile 优化：多阶段（Maven→jlink→distroless），eclipse-temurin:17-jre，非 root 用户，<200MB
- `C39` Mock LLM Server：`scripts/mock-llm-server.js`——OpenAI 兼容的 `/v1/chat/completions` + `/v1/embeddings`，可配置延迟/错误率

### 修复
- `SpringAiConfig` OpenAiApi builder：移除不存在的 `.proxy()` 方法调用
- OpenApiContractTest：修复 53 个测试上下文加载失败——用 `@MockBean RagClientErrorRepository` 解决
- `@WebMvcTest` 中的 CorsConfig：RagControllerIntegrationTest 改用静态 `@TestConfiguration` 提供真实 `RagProperties`
- MiniMax base-url 的 `/v1`：移除（MiniMax 端点本身已包含 `/v1`）
- SiliconFlow embedding base-url 的 `/v1/embeddings`：移除（OpenAiApi 会自动追加 `/v1/embeddings`）

### 变更
- 抽出 `SseEmitters` 为工具类：`sendProgress/sendDone/sendError/sendHeartbeat/completeWithError`
- SSE 流式：采用 OpenAI 兼容的 `data:{"choices":[{"delta":{"content":"..."}}]}` 格式并做 JSON 转义
- Vite 开发代理：`/api` → `http://localhost:8081`（只开发前端）

## [1.0.0-SNAPSHOT] - 2026-04-05 (Evening)

### 新增
- `POST /cache/invalidate`：用于清空 Caffeine embedding 缓存的管理端点
- `GET /metrics/slow-queries`：HikariCP 慢查询统计 REST 端点
- `GET /metrics/slo`：API SLO 达标率（按端点的 p50/p95/p99）
- `POST /client-errors`：WebUI 错误边界上报客户端错误
- `GET /client-errors/count`：客户端错误计数端点
- `ChatExportService` + `GET /chat/export/{sessionId}`：把会话历史导出为 JSON/Markdown
- `BatchCreateResponse` DTO：统一的批量创建响应类型
- 演示 E2E 脚本：`demo-basic-rag-e2e.sh`（8082）/ `demo-multi-model-e2e.sh`（8083）/ `demo-component-level-e2e.sh`（8084）/ `demo-domain-extension-e2e.sh`（8085）

### 修复
- `AlertServiceImpl` bean 歧义：`@Autowired List<NotificationService>` 通过 `@Qualifier` 消歧
- `ChatRequest.model` 字段：改为可空（多模型路由中 model 是可选的）
- `.env` 变量：为 Maven 子进程的环境变量继承补上 `export` 前缀
- Spring Boot 3.5：`springdoc 2.6.0` → `2.8.4` 以兼容 Spring Boot 3.5.3

### 变更
- 全部 API DTO 的 `@Schema` 描述：仅英文（35 个文件）
- 8 个 DTO 校验消息：仅英文（30+ 条约束消息）
- Controller 的 @Operation/@ApiResponse：仅英文（13 个 controller，100%）
- Service Javadoc：仅英文（7 个 service 接口/实现）

## [1.0.0-SNAPSHOT] - 2026-04-05

### Added
- `ChatHistoryCleanupService`：`messageTtlDays` 配置（默认30天）+ 每日凌晨3点自动清理过期聊天历史记录（cron 可配置）
- `ChatHistoryCleanupServiceTest`：6 个测试覆盖 TTL 禁用/异常/正常路径/null cutoff
- `rag.memory.message-ttl-days` / `rag.memory.cleanup-cron` 配置项（`docs/configuration.md` 同步更新）

## [1.0.0-SNAPSHOT] - 2026-04-04 (Afternoon)

### Added
- `CircuitBreakerHealthIndicator`：`/actuator/health/llmCircuitBreaker` 端点，CLOSED/HALF_OPEN=UP，OPEN=DOWN，NOT_CONFIGURED=UNKNOWN
- `POST /models/compare` 模型对比端点 + `ModelMetricsService` + `rest-api.md` 文档补充

#### Multi-Model
- demo-multi-model：`MultiModelController` + `MiniMaxAdapter` + `OpenAiCompatibleAdapter`（支持 system 消息/不支持自动转换）
- `ApiCompatibilityAdapter` 接口：`supportsSystemMessage()` + `normalizeMessages()` 默认方法
- `ChatModelRouter.getModelForRequest()`：请求级动态模型选择 + FallbackChain

### Fixed
- `RetrievalConfig`：`vectorWeight`/`fulltextWeight` 添加 `@DecimalMin(0.0)` / `@DecimalMax(1.0)` 验证
- `RagSearchController` GET `/search`：权重越界 [0.0, 1.0] 返回 400 + error+received
- 删除遗留 `TestController.java` + revert application.yml 硬编码 API key（安全修复）

## [1.0.0-SNAPSHOT] - 2026-04-04

### Added

#### Testing
- `DomainExtensionPipelineIntegrationTest`：22 个测试覆盖领域扩展 Pipeline 全流程（Registry 查找/跳过/默认行为、医疗领域症状识别/高召回配置、法律领域多扩展共存）
- `RagSearchControllerBenchmarkTest`：100 并发搜索请求验证 + 50 并发 <1s 吞吐量基准测试

#### E2E & Demo
- E2E 脚本扩展：Collection CRUD 测试（创建/列表/详情/更新/删除/文档关联）+ Cache stats + Metrics overview，共 14 项端到端验证
- demo-domain-extension `MedicalRagControllerTest` 修复 Java 24 严格类型推断导致的 Mockito 重载解析问题

#### Resilience
- LLM 熔断器基础设施（`LlmCircuitBreaker` 三状态自动机：CLOSED→OPEN→HALF_OPEN）+ `LlmCircuitOpenException`（503）
- `RagChatService` 集成熔断器：失败率阈值触发熔断、冷却后半开探测、成功率恢复

### Fixed

- `DocumentEmbedService.embedDocumentWithProgress` NPE：`maybeEmit()` null-safe 回调工具方法 + 缓存命中时 `chunks==null` 修复
- MiniMax API 不兼容 `role:system`：`ApiCompatibilityAdapter.supportsSystemMessage()` + `normalizeMessages()` 自动将 system 消息转为 user 消息

### Changed

- 主动巡检轮次（4/4 深夜）：catch 注释规范化（Health probe/Resilience/best-effort 三类）
- 领域扩展 Registry 命名一致性与 Registry 测试路径修复

## [1.0.0-SNAPSHOT] - 2026-04-03

### Added

#### Core RAG
- 混合检索（pgvector 向量 + PostgreSQL 全文检索融合）
- 全文检索策略可配置化（auto/pg_jieba/pg_trgm/none，支持 pg_jieba 中文分词）
- 查询改写（规则模式 + LLM 辅助模式）
- 重排序服务（Cross-Encoder 模式）
- Advisor 链式 Pipeline（QueryRewrite → HybridSearch → Rerank → ChatMemory）
- 内容哈希嵌入缓存（避免重复嵌入未变更文档，Caffeine L1 缓存）
- 文档版本历史（content_hash 变更自动记录）

#### 模型支持
- OpenAI 兼容模型（DeepSeek、智谱等）
- Anthropic 模型
- 三 Bean 模式自动切换（`app.llm.provider` 配置）
- 多模型并行对比服务
- 硅基流动 BGE-M3 嵌入模型（1024 维）

#### 领域扩展
- `DomainRagExtension` 接口（领域 Prompt + 检索配置 + 答案后处理）
- `PromptCustomizer` 链式定制
- `DomainExtensionRegistry` 自动注册

#### REST API
- RAG 问答（非流式 + SSE 流式）
- 文档管理（CRUD + 嵌入 + 批量操作）
- 知识库集合管理（含导出/导入）
- 检索评估 + 用户反馈
- A/B 实验框架
- 监控告警 + SLO
- 健康检查
- 缓存统计端点（GET /api/v1/rag/cache/stats）
- API Key 认证过滤器
- API 限流（滑动窗口 + per-user 分级限额，429 + Retry-After）
- API 版本管理（@ApiVersion 注解，支持 /api/v1/ + /api/v2/ 共存）
- RFC 7807 Problem Detail 错误响应格式
- 请求验证增强（@Valid + ConstraintViolationException 统一处理）

#### 可观测性
- Micrometer 指标（检索延迟、Token 用量、命中率）
- Actuator 健康检查
- 检索日志 + 性能基准测试
- 请求追踪（RequestTraceFilter + MDC traceId + logback 格式化）
- 分布式追踪增强（可配置采样率 + W3C traceparent 格式 + spanId 嵌套追踪）
- 异常处理统一（窄化具体异常类型）

#### 基础设施
- Flyway 数据库迁移（V1-V10，含 pg_trgm/pg_jieba 索引）
- HikariCP 连接池优化
- 异步处理配置
- 响应缓存（Caffeine L1，可配置 TTL/LRU）
- CORS 安全配置
- 国际化框架（MessageSource + 中/英双语错误消息）
- Docker 支持（多阶段构建 + docker-compose）
- GitHub Actions CI（PostgreSQL 服务 + JaCoCo 覆盖率上报）

#### 文档
- README.md（项目门面）
- CONTRIBUTING.md（贡献指南）
- docs/architecture.md（架构设计详解）
- docs/configuration.md（完整配置参考，含限流/CORS/缓存/追踪配置）
- docs/testing-guide.md（测试指南）
- docs/getting-started.md（开发者上手）
- docs/rest-api.md（REST API 参考，含 RFC 7807 + 缓存统计端点）
- docs/extension-guide.md（领域扩展指南）
- docs/troubleshooting.md（故障排查）
- docs/postgresql-extensions.md（PostgreSQL 扩展依赖分析）

#### 测试
- 890 个单元/集成测试
- JaCoCo 覆盖率集成（>90% 指令覆盖）
- E2E 测试脚本
- 性能基准测试（单次检索 <500ms）
- SSE 流式 E2E 测试 + 对话记忆多轮验证

### Technical Stack

| 组件 | 版本 |
|------|------|
| Java | 17+ |
| Spring Boot | 3.4.x |
| Spring AI | 1.1.2 |
| PostgreSQL + pgvector | 15+ / 0.7.x |
| Maven | 3.9+ |

---

## [1.1.0-SNAPSHOT] - 2026-04-03 Evening

### Added
- SSE 流式嵌入进度端点 `POST /documents/{id}/embed/stream`（实时推送 PREPARING→CHUNKING→EMBEDDING→STORING→COMPLETED）
- RAG 指标 REST 端点 `GET /api/v1/rag/metrics`（totalRequests/successRate/tokens 等关键指标）
- Demo E2E Shell 脚本 `scripts/demo-e2e.sh`（启动+健康等待+10项验证+彩色输出）
- MiniMax API 兼容性修复：role:system 自动转为 user 消息（防止脏数据报错）

### Fixed
- SpringAiConfig 缺少 @EnableConfigurationProperties 导致 RagProperties 无法注入
- demo-component-level @ComponentHealthService 缺少 @Service 注解
- pom.xml GraalVM profile 注释中的 `--` 导致 XML 解析错误
- .env 变量缺少 export 前缀导致 Maven subprocess 无法继承环境变量

## [1.1.0-SNAPSHOT] - 2026-04-04 Early Morning

### Added
- `LlmCircuitBreaker` + `LlmCircuitOpenException`（LLM 熔断器基础设施）
- `RagSearchControllerBenchmarkTest`（100 并发/50 并发吞吐量验证）
- E2E 脚本扩展至 14 项（Collection CRUD + cache/stats + metrics/overview）
- `DomainExtensionPipelineIntegrationTest`（22 个测试，DomainExtensionRegistry + 医疗/法律领域扩展管道验证）
- API 版本共存（@ApiVersion 注解支持 /api/v1/ + /api/v2/ 同时存在）
