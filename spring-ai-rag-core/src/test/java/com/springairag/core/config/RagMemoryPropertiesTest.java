package com.springairag.core.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for RagMemoryProperties.
 */
class RagMemoryPropertiesTest {

    @Test
    void defaults_maxMessagesIs20() {
        RagMemoryProperties props = new RagMemoryProperties();
        assertEquals(20, props.getMaxMessages());
    }

    @Test
    void defaults_messageTtlDaysIs30() {
        RagMemoryProperties props = new RagMemoryProperties();
        assertEquals(30, props.getMessageTtlDays());
    }

    @Test
    void setters_updateValues() {
        RagMemoryProperties props = new RagMemoryProperties();

        props.setMaxMessages(50);
        props.setMessageTtlDays(60);

        assertEquals(50, props.getMaxMessages());
        assertEquals(60, props.getMessageTtlDays());
    }

    @Test
    void setters_acceptBoundaryValues() {
        RagMemoryProperties props = new RagMemoryProperties();

        props.setMaxMessages(0);
        props.setMessageTtlDays(0);

        assertEquals(0, props.getMaxMessages());
        assertEquals(0, props.getMessageTtlDays());
    }
}
