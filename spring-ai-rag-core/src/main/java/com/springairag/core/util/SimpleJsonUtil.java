package com.springairag.core.util;

import java.util.Map;

/**
 * 简单 JSON 工具类
 *
 * <p>用于将 Map 转为 JSON 字符串，避免在 core 模块引入 Jackson 依赖。
 * 仅支持基本类型（String、Number、Boolean、null）的值。
 */
public final class SimpleJsonUtil {

    private SimpleJsonUtil() {}

    /**
     * 将 Map 转为 JSON 字符串
     *
     * <p>支持的值类型：String、Number、Boolean、null。
     * 不支持嵌套对象、数组等复杂类型。
     */
    public static String toJson(Map<String, Object> map) {
        if (map == null) return "null";
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(escape(entry.getKey())).append("\":");
            appendValue(sb, entry.getValue());
        }
        sb.append("}");
        return sb.toString();
    }

    private static void appendValue(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof Number || value instanceof Boolean) {
            sb.append(value);
        } else {
            sb.append("\"").append(escape(value.toString())).append("\"");
        }
    }

    /**
     * 转义 JSON 字符串中的特殊字符
     */
    public static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
