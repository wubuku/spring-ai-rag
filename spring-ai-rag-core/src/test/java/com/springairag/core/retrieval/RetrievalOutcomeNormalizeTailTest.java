package com.springairag.core.retrieval;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RetrievalOutcome 归一与序列化长尾（Batch 700，JaCoCo 驱动）：
 * 紧凑构造器对全 null 组件的回退、ofResults 对 null 输入的空回
 * 退、toMap 携带 branchStages/fusionStage/rerankStage。
 */
class RetrievalOutcomeNormalizeTailTest {

    @Test
    void compactConstructorNormalizesAllNullComponents() {
        RetrievalOutcome outcome = new RetrievalOutcome(
                null, null, null, null, null, null, null, null, null,
                null, null, 0L, 0);

        assertNotNull(outcome.traceId());
        assertTrue(outcome.results().isEmpty());
        assertTrue(outcome.effectiveQueries().isEmpty());
        assertTrue(outcome.scopeSummary().isEmpty());
        assertTrue(outcome.filterSummary().isEmpty());
        assertTrue(outcome.branchStages().isEmpty());
    }

    @Test
    void ofResultsTreatsNullInputAsNoCandidates() {
        RetrievalOutcome outcome = RetrievalOutcome.ofResults(null);

        assertNotNull(outcome.traceId());
        assertTrue(outcome.results().isEmpty());
        assertEquals(RetrievalOutcomeCodes.NO_CANDIDATES,
                outcome.outcomeCode());
    }

    @Test
    void toMapSerializesBranchFusionAndRerankStages() {
        RetrievalBranchStage vector = new RetrievalBranchStage(
                RetrievalBranchStage.VECTOR, "pgvector",
                RetrievalBranchStage.SUCCESS, 12L, 8, 5, null);
        RetrievalBranchStage fusion = new RetrievalBranchStage(
                RetrievalBranchStage.FUSION, "rrf",
                RetrievalBranchStage.SUCCESS, 3L, 13, 10, null);
        RetrievalBranchStage rerank = new RetrievalBranchStage(
                RetrievalBranchStage.RERANK, "heuristic",
                RetrievalBranchStage.SUCCESS, 7L, 10, 6, null);
        RetrievalOutcome outcome = new RetrievalOutcome(
                UUID.randomUUID(),
                List.of(),
                "查询",
                List.of(new RetrievalOutcome.QueryStat(0, 8)),
                Map.of("mode", "hybrid"),
                Map.of(),
                List.of(vector),
                fusion,
                rerank,
                RetrievalOutcomeCodes.RESULTS_RETURNED,
                null,
                42L,
                13);

        Map<String, Object> map = outcome.toMetadataMap();

        assertEquals(List.of(vector.toMap()), map.get("branchStages"));
        assertEquals(fusion.toMap(), map.get("fusionStage"));
        assertEquals(rerank.toMap(), map.get("rerankStage"));
        assertEquals(13, map.get("rawCandidateCount"));
    }
}
