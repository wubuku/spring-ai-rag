package com.springairag.starter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GeneralRagProperties 单元测试
 */
class GeneralRagPropertiesTest {

    @Test
    void defaults_areCorrect() {
        GeneralRagProperties props = new GeneralRagProperties();

        assertTrue(props.isEnabled());
        assertNotNull(props.getMemory());
        assertTrue(props.getMemory().isEnabled());
        assertEquals("jdbc", props.getMemory().getType());
        assertEquals(20, props.getMemory().getMaxMessages());
    }

    @Test
    void setEnabled_false() {
        GeneralRagProperties props = new GeneralRagProperties();
        props.setEnabled(false);

        assertFalse(props.isEnabled());
    }

    @Test
    void memory_allFields() {
        GeneralRagProperties.Memory memory = new GeneralRagProperties.Memory();
        memory.setEnabled(false);
        memory.setType("redis");
        memory.setMaxMessages(50);

        assertFalse(memory.isEnabled());
        assertEquals("redis", memory.getType());
        assertEquals(50, memory.getMaxMessages());
    }

    @Test
    void setMemory_replacesDefaults() {
        GeneralRagProperties props = new GeneralRagProperties();
        GeneralRagProperties.Memory customMemory = new GeneralRagProperties.Memory();
        customMemory.setMaxMessages(100);
        customMemory.setType("inmemory");

        props.setMemory(customMemory);

        assertEquals("inmemory", props.getMemory().getType());
        assertEquals(100, props.getMemory().getMaxMessages());
    }
}
