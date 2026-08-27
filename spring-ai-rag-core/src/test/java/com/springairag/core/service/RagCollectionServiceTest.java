package com.springairag.core.service;

import com.springairag.api.dto.CollectionCloneResponse;
import com.springairag.api.dto.CollectionRequest;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.entity.RagCollection;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.exception.DocumentRevisionConflictException;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.RagCollectionRepository;
import com.springairag.core.repository.RagDocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("RagCollectionService")
class RagCollectionServiceTest {

    @Mock
    private RagCollectionRepository collectionRepository;

    @Mock
    private RagDocumentRepository documentRepository;

    @Mock
    private AuditLogService auditLogService;

    private RagCollectionService service;

    @BeforeEach
    void setUp() {
        service = new RagCollectionService(collectionRepository, documentRepository, auditLogService);
        lenient().when(collectionRepository.advanceActiveVersion(
                anyLong(), anyLong())).thenReturn(1);
    }

    private RagCollection createCollection(Long id, String name) {
        RagCollection c = new RagCollection();
        c.setId(id);
        c.setVersion(0L);
        c.setCollectionKey(id == null ? "collection-new" : "collection-" + id);
        c.setName(name);
        c.setDescription("Description of " + name);
        c.setEmbeddingModel("bge-m3");
        c.setDimensions(1024);
        c.setEnabled(true);
        c.setDeleted(false);
        c.setCreatedAt(LocalDateTime.now());
        return c;
    }

    // ==================== createCollection ====================

    @Nested
    @DisplayName("createCollection")
    class CreateCollection {

        @Test
        @DisplayName("persists the caller supplied key without normalization")
        void createsWithExplicitKey() {
            CollectionRequest request = request("Customer:Manual/V3");
            when(collectionRepository.saveAndFlush(any(RagCollection.class)))
                    .thenAnswer(invocation -> {
                        RagCollection collection = invocation.getArgument(0);
                        collection.setId(1L);
                        return collection;
                    });

            RagCollection created = service.createCollection(request);

            assertEquals("Customer:Manual/V3", created.getCollectionKey());
            verify(collectionRepository).existsByCollectionKey("Customer:Manual/V3");
            verify(collectionRepository).saveAndFlush(argThat(collection ->
                    "Customer:Manual/V3".equals(collection.getCollectionKey())));
        }

        @Test
        @DisplayName("requires a valid explicit key")
        void rejectsMissingAndInvalidKeys() {
            assertThrows(IllegalArgumentException.class,
                    () -> service.createCollection(request(null)));
            assertThrows(IllegalArgumentException.class,
                    () -> service.createCollection(request("has space")));
            assertThrows(IllegalArgumentException.class,
                    () -> service.createCollection(request("x".repeat(129))));
            verify(collectionRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("precheck maps an existing key to duplicate resource")
        void duplicatePrecheckReturnsConflict() {
            when(collectionRepository.existsByCollectionKey("duplicate"))
                    .thenReturn(true);

            RagException error = assertThrows(RagException.class,
                    () -> service.createCollection(request("duplicate")));

            assertEquals(ErrorCode.DUPLICATE_RESOURCE, error.getErrorCodeEnum());
            verify(collectionRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("database uniqueness race maps only the named key constraint")
        void uniqueConstraintRaceReturnsConflict() {
            org.hibernate.exception.ConstraintViolationException violation =
                    mock(org.hibernate.exception.ConstraintViolationException.class);
            when(violation.getConstraintName())
                    .thenReturn("uk_rag_collection_collection_key");
            when(collectionRepository.saveAndFlush(any(RagCollection.class)))
                    .thenThrow(new DataIntegrityViolationException(
                            "duplicate", violation));

            RagException error = assertThrows(RagException.class,
                    () -> service.createCollection(request("race")));

            assertEquals(ErrorCode.DUPLICATE_RESOURCE, error.getErrorCodeEnum());
        }

        @Test
        @DisplayName("unrelated database errors are not mislabeled as duplicates")
        void unrelatedDatabaseErrorIsPreserved() {
            DataIntegrityViolationException databaseError =
                    new DataIntegrityViolationException("other constraint");
            when(collectionRepository.saveAndFlush(any(RagCollection.class)))
                    .thenThrow(databaseError);

            assertSame(databaseError, assertThrows(
                    DataIntegrityViolationException.class,
                    () -> service.createCollection(request("valid-key"))));
        }
    }

    // ==================== deleteCollection ====================

    @Nested
    @DisplayName("deleteCollection")
    class DeleteCollection {

        @Test
        @DisplayName("throws NullPointerException when id is null")
        void nullId_throwsNullPointerException() {
            NullPointerException ex = assertThrows(NullPointerException.class,
                    () -> service.deleteCollection(null));
            assertEquals("id must not be null", ex.getMessage());
        }

        @Test
        @DisplayName("rejects unlinking external-managed document identities")
        void externalManagedDocumentsRejectLegacySoftDelete() {
            RagCollection collection = createCollection(1L, "External");
            when(collectionRepository.findByIdAndDeletedFalse(1L))
                .thenReturn(Optional.of(collection));
            when(documentRepository.countExternalManagedByCollectionId(1L))
                    .thenReturn(1L);

            assertThrows(DocumentRevisionConflictException.class,
                    () -> service.deleteCollection(1L));

            verify(documentRepository, never()).countByCollectionId(1L);
            verify(documentRepository, never()).clearCollectionIdByCollectionId(1L);
            verify(collectionRepository, never()).softDeleteIfVersion(
                    anyLong(), anyLong(), any(LocalDateTime.class));
        }

        @Test
        @DisplayName("soft-deletes collection and unlinks documents")
        void existingCollection_unlinksDocumentsAndSoftDeletes() {
            RagCollection collection = createCollection(1L, "To Delete");
            when(collectionRepository.findByIdAndDeletedFalse(1L))
                .thenReturn(Optional.of(collection));
            when(collectionRepository.softDeleteIfVersion(
                    eq(1L), eq(1L), any(LocalDateTime.class))).thenReturn(1);
            when(documentRepository.countByCollectionId(1L)).thenReturn(5L);

            Optional<RagCollectionService.DeleteResult> result = service.deleteCollection(1L);

            assertTrue(result.isPresent());
            assertEquals(1L, result.get().id());
            assertEquals(5L, result.get().documentsUnlinked());

            verify(documentRepository).clearCollectionIdByCollectionId(1L);
            verify(collectionRepository).softDeleteIfVersion(
                    eq(1L), eq(1L), any(LocalDateTime.class));
            verify(auditLogService).logDelete(eq("Collection"), eq("1"), anyString());
        }

        @Test
        @DisplayName("soft-deletes with zero documents when collection is empty")
        void emptyCollection_deletesWithZeroDocuments() {
            RagCollection collection = createCollection(1L, "Empty");
            when(collectionRepository.findByIdAndDeletedFalse(1L))
                .thenReturn(Optional.of(collection));
            when(collectionRepository.softDeleteIfVersion(
                    eq(1L), eq(1L), any(LocalDateTime.class))).thenReturn(1);
            when(documentRepository.countByCollectionId(1L)).thenReturn(0L);

            Optional<RagCollectionService.DeleteResult> result = service.deleteCollection(1L);

            assertTrue(result.isPresent());
            assertEquals(0L, result.get().documentsUnlinked());
            verify(documentRepository, never()).clearCollectionIdByCollectionId(anyLong());
            verify(collectionRepository).softDeleteIfVersion(
                    eq(1L), eq(1L), any(LocalDateTime.class));
        }

        @Test
        @DisplayName("returns empty when collection not found")
        void nonExisting_returnsEmpty() {
            when(collectionRepository.findByIdAndDeletedFalse(999L))
                .thenReturn(Optional.empty());

            Optional<RagCollectionService.DeleteResult> result = service.deleteCollection(999L);

            assertTrue(result.isEmpty());
            verify(collectionRepository, never()).softDeleteIfVersion(
                    anyLong(), anyLong(), any(LocalDateTime.class));
        }

        @Test
        @DisplayName("does not fail when auditLogService is null")
        void noAuditLogService_doesNotFail() {
            RagCollectionService svcNoAudit = new RagCollectionService(collectionRepository, documentRepository, null);
            RagCollection collection = createCollection(1L, "No Audit");
            when(collectionRepository.findByIdAndDeletedFalse(1L))
                .thenReturn(Optional.of(collection));
            when(collectionRepository.softDeleteIfVersion(
                    eq(1L), eq(1L), any(LocalDateTime.class))).thenReturn(1);
            when(documentRepository.countByCollectionId(1L)).thenReturn(0L);

            Optional<RagCollectionService.DeleteResult> result = svcNoAudit.deleteCollection(1L);

            assertTrue(result.isPresent());
        }

        @Test
        @DisplayName("rejects permanently retired collections")
        void retiredCollectionIsRejected() {
            RagCollection retired = createCollection(1L, "Retired");
            retired.setDeleted(true);
            retired.setPurgedAt(LocalDateTime.now());
            when(collectionRepository.findByIdAndDeletedFalse(1L))
                    .thenReturn(Optional.empty());
            when(collectionRepository.findById(1L))
                    .thenReturn(Optional.of(retired));

            RagException error = assertThrows(
                    RagException.class,
                    () -> service.deleteCollection(1L));

            assertEquals(ErrorCode.COLLECTION_ALREADY_RETIRED,
                    error.getErrorCodeEnum());
        }
    }

    // ==================== restoreCollection ====================

    @Nested
    @DisplayName("restoreCollection")
    class RestoreCollection {

        @Test
        @DisplayName("throws NullPointerException when id is null")
        void nullId_throwsNullPointerException() {
            NullPointerException ex = assertThrows(NullPointerException.class,
                    () -> service.restoreCollection(null));
            assertEquals("id must not be null", ex.getMessage());
        }

        @Test
        @DisplayName("restores collection and returns with document count")
        void restoresCollectionSuccessfully() {
            RagCollection restored = createCollection(1L, "Restored");
            restored.setDeleted(false);
            when(collectionRepository.restore(1L)).thenReturn(1);
            when(collectionRepository.findById(1L)).thenReturn(Optional.of(restored));
            when(documentRepository.countByCollectionId(1L)).thenReturn(10L);

            Optional<RagCollectionService.RestoreResult> result = service.restoreCollection(1L);

            assertTrue(result.isPresent());
            assertEquals("Restored", result.get().collection().getName());
            assertEquals(10L, result.get().documentCount());
            verify(auditLogService).logUpdate(eq("Collection"), eq("1"), anyString());
        }

        @Test
        @DisplayName("returns empty when collection not found or not deleted")
        void notFound_returnsEmpty() {
            when(collectionRepository.restore(999L)).thenReturn(0);

            Optional<RagCollectionService.RestoreResult> result = service.restoreCollection(999L);

            assertTrue(result.isEmpty());
            verify(collectionRepository).findById(999L);
        }

        @Test
        @DisplayName("does not fail when auditLogService is null")
        void noAuditLogService_doesNotFail() {
            RagCollectionService svcNoAudit = new RagCollectionService(collectionRepository, documentRepository, null);
            RagCollection restored = createCollection(1L, "Restored");
            restored.setDeleted(false);
            when(collectionRepository.restore(1L)).thenReturn(1);
            when(collectionRepository.findById(1L)).thenReturn(Optional.of(restored));
            when(documentRepository.countByCollectionId(1L)).thenReturn(0L);

            Optional<RagCollectionService.RestoreResult> result = svcNoAudit.restoreCollection(1L);

            assertTrue(result.isPresent());
        }

        @Test
        @DisplayName("rejects permanently retired collections")
        void retiredCollectionIsRejected() {
            RagCollection retired = createCollection(1L, "Retired");
            retired.setDeleted(true);
            retired.setPurgedAt(LocalDateTime.now());
            when(collectionRepository.findById(1L))
                    .thenReturn(Optional.of(retired));

            RagException error = assertThrows(
                    RagException.class,
                    () -> service.restoreCollection(1L));

            assertEquals(ErrorCode.COLLECTION_ALREADY_RETIRED,
                    error.getErrorCodeEnum());
            verify(collectionRepository, never()).restore(1L);
        }
    }

    // ==================== cloneCollection ====================

    @Nested
    @DisplayName("cloneCollection")
    class CloneCollection {

        @Test
        @DisplayName("throws NullPointerException when id is null")
        void nullId_throwsNullPointerException() {
            NullPointerException ex = assertThrows(NullPointerException.class,
                    () -> service.cloneCollection(null, "clone-null-id"));
            assertEquals("id must not be null", ex.getMessage());
        }

        @Test
        @DisplayName("requires a new explicit key")
        void missingOrDuplicateTargetKeyIsRejected() {
            assertThrows(IllegalArgumentException.class,
                    () -> service.cloneCollection(1L));
            assertThrows(IllegalArgumentException.class,
                    () -> service.cloneCollection(1L, null));
            when(collectionRepository.existsByCollectionKey("existing-clone"))
                    .thenReturn(true);
            RagException duplicate = assertThrows(RagException.class,
                    () -> service.cloneCollection(1L, "existing-clone"));
            assertEquals(ErrorCode.DUPLICATE_RESOURCE,
                    duplicate.getErrorCodeEnum());
        }

        @Test
        @DisplayName("clones collection and documents with PENDING status")
        void clonesWithDocuments() {
            RagCollection source = createCollection(1L, "Source");
            source.setDescription("Source description");
            source.setEmbeddingModel("bge-m3");
            source.setDimensions(1024);

            RagDocument doc1 = new RagDocument();
            doc1.setId(10L);
            doc1.setTitle("Doc 1");
            doc1.setSource("http://example.com/1");
            doc1.setContent("Content 1");
            doc1.setDocumentType("PDF");
            doc1.setMetadata(java.util.Map.of("key", "value"));
            doc1.setSize(1024L);
            doc1.setCollectionId(1L);
            doc1.setEnabled(true);
            doc1.setProcessingStatus("COMPLETED");

            RagDocument doc2 = new RagDocument();
            doc2.setId(11L);
            doc2.setTitle("Doc 2");
            doc2.setContent("Content 2");
            doc2.setCollectionId(1L);
            doc2.setEnabled(true);
            doc2.setProcessingStatus("COMPLETED");

            when(collectionRepository.findByIdAndDeletedFalse(1L)).thenReturn(Optional.of(source));
            when(documentRepository.findAllByCollectionId(1L)).thenReturn(List.of(doc1, doc2));
            when(collectionRepository.saveAndFlush(any(RagCollection.class))).thenAnswer(inv -> {
                RagCollection c = inv.getArgument(0);
                if (c.getId() == null) c.setId(5L);
                return c;
            });
            when(documentRepository.saveAllAndFlush(anyList())).thenAnswer(inv -> inv.getArgument(0));

            Optional<CollectionCloneResponse> result =
                    service.cloneCollection(1L, "clone-with-documents");

            assertTrue(result.isPresent());
            assertEquals(5L, result.get().clonedCollectionId());
            assertEquals("clone-with-documents", result.get().clonedCollectionKey());
            assertEquals("Source (Copy)", result.get().clonedCollectionName());
            assertEquals(1L, result.get().sourceCollectionId());
            assertEquals(2, result.get().documentsCloned());

            // Verify documents are saved with PENDING status and new collection id
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<RagDocument>> docsCaptor = ArgumentCaptor.forClass(List.class);
            verify(documentRepository).saveAllAndFlush(docsCaptor.capture());
            List<RagDocument> savedDocs = docsCaptor.getValue();
            assertEquals(2, savedDocs.size());
            savedDocs.forEach(doc -> {
                assertEquals("PENDING", doc.getProcessingStatus());
                assertEquals(5L, doc.getCollectionId());
            });

            // Verify audit log
            verify(auditLogService).logCreate(eq("Collection"), eq("5"), anyString(), anyMap());
        }

        @Test
        @DisplayName("clones empty collection with zero documents")
        void clonesEmptyCollection() {
            RagCollection source = createCollection(1L, "Empty Source");
            when(collectionRepository.findByIdAndDeletedFalse(1L)).thenReturn(Optional.of(source));
            when(documentRepository.findAllByCollectionId(1L)).thenReturn(List.of());
            when(collectionRepository.saveAndFlush(any(RagCollection.class))).thenAnswer(inv -> {
                RagCollection c = inv.getArgument(0);
                if (c.getId() == null) c.setId(5L);
                return c;
            });

            Optional<CollectionCloneResponse> result =
                    service.cloneCollection(1L, "clone-empty");

            assertTrue(result.isPresent());
            assertEquals("clone-empty", result.get().clonedCollectionKey());
            assertEquals(0, result.get().documentsCloned());
            verify(documentRepository, never()).saveAll(anyList());
        }

        @Test
        @DisplayName("returns empty when source collection not found")
        void notFound_returnsEmpty() {
            when(collectionRepository.findByIdAndDeletedFalse(999L)).thenReturn(Optional.empty());

            Optional<CollectionCloneResponse> result =
                    service.cloneCollection(999L, "clone-missing");

            assertTrue(result.isEmpty());
            verify(collectionRepository, never()).save(any());
        }

        @Test
        @DisplayName("does not fail when auditLogService is null")
        void noAuditLogService_doesNotFail() {
            RagCollectionService svcNoAudit = new RagCollectionService(collectionRepository, documentRepository, null);
            RagCollection source = createCollection(1L, "Source");
            when(collectionRepository.findByIdAndDeletedFalse(1L)).thenReturn(Optional.of(source));
            when(documentRepository.findAllByCollectionId(1L)).thenReturn(List.of());
            when(collectionRepository.saveAndFlush(any(RagCollection.class))).thenAnswer(inv -> {
                RagCollection c = inv.getArgument(0);
                if (c.getId() == null) c.setId(5L);
                return c;
            });

            Optional<CollectionCloneResponse> result =
                    svcNoAudit.cloneCollection(1L, "clone-no-audit");

            assertTrue(result.isPresent());
        }

        @Test
        @DisplayName("rejects permanently retired source collections")
        void retiredCollectionIsRejected() {
            RagCollection retired = createCollection(1L, "Retired");
            retired.setDeleted(true);
            retired.setPurgedAt(LocalDateTime.now());
            when(collectionRepository.findByIdAndDeletedFalse(1L))
                    .thenReturn(Optional.empty());
            when(collectionRepository.findById(1L))
                    .thenReturn(Optional.of(retired));

            RagException error = assertThrows(
                    RagException.class,
                    () -> service.cloneCollection(1L, "clone-retired"));

            assertEquals(ErrorCode.COLLECTION_ALREADY_RETIRED,
                    error.getErrorCodeEnum());
            verify(collectionRepository, never())
                    .saveAndFlush(any(RagCollection.class));
        }
    }

    private CollectionRequest request(String collectionKey) {
        CollectionRequest request = new CollectionRequest();
        request.setCollectionKey(collectionKey);
        request.setName("Test Collection");
        return request;
    }
}
