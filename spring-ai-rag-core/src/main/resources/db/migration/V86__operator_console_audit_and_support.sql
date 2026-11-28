-- V86: Operator Console persistent platform audit events and read-only support sessions.
-- Both tables are PLATFORM_GLOBAL (no RLS), registered in TenantTableCatalog.java.

CREATE TABLE IF NOT EXISTS rag_platform_audit_event (
    seq                BIGSERIAL PRIMARY KEY,
    event_id           VARCHAR(64)  NOT NULL UNIQUE,
    occurred_at        TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    actor              VARCHAR(128) NOT NULL,
    actor_type         VARCHAR(32)  NOT NULL,
    action             VARCHAR(64)  NOT NULL,
    target_tenant_id   VARCHAR(64),
    target_resource_id VARCHAR(128),
    reason             TEXT,
    outcome            VARCHAR(16)  NOT NULL,
    detail_json        JSONB
);

CREATE INDEX IF NOT EXISTS idx_rag_platform_audit_tenant_seq
    ON rag_platform_audit_event (target_tenant_id, seq DESC);

CREATE INDEX IF NOT EXISTS idx_rag_platform_audit_action_seq
    ON rag_platform_audit_event (action, seq DESC);

CREATE INDEX IF NOT EXISTS idx_rag_platform_audit_occurred_at
    ON rag_platform_audit_event (occurred_at DESC);

CREATE TABLE IF NOT EXISTS rag_operator_support_session (
    session_id     VARCHAR(64)  PRIMARY KEY,
    tenant_id      VARCHAR(64)  NOT NULL REFERENCES rag_tenant (tenant_id),
    operator_actor VARCHAR(128) NOT NULL,
    ticket_ref     VARCHAR(128) NOT NULL,
    reason         TEXT         NOT NULL,
    read_only      BOOLEAN      NOT NULL DEFAULT TRUE CHECK (read_only = TRUE),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at     TIMESTAMPTZ  NOT NULL,
    revoked_at     TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_rag_operator_support_tenant_created
    ON rag_operator_support_session (tenant_id, created_at DESC);
