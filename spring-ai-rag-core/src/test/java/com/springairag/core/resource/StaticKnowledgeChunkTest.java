package com.springairag.core.resource;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 覆盖静态知识块 record 的必填校验与集合防御性拷贝。
 */
class StaticKnowledgeChunkTest {

    private StaticKnowledgeChunk validChunk() {
        return new StaticKnowledgeChunk(
                "chunk-1", "root", "Root Docs", "docs/root.md",
                "digest-1", 0, "Root/Intro", "hello world",
                List.of("hello"), Map.of("k", "v"));
    }

    @Test
    void acceptsValidChunkAndNormalizesCollections() {
        StaticKnowledgeChunk chunk = new StaticKnowledgeChunk(
                "chunk-1", "root", "Root Docs", "docs/root.md",
                "digest-1", 0, "Root/Intro", "hello",
                null, null);

        assertEquals(List.of(), chunk.terms());
        assertEquals(Map.of(), chunk.metadata());
    }

    @Test
    void rejectsBlankRequiredIdentityFields() {
        assertThrows(IllegalArgumentException.class, () -> new StaticKnowledgeChunk(
                " ", "root", "s", "p", "d", 0, "t", "text", List.of(), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new StaticKnowledgeChunk(
                "c", " ", "s", "p", "d", 0, "t", "text", List.of(), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new StaticKnowledgeChunk(
                "c", "root", "s", " ", "d", 0, "t", "text", List.of(), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new StaticKnowledgeChunk(
                "c", "root", "s", "p", "d", 0, "t", "  ", List.of(), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new StaticKnowledgeChunk(
                null, "root", "s", "p", "d", 0, "t", "text", List.of(), Map.of()));
    }

    @Test
    void defensiveCopiesAreImmutable() {
        StaticKnowledgeChunk chunk = validChunk();

        assertThrows(UnsupportedOperationException.class,
                () -> chunk.terms().add("mutate"));
        assertThrows(UnsupportedOperationException.class,
                () -> chunk.metadata().put("mutate", "x"));
        assertTrue(chunk.text().contains("hello"));
    }
}
