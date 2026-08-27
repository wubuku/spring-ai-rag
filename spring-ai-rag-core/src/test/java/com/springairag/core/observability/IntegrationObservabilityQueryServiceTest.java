package com.springairag.core.observability;

import com.springairag.api.dto.IntegrationObservabilityResponse;
import com.springairag.api.enums.ErrorCode;
import com.springairag.api.enums.IntegrationObservabilityBucket;
import com.springairag.api.enums.IntegrationOperation;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.entity.RagCollection;
import com.springairag.core.exception.RagException;
import com.springairag.core.filter.ApiKeyAuthFilter;
import com.springairag.core.security.ApiCapabilitySupport;
import com.springairag.core.security.AuthenticatedApiPrincipal;
import com.springairag.core.service.CollectionIdentityResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IntegrationObservabilityQueryServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-27T12:00:00Z");
    private static final Instant DEFAULT_FROM =
            Instant.parse("2026-08-26T12:00:00Z");

    private IntegrationObservationRepository repository;
    private RagProperties properties;
    private CollectionIdentityResolver collectionIdentityResolver;
    private IntegrationObservationRecorder recorder;
    private IntegrationObservabilityQueryService service;

    @BeforeEach
    void setUp() {
        repository = mock(IntegrationObservationRepository.class);
        properties = new RagProperties();
        collectionIdentityResolver = mock(CollectionIdentityResolver.class);
        recorder = mock(IntegrationObservationRecorder.class);
        service = new IntegrationObservabilityQueryService(
                repository,
                properties,
                collectionIdentityResolver,
                recorder,
                Clock.fixed(NOW, ZoneOffset.UTC));

        when(repository.totals(
                any(), any(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(emptyAggregate());
        when(repository.byStatus(
                any(), any(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(List.of());
        when(repository.byOperation(
                any(), any(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(List.of());
        when(repository.collectionContributions(
                any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of());
        when(repository.timeline(
                any(), any(), any(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(List.of());
        when(recorder.droppedEvents()).thenReturn(3L);
    }

    @Test
    void normalPrincipalDefaultsToSelfAndCurrentRestrictedCollections() {
        AuthenticatedApiPrincipal principal = principal(
                "rag_p_self", ApiKeyRole.NORMAL, "7,9");
        when(collectionIdentityResolver.mapKeys(List.of(7L, 9L)))
                .thenReturn(Map.of(7L, "manual", 9L, "faq"));

        IntegrationObservabilityResponse response = service.query(
                databaseRequest(principal), null, null, null, null, null, null);

        assertEquals(DEFAULT_FROM, response.scope().from());
        assertEquals(NOW, response.scope().to());
        assertEquals(IntegrationObservabilityBucket.HOUR, response.scope().bucket());
        assertEquals("rag_p_self", response.scope().principalId());
        assertEquals("BEST_EFFORT", response.completeness().mode());
        assertEquals(3L, response.completeness().currentInstanceDropped());
        verify(repository).totals(
                DEFAULT_FROM,
                NOW,
                ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY,
                "rag_p_self",
                null,
                false,
                List.of(7L, 9L));
        verify(repository).collectionContributions(
                DEFAULT_FROM,
                NOW,
                ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY,
                "rag_p_self",
                null,
                List.of(7L, 9L),
                100);
    }

    @Test
    void normalPrincipalAllowsExplicitSelfButRejectsOtherPrincipal() {
        AuthenticatedApiPrincipal principal = principal(
                "rag_p_self", ApiKeyRole.NORMAL, null);

        IntegrationObservabilityResponse self = service.query(
                databaseRequest(principal),
                null, null, null, null, null, "rag_p_self");
        assertEquals("rag_p_self", self.scope().principalId());

        RagException denied = assertThrows(RagException.class, () ->
                service.query(
                        databaseRequest(principal),
                        null, null, null, null, null, "rag_p_other"));
        assertEquals(ErrorCode.FORBIDDEN, denied.getErrorCodeEnum());
    }

    @Test
    void restrictedCollectionFilterUsesOnlyAuthorizedContributionRows() {
        AuthenticatedApiPrincipal principal = principal(
                "rag_p_self", ApiKeyRole.NORMAL, "7,9");
        RagCollection collection = collection(7L, "manual");
        when(collectionIdentityResolver.requireActiveWithinAllowed(
                "manual", java.util.Set.of(7L, 9L)))
                .thenReturn(collection);

        IntegrationObservabilityResponse response = service.query(
                databaseRequest(principal),
                null, null, "day", "json_record_search", "manual", null);

        assertEquals("manual", response.scope().collectionKey());
        assertEquals("JSON_RECORD_SEARCH", response.scope().operation());
        verify(repository).totals(
                DEFAULT_FROM,
                NOW,
                ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY,
                "rag_p_self",
                IntegrationOperation.JSON_RECORD_SEARCH,
                true,
                List.of(7L));
        verify(repository).timeline(
                DEFAULT_FROM,
                NOW,
                ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY,
                "rag_p_self",
                IntegrationOperation.JSON_RECORD_SEARCH,
                IntegrationObservabilityBucket.DAY,
                true,
                List.of(7L));
    }

    @Test
    void restrictedCollectionUnknownOrOutsidePolicyIsForbidden() {
        AuthenticatedApiPrincipal principal = principal(
                "rag_p_self", ApiKeyRole.NORMAL, "7");
        when(collectionIdentityResolver.requireActiveWithinAllowed(
                "private", java.util.Set.of(7L)))
                .thenThrow(new RagException(
                        ErrorCode.COLLECTION_NOT_FOUND, "not found"));

        RagException denied = assertThrows(RagException.class, () ->
                service.query(
                        databaseRequest(principal),
                        null, null, null, null, "private", null));

        assertEquals(ErrorCode.FORBIDDEN, denied.getErrorCodeEnum());
        verify(repository, never()).totals(
                any(), any(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void unrestrictedNormalMayFilterAnActiveCollectionButRemainsSelfScoped() {
        AuthenticatedApiPrincipal principal = principal(
                "rag_p_self", ApiKeyRole.NORMAL, null);
        when(collectionIdentityResolver.requireActive(null, "public"))
                .thenReturn(collection(11L, "public"));

        service.query(
                databaseRequest(principal),
                null, null, null, null, "public", null);

        verify(repository).totals(
                DEFAULT_FROM,
                NOW,
                ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY,
                "rag_p_self",
                null,
                true,
                List.of(11L));
    }

    @Test
    void incompleteRestrictedAclMappingFailsClosed() {
        AuthenticatedApiPrincipal principal = principal(
                "rag_p_self", ApiKeyRole.NORMAL, "7,9");
        when(collectionIdentityResolver.mapKeys(List.of(7L, 9L)))
                .thenReturn(Map.of(7L, "manual"));

        RagException unavailable = assertThrows(RagException.class, () ->
                service.query(
                        databaseRequest(principal),
                        null, null, null, null, null, null));

        assertEquals(ErrorCode.SERVICE_UNAVAILABLE,
                unavailable.getErrorCodeEnum());
        verify(repository, never()).totals(
                any(), any(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void rootCanQueryGlobalOrSelectedDatabasePrincipal() {
        MockHttpServletRequest root = rootRequest();

        service.query(root, null, null, null, null, null, null);
        verify(repository).totals(
                DEFAULT_FROM, NOW, null, null, null, false, null);

        service.query(
                root, null, null, null, null, null, "rag_p_selected");
        verify(repository).totals(
                DEFAULT_FROM,
                NOW,
                ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY,
                "rag_p_selected",
                null,
                false,
                null);
    }

    @Test
    void databaseAdminCanQueryGlobalOrSelectedDatabasePrincipal() {
        AuthenticatedApiPrincipal admin = principal(
                "rag_p_admin", ApiKeyRole.ADMIN, "7");
        MockHttpServletRequest request = databaseRequest(admin);

        service.query(request, null, null, null, null, null, null);
        verify(repository).totals(
                DEFAULT_FROM, NOW, null, null, null, false, null);

        service.query(
                request, null, null, null, null, null, "rag_p_selected");
        verify(repository).totals(
                DEFAULT_FROM,
                NOW,
                ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY,
                "rag_p_selected",
                null,
                false,
                null);
    }

    @Test
    void authDisabledOffersGlobalViewButRejectsPrincipalSelection() {
        MockHttpServletRequest local = new MockHttpServletRequest();

        IntegrationObservabilityResponse global = service.query(
                local, null, null, null, null, null, null);
        assertNull(global.scope().principalId());
        verify(repository).totals(
                DEFAULT_FROM, NOW, null, null, null, false, null);

        RagException denied = assertThrows(RagException.class, () ->
                service.query(
                        local,
                        null, null, null, null, null, "rag_p_selected"));
        assertEquals(ErrorCode.FORBIDDEN, denied.getErrorCodeEnum());
    }

    @Test
    void legacyAnonymousAndInconsistentDatabaseContextAreDeniedOrUnavailable() {
        MockHttpServletRequest legacy = new MockHttpServletRequest();
        legacy.setAttribute(
                ApiKeyAuthFilter.AUTHENTICATED_PRINCIPAL_TYPE,
                ApiKeyAuthFilter.PRINCIPAL_LEGACY_STATIC);
        assertEquals(ErrorCode.FORBIDDEN,
                assertThrows(RagException.class, () ->
                        service.query(
                                legacy,
                                null, null, null, null, null, null))
                        .getErrorCodeEnum());

        MockHttpServletRequest anonymous = new MockHttpServletRequest();
        anonymous.setAttribute(
                ApiKeyAuthFilter.AUTHENTICATION_REQUIRED_ATTRIBUTE, true);
        assertEquals(ErrorCode.FORBIDDEN,
                assertThrows(RagException.class, () ->
                        service.query(
                                anonymous,
                                null, null, null, null, null, null))
                        .getErrorCodeEnum());

        MockHttpServletRequest inconsistent = new MockHttpServletRequest();
        inconsistent.setAttribute(
                ApiKeyAuthFilter.AUTHENTICATED_PRINCIPAL_TYPE,
                ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY);
        inconsistent.setAttribute(
                ApiKeyAuthFilter.AUTHENTICATED_KEY_ATTRIBUTE, "rag_p_self");
        assertEquals(ErrorCode.SERVICE_UNAVAILABLE,
                assertThrows(RagException.class, () ->
                        service.query(
                                inconsistent,
                                null, null, null, null, null, null))
                        .getErrorCodeEnum());
    }

    @Test
    void disabledObservabilityReturnsSpecificServiceUnavailable() {
        properties.getIntegrationObservability().setEnabled(false);

        RagException error = assertThrows(RagException.class, () ->
                service.query(
                        rootRequest(),
                        null, null, null, null, null, null));

        assertEquals(ErrorCode.INTEGRATION_OBSERVABILITY_DISABLED,
                error.getErrorCodeEnum());
        verify(repository, never()).totals(
                any(), any(), any(), any(), any(), anyBoolean(), any());
    }

    @Test
    void validatesWindowBucketOperationAndPrintableFilters() {
        MockHttpServletRequest root = rootRequest();
        assertBadRequest(root, "not-an-instant", null, null, null, null, null);
        assertBadRequest(
                root,
                "2026-08-27T12:00:00Z",
                "2026-08-27T12:00:00Z",
                null, null, null, null);
        assertBadRequest(
                root,
                "2026-07-01T00:00:00Z",
                "2026-08-27T00:00:00Z",
                null, null, null, null);
        assertBadRequest(root, null, null, "minute", null, null, null);
        assertBadRequest(root, null, null, null, "unknown", null, null);
        assertBadRequest(root, null, null, null, null, "", null);
        assertBadRequest(root, null, null, null, null, "bad\nkey", null);
        assertBadRequest(root, null, null, null, null, null, "bad\tid");

        properties.getIntegrationObservability()
                .setMaxQueryRange(Duration.ofDays(31));
        service.query(
                root,
                "2026-07-27T12:00:00Z",
                "2026-08-27T12:00:00Z",
                null, null, null, null);
    }

    @Test
    void mapsEmptyAndPopulatedAggregatesWithStableOrderingAndPercentiles() {
        IntegrationObservationRepository.Aggregate aggregate = aggregate(
                20, 1_010, 800,
                2, 10, 15, 19, 19, 20, 20, 20, 0);
        when(repository.totals(
                any(), any(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(aggregate);
        when(repository.byStatus(
                any(), any(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(List.of(
                        new IntegrationObservationRepository.DimensionAggregate(
                                "500", aggregate),
                        new IntegrationObservationRepository.DimensionAggregate(
                                "200", aggregate)));
        when(repository.byOperation(
                any(), any(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(List.of(
                        new IntegrationObservationRepository.DimensionAggregate(
                                "SYNC_RUN_LIST", aggregate),
                        new IntegrationObservationRepository.DimensionAggregate(
                                "CURRENT_PRINCIPAL", aggregate)));
        when(repository.collectionContributions(
                any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(
                        new IntegrationObservationRepository.CollectionAggregate(
                                7L, "manual", aggregate)));
        when(repository.timeline(
                any(), any(), any(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(List.of(
                        new IntegrationObservationRepository.TimelineAggregate(
                                "2026-08-27T11:00:00Z", aggregate)));
        when(repository.oldestBucket(
                any(), any(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(Instant.parse("2026-08-27T11:00:00Z"));

        IntegrationObservabilityResponse response = service.query(
                rootRequest(), null, null, null, null, null, null);

        assertEquals(20, response.totals().requestCount());
        assertEquals(new BigDecimal("50.50"),
                response.totals().durationAverageMs());
        assertEquals(800, response.totals().durationMaxMs());
        assertEquals(50, response.totals().estimatedP50UpperBoundMs());
        assertEquals(250, response.totals().estimatedP95UpperBoundMs());
        assertEquals(List.of(200, 500), response.byStatus().stream()
                .map(IntegrationObservabilityResponse.StatusBreakdown::httpStatus)
                .toList());
        assertEquals("SUCCESS", response.byStatus().getFirst().statusClass());
        assertEquals(List.of("CURRENT_PRINCIPAL", "SYNC_RUN_LIST"),
                response.byOperation().stream()
                        .map(IntegrationObservabilityResponse.OperationBreakdown::operation)
                        .toList());
        assertEquals("manual",
                response.collectionContributions().getFirst().collectionKey());
        assertEquals("2026-08-27T11:00:00Z",
                response.timeline().getFirst().bucketStart());
        assertTrue(response.totals().estimated());
    }

    @Test
    void emptyWindowReturnsZeroTotalsAndEmptyArrays() {
        IntegrationObservabilityResponse response = service.query(
                rootRequest(), null, null, null, null, null, null);

        assertEquals(0, response.totals().requestCount());
        assertEquals(BigDecimal.ZERO, response.totals().durationAverageMs());
        assertEquals(0, response.totals().estimatedP50UpperBoundMs());
        assertTrue(response.byStatus().isEmpty());
        assertTrue(response.byOperation().isEmpty());
        assertTrue(response.collectionContributions().isEmpty());
        assertTrue(response.timeline().isEmpty());
    }

    @Test
    void invalidPersistedDimensionsAndLongOverflowFailClosed() {
        IntegrationObservationRepository.Aggregate one = aggregate(
                1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0);
        when(repository.byOperation(
                any(), any(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(List.of(
                        new IntegrationObservationRepository.DimensionAggregate(
                                "UNKNOWN_OPERATION", one)));
        assertServiceUnavailable();

        when(repository.byOperation(
                any(), any(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(List.of());
        when(repository.byStatus(
                any(), any(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(List.of(
                        new IntegrationObservationRepository.DimensionAggregate(
                                "99", one)));
        assertServiceUnavailable();

        when(repository.byStatus(
                any(), any(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(List.of());
        when(repository.totals(
                any(), any(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(new IntegrationObservationRepository.Aggregate(
                        BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE),
                        BigDecimal.ZERO,
                        BigInteger.ZERO,
                        BigInteger.ZERO,
                        BigInteger.ZERO,
                        BigInteger.ZERO,
                        BigInteger.ZERO,
                        BigInteger.ZERO,
                        BigInteger.ZERO,
                        BigInteger.ZERO,
                        BigInteger.ZERO,
                        BigInteger.ZERO));
        assertServiceUnavailable();
    }

    @Test
    void softDeletedCollectionsAreOmittedByRepositoryContract() {
        when(repository.collectionContributions(
                any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of());

        IntegrationObservabilityResponse response = service.query(
                rootRequest(), null, null, null, null, null, null);

        assertTrue(response.collectionContributions().isEmpty());
        verify(repository).collectionContributions(
                DEFAULT_FROM, NOW, null, null, null, null, 100);
    }

    private void assertBadRequest(
            MockHttpServletRequest request,
            String from,
            String to,
            String bucket,
            String operation,
            String collectionKey,
            String principalId) {
        assertThrows(IllegalArgumentException.class, () ->
                service.query(
                        request,
                        from,
                        to,
                        bucket,
                        operation,
                        collectionKey,
                        principalId));
    }

    private void assertServiceUnavailable() {
        RagException error = assertThrows(RagException.class, () ->
                service.query(
                        rootRequest(),
                        null, null, null, null, null, null));
        assertEquals(ErrorCode.SERVICE_UNAVAILABLE, error.getErrorCodeEnum());
    }

    private static MockHttpServletRequest databaseRequest(
            AuthenticatedApiPrincipal principal) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(
                ApiKeyAuthFilter.AUTHENTICATED_PRINCIPAL_TYPE,
                ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY);
        request.setAttribute(
                ApiKeyAuthFilter.AUTHENTICATED_KEY_ATTRIBUTE,
                principal.getPrincipalId());
        request.setAttribute(
                ApiKeyAuthFilter.AUTHENTICATED_API_PRINCIPAL_ATTRIBUTE,
                principal);
        return request;
    }

    private static MockHttpServletRequest rootRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(
                ApiKeyAuthFilter.AUTHENTICATED_PRINCIPAL_TYPE,
                ApiKeyAuthFilter.PRINCIPAL_ENVIRONMENT_ROOT);
        return request;
    }

    private static AuthenticatedApiPrincipal principal(
            String principalId,
            ApiKeyRole role,
            String allowedCollectionIds) {
        return new AuthenticatedApiPrincipal(
                principalId,
                "credential-redacted",
                1,
                ApiKeyAuthFilter.PRINCIPAL_DATABASE_API_KEY,
                role,
                allowedCollectionIds,
                null,
                1,
                null,
                List.of(ApiCapabilitySupport.RAG_READ));
    }

    private static RagCollection collection(long id, String key) {
        RagCollection collection = new RagCollection();
        collection.setId(id);
        collection.setCollectionKey(key);
        collection.setName(key);
        collection.setDeleted(false);
        return collection;
    }

    private static IntegrationObservationRepository.Aggregate emptyAggregate() {
        return aggregate(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    private static IntegrationObservationRepository.Aggregate aggregate(
            long requestCount,
            long durationSumMs,
            long durationMaxMs,
            long le25,
            long le50,
            long le100,
            long le250,
            long le500,
            long le1000,
            long le2500,
            long le5000,
            long over5000) {
        return new IntegrationObservationRepository.Aggregate(
                BigInteger.valueOf(requestCount),
                BigDecimal.valueOf(durationSumMs),
                BigInteger.valueOf(durationMaxMs),
                BigInteger.valueOf(le25),
                BigInteger.valueOf(le50),
                BigInteger.valueOf(le100),
                BigInteger.valueOf(le250),
                BigInteger.valueOf(le500),
                BigInteger.valueOf(le1000),
                BigInteger.valueOf(le2500),
                BigInteger.valueOf(le5000),
                BigInteger.valueOf(over5000));
    }
}
