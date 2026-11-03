package com.springairag.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.core.entity.ApiKeyRole;
import com.springairag.core.entity.RagUserFeedback;
import com.springairag.core.filter.ApiKeyAuthFilter;
import com.springairag.core.repository.RagUserFeedbackRepository;
import com.springairag.core.security.ApiAccessPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 反馈视图的租户隔离回归。
 *
 * <p>root/不受限保持全局视图；受限 key 只能读取自己的行。
 */
class UserFeedbackServiceScopingTest {

    private RagUserFeedbackRepository repository;
    private UserFeedbackServiceImpl service;

    @BeforeEach
    void setUp() {
        repository = mock(RagUserFeedbackRepository.class);
        service = new UserFeedbackServiceImpl(
                repository, new ObjectMapper(), mock(FeedbackDocumentReferenceStore.class),
                mock(CollectionIdentityResolver.class));
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
    void restrictedHistoryFiltersByOwnerPrincipal() {
        authenticate(policy("principal-a", "9"));
        List<RagUserFeedback> rows = List.of(new RagUserFeedback());
        when(repository.findByOwnerPrincipalIdOrderByCreatedAtDesc(
                eq("principal-a"), any(PageRequest.class))).thenReturn(rows);

        List<RagUserFeedback> result = service.getHistory(0, 20);

        assertSame(rows, result);
        verify(repository).findByOwnerPrincipalIdOrderByCreatedAtDesc(
                eq("principal-a"), any(PageRequest.class));
        verify(repository, never()).findAll(any(PageRequest.class));
    }

    @Test
    void rootHistoryKeepsGlobalView() {
        authenticate(policy("root", null));
        when(repository.findAll(any(PageRequest.class)))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(
                        List.of(new RagUserFeedback())));

        service.getHistory(0, 20);

        verify(repository).findAll(any(PageRequest.class));
        verify(repository, never()).findByOwnerPrincipalIdOrderByCreatedAtDesc(any(), any());
    }

    @Test
    void restrictedStatsUseOwnerScopedCounts() {
        authenticate(policy("principal-a", "9"));
        ZonedDateTime start = ZonedDateTime.now().minusDays(1);
        ZonedDateTime end = ZonedDateTime.now();
        when(repository.countByFeedbackTypeAndOwnerPrincipalIdAndCreatedAtBetween(
                any(), any(), any(), any())).thenReturn(1L);
        when(repository.findByOwnerPrincipalIdAndCreatedAtBetweenOrderByCreatedAtDesc(
                any(), any(), any())).thenReturn(List.of());

        service.getStats(start, end);

        verify(repository).countByFeedbackTypeAndOwnerPrincipalIdAndCreatedAtBetween(
                eq("THUMBS_UP"), eq("principal-a"), eq(start), eq(end));
        verify(repository).countByFeedbackTypeAndOwnerPrincipalIdAndCreatedAtBetween(
                eq("THUMBS_DOWN"), eq("principal-a"), eq(start), eq(end));
        verify(repository, never()).countByFeedbackTypeAndCreatedAtBetween(any(), any(), any());
    }

    @Test
    void restrictedByTypeFiltersByOwnerPrincipal() {
        authenticate(policy("principal-a", "9"));
        when(repository.findByFeedbackTypeAndOwnerPrincipalIdOrderByCreatedAtDesc(
                any(), any(), any())).thenReturn(List.of());

        service.getByType("THUMBS_UP", 0, 20);

        verify(repository).findByFeedbackTypeAndOwnerPrincipalIdOrderByCreatedAtDesc(
                eq("THUMBS_UP"), eq("principal-a"), any(PageRequest.class));
    }
}
