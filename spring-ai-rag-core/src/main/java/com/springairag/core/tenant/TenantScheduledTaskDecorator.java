package com.springairag.core.tenant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.support.ScheduledMethodRunnable;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * WP7：调度任务的逐租户驱动装饰器（实施规划 §5.4 / §6 WP7）。
 *
 * <p>调度线程本来没有租户上下文。租户私有任务（类上标 {@link TenantScopedJob}）
 * 若直接跑，会因 RLS 读 0 行而静默停摆——这正是参考项目反复警告的
 * 「单租户能跑通掩盖多租户静默 0 行」。因此：</p>
 * <ul>
 *   <li><b>SINGLE</b>：以 {@code default} 作用域执行一次（N=1 特例）；</li>
 *   <li><b>STRICT</b>：由 {@link TenantDirectory} 枚举 ACTIVE 租户，
 *       <b>逐租户</b>各执行一次；每个租户独立租户作用域（独立 GUC → 独立 RLS 过滤），
 *       单租户失败不阻断其余租户；</li>
 *   <li>平台全局任务（未标 {@link TenantScopedJob}）不逐租户执行，只做上下文卫生
 *       （有上下文则传播，无上下文则按 SINGLE bootstrap default）。</li>
 * </ul>
 *
 * <p>实现方式：Spring 调度线程池交给我们的 {@code Runnable} 是
 * {@link ScheduledMethodRunnable}，可反射取到目标 bean 与方法，据此判断该任务
 * 是否租户私有——无需改动 18 个任务类的方法签名。</p>
 */
public final class TenantScheduledTaskDecorator {

    private static final Logger log =
            LoggerFactory.getLogger(TenantScheduledTaskDecorator.class);

    private final TenantMode mode;
    private final Supplier<TenantDirectory> directorySupplier;
    private final TenantJobDispatcher dispatcher;

    public TenantScheduledTaskDecorator(TenantMode mode,
                                        Supplier<TenantDirectory> directorySupplier) {
        this.mode = Objects.requireNonNull(mode, "mode must not be null");
        this.directorySupplier = Objects.requireNonNull(
                directorySupplier, "directorySupplier must not be null");
        this.dispatcher = new TenantJobDispatcher();
    }

    /**
     * 装饰一次调度任务。
     *
     * @param runnable 调度线程池交给我们的任务体（通常是 {@link ScheduledMethodRunnable}）
     */
    public Runnable decorate(Runnable runnable) {
        Objects.requireNonNull(runnable, "runnable must not be null");
        if (!isTenantScoped(runnable)) {
            // 平台全局任务：仅做上下文卫生，不逐租户执行。
            return new TenantTaskScopeDecorator(mode).decorate(runnable);
        }
        return () -> runPerTenant(runnable);
    }

    /** 逐租户执行一次调度任务。 */
    void runPerTenant(Runnable runnable) {
        if (mode == TenantMode.SINGLE) {
            dispatcher.dispatch(
                    new TenantJob("default", null, null),
                    runnable);
            return;
        }
        TenantDirectory directory = directorySupplier.get();
        List<String> tenants;
        try {
            tenants = directory.activeTenantIds();
        } catch (RuntimeException error) {
            // fail-closed：枚举失败不得降级为「按 default 跑一遍」。
            log.error("逐租户调度任务失败：ACTIVE 租户枚举异常，本次跳过（fail-closed）", error);
            return;
        }
        if (tenants.isEmpty()) {
            log.error("逐租户调度任务失败：ACTIVE 租户枚举为空，本次跳过（fail-closed）");
            return;
        }
        java.util.List<String> failures = new java.util.ArrayList<>();
        for (String tenantId : tenants) {
            try {
                dispatcher.dispatch(new TenantJob(tenantId, null, null), runnable);
            } catch (RuntimeException error) {
                log.warn("租户 {} 的调度任务执行失败（不影响其他租户）", tenantId, error);
                failures.add(tenantId + ": " + error.getMessage());
            }
        }
        if (!failures.isEmpty()) {
            log.error("逐租户调度任务存在失败（{}/{}）：{}",
                    failures.size(), tenants.size(), String.join("; ", failures));
        }
    }

    /** 判断该调度任务的目标类是否标注了 {@link TenantScopedJob}。 */
    static boolean isTenantScoped(Runnable runnable) {
        if (!(runnable instanceof ScheduledMethodRunnable scheduled)) {
            return false;
        }
        Object target = scheduled.getTarget();
        if (target == null) {
            return false;
        }
        return target.getClass().isAnnotationPresent(TenantScopedJob.class);
    }
}
