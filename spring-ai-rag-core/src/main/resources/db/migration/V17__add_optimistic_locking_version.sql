-- Add optimistic locking version column to core mutable entities.
-- Hibernate @Version auto-increments on each update, throws OptimisticLockException on concurrent modification.

ALTER TABLE rag_collection ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE rag_documents ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE rag_alerts ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE rag_ab_experiments ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE rag_silence_schedules ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE rag_slo_configs ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE rag_user_feedback ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

-- Index on version column for monitoring queries (optional, useful for auditing)
CREATE INDEX IF NOT EXISTS idx_rag_collection_version ON rag_collection(version);
CREATE INDEX IF NOT EXISTS idx_rag_document_version ON rag_documents(version);
CREATE INDEX IF NOT EXISTS idx_rag_alert_version ON rag_alerts(version);
CREATE INDEX IF NOT EXISTS idx_rag_ab_experiment_version ON rag_ab_experiments(version);
CREATE INDEX IF NOT EXISTS idx_rag_silence_schedule_version ON rag_silence_schedules(version);
CREATE INDEX IF NOT EXISTS idx_rag_slo_config_version ON rag_slo_configs(version);
CREATE INDEX IF NOT EXISTS idx_rag_user_feedback_version ON rag_user_feedback(version);
