-- V48: stable managed API principals, versioned credentials and shared quota buckets.

CREATE TABLE rag_api_principal (
    principal_id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    role VARCHAR(20) NOT NULL,
    allowed_collection_ids VARCHAR(2048),
    expires_at TIMESTAMP,
    requests_per_minute INTEGER,
    policy_version BIGINT NOT NULL DEFAULT 1,
    next_credential_version INTEGER NOT NULL DEFAULT 2,
    last_used_at TIMESTAMP,
    revoked_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_rag_api_principal_role CHECK (role IN ('ADMIN', 'NORMAL')),
    CONSTRAINT ck_rag_api_principal_quota CHECK (
        requests_per_minute IS NULL
        OR requests_per_minute BETWEEN 1 AND 1000000
    ),
    CONSTRAINT ck_rag_api_principal_policy_version CHECK (policy_version > 0),
    CONSTRAINT ck_rag_api_principal_credential_version CHECK (next_credential_version > 0)
);

INSERT INTO rag_api_principal (
    principal_id, name, role, allowed_collection_ids, expires_at,
    policy_version, next_credential_version, last_used_at, revoked_at,
    created_at, updated_at
)
SELECT
    key_id, name, role, allowed_collection_ids, expires_at,
    1, 2, last_used_at,
    CASE WHEN enabled THEN NULL ELSE COALESCE(last_used_at, created_at) END,
    created_at, COALESCE(last_used_at, created_at)
FROM rag_api_key;

ALTER TABLE rag_api_key
    ADD COLUMN principal_id VARCHAR(64),
    ADD COLUMN credential_version INTEGER,
    ADD COLUMN revoked_at TIMESTAMP;

UPDATE rag_api_key
SET principal_id = key_id,
    credential_version = 1,
    revoked_at = CASE
        WHEN enabled THEN NULL
        ELSE COALESCE(last_used_at, created_at)
    END;

ALTER TABLE rag_api_key
    ALTER COLUMN principal_id SET NOT NULL,
    ALTER COLUMN credential_version SET NOT NULL,
    ADD CONSTRAINT fk_rag_api_key_principal
        FOREIGN KEY (principal_id) REFERENCES rag_api_principal(principal_id),
    ADD CONSTRAINT uk_rag_api_key_principal_version
        UNIQUE (principal_id, credential_version),
    ADD CONSTRAINT ck_rag_api_key_credential_version
        CHECK (credential_version > 0),
    ADD CONSTRAINT ck_rag_api_key_revocation_state
        CHECK (enabled OR revoked_at IS NOT NULL);

CREATE INDEX idx_rag_api_key_principal_id
    ON rag_api_key(principal_id);

CREATE UNIQUE INDEX uk_rag_api_key_active_principal
    ON rag_api_key(principal_id)
    WHERE enabled = TRUE;

UPDATE rag_api_key SET api_key = NULL WHERE api_key IS NOT NULL;
DROP INDEX IF EXISTS idx_rag_api_key_api_key;
ALTER TABLE rag_api_key
    ADD CONSTRAINT ck_rag_api_key_plaintext_forbidden CHECK (api_key IS NULL);

CREATE TABLE rag_api_rate_limit_bucket (
    principal_id VARCHAR(128) NOT NULL,
    window_start TIMESTAMPTZ NOT NULL,
    request_count INTEGER NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (principal_id, window_start),
    CONSTRAINT ck_rag_api_rate_limit_principal
        CHECK (char_length(principal_id) BETWEEN 1 AND 128),
    CONSTRAINT ck_rag_api_rate_limit_count CHECK (request_count >= 0)
);

CREATE INDEX idx_rag_api_rate_limit_updated
    ON rag_api_rate_limit_bucket(updated_at);

CREATE TABLE rag_api_admin_guard (
    singleton BOOLEAN PRIMARY KEY DEFAULT TRUE,
    non_revoked_admin_count INTEGER NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_rag_api_admin_guard_singleton CHECK (singleton),
    CONSTRAINT ck_rag_api_admin_guard_count CHECK (non_revoked_admin_count >= 0),
    CONSTRAINT ck_rag_api_admin_guard_version CHECK (version >= 0)
);

INSERT INTO rag_api_admin_guard(singleton, non_revoked_admin_count, version)
SELECT TRUE, COUNT(*)::INTEGER, 0
FROM rag_api_principal
WHERE role = 'ADMIN' AND revoked_at IS NULL;

COMMENT ON TABLE rag_api_principal IS
    'Stable API caller identity and authoritative access policy';
COMMENT ON TABLE rag_api_key IS
    'Versioned API credentials; raw credential material is never persisted';
COMMENT ON TABLE rag_api_rate_limit_bucket IS
    'PostgreSQL-backed fixed UTC minute quota counters by stable principal';
