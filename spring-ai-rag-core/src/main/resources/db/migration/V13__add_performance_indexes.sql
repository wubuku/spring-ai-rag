-- V13: Add missing query performance indexes
-- Missing indexes identified by C23 @Indexed review

-- ============================================================
-- rag_collection.name: used in findByName (duplicate check before create)
-- ============================================================
CREATE INDEX IF NOT EXISTS idx_rag_col_name ON rag_collection (name);

-- ============================================================
-- rag_documents.document_type: used in findByDocumentType (filter by type)
-- ============================================================
CREATE INDEX IF NOT EXISTS idx_rag_doc_type ON rag_documents (document_type);

-- ============================================================
-- rag_documents.enabled: used in findByEnabled (filter active/inactive)
-- ============================================================
CREATE INDEX IF NOT EXISTS idx_rag_doc_enabled ON rag_documents (enabled);
