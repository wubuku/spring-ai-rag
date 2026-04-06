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
 * 混合检索 Advisor
 *
 * <p>在 RAG Pipeline 中执行顺序：第三位（QueryRewriteAdvisor 之后，RerankAdvisor 之前）。
 * 职责：调用 {@link HybridRetrieverService} 进行向量 + 全文混合检索，
 * 将检索结果存入 context attributes，供后续 {@link RerankAdvisor} 使用。
 *
 * <p>重要：此 Advisor 只执行检索，不注入上下文。上下文注入由 RerankAdvisor 完成。
 *
 * <p>Context Keys:
 * <ul>
 *   <li>{@code hybrid.search.results} — 混合检索结果（List&lt;RetrievalResult&gt;）</li>
 * </ul>
 */
@Component
public class HybridSearchAdvisor extends AbstractRagAdvisor {

    private static final Logger log = LoggerFactory.getLogger(HybridSearchAdvisor.class);

    /** 检索结果在 request context 中的 attribute key */
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
     * 可选注入：检索日志服务（Repository 不可用时为 null）
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
     * 在 QueryRewriteAdvisor (+10) 之后执行，在 RerankAdvisor (+30) 之前执行
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
            log.debug("[HybridSearchAdvisor] 查询为空，跳过检索");
            return request;
        }

        long startMs = System.currentTimeMillis();
        List<RetrievalResult> results = hybridRetriever.search(query, null, null, 10);
        long elapsedMs = System.currentTimeMillis() - startMs;

        log.info("[HybridSearchAdvisor] 混合检索返回 {} 条结果，耗时 {}ms，查询: \"{}\"",
                results.size(), elapsedMs, query);

        recordMetricsAndLog(request, query, elapsedMs, results);

        return request.mutate()
                .context(RETRIEVAL_RESULTS_KEY, results)
                .build();
    }

    /** 记录 Pipeline 指标和检索日志 */
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
