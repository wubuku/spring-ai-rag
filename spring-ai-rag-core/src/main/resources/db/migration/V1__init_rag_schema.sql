-- ============================================================
-- V1：初始化通用 RAG 服务数据库结构
-- ============================================================

-- 1. 扩展
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS pg_jieba;

-- 2. 中文全文检索配置
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_ts_config WHERE cfgname = 'jiebacfg') THEN
        CREATE TEXT SEARCH CONFIGURATION jiebacfg (parser = jieba);
    END IF;
END
$$;

-- 3. 文档集合表（支持多知识库/多租户隔离）
CREATE TABLE IF NOT EXISTS rag_collection (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    embedding_model VARCHAR(100),
    dimensions INTEGER DEFAULT 1024,
    enabled BOOLEAN NOT NULL DEFAULT true,
    metadata JSONB,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP(6)
);

-- 4. 文档表
CREATE TABLE IF NOT EXISTS rag_documents (
    id BIGSERIAL PRIMARY KEY,
    collection_id BIGINT REFERENCES rag_collection(id),
    title VARCHAR(255) NOT NULL,
    source VARCHAR(255),
    content TEXT NOT NULL,
    metadata JSONB,
    enabled BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP(6),
    document_type VARCHAR(50),
    original_filename VARCHAR(255),
    content_hash VARCHAR(64),
    size BIGINT,
    processing_status VARCHAR(20) DEFAULT 'COMPLETED',
    processing_error TEXT
);

-- 5. 向量嵌入表
-- 注意：VECTOR 维度必须与嵌入模型输出维度一致（BGE-M3 = 1024）
CREATE TABLE IF NOT EXISTS rag_embeddings (
    id BIGSERIAL PRIMARY KEY,
    document_id BIGINT NOT NULL REFERENCES rag_documents(id),
    chunk_text TEXT NOT NULL,
    chunk_index INTEGER NOT NULL DEFAULT 0,
    embedding VECTOR(1024) NOT NULL,
    metadata JSONB,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    chunk_start_pos INTEGER,
    chunk_end_pos INTEGER
);

-- 6. 对话历史表（业务审计用；LLM 上下文由 spring_ai_chat_memory 管理）
CREATE TABLE IF NOT EXISTS rag_chat_history (
    id BIGSERIAL PRIMARY KEY,
    session_id VARCHAR(255) NOT NULL,
    user_message TEXT NOT NULL,
    ai_response TEXT,
    related_document_ids TEXT,
    metadata JSONB,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ============================================================
-- 索引
-- ============================================================

-- 集合表
CREATE INDEX IF NOT EXISTS idx_rag_col_enabled ON rag_collection (enabled);

-- 文档表
CREATE INDEX IF NOT EXISTS idx_rag_doc_created ON rag_documents (created_at);
CREATE INDEX IF NOT EXISTS idx_rag_doc_collection ON rag_documents (collection_id);
CREATE INDEX IF NOT EXISTS idx_rag_doc_hash ON rag_documents (content_hash);
CREATE INDEX IF NOT EXISTS idx_rag_doc_status ON rag_documents (processing_status);

-- 向量索引（HNSW）
CREATE INDEX IF NOT EXISTS idx_rag_emb_doc_id ON rag_embeddings (document_id);
CREATE INDEX IF NOT EXISTS idx_rag_emb_vector_hnsw ON rag_embeddings
    USING hnsw (embedding vector_cosine_ops) WITH (m='16', ef_construction='64');

-- 聊天历史表
CREATE INDEX IF NOT EXISTS idx_rag_chat_session ON rag_chat_history (session_id);
CREATE INDEX IF NOT EXISTS idx_rag_chat_created ON rag_chat_history (created_at);
