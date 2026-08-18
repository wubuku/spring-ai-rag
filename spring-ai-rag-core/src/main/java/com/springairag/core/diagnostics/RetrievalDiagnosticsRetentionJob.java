package com.springairag.core.diagnostics;

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

    public RetrievalDiagnosticsRetentionJob(RetrievalDiagnosticsService diagnosticsService) {
        this.diagnosticsService = diagnosticsService;
    }

    @Scheduled(cron = "${rag.retrieval-diagnostics.cleanup-cron:0 25 * * * *}")
    public void cleanup() {
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
