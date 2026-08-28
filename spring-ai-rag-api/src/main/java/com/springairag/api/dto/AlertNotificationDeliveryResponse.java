package com.springairag.api.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Operator 可见的低敏告警通知投递回执。 */
public record AlertNotificationDeliveryResponse(
        UUID id,
        long alertId,
        int notificationVersion,
        String provider,
        String status,
        int attemptCount,
        int attemptBudget,
        int manualRetryCount,
        OffsetDateTime nextAttemptAt,
        String lastErrorCode,
        Integer lastHttpStatus,
        OffsetDateTime lastAttemptAt,
        OffsetDateTime deliveredAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
