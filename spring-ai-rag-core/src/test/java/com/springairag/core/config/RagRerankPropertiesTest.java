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
