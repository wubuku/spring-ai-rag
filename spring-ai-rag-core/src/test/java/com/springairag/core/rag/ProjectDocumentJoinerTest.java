package com.springairag.core.rag;

import com.springairag.core.chat.AuthorizedRetrievalContext;
import com.springairag.core.chat.ChatPrincipal;
import com.springairag.core.chat.RetrievalOptions;
import com.springairag.core.chat.RetrievalTraceCollector;
import com.springairag.core.retrieval.RetrievalScope;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProjectDocumentJoinerTest {

    private final ProjectDocumentJoiner joiner = new ProjectDocumentJoiner();

    @Test
    void keepsHighestScoreAndCanonicalTieAcrossMapOrder() {
        RetrievalTraceCollector trace = new RetrievalTraceCollector();
        Document sharedLow = document("shared", "shared-low", 0.4);
        Document sharedHigh = document("shared", "shared-high", 0.9);
        Document tieA = document("tie", "tie-a", 0.7);
        Document tieZ = document("tie", "tie-z", 0.7);
        Document idA = document("a", "a", 0.7);
        Document idB = document("b", "b", 0.7);
        Query queryA = query("a-query", trace);
        Query queryZ = query("z-query", trace);
        Map<Query, List<List<Document>>> reverseInsertion = new LinkedHashMap<>();
        reverseInsertion.put(
                queryZ,
                List.of(List.of(sharedHigh, tieZ, idB)));
        reverseInsertion.put(
                queryA,
                List.of(List.of(sharedLow, tieA, idA)));

        List<Document> output = joiner.join(reverseInsertion);

        assertEquals(
                List.of(sharedHigh, idA, idB, tieA),
                output);
        assertEquals(
                Map.of(
                        "inputDocuments", 6,
                        "uniqueDocuments", 4,
                        "duplicateDocumentsRemoved", 2,
                        "scoreReplacements", 1),
                trace.documentJoin());

        Map<Query, List<List<Document>>> forwardInsertion = new LinkedHashMap<>();
        forwardInsertion.put(
                new Query("a-query"),
                List.of(List.of(sharedLow, tieA, idA)));
        forwardInsertion.put(
                new Query("z-query"),
                List.of(List.of(sharedHigh, tieZ, idB)));
        assertEquals(output, joiner.join(forwardInsertion));
    }

    @Test
    void keepsNullAndBlankIdentityDocumentsSeparateAndOrdersFiniteBeforeInvalid() {
        Document identifiedFinite = document("identified", "finite", -1.0);
        Document anonymousFinite = anonymous("anonymous-finite", -1.0);
        Document identifiedInvalid = document(
                "invalid", "invalid", Double.NaN);
        Document anonymousInvalid = anonymous("anonymous-invalid", null);
        Document blankInvalidA = anonymous(
                " ", "blank-invalid-a", null);
        Document blankInvalidB = anonymous(
                " ", "blank-invalid-b", Double.NaN);

        List<Document> output = joiner.join(Map.of(
                new Query("query"),
                List.of(List.of(
                        anonymousInvalid,
                        blankInvalidA,
                        blankInvalidB,
                        identifiedInvalid,
                        anonymousFinite,
                        identifiedFinite))));

        assertEquals(6, output.size());
        assertSame(identifiedFinite, output.get(0));
        assertSame(anonymousFinite, output.get(1));
        assertSame(identifiedInvalid, output.get(2));
        assertSame(anonymousInvalid, output.get(3));
        assertSame(blankInvalidA, output.get(4));
        assertSame(blankInvalidB, output.get(5));
    }

    @Test
    void invalidScoresKeepCanonicalFirstWithoutReplacement() {
        RetrievalTraceCollector trace = new RetrievalTraceCollector();
        Document first = document("same", "first", Double.NaN);
        Document second = document(
                "same", "second", Double.POSITIVE_INFINITY);

        List<Document> output = joiner.join(Map.of(
                query("query", trace),
                List.of(List.of(first, second))));

        assertEquals(List.of(first), output);
        assertEquals(0, trace.documentJoin().get("scoreReplacements"));
        assertEquals(1, trace.documentJoin().get("duplicateDocumentsRemoved"));
    }

    @Test
    void finiteScoreReplacesInvalidScoreEvenWhenNegative() {
        RetrievalTraceCollector trace = new RetrievalTraceCollector();
        Document invalid = document("same", "invalid", Double.NaN);
        Document finite = document("same", "finite", -2.0);

        List<Document> output = joiner.join(Map.of(
                query("query", trace),
                List.of(List.of(invalid, finite))));

        assertEquals(List.of(finite), output);
        assertEquals(1, trace.documentJoin().get("scoreReplacements"));
    }

    private Query query(String text, RetrievalTraceCollector trace) {
        AuthorizedRetrievalContext context =
                new AuthorizedRetrievalContext(
                        RetrievalScope.unscoped(),
                        new RetrievalOptions(
                                5, 0.0, true, true, 0.5, 0.5),
                        trace,
                        "document-join",
                        ChatPrincipal.local());
        return Query.builder()
                .text(text)
                .context(Map.of(
                        ProjectDocumentRetriever.CONTEXT_KEY,
                        context))
                .build();
    }

    private Document document(String id, String text, Double score) {
        Document.Builder builder = Document.builder()
                .id(id)
                .text(text)
                .metadata(Map.of("label", text));
        if (score != null) {
            builder.score(score);
        }
        return builder.build();
    }

    private Document anonymous(String text, Double score) {
        return anonymous(null, text, score);
    }

    private Document anonymous(String id, String text, Double score) {
        Document document = mock(Document.class);
        when(document.getId()).thenReturn(id);
        when(document.getText()).thenReturn(text);
        when(document.getScore()).thenReturn(score);
        when(document.getMetadata()).thenReturn(Map.of("label", text));
        return document;
    }
}
