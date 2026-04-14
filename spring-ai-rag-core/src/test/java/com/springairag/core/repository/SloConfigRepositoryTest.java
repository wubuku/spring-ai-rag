package com.springairag.core.repository;

import com.springairag.core.entity.RagSloConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * SloConfigRepository Unit Tests (using Mock).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SloConfigRepository Tests")
class SloConfigRepositoryTest {

    @Mock
    private SloConfigRepository repository;

    private RagSloConfig createSloConfig(Long id, String sloName, String sloType, boolean enabled) {
        RagSloConfig c = new RagSloConfig();
        c.setId(id);
        c.setSloName(sloName);
        c.setSloType(sloType);
        c.setEnabled(enabled);
        c.setTargetValue(500.0);
        c.setUnit("ms");
        return c;
    }

    // findBySloName

    @Nested
    @DisplayName("findBySloName")
    class FindBySloName {

        @Test
        @DisplayName("returns config when found")
        void returnsConfigWhenFound() {
            RagSloConfig config = createSloConfig(1L, "p99_latency", "LATENCY", true);
            when(repository.findBySloName("p99_latency")).thenReturn(Optional.of(config));

            Optional<RagSloConfig> result = repository.findBySloName("p99_latency");

            assertTrue(result.isPresent());
            assertEquals("p99_latency", result.get().getSloName());
            assertEquals("LATENCY", result.get().getSloType());
        }

        @Test
        @DisplayName("returns empty when not found")
        void returnsEmptyWhenNotFound() {
            when(repository.findBySloName("nonexistent")).thenReturn(Optional.empty());

            Optional<RagSloConfig> result = repository.findBySloName("nonexistent");

            assertFalse(result.isPresent());
        }
    }

    // findByEnabledTrue

    @Nested
    @DisplayName("findByEnabledTrue")
    class FindByEnabledTrue {

        @Test
        @DisplayName("returns all enabled configs")
        void returnsAllEnabledConfigs() {
            RagSloConfig c1 = createSloConfig(1L, "latency_slo", "LATENCY", true);
            RagSloConfig c2 = createSloConfig(2L, "quality_slo", "QUALITY", true);
            when(repository.findByEnabledTrue()).thenReturn(List.of(c1, c2));

            List<RagSloConfig> configs = repository.findByEnabledTrue();

            assertEquals(2, configs.size());
            assertTrue(configs.stream().allMatch(c -> c.getEnabled()));
        }

        @Test
        @DisplayName("returns empty list when no enabled configs")
        void returnsEmptyListWhenNoEnabledConfigs() {
            when(repository.findByEnabledTrue()).thenReturn(List.of());

            List<RagSloConfig> configs = repository.findByEnabledTrue();

            assertTrue(configs.isEmpty());
        }
    }

    // findBySloType

    @Nested
    @DisplayName("findBySloType")
    class FindBySloType {

        @Test
        @DisplayName("returns configs by type")
        void returnsConfigsByType() {
            RagSloConfig c1 = createSloConfig(1L, "p50_latency", "LATENCY", true);
            RagSloConfig c2 = createSloConfig(2L, "p99_latency", "LATENCY", false);
            when(repository.findBySloType("LATENCY")).thenReturn(List.of(c1, c2));

            List<RagSloConfig> configs = repository.findBySloType("LATENCY");

            assertEquals(2, configs.size());
        }

        @Test
        @DisplayName("returns empty list for unknown type")
        void returnsEmptyListForUnknownType() {
            when(repository.findBySloType("UNKNOWN_TYPE")).thenReturn(List.of());

            List<RagSloConfig> configs = repository.findBySloType("UNKNOWN_TYPE");

            assertTrue(configs.isEmpty());
        }
    }

    // CRUD inherited methods

    @Nested
    @DisplayName("CRUD inherited methods")
    class CrudMethods {

        @Test
        @DisplayName("save stores config and returns it")
        void saveStoresConfigAndReturnsIt() {
            RagSloConfig c = createSloConfig(null, "new_slo", "LATENCY", true);
            RagSloConfig saved = createSloConfig(1L, "new_slo", "LATENCY", true);
            when(repository.save(c)).thenReturn(saved);

            RagSloConfig result = repository.save(c);

            assertNotNull(result.getId());
            assertEquals("new_slo", result.getSloName());
        }

        @Test
        @DisplayName("findById returns config when present")
        void findByIdReturnsConfigWhenPresent() {
            RagSloConfig c = createSloConfig(1L, "test_slo", "LATENCY", true);
            when(repository.findById(1L)).thenReturn(Optional.of(c));

            Optional<RagSloConfig> result = repository.findById(1L);

            assertTrue(result.isPresent());
            assertEquals("test_slo", result.get().getSloName());
        }

        @Test
        @DisplayName("findById returns empty when not present")
        void findByIdReturnsEmptyWhenNotPresent() {
            when(repository.findById(999L)).thenReturn(Optional.empty());

            Optional<RagSloConfig> result = repository.findById(999L);

            assertFalse(result.isPresent());
        }

        @Test
        @DisplayName("findAll returns all configs")
        void findAllReturnsAllConfigs() {
            RagSloConfig c1 = createSloConfig(1L, "slo_a", "LATENCY", true);
            RagSloConfig c2 = createSloConfig(2L, "slo_b", "QUALITY", false);
            when(repository.findAll()).thenReturn(List.of(c1, c2));

            List<RagSloConfig> configs = repository.findAll();

            assertEquals(2, configs.size());
        }

        @Test
        @DisplayName("deleteById removes config")
        void deleteByIdRemovesConfig() {
            doNothing().when(repository).deleteById(1L);

            repository.deleteById(1L);

            verify(repository).deleteById(1L);
        }

        @Test
        @DisplayName("delete removes config")
        void deleteRemovesConfig() {
            RagSloConfig c = createSloConfig(1L, "to_delete", "LATENCY", true);
            doNothing().when(repository).delete(c);

            repository.delete(c);

            verify(repository).delete(c);
        }

        @Test
        @DisplayName("count returns total config count")
        void countReturnsTotalConfigCount() {
            when(repository.count()).thenReturn(5L);

            long count = repository.count();

            assertEquals(5L, count);
        }

        @Test
        @DisplayName("existsById returns true when present")
        void existsByIdReturnsTrueWhenPresent() {
            when(repository.existsById(1L)).thenReturn(true);

            boolean exists = repository.existsById(1L);

            assertTrue(exists);
        }

        @Test
        @DisplayName("existsById returns false when not present")
        void existsByIdReturnsFalseWhenNotPresent() {
            when(repository.existsById(999L)).thenReturn(false);

            boolean exists = repository.existsById(999L);

            assertFalse(exists);
        }
    }
}
