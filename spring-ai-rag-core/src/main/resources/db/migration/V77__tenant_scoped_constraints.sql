-- WP2：租户化底座（3/3）—— 租户内复合约束与残留全局唯一清理。
--
-- 本迁移是事务内 DDL；CREATE INDEX CONCURRENTLY 放在 V78 非事务迁移（见 V78 注释）。

-- 1) collection_key 从「全局唯一」改为「租户内唯一」。
--    V27 建的是命名约束 uk_rag_collection_collection_key，先删再建复合约束。
ALTER TABLE rag_collection
    DROP CONSTRAINT IF EXISTS uk_rag_collection_collection_key;

ALTER TABLE rag_collection
    ADD CONSTRAINT uk_rag_collection_tenant_key UNIQUE (tenant_id, collection_key);

COMMENT ON CONSTRAINT uk_rag_collection_tenant_key ON rag_collection IS
    'WP2：collection_key 只在租户内唯一；cross-tenant 同名 key 合法（规划 §3#5）';

-- 2) 历史遗留的全局唯一索引若存在则清理（幂等；名字来自 V25）。
--    rag_embeddings 的 (document_id, chunk_index, embedding_profile_id) 部分唯一索引
--    在加 tenant_id 后需要改为 tenant-leading，避免跨租户 document_id 冲突误判。
--    注意：DROP/CREATE 大表索引放 V78 以 CONCURRENTLY 执行，此处仅登记裁决。

-- 3) 迁移内建终态断言：无残留旧全局唯一、新复合唯一存在。
DO $$
DECLARE
    n INTEGER;
BEGIN
    IF EXISTS (SELECT 1 FROM pg_constraint
               WHERE conname = 'uk_rag_collection_collection_key'
                 AND conrelid = 'rag_collection'::regclass) THEN
        RAISE EXCEPTION 'V77 终态断言失败：残留 V27 全局 collection_key 唯一约束';
    END IF;

    SELECT count(*) INTO n FROM pg_constraint
     WHERE conrelid = 'rag_collection'::regclass
       AND contype = 'u'
       AND pg_get_constraintdef(oid) LIKE '%tenant_id%collection_key%';
    IF n <> 1 THEN
        RAISE EXCEPTION 'V77 终态断言失败：(tenant_id, collection_key) 复合唯一约束缺失（n=%）', n;
    END IF;
END
$$;
