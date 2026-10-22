CREATE TABLE rag_collection_purge_worker_slots (
    slot_id SMALLINT PRIMARY KEY CHECK (slot_id BETWEEN 1 AND 8),
    operation_id UUID UNIQUE
        REFERENCES rag_long_running_operations(id) ON DELETE RESTRICT,
    lease_owner VARCHAR(128),
    lease_token UUID,
    lease_expires_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT ck_rag_collection_purge_worker_slot_lease CHECK (
        (operation_id IS NULL AND lease_owner IS NULL
            AND lease_token IS NULL AND lease_expires_at IS NULL)
        OR (operation_id IS NOT NULL AND lease_owner IS NOT NULL
            AND lease_token IS NOT NULL AND lease_expires_at IS NOT NULL)
    )
);

INSERT INTO rag_collection_purge_worker_slots (slot_id)
SELECT generate_series(1, 8);
