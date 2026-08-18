-- ============================================================
-- 检索诊断：扩展 rag_retrieval_logs
-- 版本: V35
-- 说明: 为生产 Search/Chat 增加 trace、owner、outcome；旧 V3 行保持 NULL
-- ============================================================

ALTER TABLE rag_retrieval_logs
    ADD COLUMN IF NOT EXISTS trace_id UUID,
    ADD COLUMN IF NOT EXISTS owner_principal_id VARCHAR(128),
    ADD COLUMN IF NOT EXISTS operation VARCHAR(32),
    ADD COLUMN IF NOT EXISTS outcome_code VARCHAR(64),
    ADD COLUMN IF NOT EXISTS empty_reason_code VARCHAR(64);

CREATE UNIQUE INDEX IF NOT EXISTS idx_rag_retrieval_logs_trace_id
    ON rag_retrieval_logs (trace_id)
    WHERE trace_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_rag_retrieval_logs_owner_created
    ON rag_retrieval_logs (owner_principal_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_rag_retrieval_logs_operation_created
    ON rag_retrieval_logs (operation, created_at DESC);
