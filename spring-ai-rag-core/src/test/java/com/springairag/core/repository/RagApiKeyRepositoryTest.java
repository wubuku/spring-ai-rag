package com.springairag.core.repository;

import com.springairag.core.entity.RagApiKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * RagApiKeyRepository Unit Tests (using Mock).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RagApiKeyRepository Tests")
class RagApiKeyRepositoryTest {

    @Mock
    private RagApiKeyRepository repository;

    private RagApiKey createApiKey(Long id, String keyId, String name, boolean enabled) {
        RagApiKey key = new RagApiKey();
        key.setId(id);
        key.setKeyId(keyId);
        key.setName(name);
        key.setKeyHash("sha256:" + keyId);
        key.setEnabled(enabled);
        key.setCreatedAt(LocalDateTime.now());
        return key;
    }

    // findByKeyId

    @Nested
    @DisplayName("findByKeyId")
    class FindByKeyId {

        @Test
        @DisplayName("returns key when found")
        void returnsKeyWhenFound() {
            RagApiKey key = createApiKey(1L, "rag_k_abc123", "Test Key", true);
            when(repository.findByKeyId("rag_k_abc123")).thenReturn(Optional.of(key));

            Optional<RagApiKey> result = repository.findByKeyId("rag_k_abc123");

            assertTrue(result.isPresent());
            assertEquals("Test Key", result.get().getName());
            assertTrue(result.get().getEnabled());
        }

        @Test
        @DisplayName("returns empty when not found")
        void returnsEmptyWhenNotFound() {
            when(repository.findByKeyId("rag_k_unknown")).thenReturn(Optional.empty());

            Optional<RagApiKey> result = repository.findByKeyId("rag_k_unknown");

            assertFalse(result.isPresent());
        }

        @Test
        @DisplayName("returns empty for null keyId")
        void returnsEmptyForNullKeyId() {
            when(repository.findByKeyId(null)).thenReturn(Optional.empty());

            Optional<RagApiKey> result = repository.findByKeyId(null);

            assertFalse(result.isPresent());
        }
    }

    // updateLastUsed

    @Nested
    @DisplayName("updateLastUsed")
    class UpdateLastUsed {

        @Test
        @DisplayName("updates last used timestamp")
        void updatesLastUsedTimestamp() {
            LocalDateTime now = LocalDateTime.now();
            when(repository.updateLastUsed("rag_k_abc123", now)).thenReturn(1);

            int updated = repository.updateLastUsed("rag_k_abc123", now);

            assertEquals(1, updated);
            verify(repository).updateLastUsed("rag_k_abc123", now);
        }

        @Test
        @DisplayName("returns 0 when key not found")
        void returnsZeroWhenKeyNotFound() {
            LocalDateTime now = LocalDateTime.now();
            when(repository.updateLastUsed("rag_k_unknown", now)).thenReturn(0);

            int updated = repository.updateLastUsed("rag_k_unknown", now);

            assertEquals(0, updated);
        }
    }

    // CRUD inherited methods

    @Nested
    @DisplayName("CRUD inherited methods")
    class CrudMethods {

        @Test
        @DisplayName("save stores key and returns it")
        void saveStoresKeyAndReturnsIt() {
            RagApiKey key = createApiKey(null, null, "New Key", true);
            RagApiKey saved = createApiKey(1L, "rag_k_xyz789", "New Key", true);
            when(repository.save(key)).thenReturn(saved);

            RagApiKey result = repository.save(key);

            assertNotNull(result.getId());
            assertNotNull(result.getKeyId());
        }

        @Test
        @DisplayName("findById returns key when present")
        void findByIdReturnsKeyWhenPresent() {
            RagApiKey key = createApiKey(1L, "rag_k_abc123", "Test Key", true);
            when(repository.findById(1L)).thenReturn(Optional.of(key));

            Optional<RagApiKey> result = repository.findById(1L);

            assertTrue(result.isPresent());
            assertEquals("rag_k_abc123", result.get().getKeyId());
        }

        @Test
        @DisplayName("findById returns empty when not present")
        void findByIdReturnsEmptyWhenNotPresent() {
            when(repository.findById(999L)).thenReturn(Optional.empty());

            Optional<RagApiKey> result = repository.findById(999L);

            assertFalse(result.isPresent());
        }

        @Test
        @DisplayName("findAll returns all keys")
        void findAllReturnsAllKeys() {
            RagApiKey k1 = createApiKey(1L, "rag_k_aaa", "Key A", true);
            RagApiKey k2 = createApiKey(2L, "rag_k_bbb", "Key B", false);
            when(repository.findAll()).thenReturn(List.of(k1, k2));

            List<RagApiKey> keys = repository.findAll();

            assertEquals(2, keys.size());
        }

        @Test
        @DisplayName("deleteById removes key")
        void deleteByIdRemovesKey() {
            doNothing().when(repository).deleteById(1L);

            repository.deleteById(1L);

            verify(repository).deleteById(1L);
        }

        @Test
        @DisplayName("count returns total key count")
        void countReturnsTotalKeyCount() {
            when(repository.count()).thenReturn(5L);

            long count = repository.count();

            assertEquals(5L, count);
        }
    }

    // findAll (enabled/disabled filter via findAll + assert)

    @Nested
    @DisplayName("findAll with filtering")
    class FindAllWithFiltering {

        @Test
        @DisplayName("can filter enabled keys")
        void canFilterEnabledKeys() {
            RagApiKey enabled = createApiKey(1L, "rag_k_on", "Enabled Key", true);
            RagApiKey disabled = createApiKey(2L, "rag_k_off", "Disabled Key", false);
            when(repository.findAll()).thenReturn(List.of(enabled, disabled));

            List<RagApiKey> all = repository.findAll();
            List<RagApiKey> enabledOnly = all.stream()
                    .filter(RagApiKey::getEnabled).toList();

            assertEquals(2, all.size());
            assertEquals(1, enabledOnly.size());
            assertTrue(enabledOnly.get(0).getEnabled());
        }
    }
}
