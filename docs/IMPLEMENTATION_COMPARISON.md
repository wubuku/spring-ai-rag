# 实现对比分析文档

> **当前项目**: spring-ai-rag  
> **参考项目**: spring-ai-skills-demo, MaxKB4j, dermai-rag-service  
> **创建时间**: 2026-04-01  
> **最后更新**: 2026-05-06 06:22 — 文档对齐：gap 表格中 20+ 项已实现但标注未完成，本次更正为 ✅；源文件 280 / 测试文件 203；项目处于生产级成熟状态

---

## 🎉 完成状态总览

| 阶段 | 内容 | 状态 |
|------|------|------|
| Phase 1 | 基础框架（ChatModel/EmbeddingModel/PgVectorStore） | ✅ 完成 |
| Phase 2 | 核心 RAG 组件（混合检索/查询改写/重排/分块） | ✅ 完成 |
| Phase 3 | RAG Pipeline + REST API + 对话记忆 | ✅ 完成 |
| Phase 4 | 领域扩展示例（DomainRagExtension + demo） | ✅ 完成 |
| Phase 5 | 运维支持（监控/健康检查/告警/性能） | ✅ 完成 |

**已完成改进项**: P1 × 7 + P2 × 15 + P3 × 2 = 全部 24 项 + 持续改进周期

---

---

## 1. 数据库/JPA 层

### 当前实现
- **实体**: 4 个 JPA 实体（RagDocument, RagEmbedding, RagChatHistory, RagCollection）
- **JSONB**: `@JdbcTypeCode(SqlTypes.JSON)` + `Map<String, Object>`（参考 dermai-rag-service）
- **VECTOR**: `@JdbcTypeCode(SqlTypes.VECTOR)` + `@Array(length=1024)` + `float[]`
- **hibernate-vector**: ✅ 已添加（6.6.5.Final）
- **ddl-auto**: `none`（Flyway 管理）
- **open-in-view**: `false`
- **Repository**: Spring Data JPA 接口，派生方法 + JPQL

### 参考实现
- **dermai-rag-service**: 同样用 `@JdbcTypeCode(SqlTypes.JSON)` 和 `@JdbcTypeCode(SqlTypes.VECTOR)`，有 `hibernate-vector` 依赖，`ddl-auto: validate`，`open-in-view: false`
- **spring-ai-skills-demo**: 用 Spring AI 的 PgVectorStore（auto-config），不直接定义实体

### 差距
| 差距 | 严重度 |
|------|--------|
| （已修复）`RagEmbedding` 和 `RagDocument` 的 `@Table(indexes=...)` 注解已补全，与 Flyway 索引保持一致 | — |
| （已修复）`RagDocument` 的 `@Column` 注解已完整 | — |

---

## 2. 模型调用层

### 当前实现
- 使用 Spring AI `OpenAiChatModel` + `OpenAiEmbeddingModel`
- 通过 `OpenAiApi.builder().baseUrl(...).apiKey(...).build()` 构建
- system 消息由 Spring AI ChatClient 管理
- RerankAdvisor 改用 `augmentUserMessage()`（兼容 MiniMax 不支持多 system 消息）
- 重试：Spring AI 内置 `RetryTemplate`（5 次重试）

### 参考实现
- **dermai-rag-service**: 用 RestTemplate 裸 HTTP 调用，手动构造 JSON 请求体，只有一个 system 消息
- **spring-ai-skills-demo**: 用 Spring AI OpenAiChatModel，和我们一样

### 差距
| 差距 | 严重度 |
|------|--------|
| （已修复）API 兼容性适配层已实现（ApiCompatibilityAdapter）——自动检测多 system 消息支持，不支持时转换为 user 消息 | P1 ✅ |
| （已修复）MiniMax Adapter system 消息兼容已实现（MiniMaxAdapter.supportsSystemMessage()=false） | P1 ✅ |
| （已修复）SSE 流式响应已实现（SseEmitter + 心跳机制 + SseStreamE2ETest） | P2 ✅ |

---

## 3. RAG Pipeline

### 当前实现
- Advisor 链模式：QueryRewriteAdvisor(+10) → HybridSearchAdvisor(+20) → RerankAdvisor(+30) → MessageChatMemoryAdvisor
- HybridSearchAdvisor 通过 context attributes 传递检索结果给 RerankAdvisor
- RerankAdvisor 通过 `augmentUserMessage()` 注入上下文

### 参考实现
- **MaxKB4j**: PipelineManage + AbsStep 模式，步骤可编排、可扩展，每个步骤有独立的 context
- **dermai-rag-service**: 串行调用各个 Service（QueryRewritingService → HybridRetrieverService → ReRankingService），无 Pipeline 抽象
- **spring-ai-skills-demo**: 用 Spring AI 的 `QuestionAnswerAdvisor` 直接注入上下文（不做混合检索和重排）

### 差距
| 差距 | 严重度 |
|------|--------|
| （已修复）Advisor 链 Pipeline 可观测性已实现（RagPipelineMetrics + 每步耗时/结果数量） | P2 ✅ |
| （已修复）查询改写同义词/限定词/Padding 已实现（QueryRewritingService） | P1 ✅ |

---

## 4. 监控运维

### 当前实现
- RagMetricsService: Micrometer Timer/Counter/Gauge（请求成功率、响应时间、LLM tokens）
- RagHealthIndicator: Actuator /actuator/health（检查 DB 连接 + 表数据）
- Caffeine 缓存（嵌入向量缓存、检索结果缓存）

### 参考实现
- **dermai-rag-service**: 
  - A/B 实验框架（AbTestService）——对比不同检索策略效果
  - 检索质量评估（RetrievalEvaluationService）——用户反馈收集
  - 告警系统（AlertService）——阈值告警
  - 指标趋势（RagMetricTrend）——历史趋势分析
  - 监控专用表（rag_retrieval_logs, rag_ab_results, rag_alerts）

### 差距
| 差距 | 严重度 |
|------|--------|
| （已修复）检索日志已实现（RetrievalLoggingService + V3 迁移） | P1 ✅ |
| （已修复）A/B 实验框架已实现（AbTestService + AbTestController + V6 迁移） | P2 ✅ |
| （已修复）告警系统已实现（AlertService + AlertController + V7 迁移 + 静默管理） | P2 ✅ |
| （已修复）用户反馈收集已实现（UserFeedbackService + EvaluationController） | P2 ✅ |

---

| 优先级 | 改进项 | 参考来源 | 文件 |
|--------|--------|---------|------|
| P1 | API 兼容性适配层（多 system 消息检测） | dermai-rag-service ApiClientService | 新增 `adapter/` 包 |
| P1 | 查询改写增加同义词/限定词 | dermai-rag-service QueryRewritingService | `retrieval/QueryRewritingService.java` |
| P1 | 添加检索日志表 | dermai-rag-service V3 迁移脚本 | `db/migration/V3__add_retrieval_logs.sql` |
| P2 | 实体添加 @Table(indexes) 注解 | dermai-rag-service 实体 | 所有实体类 |
| P2 | Pipeline 可观测性 | MaxKB4j AbsStep | `advisor/` 各 Advisor |
| P2 | A/B 实验框架 | dermai-rag-service AbTestService | 新增 `service/AbTestService.java` + `controller/AbTestController.java` |
| P2 | 用户反馈端点 | dermai-rag-service | 新增 `controller/FeedbackController.java` |
| P2 | 检索质量评估（RetrievalEvaluationService） | dermai-rag-service | 新增 `service/RetrievalEvaluationService.java` |
---

## 5. 文档处理

### 当前实现
- **分块**: HierarchicalTextChunker（从 dermai-rag-service 迁移），支持 Markdown 标题/段落/句子三级分块
- **清洗**: TextCleaner（从 dermai-rag-service 迁移）
- **嵌入**: EmbeddingBatchService，按 batchSize 分批调用 EmbeddingModel
- **存储**: 通过 JdbcTemplate INSERT 到 rag_embeddings（vector 列用 `?::vector` 转换）

### 参考实现
- **dermai-rag-service**: 同样的 HierarchicalTextChunker + TextCleaner + EmbeddingBatchService
- **spring-ai-skills-demo**: 直接用 Spring AI 的 `VectorStore.add(List<Document>)` 自动处理嵌入和存储

### 差距
| 差距 | 严重度 |
|------|--------|
| （已修复）文档内容哈希去重已实现（HashDeduplicationService + SHA-256 content_hash） | P2 ✅ |
| （已修复）进度回调 SSE 流已实现（PdfImportController + SseEmitter 实时进度） | P2 ✅ |
| （保留）手动 JdbcTemplate INSERT 被保留——因需要精确控制向量维度和批量大小，PgVectorStore.add() 灵活性不足 | — |

---

## 6. 配置管理

### 当前实现
- **LLM**: `application.yml` 中 `spring.ai.openai.*` / `spring.ai.anthropic.*`，通过 `app.llm.provider` 切换
- **嵌入**: `siliconflow.*` 自定义前缀
- **数据库**: `spring.datasource.*`，环境变量 `${POSTGRES_*}`
- **业务配置**: `rag.*` 自定义前缀（retrieval/chunk/memory）
- **Starter**: `GeneralRagProperties` + `@ConfigurationProperties(prefix="general.rag")`

### 参考实现
- **dermai-rag-service**: `@ConfigurationProperties(prefix="rag.alert.notification")` 用于告警配置，`@Value("${rag.async.*}")` 用于线程池配置，`@Value("${rag.security.api-key}")` 用于 API 认证
- **spring-ai-skills-demo**: 用 `siliconflow.*` 前缀 + `@Value` 注入，和我们类似

### 差距
| 差距 | 严重度 |
|------|--------|
| （已修复）RagProperties ConfigurationProperties 已实现（统一管理 rag.* 配置） | P1 ✅ |
| （已修复）API Key 认证已实现（ApiKeyAuthFilter + 数据库存储 + RBAC 角色） | P2 ✅ |
| （已修复）NotificationProperties 已实现（NotificationConfig + DingTalk/Email 配置） | P2 ✅ |

---

## 7. 错误处理

### 当前实现
- **GlobalExceptionHandler**: 处理 6 种异常（400/404/405/500）
- **Controller**: 用 `ResponseEntity` 返回错误
- **Service**: try-catch + log.error

### 参考实现
- **dermai-rag-service**: GlobalExceptionHandler + `CustomAsyncExceptionHandler`（异步异常处理）
- 具体异常类型：`InvalidRequestException`, `ResourceNotFoundException`, `EmbeddingGenerationException`

### 差距
| 差距 | 严重度 |
|------|--------|
| （已修复）业务自定义异常类已实现（LlmCircuitOpenException + GlobalExceptionHandler 统一处理） | P1 ✅ |
| （已修复）异步异常处理已实现（CustomAsyncExceptionHandler + AsyncConfig） | P1 ✅ |
| （已修复）统一错误响应格式已实现（ErrorResponse DTO：code+message+path+timestamp） | P2 ✅ |

---

## 8. API 设计

### 当前实现
- 路径：`/api/v1/rag/*`（带版本号）
- 端点：chat/ask, chat/stream, chat/history, documents CRUD, documents/{id}/embed, search, health
- 文档：SpringDoc OpenAPI (Swagger UI)

### 参考实现
- **dermai-rag-service**: 路径 `/api/rag/*`（无版本号），端点更多（alerts, evaluations, ab-experiments）
- **spring-ai-skills-demo**: 路径 `/api/agent/*`，更简单

### 差距
| 差距 | 严重度 |
|------|--------|
| （已修复）用户反馈端点已实现（EvaluationController + POST /feedback） | P2 ✅ |
| （已修复）检索质量评估端点已实现（EvaluationController + GET /evaluations + Precision@K/MRR） | P2 ✅ |
| （已修复）文档批量操作端点已实现（BatchDocumentService + POST/DELETE /documents/batch） | P2 ✅ |

---

## 完整改进待办清单

| 优先级 | 改进项 | 参考来源 | 文件 | 状态 |
|--------|--------|---------|------|------|
| P1 | API 兼容性适配层（多 system 消息） | dermai-rag-service ApiClientService | `config/ChatModelConfig.java` | ✅ @ConditionalOnMissingBean 双 Bean |
| P1 | 查询改写增加同义词/限定词 | dermai-rag-service QueryRewritingService | `retrieval/QueryRewritingService.java` | ✅ 同义词+领域限定+Padding |
| P1 | 添加检索日志表 | dermai-rag-service V3 | `db/migration/V7__add_collection.sql` | ✅ rag_retrieval_logs 已迁移 |
| P1 | 用 VectorStore.add() 简化嵌入存储 | spring-ai-skills-demo | `controller/RagDocumentController.java` | ✅ /embed/vs 端点 |
| P1 | 创建 RagProperties 统一配置类 | dermai-rag-service | `config/RagProperties.java` | ✅ @ConfigurationProperties |
| P1 | 创建业务异常类 | dermai-rag-service | `exception/` 包 | ✅ 3 个自定义异常 |
| P1 | 异步异常处理 | dermai-rag-service AsyncConfig | `config/AsyncConfig.java` | ✅ CustomAsyncExceptionHandler |
| P2 | 实体 @Table(indexes) 注解 | dermai-rag-service | 所有实体类 | ✅ 7 实体已补注解 |
| P2 | Pipeline 可观测性 | MaxKB4j AbsStep | advisor/ 各 Advisor | ✅ MetricsService + Timer |
| P2 | A/B 实验框架 | dermai-rag-service | `service/AbTestService.java` | ✅ 接口+实现+Controller |
| P2 | 用户反馈端点 | dermai-rag-service | `controller/FeedbackController.java` | ✅ POST /feedback |
| P2 | 检索质量评估 | dermai-rag-service | `service/RetrievalEvaluationService.java` | ✅ Precision@K/MRR |
| P2 | API Key 认证 | dermai-rag-service ApiKeyAuthFilter | `filter/ApiKeyAuthFilter.java` | ✅ X-API-Key 过滤器 |
| P2 | 统一错误响应格式 | dermai-rag-service | `dto/ErrorResponse.java` | ✅ code+message+details |
| P2 | 文档内容哈希去重 | dermai-rag-service | `controller/RagDocumentController.java` | ✅ SHA-256 去重 |
| P2 | 文档批量操作端点 | — | `controller/RagDocumentController.java` | ✅ batch create/delete/embed |
| P2 | JaCoCo 覆盖率集成 | — | 所有模块 pom.xml | ✅ 87.4% 指令/74.8% 分支 |
| P3 | SpringDoc OpenAPI 文档 | — | OpenApiConfig + Controllers | ✅ 全局配置+@ApiResponse |

---


---

## P2 改进项详细实施方案（已全部完成）

> 以下所有 P2 项均已实现完成。保留作为历史记录和实现参考。

### P2-1: 实体 @Table(indexes) 注解
**目的**：JPA 实体与数据库索引保持一致。
**参考**：dermai-rag-service RagDocument.java 的 `@Table(indexes={...})`
**方案**：修改 4 个实体类，添加索引注解。ddl-auto: none 时不自动创建索引，仅用于文档。

### P2-2: Pipeline 可观测性
**目的**：记录 RAG Pipeline 每步耗时和结果数量。
**参考**：MaxKB4j AbsStep.java
**方案**：在 3 个 Advisor 的 before() 中记录开始时间，结束时存入 request context。

### P2-3: A/B 实验框架
**目的**：对比不同检索策略效果。
**参考**：dermai-rag-service AbTestService（实验定义、流量分配 Map<String,Double>、结果收集）
**方案**：新增 AbTestService 接口+实现+Controller，新增 rag_ab_experiments/rag_ab_results 表。

### P2-4: 用户反馈端点
**目的**：收集用户对回答质量评分。
**方案**：新增 FeedbackController + POST /api/v1/rag/feedback（sessionId/query/answer/rating/comment），新增 rag_feedback 表。

### P2-5: 检索质量评估
**目的**：计算 Precision@K、Recall@K、MRR。
**参考**：dermai-rag-service RetrievalEvaluationService（EvaluationCase + 指标计算）
**方案**：新增 RetrievalEvaluationService，可与 A/B 实验结合对比策略效果。

### P2-6: API Key 认证
**目的**：防止未授权访问。
**参考**：dermai-rag-service ApiKeyAuthFilter（OncePerRequestFilter + X-API-Key 请求头）
**方案**：新增 ApiKeyAuthFilter，@Value 配置 api-key，健康检查白名单放行。

### P2-7: 统一错误响应格式
**目的**：所有 API 错误返回统一格式。
**方案**：新增 ErrorResponse DTO（code+message+details），修改 GlobalExceptionHandler 统一返回。

### P2-8: 文档内容哈希去重
**目的**：避免重复上传相同内容。
**方案**：createDocument 时 SHA-256(content)，查是否存在相同 hash。content_hash 字段和索引已存在，只需加业务逻辑。

### P2-9: 文档批量操作
**目的**：支持批量上传/删除。
**方案**：新增 POST/DELETE /api/v1/rag/documents/batch。

## 文档审查记录

| 轮次 | 审查内容 | 发现问题 | 修正 |
|------|---------|---------|------|
| 1 | 代码依据验证 | RagEmbedding chunk_index 断言错误（实际已存在） | 移除错误断言 |
| 1 | 代码依据验证 | MaxKB4j AbsStep 路径不正确 | 修正为正确路径 |
| 2 | 改进建议可执行性 | 部分 P2 项只写"新增"无具体文件 | 补充具体文件名 |
| 3 | 差异遗漏检查 | 缺少 RetrievalEvaluationService 对比 | 添加到监控章节 |
| 4 | 文档结构 | 两个待办清单导致重复 | 合并为一个 |

**审查通过**：经过 4 轮审查，修正 5 个问题，文档内容准确、结构清晰。

---

## 🚀 Phase 6 进展

Phase 1-5 + 改进周期全部完成后，Phase 6 建议已推进：

| 优先级 | 改进项 | 状态 |
|--------|--------|------|
| P1 | RagDocumentController 重构 | ✅ 668→294 行（-56%），拆分 DocumentEmbedService + BatchDocumentService |
| P2 | 多模型并行对比测试 | ✅ ModelComparisonService + 8 测试 |
| P2 | 查询改写 LLM 辅助模式 | ✅ llmRewrite() 规则+LLM 混合模式 |
| P2 | 检索结果缓存策略优化 | ✅ #72 Caffeine L1 缓存 + rag.cache.* 配置外部化 |
| P3 | Docker Compose 一键部署 | ✅ #36 多阶段 Dockerfile + docker-compose.yml + 非 root 用户 |
| P3 | API 版本管理策略 | ✅ #70 @ApiVersion 注解 + ApiVersionRequestMappingHandlerMapping |
| P3 | 国际化支持 | ✅ #71 MessageSource + messages.properties/en/zh_CN + ConstraintViolationException |

## 🚀 Phase 7 进展（多模型支持）

多 Provider 统一接入：OpenAI/DeepSeek + Anthropic + MiniMax，模型无关架构：

| 优先级 | 改进项 | 状态 |
|--------|--------|------|
| P1 | MiniMax ChatModel 支持 | ✅ Spring AI 1.1.2→1.1.4，spring-ai-starter-model-minimax，miniMaxChatModel Bean，.env.example 配置 |
| P1 | ModelRegistry 模型注册中心 | ✅ 自动收集 openai/anthropic/minimax Bean，统一访问接口，10 个单元测试 |
| P2 | ChatModelRouter 请求级动态路由 | ✅ FallbackChain 降级链，9 个单元测试 |
| P2 | 模型对比 REST 端点 | ✅ GET /api/v1/rag/models、/models/{provider}、POST /models/compare |
| P2 | 模型级指标体系 | ✅ ModelMetricsService（Micrometer），GET /api/v1/rag/metrics/models |
| P2 | ModelComparisonService 与 ModelRegistry 对接 | ✅ compareProviders/compareAllProviders 并行多模型查询 |
| P3 | demo-multi-model 集成测试 | ✅ 9 个单元测试覆盖 list/get/compare/chatWithProvider |
| P3 | CircuitBreaker LLM 熔断器 | ✅ LlmCircuitBreaker + CircuitBreakerHealthIndicator，CLOSED/HALF_OPEN/OPEN 状态机 |
| P3 | MiniMax Adapter system 消息兼容 | ✅ MiniMaxAdapter.supportsSystemMessage()=false，system→user 自动转换 |
