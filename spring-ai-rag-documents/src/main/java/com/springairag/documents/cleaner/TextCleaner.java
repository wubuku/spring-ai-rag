package com.springairag.documents.cleaner;

import java.util.regex.Pattern;

/**
 * 文本清洗工具类
 *
 * <p>提供全面的文本清洗功能：
 * <ul>
 *   <li>移除多余空格和换行</li>
 *   <li>移除 Markdown 标题前缀</li>
 *   <li>规范化标点符号</li>
 *   <li>移除控制字符</li>
 * </ul>
 */
public final class TextCleaner {

    private static final Pattern MULTIPLE_SPACES = Pattern.compile(" +");
    private static final Pattern MULTIPLE_NEWLINES = Pattern.compile("\n{2,}");
    private static final Pattern MARKDOWN_HEADER_END = Pattern.compile(" #{1,6}$");
    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\p{Cntrl}&&[^\\n\\r]]");

    private TextCleaner() {}

    /**
     * 完整清洗流程
     *
     * @param input 原始文本
     * @return 清洗后的文本
     */
    public static String clean(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }

        String result = input;

        // 1. 移除控制字符（保留换行和回车）
        result = CONTROL_CHARS.matcher(result).replaceAll("");

        // 2. 移除多余空格
        result = MULTIPLE_SPACES.matcher(result).replaceAll(" ");

        // 3. 规范化换行（多个换行合并为两个）
        result = MULTIPLE_NEWLINES.matcher(result).replaceAll("\n\n");

        // 4. 移除行尾 Markdown 标题标记
        result = MARKDOWN_HEADER_END.matcher(result).replaceAll("");

        // 5. 去除首尾空白
        result = result.trim();

        return result;
    }

    /**
     * 清洗但保留标题结构
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
     * 仅移除多余空白
     */
    public static String trimWhitespace(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        return MULTIPLE_SPACES.matcher(input).replaceAll(" ").trim();
    }

    /**
     * 规范化换行符
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
