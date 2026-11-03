package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.entity.RagRetrievalEvaluation;
import com.springairag.core.filter.ApiKeyAuthFilter;
import com.springairag.core.repository.RagRetrievalEvaluationRepository;
import com.springairag.core.security.ApiAccessPolicy;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 检索评估视图的租户隔离回归。
 */
class RetrievalEvaluationServiceScopingTest {

    private RagRetrievalEvaluationRepository repository;
    private RetrievalEvaluationServiceImpl service;

    @BeforeEach
    void setUp() {
        repository = mock(RagRetrievalEvaluationRepository.class);
        service = new RetrievalEvaluationServiceImpl(
                repository, new ObjectMapper(), new SimpleMeterRegistry(),
                null, null, new RagProperties());
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    private static ApiAccessPolicy policy(String principalId, String allowed) {
        return new ApiAccessPolicy() {
            @Override public String getPrincipalId() { return principalId; }
            @Override public String getCredentialId() { return "cred"; }
            @Override public ApiKeyRole getRole() { return ApiKeyRole.NORMAL; }
            @Override public String getAllowedCollectionIds() { return allowed; }
            @Override public LocalDateTime getExpiresAt() { return null; }
        };
    }

    private void authenticate(ApiAccessPolicy key) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(
                ApiKeyAuthFilter.AUTHENTICATED_API_PRINCIPAL_ATTRIBUTE, key);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @Test
    void restrictedEvaluationHistoryFiltersByOwnerPrincipal() {
        authenticate(policy("principal-a", "9"));
        when(repository.findByOwnerPrincipalIdOrderByCreatedAtDesc(
                eq("principal-a"), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(new RagRetrievalEvaluation())));

        service.getHistory(0, 20);

        verify(repository).findByOwnerPrincipalIdOrderByCreatedAtDesc(
                eq("principal-a"), any(PageRequest.class));
        verify(repository, never()).findAllByOrderByCreatedAtDesc(any(PageRequest.class));
    }

    @Test
    void rootEvaluationHistoryKeepsGlobalView() {
        authenticate(policy("root", null));
        when(repository.findAllByOrderByCreatedAtDesc(any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of()));

        service.getHistory(0, 20);

        verify(repository).findAllByOrderByCreatedAtDesc(any(PageRequest.class));
    }

    @Test
    void restrictedReportUsesOwnerScopedRange() {
        authenticate(policy("principal-a", "9"));
        ZonedDateTime start = ZonedDateTime.now().minusDays(1);
        ZonedDateTime end = ZonedDateTime.now();
        when(repository.findByOwnerPrincipalIdAndCreatedAtBetweenOrderByCreatedAtDesc(
                eq("principal-a"), eq(start), eq(end))).thenReturn(List.of());

        service.getReport(start, end);

        verify(repository).findByOwnerPrincipalIdAndCreatedAtBetweenOrderByCreatedAtDesc(
                eq("principal-a"), eq(start), eq(end));
        verify(repository, never()).findByCreatedAtBetweenOrderByCreatedAtDesc(any(), any());
    }

    @Test
    void restrictedAggregatedMetricsUseOwnerScopedRange() {
        authenticate(policy("principal-a", "9"));
        ZonedDateTime start = ZonedDateTime.now().minusDays(1);
        ZonedDateTime end = ZonedDateTime.now();
        when(repository.findByOwnerPrincipalIdAndCreatedAtBetweenOrderByCreatedAtDesc(
                any(), any(), any())).thenReturn(List.of());
        when(repository.countByCreatedAtBetween(any(), any())).thenReturn(0L);

        service.getAggregatedMetrics(start, end);

        verify(repository).findByOwnerPrincipalIdAndCreatedAtBetweenOrderByCreatedAtDesc(
                eq("principal-a"), eq(start), eq(end));
    }
}
