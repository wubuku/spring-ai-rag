-- ============================================================
-- V10：全文检索索引（可选，按扩展可用性创建）
-- ============================================================

-- pg_trgm GIN 索引：加速 similarity() 查询
-- 对 chunk_text 列创建 trigram 索引，避免全文检索时全表扫描
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'pg_trgm') THEN
        IF NOT EXISTS (SELECT 1 FROM pg_indexes WHERE indexname = 'idx_rag_emb_chunk_trgm') THEN
            CREATE INDEX idx_rag_emb_chunk_trgm ON rag_embeddings
                USING gin (chunk_text gin_trgm_ops);
            RAISE NOTICE 'Created pg_trgm GIN index on rag_embeddings.chunk_text';
        END IF;
    ELSE
        RAISE NOTICE 'pg_trgm not available, skipping trigram index';
    END IF;
END
$$;

-- pg_jieba 函数索引：加速 to_tsvector('jiebacfg') 查询
-- 使用表达式索引，PostgreSQL 在查询匹配时自动使用
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'pg_jieba') THEN
        IF NOT EXISTS (SELECT 1 FROM pg_indexes WHERE indexname = 'idx_rag_emb_chunk_jieba') THEN
            CREATE INDEX idx_rag_emb_chunk_jieba ON rag_embeddings
                USING gin (to_tsvector('jiebacfg', chunk_text));
            RAISE NOTICE 'Created pg_jieba GIN index on rag_embeddings.chunk_text';
        END IF;
    ELSE
        RAISE NOTICE 'pg_jieba not available, skipping jieba index';
    END IF;
END
$$;
