package com.springairag.core.diagnostics;

import com.springairag.core.config.RagProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 按 created_at 分批删除过期诊断记录。失败不影响检索请求。
 */
@Component
public class RetrievalDiagnosticsRetentionJob {

    private static final Logger log =
            LoggerFactory.getLogger(RetrievalDiagnosticsRetentionJob.class);

    private final RetrievalDiagnosticsService diagnosticsService;
    private final RagProperties properties;

    public RetrievalDiagnosticsRetentionJob(RetrievalDiagnosticsService diagnosticsService) {
        this(diagnosticsService, legacyProperties());
    }

    @Autowired
    public RetrievalDiagnosticsRetentionJob(
            RetrievalDiagnosticsService diagnosticsService,
            RagProperties properties) {
        this.diagnosticsService = diagnosticsService;
        this.properties = properties;
    }

    private static RagProperties legacyProperties() {
        RagProperties properties = new RagProperties();
        properties.getDataGovernance().setCentralCleanupEnabled(false);
        return properties;
    }

    @Scheduled(cron = "${rag.retrieval-diagnostics.cleanup-cron:0 25 * * * *}")
    public void cleanup() {
        if (properties.getDataGovernance().isCentralCleanupEnabled()) {
            return;
        }
        cleanupNow();
    }

    /** 供统一数据治理协调器调用。 */
    public void cleanupNow() {
        try {
            int deleted = diagnosticsService.cleanupExpired();
            if (deleted > 0) {
                log.info("Deleted {} expired retrieval diagnostic rows", deleted);
            }
        } catch (Exception e) {
            log.warn("Retrieval diagnostic retention failed: {}", e.getMessage());
        }
    }
}
