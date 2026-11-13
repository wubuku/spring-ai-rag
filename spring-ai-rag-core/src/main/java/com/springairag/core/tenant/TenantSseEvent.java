package com.springairag.core.tenant;

/**
 * WP8：SSE 事件（必带 tenant 与 collection 归属）。
 */
public record TenantSseEvent(
        String tenantId, String collectionId, String eventType, String payload) {

    public TenantSseEvent {
        require(tenantId, "tenantId");
        require(collectionId, "collectionId");
        require(eventType, "eventType");
        require(payload, "payload");
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }
}
