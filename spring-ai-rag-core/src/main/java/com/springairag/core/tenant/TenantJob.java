package com.springairag.core.tenant;

import java.util.Objects;

/**
 * WP7：任务载荷的租户/actor/audit 三元组（实施规划 §5.4 / §6 WP7）。
 *
 * <p>任务表/事件载荷必须持久化 tenant_id、actor、audit principal；
 * 执行前由 {@link TenantJobDispatcher} 做「载荷 tenant == 当前 scope tenant」一致性断言。
 * 不含 raw credential 或租户私有正文。</p>
 */
public record TenantJob(String tenantId, String actorId, String auditId) {

    // 允许 null，由 TenantJobDispatcher 统一 fail-closed 拒绝；
    // 不在构造器校验，避免「载荷非法」被吞成参数异常而绕过 dispatcher 审计。
    public TenantJob {
    }
}
