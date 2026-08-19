-- V42: authoritative external-source snapshot reconciliation.

ALTER TABLE rag_documents
    ADD COLUMN IF NOT EXISTS last_seen_sync_run_id UUID,
    ADD COLUMN IF NOT EXISTS last_seen_sync_generation BIGINT,
    ADD COLUMN IF NOT EXISTS reconciliation_tombstone_run_id UUID,
    ADD COLUMN IF NOT EXISTS deletion_origin VARCHAR(32);

UPDATE rag_documents
SET deletion_origin = 'SOURCE'
WHERE source_deleted_at IS NOT NULL
  AND deletion_origin IS NULL;

ALTER TABLE rag_documents
    DROP CONSTRAINT IF EXISTS ck_rag_document_deletion_origin;

ALTER TABLE rag_documents
    ADD CONSTRAINT ck_rag_document_deletion_origin
        CHECK (deletion_origin IS NULL OR deletion_origin IN ('SOURCE', 'RECONCILIATION'));

CREATE TABLE rag_document_sync_runs (
    id UUID PRIMARY KEY,
    collection_id BIGINT NOT NULL REFERENCES rag_collection(id) ON DELETE CASCADE,
    source_namespace VARCHAR(128) NOT NULL,
    client_run_id VARCHAR(255) NOT NULL,
    lease_token_hash VARCHAR(64) NOT NULL,
    sync_generation BIGINT NOT NULL,
    snapshot_start_sequence BIGINT NOT NULL,
    complete_sequence BIGINT,
    snapshot_mode VARCHAR(32) NOT NULL,
    missing_policy VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    lease_expires_at TIMESTAMPTZ NOT NULL,
    preview_token_hash VARCHAR(64),
    preview_fingerprint VARCHAR(64),
    preview_missing_count INTEGER,
    applied_count INTEGER NOT NULL DEFAULT 0,
    unchanged_count INTEGER NOT NULL DEFAULT 0,
    skipped_count INTEGER NOT NULL DEFAULT 0,
    failed_count INTEGER NOT NULL DEFAULT 0,
    tombstoned_count INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    aborted_at TIMESTAMPTZ,
    CONSTRAINT uk_rag_sync_run_client
        UNIQUE (collection_id, source_namespace, client_run_id),
    CONSTRAINT ck_rag_sync_run_mode
        CHECK (snapshot_mode IN ('ONLINE_CUT', 'OFFLINE_MANIFEST', 'EXCLUSIVE_OFFLINE')),
    CONSTRAINT ck_rag_sync_run_missing_policy
        CHECK (missing_policy IN ('NONE', 'TOMBSTONE')),
    CONSTRAINT ck_rag_sync_run_status
        CHECK (status IN ('ACTIVE', 'COMPLETED', 'ABORTED', 'EXPIRED')),
    CONSTRAINT ck_rag_sync_run_namespace
        CHECK (source_namespace <> '' AND LENGTH(source_namespace) <= 128),
    CONSTRAINT ck_rag_sync_run_counts
        CHECK (
            applied_count >= 0 AND unchanged_count >= 0
            AND skipped_count >= 0 AND failed_count >= 0
            AND tombstoned_count >= 0
        )
);

CREATE UNIQUE INDEX uk_rag_sync_run_active_scope
    ON rag_document_sync_runs(collection_id, source_namespace)
    WHERE status = 'ACTIVE';

CREATE INDEX idx_rag_sync_run_scope_status
    ON rag_document_sync_runs(collection_id, source_namespace, status, created_at DESC);

CREATE TABLE rag_document_sync_run_items (
    run_id UUID NOT NULL REFERENCES rag_document_sync_runs(id) ON DELETE CASCADE,
    external_id VARCHAR(255) NOT NULL,
    document_kind VARCHAR(32) NOT NULL,
    item_fingerprint VARCHAR(64) NOT NULL,
    source_revision VARCHAR(255) NOT NULL,
    document_id BIGINT REFERENCES rag_documents(id) ON DELETE SET NULL,
    status VARCHAR(32) NOT NULL,
    error_code VARCHAR(64),
    error_message VARCHAR(500),
    seen_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (run_id, external_id),
    CONSTRAINT ck_rag_sync_run_item_kind
        CHECK (document_kind IN ('TEXT', 'JSON_RECORD')),
    CONSTRAINT ck_rag_sync_run_item_status
        CHECK (status IN ('APPLIED', 'UNCHANGED', 'SKIPPED_NEWER_MUTATION', 'FAILED'))
);

CREATE INDEX idx_rag_sync_run_item_document
    ON rag_document_sync_run_items(document_id);

COMMENT ON TABLE rag_document_sync_runs IS
    'Authoritative external source snapshot reconciliation runs';
COMMENT ON TABLE rag_document_sync_run_items IS
    'Idempotent snapshot item ledger; never stores document body or JSONB payload';
COMMENT ON COLUMN rag_documents.deletion_origin IS
    'SOURCE or server-owned RECONCILIATION marker';
