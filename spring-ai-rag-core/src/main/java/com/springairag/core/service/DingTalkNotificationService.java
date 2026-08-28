package com.springairag.core.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.core.alertdelivery.AlertNotificationAttemptResult;
import com.springairag.core.alertdelivery.AlertNotificationPayload;
import com.springairag.core.alertdelivery.AlertNotificationProvider;
import com.springairag.core.config.NotificationConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.CompletableFuture;
import org.apache.hc.client5.http.impl.classic.HttpClients;

/**
 * DingTalk robot webhook notification service.
 * Supports DingTalk's secret signature mode (secret signature mode (HmacSHA256)) for enhanced security.
 *
 * <p>DingTalk webhook format:
 * <pre>
 * POST https://oapi.dingtalk.com/robot/send?access_token=XXXXX
 * Header: Content-Type: application/json
 * Body: {"msgtype":"markdown","markdown":{"title":"...","text":"..."}}
 * </pre>
 *
 * <p>With secret enabled, sign using HMAC-SHA256:
 * {@code sign = Base64(HMAC-SHA256(secret, "\n" + timestamp))}
 */
@Service
public class DingTalkNotificationService
        implements NotificationService, AlertNotificationProvider {

    private static final Logger log = LoggerFactory.getLogger(DingTalkNotificationService.class);
    private static final String SIGNATURE_ALGORITHM = "HmacSHA256";
    private static final String DINGTALK_API = "https://oapi.dingtalk.com/robot/send";

    private final NotificationConfig notificationConfig;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public DingTalkNotificationService(
            NotificationConfig notificationConfig,
            RestTemplateBuilder builder) {
        this(notificationConfig, builder, new ObjectMapper(),
                Duration.ofSeconds(5), Duration.ofSeconds(10));
    }

    @Autowired
    public DingTalkNotificationService(
            NotificationConfig notificationConfig,
            RestTemplateBuilder builder,
            ObjectMapper objectMapper) {
        this(notificationConfig, builder, objectMapper,
                notificationConfig.getDelivery().getProviderAttemptTimeout(),
                notificationConfig.getDelivery().getProviderAttemptTimeout());
    }

    private DingTalkNotificationService(
            NotificationConfig notificationConfig,
            RestTemplateBuilder builder,
            ObjectMapper objectMapper,
            Duration connectTimeout,
            Duration readTimeout) {
        this.notificationConfig = notificationConfig;
        this.objectMapper = objectMapper;
        this.restTemplate = builder
                .requestFactory(() ->
                        new HttpComponentsClientHttpRequestFactory(
                                HttpClients.custom()
                                        .disableAutomaticRetries()
                                        .build()))
                .connectTimeout(connectTimeout)
                .readTimeout(readTimeout)
                .build();
    }

    @Override
    public String provider() {
        return "DINGTALK";
    }

    @Override
    public boolean isRoutedFor(String alertType) {
        return notificationConfig.isEnabled()
                && notificationConfig.getDingtalk().stream()
                .anyMatch(config -> config.isEnabled()
                        && config.getAlertTypes().contains(alertType));
    }

    @Override
    public boolean isConfigured() {
        return notificationConfig.getDingtalk().stream()
                .anyMatch(config -> config.isEnabled()
                        && config.getWebhookUrl() != null
                        && !config.getWebhookUrl().isBlank());
    }

    @Override
    public boolean isCurrentlyAvailable() {
        return isConfigured();
    }

    @Override
    public AlertNotificationAttemptResult deliver(
            AlertNotificationPayload payload) {
        if (!isRoutedFor(payload.alertType()) || !isCurrentlyAvailable()) {
            return AlertNotificationAttemptResult.permanentFailure(
                    "PERMANENT_CONFIGURATION", null);
        }
        AlertNotificationAttemptResult lastPermanent = null;
        AlertNotificationAttemptResult lastTransient = null;
        for (NotificationConfig.DingTalkConfig config
                : notificationConfig.getDingtalk()) {
            if (!config.isEnabled()
                    || !config.getAlertTypes().contains(payload.alertType())) {
                continue;
            }
            AlertNotificationAttemptResult result = sendOnce(
                    config,
                    buildMarkdownBody(
                            payload.alertType(),
                            payload.alertName(),
                            payload.severity(),
                            payload.message(),
                            payload.metrics(),
                            payload.deliveryId().toString()));
            if (result.outcome()
                    == AlertNotificationAttemptResult.Outcome.SUCCESS) {
                return result;
            }
            if (result.outcome()
                    == AlertNotificationAttemptResult.Outcome.TRANSIENT_FAILURE) {
                lastTransient = result;
            } else {
                lastPermanent = result;
            }
        }
        return lastTransient != null
                ? lastTransient
                : lastPermanent != null
                        ? lastPermanent
                        : AlertNotificationAttemptResult.permanentFailure(
                                "PERMANENT_CONFIGURATION", null);
    }

    @Override
    @Async("taskExecutor")
    public CompletableFuture<Boolean> sendAlert(
            String alertType,
            String alertName,
            String severity,
            String message,
            Map<String, Object> metadata) {
        if (!notificationConfig.isEnabled() || notificationConfig.getDingtalk().isEmpty()) {
            return CompletableFuture.completedFuture(false);
        }

        for (NotificationConfig.DingTalkConfig dtConfig : notificationConfig.getDingtalk()) {
            if (!dtConfig.isEnabled()) continue;
            if (!dtConfig.getAlertTypes().contains(alertType)) continue;

            try {
                sendToDingTalk(dtConfig, alertType, alertName, severity, message, metadata);
                log.info("DingTalk notification sent: channel={} alertType={} alertName={}",
                        dtConfig.getName(), alertType, alertName);
                return CompletableFuture.completedFuture(true);
            } catch (Exception e) {
                // Resilience: one DingTalk channel failure must not block other channels or abort alerting
                log.warn("Failed to send DingTalk notification: channel={} error={}",
                        dtConfig.getName(), e.getMessage());
            }
        }
        return CompletableFuture.completedFuture(false);
    }

    private static final int MAX_RETRIES = 3;
    private static final long INITIAL_BACKOFF_MS = 500;

    private void sendToDingTalk(NotificationConfig.DingTalkConfig config,
                                 String alertType, String alertName, String severity,
                                 String message, Map<String, Object> metadata) {
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            AlertNotificationAttemptResult result = sendOnce(
                    config,
                    buildMarkdownBody(
                            alertType, alertName, severity,
                            message, metadata, null));
            if (result.outcome()
                    == AlertNotificationAttemptResult.Outcome.SUCCESS) {
                return;
            }
            if (result.outcome()
                    == AlertNotificationAttemptResult.Outcome.PERMANENT_FAILURE) {
                throw new IllegalStateException(result.errorCode());
            }
            if (attempt < MAX_RETRIES) {
                try {
                    long sleepMs = INITIAL_BACKOFF_MS * (1L << (attempt - 1));
                    Thread.sleep(sleepMs);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(
                            "Interrupted during retry backoff", interrupted);
                }
            }
        }
        throw new IllegalStateException(
                "DingTalk API failed after " + MAX_RETRIES + " attempts");
    }

    private AlertNotificationAttemptResult sendOnce(
            NotificationConfig.DingTalkConfig config,
            String body) {
        if (config.getWebhookUrl() == null
                || config.getWebhookUrl().isBlank()) {
            return AlertNotificationAttemptResult.permanentFailure(
                    "PERMANENT_CONFIGURATION", null);
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> request = new HttpEntity<>(body, headers);
        try {
            ResponseEntity<String> response = restTemplate.postForEntity(
                    buildWebhookUrl(config), request, String.class);
            return classifyResponse(
                    response.getStatusCode().value(), response.getBody(), null);
        } catch (HttpStatusCodeException error) {
            return classifyResponse(
                    error.getStatusCode().value(),
                    error.getResponseBodyAsString(),
                    error.getResponseHeaders() == null
                            ? null
                            : error.getResponseHeaders().getFirst("Retry-After"));
        } catch (ResourceAccessException error) {
            return AlertNotificationAttemptResult.transientFailure(
                    "TRANSIENT_NETWORK", null, null);
        } catch (RuntimeException error) {
            return AlertNotificationAttemptResult.transientFailure(
                    "TRANSIENT_NETWORK", null, null);
        }
    }

    private AlertNotificationAttemptResult classifyResponse(
            int status,
            String body,
            String retryAfter) {
        if (status == 429) {
            return AlertNotificationAttemptResult.transientFailure(
                    "TRANSIENT_RATE_LIMIT", status, retryAfter(retryAfter));
        }
        if (status >= 500) {
            return AlertNotificationAttemptResult.transientFailure(
                    "TRANSIENT_PROVIDER_5XX", status, null);
        }
        if (status < 200 || status >= 300) {
            return AlertNotificationAttemptResult.permanentFailure(
                    "PERMANENT_PROVIDER_REJECTED", status);
        }
        try {
            JsonNode root = objectMapper.readTree(body == null ? "{}" : body);
            JsonNode errorCode = root.get("errcode");
            if (errorCode != null && errorCode.asInt() != 0) {
                return AlertNotificationAttemptResult.permanentFailure(
                        "PERMANENT_PROVIDER_REJECTED", status);
            }
            return AlertNotificationAttemptResult.success();
        } catch (Exception error) {
            return AlertNotificationAttemptResult.permanentFailure(
                    "PERMANENT_PROVIDER_REJECTED", status);
        }
    }

    private static Duration retryAfter(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Duration.ofSeconds(Math.max(0, Long.parseLong(value.trim())));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    String buildWebhookUrl(NotificationConfig.DingTalkConfig config) {
        String baseUrl = config.getWebhookUrl();
        if (config.getSecret() == null || config.getSecret().isBlank()) {
            return baseUrl;
        }
        long timestamp = System.currentTimeMillis();
        String sign = computeSignature(config.getSecret(), timestamp);
        return baseUrl + (baseUrl.contains("?") ? "&" : "?") + "timestamp=" + timestamp + "&sign=" + sign;
    }

    String computeSignature(String secret, long timestamp) {
        try {
            String stringToSign = timestamp + "\n" + secret;
            Mac mac = Mac.getInstance(SIGNATURE_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), SIGNATURE_ALGORITHM));
            byte[] signData = mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signData);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            log.warn("Failed to compute DingTalk signature: {}", e.getMessage());
            return "";
        }
    }

    String buildMarkdownBody(String alertType, String alertName, String severity,
                             String message, Map<String, Object> metadata) {
        return buildMarkdownBody(
                alertType, alertName, severity, message, metadata, null);
    }

    private String buildMarkdownBody(
            String alertType,
            String alertName,
            String severity,
            String message,
            Map<String, Object> metadata,
            String deliveryId) {
        StringBuilder text = new StringBuilder();
        text.append("## ").append(severity).append(" Alert: ").append(alertName).append("\n\n");
        text.append("> **Type:** ").append(alertType).append("\n\n");
        text.append("> **Message:** ").append(message).append("\n\n");
        if (deliveryId != null) {
            text.append("> **Delivery ID:** ")
                    .append(deliveryId)
                    .append("\n\n");
        }

        if (metadata != null && !metadata.isEmpty()) {
            text.append("### Details\n\n");
            for (Entry<String, Object> entry : metadata.entrySet()) {
                text.append("- **").append(entry.getKey()).append("**: ").append(entry.getValue()).append("\n");
            }
        }

        return """
                {
                    "msgtype": "markdown",
                    "markdown": {
                        "title": "[%s] %s",
                        "text": %s
                    }
                }
                """.formatted(severity, alertName, escapeJson(text.toString()));
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"'  -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default   -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
