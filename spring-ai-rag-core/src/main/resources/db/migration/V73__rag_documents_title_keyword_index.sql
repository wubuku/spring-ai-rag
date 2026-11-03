-- ================================================================
-- V73: rag_documents 标题关键词检索 trigram 索引
-- ================================================================
-- 公开文档列表的 keyword 过滤是
--   LOWER(title) LIKE LOWER(CONCAT('%', :keyword, '%'))
-- 即 %keyword% 形态；没有索引时它在 rag_documents 上做全表扫描。
--
-- 处理方式与 V16（rag_embeddings.chunk_text）保持一致：
--   - 只在 pg_trgm 已安装时创建索引；
--   - 未安装时只记 NOTICE，迁移不失败，关键词检索继续顺序扫描；
--   - 不在迁移里强行 CREATE EXTENSION（部分部署没有该扩展文件，
--     强行创建会让启动/迁移硬失败）。
--
-- 索引必须建在 LOWER(title) 表达式上，因为查询条件也是 LOWER(title)。
-- 建在 title 上的普通 gin_trgm_ops 索引不会被该表达式命中。

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'pg_trgm') THEN
        CREATE INDEX IF NOT EXISTS idx_rag_documents_title_trgm
        ON rag_documents USING gin (lower(title) gin_trgm_ops);
        COMMENT ON INDEX idx_rag_documents_title_trgm
            IS 'Trigram GIN index for public document-list title keyword search';
        RAISE NOTICE 'Created trigram GIN index idx_rag_documents_title_trgm';
    ELSE
        RAISE NOTICE 'pg_trgm not installed, skipping rag_documents trigram index; title keyword search falls back to sequential scan';
    END IF;
END $$;

-- 记录最终状态，便于部署日志确认是命中还是降级。
DO $$
DECLARE
    v_exists BOOLEAN;
BEGIN
    SELECT EXISTS (
        SELECT 1 FROM pg_indexes
        WHERE tablename = 'rag_documents'
          AND indexname = 'idx_rag_documents_title_trgm'
    ) INTO v_exists;
    RAISE NOTICE 'rag_documents title trigram index exists: %', v_exists;
END $$;
