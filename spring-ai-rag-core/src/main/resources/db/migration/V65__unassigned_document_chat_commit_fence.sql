-- V65: reserve durable Chat commits for documents without a Collection.
-- The fence is internal and intentionally separate from document_revision,
-- which remains the public mutation/delete concurrency contract.

ALTER TABLE rag_documents
    ADD COLUMN IF NOT EXISTS chat_commit_fence_version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE rag_documents
    DROP CONSTRAINT IF EXISTS ck_rag_document_chat_commit_fence_version;

ALTER TABLE rag_documents
    ADD CONSTRAINT ck_rag_document_chat_commit_fence_version
        CHECK (chat_commit_fence_version >= 0);

COMMENT ON COLUMN rag_documents.chat_commit_fence_version IS
    'Internal CAS fence for durable Chat commits, independent of document_revision';
