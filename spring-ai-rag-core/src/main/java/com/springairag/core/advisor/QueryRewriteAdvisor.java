package com.springairag.core.advisor;

import com.springairag.core.retrieval.QueryRewritingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 查询改写 Advisor
 *
 * <p>在 RAG Pipeline 中执行顺序：第二位（仅次于客户自定义高优先级 Advisor）。
 * 职责：在检索之前调用 {@link QueryRewritingService} 对原始查询进行改写，
 * 将改写结果存入 {@link ChatClientRequest#context()} 中，供后续 {@link HybridSearchAdvisor} 使用。
 *
 * <p>执行流程：
 * <ol>
 *   <li>before() → 读取原始 user query，调用 QueryRewritingService.rewriteQuery()，
 *      将 List&lt;String&gt; 改写结果存入 context</li>
 *   <li>adviseCall()（框架调用）→ 依次执行 before() → chain.nextCall() → after()</li>
 *   <li>after() → 原样透传响应</li>
 * </ol>
 *
 * <p>Context Keys:
 * <ul>
 *   <li>{@code rewrite.original} — 原始查询文本（String）</li>
 *   <li>{@code rewrite.queries} — 改写后的查询列表（List&lt;String&gt;），首个元素为原始查询</li>
 * </ul>
 */
@Component
public class QueryRewriteAdvisor implements BaseAdvisor {

    private static final Logger log = LoggerFactory.getLogger(QueryRewriteAdvisor.class);

    /** Context key: 原始查询文本 */
    public static final String CTX_ORIGINAL_QUERY = "rewrite.original";

    /** Context key: 改写后的查询列表（List&lt;String&gt;） */
    public static final String CTX_REWRITE_QUERIES = "rewrite.queries";

    private final QueryRewritingService queryRewritingService;

    /** 查询改写开关，可通过配置或 setter 覆盖 */
    private boolean enabled = true;

    @Autowired
    public QueryRewriteAdvisor(QueryRewritingService queryRewritingService) {
        this.queryRewritingService = queryRewritingService;
    }

    /**
     * 设置是否启用查询改写（供 Starter 配置类覆盖）
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public String getName() {
        return "QueryRewriteAdvisor";
    }

    /**
     * HIGHEST_PRECEDENCE + 10
     * 确保在 HybridSearchAdvisor（+20）之前执行，在客户自定义高优先级 Advisor 之后执行
     */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        String originalQuery = AdvisorUtils.extractUserMessage(request);

        if (!enabled || originalQuery == null || originalQuery.isBlank()) {
            log.debug("[QueryRewriteAdvisor] 禁用或查询为空，不改写");
            return request;
        }

        List<String> rewrittenQueries = queryRewritingService.rewriteQuery(originalQuery);

        log.info("[QueryRewriteAdvisor] 原始查询: \"{}\" → 改写 {} 条: {}",
                originalQuery, rewrittenQueries.size(), rewrittenQueries);

        // 将改写结果存入 context，传递给下游 Advisor（HybridSearchAdvisor 等）
        return request.mutate()
                .context(CTX_ORIGINAL_QUERY, originalQuery)
                .context(CTX_REWRITE_QUERIES, rewrittenQueries)
                .build();
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        // 不做任何后处理，直接透传
        return response;
    }
}
