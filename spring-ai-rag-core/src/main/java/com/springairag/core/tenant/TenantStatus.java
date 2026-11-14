package com.springairag.core.tenant;

/**
 * WP9：租户生命周期状态（与 {@code rag_tenant.status} 的 CHECK 约束一致）。
 *
 * <p>状态机是<b>单向收紧</b>的：{@code DISABLED} 是终态，不可恢复；
 * {@code SUSPENDED} 可恢复为 {@code ACTIVE}；软删（{@code deleted_at}）是独立标记，
 * 与状态正交——软删后不再出现在 ACTIVE 租户枚举里，但历史行仍保留以供审计。</p>
 *
 * <p>取值必须与 V75 的 {@code CHECK (status IN ('ACTIVE','SUSPENDED','DISABLED'))} 一致；
 * 任何新增状态都必须同时改迁移（不可改写已执行迁移）与凭据解析的 ACTIVE 判定。</p>
 */
public enum TenantStatus {

    /** 正常服务中。 */
    ACTIVE,
    /** 暂停：凭据解析 fail-closed（不再解析出该租户），数据保留。 */
    SUSPENDED,
    /** 禁用：终态，不可恢复。 */
    DISABLED;

    /** 由字面量解析；未知值一律 fail-closed（视为 DISABLED）。 */
    public static TenantStatus from(String value) {
        if (value == null) {
            return DISABLED;
        }
        for (TenantStatus status : values()) {
            if (status.name().equalsIgnoreCase(value.trim())) {
                return status;
            }
        }
        return DISABLED;
    }

    /**
     * 状态迁移是否允许。
     *
     * <p>允许：ACTIVE→SUSPENDED、SUSPENDED→ACTIVE、ACTIVE→DISABLED。
     * 禁止：任何→ACTIVE 以外的自环重复之外均可回 ACTIVE？——不允许：
     * DISABLED 不可回到 ACTIVE/SUSPENDED；SUSPENDED 不可直接 DISABLED（须先恢复再禁用，
     * 避免「跳过恢复」造成运维误判）。</p>
     */
    public boolean canTransitionTo(TenantStatus target) {
        if (target == null) {
            return false;
        }
        return switch (this) {
            case ACTIVE -> target == SUSPENDED || target == DISABLED;
            case SUSPENDED -> target == ACTIVE;
            case DISABLED -> false;
        };
    }
}
