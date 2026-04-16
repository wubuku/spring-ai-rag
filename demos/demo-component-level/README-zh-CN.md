# Component-Level Demo

> 展示如何选择性地引入 RAG 关键组件，不依赖完整 Starter。

## 核心代码

参考 `src/main/java/com/springairag/demo/component/ComponentLevelDemoConfig.java`：
- 手动创建 `HybridRetrieverService`、`QueryRewritingService`、`ReRankingService`
- 手动创建 `HybridSearchAdvisor`、`QueryRewriteAdvisor`、`RerankAdvisor`
- 将 Advisors 挂载到 `ChatClient.builder().defaultAdvisors(...)`

## 关键模式

```java
// Advisor 链（order 值越小越先执行）
QueryRewriteAdvisor(+10) → HybridSearchAdvisor(+20) → RerankAdvisor(+30)

// 1. 手动创建服务
@Bean
public HybridRetrieverService hybridRetrieverService(
        EmbeddingModel embeddingModel,
        JdbcTemplate jdbcTemplate,
        RagProperties ragProperties,
        @Autowired(required = false) FulltextSearchProviderFactory fulltextProviderFactory) {
    return new HybridRetrieverService(
            embeddingModel, jdbcTemplate, ragProperties, fulltextProviderFactory, null);
}

// 2. 手动创建 Advisor
@Bean
public HybridSearchAdvisor hybridSearchAdvisor(HybridRetrieverService service) {
    return new HybridSearchAdvisor(service);
}

// 3. 挂载到 ChatClient
@Bean
public ChatClient ragChatClient(OpenAiChatModel chatModel,
        QueryRewriteAdvisor queryRewriteAdvisor,
        HybridSearchAdvisor hybridSearchAdvisor,
        RerankAdvisor rerankAdvisor) {
    return ChatClient.builder(chatModel)
            .defaultAdvisors(queryRewriteAdvisor, hybridSearchAdvisor, rerankAdvisor)
            .build();
}
```

## 运行

```bash
export DEEPSEEK_API_KEY=xxx SILICONFLOW_API_KEY=xxx
mvn spring-boot:run  # 端口 8081
```

## 测试端点

```bash
# 简单问答
curl "http://localhost:8081/demo/component/ask?q=什么是RAG"

# 多轮对话
curl -X POST http://localhost:8081/demo/component/chat \
  -H "Content-Type: application/json" \
  -d '{"message": "我叫张三", "sessionId": "test-001"}'
```
