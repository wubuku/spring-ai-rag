-- ================================================================
-- V16: 添加 trigram 模糊搜索索引
-- ================================================================
-- 目标：支持基于字符相似度的模糊搜索
--
-- 说明：
--   - trigram 索引不需要 GENERATED 列
--   - 直接在 chunk_text 列上创建 gin_trgm_ops 索引
--   - 适合：短词、部分匹配、轻微拼写错误
--   - 降级策略：FTS 不可用时的兜底方案

-- 1. 只有在 pg_trgm 扩展已安装时才创建 trigram GIN 索引
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'pg_trgm') THEN
        CREATE INDEX IF NOT EXISTS idx_rag_embeddings_trgm 
        ON rag_embeddings USING gin (chunk_text gin_trgm_ops);
        RAISE NOTICE 'Created trigram GIN index idx_rag_embeddings_trgm';
    ELSE
        RAISE NOTICE 'pg_trgm not installed, skipping trigram index creation';
    END IF;
END $$;

-- 2. 验证 trigram 索引是否存在
DO $$
DECLARE
    v_exists BOOLEAN;
BEGIN
    SELECT EXISTS (
        SELECT 1 FROM pg_indexes 
        WHERE tablename = 'rag_embeddings' 
        AND indexdef ILIKE '%gin_trgm_ops%'
    ) INTO v_exists;
    RAISE NOTICE 'Trigram index exists: %', v_exists;
END $$;

-- 3. 添加注释
COMMENT ON INDEX idx_rag_embeddings_trgm IS 'Trigram GIN index for fuzzy text search using pg_trgm';
