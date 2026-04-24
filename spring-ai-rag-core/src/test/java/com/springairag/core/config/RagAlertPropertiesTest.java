package com.springairag.core.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link RagAlertProperties}.
 */
class RagAlertPropertiesTest {

    @Test
    void defaultConstructor_shouldInitializeWithDefaultSloValues() {
        RagAlertProperties props = new RagAlertProperties();

        assertEquals(99.9, props.getAvailabilitySlo());
        assertEquals(500.0, props.getLatencyP50SloMs());
        assertEquals(2000.0, props.getLatencyP95SloMs());
        assertEquals(5000.0, props.getLatencyP99SloMs());
        assertEquals(0.6, props.getMrrSlo());
        assertEquals(0.85, props.getHitRateSlo());
    }

    @Test
    void setAvailabilitySlo_shouldUpdateValue() {
        RagAlertProperties props = new RagAlertProperties();

        props.setAvailabilitySlo(99.5);
        assertEquals(99.5, props.getAvailabilitySlo());
    }

    @Test
    void setLatencyP50SloMs_shouldUpdateValue() {
        RagAlertProperties props = new RagAlertProperties();

        props.setLatencyP50SloMs(300.0);
        assertEquals(300.0, props.getLatencyP50SloMs());
    }

    @Test
    void setLatencyP95SloMs_shouldUpdateValue() {
        RagAlertProperties props = new RagAlertProperties();

        props.setLatencyP95SloMs(1500.0);
        assertEquals(1500.0, props.getLatencyP95SloMs());
    }

    @Test
    void setLatencyP99SloMs_shouldUpdateValue() {
        RagAlertProperties props = new RagAlertProperties();

        props.setLatencyP99SloMs(3000.0);
        assertEquals(3000.0, props.getLatencyP99SloMs());
    }

    @Test
    void setMrrSlo_shouldUpdateValue() {
        RagAlertProperties props = new RagAlertProperties();

        props.setMrrSlo(0.8);
        assertEquals(0.8, props.getMrrSlo());
    }

    @Test
    void setHitRateSlo_shouldUpdateValue() {
        RagAlertProperties props = new RagAlertProperties();

        props.setHitRateSlo(0.9);
        assertEquals(0.9, props.getHitRateSlo());
    }

    @Test
    void allSetters_shouldUpdateCorrespondingGetters() {
        RagAlertProperties props = new RagAlertProperties();

        props.setAvailabilitySlo(99.0);
        props.setLatencyP50SloMs(400.0);
        props.setLatencyP95SloMs(1800.0);
        props.setLatencyP99SloMs(4000.0);
        props.setMrrSlo(0.75);
        props.setHitRateSlo(0.92);

        assertEquals(99.0, props.getAvailabilitySlo());
        assertEquals(400.0, props.getLatencyP50SloMs());
        assertEquals(1800.0, props.getLatencyP95SloMs());
        assertEquals(4000.0, props.getLatencyP99SloMs());
        assertEquals(0.75, props.getMrrSlo());
        assertEquals(0.92, props.getHitRateSlo());
    }
}
