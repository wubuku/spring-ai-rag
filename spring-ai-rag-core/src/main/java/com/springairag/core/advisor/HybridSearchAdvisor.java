package com.springairag.core.advisor;

import com.springairag.api.dto.RetrievalResult;
import com.springairag.core.retrieval.HybridRetrieverService;
import com.springairag.core.service.RetrievalLoggingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Hybrid Search Advisor
 *
 * <p>Execution order in RAG Pipeline: third (after QueryRewriteAdvisor, before RerankAdvisor).
 * Responsibility: calls {@link HybridRetrieverService} for vector + full-text hybrid retrieval,
 * stores results in context attributes for downstream {@link RerankAdvisor}.
 *
 * <p>Important: this Advisor only performs retrieval; context injection is done by RerankAdvisor.
 *
 * <p>Context Keys:
 * <ul>
 *   <li>{@code hybrid.search.results} — hybrid search results (List&lt;RetrievalResult&gt;)</li>
 * </ul>
 */
@Component
public class HybridSearchAdvisor extends AbstractRagAdvisor {

    private static final Logger log = LoggerFactory.getLogger(HybridSearchAdvisor.class);

    /** Retrieval results attribute key in request context */
    public static final String RETRIEVAL_RESULTS_KEY = "hybrid.search.results";

    private final HybridRetrieverService hybridRetriever;
    private final AdvisorMetrics advisorMetrics;

    private RetrievalLoggingService retrievalLoggingService;

    @Autowired
    public HybridSearchAdvisor(HybridRetrieverService hybridRetriever,
                                 AdvisorMetrics advisorMetrics) {
        this.hybridRetriever = hybridRetriever;
        this.advisorMetrics = advisorMetrics;
    }

    /**
     * Optional injection: retrieval logging service (null when Repository is unavailable)
     */
    @Autowired(required = false)
    public void setRetrievalLoggingService(RetrievalLoggingService retrievalLoggingService) {
        this.retrievalLoggingService = retrievalLoggingService;
    }

    @Override
    public String getName() {
        return "HybridSearchAdvisor";
    }

    /**
     * HIGHEST_PRECEDENCE + 20
     * Executes after QueryRewriteAdvisor (+10) and before RerankAdvisor (+30)
     */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 20;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        if (shouldSkip(log)) {
            return request;
        }

        String query = AdvisorUtils.extractUserMessage(request);
        if (query == null || query.isBlank()) {
            log.debug("[HybridSearchAdvisor] query is empty, skipping search");
            return request;
        }

        long startMs = System.currentTimeMillis();
        List<RetrievalResult> results = hybridRetriever.search(query, null, null, 10);
        long elapsedMs = System.currentTimeMillis() - startMs;

        log.info("[HybridSearchAdvisor] hybrid search returned {} results in {}ms, query: \"{}\"",
                results.size(), elapsedMs, query);

        recordMetricsAndLog(request, query, elapsedMs, results);

        return request.mutate()
                .context(RETRIEVAL_RESULTS_KEY, results)
                .build();
    }

    /** Records pipeline metrics and retrieval logs */
    private void recordMetricsAndLog(ChatClientRequest request, String query,
                                      long elapsedMs, List<RetrievalResult> results) {
        RagPipelineMetrics.getOrCreate(request.context())
                .recordStep("HybridSearch", elapsedMs, results.size());
        advisorMetrics.record("HybridSearch", elapsedMs, results.size());

        if (retrievalLoggingService != null) {
            String sessionId = request.context().get("sessionId") != null
                    ? String.valueOf(request.context().get("sessionId")) : null;
            retrievalLoggingService.logRetrieval(
                    sessionId, query, "hybrid",
                    elapsedMs, 0L, 0L, results);
        }
    }
}
