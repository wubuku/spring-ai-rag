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
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Real PostgreSQL acceptance tests for the JSONB structured-record migration.
 *
 * <p>Run explicitly with {@code -Djsonb.it.enabled=true}. The image can be
 * overridden with {@code -Dtestcontainers.pg.image=} or
 * {@code TESTCONTAINERS_PG_IMAGE}; no regional mirror is hard-coded here.
 */
class JsonbStructuredRecordsPostgresIntegrationTest {

    private static PostgreSQLContainer<?> postgres;
    private static JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void startDatabase() {
        assumeTrue(Boolean.getBoolean("jsonb.it.enabled"),
                "Set -Djsonb.it.enabled=true to run PostgreSQL JSONB integration tests");

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

        jdbcTemplate = new JdbcTemplate(dataSource(postgres));
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
                .dataSource(dataSource(postgres))
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load()
                .clean();
        Flyway.configure()
                .dataSource(dataSource(postgres))
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
