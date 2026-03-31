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
 * EmbeddingModel 配置类
 * 使用 SiliconFlow API 提供嵌入模型（OpenAI 兼容格式）
 *
 * <p>此 Bean 不受 app.llm.provider 开关影响，始终创建。
 * 嵌入模型和 Chat 模型通常由不同提供商提供。
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

    /**
     * 创建嵌入模型 Bean
     * Bean 名称为 embeddingModel，会被 PgVectorStore 自动使用
     */
    @Bean
    public EmbeddingModel embeddingModel() {
        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .build();

        OpenAiEmbeddingOptions embeddingOptions = OpenAiEmbeddingOptions.builder()
                .model(model)
                .dimensions(dimensions)
                .build();

        return new OpenAiEmbeddingModel(openAiApi, MetadataMode.EMBED, embeddingOptions);
    }
}
