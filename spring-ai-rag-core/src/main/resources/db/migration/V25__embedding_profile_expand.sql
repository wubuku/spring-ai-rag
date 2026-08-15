-- V25: 引入 Embedding Profile、Profile 级文档状态和固定维度向量列。
-- 旧 embedding 列保留用于兼容窗口双写和旧应用回滚。

CREATE TABLE IF NOT EXISTS rag_embedding_profiles (
    id BIGSERIAL PRIMARY KEY,
    profile_key VARCHAR(128) NOT NULL UNIQUE,
    provider VARCHAR(64) NOT NULL,
    model_name VARCHAR(255) NOT NULL,
    model_revision VARCHAR(128) NOT NULL,
    dimensions INTEGER NOT NULL CHECK (dimensions > 0),
    distance_metric VARCHAR(32) NOT NULL,
    normalization VARCHAR(32) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_rag_embedding_profile_identity UNIQUE (
        provider,
        model_name,
        model_revision,
        dimensions,
        distance_metric,
        normalization
    )
);

ALTER TABLE rag_embeddings
    ADD COLUMN IF NOT EXISTS embedding_profile_id BIGINT,
    ADD COLUMN IF NOT EXISTS embedding_1024 VECTOR(1024);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'fk_rag_embedding_profile'
    ) THEN
        ALTER TABLE rag_embeddings
            ADD CONSTRAINT fk_rag_embedding_profile
            FOREIGN KEY (embedding_profile_id)
            REFERENCES rag_embedding_profiles(id);
    END IF;
END
$$;

CREATE UNIQUE INDEX IF NOT EXISTS uq_rag_embedding_chunk_profile
    ON rag_embeddings(document_id, chunk_index, embedding_profile_id)
    WHERE embedding_profile_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_rag_embedding_profile_id
    ON rag_embeddings(embedding_profile_id);

CREATE TABLE IF NOT EXISTS rag_document_embedding_state (
    document_id BIGINT NOT NULL REFERENCES rag_documents(id) ON DELETE CASCADE,
    embedding_profile_id BIGINT NOT NULL REFERENCES rag_embedding_profiles(id),
    content_hash VARCHAR(64) NOT NULL,
    chunker_version VARCHAR(128) NOT NULL,
    status VARCHAR(20) NOT NULL,
    chunk_count INTEGER NOT NULL DEFAULT 0,
    processing_error TEXT,
    completed_at TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (document_id, embedding_profile_id),
    CONSTRAINT ck_rag_document_embedding_state_status
        CHECK (status IN ('PROCESSING', 'COMPLETED', 'FAILED'))
);

CREATE INDEX IF NOT EXISTS idx_rag_document_embedding_state_profile_status
    ON rag_document_embedding_state(embedding_profile_id, status);

COMMENT ON TABLE rag_embedding_profiles IS
    '不可变的嵌入模型空间身份；profile_key 用于在线活动 Profile 选择';
COMMENT ON COLUMN rag_embeddings.embedding_profile_id IS
    '生成本行向量的 Embedding Profile；迁移窗口内 Legacy 行可暂时为 NULL';
COMMENT ON COLUMN rag_embeddings.embedding_1024 IS
    '固定 1024 维向量列；当前用于 BGE-M3 等 1024 维 Profile';
COMMENT ON TABLE rag_document_embedding_state IS
    '按 document + embedding profile 保存缓存、覆盖率和处理状态';
