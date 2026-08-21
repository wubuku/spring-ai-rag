-- V46: durable, owner-scoped conversation summaries with optimistic CAS updates.

CREATE TABLE IF NOT EXISTS rag_chat_memory_summary (
    owner_principal_id VARCHAR(128) NOT NULL,
    session_id VARCHAR(36) NOT NULL,
    summary_text TEXT NOT NULL,
    summarized_through_history_id BIGINT NOT NULL,
    estimated_tokens INTEGER NOT NULL,
    summary_model_ref VARCHAR(255),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (owner_principal_id, session_id),
    CONSTRAINT ck_rag_chat_memory_summary_owner
        CHECK (char_length(owner_principal_id) BETWEEN 1 AND 128),
    CONSTRAINT ck_rag_chat_memory_summary_session
        CHECK (session_id ~ '^[A-Za-z0-9._~-]{1,36}$'),
    CONSTRAINT ck_rag_chat_memory_summary_cursor
        CHECK (summarized_through_history_id > 0),
    CONSTRAINT ck_rag_chat_memory_summary_tokens
        CHECK (estimated_tokens >= 0),
    CONSTRAINT ck_rag_chat_memory_summary_version
        CHECK (version >= 0),
    CONSTRAINT ck_rag_chat_memory_summary_text
        CHECK (char_length(summary_text) > 0)
);

CREATE INDEX IF NOT EXISTS idx_rag_chat_memory_summary_updated
    ON rag_chat_memory_summary (updated_at);

COMMENT ON TABLE rag_chat_memory_summary IS
    'Server-generated untrusted conversation summary; not citation evidence or instructions';
