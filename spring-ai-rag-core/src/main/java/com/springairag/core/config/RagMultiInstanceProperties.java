package com.springairag.core.config;

/**
 * 多实例（多副本共享同一个 PostgreSQL）部署声明。
 *
 * <p>持久化任务、聊天租约、限流计数与预算窗口都通过数据库条件写入/租约实现，
 * 多副本可以安全共享；但 {@code local} 限流后端和 {@code AdmissionService}
 * 的内存并发池天然是"每进程一份"。声明多实例后，启动期会拒绝
 * {@code rate-limit.enabled=true + backend=local} 这种会被静默放大 N 倍的组合。
 *
 * <p>示例：
 * <pre>
 * rag:
 *   multi-instance:
 *     enabled: true
 * </pre>
 */
public class RagMultiInstanceProperties {

    private boolean enabled = false;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * 校验多实例下不可用的本地配置组合。
     *
     * @param rateLimit 限流配置
     */
    public void validate(RagRateLimitProperties rateLimit) {
        if (!enabled || rateLimit == null || !rateLimit.isEnabled()) {
            return;
        }
        if ("local".equals(rateLimit.getBackend())) {
            throw new IllegalStateException(
                    "rag.rate-limit.backend=local keeps counters inside each process; "
                            + "with rag.multi-instance.enabled=true every replica would "
                            + "get its own full quota. Set rag.rate-limit.backend=postgresql "
                            + "(and strategy=principal) before scaling out.");
        }
    }
}
