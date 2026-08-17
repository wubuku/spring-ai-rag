-- V34: accelerate JSON structured-record payload containment filters.
CREATE INDEX IF NOT EXISTS idx_rag_documents_jsonb_payload_path_ops
    ON rag_documents USING GIN (jsonb_payload jsonb_path_ops)
    WHERE document_type = 'json-record'
      AND enabled = true
      AND jsonb_payload IS NOT NULL;

COMMENT ON INDEX idx_rag_documents_jsonb_payload_path_ops IS
    'GIN jsonb_path_ops index for json-record payload containment (@>)';
