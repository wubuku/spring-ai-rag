package com.springairag.core.tenant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * WP7：线程池统一任务装饰器（实施规划 §5.4 / §6 WP7）。
 *
 * <p>对 {@code AsyncConfig} 的 {@code taskExecutor} 与调度线程统一生效，语义：</p>
 * <ul>
 *   <li>提交方<strong>有</strong>上下文（请求链路触发 @Async）：
 *       {@link TenantContext#wrap(Runnable)} 显式跨线程传播，目标线程结束后
 *       还原线程原值；</li>
 *   <li>提交方<strong>无</strong>上下文且模式为 SINGLE：显式 bootstrap
 *       {@code default}（N=1），执行后清理；</li>
 *   <li>提交方<strong>无</strong>上下文且模式为 STRICT：SINGLE 兜底不生效，
 *       任务按 {@link TenantTaskScopeDecorator#decorate(Runnable)} 的 STRICT
 *       语义处理（noop / 拒绝），防止「单租户下能跑」掩盖多租户静默读 0 行；</li>
 *   <li>逐租户遍历（STRICT 多租户批量任务）不走本装饰器，走
 *       {@link TenantJobDispatcher#dispatchForEachActiveTenant} 显式枚举。</li>
 * </ul>
 */
public final class TenantTaskDecorator implements java.util.concurrent.Executor,
        org.springframework.core.task.TaskDecorator {

    private static final Logger log = LoggerFactory.getLogger(TenantTaskDecorator.class);

    private final TenantMode mode;
    private final TenantTaskScopeDecorator scopeDecorator;

    public TenantTaskDecorator(TenantMode mode) {
        this.mode = Objects.requireNonNull(mode, "mode must not be null");
        this.scopeDecorator = new TenantTaskScopeDecorator(mode);
    }

    /** Spring TaskDecorator 入口（用于 ThreadPoolTaskExecutor / 调度器）。 */
    @Override
    public Runnable decorate(Runnable runnable) {
        Objects.requireNonNull(runnable, "runnable must not be null");
        if (TenantContext.current() != null) {
            return TenantContext.wrap(runnable);
        }
        return scopeDecorator.decorate(runnable);
    }

    /** Executor 入口：便于直接包装自建线程池提交的任务。 */
    @Override
    public void execute(Runnable command) {
        Objects.requireNonNull(command, "command must not be null");
        throw new UnsupportedOperationException(
                "TenantTaskDecorator 是包装器不是执行器；请经 decorate() / wrap() 使用");
    }
}
