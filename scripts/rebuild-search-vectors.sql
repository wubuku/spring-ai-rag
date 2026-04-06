-- ================================================================
-- 批量重建 search_vector_zh 和 search_vector_en GENERATED 列
-- ================================================================
-- 用途：V15 迁移创建了 GENERATED 列，但已有数据的这些列为 NULL
--
-- 方法：UPDATE 每行来触发 GENERATED 列重新计算
--       PostgreSQL 15+ 在 UPDATE 时会重新计算 GENERATED 列
--
-- 注意：此脚本会 UPDATE 大量行，请在低峰期执行

-- 1. 检查当前状态
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
    
    RAISE NOTICE 'Before rebuild: total=%, search_vector_zh populated=%, search_vector_en populated=%', 
        v_total, v_zh, v_en;
END $$;

-- 2. 检查 pg_jieba 扩展是否安装
SELECT extname 
FROM pg_extension 
WHERE extname IN ('pg_jieba', 'pg_trgm', 'vector');

-- 3. 重建 search_vector_en（英文 tsvector，无需扩展）
--    使用 + '' 来强制触发 UPDATE（即使值相同也会触发重新计算）
DO $$
DECLARE
    v_count INTEGER := 0;
BEGIN
    UPDATE rag_embeddings 
    SET chunk_text = chunk_text || ''  -- 强制触发 UPDATE
    WHERE search_vector_en IS NULL
    OR search_vector_en IS NOT NULL;  -- 也更新已有值的行以确保一致性
    
    GET DIAGNOSTICS v_count = ROW_COUNT;
    RAISE NOTICE 'Updated search_vector_en for % rows', v_count;
END $$;

-- 4. 重建 search_vector_zh（需要 pg_jieba 扩展）
DO $$
DECLARE
    v_count INTEGER := 0;
BEGIN
    IF EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'pg_jieba') THEN
        UPDATE rag_embeddings 
        SET chunk_text = chunk_text || ''  -- 强制触发 UPDATE
        WHERE search_vector_zh IS NULL
        OR search_vector_zh IS NOT NULL;
        
        GET DIAGNOSTICS v_count = ROW_COUNT;
        RAISE NOTICE 'Updated search_vector_zh for % rows (pg_jieba available)', v_count;
    ELSE
        RAISE NOTICE 'pg_jieba not installed, skipping search_vector_zh rebuild';
        
        -- 检查是否有需要重建的行
        IF EXISTS (SELECT 1 FROM rag_embeddings WHERE search_vector_zh IS NULL) THEN
            RAISE NOTICE 'WARNING: % rows have NULL search_vector_zh', 
                (SELECT COUNT(*) FROM rag_embeddings WHERE search_vector_zh IS NULL);
        END IF;
    END IF;
END $$;

-- 5. 验证重建结果
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
    
    RAISE NOTICE 'After rebuild: total=%, search_vector_zh populated=%, search_vector_en populated=%', 
        v_total, v_zh, v_en;
    
    -- 检查是否还有 NULL
    IF v_zh < v_total THEN
        RAISE WARNING 'search_vector_zh still has % NULL values out of % total', 
            v_total - v_zh, v_total;
    END IF;
    
    IF v_en < v_total THEN
        RAISE WARNING 'search_vector_en still has % NULL values out of % total', 
            v_total - v_en, v_total;
    END IF;
END $$;

-- 6. 采样检查重建结果
SELECT 
    id, 
    LEFT(chunk_text, 50) as chunk_preview,
    search_vector_zh IS NOT NULL as zh_ok,
    search_vector_en IS NOT NULL as en_ok,
    ts_rank_cd('english'::regconfig, to_tsvector('english', chunk_text)) as sample_en_rank
FROM rag_embeddings
LIMIT 5;
