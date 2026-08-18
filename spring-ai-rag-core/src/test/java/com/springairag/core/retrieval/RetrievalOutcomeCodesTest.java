package com.springairag.core.retrieval;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RetrievalOutcomeCodesTest {

    @Test
    void matchNoneAndBudgetTakePriority() {
        assertEquals(
                RetrievalOutcomeCodes.SCOPE_MATCH_NONE,
                RetrievalOutcomeCodes.resolve(
                        true, false, false, 0, null, null, 0, 0, false, 0)
                        .outcomeCode());
        assertEquals(
                RetrievalOutcomeCodes.RETRIEVAL_BUDGET_EXHAUSTED,
                RetrievalOutcomeCodes.resolve(
                        false, true, false, 0, null, null, 1, 1, false, 0)
                        .emptyReasonCode());
    }

    @Test
    void timeoutAndErrorWhenOtherBranchEmpty() {
        RetrievalBranchStage timeout = new RetrievalBranchStage(
                RetrievalBranchStage.VECTOR, "embedding",
                RetrievalBranchStage.TIMEOUT, 5000, 0, 0, "TIMEOUT");
        RetrievalBranchStage empty = new RetrievalBranchStage(
                RetrievalBranchStage.FULLTEXT, "pg_trgm",
                RetrievalBranchStage.SUCCESS, 10, 0, 0, null);
        assertEquals(
                RetrievalOutcomeCodes.VECTOR_TIMEOUT,
                RetrievalOutcomeCodes.resolve(
                        false, false, false, 0, timeout, empty, 2, 2, false, 0)
                        .emptyReasonCode());
    }

    @Test
    void probeAndMinScoreAndPartial() {
        RetrievalBranchStage vector = new RetrievalBranchStage(
                RetrievalBranchStage.VECTOR, "embedding",
                RetrievalBranchStage.SUCCESS, 12, 4, 2, null);
        RetrievalBranchStage fulltext = new RetrievalBranchStage(
                RetrievalBranchStage.FULLTEXT, "pg_trgm",
                RetrievalBranchStage.SUCCESS, 8, 0, 0, null);
        assertEquals(
                RetrievalOutcomeCodes.PARTIAL_VECTOR,
                RetrievalOutcomeCodes.resolve(
                        false, false, false, 2, vector, fulltext, 3, 3, false, 4)
                        .outcomeCode());
        assertNull(RetrievalOutcomeCodes.resolve(
                false, false, false, 2, vector, fulltext, 3, 3, false, 4)
                .emptyReasonCode());
        assertEquals(
                RetrievalOutcomeCodes.NO_ELIGIBLE_DOCUMENTS,
                RetrievalOutcomeCodes.resolve(
                        false, false, false, 0, vector, fulltext, 0, 0, false, 0)
                        .emptyReasonCode());
        assertEquals(
                RetrievalOutcomeCodes.NO_FRESH_EMBEDDINGS,
                RetrievalOutcomeCodes.resolve(
                        false, false, false, 0, vector, fulltext, 4, 0, false, 0)
                        .emptyReasonCode());
        assertEquals(
                RetrievalOutcomeCodes.BELOW_MIN_SCORE,
                RetrievalOutcomeCodes.resolve(
                        false, false, false, 0, vector, fulltext, 4, 4, false, 3)
                        .emptyReasonCode());
        assertEquals(
                RetrievalOutcomeCodes.DIAGNOSTIC_UNKNOWN,
                RetrievalOutcomeCodes.resolve(
                        false, false, false, 0, vector, fulltext, null, null, true, 0)
                        .emptyReasonCode());
    }
}
