package com.springairag.core.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RagCorsPropertiesTest {

    @Test
    void defaultsShouldBeDisabledWithWildcardOrigin() {
        RagCorsProperties props = new RagCorsProperties();
        assertFalse(props.isEnabled(), "CORS should be disabled by default");
        assertEquals(List.of("*"), props.getAllowedOrigins());
        assertEquals("GET,POST,PUT,DELETE,OPTIONS", props.getAllowedMethods());
        assertEquals("*", props.getAllowedHeaders());
        assertEquals(3600L, props.getMaxAge());
    }

    @Test
    void settersAndGettersShouldWork() {
        RagCorsProperties props = new RagCorsProperties();
        props.setEnabled(true);
        props.setAllowedOrigins(List.of("https://example.com", "http://localhost:3000"));
        props.setAllowedMethods("GET,POST");
        props.setAllowedHeaders("Authorization,Content-Type");
        props.setMaxAge(7200L);

        assertTrue(props.isEnabled());
        assertEquals(List.of("https://example.com", "http://localhost:3000"), props.getAllowedOrigins());
        assertEquals("GET,POST", props.getAllowedMethods());
        assertEquals("Authorization,Content-Type", props.getAllowedHeaders());
        assertEquals(7200L, props.getMaxAge());
    }

    @Test
    void allowedOriginsShouldSupportEmptyList() {
        RagCorsProperties props = new RagCorsProperties();
        props.setAllowedOrigins(List.of());
        assertEquals(List.of(), props.getAllowedOrigins());
    }
}
