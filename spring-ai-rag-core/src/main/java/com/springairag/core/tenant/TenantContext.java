package com.springairag.core.tenant;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * WP5：一次请求 / 任务 / 事务的租户作用域快照（实施规划 §5.3 / §4.5）。
 *
 * <p>不可变值对象，包含 tenant_id、actor（principal）、运行模式与审计标识。
 * 通过 {@link #set(TenantContext)} / {@link #clear()} 绑定到当前线程；
 * 事务内禁止切换租户：调用 {@link #markTransactionScope()} 后，任何与当前
 * tenantId 不一致的 {@code set} 都抛 {@link IllegalStateException}。</p>
 *
 * <p>无上下文时 {@link #current()} 返回 {@code null}；调用方必须按规划 §4.4
 * 「无上下文 fail-closed」处理，不能视为 default。</p>
 *
 * <p>WP7 新增 {@link #wrap(Runnable)}：显式携带当前租户上下文跨线程执行，
 * 结束时还原目标线程原有上下文。这是「跨线程传播租户」的合同入口，
 * 不依赖 ThreadLocal 隐式透传（参考项目教训：异步丢上下文是生产事故高发点）。</p>
 */
public final class TenantContext {

    private static final ThreadLocal<TenantContext> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> IN_TRANSACTION_SCOPE =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    private final String tenantId;
    private final String actorId;
    private final TenantMode mode;
    private final String auditId;

    public TenantContext(String tenantId, String actorId, TenantMode mode) {
        this(tenantId, actorId, mode, null);
    }

    public TenantContext(String tenantId, String actorId, TenantMode mode, String auditId) {
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId must not be null");
        this.actorId = actorId;
        this.mode = Objects.requireNonNull(mode, "mode must not be null");
        this.auditId = auditId;
    }

    public String tenantId() { return tenantId; }
    public String actorId() { return actorId; }
    public TenantMode mode() { return mode; }
    public String auditId() { return auditId; }

    /**
     * 绑定当前线程上下文。事务作用域内只允许绑定同一租户（重复 set 幂等），
     * 切换租户直接拒绝，防止 Hibernate Session / GUC / RLS 三层不一致
     * （参考项目经验：事务内切租户会污染数据）。
     */
    public static void set(TenantContext context) {
        Objects.requireNonNull(context, "context must not be null");
        TenantContext previous = CURRENT.get();
        if (Boolean.TRUE.equals(IN_TRANSACTION_SCOPE.get()) && previous != null
                && !previous.tenantId.equals(context.tenantId)) {
            throw new IllegalStateException("事务内禁止切换租户：当前 tenant="
                    + previous.tenantId + "，试图切换到 tenant=" + context.tenantId
                    + "；请在外层无事务作用域切换后开启新事务");
        }
        CURRENT.set(context);
    }

    public static TenantContext current() { return CURRENT.get(); }

    public static String currentTenantId() {
        TenantContext c = CURRENT.get();
        return c == null ? null : c.tenantId;
    }

    /**
     * 标记「已进入事务作用域」。由 DataSource 在借出连接并开启事务时调用；
     * 事务结束必须调用 {@link #clear()} 复位。
     */
    public static void markTransactionScope() {
        IN_TRANSACTION_SCOPE.set(Boolean.TRUE);
    }

    public static boolean inTransactionScope() {
        return Boolean.TRUE.equals(IN_TRANSACTION_SCOPE.get());
    }

    /** 清理线程绑定与事务标记；请求 / 任务 / 事务 finally 中必须调用。 */
    public static void clear() {
        CURRENT.remove();
        IN_TRANSACTION_SCOPE.remove();
    }

    /**
     * WP7：显式携带当前租户上下文跨线程执行（bootstrap—assert—restore—clear）。
     *
     * <p>提交时捕获当前上下文；目标线程先恢复线程原值，进入任务前按捕获快照
     * 显式 set（bootstrap），任务内可断言 tenant；finally 还原目标线程原有
     * 上下文（restore），绝不隐式清空线程池复用线程的原值。</p>
     *
     * @param task 待包装任务体
     * @return 包装后的 Runnable；提交时无上下文则原样返回（语义由调用方负责：
     *         STRICT 下未 bootstrap 会读 0 行，包装不背锅）
     */
    public static Runnable wrap(Runnable task) {
        Objects.requireNonNull(task, "task must not be null");
        TenantContext snapshot = CURRENT.get();
        return () -> {
            TenantContext previous = CURRENT.get();
            if (snapshot == null) {
                task.run();
                return;
            }
            CURRENT.set(snapshot);
            try {
                task.run();
            } finally {
                if (previous == null) {
                    clear();
                } else {
                    CURRENT.set(previous);
                    IN_TRANSACTION_SCOPE.remove();
                }
            }
        };
    }

    /** {@link #wrap(Runnable)} 的 Supplier 版本：与 Runnable 一致，在 wrap() 调用时捕获快照。 */
    public static <T> Supplier<T> wrap(Supplier<T> task) {
        Objects.requireNonNull(task, "task must not be null");
        TenantContext snapshot = CURRENT.get();
        return () -> {
            TenantContext previous = CURRENT.get();
            if (snapshot == null) {
                return task.get();
            }
            CURRENT.set(snapshot);
            try {
                return task.get();
            } finally {
                if (previous == null) {
                    clear();
                } else {
                    CURRENT.set(previous);
                    IN_TRANSACTION_SCOPE.remove();
                }
            }
        };
    }
}
