package com.springairag.core.alertdelivery;

import com.springairag.core.config.NotificationConfig;
import com.springairag.core.entity.RagAlert;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 在告警事务中原子创建 durable provider delivery。 */
@Service
public class AlertNotificationOutboxService {

    private final AlertNotificationDeliveryRepository repository;
    private final AlertNotificationPayloadSanitizer sanitizer;
    private final AlertNotificationWakeupPublisher wakeupPublisher;
    private final NotificationConfig notificationConfig;
    private final Map<String, AlertNotificationProvider> providers;

    public AlertNotificationOutboxService(
            AlertNotificationDeliveryRepository repository,
            AlertNotificationPayloadSanitizer sanitizer,
            AlertNotificationWakeupPublisher wakeupPublisher,
            NotificationConfig notificationConfig,
            List<AlertNotificationProvider> providers) {
        this.repository = repository;
        this.sanitizer = sanitizer;
        this.wakeupPublisher = wakeupPublisher;
        this.notificationConfig = notificationConfig;
        Map<String, AlertNotificationProvider> indexed = new LinkedHashMap<>();
        for (AlertNotificationProvider provider : providers) {
            AlertNotificationProvider previous =
                    indexed.put(provider.provider(), provider);
            if (previous != null) {
                throw new IllegalStateException(
                        "Duplicate alert notification provider: "
                                + provider.provider());
            }
        }
        this.providers = Map.copyOf(indexed);
    }

    public boolean isDurableEnabled() {
        return notificationConfig.getDelivery().isEnabled();
    }

    public boolean notificationsEnabled() {
        return notificationConfig.isEnabled();
    }

    public List<String> configuredProviders() {
        return providers.values().stream()
                .filter(AlertNotificationProvider::isConfigured)
                .map(AlertNotificationProvider::provider)
                .sorted()
                .toList();
    }

    public AlertNotificationProvider provider(String provider) {
        return providers.get(provider);
    }

    public int enqueueOrdinary(RagAlert alert) {
        if (!isDurableEnabled()) {
            return 0;
        }
        return enqueue(
                alert.getId(), 1, false,
                alert.getAlertType(), alert.getAlertName(),
                alert.getSeverity(), alert.getMessage(), alert.getMetrics());
    }

    public int enqueueManaged(
            long alertId,
            int notificationVersion,
            String alertType,
            String alertName,
            String severity,
            String message,
            Map<String, Object> metrics) {
        if (!isDurableEnabled()) {
            return 0;
        }
        repository.supersedeOlderManaged(alertId, notificationVersion);
        return enqueue(
                alertId, notificationVersion, true,
                alertType, alertName, severity, message, metrics);
    }

    public void supersedeManaged(long alertId) {
        if (isDurableEnabled()) {
            repository.supersedeManaged(alertId);
        }
    }

    private int enqueue(
            long alertId,
            int notificationVersion,
            boolean managedCondition,
            String alertType,
            String alertName,
            String severity,
            String message,
            Map<String, Object> metrics) {
        int inserted = 0;
        for (AlertNotificationProvider provider : providers.values()) {
            if (!provider.isRoutedFor(alertType)) {
                continue;
            }
            UUID deliveryId = UUID.randomUUID();
            AlertNotificationPayloadSanitizer.SanitizedPayload payload =
                    sanitizer.create(
                            deliveryId, alertType, alertName,
                            severity, message, metrics);
            if (repository.insert(
                    deliveryId,
                    alertId,
                    notificationVersion,
                    managedCondition,
                    provider.provider(),
                    payload.json(),
                    notificationConfig.getDelivery().getMaxAttempts())) {
                inserted++;
            }
        }
        if (inserted > 0) {
            wakeupPublisher.publishAfterCommit();
        }
        return inserted;
    }
}
