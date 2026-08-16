package com.springairag.core.integration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationVersion;
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
    private static DataSource testDataSource;
    private static JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void startDatabase() {
        assumeTrue(Boolean.getBoolean("external-document.it.enabled"),
                "Set -Dexternal-document.it.enabled=true to run PostgreSQL integration tests");

        String externalJdbcUrl = System.getenv("EXTERNAL_DOCUMENT_IT_JDBC_URL");
        if (externalJdbcUrl != null && !externalJdbcUrl.isBlank()) {
            if (!"YES".equals(System.getenv("EXTERNAL_DOCUMENT_IT_CLEAN_CONFIRM"))) {
                throw new IllegalStateException(
                        "Set EXTERNAL_DOCUMENT_IT_CLEAN_CONFIRM=YES only for a disposable database");
            }
            testDataSource = dataSource(
                    externalJdbcUrl,
                    System.getenv("EXTERNAL_DOCUMENT_IT_USERNAME"),
                    System.getenv("EXTERNAL_DOCUMENT_IT_PASSWORD"));
            jdbcTemplate = new JdbcTemplate(testDataSource);
            return;
        }

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
        testDataSource = dataSource(
                postgres.getJdbcUrl(),
                postgres.getUsername(),
                postgres.getPassword());
        jdbcTemplate = new JdbcTemplate(testDataSource);
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
        assertEquals("YES", jdbcTemplate.queryForObject(
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

    @Test
    void v29ToV31NormalizesLegacyExternalIdsBeforeEnforcingIdentity() {
        migrateToV29();
        long collectionId = insertCollection();
        long documentId = insertLegacyDocument(collectionId, "\t legacy-id \n");

        flyway().migrate();

        assertEquals("legacy-id", jdbcTemplate.queryForObject(
                "SELECT external_id FROM rag_documents WHERE id = ?",
                String.class, documentId));
        assertThrows(DataIntegrityViolationException.class, () -> insertDocument(
                collectionId, "legacy-id", "json-record"));
    }

    @Test
    void v30ToV31NormalizesLegacyExternalIdsWithoutChecksumRepair() {
        migrateToV30();
        long collectionId = insertCollection();
        long documentId = insertDocument(collectionId, "\t legacy-id \n", "text");

        flyway().migrate();

        assertEquals("legacy-id", jdbcTemplate.queryForObject(
                "SELECT external_id FROM rag_documents WHERE id = ?",
                String.class, documentId));
        assertThrows(DataIntegrityViolationException.class, () -> insertDocument(
                collectionId, "legacy-id", "json-record"));
    }

    @Test
    void v31RejectsNormalizedExternalIdentityDuplicatesWithoutChangingRows() {
        migrateToV29();
        long collectionId = insertCollection();
        long firstDocumentId = insertLegacyDocument(collectionId, "legacy-id");
        long secondDocumentId = insertLegacyDocument(collectionId, "\tlegacy-id\n");

        assertThrows(FlywayException.class, () -> flyway().migrate());
        assertEquals(2L, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rag_documents WHERE id IN (?, ?)",
                Long.class, firstDocumentId, secondDocumentId));
        assertEquals("\tlegacy-id\n", jdbcTemplate.queryForObject(
                "SELECT external_id FROM rag_documents WHERE id = ?",
                String.class, secondDocumentId));
        assertEquals(1L, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pg_indexes "
                        + "WHERE schemaname = 'public' "
                        + "AND indexname = 'uk_rag_doc_external_identity'",
                Long.class));
        assertEquals("30", jdbcTemplate.queryForObject(
                "SELECT version FROM flyway_schema_history "
                        + "WHERE success = true ORDER BY installed_rank DESC LIMIT 1",
                String.class));
    }

    private void migrateToV29() {
        flyway().clean();
        flyway(MigrationVersion.fromVersion("29")).migrate();
    }

    private void migrateToV30() {
        flyway().clean();
        flyway(MigrationVersion.fromVersion("30")).migrate();
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

    private long insertLegacyDocument(long collectionId, String externalId) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO rag_documents "
                        + "(collection_id, title, content, document_type, external_id, "
                        + "content_hash, processing_status) "
                        + "VALUES (?, 'Legacy document', 'Content', 'text', ?, "
                        + "'legacy-hash-' || ?, 'PENDING') RETURNING id",
                Long.class, collectionId, externalId, UUID.randomUUID().toString());
    }

    private Flyway flyway() {
        return flyway(null);
    }

    private Flyway flyway(MigrationVersion target) {
        var configuration = Flyway.configure()
                .dataSource(testDataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(false);
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    private static DataSource dataSource(
            String jdbcUrl, String username, String password) {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(jdbcUrl);
        dataSource.setUser(username);
        dataSource.setPassword(password);
        return dataSource;
    }
}
