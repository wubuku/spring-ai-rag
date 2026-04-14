package com.springairag.core.controller;

import com.springairag.api.dto.CollectionDocumentListResponse;
import com.springairag.api.dto.CollectionExportResponse;
import com.springairag.api.dto.CollectionRequest;
import com.springairag.api.dto.CollectionRestoreResponse;
import com.springairag.api.dto.DocumentAddedResponse;
import com.springairag.core.entity.RagCollection;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.repository.RagCollectionRepository;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.service.AuditLogService;
import com.springairag.core.service.RagCollectionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.*;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * RagCollectionController unit tests.
 */
class RagCollectionControllerTest {

    private RagCollectionRepository collectionRepository;
    private RagDocumentRepository documentRepository;
    private RagCollectionService collectionService;
    private AuditLogService auditLogService;
    private RagCollectionController controller;

    @BeforeEach
    void setUp() {
        collectionRepository = mock(RagCollectionRepository.class);
        documentRepository = mock(RagDocumentRepository.class);
        collectionService = mock(RagCollectionService.class);
        auditLogService = mock(AuditLogService.class);
        controller = new RagCollectionController(collectionRepository, documentRepository, collectionService, auditLogService);
    }

    private RagCollection createCollection(Long id, String name) {
        RagCollection c = new RagCollection();
        c.setId(id);
        c.setName(name);
        c.setDescription("测试集合 " + name);
        c.setEmbeddingModel("bge-m3");
        c.setDimensions(1024);
        c.setEnabled(true);
        c.setCreatedAt(LocalDateTime.now());
        return c;
    }

    @Test
    void create_returnsCollectionWithId() {
        when(collectionRepository.save(any(RagCollection.class))).thenAnswer(inv -> {
            RagCollection c = inv.getArgument(0);
            c.setId(1L);
            c.setCreatedAt(LocalDateTime.now());
            return c;
        });

        CollectionRequest req = new CollectionRequest();
        req.setName("测试知识库");
        req.setDescription("这是一个测试知识库");

        ResponseEntity<Map<String, Object>> response = controller.create(req);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(1L, response.getBody().get("id"));
        assertEquals("测试知识库", response.getBody().get("name"));
        assertEquals(1024, response.getBody().get("dimensions"));
    }

    @Test
    void getById_existingCollection_returnsCollection() {
        RagCollection c = createCollection(1L, "知识库A");
        when(collectionRepository.findByIdAndDeletedFalse(1L)).thenReturn(Optional.of(c));
        when(documentRepository.countByCollectionId(1L)).thenReturn(5L);

        ResponseEntity<Map<String, Object>> response = controller.getById(1L);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("知识库A", response.getBody().get("name"));
        assertEquals(5L, response.getBody().get("documentCount"));
    }

    @Test
    void getById_nonExisting_returns404() {
        when(collectionRepository.findByIdAndDeletedFalse(999L)).thenReturn(Optional.empty());

        ResponseEntity<Map<String, Object>> response = controller.getById(999L);

        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void getById_deletedCollection_returns404() {
        // A deleted collection should not be returned even if findById finds it
        RagCollection c = createCollection(1L, "Deleted Collection");
        c.setDeleted(true);
        c.setDeletedAt(LocalDateTime.now());
        when(collectionRepository.findById(1L)).thenReturn(Optional.of(c));
        when(collectionRepository.findByIdAndDeletedFalse(1L)).thenReturn(Optional.empty());

        ResponseEntity<Map<String, Object>> response = controller.getById(1L);

        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void list_returnsPagedCollections() {
        RagCollection c1 = createCollection(1L, "知识库A");
        RagCollection c2 = createCollection(2L, "知识库B");
        Page<RagCollection> page = new PageImpl<>(List.of(c1, c2));

        when(collectionRepository.searchCollections(isNull(), isNull(), any(Pageable.class)))
                .thenReturn(page);
        when(documentRepository.countByCollectionId(1L)).thenReturn(3L);
        when(documentRepository.countByCollectionId(2L)).thenReturn(7L);

        ResponseEntity<Map<String, Object>> response = controller.list(0, 20, null, null);

        assertEquals(200, response.getStatusCode().value());
        List<?> collections = (List<?>) response.getBody().get("collections");
        assertEquals(2, collections.size());
        assertEquals(2L, response.getBody().get("total"));
    }

    @Test
    void list_withNameFilter_filtersByName() {
        RagCollection c = createCollection(1L, "RAG 知识库");
        Page<RagCollection> page = new PageImpl<>(List.of(c));

        when(collectionRepository.searchCollections(eq("RAG"), isNull(), any(Pageable.class)))
                .thenReturn(page);
        when(documentRepository.countByCollectionId(1L)).thenReturn(0L);

        ResponseEntity<Map<String, Object>> response = controller.list(0, 20, "RAG", null);

        assertEquals(200, response.getStatusCode().value());
        List<?> collections = (List<?>) response.getBody().get("collections");
        assertEquals(1, collections.size());
    }

    @Test
    void update_existingCollection_updatesFields() {
        RagCollection existing = createCollection(1L, "旧名称");
        when(collectionRepository.findByIdAndDeletedFalse(1L)).thenReturn(Optional.of(existing));
        when(collectionRepository.save(any(RagCollection.class))).thenAnswer(inv -> inv.getArgument(0));
        when(documentRepository.countByCollectionId(1L)).thenReturn(0L);

        CollectionRequest req = new CollectionRequest();
        req.setName("新名称");
        req.setDescription("新描述");

        ResponseEntity<Map<String, Object>> response = controller.update(1L, req);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("新名称", response.getBody().get("name"));
        assertEquals("新描述", response.getBody().get("description"));
    }

    @Test
    void update_nonExisting_returns404() {
        when(collectionRepository.findByIdAndDeletedFalse(999L)).thenReturn(Optional.empty());

        CollectionRequest req = new CollectionRequest();
        req.setName("test");

        ResponseEntity<Map<String, Object>> response = controller.update(999L, req);

        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void delete_existingCollection_unlinksDocumentsAndSoftDeletes() {
        when(collectionService.deleteCollection(1L))
                .thenReturn(Optional.of(new com.springairag.core.service.RagCollectionService.DeleteResult(1L, 1L)));

        ResponseEntity<Map<String, String>> response = controller.delete(1L);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("Collection deleted", response.getBody().get("message"));
        assertEquals("1", response.getBody().get("documentsUnlinked"));
        verify(collectionService).deleteCollection(1L);
    }

    @Test
    void delete_nonExisting_returns404() {
        when(collectionService.deleteCollection(999L)).thenReturn(Optional.empty());

        ResponseEntity<Map<String, String>> response = controller.delete(999L);

        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void delete_existingCollectionNoDocuments_doesNotCallClearCollectionId() {
        when(collectionService.deleteCollection(1L))
                .thenReturn(Optional.of(new com.springairag.core.service.RagCollectionService.DeleteResult(1L, 0L)));

        ResponseEntity<Map<String, String>> response = controller.delete(1L);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("0", response.getBody().get("documentsUnlinked"));
        verify(collectionService).deleteCollection(1L);
    }

    @Test
    void delete_alreadyDeleted_returns404() {
        when(collectionService.deleteCollection(1L)).thenReturn(Optional.empty());

        ResponseEntity<Map<String, String>> response = controller.delete(1L);

        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void restore_existingDeletedCollection_restoresSuccessfully() {
        RagCollection restored = createCollection(1L, "Restored Collection");
        restored.setDeleted(false);
        // The restored collection's id is used to count documents
        when(collectionService.restoreCollection(1L))
                .thenReturn(Optional.of(new com.springairag.core.service.RagCollectionService.RestoreResult(restored, 3L)));
        // The controller also calls documentRepository.countByCollectionId for the response map
        when(documentRepository.countByCollectionId(1L)).thenReturn(3L);

        ResponseEntity<CollectionRestoreResponse> response = controller.restore(1L);

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals("Restored Collection", response.getBody().name());
        assertEquals(3L, response.getBody().documentCount());
        assertEquals("Collection restored", response.getBody().message());
        verify(collectionService).restoreCollection(1L);
    }

    @Test
    void restore_nonExisting_returns404() {
        when(collectionService.restoreCollection(999L)).thenReturn(Optional.empty());

        ResponseEntity<CollectionRestoreResponse> response = controller.restore(999L);

        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void listDocuments_collectionExists_returnsPagedDocs() {
        when(collectionRepository.existsById(1L)).thenReturn(true);

        RagDocument doc = new RagDocument();
        doc.setId(10L);
        doc.setTitle("文档A");
        doc.setCreatedAt(LocalDateTime.now());
        Page<RagDocument> page = new PageImpl<>(List.of(doc));

        when(documentRepository.findByCollectionId(eq(1L), any(Pageable.class))).thenReturn(page);

        ResponseEntity<CollectionDocumentListResponse> response = controller.listDocuments(1L, 0, 20, null, null, null);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(1L, response.getBody().collectionId());
        assertEquals(1, response.getBody().documents().size());
    }

    @Test
    void listDocuments_collectionNotExists_returns404() {
        when(collectionRepository.existsById(999L)).thenReturn(false);

        ResponseEntity<CollectionDocumentListResponse> response = controller.listDocuments(999L, 0, 20, null, null, null);

        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void addDocument_collectionAndDocExist_linksDocument() {
        when(collectionRepository.existsById(1L)).thenReturn(true);
        RagDocument doc = new RagDocument();
        doc.setId(10L);
        when(documentRepository.findById(10L)).thenReturn(Optional.of(doc));
        when(documentRepository.save(any(RagDocument.class))).thenAnswer(inv -> inv.getArgument(0));

        ResponseEntity<DocumentAddedResponse> response = controller.addDocument(1L, Map.of("documentId", 10L));

        assertEquals(200, response.getStatusCode().value());
        assertEquals("Document added to collection", response.getBody().message());
        assertEquals(1L, response.getBody().collectionId());
        assertEquals(10L, response.getBody().documentId());
    }

    @Test
    void addDocument_missingDocumentId_returns400() {
        assertThrows(IllegalArgumentException.class,
                () -> controller.addDocument(1L, Map.of()));
    }

    @Test
    void addDocument_collectionNotExists_returns404() {
        when(collectionRepository.existsById(999L)).thenReturn(false);

        ResponseEntity<DocumentAddedResponse> response = controller.addDocument(999L, Map.of("documentId", 10L));

        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void addDocument_documentNotExists_returns404() {
        when(collectionRepository.existsById(1L)).thenReturn(true);
        when(documentRepository.findById(999L)).thenReturn(Optional.empty());

        ResponseEntity<DocumentAddedResponse> response = controller.addDocument(1L, Map.of("documentId", 999L));

        assertEquals(404, response.getStatusCode().value());
    }

    // ========== 导出/导入 ==========

    @Test
    void exportCollection_returnsCollectionAndDocuments() {
        RagCollection c = createCollection(1L, "知识库A");
        when(collectionRepository.findByIdAndDeletedFalse(1L)).thenReturn(Optional.of(c));

        RagDocument doc = new RagDocument();
        doc.setTitle("文档1");
        doc.setSource("test.txt");
        doc.setContent("内容");
        doc.setSize(100L);
        when(documentRepository.findAllByCollectionId(1L)).thenReturn(List.of(doc));

        ResponseEntity<CollectionExportResponse> response = controller.exportCollection(1L);

        assertEquals(200, response.getStatusCode().value());
        CollectionExportResponse body = response.getBody();
        assertNotNull(body);
        assertEquals("知识库A", body.name());
        assertEquals(1, body.documentCount());
        assertNotNull(body.exportedAt());

        assertEquals("文档1", body.documents().get(0).title());
    }

    @Test
    void exportCollection_multipleDocuments_exportsAllCorrectly() {
        RagCollection c = createCollection(1L, "多文档知识库");
        when(collectionRepository.findByIdAndDeletedFalse(1L)).thenReturn(Optional.of(c));

        RagDocument doc1 = new RagDocument();
        doc1.setTitle("文档A");
        doc1.setSource("a.txt");
        doc1.setContent("内容A");
        doc1.setDocumentType("PDF");
        doc1.setMetadata(Map.of("author", "Alice"));
        doc1.setSize(200L);

        RagDocument doc2 = new RagDocument();
        doc2.setTitle("文档B");
        doc2.setSource("b.txt");
        doc2.setContent("内容B");
        doc2.setSize(150L);

        when(documentRepository.findAllByCollectionId(1L)).thenReturn(List.of(doc1, doc2));

        ResponseEntity<CollectionExportResponse> response = controller.exportCollection(1L);

        assertEquals(200, response.getStatusCode().value());
        CollectionExportResponse body = response.getBody();
        assertNotNull(body);
        assertEquals(2, body.documentCount());

        assertEquals(2, body.documents().size());
        assertEquals("文档A", body.documents().get(0).title());
        assertEquals("PDF", body.documents().get(0).documentType());
        assertEquals("Alice", body.documents().get(0).metadata().get("author"));
        assertEquals("文档B", body.documents().get(1).title());
    }

    @Test
    void exportCollection_deletedCollection_returns404() {
        when(collectionRepository.findByIdAndDeletedFalse(1L)).thenReturn(Optional.empty());

        ResponseEntity<CollectionExportResponse> response = controller.exportCollection(1L);

        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void importCollection_createsCollectionAndDocuments() {
        RagCollection saved = createCollection(1L, "导入的知识库");
        when(collectionRepository.save(any(RagCollection.class))).thenReturn(saved);

        RagDocument savedDoc = new RagDocument();
        savedDoc.setId(10L);
        when(documentRepository.save(any(RagDocument.class))).thenReturn(savedDoc);

        Map<String, Object> importData = new HashMap<>();
        importData.put("name", "导入的知识库");
        importData.put("description", "测试导入");
        importData.put("dimensions", 1024);

        List<Map<String, Object>> docs = new ArrayList<>();
        Map<String, Object> docData = new HashMap<>();
        docData.put("title", "文档1");
        docData.put("source", "test.txt");
        docData.put("content", "内容");
        docData.put("size", 100);
        docs.add(docData);
        importData.put("documents", docs);

        ResponseEntity<Map<String, Object>> response = controller.importCollection(importData);

        assertEquals(200, response.getStatusCode().value());
        verify(collectionRepository).save(any(RagCollection.class));
        verify(documentRepository).save(any(RagDocument.class));
    }

    @Test
    void importCollection_missingName_returns400() {
        Map<String, Object> importData = Map.of("description", "no name");

        assertThrows(IllegalArgumentException.class,
                () -> controller.importCollection(importData));
    }

    @Test
    void importCollection_emptyName_returns400() {
        assertThrows(IllegalArgumentException.class,
                () -> controller.importCollection(Map.of("name", "  ")));
    }

    @Test
    void cloneCollection_existingCollectionWithDocuments_clonesSuccessfully() {
        com.springairag.api.dto.CollectionCloneResponse mockResponse =
                com.springairag.api.dto.CollectionCloneResponse.of(5L, "Source Collection (Copy)", 1L, "Source Collection", 2);
        when(collectionService.cloneCollection(1L)).thenReturn(Optional.of(mockResponse));

        ResponseEntity<com.springairag.api.dto.CollectionCloneResponse> response =
                controller.cloneCollection(1L);

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals(5L, response.getBody().clonedCollectionId());
        assertEquals("Source Collection (Copy)", response.getBody().clonedCollectionName());
        assertEquals(1L, response.getBody().sourceCollectionId());
        assertEquals("Source Collection", response.getBody().sourceCollectionName());
        assertEquals(2, response.getBody().documentsCloned());
        verify(collectionService).cloneCollection(1L);
    }

    @Test
    void cloneCollection_existingCollectionNoDocuments_clonesWithZeroDocuments() {
        com.springairag.api.dto.CollectionCloneResponse mockResponse =
                com.springairag.api.dto.CollectionCloneResponse.of(5L, "Empty Source (Copy)", 1L, "Empty Source", 0);
        when(collectionService.cloneCollection(1L)).thenReturn(Optional.of(mockResponse));

        ResponseEntity<com.springairag.api.dto.CollectionCloneResponse> response =
                controller.cloneCollection(1L);

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals(5L, response.getBody().clonedCollectionId());
        assertEquals(0, response.getBody().documentsCloned());
        verify(collectionService).cloneCollection(1L);
    }

    @Test
    void cloneCollection_nonExisting_returns404() {
        when(collectionService.cloneCollection(999L)).thenReturn(Optional.empty());

        ResponseEntity<com.springairag.api.dto.CollectionCloneResponse> response =
                controller.cloneCollection(999L);

        assertEquals(404, response.getStatusCode().value());
    }
}
