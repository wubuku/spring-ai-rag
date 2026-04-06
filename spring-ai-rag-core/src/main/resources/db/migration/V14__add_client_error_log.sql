-- V14: Client-side error log table for WebUI error reporting
CREATE TABLE rag_client_error (
    id BIGSERIAL PRIMARY KEY,
    error_type VARCHAR(256) NOT NULL,
    error_message VARCHAR(1024) NOT NULL,
    stack_trace TEXT,
    component_stack TEXT,
    page_url VARCHAR(512),
    user_agent VARCHAR(512),
    session_id VARCHAR(64),
    user_id VARCHAR(64),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

-- Indexes for common query patterns
CREATE INDEX idx_client_error_timestamp ON rag_client_error(created_at DESC);
CREATE INDEX idx_client_error_error_type ON rag_client_error(error_type);
CREATE INDEX idx_client_error_session_id ON rag_client_error(session_id);
