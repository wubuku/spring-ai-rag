package com.springairag.core.service;

import com.springairag.core.config.NotificationConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
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

/**
 * DingTalk robot webhook notification service.
 * Supports DingTalk's secret signature mode (加签) for enhanced security.
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
public class DingTalkNotificationService implements NotificationService {

    private static final Logger log = LoggerFactory.getLogger(DingTalkNotificationService.class);
    private static final String SIGNATURE_ALGORITHM = "HmacSHA256";
    private static final String DINGTALK_API = "https://oapi.dingtalk.com/robot/send";

    private final NotificationConfig notificationConfig;
    private final RestTemplate restTemplate;

    public DingTalkNotificationService(NotificationConfig notificationConfig, RestTemplateBuilder builder) {
        this.notificationConfig = notificationConfig;
        this.restTemplate = builder
                .connectTimeout(Duration.ofSeconds(5))
                .readTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    @Async
    public boolean sendAlert(String alertType, String alertName, String severity,
                             String message, Map<String, Object> metadata) {
        if (!notificationConfig.isEnabled() || notificationConfig.getDingtalk().isEmpty()) {
            return false;
        }

        for (NotificationConfig.DingTalkConfig dtConfig : notificationConfig.getDingtalk()) {
            if (!dtConfig.isEnabled()) continue;
            if (!dtConfig.getAlertTypes().contains(alertType)) continue;

            try {
                sendToDingTalk(dtConfig, alertType, alertName, severity, message, metadata);
                log.info("DingTalk notification sent: channel={} alertType={} alertName={}",
                        dtConfig.getName(), alertType, alertName);
                return true;
            } catch (Exception e) {
                log.warn("Failed to send DingTalk notification: channel={} error={}",
                        dtConfig.getName(), e.getMessage());
            }
        }
        return false;
    }

    private void sendToDingTalk(NotificationConfig.DingTalkConfig config,
                                 String alertType, String alertName, String severity,
                                 String message, Map<String, Object> metadata) {
        String url = buildWebhookUrl(config);
        String body = buildMarkdownBody(alertType, alertName, severity, message, metadata);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> request = new HttpEntity<>(body, headers);

        restTemplate.postForEntity(url, request, String.class);
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
        StringBuilder text = new StringBuilder();
        text.append("## ").append(severity).append(" Alert: ").append(alertName).append("\n\n");
        text.append("> **Type:** ").append(alertType).append("\n\n");
        text.append("> **Message:** ").append(message).append("\n\n");

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
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
