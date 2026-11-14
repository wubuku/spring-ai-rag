-- WP2：租户化底座（2/3）—— 租户私有表加 tenant_id 与 tenant-leading 索引。
--
-- 权威清单：spring-ai-rag-core/src/main/java/com/springairag/core/tenant/TenantTableCatalog.java
-- 与 docs/drafts/MULTI_TENANCY_TABLE_CATALOG.md（TENANT_PRIVATE 45 张）。
-- 本迁移由 WP1 catalog 生成；新增租户私有表必须先登记 catalog 再补迁移。
-- 框架表 SPRING_AI_CHAT_MEMORY 在 V2 未加引号创建，实际存储为小写 spring_ai_chat_memory；
-- 本迁移按小写名引用。
-- 加列用 ADD COLUMN IF NOT EXISTS + DEFAULT 'default' + NOT NULL，存量行全部回填 default；
-- 不引入第二隔离根列（BOS V126 承重列教训），不做双列共存。

ALTER TABLE rag_collection ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE rag_documents ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE rag_embeddings ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE rag_chat_history ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE spring_ai_chat_memory ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE rag_retrieval_logs ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE rag_retrieval_evaluations ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE rag_user_feedback ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE rag_ab_experiments ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE rag_ab_results ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE rag_document_versions ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE fs_files ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE rag_document_embedding_state ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE rag_chat_session_lease ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE rag_embedding_jobs ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE rag_evaluation_suites ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE rag_evaluation_suite_versions ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE rag_evaluation_runs ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE rag_evaluation_case_results ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE rag_document_source_namespaces ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE rag_document_idempotency_operations ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE rag_document_sync_runs ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE rag_document_sync_run_items ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE rag_document_chunks ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE rag_document_local_index_state ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE rag_document_relocated_addresses ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE rag_derivation_repair_previews ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE rag_derivation_repair_items ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE rag_chat_memory_summary ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE rag_chat_turn_operations ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE rag_collection_provisioning_operation ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE rag_llm_usage_event ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE rag_api_collection_operation_hourly ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE rag_chat_history_source_document ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE rag_user_feedback_document ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE rag_collection_purge_preview ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE fs_import_batches ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE rag_document_processing_runs ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE rag_document_processing_stages ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE rag_chunking_upgrade_tasks ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE rag_chunking_upgrade_retry_buckets ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE rag_long_running_operations ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE rag_collection_operation_slot ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE rag_long_running_operation_run_refs ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';
ALTER TABLE rag_collection_purge_operation_targets ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64) NOT NULL DEFAULT 'default';

-- tenant-leading 索引：让 RLS 过滤走 tenant 前缀；统一用单列 (tenant_id)，
-- 不假设各表主键列名（45 张表中存在复合主键），避免迁移依赖未知列名。
-- 租户内高频复合索引在 WP2 后续迁移按实际查询逐张补，不在此臆测。
-- 索引名统一 idx_<table>_tenant_leading，按表名去重；IF NOT EXISTS 保证重跑安全。
CREATE INDEX IF NOT EXISTS idx_rag_collection_tenant_leading ON rag_collection (tenant_id);
CREATE INDEX IF NOT EXISTS idx_rag_documents_tenant_leading ON rag_documents (tenant_id);
CREATE INDEX IF NOT EXISTS idx_rag_embeddings_tenant_leading ON rag_embeddings (tenant_id);
CREATE INDEX IF NOT EXISTS idx_rag_chat_history_tenant_leading ON rag_chat_history (tenant_id);
CREATE INDEX IF NOT EXISTS idx_spring_ai_chat_memory_tenant_leading ON spring_ai_chat_memory (tenant_id);
CREATE INDEX IF NOT EXISTS idx_rag_retrieval_logs_tenant_leading ON rag_retrieval_logs (tenant_id);
CREATE INDEX IF NOT EXISTS idx_rag_retrieval_evaluations_tenant_leading ON rag_retrieval_evaluations (tenant_id);
CREATE INDEX IF NOT EXISTS idx_rag_user_feedback_tenant_leading ON rag_user_feedback (tenant_id);
CREATE INDEX IF NOT EXISTS idx_rag_ab_experiments_tenant_leading ON rag_ab_experiments (tenant_id);
CREATE INDEX IF NOT EXISTS idx_rag_ab_results_tenant_leading ON rag_ab_results (tenant_id);
CREATE INDEX IF NOT EXISTS idx_rag_document_versions_tenant_leading ON rag_document_versions (tenant_id);
CREATE INDEX IF NOT EXISTS idx_fs_files_tenant_leading ON fs_files (tenant_id);
CREATE INDEX IF NOT EXISTS idx_rag_document_embedding_state_tenant_leading ON rag_document_embedding_state (tenant_id);
CREATE INDEX IF NOT EXISTS idx_rag_chat_session_lease_tenant_leading ON rag_chat_session_lease (tenant_id);
CREATE INDEX IF NOT EXISTS idx_rag_embedding_jobs_tenant_leading ON rag_embedding_jobs (tenant_id);
CREATE INDEX IF NOT EXISTS idx_rag_evaluation_suites_tenant_leading ON rag_evaluation_suites (tenant_id);
CREATE INDEX IF NOT EXISTS idx_rag_evaluation_suite_versions_tenant_leading ON rag_evaluation_suite_versions (tenant_id);
CREATE INDEX IF NOT EXISTS idx_rag_evaluation_runs_tenant_leading ON rag_evaluation_runs (tenant_id);
CREATE INDEX IF NOT EXISTS idx_rag_evaluation_case_results_tenant_leading ON rag_evaluation_case_results (tenant_id);
CREATE INDEX IF NOT EXISTS idx_rag_document_source_namespaces_tenant_leading ON rag_document_source_namespaces (tenant_id);
CREATE INDEX IF NOT EXISTS idx_rag_document_idempotency_operations_tenant_leading ON rag_document_idempotency_operations (tenant_id);
CREATE INDEX IF NOT EXISTS idx_rag_document_sync_runs_tenant_leading ON rag_document_sync_runs (tenant_id);
CREATE INDEX IF NOT EXISTS idx_rag_document_sync_run_items_tenant_leading ON rag_document_sync_run_items (tenant_id);
CREATE INDEX IF NOT EXISTS idx_rag_document_chunks_tenant_leading ON rag_document_chunks (tenant_id);
CREATE INDEX IF NOT EXISTS idx_rag_document_local_index_state_tenant_leading ON rag_document_local_index_state (tenant_id);
CREATE INDEX IF NOT EXISTS idx_rag_document_relocated_addresses_tenant_leading ON rag_document_relocated_addresses (tenant_id);
CREATE INDEX IF NOT EXISTS idx_rag_derivation_repair_previews_tenant_leading ON rag_derivation_repair_previews (tenant_id);
CREATE INDEX IF NOT EXISTS idx_rag_derivation_repair_items_tenant_leading ON rag_derivation_repair_items (tenant_id);
CREATE INDEX IF NOT EXISTS idx_rag_chat_memory_summary_tenant_leading ON rag_chat_memory_summary (tenant_id);
CREATE INDEX IF NOT EXISTS idx_rag_chat_turn_operations_tenant_leading ON rag_chat_turn_operations (tenant_id);
CREATE INDEX IF NOT EXISTS idx_rag_collection_provisioning_operation_tenant_leading ON rag_collection_provisioning_operation (tenant_id);
CREATE INDEX IF NOT EXISTS idx_rag_llm_usage_event_tenant_leading ON rag_llm_usage_event (tenant_id);
CREATE INDEX IF NOT EXISTS idx_rag_api_collection_operation_hourly_tenant_leading ON rag_api_collection_operation_hourly (tenant_id);
CREATE INDEX IF NOT EXISTS idx_rag_chat_history_source_document_tenant_leading ON rag_chat_history_source_document (tenant_id);
CREATE INDEX IF NOT EXISTS idx_rag_user_feedback_document_tenant_leading ON rag_user_feedback_document (tenant_id);
CREATE INDEX IF NOT EXISTS idx_rag_collection_purge_preview_tenant_leading ON rag_collection_purge_preview (tenant_id);
CREATE INDEX IF NOT EXISTS idx_fs_import_batches_tenant_leading ON fs_import_batches (tenant_id);
CREATE INDEX IF NOT EXISTS idx_rag_document_processing_runs_tenant_leading ON rag_document_processing_runs (tenant_id);
CREATE INDEX IF NOT EXISTS idx_rag_document_processing_stages_tenant_leading ON rag_document_processing_stages (tenant_id);
CREATE INDEX IF NOT EXISTS idx_rag_chunking_upgrade_tasks_tenant_leading ON rag_chunking_upgrade_tasks (tenant_id);
CREATE INDEX IF NOT EXISTS idx_rag_chunking_upgrade_retry_buckets_tenant_leading ON rag_chunking_upgrade_retry_buckets (tenant_id);
CREATE INDEX IF NOT EXISTS idx_rag_long_running_operations_tenant_leading ON rag_long_running_operations (tenant_id);
CREATE INDEX IF NOT EXISTS idx_rag_collection_operation_slot_tenant_leading ON rag_collection_operation_slot (tenant_id);
CREATE INDEX IF NOT EXISTS idx_rag_long_running_operation_run_refs_tenant_leading ON rag_long_running_operation_run_refs (tenant_id);
CREATE INDEX IF NOT EXISTS idx_rag_collection_purge_operation_targets_tenant_leading ON rag_collection_purge_operation_targets (tenant_id);

-- 迁移内建终态断言：45 张租户私有表都必须有且仅有一列 tenant_id，且全为 NOT NULL。
DO $$
DECLARE
    expected TEXT[] := ARRAY[
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
    t TEXT;
    n INTEGER;
BEGIN
    FOREACH t IN ARRAY expected LOOP
        SELECT count(*) INTO n FROM information_schema.columns
         WHERE table_schema = 'public' AND lower(table_name) = t
           AND column_name = 'tenant_id' AND is_nullable = 'NO'
           AND column_default LIKE '%default%';
        IF n <> 1 THEN
            RAISE EXCEPTION 'V76 终态断言失败：表 % 的 tenant_id 列数量/约束不符（n=%）', t, n;
        END IF;
    END LOOP;
    -- 拒绝双 tenant_id 列（BOS V126 断裂项防回归）
    IF EXISTS (SELECT 1 FROM (SELECT lower(table_name) tn, count(*) c
                 FROM information_schema.columns WHERE table_schema='public'
                  AND column_name='tenant_id' GROUP BY 1 HAVING count(*) > 1) x) THEN
        RAISE EXCEPTION 'V76 终态断言失败：存在重复的 tenant_id 列';
    END IF;
END
$$;
