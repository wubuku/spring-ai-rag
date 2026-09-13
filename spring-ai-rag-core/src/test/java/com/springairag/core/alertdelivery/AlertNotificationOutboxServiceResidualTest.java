package com.springairag.core.alertdelivery;

import com.springairag.core.config.NotificationConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 告警 outbox 残余（Batch 368）：重复 provider 拒绝、通知总开关
 * 读取、已配置 provider 的过滤排序。
 */
class AlertNotificationOutboxServiceResidualTest {

    private AlertNotificationProvider provider(
            String name, boolean configured) {
        AlertNotificationProvider provider =
                mock(AlertNotificationProvider.class);
        when(provider.provider()).thenReturn(name);
        when(provider.isConfigured()).thenReturn(configured);
        return provider;
    }

    private NotificationConfig config(boolean enabled) {
        NotificationConfig config = new NotificationConfig();
        config.setEnabled(enabled);
        config.getDelivery().setEnabled(true);
        config.getDelivery().setMaxAttempts(3);
        return config;
    }

    private AlertNotificationOutboxService service(
            NotificationConfig config,
            AlertNotificationProvider... providers) {
        return new AlertNotificationOutboxService(
                mock(AlertNotificationDeliveryRepository.class),
                mock(AlertNotificationPayloadSanitizer.class),
                mock(AlertNotificationWakeupPublisher.class),
                config,
                List.of(providers));
    }

    @Test
    void duplicateProvidersAreRejected() {
        // mock 构造移出 lambda：避免 stubbing 与待断言异常相互干扰。
        var first = provider("webhook", true);
        var second = provider("webhook", true);
        var config = config(true);

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> service(config, first, second));
        assertTrue(error.getMessage()
                .contains("Duplicate alert notification provider"));
    }

    @Test
    void notificationsEnabledReflectsMasterConfig() {
        assertTrue(service(config(true), provider("webhook", true))
                .notificationsEnabled());
        assertEquals(false, service(config(false), provider("webhook", true))
                .notificationsEnabled());
    }

    @Test
    void configuredProvidersFilterUnconfiguredAndSortByName() {
        AlertNotificationOutboxService service = service(
                config(true),
                provider("webhook", true),
                provider("email", false),
                provider("dingtalk", true));

        assertEquals(List.of("dingtalk", "webhook"),
                service.configuredProviders());
    }
}
