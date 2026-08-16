package com.springairag.core.integration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * PostgreSQL acceptance tests for external document synchronization schema.
 *
 * <p>Run explicitly with {@code -Dexternal-document.it.enabled=true}.
 */
class ExternalDocumentSyncPostgresIntegrationTest {

    private static PostgreSQLContainer<?> postgres;
    private static JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void startDatabase() {
        assumeTrue(Boolean.getBoolean("external-document.it.enabled"),
                "Set -Dexternal-document.it.enabled=true to run PostgreSQL integration tests");
        try {
            assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                    "Docker is not available for PostgreSQL integration tests");
        } catch (RuntimeException unavailable) {
            assumeTrue(false,
                    "Docker is not available for PostgreSQL integration tests: "
                            + unavailable.getMessage());
        }

        String image = System.getProperty(
                "testcontainers.pg.image",
                System.getenv().getOrDefault(
                        "TESTCONTAINERS_PG_IMAGE", "pgvector/pgvector:pg16"));
        postgres = new PostgreSQLContainer<>(
                DockerImageName.parse(image).asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("spring_ai_rag_external_document_test")
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
        flyway().clean();
        flyway().migrate();
    }

    @Test
    void v30AddsSourceStateAndEnforcesCollectionWideExternalIdentity() {
        long collectionId = insertCollection();
        long documentId = insertDocument(collectionId, "shared-id", "text");

        assertEquals("rev-1", jdbcTemplate.queryForObject(
                "SELECT source_revision FROM rag_documents WHERE id = ?",
                String.class, documentId));
        assertEquals("NO", jdbcTemplate.queryForObject(
                "SELECT is_nullable FROM information_schema.columns "
                        + "WHERE table_name = 'rag_documents' "
                        + "AND column_name = 'source_revision'",
                String.class));

        jdbcTemplate.update(
                "UPDATE rag_documents SET enabled = false, source_deleted_at = ? "
                        + "WHERE id = ?",
                LocalDateTime.now(), documentId);
        assertEquals(Boolean.FALSE, jdbcTemplate.queryForObject(
                "SELECT enabled FROM rag_documents WHERE id = ?",
                Boolean.class, documentId));
        assertNotNull(jdbcTemplate.queryForObject(
                "SELECT source_deleted_at FROM rag_documents WHERE id = ?",
                LocalDateTime.class, documentId));

        assertThrows(DataIntegrityViolationException.class, () -> insertDocument(
                collectionId, "shared-id", "json-record"));
    }

    @Test
    void sameExternalIdentityCanBeUsedInDifferentCollections() {
        long firstCollection = insertCollection();
        long secondCollection = insertCollection();

        assertNotNull(insertDocument(firstCollection, "same-id", "text"));
        assertNotNull(insertDocument(secondCollection, "same-id", "text"));
        assertEquals(2L, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rag_documents WHERE external_id = 'same-id'",
                Long.class));
    }

    private long insertCollection() {
        return jdbcTemplate.queryForObject(
                "INSERT INTO rag_collection "
                        + "(collection_key, name, dimensions) "
                        + "VALUES (?, 'External document test', 1024) RETURNING id",
                Long.class, "external-" + UUID.randomUUID());
    }

    private long insertDocument(
            long collectionId, String externalId, String documentType) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO rag_documents "
                        + "(collection_id, title, content, document_type, external_id, "
                        + "source_revision, content_hash, processing_status) "
                        + "VALUES (?, 'External document', 'Content', ?, ?, 'rev-1', "
                        + "'hash', 'PENDING') RETURNING id",
                Long.class, collectionId, documentType, externalId);
    }

    private Flyway flyway() {
        return Flyway.configure()
                .dataSource(dataSource(postgres))
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load();
    }

    private static DataSource dataSource(
            PostgreSQLContainer<?> container) {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(container.getJdbcUrl());
        dataSource.setUser(container.getUsername());
        dataSource.setPassword(container.getPassword());
        return dataSource;
    }
}
