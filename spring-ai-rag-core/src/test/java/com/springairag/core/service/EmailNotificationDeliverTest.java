package com.springairag.core.service;

import com.springairag.core.alertdelivery.AlertNotificationAttemptResult;
import com.springairag.core.alertdelivery.AlertNotificationPayload;
import com.springairag.core.config.NotificationConfig;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * EmailNotificationService.deliver 的路由守卫与异常分型：未路由或
 * 不可用 → 永久配置失败；认证/解析异常 → 永久失败；MailException
 * /RuntimeException → 瞬态失败；正常发送 → 成功。
 */
@ExtendWith(MockitoExtension.class)
class EmailNotificationDeliverTest {

    @Mock JavaMailSender mailSender;

    private NotificationConfig config;
    private EmailNotificationService service;

    @BeforeEach
    void setUp() {
        config = new NotificationConfig();
        config.setEnabled(true);
        NotificationConfig.EmailConfig email = config.getEmail();
        email.setEnabled(true);
        email.setFrom("alerts@rag.local");
        email.setTo(List.of("ops@rag.local"));
        service = new EmailNotificationService(config, mailSender);
    }

    private AlertNotificationPayload payload() {
        return new AlertNotificationPayload(
                UUID.randomUUID(), "CRITICAL", "db-down", "CRITICAL",
                "Database unreachable", Map.of("host", "db-1"), false);
    }

    private void stubMimeMessage() {
        when(mailSender.createMimeMessage())
                .thenReturn(new MimeMessage((jakarta.mail.Session) null));
    }

    @Test
    void deliver_successReturnsSuccessOutcome() {
        stubMimeMessage();

        AlertNotificationAttemptResult result = service.deliver(payload());

        assertEquals(AlertNotificationAttemptResult.Outcome.SUCCESS,
                result.outcome());
        // success 工厂 retryAfter 为 null（Duration record 组件可空）。
        assertTrue(result.retryAfter() == null || result.retryAfter().equals(java.time.Duration.ZERO));
    }

    @Test
    void deliver_notRoutedReturnsPermanentConfigurationFailure() {
        // alertType 不在 email.alertTypes 默认列表中。
        AlertNotificationAttemptResult result = service.deliver(
                new AlertNotificationPayload(
                        UUID.randomUUID(), "UNKNOWN_TYPE", "test", "INFO",
                        "msg", Map.of(), false));

        assertEquals(AlertNotificationAttemptResult.Outcome.PERMANENT_FAILURE,
                result.outcome());
        assertEquals("PERMANENT_CONFIGURATION", result.errorCode());
    }

    @Test
    void deliver_mailSenderMissingReturnsPermanentConfigurationFailure() {
        EmailNotificationService noSender = new EmailNotificationService(
                config, null);

        AlertNotificationAttemptResult result = noSender.deliver(payload());

        assertEquals(AlertNotificationAttemptResult.Outcome.PERMANENT_FAILURE,
                result.outcome());
        assertEquals("PERMANENT_CONFIGURATION", result.errorCode());
    }

    @Test
    void deliver_authExceptionReturnsPermanentFailure() {
        stubMimeMessage();
        org.mockito.Mockito.doThrow(new MailAuthenticationException("bad credentials"))
                .when(mailSender).send(org.mockito.ArgumentMatchers.any(MimeMessage.class));

        AlertNotificationAttemptResult result = service.deliver(payload());

        assertEquals(AlertNotificationAttemptResult.Outcome.PERMANENT_FAILURE,
                result.outcome());
        assertEquals("PERMANENT_CONFIGURATION", result.errorCode());
    }

    @Test
    void deliver_mailSendExceptionReturnsTransientFailure() {
        stubMimeMessage();
        org.mockito.Mockito.doThrow(new MailSendException("smtp relay down"))
                .when(mailSender).send(org.mockito.ArgumentMatchers.any(MimeMessage.class));

        AlertNotificationAttemptResult result = service.deliver(payload());

        assertEquals(AlertNotificationAttemptResult.Outcome.TRANSIENT_FAILURE,
                result.outcome());
        assertEquals("TRANSIENT_NETWORK", result.errorCode());
    }

    @Test
    void deliver_runtimeExceptionReturnsTransientFailure() {
        // createMimeMessage 抛 RuntimeException → 同样映射为瞬态失败。
        when(mailSender.createMimeMessage())
                .thenThrow(new IllegalStateException("session broken"));

        AlertNotificationAttemptResult result = service.deliver(payload());

        assertEquals(AlertNotificationAttemptResult.Outcome.TRANSIENT_FAILURE,
                result.outcome());
    }

    @Test
    void isCurrentlyAvailable_dependsOnDeliveryConfigAndSenderType() {
        // delivery.enabled=false（默认）：仅要求 mailSender 非空。
        assertTrue(service.isCurrentlyAvailable());

        // delivery.enabled=true 且 mailSender 不是 JavaMailSenderImpl → 不可用。
        config.getDelivery().setEnabled(true);
        assertFalse(service.isCurrentlyAvailable());

        // delivery.enabled=true 且 mailSender 是 JavaMailSenderImpl → 可用。
        org.springframework.mail.javamail.JavaMailSenderImpl impl =
                new org.springframework.mail.javamail.JavaMailSenderImpl();
        EmailNotificationService implService = new EmailNotificationService(
                config, impl);
        assertTrue(implService.isCurrentlyAvailable());
    }
}
