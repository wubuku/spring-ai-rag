package com.springairag.core.config;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for RagEmbeddingProperties.
 */
class RagEmbeddingPropertiesTest {

    @Test
    void defaults_apiKeyIsEmpty() {
        RagEmbeddingProperties props = new RagEmbeddingProperties();
        assertEquals("", props.getApiKey());
    }

    @Test
    void defaults_baseUrlIsSiliconFlow() {
        RagEmbeddingProperties props = new RagEmbeddingProperties();
        assertEquals("https://api.siliconflow.cn", props.getBaseUrl());
    }

    @Test
    void defaults_modelIsBGE_M3() {
        RagEmbeddingProperties props = new RagEmbeddingProperties();
        assertEquals("BAAI/bge-m3", props.getModel());
    }

    @Test
    void defaults_dimensionsIs1024() {
        RagEmbeddingProperties props = new RagEmbeddingProperties();
        assertEquals(1024, props.getDimensions());
    }

    @Test
    void setters_updateAllValues() {
        RagEmbeddingProperties props = new RagEmbeddingProperties();

        props.setApiKey("sk-test-key");
        props.setBaseUrl("https://custom.embedding.api");
        props.setModel("custom/bge-m3-v1.5");
        props.setDimensions(2048);

        assertEquals("sk-test-key", props.getApiKey());
        assertEquals("https://custom.embedding.api", props.getBaseUrl());
        assertEquals("custom/bge-m3-v1.5", props.getModel());
        assertEquals(2048, props.getDimensions());
    }

    @Test
    void setters_acceptBoundaryValues() {
        RagEmbeddingProperties props = new RagEmbeddingProperties();

        props.setApiKey("");
        props.setBaseUrl("");
        props.setModel("");
        props.setDimensions(0);

        assertEquals("", props.getApiKey());
        assertEquals("", props.getBaseUrl());
        assertEquals("", props.getModel());
        assertEquals(0, props.getDimensions());
    }

    @Test
    void dimensions_acceptsTypicalValues() {
        RagEmbeddingProperties props = new RagEmbeddingProperties();

        props.setDimensions(768);
        assertEquals(768, props.getDimensions());

        props.setDimensions(1536);
        assertEquals(1536, props.getDimensions());

        props.setDimensions(4096);
        assertEquals(4096, props.getDimensions());
    }
}
