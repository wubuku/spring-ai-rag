package com.springairag.core.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for RagSecurityProperties.
 */
class RagSecurityPropertiesTest {

    @Test
    void defaults_apiKeyIsEmpty() {
        RagSecurityProperties props = new RagSecurityProperties();
        assertEquals("", props.getApiKey());
    }

    @Test
    void defaults_enabledIsFalse() {
        RagSecurityProperties props = new RagSecurityProperties();
        assertFalse(props.isEnabled());
    }

    @Test
    void setters_updateAllValues() {
        RagSecurityProperties props = new RagSecurityProperties();

        props.setApiKey("sk-secure-api-key-12345");
        props.setEnabled(true);

        assertEquals("sk-secure-api-key-12345", props.getApiKey());
        assertTrue(props.isEnabled());
    }

    @Test
    void setters_acceptBoundaryValues() {
        RagSecurityProperties props = new RagSecurityProperties();

        props.setApiKey("");
        props.setEnabled(false);

        assertEquals("", props.getApiKey());
        assertFalse(props.isEnabled());
    }

    @Test
    void apiKey_acceptsVariousFormats() {
        RagSecurityProperties props = new RagSecurityProperties();

        props.setApiKey("sk-xxx");
        assertEquals("sk-xxx", props.getApiKey());

        props.setApiKey("rag_k_abc123def456");
        assertEquals("rag_k_abc123def456", props.getApiKey());

        props.setApiKey("rgapi_1234567890abcdef");
        assertEquals("rgapi_1234567890abcdef", props.getApiKey());
    }
}
