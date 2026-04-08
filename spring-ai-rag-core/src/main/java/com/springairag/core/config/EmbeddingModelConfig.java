package com.springairag.core.config;

import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;

/**
 * EmbeddingModel Configuration
 * Uses SiliconFlow API (OpenAI-compatible format), unaffected by app.llm.provider switch
 *
 * <p>MiniMaxEmbeddingAutoConfiguration is excluded in application.yml,
 * ensuring SiliconFlow EmbeddingModel is the only EmbeddingModel implementation.
 */
@Configuration
public class EmbeddingModelConfig {

    // Use @Value to directly bind from environment - avoids nested @ConfigurationProperties issues
    // Uses spring.ai.openai.* which is passed via command-line in mvn spring-boot:run
    @Value("${spring.ai.openai.api-key:${SPRING_AI_OPENAI_API_KEY:}}")
    private String apiKey;

    @Value("${rag.embedding.model:BAAI/bge-m3}")
    private String model;

    @Value("${rag.embedding.dimensions:1024}")
    private int dimensions;

    @Bean
    @ConditionalOnMissingBean(EmbeddingModel.class)
    public EmbeddingModel embeddingModel() {
        // Use explicit SiliconFlow URL without /v1 suffix (OpenAiApi appends /v1/embeddings automatically)
        String baseUrl = "https://api.siliconflow.cn";

        org.slf4j.LoggerFactory.getLogger(EmbeddingModelConfig.class)
                .info("Creating EmbeddingModel: baseUrl={}, model={}, apiKey={}..., dimensions={}",
                        baseUrl, model,
                        apiKey != null && apiKey.length() > 10 ? apiKey.substring(0, 10) : "***",
                        dimensions);

        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .build();

        return new OpenAiEmbeddingModel(openAiApi, MetadataMode.EMBED,
                OpenAiEmbeddingOptions.builder()
                        .model(model)
                        .dimensions(dimensions)
                        .build());
    }
}
