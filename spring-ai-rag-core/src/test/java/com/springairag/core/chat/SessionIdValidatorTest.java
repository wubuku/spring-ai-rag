package com.springairag.core.chat;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 会话 ID 契约：缺省生成、非法字符拒绝、边界长度。 */
class SessionIdValidatorTest {

    @Test
    void resolvesBlankValuesToARandomUuid() {
        String fromNull = SessionIdValidator.resolve(null);
        String fromBlank = SessionIdValidator.resolve("   ");

        assertDoesNotThrowUuid(fromNull);
        assertDoesNotThrowUuid(fromBlank);
        assertNotEquals(fromNull, SessionIdValidator.resolve(null));
    }

    private static void assertDoesNotThrowUuid(String value) {
        assertEquals(value, UUID.fromString(value).toString());
    }

    @Test
    void passesThroughValidIdentifiers() {
        assertEquals("session-1", SessionIdValidator.resolve("session-1"));
        assertEquals("a.b_c~d", SessionIdValidator.resolve("a.b_c~d"));
        assertEquals("x".repeat(36), SessionIdValidator.resolve("x".repeat(36)));
    }

    @Test
    void rejectsIllegalCharactersAndOverlongIdentifiers() {
        assertThrows(IllegalArgumentException.class,
                () -> SessionIdValidator.resolve("bad/session"));
        assertThrows(IllegalArgumentException.class,
                () -> SessionIdValidator.resolve("空格 id"));
        assertThrows(IllegalArgumentException.class,
                () -> SessionIdValidator.resolve("x".repeat(37)));
    }

    @Test
    void validatesWithoutGenerating() {
        assertTrue(SessionIdValidator.isValid("session-1"));
        assertTrue(SessionIdValidator.isValid("x".repeat(36)));
        assertFalse(SessionIdValidator.isValid(null));
        assertFalse(SessionIdValidator.isValid(""));
        assertFalse(SessionIdValidator.isValid("bad/slash"));
        assertFalse(SessionIdValidator.isValid("x".repeat(37)));
    }
}
