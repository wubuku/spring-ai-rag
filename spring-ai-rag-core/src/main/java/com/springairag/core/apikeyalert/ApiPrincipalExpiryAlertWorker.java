package com.springairag.core.apikeyalert;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * Spring Event 提供低延迟提示，低频 Scheduled 扫描负责时间阈值与漏事件恢复。
 */
@Component
public class ApiPrincipalExpiryAlertWorker {

    private static final Logger log = LoggerFactory.getLogger(
            ApiPrincipalExpiryAlertWorker.class);

    private final ApiPrincipalExpiryAlertService service;
    private final ApiPrincipalExpiryAlertMetrics metrics;

    public ApiPrincipalExpiryAlertWorker(
            ApiPrincipalExpiryAlertService service,
            ApiPrincipalExpiryAlertMetrics metrics) {
        this.service = service;
        this.metrics = metrics;
    }

    @Async("taskExecutor")
    @EventListener
    public CompletableFuture<Void> onPrincipalChanged(
            ApiPrincipalLifecycleChangedEvent event) {
        reconcileSafely(event.principalId(), "event");
        return CompletableFuture.completedFuture(null);
    }

    @Scheduled(
            fixedDelayString =
                    "${rag.api-key-expiry-alerts.fallback-scan-interval:PT1H}",
            zone = "${spring.task.scheduling.timezone:Asia/Shanghai}")
    public void fallbackScan() {
        ApiPrincipalExpiryAlertService.CandidateBatch batch;
        try {
            batch = service.findFallbackCandidates();
        } catch (RuntimeException failure) {
            log.warn("API principal expiry fallback scan failed", failure);
            return;
        }
        if (batch.truncated()) {
            metrics.recordScanTruncated();
            log.warn(
                    "API principal expiry fallback scan reached its bounded limit");
        }
        for (String principalId : batch.principalIds()) {
            reconcileSafely(principalId, "fallback");
        }
    }

    private void reconcileSafely(String principalId, String source) {
        try {
            service.reconcilePrincipalExpiry(principalId);
        } catch (RuntimeException failure) {
            log.warn(
                    "API principal expiry reconciliation failed: source={}, principalId={}",
                    source,
                    principalId,
                    failure);
        }
    }
}
