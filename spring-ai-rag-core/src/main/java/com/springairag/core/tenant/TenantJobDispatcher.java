package com.springairag.core.tenant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * WP7：统一租户任务 dispatcher（实施规划 §5.4 / §6 WP7）。
 *
 * <p>所有租户私有数据作用域的任务统一经本 dispatcher 执行：</p>
 * <ul>
 *   <li>载荷 tenant 与当前上下文一致时执行；不一致拒绝（防串台——
 *       「合法但错误的 GUC」RLS 自身发现不了，参考项目案例 03）；</li>
 *   <li>无上下文时按载荷 tenant 显式建立 scope 执行（CallerRuns 同线程语义），
 *       执行后 finally 清理，不依赖 ThreadLocal 透传；</li>
 *   <li>无载荷 tenant fail-closed 拒绝（不隐式 default）；</li>
 *   <li>任务异常 finally 仍清理上下文，异常原样抛出由调用方/线程池记录。</li>
 * </ul>
 *
 * <p>多租户 STRICT 模式下由 {@link #dispatchForEachActiveTenant} 按租户枚举
 * 逐租户 dispatch，每租户独立事务与上下文；单租户失败不阻断其余租户，
 * 失败汇总上报（仍抛出聚合异常）。SINGLE 模式只跑 {@code default}（N=1）。</p>
 */
public final class TenantJobDispatcher {

    private static final Logger log = LoggerFactory.getLogger(TenantJobDispatcher.class);
    private static final String DEFAULT_TENANT = "default";

    /**
     * 在租户作用域内执行任务体。
     *
     * @param job  任务载荷（tenant/actor/audit 三元组）
     * @param body 任务体；执行期间 {@link TenantContext#currentTenantId()} 为 job.tenantId
     */
    public void dispatch(TenantJob job, Runnable body) {
        String tenantId = job == null ? null : job.tenantId();
        if (tenantId == null || tenantId.isBlank()) {
            log.warn("Tenant job rejected: missing tenantId in payload (fail-closed)");
            throw new IllegalStateException(
                    "任务载荷缺少 tenant_id，拒绝执行（fail-closed）");
        }
        TenantContext current = TenantContext.current();
        if (current != null && !current.tenantId().equals(tenantId)) {
            throw new IllegalStateException(
                    "任务载荷 tenant 与当前上下文不一致：context tenant="
                            + current.tenantId() + "，job tenant=" + tenantId + "（拒绝串台）");
        }
        boolean needsScope = current == null;
        if (needsScope) {
            TenantContext.set(new TenantContext(tenantId, job.actorId(),
                    TenantMode.STRICT, job.auditId()));
        }
        try {
            body.run();
        } finally {
            if (needsScope) {
                TenantContext.clear();
            }
        }
    }

    /**
     * CallerRuns / 异步包装：把载荷 tenant 显式传播到目标线程，
     * 不依赖 ThreadLocal 透传；执行后恢复目标线程原值。
     *
     * @throws IllegalStateException 载荷缺失 tenant 时拒绝（不得隐式兜 default）
     */
    public Runnable scoped(TenantJob job, Runnable body) {
        String tenantId = job == null ? null : job.tenantId();
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalStateException(
                    "任务载荷缺少 tenant_id，拒绝建立租户作用域（fail-closed）");
        }
        String actorId = job == null ? null : job.actorId();
        String auditId = job == null ? null : job.auditId();
        return () -> {
            TenantContext previous = TenantContext.current();
            try {
                TenantContext.set(new TenantContext(tenantId, actorId,
                        TenantMode.STRICT, auditId));
                body.run();
            } finally {
                if (previous == null) {
                    TenantContext.clear();
                } else {
                    TenantContext.set(previous);
                }
            }
        };
    }

    /**
     * WP7：按 ACTIVE 租户枚举逐租户执行一个 {@link TenantScopedJob} 任务体。
     *
     * <p>语义（实施规划 §5.4）：</p>
     * <ul>
     *   <li>SINGLE：只跑 {@code default}（N=1 特例，不经目录）；</li>
     *   <li>STRICT：由 {@link TenantDirectory} 枚举 ACTIVE 租户，逐租户以独立
     *       作用域执行；单租户失败不阻断其余租户；</li>
     *   <li>全部执行后仍有失败 → 抛 {@link IllegalStateException} 聚合失败明细；
     *       目录枚举失败 → 直接抛出（fail-closed，不降级为 default）。</li>
     * </ul>
     *
     * @param directory ACTIVE 租户目录（仅 STRICT 使用）
     * @param mode      当前租户运行模式
     * @param actorId   任务审计 actor
     * @param auditId   任务审计标识
     * @param body      每租户执行一次的任务体
     */
    public void dispatchForEachActiveTenant(
            TenantDirectory directory,
            TenantMode mode,
            String actorId,
            String auditId,
            Runnable body) {
        Objects.requireNonNull(body, "body must not be null");
        List<String> tenants = mode == TenantMode.STRICT
                ? requireTenants(directory)
                : List.of(DEFAULT_TENANT);
        List<String> failures = new ArrayList<>();
        for (String tenantId : tenants) {
            try {
                dispatch(new TenantJob(tenantId, actorId, auditId), body);
            } catch (RuntimeException error) {
                log.warn("Tenant job failed: tenant={}", tenantId, error);
                failures.add(tenantId + ": " + error.getMessage());
            }
        }
        if (!failures.isEmpty()) {
            throw new IllegalStateException(
                    "逐租户任务执行存在失败（共 " + failures.size() + "/" + tenants.size() + "）："
                            + String.join("; ", failures));
        }
    }

    /** 目录枚举失败必须 fail-closed，绝不降级。 */
    private static List<String> requireTenants(TenantDirectory directory) {
        Objects.requireNonNull(directory, "TenantDirectory must not be null in STRICT mode");
        List<String> tenants = directory.activeTenantIds();
        if (tenants == null || tenants.isEmpty()) {
            throw new IllegalStateException(
                    "STRICT 模式 ACTIVE 租户枚举为空：拒绝静默降级（fail-closed）");
        }
        return List.copyOf(tenants);
    }
}
