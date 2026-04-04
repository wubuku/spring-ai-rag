-- V10: 审计日志表
-- 记录关键业务操作的审计轨迹（创建/更新/删除）

CREATE TABLE rag_audit_log (
    id BIGSERIAL PRIMARY KEY,
    operation VARCHAR(16) NOT NULL,         -- CREATE / UPDATE / DELETE
    entity_type VARCHAR(64) NOT NULL,       -- Collection / Document / ChatHistory 等
    entity_id VARCHAR(64) NOT NULL,         -- 实体 ID
    session_id VARCHAR(128),                 -- 会话 ID（可为 null）
    operator VARCHAR(128),                    -- 操作人标识（API Key 前缀/用户 ID）
    description VARCHAR(512),               -- 操作描述
    details TEXT,                            -- JSON 格式操作详情
    client_ip VARCHAR(45),                   -- 客户端 IP
    trace_id VARCHAR(64),                    -- 请求追踪 ID
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

-- 索引：按实体类型+ID 查询（审计历史）
CREATE INDEX idx_audit_entity_type_id ON rag_audit_log(entity_type, entity_id);
-- 索引：按操作类型查询
CREATE INDEX idx_audit_operation ON rag_audit_log(operation);
-- 索引：按会话查询
CREATE INDEX idx_audit_session ON rag_audit_log(session_id);
-- 索引：按时间查询（时序清理）
CREATE INDEX idx_audit_created_at ON rag_audit_log(created_at);

COMMENT ON TABLE rag_audit_log IS '审计日志表 - 记录关键业务操作的审计轨迹';
COMMENT ON COLUMN rag_audit_log.operation IS '操作类型：CREATE / UPDATE / DELETE';
COMMENT ON COLUMN rag_audit_log.entity_type IS '实体类型：Collection / Document / ChatHistory / AbTest / Alert';
COMMENT ON COLUMN rag_audit_log.entity_id IS '实体唯一标识符';
COMMENT ON COLUMN rag_audit_log.session_id IS '会话 ID（可选）';
COMMENT ON COLUMN rag_audit_log.operator IS '操作人标识，如 API Key 前缀或用户 ID';
COMMENT ON COLUMN rag_audit_log.description IS '操作描述，如"创建集合 my-collection"';
COMMENT ON COLUMN rag_audit_log.details IS 'JSON 格式操作详情，存储变更前后内容等';
COMMENT ON COLUMN rag_audit_log.client_ip IS '客户端 IP 地址';
COMMENT ON COLUMN rag_audit_log.trace_id IS '请求追踪 ID（来自 X-Trace-Id 或自动生成）';
