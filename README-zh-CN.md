# spring-ai-rag

📖 [English](README.md) · 📖 [中文](README-zh-CN.md)

---

基于 [Spring AI](https://docs.spring.io/spring-ai/reference/) 的通用 RAG（检索增强生成）服务框架。

**模型无关 · 领域解耦 · 组件化**

## 为什么选 spring-ai-rag？

| 痛点 | spring-ai-rag 方案 |
|------|-------------------|
| 换模型要改代码？ | 切换 LLM 只改配置，OpenAI/DeepSeek/Anthropic/智谱全兼容 |
| RAG 效果差？ | 混合检索（向量+全文）+ 查询改写 + 重排序，逐层优化 |
| 只能用一个领域？ | `DomainRagExtension` 接口，一个服务支撑 N 个垂直领域 |
| 组件太重？ | 每个 Advisor/Service 可独立引入，不捆绑 |

## 功能特性

- **混合检索**：向量检索（pgvector HNSW）+ 全文检索（pg_jieba 分词 / pg_trgm 三元组）
- **全文检索策略**：可配置 `auto`（自动检测）/ `pg_jieba` / `pg_trgm` / `none`
- **Advisor 链式 Pipeline**：查询改写 → 混合检索 → 重排序 → 上下文注入
- **多模型支持**：OpenAI 兼容 + Anthropic，Provider 一行配置切换
- **领域扩展**：实现 `DomainRagExtension` 注入领域 Prompt 和检索策略
- **SSE 流式输出**：Server-Send Events 实时响应
- **A/B 实验框架**：多模型并行对比，自动收集延迟/token/质量指标
- **检索评估**：Precision@K / MRR / NDCG 评估 + 用户反馈闭环
- **缓存策略**：嵌入结果缓存 + Caffeine 本地缓存，配置可外部化
- **监控可观测**：Micrometer 指标 + Actuator 健康检查 + 请求追踪（traceId）
- **API Key 认证**：内建安全过滤器 + per-user 限流（滑动窗口）
- **API 版本管理**：`@ApiVersion` 注解支持 `/api/v1/` 路径自动映射
- **PDF 导入与预览**：使用 marker CLI 将 PDF 转换为 Markdown + 图片，支持浏览器预览（`<base>` 标签解决图片路径）

## 快速开始

### 1. 数据库

```bash
createdb spring_ai_rag_dev
psql spring_ai_rag_dev -c "CREATE EXTENSION IF NOT EXISTS vector;"
psql spring_ai_rag_dev -c "CREATE EXTENSION IF NOT EXISTS pg_trgm;"
# 可选（中文分词，效果优于 pg_trgm）：
# psql spring_ai_rag_dev -c "CREATE EXTENSION IF NOT EXISTS pg_jieba;"
```

应用启动时 Flyway 自动执行 V1-V10 迁移（建表 + HNSW 索引 + 全文检索 GIN 索引）。

### 2. 添加依赖

```xml
<dependency>
    <groupId>com.springairag</groupId>
    <artifactId>spring-ai-rag-starter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### 3. 配置

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/spring_ai_rag_dev
    username: postgres
    password: ${DB_PASSWORD}

  # Flyway 自动迁移（V1-V10）
  flyway:
    enabled: true

app:
  llm:
    provider: openai          # openai | anthropic
  ai:
    openai:
      api-key: ${DEEPSEEK_API_KEY}
      base-url: https://api.deepseek.com/v1
      chat:
        options:
          model: deepseek-chat
          temperature: 0.7

siliconflow:
  api-key: ${SILICONFLOW_API_KEY}
  embedding:
    model: BAAI/bge-m3
    dimensions: 1024

rag:
  retrieval:
    fulltext-enabled: true
    fulltext-strategy: auto   # auto | pg_jieba | pg_trgm | none
    vector-weight: 0.5
    default-limit: 10
    min-score: 0.3
```

### 4. 调用

```bash
# RAG 问答
curl -X POST http://localhost:8080/api/v1/rag/chat/ask \
  -H "Content-Type: application/json" \
  -d '{"message": "什么是 RAG？"}'

# 流式问答
curl -N -X POST http://localhost:8080/api/v1/rag/chat/stream \
  -H "Content-Type: application/json" \
  -d '{"message": "详细解释 RAG 工作原理"}'

# 上传文档
curl -X POST http://localhost:8080/api/v1/rag/documents \
  -H "Content-Type: application/json" \
  -d '{"title": "RAG 简介", "content": "RAG 是检索增强生成..."}'

# 文档嵌入
curl -X POST http://localhost:8080/api/v1/rag/documents/1/embed

# 检索（不经过 LLM）
curl "http://localhost:8080/api/v1/rag/search?query=RAG&limit=5"
```

启动后访问 `http://localhost:8080/swagger-ui.html` 查看完整 API 文档。

## 架构概览

```
请求
  │
  ▼
QueryRewriteAdvisor(+10)  — 查询改写，提升召回
  │
  ▼
HybridSearchAdvisor(+20)  — 向量 + 全文混合检索
  │   ├─ pgvector HNSW（向量）
  │   └─ pg_jieba / pg_trgm（全文，Gin 索引加速）
  │
  ▼
RerankAdvisor(+30)        — 结果重排序
  │
  ▼
ChatClient.call()          — LLM 生成回答
  │
  ▼
MessageChatMemoryAdvisor  — 对话记忆
  │
  ▼
响应
```

```
spring-ai-rag/
├── spring-ai-rag-api/        # API 接口、DTO
├── spring-ai-rag-core/       # 核心实现
│   ├── advisor/              # QueryRewrite / HybridSearch / Rerank Advisors
│   ├── retrieval/           # 检索服务 + 全文检索策略
│   ├── retrieval/fulltext/  # pg_jieba / pg_trgm / no-op Provider
│   ├── controller/         # REST 控制器
│   ├── service/            # 业务服务
│   ├── config/             # RagChatService / RagProperties
│   └── metrics/            # Micrometer 指标
├── spring-ai-rag-starter/  # Spring Boot 自动配置
├── spring-ai-rag-documents/  # 文档处理（HierarchicalTextChunker）
└── demos/                   # 集成演示项目
```

## REST API 端点（40+ 个）

| 模块 | 端点 | 说明 |
|------|------|------|
| 问答 | `POST /api/v1/rag/chat/ask` | 非流式 RAG 问答 |
| 问答 | `POST /api/v1/rag/chat/stream` | SSE 流式问答 |
| 问答 | `GET /api/v1/rag/chat/history/{sessionId}` | 会话历史 |
| 检索 | `GET /api/v1/rag/search` | 混合检索（不经过 LLM） |
| 文档 | `POST /api/v1/rag/documents` | 创建文档 |
| 文档 | `GET /api/v1/rag/documents/{id}` | 获取文档 |
| 文档 | `POST /api/v1/rag/documents/{id}/embed` | 生成嵌入向量 |
| 文档 | `GET /api/v1/rag/documents/{id}/versions` | 版本历史 |
| 集合 | `/api/v1/rag/collections` | 知识库管理 |
| 评估 | `/api/v1/rag/evaluations` | 检索质量评估 |
| 反馈 | `/api/v1/rag/feedbacks` | 用户反馈 |
| A/B | `/api/v1/rag/ab-tests` | 实验管理 |
| 告警 | `/api/v1/rag/alerts` | 监控告警 |
| 缓存 | `GET /api/v1/rag/cache/stats` | 嵌入缓存命中率 |
| **PDF** | `POST /api/v1/files/pdf` | 上传并转换 PDF 为 Markdown |
| **PDF** | `GET /api/v1/files/preview/{uuid}/default.html` | 预览转换后的 HTML（使用 `<base>` 标签） |
| **PDF** | `GET /api/v1/files/raw/{uuid}/{filename}` | 获取原始文件（图片、PDF 等） |
| **PDF** | `GET /api/v1/files/tree` | 查看文件目录树 |
| 健康 | `/actuator/health` | Actuator 健康检查 |

## 两种集成方式

参考 `demos/README.md`：

| 方式 | Demo | 适用场景 |
|------|------|---------|
| 完整 Starter | `demo-basic-rag` | 快速集成，引入一个依赖即可 |
| 组件级 | `demo-component-level` | 已有 Spring AI 项目，选择性引入 Advisor |

## 构建与测试

```bash
# 编译
mvn clean compile

# 测试（真实数据库，964 个测试）
export $(cat .env | grep -v '^#' | xargs)
mvn test

# 打包
mvn clean package -DskipTests

# 单独测试 demo
cd demos/demo-basic-rag && mvn test
```

## 文档

- [架构设计](docs/architecture.md)
- [配置参考](docs/configuration.md)
- [REST API 参考](docs/rest-api.md)
- [PostgreSQL 扩展说明](docs/postgresql-extensions.md)（pg_jieba / pg_trgm / pgvector）
- [领域扩展指南](docs/extension-guide.md)
- [测试指南](docs/testing-guide.md)
- [开发者上手](docs/getting-started.md)
- [故障排查](docs/troubleshooting.md)
- [CHANGELOG](CHANGELOG.md)
- [贡献指南](CONTRIBUTING.md)

## License

Apache License 2.0
