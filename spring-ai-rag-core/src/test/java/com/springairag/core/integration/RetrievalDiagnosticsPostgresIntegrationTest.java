package com.springairag.core.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * V1–V35 迁移、JSONB metadata 和 owner predicate 的真实 PostgreSQL 验收。
 */
class RetrievalDiagnosticsPostgresIntegrationTest {

    private static PostgreSQLContainer<?> postgres;
    private static DataSource dataSource;
    private JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void startDatabase() {
        assumeTrue(Boolean.getBoolean("retrieval-diagnostics.it.enabled"),
                "Set -Dretrieval-diagnostics.it.enabled=true to run this test");
        String externalJdbcUrl = System.getProperty(
                "retrieval-diagnostics.it.jdbc-url");
        if (externalJdbcUrl != null && !externalJdbcUrl.isBlank()) {
            PGSimpleDataSource pg = new PGSimpleDataSource();
            pg.setUrl(externalJdbcUrl);
            pg.setUser(System.getProperty(
                    "retrieval-diagnostics.it.username", "postgres"));
            pg.setPassword(System.getProperty(
                    "retrieval-diagnostics.it.password", "postgres"));
            dataSource = pg;
            return;
        }
        String image = System.getProperty(
                "testcontainers.pg.image",
                System.getenv().getOrDefault(
                        "TESTCONTAINERS_PG_IMAGE",
                        "pgvector/pgvector:pg16"));
        postgres = new PostgreSQLContainer<>(
                DockerImageName.parse(image)
                        .asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("spring_ai_rag_retrieval_diagnostics_test")
                .withUsername("postgres")
                .withPassword("postgres");
        postgres.start();
        PGSimpleDataSource pg = new PGSimpleDataSource();
        pg.setUrl(postgres.getJdbcUrl());
        pg.setUser(postgres.getUsername());
        pg.setPassword(postgres.getPassword());
        dataSource = pg;
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @BeforeEach
    void migrate() {
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();
        jdbcTemplate = new JdbcTemplate(dataSource);
    }

    @Test
    void migratesToV35AndEnforcesOwnerAndTraceUniqueness() {
        Integer version = jdbcTemplate.queryForObject(
                "SELECT version FROM flyway_schema_history "
                        + "ORDER BY installed_rank DESC LIMIT 1",
                Integer.class);
        assertTrue(version >= 35);

        UUID traceId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO rag_retrieval_logs "
                        + "(query, retrieval_strategy, result_count, total_time_ms, "
                        + "result_scores, metadata, trace_id, owner_principal_id, "
                        + "operation, outcome_code, empty_reason_code, session_id) "
                        + "VALUES (?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), "
                        + "?, ?, ?, ?, ?, ?)",
                "[redacted]",
                "hybrid",
                1,
                15L,
                "{\"rank_1\":0.81}",
                "{\"schemaVersion\":1,\"scope\":{\"collectionScopeMode\":\"SELECTED_COLLECTIONS\",\"collectionKeys\":[\"furniture\"]}}",
                traceId,
                "db:owner",
                "SEARCH",
                "RESULTS_RETURNED",
                null,
                "sess-1");

        Integer ownerCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rag_retrieval_logs "
                        + "WHERE owner_principal_id = ? AND trace_id IS NOT NULL",
                Integer.class, "db:owner");
        Integer otherCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rag_retrieval_logs "
                        + "WHERE owner_principal_id = ? AND trace_id IS NOT NULL",
                Integer.class, "db:other");
        assertEquals(1, ownerCount);
        assertEquals(0, otherCount);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT query, metadata #>> '{schemaVersion}' AS schema_version, "
                        + "result_scores ?? 'rank_1' AS has_rank, "
                        + "result_scores ?? 'doc-9' AS has_doc "
                        + "FROM rag_retrieval_logs WHERE trace_id = ?",
                traceId);
        assertEquals("[redacted]", row.get("query"));
        assertEquals("1", String.valueOf(row.get("schema_version")));
        assertTrue(Boolean.TRUE.equals(row.get("has_rank")));
        assertTrue(Boolean.FALSE.equals(row.get("has_doc")));

        Boolean unique = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) = 1 FROM pg_indexes "
                        + "WHERE indexname = 'idx_rag_retrieval_logs_trace_id'",
                Boolean.class);
        assertTrue(Boolean.TRUE.equals(unique));
    }
}
