package com.springairag.core.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.RetryContext;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.policy.ExceptionClassifierRetryPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.util.Random;

/**
 * Retry configuration for LLM API calls.
 *
 * <p>Provides a configured {@link RetryTemplate} with exponential backoff
 * for transient failures (timeouts, rate limits, 5xx errors).
 *
 * <p>Retryable exceptions:
 * <ul>
 *   <li>{@link ResourceAccessException} — timeouts, connection refused</li>
 *   <li>{@link HttpServerErrorException} 503 — service unavailable</li>
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
    private final Random random = new Random();

    public RetryConfig(RagRetryProperties properties) {
        this.properties = properties;
    }

    @Bean
    public RetryTemplate retryTemplate() {
        RetryTemplate retryTemplate = new RetryTemplate();

        // Exponential backoff with jitter
        ExponentialBackOffPolicy backOffPolicy = new ExponentialBackOffPolicy();
        backOffPolicy.setInitialInterval(properties.getInitialBackoffMs());
        backOffPolicy.setMultiplier(properties.getBackoffMultiplier());
        backOffPolicy.setMaxInterval(properties.getMaxBackoffMs());
        retryTemplate.setBackOffPolicy(backOffPolicy);

        // Exception-classifying retry policy
        ExceptionClassifierRetryPolicy retryPolicy = new ExceptionClassifierRetryPolicy();
        retryPolicy.setExceptionClassifier(throwable -> {
            if (!properties.isEnabled()) {
                // Return a policy that never retries
                return new SimpleRetryPolicy() {
                    @Override
                    public boolean canRetry(RetryContext context) {
                        return false;
                    }
                };
            }

            SimpleRetryPolicy simple = new SimpleRetryPolicy(properties.getMaxAttempts());

            // Classify the exception
            if (throwable instanceof ResourceAccessException) {
                String msg = throwable.getMessage() != null ? throwable.getMessage() : "";
                boolean isReadTimeout = msg.contains("Read timed out");
                boolean isConnectTimeout = msg.contains("Connect timed out");

                if (isReadTimeout && properties.isRetryOnReadTimeout()) {
                    log.debug("Retryable: Read timeout");
                    return simple;
                }
                if (isConnectTimeout && properties.isRetryOnConnectTimeout()) {
                    log.debug("Retryable: Connect timeout");
                    return simple;
                }
                if (properties.isRetryOnConnectTimeout()) {
                    log.debug("Retryable: ResourceAccessException (generic network error)");
                    return simple;
                }
            }

            if (throwable instanceof HttpServerErrorException httpEx) {
                int status = httpEx.getStatusCode().value();
                if (status == 503 && properties.isRetryOnServiceUnavailable()) {
                    log.debug("Retryable: HTTP 503 Service Unavailable");
                    return simple;
                }
                if (status >= 500) {
                    log.debug("Retryable: HTTP {} server error", status);
                    return simple;
                }
            }

            if (throwable instanceof HttpClientErrorException httpEx) {
                int status = httpEx.getStatusCode().value();
                if (status == 429 && properties.isRetryOnRateLimit()) {
                    log.debug("Retryable: HTTP 429 Rate Limit");
                    return simple;
                }
            }

            // Not retryable
            return new SimpleRetryPolicy() {
                @Override
                public boolean canRetry(RetryContext context) {
                    return false;
                }
            };
        });

        retryTemplate.setRetryPolicy(retryPolicy);
        retryTemplate.setThrowLastExceptionOnExhausted(true);

        log.info("RetryTemplate configured: enabled={}, maxAttempts={}, initialBackoff={}ms, maxBackoff={}ms, multiplier={}",
                properties.isEnabled(), properties.getMaxAttempts(),
                properties.getInitialBackoffMs(), properties.getMaxBackoffMs(),
                properties.getBackoffMultiplier());

        return retryTemplate;
    }
}
