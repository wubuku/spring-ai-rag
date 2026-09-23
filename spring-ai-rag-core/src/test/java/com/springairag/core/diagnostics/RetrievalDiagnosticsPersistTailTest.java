package com.springairag.core.diagnostics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.chat.ChatPrincipal;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.RagRetrievalLog;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.RagRetrievalLogRepository;
import com.springairag.core.retrieval.RetrievalBranchStage;
import com.springairag.core.retrieval.RetrievalOutcome;
import com.springairag.core.retrieval.RetrievalOutcomeCodes;
import com.springairag.core.retrieval.RetrievalTraceHeaders;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RetrievalDiagnosticsService 落库/读取长尾（Batch 587，JaCoCo 驱动）：
 * persistSearch 空参保护、persist 降级、查询原文脱敏组合、预算耗尽
 * 且最新检索为空的判定、阶段耗时投影、仓库缺失时 get/list 行为、
 * 详情对空元数据与非 rank 分数的裁剪。
 */
class RetrievalDiagnosticsPersistTailTest {

    private final RagRetrievalLogRepository repository =
            mock(RagRetrievalLogRepository.class);
    private final RagProperties properties = new RagProperties();

    private RetrievalDiagnosticsService service() {
        return new RetrievalDiagnosticsService(
                repository, properties, new ObjectMapper(), null);
    }

    private RetrievalDiagnosticsService bareService() {
        return new RetrievalDiagnosticsService(
                null, properties, new ObjectMapper(), null);
    }

    private ChatPrincipal principal() {
        return new ChatPrincipal("db:1", "DATABASE_API_KEY", false);
    }

    @Test
    void persistSearchIgnoresNullSessionAndNullOutcome() {
        RetrievalDiagnosticsService service = service();
        RetrievalTraceSession session = service.createSession(
                principal(), RetrievalTraceHeaders.OPERATION_SEARCH, "s-1");

        service.persistSearch(null, RetrievalOutcome.ofResults(List.of()),
                Map.of(), null);
        service.persistSearch(session, null, Map.of(), null);

        verify(repository, never()).save(any());
    }

    @Test
    void persistIgnoresNullSession() {
        service().persist(null);

        verify(repository, never()).save(any());
    }

    @Test
    void persistWithoutRepositoryIsSilentlySkipped() {
        RetrievalTraceSession session = bareService().createSession(
                principal(), RetrievalTraceHeaders.OPERATION_SEARCH, "s-1");

        bareService().persist(session);

        verify(repository, never()).save(any());
    }

    @Test
    void persistRedactsQueryWhenStoreQueryTextEnabledButOutcomeMissing() {
        properties.getRetrievalDiagnostics().setStoreQueryText(true);
        RetrievalTraceSession session = service().createSession(
                principal(), RetrievalTraceHeaders.OPERATION_SEARCH, "s-1");

        service().persist(session);

        assertEquals(
                RetrievalTraceHeaders.REDACTED_QUERY,
                capturedLog().getQuery());
    }

    @Test
    void persistRedactsBlankQueryTextWhenStoreQueryTextEnabled() {
        properties.getRetrievalDiagnostics().setStoreQueryText(true);
        RetrievalTraceSession session = sessionWithOutcome(null);

        service().persist(session);

        assertEquals(
                RetrievalTraceHeaders.REDACTED_QUERY,
                capturedLog().getQuery());
    }

    @Test
    void persistMarksBudgetExhaustedWhenLatestRetrievalIsEmpty() {
        RetrievalTraceSession session = service().createSession(
                principal(), RetrievalTraceHeaders.OPERATION_SEARCH, "s-1");
        session.recordBudgetExhausted("a1", "q");
        session.recordRetrieval("a1", RetrievalOutcome.ofResults(List.of()));

        service().persist(session);

        RagRetrievalLog log = capturedLog();
        assertEquals(
                RetrievalOutcomeCodes.RETRIEVAL_BUDGET_EXHAUSTED,
                log.getOutcomeCode());
        assertEquals(
                RetrievalOutcomeCodes.RETRIEVAL_BUDGET_EXHAUSTED,
                log.getEmptyReasonCode());
        assertEquals(0, log.getResultCount());
    }

    @Test
    void persistProjectsStageElapsedMillisAndStrategy() {
        RetrievalBranchStage vector = new RetrievalBranchStage(
                RetrievalBranchStage.VECTOR, "bge-m3", "OK", 12L, 5, 3, null);
        RetrievalTraceSession session = service().createSession(
                principal(), RetrievalTraceHeaders.OPERATION_SEARCH, "s-1");
        session.recordRetrieval("a1", new RetrievalOutcome(
                UUID.randomUUID(),
                List.of(),
                "q",
                List.of(),
                Map.of(),
                Map.of(),
                List.of(vector),
                null,
                null,
                "NO_CANDIDATES",
                "NO_CANDIDATES",
                20L,
                0));

        service().persist(session);

        RagRetrievalLog log = capturedLog();
        assertEquals(12L, log.getVectorSearchTimeMs());
        assertEquals(0L, log.getFulltextSearchTimeMs());
        assertEquals(0L, log.getRerankTimeMs());
        assertEquals("vector", log.getRetrievalStrategy());
    }

    @Test
    void getThrowsNotFoundWhenRepositoryMissing() {
        var exception = assertThrows(
                RagException.class,
                () -> bareService().get(principal(), UUID.randomUUID()));

        assertEquals(ErrorCode.NOT_FOUND, exception.getErrorCodeEnum());
    }

    @Test
    void getWithNullPrincipalFallsBackToLocalIdentity() {
        var exception = assertThrows(
                RagException.class,
                () -> bareService().get(null, UUID.randomUUID()));

        assertEquals(ErrorCode.NOT_FOUND, exception.getErrorCodeEnum());
    }

    @Test
    void listPassesNonBlankFiltersThroughToRepository() {
        when(repository.searchTraces(
                anyString(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of()));

        service().list(principal(), "CHAT", "RESULTS_RETURNED",
                "NO_CANDIDATES", "s-1", "VALID", 1, 10);

        verify(repository).searchTraces(
                eq("db:1"), eq("CHAT"), eq("RESULTS_RETURNED"),
                eq("NO_CANDIDATES"), eq("s-1"), eq("VALID"), any());
    }

    @Test
    void detailHandlesNullMetadataAndNullScores() {
        RagRetrievalLog log = ownedLog();
        log.setMetadata(null);
        log.setResultScores(null);
        when(repository.findByTraceId(log.getTraceId()))
                .thenReturn(Optional.of(log));

        var detail = service().get(principal(), log.getTraceId());

        assertEquals(Map.of(), detail.metadata());
        assertEquals(Map.of(), detail.resultScores());
    }

    @Test
    void detailKeepsNonListScopeKeysAndDropsNonRankScores() {
        RagRetrievalLog log = ownedLog();
        log.setCreatedAt(null);
        log.setMetadata(Map.of(
                "scope", Map.of("collectionKeys", "not-a-list", "keep", 1)));
        log.setResultScores(Map.of("rank_1", 0.9, "raw", 0.4));
        when(repository.findByTraceId(log.getTraceId()))
                .thenReturn(Optional.of(log));

        var detail = service().get(principal(), log.getTraceId());

        assertNull(detail.createdAt());
        @SuppressWarnings("unchecked")
        Map<String, Object> scope =
                (Map<String, Object>) detail.metadata().get("scope");
        assertEquals("not-a-list", scope.get("collectionKeys"));
        assertEquals(Map.of("rank_1", 0.9), detail.resultScores());
    }

    private RetrievalTraceSession sessionWithOutcome(String originalQuery) {
        RetrievalTraceSession session = service().createSession(
                principal(), RetrievalTraceHeaders.OPERATION_SEARCH, "s-1");
        session.recordRetrieval("a1", new RetrievalOutcome(
                UUID.randomUUID(),
                List.of(),
                originalQuery,
                List.of(),
                Map.of(),
                Map.of(),
                List.of(),
                null,
                null,
                "NO_CANDIDATES",
                "NO_CANDIDATES",
                5L,
                0));
        return session;
    }

    @Test
    void persistSkipsOnlyPersistFlagWhenEnabled() {
        properties.getRetrievalDiagnostics().setEnabled(true);
        properties.getRetrievalDiagnostics().setPersist(false);
        RetrievalTraceSession session = service().createSession(
                principal(), RetrievalTraceHeaders.OPERATION_SEARCH, "s-1");

        service().persist(session);

        verify(repository, never()).save(any());
    }

    @Test
    void persistKeepsOutcomeCodesWhenBudgetExhaustedButResultsExist() {
        RetrievalTraceSession session = service().createSession(
                principal(), RetrievalTraceHeaders.OPERATION_SEARCH, "s-1");
        session.recordBudgetExhausted("a1", "q");
        session.recordRetrieval("a1", RetrievalOutcome.ofResults(
                List.of(new com.springairag.api.dto.RetrievalResult())));

        service().persist(session);

        RagRetrievalLog log = capturedLog();
        assertEquals(
                RetrievalOutcomeCodes.RESULTS_RETURNED,
                log.getOutcomeCode());
        assertNull(log.getEmptyReasonCode());
    }

    @Test
    void detailHandlesEmptyMetadataAndEmptyScores() {
        RagRetrievalLog log = ownedLog();
        log.setMetadata(Map.of());
        log.setResultScores(Map.of());
        when(repository.findByTraceId(log.getTraceId()))
                .thenReturn(Optional.of(log));

        var detail = service().get(principal(), log.getTraceId());

        assertEquals(Map.of(), detail.metadata());
        assertEquals(Map.of(), detail.resultScores());
    }

    @Test
    void detailDropsScoresWithNullKey() {
        RagRetrievalLog log = ownedLog();
        Map<String, Object> scores = new java.util.HashMap<>();
        scores.put(null, 0.1);
        scores.put("rank_2", 0.8);
        log.setResultScores(scores);
        when(repository.findByTraceId(log.getTraceId()))
                .thenReturn(Optional.of(log));

        var detail = service().get(principal(), log.getTraceId());

        assertEquals(Map.of("rank_2", 0.8), detail.resultScores());
    }

    private RagRetrievalLog ownedLog() {
        RagRetrievalLog log = new RagRetrievalLog();
        log.setTraceId(UUID.randomUUID());
        log.setOwnerPrincipalId("db:1");
        return log;
    }

    private RagRetrievalLog capturedLog() {
        ArgumentCaptor<RagRetrievalLog> captor =
                ArgumentCaptor.forClass(RagRetrievalLog.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }
}
