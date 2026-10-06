package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.service.AbTestService;
import com.springairag.core.entity.RagAbExperiment;
import com.springairag.core.repository.RagAbExperimentRepository;
import com.springairag.core.repository.RagAbResultRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Batch 932: the experiment list and the experiment lookup.
 *
 * <p>Both exist because the WebUI called them and nothing was behind either
 * call. The sample count is the part worth testing hard — the column it feeds
 * used to render {@code sampleCount ?? 0} against a field the server did not
 * have, so it showed zero for every experiment. A zero is a measurement
 * somebody could act on, and nobody had measured anything.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AbTestServiceImplListingTest {

    @Mock
    private RagAbExperimentRepository experimentRepository;

    @Mock
    private RagAbResultRepository resultRepository;

    private AbTestServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AbTestServiceImpl(
                experimentRepository, resultRepository, new ObjectMapper());
    }

    private RagAbExperiment experiment(long id, String name) {
        RagAbExperiment entity = new RagAbExperiment();
        entity.setId(id);
        entity.setExperimentName(name);
        entity.setStatus("DRAFT");
        entity.setMinSampleSize(100);
        return entity;
    }

    /**
     * One row of the grouped count query. A plain record rather than a mock:
     * the projection has two getters and nothing else, and mocking it would buy
     * nothing over saying what it is.
     */
    private record CountRow(Long experimentId, long total)
            implements RagAbResultRepository.ExperimentResultCount {
        @Override
        public Long getExperimentId() {
            return experimentId;
        }

        @Override
        public long getTotal() {
            return total;
        }
    }

    private RagAbResultRepository.ExperimentResultCount countRow(long id, long total) {
        return new CountRow(id, total);
    }

    @Nested
    @DisplayName("listExperiments")
    class ListExperiments {

        @Test
        @DisplayName("reports the recorded result count per experiment, not zero")
        void reportsSampleCounts() {
            when(experimentRepository.findAll(any(Pageable.class)))
                    .thenReturn(new PageImpl<>(
                            List.of(experiment(1L, "a"), experiment(2L, "b")),
                            PageRequest.of(0, 20), 2));
            when(resultRepository.countResultsByExperimentIds(anyCollection()))
                    .thenReturn(List.of(countRow(1L, 420)));

            AbTestService.ExperimentPage page = service.listExperiments(0, 20);

            assertEquals(420, page.items().get(0).getSampleCount());
            // Experiment 2 is absent from the grouped count. That is a real
            // answer — no result rows means no samples — and getting it
            // backwards would report every fresh experiment as having samples.
            assertEquals(0, page.items().get(1).getSampleCount());
        }

        @Test
        @DisplayName("asks for the counts of exactly the experiments on this page")
        void countsOnlyThePageOnScreen() {
            when(experimentRepository.findAll(any(Pageable.class)))
                    .thenReturn(new PageImpl<>(
                            List.of(experiment(7L, "only")),
                            PageRequest.of(0, 20), 1));
            when(resultRepository.countResultsByExperimentIds(anyCollection()))
                    .thenReturn(List.of());

            service.listExperiments(0, 20);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<java.util.Collection<Long>> ids = ArgumentCaptor.forClass(java.util.Collection.class);
            verify(resultRepository).countResultsByExperimentIds(ids.capture());
            assertEquals(Set.of(7L), Set.copyOf(ids.getValue()));
        }

        @Test
        @DisplayName("counts once for a whole page, not once per row")
        void countsInOneQuery() {
            when(experimentRepository.findAll(any(Pageable.class)))
                    .thenReturn(new PageImpl<>(
                            List.of(experiment(1L, "a"), experiment(2L, "b"), experiment(3L, "c")),
                            PageRequest.of(0, 20), 3));
            when(resultRepository.countResultsByExperimentIds(anyCollection()))
                    .thenReturn(List.of(countRow(1L, 1), countRow(2L, 2), countRow(3L, 3)));

            service.listExperiments(0, 20);

            // One grouped query regardless of page size: the alternative is a
            // count per row, which on the page the UI asks for is a hundred
            // queries to draw one table.
            verify(resultRepository, times(1)).countResultsByExperimentIds(anyCollection());
        }

        @Test
        @DisplayName("carries the whole total, so a page does not read as the end")
        void carriesTotalBeyondThePage() {
            when(experimentRepository.findAll(any(Pageable.class)))
                    .thenReturn(new PageImpl<>(
                            List.of(experiment(1L, "a")),
                            PageRequest.of(0, 20), 312));
            when(resultRepository.countResultsByExperimentIds(anyCollection()))
                    .thenReturn(List.of());

            AbTestService.ExperimentPage page = service.listExperiments(0, 20);

            assertEquals(312, page.totalElements());
            assertEquals(16, page.totalPages());
        }

        @Test
        @DisplayName("orders newest first, so the experiment just made is on page one")
        void ordersNewestFirst() {
            when(experimentRepository.findAll(any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

            service.listExperiments(0, 20);

            ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
            verify(experimentRepository).findAll(pageable.capture());
            Sort.Order order = pageable.getValue().getSort().getOrderFor("createdAt");
            assertTrue(order != null && order.isDescending(),
                    "experiments must be listed newest first");
        }

        @Test
        @DisplayName("clamps a page size nobody should be able to ask for")
        void clampsOversizedPage() {
            when(experimentRepository.findAll(any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

            service.listExperiments(0, 100_000);

            ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
            verify(experimentRepository).findAll(pageable.capture());
            assertEquals(200, pageable.getValue().getPageSize());
        }

        @Test
        @DisplayName("asks for nothing when the page is empty")
        void emptyPageSkipsTheCountQuery() {
            when(experimentRepository.findAll(any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

            assertEquals(List.of(), service.listExperiments(0, 20).items());

            verify(resultRepository, never()).countResultsByExperimentIds(anyCollection());
        }
    }

    @Nested
    @DisplayName("getExperiment")
    class GetExperiment {

        @Test
        @DisplayName("returns the experiment with its recorded sample count")
        void returnsTheExperiment() {
            when(experimentRepository.findById(5L)).thenReturn(Optional.of(experiment(5L, "rerank")));
            when(resultRepository.countResultsByExperimentIds(anyCollection()))
                    .thenReturn(List.of(countRow(5L, 42)));

            assertEquals("rerank", service.getExperiment(5L).getExperimentName());
        }

        @Test
        @DisplayName("reports zero samples for an experiment nobody has measured yet")
        void newExperimentHasNoSamples() {
            when(experimentRepository.findById(6L)).thenReturn(Optional.of(experiment(6L, "fresh")));
            when(resultRepository.countResultsByExperimentIds(anyCollection()))
                    .thenReturn(List.of());

            assertEquals(0, service.getExperiment(6L).getSampleCount());
        }

        @Test
        @DisplayName("says an unknown id is unknown instead of inventing an experiment")
        void unknownIdIsRejected() {
            when(experimentRepository.findById(404L)).thenReturn(Optional.empty());

            IllegalArgumentException thrown = assertThrows(
                    IllegalArgumentException.class, () -> service.getExperiment(404L));
            assertTrue(thrown.getMessage().contains("404"),
                    "the message has to name the id that was not found");
        }

        @Test
        @DisplayName("rejects a null id before touching the database")
        void nullIdIsRejected() {
            assertThrows(IllegalArgumentException.class, () -> service.getExperiment(null));
            verify(experimentRepository, never()).findById(any());
        }
    }
}
