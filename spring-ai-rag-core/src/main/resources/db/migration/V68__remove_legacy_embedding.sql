SET LOCAL lock_timeout = '5s';

UPDATE rag_embedding_backfill_control
SET status = 'PENDING',
    updated_at = CURRENT_TIMESTAMP
WHERE migration_key = 'remove-legacy-embedding-v1';

ALTER TABLE rag_embeddings
    DROP COLUMN embedding;
