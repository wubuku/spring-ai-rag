# Demo: Basic RAG

最简单的 spring-ai-rag 集成示例，5 分钟跑通 RAG 问答。

## 前置条件

| 依赖 | 说明 |
|------|------|
| Java 17+ | 运行时 |
| PostgreSQL + pgvector | 向量数据库 |
| DeepSeek API Key | LLM（可换其他 OpenAI 兼容模型） |
| SiliconFlow API Key | 嵌入模型 BGE-M3 |

### 数据库准备

```bash
# 创建数据库
createdb spring_ai_rag_dev

# 启用扩展（应用启动时 Flyway 自动建表）
psql spring_ai_rag_dev -c "CREATE EXTENSION IF NOT EXISTS vector;"
psql spring_ai_rag_dev -c "CREATE EXTENSION IF NOT EXISTS pg_trgm;"
```

## 快速启动

```bash
# 1. 在项目根目录先安装框架
cd ../..
mvn clean install -DskipTests

# 2. 启动 Demo
cd demos/demo-basic-rag
export DEEPSEEK_API_KEY=sk-your-key
export SILICONFLOW_API_KEY=sk-your-key
mvn spring-boot:run
```

## 测试接口

### 方式一：快速问答（GET）

```bash
curl "http://localhost:8080/demo/ask?q=什么是RAG？"
```

### 方式二：完整问答（POST）

```bash
curl -X POST http://localhost:8080/demo/chat \
  -H "Content-Type: application/json" \
  -d '{"message": "spring-ai-rag 支持哪些模型？", "sessionId": "test-001"}'
```

### 方式三：使用框架内置 API

框架自动注册了完整的 REST API，可直接使用：

```bash
# RAG 问答（非流式）
curl -X POST http://localhost:8080/api/v1/rag/chat/ask \
  -H "Content-Type: application/json" \
  -d '{"message": "什么是混合检索？", "sessionId": "session-1"}'

# RAG 问答（流式 SSE）
curl -X POST http://localhost:8080/api/v1/rag/chat/stream \
  -H "Content-Type: application/json" \
  -d '{"message": "详细解释向量检索", "sessionId": "session-1"}'

# 上传文档
curl -X POST http://localhost:8080/api/v1/rag/documents \
  -H "Content-Type: application/json" \
  -d '{"title": "RAG 简介", "content": "RAG 是检索增强生成...", "source": "manual"}'

# 为文档生成嵌入向量
curl -X POST http://localhost:8080/api/v1/rag/documents/1/embed

# 直接检索（不经过 LLM）
curl "http://localhost:8080/api/v1/rag/search?query=向量检索&limit=5"

# 查看会话历史
curl http://localhost:8080/api/v1/rag/chat/history/session-1

# 健康检查
curl http://localhost:8080/actuator/health
```

### Swagger UI

启动后访问：http://localhost:8080/swagger-ui.html

## 代码说明

**核心就一行**：

```java
@Autowired
private RagChatService ragChatService;

String answer = ragChatService.chat("你的问题", "会话ID");
```

`RagChatService` 自动完成：
1. 查询改写（QueryRewriteAdvisor）
2. 混合检索（HybridSearchAdvisor：向量 + 全文）
3. 结果重排（RerankAdvisor）
4. LLM 生成回答
5. 对话记忆管理

## 切换模型

修改 `application.yml` 中的 `app.llm.provider`：

```yaml
# DeepSeek（默认）
app.llm.provider: openai
spring.ai.openai.base-url: https://api.deepseek.com/v1

# Anthropic
app.llm.provider: anthropic
spring.ai.anthropic.api-key: ${ANTHROPIC_API_KEY}
```

## 领域扩展

实现 `DomainRagExtension` 接口并注册为 Bean，即可为特定领域定制 Prompt 和检索策略：

```java
@Component
public class SkinRagExtension extends DefaultDomainRagExtension {
    @Override
    public String getDomainId() { return "skin-care"; }

    @Override
    public String getSystemPrompt() {
        return "你是皮肤护理专家，基于提供的知识库回答问题...";
    }
}
```

使用时指定 domainId：

```bash
curl -X POST http://localhost:8080/api/v1/rag/chat/ask \
  -H "Content-Type: application/json" \
  -d '{"message": "敏感肌怎么护理？", "sessionId": "s1", "domainId": "skin-care"}'
```
