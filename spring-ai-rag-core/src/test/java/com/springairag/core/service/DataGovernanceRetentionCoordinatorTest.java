package com.springairag.core.service;

import com.springairag.core.budget.BudgetReservationService;
import com.springairag.core.config.RagProperties;
import com.springairag.core.diagnostics.RetrievalDiagnosticsRetentionJob;
import com.springairag.core.observability.IntegrationObservationRecorder;
import com.springairag.core.usage.LlmUsageRetentionJob;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class DataGovernanceRetentionCoordinatorTest {

    @Test
    void centralModeRunsEachRetentionDelegateOnce() {
        RagProperties properties = new RagProperties();
        DataGovernanceCleanupService governance = mock(DataGovernanceCleanupService.class);
        ChatHistoryCleanupService chat = mock(ChatHistoryCleanupService.class);
        LlmUsageRetentionJob usage = mock(LlmUsageRetentionJob.class);
        RetrievalDiagnosticsRetentionJob diagnostics = mock(RetrievalDiagnosticsRetentionJob.class);
        IntegrationObservationRecorder integration = mock(IntegrationObservationRecorder.class);
        BudgetReservationService budget = mock(BudgetReservationService.class);
        DataGovernanceRetentionCoordinator coordinator = new DataGovernanceRetentionCoordinator(
                properties, governance, chat, usage, diagnostics, integration, budget);

        coordinator.cleanup();

        verify(governance).cleanupExpiredDataNow();
        verify(chat).cleanupExpiredChatHistoryNow();
        verify(usage).cleanupNow();
        verify(diagnostics).cleanupNow();
        verify(integration).cleanupExpiredNow();
        verify(budget).cleanupTerminalReservations();
    }

    /**
     * Batch 952. 一个清理类别失败时，协调器必须继续跑后面的类别，并且留下 WARN。
     * 这两点以前没有任何测试证明：`run(...)` 吞掉 RuntimeException 只写日志，
     * 如果日志级别或格式改动让它变成无声，所有保留策略会一起停摆而无人发现。
     */
    @Test
    void aFailingCategoryIsLoggedAndDoesNotStopTheOthers() {
        RagProperties properties = new RagProperties();
        DataGovernanceCleanupService governance = mock(DataGovernanceCleanupService.class);
        ChatHistoryCleanupService chat = mock(ChatHistoryCleanupService.class);
        LlmUsageRetentionJob usage = mock(LlmUsageRetentionJob.class);
        RetrievalDiagnosticsRetentionJob diagnostics = mock(RetrievalDiagnosticsRetentionJob.class);
        IntegrationObservationRecorder integration = mock(IntegrationObservationRecorder.class);
        BudgetReservationService budget = mock(BudgetReservationService.class);
        doThrow(new IllegalStateException("table unavailable"))
                .when(chat).cleanupExpiredChatHistoryNow();

        Logger logger = (Logger) LoggerFactory.getLogger(
                DataGovernanceRetentionCoordinator.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            new DataGovernanceRetentionCoordinator(
                    properties, governance, chat, usage, diagnostics, integration, budget)
                    .cleanup();
        } finally {
            logger.detachAppender(appender);
        }

        verify(governance).cleanupExpiredDataNow();
        verify(usage).cleanupNow();
        verify(diagnostics).cleanupNow();
        verify(integration).cleanupExpiredNow();
        verify(budget).cleanupTerminalReservations();
        assertTrue(appender.list.stream().anyMatch(event ->
                        event.getLevel() == Level.WARN
                                && event.getFormattedMessage().contains("chat history")
                                && event.getFormattedMessage().contains("IllegalStateException")),
                "the failed category must be visible in the log: " + appender.list);
    }

    @Test
    void disabledCentralModeDoesNotDuplicateLegacyScheduler() {
        RagProperties properties = new RagProperties();
        properties.getDataGovernance().setCentralCleanupEnabled(false);
        DataGovernanceCleanupService governance = mock(DataGovernanceCleanupService.class);
        ChatHistoryCleanupService chat = mock(ChatHistoryCleanupService.class);
        LlmUsageRetentionJob usage = mock(LlmUsageRetentionJob.class);
        RetrievalDiagnosticsRetentionJob diagnostics = mock(RetrievalDiagnosticsRetentionJob.class);
        IntegrationObservationRecorder integration = mock(IntegrationObservationRecorder.class);
        BudgetReservationService budget = mock(BudgetReservationService.class);
        DataGovernanceRetentionCoordinator coordinator = new DataGovernanceRetentionCoordinator(
                properties, governance, chat, usage, diagnostics, integration, budget);

        coordinator.cleanup();

        verifyNoInteractions(governance, chat, usage, diagnostics, integration, budget);
    }
}
