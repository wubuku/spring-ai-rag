-- WP2：租户化底座（1/3）—— 租户主表。
--
-- 决策（实施规划 §0 / §5.2，终审核 APPROVE 基线）：
--   * tenant_id VARCHAR(64)，slug 正则 ^[a-z0-9][a-z0-9_-]{0,63}$；不用随机 UUID 作 GUC 值。
--   * 单租户模式仍写 tenant_id、仍走同一 SQL/任务路径；首条迁移插入 default。
--   * rag_tenant 是 PLATFORM_GLOBAL 平台表，不启用 RLS；由平台池/生命周期端点管理。
--   * 本迁移不改写 V1–V74；全新库与 V74 存量库执行同一条路径。

CREATE TABLE IF NOT EXISTS rag_tenant (
    tenant_id      VARCHAR(64) PRIMARY KEY,
    display_name   VARCHAR(255),
    status         VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
                   CHECK (status IN ('ACTIVE', 'SUSPENDED', 'DISABLED')),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMPTZ,
    CONSTRAINT ck_rag_tenant_id_slug
        CHECK (tenant_id ~ '^[a-z0-9][a-z0-9_-]{0,63}$')
);

COMMENT ON TABLE rag_tenant IS 'WP2 租户主表；平台全局表，跨租户生命周期由平台池管理';
COMMENT ON COLUMN rag_tenant.tenant_id IS '租户隔离根，与 app.current_tenant_id GUC 同值';

INSERT INTO rag_tenant (tenant_id, display_name, status)
VALUES ('default', 'Default tenant (single-tenant mode)', 'ACTIVE')
ON CONFLICT (tenant_id) DO NOTHING;

-- 迁移内建终态断言：default 必须存在，且租户 id 格式被 DB 约束兜住。
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM rag_tenant WHERE tenant_id = 'default') THEN
        RAISE EXCEPTION 'V75 终态断言失败：default 租户缺失';
    END IF;
    IF EXISTS (SELECT 1 FROM rag_tenant WHERE tenant_id !~ '^[a-z0-9][a-z0-9_-]{0,63}$') THEN
        RAISE EXCEPTION 'V75 终态断言失败：存在非法 tenant_id';
    END IF;
END
$$;
