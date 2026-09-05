package com.springairag.core.rag;

import com.springairag.core.chat.AuthorizedRetrievalContext;
import com.springairag.core.chat.ChatPrincipal;
import com.springairag.core.chat.RetrievalTraceCollector;
import com.springairag.core.resource.StaticKnowledgeCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 覆盖组合检索器：授权上下文校验、检索预算门、project 与静态知识
 * 合并去重以及组合上下文标记的传递。
 */
class CompositeChatDocumentRetrieverTest {

    private final ProjectDocumentRetriever projectRetriever =
            mock(ProjectDocumentRetriever.class);
    private final StaticKnowledgeCatalog catalog = mock(StaticKnowledgeCatalog.class);
    private final StaticKnowledgeDocumentRetriever staticRetriever =
            new StaticKnowledgeDocumentRetriever(
                    catalog, new RetrievalDocumentMapper());
    private final CompositeChatDocumentRetriever retriever =
            new CompositeChatDocumentRetriever(projectRetriever, staticRetriever);

    private Query queryWithContext(AuthorizedRetrievalContext context) {
        return Query.builder()
                .text("question")
                .context(Map.of(ProjectDocumentRetriever.CONTEXT_KEY, context))
                .build();
    }

    @Test
    void rejectsQueryWithoutAuthorizedContext() {
        Query query = Query.builder().text("q").build();

        assertThrows(IllegalStateException.class, () -> retriever.retrieve(query));
    }

    @Test
    void returnsEmptyAndSkipsRetrieversWhenBudgetExhausted() {
        RetrievalTraceCollector trace = new RetrievalTraceCollector(1, 1, 10);
        assertTrue(trace.tryBeginRetrieval("burn"));
        AuthorizedRetrievalContext context = new AuthorizedRetrievalContext(
                null, null, trace, "s1", ChatPrincipal.local());

        List<Document> documents = retriever.retrieve(queryWithContext(context));

        assertTrue(documents.isEmpty());
        verifyNoInteractions(projectRetriever);
    }

    @Test
    void mergesProjectAndStaticResultsAndMarksCompositeContext() {
        AuthorizedRetrievalContext context = new AuthorizedRetrievalContext(
                null, null, new RetrievalTraceCollector(), "s1", ChatPrincipal.local());
        Document projectDoc = Document.builder()
                .id("project:1")
                .text("project body")
                .build();
        when(projectRetriever.retrieve(any(Query.class))).thenAnswer(invocation -> {
            Query received = invocation.getArgument(0);
            // 组合标记与原上下文键都必须随查询传递。
            assertEquals(Boolean.TRUE,
                    received.context().get(
                            CompositeChatDocumentRetriever.COMPOSITE_RETRIEVAL_CONTEXT_KEY));
            assertEquals(context,
                    received.context().get(ProjectDocumentRetriever.CONTEXT_KEY));
            return List.of(projectDoc);
        });
        when(catalog.search(any(), anyInt(), anyInt()))
                .thenReturn(List.of(Document.builder()
                        .id("static:1")
                        .text("static body")
                        .build()));

        List<Document> documents = retriever.retrieve(queryWithContext(context));

        assertEquals(List.of("project:1", "static:1"),
                documents.stream().map(Document::getId).toList());
    }

    @Test
    void deduplicatesDocumentsByIdKeepingFirstOccurrence() {
        AuthorizedRetrievalContext context = new AuthorizedRetrievalContext(
                null, null, new RetrievalTraceCollector(), "s1", ChatPrincipal.local());
        Document projectVersion = Document.builder()
                .id("shared:1")
                .text("project wins")
                .build();
        when(projectRetriever.retrieve(any(Query.class)))
                .thenReturn(List.of(projectVersion));
        when(catalog.search(any(), anyInt(), anyInt()))
                .thenReturn(List.of(Document.builder()
                        .id("shared:1")
                        .text("static duplicate")
                        .build()));

        List<Document> documents = retriever.retrieve(queryWithContext(context));

        assertEquals(1, documents.size());
        assertEquals("project wins", documents.getFirst().getText());
    }
}
