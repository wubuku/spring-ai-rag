package com.springairag.core.retrieval;

import com.springairag.core.config.EmbeddingProfile;
import com.springairag.core.config.EmbeddingProfileProvider;
import com.springairag.core.config.RagProperties;
import com.springairag.core.retrieval.fulltext.FulltextSearchProvider;
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
 * HybridRetrieverService orTimeout 超时分支（Batch 510，JaCoCo 驱
 * 动）：retrievalTimeoutSeconds=1 且向量 JDBC 慢查询 → 超时经
 * handle 归一为 VECTOR ERROR 分支（timeout warn 路径），结果为空
 * 但整体不抛。
 */
class HybridRetrieverVectorTimeoutTailTest {

    private EmbeddingModel embeddingModel;
    private EmbeddingProfileProvider profileProvider;
    private JdbcTemplate jdbcTemplate;
    private RagProperties ragProperties;

    @BeforeEach
    void setUp() {
        embeddingModel = mock(EmbeddingModel.class);
        profileProvider = mock(EmbeddingProfileProvider.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        ragProperties = new RagProperties();
        ragProperties.getAsync().setRetrievalTimeoutSeconds(1);
        when(profileProvider.getActiveProfile()).thenReturn(new EmbeddingProfile(
                9L, "bge-m3", "vendor", "bge-m3", "rev-1",
                1024, "cosine", "normalize", true));
        when(embeddingModel.embed(anyString())).thenReturn(validVector());
    }

    private float[] validVector() {
        float[] vector = new float[1024];
        vector[0] = 0.5f;
        return vector;
    }

    private HybridRetrieverService service(Executor executor) {
        return new HybridRetrieverService(
                embeddingModel,
                profileProvider,
                jdbcTemplate,
                ragProperties,
                null,
                executor,
                null);
    }

    @Test
    void slowVectorQueryTimesOutIntoErrorBranch() throws Exception {
        // JDBC 慢查询 1.5s > 1s 超时 → orTimeout 触发 → VECTOR ERROR。
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                .thenAnswer(invocation -> {
                    Thread.sleep(1500);
                    return List.of();
                });

        long startedAt = System.currentTimeMillis();
        var pool = java.util.concurrent.Executors.newCachedThreadPool();
        RetrievalOutcome outcome = service(pool).searchInScopeDetailed(
                "超时查询", RetrievalScope.unscoped(), List.of(), 5,
                null, null);
        pool.shutdown();
        long elapsed = System.currentTimeMillis() - startedAt;

        assertTrue(elapsed < 3000, "应在超时后快速返回: " + elapsed);
        // 超时归一为独立 TIMEOUT 状态（区别于一般 ERROR）。
        assertEquals(2, outcome.branchStages().size());
        assertEquals(RetrievalBranchStage.TIMEOUT,
                outcome.branchStages().get(0).status());
        assertTrue(outcome.results().isEmpty());
    }

    @Test
    void fastVectorQueryWithinTimeoutSucceeds() throws Exception {
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                .thenAnswer(invocation -> {
                    Thread.sleep(50);
                    return List.of();
                });

        var pool = java.util.concurrent.Executors.newCachedThreadPool();
        RetrievalOutcome outcome = service(pool).searchInScopeDetailed(
                "快速查询", RetrievalScope.unscoped(), List.of(), 5,
                null, null);
        pool.shutdown();

        assertEquals(RetrievalBranchStage.SUCCESS,
                outcome.branchStages().get(0).status());
    }
}
