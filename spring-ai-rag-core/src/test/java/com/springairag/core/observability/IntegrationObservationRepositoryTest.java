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
import static org.mockito.Mockito.times;
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

        assertEquals(5, repository.deleteExpired(FROM, 100, 1_000));
    }

    // ── collection 作用域读变体与守卫（Batch 359）─────────────────────

    @Test
    void totalsCollectionScopedQueriesObservationJoinWithAllFilters()
            throws Exception {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<RowMapper<Aggregate>> mapper =
                ArgumentCaptor.forClass(RowMapper.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        when(jdbcTemplate.queryForObject(sql.capture(), mapper.capture(),
                args.capture())).thenAnswer(invocation -> null);

        // stub 返回 null：SQL/参数经捕获断言，聚合行由手工 mapRow 执行。
        repository.totals(FROM, TO, "environment", "root",
                IntegrationOperation.COLLECTION_LOOKUP, true, List.of(1L, 2L));
        Aggregate aggregate = mapper.getValue()
                .mapRow(stubAggregateRow(), 0);

        assertTrue(sql.getValue()
                .contains("rag_api_collection_operation_hourly observation"));
        // 作用域读用带 observation. 前缀的限定聚合列。
        assertTrue(sql.getValue().contains("observation.request_count"));
        assertTrue(sql.getValue()
                .contains("observation.principal_type = ?"));
        assertTrue(sql.getValue().contains("observation.operation = ?"));
        assertTrue(sql.getValue().contains("observation.collection_id IN (?,?)"));
        // 参数顺序：from → to → principalType → principalRef →
        // operation → collectionIds。
        assertEquals(FROM, ((Timestamp) args.getValue()[0]).toInstant());
        assertEquals("environment", args.getValue()[2]);
        assertEquals("root", args.getValue()[3]);
        assertEquals("COLLECTION_LOOKUP", args.getValue()[4]);
        assertEquals(1L, args.getValue()[5]);
        assertEquals(2L, args.getValue()[6]);
        assertEquals(BigInteger.valueOf(20), aggregate.requestCount());
    }

    @Test
    void byStatusAndByOperationCollectionScopedShortCircuitAndQualifiedColumns()
            throws Exception {
        assertTrue(repository.byStatus(FROM, TO, null, null, null,
                true, List.of()).isEmpty());
        assertTrue(repository.byOperation(FROM, TO, null, null, null,
                true, List.of()).isEmpty());
        verifyNoInteractions(jdbcTemplate);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<RowMapper<DimensionAggregate>> mapper =
                ArgumentCaptor.forClass(RowMapper.class);
        when(jdbcTemplate.query(sql.capture(), mapper.capture(),
                any(Object[].class))).thenAnswer(invocation -> List.of());

        repository.byStatus(FROM, TO, null, null, null, true, List.of(1L));
        assertTrue(sql.getValue()
                .contains("observation.http_status AS dimension_key"));
        ResultSet rs = stubAggregateRow();
        when(rs.getObject("dimension_key")).thenReturn("200");
        DimensionAggregate dimension = mapper.getValue().mapRow(rs, 0);
        assertEquals("200", dimension.dimension());

        repository.byOperation(FROM, TO, null, null, null, true, List.of(1L));
        assertTrue(sql.getValue()
                .contains("observation.operation AS dimension_key"));
    }

    @Test
    void timelineCollectionScopedShortCircuitQualifiedColumnAndKeyTypes()
            throws Exception {
        assertTrue(repository.timeline(FROM, TO, null, null, null,
                IntegrationObservabilityBucket.DAY, true, List.of()).isEmpty());
        verifyNoInteractions(jdbcTemplate);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<RowMapper<TimelineAggregate>> mapper =
                ArgumentCaptor.forClass(RowMapper.class);
        when(jdbcTemplate.query(sql.capture(), mapper.capture(),
                any(Object[].class))).thenAnswer(invocation -> List.of());

        repository.timeline(FROM, TO, null, null, null,
                IntegrationObservabilityBucket.DAY, true, List.of(1L));
        assertTrue(sql.getValue().contains(
                "observation.bucket_start AT TIME ZONE 'UTC')::date"));

        // dimension_key 的其余类型分支：OffsetDateTime / Instant /
        // LocalDate / 其他对象（requiredText 文本化）。
        ResultSet offsetRow = stubAggregateRow();
        when(offsetRow.getObject("dimension_key"))
                .thenReturn(java.time.OffsetDateTime.parse(
                        "2026-09-01T10:00+01:00"));
        assertEquals("2026-09-01T09:00:00Z",
                mapper.getValue().mapRow(offsetRow, 0).bucketStart());

        ResultSet instantRow = stubAggregateRow();
        when(instantRow.getObject("dimension_key"))
                .thenReturn(Instant.parse("2026-09-01T10:00:00Z"));
        assertEquals("2026-09-01T10:00:00Z",
                mapper.getValue().mapRow(instantRow, 0).bucketStart());

        ResultSet dateRow = stubAggregateRow();
        when(dateRow.getObject("dimension_key"))
                .thenReturn(java.time.LocalDate.parse("2026-09-01"));
        assertEquals("2026-09-01",
                mapper.getValue().mapRow(dateRow, 0).bucketStart());

        ResultSet otherRow = stubAggregateRow();
        when(otherRow.getObject("dimension_key")).thenReturn(42);
        assertEquals("42", mapper.getValue().mapRow(otherRow, 0).bucketStart());
    }

    @Test
    void oldestBucketCollectionScopedUsesQualifiedBucketColumn() {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        when(jdbcTemplate.queryForObject(sql.capture(),
                any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> null);

        assertNull(repository.oldestBucket(FROM, TO, null, null, null,
                true, List.of(1L)));

        assertTrue(sql.getValue().contains("MIN(observation.bucket_start)"));
    }

    @Test
    void deleteExpiredAppliesSetterTimeoutTimestampAndBatchSize()
            throws Exception {
        PreparedStatement statement = mock(PreparedStatement.class);
        ArgumentCaptor<org.springframework.jdbc.core.PreparedStatementSetter>
                setters = ArgumentCaptor.forClass(
                        org.springframework.jdbc.core.PreparedStatementSetter.class);
        when(jdbcTemplate.update(anyString(), setters.capture()))
                .thenReturn(3)
                .thenReturn(2);

        assertEquals(5, repository.deleteExpired(FROM, 100, 0));

        // 两段 DELETE 都经 setter 绑定：超时下限 1 秒、cutoff、批大小。
        // （Framework 7 起 PreparedStatementSetter.setValues 只有单参。）
        for (var setter : setters.getAllValues()) {
            setter.setValues(statement);
        }
        verify(statement, times(2)).setQueryTimeout(1);
        verify(statement, times(2)).setTimestamp(1, Timestamp.from(FROM));
        verify(statement, times(2)).setInt(2, 100);
    }

    @Test
    void upsertSkipsCollectionRollupWhenObservationHasNoCollections() {
        ArgumentCaptor<BatchPreparedStatementSetter> batches =
                ArgumentCaptor.forClass(BatchPreparedStatementSetter.class);
        when(jdbcTemplate.batchUpdate(anyString(), batches.capture()))
                .thenReturn(new int[]{1});

        repository.upsert(List.of(new IntegrationObservation(
                Instant.parse("2026-09-01T10:00:00Z"),
                "environment", "root",
                IntegrationOperation.COLLECTION_LOOKUP, 200, 30,
                List.of())), 1_000);

        // 无授权集合 → 仅操作侧批写入，集合侧空分组直接跳过。
        assertEquals(1, batches.getAllValues().size());
    }

    @Test
    void upsertCollectionSetterBindsCollectionIdAndOver5000Bucket()
            throws Exception {
        PreparedStatement statement = mock(PreparedStatement.class);
        ArgumentCaptor<BatchPreparedStatementSetter> batches =
                ArgumentCaptor.forClass(BatchPreparedStatementSetter.class);
        when(jdbcTemplate.batchUpdate(anyString(), batches.capture()))
                .thenReturn(new int[]{1});

        repository.upsert(List.of(observation(200, 6_000)), 1_000);

        // 集合侧 setter：参数 4 是 collectionId（操作侧无此参数）。
        batches.getAllValues().get(1).setValues(statement, 0);
        verify(statement).setLong(4, 1L);
        // 时延 6000ms → over_5000 计数 1（集合侧参数 18、操作侧 17）。
        verify(statement).setLong(18, 1L);

        PreparedStatement operationStatement = mock(PreparedStatement.class);
        batches.getAllValues().get(0).setValues(operationStatement, 0);
        verify(operationStatement).setLong(17, 1L);
        verify(operationStatement).setLong(16, 0L);
    }

    @Test
    void mappingRejectsNegativeDurationAndBlankDimensionKey() throws Exception {
        ArgumentCaptor<RowMapper<Aggregate>> totalsMapper =
                ArgumentCaptor.forClass(RowMapper.class);
        when(jdbcTemplate.queryForObject(anyString(), totalsMapper.capture(),
                any(Object[].class))).thenAnswer(invocation -> null);
        repository.totals(FROM, TO, null, null, null);

        ResultSet negativeDuration = mock(ResultSet.class);
        when(negativeDuration.getBigDecimal(anyString()))
                .thenReturn(BigDecimal.ONE);
        when(negativeDuration.getBigDecimal("duration_sum_ms"))
                .thenReturn(new BigDecimal("-5"));
        assertThrows(IllegalStateException.class,
                () -> totalsMapper.getValue().mapRow(negativeDuration, 0));

        ArgumentCaptor<RowMapper<DimensionAggregate>> dimensionMapper =
                ArgumentCaptor.forClass(RowMapper.class);
        when(jdbcTemplate.query(anyString(), dimensionMapper.capture(),
                any(Object[].class))).thenAnswer(invocation -> List.of());
        repository.byStatus(FROM, TO, null, null, null, true, List.of(1L));

        ResultSet blankKey = stubAggregateRow();
        when(blankKey.getObject("dimension_key")).thenReturn(" ");
        assertThrows(IllegalStateException.class,
                () -> dimensionMapper.getValue().mapRow(blankKey, 0));
    }
}
