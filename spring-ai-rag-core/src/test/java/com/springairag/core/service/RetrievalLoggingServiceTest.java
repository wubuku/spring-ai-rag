package com.springairag.core.service;

import com.springairag.api.dto.RetrievalResult;
import com.springairag.core.entity.RagRetrievalLog;
import com.springairag.core.repository.RagRetrievalLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * RetrievalLoggingService Unit Tests
 */
@ExtendWith(MockitoExtension.class)
class RetrievalLoggingServiceTest {

    @Mock
    private RagRetrievalLogRepository repository;

    private RetrievalLoggingService service;

    @BeforeEach
    void setUp() {
        service = new RetrievalLoggingService(repository);
    }

    @Test
    void logRetrieval_savesBasicFields() {
        RetrievalResult r = new RetrievalResult();
        r.setDocumentId("doc-1");
        r.setScore(0.85);

        service.logRetrieval("session-1", "测试查询", "hybrid", 100L, 50L, 30L, List.of(r));

        ArgumentCaptor<RagRetrievalLog> captor = ArgumentCaptor.forClass(RagRetrievalLog.class);
        verify(repository).save(captor.capture());

        RagRetrievalLog saved = captor.getValue();
        assertEquals("session-1", saved.getSessionId());
        assertEquals("测试查询", saved.getQuery());
        assertEquals("hybrid", saved.getRetrievalStrategy());
        assertEquals(100L, saved.getVectorSearchTimeMs());
        assertEquals(50L, saved.getFulltextSearchTimeMs());
        assertEquals(30L, saved.getRerankTimeMs());
        assertEquals(180L, saved.getTotalTimeMs());
        assertEquals(1, saved.getResultCount());
    }

    @Test
    void logRetrieval_nullSessionId_allowed() {
        service.logRetrieval(null, "查询", "vector", 10L, 0L, 0L, Collections.emptyList());

        ArgumentCaptor<RagRetrievalLog> captor = ArgumentCaptor.forClass(RagRetrievalLog.class);
        verify(repository).save(captor.capture());

        assertNull(captor.getValue().getSessionId());
    }

    @Test
    void logRetrieval_emptyResults_zeroCount() {
        service.logRetrieval("s1", "q", "fulltext", 10L, 20L, 0L, Collections.emptyList());

        ArgumentCaptor<RagRetrievalLog> captor = ArgumentCaptor.forClass(RagRetrievalLog.class);
        verify(repository).save(captor.capture());

        assertEquals(0, captor.getValue().getResultCount());
        assertNull(captor.getValue().getResultScores());
    }

    @Test
    void logRetrieval_multipleResults_capturesScores() {
        RetrievalResult r1 = new RetrievalResult();
        r1.setDocumentId("doc-1");
        r1.setScore(0.9);

        RetrievalResult r2 = new RetrievalResult();
        r2.setDocumentId("doc-2");
        r2.setScore(0.7);

        service.logRetrieval("s1", "q", "hybrid", 10L, 5L, 3L, List.of(r1, r2));

        ArgumentCaptor<RagRetrievalLog> captor = ArgumentCaptor.forClass(RagRetrievalLog.class);
        verify(repository).save(captor.capture());

        assertEquals(2, captor.getValue().getResultCount());
        assertNotNull(captor.getValue().getResultScores());
        assertEquals(2, captor.getValue().getResultScores().size());
        assertEquals(0.9, captor.getValue().getResultScores().get("doc-1"));
        assertEquals(0.7, captor.getValue().getResultScores().get("doc-2"));
    }

    @Test
    void logRetrieval_nullDocumentId_usesIndexKey() {
        RetrievalResult r = new RetrievalResult();
        r.setDocumentId(null);
        r.setScore(0.5);

        service.logRetrieval("s1", "q", "hybrid", 10L, 0L, 0L, List.of(r));

        ArgumentCaptor<RagRetrievalLog> captor = ArgumentCaptor.forClass(RagRetrievalLog.class);
        verify(repository).save(captor.capture());

        assertNotNull(captor.getValue().getResultScores());
        assertTrue(captor.getValue().getResultScores().containsKey("idx_0"));
    }

    @Test
    void logRetrieval_repositoryThrows_doesNotPropagate() {
        doThrow(new RuntimeException("DB error")).when(repository).save(any());

        // 不应抛出异常
        assertDoesNotThrow(() ->
                service.logRetrieval("s1", "q", "hybrid", 10L, 0L, 0L, Collections.emptyList()));
    }

    @Test
    void getAvgTotalTime_delegatesToRepository() {
        ZonedDateTime start = ZonedDateTime.now().minusDays(1);
        ZonedDateTime end = ZonedDateTime.now();
        when(repository.findAvgTotalTime(start, end)).thenReturn(150.0);

        Double avg = service.getAvgTotalTime(start, end);
        assertEquals(150.0, avg);
    }

    @Test
    void count_delegatesToRepository() {
        ZonedDateTime start = ZonedDateTime.now().minusDays(7);
        ZonedDateTime end = ZonedDateTime.now();
        when(repository.countByCreatedAtBetween(start, end)).thenReturn(99L);

        long count = service.count(start, end);
        assertEquals(99L, count);
    }

    @Test
    void count_returnsZeroWhenNoLogs() {
        ZonedDateTime start = ZonedDateTime.now().minusDays(1);
        ZonedDateTime end = ZonedDateTime.now();
        when(repository.countByCreatedAtBetween(start, end)).thenReturn(0L);

        long count = service.count(start, end);
        assertEquals(0L, count);
    }

    @Test
    void cleanup_delegatesToRepository() {
        ZonedDateTime cutoff = ZonedDateTime.now().minusDays(30);
        when(repository.deleteByCreatedAtBefore(cutoff)).thenReturn(42L);

        long deleted = service.cleanup(cutoff);
        assertEquals(42L, deleted);
    }

}
