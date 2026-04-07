-- ================================================================
-- Batch rebuild search_vector_zh and search_vector_en GENERATED columns
-- ================================================================
-- Purpose: V15 migration creates GENERATED columns, but existing data
--          may have NULL values in these columns.
--
-- Method: UPDATE each row to trigger recalculation of GENERATED column.
--         PostgreSQL 15+ recalculates GENERATED columns on UPDATE.
--
-- Note: This script UPDATEs many rows — run during low-traffic periods.

-- 1. Check current state
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

-- 2. Check pg_jieba extension is installed
SELECT extname
FROM pg_extension
WHERE extname IN ('pg_jieba', 'pg_trgm', 'vector');

-- 3. Rebuild search_vector_en (English tsvector, no extension required)
--    Use + '' to force UPDATE trigger (even if value is unchanged)
DO $$
DECLARE
    v_count INTEGER := 0;
BEGIN
    UPDATE rag_embeddings
    SET chunk_text = chunk_text || ''  -- force UPDATE trigger
    WHERE search_vector_en IS NULL
    OR search_vector_en IS NOT NULL;  -- also update existing rows to ensure consistency

    GET DIAGNOSTICS v_count = ROW_COUNT;
    RAISE NOTICE 'Updated search_vector_en for % rows', v_count;
END $$;

-- 4. Rebuild search_vector_zh (requires pg_jieba extension)
DO $$
DECLARE
    v_count INTEGER := 0;
BEGIN
    IF EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'pg_jieba') THEN
        UPDATE rag_embeddings
        SET chunk_text = chunk_text || ''  -- force UPDATE trigger
        WHERE search_vector_zh IS NULL
        OR search_vector_zh IS NOT NULL;

        GET DIAGNOSTICS v_count = ROW_COUNT;
        RAISE NOTICE 'Updated search_vector_zh for % rows (pg_jieba available)', v_count;
    ELSE
        RAISE NOTICE 'pg_jieba not installed, skipping search_vector_zh rebuild';

        -- Check if there are rows needing rebuild
        IF EXISTS (SELECT 1 FROM rag_embeddings WHERE search_vector_zh IS NULL) THEN
            RAISE NOTICE 'WARNING: % rows have NULL search_vector_zh',
                (SELECT COUNT(*) FROM rag_embeddings WHERE search_vector_zh IS NULL);
        END IF;
    END IF;
END $$;

-- 5. Verify rebuild result
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

    -- Check for remaining NULLs
    IF v_zh < v_total THEN
        RAISE WARNING 'search_vector_zh still has % NULL values out of % total',
            v_total - v_zh, v_total;
    END IF;

    IF v_en < v_total THEN
        RAISE WARNING 'search_vector_en still has % NULL values out of % total',
            v_total - v_en, v_total;
    END IF;
END $$;

-- 6. Sample verification
SELECT
    id,
    LEFT(chunk_text, 50) as chunk_preview,
    search_vector_zh IS NOT NULL as zh_ok,
    search_vector_en IS NOT NULL as en_ok,
    ts_rank_cd('english'::regconfig, to_tsvector('english', chunk_text)) as sample_en_rank
FROM rag_embeddings
LIMIT 5;
