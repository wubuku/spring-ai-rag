-- 告警表
CREATE TABLE IF NOT EXISTS rag_alerts (
    id              BIGSERIAL PRIMARY KEY,
    alert_type      VARCHAR(50)  NOT NULL,
    alert_name      VARCHAR(100) NOT NULL,
    message         TEXT         NOT NULL,
    severity        VARCHAR(20)  NOT NULL,   -- INFO/WARNING/CRITICAL
    metrics         JSONB,
    status          VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE/RESOLVED/SILENCED
    resolution      TEXT,
    fired_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    resolved_at     TIMESTAMPTZ,
    silenced_until  TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- SLO 配置表
CREATE TABLE IF NOT EXISTS rag_slo_configs (
    id              BIGSERIAL PRIMARY KEY,
    slo_name        VARCHAR(100) NOT NULL UNIQUE,
    slo_type        VARCHAR(50)  NOT NULL,    -- AVAILABILITY/LATENCY/QUALITY/ERROR_RATE
    target_value    DOUBLE PRECISION NOT NULL,
    unit            VARCHAR(20)  NOT NULL,
    description     TEXT,
    enabled         BOOLEAN      NOT NULL DEFAULT true,
    metadata        JSONB,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ
);

-- 索引
CREATE INDEX IF NOT EXISTS idx_rag_alerts_status ON rag_alerts (status);
CREATE INDEX IF NOT EXISTS idx_rag_alerts_severity ON rag_alerts (severity);
CREATE INDEX IF NOT EXISTS idx_rag_alerts_fired_at ON rag_alerts (fired_at);
CREATE INDEX IF NOT EXISTS idx_rag_alerts_type ON rag_alerts (alert_type);
CREATE INDEX IF NOT EXISTS idx_rag_alerts_status_fired ON rag_alerts (status, fired_at DESC);
CREATE INDEX IF NOT EXISTS idx_rag_slo_configs_name ON rag_slo_configs (slo_name);
CREATE INDEX IF NOT EXISTS idx_rag_slo_configs_enabled ON rag_slo_configs (enabled);
