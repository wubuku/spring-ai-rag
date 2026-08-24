package com.springairag.core.rag;

import com.springairag.core.chat.AuthorizedRetrievalContext;
import com.springairag.core.chat.ChatPrincipal;
import com.springairag.core.chat.RetrievalOptions;
import com.springairag.core.chat.RetrievalTraceCollector;
import com.springairag.core.retrieval.RetrievalFilters;
import com.springairag.core.retrieval.RetrievalScope;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.rag.Query;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BoundedMultiQueryExpanderTest {

    @Test
    void trimsDeduplicatesAndPreservesQueryContext() {
        RetrievalTraceCollector trace = new RetrievalTraceCollector();
        AuthorizedRetrievalContext context = new AuthorizedRetrievalContext(
                RetrievalScope.unscoped(),
                new RetrievalOptions(5, 0.3, true, true, 0.5, 0.5),
                trace,
                "bounded-expansion",
                ChatPrincipal.local(),
                24_000,
                RetrievalFilters.none());
        Map<String, Object> queryContext = Map.of(
                ProjectDocumentRetriever.CONTEXT_KEY, context,
                "caller", "test");
        Query input = Query.builder()
                .text("  原始问题  ")
                .history(new UserMessage("历史问题"))
                .context(queryContext)
                .build();
        var delegate = mock(
                org.springframework.ai.rag.preretrieval.query.expansion.QueryExpander.class);
        when(delegate.expand(input)).thenReturn(List.of(
                Query.builder().text("原始问题").build(),
                Query.builder().text("  alpha  ").build(),
                Query.builder().text("alpha").build(),
                Query.builder().text("beta").build(),
                Query.builder().text("gamma").build()));
        trace.configureQueryExpansion(5, 2, true, 3, 3, true);

        BoundedMultiQueryExpander expander =
                new BoundedMultiQueryExpander(delegate, 3, true);

        List<Query> expanded = expander.expand(input);

        assertEquals(
                List.of("原始问题", "alpha", "beta"),
                expanded.stream().map(Query::text).toList());
        assertSame(queryContext, expanded.get(0).context());
        assertSame(queryContext, expanded.get(1).context());
        assertSame(queryContext, expanded.get(2).context());
        assertEquals(
                List.of(new UserMessage("历史问题")),
                expanded.get(0).history());
        assertEquals(1, trace.queryExpansion().get("duplicateVariantsRemoved"));
    }

    @Test
    void delegateFailureFallsBackToInputAndRecordsDegradedSummary() {
        RetrievalTraceCollector trace = new RetrievalTraceCollector();
        AuthorizedRetrievalContext context = new AuthorizedRetrievalContext(
                RetrievalScope.unscoped(),
                new RetrievalOptions(5, 0.3, true, true, 0.5, 0.5),
                trace,
                "bounded-failure",
                ChatPrincipal.local());
        Query input = Query.builder()
                .text("原始问题")
                .context(Map.of(ProjectDocumentRetriever.CONTEXT_KEY, context))
                .build();
        var delegate = mock(
                org.springframework.ai.rag.preretrieval.query.expansion.QueryExpander.class);
        when(delegate.expand(input)).thenThrow(new IllegalStateException("provider down"));
        trace.configureQueryExpansion(5, 2, false, 2, 2, true);

        List<Query> expanded = new BoundedMultiQueryExpander(
                delegate, 2, false).expand(input);

        assertEquals(List.of("原始问题"), expanded.stream().map(Query::text).toList());
        assertTrue((Boolean) trace.queryExpansion().get("degraded"));
        assertEquals(0, trace.queryExpansion().get("duplicateVariantsRemoved"));
    }

    @Test
    void emptyDelegateOutputIsADegradedInputFallback() {
        RetrievalTraceCollector trace = new RetrievalTraceCollector();
        AuthorizedRetrievalContext context = new AuthorizedRetrievalContext(
                RetrievalScope.unscoped(),
                new RetrievalOptions(5, 0.3, true, true, 0.5, 0.5),
                trace,
                "bounded-empty",
                ChatPrincipal.local());
        Query input = Query.builder()
                .text("原始问题")
                .context(Map.of(ProjectDocumentRetriever.CONTEXT_KEY, context))
                .build();
        var delegate = mock(
                org.springframework.ai.rag.preretrieval.query.expansion.QueryExpander.class);
        when(delegate.expand(input)).thenReturn(List.of());
        trace.configureQueryExpansion(2, 2, false, 2, 2, false);

        assertEquals(
                List.of(input),
                new BoundedMultiQueryExpander(delegate, 2, false).expand(input));
        assertTrue((Boolean) trace.queryExpansion().get("degraded"));
    }
}
