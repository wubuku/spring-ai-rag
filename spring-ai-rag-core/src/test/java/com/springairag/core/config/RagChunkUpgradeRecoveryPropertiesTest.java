package com.springairag.core.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RagChunkUpgradeRecoveryPropertiesTest {
    @Test
    void defaultRecoveryIsEnabledAndBounded() {
        RagChunkProperties chunk = new RagChunkProperties();
        assertTrue(chunk.isAutoUpgradeEnabled());
        assertEquals(Duration.ofHours(1), chunk.getUpgradeRecovery().getCooldown());
        assertEquals(Duration.ofHours(24), chunk.getUpgradeRecovery().getMaxCooldown());
        assertEquals(12, chunk.getUpgradeRecovery().getRetryDocumentsPerHour());
        assertDoesNotThrow(chunk::validate);
    }

    @Test
    void configurationBindingDoesNotNeedOperatorOverrides() {
        var source = new MapConfigurationPropertySource(Map.of(
                "rag.chunk.upgrade-recovery.cooldown", "PT5M",
                "rag.chunk.upgrade-recovery.max-cooldown", "PT2H",
                "rag.chunk.upgrade-recovery.retry-documents-per-hour", "2"));
        RagProperties properties = new Binder(source).bind("rag", Bindable.of(RagProperties.class)).get();
        assertEquals(Duration.ofMinutes(5), properties.getChunk().getUpgradeRecovery().getCooldown());
        assertEquals(Duration.ofHours(2), properties.getChunk().getUpgradeRecovery().getMaxCooldown());
        assertEquals(2, properties.getChunk().getUpgradeRecovery().getRetryDocumentsPerHour());
        properties.getChunk().validate();
    }

    @Test
    void invalidBudgetsFailEarly() {
        RagChunkUpgradeRecoveryProperties properties = new RagChunkUpgradeRecoveryProperties();
        for (Duration invalid : new Duration[]{null, Duration.ZERO, Duration.ofSeconds(59), Duration.ofHours(25)}) {
            properties.setCooldown(invalid);
            assertThrows(IllegalArgumentException.class, properties::validate);
        }
        properties.setCooldown(Duration.ofHours(1));
        for (Duration invalid : new Duration[]{null, Duration.ZERO, Duration.ofMinutes(59), Duration.ofDays(8)}) {
            properties.setMaxCooldown(invalid);
            assertThrows(IllegalArgumentException.class, properties::validate);
        }
        properties.setMaxCooldown(Duration.ofDays(7));
        for (int invalid : new int[]{0, -1, 1001}) {
            properties.setRetryDocumentsPerHour(invalid);
            assertThrows(IllegalArgumentException.class, properties::validate);
        }
        properties.setRetryDocumentsPerHour(1000);
        assertDoesNotThrow(properties::validate);
        RagChunkProperties chunk = new RagChunkProperties();
        chunk.setUpgradeRecovery(null);
        assertThrows(IllegalArgumentException.class, chunk::validate);
    }
}
