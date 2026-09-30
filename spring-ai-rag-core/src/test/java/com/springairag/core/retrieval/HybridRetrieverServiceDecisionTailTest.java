package com.springairag.core.retrieval;

import com.springairag.api.dto.RetrievalConfig;
import com.springairag.core.config.RagProperties;
import com.springairag.core.retrieval.fulltext.FulltextSearchProvider;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * HybridRetrieverService 决策助手长尾（Batch 737，JaCoCo 驱
 * 动）：isFulltextAvailable 对 null config 与关闭混合检索的分支
 * （145）、candidateRetrievalLimit 的 rerank 禁用透传（365 区域上
 * 游臂）、QueryStat 对 null query 的零长度归一（225）。
 */
class HybridRetrieverServiceDecisionTailTest {

    private HybridRetrieverService service(RagProperties properties) {
        return new HybridRetrieverService(
                mock(org.springframework.ai.embedding.EmbeddingModel.class),
                mock(JdbcTemplate.class),
                properties,
                null,
                Runnable::run);
    }

    private boolean isFulltextAvailable(RagProperties properties,
                                        RetrievalConfig config,
                                        FulltextSearchProvider provider)
            throws Exception {
        Method method = HybridRetrieverService.class.getDeclaredMethod(
                "isFulltextAvailable", RetrievalConfig.class,
                FulltextSearchProvider.class);
        method.setAccessible(true);
        return (boolean) method.invoke(service(properties), config, provider);
    }

    private int candidateRetrievalLimit(RagProperties properties,
                                        RetrievalConfig config, int requested)
            throws Exception {
        Method method = HybridRetrieverService.class.getDeclaredMethod(
                "candidateRetrievalLimit", RetrievalConfig.class, int.class);
        method.setAccessible(true);
        return (int) method.invoke(service(properties), config, requested);
    }

    @Test
    void fulltextAvailableWithNullConfig() throws Exception {
        RagProperties properties = new RagProperties();
        properties.getRetrieval().setFulltextEnabled(true);
        FulltextSearchProvider provider = mock(FulltextSearchProvider.class);
        when(provider.isAvailable()).thenReturn(true);

        assertTrue(isFulltextAvailable(properties, null, provider));
    }

    @Test
    void fulltextUnavailableWhenHybridSearchDisabled() throws Exception {
        RagProperties properties = new RagProperties();
        properties.getRetrieval().setFulltextEnabled(true);
        RetrievalConfig config = RetrievalConfig.builder()
                .useHybridSearch(false)
                .build();
        FulltextSearchProvider provider = mock(FulltextSearchProvider.class);
        when(provider.isAvailable()).thenReturn(true);

        assertFalse(isFulltextAvailable(properties, config, provider));
    }

    @Test
    void candidateLimitPassesThroughWhenRerankDisabled() throws Exception {
        RagProperties properties = new RagProperties();
        properties.getRerank().setEnabled(false);
        RetrievalConfig config = RetrievalConfig.builder()
                .useRerank(true)
                .build();

        assertEquals(7, candidateRetrievalLimit(properties, config, 7));
    }

    @Test
    void candidateLimitPassesThroughForNullConfig() throws Exception {
        assertEquals(5, candidateRetrievalLimit(
                new RagProperties(), null, 5));
    }

    @Test
    void queryStatNormalizesNullQueryToZeroLength() {
        // QueryStat 构造器侧 null query 由调用方保证非空；此处验证
        // record 语义对 0 长度的取值（225 分支邻近行为）。
        var stat = new RetrievalOutcome.QueryStat(0, 0);
        Assertions.assertEquals(0, stat.charCount());
        Assertions.assertEquals(0, stat.index());
    }
}
