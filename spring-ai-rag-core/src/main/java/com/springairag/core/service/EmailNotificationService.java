package com.springairag.core.service;

import com.springairag.core.config.NotificationConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.util.Map;

/**
 * Email notification service for alert delivery via SMTP.
 * Sends HTML-formatted alert emails to configured recipients.
 */
@Service
public class EmailNotificationService implements NotificationService {

    private static final Logger log = LoggerFactory.getLogger(EmailNotificationService.class);
    private static final int MAX_RETRIES = 3;
    private static final long INITIAL_BACKOFF_MS = 500;

    private final NotificationConfig notificationConfig;
    private final JavaMailSender mailSender;

    public EmailNotificationService(NotificationConfig notificationConfig,
                                   @Autowired(required = false) JavaMailSender mailSender) {
        this.notificationConfig = notificationConfig;
        this.mailSender = mailSender;
    }

    @Override
    @Async
    public boolean sendAlert(String alertType, String alertName, String severity,
                             String message, Map<String, Object> metadata) {
        NotificationConfig.EmailConfig emailConfig = notificationConfig.getEmail();
        if (!notificationConfig.isEnabled() || !emailConfig.isEnabled()) {
            return false;
        }

        if (!emailConfig.getAlertTypes().contains(alertType)) {
            return false;
        }

        Exception lastException = null;
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                sendEmail(emailConfig, alertType, alertName, severity, message, metadata);
                log.info("Email notification sent: alertType={} alertName={} to={}",
                        alertType, alertName, emailConfig.getTo());
                return true;
            } catch (Exception e) {
                lastException = e;
                if (attempt < MAX_RETRIES) {
                    try {
                        long sleepMs = INITIAL_BACKOFF_MS * (1L << (attempt - 1));
                        Thread.sleep(sleepMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        log.warn("Email notification interrupted during retry backoff: alertType={}", alertType);
                        return false;
                    }
                }
            }
        }
        log.warn("Failed to send email notification after {} attempts: alertType={} error={}",
                MAX_RETRIES, alertType, lastException != null ? lastException.getMessage() : "unknown");
        return false;
    }

    private void sendEmail(NotificationConfig.EmailConfig config,
                           String alertType, String alertName, String severity,
                           String message, Map<String, Object> metadata) throws MessagingException {
        if (mailSender == null) {
            log.warn("JavaMailSender not available, skipping email notification");
            throw new MessagingException("JavaMailSender not configured");
        }
        MimeMessage mimeMessage = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");

        helper.setFrom(config.getFrom());
        helper.setTo(config.getTo().toArray(new String[0]));
        helper.setSubject(buildSubject(severity, alertName));
        helper.setText(buildHtmlBody(alertType, alertName, severity, message, metadata), true);

        mailSender.send(mimeMessage);
    }

    String buildSubject(String severity, String alertName) {
        return String.format("[%s] RAG Alert: %s", severity, alertName);
    }

    String buildHtmlBody(String alertType, String alertName, String severity,
                        String message, Map<String, Object> metadata) {
        StringBuilder html = new StringBuilder();
        html.append("<html><body style=\"font-family: Arial, sans-serif;\">");
        html.append("<div style=\"background-color: #");
        html.append(severityColor(severity));
        html.append("; color: white; padding: 15px; border-radius: 5px;\">");
        html.append("<h2>").append(escapeHtml(severity)).append(" Alert: ").append(escapeHtml(alertName)).append("</h2>");
        html.append("</div>");

        html.append("<div style=\"padding: 15px;\">");
        html.append("<p><strong>Type:</strong> ").append(escapeHtml(alertType)).append("</p>");
        html.append("<p><strong>Severity:</strong> ").append(escapeHtml(severity)).append("</p>");
        html.append("<p><strong>Message:</strong> ").append(escapeHtml(message)).append("</p>");
        html.append("</div>");

        if (metadata != null && !metadata.isEmpty()) {
            html.append("<div style=\"padding: 15px;\"><h3>Details</h3><ul>");
            for (Map.Entry<String, Object> entry : metadata.entrySet()) {
                html.append("<li><strong>").append(escapeHtml(entry.getKey()))
                    .append(":</strong> ").append(escapeHtml(String.valueOf(entry.getValue()))).append("</li>");
            }
            html.append("</ul></div>");
        }

        html.append("<div style=\"padding: 15px; color: #666; font-size: 12px;\">");
        html.append("Sent by Spring AI RAG Alert System");
        html.append("</div></body></html>");

        return html.toString();
    }

    private String severityColor(String severity) {
        return switch (severity) {
            case "CRITICAL" -> "dc3545";
            case "WARNING" -> "fd7e14";
            case "INFO" -> "0d6efd";
            default -> "6c757d";
        };
    }

    private String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                 .replace("<", "&lt;")
                 .replace(">", "&gt;")
                 .replace("\"", "&quot;")
                 .replace("'", "&#39;");
    }
}
