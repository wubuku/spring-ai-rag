package com.springairag.core.tenant;

/**
 * WP8：SSE 投递点的当前权限查询（SPI）。
 *
 * <p>实现必须读取<b>当前</b>授权状态（Collection ACL / 成员资格），而不是复用
 * 建连时的旧结果。生产实现由 Collection ACL + 租户成员资格合成。</p>
 */
@FunctionalInterface
public interface TenantSseAccessChecker {

    boolean canRead(String tenantId, String actorId, String collectionId);
}
