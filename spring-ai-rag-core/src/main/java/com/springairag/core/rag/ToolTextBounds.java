package com.springairag.core.rag;

/**
 * 服务端工具参数的文本边界。
 *
 * <p>模型（尤其是在被检索内容诱导时）可能生成任意长度的工具 query。
 * 检索与嵌入链路必须在进入模型/向量服务前按预算截断，不能把超大
 * payload 直接透传。</p>
 */
public final class ToolTextBounds {

    /**
     * 单个工具 query 的字符上限。正常检索 query 远小于该值；截断只针对
     * 注入诱导或异常模型输出。
     */
    public static final int MAX_QUERY_CHARACTERS = 2_000;

    private ToolTextBounds() {
    }

    /**
     * 按 UTF-16 长度截断，并避免把代理对（emoji）劈成半个字符。
     */
    public static String truncate(String value, int maxCharacters) {
        if (value == null || value.length() <= maxCharacters) {
            return value;
        }
        int end = Math.max(0, maxCharacters);
        if (end > 0
                && end < value.length()
                && Character.isHighSurrogate(value.charAt(end - 1))
                && Character.isLowSurrogate(value.charAt(end))) {
            end--;
        }
        return value.substring(0, end);
    }

    /**
     * 工具 query 的预算截断。
     */
    public static String truncateQuery(String value) {
        return truncate(value, MAX_QUERY_CHARACTERS);
    }
}
