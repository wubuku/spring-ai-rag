package com.springairag.core.integration;

import com.springairag.api.dto.RetrievalResult;
import com.springairag.core.config.EmbeddingProfile;
import com.springairag.core.config.EmbeddingProfileIndexManager;
import com.springairag.core.config.EmbeddingProfileRegistry;
import com.springairag.core.config.RagProperties;
import com.springairag.core.repository.RagDocumentRepository;
import com.springairag.core.repository.RagEmbeddingRepository;
import com.springairag.core.retrieval.EmbeddingBatchService;
import com.springairag.core.retrieval.HybridRetrieverService;
import com.springairag.core.retrieval.fulltext.PgEnglishFtsProvider;
import com.springairag.core.service.EmbeddingPersistenceService;
import com.springairag.core.service.LegacyEmbeddingMigrationService;
import com.springairag.documents.chunk.TextChunk;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.persistence.EntityManagerFactory;
import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EmbeddingProfilePostgresIntegrationTest {

    private DataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void resetDatabase() {
        String jdbcUrl = System.getProperty("rag.it.jdbc-url");
        assumeTrue(jdbcUrl != null && !jdbcUrl.isBlank(),
                "Set -Drag.it.jdbc-url to run PostgreSQL integration tests");
        PGSimpleDataSource pgDataSource = new PGSimpleDataSource();
        pgDataSource.setUrl(jdbcUrl);
        pgDataSource.setUser(System.getProperty("rag.it.username", "postgres"));
        pgDataSource.setPassword(System.getProperty("rag.it.password", "postgres"));
        dataSource = pgDataSource;
        jdbcTemplate = new JdbcTemplate(dataSource);
        transactionTemplate = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource));

        Flyway flyway = flyway(null);
        flyway.clean();
        flyway.migrate();
    }

    @Test
    void migrationsCreateFixedVectorSchemaAndProfileIndex() {
        assertEquals("vector", jdbcTemplate.queryForObject(
                "SELECT udt_name FROM information_schema.columns "
                        + "WHERE table_name = 'rag_embeddings' "
                        + "AND column_name = 'embedding_1024'",
                String.class));

        EmbeddingProfile profile = registry().initialize();
        new EmbeddingProfileIndexManager(jdbcTemplate).ensureIndex(profile);

        Boolean valid = jdbcTemplate.queryForObject(
                "SELECT i.indisvalid FROM pg_class c "
                        + "JOIN pg_index i ON i.indexrelid = c.oid "
                        + "WHERE c.relname = ?",
                Boolean.class,
                "idx_rag_emb_p_" + profile.id() + "_1024_hnsw");
        assertEquals(Boolean.TRUE, valid);
    }

    @Test
    void nonEmptyLegacyVectorStoreBlocksCleanupWithoutDataLoss() {
        Flyway current = flyway(null);
        current.clean();
        flyway(MigrationVersion.fromVersion("25")).migrate();
        jdbcTemplate.execute("CREATE TABLE rag_vector_store (id BIGINT PRIMARY KEY)");
        jdbcTemplate.update("INSERT INTO rag_vector_store (id) VALUES (1)");

        assertThrows(Exception.class, () -> flyway(null).migrate());

        assertEquals(1L, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rag_vector_store", Long.class));
    }

    @Test
    void replacementRollsBackWhenAnyVectorCannotBeInserted() {
        EmbeddingProfile profile = registry().initialize();
        long documentId = insertDocument("atomic", "atomic-content", "hash-atomic");
        EmbeddingPersistenceService persistence =
                new EmbeddingPersistenceService(jdbcTemplate);

        transactionTemplate.executeWithoutResult(status -> persistence.replace(
                documentId,
                0L,
                "hash-atomic",
                profile,
                "chunker-v1",
                List.of(new TextChunk("old chunk", 0, 9)),
                List.of(result("old chunk", vector(1024, 1.0f)))));

        assertThrows(Exception.class, () -> transactionTemplate.executeWithoutResult(
                status -> persistence.replace(
                        documentId,
                        1L,
                        "hash-atomic",
                        profile,
                        "chunker-v1",
                        List.of(
                                new TextChunk("new chunk 1", 0, 11),
                                new TextChunk("new chunk 2", 11, 22)),
                        List.of(
                                result("new chunk 1", vector(1024, 0.5f)),
                                result("new chunk 2", vector(768, 0.5f))))));

        assertEquals(1L, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rag_embeddings "
                        + "WHERE document_id = ? AND embedding_profile_id = ?",
                Long.class,
                documentId,
                profile.id()));
        assertEquals("old chunk", jdbcTemplate.queryForObject(
                "SELECT chunk_text FROM rag_embeddings "
                        + "WHERE document_id = ? AND embedding_profile_id = ?",
                String.class,
                documentId,
                profile.id()));
        assertEquals(1L, jdbcTemplate.queryForObject(
                "SELECT version FROM rag_documents WHERE id = ?",
                Long.class,
                documentId));
    }

    @Test
    void vectorAndEnglishFulltextSearchUseTheRequestedProfileAndFreshState() {
        EmbeddingProfile profileA = registry().initialize();
        EmbeddingProfile profileB = insertProfile(
                "second-1024-profile", "other-provider", "other-model");
        EmbeddingPersistenceService persistence =
                new EmbeddingPersistenceService(jdbcTemplate);
        long documentA = insertDocument("A", "shared searchable content", "hash-a");
        long documentB = insertDocument("B", "shared searchable content", "hash-b");

        transactionTemplate.executeWithoutResult(status -> persistence.replace(
                documentA, 0L, "hash-a", profileA, "chunker-v1",
                List.of(new TextChunk("shared searchable content", 0, 25)),
                List.of(result("shared searchable content", vector(1024, 1.0f)))));
        transactionTemplate.executeWithoutResult(status -> persistence.replace(
                documentB, 0L, "hash-b", profileB, "chunker-v1",
                List.of(new TextChunk("shared searchable content", 0, 25)),
                List.of(result("shared searchable content", vector(1024, 1.0f)))));

        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        when(embeddingModel.embed("shared")).thenReturn(vector(1024, 1.0f));
        RagProperties properties = new RagProperties();
        properties.getRetrieval().setFulltextEnabled(false);
        HybridRetrieverService retriever = new HybridRetrieverService(
                embeddingModel,
                () -> profileA,
                jdbcTemplate,
                properties,
                null,
                Runnable::run);

        List<RetrievalResult> vectorResults =
                retriever.search("shared", null, null, 10);
        assertEquals(1, vectorResults.size());
        assertEquals(String.valueOf(documentA),
                vectorResults.getFirst().getDocumentId());

        PgEnglishFtsProvider english = new PgEnglishFtsProvider(jdbcTemplate);
        assertTrue(english.isAvailable());
        List<RetrievalResult> fulltextResults = english.search(
                "searchable", null, null, 10, 0.0, profileA.id());
        assertEquals(1, fulltextResults.size());
        assertEquals(String.valueOf(documentA),
                fulltextResults.getFirst().getDocumentId());

        jdbcTemplate.update(
                "UPDATE rag_documents SET content_hash = 'changed' WHERE id = ?",
                documentA);
        assertTrue(retriever.search("shared", null, null, 10).isEmpty());
        assertTrue(english.search(
                "searchable", null, null, 10, 0.0, profileA.id()).isEmpty());
    }

    @Test
    void legacyAdoptionRequiresConfirmationAndBackfillsProfileState() {
        EmbeddingProfile profile = registry().initialize();
        long documentId = insertDocument(
                "legacy", "legacy content", null);
        jdbcTemplate.update(
                "INSERT INTO rag_embeddings "
                        + "(document_id, chunk_text, chunk_index, embedding) "
                        + "VALUES (?, 'legacy chunk', 0, ?::vector)",
                documentId,
                vectorText(vector(1024, 0.25f)));
        LegacyEmbeddingMigrationService migration = new LegacyEmbeddingMigrationService(
                jdbcTemplate,
                new DataSourceTransactionManager(dataSource),
                registry());

        assertThrows(IllegalStateException.class,
                () -> migration.adoptLegacy(profile.profileKey(), "wrong"));
        assertEquals(1L, migration.countUnassigned());

        assertEquals(1, migration.adoptLegacy(
                profile.profileKey(),
                LegacyEmbeddingMigrationService.ADOPT_CONFIRMATION));
        assertEquals(0L, migration.countUnassigned());
        assertEquals(profile.id(), jdbcTemplate.queryForObject(
                "SELECT embedding_profile_id FROM rag_embeddings "
                        + "WHERE document_id = ?",
                Long.class,
                documentId));
        assertEquals("COMPLETED", jdbcTemplate.queryForObject(
                "SELECT status FROM rag_document_embedding_state "
                        + "WHERE document_id = ? AND embedding_profile_id = ?",
                String.class,
                documentId,
                profile.id()));
        assertNotNull(jdbcTemplate.queryForObject(
                "SELECT content_hash FROM rag_documents WHERE id = ?",
                String.class,
                documentId));
        assertFalse(jdbcTemplate.queryForObject(
                "SELECT embedding_1024 IS NULL FROM rag_embeddings "
                        + "WHERE document_id = ?",
                Boolean.class,
                documentId));
    }

    @Test
    void legacyAdoptionRejectsNonContinuousChunkIndexesWithoutMarkingCompleted() {
        EmbeddingProfile profile = registry().initialize();
        long documentId = insertDocument(
                "legacy-invalid-chunks", "legacy content with invalid chunks", "hash-invalid-chunks");
        jdbcTemplate.update(
                "INSERT INTO rag_embeddings "
                        + "(document_id, chunk_text, chunk_index, embedding) "
                        + "VALUES (?, 'invalid chunk index', 1, ?::vector)",
                documentId,
                vectorText(vector(1024, 0.25f)));
        LegacyEmbeddingMigrationService migration = new LegacyEmbeddingMigrationService(
                jdbcTemplate,
                new DataSourceTransactionManager(dataSource),
                registry());

        assertThrows(IllegalStateException.class,
                () -> migration.adoptLegacy(
                        profile.profileKey(),
                        LegacyEmbeddingMigrationService.ADOPT_CONFIRMATION));
        assertEquals(1L, migration.countUnassigned());
        assertEquals(0L, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rag_document_embedding_state "
                        + "WHERE document_id = ? AND embedding_profile_id = ? "
                        + "AND status = 'COMPLETED'",
                Long.class,
                documentId,
                profile.id()));
        assertEquals(0L, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rag_embeddings "
                        + "WHERE document_id = ? AND embedding_profile_id IS NOT NULL",
                Long.class,
                documentId));
    }

    @Test
    void repositoriesScopeCoverageAndChunkCountsToFreshActiveProfileState() {
        EmbeddingProfile profileA = registry().initialize();
        EmbeddingProfile profileB = insertProfile(
                "coverage-second-profile", "other-provider", "other-model");
        EmbeddingPersistenceService persistence =
                new EmbeddingPersistenceService(jdbcTemplate);
        long documentA = insertDocument("coverage-a", "content a", "hash-a");
        long documentB = insertDocument("coverage-b", "content b", "hash-b");

        transactionTemplate.executeWithoutResult(status -> persistence.replace(
                documentA, 0L, "hash-a", profileA, "chunker-v1",
                List.of(new TextChunk("content a", 0, 9)),
                List.of(result("content a", vector(1024, 1.0f)))));
        transactionTemplate.executeWithoutResult(status -> persistence.replace(
                documentB, 0L, "hash-b", profileB, "chunker-v1",
                List.of(new TextChunk("content b", 0, 9)),
                List.of(result("content b", vector(1024, 1.0f)))));

        try (EntityManagerFactory entityManagerFactory = entityManagerFactory()) {
            JpaRepositoryFactory factory = new JpaRepositoryFactory(
                    SharedEntityManagerCreator.createSharedEntityManager(
                            entityManagerFactory));
            RagDocumentRepository documents =
                    factory.getRepository(RagDocumentRepository.class);
            RagEmbeddingRepository embeddings =
                    factory.getRepository(RagEmbeddingRepository.class);

            assertEquals(1L, documents.countDocumentsWithoutEmbeddings(
                    profileA.id()));
            assertEquals(List.of(documentB),
                    documents.findDocumentsWithoutEmbeddings(profileA.id())
                            .stream().map(document -> document.getId()).toList());
            assertEquals(1L,
                    embeddings.countFreshChunksByDocumentIdAndProfileId(
                            documentA, profileA.id()));
            assertEquals(0L,
                    embeddings.countFreshChunksByDocumentIdAndProfileId(
                            documentA, profileB.id()));

            jdbcTemplate.update(
                    "UPDATE rag_documents SET content_hash = 'changed' WHERE id = ?",
                    documentA);

            assertEquals(2L, documents.countDocumentsWithoutEmbeddings(
                    profileA.id()));
            assertEquals(0L,
                    embeddings.countFreshChunksByDocumentIdAndProfileId(
                            documentA, profileA.id()));
        }
    }

    private EmbeddingProfileRegistry registry() {
        return new EmbeddingProfileRegistry(jdbcTemplate, new RagProperties());
    }

    private EmbeddingProfile insertProfile(
            String key, String provider, String model) {
        Long id = jdbcTemplate.queryForObject(
                "INSERT INTO rag_embedding_profiles "
                        + "(profile_key, provider, model_name, model_revision, dimensions, "
                        + "distance_metric, normalization, enabled) "
                        + "VALUES (?, ?, ?, 'v1', 1024, 'COSINE', 'PROVIDER_DEFAULT', true) "
                        + "RETURNING id",
                Long.class,
                key,
                provider,
                model);
        assertNotNull(id);
        return new EmbeddingProfile(
                id, key, provider, model, "v1", 1024,
                "COSINE", "PROVIDER_DEFAULT", true);
    }

    private long insertDocument(
            String title, String content, String contentHash) {
        Long id = jdbcTemplate.queryForObject(
                "INSERT INTO rag_documents "
                        + "(title, content, content_hash, enabled, processing_status) "
                        + "VALUES (?, ?, ?, true, 'PENDING') RETURNING id",
                Long.class,
                title,
                content,
                contentHash);
        assertNotNull(id);
        return id;
    }

    private EmbeddingBatchService.EmbeddingResult result(
            String text, float[] vector) {
        return new EmbeddingBatchService.EmbeddingResult(text, vector, null);
    }

    private float[] vector(int dimensions, float firstValue) {
        float[] vector = new float[dimensions];
        vector[0] = firstValue;
        return vector;
    }

    private String vectorText(float[] vector) {
        StringBuilder value = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                value.append(',');
            }
            value.append(vector[i]);
        }
        return value.append(']').toString();
    }

    private Flyway flyway(MigrationVersion target) {
        var configuration = Flyway.configure()
                .dataSource(dataSource)
                .cleanDisabled(false)
                .locations("classpath:db/migration");
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    private EntityManagerFactory entityManagerFactory() {
        LocalContainerEntityManagerFactoryBean factory =
                new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(dataSource);
        factory.setPackagesToScan("com.springairag.core.entity");
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setJpaPropertyMap(Map.of(
                "hibernate.hbm2ddl.auto", "none",
                "hibernate.show_sql", "false"));
        factory.afterPropertiesSet();
        EntityManagerFactory entityManagerFactory = factory.getObject();
        assertNotNull(entityManagerFactory);
        return entityManagerFactory;
    }
}
