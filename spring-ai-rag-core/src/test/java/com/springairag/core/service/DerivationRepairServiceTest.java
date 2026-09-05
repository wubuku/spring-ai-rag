package com.springairag.core.service;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DerivationRepairService} 的纯决策逻辑单测：preview 计划的
 * action 判定矩阵与选择白名单校验（主链由 gated IT 与 WebTest 覆盖）。
 */
class DerivationRepairServiceTest {

    private final DerivationRepairService service = new DerivationRepairService(
            null, null, null, null, null, null, null, null, null,
            new com.springairag.core.config.RagProperties(), null);

    private DerivationIntegrityRepository.Snapshot snapshot(
            boolean enabled,
            boolean tombstoned,
            String bucket,
            boolean localFresh,
            String vectorCondition,
            boolean vectorFresh) {
        Instant now = Instant.now();
        return new DerivationIntegrityRepository.Snapshot(
                1L, "doc", 1L, 1L, "hash", enabled, tombstoned, "default", null,
                "chunker", "READY", "hash", "chunker", 1L, 2, 2,
                null, localFresh, false,
                "COMPLETED", "hash", "chunker", 1L, 2, 2,
                null, UUID.randomUUID(), "COMPLETED", vectorFresh, false,
                bucket, "READY", vectorCondition, null);
    }

    @Test
    void tombstonedDocumentIsNotPlanned() {
        assertNull(service.plan(
                snapshot(false, true, "READY", false, "STALE", false)));
    }

    @Test
    void disabledDocumentIsNotPlanned() {
        assertNull(service.plan(
                snapshot(false, false, "READY", false, "STALE", false)));
    }

    @Test
    void indexingBucketIsNeverPlanned() {
        assertNull(service.plan(
                snapshot(true, false, "INDEXING", false, "STALE", false)));
    }

    @Test
    void staleLocalAndVectorPlanFullRebuild() {
        DerivationRepairService.PlanItem plan = service.plan(
                snapshot(true, false, "READY", false, "STALE", false));
        assertEquals("REBUILD_LOCAL_AND_QUEUE_VECTOR", plan.action());
        assertTrue(plan.localAction());
        assertTrue(plan.vectorAction());
    }

    @Test
    void staleLocalOnlyPlansLocalRebuild() {
        DerivationRepairService.PlanItem plan = service.plan(
                snapshot(true, false, "READY", false, "READY", true));
        assertEquals("REBUILD_LOCAL", plan.action());
        assertTrue(plan.localAction());
        assertEquals(false, plan.vectorAction());
    }

    @Test
    void staleVectorOnlyPlansVectorQueue() {
        DerivationRepairService.PlanItem plan = service.plan(
                snapshot(true, false, "READY", true, "STALE", false));
        assertEquals("QUEUE_VECTOR", plan.action());
        assertEquals(false, plan.localAction());
        assertTrue(plan.vectorAction());
    }

    @Test
    void indexingVectorIsTreatedAsFreshAndNotPlanned() {
        assertNull(service.plan(
                snapshot(true, false, "READY", true, "INDEXING", false)));
    }

    @Test
    void validateSelectionRejectsEmptyBuckets() {
        assertThrows(IllegalArgumentException.class,
                () -> DerivationRepairService.validateSelection(
                        Set.of(), Set.of("READY")));
    }

    @Test
    void validateSelectionRejectsUnsupportedValues() {
        assertThrows(IllegalArgumentException.class,
                () -> DerivationRepairService.validateSelection(
                        Set.of("NOT_A_BUCKET"), Set.of()));
        assertThrows(IllegalArgumentException.class,
                () -> DerivationRepairService.validateSelection(
                        Set.of("READY"), Set.of("NOT_A_CONDITION")));
    }

    @Test
    void validateSelectionAcceptsLegalCombinations() {
        DerivationRepairService.validateSelection(
                Set.of("READY", "KEYWORD_ONLY", "CORRUPT"),
                Set.of("READY", "STALE", "FAILED"));
    }

    @Test
    void upperSetNormalizesValues() {
        assertEquals(
                Set.of("READY", "STALE"),
                DerivationRepairService.upperSet(List.of("ready", "STALE")));
    }
}
