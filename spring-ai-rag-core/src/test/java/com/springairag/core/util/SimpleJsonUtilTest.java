package com.springairag.core.util;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SimpleJsonUtil 单元测试
 */
class SimpleJsonUtilTest {

    @Test
    void toJson_nullMap_returnsNull() {
        assertEquals("null", SimpleJsonUtil.toJson(null));
    }

    @Test
    void toJson_emptyMap_returnsEmptyObject() {
        assertEquals("{}", SimpleJsonUtil.toJson(Map.of()));
    }

    @Test
    void toJson_stringValue() {
        Map<String, Object> map = Map.of("key", "value");
        assertEquals("{\"key\":\"value\"}", SimpleJsonUtil.toJson(map));
    }

    @Test
    void toJson_numberValue() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("count", 42);
        map.put("score", 3.14);
        assertEquals("{\"count\":42,\"score\":3.14}", SimpleJsonUtil.toJson(map));
    }

    @Test
    void toJson_booleanValue() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("enabled", true);
        assertEquals("{\"enabled\":true}", SimpleJsonUtil.toJson(map));
    }

    @Test
    void toJson_nullValue() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("key", null);
        assertEquals("{\"key\":null}", SimpleJsonUtil.toJson(map));
    }

    @Test
    void toJson_specialChars_areEscaped() {
        Map<String, Object> map = Map.of("text", "line1\nline2\ttab\"quote");
        assertEquals("{\"text\":\"line1\\nline2\\ttab\\\"quote\"}", SimpleJsonUtil.toJson(map));
    }

    @Test
    void toJson_multipleEntries() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", "test");
        map.put("count", 10);
        map.put("enabled", false);
        String json = SimpleJsonUtil.toJson(map);
        assertEquals("{\"name\":\"test\",\"count\":10,\"enabled\":false}", json);
    }

    @Test
    void escape_null_returnsEmpty() {
        assertEquals("", SimpleJsonUtil.escape(null));
    }

    @Test
    void escape_normalString_noChange() {
        assertEquals("hello world", SimpleJsonUtil.escape("hello world"));
    }

    @Test
    void escape_backslashAndQuotes() {
        assertEquals("a\\\\b\\\"c", SimpleJsonUtil.escape("a\\b\"c"));
    }
}
