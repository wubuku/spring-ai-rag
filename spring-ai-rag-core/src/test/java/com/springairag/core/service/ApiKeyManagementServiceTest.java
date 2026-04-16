package com.springairag.core.service;

import com.springairag.api.dto.ApiKeyCreateRequest;
import com.springairag.api.dto.ApiKeyCreatedResponse;
import com.springairag.api.dto.ApiKeyResponse;
import com.springairag.core.entity.RagApiKey;
import com.springairag.core.repository.RagApiKeyRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import com.github.benmanes.caffeine.cache.Cache;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ApiKeyManagementServiceTest {

    @Mock
    private RagApiKeyRepository apiKeyRepository;

    private ApiKeyManagementService service;

    @BeforeEach
    void setUp() {
        service = new ApiKeyManagementService(apiKeyRepository);
        // Clear static validation cache between tests to avoid cross-test pollution
        ApiKeyManagementServiceTest.clearValidationCache();
    }

    @AfterEach
    void tearDown() {
        ApiKeyManagementServiceTest.clearValidationCache();
    }

    // Visible for test cleanup — clears the static VALIDATED_KEY_CACHE
    static void clearValidationCache() {
        try {
            var field = ApiKeyManagementService.class.getDeclaredField("VALIDATED_KEY_CACHE");
            field.setAccessible(true);
            ((com.github.benmanes.caffeine.cache.Cache<?, ?>) field.get(null)).invalidateAll();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void generateKey_createsAndSavesKey() {
        ApiKeyCreateRequest request = new ApiKeyCreateRequest("Test Key", null);

        ApiKeyCreatedResponse response = service.generateKey(request);

        assertNotNull(response);
        assertNotNull(response.getKeyId());
        assertTrue(response.getKeyId().startsWith("rag_k_"));
        assertNotNull(response.getRawKey());
        assertTrue(response.getRawKey().startsWith("rag_sk_"));
        assertEquals("Test Key", response.getName());
        assertNull(response.getExpiresAt());
        assertNotNull(response.getWarning());

        ArgumentCaptor<RagApiKey> captor = ArgumentCaptor.forClass(RagApiKey.class);
        verify(apiKeyRepository).save(captor.capture());
        RagApiKey saved = captor.getValue();
        assertEquals("Test Key", saved.getName());
        assertTrue(saved.getEnabled());
        assertNotNull(saved.getKeyHash());
        assertNotNull(saved.getKeyId());
    }

    @Test
    void generateKey_withExpiration_setsExpiresAt() {
        LocalDateTime expires = LocalDateTime.of(2027, 1, 1, 0, 0);
        ApiKeyCreateRequest request = new ApiKeyCreateRequest("Expiring Key", expires);

        ApiKeyCreatedResponse response = service.generateKey(request);

        assertEquals(expires, response.getExpiresAt());
        ArgumentCaptor<RagApiKey> captor = ArgumentCaptor.forClass(RagApiKey.class);
        verify(apiKeyRepository).save(captor.capture());
        assertEquals(expires, captor.getValue().getExpiresAt());
    }

    @Test
    void revokeKey_existingKey_disablesAndReturnsTrue() {
        when(apiKeyRepository.disableByKeyId("rag_k_abc")).thenReturn(1);

        boolean result = service.revokeKey("rag_k_abc");

        assertTrue(result);
        verify(apiKeyRepository).disableByKeyId("rag_k_abc");
    }

    @Test
    void revokeKey_nonExistentKey_returnsFalse() {
        when(apiKeyRepository.disableByKeyId("rag_k_unknown")).thenReturn(0);

        boolean result = service.revokeKey("rag_k_unknown");

        assertFalse(result);
    }

    @Test
    void rotateKey_existingKey_disablesOldAndCreatesNew() {
        RagApiKey existing = new RagApiKey();
        existing.setKeyId("rag_k_old");
        existing.setName("My Key");
        existing.setKeyHash("oldhash");
        existing.setEnabled(true);
        when(apiKeyRepository.findByKeyId("rag_k_old")).thenReturn(Optional.of(existing));
        when(apiKeyRepository.disableByKeyId("rag_k_old")).thenReturn(1);

        ApiKeyCreatedResponse response = service.rotateKey("rag_k_old");

        assertNotNull(response);
        assertEquals("My Key", response.getName());
        verify(apiKeyRepository).disableByKeyId("rag_k_old");
        // disableByKeyId is @Modifying (no save), only generateKey calls save() once
        verify(apiKeyRepository, times(1)).save(any(RagApiKey.class));
    }

    @Test
    void rotateKey_nonExistentKey_returnsNull() {
        when(apiKeyRepository.findByKeyId("rag_k_unknown")).thenReturn(Optional.empty());

        ApiKeyCreatedResponse result = service.rotateKey("rag_k_unknown");

        assertNull(result);
    }

    @Test
    void listKeys_returnsAllKeyMetadata() {
        RagApiKey key1 = new RagApiKey();
        key1.setKeyId("rag_k_1");
        key1.setName("Key 1");
        key1.setEnabled(true);
        key1.setCreatedAt(LocalDateTime.of(2026, 1, 1, 0, 0));

        RagApiKey key2 = new RagApiKey();
        key2.setKeyId("rag_k_2");
        key2.setName("Key 2");
        key2.setEnabled(false);
        key2.setCreatedAt(LocalDateTime.of(2026, 2, 1, 0, 0));
        key2.setLastUsedAt(LocalDateTime.of(2026, 3, 1, 0, 0));

        when(apiKeyRepository.findAll()).thenReturn(List.of(key1, key2));

        List<ApiKeyResponse> result = service.listKeys();

        assertEquals(2, result.size());
        assertEquals("rag_k_1", result.get(0).getKeyId());
        assertEquals("Key 1", result.get(0).getName());
        assertEquals(true, result.get(0).getEnabled());
        assertEquals("rag_k_2", result.get(1).getKeyId());
        assertEquals(false, result.get(1).getEnabled());
    }

    @Test
    void listKeys_empty_returnsEmptyList() {
        when(apiKeyRepository.findAll()).thenReturn(List.of());

        List<ApiKeyResponse> result = service.listKeys();

        assertTrue(result.isEmpty());
    }

    @Test
    void validateKeyEntity_validKey_returnsKeyAndUpdatesLastUsed() {
        String rawKey = "rag_sk_testkey123";
        String hash = sha256(rawKey);
        RagApiKey key = new RagApiKey();
        key.setKeyId("rag_k_abc");
        key.setKeyHash(hash);
        key.setEnabled(true);
        key.setExpiresAt(null);

        when(apiKeyRepository.findByKeyHash(hash)).thenReturn(Optional.of(key));

        RagApiKey result = service.validateKeyEntity(rawKey);

        assertNotNull(result);
        assertEquals("rag_k_abc", result.getKeyId());
        verify(apiKeyRepository).updateLastUsed(eq("rag_k_abc"), any(LocalDateTime.class));
    }

    @Test
    void validateKeyEntity_expiredKey_returnsNull() {
        String rawKey = "rag_sk_expired";
        String hash = sha256(rawKey);
        RagApiKey key = new RagApiKey();
        key.setKeyId("rag_k_expired");
        key.setKeyHash(hash);
        key.setEnabled(true);
        key.setExpiresAt(LocalDateTime.of(2020, 1, 1, 0, 0)); // expired

        when(apiKeyRepository.findByKeyHash(hash)).thenReturn(Optional.of(key));

        RagApiKey result = service.validateKeyEntity(rawKey);

        assertNull(result);
    }

    @Test
    void validateKeyEntity_disabledKey_returnsNull() {
        String rawKey = "rag_sk_disabled";
        String hash = sha256(rawKey);
        RagApiKey key = new RagApiKey();
        key.setKeyId("rag_k_disabled");
        key.setKeyHash(hash);
        key.setEnabled(false);

        when(apiKeyRepository.findByKeyHash(hash)).thenReturn(Optional.of(key));

        RagApiKey result = service.validateKeyEntity(rawKey);

        assertNull(result);
    }

    @Test
    void validateKeyEntity_invalidKey_returnsNull() {
        String hash = sha256("rag_sk_notexist");
        when(apiKeyRepository.findByKeyHash(hash)).thenReturn(Optional.empty());

        RagApiKey result = service.validateKeyEntity("rag_sk_notexist");

        assertNull(result);
    }

    @Test
    void validateKeyEntity_nullRawKey_returnsNull() {
        RagApiKey result = service.validateKeyEntity(null);
        assertNull(result);
    }

    @Test
    void validateKeyEntity_blankRawKey_returnsNull() {
        RagApiKey result = service.validateKeyEntity("   ");
        assertNull(result);
    }

    // ==================== validateKey tests ====================

    @Test
    void validateKey_validKey_returnsKeyId() {
        String rawKey = "rag_sk_validkey";
        String hash = sha256(rawKey);
        RagApiKey key = new RagApiKey();
        key.setKeyId("rag_k_valid");
        key.setKeyHash(hash);
        key.setEnabled(true);
        key.setExpiresAt(null);

        when(apiKeyRepository.findByKeyHash(hash)).thenReturn(Optional.of(key));

        String result = service.validateKey(rawKey);

        assertNotNull(result);
        assertEquals("rag_k_valid", result);
        verify(apiKeyRepository).updateLastUsed(eq("rag_k_valid"), any(LocalDateTime.class));
    }

    @Test
    void validateKey_nonPrefixKey_returnsNull() {
        // Keys without rag_sk_ prefix are legacy/plain-text keys handled by the filter
        assertNull(service.validateKey("some-plain-key"));
        assertNull(service.validateKey("sk_another"));
        verifyNoInteractions(apiKeyRepository);
    }

    @Test
    void validateKey_disabledKey_returnsNull() {
        String rawKey = "rag_sk_disabled2";
        String hash = sha256(rawKey);
        RagApiKey key = new RagApiKey();
        key.setKeyId("rag_k_disabled2");
        key.setKeyHash(hash);
        key.setEnabled(false);

        when(apiKeyRepository.findByKeyHash(hash)).thenReturn(Optional.of(key));

        String result = service.validateKey(rawKey);

        assertNull(result);
        // lastUsed should NOT be updated for disabled keys
        verify(apiKeyRepository, never()).updateLastUsed(anyString(), any(LocalDateTime.class));
    }

    @Test
    void validateKey_expiredKey_returnsNull() {
        String rawKey = "rag_sk_expired2";
        String hash = sha256(rawKey);
        RagApiKey key = new RagApiKey();
        key.setKeyId("rag_k_expired2");
        key.setKeyHash(hash);
        key.setEnabled(true);
        key.setExpiresAt(LocalDateTime.of(2020, 1, 1, 0, 0));

        when(apiKeyRepository.findByKeyHash(hash)).thenReturn(Optional.of(key));

        String result = service.validateKey(rawKey);

        assertNull(result);
        verify(apiKeyRepository, never()).updateLastUsed(anyString(), any(LocalDateTime.class));
    }

    @Test
    void validateKey_notFound_returnsNull() {
        String hash = sha256("rag_sk_unknown");
        when(apiKeyRepository.findByKeyHash(hash)).thenReturn(Optional.empty());

        String result = service.validateKey("rag_sk_unknown");

        assertNull(result);
    }

    @Test
    void validateKey_nullKey_returnsNull() {
        assertNull(service.validateKey(null));
    }

    @Test
    void validateKey_blankKey_returnsNull() {
        assertNull(service.validateKey("   "));
    }

    @Test
    void validateKeyEntity_cacheHit_skipsDbLookup() {
        // First call: populates cache
        String rawKey = "rag_sk_cachekey";
        String hash = sha256(rawKey);
        RagApiKey key = new RagApiKey();
        key.setKeyId("rag_k_cache");
        key.setKeyHash(hash);
        key.setEnabled(true);
        key.setExpiresAt(null);

        when(apiKeyRepository.findByKeyHash(hash)).thenReturn(Optional.of(key));

        RagApiKey first = service.validateKeyEntity(rawKey);
        assertNotNull(first);
        assertEquals("rag_k_cache", first.getKeyId());

        // Second call: should hit cache, not call DB
        RagApiKey second = service.validateKeyEntity(rawKey);
        assertNotNull(second);
        assertEquals("rag_k_cache", second.getKeyId());

        // DB should only be called once (cache hit on second call)
        verify(apiKeyRepository, times(1)).findByKeyHash(hash);
        // But lastUsed is updated on each call
        verify(apiKeyRepository, times(2)).updateLastUsed(eq("rag_k_cache"), any(LocalDateTime.class));
    }

    @Test
    void validateKeyEntity_cacheMiss_queriesDb() {
        String rawKey = "rag_sk_miss";
        String hash = sha256(rawKey);
        RagApiKey key = new RagApiKey();
        key.setKeyId("rag_k_miss");
        key.setKeyHash(hash);
        key.setEnabled(true);

        when(apiKeyRepository.findByKeyHash(hash)).thenReturn(Optional.of(key));

        RagApiKey result = service.validateKeyEntity(rawKey);

        assertNotNull(result);
        assertEquals("rag_k_miss", result.getKeyId());
        verify(apiKeyRepository, times(1)).findByKeyHash(hash);
    }

    @Test
    void revokeKey_invalidatesValidationCache() {
        // Pre-populate cache with a key
        String rawKey = "rag_sk_willrevoke";
        String hash = sha256(rawKey);
        RagApiKey key = new RagApiKey();
        key.setKeyId("rag_k_revoke");
        key.setKeyHash(hash);
        key.setEnabled(true);
        when(apiKeyRepository.findByKeyHash(hash)).thenReturn(Optional.of(key));

        RagApiKey first = service.validateKeyEntity(rawKey);
        assertNotNull(first);
        assertEquals("rag_k_revoke", first.getKeyId());
        verify(apiKeyRepository, times(1)).findByKeyHash(hash);

        // Revoke: should invalidate cache, and key is now disabled in DB
        when(apiKeyRepository.disableByKeyId("rag_k_revoke")).thenReturn(1);
        when(apiKeyRepository.findByKeyHash(hash)).thenReturn(Optional.empty()); // key no longer valid
        boolean result = service.revokeKey("rag_k_revoke");
        assertTrue(result);

        // Next validateKeyEntity call should query DB (cache was cleared) and return null
        RagApiKey afterRevoke = service.validateKeyEntity(rawKey);
        assertNull(afterRevoke);
        verify(apiKeyRepository, times(2)).findByKeyHash(hash);
    }

    @Test
    void validateKeyEntity_nonRagSkPrefix_returnsNullWithoutCacheLookup() {
        // Non rag_sk_ prefix keys skip validation entirely (legacy path)
        service.validateKeyEntity("some-legacy-key");
        verifyNoInteractions(apiKeyRepository);
    }

    private String sha256(String input) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    // ==================== T13: Encryption / security boundary tests ====================

    @Test
    void sha256_producesConsistentHash() {
        String hash1 = sha256("test_api_key_123");
        String hash2 = sha256("test_api_key_123");
        assertEquals(hash1, hash2);
        assertEquals(64, hash1.length());
        assertTrue(hash1.matches("[0-9a-f]{64}"));
    }

    @Test
    void sha256_differentInputs_differentHashes() {
        assertNotEquals(sha256("key_a"), sha256("key_b"));
    }

    @Test
    void generateRawKey_hasCorrectPrefix() {
        assertTrue(service.generateRawKey().startsWith("rag_sk_"));
    }

    @Test
    void generateRawKey_uuidSuffixIs32HexChars() {
        String rawKey = service.generateRawKey();
        String uuidPart = rawKey.substring("rag_sk_".length());
        assertEquals(32, uuidPart.length());
        assertTrue(uuidPart.matches("[0-9a-f]{32}"));
    }

    @Test
    void generateKeyId_startsWithRagKPrefix() {
        assertTrue(service.generateKeyId().startsWith("rag_k_"));
    }

    @Test
    void generateKeyId_is12HexCharsAfterPrefix() {
        String keyId = service.generateKeyId();
        assertEquals(12, keyId.substring("rag_k_".length()).length());
    }

    @Test
    void isExpired_nullExpiresAt_isNotExpired() throws Exception {
        RagApiKey key = new RagApiKey();
        key.setExpiresAt(null);
        var method = ApiKeyManagementService.class.getDeclaredMethod("isExpired", RagApiKey.class);
        method.setAccessible(true);
        assertFalse((Boolean) method.invoke(service, key));
    }

    @Test
    void isExpired_futureDate_isNotExpired() throws Exception {
        RagApiKey key = new RagApiKey();
        key.setExpiresAt(LocalDateTime.now().plusDays(30));
        var method = ApiKeyManagementService.class.getDeclaredMethod("isExpired", RagApiKey.class);
        method.setAccessible(true);
        assertFalse((Boolean) method.invoke(service, key));
    }

    @Test
    void isExpired_pastDate_isExpired() throws Exception {
        RagApiKey key = new RagApiKey();
        key.setExpiresAt(LocalDateTime.now().minusDays(1));
        var method = ApiKeyManagementService.class.getDeclaredMethod("isExpired", RagApiKey.class);
        method.setAccessible(true);
        assertTrue((Boolean) method.invoke(service, key));
    }

    @Test
    void validateKeyEntity_expiredKey_notCached() {
        String rawKey = "rag_sk_expired_test_key_0000000000000";
        String hash = sha256(rawKey);
        RagApiKey expiredKey = new RagApiKey();
        expiredKey.setKeyId("rag_k_expired00001");
        expiredKey.setKeyHash(hash);
        expiredKey.setName("expired-test");
        expiredKey.setEnabled(true);
        expiredKey.setExpiresAt(LocalDateTime.now().minusDays(1));
        when(apiKeyRepository.findByKeyHash(hash)).thenReturn(Optional.of(expiredKey));

        assertNull(service.validateKeyEntity(rawKey));
        assertNull(getValidationCache().getIfPresent(hash));
    }

    @Test
    void validateKeyEntity_disabledKey_notCached() {
        String rawKey = "rag_sk_disabled_test_key_00000000000";
        String hash = sha256(rawKey);
        RagApiKey disabledKey = new RagApiKey();
        disabledKey.setKeyId("rag_k_disabled001");
        disabledKey.setKeyHash(hash);
        disabledKey.setName("disabled-test");
        disabledKey.setEnabled(false);
        disabledKey.setExpiresAt(null);
        when(apiKeyRepository.findByKeyHash(hash)).thenReturn(Optional.of(disabledKey));

        assertNull(service.validateKeyEntity(rawKey));
        assertNull(getValidationCache().getIfPresent(hash));
    }

    // Helper to access static VALIDATED_KEY_CACHE via reflection
    @SuppressWarnings("unchecked")
    private Cache<String, RagApiKey> getValidationCache() {
        try {
            var field = ApiKeyManagementService.class.getDeclaredField("VALIDATED_KEY_CACHE");
            field.setAccessible(true);
            return (Cache<String, RagApiKey>) field.get(null);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
