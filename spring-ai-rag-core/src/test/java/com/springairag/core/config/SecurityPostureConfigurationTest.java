package com.springairag.core.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.mock.env.MockEnvironment;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 安全关闭时的误用防护回归。
 */
class SecurityPostureConfigurationTest {

    private static RagProperties properties(boolean enabled) {
        RagProperties properties = new RagProperties();
        properties.getSecurity().setEnabled(enabled);
        return properties;
    }

    @Test
    void productionProfileRefusesToStartWhenSecurityDisabled() {
        SecurityPostureConfiguration configuration = new SecurityPostureConfiguration();
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        var runner = configuration.securityPostureStartupCheck(
                properties(false), environment);
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> runner.run(null));
        assertTrue(exception.getMessage().contains("rag.security.enabled=false"));
        assertTrue(exception.getMessage().contains("RAG_SECURITY_ENABLED=true"));
    }

    @Test
    void nonProductionProfileOnlyWarns() {
        SecurityPostureConfiguration configuration = new SecurityPostureConfiguration();
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("postgresql");
        var runner = configuration.securityPostureStartupCheck(
                properties(false), environment);
        assertDoesNotThrow(() -> runner.run(null));
    }

    @Test
    void enabledSecurityIsNeverRejected() {
        SecurityPostureConfiguration configuration = new SecurityPostureConfiguration();
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        var runner = configuration.securityPostureStartupCheck(
                properties(true), environment);
        assertDoesNotThrow(() -> runner.run(null));
    }

    @Test
    void healthIndicatorExposesHighRiskWhenDisabled() {
        HealthIndicator indicator =
                new SecurityPostureConfiguration().ragSecurityPostureHealthIndicator(
                        properties(false));
        Health health = indicator.health();
        // 进程保持 UP：配置风险不应导致编排系统重启健康实例。
        assertEquals(Status.UP, health.getStatus());
        Map<String, Object> details = health.getDetails();
        assertEquals(Boolean.FALSE, details.get("securityEnabled"));
        assertEquals("HIGH", details.get("risk"));
        assertEquals(Boolean.TRUE, details.get("exposedWithoutAuthentication"));
        assertNotNull(details.get("warning"));
    }

    @Test
    void healthIndicatorIsQuietWhenEnabled() {
        HealthIndicator indicator =
                new SecurityPostureConfiguration().ragSecurityPostureHealthIndicator(
                        properties(true));
        Health health = indicator.health();
        assertEquals(Status.UP, health.getStatus());
        assertEquals(Boolean.TRUE, health.getDetails().get("securityEnabled"));
        assertFalse(health.getDetails().containsKey("risk"));
    }
}
