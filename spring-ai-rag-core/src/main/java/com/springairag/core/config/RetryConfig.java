package com.springairag.core.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.RetryContext;
import org.springframework.retry.policy.ExceptionClassifierRetryPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;


/**
 * Retry configuration for LLM API calls.
 *
 * <p>Provides a configured {@link RetryTemplate} with exponential backoff
 * for transient failures (timeouts, rate limits, 5xx errors).
 *
 * <p>Retryable exceptions:
 * <ul>
 *   <li>{@link ResourceAccessException} — timeouts, connection refused</li>
 *   <li>{@link HttpServerErrorException} 503 — service unavailable
 *       (仅当 retry-on-service-unavailable 开启)</li>
 *   <li>{@link HttpClientErrorException} 429 — rate limited</li>
 * </ul>
 *
 * <p>NOT retried:
 * <ul>
 *   <li>4xx client errors (400, 401, 403, 404) — not transient</li>
 *   <li>Circuit breaker open — handled separately by {@link com.springairag.core.resilience.LlmCircuitBreaker}</li>
 * </ul>
 *
 * @see RagRetryProperties
 */
@Configuration
@EnableConfigurationProperties(RagRetryProperties.class)
public class RetryConfig {

    private static final Logger log = LoggerFactory.getLogger(RetryConfig.class);

    private final RagRetryProperties properties;

    public RetryConfig(RagRetryProperties properties) {
        this.properties = properties;
    }

    @Bean
    public RetryTemplate retryTemplate() {
        RetryTemplate retryTemplate = new RetryTemplate();

        // Exponential backoff with jitter
        retryTemplate.setBackOffPolicy(new UpstreamBackOffPolicy(properties));

        // Exception-classifying retry policy
        ExceptionClassifierRetryPolicy retryPolicy =
                exceptionClassifierRetryPolicy(properties);

        retryTemplate.setRetryPolicy(retryPolicy);
        retryTemplate.setThrowLastExceptionOnExhausted(true);

        log.info("RetryTemplate configured: enabled={}, maxAttempts={}, initialBackoff={}ms, maxBackoff={}ms, multiplier={}",
                properties.isEnabled(), properties.getMaxAttempts(),
                properties.getInitialBackoffMs(), properties.getMaxBackoffMs(),
                properties.getBackoffMultiplier());

        return retryTemplate;
    }

    /** 按异常类型分类重试策略（包私有便于测试直接驱动分类结果）。 */
    static ExceptionClassifierRetryPolicy exceptionClassifierRetryPolicy(
            RagRetryProperties properties) {
        ExceptionClassifierRetryPolicy retryPolicy = new ExceptionClassifierRetryPolicy();
        SimpleRetryPolicy simple = new SimpleRetryPolicy(properties.getMaxAttempts());
        retryPolicy.setExceptionClassifier(throwable -> {
            if (!properties.isEnabled()) {
                return notRetryable();
            }

            var details = com.springairag.core.resilience.UpstreamFailureClassifier.classify(throwable);
            if (!details.retryable()) return notRetryable();
            if ("UPSTREAM_RATE_LIMITED".equals(details.reason())) return properties.isRetryOnRateLimit() ? simple : notRetryable();
            if (Integer.valueOf(503).equals(details.upstreamStatus())) return properties.isRetryOnServiceUnavailable() ? simple : notRetryable();
            if ("UPSTREAM_TIMEOUT".equals(details.reason())) return properties.isRetryOnReadTimeout() ? simple : notRetryable();
            if ("UPSTREAM_CONNECTION".equals(details.reason())) return properties.isRetryOnConnectTimeout() ? simple : notRetryable();
            if ("UPSTREAM_UNAVAILABLE".equals(details.reason())) return simple;

            return notRetryable();
        });

        return retryPolicy;
    }

    private static SimpleRetryPolicy notRetryable() {
        return new SimpleRetryPolicy() {
            @Override
            public boolean canRetry(RetryContext context) {
                return false;
            }
        };
    }
}
