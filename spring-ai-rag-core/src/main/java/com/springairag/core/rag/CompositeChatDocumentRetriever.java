package com.springairag.core.rag;

import com.springairag.core.chat.AuthorizedRetrievalContext;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.retrieval.search.DocumentRetriever;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Combines authorized project retrieval and static knowledge under one
 * logical retrieval budget.
 */
@Component
public final class CompositeChatDocumentRetriever
        implements DocumentRetriever {

    public static final String COMPOSITE_RETRIEVAL_CONTEXT_KEY =
            "rag.composite.retrieval";

    private final ProjectDocumentRetriever projectRetriever;
    private final StaticKnowledgeDocumentRetriever staticRetriever;

    public CompositeChatDocumentRetriever(
            ProjectDocumentRetriever projectRetriever,
            StaticKnowledgeDocumentRetriever staticRetriever) {
        this.projectRetriever = projectRetriever;
        this.staticRetriever = staticRetriever;
    }

    @Override
    public List<Document> retrieve(Query query) {
        AuthorizedRetrievalContext context = context(query);
        if (!context.trace().tryBeginRetrieval(query.text())) {
            return List.of();
        }
        Map<String, Object> projectContext = new LinkedHashMap<>(
                query.context());
        projectContext.put(COMPOSITE_RETRIEVAL_CONTEXT_KEY, true);
        List<Document> project = projectRetriever.retrieve(new Query(
                query.text(), query.history(), projectContext));
        List<Document> staticKnowledge = staticRetriever.retrieve(
                query.text(), context);
        List<Document> combined = new ArrayList<>(
                project.size() + staticKnowledge.size());
        combined.addAll(project);
        combined.addAll(staticKnowledge);
        return deduplicate(combined);
    }

    private List<Document> deduplicate(List<Document> documents) {
        Map<String, Document> result = new LinkedHashMap<>();
        for (Document document : documents) {
            result.putIfAbsent(document.getId(), document);
        }
        return List.copyOf(result.values());
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
