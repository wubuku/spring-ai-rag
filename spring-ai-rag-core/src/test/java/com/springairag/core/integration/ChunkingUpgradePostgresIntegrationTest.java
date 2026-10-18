package com.springairag.core.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.springairag.core.config.EmbeddingProfile;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.config.RagChunkProperties;
import com.springairag.core.config.RagProperties;
import com.springairag.core.observability.ChunkingUpgradeMetrics;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.embeddingjob.EmbeddingJobRepository;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.retrieval.EmbeddingBatchService;
import com.springairag.core.retrieval.EmbeddingFailureClassifier;
import com.springairag.core.retrieval.EmbeddingProfileSqlScope;
import com.springairag.core.retrieval.KeywordIndexSqlScope;
import com.springairag.core.service.ChunkingUpgradeService;
import com.springairag.core.service.DocumentChunkingService;
import com.springairag.core.service.DocumentDerivationDescriptorProvider;
import com.springairag.core.service.EmbeddingPersistenceService;
import com.springairag.core.service.KeywordIndexPersistenceService;
import com.springairag.core.util.DigestUtils;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.ai.retry.TransientAiException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@EnabledIfSystemProperty(named = "chunking-upgrade.it.enabled", matches = "true")
class ChunkingUpgradePostgresIntegrationTest {
    private static PostgreSQLContainer<?> postgres;
    private JdbcTemplate jdbc;
    private RagProperties properties;
    private EmbeddingProfile profile;
    private EmbeddingProfileProvider profiles;
    private EmbeddingBatchService embeddings;
    private RagDocumentRepository documents;
    private DocumentDerivationDescriptorProvider descriptors;
    private ChunkingUpgradeService upgrade;
    private KeywordIndexPersistenceService keywords;
    private EmbeddingPersistenceService vectors;
    private SimpleMeterRegistry registry;
    private long documentId;
    private final String content = "# First\nfirst content\n# Second\nsecond content\n" + "中文检索证据。".repeat(160);

    @BeforeAll
    static void startDatabase() {
        postgres = new PostgreSQLContainer<>(DockerImageName.parse(System.getenv().getOrDefault(
                "TESTCONTAINERS_PG_IMAGE", "postgres:16-pgvector")).asCompatibleSubstituteFor("postgres"));
        postgres.start();
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    private Flyway flyway(String target) {
        var config = Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration").cleanDisabled(false);
        if (target != null) {
            config.target(target);
        }
        return config.load();
    }

    @BeforeEach
    void setup() {
        setupAtVersion(null);
    }

    private void setupAtVersion(String version) {
        flyway(null).clean();
        flyway("61").migrate();
        var dataSource = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        long profileId = jdbc.queryForObject("""
                INSERT INTO rag_embedding_profiles
                    (profile_key, provider, model_name, model_revision, dimensions, distance_metric, normalization)
                VALUES ('upgrade-test', 'test', 'test', 'v1', 1024, 'COSINE', 'PROVIDER_DEFAULT') RETURNING id
                """, Long.class);
        profile = new EmbeddingProfile(profileId, "upgrade-test", "test", "test", "v1", 1024,
                "COSINE", "PROVIDER_DEFAULT", true);
        documentId = jdbc.queryForObject("""
                INSERT INTO rag_documents (title, content, content_hash, document_type, enabled, version)
                VALUES ('upgrade fixture', ?, ?, 'text', true, 0) RETURNING id
                """, Long.class, content, DigestUtils.sha256(content));
        RagProperties legacy = new RagProperties();
        legacy.getChunk().setProfile(RagChunkProperties.Profile.LEGACY_V2);
        var oldDescriptors = new DocumentDerivationDescriptorProvider(legacy);
        var oldChunks = new DocumentChunkingService(legacy, oldDescriptors);
        var oldKeywords = new KeywordIndexPersistenceService(jdbc, oldChunks, oldDescriptors);
        oldKeywords.ensureCurrent(document(false));
        flyway(version).migrate();
        properties = new RagProperties();
        descriptors = new DocumentDerivationDescriptorProvider(properties);
        var chunking = new DocumentChunkingService(properties, descriptors);
        keywords = new KeywordIndexPersistenceService(jdbc, chunking, descriptors);
        profiles = mock(EmbeddingProfileProvider.class);
        when(profiles.getActiveProfile()).thenReturn(profile);
        embeddings = mock(EmbeddingBatchService.class);
        when(embeddings.createEmbeddingsBatch(anyList())).thenAnswer(invocation ->
                invocation.<List<String>>getArgument(0).stream().map(text ->
                        new EmbeddingBatchService.EmbeddingResult(text, new float[1024], null)).toList());
        documents = mock(RagDocumentRepository.class);
        when(documents.findById(anyLong())).thenAnswer(invocation -> Optional.of(document(invocation.getArgument(0), true)));
        vectors = spy(new EmbeddingPersistenceService(jdbc, descriptors));
        registry = new SimpleMeterRegistry();
        upgrade = new ChunkingUpgradeService(jdbc, documents, profiles, properties, descriptors, chunking,
                keywords, vectors, embeddings,
                new DataSourceTransactionManager(dataSource), new ChunkingUpgradeMetrics(registry));
    }

    @AfterEach
    void closeWorker() {
        if (upgrade != null) {
            upgrade.close();
        }
        if (registry != null) {
            registry.close();
        }
    }

    private RagDocument document(boolean migrated) {
        return document(documentId, migrated);
    }

    private RagDocument document(long id, boolean migrated) {
        return jdbc.queryForObject("SELECT * FROM rag_documents WHERE id = ?", (row, index) -> {
            RagDocument result = new RagDocument();
            result.setId(row.getLong("id"));
            result.setVersion(row.getLong("version"));
            result.setDocumentRevision(row.getLong("document_revision"));
            result.setContent(row.getString("content"));
            result.setContentHash(row.getString("content_hash"));
            result.setDocumentType(row.getString("document_type"));
            result.setEnabled(row.getBoolean("enabled"));
            if (migrated) {
                result.setDerivationChunkerVersion(row.getString("derivation_chunker_version"));
            }
            return result;
        }, id);
    }

    private void seedVectors() {
        jdbc.update("""
                INSERT INTO rag_document_embedding_state
                    (document_id, embedding_profile_id, content_hash, chunker_version, status, chunk_count, request_generation)
                SELECT document_id, ?, content_hash, chunker_version, 'COMPLETED', chunk_count, 1
                FROM rag_document_local_index_state WHERE document_id = ?
                """, profile.id(), documentId);
        String vector = "[1" + ",0".repeat(1023) + "]";
        jdbc.update("""
                INSERT INTO rag_embeddings (document_id, embedding_profile_id, chunk_text, chunk_index,
                    chunk_start_pos, chunk_end_pos, embedding, embedding_1024)
                SELECT document_id, ?, chunk_text, chunk_index, chunk_start_pos, chunk_end_pos,
                    CAST(? AS vector), CAST(? AS vector) FROM rag_document_chunks WHERE document_id = ?
                """, profile.id(), vector, vector, documentId);
    }

    private int searchable(boolean vector) {
        String sql = vector ? EmbeddingProfileSqlScope.fromAndFreshness(profile.id(), target(), "json-record-v1:single")
                : KeywordIndexSqlScope.fromAndFreshness(profile.id(), target(), "json-record-v1:single");
        return jdbc.queryForObject("SELECT count(*) " + sql, Integer.class);
    }

    private String target() { return descriptors.textDescriptor().chunkerVersion(); }
    private String status() {
        return jdbc.queryForObject("SELECT status FROM rag_chunking_upgrade_tasks WHERE document_id = ?",
                String.class, documentId);
    }
    private List<String> stored(String table) {
        return jdbc.queryForList("SELECT chunk_text FROM " + table + " WHERE document_id = ? ORDER BY chunk_index",
                String.class, documentId);
    }

    private void makeDue(long id) {
        jdbc.update("UPDATE rag_chunking_upgrade_tasks SET available_at = NOW() - INTERVAL '1 second' WHERE document_id = ?", id);
    }

    private void restoreProvider() {
        doAnswer(invocation -> invocation.<List<String>>getArgument(0).stream().map(text ->
                new EmbeddingBatchService.EmbeddingResult(text, new float[1024], null)).toList())
                .when(embeddings).createEmbeddingsBatch(anyList());
    }

    private ChunkingUpgradeService service(JdbcTemplate template) {
        var chunking = new DocumentChunkingService(properties, descriptors);
        return new ChunkingUpgradeService(template, documents, profiles, properties, descriptors, chunking,
                keywords, vectors, embeddings, new DataSourceTransactionManager(jdbc.getDataSource()),
                new ChunkingUpgradeMetrics(registry));
    }

    private long sibling(boolean withVectors) {
        long original = documentId;
        long siblingId = jdbc.queryForObject("""
                INSERT INTO rag_documents (title, content, content_hash, document_type, enabled, version, derivation_chunker_version)
                SELECT title, content, content_hash, document_type, enabled, 0, derivation_chunker_version
                FROM rag_documents WHERE id = ? RETURNING id
                """, Long.class, original);
        RagProperties legacy = new RagProperties();
        legacy.getChunk().setProfile(RagChunkProperties.Profile.LEGACY_V2);
        var legacyDescriptors = new DocumentDerivationDescriptorProvider(legacy);
        new KeywordIndexPersistenceService(jdbc, new DocumentChunkingService(legacy, legacyDescriptors), legacyDescriptors)
                .ensureCurrent(document(siblingId, false));
        if (withVectors) {
            try {
                documentId = siblingId;
                seedVectors();
            } finally {
                documentId = original;
            }
        }
        return siblingId;
    }

    private void seedCooldown(long id, int cycles) {
        jdbc.update("""
                INSERT INTO rag_chunking_upgrade_tasks (document_id, embedding_profile_id, target_version, source_hash,
                    status, attempt_count, recovery_cycles, failure_kind, available_at)
                SELECT id, ?, ?, content_hash, 'COOLDOWN', 3, ?, 'TRANSIENT', NOW() - INTERVAL '1 second'
                FROM rag_documents WHERE id = ?
                """, profile.id(), target(), cycles, id);
    }

    @Test
    void upgradeFromV61PreservesSearchThenAtomicallyRebuildsBothIndexes() {
        seedVectors();
        doAnswer(invocation -> {
            assertTrue(com.springairag.core.admission.DependencyCallContext.background());
            return invocation.<List<String>>getArgument(0).stream().map(text ->
                    new EmbeddingBatchService.EmbeddingResult(text, new float[1024], null)).toList();
        }).when(embeddings).createEmbeddingsBatch(anyList());
        assertEquals("hierarchical-v2:1000:100:100", document(true).getDerivationChunkerVersion());
        assertTrue(searchable(false) > 0);
        assertTrue(searchable(true) > 0);
        long collectionId = jdbc.queryForObject("""
                INSERT INTO rag_collection (name, collection_key) VALUES ('upgrade readiness', 'upgrade-test') RETURNING id
                """, Long.class);
        jdbc.update("UPDATE rag_documents SET collection_id = ? WHERE id = ?", collectionId, documentId);
        var readiness = new EmbeddingJobRepository(jdbc).readiness(collectionId, "upgrade-test", profile,
                target(), "json-record-v1:single");
        assertEquals(1, readiness.enabledDocuments());
        assertEquals(1, readiness.freshDocuments());
        assertEquals(0, readiness.staleOrMissingDocuments());
        long revision = document(true).getDocumentRevision();
        upgrade.scanOnce();
        assertEquals("COMPLETED", status());
        assertFalse(com.springairag.core.admission.DependencyCallContext.background());
        assertNull(document(true).getDerivationChunkerVersion());
        assertEquals(revision, document(true).getDocumentRevision());
        assertEquals(content, document(true).getContent());
        assertEquals(stored("rag_document_chunks"), stored("rag_embeddings"));
        assertTrue(searchable(false) > 0);
        assertTrue(searchable(true) > 0);
        upgrade.scanOnce();
        verify(embeddings, times(1)).createEmbeddingsBatch(anyList());
    }

    @Test
    void failedProviderPreservesBothIndexesAndRetriesAtMostThreeTimesBeforeCooldown() {
        seedVectors();
        var originalChunks = stored("rag_document_chunks");
        var originalVectors = stored("rag_embeddings");
        when(embeddings.createEmbeddingsBatch(anyList())).thenThrow(new TransientAiException("private provider error"));
        for (int attempt = 0; attempt < 3; attempt++) {
            upgrade.scanOnce();
            if (attempt < 2) {
                makeDue(documentId);
            }
        }
        upgrade.scanOnce();
        assertEquals("COOLDOWN", status());
        assertEquals(originalChunks, stored("rag_document_chunks"));
        assertEquals(originalVectors, stored("rag_embeddings"));
        assertTrue(searchable(true) > 0);
        assertEquals("CHUNK_UPGRADE_TRANSIENT", jdbc.queryForObject(
                "SELECT last_error_code FROM rag_chunking_upgrade_tasks", String.class));
        verify(embeddings, times(3)).createEmbeddingsBatch(anyList());
    }

    @Test
    void keywordOnlyAndNotRequestedNeverStartProviderCalls() {
        upgrade.scanOnce();
        assertEquals("COMPLETED", status());
        assertTrue(searchable(false) > 0);
        verify(embeddings, never()).createEmbeddingsBatch(anyList());
        jdbc.update("UPDATE rag_documents SET derivation_chunker_version = 'hierarchical-v2:1000:100:100'");
        jdbc.update("DELETE FROM rag_chunking_upgrade_tasks");
        jdbc.update("DELETE FROM rag_document_chunks");
        jdbc.update("UPDATE rag_document_local_index_state SET local_index_status = 'NOT_REQUESTED', chunk_count = 0");
        upgrade.scanOnce();
        upgrade.scanOnce();
        assertNull(document(true).getDerivationChunkerVersion());
        assertTrue(stored("rag_document_chunks").isEmpty());
        verify(embeddings, never()).createEmbeddingsBatch(anyList());
    }

    @Test
    void activeJobsAndDisabledDocumentsAreNotMigrated() {
        jdbc.update("UPDATE rag_documents SET enabled = false WHERE id = ?", documentId);
        upgrade.scanOnce();
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM rag_chunking_upgrade_tasks", Integer.class));
        jdbc.update("UPDATE rag_documents SET enabled = true WHERE id = ?", documentId);
        jdbc.update("""
                INSERT INTO rag_embedding_jobs (id, batch_id, document_id, embedding_profile_id,
                    content_hash, document_version, request_generation, document_kind, chunker_version)
                VALUES (?, ?, ?, ?, ?, 0, 1, 'TEXT', 'hierarchical-v2:1000:100:100')
                """, UUID.randomUUID(), UUID.randomUUID(), documentId, profile.id(), document(true).getContentHash());
        upgrade.scanOnce();
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM rag_chunking_upgrade_tasks", Integer.class));
        verify(embeddings, never()).createEmbeddingsBatch(anyList());
    }

    @Test
    void concurrentContentMutationRejectsCommitWithoutReplacingOldDerivatives() {
        seedVectors();
        var before = stored("rag_embeddings");
        when(embeddings.createEmbeddingsBatch(anyList())).thenAnswer(invocation -> {
            jdbc.update("UPDATE rag_documents SET version = version + 1, content = 'new content', content_hash = ? WHERE id = ?",
                    DigestUtils.sha256("new content"), documentId);
            return invocation.<List<String>>getArgument(0).stream().map(text ->
                    new EmbeddingBatchService.EmbeddingResult(text, new float[1024], null)).toList();
        });
        upgrade.scanOnce();
        assertEquals("RETRY", status());
        assertEquals("new content", document(true).getContent());
        assertEquals(before, stored("rag_embeddings"));
        assertNotNull(document(true).getDerivationChunkerVersion());
    }

    @Test
    void invalidVectorsCannotReplaceEitherIndex() {
        seedVectors();
        var before = stored("rag_document_chunks");
        when(embeddings.createEmbeddingsBatch(anyList())).thenAnswer(invocation ->
                invocation.<List<String>>getArgument(0).stream().map(text ->
                        new EmbeddingBatchService.EmbeddingResult(text, new float[3], null)).toList());
        upgrade.scanOnce();
        assertEquals("FAILED", status());
        assertEquals(before, stored("rag_document_chunks"));
        assertTrue(searchable(true) > 0);
        makeDue(documentId);
        upgrade.scanOnce();
        verify(embeddings, times(1)).createEmbeddingsBatch(anyList());
    }

    @Test
    void explicitRollbackIsAlsoAutomaticAndKeepsRealVersions() {
        seedVectors();
        upgrade.scanOnce();
        properties.getChunk().setProfile(RagChunkProperties.Profile.LEGACY_V2);
        upgrade.scanOnce();
        assertEquals("COMPLETED", status());
        assertNull(document(true).getDerivationChunkerVersion());
        assertEquals("hierarchical-v2:1000:100:100", jdbc.queryForObject(
                "SELECT chunker_version FROM rag_document_embedding_state WHERE document_id = ?",
                String.class, documentId));
        assertTrue(searchable(true) > 0);
        verify(embeddings, times(2)).createEmbeddingsBatch(anyList());
    }

    @Test
    void leasePreventsDuplicateProviderCallsAcrossConcurrentScans() throws Exception {
        seedVectors();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(embeddings.createEmbeddingsBatch(anyList())).thenAnswer(invocation -> {
            entered.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS));
            return invocation.<List<String>>getArgument(0).stream().map(text ->
                    new EmbeddingBatchService.EmbeddingResult(text, new float[1024], null)).toList();
        });
        try (var executor = Executors.newSingleThreadExecutor()) {
            var first = executor.submit(upgrade::scanOnce);
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            try {
                upgrade.scanOnce();
                verify(embeddings, times(1)).createEmbeddingsBatch(anyList());
            } finally {
                release.countDown();
            }
            first.get(10, TimeUnit.SECONDS);
        }
        assertEquals("COMPLETED", status());
    }

    @Test
    void inactiveProfileIsUpgradedOnlyAfterActivationEvenWhenKeywordsAreAlreadyCurrent() {
        seedVectors();
        long otherProfileId = jdbc.queryForObject("""
                INSERT INTO rag_embedding_profiles (profile_key, provider, model_name, model_revision,
                    dimensions, distance_metric, normalization)
                VALUES ('inactive-test', 'test', 'test-inactive', 'v1', 1024, 'COSINE', 'PROVIDER_DEFAULT') RETURNING id
                """, Long.class);
        jdbc.update("""
                INSERT INTO rag_document_embedding_state (document_id, embedding_profile_id, content_hash,
                    chunker_version, status, chunk_count, request_generation)
                SELECT document_id, ?, content_hash, chunker_version, status, chunk_count, request_generation
                FROM rag_document_embedding_state WHERE embedding_profile_id = ?
                """, otherProfileId, profile.id());
        jdbc.update("""
                INSERT INTO rag_embeddings (document_id, embedding_profile_id, chunk_text, chunk_index,
                    chunk_start_pos, chunk_end_pos, embedding, embedding_1024)
                SELECT document_id, ?, chunk_text, chunk_index, chunk_start_pos, chunk_end_pos, embedding, embedding_1024
                FROM rag_embeddings WHERE embedding_profile_id = ?
                """, otherProfileId, profile.id());
        upgrade.scanOnce();
        assertEquals("hierarchical-v2:1000:100:100", jdbc.queryForObject(
                "SELECT chunker_version FROM rag_document_embedding_state WHERE embedding_profile_id = ?",
                String.class, otherProfileId));
        verify(embeddings, times(1)).createEmbeddingsBatch(anyList());
        when(profiles.getActiveProfile()).thenReturn(new EmbeddingProfile(otherProfileId, "inactive-test", "test",
                "test-inactive", "v1", 1024, "COSINE", "PROVIDER_DEFAULT", true));
        upgrade.scanOnce();
        assertNull(document(true).getDerivationChunkerVersion());
        assertEquals(target(), jdbc.queryForObject(
                "SELECT chunker_version FROM rag_document_embedding_state WHERE embedding_profile_id = ?",
                String.class, otherProfileId));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM rag_chunking_upgrade_tasks WHERE status = 'COMPLETED'",
                Integer.class));
        verify(embeddings, times(2)).createEmbeddingsBatch(anyList());
    }

    @Test
    void expiredLeaseResumesAfterRestartWithoutRepeatingCompletedWork() {
        seedVectors();
        jdbc.update("""
                INSERT INTO rag_chunking_upgrade_tasks (document_id, embedding_profile_id, target_version,
                    source_hash, status, attempt_count, lease_owner, lease_expires_at)
                VALUES (?, ?, ?, ?, 'RUNNING', 1, 'old-instance', NOW() - INTERVAL '1 second')
                """, documentId, profile.id(), target(), document(true).getContentHash());
        upgrade.scanOnce();
        assertEquals("COMPLETED", status());
        assertEquals(2, jdbc.queryForObject("SELECT attempt_count FROM rag_chunking_upgrade_tasks", Integer.class));
        upgrade.scanOnce();
        verify(embeddings, times(1)).createEmbeddingsBatch(anyList());
    }

    @Test
    void exhaustedExpiredLeaseCoolsDownWithoutImmediateProviderCalls() {
        seedVectors();
        jdbc.update("""
                INSERT INTO rag_chunking_upgrade_tasks (document_id, embedding_profile_id, target_version,
                    source_hash, status, attempt_count, lease_owner, lease_expires_at)
                VALUES (?, ?, ?, ?, 'RUNNING', 3, 'old-instance', NOW() - INTERVAL '1 second')
                """, documentId, profile.id(), target(), document(true).getContentHash());
        upgrade.scanOnce();
        assertEquals("COOLDOWN", status());
        assertTrue(searchable(true) > 0);
        verify(embeddings, never()).createEmbeddingsBatch(anyList());
        makeDue(documentId);
        upgrade.scanOnce();
        assertEquals("COMPLETED", status());
        verify(embeddings, times(1)).createEmbeddingsBatch(anyList());
    }

    @Test
    void failureAfterKeywordReplacementRollsBackBothIndexesAndServingIdentity() {
        seedVectors();
        var beforeKeywords = stored("rag_document_chunks");
        var beforeVectors = stored("rag_embeddings");
        long version = document(true).getVersion();
        doThrow(new IllegalStateException("commit failure")).when(vectors)
                .replace(anyLong(), anyLong(), anyString(), eq(profile), anyList(), anyList());
        upgrade.scanOnce();
        assertEquals("COOLDOWN", status());
        assertEquals(version, document(true).getVersion());
        assertNotNull(document(true).getDerivationChunkerVersion());
        assertEquals(beforeKeywords, stored("rag_document_chunks"));
        assertEquals(beforeVectors, stored("rag_embeddings"));
        assertTrue(searchable(false) > 0);
        assertTrue(searchable(true) > 0);
    }

    @Test
    void permanentBudgetFailureRecoversWhenTargetChanges() {
        seedVectors();
        properties.getChunk().setMaxInputChars(100);
        upgrade.scanOnce();
        assertEquals("FAILED", status());
        assertTrue(searchable(true) > 0);
        verify(embeddings, never()).createEmbeddingsBatch(anyList());
        properties.getChunk().setMaxInputChars(1000000);
        upgrade.scanOnce();
        assertEquals("COMPLETED", status());
        assertNull(document(true).getDerivationChunkerVersion());
        verify(embeddings, times(1)).createEmbeddingsBatch(anyList());
    }

    @Test
    void jsonRecordsKeepSingleChunkIdentityAndNeverEnterUpgradeQueue() {
        jdbc.update("UPDATE rag_documents SET document_type = 'json-record', derivation_chunker_version = NULL");
        upgrade.registerServingVersions();
        upgrade.scanOnce();
        assertNull(document(true).getDerivationChunkerVersion());
        assertEquals("json-record-v1:single", descriptors.describe(document(true)).chunkerVersion());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM rag_chunking_upgrade_tasks", Integer.class));
        verify(embeddings, never()).createEmbeddingsBatch(anyList());
    }

    @Test
    void v62PinsActiveLegacyJobWithoutIndexesAndKeepsItsSnapshotVersion() {
        flyway(null).clean();
        flyway("61").migrate();
        long profileId = jdbc.queryForObject("""
                INSERT INTO rag_embedding_profiles (profile_key, provider, model_name, model_revision,
                    dimensions, distance_metric, normalization)
                VALUES ('job-only', 'test', 'test', 'v1', 1024, 'COSINE', 'PROVIDER_DEFAULT') RETURNING id
                """, Long.class);
        long jobDocumentId = jdbc.queryForObject("""
                INSERT INTO rag_documents (title, content, content_hash, document_type, enabled, version)
                VALUES ('job fixture', ?, ?, 'text', true, 7) RETURNING id
                """, Long.class, content, DigestUtils.sha256(content));
        jdbc.update("""
                INSERT INTO rag_embedding_jobs (id, batch_id, document_id, embedding_profile_id,
                    content_hash, document_version, request_generation, document_kind, chunker_version)
                VALUES (?, ?, ?, ?, ?, 7, 1, 'TEXT', 'hierarchical-v2:1000:100:100')
                """, UUID.randomUUID(), UUID.randomUUID(), jobDocumentId, profileId, DigestUtils.sha256(content));
        flyway(null).migrate();
        assertEquals("hierarchical-v2:1000:100:100", jdbc.queryForObject(
                "SELECT derivation_chunker_version FROM rag_documents WHERE id = ?", String.class, jobDocumentId));
        assertEquals(7, jdbc.queryForObject("SELECT version FROM rag_documents WHERE id = ?", Long.class, jobDocumentId));
        assertEquals(7, jdbc.queryForObject("SELECT document_version FROM rag_embedding_jobs", Long.class));
    }

    @Test
    void temporaryOutageRecoversWithUnchangedSourceAfterRestart() {
        seedVectors();
        var original = document(true);
        var before = stored("rag_embeddings");
        when(embeddings.createEmbeddingsBatch(anyList())).thenThrow(new TransientAiException("private-provider-fixture"));
        for (int attempt = 0; attempt < 3; attempt++) {
            upgrade.scanOnce();
            if (attempt < 2) {
                makeDue(documentId);
            }
        }
        assertEquals("COOLDOWN", status());
        assertEquals(before, stored("rag_embeddings"));
        assertTrue(jdbc.queryForObject("SELECT available_at >= NOW() + INTERVAL '59 minutes' FROM rag_chunking_upgrade_tasks", Boolean.class));
        upgrade.close();
        upgrade = service(jdbc);
        restoreProvider();
        upgrade.scanOnce();
        verify(embeddings, times(3)).createEmbeddingsBatch(anyList());
        makeDue(documentId);
        upgrade.scanOnce();
        assertEquals("COMPLETED", status());
        assertEquals(original.getContentHash(), document(true).getContentHash());
        assertEquals(original.getContent(), document(true).getContent());
        assertEquals(original.getDocumentRevision(), document(true).getDocumentRevision());
        assertEquals(1, jdbc.queryForObject("SELECT recovery_cycles FROM rag_chunking_upgrade_tasks", Integer.class));
        assertNull(document(true).getDerivationChunkerVersion());
        assertTrue(searchable(true) > 0);
        assertTrue(searchable(false) > 0);
        verify(embeddings, times(4)).createEmbeddingsBatch(anyList());
    }

    @Test
    void classifiedFailedResultsPreserveIndexesAndSafeCodes() {
        seedVectors();
        var before = stored("rag_embeddings");
        when(embeddings.createEmbeddingsBatch(anyList())).thenAnswer(invocation ->
                invocation.<List<String>>getArgument(0).stream().map(text ->
                        new EmbeddingBatchService.EmbeddingResult(text, null, "private-provider-fixture",
                                EmbeddingFailureClassifier.Kind.TRANSIENT)).toList());
        upgrade.scanOnce();
        assertEquals("RETRY", status());
        assertEquals("CHUNK_UPGRADE_PROVIDER_TRANSIENT", jdbc.queryForObject(
                "SELECT last_error_code FROM rag_chunking_upgrade_tasks", String.class));
        assertFalse(jdbc.queryForObject("SELECT row_to_json(task)::text FROM rag_chunking_upgrade_tasks task", String.class)
                .contains("private-provider-fixture"));
        assertEquals(before, stored("rag_embeddings"));
        makeDue(documentId);
        restoreProvider();
        upgrade.scanOnce();
        assertEquals("COMPLETED", status());
        verify(embeddings, times(2)).createEmbeddingsBatch(anyList());
    }

    @ParameterizedTest
    @EnumSource(value = EmbeddingFailureClassifier.Kind.class, names = {"UNKNOWN", "REJECTED"})
    void opaqueAndRejectedProviderFailuresRecoverOnlyAfterLongCooldown(EmbeddingFailureClassifier.Kind kind) {
        seedVectors();
        when(embeddings.createEmbeddingsBatch(anyList())).thenAnswer(invocation ->
                invocation.<List<String>>getArgument(0).stream().map(text ->
                        new EmbeddingBatchService.EmbeddingResult(text, null, "private-provider-fixture", kind)).toList());
        upgrade.scanOnce();
        assertEquals("COOLDOWN", status());
        assertEquals(kind.name(), jdbc.queryForObject("SELECT failure_kind FROM rag_chunking_upgrade_tasks", String.class));
        assertTrue(jdbc.queryForObject("SELECT available_at >= NOW() + INTERVAL '23 hours' FROM rag_chunking_upgrade_tasks", Boolean.class));
        upgrade.scanOnce();
        verify(embeddings, times(1)).createEmbeddingsBatch(anyList());
        restoreProvider();
        makeDue(documentId);
        upgrade.scanOnce();
        assertEquals("COMPLETED", status());
        verify(embeddings, times(2)).createEmbeddingsBatch(anyList());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void missingNonFiniteAndPartialVectorsArePermanent(int variant) {
        seedVectors();
        var before = stored("rag_embeddings");
        when(embeddings.createEmbeddingsBatch(anyList())).thenAnswer(invocation -> {
            if (variant == 2) {
                return List.of();
            }
            return invocation.<List<String>>getArgument(0).stream().map(text -> {
                float[] values = variant == 0 ? null : new float[1024];
                if (values != null) {
                    values[0] = Float.NaN;
                }
                return new EmbeddingBatchService.EmbeddingResult(text, values, null);
            }).toList();
        });
        upgrade.scanOnce();
        assertEquals("FAILED", status());
        assertEquals("PERMANENT", jdbc.queryForObject("SELECT failure_kind FROM rag_chunking_upgrade_tasks", String.class));
        makeDue(documentId);
        upgrade.scanOnce();
        assertEquals(before, stored("rag_embeddings"));
        verify(embeddings, times(1)).createEmbeddingsBatch(anyList());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2, 10})
    void recurringOutagesBackOffWithinTheConfiguredMaximum(int cycles) {
        seedVectors();
        seedCooldown(documentId, cycles);
        when(embeddings.createEmbeddingsBatch(anyList())).thenThrow(new TransientAiException("fixture"));
        for (int attempt = 0; attempt < 3; attempt++) {
            makeDue(documentId);
            upgrade.scanOnce();
        }
        assertEquals("COOLDOWN", status());
        long expectedSeconds = Math.min(24, 1L << (cycles + 1)) * 3600;
        double delay = jdbc.queryForObject("SELECT EXTRACT(EPOCH FROM available_at - NOW()) FROM rag_chunking_upgrade_tasks", Double.class);
        assertTrue(delay > expectedSeconds - 5 && delay <= expectedSeconds);
        verify(embeddings, times(3)).createEmbeddingsBatch(anyList());
    }

    @Test
    void concurrentInstancesShareRetryQuotaAndDeferredWorkRecoversNextHour() throws Exception {
        seedVectors();
        long otherId = sibling(true);
        seedCooldown(documentId, 0);
        seedCooldown(otherId, 0);
        properties.getChunk().getUpgradeRecovery().setRetryDocumentsPerHour(1);
        var otherInstance = service(jdbc);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(upgrade::scanOnce);
            var second = executor.submit(otherInstance::scanOnce);
            first.get(20, TimeUnit.SECONDS);
            second.get(20, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
            otherInstance.close();
        }
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM rag_chunking_upgrade_tasks WHERE status = 'COMPLETED'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM rag_chunking_upgrade_tasks WHERE status = 'COOLDOWN'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT reserved_count FROM rag_chunking_upgrade_retry_buckets", Integer.class));
        assertTrue(jdbc.queryForObject("""
                SELECT available_at = (date_trunc('hour', NOW() AT TIME ZONE 'UTC') + INTERVAL '1 hour') AT TIME ZONE 'UTC'
                FROM rag_chunking_upgrade_tasks WHERE status = 'COOLDOWN'
                """, Boolean.class));
        verify(embeddings, times(1)).createEmbeddingsBatch(anyList());
        long deferred = jdbc.queryForObject("SELECT document_id FROM rag_chunking_upgrade_tasks WHERE status = 'COOLDOWN'", Long.class);
        jdbc.update("UPDATE rag_chunking_upgrade_retry_buckets SET bucket_start = bucket_start - INTERVAL '1 hour'");
        makeDue(deferred);
        upgrade.scanOnce();
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM rag_chunking_upgrade_tasks WHERE status = 'COMPLETED'", Integer.class));
        verify(embeddings, times(2)).createEmbeddingsBatch(anyList());
    }

    @Test
    void retryQuotaDatabaseFailureNeverStartsProviderCalls() {
        seedVectors();
        seedCooldown(documentId, 0);
        var before = stored("rag_embeddings");
        JdbcTemplate unavailableQuota = new JdbcTemplate(jdbc.getDataSource()) {
            @Override
            public <Result> List<Result> queryForList(String sql, Class<Result> type, Object... args) {
                if (sql.contains("INSERT INTO rag_chunking_upgrade_retry_buckets")) {
                    throw new DataAccessResourceFailureException("private-quota-fixture");
                }
                return super.queryForList(sql, type, args);
            }
        };
        var isolated = service(unavailableQuota);
        try {
            isolated.scanOnce();
        } finally {
            isolated.close();
        }
        assertEquals("COOLDOWN", status());
        assertEquals(before, stored("rag_embeddings"));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM rag_chunking_upgrade_retry_buckets", Integer.class));
        verify(embeddings, never()).createEmbeddingsBatch(anyList());
    }

    @Test
    void lowIdFailureDoesNotStarveLaterKeywordOnlyDocuments() {
        seedVectors();
        long second = sibling(false);
        long third = sibling(false);
        when(embeddings.createEmbeddingsBatch(anyList())).thenThrow(new TransientAiException("fixture"));
        upgrade.scanOnce();
        upgrade.scanOnce();
        assertEquals("RETRY", status());
        assertEquals("COMPLETED", jdbc.queryForObject("SELECT status FROM rag_chunking_upgrade_tasks WHERE document_id = ?", String.class, second));
        assertEquals("COMPLETED", jdbc.queryForObject("SELECT status FROM rag_chunking_upgrade_tasks WHERE document_id = ?", String.class, third));
        verify(embeddings, times(1)).createEmbeddingsBatch(anyList());
    }

    @Test
    void v62MigrationRearmsOnlyRecoverableHistoryWithoutChangingPhysicalData() {
        closeWorker();
        setupAtVersion("62");
        seedVectors();
        long leaseFailed = sibling(true);
        long permanent = sibling(true);
        long retry = sibling(true);
        long completed = sibling(true);
        long running = sibling(true);
        for (long id : new long[]{documentId, leaseFailed, permanent, retry, completed, running}) {
            String historyStatus = id == retry ? "RETRY" : id == completed ? "COMPLETED" : id == running ? "RUNNING" : "FAILED";
            String code = id == leaseFailed ? "CHUNK_UPGRADE_LEASE_EXHAUSTED"
                    : id == permanent ? "CHUNK_INPUT_TOO_LARGE" : "CHUNK_UPGRADE_FAILED";
            jdbc.update("""
                    INSERT INTO rag_chunking_upgrade_tasks (document_id, embedding_profile_id, target_version, source_hash,
                        status, attempt_count, last_error_code, lease_owner, lease_expires_at)
                    SELECT id, ?, ?, content_hash, ?, 3, ?, ?, NOW() + INTERVAL '1 hour' FROM rag_documents WHERE id = ?
                    """, profile.id(), target(), historyStatus, code, id == running ? "active-instance" : null, id);
        }
        var oldKeywords = jdbc.queryForList("SELECT chunk_text FROM rag_document_chunks ORDER BY id", String.class);
        var oldVectors = jdbc.queryForList("SELECT embedding_1024::text FROM rag_embeddings ORDER BY id", String.class);
        var oldIdentity = document(true).getDerivationChunkerVersion();
        flyway(null).migrate();
        MigrationVersions.assertLatestApplied(jdbc);
        assertEquals(oldKeywords, jdbc.queryForList("SELECT chunk_text FROM rag_document_chunks ORDER BY id", String.class));
        assertEquals(oldVectors, jdbc.queryForList("SELECT embedding_1024::text FROM rag_embeddings ORDER BY id", String.class));
        assertEquals(oldIdentity, document(true).getDerivationChunkerVersion());
        assertEquals("COOLDOWN", status());
        assertEquals("UNKNOWN", jdbc.queryForObject("SELECT failure_kind FROM rag_chunking_upgrade_tasks WHERE document_id = ?", String.class, documentId));
        assertEquals("TRANSIENT", jdbc.queryForObject("SELECT failure_kind FROM rag_chunking_upgrade_tasks WHERE document_id = ?", String.class, leaseFailed));
        assertEquals("FAILED", jdbc.queryForObject("SELECT status FROM rag_chunking_upgrade_tasks WHERE document_id = ?", String.class, permanent));
        assertEquals("PERMANENT", jdbc.queryForObject("SELECT failure_kind FROM rag_chunking_upgrade_tasks WHERE document_id = ?", String.class, permanent));
        assertEquals("COOLDOWN", jdbc.queryForObject("SELECT status FROM rag_chunking_upgrade_tasks WHERE document_id = ?", String.class, retry));
        assertEquals("COMPLETED", jdbc.queryForObject("SELECT status FROM rag_chunking_upgrade_tasks WHERE document_id = ?", String.class, completed));
        assertEquals("active-instance", jdbc.queryForObject("SELECT lease_owner FROM rag_chunking_upgrade_tasks WHERE document_id = ?", String.class, running));
        upgrade.scanOnce();
        verify(embeddings, never()).createEmbeddingsBatch(anyList());
        makeDue(documentId);
        upgrade.scanOnce();
        assertEquals("COMPLETED", status());
        verify(embeddings, times(1)).createEmbeddingsBatch(anyList());
    }

    @Test
    void cleanupIsBoundedAndNeverRemovesCurrentQuotaOrTasks() {
        for (int index = 0; index < 105; index++) {
            jdbc.update("""
                    INSERT INTO rag_chunking_upgrade_retry_buckets (embedding_profile_id, bucket_start, reserved_count)
                    VALUES (?, NOW() - INTERVAL '3 days' - CAST(? AS INTEGER) * INTERVAL '1 hour', 1)
                    """, profile.id(), index);
        }
        jdbc.update("""
                INSERT INTO rag_chunking_upgrade_retry_buckets (embedding_profile_id, bucket_start, reserved_count)
                VALUES (?, date_trunc('hour', NOW() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC', 1)
                """, profile.id());
        upgrade.scanOnce();
        assertEquals(6, jdbc.queryForObject("SELECT count(*) FROM rag_chunking_upgrade_retry_buckets", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM rag_chunking_upgrade_retry_buckets WHERE bucket_start > NOW() - INTERVAL '1 hour'", Integer.class));
        assertEquals("COMPLETED", status());
        assertTrue(Double.isFinite(registry.get("rag.chunking.upgrade.scan.age.seconds").gauge().value()));
    }

    @Test
    void privateServingIdentityCannotBeWrittenByJsonClients() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        RagDocument input = mapper.readValue("{\"derivationChunkerVersion\":\"hierarchical-v2:1000:100:100\"}", RagDocument.class);
        assertNull(input.getDerivationChunkerVersion());
        assertFalse(mapper.writeValueAsString(document(true)).contains("derivationChunkerVersion"));
    }
}
