package com.springairag.core.repository;

import com.springairag.core.entity.RagAbExperiment;
import com.springairag.core.entity.RagAbResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * RagAbResultRepository Unit Tests (using Mock).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RagAbResultRepository Tests")
class RagAbResultRepositoryTest {

    @Mock
    private RagAbResultRepository repository;

    private RagAbExperiment createMockExperiment(Long id) {
        RagAbExperiment exp = new RagAbExperiment();
        exp.setId(id);
        return exp;
    }

    private RagAbResult createResult(Long id, Long experimentId, String variantName,
                                    String sessionId, Map<String, Double> metrics) {
        RagAbResult result = new RagAbResult();
        result.setId(id);
        result.setExperiment(createMockExperiment(experimentId));
        result.setVariantName(variantName);
        result.setSessionId(sessionId);
        result.setQuery("test query " + id);
        result.setRetrievedDocumentIds("doc-1,doc-2");
        result.setMetrics(metrics);
        result.setIsConverted(false);
        result.setCreatedAt(ZonedDateTime.now());
        return result;
    }

    // findByExperimentId

    @Nested
    @DisplayName("findByExperimentId")
    class FindByExperimentId {

        @Test
        @DisplayName("returns results when found")
        void returnsResultsWhenFound() {
            RagAbResult r1 = createResult(1L, 10L, "control", "sess-1",
                    Map.of("mrr", 0.5));
            RagAbResult r2 = createResult(2L, 10L, "treatment", "sess-2",
                    Map.of("mrr", 0.7));
            when(repository.findByExperimentId(10L)).thenReturn(List.of(r1, r2));

            List<RagAbResult> results = repository.findByExperimentId(10L);

            assertEquals(2, results.size());
            assertEquals("control", results.get(0).getVariantName());
            assertEquals("treatment", results.get(1).getVariantName());
            verify(repository).findByExperimentId(10L);
        }

        @Test
        @DisplayName("returns empty list when not found")
        void returnsEmptyListWhenNotFound() {
            when(repository.findByExperimentId(999L)).thenReturn(List.of());

            List<RagAbResult> results = repository.findByExperimentId(999L);

            assertTrue(results.isEmpty());
            verify(repository).findByExperimentId(999L);
        }
    }

    // findByExperimentIdOrderByCreatedAtDesc (paginated)

    @Nested
    @DisplayName("findByExperimentIdOrderByCreatedAtDesc")
    class FindByExperimentIdPaginated {

        @Test
        @DisplayName("returns paginated results")
        void returnsPaginatedResults() {
            Pageable pageable = PageRequest.of(0, 10);
            RagAbResult r1 = createResult(1L, 10L, "control", "sess-1",
                    Map.of("mrr", 0.5));
            Page<RagAbResult> page = new org.springframework.data.domain.PageImpl<>(
                    List.of(r1), pageable, 1);
            when(repository.findByExperimentIdOrderByCreatedAtDesc(10L, pageable))
                    .thenReturn(page);

            Page<RagAbResult> result =
                    repository.findByExperimentIdOrderByCreatedAtDesc(10L, pageable);

            assertEquals(1, result.getTotalElements());
            assertEquals("control", result.getContent().get(0).getVariantName());
        }

        @Test
        @DisplayName("returns empty page when not found")
        void returnsEmptyPageWhenNotFound() {
            Pageable pageable = PageRequest.of(0, 10);
            Page<RagAbResult> page = new org.springframework.data.domain.PageImpl<>(
                    List.of(), pageable, 0);
            when(repository.findByExperimentIdOrderByCreatedAtDesc(999L, pageable))
                    .thenReturn(page);

            Page<RagAbResult> result =
                    repository.findByExperimentIdOrderByCreatedAtDesc(999L, pageable);

            assertEquals(0, result.getTotalElements());
        }
    }

    // findByExperimentIdAndVariantName

    @Nested
    @DisplayName("findByExperimentIdAndVariantName")
    class FindByExperimentIdAndVariantName {

        @Test
        @DisplayName("returns filtered results by variant")
        void returnsFilteredResultsByVariant() {
            RagAbResult r1 = createResult(1L, 10L, "control", "sess-1",
                    Map.of("mrr", 0.5));
            when(repository.findByExperimentIdAndVariantName(10L, "control"))
                    .thenReturn(List.of(r1));

            List<RagAbResult> results =
                    repository.findByExperimentIdAndVariantName(10L, "control");

            assertEquals(1, results.size());
            assertEquals("control", results.get(0).getVariantName());
        }

        @Test
        @DisplayName("returns empty list for unknown variant")
        void returnsEmptyListForUnknownVariant() {
            when(repository.findByExperimentIdAndVariantName(10L, "unknown"))
                    .thenReturn(List.of());

            List<RagAbResult> results =
                    repository.findByExperimentIdAndVariantName(10L, "unknown");

            assertTrue(results.isEmpty());
        }
    }

    // countByExperimentIdAndVariantName

    @Nested
    @DisplayName("countByExperimentIdAndVariantName")
    class CountByExperimentIdAndVariantName {

        @Test
        @DisplayName("returns count of variant results")
        void returnsCountOfVariantResults() {
            when(repository.countByExperimentIdAndVariantName(10L, "control"))
                    .thenReturn(42L);

            long count = repository.countByExperimentIdAndVariantName(10L, "control");

            assertEquals(42L, count);
        }

        @Test
        @DisplayName("returns zero when no results")
        void returnsZeroWhenNoResults() {
            when(repository.countByExperimentIdAndVariantName(10L, "treatment"))
                    .thenReturn(0L);

            long count = repository.countByExperimentIdAndVariantName(10L, "treatment");

            assertEquals(0L, count);
        }
    }

    // existsBySessionIdAndExperimentId

    @Nested
    @DisplayName("existsBySessionIdAndExperimentId")
    class ExistsBySessionIdAndExperimentId {

        @Test
        @DisplayName("returns true when exists")
        void returnsTrueWhenExists() {
            when(repository.existsBySessionIdAndExperimentId("sess-1", 10L))
                    .thenReturn(true);

            boolean exists =
                    repository.existsBySessionIdAndExperimentId("sess-1", 10L);

            assertTrue(exists);
        }

        @Test
        @DisplayName("returns false when not exists")
        void returnsFalseWhenNotExists() {
            when(repository.existsBySessionIdAndExperimentId("sess-new", 10L))
                    .thenReturn(false);

            boolean exists =
                    repository.existsBySessionIdAndExperimentId("sess-new", 10L);

            assertFalse(exists);
        }
    }

    // CRUD inherited methods

    @Nested
    @DisplayName("CRUD inherited methods")
    class CrudMethods {

        @Test
        @DisplayName("save stores result and returns it")
        void saveStoresResultAndReturnsIt() {
            RagAbResult r = createResult(null, 10L, "control", "sess-1",
                    Map.of("mrr", 0.5));
            RagAbResult saved = createResult(1L, 10L, "control", "sess-1",
                    Map.of("mrr", 0.5));
            when(repository.save(r)).thenReturn(saved);

            RagAbResult result = repository.save(r);

            assertNotNull(result.getId());
            assertEquals("control", result.getVariantName());
        }

        @Test
        @DisplayName("findById returns result when present")
        void findByIdReturnsResultWhenPresent() {
            RagAbResult r = createResult(1L, 10L, "control", "sess-1",
                    Map.of("mrr", 0.5));
            when(repository.findById(1L)).thenReturn(Optional.of(r));

            Optional<RagAbResult> result = repository.findById(1L);

            assertTrue(result.isPresent());
            assertEquals("control", result.get().getVariantName());
        }

        @Test
        @DisplayName("findById returns empty when not present")
        void findByIdReturnsEmptyWhenNotPresent() {
            when(repository.findById(999L)).thenReturn(Optional.empty());

            Optional<RagAbResult> result = repository.findById(999L);

            assertFalse(result.isPresent());
        }

        @Test
        @DisplayName("deleteById removes result")
        void deleteByIdRemovesResult() {
            doNothing().when(repository).deleteById(1L);

            repository.deleteById(1L);

            verify(repository).deleteById(1L);
        }

        @Test
        @DisplayName("findAll returns all results")
        void findAllReturnsAllResults() {
            RagAbResult r1 = createResult(1L, 10L, "control", "sess-1",
                    Map.of("mrr", 0.5));
            RagAbResult r2 = createResult(2L, 20L, "treatment", "sess-2",
                    Map.of("mrr", 0.7));
            when(repository.findAll()).thenReturn(List.of(r1, r2));

            List<RagAbResult> results = repository.findAll();

            assertEquals(2, results.size());
        }
    }
}
