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
 * Query rewriting service
 *
 * <p>Supports three rewriting modes:
 * <ul>
 *   <li>Rule mode — synonym expansion, domain qualifier expansion, padding query</li>
 *   <li>LLM-assisted mode — calls ChatModel to generate rewritten queries</li>
 *   <li>Hybrid mode — both rule and LLM</li>
 * </ul>
 *
 * <p>Synonym dictionary and domain qualifiers are configurable (empty by default, no hardcoded domain).
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
     * Runtime-mutable synonym dictionary (higher priority than config file).
     * Maps keywords to arrays of synonyms.
     */
    private Map<String, String[]> synonymDictionary = Collections.emptyMap();

    /**
     * Runtime-mutable domain qualifiers (higher priority than config file).
     * Qualifiers are appended to queries to narrow the domain.
     */
    private List<String> domainQualifiers = Collections.emptyList();

    /**
     * No-arg constructor (for Spring framework compatibility).
     */
    public QueryRewritingService() {
    }

    /**
     * Single-parameter constructor (for testing and manual wiring).
     * Note: Use field injection + @PostConstruct in production code.
     */
    public QueryRewritingService(RagProperties ragProperties) {
        this.ragProperties = ragProperties;
        this.chatModel = null;
        init();
    }

    /**
     * Two-parameter constructor (for testing).
     * Note: Use field injection + @PostConstruct in production code.
     */
    public QueryRewritingService(RagProperties ragProperties, ChatModel chatModel) {
        this.ragProperties = ragProperties;
        this.chatModel = chatModel;
        init();
    }

    /**
     * Initialize configuration (called by Spring after field injection, or directly by single-parameter constructor).
     * Uses default configuration even if ragProperties is null, ensuring graceful degradation.
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
     * Set synonym dictionary (runtime override, higher priority than config file).
     */
    public void setSynonymDictionary(Map<String, String[]> dictionary) {
        this.synonymDictionary = dictionary != null ? dictionary : Collections.emptyMap();
    }

    /**
     * Set domain qualifier list (runtime override, higher priority than config file).
     */
    public void setDomainQualifiers(List<String> qualifiers) {
        this.domainQualifiers = qualifiers != null ? qualifiers : Collections.emptyList();
    }

    /**
     * Rewrite query
     *
     * <p>Executes rule rewrite, LLM rewrite, or hybrid mode based on configuration.
     *
     * @param originalQuery the original query
     * @return list of rewritten queries (including original and expanded queries)
     */
    public List<String> rewriteQuery(String originalQuery) {
        if (config == null || !config.isEnabled() || originalQuery == null || originalQuery.isBlank()) {
            return List.of(originalQuery);
        }

        List<String> queries = new ArrayList<>();
        queries.add(originalQuery);

        // 1. Synonym expansion
        queries.addAll(expandWithSynonyms(originalQuery));

        // 2. Domain qualifier expansion
        queries.addAll(expandWithDomainQualifiers(originalQuery));

        // 3. LLM-assisted rewrite (optional)
        if (config.isLlmEnabled()) {
            queries.addAll(llmRewrite(originalQuery));
        }

        // Deduplicate
        return queries.stream().distinct().toList();
    }

    /**
     * LLM-assisted query rewriting
     *
     * <p>Calls ChatModel to rewrite the user query into multiple expressions, improving search recall.
     * Silently degrades on failure; rule rewriting results are still returned.
     *
     * @param originalQuery the original query
     * @return list of LLM-generated rewritten queries
     */
    public List<String> llmRewrite(String originalQuery) {
        if (chatModel == null) {
            log.warn("LLM rewrite enabled but ChatModel not configured, skipping");
            return List.of();
        }

        try {
            String prompt = buildRewritePrompt(originalQuery);
            String response = callLlm(prompt);
            if (response == null || response.isBlank()) {
                log.warn("LLM returned empty response");
                return List.of();
            }

            List<String> rewritten = parseRewriteResponse(response, originalQuery);
            log.info("LLM rewrite: \"{}\" → {} results: {}",
                    originalQuery, rewritten.size(),
                    rewritten.size() > 3 ? rewritten.subList(0, 3) + "..." : rewritten);
            return rewritten.stream().limit(config.getLlmMaxRewrites()).toList();

        } catch (Exception e) { // Resilience: LLM failure, fallback to rule mode
            log.error("LLM rewrite failed, falling back to rule mode: {}", e.getMessage());
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
     * Expand query using synonym dictionary.
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
     * Expand query with domain qualifiers.
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
     * Generate padding queries for parallel retrieval.
     *
     * @param query the original query
     * @return list of expanded queries
     */
    public List<String> generatePaddingQueries(String query) {
        List<String> paddingQueries = new ArrayList<>();

        if (config == null || !config.isEnabled() || query == null || query.isBlank()) {
            return paddingQueries;
        }

        // 1. Add interrogative prefixes
        String[] prefixes = {"如何", "怎么", "怎样", "什么"};
        for (String prefix : prefixes) {
            if (!query.startsWith(prefix)) {
                paddingQueries.add(prefix + query);
            }
        }

        // 2. Extract keywords and pair them
        List<String> keywords = extractKeywords(query);
        if (keywords.size() > 1) {
            for (int i = 0; i < keywords.size(); i++) {
                for (int j = i + 1; j < keywords.size(); j++) {
                    paddingQueries.add(keywords.get(i) + "和" + keywords.get(j));
                }
            }
        }

        // 3. Add solution suffixes
        String[] suffixes = {"怎么办", "如何解决", "用什么"};
        for (String suffix : suffixes) {
            if (!query.contains(suffix)) {
                paddingQueries.add(query + suffix);
            }
        }

        return paddingQueries.stream().limit(config.getPaddingCount()).toList();
    }

    /**
     * Extract keywords from query.
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
     * Clean query text.
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
