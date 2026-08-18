-- V36: accelerate ordinary-document metadata containment filters.
CREATE INDEX IF NOT EXISTS idx_rag_documents_metadata_path_ops
    ON rag_documents USING GIN (metadata jsonb_path_ops)
    WHERE enabled = true
      AND metadata IS NOT NULL;

COMMENT ON INDEX idx_rag_documents_metadata_path_ops IS
    'GIN jsonb_path_ops index for document metadata containment (@>)';
