-- Add api_key column for raw key storage (shown only once at creation)
ALTER TABLE rag_api_key ADD COLUMN IF NOT EXISTS api_key VARCHAR(128);

-- Index for fast key lookup (the raw key used for validation)
CREATE INDEX IF NOT EXISTS idx_rag_api_key_api_key ON rag_api_key (api_key);
