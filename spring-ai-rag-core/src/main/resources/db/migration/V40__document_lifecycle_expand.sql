-- V40: expand the document lifecycle model without dropping V39-compatible columns.

ALTER TABLE rag_documents
    ADD COLUMN source_namespace VARCHAR(128) NOT NULL DEFAULT 'default',
    ADD COLUMN document_revision BIGINT NOT NULL DEFAULT 1,
    ADD COLUMN next_history_version INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN source_mutation_sequence BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN disabled_at TIMESTAMP(6);

UPDATE rag_documents document
SET next_history_version = COALESCE((
    SELECT MAX(version_number) + 1
    FROM rag_document_versions version
    WHERE version.document_id = document.id
), 1);

ALTER TABLE rag_documents
    ADD CONSTRAINT ck_rag_document_revision_positive
        CHECK (document_revision > 0),
    ADD CONSTRAINT ck_rag_document_next_history_version_positive
        CHECK (next_history_version > 0),
    ADD CONSTRAINT ck_rag_document_source_namespace
        CHECK (
            source_namespace <> ''
            AND LENGTH(source_namespace) <= 128
            AND source_namespace !~ '[^ -~]'
        );

CREATE TABLE rag_document_source_namespaces (
    collection_id BIGINT NOT NULL
        REFERENCES rag_collection(id) ON DELETE CASCADE,
    source_namespace VARCHAR(128) NOT NULL,
    mutation_sequence BIGINT NOT NULL DEFAULT 0,
    sync_generation BIGINT NOT NULL DEFAULT 0,
    active_run_id UUID,
    row_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (collection_id, source_namespace),
    CONSTRAINT ck_rag_source_namespace_name
        CHECK (
            source_namespace <> ''
            AND LENGTH(source_namespace) <= 128
            AND source_namespace !~ '[^ -~]'
        )
);

CREATE UNIQUE INDEX uk_rag_doc_external_identity_v2
    ON rag_documents (collection_id, source_namespace, external_id)
    WHERE collection_id IS NOT NULL
      AND external_id IS NOT NULL
      AND external_id <> '';

CREATE INDEX idx_rag_doc_source_namespace_visibility
    ON rag_documents (
        collection_id,
        source_namespace,
        enabled,
        source_deleted_at,
        source_mutation_sequence
    )
    WHERE external_id IS NOT NULL;

ALTER TABLE rag_document_embedding_state
    DROP CONSTRAINT IF EXISTS ck_rag_document_embedding_state_status;

ALTER TABLE rag_document_embedding_state
    ADD COLUMN request_generation BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN active_job_id UUID,
    ADD COLUMN last_reindex_error VARCHAR(500),
    ADD CONSTRAINT ck_rag_document_embedding_state_status
        CHECK (status IN (
            'QUEUED', 'PROCESSING', 'COMPLETED', 'FAILED',
            'NOT_REQUESTED', 'CANCELLED'
        )),
    ADD CONSTRAINT ck_rag_document_embedding_state_generation
        CHECK (request_generation >= 0);

ALTER TABLE rag_embedding_jobs
    ADD COLUMN request_generation BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN document_kind VARCHAR(32),
    ADD COLUMN chunker_version VARCHAR(128);

UPDATE rag_embedding_jobs job
SET document_kind = COALESCE((
        SELECT CASE
            WHEN document.document_type = 'json-record' THEN 'JSON_RECORD'
            ELSE 'TEXT'
        END
        FROM rag_documents document
        WHERE document.id = job.document_id
    ), 'TEXT'),
    chunker_version = COALESCE((
        SELECT state.chunker_version
        FROM rag_document_embedding_state state
        WHERE state.document_id = job.document_id
          AND state.embedding_profile_id = job.embedding_profile_id
          AND state.content_hash = job.content_hash
    ), 'legacy-unknown');

UPDATE rag_embedding_jobs
SET status = 'STALE',
    lease_owner = NULL,
    lease_expires_at = NULL,
    finished_at = CURRENT_TIMESTAMP,
    last_error = 'Superseded during document lifecycle migration',
    updated_at = CURRENT_TIMESTAMP
WHERE request_generation = 0
  AND status IN ('QUEUED', 'RUNNING');

CREATE INDEX idx_rag_embedding_job_generation
    ON rag_embedding_jobs (
        document_id,
        embedding_profile_id,
        request_generation
    );

CREATE UNIQUE INDEX uq_rag_embedding_job_active_generation
    ON rag_embedding_jobs (
        document_id,
        embedding_profile_id,
        request_generation
    )
    WHERE status IN ('QUEUED', 'RUNNING');

ALTER TABLE rag_document_versions
    ADD COLUMN title_snapshot VARCHAR(255),
    ADD COLUMN source_snapshot VARCHAR(255),
    ADD COLUMN document_type_snapshot VARCHAR(50),
    ADD COLUMN original_filename_snapshot VARCHAR(255),
    ADD COLUMN collection_id_snapshot BIGINT,
    ADD COLUMN source_namespace_snapshot VARCHAR(128),
    ADD COLUMN enabled_snapshot BOOLEAN,
    ADD COLUMN disabled_at_snapshot TIMESTAMP(6),
    ADD COLUMN source_deleted_at_snapshot TIMESTAMP(6),
    ADD COLUMN snapshot_completeness VARCHAR(40)
        NOT NULL DEFAULT 'CONTENT_AND_METADATA_ONLY';

ALTER TABLE rag_document_versions
    ADD CONSTRAINT ck_rag_document_version_snapshot_completeness
        CHECK (snapshot_completeness IN (
            'CONTENT_AND_METADATA_ONLY', 'FULL'
        ));

CREATE TABLE rag_document_idempotency_operations (
    id BIGSERIAL PRIMARY KEY,
    owner_principal_id VARCHAR(255) NOT NULL,
    operation_type VARCHAR(64) NOT NULL,
    idempotency_key_hash VARCHAR(64) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL,
    result_document_id BIGINT REFERENCES rag_documents(id) ON DELETE SET NULL,
    result_batch_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_rag_document_idempotency
        UNIQUE (owner_principal_id, operation_type, idempotency_key_hash),
    CONSTRAINT ck_rag_document_idempotency_status
        CHECK (status IN ('IN_PROGRESS', 'SUCCEEDED', 'FAILED'))
);

CREATE INDEX idx_rag_document_idempotency_expiry
    ON rag_document_idempotency_operations(expires_at);

COMMENT ON COLUMN rag_documents.document_revision IS
    'Public business CAS token; embedding-only writes do not increment it';
COMMENT ON COLUMN rag_documents.source_namespace IS
    'External connector identity namespace; default preserves V39 identities';
COMMENT ON COLUMN rag_documents.next_history_version IS
    'Atomic allocator for complete mutation snapshots';
COMMENT ON TABLE rag_document_source_namespaces IS
    'CAS coordination state for one Collection and external source namespace';
COMMENT ON COLUMN rag_document_embedding_state.request_generation IS
    'Current derivation generation for this document and embedding profile';
COMMENT ON COLUMN rag_embedding_jobs.request_generation IS
    'Derivation generation fenced at commit time';
