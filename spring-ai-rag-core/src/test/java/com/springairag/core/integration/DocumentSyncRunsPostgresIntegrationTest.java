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
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Real PostgreSQL acceptance tests for V42 sync-run ledger semantics.
 *
 * <p>Run with {@code -Ddocument-sync-runs.it.enabled=true}.
 */
class DocumentSyncRunsPostgresIntegrationTest {

    private static PostgreSQLContainer<?> postgres;
    private static DataSource dataSource;
    private JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void startDatabase() {
        assumeTrue(Boolean.getBoolean("document-sync-runs.it.enabled"),
                "Set -Ddocument-sync-runs.it.enabled=true to run this test");
        String externalUrl = System.getenv("DOCUMENT_SYNC_RUNS_IT_JDBC_URL");
        if (externalUrl != null && !externalUrl.isBlank()) {
            if (!"YES".equals(System.getenv(
                    "DOCUMENT_SYNC_RUNS_IT_CLEAN_CONFIRM"))) {
                throw new IllegalStateException(
                        "Set DOCUMENT_SYNC_RUNS_IT_CLEAN_CONFIRM=YES for a disposable database");
            }
            dataSource = dataSource(
                    externalUrl,
                    System.getenv("DOCUMENT_SYNC_RUNS_IT_USERNAME"),
                    System.getenv("DOCUMENT_SYNC_RUNS_IT_PASSWORD"));
            return;
        }
        try {
            assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                    "Docker is not available for PostgreSQL integration tests");
        } catch (RuntimeException unavailable) {
            assumeTrue(false, "Docker is not available: " + unavailable.getMessage());
        }
        String image = System.getProperty(
                "testcontainers.pg.image",
                System.getenv().getOrDefault(
                        "TESTCONTAINERS_PG_IMAGE", "pgvector/pgvector:pg16"));
        postgres = new PostgreSQLContainer<>(
                DockerImageName.parse(image)
                        .asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("spring_ai_rag_sync_runs_test")
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
    void migrate() {
        flyway().clean();
        flyway().migrate();
        jdbcTemplate = new JdbcTemplate(dataSource);
    }

    @Test
    void v42CreatesLedgerAndProtectsOneActiveRunPerNamespace() {
        long collectionId = insertCollection("sync-run");
        UUID first = insertRun(collectionId, "cms-main", "run-1");

        assertThrows(DataIntegrityViolationException.class, () ->
                insertRun(collectionId, "cms-main", "run-2"));

        assertEquals(1L, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pg_indexes "
                        + "WHERE indexname = 'uk_rag_sync_run_active_scope'",
                Long.class));
        assertEquals("cms-main", jdbcTemplate.queryForObject(
                "SELECT source_namespace FROM rag_document_sync_runs WHERE id = ?",
                String.class,
                first));
        jdbcTemplate.update(
                "UPDATE rag_document_sync_runs SET status = 'ABORTED' WHERE id = ?",
                first);
        assertEquals(1, jdbcTemplate.update("""
                INSERT INTO rag_document_sync_runs (
                    id, collection_id, source_namespace, client_run_id,
                    lease_token_hash, sync_generation, snapshot_start_sequence,
                    snapshot_mode, missing_policy, status, lease_expires_at
                ) VALUES (?, ?, 'cms-main', 'run-2', 'hash-2', 2, 2,
                    'ONLINE_CUT', 'NONE', 'ACTIVE',
                    CURRENT_TIMESTAMP + INTERVAL '15 minutes')
                """, UUID.randomUUID(), collectionId));
    }

    @Test
    void ledgerDoesNotStoreBodiesAndRunItemIdentityIsIdempotent() {
        long collectionId = insertCollection("items");
        UUID runId = insertRun(collectionId, "cms-main", "run-1");
        assertEquals(1, jdbcTemplate.update("""
                INSERT INTO rag_document_sync_run_items (
                    run_id, external_id, document_kind, item_fingerprint,
                    source_revision, status
                ) VALUES (?, 'article-1', 'TEXT', 'fingerprint', 'r1', 'APPLIED')
                """, runId));
        assertThrows(DataIntegrityViolationException.class, () ->
                jdbcTemplate.update("""
                    INSERT INTO rag_document_sync_run_items (
                        run_id, external_id, document_kind, item_fingerprint,
                        source_revision, status
                    ) VALUES (?, 'article-1', 'TEXT', 'other', 'r2', 'APPLIED')
                    """, runId));
        assertEquals(0L, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_name = 'rag_document_sync_run_items' "
                        + "AND column_name IN ('content', 'jsonb_payload')",
                Long.class));
    }

    @Test
    void reconciliationMarkerAndDeletionOriginAreConstrained() {
        long collectionId = insertCollection("markers");
        long documentId = insertDocument(collectionId, "cms-main", "article-1");
        UUID runId = insertRun(collectionId, "cms-main", "run-1");
        jdbcTemplate.update("""
                UPDATE rag_documents
                SET enabled = false, source_deleted_at = CURRENT_TIMESTAMP,
                    deletion_origin = 'RECONCILIATION',
                    reconciliation_tombstone_run_id = ?
                WHERE id = ?
                """, runId, documentId);
        assertEquals("RECONCILIATION", jdbcTemplate.queryForObject(
                "SELECT deletion_origin FROM rag_documents WHERE id = ?",
                String.class, documentId));
        assertThrows(DataIntegrityViolationException.class, () ->
                jdbcTemplate.update(
                        "UPDATE rag_documents SET deletion_origin = 'CLIENT' WHERE id = ?",
                        documentId));
    }

    private UUID insertRun(long collectionId, String namespace, String clientRunId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO rag_document_sync_runs (
                    id, collection_id, source_namespace, client_run_id,
                    lease_token_hash, sync_generation, snapshot_start_sequence,
                    snapshot_mode, missing_policy, status, lease_expires_at
                ) VALUES (?, ?, ?, ?, ?, 1, 1, 'ONLINE_CUT', 'NONE', 'ACTIVE',
                    CURRENT_TIMESTAMP + INTERVAL '15 minutes')
                """,
                id, collectionId, namespace, clientRunId, "hash-" + clientRunId);
        return id;
    }

    private long insertCollection(String suffix) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO rag_collection (collection_key, name, dimensions) "
                        + "VALUES (?, ?, 1024) RETURNING id",
                Long.class,
                "sync-" + suffix + "-" + UUID.randomUUID(),
                suffix);
    }

    private long insertDocument(
            long collectionId, String namespace, String externalId) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO rag_documents (
                    collection_id, title, content, document_type,
                    external_id, source_namespace, source_revision,
                    content_hash, processing_status
                ) VALUES (?, 'Document', 'Content', 'text', ?, ?, 'r1',
                    'hash', 'PENDING')
                RETURNING id
                """,
                Long.class,
                collectionId,
                externalId,
                namespace);
    }

    private Flyway flyway() {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load();
    }

    private static DataSource dataSource(
            String url,
            String username,
            String password) {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(url);
        dataSource.setUser(username);
        dataSource.setPassword(password);
        return dataSource;
    }
}
