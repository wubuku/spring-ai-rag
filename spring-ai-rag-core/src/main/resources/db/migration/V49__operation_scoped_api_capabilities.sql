-- V49: Persisted operation capabilities for managed API principals.

ALTER TABLE rag_api_principal
    ADD COLUMN capabilities VARCHAR(64) NOT NULL DEFAULT 'RAG_READ,RAG_WRITE';

ALTER TABLE rag_api_principal
    ADD CONSTRAINT ck_rag_api_principal_capabilities
    CHECK (capabilities IN ('RAG_READ', 'RAG_READ,RAG_WRITE'));
