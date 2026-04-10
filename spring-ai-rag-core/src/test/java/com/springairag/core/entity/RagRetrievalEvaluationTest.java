package com.springairag.core.entity;

import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for RagRetrievalEvaluation entity.
 */
class RagRetrievalEvaluationTest {

    @Test
    void defaultConstructor_works() {
        RagRetrievalEvaluation eval = new RagRetrievalEvaluation();
        assertNotNull(eval);
        assertNull(eval.getId());
    }

    @Test
    void allFields_setAndGet() {
        ZonedDateTime now = ZonedDateTime.now();

        RagRetrievalEvaluation eval = new RagRetrievalEvaluation();
        eval.setId(1L);
        eval.setQuery("What is machine learning?");
        eval.setExpectedDocumentIds("[1, 2, 3]");
        eval.setRetrievedDocumentIds("[1, 2, 5]");
        eval.setEvaluationResult(Map.of("hitCount", 2, "totalExpected", 3));
        eval.setPrecisionAtK(Map.of(1, 1.0, 3, 0.67, 5, 0.4));
        eval.setRecallAtK(Map.of(1, 0.33, 3, 0.67, 5, 0.67));
        eval.setMrr(0.5);
        eval.setNdcg(0.78);
        eval.setHitRate(0.67);
        eval.setEvaluationMethod("AUTO");
        eval.setEvaluatorId("system");
        eval.setMetadata(Map.of("collection", "ml-docs", "topK", 5));
        eval.setCreatedAt(now);

        assertEquals(1L, eval.getId());
        assertEquals("What is machine learning?", eval.getQuery());
        assertEquals("[1, 2, 3]", eval.getExpectedDocumentIds());
        assertEquals("[1, 2, 5]", eval.getRetrievedDocumentIds());
        assertEquals(2, eval.getEvaluationResult().get("hitCount"));
        assertEquals(1.0, eval.getPrecisionAtK().get(1));
        assertEquals(0.67, eval.getRecallAtK().get(3));
        assertEquals(0.5, eval.getMrr());
        assertEquals(0.78, eval.getNdcg());
        assertEquals(0.67, eval.getHitRate());
        assertEquals("AUTO", eval.getEvaluationMethod());
        assertEquals("system", eval.getEvaluatorId());
        assertEquals("ml-docs", eval.getMetadata().get("collection"));
        assertEquals(now, eval.getCreatedAt());
    }

    @Test
    void evaluationMethods() {
        RagRetrievalEvaluation eval = new RagRetrievalEvaluation();
        eval.setEvaluationMethod("AUTO");
        assertEquals("AUTO", eval.getEvaluationMethod());

        eval.setEvaluationMethod("MANUAL");
        assertEquals("MANUAL", eval.getEvaluationMethod());

        eval.setEvaluationMethod("LLM");
        assertEquals("LLM", eval.getEvaluationMethod());
    }

    @Test
    void precisionAtK_variousKValues() {
        RagRetrievalEvaluation eval = new RagRetrievalEvaluation();
        Map<Integer, Double> precisionAtK = Map.of(1, 1.0, 3, 0.67, 5, 0.6, 10, 0.3);
        eval.setPrecisionAtK(precisionAtK);

        assertEquals(1.0, eval.getPrecisionAtK().get(1));
        assertEquals(0.67, eval.getPrecisionAtK().get(3));
        assertEquals(0.3, eval.getPrecisionAtK().get(10));
    }

    @Test
    void recallAtK_variousKValues() {
        RagRetrievalEvaluation eval = new RagRetrievalEvaluation();
        Map<Integer, Double> recallAtK = Map.of(1, 0.2, 5, 0.8, 10, 1.0);
        eval.setRecallAtK(recallAtK);

        assertEquals(0.2, eval.getRecallAtK().get(1));
        assertEquals(1.0, eval.getRecallAtK().get(10));
    }

    @Test
    void mrr_values() {
        RagRetrievalEvaluation eval = new RagRetrievalEvaluation();
        eval.setMrr(1.0);
        assertEquals(1.0, eval.getMrr());

        eval.setMrr(0.333);
        assertEquals(0.333, eval.getMrr());

        eval.setMrr(0.0);
        assertEquals(0.0, eval.getMrr());
    }

    @Test
    void ndcg_normalizedScores() {
        RagRetrievalEvaluation eval = new RagRetrievalEvaluation();
        eval.setNdcg(1.0);
        assertEquals(1.0, eval.getNdcg());

        eval.setNdcg(0.853);
        assertEquals(0.853, eval.getNdcg());

        eval.setNdcg(0.0);
        assertEquals(0.0, eval.getNdcg());
    }

    @Test
    void hitRate_zeroToOne() {
        RagRetrievalEvaluation eval = new RagRetrievalEvaluation();
        eval.setHitRate(0.0);
        assertEquals(0.0, eval.getHitRate());

        eval.setHitRate(0.5);
        assertEquals(0.5, eval.getHitRate());

        eval.setHitRate(1.0);
        assertEquals(1.0, eval.getHitRate());
    }

    @Test
    void evaluationResult_jsonMap() {
        RagRetrievalEvaluation eval = new RagRetrievalEvaluation();
        Map<String, Object> result = Map.of(
                "totalRetrieved", 10,
                "relevantCount", 7,
                "avgScore", 0.85
        );
        eval.setEvaluationResult(result);

        assertEquals(10, eval.getEvaluationResult().get("totalRetrieved"));
        assertEquals(7, eval.getEvaluationResult().get("relevantCount"));
    }

    @Test
    void optionalFields_canBeNull() {
        RagRetrievalEvaluation eval = new RagRetrievalEvaluation();
        eval.setExpectedDocumentIds(null);
        eval.setRetrievedDocumentIds(null);
        eval.setEvaluationResult(null);
        eval.setPrecisionAtK(null);
        eval.setRecallAtK(null);
        eval.setMrr(null);
        eval.setNdcg(null);
        eval.setHitRate(null);
        eval.setEvaluationMethod(null);
        eval.setEvaluatorId(null);
        eval.setMetadata(null);

        assertNull(eval.getExpectedDocumentIds());
        assertNull(eval.getRetrievedDocumentIds());
        assertNull(eval.getEvaluationResult());
        assertNull(eval.getPrecisionAtK());
        assertNull(eval.getRecallAtK());
        assertNull(eval.getMrr());
        assertNull(eval.getNdcg());
        assertNull(eval.getHitRate());
        assertNull(eval.getEvaluationMethod());
        assertNull(eval.getEvaluatorId());
        assertNull(eval.getMetadata());
    }

    @Test
    void defaultValues_createdAtIsNotNull() {
        RagRetrievalEvaluation eval = new RagRetrievalEvaluation();
        assertNotNull(eval.getCreatedAt());
    }

    @Test
    void metadata_jsonMap() {
        RagRetrievalEvaluation eval = new RagRetrievalEvaluation();
        Map<String, Object> metadata = Map.of(
                "indexType", "HNSW",
                "m", 16,
                "efConstruction", 200
        );
        eval.setMetadata(metadata);

        assertEquals("HNSW", eval.getMetadata().get("indexType"));
        assertEquals(16, eval.getMetadata().get("m"));
    }
}
