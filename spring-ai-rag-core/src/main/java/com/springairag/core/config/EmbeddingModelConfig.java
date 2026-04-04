package com.springairag.core.config;

import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * EmbeddingModel 配置
 * 使用 SiliconFlow API（OpenAI 兼容格式），不受 app.llm.provider 开关影响
 *
 * <p>MiniMaxEmbeddingAutoConfiguration 在 application.yml 中已排除，
 * 确保 SiliconFlow EmbeddingModel 是唯一的 EmbeddingModel 实现。
 */
@Configuration
public class EmbeddingModelConfig {

    private final RagEmbeddingProperties embedding;

    public EmbeddingModelConfig(RagProperties ragProperties) {
        this.embedding = ragProperties.getEmbedding();
    }

    @Bean
    @ConditionalOnMissingBean(EmbeddingModel.class)
    public EmbeddingModel embeddingModel() {
        org.slf4j.LoggerFactory.getLogger(EmbeddingModelConfig.class)
                .info("Creating EmbeddingModel: baseUrl={}, model={}, apiKey={}..., dimensions={}",
                        embedding.getBaseUrl(), embedding.getModel(),
                        embedding.getApiKey().length() > 10 ? embedding.getApiKey().substring(0, 10) : "***",
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
