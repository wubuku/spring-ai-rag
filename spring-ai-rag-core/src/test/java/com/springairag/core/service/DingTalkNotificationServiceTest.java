package com.springairag.core.service;

import com.springairag.core.config.NotificationConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DingTalkNotificationServiceTest {

    @Mock
    private NotificationConfig notificationConfig;

    @Mock
    private RestTemplateBuilder restTemplateBuilder;

    @Mock
    private RestTemplate restTemplate;

    private DingTalkNotificationService service;

    @BeforeEach
    void setUp() {
        when(restTemplateBuilder.connectTimeout(Duration.ofSeconds(5))).thenReturn(restTemplateBuilder);
        when(restTemplateBuilder.readTimeout(Duration.ofSeconds(10))).thenReturn(restTemplateBuilder);
        when(restTemplateBuilder.build()).thenReturn(restTemplate);
        service = new DingTalkNotificationService(notificationConfig, restTemplateBuilder);
    }

    // --- buildWebhookUrl tests ---

    @Test
    void buildWebhookUrl_noSecret_returnsOriginalUrl() {
        NotificationConfig.DingTalkConfig config = new NotificationConfig.DingTalkConfig();
        config.setWebhookUrl("https://oapi.dingtalk.com/robot/send?access_token=ABC");

        String url = service.buildWebhookUrl(config);

        assertEquals("https://oapi.dingtalk.com/robot/send?access_token=ABC", url);
    }

    @Test
    void buildWebhookUrl_nullSecret_returnsOriginalUrl() {
        NotificationConfig.DingTalkConfig config = new NotificationConfig.DingTalkConfig();
        config.setWebhookUrl("https://oapi.dingtalk.com/robot/send?access_token=ABC");
        config.setSecret(null);

        String url = service.buildWebhookUrl(config);

        assertEquals("https://oapi.dingtalk.com/robot/send?access_token=ABC", url);
    }

    @Test
    void buildWebhookUrl_blankSecret_returnsOriginalUrl() {
        NotificationConfig.DingTalkConfig config = new NotificationConfig.DingTalkConfig();
        config.setWebhookUrl("https://oapi.dingtalk.com/robot/send?access_token=ABC");
        config.setSecret("   ");

        String url = service.buildWebhookUrl(config);

        assertEquals("https://oapi.dingtalk.com/robot/send?access_token=ABC", url);
    }

    @Test
    void buildWebhookUrl_withSecret_appendsTimestampAndSign() {
        NotificationConfig.DingTalkConfig config = new NotificationConfig.DingTalkConfig();
        config.setWebhookUrl("https://oapi.dingtalk.com/robot/send?access_token=ABC");
        config.setSecret("SECRET123");

        String url = service.buildWebhookUrl(config);

        assertTrue(url.startsWith("https://oapi.dingtalk.com/robot/send?access_token=ABC&"));
        assertTrue(url.contains("timestamp="));
        assertTrue(url.contains("sign="));
    }

    @Test
    void buildWebhookUrl_urlWithExistingQueryParams_usesAmpersand() {
        NotificationConfig.DingTalkConfig config = new NotificationConfig.DingTalkConfig();
        config.setWebhookUrl("https://oapi.dingtalk.com/robot/send?access_token=ABC&foo=bar");
        config.setSecret("SECRET123");

        String url = service.buildWebhookUrl(config);

        assertTrue(url.contains("&timestamp="));
        assertTrue(url.contains("&sign="));
    }

    // --- computeSignature tests ---

    @Test
    void computeSignature_validSecretAndTimestamp_returnsNonEmptyBase64() {
        String signature = service.computeSignature("secret", 1234567890000L);

        assertNotNull(signature);
        assertFalse(signature.isEmpty());
        // Base64 charset check
        assertTrue(signature.matches("[A-Za-z0-9+/=]+"));
    }

    @Test
    void computeSignature_differentSecrets_produceDifferentSignatures() {
        String sig1 = service.computeSignature("secret1", 1234567890000L);
        String sig2 = service.computeSignature("secret2", 1234567890000L);

        assertNotEquals(sig1, sig2);
    }

    @Test
    void computeSignature_differentTimestamps_produceDifferentSignatures() {
        String sig1 = service.computeSignature("secret", 1234567890000L);
        String sig2 = service.computeSignature("secret", 1234567890001L);

        assertNotEquals(sig1, sig2);
    }

    @Test
    void computeSignature_sameInputs_produceSameSignature() {
        String sig1 = service.computeSignature("SECRET", 9999999999999L);
        String sig2 = service.computeSignature("SECRET", 9999999999999L);

        assertEquals(sig1, sig2);
    }

    // --- buildMarkdownBody tests ---

    @Test
    void buildMarkdownBody_basicAlert_containsExpectedJsonFields() {
        String body = service.buildMarkdownBody("THRESHOLD_HIGH", "High CPU", "CRITICAL",
                "CPU exceeded 90%", null);

        assertNotNull(body);
        // Valid JSON with expected msgtype and title (note: spaces after colons in actual output)
        assertTrue(body.contains("\"msgtype\": \"markdown\""), "Body should contain msgtype: " + body);
        assertTrue(body.contains("\"title\": \"[CRITICAL] High CPU\""), "Body should contain title: " + body);
        // JSON text field contains alert content (markdown with escaped newlines)
        assertTrue(body.contains("**Type:**"), "Body should contain **Type: " + body);
        assertTrue(body.contains("**Message:**"), "Body should contain **Message: " + body);
    }

    @Test
    void buildMarkdownBody_withMetadata_includesAllEntries() {
        Map<String, Object> metadata = Map.of(
                "collection", "prod-collection",
                "value", 95.5,
                "threshold", 90.0
        );

        String body = service.buildMarkdownBody("THRESHOLD_HIGH", "High CPU", "WARNING",
                "CPU above threshold", metadata);

        assertTrue(body.contains("**collection**: prod-collection"));
        assertTrue(body.contains("**value**: 95.5"));
        assertTrue(body.contains("**threshold**: 90.0"));
        assertTrue(body.contains("### Details"));
    }

    @Test
    void buildMarkdownBody_nullMetadata_omitsDetailsSection() {
        String body = service.buildMarkdownBody("SLO_BREACH", "SLO Breach", "ERROR",
                "p99 latency exceeded SLO", null);

        assertTrue(body.contains("**Message:**"));
        assertFalse(body.contains("### Details"));
    }

    @Test
    void buildMarkdownBody_emptyMetadata_omitsDetailsSection() {
        String body = service.buildMarkdownBody("AVAILABILITY", "Service Down", "CRITICAL",
                "Service unavailable", Map.of());

        assertFalse(body.contains("### Details"));
    }

    @Test
    void buildMarkdownBody_specialCharacters_areEscaped() {
        String body = service.buildMarkdownBody("TEST", "Test Alert", "INFO",
                "Line1\nLine2\rTab\tEnd", null);

        // After escapeJson: \n → \\n, \r → \\r, \t → \\t
        // Body contains escaped sequences, not raw control chars
        assertTrue(body.contains("\\n"), "Body should contain escaped newline \\n: " + body);
        assertTrue(body.contains("\\r"), "Body should contain escaped carriage return \\r: " + body);
        assertTrue(body.contains("\\t"), "Body should contain escaped tab \\t: " + body);
        // Original unescaped control chars should NOT be present
        assertFalse(body.contains("Line1\n"), "Body should not contain raw newline: " + body);
        assertFalse(body.contains("Tab\tEnd"), "Body should not contain raw tab: " + body);
    }

    @Test
    void buildMarkdownBody_doubleQuotes_areEscaped() {
        String body = service.buildMarkdownBody("TEST", "Test", "INFO",
                "Say \"hello\"", null);

        // After escapeJson: " → \"
        assertTrue(body.contains("\\\""), "Body should contain escaped quote: " + body);
    }

    // --- sendAlert tests ---

    @Test
    void sendAlert_notEnabled_returnsFalse() {
        when(notificationConfig.isEnabled()).thenReturn(false);

        boolean result = service.sendAlert("THRESHOLD_HIGH", "Test Alert", "WARNING",
                "test message", null);

        assertFalse(result);
    }

    @Test
    void sendAlert_noDingtalkConfigs_returnsFalse() {
        when(notificationConfig.isEnabled()).thenReturn(true);
        when(notificationConfig.getDingtalk()).thenReturn(List.of());

        boolean result = service.sendAlert("THRESHOLD_HIGH", "Test Alert", "WARNING",
                "test message", null);

        assertFalse(result);
    }

    @Test
    void sendAlert_allChannelsDisabled_returnsFalse() {
        when(notificationConfig.isEnabled()).thenReturn(true);

        NotificationConfig.DingTalkConfig config = new NotificationConfig.DingTalkConfig();
        config.setEnabled(false);
        config.setWebhookUrl("https://oapi.dingtalk.com/robot/send?access_token=ABC");
        when(notificationConfig.getDingtalk()).thenReturn(List.of(config));

        boolean result = service.sendAlert("THRESHOLD_HIGH", "Test Alert", "WARNING",
                "test message", null);

        assertFalse(result);
    }

    @Test
    void sendAlert_alertTypeNotInConfig_returnsFalse() {
        when(notificationConfig.isEnabled()).thenReturn(true);

        NotificationConfig.DingTalkConfig config = new NotificationConfig.DingTalkConfig();
        config.setEnabled(true);
        config.setWebhookUrl("https://oapi.dingtalk.com/robot/send?access_token=ABC");
        config.setAlertTypes(List.of("CRITICAL")); // THRESHOLD_HIGH not in list
        when(notificationConfig.getDingtalk()).thenReturn(List.of(config));

        boolean result = service.sendAlert("THRESHOLD_HIGH", "Test Alert", "WARNING",
                "test message", null);

        assertFalse(result);
    }

    @Test
    void sendAlert_matchingConfig_sendsSuccessfully() {
        when(notificationConfig.isEnabled()).thenReturn(true);

        NotificationConfig.DingTalkConfig config = new NotificationConfig.DingTalkConfig();
        config.setName("test-channel");
        config.setEnabled(true);
        config.setWebhookUrl("https://oapi.dingtalk.com/robot/send?access_token=ABC");
        config.setSecret(null); // no signing
        config.setAlertTypes(List.of("THRESHOLD_HIGH"));
        when(notificationConfig.getDingtalk()).thenReturn(List.of(config));

        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenReturn(new ResponseEntity<>("{\"errcode\":0}", HttpStatus.OK));

        boolean result = service.sendAlert("THRESHOLD_HIGH", "High CPU", "WARNING",
                "CPU above 90%", null);

        assertTrue(result);
        verify(restTemplate).postForEntity(
                eq("https://oapi.dingtalk.com/robot/send?access_token=ABC"),
                any(HttpEntity.class),
                eq(String.class)
        );
    }

    @Test
    void sendAlert_apiFailure_returnsFalse() {
        when(notificationConfig.isEnabled()).thenReturn(true);

        NotificationConfig.DingTalkConfig config = new NotificationConfig.DingTalkConfig();
        config.setEnabled(true);
        config.setWebhookUrl("https://oapi.dingtalk.com/robot/send?access_token=ABC");
        config.setAlertTypes(List.of("THRESHOLD_HIGH"));
        when(notificationConfig.getDingtalk()).thenReturn(List.of(config));

        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new RuntimeException("Network error"));

        boolean result = service.sendAlert("THRESHOLD_HIGH", "Test Alert", "WARNING",
                "test message", null);

        assertFalse(result);
    }

    @Test
    void sendAlert_withSecret_signsRequest() {
        when(notificationConfig.isEnabled()).thenReturn(true);

        NotificationConfig.DingTalkConfig config = new NotificationConfig.DingTalkConfig();
        config.setEnabled(true);
        config.setWebhookUrl("https://oapi.dingtalk.com/robot/send?access_token=ABC");
        config.setSecret("MY_SECRET");
        config.setAlertTypes(List.of("THRESHOLD_HIGH"));
        when(notificationConfig.getDingtalk()).thenReturn(List.of(config));

        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenReturn(new ResponseEntity<>("{\"errcode\":0}", HttpStatus.OK));

        boolean result = service.sendAlert("THRESHOLD_HIGH", "High CPU", "CRITICAL",
                "CPU exceeded 95%", null);

        assertTrue(result);
        // Verify URL contains signature parameters
        verify(restTemplate).postForEntity(
                argThat((String url) -> url.contains("timestamp=") && url.contains("sign=")),
                any(HttpEntity.class),
                eq(String.class)
        );
    }

    @Test
    void sendAlert_multipleConfigs_firstMatchingReturnsTrue() {
        when(notificationConfig.isEnabled()).thenReturn(true);

        NotificationConfig.DingTalkConfig config1 = new NotificationConfig.DingTalkConfig();
        config1.setEnabled(true);
        config1.setWebhookUrl("https://oapi.dingtalk.com/robot/send?access_token=ABC1");
        config1.setAlertTypes(List.of("THRESHOLD_HIGH"));
        config1.setSecret(null);

        NotificationConfig.DingTalkConfig config2 = new NotificationConfig.DingTalkConfig();
        config2.setEnabled(true);
        config2.setWebhookUrl("https://oapi.dingtalk.com/robot/send?access_token=ABC2");
        config2.setAlertTypes(List.of("CRITICAL"));
        config2.setSecret(null);

        when(notificationConfig.getDingtalk()).thenReturn(List.of(config1, config2));
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenReturn(new ResponseEntity<>("{\"errcode\":0}", HttpStatus.OK));

        boolean result = service.sendAlert("THRESHOLD_HIGH", "High CPU", "WARNING",
                "CPU above 90%", null);

        assertTrue(result);
        verify(restTemplate).postForEntity(
                eq("https://oapi.dingtalk.com/robot/send?access_token=ABC1"),
                any(HttpEntity.class),
                eq(String.class)
        );
    }

    @Test
    void sendAlert_nullMetadata_sendsSuccessfully() {
        when(notificationConfig.isEnabled()).thenReturn(true);

        NotificationConfig.DingTalkConfig config = new NotificationConfig.DingTalkConfig();
        config.setEnabled(true);
        config.setWebhookUrl("https://oapi.dingtalk.com/robot/send?access_token=ABC");
        config.setAlertTypes(List.of("THRESHOLD_HIGH"));
        when(notificationConfig.getDingtalk()).thenReturn(List.of(config));

        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenReturn(new ResponseEntity<>("{\"errcode\":0}", HttpStatus.OK));

        boolean result = service.sendAlert("THRESHOLD_HIGH", "High CPU", "WARNING",
                "CPU above 90%", null);

        assertTrue(result);
    }

    @Test
    void sendAlert_allRetriesFail_returnsFalse() {
        when(notificationConfig.isEnabled()).thenReturn(true);

        NotificationConfig.DingTalkConfig config = new NotificationConfig.DingTalkConfig();
        config.setEnabled(true);
        config.setWebhookUrl("https://oapi.dingtalk.com/robot/send?access_token=ABC");
        config.setAlertTypes(List.of("THRESHOLD_HIGH"));
        when(notificationConfig.getDingtalk()).thenReturn(List.of(config));

        // Simulate all 3 retry attempts failing
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new RuntimeException("Connection timeout"));

        boolean result = service.sendAlert("THRESHOLD_HIGH", "Test Alert", "WARNING",
                "test message", null);

        assertFalse(result);
        // Verify 3 attempts were made (MAX_RETRIES = 3)
        verify(restTemplate, times(3))
                .postForEntity(anyString(), any(HttpEntity.class), eq(String.class));
    }

    @Test
    void sendAlert_secondAttemptSucceeds_returnsTrue() {
        when(notificationConfig.isEnabled()).thenReturn(true);

        NotificationConfig.DingTalkConfig config = new NotificationConfig.DingTalkConfig();
        config.setEnabled(true);
        config.setWebhookUrl("https://oapi.dingtalk.com/robot/send?access_token=ABC");
        config.setAlertTypes(List.of("THRESHOLD_HIGH"));
        when(notificationConfig.getDingtalk()).thenReturn(List.of(config));

        // First attempt fails, second succeeds
        when(restTemplate.postForEntity(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenThrow(new RuntimeException("Connection timeout"))
                .thenReturn(new ResponseEntity<>("{\"errcode\":0}", HttpStatus.OK));

        boolean result = service.sendAlert("THRESHOLD_HIGH", "Test Alert", "WARNING",
                "test message", null);

        assertTrue(result);
        // Verify 2 attempts were made (failed once, succeeded on second)
        verify(restTemplate, times(2))
                .postForEntity(anyString(), any(HttpEntity.class), eq(String.class));
    }
}
