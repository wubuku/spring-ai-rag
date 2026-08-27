-- V56: protected Collection content purge, retirement tombstones, and content references.

ALTER TABLE rag_collection
    ADD COLUMN IF NOT EXISTS purged_at TIMESTAMP(6),
    ADD COLUMN IF NOT EXISTS chat_commit_fence_version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE rag_collection
    ADD CONSTRAINT ck_rag_collection_purged_deleted
        CHECK (purged_at IS NULL OR deleted = TRUE);

ALTER TABLE rag_chat_history
    ADD COLUMN IF NOT EXISTS content_reference_index_complete BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE rag_user_feedback
    ADD COLUMN IF NOT EXISTS content_reference_index_complete BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE rag_chat_history_source_document (
    history_id BIGINT NOT NULL REFERENCES rag_chat_history(id) ON DELETE CASCADE,
    document_id BIGINT NOT NULL,
    PRIMARY KEY (history_id, document_id),
    CONSTRAINT ck_rag_chat_history_source_document_id CHECK (document_id > 0)
);

CREATE INDEX idx_rag_chat_history_source_document_document
    ON rag_chat_history_source_document(document_id, history_id);

CREATE TABLE rag_user_feedback_document (
    feedback_id BIGINT NOT NULL REFERENCES rag_user_feedback(id) ON DELETE CASCADE,
    document_id BIGINT NOT NULL,
    PRIMARY KEY (feedback_id, document_id),
    CONSTRAINT ck_rag_user_feedback_document_id CHECK (document_id > 0)
);

CREATE INDEX idx_rag_user_feedback_document_document
    ON rag_user_feedback_document(document_id, feedback_id);

CREATE OR REPLACE FUNCTION rag_v56_try_jsonb(value TEXT)
RETURNS JSONB
LANGUAGE plpgsql
IMMUTABLE
AS $$
BEGIN
    IF value IS NULL OR btrim(value) = '' THEN
        RETURN NULL;
    END IF;
    RETURN value::jsonb;
EXCEPTION WHEN OTHERS THEN
    RETURN NULL;
END;
$$;

WITH parsed AS (
    SELECT id,
           sources,
           rag_v56_try_jsonb(related_document_ids) AS related_ids,
           related_document_ids
    FROM rag_chat_history
)
UPDATE rag_chat_history history
SET content_reference_index_complete =
    (parsed.sources IS NULL OR (
        jsonb_typeof(parsed.sources) = 'array'
        AND NOT EXISTS (
            SELECT 1
            FROM jsonb_array_elements(parsed.sources) source
            WHERE jsonb_typeof(source) <> 'object'
               OR (
                    source ? 'documentId'
                    AND jsonb_typeof(source -> 'documentId') NOT IN ('string', 'number', 'null')
               )
        )
    ))
    AND (
        parsed.related_document_ids IS NULL
        OR btrim(parsed.related_document_ids) = ''
        OR (
            parsed.related_ids IS NOT NULL
            AND jsonb_typeof(parsed.related_ids) = 'array'
            AND NOT EXISTS (
                SELECT 1
                FROM jsonb_array_elements(parsed.related_ids) item
                WHERE jsonb_typeof(item) NOT IN ('string', 'number')
            )
        )
    )
FROM parsed
WHERE parsed.id = history.id
  AND history.session_id ~ '^[A-Za-z0-9._~-]{1,36}$';

INSERT INTO rag_chat_history_source_document(history_id, document_id)
SELECT DISTINCT candidate.history_id, candidate.document_id
FROM (
    SELECT history.id AS history_id,
           CASE
               WHEN source_value.value ~ '^[1-9][0-9]{0,18}$'
                    AND source_value.value::numeric <= 9223372036854775807
               THEN source_value.value::bigint
           END AS document_id
    FROM rag_chat_history history
    CROSS JOIN LATERAL jsonb_array_elements(
        CASE WHEN jsonb_typeof(history.sources) = 'array'
             THEN history.sources ELSE '[]'::jsonb END
    ) source
    CROSS JOIN LATERAL (
        SELECT source ->> 'documentId' AS value
    ) source_value
    UNION ALL
    SELECT history.id AS history_id,
           CASE
               WHEN related_value.value ~ '^[1-9][0-9]{0,18}$'
                    AND related_value.value::numeric <= 9223372036854775807
               THEN related_value.value::bigint
           END AS document_id
    FROM rag_chat_history history
    CROSS JOIN LATERAL jsonb_array_elements(
        CASE
            WHEN jsonb_typeof(rag_v56_try_jsonb(history.related_document_ids)) = 'array'
            THEN rag_v56_try_jsonb(history.related_document_ids)
            ELSE '[]'::jsonb
        END
    ) item
    CROSS JOIN LATERAL (
        SELECT trim(BOTH '"' FROM item::text) AS value
    ) related_value
) candidate
WHERE candidate.document_id IS NOT NULL
ON CONFLICT DO NOTHING;

WITH parsed AS (
    SELECT id,
           rag_v56_try_jsonb(retrieved_document_ids) AS retrieved_ids,
           rag_v56_try_jsonb(selected_document_ids) AS selected_ids,
           retrieved_document_ids,
           selected_document_ids
    FROM rag_user_feedback
)
UPDATE rag_user_feedback feedback
SET content_reference_index_complete =
    (
        parsed.retrieved_document_ids IS NULL
        OR btrim(parsed.retrieved_document_ids) = ''
        OR (
            parsed.retrieved_ids IS NOT NULL
            AND jsonb_typeof(parsed.retrieved_ids) = 'array'
            AND NOT EXISTS (
                SELECT 1 FROM jsonb_array_elements(parsed.retrieved_ids) item
                WHERE jsonb_typeof(item) NOT IN ('string', 'number')
            )
        )
    )
    AND (
        parsed.selected_document_ids IS NULL
        OR btrim(parsed.selected_document_ids) = ''
        OR (
            parsed.selected_ids IS NOT NULL
            AND jsonb_typeof(parsed.selected_ids) = 'array'
            AND NOT EXISTS (
                SELECT 1 FROM jsonb_array_elements(parsed.selected_ids) item
                WHERE jsonb_typeof(item) NOT IN ('string', 'number')
            )
        )
    )
FROM parsed
WHERE parsed.id = feedback.id;

INSERT INTO rag_user_feedback_document(feedback_id, document_id)
SELECT DISTINCT candidate.feedback_id, candidate.document_id
FROM (
    SELECT feedback.id AS feedback_id,
           CASE
               WHEN retrieved_value.value ~ '^[1-9][0-9]{0,18}$'
                    AND retrieved_value.value::numeric <= 9223372036854775807
               THEN retrieved_value.value::bigint
           END AS document_id
    FROM rag_user_feedback feedback
    CROSS JOIN LATERAL jsonb_array_elements(
        CASE
            WHEN jsonb_typeof(rag_v56_try_jsonb(feedback.retrieved_document_ids)) = 'array'
            THEN rag_v56_try_jsonb(feedback.retrieved_document_ids)
            ELSE '[]'::jsonb
        END
    ) item
    CROSS JOIN LATERAL (
        SELECT trim(BOTH '"' FROM item::text) AS value
    ) retrieved_value
    UNION ALL
    SELECT feedback.id AS feedback_id,
           CASE
               WHEN selected_value.value ~ '^[1-9][0-9]{0,18}$'
                    AND selected_value.value::numeric <= 9223372036854775807
               THEN selected_value.value::bigint
           END AS document_id
    FROM rag_user_feedback feedback
    CROSS JOIN LATERAL jsonb_array_elements(
        CASE
            WHEN jsonb_typeof(rag_v56_try_jsonb(feedback.selected_document_ids)) = 'array'
            THEN rag_v56_try_jsonb(feedback.selected_document_ids)
            ELSE '[]'::jsonb
        END
    ) item
    CROSS JOIN LATERAL (
        SELECT trim(BOTH '"' FROM item::text) AS value
    ) selected_value
) candidate
WHERE candidate.document_id IS NOT NULL
ON CONFLICT DO NOTHING;

DROP FUNCTION rag_v56_try_jsonb(TEXT);

CREATE TABLE rag_collection_purge_preview (
    id UUID PRIMARY KEY,
    owner_principal_id VARCHAR(128) NOT NULL,
    collection_id BIGINT NOT NULL REFERENCES rag_collection(id),
    collection_key VARCHAR(128) NOT NULL,
    collection_version BIGINT NOT NULL,
    chat_commit_fence_version BIGINT NOT NULL,
    confirmation_token_hash CHAR(64) NOT NULL,
    fingerprint CHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    apply_lease_owner_hash CHAR(64),
    apply_lease_expires_at TIMESTAMPTZ,
    document_count BIGINT NOT NULL,
    external_document_count BIGINT NOT NULL,
    local_document_count BIGINT NOT NULL,
    embedding_count BIGINT NOT NULL,
    embedding_job_count BIGINT NOT NULL,
    version_count BIGINT NOT NULL,
    keyword_chunk_count BIGINT NOT NULL,
    repair_preview_count BIGINT NOT NULL,
    repair_item_count BIGINT NOT NULL,
    derived_row_count BIGINT NOT NULL,
    document_idempotency_operation_count BIGINT NOT NULL,
    feedback_count BIGINT NOT NULL,
    feedback_document_reference_count BIGINT NOT NULL,
    document_audit_count BIGINT NOT NULL,
    collection_audit_count BIGINT NOT NULL,
    relocation_marker_count BIGINT NOT NULL,
    affected_chat_session_count BIGINT NOT NULL,
    chat_history_count BIGINT NOT NULL,
    chat_memory_count BIGINT NOT NULL,
    chat_summary_count BIGINT NOT NULL,
    chat_turn_operation_count BIGINT NOT NULL,
    active_sync_run_count BIGINT NOT NULL,
    active_derivation_repair_count BIGINT NOT NULL,
    active_chat_session_count BIGINT NOT NULL,
    unindexed_chat_reference_count BIGINT NOT NULL,
    unindexed_feedback_reference_count BIGINT NOT NULL,
    preview_deadline TIMESTAMPTZ NOT NULL,
    operation_deadline TIMESTAMPTZ NOT NULL,
    result_expires_at TIMESTAMPTZ NOT NULL,
    result_payload JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    CONSTRAINT ck_rag_collection_purge_preview_status
        CHECK (status IN ('PREVIEWED', 'APPLYING', 'COMPLETED', 'EXPIRED')),
    CONSTRAINT ck_rag_collection_purge_preview_deadlines
        CHECK (preview_deadline <= operation_deadline
            AND operation_deadline <= result_expires_at),
    CONSTRAINT ck_rag_collection_purge_preview_counts
        CHECK (
            document_count >= 0 AND external_document_count >= 0
            AND local_document_count >= 0 AND derived_row_count >= 0
            AND affected_chat_session_count >= 0
        ),
    CONSTRAINT ck_rag_collection_purge_preview_result
        CHECK (
            (status = 'COMPLETED' AND result_payload IS NOT NULL AND completed_at IS NOT NULL)
            OR (status = 'EXPIRED' AND completed_at IS NOT NULL)
            OR (status IN ('PREVIEWED', 'APPLYING') AND result_payload IS NULL)
        )
);

CREATE INDEX idx_rag_collection_purge_preview_owner_status
    ON rag_collection_purge_preview(owner_principal_id, status, created_at DESC);

CREATE INDEX idx_rag_collection_purge_preview_cleanup
    ON rag_collection_purge_preview(status, operation_deadline, result_expires_at);

COMMENT ON TABLE rag_collection_purge_preview IS
    'Body-free, owner-scoped protected Collection purge previews and replay envelopes';

-- V54 keeps operation names in database CHECK constraints. Every additive
-- IntegrationOperation introduced later must extend both rollup tables in the
-- same migration so best-effort observation does not silently drop new routes.
ALTER TABLE rag_api_operation_hourly
    DROP CONSTRAINT ck_rag_api_operation_hourly_operation;

ALTER TABLE rag_api_operation_hourly
    ADD CONSTRAINT ck_rag_api_operation_hourly_operation
        CHECK (operation IN (
            'INTEGRATION_CAPABILITIES', 'CURRENT_PRINCIPAL',
            'COLLECTION_LOOKUP', 'COLLECTION_READINESS',
            'JSON_RECORD_UPSERT', 'JSON_RECORD_BATCH_UPSERT',
            'JSON_RECORD_SEARCH', 'JSON_RECORD_LOOKUP',
            'JSON_RECORD_TOMBSTONE', 'SYNC_RUN_BEGIN',
            'SYNC_RUN_BATCH_UPSERT', 'SYNC_RUN_PREVIEW',
            'SYNC_RUN_COMPLETE', 'SYNC_RUN_ABORT', 'SYNC_RUN_GET',
            'SYNC_RUN_ITEMS', 'SYNC_RUN_LIST',
            'COLLECTION_PURGE_PREVIEW', 'COLLECTION_PURGE_APPLY'));

ALTER TABLE rag_api_collection_operation_hourly
    DROP CONSTRAINT ck_rag_api_collection_operation_hourly_operation;

ALTER TABLE rag_api_collection_operation_hourly
    ADD CONSTRAINT ck_rag_api_collection_operation_hourly_operation
        CHECK (operation IN (
            'INTEGRATION_CAPABILITIES', 'CURRENT_PRINCIPAL',
            'COLLECTION_LOOKUP', 'COLLECTION_READINESS',
            'JSON_RECORD_UPSERT', 'JSON_RECORD_BATCH_UPSERT',
            'JSON_RECORD_SEARCH', 'JSON_RECORD_LOOKUP',
            'JSON_RECORD_TOMBSTONE', 'SYNC_RUN_BEGIN',
            'SYNC_RUN_BATCH_UPSERT', 'SYNC_RUN_PREVIEW',
            'SYNC_RUN_COMPLETE', 'SYNC_RUN_ABORT', 'SYNC_RUN_GET',
            'SYNC_RUN_ITEMS', 'SYNC_RUN_LIST',
            'COLLECTION_PURGE_PREVIEW', 'COLLECTION_PURGE_APPLY'));
