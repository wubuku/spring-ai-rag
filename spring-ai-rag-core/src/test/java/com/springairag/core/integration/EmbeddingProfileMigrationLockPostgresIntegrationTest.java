package com.springairag.core.integration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@EnabledIfSystemProperty(named = "embedding-profile-migration.it.enabled", matches = "true")
class EmbeddingProfileMigrationLockPostgresIntegrationTest {

    private static PostgreSQLContainer<?> postgres;
    private static DataSource dataSource;

    @BeforeAll
    static void startDatabase() {
        assumeTrue(Boolean.getBoolean("embedding-profile-migration.it.enabled"),
                "Set -Dembedding-profile-migration.it.enabled=true to run this test");
        try {
            assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                    "Docker is unavailable for PostgreSQL migration tests");
        } catch (RuntimeException unavailable) {
            assumeTrue(false, "Docker is unavailable: " + unavailable.getMessage());
        }
        String image = System.getProperty(
                "testcontainers.pg.image",
                System.getenv().getOrDefault("TESTCONTAINERS_PG_IMAGE", "pgvector/pgvector:pg16"));
        postgres = new PostgreSQLContainer<>(
                DockerImageName.parse(image).asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("embedding_profile_migration")
                .withUsername("postgres")
                .withPassword("postgres");
        postgres.start();
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setURL(postgres.getJdbcUrl());
        source.setUser(postgres.getUsername());
        source.setPassword(postgres.getPassword());
        dataSource = source;
        migrateTo("66");
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    void v67AndV68FailFastWithoutPartialMigrationAndRetryAfterLockRelease() throws Exception {
        migrateTo("66");

        try (Connection blocker = holdReadLock("rag_collection")) {
            Instant started = Instant.now();
            assertThrows(FlywayException.class, () -> migrateTo("67"));
            assertTrue(Duration.between(started, Instant.now()).toSeconds() < 15);
            assertEquals("66", latestInstalled());
            assertFalse(columnExists("rag_collection", "active_embedding_profile_id"));
        }
        migrateTo("67");
        assertEquals("67", latestInstalled());

        try (Connection blocker = holdReadLock("rag_embeddings")) {
            Instant started = Instant.now();
            assertThrows(FlywayException.class, () -> migrateTo("68"));
            assertTrue(Duration.between(started, Instant.now()).toSeconds() < 15);
            assertEquals("67", latestInstalled());
            assertTrue(columnExists("rag_embeddings", "embedding"));
            assertEquals("PENDING", backfillStatus());
        }
        migrateTo("68");
        assertEquals("68", latestInstalled());
        assertFalse(columnExists("rag_embeddings", "embedding"));
    }

    private static Connection holdReadLock(String table) throws Exception {
        Connection connection = dataSource.getConnection();
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            statement.executeQuery("SELECT 1 FROM " + table + " LIMIT 1").close();
        }
        return connection;
    }

    private static Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .target(target == null ? MigrationVersion.LATEST : MigrationVersion.fromVersion(target))
                .load();
    }

    private static void migrateTo(String target) {
        flyway(target).migrate();
    }

    private static String latestInstalled() {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             var result = statement.executeQuery("""
                     SELECT version FROM flyway_schema_history
                     WHERE success = TRUE ORDER BY installed_rank DESC LIMIT 1
                     """)) {
            if (!result.next()) {
                throw new IllegalStateException("Flyway history is empty");
            }
            return result.getString(1);
        } catch (Exception e) {
            throw new IllegalStateException("Could not read Flyway history", e);
        }
    }

    private static boolean columnExists(String table, String column) {
        try (Connection connection = dataSource.getConnection();
             var statement = connection.prepareStatement("""
                     SELECT EXISTS (
                         SELECT 1 FROM information_schema.columns
                         WHERE table_schema = current_schema() AND table_name = ? AND column_name = ?
                     )
                     """)) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (var result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Could not inspect migration column", e);
        }
    }

    private static String backfillStatus() {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             var result = statement.executeQuery("""
                     SELECT status FROM rag_embedding_backfill_control
                     WHERE migration_key = 'remove-legacy-embedding-v1'
                     """)) {
            if (!result.next()) {
                throw new IllegalStateException("Embedding backfill control row is missing");
            }
            return result.getString(1);
        } catch (Exception e) {
            throw new IllegalStateException("Could not inspect embedding backfill status", e);
        }
    }
}
