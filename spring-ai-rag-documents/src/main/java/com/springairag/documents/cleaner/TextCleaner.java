package com.springairag.documents.cleaner;

import java.util.regex.Pattern;

/**
 * Text cleaning utility class
 *
 * <p>Provides comprehensive text cleaning:
 * <ul>
 *   <li>Remove extra spaces and newlines</li>
 *   <li>Remove Markdown heading prefixes</li>
 *   <li>Normalize punctuation</li>
 *   <li>Remove control characters</li>
 * </ul>
 */
public final class TextCleaner {

    private static final Pattern MULTIPLE_SPACES = Pattern.compile(" +");
    private static final Pattern MULTIPLE_NEWLINES = Pattern.compile("\n{2,}");
    private static final Pattern MARKDOWN_HEADER_END = Pattern.compile(" #{1,6}$");
    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\p{Cntrl}&&[^\\n\\r]]");

    private TextCleaner() {}

    /**
     * Full cleaning flow
     *
     * @param input raw text
     * @return cleaned text
     */
    public static String clean(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }

        String result = input;

        // 1. Remove control characters (preserve newlines and carriage returns)
        result = CONTROL_CHARS.matcher(result).replaceAll("");

        // 2. Remove extra spaces
        result = MULTIPLE_SPACES.matcher(result).replaceAll(" ");

        // 3. Normalize newlines (merge multiple newlines into two)
        result = MULTIPLE_NEWLINES.matcher(result).replaceAll("\n\n");

        // 4. Remove trailing Markdown heading markers
        result = MARKDOWN_HEADER_END.matcher(result).replaceAll("");

        // 5. Trim leading and trailing whitespace
        result = result.trim();

        return result;
    }

    /**
     * Clean while preserving heading structure
     */
    public static String cleanPreserveHeaders(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }

        String result = input;
        result = CONTROL_CHARS.matcher(result).replaceAll("");
        result = MULTIPLE_SPACES.matcher(result).replaceAll(" ");
        result = MULTIPLE_NEWLINES.matcher(result).replaceAll("\n\n");
        return result.trim();
    }

    /**
     * Only remove extra spaces
     */
    public static String trimWhitespace(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        return MULTIPLE_SPACES.matcher(input).replaceAll(" ").trim();
    }

    /**
     * Normalize line endings
     */
    public static String normalizeLineBreaks(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        String result = input.replaceAll("\\r\\n", "\n");
        result = result.replaceAll("\\r", "\n");
        result = MULTIPLE_NEWLINES.matcher(result).replaceAll("\n\n");
        return result;
    }
}
