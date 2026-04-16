package com.springairag.core.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CorsConfig Unit Tests
 */
class CorsConfigTest {

    @Test
    void corsProperties_defaults() {
        RagCorsProperties cors = new RagCorsProperties();

        assertFalse(cors.isEnabled());
        assertEquals(List.of("*"), cors.getAllowedOrigins());
        assertEquals("GET,POST,PUT,DELETE,OPTIONS", cors.getAllowedMethods());
        assertEquals("*", cors.getAllowedHeaders());
        assertEquals(3600, cors.getMaxAge());
    }

    @Test
    void corsProperties_settersWork() {
        RagCorsProperties cors = new RagCorsProperties();
        cors.setEnabled(true);
        cors.setAllowedOrigins(List.of("https://example.com", "http://localhost:3000"));
        cors.setAllowedMethods("GET,POST");
        cors.setAllowedHeaders("Content-Type,Authorization");
        cors.setMaxAge(7200);

        assertTrue(cors.isEnabled());
        assertEquals(2, cors.getAllowedOrigins().size());
        assertEquals("https://example.com", cors.getAllowedOrigins().get(0));
        assertEquals("GET,POST", cors.getAllowedMethods());
        assertEquals("Content-Type,Authorization", cors.getAllowedHeaders());
        assertEquals(7200, cors.getMaxAge());
    }

    @Test
    void corsProperties_inRagProperties() {
        RagProperties props = new RagProperties();
        assertNotNull(props.getCors());
        assertFalse(props.getCors().isEnabled());
    }

    @Test
    void corsConfig_usesProperties() {
        RagProperties props = new RagProperties();
        props.getCors().setEnabled(true);
        props.getCors().setAllowedOrigins(List.of("https://example.com"));

        CorsConfig config = new CorsConfig(props);
        assertNotNull(config);
    }
}
