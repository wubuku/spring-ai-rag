package com.springairag.core.retrieval.rerank;

import com.springairag.api.dto.RetrievalResult;
import com.springairag.core.config.RagRerankProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

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
        assertEquals(original.get(2).getSource(), out.get(0).getSource());
        assertEquals(original.get(2).getOriginalFilename(),
                out.get(0).getOriginalFilename());
        assertEquals(original.get(2).getOriginalFilePath(),
                out.get(0).getOriginalFilePath());
        assertEquals("a", out.get(1).getDocumentId());
    }

    private static RetrievalResult result(String id, String text, double score) {
        RetrievalResult r = new RetrievalResult();
        r.setDocumentId(id);
        r.setChunkText(text);
        r.setScore(score);
        return r;
    }
}
