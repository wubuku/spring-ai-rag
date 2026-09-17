package com.springairag.core.retrieval;

import com.springairag.api.dto.RetrievalConfig;
import com.springairag.core.retrieval.RetrievalOutcome;
import com.springairag.core.config.EmbeddingProfile;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.config.RagProperties;
import com.springairag.core.retrieval.fulltext.FulltextSearchProvider;
import com.springairag.core.retrieval.fulltext.FulltextSearchProviderFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.List;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * HybridRetrieverService 分支隔离长尾（Batch 505，JaCoCo 驱动）：
 * 无全文工厂时向量单臂 DISABLED 全文、向量分支异常（空向量/维度
 * 不匹配）被 catch 归一为 ERROR 分支且不拖垮整体、全文 provider
 * 抛错被 catch 归一为 ERROR 分支、详细结果 failed 标记同样映射
 * ERROR。
 */
class HybridRetrieverFulltextErrorTailTest {

    private static final String QUERY = "膝盖康复训练";

    private EmbeddingModel embeddingModel;
    private EmbeddingProfileProvider profileProvider;
    private JdbcTemplate jdbcTemplate;
    private RagProperties ragProperties;
    private FulltextSearchProvider fulltextProvider;
    private FulltextSearchProviderFactory fulltextProviderFactory;

    @BeforeEach
    void setUp() {
        embeddingModel = mock(EmbeddingModel.class);
        profileProvider = mock(EmbeddingProfileProvider.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        fulltextProvider = mock(FulltextSearchProvider.class);
        fulltextProviderFactory = mock(FulltextSearchProviderFactory.class);
        ragProperties = new RagProperties();
        when(profileProvider.getActiveProfile()).thenReturn(new EmbeddingProfile(
                9L, "bge-m3", "vendor", "bge-m3", "rev-1",
                1024, "cosine", "normalize", true));
        // 向量查询默认空行集。
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of());
    }

    private HybridRetrieverService service(FulltextSearchProviderFactory factory) {
        return new HybridRetrieverService(
                embeddingModel,
                profileProvider,
                jdbcTemplate,
                ragProperties,
                factory,
                Runnable::run,
                null);
    }

    private void stubEmbedding(float[] vector) {
        when(embeddingModel.embed(anyString())).thenReturn(vector);
    }

    private FulltextSearchProviderFactory availableFactory() {
        when(fulltextProviderFactory.detectLang(anyString()))
                .thenReturn(com.springairag.core.retrieval.fulltext.QueryLang.ZH);
        when(fulltextProviderFactory.getProvider(any(com.springairag.core.retrieval.fulltext.QueryLang.class)))
                .thenReturn(fulltextProvider);
        when(fulltextProvider.isAvailable()).thenReturn(true);
        when(fulltextProvider.getName()).thenReturn("fts");
        return fulltextProviderFactory;
    }

    @Test
    void missingFactoryRunsVectorOnlyWithDisabledFulltext() {
        // 工厂缺省：全文分支 DISABLED，仅向量臂执行并成功。
        stubEmbedding(validVector());

        RetrievalOutcome outcome = service(null).searchInScopeDetailed(
                QUERY, null, List.of(), 5, null, null);

        // 无工厂 → NoOp 全文 provider，两阶段仍占位：向量 SUCCESS、
        // 全文以空结果成功完成。
        assertEquals(2, outcome.branchStages().size());
        assertEquals(RetrievalBranchStage.VECTOR,
                outcome.branchStages().get(0).branch());
        assertEquals(RetrievalBranchStage.SUCCESS,
                outcome.branchStages().get(0).status());
        assertEquals(RetrievalBranchStage.FULLTEXT,
                outcome.branchStages().get(1).branch());
        assertEquals(RetrievalBranchStage.SUCCESS,
                outcome.branchStages().get(1).status());
    }

    @Test
    void nullQueryVectorIsReportedAsVectorErrorBranch() {
        HybridRetrieverService service = service(null);
        stubEmbedding(null);

        RetrievalOutcome outcome = service.searchInScopeDetailed(
                QUERY, null, List.of(), 5, null, null);

        assertEquals(RetrievalBranchStage.ERROR,
                outcome.branchStages().getFirst().status());
        assertTrue(outcome.results().isEmpty());
    }

    @Test
    void dimensionMismatchIsReportedAsVectorErrorBranch() {
        HybridRetrieverService service = service(null);
        stubEmbedding(new float[8]);

        RetrievalOutcome outcome = service.searchInScopeDetailed(
                QUERY, null, List.of(), 5, null, null);

        assertEquals(RetrievalBranchStage.ERROR,
                outcome.branchStages().getFirst().status());
        assertTrue(outcome.results().isEmpty());
    }

    @Test
    void nonFiniteVectorValueIsReportedAsVectorErrorBranch() {
        HybridRetrieverService service = service(null);
        float[] nanVector = new float[1024];
        nanVector[0] = Float.NaN;
        stubEmbedding(nanVector);

        RetrievalOutcome outcome = service.searchInScopeDetailed(
                QUERY, null, List.of(), 5, null, null);

        assertEquals(RetrievalBranchStage.ERROR,
                outcome.branchStages().getFirst().status());
    }

    @Test
    void fulltextProviderFailureIsIsolatedAsErrorBranch() {
        HybridRetrieverService service = service(availableFactory());
        stubEmbedding(validVector());
        // provider.searchInScopeDetailed 抛错 → runFulltext catch →
        // ERROR 分支；向量臂照常成功。
        when(fulltextProvider.searchInScopeDetailed(
                anyString(), any(), any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.anyLong(), any()))
                .thenThrow(new IllegalStateException("fts blew up"));
        RetrievalConfig config = new RetrievalConfig();
        config.setUseHybridSearch(true);
        config.setUseRerank(false);

        RetrievalOutcome outcome = service.searchInScopeDetailed(
                QUERY, RetrievalScope.unscoped(), List.of(), 5, config, null);

        assertEquals(2, outcome.branchStages().size());
        assertEquals(RetrievalBranchStage.SUCCESS,
                outcome.branchStages().get(0).status());
        assertEquals(RetrievalBranchStage.ERROR,
                outcome.branchStages().get(1).status());
        assertTrue(outcome.results().isEmpty());
    }

    @Test
    void fulltextDetailedFailureFlagMapsToErrorBranch() {
        HybridRetrieverService service = service(availableFactory());
        stubEmbedding(validVector());
        when(fulltextProvider.searchInScopeDetailed(
                anyString(), any(), any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.anyLong(), any()))
                .thenReturn(FulltextSearchProvider.SearchResult.failure("FTS_DOWN"));
        RetrievalConfig config = new RetrievalConfig();
        config.setUseHybridSearch(true);
        config.setUseRerank(false);

        RetrievalOutcome outcome = service.searchInScopeDetailed(
                QUERY, RetrievalScope.unscoped(), List.of(), 5, config, null);

        assertEquals(RetrievalBranchStage.ERROR,
                outcome.branchStages().get(1).status());
        assertEquals("FTS_DOWN",
                outcome.branchStages().get(1).errorCode());
    }

    private float[] validVector() {
        float[] vector = new float[1024];
        vector[0] = 0.5f;
        return vector;
    }
}
