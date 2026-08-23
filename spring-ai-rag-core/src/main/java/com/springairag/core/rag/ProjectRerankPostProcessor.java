package com.springairag.core.rag;

import com.springairag.api.dto.RetrievalResult;
import com.springairag.core.chat.AuthorizedRetrievalContext;
import com.springairag.core.retrieval.ReRankingService;
import com.springairag.core.retrieval.RetrievalBranchStage;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Reranks the project documents while preserving the standard RAG document contract.
 */
@Component
public class ProjectRerankPostProcessor implements DocumentPostProcessor {

    private final ReRankingService rerankingService;
    private final RetrievalDocumentMapper mapper;

    public ProjectRerankPostProcessor(
            ReRankingService rerankingService,
            RetrievalDocumentMapper mapper) {
        this.rerankingService = rerankingService;
        this.mapper = mapper;
    }

    @Override
    public List<Document> process(Query query, List<Document> documents) {
        AuthorizedRetrievalContext context = context(query.context());
        if (!context.options().useRerank() || documents == null || documents.isEmpty()) {
            return documents;
        }
        List<RetrievalResult> results = documents.stream()
                .map(this::toRetrievalResult)
                .toList();
        String effectiveQuery = context.trace().effectiveQuery(query.text());
        long startedAt = System.nanoTime();
        List<RetrievalResult> reranked;
        boolean degraded = false;
        String errorCode = null;
        try {
            reranked = rerankingService.rerank(
                    effectiveQuery, results, context.options().maxResults());
        } catch (RuntimeException e) {
            reranked = ReRankingService.limitResults(
                    results, context.options().maxResults());
            degraded = true;
            errorCode = e.getClass().getSimpleName();
        }
        reranked = ReRankingService.limitResults(
                reranked, context.options().maxResults());
        context.trace().recordRerank(
                new RetrievalBranchStage(
                        RetrievalBranchStage.RERANK,
                        "rerank",
                        degraded ? RetrievalBranchStage.ERROR : RetrievalBranchStage.SUCCESS,
                        (System.nanoTime() - startedAt) / 1_000_000L,
                        results.size(),
                        reranked.size(),
                        errorCode),
                reranked,
                degraded,
                effectiveQuery,
                context.options().maxResults());
        return reranked.stream().map(mapper::toDocument).toList();
    }

    private RetrievalResult toRetrievalResult(Document document) {
        RetrievalResult result = new RetrievalResult();
        Map<String, Object> metadata = document.getMetadata();
        result.setDocumentId(stringValue(metadata, "documentId", document.getId()));
        result.setChunkIndex(intValue(metadata, "chunkIndex"));
        result.setTitle(stringValue(metadata, "title", result.getDocumentId()));
        result.setChunkText(document.getText());
        result.setScore(numberValue(metadata, "score"));
        result.setVectorScore(numberValue(metadata, "vectorScore"));
        result.setFulltextScore(numberValue(metadata, "fulltextScore"));
        result.setMetadata(metadata);
        return result;
    }

    private AuthorizedRetrievalContext context(Map<String, Object> values) {
        Object value = values != null ? values.get(ProjectDocumentRetriever.CONTEXT_KEY) : null;
        if (value instanceof AuthorizedRetrievalContext context) {
            return context;
        }
        throw new IllegalStateException("Missing server-owned authorized retrieval context");
    }

    private String stringValue(Map<String, Object> values, String key, String fallback) {
        Object value = values != null ? values.get(key) : null;
        return value != null ? String.valueOf(value) : fallback;
    }

    private int intValue(Map<String, Object> values, String key) {
        Object value = values != null ? values.get(key) : null;
        return value instanceof Number number ? number.intValue() : 0;
    }

    private double numberValue(Map<String, Object> values, String key) {
        Object value = values != null ? values.get(key) : null;
        return value instanceof Number number ? number.doubleValue() : 0.0;
    }
}
