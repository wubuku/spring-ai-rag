package com.springairag.core.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.CollectionImportRequest;
import com.springairag.core.entity.RagCollection;
import com.springairag.core.repository.RagCollectionRepository;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.service.AuditLogService;
import com.springairag.core.service.DocumentMutationService;
import com.springairag.core.service.JsonRecordService;
import com.springairag.core.service.RagCollectionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * importCollection → importDocuments 分支（Batch 315）：空文档列
 * 表、重复 external identity 拒绝、跨命名空间同名允许、突变服务
 * 委派、json-record 守卫与委派、混合类型计数。
 */
class RagCollectionControllerImportDocumentsTest {

    private RagCollectionRepository collectionRepository;
    private RagDocumentRepository documentRepository;
    private RagCollectionService collectionService;
    private AuditLogService auditLogService;
    private DocumentMutationService documentMutationService;
    private JsonRecordService jsonRecordService;
    private RagCollectionController controller;

    @BeforeEach
    void setUp() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(request));
        collectionRepository = mock(RagCollectionRepository.class);
        documentRepository = mock(RagDocumentRepository.class);
        collectionService = mock(RagCollectionService.class);
        auditLogService = mock(AuditLogService.class);
        documentMutationService = mock(DocumentMutationService.class);
        jsonRecordService = mock(JsonRecordService.class);
        controller = new RagCollectionController(
                collectionRepository, documentRepository,
                collectionService, auditLogService);
        controller.setJsonRecordService(jsonRecordService);
        controller.setDocumentMutationService(documentMutationService);
        when(collectionService.createCollection(any()))
                .thenReturn(collection(1L, "kb"));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    private RagCollection collection(long id, String key) {
        RagCollection collection = new RagCollection();
        collection.setId(id);
        collection.setCollectionKey(key);
        collection.setName("Imported");
        collection.setEnabled(true);
        collection.setCreatedAt(LocalDateTime.now());
        return collection;
    }

    private CollectionImportRequest.ImportedDocument textDocument(
            String externalId) {
        CollectionImportRequest.ImportedDocument doc =
                new CollectionImportRequest.ImportedDocument();
        doc.setTitle("Doc");
        doc.setContent("body");
        doc.setExternalId(externalId);
        return doc;
    }

    private CollectionImportRequest.ImportedDocument jsonDocument(
            String externalId, String payload) {
        CollectionImportRequest.ImportedDocument doc =
                new CollectionImportRequest.ImportedDocument();
        doc.setTitle("Record");
        doc.setContent("{}");
        doc.setDocumentType("json-record");
        doc.setExternalId(externalId);
        if (payload != null) {
            doc.setJsonbPayload(tryParse(payload));
        }
        return doc;
    }

    private com.fasterxml.jackson.databind.JsonNode tryParse(String json) {
        try {
            return new ObjectMapper().readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private CollectionImportRequest importRequest(
            CollectionImportRequest.ImportedDocument... docs) {
        CollectionImportRequest request = new CollectionImportRequest();
        request.setName("Imported");
        request.setCollectionKey("kb");
        request.setDocuments(docs.length == 0 ? null : List.of(docs));
        return request;
    }

    @Test
    void importWithoutDocumentsReportsZero() {
        ResponseEntity<Map<String, Object>> response =
                controller.importCollection(importRequest());

        assertEquals(200, response.getStatusCode().value());
        assertEquals(0, response.getBody().get("importedDocuments"));
        verify(documentRepository, never()).saveAndFlush(any());
    }

    @Test
    void duplicateExternalIdentityRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> controller.importCollection(importRequest(
                        textDocument("ext-1"), textDocument("ext-1"))));
    }

    @Test
    void duplicateExternalIdentityAcrossNamespacesAllowed() {
        // 走直接落库路径才能验证 saveAndFlush 次数。
        controller.setDocumentMutationService(null);
        CollectionImportRequest.ImportedDocument first = textDocument("ext-1");
        first.setSourceNamespace("default");
        CollectionImportRequest.ImportedDocument second = textDocument("ext-1");
        second.setSourceNamespace("cms");

        ResponseEntity<Map<String, Object>> response =
                controller.importCollection(
                        importRequest(first, second));

        assertEquals(2, response.getBody().get("importedDocuments"));
        verify(documentRepository, times(2)).saveAndFlush(any());
    }

    @Test
    void blankExternalIdentitySkipsUniquenessCheck() {
        ResponseEntity<Map<String, Object>> response =
                controller.importCollection(importRequest(
                        textDocument("  "), textDocument("  ")));

        assertEquals(2, response.getBody().get("importedDocuments"));
    }

    @Test
    void mutationServiceDelegatesEveryDocument() {
        ResponseEntity<Map<String, Object>> response =
                controller.importCollection(importRequest(
                        textDocument("ext-1"), textDocument("ext-2")));

        assertEquals(2, response.getBody().get("importedDocuments"));
        verify(documentMutationService, times(2)).importDocument(
                eq(1L), eq("kb"), any());
        verify(documentRepository, never()).saveAndFlush(any());
        verify(jsonRecordService, never()).importRecord(any(), any());
    }

    @Test
    void jsonRecordRequiresExternalIdAndPayload() {
        // json-record 分支仅在突变服务缺位时可达。
        controller.setDocumentMutationService(null);
        assertThrows(IllegalArgumentException.class,
                () -> controller.importCollection(importRequest(
                        jsonDocument(null, "{\"a\":1}"))));
        assertThrows(IllegalArgumentException.class,
                () -> controller.importCollection(importRequest(
                        jsonDocument("ext-1", null))));
    }

    @Test
    void jsonRecordWithoutServiceFails() {
        controller.setDocumentMutationService(null);
        controller.setJsonRecordService(null);

        assertThrows(IllegalStateException.class,
                () -> controller.importCollection(importRequest(
                        jsonDocument("ext-1", "{\"a\":1}"))));
    }

    @Test
    void jsonRecordDelegatesToService() {
        controller.setDocumentMutationService(null);
        ResponseEntity<Map<String, Object>> response =
                controller.importCollection(importRequest(
                        jsonDocument("ext-1", "{\"a\":1}")));

        assertEquals(1, response.getBody().get("importedDocuments"));
        verify(jsonRecordService).importRecord(eq(1L), any());
        verify(documentRepository, never()).saveAndFlush(any());
    }

    @Test
    void mixedDocumentKindsAreCountedTogether() {
        // 文本记录：saveAndFlush；json-record：importRecord。
        controller.setDocumentMutationService(null);
        when(documentRepository.saveAndFlush(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ResponseEntity<Map<String, Object>> response =
                controller.importCollection(importRequest(
                        textDocument("ext-1"),
                        jsonDocument("ext-2", "{\"a\":1}")));

        assertEquals(2, response.getBody().get("importedDocuments"));
        verify(jsonRecordService).importRecord(eq(1L), any());
        verify(documentRepository).saveAndFlush(any());
    }
}
