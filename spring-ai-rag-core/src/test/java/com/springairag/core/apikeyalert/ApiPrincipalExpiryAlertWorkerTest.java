package com.springairag.core.apikeyalert;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 到期提醒 worker：事件与兜底扫描双入口，失败吞噬且截断上报。 */
class ApiPrincipalExpiryAlertWorkerTest {

    private ApiPrincipalExpiryAlertService service;
    private ApiPrincipalExpiryAlertMetrics metrics;
    private ApiPrincipalExpiryAlertWorker worker;

    @BeforeEach
    void setUp() {
        service = mock(ApiPrincipalExpiryAlertService.class);
        metrics = mock(ApiPrincipalExpiryAlertMetrics.class);
        worker = new ApiPrincipalExpiryAlertWorker(service, metrics);
    }

    @Test
    void principalChangedEventReconcilesTheAffectedPrincipal() throws Exception {
        var future = worker.onPrincipalChanged(
                new ApiPrincipalLifecycleChangedEvent("rag_p_1"));

        assertTrue(future.get(1, TimeUnit.SECONDS) == null
                || future.isDone());
        verify(service).reconcilePrincipalExpiry("rag_p_1");
    }

    @Test
    void eventDrivenReconciliationFailureIsSwallowed() {
        doThrow(new IllegalStateException("down"))
                .when(service).reconcilePrincipalExpiry("rag_p_1");

        worker.onPrincipalChanged(new ApiPrincipalLifecycleChangedEvent("rag_p_1"));

        verify(service).reconcilePrincipalExpiry("rag_p_1");
    }

    @Test
    void fallbackScanReconcilesEveryCandidate() {
        when(service.findFallbackCandidates()).thenReturn(
                new ApiPrincipalExpiryAlertService.CandidateBatch(
                        List.of("rag_p_1", "rag_p_2"), false));

        worker.fallbackScan();

        verify(service).reconcilePrincipalExpiry("rag_p_1");
        verify(service).reconcilePrincipalExpiry("rag_p_2");
    }

    @Test
    void fallbackScanReportsTruncationThroughMetrics() {
        when(service.findFallbackCandidates()).thenReturn(
                new ApiPrincipalExpiryAlertService.CandidateBatch(
                        List.of("rag_p_1"), true));

        worker.fallbackScan();

        verify(metrics).recordScanTruncated();
        verify(service).reconcilePrincipalExpiry("rag_p_1");
    }

    @Test
    void fallbackScanSwallowsCandidateListingFailures() {
        when(service.findFallbackCandidates())
                .thenThrow(new IllegalStateException("db down"));

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> worker.fallbackScan());
        org.mockito.Mockito.verifyNoInteractions(metrics);
    }

    @Test
    void fallbackScanContinuesPastAFailingCandidate() {
        when(service.findFallbackCandidates()).thenReturn(
                new ApiPrincipalExpiryAlertService.CandidateBatch(
                        List.of("rag_p_bad", "rag_p_good"), false));
        doThrow(new IllegalStateException("bad principal"))
                .when(service).reconcilePrincipalExpiry("rag_p_bad");

        worker.fallbackScan();

        verify(service).reconcilePrincipalExpiry("rag_p_good");
    }
}
