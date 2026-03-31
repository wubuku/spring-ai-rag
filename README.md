# spring-ai-rag

基于 [Spring AI](https://docs.spring.io/spring-ai/reference/) 的通用 RAG（检索增强生成）服务框架。

**模型无关 · 领域解耦 · 组件化**

## 特性

- **模型无关**：通过 Spring AI ChatClient 抽象，支持 OpenAI、DeepSeek、Anthropic、智谱等模型，切换模型只改配置
- **混合检索**：向量检索（pgvector HNSW）+ 全文检索（pg_trgm）融合打分
- **Advisor 链式 RAG Pipeline**：查询改写 → 混合检索 → 重排序 → 上下文注入
- **组件独立**：每个 Advisor 和 Service 可独立使用，支持组件级集成
- **领域解耦**：通过 `DomainRagExtension` 接口支持垂直领域定制
- **SSE 流式输出**：支持 Server-Sent Events 流式响应
- **监控可观测**：集成 Micrometer 指标 + Actuator 健康检查

## 快速开始

### 前置要求

- Java 17+
- PostgreSQL 15+（需安装 `vector` 和 `pg_trgm` 扩展）
- Maven 3.9+

### 数据库准备

```sql
CREATE DATABASE spring_ai_rag;
\c spring_ai_rag;
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;
```

Flyway 会自动创建表结构。

### 集成到项目

```xml
<dependency>
    <groupId>com.springairag</groupId>
    <artifactId>spring-ai-rag-starter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### 配置

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/spring_ai_rag
    username: postgres
    password: your-password
  ai:
    openai:
      api-key: ${OPENAI_API_KEY}
      base-url: https://api.deepseek.com/v1
      chat:
        enabled: false
        options:
          model: deepseek-chat

app:
  llm:
    provider: openai  # openai | anthropic

rag:
  embedding:
    api-key: ${SILICONFLOW_API_KEY}
    base-url: https://api.siliconflow.cn/v1
    model: BAAI/bge-m3
    dimensions: 1024
```

## REST API

### RAG 问答

```bash
# 非流式问答
curl -X POST http://localhost:8080/api/v1/rag/chat/ask \
  -H "Content-Type: application/json" \
  -d '{"message": "什么是 Spring AI？", "sessionId": "test-001"}'

# 流式问答（SSE）
curl -N -X POST http://localhost:8080/api/v1/rag/chat/stream \
  -H "Content-Type: application/json" \
  -d '{"message": "解释 RAG 的工作原理", "sessionId": "test-001"}'
```

### 文档管理

```bash
# 上传文档
curl -X POST http://localhost:8080/api/v1/rag/documents \
  -H "Content-Type: application/json" \
  -d '{"title": "Spring AI 入门", "content": "Spring AI 是...", "source": "manual"}'

# 生成嵌入向量
curl -X POST http://localhost:8080/api/v1/rag/documents/1/embed
```

### 混合检索

```bash
curl -X POST http://localhost:8080/api/v1/rag/search \
  -H "Content-Type: application/json" \
  -d '{"query": "Spring AI", "topK": 5}'
```

### 健康检查

```bash
curl http://localhost:8080/api/v1/rag/health
curl http://localhost:8080/actuator/health
```

完整 API 文档：启动后访问 `/swagger-ui.html`

## 架构

### RAG Pipeline（Advisor 链）

```
用户请求
  │
  ▼
QueryRewriteAdvisor (order +10)
  │  查询改写：同义词扩展、限定词补充
  ▼
HybridSearchAdvisor (order +20)
  │  混合检索：向量 + 全文融合，结果存入 context attributes
  ▼
RerankAdvisor (order +30)
  │  多维重排：相关性 + 多样性，注入 Prompt 上下文
  ▼
ChatClient.call() / stream()
  │
  ▼
MessageChatMemoryAdvisor
  │  对话记忆：spring_ai_chat_memory + rag_chat_history 双表
  ▼
响应
```

### 模块结构

```
spring-ai-rag/
├── spring-ai-rag-api/        # API 接口、DTO 定义
├── spring-ai-rag-core/       # 核心实现
│   ├── advisor/              #   QueryRewrite / HybridSearch / Rerank Advisor
│   ├── config/               #   SpringAiConfig / RagChatService / CacheConfig
│   ├── controller/           #   REST 控制器
│   ├── extension/            #   DomainRagExtension 领域扩展
│   ├── metrics/              #   Micrometer 指标 + Actuator 健康检查
│   ├── repository/           #   数据访问层
│   └── retrieval/            #   检索服务（混合检索、重排、查询改写、嵌入）
├── spring-ai-rag-starter/    # Spring Boot 自动配置
├── spring-ai-rag-documents/  # 文档处理（分块、清洗）
└── demos/                    # 示例项目
```

## 领域扩展

实现 `DomainRagExtension` 接口，注册到 `DomainExtensionRegistry`：

```java
@Component
public class MedicalDomainExtension implements DomainRagExtension {
    @Override public String getDomainId() { return "medical"; }
    @Override public String getName() { return "医疗领域"; }
    @Override public String getSystemPromptSuffix() { return "你是医疗知识助手..."; }
}
```

使用时指定 `domainId`：

```bash
curl -X POST http://localhost:8080/api/v1/rag/chat/ask \
  -H "Content-Type: application/json" \
  -d '{"message": "感冒症状", "sessionId": "s1", "domainId": "medical"}'
```

## 监控

集成 Micrometer + Actuator：

```bash
# 健康检查
curl /actuator/health

# Prometheus 指标
curl /actuator/prometheus
```

指标包括：
- `rag.requests.total` — 请求计数（success/failure 标签）
- `rag.request.duration` — 请求耗时分布
- `rag.llm.tokens` — LLM token 消耗

## 构建与测试

```bash
# 编译
mvn clean compile

# 测试
mvn test

# 打包
mvn clean package -DskipTests
```

## 详细文档

- [部署指南](docs/DEPLOYMENT.md)

## License

Apache License 2.0
