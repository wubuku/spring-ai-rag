package com.springairag.core.retrieval.rerank;

import com.springairag.api.dto.RetrievalResult;
import com.springairag.core.config.RagRerankProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HeuristicRerankProviderTest {

    @Test
    void rerank_preservesProvenanceFields() {
        RetrievalResult source = new RetrievalResult();
        source.setDocumentId("1");
        source.setChunkText("matching content");
        source.setScore(0.8);
        source.setSource("pdf-import:uuid/default.md");
        source.setOriginalFilename("manual.pdf");
        source.setFileDirectoryPath("uuid/");
        source.setIndexedFilePath("uuid/default.md");
        source.setOriginalFilePath("uuid/original.pdf");

        RetrievalResult result = new HeuristicRerankProvider(
                new RagRerankProperties())
                .rerank("matching", List.of(source), 1)
                .get(0);

        assertEquals(source.getSource(), result.getSource());
        assertEquals(source.getOriginalFilename(), result.getOriginalFilename());
        assertEquals(source.getFileDirectoryPath(), result.getFileDirectoryPath());
        assertEquals(source.getIndexedFilePath(), result.getIndexedFilePath());
        assertEquals(source.getOriginalFilePath(), result.getOriginalFilePath());
    }
}
