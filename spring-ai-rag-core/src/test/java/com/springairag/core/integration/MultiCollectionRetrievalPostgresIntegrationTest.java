package com.springairag.core.integration;

import com.springairag.api.dto.RetrievalConfig;
import com.springairag.api.dto.RetrievalResult;
import com.springairag.core.config.EmbeddingProfile;
import com.springairag.core.config.RagProperties;
import com.springairag.core.entity.RagDocument;
import com.springairag.core.retrieval.HybridRetrieverService;
import com.springairag.core.retrieval.RetrievalScope;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 使用真实 PostgreSQL/pgvector 验证多 Collection 范围 SQL。
 */
class MultiCollectionRetrievalPostgresIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(
            MultiCollectionRetrievalPostgresIntegrationTest.class);

    private static PostgreSQLContainer<?> postgres;
    private static DataSource dataSource;
    private static JdbcTemplate jdbcTemplate;

    private EmbeddingProfile activeProfile;
    private HybridRetrieverService retriever;

    @BeforeAll
    static void startDatabase() {
        assumeTrue(Boolean.getBoolean("multi.collection.it.enabled"),
                "Set -Dmulti.collection.it.enabled=true to run this test");

        String image = System.getProperty(
                "testcontainers.pg.image",
                System.getenv().getOrDefault(
                        "TESTCONTAINERS_PG_IMAGE",
                        "pgvector/pgvector:pg16"));
        postgres = new PostgreSQLContainer<>(
                DockerImageName.parse(image)
                        .asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("spring_ai_rag_multi_collection_test")
                .withUsername("postgres")
                .withPassword("postgres");
        postgres.start();

        PGSimpleDataSource pgDataSource = new PGSimpleDataSource();
        pgDataSource.setUrl(postgres.getJdbcUrl());
        pgDataSource.setUser(postgres.getUsername());
        pgDataSource.setPassword(postgres.getPassword());
        dataSource = pgDataSource;
        jdbcTemplate = new JdbcTemplate(dataSource);
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @BeforeEach
    void resetDatabase() {
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();

        activeProfile = insertProfile(
                "multi-collection-active", "test", "active-model");
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        when(embeddingModel.embed("scope query"))
                .thenReturn(vector(1.0f));
        RagProperties properties = new RagProperties();
        properties.getRetrieval().setFulltextEnabled(false);
        retriever = new HybridRetrieverService(
                embeddingModel,
                () -> activeProfile,
                jdbcTemplate,
                properties,
                null,
                Runnable::run);
    }

    @Test
    void directCollectionPredicatesPreserveScopeAndFreshness() {
        long collectionA = insertCollection("collection-a");
        long collectionB = insertCollection("collection-b");
        long collectionC = insertCollection("collection-c");
        EmbeddingProfile otherProfile = insertProfile(
                "multi-collection-other", "test", "other-model");

        long a1 = insertFreshDocument(
                collectionA, "A1", "document", true, activeProfile);
        long a2 = insertFreshDocument(
                collectionA, "A2", "document", true, activeProfile);
        long b1 = insertFreshDocument(
                collectionB, "B1", "document", true, activeProfile);
        long b2 = insertFreshDocument(
                collectionB, "B2", "document", true, activeProfile);
        long unassigned = insertFreshDocument(
                null, "Unassigned", "document", true, activeProfile);
        long jsonRecord = insertFreshDocument(
                collectionA, "JSON", RagDocument.JSON_RECORD,
                true, activeProfile);
        long disabled = insertFreshDocument(
                collectionA, "Disabled", "document", false, activeProfile);
        long stale = insertDocument(
                collectionA, "Stale", "document", true, "hash-stale-current");
        insertEmbedding(
                stale, activeProfile, "hash-stale-old", "COMPLETED");
        long wrongProfile = insertFreshDocument(
                collectionB, "Other profile", "document",
                true, otherProfile);

        log.info("pgvector version used by multi-collection test: {}",
                jdbcTemplate.queryForObject(
                        "SELECT extversion FROM pg_extension "
                                + "WHERE extname = 'vector'",
                        String.class));

        Set<Long> callerVisible = ids(search(RetrievalScope.unscoped()));
        assertTrue(callerVisible.contains(unassigned));
        assertTrue(callerVisible.containsAll(
                Set.of(a1, a2, b1, b2, jsonRecord)));

        Set<Long> anyCollection = ids(search(
                RetrievalScope.anyAssigned(null, null)));
        assertFalse(anyCollection.contains(unassigned));
        assertTrue(anyCollection.containsAll(
                Set.of(a1, a2, b1, b2, jsonRecord)));

        Set<Long> selected = ids(search(
                RetrievalScope.selectedCollections(
                        List.of(collectionA, collectionB), null, null)));
        assertEquals(Set.of(a1, a2, b1, b2, jsonRecord), selected);

        assertTrue(search(RetrievalScope.selectedCollections(
                List.of(collectionC), null, null)).isEmpty());

        Set<Long> intersection = ids(search(
                RetrievalScope.selectedCollections(
                        List.of(collectionA, collectionB),
                        List.of(a1, b1), null)));
        assertEquals(Set.of(a1, b1), intersection);

        Set<Long> jsonOnly = ids(search(
                RetrievalScope.selectedCollections(
                        List.of(collectionA), null,
                        RagDocument.JSON_RECORD)));
        assertEquals(Set.of(jsonRecord), jsonOnly);

        assertFalse(callerVisible.contains(disabled));
        assertFalse(callerVisible.contains(stale));
        assertFalse(callerVisible.contains(wrongProfile));
    }

    private List<RetrievalResult> search(RetrievalScope scope) {
        return retriever.searchInScope(
                "scope query",
                scope,
                null,
                50,
                RetrievalConfig.builder()
                        .maxResults(50)
                        .useHybridSearch(false)
                        .build());
    }

    private Set<Long> ids(List<RetrievalResult> results) {
        return results.stream()
                .map(RetrievalResult::getDocumentId)
                .map(Long::valueOf)
                .collect(java.util.stream.Collectors.toSet());
    }

    private EmbeddingProfile insertProfile(
            String key, String provider, String model) {
        Long id = jdbcTemplate.queryForObject(
                "INSERT INTO rag_embedding_profiles "
                        + "(profile_key, provider, model_name, model_revision, "
                        + "dimensions, distance_metric, normalization, enabled) "
                        + "VALUES (?, ?, ?, 'v1', 1024, 'COSINE', "
                        + "'PROVIDER_DEFAULT', true) RETURNING id",
                Long.class,
                key,
                provider,
                model);
        return new EmbeddingProfile(
                id, key, provider, model, "v1", 1024,
                "COSINE", "PROVIDER_DEFAULT", true);
    }

    private long insertCollection(String key) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO rag_collection (collection_key, name) "
                        + "VALUES (?, ?) RETURNING id",
                Long.class,
                key,
                key);
    }

    private long insertFreshDocument(
            Long collectionId,
            String title,
            String documentType,
            boolean enabled,
            EmbeddingProfile profile) {
        String hash = "hash-" + title.toLowerCase().replace(' ', '-');
        long documentId = insertDocument(
                collectionId, title, documentType, enabled, hash);
        insertEmbedding(documentId, profile, hash, "COMPLETED");
        return documentId;
    }

    private long insertDocument(
            Long collectionId,
            String title,
            String documentType,
            boolean enabled,
            String contentHash) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO rag_documents "
                        + "(collection_id, title, content, content_hash, "
                        + "document_type, enabled, processing_status) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 'COMPLETED') RETURNING id",
                Long.class,
                collectionId,
                title,
                "scope query " + title,
                contentHash,
                documentType,
                enabled);
    }

    private void insertEmbedding(
            long documentId,
            EmbeddingProfile profile,
            String stateContentHash,
            String status) {
        String vector = vectorText(vector(1.0f));
        jdbcTemplate.update(
                "INSERT INTO rag_embeddings "
                        + "(document_id, chunk_text, chunk_index, embedding, "
                        + "embedding_profile_id, embedding_1024, metadata) "
                        + "VALUES (?, 'scope query', 0, ?::vector, ?, "
                        + "?::vector, '{}'::jsonb)",
                documentId,
                vector,
                profile.id(),
                vector);
        jdbcTemplate.update(
                "INSERT INTO rag_document_embedding_state "
                        + "(document_id, embedding_profile_id, content_hash, "
                        + "chunker_version, status, chunk_count) "
                        + "VALUES (?, ?, ?, 'test', ?, 1)",
                documentId,
                profile.id(),
                stateContentHash,
                status);
    }

    private float[] vector(float firstValue) {
        float[] vector = new float[1024];
        vector[0] = firstValue;
        return vector;
    }

    private String vectorText(float[] vector) {
        StringBuilder value = new StringBuilder("[");
        for (int index = 0; index < vector.length; index++) {
            if (index > 0) {
                value.append(',');
            }
            value.append(vector[index]);
        }
        return value.append(']').toString();
    }
}
