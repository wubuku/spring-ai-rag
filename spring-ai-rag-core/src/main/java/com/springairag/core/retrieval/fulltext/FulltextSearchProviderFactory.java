package com.springairag.core.retrieval.fulltext;

import com.springairag.core.config.RagProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Full-text search strategy factory.
 *
 * <p>Supports two modes:
 * <ul>
 *   <li>Fixed strategy: selects a single strategy via {@code rag.retrieval.fulltext-strategy} config</li>
 *   <li>Auto strategy (default): automatically selects the best strategy based on language detection</li>
 * </ul>
 *
 * <p>Auto strategy fallback chain:
 * <ul>
 *   <li>Chinese: jieba FTS → pg_trgm → none</li>
 *   <li>English/other: English FTS → pg_trgm → none</li>
 * </ul>
 */
@Component
public class FulltextSearchProviderFactory {
    
    private static final Logger log = LoggerFactory.getLogger(FulltextSearchProviderFactory.class);
    
    private static final Map<String, String> STRATEGY_LABELS = Map.of(
            "auto", "auto-detect",
            "pg_jieba", "pg_jieba (Chinese segmentation)",
            "pg_trgm", "pg_trgm (trigram matching)",
            "none", "disabled (vector-only)"
    );
    
    private final JdbcTemplate jdbcTemplate;
    private final String configuredStrategy;
    private final SearchCapabilities capabilities;
    
    // Cached per-language providers (interface type, supports test injection)
    private volatile FulltextSearchProvider jiebaProvider;
    private volatile FulltextSearchProvider englishProvider;
    private volatile FulltextSearchProvider trgmProvider;
    
    @Autowired
    public FulltextSearchProviderFactory(JdbcTemplate jdbcTemplate, RagProperties ragProperties) {
        this(jdbcTemplate, ragProperties.getRetrieval().getFulltextStrategy(),
                new SearchCapabilities(jdbcTemplate));
        log.info("FulltextSearchProviderFactory initialized: strategy={}, capabilities={}",
                configuredStrategy, capabilities);
    }
    
    /**
     * No-arg constructor for test contexts where JdbcTemplate is unavailable.
     * Creates a fully-disabled factory (all providers return NoOpFulltextSearchProvider).
     */
    public FulltextSearchProviderFactory() {
        log.info("CONSTRUCTOR: FulltextSearchProviderFactory() - NO-ARG CONSTRUCTOR USED");
        this.configuredStrategy = "none";
        this.capabilities = null;
        this.jdbcTemplate = null;
    }
    
    /**
     * Test constructor (injects SearchCapabilities for precise capability detection control)
     */
    public FulltextSearchProviderFactory(JdbcTemplate jdbcTemplate, String configuredStrategy,
                                         SearchCapabilities capabilities) {
        this.jdbcTemplate = jdbcTemplate;
        this.configuredStrategy = configuredStrategy;
        this.capabilities = capabilities;
        
        // Initialize providers for each language
        initProviders();
    }
    
    private void initProviders() {
        this.jiebaProvider = new PgJiebaFulltextProvider(jdbcTemplate);
        this.englishProvider = new PgEnglishFtsProvider(jdbcTemplate);
        this.trgmProvider = new PgTrgmFulltextProvider(jdbcTemplate);
        
        log.info("Fulltext search factory initialized with strategy={}, capabilities: {}", 
                configuredStrategy, capabilities);
    }

    /**
     * Fully controllable constructor (for testing with spy/fake providers)
     */
    public FulltextSearchProviderFactory(JdbcTemplate jdbcTemplate, String configuredStrategy,
                                        SearchCapabilities capabilities,
                                        FulltextSearchProvider jiebaProvider,
                                        FulltextSearchProvider englishProvider,
                                        FulltextSearchProvider trgmProvider) {
        this.jdbcTemplate = jdbcTemplate;
        this.configuredStrategy = configuredStrategy;
        this.capabilities = capabilities;
        this.jiebaProvider = jiebaProvider;
        this.englishProvider = englishProvider;
        this.trgmProvider = trgmProvider;
    }
    
    /**
     * Get full-text search provider for specified language
     *
     * @param lang detected language
     * @return full-text search provider for the corresponding language
     */
    public FulltextSearchProvider getProvider(QueryLang lang) {
        log.info("GET_PROVIDER called: configuredStrategy={}, lang={}", configuredStrategy, lang);
        if ("none".equals(configuredStrategy)) {
            return new NoOpFulltextSearchProvider();
        }
        
        if ("auto".equals(configuredStrategy) || !isExplicitStrategy()) {
            return autoDetectForLang(lang);
        }
        
        // Fixed strategy configuration
        return getFixedProvider();
    }
    
    /**
     * Auto-select best Provider based on language.
     */
    private FulltextSearchProvider autoDetectForLang(QueryLang lang) {
        if (lang == QueryLang.ZH) {
            // Chinese fallback chain: jieba → trgm → none
            if (capabilities.enableChineseFts() && jiebaProvider.isAvailable()) {
                return jiebaProvider;
            }
            if (capabilities.enableTrgm() && trgmProvider.isAvailable()) {
                return trgmProvider;
            }
            return new NoOpFulltextSearchProvider();
        } else {
            // English fallback chain: english FTS → trgm → none
            if (capabilities.enableEnglishFts() && englishProvider.isAvailable()) {
                log.debug("Auto-selected provider for EN: english_fts");
                return englishProvider;
            }
            if (capabilities.enableTrgm() && trgmProvider.isAvailable()) {
                log.debug("Auto-selected provider for EN: pg_trgm (fallback)");
                return trgmProvider;
            }
            log.debug("No fulltext provider available for EN, using NoOp");
            return new NoOpFulltextSearchProvider();
        }
    }

    /**
     * Auto-select best Provider (no language specified, prefers pg_jieba).
     */
    private FulltextSearchProvider autoDetectBest() {
        // Prefer jieba (Chinese scenarios)
        if (capabilities.enableChineseFts() && jiebaProvider.isAvailable()) {
            log.debug("Auto-selected best provider: pg_jieba");
            return jiebaProvider;
        }
        // Then check english FTS
        if (capabilities.enableEnglishFts() && englishProvider.isAvailable()) {
            log.debug("Auto-selected best provider: english_fts");
            return englishProvider;
        }
        // Finally check trgm
        if (capabilities.enableTrgm() && trgmProvider.isAvailable()) {
            log.debug("Auto-selected best provider: pg_trgm");
            return trgmProvider;
        }
        log.debug("No fulltext provider available, using NoOp");
        return new NoOpFulltextSearchProvider();
    }

    /**
     * Get provider for fixed strategy
     */
    private FulltextSearchProvider getFixedProvider() {
        return switch (configuredStrategy) {
            case "pg_jieba" -> {
                if (!jiebaProvider.isAvailable()) {
                    throw new IllegalStateException(
                            "fulltext-strategy=pg_jieba but pg_jieba extension is not available. " +
                            "Install pg_jieba: CREATE EXTENSION pg_jieba;");
                }
                log.info("Full-text search provider: pg_jieba (explicitly configured)");
                yield jiebaProvider;
            }
            case "pg_trgm" -> {
                if (!trgmProvider.isAvailable()) {
                    throw new IllegalStateException(
                            "fulltext-strategy=pg_trgm but pg_trgm extension is not available. " +
                            "Install pg_trgm: CREATE EXTENSION pg_trgm;");
                }
                log.info("Full-text search provider: pg_trgm (explicitly configured)");
                yield trgmProvider;
            }
            default -> {
                log.warn("Unknown strategy={}, falling back to auto-detect", configuredStrategy);
                yield autoDetectForLang(QueryLang.EN_OR_OTHER);
            }
        };
    }
    
    /**
     * Whether this is an explicit strategy configuration (not auto)
     */
    private boolean isExplicitStrategy() {
        return "pg_jieba".equals(configuredStrategy) || "pg_trgm".equals(configuredStrategy);
    }
    
    /**
     * Get currently active full-text search strategy (for legacy code compatibility)
     */
    public FulltextSearchProvider getProvider() {
        if ("none".equals(configuredStrategy)) {
            return new NoOpFulltextSearchProvider();
        }
        if ("auto".equals(configuredStrategy) || !isExplicitStrategy()) {
            return autoDetectBest();
        }
        return getFixedProvider();
    }
    
    /**
     * Get capability detection instance
     */
    public SearchCapabilities getCapabilities() {
        return capabilities;
    }
    
    /**
     * Detect query language
     */
    public QueryLang detectLang(String text) {
        if (text == null || text.isBlank()) {
            return QueryLang.EN_OR_OTHER;
        }
        return text.codePoints().anyMatch(cp -> {
            Character.UnicodeBlock block = Character.UnicodeBlock.of(cp);
            return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B;
        }) ? QueryLang.ZH : QueryLang.EN_OR_OTHER;
    }
    
    /**
     * Get strategy display name
     */
    public static String getStrategyLabel(String strategy) {
        return STRATEGY_LABELS.getOrDefault(strategy, strategy);
    }
}
