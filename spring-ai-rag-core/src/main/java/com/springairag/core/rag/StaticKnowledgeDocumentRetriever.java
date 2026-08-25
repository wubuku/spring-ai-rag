package com.springairag.core.rag;

import com.springairag.core.chat.AuthorizedRetrievalContext;
import com.springairag.core.resource.StaticKnowledgeCatalog;
import com.springairag.api.dto.RetrievalResult;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Retrieves deployment-provided static knowledge without vector-store access.
 */
@Component
public final class StaticKnowledgeDocumentRetriever
        implements DocumentRetriever {

    private final StaticKnowledgeCatalog catalog;
    private final RetrievalDocumentMapper mapper;

    public StaticKnowledgeDocumentRetriever(
            StaticKnowledgeCatalog catalog,
            RetrievalDocumentMapper mapper) {
        this.catalog = catalog;
        this.mapper = mapper;
    }

    @Override
    public List<Document> retrieve(Query query) {
        AuthorizedRetrievalContext context = context(query);
        return retrieve(query.text(), context);
    }

    public List<Document> retrieve(
            String query,
            AuthorizedRetrievalContext context) {
        List<Document> documents = catalog.search(
                query,
                context.options().maxResults(),
                context.maxToolResultCharacters());
        List<RetrievalResult> results = documents.stream()
                .map(mapper::toRetrievalResult)
                .toList();
        context.trace().record(results);
        return documents;
    }

    public boolean enabled() {
        return catalog.snapshot().healthy()
                && !catalog.snapshot().chunks().isEmpty();
    }

    private AuthorizedRetrievalContext context(Query query) {
        Object value = query != null && query.context() != null
                ? query.context().get(ProjectDocumentRetriever.CONTEXT_KEY)
                : null;
        if (value instanceof AuthorizedRetrievalContext context) {
            return context;
        }
        throw new IllegalStateException(
                "Missing server-owned authorized retrieval context");
    }
}
