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
import com.springairag.core.service.DocumentDerivationDescriptorProvider;
import com.springairag.documents.chunk.TextChunk;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

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

/**
 * Real PostgreSQL acceptance tests for the fixed-width embedding profile schema.
 *
 * <p>Run with {@code -Dembedding-profile.it.enabled=true}.
 *
 * <p>This class used to be unreachable in every automated path. It had no
 * {@code .it.enabled} switch, and the only way to make it run was to hand it an
 * external database through {@code -Drag.it.jdbc-url} — a property no script in
 * the repository sets. Seven tests therefore contributed nothing while sitting
 * in a file whose name looked exactly like the twenty-two that are gated.
 *
 * <p>It also had a sharper problem than being unreachable. {@code flyway.clean()}
 * drops every object in the schema, and the only database it could ever be
 * pointed at was one the caller supplied. Ten sibling suites that accept an
 * external JDBC URL all require {@code *_CLEAN_CONFIRM=YES} before doing
 * anything destructive; this one did not. A mistyped property was enough to
 * wipe a database someone cared about.
 */
@EnabledIfSystemProperty(named = "embedding-profile.it.enabled", matches = "true")
class EmbeddingProfilePostgresIntegrationTest {

    private static PostgreSQLContainer<?> postgres;
    private DataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private TransactionTemplate transactionTemplate;

    @BeforeAll
    static void startDatabase() {
        assumeTrue(Boolean.getBoolean("embedding-profile.it.enabled"),
                "Set -Dembedding-profile.it.enabled=true to run PostgreSQL integration tests");
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) {
            postgres.stop();
            postgres = null;
        }
    }

    /**
     * The chunker version the retriever will actually filter on.
     *
     * <p>This suite used to write the literal {@code "chunker-v1"} into
     * {@code rag_document_embedding_state}. The production scope in
     * {@code EmbeddingProfileSqlScope} filters on
     * {@code hierarchical-v2:<size>:<min>:<overlap>} for text, so no row matched
     * and {@link #vectorAndEnglishFulltextSearchUseTheRequestedProfileAndFreshState}
     * came back with an empty result set — a failure that reads like "the
     * retriever lost the document" and is really "the fixture never matched the
     * predicate". The second occurrence of this drift in the repository, after
     * {@code MultiCollectionRetrievalPostgresIntegrationTest} in Batch 801;
     * both were invisible for the same reason, a suite that could not run.
     */
    private static String chunkerVersion() {
        return new DocumentDerivationDescriptorProvider(new RagProperties())
                .textDescriptor()
                .chunkerVersion();
    }

    /**
     * {@code rag_document_chunks} enforces a 64-hex hash and
     * {@code KeywordIndexSqlScope} compares it against
     * {@code rag_documents.content_hash}, so the document, the chunk and the
     * local-index state row all have to carry the same one.
     */
    private static final String HASH_A =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                    + "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String HASH_B =
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
                    + "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String CHANGED_HASH =
            "cccccccccccccccccccccccccccccccc"
                    + "cccccccccccccccccccccccccccccccc";

    @BeforeEach
    void resetDatabase() {
        String externalUrl = System.getProperty("rag.it.jdbc-url");
        if (externalUrl != null && !externalUrl.isBlank()) {
            // The caller named a database, so it might be one they care about.
            // Every other suite in this shape demands an explicit acknowledgement
            // before it drops a schema; this one now does too.
            if (!"YES".equals(System.getenv("EMBEDDING_PROFILE_IT_CLEAN_CONFIRM"))) {
                throw new IllegalStateException(
                        "Set EMBEDDING_PROFILE_IT_CLEAN_CONFIRM=YES only for a disposable"
                                + " database; this suite runs flyway.clean() against"
                                + " -Drag.it.jdbc-url");
            }
            PGSimpleDataSource pgDataSource = new PGSimpleDataSource();
            pgDataSource.setUrl(externalUrl);
            pgDataSource.setUser(System.getProperty("rag.it.username", "postgres"));
            pgDataSource.setPassword(System.getProperty("rag.it.password", "postgres"));
            dataSource = pgDataSource;
        } else {
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
                    DockerImageName.parse(image).asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("spring_ai_rag_embedding_profile_test")
                    .withUsername("postgres")
                    .withPassword("postgres");
            postgres.start();
            PGSimpleDataSource pgDataSource = new PGSimpleDataSource();
            pgDataSource.setUrl(postgres.getJdbcUrl());
            pgDataSource.setUser(postgres.getUsername());
            pgDataSource.setPassword(postgres.getPassword());
            dataSource = pgDataSource;
        }
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
                chunkerVersion(),
                List.of(new TextChunk("old chunk", 0, 9)),
                List.of(result("old chunk", vector(1024, 1.0f)))));

        assertThrows(Exception.class, () -> transactionTemplate.executeWithoutResult(
                status -> persistence.replace(
                        documentId,
                        1L,
                        "hash-atomic",
                        profile,
                        chunkerVersion(),
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
        long documentA = insertDocument("A", "shared searchable content", HASH_A);
        long documentB = insertDocument("B", "shared searchable content", HASH_B);
        jdbcTemplate.update(
                "UPDATE rag_documents "
                        + "SET source = ?, original_filename = ? WHERE id = ?",
                "pdf-import:traceability-test/default.md",
                "source-manual.pdf",
                documentA);

        transactionTemplate.executeWithoutResult(status -> persistence.replace(
                documentA, 0L, HASH_A, profileA, chunkerVersion(),
                List.of(new TextChunk("shared searchable content", 0, 25)),
                List.of(result("shared searchable content", vector(1024, 1.0f)))));
        transactionTemplate.executeWithoutResult(status -> persistence.replace(
                documentB, 0L, HASH_B, profileB, chunkerVersion(),
                List.of(new TextChunk("shared searchable content", 0, 25)),
                List.of(result("shared searchable content", vector(1024, 1.0f)))));

        // V43 split the keyword index away from the embeddings, and this half
        // of the test never followed. It searched `rag_document_chunks` — a
        // table the fixture left empty — and so returned nothing for a reason
        // that had nothing to do with the profile scoping it is meant to check.
        // The hashes are 64-hex because `rag_document_chunks` enforces that
        // shape; `rag_documents` does not, which is why the short hashes this
        // file used elsewhere were accepted and still wrong here.
        indexLocalKeywords(documentA, HASH_A, "shared searchable content");
        indexLocalKeywords(documentB, HASH_B, "shared searchable content");

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
        assertPdfProvenance(vectorResults.getFirst());

        PgEnglishFtsProvider english = new PgEnglishFtsProvider(jdbcTemplate);
        assertTrue(english.isAvailable());
        List<RetrievalResult> fulltextResults = english.search(
                "searchable", null, null, 10, 0.0, profileA.id());
        // Two, not one. V43 deliberately decoupled the keyword index from the
        // embedding profile: `KeywordIndexSqlScope` LEFT JOINs the vector state
        // only to map `rag_embeddings.id` for the excludeIds contract, and its
        // own doc comment says full-text search no longer depends on whether
        // the profile has vectors. The old `assertEquals(1, ...)` encoded the
        // pre-V43 behaviour — that keyword search was profile-scoped — so it
        // was asserting a contract the design had on purpose removed, and it
        // could only be "fixed" by deleting the assertion. Naming the real
        // contract keeps the half of the test that is still true: the vector
        // search above is still profile-scoped and still returns one.
        assertEquals(
                List.of(String.valueOf(documentA), String.valueOf(documentB)),
                fulltextResults.stream()
                        .map(RetrievalResult::getDocumentId)
                        .sorted()
                        .toList());
        assertPdfProvenance(
                fulltextResults.stream()
                        .filter(r -> String.valueOf(documentA).equals(r.getDocumentId()))
                        .findFirst()
                        .orElseThrow());

        // Both sources must agree that a document whose stored hash no longer
        // matches its state rows is stale — but only the vector search loses
        // the document entirely, because it was the only one scoped to
        // profile A. The keyword search still finds B, which is the point of
        // the decoupling: losing a vector does not make a document
        // unsearchable.
        jdbcTemplate.update(
                "UPDATE rag_documents SET content_hash = ? WHERE id = ?",
                CHANGED_HASH,
                documentA);
        assertTrue(retriever.search("shared", null, null, 10).isEmpty());
        assertEquals(
                List.of(String.valueOf(documentB)),
                english.search("searchable", null, null, 10, 0.0, profileA.id())
                        .stream()
                        .map(RetrievalResult::getDocumentId)
                        .toList());
    }

    /** Publishes a document to the local keyword index the way V43 expects. */
    private void indexLocalKeywords(long documentId, String hash, String text) {
        jdbcTemplate.update(
                "INSERT INTO rag_document_chunks ("
                        + "document_id, local_index_generation, content_hash, "
                        + "chunker_version, chunk_text, chunk_index, "
                        + "chunk_start_pos, chunk_end_pos) "
                        + "VALUES (?, 1, ?, ?, ?, 0, 0, ?)",
                documentId,
                hash,
                chunkerVersion(),
                text,
                text.length());
        jdbcTemplate.update(
                "INSERT INTO rag_document_local_index_state ("
                        + "document_id, local_index_status, content_hash, "
                        + "chunker_version, local_index_generation, chunk_count) "
                        + "VALUES (?, 'READY', ?, ?, 1, 1)",
                documentId,
                hash,
                chunkerVersion());
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
                documentA, 0L, "hash-a", profileA, chunkerVersion(),
                List.of(new TextChunk("content a", 0, 9)),
                List.of(result("content a", vector(1024, 1.0f)))));
        transactionTemplate.executeWithoutResult(status -> persistence.replace(
                documentB, 0L, "hash-b", profileB, chunkerVersion(),
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

    private void assertPdfProvenance(RetrievalResult result) {
        assertEquals("A", result.getTitle());
        assertEquals("pdf-import:traceability-test/default.md", result.getSource());
        assertEquals("source-manual.pdf", result.getOriginalFilename());
        assertEquals("traceability-test/", result.getFileDirectoryPath());
        assertEquals("traceability-test/default.md", result.getIndexedFilePath());
        assertEquals("traceability-test/original.pdf", result.getOriginalFilePath());
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
