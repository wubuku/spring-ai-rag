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

import java.util.ArrayList;
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
 * <p>Context Keys (written):
 * <ul>
 *   <li>{@code hybrid.search.results} — hybrid search results (List&lt;RetrievalResult&gt;)</li>
 * </ul>
 *
 * <p>Context Keys (read, optional advisor params):
 * <ul>
 *   <li>{@code documentIds} — List&lt;Long&gt; scope filter; empty list means no hits when filter requested</li>
 *   <li>{@code maxResults} — Integer retrieval limit (default 10)</li>
 *   <li>{@code filterRequested} — Boolean; when true and documentIds empty, return no hits</li>
 * </ul>
 */
@Component
public class HybridSearchAdvisor extends AbstractRagAdvisor {

    private static final Logger log = LoggerFactory.getLogger(HybridSearchAdvisor.class);

    /** Retrieval results attribute key in request context */
    public static final String RETRIEVAL_RESULTS_KEY = "hybrid.search.results";

    /** Advisor param: document ID scope (List&lt;Long&gt;) */
    public static final String DOCUMENT_IDS_KEY = "documentIds";

    /** Advisor param: max results (Integer) */
    public static final String MAX_RESULTS_KEY = "maxResults";

    /** Advisor param: caller requested a filter (Boolean); empty documentIds → no hits */
    public static final String FILTER_REQUESTED_KEY = "filterRequested";

    private static final int DEFAULT_LIMIT = 10;

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

        List<Long> documentIds = extractDocumentIds(request);
        int limit = extractMaxResults(request);
        boolean filterRequested = Boolean.TRUE.equals(request.context().get(FILTER_REQUESTED_KEY))
                || (documentIds != null && documentIds.isEmpty());

        // Isolation: explicit filter with zero matching docs must not fall through to global search
        List<RetrievalResult> results;
        long startMs = System.currentTimeMillis();
        if (filterRequested && (documentIds == null || documentIds.isEmpty())) {
            log.info("[HybridSearchAdvisor] collection/document filter resolved to zero docs — returning empty results");
            results = List.of();
        } else {
            results = hybridRetriever.search(query, documentIds, null, limit);
        }
        long elapsedMs = System.currentTimeMillis() - startMs;

        log.info("[HybridSearchAdvisor] hybrid search returned {} results in {}ms, query: \"{}\", documentIds={}",
                results.size(), elapsedMs, query, documentIds);

        recordMetricsAndLog(request, query, elapsedMs, results);

        return request.mutate()
                .context(RETRIEVAL_RESULTS_KEY, results)
                .build();
    }

    private List<Long> extractDocumentIds(ChatClientRequest request) {
        Object raw = request.context().get(DOCUMENT_IDS_KEY);
        if (raw == null) {
            return null;
        }
        if (raw instanceof List<?> list) {
            List<Long> ids = new ArrayList<>();
            for (Object o : list) {
                if (o instanceof Long l) {
                    ids.add(l);
                } else if (o instanceof Number n) {
                    ids.add(n.longValue());
                } else if (o != null) {
                    try {
                        ids.add(Long.parseLong(o.toString()));
                    } catch (NumberFormatException ignored) {
                        // skip non-numeric
                    }
                }
            }
            return ids;
        }
        return null;
    }

    private int extractMaxResults(ChatClientRequest request) {
        Object raw = request.context().get(MAX_RESULTS_KEY);
        if (raw instanceof Number n) {
            int v = n.intValue();
            return v > 0 ? Math.min(v, 50) : DEFAULT_LIMIT;
        }
        return DEFAULT_LIMIT;
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
