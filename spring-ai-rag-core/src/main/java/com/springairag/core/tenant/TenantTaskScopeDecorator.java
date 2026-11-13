package com.springairag.core.tenant;

import java.util.Objects;

/**
 * WP7：@Scheduled / @Async 方法级的租户作用域包装器（实施规划 §5.4 / §6 WP7）。
 *
 * <p>语义按 {@link TenantScopedJob} 注解分为两档：</p>
 * <ul>
 *   <li><b>SINGLE</b>：无上下文 → 显式 bootstrap {@code default}（N=1 特例），
 *       执行后 restore/clear；有上下文 → 保持不动（请求链路直接触发时
 *       上下文已存在，不得覆盖）；</li>
 *   <li><b>STRICT</b>：无上下文 → 不执行（fail-closed 告警），返回 noop；
 *       有上下文 → 保持不动。</li>
 * </ul>
 *
 * <p>逐租户遍历语义由 {@link TenantJobDispatcher#dispatchForEachActiveTenant} 承担，
 * 本装饰器只负责「方法级上下文 bootstrap / 保持」这一最小合同。</p>
 */
public final class TenantTaskScopeDecorator {

    private final TenantMode mode;

    public TenantTaskScopeDecorator(TenantMode mode) {
        this.mode = Objects.requireNonNull(mode, "mode must not be null");
    }

    /**
     * 包装一个任务体：进入时按模式 bootstrap 租户上下文，结束时还原线程原值。
     *
     * <p>SINGLE 无上下文时 bootstrap default；STRICT 无上下文时返回 noop
     * （不静默读 0 行）。有上下文时原样返回任务体（保持请求链路上下文）。</p>
     */
    public Runnable decorate(Runnable task) {
        Objects.requireNonNull(task, "task must not be null");
        TenantContext existing = TenantContext.current();
        if (existing != null) {
            return task;
        }
        if (mode == TenantMode.STRICT) {
            return () -> { };
        }
        return () -> {
            try {
                TenantContext.set(new TenantContext(
                        "default", null, TenantMode.SINGLE, null));
                task.run();
            } finally {
                TenantContext.clear();
            }
        };
    }
}
