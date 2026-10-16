-- V64: durable LLM budget reservations and bounded data-erasure operations.
-- All owner ids use ChatPrincipal.id() (for example db:<principalId>).

CREATE TABLE rag_llm_budget_policy (
    principal_id VARCHAR(128) PRIMARY KEY,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    policy_version BIGINT NOT NULL DEFAULT 1,
    policy_source VARCHAR(32) NOT NULL DEFAULT 'CONFIG_DEFAULT',
    defaults_fingerprint CHAR(64),
    daily_token_limit BIGINT NOT NULL,
    monthly_token_limit BIGINT NOT NULL,
    daily_cost_limit NUMERIC(30, 8),
    monthly_cost_limit NUMERIC(30, 8),
    max_request_tokens BIGINT NOT NULL,
    max_request_cost NUMERIC(30, 8),
    reservation_ttl_seconds INTEGER NOT NULL DEFAULT 900,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT ck_rag_llm_budget_policy_owner
        CHECK (char_length(principal_id) BETWEEN 1 AND 128
            AND principal_id !~ '[^ -~]'),
    CONSTRAINT ck_rag_llm_budget_policy_source
        CHECK (policy_source IN ('CONFIG_DEFAULT', 'MANAGED')),
    CONSTRAINT ck_rag_llm_budget_policy_limits
        CHECK (daily_token_limit > 0
            AND monthly_token_limit >= daily_token_limit
            AND max_request_tokens > 0
            AND reservation_ttl_seconds BETWEEN 1 AND 3600),
    CONSTRAINT ck_rag_llm_budget_policy_cost
        CHECK ((daily_cost_limit IS NULL OR daily_cost_limit >= 0)
            AND (monthly_cost_limit IS NULL OR monthly_cost_limit >= 0)
            AND (max_request_cost IS NULL OR max_request_cost >= 0))
);

CREATE TABLE rag_llm_budget_window (
    principal_id VARCHAR(128) NOT NULL,
    window_kind VARCHAR(8) NOT NULL,
    window_start TIMESTAMPTZ NOT NULL,
    reserved_tokens BIGINT NOT NULL DEFAULT 0,
    settled_tokens BIGINT NOT NULL DEFAULT 0,
    reserved_cost NUMERIC(30, 8) NOT NULL DEFAULT 0,
    settled_cost NUMERIC(30, 8) NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (principal_id, window_kind, window_start),
    CONSTRAINT ck_rag_llm_budget_window_owner
        CHECK (char_length(principal_id) BETWEEN 1 AND 128),
    CONSTRAINT ck_rag_llm_budget_window_kind
        CHECK (window_kind IN ('DAY', 'MONTH')),
    CONSTRAINT ck_rag_llm_budget_window_counts
        CHECK (reserved_tokens >= 0 AND settled_tokens >= 0
            AND reserved_cost >= 0 AND settled_cost >= 0 AND version >= 0)
);

CREATE INDEX idx_rag_llm_budget_window_expiry
    ON rag_llm_budget_window (updated_at);

CREATE TABLE rag_llm_budget_reservation (
    reservation_id UUID PRIMARY KEY,
    principal_id VARCHAR(128) NOT NULL,
    logical_execution_id UUID NOT NULL,
    call_ordinal INTEGER NOT NULL,
    day_window_start TIMESTAMPTZ NOT NULL,
    month_window_start TIMESTAMPTZ NOT NULL,
    estimated_tokens BIGINT NOT NULL,
    estimated_cost NUMERIC(30, 8) NOT NULL DEFAULT 0,
    actual_tokens BIGINT,
    actual_cost NUMERIC(30, 8),
    state VARCHAR(16) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    finalized_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT uk_rag_llm_budget_reservation_execution
        UNIQUE (logical_execution_id, call_ordinal),
    CONSTRAINT ck_rag_llm_budget_reservation_owner
        CHECK (char_length(principal_id) BETWEEN 1 AND 128),
    CONSTRAINT ck_rag_llm_budget_reservation_ordinal
        CHECK (call_ordinal > 0),
    CONSTRAINT ck_rag_llm_budget_reservation_estimate
        CHECK (estimated_tokens > 0 AND estimated_cost >= 0),
    CONSTRAINT ck_rag_llm_budget_reservation_actual
        CHECK ((actual_tokens IS NULL OR actual_tokens >= 0)
            AND (actual_cost IS NULL OR actual_cost >= 0)),
    CONSTRAINT ck_rag_llm_budget_reservation_state
        CHECK (state IN ('RESERVED', 'SETTLED', 'RELEASED', 'EXPIRED')),
    CONSTRAINT ck_rag_llm_budget_reservation_version
        CHECK (version >= 0)
);

CREATE INDEX idx_rag_llm_budget_reservation_expiry
    ON rag_llm_budget_reservation (state, expires_at);
CREATE INDEX idx_rag_llm_budget_reservation_owner
    ON rag_llm_budget_reservation (principal_id, created_at DESC);

CREATE TABLE rag_data_erasure_operation (
    operation_id UUID PRIMARY KEY,
    owner_principal_id VARCHAR(128) NOT NULL,
    target_principal_id VARCHAR(128) NOT NULL,
    session_id VARCHAR(36),
    trace_id VARCHAR(128),
    scope_fingerprint CHAR(64) NOT NULL,
    confirmation_token_hash CHAR(64) NOT NULL,
    preview_counts JSONB NOT NULL DEFAULT '{}'::jsonb,
    completed_counts JSONB NOT NULL DEFAULT '{}'::jsonb,
    category_state JSONB NOT NULL DEFAULT '{}'::jsonb,
    stable_errors JSONB NOT NULL DEFAULT '{}'::jsonb,
    status VARCHAR(24) NOT NULL DEFAULT 'PREVIEWED',
    lease_expires_at TIMESTAMPTZ,
    preview_expires_at TIMESTAMPTZ NOT NULL,
    operation_expires_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT ck_rag_data_erasure_owner
        CHECK (char_length(owner_principal_id) BETWEEN 1 AND 128),
    CONSTRAINT ck_rag_data_erasure_target
        CHECK (char_length(target_principal_id) BETWEEN 1 AND 128),
    CONSTRAINT ck_rag_data_erasure_session
        CHECK (session_id IS NULL OR session_id ~ '^[A-Za-z0-9._~-]{1,36}$'),
    CONSTRAINT ck_rag_data_erasure_trace
        CHECK (trace_id IS NULL OR (char_length(trace_id) BETWEEN 1 AND 128
            AND trace_id !~ '[^ -~]')),
    CONSTRAINT ck_rag_data_erasure_status
        CHECK (status IN ('PREVIEWED', 'APPLYING', 'COMPLETED', 'PARTIAL_FAILED', 'EXPIRED')),
    CONSTRAINT ck_rag_data_erasure_version
        CHECK (version >= 0)
);

CREATE INDEX idx_rag_data_erasure_owner_created
    ON rag_data_erasure_operation (owner_principal_id, created_at DESC);
CREATE INDEX idx_rag_data_erasure_expiry
    ON rag_data_erasure_operation (status, operation_expires_at);

ALTER TABLE rag_client_error
    ADD COLUMN IF NOT EXISTS owner_principal_id VARCHAR(128),
    ADD COLUMN IF NOT EXISTS request_trace_id VARCHAR(128);

CREATE INDEX IF NOT EXISTS idx_client_error_owner_created
    ON rag_client_error (owner_principal_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_client_error_request_trace
    ON rag_client_error (request_trace_id);

INSERT INTO rag_llm_budget_policy (
    principal_id, enabled, policy_version, policy_source, defaults_fingerprint,
    daily_token_limit, monthly_token_limit, max_request_tokens,
    reservation_ttl_seconds)
SELECT
    'db:' || principal_id, TRUE, 1, 'CONFIG_DEFAULT', NULL,
    5000000, 50000000, 200000, 900
FROM rag_api_principal
ON CONFLICT (principal_id) DO NOTHING;

COMMENT ON TABLE rag_llm_budget_policy IS
    'Canonical-principal LLM hard-budget policy; default rows are reconciled by application config';
COMMENT ON TABLE rag_llm_budget_reservation IS
    'Durable provider-call reservations; contains no prompt, answer, secret or tool payload';
COMMENT ON TABLE rag_data_erasure_operation IS
    'Preview-first, owner-scoped, resumable data-erasure operation without raw content';
