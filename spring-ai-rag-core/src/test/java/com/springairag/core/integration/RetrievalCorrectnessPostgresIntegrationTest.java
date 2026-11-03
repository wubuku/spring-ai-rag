package com.springairag.core.integration;

import com.springairag.api.dto.ChatSource;
import com.springairag.api.dto.RetrievalConfig;
import com.springairag.api.dto.RetrievalResult;
import com.springairag.core.chat.RetrievalTraceCollector;
import com.springairag.core.config.EmbeddingProfile;
import com.springairag.core.config.RagProperties;
import com.springairag.core.config.RagRerankProperties;
import com.springairag.core.rag.RetrievalDocumentMapper;
import com.springairag.core.retrieval.HybridRetrieverService;
import com.springairag.core.retrieval.JsonbContainmentFilter;
import com.springairag.core.retrieval.ReRankingService;
import com.springairag.core.retrieval.RetrievalFilters;
import com.springairag.core.retrieval.RetrievalOutcome;
import com.springairag.core.retrieval.RetrievalOutcomeCodes;
import com.springairag.core.retrieval.RetrievalScope;
import com.springairag.core.retrieval.fulltext.FulltextSearchProviderFactory;
import com.springairag.core.retrieval.rerank.HeuristicRerankProvider;
import com.springairag.core.retrieval.rerank.HttpRerankProvider;
import com.springairag.core.service.DocumentDerivationDescriptorProvider;
import com.springairag.core.util.DigestUtils;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 使用真实 PostgreSQL/pgvector 的检索正确性 gated 套件。
 *
 * <p>测试语料全部由 1024 维确定性假 embedding 与固定文本构造，因此断言的是
 * 过滤、融合、重排、引用与可复现性契约，不依赖任何真实模型质量。</p>
 */
@EnabledIfSystemProperty(
        named = "retrieval-correctness.it.enabled", matches = "true")
class RetrievalCorrectnessPostgresIntegrationTest {

    private static PostgreSQLContainer<?> postgres;
    private static DataSource dataSource;
    private static JdbcTemplate jdbcTemplate;

    private EmbeddingProfile activeProfile;
    private DocumentDerivationDescriptorProvider descriptors;

    @BeforeAll
    static void startDatabase() {
        assumeTrue(Boolean.getBoolean("retrieval-correctness.it.enabled"),
                "Set -Dretrieval-correctness.it.enabled=true to run this test");

        String image = System.getProperty(
                "testcontainers.pg.image",
                System.getenv().getOrDefault(
                        "TESTCONTAINERS_PG_IMAGE",
                        "pgvector/pgvector:pg16"));
        postgres = new PostgreSQLContainer<>(
                DockerImageName.parse(image)
                        .asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("spring_ai_rag_retrieval_correctness_test")
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

        activeProfile = insertProfile("retrieval-correctness", "test", "fake-model");
        descriptors = new DocumentDerivationDescriptorProvider(new RagProperties());
    }

    // ========== 1. 过滤语义 ==========

    @Test
    void filtersComposeAsIntersectionAcrossCollectionMetadataAndMinScore() {
        RagProperties properties = new RagProperties();
        properties.getRetrieval().setFulltextEnabled(false);
        HybridRetrieverService retriever = retriever(properties, query -> e1());

        long collectionA = insertCollection("collection-a");
        long collectionB = insertCollection("collection-b");

        long aHit = insertDocument(collectionA, "Alpha policy anchor",
                "alpha policy anchor", "{\"tenant\":\"acme\",\"tags\":[\"policy\"]}", true);
        insertVector(aHit, e1());
        long aMemo = insertDocument(collectionA, "Beta memo",
                "beta memo", "{\"tenant\":\"acme\",\"tags\":[\"memo\"]}", true);
        insertVector(aMemo, vector(1.0f, (float) Math.sqrt(3.0)));
        long aForeign = insertDocument(collectionA, "Alpha foreign tenant",
                "alpha foreign tenant", "{\"tenant\":\"globex\",\"tags\":[\"policy\"]}", true);
        insertVector(aForeign, e1());
        long bPolicy = insertDocument(collectionB, "Alpha policy other",
                "alpha policy other", "{\"tenant\":\"globex\",\"tags\":[\"policy\"]}", true);
        insertVector(bPolicy, e2());
        long orphan = insertDocument(null, "Alpha orphan",
                "alpha orphan", "{\"tenant\":\"acme\",\"tags\":[\"policy\"]}", true);
        insertVector(orphan, e1());
        long disabled = insertDocument(collectionA, "Alpha disabled",
                "alpha disabled", "{\"tenant\":\"acme\",\"tags\":[\"policy\"]}", false);
        insertVector(disabled, e1());

        Set<Long> unscoped = ids(search(retriever, "alpha",
                RetrievalScope.unscoped(), metadataFilter("{\"tenant\":\"acme\"}"), 0.3));
        assertEquals(Set.of(aHit, aMemo, orphan), unscoped);

        Set<Long> selectedA = ids(search(retriever, "alpha",
                RetrievalScope.selectedCollections(List.of(collectionA), null, null),
                RetrievalFilters.none(), 0.3));
        assertEquals(Set.of(aHit, aMemo, aForeign), selectedA);
        assertFalse(selectedA.contains(orphan),
                "未归属文档不属于任何 SELECTED collection");

        Set<Long> tagged = ids(search(retriever, "alpha",
                RetrievalScope.selectedCollections(List.of(collectionA), null, null),
                metadataFilter("{\"tags\":[\"policy\"]}"), 0.3));
        assertEquals(Set.of(aHit, aForeign), tagged);

        // collectionIds 与 documentIds 是求交而非并集：B 文档被显式指定也不会越界进入 A 的搜索。
        Set<Long> intersection = ids(search(retriever, "alpha",
                RetrievalScope.selectedCollections(
                        List.of(collectionA), List.of(aHit, bPolicy), null),
                metadataFilter("{\"tenant\":\"acme\"}"), 0.3));
        assertEquals(Set.of(aHit), intersection);

        Set<Long> belowThreshold = ids(search(retriever, "alpha",
                RetrievalScope.selectedCollections(List.of(collectionB), null, null),
                RetrievalFilters.none(), 0.3));
        assertTrue(belowThreshold.isEmpty(),
                "余弦相似度 0 的文档在默认阈值下不应命中");

        Set<Long> highThreshold = ids(search(retriever, "alpha",
                RetrievalScope.selectedCollections(List.of(collectionA), null, null),
                RetrievalFilters.none(), 0.9));
        assertEquals(Set.of(aHit, aForeign), highThreshold);

        Set<Long> anyAssigned = ids(search(retriever, "alpha",
                RetrievalScope.anyAssigned(null, null),
                metadataFilter("{\"tenant\":\"acme\"}"), 0.3));
        assertEquals(Set.of(aHit, aMemo), anyAssigned);

        assertFalse(unscoped.contains(disabled), "enabled=false 的文档任何入口都不可见");
    }

    // ========== 2. 混合检索融合 ==========

    @Test
    void hybridWeightedRrfAndSingleSidedChannelsDegradePredictably() {
        RagProperties properties = hybridProperties("pg_trgm");
        HybridRetrieverService retriever = retriever(properties, this::queryVector);

        long collection = insertCollection("hybrid");
        long both = insertKeywordDocument(collection, "Both alpha", "alpha beta");
        insertVector(both, e1());
        long vectorOnly = insertKeywordDocument(collection, "Vector only", "beta gamma");
        insertVector(vectorOnly, vector(0.95f, (float) Math.sqrt(1.0 - 0.95 * 0.95)));
        long keywordOnly = insertKeywordDocument(
                collection, "Keyword only", "zzzz alpha keyword release");
        insertVector(keywordOnly, e2());
        long neither = insertKeywordDocument(collection, "Neither", "delta epsilon");
        insertVector(neither, e2());

        List<RetrievalResult> fused = search(retriever, "alpha",
                RetrievalScope.unscoped(), RetrievalFilters.none(), 0.3, 0.5, 0.5);
        assertEquals(jsonIds(both, vectorOnly, keywordOnly),
                fused.stream().map(RetrievalResult::getDocumentId).toList());
        assertFalse(fused.stream().anyMatch(
                result -> result.getDocumentId().equals(String.valueOf(neither))));

        assertEquals(1.0, fused.get(0).getScore(), 1e-9,
                "两个通道都排第 1 时融合分为两个 0.5 权重之和");
        assertEquals(61.0 * 0.5 / 62.0, fused.get(1).getScore(), 1e-9);
        assertEquals(fused.get(1).getScore(), fused.get(2).getScore(), 1e-9,
                "单通道排名相同则融合分相同");

        assertEquals(1.0, fused.get(0).getVectorScore(), 1e-9);
        assertEquals(1.0, fused.get(0).getFulltextScore(), 1e-9);
        assertEquals(0.0, fused.get(2).getVectorScore(), 1e-9);
        assertEquals(1.0, fused.get(2).getFulltextScore(), 1e-9);

        List<RetrievalResult> vectorOnlyConfig = search(retriever, "alpha",
                RetrievalScope.unscoped(), RetrievalFilters.none(),
                0.3, 1.0, 0.0);
        assertEquals(jsonIds(both, vectorOnly),
                vectorOnlyConfig.stream().map(RetrievalResult::getDocumentId).toList(),
                "fulltext 权重为 0 时关键词通道不得把候选带入结果集");

        List<RetrievalResult> keywordOnlyConfig = search(retriever, "alpha",
                RetrievalScope.unscoped(), RetrievalFilters.none(),
                0.3, 0.0, 1.0);
        assertEquals(jsonIds(both, keywordOnly),
                keywordOnlyConfig.stream().map(RetrievalResult::getDocumentId).toList(),
                "vector 权重为 0 时向量通道不得把候选带入结果集");

        List<RetrievalResult> hybridDisabled = search(retriever, "alpha",
                RetrievalScope.unscoped(), RetrievalFilters.none(),
                0.3, 0.5, 0.5, false);
        assertEquals(jsonIds(both, vectorOnly),
                hybridDisabled.stream().map(RetrievalResult::getDocumentId).toList());

        RetrievalOutcome keywordSide = searchDetailed(retriever, "zzzz",
                RetrievalScope.unscoped(), RetrievalFilters.none(), 0.5, 0.5, 0.5);
        assertEquals(1, keywordSide.results().size());
        assertEquals(String.valueOf(keywordOnly),
                keywordSide.results().get(0).getDocumentId());
        assertEquals(0.0, keywordSide.results().get(0).getVectorScore(), 1e-9);
        assertEquals(1.0, keywordSide.results().get(0).getFulltextScore(), 1e-9);
        assertEquals(RetrievalOutcomeCodes.PARTIAL_FULLTEXT, keywordSide.outcomeCode());
        assertEquals(0, keywordSide.vectorStage().resultCount());

        RetrievalOutcome empty = searchDetailed(retriever, "empty-probe",
                RetrievalScope.unscoped(), RetrievalFilters.none(), 0.3, 0.5, 0.5);
        assertTrue(empty.results().isEmpty());
        assertEquals(RetrievalOutcomeCodes.BELOW_MIN_SCORE, empty.emptyReasonCode());

        // 关键词索引不可用时，向量分支仍必须返回结果，失败只体现在诊断分支上。
        jdbcTemplate.execute(
                "ALTER TABLE rag_document_chunks RENAME TO rag_document_chunks_unavailable");
        RetrievalOutcome degraded = searchDetailed(retriever, "alpha",
                RetrievalScope.unscoped(), RetrievalFilters.none(), 0.3, 0.5, 0.5);
        assertEquals(jsonIds(both, vectorOnly),
                degraded.results().stream().map(RetrievalResult::getDocumentId).toList());
        assertEquals(RetrievalOutcomeCodes.PARTIAL_VECTOR, degraded.outcomeCode());
        assertTrue(degraded.fulltextStage().failed(),
                "关键词通道失败不得拖垮向量结果");
    }

    // ========== 3. 重排 ==========

    @Test
    void rerankReordersWithoutChangingCandidateProvenance() {
        RagProperties properties = new RagProperties();
        properties.getRetrieval().setFulltextEnabled(false);
        HybridRetrieverService retriever = retriever(properties, query -> e1());

        long collection = insertCollection("rerank");
        long distractor = insertDocument(collection, "Distractor",
                "beta gamma delta", "{}", true);
        insertVector(distractor, e1());
        long relevant = insertDocument(collection, "K47 manual",
                "K47 operation manual", "{}", true);
        insertVector(relevant, vector(0.9f, (float) Math.sqrt(1.0 - 0.9 * 0.9)));
        long other = insertDocument(collection, "Other",
                "epsilon zeta", "{}", true);
        insertVector(other, vector(0.8f, (float) Math.sqrt(1.0 - 0.8 * 0.8)));

        List<RetrievalResult> candidates = search(retriever, "K47",
                RetrievalScope.unscoped(), RetrievalFilters.none(), 0.3);
        assertEquals(jsonIds(distractor, relevant, other),
                candidates.stream().map(RetrievalResult::getDocumentId).toList(),
                "无重排时按向量相似度排序");

        RagRerankProperties disabled = new RagRerankProperties();
        disabled.setEnabled(false);
        List<RetrievalResult> disabledOutput = new ReRankingService(
                disabled, new HeuristicRerankProvider(disabled))
                .rerank("K47", candidates, candidates.size());
        assertEquals(ids(candidates), ids(disabledOutput));
        assertEquals(jsonIds(distractor, relevant, other),
                disabledOutput.stream().map(RetrievalResult::getDocumentId).toList());

        RagRerankProperties heuristic = new RagRerankProperties();
        heuristic.setEnabled(true);
        heuristic.setProvider("heuristic");
        List<RetrievalResult> reranked = new ReRankingService(
                heuristic, new HeuristicRerankProvider(heuristic))
                .rerank("K47", candidates, candidates.size());
        assertEquals(String.valueOf(relevant), reranked.get(0).getDocumentId(),
                "词面命中应被提升到首位");
        assertEquals(ids(candidates), ids(reranked), "重排不得增删候选文档");
        assertTrue(reranked.stream().allMatch(result ->
                result.getCollectionId() != null
                        && result.getCollectionId().equals(collection)));
        assertEquals("rerank", reranked.get(0).getCollectionKey());

        RagRerankProperties unavailable = new RagRerankProperties();
        unavailable.setEnabled(true);
        unavailable.setProvider("http");
        unavailable.setApiKey("");
        assertEquals(reranked.stream().map(RetrievalResult::getDocumentId).toList(),
                new ReRankingService(unavailable, new HttpRerankProvider(unavailable))
                        .rerank("K47", candidates, candidates.size())
                        .stream().map(RetrievalResult::getDocumentId).toList(),
                "rerank 服务缺失时按默认启发式降级，而不是失败或丢候选");

        unavailable.setFallbackToHeuristic(false);
        assertEquals(jsonIds(distractor, relevant, other),
                new ReRankingService(unavailable, new HttpRerankProvider(unavailable))
                        .rerank("K47", candidates, candidates.size())
                        .stream().map(RetrievalResult::getDocumentId).toList(),
                "关闭启发式降级时保持原始候选顺序");
    }

    // ========== 4. 引用 / 溯源 ==========

    @Test
    void citationsOnlyReferenceRecalledChunksAndStayInsideCollection() {
        RagProperties properties = hybridProperties("pg_trgm");
        HybridRetrieverService retriever = retriever(properties, this::queryVector);
        RetrievalDocumentMapper mapper = new RetrievalDocumentMapper();

        long collectionA = insertCollection("collection-a");
        long collectionB = insertCollection("collection-b");
        long aHit = insertKeywordDocument(collectionA, "A alpha", "alpha policy A");
        insertVector(aHit, e1());
        long aOther = insertKeywordDocument(collectionA, "A other", "beta gamma A");
        insertVector(aOther, e1());
        long bHit = insertKeywordDocument(collectionB, "B alpha", "alpha policy B");
        insertVector(bHit, e1());

        RetrievalOutcome outcomeA = searchDetailed(retriever, "alpha",
                RetrievalScope.selectedCollections(List.of(collectionA), null, null),
                RetrievalFilters.none(), 0.3, 0.5, 0.5);
        assertEquals(Set.of(aHit, aOther),
                outcomeA.results().stream()
                        .map(result -> Long.valueOf(result.getDocumentId()))
                        .collect(Collectors.toSet()));

        RetrievalTraceCollector trace = new RetrievalTraceCollector(3, 3, 20);
        trace.recordOutcome(outcomeA);
        trace.markExposed(outcomeA.results().subList(0, 1));

        List<ChatSource> exposed = trace.sources().stream()
                .map(result -> mapper.toChatSource(result, trace.citationId(result)))
                .toList();
        assertEquals(1, exposed.size());
        assertEquals(String.valueOf(aHit), exposed.get(0).getDocumentId());
        assertEquals("collection-a", exposed.get(0).getCollectionKey());
        assertNotNull(trace.citationId(outcomeA.results().get(1)));
        assertTrue(trace.sources().stream().noneMatch(
                result -> result.getDocumentId().equals(String.valueOf(aOther))),
                "未暴露给模型的 chunk 不得出现在最终引用来源里");

        RetrievalOutcome outcomeB = searchDetailed(retriever, "alpha",
                RetrievalScope.selectedCollections(List.of(collectionB), null, null),
                RetrievalFilters.none(), 0.3, 0.5, 0.5);
        RetrievalTraceCollector traceB = new RetrievalTraceCollector(3, 3, 20);
        traceB.recordOutcome(outcomeB);
        traceB.markExposed(outcomeB.results());
        assertEquals(1, traceB.sources().size());
        assertEquals(String.valueOf(bHit), traceB.sources().get(0).getDocumentId());
        assertEquals("collection-b",
                mapper.toChatSource(traceB.sources().get(0),
                        traceB.citationId(traceB.sources().get(0))).getCollectionKey());

        // 硬删除后再次检索：已删文档不会再产生任何引用来源。
        deleteDocument(aHit);
        RetrievalOutcome afterDelete = searchDetailed(retriever, "alpha",
                RetrievalScope.selectedCollections(List.of(collectionA), null, null),
                RetrievalFilters.none(), 0.3, 0.5, 0.5);
        assertTrue(afterDelete.results().stream().noneMatch(
                result -> result.getDocumentId().equals(String.valueOf(aHit))));
        RetrievalTraceCollector afterDeleteTrace = new RetrievalTraceCollector(3, 3, 20);
        afterDeleteTrace.recordOutcome(afterDelete);
        afterDeleteTrace.markExposed(afterDelete.results());
        assertTrue(afterDeleteTrace.sources().stream().noneMatch(
                result -> result.getDocumentId().equals(String.valueOf(aHit))));
    }

    // ========== 5. 可复现性 ==========

    @Test
    void identicalCorpusAndParametersProduceIdenticalResults() {
        RagProperties properties = new RagProperties();
        properties.getRetrieval().setFulltextEnabled(false);
        HybridRetrieverService retriever = retriever(properties, query -> e1());

        long collection = insertCollection("determinism");
        long tieB = insertDocument(collection, "Tie B", "same text", "{}", true);
        insertVector(tieB, e1());
        long tieA = insertDocument(collection, "Tie A", "same text", "{}", true);
        insertVector(tieA, e1());
        long tieC = insertDocument(collection, "Tie C", "same text", "{}", true);
        insertVector(tieC, e1());

        RetrievalConfig config = RetrievalConfig.builder()
                .maxResults(10)
                .useHybridSearch(false)
                .minScore(0.3)
                .build();
        List<RetrievalResult> first = retriever.searchInScope(
                "alpha", RetrievalScope.unscoped(), null, 10, config);
        List<RetrievalResult> second = retriever.searchInScope(
                "alpha", RetrievalScope.unscoped(), null, 10, config);
        assertEquals(jsonIds(tieB, tieA, tieC),
                first.stream().map(RetrievalResult::getDocumentId).toList(),
                "相同余弦分数必须按 documentId 升序稳定排序");
        assertEquals(resultSignature(first), resultSignature(second),
                "相同语料、相同参数两次检索结果必须完全一致");

        int approximateIndexes = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_indexes WHERE tablename = 'rag_embeddings' "
                        + "AND indexdef ILIKE '%embedding_1024%'",
                Integer.class);
        assertEquals(0, approximateIndexes,
                "当前向量列没有近似索引：检索走精确扫描，结果可复现；"
                        + "若将来引入 HNSW/IVFFlat，必须同时更新本用例的抖动容忍说明");

        RagProperties hybrid = hybridProperties("pg_trgm");
        HybridRetrieverService hybridRetriever = retriever(hybrid, this::queryVector);
        long keywordCollection = insertCollection("determinism-keyword");
        long keyword1 = insertKeywordDocument(keywordCollection, "Tie 1", "alpha tie");
        insertVector(keyword1, e2());
        long keyword2 = insertKeywordDocument(keywordCollection, "Tie 2", "alpha tie");
        insertVector(keyword2, e2());
        long keyword3 = insertKeywordDocument(keywordCollection, "Tie 3", "alpha tie");
        insertVector(keyword3, e2());

        List<RetrievalResult> keywordFirst = search(hybridRetriever, "alpha",
                RetrievalScope.selectedCollections(
                        List.of(keywordCollection), null, null),
                RetrievalFilters.none(), 0.3, 0.5, 0.5);
        List<RetrievalResult> keywordSecond = search(hybridRetriever, "alpha",
                RetrievalScope.selectedCollections(
                        List.of(keywordCollection), null, null),
                RetrievalFilters.none(), 0.3, 0.5, 0.5);
        assertEquals(jsonIds(keyword1, keyword2, keyword3),
                keywordFirst.stream().map(RetrievalResult::getDocumentId).toList(),
                "关键词通道同分时必须按 documentId 升序稳定排序");
        assertEquals(resultSignature(keywordFirst), resultSignature(keywordSecond));
    }

    // ========== 辅助方法 ==========

    private HybridRetrieverService retriever(
            RagProperties properties, QueryVector queryVector) {
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        when(embeddingModel.embed(anyString()))
                .thenAnswer(invocation -> queryVector.forQuery(invocation.getArgument(0)));
        return new HybridRetrieverService(
                embeddingModel,
                () -> activeProfile,
                jdbcTemplate,
                properties,
                new FulltextSearchProviderFactory(jdbcTemplate, properties),
                Runnable::run);
    }

    private RagProperties hybridProperties(String strategy) {
        RagProperties properties = new RagProperties();
        properties.getRetrieval().setFulltextEnabled(true);
        properties.getRetrieval().setFulltextStrategy(strategy);
        return properties;
    }

    private List<RetrievalResult> search(
            HybridRetrieverService retriever,
            String query,
            RetrievalScope scope,
            RetrievalFilters filters,
            double minScore) {
        return search(retriever, query, scope, filters, minScore, 0.5, 0.5);
    }

    private List<RetrievalResult> search(
            HybridRetrieverService retriever,
            String query,
            RetrievalScope scope,
            RetrievalFilters filters,
            double minScore,
            double vectorWeight,
            double fulltextWeight) {
        return search(retriever, query, scope, filters, minScore,
                vectorWeight, fulltextWeight, true);
    }

    private List<RetrievalResult> search(
            HybridRetrieverService retriever,
            String query,
            RetrievalScope scope,
            RetrievalFilters filters,
            double minScore,
            double vectorWeight,
            double fulltextWeight,
            boolean hybrid) {
        return searchDetailed(retriever, query, scope, filters, minScore,
                vectorWeight, fulltextWeight, hybrid).results();
    }

    private RetrievalOutcome searchDetailed(
            HybridRetrieverService retriever,
            String query,
            RetrievalScope scope,
            RetrievalFilters filters,
            double minScore,
            double vectorWeight,
            double fulltextWeight) {
        return searchDetailed(retriever, query, scope, filters, minScore,
                vectorWeight, fulltextWeight, true);
    }

    private RetrievalOutcome searchDetailed(
            HybridRetrieverService retriever,
            String query,
            RetrievalScope scope,
            RetrievalFilters filters,
            double minScore,
            double vectorWeight,
            double fulltextWeight,
            boolean hybrid) {
        RetrievalConfig config = RetrievalConfig.builder()
                .maxResults(10)
                .useHybridSearch(hybrid)
                .minScore(minScore)
                .vectorWeight(vectorWeight)
                .fulltextWeight(fulltextWeight)
                .build();
        return retriever.searchInScopeDetailed(
                query, scope, null, 10, config, filters);
    }

    private RetrievalFilters metadataFilter(String metadata) {
        return new RetrievalFilters(
                new JsonbContainmentFilter(metadata), List.of());
    }

    private Set<Long> ids(List<RetrievalResult> results) {
        return results.stream()
                .map(RetrievalResult::getDocumentId)
                .map(Long::valueOf)
                .collect(Collectors.toSet());
    }

    private List<String> jsonIds(long... documentIds) {
        return java.util.Arrays.stream(documentIds)
                .mapToObj(String::valueOf)
                .toList();
    }

    private String resultSignature(List<RetrievalResult> results) {
        return results.stream()
                .map(result -> result.getDocumentId() + ":" + result.getChunkIndex()
                        + "@" + result.getScore() + "/" + result.getVectorScore()
                        + "/" + result.getFulltextScore())
                .collect(Collectors.joining("|"));
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
                "INSERT INTO rag_collection "
                        + "(collection_key, name, active_embedding_profile_id) "
                        + "VALUES (?, ?, ?) RETURNING id",
                Long.class,
                key,
                key,
                activeProfile.id());
    }

    private long insertDocument(
            Long collectionId,
            String title,
            String content,
            String metadataJson,
            boolean enabled) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO rag_documents "
                        + "(collection_id, title, content, content_hash, "
                        + "document_type, enabled, processing_status, metadata) "
                        + "VALUES (?, ?, ?, ?, 'document', ?, 'COMPLETED', "
                        + "CAST(? AS jsonb)) RETURNING id",
                Long.class,
                collectionId,
                title,
                content,
                DigestUtils.sha256(content),
                enabled,
                metadataJson);
    }

    private void insertVector(long documentId, float[] embedding) {
        String contentHash = jdbcTemplate.queryForObject(
                "SELECT content_hash FROM rag_documents WHERE id = ?",
                String.class, documentId);
        jdbcTemplate.update(
                "INSERT INTO rag_embeddings "
                        + "(document_id, chunk_text, chunk_index, "
                        + "embedding_profile_id, embedding_1024, metadata) "
                        + "VALUES (?, ?, 0, ?, ?::vector, '{}'::jsonb)",
                documentId,
                contentHash,
                activeProfile.id(),
                RetrievalVectorText.of(embedding));
        jdbcTemplate.update(
                "INSERT INTO rag_document_embedding_state "
                        + "(document_id, embedding_profile_id, content_hash, "
                        + "chunker_version, status, chunk_count) "
                        + "VALUES (?, ?, ?, ?, 'COMPLETED', 1)",
                documentId,
                activeProfile.id(),
                contentHash,
                descriptors.textDescriptor().chunkerVersion());
    }

    /**
     * 同时写入向量与可被关键词通道命中的本地 chunk。
     */
    private long insertKeywordDocument(
            long collectionId, String title, String content) {
        long documentId = insertDocument(
                collectionId, title, content, "{}", true);
        String contentHash = DigestUtils.sha256(content);
        jdbcTemplate.update(
                "INSERT INTO rag_document_chunks "
                        + "(document_id, local_index_generation, content_hash, "
                        + "chunker_version, chunk_text, chunk_index, "
                        + "chunk_start_pos, chunk_end_pos) "
                        + "VALUES (?, 1, ?, ?, ?, 0, 0, ?)",
                documentId,
                contentHash,
                descriptors.textDescriptor().chunkerVersion(),
                content,
                content.length());
        jdbcTemplate.update(
                "INSERT INTO rag_document_local_index_state "
                        + "(document_id, local_index_status, content_hash, "
                        + "chunker_version, local_index_generation, chunk_count) "
                        + "VALUES (?, 'READY', ?, ?, 1, 1)",
                documentId,
                contentHash,
                descriptors.textDescriptor().chunkerVersion());
        return documentId;
    }

    private void deleteDocument(long documentId) {
        jdbcTemplate.update("DELETE FROM rag_embeddings WHERE document_id = ?", documentId);
        jdbcTemplate.update("DELETE FROM rag_document_chunks WHERE document_id = ?", documentId);
        jdbcTemplate.update(
                "DELETE FROM rag_document_local_index_state WHERE document_id = ?", documentId);
        jdbcTemplate.update(
                "DELETE FROM rag_document_embedding_state WHERE document_id = ?", documentId);
        jdbcTemplate.update("DELETE FROM rag_documents WHERE id = ?", documentId);
    }

    private float[] queryVector(String query) {
        if ("zzzz".equals(query) || "empty-probe".equals(query)) {
            return e3();
        }
        return e1();
    }

    private float[] e1() {
        return vector(1.0f, 0.0f);
    }

    private float[] e2() {
        return vector(0.0f, 1.0f);
    }

    private float[] e3() {
        float[] vector = new float[1024];
        vector[2] = 1.0f;
        return vector;
    }

    private float[] vector(float firstComponent, float secondComponent) {
        float[] vector = new float[1024];
        vector[0] = firstComponent;
        vector[1] = secondComponent;
        return vector;
    }

    @FunctionalInterface
    private interface QueryVector {
        float[] forQuery(String query);
    }

    private static final class RetrievalVectorText {
        private RetrievalVectorText() {
        }

        static String of(float[] embedding) {
            StringBuilder builder = new StringBuilder("[");
            for (int index = 0; index < embedding.length; index++) {
                if (index > 0) {
                    builder.append(',');
                }
                builder.append(embedding[index]);
            }
            return builder.append(']').toString();
        }
    }
}
