package com.springairag.core.integration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Real PostgreSQL acceptance tests for the Collection business key contract.
 */
class CollectionKeyPostgresIntegrationTest {

    private static PostgreSQLContainer<?> postgres;
    private static DataSource dataSource;
    private static JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void startDatabase() {
        assumeTrue(Boolean.getBoolean("collection-key.it.enabled"),
                "Set -Dcollection-key.it.enabled=true to run Collection key integration tests");

        String image = System.getProperty(
                "testcontainers.pg.image",
                System.getenv().getOrDefault(
                        "TESTCONTAINERS_PG_IMAGE", "pgvector/pgvector:pg16"));
        DockerImageName imageName = DockerImageName.parse(image)
                .asCompatibleSubstituteFor("postgres");
        postgres = new PostgreSQLContainer<>(imageName)
                .withDatabaseName("spring_ai_rag_collection_key_test")
                .withUsername("postgres")
                .withPassword("postgres");
        postgres.start();
        dataSource = dataSource(postgres);
        jdbcTemplate = new JdbcTemplate(dataSource);
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @BeforeEach
    void cleanDatabase() {
        flyway(null).clean();
    }

    @Test
    void expandAndContractMigrationsBackfillWithoutInventingCollisions() {
        flyway(MigrationVersion.fromVersion("26")).migrate();
        jdbcTemplate.execute(
                "ALTER TABLE rag_collection "
                        + "ADD COLUMN collection_key VARCHAR(128) COLLATE \"C\"");
        jdbcTemplate.update(
                "INSERT INTO rag_collection (id, name, collection_key) "
                        + "VALUES (1, 'Legacy row', NULL)");
        jdbcTemplate.update(
                "INSERT INTO rag_collection (id, name, collection_key) "
                        + "VALUES (2, 'Reserved candidate', 'legacy-collection-1')");
        jdbcTemplate.queryForObject(
                "SELECT setval(pg_get_serial_sequence('rag_collection', 'id'), 2, true)",
                Long.class);

        flyway(MigrationVersion.fromVersion("27")).migrate();

        assertEquals("legacy-collection-1-1", jdbcTemplate.queryForObject(
                "SELECT collection_key FROM rag_collection WHERE id = 1",
                String.class));
        assertEquals("YES", jdbcTemplate.queryForObject(
                "SELECT is_nullable FROM information_schema.columns "
                        + "WHERE table_name = 'rag_collection' "
                        + "AND column_name = 'collection_key'",
                String.class));

        Long transitionId = jdbcTemplate.queryForObject(
                "INSERT INTO rag_collection (name) "
                        + "VALUES ('Old writer during expand') RETURNING id",
                Long.class);
        assertEquals(null, jdbcTemplate.queryForObject(
                "SELECT collection_key FROM rag_collection WHERE id = ?",
                String.class, transitionId));

        flyway(MigrationVersion.fromVersion("28")).migrate();

        assertEquals("legacy-collection-" + transitionId,
                jdbcTemplate.queryForObject(
                        "SELECT collection_key FROM rag_collection WHERE id = ?",
                        String.class, transitionId));
        assertEquals("NO", jdbcTemplate.queryForObject(
                "SELECT is_nullable FROM information_schema.columns "
                        + "WHERE table_name = 'rag_collection' "
                        + "AND column_name = 'collection_key'",
                String.class));
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbcTemplate.update(
                        "INSERT INTO rag_collection (name) VALUES ('Missing key')"));
    }

    @Test
    void finalConstraintsAreCaseSensitiveImmutableAndRetainedAfterSoftDelete() {
        flyway(MigrationVersion.fromVersion("28")).migrate();

        insertCollection("A");
        insertCollection("x".repeat(128));
        insertCollection("ABC");
        insertCollection("abc");

        assertEquals(4L, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rag_collection", Long.class));
        assertRejectedKey("");
        assertRejectedKey("has space");
        assertRejectedKey("中文");
        assertRejectedKey("line\nbreak");
        assertRejectedKey("x".repeat(129));

        assertThrows(DataIntegrityViolationException.class,
                () -> insertCollection("ABC"));

        Long softDeletedId = insertCollection("retained-after-delete");
        jdbcTemplate.update(
                "UPDATE rag_collection SET deleted = true WHERE id = ?",
                softDeletedId);
        assertThrows(DataIntegrityViolationException.class,
                () -> insertCollection("retained-after-delete"));

        Long immutableId = insertCollection("immutable-key");
        assertThrows(DataIntegrityViolationException.class,
                () -> jdbcTemplate.update(
                        "UPDATE rag_collection SET collection_key = ? WHERE id = ?",
                        "replacement-key", immutableId));
        assertEquals("immutable-key", jdbcTemplate.queryForObject(
                "SELECT collection_key FROM rag_collection WHERE id = ?",
                String.class, immutableId));
    }

    @Test
    void concurrentCreationOfTheSameKeyAllowsExactlyOneCommit() throws Exception {
        flyway(MigrationVersion.fromVersion("28")).migrate();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<Boolean> insert = () -> insertConcurrently(
                    "concurrent-key", ready, start);
            List<Future<Boolean>> futures = List.of(
                    executor.submit(insert), executor.submit(insert));
            ready.await();
            start.countDown();

            long successes = 0;
            for (Future<Boolean> future : futures) {
                if (future.get()) {
                    successes++;
                }
            }
            assertEquals(1L, successes);
            assertEquals(1L, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM rag_collection "
                            + "WHERE collection_key = 'concurrent-key'",
                    Long.class));
        } finally {
            executor.shutdownNow();
        }
    }

    private Long insertCollection(String key) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO rag_collection (collection_key, name) "
                        + "VALUES (?, 'Collection key test') RETURNING id",
                Long.class, key);
    }

    private void assertRejectedKey(String key) {
        assertThrows(DataIntegrityViolationException.class,
                () -> insertCollection(key));
    }

    private boolean insertConcurrently(
            String key, CountDownLatch ready, CountDownLatch start)
            throws InterruptedException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO rag_collection (collection_key, name) "
                             + "VALUES (?, 'Concurrent test')")) {
            connection.setAutoCommit(false);
            statement.setString(1, key);
            ready.countDown();
            start.await();
            statement.executeUpdate();
            connection.commit();
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    private static Flyway flyway(MigrationVersion target) {
        var configuration = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(false);
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    private static DataSource dataSource(PostgreSQLContainer<?> container) {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(container.getJdbcUrl());
        dataSource.setUser(container.getUsername());
        dataSource.setPassword(container.getPassword());
        return dataSource;
    }
}
