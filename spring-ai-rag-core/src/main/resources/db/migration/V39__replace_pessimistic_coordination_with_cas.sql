-- V39: replace explicit pessimistic coordination with optimistic/CAS state.

ALTER TABLE rag_evaluation_suites
    ADD COLUMN next_version INTEGER NOT NULL DEFAULT 1;

UPDATE rag_evaluation_suites suite
SET next_version = versions.next_version
FROM (
    SELECT suite_id, COALESCE(MAX(version), 0) + 1 AS next_version
    FROM rag_evaluation_suite_versions
    GROUP BY suite_id
) versions
WHERE suite.id = versions.suite_id;

ALTER TABLE rag_evaluation_runs
    ADD COLUMN concurrency_slot INTEGER;

WITH ranked AS (
    SELECT id,
           ROW_NUMBER() OVER (
               PARTITION BY owner_principal_id
               ORDER BY created_at, id
           ) - 1 AS slot
    FROM rag_evaluation_runs
    WHERE status IN ('PENDING', 'RUNNING')
)
UPDATE rag_evaluation_runs run
SET concurrency_slot = ranked.slot
FROM ranked
WHERE run.id = ranked.id;

ALTER TABLE rag_evaluation_runs
    ADD CONSTRAINT rag_evaluation_runs_concurrency_slot_chk
    CHECK (concurrency_slot IS NULL OR concurrency_slot >= 0);

CREATE UNIQUE INDEX uq_rag_evaluation_active_owner_slot
    ON rag_evaluation_runs (owner_principal_id, concurrency_slot)
    WHERE status IN ('PENDING', 'RUNNING');

COMMENT ON COLUMN rag_evaluation_suites.next_version IS
    'Next immutable suite version allocated by an atomic counter update';

COMMENT ON COLUMN rag_evaluation_runs.concurrency_slot IS
    'Optimistic owner-local active-run slot; terminal runs release the slot';
