package com.springairag.core.service;

import com.springairag.api.dto.CollectionCloneResponse;
import com.springairag.core.entity.RagCollection;
import com.springairag.core.entity.RagDocument;
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
    }

    private RagCollection createCollection(Long id, String name) {
        RagCollection c = new RagCollection();
        c.setId(id);
        c.setName(name);
        c.setDescription("Description of " + name);
        c.setEmbeddingModel("bge-m3");
        c.setDimensions(1024);
        c.setEnabled(true);
        c.setDeleted(false);
        c.setCreatedAt(LocalDateTime.now());
        return c;
    }

    // ==================== deleteCollection ====================

    @Nested
    @DisplayName("deleteCollection")
    class DeleteCollection {

        @Test
        @DisplayName("soft-deletes collection and unlinks documents")
        void existingCollection_unlinksDocumentsAndSoftDeletes() {
            RagCollection collection = createCollection(1L, "To Delete");
            when(collectionRepository.findByIdAndDeletedFalse(1L)).thenReturn(Optional.of(collection));
            when(documentRepository.countByCollectionId(1L)).thenReturn(5L);

            Optional<RagCollectionService.DeleteResult> result = service.deleteCollection(1L);

            assertTrue(result.isPresent());
            assertEquals(1L, result.get().id());
            assertEquals(5L, result.get().documentsUnlinked());

            verify(documentRepository).clearCollectionIdByCollectionId(1L);
            verify(collectionRepository).softDelete(eq(1L), any(LocalDateTime.class));
            verify(auditLogService).logDelete(eq("Collection"), eq("1"), anyString());
        }

        @Test
        @DisplayName("soft-deletes with zero documents when collection is empty")
        void emptyCollection_deletesWithZeroDocuments() {
            RagCollection collection = createCollection(1L, "Empty");
            when(collectionRepository.findByIdAndDeletedFalse(1L)).thenReturn(Optional.of(collection));
            when(documentRepository.countByCollectionId(1L)).thenReturn(0L);

            Optional<RagCollectionService.DeleteResult> result = service.deleteCollection(1L);

            assertTrue(result.isPresent());
            assertEquals(0L, result.get().documentsUnlinked());
            verify(documentRepository, never()).clearCollectionIdByCollectionId(anyLong());
            verify(collectionRepository).softDelete(eq(1L), any(LocalDateTime.class));
        }

        @Test
        @DisplayName("returns empty when collection not found")
        void nonExisting_returnsEmpty() {
            when(collectionRepository.findByIdAndDeletedFalse(999L)).thenReturn(Optional.empty());

            Optional<RagCollectionService.DeleteResult> result = service.deleteCollection(999L);

            assertTrue(result.isEmpty());
            verify(collectionRepository, never()).softDelete(anyLong(), any());
        }

        @Test
        @DisplayName("does not fail when auditLogService is null")
        void noAuditLogService_doesNotFail() {
            RagCollectionService svcNoAudit = new RagCollectionService(collectionRepository, documentRepository, null);
            RagCollection collection = createCollection(1L, "No Audit");
            when(collectionRepository.findByIdAndDeletedFalse(1L)).thenReturn(Optional.of(collection));
            when(documentRepository.countByCollectionId(1L)).thenReturn(0L);

            Optional<RagCollectionService.DeleteResult> result = svcNoAudit.deleteCollection(1L);

            assertTrue(result.isPresent());
        }
    }

    // ==================== restoreCollection ====================

    @Nested
    @DisplayName("restoreCollection")
    class RestoreCollection {

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
            verify(collectionRepository, never()).findById(anyLong());
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
    }

    // ==================== cloneCollection ====================

    @Nested
    @DisplayName("cloneCollection")
    class CloneCollection {

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
            when(collectionRepository.save(any(RagCollection.class))).thenAnswer(inv -> {
                RagCollection c = inv.getArgument(0);
                if (c.getId() == null) c.setId(5L);
                return c;
            });
            when(documentRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

            Optional<CollectionCloneResponse> result = service.cloneCollection(1L);

            assertTrue(result.isPresent());
            assertEquals(5L, result.get().clonedCollectionId());
            assertEquals("Source (Copy)", result.get().clonedCollectionName());
            assertEquals(1L, result.get().sourceCollectionId());
            assertEquals(2, result.get().documentsCloned());

            // Verify documents are saved with PENDING status and new collection id
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<RagDocument>> docsCaptor = ArgumentCaptor.forClass(List.class);
            verify(documentRepository).saveAll(docsCaptor.capture());
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
            when(collectionRepository.save(any(RagCollection.class))).thenAnswer(inv -> {
                RagCollection c = inv.getArgument(0);
                if (c.getId() == null) c.setId(5L);
                return c;
            });

            Optional<CollectionCloneResponse> result = service.cloneCollection(1L);

            assertTrue(result.isPresent());
            assertEquals(0, result.get().documentsCloned());
            verify(documentRepository, never()).saveAll(anyList());
        }

        @Test
        @DisplayName("returns empty when source collection not found")
        void notFound_returnsEmpty() {
            when(collectionRepository.findByIdAndDeletedFalse(999L)).thenReturn(Optional.empty());

            Optional<CollectionCloneResponse> result = service.cloneCollection(999L);

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
            when(collectionRepository.save(any(RagCollection.class))).thenAnswer(inv -> {
                RagCollection c = inv.getArgument(0);
                if (c.getId() == null) c.setId(5L);
                return c;
            });

            Optional<CollectionCloneResponse> result = svcNoAudit.cloneCollection(1L);

            assertTrue(result.isPresent());
        }
    }
}
