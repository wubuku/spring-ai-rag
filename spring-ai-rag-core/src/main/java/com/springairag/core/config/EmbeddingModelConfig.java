package com.springairag.core.config;

import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.ResourceAccessException;

import java.time.Duration;

/**
 * EmbeddingModel Configuration
 * Uses SiliconFlow API (OpenAI-compatible format), independent of app.llm.provider.
 *
 * <p>Bean name {@code embeddingModel} so PerformanceConfig can wrap it via @Qualifier("embeddingModel").
 */
@Configuration
public class EmbeddingModelConfig {

    @Value("${rag.embedding.api-key:${SILICONFLOW_API_KEY:${SPRING_AI_OPENAI_API_KEY:}}}")
    private String apiKey;

    @Value("${rag.embedding.base-url:${SILICONFLOW_URL:https://api.siliconflow.cn}}")
    private String baseUrl;

    @Value("${rag.embedding.model:BAAI/bge-m3}")
    private String model;

    @Value("${rag.embedding.dimensions:1024}")
    private int dimensions;

    @Value("${rag.embedding.retry-max-attempts:${RAG_EMBEDDING_RETRY_MAX_ATTEMPTS:10}}")
    private int retryMaxAttempts;

    @Bean(name = "embeddingModel")
    public EmbeddingModel embeddingModel() {
        // base-url must NOT include /v1 (OpenAiApi appends /v1/embeddings)
        String url = baseUrl != null
                ? baseUrl.replaceAll("/+$", "").replaceAll("/v1$", "")
                : "https://api.siliconflow.cn";

        org.slf4j.LoggerFactory.getLogger(EmbeddingModelConfig.class)
                .info("Creating EmbeddingModel: baseUrl={}, model={}, apiKey={}..., dimensions={}",
                        url, model,
                        apiKey != null && apiKey.length() > 10 ? apiKey.substring(0, 10) : "***",
                        dimensions);

        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(url)
                .apiKey(apiKey != null && !apiKey.isBlank() ? apiKey : "dummy")
                .build();

        // Note: SiliconFlow BGE-M3 rejects OpenAI-style "dimensions" (HTTP 400 code 20015).
        // Only set dimensions when explicitly required by a provider that supports it.
        OpenAiEmbeddingOptions.Builder opt = OpenAiEmbeddingOptions.builder().model(model);
        // Keep field for docs/DB VECTOR(1024) consistency, but do not send to SiliconFlow.
        return new OpenAiEmbeddingModel(
                openAiApi,
                MetadataMode.EMBED,
                opt.build(),
                embeddingRetryTemplate());
    }

    private RetryTemplate embeddingRetryTemplate() {
        if (retryMaxAttempts < 1 || retryMaxAttempts > 10) {
            throw new IllegalArgumentException(
                    "rag.embedding.retry-max-attempts must be between 1 and 10");
        }
        if (retryMaxAttempts == 10) {
            return RetryUtils.DEFAULT_RETRY_TEMPLATE;
        }
        return RetryTemplate.builder()
                .maxAttempts(retryMaxAttempts)
                .retryOn(TransientAiException.class)
                .retryOn(ResourceAccessException.class)
                .exponentialBackoff(
                        Duration.ofSeconds(2),
                        5.0,
                        Duration.ofMinutes(3))
                .build();
    }
}
