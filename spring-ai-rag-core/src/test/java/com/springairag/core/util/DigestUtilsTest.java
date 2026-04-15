package com.springairag.core.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("DigestUtils Tests")
class DigestUtilsTest {

    @Test
    @DisplayName("sha256 returns correct 64-char lowercase hex for empty string")
    void sha256_emptyString_returnsCorrectHash() {
        // SHA-256 of "" = e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
        String hash = DigestUtils.sha256("");
        assertEquals(64, hash.length());
        assertTrue(hash.matches("[0-9a-f]{64}"));
    }

    @Test
    @DisplayName("sha256 returns correct hash for known input")
    void sha256_knownInput_returnsCorrectHash() {
        // SHA-256 of "hello" = 2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824
        String hash = DigestUtils.sha256("hello");
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", hash);
    }

    @Test
    @DisplayName("sha256 is deterministic")
    void sha256_deterministic() {
        String input = "The quick brown fox jumps over the lazy dog";
        String hash1 = DigestUtils.sha256(input);
        String hash2 = DigestUtils.sha256(input);
        assertEquals(hash1, hash2);
    }

    @Test
    @DisplayName("sha256 returns different hashes for different inputs")
    void sha256_differentInputs_differentHashes() {
        String hashA = DigestUtils.sha256("hello");
        String hashB = DigestUtils.sha256("world");
        assertNotEquals(hashA, hashB);
    }

    @Test
    @DisplayName("sha256 handles unicode content")
    void sha256_unicodeContent() {
        String hash = DigestUtils.sha256("中文内容测试");
        assertEquals(64, hash.length());
        assertTrue(hash.matches("[0-9a-f]{64}"));
    }

    @Test
    @DisplayName("sha256 handles long content")
    void sha256_longContent() {
        String longContent = "A".repeat(100_000);
        String hash = DigestUtils.sha256(longContent);
        assertEquals(64, hash.length());
        assertTrue(hash.matches("[0-9a-f]{64}"));
    }

    @Test
    @DisplayName("sha256 throws IllegalArgumentException for null input")
    void sha256_nullInput_throwsIllegalArgumentException() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> DigestUtils.sha256(null)
        );
        assertEquals("Content must not be null", ex.getMessage());
    }
}
