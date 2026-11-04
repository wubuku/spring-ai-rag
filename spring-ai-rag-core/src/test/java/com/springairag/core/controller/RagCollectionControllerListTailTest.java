package com.springairag.core.controller;

import com.springairag.core.repository.RagCollectionRepository;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.service.AuditLogService;
import com.springairag.core.security.ProvisioningOwnerResolver;
import com.springairag.core.service.CollectionProvisioningService;
import com.springairag.core.service.RagCollectionService;
import com.springairag.core.service.CollectionIdentityResolver;
import com.springairag.core.service.DocumentMutationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.spy;

/**
 * RagCollectionController.listDocuments 长尾（Batch 436）：过滤
 * 条件的有无切换搜索方法、空白 keyword 归一 null、offset→page
 * 换算、集合键映射透出。
 */
class RagCollectionControllerListTailTest {

    private RagCollectionRepository collectionRepository;
    private RagDocumentRepository documentRepository;
    private RagCollectionService collectionService;
    private RagCollectionController controller;

    @BeforeEach
    void setUp() {
        collectionRepository = mock(RagCollectionRepository.class);
        documentRepository = mock(RagDocumentRepository.class);
        collectionService = mock(RagCollectionService.class);
        controller = new RagCollectionController(
                collectionService,
                new CollectionIdentityResolver(collectionRepository),
                mock(AuditLogService.class),
                mock(DocumentMutationService.class),
                mock(CollectionProvisioningService.class),
                new ProvisioningOwnerResolver(),
                null);
        // 无请求上下文时 currentPolicy 为 null → 视为 unrestricted。
        RequestContextHolder.resetRequestAttributes();
    }

    private com.springairag.core.entity.RagCollection collection(long id) {
        var value = new com.springairag.core.entity.RagCollection();
        value.setId(id);
        value.setEnabled(true);
        return value;
    }

    private org.springframework.data.domain.Page<com.springairag.core.entity.RagDocument>
            emptyPage() {
        return new org.springframework.data.domain.PageImpl<>(List.of());
    }

    private void setCurrentRequest() {
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(
                        new org.springframework.mock.web.MockHttpServletRequest()));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void keywordIsForwardedToCollectionService() {
        setCurrentRequest();
        when(collectionService.findDocuments(
                eq(1L), any(), any(), any(), any()))
                .thenReturn(java.util.Optional.of(emptyPage()));

        controller.listDocuments(1L, 0, 20, "  spring  ", null, null);

        ArgumentCaptor<String> keyword = ArgumentCaptor.forClass(String.class);
        verify(collectionService).findDocuments(
                eq(1L), keyword.capture(), isNull(), isNull(), any());
        // 控制器只透传原始 keyword；trim 与路由由 service 负责。
        assertEquals("  spring  ", keyword.getValue());
    }

    @Test
    void blankKeywordWithDocumentTypeIsForwardedUnchanged() {
        setCurrentRequest();
        when(collectionService.findDocuments(
                eq(1L), any(), any(), any(), any()))
                .thenReturn(java.util.Optional.of(emptyPage()));

        controller.listDocuments(1L, 0, 20, "   ", "pdf", null);

        verify(collectionService).findDocuments(
                eq(1L), eq("   "), eq("pdf"), isNull(), any());
    }

    @Test
    void processingStatusOnlyFilterIsForwarded() {
        setCurrentRequest();
        when(collectionService.findDocuments(
                eq(1L), any(), any(), any(), any()))
                .thenReturn(java.util.Optional.of(emptyPage()));

        controller.listDocuments(1L, 0, 20, null, null, "COMPLETED");

        verify(collectionService).findDocuments(
                eq(1L), isNull(), isNull(), eq("COMPLETED"), any());
    }

    @Test
    void offsetIsConvertedIntoPageNumber() {
        setCurrentRequest();
        when(collectionService.findDocuments(
                eq(1L), any(), any(), any(), any()))
                .thenReturn(java.util.Optional.of(emptyPage()));

        controller.listDocuments(1L, 40, 20, null, null, null);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(collectionService).findDocuments(
                eq(1L), isNull(), isNull(), isNull(), pageable.capture());
        // offset 40 / limit 20 → 第 2 页（0 基）。
        assertEquals(2, pageable.getValue().getPageNumber());
        assertEquals(20, pageable.getValue().getPageSize());
    }

    @Test
    void collectionKeyIsResolvedIntoResponse() {
        setCurrentRequest();
        var resolver = org.mockito.Mockito.mock(
                com.springairag.core.service.CollectionIdentityResolver.class);
        // mapKeys 需要返回键映射：重建 controller 注入 resolver。
        controller = new RagCollectionController(
                collectionService,
                new CollectionIdentityResolver(collectionRepository),
                mock(AuditLogService.class),
                mock(DocumentMutationService.class),
                mock(CollectionProvisioningService.class),
                new ProvisioningOwnerResolver(),
                null);
        org.springframework.test.util.ReflectionTestUtils.setField(
                controller, "identityResolver", resolver);
        when(resolver.mapKeys(List.of(1L))).thenReturn(java.util.Map.of(1L, "kb"));
        when(collectionService.findDocuments(
                eq(1L), any(), any(), any(), any()))
                .thenReturn(java.util.Optional.of(emptyPage()));

        var response = controller.listDocuments(1L, 0, 20, null, null, null);

        assertEquals("kb", response.getBody().collectionKey());
        assertNotNull(response.getBody());
    }
}
