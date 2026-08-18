-- V37: embedding job 运营审计字段与列表索引
ALTER TABLE rag_embedding_jobs
    ADD COLUMN IF NOT EXISTS origin VARCHAR(32),
    ADD COLUMN IF NOT EXISTS requested_by_principal_id VARCHAR(128);

CREATE INDEX IF NOT EXISTS idx_rag_embedding_job_status_created
    ON rag_embedding_jobs (status, created_at DESC);

COMMENT ON COLUMN rag_embedding_jobs.origin IS
    '创建该 job 的入口，例如 EXTERNAL_UPSERT / JSON_UPSERT / API';
COMMENT ON COLUMN rag_embedding_jobs.requested_by_principal_id IS
    '规范化 principal ID，不保存 API secret';
