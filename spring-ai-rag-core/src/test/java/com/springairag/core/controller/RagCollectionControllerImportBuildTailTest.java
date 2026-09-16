package com.springairag.core.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.CollectionImportRequest;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.repository.RagCollectionRepository;
import com.springairag.core.entity.RagCollection;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.service.AuditLogService;
import com.springairag.core.service.CollectionProvisioningService;
import com.springairag.core.service.DocumentMutationService;
import com.springairag.core.service.RagCollectionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RagCollectionController 导入文档构建长尾（Batch 449）：size
 * 缺省按 UTF-8 字节计算、originalFilename/sourceDeletedAt 透传、
 * namespace 空白归一 default、identity 超 255 拒绝、jsonbPayload
 * 深拷贝。
 */
class RagCollectionControllerImportBuildTailTest {

    private RagCollectionRepository collectionRepository;
    private RagDocumentRepository documentRepository;
    private com.springairag.core.service.RagCollectionService collectionService;
    private RagCollectionController controller;

    @BeforeEach
    void setUp() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(request));
        collectionRepository = mock(RagCollectionRepository.class);
        documentRepository = mock(RagDocumentRepository.class);
        collectionService = mock(com.springairag.core.service.RagCollectionService.class);
        controller = new RagCollectionController(
                collectionRepository, documentRepository,
                collectionService, mock(AuditLogService.class));
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
        return collection;
    }

    /** 关闭 mutation 委托，走直接落库路径以便捕获 RagDocument。 */
    private void disableMutationDelegation() {
        controller.setDocumentMutationService(null);
        controller.setJsonRecordService(null);
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

    private CollectionImportRequest importRequest(
            CollectionImportRequest.ImportedDocument... docs) {
        CollectionImportRequest request = new CollectionImportRequest();
        request.setName("Imported");
        request.setCollectionKey("kb");
        request.setDocuments(docs.length == 0 ? null : List.of(docs));
        return request;
    }

    @Test
    void missingSizeIsComputedFromUtf8Bytes() {
        disableMutationDelegation();
        CollectionImportRequest.ImportedDocument doc = textDocument(null);
        doc.setContent("中文内容"); // 12 UTF-8 字节，非 4 字符。

        controller.importCollection(importRequest(doc));

        ArgumentCaptor<RagDocument> captor =
                ArgumentCaptor.forClass(RagDocument.class);
        org.mockito.Mockito.verify(documentRepository)
                .saveAndFlush(captor.capture());
        assertEquals(12L, captor.getValue().getSize());
    }

    @Test
    void explicitSizeAndOriginalFilenameArePreserved() {
        disableMutationDelegation();
        CollectionImportRequest.ImportedDocument doc = textDocument(null);
        doc.setSize(777L);
        doc.setOriginalFilename("manual.pdf");

        controller.importCollection(importRequest(doc));

        ArgumentCaptor<RagDocument> captor =
                ArgumentCaptor.forClass(RagDocument.class);
        org.mockito.Mockito.verify(documentRepository)
                .saveAndFlush(captor.capture());
        assertEquals(777L, captor.getValue().getSize());
        assertEquals("manual.pdf", captor.getValue().getOriginalFilename());
    }

    @Test
    void blankNamespaceNormalizesToDefaultAndIdentityTrims() {
        disableMutationDelegation();
        CollectionImportRequest.ImportedDocument doc = textDocument("  ");
        doc.setSourceNamespace("  ");

        controller.importCollection(importRequest(doc));

        ArgumentCaptor<RagDocument> captor =
                ArgumentCaptor.forClass(RagDocument.class);
        org.mockito.Mockito.verify(documentRepository)
                .saveAndFlush(captor.capture());
        assertEquals("default", captor.getValue().getSourceNamespace());
    }

    @Test
    void identityLongerThan255IsRejected() {
        disableMutationDelegation();
        CollectionImportRequest.ImportedDocument doc = textDocument(null);
        doc.setExternalId("x".repeat(256));

        assertThrows(IllegalArgumentException.class,
                () -> controller.importCollection(importRequest(doc)));
    }

    @Test
    void sourceDeletedAtAndJsonbPayloadDeepCopyAreApplied() throws Exception {
        disableMutationDelegation();
        var deletedAt = java.time.LocalDateTime.parse("2026-09-01T00:00:00");
        var payload = (com.fasterxml.jackson.databind.JsonNode)
                new ObjectMapper().readTree("{\"k\":\"v\"}");
        CollectionImportRequest.ImportedDocument doc = textDocument(null);
        doc.setSourceDeletedAt(deletedAt);
        doc.setJsonbPayload(payload);

        controller.importCollection(importRequest(doc));

        ArgumentCaptor<RagDocument> captor =
                ArgumentCaptor.forClass(RagDocument.class);
        org.mockito.Mockito.verify(documentRepository)
                .saveAndFlush(captor.capture());
        assertEquals(deletedAt, captor.getValue().getSourceDeletedAt());
        assertEquals("{\"k\":\"v\"}",
                captor.getValue().getJsonbPayload().toString());
    }
}
