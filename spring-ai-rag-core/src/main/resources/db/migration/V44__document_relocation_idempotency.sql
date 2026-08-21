-- V44: durable idempotent external-document relocation and permanent old-address fencing.

ALTER TABLE rag_document_idempotency_operations
    ADD COLUMN result_payload JSONB,
    ADD COLUMN authorization_collection_ids BIGINT[];

CREATE TABLE rag_document_relocated_addresses (
    id BIGSERIAL PRIMARY KEY,
    source_collection_id BIGINT NOT NULL REFERENCES rag_collection(id),
    source_namespace VARCHAR(128) NOT NULL,
    external_id VARCHAR(255) NOT NULL,
    document_id BIGINT REFERENCES rag_documents(id) ON DELETE SET NULL,
    target_collection_id BIGINT NOT NULL REFERENCES rag_collection(id),
    relocation_idempotency_operation_id BIGINT
        REFERENCES rag_document_idempotency_operations(id) ON DELETE SET NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved_at TIMESTAMPTZ,
    CONSTRAINT ck_rag_relocated_address_namespace
        CHECK (BTRIM(source_namespace) <> '' AND LENGTH(source_namespace) <= 128),
    CONSTRAINT ck_rag_relocated_address_external_id
        CHECK (BTRIM(external_id) <> '' AND LENGTH(external_id) <= 255),
    CONSTRAINT ck_rag_relocated_address_collections
        CHECK (source_collection_id <> target_collection_id),
    CONSTRAINT ck_rag_relocated_address_resolution
        CHECK ((active = TRUE AND resolved_at IS NULL)
            OR (active = FALSE AND resolved_at IS NOT NULL))
);

CREATE UNIQUE INDEX uk_rag_relocated_address_active
    ON rag_document_relocated_addresses(
        source_collection_id, source_namespace, external_id
    ) WHERE active = TRUE;

CREATE INDEX idx_rag_relocated_address_document
    ON rag_document_relocated_addresses(document_id, active);

ALTER TABLE rag_document_idempotency_operations
    ADD CONSTRAINT ck_rag_relocation_idempotency_result
    CHECK (
        operation_type <> 'EXTERNAL_RELOCATE'
        OR status <> 'SUCCEEDED'
        OR (
            result_payload IS NOT NULL
            AND authorization_collection_ids IS NOT NULL
            AND cardinality(authorization_collection_ids) = 2
            AND authorization_collection_ids[1] < authorization_collection_ids[2]
        )
    );

COMMENT ON TABLE rag_document_relocated_addresses IS
    'Permanent fencing ledger for retired external document placement addresses';
COMMENT ON COLUMN rag_document_idempotency_operations.result_payload IS
    'Versioned immutable response envelope for operations requiring exact replay';
COMMENT ON COLUMN rag_document_idempotency_operations.authorization_collection_ids IS
    'Sorted Collection IDs whose current ACL must be rechecked before replay';
