-- V8: 添加 embedded_content_hash 字段用于嵌入缓存
-- 记录上次嵌入时的内容哈希，用于判断内容是否变更、是否需要重新嵌入

ALTER TABLE rag_documents ADD COLUMN embedded_content_hash VARCHAR(64);

COMMENT ON COLUMN rag_documents.embedded_content_hash IS '上次嵌入时的内容 SHA-256 哈希值，用于内容变更检测';
