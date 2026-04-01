-- ============================================================
-- RAG 检索评估表
-- 版本: V4
-- 说明: 记录每次检索效果评估的数据，包括 Precision@K, Recall@K, MRR, NDCG 等指标
-- ============================================================

CREATE TABLE IF NOT EXISTS rag_retrieval_evaluations (
    id BIGSERIAL PRIMARY KEY,
    query TEXT NOT NULL,                                    -- 查询文本
    expected_document_ids TEXT,                             -- 期望相关文档 ID 列表（JSON）
    retrieved_document_ids TEXT,                            -- 实际检索到的文档 ID 列表（JSON）
    evaluation_result JSONB,                                -- 评估结果详情
    precision_at_k JSONB,                                   -- Precision@K（K → 值）
    recall_at_k JSONB,                                      -- Recall@K（K → 值）
    mrr DOUBLE PRECISION,                                   -- Mean Reciprocal Rank
    ndcg DOUBLE PRECISION,                                  -- NDCG
    hit_rate DOUBLE PRECISION,                              -- Hit Rate
    evaluation_method VARCHAR(50) DEFAULT 'AUTO',           -- 评估方法: AUTO/MANUAL/LLM
    evaluator_id VARCHAR(255),                              -- 评估人 ID
    metadata JSONB,                                         -- 扩展字段
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 索引
CREATE INDEX IF NOT EXISTS idx_evaluations_created ON rag_retrieval_evaluations (created_at);
CREATE INDEX IF NOT EXISTS idx_evaluations_method ON rag_retrieval_evaluations (evaluation_method);
