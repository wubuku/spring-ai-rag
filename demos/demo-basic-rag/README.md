# Customer Service Bot — RAG 集成演示

> 场景：已有 Spring Boot 应用，通过引入 `spring-ai-rag-starter` 一行依赖获得完整 RAG 能力。

## 快速开始

### 1. 安装 spring-ai-rag-starter 到本地仓库

```bash
# 在 spring-ai-rag 项目根目录
cd ../..
mvn clean install -DskipTests
```

### 2. 启动本 Demo

```bash
cd demos/demo-basic-rag
export DEEPSEEK_API_KEY=sk-xxx
export SILICONFLOW_API_KEY=sk-xxx
mvn spring-boot:run
```

### 3. 测试

```bash
# 客服问答
curl -X POST http://localhost:8080/api/v1/rag/chat/ask \
  -H "Content-Type: application/json" \
  -d '{"message": "你们的退换货政策是什么？", "sessionId": "customer-001"}'

# 上传知识库文档
curl -X POST http://localhost:8080/api/v1/rag/documents \
  -H "Content-Type: application/json" \
  -d '{"title": "退换货政策", "content": "自收到商品之日起7天内可申请退换货...", "source": "policy"}'
```

---

## 如何集成到现有项目

### Step 1：添加 Maven 依赖

```xml
<dependency>
    <groupId>com.springairag</groupId>
    <artifactId>spring-ai-rag-starter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### Step 2：配置 application.yml

参考 `src/main/resources/application.yml`，
只需配置：
- `spring.datasource.*`（PostgreSQL + pgvector）
- `app.llm.*`（LLM Provider）
- `siliconflow.*`（嵌入模型）

### Step 3：直接使用

```java
@RestController
public class CustomerServiceController {

    @Autowired
    private RagChatService ragChatService;

    @PostMapping("/chat")
    public Map<String, String> chat(@RequestBody ChatRequest req) {
        String answer = ragChatService.chat(req.getMessage(), req.getSessionId());
        return Map.of("answer", answer, "sessionId", req.getSessionId());
    }
}
```

---

## 只需一行代码即可获得的能力

| 功能 | 原本工作量 | 引入 starter 后 |
|------|-----------|----------------|
| 向量检索 | 写 JDBC SQL + HNSW | ✅ 自动 |
| 全文检索 | 写分词 + 相似度 SQL | ✅ 自动 |
| 查询改写 | 调用 LLM 改写 Query | ✅ 自动 |
| 结果重排 | 自己实现 ReRank | ✅ 自动 |
| 对话记忆 | 写表 + CRUD | ✅ 自动 |
| REST API | 自己写 Controller | ✅ 40+ 端点 |
| 健康检查 | 写 Probe | ✅ Actuator 自动 |
| 文档嵌入 | 写嵌入逻辑 | ✅ 自动 |

## 数据库准备

```bash
createdb spring_ai_rag_dev
psql spring_ai_rag_dev -c "CREATE EXTENSION IF NOT EXISTS vector;"
psql spring_ai_rag_dev -c "CREATE EXTENSION IF NOT EXISTS pg_trgm;"
```

应用启动时 Flyway 会自动创建所有表（HNSW 索引、全文检索配置等）。

## 切换 LLM Provider

```yaml
# DeepSeek（默认）
app.llm.provider: openai

# 智谱 GLM
app.llm.provider: openai
spring.ai.openai.base-url: https://open.bigmodel.cn/paas/v4

# Anthropic
app.llm.provider: anthropic
```
