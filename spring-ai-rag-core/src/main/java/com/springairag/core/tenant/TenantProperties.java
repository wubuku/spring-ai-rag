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

    public TenantMode getMode() {
        return mode;
    }

    public void setMode(TenantMode mode) {
        this.mode = mode;
    }
}
