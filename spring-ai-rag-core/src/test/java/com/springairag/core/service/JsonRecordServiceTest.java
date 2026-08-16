package com.springairag.core.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.CollectionImportRequest;
import com.springairag.api.dto.JsonRecordSearchRequest;
import com.springairag.api.dto.JsonRecordSearchResponse;
import com.springairag.api.dto.JsonRecordUpsertRequest;
import com.springairag.api.dto.JsonRecordUpsertResponse;
import com.springairag.api.dto.RetrievalConfig;
import com.springairag.api.dto.RetrievalResult;
import com.springairag.api.enums.CollectionScopeMode;
import com.springairag.core.config.EmbeddingProfile;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.entity.RagDocumentVersion;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.retrieval.HybridRetrieverService;
import com.springairag.core.retrieval.ReRankingService;
import com.springairag.core.retrieval.RetrievalScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JsonRecordServiceTest {

    private static final EmbeddingProfile PROFILE = new EmbeddingProfile(
            7L, "test-json-profile", "test", "test-model", "v1",
            1024, "COSINE", "PROVIDER_DEFAULT", true);

    @Mock
    private RagDocumentRepository documentRepository;
    @Mock
    private DocumentVersionService documentVersionService;
    @Mock
    private DocumentEmbedService documentEmbedService;
    @Mock
    private HybridRetrieverService hybridRetrieverService;
    @Mock
    private ReRankingService reRankingService;
    @Mock
    private EmbeddingProfileProvider embeddingProfileProvider;
    @Mock
    private CollectionIdentityResolver collectionIdentityResolver;
    @Mock
    private CollectionRetrievalScopeResolver retrievalScopeResolver;
    @Mock
    private JdbcTemplate jdbcTemplate;
    @Mock
    private PlatformTransactionManager transactionManager;
    @Mock
    private TransactionStatus transactionStatus;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private JsonRecordService service;

    @BeforeEach
    void setUp() {
        RagProperties properties = new RagProperties();
        lenient().when(embeddingProfileProvider.getActiveProfile()).thenReturn(PROFILE);
        lenient().when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
        lenient().doAnswer(invocation -> {
            RagDocument document = invocation.getArgument(0);
            if (document.getId() == null) {
                document.setId(41L);
            }
            return document;
        }).when(documentRepository).saveAndFlush(any(RagDocument.class));
        lenient().when(jdbcTemplate.execute(any(ConnectionCallback.class))).thenReturn(null);
        lenient().when(collectionIdentityResolver.mapKeys(any()))
                .thenReturn(Map.of(
                        2L, "collection-2",
                        10L, "collection-10"));

        service = new JsonRecordService(
                documentRepository,
                documentVersionService,
                documentEmbedService,
                hybridRetrieverService,
                reRankingService,
                embeddingProfileProvider,
                collectionIdentityResolver,
                properties,
                objectMapper,
                jdbcTemplate,
                transactionManager);
    }

    @Test
    void keyOnlyUpsertResolvesToInternalIdAndReturnsStableKey() throws Exception {
        when(collectionIdentityResolver.resolveActiveIds(
                null, List.of("customer-42:records:v1")))
                .thenReturn(List.of(10L));
        when(documentRepository.findByCollectionIdAndDocumentTypeAndExternalId(
                10L, RagDocument.JSON_RECORD, "customer-key"))
                .thenReturn(Optional.empty());
        when(documentVersionService.forceRecordVersion(any(), eq("CREATE"), any()))
                .thenAnswer(invocation -> version(1));

        JsonRecordUpsertRequest request = request(
                null, "customer-key", "Customer record.",
                objectMapper.readTree("{\"name\":\"one\"}"), false);
        request.setCollectionKey("customer-42:records:v1");

        JsonRecordUpsertResponse response = service.upsert(request);

        assertEquals(10L, response.collectionId());
        assertEquals("collection-10", response.collectionKey());
        assertEquals(10L, request.getCollectionId());
        verify(collectionIdentityResolver).requireActiveForShare(10L);
    }

    @Test
    void upsertRejectsMismatchedCollectionIdAndKey() throws Exception {
        when(collectionIdentityResolver.resolveActiveIds(
                null, List.of("customer-42:records:v1")))
                .thenReturn(List.of(10L));
        JsonRecordUpsertRequest request = request(
                11L, "customer-key", "Customer record.",
                objectMapper.readTree("{\"name\":\"one\"}"), false);
        request.setCollectionKey("customer-42:records:v1");

        assertThrows(IllegalArgumentException.class, () -> service.upsert(request));
        verify(documentRepository, never()).saveAndFlush(any());
    }

    @Test
    void createPersistsJsonPayloadAndDoesNotEmbedWhenDisabled() throws Exception {
        when(documentRepository.findByCollectionIdAndDocumentTypeAndExternalId(
                10L, RagDocument.JSON_RECORD, "customer-1"))
                .thenReturn(Optional.empty());
        when(documentVersionService.forceRecordVersion(any(), eq("CREATE"), any()))
                .thenAnswer(invocation -> version(1));

        JsonRecordUpsertResponse response = service.upsert(
                request(10L, "customer-1", "Customer one is in Beijing.",
                        objectMapper.readTree("{\"name\":\"one\"}"), false));

        assertEquals("CREATED", response.action());
        assertTrue(response.contentChanged());
        assertTrue(response.payloadChanged());
        assertEquals(1, response.versionNumber());
        verify(documentEmbedService, never()).embedDocument(any(Long.class), eq(false));
    }

    @Test
    void payloadOnlyUpdateCreatesVersionWithoutCallingEmbedding() throws Exception {
        RagDocument document = document(41L, 3L, "customer-1",
                "Customer one is in Beijing.", "{\"name\":\"one\"}");
        when(documentRepository.findByCollectionIdAndDocumentTypeAndExternalId(
                10L, RagDocument.JSON_RECORD, "customer-1"))
                .thenReturn(Optional.of(document));
        when(documentVersionService.forceRecordVersion(any(), eq("UPDATE"), any()))
                .thenAnswer(invocation -> version(4));
        when(documentEmbedService.hasFreshEmbedding(document)).thenReturn(true);

        JsonRecordUpsertResponse response = service.upsert(
                request(10L, "customer-1", "Customer one is in Beijing.",
                        objectMapper.readTree("{\"name\":\"updated\"}"), true));

        assertEquals("UPDATED", response.action());
        assertFalse(response.contentChanged());
        assertTrue(response.payloadChanged());
        assertEquals(4, response.versionNumber());
        verify(documentEmbedService, never()).embedDocument(any(Long.class), eq(false));
    }

    @Test
    void exactReplayIsUnchangedAndDoesNotCreateVersion() throws Exception {
        RagDocument document = document(41L, 3L, "customer-1",
                "Customer one is in Beijing.", "{\"name\":\"one\"}");
        when(documentRepository.findByCollectionIdAndDocumentTypeAndExternalId(
                10L, RagDocument.JSON_RECORD, "customer-1"))
                .thenReturn(Optional.of(document));
        when(documentVersionService.getLatestVersion(41L))
                .thenReturn(Optional.of(version(3)));
        when(documentEmbedService.hasFreshEmbedding(document)).thenReturn(true);

        JsonRecordUpsertResponse response = service.upsert(
                request(10L, "customer-1", "Customer one is in Beijing.",
                        objectMapper.readTree("{\"name\":\"one\"}"), true));

        assertEquals("UNCHANGED", response.action());
        assertEquals(3, response.versionNumber());
        verify(documentRepository, never()).saveAndFlush(any());
        verify(documentVersionService, never()).forceRecordVersion(any(), any(), any());
        verify(documentEmbedService, never()).embedDocument(any(Long.class), eq(false));
    }

    @Test
    void retrievalTextUpdateCallsEmbedding() throws Exception {
        RagDocument document = document(41L, 3L, "customer-1",
                "Customer one is in Beijing.", "{\"name\":\"one\"}");
        when(documentRepository.findByCollectionIdAndDocumentTypeAndExternalId(
                10L, RagDocument.JSON_RECORD, "customer-1"))
                .thenReturn(Optional.of(document));
        when(documentVersionService.forceRecordVersion(any(), eq("UPDATE"), any()))
                .thenAnswer(invocation -> version(4));
        when(documentEmbedService.embedDocument(41L, false))
                .thenReturn(Map.of("status", "COMPLETED",
                        "embeddingProfileKey", PROFILE.profileKey()));

        JsonRecordUpsertResponse response = service.upsert(
                request(10L, "customer-1", "Customer one moved to Shanghai.",
                        objectMapper.readTree("{\"name\":\"one\"}"), true));

        assertEquals("UPDATED", response.action());
        assertTrue(response.contentChanged());
        assertEquals("COMPLETED", response.embeddingStatus());
        verify(documentEmbedService).embedDocument(41L, false);
    }

    @Test
    void batchKeepsValidItemsWhenOneItemValidationFails() throws Exception {
        when(documentRepository.findByCollectionIdAndDocumentTypeAndExternalId(
                10L, RagDocument.JSON_RECORD, "valid-1"))
                .thenReturn(Optional.empty());
        when(documentVersionService.forceRecordVersion(any(), eq("CREATE"), any()))
                .thenAnswer(invocation -> version(1));

        JsonRecordUpsertRequest invalid = request(
                10L, "invalid-1", " ",
                objectMapper.readTree("{\"value\":1}"), false);
        JsonRecordUpsertRequest valid = request(
                10L, "valid-1", "A valid retrieval description.",
                objectMapper.readTree("{\"value\":2}"), false);

        var response = service.batchUpsert(List.of(invalid, valid));

        assertEquals(2, response.summary().total());
        assertEquals(1, response.summary().created());
        assertEquals(1, response.summary().persistenceFailed());
        assertEquals("FAILED", response.results().get(0).action());
        assertEquals("CREATED", response.results().get(1).action());
        verify(documentRepository).saveAndFlush(any(RagDocument.class));
    }

    @Test
    void embeddingErrorIsMaskedAndBounded() throws Exception {
        when(documentRepository.findByCollectionIdAndDocumentTypeAndExternalId(
                10L, RagDocument.JSON_RECORD, "error-1"))
                .thenReturn(Optional.empty());
        when(documentVersionService.forceRecordVersion(any(), eq("CREATE"), any()))
                .thenAnswer(invocation -> version(1));
        when(documentEmbedService.embedDocument(41L, false))
                .thenReturn(Map.of(
                        "status", "FAILED",
                        "error", "provider failed apiKey=secret-value"));

        JsonRecordUpsertResponse response = service.upsert(
                request(10L, "error-1", "A retrieval description.",
                        objectMapper.readTree("{\"value\":1}"), true));

        assertEquals("FAILED", response.embeddingStatus());
        assertTrue(response.error().contains("***REDACTED***"));
        assertFalse(response.error().contains("secret-value"));
        assertTrue(response.error().length() <= 500);
    }

    @Test
    void searchScopesCandidatesAndEnrichesInRankingOrder() throws Exception {
        JsonNode payload = objectMapper.readTree("{\"id\":1}");
        RagDocument document = document(1L, 2L, "one",
                "One record about Spring AI.", payload.toString());
        document.setCollectionId(10L);
        document.setJsonbPayload(payload);
        RetrievalResult rankedOne = retrieval("1", 0.9);
        RetrievalResult duplicate = retrieval("1", 0.8);
        when(documentRepository.findEnabledIdsByCollectionIdsAndDocumentType(
                List.of(10L), RagDocument.JSON_RECORD)).thenReturn(List.of(1L));
        when(hybridRetrieverService.search(
                eq("spring"), eq(List.of(1L)), eq((List<Long>) null),
                eq(5), any(RetrievalConfig.class)))
                .thenReturn(List.of(rankedOne, duplicate));
        when(documentRepository.findByIdInAndDocumentTypeAndEnabledTrue(
                List.of(1L), RagDocument.JSON_RECORD))
                .thenReturn(List.of(document));

        JsonRecordSearchRequest request = new JsonRecordSearchRequest();
        request.setQuery("spring");
        request.setCollectionIds(List.of(10L));
        RetrievalConfig config = RetrievalConfig.builder()
                .maxResults(5)
                .useRerank(false)
                .build();
        request.setConfig(config);

        JsonRecordSearchResponse response = service.search(request);

        assertEquals(1, response.results().size());
        assertEquals(1L, response.results().getFirst().documentId());
        assertEquals(payload, response.results().getFirst().jsonbPayload());
        verify(reRankingService, never()).rerank(any(), anyList(), any(Integer.class));
    }

    @Test
    void productionSearchUsesDirectJsonScopeWithoutPreloadingDocumentIds()
            throws Exception {
        JsonRecordService productionService = new JsonRecordService(
                documentRepository,
                documentVersionService,
                documentEmbedService,
                hybridRetrieverService,
                reRankingService,
                embeddingProfileProvider,
                collectionIdentityResolver,
                new RagProperties(),
                objectMapper,
                jdbcTemplate,
                retrievalScopeResolver,
                transactionManager);
        RetrievalScope scope = RetrievalScope.selectedCollections(
                List.of(10L), null, RagDocument.JSON_RECORD);
        when(retrievalScopeResolver.resolve(
                CollectionScopeMode.SELECTED_COLLECTIONS,
                null, List.of("records:v1"),
                null, RagDocument.JSON_RECORD, null))
                .thenReturn(scope);
        when(hybridRetrieverService.searchInScope(
                eq("spring"), same(scope), eq((List<Long>) null),
                eq(5), any(RetrievalConfig.class)))
                .thenReturn(List.of(retrieval("1", 0.9)));

        JsonNode payload = objectMapper.readTree("{\"id\":1}");
        RagDocument document = document(
                1L, 1L, "one", "Spring record.", payload.toString());
        when(documentRepository.findByIdInAndDocumentTypeAndEnabledTrue(
                List.of(1L), RagDocument.JSON_RECORD))
                .thenReturn(List.of(document));

        JsonRecordSearchRequest request = new JsonRecordSearchRequest();
        request.setQuery("spring");
        request.setCollectionKeys(List.of("records:v1"));
        request.setConfig(RetrievalConfig.builder()
                .maxResults(5)
                .useRerank(false)
                .build());

        JsonRecordSearchResponse response = productionService.search(request);

        assertEquals(1, response.results().size());
        verify(hybridRetrieverService).searchInScope(
                eq("spring"), same(scope), eq((List<Long>) null),
                eq(5), any(RetrievalConfig.class));
        verify(documentRepository, never())
                .findEnabledIdsByCollectionIdsAndDocumentType(
                        anyList(), any());
    }

    @Test
    void searchRequiresExplicitCollectionScope() {
        JsonRecordSearchRequest request = new JsonRecordSearchRequest();
        request.setQuery("spring");
        request.setCollectionIds(List.of());

        assertThrows(IllegalArgumentException.class, () -> service.search(request));
        verifyNoCandidateQuery();
    }

    @Test
    void searchAcceptsStableCollectionKeys() {
        when(collectionIdentityResolver.resolveActiveIds(
                null, List.of("customer-42:records:v1")))
                .thenReturn(List.of(10L));
        when(documentRepository.findEnabledIdsByCollectionIdsAndDocumentType(
                List.of(10L), RagDocument.JSON_RECORD)).thenReturn(List.of());

        JsonRecordSearchRequest request = new JsonRecordSearchRequest();
        request.setQuery("spring");
        request.setCollectionKeys(List.of("customer-42:records:v1"));

        JsonRecordSearchResponse response = service.search(request);

        assertTrue(response.results().isEmpty());
        assertEquals(List.of(10L), request.getCollectionIds());
    }

    @Test
    void searchRejectsExplicitEmptyStableKeyScope() {
        JsonRecordSearchRequest request = new JsonRecordSearchRequest();
        request.setQuery("spring");
        request.setCollectionKeys(List.of());

        assertThrows(IllegalArgumentException.class, () -> service.search(request));
        verifyNoCandidateQuery();
    }

    @Test
    void importRecordPreservesExportFieldsAndCreatesVersion() throws Exception {
        CollectionImportRequest.ImportedDocument imported =
                new CollectionImportRequest.ImportedDocument();
        imported.setTitle("Imported");
        imported.setContent("Imported retrieval description");
        imported.setDocumentType(RagDocument.JSON_RECORD);
        imported.setExternalId("imported-1");
        imported.setJsonbPayload(objectMapper.readTree("{\"value\":42}"));
        imported.setOriginalFilename("source.json");
        imported.setEnabled(false);
        when(documentRepository.findByCollectionIdAndDocumentTypeAndExternalId(
                10L, RagDocument.JSON_RECORD, "imported-1"))
                .thenReturn(Optional.empty());
        when(documentVersionService.forceRecordVersion(any(), eq("CREATE"), any()))
                .thenAnswer(invocation -> version(1));

        JsonRecordUpsertResponse result = service.importRecord(10L, imported);

        assertEquals("CREATED", result.action());
        verify(documentRepository).saveAndFlush(any(RagDocument.class));
        verify(documentVersionService).forceRecordVersion(any(), eq("CREATE"), any());
    }

    private JsonRecordUpsertRequest request(
            Long collectionId, String externalId, String retrievalText,
            JsonNode payload, boolean embed) {
        JsonRecordUpsertRequest request = new JsonRecordUpsertRequest();
        request.setCollectionId(collectionId);
        request.setExternalId(externalId);
        request.setTitle("Record " + externalId);
        request.setRetrievalText(retrievalText);
        request.setJsonbPayload(payload);
        request.setEmbed(embed);
        return request;
    }

    private RagDocument document(
            Long id, Long version, String externalId,
            String content, String payloadJson) throws Exception {
        RagDocument document = new RagDocument();
        document.setId(id);
        document.setVersion(version);
        document.setCollectionId(10L);
        document.setDocumentType(RagDocument.JSON_RECORD);
        document.setExternalId(externalId);
        document.setTitle("Record " + externalId);
        document.setContent(content);
        document.setContentHash(com.springairag.core.util.DigestUtils.sha256(content));
        document.setJsonbPayload(objectMapper.readTree(payloadJson));
        document.setEnabled(true);
        document.setProcessingStatus("COMPLETED");
        return document;
    }

    private RagDocumentVersion version(int number) {
        RagDocumentVersion version = new RagDocumentVersion();
        version.setVersionNumber(number);
        return version;
    }

    private RetrievalResult retrieval(String documentId, double score) {
        RetrievalResult result = new RetrievalResult();
        result.setDocumentId(documentId);
        result.setChunkText("matched");
        result.setScore(score);
        return result;
    }

    private void verifyNoCandidateQuery() {
        verify(documentRepository, never()).findEnabledIdsByCollectionIdsAndDocumentType(anyList(), any());
    }
}
