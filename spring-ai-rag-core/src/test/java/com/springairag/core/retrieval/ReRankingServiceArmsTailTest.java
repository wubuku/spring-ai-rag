package com.springairag.core.retrieval;

import com.springairag.api.dto.RetrievalResult;
import com.springairag.core.config.RagRerankProperties;
import com.springairag.core.retrieval.rerank.RerankProvider;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 重排服务长尾（Batch 965，JaCoCo 驱动）：测试构造器的 null 容错、
 * maxResults<=0 时回落到配置 topN、 diversification 判定里 provider
 * 名为 null 的归一。
 */
class ReRankingServiceArmsTailTest {

    private RetrievalResult result(String docId, String text, double score) {
        RetrievalResult r = new RetrievalResult();
        r.setDocumentId(docId);
        r.setChunkText(text);
        r.setScore(score);
        r.setVectorScore(score);
        r.setFulltextScore(score);
        return r;
    }

    private List<RetrievalResult> results(int count) {
        List<RetrievalResult> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(result("doc-" + i, "text " + i, 1.0 - i * 0.1));
        }
        return list;
    }

    private RerankProvider passthroughProvider(String name) {
        RerankProvider provider = mock(RerankProvider.class);
        when(provider.getName()).thenReturn(name);
        when(provider.rerank(anyString(), anyList(), anyInt()))
                .thenAnswer(invocation -> List.copyOf(
                        invocation.getArgument(1)));
        return provider;
    }

    @Test
    void testConstructorToleratesNullConfigAndNullProvider() {
        // 双二参构造器下 (null, null) 有歧义，显式 cast 选测试构造器。
        ReRankingService service =
                new ReRankingService((RagRerankProperties) null, (RerankProvider) null);

        // 默认 config.enabled=false → 原样透传，不触碰 provider。
        List<RetrievalResult> input = results(3);
        assertSame(input, service.rerank("q", input, 2));
    }

    @Test
    void maxResultsZeroFallsBackToConfiguredTopN() {
        RagRerankProperties config = new RagRerankProperties();
        config.setEnabled(true);
        config.setTopN(2);
        // preferredMax(默认2) >= finalLimit(2) → 不走 diversification，
        // provider 深度即 finalLimit；透传 provider 无视深度返回 4 条，
        // 由 82-86 行截断回 rankingDepth。
        ReRankingService service = new ReRankingService(
                config, passthroughProvider("mock"));

        List<RetrievalResult> out = service.rerank("q", results(4), 0);

        // maxResults=0 生效：最终条数取配置 topN=2，而不是候选数 4。
        assertEquals(2, out.size());
        assertEquals("doc-0", out.get(0).getDocumentId());
        assertEquals("doc-1", out.get(1).getDocumentId());
    }

    @Test
    void maxResultsZeroWithNonPositiveTopNKeepsAllCandidates() {
        RagRerankProperties config = new RagRerankProperties();
        config.setEnabled(true);
        config.setTopN(0);
        ReRankingService service = new ReRankingService(
                config, passthroughProvider("mock"));

        // maxResults=0 且 topN<=0 → finalLimit 回落到候选数本身，
        // 不发生任何截断。
        List<RetrievalResult> out = service.rerank("q", results(4), 0);

        assertEquals(4, out.size());
    }

    @Test
    void nullProviderNameNormalizedInDiversificationCheck() {
        RagRerankProperties config = new RagRerankProperties();
        config.setEnabled(true);
        config.setCandidateLimit(10);
        config.setPreferredMaxChunksPerDocument(2);
        ReRankingService service = new ReRankingService(
                config, passthroughProvider(null));

        // candidateCount=6 > finalLimit=3、preferredMax=2 < 3、
        // candidateLimit=10 > 3 → 进入名字归一；null → ""，不在
        // no-op 词表 → diversification 生效，按每文档上限 2 选 3 条。
        List<RetrievalResult> out = service.rerank("q", results(6), 3);

        assertEquals(3, out.size());
        assertEquals("doc-0", out.get(0).getDocumentId());
        assertEquals("doc-1", out.get(1).getDocumentId());
        assertEquals("doc-2", out.get(2).getDocumentId());
    }
}
