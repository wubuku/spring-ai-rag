-- 安全边界：为文件子系统、用户反馈与检索评估补上归属信息。
--
-- 背景：
--   * fs_files / fs_import_batches 没有归属，任意 RAG_READ key 可以枚举并下载
--     全部上传文件；/files/* 与 /preview/* 只校验认证，不校验 Collection ACL。
--   * rag_user_feedback / rag_retrieval_evaluations 是全局视图，任意 NORMAL key
--     都能读到他人的 query、comment、sessionId。
--
-- 兼容策略：
--   * 新增列全部可空。历史行没有归属信息，受限 key 不再可见；root/不受限
--     key（含 rag.security.enabled=false 的部署）继续看到全量数据。
--   * 新写入的行由服务端写入 owner_principal_id 与 collection_id。
--   * 不回填历史行的 owner：无法可靠推断 principal，静默猜测会把数据暴露给
--     错误的调用方，宁可让受限 key 看不到历史数据。

ALTER TABLE fs_files
    ADD COLUMN owner_principal_id VARCHAR(128),
    ADD COLUMN collection_id BIGINT;

CREATE INDEX idx_fs_files_owner
    ON fs_files (owner_principal_id, collection_id);

CREATE INDEX idx_fs_files_collection
    ON fs_files (collection_id);

ALTER TABLE fs_import_batches
    ADD COLUMN owner_principal_id VARCHAR(128),
    ADD COLUMN collection_id BIGINT;

CREATE INDEX idx_fs_import_batches_owner
    ON fs_import_batches (owner_principal_id, collection_id);

ALTER TABLE rag_user_feedback
    ADD COLUMN owner_principal_id VARCHAR(128);

CREATE INDEX idx_rag_fb_owner_created
    ON rag_user_feedback (owner_principal_id, created_at DESC);

ALTER TABLE rag_retrieval_evaluations
    ADD COLUMN owner_principal_id VARCHAR(128);

CREATE INDEX idx_rag_eval_owner_created
    ON rag_retrieval_evaluations (owner_principal_id, created_at DESC);
