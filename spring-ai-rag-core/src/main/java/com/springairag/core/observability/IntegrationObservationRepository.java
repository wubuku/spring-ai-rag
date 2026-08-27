package com.springairag.core.observability;

import com.springairag.api.enums.IntegrationObservabilityBucket;
import com.springairag.api.enums.IntegrationOperation;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * V54 小时级 HTTP operation rollup 的 JDBC 边界。
 *
 * <p>请求记录先在内存按唯一键合并，再以短事务批量 upsert。查询只读取聚合列，
 * 不提供逐请求明细。</p>
 */
@Repository
public class IntegrationObservationRepository {

    private static final String AGGREGATE_COLUMNS = """
            COALESCE(SUM(request_count::numeric), 0) AS request_count,
            COALESCE(SUM(duration_sum_ms), 0) AS duration_sum_ms,
            COALESCE(MAX(duration_max_ms), 0) AS duration_max_ms,
            COALESCE(SUM(le_25_ms_count::numeric), 0) AS le_25_ms_count,
            COALESCE(SUM(le_50_ms_count::numeric), 0) AS le_50_ms_count,
            COALESCE(SUM(le_100_ms_count::numeric), 0) AS le_100_ms_count,
            COALESCE(SUM(le_250_ms_count::numeric), 0) AS le_250_ms_count,
            COALESCE(SUM(le_500_ms_count::numeric), 0) AS le_500_ms_count,
            COALESCE(SUM(le_1000_ms_count::numeric), 0) AS le_1000_ms_count,
            COALESCE(SUM(le_2500_ms_count::numeric), 0) AS le_2500_ms_count,
            COALESCE(SUM(le_5000_ms_count::numeric), 0) AS le_5000_ms_count,
            COALESCE(SUM(over_5000_ms_count::numeric), 0) AS over_5000_ms_count
            """;

    private static final String QUALIFIED_AGGREGATE_COLUMNS = """
            COALESCE(SUM(observation.request_count::numeric), 0) AS request_count,
            COALESCE(SUM(observation.duration_sum_ms), 0) AS duration_sum_ms,
            COALESCE(MAX(observation.duration_max_ms), 0) AS duration_max_ms,
            COALESCE(SUM(observation.le_25_ms_count::numeric), 0) AS le_25_ms_count,
            COALESCE(SUM(observation.le_50_ms_count::numeric), 0) AS le_50_ms_count,
            COALESCE(SUM(observation.le_100_ms_count::numeric), 0) AS le_100_ms_count,
            COALESCE(SUM(observation.le_250_ms_count::numeric), 0) AS le_250_ms_count,
            COALESCE(SUM(observation.le_500_ms_count::numeric), 0) AS le_500_ms_count,
            COALESCE(SUM(observation.le_1000_ms_count::numeric), 0) AS le_1000_ms_count,
            COALESCE(SUM(observation.le_2500_ms_count::numeric), 0) AS le_2500_ms_count,
            COALESCE(SUM(observation.le_5000_ms_count::numeric), 0) AS le_5000_ms_count,
            COALESCE(SUM(observation.over_5000_ms_count::numeric), 0) AS over_5000_ms_count
            """;

    private static final String OPERATION_UPSERT = """
            INSERT INTO rag_api_operation_hourly (
                bucket_start, principal_type, principal_ref, operation,
                http_status, request_count, duration_sum_ms, duration_max_ms,
                le_25_ms_count, le_50_ms_count, le_100_ms_count,
                le_250_ms_count, le_500_ms_count, le_1000_ms_count,
                le_2500_ms_count, le_5000_ms_count, over_5000_ms_count)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (
                bucket_start, principal_type, principal_ref, operation, http_status)
            DO UPDATE SET
                request_count = rag_api_operation_hourly.request_count
                    + EXCLUDED.request_count,
                duration_sum_ms = rag_api_operation_hourly.duration_sum_ms
                    + EXCLUDED.duration_sum_ms,
                duration_max_ms = GREATEST(
                    rag_api_operation_hourly.duration_max_ms,
                    EXCLUDED.duration_max_ms),
                le_25_ms_count = rag_api_operation_hourly.le_25_ms_count
                    + EXCLUDED.le_25_ms_count,
                le_50_ms_count = rag_api_operation_hourly.le_50_ms_count
                    + EXCLUDED.le_50_ms_count,
                le_100_ms_count = rag_api_operation_hourly.le_100_ms_count
                    + EXCLUDED.le_100_ms_count,
                le_250_ms_count = rag_api_operation_hourly.le_250_ms_count
                    + EXCLUDED.le_250_ms_count,
                le_500_ms_count = rag_api_operation_hourly.le_500_ms_count
                    + EXCLUDED.le_500_ms_count,
                le_1000_ms_count = rag_api_operation_hourly.le_1000_ms_count
                    + EXCLUDED.le_1000_ms_count,
                le_2500_ms_count = rag_api_operation_hourly.le_2500_ms_count
                    + EXCLUDED.le_2500_ms_count,
                le_5000_ms_count = rag_api_operation_hourly.le_5000_ms_count
                    + EXCLUDED.le_5000_ms_count,
                over_5000_ms_count = rag_api_operation_hourly.over_5000_ms_count
                    + EXCLUDED.over_5000_ms_count,
                updated_at = clock_timestamp()
            """;

    private static final String COLLECTION_UPSERT = """
            INSERT INTO rag_api_collection_operation_hourly (
                bucket_start, principal_type, principal_ref, collection_id,
                operation, http_status, request_count, duration_sum_ms,
                duration_max_ms, le_25_ms_count, le_50_ms_count,
                le_100_ms_count, le_250_ms_count, le_500_ms_count,
                le_1000_ms_count, le_2500_ms_count, le_5000_ms_count,
                over_5000_ms_count)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (
                bucket_start, principal_type, principal_ref, collection_id,
                operation, http_status)
            DO UPDATE SET
                request_count = rag_api_collection_operation_hourly.request_count
                    + EXCLUDED.request_count,
                duration_sum_ms = rag_api_collection_operation_hourly.duration_sum_ms
                    + EXCLUDED.duration_sum_ms,
                duration_max_ms = GREATEST(
                    rag_api_collection_operation_hourly.duration_max_ms,
                    EXCLUDED.duration_max_ms),
                le_25_ms_count = rag_api_collection_operation_hourly.le_25_ms_count
                    + EXCLUDED.le_25_ms_count,
                le_50_ms_count = rag_api_collection_operation_hourly.le_50_ms_count
                    + EXCLUDED.le_50_ms_count,
                le_100_ms_count = rag_api_collection_operation_hourly.le_100_ms_count
                    + EXCLUDED.le_100_ms_count,
                le_250_ms_count = rag_api_collection_operation_hourly.le_250_ms_count
                    + EXCLUDED.le_250_ms_count,
                le_500_ms_count = rag_api_collection_operation_hourly.le_500_ms_count
                    + EXCLUDED.le_500_ms_count,
                le_1000_ms_count = rag_api_collection_operation_hourly.le_1000_ms_count
                    + EXCLUDED.le_1000_ms_count,
                le_2500_ms_count = rag_api_collection_operation_hourly.le_2500_ms_count
                    + EXCLUDED.le_2500_ms_count,
                le_5000_ms_count = rag_api_collection_operation_hourly.le_5000_ms_count
                    + EXCLUDED.le_5000_ms_count,
                over_5000_ms_count = rag_api_collection_operation_hourly.over_5000_ms_count
                    + EXCLUDED.over_5000_ms_count,
                updated_at = clock_timestamp()
            """;

    private static final String DELETE_OPERATION_SQL = """
            WITH victims AS (
                SELECT bucket_start, principal_type, principal_ref,
                       operation, http_status
                FROM rag_api_operation_hourly
                WHERE bucket_start < ?
                ORDER BY bucket_start ASC
                LIMIT ?
            )
            DELETE FROM rag_api_operation_hourly target
            USING victims
            WHERE target.bucket_start = victims.bucket_start
              AND target.principal_type = victims.principal_type
              AND target.principal_ref = victims.principal_ref
              AND target.operation = victims.operation
              AND target.http_status = victims.http_status
            """;

    private static final String DELETE_COLLECTION_SQL = """
            WITH victims AS (
                SELECT bucket_start, principal_type, principal_ref,
                       collection_id, operation, http_status
                FROM rag_api_collection_operation_hourly
                WHERE bucket_start < ?
                ORDER BY bucket_start ASC
                LIMIT ?
            )
            DELETE FROM rag_api_collection_operation_hourly target
            USING victims
            WHERE target.bucket_start = victims.bucket_start
              AND target.principal_type = victims.principal_type
              AND target.principal_ref = victims.principal_ref
              AND target.collection_id = victims.collection_id
              AND target.operation = victims.operation
              AND target.http_status = victims.http_status
            """;

    private final JdbcTemplate jdbcTemplate;

    public IntegrationObservationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 合并并写入一批请求观测。异常由上层 recorder 转换为 best-effort drop。
     */
    @Transactional
    public void upsert(List<IntegrationObservation> observations, int timeoutMs) {
        if (observations == null || observations.isEmpty()) {
            return;
        }
        Map<RollupKey, RollupDelta> operationGroups = new LinkedHashMap<>();
        Map<RollupKey, RollupDelta> collectionGroups = new LinkedHashMap<>();
        for (IntegrationObservation observation : observations) {
            RollupKey operationKey = RollupKey.operation(observation);
            operationGroups.computeIfAbsent(operationKey, ignored -> new RollupDelta())
                    .add(observation);
            for (Long collectionId : observation.authorizedCollectionIds()) {
                RollupKey collectionKey = RollupKey.collection(observation, collectionId);
                collectionGroups.computeIfAbsent(collectionKey, ignored -> new RollupDelta())
                        .add(observation);
            }
        }
        batchUpsert(OPERATION_UPSERT, operationGroups, timeoutMs, false);
        batchUpsert(COLLECTION_UPSERT, collectionGroups, timeoutMs, true);
    }

    public Aggregate totals(
            Instant fromInclusive,
            Instant toExclusive,
            String principalType,
            String principalRef,
            IntegrationOperation operation) {
        return totals(
                fromInclusive,
                toExclusive,
                principalType,
                principalRef,
                operation,
                false,
                null);
    }

    public Aggregate totals(
            Instant fromInclusive,
            Instant toExclusive,
            String principalType,
            String principalRef,
            IntegrationOperation operation,
            boolean collectionScoped,
            List<Long> collectionIds) {
        if (collectionScoped && collectionIds != null && collectionIds.isEmpty()) {
            return emptyAggregate();
        }
        String source = collectionScoped
                ? COLLECTION_SOURCE
                : "FROM rag_api_operation_hourly";
        Query query = baseQuery(
                source,
                fromInclusive,
                toExclusive,
                principalType,
                principalRef,
                operation,
                collectionScoped,
                collectionIds);
        return jdbcTemplate.queryForObject(
                "SELECT " + aggregateColumns(collectionScoped) + " "
                        + query.sql(),
                this::mapAggregate,
                query.args().toArray());
    }

    public List<DimensionAggregate> byStatus(
            Instant fromInclusive,
            Instant toExclusive,
            String principalType,
            String principalRef,
            IntegrationOperation operation) {
        return byStatus(
                fromInclusive,
                toExclusive,
                principalType,
                principalRef,
                operation,
                false,
                null);
    }

    public List<DimensionAggregate> byStatus(
            Instant fromInclusive,
            Instant toExclusive,
            String principalType,
            String principalRef,
            IntegrationOperation operation,
            boolean collectionScoped,
            List<Long> collectionIds) {
        if (collectionScoped && collectionIds != null && collectionIds.isEmpty()) {
            return List.of();
        }
        String source = collectionScoped
                ? COLLECTION_SOURCE
                : "FROM rag_api_operation_hourly";
        Query query = baseQuery(
                source,
                fromInclusive,
                toExclusive,
                principalType,
                principalRef,
                operation,
                collectionScoped,
                collectionIds);
        String statusColumn = collectionScoped
                ? "observation.http_status"
                : "http_status";
        return jdbcTemplate.query(
                "SELECT " + statusColumn + " AS dimension_key, "
                        + aggregateColumns(collectionScoped) + " "
                        + query.sql() + " GROUP BY http_status ORDER BY http_status ASC",
                this::mapDimension,
                query.args().toArray());
    }

    public List<DimensionAggregate> byOperation(
            Instant fromInclusive,
            Instant toExclusive,
            String principalType,
            String principalRef,
            IntegrationOperation operation) {
        return byOperation(
                fromInclusive,
                toExclusive,
                principalType,
                principalRef,
                operation,
                false,
                null);
    }

    public List<DimensionAggregate> byOperation(
            Instant fromInclusive,
            Instant toExclusive,
            String principalType,
            String principalRef,
            IntegrationOperation operation,
            boolean collectionScoped,
            List<Long> collectionIds) {
        if (collectionScoped && collectionIds != null && collectionIds.isEmpty()) {
            return List.of();
        }
        String source = collectionScoped
                ? COLLECTION_SOURCE
                : "FROM rag_api_operation_hourly";
        Query query = baseQuery(
                source,
                fromInclusive,
                toExclusive,
                principalType,
                principalRef,
                operation,
                collectionScoped,
                collectionIds);
        String operationColumn = collectionScoped
                ? "observation.operation"
                : "operation";
        return jdbcTemplate.query(
                "SELECT " + operationColumn + " AS dimension_key, "
                        + aggregateColumns(collectionScoped) + " "
                        + query.sql()
                        + " GROUP BY operation ORDER BY operation ASC",
                this::mapDimension,
                query.args().toArray());
    }

    public List<TimelineAggregate> timeline(
            Instant fromInclusive,
            Instant toExclusive,
            String principalType,
            String principalRef,
            IntegrationOperation operation,
            IntegrationObservabilityBucket bucket) {
        return timeline(
                fromInclusive,
                toExclusive,
                principalType,
                principalRef,
                operation,
                bucket,
                false,
                null);
    }

    public List<TimelineAggregate> timeline(
            Instant fromInclusive,
            Instant toExclusive,
            String principalType,
            String principalRef,
            IntegrationOperation operation,
            IntegrationObservabilityBucket bucket,
            boolean collectionScoped,
            List<Long> collectionIds) {
        if (collectionScoped && collectionIds != null && collectionIds.isEmpty()) {
            return List.of();
        }
        String source = collectionScoped
                ? COLLECTION_SOURCE
                : "FROM rag_api_operation_hourly";
        Query query = baseQuery(
                source,
                fromInclusive,
                toExclusive,
                principalType,
                principalRef,
                operation,
                collectionScoped,
                collectionIds);
        String bucketColumn = collectionScoped
                ? "observation.bucket_start"
                : "bucket_start";
        String groupedDimension = bucket == IntegrationObservabilityBucket.DAY
                ? "(" + bucketColumn + " AT TIME ZONE 'UTC')::date"
                : bucketColumn;
        return jdbcTemplate.query(
                "SELECT " + groupedDimension + " AS dimension_key, "
                        + aggregateColumns(collectionScoped) + " "
                        + query.sql()
                        + " GROUP BY " + groupedDimension
                        + " ORDER BY " + groupedDimension + " ASC",
                this::mapTimeline,
                query.args().toArray());
    }

    public List<CollectionAggregate> collectionContributions(
            Instant fromInclusive,
            Instant toExclusive,
            String principalType,
            String principalRef,
            IntegrationOperation operation,
            List<Long> collectionIds,
            int limit) {
        StringBuilder from = new StringBuilder("""
                FROM rag_api_collection_operation_hourly observation
                JOIN rag_collection collection
                  ON collection.id = observation.collection_id
                 AND collection.deleted = false
                """);
        List<Object> args = new ArrayList<>();
        String where = whereClause(
                "observation",
                fromInclusive,
                toExclusive,
                principalType,
                principalRef,
                operation,
                args);
        if (collectionIds != null) {
            if (collectionIds.isEmpty()) {
                return List.of();
            }
            where += " AND observation.collection_id IN ("
                    + "?,".repeat(Math.max(0, collectionIds.size() - 1))
                    + "?)";
            args.addAll(collectionIds);
        }
        return jdbcTemplate.query(
                "SELECT observation.collection_id, collection.collection_key, "
                        + QUALIFIED_AGGREGATE_COLUMNS
                        + " " + from + where
                        + " GROUP BY observation.collection_id, collection.collection_key"
                        + " ORDER BY SUM(observation.request_count::numeric) DESC,"
                        + " collection.collection_key ASC LIMIT ?",
                (rs, rowNum) -> new CollectionAggregate(
                        rs.getLong("collection_id"),
                        requiredText(rs.getString("collection_key"), "collection_key"),
                        mapAggregate(rs, rowNum)),
                append(args, limit).toArray());
    }

    public Instant oldestBucket(
            Instant fromInclusive,
            Instant toExclusive,
            String principalType,
            String principalRef,
            IntegrationOperation operation) {
        return oldestBucket(
                fromInclusive,
                toExclusive,
                principalType,
                principalRef,
                operation,
                false,
                null);
    }

    public Instant oldestBucket(
            Instant fromInclusive,
            Instant toExclusive,
            String principalType,
            String principalRef,
            IntegrationOperation operation,
            boolean collectionScoped,
            List<Long> collectionIds) {
        if (collectionScoped && collectionIds != null && collectionIds.isEmpty()) {
            return null;
        }
        String source = collectionScoped
                ? COLLECTION_SOURCE
                : "FROM rag_api_operation_hourly";
        Query query = baseQuery(
                source,
                fromInclusive,
                toExclusive,
                principalType,
                principalRef,
                operation,
                collectionScoped,
                collectionIds);
        String bucketColumn = collectionScoped
                ? "observation.bucket_start"
                : "bucket_start";
        return jdbcTemplate.queryForObject(
                "SELECT MIN(" + bucketColumn + ") " + query.sql(),
                (rs, rowNum) -> {
                    Timestamp value = rs.getTimestamp(1);
                    return value == null ? null : value.toInstant();
                },
                query.args().toArray());
    }

    @Transactional
    public int deleteExpired(Instant cutoff, int batchSize, int timeoutMs) {
        if (cutoff == null || batchSize < 1) {
            return 0;
        }
        int deleted = jdbcTemplate.update(
                DELETE_OPERATION_SQL,
                statement -> {
                    statement.setQueryTimeout(timeoutSeconds(timeoutMs));
                    statement.setTimestamp(1, Timestamp.from(cutoff));
                    statement.setInt(2, batchSize);
                });
        deleted += jdbcTemplate.update(
                DELETE_COLLECTION_SQL,
                statement -> {
                    statement.setQueryTimeout(timeoutSeconds(timeoutMs));
                    statement.setTimestamp(1, Timestamp.from(cutoff));
                    statement.setInt(2, batchSize);
                });
        return deleted;
    }

    private void batchUpsert(
            String sql,
            Map<RollupKey, RollupDelta> groups,
            int timeoutMs,
            boolean collection) {
        if (groups.isEmpty()) {
            return;
        }
        List<Map.Entry<RollupKey, RollupDelta>> entries =
                new ArrayList<>(groups.entrySet());
        jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement statement, int index)
                    throws SQLException {
                Map.Entry<RollupKey, RollupDelta> entry = entries.get(index);
                RollupKey key = entry.getKey();
                RollupDelta delta = entry.getValue();
                int parameter = 1;
                statement.setQueryTimeout(timeoutSeconds(timeoutMs));
                statement.setTimestamp(parameter++, Timestamp.from(key.bucketStart()));
                statement.setString(parameter++, key.principalType());
                statement.setString(parameter++, key.principalRef());
                if (collection) {
                    statement.setLong(parameter++, key.collectionId());
                }
                statement.setString(parameter++, key.operation().name());
                statement.setInt(parameter++, key.httpStatus());
                statement.setLong(parameter++, delta.requestCount);
                statement.setBigDecimal(
                        parameter++,
                        new BigDecimal(delta.durationSumMs));
                statement.setLong(parameter++, delta.durationMaxMs);
                statement.setLong(parameter++, delta.le25);
                statement.setLong(parameter++, delta.le50);
                statement.setLong(parameter++, delta.le100);
                statement.setLong(parameter++, delta.le250);
                statement.setLong(parameter++, delta.le500);
                statement.setLong(parameter++, delta.le1000);
                statement.setLong(parameter++, delta.le2500);
                statement.setLong(parameter++, delta.le5000);
                statement.setLong(parameter, delta.over5000);
            }

            @Override
            public int getBatchSize() {
                return entries.size();
            }
        });
    }

    private Query baseQuery(
            String from,
            Instant fromInclusive,
            Instant toExclusive,
            String principalType,
            String principalRef,
            IntegrationOperation operation,
            boolean collectionScoped,
            List<Long> collectionIds) {
        List<Object> args = new ArrayList<>();
        String where = whereClause(
                collectionScoped ? "observation" : null,
                fromInclusive,
                toExclusive,
                principalType,
                principalRef,
                operation,
                args);
        if (collectionScoped && collectionIds != null) {
            where += " AND observation.collection_id IN ("
                    + "?,".repeat(Math.max(0, collectionIds.size() - 1))
                    + "?)";
            args.addAll(collectionIds);
        }
        return new Query(from + where, args);
    }

    private static final String COLLECTION_SOURCE = """
            FROM rag_api_collection_operation_hourly observation
            JOIN rag_collection collection
              ON collection.id = observation.collection_id
             AND collection.deleted = false
            """;

    private static String aggregateColumns(boolean collectionScoped) {
        return collectionScoped
                ? QUALIFIED_AGGREGATE_COLUMNS
                : AGGREGATE_COLUMNS;
    }

    private static Aggregate emptyAggregate() {
        return new Aggregate(
                BigInteger.ZERO,
                BigDecimal.ZERO,
                BigInteger.ZERO,
                BigInteger.ZERO,
                BigInteger.ZERO,
                BigInteger.ZERO,
                BigInteger.ZERO,
                BigInteger.ZERO,
                BigInteger.ZERO,
                BigInteger.ZERO,
                BigInteger.ZERO,
                BigInteger.ZERO);
    }

    private static String whereClause(
            String alias,
            Instant fromInclusive,
            Instant toExclusive,
            String principalType,
            String principalRef,
            IntegrationOperation operation,
            List<Object> args) {
        String prefix = alias == null ? "" : alias + ".";
        args.add(Timestamp.from(fromInclusive));
        args.add(Timestamp.from(toExclusive));
        StringBuilder where = new StringBuilder(
                " WHERE " + prefix + "bucket_start >= ? AND "
                        + prefix + "bucket_start < ?");
        if (principalType != null) {
            where.append(" AND ").append(prefix).append("principal_type = ?");
            args.add(principalType);
        }
        if (principalRef != null) {
            where.append(" AND ").append(prefix).append("principal_ref = ?");
            args.add(principalRef);
        }
        if (operation != null) {
            where.append(" AND ").append(prefix).append("operation = ?");
            args.add(operation.name());
        }
        return where.toString();
    }

    private Aggregate mapAggregate(ResultSet rs, int rowNum) throws SQLException {
        return new Aggregate(
                nonNegativeInteger(rs.getBigDecimal("request_count")),
                nonNegativeDecimal(rs.getBigDecimal("duration_sum_ms")),
                nonNegativeInteger(rs.getBigDecimal("duration_max_ms")),
                nonNegativeInteger(rs.getBigDecimal("le_25_ms_count")),
                nonNegativeInteger(rs.getBigDecimal("le_50_ms_count")),
                nonNegativeInteger(rs.getBigDecimal("le_100_ms_count")),
                nonNegativeInteger(rs.getBigDecimal("le_250_ms_count")),
                nonNegativeInteger(rs.getBigDecimal("le_500_ms_count")),
                nonNegativeInteger(rs.getBigDecimal("le_1000_ms_count")),
                nonNegativeInteger(rs.getBigDecimal("le_2500_ms_count")),
                nonNegativeInteger(rs.getBigDecimal("le_5000_ms_count")),
                nonNegativeInteger(rs.getBigDecimal("over_5000_ms_count")));
    }

    private DimensionAggregate mapDimension(ResultSet rs, int rowNum)
            throws SQLException {
        return new DimensionAggregate(
                requiredText(String.valueOf(rs.getObject("dimension_key")), "dimension_key"),
                mapAggregate(rs, rowNum));
    }

    private TimelineAggregate mapTimeline(ResultSet rs, int rowNum)
            throws SQLException {
        Object value = rs.getObject("dimension_key");
        String bucket = switch (value) {
            case Timestamp timestamp -> timestamp.toInstant().toString();
            case java.time.OffsetDateTime offsetDateTime ->
                    offsetDateTime.toInstant().toString();
            case Instant instant -> instant.toString();
            case LocalDate date -> date.toString();
            default -> requiredText(String.valueOf(value), "dimension_key");
        };
        return new TimelineAggregate(bucket, mapAggregate(rs, rowNum));
    }

    private static List<Object> append(List<Object> args, Object value) {
        List<Object> copy = new ArrayList<>(args);
        copy.add(value);
        return copy;
    }

    private static int timeoutSeconds(int timeoutMs) {
        return Math.max(1, (int) Math.ceil(Math.max(1, timeoutMs) / 1_000.0));
    }

    private static BigInteger nonNegativeInteger(BigDecimal value) {
        if (value == null || value.signum() < 0
                || value.stripTrailingZeros().scale() > 0) {
            throw new IllegalStateException("rollup count must be a non-negative integer");
        }
        return value.toBigIntegerExact();
    }

    private static BigDecimal nonNegativeDecimal(BigDecimal value) {
        if (value == null || value.signum() < 0) {
            throw new IllegalStateException("rollup duration must be non-negative");
        }
        return value;
    }

    private static String requiredText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(field + " must not be blank");
        }
        return value;
    }

    public record Aggregate(
            BigInteger requestCount,
            BigDecimal durationSumMs,
            BigInteger durationMaxMs,
            BigInteger le25,
            BigInteger le50,
            BigInteger le100,
            BigInteger le250,
            BigInteger le500,
            BigInteger le1000,
            BigInteger le2500,
            BigInteger le5000,
            BigInteger over5000) {
    }

    public record DimensionAggregate(String dimension, Aggregate aggregate) {
    }

    public record TimelineAggregate(String bucketStart, Aggregate aggregate) {
    }

    public record CollectionAggregate(
            long collectionId,
            String collectionKey,
            Aggregate aggregate) {
    }

    public record Query(String sql, List<Object> args) {
    }

    private record RollupKey(
            Instant bucketStart,
            String principalType,
            String principalRef,
            long collectionId,
            IntegrationOperation operation,
            int httpStatus) {

        static RollupKey operation(IntegrationObservation observation) {
            return new RollupKey(
                    observation.bucketStart(),
                    observation.principalType(),
                    observation.principalRef(),
                    0,
                    observation.operation(),
                    observation.httpStatus());
        }

        static RollupKey collection(
                IntegrationObservation observation,
                long collectionId) {
            return new RollupKey(
                    observation.bucketStart(),
                    observation.principalType(),
                    observation.principalRef(),
                    collectionId,
                    observation.operation(),
                    observation.httpStatus());
        }
    }

    private static final class RollupDelta {
        private long requestCount;
        private BigInteger durationSumMs = BigInteger.ZERO;
        private long durationMaxMs;
        private long le25;
        private long le50;
        private long le100;
        private long le250;
        private long le500;
        private long le1000;
        private long le2500;
        private long le5000;
        private long over5000;

        void add(IntegrationObservation observation) {
            requestCount++;
            durationSumMs = durationSumMs.add(
                    BigInteger.valueOf(observation.durationMs()));
            durationMaxMs = Math.max(durationMaxMs, observation.durationMs());
            if (observation.durationMs() <= 25) le25++;
            if (observation.durationMs() <= 50) le50++;
            if (observation.durationMs() <= 100) le100++;
            if (observation.durationMs() <= 250) le250++;
            if (observation.durationMs() <= 500) le500++;
            if (observation.durationMs() <= 1000) le1000++;
            if (observation.durationMs() <= 2500) le2500++;
            if (observation.durationMs() <= 5000) le5000++;
            if (observation.durationMs() > 5000) over5000++;
        }
    }
}
