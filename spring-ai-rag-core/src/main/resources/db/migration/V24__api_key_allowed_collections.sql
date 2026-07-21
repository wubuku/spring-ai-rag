-- V24: API key allowed collection IDs (minimal ACL)
-- NULL / empty = unrestricted (backward compatible). ADMIN keys always unrestricted in app logic.
ALTER TABLE rag_api_key ADD COLUMN IF NOT EXISTS allowed_collection_ids VARCHAR(2048);

COMMENT ON COLUMN rag_api_key.allowed_collection_ids IS
  'Comma-separated collection IDs this key may access; null/blank = all collections';
