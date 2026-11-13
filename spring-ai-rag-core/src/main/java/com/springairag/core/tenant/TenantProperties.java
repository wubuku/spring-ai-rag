package com.springairag.core.tenant;

/**
 * WP5：租户运行模式配置（实施规划 §0 / §8.2）。
 *
 * <pre>
 * rag:
 *   tenant:
 *     mode: SINGLE   # 或 STRICT
 * </pre>
 *
 * <p>默认 SINGLE：无配置变化，单租户继续跑；切 STRICT 由运维显式执行，
 * 并由 {@link TenantModeGuard} 在启动时校验三角色与 RLS。</p>
 */
public class TenantProperties {

    private TenantMode mode = TenantMode.SINGLE;

    /**
     * WP9：允许调用租户生命周期 capability 的 actor 白名单。
     *
     * <p>默认只放行 {@code environment-root}（与 {@code TenantLifecycleController}
     * 的 root 门槛同一身份）；运维可用 {@code rag.tenant.lifecycle-actors} 追加平台账号，
     * 但绝不接受请求 header 里的自称 actor。</p>
     */
    private java.util.List<String> lifecycleActors =
            new java.util.ArrayList<>(java.util.List.of("environment-root"));

    public TenantMode getMode() {
        return mode;
    }

    public void setMode(TenantMode mode) {
        this.mode = mode;
    }

    public java.util.List<String> getLifecycleActors() {
        return lifecycleActors;
    }

    public void setLifecycleActors(java.util.List<String> lifecycleActors) {
        this.lifecycleActors = lifecycleActors == null
                ? new java.util.ArrayList<>()
                : new java.util.ArrayList<>(lifecycleActors);
    }
}
