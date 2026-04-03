# Demos 目录说明

本目录包含两个独立演示项目，分别展示不同的集成方式。

---

## demo-basic-rag — 完整 Starter 集成（推荐）

**场景**：想把 RAG 能力引入任意现有 Spring Boot 应用，最快方式。

**特点**：
- 只需添加 `spring-ai-rag-starter` 一个依赖
- 所有 Bean 自动配置（`RagChatService`、`ChatClient` 等）
- 提供完整 REST API（40+ 端点）

**集成步骤**：

```xml
<!-- pom.xml -->
<dependency>
    <groupId>com.springairag</groupId>
    <artifactId>spring-ai-rag-starter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

```yaml
# application.yml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/spring_ai_rag_dev
app:
  llm:
    provider: openai
  ai:
    openai:
      api-key: ${DEEPSEEK_API_KEY}
siliconflow:
  api-key: ${SILICONFLOW_API_KEY}
```

```java
// 使用 RagChatService（一行调用）
@Autowired
private RagChatService ragChatService;

String answer = ragChatService.chat("你好", "session-001");
```

**快速启动**：
```bash
# 1. 安装 spring-ai-rag-starter 到本地仓库
cd ../..
mvn clean install -DskipTests

# 2. 启动 demo
cd demos/demo-basic-rag
export DEEPSEEK_API_KEY=xxx SILICONFLOW_API_KEY=xxx
mvn spring-boot:run

# 3. 测试
curl "http://localhost:8080/demo/ask?q=什么是RAG"
curl -X POST http://localhost:8080/demo/chat \
  -H "Content-Type: application/json" \
  -d '{"message": "你们的退换货政策是什么？"}'
```

---

## demo-component-level — 组件级集成（进阶）

**场景**：已有 Spring AI 项目，不想引入完整 Starter，只想选择性地使用某些 RAG 组件。

**特点**：
- 只引入 `spring-ai-rag-core`（非 starter）
- 手动注入 `HybridSearchAdvisor` / `QueryRewriteAdvisor` / `RerankAdvisor`
- 自己组装 `ChatClient` 的 Advisor 链
- 适合想理解 RAG Pipeline 内部机制的场景

**集成步骤**：

```xml
<!-- pom.xml -->
<dependency>
    <groupId>com.springairag</groupId>
    <artifactId>spring-ai-rag-core</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
<!-- Spring AI OpenAI + JDBC Chat Memory -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-openai</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-model-chat-memory-repository-jdbc</artifactId>
</dependency>
```

```java
@Configuration
@EnableConfigurationProperties(RagProperties.class)
public class MyRagConfig {

    @Bean
    public HybridRetrieverService hybridRetrieverService(
            EmbeddingModel embeddingModel,
            JdbcTemplate jdbcTemplate,
            RagProperties ragProperties,
            @Autowired(required = false) FulltextSearchProviderFactory fulltextProviderFactory) {
        return new HybridRetrieverService(
                embeddingModel, jdbcTemplate, ragProperties, fulltextProviderFactory, null);
    }

    @Bean
    public HybridSearchAdvisor hybridSearchAdvisor(HybridRetrieverService service) {
        return new HybridSearchAdvisor(service);
    }

    @Bean
    public ChatClient ragChatClient(OpenAiChatModel chatModel,
            HybridSearchAdvisor hybridSearchAdvisor) {
        return ChatClient.builder(chatModel)
                .defaultAdvisors(hybridSearchAdvisor)  // 挂载 RAG Advisor
                .build();
    }
}
```

**启动**：
```bash
cd demos/demo-component-level
export DEEPSEEK_API_KEY=xxx SILICONFLOW_API_KEY=xxx
mvn spring-boot:run  # 端口 8081
```

---

## 两种集成方式对比

| | demo-basic-rag | demo-component-level |
|---|---|---|
| 引入方式 | starter（自动配置） | core（手动配置） |
| 依赖数量 | 1 个 JAR | 3 个 JAR |
| Bean 注入 | 自动（starter） | 手动（@Bean） |
| Advisor 链 | 自动组装 | 自己组装 |
| 适用场景 | 快速集成 / 微服务 | 已有项目 / 细粒度控制 |
| 学习成本 | 低 | 中 |

**推荐**：大多数场景用 `demo-basic-rag`（完整 starter）。只有当你需要细粒度控制 RAG Pipeline 的每个环节时，才考虑 `demo-component-level`。

---

## 前提条件（两个 Demo 都需要）

```bash
# PostgreSQL 数据库
createdb spring_ai_rag_dev
psql spring_ai_rag_dev -c "CREATE EXTENSION IF NOT EXISTS vector;"
psql spring_ai_rag_dev -c "CREATE EXTENSION IF NOT EXISTS pg_trgm;"
```

Flyway 迁移会在首次启动时自动创建所有表。
