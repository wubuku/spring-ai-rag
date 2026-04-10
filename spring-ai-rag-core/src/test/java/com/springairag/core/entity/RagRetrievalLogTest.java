package com.springairag.core.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for RagRetrievalLog entity.
 */
@DisplayName("RagRetrievalLog Entity Tests")
class RagRetrievalLogTest {

    @Test
    @DisplayName("Default constructor creates instance with only createdAt set to now")
    void defaultConstructor_createsInstanceWithOnlyCreatedAtSet() {
        ZonedDateTime before = ZonedDateTime.now().minusSeconds(1);
        RagRetrievalLog log = new RagRetrievalLog();
        ZonedDateTime after = ZonedDateTime.now().plusSeconds(1);

        // createdAt is initialized to ZonedDateTime.now() at field level
        assertNotNull(log.getCreatedAt());
        assertTrue(log.getCreatedAt().isAfter(before));
        assertTrue(log.getCreatedAt().isBefore(after));

        // All other fields are null
        assertNull(log.getId());
        assertNull(log.getSessionId());
        assertNull(log.getQuery());
        assertNull(log.getRetrievalStrategy());
        assertNull(log.getVectorSearchTimeMs());
        assertNull(log.getFulltextSearchTimeMs());
        assertNull(log.getRerankTimeMs());
        assertNull(log.getTotalTimeMs());
        assertNull(log.getResultCount());
        assertNull(log.getResultScores());
        assertNull(log.getMetadata());
    }

    @Test
    @DisplayName("Setters and getters work correctly for all fields")
    void settersAndGetters_workCorrectly() {
        RagRetrievalLog log = new RagRetrievalLog();

        ZonedDateTime now = ZonedDateTime.now();

        log.setId(42L);
        log.setSessionId("session-abc");
        log.setQuery("How do I reset my password?");
        log.setRetrievalStrategy("hybrid");
        log.setVectorSearchTimeMs(15L);
        log.setFulltextSearchTimeMs(8L);
        log.setRerankTimeMs(3L);
        log.setTotalTimeMs(26L);
        log.setResultCount(5);
        log.setResultScores(Map.of("doc1", 0.92, "doc2", 0.85));
        log.setMetadata(Map.of("rerankEnabled", true));
        log.setCreatedAt(now);

        assertEquals(42L, log.getId());
        assertEquals("session-abc", log.getSessionId());
        assertEquals("How do I reset my password?", log.getQuery());
        assertEquals("hybrid", log.getRetrievalStrategy());
        assertEquals(15L, log.getVectorSearchTimeMs());
        assertEquals(8L, log.getFulltextSearchTimeMs());
        assertEquals(3L, log.getRerankTimeMs());
        assertEquals(26L, log.getTotalTimeMs());
        assertEquals(5, log.getResultCount());
        assertEquals(Map.of("doc1", 0.92, "doc2", 0.85), log.getResultScores());
        assertEquals(Map.of("rerankEnabled", true), log.getMetadata());
        assertEquals(now, log.getCreatedAt());
    }


    @Test
    @DisplayName("Metadata field handles various JSON-compatible types")
    void metadataField_handlesVariousTypes() {
        RagRetrievalLog log = new RagRetrievalLog();

        Map<String, Object> mixedMetadata = new java.util.HashMap<>();
        mixedMetadata.put("stringValue", "text");
        mixedMetadata.put("intValue", 42);
        mixedMetadata.put("boolValue", true);
        mixedMetadata.put("doubleValue", 3.14);
        mixedMetadata.put("nullValue", null);
        mixedMetadata.put("arrayValue", java.util.List.of(1, 2, 3));

        log.setMetadata(mixedMetadata);

        Map<String, Object> retrieved = log.getMetadata();
        assertEquals("text", retrieved.get("stringValue"));
        assertEquals(42, retrieved.get("intValue"));
        assertEquals(true, retrieved.get("boolValue"));
        assertEquals(3.14, retrieved.get("doubleValue"));
        assertNull(retrieved.get("nullValue"));
    }

    @Test
    @DisplayName("ResultScores field handles score maps")
    void resultScoresField_handlesScoreMaps() {
        RagRetrievalLog log = new RagRetrievalLog();

        Map<String, Object> scores = Map.of(
                "doc_1", 0.952,
                "doc_2", 0.871,
                "doc_3", 0.623
        );
        log.setResultScores(scores);

        Map<String, Object> retrieved = log.getResultScores();
        assertEquals(3, retrieved.size());
        assertEquals(0.952, retrieved.get("doc_1"));
        assertEquals(0.871, retrieved.get("doc_2"));
        assertEquals(0.623, retrieved.get("doc_3"));
    }

    @Test
    @DisplayName("Timing fields handle null values (when step is skipped)")
    void timingFields_handleNullWhenSkipped() {
        RagRetrievalLog log = new RagRetrievalLog();

        // Vector-only search (no FTS, no rerank)
        log.setVectorSearchTimeMs(12L);
        log.setFulltextSearchTimeMs(null);
        log.setRerankTimeMs(null);
        log.setTotalTimeMs(12L);

        assertEquals(12L, log.getVectorSearchTimeMs());
        assertNull(log.getFulltextSearchTimeMs());
        assertNull(log.getRerankTimeMs());
        assertEquals(12L, log.getTotalTimeMs());
    }

    @Test
    @DisplayName("ResultCount handles zero results")
    void resultCount_handlesZeroResults() {
        RagRetrievalLog log = new RagRetrievalLog();

        log.setResultCount(0);

        assertEquals(0, log.getResultCount());
    }

    @Test
    @DisplayName("RetrievalStrategy accepts all valid strategy names")
    void retrievalStrategy_acceptsValidStrategyNames() {
        RagRetrievalLog log = new RagRetrievalLog();

        String[] strategies = {"hybrid", "vector", "fulltext", "hybrid_rrf", "hybrid_rerank"};

        for (String strategy : strategies) {
            log.setRetrievalStrategy(strategy);
            assertEquals(strategy, log.getRetrievalStrategy());
        }
    }
}
