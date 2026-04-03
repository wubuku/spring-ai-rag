package com.springairag.core.config;

import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * EmbeddingModel 配置
 * 使用 SiliconFlow API（OpenAI 兼容格式），不受 app.llm.provider 开关影响
 */
@Configuration
public class EmbeddingModelConfig {

    private final RagEmbeddingProperties embedding;

    public EmbeddingModelConfig(RagProperties ragProperties) {
        this.embedding = ragProperties.getEmbedding();
    }

    @Bean
    public EmbeddingModel embeddingModel() {
        org.slf4j.LoggerFactory.getLogger(EmbeddingModelConfig.class)
                .info("Creating EmbeddingModel: baseUrl={}, model={}, apiKey={}..., dimensions={}",
                        embedding.getBaseUrl(), embedding.getModel(),
                        embedding.getApiKey().substring(0, Math.min(10, embedding.getApiKey().length())),
                        embedding.getDimensions());

        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(embedding.getBaseUrl())
                .apiKey(embedding.getApiKey())
                .build();

        return new OpenAiEmbeddingModel(openAiApi, MetadataMode.EMBED,
                OpenAiEmbeddingOptions.builder()
                        .model(embedding.getModel())
                        .dimensions(embedding.getDimensions())
                        .build());
    }
}
