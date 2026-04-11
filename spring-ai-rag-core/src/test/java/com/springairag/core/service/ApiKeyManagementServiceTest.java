package com.springairag.core.service;

import com.springairag.api.dto.ApiKeyCreateRequest;
import com.springairag.api.dto.ApiKeyCreatedResponse;
import com.springairag.api.dto.ApiKeyResponse;
import com.springairag.core.entity.RagApiKey;
import com.springairag.core.repository.RagApiKeyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

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
    void validateKeyEntity_validKey_returnsKeyAndUpdatesLastUsed() {
        String rawKey = "rag_sk_testkey123";
        String hash = sha256(rawKey);
        RagApiKey key = new RagApiKey();
        key.setKeyId("rag_k_abc");
        key.setKeyHash(hash);
        key.setEnabled(true);
        key.setExpiresAt(null);

        when(apiKeyRepository.findAll()).thenReturn(List.of(key));

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

        when(apiKeyRepository.findAll()).thenReturn(List.of(key));

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

        when(apiKeyRepository.findAll()).thenReturn(List.of(key));

        RagApiKey result = service.validateKeyEntity(rawKey);

        assertNull(result);
    }

    @Test
    void validateKeyEntity_invalidKey_returnsNull() {
        when(apiKeyRepository.findAll()).thenReturn(List.of());

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
        RagApiKey result = service.validateKeyEntity("  ");
        assertNull(result);
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
}
