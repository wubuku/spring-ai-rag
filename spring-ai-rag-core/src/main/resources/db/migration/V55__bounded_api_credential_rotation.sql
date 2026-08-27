-- V55: bounded, staged API credential rotation with fail-closed overlap deadlines.

ALTER TABLE rag_api_key
    ADD COLUMN retire_at TIMESTAMP;

DROP INDEX uk_rag_api_key_active_principal;

CREATE UNIQUE INDEX uk_rag_api_key_current_principal
    ON rag_api_key(principal_id)
    WHERE enabled = TRUE AND retire_at IS NULL;

CREATE UNIQUE INDEX uk_rag_api_key_retiring_principal
    ON rag_api_key(principal_id)
    WHERE enabled = TRUE AND retire_at IS NOT NULL;

CREATE INDEX idx_rag_api_key_retire_at
    ON rag_api_key(retire_at)
    WHERE enabled = TRUE AND retire_at IS NOT NULL;

CREATE TABLE rag_api_key_rotation (
    rotation_id UUID PRIMARY KEY,
    principal_id VARCHAR(64) NOT NULL
        REFERENCES rag_api_principal(principal_id),
    idempotency_key_hash VARCHAR(64) NOT NULL,
    request_fingerprint_sha256 VARCHAR(64) NOT NULL,
    source_credential_id VARCHAR(64) NOT NULL
        REFERENCES rag_api_key(key_id),
    target_credential_id VARCHAR(64) NOT NULL
        REFERENCES rag_api_key(key_id),
    overlap_seconds INTEGER NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    terminal_at TIMESTAMP,
    CONSTRAINT uk_rag_api_key_rotation_principal_idempotency
        UNIQUE (principal_id, idempotency_key_hash),
    CONSTRAINT ck_rag_api_key_rotation_hashes CHECK (
        idempotency_key_hash ~ '^[0-9a-f]{64}$'
        AND request_fingerprint_sha256 ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_rag_api_key_rotation_credentials CHECK (
        source_credential_id <> target_credential_id
    ),
    CONSTRAINT ck_rag_api_key_rotation_overlap CHECK (
        overlap_seconds BETWEEN 1 AND 86400
    ),
    CONSTRAINT ck_rag_api_key_rotation_status CHECK (
        status IN ('PENDING', 'COMPLETED', 'CANCELED', 'EXPIRED', 'REVOKED')
    ),
    CONSTRAINT ck_rag_api_key_rotation_terminal CHECK (
        (status = 'PENDING' AND terminal_at IS NULL)
        OR (status <> 'PENDING' AND terminal_at IS NOT NULL)
    )
);

CREATE UNIQUE INDEX uk_rag_api_key_rotation_pending_principal
    ON rag_api_key_rotation(principal_id)
    WHERE status = 'PENDING';

CREATE INDEX idx_rag_api_key_rotation_pending_expiry
    ON rag_api_key_rotation(expires_at, rotation_id)
    WHERE status = 'PENDING';

CREATE INDEX idx_rag_api_key_rotation_terminal_retention
    ON rag_api_key_rotation(terminal_at, rotation_id)
    WHERE status <> 'PENDING';

COMMENT ON TABLE rag_api_key_rotation IS
    'Bounded credential rotation metadata; never stores raw credentials or header values';
