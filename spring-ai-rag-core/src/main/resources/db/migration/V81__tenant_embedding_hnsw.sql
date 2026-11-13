-- WP4：活动向量列的 tenant-leading HNSW 索引（基准阻断项修复）。
--
-- 背景（2026-11-14 基准实测）：
--   * V1 曾在旧 embedding 列建 HNSW；V68 DROP COLUMN embedding 后该索引一并消失；
--   * embedding_1024 VECTOR(1024) 是当前唯一活动向量列，但 V1–V80 从未给它建 ANN 索引；
--   * WP4 基准 EXPLAIN 显示 rag_embeddings 为 Seq Scan + top-N heapsort，
--     planUsesIndex=false，属实施规划 §9「HNSW 基准不达标 → 阻断发布」。
--
-- 修复：按规划「第一版共享 HNSW + RLS + 基准」与 §4.8「Embedding 冗余租户列」，
-- 在 embedding_1024 上建 HNSW（cosine），并让 tenant_id 打头参与索引键，使 RLS
-- 过滤与 ANN 排序共用同一前缀，避免「给 documents 加 RLS 退化成昂贵子查询」。
-- pgvector 的 HNSW 只支持单列向量列作为索引键；tenant 前缀通过 partial index
-- 无法覆盖多租户，因此这里保留全局 HNSW + RLS 谓词（规划 §9 允许 over-fetch /
-- iterative scan），并把 tenant-leading 的单列 B-tree 索引由 V76 保留（供 RLS Filter）。
-- 真正的「tenant-leading ANN」需每租户分区或 vector 与 tenant 的复合 ANN 支持，
-- 当前 pgvector 不提供；本索引让 ANN 排序走 HNSW，RLS 谓词在候选集上过滤。
--
-- 注意：CREATE INDEX 在事务内执行，对大表有锁窗口；在线 CONCURRENTLY 演练
-- 不进迁移链（WP2 事故教训，见 V78 注释 / MULTI_TENANCY_UPGRADE_REHEARSAL.md）。

CREATE INDEX IF NOT EXISTS idx_rag_embeddings_embedding_1024_hnsw
    ON rag_embeddings USING hnsw (embedding_1024 vector_cosine_ops)
    WITH (m = '16', ef_construction = '64');

-- 终态断言：ANN 索引存在且有效（非 INVALID）。
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_index i
        JOIN pg_class c ON c.oid = i.indexrelid
        JOIN pg_am am ON am.oid = c.relam
        WHERE c.relname = 'idx_rag_embeddings_embedding_1024_hnsw'
          AND am.amname = 'hnsw'
          AND i.indisvalid
    ) THEN
        RAISE EXCEPTION 'V81 终态断言失败：embedding_1024 HNSW 索引缺失或 INVALID。清理：DROP INDEX IF EXISTS idx_rag_embeddings_embedding_1024_hnsw; 再重跑；若仍 INVALID 检查是否有在线 CONCURRENTLY 演练并发执行。';
    END IF;
END
$$;
