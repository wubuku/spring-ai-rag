package com.springairag.core.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 启动期 PostConstruct 校验器：默认配置放行、越界配置拒绝。
 * 各校验器只是把 validate() 委托给对应 properties 分组。
 */
class PostConstructPropertyValidatorsTest {

    private static RagProperties newProperties() {
        return new RagProperties();
    }

    @Test
    void chatValidatorPassesOnDefaultsAndRejectsNonPositiveLimits() {
        RagProperties properties = newProperties();
        assertDoesNotThrow(new RagChatPropertiesValidator(properties)::validate);

        properties.getChat().getAgent().setMaxToolRounds(0);
        // chat 分组的 invalid() 抛 IllegalStateException。
        IllegalStateException error = assertThrows(IllegalStateException.class,
                new RagChatPropertiesValidator(properties)::validate);
        assertTrue(error.getMessage().contains("rag.chat.agent.max-tool-rounds"));
    }

    @Test
    void usageValidatorPassesOnDefaultsAndRejectsOutOfRangeRetention() {
        RagProperties properties = newProperties();
        assertDoesNotThrow(new RagUsagePropertiesValidator(properties)::validate);

        properties.getUsage().setRetentionDays(10);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                new RagUsagePropertiesValidator(properties)::validate);
        assertTrue(error.getMessage().contains("rag.usage.retention-days"));
    }

    @Test
    void integrationObservabilityValidatorPassesOnDefaultsAndRejectsShortRetention() {
        RagProperties properties = newProperties();
        assertDoesNotThrow(
                new RagIntegrationObservabilityPropertiesValidator(properties)::validate);

        properties.getIntegrationObservability().setRetention(Duration.ofDays(1));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                new RagIntegrationObservabilityPropertiesValidator(properties)::validate);
        assertTrue(error.getMessage().contains("rag.integration-observability.retention"));
    }

    @Test
    void notificationDeliveryValidatorPassesOnDefaultsAndRejectsCrossFieldConflict() {
        NotificationConfig config = new NotificationConfig();
        RagNotificationDeliveryProperties delivery = config.getDelivery();
        assertDoesNotThrow(new RagNotificationDeliveryPropertiesValidator(config)::validate);

        delivery.setWorkerConcurrency(8);
        delivery.setClaimBatchSize(4);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                new RagNotificationDeliveryPropertiesValidator(config)::validate);
        assertTrue(error.getMessage().contains("claim-batch-size"));
    }
}
