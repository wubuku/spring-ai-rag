package com.springairag.core.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ChunkingUpgradeMetricsTest {
    @Test
    void snapshotHasFiniteTagsAndExposesScanFreshness() {
        var registry = new SimpleMeterRegistry();
        try {
            var metrics = new ChunkingUpgradeMetrics(registry);
            assertTrue(Double.isNaN(registry.get("rag.chunking.upgrade.scan.age.seconds").gauge().value()));
            metrics.scanned(Map.of("COOLDOWN", 2L, "FAILED", 1L, "private-doc-id", 99L));
            assertEquals(2, registry.get("rag.chunking.upgrade.tasks").tag("status", "COOLDOWN").gauge().value());
            assertEquals(0, registry.get("rag.chunking.upgrade.tasks").tag("status", "RUNNING").gauge().value());
            assertTrue(registry.get("rag.chunking.upgrade.scan.age.seconds").gauge().value() >= 0);
            metrics.transitioned("COOLDOWN", "TRANSIENT");
            metrics.transitioned("untrusted", "private-provider-message");
            assertEquals(1, registry.get("rag.chunking.upgrade.transitions")
                    .tags("status", "COOLDOWN", "failure_kind", "TRANSIENT").counter().count());
            assertEquals(1, registry.get("rag.chunking.upgrade.transitions")
                    .tags("status", "FAILED", "failure_kind", "UNKNOWN").counter().count());
            assertTrue(registry.getMeters().stream().flatMap(meter -> meter.getId().getTags().stream())
                    .noneMatch(tag -> tag.getValue().contains("private") || tag.getValue().equals("untrusted")));
        } finally {
            registry.close();
        }
    }
}
