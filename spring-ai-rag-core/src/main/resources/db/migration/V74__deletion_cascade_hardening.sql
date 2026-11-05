-- ================================================================
-- V74: 永久删除的级联加固
-- ================================================================
-- 单篇文档永久删除已经由 DocumentMutationService 显式清理向量、引用与
-- 派生记录；这里把两条"删除顺序漏一步就会失败/残留"的外键改成级联，
-- 保证任何受控删除路径都不会留下孤儿或半删状态：
--
-- 1) rag_embeddings.document_id：当前是 NO ACTION。若后续新增删除路径
--    忘记先删向量，删除文档会直接失败；改成 ON DELETE CASCADE 后，
--    文档行删除即保证向量列一并消失。
-- 2) rag_derivation_repair_items.document_id：派生修复预览的逐文档台账
--    当前也是 NO ACTION，历史预览尚未过期时会让单篇删除返回外键错误。
--    预览本身按 result_expires_at 自动清理；文档被永久删除时，指向它的
--    台账行不应继续阻塞删除。
--
-- 其他引用保持既有语义：同步历史 run item 与迁移地址用 SET NULL 保留
-- 历史标记；反馈/会话来源引用没有外键，由业务删除方法显式清理。

ALTER TABLE rag_embeddings
    DROP CONSTRAINT rag_embeddings_document_id_fkey;
ALTER TABLE rag_embeddings
    ADD CONSTRAINT rag_embeddings_document_id_fkey
    FOREIGN KEY (document_id) REFERENCES rag_documents(id)
    ON DELETE CASCADE;

ALTER TABLE rag_derivation_repair_items
    DROP CONSTRAINT rag_derivation_repair_items_document_id_fkey;
ALTER TABLE rag_derivation_repair_items
    ADD CONSTRAINT rag_derivation_repair_items_document_id_fkey
    FOREIGN KEY (document_id) REFERENCES rag_documents(id)
    ON DELETE CASCADE;

COMMENT ON CONSTRAINT rag_embeddings_document_id_fkey ON rag_embeddings IS
    'Permanent document deletion removes its vectors atomically';
COMMENT ON CONSTRAINT rag_derivation_repair_items_document_id_fkey
    ON rag_derivation_repair_items IS
    'Permanent document deletion removes its repair ledger rows';
