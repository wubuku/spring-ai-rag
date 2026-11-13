package com.springairag.core.tenant;

import java.util.Objects;
import java.util.Optional;

/**
 * WP8：SSE 逐次授权发布器（实施规划 §5.5 / §6 WP8）。
 *
 * <p>对齐参考项目 {@code ../seedance-research} 案例 07「SSE 推送前重新校验 Project
 * 权限」：</p>
 * <ul>
 *   <li>每次投递前重查当前 ACL（不复用建连时的旧授权结果）；</li>
 *   <li>事件的 tenant/collection 必须与订阅完全匹配，跨租户/跨 collection 事件拒绝；</li>
 *   <li>撤权后丢弃本次推送，客户端从持久化 read model 恢复；</li>
 *   <li>无 {@link TenantContext} 时 fail-closed（不推送）；</li>
 *   <li>SSE 是投影通道，不改变持久化业务事实。</li>
 * </ul>
 */
public final class TenantAuthorizedSsePublisher {

    private final TenantSseAccessChecker accessChecker;

    public TenantAuthorizedSsePublisher(TenantSseAccessChecker accessChecker) {
        this.accessChecker = Objects.requireNonNull(
                accessChecker, "accessChecker must not be null");
    }

    /** 投递点授权判定；不允许投递时返回 empty（调用方丢弃本次推送）。 */
    public Optional<TenantSseEvent> publishIfAuthorized(
            TenantSseSubscription subscription, TenantSseEvent event) {
        Objects.requireNonNull(subscription, "subscription must not be null");
        Objects.requireNonNull(event, "event must not be null");
        TenantContext context = TenantContext.current();
        if (context == null) {
            throw new IllegalStateException(
                    "无租户上下文（TenantContext），SSE 投递必须 fail-closed（不推送）");
        }
        if (!subscription.tenantId().equals(context.tenantId())) {
            return Optional.empty();
        }
        if (!subscription.tenantId().equals(event.tenantId())
                || !subscription.collectionId().equals(event.collectionId())) {
            return Optional.empty();
        }
        if (!accessChecker.canRead(subscription.tenantId(),
                subscription.actorId(), subscription.collectionId())) {
            return Optional.empty();
        }
        return Optional.of(event);
    }
}
