package com.springairag.core.config;

import org.junit.jupiter.api.Test;

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
    }

    @Test
    void setters_updateAllValues() {
        RagPdfProperties props = new RagPdfProperties();

        props.setEnabled(false);
        props.setMarkerCli("/usr/bin/marker_single");
        props.setLangs("en,zh");
        props.setOutputBaseDir("/var/tmp/pdf-imports");
        props.setExtraArgs("--no-markdown-template");

        assertFalse(props.isEnabled());
        assertEquals("/usr/bin/marker_single", props.getMarkerCli());
        assertEquals("en,zh", props.getLangs());
        assertEquals("/var/tmp/pdf-imports", props.getOutputBaseDir());
        assertEquals("--no-markdown-template", props.getExtraArgs());
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
