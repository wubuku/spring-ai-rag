package com.springairag.core.tenant;

/**
 * WP8：权威 Provider 查询结果。identity 必须与 receipt 一致才允许 APPLIED。
 */
public record TenantProviderResult(
        String operation,
        String providerName,
        String providerTaskId,
        String status,
        String resultUrl) {
}
