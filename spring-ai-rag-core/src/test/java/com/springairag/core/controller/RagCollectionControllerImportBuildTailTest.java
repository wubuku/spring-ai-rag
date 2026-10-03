package com.springairag.core.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.CollectionImportRequest;
import com.springairag.core.entity.RagCollection;
import com.springairag.core.repository.RagCollectionRepository;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.service.AuditLogService;
import com.springairag.core.service.DocumentMutationService;
import com.springairag.core.service.CollectionIdentityResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RagCollectionController 导入入口的委派契约。
 *
 * <p>Batch 847 之前，导入分派没有 mutation 委托时会走 controller 内联的
 * legacy 路径：自己拼 {@code RagDocument}、自己按 UTF-8 算 size、自己在
 * 仓储里 saveAndFlush。本文件原来那五条用例全部依赖那条已删的路径
 * （通过 {@code disableMutationDelegation()} 把委托置空），断言的是
 * {@code buildDocumentFromImport} 的产物。
 *
 * <p>现在 controller 不再构造文档，只负责把请求原样交给
 * {@link DocumentMutationService#importDocument}，字段映射（size 按 UTF-8
 * 字节计算、namespace 归一、jsonbPayload 深拷贝、identity/title 长度校验）
 * 全部由变更层负责，测试也随之搬到了 service 侧。
 */
class RagCollectionControllerImportBuildTailTest {

    private RagCollectionRepository collectionRepository;
    private RagDocumentRepository documentRepository;
    private com.springairag.core.service.RagCollectionService collectionService;
    private DocumentMutationService documentMutationService;
    private RagCollectionController controller;

    @BeforeEach
    void setUp() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(request));
        collectionRepository = mock(RagCollectionRepository.class);
        documentRepository = mock(RagDocumentRepository.class);
        collectionService = mock(com.springairag.core.service.RagCollectionService.class);
        documentMutationService = mock(DocumentMutationService.class);
        controller = new RagCollectionController(
                collectionRepository,
                documentRepository,
                collectionService,
                new CollectionIdentityResolver(collectionRepository),
                mock(AuditLogService.class),
                documentMutationService);
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
    void importedDocumentIsHandedToTheMutationLayerWithoutRewritingItsFields() throws Exception {
        LocalDateTime deletedAt = LocalDateTime.parse("2026-09-01T00:00:00");
        var payload = (com.fasterxml.jackson.databind.JsonNode)
                new ObjectMapper().readTree("{\"k\":\"v\"}");
        CollectionImportRequest.ImportedDocument doc = textDocument("ext-1");
        doc.setSize(777L);
        doc.setOriginalFilename("manual.pdf");
        doc.setSourceNamespace("  ");
        doc.setSourceDeletedAt(deletedAt);
        doc.setJsonbPayload(payload);

        controller.importCollection(importRequest(doc));

        ArgumentCaptor<CollectionImportRequest.ImportedDocument> captor =
                ArgumentCaptor.forClass(CollectionImportRequest.ImportedDocument.class);
        verify(documentMutationService).importDocument(eq(1L), eq("kb"), captor.capture());

        // 归一化、size 计算、payload 深拷贝都是变更层的职责；controller
        // 交出去的必须是调用方给的原样请求，替它"顺手改好"反而会让
        // service 侧那些用例失去意义。
        CollectionImportRequest.ImportedDocument forwarded = captor.getValue();
        assertEquals("  ", forwarded.getSourceNamespace());
        assertEquals(777L, forwarded.getSize());
        assertEquals("manual.pdf", forwarded.getOriginalFilename());
        assertEquals(deletedAt, forwarded.getSourceDeletedAt());
        assertEquals("{\"k\":\"v\"}", forwarded.getJsonbPayload().toString());
    }

    @Test
    void importNeverFallsBackToBuildingDocumentsInTheController() {
        controller.importCollection(importRequest(textDocument("ext-1"), textDocument("ext-2")));

        verify(documentMutationService, org.mockito.Mockito.times(2))
                .importDocument(eq(1L), eq("kb"), any());
        verify(documentRepository, never()).saveAndFlush(any());
    }
}
