package com.springairag.demo.component;

import com.springairag.core.adapter.ApiAdapterFactory;
import com.springairag.core.advisor.HybridSearchAdvisor;
import com.springairag.core.advisor.RerankAdvisor;
import com.springairag.core.advisor.QueryRewriteAdvisor;
import com.springairag.core.config.RagProperties;
import com.springairag.core.retrieval.HybridRetrieverService;
import com.springairag.core.retrieval.QueryRewritingService;
import com.springairag.core.retrieval.ReRankingService;
import com.springairag.core.retrieval.fulltext.FulltextSearchProviderFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 组件手动配置（替代 Starter 自动配置）
 *
 * <p>展示如何手动组装 RAG 核心组件：
 * 不需要 spring-ai-rag-starter，只需 spring-ai-rag-core + Spring AI，
 * 自己按需注入 Advisor，组装成 ChatClient。
 *
 * <p>Advisor 链：
 * QueryRewriteAdvisor(+10) → HybridSearchAdvisor(+20) → RerankAdvisor(+30)
 */
@Configuration
@EnableConfigurationProperties(RagProperties.class)
public class ComponentLevelDemoConfig {

    @Value("${spring.ai.openai.base-url:}")
    private String openAiBaseUrl;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RagProperties ragProperties;

    // ================================================================
    // 底层服务
    // ================================================================

    @Bean
    public QueryRewritingService queryRewritingService() {
        return new QueryRewritingService(ragProperties);
    }

    @Bean
    public HybridRetrieverService hybridRetrieverService(
            EmbeddingModel embeddingModel,
            @Autowired(required = false) FulltextSearchProviderFactory fulltextProviderFactory) {
        return new HybridRetrieverService(
                embeddingModel, jdbcTemplate, ragProperties, fulltextProviderFactory, null);
    }

    @Bean
    public ReRankingService reRankingService() {
        return new ReRankingService(ragProperties);
    }

    // ================================================================
    // RAG Advisors
    // ================================================================

    @Bean
    public QueryRewriteAdvisor queryRewriteAdvisor(QueryRewritingService queryRewritingService) {
        return new QueryRewriteAdvisor(queryRewritingService);
    }

    @Bean
    public HybridSearchAdvisor hybridSearchAdvisor(HybridRetrieverService hybridRetrieverService) {
        return new HybridSearchAdvisor(hybridRetrieverService);
    }

    @Bean
    public RerankAdvisor rerankAdvisor(ReRankingService reRankingService) {
        return new RerankAdvisor(reRankingService, new ApiAdapterFactory(), openAiBaseUrl);
    }

    // ================================================================
    // 对话记忆（Spring AI 原生 JdbcChatMemoryRepository）
    // Spring Boot 会自动创建 JdbcChatMemoryRepository（如果 spring-ai-jdbc-memory 在 classpath）
    // 这里手动创建，确保脱离 starter 也能工作
    // ================================================================

    @Bean
    public JdbcChatMemoryRepository jdbcChatMemoryRepository() {
        return JdbcChatMemoryRepository.builder()
                .jdbcTemplate(jdbcTemplate)
                .build();
    }

    @Bean
    public MessageChatMemoryAdvisor chatMemoryAdvisor(JdbcChatMemoryRepository repository) {
        ChatMemory chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)
                .maxMessages(ragProperties.getMemory().getMaxMessages())
                .build();
        return MessageChatMemoryAdvisor.builder(chatMemory).build();
    }

    // ================================================================
    // ChatClient（带 RAG Advisors）
    // ================================================================

    @Bean
    public ChatClient ragChatClient(
            OpenAiChatModel chatModel,
            QueryRewriteAdvisor queryRewriteAdvisor,
            HybridSearchAdvisor hybridSearchAdvisor,
            RerankAdvisor rerankAdvisor) {

        return ChatClient.builder(chatModel)
                .defaultAdvisors(
                        queryRewriteAdvisor,
                        hybridSearchAdvisor,
                        rerankAdvisor)
                .build();
    }

    @Bean
    public ChatClient ragChatClientWithMemory(
            OpenAiChatModel chatModel,
            QueryRewriteAdvisor queryRewriteAdvisor,
            HybridSearchAdvisor hybridSearchAdvisor,
            RerankAdvisor rerankAdvisor,
            MessageChatMemoryAdvisor memoryAdvisor) {

        return ChatClient.builder(chatModel)
                .defaultAdvisors(
                        queryRewriteAdvisor,
                        hybridSearchAdvisor,
                        rerankAdvisor,
                        memoryAdvisor)
                .build();
    }
}
