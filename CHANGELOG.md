# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [1.0.0-SNAPSHOT] - 2026-04-02

### Added

#### Core RAG
- 混合检索（pgvector 向量 + PostgreSQL 全文检索融合）
- 查询改写（规则模式 + LLM 辅助模式）
- 重排序服务（Cross-Encoder 模式）
- Advisor 链式 Pipeline（QueryRewrite → HybridSearch → Rerank → ChatMemory）

#### 模型支持
- OpenAI 兼容模型（DeepSeek、智谱等）
- Anthropic 模型
- 三 Bean 模式自动切换（`app.llm.provider` 配置）
- 硅基流动 BGE-M3 嵌入模型（1024 维）

#### 领域扩展
- `DomainRagExtension` 接口（领域 Prompt + 检索配置 + 答案后处理）
- `PromptCustomizer` 链式定制
- `DomainExtensionRegistry` 自动注册

#### REST API
- RAG 问答（非流式 + SSE 流式）
- 文档管理（CRUD + 嵌入 + 批量操作）
- 知识库集合管理
- 检索评估 + 用户反馈
- A/B 实验框架
- 监控告警 + SLO
- 健康检查
- API Key 认证过滤器

#### 可观测性
- Micrometer 指标（检索延迟、Token 用量、命中率）
- Actuator 健康检查
- 检索日志 + 性能基准测试

#### 基础设施
- Flyway 数据库迁移
- HikariCP 连接池优化
- 异步处理配置
- 响应缓存

#### 文档
- README.md（项目门面）
- CONTRIBUTING.md（贡献指南）
- docs/architecture.md（架构设计详解）
- docs/configuration.md（完整配置参考）
- docs/testing-guide.md（测试指南）
- docs/getting-started.md（开发者上手）
- docs/rest-api.md（REST API 参考）
- docs/extension-guide.md（领域扩展指南）
- docs/troubleshooting.md（故障排查）

#### 测试
- 712 个单元/集成测试
- JaCoCo 覆盖率集成（>90% 指令覆盖）
- E2E 测试脚本
- 性能基准测试（单次检索 <500ms）

### Technical Stack

| 组件 | 版本 |
|------|------|
| Java | 17+ |
| Spring Boot | 3.4.x |
| Spring AI | 1.1.2 |
| PostgreSQL + pgvector | 15+ / 0.7.x |
| Maven | 3.9+ |
