package com.springairag.core.observability;

import com.springairag.core.observability.IntegrationObservationRepository.Aggregate;
import com.springairag.api.enums.IntegrationObservabilityBucket;
import com.springairag.api.enums.IntegrationOperation;
import com.springairag.core.observability.IntegrationObservationRepository.CollectionAggregate;
import com.springairag.core.observability.IntegrationObservationRepository.DimensionAggregate;
import com.springairag.core.observability.IntegrationObservationRepository.TimelineAggregate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 覆盖集成观测仓储：upsert 聚合分组、聚合行映射与非负守卫、
 * 维度/时间线/集合贡献 SQL 形状、oldestBucket 短路与过期清理。
 */
class IntegrationObservationRepositoryTest {

    private static final Instant FROM = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-09-02T00:00:00Z");

    private JdbcTemplate jdbcTemplate;
    private IntegrationObservationRepository repository;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        repository = new IntegrationObservationRepository(jdbcTemplate);
    }

    private IntegrationObservation observation(int status, long durationMs) {
        return new IntegrationObservation(
                Instant.parse("2026-09-01T10:00:00Z"),
                "environment",
                "root",
                IntegrationOperation.COLLECTION_LOOKUP,
                status,
                durationMs,
                List.of(1L, 2L));
    }

    @Test
    void upsertIgnoresEmptyBatch() {
        repository.upsert(null, 1_000);
        repository.upsert(List.of(), 1_000);

        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void upsertMergesSameRollupKeyIntoSingleOperationAndCollectionRow()
            throws Exception {
        PreparedStatement statement = mock(PreparedStatement.class);
        ArgumentCaptor<BatchPreparedStatementSetter> batches =
                ArgumentCaptor.forClass(BatchPreparedStatementSetter.class);
        when(jdbcTemplate.batchUpdate(anyString(),
                batches.capture())).thenReturn(new int[]{1});

        repository.upsert(List.of(
                observation(200, 120),
                observation(200, 80)), 1_000);

        assertEquals(2, batches.getAllValues().size());
        // 相同 bucket/principal/operation/status 合并为 1 行操作汇总。
        assertEquals(1, batches.getAllValues().get(0).getBatchSize());
        batches.getAllValues().get(0).setValues(statement, 0);
        // 参数 5 是 httpStatus，参数 6 是合并后的请求计数。
        verify(statement).setInt(5, 200);
        verify(statement).setLong(6, 2L);
        // 集合侧按两个 collectionId 展开为两行（每键一条）。
        assertEquals(2, batches.getAllValues().get(1).getBatchSize());
    }

    @Test
    void upsertGroupsDistinctHttpStatusesSeparately() {
        ArgumentCaptor<BatchPreparedStatementSetter> batches =
                ArgumentCaptor.forClass(BatchPreparedStatementSetter.class);
        when(jdbcTemplate.batchUpdate(anyString(), batches.capture()))
                .thenReturn(new int[]{1});

        repository.upsert(List.of(
                observation(200, 10),
                observation(500, 20)), 1_000);

        assertEquals(2, batches.getAllValues().get(0).getBatchSize());
    }

    @Test
    void totalsShortCircuitsEmptyCollectionScopeWithoutQuery() {
        Aggregate aggregate = repository.totals(FROM, TO, null, null, null,
                true, List.of());

        assertEquals(BigInteger.ZERO, aggregate.requestCount());
        verifyNoInteractions(jdbcTemplate);
    }

    private ResultSet stubAggregateRow() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getBigDecimal("request_count")).thenReturn(new BigDecimal("20"));
        when(rs.getBigDecimal("duration_sum_ms")).thenReturn(new BigDecimal("900"));
        when(rs.getBigDecimal("duration_max_ms")).thenReturn(new BigDecimal("250"));
        String[] buckets = {"le_25_ms_count", "le_50_ms_count", "le_100_ms_count",
                "le_250_ms_count", "le_500_ms_count", "le_1000_ms_count",
                "le_2500_ms_count", "le_5000_ms_count", "over_5000_ms_count"};
        long[] values = {5, 8, 12, 15, 17, 18, 19, 20, 0};
        for (int index = 0; index < buckets.length; index++) {
            when(rs.getBigDecimal(buckets[index]))
                    .thenReturn(new BigDecimal(values[index]));
        }
        return rs;
    }

    @Test
    void totalsMapsAggregateRow() throws Exception {
        ArgumentCaptor<RowMapper<Aggregate>> mapper =
                ArgumentCaptor.forClass(RowMapper.class);
        when(jdbcTemplate.queryForObject(anyString(), mapper.capture(),
                any(Object[].class))).thenAnswer(invocation -> null);

        repository.totals(FROM, TO, null, null, null);

        Aggregate aggregate = mapper.getValue().mapRow(stubAggregateRow(), 0);
        assertEquals(BigInteger.valueOf(20), aggregate.requestCount());
        assertEquals(new BigDecimal("900"), aggregate.durationSumMs());
        assertEquals(BigInteger.valueOf(250), aggregate.durationMaxMs());
        assertEquals(BigInteger.valueOf(5), aggregate.le25());
        assertEquals(BigInteger.ZERO, aggregate.over5000());
    }

    @Test
    void aggregateMappingRejectsNegativeAndFractionalCounts() throws Exception {
        ArgumentCaptor<RowMapper<Aggregate>> mapper =
                ArgumentCaptor.forClass(RowMapper.class);
        when(jdbcTemplate.queryForObject(anyString(), mapper.capture(),
                any(Object[].class))).thenAnswer(invocation -> null);
        repository.totals(FROM, TO, null, null, null);

        ResultSet negative = mock(ResultSet.class);
        when(negative.getBigDecimal(anyString())).thenReturn(BigDecimal.ONE);
        when(negative.getBigDecimal("request_count"))
                .thenReturn(new BigDecimal("-1"));
        assertThrows(IllegalStateException.class,
                () -> mapper.getValue().mapRow(negative, 0));

        ResultSet fractional = mock(ResultSet.class);
        when(fractional.getBigDecimal(anyString())).thenReturn(BigDecimal.ONE);
        when(fractional.getBigDecimal("request_count"))
                .thenReturn(new BigDecimal("1.5"));
        assertThrows(IllegalStateException.class,
                () -> mapper.getValue().mapRow(fractional, 0));
    }

    @Test
    void dimensionQueriesUseTheirDimensionColumns() {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        when(jdbcTemplate.query(sql.capture(), any(RowMapper.class),
                any(Object[].class))).thenReturn(List.of());

        repository.byStatus(FROM, TO, null, null, null);
        assertTrue(sql.getValue().contains("status AS dimension_key"));

        repository.byOperation(FROM, TO, null, null, null);
        assertTrue(sql.getValue().contains("operation AS dimension_key"));
    }

    @Test
    void timelineGroupsByUtcDayForDayBucket() {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        when(jdbcTemplate.query(sql.capture(), any(RowMapper.class),
                any(Object[].class))).thenReturn(List.of());

        repository.timeline(FROM, TO, null, null, null,
                IntegrationObservabilityBucket.DAY);

        assertTrue(sql.getValue().contains("AT TIME ZONE 'UTC')::date"));
    }

    @Test
    void timelineMapsTimestampBucketToInstantString() throws Exception {
        ArgumentCaptor<RowMapper<TimelineAggregate>> mapper =
                ArgumentCaptor.forClass(RowMapper.class);
        when(jdbcTemplate.query(anyString(), mapper.capture(),
                any(Object[].class))).thenAnswer(invocation -> List.of());
        repository.timeline(FROM, TO, null, null, null,
                IntegrationObservabilityBucket.HOUR);
        ResultSet rs = stubAggregateRow();
        when(rs.getObject("dimension_key"))
                .thenReturn(Timestamp.from(Instant.parse("2026-09-01T10:00:00Z")));

        var timeline = mapper.getValue().mapRow(rs, 0);
        assertEquals("2026-09-01T10:00:00Z", timeline.bucketStart());
        assertEquals(BigInteger.valueOf(20), timeline.aggregate().requestCount());
    }

    @Test
    void collectionContributionsBuildsInClauseAndMapsRows() throws Exception {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<RowMapper<CollectionAggregate>> mapper =
                ArgumentCaptor.forClass(RowMapper.class);
        when(jdbcTemplate.query(sql.capture(), mapper.capture(),
                any(Object[].class))).thenAnswer(invocation -> List.of());

        repository.collectionContributions(FROM, TO, null, null, null,
                List.of(1L, 2L), 5);

        assertTrue(sql.getValue().contains("IN (?,?)"));
        assertTrue(sql.getValue().contains("LIMIT ?"));

        ResultSet rs = stubAggregateRow();
        when(rs.getLong("collection_id")).thenReturn(1L);
        when(rs.getString("collection_key")).thenReturn("kb");
        List<CollectionAggregate> rows = List.of(mapper.getValue().mapRow(rs, 0));
        assertEquals(1, rows.size());
        assertEquals("kb", rows.getFirst().collectionKey());

        assertTrue(repository.collectionContributions(FROM, TO, null, null,
                null, List.of(), 5).isEmpty());
    }

    @Test
    void oldestBucketShortCircuitsAndConvertsTimestamp() throws Exception {
        assertNull(repository.oldestBucket(FROM, TO, null, null, null,
                true, List.of()));

        ArgumentCaptor<RowMapper<Instant>> mapper =
                ArgumentCaptor.forClass(RowMapper.class);
        when(jdbcTemplate.queryForObject(anyString(), mapper.capture(),
                any(Object[].class))).thenAnswer(invocation -> null);
        repository.oldestBucket(FROM, TO, null, null, null);
        ResultSet rs = mock(ResultSet.class);
        when(rs.getTimestamp(1))
                .thenReturn(Timestamp.from(Instant.parse("2026-09-01T08:00:00Z")));

        assertEquals(Instant.parse("2026-09-01T08:00:00Z"),
                mapper.getValue().mapRow(rs, 0));

        when(rs.getTimestamp(1)).thenReturn(null);
        assertNull(mapper.getValue().mapRow(rs, 0));
    }

    @Test
    void deleteExpiredGuardsArgumentsAndSumsBothTables() {
        assertEquals(0, repository.deleteExpired(null, 100, 1_000));
        assertEquals(0, repository.deleteExpired(FROM, 0, 1_000));

        when(jdbcTemplate.update(anyString(),
                any(org.springframework.jdbc.core.PreparedStatementSetter.class)))
                .thenReturn(3)
                .thenReturn(2);

        assertEquals(5, repository.deleteExpired(FROM, 100, 0));
    }
}
