package com.springairag.core.logging;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SensitiveMdc 键名边界矩阵（Batch 407）：子串匹配、大小写、
 * snake↔camel 双向变体、下划线边界（双下划线/前导/尾随）、
 * 空串与 null 的既有语义。
 */
class SensitiveMdcBoundaryTest {

    @Test
    void nullKeyIsNeverSensitive() {
        assertFalse(SensitiveMdc.isSensitiveKey(null));
    }

    @Test
    void emptyKeyMatchesSubstringQuirk() {
        // 既有语义：空串是任何键的子串 → 判定为敏感。
        assertTrue(SensitiveMdc.isSensitiveKey(""));
    }

    @Test
    void candidateThatIsSubstringOfSensitiveNameIsSensitive() {
        // 既有语义（单向）：候选键是敏感键的子串 → 敏感。
        assertTrue(SensitiveMdc.isSensitiveKey("pass"));
        assertTrue(SensitiveMdc.isSensitiveKey("key"));
        assertTrue(SensitiveMdc.isSensitiveKey("token"));
    }

    @Test
    void keysMerelyContainingSensitiveWordsAreNotSensitive() {
        // 反向不成立：候选键包含敏感词不判定敏感（含 snake 组合）。
        assertFalse(SensitiveMdc.isSensitiveKey("login_password_check"));
        assertFalse(SensitiveMdc.isSensitiveKey("cvv_code"));
        assertFalse(SensitiveMdc.isSensitiveKey("refresh_token2"));
    }

    @Test
    void caseInsensitiveExactMatchIsSensitive() {
        assertTrue(SensitiveMdc.isSensitiveKey("AUTH"));
        assertTrue(SensitiveMdc.isSensitiveKey("ClientSecret"));
    }

    @Test
    void underscoreVariantsResolveThroughCamelSwap() {
        // 标准 snake 命中精确集合。
        assertTrue(SensitiveMdc.isSensitiveKey("private_key"));
        // 双下划线/尾随下划线经 camel 归一后仍命中。
        assertTrue(SensitiveMdc.isSensitiveKey("access__token"));
        assertTrue(SensitiveMdc.isSensitiveKey("access_token_"));
        // 既有语义：前导下划线归一为 "Secret"，集合判定大小写敏感
        // → 不再命中（swap 产物首字母大写不匹配小写集合项）。
        assertFalse(SensitiveMdc.isSensitiveKey("_secret"));
    }

    @Test
    void ordinaryKeysAreNotSensitive() {
        assertFalse(SensitiveMdc.isSensitiveKey("totally-fine-field"));
        assertFalse(SensitiveMdc.isSensitiveKey("userProfile"));
        assertFalse(SensitiveMdc.isSensitiveKey("api2key"));
        // 连字符命名不在 snake/camel 归一范围内 → 不敏感（既有语义）。
        assertFalse(SensitiveMdc.isSensitiveKey("x-api-key"));
    }

    @Test
    void putMasksBoundarySensitiveKeys() {
        try {
            SensitiveMdc.put("access__token", "raw-value");
            SensitiveMdc.put("totally-fine-field", "raw-value");
            assertEquals("[MASKED]", MDC.get("access__token"));
            assertEquals("raw-value", MDC.get("totally-fine-field"));
        } finally {
            SensitiveMdc.clear();
        }
    }

    @Test
    void putAllHandlesMixedAndNullEntries() {
        try {
            SensitiveMdc.putAll(null);
            SensitiveMdc.putAll(Map.of("apiKey", "k", "tenant", "acme"));
            assertEquals("[MASKED]", MDC.get("apiKey"));
            assertEquals("acme", MDC.get("tenant"));
        } finally {
            SensitiveMdc.clear();
        }
    }
}
