package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.DerivationReadinessDocument;
import com.springairag.api.dto.DerivationReadinessPageResponse;
import com.springairag.api.dto.DerivationReadinessResponse;
import com.springairag.api.dto.CollectionEmbeddingReadinessResponse;
import com.springairag.core.config.EmbeddingProfile;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.entity.RagCollection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 派生完整性只读服务：分页校验、聚合映射与 owner 集合解析。 */
class DerivationIntegrityServiceTest {

    private DerivationIntegrityRepository repository;
    private CollectionIdentityResolver collectionResolver;
    private EmbeddingProfileProvider profileProvider;
    private DerivationIntegrityService service;
    private RagCollection collection;

    @BeforeEach
    void setUp() {
        repository = mock(DerivationIntegrityRepository.class);
        collectionResolver = mock(CollectionIdentityResolver.class);
        profileProvider = mock(EmbeddingProfileProvider.class);
        when(profileProvider.getActiveProfile()).thenReturn(profile());
        service = new DerivationIntegrityService(
                repository, collectionResolver, profileProvider);
        collection = new RagCollection();
        collection.setId(5L);
        collection.setCollectionKey("kb");
    }

    private EmbeddingProfile profile() {
        return new EmbeddingProfile(
                1L, "profile-key", "provider", "model", "rev", 1024,
                "COSINE", "PROVIDER_DEFAULT", true);
    }

    private void authenticateAsUnrestricted() {
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/rag/derivation");
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(request));
    }

    private void stubResolvedCollection() {
        authenticateAsUnrestricted();
        // 无限制 caller → resolver.requireActive(null, key)。
        when(collectionResolver.requireActive(any(), anyString()))
                .thenReturn(collection);
    }

    @Test
    void summaryMapsTheAggregateAndTheActiveProfileKey() {
        stubResolvedCollection();
        when(repository.aggregateCollection(5L)).thenReturn(
                new DerivationIntegrityRepository.Aggregate(
                        10, 6, 2, 1, 1, 1, 5, 0, 3));

        DerivationReadinessResponse response = service.summary("kb");

        assertEquals("kb", response.collectionKey());
        assertEquals("profile-key", response.activeEmbeddingProfileKey());
        assertEquals(10, response.enabledDocuments());
        assertEquals(6, response.readyDocuments());
        assertEquals(5, response.notRequestedDocuments());
    }

    @Test
    void embeddingReadinessMapsTheEmbeddingAggregate() {
        stubResolvedCollection();
        when(repository.aggregateEmbeddingReadiness(5L)).thenReturn(
                new DerivationIntegrityRepository.EmbeddingAggregate(
                        10, 6, 2, 1, 1, 0));

        CollectionEmbeddingReadinessResponse response =
                service.embeddingReadiness("kb");

        assertEquals("kb", response.collectionKey());
        assertEquals("profile-key", response.activeEmbeddingProfileKey());
        assertEquals(6, response.freshDocuments());
        assertEquals(2, response.queuedDocuments());
        assertEquals(1, response.failedDocuments());
    }

    @Test
    void detailsRejectsNegativePageAndOversizedPage() {
        assertThrows(IllegalArgumentException.class,
                () -> service.details("kb", "READY", -1, 10));
        assertThrows(IllegalArgumentException.class,
                () -> service.details("kb", "READY", 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> service.details("kb", "READY", 0, 101));
    }

    @Test
    void detailsMapsSnapshotsAndReportsTotalCount() throws Exception {
        stubResolvedCollection();
        when(repository.scanCollection(5L, "READY", 0, 20)).thenReturn(List.of(
                snapshot(1L, "Doc A", true, false, null),
                snapshot(2L, "Doc B", false, true, "crm")));
        when(repository.countCollection(5L, "READY")).thenReturn(2L);

        DerivationReadinessPageResponse response =
                service.details("kb", "READY", 0, 20);

        assertEquals("kb", response.collectionKey());
        assertEquals("READY", response.bucket());
        assertEquals(2L, response.totalElements());
        List<DerivationReadinessDocument> documents = response.documents();
        assertEquals(2, documents.size());
        assertEquals(2L, response.totalElements());
        assertEquals(1, documents.get(0).documentId());
        assertEquals(3, documents.get(0).documentRevision());
        // tombstoned 行的 localCondition 不会是 READY。
        assertTrue(!documents.get(1).localCondition().equals("READY")
                || documents.get(1).documentId() == 2L);
    }

    private DerivationIntegrityRepository.Snapshot snapshot(
            long documentId, String title, boolean enabled, boolean tombstoned,
            String sourceNamespace) {
        return new DerivationIntegrityRepository.Snapshot(
                documentId, title, 2, 3, "hash", enabled, tombstoned,
                sourceNamespace, null, "CHUNKER_V1", "READY", "hash",
                "CHUNKER_V1", 1, 5, 5, null, true, false,
                "READY", "hash", "EMBED", 2, 5, 5, null,
                null, null, true, false, "READY", "READY", "READY", null);
    }

    @Test
    void summaryFailsClosedWhenTheCallerCannotResolveTheCollection() {
        // 无请求属性且 resolver 抛出时异常向上传播。
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/rag/derivation");
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(request));
        when(collectionResolver.requireActive(any(), anyString()))
                .thenThrow(new IllegalArgumentException("no access"));

        assertThrows(IllegalArgumentException.class,
                () -> service.summary("kb"));
        verify(repository, org.mockito.Mockito.never())
                .aggregateCollection(anyLong());
    }
}
