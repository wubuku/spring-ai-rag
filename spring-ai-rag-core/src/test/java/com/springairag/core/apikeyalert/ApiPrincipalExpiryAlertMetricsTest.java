package com.springairag.core.apikeyalert;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ApiPrincipalExpiryAlertMetrics（Batch 415）：对账计数按
 * outcome/phase 低基数标签累加、空值归一 NONE、扫描截断计数、
 * registry 缺失时整体 no-op。
 */
class ApiPrincipalExpiryAlertMetricsTest {

    @SuppressWarnings("unchecked")
    private ObjectProvider<MeterRegistry> provider(MeterRegistry registry) {
        ObjectProvider<MeterRegistry> provider =
                mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(registry);
        return provider;
    }

    @Test
    void reconcileCounterAccumulatesWithNormalizedTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ApiPrincipalExpiryAlertMetrics metrics =
                new ApiPrincipalExpiryAlertMetrics(provider(registry));

        metrics.recordReconcile("sent", "post-commit");
        metrics.recordReconcile("sent", "post-commit");
        metrics.recordReconcile(null, "  ");
        metrics.recordReconcile("suppressed", "pre-commit");

        assertEquals(2.0, registry.get("rag.api.principal.expiry.alert.reconcile")
                .tag("outcome", "sent")
                .tag("phase", "post-commit")
                .counter().count());
        assertEquals(1.0, registry.get("rag.api.principal.expiry.alert.reconcile")
                .tag("outcome", "NONE")
                .tag("phase", "NONE")
                .counter().count());
        assertEquals(1.0, registry.get("rag.api.principal.expiry.alert.reconcile")
                .tag("outcome", "suppressed")
                .tag("phase", "pre-commit")
                .counter().count());
    }

    @Test
    void scanTruncatedCounterIncrements() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ApiPrincipalExpiryAlertMetrics metrics =
                new ApiPrincipalExpiryAlertMetrics(provider(registry));

        metrics.recordScanTruncated();
        metrics.recordScanTruncated();

        assertEquals(2.0, registry.get("rag.api.principal.expiry.alert.scan.truncated")
                .counter().count());
    }

    @Test
    void missingRegistryMakesAllCallsNoOp() {
        ApiPrincipalExpiryAlertMetrics metrics =
                new ApiPrincipalExpiryAlertMetrics(provider(null));

        metrics.recordReconcile("sent", "post-commit");
        metrics.recordScanTruncated();
    }
}
