package com.springairag.core.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for RagPdfProperties.
 */
class RagPdfPropertiesTest {

    @Test
    void defaults_areCorrect() {
        RagPdfProperties props = new RagPdfProperties();
        assertTrue(props.isEnabled());
        assertEquals("marker_single", props.getMarkerCli());
        assertEquals("zh", props.getLangs());
        assertEquals("/tmp/spring-ai-rag/pdf-imports", props.getOutputBaseDir());
        assertEquals("", props.getExtraArgs());
        assertEquals(20L * 1024 * 1024, props.getMaxUploadBytes());
        assertEquals(500, props.getMaxPages());
        assertEquals(256, props.getMaxOutputFiles());
        assertEquals(200L * 1024 * 1024, props.getMaxOutputBytes());
        assertEquals(Duration.ofMinutes(5), props.getConversionTimeout());
        assertEquals(32L * 1024 * 1024, props.getMaxInMemoryBytes());
    }

    @Test
    void setters_updateAllValues() {
        RagPdfProperties props = new RagPdfProperties();

        props.setEnabled(false);
        props.setMarkerCli("/usr/bin/marker_single");
        props.setLangs("en,zh");
        props.setOutputBaseDir("/var/tmp/pdf-imports");
        props.setExtraArgs("--no-markdown-template");
        props.setMaxUploadBytes(10);
        props.setMaxPages(11);
        props.setMaxOutputFiles(12);
        props.setMaxOutputBytes(13);
        props.setConversionTimeout(Duration.ofSeconds(14));
        props.setMaxInMemoryBytes(15);

        assertFalse(props.isEnabled());
        assertEquals("/usr/bin/marker_single", props.getMarkerCli());
        assertEquals("en,zh", props.getLangs());
        assertEquals("/var/tmp/pdf-imports", props.getOutputBaseDir());
        assertEquals("--no-markdown-template", props.getExtraArgs());
        assertEquals(10, props.getMaxUploadBytes());
        assertEquals(11, props.getMaxPages());
        assertEquals(12, props.getMaxOutputFiles());
        assertEquals(13, props.getMaxOutputBytes());
        assertEquals(Duration.ofSeconds(14), props.getConversionTimeout());
        assertEquals(15, props.getMaxInMemoryBytes());
    }

    @Test
    void setters_acceptEmptyStrings() {
        RagPdfProperties props = new RagPdfProperties();

        props.setMarkerCli("");
        props.setLangs("");
        props.setOutputBaseDir("");
        props.setExtraArgs("");

        assertEquals("", props.getMarkerCli());
        assertEquals("", props.getLangs());
        assertEquals("", props.getOutputBaseDir());
        assertEquals("", props.getExtraArgs());
    }
}
