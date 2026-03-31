package com.springairag.core.config;

import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * EmbeddingModel 配置
 * 使用 SiliconFlow API（OpenAI 兼容格式），不受 app.llm.provider 开关影响
 */
@Configuration
public class EmbeddingModelConfig {

    @Value("${rag.embedding.api-key:}")
    private String apiKey;

    @Value("${rag.embedding.base-url:https://api.siliconflow.cn/v1}")
    private String baseUrl;

    @Value("${rag.embedding.model:BAAI/bge-m3}")
    private String model;

    @Value("${rag.embedding.dimensions:1024}")
    private int dimensions;

    @Bean
    public EmbeddingModel embeddingModel() {
        org.slf4j.LoggerFactory.getLogger(EmbeddingModelConfig.class)
                .info("Creating EmbeddingModel: baseUrl={}, model={}, apiKey={}..., dimensions={}",
                        baseUrl, model, apiKey.substring(0, Math.min(10, apiKey.length())), dimensions);

        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .build();

        return new OpenAiEmbeddingModel(openAiApi, MetadataMode.EMBED,
                OpenAiEmbeddingOptions.builder().model(model).dimensions(dimensions).build());
    }
}
