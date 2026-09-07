package com.springairag.core.alertdelivery;

import com.springairag.core.config.NotificationConfig;
import com.springairag.core.config.RagNotificationDeliveryProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Durable 模式启动门禁：配置缺失放行、不可控 provider 拒绝启动。 */
class AlertNotificationProviderValidatorTest {

    private NotificationConfig notificationConfig;
    private RagNotificationDeliveryProperties deliveryProperties;

    @BeforeEach
    void setUp() {
        notificationConfig = mock(NotificationConfig.class);
        deliveryProperties = new RagNotificationDeliveryProperties();
        when(notificationConfig.getDelivery()).thenReturn(deliveryProperties);
    }

    private AlertNotificationProvider provider(
            boolean configured, boolean available) {
        AlertNotificationProvider provider = mock(AlertNotificationProvider.class);
        when(provider.isConfigured()).thenReturn(configured);
        when(provider.isCurrentlyAvailable()).thenReturn(available);
        when(provider.provider()).thenReturn("dingtalk");
        return provider;
    }

    private void enableDurableDelivery() {
        deliveryProperties.setEnabled(true);
        when(notificationConfig.isEnabled()).thenReturn(true);
    }

    @Test
    void skipsValidationWhenDeliveryDisabled() {
        deliveryProperties.setEnabled(false);
        when(notificationConfig.isEnabled()).thenReturn(true);

        AlertNotificationProviderValidator validator =
                new AlertNotificationProviderValidator(
                        notificationConfig,
                        List.of(provider(true, false)));

        assertDoesNotThrow(validator::validate);
    }

    @Test
    void skipsValidationWhenNotificationsDisabled() {
        deliveryProperties.setEnabled(true);
        when(notificationConfig.isEnabled()).thenReturn(false);

        AlertNotificationProviderValidator validator =
                new AlertNotificationProviderValidator(
                        notificationConfig,
                        List.of(provider(true, false)));

        assertDoesNotThrow(validator::validate);
    }

    @Test
    void rejectsConfiguredButUnavailableProviders() {
        enableDurableDelivery();

        AlertNotificationProviderValidator validator =
                new AlertNotificationProviderValidator(
                        notificationConfig,
                        List.of(
                                provider(true, true),
                                provider(true, false)));

        IllegalStateException error = assertThrows(
                IllegalStateException.class, validator::validate);
        assertTrue(error.getMessage().contains("dingtalk"));
    }

    @Test
    void acceptsUnavailableProvidersWithoutDeclaredRoutes() {
        enableDurableDelivery();

        AlertNotificationProviderValidator validator =
                new AlertNotificationProviderValidator(
                        notificationConfig,
                        List.of(provider(false, false)));

        assertDoesNotThrow(validator::validate);
    }
}
