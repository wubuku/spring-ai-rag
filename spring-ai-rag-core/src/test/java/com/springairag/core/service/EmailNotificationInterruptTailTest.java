package com.springairag.core.service;

import com.springairag.core.config.NotificationConfig;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
    }

    private EmailNotificationService service() {
        return new EmailNotificationService(config, mailSender);
    }

    @Test
    void interruptDuringRetryBackoffStopsRetries() throws Exception {
        EmailNotificationService service = service();
        AtomicReference<Thread> workerRef = new AtomicReference<>();
        AtomicBoolean interruptFlagPreserved = new AtomicBoolean();
        CompletableFuture<Boolean> future = new CompletableFuture<>();

        // 夹具修正：sendEmail 第一步就是 mailSender.createMimeMessage()，mock 默认
        // 返回 null，紧接着的 new MimeMessageHelper(null, true, "UTF-8") 会在
        // 走到 mailSender.send() 之前就抛 NPE（实测：
        // "Cannot invoke MimeMessage.setContent because mimeMessage is null"）。
        // 也就是说原来 setUp 里那句 doThrow("smtp down").send(...) 从来没被打中，
        // 三次重试全都死在同一个 NPE 上，注释里说的"首次发送失败"从来不是
        // smtp 失败。给 createMimeMessage 一封真信，桩才打得到真正的发送。
        when(mailSender.createMimeMessage()).thenAnswer(invocation ->
                new MimeMessage(Session.getInstance(new Properties())));
        doAnswer(invocation -> {
            // 精确地把中断打在"首次发送失败、下一次退避 Thread.sleep 之前"，
            // 不再用 sleep(150) 去赌时序。
            workerRef.get().interrupt();
            throw new IllegalStateException("smtp down");
        }).when(mailSender).send(any(MimeMessage.class));

        Thread worker = new Thread(() -> {
            workerRef.set(Thread.currentThread());
            Boolean result = service.sendAlert("AVAILABILITY", "DB", "CRITICAL",
                    "unreachable", Map.of()).join();
            interruptFlagPreserved.set(Thread.currentThread().isInterrupted());
            future.complete(result);
        });
        worker.start();
        worker.join(TimeUnit.SECONDS.toMillis(5));

        assertFalse(worker.isAlive(), "工作线程没有在 5 秒内退出");
        assertEquals(Boolean.FALSE, future.get(2, TimeUnit.SECONDS));

        // 这两条才是"中断真的起作用了"的证据。原来的断言只看返回值 false，
        // 而跑满 3 次重试（500ms + 1000ms 退避）后返回值**也是** false——
        // 也就是说即使中断完全失效，这条用例照样是绿的。
        verify(mailSender, times(1)).send(any(MimeMessage.class));
        assertTrue(interruptFlagPreserved.get(),
                "中断位没有被复原：生产代码在 InterruptedException 分支里"
                        + "调了 Thread.currentThread().interrupt()");
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
