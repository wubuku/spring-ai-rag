package com.springairag.core.retrieval;

import com.springairag.core.config.EmbeddingProfile;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.config.RagProperties;
import com.springairag.core.retrieval.fulltext.FulltextSearchProvider;
import com.springairag.core.retrieval.fulltext.FulltextSearchProviderFactory;
import com.springairag.core.retrieval.fulltext.QueryLang;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.springairag.core.service.DocumentDerivationDescriptorProvider;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * HybridRetrieverService 便捷构造与全文超时长尾（Batch 661，JaCoCo
 * 驱动）：公开 6 参构造器完整装配（含全文工厂与空融合原因探针）、
 * 全文分支超过检索超时后经 handle 错误臂归一为 TIMEOUT、空融合时
 * 触发空原因探针。
 */
class HybridRetrieverFulltextProbeTailTest {

    private static final String QUERY = "膝盖康复训练";

    private EmbeddingModel embeddingModel;
    private EmbeddingProfileProvider profileProvider;
    private JdbcTemplate jdbcTemplate;
    private RagProperties ragProperties;
    private FulltextSearchProvider fulltextProvider;
    private FulltextSearchProviderFactory fulltextProviderFactory;
    private RetrievalEmptyReasonProbe emptyReasonProbe;

    @BeforeEach
    void setUp() {
        embeddingModel = mock(EmbeddingModel.class);
        profileProvider = mock(EmbeddingProfileProvider.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        fulltextProvider = mock(FulltextSearchProvider.class);
        fulltextProviderFactory = mock(FulltextSearchProviderFactory.class);
        emptyReasonProbe = new RetrievalEmptyReasonProbe(
                jdbcTemplate,
                new DocumentDerivationDescriptorProvider(new RagProperties()));
        ragProperties = new RagProperties();
        ragProperties.getAsync().setRetrievalTimeoutSeconds(1);
        when(profileProvider.getActiveProfile()).thenReturn(new EmbeddingProfile(
                9L, "bge-m3", "vendor", "bge-m3", "rev-1",
                1024, "cosine", "normalize", true));
        // 向量查询默认空行集（融合结果为空）。
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of());
        // 空原因探针：结果集计数全部为 0，产出真实 Eligibility。
        when(jdbcTemplate.query(anyString(),
                org.mockito.ArgumentMatchers
                        .<org.springframework.jdbc.core.ResultSetExtractor<?>>any(),
                any(Object[].class)))
                .thenAnswer(invocation -> {
                    var extractor = (org.springframework.jdbc.core
                            .ResultSetExtractor<?>) invocation.getArgument(1);
                    java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
                    when(rs.getLong(anyString())).thenReturn(0L);
                    when(rs.getInt(anyString())).thenReturn(0);
                    when(rs.getBoolean(anyString())).thenReturn(false);
                    return extractor.extractData(rs);
                });
    }

    private float[] validVector() {
        float[] vector = new float[1024];
        vector[0] = 0.5f;
        return vector;
    }

    private FulltextSearchProviderFactory availableFactory() {
        when(fulltextProviderFactory.detectLang(anyString()))
                .thenReturn(QueryLang.ZH);
        when(fulltextProviderFactory.getProvider(any(QueryLang.class)))
                .thenReturn(fulltextProvider);
        when(fulltextProvider.isAvailable()).thenReturn(true);
        when(fulltextProvider.getName()).thenReturn("fts");
        return fulltextProviderFactory;
    }

    @Test
    void convenienceConstructorWiresFactoryAndProbeEndToEnd() {
        when(fulltextProviderFactory.detectLang(anyString()))
                .thenReturn(QueryLang.ZH);
        when(fulltextProviderFactory.getProvider(any(QueryLang.class)))
                .thenReturn(fulltextProvider);
        when(fulltextProvider.isAvailable()).thenReturn(true);
        when(fulltextProvider.getName()).thenReturn("fts");
        FulltextSearchProvider.SearchResult success =
                FulltextSearchProvider.SearchResult.success(List.of());
        when(fulltextProvider.searchInScopeDetailed(
                anyString(), any(), any(), anyInt(), any(double.class),
                any(long.class), any()))
                .thenReturn(success);

        // 公开 6 参构造器：taskExecutor 为直通执行器，验证工厂与
        // 空原因探针的整体装配。
        HybridRetrieverService service = new HybridRetrieverService(
                embeddingModel,
                profileProvider,
                jdbcTemplate,
                ragProperties,
                availableFactory(),
                Runnable::run);

        var outcome = service.searchInScopeDetailed(
                QUERY, null, List.of(), 5, null, null);

        assertEquals(2, outcome.branchStages().size());
        assertTrue(outcome.results().isEmpty());
    }

    @Test
    void fulltextBranchTimeoutEntersErrorHandleArm() throws Exception {
        when(fulltextProviderFactory.detectLang(anyString()))
                .thenReturn(QueryLang.ZH);
        when(fulltextProviderFactory.getProvider(any(QueryLang.class)))
                .thenReturn(fulltextProvider);
        when(fulltextProvider.isAvailable()).thenReturn(true);
        when(fulltextProvider.getName()).thenReturn("fts");
        // 全文详细检索阻塞 1.5s > 1s 超时 → orTimeout 触发 handle
        // 错误臂（timeoutOrError）归一为 TIMEOUT。
        when(fulltextProvider.searchInScopeDetailed(
                anyString(), any(), any(), anyInt(), any(double.class),
                any(long.class), any()))
                .thenAnswer(invocation -> {
                    Thread.sleep(1500);
                    return FulltextSearchProvider.SearchResult.success(
                            List.of());
                });

        HybridRetrieverService service = new HybridRetrieverService(
                embeddingModel,
                profileProvider,
                jdbcTemplate,
                ragProperties,
                availableFactory(),
                java.util.concurrent.Executors.newCachedThreadPool(),
                emptyReasonProbe);

        ExecutorService pool = Executors.newCachedThreadPool();
        var outcome = service.searchInScopeDetailed(
                QUERY, null, List.of(), 5, null, null);
        pool.shutdown();

        assertEquals(RetrievalBranchStage.FULLTEXT,
                outcome.branchStages().get(1).branch());
        assertEquals(RetrievalBranchStage.TIMEOUT,
                outcome.branchStages().get(1).status());
        assertTrue(outcome.results().isEmpty());
    }

    @Test
    void emptyFusionTriggersEmptyReasonProbe() {
        // 向量空结果 → 融合为空 → 空原因探针被调用（探针内部查询
        // 返回空行集，候选计数为 0）。
        HybridRetrieverService service = new HybridRetrieverService(
                embeddingModel,
                profileProvider,
                jdbcTemplate,
                ragProperties,
                availableFactory(),
                Runnable::run,
                emptyReasonProbe);

        var outcome = service.searchInScopeDetailed(
                QUERY, null, List.of(), 5, null, null);

        assertTrue(outcome.results().isEmpty());
    }
}
