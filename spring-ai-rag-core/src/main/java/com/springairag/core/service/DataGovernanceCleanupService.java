package com.springairag.core.service;

import com.springairag.core.config.RagDataGovernanceProperties;
import com.springairag.core.config.RagProperties;
import com.springairag.core.repository.RagAuditLogRepository;
import com.springairag.core.repository.RagClientErrorRepository;
import com.springairag.core.repository.RagRetrievalEvaluationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

/**
 * 默认开启的有界数据治理清理任务。
 *
 * <p>每个数据类别、每个批次使用独立事务；单类失败只影响当前调度周期，下一周期会重试，
 * 不阻塞在线 API。删除始终通过主键和固定批量上限完成，不使用悲观锁或 advisory lock。
 */
@Service
public class DataGovernanceCleanupService {

    private static final Logger log = LoggerFactory.getLogger(DataGovernanceCleanupService.class);

    private final RagDataGovernanceProperties properties;
    private final RagAuditLogRepository auditLogRepository;
    private final RagClientErrorRepository clientErrorRepository;
    private final RagRetrievalEvaluationRepository evaluationRepository;
    private final PlatformTransactionManager transactionManager;

    public DataGovernanceCleanupService(
            RagProperties ragProperties,
            RagAuditLogRepository auditLogRepository,
            RagClientErrorRepository clientErrorRepository,
            RagRetrievalEvaluationRepository evaluationRepository,
            PlatformTransactionManager transactionManager) {
        this.properties = ragProperties.getDataGovernance();
        this.auditLogRepository = auditLogRepository;
        this.clientErrorRepository = clientErrorRepository;
        this.evaluationRepository = evaluationRepository;
        this.transactionManager = transactionManager;
    }

    @Scheduled(
            cron = "${rag.data-governance.cleanup-cron:0 40 * * * *}",
            zone = "${spring.task.scheduling.timezone:Asia/Shanghai}")
    void scheduledCleanupExpiredData() {
        if (properties.isCentralCleanupEnabled()) {
            return;
        }
        cleanupExpiredDataNow();
    }

    /** 供统一协调器调用；保留旧公开入口以兼容测试和运维手工触发。 */
    public void cleanupExpiredData() {
        cleanupExpiredDataNow();
    }

    /** 供统一协调器调用。 */
    public void cleanupExpiredDataNow() {
        if (!properties.isEnabled() || !properties.isCleanupEnabled()) {
            return;
        }

        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        runCategory("audit log", () -> cleanupAuditLogs(
                now.minusDays(properties.getAuditLogRetentionDays())));
        runCategory("client error", () -> cleanupClientErrors(
                now.toInstant().minusSeconds(
                        properties.getClientErrorRetentionDays() * 86_400L)));
        runCategory("retrieval evaluation", () -> cleanupEvaluations(
                now.minusDays(properties.getEvaluationRetentionDays())));
    }

    private long cleanupAuditLogs(ZonedDateTime cutoff) {
        return cleanupBatches(
                () -> auditLogRepository.deleteExpiredBatch(
                        cutoff, properties.getCleanupBatchSize()));
    }

    private long cleanupClientErrors(Instant cutoff) {
        return cleanupBatches(
                () -> clientErrorRepository.deleteExpiredBatch(
                        cutoff, properties.getCleanupBatchSize()));
    }

    private long cleanupEvaluations(ZonedDateTime cutoff) {
        return cleanupBatches(
                () -> evaluationRepository.deleteExpiredBatch(
                        cutoff, properties.getCleanupBatchSize()));
    }

    private long cleanupBatches(IntSupplier deleteBatch) {
        long deleted = 0;
        for (int batch = 0; batch < properties.getCleanupMaxBatches(); batch++) {
            int current = executeInNewTransaction(deleteBatch);
            deleted += current;
            if (current < properties.getCleanupBatchSize()) {
                break;
            }
        }
        return deleted;
    }

    private int executeInNewTransaction(IntSupplier deleteBatch) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
        Integer deleted = template.execute(status -> deleteBatch.getAsInt());
        return deleted == null ? 0 : deleted;
    }

    private void runCategory(String category, LongSupplier cleanup) {
        try {
            long deleted = cleanup.getAsLong();
            if (deleted > 0) {
                log.info("Data governance cleanup removed {} expired {} record(s)",
                        deleted, category);
            }
        } catch (Exception error) {
            log.warn("Data governance cleanup failed for {}; next scheduled run will retry: {}",
                    category, error.getMessage());
        }
    }
}
