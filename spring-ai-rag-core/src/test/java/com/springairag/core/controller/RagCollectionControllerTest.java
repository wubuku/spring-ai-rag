package com.springairag.core.controller;

import com.springairag.api.dto.CollectionRequest;
import com.springairag.core.entity.RagCollection;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.repository.RagCollectionRepository;
import com.springairag.core.repository.RagDocumentRepository;
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
 * RagCollectionController 单元测试
 */
class RagCollectionControllerTest {

    private RagCollectionRepository collectionRepository;
    private RagDocumentRepository documentRepository;
    private RagCollectionController controller;

    @BeforeEach
    void setUp() {
        collectionRepository = mock(RagCollectionRepository.class);
        documentRepository = mock(RagDocumentRepository.class);
        controller = new RagCollectionController(collectionRepository, documentRepository);
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
        when(collectionRepository.findById(1L)).thenReturn(Optional.of(c));
        when(documentRepository.countByCollectionId(1L)).thenReturn(5L);

        ResponseEntity<Map<String, Object>> response = controller.getById(1L);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("知识库A", response.getBody().get("name"));
        assertEquals(5L, response.getBody().get("documentCount"));
    }

    @Test
    void getById_nonExisting_returns404() {
        when(collectionRepository.findById(999L)).thenReturn(Optional.empty());

        ResponseEntity<Map<String, Object>> response = controller.getById(999L);

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
        when(collectionRepository.findById(1L)).thenReturn(Optional.of(existing));
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
        when(collectionRepository.findById(999L)).thenReturn(Optional.empty());

        CollectionRequest req = new CollectionRequest();
        req.setName("test");

        ResponseEntity<Map<String, Object>> response = controller.update(999L, req);

        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void delete_existingCollection_unlinksDocumentsAndDeletes() {
        RagCollection c = createCollection(1L, "待删除集合");
        when(collectionRepository.findById(1L)).thenReturn(Optional.of(c));

        RagDocument doc = new RagDocument();
        doc.setId(10L);
        doc.setCollectionId(1L);
        when(documentRepository.findAllByCollectionId(1L)).thenReturn(List.of(doc));

        ResponseEntity<Map<String, String>> response = controller.delete(1L);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("集合已删除", response.getBody().get("message"));
        assertEquals("1", response.getBody().get("documentsUnlinked"));
        verify(documentRepository).saveAll(anyList());
        verify(collectionRepository).deleteById(1L);
    }

    @Test
    void delete_nonExisting_returns404() {
        when(collectionRepository.findById(999L)).thenReturn(Optional.empty());

        ResponseEntity<Map<String, String>> response = controller.delete(999L);

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

        ResponseEntity<Map<String, Object>> response = controller.listDocuments(1L, 0, 20);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(1L, response.getBody().get("collectionId"));
        List<?> docs = (List<?>) response.getBody().get("documents");
        assertEquals(1, docs.size());
    }

    @Test
    void listDocuments_collectionNotExists_returns404() {
        when(collectionRepository.existsById(999L)).thenReturn(false);

        ResponseEntity<Map<String, Object>> response = controller.listDocuments(999L, 0, 20);

        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void addDocument_collectionAndDocExist_linksDocument() {
        when(collectionRepository.existsById(1L)).thenReturn(true);
        RagDocument doc = new RagDocument();
        doc.setId(10L);
        when(documentRepository.findById(10L)).thenReturn(Optional.of(doc));
        when(documentRepository.save(any(RagDocument.class))).thenAnswer(inv -> inv.getArgument(0));

        ResponseEntity<Map<String, Object>> response = controller.addDocument(1L, Map.of("documentId", 10L));

        assertEquals(200, response.getStatusCode().value());
        assertEquals("文档已加入集合", response.getBody().get("message"));
        assertEquals(1L, response.getBody().get("collectionId"));
        assertEquals(10L, response.getBody().get("documentId"));
    }

    @Test
    void addDocument_missingDocumentId_returns400() {
        ResponseEntity<Map<String, Object>> response = controller.addDocument(1L, Map.of());

        assertEquals(400, response.getStatusCode().value());
    }

    @Test
    void addDocument_collectionNotExists_returns404() {
        when(collectionRepository.existsById(999L)).thenReturn(false);

        ResponseEntity<Map<String, Object>> response = controller.addDocument(999L, Map.of("documentId", 10L));

        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void addDocument_documentNotExists_returns404() {
        when(collectionRepository.existsById(1L)).thenReturn(true);
        when(documentRepository.findById(999L)).thenReturn(Optional.empty());

        ResponseEntity<Map<String, Object>> response = controller.addDocument(1L, Map.of("documentId", 999L));

        assertEquals(404, response.getStatusCode().value());
    }
}
