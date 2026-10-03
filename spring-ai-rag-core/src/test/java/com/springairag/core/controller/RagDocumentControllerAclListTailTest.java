package com.springairag.core.controller;

import com.springairag.api.dto.DocumentStatsResponse;
import com.springairag.core.config.EmbeddingProfile;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.entity.RagApiKey;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.filter.ApiKeyAuthFilter;
import com.springairag.core.repository.RagCollectionRepository;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.repository.RagEmbeddingRepository;
import com.springairag.core.service.BatchDocumentService;
import com.springairag.core.service.CollectionIdentityResolver;
import com.springairag.core.service.DocumentEmbedService;
import com.springairag.core.service.DocumentLifecycleService;
import com.springairag.core.service.DocumentVersionService;
import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.entity.RagApiKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RagDocumentController ACL 列举/统计/批量嵌入流长尾（Batch 697，
 * JaCoCo 驱动）：受限策略下的集合范围统计、无显式 collectionId 时
 * 按允许列表检索、批量嵌入 SSE 流的进度/完成与 IAE → sendError。
 */
class RagDocumentControllerAclListTailTest {

    private RagDocumentRepository documentRepository;
    private DocumentEmbedService documentEmbedService;
    private RagDocumentController controller;

    @BeforeEach
    void setUp() {
        documentRepository = mock(RagDocumentRepository.class);
        documentEmbedService = mock(DocumentEmbedService.class);
        EmbeddingProfileProvider profileProvider =
                mock(EmbeddingProfileProvider.class);
        when(profileProvider.getActiveProfile()).thenReturn(
                new EmbeddingProfile(
                        1L, "test-profile", "test", "test-model", "v1",
                        1024, "COSINE", "PROVIDER_DEFAULT", true));
        RagCollectionRepository collectionRepository =
                mock(RagCollectionRepository.class);
        controller = new RagDocumentController(
                documentRepository,
                mock(RagEmbeddingRepository.class),
                collectionRepository,
                documentEmbedService,
                mock(BatchDocumentService.class),
                mock(DocumentVersionService.class),
                profileProvider,
                new CollectionIdentityResolver(collectionRepository),
                null);
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    private void authenticateRestrictedKey(Long... ids) {
        RagApiKey key = new RagApiKey();
        key.setRole(ApiKeyRole.NORMAL);
        key.setAllowedCollectionIds(java.util.Arrays.stream(ids)
                .map(String::valueOf)
                .reduce((a, b) -> a + "," + b)
                .orElse(""));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(
                ApiKeyAuthFilter.AUTHENTICATED_API_KEY_ENTITY, key);
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(request));
    }

    @Test
    void documentStatsWithRestrictedPolicyUsesCollectionScopedCounts() {
        authenticateRestrictedKey(2L, 4L);
        when(documentRepository.countByProcessingStatusAndCollectionIds(
                List.of(2L, 4L)))
                .thenReturn(List.<Object[]>of(
                        new Object[]{"COMPLETED", 5L},
                        new Object[]{null, 1L}));

        DocumentStatsResponse response = controller.getDocumentStats()
                .getBody();

        assertNotNull(response);
        assertEquals(6L, response.total());
        assertEquals(5L, response.byStatus().get("COMPLETED"));
        assertEquals(1L, response.byStatus().get("UNKNOWN"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchWithRestrictedPolicyWithoutExplicitCollectionUsesAllowList() {
        authenticateRestrictedKey(2L, 4L);
        when(documentRepository.searchDocumentsByCollectionIds(
                anyList(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<RagDocument>(List.of()));

        controller.listDocuments(
                0, 10, null, null, null, null,
                null, null, null, null);

        org.mockito.Mockito.verify(documentRepository)
                .searchDocumentsByCollectionIds(
                        org.mockito.ArgumentMatchers.eq(List.of(2L, 4L)),
                        org.mockito.ArgumentMatchers.isNull(),
                        org.mockito.ArgumentMatchers.isNull(),
                        org.mockito.ArgumentMatchers.isNull(),
                        org.mockito.ArgumentMatchers.isNull(),
                        org.mockito.ArgumentMatchers.isNull(),
                        org.mockito.ArgumentMatchers.isNull(),
                        any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void batchEmbedStreamPublishesProgressAndDone() {
        authenticateRestrictedKey(2L, 4L);
        RagDocument document = new RagDocument();
        document.setId(1L);
        document.setCollectionId(2L);
        when(documentRepository.findAllById(anyList()))
                .thenReturn(List.of(document));
        doAnswer(invocation -> {
            Consumer<Object> callback = invocation.getArgument(1);
            callback.accept(Map.of("percent", 50));
            return Map.of("succeeded", 1, "failed", 0);
        }).when(documentEmbedService)
                .batchEmbedDocumentsWithProgress(anyList(), any());

        var emitter = controller.batchEmbedDocumentsStream(
                Map.of("ids", List.of(1L)));

        assertNotNull(emitter);
    }

    @Test
    @SuppressWarnings("unchecked")
    void batchEmbedStreamMapsIllegalArgumentToSseError() {
        authenticateRestrictedKey(2L, 4L);
        RagDocument document = new RagDocument();
        document.setId(1L);
        document.setCollectionId(2L);
        when(documentRepository.findAllById(anyList()))
                .thenReturn(List.of(document));
        when(documentEmbedService.batchEmbedDocumentsWithProgress(
                anyList(), any()))
                .thenThrow(new IllegalArgumentException("profile missing"));

        var emitter = controller.batchEmbedDocumentsStream(
                Map.of("ids", List.of(1L)));

        assertNotNull(emitter);
    }
}
