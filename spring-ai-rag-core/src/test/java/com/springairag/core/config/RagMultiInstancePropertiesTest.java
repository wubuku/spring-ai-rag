package com.springairag.core.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 多实例启动断言的单元测试：只拒绝会被副本数放大的本地限流组合。
 */
class RagMultiInstancePropertiesTest {

    private static RagRateLimitProperties rateLimit(boolean enabled, String backend) {
        RagRateLimitProperties properties = new RagRateLimitProperties();
        properties.setEnabled(enabled);
        properties.setBackend(backend);
        return properties;
    }

    @Test
    void disabledDeclarationAllowsLocalRateLimit() {
        RagMultiInstanceProperties multiInstance = new RagMultiInstanceProperties();

        assertFalse(multiInstance.isEnabled());
        assertDoesNotThrow(() -> multiInstance.validate(rateLimit(true, "local")));
    }

    @Test
    void enabledDeclarationAllowsLocalRateLimitWhenRateLimitIsOff() {
        RagMultiInstanceProperties multiInstance = new RagMultiInstanceProperties();
        multiInstance.setEnabled(true);

        assertTrue(multiInstance.isEnabled());
        assertDoesNotThrow(() -> multiInstance.validate(rateLimit(false, "local")));
    }

    @Test
    void enabledDeclarationAllowsSharedPostgresRateLimit() {
        RagMultiInstanceProperties multiInstance = new RagMultiInstanceProperties();
        multiInstance.setEnabled(true);

        assertDoesNotThrow(() -> multiInstance.validate(rateLimit(true, "postgresql")));
    }

    @Test
    void enabledDeclarationRejectsLocalRateLimit() {
        RagMultiInstanceProperties multiInstance = new RagMultiInstanceProperties();
        multiInstance.setEnabled(true);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> multiInstance.validate(rateLimit(true, "local")));
        assertTrue(failure.getMessage().contains("backend=postgresql"));
    }

    @Test
    void nullRateLimitIsTolerated() {
        RagMultiInstanceProperties multiInstance = new RagMultiInstanceProperties();
        multiInstance.setEnabled(true);

        assertDoesNotThrow(() -> multiInstance.validate(null));
    }
}
