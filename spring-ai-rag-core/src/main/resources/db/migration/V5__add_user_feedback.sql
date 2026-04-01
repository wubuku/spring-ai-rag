-- ============================================================
-- 用户反馈表
-- 版本: V5
-- 说明: 收集用户对 RAG 检索结果的反馈（点赞/点踩/评分/评论）
-- ============================================================

CREATE TABLE IF NOT EXISTS rag_user_feedback (
    id BIGSERIAL PRIMARY KEY,
    session_id VARCHAR(255) NOT NULL,                       -- 会话 ID
    query TEXT NOT NULL,                                    -- 查询文本
    retrieved_document_ids TEXT,                            -- 检索到的文档 ID 列表（JSON）
    feedback_type VARCHAR(50) NOT NULL,                     -- 反馈类型: THUMBS_UP / THUMBS_DOWN / RATING
    rating INTEGER CHECK (rating >= 1 AND rating <= 5),     -- 评分（1-5）
    comment TEXT,                                           -- 用户评论
    selected_document_ids TEXT,                             -- 用户认为有用的文档 ID 列表（JSON）
    dwell_time_ms BIGINT,                                   -- 用户停留时间（毫秒）
    metadata JSONB,                                         -- 扩展字段
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 索引
CREATE INDEX IF NOT EXISTS idx_feedback_created ON rag_user_feedback (created_at);
CREATE INDEX IF NOT EXISTS idx_feedback_type ON rag_user_feedback (feedback_type);
CREATE INDEX IF NOT EXISTS idx_feedback_session ON rag_user_feedback (session_id);
