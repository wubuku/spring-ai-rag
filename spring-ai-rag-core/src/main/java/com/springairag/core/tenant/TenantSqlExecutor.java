package com.springairag.core.tenant;

/**
 * WP9：租户生命周期 SQL 执行端口。
 *
 * <p>抽成接口有两个目的：① 生命周期状态机与状态迁移规则可以脱离数据库单测；
 * ② 事务原子性由 {@link TenantLifecycleService} 统一包住「租户 INSERT + 初始 principal
 * INSERT」，数据库实现在真实 PostgreSQL 上验证回滚（见 TenantWp9PostgresIntegrationTest）。</p>
 */
public interface TenantSqlExecutor {

    /** 插入租户行；已存在时抛冲突。 */
    void insertTenant(String tenantId, String displayName);

    /** 插入该租户的初始 principal（凭据绑定 tenant_id）。 */
    void insertInitialPrincipal(String tenantId, String principalId);

    /** 租户是否存在（含软删行）。 */
    boolean tenantExists(String tenantId);

    /** 更新租户状态。 */
    void updateStatus(String tenantId, String status);

    /** 打软删标记。 */
    void markDeleted(String tenantId);
}
