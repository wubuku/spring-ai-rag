-- WP10：租户级配额与用量（硬阈值聚合，非 billing / hard-limit 结算）。
--
-- 参考 ../seedance-research/docs/multitenancy/drafts/租户级Credits-分配与回收-实施规划.md
-- 的分层语义（租户池 / 成员配额 / 分配审计），改写为本仓自包含的通用能力：
--   * 不做充值、邀请码、成员间分配/收回（规划 §6 WP10 明确排除）；
--   * 平台管理员可用平台池调整租户阈值（rag_tenant_quota_policy），
--     app 请求路径按租户维度做 DAY/MONTH 硬阈值 CAS（rag_tenant_quota_window）；
--   * 不引入 billing 表、外部账单或 hard-limit 结算语义。
--
-- 两张表均为 PLATFORM_GLOBAL（无 RLS、按 tenant_id 键控）：
--   * 平台容量治理在「确定租户之后」执行，app 角色读写窗口表是登记的访问点；
--   * 用量明细（rag_llm_usage_event）保持 TENANT_PRIVATE + RLS（V76/V80 已有），
--     WP10 只在聚合输出层增加 tenant 维度，不降低明细隔离。
--
-- 迁移纪律：纯事务内 DDL，无 CONCURRENTLY；不改写 V1–V83 已执行迁移。

CREATE TABLE rag_tenant_quota_policy (
    tenant_id VARCHAR(64) PRIMARY KEY,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    daily_token_limit BIGINT NOT NULL,
    monthly_token_limit BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT ck_rag_tenant_quota_policy_tenant
        CHECK (tenant_id ~ '^[a-z0-9][a-z0-9_-]{0,63}$'),
    CONSTRAINT ck_rag_tenant_quota_policy_limits
        CHECK (daily_token_limit > 0
            AND monthly_token_limit >= daily_token_limit)
);

COMMENT ON TABLE rag_tenant_quota_policy IS
    'WP10 租户级硬阈值策略；PLATFORM_GLOBAL，无 RLS，由平台管理面配置，app 只读';

CREATE TABLE rag_tenant_quota_window (
    tenant_id VARCHAR(64) NOT NULL,
    window_kind VARCHAR(8) NOT NULL,
    window_start TIMESTAMPTZ NOT NULL,
    reserved_tokens BIGINT NOT NULL DEFAULT 0,
    settled_tokens BIGINT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (tenant_id, window_kind, window_start),
    CONSTRAINT ck_rag_tenant_quota_window_tenant
        CHECK (tenant_id ~ '^[a-z0-9][a-z0-9_-]{0,63}$'),
    CONSTRAINT ck_rag_tenant_quota_window_kind
        CHECK (window_kind IN ('DAY', 'MONTH')),
    CONSTRAINT ck_rag_tenant_quota_window_counts
        CHECK (reserved_tokens >= 0 AND settled_tokens >= 0 AND version >= 0)
);

COMMENT ON TABLE rag_tenant_quota_window IS
    'WP10 租户级 DAY/MONTH 硬阈值聚合账本；CAS（version 列）并发预留/结算；PLATFORM_GLOBAL 无 RLS';

-- 终态断言：两张表存在，default 租户种子仍完好。
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.tables
                   WHERE table_schema='public' AND table_name='rag_tenant_quota_policy') THEN
        RAISE EXCEPTION 'V84 终态断言失败：rag_tenant_quota_policy 缺失';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM information_schema.tables
                   WHERE table_schema='public' AND table_name='rag_tenant_quota_window') THEN
        RAISE EXCEPTION 'V84 终态断言失败：rag_tenant_quota_window 缺失';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM rag_tenant WHERE tenant_id='default') THEN
        RAISE EXCEPTION 'V84 终态断言失败：default 租户缺失';
    END IF;
END
$$;

-- 租户级预留与 principal 预算预留的关联映射：异步结算/TTL 释放时从 DB 恢复
-- tenant / window / estimate，不依赖调用线程仍持有 TenantContext。
CREATE TABLE rag_tenant_quota_reservation (
    reservation_id UUID PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    day_window_start TIMESTAMPTZ NOT NULL,
    month_window_start TIMESTAMPTZ NOT NULL,
    estimated_tokens BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT ck_rag_tenant_quota_reservation_tenant
        CHECK (tenant_id ~ '^[a-z0-9][a-z0-9_-]{0,63}$'),
    CONSTRAINT ck_rag_tenant_quota_reservation_estimate
        CHECK (estimated_tokens > 0)
);

COMMENT ON TABLE rag_tenant_quota_reservation IS
    'WP10 principal 预算预留 → 租户级预留映射；异步 settle/release/TTL 从 DB 恢复租户窗口';

-- 终态断言补齐第三张表。
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.tables
                   WHERE table_schema='public' AND table_name='rag_tenant_quota_reservation') THEN
        RAISE EXCEPTION 'V84 终态断言失败：rag_tenant_quota_reservation 缺失';
    END IF;
END
$$;
