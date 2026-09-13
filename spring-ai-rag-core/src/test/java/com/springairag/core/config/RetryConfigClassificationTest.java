package com.springairag.core.config;

import org.junit.jupiter.api.Test;
import org.springframework.retry.RetryContext;
import org.springframework.retry.policy.ExceptionClassifierRetryPolicy;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RetryTemplate 异常分类矩阵（Batch 366）：直接驱动
 * ExceptionClassifierRetryPolicy 的分类结果——重试开关、读/连接
 * 超时、通用网络错误、503、其他 5xx、429、非瞬态 4xx。
 */
class RetryConfigClassificationTest {

    private ExceptionClassifierRetryPolicy policy(RagRetryProperties props) {
        return RetryConfig.exceptionClassifierRetryPolicy(props);
    }

    private RagRetryProperties props() {
        RagRetryProperties props = new RagRetryProperties();
        props.setMaxAttempts(3);
        return props;
    }

    private boolean canRetryAfterFailure(
            ExceptionClassifierRetryPolicy policy, RuntimeException failure) {
        RetryContext context = policy.open(null);
        policy.registerThrowable(context, failure);
        return policy.canRetry(context);
    }

    @Test
    void disabledRetryNeverClassifiesAsRetryable() {
        RagRetryProperties props = props();
        props.setEnabled(false);
        var policy = policy(props);

        assertFalse(canRetryAfterFailure(policy,
                new ResourceAccessException("Read timed out")));
        assertFalse(canRetryAfterFailure(policy,
                new HttpServerErrorException(
                        org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                        "Service Unavailable")));
    }

    @Test
    void readTimeoutIsRetryableWhenFlagOn() {
        assertTrue(canRetryAfterFailure(
                policy(props()),
                new ResourceAccessException("Read timed out executing PUT")));
    }

    @Test
    void readTimeoutFallsBackToGenericNetworkErrorFlag() {
        // 读超时关闭、通用网络错误开关（connect）开启 → 仍可重试。
        RagRetryProperties props = props();
        props.setRetryOnReadTimeout(false);
        assertTrue(canRetryAfterFailure(
                policy(props),
                new ResourceAccessException("Read timed out")));

        // 两者均关闭 → 不可重试。
        props.setRetryOnConnectTimeout(false);
        assertFalse(canRetryAfterFailure(
                policy(props),
                new ResourceAccessException("Read timed out")));
    }

    @Test
    void connectTimeoutFollowsItsOwnFlag() {
        RagRetryProperties props = props();
        props.setRetryOnConnectTimeout(false);
        assertFalse(canRetryAfterFailure(
                policy(props),
                new ResourceAccessException("Connect timed out")));

        props.setRetryOnConnectTimeout(true);
        assertTrue(canRetryAfterFailure(
                policy(props),
                new ResourceAccessException("Connect timed out")));
    }

    @Test
    void genericNetworkErrorFollowsConnectTimeoutFlag() {
        RagRetryProperties props = props();
        props.setRetryOnConnectTimeout(true);
        assertTrue(canRetryAfterFailure(
                policy(props),
                new ResourceAccessException("Connection refused")));

        props.setRetryOnConnectTimeout(false);
        assertFalse(canRetryAfterFailure(
                policy(props),
                new ResourceAccessException("Connection refused")));
    }

    @Test
    void http503RemainsRetryableViaGenericServerErrorCodePath() {
        // 行为注记：503 关闭专属开关后仍可重试——通用 `status >= 500`
        // 分支在其之后再次命中，专属开关被遮蔽（现状语义，非笔误）。
        RagRetryProperties props = props();
        assertTrue(canRetryAfterFailure(
                policy(props),
                new HttpServerErrorException(
                        org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                        "Service Unavailable")));

        props.setRetryOnServiceUnavailable(false);
        assertTrue(canRetryAfterFailure(
                policy(props),
                new HttpServerErrorException(
                        org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                        "Service Unavailable")));
    }

    @Test
    void otherServerErrorsAreRetryable() {
        assertTrue(canRetryAfterFailure(
                policy(props()),
                new HttpServerErrorException(
                        org.springframework.http.HttpStatus.BAD_GATEWAY,
                        "Bad Gateway")));
    }

    @Test
    void http429FollowsRateLimitFlag() {
        RagRetryProperties props = props();
        assertTrue(canRetryAfterFailure(
                policy(props),
                new HttpClientErrorException(
                        org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,
                        "Too Many Requests")));

        props.setRetryOnRateLimit(false);
        assertFalse(canRetryAfterFailure(
                policy(props),
                new HttpClientErrorException(
                        org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,
                        "Too Many Requests")));
    }

    @Test
    void nonTransientClientErrorsAreNotRetried() {
        assertFalse(canRetryAfterFailure(
                policy(props()),
                new HttpClientErrorException(
                        org.springframework.http.HttpStatus.UNAUTHORIZED,
                        "Unauthorized")));
    }
}
