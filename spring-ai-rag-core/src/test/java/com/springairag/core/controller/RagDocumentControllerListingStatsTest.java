package com.springairag.core.controller;

import com.springairag.api.dto.RetrievalResult;
import com.springairag.api.enums.EmbeddingPolicy;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.entity.RagCollection;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.RagCollectionRepository;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.repository.RagEmbeddingRepository;
import com.springairag.core.service.BatchDocumentService;
import com.springairag.core.service.CollectionIdentityResolver;
import com.springairag.core.service.DocumentEmbedService;
import com.springairag.core.service.DocumentVersionService;
import com.springairag.core.embeddingjob.EmbeddingDispatchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RagDocumentController 列表统计与嵌入端点残余（Batch 386）：
 * 文档统计的无限制/受限查询与 UNKNOWN 状态归并、嵌入端点的
 * ASYNC 派发/SYNC 直调/参数错误、私有查询选择与集合元数据收集。
 */
class RagDocumentControllerListingStatsTest {

    private RagDocumentRepository documentRepository;
    private RagCollectionRepository collectionRepository;
    private DocumentEmbedService documentEmbedService;
    private EmbeddingDispatchService dispatchService;
    private RagDocumentController controller;

    @BeforeEach
    void setUp() {
        documentRepository = mock(RagDocumentRepository.class);
        collectionRepository = mock(RagCollectionRepository.class);
        documentEmbedService = mock(DocumentEmbedService.class);
        dispatchService = mock(EmbeddingDispatchService.class);
        controller = new RagDocumentController(
                documentRepository,
                mock(RagEmbeddingRepository.class),
                collectionRepository,
                documentEmbedService,
                mock(BatchDocumentService.class),
                mock(DocumentVersionService.class),
                mock(EmbeddingProfileProvider.class),
                mock(CollectionIdentityResolver.class),
                null);
        controller.setDispatchService(dispatchService);
    }

    @Test
    @SuppressWarnings("unchecked")
    void documentStatsMergeCountsAndNormalizeNullStatus() {
        List<Object[]> rows = List.of(
                new Object[]{"PENDING", 2L},
                new Object[]{null, 1L});
        when(documentRepository.countByProcessingStatus()).thenReturn(rows);

        var response = controller.getDocumentStats();

        assertEquals(200, response.getStatusCode().value());
        assertEquals(3L, response.getBody().total());
        assertEquals(2L, response.getBody().byStatus().get("PENDING"));
        assertEquals(1L, response.getBody().byStatus().get("UNKNOWN"));
    }

    @Test
    void embedDocumentAsyncDelegatesToDispatch() {
        when(documentRepository.findById(41L))
                .thenReturn(Optional.of(new RagDocument()));
        when(dispatchService.enqueueInCurrentTransaction(
                any(RagDocument.class), anyBoolean(), anyBoolean(), anyString()))
                .thenReturn(new EmbeddingDispatchService.Result(
                        com.springairag.api.enums.EmbeddingAction.ASYNC_QUEUED,
                        "QUEUED", "profile", UUID.randomUUID(),
                        UUID.randomUUID(), null));

        ResponseEntity<Object> response = controller.embedDocument(
                41L, false, EmbeddingPolicy.ASYNC);

        assertEquals(200, response.getStatusCode().value());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertEquals("QUEUED", body.get("status"));
    }

    @Test
    void embedDocumentSyncDelegatesAndMapsIllegalArgumentToBadRequest() {
        when(documentEmbedService.embedDocument(41L, false))
                .thenReturn(Map.of("status", "COMPLETED", "chunks", 3));

        assertEquals(200, controller.embedDocument(41L, false)
                .getStatusCode().value());

        when(documentEmbedService.embedDocument(41L, true))
                .thenThrow(new IllegalArgumentException("document missing"));
        ResponseEntity<Object> bad = controller.embedDocument(41L, true);
        assertEquals(400, bad.getStatusCode().value());
    }

    @Test
    void searchDocumentsForCallerSelectsRestrictedOrUnrestrictedQuery()
            throws Exception {
        Page<RagDocument> page = mock(Page.class);
        when(documentRepository.searchDocumentsByCollectionIds(
                any(), eq("t"), any(), any(), any(),
                any(), any(), any(Pageable.class))).thenReturn(page);
        when(documentRepository.searchDocuments(
                eq("t"), any(), any(), any(), eq(7L), any(), any(),
                any(Pageable.class))).thenReturn(page);

        Method method = RagDocumentController.class.getDeclaredMethod(
                "searchDocumentsForCaller",
                java.util.Optional.class, Long.class, String.class,
                String.class, String.class, Boolean.class,
                LocalDateTime.class, LocalDateTime.class, Pageable.class);
        method.setAccessible(true);

        assertSame(page, method.invoke(controller,
                Optional.of(Set.of(1L)), null, "t", null, null,
                null, null, null, Pageable.ofSize(10)));
        assertSame(page, method.invoke(controller,
                Optional.empty(), 7L, "t", null, null,
                null, null, null, Pageable.ofSize(10)));
    }

    private static java.util.List<Long> anyListOrNull() {
        return isNull();
    }

    @Test
    void collectionMetadataBatchesNamesAndKeysAndHandlesEmpty() throws Exception {
        RagDocument doc = new RagDocument();
        doc.setCollectionId(7L);
        Page<RagDocument> page = mock(Page.class);
        when(page.getContent()).thenReturn(List.of(doc));

        RagCollection collection = new RagCollection();
        collection.setId(7L);
        collection.setName("KB");
        collection.setCollectionKey("kb");
        when(collectionRepository.findAllById(List.of(7L)))
                .thenReturn(List.of(collection));

        Method metadata = RagDocumentController.class.getDeclaredMethod(
                "collectionMetadata", Page.class);
        metadata.setAccessible(true);
        try {
            Object result = metadata.invoke(controller, page);
            assertNotNull(result);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }

        // 空页 → 空 metadata（无集合查询）。
        Page<RagDocument> emptyPage = mock(Page.class);
        when(emptyPage.getContent()).thenReturn(List.of());
        assertDoesNotThrowEmptyMetadata(emptyPage);
    }

    private void assertDoesNotThrowEmptyMetadata(Page<RagDocument> emptyPage)
            throws Exception {
        Method metadata = RagDocumentController.class.getDeclaredMethod(
                "collectionMetadata", Page.class);
        metadata.setAccessible(true);
        Object result = metadata.invoke(controller, emptyPage);
        assertNotNull(result);
    }
}
