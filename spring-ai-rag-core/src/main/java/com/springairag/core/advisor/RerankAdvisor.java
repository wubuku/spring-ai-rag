package com.springairag.core.advisor;

import com.springairag.api.dto.RetrievalResult;
import com.springairag.core.adapter.ApiAdapterFactory;
import com.springairag.core.adapter.ApiCompatibilityAdapter;
import com.springairag.core.retrieval.ReRankingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Re-ranking Advisor
 *
 * <p>Execution order in RAG Pipeline: fourth (after HybridSearchAdvisor).
 * Responsibility: retrieves {@link HybridSearchAdvisor} results from context attributes,
 * calls {@link ReRankingService} to re-rank, then injects the final results into the Prompt context.
 *
 * <p>Injection strategy (determined by {@link ApiCompatibilityAdapter}):
 * <ul>
 *   <li>APIs supporting multiple system messages (OpenAI/Anthropic) → use augmentSystemMessage</li>
 *   <li>APIs not supporting it (MiniMax, etc.) → use augmentUserMessage merged into the user message</li>
 * </ul>
 *
 * <p>Context Keys:
 * <ul>
 *   <li>Read: {@code hybrid.search.results} — HybridSearchAdvisor retrieval results</li>
 * </ul>
 */
@Component
public class RerankAdvisor extends AbstractRagAdvisor {

    private static final Logger log = LoggerFactory.getLogger(RerankAdvisor.class);

    private final ReRankingService rerankingService;

    private final ApiCompatibilityAdapter adapter;

    private final AdvisorMetrics advisorMetrics;

    /** Reranked results key in response context, used by RagChatService to extract sources */
    public static final String RERANKED_RESULTS_KEY = "rag.reranked.results";

    /** Context prefix injected into system messages */
    private String systemContextPrefix = "Answer the question based on the following references:\n\n";

    /** Maximum number of results to return */
    private int maxResults = 5;

    @Autowired
    public RerankAdvisor(ReRankingService rerankingService, ApiAdapterFactory adapterFactory,
                          AdvisorMetrics advisorMetrics,
                          @org.springframework.beans.factory.annotation.Value("${spring.ai.openai.base-url:}") String baseUrl) {
        this.rerankingService = rerankingService;
        this.adapter = adapterFactory.getAdapter(baseUrl);
        this.advisorMetrics = advisorMetrics;
    }

    public void setSystemContextPrefix(String systemContextPrefix) {
        this.systemContextPrefix = systemContextPrefix;
    }

    public void setMaxResults(int maxResults) {
        this.maxResults = maxResults;
    }

    @Override
    public String getName() {
        return "RerankAdvisor";
    }

    /**
     * HIGHEST_PRECEDENCE + 30
     * Executes after HybridSearchAdvisor (+20)
     */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 30;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        if (shouldSkip(log)) {
            return request;
        }

        List<RetrievalResult> results = getRetrievalResults(request);
        if (results == null) {
            return request;
        }

        String query = AdvisorUtils.extractUserMessage(request);

        long startMs = System.currentTimeMillis();
        List<RetrievalResult> reranked = rerankingService.rerank(query, results, maxResults);
        long elapsedMs = System.currentTimeMillis() - startMs;

        log.info("[RerankAdvisor] reranking complete: {} → {} results, {}ms", results.size(), reranked.size(), elapsedMs);
        RagPipelineMetrics.getOrCreate(request.context())
                .recordStep("Rerank", elapsedMs, reranked.size());
        advisorMetrics.record("Rerank", elapsedMs, reranked.size());

        return injectRerankedContext(request, reranked);
    }

    /** Retrieves retrieval results from context; returns null if no results */
    @SuppressWarnings("unchecked")
    private List<RetrievalResult> getRetrievalResults(ChatClientRequest request) {
        Object resultsObj = request.context().get(HybridSearchAdvisor.RETRIEVAL_RESULTS_KEY);
        if (resultsObj == null) {
            log.warn("[RerankAdvisor] no retrieval results found in context, skipping rerank");
            return null;
        }
        List<RetrievalResult> results = (List<RetrievalResult>) resultsObj;
        if (results.isEmpty()) {
            log.debug("[RerankAdvisor] retrieval results empty, skipping rerank");
            return null;
        }
        return results;
    }

    /** Selects strategy based on API adapter, injects reranked results into Prompt context */
    private ChatClientRequest injectRerankedContext(ChatClientRequest request, List<RetrievalResult> reranked) {
        String context = buildContextFromResults(reranked);
        ChatClientRequest.Builder mutated = request.mutate().context(RERANKED_RESULTS_KEY, reranked);

        if (adapter.supportsMultipleSystemMessages()) {
            mutated.prompt(request.prompt().augmentSystemMessage(systemContextPrefix + context));
            log.debug("[RerankAdvisor] using augmentSystemMessage to inject context");
        } else {
            String userPrefix = systemContextPrefix + context + "\n\nAnswer the question based on the above references:\n\n";
            mutated.prompt(request.prompt().augmentUserMessage(
                    userMsg -> new UserMessage(userPrefix + userMsg.getText())));
            log.debug("[RerankAdvisor] using augmentUserMessage to inject context (API does not support multiple system messages)");
        }
        return mutated.build();
    }

    /**
     * Formats retrieval results as context text
     */
    String buildContextFromResults(List<RetrievalResult> results) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < results.size(); i++) {
            RetrievalResult r = results.get(i);
            sb.append(i + 1).append(". ").append(r.getChunkText()).append("\n\n");
        }
        return sb.toString();
    }
}
