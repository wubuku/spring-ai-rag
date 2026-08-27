package com.springairag.core.apikeyalert;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** 到期告警只记录低基数结果与阶段指标。 */
@Component
public class ApiPrincipalExpiryAlertMetrics {

    private final MeterRegistry registry;

    public ApiPrincipalExpiryAlertMetrics(
            ObjectProvider<MeterRegistry> registries) {
        this.registry = registries.getIfAvailable();
    }

    public void recordReconcile(String outcome, String phase) {
        if (registry == null) {
            return;
        }
        Counter.builder("rag.api.principal.expiry.alert.reconcile")
                .tag("outcome", normalize(outcome))
                .tag("phase", normalize(phase))
                .register(registry)
                .increment();
    }

    public void recordScanTruncated() {
        if (registry == null) {
            return;
        }
        Counter.builder("rag.api.principal.expiry.alert.scan.truncated")
                .register(registry)
                .increment();
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? "NONE" : value;
    }
}
