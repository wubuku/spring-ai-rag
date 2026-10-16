package com.springairag.core.service;

import com.springairag.core.config.RagProperties;
import com.springairag.core.budget.BudgetReservationService;
import com.springairag.core.diagnostics.RetrievalDiagnosticsRetentionJob;
import com.springairag.core.observability.IntegrationObservationRecorder;
import com.springairag.core.usage.LlmUsageRetentionJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 默认唯一的业务数据保留协调器。
 *
 * <p>各保留任务仍保留原公开方法，但默认 scheduler 由本协调器统一触发，
 * 避免不同 cron 同时删除同一类数据或让 client 看见互相矛盾的保留状态。
 * LLM reservation 的过期回收是预算安全协议的一部分，仍由预算服务单独负责。</p>
 */
@Service
@ConditionalOnBean({
        DataGovernanceCleanupService.class,
        ChatHistoryCleanupService.class,
        LlmUsageRetentionJob.class,
        RetrievalDiagnosticsRetentionJob.class,
        IntegrationObservationRecorder.class,
        BudgetReservationService.class})
public final class DataGovernanceRetentionCoordinator {

    private static final Logger log =
            LoggerFactory.getLogger(DataGovernanceRetentionCoordinator.class);

    private final RagProperties properties;
    private final DataGovernanceCleanupService governanceCleanup;
    private final ChatHistoryCleanupService chatHistoryCleanup;
    private final LlmUsageRetentionJob usageCleanup;
    private final RetrievalDiagnosticsRetentionJob diagnosticsCleanup;
    private final IntegrationObservationRecorder integrationCleanup;
    private final BudgetReservationService budgetCleanup;

    public DataGovernanceRetentionCoordinator(
            RagProperties properties,
            DataGovernanceCleanupService governanceCleanup,
            ChatHistoryCleanupService chatHistoryCleanup,
            LlmUsageRetentionJob usageCleanup,
            RetrievalDiagnosticsRetentionJob diagnosticsCleanup,
            IntegrationObservationRecorder integrationCleanup,
            BudgetReservationService budgetCleanup) {
        this.properties = properties;
        this.governanceCleanup = governanceCleanup;
        this.chatHistoryCleanup = chatHistoryCleanup;
        this.usageCleanup = usageCleanup;
        this.diagnosticsCleanup = diagnosticsCleanup;
        this.integrationCleanup = integrationCleanup;
        this.budgetCleanup = budgetCleanup;
    }

    @Scheduled(
            cron = "${rag.data-governance.cleanup-cron:0 40 * * * *}",
            zone = "${spring.task.scheduling.timezone:Asia/Shanghai}")
    public void cleanup() {
        if (!properties.getDataGovernance().isCentralCleanupEnabled()) {
            return;
        }
        run("governance", governanceCleanup::cleanupExpiredDataNow);
        run("chat history", chatHistoryCleanup::cleanupExpiredChatHistoryNow);
        run("LLM usage", usageCleanup::cleanupNow);
        run("retrieval diagnostics", diagnosticsCleanup::cleanupNow);
        run("integration observability", integrationCleanup::cleanupExpiredNow);
        run("budget reservation", budgetCleanup::cleanupTerminalReservations);
    }

    private void run(String category, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException failure) {
            log.warn("Central data retention cleanup failed for {}: {}",
                    category, failure.getClass().getSimpleName());
        }
    }
}
