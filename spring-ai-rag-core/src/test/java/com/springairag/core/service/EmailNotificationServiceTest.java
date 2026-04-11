package com.springairag.core.service;

import com.springairag.core.config.NotificationConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.javamail.JavaMailSender;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.MessagingException;
import java.util.Map;

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
        boolean result = emailService.sendAlert("CRITICAL", "Test", "CRITICAL", "msg", Map.of());
        assertFalse(result);
        verifyNoInteractions(mailSender);
    }

    @Test
    void sendAlert_globalDisabled_returnsFalse() {
        notificationConfig.setEnabled(false);
        notificationConfig.getEmail().setEnabled(true);
        boolean result = emailService.sendAlert("CRITICAL", "Test", "CRITICAL", "msg", Map.of());
        assertFalse(result);
    }

    @Test
    void sendAlert_alertTypeNotConfigured_returnsFalse() {
        notificationConfig.getEmail().setEnabled(true);
        notificationConfig.getEmail().setAlertTypes(java.util.List.of("SLO_BREACH"));
        boolean result = emailService.sendAlert("CRITICAL", "Test", "CRITICAL", "msg", Map.of());
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

        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage((Session) null));

        boolean result = emailService.sendAlert("CRITICAL", "P99 Latency", "CRITICAL",
                "P99 exceeds threshold", Map.of("p99_ms", 2500, "slo_threshold_ms", 1000));

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
                "P99 exceeds threshold", Map.of());

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

        // First attempt fails, second succeeds
        when(mailSender.createMimeMessage())
                .thenThrow(new RuntimeException("SMTP error"))
                .thenReturn(new MimeMessage((Session) null));

        boolean result = emailService.sendAlert("CRITICAL", "P99 Latency", "CRITICAL",
                "P99 exceeds threshold", Map.of());

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

        boolean result = emailService.sendAlert("CRITICAL", "Test", "CRITICAL", "msg", Map.of());
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
}
