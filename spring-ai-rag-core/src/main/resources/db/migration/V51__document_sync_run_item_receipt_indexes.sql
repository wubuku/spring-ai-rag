-- V51: bounded cursor queries for durable document sync run item receipts.

CREATE INDEX idx_rag_sync_run_item_cursor
    ON rag_document_sync_run_items(run_id, seen_at, external_id);

CREATE INDEX idx_rag_sync_run_item_status_cursor
    ON rag_document_sync_run_items(run_id, status, seen_at, external_id);
