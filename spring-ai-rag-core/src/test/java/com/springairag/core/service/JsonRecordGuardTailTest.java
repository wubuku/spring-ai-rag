package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.JsonRecordUpsertRequest;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.config.EmbeddingProfile;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.retrieval.HybridRetrieverService;
import com.springairag.core.retrieval.ReRankingService;
import com.springairag.core.retrieval.RetrievalScope;
import com.springairag.core.repository.RagDocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * JsonRecordService 守卫与解析长尾（Batch 636，JaCoCo 驱动）：
 * null 请求拒绝、缺集合身份解析拒绝、getByExternalIdentity 的
 * 集合数量守卫与地址退役检查、空检索结果的集合键空投影、
 * payloadContains 过滤校验入口。
 */
class JsonRecordGuardTailTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RagDocumentRepository documentRepository;
    private HybridRetrieverService hybridRetrieverService;
    private CollectionIdentityResolver resolver;
    private ExternalAddressRetirementService addressRetirementService;
    private DocumentVersionService versionService;
    private RagProperties properties;
    private JsonRecordService service;

    @BeforeEach
    void setUp() {
        documentRepository = mock(RagDocumentRepository.class);
        hybridRetrieverService = mock(HybridRetrieverService.class);
        resolver = mock(CollectionIdentityResolver.class);
        addressRetirementService = mock(ExternalAddressRetirementService.class);
        properties = new RagProperties();
        versionService = mock(DocumentVersionService.class);
        lenient().when(versionService.getLatestVersion(any()))
                .thenReturn(Optional.empty());
        lenient().when(versionService.forceRecordVersion(
                any(RagDocument.class), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    var version = new com.springairag.core.entity.RagDocumentVersion();
                    version.setVersionNumber(1);
                    return version;
                });
        lenient().when(documentRepository.saveAndFlush(any(RagDocument.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        var profileProvider = mock(EmbeddingProfileProvider.class);
        lenient().when(profileProvider.getActiveProfile())
                .thenReturn(new EmbeddingProfile(
                        9L, "profile", "test", "model", "v1",
                        1024, "COSINE", "NONE", true));
        lenient().when(resolver.beginActiveWrite(7L))
                .thenReturn(new CollectionIdentityResolver.ActiveCollectionToken(7L, 0L));

        service = new JsonRecordService(
                documentRepository,
                versionService,
                mock(DocumentEmbedService.class),
                hybridRetrieverService,
                mock(ReRankingService.class),
                profileProvider,
                resolver,
                properties,
                MAPPER,
                mock(JdbcTemplate.class),
                null,
                null);
        service.setAddressRetirementService(addressRetirementService);
    }

    @Test
    void upsertNullRequestRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> service.upsert(null));
    }

    @Test
    void upsertWithoutResolvableCollectionRejected() {
        JsonRecordUpsertRequest request = new JsonRecordUpsertRequest();
        request.setExternalId("rec-x");

        assertThrows(IllegalArgumentException.class,
                () -> service.upsert(request));
    }

    @Test
    void getByExternalIdentityWithAmbiguousCollectionRejected() {
        when(resolver.resolveActiveIds(any(), any()))
                .thenReturn(java.util.List.of());

        assertThrows(IllegalArgumentException.class,
                () -> service.getByExternalIdentity(
                        "kb", "default", "rec-1"));
    }

    @Test
    void getByExternalIdentityChecksAddressRetirement() {
        when(resolver.resolveActiveIds(any(), any()))
                .thenReturn(java.util.List.of(7L));
        RagDocument document = new RagDocument();
        document.setId(41L);
        document.setCollectionId(7L);
        document.setDocumentType(RagDocument.JSON_RECORD);
        when(documentRepository.findById(41L))
                .thenReturn(Optional.of(document));
        when(resolver.mapKeys(any()))
                .thenReturn(Map.of(7L, "kb"));
        when(documentRepository
                .findByCollectionIdAndSourceNamespaceAndDocumentTypeAndExternalId(
                        eq(7L), eq("default"), eq(RagDocument.JSON_RECORD),
                        eq("rec-1")))
                .thenReturn(Optional.of(document));

        service.getByExternalIdentity("kb", "default", "rec-1");

        verify(addressRetirementService).requireNotRetired(
                7L, "default", "rec-1");
    }

    @Test
    void emptySearchProjectsNoCollectionKeys() throws Exception {
        when(hybridRetrieverService.searchInScopeDetailed(
                anyString(), any(RetrievalScope.class), any(),
                org.mockito.ArgumentMatchers.anyInt(),
                any(com.springairag.api.dto.RetrievalConfig.class),
                any(com.springairag.core.retrieval.RetrievalFilters.class)))
                .thenReturn(com.springairag.core.retrieval.RetrievalOutcome
                        .ofResults(java.util.List.of()));

        var response = service.searchAuthorizedDetailed(
                "不存在的查询",
                null,
                MAPPER.readTree("{\"k\":\"v\"}"),
                RetrievalScope.unscoped(),
                com.springairag.api.dto.RetrievalConfig.builder()
                        .maxResults(10).useRerank(false).build())
                .response();

        assertTrue(response.results().isEmpty());
    }
}
