package com.springairag.core.retrieval;

import com.springairag.core.config.RagProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 查询改写服务
 *
 * <p>支持同义词扩展、领域限定词扩展、Padding 查询生成。
 * 同义词词典和领域限定词均可通过配置自定义（默认空，不含领域硬编码）。
 *
 * <p>设计原则：通用 RAG 服务不含领域知识，具体领域通过配置注入。
 */
@Service
public class QueryRewritingService {

    private static final Logger log = LoggerFactory.getLogger(QueryRewritingService.class);

    private final RagProperties.QueryRewrite config;

    /**
     * 运行时可变的同义词词典（优先级高于配置文件）
     */
    private Map<String, String[]> synonymDictionary;

    /**
     * 运行时可变的领域限定词（优先级高于配置文件）
     */
    private List<String> domainQualifiers;

    public QueryRewritingService(RagProperties ragProperties) {
        this.config = ragProperties.getQueryRewrite();
        this.synonymDictionary = config.getSynonymDictionary();
        this.domainQualifiers = config.getDomainQualifiers();
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

        // 去重
        return queries.stream().distinct().toList();
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
