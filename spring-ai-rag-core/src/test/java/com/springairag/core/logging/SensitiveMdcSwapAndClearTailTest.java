package com.springairag.core.logging;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SensitiveMdc 转换与 MDC 操作长尾（Batch 690，JaCoCo 驱动）：
 * swapCamelToUnderscore 的 camelCase → snake_case 转换、MDC put
 * 的敏感/非敏感分支、clear 操作。
 */
class SensitiveMdcSwapAndClearTailTest {

    private String swapCamelToUnderscore(String s) throws Exception {
        Method method = SensitiveMdc.class
                .getDeclaredMethod("swapCamelToUnderscore", String.class);
        method.setAccessible(true);
        return (String) method.invoke(null, s);
    }

    @AfterEach
    void tearDown() {
        org.slf4j.MDC.clear();
    }

    @Test
    void swapCamelToUnderscoreConvertsCorrectly() throws Exception {
        assertEquals("private_key", swapCamelToUnderscore("privateKey"));
        assertEquals("access_token", swapCamelToUnderscore("accessToken"));
        assertEquals("refresh_token", swapCamelToUnderscore("refreshToken"));
        assertEquals("secret_key", swapCamelToUnderscore("secretKey"));
    }

    @Test
    void swapCamelToUnderscoreNoUppercaseReturnsOriginal() throws Exception {
        assertEquals("lowercase", swapCamelToUnderscore("lowercase"));
        assertEquals("", swapCamelToUnderscore(""));
    }

    @Test
    void putSensitiveKeyMasksValue() {
        SensitiveMdc.put("password", "secret123");
        assertEquals("[MASKED]", org.slf4j.MDC.get("password"));
        SensitiveMdc.put("normalKey", "normalValue");
        assertEquals("normalValue", org.slf4j.MDC.get("normalKey"));
    }

    @Test
    void putAllMasksSensitiveEntries() {
        SensitiveMdc.putAll(Map.of(
                "userId", "user-1",
                "api_key", "key-123",
                "environment", "prod"));
        assertEquals("[MASKED]", org.slf4j.MDC.get("api_key"));
        assertEquals("user-1", org.slf4j.MDC.get("userId"));
        assertEquals("prod", org.slf4j.MDC.get("environment"));
    }

    @Test
    void clearRemovesAllEntries() {
        SensitiveMdc.put("key1", "value1");
        SensitiveMdc.put("key2", "value2");
        SensitiveMdc.clear();
        assertEquals(null, org.slf4j.MDC.get("key1"));
        assertEquals(null, org.slf4j.MDC.get("key2"));
    }
}
