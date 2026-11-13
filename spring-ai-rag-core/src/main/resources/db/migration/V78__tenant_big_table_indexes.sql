-- WP2 大表专项：rag_embeddings 索引 tenant-leading 改造。
--
-- 2026-11-13 修订（监控方实测事故后）：
--   * 原方案在 Flyway 迁移里直接 CREATE INDEX CONCURRENTLY，实测被并发事务
--     （pg_stat_activity 中 pid68 idle in transaction）无限阻塞；加 lock_timeout
--     后变成 30s 快速失败，但 Flyway 非事务迁移与 schema history 更新连接之间
--     的并发事务无法由迁移脚本自身消除。
--   * 结论：本仓的 Flyway 升级路径保持「纯事务内 DDL」，V78 用普通索引；
--     CONCURRENTLY 只适用于生产在线演练（有真实大表数据、且运维可独占连接时），
--     由 scripts/verify-multi-tenancy-upgrade.sh 在演练库上单独执行，不进迁移链。
--   * 这样空库全新迁移与 V74 存量库升级走同一条可重放路径，无 INVALID 索引风险。
--   * 锁窗口 / 每批行数 / 在线 CONCURRENTLY 演练步骤见
--     docs/drafts/MULTI_TENANCY_UPGRADE_REHEARSAL.md。

-- 旧全局唯一索引（V25）下线，避免与租户内唯一语义冲突。
DROP INDEX IF EXISTS uq_rag_embedding_chunk_profile;

-- 新索引：tenant_id 打头，RLS 过滤与租户内去重共用同一前缀。
CREATE UNIQUE INDEX IF NOT EXISTS uq_rag_embedding_chunk_profile_tenant
    ON rag_embeddings (tenant_id, document_id, chunk_index, embedding_profile_id)
    WHERE embedding_profile_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_rag_embeddings_tenant_leading
    ON rag_embeddings (tenant_id);
