package com.springairag.core.service;

import com.springairag.core.alertdelivery.AlertNotificationAttemptResult;
import com.springairag.core.alertdelivery.AlertNotificationAttemptResult.Outcome;
import com.springairag.core.alertdelivery.AlertNotificationPayload;
import com.springairag.core.config.NotificationConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DingTalk 路由与重试中断长尾（Batch 643，JaCoCo 驱动）：禁用 /
 * 类型不匹配配置的路由跳过、无可达路由的 PERMANENT_CONFIGURATION
 * 兜底、sendToDingTalk 永久失败即中止重试、退避休眠被中断恢复、
 * 429 错误响应的 Retry-After 头解析（含缺失头分支）。
 */
@ExtendWith(MockitoExtension.class)
class DingTalkRoutingInterruptTailTest {

    @Mock
    private RestTemplateBuilder restTemplateBuilder;
    @Mock
    private RestTemplate restTemplate;

    private NotificationConfig notificationConfig;
    private DingTalkNotificationService service;

    @BeforeEach
    void setUp() {
        notificationConfig = new NotificationConfig();
        notificationConfig.setEnabled(true);
        when(restTemplateBuilder.requestFactory(any(Supplier.class)))
                .thenReturn(restTemplateBuilder);
        when(restTemplateBuilder.connectTimeout(Duration.ofSeconds(5)))
                .thenReturn(restTemplateBuilder);
        when(restTemplateBuilder.readTimeout(Duration.ofSeconds(10)))
                .thenReturn(restTemplateBuilder);
        when(restTemplateBuilder.build()).thenReturn(restTemplate);
        service = new DingTalkNotificationService(
                notificationConfig, restTemplateBuilder);
    }

    private NotificationConfig.DingTalkConfig config(
            String webhookUrl, boolean enabled, List<String> alertTypes) {
        NotificationConfig.DingTalkConfig config =
                new NotificationConfig.DingTalkConfig();
        config.setWebhookUrl(webhookUrl);
        config.setEnabled(enabled);
        config.setAlertTypes(alertTypes);
        notificationConfig.getDingtalk().add(config);
        return config;
    }

    private AlertNotificationPayload payload() {
        return new AlertNotificationPayload(
                UUID.randomUUID(), "THRESHOLD_HIGH", "alert-1",
                "warning", "message", Map.of("k", "v"), false);
    }

    @Test
    void skippedConfigsFallThroughToMatchingRoute() {
        config("https://oapi.example/disabled", false, List.of("THRESHOLD_HIGH"));
        config("https://oapi.example/mismatch", true, List.of("OTHER_TYPE"));
        config("https://oapi.example/robot", true, List.of("THRESHOLD_HIGH"));
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"errcode\":0}"));

        AlertNotificationAttemptResult result = service.deliver(payload());

        assertEquals(Outcome.SUCCESS, result.outcome());
    }

    @Test
    void noViableRouteFallsBackToPermanentConfiguration() {
        // 唯一配置类型不匹配 → 循环空转 → 兜底 PERMANENT_CONFIGURATION。
        config("https://oapi.example/mismatch", true, List.of("OTHER_TYPE"));

        AlertNotificationAttemptResult result = service.deliver(payload());

        assertEquals(Outcome.PERMANENT_FAILURE, result.outcome());
        assertEquals("PERMANENT_CONFIGURATION", result.errorCode());
    }

    @Test
    void permanentFailureAbortsRetryLoopInSendAlert() {
        config("", true, List.of("THRESHOLD_HIGH"));

        CompletableFuture<Boolean> future = service.sendAlert(
                "THRESHOLD_HIGH", "alert-1", "warning", "message",
                Map.of());

        assertEquals(Boolean.FALSE, future.join());
        verify(restTemplate, times(0))
                .postForEntity(anyString(), any(HttpEntity.class),
                        eq(String.class));
    }

    @Test
    void interruptedBackoffStopsRetriesAndKeepsInterruptFlag() {
        config("https://oapi.example/robot", true, List.of("THRESHOLD_HIGH"));
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class),
                eq(String.class)))
                .thenThrow(new ResourceAccessException("网络不可达"));
        Thread.currentThread().interrupt();

        CompletableFuture<Boolean> future = service.sendAlert(
                "THRESHOLD_HIGH", "alert-1", "warning", "message",
                Map.of());

        assertEquals(Boolean.FALSE, future.join());
        // 只应尝试一次：退避被中断后立即放弃后续重试。
        verify(restTemplate, times(1))
                .postForEntity(anyString(), any(HttpEntity.class),
                        eq(String.class));
        assertTrue(Thread.interrupted(), "中断标志应保留供调用方感知");
    }

    @Test
    void rateLimitResponseReadsRetryAfterHeader() {
        config("https://oapi.example/robot", true, List.of("THRESHOLD_HIGH"));
        HttpHeaders headers = new HttpHeaders();
        headers.set("Retry-After", "30");
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class),
                eq(String.class)))
                .thenThrow(HttpClientErrorException.create(
                        HttpStatus.TOO_MANY_REQUESTS, "429",
                        headers, new byte[0], null));

        AlertNotificationAttemptResult result = service.deliver(payload());

        assertEquals(Outcome.TRANSIENT_FAILURE, result.outcome());
        assertEquals("TRANSIENT_RATE_LIMIT", result.errorCode());
    }

    @Test
    void rateLimitResponseWithoutHeadersStillClassified() {
        config("https://oapi.example/robot", true, List.of("THRESHOLD_HIGH"));
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class),
                eq(String.class)))
                .thenThrow(HttpClientErrorException.create(
                        HttpStatus.TOO_MANY_REQUESTS, "429",
                        null, new byte[0], null));

        AlertNotificationAttemptResult result = service.deliver(payload());

        assertEquals(Outcome.TRANSIENT_FAILURE, result.outcome());
        assertEquals("TRANSIENT_RATE_LIMIT", result.errorCode());
    }
}
