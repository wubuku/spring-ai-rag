-- V60: backfill and fence the derivation generation of every embedding state row.
--
-- V40 added rag_document_embedding_state.request_generation with
-- `BIGINT NOT NULL DEFAULT 0` and no backfill, so every state row that existed
-- at that moment has been sitting at 0 ever since.  V41 then put a positive
-- generation contract on rag_embedding_jobs and deliberately left the state
-- table at `>= 0`, which is the asymmetry this migration closes.
--
-- The application side of the same gap was live, not historical:
-- EmbeddingPersistenceService.replace / recordFailureIfNoCompleted and
-- LegacyEmbeddingMigrationService all wrote the state row without naming
-- request_generation, so an INSERT took the column default of 0.  Since
-- DerivationIntegrityRepository requires `vector_generation > 0` before it will
-- call a vector fresh, such a row was classified CORRUPT and the public
-- lifecycle reported it as `embeddingStatus=FAILED` with a null error — a
-- document with a valid vector, a matching content hash and no failure
-- anywhere.  Measured on a live PostgreSQL instance: 69 of 82 state rows, and
-- `run-retrieval-regression.sh` aborted on its third fixture with
-- `status=FAILED error=None`.
--
-- The three writers now name the column, so the only remaining source of 0 is
-- the rows V40 stranded.  The order below is load-bearing: the backfill has to
-- run before the constraint is added, or this migration fails outright on any
-- database that predates V40 — which is the behaviour we want, because it means
-- the repair can never be skipped.

UPDATE rag_document_embedding_state
SET request_generation = 1,
    updated_at = CURRENT_TIMESTAMP
WHERE request_generation = 0;

-- 1 is the first generation; the only reachable value before this migration was
-- 0, and a row that exists at all records at least one derivation request.
-- The default moves with the constraint so that an INSERT which forgets the
-- column fails the check below instead of quietly producing a row the integrity
-- repository will call corrupt forever.
ALTER TABLE rag_document_embedding_state
    ALTER COLUMN request_generation SET DEFAULT 1;

ALTER TABLE rag_document_embedding_state
    DROP CONSTRAINT IF EXISTS ck_rag_document_embedding_state_generation;

ALTER TABLE rag_document_embedding_state
    ADD CONSTRAINT ck_rag_document_embedding_state_generation
        CHECK (request_generation > 0);

COMMENT ON COLUMN rag_document_embedding_state.request_generation IS
    'Derivation generation for this document and embedding profile; at least 1, because a row exists only after a derivation request';
