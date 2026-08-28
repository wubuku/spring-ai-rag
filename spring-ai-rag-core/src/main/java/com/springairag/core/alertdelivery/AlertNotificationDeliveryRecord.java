package com.springairag.core.alertdelivery;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 数据库中的 durable delivery 快照。 */
public record AlertNotificationDeliveryRecord(
        UUID id,
        long alertId,
        int notificationVersion,
        boolean managedCondition,
        String provider,
        String status,
        AlertNotificationPayload payload,
        int attemptCount,
        int attemptBudget,
        int manualRetryCount,
        OffsetDateTime nextAttemptAt,
        UUID leaseToken,
        OffsetDateTime leaseUntil,
        String lastErrorCode,
        Integer lastHttpStatus,
        OffsetDateTime lastAttemptAt,
        OffsetDateTime deliveredAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
