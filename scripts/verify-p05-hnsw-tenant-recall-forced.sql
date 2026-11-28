-- P0-5 补充实验：临时删除 tenant-leading B-tree 索引，强制规划器走全局 HNSW，
-- 以复现「小租户过滤 + 全局 HNSW 候选被大租户挤占」的召回退化，并对比
-- hnsw.iterative_scan = off / relaxed_order。
-- 只能在一次性演练容器内执行；执行完容器即删除，不影响生产 schema。

\timing on
\pset format unaligned
\pset fieldsep '|'

DROP INDEX IF EXISTS idx_rag_embeddings_tenant_leading;
ANALYZE rag_embeddings;

-- 真值已由 verify-p05-hnsw-tenant-recall.sql 生成在 p05_truth 中。
DROP TABLE IF EXISTS p05_forced_results;
CREATE TABLE p05_forced_results (
    mode TEXT, tenant_id TEXT, query_id BIGINT, hit_id BIGINT, elapsed_ms NUMERIC
);

CREATE OR REPLACE FUNCTION p05_run_forced_mode(p_mode TEXT) RETURNS void AS $$
DECLARE
    rec RECORD;
    t0 TIMESTAMPTZ;
    r RECORD;
BEGIN
    EXECUTE format('SET hnsw.iterative_scan = %s', p_mode);
    SET hnsw.ef_search = 40;
    FOR rec IN SELECT tenant_id, query_id, q FROM p05_queries LOOP
        t0 := clock_timestamp();
        FOR r IN EXECUTE
            'SELECT id FROM rag_embeddings WHERE tenant_id = $1 '
            'ORDER BY embedding_1024 <=> $2 LIMIT 10'
            USING rec.tenant_id, rec.q
        LOOP
            INSERT INTO p05_forced_results VALUES (
                p_mode, rec.tenant_id, rec.query_id, r.id,
                EXTRACT(EPOCH FROM (clock_timestamp() - t0)) * 1000
            );
        END LOOP;
    END LOOP;
    RESET hnsw.iterative_scan;
    RESET hnsw.ef_search;
END;
$$ LANGUAGE plpgsql;

SELECT p05_run_forced_mode('off');
SELECT p05_run_forced_mode('relaxed_order');

SELECT
    a.mode,
    a.tenant_id,
    COUNT(DISTINCT a.query_id) AS queries,
    ROUND(
        SUM(CASE WHEN t.truth_id IS NOT NULL THEN 1 ELSE 0 END)::numeric
        / (COUNT(DISTINCT a.query_id) * 10), 4
    ) AS recall_at_10,
    ROUND(PERCENTILE_CONT(0.95) WITHIN GROUP (
        ORDER BY a.elapsed_ms
    )::numeric, 3) AS p95_ms
FROM p05_forced_results a
LEFT JOIN p05_truth t
  ON t.tenant_id = a.tenant_id
 AND t.query_id = a.query_id
 AND t.truth_id = a.hit_id
GROUP BY a.mode, a.tenant_id
ORDER BY a.mode, a.tenant_id;

-- 计划取证：确认已走 HNSW。
SET hnsw.iterative_scan = relaxed_order;
EXPLAIN (ANALYZE, BUFFERS)
SELECT id FROM rag_embeddings
WHERE tenant_id = 't_small'
ORDER BY embedding_1024 <=> (SELECT q FROM p05_queries WHERE tenant_id='t_small' LIMIT 1)
LIMIT 10;
RESET hnsw.iterative_scan;
