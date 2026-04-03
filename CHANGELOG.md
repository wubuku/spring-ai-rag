# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

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
