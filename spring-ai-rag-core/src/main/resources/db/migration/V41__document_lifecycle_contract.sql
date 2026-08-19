-- V41: contract the document lifecycle schema after V40-compatible code is deployed.

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM rag_embedding_jobs
        WHERE status IN ('QUEUED', 'RUNNING')
          AND (
              request_generation <= 0
              OR document_kind IS NULL
              OR chunker_version IS NULL
              OR chunker_version = 'legacy-unknown'
          )
    ) THEN
        RAISE EXCEPTION
            'Cannot contract document lifecycle schema while legacy active jobs remain';
    END IF;
END
$$;

DROP INDEX IF EXISTS uk_rag_doc_external_identity;
DROP INDEX IF EXISTS uq_rag_embedding_job_active;

ALTER TABLE rag_embedding_jobs
    ALTER COLUMN document_kind SET NOT NULL,
    ALTER COLUMN chunker_version SET NOT NULL,
    ADD CONSTRAINT ck_rag_embedding_job_generation_positive
        CHECK (
            request_generation > 0
            OR status IN ('SUCCEEDED', 'FAILED', 'CANCELLED', 'STALE')
        ),
    ADD CONSTRAINT ck_rag_embedding_job_document_kind
        CHECK (document_kind IN ('TEXT', 'JSON_RECORD')),
    ADD CONSTRAINT ck_rag_embedding_job_chunker_version
        CHECK (chunker_version <> '');

CREATE UNIQUE INDEX IF NOT EXISTS uq_rag_embedding_job_active_generation
    ON rag_embedding_jobs (
        document_id,
        embedding_profile_id,
        request_generation
    )
    WHERE status IN ('QUEUED', 'RUNNING');
