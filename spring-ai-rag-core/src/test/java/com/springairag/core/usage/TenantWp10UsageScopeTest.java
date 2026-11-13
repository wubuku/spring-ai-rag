package com.springairag.core.usage;

import com.springairag.api.dto.LlmUsageResponse;
import com.springairag.core.chat.ChatPrincipal;
import com.springairag.core.tenant.TenantContext;
import com.springairag.core.tenant.TenantMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WP10 用量租户聚合隔离测试（与 {@code TenantWp10ContractTest} 同包分层）。
 */
@DisplayName("WP10 tenant usage scope isolation")
class TenantWp10UsageScopeTest {

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    private LlmUsageQueryService service(LlmUsageQueryRepository repository) {
        return new LlmUsageQueryService(
                repository, new com.springairag.core.config.RagProperties(), null,
                Clock.fixed(Instant.parse("2026-08-27T09:00:00Z"), ZoneOffset.UTC));
    }

    private LlmUsageQueryRepository emptyRepository() {
        LlmUsageQueryRepository repository = mock(LlmUsageQueryRepository.class);
        when(repository.totals(any(), any(), any()))
                .thenReturn(new LlmUsageQueryRepository.UsageAggregate(
                        0, 0, 0, 0, 0, java.math.BigDecimal.ZERO,
                        java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO,
                        0, 0, 0, 0));
        when(repository.costs(any(), any(), any())).thenReturn(List.of());
        when(repository.byModel(any(), any(), any())).thenReturn(List.of());
        when(repository.byPurpose(any(), any(), any())).thenReturn(List.of());
        when(repository.byMode(any(), any(), any())).thenReturn(List.of());
        when(repository.byDay(any(), any(), any())).thenReturn(List.of());
        return repository;
    }

    @Test
    @DisplayName("用量：tenantScope=current 只返回当前租户聚合，不返回跨租户数据")
    void tenantUsageScopeReturnsOnlyCurrentTenant() {
        LlmUsageQueryRepository repository = mock(LlmUsageQueryRepository.class);
        when(repository.totals(any(), any(), any()))
                .thenReturn(new LlmUsageQueryRepository.UsageAggregate(
                        1, 2, 2, 0, 0, java.math.BigDecimal.TEN,
                        java.math.BigDecimal.TEN, java.math.BigDecimal.TEN,
                        2, 0, 0, 0));
        when(repository.costs(any(), any(), any())).thenReturn(List.of());
        when(repository.byModel(any(), any(), any())).thenReturn(List.of());
        when(repository.byPurpose(any(), any(), any())).thenReturn(List.of());
        when(repository.byMode(any(), any(), any())).thenReturn(List.of());
        when(repository.byDay(any(), any(), any())).thenReturn(List.of());
        when(repository.tenantTotals(any(), any(), eq("acme")))
                .thenReturn(new LlmUsageQueryRepository.UsageAggregate(
                        3, 4, 4, 0, 0, java.math.BigDecimal.TEN,
                        java.math.BigDecimal.TEN, java.math.BigDecimal.TEN,
                        4, 0, 0, 0));

        LlmUsageQueryService service = new LlmUsageQueryService(
                repository, new com.springairag.core.config.RagProperties(), null,
                Clock.fixed(Instant.parse("2026-08-27T09:00:00Z"), ZoneOffset.UTC));

        TenantContext.set(new TenantContext("acme", "db:caller", TenantMode.STRICT));
        LlmUsageResponse response = service.query(
                new ChatPrincipal("db:caller", "DATABASE_API_KEY", false),
                "2026-08-01", "2026-08-02", null, "current");

        assertEquals("acme", response.tenantTotals().tenantId());
        assertEquals(4L, response.tenantTotals().totals().invocationCount());
        verify(repository).tenantTotals(any(), any(), eq("acme"));
        verify(repository, never()).tenantTotals(any(), any(), eq("other"));
    }

    @Test
    @DisplayName("用量：不带 tenantScope 时不返回租户聚合（向后兼容）")
    void tenantUsageProjectionIsAbsentByDefault() {
        LlmUsageQueryRepository repository = mock(LlmUsageQueryRepository.class);
        when(repository.totals(any(), any(), any()))
                .thenReturn(new LlmUsageQueryRepository.UsageAggregate(
                        0, 0, 0, 0, 0, java.math.BigDecimal.ZERO,
                        java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO,
                        0, 0, 0, 0));
        when(repository.costs(any(), any(), any())).thenReturn(List.of());
        when(repository.byModel(any(), any(), any())).thenReturn(List.of());
        when(repository.byPurpose(any(), any(), any())).thenReturn(List.of());
        when(repository.byMode(any(), any(), any())).thenReturn(List.of());
        when(repository.byDay(any(), any(), any())).thenReturn(List.of());

        LlmUsageQueryService service = new LlmUsageQueryService(
                repository, new com.springairag.core.config.RagProperties(), null,
                Clock.fixed(Instant.parse("2026-08-27T09:00:00Z"), ZoneOffset.UTC));

        LlmUsageResponse response = service.query(
                new ChatPrincipal("db:caller", "DATABASE_API_KEY", false),
                "2026-08-01", "2026-08-02", null);
        assertNull(response.tenantTotals());
        verify(repository, never()).tenantTotals(any(), any(), anyString());
    }

    @Test
    @DisplayName("用量：无租户上下文时 tenantScope fail-closed，不返回任何租户聚合")
    void tenantUsageWithoutContextIsRejected() {
        LlmUsageQueryRepository repository = mock(LlmUsageQueryRepository.class);
        when(repository.totals(any(), any(), any()))
                .thenReturn(new LlmUsageQueryRepository.UsageAggregate(
                        0, 0, 0, 0, 0, java.math.BigDecimal.ZERO,
                        java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO,
                        0, 0, 0, 0));
        when(repository.costs(any(), any(), any())).thenReturn(List.of());
        when(repository.byModel(any(), any(), any())).thenReturn(List.of());
        when(repository.byPurpose(any(), any(), any())).thenReturn(List.of());
        when(repository.byMode(any(), any(), any())).thenReturn(List.of());
        when(repository.byDay(any(), any(), any())).thenReturn(List.of());

        LlmUsageQueryService service = new LlmUsageQueryService(
                repository, new com.springairag.core.config.RagProperties(), null,
                Clock.fixed(Instant.parse("2026-08-27T09:00:00Z"), ZoneOffset.UTC));

        assertThrows(SecurityException.class, () -> service.query(
                new ChatPrincipal("db:caller", "DATABASE_API_KEY", false),
                "2026-08-01", "2026-08-02", null, "current"));
    }
}
