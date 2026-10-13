-- V61: unified document processing run/stage ledger.

ALTER TABLE rag_embedding_jobs
    ADD COLUMN terminal_reason VARCHAR(32),
    ADD CONSTRAINT ck_rag_embedding_job_terminal_reason
        CHECK (
            terminal_reason IS NULL
            OR (terminal_reason IN ('NON_RETRYABLE', 'RETRY_EXHAUSTED')
                AND status = 'FAILED')
        );

CREATE TABLE rag_document_processing_runs (
    id UUID PRIMARY KEY,
    document_id BIGINT NOT NULL REFERENCES rag_documents(id) ON DELETE CASCADE,
    embedding_profile_id BIGINT NOT NULL REFERENCES rag_embedding_profiles(id),
    embedding_job_id UUID REFERENCES rag_embedding_jobs(id) ON DELETE SET NULL,
    request_generation BIGINT NOT NULL CHECK (request_generation > 0),
    document_version BIGINT NOT NULL,
    content_hash VARCHAR(64) NOT NULL
        CHECK (content_hash ~ '^[0-9a-fA-F]{64}$'),
    document_kind VARCHAR(32) NOT NULL
        CHECK (document_kind IN ('TEXT', 'JSON_RECORD')),
    chunker_version VARCHAR(128) NOT NULL CHECK (chunker_version <> ''),
    attempt_no INTEGER NOT NULL CHECK (attempt_no >= 1),
    execution_attempt INTEGER NOT NULL DEFAULT 0 CHECK (execution_attempt >= 0),
    max_attempts INTEGER NOT NULL CHECK (max_attempts BETWEEN 1 AND 10),
    status VARCHAR(20) NOT NULL DEFAULT 'QUEUED'
        CHECK (status IN ('QUEUED', 'RUNNING', 'COMPLETED', 'FAILED',
                          'CANCELLED', 'STALE', 'DEAD_LETTERED')),
    active_stage VARCHAR(32)
        CHECK (active_stage IS NULL OR active_stage IN
               ('PARSING', 'CHUNKING', 'EMBEDDING', 'INDEXING', 'FINALIZING')),
    next_retry_at TIMESTAMPTZ,
    last_heartbeat_at TIMESTAMPTZ,
    cancel_requested_at TIMESTAMPTZ,
    dead_lettered_at TIMESTAMPTZ,
    dead_letter_reason VARCHAR(500),
    last_error_code VARCHAR(64),
    last_error VARCHAR(500),
    origin VARCHAR(32) NOT NULL CHECK (origin IN (
        'API', 'LOCAL_CREATE', 'FILE_UPLOAD', 'DOCUMENT_EMBED',
        'REEMBED_MISSING', 'BATCH_EMBED', 'DERIVATION_REPAIR',
        'DOCUMENT_MUTATION', 'PDF_TO_RAG', 'EXTERNAL_SYNC', 'OTHER')),
    requested_by_principal_id VARCHAR(128),
    supersedes_run_id UUID REFERENCES rag_document_processing_runs(id)
        ON DELETE SET NULL,
    legacy_bridge BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_rag_processing_run_terminal_fields CHECK (
        (status = 'DEAD_LETTERED'
            AND dead_lettered_at IS NOT NULL
            AND dead_letter_reason IS NOT NULL)
        OR (status <> 'DEAD_LETTERED'
            AND dead_lettered_at IS NULL
            AND dead_letter_reason IS NULL)
    ),
    CONSTRAINT ck_rag_processing_run_retry_time CHECK (
        next_retry_at IS NULL OR status = 'QUEUED'
    )
);

CREATE UNIQUE INDEX uq_rag_processing_run_job
    ON rag_document_processing_runs (embedding_job_id)
    WHERE embedding_job_id IS NOT NULL;

CREATE UNIQUE INDEX uq_rag_processing_run_attempt
    ON rag_document_processing_runs (document_id, embedding_profile_id, attempt_no);

CREATE UNIQUE INDEX uq_rag_processing_run_active
    ON rag_document_processing_runs (document_id, embedding_profile_id)
    WHERE status IN ('QUEUED', 'RUNNING');

CREATE INDEX idx_rag_processing_run_document
    ON rag_document_processing_runs (document_id, created_at DESC, id DESC);
CREATE INDEX idx_rag_processing_run_status
    ON rag_document_processing_runs (status, updated_at, id);
CREATE INDEX idx_rag_processing_run_dead_letter
    ON rag_document_processing_runs (dead_lettered_at, updated_at, id);

CREATE TABLE rag_document_processing_stages (
    run_id UUID NOT NULL REFERENCES rag_document_processing_runs(id)
        ON DELETE CASCADE,
    stage_name VARCHAR(32) NOT NULL
        CHECK (stage_name IN ('PARSING', 'CHUNKING', 'EMBEDDING', 'INDEXING', 'FINALIZING')),
    stage_order SMALLINT NOT NULL CHECK (stage_order BETWEEN 1 AND 5),
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED',
                          'SKIPPED', 'CANCELLED', 'STALE')),
    execution_attempt INTEGER NOT NULL DEFAULT 0 CHECK (execution_attempt >= 0),
    progress_current INTEGER NOT NULL DEFAULT 0 CHECK (progress_current >= 0),
    progress_total INTEGER NOT NULL DEFAULT 0 CHECK (progress_total >= 0),
    progress_label VARCHAR(128),
    last_error_code VARCHAR(64),
    last_error VARCHAR(500),
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    last_heartbeat_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (run_id, stage_name),
    CONSTRAINT ck_rag_processing_stage_progress
        CHECK (progress_total = 0 OR progress_current <= progress_total),
    CONSTRAINT ck_rag_processing_stage_order CHECK (
        (stage_name = 'PARSING' AND stage_order = 1)
        OR (stage_name = 'CHUNKING' AND stage_order = 2)
        OR (stage_name = 'EMBEDDING' AND stage_order = 3)
        OR (stage_name = 'INDEXING' AND stage_order = 4)
        OR (stage_name = 'FINALIZING' AND stage_order = 5)
    )
);

CREATE INDEX idx_rag_processing_stage_status
    ON rag_document_processing_stages (status, updated_at);

COMMENT ON TABLE rag_document_processing_runs IS
    'Auditable document derivation run ledger; never stores document text, vectors, or provider payloads';
COMMENT ON TABLE rag_document_processing_stages IS
    'Fixed five-stage document derivation timeline';
COMMENT ON COLUMN rag_embedding_jobs.terminal_reason IS
    'Stable distinction between non-retryable failure and exhausted automatic retries';
