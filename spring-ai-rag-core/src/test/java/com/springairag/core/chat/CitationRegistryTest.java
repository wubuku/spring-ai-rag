package com.springairag.core.chat;

import com.springairag.api.dto.ChatSource;
import com.springairag.api.dto.RetrievalResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CitationRegistryTest {

    @Test
    void assignsStableDualAliasesAndReusesDuplicateSource() {
        CitationRegistry registry = new CitationRegistry(2);
        RetrievalResult first = result("doc-1", 0, 0.2);
        RetrievalResult duplicate = result("doc-1", 0, 0.8);

        assertEquals(
                new CitationRegistry.Registration("doc-1:0", "c1", "S1"),
                registry.register(first));
        assertEquals(
                new CitationRegistry.Registration("doc-1:0", "c1", "S1"),
                registry.register(duplicate));
        assertEquals("c1", registry.sourceHandle(duplicate));
        assertEquals("S1", registry.citationId(duplicate));
    }

    @Test
    void excludesSourcesBeyondBudgetAndProjectsFinalHandlesOnly() {
        CitationRegistry registry = new CitationRegistry(1);
        RetrievalResult first = result("doc-1", 0, 0.2);
        RetrievalResult second = result("doc-2", 0, 0.9);

        registry.register(first);
        assertNull(registry.register(second));
        assertNull(registry.sourceHandle(second));

        ChatSource source = new ChatSource();
        source.setSourceHandle("c1");
        Map<String, Object> metadata = registry.metadataFor(List.of(source));
        assertEquals(1, metadata.get("version"));
        assertEquals("c", metadata.get("handlePrefix"));
        assertEquals(1, metadata.get("sourceCount"));
        assertEquals(List.of("c1"), metadata.get("handles"));
    }

    @Test
    void missingChunkIndexDoesNotRegisterOrConsumeBudget() {
        CitationRegistry registry = new CitationRegistry(1);
        RetrievalResult missing = new RetrievalResult();
        missing.setDocumentId("doc-missing-index");

        assertNull(registry.register(missing));
        assertNull(registry.sourceHandle(missing));
        assertEquals(
                new CitationRegistry.Registration("doc-zero:0", "c1", "S1"),
                registry.register(result("doc-zero", 0, 0.8)));
    }

    private static RetrievalResult result(String documentId, int chunkIndex, double score) {
        RetrievalResult result = new RetrievalResult();
        result.setDocumentId(documentId);
        result.setChunkIndex(chunkIndex);
        result.setScore(score);
        return result;
    }
}
