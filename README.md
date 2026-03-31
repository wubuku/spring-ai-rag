# spring-ai-rag

基于 [Spring AI](https://docs.spring.io/spring-ai/reference/) 的通用 RAG（检索增强生成）服务框架。

## 特性

- **模型无关**：通过 Spring AI ChatClient 抽象，支持 OpenAI、DeepSeek、Anthropic 等多种模型
- **混合检索**：向量检索 + 全文检索融合（pgvector + pg_trgm）
- **Advisor 链式 RAG Pipeline**：查询改写 → 混合检索 → 重排序 → 上下文注入
- **组件独立**：每个 Advisor 和 Service 可独立使用，支持组件级集成
- **领域解耦**：通过 DomainRagExtension 接口支持垂直领域定制

## 技术栈

| 组件 | 技术 | 版本 |
|------|------|------|
| 框架 | Spring Boot | 3.4.2 |
| AI 框架 | Spring AI | 1.1.2 |
| 数据库 | PostgreSQL + pgvector | - |
| 构建工具 | Maven | 3.9.x |

## 模块结构

| 模块 | 说明 |
|------|------|
| `spring-ai-rag-api` | API 接口定义、DTO |
| `spring-ai-rag-core` | 核心实现（配置、Advisor、检索、服务） |
| `spring-ai-rag-starter` | Spring Boot Starter 自动配置 |
| `spring-ai-rag-documents` | 文档处理组件 |

## 快速开始

### 构建

```bash
mvn clean compile
```

### 测试

```bash
mvn test
```

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
    provider: openai
rag:
  embedding:
    api-key: ${SILICONFLOW_API_KEY}
    base-url: https://api.siliconflow.cn/v1
    model: BAAI/bge-m3
    dimensions: 1024
```

## 架构

```
ChatClient.prompt()
  ├── QueryRewriteAdvisor     (查询改写)
  ├── HybridSearchAdvisor     (混合检索 → context attributes)
  ├── RerankAdvisor           (重排序 + 上下文注入)
  └── MessageChatMemoryAdvisor (对话记忆)
```

## License

Apache License 2.0
