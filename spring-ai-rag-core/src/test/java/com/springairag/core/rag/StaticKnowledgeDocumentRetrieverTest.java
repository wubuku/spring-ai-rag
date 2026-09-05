package com.springairag.core.rag;

import com.springairag.core.chat.AuthorizedRetrievalContext;
import com.springairag.core.chat.ChatPrincipal;
import com.springairag.core.chat.RetrievalTraceCollector;
import com.springairag.core.resource.StaticKnowledgeChunk;
import com.springairag.core.resource.StaticKnowledgeCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 覆盖静态知识检索器：授权上下文校验、目录检索接线与可用性判定。
 */
class StaticKnowledgeDocumentRetrieverTest {

    private final StaticKnowledgeCatalog catalog = mock(StaticKnowledgeCatalog.class);
    private final RetrievalDocumentMapper mapper = new RetrievalDocumentMapper();
    private final StaticKnowledgeDocumentRetriever retriever =
            new StaticKnowledgeDocumentRetriever(catalog, mapper);

    @Test
    void rejectsQueryWithoutAuthorizedContext() {
        Query query = Query.builder().text("q").build();

        assertThrows(IllegalStateException.class, () -> retriever.retrieve(query));
    }

    @Test
    void retrievesFromCatalogAndRecordsTrace() {
        Document document = Document.builder()
                .id("static:1")
                .text("static knowledge")
                .metadata(Map.of("title", "Static", "chunkIndex", 0))
                .build();
        when(catalog.search(anyString(), anyInt(), anyInt()))
                .thenReturn(List.of(document));
        AuthorizedRetrievalContext context = new AuthorizedRetrievalContext(
                null, null, new RetrievalTraceCollector(), "s1", ChatPrincipal.local());

        List<Document> documents = retriever.retrieve(
                "knowledge query", context);

        assertEquals(1, documents.size());
        assertEquals("static knowledge", documents.getFirst().getText());
        verify(catalog).search("knowledge query", 5, 24_000);
    }

    @Test
    void retrieveViaQueryDelegatesWithContext() {
        Document document = Document.builder()
                .id("static:2")
                .text("body")
                .build();
        when(catalog.search(anyString(), anyInt(), anyInt()))
                .thenReturn(List.of(document));
        AuthorizedRetrievalContext context = new AuthorizedRetrievalContext(
                null, null, new RetrievalTraceCollector(), "s1", ChatPrincipal.local());
        Query query = Query.builder()
                .text("q")
                .context(Map.of(ProjectDocumentRetriever.CONTEXT_KEY, context))
                .build();

        List<Document> documents = retriever.retrieve(query);

        assertEquals(1, documents.size());
    }

    @Test
    void enabledRequiresHealthySnapshotWithChunks() {
        when(catalog.snapshot()).thenReturn(new StaticKnowledgeCatalog.Snapshot(
                1, "d", true, List.of(mock(StaticKnowledgeChunk.class))));
        assertTrue(retriever.enabled());

        when(catalog.snapshot()).thenReturn(new StaticKnowledgeCatalog.Snapshot(
                1, "d", false, List.of(mock(StaticKnowledgeChunk.class))));
        assertFalse(retriever.enabled());

        when(catalog.snapshot()).thenReturn(new StaticKnowledgeCatalog.Snapshot(
                1, "d", true, List.of()));
        assertFalse(retriever.enabled());
    }
}
