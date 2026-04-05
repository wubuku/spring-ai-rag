-- V11: Add silence schedules table for alert downtime/suppress periods
CREATE TABLE rag_silence_schedules (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL UNIQUE,
    alert_key VARCHAR(100),
    silence_type VARCHAR(20) NOT NULL,
    start_time VARCHAR(100),
    end_time VARCHAR(100),
    description TEXT,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    metadata JSONB,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_silence_enabled ON rag_silence_schedules(enabled);
CREATE INDEX idx_silence_alert_key ON rag_silence_schedules(alert_key);
