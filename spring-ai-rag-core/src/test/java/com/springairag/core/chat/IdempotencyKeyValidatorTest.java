package com.springairag.core.chat;

import com.springairag.core.exception.RagException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class IdempotencyKeyValidatorTest {

    @Test
    void normalizeAcceptsSimpleValue() {
        assertEquals("key-1", IdempotencyKeyValidator.normalize(List.of("key-1")));
    }

    @Test
    void normalizeTrimsOws() {
        assertEquals("key", IdempotencyKeyValidator.normalize(List.of("  \t key \t ")));
    }

    @Test
    void normalizeReturnsNullForNullList() {
        assertNull(IdempotencyKeyValidator.normalize(null));
    }

    @Test
    void normalizeReturnsNullForEmptyList() {
        assertNull(IdempotencyKeyValidator.normalize(List.of()));
    }

    @Test
    void normalizeRejectsMultipleValues() {
        assertThrows(RagException.class,
                () -> IdempotencyKeyValidator.normalize(List.of("a", "b")));
    }

    @Test
    void normalizeRejectsNullElement() {
        List<String> list = new java.util.ArrayList<>();
        list.add(null);
        assertThrows(RagException.class,
                () -> IdempotencyKeyValidator.normalize(list));
    }

    @Test
    void normalizeRejectsEmptyAfterTrim() {
        assertThrows(RagException.class,
                () -> IdempotencyKeyValidator.normalize(List.of("   ")));
    }

    @Test
    void normalizeRejectsTooLongValue() {
        assertThrows(RagException.class,
                () -> IdempotencyKeyValidator.normalize(List.of("x".repeat(256))));
    }

    @Test
    void normalizeAcceptsMaxLengthValue() {
        assertEquals("x".repeat(255),
                IdempotencyKeyValidator.normalize(List.of("x".repeat(255))));
    }

    @Test
    void normalizeRejectsNonAsciiCharacters() {
        assertThrows(RagException.class,
                () -> IdempotencyKeyValidator.normalize(List.of("中文key")));
    }

    @Test
    void normalizeRejectsControlCharacters() {
        assertThrows(RagException.class,
                () -> IdempotencyKeyValidator.normalize(List.of("key\u0000value")));
    }

    @Test
    void normalizeRejectsComma() {
        assertThrows(RagException.class,
                () -> IdempotencyKeyValidator.normalize(List.of("key,value")));
    }

    @Test
    void hashProducesDeterministicSha256() {
        String h1 = IdempotencyKeyValidator.hash("idempotency-key");
        String h2 = IdempotencyKeyValidator.hash("idempotency-key");
        assertEquals(h1, h2);
        assertEquals(64, h1.length());
    }

    @Test
    void hashReturnsNullForNullInput() {
        assertNull(IdempotencyKeyValidator.hash(null));
    }
}
