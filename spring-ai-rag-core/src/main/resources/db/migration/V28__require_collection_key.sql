-- V28: contract phase for Collection keys.

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
        char_length(collection_key) BETWEEN 1 AND 128
        AND collection_key !~ '[^!-~]'
    );

ALTER TABLE rag_collection
    ALTER COLUMN collection_key SET NOT NULL;
