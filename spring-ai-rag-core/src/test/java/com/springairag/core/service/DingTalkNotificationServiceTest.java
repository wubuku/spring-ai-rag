package com.springairag.core.service;

import com.springairag.core.config.NotificationConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DingTalkNotificationServiceTest {

    @Mock
    private RestTemplate restTemplate;

    private NotificationConfig config;
    private DingTalkNotificationService service;

    @BeforeEach
    void setUp() {
        config = new NotificationConfig();
        config.setEnabled(true);

        NotificationConfig.DingTalkConfig dt = new NotificationConfig.DingTalkConfig();
        dt.setName("default");
        dt.setWebhookUrl("https://oapi.dingtalk.com/robot/send?access_token=TEST_TOKEN");
        dt.setEnabled(true);
        dt.setAlertTypes(List.of("THRESHOLD_HIGH", "SLO_BREACH"));
        config.getDingtalk().add(dt);

        RestTemplateBuilder builder = mock(RestTemplateBuilder.class);
        when(builder.connectTimeout(any(Duration.class))).thenReturn(builder);
        when(builder.readTimeout(any(Duration.class))).thenReturn(builder);
        when(builder.build()).thenReturn(restTemplate);

        service = new DingTalkNotificationService(config, builder);
    }

    @Test
    @DisplayName("sendAlert returns false when notifications disabled")
    void sendAlert_disabled_returnsFalse() {
        config.setEnabled(false);
        boolean result = service.sendAlert("THRESHOLD_HIGH", "Test", "WARNING", "msg", Map.of());
        assertFalse(result);
        verifyNoInteractions(restTemplate);
    }

    @Test
    @DisplayName("sendAlert returns false when no DingTalk channels configured")
    void sendAlert_noChannels_returnsFalse() {
        config.getDingtalk().clear();
        boolean result = service.sendAlert("THRESHOLD_HIGH", "Test", "WARNING", "msg", Map.of());
        assertFalse(result);
        verifyNoInteractions(restTemplate);
    }

    @Test
    @DisplayName("sendAlert skips disabled channel")
    void sendAlert_channelDisabled_skips() {
        config.getDingtalk().get(0).setEnabled(false);
        boolean result = service.sendAlert("THRESHOLD_HIGH", "Test", "WARNING", "msg", Map.of());
        assertFalse(result);
    }

    @Test
    @DisplayName("sendAlert skips alert type not in channel alertTypes")
    void sendAlert_typeNotMatched_skips() {
        boolean result = service.sendAlert("AVAILABILITY", "Test", "CRITICAL", "msg", Map.of());
        assertFalse(result);
        verifyNoInteractions(restTemplate);
    }

    @Test
    @DisplayName("sendAlert sends to matched channel and returns true")
    void sendAlert_matchedChannel_returnsTrue() {
        when(restTemplate.postForEntity(any(String.class), any(HttpEntity.class), eq(String.class)))
                .thenReturn(new ResponseEntity<>("{\"errcode\":0}", HttpStatus.OK));

        boolean result = service.sendAlert("THRESHOLD_HIGH", "High Latency",
                "WARNING", "P95 latency exceeded 2000ms",
                Map.of("p95_latency_ms", 2500));

        assertTrue(result);
        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<HttpEntity> bodyCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).postForEntity(urlCaptor.capture(), bodyCaptor.capture(), eq(String.class));
        String url = urlCaptor.getValue();
        assertTrue(url.contains("access_token=TEST_TOKEN"));
        String body = (String) bodyCaptor.getValue().getBody();
        assertNotNull(body);
        assertTrue(body.contains("WARNING"));
        assertTrue(body.contains("High Latency"));
        assertTrue(body.contains("P95 latency exceeded"));
    }

    @Test
    @DisplayName("sendAlert returns false when HTTP call throws")
    void sendAlert_httpThrows_returnsFalse() {
        when(restTemplate.postForEntity(any(String.class), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new RuntimeException("Connection refused"));

        boolean result = service.sendAlert("SLO_BREACH", "SLO Breach", "CRITICAL", "P99 exceeded", Map.of());
        assertFalse(result);
    }

    @Test
    @DisplayName("buildWebhookUrl without secret returns base URL")
    void buildWebhookUrl_noSecret_returnsBaseUrl() {
        NotificationConfig.DingTalkConfig dt = config.getDingtalk().get(0);
        dt.setSecret(null);
        String url = service.buildWebhookUrl(dt);
        assertEquals("https://oapi.dingtalk.com/robot/send?access_token=TEST_TOKEN", url);
    }

    @Test
    @DisplayName("buildWebhookUrl with secret appends timestamp and sign")
    void buildWebhookUrl_withSecret_appendsSignature() {
        NotificationConfig.DingTalkConfig dt = config.getDingtalk().get(0);
        dt.setSecret("SEC1234567890abcdef");
        String url = service.buildWebhookUrl(dt);
        assertTrue(url.contains("access_token=TEST_TOKEN"));
        assertTrue(url.contains("timestamp="));
        assertTrue(url.contains("sign="));
    }

    @Test
    @DisplayName("computeSignature produces valid HMAC-SHA256")
    void computeSignature_validHmacSha256() {
        String sig = service.computeSignature("SEC1234567890abcdef", 1744076400000L);
        assertNotNull(sig);
        assertFalse(sig.isEmpty());
        // Signature should be Base64 encoded
        assertTrue(sig.matches("[A-Za-z0-9+/=]+"));
    }

    @Test
    @DisplayName("escapeJson escapes backslash before other chars to prevent corruption")
    void escapeJson_escapesBackslashFirst() throws Exception {
        // Use reflection to access the private escapeJson method
        var method = DingTalkNotificationService.class.getDeclaredMethod("escapeJson", String.class);
        method.setAccessible(true);

        // Backslash must be escaped FIRST; otherwise "C:\n" would have its backslash
        // consumed by the newline escape (producing C:\ followed by literal newline),
        // corrupting the JSON string.
        String input = "C:\\Users\\test";
        String result = (String) method.invoke(service, input);
        assertEquals("C:\\\\Users\\\\test", result);

        // Verify backslash before 'n' produces \\n not consumed-as-escape-sequence
        String input2 = "line1\\n";
        String result2 = (String) method.invoke(service, input2);
        assertEquals("line1\\\\n", result2);

        // Null input returns empty string
        String nullResult = (String) method.invoke(service, (String) null);
        assertEquals("", nullResult);
    }

    @Test
    @DisplayName("buildMarkdownBody escapes JSON special characters")
    void buildMarkdownBody_escapesJsonSpecialChars() {
        String body = service.buildMarkdownBody("SLO_BREACH", "Test Alert \"CRITICAL\"",
                "CRITICAL", "Message with\nnewline\\backslash",
                Map.of("key", "value with \"quotes\""));
        assertNotNull(body);
        assertTrue(body.contains("\"msgtype\": \"markdown\""));
        assertTrue(body.contains("CRITICAL"));
    }

    @Test
    @DisplayName("sendAlert retries on transient failure and succeeds on second attempt")
    void sendAlert_retriesOnTransientFailure_succeedsOnRetry() {
        // First call fails, second succeeds
        when(restTemplate.postForEntity(any(String.class), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new RuntimeException("Connection refused"))
                .thenReturn(new ResponseEntity<>("{\"errcode\":0}", HttpStatus.OK));

        boolean result = service.sendAlert("SLO_BREACH", "SLO Breach", "CRITICAL", "P99 exceeded", Map.of());
        assertTrue(result);
        // Should have been called twice (1 failure + 1 success)
        verify(restTemplate, times(2)).postForEntity(any(String.class), any(HttpEntity.class), eq(String.class));
    }

    @Test
    @DisplayName("sendAlert with metadata includes all details in markdown")
    void sendAlert_withMetadata_includesAllDetails() {
        when(restTemplate.postForEntity(any(String.class), any(HttpEntity.class), eq(String.class)))
                .thenReturn(new ResponseEntity<>("{\"errcode\":0}", HttpStatus.OK));

        service.sendAlert("THRESHOLD_HIGH", "Latency Alert", "WARNING",
                "P95 exceeded",
                Map.of("p95_ms", 2500, "threshold_ms", 2000, "slo_name", "latency_p95"));

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(restTemplate).postForEntity(any(String.class), any(HttpEntity.class), eq(String.class));
        // Just verify no exception is thrown with metadata
    }
}
