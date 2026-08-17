package com.springairag.core.retrieval;

/**
 * 已校验并序列化的 JSONB containment 检索条件。
 */
public record JsonbContainmentFilter(String canonicalJson) {

    public JsonbContainmentFilter {
        if (canonicalJson == null || canonicalJson.isBlank()) {
            throw new IllegalArgumentException(
                    "JSONB containment filter must not be blank");
        }
    }
}
