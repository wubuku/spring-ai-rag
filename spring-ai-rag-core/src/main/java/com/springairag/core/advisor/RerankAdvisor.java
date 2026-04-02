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
 * 重排序 Advisor
 *
 * <p>在 RAG Pipeline 中执行顺序：第四位（HybridSearchAdvisor 之后）。
 * 职责：从 context attributes 获取 {@link HybridSearchAdvisor} 的检索结果，
 * 调用 {@link ReRankingService} 进行重排，然后将最终结果注入 Prompt 上下文。
 *
 * <p>注入策略（由 {@link ApiCompatibilityAdapter} 决定）：
 * <ul>
 *   <li>支持多 system 消息的 API（OpenAI/Anthropic）→ 使用 augmentSystemMessage</li>
 *   <li>不支持的 API（MiniMax 等）→ 使用 augmentUserMessage 合并到用户消息</li>
 * </ul>
 *
 * <p>Context Keys:
 * <ul>
 *   <li>读取：{@code hybrid.search.results} — HybridSearchAdvisor 的检索结果</li>
 * </ul>
 */
@Component
public class RerankAdvisor extends AbstractRagAdvisor {

    private static final Logger log = LoggerFactory.getLogger(RerankAdvisor.class);

    private final ReRankingService rerankingService;

    private final ApiCompatibilityAdapter adapter;

    /** 重排结果在 response context 中的 key，供 RagChatService 提取 sources */
    public static final String RERANKED_RESULTS_KEY = "rag.reranked.results";

    /** 注入到系统消息的上下文前缀 */
    private String systemContextPrefix = "基于以下参考资料回答问题：\n\n";

    /** 返回的最大结果数 */
    private int maxResults = 5;

    @Autowired
    public RerankAdvisor(ReRankingService rerankingService, ApiAdapterFactory adapterFactory, @org.springframework.beans.factory.annotation.Value("${spring.ai.openai.base-url:}") String baseUrl) {
        this.rerankingService = rerankingService;
        this.adapter = adapterFactory.getAdapter(baseUrl);
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
     * 在 HybridSearchAdvisor (+20) 之后执行
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

        log.info("[RerankAdvisor] 重排完成：{} → {} 条结果，耗时 {}ms", results.size(), reranked.size(), elapsedMs);
        RagPipelineMetrics.getOrCreate(request.context())
                .recordStep("Rerank", elapsedMs, reranked.size());

        return injectRerankedContext(request, reranked);
    }

    /** 从 context 获取检索结果，无结果返回 null */
    @SuppressWarnings("unchecked")
    private List<RetrievalResult> getRetrievalResults(ChatClientRequest request) {
        Object resultsObj = request.context().get(HybridSearchAdvisor.RETRIEVAL_RESULTS_KEY);
        if (resultsObj == null) {
            log.warn("[RerankAdvisor] context 中未找到检索结果，跳过重排");
            return null;
        }
        List<RetrievalResult> results = (List<RetrievalResult>) resultsObj;
        if (results.isEmpty()) {
            log.debug("[RerankAdvisor] 检索结果为空，跳过重排");
            return null;
        }
        return results;
    }

    /** 根据 API 适配器选择策略，将重排结果注入 Prompt 上下文 */
    private ChatClientRequest injectRerankedContext(ChatClientRequest request, List<RetrievalResult> reranked) {
        String context = buildContextFromResults(reranked);
        ChatClientRequest.Builder mutated = request.mutate().context(RERANKED_RESULTS_KEY, reranked);

        if (adapter.supportsMultipleSystemMessages()) {
            mutated.prompt(request.prompt().augmentSystemMessage(systemContextPrefix + context));
            log.debug("[RerankAdvisor] 使用 augmentSystemMessage 注入上下文");
        } else {
            String userPrefix = systemContextPrefix + context + "\n\n基于以上资料回答以下问题：\n\n";
            mutated.prompt(request.prompt().augmentUserMessage(
                    userMsg -> new UserMessage(userPrefix + userMsg.getText())));
            log.debug("[RerankAdvisor] 使用 augmentUserMessage 注入上下文（API 不支持多 system 消息）");
        }
        return mutated.build();
    }

    /**
     * 将检索结果格式化为上下文文本
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
