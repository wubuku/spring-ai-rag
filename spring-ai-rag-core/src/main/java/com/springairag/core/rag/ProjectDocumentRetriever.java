package com.springairag.core.rag;

import com.springairag.api.dto.RetrievalResult;
import com.springairag.core.chat.AuthorizedRetrievalContext;
import com.springairag.core.chat.RetrievalTraceCollector;
import com.springairag.core.retrieval.HybridRetrieverService;
import com.springairag.core.retrieval.RetrievalScope;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Spring AI DocumentRetriever backed by the project's authorized hybrid search.
 */
@Component
public class ProjectDocumentRetriever implements DocumentRetriever {

    public static final String CONTEXT_KEY = "rag.authorized.retrieval";

    private final HybridRetrieverService hybridRetriever;
    private final RetrievalDocumentMapper mapper;

    public ProjectDocumentRetriever(
            HybridRetrieverService hybridRetriever,
            RetrievalDocumentMapper mapper) {
        this.hybridRetriever = hybridRetriever;
        this.mapper = mapper;
    }

    @Override
    public List<Document> retrieve(Query query) {
        AuthorizedRetrievalContext context = context(query.context());
        RetrievalTraceCollector trace = context.trace();
        String retrievalQuery = query.text();
        trace.setEffectiveQuery(retrievalQuery);
        if (!trace.tryBeginRetrieval(retrievalQuery)) {
            return List.of();
        }
        RetrievalScope scope = context.scope();
        List<RetrievalResult> results = scope.matchNone()
                ? List.of()
                : hybridRetriever.searchInScope(
                        retrievalQuery,
                        scope,
                        null,
                        context.options().maxResults(),
                        context.options().toConfig());
        trace.record(retrievalQuery, results);
        return results.stream().map(mapper::toDocument).toList();
    }

    private AuthorizedRetrievalContext context(Map<String, Object> values) {
        Object value = values != null ? values.get(CONTEXT_KEY) : null;
        if (value instanceof AuthorizedRetrievalContext context) {
            return context;
        }
        throw new IllegalStateException("Missing server-owned authorized retrieval context");
    }
}
