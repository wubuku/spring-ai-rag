package com.springairag.core.controller;

import com.springairag.core.repository.RagCollectionRepository;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.service.AuditLogService;
import com.springairag.core.service.CollectionProvisioningService;
import com.springairag.core.service.RagCollectionService;
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

/**
 * RagCollectionController.listDocuments 长尾（Batch 436）：过滤
 * 条件的有无切换搜索方法、空白 keyword 归一 null、offset→page
 * 换算、集合键映射透出。
 */
class RagCollectionControllerListTailTest {

    private RagCollectionRepository collectionRepository;
    private RagDocumentRepository documentRepository;
    private RagCollectionController controller;

    @BeforeEach
    void setUp() {
        collectionRepository = mock(RagCollectionRepository.class);
        documentRepository = mock(RagDocumentRepository.class);
        controller = new RagCollectionController(
                collectionRepository,
                documentRepository,
                mock(RagCollectionService.class),
                mock(AuditLogService.class));
        when(collectionRepository.findByIdAndDeletedFalse(1L))
                .thenReturn(Optional.of(collection(1L)));
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
    void keywordFilterRoutesToSearchAndTrimsKeyword() {
        setCurrentRequest();
        when(documentRepository.searchDocumentsByCollectionId(
                any(), any(), any(), any(), any())).thenReturn(emptyPage());
        when(documentRepository.findByCollectionId(any(), any()))
                .thenReturn(emptyPage());

        controller.listDocuments(1L, 0, 20, "  spring  ", null, null);

        ArgumentCaptor<String> keyword = ArgumentCaptor.forClass(String.class);
        verify(documentRepository).searchDocumentsByCollectionId(
                eq(1L), keyword.capture(), isNull(), isNull(), any());
        assertEquals("spring", keyword.getValue());
        verify(documentRepository, org.mockito.Mockito.never())
                .findByCollectionId(any(), any());
    }

    @Test
    void blankKeywordWithDocumentTypeStillRoutesToSearch() {
        setCurrentRequest();
        when(documentRepository.searchDocumentsByCollectionId(
                any(), any(), any(), any(), any())).thenReturn(emptyPage());

        controller.listDocuments(1L, 0, 20, "   ", "pdf", null);

        // 既有语义：空白 keyword trim 为空串透传（不转 null）。
        verify(documentRepository).searchDocumentsByCollectionId(
                eq(1L), eq(""), eq("pdf"), isNull(), any());
    }

    @Test
    void processingStatusOnlyFilterRoutesToSearch() {
        setCurrentRequest();
        when(documentRepository.searchDocumentsByCollectionId(
                any(), any(), any(), any(), any())).thenReturn(emptyPage());

        controller.listDocuments(1L, 0, 20, null, null, "COMPLETED");

        verify(documentRepository).searchDocumentsByCollectionId(
                eq(1L), isNull(), isNull(), eq("COMPLETED"), any());
    }

    @Test
    void offsetIsConvertedIntoPageNumber() {
        setCurrentRequest();
        when(documentRepository.findByCollectionId(any(), any()))
                .thenReturn(emptyPage());

        controller.listDocuments(1L, 40, 20, null, null, null);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(documentRepository).findByCollectionId(eq(1L), pageable.capture());
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
                collectionRepository,
                documentRepository,
                mock(RagCollectionService.class),
                mock(AuditLogService.class));
        org.springframework.test.util.ReflectionTestUtils.setField(
                controller, "identityResolver", resolver);
        when(resolver.mapKeys(List.of(1L))).thenReturn(java.util.Map.of(1L, "kb"));
        when(documentRepository.findByCollectionId(any(), any()))
                .thenReturn(emptyPage());

        var response = controller.listDocuments(1L, 0, 20, null, null, null);

        assertEquals("kb", response.getBody().collectionKey());
        assertNotNull(response.getBody());
    }
}
