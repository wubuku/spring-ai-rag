package com.springairag.core.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for RagRetrievalProperties.
 */
class RagRetrievalPropertiesTest {

    @Test
    void defaults_vectorWeightIsHalf() {
        RagRetrievalProperties props = new RagRetrievalProperties();
        assertEquals(0.5f, props.getVectorWeight());
    }

    @Test
    void defaults_fulltextWeightIsHalf() {
        RagRetrievalProperties props = new RagRetrievalProperties();
        assertEquals(0.5f, props.getFulltextWeight());
    }

    @Test
    void defaults_defaultLimitIs10() {
        RagRetrievalProperties props = new RagRetrievalProperties();
        assertEquals(10, props.getDefaultLimit());
    }

    @Test
    void defaults_minScoreIs0_3() {
        RagRetrievalProperties props = new RagRetrievalProperties();
        assertEquals(0.3f, props.getMinScore());
    }

    @Test
    void defaults_fulltextEnabledIsTrue() {
        RagRetrievalProperties props = new RagRetrievalProperties();
        assertTrue(props.isFulltextEnabled());
    }

    @Test
    void defaults_fulltextStrategyIsAuto() {
        RagRetrievalProperties props = new RagRetrievalProperties();
        assertEquals("auto", props.getFulltextStrategy());
    }

    @Test
    void defaults_evaluationKIs10() {
        RagRetrievalProperties props = new RagRetrievalProperties();
        assertEquals(10, props.getEvaluationK());
    }

    @Test
    void defaults_answerQualityTimeoutSecondsIs30() {
        RagRetrievalProperties props = new RagRetrievalProperties();
        assertEquals(30, props.getAnswerQualityTimeoutSeconds());
    }

    @Test
    void setters_updateAllValues() {
        RagRetrievalProperties props = new RagRetrievalProperties();

        props.setVectorWeight(0.7f);
        props.setFulltextWeight(0.3f);
        props.setDefaultLimit(20);
        props.setMinScore(0.5f);
        props.setFulltextEnabled(false);
        props.setFulltextStrategy("pg_jieba");
        props.setEvaluationK(5);
        props.setAnswerQualityTimeoutSeconds(60);

        assertEquals(0.7f, props.getVectorWeight());
        assertEquals(0.3f, props.getFulltextWeight());
        assertEquals(20, props.getDefaultLimit());
        assertEquals(0.5f, props.getMinScore());
        assertFalse(props.isFulltextEnabled());
        assertEquals("pg_jieba", props.getFulltextStrategy());
        assertEquals(5, props.getEvaluationK());
        assertEquals(60, props.getAnswerQualityTimeoutSeconds());
    }

    @Test
    void setters_acceptBoundaryValues() {
        RagRetrievalProperties props = new RagRetrievalProperties();

        props.setVectorWeight(0.0f);
        props.setFulltextWeight(1.0f);
        props.setDefaultLimit(0);
        props.setMinScore(0.0f);
        props.setEvaluationK(0);
        props.setAnswerQualityTimeoutSeconds(0);

        assertEquals(0.0f, props.getVectorWeight());
        assertEquals(1.0f, props.getFulltextWeight());
        assertEquals(0, props.getDefaultLimit());
        assertEquals(0.0f, props.getMinScore());
        assertEquals(0, props.getEvaluationK());
        assertEquals(0, props.getAnswerQualityTimeoutSeconds());
    }

    @Test
    void fulltextStrategy_acceptsAllValidStrategies() {
        RagRetrievalProperties props = new RagRetrievalProperties();

        props.setFulltextStrategy("auto");
        assertEquals("auto", props.getFulltextStrategy());

        props.setFulltextStrategy("pg_jieba");
        assertEquals("pg_jieba", props.getFulltextStrategy());

        props.setFulltextStrategy("pg_trgm");
        assertEquals("pg_trgm", props.getFulltextStrategy());

        props.setFulltextStrategy("none");
        assertEquals("none", props.getFulltextStrategy());

        props.setFulltextStrategy("");
        assertEquals("", props.getFulltextStrategy());
    }
}
