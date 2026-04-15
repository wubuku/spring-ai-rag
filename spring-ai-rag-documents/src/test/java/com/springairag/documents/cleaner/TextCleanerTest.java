package com.springairag.documents.cleaner;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TextCleaner Tests
 */
class TextCleanerTest {

    @Test
    void clean_nullInput() {
        assertEquals("", TextCleaner.clean(null));
    }

    @Test
    void clean_emptyInput() {
        assertEquals("", TextCleaner.clean(""));
    }

    @Test
    void clean_multipleSpaces() {
        String input = "Hello    World   Test";
        assertEquals("Hello World Test", TextCleaner.clean(input));
    }

    @Test
    void clean_multipleNewlines() {
        String input = "Line1\n\n\n\nLine2";
        assertEquals("Line1\n\nLine2", TextCleaner.clean(input));
    }

    @Test
    void clean_controlChars() {
        String input = "Hello\u0000World\u0007Test";
        assertEquals("HelloWorldTest", TextCleaner.clean(input));
    }

    @Test
    void clean_preserveNewlines() {
        String input = "Line1\nLine2\nLine3";
        String result = TextCleaner.clean(input);
        assertTrue(result.contains("\n"));
    }

    @Test
    void cleanPreserveHeaders() {
        String input = "# Title\n\nSome content";
        String result = TextCleaner.cleanPreserveHeaders(input);
        assertTrue(result.startsWith("# Title"));
    }

    @Test
    void trimWhitespace_basic() {
        assertEquals("hello world", TextCleaner.trimWhitespace("  hello   world  "));
    }

    @Test
    void trimWhitespace_null() {
        assertEquals("", TextCleaner.trimWhitespace(null));
    }

    @Test
    void normalizeLineBreaks_crlf() {
        String input = "Line1\r\nLine2\rLine3\nLine4";
        String result = TextCleaner.normalizeLineBreaks(input);
        assertEquals("Line1\nLine2\nLine3\nLine4", result);
    }

    @Test
    void normalizeLineBreaks_nullInput() {
        assertEquals("", TextCleaner.normalizeLineBreaks(null));
    }

    @Test
    void normalizeLineBreaks_emptyInput() {
        assertEquals("", TextCleaner.normalizeLineBreaks(""));
    }

    @Test
    void clean_whitespaceOnlyInput() {
        assertEquals("", TextCleaner.clean("   \t\n\n   "));
    }

    @Test
    void cleanPreserveHeaders_nullInput() {
        assertEquals("", TextCleaner.cleanPreserveHeaders(null));
    }

    @Test
    void cleanPreserveHeaders_emptyInput() {
        assertEquals("", TextCleaner.cleanPreserveHeaders(""));
    }
}
