-- WP8：结果 owner / 回调权威 / SSE 逐次授权的持久化证据层。
--
-- 参考 ../seedance-research/multitenancy-verification/src/main/resources/db/init/001_schema.sql
-- 的 callback_receipt / callback_replay_nonce / job_result 三表模板；本仓按自包含对象模型
-- （tenant / collection / owner / receipt / operation）重写，不依赖外部项目私有协议。
--
-- 设计取舍：
--   * 不改写 V1–V81 已执行迁移；V80 已给 45 张 TENANT_PRIVATE 表 ENABLE+FORCE RLS，
--     本迁移新增的 3 张证据表必须自带同样的 RLS policy 与终态断言（不能塞回 V80）。
--   * 现有业务表（rag_long_running_operations 的 owner_principal_id +
--     idempotency_key_sha256 + result_payload、fs_import_batches / rag_evaluation_runs 的
--     owner_principal_id）已充当各自结果的 durable receipt；本迁移提供「跨结果面统一、
--     带完整 owner 四元组」的证据表与回调 receipt / replay / response-lost intent，
--     避免给每张业务表重复加列（V71 明确禁止猜测回填历史 owner）。
--   * 纯事务内 DDL：WP2 的 CREATE INDEX CONCURRENTLY 挂死事故后，迁移链一律不使用
--     非事务迁移；锁窗口与在线演练只走 MULTI_TENANCY_UPGRADE_REHEARSAL.md。
--   * 凭据表无 RLS（认证先于租户解析）；本 3 张表属租户私有，STRICT 下由 GUC 隔离。

-- ---------- 1) 结果 owner receipt ----------
-- task/operation ID 不是授权证明：必须同源持久化 tenant、collection 资源作用域、
-- owner principal 与 receipt。缺任一列在 DB 层即拒绝（对齐 TenantResultReceipt 合同）。
CREATE TABLE IF NOT EXISTS rag_tenant_result_receipt (
    tenant_id           VARCHAR(64)  NOT NULL,
    operation_id        VARCHAR(128) NOT NULL,
    collection_id       VARCHAR(128) NOT NULL,
    owner_principal_id  VARCHAR(128) NOT NULL,
    receipt_id          VARCHAR(128) NOT NULL,
    result_url          TEXT,
    result_payload      JSONB,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_rag_tenant_result_receipt
        PRIMARY KEY (tenant_id, operation_id),
    CONSTRAINT uq_rag_tenant_result_receipt_receipt
        UNIQUE (tenant_id, receipt_id),
    CONSTRAINT ck_rag_tenant_result_receipt_tenant
        CHECK (tenant_id ~ '^[a-z0-9][a-z0-9_-]{0,63}$')
);

COMMENT ON TABLE rag_tenant_result_receipt IS
    'WP8 结果 owner 四元组：tenant/collection/owner/receipt；任意 task ID 不得触发外部 Provider 查询';
COMMENT ON COLUMN rag_tenant_result_receipt.operation_id IS
    '本仓长任务/导入批次/评估 run 的文本化稳定 ID（不是授权凭证）';

CREATE INDEX IF NOT EXISTS idx_rag_tenant_result_receipt_owner
    ON rag_tenant_result_receipt (tenant_id, owner_principal_id, created_at DESC);

-- ---------- 2) 回调 receipt + response-lost durable intent ----------
-- 同一行同时承担「按 operation 的权威 receipt」与「response-lost 幂等 intent」：
-- PENDING（已登记、Provider task 未确认）→ ACCEPTED（provider_task_id 绑定）→ APPLIED。
-- client_token 复合主键保证同一客户端重试只找回原 task，不二次 create。
CREATE TABLE IF NOT EXISTS rag_tenant_callback_receipt (
    tenant_id           VARCHAR(64)  NOT NULL,
    operation           VARCHAR(128) NOT NULL,
    provider_name       VARCHAR(128) NOT NULL,
    client_token        VARCHAR(128) NOT NULL,
    provider_task_id    VARCHAR(128),
    owner_reference     VARCHAR(128) NOT NULL,
    actor_id            VARCHAR(128),
    status              VARCHAR(24)  NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'ACCEPTED', 'APPLIED', 'REJECTED')),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_rag_tenant_callback_receipt
        PRIMARY KEY (tenant_id, operation, provider_name, client_token),
    CONSTRAINT uq_rag_tenant_callback_receipt_provider_task
        UNIQUE (operation, provider_name, provider_task_id),
    CONSTRAINT ck_rag_tenant_callback_receipt_tenant
        CHECK (tenant_id ~ '^[a-z0-9][a-z0-9_-]{0,63}$'),
    CONSTRAINT ck_rag_tenant_callback_receipt_status_fields
        CHECK (status <> 'ACCEPTED' OR provider_task_id IS NOT NULL)
);

COMMENT ON TABLE rag_tenant_callback_receipt IS
    'WP8 回调权威 receipt：body 不具权威；按 operation 验签→receipt→replay→权威 Provider 结果';

CREATE INDEX IF NOT EXISTS idx_rag_tenant_callback_receipt_owner
    ON rag_tenant_callback_receipt (tenant_id, owner_reference, created_at DESC);

-- ---------- 3) replay nonce（并发/重启下仍幂等） ----------
-- callback actor 与 manual resolution 的 audit principal 分列，避免审计上不可区分。
CREATE TABLE IF NOT EXISTS rag_tenant_callback_replay_nonce (
    tenant_id           VARCHAR(64)  NOT NULL,
    operation           VARCHAR(128) NOT NULL,
    provider_name       VARCHAR(128) NOT NULL,
    nonce               VARCHAR(256) NOT NULL,
    actor_id            VARCHAR(128),
    audit_principal     VARCHAR(256) NOT NULL,
    subject_id          VARCHAR(128),
    action              VARCHAR(64)  NOT NULL,
    consumed_at         TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_rag_tenant_callback_replay_nonce
        PRIMARY KEY (tenant_id, operation, provider_name, nonce),
    CONSTRAINT ck_rag_tenant_callback_replay_nonce_tenant
        CHECK (tenant_id ~ '^[a-z0-9][a-z0-9_-]{0,63}$')
);

COMMENT ON TABLE rag_tenant_callback_replay_nonce IS
    'WP8 replay nonce：首次 claim 成功，重复即 REJECTED；并发/进程重启不失效';

-- ---------- 4) 新表自带 ENABLE + FORCE RLS + 规范 policy ----------
-- V80 已执行，不能改写；新表必须在本迁移内补齐同等隔离，否则 STRICT verifier 会拒绝启动。
DO $$
DECLARE
    tbl TEXT;
    tables TEXT[] := ARRAY[
        'rag_tenant_result_receipt',
        'rag_tenant_callback_receipt',
        'rag_tenant_callback_replay_nonce'
    ];
BEGIN
    FOREACH tbl IN ARRAY tables
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', tbl);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', tbl);
        EXECUTE format('DROP POLICY IF EXISTS tenant_isolation ON %I', tbl);
        EXECUTE format($f$
            CREATE POLICY tenant_isolation ON %I
                USING      (tenant_id = current_setting('app.current_tenant_id', true))
                WITH CHECK (tenant_id = current_setting('app.current_tenant_id', true))
        $f$, tbl);
    END LOOP;
END
$$;

-- ---------- 5) 终态断言（三表齐全 + RLS + policy 非恒真 + owner 四元组列齐全） ----------
DO $$
DECLARE
    tbl TEXT;
    ok BOOLEAN;
    n INTEGER;
    tables TEXT[] := ARRAY[
        'rag_tenant_result_receipt',
        'rag_tenant_callback_receipt',
        'rag_tenant_callback_replay_nonce'
    ];
BEGIN
    FOREACH tbl IN ARRAY tables
    LOOP
        SELECT c.relrowsecurity AND c.relforcerowsecurity INTO ok
        FROM pg_class c JOIN pg_namespace ns ON ns.oid=c.relnamespace
        WHERE ns.nspname='public' AND c.relname=tbl;
        IF ok IS DISTINCT FROM TRUE THEN
            RAISE EXCEPTION 'V82 终态断言失败：% 未 ENABLE+FORCE RLS', tbl;
        END IF;
        SELECT count(*) INTO n FROM pg_policies
         WHERE schemaname='public' AND tablename=tbl AND policyname='tenant_isolation'
           AND qual IS DISTINCT FROM 'true';
        IF n <> 1 THEN
            RAISE EXCEPTION 'V82 终态断言失败：% 缺规范 tenant_isolation policy 或 policy 恒真', tbl;
        END IF;
    END LOOP;

    -- 结果 owner 四元组：缺任一列即拒绝。
    SELECT count(*) INTO n FROM information_schema.columns
     WHERE table_schema='public' AND table_name='rag_tenant_result_receipt'
       AND column_name IN ('tenant_id','collection_id','owner_principal_id','receipt_id');
    IF n <> 4 THEN
        RAISE EXCEPTION 'V82 终态断言失败：rag_tenant_result_receipt 缺 owner 四元组列（n=%）', n;
    END IF;

    -- 回调 receipt 的 client_token 幂等键与 provider_task 全局唯一都在。
    SELECT count(*) INTO n FROM pg_constraint
     WHERE conrelid='rag_tenant_callback_receipt'::regclass AND contype='p'
       AND pg_get_constraintdef(oid) LIKE '%client_token%';
    IF n <> 1 THEN
        RAISE EXCEPTION 'V82 终态断言失败：callback receipt 缺 client_token 复合主键';
    END IF;
    SELECT count(*) INTO n FROM pg_constraint
     WHERE conrelid='rag_tenant_callback_replay_nonce'::regclass AND contype='p'
       AND pg_get_constraintdef(oid) LIKE '%nonce%';
    IF n <> 1 THEN
        RAISE EXCEPTION 'V82 终态断言失败：replay nonce 缺主键唯一约束';
    END IF;
END
$$;
