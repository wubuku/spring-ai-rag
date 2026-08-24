package com.springairag.core.rag;

import com.springairag.core.chat.AuthorizedRetrievalContext;
import com.springairag.core.chat.RetrievalTraceCollector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.expansion.QueryExpander;
import org.springframework.util.Assert;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Keeps Spring AI multi-query expansion bounded and deterministic.
 *
 * <p>The delegate remains responsible for model interaction. This adapter only
 * preserves the server-owned query context, removes blank and exact duplicate
 * variants, and records low-cardinality expansion diagnostics.</p>
 */
public final class BoundedMultiQueryExpander implements QueryExpander {

    private static final Logger log =
            LoggerFactory.getLogger(BoundedMultiQueryExpander.class);

    private final QueryExpander delegate;
    private final int plannedQueries;
    private final boolean includeOriginal;

    public BoundedMultiQueryExpander(
            QueryExpander delegate,
            int plannedQueries,
            boolean includeOriginal) {
        this.delegate = Objects.requireNonNull(delegate, "delegate cannot be null");
        this.plannedQueries = Math.max(1, plannedQueries);
        this.includeOriginal = includeOriginal;
    }

    @Override
    public List<Query> expand(Query query) {
        Assert.notNull(query, "query cannot be null");

        List<Query> expanded;
        boolean degraded = false;
        try {
            expanded = delegate.expand(query);
        } catch (RuntimeException ex) {
            log.warn("Query expansion failed; using the input query unchanged");
            expanded = List.of(query);
            degraded = true;
        }
        if (expanded == null || expanded.isEmpty()) {
            degraded = true;
        }

        String originalText = normalizedText(query);
        List<Query> bounded = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        int duplicateVariantsRemoved = 0;

        if (includeOriginal && originalText != null) {
            bounded.add(queryWithText(query, originalText));
            seen.add(originalText);
        }

        boolean skippedExpectedOriginal = false;
        boolean hasValidCandidate = false;
        if (expanded != null) {
            for (Query candidate : expanded) {
                String text = normalizedText(candidate);
                if (text == null) {
                    continue;
                }
                hasValidCandidate = true;
                if (includeOriginal
                        && Objects.equals(text, originalText)
                        && !skippedExpectedOriginal) {
                    skippedExpectedOriginal = true;
                    continue;
                }
                if (!seen.add(text)) {
                    duplicateVariantsRemoved++;
                    continue;
                }
                if (bounded.size() < plannedQueries) {
                    bounded.add(queryWithText(query, text));
                }
            }
        }
        if (!hasValidCandidate) {
            degraded = true;
        }

        if (bounded.isEmpty()) {
            bounded.add(query);
        }
        if (bounded.size() > plannedQueries) {
            bounded = new ArrayList<>(bounded.subList(0, plannedQueries));
        }

        RetrievalTraceCollector retrievalTrace = trace(query).orElse(null);
        if (retrievalTrace != null) {
            retrievalTrace.recordQueryExpansionOutcome(
                    duplicateVariantsRemoved,
                    degraded);
        }
        return List.copyOf(bounded);
    }

    private String normalizedText(Query query) {
        if (query == null || query.text() == null) {
            return null;
        }
        String text = query.text().trim();
        return text.isEmpty() ? null : text;
    }

    private Query queryWithText(Query query, String text) {
        if (text.equals(query.text())) {
            return query;
        }
        return query.mutate().text(text).build();
    }

    private java.util.Optional<RetrievalTraceCollector> trace(Query query) {
        if (query.context() == null) {
            return java.util.Optional.empty();
        }
        Object value = query.context().get(ProjectDocumentRetriever.CONTEXT_KEY);
        if (value instanceof AuthorizedRetrievalContext context) {
            return java.util.Optional.of(context.trace());
        }
        return java.util.Optional.empty();
    }
}
