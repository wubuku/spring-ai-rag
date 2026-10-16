package com.springairag.core.service;

import com.springairag.core.budget.BudgetReservationService;
import com.springairag.core.config.RagProperties;
import com.springairag.core.diagnostics.RetrievalDiagnosticsRetentionJob;
import com.springairag.core.observability.IntegrationObservationRecorder;
import com.springairag.core.usage.LlmUsageRetentionJob;
import org.junit.jupiter.api.Test;

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
