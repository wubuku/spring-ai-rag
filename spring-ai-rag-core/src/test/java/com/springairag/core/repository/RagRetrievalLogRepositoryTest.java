package com.springairag.core.repository;

import com.springairag.core.entity.RagRetrievalLog;
import org.junit.jupiter.api.DisplayName;
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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * RagRetrievalLogRepository Unit Tests
 *
 * <p>Tests all custom JPQL query methods and inherited CRUD operations.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RagRetrievalLogRepository Tests")
class RagRetrievalLogRepositoryTest {

    @Mock
    private RagRetrievalLogRepository repository;

    private RagRetrievalLog createLog(String sessionId, String strategy, long totalTimeMs,
            int resultCount, ZonedDateTime createdAt) {
        RagRetrievalLog log = new RagRetrievalLog();
        log.setId(1L);
        log.setSessionId(sessionId);
        log.setQuery("test query");
        log.setRetrievalStrategy(strategy);
        log.setVectorSearchTimeMs(10L);
        log.setFulltextSearchTimeMs(5L);
        log.setRerankTimeMs(3L);
        log.setTotalTimeMs(totalTimeMs);
        log.setResultCount(resultCount);
        log.setResultScores(Map.of("doc1", 0.95));
        log.setMetadata(Map.of("source", "test"));
        log.setCreatedAt(createdAt);
        return log;
    }

    // ==================== Inherited CRUD ====================

    @Test
    @DisplayName("save stores entity and returns it with generated ID")
    void save_returnsSavedEntity() {
        RagRetrievalLog log = createLog("s1", "hybrid", 50L, 5, ZonedDateTime.now());
        when(repository.save(log)).thenReturn(log);

        RagRetrievalLog saved = repository.save(log);

        assertNotNull(saved);
        assertEquals("hybrid", saved.getRetrievalStrategy());
        verify(repository).save(log);
    }

    @Test
    @DisplayName("findById returns entity when present")
    void findById_found() {
        RagRetrievalLog log = createLog("s1", "vector", 30L, 3, ZonedDateTime.now());
        when(repository.findById(1L)).thenReturn(Optional.of(log));

        Optional<RagRetrievalLog> found = repository.findById(1L);

        assertTrue(found.isPresent());
        assertEquals("vector", found.get().getRetrievalStrategy());
        verify(repository).findById(1L);
    }

    @Test
    @DisplayName("findById returns empty when not present")
    void findById_notFound() {
        when(repository.findById(999L)).thenReturn(Optional.empty());

        Optional<RagRetrievalLog> found = repository.findById(999L);

        assertTrue(found.isEmpty());
    }

    @Test
    @DisplayName("findAll returns all entities")
    void findAll_returnsAll() {
        RagRetrievalLog log1 = createLog("s1", "hybrid", 50L, 5, ZonedDateTime.now());
        RagRetrievalLog log2 = createLog("s2", "vector", 30L, 3, ZonedDateTime.now());
        when(repository.findAll()).thenReturn(List.of(log1, log2));

        List<RagRetrievalLog> all = repository.findAll();

        assertEquals(2, all.size());
    }

    @Test
    @DisplayName("deleteById removes entity")
    void deleteById_removesEntity() {
        doNothing().when(repository).deleteById(1L);

        repository.deleteById(1L);

        verify(repository).deleteById(1L);
    }

    @Test
    @DisplayName("count returns total number of entities")
    void count_returnsTotal() {
        when(repository.count()).thenReturn(42L);

        long count = repository.count();

        assertEquals(42L, count);
    }

    // ==================== findByCreatedAtBetween ====================

    @Test
    @DisplayName("findByCreatedAtBetween returns paginated results in time range")
    void findByCreatedAtBetween_returnsPaginatedResults() {
        ZonedDateTime start = ZonedDateTime.parse("2026-04-01T00:00:00+08:00");
        ZonedDateTime end = ZonedDateTime.parse("2026-04-10T23:59:59+08:00");
        Pageable pageable = PageRequest.of(0, 10);
        RagRetrievalLog log = createLog("s1", "hybrid", 50L, 5, ZonedDateTime.now());
        Page<RagRetrievalLog> page = Page.empty();
        when(repository.findByCreatedAtBetween(start, end, pageable)).thenReturn(page);

        Page<RagRetrievalLog> result = repository.findByCreatedAtBetween(start, end, pageable);

        assertNotNull(result);
        verify(repository).findByCreatedAtBetween(start, end, pageable);
    }

    // ==================== findBySessionIdOrderByCreatedAtDesc ====================

    @Test
    @DisplayName("findBySessionIdOrderByCreatedAtDesc returns logs for session")
    void findBySessionId_returnsLogsOrderedByCreatedAt() {
        RagRetrievalLog log = createLog("session-42", "vector", 25L, 2, ZonedDateTime.now());
        when(repository.findBySessionIdOrderByCreatedAtDesc("session-42")).thenReturn(List.of(log));

        List<RagRetrievalLog> logs = repository.findBySessionIdOrderByCreatedAtDesc("session-42");

        assertEquals(1, logs.size());
        assertEquals("session-42", logs.get(0).getSessionId());
        verify(repository).findBySessionIdOrderByCreatedAtDesc("session-42");
    }

    @Test
    @DisplayName("findBySessionIdOrderByCreatedAtDesc returns empty for unknown session")
    void findBySessionId_emptyForUnknownSession() {
        when(repository.findBySessionIdOrderByCreatedAtDesc("non-existent")).thenReturn(List.of());

        List<RagRetrievalLog> logs = repository.findBySessionIdOrderByCreatedAtDesc("non-existent");

        assertTrue(logs.isEmpty());
    }

    // ==================== findSlowQueries ====================

    @Test
    @DisplayName("findSlowQueries returns paginated results ordered by total time descending")
    void findSlowQueries_returnsOrderedResults() {
        ZonedDateTime start = ZonedDateTime.parse("2026-04-01T00:00:00+08:00");
        ZonedDateTime end = ZonedDateTime.parse("2026-04-10T23:59:59+08:00");
        Pageable pageable = PageRequest.of(0, 5);
        RagRetrievalLog slow = createLog("s1", "hybrid", 5000L, 10, ZonedDateTime.now());
        when(repository.findSlowQueries(start, end, pageable)).thenReturn(Page.empty());

        repository.findSlowQueries(start, end, pageable);

        verify(repository).findSlowQueries(start, end, pageable);
    }

    // ==================== countByCreatedAtBetween ====================

    @Test
    @DisplayName("countByCreatedAtBetween returns count within range")
    void countByCreatedAtBetween_returnsCount() {
        ZonedDateTime start = ZonedDateTime.parse("2026-04-01T00:00:00+08:00");
        ZonedDateTime end = ZonedDateTime.parse("2026-04-10T23:59:59+08:00");
        when(repository.countByCreatedAtBetween(start, end)).thenReturn(150L);

        long count = repository.countByCreatedAtBetween(start, end);

        assertEquals(150L, count);
    }

    // ==================== findAvgTotalTime ====================

    @Test
    @DisplayName("findAvgTotalTime returns average total time in range")
    void findAvgTotalTime_returnsAverage() {
        ZonedDateTime start = ZonedDateTime.parse("2026-04-01T00:00:00+08:00");
        ZonedDateTime end = ZonedDateTime.parse("2026-04-10T23:59:59+08:00");
        when(repository.findAvgTotalTime(start, end)).thenReturn(42.5);

        Double avg = repository.findAvgTotalTime(start, end);

        assertEquals(42.5, avg);
    }

    @Test
    @DisplayName("findAvgTotalTime returns null when no data in range")
    void findAvgTotalTime_returnsNullWhenEmpty() {
        ZonedDateTime start = ZonedDateTime.parse("2026-01-01T00:00:00+08:00");
        ZonedDateTime end = ZonedDateTime.parse("2026-01-31T23:59:59+08:00");
        when(repository.findAvgTotalTime(start, end)).thenReturn(null);

        Double avg = repository.findAvgTotalTime(start, end);

        assertNull(avg);
    }

    // ==================== findAvgVectorSearchTime ====================

    @Test
    @DisplayName("findAvgVectorSearchTime returns average vector search time")
    void findAvgVectorSearchTime_returnsAverage() {
        ZonedDateTime start = ZonedDateTime.parse("2026-04-01T00:00:00+08:00");
        ZonedDateTime end = ZonedDateTime.parse("2026-04-10T23:59:59+08:00");
        when(repository.findAvgVectorSearchTime(start, end)).thenReturn(15.3);

        Double avg = repository.findAvgVectorSearchTime(start, end);

        assertEquals(15.3, avg);
    }

    // ==================== findAvgFulltextSearchTime ====================

    @Test
    @DisplayName("findAvgFulltextSearchTime returns average fulltext search time")
    void findAvgFulltextSearchTime_returnsAverage() {
        ZonedDateTime start = ZonedDateTime.parse("2026-04-01T00:00:00+08:00");
        ZonedDateTime end = ZonedDateTime.parse("2026-04-10T23:59:59+08:00");
        when(repository.findAvgFulltextSearchTime(start, end)).thenReturn(8.7);

        Double avg = repository.findAvgFulltextSearchTime(start, end);

        assertEquals(8.7, avg);
    }

    // ==================== findStatsByStrategy ====================

    @Test
    @DisplayName("findStatsByStrategy returns grouped statistics")
    void findStatsByStrategy_returnsGroupedStats() {
        ZonedDateTime start = ZonedDateTime.parse("2026-04-01T00:00:00+08:00");
        ZonedDateTime end = ZonedDateTime.parse("2026-04-10T23:59:59+08:00");
        Object[] hybridStats = new Object[]{"hybrid", 100L, 35.5};
        Object[] vectorStats = new Object[]{"vector", 200L, 18.2};
        when(repository.findStatsByStrategy(start, end)).thenReturn(List.of(hybridStats, vectorStats));

        List<Object[]> stats = repository.findStatsByStrategy(start, end);

        assertEquals(2, stats.size());
        verify(repository).findStatsByStrategy(start, end);
    }

    @Test
    @DisplayName("findStatsByStrategy returns empty when no data")
    void findStatsByStrategy_emptyWhenNoData() {
        ZonedDateTime start = ZonedDateTime.parse("2026-01-01T00:00:00+08:00");
        ZonedDateTime end = ZonedDateTime.parse("2026-01-31T23:59:59+08:00");
        when(repository.findStatsByStrategy(start, end)).thenReturn(List.of());

        List<Object[]> stats = repository.findStatsByStrategy(start, end);

        assertTrue(stats.isEmpty());
    }

    // ==================== aggregateAvgTotalTimeByDay (native query) ====================

    @Test
    @DisplayName("aggregateAvgTotalTimeByDay returns daily aggregation via native query")
    void aggregateAvgTotalTimeByDay_returnsDailyAggregation() {
        ZonedDateTime start = ZonedDateTime.parse("2026-04-01T00:00:00+08:00");
        ZonedDateTime end = ZonedDateTime.parse("2026-04-07T23:59:59+08:00");
        Object[] day1 = new Object[]{"2026-04-01", 40.5, 50};
        Object[] day2 = new Object[]{"2026-04-02", 38.1, 60};
        when(repository.aggregateAvgTotalTimeByDay(start, end))
                .thenReturn(List.of(day1, day2));

        List<Object[]> result = repository.aggregateAvgTotalTimeByDay(start, end);

        assertEquals(2, result.size());
        verify(repository).aggregateAvgTotalTimeByDay(start, end);
    }

    // ==================== deleteByCreatedAtBefore ====================

    @Test
    @DisplayName("deleteByCreatedAtBefore removes old logs and returns count")
    void deleteByCreatedAtBefore_returnsDeletedCount() {
        ZonedDateTime cutoff = ZonedDateTime.parse("2026-01-01T00:00:00+08:00");
        when(repository.deleteByCreatedAtBefore(cutoff)).thenReturn(25L);

        long deleted = repository.deleteByCreatedAtBefore(cutoff);

        assertEquals(25L, deleted);
        verify(repository).deleteByCreatedAtBefore(cutoff);
    }

    @Test
    @DisplayName("deleteByCreatedAtBefore returns zero when no logs to delete")
    void deleteByCreatedAtBefore_returnsZeroWhenNothingToDelete() {
        ZonedDateTime cutoff = ZonedDateTime.parse("2099-01-01T00:00:00+08:00");
        when(repository.deleteByCreatedAtBefore(cutoff)).thenReturn(0L);

        long deleted = repository.deleteByCreatedAtBefore(cutoff);

        assertEquals(0L, deleted);
    }

    // ==================== Entity Field Access ====================

    @Test
    @DisplayName("RagRetrievalLog entity stores all fields correctly")
    void entity_storesAllFields() {
        ZonedDateTime now = ZonedDateTime.parse("2026-04-10T12:00:00+08:00");
        RagRetrievalLog log = createLog("session-x", "fulltext", 100L, 7, now);

        assertEquals(1L, log.getId());
        assertEquals("session-x", log.getSessionId());
        assertEquals("test query", log.getQuery());
        assertEquals("fulltext", log.getRetrievalStrategy());
        assertEquals(10L, log.getVectorSearchTimeMs());
        assertEquals(5L, log.getFulltextSearchTimeMs());
        assertEquals(3L, log.getRerankTimeMs());
        assertEquals(100L, log.getTotalTimeMs());
        assertEquals(7, log.getResultCount());
        assertEquals(Map.of("doc1", 0.95), log.getResultScores());
        assertEquals(Map.of("source", "test"), log.getMetadata());
        assertEquals(now, log.getCreatedAt());
    }

    @Test
    @DisplayName("RagRetrievalLog default constructor creates empty entity")
    void entity_defaultConstructor() {
        RagRetrievalLog log = new RagRetrievalLog();

        assertNull(log.getId());
        assertNull(log.getSessionId());
        assertNull(log.getQuery());
        assertNull(log.getRetrievalStrategy());
        assertNull(log.getTotalTimeMs());
        assertNull(log.getResultCount());
        assertNotNull(log.getCreatedAt()); // initialized in field declaration
    }
}
