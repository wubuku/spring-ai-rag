-- WP2 大表索引终态断言（V78 已改为事务内普通索引，见 V78 注释）。

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid
                   WHERE c.relname = 'uq_rag_embedding_chunk_profile_tenant' AND i.indisvalid) THEN
        RAISE EXCEPTION 'V79 终态断言失败：租户内唯一索引缺失或 INVALID。清理：DROP INDEX IF EXISTS uq_rag_embedding_chunk_profile_tenant; 然后重跑 V78（IF NOT EXISTS 幂等）；排查残留旧全局索引或重复索引名。';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_class WHERE relname = 'uq_rag_embedding_chunk_profile') THEN
        RAISE EXCEPTION 'V79 终态断言失败：残留全局 uq_rag_embedding_chunk_profile';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid
               WHERE c.relname IN ('uq_rag_embedding_chunk_profile_tenant',
                                   'idx_rag_embeddings_tenant_leading')
                 AND NOT i.indisvalid) THEN
        RAISE EXCEPTION 'V79 终态断言失败：存在 INVALID 索引。清理：DROP INDEX IF EXISTS <invalid_index_name>; 再重跑 V78；若仍见 INVALID，检查是否有在线 CONCURRENTLY 演练脚本并发执行。';
    END IF;
END
$$;
