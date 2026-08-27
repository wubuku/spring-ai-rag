-- V54: bounded hourly operation observations for external integration support.

CREATE TABLE rag_api_operation_hourly (
    bucket_start TIMESTAMPTZ NOT NULL,
    principal_type VARCHAR(32) NOT NULL,
    principal_ref VARCHAR(64) NOT NULL,
    operation VARCHAR(64) NOT NULL,
    http_status SMALLINT NOT NULL,
    request_count BIGINT NOT NULL DEFAULT 0,
    duration_sum_ms NUMERIC(30, 0) NOT NULL DEFAULT 0,
    duration_max_ms BIGINT NOT NULL DEFAULT 0,
    le_25_ms_count BIGINT NOT NULL DEFAULT 0,
    le_50_ms_count BIGINT NOT NULL DEFAULT 0,
    le_100_ms_count BIGINT NOT NULL DEFAULT 0,
    le_250_ms_count BIGINT NOT NULL DEFAULT 0,
    le_500_ms_count BIGINT NOT NULL DEFAULT 0,
    le_1000_ms_count BIGINT NOT NULL DEFAULT 0,
    le_2500_ms_count BIGINT NOT NULL DEFAULT 0,
    le_5000_ms_count BIGINT NOT NULL DEFAULT 0,
    over_5000_ms_count BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),

    PRIMARY KEY (bucket_start, principal_type, principal_ref, operation, http_status),
    CONSTRAINT ck_rag_api_operation_hourly_bucket
        CHECK (bucket_start =
            date_trunc('hour', bucket_start AT TIME ZONE 'UTC') AT TIME ZONE 'UTC'),
    CONSTRAINT ck_rag_api_operation_hourly_principal_type
        CHECK (char_length(principal_type) BETWEEN 1 AND 32
            AND principal_type !~ '[^ -~]'),
    CONSTRAINT ck_rag_api_operation_hourly_principal_ref
        CHECK (char_length(principal_ref) BETWEEN 1 AND 64
            AND principal_ref !~ '[^ -~]'),
    CONSTRAINT ck_rag_api_operation_hourly_operation
        CHECK (operation IN (
            'INTEGRATION_CAPABILITIES', 'CURRENT_PRINCIPAL',
            'COLLECTION_LOOKUP', 'COLLECTION_READINESS',
            'JSON_RECORD_UPSERT', 'JSON_RECORD_BATCH_UPSERT',
            'JSON_RECORD_SEARCH', 'JSON_RECORD_LOOKUP',
            'JSON_RECORD_TOMBSTONE', 'SYNC_RUN_BEGIN',
            'SYNC_RUN_BATCH_UPSERT', 'SYNC_RUN_PREVIEW',
            'SYNC_RUN_COMPLETE', 'SYNC_RUN_ABORT', 'SYNC_RUN_GET',
            'SYNC_RUN_ITEMS', 'SYNC_RUN_LIST')),
    CONSTRAINT ck_rag_api_operation_hourly_status
        CHECK (http_status BETWEEN 100 AND 599),
    CONSTRAINT ck_rag_api_operation_hourly_counts
        CHECK (
            request_count >= 0
            AND duration_sum_ms >= 0
            AND duration_max_ms >= 0
            AND le_25_ms_count >= 0
            AND le_50_ms_count >= 0
            AND le_100_ms_count >= 0
            AND le_250_ms_count >= 0
            AND le_500_ms_count >= 0
            AND le_1000_ms_count >= 0
            AND le_2500_ms_count >= 0
            AND le_5000_ms_count >= 0
            AND over_5000_ms_count >= 0
        )
);

CREATE TABLE rag_api_collection_operation_hourly (
    bucket_start TIMESTAMPTZ NOT NULL,
    principal_type VARCHAR(32) NOT NULL,
    principal_ref VARCHAR(64) NOT NULL,
    collection_id BIGINT NOT NULL REFERENCES rag_collection(id),
    operation VARCHAR(64) NOT NULL,
    http_status SMALLINT NOT NULL,
    request_count BIGINT NOT NULL DEFAULT 0,
    duration_sum_ms NUMERIC(30, 0) NOT NULL DEFAULT 0,
    duration_max_ms BIGINT NOT NULL DEFAULT 0,
    le_25_ms_count BIGINT NOT NULL DEFAULT 0,
    le_50_ms_count BIGINT NOT NULL DEFAULT 0,
    le_100_ms_count BIGINT NOT NULL DEFAULT 0,
    le_250_ms_count BIGINT NOT NULL DEFAULT 0,
    le_500_ms_count BIGINT NOT NULL DEFAULT 0,
    le_1000_ms_count BIGINT NOT NULL DEFAULT 0,
    le_2500_ms_count BIGINT NOT NULL DEFAULT 0,
    le_5000_ms_count BIGINT NOT NULL DEFAULT 0,
    over_5000_ms_count BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),

    PRIMARY KEY (
        bucket_start, principal_type, principal_ref, collection_id,
        operation, http_status),
    CONSTRAINT ck_rag_api_collection_operation_hourly_bucket
        CHECK (bucket_start =
            date_trunc('hour', bucket_start AT TIME ZONE 'UTC') AT TIME ZONE 'UTC'),
    CONSTRAINT ck_rag_api_collection_operation_hourly_principal_type
        CHECK (char_length(principal_type) BETWEEN 1 AND 32
            AND principal_type !~ '[^ -~]'),
    CONSTRAINT ck_rag_api_collection_operation_hourly_principal_ref
        CHECK (char_length(principal_ref) BETWEEN 1 AND 64
            AND principal_ref !~ '[^ -~]'),
    CONSTRAINT ck_rag_api_collection_operation_hourly_operation
        CHECK (operation IN (
            'INTEGRATION_CAPABILITIES', 'CURRENT_PRINCIPAL',
            'COLLECTION_LOOKUP', 'COLLECTION_READINESS',
            'JSON_RECORD_UPSERT', 'JSON_RECORD_BATCH_UPSERT',
            'JSON_RECORD_SEARCH', 'JSON_RECORD_LOOKUP',
            'JSON_RECORD_TOMBSTONE', 'SYNC_RUN_BEGIN',
            'SYNC_RUN_BATCH_UPSERT', 'SYNC_RUN_PREVIEW',
            'SYNC_RUN_COMPLETE', 'SYNC_RUN_ABORT', 'SYNC_RUN_GET',
            'SYNC_RUN_ITEMS', 'SYNC_RUN_LIST')),
    CONSTRAINT ck_rag_api_collection_operation_hourly_status
        CHECK (http_status BETWEEN 100 AND 599),
    CONSTRAINT ck_rag_api_collection_operation_hourly_counts
        CHECK (
            request_count >= 0
            AND duration_sum_ms >= 0
            AND duration_max_ms >= 0
            AND le_25_ms_count >= 0
            AND le_50_ms_count >= 0
            AND le_100_ms_count >= 0
            AND le_250_ms_count >= 0
            AND le_500_ms_count >= 0
            AND le_1000_ms_count >= 0
            AND le_2500_ms_count >= 0
            AND le_5000_ms_count >= 0
            AND over_5000_ms_count >= 0
        )
);

CREATE INDEX idx_rag_api_operation_hourly_time
    ON rag_api_operation_hourly(bucket_start);

CREATE INDEX idx_rag_api_operation_hourly_principal_time
    ON rag_api_operation_hourly(principal_type, principal_ref, bucket_start);

CREATE INDEX idx_rag_api_collection_operation_hourly_time
    ON rag_api_collection_operation_hourly(bucket_start);

CREATE INDEX idx_rag_api_collection_operation_hourly_principal_time
    ON rag_api_collection_operation_hourly(
        principal_type, principal_ref, bucket_start);

CREATE INDEX idx_rag_api_collection_operation_hourly_collection_time
    ON rag_api_collection_operation_hourly(collection_id, bucket_start);

COMMENT ON TABLE rag_api_operation_hourly IS
    'Best-effort hourly HTTP operation totals without request payloads or credentials';

COMMENT ON TABLE rag_api_collection_operation_hourly IS
    'Best-effort authorized Collection contributions; not request totals';
