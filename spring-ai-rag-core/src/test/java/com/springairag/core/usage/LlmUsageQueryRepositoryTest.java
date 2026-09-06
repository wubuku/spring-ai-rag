package com.springairag.core.usage;

import com.springairag.core.usage.LlmUsageQueryRepository.CostAggregate;
import com.springairag.core.usage.LlmUsageQueryRepository.DimensionAggregate;
import com.springairag.core.usage.LlmUsageQueryRepository.UsageAggregate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 覆盖只读用量聚合仓储：principal 过滤 SQL 拼装、行映射、
 * LocalDate 维度键转换与非负守卫。
 */
class LlmUsageQueryRepositoryTest {

    private static final Instant FROM = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-09-02T00:00:00Z");

    private JdbcTemplate jdbcTemplate;
    private LlmUsageQueryRepository repository;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        repository = new LlmUsageQueryRepository(jdbcTemplate);
    }

    @SuppressWarnings("unchecked")
    private <T> RowMapper<T> capturedMapper() {
        ArgumentCaptor<RowMapper<T>> captor =
                ArgumentCaptor.forClass(RowMapper.class);
        org.mockito.Mockito.verify(jdbcTemplate)
                .queryForObject(anyString(), captor.capture(), any(Object[].class));
        return captor.getValue();
    }

    private ResultSet aggregateRow() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getLong("logical_execution_count")).thenReturn(9L);
        when(rs.getLong("invocation_count")).thenReturn(20L);
        when(rs.getLong("succeeded_count")).thenReturn(18L);
        when(rs.getLong("failed_count")).thenReturn(1L);
        when(rs.getLong("cancelled_count")).thenReturn(1L);
        when(rs.getBigDecimal("prompt_tokens")).thenReturn(new BigDecimal("100"));
        when(rs.getBigDecimal("completion_tokens")).thenReturn(new BigDecimal("50"));
        when(rs.getBigDecimal("total_tokens")).thenReturn(new BigDecimal("150"));
        when(rs.getLong("usage_available_count")).thenReturn(19L);
        when(rs.getLong("usage_unavailable_count")).thenReturn(1L);
        when(rs.getLong("pricing_unavailable_count")).thenReturn(2L);
        when(rs.getLong("cost_unavailable_count")).thenReturn(3L);
        return rs;
    }

    @Test
    void totalsFiltersWithoutPrincipalWhenNull() {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        when(jdbcTemplate.queryForObject(sql.capture(), any(RowMapper.class),
                args.capture())).thenReturn(null);

        repository.totals(FROM, TO, null);

        assertFalse(sql.getValue().contains("owner_principal_id"));
        assertEquals(2, args.getValue().length);
        assertEquals(Timestamp.from(FROM), args.getValue()[0]);
    }

    @Test
    void totalsAppendsPrincipalFilterWhenProvided() {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        when(jdbcTemplate.queryForObject(sql.capture(), any(RowMapper.class),
                args.capture())).thenReturn(null);

        repository.totals(FROM, TO, "principal-1");

        assertTrue(sql.getValue().contains("owner_principal_id = ?"));
        assertEquals(3, args.getValue().length);
        assertEquals("principal-1", args.getValue()[2]);
    }

    @Test
    void totalsMapsAggregateRowWithCountsTokensAndGaps() throws Exception {
        when(jdbcTemplate.queryForObject(anyString(), any(RowMapper.class),
                any(Object[].class))).thenAnswer((InvocationOnMock invocation) -> null);

        repository.totals(FROM, TO, null);
        UsageAggregate aggregate =
                this.<UsageAggregate>capturedMapper().mapRow(aggregateRow(), 0);

        assertEquals(9L, aggregate.logicalExecutionCount());
        assertEquals(20L, aggregate.invocationCount());
        assertEquals(18L, aggregate.succeededCount());
        assertEquals(1L, aggregate.failedCount());
        assertEquals(1L, aggregate.cancelledCount());
        assertEquals(new BigDecimal("150"), aggregate.totalTokens());
        assertEquals(19L, aggregate.usageAvailableCount());
        assertEquals(3L, aggregate.costUnavailableCount());
    }

    @Test
    void aggregateMappingRejectsNegativeCountsAndNullDecimals() throws Exception {
        when(jdbcTemplate.queryForObject(anyString(), any(RowMapper.class),
                any(Object[].class))).thenAnswer(invocation -> null);
        repository.totals(FROM, TO, null);
        RowMapper<UsageAggregate> mapper = this.<UsageAggregate>capturedMapper();

        ResultSet negative = mock(ResultSet.class);
        when(negative.getLong("logical_execution_count")).thenReturn(-1L);
        assertThrows(IllegalStateException.class, () -> mapper.mapRow(negative, 0));

        ResultSet nullDecimal = mock(ResultSet.class);
        when(nullDecimal.getLong(anyString())).thenReturn(0L);
        when(nullDecimal.getBigDecimal("prompt_tokens")).thenReturn(null);
        assertThrows(IllegalStateException.class, () -> mapper.mapRow(nullDecimal, 0));
    }

    @Test
    void byModelQueriesModelDimensionWithPrincipalFilter() {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        when(jdbcTemplate.query(sql.capture(), any(RowMapper.class),
                any(Object[].class))).thenReturn(List.of());

        repository.byModel(FROM, TO, "principal-1");

        assertTrue(sql.getValue().contains("model_ref AS dimension_key"));
        assertTrue(sql.getValue().contains("GROUP BY model_ref"));
        assertTrue(sql.getValue().contains("owner_principal_id = ?"));
    }

    @Test
    void byPurposeAndByModeUseOrderedCaseExpressions() {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        when(jdbcTemplate.query(sql.capture(), any(RowMapper.class),
                any(Object[].class))).thenReturn(List.of());

        repository.byPurpose(FROM, TO, null);
        assertTrue(sql.getValue().contains("purpose AS dimension_key"));
        assertTrue(sql.getValue().contains("WHEN 'QUERY_TRANSFORM' THEN 2"));

        repository.byMode(FROM, TO, null);
        assertTrue(sql.getValue().contains("chat_mode AS dimension_key"));
        assertTrue(sql.getValue().contains("WHEN 'AGENT' THEN 3"));
    }

    @Test
    void byDayGroupsByUtcDateAndConvertsLocalDateKeys() throws Exception {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<RowMapper<DimensionAggregate>> mapper =
                ArgumentCaptor.forClass(RowMapper.class);
        when(jdbcTemplate.query(sql.capture(), mapper.capture(),
                any(Object[].class))).thenAnswer(invocation -> {
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getObject("dimension_key")).thenReturn(LocalDate.of(2026, 9, 1));
                    when(rs.getLong(anyString())).thenReturn(1L);
                    when(rs.getBigDecimal(anyString())).thenReturn(BigDecimal.ONE);
                    return List.of(mapper.getValue().mapRow(rs, 0));
                });

        List<DimensionAggregate> rows = repository.byDay(FROM, TO, null);

        assertTrue(sql.getValue().contains("AT TIME ZONE 'UTC'"));
        assertEquals(1, rows.size());
        assertEquals("2026-09-01", rows.getFirst().dimension());
    }

    @Test
    void costsMapsUnitAggregatesAndRejectsBlankUnits() throws Exception {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<RowMapper<CostAggregate>> mapper =
                ArgumentCaptor.forClass(RowMapper.class);
        when(jdbcTemplate.query(sql.capture(), mapper.capture(),
                any(Object[].class))).thenAnswer(invocation -> List.of());

        repository.costs(FROM, TO, null);

        assertTrue(sql.getValue().contains("GROUP BY cost_unit"));
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("unit")).thenReturn("USD");
        when(rs.getBigDecimal("configured_cost")).thenReturn(new BigDecimal("4.50"));
        when(rs.getLong("invocation_count")).thenReturn(12L);
        when(rs.getLong("cost_available_count")).thenReturn(10L);

        CostAggregate cost = mapper.getValue().mapRow(rs, 0);
        assertEquals("USD", cost.unit());
        assertEquals(new BigDecimal("4.50"), cost.configuredCost());
        assertEquals(12L, cost.invocationCount());

        when(rs.getString("unit")).thenReturn(" ");
        assertThrows(IllegalStateException.class, () -> mapper.getValue().mapRow(rs, 0));
    }
}
