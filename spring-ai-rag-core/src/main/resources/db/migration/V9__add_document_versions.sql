-- V9: 文档版本历史表
-- 记录文档内容变更历史，支持版本回溯和变更审计

CREATE TABLE IF NOT EXISTS rag_document_versions (
    id BIGSERIAL PRIMARY KEY,
    document_id BIGINT NOT NULL,
    version_number INTEGER NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    content_snapshot TEXT NOT NULL,
    size BIGINT,
    change_type VARCHAR(20) NOT NULL,  -- CREATE / UPDATE / EMBED
    change_description VARCHAR(500),
    metadata_snapshot JSONB,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    -- 同一文档 + 同一哈希 唯一约束（避免重复版本）
    CONSTRAINT uk_doc_version_hash UNIQUE (document_id, content_hash)
);

-- 索引：按文档查询版本历史
CREATE INDEX idx_doc_version_doc_id ON rag_document_versions (document_id);

-- 索引：按哈希查询（去重判断）
CREATE INDEX idx_doc_version_hash ON rag_document_versions (content_hash);

-- 索引：按时间查询
CREATE INDEX idx_doc_version_created ON rag_document_versions (created_at);

-- 注释
COMMENT ON TABLE rag_document_versions IS '文档版本历史表：每次内容变更自动记录快照';
COMMENT ON COLUMN rag_document_versions.change_type IS '变更类型：CREATE=首次创建, UPDATE=内容更新, EMBED=嵌入时间点';
COMMENT ON COLUMN rag_document_versions.content_snapshot IS '该版本的完整内容快照';
