-- ================================================================
-- V15: 添加全文检索 tsvector GENERATED 列和 GIN 索引
-- ================================================================
-- 目标：支持高效的中文和英文全文检索
--
-- 生成的 tsvector 列：
--   - search_vector_zh: 使用 jieba 分词（需要 pg_jieba 扩展）
--   - search_vector_en: 使用内置 english 配置
--
-- 索引：
--   - idx_rag_embeddings_search_vector_zh: search_vector_zh 的 GIN 索引
--   - idx_rag_embeddings_search_vector_en: search_vector_en 的 GIN 索引
--
-- 注意：
--   - GENERATED 列在 INSERT 时自动计算
--   - 对于已有数据，GENERATED 列初始为 NULL
--   - 已有数据可以通过重新插入或更新来触发 GENERATED 列计算
--   - 新的插入/更新会自动计算 GENERATED 列

-- 1. 添加中文 tsvector 列（使用 pg_jieba 分词配置）
--    注意：如果 pg_jieba 未安装，列会创建但 search_vector_zh 为 NULL
ALTER TABLE rag_embeddings 
ADD COLUMN search_vector_zh tsvector
GENERATED ALWAYS AS (to_tsvector('jiebacfg', chunk_text)) STORED;

-- 2. 添加英文 tsvector 列（使用内置 english 配置，始终有效）
ALTER TABLE rag_embeddings 
ADD COLUMN search_vector_en tsvector
GENERATED ALWAYS AS (to_tsvector('english', chunk_text)) STORED;

-- 3. 创建中文 GIN 索引
CREATE INDEX idx_rag_embeddings_search_vector_zh 
ON rag_embeddings USING gin (search_vector_zh);

-- 4. 创建英文 GIN 索引
CREATE INDEX idx_rag_embeddings_search_vector_en 
ON rag_embeddings USING gin (search_vector_en);

-- 5. 验证结果
DO $$
DECLARE
    v_total INTEGER;
    v_zh INTEGER;
    v_en INTEGER;
BEGIN
    SELECT COUNT(*), 
           COUNT(search_vector_zh) FILTER (WHERE search_vector_zh IS NOT NULL),
           COUNT(search_vector_en) FILTER (WHERE search_vector_en IS NOT NULL)
    INTO v_total, v_zh, v_en
    FROM rag_embeddings;
    RAISE NOTICE 'Search vectors: total=% rows, search_vector_zh populated=%, search_vector_en populated=%', 
        v_total, v_zh, v_en;
END $$;

-- 6. 添加注释
COMMENT ON COLUMN rag_embeddings.search_vector_zh IS 'Chinese full-text search vector using pg_jieba (jiebacfg config), GIN indexed';
COMMENT ON COLUMN rag_embeddings.search_vector_en IS 'English full-text search vector using english config, GIN indexed';
