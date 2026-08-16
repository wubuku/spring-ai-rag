-- V30: stable external-document identity, source revision and tombstones.

ALTER TABLE rag_documents
    ADD COLUMN source_revision VARCHAR(255),
    ADD COLUMN source_deleted_at TIMESTAMP(6);

ALTER TABLE rag_document_versions
    ADD COLUMN source_revision_snapshot VARCHAR(255);

DO $$
DECLARE
    duplicate_count BIGINT;
    trim_chars TEXT;
BEGIN
    SELECT STRING_AGG(CHR(code), '' ORDER BY code)
    INTO trim_chars
    FROM GENERATE_SERIES(1, 32) AS codes(code);

    SELECT COUNT(*) INTO duplicate_count
    FROM (
        SELECT collection_id,
               BTRIM(external_id, trim_chars) AS normalized_external_id
        FROM rag_documents
        WHERE collection_id IS NOT NULL
          AND external_id IS NOT NULL
          AND BTRIM(external_id, trim_chars) <> ''
        GROUP BY collection_id, BTRIM(external_id, trim_chars)
        HAVING COUNT(*) > 1
    ) duplicates;

    IF duplicate_count > 0 THEN
        RAISE EXCEPTION
            'Cannot enforce normalized external document identity: % duplicate collection/external_id groups exist',
            duplicate_count;
    END IF;

    UPDATE rag_documents
    SET external_id = BTRIM(external_id, trim_chars)
    WHERE external_id IS NOT NULL
      AND external_id <> BTRIM(external_id, trim_chars);
END
$$;

DROP INDEX IF EXISTS uk_rag_doc_structured_identity;

CREATE UNIQUE INDEX uk_rag_doc_external_identity
    ON rag_documents (collection_id, external_id)
    WHERE collection_id IS NOT NULL
      AND external_id IS NOT NULL
      AND external_id <> '';

COMMENT ON COLUMN rag_documents.source_revision IS
    'Opaque caller-supplied source revision for external document synchronization';
COMMENT ON COLUMN rag_documents.source_deleted_at IS
    'Tombstone timestamp for source-managed deletion';
COMMENT ON COLUMN rag_document_versions.source_revision_snapshot IS
    'Source revision captured by a document version snapshot';
