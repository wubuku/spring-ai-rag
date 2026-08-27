-- V57: managed API principal expiry alerts with durable dedupe and fair recovery scans.

ALTER TABLE rag_alerts
    ADD COLUMN dedupe_key VARCHAR(160),
    ADD COLUMN condition_state VARCHAR(32),
    ADD COLUMN state_version INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN notified_version INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp();

ALTER TABLE rag_alerts
    ADD CONSTRAINT ck_rag_alert_managed_condition_pair CHECK (
        (dedupe_key IS NULL AND condition_state IS NULL)
        OR (dedupe_key IS NOT NULL AND condition_state IS NOT NULL)
    ),
    ADD CONSTRAINT ck_rag_alert_condition_state CHECK (
        condition_state IS NULL
        OR condition_state IN ('WARNING', 'CRITICAL', 'EXPIRED')
    ),
    ADD CONSTRAINT ck_rag_alert_state_version CHECK (state_version >= 0),
    ADD CONSTRAINT ck_rag_alert_notified_version CHECK (
        notified_version >= 0 AND notified_version <= state_version
    );

CREATE UNIQUE INDEX uk_rag_alert_active_dedupe
    ON rag_alerts(dedupe_key)
    WHERE dedupe_key IS NOT NULL AND status = 'ACTIVE';

ALTER TABLE rag_api_principal
    ADD COLUMN expiry_alert_checked_at TIMESTAMP;

CREATE INDEX idx_rag_api_principal_expiry_alert_window
    ON rag_api_principal(expires_at, principal_id)
    WHERE revoked_at IS NULL AND expires_at IS NOT NULL;

CREATE INDEX idx_rag_api_principal_expiry_alert_scan
    ON rag_api_principal(expiry_alert_checked_at NULLS FIRST, principal_id);

COMMENT ON COLUMN rag_alerts.dedupe_key IS
    'Internal managed-condition identity; never contains credential material';
COMMENT ON COLUMN rag_alerts.condition_state IS
    'Current managed alert phase for durable lifecycle reconciliation';
COMMENT ON COLUMN rag_api_principal.expiry_alert_checked_at IS
    'Last successful expiry-alert reconciliation time for fair bounded scans';
