package com.springairag.core.config;

import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import com.springairag.core.admission.DependencyCallContext;
import com.springairag.core.admission.DependencyCallException;
import com.springairag.core.resilience.UpstreamFailureClassifier;
import com.springairag.core.resilience.UpstreamResponseErrorHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.RetryContext;
import org.springframework.retry.backoff.BackOffContext;
import org.springframework.retry.backoff.BackOffInterruptedException;
import org.springframework.retry.backoff.BackOffPolicy;
import org.springframework.retry.backoff.Sleeper;
import org.springframework.retry.policy.ExceptionClassifierRetryPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;

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

    @Value("${rag.embedding.request-dimensions:${RAG_EMBEDDING_REQUEST_DIMENSIONS:false}}")
    private boolean requestDimensions;

    @Value("${rag.embedding.retry-max-attempts:${RAG_EMBEDDING_RETRY_MAX_ATTEMPTS:3}}")
    private int retryMaxAttempts;

    @Value("${rag.embedding.request-timeout-ms:${RAG_EMBEDDING_REQUEST_TIMEOUT_MS:30000}}")
    private int requestTimeoutMs = 30_000;

    @Bean(name = "embeddingModel")
    public EmbeddingModel embeddingModel() {
        return createModel(baseUrl, apiKey, model, dimensions, requestDimensions,
                retryMaxAttempts, requestTimeoutMs);
    }

    public EmbeddingModel createModel(RagEmbeddingProfileProperties profile) {
        if (profile == null) {
            throw new IllegalArgumentException("Embedding Profile configuration is required");
        }
        return createModel(
                profile.getBaseUrl(),
                profile.getApiKey(),
                profile.getModel(),
                profile.getDimensions(),
                profile.isRequestDimensions(),
                profile.getRetryMaxAttempts(),
                requestTimeoutMs);
    }

    private EmbeddingModel createModel(
            String configuredBaseUrl,
            String configuredApiKey,
            String configuredModel,
            int configuredDimensions,
            boolean configuredRequestDimensions,
            int configuredRetryMaxAttempts,
            int configuredRequestTimeoutMs) {
        // base-url must NOT include /v1 (OpenAiApi appends /v1/embeddings)
        String url = configuredBaseUrl != null
                ? configuredBaseUrl.replaceAll("/+$", "").replaceAll("/v1$", "")
                : "https://api.siliconflow.cn";
        boolean apiKeyConfigured = configuredApiKey != null && !configuredApiKey.isBlank();

        org.slf4j.Logger logger =
                org.slf4j.LoggerFactory.getLogger(EmbeddingModelConfig.class);
        logger.info("Creating EmbeddingModel: baseUrl={}, model={}, apiKeyConfigured={}, dimensions={}",
                url, configuredModel, apiKeyConfigured, configuredDimensions);
        if (!apiKeyConfigured) {
            throw new IllegalStateException(
                    "Embedding API key is not configured for the selected Profile");
        }
        if (configuredRequestTimeoutMs < 100 || configuredRequestTimeoutMs > 120_000) {
            throw new IllegalArgumentException(
                    "rag.embedding.request-timeout-ms must be between 100 and 120000");
        }

        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(url)
                .apiKey(configuredApiKey)
                .restClientBuilder(org.springframework.web.client.RestClient.builder()
                        .requestFactory(new org.springframework.http.client.SimpleClientHttpRequestFactory() {
                            @Override
                            protected void prepareConnection(java.net.HttpURLConnection connection, String method)
                                    throws java.io.IOException {
                                super.prepareConnection(connection, method);
                                int timeout = (int) Math.max(1, Math.min(
                                        configuredRequestTimeoutMs,
                                        DependencyCallContext.remainingMillis()));
                                connection.setConnectTimeout(timeout);
                                connection.setReadTimeout(timeout);
                            }
                        }))
                // 与 chat 路径共用同一错误映射：429/401/403 不能被 Spring AI 默认
                // handler 压成 NonTransientAiException，否则会被误判成“请求被拒绝”，
                // 拿不到 Retry-After，也无法区分限流。
                .responseErrorHandler(new UpstreamResponseErrorHandler())
                .build();

        // Some OpenAI-compatible embedding providers reject the dimensions parameter.
        // Only set dimensions when explicitly required by a provider that supports it.
        OpenAiEmbeddingOptions.Builder opt = OpenAiEmbeddingOptions.builder().model(configuredModel);
        if (configuredRequestDimensions) {
            EmbeddingVectorColumns.columnFor(configuredDimensions);
            opt.dimensions(configuredDimensions);
        }
        return new OpenAiEmbeddingModel(
                openAiApi,
                MetadataMode.EMBED,
                opt.build(),
                embeddingRetryTemplate(configuredRetryMaxAttempts));
    }

    private RetryTemplate embeddingRetryTemplate(int configuredRetryMaxAttempts) {
        if (configuredRetryMaxAttempts < 1 || configuredRetryMaxAttempts > 10) {
            throw new IllegalArgumentException(
                    "rag.embedding.retry-max-attempts must be between 1 and 10");
        }
        RetryTemplate template = new RetryTemplate();
        template.setRetryPolicy(embeddingRetryPolicy(configuredRetryMaxAttempts));
        template.setBackOffPolicy(new EmbeddingBackOffPolicy());
        template.setThrowLastExceptionOnExhausted(true);
        return template;
    }

    /**
     * 只重试 {@link UpstreamFailureClassifier} 判定为可重试的上游失败。
     *
     * <p>Spring AI 的默认错误处理器会把 429 归为 {@code NonTransientAiException}，
     * 如果沿用“按异常类型重试”，限流反而不会重试、而 5xx 会。这里统一按仓库的
     * 失败分类决策，包私有便于单测直接驱动。
     */
    static ExceptionClassifierRetryPolicy embeddingRetryPolicy(int maxAttempts) {
        ExceptionClassifierRetryPolicy policy = new ExceptionClassifierRetryPolicy();
        SimpleRetryPolicy retryable = new SimpleRetryPolicy(maxAttempts);
        policy.setExceptionClassifier(throwable ->
                isRetryableUpstreamFailure(throwable) ? retryable : noRetry());
        return policy;
    }

    static boolean isRetryableUpstreamFailure(Throwable throwable) {
        return UpstreamFailureClassifier.classify(throwable).retryable();
    }

    private static SimpleRetryPolicy noRetry() {
        return new SimpleRetryPolicy() {
            @Override
            public boolean canRetry(RetryContext context) {
                return false;
            }
        };
    }

    /** 指数退避，并把 provider 的 Retry-After 作为下限；总等待受调用 deadline 约束。 */
    static final class EmbeddingBackOffPolicy implements BackOffPolicy {
        static final long INITIAL_INTERVAL_MS = 2_000;
        static final long MAX_INTERVAL_MS = 10_000;
        static final double MULTIPLIER = 5.0;

        private final Sleeper sleeper;

        EmbeddingBackOffPolicy() {
            this(Thread::sleep);
        }

        EmbeddingBackOffPolicy(Sleeper sleeper) {
            this.sleeper = sleeper;
        }

        @Override
        public BackOffContext start(RetryContext context) {
            return new State(context);
        }

        @Override
        public void backOff(BackOffContext backOffContext) {
            RetryContext context = ((State) backOffContext).context();
            long delay = computeDelay(context.getRetryCount(),
                    UpstreamFailureClassifier.classify(context.getLastThrowable()).retryAfterMs());
            long remaining = DependencyCallContext.remainingMillis();
            if (remaining <= delay) {
                // 不在 deadline 内等待完整退避；让上层按超时语义失败。
                throw new DependencyCallException("DEPENDENCY_TIMEOUT");
            }
            try {
                sleeper.sleep(delay);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new BackOffInterruptedException("Retry wait interrupted", error);
            }
            DependencyCallContext.remainingMillis();
        }

        static long computeDelay(int retryCount, Long retryAfterMs) {
            long exponential = (long) (INITIAL_INTERVAL_MS
                    * Math.pow(MULTIPLIER, Math.max(0, retryCount - 1)));
            long delay = Math.min(MAX_INTERVAL_MS, exponential);
            if (retryAfterMs != null) {
                if (retryAfterMs >= MAX_INTERVAL_MS) {
                    return MAX_INTERVAL_MS;
                }
                delay = Math.max(delay, retryAfterMs);
            }
            return delay;
        }

        private record State(RetryContext context) implements BackOffContext { }
    }
}
