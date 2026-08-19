-- V43: decouple the durable local keyword index from remote embeddings.
--
-- rag_embeddings remains the vector source of truth.  The new local tables
-- own keyword-search chunks and their independent freshness generation.

CREATE TABLE rag_document_chunks (
    id BIGSERIAL PRIMARY KEY,
    document_id BIGINT NOT NULL REFERENCES rag_documents(id) ON DELETE CASCADE,
    local_index_generation BIGINT NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    chunker_version VARCHAR(128) NOT NULL,
    chunk_text TEXT NOT NULL,
    chunk_index INTEGER NOT NULL,
    chunk_start_pos INTEGER NOT NULL,
    chunk_end_pos INTEGER NOT NULL,
    metadata JSONB,
    search_vector_en TSVECTOR
        GENERATED ALWAYS AS (to_tsvector('english', chunk_text)) STORED,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_rag_document_chunk_generation_index
        UNIQUE (document_id, local_index_generation, chunk_index),
    CONSTRAINT ck_rag_document_chunk_generation
        CHECK (local_index_generation > 0),
    CONSTRAINT ck_rag_document_chunk_index
        CHECK (chunk_index >= 0),
    CONSTRAINT ck_rag_document_chunk_text
        CHECK (BTRIM(chunk_text) <> ''),
    CONSTRAINT ck_rag_document_chunk_positions
        CHECK (
            chunk_start_pos >= 0
            AND chunk_end_pos >= chunk_start_pos
        ),
    CONSTRAINT ck_rag_document_chunk_hash
        CHECK (content_hash ~ '^[0-9a-fA-F]{64}$')
);

CREATE INDEX idx_rag_document_chunks_document_generation
    ON rag_document_chunks(document_id, local_index_generation, chunk_index);

CREATE INDEX idx_rag_document_chunks_search_vector_en
    ON rag_document_chunks USING gin(search_vector_en);

CREATE TABLE rag_document_local_index_state (
    document_id BIGINT PRIMARY KEY
        REFERENCES rag_documents(id) ON DELETE CASCADE,
    local_index_status VARCHAR(20) NOT NULL DEFAULT 'NOT_REQUESTED',
    content_hash VARCHAR(64),
    chunker_version VARCHAR(128),
    local_index_generation BIGINT NOT NULL DEFAULT 0,
    chunk_count INTEGER NOT NULL DEFAULT 0,
    processing_error VARCHAR(500),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_rag_document_local_index_status
        CHECK (local_index_status IN ('READY', 'FAILED', 'NOT_REQUESTED')),
    CONSTRAINT ck_rag_document_local_index_generation
        CHECK (local_index_generation >= 0),
    CONSTRAINT ck_rag_document_local_index_chunk_count
        CHECK (chunk_count >= 0),
    CONSTRAINT ck_rag_document_local_index_ready
        CHECK (
            local_index_status <> 'READY'
            OR (
                content_hash IS NOT NULL
                AND chunker_version IS NOT NULL
                AND chunk_count > 0
                AND local_index_generation > 0
            )
        )
);

CREATE INDEX idx_rag_document_local_index_status
    ON rag_document_local_index_state(local_index_status, updated_at);

COMMENT ON TABLE rag_document_chunks IS
    'Document-local keyword chunks, independent from any remote embedding profile';
COMMENT ON TABLE rag_document_local_index_state IS
    'Current local keyword-index generation and lifecycle state per document';
COMMENT ON COLUMN rag_document_chunks.local_index_generation IS
    'Independent local derivation generation; never use embedding request_generation here';
COMMENT ON COLUMN rag_document_local_index_state.local_index_generation IS
    'Generation selected by keyword retrieval for this document';

-- Start every existing document with an explicit local state.  The backfill
-- below upgrades only complete, current embedding groups to READY.
INSERT INTO rag_document_local_index_state (
    document_id,
    local_index_status,
    local_index_generation,
    chunk_count,
    updated_at
)
SELECT id, 'NOT_REQUESTED', 0, 0, CURRENT_TIMESTAMP
FROM rag_documents;

-- Pick one complete current embedding group per document.  A previous
-- migration may have left several profiles; local chunks are profile-neutral,
-- so any complete current group is a valid source for the compatibility
-- backfill.  The runtime descriptor check will rebuild legacy chunker output.
CREATE TEMP TABLE rag_v43_local_sources ON COMMIT DROP AS
SELECT DISTINCT ON (state.document_id)
       state.document_id,
       state.embedding_profile_id,
       state.content_hash,
       state.chunker_version,
       state.chunk_count
FROM rag_document_embedding_state state
JOIN rag_documents document
  ON document.id = state.document_id
WHERE document.enabled = true
  AND state.status = 'COMPLETED'
  AND state.content_hash = document.content_hash
  AND state.chunk_count > 0
  AND (
      SELECT COUNT(*)
      FROM rag_embeddings embedding
      WHERE embedding.document_id = state.document_id
        AND embedding.embedding_profile_id = state.embedding_profile_id
  ) = state.chunk_count
  AND (
      SELECT MIN(embedding.chunk_index)
      FROM rag_embeddings embedding
      WHERE embedding.document_id = state.document_id
        AND embedding.embedding_profile_id = state.embedding_profile_id
  ) = 0
  AND (
      SELECT MAX(embedding.chunk_index)
      FROM rag_embeddings embedding
      WHERE embedding.document_id = state.document_id
        AND embedding.embedding_profile_id = state.embedding_profile_id
  ) = state.chunk_count - 1
ORDER BY state.document_id, state.embedding_profile_id;

INSERT INTO rag_document_chunks (
    document_id,
    local_index_generation,
    content_hash,
    chunker_version,
    chunk_text,
    chunk_index,
    chunk_start_pos,
    chunk_end_pos,
    metadata,
    created_at
)
SELECT source.document_id,
       1,
       source.content_hash,
       source.chunker_version,
       embedding.chunk_text,
       embedding.chunk_index,
       COALESCE(embedding.chunk_start_pos, 0),
       COALESCE(
           embedding.chunk_end_pos,
           COALESCE(embedding.chunk_start_pos, 0)
               + LENGTH(embedding.chunk_text)
       ),
       embedding.metadata,
       CURRENT_TIMESTAMP
FROM rag_v43_local_sources source
JOIN rag_embeddings embedding
  ON embedding.document_id = source.document_id
 AND embedding.embedding_profile_id = source.embedding_profile_id;

UPDATE rag_document_local_index_state state
SET local_index_status = 'READY',
    content_hash = source.content_hash,
    chunker_version = source.chunker_version,
    local_index_generation = 1,
    chunk_count = source.chunk_count,
    processing_error = NULL,
    updated_at = CURRENT_TIMESTAMP
FROM rag_v43_local_sources source
WHERE state.document_id = source.document_id
  AND (
      SELECT COUNT(*)
      FROM rag_document_chunks chunk
      WHERE chunk.document_id = source.document_id
        AND chunk.local_index_generation = 1
  ) = source.chunk_count;

-- Optional indexes are created only when their database capability exists.
-- The application remains capable of using the English generated-vector path
-- when neither optional extension is installed.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM pg_extension WHERE extname = 'pg_trgm'
    ) THEN
        EXECUTE 'CREATE INDEX idx_rag_document_chunks_text_trgm
                 ON rag_document_chunks USING gin (chunk_text gin_trgm_ops)';
    END IF;

    IF EXISTS (
        SELECT 1 FROM pg_ts_config WHERE cfgname = 'jiebacfg'
    ) THEN
        EXECUTE 'CREATE INDEX idx_rag_document_chunks_search_vector_zh
                 ON rag_document_chunks USING gin
                 (to_tsvector(''jiebacfg'', chunk_text))';
    END IF;
END
$$;
