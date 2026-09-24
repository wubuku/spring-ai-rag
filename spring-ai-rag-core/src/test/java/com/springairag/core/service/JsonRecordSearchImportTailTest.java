package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.JsonRecordSearchRequest;
import com.springairag.api.dto.JsonRecordUpsertRequest;
import com.springairag.api.dto.RetrievalConfig;
import com.springairag.api.enums.EmbeddingPolicy;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.exception.DocumentNotFoundException;
import com.springairag.core.retrieval.HybridRetrieverService;
import com.springairag.core.retrieval.RetrievalOutcome;
import com.springairag.core.retrieval.RetrievalScope;
import com.springairag.core.retrieval.ReRankingService;
import com.springairag.core.repository.RagDocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import com.springairag.core.retrieval.RetrievalFilters;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * JSON 记录检索与导入守卫长尾（Batch 622，JaCoCo 驱动）：search 对
 * 缺失集合作用域的拒绝；jsonRecordScope 对 null/matchNone scope 的
 * 收敛与空结果投影；getDetail 对非 JSON 记录与 null lifecycle 的
 * 处理；importRecord 对 null 项与超长 originalFilename 的拒绝；
 * upsert 元数据单独变更不重置内容哈希。
 */
class JsonRecordSearchImportTailTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RagDocumentRepository documentRepository;
    private HybridRetrieverService hybridRetrieverService;
    private DocumentEmbedService documentEmbedService;
    private EmbeddingProfileProvider embeddingProfileProvider;
    private CollectionIdentityResolver resolver;
    private ExternalAddressRetirementService addressRetirementService;
    private RagProperties properties;
    private JsonRecordService service;

    @BeforeEach
    void setUp() {
        documentRepository = mock(RagDocumentRepository.class);
        hybridRetrieverService = mock(HybridRetrieverService.class);
        documentEmbedService = mock(DocumentEmbedService.class);
        embeddingProfileProvider = mock(EmbeddingProfileProvider.class);
        resolver = mock(CollectionIdentityResolver.class);
        addressRetirementService = mock(ExternalAddressRetirementService.class);
        properties = new RagProperties();

        lenient().when(embeddingProfileProvider.getActiveProfile())
                .thenReturn(new com.springairag.core.config.EmbeddingProfile(
                        9L, "profile", "test", "model", "v1",
                        1024, "COSINE", "NONE", true));
        lenient().when(resolver.beginActiveWrite(7L))
                .thenReturn(new CollectionIdentityResolver.ActiveCollectionToken(7L, 0L));
        lenient().when(documentRepository.saveAndFlush(any(RagDocument.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(hybridRetrieverService.searchInScopeDetailed(
                anyString(), any(RetrievalScope.class), any(), anyInt(),
                any(com.springairag.api.dto.RetrievalConfig.class), any()))
                .thenReturn(RetrievalOutcome.ofResults(List.of()));

        service = new JsonRecordService(
                documentRepository,
                mock(DocumentVersionService.class),
                documentEmbedService,
                hybridRetrieverService,
                mock(ReRankingService.class),
                embeddingProfileProvider,
                resolver,
                properties,
                MAPPER,
                mock(JdbcTemplate.class),
                null,
                null);
        service.setAddressRetirementService(addressRetirementService);
    }

    private RagDocument jsonRecordDoc() {
        RagDocument value = new RagDocument();
        value.setId(41L);
        value.setCollectionId(7L);
        value.setExternalId("rec-1");
        value.setDocumentType(RagDocument.JSON_RECORD);
        value.setTitle("Record");
        value.setContent("text");
        value.setContentHash(
                com.springairag.core.util.DigestUtils.sha256("text"));
        value.setSourceRevision("rev-1");
        value.setEnabled(Boolean.TRUE);
        try {
            value.setJsonbPayload(MAPPER.readTree("{\"k\":1}"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return value;
    }

    private JsonRecordSearchRequest searchRequest() {
        JsonRecordSearchRequest request = new JsonRecordSearchRequest();
        request.setQuery("q");
        request.setCollectionIds(List.of(7L));
        return request;
    }

    @Test
    void searchRejectsRequestWithoutCollectionScope() {
        JsonRecordSearchRequest request = new JsonRecordSearchRequest();
        request.setQuery("q");

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, () -> service.search(request));
        assertEquals("collectionKeys or collectionIds must be provided",
                error.getMessage());
    }

    @Test
    void searchAuthorizedWithNullScopeMatchesNothing() {
        var response = service.searchAuthorized(
                "q", (com.fasterxml.jackson.databind.JsonNode) null,
                null, null);

        assertTrue(response.results().isEmpty());
    }

    @Test
    void searchAuthorizedWithMatchNoneScopeMatchesNothing() {
        var response = service.searchAuthorized(
                "q", (com.fasterxml.jackson.databind.JsonNode) null,
                RetrievalScope.noMatches(), null);

        assertTrue(response.results().isEmpty());
    }

    @Test
    void searchAuthorizedProjectsResultsAndCollectionKeys() throws Exception {
        RetrievalScope scope = RetrievalScope.selectedCollections(
                List.of(7L), null, RagDocument.JSON_RECORD);
        RagDocument doc = jsonRecordDoc();
        when(hybridRetrieverService.searchInScopeDetailed(
                eq("q"), any(RetrievalScope.class), any(), anyInt(),
                any(com.springairag.api.dto.RetrievalConfig.class), any()))
                .thenReturn(RetrievalOutcome.ofResults(List.of(
                        result("41", 0.9))));
        when(documentRepository.findByIdInAndDocumentTypeAndEnabledTrue(
                anyList(), eq(RagDocument.JSON_RECORD)))
                .thenReturn(List.of(doc));
        when(resolver.mapKeys(List.of(7L))).thenReturn(Map.of(7L, "kb-key"));

        RetrievalConfig noRerank = RetrievalConfig.builder()
                .maxResults(10)
                .useRerank(false)
                .build();
        var response = service.searchAuthorized(
                "q", RetrievalFilters.none(), null, scope, noRerank);

        assertEquals(1, response.results().size());
        assertEquals(41L, response.results().getFirst().documentId());
        assertEquals("rec-1", response.results().getFirst().externalId());
        assertEquals("kb-key", response.results().getFirst().collectionKey());
    }

    private com.springairag.api.dto.RetrievalResult result(
            String documentId, double score) {
        com.springairag.api.dto.RetrievalResult result =
                new com.springairag.api.dto.RetrievalResult();
        result.setDocumentId(documentId);
        result.setScore(score);
        return result;
    }

    @Test
    void searchAuthorizedFiltersDocsOutsideSelectedCollections() throws Exception {
        RetrievalScope scope = RetrievalScope.selectedCollections(
                List.of(7L), null, RagDocument.JSON_RECORD);
        RagDocument doc = jsonRecordDoc();
        doc.setCollectionId(null);
        when(hybridRetrieverService.searchInScopeDetailed(
                eq("q"), any(RetrievalScope.class), any(), anyInt(),
                any(com.springairag.api.dto.RetrievalConfig.class), any()))
                .thenReturn(RetrievalOutcome.ofResults(List.of(
                        result("41", 0.9))));
        when(documentRepository.findByIdInAndDocumentTypeAndEnabledTrue(
                anyList(), eq(RagDocument.JSON_RECORD)))
                .thenReturn(List.of(doc));

        var response = service.searchAuthorized(
                "q", RetrievalFilters.none(), null, scope, null);

        assertTrue(response.results().isEmpty());
    }

    @Test
    void getDetailProjectsNullLifecycleWithoutLifecycleService() {
        RagDocument doc = jsonRecordDoc();
        when(documentRepository.findById(41L)).thenReturn(Optional.of(doc));

        var detail = service.getDetail(41L);

        assertEquals("Record", detail.title());
        assertNull(detail.lifecycle());
    }

    private static void assertNull(Object value) {
        org.junit.jupiter.api.Assertions.assertNull(value);
    }

    @Test
    void getDetailRejectsNonJsonRecordDocuments() {
        RagDocument plain = jsonRecordDoc();
        plain.setDocumentType("text");
        when(documentRepository.findById(41L)).thenReturn(Optional.of(plain));

        assertThrows(DocumentNotFoundException.class,
                () -> service.getDetail(41L));
    }

    @Test
    void importRecordRejectsNullItemAndOverlongOriginalFilename() {
        assertThrows(IllegalArgumentException.class,
                () -> service.importRecord(7L, null));

        var imported = new com.springairag.api.dto.CollectionImportRequest.ImportedDocument();
        imported.setExternalId("rec-1");
        imported.setTitle("T");
        imported.setContent("C");
        imported.setOriginalFilename("x".repeat(256));

        assertThrows(IllegalArgumentException.class,
                () -> service.importRecord(7L, imported));
    }

    private JsonRecordUpsertRequest request() {
        JsonRecordUpsertRequest request = new JsonRecordUpsertRequest();
        request.setCollectionId(7L);
        request.setExternalId("rec-1");
        request.setTitle("Record");
        request.setRetrievalText("text");
        request.setSourceRevision("rev-1");
        try {
            request.setJsonbPayload(MAPPER.readTree("{\"k\":1}"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return request;
    }

    @Test
    void upsertMetadataOnlyChangeKeepsContentHash() {
        RagDocument doc = jsonRecordDoc();
        when(documentRepository.findByCollectionIdAndDocumentTypeAndExternalId(
                eq(7L), eq(RagDocument.JSON_RECORD), eq("rec-1")))
                .thenReturn(Optional.of(doc));
        JsonRecordUpsertRequest request = request();
        request.setTitle("New Title");

        var response = service.upsert(request);

        assertEquals("UPDATED", response.action());
        assertFalse(response.contentChanged());
        assertEquals("New Title", doc.getTitle());
        assertEquals(
                com.springairag.core.util.DigestUtils.sha256("text"),
                doc.getContentHash());
    }

    @Test
    void searchAuthorizedRejectsBlankAndOverlongQueries() {
        assertThrows(IllegalArgumentException.class,
                () -> service.searchAuthorized(
                        "  ", null, null, RetrievalScope.unscoped(), null));
        String longQuery = "q".repeat(10_001);
        assertThrows(IllegalArgumentException.class,
                () -> service.searchAuthorized(
                        longQuery, null, null, RetrievalScope.unscoped(), null));
    }
}
