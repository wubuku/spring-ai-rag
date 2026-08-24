package com.springairag.core.integration;

import com.springairag.api.dto.RetrievalConfig;
import com.springairag.api.dto.RetrievalResult;
import com.springairag.core.chat.AuthorizedRetrievalContext;
import com.springairag.core.chat.ChatPrincipal;
import com.springairag.core.chat.RetrievalOptions;
import com.springairag.core.chat.RetrievalTraceCollector;
import com.springairag.core.config.EmbeddingProfile;
import com.springairag.core.config.RagProperties;
import com.springairag.core.rag.BoundedMultiQueryExpander;
import com.springairag.core.rag.ProjectDocumentRetriever;
import com.springairag.core.rag.RetrievalDocumentMapper;
import com.springairag.core.retrieval.HybridRetrieverService;
import com.springairag.core.retrieval.ReRankingService;
import com.springairag.core.retrieval.RetrievalFilters;
import com.springairag.core.retrieval.RetrievalScope;
import com.springairag.core.retrieval.fulltext.FulltextSearchProvider;
import com.springairag.core.retrieval.fulltext.FulltextSearchProviderFactory;
import com.springairag.core.retrieval.fulltext.QueryLang;
import com.springairag.core.retrieval.fulltext.SearchCapabilities;
import com.springairag.core.retrieval.rerank.RerankProvider;
import com.springairag.core.retrieval.rerank.RerankProviderFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.rag.Query;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

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

    @Test
    void realVectorSqlExpandsOnlyForEffectiveRerank() {
        EmbeddingProfile profile = insertProfile();
        long firstDocument = insertDocument(
                "candidate-first", "candidate pool content", "d".repeat(64));
        long secondDocument = insertDocument(
                "candidate-second", "candidate pool content", "e".repeat(64));
        long thirdDocument = insertDocument(
                "candidate-third", "candidate pool content", "f".repeat(64));

        insertVector(profile.id(), firstDocument, 0, 1.0f);
        insertVector(profile.id(), secondDocument, 0, 0.95f);
        insertVector(profile.id(), thirdDocument, 0, 0.9f);
        insertEmbeddingState(profile.id(), firstDocument, "d".repeat(64));
        insertEmbeddingState(profile.id(), secondDocument, "e".repeat(64));
        insertEmbeddingState(profile.id(), thirdDocument, "f".repeat(64));

        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        when(embeddingModel.embed("candidate query"))
                .thenReturn(vector(1024, 1.0f));
        RagProperties properties = new RagProperties();
        properties.getRetrieval().setFulltextEnabled(false);
        properties.getRerank().setEnabled(true);
        properties.getRerank().setProvider("heuristic");
        properties.getRerank().setCandidateLimit(3);
        HybridRetrieverService service = new HybridRetrieverService(
                embeddingModel, () -> profile, jdbc, properties, null, Runnable::run);

        List<RetrievalResult> rerankCandidates = service.searchInScopeDetailed(
                "candidate query",
                RetrievalScope.unscoped(),
                null,
                1,
                RetrievalConfig.builder()
                        .maxResults(1)
                        .minScore(0.0)
                        .useHybridSearch(false)
                        .useRerank(true)
                        .build(),
                RetrievalFilters.none()).results();
        List<RetrievalResult> directResults = service.searchInScopeDetailed(
                "candidate query",
                RetrievalScope.unscoped(),
                null,
                1,
                RetrievalConfig.builder()
                        .maxResults(1)
                        .minScore(0.0)
                        .useHybridSearch(false)
                        .useRerank(false)
                        .build(),
                RetrievalFilters.none()).results();

        assertEquals(
                List.of(
                        String.valueOf(firstDocument),
                        String.valueOf(secondDocument),
                        String.valueOf(thirdDocument)),
                rerankCandidates.stream()
                        .map(RetrievalResult::getDocumentId)
                        .toList());
        assertEquals(
                List.of(String.valueOf(firstDocument)),
                directResults.stream()
                        .map(RetrievalResult::getDocumentId)
                        .toList());
    }

    @Test
    void realVectorCandidatesUseCjkAwareHeuristicRerank() {
        EmbeddingProfile profile = insertProfile();
        long distractorDocument = insertDocument(
                "cjk-distractor",
                "账户权限审批流程和用量账本统计",
                "k".repeat(64));
        long relevantDocument = insertDocument(
                "cjk-relevant",
                "检索质量需要结合中文分词进行优化",
                "l".repeat(64));

        insertVector(
                profile.id(),
                distractorDocument,
                0,
                1.0f,
                "账户权限审批流程和用量账本统计");
        insertVector(
                profile.id(),
                relevantDocument,
                0,
                0.99f,
                "检索质量需要结合中文分词进行优化");
        insertEmbeddingState(
                profile.id(), distractorDocument, "k".repeat(64));
        insertEmbeddingState(
                profile.id(), relevantDocument, "l".repeat(64));

        String query = "中文检索质量优化";
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        when(embeddingModel.embed(query)).thenReturn(vector(1024, 1.0f));
        RagProperties properties = new RagProperties();
        properties.getRetrieval().setFulltextEnabled(false);
        properties.getRerank().setEnabled(true);
        properties.getRerank().setProvider("heuristic");
        properties.getRerank().setDiversityWeight(0.2f);
        properties.getRerank().setCandidateLimit(2);
        HybridRetrieverService retriever = new HybridRetrieverService(
                embeddingModel,
                () -> profile,
                jdbc,
                properties,
                null,
                Runnable::run);

        List<RetrievalResult> candidates = retriever.searchInScopeDetailed(
                query,
                RetrievalScope.unscoped(),
                null,
                1,
                RetrievalConfig.builder()
                        .maxResults(1)
                        .minScore(0.0)
                        .useHybridSearch(false)
                        .useRerank(true)
                        .build(),
                RetrievalFilters.none()).results();

        assertEquals(
                List.of(
                        String.valueOf(distractorDocument),
                        String.valueOf(relevantDocument)),
                candidates.stream()
                        .map(RetrievalResult::getDocumentId)
                        .toList());

        ReRankingService reranking = new ReRankingService(
                properties,
                new RerankProviderFactory(properties));
        List<RetrievalResult> reranked =
                reranking.rerank(query, candidates, 1);

        assertEquals(1, reranked.size());
        assertEquals(
                String.valueOf(relevantDocument),
                reranked.getFirst().getDocumentId());
    }

    @Test
    void realVectorCandidatesUseAuthoritativeTitleForHeuristicRerank() {
        EmbeddingProfile profile = insertProfile();
        long distractorDocument = insertDocument(
                "Account approval handbook",
                "shared neutral maintenance evidence",
                "m".repeat(64));
        long relevantDocument = insertDocument(
                "ZX-9042 液压校准规范",
                "shared neutral maintenance evidence",
                "n".repeat(64));

        insertVector(
                profile.id(),
                distractorDocument,
                0,
                1.0f,
                "shared neutral maintenance evidence");
        insertVector(
                profile.id(),
                relevantDocument,
                0,
                0.99f,
                "shared neutral maintenance evidence");
        insertEmbeddingState(
                profile.id(), distractorDocument, "m".repeat(64));
        insertEmbeddingState(
                profile.id(), relevantDocument, "n".repeat(64));

        String query = "ZX-9042 液压校准";
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        when(embeddingModel.embed(query)).thenReturn(vector(1024, 1.0f));
        RagProperties properties = new RagProperties();
        properties.getRetrieval().setFulltextEnabled(false);
        properties.getRerank().setEnabled(true);
        properties.getRerank().setProvider("heuristic");
        properties.getRerank().setDiversityWeight(0.2f);
        properties.getRerank().setCandidateLimit(2);
        HybridRetrieverService retriever = new HybridRetrieverService(
                embeddingModel,
                () -> profile,
                jdbc,
                properties,
                null,
                Runnable::run);

        List<RetrievalResult> candidates = retriever.searchInScopeDetailed(
                query,
                RetrievalScope.unscoped(),
                null,
                1,
                RetrievalConfig.builder()
                        .maxResults(1)
                        .minScore(0.0)
                        .useHybridSearch(false)
                        .useRerank(true)
                        .build(),
                RetrievalFilters.none()).results();

        assertEquals(
                List.of(
                        String.valueOf(distractorDocument),
                        String.valueOf(relevantDocument)),
                candidates.stream()
                        .map(RetrievalResult::getDocumentId)
                        .toList());
        assertEquals(
                List.of(
                        "Account approval handbook",
                        "ZX-9042 液压校准规范"),
                candidates.stream().map(RetrievalResult::getTitle).toList());

        ReRankingService reranking = new ReRankingService(
                properties,
                new RerankProviderFactory(properties));
        List<RetrievalResult> reranked =
                reranking.rerank(query, candidates, 1);

        assertEquals(1, reranked.size());
        assertEquals(
                String.valueOf(relevantDocument),
                reranked.getFirst().getDocumentId());
        assertEquals(
                "ZX-9042 液压校准规范",
                reranked.getFirst().getTitle());
    }

    @Test
    void boundedKnowledgeExpansionExecutesOnlyUniqueQueriesThroughPostgresRetriever() {
        EmbeddingProfile profile = insertProfile();
        long documentId = insertDocument(
                "bounded-expansion",
                "bounded expansion content",
                "j".repeat(64));
        insertVector(profile.id(), documentId, 0, 1.0f);
        insertEmbeddingState(profile.id(), documentId, "j".repeat(64));

        AtomicInteger embeddingCalls = new AtomicInteger();
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        when(embeddingModel.embed(anyString())).thenAnswer(invocation -> {
            embeddingCalls.incrementAndGet();
            return vector(1024, 1.0f);
        });
        RagProperties properties = new RagProperties();
        properties.getRetrieval().setFulltextEnabled(false);
        HybridRetrieverService service = new HybridRetrieverService(
                embeddingModel,
                () -> profile,
                jdbc,
                properties,
                null,
                Runnable::run);
        ProjectDocumentRetriever retriever = new ProjectDocumentRetriever(
                service,
                new RetrievalDocumentMapper());

        RetrievalTraceCollector trace = new RetrievalTraceCollector(3, 3, 10);
        AuthorizedRetrievalContext context = new AuthorizedRetrievalContext(
                RetrievalScope.unscoped(),
                new RetrievalOptions(5, 0.0, false, false, 0.5, 0.5),
                trace,
                "bounded-postgres",
                ChatPrincipal.local());
        Query input = Query.builder()
                .text("original query")
                .context(Map.of(ProjectDocumentRetriever.CONTEXT_KEY, context))
                .build();
        trace.configureQueryExpansion(5, 2, true, 3, 3, true);
        BoundedMultiQueryExpander expander = new BoundedMultiQueryExpander(
                query -> List.of(
                        Query.builder().text("original query").build(),
                        Query.builder().text("variant one").build(),
                        Query.builder().text("variant one").build(),
                        Query.builder().text("variant two").build()),
                3,
                true);

        List<Query> expanded = expander.expand(input);
        List<List<org.springframework.ai.document.Document>> retrieved =
                expanded.stream().map(retriever::retrieve).toList();

        assertEquals(
                List.of("original query", "variant one", "variant two"),
                expanded.stream().map(Query::text).toList());
        assertEquals(3, embeddingCalls.get());
        assertEquals(3, trace.retrievalCalls());
        assertTrue(retrieved.stream().allMatch(results -> !results.isEmpty()));
        assertEquals(1, trace.queryExpansion().get("duplicateVariantsRemoved"));
        assertEquals(3, trace.summary().get("retrievalCalls"));
    }

    @Test
    void realVectorCandidatesSupportDocumentDiversificationAndRollback() {
        EmbeddingProfile profile = insertProfile();
        long repeatedDocument = insertDocument(
                "repeated-document",
                "repeated document content",
                "g".repeat(64));
        long alternateDocument = insertDocument(
                "alternate-document",
                "alternate document content",
                "h".repeat(64));
        long thirdDocument = insertDocument(
                "third-document",
                "third document content",
                "i".repeat(64));

        insertVector(profile.id(), repeatedDocument, 0, 1.0f);
        insertVector(profile.id(), repeatedDocument, 1, 0.99f);
        insertVector(profile.id(), repeatedDocument, 2, 0.98f);
        insertVector(profile.id(), repeatedDocument, 3, 0.97f);
        insertVector(profile.id(), alternateDocument, 0, 0.96f);
        insertVector(profile.id(), thirdDocument, 0, 0.95f);
        insertEmbeddingState(
                profile.id(), repeatedDocument, "g".repeat(64), 4);
        insertEmbeddingState(
                profile.id(), alternateDocument, "h".repeat(64), 1);
        insertEmbeddingState(
                profile.id(), thirdDocument, "i".repeat(64), 1);

        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        when(embeddingModel.embed("document diversity query"))
                .thenReturn(vector(1024, 1.0f));
        RagProperties properties = new RagProperties();
        properties.getRetrieval().setFulltextEnabled(false);
        properties.getRerank().setEnabled(true);
        properties.getRerank().setProvider("heuristic");
        properties.getRerank().setCandidateLimit(6);
        properties.getRerank().setPreferredMaxChunksPerDocument(1);
        HybridRetrieverService retriever = new HybridRetrieverService(
                embeddingModel, () -> profile, jdbc, properties, null, Runnable::run);

        List<RetrievalResult> candidates = retriever.searchInScopeDetailed(
                "document diversity query",
                RetrievalScope.unscoped(),
                null,
                3,
                RetrievalConfig.builder()
                        .maxResults(3)
                        .minScore(0.0)
                        .useHybridSearch(false)
                        .useRerank(true)
                        .build(),
                RetrievalFilters.none()).results();

        assertEquals(
                List.of(
                        String.valueOf(repeatedDocument),
                        String.valueOf(repeatedDocument),
                        String.valueOf(repeatedDocument),
                        String.valueOf(repeatedDocument),
                        String.valueOf(alternateDocument),
                        String.valueOf(thirdDocument)),
                candidates.stream()
                        .map(RetrievalResult::getDocumentId)
                        .toList());
        assertEquals(
                List.of(0, 1, 2, 3, 0, 0),
                candidates.stream()
                        .map(RetrievalResult::getChunkIndex)
                        .toList());

        RerankProvider provider = orderedProvider();
        ReRankingService reranking =
                new ReRankingService(properties.getRerank(), provider);
        List<RetrievalResult> diversified =
                reranking.rerank("document diversity query", candidates, 3);
        assertEquals(
                List.of(
                        String.valueOf(repeatedDocument),
                        String.valueOf(alternateDocument),
                        String.valueOf(thirdDocument)),
                diversified.stream()
                        .map(RetrievalResult::getDocumentId)
                        .toList());
        assertEquals(List.of(0, 0, 0), diversified.stream()
                .map(RetrievalResult::getChunkIndex)
                .toList());

        properties.getRerank().setPreferredMaxChunksPerDocument(0);
        List<RetrievalResult> restored =
                reranking.rerank("document diversity query", candidates, 3);
        assertEquals(
                List.of(
                        String.valueOf(repeatedDocument),
                        String.valueOf(repeatedDocument),
                        String.valueOf(repeatedDocument)),
                restored.stream()
                        .map(RetrievalResult::getDocumentId)
                        .toList());

        properties.getRerank().setPreferredMaxChunksPerDocument(2);
        RerankProvider insufficientCoverageProvider = new RerankProvider() {
            @Override
            public String getName() {
                return "controlled";
            }

            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public List<RetrievalResult> rerank(
                    String query,
                    List<RetrievalResult> results,
                    int rankingDepth) {
                return results.subList(0, Math.min(5, results.size()));
            }
        };
        List<RetrievalResult> backfilled =
                new ReRankingService(
                        properties.getRerank(), insufficientCoverageProvider)
                        .rerank("document diversity query", candidates, 4);
        assertEquals(4, backfilled.size());
        assertEquals(
                List.of(
                        String.valueOf(repeatedDocument),
                        String.valueOf(repeatedDocument),
                        String.valueOf(repeatedDocument),
                        String.valueOf(alternateDocument)),
                backfilled.stream()
                        .map(RetrievalResult::getDocumentId)
                        .toList());
        assertEquals(
                List.of(0, 1, 2, 0),
                backfilled.stream()
                        .map(RetrievalResult::getChunkIndex)
                        .toList());
    }

    private RerankProvider orderedProvider() {
        return new RerankProvider() {
            @Override
            public String getName() {
                return "controlled";
            }

            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public List<RetrievalResult> rerank(
                    String query,
                    List<RetrievalResult> results,
                    int rankingDepth) {
                return results.subList(
                        0, Math.min(rankingDepth, results.size()));
            }
        };
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
        insertVector(
                profileId,
                documentId,
                chunkIndex,
                firstValue,
                "shared rrf content");
    }

    private void insertVector(
            long profileId,
            long documentId,
            int chunkIndex,
            float firstValue,
            String chunkText) {
        jdbc.update(
                """
                INSERT INTO rag_embeddings (
                    document_id, chunk_text, chunk_index, embedding,
                    embedding_1024, embedding_profile_id
                ) VALUES (?, ?, ?, ?::vector, ?::vector, ?)
                """,
                documentId,
                chunkText,
                chunkIndex,
                vectorText(vector(1024, firstValue)),
                vectorText(vector(1024, firstValue)),
                profileId);
    }

    private void insertEmbeddingState(
            long profileId, long documentId, String contentHash) {
        insertEmbeddingState(profileId, documentId, contentHash, 1);
    }

    private void insertEmbeddingState(
            long profileId,
            long documentId,
            String contentHash,
            int chunkCount) {
        jdbc.update(
                """
                INSERT INTO rag_document_embedding_state (
                    document_id, embedding_profile_id, content_hash,
                    chunker_version, status, chunk_count, request_generation
                ) VALUES (?, ?, ?, 'hierarchical-v2:1000:100:100',
                    'COMPLETED', ?, 1)
                """,
                documentId,
                profileId,
                contentHash,
                chunkCount);
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
