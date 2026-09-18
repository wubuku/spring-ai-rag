package com.springairag.core.alertdelivery;

import com.springairag.core.config.NotificationConfig;
import com.springairag.core.config.RagNotificationDeliveryProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AlertNotificationProviderValidator 启动门禁长尾（Batch 516，JaCoCo
 * 驱动）：开关关闭跳过校验、configured+unavailable 拒绝启动、未配置
 * provider 不阻断启动。
 */
class AlertNotificationProviderValidatorTailTest {

    private NotificationConfig notificationConfig;

    @BeforeEach
    void setUp() {
        notificationConfig = new NotificationConfig();
        notificationConfig.setEnabled(true);
        // delivery 为只读聚合：直接修改 getDelivery() 返回的实例。
        RagNotificationDeliveryProperties delivery =
                notificationConfig.getDelivery();
        delivery.setEnabled(true);
    }

    private AlertNotificationProvider provider(
            boolean configured, boolean available) {
        AlertNotificationProvider provider =
                mock(AlertNotificationProvider.class);
        when(provider.provider()).thenReturn("webhook-test");
        when(provider.isConfigured()).thenReturn(configured);
        when(provider.isCurrentlyAvailable()).thenReturn(available);
        return provider;
    }

    @Test
    void skipsValidationWhenDeliveryDisabled() {
        notificationConfig.getDelivery().setEnabled(false);
        var validator = new AlertNotificationProviderValidator(
                notificationConfig,
                List.of(provider(true, false)));
        assertDoesNotThrow(validator::validate);
    }

    @Test
    void skipsValidationWhenNotificationDisabled() {
        notificationConfig.setEnabled(false);
        var validator = new AlertNotificationProviderValidator(
                notificationConfig,
                List.of(provider(true, false)));
        assertDoesNotThrow(validator::validate);
    }

    @Test
    void passesWhenConfiguredProviderIsAvailable() {
        var validator = new AlertNotificationProviderValidator(
                notificationConfig,
                List.of(provider(true, true)));
        assertDoesNotThrow(validator::validate);
    }

    @Test
    void rejectsStartupWhenConfiguredProviderUnavailable() {
        var validator = new AlertNotificationProviderValidator(
                notificationConfig,
                List.of(provider(true, false)));
        var error = assertThrows(IllegalStateException.class,
                validator::validate);
        assertEquals(
                "Durable alert notification provider is unavailable: webhook-test",
                error.getMessage());
    }

    @Test
    void ignoresUnavailableProviderThatIsNotConfigured() {
        var validator = new AlertNotificationProviderValidator(
                notificationConfig,
                List.of(provider(false, false)));
        assertDoesNotThrow(validator::validate);
    }
}
