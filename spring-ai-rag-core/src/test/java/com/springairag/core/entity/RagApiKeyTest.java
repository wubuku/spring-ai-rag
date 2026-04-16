package com.springairag.core.entity;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for RagApiKey entity.
 * Covers: constructor, getters/setters, role defaults, isEnabled() null-safety.
 */
class RagApiKeyTest {

    @Test
    void defaultConstructor() {
        RagApiKey key = new RagApiKey();
        assertNull(key.getId());
        assertNull(key.getKeyId());
        assertNull(key.getKeyHash());
        assertNull(key.getName());
        assertNull(key.getCreatedAt());
        assertNull(key.getLastUsedAt());
        assertNull(key.getExpiresAt());
        // enabled defaults to Boolean.TRUE (field initializer)
        assertEquals(Boolean.TRUE, key.getEnabled());
        // role defaults to NORMAL (field initializer)
        assertEquals(ApiKeyRole.NORMAL, key.getRole());
        assertNull(key.getApiKey());
    }

    @Test
    void allGettersAndSetters() {
        RagApiKey key = new RagApiKey();

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expires = now.plusDays(30);

        key.setId(1L);
        key.setKeyId("rag_k_test123");
        key.setKeyHash("abc123def456");
        key.setName("Production API Key");
        key.setCreatedAt(now);
        key.setLastUsedAt(now);
        key.setExpiresAt(expires);
        key.setEnabled(true);
        key.setRole(ApiKeyRole.ADMIN);
        key.setApiKey("sk-test-secret-value");

        assertEquals(1L, key.getId());
        assertEquals("rag_k_test123", key.getKeyId());
        assertEquals("abc123def456", key.getKeyHash());
        assertEquals("Production API Key", key.getName());
        assertEquals(now, key.getCreatedAt());
        assertEquals(now, key.getLastUsedAt());
        assertEquals(expires, key.getExpiresAt());
        assertEquals(true, key.getEnabled());
        assertEquals(ApiKeyRole.ADMIN, key.getRole());
        assertEquals("sk-test-secret-value", key.getApiKey());
    }

    @Test
    void roleDefaultsToNormal() {
        RagApiKey key = new RagApiKey();
        // Role defaults to NORMAL in field declaration
        assertEquals(ApiKeyRole.NORMAL, key.getRole());
    }

    @Test
    void enabledDefaultsToTrue() {
        RagApiKey key = new RagApiKey();
        // enabled defaults to Boolean.TRUE in field declaration
        assertEquals(Boolean.TRUE, key.getEnabled());
    }

    @Test
    void isEnabled_trueObject_returnsTrue() {
        RagApiKey key = new RagApiKey();
        key.setEnabled(true);
        assertTrue(key.isEnabled());
    }

    @Test
    void isEnabled_falseObject_returnsFalse() {
        RagApiKey key = new RagApiKey();
        key.setEnabled(false);
        assertFalse(key.isEnabled());
    }

    @Test
    void isEnabled_null_returnsFalse() {
        RagApiKey key = new RagApiKey();
        key.setEnabled(null);
        // isEnabled() must return false when enabled is null (null-safe)
        assertFalse(key.isEnabled());
    }

    @Test
    void apiKeyRole_adminAndNormal() {
        RagApiKey adminKey = new RagApiKey();
        adminKey.setRole(ApiKeyRole.ADMIN);
        assertEquals(ApiKeyRole.ADMIN, adminKey.getRole());

        RagApiKey normalKey = new RagApiKey();
        normalKey.setRole(ApiKeyRole.NORMAL);
        assertEquals(ApiKeyRole.NORMAL, normalKey.getRole());
    }

    @Test
    void lastUsedAt_canBeSetAndCleared() {
        RagApiKey key = new RagApiKey();
        LocalDateTime now = LocalDateTime.now();

        key.setLastUsedAt(now);
        assertEquals(now, key.getLastUsedAt());

        // Allow clearing by setting null
        key.setLastUsedAt(null);
        assertNull(key.getLastUsedAt());
    }

    @Test
    void expiresAt_canBeNull() {
        RagApiKey key = new RagApiKey();
        // Null expires means never expires
        assertNull(key.getExpiresAt());
        key.setExpiresAt(null);
        assertNull(key.getExpiresAt());
    }

    @Test
    void expiresAt_canBeSet() {
        RagApiKey key = new RagApiKey();
        LocalDateTime future = LocalDateTime.now().plusDays(90);
        key.setExpiresAt(future);
        assertEquals(future, key.getExpiresAt());
    }
}
