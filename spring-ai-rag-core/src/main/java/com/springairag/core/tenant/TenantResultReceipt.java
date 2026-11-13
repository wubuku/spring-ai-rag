package com.springairag.core.tenant;

/**
 * WP8：结果 owner 的持久化 receipt（实施规划 §5.4「结果 owner/tenant/receipt」）。
 *
 * <p>task ID 只是标识符，不是授权证明；必须同时携带 tenant、collection（资源作用域）、
 * owner、receipt 四类证据。缺任一字段即拒绝构造，避免「volatile registry 命中不验 owner」
 * 反模式（参考项目案例 06）。</p>
 */
public record TenantResultReceipt(
        String operationId,
        String tenantId,
        String collectionId,
        String ownerPrincipalId,
        String receiptId,
        String resultUrl) {

    public TenantResultReceipt {
        require(operationId, "operationId");
        require(tenantId, "tenantId");
        require(collectionId, "collectionId");
        require(ownerPrincipalId, "ownerPrincipalId");
        require(receiptId, "receiptId");
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }
}
