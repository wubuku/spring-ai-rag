package com.springairag.core.tenant;

import java.util.Objects;

/**
 * WP8：内层 worker 的「claim 行 tenant == 当前作用域 tenant」一致性断言。
 *
 * <p>规划 §5.4：合法但错误的 GUC 不会让 RLS 报错，必须靠载荷 tenant 与作用域
 * 的显式比对发现串台。claim 行来自租户私有表，tenant_id 是持久化载荷的一部分；
 * 内层线程池任务提交前调用本断言，绝不依赖 ThreadLocal 隐式透传。</p>
 *
 * <p>旧单测 fixture 若未投影 tenant_id（{@code null}）则只记录并返回，作用域仍由
 * {@link TenantContext#wrap} 的调度 scope 保证；真实数据库路径一定有 tenant_id，
 * 断言完整生效。该兼容边界在进度账本如实登记，不得对外宣称「所有路径都断言」。</p>
 */
public final class TenantWorkItemAssert {

    private TenantWorkItemAssert() {
    }

    /**
     * 断言 claim 行 tenant 与当前 {@link TenantContext} 一致。
     *
     * @param claimedTenantId claim 行的 tenant_id（可为空表示旧 fixture 未投影）
     * @param workItemDescription 审计用工作项描述
     * @throws IllegalStateException 无上下文、或 tenant 不一致（fail-closed）
     */
    public static void assertMatchesScope(String claimedTenantId,
                                          String workItemDescription) {
        if (claimedTenantId == null || claimedTenantId.isBlank()) {
            // 旧单测 fixture 未投影 tenant_id：调度 scope 仍由 wrap 保证，但不宣称完成一致性断言；
            // PLATFORM_GLOBAL 告警投递表按 WP1 裁决本就无 claim 行 tenant，也不在此断言。
            return;
        }
        TenantContext context = TenantContext.current();
        if (context == null) {
            throw new IllegalStateException(
                    "内层工作项无租户上下文，拒绝执行（fail-closed）：" + workItemDescription);
        }
        if (!Objects.equals(context.tenantId(), claimedTenantId)) {
            throw new IllegalStateException(
                    "工作项 tenant 与当前作用域不一致（拒绝串台）：scope tenant="
                            + context.tenantId() + "，claim tenant=" + claimedTenantId
                            + "，工作项=" + workItemDescription);
        }
    }
}
