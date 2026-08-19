package com.springairag.core.integration;

import com.springairag.core.config.EmbeddingProfile;
import com.springairag.core.config.RagProperties;
import com.springairag.core.embeddingjob.EmbeddingJobRepository;
import com.springairag.core.embeddingjob.EmbeddingJobStatus;
import com.springairag.core.retrieval.RetrievalEmptyReasonProbe;
import com.springairag.core.retrieval.RetrievalFilters;
import com.springairag.core.retrieval.RetrievalScope;
import com.springairag.core.service.DocumentDerivationDescriptorProvider;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 文档 CRUD、版本快照和派生任务一致性的真实 PostgreSQL 验收。
 *
 * <p>显式通过 {@code -Ddocument-lifecycle.it.enabled=true} 运行。
 */
class DocumentLifecyclePostgresIntegrationTest {

    private static final String HASH_A =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                    + "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String HASH_B =
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
                    + "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String TEXT_CHUNKER =
            "hierarchical-v2:1000:100:100";

    private static PostgreSQLContainer<?> postgres;
    private static DataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private EmbeddingJobRepository jobRepository;

    @BeforeAll
    static void startDatabase() {
        assumeTrue(Boolean.getBoolean("document-lifecycle.it.enabled"),
                "Set -Ddocument-lifecycle.it.enabled=true to run this test");

        String externalJdbcUrl =
                System.getenv("DOCUMENT_LIFECYCLE_IT_JDBC_URL");
        if (externalJdbcUrl != null && !externalJdbcUrl.isBlank()) {
            if (!"YES".equals(System.getenv(
                    "DOCUMENT_LIFECYCLE_IT_CLEAN_CONFIRM"))) {
                throw new IllegalStateException(
                        "Set DOCUMENT_LIFECYCLE_IT_CLEAN_CONFIRM=YES "
                                + "only for a disposable database");
            }
            dataSource = dataSource(
                    externalJdbcUrl,
                    System.getenv("DOCUMENT_LIFECYCLE_IT_USERNAME"),
                    System.getenv("DOCUMENT_LIFECYCLE_IT_PASSWORD"));
            return;
        }

        try {
            assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                    "Docker is not available for lifecycle integration tests");
        } catch (RuntimeException unavailable) {
            assumeTrue(false,
                    "Docker is not available for lifecycle integration tests: "
                            + unavailable.getMessage());
        }

        String image = System.getProperty(
                "testcontainers.pg.image",
                System.getenv().getOrDefault(
                        "TESTCONTAINERS_PG_IMAGE",
                        "pgvector/pgvector:pg16"));
        postgres = new PostgreSQLContainer<>(
                DockerImageName.parse(image)
                        .asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("spring_ai_rag_document_lifecycle_test")
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
    void migrateFromEmptyDatabase() {
        flyway(null).clean();
        flyway(null).migrate();
        jdbcTemplate = new JdbcTemplate(dataSource);
        jobRepository = new EmbeddingJobRepository(jdbcTemplate);
    }

    @Test
    void v39UpgradeStalesActiveLegacyJobsAndPreservesTerminalHistory() {
        flyway(null).clean();
        flyway(MigrationVersion.fromVersion("39")).migrate();
        jdbcTemplate = new JdbcTemplate(dataSource);

        long collectionId = insertCollection("migration");
        long documentId = insertDocument(
                collectionId, null, "legacy-id", HASH_A);
        long profileId = insertProfile("migration");
        UUID queuedJob = insertLegacyJob(
                documentId, profileId, "QUEUED", null);
        UUID succeededJob = insertLegacyJob(
                documentId, profileId, "SUCCEEDED", "done");

        flyway(null).migrate();

        assertEquals("42", jdbcTemplate.queryForObject(
                "SELECT version FROM flyway_schema_history "
                        + "WHERE success = true "
                        + "ORDER BY installed_rank DESC LIMIT 1",
                String.class));
        assertEquals("default", jdbcTemplate.queryForObject(
                "SELECT source_namespace FROM rag_documents WHERE id = ?",
                String.class, documentId));
        assertEquals(1L, jdbcTemplate.queryForObject(
                "SELECT document_revision FROM rag_documents WHERE id = ?",
                Long.class, documentId));
        assertEquals("STALE", jdbcTemplate.queryForObject(
                "SELECT status FROM rag_embedding_jobs WHERE id = ?",
                String.class, queuedJob));
        assertEquals("SUCCEEDED", jdbcTemplate.queryForObject(
                "SELECT status FROM rag_embedding_jobs WHERE id = ?",
                String.class, succeededJob));
        assertEquals(0L, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rag_embedding_jobs "
                        + "WHERE status IN ('QUEUED', 'RUNNING') "
                        + "AND request_generation = 0",
                Long.class));
    }

    @Test
    void externalIdentityIncludesNamespaceAndRejectsDuplicateTriple() {
        long collectionId = insertCollection("namespace");

        assertNotNull(insertDocument(
                collectionId, "cms-main", "article-1", HASH_A));
        assertNotNull(insertDocument(
                collectionId, "erp-main", "article-1", HASH_B));
        assertEquals(2L, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rag_documents "
                        + "WHERE collection_id = ? AND external_id = ?",
                Long.class, collectionId, "article-1"));

        assertThrows(DataAccessException.class, () -> insertDocument(
                collectionId, "cms-main", "article-1", HASH_B));
    }

    @Test
    void freshnessChangesOnlyWhenDerivedInputChanges() {
        long collectionId = insertCollection("freshness");
        long documentId = insertDocument(
                collectionId, "default", null, HASH_A);
        long profileId = insertProfile("freshness");
        jdbcTemplate.update("""
                INSERT INTO rag_document_embedding_state (
                    document_id, embedding_profile_id, content_hash,
                    chunker_version, status, chunk_count,
                    request_generation
                ) VALUES (?, ?, ?, ?, 'COMPLETED', 1, 1)
                """,
                documentId, profileId, HASH_A, TEXT_CHUNKER);

        assertEquals(1L, freshDocumentCount(documentId, profileId));

        jdbcTemplate.update(
                "UPDATE rag_documents "
                        + "SET metadata = '{\"locale\":\"zh-CN\"}'::jsonb, "
                        + "version = version + 1 WHERE id = ?",
                documentId);
        assertEquals(1L, freshDocumentCount(documentId, profileId));

        jdbcTemplate.update(
                "UPDATE rag_documents "
                        + "SET content = 'new body', content_hash = ?, "
                        + "version = version + 1 WHERE id = ?",
                HASH_B, documentId);
        assertEquals(0L, freshDocumentCount(documentId, profileId));

        jdbcTemplate.update(
                "UPDATE rag_documents SET enabled = false WHERE id = ?",
                documentId);
        assertEquals(0L, freshDocumentCount(documentId, profileId));
    }

    @Test
    void readinessAndEmptyProbeRequireCurrentChunkerVersion() {
        long collectionId = insertCollection("current-chunker");
        long documentId = insertDocument(
                collectionId, "default", null, HASH_A);
        long profileId = insertProfile("current-chunker");
        jdbcTemplate.update("""
                INSERT INTO rag_document_embedding_state (
                    document_id, embedding_profile_id, content_hash,
                    chunker_version, status, chunk_count,
                    request_generation
                ) VALUES (?, ?, ?, 'hierarchical-v1:legacy',
                    'COMPLETED', 1, 1)
                """,
                documentId, profileId, HASH_A);
        EmbeddingProfile profile = new EmbeddingProfile(
                profileId,
                "lifecycle-current-chunker",
                "test",
                "model",
                "v1",
                1024,
                "COSINE",
                "NONE",
                true);
        DocumentDerivationDescriptorProvider descriptorProvider =
                new DocumentDerivationDescriptorProvider(
                        new RagProperties());
        RetrievalEmptyReasonProbe probe = new RetrievalEmptyReasonProbe(
                jdbcTemplate, descriptorProvider);

        var stale = jobRepository.readiness(
                collectionId,
                "lifecycle-current-chunker",
                profile,
                TEXT_CHUNKER,
                "json-record-v1:single");
        assertEquals(0, stale.freshDocuments());
        assertEquals(1, stale.staleOrMissingDocuments());
        var staleEligibility = probe.count(
                RetrievalScope.selectedCollections(
                        java.util.List.of(collectionId),
                        java.util.List.of(),
                        null),
                RetrievalFilters.none(),
                profile,
                2_000);
        assertEquals(1, staleEligibility.enabledDocuments());
        assertEquals(0, staleEligibility.freshDocuments());

        jdbcTemplate.update("""
                UPDATE rag_document_embedding_state
                SET chunker_version = ?
                WHERE document_id = ?
                  AND embedding_profile_id = ?
                """,
                TEXT_CHUNKER, documentId, profileId);

        var fresh = jobRepository.readiness(
                collectionId,
                "lifecycle-current-chunker",
                profile,
                TEXT_CHUNKER,
                "json-record-v1:single");
        assertEquals(1, fresh.freshDocuments());
        assertEquals(0, fresh.staleOrMissingDocuments());
        var freshEligibility = probe.count(
                RetrievalScope.selectedCollections(
                        java.util.List.of(collectionId),
                        java.util.List.of(),
                        null),
                RetrievalFilters.none(),
                profile,
                2_000);
        assertEquals(1, freshEligibility.freshDocuments());
    }

    @Test
    void cancelRetryAndExpiredLeaseSynchronizeLifecycleState() {
        long collectionId = insertCollection("job-state");
        long documentId = insertDocument(
                collectionId, "default", null, HASH_A);
        long profileId = insertProfile("job-state");
        long generation = jobRepository.allocateGeneration(
                documentId, profileId, HASH_A, TEXT_CHUNKER, false);
        var created = jobRepository.createOrCoalesce(
                UUID.randomUUID(),
                documentId,
                profileId,
                HASH_A,
                1L,
                false,
                1,
                "TEST",
                "integration",
                generation,
                "TEXT",
                TEXT_CHUNKER);
        jobRepository.activateJob(
                documentId, profileId, generation, created.job().id());

        jobRepository.cancel(created.job().id()).orElseThrow();
        assertEquals("CANCELLED", stateValue(
                documentId, profileId, "status", String.class));
        assertEquals(0L, jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM rag_document_embedding_state
                WHERE document_id = ?
                  AND embedding_profile_id = ?
                  AND active_job_id IS NOT NULL
                """,
                Long.class, documentId, profileId));

        jobRepository.retry(created.job().id(), 1).orElseThrow();
        assertEquals("QUEUED", stateValue(
                documentId, profileId, "status", String.class));
        assertEquals(created.job().id(), stateValue(
                documentId, profileId, "active_job_id", UUID.class));
        assertEquals(1, jobRepository.claim(
                "lifecycle-worker-a", 1, 30).size());
        assertEquals("PROCESSING", stateValue(
                documentId, profileId, "status", String.class));

        jdbcTemplate.update("""
                UPDATE rag_embedding_jobs
                SET lease_expires_at =
                    CURRENT_TIMESTAMP - INTERVAL '1 second'
                WHERE id = ?
                """,
                created.job().id());
        assertTrue(jobRepository.claim(
                "lifecycle-worker-b", 1, 30).isEmpty());
        assertEquals(EmbeddingJobStatus.FAILED,
                jobRepository.find(created.job().id())
                        .orElseThrow().status());
        assertEquals("FAILED", stateValue(
                documentId, profileId, "status", String.class));
        assertEquals(
                "Worker lease expired after maximum attempts",
                stateValue(
                        documentId,
                        profileId,
                        "processing_error",
                        String.class));
    }

    @Test
    void generationFenceAllowsMetadataCommitButRejectsOldContentJob() {
        long collectionId = insertCollection("generation");
        long documentId = insertDocument(
                collectionId, "default", null, HASH_A);
        long profileId = insertProfile("generation");

        long generation1 = jobRepository.allocateGeneration(
                documentId, profileId, HASH_A, TEXT_CHUNKER, false);
        var first = jobRepository.createOrCoalesce(
                UUID.randomUUID(),
                documentId,
                profileId,
                HASH_A,
                1L,
                false,
                3,
                "TEST",
                "integration",
                generation1,
                "TEXT",
                TEXT_CHUNKER);
        jobRepository.activateJob(
                documentId, profileId, generation1, first.job().id());
        var claimed = jobRepository.claimById(
                first.job().id(), "worker-a", 60).orElseThrow();

        jdbcTemplate.update(
                "UPDATE rag_documents "
                        + "SET metadata = '{\"changed\":true}'::jsonb, "
                        + "version = version + 1 WHERE id = ?",
                documentId);
        assertTrue(jobRepository.isCommitAllowed(
                claimed.id(), "worker-a", profileId));

        jdbcTemplate.update(
                "UPDATE rag_documents "
                        + "SET content = 'replacement', content_hash = ?, "
                        + "version = version + 1 WHERE id = ?",
                HASH_B, documentId);
        long generation2 = jobRepository.allocateGeneration(
                documentId, profileId, HASH_B, TEXT_CHUNKER, false);
        jobRepository.cancelSuperseded(
                documentId, profileId, generation2);

        assertFalse(jobRepository.isCommitAllowed(
                claimed.id(), "worker-a", profileId));
        assertNotNull(jdbcTemplate.queryForObject(
                "SELECT cancel_requested_at FROM rag_embedding_jobs "
                        + "WHERE id = ?",
                java.time.OffsetDateTime.class,
                claimed.id()));
        assertEquals(generation2, jdbcTemplate.queryForObject(
                "SELECT request_generation "
                        + "FROM rag_document_embedding_state "
                        + "WHERE document_id = ? "
                        + "AND embedding_profile_id = ?",
                Long.class, documentId, profileId));
    }

    @Test
    void documentSnapshotAndJobRollBackAsOneTransaction() {
        long collectionId = insertCollection("rollback");
        long profileId = insertProfile("rollback");
        TransactionTemplate transaction = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource));
        AtomicLong attemptedDocumentId = new AtomicLong();

        assertThrows(IllegalStateException.class, () ->
                transaction.executeWithoutResult(status -> {
                    long documentId = insertDocument(
                            collectionId, "default", null, HASH_A);
                    attemptedDocumentId.set(documentId);
                    jdbcTemplate.update("""
                            INSERT INTO rag_document_versions (
                                document_id, version_number, content_hash,
                                content_snapshot, change_type,
                                snapshot_completeness
                            ) VALUES (?, 1, ?, 'body', 'CREATE', 'FULL')
                            """, documentId, HASH_A);
                    long generation = jobRepository.allocateGeneration(
                            documentId, profileId,
                            HASH_A, TEXT_CHUNKER, false);
                    jobRepository.createOrCoalesce(
                            UUID.randomUUID(),
                            documentId,
                            profileId,
                            HASH_A,
                            1L,
                            false,
                            3,
                            "TEST",
                            "integration",
                            generation,
                            "TEXT",
                            TEXT_CHUNKER);
                    throw new IllegalStateException("force rollback");
                }));

        assertEquals(0L, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rag_documents WHERE id = ?",
                Long.class, attemptedDocumentId.get()));
        assertEquals(0L, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rag_document_versions "
                        + "WHERE document_id = ?",
                Long.class, attemptedDocumentId.get()));
        assertEquals(0L, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rag_embedding_jobs "
                        + "WHERE document_id = ?",
                Long.class, attemptedDocumentId.get()));
        assertEquals(0L, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rag_document_embedding_state "
                        + "WHERE document_id = ?",
                Long.class, attemptedDocumentId.get()));
    }

    @Test
    void hardDeleteCascadesLifecycleStateJobsAndSnapshots() {
        long collectionId = insertCollection("delete");
        long documentId = insertDocument(
                collectionId, "default", null, HASH_A);
        long profileId = insertProfile("delete");
        jdbcTemplate.update("""
                INSERT INTO rag_document_versions (
                    document_id, version_number, content_hash,
                    content_snapshot, change_type, snapshot_completeness
                ) VALUES (?, 1, ?, 'body', 'CREATE', 'FULL')
                """, documentId, HASH_A);
        long generation = jobRepository.allocateGeneration(
                documentId, profileId, HASH_A, TEXT_CHUNKER, false);
        var job = jobRepository.createOrCoalesce(
                UUID.randomUUID(),
                documentId,
                profileId,
                HASH_A,
                1L,
                false,
                3,
                "TEST",
                "integration",
                generation,
                "TEXT",
                TEXT_CHUNKER);
        jobRepository.activateJob(
                documentId, profileId, generation, job.job().id());

        jdbcTemplate.update(
                "DELETE FROM rag_documents WHERE id = ?",
                documentId);

        assertEquals(0L, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rag_document_versions "
                        + "WHERE document_id = ?",
                Long.class, documentId));
        assertEquals(0L, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rag_document_embedding_state "
                        + "WHERE document_id = ?",
                Long.class, documentId));
        assertEquals(0L, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rag_embedding_jobs "
                        + "WHERE document_id = ?",
                Long.class, documentId));
    }

    private long freshDocumentCount(long documentId, long profileId) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM rag_documents document
                JOIN rag_document_embedding_state state
                  ON state.document_id = document.id
                WHERE document.id = ?
                  AND state.embedding_profile_id = ?
                  AND document.enabled = true
                  AND state.status = 'COMPLETED'
                  AND state.chunk_count > 0
                  AND state.content_hash = document.content_hash
                  AND state.chunker_version = ?
                """,
                Long.class,
                documentId,
                profileId,
                TEXT_CHUNKER);
    }

    private <T> T stateValue(
            long documentId,
            long profileId,
            String column,
            Class<T> type) {
        if (!java.util.Set.of(
                "status", "active_job_id", "processing_error")
                .contains(column)) {
            throw new IllegalArgumentException(
                    "Unsupported state column: " + column);
        }
        return jdbcTemplate.queryForObject(
                "SELECT " + column
                        + " FROM rag_document_embedding_state "
                        + "WHERE document_id = ? "
                        + "AND embedding_profile_id = ?",
                type,
                documentId,
                profileId);
    }

    private long insertCollection(String suffix) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO rag_collection "
                        + "(collection_key, name, dimensions) "
                        + "VALUES (?, 'Lifecycle test', 1024) RETURNING id",
                Long.class,
                "lifecycle-" + suffix + "-" + UUID.randomUUID());
    }

    private long insertDocument(
            long collectionId,
            String namespace,
            String externalId,
            String hash) {
        boolean namespaceColumn = columnExists(
                "rag_documents", "source_namespace");
        if (namespaceColumn) {
            return jdbcTemplate.queryForObject("""
                    INSERT INTO rag_documents (
                        collection_id, title, content, document_type,
                        external_id, source_namespace, source_revision,
                        content_hash, processing_status
                    ) VALUES (?, 'Lifecycle document', 'body', 'text',
                        ?, ?, 'rev-1', ?, 'COMPLETED')
                    RETURNING id
                    """,
                    Long.class,
                    collectionId,
                    externalId,
                    namespace == null ? "default" : namespace,
                    hash);
        }
        return jdbcTemplate.queryForObject("""
                INSERT INTO rag_documents (
                    collection_id, title, content, document_type,
                    external_id, source_revision,
                    content_hash, processing_status
                ) VALUES (?, 'Lifecycle document', 'body', 'text',
                    ?, 'rev-1', ?, 'COMPLETED')
                RETURNING id
                """,
                Long.class,
                collectionId,
                externalId,
                hash);
    }

    private long insertProfile(String suffix) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO rag_embedding_profiles (
                    profile_key, provider, model_name, model_revision,
                    dimensions, distance_metric, normalization
                ) VALUES (?, 'test', ?, 'v1', 1024, 'COSINE', 'NONE')
                RETURNING id
                """,
                Long.class,
                "lifecycle-" + suffix + "-" + UUID.randomUUID(),
                "model-" + suffix + "-" + UUID.randomUUID());
    }

    private UUID insertLegacyJob(
            long documentId,
            long profileId,
            String status,
            String error) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO rag_embedding_jobs (
                    id, batch_id, document_id, embedding_profile_id,
                    content_hash, document_version, status,
                    last_error, finished_at
                ) VALUES (?, ?, ?, ?, ?, 1, ?, ?,
                    CASE WHEN ? IN ('QUEUED', 'RUNNING')
                        THEN NULL ELSE CURRENT_TIMESTAMP END)
                """,
                id,
                UUID.randomUUID(),
                documentId,
                profileId,
                HASH_A,
                status,
                error,
                status);
        return id;
    }

    private boolean columnExists(String table, String column) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE table_schema = 'public' "
                        + "AND table_name = ? AND column_name = ?",
                Long.class,
                table,
                column);
        return count != null && count == 1L;
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

    private static DataSource dataSource(
            String jdbcUrl,
            String username,
            String password) {
        PGSimpleDataSource value = new PGSimpleDataSource();
        value.setUrl(jdbcUrl);
        value.setUser(username);
        value.setPassword(password);
        return value;
    }
}
