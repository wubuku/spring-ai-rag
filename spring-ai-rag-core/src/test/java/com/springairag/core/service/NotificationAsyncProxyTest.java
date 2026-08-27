package com.springairag.core.service;

import com.springairag.core.config.NotificationConfig;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NotificationAsyncProxyTest {

    @Test
    void emailNotificationUsesValidSpringAsyncFutureContract()
            throws Exception {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(
                             TestConfiguration.class)) {
            NotificationService service =
                    context.getBean(NotificationService.class);
            JavaMailSender sender = context.getBean(JavaMailSender.class);

            assertTrue(AopUtils.isAopProxy(service));
            assertTrue(service.sendAlert(
                    "API_PRINCIPAL_EXPIRY",
                    "Managed API principal expiry",
                    "WARNING",
                    "fixture",
                    Map.of("principalId", "principal-1"))
                    .get(5, TimeUnit.SECONDS));
            verify(sender).send(any(MimeMessage.class));
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAsync
    public static class TestConfiguration {

        @Bean(name = "taskExecutor")
        public Executor taskExecutor() {
            ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
            executor.setCorePoolSize(1);
            executor.setMaxPoolSize(1);
            executor.setQueueCapacity(10);
            executor.setThreadNamePrefix("notification-test-");
            executor.initialize();
            return executor;
        }

        @Bean
        public NotificationConfig notificationConfig() {
            NotificationConfig config = new NotificationConfig();
            config.setEnabled(true);
            config.getEmail().setEnabled(true);
            config.getEmail().setFrom("alerts@example.com");
            config.getEmail().setTo(List.of("operator@example.com"));
            return config;
        }

        @Bean
        public JavaMailSender javaMailSender() {
            JavaMailSender sender = mock(JavaMailSender.class);
            when(sender.createMimeMessage()).thenReturn(
                    mock(MimeMessage.class));
            return sender;
        }

        @Bean
        public EmailNotificationService emailNotificationService(
                NotificationConfig config, JavaMailSender sender) {
            return new EmailNotificationService(config, sender);
        }
    }
}
