package com.springairag.core.service;

import com.springairag.core.config.NotificationConfig;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/**
 * EmailNotificationService 中断与转义长尾（Batch 701，JaCoCo 驱
 * 动）：重试退避期间被中断 → 记录中断位并返回失败、邮件异常解包
 * 对 null 消息回退类名、HTML 转义对 null 的容忍与四档严重度配色。
 */
class EmailNotificationInterruptTailTest {

    private NotificationConfig config;
    private JavaMailSender mailSender;

    @BeforeEach
    void setUp() {
        config = new NotificationConfig();
        config.setEnabled(true);
        NotificationConfig.EmailConfig email = config.getEmail();
        email.setEnabled(true);
        email.setFrom("alerts@rag.local");
        email.setTo(List.of("ops@rag.local"));
        email.setAlertTypes(List.of("AVAILABILITY"));
        mailSender = mock(JavaMailSender.class);
        doThrow(new IllegalStateException("smtp down"))
                .when(mailSender).send(any(MimeMessage.class));
    }

    private EmailNotificationService service() {
        return new EmailNotificationService(config, mailSender);
    }

    @Test
    void interruptDuringRetryBackoffStopsRetries() throws Exception {
        EmailNotificationService service = service();
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        Thread worker = new Thread(() -> future.complete(
                service.sendAlert("AVAILABILITY", "DB", "CRITICAL",
                        "unreachable", Map.of()).join()));
        worker.start();
        // 等待首次发送失败进入 500ms 退避睡眠，然后中断。
        Thread.sleep(150);
        worker.interrupt();
        worker.join(TimeUnit.SECONDS.toMillis(5));

        assertTrue(!worker.isAlive());
        assertEquals(Boolean.FALSE, future.get(2, TimeUnit.SECONDS));
    }

    private String invokeString(String name, Class<?> type, Object arg)
            throws Exception {
        Method method = EmailNotificationService.class
                .getDeclaredMethod(name, type);
        method.setAccessible(true);
        return (String) method.invoke(service(), arg);
    }

    @Test
    void unwrapMailExceptionFallsBackToSimpleClassName() throws Exception {
        // 无消息的 RuntimeException → 回退为类简名。
        String name = invokeString("unwrapMailException",
                Exception.class, new RuntimeException());
        assertEquals("RuntimeException", name);

        MessagingException messaging = new MessagingException();
        String messagingName = invokeString("unwrapMailException",
                Exception.class, new RuntimeException(messaging));
        assertEquals("MessagingException", messagingName);

        // mock JavaMailSender 场景：mimeMessage 初始化缺失的特判消息。
        MessagingException mimeIssue = new MessagingException(
                "NPE on mimeMessage");
        String special = invokeString("unwrapMailException",
                Exception.class, new RuntimeException(mimeIssue));
        assertEquals("JavaMail configuration error "
                + "(MimeMessage not properly initialized)", special);
    }

    @Test
    void escapeHtmlToleratesNullAndEscapesMarkup() throws Exception {
        assertEquals("", invokeString("escapeHtml",
                String.class, null));
        assertEquals("&lt;b&gt;alert&amp;more&lt;/b&gt;",
                invokeString("escapeHtml", String.class, "<b>alert&more</b>"));
    }

    @Test
    void severityColorCoversAllBuckets() throws Exception {
        assertEquals("dc3545", invokeString("severityColor",
                String.class, "CRITICAL"));
        assertEquals("fd7e14", invokeString("severityColor",
                String.class, "WARNING"));
        assertEquals("0d6efd", invokeString("severityColor",
                String.class, "INFO"));
        assertEquals("6c757d", invokeString("severityColor",
                String.class, "UNKNOWN"));
    }
}
