package com.springairag.core.chat;

import com.springairag.core.retrieval.RetrievalFilters;
import com.springairag.core.retrieval.RetrievalScope;

/**
 * Immutable server-owned context passed to Modular RAG and tools.
 */
public record AuthorizedRetrievalContext(
        RetrievalScope scope,
        RetrievalOptions options,
        RetrievalTraceCollector trace,
        String sessionId,
        ChatPrincipal principal,
        int maxToolResultCharacters,
        RetrievalFilters filters,
        ChatExecutionBudget executionBudget) {

    public AuthorizedRetrievalContext {
        scope = scope != null ? scope : RetrievalScope.unscoped();
        options = options != null
                ? options
                : new RetrievalOptions(5, 0.3, true, true, 0.5, 0.5);
        trace = trace != null ? trace : new RetrievalTraceCollector();
        sessionId = SessionIdValidator.resolve(sessionId);
        principal = principal != null ? principal : ChatPrincipal.local();
        maxToolResultCharacters = Math.max(1024, maxToolResultCharacters);
        filters = filters != null ? filters : RetrievalFilters.none();
    }

    public AuthorizedRetrievalContext(
            RetrievalScope scope,
            RetrievalOptions options,
            RetrievalTraceCollector trace,
            String sessionId,
            ChatPrincipal principal) {
        this(scope, options, trace, sessionId, principal, 24_000, RetrievalFilters.none());
    }

    public AuthorizedRetrievalContext(
            RetrievalScope scope,
            RetrievalOptions options,
            RetrievalTraceCollector trace,
            String sessionId,
            ChatPrincipal principal,
            int maxToolResultCharacters) {
        this(scope, options, trace, sessionId, principal, maxToolResultCharacters,
                RetrievalFilters.none());
    }

    public AuthorizedRetrievalContext(
            RetrievalScope scope,
            RetrievalOptions options,
            RetrievalTraceCollector trace,
            String sessionId,
            ChatPrincipal principal,
            int maxToolResultCharacters,
            RetrievalFilters filters) {
        this(scope, options, trace, sessionId, principal, maxToolResultCharacters,
                filters, null);
    }

    public ChatExecutionBudget executionBudget() {
        return executionBudget;
    }
}
