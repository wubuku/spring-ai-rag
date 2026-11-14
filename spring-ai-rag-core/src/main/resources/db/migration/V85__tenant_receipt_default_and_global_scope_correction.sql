-- 纠正迁移：main 上 V82/V84 已执行，禁止改写；本迁移只做前向纠正。
--
-- 缺陷 2（S1 安全）：V82 的 3 张 TENANT_PRIVATE 证据表 tenant_id 为 NOT NULL
--   但缺 DEFAULT 'default'，与 TenantTableCatalog 的统一租户私有表口径不一致。
--   单租户 SINGLE 模式下若写入方未显式带 tenant_id，STRICT 的 RLS WITH CHECK
--   会拒写；STRICT 下 GUC 绑定也必须由应用层显式给值，没有 DB 默认兜底。
--   本迁移补齐 DEFAULT，不改历史行（列已 NOT NULL，历史行均有值）。
--
-- 缺陷 1（S2 一致性）：WP10 的 3 张 PLATFORM_GLOBAL 配额表按 tenant_id 键控、
--   无 RLS，是 V84 与 TenantTableCatalog 的一致设计；「PLATFORM_GLOBAL 无
--   tenant_id 列」是测试断言口径错误，不是 schema 错。本迁移只加终态断言，
--   防止未来把这三张表误加进 V80 的 TENANT_PRIVATE RLS 清单。
--
-- 迁移纪律：纯事务 DDL，无 CONCURRENTLY；不改写 V1–V84 已执行迁移。

-- ---------- 1) 租户私有证据表：补齐 tenant_id DEFAULT ----------
ALTER TABLE rag_tenant_result_receipt
    ALTER COLUMN tenant_id SET DEFAULT 'default';
ALTER TABLE rag_tenant_callback_receipt
    ALTER COLUMN tenant_id SET DEFAULT 'default';
ALTER TABLE rag_tenant_callback_replay_nonce
    ALTER COLUMN tenant_id SET DEFAULT 'default';

-- 结果 owner 四元组查询面：tenant-leading 复合索引（与 V82 的 owner 索引互补，
-- 不与主键 / (tenant_id, receipt_id) 唯一约束重复）。
CREATE INDEX IF NOT EXISTS idx_rag_tenant_result_receipt_scope
    ON rag_tenant_result_receipt (tenant_id, collection_id, owner_principal_id);

-- ---------- 2) 终态断言 ----------
DO $$
DECLARE
    tbl TEXT;
    n INTEGER;
    evidence_tables TEXT[] := ARRAY[
        'rag_tenant_result_receipt',
        'rag_tenant_callback_receipt',
        'rag_tenant_callback_replay_nonce'
    ];
    quota_tables TEXT[] := ARRAY[
        'rag_tenant_quota_policy',
        'rag_tenant_quota_window',
        'rag_tenant_quota_reservation'
    ];
BEGIN
    -- 2a) 三张证据表：tenant_id NOT NULL + DEFAULT 'default' + RLS 仍开启。
    FOREACH tbl IN ARRAY evidence_tables
    LOOP
        SELECT count(*) INTO n FROM information_schema.columns
         WHERE table_schema = 'public' AND table_name = tbl
           AND column_name = 'tenant_id' AND is_nullable = 'NO'
           AND column_default LIKE '%default%';
        IF n <> 1 THEN
            RAISE EXCEPTION 'V85 终态断言失败：% 缺 tenant_id NOT NULL DEFAULT default', tbl;
        END IF;
        SELECT count(*) INTO n FROM pg_class c JOIN pg_namespace ns ON ns.oid = c.relnamespace
         WHERE ns.nspname = 'public' AND c.relname = tbl
           AND c.relrowsecurity AND c.relforcerowsecurity;
        IF n <> 1 THEN
            RAISE EXCEPTION 'V85 终态断言失败：% 应保留 ENABLE+FORCE RLS', tbl;
        END IF;
    END LOOP;

    -- 2b) 结果证据表的 tenant-leading 复合索引存在。
    SELECT count(*) INTO n FROM pg_indexes
     WHERE schemaname = 'public' AND tablename = 'rag_tenant_result_receipt'
       AND indexname = 'idx_rag_tenant_result_receipt_scope';
    IF n <> 1 THEN
        RAISE EXCEPTION 'V85 终态断言失败：rag_tenant_result_receipt 缺 tenant-leading 复合索引';
    END IF;

    -- 2c) PLATFORM_GLOBAL 配额表：按 tenant_id 键控但**不得启用 RLS**。
    --     这是「平台全局表无 RLS」的正确不变量；tenant_id 列本身允许存在。
    FOREACH tbl IN ARRAY quota_tables
    LOOP
        SELECT count(*) INTO n FROM pg_class c JOIN pg_namespace ns ON ns.oid = c.relnamespace
         WHERE ns.nspname = 'public' AND c.relname = tbl AND c.relrowsecurity;
        IF n <> 0 THEN
            RAISE EXCEPTION 'V85 终态断言失败：PLATFORM_GLOBAL 表 % 不得启用 RLS', tbl;
        END IF;
        SELECT count(*) INTO n FROM information_schema.columns
         WHERE table_schema = 'public' AND table_name = tbl
           AND column_name = 'tenant_id';
        IF n <> 1 THEN
            RAISE EXCEPTION 'V85 终态断言失败：PLATFORM_GLOBAL 表 % 应保持按 tenant_id 键控', tbl;
        END IF;
    END LOOP;
END
$$;
