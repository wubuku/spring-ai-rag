package com.springairag.core.tenant;

/**
 * WP6：租户成员/状态守卫（实施规划 §5.3.6 / §6 WP6）。
 *
 * <p>在端点/任务执行前校验：上下文存在、工作项 tenant 与上下文一致、
 * 租户状态 ACTIVE。无上下文一律 fail-closed（规划 §4.4）。</p>
 *
 * <p>RLS 只保证「绑定租户合法」，不保证「工作项属于该租户」；本守卫补上第二层
 * 一致性断言（参考项目经验：合法但错误的 GUC 不能靠 RLS 发现串台）。</p>
 */
public final class TenantMembershipGuard {

    public void verify(TenantContext context, String targetTenantId, String tenantStatus) {
        if (context == null) {
            throw new IllegalStateException(
                    "无租户上下文（TenantContext），拒绝执行租户作用域操作（fail-closed）");
        }
        if (!context.tenantId().equals(targetTenantId)) {
            throw new IllegalStateException(
                    "工作项租户与上下文不一致：context tenant=" + context.tenantId()
                            + "，target tenant=" + targetTenantId + "（拒绝串台）");
        }
        if (!"ACTIVE".equalsIgnoreCase(tenantStatus)) {
            throw new IllegalStateException(
                    "租户状态不可执行：" + tenantStatus + "（仅 ACTIVE 允许）");
        }
    }
}
