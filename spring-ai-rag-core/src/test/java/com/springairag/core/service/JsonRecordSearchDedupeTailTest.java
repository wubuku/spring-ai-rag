package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.JsonRecordUpsertRequest;
import com.springairag.api.dto.RetrievalConfig;
import com.springairag.api.dto.RetrievalResult;
import com.springairag.core.retrieval.HybridRetrieverService;
import com.springairag.core.retrieval.RetrievalFilters;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.retrieval.ReRankingService;
import com.springairag.core.retrieval.RetrievalOutcome;
import com.springairag.core.retrieval.RetrievalScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * JsonRecordService 检索去重与辅助长尾（Batch 534，JaCoCo 驱动）：
 * 非数字 documentId 跳过、唯一排名为空提前返回、仓库缺文档跳过、
 * safeError/requestCollectionKey/parseDocumentId 反射分支。
 */
class JsonRecordSearchDedupeTailTest {

    private HybridRetrieverService hybridRetrieverService;
    private RagDocumentRepository documentRepository;
    private JsonRecordService service;
    private CollectionIdentityResolver collectionIdentityResolver;

    @BeforeEach
    void setUp() {
        hybridRetrieverService = mock(HybridRetrieverService.class);
        documentRepository = mock(RagDocumentRepository.class);
        collectionIdentityResolver = mock(CollectionIdentityResolver.class);
        service = new JsonRecordService(
                documentRepository,
                mock(DocumentVersionService.class),
                mock(DocumentEmbedService.class),
                hybridRetrieverService,
                mock(ReRankingService.class),
                mock(com.springairag.core.config.EmbeddingProfileProvider.class),
                collectionIdentityResolver,
                new com.springairag.core.config.RagProperties(),
                new ObjectMapper().findAndRegisterModules(),
                mock(JdbcTemplate.class),
                mock(CollectionRetrievalScopeResolver.class),
                mock(PlatformTransactionManager.class));
    }

    private RetrievalResult result(String documentId) {
        RetrievalResult result = new RetrievalResult();
        result.setDocumentId(documentId);
        result.setChunkIndex(0);
        result.setChunkText("text");
        result.setScore(0.8);
        return result;
    }

    @Test
    void nonNumericDocumentIdsProduceEmptySearchResponse() {
        when(hybridRetrieverService.searchInScopeDetailed(
                anyString(), any(RetrievalScope.class), any(),
                anyInt(), any(RetrievalConfig.class),
                any(RetrievalFilters.class)))
                .thenReturn(RetrievalOutcome.ofResults(
                        List.of(result("abc"), result("  "))));
        when(hybridRetrieverService.searchInScopeDetailed(
                anyString(), any(), any(), anyInt(), any(), any()))
                .thenReturn(RetrievalOutcome.ofResults(
                        List.of(result("abc"))));

        var response = service.searchAuthorizedDetailed(
                "query", null, null,
                RetrievalScope.unscoped(),
                RetrievalConfig.builder().maxResults(10).useRerank(false).build())
                .response();

        assertTrue(response.results().isEmpty());
    }

    @Test
    void rankedIdsMissingFromRepositoryAreSkipped() {
        com.springairag.core.entity.RagDocument known =
                new com.springairag.core.entity.RagDocument();
        known.setId(42L);
        known.setDocumentType(com.springairag.core.entity.RagDocument.JSON_RECORD);
        known.setEnabled(true);
        known.setCollectionId(7L);
        when(documentRepository.findByIdInAndDocumentTypeAndEnabledTrue(
                anyList(), eq(com.springairag.core.entity.RagDocument.JSON_RECORD)))
                .thenReturn(List.of(known));
        when(hybridRetrieverService.searchInScopeDetailed(
                anyString(), any(RetrievalScope.class), any(),
                anyInt(), any(RetrievalConfig.class),
                any(RetrievalFilters.class)))
                .thenReturn(RetrievalOutcome.ofResults(
                        List.of(result("42"), result("43"))));
        when(collectionIdentityResolver.mapKeys(List.of(7L)))
                .thenReturn(Map.of(7L, "kb"));

        var response = service.searchAuthorizedDetailed(
                "query", null, null,
                RetrievalScope.unscoped(),
                RetrievalConfig.builder().maxResults(10).useRerank(false).build())
                .response();

        var detailed = service.searchAuthorizedDetailed(
                "query", null, null, RetrievalScope.unscoped(),
                RetrievalConfig.builder().maxResults(10).useRerank(false).build());
        System.out.println("DEBUG ranked=" + detailed.outcome().results().size()
                + " results=" + detailed.response().results().size());
        assertEquals(1, response.results().size());
    }

    @Test
    void blankQueryIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> service.searchAuthorizedDetailed(
                        "  ", null, null, null, null));
    }

    @Test
    void safeErrorFallsBackToSimpleNamesAndTruncates() throws Exception {
        var byRuntime = findMethod("safeError",
                RuntimeException.class);
        var byString = findMethod("safeError", String.class);

        assertEquals("IllegalStateException", byRuntime.invoke(service,
                new IllegalStateException((String) null)));
        assertEquals("boo", byRuntime.invoke(service,
                new IllegalStateException("boo")));

        Object truncated = byString.invoke(service, "e".repeat(600));
        assertEquals(503, ((String) truncated).length());
        assertTrue(((String) truncated).endsWith("..."));
    }

    @Test
    void requestCollectionKeyHandlesMissingIdentifiers() throws Exception {
        var method = findMethod("requestCollectionKey",
                JsonRecordUpsertRequest.class);

        assertNull(method.invoke(service, (Object) null));

        JsonRecordUpsertRequest neither = new JsonRecordUpsertRequest();
        assertNull(method.invoke(service, neither));
    }

    @Test
    void parseDocumentIdHandlesNullNonNumericAndNumeric() throws Exception {
        var method = findMethod("parseDocumentId", String.class);

        assertNull(method.invoke(service, (String) null));
        assertNull(method.invoke(service, "not-a-number"));
        assertEquals(42L, method.invoke(service, "42"));
    }

    private java.lang.reflect.Method findMethod(String name,
                                                Class<?>... params)
            throws Exception {
        var method = JsonRecordService.class
                .getDeclaredMethod(name, params);
        method.setAccessible(true);
        return method;
    }
}
