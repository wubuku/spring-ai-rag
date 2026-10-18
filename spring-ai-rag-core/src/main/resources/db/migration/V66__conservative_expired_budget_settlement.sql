INSERT INTO rag_llm_budget_window (
    principal_id, window_kind, window_start, settled_tokens, settled_cost)
SELECT principal_id, window_kind, window_start,
       SUM(estimated_tokens), SUM(estimated_cost)
FROM (
    SELECT principal_id, 'DAY' AS window_kind, day_window_start AS window_start,
           estimated_tokens, estimated_cost
    FROM rag_llm_budget_reservation WHERE state = 'EXPIRED'
    UNION ALL
    SELECT principal_id, 'MONTH', month_window_start, estimated_tokens, estimated_cost
    FROM rag_llm_budget_reservation WHERE state = 'EXPIRED'
) expired
GROUP BY principal_id, window_kind, window_start
ON CONFLICT (principal_id, window_kind, window_start) DO UPDATE
SET settled_tokens = rag_llm_budget_window.settled_tokens + EXCLUDED.settled_tokens,
    settled_cost = rag_llm_budget_window.settled_cost + EXCLUDED.settled_cost,
    version = rag_llm_budget_window.version + 1,
    updated_at = clock_timestamp();

UPDATE rag_llm_budget_reservation
SET actual_tokens = estimated_tokens, actual_cost = estimated_cost,
    version = version + 1, updated_at = clock_timestamp()
WHERE state = 'EXPIRED';
