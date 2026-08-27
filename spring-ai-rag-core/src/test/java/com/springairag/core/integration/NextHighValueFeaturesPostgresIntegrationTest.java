package com.springairag.core.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.api.dto.DocumentLifecycleResponse;
import com.springairag.api.dto.ExternalDocumentRelocateRequest;
import com.springairag.api.dto.ExternalDocumentRelocateResponse;
import com.springairag.api.dto.ExternalDocumentUpsertRequest;
import com.springairag.api.dto.DerivationRepairApplyRequest;
import com.springairag.api.dto.DerivationRepairPreviewRequest;
import com.springairag.api.dto.DerivationRepairStatusResponse;
import com.springairag.api.enums.EmbeddingAction;
import com.springairag.api.enums.ErrorCode;
import com.springairag.core.config.EmbeddingProfile;
import com.springairag.core.config.RagProperties;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.embeddingjob.EmbeddingDispatchService;
import com.springairag.core.entity.RagCollection;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.entity.RagDocumentVersion;
import com.springairag.core.exception.RagException;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.repository.RagEmbeddingRepository;
import com.springairag.core.service.CollectionIdentityResolver;
import com.springairag.core.service.DerivationIntegrityRepository;
import com.springairag.core.service.DerivationIntegrityService;
import com.springairag.core.service.DerivationRepairService;
import com.springairag.core.service.DocumentDerivationDescriptorProvider;
import com.springairag.core.service.DocumentLifecycleService;
import com.springairag.core.service.DocumentMutationService;
import com.springairag.core.service.DocumentRelocationService;
import com.springairag.core.service.DocumentEmbedService;
import com.springairag.core.service.DocumentVersionService;
import com.springairag.core.service.EmbeddingPersistenceService;
import com.springairag.core.service.ExternalAddressRetirementService;
import com.springairag.core.service.KeywordIndexPersistenceService;
import jakarta.persistence.EntityManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** P0 relocation 与 P1 derivation integrity 的真实 PostgreSQL 验收。 */
class NextHighValueFeaturesPostgresIntegrationTest {

    private static final String HASH = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
            + "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String OTHER_HASH = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
            + "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String CHUNKER = "hierarchical-v2:1000:100:100";

    private static PostgreSQLContainer<?> postgres;
    private static DataSource dataSource;
    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;

    @BeforeAll
    static void startDatabase() {
        assumeTrue(Boolean.getBoolean("next-high-value.it.enabled"),
                "Set -Dnext-high-value.it.enabled=true to run this test");
        String externalUrl = System.getenv("NEXT_HIGH_VALUE_IT_JDBC_URL");
        if (externalUrl != null && !externalUrl.isBlank()) {
            if (!"YES".equals(System.getenv("NEXT_HIGH_VALUE_IT_CLEAN_CONFIRM"))) {
                throw new IllegalStateException(
                        "Set NEXT_HIGH_VALUE_IT_CLEAN_CONFIRM=YES only for a disposable database");
            }
            dataSource = dataSource(externalUrl,
                    System.getenv("NEXT_HIGH_VALUE_IT_USERNAME"),
                    System.getenv("NEXT_HIGH_VALUE_IT_PASSWORD"));
            return;
        }
        try {
            assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                    "Docker is unavailable for PostgreSQL integration tests");
        } catch (RuntimeException unavailable) {
            assumeTrue(false, "Docker is unavailable: " + unavailable.getMessage());
        }
        String image = System.getProperty("testcontainers.pg.image",
                System.getenv().getOrDefault("TESTCONTAINERS_PG_IMAGE",
                        "pgvector/pgvector:pg16"));
        postgres = new PostgreSQLContainer<>(DockerImageName.parse(image)
                .asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("next_high_value_features")
                .withUsername("postgres").withPassword("postgres");
        postgres.start();
        dataSource = dataSource(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword());
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @BeforeEach
    void migrateEmptyDatabase() {
        Flyway flyway = Flyway.configure().dataSource(dataSource)
                .locations("classpath:db/migration").cleanDisabled(false).load();
        flyway.clean();
        flyway.migrate();
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @Test
    void latestMigrationsCreateDurableControlPlanesFromEmptyDatabase() {
        assertEquals("57", jdbc.queryForObject(
                "SELECT version FROM flyway_schema_history WHERE success = TRUE ORDER BY installed_rank DESC LIMIT 1",
                String.class));
        assertEquals(2L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_name IN ('rag_document_relocated_addresses', 'rag_derivation_repair_previews')",
                Long.class));
        assertEquals(2L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_name = 'rag_document_idempotency_operations' AND column_name IN ('result_payload', 'authorization_collection_ids')",
                Long.class));
    }

    @Test
    void relocationPreservesIdentityAndDerivationsAndReplaysExactly() {
        long sourceId = insertCollection("source");
        long targetId = insertCollection("target");
        long profileId = insertProfile();
        long documentId = insertDocument(sourceId, "article:100", "rev-8");
        insertLocalAndVector(documentId, profileId);
        DocumentRelocationService service = relocationService(sourceId, targetId);
        ExternalDocumentRelocateRequest request = new ExternalDocumentRelocateRequest(
                "source", "target", "cms", "article:100", "rev-8");

        ExternalDocumentRelocateResponse first = transactions.execute(status ->
                service.relocate(request, "relocate-key-1"));
        assertNotNull(first);
        assertEquals(documentId, first.documentId());
        assertEquals("rev-8", first.sourceRevision());
        assertEquals("PRESERVED", first.derivationAction());
        assertEquals(targetId, jdbc.queryForObject(
                "SELECT collection_id FROM rag_documents WHERE id = ?", Long.class, documentId));
        assertEquals(2L, jdbc.queryForObject(
                "SELECT document_revision FROM rag_documents WHERE id = ?", Long.class, documentId));
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM rag_document_chunks WHERE document_id = ?", Long.class, documentId));
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM rag_embeddings WHERE document_id = ?", Long.class, documentId));
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM rag_document_relocated_addresses WHERE source_collection_id = ? AND active = TRUE",
                Long.class, sourceId));
        assertEquals(1, jdbc.queryForObject(
                "SELECT (result_payload ->> 'schemaVersion')::int FROM rag_document_idempotency_operations WHERE operation_type = 'EXTERNAL_RELOCATE'",
                Integer.class));

        ExternalDocumentRelocateResponse replay = transactions.execute(status ->
                service.relocate(request, "relocate-key-1"));
        assertEquals(first, replay);
        assertEquals(1L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM rag_document_idempotency_operations WHERE operation_type = 'EXTERNAL_RELOCATE'",
                Long.class));

        ExternalAddressRetirementService guard = new ExternalAddressRetirementService(jdbc);
        RagException retired = assertThrows(RagException.class,
                () -> guard.requireNotRetired(sourceId, "cms", "article:100"));
        assertEquals(ErrorCode.EXTERNAL_IDENTITY_RELOCATED, retired.getErrorCodeEnum());
    }

    @Test
    void relocationRejectsActiveSyncRunWithoutLeavingPartialState() {
        long sourceId = insertCollection("source");
        long targetId = insertCollection("target");
        long documentId = insertDocument(sourceId, "article:100", "rev-8");
        jdbc.update("""
                INSERT INTO rag_document_sync_runs (
                    id, collection_id, source_namespace, client_run_id,
                    lease_token_hash, sync_generation, snapshot_start_sequence,
                    snapshot_mode, missing_policy, status, lease_expires_at
                ) VALUES (?, ?, 'cms', 'run-1', ?, 1, 0,
                    'ONLINE_CUT', 'NONE', 'ACTIVE', CURRENT_TIMESTAMP + INTERVAL '5 minutes')
                """, UUID.randomUUID(), sourceId, HASH);
        DocumentRelocationService service = relocationService(sourceId, targetId);

        RagException conflict = assertThrows(RagException.class, () ->
                transactions.execute(status -> service.relocate(
                        new ExternalDocumentRelocateRequest(
                                "source", "target", "cms", "article:100", "rev-8"),
                        "active-run-key")));
        assertEquals(ErrorCode.ACTIVE_SYNC_RUN_CONFLICT, conflict.getErrorCodeEnum());
        assertEquals(sourceId, jdbc.queryForObject(
                "SELECT collection_id FROM rag_documents WHERE id = ?", Long.class, documentId));
        assertEquals(0L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM rag_document_idempotency_operations WHERE operation_type = 'EXTERNAL_RELOCATE'",
                Long.class));
    }

    @Test
    void externalUpsertRechecksRetiredAddressAfterNamespaceSequenceWait()
            throws Exception {
        long sourceId = insertCollection("source");
        long targetId = insertCollection("target");
        jdbc.update("""
                INSERT INTO rag_document_source_namespaces (
                    collection_id, source_namespace, mutation_sequence
                ) VALUES (?, 'cms', 0)
                """, sourceId);

        CountDownLatch firstGuardPassed = new CountDownLatch(1);
        CountDownLatch relocationOwnsSequence = new CountDownLatch(1);
        AtomicInteger guardCalls = new AtomicInteger();
        ExternalAddressRetirementService realGuard =
                new ExternalAddressRetirementService(jdbc);
        ExternalAddressRetirementService barrierGuard =
                mock(ExternalAddressRetirementService.class);
        doAnswer(invocation -> {
            if (guardCalls.incrementAndGet() == 1) {
                firstGuardPassed.countDown();
                assertTrue(relocationOwnsSequence.await(5, TimeUnit.SECONDS));
                return null;
            }
            realGuard.requireNotRetired(
                    invocation.getArgument(0), invocation.getArgument(1),
                    invocation.getArgument(2));
            return null;
        }).when(barrierGuard).requireNotRetired(sourceId, "cms", "article:late");

        CollectionIdentityResolver resolver = mock(CollectionIdentityResolver.class);
        when(resolver.requireActive(null, "source"))
                .thenReturn(collection(sourceId, "source"));
        RagDocumentRepository documentRepository = mock(RagDocumentRepository.class);
        when(documentRepository.findByCollectionIdAndSourceNamespaceAndExternalId(
                sourceId, "cms", "article:late")).thenReturn(Optional.empty());
        DocumentMutationService mutationService = new DocumentMutationService(
                documentRepository, mock(RagEmbeddingRepository.class), resolver,
                mock(DocumentVersionService.class), mock(EmbeddingDispatchService.class),
                mock(DocumentEmbedService.class), mock(DocumentLifecycleService.class),
                jdbc, new ObjectMapper(), enabledRagProperties(),
                new DataSourceTransactionManager(dataSource));
        ReflectionTestUtils.invokeMethod(mutationService,
                "setAddressRetirementService", barrierGuard);

        ExternalDocumentUpsertRequest request = new ExternalDocumentUpsertRequest();
        request.setCollectionKey("source");
        request.setSourceNamespace("cms");
        request.setExternalId("article:late");
        request.setSourceRevision("rev-1");
        request.setTitle("Late delivery");
        request.setContent("Must not recreate the retired address");
        request.setEmbed(false);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<RagException> upsert = executor.submit(() -> assertThrows(
                    RagException.class, () -> mutationService.upsertExternal(request)));
            assertTrue(firstGuardPassed.await(5, TimeUnit.SECONDS));
            transactions.executeWithoutResult(status -> {
                jdbc.queryForObject("""
                        UPDATE rag_document_source_namespaces
                        SET mutation_sequence = mutation_sequence + 1
                        WHERE collection_id = ? AND source_namespace = 'cms'
                        RETURNING mutation_sequence
                        """, Long.class, sourceId);
                jdbc.update("""
                        INSERT INTO rag_document_relocated_addresses (
                            source_collection_id, source_namespace, external_id,
                            target_collection_id
                        ) VALUES (?, 'cms', 'article:late', ?)
                        """, sourceId, targetId);
                relocationOwnsSequence.countDown();
            });

            RagException conflict = upsert.get(10, TimeUnit.SECONDS);
            assertEquals(ErrorCode.EXTERNAL_IDENTITY_RELOCATED,
                    conflict.getErrorCodeEnum());
        } finally {
            relocationOwnsSequence.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
        assertEquals(2, guardCalls.get());
        assertEquals(1L, jdbc.queryForObject("""
                SELECT mutation_sequence FROM rag_document_source_namespaces
                WHERE collection_id = ? AND source_namespace = 'cms'
                """, Long.class, sourceId));
        assertEquals(0L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM rag_documents WHERE collection_id = ?",
                Long.class, sourceId));
        verify(documentRepository, never()).saveAndFlush(any());
    }

    @Test
    void strictIntegrityRejectsSameCountButMismatchedVectorContent() {
        long collectionId = insertCollection("integrity");
        long profileId = insertProfile();
        long documentId = insertDocument(collectionId, null, null);
        insertLocalAndVector(documentId, profileId);
        EmbeddingProfile profile = new EmbeddingProfile(profileId, "test-profile",
                "test", "test", "v1", 1024, "COSINE", "NONE", true);
        DerivationIntegrityRepository repository = new DerivationIntegrityRepository(
                jdbc, () -> profile,
                new DocumentDerivationDescriptorProvider(new RagProperties()));

        DerivationIntegrityRepository.Snapshot ready = repository.inspect(documentId);
        assertTrue(ready.localFresh());
        assertTrue(ready.vectorFresh());
        assertEquals("READY", ready.bucket());
        DerivationIntegrityRepository.Aggregate readyAggregate =
                repository.aggregateCollection(collectionId);
        assertEquals(1, readyAggregate.enabledDocuments());
        assertEquals(1, readyAggregate.readyDocuments());
        assertEquals(1, repository.scanCollection(
                collectionId, "READY", 0, 1).size());
        assertEquals(1, repository.aggregateEmbeddingReadiness(
                collectionId).freshDocuments());

        jdbc.update("UPDATE rag_embeddings SET chunk_text = 'stale text' WHERE document_id = ?",
                documentId);
        DerivationIntegrityRepository.Snapshot corrupt = repository.inspect(documentId);
        assertTrue(corrupt.localFresh());
        assertFalse(corrupt.vectorFresh());
        assertTrue(corrupt.vectorCorrupt());
        assertEquals("CORRUPT", corrupt.bucket());
        DerivationIntegrityRepository.Aggregate corruptAggregate =
                repository.aggregateCollection(collectionId);
        assertEquals(1, corruptAggregate.corruptDocuments());
        assertEquals(1, corruptAggregate.vectorRepairNeededDocuments());
        assertEquals(1, repository.scanCollection(
                collectionId, "CORRUPT", 0, 1).size());
        assertEquals(1, repository.aggregateEmbeddingReadiness(
                collectionId).staleDocuments());

        jdbc.update("UPDATE rag_documents SET source_deleted_at = CURRENT_TIMESTAMP "
                + "WHERE id = ?", documentId);
        DerivationIntegrityRepository.Snapshot tombstoned = repository.inspect(documentId);
        assertEquals("DISABLED", tombstoned.bucket());
        assertFalse(tombstoned.toResponse().repairable());
        assertEquals(0, tombstoned.toResponse().recommendedActions().size());
        DerivationIntegrityRepository.Aggregate tombstoneAggregate =
                repository.aggregateCollection(collectionId);
        assertEquals(0, tombstoneAggregate.enabledDocuments());
        assertEquals(1, tombstoneAggregate.disabledDocuments());
        assertEquals(0, tombstoneAggregate.vectorRepairNeededDocuments());
        assertEquals(0, repository.aggregateEmbeddingReadiness(
                collectionId).enabledDocuments());

        EmbeddingPersistenceService persistence = new EmbeddingPersistenceService(jdbc);
        persistence.setIntegrityRepository(repository);
        assertFalse(persistence.findCacheState(
                documentId, profile, HASH, CHUNKER).hit());
    }

    @Test
    void repairPreviewAndApplyPersistStableLedgerAndOnlyQueueVectorWork() {
        long collectionId = insertCollection("repair");
        long profileId = insertProfile();
        long documentId = insertDocument(collectionId, null, null);
        insertLocalAndVector(documentId, profileId);
        jdbc.update("UPDATE rag_embeddings SET chunk_text = 'stale text' WHERE document_id = ?",
                documentId);
        EmbeddingProfile profile = new EmbeddingProfile(profileId, "repair-profile",
                "test", "test", "v1", 1024, "COSINE", "NONE", true);
        EmbeddingProfileProvider profileProvider = () -> profile;
        DocumentDerivationDescriptorProvider descriptor =
                new DocumentDerivationDescriptorProvider(new RagProperties());
        DerivationIntegrityRepository integrityRepository =
                new DerivationIntegrityRepository(jdbc, profileProvider, descriptor);

        CollectionIdentityResolver resolver = mock(CollectionIdentityResolver.class);
        RagCollection collection = collection(collectionId, "repair");
        when(resolver.requireActive(null, "repair")).thenReturn(collection);
        when(resolver.beginActiveWrite(collectionId)).thenReturn(
                new CollectionIdentityResolver.ActiveCollectionToken(collectionId, 0));
        when(resolver.requireIncludingDeleted(collectionId, null)).thenReturn(collection);
        DerivationIntegrityService integrityService = new DerivationIntegrityService(
                integrityRepository, resolver, profileProvider);
        RagDocumentRepository documentRepository = mock(RagDocumentRepository.class);
        when(documentRepository.findById(documentId))
                .thenAnswer(invocation -> Optional.of(readDocument(documentId)));
        KeywordIndexPersistenceService keywordService = mock(
                KeywordIndexPersistenceService.class);
        EmbeddingDispatchService dispatchService = mock(EmbeddingDispatchService.class);
        UUID jobId = UUID.randomUUID();
        when(dispatchService.enqueueInCurrentTransaction(
                any(), eq(false), eq(true), eq("DERIVATION_REPAIR")))
                .thenReturn(new EmbeddingDispatchService.Result(
                        EmbeddingAction.ASYNC_QUEUED, "QUEUED", "repair-profile",
                        jobId, UUID.randomUUID(), null));
        DerivationRepairService repairService = new DerivationRepairService(
                jdbc, new ObjectMapper().findAndRegisterModules(), integrityRepository,
                integrityService, documentRepository, keywordService, dispatchService,
                resolver, profileProvider, enabledRagProperties(),
                new DataSourceTransactionManager(dataSource));

        var preview = repairService.preview(new DerivationRepairPreviewRequest(
                "repair", List.of("CORRUPT"), List.of("FAILED", "STALE"), 100));
        assertEquals(1, preview.items().size());
        assertEquals("QUEUE_VECTOR", preview.items().getFirst().action());
        assertFalse(preview.previewToken().equals(jdbc.queryForObject(
                "SELECT preview_token_hash FROM rag_derivation_repair_previews WHERE id = ?",
                String.class, preview.repairId())));

        DerivationRepairStatusResponse applied = repairService.apply(
                new DerivationRepairApplyRequest(
                        preview.repairId(), "repair", preview.previewToken(),
                        preview.previewFingerprint()));
        assertEquals("COMPLETED", applied.status());
        assertEquals("QUEUED_VECTOR", applied.items().getFirst().resultCode());
        assertEquals(jobId, applied.items().getFirst().embeddingJobId());
        assertEquals(0L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM rag_derivation_repair_previews WHERE request_payload::text LIKE ? OR plan_payload::text LIKE ?",
                Long.class, "%" + preview.previewToken() + "%",
                "%" + preview.previewToken() + "%"));
    }

    @Test
    void repairSelectionExcludesAlreadyConvergingDocuments() {
        long collectionId = insertCollection("converging");
        long profileId = insertProfile();
        long documentId = insertDocument(collectionId, null, null);
        insertLocalAndVector(documentId, profileId);
        UUID jobId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO rag_embedding_jobs (
                    id, batch_id, document_id, embedding_profile_id, force,
                    content_hash, document_version, status, request_generation,
                    document_kind, chunker_version
                ) VALUES (?, ?, ?, ?, TRUE, ?, 0, 'QUEUED', 2, 'TEXT', ?)
                """, jobId, UUID.randomUUID(), documentId, profileId, HASH, CHUNKER);
        jdbc.update("""
                UPDATE rag_document_embedding_state
                SET status = 'QUEUED', request_generation = 2, active_job_id = ?
                WHERE document_id = ? AND embedding_profile_id = ?
                """, jobId, documentId, profileId);
        DerivationIntegrityRepository repository = integrityRepository(profileId);

        DerivationIntegrityRepository.Snapshot snapshot = repository.inspect(documentId);
        assertEquals("KEYWORD_ONLY", snapshot.bucket());
        assertEquals("INDEXING", snapshot.vectorCondition());
        assertFalse(snapshot.toResponse().repairable());
        assertTrue(snapshot.toResponse().recommendedActions().isEmpty());
        assertEquals(1, repository.countRepairSelection(
                collectionId, Set.of("KEYWORD_ONLY"), Set.of("INDEXING")));
        assertTrue(repository.scanRepairCandidates(
                collectionId, Set.of("KEYWORD_ONLY"), Set.of("INDEXING"), 100).isEmpty());
    }

    @Test
    void repairApplyTakesOverExpiredLeasesAndRetainsResultForFullDay() {
        RepairFixture fixture = repairFixture("repair-takeover");
        UUID expiredId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO rag_derivation_repair_previews (
                    id, owner_principal_id, collection_id, active_embedding_profile_id,
                    preview_token_hash, preview_fingerprint, request_payload,
                    plan_payload, status, preview_deadline, operation_deadline,
                    result_expires_at, created_at, completed_at
                ) VALUES (?, 'expired-owner', ?, ?, ?, ?, '{}'::jsonb, '[]'::jsonb,
                    'COMPLETED', CURRENT_TIMESTAMP - INTERVAL '26 hours',
                    CURRENT_TIMESTAMP - INTERVAL '25 hours',
                    CURRENT_TIMESTAMP - INTERVAL '1 minute',
                    CURRENT_TIMESTAMP - INTERVAL '26 hours',
                    CURRENT_TIMESTAMP - INTERVAL '25 hours')
                """, expiredId, fixture.collectionId(), fixture.profileId(), HASH, HASH);

        var preview = fixture.service().preview(new DerivationRepairPreviewRequest(
                fixture.collectionKey(), List.of("CORRUPT"), List.of("STALE"), 100));
        assertEquals(0L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM rag_derivation_repair_previews WHERE id = ?",
                Long.class, expiredId));
        jdbc.update("""
                UPDATE rag_derivation_repair_previews
                SET status = 'APPLYING', apply_lease_owner_hash = ?,
                    apply_lease_expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second'
                WHERE id = ?
                """, HASH, preview.repairId());
        jdbc.update("""
                UPDATE rag_derivation_repair_items
                SET status = 'APPLYING', lease_owner_hash = ?,
                    lease_expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second',
                    attempt_count = 1
                WHERE repair_id = ? AND document_id = ?
                """, HASH, preview.repairId(), fixture.documentId());

        DerivationRepairStatusResponse applied = fixture.service().apply(
                new DerivationRepairApplyRequest(preview.repairId(), fixture.collectionKey(),
                        preview.previewToken(), preview.previewFingerprint()));

        assertEquals("COMPLETED", applied.status());
        assertEquals(2, jdbc.queryForObject(
                "SELECT attempt_count FROM rag_derivation_repair_items WHERE repair_id = ? AND document_id = ?",
                Integer.class, preview.repairId(), fixture.documentId()));
        assertEquals(0L, jdbc.queryForObject("""
                SELECT COUNT(*) FROM rag_derivation_repair_previews
                WHERE id = ? AND (apply_lease_owner_hash IS NOT NULL
                    OR apply_lease_expires_at IS NOT NULL)
                """, Long.class, preview.repairId()));
        assertTrue(jdbc.queryForObject("""
                SELECT result_expires_at >= completed_at + INTERVAL '23 hours 59 minutes'
                FROM rag_derivation_repair_previews WHERE id = ?
                """, Boolean.class, preview.repairId()));
    }

    @Test
    void repairTakeoverContinuesFromCommittedLocalLedgerState() {
        RepairFixture fixture = repairFixture("repair-post-local");
        jdbc.update("""
                UPDATE rag_document_local_index_state SET content_hash = ?
                WHERE document_id = ?
                """, OTHER_HASH, fixture.documentId());
        var preview = fixture.service().preview(new DerivationRepairPreviewRequest(
                fixture.collectionKey(), List.of("CORRUPT"), List.of("CORRUPT"), 100));
        assertEquals("REBUILD_LOCAL_AND_QUEUE_VECTOR",
                preview.items().getFirst().action());

        jdbc.update("UPDATE rag_documents SET version = version + 1 WHERE id = ?",
                fixture.documentId());
        long postLocalVersion = jdbc.queryForObject(
                "SELECT version FROM rag_documents WHERE id = ?",
                Long.class, fixture.documentId());
        jdbc.update("""
                UPDATE rag_document_local_index_state SET content_hash = ?
                WHERE document_id = ?
                """, HASH, fixture.documentId());
        jdbc.update("""
                UPDATE rag_derivation_repair_previews
                SET status = 'APPLYING', apply_lease_owner_hash = ?,
                    apply_lease_expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second'
                WHERE id = ?
                """, HASH, preview.repairId());
        jdbc.update("""
                UPDATE rag_derivation_repair_items
                SET status = 'APPLYING', local_action_status = 'SUCCEEDED',
                    lease_owner_hash = ?,
                    lease_expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second',
                    attempt_count = 1, post_local_document_version = ?,
                    post_local_content_hash = ?, post_local_generation = 1
                WHERE repair_id = ? AND document_id = ?
                """, HASH, postLocalVersion, HASH, preview.repairId(), fixture.documentId());

        DerivationRepairStatusResponse applied = fixture.service().apply(
                new DerivationRepairApplyRequest(preview.repairId(), fixture.collectionKey(),
                        preview.previewToken(), preview.previewFingerprint()));

        assertEquals("COMPLETED", applied.status());
        assertEquals("SUCCEEDED", applied.items().getFirst().localActionStatus());
        assertEquals("SUCCEEDED", applied.items().getFirst().vectorActionStatus());
        assertEquals("QUEUED_VECTOR", applied.items().getFirst().resultCode());
        assertEquals(2, jdbc.queryForObject(
                "SELECT attempt_count FROM rag_derivation_repair_items WHERE repair_id = ? AND document_id = ?",
                Integer.class, preview.repairId(), fixture.documentId()));
    }

    @Test
    void repairRejectsActiveProfileChangeWithoutQueueingWork() {
        RepairFixture fixture = repairFixture("repair-profile-change");
        var preview = fixture.service().preview(new DerivationRepairPreviewRequest(
                fixture.collectionKey(), List.of("CORRUPT"), List.of("STALE"), 100));
        long replacementProfileId = insertProfile("v2");
        fixture.activeProfile().set(new EmbeddingProfile(
                replacementProfileId, "replacement-profile", "test", "test",
                "v2", 1024, "COSINE", "NONE", true));

        RagException conflict = assertThrows(RagException.class, () ->
                fixture.service().apply(new DerivationRepairApplyRequest(
                        preview.repairId(), fixture.collectionKey(),
                        preview.previewToken(), preview.previewFingerprint())));

        assertEquals(ErrorCode.DERIVATION_REPAIR_CONFLICT,
                conflict.getErrorCodeEnum());
        assertEquals("PREVIEWED", jdbc.queryForObject(
                "SELECT status FROM rag_derivation_repair_previews WHERE id = ?",
                String.class, preview.repairId()));
        verify(fixture.dispatchService(), never()).enqueueInCurrentTransaction(
                any(), any(Boolean.class), any(Boolean.class), any());
    }

    private DocumentRelocationService relocationService(long sourceId, long targetId) {
        CollectionIdentityResolver resolver = mock(CollectionIdentityResolver.class);
        RagCollection source = collection(sourceId, "source");
        RagCollection target = collection(targetId, "target");
        when(resolver.requireActive(null, "source")).thenReturn(source);
        when(resolver.requireActive(null, "target")).thenReturn(target);
        when(resolver.beginActiveWrite(anyLong())).thenAnswer(invocation ->
                new CollectionIdentityResolver.ActiveCollectionToken(
                        invocation.getArgument(0), 0));

        RagDocumentRepository repository = mock(RagDocumentRepository.class);
        when(repository.findById(anyLong())).thenAnswer(invocation ->
                Optional.of(readDocument(invocation.getArgument(0))));
        DocumentVersionService versionService = mock(DocumentVersionService.class);
        when(versionService.forceRecordVersion(any(), eq("RELOCATE"), any()))
                .thenAnswer(invocation -> {
                    RagDocument document = invocation.getArgument(0);
                    Integer version = jdbc.queryForObject(
                            "UPDATE rag_documents SET next_history_version = next_history_version + 1 WHERE id = ? RETURNING next_history_version - 1",
                            Integer.class, document.getId());
                    RagDocumentVersion result = new RagDocumentVersion();
                    result.setVersionNumber(version == null ? 1 : version);
                    return result;
                });
        DocumentLifecycleService lifecycle = mock(DocumentLifecycleService.class);
        when(lifecycle.read(any())).thenReturn(new DocumentLifecycleResponse(
                "ACTIVE", "READY", "READY", "READY", "test-profile",
                null, null, null, false));
        return new DocumentRelocationService(
                jdbc, new ObjectMapper().findAndRegisterModules(), repository, resolver,
                versionService, lifecycle, enabledRagProperties(), mock(EntityManager.class));
    }

    private static RagProperties enabledRagProperties() {
        RagProperties properties = new RagProperties();
        properties.getDocumentLifecycle().setRelocationEnabled(true);
        properties.getDocumentLifecycle().setDerivationRepairEnabled(true);
        return properties;
    }

    private DerivationIntegrityRepository integrityRepository(long profileId) {
        EmbeddingProfile profile = new EmbeddingProfile(profileId, "test-profile",
                "test", "test", "v1", 1024, "COSINE", "NONE", true);
        return new DerivationIntegrityRepository(jdbc, () -> profile,
                new DocumentDerivationDescriptorProvider(new RagProperties()));
    }

    private RepairFixture repairFixture(String collectionKey) {
        long collectionId = insertCollection(collectionKey);
        long profileId = insertProfile();
        long documentId = insertDocument(collectionId, null, null);
        insertLocalAndVector(documentId, profileId);
        jdbc.update("UPDATE rag_embeddings SET chunk_text = 'stale text' WHERE document_id = ?",
                documentId);
        EmbeddingProfile profile = new EmbeddingProfile(profileId, "repair-profile",
                "test", "test", "v1", 1024, "COSINE", "NONE", true);
        AtomicReference<EmbeddingProfile> activeProfile = new AtomicReference<>(profile);
        EmbeddingProfileProvider profileProvider = activeProfile::get;
        DerivationIntegrityRepository integrityRepository = new DerivationIntegrityRepository(
                jdbc, profileProvider,
                new DocumentDerivationDescriptorProvider(new RagProperties()));
        CollectionIdentityResolver resolver = mock(CollectionIdentityResolver.class);
        RagCollection collection = collection(collectionId, collectionKey);
        when(resolver.requireActive(null, collectionKey)).thenReturn(collection);
        when(resolver.beginActiveWrite(collectionId)).thenReturn(
                new CollectionIdentityResolver.ActiveCollectionToken(collectionId, 0));
        when(resolver.requireIncludingDeleted(collectionId, null)).thenReturn(collection);
        DerivationIntegrityService integrityService = new DerivationIntegrityService(
                integrityRepository, resolver, profileProvider);
        RagDocumentRepository documentRepository = mock(RagDocumentRepository.class);
        when(documentRepository.findById(documentId))
                .thenAnswer(invocation -> Optional.of(readDocument(documentId)));
        EmbeddingDispatchService dispatchService = mock(EmbeddingDispatchService.class);
        when(dispatchService.enqueueInCurrentTransaction(
                any(), eq(false), eq(true), eq("DERIVATION_REPAIR")))
                .thenReturn(new EmbeddingDispatchService.Result(
                        EmbeddingAction.ASYNC_QUEUED, "QUEUED", "repair-profile",
                        UUID.randomUUID(), UUID.randomUUID(), null));
        DerivationRepairService service = new DerivationRepairService(
                jdbc, new ObjectMapper().findAndRegisterModules(), integrityRepository,
                integrityService, documentRepository,
                mock(KeywordIndexPersistenceService.class), dispatchService, resolver,
                profileProvider, enabledRagProperties(),
                new DataSourceTransactionManager(dataSource));
        return new RepairFixture(service, collectionId, profileId, documentId, collectionKey,
                activeProfile, dispatchService);
    }

    private RagDocument readDocument(long id) {
        return jdbc.queryForObject("""
                SELECT id, version, document_revision, next_history_version,
                       collection_id, title, content, document_type, content_hash,
                       external_id, source_namespace, source_revision, enabled,
                       source_mutation_sequence
                FROM rag_documents WHERE id = ?
                """, (rs, rowNum) -> {
            RagDocument document = new RagDocument();
            document.setId(rs.getLong("id"));
            document.setVersion(rs.getLong("version"));
            document.setDocumentRevision(rs.getLong("document_revision"));
            document.setNextHistoryVersion(rs.getInt("next_history_version"));
            document.setCollectionId(rs.getLong("collection_id"));
            document.setTitle(rs.getString("title"));
            document.setContent(rs.getString("content"));
            document.setDocumentType(rs.getString("document_type"));
            document.setContentHash(rs.getString("content_hash"));
            document.setExternalId(rs.getString("external_id"));
            document.setSourceNamespace(rs.getString("source_namespace"));
            document.setSourceRevision(rs.getString("source_revision"));
            document.setEnabled(rs.getBoolean("enabled"));
            document.setSourceMutationSequence(rs.getLong("source_mutation_sequence"));
            return document;
        }, id);
    }

    private long insertCollection(String key) {
        return jdbc.queryForObject(
                "INSERT INTO rag_collection (collection_key, name, dimensions) VALUES (?, ?, 1024) RETURNING id",
                Long.class, key, key);
    }

    private long insertProfile() {
        return insertProfile("v1");
    }

    private long insertProfile(String modelRevision) {
        return jdbc.queryForObject("""
                INSERT INTO rag_embedding_profiles (
                    profile_key, provider, model_name, model_revision,
                    dimensions, distance_metric, normalization, enabled
                ) VALUES (?, 'test', 'test', ?, 1024, 'COSINE', 'NONE', TRUE)
                RETURNING id
                """, Long.class, "test-profile-" + UUID.randomUUID(), modelRevision);
    }

    private long insertDocument(
            long collectionId, String externalId, String sourceRevision) {
        return jdbc.queryForObject("""
                INSERT INTO rag_documents (
                    collection_id, title, content, document_type, content_hash,
                    external_id, source_namespace, source_revision,
                    processing_status, enabled
                ) VALUES (?, 'Document', 'current text', 'text', ?, ?, 'cms', ?,
                    'COMPLETED', TRUE) RETURNING id
                """, Long.class, collectionId, HASH, externalId, sourceRevision);
    }

    private void insertLocalAndVector(long documentId, long profileId) {
        jdbc.update("""
                INSERT INTO rag_document_local_index_state (
                    document_id, local_index_status, content_hash, chunker_version,
                    local_index_generation, chunk_count
                ) VALUES (?, 'READY', ?, ?, 1, 1)
                """, documentId, HASH, CHUNKER);
        jdbc.update("""
                INSERT INTO rag_document_chunks (
                    document_id, local_index_generation, content_hash, chunker_version,
                    chunk_text, chunk_index, chunk_start_pos, chunk_end_pos
                ) VALUES (?, 1, ?, ?, 'current text', 0, 0, 12)
                """, documentId, HASH, CHUNKER);
        jdbc.update("""
                INSERT INTO rag_document_embedding_state (
                    document_id, embedding_profile_id, content_hash, chunker_version,
                    status, chunk_count, request_generation
                ) VALUES (?, ?, ?, ?, 'COMPLETED', 1, 1)
                """, documentId, profileId, HASH, CHUNKER);
        jdbc.update("""
                INSERT INTO rag_embeddings (
                    document_id, chunk_text, chunk_index, embedding, embedding_1024,
                    embedding_profile_id, chunk_start_pos, chunk_end_pos
                ) VALUES (?, 'current text', 0,
                    array_fill(0.0::real, ARRAY[1024])::vector,
                    array_fill(0.0::real, ARRAY[1024])::vector, ?, 0, 12)
                """, documentId, profileId);
    }

    private static RagCollection collection(long id, String key) {
        RagCollection collection = new RagCollection();
        collection.setId(id);
        collection.setCollectionKey(key);
        collection.setName(key);
        collection.setVersion(0L);
        collection.setDeleted(false);
        return collection;
    }

    private static DataSource dataSource(String url, String username, String password) {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setUrl(url);
        source.setUser(username == null || username.isBlank() ? "postgres" : username);
        source.setPassword(password == null ? "" : password);
        return source;
    }

    private record RepairFixture(
            DerivationRepairService service,
            long collectionId,
            long profileId,
            long documentId,
            String collectionKey,
            AtomicReference<EmbeddingProfile> activeProfile,
            EmbeddingDispatchService dispatchService) {
    }
}
