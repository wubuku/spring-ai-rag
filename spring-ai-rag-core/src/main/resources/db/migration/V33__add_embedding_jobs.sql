-- V33: durable embedding/reindex jobs with bounded retries and worker leases.

CREATE TABLE rag_embedding_jobs (
    id UUID PRIMARY KEY,
    batch_id UUID NOT NULL,
    job_type VARCHAR(32) NOT NULL DEFAULT 'EMBED_DOCUMENT',
    document_id BIGINT NOT NULL REFERENCES rag_documents(id) ON DELETE CASCADE,
    embedding_profile_id BIGINT NOT NULL REFERENCES rag_embedding_profiles(id),
    force BOOLEAN NOT NULL DEFAULT false,
    content_hash VARCHAR(64) NOT NULL,
    document_version BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'QUEUED',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    max_attempts INTEGER NOT NULL DEFAULT 3,
    available_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lease_owner VARCHAR(128),
    lease_expires_at TIMESTAMPTZ,
    cancel_requested_at TIMESTAMPTZ,
    progress JSONB NOT NULL DEFAULT '{}'::jsonb,
    last_error VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_rag_embedding_job_type
        CHECK (job_type = 'EMBED_DOCUMENT'),
    CONSTRAINT ck_rag_embedding_job_status
        CHECK (status IN (
            'QUEUED', 'RUNNING', 'SUCCEEDED',
            'FAILED', 'CANCELLED', 'STALE'
        )),
    CONSTRAINT ck_rag_embedding_job_attempts
        CHECK (
            attempt_count >= 0
            AND max_attempts BETWEEN 1 AND 10
            AND attempt_count <= max_attempts
        ),
    CONSTRAINT ck_rag_embedding_job_hash
        CHECK (content_hash ~ '^[0-9a-fA-F]{64}$'),
    CONSTRAINT ck_rag_embedding_job_lease
        CHECK (
            (lease_owner IS NULL AND lease_expires_at IS NULL)
            OR (lease_owner IS NOT NULL AND lease_expires_at IS NOT NULL)
        )
);

CREATE UNIQUE INDEX uq_rag_embedding_job_active
    ON rag_embedding_jobs (
        document_id,
        embedding_profile_id,
        content_hash
    )
    WHERE status IN ('QUEUED', 'RUNNING');

CREATE INDEX idx_rag_embedding_job_claim
    ON rag_embedding_jobs (available_at, created_at)
    WHERE status IN ('QUEUED', 'RUNNING');

CREATE INDEX idx_rag_embedding_job_batch
    ON rag_embedding_jobs (batch_id, created_at, id);

CREATE INDEX idx_rag_embedding_job_document
    ON rag_embedding_jobs (document_id, created_at DESC);

COMMENT ON TABLE rag_embedding_jobs IS
    'Durable one-document embedding jobs; content is never copied into this table';
