package com.springairag.core.tenant;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * WP6：平台 capability 注册表（实施规划 §4.6 / §6 WP6）。
 *
 * <p>跨租户 discovery 只返回 {@link TenantProjection} 最小身份投影；
 * 命名查询由 {@link PlatformCapability} 枚举收口；actor 白名单 + 每次调用审计；
 * 未登记 capability 或未在白名单内的 actor 一律拒绝。</p>
 *
 * <p>业务包不得依赖本类（由静态边界测试保证），只能经装配层注入。</p>
 */
public final class PlatformCapabilityRegistry {

    private final Map<PlatformCapability, Function<String, TenantProjection>> handlers =
            new EnumMap<>(PlatformCapability.class);
    private final Set<String> allowedActors = new LinkedHashSet<>();
    private final List<PlatformAuditEvent> auditEvents = new ArrayList<>();

    public void register(PlatformCapability capability,
                         Function<String, TenantProjection> handler) {
        if (capability == null || handler == null) {
            throw new IllegalArgumentException("capability and handler must not be null");
        }
        handlers.put(capability, handler);
    }

    public void allowActor(String actorId) {
        if (actorId == null || actorId.isBlank()) {
            throw new IllegalArgumentException("actorId must not be blank");
        }
        allowedActors.add(actorId);
    }

    /**
     * 执行已登记 capability；未登记 / actor 未在白名单 → 拒绝并记审计。
     */
    public TenantProjection execute(PlatformCapability capability,
                                    String actorId,
                                    String targetTenantId) {
        Function<String, TenantProjection> handler = handlers.get(capability);
        boolean allowed = handler != null && allowedActors.contains(actorId);
        auditEvents.add(new PlatformAuditEvent(
                capability, actorId, targetTenantId, allowed));
        if (handler == null) {
            throw new IllegalStateException("未登记的平台 capability：" + capability);
        }
        if (!allowed) {
            throw new IllegalStateException(
                    "actor 未在平台 capability 白名单内：" + actorId);
        }
        return handler.apply(targetTenantId);
    }

    public List<PlatformAuditEvent> auditEvents() {
        return List.copyOf(auditEvents);
    }
}
