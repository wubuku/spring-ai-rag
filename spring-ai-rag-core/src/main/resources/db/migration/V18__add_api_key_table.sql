-- ============================================================
-- V18: API Key management table
-- ============================================================
-- Stores API keys for programmatic authentication.
-- The raw key value is never stored — only its SHA-256 hash.
-- The public keyId (rag_k_xxx) is used for listings.
-- The raw key (rag_sk_xxx) is shown only once at creation.
-- ============================================================

CREATE TABLE rag_api_key (
    id              BIGSERIAL PRIMARY KEY,
    key_id          VARCHAR(64)  NOT NULL UNIQUE,
    key_hash        VARCHAR(64) NOT NULL,
    name            VARCHAR(255) NOT NULL,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    last_used_at    TIMESTAMP,
    expires_at      TIMESTAMP,
    enabled         BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE UNIQUE INDEX idx_rag_api_key_key_id ON rag_api_key(key_id);
CREATE INDEX idx_rag_api_key_enabled ON rag_api_key(enabled);
