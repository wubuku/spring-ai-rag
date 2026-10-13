package com.springairag.core.integration;

import com.springairag.api.enums.DocumentProcessingRunStatus;
import com.springairag.api.enums.DocumentProcessingStageName;
import com.springairag.api.enums.DocumentProcessingStageStatus;
import com.springairag.core.config.RagProperties;
import com.springairag.core.documentprocessing.DocumentProcessingHousekeeping;
import com.springairag.core.documentprocessing.DocumentProcessingRun;
import com.springairag.core.documentprocessing.DocumentProcessingRunRepository;
import com.springairag.core.documentprocessing.DocumentProcessingStage;
import com.springairag.core.embeddingjob.EmbeddingJob;
import com.springairag.core.embeddingjob.EmbeddingJobRepository;
import com.springairag.core.embeddingjob.EmbeddingJobStatus;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.util.UUID;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** V61 Run/Stage 账本的真实 PostgreSQL 验收。 */
@EnabledIfSystemProperty(
        named = "document-processing.it.enabled", matches = "true")
class DocumentProcessingRunPostgresIntegrationTest {

    private static DataSource dataSource;
    private static PostgreSQLContainer<?> postgres;
    private JdbcTemplate jdbcTemplate;
    private EmbeddingJobRepository jobRepository;
    private DocumentProcessingRunRepository runRepository;

    @BeforeAll
    static void connectDatabase() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        String externalJdbcUrl = System.getProperty(
                "document-processing.it.jdbc-url");
        if (externalJdbcUrl != null && !externalJdbcUrl.isBlank()) {
            dataSource.setUrl(externalJdbcUrl);
            dataSource.setUser(System.getProperty(
                    "document-processing.it.username", "postgres"));
            dataSource.setPassword(System.getProperty(
                    "document-processing.it.password", "postgres"));
            DocumentProcessingRunPostgresIntegrationTest.dataSource = dataSource;
            return;
        }
        String image = System.getProperty(
                "testcontainers.pg.image",
                System.getenv().getOrDefault(
                        "TESTCONTAINERS_PG_IMAGE", "pgvector/pgvector:pg16"));
        postgres = new PostgreSQLContainer<>(
                DockerImageName.parse(image)
                        .asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("spring_ai_rag_document_processing_test")
                .withUsername("postgres")
                .withPassword("postgres");
        postgres.start();
        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
        DocumentProcessingRunPostgresIntegrationTest.dataSource = dataSource;
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @BeforeEach
    void migrate() {
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load()
                .clean();
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load()
                .migrate();
        jdbcTemplate = new JdbcTemplate(dataSource);
        jobRepository = new EmbeddingJobRepository(jdbcTemplate);
        runRepository = new DocumentProcessingRunRepository(jdbcTemplate);
    }

    @Test
    void createsFiveStagesAndMirrorsTerminalJob() {
        Fixture fixture = fixture();
        EmbeddingJob job = fixture.job();
        DocumentProcessingRun run = runRepository.ensureForJob(
                job, "API", "local:auth-disabled", null, false);

        assertEquals(DocumentProcessingRunStatus.QUEUED, run.status());
        assertEquals(5, runRepository.stages(run.id()).size());
        assertEquals(DocumentProcessingStageStatus.PENDING,
                runRepository.stages(run.id()).getFirst().status());

        EmbeddingJob claimed = jobRepository.claim("run-it-worker", 1, 60)
                .getFirst();
        assertEquals(1, runRepository.mirrorClaimed(
                claimed, "run-it-worker"));
        assertEquals(1, runRepository.markStage(
                claimed, "run-it-worker", DocumentProcessingStageName.PARSING,
                DocumentProcessingStageStatus.RUNNING, null, null));
        assertEquals(1, runRepository.updateStageProgress(
                claimed, "run-it-worker", DocumentProcessingStageName.PARSING,
                1, 1, "PARSING"));

        EmbeddingJob running = jobRepository.find(job.id()).orElseThrow();
        assertEquals(EmbeddingJobStatus.RUNNING, running.status());
        assertEquals(1, runRepository.mirrorJob(running));
        DocumentProcessingRun runningRun = runRepository.find(run.id()).orElseThrow();
        assertEquals(DocumentProcessingRunStatus.RUNNING, runningRun.status());

        assertEquals(1, jobRepository.markSucceeded(
                job.id(), "run-it-worker", true));
        EmbeddingJob succeeded = jobRepository.find(job.id()).orElseThrow();
        assertEquals(1, runRepository.mirrorJob(succeeded));
        DocumentProcessingRun completed = runRepository.find(run.id()).orElseThrow();
        assertEquals(DocumentProcessingRunStatus.COMPLETED, completed.status());
        assertTrue(runRepository.stages(run.id()).stream()
                .allMatch(stage -> stage.status() == DocumentProcessingStageStatus.SUCCEEDED));
    }

    @Test
    void concurrentBridgeCreationReturnsOneRun() throws Exception {
        Fixture fixture = fixture();
        int workers = 4;
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<DocumentProcessingRun>> futures =
                    java.util.stream.IntStream.range(0, workers)
                            .mapToObj(index -> executor.submit(() -> {
                                ready.countDown();
                                start.await();
                                return runRepository.ensureForJob(
                                        fixture.job(), "API", "local:auth-disabled",
                                        null, true);
                            }))
                            .toList();
            ready.await();
            start.countDown();
            Set<UUID> runIds = futures.stream()
                    .map(future -> get(future))
                    .map(DocumentProcessingRun::id)
                    .collect(java.util.stream.Collectors.toSet());

            assertEquals(1, runIds.size());
            assertEquals(1, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM rag_document_processing_runs",
                    Integer.class));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void leaseReclaimResetsStagesAndRejectsOldWorker() throws Exception {
        Fixture fixture = fixture();
        DocumentProcessingRun run = runRepository.ensureForJob(
                fixture.job(), "API", "local:auth-disabled", null, false);
        EmbeddingJob firstClaim = jobRepository.claim("worker-a", 1, 1)
                .getFirst();
        runRepository.mirrorClaimed(firstClaim, "worker-a");
        runRepository.markStage(
                firstClaim, "worker-a", DocumentProcessingStageName.PARSING,
                DocumentProcessingStageStatus.RUNNING, null, null);
        jdbcTemplate.update("""
                UPDATE rag_embedding_jobs
                SET lease_expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second'
                WHERE id = ?
                """, fixture.job().id());

        EmbeddingJob secondClaim = jobRepository.claim("worker-b", 1, 60)
                .getFirst();
        assertEquals(1, runRepository.mirrorClaimed(secondClaim, "worker-b"));
        assertEquals(1, runRepository.markStage(
                secondClaim, "worker-b", DocumentProcessingStageName.PARSING,
                DocumentProcessingStageStatus.RUNNING, null, null));
        assertEquals(0, runRepository.updateStageProgress(
                firstClaim, "worker-a", DocumentProcessingStageName.PARSING,
                1, 2, "old-worker"));
        assertEquals(1, runRepository.updateStageProgress(
                secondClaim, "worker-b", DocumentProcessingStageName.PARSING,
                1, 2, "new-worker"));
        assertEquals("new-worker", runRepository.stages(
                run.id()).getFirst().progressLabel());
        assertEquals(0, jobRepository.heartbeat(
                firstClaim.id(), "worker-a", 60));
        assertEquals(0, jobRepository.markFailure(
                firstClaim.id(), "worker-a", "late failure", 1));
        assertEquals(0, jobRepository.markSucceeded(
                firstClaim.id(), "worker-a", true));
        assertEquals(0, runRepository.markStage(
                firstClaim, "worker-a", DocumentProcessingStageName.PARSING,
                DocumentProcessingStageStatus.SUCCEEDED, null, null));
    }

    @Test
    void newerGenerationRejectsOldCommitAndRunStageUpdates() {
        Fixture fixture = fixture();
        DocumentProcessingRun run = runRepository.ensureForJob(
                fixture.job(), "API", "local:auth-disabled", null, false);
        EmbeddingJob oldJob = jobRepository.claim("generation-a", 1, 60)
                .getFirst();
        runRepository.mirrorClaimed(oldJob, "generation-a");
        runRepository.markStage(oldJob, "generation-a",
                DocumentProcessingStageName.EMBEDDING,
                DocumentProcessingStageStatus.RUNNING, null, null);

        long nextGeneration = jobRepository.allocateGeneration(
                fixture.documentId(), fixture.profileId(), oldJob.contentHash(),
                oldJob.chunkerVersion(), false);
        jobRepository.cancelSuperseded(
                fixture.documentId(), fixture.profileId(), nextGeneration);
        EmbeddingJob newJob = jobRepository.createOrCoalesce(
                UUID.randomUUID(), fixture.documentId(), fixture.profileId(),
                oldJob.contentHash(), oldJob.documentVersion(), false, 3,
                "API", "local:auth-disabled", nextGeneration,
                oldJob.documentKind(), oldJob.chunkerVersion()).job();
        jobRepository.activateJob(fixture.documentId(), fixture.profileId(),
                nextGeneration, newJob.id());

        assertFalse(jobRepository.claimCommitAllowed(
                oldJob.id(), "generation-a", fixture.profileId(), 60));
        assertEquals(0, runRepository.markStage(
                oldJob, "generation-a", DocumentProcessingStageName.EMBEDDING,
                DocumentProcessingStageStatus.SUCCEEDED, null, null));
        assertEquals(0, runRepository.updateStageProgress(
                oldJob, "generation-a", DocumentProcessingStageName.EMBEDDING,
                2, 3, "late-old-worker"));
        assertEquals(0, runRepository.heartbeat(oldJob, "generation-a"));
        assertEquals(0, jobRepository.markSucceeded(
                oldJob.id(), "generation-a", true));
        assertEquals(DocumentProcessingRunStatus.RUNNING,
                runRepository.find(run.id()).orElseThrow().status());
        assertEquals(EmbeddingJobStatus.QUEUED,
                jobRepository.find(newJob.id()).orElseThrow().status());
    }

    @Test
    void cancelAndSuccessCasAllowsOnlyCurrentTerminalWinner() {
        Fixture fixture = fixture();
        EmbeddingJob claimed = jobRepository.claim("race-worker", 1, 60)
                .getFirst();
        jobRepository.cancel(claimed.id());
        assertEquals(1, jobRepository.markCancelled(
                claimed.id(), "race-worker"));
        assertEquals(0, jobRepository.markSucceeded(
                claimed.id(), "race-worker", true));
        EmbeddingJob cancelled = jobRepository.find(claimed.id()).orElseThrow();
        assertEquals(EmbeddingJobStatus.CANCELLED, cancelled.status());
        assertEquals(1, jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM rag_embedding_jobs
                WHERE id = ? AND status = 'CANCELLED'
                """, Integer.class, claimed.id()));
    }

    @Test
    void terminalMirrorIsIdempotentAfterRunMirrorWasSkipped() {
        Fixture fixture = fixture();
        DocumentProcessingRun run = runRepository.ensureForJob(
                fixture.job(), "API", "local:auth-disabled", null, false);
        EmbeddingJob claimed = jobRepository.claim("mirror-worker", 1, 60)
                .getFirst();
        jobRepository.markSucceeded(claimed.id(), "mirror-worker", true);

        assertEquals(DocumentProcessingRunStatus.QUEUED,
                runRepository.find(run.id()).orElseThrow().status());
        assertEquals(1, runRepository.mirrorJob(
                jobRepository.find(claimed.id()).orElseThrow()));
        assertEquals(0, runRepository.mirrorJob(
                jobRepository.find(claimed.id()).orElseThrow()));
        assertEquals(DocumentProcessingRunStatus.COMPLETED,
                runRepository.find(run.id()).orElseThrow().status());
    }

    @Test
    void housekeepingDoesNotStaleFreshQueuedRunOrFreshRunningJob() {
        Fixture queued = fixture();
        DocumentProcessingRun queuedRun = runRepository.ensureForJob(
                queued.job(), "API", "local:auth-disabled", null, false);
        assertEquals(0, runRepository.markStuck(
                queuedRun.id(), queued.job().requestGeneration(), 60));

        Fixture running = fixture();
        DocumentProcessingRun runningRun = runRepository.ensureForJob(
                running.job(), "API", "local:auth-disabled", null, false);
        EmbeddingJob claimed = jobRepository.claimById(
                running.job().id(), "fresh-worker", 60).orElseThrow();
        runRepository.mirrorClaimed(claimed, "fresh-worker");
        assertEquals(0, runRepository.markStuck(
                runningRun.id(), running.job().requestGeneration(), 60));
        assertEquals(DocumentProcessingRunStatus.RUNNING,
                runRepository.find(runningRun.id()).orElseThrow().status());
    }

    @Test
    void cancelSupersedeAndStuckMirrorStageTerminalStates() {
        Fixture cancelled = fixture();
        DocumentProcessingRun cancelledRun = runRepository.ensureForJob(
                cancelled.job(), "API", "local:auth-disabled", null, false);
        jobRepository.cancel(cancelled.job().id());
        runRepository.mirrorJob(jobRepository.find(cancelled.job().id()).orElseThrow());
        assertEquals(DocumentProcessingRunStatus.CANCELLED,
                runRepository.find(cancelledRun.id()).orElseThrow().status());
        assertTrue(runRepository.stages(cancelledRun.id()).stream()
                .allMatch(stage -> stage.status() == DocumentProcessingStageStatus.CANCELLED));

        Fixture superseded = fixture();
        DocumentProcessingRun supersededRun = runRepository.ensureForJob(
                superseded.job(), "API", "local:auth-disabled", null, false);
        long generation = superseded.job().requestGeneration() + 1;
        assertEquals(1, runRepository.markSuperseded(
                superseded.documentId(), superseded.profileId(), generation));
        assertEquals(DocumentProcessingRunStatus.STALE,
                runRepository.find(supersededRun.id()).orElseThrow().status());
        assertTrue(runRepository.stages(supersededRun.id()).stream()
                .allMatch(stage -> stage.status() == DocumentProcessingStageStatus.STALE));

        Fixture stuck = fixture();
        DocumentProcessingRun stuckRun = runRepository.ensureForJob(
                stuck.job(), "API", "local:auth-disabled", null, false);
        jdbcTemplate.update("""
                UPDATE rag_document_processing_runs
                SET last_heartbeat_at = CURRENT_TIMESTAMP - INTERVAL '10 minutes',
                    updated_at = CURRENT_TIMESTAMP - INTERVAL '10 minutes'
                WHERE id = ?
                """, stuckRun.id());
        jdbcTemplate.update("DELETE FROM rag_embedding_jobs WHERE id = ?",
                stuck.job().id());
        assertEquals(1, runRepository.markStuck(
                stuckRun.id(), stuck.job().requestGeneration(), 60));
        assertEquals(DocumentProcessingRunStatus.STALE,
                runRepository.find(stuckRun.id()).orElseThrow().status());
    }

    @Test
    void retryExhaustionMirrorsDeadLetterAndSkipsDownstreamStages() {
        Fixture fixture = fixture(1);
        DocumentProcessingRun run = runRepository.ensureForJob(
                fixture.job(), "API", "local:auth-disabled", null, false);
        EmbeddingJob claimed = jobRepository.claim("dead-letter-worker", 1, 60)
                .getFirst();
        runRepository.mirrorClaimed(claimed, "dead-letter-worker");
        runRepository.markStage(
                claimed, "dead-letter-worker", DocumentProcessingStageName.EMBEDDING,
                DocumentProcessingStageStatus.RUNNING, null, null);
        jobRepository.markFailure(
                claimed.id(), "dead-letter-worker", "provider failed", 0);
        EmbeddingJob failed = jobRepository.find(claimed.id()).orElseThrow();
        runRepository.mirrorJob(failed);

        DocumentProcessingRun deadLettered = runRepository.find(run.id()).orElseThrow();
        assertEquals(DocumentProcessingRunStatus.DEAD_LETTERED, deadLettered.status());
        assertTrue(deadLettered.deadLetteredAt() != null);
        List<DocumentProcessingStage> stages = runRepository.stages(run.id());
        assertEquals(DocumentProcessingStageStatus.FAILED,
                stages.get(2).status());
        assertEquals(DocumentProcessingStageStatus.SKIPPED,
                stages.get(3).status());
        assertEquals(DocumentProcessingStageStatus.SKIPPED,
                stages.get(4).status());
    }

    @Test
    void nonRetryableFailureMirrorsFailedRunAndSkipsDownstreamStages() {
        Fixture fixture = fixture(3);
        DocumentProcessingRun run = runRepository.ensureForJob(
                fixture.job(), "API", "local:auth-disabled", null, false);
        EmbeddingJob claimed = jobRepository.claim("non-retryable-worker", 1, 60)
                .getFirst();
        runRepository.mirrorClaimed(claimed, "non-retryable-worker");
        runRepository.markStage(
                claimed, "non-retryable-worker", DocumentProcessingStageName.EMBEDDING,
                DocumentProcessingStageStatus.RUNNING, null, null);
        jobRepository.markNonRetryableFailure(
                claimed.id(), "non-retryable-worker",
                "Embedding dimension mismatch at chunk 0");
        EmbeddingJob failed = jobRepository.find(claimed.id()).orElseThrow();
        runRepository.mirrorJob(failed);

        DocumentProcessingRun failedRun = runRepository.find(run.id()).orElseThrow();
        assertEquals(DocumentProcessingRunStatus.FAILED, failedRun.status());
        assertEquals("NON_RETRYABLE", failedRun.lastErrorCode());
        List<DocumentProcessingStage> stages = runRepository.stages(run.id());
        assertEquals(DocumentProcessingStageStatus.FAILED, stages.get(2).status());
        assertEquals(DocumentProcessingStageStatus.SKIPPED, stages.get(3).status());
        assertEquals(DocumentProcessingStageStatus.SKIPPED, stages.get(4).status());
    }

    @Test
    void housekeepingMirrorsTerminalJobAndRepeatedScanIsIdempotent() {
        Fixture fixture = fixture();
        DocumentProcessingRun run = runRepository.ensureForJob(
                fixture.job(), "API", "local:auth-disabled", null, false);
        EmbeddingJob claimed = jobRepository.claim("housekeeping-worker", 1, 60)
                .getFirst();
        runRepository.mirrorClaimed(claimed, "housekeeping-worker");
        runRepository.markStage(
                claimed, "housekeeping-worker", DocumentProcessingStageName.EMBEDDING,
                DocumentProcessingStageStatus.RUNNING, null, null);
        assertEquals(1, jobRepository.markSucceeded(
                claimed.id(), "housekeeping-worker", true));

        DocumentProcessingHousekeeping housekeeping = housekeeping();
        housekeeping.scan();
        housekeeping.scan();

        assertEquals(DocumentProcessingRunStatus.COMPLETED,
                runRepository.find(run.id()).orElseThrow().status());
        assertEquals(5, runRepository.stages(run.id()).size());
        assertTrue(runRepository.stages(run.id()).stream()
                .allMatch(stage -> stage.status() == DocumentProcessingStageStatus.SUCCEEDED));
    }

    @Test
    void housekeepingDoesNotStaleFreshQueuedOrRunningRun() {
        Fixture queued = fixture();
        DocumentProcessingRun queuedRun = runRepository.ensureForJob(
                queued.job(), "API", "local:auth-disabled", null, false);

        Fixture running = fixture();
        DocumentProcessingRun runningRun = runRepository.ensureForJob(
                running.job(), "API", "local:auth-disabled", null, false);
        EmbeddingJob claimed = jobRepository.claimById(
                running.job().id(), "fresh-housekeeping-worker", 60).orElseThrow();
        runRepository.mirrorClaimed(claimed, "fresh-housekeeping-worker");

        housekeeping().scan();

        assertEquals(DocumentProcessingRunStatus.QUEUED,
                runRepository.find(queuedRun.id()).orElseThrow().status());
        assertEquals(DocumentProcessingRunStatus.RUNNING,
                runRepository.find(runningRun.id()).orElseThrow().status());
    }

    @Test
    void housekeepingAndLeaseRecoveryDoNotCreateRunOrFalseStaleState() throws Exception {
        Fixture fixture = fixture();
        DocumentProcessingRun run = runRepository.ensureForJob(
                fixture.job(), "API", "local:auth-disabled", null, false);
        EmbeddingJob claimed = jobRepository.claimById(
                fixture.job().id(), "expired-worker", 60).orElseThrow();
        runRepository.mirrorClaimed(claimed, "expired-worker");
        jdbcTemplate.update("""
                UPDATE rag_embedding_jobs
                SET lease_expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second'
                WHERE id = ?
                """, claimed.id());

        DocumentProcessingHousekeeping housekeeping = housekeeping();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> recovery = executor.submit(() -> {
                await(start);
                jobRepository.claim("recovery-worker", 1, 60);
            });
            Future<?> scan = executor.submit(() -> {
                await(start);
                housekeeping.scan();
            });
            start.countDown();
            recovery.get();
            scan.get();
        } finally {
            executor.shutdownNow();
        }

        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rag_document_processing_runs",
                Integer.class));
        assertTrue(runRepository.find(run.id()).orElseThrow().status()
                == DocumentProcessingRunStatus.RUNNING
                || runRepository.find(run.id()).orElseThrow().status()
                == DocumentProcessingRunStatus.QUEUED);
    }

    private DocumentProcessingHousekeeping housekeeping() {
        RagProperties properties = new RagProperties();
        properties.getDocumentProcessing().setEnabled(true);
        properties.getDocumentProcessing().setHousekeepingEnabled(true);
        properties.getDocumentProcessing().setMaxHousekeepingRunsPerScan(100);
        properties.getDocumentProcessing().setStuckThresholdSeconds(60);
        return new DocumentProcessingHousekeeping(
                runRepository, jobRepository, properties);
    }

    private Fixture fixture() {
        return fixture(3);
    }

    private Fixture fixture(int maxAttempts) {
        long collectionId = jdbcTemplate.queryForObject("""
                INSERT INTO rag_collection (collection_key, name, dimensions)
                VALUES (?, 'processing-it', 1024)
                RETURNING id
                """, Long.class, "processing-it-" + UUID.randomUUID());
        String hash = "0123456789abcdef0123456789abcdef"
                + "0123456789abcdef0123456789abcdef";
        long documentId = jdbcTemplate.queryForObject("""
                INSERT INTO rag_documents (
                    collection_id, title, content, content_hash,
                    processing_status, version)
                VALUES (?, 'processing', 'content', ?, 'PENDING', 1)
                RETURNING id
                """, Long.class, collectionId, hash);
        String modelName = "model-" + UUID.randomUUID();
        long profileId = jdbcTemplate.queryForObject("""
                INSERT INTO rag_embedding_profiles (
                    profile_key, provider, model_name, model_revision,
                    dimensions, distance_metric, normalization)
                VALUES (?, 'test', ?, 'v1', 1024, 'COSINE', 'NONE')
                RETURNING id
                """, Long.class, "processing-profile-" + UUID.randomUUID(), modelName);
        long generation = jobRepository.allocateGeneration(
                documentId, profileId, hash, "legacy-compatible", false);
        EmbeddingJob job = jobRepository.createOrCoalesce(
                UUID.randomUUID(), documentId, profileId, hash, 1L,
                false, maxAttempts, "API", "local:auth-disabled", generation,
                "TEXT", "legacy-compatible").job();
        jobRepository.activateJob(documentId, profileId, generation, job.id());
        assertNotNull(job);
        assertFalse(job.id().toString().isBlank());
        return new Fixture(documentId, profileId, job);
    }

    private static DocumentProcessingRun get(
            Future<DocumentProcessingRun> future) {
        try {
            return future.get();
        } catch (Exception error) {
            throw new AssertionError("Concurrent Run creation failed", error);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Concurrent test interrupted", error);
        }
    }

    private record Fixture(long documentId, long profileId, EmbeddingJob job) {
    }
}
