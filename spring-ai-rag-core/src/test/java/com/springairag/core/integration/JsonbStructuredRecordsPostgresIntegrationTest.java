package com.springairag.core.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real PostgreSQL acceptance tests for the JSONB structured-record migration.
 *
 * <p>Run explicitly with {@code -Djsonb.it.enabled=true}. The image can be
 * overridden with {@code -Dtestcontainers.pg.image=} or
 * {@code TESTCONTAINERS_PG_IMAGE}; no regional mirror is hard-coded here.
 */
class JsonbStructuredRecordsPostgresIntegrationTest {

    private static PostgreSQLContainer<?> postgres;
    private static DataSource dataSource;
    private static JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void startDatabase() {
        assumeTrue(Boolean.getBoolean("jsonb.it.enabled"),
                "Set -Djsonb.it.enabled=true to run PostgreSQL JSONB integration tests");

        String externalJdbcUrl = System.getProperty("jsonb.it.jdbc-url");
        if (externalJdbcUrl != null && !externalJdbcUrl.isBlank()) {
            PGSimpleDataSource pg = new PGSimpleDataSource();
            pg.setUrl(externalJdbcUrl);
            pg.setUser(System.getProperty(
                    "jsonb.it.username", "postgres"));
            pg.setPassword(System.getProperty(
                    "jsonb.it.password", "postgres"));
            dataSource = pg;
            jdbcTemplate = new JdbcTemplate(dataSource);
            return;
        }
        String image = System.getProperty(
                "testcontainers.pg.image",
                System.getenv().getOrDefault(
                        "TESTCONTAINERS_PG_IMAGE", "pgvector/pgvector:pg16"));
        DockerImageName imageName = DockerImageName.parse(image)
                .asCompatibleSubstituteFor("postgres");
        postgres = new PostgreSQLContainer<>(imageName)
                .withDatabaseName("spring_ai_rag_jsonb_test")
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
    void migrateFromEmptyDatabase() {
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load()
                .clean();
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @Test
    void jsonbPayloadRoundTripsAndPayloadOnlyVersionIsAllowed() {
        long collectionId = insertCollection();
        long documentId = insertJsonDocument(collectionId, "customer-1", "{\"name\":\"Alice\",\"age\":30}");

        assertEquals("{\"age\": 30, \"name\": \"Alice\"}",
                jdbcTemplate.queryForObject(
                        "SELECT jsonb_payload::text FROM rag_documents WHERE id = ?",
                        String.class, documentId));

        jdbcTemplate.update(
                "INSERT INTO rag_document_versions "
                        + "(document_id, version_number, content_hash, content_snapshot, "
                        + "change_type, jsonb_payload_snapshot) "
                        + "VALUES (?, 1, ?, ?, 'CREATE', ?::jsonb)",
                documentId, "same-description-hash", "Description",
                "{\"name\":\"Alice\",\"age\":30}");
        jdbcTemplate.update(
                "INSERT INTO rag_document_versions "
                        + "(document_id, version_number, content_hash, content_snapshot, "
                        + "change_type, jsonb_payload_snapshot) "
                        + "VALUES (?, 2, ?, ?, 'UPDATE', ?::jsonb)",
                documentId, "same-description-hash", "Description",
                "{\"name\":\"Alice\",\"age\":31}");

        assertEquals(2L, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rag_document_versions WHERE document_id = ?",
                Long.class, documentId));
        assertEquals("{\"age\": 31, \"name\": \"Alice\"}",
                jdbcTemplate.queryForObject(
                        "SELECT jsonb_payload_snapshot::text FROM rag_document_versions "
                                + "WHERE document_id = ? AND version_number = 2",
                        String.class, documentId));
    }

    @Test
    void documentDeleteCascadesVersionAndEmbeddingStateRows() {
        long collectionId = insertCollection();
        long documentId = insertJsonDocument(collectionId, "customer-2", "{\"id\":2}");
        long profileId = jdbcTemplate.queryForObject(
                "INSERT INTO rag_embedding_profiles "
                        + "(profile_key, provider, model_name, model_revision, dimensions, "
                        + "distance_metric, normalization) "
                        + "VALUES (?, 'test', 'test-model', 'v1', 1024, 'COSINE', 'NONE') "
                        + "RETURNING id",
                Long.class, "jsonb-" + UUID.randomUUID());

        jdbcTemplate.update(
                "INSERT INTO rag_document_versions "
                        + "(document_id, version_number, content_hash, content_snapshot, change_type) "
                        + "VALUES (?, 1, 'hash', 'Description', 'CREATE')",
                documentId);
        jdbcTemplate.update(
                "INSERT INTO rag_document_embedding_state "
                        + "(document_id, embedding_profile_id, content_hash, chunker_version, "
                        + "status, chunk_count) VALUES (?, ?, 'hash', 'json-record-v1:single', "
                        + "'COMPLETED', 1)",
                documentId, profileId);

        jdbcTemplate.update("DELETE FROM rag_documents WHERE id = ?", documentId);

        assertEquals(0L, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rag_document_versions WHERE document_id = ?",
                Long.class, documentId));
        assertEquals(0L, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rag_document_embedding_state WHERE document_id = ?",
                Long.class, documentId));
    }

    @Test
    void payloadContainmentUsesNestedSemanticsAndGinIndex() {
        long collectionId = insertCollection();
        insertJsonDocument(
                collectionId,
                "active-sofa",
                "{\"status\":\"active\",\"category\":{\"code\":\"sofa\"}}");
        insertJsonDocument(
                collectionId,
                "inactive-sofa",
                "{\"status\":\"inactive\",\"category\":{\"code\":\"sofa\"}}");
        jdbcTemplate.update(
                "INSERT INTO rag_documents "
                        + "(collection_id, title, content, document_type, external_id, "
                        + "content_hash, jsonb_payload, processing_status) "
                        + "SELECT ?, 'JSON record', 'Description', 'json-record', "
                        + "'noise-' || series_id, 'same-description-hash', "
                        + "jsonb_build_object('status', 'inactive', 'sequence', series_id), "
                        + "'PENDING' "
                        + "FROM generate_series(1, 5000) AS series_id",
                collectionId);
        jdbcTemplate.execute("ANALYZE rag_documents");

        assertEquals(1L, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rag_documents "
                        + "WHERE collection_id = ? "
                        + "AND document_type = 'json-record' "
                        + "AND enabled = true "
                        + "AND jsonb_payload @> ?::jsonb",
                Long.class,
                collectionId,
                "{\"status\":\"active\","
                        + "\"category\":{\"code\":\"sofa\"}}"));

        jdbcTemplate.execute("SET enable_seqscan = off");
        List<String> plan = jdbcTemplate.query(
                "EXPLAIN SELECT id FROM rag_documents "
                        + "WHERE document_type = 'json-record' "
                        + "AND enabled = true "
                        + "AND jsonb_payload @> ?::jsonb",
                (resultSet, rowNum) -> resultSet.getString(1),
                "{\"status\":\"active\"}");
        assertTrue(
                plan.stream().anyMatch(line ->
                        line.contains(
                                "idx_rag_documents_jsonb_payload_path_ops")),
                () -> "Expected V34 GIN index in query plan: " + plan);
    }

    private long insertCollection() {
        return jdbcTemplate.queryForObject(
                "INSERT INTO rag_collection "
                        + "(collection_key, name, dimensions) VALUES (?, 'JSONB test', 1024) "
                        + "RETURNING id",
                Long.class, "jsonb-" + UUID.randomUUID());
    }

    private long insertJsonDocument(
            long collectionId, String externalId, String payload) {
        Long id = jdbcTemplate.queryForObject(
                "INSERT INTO rag_documents "
                        + "(collection_id, title, content, document_type, external_id, "
                        + "content_hash, jsonb_payload, processing_status) "
                        + "VALUES (?, 'JSON record', 'Description', 'json-record', ?, "
                        + "'same-description-hash', ?::jsonb, 'PENDING') RETURNING id",
                Long.class, collectionId, externalId, payload);
        assertNotNull(id);
        return id;
    }

    private static DataSource dataSource(PostgreSQLContainer<?> container) {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(container.getJdbcUrl());
        dataSource.setUser(container.getUsername());
        dataSource.setPassword(container.getPassword());
        return dataSource;
    }
}
