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

    // ─────────────────────────────────────────────────────────
    // GeneralRagProperties.Memory equals / hashCode / toString
    // ─────────────────────────────────────────────────────────

    @Test
    void memory_equals_sameFields() {
        GeneralRagProperties.Memory a = new GeneralRagProperties.Memory();
        a.setEnabled(true);
        a.setType("jdbc");
        a.setMaxMessages(20);

        GeneralRagProperties.Memory b = new GeneralRagProperties.Memory();
        b.setEnabled(true);
        b.setType("jdbc");
        b.setMaxMessages(20);

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void memory_equals_differentFields() {
        GeneralRagProperties.Memory a = new GeneralRagProperties.Memory();
        a.setType("jdbc");

        GeneralRagProperties.Memory b = new GeneralRagProperties.Memory();
        b.setType("redis");

        assertNotEquals(a, b);
    }

    @Test
    void memory_equals_nullType() {
        GeneralRagProperties.Memory a = new GeneralRagProperties.Memory();
        a.setType(null);

        GeneralRagProperties.Memory b = new GeneralRagProperties.Memory();
        b.setType(null);

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void memory_equals_differentTypes() {
        GeneralRagProperties.Memory memory = new GeneralRagProperties.Memory();
        assertNotEquals(memory, "not-a-memory");
        assertNotEquals(memory, null);
    }

    @Test
    void memory_toString_containsAllFields() {
        GeneralRagProperties.Memory memory = new GeneralRagProperties.Memory();
        memory.setEnabled(true);
        memory.setType("jdbc");
        memory.setMaxMessages(20);

        String str = memory.toString();
        assertTrue(str.contains("enabled=true"));
        assertTrue(str.contains("type='jdbc'"));
        assertTrue(str.contains("maxMessages=20"));
    }
}
