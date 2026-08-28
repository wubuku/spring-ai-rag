package com.springairag.api.dto;

import java.util.List;

/** 告警通知投递回执的 keyset 分页响应。 */
public record AlertNotificationDeliveryPageResponse(
        boolean notificationsEnabled,
        boolean durableDeliveryEnabled,
        List<String> configuredProviders,
        List<AlertNotificationDeliveryResponse> items,
        int limit,
        boolean hasMore,
        String nextCursor) {
}
