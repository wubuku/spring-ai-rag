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
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * DingTalk 投递长尾（Batch 486，JaCoCo 驱动）：deliver 的路由/
 * 可用性守卫、sendOnce 的空 webhook 早退与网络异常降级、classify
 * Response 的 429（Retry-After）/5xx/4xx/errcode 非零/非法 JSON
 * 五类判定。
 */
@ExtendWith(MockitoExtension.class)
class DingTalkDeliveryTailTest {

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

    private NotificationConfig.DingTalkConfig enabledConfig(String webhookUrl) {
        NotificationConfig.DingTalkConfig config =
                new NotificationConfig.DingTalkConfig();
        config.setWebhookUrl(webhookUrl);
        config.setEnabled(true);
        notificationConfig.getDingtalk().add(config);
        return config;
    }

    private AlertNotificationPayload payload() {
        return new AlertNotificationPayload(
                UUID.randomUUID(), "THRESHOLD_HIGH", "alert-1",
                "warning", "message", Map.of("k", "v"), false);
    }

    private AlertNotificationAttemptResult deliver() {
        return service.deliver(payload());
    }

    @Test
    void unroutedOrUnavailableIsPermanentConfiguration() {
        // 通知未启用 → 未路由且不可用 → PERMANENT_CONFIGURATION。
        AlertNotificationAttemptResult result = deliver();

        assertEquals(Outcome.PERMANENT_FAILURE, result.outcome());
        assertEquals("PERMANENT_CONFIGURATION", result.errorCode());
    }

    @Test
    void successBodyIsMappedToSuccess() {
        enabledConfig("https://oapi.example/robot");
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"errcode\":0}"));

        AlertNotificationAttemptResult result = deliver();

        assertEquals(Outcome.SUCCESS, result.outcome());
    }

    @Test
    void nonZeroErrcodeIsPermanent() {
        enabledConfig("https://oapi.example/robot");
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"errcode\":310000}"));

        AlertNotificationAttemptResult result = deliver();

        assertEquals(Outcome.PERMANENT_FAILURE, result.outcome());
        assertEquals("PERMANENT_PROVIDER_REJECTED", result.errorCode());
    }

    @Test
    void malformedBodyIsPermanent() {
        enabledConfig("https://oapi.example/robot");
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(ResponseEntity.ok("not-json"));

        AlertNotificationAttemptResult result = deliver();

        assertEquals(Outcome.PERMANENT_FAILURE, result.outcome());
        assertEquals("PERMANENT_PROVIDER_REJECTED", result.errorCode());
    }

    @Test
    void rateLimited429IsTransientWithRetryAfter() {
        enabledConfig("https://oapi.example/robot");
        HttpHeaders headers = new HttpHeaders();
        headers.set("Retry-After", "30");
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class),
                eq(String.class)))
                .thenThrow(HttpClientErrorException.create(
                        HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests",
                        headers, new byte[0], null));

        AlertNotificationAttemptResult result = deliver();

        assertEquals(Outcome.TRANSIENT_FAILURE, result.outcome());
        assertEquals("TRANSIENT_RATE_LIMIT", result.errorCode());
        assertEquals(429, result.httpStatus());
        assertEquals(Duration.ofSeconds(30), result.retryAfter());
    }

    @Test
    void server5xxIsTransient() {
        enabledConfig("https://oapi.example/robot");
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class),
                eq(String.class)))
                .thenThrow(HttpServerErrorException.create(
                        HttpStatus.BAD_GATEWAY, "Bad Gateway",
                        new HttpHeaders(), new byte[0], null));

        AlertNotificationAttemptResult result = deliver();

        assertEquals(Outcome.TRANSIENT_FAILURE, result.outcome());
        assertEquals("TRANSIENT_PROVIDER_5XX", result.errorCode());
    }

    @Test
    void client4xxIsPermanent() {
        enabledConfig("https://oapi.example/robot");
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class),
                eq(String.class)))
                .thenThrow(HttpClientErrorException.create(
                        HttpStatus.BAD_REQUEST, "Bad Request",
                        new HttpHeaders(), new byte[0], null));

        AlertNotificationAttemptResult result = deliver();

        assertEquals(Outcome.PERMANENT_FAILURE, result.outcome());
        assertEquals("PERMANENT_PROVIDER_REJECTED", result.errorCode());
    }

    @Test
    void networkFailureIsTransientNetwork() {
        enabledConfig("https://oapi.example/robot");
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class),
                eq(String.class)))
                .thenThrow(new ResourceAccessException("timeout"));

        AlertNotificationAttemptResult result = deliver();

        assertEquals(Outcome.TRANSIENT_FAILURE, result.outcome());
        assertEquals("TRANSIENT_NETWORK", result.errorCode());
    }

    @Test
    void blankWebhookConfigFallsThroughToNextChannel() {
        // 通道 1 webhook 缺失 → 早退 PERMANENT_CONFIGURATION；
        // 通道 2 正常 → 整体仍 SUCCESS。
        enabledConfig(null);
        NotificationConfig.DingTalkConfig good = enabledConfig(
                "https://oapi.example/robot");
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"errcode\":0}"));

        AlertNotificationAttemptResult result = deliver();

        assertEquals(Outcome.SUCCESS, result.outcome());
        assertEquals("default", good.getName());
    }
}
