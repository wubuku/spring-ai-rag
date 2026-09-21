# Component-Level Demo

> Demonstrates how to selectively import key RAG components without depending on the full Starter.

## Core Code

See `src/main/java/com/springairag/demo/component/ComponentLevelDemoConfig.java`:
- Manually create `HybridRetrieverService`, `QueryRewritingService`, `ReRankingService`
- Manually create `HybridSearchAdvisor`, `QueryRewriteAdvisor`, `RerankAdvisor`
- Mount Advisors to `ChatClient.builder().defaultAdvisors(...)`

## Key Patterns

```java
// Advisor chain (lower order values execute first)
QueryRewriteAdvisor(+10) → HybridSearchAdvisor(+20) → RerankAdvisor(+30)

// 1. Manually create the service
@Bean
public HybridRetrieverService hybridRetrieverService(
        EmbeddingModel embeddingModel,
        JdbcTemplate jdbcTemplate,
        RagProperties ragProperties,
        @Autowired(required = false) FulltextSearchProviderFactory fulltextProviderFactory) {
    return new HybridRetrieverService(
            embeddingModel, jdbcTemplate, ragProperties, fulltextProviderFactory, null);
}

// 2. Manually create the Advisor
@Bean
public HybridSearchAdvisor hybridSearchAdvisor(HybridRetrieverService service) {
    return new HybridSearchAdvisor(service);
}

// 3. Mount to ChatClient
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

## Running

```bash
export DEEPSEEK_API_KEY=xxx RAG_EMBEDDING_API_KEY=xxx
mvn spring-boot:run  # Port 8081
```

## Test Endpoints

```bash
# Simple Q&A
curl "http://localhost:8081/demo/component/ask?q=What is RAG"

# Multi-turn conversation
curl -X POST http://localhost:8081/demo/component/chat \
  -H "Content-Type: application/json" \
  -d '{"message": "My name is John", "sessionId": "test-001"}'
```
