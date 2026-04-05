package com.springairag.core.retrieval;

import com.springairag.core.config.RagProperties;
import com.springairag.core.config.RagQueryRewriteProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * 查询改写服务
 *
 * <p>支持三种改写模式：
 * <ul>
 *   <li>规则模式 — 同义词扩展、领域限定词扩展、Padding 查询</li>
 *   <li>LLM 辅助模式 — 调用 ChatModel 生成改写查询</li>
 *   <li>混合模式 — 规则 + LLM 双管齐下</li>
 * </ul>
 *
 * <p>同义词词典和领域限定词可通过配置自定义（默认空，不含领域硬编码）。
 */
@Service
public class QueryRewritingService {

    private static final Logger log = LoggerFactory.getLogger(QueryRewritingService.class);

    @Autowired(required = false)
    private RagProperties ragProperties;

    @Autowired(required = false)
    private ChatModel chatModel;

    @Autowired(required = false)
    private RetryTemplate retryTemplate;

    private RagQueryRewriteProperties config;

    /**
     * 运行时可变的同义词词典（优先级高于配置文件）
     */
    private Map<String, String[]> synonymDictionary = Collections.emptyMap();

    /**
     * 运行时可变的领域限定词（优先级高于配置文件）
     */
    private List<String> domainQualifiers = Collections.emptyList();

    /**
     * 无参构造器（兼容 Spring 框架）
     */
    public QueryRewritingService() {
    }

    /**
     * 单参数构造器（用于测试和手动装配）。
     * 注意：生产代码中请使用字段注入 + @PostConstruct 方式。
     */
    public QueryRewritingService(RagProperties ragProperties) {
        this.ragProperties = ragProperties;
        this.chatModel = null;
        init();
    }

    /**
     * 双参数构造器（用于测试）。
     * 注意：生产代码中请使用字段注入 + @PostConstruct 方式。
     */
    public QueryRewritingService(RagProperties ragProperties, ChatModel chatModel) {
        this.ragProperties = ragProperties;
        this.chatModel = chatModel;
        init();
    }

    /**
     * 初始化配置（在字段注入后由 Spring 调用，或由单参数构造器直接调用）。
     * 即使 ragProperties 为 null 也使用默认配置，保证服务可正常降级运行。
     */
    @PostConstruct
    public void init() {
        if (ragProperties != null) {
            this.config = ragProperties.getQueryRewrite();
            if (this.config == null) {
                this.config = new RagQueryRewriteProperties();
            }
            this.synonymDictionary = config.getSynonymDictionary() != null
                    ? config.getSynonymDictionary() : Collections.emptyMap();
            this.domainQualifiers = config.getDomainQualifiers() != null
                    ? config.getDomainQualifiers() : Collections.emptyList();
        } else {
            this.config = new RagQueryRewriteProperties();
        }
    }

    /**
     * 设置同义词词典（运行时覆盖配置文件值）
     */
    public void setSynonymDictionary(Map<String, String[]> dictionary) {
        this.synonymDictionary = dictionary != null ? dictionary : Collections.emptyMap();
    }

    /**
     * 设置领域限定词列表（运行时覆盖配置文件值）
     */
    public void setDomainQualifiers(List<String> qualifiers) {
        this.domainQualifiers = qualifiers != null ? qualifiers : Collections.emptyList();
    }

    /**
     * 改写查询
     *
     * <p>根据配置执行规则改写、LLM 改写或混合模式。
     *
     * @param originalQuery 原始查询
     * @return 改写后的查询列表（包含原始查询和扩展查询）
     */
    public List<String> rewriteQuery(String originalQuery) {
        if (config == null || !config.isEnabled() || originalQuery == null || originalQuery.isBlank()) {
            return List.of(originalQuery);
        }

        List<String> queries = new ArrayList<>();
        queries.add(originalQuery);

        // 1. 同义词扩展
        queries.addAll(expandWithSynonyms(originalQuery));

        // 2. 领域限定词扩展
        queries.addAll(expandWithDomainQualifiers(originalQuery));

        // 3. LLM 辅助改写（可选）
        if (config.isLlmEnabled()) {
            queries.addAll(llmRewrite(originalQuery));
        }

        // 去重
        return queries.stream().distinct().toList();
    }

    /**
     * LLM 辅助查询改写
     *
     * <p>调用 ChatModel 将用户查询改写为多种表述，提升检索召回率。
     * 失败时静默降级，不影响规则改写结果。
     *
     * @param originalQuery 原始查询
     * @return LLM 生成的改写查询列表
     */
    public List<String> llmRewrite(String originalQuery) {
        if (chatModel == null) {
            log.warn("LLM 改写已启用但 ChatModel 未配置，跳过");
            return List.of();
        }

        try {
            String prompt = buildRewritePrompt(originalQuery);
            String response = callLlm(prompt);
            if (response == null || response.isBlank()) {
                log.warn("LLM 返回空响应");
                return List.of();
            }

            List<String> rewritten = parseRewriteResponse(response, originalQuery);
            log.info("LLM 改写: \"{}\" → {} 条: {}",
                    originalQuery, rewritten.size(),
                    rewritten.size() > 3 ? rewritten.subList(0, 3) + "..." : rewritten);
            return rewritten.stream().limit(config.getLlmMaxRewrites()).toList();

        } catch (Exception e) { // Resilience: LLM failure, fallback to rule mode
            log.error("LLM 改写失败，降级到规则模式: {}", e.getMessage());
            return List.of();
        }
    }

    private String buildRewritePrompt(String originalQuery) {
        return String.format(
                "You are a query rewriting assistant. Rewrite the user query into %d different expressions to improve search recall." +
                "Requirements:\n1. One rewritten query per line\n2. Preserve original intent\n" +
                "3. Use different expressions, synonyms, or sentence structures\n4. No numbering, no explanations\n\n" +
                "Original query: %s",
                config.getLlmMaxRewrites(), originalQuery);
    }

    private String callLlm(String prompt) {
        ChatClient client = ChatClient.builder(chatModel).build();
        Supplier<String> llmCall = () -> client.prompt(prompt).call().content();
        if (retryTemplate != null) {
            try {
                return retryTemplate.execute(status -> {
                    if (status.getRetryCount() > 0) {
                        log.debug("LLM rewrite retry attempt {}", status.getRetryCount() + 1);
                    }
                    return llmCall.get();
                });
            } catch (Exception e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                log.warn("LLM rewrite failed after retries: {}", cause.getMessage());
                throw e instanceof RuntimeException ? (RuntimeException) e : new RuntimeException(cause);
            }
        }
        return llmCall.get();
    }

    private List<String> parseRewriteResponse(String response, String originalQuery) {
        List<String> rewritten = new ArrayList<>();
        for (String line : response.split("\n")) {
            String cleaned = line.trim().replaceAll("^[-*•\\d.]+\\s*", "").trim();
            if (!cleaned.isEmpty() && !cleaned.equals(originalQuery)) {
                rewritten.add(cleaned);
            }
        }
        return rewritten;
    }

    /**
     * 使用同义词扩展查询
     */
    private List<String> expandWithSynonyms(String query) {
        List<String> expanded = new ArrayList<>();
        String lowerQuery = query.toLowerCase();

        for (Map.Entry<String, String[]> entry : synonymDictionary.entrySet()) {
            String keyword = entry.getKey();
            String[] synonyms = entry.getValue();
            if (synonyms == null || synonyms.length == 0) continue;

            if (lowerQuery.contains(keyword)) {
                for (String synonym : synonyms) {
                    expanded.add(query.replaceAll("(?i)" + Pattern.quote(keyword), synonym));
                }
                for (String synonym : synonyms) {
                    expanded.add(query + " " + synonym);
                }
            }
        }

        return expanded;
    }

    /**
     * 添加领域限定词扩展查询
     */
    private List<String> expandWithDomainQualifiers(String query) {
        List<String> expanded = new ArrayList<>();

        for (String qualifier : domainQualifiers) {
            if (!query.contains(qualifier)) {
                expanded.add(query + " " + qualifier);
            }
        }

        return expanded;
    }

    /**
     * 生成 Padding 查询（用于并行检索）
     *
     * @param query 原始查询
     * @return 扩展查询列表
     */
    public List<String> generatePaddingQueries(String query) {
        List<String> paddingQueries = new ArrayList<>();

        if (config == null || !config.isEnabled() || query == null || query.isBlank()) {
            return paddingQueries;
        }

        // 1. 添加疑问前缀
        String[] prefixes = {"如何", "怎么", "怎样", "什么"};
        for (String prefix : prefixes) {
            if (!query.startsWith(prefix)) {
                paddingQueries.add(prefix + query);
            }
        }

        // 2. 提取关键词两两组合
        List<String> keywords = extractKeywords(query);
        if (keywords.size() > 1) {
            for (int i = 0; i < keywords.size(); i++) {
                for (int j = i + 1; j < keywords.size(); j++) {
                    paddingQueries.add(keywords.get(i) + "和" + keywords.get(j));
                }
            }
        }

        // 3. 添加解决方案后缀
        String[] suffixes = {"怎么办", "如何解决", "用什么"};
        for (String suffix : suffixes) {
            if (!query.contains(suffix)) {
                paddingQueries.add(query + suffix);
            }
        }

        return paddingQueries.stream().limit(config.getPaddingCount()).toList();
    }

    /**
     * 从查询中提取关键词
     */
    private List<String> extractKeywords(String query) {
        List<String> keywords = new ArrayList<>();
        Pattern pattern = Pattern.compile("[\\s,，.。!?！?]+");
        String[] parts = pattern.split(query);

        for (String part : parts) {
            if (part.trim().length() >= 2) {
                keywords.add(part.trim());
            }
        }

        return keywords;
    }

    /**
     * 清理查询文本
     */
    public String cleanQuery(String query) {
        if (query == null) {
            return "";
        }
        String cleaned = query.replaceAll("\\s+", " ").trim();
        cleaned = cleaned.replaceAll("[^\\u4e00-\\u9fa5a-zA-Z0-9\\s]", "");
        return cleaned.trim();
    }
}
