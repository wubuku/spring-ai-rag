package com.springairag.core.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for RagChunkProperties.
 */
class RagChunkPropertiesTest {

    @Test
    void defaults_defaultChunkSizeIs1000() {
        RagChunkProperties props = new RagChunkProperties();
        assertEquals(1000, props.getDefaultChunkSize());
    }

    @Test
    void defaults_defaultChunkOverlapIs100() {
        RagChunkProperties props = new RagChunkProperties();
        assertEquals(100, props.getDefaultChunkOverlap());
    }

    @Test
    void defaults_minChunkSizeIs100() {
        RagChunkProperties props = new RagChunkProperties();
        assertEquals(100, props.getMinChunkSize());
    }

    @Test
    void setters_updateAllValues() {
        RagChunkProperties props = new RagChunkProperties();

        props.setDefaultChunkSize(500);
        props.setDefaultChunkOverlap(50);
        props.setMinChunkSize(50);

        assertEquals(500, props.getDefaultChunkSize());
        assertEquals(50, props.getDefaultChunkOverlap());
        assertEquals(50, props.getMinChunkSize());
    }

    @Test
    void setters_acceptBoundaryValues() {
        RagChunkProperties props = new RagChunkProperties();

        props.setDefaultChunkSize(0);
        props.setDefaultChunkOverlap(0);
        props.setMinChunkSize(0);

        assertEquals(0, props.getDefaultChunkSize());
        assertEquals(0, props.getDefaultChunkOverlap());
        assertEquals(0, props.getMinChunkSize());

        props.setDefaultChunkSize(Integer.MAX_VALUE);
        props.setDefaultChunkOverlap(Integer.MAX_VALUE);
        props.setMinChunkSize(Integer.MAX_VALUE);

        assertEquals(Integer.MAX_VALUE, props.getDefaultChunkSize());
        assertEquals(Integer.MAX_VALUE, props.getDefaultChunkOverlap());
        assertEquals(Integer.MAX_VALUE, props.getMinChunkSize());
    }
}
