-- 流式导出按 (collection_id, id > afterId ORDER BY id) 游标翻页。
-- 仅有 collection_id 单列索引时，每批仍要排序或回退全表扫描；
-- 复合索引让每批都走范围扫描，代价与批大小相关而不是与表大小相关。
CREATE INDEX IF NOT EXISTS idx_rag_doc_collection_id
    ON rag_documents (collection_id, id);
