package com.springairag.core.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for RagRerankProperties.
 */
class RagRerankPropertiesTest {

    @Test
    void defaults_enabledIsFalse() {
        RagRerankProperties props = new RagRerankProperties();
        assertFalse(props.isEnabled());
    }

    @Test
    void defaults_diversityWeightIs0_2() {
        RagRerankProperties props = new RagRerankProperties();
        assertEquals(0.2f, props.getDiversityWeight());
    }

    @Test
    void defaults_candidateLimitIs20() {
        RagRerankProperties props = new RagRerankProperties();
        assertEquals(20, props.getCandidateLimit());
    }

    @Test
    void defaults_preferredMaxChunksPerDocumentIs2() {
        RagRerankProperties props = new RagRerankProperties();
        assertEquals(2, props.getPreferredMaxChunksPerDocument());
    }

    @Test
    void candidateLimit_isClampedToOperationalBounds() {
        RagRerankProperties props = new RagRerankProperties();

        props.setCandidateLimit(0);
        assertEquals(1, props.getCandidateLimit());

        props.setCandidateLimit(101);
        assertEquals(100, props.getCandidateLimit());
    }

    @Test
    void preferredMaxChunksPerDocument_isClampedToOperationalBounds() {
        RagRerankProperties props = new RagRerankProperties();

        props.setPreferredMaxChunksPerDocument(-1);
        assertEquals(0, props.getPreferredMaxChunksPerDocument());

        props.setPreferredMaxChunksPerDocument(0);
        assertEquals(0, props.getPreferredMaxChunksPerDocument());

        props.setPreferredMaxChunksPerDocument(1);
        assertEquals(1, props.getPreferredMaxChunksPerDocument());

        props.setPreferredMaxChunksPerDocument(100);
        assertEquals(100, props.getPreferredMaxChunksPerDocument());

        props.setPreferredMaxChunksPerDocument(101);
        assertEquals(100, props.getPreferredMaxChunksPerDocument());
    }

    @Test
    void setters_updateAllValues() {
        RagRerankProperties props = new RagRerankProperties();

        props.setEnabled(true);
        props.setDiversityWeight(0.5f);

        assertTrue(props.isEnabled());
        assertEquals(0.5f, props.getDiversityWeight());
    }

    @Test
    void setters_acceptBoundaryValues() {
        RagRerankProperties props = new RagRerankProperties();

        props.setEnabled(true);
        props.setDiversityWeight(1.0f);
        assertTrue(props.isEnabled());
        assertEquals(1.0f, props.getDiversityWeight());

        props.setEnabled(false);
        props.setDiversityWeight(0.0f);
        assertFalse(props.isEnabled());
        assertEquals(0.0f, props.getDiversityWeight());
    }
}
