package com.springairag.core.retrieval.fulltext;

import com.springairag.core.config.RagProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 全文检索策略工厂
 *
 * <p>支持两种模式：
 * <ul>
 *   <li>固定策略：通过 {@code rag.retrieval.fulltext-strategy} 配置选择单一策略</li>
 *   <li>自动策略（默认）：根据语言检测自动选择最佳策略</li>
 * </ul>
 *
 * <p>自动策略降级链：
 * <ul>
 *   <li>中文：jieba FTS → pg_trgm → none</li>
 *   <li>英文/其他：English FTS → pg_trgm → none</li>
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
    
    // 缓存各语言的 Provider
    private volatile PgJiebaFulltextProvider jiebaProvider;
    private volatile PgEnglishFtsProvider englishProvider;
    private volatile PgTrgmFulltextProvider trgmProvider;
    
    public FulltextSearchProviderFactory(JdbcTemplate jdbcTemplate, RagProperties ragProperties) {
        this.jdbcTemplate = jdbcTemplate;
        this.configuredStrategy = ragProperties.getRetrieval().getFulltextStrategy();
        this.capabilities = new SearchCapabilities(jdbcTemplate);
        
        // 初始化各语言的 Provider
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
     * 获取指定语言的全文检索 Provider
     *
     * @param lang 检测到的语言
     * @return 对应语言的全文检索 Provider
     */
    public FulltextSearchProvider getProvider(QueryLang lang) {
        if ("none".equals(configuredStrategy)) {
            return new NoOpFulltextSearchProvider();
        }
        
        if ("auto".equals(configuredStrategy) || !isExplicitStrategy()) {
            return autoDetectForLang(lang);
        }
        
        // 固定策略配置
        return getFixedProvider();
    }
    
    /**
     * 根据语言自动选择最佳 Provider
     */
    private FulltextSearchProvider autoDetectForLang(QueryLang lang) {
        if (lang == QueryLang.ZH) {
            // 中文降级链：jieba → trgm → none
            if (capabilities.enableChineseFts() && jiebaProvider.isAvailable()) {
                log.debug("Auto-selected provider for ZH: pg_jieba");
                return jiebaProvider;
            }
            if (capabilities.enableTrgm() && trgmProvider.isAvailable()) {
                log.debug("Auto-selected provider for ZH: pg_trgm (fallback)");
                return trgmProvider;
            }
            log.debug("No fulltext provider available for ZH, using NoOp");
            return new NoOpFulltextSearchProvider();
        } else {
            // 英文降级链：english FTS → trgm → none
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
     * 自动选择最佳 Provider（不指定语言，优先选择 pg_jieba）
     */
    private FulltextSearchProvider autoDetectBest() {
        // 优先检查 jieba（中文场景）
        if (capabilities.enableChineseFts() && jiebaProvider.isAvailable()) {
            log.debug("Auto-selected best provider: pg_jieba");
            return jiebaProvider;
        }
        // 然后检查 english FTS
        if (capabilities.enableEnglishFts() && englishProvider.isAvailable()) {
            log.debug("Auto-selected best provider: english_fts");
            return englishProvider;
        }
        // 最后检查 trgm
        if (capabilities.enableTrgm() && trgmProvider.isAvailable()) {
            log.debug("Auto-selected best provider: pg_trgm");
            return trgmProvider;
        }
        log.debug("No fulltext provider available, using NoOp");
        return new NoOpFulltextSearchProvider();
    }

    /**
     * 获取固定策略的 Provider
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
     * 是否为显式策略配置（非 auto）
     */
    private boolean isExplicitStrategy() {
        return "pg_jieba".equals(configuredStrategy) || "pg_trgm".equals(configuredStrategy);
    }
    
    /**
     * 获取当前活跃的全文检索策略（用于兼容旧代码）
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
     * 获取能力探测实例
     */
    public SearchCapabilities getCapabilities() {
        return capabilities;
    }
    
    /**
     * 检测查询语言
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
     * 获取策略显示名称
     */
    public static String getStrategyLabel(String strategy) {
        return STRATEGY_LABELS.getOrDefault(strategy, strategy);
    }
}
