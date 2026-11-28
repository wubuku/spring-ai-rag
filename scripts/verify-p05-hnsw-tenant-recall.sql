-- P0-5：在真实 pgvector 容器内执行的多租户 HNSW Recall@10 / P95 压测 SQL。
-- 由 verify-p05-hnsw-tenant-recall.py 建好的 300k 行语料与 HNSW 索引上运行。
-- 用 psql 在容器内部执行（docker exec -i <container> psql -U p05 -d p05 -f -），避开宿主 bridge 连接不稳定。

\timing on
\pset format unaligned
\pset fieldsep '|'

-- 1. 建立查询集：每租户 50 个 query（直接取真实行向量，确保查询落在租户簇内）。
DROP TABLE IF EXISTS p05_queries;
CREATE TABLE p05_queries AS
SELECT tenant_id, id AS query_id, embedding_1024 AS q
FROM (
    SELECT tenant_id, id, embedding_1024,
           row_number() OVER (PARTITION BY tenant_id ORDER BY id) AS rn
    FROM rag_embeddings
) t
WHERE rn <= 50;
ANALYZE p05_queries;

-- 2. 真值：强制顺序扫描（禁用 ANN 索引）计算每 query 的精确 top-10。
-- 关键：必须禁用索引扫描，否则下面 LATERAL 会走 HNSW，「真值」本身也是近似值。
SET enable_indexscan = off;
SET enable_bitmapscan = off;
DROP TABLE IF EXISTS p05_truth;
CREATE TABLE p05_truth AS
SELECT q.tenant_id, q.query_id, e.id AS truth_id
FROM p05_queries q
CROSS JOIN LATERAL (
    SELECT id
    FROM rag_embeddings e
    WHERE e.tenant_id = q.tenant_id
    ORDER BY e.embedding_1024 <=> q.q
    LIMIT 10
) e;
ANALYZE p05_truth;
RESET enable_indexscan;
RESET enable_bitmapscan;

-- 3. 两种 iterative_scan 模式下的结果表。
DROP TABLE IF EXISTS p05_ann_results;
CREATE TABLE p05_ann_results (
    mode TEXT, tenant_id TEXT, query_id BIGINT, hit_id BIGINT, elapsed_ms NUMERIC
);

-- 4. 执行 ANN 查询的 PL/pgSQL：对每条 query 计时，写入命中的 id。
CREATE OR REPLACE FUNCTION p05_run_mode(p_mode TEXT) RETURNS void AS $$
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
            INSERT INTO p05_ann_results VALUES (
                p_mode, rec.tenant_id, rec.query_id, r.id,
                EXTRACT(EPOCH FROM (clock_timestamp() - t0)) * 1000
            );
        END LOOP;
    END LOOP;
    RESET hnsw.iterative_scan;
    RESET hnsw.ef_search;
END;
$$ LANGUAGE plpgsql;

SELECT p05_run_mode('off');
SELECT p05_run_mode('relaxed_order');

-- 5. Recall@10 + P95 延迟（每条 query 的延迟用其首条命中行的 elapsed_ms）。
SELECT
    a.mode,
    a.tenant_id,
    COUNT(DISTINCT a.query_id) AS queries,
    ROUND(
        SUM(CASE WHEN t.truth_id IS NOT NULL THEN 1 ELSE 0 END)::numeric
        / (COUNT(DISTINCT a.query_id) * 10), 4
    ) AS recall_at_10,
    ROUND(PERCENTILE_CONT(0.5) WITHIN GROUP (
        ORDER BY a.elapsed_ms
    )::numeric, 3) AS p50_ms,
    ROUND(PERCENTILE_CONT(0.95) WITHIN GROUP (
        ORDER BY a.elapsed_ms
    )::numeric, 3) AS p95_ms
FROM p05_ann_results a
LEFT JOIN p05_truth t
  ON t.tenant_id = a.tenant_id
 AND t.query_id = a.query_id
 AND t.truth_id = a.hit_id
GROUP BY a.mode, a.tenant_id
ORDER BY a.mode, a.tenant_id;

-- 6. 执行计划取证（小租户过滤查询）。
SET hnsw.iterative_scan = off;
EXPLAIN (ANALYZE, BUFFERS)
SELECT id FROM rag_embeddings
WHERE tenant_id = 't_small'
ORDER BY embedding_1024 <=> (SELECT q FROM p05_queries WHERE tenant_id='t_small' LIMIT 1)
LIMIT 10;
SET hnsw.iterative_scan = relaxed_order;
EXPLAIN (ANALYZE, BUFFERS)
SELECT id FROM rag_embeddings
WHERE tenant_id = 't_small'
ORDER BY embedding_1024 <=> (SELECT q FROM p05_queries WHERE tenant_id='t_small' LIMIT 1)
LIMIT 10;
RESET hnsw.iterative_scan;
