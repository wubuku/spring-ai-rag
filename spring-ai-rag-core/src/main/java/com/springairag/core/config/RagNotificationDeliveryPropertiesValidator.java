package com.springairag.core.config;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/** 启动时校验 durable notification 配置。 */
@Component
public class RagNotificationDeliveryPropertiesValidator {

    private final RagNotificationDeliveryProperties properties;

    public RagNotificationDeliveryPropertiesValidator(NotificationConfig notificationConfig) {
        this.properties = notificationConfig.getDelivery();
    }

    @PostConstruct
    void validate() {
        properties.validate();
    }
}
