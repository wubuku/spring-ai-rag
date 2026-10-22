SET LOCAL lock_timeout = '5s';

ALTER TABLE rag_collection
    ADD COLUMN active_embedding_profile_id BIGINT
        REFERENCES rag_embedding_profiles(id);

ALTER TABLE rag_embeddings
    ADD COLUMN embedding_384 VECTOR(384),
    ADD COLUMN embedding_768 VECTOR(768),
    ADD COLUMN embedding_1536 VECTOR(1536);

ALTER TABLE rag_collection_purge_preview
    ADD COLUMN execution_mode VARCHAR(8) NOT NULL DEFAULT 'SYNC',
    ADD CONSTRAINT ck_rag_collection_purge_preview_execution_mode
        CHECK (execution_mode IN ('SYNC', 'ASYNC'));

CREATE TABLE rag_embedding_backfill_control (
    migration_key VARCHAR(64) PRIMARY KEY,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETED', 'DEGRADED')),
    cursor_document_id BIGINT NOT NULL DEFAULT 0 CHECK (cursor_document_id >= 0),
    lease_owner VARCHAR(128),
    lease_token UUID,
    lease_expires_at TIMESTAMPTZ,
    heartbeat_at TIMESTAMPTZ,
    candidate_count BIGINT NOT NULL DEFAULT 0 CHECK (candidate_count >= 0),
    queued_count BIGINT NOT NULL DEFAULT 0 CHECK (queued_count >= 0),
    succeeded_count BIGINT NOT NULL DEFAULT 0 CHECK (succeeded_count >= 0),
    failed_count BIGINT NOT NULL DEFAULT 0 CHECK (failed_count >= 0),
    last_error_code VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_rag_embedding_backfill_lease CHECK (
        (lease_owner IS NULL AND lease_token IS NULL AND lease_expires_at IS NULL)
        OR (lease_owner IS NOT NULL AND lease_token IS NOT NULL AND lease_expires_at IS NOT NULL)
    )
);

INSERT INTO rag_embedding_backfill_control (migration_key)
VALUES ('remove-legacy-embedding-v1');

CREATE TABLE rag_long_running_operations (
    id UUID PRIMARY KEY,
    owner_principal_id VARCHAR(128) NOT NULL,
    resource_type VARCHAR(32) NOT NULL CHECK (resource_type = 'COLLECTION'),
    collection_id BIGINT NOT NULL REFERENCES rag_collection(id) ON DELETE RESTRICT,
    operation_kind VARCHAR(32) NOT NULL
        CHECK (operation_kind IN ('COLLECTION_PURGE', 'EMBEDDING_PROFILE_SWITCH')),
    idempotency_key_sha256 CHAR(64) NOT NULL
        CHECK (idempotency_key_sha256 ~ '^[0-9a-f]{64}$'),
    request_fingerprint_sha256 CHAR(64) NOT NULL
        CHECK (request_fingerprint_sha256 ~ '^[0-9a-f]{64}$'),
    fingerprint_version SMALLINT NOT NULL DEFAULT 1 CHECK (fingerprint_version > 0),
    status VARCHAR(20) NOT NULL DEFAULT 'QUEUED'
        CHECK (status IN ('QUEUED', 'RUNNING', 'RETRY_WAIT', 'SUCCEEDED',
                          'CANCELLED', 'STALE', 'DEGRADED')),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    lease_owner VARCHAR(128),
    lease_token UUID,
    lease_expires_at TIMESTAMPTZ,
    heartbeat_at TIMESTAMPTZ,
    cancel_requested_at TIMESTAMPTZ,
    purge_fence_completed_at TIMESTAMPTZ,
    next_retry_at TIMESTAMPTZ,
    terminal_reason VARCHAR(64),
    progress_payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    result_payload JSONB,
    error_code VARCHAR(64),
    error_payload JSONB,
    target_embedding_profile_id BIGINT REFERENCES rag_embedding_profiles(id),
    source_preview_id UUID UNIQUE
        REFERENCES rag_collection_purge_preview(id) ON DELETE RESTRICT,
    cursor_document_id BIGINT NOT NULL DEFAULT 0 CHECK (cursor_document_id >= 0),
    cursor_phase VARCHAR(64),
    cursor_row_id BIGINT NOT NULL DEFAULT 0 CHECK (cursor_row_id >= 0),
    processed_count BIGINT NOT NULL DEFAULT 0 CHECK (processed_count >= 0),
    total_count BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at TIMESTAMPTZ,
    result_expires_at TIMESTAMPTZ,
    tombstone_expires_at TIMESTAMPTZ,
    CONSTRAINT uq_rag_long_running_operation_idempotency UNIQUE (
        owner_principal_id, idempotency_key_sha256, operation_kind, collection_id
    ),
    CONSTRAINT ck_rag_long_running_operation_kind_fields CHECK (
        (operation_kind = 'COLLECTION_PURGE'
            AND source_preview_id IS NOT NULL
            AND target_embedding_profile_id IS NULL)
        OR (operation_kind = 'EMBEDDING_PROFILE_SWITCH'
            AND source_preview_id IS NULL
            AND target_embedding_profile_id IS NOT NULL)
    ),
    CONSTRAINT ck_rag_long_running_operation_lease CHECK (
        (lease_owner IS NULL AND lease_token IS NULL AND lease_expires_at IS NULL)
        OR (lease_owner IS NOT NULL AND lease_token IS NOT NULL AND lease_expires_at IS NOT NULL)
    ),
    CONSTRAINT ck_rag_long_running_operation_retry CHECK (
        next_retry_at IS NULL OR status = 'RETRY_WAIT'
    ),
    CONSTRAINT ck_rag_long_running_operation_result_retention CHECK (
        result_expires_at IS NULL OR finished_at IS NOT NULL
    )
);

CREATE INDEX idx_rag_long_running_operations_schedule
    ON rag_long_running_operations(status, next_retry_at, lease_expires_at, created_at);
CREATE INDEX idx_rag_long_running_operations_owner
    ON rag_long_running_operations(owner_principal_id, created_at DESC);
CREATE INDEX idx_rag_long_running_operations_collection
    ON rag_long_running_operations(collection_id, status, updated_at DESC);
CREATE INDEX idx_rag_long_running_operations_retention
    ON rag_long_running_operations(result_expires_at, tombstone_expires_at)
    WHERE status IN ('SUCCEEDED', 'CANCELLED', 'STALE');

CREATE TABLE rag_long_running_operation_owner_capacity (
    owner_principal_id VARCHAR(128) PRIMARY KEY,
    active_count INTEGER NOT NULL DEFAULT 0 CHECK (active_count >= 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE rag_long_running_operation_global_capacity (
    singleton_id SMALLINT PRIMARY KEY CHECK (singleton_id = 1),
    active_count INTEGER NOT NULL DEFAULT 0 CHECK (active_count >= 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO rag_long_running_operation_global_capacity (singleton_id, active_count)
VALUES (1, 0);

CREATE TABLE rag_collection_operation_slot (
    collection_id BIGINT PRIMARY KEY REFERENCES rag_collection(id) ON DELETE CASCADE,
    holder_kind VARCHAR(24) NOT NULL
        CHECK (holder_kind IN ('SYNC_PURGE', 'ASYNC_PURGE', 'PROFILE_SWITCH')),
    operation_id UUID UNIQUE
        REFERENCES rag_long_running_operations(id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_rag_collection_operation_slot_owner CHECK (
        (holder_kind = 'SYNC_PURGE' AND operation_id IS NULL)
        OR (holder_kind IN ('ASYNC_PURGE', 'PROFILE_SWITCH') AND operation_id IS NOT NULL)
    )
);

CREATE TABLE rag_long_running_operation_run_refs (
    operation_id UUID NOT NULL REFERENCES rag_long_running_operations(id) ON DELETE CASCADE,
    run_id UUID NOT NULL REFERENCES rag_document_processing_runs(id) ON DELETE CASCADE,
    created_by_operation BOOLEAN NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (operation_id, run_id)
);

CREATE INDEX idx_rag_long_running_operation_run_refs_run
    ON rag_long_running_operation_run_refs(run_id, operation_id);

CREATE TABLE rag_collection_purge_preview_admission_owner (
    owner_principal_id VARCHAR(128) PRIMARY KEY,
    lease_owner VARCHAR(128),
    lease_token UUID,
    lease_expires_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_rag_purge_preview_owner_lease CHECK (
        (lease_owner IS NULL AND lease_token IS NULL AND lease_expires_at IS NULL)
        OR (lease_owner IS NOT NULL AND lease_token IS NOT NULL AND lease_expires_at IS NOT NULL)
    )
);

CREATE TABLE rag_collection_purge_preview_admission_global (
    singleton_id SMALLINT PRIMARY KEY CHECK (singleton_id = 1),
    active_count SMALLINT NOT NULL DEFAULT 0 CHECK (active_count BETWEEN 0 AND 2),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO rag_collection_purge_preview_admission_global (singleton_id, active_count)
VALUES (1, 0);
