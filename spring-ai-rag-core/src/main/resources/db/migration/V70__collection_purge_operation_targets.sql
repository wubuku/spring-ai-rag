CREATE TABLE rag_collection_purge_operation_targets (
    operation_id UUID NOT NULL
        REFERENCES rag_long_running_operations(id) ON DELETE RESTRICT,
    target_kind CHAR(1) NOT NULL
        CHECK (target_kind IN ('D', 'S', 'F', 'A', 'R', 'I')),
    target_key TEXT NOT NULL CHECK (target_key <> ''),
    PRIMARY KEY (operation_id, target_kind, target_key)
);

CREATE INDEX idx_rag_collection_purge_operation_targets_kind
    ON rag_collection_purge_operation_targets(operation_id, target_kind);
