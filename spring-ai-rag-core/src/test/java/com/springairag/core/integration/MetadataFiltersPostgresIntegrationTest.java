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
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * V1–V36 迁移、metadata @> 语义和 GIN planner 证据。
 */
class MetadataFiltersPostgresIntegrationTest {

    private static PostgreSQLContainer<?> postgres;
    private static DataSource dataSource;
    private JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void startDatabase() {
        assumeTrue(Boolean.getBoolean("retrieval-filters.it.enabled"),
                "Set -Dretrieval-filters.it.enabled=true to run this test");
        String externalJdbcUrl = System.getProperty("retrieval-filters.it.jdbc-url");
        if (externalJdbcUrl != null && !externalJdbcUrl.isBlank()) {
            PGSimpleDataSource pg = new PGSimpleDataSource();
            pg.setUrl(externalJdbcUrl);
            pg.setUser(System.getProperty("retrieval-filters.it.username", "postgres"));
            pg.setPassword(System.getProperty("retrieval-filters.it.password", "postgres"));
            dataSource = pg;
            return;
        }
        String image = System.getProperty(
                "testcontainers.pg.image",
                System.getenv().getOrDefault(
                        "TESTCONTAINERS_PG_IMAGE",
                        "pgvector/pgvector:pg16"));
        postgres = new PostgreSQLContainer<>(
                DockerImageName.parse(image).asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("spring_ai_rag_retrieval_filters_test")
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
    void metadataContainmentDoesNotWidenAndUsesGin() {
        Integer version = jdbcTemplate.queryForObject(
                "SELECT version FROM flyway_schema_history "
                        + "ORDER BY installed_rank DESC LIMIT 1",
                Integer.class);
        assertTrue(version >= 36);

        long collectionId = jdbcTemplate.queryForObject(
                "INSERT INTO rag_collection "
                        + "(collection_key, name, dimensions) VALUES (?, 'filters', 1024) "
                        + "RETURNING id",
                Long.class, "filters-" + UUID.randomUUID());
        insertDocument(collectionId, "hit",
                "{\"tenant\":\"acme\",\"language\":\"zh-CN\",\"tags\":[\"policy\"]}",
                true);
        insertDocument(collectionId, "miss-tenant",
                "{\"tenant\":\"other\",\"language\":\"zh-CN\",\"tags\":[\"policy\"]}",
                true);
        insertDocument(collectionId, "disabled",
                "{\"tenant\":\"acme\",\"language\":\"zh-CN\",\"tags\":[\"policy\"]}",
                false);
        jdbcTemplate.update(
                "INSERT INTO rag_documents "
                        + "(collection_id, title, content, document_type, external_id, "
                        + "content_hash, metadata, processing_status, enabled) "
                        + "SELECT ?, 'noise', 'noise', 'TEXT', "
                        + "'noise-' || series_id, 'hash', "
                        + "jsonb_build_object('tenant', 'noise', 'sequence', series_id), "
                        + "'PENDING', true "
                        + "FROM generate_series(1, 5000) AS series_id",
                collectionId);
        jdbcTemplate.execute("ANALYZE rag_documents");

        assertEquals(1L, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rag_documents "
                        + "WHERE collection_id = ? AND enabled = true "
                        + "AND metadata @> ?::jsonb",
                Long.class, collectionId,
                "{\"tenant\":\"acme\",\"language\":\"zh-CN\"}"));
        assertEquals(2L, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rag_documents "
                        + "WHERE collection_id = ? AND enabled = true "
                        + "AND metadata @> ?::jsonb",
                Long.class, collectionId,
                "{\"tags\":[\"policy\"]}"));

        jdbcTemplate.execute("SET enable_seqscan = off");
        List<String> plan = jdbcTemplate.query(
                "EXPLAIN SELECT id FROM rag_documents "
                        + "WHERE enabled = true AND metadata @> ?::jsonb",
                (resultSet, rowNum) -> resultSet.getString(1),
                "{\"tenant\":\"acme\"}");
        assertTrue(
                plan.stream().anyMatch(line ->
                        line.contains("idx_rag_documents_metadata_path_ops")),
                () -> "Expected V36 GIN index in query plan: " + plan);
    }

    private void insertDocument(
            long collectionId, String externalId, String metadata, boolean enabled) {
        jdbcTemplate.update(
                "INSERT INTO rag_documents "
                        + "(collection_id, title, content, document_type, external_id, "
                        + "content_hash, metadata, processing_status, enabled) "
                        + "VALUES (?, ?, ?, 'TEXT', ?, 'hash', ?::jsonb, 'PENDING', ?)",
                collectionId, externalId, "content", externalId, metadata, enabled);
    }
}
