-- Add role column to rag_api_key for permission分层
-- First key created (by creation time) becomes ADMIN, rest are NORMAL

ALTER TABLE rag_api_key ADD COLUMN IF NOT EXISTS role VARCHAR(20) NOT NULL DEFAULT 'NORMAL';

-- Set the earliest key as ADMIN (all others stay NORMAL by default)
-- This is safe: if there was only one key, it becomes admin
DO $$
DECLARE
    first_key_id BIGINT;
BEGIN
    SELECT id INTO first_key_id
    FROM rag_api_key
    ORDER BY created_at ASC
    LIMIT 1;

    IF first_key_id IS NOT NULL THEN
        UPDATE rag_api_key SET role = 'ADMIN' WHERE id = first_key_id;
    END IF;
END $$;

-- Index for fast role-based queries
CREATE INDEX IF NOT EXISTS idx_rag_api_key_role ON rag_api_key (role);
