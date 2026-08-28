package com.springairag.core.alertdelivery;

import java.util.Map;
import java.util.UUID;

/** 持久化并发送给通知 provider 的有界低敏 payload。 */
public record AlertNotificationPayload(
        UUID deliveryId,
        String alertType,
        String alertName,
        String severity,
        String message,
        Map<String, Object> metrics,
        boolean payloadTruncated) {
}
