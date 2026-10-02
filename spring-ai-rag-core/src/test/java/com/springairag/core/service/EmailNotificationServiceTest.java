package com.springairag.core.service;

import com.springairag.core.alertdelivery.AlertNotificationAttemptResult;
import com.springairag.core.alertdelivery.AlertNotificationPayload;
import com.springairag.core.config.NotificationConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.javamail.JavaMailSender;

import jakarta.mail.internet.MimeMessage;
import jakarta.mail.MessagingException;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EmailNotificationServiceTest {

    @Mock
    private JavaMailSender mailSender;

    private NotificationConfig notificationConfig;
    private EmailNotificationService emailService;

    @BeforeEach
    void setUp() {
        notificationConfig = new NotificationConfig();
        notificationConfig.setEnabled(true);
        emailService = new EmailNotificationService(notificationConfig, mailSender);
    }

    @Test
    void sendAlert_emailDisabled_returnsFalse() {
        notificationConfig.getEmail().setEnabled(false);
        boolean result = emailService.sendAlert(
                "CRITICAL", "Test", "CRITICAL", "msg", Map.of()).join();
        assertFalse(result);
        verifyNoInteractions(mailSender);
    }

    @Test
    void sendAlert_globalDisabled_returnsFalse() {
        notificationConfig.setEnabled(false);
        notificationConfig.getEmail().setEnabled(true);
        boolean result = emailService.sendAlert(
                "CRITICAL", "Test", "CRITICAL", "msg", Map.of()).join();
        assertFalse(result);
    }

    @Test
    void sendAlert_alertTypeNotConfigured_returnsFalse() {
        notificationConfig.getEmail().setEnabled(true);
        notificationConfig.getEmail().setAlertTypes(java.util.List.of("SLO_BREACH"));
        boolean result = emailService.sendAlert(
                "CRITICAL", "Test", "CRITICAL", "msg", Map.of()).join();
        assertFalse(result);
    }

    @Test
    void sendAlert_success_returnsTrue() throws Exception {
        notificationConfig.getEmail().setEnabled(true);
        notificationConfig.getEmail().setHost("smtp.example.com");
        notificationConfig.getEmail().setPort(587);
        notificationConfig.getEmail().setUsername("alert@example.com");
        notificationConfig.getEmail().setPassword("secret");
        notificationConfig.getEmail().setFrom("noreply@example.com");
        notificationConfig.getEmail().setTo(java.util.List.of("admin@example.com"));

        // Use mock MimeMessage to avoid NPE from null Session in real MimeMessage
        MimeMessage mockMimeMessage = mock(MimeMessage.class);
        when(mailSender.createMimeMessage()).thenReturn(mockMimeMessage);

        boolean result = emailService.sendAlert("CRITICAL", "P99 Latency", "CRITICAL",
                "P99 exceeds threshold",
                Map.of("p99_ms", 2500, "slo_threshold_ms", 1000)).join();

        assertTrue(result);
        verify(mailSender).send(any(MimeMessage.class));
    }

    @Test
    void sendAlert_mailSenderThrowsAll3Attempts_returnsFalse() throws Exception {
        notificationConfig.getEmail().setEnabled(true);
        notificationConfig.getEmail().setHost("smtp.example.com");
        notificationConfig.getEmail().setPort(587);
        notificationConfig.getEmail().setFrom("noreply@example.com");
        notificationConfig.getEmail().setTo(java.util.List.of("admin@example.com"));

        when(mailSender.createMimeMessage()).thenThrow(new RuntimeException("SMTP error"));

        boolean result = emailService.sendAlert("CRITICAL", "P99 Latency", "CRITICAL",
                "P99 exceeds threshold", Map.of()).join();

        assertFalse(result);
        // Retry logic: 3 attempts before giving up
        verify(mailSender, times(3)).createMimeMessage();
    }

    @Test
    void sendAlert_succeedsOnSecondAttempt_returnsTrue() throws Exception {
        notificationConfig.getEmail().setEnabled(true);
        notificationConfig.getEmail().setHost("smtp.example.com");
        notificationConfig.getEmail().setPort(587);
        notificationConfig.getEmail().setFrom("noreply@example.com");
        notificationConfig.getEmail().setTo(java.util.List.of("admin@example.com"));

        // Use mock MimeMessage to avoid NPE from null Session in real MimeMessage
        MimeMessage mockMimeMessage = mock(MimeMessage.class);
        // First attempt fails, second succeeds
        when(mailSender.createMimeMessage())
                .thenThrow(new RuntimeException("SMTP error"))
                .thenReturn(mockMimeMessage);

        boolean result = emailService.sendAlert("CRITICAL", "P99 Latency", "CRITICAL",
                "P99 exceeds threshold", Map.of()).join();

        assertTrue(result);
        verify(mailSender, times(2)).createMimeMessage();
        verify(mailSender).send(any(MimeMessage.class));
    }

    @Test
    void buildSubject_formatCorrect() {
        String subject = emailService.buildSubject("CRITICAL", "P99 Latency");
        assertEquals("[CRITICAL] RAG Alert: P99 Latency", subject);
    }

    @Test
    void buildSubject_warningSeverity() {
        String subject = emailService.buildSubject("WARNING", "Slow Query");
        assertEquals("[WARNING] RAG Alert: Slow Query", subject);
    }

    @Test
    void buildHtmlBody_withMetadata() {
        String html = emailService.buildHtmlBody(
                "THRESHOLD_HIGH", "P99 Latency", "CRITICAL",
                "P99 exceeds 2000ms threshold",
                Map.of("p99_ms", 2500, "threshold_ms", 2000, "endpoint", "/api/search"));

        assertTrue(html.contains("<html>"));
        assertTrue(html.contains("</h2>"));
        assertTrue(html.contains("THRESHOLD_HIGH"));
        assertTrue(html.contains("P99 exceeds 2000ms threshold"));
        assertTrue(html.contains("p99_ms"));
        assertTrue(html.contains("2500"));
        assertTrue(html.contains("endpoint"));
        assertTrue(html.contains("/api/search"));
    }

    @Test
    void buildHtmlBody_withoutMetadata() {
        String html = emailService.buildHtmlBody(
                "SLO_BREACH", "Latency SLO", "WARNING",
                "P95 exceeds threshold", null);

        assertTrue(html.contains("<html>"));
        assertTrue(html.contains("</h2>"));
        assertTrue(html.contains("SLO_BREACH"));
        assertTrue(html.contains("P95 exceeds threshold"));
        assertFalse(html.contains("<h3>Details</h3>"));
    }

    @Test
    void buildHtmlBody_htmlEscaping() {
        String html = emailService.buildHtmlBody(
                "TEST", "XSS Test", "INFO",
                "<script>alert('xss')</script>",
                Map.of("input", "<b>bold</b>"));

        assertFalse(html.contains("<script>"));
        assertFalse(html.contains("<b>bold</b>"));
        assertTrue(html.contains("&lt;script&gt;"));
        assertTrue(html.contains("&lt;b&gt;bold&lt;/b&gt;"));
    }

    // Batch 814：上面那条只覆盖了 message 和 metadata 的**值**。buildHtmlBody 里
    // 另外几处插值同样走 escapeHtml，但没有任何测试守着——改坏了照样全绿。
    // deliveryId 不在此列：唯一调用点传的是 payload.deliveryId().toString()（UUID），
    // 构造上就不可控，写测试等于测一个不可达状态。
    @Test
    void buildHtmlBody_escapesEveryInterpolatedField() {
        String payload = "<script>alert('xss')</script>";
        String html = emailService.buildHtmlBody(
                payload,                    // alertType
                payload,                    // alertName
                payload,                    // severity
                "harmless message",
                Map.of(payload, payload));  // metadata key 与 value

        assertFalse(html.contains("<script"),
                () -> "有插值字段未转义：" + html);
        assertTrue(html.contains("&lt;script&gt;alert(&#39;xss&#39;)&lt;/script&gt;"),
                () -> "载荷未被转义成实体：" + html);
    }

    @Test
    void buildHtmlBody_severityColorIgnoresUnknownSeverity() {
        // severity 同时进了 style 属性和正文两处；前者只能来自这个封闭 switch。
        String html = emailService.buildHtmlBody(
                "INFO", "Alert", "\"><script>alert(1)</script>", "msg", null);

        assertFalse(html.contains("background-color: #\"><"),
                () -> "颜色值被 severity 影响了：" + html);
        assertTrue(html.contains("background-color: #6c757d"),
                () -> "未知 severity 应回落到默认灰：" + html);
    }

    @Test
    void buildHtmlBody_nullMetadata() {
        String html = emailService.buildHtmlBody(
                "INFO", "Test", "INFO", "test message", null);
        assertFalse(html.contains("<h3>Details</h3>"));
    }

    @Test
    void sendAlert_emptyToList_returnsFalse() {
        notificationConfig.getEmail().setEnabled(true);
        notificationConfig.getEmail().setHost("smtp.example.com");
        notificationConfig.getEmail().setPort(587);
        notificationConfig.getEmail().setFrom("noreply@example.com");
        notificationConfig.getEmail().setTo(java.util.List.of());

        boolean result = emailService.sendAlert(
                "CRITICAL", "Test", "CRITICAL", "msg", Map.of()).join();
        assertFalse(result);
    }

    @Test
    void sendAlert_mailSenderNull_returnsFalse() {
        // mailSender is null when JavaMailSender is not on classpath / not configured
        EmailNotificationService serviceWithoutMail = new EmailNotificationService(notificationConfig, null);
        notificationConfig.getEmail().setEnabled(true);
        notificationConfig.getEmail().setAlertTypes(java.util.List.of("CRITICAL"));

        boolean result = serviceWithoutMail.sendAlert("CRITICAL", "Test Alert", "CRITICAL",
                "test message", Map.of()).join();

        assertFalse(result);
    }

    @Test
    void buildHtmlBody_severityColors() {
        String criticalHtml = emailService.buildHtmlBody("T1", "C", "CRITICAL", "m", null);
        assertTrue(criticalHtml.contains("dc3545")); // red

        String warningHtml = emailService.buildHtmlBody("T2", "W", "WARNING", "m", null);
        assertTrue(warningHtml.contains("fd7e14")); // orange

        String infoHtml = emailService.buildHtmlBody("T3", "I", "INFO", "m", null);
        assertTrue(infoHtml.contains("0d6efd")); // blue
    }

    @Test
    void durableEmailRejectsUntimeboundedCustomSender() {
        notificationConfig.getDelivery().setEnabled(true);
        notificationConfig.getEmail().setEnabled(true);
        notificationConfig.getEmail().setFrom("alerts@example.com");
        notificationConfig.getEmail().setTo(
                java.util.List.of("operator@example.com"));
        notificationConfig.getEmail().setAlertTypes(
                java.util.List.of("SLO_BREACH"));
        EmailNotificationService durable =
                new EmailNotificationService(notificationConfig, mailSender);

        AlertNotificationAttemptResult result = durable.deliver(
                new AlertNotificationPayload(
                        UUID.randomUUID(), "SLO_BREACH", "fixture",
                        "CRITICAL", "fixture", Map.of(), false));

        assertEquals(
                AlertNotificationAttemptResult.Outcome.PERMANENT_FAILURE,
                result.outcome());
        assertEquals("PERMANENT_CONFIGURATION", result.errorCode());
        verifyNoInteractions(mailSender);
    }
}
