-- V27: add the externally supplied, immutable Collection business key.
--
-- This migration is intentionally expand-compatible: the column is initially
-- nullable so an older writer can finish draining during a rolling rollout.

ALTER TABLE rag_collection
    ADD COLUMN IF NOT EXISTS collection_key VARCHAR(128) COLLATE "C";

DO $$
DECLARE
    collection_row RECORD;
    candidate TEXT;
    suffix INTEGER;
BEGIN
    FOR collection_row IN
        SELECT id
        FROM rag_collection
        WHERE collection_key IS NULL
        ORDER BY id
    LOOP
        candidate := 'legacy-collection-' || collection_row.id;
        suffix := 0;
        WHILE EXISTS (
            SELECT 1 FROM rag_collection WHERE collection_key = candidate
        ) LOOP
            suffix := suffix + 1;
            candidate := 'legacy-collection-' || collection_row.id || '-' || suffix;
        END LOOP;
        UPDATE rag_collection
        SET collection_key = candidate
        WHERE id = collection_row.id;
    END LOOP;
END
$$;

ALTER TABLE rag_collection
    DROP CONSTRAINT IF EXISTS ck_rag_collection_collection_key_ascii;

ALTER TABLE rag_collection
    ADD CONSTRAINT ck_rag_collection_collection_key_ascii
    CHECK (
        collection_key IS NULL
        OR (
            char_length(collection_key) BETWEEN 1 AND 128
            AND collection_key !~ '[^!-~]'
        )
    );

ALTER TABLE rag_collection
    ADD CONSTRAINT uk_rag_collection_collection_key UNIQUE (collection_key);

CREATE OR REPLACE FUNCTION fn_rag_collection_key_immutable()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    -- Expand/contract rollout may backfill rows inserted by an older writer
    -- while this column was nullable. Once a key exists it is immutable.
    IF OLD.collection_key IS NOT NULL
            AND NEW.collection_key IS DISTINCT FROM OLD.collection_key THEN
        RAISE EXCEPTION 'collection_key is immutable'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_rag_collection_key_immutable ON rag_collection;

CREATE TRIGGER trg_rag_collection_key_immutable
BEFORE UPDATE OF collection_key ON rag_collection
FOR EACH ROW
EXECUTE FUNCTION fn_rag_collection_key_immutable();
