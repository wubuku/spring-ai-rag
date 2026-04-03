package com.springairag.demo.component;

import com.springairag.core.advisor.HybridSearchAdvisor;
import com.springairag.core.advisor.RerankAdvisor;
import com.springairag.core.advisor.QueryRewriteAdvisor;
import com.springairag.core.config.RagProperties;
import com.springairag.core.retrieval.fulltext.FulltextSearchProviderFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.Advisor;
import org.springframework.ai.chat.client.ChatClient.Builder;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.ai.embedding.EmbeddingModel;

/**
 * 组件手动配置（替代 Starter 自动配置）
 *
 * <p>这个配置类展示了如何手动组装 RAG 组件。
 * 关键：不需要引入 spring-ai-rag-starter，只需引入 spring-ai-rag-core，
 * 然后按需注入 Advisor，自己挂到 ChatClient 上。
 *
 * <p>两种使用方式：
 * <ol>
 *   <li><b>纯 ChatClient + Advisors</b>：自己组装 Advisor 链，最灵活</li>
 *   <li><b>ChatClient + Advisors + Memory</b>：加上对话记忆，支持多轮</li>
 * </ol>
 */
@Configuration
@EnableConfigurationProperties(RagProperties.class)
public class ComponentLevelDemoConfig {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RagProperties ragProperties;

    // ================================================================
    // 第一步：创建 RAG Advisors（核心组件）
    // ================================================================

    @Bean
    public QueryRewriteAdvisor queryRewriteAdvisor(OpenAiChatModel chatModel, RagProperties ragProperties) {
        return new QueryRewriteAdvisor(chatModel, ragProperties);
    }

    @Bean
    public HybridSearchAdvisor hybridSearchAdvisor(
            EmbeddingModel embeddingModel,
            JdbcTemplate jdbcTemplate,
            RagProperties ragProperties,
            @Autowired(required = false) com.springairag.core.retrieval.fulltext.FulltextSearchProviderFactory fulltextProviderFactory) {
        return new HybridSearchAdvisor(embeddingModel, jdbcTemplate, ragProperties, fulltextProviderFactory);
    }

    @Bean
    public RerankAdvisor rerankAdvisor(OpenAiChatModel chatModel, JdbcTemplate jdbcTemplate, RagProperties ragProperties) {
        return new RerankAdvisor(chatModel, jdbcTemplate, ragProperties);
    }

    @Bean
    public MessageChatMemoryAdvisor chatMemoryAdvisor(JdbcTemplate jdbcTemplate) {
        // 对话记忆表，表名必须与 Flyway 迁移创建的表一致
        return new MessageChatMemoryAdvisor(jdbcTemplate, "spring_ai_chat_memory");
    }

    // ================================================================
    // 第二步：创建 ChatClient，把 Advisors 挂上去
    // ================================================================

    /**
     * 带完整 RAG Pipeline 的 ChatClient
     *
     * 自动经过 Advisor 链：
     *   QueryRewriteAdvisor (+10) → HybridSearchAdvisor (+20) → RerankAdvisor (+30)
     *
     * 然后才到 LLM 生成回答。
     */
    @Bean
    public ChatClient ragChatClient(
            OpenAiChatModel chatModel,
            QueryRewriteAdvisor queryRewriteAdvisor,
            HybridSearchAdvisor hybridSearchAdvisor,
            RerankAdvisor rerankAdvisor) {

        return ChatClient.builder(chatModel)
                .defaultAdvisors(
                        queryRewriteAdvisor,   // +10 查询改写
                        hybridSearchAdvisor,   // +20 混合检索
                        rerankAdvisor          // +30 结果重排
                )
                .build();
    }

    /**
     * 带 RAG + 对话记忆的 ChatClient
     *
     * Advisor 链：
     *   QueryRewrite → HybridSearch → Rerank → MessageChatMemory
     */
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
                        memoryAdvisor   // 对话记忆（多轮支持）
                )
                .build();
    }
}
