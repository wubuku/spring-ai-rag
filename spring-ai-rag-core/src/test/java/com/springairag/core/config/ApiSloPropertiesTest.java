package com.springairag.core.config;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ApiSloProperties}.
 */
class ApiSloPropertiesTest {

    @Test
    void defaultConstructor_shouldInitializeWithDefaultThresholds() {
        ApiSloProperties props = new ApiSloProperties();

        assertTrue(props.isEnabled());
        assertEquals(300, props.getWindowSeconds());
        assertEquals(500L, props.getThreshold("rag.search.post"));
        assertEquals(500L, props.getThreshold("rag.search.get"));
        assertEquals(1000L, props.getThreshold("rag.chat.ask"));
        assertEquals(1500L, props.getThreshold("rag.chat.stream"));
        assertEquals(2000L, props.getThreshold("rag.documents.embed"));
    }

    @Test
    void getThreshold_shouldReturnConfiguredValue() {
        ApiSloProperties props = new ApiSloProperties();

        assertEquals(500L, props.getThreshold("rag.search.post"));
        assertEquals(1000L, props.getThreshold("rag.chat.ask"));
    }

    @Test
    void getThreshold_shouldReturnDefaultForUnknownEndpoint() {
        ApiSloProperties props = new ApiSloProperties();

        assertEquals(500L, props.getThreshold("unknown.endpoint"));
        assertEquals(500L, props.getThreshold(""));
        assertEquals(500L, props.getThreshold("rag.unknown.post"));
    }

    @Test
    void setEnabled_shouldUpdateEnabledFlag() {
        ApiSloProperties props = new ApiSloProperties();

        props.setEnabled(false);
        assertFalse(props.isEnabled());

        props.setEnabled(true);
        assertTrue(props.isEnabled());
    }

    @Test
    void setWindowSeconds_shouldUpdateWindow() {
        ApiSloProperties props = new ApiSloProperties();

        props.setWindowSeconds(600);
        assertEquals(600, props.getWindowSeconds());
    }

    @Test
    void setThresholds_shouldReplaceDefaultThresholds() {
        ApiSloProperties props = new ApiSloProperties();
        Map<String, Long> newThresholds = new HashMap<>();
        newThresholds.put("rag.search.post", 300L);
        newThresholds.put("custom.endpoint", 800L);

        props.setThresholds(newThresholds);

        assertEquals(300L, props.getThreshold("rag.search.post"));
        assertEquals(800L, props.getThreshold("custom.endpoint"));
        // Unknown endpoints still return default
        assertEquals(500L, props.getThreshold("rag.chat.ask"));
    }

    @Test
    void emptyThresholds_shouldReturnDefaultForAllEndpoints() {
        ApiSloProperties props = new ApiSloProperties();
        props.setThresholds(new HashMap<>());

        assertEquals(500L, props.getThreshold("rag.search.post"));
        assertEquals(500L, props.getThreshold("rag.chat.ask"));
        assertEquals(500L, props.getThreshold("any.endpoint"));
    }
}
