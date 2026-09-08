package com.springairag.core.diagnostics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.RetrievalResult;
import com.springairag.core.chat.ChatPrincipal;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.RagRetrievalLog;
import com.springairag.core.repository.RagRetrievalLogRepository;
import com.springairag.core.retrieval.RetrievalFilters;
import com.springairag.core.retrieval.RetrievalOutcome;
import com.springairag.core.retrieval.RetrievalOutcomeCodes;
import com.springairag.core.retrieval.RetrievalTraceHeaders;
import com.springairag.core.service.CollectionIdentityResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 检索诊断分支语义：分页边界钳制、过期清理有界批量循环、
 * 预算耗尽/未知结果落库、查询原文开关、空仓库降级。
 */
class RetrievalDiagnosticsBranchTest {

    private RagRetrievalLogRepository repository;
    private RagProperties properties;
    private RetrievalDiagnosticsService service;

    @BeforeEach
    void setUp() {
        repository = mock(RagRetrievalLogRepository.class);
        properties = new RagProperties();
        service = new RetrievalDiagnosticsService(
                repository,
                properties,
                new ObjectMapper(),
                mock(CollectionIdentityResolver.class));
    }

    private ChatPrincipal principal() {
        return new ChatPrincipal("db:1", "DATABASE_API_KEY", false);
    }

    @Test
    void listClampsPageSizeAndPageBounds() {
        when(repository.searchTraces(
                anyString(), isNull(), isNull(), isNull(),
                isNull(), isNull(), any()))
                .thenReturn(new PageImpl<>(List.of()));
        properties.getRetrievalDiagnostics().setEnabled(true);

        // size=500 钳到 100，page=-3 钳到 0；空白过滤参数转为 null。
        service.list(principal(), "  ", "", null, null, null, -3, 500);

        ArgumentCaptor<PageRequest> captor =
                ArgumentCaptor.forClass(PageRequest.class);
        verify(repository).searchTraces(
                eq("db:1"), isNull(), isNull(), isNull(),
                isNull(), isNull(), captor.capture());
        assertEquals(0, captor.getValue().getPageNumber());
        assertEquals(100, captor.getValue().getPageSize());
    }

    @Test
    void listReturnsEmptyPageWhenRepositoryIsMissing() {
        RetrievalDiagnosticsService bare = new RetrievalDiagnosticsService(
                null, properties, new ObjectMapper(), null);

        var response = bare.list(
                principal(), null, null, null, null, null, 2, 20);

        assertEquals(0, response.items().size());
        assertEquals(2, response.page());
        assertEquals(20, response.size());
        assertEquals(0, response.totalElements());
        verify(repository, never()).searchTraces(
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void cleanupExpiredBatchesUntilBelowBatchSize() {
        properties.getRetrievalDiagnostics().setRetentionDays(7);
        when(repository.deleteExpiredTraces(any(), eq(500)))
                .thenReturn(500)
                .thenReturn(120);

        int deleted = service.cleanupExpired();

        assertEquals(620, deleted);
        verify(repository, times(2)).deleteExpiredTraces(any(), eq(500));
    }

    @Test
    void cleanupExpiredSkipsWhenRetentionDisabled() {
        properties.getRetrievalDiagnostics().setRetentionDays(0);

        assertEquals(0, service.cleanupExpired());
        verify(repository, never()).deleteExpiredTraces(any(), anyInt());
    }

    @Test
    void persistRecordsBudgetExhaustedWhenNoOutcomeRecorded() {
        RetrievalTraceSession session = service.createSession(
                principal(), RetrievalTraceHeaders.OPERATION_SEARCH, "s-1");
        session.recordBudgetExhausted("attempt-1", "query");

        service.persist(session);

        ArgumentCaptor<RagRetrievalLog> captor =
                ArgumentCaptor.forClass(RagRetrievalLog.class);
        verify(repository).save(captor.capture());
        assertEquals(
                RetrievalOutcomeCodes.RETRIEVAL_BUDGET_EXHAUSTED,
                captor.getValue().getOutcomeCode());
        assertEquals(
                RetrievalOutcomeCodes.RETRIEVAL_BUDGET_EXHAUSTED,
                captor.getValue().getEmptyReasonCode());
    }

    @Test
    void persistRecordsUnknownWhenSessionIsEmpty() {
        RetrievalTraceSession session = service.createSession(
                principal(), RetrievalTraceHeaders.OPERATION_SEARCH, "s-1");

        service.persist(session);

        ArgumentCaptor<RagRetrievalLog> captor =
                ArgumentCaptor.forClass(RagRetrievalLog.class);
        verify(repository).save(captor.capture());
        assertEquals(
                RetrievalOutcomeCodes.DIAGNOSTIC_UNKNOWN,
                captor.getValue().getOutcomeCode());
    }

    @Test
    void persistKeepsQueryTextWhenStoreQueryTextEnabled() {
        properties.getRetrievalDiagnostics().setStoreQueryText(true);
        RetrievalResult result = new RetrievalResult();
        result.setDocumentId("doc-1");
        result.setScore(0.5);
        RetrievalOutcome outcome = RetrievalOutcome.ofResults(List.of(result));
        RetrievalOutcome withQuery = new RetrievalOutcome(
                outcome.traceId(),
                outcome.results(),
                "真皮沙发",
                List.of(),
                Map.of(),
                Map.of(),
                List.of(),
                null,
                null,
                outcome.outcomeCode(),
                outcome.emptyReasonCode(),
                9L,
                1);
        RetrievalTraceSession session = service.createSession(
                principal(), RetrievalTraceHeaders.OPERATION_SEARCH, "s-1");
        session.recordRetrieval(null, withQuery);

        service.persist(session);

        ArgumentCaptor<RagRetrievalLog> captor =
                ArgumentCaptor.forClass(RagRetrievalLog.class);
        verify(repository).save(captor.capture());
        assertEquals("真皮沙发", captor.getValue().getQuery());
        assertEquals(1, captor.getValue().getResultCount());
        assertEquals(9L, captor.getValue().getTotalTimeMs());
    }

    @Test
    void persistDoesNothingWhenDiagnosticsDisabled() {
        properties.getRetrievalDiagnostics().setEnabled(false);
        properties.getRetrievalDiagnostics().setPersist(false);
        RetrievalTraceSession session = service.createSession(
                principal(), RetrievalTraceHeaders.OPERATION_SEARCH, "s-1");

        service.persist(session);

        verify(repository, never()).save(any());
    }

    @Test
    void isEnabledMirrorsConfiguration() {
        properties.getRetrievalDiagnostics().setEnabled(false);
        assertEquals(false, service.isEnabled());
        properties.getRetrievalDiagnostics().setEnabled(true);
        assertTrue(service.isEnabled());
    }
}
