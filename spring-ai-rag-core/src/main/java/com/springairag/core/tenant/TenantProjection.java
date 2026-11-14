package com.springairag.core.tenant;

/**
 * WP6：跨租户 discovery 的最小身份投影。
 *
 * <p>只含 tenantId 与 status；不含名称、配额、正文、凭据等私有字段，
 * 防止平台 capability 返回面无意扩大（规划 §4.6）。</p>
 */
public record TenantProjection(String tenantId, String status) {
}
