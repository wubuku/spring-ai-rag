-- Add soft-delete support to rag_collection
-- The RagCollection entity has deleted/deletedAt fields for soft delete support

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                  WHERE table_name = 'rag_collection' AND column_name = 'deleted') THEN
        ALTER TABLE rag_collection ADD COLUMN deleted BOOLEAN NOT NULL DEFAULT false;
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                  WHERE table_name = 'rag_collection' AND column_name = 'deleted_at') THEN
        ALTER TABLE rag_collection ADD COLUMN deleted_at TIMESTAMP(6);
    END IF;
END $$;

-- Add index for efficient soft-delete queries
CREATE INDEX IF NOT EXISTS idx_rag_collection_deleted ON rag_collection (deleted) WHERE deleted = false;
