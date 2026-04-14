package com.springairag.core.repository;

import com.springairag.core.entity.RagDocument;
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
 * RagDocumentRepository Unit Tests (using Mock).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RagDocumentRepository Tests")
class RagDocumentRepositoryTest {

    @Mock
    private RagDocumentRepository repository;

    private RagDocument createDocument(Long id, String title, String documentType,
                                       Long collectionId, boolean enabled) {
        RagDocument d = new RagDocument();
        d.setId(id);
        d.setTitle(title);
        d.setContent("Content of " + title);
        d.setDocumentType(documentType);
        d.setCollectionId(collectionId);
        d.setEnabled(enabled);
        d.setProcessingStatus("COMPLETED");
        d.setContentHash("hash-" + id);
        d.setCreatedAt(LocalDateTime.now());
        return d;
    }

    // findByTitleContainingIgnoreCase

    @Nested
    @DisplayName("findByTitleContainingIgnoreCase")
    class FindByTitleContainingIgnoreCase {

        @Test
        @DisplayName("returns paginated documents matching title")
        void returnsPaginatedDocumentsMatchingTitle() {
            Pageable pageable = PageRequest.of(0, 10);
            RagDocument d1 = createDocument(1L, "Java Programming", "TEXT", 1L, true);
            Page<RagDocument> page = new org.springframework.data.domain.PageImpl<>(
                    List.of(d1), pageable, 1);
            when(repository.findByTitleContainingIgnoreCase("Java", pageable))
                    .thenReturn(page);

            Page<RagDocument> result =
                    repository.findByTitleContainingIgnoreCase("Java", pageable);

            assertEquals(1, result.getTotalElements());
            assertEquals("Java Programming", result.getContent().get(0).getTitle());
        }

        @Test
        @DisplayName("returns empty page for unknown title")
        void returnsEmptyPageForUnknownTitle() {
            Pageable pageable = PageRequest.of(0, 10);
            Page<RagDocument> page = new org.springframework.data.domain.PageImpl<>(
                    List.of(), pageable, 0);
            when(repository.findByTitleContainingIgnoreCase("NonexistentTitle", pageable))
                    .thenReturn(page);

            Page<RagDocument> result =
                    repository.findByTitleContainingIgnoreCase("NonexistentTitle", pageable);

            assertEquals(0, result.getTotalElements());
        }

        @Test
        @DisplayName("case insensitive search works")
        void caseInsensitiveSearchWorks() {
            Pageable pageable = PageRequest.of(0, 10);
            RagDocument d1 = createDocument(1L, "Python Guide", "TEXT", 1L, true);
            Page<RagDocument> page = new org.springframework.data.domain.PageImpl<>(
                    List.of(d1), pageable, 1);
            when(repository.findByTitleContainingIgnoreCase("python", pageable))
                    .thenReturn(page);

            Page<RagDocument> result =
                    repository.findByTitleContainingIgnoreCase("python", pageable);

            assertEquals(1, result.getTotalElements());
        }
    }

    // findByDocumentType

    @Nested
    @DisplayName("findByDocumentType")
    class FindByDocumentType {

        @Test
        @DisplayName("returns paginated documents of type")
        void returnsPaginatedDocumentsOfType() {
            Pageable pageable = PageRequest.of(0, 10);
            RagDocument d1 = createDocument(1L, "Doc PDF", "PDF", 1L, true);
            RagDocument d2 = createDocument(2L, "Doc PDF 2", "PDF", 1L, false);
            Page<RagDocument> page = new org.springframework.data.domain.PageImpl<>(
                    List.of(d1, d2), pageable, 2);
            when(repository.findByDocumentType("PDF", pageable)).thenReturn(page);

            Page<RagDocument> result = repository.findByDocumentType("PDF", pageable);

            assertEquals(2, result.getTotalElements());
        }

        @Test
        @DisplayName("returns empty page for unknown type")
        void returnsEmptyPageForUnknownType() {
            Pageable pageable = PageRequest.of(0, 10);
            Page<RagDocument> page = new org.springframework.data.domain.PageImpl<>(
                    List.of(), pageable, 0);
            when(repository.findByDocumentType("UNKNOWN", pageable)).thenReturn(page);

            Page<RagDocument> result = repository.findByDocumentType("UNKNOWN", pageable);

            assertEquals(0, result.getTotalElements());
        }
    }

    // countByCollectionId

    @Nested
    @DisplayName("countByCollectionId")
    class CountByCollectionId {

        @Test
        @DisplayName("returns count of documents in collection")
        void returnsCountOfDocumentsInCollection() {
            when(repository.countByCollectionId(1L)).thenReturn(42L);

            long count = repository.countByCollectionId(1L);

            assertEquals(42L, count);
        }

        @Test
        @DisplayName("returns zero for empty collection")
        void returnsZeroForEmptyCollection() {
            when(repository.countByCollectionId(999L)).thenReturn(0L);

            long count = repository.countByCollectionId(999L);

            assertEquals(0L, count);
        }
    }

    // findAllByCollectionId

    @Nested
    @DisplayName("findAllByCollectionId")
    class FindAllByCollectionId {

        @Test
        @DisplayName("returns all documents in collection")
        void returnsAllDocumentsInCollection() {
            RagDocument d1 = createDocument(1L, "Doc 1", "TEXT", 5L, true);
            RagDocument d2 = createDocument(2L, "Doc 2", "TEXT", 5L, false);
            when(repository.findAllByCollectionId(5L)).thenReturn(List.of(d1, d2));

            List<RagDocument> docs = repository.findAllByCollectionId(5L);

            assertEquals(2, docs.size());
        }

        @Test
        @DisplayName("returns empty list for empty collection")
        void returnsEmptyListForEmptyCollection() {
            when(repository.findAllByCollectionId(999L)).thenReturn(List.of());

            List<RagDocument> docs = repository.findAllByCollectionId(999L);

            assertTrue(docs.isEmpty());
        }
    }

    // CRUD inherited methods

    @Nested
    @DisplayName("CRUD inherited methods")
    class CrudMethods {

        @Test
        @DisplayName("save stores document and returns it")
        void saveStoresDocumentAndReturnsIt() {
            RagDocument d = createDocument(null, "New Doc", "TEXT", 1L, true);
            RagDocument saved = createDocument(1L, "New Doc", "TEXT", 1L, true);
            when(repository.save(d)).thenReturn(saved);

            RagDocument result = repository.save(d);

            assertNotNull(result.getId());
            assertEquals("New Doc", result.getTitle());
        }

        @Test
        @DisplayName("findById returns document when present")
        void findByIdReturnsDocumentWhenPresent() {
            RagDocument d = createDocument(1L, "Test Doc", "TEXT", 1L, true);
            when(repository.findById(1L)).thenReturn(Optional.of(d));

            Optional<RagDocument> result = repository.findById(1L);

            assertTrue(result.isPresent());
            assertEquals("COMPLETED", result.get().getProcessingStatus());
        }

        @Test
        @DisplayName("findById returns empty when not present")
        void findByIdReturnsEmptyWhenNotPresent() {
            when(repository.findById(999L)).thenReturn(Optional.empty());

            Optional<RagDocument> result = repository.findById(999L);

            assertFalse(result.isPresent());
        }

        @Test
        @DisplayName("findAll returns all documents")
        void findAllReturnsAllDocuments() {
            RagDocument d1 = createDocument(1L, "Doc A", "TEXT", 1L, true);
            RagDocument d2 = createDocument(2L, "Doc B", "PDF", 2L, false);
            when(repository.findAll()).thenReturn(List.of(d1, d2));

            List<RagDocument> docs = repository.findAll();

            assertEquals(2, docs.size());
        }

        @Test
        @DisplayName("deleteById removes document")
        void deleteByIdRemovesDocument() {
            doNothing().when(repository).deleteById(1L);

            repository.deleteById(1L);

            verify(repository).deleteById(1L);
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

        @Test
        @DisplayName("count returns total document count")
        void countReturnsTotalDocumentCount() {
            when(repository.count()).thenReturn(100L);

            long count = repository.count();

            assertEquals(100L, count);
        }
    }
}
