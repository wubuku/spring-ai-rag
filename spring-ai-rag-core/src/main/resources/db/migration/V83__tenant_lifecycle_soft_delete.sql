-- WP9：租户生命周期软删标记（平台管理面）。
--
-- 参考 ../seedance-research/docs/multitenancy/drafts/用户自助注册B4三段式单事务化-实施方案.md
-- 的「幽灵租户」教训：租户行 + 初始 principal 必须单事务原子；软删不得物理删除，
-- 否则审计链路与历史数据出现断口。
--
-- 设计取舍：
--   * 不改写 V1–V82 已执行迁移；V75 已有 status CHECK（ACTIVE/SUSPENDED/DISABLED），
--     本迁移只补 deleted_at 软删列与生命周期终态断言。
--   * rag_tenant 是 PLATFORM_GLOBAL 平台表，无 RLS、无 tenant_id 列；
--     由平台 capability 端点经 TenantLifecycleService 读写（认证先于租户解析）。
--   * 纯事务内 DDL：WP2 的 CONCURRENTLY 挂死事故后迁移链一律不使用非事务迁移。
--   * 凭据表 rag_api_key / rag_api_principal 的 tenant_id 绑定列已在 V80 补齐；
--     WP9 的初始 principal INSERT 显式写入 tenant_id，STRICT 解析不再走 default 兜底。

ALTER TABLE rag_tenant
    ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ;

COMMENT ON COLUMN rag_tenant.deleted_at IS
    'WP9 软删标记（幂等）；不物理删除，保留审计与历史数据；ACTIVE 租户枚举排除已软删行';

CREATE INDEX IF NOT EXISTS idx_rag_tenant_active
    ON rag_tenant (status, tenant_id)
    WHERE deleted_at IS NULL;

-- 终态断言：default 租户必须存在且未被软删；status 取值仍受 V75 CHECK 约束兜住。
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM rag_tenant
                   WHERE tenant_id = 'default' AND deleted_at IS NULL) THEN
        RAISE EXCEPTION 'V83 终态断言失败：default 租户缺失或已软删';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'rag_tenant'
          AND column_name = 'deleted_at') THEN
        RAISE EXCEPTION 'V83 终态断言失败：rag_tenant.deleted_at 缺失';
    END IF;
    IF EXISTS (SELECT 1 FROM rag_tenant
               WHERE status NOT IN ('ACTIVE','SUSPENDED','DISABLED')) THEN
        RAISE EXCEPTION 'V83 终态断言失败：存在非法租户状态';
    END IF;
END
$$;
