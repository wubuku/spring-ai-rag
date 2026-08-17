-- V32: principal-owned chat history, citation snapshots, and cross-instance session lease.
-- Legacy rows keep owner_principal_id NULL and are never claimed or rewritten.

ALTER TABLE rag_chat_history
    ADD COLUMN IF NOT EXISTS owner_principal_id VARCHAR(128),
    ADD COLUMN IF NOT EXISTS sources JSONB,
    ADD COLUMN IF NOT EXISTS turn_status VARCHAR(20) NOT NULL DEFAULT 'COMPLETE';

ALTER TABLE rag_chat_history
    ADD CONSTRAINT ck_rag_chat_history_session_id
        CHECK (session_id ~ '^[A-Za-z0-9._~-]{1,36}$') NOT VALID,
    ADD CONSTRAINT ck_rag_chat_history_owner_principal_id
        CHECK (
            owner_principal_id IS NULL
            OR char_length(owner_principal_id) BETWEEN 1 AND 128
        ),
    ADD CONSTRAINT ck_rag_chat_history_turn_status
        CHECK (turn_status IN ('COMPLETE', 'CANCELLED'));

CREATE INDEX IF NOT EXISTS idx_rag_chat_owner_session_created
    ON rag_chat_history
    (owner_principal_id, session_id, created_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS idx_rag_chat_ttl_owner_session
    ON rag_chat_history (created_at, owner_principal_id, session_id);

CREATE TABLE IF NOT EXISTS rag_chat_session_lease (
    owner_principal_id VARCHAR(128) NOT NULL,
    session_id VARCHAR(36) NOT NULL,
    owner_token VARCHAR(36) NOT NULL,
    acquired_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (owner_principal_id, session_id),
    CONSTRAINT ck_rag_chat_session_lease_owner
        CHECK (char_length(owner_principal_id) BETWEEN 1 AND 128),
    CONSTRAINT ck_rag_chat_session_lease_session
        CHECK (session_id ~ '^[A-Za-z0-9._~-]{1,36}$'),
    CONSTRAINT ck_rag_chat_session_lease_token
        CHECK (owner_token ~ '^[A-Za-z0-9._~-]{1,36}$'),
    CONSTRAINT ck_rag_chat_session_lease_expiry
        CHECK (expires_at > acquired_at)
);

CREATE INDEX IF NOT EXISTS idx_rag_chat_session_lease_expires
    ON rag_chat_session_lease (expires_at);
