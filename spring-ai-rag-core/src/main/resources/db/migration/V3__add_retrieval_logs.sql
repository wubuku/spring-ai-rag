-- ============================================================
-- RAG 检索日志表
-- 版本: V3
-- 说明: 记录每次检索的详细性能数据，用于趋势分析和性能调优
-- ============================================================

CREATE TABLE IF NOT EXISTS rag_retrieval_logs (
    id BIGSERIAL PRIMARY KEY,
    session_id VARCHAR(255),                            -- 会话ID
    query TEXT NOT NULL,                                -- 查询文本
    retrieval_strategy VARCHAR(50),                     -- 检索策略: hybrid/vector/fulltext
    vector_search_time_ms BIGINT,                       -- 向量检索耗时（毫秒）
    fulltext_search_time_ms BIGINT,                     -- 全文检索耗时（毫秒）
    rerank_time_ms BIGINT,                              -- 重排序耗时（毫秒）
    total_time_ms BIGINT,                               -- 总耗时（毫秒）
    result_count INTEGER,                               -- 返回结果数
    result_scores JSONB,                                -- 各结果得分（文档ID → 分数）
    metadata JSONB,                                     -- 扩展字段
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 索引
CREATE INDEX IF NOT EXISTS idx_retrieval_logs_session ON rag_retrieval_logs (session_id);
CREATE INDEX IF NOT EXISTS idx_retrieval_logs_created ON rag_retrieval_logs (created_at);
CREATE INDEX IF NOT EXISTS idx_retrieval_logs_strategy ON rag_retrieval_logs (retrieval_strategy);
