package com.springairag.core.alertdelivery;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.core.config.NotificationConfig;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/** 创建有界、递归脱敏且可稳定序列化的通知 payload。 */
@Component
public class AlertNotificationPayloadSanitizer {

    private static final int MAX_DEPTH = 8;
    private static final int MAX_ITEMS = 100;
    private static final int MAX_STRING = 2048;
    private static final Pattern SENSITIVE_KEY = Pattern.compile(
            "(?i).*(password|secret|token|api.?key|authorization|credential"
                    + "|webhook|smtp|recipient|cookie).*");
    private static final Pattern SECRET_VALUE = Pattern.compile(
            "(?i)(bearer\\s+[A-Za-z0-9._~+/=-]+"
                    + "|sk-[A-Za-z0-9_-]{8,}"
                    + "|rag_sk_[A-Za-z0-9_-]{8,}"
                    + "|(access_?token|api_?key|secret)=([^&\\s]+))");
    private static final String REDACTED = "[REDACTED]";

    private final ObjectMapper objectMapper;
    private final NotificationConfig notificationConfig;

    public AlertNotificationPayloadSanitizer(
            ObjectMapper objectMapper,
            NotificationConfig notificationConfig) {
        this.objectMapper = objectMapper;
        this.notificationConfig = notificationConfig;
    }

    public SanitizedPayload create(
            UUID deliveryId,
            String alertType,
            String alertName,
            String severity,
            String message,
            Map<String, Object> metrics) {
        String safeMessage = sanitizeString(message);
        Map<String, Object> safeMetrics = sanitizeMap(metrics, 0);
        AlertNotificationPayload payload = new AlertNotificationPayload(
                deliveryId,
                sanitizeString(alertType),
                sanitizeString(alertName),
                sanitizeString(severity),
                safeMessage,
                safeMetrics,
                false);
        byte[] json = serialize(payload);
        int maxBytes = notificationConfig.getDelivery().getMaxPayloadBytes();
        if (json.length <= maxBytes) {
            return new SanitizedPayload(payload, new String(
                    json, StandardCharsets.UTF_8));
        }

        payload = new AlertNotificationPayload(
                deliveryId, payload.alertType(), payload.alertName(),
                payload.severity(), truncate(safeMessage, 512), Map.of(), true);
        json = serialize(payload);
        if (json.length > maxBytes) {
            payload = new AlertNotificationPayload(
                    deliveryId, truncate(payload.alertType(), 128),
                    truncate(payload.alertName(), 256),
                    truncate(payload.severity(), 32), "", Map.of(), true);
            json = serialize(payload);
        }
        if (json.length > maxBytes) {
            throw new IllegalStateException(
                    "Minimum alert notification payload exceeds configured limit");
        }
        return new SanitizedPayload(payload, new String(
                json, StandardCharsets.UTF_8));
    }

    private Map<String, Object> sanitizeMap(
            Map<String, Object> source, int depth) {
        if (source == null || source.isEmpty() || depth >= MAX_DEPTH) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        int count = 0;
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            if (count++ >= MAX_ITEMS) {
                break;
            }
            String key = truncate(
                    entry.getKey() == null ? "" : entry.getKey(), 256);
            result.put(key, SENSITIVE_KEY.matcher(
                    key.toLowerCase(Locale.ROOT)).matches()
                    ? REDACTED
                    : sanitizeValue(entry.getValue(), depth + 1));
        }
        return Collections.unmodifiableMap(result);
    }

    private Object sanitizeValue(Object value, int depth) {
        if (value == null) {
            return null;
        }
        if (depth >= MAX_DEPTH) {
            return "[TRUNCATED]";
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> converted = new LinkedHashMap<>();
            map.forEach((key, nested) ->
                    converted.put(String.valueOf(key), nested));
            return sanitizeMap(converted, depth);
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> result = new ArrayList<>();
            for (Object item : iterable) {
                if (result.size() >= MAX_ITEMS) {
                    break;
                }
                result.add(sanitizeValue(item, depth + 1));
            }
            return Collections.unmodifiableList(result);
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value;
        }
        return sanitizeString(String.valueOf(value));
    }

    private static String sanitizeString(String value) {
        if (value == null) {
            return "";
        }
        return truncate(SECRET_VALUE.matcher(value).replaceAll(REDACTED),
                MAX_STRING);
    }

    private static String truncate(String value, int maximum) {
        if (value == null || value.length() <= maximum) {
            return value == null ? "" : value;
        }
        return value.substring(0, maximum);
    }

    private byte[] serialize(AlertNotificationPayload payload) {
        try {
            return objectMapper.writeValueAsBytes(payload);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException(
                    "Unable to serialize alert notification payload", error);
        }
    }

    public record SanitizedPayload(
            AlertNotificationPayload payload,
            String json) {
    }
}
