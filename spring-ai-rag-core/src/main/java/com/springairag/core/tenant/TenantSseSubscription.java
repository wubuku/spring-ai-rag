package com.springairag.core.tenant;

/**
 * WP8：SSE 订阅快照（tenant + actor + collection）。
 *
 * <p>订阅建立时的授权结果不能永久代表后续每条消息；订阅本身必须携带归属，
 * 投递点才能逐次比对事件归属并重查当前权限。</p>
 */
public record TenantSseSubscription(String tenantId, String actorId, String collectionId) {

    public TenantSseSubscription {
        require(tenantId, "tenantId");
        require(actorId, "actorId");
        require(collectionId, "collectionId");
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }
}
