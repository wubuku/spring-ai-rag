-- WP3：RLS 启用 + 凭据表租户化补齐（1/2）。
--
-- 权威清单：TenantTableCatalog（TENANT_PRIVATE → ENABLE + FORCE RLS + 规范 policy；
-- CREDENTIAL/PLATFORM_GLOBAL → 无 RLS）。
-- 规划 §0/§5.2 要求 rag_api_key / rag_api_principal 带 tenant_id（WP1 落地遗漏，
-- 本迁移补齐；rationale：认证发生在确定租户之前，tenant_id 仅作绑定与 SINGLE 守卫数据）。
-- policy 命名规范统一为 tenant_isolation（对齐参考项目 ../seedance-research 的 V175 惯例）。
-- GUC：app.current_tenant_id（session 级三件套由 WP5 datasource 层落地；迁移本身只依赖
-- current_setting(..., true)，无 GUC 时表达式为 NULL 恒假 → fail-closed 读 0 行）。

-- ---------- 1) 凭据表租户化（无 RLS，仅绑定列） ----------
ALTER TABLE rag_api_key
    ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE rag_api_principal
    ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE rag_api_key_rotation
    ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE rag_api_provisioning_operation
    ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';

CREATE INDEX IF NOT EXISTS idx_rag_api_key_tenant
    ON rag_api_key (tenant_id);
CREATE INDEX IF NOT EXISTS idx_rag_api_principal_tenant
    ON rag_api_principal (tenant_id);

-- ---------- 2) 租户私有表 RLS ----------
-- 45 张 TENANT_PRIVATE 表逐一 ENABLE + FORCE + tenant_isolation policy。
-- 用 DO 块动态生成，与 catalog 保持同一来源（见终态断言按 catalog 张数校验）。
DO $$
DECLARE
    tbl TEXT;
    tables TEXT[] := ARRAY[
        'rag_collection',
        'rag_documents',
        'rag_embeddings',
        'rag_chat_history',
        'spring_ai_chat_memory',
        'rag_retrieval_logs',
        'rag_retrieval_evaluations',
        'rag_user_feedback',
        'rag_ab_experiments',
        'rag_ab_results',
        'rag_document_versions',
        'fs_files',
        'rag_document_embedding_state',
        'rag_chat_session_lease',
        'rag_embedding_jobs',
        'rag_evaluation_suites',
        'rag_evaluation_suite_versions',
        'rag_evaluation_runs',
        'rag_evaluation_case_results',
        'rag_document_source_namespaces',
        'rag_document_idempotency_operations',
        'rag_document_sync_runs',
        'rag_document_sync_run_items',
        'rag_document_chunks',
        'rag_document_local_index_state',
        'rag_document_relocated_addresses',
        'rag_derivation_repair_previews',
        'rag_derivation_repair_items',
        'rag_chat_memory_summary',
        'rag_chat_turn_operations',
        'rag_collection_provisioning_operation',
        'rag_llm_usage_event',
        'rag_api_collection_operation_hourly',
        'rag_chat_history_source_document',
        'rag_user_feedback_document',
        'rag_collection_purge_preview',
        'fs_import_batches',
        'rag_document_processing_runs',
        'rag_document_processing_stages',
        'rag_chunking_upgrade_tasks',
        'rag_chunking_upgrade_retry_buckets',
        'rag_long_running_operations',
        'rag_collection_operation_slot',
        'rag_long_running_operation_run_refs',
        'rag_collection_purge_operation_targets'
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

-- ---------- 3) 终态断言 ----------
DO $$
DECLARE
    tbl TEXT;
    missing BOOLEAN;
    n INTEGER;
    tables TEXT[] := ARRAY[
        'rag_collection',
        'rag_documents',
        'rag_embeddings',
        'rag_chat_history',
        'spring_ai_chat_memory',
        'rag_retrieval_logs',
        'rag_retrieval_evaluations',
        'rag_user_feedback',
        'rag_ab_experiments',
        'rag_ab_results',
        'rag_document_versions',
        'fs_files',
        'rag_document_embedding_state',
        'rag_chat_session_lease',
        'rag_embedding_jobs',
        'rag_evaluation_suites',
        'rag_evaluation_suite_versions',
        'rag_evaluation_runs',
        'rag_evaluation_case_results',
        'rag_document_source_namespaces',
        'rag_document_idempotency_operations',
        'rag_document_sync_runs',
        'rag_document_sync_run_items',
        'rag_document_chunks',
        'rag_document_local_index_state',
        'rag_document_relocated_addresses',
        'rag_derivation_repair_previews',
        'rag_derivation_repair_items',
        'rag_chat_memory_summary',
        'rag_chat_turn_operations',
        'rag_collection_provisioning_operation',
        'rag_llm_usage_event',
        'rag_api_collection_operation_hourly',
        'rag_chat_history_source_document',
        'rag_user_feedback_document',
        'rag_collection_purge_preview',
        'fs_import_batches',
        'rag_document_processing_runs',
        'rag_document_processing_stages',
        'rag_chunking_upgrade_tasks',
        'rag_chunking_upgrade_retry_buckets',
        'rag_long_running_operations',
        'rag_collection_operation_slot',
        'rag_long_running_operation_run_refs',
        'rag_collection_purge_operation_targets'
    ];
BEGIN
    -- 3.1 凭据表：有 tenant_id 且无 RLS
    FOREACH tbl IN ARRAY ARRAY['rag_api_key','rag_api_principal','rag_api_key_rotation','rag_api_provisioning_operation']
    LOOP
        SELECT count(*) INTO n FROM information_schema.columns
         WHERE table_schema='public' AND lower(table_name)=tbl AND column_name='tenant_id';
        IF n <> 1 THEN
            RAISE EXCEPTION 'V80 终态断言失败：凭据表 % 缺 tenant_id 列', tbl;
        END IF;
        SELECT c.relrowsecurity INTO missing FROM pg_class c
         JOIN pg_namespace ns ON ns.oid=c.relnamespace
         WHERE ns.nspname='public' AND lower(c.relname)=tbl;
        IF missing THEN
            RAISE EXCEPTION 'V80 终态断言失败：凭据表 % 不得启用 RLS', tbl;
        END IF;
    END LOOP;

    -- 3.2 清单中每张表：ENABLE+FORCE RLS + tenant_isolation policy + 非恒真表达式
    FOREACH tbl IN ARRAY tables
    LOOP
        SELECT c.relrowsecurity AND c.relforcerowsecurity INTO missing
        FROM pg_class c JOIN pg_namespace ns ON ns.oid=c.relnamespace
        WHERE ns.nspname='public' AND lower(c.relname)=lower(tbl);
        IF missing IS DISTINCT FROM TRUE THEN
            RAISE EXCEPTION 'V80 终态断言失败：表 % 未 ENABLE+FORCE RLS', tbl;
        END IF;

        SELECT count(*) INTO n FROM pg_policies
         WHERE schemaname='public' AND tablename=lower(tbl) AND policyname='tenant_isolation';
        IF n <> 1 THEN
            RAISE EXCEPTION 'V80 终态断言失败：表 % 缺 tenant_isolation policy', tbl;
        END IF;

        IF EXISTS (SELECT 1 FROM pg_policies
                   WHERE schemaname='public' AND tablename=lower(tbl) AND policyname='tenant_isolation'
                     AND (qual::text = 'true' OR with_check::text = 'true')) THEN
            RAISE EXCEPTION 'V80 终态断言失败：表 % policy 表达式为恒真', tbl;
        END IF;
    END LOOP;

    -- 3.3 差集：任何其他带 tenant_id 的表不得启用 RLS（防平台全局/凭据表误开）
    FOR tbl IN
        SELECT DISTINCT c.relname FROM pg_attribute a
        JOIN pg_class c ON c.oid=a.attrelid
        JOIN pg_namespace ns ON ns.oid=c.relnamespace
        WHERE ns.nspname='public' AND a.attname='tenant_id' AND NOT a.attisdropped
          AND c.relkind='r'
    LOOP
        SELECT c.relrowsecurity INTO missing
        FROM pg_class c WHERE c.oid = to_regclass('public.' || tbl);
        IF missing AND NOT (tbl = ANY(tables)) THEN
            RAISE EXCEPTION 'V80 终态断言失败：非清单表 % 启用了 RLS', tbl;
        END IF;
    END LOOP;
END
$$;
