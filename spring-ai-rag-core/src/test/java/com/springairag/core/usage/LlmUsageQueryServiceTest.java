package com.springairag.core.usage;

import com.springairag.api.dto.LlmUsageResponse;
import com.springairag.core.chat.ChatPrincipal;
import com.springairag.core.config.RagProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LlmUsageQueryServiceTest {

    private static final Instant NOW =
            Instant.parse("2026-08-27T09:00:00Z");

    @Mock
    private LlmUsageQueryRepository repository;

    @Mock
    private LlmUsageRecorder recorder;

    private LlmUsageQueryService service;

    @BeforeEach
    void setUp() {
        service = new LlmUsageQueryService(
                repository,
                new RagProperties(),
                recorder,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void stubSuccessfulQuery() {
        when(repository.totals(any(), any(), any()))
                .thenReturn(aggregate(2, 3, 2, 1, 0));
        when(repository.costs(any(), any(), any())).thenReturn(List.of(
                new LlmUsageQueryRepository.CostAggregate(
                        "CONFIGURED_MODEL_COST",
                        new BigDecimal("0.12500000"),
                        3,
                        2)));
        when(repository.byModel(any(), any(), any())).thenReturn(List.of(
                new LlmUsageQueryRepository.DimensionAggregate(
                        "openai/gpt-test",
                        aggregate(2, 3, 2, 1, 0))));
        when(repository.byPurpose(any(), any(), any())).thenReturn(List.of(
                new LlmUsageQueryRepository.DimensionAggregate(
                        "CHAT",
                        aggregate(2, 3, 2, 1, 0))));
        when(repository.byMode(any(), any(), any())).thenReturn(List.of(
                new LlmUsageQueryRepository.DimensionAggregate(
                        "KNOWLEDGE",
                        aggregate(2, 3, 2, 1, 0))));
        when(repository.byDay(any(), any(), any())).thenReturn(List.of(
                new LlmUsageQueryRepository.DimensionAggregate(
                "2026-08-27",
                aggregate(2, 3, 2, 1, 0))));
        when(recorder.lostEvents()).thenReturn(4L);
    }

    @Test
    void defaultsToThirtyInclusiveUtcDaysAndSelfScope() {
        stubSuccessfulQuery();
        LlmUsageResponse response = service.query(
                new ChatPrincipal("db:caller", "DATABASE_API_KEY", false),
                null,
                null,
                null);

        assertEquals(LocalDate.of(2026, 7, 29), response.from());
        assertEquals(LocalDate.of(2026, 8, 27), response.to());
        assertEquals("SELF", response.scope().type());
        assertEquals("db:caller", response.scope().principalId());
        assertEquals(4L, response.localLostEventsSinceStart());
        assertEquals(new BigDecimal("0.12500000"),
                response.costs().getFirst().configuredCost());
        verify(repository).totals(
                eq(Instant.parse("2026-07-29T00:00:00Z")),
                eq(Instant.parse("2026-08-28T00:00:00Z")),
                eq("db:caller"));
    }

    @Test
    void adminCanQueryAllOrOnePrincipal() {
        stubSuccessfulQuery();
        ChatPrincipal admin = new ChatPrincipal(
                "db:admin", "DATABASE_API_KEY", true);

        LlmUsageResponse all = service.query(admin, "2026-08-01", "2026-08-02", null);
        assertEquals("ALL", all.scope().type());
        assertEquals(null, all.scope().principalId());

        LlmUsageResponse one = service.query(
                admin, "2026-08-01", "2026-08-02", "db:target");
        assertEquals("PRINCIPAL", one.scope().type());
        assertEquals("db:target", one.scope().principalId());
        verify(repository).totals(
                eq(Instant.parse("2026-08-01T00:00:00Z")),
                eq(Instant.parse("2026-08-03T00:00:00Z")),
                eq("db:target"));
    }

    @Test
    void normalPrincipalCannotQueryAnotherPrincipal() {
        assertThrows(
                SecurityException.class,
                () -> service.query(
                        new ChatPrincipal("db:caller", "DATABASE_API_KEY", false),
                        "2026-08-01",
                        "2026-08-01",
                        "db:other"));
    }

    @Test
    void rejectsInvalidAndOverlongDateRanges() {
        ChatPrincipal caller = new ChatPrincipal(
                "db:caller", "DATABASE_API_KEY", false);

        assertThrows(
                IllegalArgumentException.class,
                () -> service.query(caller, "2026-08-01", "not-a-date", null));
        assertThrows(
                IllegalArgumentException.class,
                () -> service.query(caller, "2026-08-02", "2026-08-01", null));
        assertThrows(
                IllegalArgumentException.class,
                () -> service.query(caller, "2024-12-31", "2026-01-01", null));
        assertThrows(
                IllegalArgumentException.class,
                () -> service.query(caller, "2026-08-01", "2026-08-01",
                        "contains\nnewline"));
    }

    private static LlmUsageQueryRepository.UsageAggregate aggregate(
            long executions,
            long invocations,
            long succeeded,
            long failed,
            long cancelled) {
        return new LlmUsageQueryRepository.UsageAggregate(
                executions,
                invocations,
                succeeded,
                failed,
                cancelled,
                new BigDecimal("10"),
                new BigDecimal("20"),
                new BigDecimal("30"),
                2,
                1,
                1,
                1);
    }
}
