package com.springairag.core.config;

import org.junit.jupiter.api.Test;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.RetryContext;
import org.springframework.retry.support.RetryTemplate;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link RetryConfig}.
 */
class RetryConfigTest {

    @Test
    void retryTemplateIsCreatedWithCorrectDefaults() {
        RagRetryProperties props = new RagRetryProperties();
        props.setEnabled(true);
        props.setMaxAttempts(3);
        props.setInitialBackoffMs(1_000);
        props.setMaxBackoffMs(10_000);
        props.setBackoffMultiplier(2.0);

        RetryConfig config = new RetryConfig(props);
        RetryTemplate template = config.retryTemplate();

        assertNotNull(template);
    }

    @Test
    void retryExecutesSuccessfullyOnFirstAttempt() throws Exception {
        RagRetryProperties props = new RagRetryProperties();
        props.setEnabled(true);
        props.setMaxAttempts(3);
        props.setInitialBackoffMs(100);
        props.setMaxBackoffMs(500);
        props.setBackoffMultiplier(2.0);

        RetryConfig config = new RetryConfig(props);
        RetryTemplate template = config.retryTemplate();

        AtomicInteger attempts = new AtomicInteger(0);
        String result = template.execute(status -> {
            attempts.incrementAndGet();
            return "success";
        });

        assertEquals("success", result);
        assertEquals(1, attempts.get());
    }

    @Test
    void retryTemplateDisabledDoesNotRetry() throws Exception {
        RagRetryProperties props = new RagRetryProperties();
        props.setEnabled(false);
        props.setMaxAttempts(3);

        RetryConfig config = new RetryConfig(props);
        RetryTemplate template = config.retryTemplate();

        AtomicInteger attempts = new AtomicInteger(0);
        String result = template.execute(status -> {
            attempts.incrementAndGet();
            return "done";
        });

        assertEquals("done", result);
        assertEquals(1, attempts.get());
    }

    @Test
    void retryRetriesOnResourceAccessException() throws Exception {
        RagRetryProperties props = new RagRetryProperties();
        props.setEnabled(true);
        props.setMaxAttempts(3);
        props.setInitialBackoffMs(10);
        props.setMaxBackoffMs(50);
        props.setBackoffMultiplier(2.0);
        props.setRetryOnConnectTimeout(true);
        props.setRetryOnReadTimeout(true);

        RetryConfig config = new RetryConfig(props);
        RetryTemplate template = config.retryTemplate();

        AtomicInteger attempts = new AtomicInteger(0);
        String result = template.execute(status -> {
            int attempt = attempts.incrementAndGet();
            if (attempt < 3) {
                throw new org.springframework.web.client.ResourceAccessException(
                        "Connect timed out", null);
            }
            return "recovered";
        });

        assertEquals("recovered", result);
        assertEquals(3, attempts.get());
    }

    @Test
    void retryDoesNotRetryOnClientErrors() throws Exception {
        RagRetryProperties props = new RagRetryProperties();
        props.setEnabled(true);
        props.setMaxAttempts(3);
        props.setInitialBackoffMs(10);
        props.setMaxBackoffMs(50);
        props.setBackoffMultiplier(2.0);
        props.setRetryOnRateLimit(false);

        RetryConfig config = new RetryConfig(props);
        RetryTemplate template = config.retryTemplate();

        AtomicInteger attempts = new AtomicInteger(0);
        Exception exception = assertThrows(Exception.class, () -> {
            template.execute(status -> {
                attempts.incrementAndGet();
                throw new org.springframework.web.client.HttpClientErrorException(
                        org.springframework.http.HttpStatus.BAD_REQUEST, "Bad Request");
            });
        });

        assertEquals(1, attempts.get());
        // The original HttpClientErrorException should be the cause of the exhausted exception
        Throwable rootCause = exception;
        while (rootCause.getCause() != null && rootCause.getCause() != rootCause) {
            rootCause = rootCause.getCause();
        }
        assertTrue(rootCause instanceof org.springframework.web.client.HttpClientErrorException,
                "Expected HttpClientErrorException as root cause but got: " + rootCause.getClass().getName());
    }

    @Test
    void retryRetriesOnRateLimitWhenEnabled() throws Exception {
        RagRetryProperties props = new RagRetryProperties();
        props.setEnabled(true);
        props.setMaxAttempts(3);
        props.setInitialBackoffMs(10);
        props.setMaxBackoffMs(50);
        props.setBackoffMultiplier(2.0);
        props.setRetryOnRateLimit(true);

        RetryConfig config = new RetryConfig(props);
        RetryTemplate template = config.retryTemplate();

        AtomicInteger attempts = new AtomicInteger(0);
        String result = template.execute(status -> {
            int attempt = attempts.incrementAndGet();
            if (attempt < 2) {
                throw new org.springframework.web.client.HttpClientErrorException(
                        org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, "Rate Limited");
            }
            return "rate-limit-lifted";
        });

        assertEquals("rate-limit-lifted", result);
        assertEquals(2, attempts.get());
    }

    @Test
    void retryRetriesOnServiceUnavailable() throws Exception {
        RagRetryProperties props = new RagRetryProperties();
        props.setEnabled(true);
        props.setMaxAttempts(3);
        props.setInitialBackoffMs(10);
        props.setMaxBackoffMs(50);
        props.setBackoffMultiplier(2.0);
        props.setRetryOnServiceUnavailable(true);

        RetryConfig config = new RetryConfig(props);
        RetryTemplate template = config.retryTemplate();

        AtomicInteger attempts = new AtomicInteger(0);
        String result = template.execute(status -> {
            int attempt = attempts.incrementAndGet();
            if (attempt < 3) {
                throw new org.springframework.web.client.HttpServerErrorException(
                        org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "Service Unavailable");
            }
            return "available-again";
        });

        assertEquals("available-again", result);
        assertEquals(3, attempts.get());
    }
}
