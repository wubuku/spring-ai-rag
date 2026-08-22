-- V47: durable Chat turn idempotency, replay snapshots and bounded leases.

ALTER TABLE rag_chat_history
    ADD COLUMN IF NOT EXISTS turn_id UUID;

CREATE UNIQUE INDEX IF NOT EXISTS uk_rag_chat_history_turn_id
    ON rag_chat_history(turn_id)
    WHERE turn_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS rag_chat_turn_operations (
    id BIGSERIAL PRIMARY KEY,
    owner_principal_id VARCHAR(128) NOT NULL,
    idempotency_key_sha256 CHAR(64) NOT NULL,
    request_fingerprint_sha256 CHAR(64) NOT NULL,
    fingerprint_version INTEGER NOT NULL DEFAULT 1,
    session_id VARCHAR(36) NOT NULL,
    turn_id UUID NOT NULL,
    transport VARCHAR(32) NOT NULL,
    status VARCHAR(20) NOT NULL,
    operation_token UUID,
    lease_expires_at TIMESTAMPTZ,
    attempt_count INTEGER NOT NULL DEFAULT 1,
    row_version BIGINT NOT NULL DEFAULT 0,
    response_version INTEGER NOT NULL DEFAULT 1,
    execution_snapshot JSONB,
    response_payload JSONB,
    error_code VARCHAR(96),
    error_payload JSONB,
    authorization_scope_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    CONSTRAINT uk_rag_chat_turn_operation_key
        UNIQUE(owner_principal_id, idempotency_key_sha256),
    CONSTRAINT uk_rag_chat_turn_operation_turn
        UNIQUE(turn_id),
    CONSTRAINT ck_rag_chat_turn_operation_owner
        CHECK (char_length(owner_principal_id) BETWEEN 1 AND 128),
    CONSTRAINT ck_rag_chat_turn_operation_key_hash
        CHECK (idempotency_key_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_rag_chat_turn_operation_fingerprint_hash
        CHECK (request_fingerprint_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_rag_chat_turn_operation_fingerprint_version
        CHECK (fingerprint_version > 0),
    CONSTRAINT ck_rag_chat_turn_operation_session
        CHECK (session_id ~ '^[A-Za-z0-9._~-]{1,36}$'),
    CONSTRAINT ck_rag_chat_turn_operation_transport
        CHECK (transport IN ('NATIVE_JSON', 'NATIVE_SSE', 'OPENAI_JSON', 'OPENAI_SSE')),
    CONSTRAINT ck_rag_chat_turn_operation_status
        CHECK (status IN ('IN_PROGRESS', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT ck_rag_chat_turn_operation_attempt_count
        CHECK (attempt_count > 0),
    CONSTRAINT ck_rag_chat_turn_operation_row_version
        CHECK (row_version >= 0),
    CONSTRAINT ck_rag_chat_turn_operation_response_version
        CHECK (response_version > 0),
    CONSTRAINT ck_rag_chat_turn_operation_lifecycle
        CHECK (
            (status = 'IN_PROGRESS'
                AND operation_token IS NOT NULL
                AND lease_expires_at IS NOT NULL
                AND completed_at IS NULL
                AND response_payload IS NULL
                AND error_code IS NULL
                AND error_payload IS NULL)
            OR
            (status = 'SUCCEEDED'
                AND operation_token IS NULL
                AND lease_expires_at IS NULL
                AND completed_at IS NOT NULL
                AND execution_snapshot IS NOT NULL
                AND response_payload IS NOT NULL
                AND error_code IS NULL
                AND error_payload IS NULL)
            OR
            (status = 'FAILED'
                AND operation_token IS NULL
                AND lease_expires_at IS NULL
                AND completed_at IS NOT NULL
                AND error_code IS NOT NULL
                AND error_payload IS NOT NULL
                AND response_payload IS NULL)
        ),
    CONSTRAINT ck_rag_chat_turn_operation_snapshot_versions
        CHECK (
            jsonb_typeof(authorization_scope_snapshot) = 'object'
            AND (
                execution_snapshot IS NULL
                OR execution_snapshot ->> 'executionSnapshotVersion' = '1'
            )
            AND (
                error_payload IS NULL
                OR error_payload ->> 'errorSnapshotVersion' = '1'
            )
        ));

CREATE INDEX IF NOT EXISTS idx_rag_chat_turn_operation_status_lease
    ON rag_chat_turn_operations(status, lease_expires_at);

CREATE INDEX IF NOT EXISTS idx_rag_chat_turn_operation_updated
    ON rag_chat_turn_operations(updated_at);

COMMENT ON TABLE rag_chat_turn_operations IS
    'Principal-scoped durable Chat turn idempotency operations and replay snapshots';
COMMENT ON COLUMN rag_chat_turn_operations.response_payload IS
    'Transport-neutral immutable Chat business result; protocol envelopes are projected per request';
