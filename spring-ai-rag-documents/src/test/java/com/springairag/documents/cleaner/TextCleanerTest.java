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

    // ========== Boundary Value Tests ==========

    @Test
    void clean_markdownHeaderOnly() {
        // clean() removes trailing markdown headers at end of text
        assertEquals("# Header", TextCleaner.clean("# Header"));
    }

    @Test
    void clean_markdownHeaderInline() {
        // Header in middle of text is NOT removed (only trailing)
        assertEquals("Text # Header", TextCleaner.clean("Text # Header"));
    }

    @Test
    void clean_markdownHeaderWithTrailingSpaces() {
        // clean() removes trailing headers even with trailing spaces
        assertEquals("# Header", TextCleaner.clean("# Header   "));
    }

    @Test
    void clean_controlCharsWithNewlines() {
        // Control chars that are not newlines should be removed
        String input = "Line1\u0001Line2\u0002Line3";
        String result = TextCleaner.clean(input);
        assertFalse(result.contains("\u0001"));
        assertFalse(result.contains("\u0002"));
        assertTrue(result.contains("Line1"));
        assertTrue(result.contains("Line2"));
        assertTrue(result.contains("Line3"));
    }

    @Test
    void clean_tabsCollapsed() {
        // clean() collapses spaces, tabs become part of whitespace pattern
        String input = "Word1\t\tWord2";
        String result = TextCleaner.clean(input);
        assertFalse(result.contains("\t"));
    }

    @Test
    void trimWhitespace_whitespaceOnly() {
        assertEquals("", TextCleaner.trimWhitespace("   \t   "));
    }

    @Test
    void trimWhitespace_singleWord() {
        assertEquals("Word", TextCleaner.trimWhitespace("  Word  "));
    }

    @Test
    void trimWhitespace_newlineOnly() {
        // trimWhitespace does not normalize newlines, only collapses spaces
        String input = "Word1\n\nWord2";
        String result = TextCleaner.trimWhitespace(input);
        assertTrue(result.contains("\n"));
    }

    @Test
    void normalizeLineBreaks_mixedInput() {
        String input = "A\r\nB\nC\rD";
        String result = TextCleaner.normalizeLineBreaks(input);
        assertEquals("A\nB\nC\nD", result);
    }

    @Test
    void normalizeLineBreaks_whitespaceOnly() {
        // normalizeLineBreaks does NOT trim - whitespace + newlines input stays as is (after newline normalization)
        String input = "   \r\n\r\n   ";
        String result = TextCleaner.normalizeLineBreaks(input);
        assertFalse(result.contains("\r"));
        assertTrue(result.contains("\n"));
    }

    @Test
    void normalizeLineBreaks_singleLine() {
        assertEquals("No breaks", TextCleaner.normalizeLineBreaks("No breaks"));
    }

    @Test
    void normalizeLineBreaks_preservesMultipleBreaks() {
        // Multiple consecutive newlines should be normalized to double newlines
        String input = "Para1\n\n\n\nPara2";
        String result = TextCleaner.normalizeLineBreaks(input);
        assertFalse(result.contains("\n\n\n"));
        assertTrue(result.contains("Para1\n\nPara2"));
    }

    @Test
    void cleanPreserveHeaders_preservesTrailingHashes() {
        // cleanPreserveHeaders does NOT remove markdown headers (different from clean())
        String input = "# Title\n## Subtitle\nContent";
        String result = TextCleaner.cleanPreserveHeaders(input);
        assertTrue(result.contains("# Title"));
        assertTrue(result.contains("## Subtitle"));
    }

    @Test
    void cleanPreserveHeaders_preservesNewlines() {
        String input = "Line1\nLine2\nLine3";
        String result = TextCleaner.cleanPreserveHeaders(input);
        assertTrue(result.contains("\n"));
    }

    @Test
    void clean_fullPipeline() {
        // Complete pipeline: control chars -> spaces -> newlines -> trim
        String input = "  Hello\u0000World  \n\n\n  Test  ";
        String result = TextCleaner.clean(input);
        // Verify: no control chars, no leading/trailing spaces, newlines normalized
        assertFalse(result.contains("\u0000"));
        assertTrue(result.startsWith("HelloWorld"));
        assertFalse(result.startsWith(" "));
        assertFalse(result.endsWith(" "));
        assertTrue(result.contains("\n\n"));
    }

    // ========== Additional Edge Cases ==========

    @Test
    void clean_onlyControlChars() {
        assertEquals("", TextCleaner.clean("\u0000\u0001\u0002"));
    }

    @Test
    void clean_onlyNewlines() {
        assertEquals("", TextCleaner.clean("\n\n\n"));
    }

    @Test
    void clean_mixedControlAndNormal() {
        String input = "\u0000Hello\u0001 World\u0002";
        String result = TextCleaner.clean(input);
        assertEquals("Hello World", result);
    }

    @Test
    void trimWhitespace_emptyAfterTrim() {
        assertEquals("", TextCleaner.trimWhitespace(""));
    }

    @Test
    void normalizeLineBreaks_onlyNewlines() {
        String result = TextCleaner.normalizeLineBreaks("\n\n\n");
        assertFalse(result.contains("\n\n\n"));
        assertEquals("\n\n", result);
    }

    @Test
    void cleanPreserveHeaders_controlCharsRemoved() {
        // cleanPreserveHeaders removes control chars but preserves formatting
        String input = "Title\u0000Content\nLine2";
        String result = TextCleaner.cleanPreserveHeaders(input);
        assertFalse(result.contains("\u0000"));
        assertTrue(result.contains("Title"));
    }
}
