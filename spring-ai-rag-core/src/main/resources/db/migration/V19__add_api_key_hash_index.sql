-- ============================================================
-- V19: Add unique index on key_hash for O(1) API key lookup
-- ============================================================
-- validateKeyEntity() uses key_hash as the lookup key.
-- An index makes key validation O(log n) instead of O(n) scan.
-- ============================================================

CREATE UNIQUE INDEX IF NOT EXISTS idx_rag_api_key_hash ON rag_api_key(key_hash);
