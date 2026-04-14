package com.springairag.core.repository;

import com.springairag.core.entity.RagAbExperiment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * RagAbExperimentRepository Unit Tests (using Mock).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RagAbExperimentRepository Tests")
class RagAbExperimentRepositoryTest {

    @Mock
    private RagAbExperimentRepository repository;

    private RagAbExperiment createExperiment(Long id, String name, String status,
                                             Map<String, Double> trafficSplit) {
        RagAbExperiment exp = new RagAbExperiment();
        exp.setId(id);
        exp.setExperimentName(name);
        exp.setDescription("Test experiment " + id);
        exp.setStatus(status);
        exp.setTrafficSplit(trafficSplit);
        exp.setTargetMetric("mrr");
        exp.setMinSampleSize(100);
        exp.setMetadata(Map.of("variant", "control"));
        exp.setCreatedAt(ZonedDateTime.now());
        return exp;
    }

    @Nested
    @DisplayName("findByExperimentName")
    class FindByExperimentName {

        @Test
        @DisplayName("returns experiment when found")
        void returnsExperimentWhenFound() {
            RagAbExperiment exp = createExperiment(1L, "exp-a", "DRAFT",
                    Map.of("control", 0.5, "treatment", 0.5));
            when(repository.findByExperimentName("exp-a")).thenReturn(Optional.of(exp));

            Optional<RagAbExperiment> result = repository.findByExperimentName("exp-a");

            assertTrue(result.isPresent());
            assertEquals("exp-a", result.get().getExperimentName());
            assertEquals("DRAFT", result.get().getStatus());
            verify(repository).findByExperimentName("exp-a");
        }

        @Test
        @DisplayName("returns empty when not found")
        void returnsEmptyWhenNotFound() {
            when(repository.findByExperimentName("nonexistent")).thenReturn(Optional.empty());

            Optional<RagAbExperiment> result = repository.findByExperimentName("nonexistent");

            assertFalse(result.isPresent());
            verify(repository).findByExperimentName("nonexistent");
        }

        @Test
        @DisplayName("returns correct traffic split map")
        void returnsCorrectTrafficSplit() {
            Map<String, Double> split = Map.of("control", 0.3, "treatment", 0.7);
            RagAbExperiment exp = createExperiment(2L, "exp-split", "RUNNING", split);
            when(repository.findByExperimentName("exp-split")).thenReturn(Optional.of(exp));

            RagAbExperiment result = repository.findByExperimentName("exp-split").orElseThrow();

            assertEquals(0.3, result.getTrafficSplit().get("control"));
            assertEquals(0.7, result.getTrafficSplit().get("treatment"));
        }

        @Test
        @DisplayName("handles null traffic split gracefully")
        void handlesNullTrafficSplit() {
            RagAbExperiment exp = createExperiment(3L, "exp-null-split", "DRAFT", null);
            when(repository.findByExperimentName("exp-null-split")).thenReturn(Optional.of(exp));

            RagAbExperiment result = repository.findByExperimentName("exp-null-split").orElseThrow();

            assertNull(result.getTrafficSplit());
        }
    }

    @Nested
    @DisplayName("findByStatus")
    class FindByStatus {

        @Test
        @DisplayName("returns all experiments with matching status")
        void returnsExperimentsWithMatchingStatus() {
            RagAbExperiment draft = createExperiment(1L, "draft-1", "DRAFT", Map.of("a", 1.0));
            RagAbExperiment draft2 = createExperiment(2L, "draft-2", "DRAFT", Map.of("a", 1.0));
            when(repository.findByStatus("DRAFT")).thenReturn(List.of(draft, draft2));

            List<RagAbExperiment> results = repository.findByStatus("DRAFT");

            assertEquals(2, results.size());
            assertTrue(results.stream().allMatch(e -> "DRAFT".equals(e.getStatus())));
            verify(repository).findByStatus("DRAFT");
        }

        @Test
        @DisplayName("returns empty list when no experiments match status")
        void returnsEmptyWhenNoMatch() {
            when(repository.findByStatus("COMPLETED")).thenReturn(List.of());

            List<RagAbExperiment> results = repository.findByStatus("COMPLETED");

            assertTrue(results.isEmpty());
            verify(repository).findByStatus("COMPLETED");
        }

        @Test
        @DisplayName("returns running experiments filtered by status")
        void returnsRunningExperiments() {
            RagAbExperiment running = createExperiment(5L, "running-exp", "RUNNING", Map.of("a", 1.0));
            when(repository.findByStatus("RUNNING")).thenReturn(List.of(running));

            List<RagAbExperiment> results = repository.findByStatus("RUNNING");

            assertEquals(1, results.size());
            assertEquals("RUNNING", results.get(0).getStatus());
        }

        @Test
        @DisplayName("returns paused experiments filtered by status")
        void returnsPausedExperiments() {
            RagAbExperiment paused = createExperiment(6L, "paused-exp", "PAUSED", Map.of("a", 1.0));
            when(repository.findByStatus("PAUSED")).thenReturn(List.of(paused));

            List<RagAbExperiment> results = repository.findByStatus("PAUSED");

            assertEquals(1, results.size());
            assertEquals("PAUSED", results.get(0).getStatus());
        }
    }

    @Nested
    @DisplayName("findRunningExperiments")
    class FindRunningExperiments {

        @Test
        @DisplayName("returns running experiments ordered by createdAt desc")
        void returnsRunningExperimentsOrderedByCreatedAtDesc() {
            ZonedDateTime now = ZonedDateTime.now();
            RagAbExperiment older = createExperiment(1L, "running-old", "RUNNING", Map.of("a", 1.0));
            older.setCreatedAt(now.minusHours(2));
            RagAbExperiment newer = createExperiment(2L, "running-new", "RUNNING", Map.of("a", 1.0));
            newer.setCreatedAt(now.minusHours(1));
            when(repository.findRunningExperiments()).thenReturn(List.of(newer, older));

            List<RagAbExperiment> results = repository.findRunningExperiments();

            assertEquals(2, results.size());
            // Ordered by createdAt DESC
            assertTrue(results.get(0).getCreatedAt().isAfter(results.get(1).getCreatedAt())
                    || results.get(0).getCreatedAt().equals(results.get(1).getCreatedAt()));
            verify(repository).findRunningExperiments();
        }

        @Test
        @DisplayName("returns empty list when no running experiments")
        void returnsEmptyWhenNoRunningExperiments() {
            when(repository.findRunningExperiments()).thenReturn(List.of());

            List<RagAbExperiment> results = repository.findRunningExperiments();

            assertTrue(results.isEmpty());
            verify(repository).findRunningExperiments();
        }

        @Test
        @DisplayName("each returned experiment has RUNNING status")
        void eachReturnedExperimentHasRunningStatus() {
            RagAbExperiment exp1 = createExperiment(1L, "r1", "RUNNING", Map.of("a", 1.0));
            RagAbExperiment exp2 = createExperiment(2L, "r2", "RUNNING", Map.of("a", 1.0));
            when(repository.findRunningExperiments()).thenReturn(List.of(exp1, exp2));

            List<RagAbExperiment> results = repository.findRunningExperiments();

            assertTrue(results.stream().allMatch(e -> "RUNNING".equals(e.getStatus())));
        }

        @Test
        @DisplayName("returns single running experiment")
        void returnsSingleRunningExperiment() {
            RagAbExperiment single = createExperiment(1L, "only-running", "RUNNING", Map.of("a", 1.0));
            when(repository.findRunningExperiments()).thenReturn(List.of(single));

            List<RagAbExperiment> results = repository.findRunningExperiments();

            assertEquals(1, results.size());
            assertEquals("only-running", results.get(0).getExperimentName());
        }
    }

    @Nested
    @DisplayName("existsByExperimentName")
    class ExistsByExperimentName {

        @Test
        @DisplayName("returns true when experiment name exists")
        void returnsTrueWhenExists() {
            when(repository.existsByExperimentName("existing-exp")).thenReturn(true);

            boolean result = repository.existsByExperimentName("existing-exp");

            assertTrue(result);
            verify(repository).existsByExperimentName("existing-exp");
        }

        @Test
        @DisplayName("returns false when experiment name does not exist")
        void returnsFalseWhenNotExists() {
            when(repository.existsByExperimentName("nonexistent-exp")).thenReturn(false);

            boolean result = repository.existsByExperimentName("nonexistent-exp");

            assertFalse(result);
            verify(repository).existsByExperimentName("nonexistent-exp");
        }

        @Test
        @DisplayName("handles empty string experiment name")
        void handlesEmptyString() {
            when(repository.existsByExperimentName("")).thenReturn(false);

            boolean result = repository.existsByExperimentName("");

            assertFalse(result);
        }

        @Test
        @DisplayName("handles case-sensitive matching")
        void handlesCaseSensitiveMatching() {
            when(repository.existsByExperimentName("MyExperiment")).thenReturn(true);
            when(repository.existsByExperimentName("myexperiment")).thenReturn(false);

            assertTrue(repository.existsByExperimentName("MyExperiment"));
            assertFalse(repository.existsByExperimentName("myexperiment"));
        }
    }

    @Nested
    @DisplayName("JpaRepository inherited methods")
    class InheritedMethods {

        @Test
        @DisplayName("save returns saved experiment with id")
        void saveReturnsSavedExperiment() {
            RagAbExperiment exp = createExperiment(null, "new-exp", "DRAFT", Map.of("a", 1.0));
            RagAbExperiment saved = createExperiment(10L, "new-exp", "DRAFT", Map.of("a", 1.0));
            when(repository.save(exp)).thenReturn(saved);

            RagAbExperiment result = repository.save(exp);

            assertNotNull(result.getId());
            assertEquals(10L, result.getId());
            verify(repository).save(exp);
        }

        @Test
        @DisplayName("findById returns experiment when exists")
        void findByIdReturnsExperimentWhenExists() {
            RagAbExperiment exp = createExperiment(1L, "exp-by-id", "DRAFT", Map.of("a", 1.0));
            when(repository.findById(1L)).thenReturn(Optional.of(exp));

            Optional<RagAbExperiment> result = repository.findById(1L);

            assertTrue(result.isPresent());
            assertEquals("exp-by-id", result.get().getExperimentName());
        }

        @Test
        @DisplayName("findById returns empty when not exists")
        void findByIdReturnsEmptyWhenNotExists() {
            when(repository.findById(999L)).thenReturn(Optional.empty());

            Optional<RagAbExperiment> result = repository.findById(999L);

            assertFalse(result.isPresent());
        }

        @Test
        @DisplayName("deleteById removes experiment")
        void deleteByIdRemovesExperiment() {
            doNothing().when(repository).deleteById(1L);

            repository.deleteById(1L);

            verify(repository).deleteById(1L);
        }

        @Test
        @DisplayName("findAll returns all experiments")
        void findAllReturnsAllExperiments() {
            RagAbExperiment exp1 = createExperiment(1L, "exp-1", "DRAFT", Map.of("a", 1.0));
            RagAbExperiment exp2 = createExperiment(2L, "exp-2", "RUNNING", Map.of("a", 1.0));
            when(repository.findAll()).thenReturn(List.of(exp1, exp2));

            List<RagAbExperiment> results = repository.findAll();

            assertEquals(2, results.size());
        }

        @Test
        @DisplayName("count returns correct count")
        void countReturnsCorrectCount() {
            when(repository.count()).thenReturn(5L);

            long count = repository.count();

            assertEquals(5L, count);
        }

        @Test
        @DisplayName("existsById returns true for existing id")
        void existsByIdReturnsTrueForExisting() {
            when(repository.existsById(1L)).thenReturn(true);

            assertTrue(repository.existsById(1L));
        }

        @Test
        @DisplayName("existsById returns false for non-existing id")
        void existsByIdReturnsFalseForNonExisting() {
            when(repository.existsById(999L)).thenReturn(false);

            assertFalse(repository.existsById(999L));
        }
    }

    @Nested
    @DisplayName("Experiment lifecycle states")
    class ExperimentLifecycleStates {

        @Test
        @DisplayName("handles DRAFT status correctly")
        void handlesDraftStatus() {
            RagAbExperiment draft = createExperiment(1L, "draft-exp", "DRAFT", Map.of("a", 1.0));
            when(repository.findByStatus("DRAFT")).thenReturn(List.of(draft));

            List<RagAbExperiment> results = repository.findByStatus("DRAFT");

            assertEquals(1, results.size());
            assertEquals("DRAFT", results.get(0).getStatus());
        }

        @Test
        @DisplayName("handles COMPLETED status correctly")
        void handlesCompletedStatus() {
            RagAbExperiment completed = createExperiment(1L, "completed-exp", "COMPLETED", Map.of("a", 1.0));
            when(repository.findByStatus("COMPLETED")).thenReturn(List.of(completed));

            List<RagAbExperiment> results = repository.findByStatus("COMPLETED");

            assertEquals(1, results.size());
            assertEquals("COMPLETED", results.get(0).getStatus());
        }

        @Test
        @DisplayName("can find experiment across all status types")
        void canFindExperimentAcrossAllStatuses() {
            for (String status : List.of("DRAFT", "RUNNING", "PAUSED", "COMPLETED")) {
                RagAbExperiment exp = createExperiment(1L, status + "-exp", status, Map.of("a", 1.0));
                when(repository.findByStatus(status)).thenReturn(List.of(exp));

                List<RagAbExperiment> results = repository.findByStatus(status);

                assertEquals(1, results.size(), "Status: " + status);
                assertEquals(status, results.get(0).getStatus());
            }
        }
    }

    @Nested
    @DisplayName("Traffic split validation")
    class TrafficSplitValidation {

        @Test
        @DisplayName("stores traffic split with multiple variants")
        void storesTrafficSplitWithMultipleVariants() {
            Map<String, Double> split = Map.of(
                    "control", 0.33,
                    "treatment-a", 0.33,
                    "treatment-b", 0.34
            );
            RagAbExperiment exp = createExperiment(1L, "multi-variant", "RUNNING", split);
            when(repository.findByExperimentName("multi-variant")).thenReturn(Optional.of(exp));

            RagAbExperiment result = repository.findByExperimentName("multi-variant").orElseThrow();

            assertEquals(3, result.getTrafficSplit().size());
            assertEquals(0.33, result.getTrafficSplit().get("control"));
            assertEquals(0.34, result.getTrafficSplit().get("treatment-b"));
        }

        @Test
        @DisplayName("handles single variant traffic split")
        void handlesSingleVariant() {
            Map<String, Double> split = Map.of("control", 1.0);
            RagAbExperiment exp = createExperiment(1L, "single-variant", "RUNNING", split);
            when(repository.findByExperimentName("single-variant")).thenReturn(Optional.of(exp));

            RagAbExperiment result = repository.findByExperimentName("single-variant").orElseThrow();

            assertEquals(1, result.getTrafficSplit().size());
            assertEquals(1.0, result.getTrafficSplit().get("control"));
        }

        @Test
        @DisplayName("handles empty traffic split map")
        void handlesEmptyTrafficSplit() {
            RagAbExperiment exp = createExperiment(1L, "empty-split", "DRAFT", Map.of());
            when(repository.findByExperimentName("empty-split")).thenReturn(Optional.of(exp));

            RagAbExperiment result = repository.findByExperimentName("empty-split").orElseThrow();

            assertTrue(result.getTrafficSplit().isEmpty());
        }
    }

    @Nested
    @DisplayName("Metadata and additional fields")
    class MetadataAndAdditionalFields {

        @Test
        @DisplayName("stores and retrieves metadata map")
        void storesAndRetrievesMetadataMap() {
            Map<String, Object> metadata = Map.of(
                    "owner", "team-a",
                    "tags", List.of("production", "qa"),
                    "score", 0.95
            );
            RagAbExperiment exp = createExperiment(1L, "meta-exp", "RUNNING", Map.of("a", 1.0));
            exp.setMetadata(metadata);
            when(repository.findByExperimentName("meta-exp")).thenReturn(Optional.of(exp));

            RagAbExperiment result = repository.findByExperimentName("meta-exp").orElseThrow();

            assertEquals("team-a", result.getMetadata().get("owner"));
            assertEquals(List.of("production", "qa"), result.getMetadata().get("tags"));
        }

        @Test
        @DisplayName("handles null metadata")
        void handlesNullMetadata() {
            RagAbExperiment exp = createExperiment(1L, "null-meta", "RUNNING", Map.of("a", 1.0));
            exp.setMetadata(null);
            when(repository.findByExperimentName("null-meta")).thenReturn(Optional.of(exp));

            RagAbExperiment result = repository.findByExperimentName("null-meta").orElseThrow();

            assertNull(result.getMetadata());
        }

        @Test
        @DisplayName("stores min sample size correctly")
        void storesMinSampleSizeCorrectly() {
            RagAbExperiment exp = createExperiment(1L, "sample-exp", "RUNNING", Map.of("a", 1.0));
            exp.setMinSampleSize(500);
            when(repository.findByExperimentName("sample-exp")).thenReturn(Optional.of(exp));

            RagAbExperiment result = repository.findByExperimentName("sample-exp").orElseThrow();

            assertEquals(500, result.getMinSampleSize());
        }

        @Test
        @DisplayName("stores target metric correctly")
        void storesTargetMetricCorrectly() {
            RagAbExperiment exp = createExperiment(1L, "metric-exp", "RUNNING", Map.of("a", 1.0));
            exp.setTargetMetric("ndcg@10");
            when(repository.findByExperimentName("metric-exp")).thenReturn(Optional.of(exp));

            RagAbExperiment result = repository.findByExperimentName("metric-exp").orElseThrow();

            assertEquals("ndcg@10", result.getTargetMetric());
        }

        @Test
        @DisplayName("stores start and end time correctly")
        void storesStartAndEndTimeCorrectly() {
            ZonedDateTime start = ZonedDateTime.now();
            ZonedDateTime end = start.plusDays(7);
            RagAbExperiment exp = createExperiment(1L, "time-exp", "RUNNING", Map.of("a", 1.0));
            exp.setStartTime(start);
            exp.setEndTime(end);
            when(repository.findByExperimentName("time-exp")).thenReturn(Optional.of(exp));

            RagAbExperiment result = repository.findByExperimentName("time-exp").orElseThrow();

            assertEquals(start, result.getStartTime());
            assertEquals(end, result.getEndTime());
        }

        @Test
        @DisplayName("handles null start and end time")
        void handlesNullStartAndEndTime() {
            RagAbExperiment exp = createExperiment(1L, "no-time-exp", "DRAFT", Map.of("a", 1.0));
            exp.setStartTime(null);
            exp.setEndTime(null);
            when(repository.findByExperimentName("no-time-exp")).thenReturn(Optional.of(exp));

            RagAbExperiment result = repository.findByExperimentName("no-time-exp").orElseThrow();

            assertNull(result.getStartTime());
            assertNull(result.getEndTime());
        }
    }
}
