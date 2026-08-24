package com.springairag.core.retrieval.rerank;

import com.springairag.api.dto.RetrievalResult;
import com.springairag.core.config.RagRerankProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class HttpRerankProviderTest {

    @Test
    void isAvailable_requiresKeyAndBaseUrl() {
        RagRerankProperties props = new RagRerankProperties();
        props.setApiKey("");
        props.setBaseUrl("https://api.siliconflow.cn");
        assertFalse(new HttpRerankProvider(props).isAvailable());

        props.setApiKey("sk-test");
        assertTrue(new HttpRerankProvider(props).isAvailable());
    }

    @Test
    void mapResponse_reordersByRelevanceScore() throws Exception {
        RagRerankProperties props = new RagRerankProperties();
        props.setApiKey("sk");
        HttpRerankProvider p = new HttpRerankProvider(props);

        List<RetrievalResult> original = List.of(
                result("a", "first", 0.1),
                result("b", "second", 0.2),
                result("c", "third", 0.3)
        );
        original.get(2).setTitle("Third document");
        original.get(2).setSource("pdf-import:uuid-c/default.md");
        original.get(2).setOriginalFilename("third.pdf");
        original.get(2).setFileDirectoryPath("uuid-c/");
        original.get(2).setIndexedFilePath("uuid-c/default.md");
        original.get(2).setOriginalFilePath("uuid-c/original.pdf");
        String json = """
                {"results":[
                  {"index":2,"relevance_score":0.99},
                  {"index":0,"relevance_score":0.5},
                  {"index":1,"relevance_score":0.1}
                ]}
                """;
        List<RetrievalResult> out = p.mapResponse(json, original, 2);
        assertEquals(2, out.size());
        assertEquals("c", out.get(0).getDocumentId());
        assertEquals(0.99, out.get(0).getScore(), 1e-6);
        assertEquals(original.get(2).getTitle(), out.get(0).getTitle());
        assertEquals(original.get(2).getSource(), out.get(0).getSource());
        assertEquals(original.get(2).getOriginalFilename(),
                out.get(0).getOriginalFilename());
        assertEquals(original.get(2).getOriginalFilePath(),
                out.get(0).getOriginalFilePath());
        assertEquals("a", out.get(1).getDocumentId());
    }

    @Test
    void rerank_sendsRankingDepthAsTopNAndMapsFullProviderRanking() {
        RagRerankProperties props = new RagRerankProperties();
        props.setApiKey("sk-test");
        props.setBaseUrl("https://rerank.example");
        props.setModel("test-reranker");
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        HttpRerankProvider provider =
                new HttpRerankProvider(props, builder.build());
        List<RetrievalResult> original = List.of(
                result("a", "first", 0.1),
                result("b", "second", 0.2),
                result("c", "third", 0.3));
        original.get(0).setTitle("First title");
        original.get(1).setTitle("Second title");
        original.get(2).setTitle("Third title");

        server.expect(once(), requestTo("https://rerank.example/v1/rerank"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("""
                        {
                          "model": "test-reranker",
                          "query": "query",
                          "documents": ["first", "second", "third"],
                          "top_n": 3
                        }
                        """, true))
                .andRespond(withSuccess(
                        """
                        {"results":[
                          {"index":1,"relevance_score":0.99},
                          {"index":2,"relevance_score":0.88},
                          {"index":0,"relevance_score":0.77}
                        ]}
                        """,
                        org.springframework.http.MediaType.APPLICATION_JSON));

        List<RetrievalResult> output =
                provider.rerank("query", original, 3);

        assertEquals(List.of("b", "c", "a"), output.stream()
                .map(RetrievalResult::getDocumentId)
                .toList());
        assertEquals(List.of(0.99, 0.88, 0.77), output.stream()
                .map(RetrievalResult::getScore)
                .toList());
        assertEquals("Second title", output.getFirst().getTitle());
        server.verify();
    }

    @Test
    void unavailableProviderFallbackRespectsRankingDepth() {
        RagRerankProperties props = new RagRerankProperties();
        props.setApiKey("");
        props.setFallbackToHeuristic(false);
        HttpRerankProvider provider = new HttpRerankProvider(props);
        List<RetrievalResult> original = List.of(
                result("a", "first", 0.1),
                result("b", "second", 0.2),
                result("c", "third", 0.3),
                result("d", "fourth", 0.4));

        List<RetrievalResult> output =
                provider.rerank("query", original, 3);

        assertEquals(List.of("a", "b", "c"), output.stream()
                .map(RetrievalResult::getDocumentId)
                .toList());
    }

    @Test
    void unavailableProviderUsesCjkAwareHeuristicFallback() {
        RagRerankProperties props = new RagRerankProperties();
        props.setApiKey("");
        props.setDiversityWeight(0.2f);
        HttpRerankProvider provider = new HttpRerankProvider(props);
        List<RetrievalResult> original = List.of(
                result(
                        "distractor",
                        "账户权限审批流程和用量账本统计",
                        1.0),
                result(
                        "relevant",
                        "检索质量需要结合中文分词进行优化",
                        0.99));

        List<RetrievalResult> output = provider.rerank(
                "中文检索质量优化",
                original,
                2);

        assertEquals("relevant", output.getFirst().getDocumentId());
    }

    @Test
    void unavailableProviderUsesTitleAwareHeuristicFallback() {
        RagRerankProperties props = new RagRerankProperties();
        props.setApiKey("");
        props.setDiversityWeight(0.2f);
        HttpRerankProvider provider = new HttpRerankProvider(props);
        RetrievalResult distractor = result(
                "distractor",
                "shared neutral maintenance evidence",
                1.0);
        distractor.setTitle("Account approval handbook");
        RetrievalResult relevant = result(
                "relevant",
                "shared neutral maintenance evidence",
                0.99);
        relevant.setTitle("ZX-9042 液压校准规范");

        List<RetrievalResult> output = provider.rerank(
                "ZX-9042 液压校准",
                List.of(distractor, relevant),
                2);

        assertEquals("relevant", output.getFirst().getDocumentId());
    }

    @Test
    void unavailableProviderUsesBoundaryAwareHeuristicFallback() {
        RagRerankProperties props = new RagRerankProperties();
        props.setApiKey("");
        props.setDiversityWeight(0.2f);
        HttpRerankProvider provider = new HttpRerankProvider(props);
        RetrievalResult distractor = result(
                "distractor",
                "shared neutral evidence",
                1.0);
        distractor.setTitle("Storage Chair 19042");
        RetrievalResult relevant = result(
                "relevant",
                "shared neutral evidence",
                0.99);
        relevant.setTitle("RAG AI ZX-9042");

        List<RetrievalResult> output = provider.rerank(
                "RAG AI 9042",
                List.of(distractor, relevant),
                2);

        assertEquals("relevant", output.getFirst().getDocumentId());
    }

    private static RetrievalResult result(String id, String text, double score) {
        RetrievalResult r = new RetrievalResult();
        r.setDocumentId(id);
        r.setChunkText(text);
        r.setScore(score);
        return r;
    }
}
