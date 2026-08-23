package com.springairag.core.integration;

import com.springairag.api.dto.RetrievalConfig;
import com.springairag.api.dto.RetrievalResult;
import com.springairag.core.config.EmbeddingProfile;
import com.springairag.core.config.RagProperties;
import com.springairag.core.retrieval.HybridRetrieverService;
import com.springairag.core.retrieval.RetrievalFilters;
import com.springairag.core.retrieval.RetrievalScope;
import com.springairag.core.retrieval.fulltext.FulltextSearchProvider;
import com.springairag.core.retrieval.fulltext.FulltextSearchProviderFactory;
import com.springairag.core.retrieval.fulltext.QueryLang;
import com.springairag.core.retrieval.fulltext.SearchCapabilities;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 通过真实 PostgreSQL/pgvector 和真实 HybridRetrieverService 验证 RRF 融合链路。
 *
 * <p>全文 provider 只负责提供可控的已排序候选，测试重点是服务的真实向量 SQL、
 * Profile freshness 过滤和融合调用，而不是外部全文引擎质量。
 */
class HybridRetrieverRrfPostgresIntegrationTest {

    private static PostgreSQLContainer<?> postgres;
    private static DataSource dataSource;
    private JdbcTemplate jdbc;

    @BeforeAll
    static void startDatabase() {
        assumeTrue(Boolean.getBoolean("hybrid-rrf.it.enabled"),
                "Set -Dhybrid-rrf.it.enabled=true to run this test");
        String externalUrl = System.getenv("HYBRID_RRF_IT_JDBC_URL");
        if (externalUrl != null && !externalUrl.isBlank()) {
            if (!"YES".equals(System.getenv("HYBRID_RRF_IT_CLEAN_CONFIRM"))) {
                throw new IllegalStateException(
                        "Set HYBRID_RRF_IT_CLEAN_CONFIRM=YES only for a disposable database");
            }
            dataSource = dataSource(
                    externalUrl,
                    System.getenv("HYBRID_RRF_IT_USERNAME"),
                    System.getenv("HYBRID_RRF_IT_PASSWORD"));
            return;
        }
        try {
            assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                    "Docker is unavailable for PostgreSQL integration tests");
        } catch (RuntimeException unavailable) {
            assumeTrue(false, "Docker is unavailable: " + unavailable.getMessage());
        }
        String image = System.getProperty(
                "testcontainers.pg.image",
                System.getenv().getOrDefault(
                        "TESTCONTAINERS_PG_IMAGE", "pgvector/pgvector:pg16"));
        postgres = new PostgreSQLContainer<>(
                DockerImageName.parse(image).asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("hybrid_rrf")
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
    void resetDatabase() {
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
        jdbc = new JdbcTemplate(dataSource);
    }

    @Test
    void realServiceFusesFreshVectorCandidatesWithControlledFulltextRanks() {
        EmbeddingProfile profile = insertProfile();
        long firstDocument = insertDocument(
                "vector-first", "shared rrf content", "a".repeat(64));
        long secondDocument = insertDocument(
                "overlap-second", "shared rrf content", "b".repeat(64));
        long thirdDocument = insertDocument(
                "fulltext-first", "shared rrf content", "c".repeat(64));

        insertVector(profile.id(), firstDocument, 0, 1.0f);
        insertVector(profile.id(), secondDocument, 0, 0.95f);
        insertVector(profile.id(), thirdDocument, 0, 0.9f);
        insertEmbeddingState(profile.id(), firstDocument, "a".repeat(64));
        insertEmbeddingState(profile.id(), secondDocument, "b".repeat(64));
        insertEmbeddingState(profile.id(), thirdDocument, "c".repeat(64));

        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        when(embeddingModel.embed("rrf query")).thenReturn(vector(1024, 1.0f));
        FulltextSearchProvider provider = controlledProvider(
                List.of(
                        result(String.valueOf(secondDocument), 0, 100.0),
                        result(String.valueOf(thirdDocument), 0, 1.0)));
        SearchCapabilities capabilities = new SearchCapabilities(jdbc, false);
        capabilities.setHasEnIndex(true);
        FulltextSearchProviderFactory factory = new FulltextSearchProviderFactory(
                jdbc, "auto", capabilities, provider, provider, provider);
        RagProperties properties = new RagProperties();
        properties.getRetrieval().setFulltextEnabled(true);
        HybridRetrieverService service = new HybridRetrieverService(
                embeddingModel, () -> profile, jdbc, properties, factory, Runnable::run);

        List<RetrievalResult> results = service.searchInScopeDetailed(
                "rrf query",
                RetrievalScope.unscoped(),
                null,
                3,
                RetrievalConfig.builder()
                        .maxResults(3)
                        .minScore(0.0)
                        .vectorWeight(0.5)
                        .fulltextWeight(0.5)
                        .useHybridSearch(true)
                        .build(),
                RetrievalFilters.none()).results();

        assertEquals(
                List.of(
                        String.valueOf(secondDocument),
                        String.valueOf(thirdDocument),
                        String.valueOf(firstDocument)),
                results.stream().map(RetrievalResult::getDocumentId).toList());
        assertTrue(results.get(0).getScore() > results.get(1).getScore());
        assertTrue(results.get(1).getScore() > results.get(2).getScore());
        assertEquals(0.95, results.get(0).getVectorScore(), 1e-6);
        assertEquals(100.0, results.get(0).getFulltextScore(), 1e-6);
    }

    private FulltextSearchProvider controlledProvider(List<RetrievalResult> results) {
        return new FulltextSearchProvider() {
            @Override
            public String getName() {
                return "controlled-fulltext";
            }

            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public List<RetrievalResult> search(
                    String query, List<Long> documentIds, List<Long> excludeIds,
                    int limit, double minScore, long embeddingProfileId) {
                return results;
            }
        };
    }

    private RetrievalResult result(String documentId, int chunkIndex, double score) {
        return com.springairag.core.retrieval.RetrievalUtils.createResult(
                documentId, "controlled fulltext", chunkIndex, score);
    }

    private EmbeddingProfile insertProfile() {
        Long id = jdbc.queryForObject(
                """
                INSERT INTO rag_embedding_profiles (
                    profile_key, provider, model_name, model_revision,
                    dimensions, distance_metric, normalization, enabled
                ) VALUES ('rrf-test-profile', 'test', 'test-model', 'v1',
                    1024, 'COSINE', 'PROVIDER_DEFAULT', true)
                RETURNING id
                """,
                Long.class);
        return new EmbeddingProfile(
                id,
                "rrf-test-profile",
                "test",
                "test-model",
                "v1",
                1024,
                "COSINE",
                "PROVIDER_DEFAULT",
                true);
    }

    private long insertDocument(String title, String content, String contentHash) {
        return jdbc.queryForObject(
                """
                INSERT INTO rag_documents (
                    title, content, content_hash, enabled, processing_status
                ) VALUES (?, ?, ?, true, 'COMPLETED')
                RETURNING id
                """,
                Long.class,
                title,
                content,
                contentHash);
    }

    private void insertVector(
            long profileId, long documentId, int chunkIndex, float firstValue) {
        jdbc.update(
                """
                INSERT INTO rag_embeddings (
                    document_id, chunk_text, chunk_index, embedding,
                    embedding_1024, embedding_profile_id
                ) VALUES (?, 'shared rrf content', ?, ?::vector, ?::vector, ?)
                """,
                documentId,
                chunkIndex,
                vectorText(vector(1024, firstValue)),
                vectorText(vector(1024, firstValue)),
                profileId);
    }

    private void insertEmbeddingState(
            long profileId, long documentId, String contentHash) {
        jdbc.update(
                """
                INSERT INTO rag_document_embedding_state (
                    document_id, embedding_profile_id, content_hash,
                    chunker_version, status, chunk_count, request_generation
                ) VALUES (?, ?, ?, 'hierarchical-v2:1000:100:100',
                    'COMPLETED', 1, 1)
                """,
                documentId,
                profileId,
                contentHash);
    }

    private static DataSource dataSource(
            String url, String username, String password) {
        PGSimpleDataSource source = new PGSimpleDataSource();
        source.setUrl(url);
        source.setUser(username == null || username.isBlank()
                ? "postgres" : username);
        source.setPassword(password == null ? "" : password);
        return source;
    }

    private static float[] vector(int dimensions, float firstValue) {
        float[] vector = new float[dimensions];
        vector[0] = firstValue;
        vector[1] = (float) Math.sqrt(Math.max(0.0, 1.0 - firstValue * firstValue));
        return vector;
    }

    private static String vectorText(float[] vector) {
        StringBuilder value = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                value.append(',');
            }
            value.append(vector[i]);
        }
        return value.append(']').toString();
    }
}
