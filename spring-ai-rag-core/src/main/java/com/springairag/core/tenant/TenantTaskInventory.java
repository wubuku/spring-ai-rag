package com.springairag.core.tenant;

import java.util.List;
import java.util.Map;

/**
 * WP7：后台任务现场清单（实施规划 §6 WP7「任务路径清单全部打勾，以实际清点为准」）。
 *
 * <p>本清单是<strong>现场清点事实</strong>，不是推测数量；新增 / 删除任务路径时
 * 必须同步本清单，并由 {@code TenantJobInventoryGateTest} 与
 * {@code scripts/verify-multi-tenancy-jobs.sh} 双向校验：</p>
 * <ul>
 *   <li>{@link #TENANT_SCOPED_FILES}：涉及租户私有表（{@code TENANT_PRIVATE}）的任务，
 *       标注 {@link TenantScopedJob}，由 {@link TenantJobDispatcher} 逐租户执行；</li>
 *   <li>{@link #PLATFORM_GLOBAL_FILES}：显式豁免，只访问平台全局 / 凭据表
 *       （{@code PLATFORM_GLOBAL} / {@code CREDENTIAL}），不绑定租户作用域；</li>
 *   <li>{@link #AFTER_COMMIT_FILES}：3 处 {@code TransactionSynchronization}，
 *       注册时捕获 {@link TenantContext}，afterCommit 显式 bootstrap—assert—restore—clear；</li>
 *   <li>{@link #INFRASTRUCTURE_FILES}：不含业务数据访问的配置 / MDC / dispatcher 自身。</li>
 * </ul>
 *
 * <p>归类依据：{@code docs/drafts/MULTI_TENANCY_TABLE_CATALOG.md} 的表归属裁决 +
 * 各任务文件 SQL / Repository 现场清点。未登记的新任务文件一律判红（不静默放过）。</p>
 */
public final class TenantTaskInventory {

    private TenantTaskInventory() {
    }

    /** 租户私有数据作用域任务（按实际 @Scheduled/@Async 文件清点）。 */
    public static final List<String> TENANT_SCOPED_FILES = List.of(
            "alertdelivery/AlertNotificationDeliveryWorker.java",
            "apikeyalert/ApiPrincipalExpiryAlertWorker.java",
            "chat/ChatTurnOperationMaintenance.java",
            "diagnostics/RetrievalDiagnosticsRetentionJob.java",
            "documentprocessing/DocumentProcessingHousekeeping.java",
            "embeddingjob/EmbeddingJobWorker.java",
            "evaluation/EvaluationSuiteWorker.java",
            "observability/IntegrationObservationRecorder.java",
            "service/ChatHistoryCleanupService.java",
            "service/ChunkingUpgradeService.java",
            "service/CollectionProvisioningService.java",
            "service/CollectionPurgeOperationWorker.java",
            "service/CollectionPurgeService.java",
            "service/DataGovernanceCleanupService.java",
            "service/DataGovernanceRetentionCoordinator.java",
            "service/EmbeddingProfileBackfillWorker.java",
            "service/EmbeddingProfileOperationWorker.java",
            "usage/LlmUsageRetentionJob.java"
    );

    /** 平台全局任务：不绑定租户作用域，登记豁免理由。 */
    public static final Map<String, String> PLATFORM_GLOBAL_FILES = Map.ofEntries(
            Map.entry("budget/BudgetReservationService.java",
                    "rag_llm_budget_* 为 PLATFORM_GLOBAL（按 principal 键控，v1 不随租户隔离）"),
            Map.entry("ratelimit/SharedRateLimitMaintenance.java",
                    "rag_api_rate_limit_bucket 为 PLATFORM_GLOBAL（认证/租户解析路径按 principal 键控）"),
            Map.entry("service/ApiKeyManagementService.java",
                    "rag_api_key / rag_api_principal / rotation / provisioning 为 CREDENTIAL（无 RLS，认证入口）"),
            Map.entry("service/DingTalkNotificationService.java",
                    "外部通知通道；不读租户私有表，投递内容由平台告警 worker 提供"),
            Map.entry("service/EmailNotificationService.java",
                    "外部通知通道；不读租户私有表，投递内容由平台告警 worker 提供")
    );

    /** 3 处 TransactionSynchronization（现场清点；无 @TransactionalEventListener）。 */
    public static final List<String> AFTER_COMMIT_FILES = List.of(
            "alertdelivery/AlertNotificationWakeupPublisher.java",
            "embeddingjob/EmbeddingJobWakeupPublisher.java",
            "apikeyalert/ApiPrincipalLifecycleEventPublisher.java"
    );

    /** 基础设施：配置 / MDC / dispatcher 自身，不访问业务数据。 */
    public static final Map<String, String> INFRASTRUCTURE_FILES = Map.ofEntries(
            Map.entry("config/AsyncConfig.java",
                    "线程池/调度器装配；通过 TenantTaskDecorator 统一包装任务"),
            Map.entry("logging/MdcContext.java",
                    "仅 MDC/traceId 传播；不含租户数据访问"),
            Map.entry("tenant/TenantJobDispatcher.java",
                    "WP7 dispatcher 自身，是收敛点而非任务路径")
    );

    /**
     * 返回现场任务文件（@Scheduled / @Async / TransactionSynchronization）
     * 的全部已登记路径；基础设施文件不在此集合，单独验证存在。
     */
    public static List<String> allRegisteredPaths() {
        List<String> paths = new java.util.ArrayList<>(TENANT_SCOPED_FILES);
        paths.addAll(PLATFORM_GLOBAL_FILES.keySet());
        paths.addAll(AFTER_COMMIT_FILES);
        return List.copyOf(paths);
    }
}
