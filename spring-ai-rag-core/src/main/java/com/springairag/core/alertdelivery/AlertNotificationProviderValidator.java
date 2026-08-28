package com.springairag.core.alertdelivery;

import com.springairag.core.config.NotificationConfig;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.List;

/** Durable 模式启动时拒绝不可控超时或缺少必要配置的 provider。 */
@Component
public class AlertNotificationProviderValidator {

    private final NotificationConfig notificationConfig;
    private final List<AlertNotificationProvider> providers;

    public AlertNotificationProviderValidator(
            NotificationConfig notificationConfig,
            List<AlertNotificationProvider> providers) {
        this.notificationConfig = notificationConfig;
        this.providers = providers;
    }

    @PostConstruct
    void validate() {
        if (!notificationConfig.getDelivery().isEnabled()
                || !notificationConfig.isEnabled()) {
            return;
        }
        for (AlertNotificationProvider provider : providers) {
            if (provider.isConfigured()
                    && !provider.isCurrentlyAvailable()) {
                throw new IllegalStateException(
                        "Durable alert notification provider is unavailable: "
                                + provider.provider());
            }
        }
    }
}
