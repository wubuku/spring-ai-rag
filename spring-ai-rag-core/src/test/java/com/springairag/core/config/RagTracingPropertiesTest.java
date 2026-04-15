package com.springairag.core.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RagTracingPropertiesTest {

    @Test
    void defaultsShouldBeEnabledWithFullSampling() {
        RagTracingProperties props = new RagTracingProperties();
        assertTrue(props.isEnabled(), "Tracing should be enabled by default");
        assertEquals(1.0, props.getSamplingRate(), "Sampling rate should be 1.0 (full tracing) by default");
        assertFalse(props.isW3cFormat(), "W3C format should be disabled by default");
        assertFalse(props.isSpanIdEnabled(), "Span ID should be disabled by default");
    }

    @Test
    void settersAndGettersShouldWork() {
        RagTracingProperties props = new RagTracingProperties();
        props.setEnabled(false);
        props.setSamplingRate(0.5);
        props.setW3cFormat(true);
        props.setSpanIdEnabled(true);

        assertFalse(props.isEnabled());
        assertEquals(0.5, props.getSamplingRate());
        assertTrue(props.isW3cFormat());
        assertTrue(props.isSpanIdEnabled());
    }

    @Test
    void samplingRateBoundaryValues() {
        RagTracingProperties props = new RagTracingProperties();
        props.setSamplingRate(0.0);
        assertEquals(0.0, props.getSamplingRate());

        props.setSamplingRate(1.0);
        assertEquals(1.0, props.getSamplingRate());
    }
}
