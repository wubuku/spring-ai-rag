package com.springairag.core.controller;

import com.springairag.api.dto.RetrievalResult;
import com.springairag.api.enums.EmbeddingPolicy;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.RagCollectionRepository;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.repository.RagEmbeddingRepository;
import com.springairag.core.service.BatchDocumentService;
import com.springairag.core.service.CollectionIdentityResolver;
import com.springairag.core.service.DocumentDerivationDescriptorProvider;
import com.springairag.core.service.DocumentEmbedService;
import com.springairag.core.service.DocumentVersionService;
import com.springairag.core.embeddingjob.EmbeddingDispatchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.Generation;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RagDocumentController 嵌入守卫（Batch 382）：reembed 结果的
 * ASYNC/SYNC/异常三路径、无描述符提供者与有描述符提供者的计数
 * 与查找四分支。
 */
class RagDocumentControllerEmbeddingGuardsTest {

    private RagDocumentRepository documentRepository;
    private DocumentEmbedService documentEmbedService;
    private EmbeddingDispatchService dispatchService;
    private EmbeddingProfileProvider profileProvider;
    private RagDocumentController controller;
    private Method buildReembedResult;
    private Method countWithoutEmbedding;
    private Method findWithoutEmbedding;

    @BeforeEach
    void setUp() throws Exception {
        documentRepository = mock(RagDocumentRepository.class);
        documentEmbedService = mock(DocumentEmbedService.class);
        dispatchService = mock(EmbeddingDispatchService.class);
        profileProvider = mock(EmbeddingProfileProvider.class);
        controller = new RagDocumentController(
                documentRepository,
                mock(RagEmbeddingRepository.class),
                mock(RagCollectionRepository.class),
                documentEmbedService,
                mock(BatchDocumentService.class),
                mock(DocumentVersionService.class),
                profileProvider,
                mock(CollectionIdentityResolver.class),
                null);
        controller.setDispatchService(dispatchService);

        buildReembedResult = RagDocumentController.class
                .getDeclaredMethod("buildReembedResult",
                        RagDocument.class, boolean.class, EmbeddingPolicy.class);
        buildReembedResult.setAccessible(true);
        countWithoutEmbedding = RagDocumentController.class
                .getDeclaredMethod("countDocumentsWithoutCurrentEmbedding",
                        java.util.Optional.class, long.class);
        countWithoutEmbedding.setAccessible(true);
        findWithoutEmbedding = RagDocumentController.class
                .getDeclaredMethod("findDocumentsWithoutCurrentEmbedding",
                        java.util.Optional.class, long.class);
        findWithoutEmbedding.setAccessible(true);
    }

    private RagDocument document() {
        RagDocument doc = new RagDocument();
        doc.setId(41L);
        doc.setTitle("Doc");
        return doc;
    }

    private EmbeddingDispatchService.Result dispatchResult() {
        return new EmbeddingDispatchService.Result(
                com.springairag.api.enums.EmbeddingAction.ASYNC_QUEUED,
                "QUEUED", "profile", UUID.randomUUID(), UUID.randomUUID(),
                null);
    }

    @Test
    void reembedWithAsyncPolicyUsesDispatchStatus() throws Exception {
        when(dispatchService.enqueueInCurrentTransaction(
                any(RagDocument.class), anyBoolean(), anyBoolean(), anyString()))
                .thenReturn(dispatchResult());

        Object result = buildReembedResult.invoke(controller,
                document(), false, EmbeddingPolicy.ASYNC);

        assertNotNull(result);
        assertTrue(result.toString().contains("QUEUED"));
    }

    @Test
    void reembedWithSyncPolicyAndErrorFallback() throws Exception {
        when(documentEmbedService.embedDocument(41L, false))
                .thenReturn(Map.of("status", "COMPLETED",
                        "chunksCreated", 3, "message", "done"));

        Object ok = buildReembedResult.invoke(controller,
                document(), false, EmbeddingPolicy.SYNC);
        assertTrue(ok.toString().contains("COMPLETED"));

        // 单文档失败：best-effort 记为 error，不影响批次其它文档。
        when(documentEmbedService.embedDocument(41L, false))
                .thenThrow(new IllegalStateException("embed down"));
        Object failed = buildReembedResult.invoke(controller,
                document(), false, EmbeddingPolicy.SYNC);
        assertTrue(failed.toString().contains("error"));
    }

    @Test
    void countAndFindWithoutDescriptorProviderHitUnscopedQueries()
            throws Exception {
        when(documentRepository.countDocumentsWithoutEmbeddings(9L))
                .thenReturn(5L);
        when(documentRepository.countDocumentsWithoutEmbeddingsByCollectionIds(
                anyList(), eq(9L))).thenReturn(2L);
        when(documentRepository.findDocumentsWithoutEmbeddings(9L))
                .thenReturn(List.of(document()));
        when(documentRepository.findDocumentsWithoutEmbeddingsByCollectionIds(
                anyList(), eq(9L))).thenReturn(List.of(document()));

        assertEquals(5L, countWithoutEmbedding.invoke(controller,
                Optional.empty(), 9L));
        assertEquals(2L, countWithoutEmbedding.invoke(controller,
                Optional.of(Set.of(1L)), 9L));
        assertEquals(1, ((List<?>) findWithoutEmbedding.invoke(controller,
                Optional.empty(), 9L)).size());
        assertEquals(1, ((List<?>) findWithoutEmbedding.invoke(controller,
                Optional.of(Set.of(1L)), 9L)).size());
    }

    @Test
    void countAndFindWithDescriptorProviderHitCurrentEmbeddingQueries()
            throws Exception {
        DocumentDerivationDescriptorProvider provider =
                mock(DocumentDerivationDescriptorProvider.class);
        when(provider.textDescriptor()).thenReturn(
                new DocumentDerivationDescriptorProvider.Descriptor(
                        "TEXT", "text-v1"));
        when(provider.jsonRecordDescriptor()).thenReturn(
                new DocumentDerivationDescriptorProvider.Descriptor(
                        "JSON_RECORD", "json-v1"));
        controller.setDerivationDescriptorProvider(provider);

        when(documentRepository.countDocumentsWithoutCurrentEmbeddings(
                9L, "text-v1", "json-v1")).thenReturn(3L);
        when(documentRepository.countDocumentsWithoutCurrentEmbeddingsByCollectionIds(
                anyList(), eq(9L), eq("text-v1"), eq("json-v1"))).thenReturn(4L);
        when(documentRepository.findDocumentsWithoutCurrentEmbeddings(
                9L, "text-v1", "json-v1")).thenReturn(List.of(document()));
        when(documentRepository.findDocumentsWithoutCurrentEmbeddingsByCollectionIds(
                anyList(), eq(9L), eq("text-v1"), eq("json-v1")))
                .thenReturn(List.of(document()));

        assertEquals(3L, countWithoutEmbedding.invoke(controller,
                Optional.empty(), 9L));
        assertEquals(4L, countWithoutEmbedding.invoke(controller,
                Optional.of(Set.of(1L)), 9L));
        assertEquals(1, ((List<?>) findWithoutEmbedding.invoke(controller,
                Optional.empty(), 9L)).size());
        assertEquals(1, ((List<?>) findWithoutEmbedding.invoke(controller,
                Optional.of(Set.of(1L)), 9L)).size());
    }
}
