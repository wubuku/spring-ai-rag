package com.springairag.documents.chunk;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the {@link TextChunk} record.
 */
class TextChunkTest {

    @Test
    @DisplayName("Record creation with all fields")
    void create_withAllFields() {
        TextChunk chunk = new TextChunk("hello world", 0, 11);

        assertEquals("hello world", chunk.text());
        assertEquals(0, chunk.startPos());
        assertEquals(11, chunk.endPos());
    }

    @Test
    @DisplayName("Start position and end position are correct (exclusive end)")
    void positions_exclusiveEnd() {
        String text = "test";
        TextChunk chunk = new TextChunk(text, 5, 9);

        assertEquals(5, chunk.startPos());
        assertEquals(9, chunk.endPos());
        // endPos is exclusive, so length should be endPos - startPos
        assertEquals(4, chunk.endPos() - chunk.startPos());
    }

    @Test
    @DisplayName("Empty text chunk is valid")
    void emptyText_isValid() {
        TextChunk chunk = new TextChunk("", 0, 0);

        assertEquals("", chunk.text());
        assertEquals(0, chunk.startPos());
        assertEquals(0, chunk.endPos());
    }

    @Test
    @DisplayName("Single character chunk")
    void singleChar() {
        TextChunk chunk = new TextChunk("A", 10, 11);

        assertEquals("A", chunk.text());
        assertEquals(10, chunk.startPos());
        assertEquals(11, chunk.endPos());
    }

    @Test
    @DisplayName("TextChunk equality - same values are equal")
    void equals_sameValues() {
        TextChunk chunk1 = new TextChunk("hello", 0, 5);
        TextChunk chunk2 = new TextChunk("hello", 0, 5);

        assertEquals(chunk1, chunk2);
        assertEquals(chunk1.hashCode(), chunk2.hashCode());
    }

    @Test
    @DisplayName("TextChunk equality - different text is not equal")
    void equals_differentText() {
        TextChunk chunk1 = new TextChunk("hello", 0, 5);
        TextChunk chunk2 = new TextChunk("world", 0, 5);

        assertNotEquals(chunk1, chunk2);
    }

    @Test
    @DisplayName("TextChunk equality - different startPos is not equal")
    void equals_differentStartPos() {
        TextChunk chunk1 = new TextChunk("hello", 0, 5);
        TextChunk chunk2 = new TextChunk("hello", 1, 6);

        assertNotEquals(chunk1, chunk2);
    }

    @Test
    @DisplayName("TextChunk equality - different endPos is not equal")
    void equals_differentEndPos() {
        TextChunk chunk1 = new TextChunk("hello", 0, 5);
        TextChunk chunk2 = new TextChunk("hello", 0, 6);

        assertNotEquals(chunk1, chunk2);
    }

    @Test
    @DisplayName("TextChunk toString includes all fields")
    void toString_includesAllFields() {
        TextChunk chunk = new TextChunk("test", 3, 7);
        String str = chunk.toString();

        assertTrue(str.contains("test"));
        assertTrue(str.contains("3"));
        assertTrue(str.contains("7"));
    }

    @Test
    @DisplayName("Null text is allowed")
    void nullText_isAllowed() {
        TextChunk chunk = new TextChunk(null, 0, 0);

        assertNull(chunk.text());
        assertEquals(0, chunk.startPos());
        assertEquals(0, chunk.endPos());
    }

    @Test
    @DisplayName("Chunk length matches text length")
    void chunkLength_matchesTextLength() {
        String content = "The quick brown fox";
        TextChunk chunk = new TextChunk(content, 0, content.length());

        assertEquals(content.length(), chunk.endPos() - chunk.startPos());
    }

    @Test
    @DisplayName("Chunks at different positions in same text can be equal only if values match")
    void equals_sameTextDifferentPositions() {
        // Same text content but different positions -> not equal
        TextChunk chunk1 = new TextChunk("test", 0, 4);
        TextChunk chunk2 = new TextChunk("test", 5, 9);

        assertNotEquals(chunk1, chunk2);
    }
}
