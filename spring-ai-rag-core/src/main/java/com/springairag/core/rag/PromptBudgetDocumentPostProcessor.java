package com.springairag.core.rag;

import com.springairag.core.chat.AuthorizedRetrievalContext;
import com.springairag.core.chat.ChatExecutionBudget;
import com.springairag.core.chat.PromptTokenEstimator;
import com.springairag.core.config.RagChatProperties;
import com.springairag.documents.chunk.ChunkValidator;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.postretrieval.document.DocumentPostProcessor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 在 rerank 后按 token 预算裁剪 RAG 证据，保持文档顺序和身份 metadata。
 */
public final class PromptBudgetDocumentPostProcessor
        implements DocumentPostProcessor {

    private final PromptTokenEstimator estimator;
    private final RagChatProperties properties;

    public PromptBudgetDocumentPostProcessor(
            PromptTokenEstimator estimator,
            RagChatProperties properties) {
        this.estimator = estimator;
        this.properties = properties;
    }

    @Override
    public List<Document> process(Query query, List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return documents;
        }
        if (!properties.getContext().isAdaptivePlanningEnabled()
                && documents.stream().noneMatch(document -> document.getMetadata().containsKey("contextAssembly"))) {
            return documents;
        }
        AuthorizedRetrievalContext context = context(query);
        int configuredLimit = properties.getContext().getMaxRagContextTokens();
        int planLimit = configuredLimit;
        ChatExecutionBudget budget = context.executionBudget();
        if (budget != null) {
            Object value = budget.contextPlan().get("ragContextTokens");
            if (value instanceof Number number && number.intValue() > 0) {
                planLimit = Math.min(configuredLimit, number.intValue());
            }
        }
        int remaining = planLimit;
        List<Document> selected = new ArrayList<>();
        int dropped = 0;
        int truncated = 0;
        int anchorFallbacks = 0;
        List<Document> pending = new ArrayList<>(documents);
        for (int index = 0; index < pending.size(); index++) {
            Document original = pending.get(index);
            Document document = withoutPrivateMetadata(original);
            int tokens = estimator.estimate(withoutAssemblyMetadata(document));
            if (tokens <= remaining) {
                selected.add(document);
                remaining -= tokens;
                continue;
            }
            Object anchors = original.getMetadata().get(RetrievalContextAssemblyPostProcessor.ANCHORS);
            if (anchors instanceof List<?> values && !values.isEmpty()
                    && values.stream().allMatch(Document.class::isInstance)) {
                List<Document> restored = values.stream().map(Document.class::cast).toList();
                pending.remove(index);
                pending.addAll(index, restored);
                anchorFallbacks += restored.size();
                index--;
                continue;
            }
            if (remaining > 0 && document.getText() != null
                    && !document.getText().isEmpty()) {
                String text = fitText(document, remaining);
                if (!text.isBlank()) {
                    selected.add(withBudgetMetadata(
                            document.mutate().text(text).build(), true));
                    remaining = 0;
                    truncated++;
                    continue;
                }
            }
            dropped++;
        }
        if (budget != null) {
            budget.recordContextPlan(mergeBudgetMetadata(
                    budget.contextPlan(), selected.size(), dropped, truncated));
        }
        if (context.trace().contextAssembly() != null) {
            context.trace().recordContextAssembly(Map.of("finalDocuments", selected.size(),
                    "estimatedTokens", selected.stream().mapToInt(estimator::estimate).sum(),
                    "anchorFallbacks", anchorFallbacks, "budgetDropped", dropped, "budgetTruncated", truncated));
        }
        return List.copyOf(selected);
    }

    private String fitText(Document document, int tokenLimit) {
        String text = document.getText();
        int low = 0;
        int high = text.length();
        String best = "";
        while (low <= high) {
            int middle = (low + high) >>> 1;
            int boundary = ChunkValidator.splitsSurrogate(text, middle) ? middle - 1 : middle;
            String candidate = text.substring(0, boundary);
            if (estimator.estimate(candidate) <= tokenLimit) {
                best = candidate;
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        return best;
    }

    private Document withoutAssemblyMetadata(Document document) {
        if (!document.getMetadata().containsKey("contextAssembly")) {
            return document;
        }
        Map<String, Object> metadata = new LinkedHashMap<>(document.getMetadata());
        metadata.remove("contextAssembly");
        return document.mutate().metadata(metadata).build();
    }

    private Document withBudgetMetadata(Document document, boolean wasTruncated) {
        Map<String, Object> metadata = new LinkedHashMap<>(document.getMetadata());
        metadata.remove("contextAssembly");
        metadata.put("contextBudgetTruncated", wasTruncated);
        return document.mutate().metadata(metadata).build();
    }

    private Map<String, Object> mergeBudgetMetadata(
            Map<String, Object> current,
            int included,
            int dropped,
            int truncated) {
        Map<String, Object> next = new LinkedHashMap<>(current);
        next.put("ragIncludedDocuments", included);
        next.put("ragDroppedDocuments", dropped);
        next.put("ragTruncatedDocuments", truncated);
        return next;
    }

    private Document withoutPrivateMetadata(Document document) {
        if (!document.getMetadata().containsKey(RetrievalContextAssemblyPostProcessor.ANCHORS)) {
            return document;
        }
        Map<String, Object> metadata = new LinkedHashMap<>(document.getMetadata());
        metadata.remove(RetrievalContextAssemblyPostProcessor.ANCHORS);
        return document.mutate().metadata(metadata).build();
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
