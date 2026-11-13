package com.springairag.core.tenant;

import java.util.Objects;

/**
 * WP9：创建租户的入参（租户行 + 初始 principal 的同事务原子创建）。
 *
 * @param tenantId       租户 id（须匹配 {@code ^[a-z0-9][a-z0-9_-]{0,63}$}）
 * @param displayName    展示名（可空）
 * @param actorId        操作者（平台 capability 白名单内的 actor），用于审计
 * @param principalId    初始 principal id（可空 → 由服务生成）
 */
public record TenantCreateCommand(
        String tenantId,
        String displayName,
        String actorId,
        String principalId) {

    public TenantCreateCommand {
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        Objects.requireNonNull(actorId, "actorId must not be null");
    }

    public TenantCreateCommand(String tenantId, String displayName, String actorId) {
        this(tenantId, displayName, actorId, null);
    }
}
