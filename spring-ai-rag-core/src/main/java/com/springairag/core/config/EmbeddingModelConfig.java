package com.springairag.core.config;

import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * EmbeddingModel 配置类
 * 使用 SiliconFlow API 提供嵌入模型（OpenAI 兼容格式）
 *
 * <p>此 Bean 不受 app.llm.provider 开关影响，始终创建。
 */
@Configuration
public class EmbeddingModelConfig {

    private final Environment env;

    public EmbeddingModelConfig(Environment env) {
        this.env = env;
    }

    @Bean
    public EmbeddingModel embeddingModel() {
        String apiKey = env.getProperty("rag.embedding.api-key", "");
        String baseUrl = env.getProperty("rag.embedding.base-url", "https://api.siliconflow.cn/v1");
        String model = env.getProperty("rag.embedding.model", "BAAI/bge-m3");
        int dimensions = Integer.parseInt(env.getProperty("rag.embedding.dimensions", "1024"));

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
