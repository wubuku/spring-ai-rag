# spring-ai-rag

基于 [Spring AI](https://docs.spring.io/spring-ai/reference/) 的通用 RAG（检索增强生成）服务框架。

**模型无关 · 领域解耦 · 组件化**

## 为什么选 spring-ai-rag？

| 痛点 | spring-ai-rag 方案 |
|------|-------------------|
| 换模型要改代码？ | 切换 LLM 只改一行配置，OpenAI/DeepSeek/Anthropic/智谱全兼容 |
| RAG 效果差？ | 混合检索 + 查询改写 + 重排序，Advisor 链式 Pipeline 逐层优化 |
| 只能用一个领域？ | DomainRagExtension 接口，一个服务支撑 N 个垂直领域 |
| 组件太重？ | 每个 Advisor/Service 可独立引入，不捆绑 |

## 功能特性

- **混合检索**：pgvector 向量检索 + PostgreSQL 全文检索融合打分
- **Advisor 链式 Pipeline**：查询改写 → 混合检索 → 重排序 → 上下文注入
- **多模型支持**：OpenAI 兼容 + Anthropic，Provider 自动切换
- **领域扩展**：实现 `DomainRagExtension` 即可注入领域 Prompt 和检索策略
- **SSE 流式输出**：Server-Sent Events 实时响应
- **A/B 实验框架**：多模型并行对比，自动收集延迟/token/质量指标
- **检索评估**：RetrievalEvaluationService + 用户反馈闭环
- **监控可观测**：Micrometer 指标 + Actuator 健康检查
- **API Key 认证**：内建安全过滤器

## 快速开始

### 1. 数据库

```sql
CREATE DATABASE spring_ai_rag;
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;
```

### 2. 添加依赖

```xml
<dependency>
    <groupId>com.springairag</groupId>
    <artifactId>spring-ai-rag-starter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### 3. 配置 & 启动

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/spring_ai_rag
  ai:
    openai:
      api-key: ${OPENAI_API_KEY}
      base-url: https://api.deepseek.com/v1

app:
  llm:
    provider: openai

rag:
  embedding:
    api-key: ${SILICONFLOW_API_KEY}
    base-url: https://api.siliconflow.cn/v1
    model: BAAI/bge-m3
    dimensions: 1024
```

### 4. 调用

```bash
# RAG 问答
curl -X POST http://localhost:8080/api/v1/rag/chat/ask \
  -H "Content-Type: application/json" \
  -d '{"message": "什么是 Spring AI？"}'

# 流式问答
curl -N -X POST http://localhost:8080/api/v1/rag/chat/stream \
  -H "Content-Type: application/json" \
  -d '{"message": "解释 RAG 的工作原理"}'
```

## 架构概览

```
请求 → QueryRewriteAdvisor(+10) → HybridSearchAdvisor(+20)
     → RerankAdvisor(+30) → ChatClient.call/stream()
     → MessageChatMemoryAdvisor → 响应
```

```
spring-ai-rag/
├── spring-ai-rag-api/        # API 接口、DTO
├── spring-ai-rag-core/       # 核心：Advisor / Controller / Retrieval / Metrics
├── spring-ai-rag-starter/    # Spring Boot 自动配置
├── spring-ai-rag-documents/  # 文档处理（分块、清洗）
└── demos/                    # 示例项目
```

## REST API 端点

| 模块 | 端点 | 说明 |
|------|------|------|
| 问答 | `/api/v1/rag/chat/ask` | 非流式 RAG 问答 |
| 问答 | `/api/v1/rag/chat/stream` | SSE 流式问答 |
| 检索 | `/api/v1/rag/search` | 混合检索 |
| 文档 | `/api/v1/rag/documents` | 文档 CRUD + 嵌入 |
| 集合 | `/api/v1/rag/collections` | 知识库管理 |
| 评估 | `/api/v1/rag/evaluations` | 检索质量评估 |
| 反馈 | `/api/v1/rag/feedbacks` | 用户反馈 |
| A/B | `/api/v1/rag/ab-tests` | 实验管理 |
| 告警 | `/api/v1/rag/alerts` | 监控告警 |
| 健康 | `/api/v1/rag/health` | 服务健康检查 |

完整 API 文档：启动后访问 `/swagger-ui.html`

## 领域扩展示例

```java
@Component
public class MedicalDomainExtension implements DomainRagExtension {
    @Override public String getDomainId() { return "medical"; }
    @Override public String getName() { return "医疗领域"; }
    @Override public String getSystemPromptSuffix() { return "你是医疗知识助手..."; }
}
```

调用时指定 `domainId` 即可激活领域扩展。

## 构建与测试

```bash
mvn clean compile   # 编译
mvn test            # 测试（700+ 个）
mvn clean package   # 打包
```

## 文档

- [架构设计](docs/architecture.md)
- [配置参考](docs/configuration.md)
- [REST API 参考](docs/rest-api.md)
- [领域扩展指南](docs/extension-guide.md)
- [测试指南](docs/testing-guide.md)
- [开发者上手](docs/getting-started.md)
- [部署指南](docs/DEPLOYMENT.md)
- [故障排查](docs/troubleshooting.md)
- [贡献指南](CONTRIBUTING.md)

## License

Apache License 2.0
