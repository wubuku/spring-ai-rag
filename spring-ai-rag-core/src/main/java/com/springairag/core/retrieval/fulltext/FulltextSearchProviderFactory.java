package com.springairag.core.retrieval.fulltext;

import com.springairag.core.config.RagProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 全文检索策略工厂
 *
 * <p>通过 {@code rag.retrieval.fulltext-strategy} 配置选择策略：
 * <ul>
 *   <li>{@code auto}（默认）：自动检测 pg_jieba → pg_trgm → none</li>
 *   <li>{@code pg_jieba}：强制使用 jieba 分词，不可用时启动失败</li>
 *   <li>{@code pg_trgm}：强制使用三元组匹配，不可用时启动失败</li>
 *   <li>{@code none}：禁用全文检索，纯向量模式</li>
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

    private final FulltextSearchProvider activeProvider;

    public FulltextSearchProviderFactory(JdbcTemplate jdbcTemplate, RagProperties ragProperties) {
        String strategy = ragProperties.getRetrieval().getFulltextStrategy();
        this.activeProvider = selectProvider(jdbcTemplate, strategy);
    }

    private FulltextSearchProvider selectProvider(JdbcTemplate jdbc, String strategy) {
        return switch (strategy) {
            case "none" -> {
                log.info("Full-text search explicitly disabled (strategy=none)");
                yield new NoOpFulltextSearchProvider();
            }
            case "pg_jieba" -> {
                PgJiebaFulltextProvider p = new PgJiebaFulltextProvider(jdbc);
                if (!p.isAvailable()) {
                    throw new IllegalStateException(
                            "fulltext-strategy=pg_jieba but pg_jieba extension is not available. " +
                            "Install pg_jieba: CREATE EXTENSION pg_jieba;");
                }
                log.info("Full-text search provider: pg_jieba (explicitly configured)");
                yield p;
            }
            case "pg_trgm" -> {
                PgTrgmFulltextProvider p = new PgTrgmFulltextProvider(jdbc);
                if (!p.isAvailable()) {
                    throw new IllegalStateException(
                            "fulltext-strategy=pg_trgm but pg_trgm extension is not available. " +
                            "Install pg_trgm: CREATE EXTENSION pg_trgm;");
                }
                log.info("Full-text search provider: pg_trgm (explicitly configured)");
                yield p;
            }
            default -> autoDetect(jdbc);
        };
    }

    private FulltextSearchProvider autoDetect(JdbcTemplate jdbc) {
        PgJiebaFulltextProvider jieba = new PgJiebaFulltextProvider(jdbc);
        if (jieba.isAvailable()) {
            log.info("Full-text search provider: pg_jieba (auto-detected)");
            return jieba;
        }

        PgTrgmFulltextProvider trgm = new PgTrgmFulltextProvider(jdbc);
        if (trgm.isAvailable()) {
            log.info("Full-text search provider: pg_trgm (auto-detected)");
            return trgm;
        }

        log.warn("No full-text search provider available — vector-only mode. " +
                "Install pg_jieba or pg_trgm, or set rag.retrieval.fulltext-strategy=none to suppress this warning.");
        return new NoOpFulltextSearchProvider();
    }

    /**
     * 获取当前活跃的全文检索策略
     */
    public FulltextSearchProvider getProvider() {
        return activeProvider;
    }

    /**
     * 获取策略显示名称
     */
    public static String getStrategyLabel(String strategy) {
        return STRATEGY_LABELS.getOrDefault(strategy, strategy);
    }
}
