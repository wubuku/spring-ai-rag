package com.springairag.core.tenant;

/**
 * WP9：租户生命周期审计端口。
 *
 * <p>实现必须记录：动作、目标租户、操作者、结果。SINGLE 模式拒绝创建非 default 租户
 * 也必须记审计（否则「越权尝试」无痕）。</p>
 */
public interface TenantLifecycleAudit {

    /**
     * @param action   动作（CREATE / SUSPEND / RESUME / DISABLE / DELETE / REJECT）
     * @param tenantId 目标租户
     * @param actorId  操作者
     * @param detail   结果说明（成功/拒绝原因）
     */
    void record(String action, String tenantId, String actorId, String detail);

    /** 审计端口的默认实现：只打日志（生产可替换为持久化审计表）。 */
    final class Logging implements TenantLifecycleAudit {
        private static final org.slf4j.Logger log =
                org.slf4j.LoggerFactory.getLogger(Logging.class);

        @Override
        public void record(String action, String tenantId, String actorId, String detail) {
            log.info("tenant lifecycle action={} tenant={} actor={} detail={}",
                    action, tenantId, actorId, detail);
        }
    }
}
