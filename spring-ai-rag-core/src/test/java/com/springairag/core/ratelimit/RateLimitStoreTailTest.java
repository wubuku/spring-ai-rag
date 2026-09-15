package com.springairag.core.ratelimit;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 限流三件套（Batch 402）：PostgresRateLimitStore 的消耗/拒绝/桶
 * 丢失与清理、RateLimitObservability 的固定标签计数、
 * SharedRateLimitMaintenance 的开关/后端门卫与错误观测。
 */
class RateLimitStoreTailTest {

    private JdbcTemplate jdbcTemplate;
    private PostgresRateLimitStore store;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        store = new PostgresRateLimitStore(jdbcTemplate);
    }

    @SuppressWarnings("unchecked")
    private void stubConsume(List<PostgresRateLimitStore.Decision> consumeResult) {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class),
                eq("p1"), eq(60))).thenReturn(consumeResult);
    }

    @SuppressWarnings("unchecked")
    private void stubCurrent(List<PostgresRateLimitStore.Decision> currentResult) {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class),
                eq("p1"))).thenReturn(currentResult);
    }

    private PostgresRateLimitStore.Decision decision(
            boolean allowed, int count, int retryAfter) {
        return new PostgresRateLimitStore.Decision(
                allowed, count, OffsetDateTime.parse(
                        "2026-09-15T00:00:00Z"), retryAfter);
    }

    @Test
    void consumeReturnsAcceptedDecisionDirectly() {
        PostgresRateLimitStore.Decision accepted = decision(true, 7, 42);
        stubConsume(List.of(accepted));

        PostgresRateLimitStore.Decision result =
                store.consume("p1", 60);

        assertTrue(result.allowed());
        assertEquals(7, result.requestCount());
        assertEquals(42, result.retryAfterSeconds());
    }

    @Test
    void consumeFallsBackToCurrentBucketWhenRejected() {
        PostgresRateLimitStore.Decision rejected = decision(false, 60, 1);
        stubConsume(List.of());
        stubCurrent(List.of(rejected));

        PostgresRateLimitStore.Decision result =
                store.consume("p1", 60);

        assertEquals(false, result.allowed());
        assertEquals(60, result.requestCount());
    }

    @Test
    void consumeRejectsVanishedBucket() {
        stubConsume(List.of());
        stubCurrent(List.of());

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> store.consume("p1", 60));
        assertEquals("Rate limit bucket disappeared after a rejected consume",
                error.getMessage());
    }

    @Test
    void cleanupDelegatesRetentionAndBatchSize() {
        when(jdbcTemplate.update(anyString(), eq(1440), eq(5000)))
                .thenReturn(12);

        int removed = store.cleanup(1440, 5000);

        assertEquals(12, removed);
        verify(jdbcTemplate).update(anyString(), eq(1440), eq(5000));
    }

    @Test
    void observabilityCountsDecisionsWithFixedTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RateLimitObservability observability =
                new RateLimitObservability(registry);

        observability.recordDecision("postgresql", "allowed",
                "DATABASE_API_KEY");
        observability.recordDecision("postgresql", "bogus-result",
                "not-a-principal");
        observability.recordDecision("memcached", "rejected", "ip");

        assertEquals(1.0, registry.get("rag.rate_limit.decisions")
                .tag("backend", "postgresql")
                .tag("result", "allowed")
                .tag("principal_type", "DATABASE_API_KEY")
                .counter().count());
        // 非法标签值逐项归一化为 UNKNOWN，合法维度保留原值。
        assertEquals(1.0, registry.get("rag.rate_limit.decisions")
                .tag("backend", "postgresql")
                .tag("result", "UNKNOWN")
                .tag("principal_type", "UNKNOWN")
                .counter().count());
        assertEquals(1.0, registry.get("rag.rate_limit.decisions")
                .tag("backend", "UNKNOWN")
                .tag("result", "rejected")
                .tag("principal_type", "ip")
                .counter().count());
    }

    @Test
    void observabilityCountsCleanupErrorsAndNoopIgnoresNullRegistry() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RateLimitObservability observability =
                new RateLimitObservability(registry);
        observability.recordCleanupError();
        assertEquals(1.0, registry.get("rag.rate_limit.cleanup.errors")
                .tag("backend", "postgresql").counter().count());

        // noop 实例（null registry）不抛异常、不计数。
        RateLimitObservability.noop().recordDecision(
                "postgresql", "allowed", "DATABASE_API_KEY");
        RateLimitObservability.noop().recordCleanupError();
        assertEquals(1, registry.get("rag.rate_limit.cleanup.errors")
                .counters().size());
    }

    @Test
    void maintenanceSkipsWhenDisabledOrNonPostgresBackend() {
        SharedRateLimitMaintenance maintenance = new SharedRateLimitMaintenance(
                properties(false, "postgresql"),
                store,
                mock(RateLimitObservability.class));
        maintenance.cleanup();
        verify(jdbcTemplate, org.mockito.Mockito.never()).update(anyString(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt());

        maintenance = new SharedRateLimitMaintenance(
                properties(true, "local"),
                store,
                mock(RateLimitObservability.class));
        maintenance.cleanup();
        verify(jdbcTemplate, org.mockito.Mockito.never()).update(anyString(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void maintenanceRunsCleanupWithConfiguredBounds() {
        PostgresRateLimitStore realStore = new PostgresRateLimitStore(jdbcTemplate);
        RateLimitObservability observability =
                mock(RateLimitObservability.class);
        SharedRateLimitMaintenance maintenance =
                new SharedRateLimitMaintenance(
                        properties(true, "postgresql"), realStore, observability);

        maintenance.cleanup();

        verify(jdbcTemplate).update(anyString(), eq(1440), eq(10_000));
    }

    @Test
    void maintenanceObservesCleanupFailureWithoutRethrow() {
        when(jdbcTemplate.update(anyString(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt()))
                .thenThrow(new DataAccessResourceFailureException("db down"));
        RateLimitObservability observability =
                mock(RateLimitObservability.class);
        SharedRateLimitMaintenance maintenance =
                new SharedRateLimitMaintenance(
                        properties(true, "postgresql"),
                        new PostgresRateLimitStore(jdbcTemplate),
                        observability);

        maintenance.cleanup();

        verify(observability).recordCleanupError();
    }

    private com.springairag.core.config.RagProperties properties(
            boolean enabled, String backend) {
        com.springairag.core.config.RagProperties properties =
                new com.springairag.core.config.RagProperties();
        properties.getRateLimit().setEnabled(enabled);
        properties.getRateLimit().setBackend(backend);
        return properties;
    }
}
