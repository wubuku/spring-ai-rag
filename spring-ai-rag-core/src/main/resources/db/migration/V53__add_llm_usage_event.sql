-- V53: durable, principal-scoped model invocation usage facts.

CREATE TABLE rag_llm_usage_event (
    id BIGSERIAL PRIMARY KEY,
    invocation_id UUID NOT NULL UNIQUE,
    logical_execution_id UUID NOT NULL,
    call_ordinal INTEGER NOT NULL,
    owner_principal_id VARCHAR(128) NOT NULL,
    session_id VARCHAR(255) NOT NULL,
    request_trace_id VARCHAR(128),
    model_ref VARCHAR(255) NOT NULL,
    chat_mode VARCHAR(16) NOT NULL,
    purpose VARCHAR(32) NOT NULL,
    streaming BOOLEAN NOT NULL,
    outcome VARCHAR(16) NOT NULL,
    prompt_tokens BIGINT NOT NULL,
    completion_tokens BIGINT NOT NULL,
    total_tokens BIGINT NOT NULL,
    usage_available BOOLEAN NOT NULL,
    input_cost_per_million NUMERIC(20, 8) NOT NULL,
    output_cost_per_million NUMERIC(20, 8) NOT NULL,
    pricing_available BOOLEAN NOT NULL,
    configured_cost NUMERIC(20, 8) NOT NULL,
    cost_available BOOLEAN NOT NULL,
    cost_unit VARCHAR(32) NOT NULL,
    duration_ms BIGINT NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),

    CONSTRAINT uk_rag_llm_usage_event_execution_ordinal
        UNIQUE (logical_execution_id, call_ordinal),
    CONSTRAINT ck_rag_llm_usage_event_ordinal
        CHECK (call_ordinal > 0),
    CONSTRAINT ck_rag_llm_usage_event_owner
        CHECK (char_length(owner_principal_id) BETWEEN 1 AND 128
            AND owner_principal_id !~ '[^ -~]'),
    CONSTRAINT ck_rag_llm_usage_event_session
        CHECK (char_length(session_id) BETWEEN 1 AND 255
            AND session_id !~ '[^ -~]'),
    CONSTRAINT ck_rag_llm_usage_event_trace
        CHECK (request_trace_id IS NULL
            OR (char_length(request_trace_id) BETWEEN 1 AND 128
                AND request_trace_id !~ '[^ -~]')),
    CONSTRAINT ck_rag_llm_usage_event_model
        CHECK (char_length(model_ref) BETWEEN 1 AND 255
            AND model_ref !~ '[^ -~]'),
    CONSTRAINT ck_rag_llm_usage_event_mode
        CHECK (chat_mode IN ('PLAIN', 'KNOWLEDGE', 'AGENT')),
    CONSTRAINT ck_rag_llm_usage_event_purpose
        CHECK (purpose IN ('CHAT', 'QUERY_TRANSFORM', 'QUERY_EXPAND', 'SUMMARY')),
    CONSTRAINT ck_rag_llm_usage_event_outcome
        CHECK (outcome IN ('SUCCEEDED', 'FAILED', 'CANCELLED')),
    CONSTRAINT ck_rag_llm_usage_event_tokens
        CHECK (
            prompt_tokens BETWEEN 0 AND 2147483647
            AND completion_tokens BETWEEN 0 AND 2147483647
            AND total_tokens BETWEEN 0 AND 4294967294
            AND (
                usage_available
                OR (prompt_tokens = 0 AND completion_tokens = 0 AND total_tokens = 0)
            )
        ),
    CONSTRAINT ck_rag_llm_usage_event_pricing
        CHECK (
            input_cost_per_million BETWEEN 0 AND 1000000
            AND output_cost_per_million BETWEEN 0 AND 1000000
            AND (
                pricing_available
                OR (input_cost_per_million = 0 AND output_cost_per_million = 0)
            )
        ),
    CONSTRAINT ck_rag_llm_usage_event_cost
        CHECK (
            configured_cost BETWEEN 0 AND 9999999999.99999999
            AND (
                cost_available
                OR configured_cost = 0
            )
            AND (
                NOT cost_available
                OR (usage_available AND pricing_available)
            )
        ),
    CONSTRAINT ck_rag_llm_usage_event_unit
        CHECK (char_length(cost_unit) BETWEEN 1 AND 32
            AND cost_unit !~ '[^ -~]'),
    CONSTRAINT ck_rag_llm_usage_event_duration
        CHECK (duration_ms BETWEEN 0 AND 86400000),
    CONSTRAINT ck_rag_llm_usage_event_time
        CHECK (completed_at >= started_at)
);

CREATE INDEX idx_rag_llm_usage_event_owner_started
    ON rag_llm_usage_event(owner_principal_id, started_at DESC);

CREATE INDEX idx_rag_llm_usage_event_owner_model_started
    ON rag_llm_usage_event(owner_principal_id, model_ref, started_at DESC);

CREATE INDEX idx_rag_llm_usage_event_started
    ON rag_llm_usage_event(started_at DESC);

COMMENT ON TABLE rag_llm_usage_event IS
    'Durable model invocation usage facts without prompts, answers, tool payloads, or credentials';
