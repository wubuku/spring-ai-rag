package com.springairag.core.integration;

import com.springairag.api.dto.DocumentRequest;
import com.springairag.api.enums.EmbeddingPolicy;
import com.springairag.core.config.EmbeddingProfile;
import com.springairag.core.embeddingjob.EmbeddingDispatchService;
import com.springairag.core.embeddingjob.EmbeddingJobRepository;
import com.springairag.core.embeddingjob.EmbeddingJobStatus;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.repository.RagEmbeddingRepository;
import com.springairag.core.service.BatchDocumentService;
import com.springairag.core.service.DocumentEmbedService;
import com.springairag.core.service.EmbeddingPersistenceService;
import com.springairag.core.retrieval.EmbeddingBatchService;
import com.springairag.documents.chunk.TextChunk;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * V33 任务契约、原子条件 claim、partial unique index 和租约恢复的真实 PostgreSQL 验收。
 */
class EmbeddingJobsPostgresIntegrationTest {

    private static PostgreSQLContainer<?> postgres;
    private static DataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private EmbeddingJobRepository repository;

    @BeforeAll
    static void startDatabase() {
        assumeTrue(Boolean.getBoolean("embedding-jobs.it.enabled"),
                "Set -Dembedding-jobs.it.enabled=true to run this test");
        String externalJdbcUrl = System.getProperty(
                "embedding-jobs.it.jdbc-url");
        if (externalJdbcUrl != null && !externalJdbcUrl.isBlank()) {
            PGSimpleDataSource pg = new PGSimpleDataSource();
            pg.setUrl(externalJdbcUrl);
            pg.setUser(System.getProperty(
                    "embedding-jobs.it.username", "postgres"));
            pg.setPassword(System.getProperty(
                    "embedding-jobs.it.password", "postgres"));
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
                .withDatabaseName("spring_ai_rag_embedding_jobs_test")
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
        repository = new EmbeddingJobRepository(jdbcTemplate);
    }

    @Test
    void coalescesForceAndOnlyOneWorkerClaims() {
        long collectionId = jdbcTemplate.queryForObject(
                "INSERT INTO rag_collection "
                        + "(collection_key, name, dimensions) "
                        + "VALUES (?, 'jobs', 1024) RETURNING id",
                Long.class, "jobs-" + UUID.randomUUID());
        String hash = "0123456789abcdef0123456789abcdef"
                + "0123456789abcdef0123456789abcdef";
        long documentId = jdbcTemplate.queryForObject(
                "INSERT INTO rag_documents "
                        + "(collection_id, title, content, content_hash, "
                        + "processing_status, version) "
                        + "VALUES (?, 'job', 'content', ?, 'PENDING', 7) "
                        + "RETURNING id",
                Long.class, collectionId, hash);
        long profileId = jdbcTemplate.queryForObject(
                "INSERT INTO rag_embedding_profiles "
                        + "(profile_key, provider, model_name, model_revision, "
                        + "dimensions, distance_metric, normalization) "
                        + "VALUES (?, 'test', 'model', 'v1', 1024, "
                        + "'COSINE', 'NONE') RETURNING id",
                Long.class, "jobs-" + UUID.randomUUID());

        var first = repository.createOrCoalesce(
                UUID.randomUUID(), documentId, profileId,
                hash, 7L, false, 3);
        var second = repository.createOrCoalesce(
                UUID.randomUUID(), documentId, profileId,
                hash, 7L, true, 4);

        assertEquals(first.job().id(), second.job().id());
        assertTrue(second.coalesced());
        assertTrue(second.job().force());
        assertEquals(4, second.job().maxAttempts());

        jdbcTemplate.update(
                "UPDATE rag_documents SET version = 8 WHERE id = ?",
                documentId);
        var refreshed = repository.createOrCoalesce(
                UUID.randomUUID(), documentId, profileId,
                hash, 8L, true, 4);
        assertEquals(first.job().id(), refreshed.job().id());
        assertEquals(8L, refreshed.job().documentVersion());

        var claimed = repository.claim("worker-a", 1, 60);
        assertEquals(1, claimed.size());
        assertTrue(repository.claim("worker-b", 1, 60).isEmpty());
        assertEquals(EmbeddingJobStatus.RUNNING,
                repository.find(first.job().id()).orElseThrow().status());

        UUID terminalId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO rag_embedding_jobs (
                    id, batch_id, document_id, embedding_profile_id,
                    force, content_hash, document_version, status,
                    attempt_count, max_attempts, finished_at
                ) VALUES (?, ?, ?, ?, false, ?, 7, 'FAILED', 3, 3,
                    CURRENT_TIMESTAMP)
                """,
                terminalId,
                UUID.randomUUID(),
                documentId,
                profileId,
                hash);
        assertTrue(repository.retry(terminalId, 3).isEmpty());
        assertEquals(first.job().id(), repository.findActive(
                documentId, profileId, hash).orElseThrow().id());
    }

    @Test
    void expiredLeaseAfterLastAttemptBecomesFailedInsteadOfStayingRunning() {
        long collectionId = jdbcTemplate.queryForObject(
                "INSERT INTO rag_collection "
                        + "(collection_key, name, dimensions) "
                        + "VALUES (?, 'jobs', 1024) RETURNING id",
                Long.class, "jobs-" + UUID.randomUUID());
        String hash = "abcdef0123456789abcdef0123456789"
                + "abcdef0123456789abcdef0123456789";
        long documentId = jdbcTemplate.queryForObject(
                "INSERT INTO rag_documents "
                        + "(collection_id, title, content, content_hash, "
                        + "processing_status, version) "
                        + "VALUES (?, 'job', 'content', ?, 'PENDING', 1) "
                        + "RETURNING id",
                Long.class, collectionId, hash);
        long profileId = jdbcTemplate.queryForObject(
                "INSERT INTO rag_embedding_profiles "
                        + "(profile_key, provider, model_name, model_revision, "
                        + "dimensions, distance_metric, normalization) "
                        + "VALUES (?, 'test', 'model', 'v1', 1024, "
                        + "'COSINE', 'NONE') RETURNING id",
                Long.class, "jobs-" + UUID.randomUUID());
        var created = repository.createOrCoalesce(
                UUID.randomUUID(), documentId, profileId,
                hash, 1L, false, 1);

        assertEquals(1, repository.claim("worker-a", 1, 30).size());
        jdbcTemplate.update(
                "UPDATE rag_embedding_jobs "
                        + "SET lease_expires_at = CURRENT_TIMESTAMP "
                        + "- INTERVAL '1 second' WHERE id = ?",
                created.job().id());

        assertTrue(repository.claim("worker-b", 1, 30).isEmpty());
        var failed = repository.find(created.job().id()).orElseThrow();
        assertEquals(EmbeddingJobStatus.FAILED, failed.status());
        assertEquals(1, failed.attemptCount());
        assertNull(failed.leaseOwner());
        assertNull(failed.leaseExpiresAt());
        assertEquals("Worker lease expired after maximum attempts",
                failed.lastError());
    }

    @Test
    void expiredLeaseCannotHeartbeatOrWriteAfterAnotherExecutionReclaimsJob() {
        long collectionId = jdbcTemplate.queryForObject(
                "INSERT INTO rag_collection "
                        + "(collection_key, name, dimensions) "
                        + "VALUES (?, 'jobs', 1024) RETURNING id",
                Long.class, "jobs-" + UUID.randomUUID());
        String hash = "22222222222222222222222222222222"
                + "22222222222222222222222222222222";
        long documentId = jdbcTemplate.queryForObject(
                "INSERT INTO rag_documents "
                        + "(collection_id, title, content, content_hash, "
                        + "processing_status, version) "
                        + "VALUES (?, 'job', 'content', ?, 'PENDING', 1) "
                        + "RETURNING id",
                Long.class, collectionId, hash);
        long profileId = jdbcTemplate.queryForObject(
                "INSERT INTO rag_embedding_profiles "
                        + "(profile_key, provider, model_name, model_revision, "
                        + "dimensions, distance_metric, normalization) "
                        + "VALUES (?, 'test', 'model', 'v1', 1024, "
                        + "'COSINE', 'NONE') RETURNING id",
                Long.class, "jobs-" + UUID.randomUUID());
        var created = repository.createOrCoalesce(
                UUID.randomUUID(), documentId, profileId,
                hash, 1L, false, 3);

        assertEquals(1, repository.claim("execution-a", 1, 30).size());
        jdbcTemplate.update(
                "UPDATE rag_embedding_jobs "
                        + "SET lease_expires_at = CURRENT_TIMESTAMP "
                        + "- INTERVAL '1 second' WHERE id = ?",
                created.job().id());
        assertEquals(1, repository.claim("execution-b", 1, 30).size());

        assertEquals(0, repository.heartbeat(
                created.job().id(), "execution-a", 30));
        assertEquals(0, repository.markSucceeded(
                created.job().id(), "execution-a", true));
        assertEquals(0, repository.markFailure(
                created.job().id(), "execution-a", "late failure", 1));
        assertEquals(0, repository.markStale(
                created.job().id(), "execution-a", "late stale"));
        assertEquals(1, repository.markSucceeded(
                created.job().id(), "execution-b", true));
        assertEquals(EmbeddingJobStatus.SUCCEEDED,
                repository.find(created.job().id()).orElseThrow().status());
    }

    @Test
    void guardedVectorCommitUsesConditionalLeaseTransition() throws Exception {
        long collectionId = jdbcTemplate.queryForObject(
                "INSERT INTO rag_collection "
                        + "(collection_key, name, dimensions) "
                        + "VALUES (?, 'guarded', 1024) RETURNING id",
                Long.class, "guarded-" + UUID.randomUUID());
        String hash = "33333333333333333333333333333333"
                + "33333333333333333333333333333333";
        long documentId = jdbcTemplate.queryForObject(
                "INSERT INTO rag_documents "
                        + "(collection_id, title, content, content_hash, "
                        + "processing_status, version) "
                        + "VALUES (?, 'guarded', 'content', ?, 'PENDING', 1) "
                        + "RETURNING id",
                Long.class, collectionId, hash);
        String profileKey = "guarded-" + UUID.randomUUID();
        long profileId = jdbcTemplate.queryForObject(
                "INSERT INTO rag_embedding_profiles "
                        + "(profile_key, provider, model_name, model_revision, "
                        + "dimensions, distance_metric, normalization) "
                        + "VALUES (?, 'test', 'model', 'v1', 1024, "
                        + "'COSINE', 'NONE') RETURNING id",
                Long.class, profileKey);
        var created = repository.createOrCoalesce(
                UUID.randomUUID(), documentId, profileId,
                hash, 1L, false, 3);
        assertEquals(1, repository.claim("execution-a", 1, 60).size());

        EmbeddingPersistenceService persistence =
                new EmbeddingPersistenceService(jdbcTemplate);
        EmbeddingProfile profile = new EmbeddingProfile(
                profileId, profileKey, "test", "model", "v1",
                1024, "COSINE", "NONE", true);
        var transactions =
                new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        CountDownLatch guardLocked = new CountDownLatch(1);
        CountDownLatch releaseCommit = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var commit = executor.submit(() -> {
                transactions.executeWithoutResult(status -> persistence.replace(
                        documentId,
                        1L,
                        hash,
                        profile,
                        "test",
                        List.of(new TextChunk("content", 0, 7)),
                        List.of(new EmbeddingBatchService.EmbeddingResult(
                                "content", new float[1024], null)),
                        () -> {
                            assertTrue(repository.claimCommitAllowed(
                                    created.job().id(),
                                    "execution-a",
                                    profileId,
                                    60));
                            guardLocked.countDown();
                            await(releaseCommit);
                        }));
                return null;
            });
            if (!guardLocked.await(10, TimeUnit.SECONDS)) {
                assertTrue(commit.isDone(),
                        "Embedding commit did not reach the conditional commit section");
                commit.get(1, TimeUnit.SECONDS);
                throw new AssertionError(
                        "Embedding commit completed without acquiring the commit lease");
            }

            var expireLease = executor.submit(() -> transactions.execute(status ->
                    jdbcTemplate.update(
                            "UPDATE rag_embedding_jobs "
                                    + "SET lease_expires_at = CURRENT_TIMESTAMP "
                                    + "- INTERVAL '1 second' WHERE id = ?",
                            created.job().id())));
            assertThrows(
                    TimeoutException.class,
                    () -> expireLease.get(300, TimeUnit.MILLISECONDS));

            releaseCommit.countDown();
            commit.get(10, TimeUnit.SECONDS);
            assertEquals(1, expireLease.get(10, TimeUnit.SECONDS));
            assertEquals(1, repository.claim("execution-b", 1, 60).size());
        } finally {
            releaseCommit.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void listPageFiltersByCollectionBeforeLimitAndHeartbeatExtendsLease() {
        long visible = jdbcTemplate.queryForObject(
                "INSERT INTO rag_collection (collection_key, name, dimensions) "
                        + "VALUES (?, 'visible', 1024) RETURNING id",
                Long.class, "visible-" + UUID.randomUUID());
        long hidden = jdbcTemplate.queryForObject(
                "INSERT INTO rag_collection (collection_key, name, dimensions) "
                        + "VALUES (?, 'hidden', 1024) RETURNING id",
                Long.class, "hidden-" + UUID.randomUUID());
        String hash = "11111111111111111111111111111111"
                + "11111111111111111111111111111111";
        long visibleDoc = jdbcTemplate.queryForObject(
                "INSERT INTO rag_documents (collection_id, title, content, content_hash, "
                        + "processing_status, version) VALUES (?, 'v', 'c', ?, 'PENDING', 1) "
                        + "RETURNING id",
                Long.class, visible, hash);
        long hiddenDoc = jdbcTemplate.queryForObject(
                "INSERT INTO rag_documents (collection_id, title, content, content_hash, "
                        + "processing_status, version) VALUES (?, 'h', 'c', ?, 'PENDING', 1) "
                        + "RETURNING id",
                Long.class, hidden, hash);
        long profileId = jdbcTemplate.queryForObject(
                "INSERT INTO rag_embedding_profiles "
                        + "(profile_key, provider, model_name, model_revision, "
                        + "dimensions, distance_metric, normalization) "
                        + "VALUES (?, 'test', 'model', 'v1', 1024, 'COSINE', 'NONE') "
                        + "RETURNING id",
                Long.class, "ops-" + UUID.randomUUID());
        repository.createOrCoalesce(
                UUID.randomUUID(), hiddenDoc, profileId, hash, 1L, false, 3,
                "API", "db:hidden");
        var visibleJob = repository.createOrCoalesce(
                UUID.randomUUID(), visibleDoc, profileId, hash, 1L, false, 3,
                "API", "db:visible");
        var page = repository.listPage(
                null, null, null, List.of(visible), 50, 0);
        assertEquals(1, page.totalElements());
        assertEquals(visibleJob.job().id(), page.items().getFirst().id());
        var pageBeyondLastItem = repository.listPage(
                null, null, null, List.of(visible), 50, 50);
        assertTrue(pageBeyondLastItem.items().isEmpty());
        assertEquals(1, pageBeyondLastItem.totalElements());

        var claimed = repository.claim("worker-a", 2, 60);
        assertEquals(2, claimed.size());
        assertEquals(1, repository.heartbeat(visibleJob.job().id(), "worker-a", 60));
        Integer version = jdbcTemplate.queryForObject(
                "SELECT MAX(installed_rank) FROM flyway_schema_history", Integer.class);
        assertTrue(version != null && version >= 37);
    }

    @Test
    void readinessClassifiesFreshQueuedRunningFailedAndStaleDocuments() {
        long collectionId = jdbcTemplate.queryForObject(
                "INSERT INTO rag_collection (collection_key, name, dimensions) "
                        + "VALUES (?, 'ready', 1024) RETURNING id",
                Long.class, "ready-" + UUID.randomUUID());
        String hash = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                + "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        long fresh = insertDocument(collectionId, hash, true);
        long queued = insertDocument(collectionId, hash, true);
        long running = insertDocument(collectionId, hash, true);
        long failed = insertDocument(collectionId, hash, true);
        insertDocument(collectionId, hash, true);
        insertDocument(collectionId, hash, false);
        String profileKey = "ready-" + UUID.randomUUID();
        long profileId = jdbcTemplate.queryForObject(
                "INSERT INTO rag_embedding_profiles "
                        + "(profile_key, provider, model_name, model_revision, "
                        + "dimensions, distance_metric, normalization) "
                        + "VALUES (?, 'test', 'model', 'v1', 1024, 'COSINE', 'NONE') "
                        + "RETURNING id",
                Long.class, profileKey);
        jdbcTemplate.update(
                "INSERT INTO rag_document_embedding_state "
                        + "(document_id, embedding_profile_id, content_hash, "
                        + "chunker_version, status, chunk_count) "
                        + "VALUES (?, ?, ?, 'test', 'COMPLETED', 3)",
                fresh, profileId, hash);
        jdbcTemplate.update(
                "INSERT INTO rag_document_embedding_state "
                        + "(document_id, embedding_profile_id, content_hash, "
                        + "chunker_version, status, chunk_count) "
                        + "VALUES (?, ?, ?, 'test', 'FAILED', 0)",
                failed, profileId, hash);
        jdbcTemplate.update("""
                INSERT INTO rag_embedding_jobs (
                    id, batch_id, document_id, embedding_profile_id,
                    force, content_hash, document_version, status,
                    attempt_count, max_attempts)
                VALUES (?, ?, ?, ?, false, ?, 1, 'QUEUED', 0, 3)
                """,
                UUID.randomUUID(), UUID.randomUUID(), queued, profileId, hash);
        jdbcTemplate.update("""
                INSERT INTO rag_embedding_jobs (
                    id, batch_id, document_id, embedding_profile_id,
                    force, content_hash, document_version, status,
                    attempt_count, max_attempts, lease_owner, lease_expires_at)
                VALUES (?, ?, ?, ?, false, ?, 1, 'RUNNING', 1, 3,
                    'worker-a', CURRENT_TIMESTAMP + INTERVAL '60 seconds')
                """,
                UUID.randomUUID(), UUID.randomUUID(), running, profileId, hash);

        var readiness = repository.readiness(
                collectionId,
                "ready-collection",
                new EmbeddingProfile(
                        profileId, profileKey, "test", "model", "v1",
                        1024, "COSINE", "NONE", true));

        assertEquals("ready-collection", readiness.collectionKey());
        assertEquals(profileKey, readiness.activeEmbeddingProfileKey());
        assertEquals(5, readiness.enabledDocuments());
        assertEquals(1, readiness.freshDocuments());
        assertEquals(1, readiness.queuedDocuments());
        assertEquals(1, readiness.runningDocuments());
        assertEquals(1, readiness.failedDocuments());
        assertEquals(1, readiness.staleOrMissingDocuments());
        assertEquals(
                readiness.enabledDocuments(),
                readiness.freshDocuments()
                        + readiness.queuedDocuments()
                        + readiness.runningDocuments()
                        + readiness.failedDocuments()
                        + readiness.staleOrMissingDocuments());
    }

    @Test
    void batchAsyncEnqueueFailureRollsBackDocumentPersistence() {
        RagDocumentRepository documentRepository =
                mock(RagDocumentRepository.class);
        when(documentRepository.findByContentHash(anyString())).thenReturn(List.of());
        when(documentRepository.save(any(RagDocument.class))).thenAnswer(invocation -> {
            RagDocument document = invocation.getArgument(0);
            long id = jdbcTemplate.queryForObject(
                    "INSERT INTO rag_documents "
                            + "(title, content, content_hash, processing_status, version) "
                            + "VALUES (?, ?, ?, 'COMPLETED', 0) RETURNING id",
                    Long.class,
                    document.getTitle(),
                    document.getContent(),
                    document.getContentHash());
            document.setId(id);
            document.setVersion(0L);
            return document;
        });
        EmbeddingDispatchService dispatchService =
                mock(EmbeddingDispatchService.class);
        doThrow(new IllegalStateException("job enqueue failed"))
                .when(dispatchService)
                .enqueueInCurrentTransaction(
                        any(RagDocument.class),
                        anyBoolean(),
                        anyBoolean(),
                        anyString());
        BatchDocumentService service = new BatchDocumentService(
                documentRepository,
                mock(RagEmbeddingRepository.class),
                mock(DocumentEmbedService.class),
                new DataSourceTransactionManager(dataSource));
        ReflectionTestUtils.setField(
                service, "dispatchService", dispatchService);
        DocumentRequest request = new DocumentRequest(
                "transactional batch", "rollback me");

        var response = service.batchCreateDocuments(
                List.of(request), false, null, false, EmbeddingPolicy.ASYNC);

        assertEquals(1, response.failed());
        assertEquals(0, response.created());
        assertEquals(0L, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rag_documents "
                        + "WHERE title = 'transactional batch'",
                Long.class));
    }

    private long insertDocument(long collectionId, String hash, boolean enabled) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO rag_documents (collection_id, title, content, content_hash, "
                        + "processing_status, version, enabled) "
                        + "VALUES (?, 'doc', 'content', ?, 'PENDING', 1, ?) RETURNING id",
                Long.class, collectionId, hash, enabled);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for test latch");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for test latch", e);
        }
    }
}
