-- V58: durable alert notification outbox and provider delivery receipts.

CREATE TABLE rag_alert_notification_delivery (
    id UUID PRIMARY KEY,
    alert_id BIGINT NOT NULL,
    notification_version INTEGER NOT NULL,
    managed_condition BOOLEAN NOT NULL,
    provider VARCHAR(32) NOT NULL,
    status VARCHAR(24) NOT NULL,
    payload JSONB NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    attempt_budget INTEGER NOT NULL,
    manual_retry_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    lease_token UUID,
    lease_until TIMESTAMPTZ,
    last_error_code VARCHAR(64),
    last_http_status INTEGER,
    last_attempt_at TIMESTAMPTZ,
    delivered_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT uk_rag_alert_notification_delivery
        UNIQUE (alert_id, notification_version, provider),
    CONSTRAINT ck_rag_alert_notification_version
        CHECK (notification_version >= 1),
    CONSTRAINT ck_rag_alert_notification_provider
        CHECK (provider IN ('EMAIL', 'DINGTALK')),
    CONSTRAINT ck_rag_alert_notification_status
        CHECK (status IN (
            'PENDING', 'IN_PROGRESS', 'RETRY_WAIT',
            'DELIVERED', 'FAILED', 'SUPERSEDED'
        )),
    CONSTRAINT ck_rag_alert_notification_attempts
        CHECK (
            attempt_count >= 0
            AND manual_retry_count >= 0
            AND attempt_budget >= attempt_count
        ),
    CONSTRAINT ck_rag_alert_notification_lease
        CHECK (
            (
                status = 'IN_PROGRESS'
                AND lease_token IS NOT NULL
                AND lease_until IS NOT NULL
            )
            OR
            (
                status <> 'IN_PROGRESS'
                AND lease_token IS NULL
                AND lease_until IS NULL
            )
        ),
    CONSTRAINT ck_rag_alert_notification_delivered
        CHECK (
            (status = 'DELIVERED' AND delivered_at IS NOT NULL)
            OR (status <> 'DELIVERED' AND delivered_at IS NULL)
        )
);

CREATE INDEX idx_rag_alert_notification_eligible
    ON rag_alert_notification_delivery(next_attempt_at, id)
    WHERE status IN ('PENDING', 'RETRY_WAIT');

CREATE INDEX idx_rag_alert_notification_expired_lease
    ON rag_alert_notification_delivery(lease_until, id)
    WHERE status = 'IN_PROGRESS';

CREATE INDEX idx_rag_alert_notification_query
    ON rag_alert_notification_delivery(created_at DESC, id DESC);

CREATE INDEX idx_rag_alert_notification_provider_status
    ON rag_alert_notification_delivery(provider, status, created_at DESC, id DESC);

COMMENT ON TABLE rag_alert_notification_delivery IS
    'Durable at-least-once alert provider delivery ledger';
COMMENT ON COLUMN rag_alert_notification_delivery.alert_id IS
    'Immutable historical alert reference without cascading retention';
COMMENT ON COLUMN rag_alert_notification_delivery.payload IS
    'Bounded sanitized provider payload without channel credentials';
