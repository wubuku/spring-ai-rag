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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

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
        // JDBC 慢查询阻塞到超过 1s 超时 → orTimeout 触发 → VECTOR TIMEOUT。
        //
        // Batch 951：原来是 Thread.sleep(1500) 配一句 assertTrue(elapsed < 3000)。
        // 那是一条单样本墙钟阈值：机器一忙就假失败，而它证明的是"跑得够快"，
        // 不是"超时之后没有继续等这次查询"。改成机制钉——查询确实阻塞着，
        // release 只在收尾才放，所以方法能返回本身就是"它没等查询做完"的证据。
        CountDownLatch queryBlocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                .thenAnswer(invocation -> {
                    queryBlocked.countDown();
                    try {
                        release.await();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    return List.of();
                });

        var pool = java.util.concurrent.Executors.newCachedThreadPool();
        RetrievalOutcome outcome;
        try {
            outcome = service(pool).searchInScopeDetailed(
                    "超时查询", RetrievalScope.unscoped(), List.of(), 5,
                    null, null);
            assertTrue(queryBlocked.await(3, TimeUnit.SECONDS),
                    "慢查询根本没有开始，超时分支没有被建立起来");
            assertEquals(1L, release.getCount(),
                    "查询此刻仍卡在 latch 上：release 只在 finally 放，"
                            + "所以方法能返回就说明它没有等这次查询做完");
        } finally {
            release.countDown();
            pool.shutdown();
        }
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
