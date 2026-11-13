package com.springairag.core.tenant;

import java.time.Instant;

/**
 * WP8：回调请求（body 字段只是传输内容，不构成权威）。
 */
public record TenantCallbackRequest(
        String operation,
        String providerName,
        String providerTaskId,
        Instant receivedAt,
        String nonce,
        String signature,
        String bodyStatus,
        String bodyResultUrl) {

    public TenantCallbackRequest {
        require(operation, "operation");
        require(providerName, "providerName");
        require(providerTaskId, "providerTaskId");
        if (receivedAt == null) {
            throw new IllegalArgumentException("receivedAt is required");
        }
        require(nonce, "nonce");
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }
}
