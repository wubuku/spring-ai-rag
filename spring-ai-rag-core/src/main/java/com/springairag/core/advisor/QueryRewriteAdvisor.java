package com.springairag.core.advisor;

import com.springairag.core.retrieval.QueryRewritingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Query Rewrite Advisor
 *
 * <p>Execution order in RAG Pipeline: second (after any custom high-priority Advisors).
 * Responsibility: before retrieval, calls {@link QueryRewritingService} to rewrite the original query,
 * storing results in {@link ChatClientRequest#context()} for downstream {@link HybridSearchAdvisor}.
 *
 * <p>Execution flow:
 * <ol>
 *   <li>before() → reads original user query, calls QueryRewritingService.rewriteQuery(),
 *      stores List&lt;String&gt; results in context</li>
 *   <li>adviseCall() (framework call) → executes before() → chain.nextCall() → after() in order</li>
 *   <li>after() → passes response through unchanged (inherited from AbstractRagAdvisor)</li>
 * </ol>
 *
 * <p>Context Keys:
 * <ul>
 *   <li>{@code rewrite.original} — original query text (String)</li>
 *   <li>{@code rewrite.queries} — rewritten query list (List&lt;String&gt;), first element is the original query</li>
 *   <li>{@code rewrite.retrieval-query} — compatibility value for legacy advisors; equal to the
 *       last user message unless an upstream standard transformer supplies a focused query</li>
 * </ul>
 */
@Component
public class QueryRewriteAdvisor extends AbstractRagAdvisor {

    private static final Logger log = LoggerFactory.getLogger(QueryRewriteAdvisor.class);

    /** Context key: original query text */
    public static final String CTX_ORIGINAL_QUERY = "rewrite.original";

    /** Context key: rewritten query list (List&lt;String&gt;) */
    public static final String CTX_REWRITE_QUERIES = "rewrite.queries";

    /** Context key: focused query used by retrieval and reranking */
    public static final String CTX_RETRIEVAL_QUERY = "rewrite.retrieval-query";

    /** Context key for a legacy execution-scoped, purpose-aware rewrite model. */
    public static final String CTX_EXECUTION_MODEL =
            "rewrite.execution-model";

    private final QueryRewritingService queryRewritingService;
    private final AdvisorMetrics advisorMetrics;

    @Autowired
    public QueryRewriteAdvisor(QueryRewritingService queryRewritingService,
                               AdvisorMetrics advisorMetrics) {
        this.queryRewritingService = queryRewritingService;
        this.advisorMetrics = advisorMetrics;
    }

    @Override
    public String getName() {
        return "QueryRewriteAdvisor";
    }

    /**
     * HIGHEST_PRECEDENCE + 10
     * Ensures execution before HybridSearchAdvisor (+20) and after any custom high-priority Advisors
     */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        if (shouldSkip(log)) {
            return request;
        }

        String originalQuery = AdvisorUtils.extractUserMessage(request);
        if (originalQuery == null || originalQuery.isBlank()) {
            log.debug("[QueryRewriteAdvisor] query is empty, skipping rewrite");
            return request;
        }

        long startMs = System.currentTimeMillis();
        Object executionModel = request.context().get(CTX_EXECUTION_MODEL);
        List<String> rewrittenQueries = executionModel instanceof ChatModel model
                ? queryRewritingService.rewriteQuery(originalQuery, model)
                : queryRewritingService.rewriteQuery(originalQuery);
        // Do not infer retrieval intent from language-specific command patterns here. The
        // production Chat path uses Spring AI QueryTransformer/Tool Calling; this legacy advisor
        // keeps the original query unless an upstream component explicitly provides another one.
        String retrievalQuery = originalQuery;
        long elapsedMs = System.currentTimeMillis() - startMs;

        log.info("[QueryRewriteAdvisor] original query: \"{}\" → retrieval query: \"{}\", "
                        + "{} rewritten, {}ms: {}",
                originalQuery, retrievalQuery,
                rewrittenQueries.size(), elapsedMs, rewrittenQueries);

        // Record Micrometer metrics for Prometheus + in-memory pipeline metrics
        RagPipelineMetrics.getOrCreate(request.context())
                .recordStep("QueryRewrite", elapsedMs, rewrittenQueries.size());
        advisorMetrics.record("QueryRewrite", elapsedMs, rewrittenQueries.size());

        // Store rewritten results in context for downstream Advisors (HybridSearchAdvisor, etc.)
        return request.mutate()
                .context(CTX_ORIGINAL_QUERY, originalQuery)
                .context(CTX_REWRITE_QUERIES, rewrittenQueries)
                .context(CTX_RETRIEVAL_QUERY, retrievalQuery)
                .build();
    }
}
