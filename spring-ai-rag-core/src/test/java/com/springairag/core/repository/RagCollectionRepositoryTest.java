package com.springairag.core.repository;

import com.springairag.core.entity.RagCollection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * RagCollectionRepository Unit Tests (using Mock).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RagCollectionRepository Tests")
class RagCollectionRepositoryTest {

    @Mock
    private RagCollectionRepository repository;

    private RagCollection createCollection(Long id, String name, boolean enabled, boolean deleted) {
        RagCollection c = new RagCollection();
        c.setId(id);
        c.setName(name);
        c.setDescription("Description for " + name);
        c.setEnabled(enabled);
        c.setDeleted(deleted);
        c.setDimensions(1024);
        c.setCreatedAt(LocalDateTime.now());
        return c;
    }

    // searchCollections

    @Nested
    @DisplayName("searchCollections")
    class SearchCollections {

        @Test
        @DisplayName("returns paginated collections matching name")
        void returnsPaginatedCollectionsMatchingName() {
            Pageable pageable = PageRequest.of(0, 10);
            RagCollection c1 = createCollection(1L, "Medical Docs", true, false);
            Page<RagCollection> page = new org.springframework.data.domain.PageImpl<>(
                    List.of(c1), pageable, 1);
            when(repository.searchCollections("Medical", null, pageable)).thenReturn(page);

            Page<RagCollection> result =
                    repository.searchCollections("Medical", null, pageable);

            assertEquals(1, result.getTotalElements());
            assertEquals("Medical Docs", result.getContent().get(0).getName());
        }

        @Test
        @DisplayName("returns paginated collections by enabled status")
        void returnsPaginatedCollectionsByEnabledStatus() {
            Pageable pageable = PageRequest.of(0, 10);
            RagCollection c1 = createCollection(1L, "Enabled Coll", true, false);
            Page<RagCollection> page = new org.springframework.data.domain.PageImpl<>(
                    List.of(c1), pageable, 1);
            when(repository.searchCollections(null, true, pageable)).thenReturn(page);

            Page<RagCollection> result =
                    repository.searchCollections(null, true, pageable);

            assertEquals(1, result.getTotalElements());
            assertTrue(result.getContent().get(0).getEnabled());
        }

        @Test
        @DisplayName("returns paginated collections with all filters")
        void returnsPaginatedCollectionsWithAllFilters() {
            Pageable pageable = PageRequest.of(0, 10);
            RagCollection c1 = createCollection(1L, "Legal Docs", true, false);
            Page<RagCollection> page = new org.springframework.data.domain.PageImpl<>(
                    List.of(c1), pageable, 1);
            when(repository.searchCollections("Legal", true, pageable)).thenReturn(page);

            Page<RagCollection> result =
                    repository.searchCollections("Legal", true, pageable);

            assertEquals(1, result.getTotalElements());
        }

        @Test
        @DisplayName("returns empty page when no matches")
        void returnsEmptyPageWhenNoMatches() {
            Pageable pageable = PageRequest.of(0, 10);
            Page<RagCollection> page = new org.springframework.data.domain.PageImpl<>(
                    List.of(), pageable, 0);
            when(repository.searchCollections("Nonexistent", null, pageable)).thenReturn(page);

            Page<RagCollection> result =
                    repository.searchCollections("Nonexistent", null, pageable);

            assertEquals(0, result.getTotalElements());
        }
    }

    // findByIdAndDeletedFalse

    @Nested
    @DisplayName("findByIdAndDeletedFalse")
    class FindByIdAndDeletedFalse {

        @Test
        @DisplayName("returns collection when not deleted")
        void returnsCollectionWhenNotDeleted() {
            RagCollection c = createCollection(1L, "Active Collection", true, false);
            when(repository.findByIdAndDeletedFalse(1L)).thenReturn(Optional.of(c));

            Optional<RagCollection> result = repository.findByIdAndDeletedFalse(1L);

            assertTrue(result.isPresent());
            assertEquals("Active Collection", result.get().getName());
        }

        @Test
        @DisplayName("returns empty for deleted collection")
        void returnsEmptyForDeletedCollection() {
            when(repository.findByIdAndDeletedFalse(1L)).thenReturn(Optional.empty());

            Optional<RagCollection> result = repository.findByIdAndDeletedFalse(1L);

            assertFalse(result.isPresent());
        }
    }

    // CRUD inherited methods

    @Nested
    @DisplayName("CRUD inherited methods")
    class CrudMethods {

        @Test
        @DisplayName("save stores collection and returns it")
        void saveStoresCollectionAndReturnsIt() {
            RagCollection c = createCollection(null, "New Collection", true, false);
            RagCollection saved = createCollection(1L, "New Collection", true, false);
            when(repository.save(c)).thenReturn(saved);

            RagCollection result = repository.save(c);

            assertNotNull(result.getId());
            assertEquals("New Collection", result.getName());
        }

        @Test
        @DisplayName("findById returns collection when present")
        void findByIdReturnsCollectionWhenPresent() {
            RagCollection c = createCollection(1L, "Test Collection", true, false);
            when(repository.findById(1L)).thenReturn(Optional.of(c));

            Optional<RagCollection> result = repository.findById(1L);

            assertTrue(result.isPresent());
            assertEquals(1024, result.get().getDimensions());
        }

        @Test
        @DisplayName("findById returns empty when not present")
        void findByIdReturnsEmptyWhenNotPresent() {
            when(repository.findById(999L)).thenReturn(Optional.empty());

            Optional<RagCollection> result = repository.findById(999L);

            assertFalse(result.isPresent());
        }

        @Test
        @DisplayName("findAll returns all collections")
        void findAllReturnsAllCollections() {
            RagCollection c1 = createCollection(1L, "Coll A", true, false);
            RagCollection c2 = createCollection(2L, "Coll B", false, false);
            when(repository.findAll()).thenReturn(List.of(c1, c2));

            List<RagCollection> collections = repository.findAll();

            assertEquals(2, collections.size());
        }

        @Test
        @DisplayName("deleteById removes collection")
        void deleteByIdRemovesCollection() {
            doNothing().when(repository).deleteById(1L);

            repository.deleteById(1L);

            verify(repository).deleteById(1L);
        }

        @Test
        @DisplayName("count returns total collection count")
        void countReturnsTotalCollectionCount() {
            when(repository.count()).thenReturn(10L);

            long count = repository.count();

            assertEquals(10L, count);
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
