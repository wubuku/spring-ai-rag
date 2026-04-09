-- Add optimistic locking version column to core mutable entities.
-- Hibernate @Version auto-increments on each update, throws OptimisticLockException on concurrent modification.

ALTER TABLE rag_collection ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE rag_document ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE rag_alert ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE rag_ab_experiment ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE rag_silence_schedule ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE rag_slo_config ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE rag_user_feedback ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

-- Index on version column for monitoring queries (optional, useful for auditing)
CREATE INDEX IF NOT EXISTS idx_rag_collection_version ON rag_collection(version);
CREATE INDEX IF NOT EXISTS idx_rag_document_version ON rag_document(version);
CREATE INDEX IF NOT EXISTS idx_rag_alert_version ON rag_alert(version);
CREATE INDEX IF NOT EXISTS idx_rag_ab_experiment_version ON rag_ab_experiment(version);
CREATE INDEX IF NOT EXISTS idx_rag_silence_schedule_version ON rag_silence_schedule(version);
CREATE INDEX IF NOT EXISTS idx_rag_slo_config_version ON rag_slo_config(version);
CREATE INDEX IF NOT EXISTS idx_rag_user_feedback_version ON rag_user_feedback(version);
