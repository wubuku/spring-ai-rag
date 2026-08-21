package com.springairag.core.rag;

import com.springairag.core.chat.AuthorizedRetrievalContext;
import com.springairag.core.chat.ChatExecutionBudget;
import com.springairag.core.chat.PromptTokenEstimator;
import com.springairag.core.chat.RetrievalOptions;
import com.springairag.core.chat.RetrievalTraceCollector;
import com.springairag.core.config.RagChatProperties;
import com.springairag.core.retrieval.RetrievalScope;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptBudgetDocumentPostProcessorTest {

    @Test
    void capsDenseChineseDocumentByTokenBudgetAndPreservesMetadata() {
        RagChatProperties properties = new RagChatProperties();
        properties.getContext().setMaxRagContextTokens(4);
        PromptTokenEstimator estimator =
                text -> text == null ? 0 : text.codePointCount(0, text.length());
        PromptBudgetDocumentPostProcessor processor =
                new PromptBudgetDocumentPostProcessor(estimator, properties);
        ChatExecutionBudget budget = new ChatExecutionBudget(
                Instant.now().plusSeconds(30), 2, 4, 2, 4, 2, 500);
        budget.recordContextPlan(Map.of("ragContextTokens", 4));
        AuthorizedRetrievalContext context = new AuthorizedRetrievalContext(
                RetrievalScope.unscoped(),
                new RetrievalOptions(5, 0.3, true, true, 0.5, 0.5),
                new RetrievalTraceCollector(),
                "session",
                null,
                24_000,
                null,
                budget);
        Document document = new Document(
                "中文中文中文中文中文",
                Map.of("source", "stable"));

        List<Document> result = processor.process(
                new Query("query", List.of(), Map.of(
                        ProjectDocumentRetriever.CONTEXT_KEY, context)),
                List.of(document));

        assertTrue(result.getFirst().getText().codePointCount(
                0, result.getFirst().getText().length()) <= 4);
        assertTrue(result.getFirst().getMetadata().containsKey(
                "contextBudgetTruncated"));
    }
}
