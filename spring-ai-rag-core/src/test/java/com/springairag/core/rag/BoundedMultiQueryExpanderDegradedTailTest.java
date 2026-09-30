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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 有界多查询扩展长尾（Batch 732，JaCoCo 驱动）：委托失败的降
 * 级、null/空扩展结果的降级、includeOriginal=false 时原始查询不
 * 预置（101/115-136 各臂）。
 */
class BoundedMultiQueryExpanderDegradedTailTest {

    private Query query(String text) {
        return Query.builder().text(text).build();
    }

    private AuthorizedRetrievalContext context(
            RetrievalTraceCollector trace) {
        return new AuthorizedRetrievalContext(
                RetrievalScope.unscoped(),
                new RetrievalOptions(5, 0.3, true, true, 0.5, 0.5),
                trace,
                "bounded-expansion",
                ChatPrincipal.local(),
                24_000,
                RetrievalFilters.none());
    }

    private Query input(RetrievalTraceCollector trace) {
        Map<String, Object> queryContext = Map.of(
                ProjectDocumentRetriever.CONTEXT_KEY, context(trace));
        return Query.builder()
                .text("原始问题")
                .history(new UserMessage("历史问题"))
                .context(queryContext)
                .build();
    }

    @Test
    void delegateFailureFallsBackToOriginalAndMarksDegraded() {
        var trace = new RetrievalTraceCollector();
        trace.configureQueryExpansion(5, 2, true, 3, 3, true);
        var delegate = mock(
                org.springframework.ai.rag.preretrieval.query.expansion.QueryExpander.class);
        when(delegate.expand(any())).thenThrow(
                new IllegalStateException("llm down"));

        BoundedMultiQueryExpander expander =
                new BoundedMultiQueryExpander(delegate, 3, true);

        List<Query> expanded = expander.expand(input(trace));

        assertEquals(List.of("原始问题"),
                expanded.stream().map(Query::text).toList());
    }

    @Test
    void nullExpansionMarksDegradedAndFallsBackToOriginal() {
        var trace = new RetrievalTraceCollector();
        trace.configureQueryExpansion(5, 2, true, 3, 3, true);
        var delegate = mock(
                org.springframework.ai.rag.preretrieval.query.expansion.QueryExpander.class);
        when(delegate.expand(any())).thenReturn(null);

        BoundedMultiQueryExpander expander =
                new BoundedMultiQueryExpander(delegate, 3, true);

        List<Query> expanded = expander.expand(input(trace));

        assertEquals(List.of("原始问题"),
                expanded.stream().map(Query::text).toList());
    }

    @Test
    void includeOriginalFalseKeepsAllDistinctVariants() {
        var trace = new RetrievalTraceCollector();
        trace.configureQueryExpansion(5, 2, false, 3, 3, true);
        Query input = input(trace);
        var delegate = mock(
                org.springframework.ai.rag.preretrieval.query.expansion.QueryExpander.class);
        when(delegate.expand(input)).thenReturn(List.of(
                query("原始问题"),
                query("alpha"),
                query("beta")));

        BoundedMultiQueryExpander expander =
                new BoundedMultiQueryExpander(delegate, 3, false);

        List<Query> expanded = expander.expand(input);

        // includeOriginal=false：原始问题不预置，作为普通变体去重后保留。
        assertEquals(List.of("原始问题", "alpha", "beta"),
                expanded.stream().map(Query::text).toList());
        assertTrue(trace.toString() != null);
    }
}
