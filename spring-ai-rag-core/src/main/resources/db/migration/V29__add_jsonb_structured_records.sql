-- V29: JSONB structured records and payload-aware document versions.
-- Keep JSONB records separate from ordinary content-hash deduplication.

ALTER TABLE rag_documents
    ADD COLUMN IF NOT EXISTS external_id VARCHAR(255),
    ADD COLUMN IF NOT EXISTS jsonb_payload JSONB;

CREATE UNIQUE INDEX IF NOT EXISTS uk_rag_doc_structured_identity
    ON rag_documents (collection_id, document_type, external_id)
    WHERE collection_id IS NOT NULL
      AND document_type = 'json-record'
      AND external_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_rag_doc_collection_type
    ON rag_documents (collection_id, document_type);

ALTER TABLE rag_document_versions
    ADD COLUMN IF NOT EXISTS jsonb_payload_snapshot JSONB;

DO $$
DECLARE
    duplicate_versions BIGINT;
    orphan_versions BIGINT;
BEGIN
    SELECT COUNT(*) INTO duplicate_versions
    FROM (
        SELECT document_id, version_number
        FROM rag_document_versions
        GROUP BY document_id, version_number
        HAVING COUNT(*) > 1
    ) duplicates;

    IF duplicate_versions > 0 THEN
        RAISE EXCEPTION
            'Cannot add version-number uniqueness: % duplicate document/version groups exist',
            duplicate_versions;
    END IF;

    SELECT COUNT(*) INTO orphan_versions
    FROM rag_document_versions v
    LEFT JOIN rag_documents d ON d.id = v.document_id
    WHERE d.id IS NULL;

    IF orphan_versions > 0 THEN
        RAISE EXCEPTION
            'Cannot add document-version foreign key: % orphan version rows exist',
            orphan_versions;
    END IF;
END
$$;

ALTER TABLE rag_document_versions
    DROP CONSTRAINT IF EXISTS uk_doc_version_hash;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'uk_doc_version_number'
          AND conrelid = 'rag_document_versions'::regclass
    ) THEN
        ALTER TABLE rag_document_versions
            ADD CONSTRAINT uk_doc_version_number
            UNIQUE (document_id, version_number);
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'fk_doc_version_document'
          AND conrelid = 'rag_document_versions'::regclass
    ) THEN
        ALTER TABLE rag_document_versions
            ADD CONSTRAINT fk_doc_version_document
            FOREIGN KEY (document_id)
            REFERENCES rag_documents(id)
            ON DELETE CASCADE;
    END IF;
END
$$;

COMMENT ON COLUMN rag_documents.external_id IS
    'Caller-supplied stable identity for structured records';
COMMENT ON COLUMN rag_documents.jsonb_payload IS
    'Structured business payload; retrieval and embedding use content instead';
COMMENT ON COLUMN rag_document_versions.jsonb_payload_snapshot IS
    'JSONB payload snapshot for structured-record version auditing';
