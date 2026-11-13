package com.springairag.core.service;

import com.springairag.core.config.RagProperties;
import com.springairag.core.budget.BudgetReservationService;
import com.springairag.core.diagnostics.RetrievalDiagnosticsRetentionJob;
import com.springairag.core.observability.IntegrationObservationRecorder;
import com.springairag.core.usage.LlmUsageRetentionJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import com.springairag.core.tenant.TenantScopedJob;

/**
 * 默认唯一的业务数据保留协调器。
 *
 * <p>各保留任务仍保留原公开方法，但默认 scheduler 由本协调器统一触发，
 * 避免不同 cron 同时删除同一类数据或让 client 看见互相矛盾的保留状态。
 * LLM reservation 的过期回收是预算安全协议的一部分，仍由预算服务单独负责。</p>
 *
 * <p>Batch 952：这里原本有 {@code @ConditionalOnBean}，列出全部六个协作者。
 * {@code @ConditionalOnBean} 只在 auto-configuration 阶段可靠；写在被扫描的
 * {@code @Service} 上时，求值时机取决于 bean 注册顺序，可能把协调器静默跳过。
 * 而 {@code central-cleanup-enabled} 默认 true，各单项任务看到它为 true 就直接
 * return —— 结果是一旦协调器没被创建，<em>所有</em> TTL/保留清理一起停摆，
 * 且日志里没有任何一条错误。依赖本身都是无条件的 {@code @Service/@Component}，
 * 所以条件装配没有收益，只留下这个静默失效面；移除后若依赖真的消失，
 * 构造注入会在启动时直接失败。回归测试见
 * {@code ScheduledWorkerRegistrationTest}。</p>
 */
@Service
@TenantScopedJob
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
