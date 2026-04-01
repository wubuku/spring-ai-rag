-- A/B 测试实验表
CREATE TABLE rag_ab_experiments (
    id              BIGSERIAL PRIMARY KEY,
    experiment_name VARCHAR(255) NOT NULL UNIQUE,
    description     TEXT,
    status          VARCHAR(50)  NOT NULL DEFAULT 'DRAFT',
    traffic_split   JSONB        NOT NULL,
    target_metric   VARCHAR(100),
    start_time      TIMESTAMPTZ,
    end_time        TIMESTAMPTZ,
    min_sample_size INTEGER      DEFAULT 100,
    metadata        JSONB,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ
);

CREATE INDEX idx_ab_experiments_status ON rag_ab_experiments (status);
CREATE INDEX idx_ab_experiments_name ON rag_ab_experiments (experiment_name);

-- A/B 测试结果表
CREATE TABLE rag_ab_results (
    id                     BIGSERIAL PRIMARY KEY,
    experiment_id          BIGINT     NOT NULL REFERENCES rag_ab_experiments (id),
    variant_name           VARCHAR(100) NOT NULL,
    session_id             VARCHAR(255) NOT NULL,
    query                  TEXT       NOT NULL,
    retrieved_document_ids TEXT,
    metrics                JSONB,
    is_converted           BOOLEAN    DEFAULT FALSE,
    created_at             TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_ab_results_experiment ON rag_ab_results (experiment_id);
CREATE INDEX idx_ab_results_session ON rag_ab_results (session_id, experiment_id);
CREATE INDEX idx_ab_results_variant ON rag_ab_results (experiment_id, variant_name);
