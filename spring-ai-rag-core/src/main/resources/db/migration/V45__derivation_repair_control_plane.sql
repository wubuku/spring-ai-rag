-- V45: bounded, preview-first derivation repair control plane.

CREATE TABLE rag_derivation_repair_previews (
    id UUID PRIMARY KEY,
    owner_principal_id VARCHAR(255) NOT NULL,
    collection_id BIGINT NOT NULL REFERENCES rag_collection(id),
    active_embedding_profile_id BIGINT NOT NULL REFERENCES rag_embedding_profiles(id),
    preview_token_hash VARCHAR(64) NOT NULL,
    preview_fingerprint VARCHAR(64) NOT NULL,
    request_payload JSONB NOT NULL,
    plan_payload JSONB NOT NULL,
    status VARCHAR(16) NOT NULL,
    apply_lease_owner_hash VARCHAR(64),
    apply_lease_expires_at TIMESTAMPTZ,
    preview_deadline TIMESTAMPTZ NOT NULL,
    operation_deadline TIMESTAMPTZ NOT NULL,
    result_expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    CONSTRAINT ck_rag_derivation_repair_status
        CHECK (status IN ('PREVIEWED', 'APPLYING', 'COMPLETED', 'EXPIRED')),
    CONSTRAINT ck_rag_derivation_repair_deadlines
        CHECK (preview_deadline <= operation_deadline
            AND operation_deadline <= result_expires_at),
    CONSTRAINT ck_rag_derivation_repair_completion
        CHECK ((status IN ('COMPLETED', 'EXPIRED') AND completed_at IS NOT NULL)
            OR (status IN ('PREVIEWED', 'APPLYING') AND completed_at IS NULL))
);

CREATE TABLE rag_derivation_repair_items (
    repair_id UUID NOT NULL REFERENCES rag_derivation_repair_previews(id) ON DELETE CASCADE,
    document_id BIGINT NOT NULL REFERENCES rag_documents(id),
    planned_document_revision BIGINT NOT NULL,
    planned_document_version BIGINT NOT NULL,
    planned_content_hash VARCHAR(64),
    planned_local_generation BIGINT NOT NULL,
    planned_vector_generation BIGINT NOT NULL,
    action VARCHAR(64) NOT NULL,
    reason_code VARCHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL,
    local_action_status VARCHAR(16) NOT NULL,
    vector_action_status VARCHAR(16) NOT NULL,
    embedding_job_id UUID,
    result_code VARCHAR(64),
    error_message VARCHAR(500),
    lease_owner_hash VARCHAR(64),
    lease_expires_at TIMESTAMPTZ,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    post_local_document_version BIGINT,
    post_local_content_hash VARCHAR(64),
    post_local_generation BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (repair_id, document_id),
    CONSTRAINT ck_rag_derivation_repair_item_status
        CHECK (status IN ('PLANNED', 'APPLYING', 'SUCCEEDED', 'SKIPPED', 'FAILED')),
    CONSTRAINT ck_rag_derivation_repair_local_status
        CHECK (local_action_status IN
            ('NOT_PLANNED', 'PLANNED', 'APPLYING', 'SUCCEEDED', 'SKIPPED', 'FAILED')),
    CONSTRAINT ck_rag_derivation_repair_vector_status
        CHECK (vector_action_status IN
            ('NOT_PLANNED', 'PLANNED', 'APPLYING', 'SUCCEEDED', 'SKIPPED', 'FAILED')),
    CONSTRAINT ck_rag_derivation_repair_attempt_count
        CHECK (attempt_count BETWEEN 0 AND 10)
);

CREATE INDEX idx_rag_derivation_repair_owner_status
    ON rag_derivation_repair_previews(owner_principal_id, status, created_at DESC);
CREATE INDEX idx_rag_derivation_repair_item_status
    ON rag_derivation_repair_items(repair_id, status, document_id);

COMMENT ON TABLE rag_derivation_repair_previews IS
    'Bounded derivation repair previews; payloads never contain document content or raw tokens';
COMMENT ON TABLE rag_derivation_repair_items IS
    'Per-document resumable repair ledger';
