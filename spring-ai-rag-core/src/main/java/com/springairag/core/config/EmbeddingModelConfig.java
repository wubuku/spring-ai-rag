package com.springairag.core.config;

import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import com.springairag.core.admission.DependencyCallContext;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.ResourceAccessException;

import java.time.Duration;

/**
 * EmbeddingModel Configuration
 * Uses an OpenAI-compatible embedding API, independent of app.llm.provider.
 *
 * <p>Bean name {@code embeddingModel} so PerformanceConfig can wrap it via @Qualifier("embeddingModel").
 */
@Configuration
public class EmbeddingModelConfig {

    @Value("${rag.embedding.api-key:}")
    private String apiKey;

    @Value("${rag.embedding.base-url:https://api.siliconflow.cn}")
    private String baseUrl;

    @Value("${rag.embedding.model:BAAI/bge-m3}")
    private String model;

    @Value("${rag.embedding.dimensions:1024}")
    private int dimensions;

    @Value("${rag.embedding.retry-max-attempts:${RAG_EMBEDDING_RETRY_MAX_ATTEMPTS:3}}")
    private int retryMaxAttempts;

    @Bean(name = "embeddingModel")
    public EmbeddingModel embeddingModel() {
        // base-url must NOT include /v1 (OpenAiApi appends /v1/embeddings)
        String url = baseUrl != null
                ? baseUrl.replaceAll("/+$", "").replaceAll("/v1$", "")
                : "https://api.siliconflow.cn";
        boolean apiKeyConfigured = apiKey != null && !apiKey.isBlank();

        org.slf4j.Logger logger =
                org.slf4j.LoggerFactory.getLogger(EmbeddingModelConfig.class);
        logger.info("Creating EmbeddingModel: baseUrl={}, model={}, apiKeyConfigured={}, dimensions={}",
                url, model, apiKeyConfigured, dimensions);
        if (!apiKeyConfigured) {
            throw new IllegalStateException(
                    "Embedding API key is not configured. Set RAG_EMBEDDING_API_KEY "
                            + "or rag.embedding.api-key before starting the application.");
        }

        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(url)
                .apiKey(apiKey)
                .restClientBuilder(org.springframework.web.client.RestClient.builder()
                        .requestFactory(new org.springframework.http.client.SimpleClientHttpRequestFactory() {
                            @Override
                            protected void prepareConnection(java.net.HttpURLConnection connection, String method)
                                    throws java.io.IOException {
                                super.prepareConnection(connection, method);
                                int timeout = (int) Math.min(30000, DependencyCallContext.remainingMillis());
                                connection.setConnectTimeout(timeout);
                                connection.setReadTimeout(timeout);
                            }
                        }))
                .build();

        // Some OpenAI-compatible embedding providers reject the dimensions parameter.
        // Only set dimensions when explicitly required by a provider that supports it.
        OpenAiEmbeddingOptions.Builder opt = OpenAiEmbeddingOptions.builder().model(model);
        // Keep field for docs/DB VECTOR(1024) consistency, but do not send it by default.
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
        RetryTemplate template = RetryTemplate.builder()
                .maxAttempts(retryMaxAttempts)
                .retryOn(TransientAiException.class)
                .retryOn(ResourceAccessException.class)
                .exponentialBackoff(
                        Duration.ofSeconds(2),
                        5.0,
                        Duration.ofMinutes(3))
                .build();
        org.springframework.retry.backoff.ExponentialBackOffPolicy backoff =
                new org.springframework.retry.backoff.ExponentialBackOffPolicy();
        backoff.setInitialInterval(2000);
        backoff.setMultiplier(5);
        backoff.setMaxInterval(180000);
        backoff.setSleeper(interval -> {
            long remaining = DependencyCallContext.remainingMillis();
            Thread.sleep(Math.min(interval, remaining));
            DependencyCallContext.remainingMillis();
        });
        template.setBackOffPolicy(backoff);
        template.registerListener(new org.springframework.retry.RetryListener() {
            @Override
            public <Result, Failure extends Throwable> boolean open(
                    org.springframework.retry.RetryContext context,
                    org.springframework.retry.RetryCallback<Result, Failure> callback) {
                DependencyCallContext.remainingMillis();
                return true;
            }

            @Override
            public <Result, Failure extends Throwable> void onError(
                    org.springframework.retry.RetryContext context,
                    org.springframework.retry.RetryCallback<Result, Failure> callback, Throwable failure) {
                DependencyCallContext.remainingMillis();
            }
        });
        return template;
    }
}
