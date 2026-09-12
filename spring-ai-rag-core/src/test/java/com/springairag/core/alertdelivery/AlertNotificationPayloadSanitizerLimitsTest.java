package com.springairag.core.alertdelivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.core.config.NotificationConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AlertNotificationPayloadSanitizer 有界与脱敏矩阵（Batch 324）：
 * 超限降级链（丢指标 → 截字段）、最小载荷仍超限抛错、键截断与
 * 敏感键打码、秘密值模式打码、深度/条目上限、标量透传。
 */
class AlertNotificationPayloadSanitizerLimitsTest {

    private AlertNotificationPayloadSanitizer sanitizer(int maxPayloadBytes) {
        NotificationConfig config = new NotificationConfig();
        config.getDelivery().setMaxPayloadBytes(maxPayloadBytes);
        return new AlertNotificationPayloadSanitizer(
                new ObjectMapper(), config);
    }

    @Test
    void oversizedPayloadDegradesMetricsFirst() {
        AlertNotificationPayloadSanitizer sanitizer = sanitizer(700);
        Map<String, Object> metrics = new LinkedHashMap<>();
        for (int i = 0; i < 20; i++) {
            metrics.put("metric-" + i, "value-" + i);
        }
        String longMessage = "x".repeat(2_000);

        AlertNotificationPayloadSanitizer.SanitizedPayload result =
                sanitizer.create(UUID.randomUUID(), "SLO", "name",
                        "WARNING", longMessage, metrics);

        // 第一级降级：消息截断到 512、指标清空、打上截断标记。
        assertTrue(result.payload().payloadTruncated());
        assertTrue(result.payload().metrics().isEmpty());
        assertEquals(512, result.payload().message().length());
        assertTrue(result.json().contains("payloadTruncated\":true"));
    }

    @Test
    void minimumPayloadStillTooLargeThrows() {
        AlertNotificationPayloadSanitizer sanitizer = sanitizer(1);

        assertThrows(IllegalStateException.class,
                () -> sanitizer.create(UUID.randomUUID(), "SLO", "name",
                        "WARNING", "msg", Map.of("k", "v")));
    }

    @Test
    void longKeysAreTruncatedAndSensitiveKeysRedacted() {
        AlertNotificationPayloadSanitizer sanitizer = sanitizer(65_536);
        String longKey = "k".repeat(300);

        var result = sanitizer.create(UUID.randomUUID(), "A", "n", "E",
                "msg",
                Map.of(longKey, "kept",
                        "smtpHost", "internal-smtp.example.test",
                        "webhookUrl", "https://hooks.example.test/x",
                        "authorizationHeader", "Bearer abc"));

        Map<String, Object> metrics = result.payload().metrics();
        // 键被截断到 256。
        assertEquals("kept", metrics.get("k".repeat(256)));
        // 敏感键（smtp/webhook/authorization）的值被整体打码。
        assertEquals("[REDACTED]", metrics.get("smtpHost"));
        assertEquals("[REDACTED]", metrics.get("webhookUrl"));
        assertEquals("[REDACTED]", metrics.get("authorizationHeader"));
    }

    @Test
    void secretValuePatternsAreRedactedInStrings() {
        AlertNotificationPayloadSanitizer sanitizer = sanitizer(65_536);

        var result = sanitizer.create(UUID.randomUUID(), "A", "n", "E",
                "auth Bearer abc123 def; key sk-12345678; "
                        + "alt rag_sk_12345678; cfg api_key=zzz999",
                Map.of());

        String message = result.payload().message();
        assertFalse(message.contains("abc123"));
        assertFalse(message.contains("sk-12345678"));
        assertFalse(message.contains("rag_sk_12345678"));
        assertFalse(message.contains("zzz999"));
        assertTrue(message.contains("[REDACTED]"));
    }

    @Test
    void scalarsPassThroughAndDepthAndItemLimitsApply() {
        AlertNotificationPayloadSanitizer sanitizer = sanitizer(65_536);

        // 深度 12 的嵌套（上限 8）。
        Map<String, Object> deep = Map.of("leaf", "deep-value");
        for (int i = 0; i < 11; i++) {
            deep = Map.of("level-" + i, deep);
        }
        // 150 项列表（上限 100）。
        List<Integer> bigList = new ArrayList<>();
        for (int i = 0; i < 150; i++) {
            bigList.add(i);
        }

        Map<String, Object> metricsInput = new java.util.HashMap<>();
        metricsInput.put("deep", deep);
        metricsInput.put("list", bigList);
        metricsInput.put("count", 42);
        metricsInput.put("flag", true);
        metricsInput.put("nothing", null);

        var result = sanitizer.create(UUID.randomUUID(), "A", "n", "E",
                "msg", metricsInput);

        Map<String, Object> metrics = result.payload().metrics();
        assertEquals(42, metrics.get("count"));
        assertEquals(true, metrics.get("flag"));
        org.junit.jupiter.api.Assertions.assertNull(metrics.get("nothing"));

        // 列表截断到 100 项。
        assertEquals(100, ((List<?>) metrics.get("list")).size());

        // 逐层下钻：深度达到 8 的嵌套整体变为 [TRUNCATED]。
        Object current = metrics.get("deep");
        boolean truncatedReached = false;
        for (int level = 10; level >= 0 && current instanceof Map<?, ?>;
                level--) {
            current = ((Map<?, ?>) current).get("level-" + level);
            if ("[TRUNCATED]".equals(current)) {
                truncatedReached = true;
                break;
            }
        }
        assertTrue(truncatedReached, "深层嵌套应被 [TRUNCATED] 截断");
    }
}
