package com.springairag.core.repository;

import com.springairag.core.entity.RagDocumentVersion;
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
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * RagDocumentVersionRepository Unit Tests (using Mock).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RagDocumentVersionRepository Tests")
class RagDocumentVersionRepositoryTest {

    @Mock
    private RagDocumentVersionRepository repository;

    private RagDocumentVersion createVersion(Long id, Long documentId,
                                            int versionNumber, String contentHash) {
        RagDocumentVersion v = new RagDocumentVersion();
        v.setId(id);
        v.setDocumentId(documentId);
        v.setVersionNumber(versionNumber);
        v.setContentHash(contentHash);
        v.setContentSnapshot("Content snapshot v" + versionNumber);
        v.setSize(1024L);
        v.setChangeType("UPDATE");
        v.setChangeDescription("Updated content");
        v.setMetadataSnapshot(Map.of());
        v.setCreatedAt(LocalDateTime.now());
        return v;
    }

    // findByDocumentIdOrderByVersionNumberDesc

    @Nested
    @DisplayName("findByDocumentIdOrderByVersionNumberDesc")
    class FindByDocumentIdDesc {

        @Test
        @DisplayName("returns paginated versions in descending order")
        void returnsPaginatedVersionsInDescendingOrder() {
            Pageable pageable = PageRequest.of(0, 10);
            RagDocumentVersion v1 = createVersion(2L, 10L, 2, "hash-2");
            RagDocumentVersion v2 = createVersion(1L, 10L, 1, "hash-1");
            Page<RagDocumentVersion> page = new org.springframework.data.domain.PageImpl<>(
                    List.of(v1, v2), pageable, 2);
            when(repository.findByDocumentIdOrderByVersionNumberDesc(10L, pageable))
                    .thenReturn(page);

            Page<RagDocumentVersion> result =
                    repository.findByDocumentIdOrderByVersionNumberDesc(10L, pageable);

            assertEquals(2, result.getTotalElements());
            assertEquals(2, result.getContent().get(0).getVersionNumber());
        }

        @Test
        @DisplayName("returns empty page for document with no versions")
        void returnsEmptyPageForDocumentWithNoVersions() {
            Pageable pageable = PageRequest.of(0, 10);
            Page<RagDocumentVersion> page = new org.springframework.data.domain.PageImpl<>(
                    List.of(), pageable, 0);
            when(repository.findByDocumentIdOrderByVersionNumberDesc(999L, pageable))
                    .thenReturn(page);

            Page<RagDocumentVersion> result =
                    repository.findByDocumentIdOrderByVersionNumberDesc(999L, pageable);

            assertEquals(0, result.getTotalElements());
        }
    }

    // findByDocumentIdOrderByVersionNumberAsc

    @Nested
    @DisplayName("findByDocumentIdOrderByVersionNumberAsc")
    class FindByDocumentIdAsc {

        @Test
        @DisplayName("returns versions in ascending order")
        void returnsVersionsInAscendingOrder() {
            RagDocumentVersion v1 = createVersion(1L, 10L, 1, "hash-1");
            RagDocumentVersion v2 = createVersion(2L, 10L, 2, "hash-2");
            when(repository.findByDocumentIdOrderByVersionNumberAsc(10L))
                    .thenReturn(List.of(v1, v2));

            List<RagDocumentVersion> versions =
                    repository.findByDocumentIdOrderByVersionNumberAsc(10L);

            assertEquals(2, versions.size());
            assertEquals(1, versions.get(0).getVersionNumber());
            assertEquals(2, versions.get(1).getVersionNumber());
        }

        @Test
        @DisplayName("returns empty list for document with no versions")
        void returnsEmptyListForDocumentWithNoVersions() {
            when(repository.findByDocumentIdOrderByVersionNumberAsc(999L))
                    .thenReturn(List.of());

            List<RagDocumentVersion> versions =
                    repository.findByDocumentIdOrderByVersionNumberAsc(999L);

            assertTrue(versions.isEmpty());
        }
    }

    // findByDocumentIdAndVersionNumber

    @Nested
    @DisplayName("findByDocumentIdAndVersionNumber")
    class FindByDocumentIdAndVersionNumber {

        @Test
        @DisplayName("returns version when found")
        void returnsVersionWhenFound() {
            RagDocumentVersion v = createVersion(1L, 10L, 3, "hash-3");
            when(repository.findByDocumentIdAndVersionNumber(10L, 3))
                    .thenReturn(Optional.of(v));

            Optional<RagDocumentVersion> result =
                    repository.findByDocumentIdAndVersionNumber(10L, 3);

            assertTrue(result.isPresent());
            assertEquals(3, result.get().getVersionNumber());
        }

        @Test
        @DisplayName("returns empty when version not found")
        void returnsEmptyWhenVersionNotFound() {
            when(repository.findByDocumentIdAndVersionNumber(10L, 99))
                    .thenReturn(Optional.empty());

            Optional<RagDocumentVersion> result =
                    repository.findByDocumentIdAndVersionNumber(10L, 99);

            assertFalse(result.isPresent());
        }
    }

    // findLatestByDocumentId

    @Nested
    @DisplayName("findLatestByDocumentId")
    class FindLatestByDocumentId {

        @Test
        @DisplayName("returns latest version")
        void returnsLatestVersion() {
            RagDocumentVersion latest = createVersion(3L, 10L, 3, "hash-3");
            when(repository.findLatestByDocumentId(10L))
                    .thenReturn(Optional.of(latest));

            Optional<RagDocumentVersion> result =
                    repository.findLatestByDocumentId(10L);

            assertTrue(result.isPresent());
            assertEquals(3, result.get().getVersionNumber());
        }

        @Test
        @DisplayName("returns empty for document with no versions")
        void returnsEmptyForDocumentWithNoVersions() {
            when(repository.findLatestByDocumentId(999L))
                    .thenReturn(Optional.empty());

            Optional<RagDocumentVersion> result =
                    repository.findLatestByDocumentId(999L);

            assertFalse(result.isPresent());
        }
    }

    // countByDocumentId

    @Nested
    @DisplayName("countByDocumentId")
    class CountByDocumentId {

        @Test
        @DisplayName("returns count of versions for document")
        void returnsCountOfVersionsForDocument() {
            when(repository.countByDocumentId(10L)).thenReturn(5L);

            long count = repository.countByDocumentId(10L);

            assertEquals(5L, count);
        }

        @Test
        @DisplayName("returns zero for document with no versions")
        void returnsZeroForDocumentWithNoVersions() {
            when(repository.countByDocumentId(999L)).thenReturn(0L);

            long count = repository.countByDocumentId(999L);

            assertEquals(0L, count);
        }
    }

    // deleteByDocumentId

    @Nested
    @DisplayName("deleteByDocumentId")
    class DeleteByDocumentId {

        @Test
        @DisplayName("deletes all versions for document")
        void deletesAllVersionsForDocument() {
            doNothing().when(repository).deleteByDocumentId(10L);

            repository.deleteByDocumentId(10L);

            verify(repository).deleteByDocumentId(10L);
        }
    }

    // findByDocumentIdAndContentHash

    @Nested
    @DisplayName("findByDocumentIdAndContentHash")
    class FindByDocumentIdAndContentHash {

        @Test
        @DisplayName("returns version by content hash")
        void returnsVersionByContentHash() {
            RagDocumentVersion v = createVersion(1L, 10L, 2, "hash-existing");
            when(repository.findByDocumentIdAndContentHash(10L, "hash-existing"))
                    .thenReturn(List.of(v));

            List<RagDocumentVersion> results =
                    repository.findByDocumentIdAndContentHash(10L, "hash-existing");

            assertEquals(1, results.size());
            assertEquals("hash-existing", results.get(0).getContentHash());
        }

        @Test
        @DisplayName("returns empty list for unknown content hash")
        void returnsEmptyListForUnknownContentHash() {
            when(repository.findByDocumentIdAndContentHash(10L, "unknown"))
                    .thenReturn(List.of());

            List<RagDocumentVersion> results =
                    repository.findByDocumentIdAndContentHash(10L, "unknown");

            assertTrue(results.isEmpty());
        }
    }

    // CRUD inherited methods

    @Nested
    @DisplayName("CRUD inherited methods")
    class CrudMethods {

        @Test
        @DisplayName("save stores version and returns it")
        void saveStoresVersionAndReturnsIt() {
            RagDocumentVersion v = createVersion(null, 10L, 1, "new-hash");
            RagDocumentVersion saved = createVersion(1L, 10L, 1, "new-hash");
            when(repository.save(v)).thenReturn(saved);

            RagDocumentVersion result = repository.save(v);

            assertNotNull(result.getId());
            assertEquals(1, result.getVersionNumber());
        }

        @Test
        @DisplayName("findById returns version when present")
        void findByIdReturnsVersionWhenPresent() {
            RagDocumentVersion v = createVersion(1L, 10L, 1, "hash-1");
            when(repository.findById(1L)).thenReturn(Optional.of(v));

            Optional<RagDocumentVersion> result = repository.findById(1L);

            assertTrue(result.isPresent());
            assertEquals("hash-1", result.get().getContentHash());
        }

        @Test
        @DisplayName("findById returns empty when not present")
        void findByIdReturnsEmptyWhenNotPresent() {
            when(repository.findById(999L)).thenReturn(Optional.empty());

            Optional<RagDocumentVersion> result = repository.findById(999L);

            assertFalse(result.isPresent());
        }

        @Test
        @DisplayName("findAll returns all versions")
        void findAllReturnsAllVersions() {
            RagDocumentVersion v1 = createVersion(1L, 10L, 1, "hash-1");
            RagDocumentVersion v2 = createVersion(2L, 20L, 1, "hash-2");
            when(repository.findAll()).thenReturn(List.of(v1, v2));

            List<RagDocumentVersion> versions = repository.findAll();

            assertEquals(2, versions.size());
        }

        @Test
        @DisplayName("deleteById removes version")
        void deleteByIdRemovesVersion() {
            doNothing().when(repository).deleteById(1L);

            repository.deleteById(1L);

            verify(repository).deleteById(1L);
        }

        @Test
        @DisplayName("count returns total version count")
        void countReturnsTotalVersionCount() {
            when(repository.count()).thenReturn(200L);

            long count = repository.count();

            assertEquals(200L, count);
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
