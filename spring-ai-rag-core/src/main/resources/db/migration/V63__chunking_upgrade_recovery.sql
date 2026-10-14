ALTER TABLE rag_chunking_upgrade_tasks
    DROP CONSTRAINT rag_chunking_upgrade_tasks_status_check;

ALTER TABLE rag_chunking_upgrade_tasks
    ADD CONSTRAINT rag_chunking_upgrade_tasks_status_check
        CHECK (status IN ('RUNNING', 'RETRY', 'COOLDOWN', 'COMPLETED', 'FAILED')),
    ADD COLUMN failure_kind VARCHAR(16) NOT NULL DEFAULT 'NONE'
        CHECK (failure_kind IN ('NONE', 'TRANSIENT', 'REJECTED', 'UNKNOWN', 'CONFLICT', 'PERMANENT')),
    ADD COLUMN recovery_cycles INTEGER NOT NULL DEFAULT 0 CHECK (recovery_cycles >= 0);

UPDATE rag_chunking_upgrade_tasks
SET status = 'COOLDOWN', failure_kind = 'UNKNOWN',
    available_at = CURRENT_TIMESTAMP + INTERVAL '24 hours',
    lease_owner = NULL, lease_expires_at = NULL, updated_at = CURRENT_TIMESTAMP
WHERE status IN ('FAILED', 'RETRY') AND last_error_code = 'CHUNK_UPGRADE_FAILED';

UPDATE rag_chunking_upgrade_tasks
SET status = 'COOLDOWN', failure_kind = 'TRANSIENT',
    available_at = CURRENT_TIMESTAMP + INTERVAL '1 hour',
    lease_owner = NULL, lease_expires_at = NULL, updated_at = CURRENT_TIMESTAMP
WHERE status = 'FAILED' AND last_error_code = 'CHUNK_UPGRADE_LEASE_EXHAUSTED';

UPDATE rag_chunking_upgrade_tasks
SET failure_kind = 'PERMANENT'
WHERE status = 'FAILED';

CREATE INDEX idx_rag_chunking_upgrade_pending
    ON rag_chunking_upgrade_tasks (status, available_at) WHERE status <> 'COMPLETED';

CREATE TABLE rag_chunking_upgrade_retry_buckets (
    embedding_profile_id BIGINT NOT NULL REFERENCES rag_embedding_profiles(id) ON DELETE CASCADE,
    bucket_start TIMESTAMPTZ NOT NULL,
    reserved_count INTEGER NOT NULL CHECK (reserved_count BETWEEN 1 AND 1000),
    PRIMARY KEY (embedding_profile_id, bucket_start)
);

COMMENT ON TABLE rag_chunking_upgrade_retry_buckets IS
    'Cross-instance UTC-hour quota for retry document embedding batches, not token or currency accounting';
