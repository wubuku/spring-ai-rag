package com.springairag.core.tenant;

/** WP6：平台 capability 调用审计事件（capability/actor/target，不含私有正文）。 */
public record PlatformAuditEvent(
        PlatformCapability capability,
        String actorId,
        String targetTenantId,
        boolean allowed) {
}
