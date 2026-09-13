package com.springairag.core.retrieval.rerank;

import com.springairag.api.dto.RetrievalResult;
import com.springairag.core.config.RagRerankProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * HttpRerankProvider 残余（Batch 368）：null 配置回退、空结果透
 * 传、深度缺失时取配置 topN、null query/chunkText 空串化、/v1 结
 * 尾基址的短路径、HTTP 失败回退、无启发式回退时按深度截断、
 * mapResponse 的空响应/无结果数组/全无效索引/data 嵌套分支。
 */
class HttpRerankProviderResidualTest {

    private RetrievalResult result(String documentId, String chunkText,
                                   double score) {
        RetrievalResult result = new RetrievalResult();
        result.setDocumentId(documentId);
        result.setChunkText(chunkText);
        result.setScore(score);
        result.setTitle(documentId + " title");
        return result;
    }

    @Test
    void nullConfigFallsBackToDefaults() {
        HttpRerankProvider provider = new HttpRerankProvider(null);

        assertEquals("http", provider.getName());
        // 无 api-key → 不可用。
        assertFalse(provider.isAvailable());
    }

    @Test
    void rerankPassesThroughNullAndEmptyResults() {
        RagRerankProperties props = new RagRerankProperties();
        props.setApiKey("sk");
        props.setBaseUrl("https://rerank.example");
        HttpRerankProvider provider = new HttpRerankProvider(
                props, RestClient.builder().build());

        assertNull(provider.rerank("query", null, 3));
        assertTrue(provider.rerank("query", List.of(), 3).isEmpty());
    }

    @Test
    void rerankFallsBackToHeuristicOnHttpError() {
        RagRerankProperties props = new RagRerankProperties();
        props.setApiKey("sk-test");
        props.setBaseUrl("https://rerank.example");
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server =
                MockRestServiceServer.bindTo(builder).build();
        HttpRerankProvider provider = new HttpRerankProvider(
                props, builder.build());
        List<RetrievalResult> original = List.of(
                result("a", "first", 0.1), result("b", "second", 0.2));

        server.expect(once(), requestTo(containsString("/rerank")))
                .andRespond(withServerError());

        List<RetrievalResult> out = provider.rerank(
                "query", original, 0);

        // HTTP 失败 → 启发式回退仍给出全量结果。
        assertEquals(2, out.size());
        server.verify();
    }

    @Test
    void fallbackWithoutHeuristicTruncatesResultsToDepth() {
        RagRerankProperties props = new RagRerankProperties();
        props.setApiKey("sk-test");
        props.setBaseUrl("https://rerank.example");
        props.setFallbackToHeuristic(false);
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server =
                MockRestServiceServer.bindTo(builder).build();
        HttpRerankProvider provider = new HttpRerankProvider(
                props, builder.build());
        List<RetrievalResult> original = List.of(
                result("a", "first", 0.1), result("b", "second", 0.2));

        server.expect(once(), requestTo(containsString("/rerank")))
                .andRespond(withServerError());

        List<RetrievalResult> out = provider.rerank(
                "query", original, 0);

        // 非启发式回退：深度缺失时保留全部结果。
        assertEquals(2, out.size());
    }

    @Test
    void rerankUsesConfiguredTopNAndShortV1PathWithNullFields()
            throws Exception {
        RagRerankProperties props = new RagRerankProperties();
        props.setApiKey("sk-test");
        props.setBaseUrl("https://rerank.example/v1/");
        props.setModel("test-reranker");
        props.setTopN(2);
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server =
                MockRestServiceServer.bindTo(builder).build();
        HttpRerankProvider provider = new HttpRerankProvider(
                props, builder.build());
        List<RetrievalResult> original = List.of(
                result("a", "first", 0.1),
                result("b", null, 0.2),
                result("c", "third", 0.3));

        // /v1 结尾基址 → 短 /rerank 路径；null query/chunkText 空串化；
        // 深度 0 → topN 取配置值 2。
        server.expect(once(), requestTo("https://rerank.example/v1/rerank"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {
                          "model": "test-reranker",
                          "query": "",
                          "documents": ["first", "", "third"],
                          "top_n": 2
                        }
                        """))
                .andRespond(withSuccess("""
                        {"results":[
                          {"index":0,"relevance_score":0.9},
                          {"index":1,"relevance_score":0.1}
                        ]}
                        """, MediaType.APPLICATION_JSON));

        List<RetrievalResult> out = provider.rerank(null, original, 0);

        assertEquals(2, out.size());
        assertEquals("a", out.get(0).getDocumentId());
        assertEquals(0.9, out.get(0).getScore(), 1e-6);
        server.verify();
    }

    @Test
    void mapResponseRejectsEmptyBodyAndMissingResultsArray()
            throws Exception {
        HttpRerankProvider provider = new HttpRerankProvider(
                new RagRerankProperties(), RestClient.builder().build());
        List<RetrievalResult> original = List.of(result("a", "first", 0.1));

        assertThrows(IllegalStateException.class,
                () -> provider.mapResponse("", original, 3));
        assertThrows(IllegalStateException.class,
                () -> provider.mapResponse("{}", original, 3));
        // 无有效索引 → 同样拒绝。
        assertThrows(IllegalStateException.class,
                () -> provider.mapResponse(
                        "{\"results\":[{\"index\":9,\"score\":0.5}]}",
                        original, 3));
    }

    @Test
    void mapResponseAcceptsResultsNestedUnderData() throws Exception {
        HttpRerankProvider provider = new HttpRerankProvider(
                new RagRerankProperties(), RestClient.builder().build());
        List<RetrievalResult> original = List.of(
                result("a", "first", 0.1), result("b", "second", 0.2));

        List<RetrievalResult> out = provider.mapResponse(
                "{\"data\":{\"results\":[{\"index\":1,\"score\":0.7}]}}",
                original, 3);

        assertEquals(1, out.size());
        assertEquals("b", out.getFirst().getDocumentId());
        assertEquals(0.7, out.getFirst().getScore(), 1e-6);
    }

    @Test
    void testVisibleConstructorAlsoToleratesNullConfig() {
        HttpRerankProvider provider = new HttpRerankProvider(
                null, RestClient.builder().build());

        assertFalse(provider.isAvailable());
        assertSame(Boolean.FALSE, Boolean.valueOf(provider.isAvailable()));
    }
}
