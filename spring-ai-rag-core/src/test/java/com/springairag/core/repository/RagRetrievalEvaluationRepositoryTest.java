package com.springairag.core.repository;

import com.springairag.core.entity.RagRetrievalEvaluation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * RagRetrievalEvaluationRepository Unit Tests (using Mock).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RagRetrievalEvaluationRepository Tests")
class RagRetrievalEvaluationRepositoryTest {

    @Mock
    private RagRetrievalEvaluationRepository repository;

    private RagRetrievalEvaluation createEvaluation(Long id, Double mrr, Double ndcg, Double hitRate,
                                                   String method, ZonedDateTime createdAt) {
        RagRetrievalEvaluation eval = new RagRetrievalEvaluation();
        eval.setId(id);
        eval.setQuery("test query " + id);
        eval.setExpectedDocumentIds("[\"doc1\",\"doc2\"]");
        eval.setRetrievedDocumentIds("[\"doc1\",\"doc3\"]");
        eval.setEvaluationResult(Map.of("precision", 0.8));
        eval.setPrecisionAtK(Map.of(5, 0.6, 10, 0.7));
        eval.setRecallAtK(Map.of(5, 0.5, 10, 0.6));
        eval.setMrr(mrr);
        eval.setNdcg(ndcg);
        eval.setHitRate(hitRate);
        eval.setEvaluationMethod(method);
        eval.setEvaluatorId("evaluator-1");
        eval.setMetadata(Map.of("model", "bge-m3"));
        eval.setCreatedAt(createdAt != null ? createdAt : ZonedDateTime.now());
        return eval;
    }

    @Nested
    @DisplayName("findByCreatedAtBetweenOrderByCreatedAtDesc")
    class FindByTimeRange {

        @Test
        @DisplayName("returns evaluations within time range")
        void returnsEvaluationsInRange() {
            ZonedDateTime start = ZonedDateTime.now().minusDays(1);
            ZonedDateTime end = ZonedDateTime.now();
            RagRetrievalEvaluation eval = createEvaluation(1L, 0.85, 0.92, 0.95, "AUTO", ZonedDateTime.now());
            when(repository.findByCreatedAtBetweenOrderByCreatedAtDesc(start, end)).thenReturn(List.of(eval));

            List<RagRetrievalEvaluation> result = repository.findByCreatedAtBetweenOrderByCreatedAtDesc(start, end);

            assertEquals(1, result.size());
            assertEquals(1L, result.get(0).getId());
            assertEquals(0.85, result.get(0).getMrr());
            verify(repository).findByCreatedAtBetweenOrderByCreatedAtDesc(start, end);
        }

        @Test
        @DisplayName("returns empty list when no evaluations in range")
        void returnsEmptyWhenNoEvaluations() {
            ZonedDateTime start = ZonedDateTime.now().minusDays(7);
            ZonedDateTime end = ZonedDateTime.now().minusDays(6);
            when(repository.findByCreatedAtBetweenOrderByCreatedAtDesc(start, end)).thenReturn(List.of());

            List<RagRetrievalEvaluation> result = repository.findByCreatedAtBetweenOrderByCreatedAtDesc(start, end);

            assertTrue(result.isEmpty());
            verify(repository).findByCreatedAtBetweenOrderByCreatedAtDesc(start, end);
        }

        @Test
        @DisplayName("returns multiple evaluations ordered by createdAt descending")
        void returnsMultipleOrderedByCreatedAtDesc() {
            ZonedDateTime now = ZonedDateTime.now();
            ZonedDateTime start = now.minusDays(1);
            ZonedDateTime end = now;
            RagRetrievalEvaluation eval1 = createEvaluation(1L, 0.8, 0.9, 0.85, "AUTO", now.minusHours(1));
            RagRetrievalEvaluation eval2 = createEvaluation(2L, 0.75, 0.88, 0.80, "MANUAL", now.minusHours(2));
            when(repository.findByCreatedAtBetweenOrderByCreatedAtDesc(start, end)).thenReturn(List.of(eval1, eval2));

            List<RagRetrievalEvaluation> result = repository.findByCreatedAtBetweenOrderByCreatedAtDesc(start, end);

            assertEquals(2, result.size());
            verify(repository).findByCreatedAtBetweenOrderByCreatedAtDesc(start, end);
        }
    }

    @Nested
    @DisplayName("findAllByOrderByCreatedAtDesc (paginated)")
    class FindAllPaginated {

        @Test
        @DisplayName("returns paginated results")
        void returnsPaginatedResults() {
            Pageable pageable = PageRequest.of(0, 10);
            RagRetrievalEvaluation eval = createEvaluation(1L, 0.85, 0.92, 0.95, "AUTO", ZonedDateTime.now());
            Page<RagRetrievalEvaluation> page = new PageImpl<>(List.of(eval), pageable, 1);
            when(repository.findAllByOrderByCreatedAtDesc(pageable)).thenReturn(page);

            Page<RagRetrievalEvaluation> result = repository.findAllByOrderByCreatedAtDesc(pageable);

            assertEquals(1, result.getTotalElements());
            assertEquals(1, result.getContent().size());
            assertEquals(1, result.getTotalPages());
            verify(repository).findAllByOrderByCreatedAtDesc(pageable);
        }

        @Test
        @DisplayName("returns empty page when no evaluations")
        void returnsEmptyPage() {
            Pageable pageable = PageRequest.of(0, 10);
            Page<RagRetrievalEvaluation> page = new PageImpl<>(List.of(), pageable, 0);
            when(repository.findAllByOrderByCreatedAtDesc(pageable)).thenReturn(page);

            Page<RagRetrievalEvaluation> result = repository.findAllByOrderByCreatedAtDesc(pageable);

            assertTrue(result.isEmpty());
            assertEquals(0, result.getTotalElements());
            verify(repository).findAllByOrderByCreatedAtDesc(pageable);
        }

        @Test
        @DisplayName("returns second page correctly")
        void returnsSecondPage() {
            Pageable pageable = PageRequest.of(1, 10);
            Page<RagRetrievalEvaluation> page = new PageImpl<>(List.of(), pageable, 15);
            when(repository.findAllByOrderByCreatedAtDesc(pageable)).thenReturn(page);

            Page<RagRetrievalEvaluation> result = repository.findAllByOrderByCreatedAtDesc(pageable);

            assertEquals(15, result.getTotalElements());
            assertEquals(1, result.getNumber());
            assertEquals(2, result.getTotalPages());
            verify(repository).findAllByOrderByCreatedAtDesc(pageable);
        }
    }

    @Nested
    @DisplayName("countByCreatedAtBetween")
    class CountByTimeRange {

        @Test
        @DisplayName("returns count of evaluations in range")
        void returnsCorrectCount() {
            ZonedDateTime start = ZonedDateTime.now().minusDays(1);
            ZonedDateTime end = ZonedDateTime.now();
            when(repository.countByCreatedAtBetween(start, end)).thenReturn(5L);

            long count = repository.countByCreatedAtBetween(start, end);

            assertEquals(5L, count);
            verify(repository).countByCreatedAtBetween(start, end);
        }

        @Test
        @DisplayName("returns zero when no evaluations in range")
        void returnsZeroWhenEmpty() {
            ZonedDateTime start = ZonedDateTime.now().minusYears(1);
            ZonedDateTime end = ZonedDateTime.now().minusYears(1).plusDays(1);
            when(repository.countByCreatedAtBetween(start, end)).thenReturn(0L);

            long count = repository.countByCreatedAtBetween(start, end);

            assertEquals(0L, count);
            verify(repository).countByCreatedAtBetween(start, end);
        }
    }

    @Nested
    @DisplayName("findAvgMrr (JPQL AVG query)")
    class FindAvgMrr {

        @Test
        @DisplayName("returns average MRR within time range")
        void returnsAverageMrr() {
            ZonedDateTime start = ZonedDateTime.now().minusDays(1);
            ZonedDateTime end = ZonedDateTime.now();
            when(repository.findAvgMrr(start, end)).thenReturn(0.8125);

            Double avgMrr = repository.findAvgMrr(start, end);

            assertEquals(0.8125, avgMrr);
            verify(repository).findAvgMrr(start, end);
        }

        @Test
        @DisplayName("returns null when no evaluations with non-null MRR in range")
        void returnsNullWhenNoMrr() {
            ZonedDateTime start = ZonedDateTime.now().minusDays(1);
            ZonedDateTime end = ZonedDateTime.now();
            when(repository.findAvgMrr(start, end)).thenReturn(null);

            Double avgMrr = repository.findAvgMrr(start, end);

            assertNull(avgMrr);
            verify(repository).findAvgMrr(start, end);
        }
    }

    @Nested
    @DisplayName("findAvgNdcg (JPQL AVG query)")
    class FindAvgNdcg {

        @Test
        @DisplayName("returns average NDCG within time range")
        void returnsAverageNdcg() {
            ZonedDateTime start = ZonedDateTime.now().minusDays(1);
            ZonedDateTime end = ZonedDateTime.now();
            when(repository.findAvgNdcg(start, end)).thenReturn(0.8875);

            Double avgNdcg = repository.findAvgNdcg(start, end);

            assertEquals(0.8875, avgNdcg);
            verify(repository).findAvgNdcg(start, end);
        }

        @Test
        @DisplayName("returns null when no evaluations with non-null NDCG in range")
        void returnsNullWhenNoNdcg() {
            ZonedDateTime start = ZonedDateTime.now().minusDays(1);
            ZonedDateTime end = ZonedDateTime.now();
            when(repository.findAvgNdcg(start, end)).thenReturn(null);

            Double avgNdcg = repository.findAvgNdcg(start, end);

            assertNull(avgNdcg);
            verify(repository).findAvgNdcg(start, end);
        }
    }

    @Nested
    @DisplayName("findAvgHitRate (JPQL AVG query)")
    class FindAvgHitRate {

        @Test
        @DisplayName("returns average Hit Rate within time range")
        void returnsAverageHitRate() {
            ZonedDateTime start = ZonedDateTime.now().minusDays(1);
            ZonedDateTime end = ZonedDateTime.now();
            when(repository.findAvgHitRate(start, end)).thenReturn(0.925);

            Double avgHitRate = repository.findAvgHitRate(start, end);

            assertEquals(0.925, avgHitRate);
            verify(repository).findAvgHitRate(start, end);
        }

        @Test
        @DisplayName("returns null when no evaluations with non-null Hit Rate in range")
        void returnsNullWhenNoHitRate() {
            ZonedDateTime start = ZonedDateTime.now().minusDays(1);
            ZonedDateTime end = ZonedDateTime.now();
            when(repository.findAvgHitRate(start, end)).thenReturn(null);

            Double avgHitRate = repository.findAvgHitRate(start, end);

            assertNull(avgHitRate);
            verify(repository).findAvgHitRate(start, end);
        }
    }

    @Nested
    @DisplayName("JpaRepository standard methods")
    class StandardJpaMethods {

        @Test
        @DisplayName("save persists evaluation and returns it")
        void savePersistsAndReturns() {
            RagRetrievalEvaluation eval = createEvaluation(null, 0.8, 0.9, 0.85, "AUTO", ZonedDateTime.now());
            RagRetrievalEvaluation saved = createEvaluation(1L, 0.8, 0.9, 0.85, "AUTO", ZonedDateTime.now());
            when(repository.save(eval)).thenReturn(saved);

            RagRetrievalEvaluation result = repository.save(eval);

            assertEquals(1L, result.getId());
            verify(repository).save(eval);
        }

        @Test
        @DisplayName("findById returns evaluation when exists")
        void findByIdReturnsWhenExists() {
            RagRetrievalEvaluation eval = createEvaluation(42L, 0.8, 0.9, 0.85, "AUTO", ZonedDateTime.now());
            when(repository.findById(42L)).thenReturn(java.util.Optional.of(eval));

            java.util.Optional<RagRetrievalEvaluation> result = repository.findById(42L);

            assertTrue(result.isPresent());
            assertEquals(42L, result.get().getId());
            verify(repository).findById(42L);
        }

        @Test
        @DisplayName("findById returns empty when not exists")
        void findByIdReturnsEmptyWhenNotExists() {
            when(repository.findById(999L)).thenReturn(java.util.Optional.empty());

            java.util.Optional<RagRetrievalEvaluation> result = repository.findById(999L);

            assertFalse(result.isPresent());
            verify(repository).findById(999L);
        }

        @Test
        @DisplayName("deleteById removes evaluation")
        void deleteByIdRemoves() {
            doNothing().when(repository).deleteById(1L);

            repository.deleteById(1L);

            verify(repository).deleteById(1L);
        }

        @Test
        @DisplayName("existsById returns true when exists")
        void existsByIdReturnsTrue() {
            when(repository.existsById(1L)).thenReturn(true);

            boolean exists = repository.existsById(1L);

            assertTrue(exists);
            verify(repository).existsById(1L);
        }

        @Test
        @DisplayName("count returns total number of evaluations")
        void countReturnsTotal() {
            when(repository.count()).thenReturn(100L);

            long count = repository.count();

            assertEquals(100L, count);
            verify(repository).count();
        }
    }

    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("handles evaluations with all null metrics")
        void handlesAllNullMetrics() {
            ZonedDateTime start = ZonedDateTime.now().minusDays(1);
            ZonedDateTime end = ZonedDateTime.now();
            RagRetrievalEvaluation eval = createEvaluation(1L, null, null, null, "AUTO", ZonedDateTime.now());
            when(repository.findByCreatedAtBetweenOrderByCreatedAtDesc(start, end)).thenReturn(List.of(eval));

            List<RagRetrievalEvaluation> result = repository.findByCreatedAtBetweenOrderByCreatedAtDesc(start, end);

            assertEquals(1, result.size());
            assertNull(result.get(0).getMrr());
            assertNull(result.get(0).getNdcg());
            assertNull(result.get(0).getHitRate());
        }

        @Test
        @DisplayName("handles different evaluation methods")
        void handlesDifferentEvaluationMethods() {
            ZonedDateTime now = ZonedDateTime.now();
            ZonedDateTime start = now.minusDays(1);
            ZonedDateTime end = now;
            RagRetrievalEvaluation auto = createEvaluation(1L, 0.8, 0.9, 0.85, "AUTO", now);
            RagRetrievalEvaluation manual = createEvaluation(2L, 0.7, 0.8, 0.75, "MANUAL", now);
            RagRetrievalEvaluation llm = createEvaluation(3L, 0.85, 0.93, 0.9, "LLM", now);
            when(repository.findByCreatedAtBetweenOrderByCreatedAtDesc(start, end)).thenReturn(List.of(auto, manual, llm));

            List<RagRetrievalEvaluation> result = repository.findByCreatedAtBetweenOrderByCreatedAtDesc(start, end);

            assertEquals(3, result.size());
            assertEquals("AUTO", result.get(0).getEvaluationMethod());
            assertEquals("MANUAL", result.get(1).getEvaluationMethod());
            assertEquals("LLM", result.get(2).getEvaluationMethod());
        }
    }
}
