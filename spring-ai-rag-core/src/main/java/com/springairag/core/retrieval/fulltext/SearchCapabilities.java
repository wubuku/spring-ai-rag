package com.springairag.core.retrieval.fulltext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Set;

/**
 * 数据库全文检索能力探测
 *
 * <p>运行时探测数据库扩展和索引，决定启用哪些全文检索策略。
 * 遵循"能力驱动"原则：只有在"扩展已安装 + 索引存在"时才启用对应策略，
 * 避免无索引的 LIKE/ILIKE 全表扫描。
 */
@Component
public class SearchCapabilities {
    
    private static final Logger log = LoggerFactory.getLogger(SearchCapabilities.class);
    
    private final JdbcTemplate jdbcTemplate;
    
    // 扩展检测结果
    private boolean hasPgVector;
    private boolean hasPgTrgm;
    private boolean hasJieba;
    private boolean hasZhparser;
    
    // 索引检测结果
    private boolean hasZhIndex;     // jieba tsvector GIN 索引
    private boolean hasEnIndex;     // english tsvector GIN 索引
    private boolean hasTrgmIndex;   // trigram GIN 索引
    
    /**
     * 主要构造函数（Spring DI 使用）
     */
    public SearchCapabilities(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, true);
    }
    
    /**
     * No-arg constructor for test contexts where JdbcTemplate is unavailable.
     * All capabilities are disabled.
     */
    public SearchCapabilities() {
        this.jdbcTemplate = null;
        this.hasPgVector = false;
        this.hasPgTrgm = false;
        this.hasJieba = false;
        this.hasZhparser = false;
        this.hasZhIndex = false;
        this.hasEnIndex = false;
        this.hasTrgmIndex = false;
    }
    
    /**
     * 测试用构造函数
     * @param init 是否执行数据库探测（测试时传 false，由测试完全控制各字段值）
     */
    public SearchCapabilities(JdbcTemplate jdbcTemplate, boolean init) {
        this.jdbcTemplate = jdbcTemplate;
        if (init) {
            detectExtensions();
            detectIndexes();
            logCapabilities();
        }
    }
    
    @PostConstruct
    public void init() {
        if (jdbcTemplate == null) {
            log.debug("SearchCapabilities initialized with no JdbcTemplate; all capabilities disabled");
            return;
        }
        detectExtensions();
        detectIndexes();
        logCapabilities();
    }
    
    /**
     * 探测已安装的扩展
     */
    private void detectExtensions() {
        try {
            List<String> extensions = jdbcTemplate.queryForList(
                    "SELECT extname FROM pg_extension " +
                    "WHERE extname IN ('vector', 'pg_trgm', 'pg_jieba', 'zhparser')",
                    String.class);
            
            Set<String> extSet = Set.copyOf(extensions);
            hasPgVector = extSet.contains("vector");
            hasPgTrgm = extSet.contains("pg_trgm");
            hasJieba = extSet.contains("pg_jieba");
            hasZhparser = extSet.contains("zhparser");
            
            log.info("Extensions detected: vector={}, pg_trgm={}, pg_jieba={}, zhparser={}",
                    hasPgVector, hasPgTrgm, hasJieba, hasZhparser);
        } catch (Exception e) {
            log.warn("Failed to detect extensions: {}", e.getMessage());
            hasPgVector = hasPgTrgm = hasJieba = hasZhparser = false;
        }
    }
    
    /**
     * 探测全文检索索引
     */
    private void detectIndexes() {
        try {
            // 检测 jieba tsvector GIN 索引（search_vector_zh 列）
            hasZhIndex = Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                    "SELECT EXISTS (" +
                    "SELECT 1 FROM pg_indexes " +
                    "WHERE schemaname = 'public' " +
                    "  AND tablename = 'rag_embeddings' " +
                    "  AND indexdef ILIKE '%search_vector_zh%gin%')",
                    Boolean.class));
            
            // 检测 english tsvector GIN 索引（search_vector_en 列）
            hasEnIndex = Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                    "SELECT EXISTS (" +
                    "SELECT 1 FROM pg_indexes " +
                    "WHERE schemaname = 'public' " +
                    "  AND tablename = 'rag_embeddings' " +
                    "  AND indexdef ILIKE '%search_vector_en%gin%')",
                    Boolean.class));
            
            // 检测 trigram GIN 索引
            hasTrgmIndex = Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                    "SELECT EXISTS (" +
                    "SELECT 1 FROM pg_indexes " +
                    "WHERE schemaname = 'public' " +
                    "  AND tablename = 'rag_embeddings' " +
                    "  AND indexdef ILIKE '%gin_trgm_ops%')",
                    Boolean.class));
            
            log.info("Indexes detected: zh_index={}, en_index={}, trgm_index={}",
                    hasZhIndex, hasEnIndex, hasTrgmIndex);
        } catch (Exception e) {
            log.warn("Failed to detect indexes: {}", e.getMessage());
            hasZhIndex = hasEnIndex = hasTrgmIndex = false;
        }
    }
    
    private void logCapabilities() {
        log.info("Fulltext search capabilities: " +
                "jieba_fts={} (ext={}, index={}), " +
                "english_fts={} (index={}), " +
                "trigram={} (ext={}, index={})",
                enableChineseFts(), hasJieba, hasZhIndex,
                enableEnglishFts(), hasEnIndex,
                enableTrgm(), hasPgTrgm, hasTrgmIndex);
    }
    
    // ========== Public API ==========
    
    /**
     * 是否启用中文 FTS（pg_jieba）
     */
    public boolean enableChineseFts() {
        return hasJieba && hasZhIndex;
    }
    
    /**
     * 是否启用英文 FTS（内置 english 配置）
     */
    public boolean enableEnglishFts() {
        return hasEnIndex;
    }
    
    /**
     * 是否启用 trigram 模糊搜索
     */
    public boolean enableTrgm() {
        return hasPgTrgm && hasTrgmIndex;
    }
    
    // ========== Getters ==========
    
    public boolean hasPgVector() { return hasPgVector; }
    public boolean hasPgTrgm() { return hasPgTrgm; }
    public boolean hasJieba() { return hasJieba; }
    public boolean hasZhparser() { return hasZhparser; }
    public boolean hasZhIndex() { return hasZhIndex; }
    public boolean hasEnIndex() { return hasEnIndex; }
    public boolean hasTrgmIndex() { return hasTrgmIndex; }
    
    // ========== Test Setters (public for test access) ==========
    
    public void setHasPgVector(boolean v) { this.hasPgVector = v; }
    public void setHasPgTrgm(boolean v) { this.hasPgTrgm = v; }
    public void setHasJieba(boolean v) { this.hasJieba = v; }
    public void setHasZhparser(boolean v) { this.hasZhparser = v; }
    public void setHasZhIndex(boolean v) { this.hasZhIndex = v; }
    public void setHasEnIndex(boolean v) { this.hasEnIndex = v; }
    public void setHasTrgmIndex(boolean v) { this.hasTrgmIndex = v; }
}
