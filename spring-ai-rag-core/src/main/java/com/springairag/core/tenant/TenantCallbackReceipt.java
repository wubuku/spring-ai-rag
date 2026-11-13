package com.springairag.core.tenant;

/**
 * WP8：持久化回调 receipt（绑定 operation/provider/provider task/tenant/owner/actor）。
 *
 * <p>receipt 是回调权威的唯一来源：没有 receipt 就无法确认 owner 与 tenant，
 * 回调只能停留在显式 UNKNOWN / wake-up 语义。</p>
 */
public record TenantCallbackReceipt(
        String operation,
        String providerName,
        String providerTaskId,
        String tenantId,
        String ownerReference,
        String actorId) {

    public TenantCallbackReceipt {
        require(operation, "operation");
        require(providerName, "providerName");
        require(providerTaskId, "providerTaskId");
        require(tenantId, "tenantId");
        require(ownerReference, "ownerReference");
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }
}
