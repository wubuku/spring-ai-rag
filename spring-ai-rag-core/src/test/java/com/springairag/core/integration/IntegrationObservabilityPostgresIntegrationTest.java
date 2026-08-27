package com.springairag.core.integration;

import com.springairag.api.enums.IntegrationObservabilityBucket;
import com.springairag.api.enums.IntegrationOperation;
import com.springairag.core.observability.IntegrationObservation;
import com.springairag.core.observability.IntegrationObservationRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * V54 operation rollup acceptance against real PostgreSQL.
 *
 * <p>Run explicitly with {@code -Dintegration-observability.it.enabled=true}.
 * Each test uses a disposable database and executes all migrations.</p>
 */
class IntegrationObservabilityPostgresIntegrationTest {

    private static PostgreSQLContainer<?> postgres;
    private static DataSource dataSource;
    private JdbcTemplate jdbc;
    private IntegrationObservationRepository repository;
    private TransactionTemplate transaction;

    @BeforeAll
    static void startDatabase() {
        assumeTrue(
                Boolean.getBoolean("integration-observability.it.enabled"),
                "Set -Dintegration-observability.it.enabled=true to run PostgreSQL tests");

        String externalJdbcUrl =
                System.getenv("INTEGRATION_OBSERVABILITY_IT_JDBC_URL");
        if (externalJdbcUrl != null && !externalJdbcUrl.isBlank()) {
            if (!"YES".equals(System.getenv(
                    "INTEGRATION_OBSERVABILITY_IT_CLEAN_CONFIRM"))) {
                throw new IllegalStateException(
                        "Set INTEGRATION_OBSERVABILITY_IT_CLEAN_CONFIRM=YES "
                                + "only for a disposable database");
            }
            dataSource = dataSource(
                    externalJdbcUrl,
                    System.getenv("INTEGRATION_OBSERVABILITY_IT_USERNAME"),
                    System.getenv("INTEGRATION_OBSERVABILITY_IT_PASSWORD"));
            return;
        }

        try {
            assumeTrue(
                    DockerClientFactory.instance().isDockerAvailable(),
                    "Docker is unavailable for PostgreSQL tests");
        } catch (RuntimeException unavailable) {
            assumeTrue(false, "Docker is unavailable: " + unavailable.getMessage());
        }
        String image = System.getProperty(
                "testcontainers.pg.image",
                System.getenv().getOrDefault(
                        "TESTCONTAINERS_PG_IMAGE", "pgvector/pgvector:pg16"));
        postgres = new PostgreSQLContainer<>(
                DockerImageName.parse(image).asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("spring_ai_rag_integration_observability_test")
                .withUsername("postgres")
                .withPassword("postgres");
        postgres.start();
        dataSource = dataSource(
                postgres.getJdbcUrl(),
                postgres.getUsername(),
                postgres.getPassword());
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @BeforeEach
    void migrateEmptyDatabase() {
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load()
                .clean();
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load()
                .migrate();
        jdbc = new JdbcTemplate(dataSource);
        repository = new IntegrationObservationRepository(jdbc);
        transaction = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource));
    }

    @Test
    void migratesThroughV54AndCreatesBoundedRollupIndexes() {
        assertEquals(
                "54",
                jdbc.queryForObject(
                        """
                        SELECT version
                        FROM flyway_schema_history
                        WHERE success = TRUE
                        ORDER BY installed_rank DESC
                        LIMIT 1
                        """,
                        String.class));
        assertEquals(
                1L,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.tables "
                                + "WHERE table_name = 'rag_api_operation_hourly'",
                        Long.class));
        assertEquals(
                1L,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.tables "
                                + "WHERE table_name = 'rag_api_collection_operation_hourly'",
                        Long.class));
        assertEquals(
                1L,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM pg_indexes "
                                + "WHERE indexname = "
                                + "'idx_rag_api_collection_operation_hourly_collection_time'",
                        Long.class));
    }

    @Test
    void upsertAggregatesConcurrentInstancesAndCountsEachCollectionContribution() throws Exception {
        long firstCollection = insertCollection("observability-first");
        long secondCollection = insertCollection("observability-second");
        Instant bucket = Instant.parse("2026-08-27T03:00:00Z");
        IntegrationObservation observation = observation(
                bucket,
                200,
                40,
                List.of(firstCollection, secondCollection));

        int workers = 6;
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        try {
            List<Future<Object>> futures = java.util.stream.IntStream.range(0, workers)
                    .mapToObj(ignored -> executor.submit(() -> {
                        ready.countDown();
                        start.await();
                        transaction.executeWithoutResult(
                                ignoredStatus -> new IntegrationObservationRepository(
                                        new JdbcTemplate(dataSource))
                                        .upsert(List.of(observation), 2_000));
                        return null;
                    }))
                    .toList();
            ready.await();
            start.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            executor.shutdownNow();
        }

        assertEquals(
                workers,
                jdbc.queryForObject(
                        """
                        SELECT request_count
                        FROM rag_api_operation_hourly
                        WHERE bucket_start = ? AND principal_type = ?
                          AND principal_ref = ? AND operation = ? AND http_status = ?
                        """,
                        Long.class,
                        java.sql.Timestamp.from(bucket),
                        "DATABASE_API_KEY",
                        "principal-a",
                        IntegrationOperation.JSON_RECORD_SEARCH.name(),
                        200));
        assertEquals(
                workers * 40L,
                jdbc.queryForObject(
                        """
                        SELECT duration_sum_ms
                        FROM rag_api_operation_hourly
                        WHERE bucket_start = ? AND principal_type = ?
                          AND principal_ref = ? AND operation = ? AND http_status = ?
                        """,
                        BigInteger.class,
                        java.sql.Timestamp.from(bucket),
                        "DATABASE_API_KEY",
                        "principal-a",
                        IntegrationOperation.JSON_RECORD_SEARCH.name(),
                        200).longValueExact());
        assertEquals(
                2L,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM rag_api_collection_operation_hourly",
                        Long.class));
        assertEquals(
                workers,
                jdbc.queryForObject(
                        "SELECT request_count FROM rag_api_collection_operation_hourly "
                                + "WHERE collection_id = ?",
                        Long.class,
                        firstCollection));
        assertEquals(
                workers,
                jdbc.queryForObject(
                        "SELECT request_count FROM rag_api_collection_operation_hourly "
                                + "WHERE collection_id = ?",
                        Long.class,
                        secondCollection));
    }

    @Test
    void queriesStatusOperationTimelineAndExclusiveTimeBoundaries() {
        long collection = insertCollection("query-boundary");
        Instant firstHour = Instant.parse("2026-08-26T23:00:00Z");
        Instant secondHour = Instant.parse("2026-08-27T00:00:00Z");
        transaction.executeWithoutResult(ignored -> repository.upsert(
                List.of(
                        observation(
                                firstHour,
                                200,
                                25,
                                List.of(collection)),
                        observation(
                                secondHour,
                                409,
                                5_001,
                                List.of(collection))),
                2_000));

        IntegrationObservationRepository.Aggregate inclusive = repository.totals(
                firstHour,
                secondHour,
                "DATABASE_API_KEY",
                "principal-a",
                null);
        assertEquals(BigInteger.ONE, inclusive.requestCount());
        assertEquals(BigInteger.ONE, inclusive.le25());

        IntegrationObservationRepository.Aggregate both = repository.totals(
                firstHour,
                secondHour.plusSeconds(1),
                "DATABASE_API_KEY",
                "principal-a",
                null);
        assertEquals(BigInteger.TWO, both.requestCount());
        assertEquals(BigInteger.ONE, both.le25());
        assertEquals(BigInteger.ONE, both.over5000());

        assertEquals(
                List.of("200", "409"),
                repository.byStatus(
                                firstHour,
                                secondHour.plusSeconds(1),
                                "DATABASE_API_KEY",
                                "principal-a",
                                null)
                        .stream()
                        .map(IntegrationObservationRepository.DimensionAggregate::dimension)
                        .toList());
        assertEquals(
                List.of(IntegrationOperation.JSON_RECORD_SEARCH.name()),
                repository.byOperation(
                                firstHour,
                                secondHour.plusSeconds(1),
                                "DATABASE_API_KEY",
                                "principal-a",
                                IntegrationOperation.JSON_RECORD_SEARCH)
                        .stream()
                        .map(IntegrationObservationRepository.DimensionAggregate::dimension)
                        .toList());
        assertEquals(
                List.of("2026-08-26T23:00:00Z", "2026-08-27T00:00:00Z"),
                repository.timeline(
                                firstHour,
                                secondHour.plusSeconds(1),
                                "DATABASE_API_KEY",
                                "principal-a",
                                null,
                                IntegrationObservabilityBucket.HOUR)
                        .stream()
                        .map(IntegrationObservationRepository.TimelineAggregate::bucketStart)
                        .toList());
        assertEquals(
                List.of("2026-08-26", "2026-08-27"),
                repository.timeline(
                                firstHour,
                                secondHour.plusSeconds(1),
                                "DATABASE_API_KEY",
                                "principal-a",
                                null,
                                IntegrationObservabilityBucket.DAY)
                        .stream()
                        .map(IntegrationObservationRepository.TimelineAggregate::bucketStart)
                        .toList());
        assertEquals(
                List.of("query-boundary"),
                repository.collectionContributions(
                                firstHour,
                                secondHour.plusSeconds(1),
                                "DATABASE_API_KEY",
                                "principal-a",
                                null,
                                List.of(collection),
                                100)
                        .stream()
                        .map(IntegrationObservationRepository.CollectionAggregate::collectionKey)
                        .toList());
    }

    @Test
    void failedCollectionBatchRollsBackOperationAndCleanupIsBounded() {
        long collection = insertCollection("cleanup-recent");
        Instant oldBucket = Instant.parse("2026-08-20T00:00:00Z");
        Instant recentBucket = Instant.parse("2026-08-27T00:00:00Z");

        assertThrows(
                DataIntegrityViolationException.class,
                () -> transaction.executeWithoutResult(ignored ->
                        repository.upsert(
                                List.of(observation(
                                        recentBucket,
                                        200,
                                        10,
                                        List.of(collection, 99_999_999L))),
                                2_000)));
        assertEquals(
                0L,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM rag_api_operation_hourly",
                        Long.class));

        transaction.executeWithoutResult(ignored -> repository.upsert(
                List.of(
                        observation(oldBucket, 200, 10, List.of(collection)),
                        observation(recentBucket, 200, 10, List.of(collection))),
                2_000));
        assertEquals(2L, repository.deleteExpired(
                Instant.parse("2026-08-25T00:00:00Z"),
                1,
                2_000));
        assertEquals(
                1L,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM rag_api_operation_hourly",
                        Long.class));
        assertEquals(
                recentBucket,
                repository.oldestBucket(
                        Instant.parse("2026-08-01T00:00:00Z"),
                        Instant.parse("2026-09-01T00:00:00Z"),
                        "DATABASE_API_KEY",
                        "principal-a",
                        null));
    }

    @Test
    void schemaRejectsUnalignedBucketsInvalidStatusAndUnknownOperation() {
        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbc.update(
                        """
                        INSERT INTO rag_api_operation_hourly (
                            bucket_start, principal_type, principal_ref,
                            operation, http_status)
                        VALUES (?, 'DATABASE_API_KEY', 'principal-a',
                                'JSON_RECORD_SEARCH', 200)
                        """,
                        java.sql.Timestamp.from(
                                Instant.parse("2026-08-27T03:01:00Z"))));
        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbc.update(
                        """
                        INSERT INTO rag_api_operation_hourly (
                            bucket_start, principal_type, principal_ref,
                            operation, http_status)
                        VALUES (?, 'DATABASE_API_KEY', 'principal-a',
                                'JSON_RECORD_SEARCH', 600)
                        """,
                        java.sql.Timestamp.from(
                                Instant.parse("2026-08-27T03:00:00Z"))));
        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbc.update(
                        """
                        INSERT INTO rag_api_operation_hourly (
                            bucket_start, principal_type, principal_ref,
                            operation, http_status)
                        VALUES (?, 'DATABASE_API_KEY', 'principal-a',
                                'NOT_AN_OPERATION', 200)
                        """,
                        java.sql.Timestamp.from(
                                Instant.parse("2026-08-27T03:00:00Z"))));
        assertFalse(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM rag_api_operation_hourly)",
                Boolean.class));
    }

    private long insertCollection(String key) {
        return jdbc.queryForObject(
                "INSERT INTO rag_collection (collection_key, name) "
                        + "VALUES (?, 'Integration observability test') RETURNING id",
                Long.class,
                key);
    }

    private static IntegrationObservation observation(
            Instant bucket,
            int status,
            long durationMs,
            List<Long> collectionIds) {
        return new IntegrationObservation(
                bucket,
                "DATABASE_API_KEY",
                "principal-a",
                IntegrationOperation.JSON_RECORD_SEARCH,
                status,
                durationMs,
                collectionIds);
    }

    private static DataSource dataSource(
            String url,
            String username,
            String password) {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setUrl(url);
        source.setUser(username);
        source.setPassword(password);
        return source;
    }
}
