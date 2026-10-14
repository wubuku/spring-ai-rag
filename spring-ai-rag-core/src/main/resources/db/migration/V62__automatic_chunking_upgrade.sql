ALTER TABLE rag_documents ADD COLUMN derivation_chunker_version VARCHAR(128);

UPDATE rag_documents document
SET derivation_chunker_version = COALESCE(
    (SELECT local.chunker_version FROM rag_document_local_index_state local
     WHERE local.document_id = document.id AND local.content_hash = document.content_hash
       AND local.chunker_version ~ '^(hierarchical-v2:[0-9]+:[0-9]+:[0-9]+|bounded-v3:[0-9]+:[0-9]+:[0-9]+:[0-9]+:[0-9]+)$'),
    (SELECT state.chunker_version FROM rag_document_embedding_state state
     WHERE state.document_id = document.id AND state.content_hash = document.content_hash
       AND state.chunker_version ~ '^(hierarchical-v2:[0-9]+:[0-9]+:[0-9]+|bounded-v3:[0-9]+:[0-9]+:[0-9]+:[0-9]+:[0-9]+)$'
     ORDER BY state.updated_at DESC, state.embedding_profile_id LIMIT 1),
    (SELECT job.chunker_version FROM rag_embedding_jobs job
     WHERE job.document_id = document.id AND job.content_hash = document.content_hash
       AND job.status IN ('QUEUED', 'RUNNING')
       AND job.chunker_version ~ '^(hierarchical-v2:[0-9]+:[0-9]+:[0-9]+|bounded-v3:[0-9]+:[0-9]+:[0-9]+:[0-9]+:[0-9]+)$'
     ORDER BY job.created_at DESC, job.id LIMIT 1))
WHERE document.document_type IS DISTINCT FROM 'json-record';

CREATE INDEX idx_rag_document_chunking_upgrade
    ON rag_documents (id) WHERE derivation_chunker_version IS NOT NULL;

CREATE TABLE rag_chunking_upgrade_tasks (
    document_id BIGINT NOT NULL REFERENCES rag_documents(id) ON DELETE CASCADE,
    embedding_profile_id BIGINT NOT NULL REFERENCES rag_embedding_profiles(id) ON DELETE CASCADE,
    target_version VARCHAR(128) NOT NULL,
    source_hash VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('RUNNING', 'RETRY', 'COMPLETED', 'FAILED')),
    attempt_count INTEGER NOT NULL CHECK (attempt_count BETWEEN 1 AND 3),
    lease_owner VARCHAR(64),
    lease_expires_at TIMESTAMPTZ,
    available_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_error_code VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (document_id, embedding_profile_id)
);

COMMENT ON COLUMN rag_documents.derivation_chunker_version IS
    'Server-owned serving identity during automatic upgrade; NULL uses the configured target';
COMMENT ON TABLE rag_chunking_upgrade_tasks IS
    'Bounded resumable chunking upgrades; contains no document text or provider payload';
