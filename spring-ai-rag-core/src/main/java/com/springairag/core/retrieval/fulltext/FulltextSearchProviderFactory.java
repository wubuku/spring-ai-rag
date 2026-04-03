package com.springairag.core.retrieval.fulltext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 全文检索策略工厂
 *
 * <p>根据数据库扩展可用性自动选择最佳全文检索策略：
 * <ol>
 *   <li>pg_jieba（中文分词，精度最高）</li>
 *   <li>pg_trgm（三元组模糊匹配，降级方案）</li>
 *   <li>无可用策略时返回 {@link NoOpFulltextSearchProvider}</li>
 * </ol>
 */
@Component
public class FulltextSearchProviderFactory {

    private static final Logger log = LoggerFactory.getLogger(FulltextSearchProviderFactory.class);

    private final FulltextSearchProvider activeProvider;

    public FulltextSearchProviderFactory(JdbcTemplate jdbcTemplate) {
        PgJiebaFulltextProvider jieba = new PgJiebaFulltextProvider(jdbcTemplate);
        PgTrgmFulltextProvider trgm = new PgTrgmFulltextProvider(jdbcTemplate);

        if (jieba.isAvailable()) {
            log.info("Selected full-text search provider: pg_jieba (Chinese segmentation)");
            this.activeProvider = jieba;
        } else if (trgm.isAvailable()) {
            log.info("Selected full-text search provider: pg_trgm (trigram matching)");
            this.activeProvider = trgm;
        } else {
            log.warn("No full-text search provider available — falling back to vector-only search. " +
                    "Install pg_jieba or pg_trgm for hybrid search support.");
            this.activeProvider = new NoOpFulltextSearchProvider();
        }
    }

    /**
     * 获取当前活跃的全文检索策略
     */
    public FulltextSearchProvider getProvider() {
        return activeProvider;
    }

    /**
     * 获取所有可用策略列表（用于健康检查和诊断）
     */
    public List<FulltextSearchProvider> getAllProviders(JdbcTemplate jdbcTemplate) {
        return List.of(
                new PgJiebaFulltextProvider(jdbcTemplate),
                new PgTrgmFulltextProvider(jdbcTemplate)
        );
    }
}
