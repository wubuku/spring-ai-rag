-- V38: 受管质量套件 / version / run
CREATE TABLE rag_evaluation_suites (
    id UUID PRIMARY KEY,
    suite_key VARCHAR(128) NOT NULL,
    name VARCHAR(255) NOT NULL,
    owner_principal_id VARCHAR(128) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (owner_principal_id, suite_key)
);

CREATE TABLE rag_evaluation_suite_versions (
    id UUID PRIMARY KEY,
    suite_id UUID NOT NULL REFERENCES rag_evaluation_suites (id) ON DELETE CASCADE,
    version INTEGER NOT NULL,
    definition JSONB NOT NULL,
    definition_sha256 CHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (suite_id, version),
    UNIQUE (suite_id, definition_sha256)
);

CREATE TABLE rag_evaluation_runs (
    id UUID PRIMARY KEY,
    suite_version_id UUID NOT NULL REFERENCES rag_evaluation_suite_versions (id) ON DELETE CASCADE,
    owner_principal_id VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL,
    configuration_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb,
    code_revision VARCHAR(128),
    embedding_profile_key VARCHAR(128),
    aggregate_metrics JSONB,
    lease_owner VARCHAR(64),
    lease_expires_at TIMESTAMP WITH TIME ZONE,
    started_at TIMESTAMP WITH TIME ZONE,
    finished_at TIMESTAMP WITH TIME ZONE,
    error VARCHAR(1000),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT rag_evaluation_runs_status_chk CHECK (status IN (
        'PENDING', 'RUNNING', 'PASSED', 'FAILED', 'SKIPPED',
        'RUN_INTERRUPTED', 'CORPUS_CHANGED'
    ))
);

CREATE INDEX idx_rag_evaluation_runs_status_created
    ON rag_evaluation_runs (status, created_at DESC);

CREATE TABLE rag_evaluation_case_results (
    run_id UUID NOT NULL REFERENCES rag_evaluation_runs (id) ON DELETE CASCADE,
    variant_key VARCHAR(64) NOT NULL,
    case_id VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL,
    retrieved_identities JSONB,
    metrics JSONB,
    latency_ms INTEGER,
    trace_id UUID,
    error_code VARCHAR(64),
    PRIMARY KEY (run_id, variant_key, case_id)
);

COMMENT ON TABLE rag_evaluation_suites IS
    '受管检索质量套件；suite_key 仅在 owner principal 内唯一';
COMMENT ON TABLE rag_evaluation_suite_versions IS
    '不可变 suite 定义与 canonical SHA-256';
COMMENT ON TABLE rag_evaluation_runs IS
    '有界 worker 执行的 suite version run';
