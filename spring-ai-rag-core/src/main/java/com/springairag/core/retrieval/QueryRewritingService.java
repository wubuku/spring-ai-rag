package com.springairag.core.retrieval;

import com.springairag.core.config.RagProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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

    private final RagProperties.QueryRewrite config;
    private final ChatModel chatModel;

    /**
     * 运行时可变的同义词词典（优先级高于配置文件）
     */
    private Map<String, String[]> synonymDictionary;

    /**
     * 运行时可变的领域限定词（优先级高于配置文件）
     */
    private List<String> domainQualifiers;

    public QueryRewritingService(RagProperties ragProperties) {
        this(ragProperties, null);
    }

    public QueryRewritingService(RagProperties ragProperties,
                                  @Autowired(required = false) ChatModel chatModel) {
        this.config = ragProperties.getQueryRewrite();
        this.synonymDictionary = config.getSynonymDictionary();
        this.domainQualifiers = config.getDomainQualifiers();
        this.chatModel = chatModel;
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
        if (!config.isEnabled() || originalQuery == null || originalQuery.isBlank()) {
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
            int maxRewrites = config.getLlmMaxRewrites();
            String prompt = String.format(
                    "你是一个查询改写助手。将用户查询改写为 %d 个不同的表述，用于提升搜索召回率。" +
                    "要求：\n" +
                    "1. 每行一条改写结果\n" +
                    "2. 保持原始意图不变\n" +
                    "3. 使用不同的表达方式、同义词或句式\n" +
                    "4. 不要编号，不要解释\n\n" +
                    "原始查询：%s",
                    maxRewrites, originalQuery);

            ChatClient client = ChatClient.builder(chatModel).build();
            String response = client.prompt(prompt).call().content();

            if (response == null || response.isBlank()) {
                log.warn("LLM 返回空响应");
                return List.of();
            }

            List<String> rewritten = new ArrayList<>();
            for (String line : response.split("\n")) {
                String cleaned = line.trim()
                        .replaceAll("^[-*•\\d.]+\\s*", "")  // 去除列表标记
                        .trim();
                if (!cleaned.isEmpty() && !cleaned.equals(originalQuery)) {
                    rewritten.add(cleaned);
                }
            }

            log.info("LLM 改写: \"{}\" → {} 条: {}",
                    originalQuery, rewritten.size(),
                    rewritten.size() > 3 ? rewritten.subList(0, 3) + "..." : rewritten);

            return rewritten.stream().limit(maxRewrites).toList();

        } catch (Exception e) {
            log.error("LLM 改写失败，降级到规则模式: {}", e.getMessage());
            return List.of();
        }
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

        if (!config.isEnabled() || query == null || query.isBlank()) {
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
